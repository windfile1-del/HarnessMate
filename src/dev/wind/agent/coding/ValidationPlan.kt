package dev.wind.agent.coding

import org.json.JSONArray
import org.json.JSONObject
import dev.wind.agent.isBlankJava
import dev.wind.agent.isNotBlankJava

/**
 * One command the harness has decided is worth running.
 *
 * [argv] is a real argv, never a shell string. The shell layer forbids passing
 * a command string to an interpreter, and a planner that emitted one would be
 * the way around that rule.
 */
data class ValidationCommand(
    val id: String,
    val label: String,
    val argv: List<String>,
    val timeoutMs: Long = 600_000L,
    val network: Boolean = false,
    val cwd: String = "workspace://"
) {
    init {
        require(argv.isNotEmpty()) { "VALIDATION_COMMAND_EMPTY" }
        require(argv.none { it.isBlankJava() }) { "VALIDATION_ARGUMENT_BLANK" }
        require(id.isNotBlankJava() && label.isNotBlankJava())
        require(timeoutMs in 1_000L..dev.wind.agent.runtime.RuntimeProcessSupervisor.MAX_TIMEOUT_MS)
    }

    fun display(): String = argv.joinToString(" ")

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("label", label)
        .put("argv", JSONArray().apply { argv.forEach { put(it) } })
        .put("timeoutMs", timeoutMs)
        .put("network", network)
        .put("cwd", cwd)

    companion object {
        fun fromJson(root: JSONObject): ValidationCommand {
            val argv = mutableListOf<String>()
            val array = root.optJSONArray("argv")
            if (array != null) {
                for (i in 0 until array.length()) {
                    argv.add(array.optString(i, ""))
                }
            }
            return ValidationCommand(
                id = root.getString("id"),
                label = root.optString("label", root.getString("id")),
                argv = argv,
                timeoutMs = root.optLong("timeoutMs", 600_000L),
                network = root.optBoolean("network", false),
                cwd = root.optString("cwd", "workspace://")
            )
        }
    }
}

/**
 * What to run, and honestly how much the harness knows about the project.
 *
 * [fastChecks] run after edits; [finalChecks] run once, immediately before the
 * work is offered for commit. Splitting them is the difference between a phone
 * that stays cool and a phone that runs a full assemble after every keystroke.
 *
 * When no test runner can be found the plan says so ([confidence] LOW, [reason]
 * naming it) instead of inventing a command that will fail for reasons the model
 * will then try to "fix" by editing source.
 */
data class ValidationPlan(
    val fastChecks: List<ValidationCommand> = emptyList(),
    val finalChecks: List<ValidationCommand> = emptyList(),
    val confidence: Confidence,
    val reason: String
) {
    init {
        require(reason.isNotBlankJava()) { "VALIDATION_PLAN_REASON_MISSING" }
        val ids = (fastChecks + finalChecks).map { it.id }
        require(ids.size == ids.toSet().size) { "VALIDATION_PLAN_DUPLICATE_ID" }
    }

    /** True when the harness found nothing it can usefully run. */
    val empty: Boolean get() = finalChecks.isEmpty()

    fun all(): List<ValidationCommand> = fastChecks + finalChecks

    /**
     * A stable identity for the plan.
     *
     * Bound into the verification stamp so that a change of plan (a new test
     * framework appearing, a wrapper arriving) invalidates an older pass rather
     * than silently reusing it.
     */
    fun fingerprint(): String {
        val input = (fastChecks + finalChecks).joinToString(";") {
            JSONArray().put(it.id).put(JSONArray(it.argv)).put(it.cwd).put(it.timeoutMs).put(it.network).toString()
        }
        return Digests.sha256(input)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("fast", JSONArray().apply { fastChecks.forEach { put(it.toJson()) } })
        .put("final", JSONArray().apply { finalChecks.forEach { put(it.toJson()) } })
        .put("confidence", confidence.name)
        .put("reason", reason)

    companion object {
        fun fromJson(root: JSONObject): ValidationPlan {
            fun commands(key: String): List<ValidationCommand> {
                val out = mutableListOf<ValidationCommand>()
                val array = root.optJSONArray(key)
                if (array != null) {
                    for (i in 0 until array.length()) {
                        array.optJSONObject(i)?.let { out.add(ValidationCommand.fromJson(it)) }
                    }
                }
                return out
            }
            return ValidationPlan(
                fastChecks = commands("fast"),
                finalChecks = commands("final"),
                confidence = Confidence.valueOf(root.optString("confidence", Confidence.LOW.name)),
                reason = root.optString("reason", "unspecified")
            )
        }
    }
}

/** SHA-256 helper shared by the coding layer. */
internal object Digests {
    fun sha256(value: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
