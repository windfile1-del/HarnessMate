package dev.wind.agent.coding

import dev.wind.agent.coding.parsers.Diagnostics
import dev.wind.agent.isBlankJava
import dev.wind.agent.isNotBlankJava

/**
 * Turns a build log into a bounded list of things that are actually wrong.
 *
 * The problem this solves is not parsing for its own sake: it is that a Gradle
 * failure is ten thousand lines, of which three matter, and the model pays for
 * all of them on every repair cycle. Structured diagnostics are also what makes
 * "did the last repair change anything" answerable — string logs cannot be
 * diffed for progress, diagnostic sets can.
 *
 * When nothing matches, the tail of the raw output is kept rather than dropped:
 * a harness that quietly discards the reason it failed is worse than one that
 * admits it did not understand.
 */
object DiagnosticPipeline {

    const val MAX_ERRORS = 40
    const val MAX_WARNINGS = 10
    const val RAW_TAIL_CHARS = 4_000
    private const val MAX_LINE_CHARS = 500

    /**
     * Errors that were present in [previous] and are gone from [report].
     *
     * Shown to the model for the same reason a compiler highlights fixed
     * warnings: it is the cheapest possible confirmation that the last edit did
     * something, and without it a repair cycle that is working looks identical to
     * one that is not.
     */
    fun resolvedSince(previous: List<Diagnostic>, report: DiagnosticReport): List<Diagnostic> {
        if (previous.isEmpty()) {
            return emptyList()
        }
        val live = report.errors.map { it.key() }.toSet()
        return previous.filter { it.key() !in live }
    }

    /**
     * Turns one command's output into diagnostics.
     *
     * [launchError] and [timedOut] are execution metadata rather than build
     * output, and they are the ones that matter most for triage: a command the
     * backend could not start produces neither a useful exit code nor any
     * stderr, which parses as "no errors" and would then be reported as a pass.
     * Those failures become TOOLCHAIN/TIMEOUT diagnostics here so the retry
     * controller can refuse to treat them as code problems — or, worse, as
     * success.
     */
    fun parse(
        stdout: String,
        stderr: String,
        exitCode: Int?,
        launchError: String? = null,
        timedOut: Boolean = false
    ): DiagnosticReport {
        val errors = linkedMapOf<String, Diagnostic>()
        val warnings = linkedMapOf<String, Diagnostic>()
        // Reserve room for execution metadata. A command can emit a full
        // parser-sized error stream and also fail to launch/time out; metadata
        // must not push the bounded report past MAX_ERRORS.
        val metadataSlots = (if (launchError?.isNotBlankJava() == true) 1 else 0) +
            (if (timedOut) 1 else 0)
        val parserErrorLimit = (MAX_ERRORS - metadataSlots).coerceAtLeast(0)

        fun consider(diagnostic: Diagnostic?): Boolean {
            val item = diagnostic ?: return false
            val bucket = if (item.severity == DiagnosticSeverity.WARNING) warnings else errors
            if (bucket.size >= if (bucket === warnings) MAX_WARNINGS else parserErrorLimit) {
                return true
            }
            // containsKey rather than putIfAbsent: the latter is a Java-8 default
            // method, and the app's floor is API 23 where it does not exist.
            val key = item.key()
            if (!bucket.containsKey(key)) {
                bucket[key] = item
            }
            return true
        }

        for (chunk in listOf(stdout, stderr)) {
            if (chunk.isEmpty()) {
                continue
            }
            for (rawLine in chunk.lineSequence()) {
                val line = if (rawLine.length > MAX_LINE_CHARS) rawLine.substring(0, MAX_LINE_CHARS) else rawLine
                if (line.isBlankJava()) {
                    continue
                }
                // First match wins. A line is ONE diagnostic: running every
                // parser over it turned "FAILED tests/test_api.py::test_login"
                // into a pytest failure AND a generic one, so the count the model
                // was shown overstated the number of things wrong with the code
                // and the "did the error count drop" signal became noise.
                if (consider(Diagnostics.parseKotlin(line))) continue
                if (consider(Diagnostics.parseJavac(line))) continue
                if (consider(Diagnostics.parseTest(line))) continue
                if (consider(Diagnostics.parseGradle(line))) continue
                if (consider(Diagnostics.parseGeneric(line))) continue
            }
        }

        val tail = tail(clip(stderr) + "\n" + clip(stdout))
        val truncated = stdout.length + stderr.length > RAW_TAIL_CHARS
        if (launchError != null && launchError.isNotBlankJava()) {
            errors["launch"] = Diagnostic(
                id = "launch:" + Digests.sha256(launchError).take(12),
                severity = DiagnosticSeverity.ERROR,
                category = DiagnosticCategory.TOOLCHAIN,
                path = null,
                line = null,
                column = null,
                code = null,
                message = DiagnosticPathMapper.sanitizeText(launchError),
                source = DiagnosticSource.GENERIC
            )
        }
        if (timedOut) {
            errors["timeout"] = Diagnostic(
                id = "timeout",
                severity = DiagnosticSeverity.ERROR,
                category = DiagnosticCategory.TIMEOUT,
                path = null,
                line = null,
                column = null,
                code = null,
                message = "the check did not finish inside its time limit",
                source = DiagnosticSource.GENERIC
            )
        }
        if (errors.isEmpty() && exitCode != null && exitCode != 0) {
            // A non-zero exit with nothing we recognised is still a failure, and
            // saying only "exit code 1" leaves the model with nothing to act on.
            if (!errors.containsKey("exit")) {
                errors["exit"] = Diagnostic(
                    id = "exit:$exitCode",
                    severity = DiagnosticSeverity.ERROR,
                    category = Diagnostics.categoryOf(tail),
                    path = null,
                    line = null,
                    column = null,
                    code = exitCode.toString(),
                    message = "command failed with exit code $exitCode",
                    source = DiagnosticSource.GENERIC
                )
            }
        }
        return DiagnosticReport(errors.values.toList(), warnings.values.toList(), tail, truncated)
    }

