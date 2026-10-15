package dev.wind.agent

import dev.wind.agent.shell.mirror.MirrorSink
import dev.wind.agent.shell.mirror.MirrorSyncBridgeServer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest

/** The Termux reverse data plane must stream beyond its old 16 MiB capture ceiling. */
object LargeMirrorBridgeTests {
    @JvmStatic fun main(args: Array<String>) {
        val directory = Files.createTempDirectory("large-mirror-bridge-").toFile()
        try {
            val source = File(directory, "source.bin")
            val target = File(directory, "target.bin")
            val size = 34L * 1024 * 1024 + 137
            val digest = MessageDigest.getInstance("SHA-256")
            source.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024) { it.toByte() }
                var left = size
                while (left > 0) {
                    val count = minOf(left, buffer.size.toLong()).toInt()
                    output.write(buffer, 0, count); digest.update(buffer, 0, count); left -= count
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            check(runCatching { MirrorSink("buffered", size, size, hash) }.isFailure)
            target.outputStream().use { destination ->
                val sink = MirrorSink("streamed", size, size, hash, destination)
                MirrorSyncBridgeServer(ByteArray(0), ByteArray(0), { error("no forward source") }, { false }, listOf(sink)).use { server ->
                    val connection = URL(server.url + "/sink/streamed").openConnection() as HttpURLConnection
                    try {
                        connection.requestMethod = "POST"
                        connection.doOutput = true
                        connection.connectTimeout = 10_000; connection.readTimeout = 30_000
                        connection.setFixedLengthStreamingMode(size)
                        connection.outputStream.use { output -> source.inputStream().use { it.copyTo(output, 64 * 1024) } }
                        check(connection.responseCode == 200)
                        connection.inputStream.use { it.readBytes() }
                        server.failure?.let { throw it }
                        server.requireSink(sink.id)
                        check(runCatching { server.sinkBytes(sink.id) }.exceptionOrNull()?.message == "MIRROR_SINK_IS_STREAMED")
                    } finally { connection.disconnect() }
                }
            }
            check(target.length() == size)
            val actual = MessageDigest.getInstance("SHA-256")
            target.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; actual.update(buffer, 0, count) }
            }
            check(actual.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == hash)
            println("PASS LargeMirrorBridgeTests: 5 assertions; payload=$size; maxHeap=${Runtime.getRuntime().maxMemory()}")
        } finally { directory.deleteRecursively() }
    }
}
