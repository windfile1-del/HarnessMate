package dev.wind.agent.runtime

import android.content.Context
import dev.wind.agent.build.PackagedKotlinRuntime
import java.io.File

data class KotlinRuntimeHandle(
    val classLoader: ClassLoader,
    val kotlinStdlibJar: File,
    val version: String
)

/** Creates the single isolated app-owned Kotlin provider loader used by later activation stages. */
object PackagedKotlinRuntimeLoader {
    private const val VERSION = PackagedKotlinRuntime.PROVIDER_VERSION
    private val lock = Any()
    @Volatile private var cached: KotlinRuntimeHandle? = null

    fun resolve(context: Context): KotlinRuntimeHandle? {
        return try {
            val appContext = context.applicationContext ?: context
            // Context may expose a framework-owned alias such as /data/user/0/...;
            // canonicalize only these trusted roots before entering strict file primitives.
            val privateFilesRoot = appContext.filesDir.canonicalFile
            if (!privateFilesRoot.isDirectory) return null
            val privateRoot = File(privateFilesRoot, "embedded-kotlin/$VERSION")
            val payload = PackagedKotlinRuntime(privateRoot) { assetPath -> appContext.assets.open(assetPath) }
                .resolve() ?: return null
            val parent = appContext.classLoader ?: return null
            synchronized(lock) {
                cached?.let { handle ->
                    if (handle.version == payload.version && handle.kotlinStdlibJar == payload.kotlinStdlib &&
                        handle.classLoader.parent === parent) return@synchronized handle
                }

                val canonicalCodeCacheRoot = appContext.codeCacheDir.canonicalFile
                if (!canonicalCodeCacheRoot.isDirectory) return@synchronized null
                val optimizedDirectory = File(canonicalCodeCacheRoot, "kotlin-provider/$VERSION")
                if (optimizedDirectory.canonicalFile != optimizedDirectory.absoluteFile) return@synchronized null
                if (!optimizedDirectory.isDirectory && !optimizedDirectory.mkdirs()) return@synchronized null
                if (optimizedDirectory.canonicalFile != optimizedDirectory.absoluteFile) return@synchronized null

                val loader = KotlinProviderClassLoader(
                    payload.dexContainer.absolutePath,
                    optimizedDirectory.absolutePath,
                    parent,
                    payload.resourceJars
                )
                KotlinRuntimeHandle(loader, payload.kotlinStdlib, payload.version).also { cached = it }
            }
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        }
    }
}
