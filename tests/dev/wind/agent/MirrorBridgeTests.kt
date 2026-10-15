package dev.wind.agent

import dev.wind.agent.shell.mirror.MirrorSyncBridgeServer
import dev.wind.agent.shell.mirror.MirrorSink
import dev.wind.agent.shell.mirror.TextEditEngine
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import java.security.MessageDigest

object MirrorBridgeTests {
    @JvmStatic fun main(args: Array<String>) {
        var opened = 0
        MirrorSyncBridgeServer("helper".toByteArray(), "{}".toByteArray(), {
            check(it == 0); opened++; ByteArrayInputStream(ByteArray(2 * 1024 * 1024) { 42 })
        }, { false }).use { server ->
            check(URL(server.url + "/control").readText() == "{}")
            check(URL(server.url + "/helper").readText() == "helper")
            check(URL(server.url + "/cancel").readText() == "0")
            check(URL(server.url + "/file/0").readBytes().size == 2 * 1024 * 1024)
            check(opened == 1)
            val rejected = URL(server.url.substringBeforeLast('/') + "/wrong/control").openConnection() as HttpURLConnection
            check(rejected.responseCode == 403); rejected.disconnect()
            val post = URL(server.url + "/result").openConnection() as HttpURLConnection
            post.requestMethod = "POST"; post.doOutput = true
            val payload = "{\"ok\":true}".toByteArray()
            post.setFixedLengthStreamingMode(payload.size)
            post.outputStream.use { it.write(payload) }
            check(post.inputStream.use { String(it.readBytes()) } == "OK")
            check(server.result!!.contentEquals(payload))
            check(server.failure == null)
            post.disconnect()
        }
        val sinkPayload = "reverse-stream".repeat(257).toByteArray()
        val sinkHash = MessageDigest.getInstance("SHA-256")
            .digest(sinkPayload).joinToString("") { "%02x".format(it.toInt() and 255) }
        MirrorSyncBridgeServer(byteArrayOf(), byteArrayOf(), { error("no source") }, { false }, listOf(
            MirrorSink("changed", sinkPayload.size.toLong(), sinkPayload.size.toLong(), sinkHash)
        )).use { server ->
            val post = URL(server.url + "/sink/changed").openConnection() as HttpURLConnection
            post.requestMethod = "POST"; post.doOutput = true
            post.setFixedLengthStreamingMode(sinkPayload.size)
            post.outputStream.use { it.write(sinkPayload) }
            check(post.inputStream.use { String(it.readBytes()) } == "OK")
            check(server.sinkBytes("changed").contentEquals(sinkPayload))
            post.disconnect()
        }
        check(TextEditEngine.apply("abc", JSONObject().put("old_string", "b").put("new_string", "B")) == "aBc")
        check(runCatching { TextEditEngine.apply("aa", JSONObject().put("old_string", "a").put("new_string", "b")) }.isFailure)
        val largeSize = 2L * 1024 * 1024 * 1024
        MirrorSyncBridgeServer(byteArrayOf(), byteArrayOf(), {
            object : java.io.InputStream() {
                var remaining = largeSize
                override fun read(): Int = if (remaining-- > 0) 0 else -1
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (remaining == 0L) return -1
                    val count = minOf(remaining, length.toLong()).toInt()
                    java.util.Arrays.fill(buffer, offset, offset + count, 0.toByte())
                    remaining -= count
                    return count
                }
            }
        }, { false }).use { server ->
            var received = 0L
            URL(server.url + "/file/0").openStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    received += count
                }
            }
            check(received == largeSize)
            check(server.failure == null)
        }
        println("MirrorBridgeTests: socket transfer, reverse sinks, token rejection, result framing and text edits passed")
    }
}
