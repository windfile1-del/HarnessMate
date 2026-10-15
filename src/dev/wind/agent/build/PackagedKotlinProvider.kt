package dev.wind.agent.build

/** Pinned SC1B1 JVM inputs used to build the separately-loaded SC1B2A Android DEX payload. */
data class KotlinProviderArtifact(
    val fileName: String,
    val relativePath: String,
    val sha256: String,
    val sizeBytes: Long
)

object PackagedKotlinProvider {
    const val PROVIDER_VERSION = "1.9.20-android3"
    const val RUNTIME_ASSET_ROOT = "embedded/kotlin"
    const val UPSTREAM_COMPILER_SHA256 = "a25024fe5da8440de01af045c4fcb954a22f078738ec02616085f0cfc57b2702"

    val upstreamCompiler = KotlinProviderArtifact(
        fileName = "kotlin-compiler-embeddable-1.9.20.jar",
        relativePath = "upstream/kotlin-compiler-embeddable-1.9.20.jar",
        sha256 = UPSTREAM_COMPILER_SHA256,
        sizeBytes = 60_146_451L
    )

    /** Exact eight runtime D8 program inputs; the upstream unpatched compiler stays excluded. */
    val artifacts = listOf(
        KotlinProviderArtifact(
            "kotlin-compiler-embeddable-1.9.20-android3.jar",
            "kotlin-compiler-embeddable-1.9.20-android3.jar",
            "499fb869c135da5e46e57674eb065dce74ce9ab82c166930a3b1b73989174a4e",
            60_542_541L
        ),
        KotlinProviderArtifact(
            "kotlin-stdlib-1.9.20.jar",
            "upstream/kotlin-stdlib-1.9.20.jar",
            "28a35bcdff46d864f80f346a617e486284b208d17378c41900dfb1de95a90e6c",
            1_718_949L
        ),
        KotlinProviderArtifact(
            "kotlin-reflect-1.6.10.jar",
            "upstream/kotlin-reflect-1.6.10.jar",
            "3277ac102ae17aad10a55abec75ff5696c8d109790396434b496e75087854203",
            3_038_560L
        ),
        KotlinProviderArtifact(
            "kotlin-script-runtime-1.9.20.jar",
            "upstream/kotlin-script-runtime-1.9.20.jar",
            "a26a6256a76f766ab8bacdb409b3f8c940d999712a8e88864252b678d66bab9e",
            43_282L
        ),
        KotlinProviderArtifact(
            "kotlin-daemon-embeddable-1.9.20.jar",
            "upstream/kotlin-daemon-embeddable-1.9.20.jar",
            "a939cb5d6ee2a758c9285bd9f3286824beabe12d9a4b5f49f784d0bca329dea5",
            398_747L
        ),
        KotlinProviderArtifact(
            "trove4j-1.0.20200330.jar",
            "upstream/trove4j-1.0.20200330.jar",
            "c5fd725bffab51846bf3c77db1383c60aaaebfe1b7fe2f00d23fe1b7df0a439d",
            572_985L
        ),
        KotlinProviderArtifact(
            "annotations-13.0.jar",
            "upstream/annotations-13.0.jar",
            "ace2a10dc8e2d5fd34925ecac03e4988b2c0f851650c94b8cef49ba1bd111478",
            17_536L
        ),
        KotlinProviderArtifact(
            "kotlinx-coroutines-core-jvm-1.5.0.jar",
            "upstream/kotlinx-coroutines-core-jvm-1.5.0.jar",
            "78d6cc7135f84d692ff3752fcfd1fa1bbe0940d7df70652e4f1eaeec0c78afbb",
            1_482_528L
        )
    )

    val runtimeProgramInputs: List<KotlinProviderArtifact>
        get() = PackagedKotlinRuntimePins.RUNTIME_PROGRAM_INPUTS
}
