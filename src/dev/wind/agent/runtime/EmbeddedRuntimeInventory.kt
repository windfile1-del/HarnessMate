package dev.wind.agent.runtime

import java.io.File
import android.content.Context
import dev.wind.agent.build.PackagedAndroidPlatform
import org.json.JSONObject

/** Loaded by the shared Java service only when the host APK packages providers. */
object EmbeddedRuntimeInventory {
    @JvmStatic
    fun probe(context: Context): JSONObject {
        val appContext = context.applicationContext ?: context
        val applicationClassLoader = appContext.classLoader ?: return JSONObject()
        val privateFilesDirectory = appContext.filesDir.canonicalFile
        val platform = PackagedAndroidPlatform(File(privateFilesDirectory, "embedded-platforms")) {
            appContext.assets.open(it)
        }.resolve(PackagedAndroidPlatform.COMPILE_SDK)
        val kotlinRuntime = PackagedKotlinRuntimeLoader.resolve(appContext)
        val resolver = RuntimeToolResolver.packaged(
            File(privateFilesDirectory, "embedded-provider-probes"),
            bootClasspath = listOfNotNull(platform),
            requireJavaBootstrap = true,
            applicationClassLoader = applicationClassLoader,
            kotlinProviderClassLoader = kotlinRuntime?.classLoader,
            kotlinStdlibJar = kotlinRuntime?.kotlinStdlibJar
        )
        return JSONObject().apply {
            resolver.available().filter { it in CATALOGABLE_IN_PROCESS_TOOL_IDS }.forEach {
                put(it.name, resolver.resolve(it).capabilityVersion)
            }
        }
    }
}