    /**
     * True when every error is one the model cannot fix by editing source.
     *
     * This is the guard that stops "gradle: command not found" from becoming a
     * hundred edits to code that was already correct.
     */
    fun environmentBlocked(report: DiagnosticReport): Boolean {
        if (report.errors.isEmpty()) {
            return false
        }
        return report.errors.all { it.category == DiagnosticCategory.TOOLCHAIN }
    }

    /** The compact rendering that reaches the model instead of the raw log. */
    fun summary(report: DiagnosticReport, newErrors: List<Diagnostic>, resolved: List<Diagnostic>): String {
        val sb = StringBuilder()
        if (report.ok) {
            sb.append("Validation passed. ")
            sb.append(report.warnings.size).append(" warning(s).")
        } else {
            sb.append("Validation failed. ")
            sb.append(report.errors.size).append(" error(s), ")
            sb.append(report.warnings.size).append(" warning(s).")
        }
        val fresh = newErrors.ifEmpty { report.errors }
        if (fresh.isNotEmpty()) {
            sb.append("\n\nNEW:\n")
            for ((index, item) in fresh.take(MAX_ERRORS).withIndex()) {
                sb.append(index + 1).append(". ").append(item.oneLine()).append('\n')
            }
        }
        if (resolved.isNotEmpty()) {
            sb.append("\nRESOLVED SINCE LAST RUN:\n")
            for (item in resolved.take(MAX_ERRORS)) {
                sb.append("- ").append(item.oneLine()).append('\n')
            }
        }
        if (report.errors.isEmpty() && report.rawTail.isNotBlankJava() && !report.ok) {
            sb.append("\nOUTPUT:\n").append(report.rawTail)
        }
        return sb.toString()
    }

    private fun clip(text: String): String =
        if (text.length <= RAW_TAIL_CHARS) text else text.substring(text.length - RAW_TAIL_CHARS)

    private fun tail(text: String): String =
        if (text.length <= RAW_TAIL_CHARS) text else text.substring(text.length - RAW_TAIL_CHARS)
}
