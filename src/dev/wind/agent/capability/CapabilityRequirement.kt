package dev.wind.agent.capability

import dev.wind.agent.project.MountMode
import org.json.JSONObject

/** The run mode is deliberately separate from ToolRisk. */
enum class CapabilityMode {
    ACCEPT,
    AUTO,
    PLAN
}

/**
 * Immutable authority context passed through a tool call. Sub-agents keep the
 * rootTaskId of their lead task and therefore cannot silently create a wider
 * session grant.
 */
data class TaskContext(
    val taskId: String,
    val projectId: String,
    val projectGeneration: Long,
    val mode: CapabilityMode = CapabilityMode.ACCEPT,
    val rootTaskId: String = taskId
)

/**
 * The actual side-effect requirement of a tool. ToolRisk remains a UI hint;
 * these types are what the broker evaluates.
 */
sealed class CapabilityRequirement {
    abstract val projectId: String

    data class ProjectRead(
        override val projectId: String,
        val mountIds: Set<String>
    ) : CapabilityRequirement()

    data class MirrorRead(
        override val projectId: String
    ) : CapabilityRequirement()

    data class MirrorMutation(
        override val projectId: String
    ) : CapabilityRequirement()

    data class Execution(
        override val projectId: String
    ) : CapabilityRequirement()

    data class CanonicalWrite(
        override val projectId: String,
        val mountIds: Set<String>
    ) : CapabilityRequirement()

    data class CanonicalDelete(
        override val projectId: String,
        val mountIds: Set<String>
    ) : CapabilityRequirement()

    data class Network(
        override val projectId: String,
        val domains: Set<String>,
        /** Shell network requests also require execution authority. */
        val execution: Boolean = false
    ) : CapabilityRequirement()

    enum class ArtifactInstallKind { RUNTIME_TOOL, PROJECT_DEPENDENCY, ANDROID_PACKAGE }

    /** Exact install intent; neither network nor generic write grants satisfy it. */
    data class ArtifactInstall(
        override val projectId: String,
        val kind: ArtifactInstallKind,
        val artifactSha256: String,
        val targetId: String,
        val targetMountId: String? = null
    ) : CapabilityRequirement() {
        init {
            if (kind == ArtifactInstallKind.PROJECT_DEPENDENCY) {
                require(projectId.isNotBlank()) { "INSTALL_PROJECT_REQUIRED" }
            }
            require(artifactSha256.matches(Regex("[0-9a-f]{64}"))) { "INSTALL_DIGEST_INVALID" }
            require(targetId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:+-]{0,159}"))) {
                "INSTALL_TARGET_INVALID"
            }
            require((kind == ArtifactInstallKind.PROJECT_DEPENDENCY) == (targetMountId != null)) {
                "INSTALL_MOUNT_INVALID"
            }
            if (targetMountId != null) require(targetMountId.isNotBlank() && targetMountId.length <= 160) {
                "INSTALL_MOUNT_INVALID"
            }
        }
    }

    /** The apply plan is populated only after the mirror has produced a diff. */
    data class ApplyChanges(
        override val projectId: String,
        val executionId: String,
        val changedMountIds: Set<String> = emptySet(),
        val diffFingerprint: String = "",
        val hasDestructiveChanges: Boolean = false
    ) : CapabilityRequirement()

    data class CapabilityExpansion(
        override val projectId: String,
        val alias: String = "",
        /** Requested mount mode is part of the approval identity. */
        val requestedMode: MountMode = MountMode.READ_WRITE,
        /** Suggested execution path is part of the approval identity. */
        val executionPath: String = ""
    ) : CapabilityRequirement()

    data class Composite(
        override val projectId: String,
        val requirements: List<CapabilityRequirement>
    ) : CapabilityRequirement()

    data class Internal(
        override val projectId: String = ""
    ) : CapabilityRequirement()

    data class Denied(
        override val projectId: String,
        val reason: String
    ) : CapabilityRequirement()
}

/** A request shown to the UI or consumed by a compatibility callback. */
data class CapabilityRequest(
    val requestId: String,
    val toolName: String,
    val args: JSONObject?,
    val context: TaskContext,
    val requirement: CapabilityRequirement,
    val signature: String,
    val summary: String,
    val createdAt: Long,
    val allowedScopes: Set<GrantScope> = setOf(GrantScope.ONCE, GrantScope.TASK, GrantScope.APP_SESSION)
)

/** Result of a broker check; Boolean would lose lease and denial information. */
sealed class CapabilityCheck {
    data class Allowed(val lease: CapabilityLease) : CapabilityCheck()
    data class NeedsApproval(val request: CapabilityRequest) : CapabilityCheck()
    data class Denied(val reason: String) : CapabilityCheck()
}

/** User/UI decision. The default compatibility path chooses TASK scope. */
sealed class CapabilityDecision {
    data class Allow(val scope: GrantScope = GrantScope.TASK) : CapabilityDecision()
    object Deny : CapabilityDecision()
}
