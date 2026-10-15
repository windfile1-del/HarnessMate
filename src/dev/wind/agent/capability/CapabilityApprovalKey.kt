package dev.wind.agent.capability

import java.security.MessageDigest

/**
 * Opaque, exact approval identities for capabilities whose authority is tied
 * to a concrete plan or expansion request.  Keeping the serialized value
 * opaque avoids persisting execution ids, mount topology, or suggested paths
 * as an authority-bearing string while still making every input part of the
 * equality check.
 */
object CapabilityApprovalKey {
    fun apply(requirement: CapabilityRequirement.ApplyChanges): String = hash(
        "apply-v1|" + listOf(
            requirement.projectId,
            requirement.executionId,
            requirement.changedMountIds.sorted().joinToString(","),
            requirement.diffFingerprint,
            requirement.hasDestructiveChanges.toString()
        ).joinToString("|")
    )

    fun expansion(requirement: CapabilityRequirement.CapabilityExpansion): String = hash(
        "expand-v1|" + listOf(
            requirement.projectId,
            requirement.alias.trim().lowercase(),
            requirement.requestedMode.name,
            requirement.executionPath.trim()
        ).joinToString("|")
    )

    fun artifactInstall(requirement: CapabilityRequirement.ArtifactInstall): String = hash(
        "artifact-install-v1|" + listOf(
            requirement.projectId,
            requirement.kind.name,
            requirement.artifactSha256,
            requirement.targetId,
            requirement.targetMountId ?: ""
        ).joinToString("|")
    )

    private fun hash(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
