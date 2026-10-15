package dev.wind.agent.build

/** Generated together with the Kotlin runtime payload; do not edit by hand. */
internal data class KotlinRuntimeAssetPin(
    val assetPath: String,
    val directory: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String
)

internal object PackagedKotlinRuntimePins {
    const val PROVIDER_VERSION = "1.9.20-android3"
    const val MANIFEST_SIZE_BYTES = 4867
    const val MANIFEST_SHA256 = "3d20ba3e83f04c0c81d38bc33124f81083278535d91266b89fa9b675954d5222"
    const val DESUGAR_RUNTIME_SHA256 = "0fd0f9ce4487082e35638e747af4d1602cb6e20c1066d0c2c9f750c3a642a11f"
    const val RUNTIME_DEX_COUNT = 7

    val RUNTIME_ARTIFACT = KotlinRuntimeAssetPin("embedded/kotlin/runtime/kotlin-provider-runtime-1.9.20-android3.jar", "runtime", "kotlin-provider-runtime-1.9.20-android3.jar", 52022626L, "47ff6b4532d3c11d29180dcc97e47a0ff9cb0ef4c444f8c241a057534926900a")
    val RESOURCE_ARTIFACTS = listOf(
        KotlinRuntimeAssetPin("embedded/kotlin/resources/compiler-resources.jar", "resources", "compiler-resources.jar", 930944L, "875f0fc72a93d0a5b447ffae5493398032495cf11872b78780be68438055f669"),
        KotlinRuntimeAssetPin("embedded/kotlin/resources/stdlib-resources.jar", "resources", "stdlib-resources.jar", 22883L, "85055839229e0682ae4031b131b0ff1131c249c8d48affd12b9a88ef26d357f5"),
        KotlinRuntimeAssetPin("embedded/kotlin/resources/reflect-resources.jar", "resources", "reflect-resources.jar", 26666L, "7703b40a9d3ee256516abadbf5d6c4dde061ff491a7276bcfa34d9d7610feb16"),
        KotlinRuntimeAssetPin("embedded/kotlin/resources/script-runtime-resources.jar", "resources", "script-runtime-resources.jar", 2235L, "9a8a58469cc6e5c371ea35e3d5bb5fb5b2c661447a836712b1b40e72fcca907d"),
        KotlinRuntimeAssetPin("embedded/kotlin/resources/daemon-resources.jar", "resources", "daemon-resources.jar", 2050L, "92af105e650b00ca5d93d307ec070ab1df5b3e9f79ac5d25ba6301c7c34dfc18"),
        KotlinRuntimeAssetPin("embedded/kotlin/resources/trove-resources.jar", "resources", "trove-resources.jar", 733L, "4531cfa003aa0090bf7574e682930bb39ed9c2138aecff2d21facc7a158c7573"),
        KotlinRuntimeAssetPin("embedded/kotlin/resources/annotations-resources.jar", "resources", "annotations-resources.jar", 3471L, "bba68f4bf563ca3293086f637e2281a25d2db48c152500ddaa5d713baa15dd25"),
        KotlinRuntimeAssetPin("embedded/kotlin/resources/coroutines-resources.jar", "resources", "coroutines-resources.jar", 5412L, "3a9bc24d70e28c76b0759f1e59f91efb6595fe71d1c316d6b46e4d42bc9a050f")
    )
    val STDLIB_ARTIFACT = KotlinRuntimeAssetPin("embedded/kotlin/classpath/kotlin-stdlib-1.9.20.jar", "classpath", "kotlin-stdlib-1.9.20.jar", 1718949L, "28a35bcdff46d864f80f346a617e486284b208d17378c41900dfb1de95a90e6c")

    val RUNTIME_PROGRAM_INPUTS = listOf(
        KotlinProviderArtifact("kotlin-compiler-embeddable-1.9.20-android3.jar", "kotlin-compiler-embeddable-1.9.20-android3.jar", "499fb869c135da5e46e57674eb065dce74ce9ab82c166930a3b1b73989174a4e", 60542541L),
        KotlinProviderArtifact("kotlin-stdlib-1.9.20.jar", "upstream/kotlin-stdlib-1.9.20.jar", "28a35bcdff46d864f80f346a617e486284b208d17378c41900dfb1de95a90e6c", 1718949L),
        KotlinProviderArtifact("kotlin-reflect-1.6.10.jar", "upstream/kotlin-reflect-1.6.10.jar", "3277ac102ae17aad10a55abec75ff5696c8d109790396434b496e75087854203", 3038560L),
        KotlinProviderArtifact("kotlin-script-runtime-1.9.20.jar", "upstream/kotlin-script-runtime-1.9.20.jar", "a26a6256a76f766ab8bacdb409b3f8c940d999712a8e88864252b678d66bab9e", 43282L),
        KotlinProviderArtifact("kotlin-daemon-embeddable-1.9.20.jar", "upstream/kotlin-daemon-embeddable-1.9.20.jar", "a939cb5d6ee2a758c9285bd9f3286824beabe12d9a4b5f49f784d0bca329dea5", 398747L),
        KotlinProviderArtifact("trove4j-1.0.20200330.jar", "upstream/trove4j-1.0.20200330.jar", "c5fd725bffab51846bf3c77db1383c60aaaebfe1b7fe2f00d23fe1b7df0a439d", 572985L),
        KotlinProviderArtifact("annotations-13.0.jar", "upstream/annotations-13.0.jar", "ace2a10dc8e2d5fd34925ecac03e4988b2c0f851650c94b8cef49ba1bd111478", 17536L),
        KotlinProviderArtifact("kotlinx-coroutines-core-jvm-1.5.0.jar", "upstream/kotlinx-coroutines-core-jvm-1.5.0.jar", "78d6cc7135f84d692ff3752fcfd1fa1bbe0940d7df70652e4f1eaeec0c78afbb", 1482528L)
    )
}
