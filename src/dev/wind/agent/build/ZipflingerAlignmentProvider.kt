package dev.wind.agent.build

import dev.wind.agent.CancellationToken
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.nio.file.Path
import java.util.zip.ZipEntry

/**
 * Loads Zipflinger's public API from the supplied application/host class loader.
 * The provider has no compile-time dependency on the optional Zipflinger artifact.
 */
class ReflectionZipflingerAlignmentProvider(
    private val classLoader: ClassLoader =
        Thread.currentThread().contextClassLoader
            ?: ReflectionZipflingerAlignmentProvider::class.java.classLoader
            ?: ClassLoader.getSystemClassLoader()
) : ApkAlignmentProvider {

    override val available: Boolean
        get() = resolveApiOrNull() != null

    override fun align(
        invocation: ApkAlignmentInvocation,
        token: CancellationToken
    ): ApkAlignmentInvocationResult {
        if (token.isCancelled) return failed("cancelled before alignment")
        if (invocation.alignmentBytes != REQUIRED_ALIGNMENT_BYTES) {
            return failed("Zipflinger provider requires four-byte alignment")
        }

        val api = resolveApiOrNull() ?: return failed("required public Zipflinger API is unavailable")
        var archive: Any? = null
        var archiveCloseStarted = false
        try {
            if (token.isCancelled) return failed("cancelled before source selection")
            val source = api.zipSourceConstructor.newInstance(invocation.inputApk.toPath())
            val entries = api.entries.invoke(source) as? Map<*, *>
                ?: return failed("Zipflinger returned an invalid source entry map")
            val selected = selectZipflingerEntries(
                entries = entries,
                token = token,
                readName = { entry -> api.entryName.invoke(entry) as? String
                    ?: throw IllegalStateException("Zipflinger entry name is not a string") },
                isDirectory = { entry -> api.entryIsDirectory.invoke(entry) as? Boolean
                    ?: throw IllegalStateException("Zipflinger directory flag is not Boolean") },
                compressionFlag = { entry -> (api.entryCompressionFlag.invoke(entry) as? Number)?.toInt()
                    ?: throw IllegalStateException("Zipflinger compression flag is not numeric") },
                selectAligned = { name ->
                    api.selectWithAlignment.invoke(
                        source,
                        name,
                        name,
                        api.compressionNoChange,
                        REQUIRED_ALIGNMENT_BYTES.toLong()
                    )
                },
                selectUnaligned = { name -> api.selectWithoutAlignment.invoke(source, name, name) }
            )
            if (!selected || token.isCancelled) return failed("cancelled during source selection")

            archive = api.zipArchiveConstructor.newInstance(invocation.outputApk.toPath())
            if (token.isCancelled) return failed("cancelled before archive write")
            api.archiveAddZipSource.invoke(archive, source)

            // ZipArchive writes/finalizes synchronously. Cancellation is observed only after close.
            archiveCloseStarted = true
            api.archiveClose.invoke(archive)
            archive = null
            if (token.isCancelled) return failed("cancelled after archive completion")
            return ApkAlignmentInvocationResult(success = true)
        } catch (error: Exception) {
            return failed(describe(error))
        } catch (error: LinkageError) {
            return failed(describe(error))
        } finally {
            if (archive != null && !archiveCloseStarted) {
                runCatching { api.archiveClose.invoke(archive) }
            }
        }
    }

    private fun resolveApiOrNull(): PublicApi? = try {
        resolveApi()
    } catch (_: ReflectiveOperationException) {
        null
    } catch (_: RuntimeException) {
        null
    } catch (_: LinkageError) {
        null
    }

    private fun resolveApi(): PublicApi {
        val zipSourceClass = Class.forName(ZIP_SOURCE_CLASS, false, classLoader)
        val zipArchiveClass = Class.forName(ZIP_ARCHIVE_CLASS, false, classLoader)
        val entryClass = Class.forName(ENTRY_CLASS, false, classLoader)
        val pathClass = Path::class.java

        val noChangeField = zipSourceClass.getField(COMPRESSION_NO_CHANGE_FIELD)
        if (noChangeField.type != Integer.TYPE || noChangeField.getInt(null) != COMPRESSION_NO_CHANGE) {
            throw NoSuchFieldException("ZipSource.COMPRESSION_NO_CHANGE has an unexpected shape")
        }
        return PublicApi(
            zipSourceConstructor = zipSourceClass.getConstructor(pathClass),
            entries = zipSourceClass.getMethod("entries"),
            selectWithoutAlignment = zipSourceClass.getMethod("select", String::class.java, String::class.java),
            selectWithAlignment = zipSourceClass.getMethod(
                "select", String::class.java, String::class.java, Integer.TYPE, java.lang.Long.TYPE
            ),
            compressionNoChange = noChangeField.getInt(null),
            zipArchiveConstructor = zipArchiveClass.getConstructor(pathClass),
            archiveAddZipSource = zipArchiveClass.getMethod("add", zipSourceClass),
            archiveClose = zipArchiveClass.getMethod("close"),
            entryName = entryClass.getMethod("getName"),
            entryIsDirectory = entryClass.getMethod("isDirectory"),
            entryCompressionFlag = entryClass.getMethod("getCompressionFlag")
        )
    }

    private data class PublicApi(
        val zipSourceConstructor: Constructor<*>,
        val entries: Method,
        val selectWithoutAlignment: Method,
        val selectWithAlignment: Method,
        val compressionNoChange: Int,
        val zipArchiveConstructor: Constructor<*>,
        val archiveAddZipSource: Method,
        val archiveClose: Method,
        val entryName: Method,
        val entryIsDirectory: Method,
        val entryCompressionFlag: Method
    )

    private fun failed(message: String) = ApkAlignmentInvocationResult(
        success = false,
        diagnostics = message.take(MAX_DIAGNOSTICS_CHARS)
    )

    private fun describe(error: Throwable): String {
        val cause = if (error is InvocationTargetException) error.targetException ?: error else error
        return "${cause.javaClass.simpleName}: ${cause.message ?: "Zipflinger provider failed"}"
    }

    private companion object {
        const val ZIP_SOURCE_CLASS = "com.android.zipflinger.ZipSource"
        const val ZIP_ARCHIVE_CLASS = "com.android.zipflinger.ZipArchive"
        const val ENTRY_CLASS = "com.android.zipflinger.Entry"
        const val COMPRESSION_NO_CHANGE_FIELD = "COMPRESSION_NO_CHANGE"
        const val COMPRESSION_NO_CHANGE = -2
        const val REQUIRED_ALIGNMENT_BYTES = 4
        const val MAX_DIAGNOSTICS_CHARS = 4_096
    }
}

/** Shared selection loop so cancellation can be tested deterministically. */
internal fun selectZipflingerEntries(
    entries: Map<*, *>,
    token: CancellationToken,
    readName: (Any) -> String,
    isDirectory: (Any) -> Boolean,
    compressionFlag: (Any) -> Int,
    selectAligned: (String) -> Unit,
    selectUnaligned: (String) -> Unit
): Boolean {
    for ((key, value) in entries) {
        if (token.isCancelled) return false
        val name = key as? String ?: throw IllegalStateException("Zipflinger entry key is not a string")
        val entry = value ?: throw IllegalStateException("Zipflinger returned a null entry for $name")
        if (readName(entry) != name) throw IllegalStateException("Zipflinger entry key/name mismatch: $name")
        if (!isDirectory(entry) && compressionFlag(entry) == ZipEntry.STORED) {
            selectAligned(name)
        } else {
            selectUnaligned(name)
        }
    }
    return !token.isCancelled
}
