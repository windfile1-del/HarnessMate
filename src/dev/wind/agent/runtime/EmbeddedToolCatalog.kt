package dev.wind.agent.runtime

import java.util.Collections

/**
 * The in-process tools that may be described by the C0 packaged-provider
 * catalog.  Native executables and the shell escape hatch are intentionally
 * outside this inventory.
 */
internal val CATALOGABLE_IN_PROCESS_TOOL_IDS: Set<RuntimeToolId> = setOf(
    RuntimeToolId.GIT,
    RuntimeToolId.JAVA_COMPILE,
    RuntimeToolId.KOTLIN_COMPILE,
    RuntimeToolId.DEX,
    RuntimeToolId.APK_SIGN
)

/** Immutable metadata for one packaged in-process provider. */
data class EmbeddedToolDescriptor(
    val toolId: RuntimeToolId,
    val providerId: String,
    val providerVersion: String,
    val licenseId: String,
    val artifactSha256: String,
    val capabilityVersion: String
) {
    init {
        require(toolId in CATALOGABLE_IN_PROCESS_TOOL_IDS) {
            "EMBEDDED_TOOL_CATALOG_TOOL_UNSUPPORTED:${toolId.name}"
        }
        require(providerId.isNotBlank()) { "EMBEDDED_TOOL_PROVIDER_ID_MISSING" }
        require(providerVersion.isNotBlank()) {
            "EMBEDDED_TOOL_PROVIDER_VERSION_MISSING"
        }
        require(licenseId.isNotBlank()) { "EMBEDDED_TOOL_LICENSE_ID_MISSING" }
        require(artifactSha256.matches(Regex("[0-9a-fA-F]{64}"))) {
            "EMBEDDED_TOOL_ARTIFACT_SHA256_INVALID"
        }
        require(capabilityVersion.isNotBlank()) {
            "EMBEDDED_TOOL_CAPABILITY_VERSION_MISSING"
        }
    }
}

/**
 * Immutable, deterministic inventory of packaged in-process providers.
 *
 * The catalog describes what is packaged; a runtime probe is still required
 * before a descriptor can advertise a usable capability.
 */
class EmbeddedToolCatalog(descriptors: Iterable<EmbeddedToolDescriptor>) {
    val entries: List<EmbeddedToolDescriptor>

    private val byToolId: Map<RuntimeToolId, EmbeddedToolDescriptor>

    init {
        val supplied = descriptors.toList()
        val seen = HashSet<RuntimeToolId>()
        supplied.forEach { descriptor ->
            require(seen.add(descriptor.toolId)) {
                "EMBEDDED_TOOL_CATALOG_DUPLICATE:${descriptor.toolId.name}"
            }
        }
        val ordered = supplied.sortedBy { it.toolId.name }
        entries = Collections.unmodifiableList(ordered)
        byToolId = entries.associateBy { it.toolId }
    }

    /** Alias for callers that refer to catalog records as descriptors. */
    val descriptors: List<EmbeddedToolDescriptor>
        get() = entries

    operator fun get(toolId: RuntimeToolId): EmbeddedToolDescriptor? = byToolId[toolId]

    fun descriptor(toolId: RuntimeToolId): EmbeddedToolDescriptor? = byToolId[toolId]

    companion object {
        /** Empty inventory for isolated/unconfigured runtimes. */
        val EMPTY: EmbeddedToolCatalog = EmbeddedToolCatalog(emptyList())

        /** Exact vendored C1 providers; functional probes still govern availability. */
        val C1A1: EmbeddedToolCatalog = EmbeddedToolCatalog(
            listOf(
                EmbeddedToolDescriptor(
                    toolId = RuntimeToolId.GIT,
                    providerId = "jgit-core",
                    providerVersion = "7.7.1.202607240634-r",
                    licenseId = "BSD-3-Clause / EDL-1.0",
                    artifactSha256 = "591abcc84955a6ad3363ec904bda79cf14427706efb4e06ffeb7bc3601503980",
                    capabilityVersion = "p6.4-c1-jgit-7.7.1"
                ),
                EmbeddedToolDescriptor(
                    toolId = RuntimeToolId.JAVA_COMPILE,
                    providerId = "eclipse-ecj",
                    providerVersion = "3.46.100-android1",
                    licenseId = "EPL-2.0",
                    artifactSha256 = "acda57158970a77135daf6e676e5208d0515f7c51f009f4a4370fc8186280254",
                    capabilityVersion = "p6.4-c1-ecj-3.46.100-android1"
                ),
                EmbeddedToolDescriptor(
                    toolId = RuntimeToolId.KOTLIN_COMPILE,
                    providerId = "kotlin-compiler-embeddable",
                    providerVersion = "1.9.20-android3",
                    licenseId = "Apache-2.0 / LGPL-2.1",
                    artifactSha256 = "3d20ba3e83f04c0c81d38bc33124f81083278535d91266b89fa9b675954d5222",
                    capabilityVersion = "p6-sc1b2b-kotlin-1.9.20-android3"
                ),
                EmbeddedToolDescriptor(
                    toolId = RuntimeToolId.DEX,
                    providerId = "google-r8",
                    providerVersion = "8.2.33",
                    licenseId = "BSD-3-Clause / Apache-2.0 / BSD / MIT / Public Domain",
                    artifactSha256 = "80c39a28bb66b64efc232d68ae554d59fe3838808738b4fe584884691cb1e5a3",
                    capabilityVersion = "p6-sc1-r8-8.2.33"
                )
            )
        )
    }
}
