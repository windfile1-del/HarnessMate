// Optional Android Studio / Gradle build.
//
// The canonical, dependency-free build is ./mkapk.sh — this file exists so the
// same tree also opens and builds as a normal Android Studio project. It maps
// Gradle onto the project's FLAT layout (src/, res/, assets/, AndroidManifest
// at the root) rather than moving anything, so both builds compile exactly the
// same files.

import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application") version "8.7.2"
    id("org.jetbrains.kotlin.android") version "2.0.21"
}

// keystore/keystore.properties is the lowest-priority source of signing
// credentials; -P properties and environment variables override it. Missing file
// is fine — an unsigned release build still assembles.
val keystoreProperties: Map<String, String> = run {
    val file = rootProject.file("keystore/keystore.properties")
    if (!file.isFile) return@run emptyMap()
    val loaded = Properties().apply { file.inputStream().use(::load) }
    // Map the file's own key names onto the VEPRO_* names used everywhere else.
    mapOf(
        "VEPRO_KEYSTORE_PATH" to loaded.getProperty("storeFile"),
        "VEPRO_KEYSTORE_TYPE" to loaded.getProperty("storeType"),
        "VEPRO_KEYSTORE_PASSWORD" to loaded.getProperty("storePassword"),
        "VEPRO_KEY_ALIAS" to loaded.getProperty("keyAlias"),
        "VEPRO_KEY_PASSWORD" to loaded.getProperty("keyPassword"),
    ).filterValues { it != null }.mapValues { it.value as String }
}

