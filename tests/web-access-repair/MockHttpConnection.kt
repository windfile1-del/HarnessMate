package dev.wind.agent.testing

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MockHttpConnection(
    url: URL,
    var responseCodeValue: Int = 200,
    var responseBody: ByteArray = ByteArray(0),
    var responseHeaders: Map<String, String> = emptyMap(),
    var onConnect: (() -> Unit)? = null,
    var blockUntilDisconnect: Boolean = false
) : HttpURLConnection(url) {

    val recordedRequestHeaders = mutableMapOf<String, String>()
    val recordedOutputStream = ByteArrayOutputStream()
    val disconnected = AtomicBoolean(false)
    val connectedFlag = AtomicBoolean(false)
    val disconnectLatch = CountDownLatch(1)
    var fixedLengthStreaming: Int? = null

    override fun connect() {
        if (connectedFlag.compareAndSet(false, true)) {
            onConnect?.invoke()
        }
    }

    override fun disconnect() {
        disconnected.set(true)
        disconnectLatch.countDown()
    }

    override fun usingProxy(): Boolean = false

    override fun setRequestProperty(key: String, value: String) {
        recordedRequestHeaders[key] = value
    }

    override fun getRequestProperty(key: String): String? = recordedRequestHeaders[key]

    override fun getResponseCode(): Int {
        connect()
        if (blockUntilDisconnect) {
            disconnectLatch.await(5, TimeUnit.SECONDS)
            throw SocketException("Socket closed by disconnect")
        }
        return responseCodeValue
    }

    override fun getContentType(): String? =
        responseHeaders["Content-Type"] ?: responseHeaders["content-type"]

    override fun getHeaderField(name: String): String? {
        val entry = responseHeaders.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
        return entry?.value
    }

    override fun getInputStream(): InputStream {
        connect()
        if (blockUntilDisconnect) {
            disconnectLatch.await(5, TimeUnit.SECONDS)
            throw SocketException("Socket closed by disconnect")
        }
        return object : ByteArrayInputStream(responseBody) {
            override fun read(): Int {
                if (disconnected.get()) throw SocketException("Socket closed")
                return super.read()
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (disconnected.get()) throw SocketException("Socket closed")
                return super.read(b, off, len)
            }
        }
    }

    override fun getOutputStream(): OutputStream {
        return recordedOutputStream
    }

    override fun setFixedLengthStreamingMode(contentLength: Int) {
        fixedLengthStreaming = contentLength
    }
}
