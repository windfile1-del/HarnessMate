package dev.wind.agent.coding

import org.json.JSONObject
import dev.wind.agent.isNotBlankJava

/**
 * The lifecycle of one coding task (P4).
 *
 * This is deliberately NOT the chat transcript. The transcript records what was
 * said; this records whether the code has actually been built, whether that
 * build still describes the code that is on disk right now, and whether anything
 * is therefore eligible to be applied. A model that "says" it finished changes
 * nothing here, which is the entire point of the phase machine.
 */
enum class CodingPhase {
    CREATED,
    INSPECTING,
    WORKING,
    VALIDATING,
    REPAIRING,
    VERIFYING,
    READY_TO_APPLY,
    APPLYING,
    COMPLETED,
    FINISHED,
    BLOCKED,
    FAILED,
    CANCELLED;

    /** True for phases from which no further work is expected. */
    val terminal: Boolean get() = this == COMPLETED || this == FINISHED || this == BLOCKED || this == FAILED || this == CANCELLED

    /**
     * True while a validation, repair or apply step is in flight.
     *
     * A process that dies in one of these phases cannot be trusted to have
     * finished the step it was in: the build tool may have been killed halfway
     * through, which is exactly the state that used to be able to read back as
     * success. Recovery turns every one of them into BLOCKED.
     */
    val uncertainWhenInterrupted: Boolean
        get() = this == INSPECTING || this == WORKING || this == VALIDATING ||
            this == REPAIRING || this == VERIFYING || this == APPLYING
}

/** Strict JSON reads: a coerced value is never accepted as a schema. */
internal object CodingJson {
    fun strictInt(root: JSONObject, key: String): Int? {
        if (!root.has(key)) {
            return null
        }
        val value = root.opt(key)
        require(value is Number) { "CODING_STATE_SCHEMA_INVALID" }
        val number = value.toDouble()
        require(number.isFinite() && number == kotlin.math.floor(number)) {
            "CODING_STATE_SCHEMA_INVALID"
        }
        require(number >= Int.MIN_VALUE.toDouble() && number <= Int.MAX_VALUE.toDouble()) {
            "CODING_STATE_SCHEMA_INVALID"
        }
        return number.toInt()
    }

    fun phaseOf(raw: String?): CodingPhase =
        CodingPhase.valueOf(requireNotNull(raw) { "CODING_STATE_PHASE_MISSING" })
}