android {
    namespace = "dev.wind.agent"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.wind.agent"
        // Android 6.0. The floor was 24 here while apksigner was already told
        // --min-sdk-version 23, so the APK was signed as Android-6-capable while
        // its dex was compiled for 24 — the manifest, the dexer and the signer
        // disagreed, and that inconsistency is what let API-24 calls
        // (stopForeground(int), Math.floorMod) ship unnoticed. All four now say 23:
        // this file, mkapk.sh's aapt2/d8/apksigner flags and AndroidManifest.xml.
        minSdk = 23
        targetSdk = 35
        // Version 1. The displayed version is "1" everywhere it appears — this
        // file, the build script, the About row and the CI workflow — which was
        // not true before: CI still built and labelled artifacts "v5" while the
        // app itself reported "1".
        //
        // versionCode is the separate, invisible number Android uses to order
        // installs. It must never go down, so it keeps rising independently of the
        // displayed name.
        //
        // The signing key lives OUTSIDE this repository and is supplied through
        // VEPRO_KEYSTORE_PATH. Android refuses an update signed by a different key
        // than the installed app, so keeping the same key is what lets a release
        // install over the last one without a reinstall. See
        // keystore/README-KEYSTORE.md.
        versionCode = 1
        versionName = "0.1.0-alpha"
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("AndroidManifest.xml")
            kotlin.setSrcDirs(listOf("src"))
            // The Binder service, workspace and process supervisor are shared
            // verbatim with the optional external Companion build.  Compiling
            // them into the host is what makes `:runtime` a true in-APK
            // endpoint rather than a second implementation of the protocol.
            java.setSrcDirs(listOf("src", "runtime-companion/src"))
            res.setSrcDirs(listOf("res"))
            assets.setSrcDirs(listOf("assets"))
            // Large build environments are distributed as optional tool packs.
            assets.exclude("embedded/linux-jvm/tools.tar.gz", "embedded/linux-android/tools.tar.gz", "embedded/linux-lsp/tools.tar.gz")
            // R0 native executables are built from pinned open-source inputs.
            jniLibs.setSrcDirs(listOf(System.getenv("VEPRO_LINUX_NATIVE_DIR") ?: "third_party/runtime/jniLibs"))
            jniLibs.srcDir(System.getenv("VEPRO_ANDROID_NATIVE_DIR") ?: "third_party/runtime/android-jniLibs")
            jniLibs.srcDir("third_party/phone-ocr/jniLibs")
            jniLibs.srcDir("third_party/adb/jniLibs")
            val linuxAssets = System.getenv("VEPRO_LINUX_ASSETS_DIR")
            if (!linuxAssets.isNullOrBlank()) assets.srcDir(linuxAssets)
            aidl.setSrcDirs(emptyList<String>())
        }
    }

    // Release signing, wired to the same keystore ./mkapk.sh uses. Credentials
    // come from -P properties, the environment, or keystore/keystore.properties,
    // in that order — so nothing secret has to live in this file.
    //
    //   ./gradlew assembleRelease \
    //     -PVEPRO_KEYSTORE_PATH="$HOME/private/vega-release-v1.jks" \
    //     -PVEPRO_KEYSTORE_PASSWORD=… -PVEPRO_KEY_ALIAS=vepro
    signingConfigs {
        create("release") {
            fun setting(name: String, fallback: String? = null): String? =
                (project.findProperty(name) as String?)
                    ?: System.getenv(name)
                    ?: keystoreProperties[name]
                    ?: fallback

            // No default path any more. It used to fall back to a key inside the
            // tree, and there is deliberately no key inside the tree — a default
            // pointing at a file that must never exist only invites someone to
            // create it. Missing credentials produce an unsigned release build and
            // the message below, which is the honest outcome.
            val path = setting("VEPRO_KEYSTORE_PATH")
            val store = path?.let(::file)
            if (store != null && store.isFile) {
                storeFile = store
                storeType = setting("VEPRO_KEYSTORE_TYPE", "PKCS12")
                storePassword = setting("VEPRO_KEYSTORE_PASSWORD")
                keyAlias = setting("VEPRO_KEY_ALIAS", "vepro")
                keyPassword = setting("VEPRO_KEY_PASSWORD")
                    ?: setting("VEPRO_KEYSTORE_PASSWORD")
            } else {
                logger.lifecycle(
                    "no keystore at $path — release builds will be unsigned; see keystore/README-KEYSTORE.md"
                )
            }
        }
    }

    buildTypes {
        release {
            // The hand-rolled build ships un-minified; keep Gradle consistent so
            // both paths produce equivalent bytecode.
            isMinifyEnabled = false
            isShrinkResources = false
            val release = signingConfigs.getByName("release")
            if (release.storeFile != null && release.storePassword != null) {
                signingConfig = release
            }
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Fonts must stay uncompressed, same as `aapt2 link -0 ttf`.
    androidResources {
        noCompress += listOf("ttf")
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            // PIE executables are carried in .so APK entries; AGP must not
            // process them as ordinary JNI shared objects after verification.
            keepDebugSymbols += setOf("**/libproot.so", "**/libprootloader.so")
            keepDebugSymbols += setOf("**/libvegaaapt2.so", "**/libvegaaidl.so", "**/libvegazipalign.so", "**/libvegaaapt.so", "**/libvegadexdump.so")
        }
        resources {
            excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/**")
            // The provider jars have distinct signed-JAR manifests. APK
            // signing owns integrity after D8 transforms their classes.
            excludes += setOf("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
            merges += setOf("META-INF/LICENSE*", "META-INF/NOTICE*")
            merges += setOf("META-INF/services/**")
        }
    }

    lint {
        // The UI is built in code on purpose; the deprecation warnings on
        // systemUiVisibility / onBackPressed / stopForeground(boolean) are
        // deliberate — they are the pre-24 halves of the API-23 support branches.
        abortOnError = false
    }
}

// Match the production providers in mkapk.sh/mkapk.ps1. These are local pinned
// artifacts: missing dependencies must not silently produce a reduced APK.
val embeddedProviderPaths = listOf(
    "third_party/embedded/jgit/org.eclipse.jgit-7.7.1.202607240634-r.jar",
    "third_party/embedded/jgit/JavaEWAH-1.2.3.jar",
    "third_party/embedded/jgit/slf4j-api-2.0.18.jar",
    "third_party/embedded/jgit/commons-codec-1.22.0.jar",
    "third_party/embedded/ecj/ecj-3.46.100-android1.jar",
    "third_party/embedded/zipflinger/zipflinger-8.13.2.jar",
    "third_party/embedded/stax/geronimo-stax-api_1.0_spec-1.0.1.jar",
    "third_party/embedded/kotlin/compat/kotlin-swing-icon-api-1.jar",
    "third_party/embedded/r8/r8-8.2.33.jar"
)
val embeddedProviderLibraryPaths = listOf(
    "third_party/embedded/zipflinger/annotations-31.13.2.jar"
)
val packagedKotlinProviderPaths = listOf(
    "third_party/embedded/kotlin/kotlin-compiler-embeddable-1.9.20-android3.jar",
    "third_party/embedded/kotlin/upstream/kotlin-stdlib-1.9.20.jar",
    "third_party/embedded/kotlin/upstream/kotlin-reflect-1.6.10.jar",
    "third_party/embedded/kotlin/upstream/kotlin-script-runtime-1.9.20.jar",
    "third_party/embedded/kotlin/upstream/kotlin-daemon-embeddable-1.9.20.jar",
    "third_party/embedded/kotlin/upstream/trove4j-1.0.20200330.jar",
    "third_party/embedded/kotlin/upstream/annotations-13.0.jar",
    "third_party/embedded/kotlin/upstream/kotlinx-coroutines-core-jvm-1.5.0.jar",
    "third_party/embedded/kotlin/upstream/kotlin-compiler-embeddable-1.9.20.jar"
)
val packagedKotlinRuntimeAssetPaths = listOf(
    "assets/embedded/kotlin/manifest.json",
    "assets/embedded/kotlin/runtime/kotlin-provider-runtime-1.9.20-android3.jar",
    "assets/embedded/kotlin/resources/compiler-resources.jar",
    "assets/embedded/kotlin/resources/stdlib-resources.jar",
    "assets/embedded/kotlin/resources/reflect-resources.jar",
    "assets/embedded/kotlin/resources/script-runtime-resources.jar",
    "assets/embedded/kotlin/resources/daemon-resources.jar",
    "assets/embedded/kotlin/resources/trove-resources.jar",
    "assets/embedded/kotlin/resources/annotations-resources.jar",
    "assets/embedded/kotlin/resources/coroutines-resources.jar",
    "assets/embedded/kotlin/classpath/kotlin-stdlib-1.9.20.jar"
)
val verifyEmbeddedProviders by tasks.registering {
    val payloadPaths = embeddedProviderPaths + embeddedProviderLibraryPaths + listOf(
        "assets/embedded/android-35/android.jar",
        "assets/embedded/android-35/NOTICE.txt",
        "assets/embedded/android-35/source.properties",
        "assets/embedded/android-35/manifest.json",
        "assets/embedded/desugar/desugar_jdk_libs_nio-2.0.1.jar",
        "assets/embedded/desugar/desugar_jdk_libs_configuration_nio-2.0.1.jar"
    ) + packagedKotlinProviderPaths + packagedKotlinRuntimeAssetPaths
    inputs.files(payloadPaths.map { file(it) })
    inputs.file("third_party/embedded/SHA256SUMS")
    doLast {
        val expected = file("third_party/embedded/SHA256SUMS").readLines()
            .filter { it.isNotBlank() }.associate { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 2)
                parts[1].removePrefix("*") to parts[0]
            }
        payloadPaths.forEach { path ->
            val artifact = file(path)
            check(artifact.isFile) { "Missing embedded provider: $path" }
            val digest = MessageDigest.getInstance("SHA-256")
            artifact.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            check(actual == expected[path]) { "Embedded provider checksum mismatch: $path" }
        }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyEmbeddedProviders) }
val verifyLinuxR0 by tasks.registering {
    val nativeRoot = file(System.getenv("VEPRO_LINUX_NATIVE_DIR") ?: "third_party/runtime/jniLibs")
    val linuxAssets = file(System.getenv("VEPRO_LINUX_ASSETS_DIR") ?: "assets")
    inputs.file("tools/runtime/package_native.py")
    inputs.file("tools/runtime/build_native.py")
    doLast {
        if (nativeRoot.exists() || file("${linuxAssets}/embedded/linux-r0").exists() || file("${linuxAssets}/embedded/linux-runtime").exists()) {
            exec {
                commandLine(System.getenv("VEPRO_PYTHON") ?: "python3",
                    file("tools/runtime/package_native.py").absolutePath, "--check-only",
                    "--native", nativeRoot.absolutePath, "--assets", linuxAssets.absolutePath,
                    "--android-native", file(System.getenv("VEPRO_ANDROID_NATIVE_DIR") ?: "third_party/runtime/android-jniLibs").absolutePath)
            }
        }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyLinuxR0) }
dependencies {
    implementation(files("third_party/embedded/ocr/tesseract4android-4.9.0.jar"))
    implementation(fileTree("third_party/webkit") { include("*.aar") })
    implementation(files(file("third_party/webkit/runtime-jars.txt").readLines().filter { it.isNotBlank() && !file(it.removeSuffix(".jar") + ".aar").exists() }))
    implementation(files(embeddedProviderPaths))
    compileOnly(files(embeddedProviderLibraryPaths))
    // NIO variant matches the hand-built APK's pinned D8/L8 configuration.
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.0.1")
}

val verifyWebProviders by tasks.registering {
    inputs.file("third_party/webkit/SHA256SUMS")
    doLast {
        file("third_party/webkit/SHA256SUMS").readLines().filter { it.isNotBlank() }.forEach { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            val artifact = file(parts[1].removePrefix("*"))
            check(artifact.isFile) { "Missing Web Provider dependency: $artifact" }
            val digest = MessageDigest.getInstance("SHA-256")
            artifact.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            check(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == parts[0]) { "Web Provider dependency checksum mismatch: $artifact" }
        }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyWebProviders) }

val verifyPhoneOcr by tasks.registering {
    inputs.file("third_party/phone-ocr/SHA256SUMS")
    doLast {
        exec { commandLine(System.getenv("VEPRO_PYTHON") ?: "python3",
            file("tools/runtime/phone_ocr_payload.py").absolutePath, "--root", rootProject.projectDir.absolutePath) }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyPhoneOcr) }

val verifyEmbeddedAdb by tasks.registering {
    inputs.dir("third_party/adb/jniLibs")
    inputs.file("tools/runtime/adb_payload.py")
    doLast {
        exec { commandLine(System.getenv("VEPRO_PYTHON") ?: "python3",
            file("tools/runtime/adb_payload.py").absolutePath, "--root", rootProject.projectDir.absolutePath) }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyEmbeddedAdb) }

val verifyPhoneBridge by tasks.registering {
    inputs.dir("phone-bridge/src")
    inputs.dir("assets/embedded/phone")
    inputs.file("tools/runtime/build_phone_bridge.py")
    doLast {
        exec { commandLine(System.getenv("VEPRO_PYTHON") ?: "python3",
            file("tools/runtime/build_phone_bridge.py").absolutePath, "--verify") }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyPhoneBridge) }
