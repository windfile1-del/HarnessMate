package dev.wind.agent

import java.io.File
import java.util.zip.ZipFile

/** Keeps provider-program versus build-library roles aligned across all packagers. */
object EmbeddedProviderPackagingTests {
    private const val ZIPFLINGER = "third_party/embedded/zipflinger/zipflinger-8.13.2.jar"
    private const val ANNOTATIONS = "third_party/embedded/zipflinger/annotations-31.13.2.jar"
    private const val STAX_API = "third_party/embedded/stax/geronimo-stax-api_1.0_spec-1.0.1.jar"
    private const val SWING_ICON_API = "third_party/embedded/kotlin/compat/kotlin-swing-icon-api-1.jar"

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "Usage: EmbeddedProviderPackagingTests <repository-root>" }
        val root = File(args.single()).canonicalFile
        check(root.isDirectory) { "Repository root does not exist: $root" }

        val gradle = read(root, "build.gradle.kts")
        val gradlePrograms = kotlinList(gradle, "embeddedProviderPaths")
        val gradleLibraries = kotlinList(gradle, "embeddedProviderLibraryPaths")
        requireContainsPath(gradlePrograms, ZIPFLINGER, "Gradle program providers")
        requireContainsPath(gradlePrograms, STAX_API, "Gradle parent-loaded StAX API")
        requireContainsPath(gradlePrograms, SWING_ICON_API, "Gradle parent-loaded Swing Icon API")
        requireContainsPath(gradlePrograms, "third_party/embedded/r8/r8-8.2.33.jar", "Gradle packaged toolchain")
        requireLacksPath(gradlePrograms, ANNOTATIONS, "Gradle program providers")
        requireContainsPath(gradleLibraries, ANNOTATIONS, "Gradle compile-only libraries")
        requireLacksPath(gradleLibraries, ZIPFLINGER, "Gradle compile-only libraries")
        check(Regex("implementation\\s*\\(\\s*files\\s*\\(\\s*embeddedProviderPaths\\s*\\)\\s*\\)")
            .containsMatchIn(gradle)) { "Gradle must package embeddedProviderPaths as program inputs" }
        check(Regex("compileOnly\\s*\\(\\s*files\\s*\\(\\s*embeddedProviderLibraryPaths\\s*\\)\\s*\\)")
            .containsMatchIn(gradle)) { "Gradle must keep annotations as compile-only library inputs" }
        val verificationPayload = gradle.substringAfter("val payloadPaths =", "").substringBefore("inputs.files")
        check(verificationPayload.contains("embeddedProviderPaths + embeddedProviderLibraryPaths")) {
            "Gradle checksum gate must verify both program and library artifacts"
        }

        val shell = read(root, "mkapk.sh")
        val shellPrograms = shellArray(shell, "PROVIDER_JARS")
        val shellToolchain = shellArray(shell, "PACKAGED_TOOLCHAIN_JARS")
        val shellToolchainResources = shellArray(shell, "PACKAGED_TOOLCHAIN_RESOURCE_JARS")
        val shellLibraries = shellArray(shell, "PROVIDER_LIBRARY_JARS")
        requireContainsPath(shellPrograms, ZIPFLINGER, "mkapk.sh program providers")
        requireContainsPath(shellPrograms, STAX_API, "mkapk.sh StAX API")
        requireContainsPath(shellPrograms, SWING_ICON_API, "mkapk.sh Swing Icon API")
        requireLacksPath(shellPrograms, ANNOTATIONS, "mkapk.sh program providers")
        requireContainsPath(shellLibraries, ANNOTATIONS, "mkapk.sh D8 libraries")
        requireLacksPath(shellLibraries, ZIPFLINGER, "mkapk.sh D8 libraries")
        requireContainsPath(shellToolchain, "third_party/embedded/r8/r8-8.2.33.jar", "mkapk.sh D8 toolchain programs")
        requireContainsPath(shellToolchainResources, "third_party/embedded/r8/r8-8.2.33.jar", "mkapk.sh toolchain resources")
        requireLacksPath(shellPrograms, "third_party/embedded/r8/r8-8.2.33.jar", "mkapk.sh provider programs")
        check(shell.contains("for jar in \"\${PROVIDER_JARS[@]}\"")) {
            "mkapk.sh sanitizer must process only program provider JARs"
        }
        check(shell.contains("D8_LIBRARY_ARGS+=(--lib \"\$library\")") &&
            shell.contains("\"\${D8_LIBRARY_ARGS[@]}\"")) {
            "mkapk.sh D8 invocation must pass each provider library with --lib"
        }
        val shellResourceMerge = shell.substringAfter("PackageJarResources.java").substringBefore("zipalign")
        check(shellResourceMerge.contains("\"\${PROVIDER_JARS[@]}\"") &&
            shellResourceMerge.contains("\"\${PACKAGED_TOOLCHAIN_RESOURCE_JARS[@]}\"") &&
            !shellResourceMerge.contains("\"\${PACKAGED_TOOLCHAIN_JARS[@]}\"")) {
            "mkapk.sh resource merger must receive provider resources plus the separate toolchain resource role"
        }

        val powershell = read(root, "mkapk.ps1")
        val psPrograms = powershellArray(powershell, "ProviderJars")
        val psToolchain = powershellArray(powershell, "PackagedToolchainJars")
        val psToolchainResources = powershellArray(powershell, "PackagedToolchainResourceJars")
        val psLibraries = powershellArray(powershell, "ProviderLibraryJars")
        requireContainsPath(psPrograms, ZIPFLINGER, "mkapk.ps1 program providers")
        requireContainsPath(psPrograms, STAX_API, "mkapk.ps1 StAX API")
        requireContainsPath(psPrograms, SWING_ICON_API, "mkapk.ps1 Swing Icon API")
        requireLacksPath(psPrograms, ANNOTATIONS, "mkapk.ps1 program providers")
        requireContainsPath(psLibraries, ANNOTATIONS, "mkapk.ps1 D8 libraries")
        requireLacksPath(psLibraries, ZIPFLINGER, "mkapk.ps1 D8 libraries")
        requireContainsPath(psToolchain, "third_party/embedded/r8/r8-8.2.33.jar", "mkapk.ps1 D8 toolchain programs")
        requireContainsPath(psToolchainResources, "third_party/embedded/r8/r8-8.2.33.jar", "mkapk.ps1 toolchain resources")
        requireLacksPath(psPrograms, "third_party/embedded/r8/r8-8.2.33.jar", "mkapk.ps1 provider programs")
        check(powershell.contains("\$ProviderJars + \$PackagedToolchainJars + \$PackagedToolchainResourceJars + \$KotlinProviderJars + \$ProviderLibraryJars")) {
            "mkapk.ps1 required-file gate must check program, resource, Kotlin runtime, and library roles"
        }
        check(powershell.contains("foreach (\$library in \$ProviderLibraryJars)") &&
            powershell.contains("\$d8Args += @(\"--lib\", \$library)")) {
            "mkapk.ps1 D8 invocation must pass annotations as --lib"
        }
        check(powershell.contains("foreach (\$jar in \$ProviderJars)") &&
            powershell.contains("\$d8Args += \$sanitizedProviderJars")) {
            "mkapk.ps1 sanitizer/program inputs must derive from ProviderJars only"
        }
        check(powershell.contains("\"\$Root/tools/PackageJarResources.java\" \$unsignedApk \$KotlinStdlib @ProviderJars @PackagedToolchainResourceJars")) {
            "mkapk.ps1 resource merger must receive provider resources plus the separate toolchain resource role"
        }

        val offline = read(root, "tools/build-offline.sh")
        val offlinePrograms = shellArray(offline, "EMBEDDED_JARS")
        val offlineLibraries = shellArray(offline, "EMBEDDED_LIBRARY_JARS")
        requireContainsPath(offlinePrograms, ZIPFLINGER, "offline/test runtime JARs")
        requireContainsPath(offlinePrograms, STAX_API, "offline/test StAX API")
        requireLacksPath(offlinePrograms, ANNOTATIONS, "offline/test runtime JARs")
        requireContainsPath(offlineLibraries, ANNOTATIONS, "offline checksum-only libraries")
        requireLacksPath(offlineLibraries, ZIPFLINGER, "offline checksum-only libraries")
        check(offline.contains("EMBEDDED_CP=\"$(IFS=:; echo \"\${EMBEDDED_JARS[*]}\")\"")) {
            "Offline runtime classpath must be assembled from program JARs only"
        }
        check(offline.contains("for embedded_library_jar in \"\${EMBEDDED_LIBRARY_JARS[@]}\"")) {
            "Offline runner must require the library-only JAR without adding it to runtime classpath"
        }

        val sums = read(root, "third_party/embedded/SHA256SUMS")
        requireContainsPath(sums, ZIPFLINGER, "embedded checksum manifest")
        requireContainsPath(sums, ANNOTATIONS, "embedded checksum manifest")
        verifyAnnotationClassNames(File(root, ANNOTATIONS))

        check(gradle.contains("merges += setOf(\"META-INF/services/**\")")) {
            "Gradle must merge service descriptors from packaged toolchain resources"
        }
        println("EmbeddedProviderPackagingTests: PASS (R8 program/resource roles stay separate; Zipflinger=program; annotations=library-only)")
    }

    private fun verifyAnnotationClassNames(jar: File) {
        check(jar.isFile) { "Pinned annotations library is missing: $jar" }
        ZipFile(jar).use { archive ->
            listOf(
                "com/android/annotations/NonNull.class",
                "com/android/annotations/Nullable.class",
                "com/android/annotations/TestOnly.class"
            ).forEach { check(archive.getEntry(it) != null) { "Expected annotation descriptor source is absent: $it" } }
        }
    }

    private fun kotlinList(source: String, name: String): String = capture(
        source,
        "(?ms)^[ \\t]*val[ \\t]+${Regex.escape(name)}[ \\t]*=[ \\t]*listOf\\([ \\t]*\\r?\\n(.*?)^[ \\t]*\\)"
    )

    private fun shellArray(source: String, name: String): String = capture(
        source,
        "(?ms)^${Regex.escape(name)}=\\([ \\t]*\\r?\\n(.*?)^\\)"
    )

    private fun powershellArray(source: String, name: String): String = capture(
        source,
        "(?ms)^\\$${Regex.escape(name)}[ \\t]*=[ \\t]*@\\([ \\t]*\\r?\\n(.*?)^\\)"
    )

    private fun capture(source: String, pattern: String): String =
        Regex(pattern).find(source)?.groupValues?.get(1)
            ?: error("Could not find expected build-definition collection matching $pattern")

    private fun requireContainsPath(block: String, path: String, label: String) {
        check(normalize(block).contains(path)) { "$label does not declare $path" }
    }

    private fun requireLacksPath(block: String, path: String, label: String) {
        check(!normalize(block).contains(path)) { "$label must not declare $path" }
    }

    private fun normalize(value: String): String = value.replace('\\', '/')

    private fun read(root: File, path: String): String = File(root, path).readText(Charsets.UTF_8)
}
