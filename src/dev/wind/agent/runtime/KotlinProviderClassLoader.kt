package dev.wind.agent.runtime

import dalvik.system.DexClassLoader
import dev.wind.agent.build.PackagedKotlinRuntime
import java.io.File
import java.net.URI
import java.net.URL
import java.util.Collections
import java.util.Enumeration
import java.util.LinkedHashMap
import java.util.jar.JarFile

/** Dex loader with provider-owned namespaces isolated from Vega's Kotlin runtime. */
class KotlinProviderClassLoader(
    dexPath: String,
    optimizedDirectory: String,
    parent: ClassLoader,
    resourceJars: List<File>
) : DexClassLoader(dexPath, optimizedDirectory, null, parent) {
    private val providerPrefixes = PackagedKotlinRuntime.CHILD_FIRST_PREFIXES
    private val providerClasses = setOf("FirNativeForwardDeclarationGetClassCallChecker")
    private val resourceArchives: List<File> = resourceJars.map { original ->
        val canonical = original.canonicalFile
        require(original.isFile && canonical == original.absoluteFile) {
            "KOTLIN_RUNTIME_RESOURCE_PATH_INVALID"
        }
        canonical
    }

    init {
        require(File(dexPath).isFile && File(dexPath).canonicalFile == File(dexPath).absoluteFile) {
            "KOTLIN_RUNTIME_DEX_PATH_INVALID"
        }
        require(File(optimizedDirectory).isDirectory &&
            File(optimizedDirectory).canonicalFile == File(optimizedDirectory).absoluteFile) {
            "KOTLIN_RUNTIME_OPTIMIZED_PATH_INVALID"
        }
    }

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (isParentOnly(name)) return loadFromParent(name)
        if (!isProviderOwned(name)) return super.loadClass(name, resolve)
        val loaded = synchronized(this) {
            findLoadedClass(name) ?: try {
                findClass(name)
            } catch (_: ClassNotFoundException) {
                loadFromParent(name)
            }
        }
        if (resolve && loaded.classLoader === this) resolveClass(loaded)
        return loaded
    }

    override fun getResource(name: String): URL? =
        findProviderResource(name) ?: super.getResource(name)

    override fun getResources(name: String): Enumeration<URL> {
        val ordered = LinkedHashMap<String, URL>()
        findProviderResources(name).forEach { ordered[it.toExternalForm()] = it }
        val inherited = super.getResources(name)
        while (inherited.hasMoreElements()) {
            val resource = inherited.nextElement()
            val key = resource.toExternalForm()
            if (!ordered.containsKey(key)) ordered[key] = resource
        }
        return Collections.enumeration(ordered.values)
    }

    private fun isProviderOwned(name: String): Boolean =
        providerClasses.contains(name) || providerPrefixes.any { prefix -> name.startsWith(prefix) }

    private fun isParentOnly(name: String): Boolean =
        name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("android.") ||
            name.startsWith("dalvik.") || name.startsWith("sun.")

    private fun loadFromParent(name: String): Class<*> = parent?.loadClass(name) ?: Class.forName(name, false, null)

    private fun findProviderResource(name: String): URL? = findProviderResources(name).firstOrNull()

    private fun findProviderResources(name: String): List<URL> {
        if (name.isEmpty() || name.startsWith('/') || name.contains('\\') ||
            name.split('/').any { it == ".." }) return emptyList()
        val result = ArrayList<URL>()
        for (archive in resourceArchives) {
            val exists = try {
                JarFile(archive, false).use { jar ->
                    val entry = jar.getJarEntry(name)
                    entry != null && !entry.isDirectory
                }
            } catch (_: Exception) {
                false
            }
            if (!exists) continue
            try {
                val escaped = URI(null, null, "/$name", null).rawPath.substring(1)
                val jarUrl = "jar:${archive.toURI().toURL().toExternalForm()}!/$escaped"
                result.add(URL(jarUrl))
            } catch (_: Exception) {
                // An invalid resource path is absent, not a reason to break class loading.
            }
        }
        return result
    }
}