/** The outcome of one validation command, as the harness saw it. */
data class ValidationSummary(
    val check: String,
    val ok: Boolean,
    val exitCode: Int?,
    val errors: Int,
    val warnings: Int,
    val blocked: Boolean,
    val sourceFingerprint: String,
    val at: Long
) {
    init {
        require(check.isNotBlankJava()) { "CODING_CHECK_ID_MISSING" }
        require(errors >= 0 && warnings >= 0)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("check", check)
        .put("ok", ok)
        .put("exitCode", exitCode ?: -1)
        .put("errors", errors)
        .put("warnings", warnings)
        .put("blocked", blocked)
        .put("sourceFingerprint", sourceFingerprint)
        .put("at", at)

    companion object {
        fun fromJson(root: JSONObject): ValidationSummary = ValidationSummary(
            check = root.getString("check"),
            ok = root.getBoolean("ok"),
            exitCode = root.optInt("exitCode", -1).takeIf { it >= 0 },
            errors = root.optInt("errors", 0),
            warnings = root.optInt("warnings", 0),
            blocked = root.optBoolean("blocked", false),
            sourceFingerprint = root.optString("sourceFingerprint", ""),
            at = root.optLong("at", 0L)
        )
    }
}

/**
 * What makes a passing build still TRUE for the code on disk.
 *
 * A build result is a statement about one exact revision of the project. Bind
 * it to that revision (and to the plan that produced it) and the statement can
 * be checked later; leave it unbound and "tests passed ten minutes ago" gets
 * reported as "the code passes now" after the model has edited three more files.
 * [sourceFingerprint] is the P3 changed-file digest when the mirror can produce
 * one; [mutationEpoch] is the harness's own count of observed source mutations,
 * which is available even when no mirror session exists.
 */
data class VerificationStamp(
    val sourceFingerprint: String,
    val mutationEpoch: Int,
    val planFingerprint: String,
    val passedChecks: List<String>,
    val verifiedAt: Long,
    val environmentIdentity: String? = null
) {
    init {
        require(passedChecks.isNotEmpty()) { "CODING_VERIFICATION_EMPTY" }
        require(mutationEpoch >= 0)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("sourceFingerprint", sourceFingerprint)
        .put("mutationEpoch", mutationEpoch)
        .put("planFingerprint", planFingerprint)
        .put("passedChecks", org.json.JSONArray().apply { passedChecks.forEach { put(it) } })
        .put("verifiedAt", verifiedAt)
        .put("environmentIdentity", environmentIdentity ?: JSONObject.NULL)

    companion object {
        fun fromJson(root: JSONObject): VerificationStamp {
            val checks = mutableListOf<String>()
            val array = root.optJSONArray("passedChecks")
            if (array != null) {
                for (i in 0 until array.length()) {
                    val value = array.optString(i, "")
                    if (value.isNotEmpty()) {
                        checks.add(value)
                    }
                }
            }
            return VerificationStamp(
                sourceFingerprint = root.optString("sourceFingerprint", ""),
                mutationEpoch = root.optInt("mutationEpoch", 0),
                planFingerprint = root.optString("planFingerprint", ""),
                passedChecks = checks,
                verifiedAt = root.optLong("verifiedAt", 0L),
                environmentIdentity = root.optString("environmentIdentity", "").takeIf { it.isNotEmpty() && it != "null" }
            )
        }
    }
}

/** An observed canonical transaction; applying files is independent of verification. */
data class CodingApplyReceipt(
    val executionId: String,
    val diffFingerprint: String,
    val sourceFingerprint: String,
    val mutationEpoch: Int,
    val createdFiles: Int,
    val modifiedFiles: Int,
    val deletedFiles: Int,
    val at: Long,
    val verifiedAtApply: Boolean
) {
    init {
        require(executionId.isNotBlankJava() && diffFingerprint.isNotBlankJava())
        require(mutationEpoch >= 0 && createdFiles >= 0 && modifiedFiles >= 0 && deletedFiles >= 0 && at >= 0)
    }
    fun toJson(): JSONObject = JSONObject()
        .put("executionId", executionId).put("diffFingerprint", diffFingerprint)
        .put("sourceFingerprint", sourceFingerprint).put("mutationEpoch", mutationEpoch)
        .put("createdFiles", createdFiles).put("modifiedFiles", modifiedFiles).put("deletedFiles", deletedFiles)
        .put("at", at).put("verifiedAtApply", verifiedAtApply)

    companion object {
        fun fromJson(json: JSONObject): CodingApplyReceipt = CodingApplyReceipt(
            json.getString("executionId"), json.getString("diffFingerprint"), json.getString("sourceFingerprint"),
            json.getInt("mutationEpoch"), json.getInt("createdFiles"), json.getInt("modifiedFiles"),
            json.getInt("deletedFiles"), json.getLong("at"), json.getBoolean("verifiedAtApply"))
    }
}

/** The durable control state of one coding task. New facts are optional for v1 records. */
data class CodingTaskState(
    val taskId: String,
    val projectId: String,
    val projectGeneration: Long,
    val phase: CodingPhase,
    val profile: ProjectProfile? = null,
    val validationPlan: ValidationPlan? = null,
    val repairCycle: Int = 0,
    val mutationEpoch: Int = 0,
    val lastValidation: ValidationSummary? = null,
    val verification: VerificationStamp? = null,
    val lastExecutionId: String? = null,
    val finalDiffFingerprint: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val note: String = "",
    val applyReceipt: CodingApplyReceipt? = null,
    val validationHistory: List<ValidationSummary> = emptyList(),
    val selectedProjectPath: String? = null,
    val registeredChecks: List<ValidationCommand> = emptyList(),
    val runOutcome: String? = null,
    val stopReason: String? = null,
    val canonicalOperations: List<String> = emptyList(),
    val discardedEpoch: Int? = null,
    val schemaVersion: Int = SCHEMA_VERSION
) {
    init {
        require(taskId.isNotBlankJava()) { "CODING_TASK_ID_MISSING" }
        require(repairCycle >= 0 && mutationEpoch >= 0)
        require(schemaVersion == SCHEMA_VERSION) { "CODING_STATE_SCHEMA_UNSUPPORTED" }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("schemaVersion", schemaVersion)
        .put("taskId", taskId)
        .put("projectId", projectId)
        .put("projectGeneration", projectGeneration)
        .put("phase", phase.name)
        .put("profile", profile?.toJson() ?: JSONObject.NULL)
        .put("validationPlan", validationPlan?.toJson() ?: JSONObject.NULL)
        .put("repairCycle", repairCycle)
        .put("mutationEpoch", mutationEpoch)
        .put("lastValidation", lastValidation?.toJson() ?: JSONObject.NULL)
        .put("verification", verification?.toJson() ?: JSONObject.NULL)
        .put("lastExecutionId", lastExecutionId ?: JSONObject.NULL)
        .put("finalDiffFingerprint", finalDiffFingerprint ?: JSONObject.NULL)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)
        .put("note", note)
        .put("applyReceipt", applyReceipt?.toJson() ?: JSONObject.NULL)
        .put("validationHistory", org.json.JSONArray().apply { validationHistory.forEach { put(it.toJson()) } })
        .put("selectedProjectPath", selectedProjectPath ?: JSONObject.NULL)
        .put("registeredChecks", org.json.JSONArray().apply { registeredChecks.forEach { put(it.toJson()) } })
        .put("runOutcome", runOutcome ?: JSONObject.NULL)
        .put("stopReason", stopReason ?: JSONObject.NULL)
        .put("canonicalOperations", org.json.JSONArray().apply { canonicalOperations.forEach { put(it) } })
        .put("discardedEpoch", discardedEpoch ?: JSONObject.NULL)

    /**
     * A step that was in flight when the process ended is never a success.
     *
     * The stamp is dropped as well, because the harness cannot prove the build
     * that produced it ran to completion, nor that the source it described is
     * still the source on disk.
     */
    fun recoveredAfterInterruption(now: Long): CodingTaskState {
        if (!phase.uncertainWhenInterrupted) {
            return this
        }
        return copy(
            phase = CodingPhase.BLOCKED,
            verification = null,
            lastValidation = null,
            note = "INTERRUPTED_" + phase.name,
            stopReason = "INTERRUPTED_" + phase.name,
            updatedAt = now
        )
    }

    fun withPhase(next: CodingPhase, now: Long, note: String = ""): CodingTaskState =
        copy(phase = next, updatedAt = now, note = note)

    companion object {
        const val SCHEMA_VERSION = 1

        fun created(taskId: String, projectId: String, generation: Long, now: Long): CodingTaskState =
            CodingTaskState(
                taskId = taskId,
                projectId = projectId,
                projectGeneration = generation,
                phase = CodingPhase.CREATED,
                createdAt = now,
                updatedAt = now
            )

        fun fromJson(root: JSONObject, expectedTaskId: String): CodingTaskState {
            val schema = CodingJson.strictInt(root, "schemaVersion")
            require(schema == SCHEMA_VERSION) { "CODING_STATE_SCHEMA_UNSUPPORTED" }
            val taskId = root.getString("taskId")
            require(taskId == expectedTaskId) { "CODING_STATE_TASK_MISMATCH" }
            val profileJson = root.optJSONObject("profile")
            val planJson = root.optJSONObject("validationPlan")
            val lastJson = root.optJSONObject("lastValidation")
            val stampJson = root.optJSONObject("verification")
            return CodingTaskState(
                taskId = taskId,
                projectId = root.optString("projectId", ""),
                projectGeneration = root.optLong("projectGeneration", 0L),
                phase = CodingJson.phaseOf(root.optString("phase", "")),
                profile = profileJson?.let { ProjectProfile.fromJson(it) },
                validationPlan = planJson?.let { ValidationPlan.fromJson(it) },
                repairCycle = root.optInt("repairCycle", 0),
                mutationEpoch = root.optInt("mutationEpoch", 0),
                lastValidation = lastJson?.let { ValidationSummary.fromJson(it) },
                verification = stampJson?.let { VerificationStamp.fromJson(it) },
                lastExecutionId = root.optString("lastExecutionId", "").takeIf { it.isNotEmpty() },
                finalDiffFingerprint = root.optString("finalDiffFingerprint", "").takeIf { it.isNotEmpty() },
                createdAt = root.optLong("createdAt", 0L),
                updatedAt = root.optLong("updatedAt", 0L),
                note = root.optString("note", ""),
                applyReceipt = root.optJSONObject("applyReceipt")?.let { CodingApplyReceipt.fromJson(it) },
                validationHistory = root.optJSONArray("validationHistory")?.let { array ->
                    (0 until array.length()).map { ValidationSummary.fromJson(array.getJSONObject(it)) }
                } ?: emptyList(),
                selectedProjectPath = root.optString("selectedProjectPath", "").takeIf { it.isNotEmpty() && it != "null" },
                registeredChecks = root.optJSONArray("registeredChecks")?.let { array ->
                    (0 until array.length()).map { ValidationCommand.fromJson(array.getJSONObject(it)) }
                } ?: emptyList(),
                runOutcome = root.optString("runOutcome", "").takeIf { it.isNotEmpty() && it != "null" },
                stopReason = root.optString("stopReason", "").takeIf { it.isNotEmpty() && it != "null" },
                canonicalOperations = root.optJSONArray("canonicalOperations")?.let { array ->
                    (0 until array.length()).map { array.getString(it) }
                } ?: emptyList(),
                discardedEpoch = if (root.has("discardedEpoch") && !root.isNull("discardedEpoch")) CodingJson.strictInt(root, "discardedEpoch") else null
            )
        }
    }
}
