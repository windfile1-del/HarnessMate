package dev.wind.agent.adb

import dev.wind.agent.CancellationToken
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Injectable byte transport. Android uses the privately linked native TLS implementation. */
interface AdbWireIo : Closeable {
    fun read(data: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int
    fun write(data: ByteArray, timeoutMs: Int)
    fun upgradeTls(timeoutMs: Int)
}

enum class AdbConnectionMode {
    AUTO, TLS, TCP;
    fun resolve(port: Int): AdbConnectionMode = if (this == AUTO) { if (port == 5555) TCP else TLS } else this
}
interface AdbAuthenticator {
    fun sign(token: ByteArray): ByteArray
    fun publicKey(): ByteArray
}

data class AdbResult(val operationId: String = UUID.randomUUID().toString(), val status: String,
    val stdout: ByteArray = byteArrayOf(), val stderr: ByteArray = byteArrayOf(), val exitCode: Int? = null,
    val truncated: Boolean = false, val submitted: Boolean = false, val error: String = "") {
    fun json() = JSONObject().put("operation_id", operationId).put("status", status)
        .put("stdout", String(stdout, Charsets.UTF_8)).put("stderr", String(stderr, Charsets.UTF_8))
        .put("exit_code", exitCode ?: JSONObject.NULL).put("truncated", truncated).put("submitted", submitted)
        .put("error", error).put("retry_safe", !submitted)
}

data class AdbPacket(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray = byteArrayOf())

object AdbProtocol {
    const val CNXN = 0x4e584e43; const val STLS = 0x534c5453; const val AUTH = 0x48545541
    const val OPEN = 0x4e45504f; const val OKAY = 0x59414b4f; const val WRTE = 0x45545257; const val CLSE = 0x45534c43
    const val VERSION = 0x01000001; const val MAX_DATA = 1024 * 1024
    fun encode(p: AdbPacket): ByteArray {
        require(p.payload.size <= MAX_DATA) { "ADB_PACKET_TOO_LARGE" }
        val sum = p.payload.sumOf { it.toInt() and 255 }
        return ByteBuffer.allocate(24 + p.payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(p.command).putInt(p.arg0).putInt(p.arg1).putInt(p.payload.size).putInt(sum)
            .putInt(p.command.inv()).put(p.payload).array()
    }
    fun decodeHeader(bytes: ByteArray): IntArray {
        require(bytes.size == 24) { "ADB_HEADER_INVALID" }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val h = IntArray(6) { b.int }
        require(h[5] == h[0].inv() && h[3] in 0..MAX_DATA) { "ADB_HEADER_INVALID" }
        require(h[0] in setOf(CNXN, STLS, AUTH, OPEN, OKAY, WRTE, CLSE)) { "ADB_COMMAND_INVALID" }
        return h
    }
    fun shellFrame(id: Int, data: ByteArray): ByteArray = ByteBuffer.allocate(5 + data.size)
        .order(ByteOrder.LITTLE_ENDIAN).put(id.toByte()).putInt(data.size).put(data).array()
}

/** A single operation owns the connection; shell v2 keeps binary stdout and stderr distinct. */
class AdbTransport(private val io: AdbWireIo, private val authenticator: AdbAuthenticator? = null) : Closeable {
    private var maxData = 4096
    private var skipChecksum = false
    private var nextId = 1
    // Closed sockets remain addressable briefly in adbd's queued transport frames.
    // IDs are never reused; retain a bounded history rather than applying an old
    // stream's ACK/CLOSE to the next command (including after the identity probe).
    private val retiredStreams = LinkedHashMap<Int, Int>()
    @Volatile private var closed = false
    val features = mutableSetOf<String>()
    private fun remaining(deadline: Long): Int {
        if (closed) throw EOFException("ADB_DISCONNECTED")
        val left = deadline - System.nanoTime()
        if (left <= 0) throw SocketTimeoutException("ADB_TIMEOUT")
        return (left / 1_000_000).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
    }
    private fun exact(size: Int, deadline: Long): ByteArray {
        val bytes = ByteArray(size); var done = 0
        while (done < size) {
            val n = io.read(bytes, done, size - done, remaining(deadline))
            if (n <= 0) throw EOFException("ADB_DISCONNECTED")
            require(n <= size - done) { "ADB_READ_INVALID" }; done += n
        }
        return bytes
    }
    private fun receive(deadline: Long): AdbPacket {
        val h = AdbProtocol.decodeHeader(exact(24, deadline))
        val data = exact(h[3], deadline)
        // Modern adbd learns our advertised version before sending AUTH, and may
        // already omit its checksum before it sends its own CNXN/version.
        if (!skipChecksum && !(h[0] == AdbProtocol.CNXN && h[1] >= AdbProtocol.VERSION) &&
            !(h[0] == AdbProtocol.AUTH && h[4] == 0))
            require(h[4] == data.sumOf { it.toInt() and 255 }) { "ADB_CHECKSUM_INVALID" }
        return AdbPacket(h[0], h[1], h[2], data)
    }
    private fun send(p: AdbPacket, deadline: Long) = io.write(AdbProtocol.encode(p), remaining(deadline))
    private fun allocateId(): Int {
        check(nextId > 0) { "ADB_STREAM_IDS_EXHAUSTED" }
        return nextId++
    }
    private fun retire(local: Int, remote: Int) {
        retiredStreams[local] = remote
        if (retiredStreams.size > 64) retiredStreams.remove(retiredStreams.keys.first())
    }
    private fun receiveStream(local: Int, remote: Int, deadline: Long, beforeStep: () -> Unit): AdbPacket {
        var discarded = 0
        while (true) {
            beforeStep()
            val p = receive(deadline)
            val control = p.command == AdbProtocol.OKAY || p.command == AdbProtocol.CLSE
            require(!control || p.payload.isEmpty()) { "ADB_STREAM_CONTROL_INVALID" }
            // Older adbd may use sender ID zero for CLOSE, also after a successful
            // OPEN. It is not valid for WRTE/OKAY or for a different receiver ID.
            fun senderMatches(expected: Int) = (p.command == AdbProtocol.CLSE && p.arg0 == 0) ||
                (p.arg0 > 0 && (expected == 0 || p.arg0 == expected))
            if (p.arg1 == local) {
                require(senderMatches(remote)) {
                    "ADB_STREAM_ID_INVALID: command=${p.command}, expected_local=$local, expected_remote=$remote, received_local=${p.arg1}, received_remote=${p.arg0}"
                }
                return p
            }
            val retired = retiredStreams[p.arg1]
            require(retired != null && senderMatches(retired) &&
                p.command in setOf(AdbProtocol.OKAY, AdbProtocol.WRTE, AdbProtocol.CLSE)) {
                "ADB_STREAM_ID_INVALID: command=${p.command}, expected_local=$local, expected_remote=$remote, received_local=${p.arg1}, received_remote=${p.arg0}"
            }
            require(++discarded <= 128) { "ADB_STALE_STREAM_FRAME_LIMIT" }
            // AOSP responds to late READY for a closed local socket with CLOSE.
            // Late WRTE/CLOSE for that socket is discarded, without affecting the
            // active stream, feeding its decoder, or replaying a service OPEN.
            if (p.command == AdbProtocol.OKAY) {
                beforeStep(); send(AdbPacket(AdbProtocol.CLSE, p.arg1, p.arg0), deadline)
            }
        }
    }
    fun connect(timeoutMs: Int = 15000, mode: AdbConnectionMode = AdbConnectionMode.TLS,
                beforeStep: () -> Unit = {}, onProgress: (String) -> Unit = {}) {
        require(mode != AdbConnectionMode.AUTO) { "ADB_MODE_MUST_BE_RESOLVED" }
        val deadline = System.nanoTime() + timeoutMs.toLong() * 1_000_000
        beforeStep()
        send(AdbPacket(AdbProtocol.CNXN, AdbProtocol.VERSION, AdbProtocol.MAX_DATA,
            "host::features=shell_v2\u0000".toByteArray()), deadline)
        onProgress("WAITING_ADB_HANDSHAKE")
        var p = receive(deadline)
        if (mode == AdbConnectionMode.TLS) {
            // An explicitly selected TLS connection never downgrades to TCP AUTH.
            require(p.command == AdbProtocol.STLS && p.arg0 == 0x01000000 && p.arg1 == 0 && p.payload.isEmpty()) { "ADB_TLS_PAIRING_REQUIRED" }
            beforeStep(); send(AdbPacket(AdbProtocol.STLS, 0x01000000, 0), deadline)
            onProgress("TLS_HANDSHAKE"); io.upgradeTls(remaining(deadline)); beforeStep()
            onProgress("WAITING_ADB_CONNECT"); p = receive(deadline)
        } else {
            require(p.command != AdbProtocol.STLS) { "ADB_ENDPOINT_REQUIRES_TLS_MODE" }
            var signed = false; var offered = false
            while (p.command == AdbProtocol.AUTH) {
                beforeStep()
                require(p.arg0 == 1 && p.arg1 == 0 && p.payload.size == 20) { "ADB_AUTH_TOKEN_INVALID" }
                val auth = requireNotNull(authenticator) { "ADB_RSA_AUTH_UNAVAILABLE" }
                if (!signed) {
                    onProgress("RSA_AUTHENTICATING")
                    val signature = auth.sign(p.payload)
                    require(signature.size == 256) { "ADB_AUTH_SIGNATURE_INVALID" }
                    beforeStep(); send(AdbPacket(AdbProtocol.AUTH, 2, 0, signature), deadline); signed = true
                } else if (!offered) {
                    val key = auth.publicKey()
                    require(key.size in 2..4096 && key.last() == 0.toByte() && key.dropLast(1).none { it == 0.toByte() }) { "ADB_AUTH_PUBLIC_KEY_INVALID" }
                    onProgress("WAITING_DEVICE_AUTHORIZATION")
                    beforeStep(); send(AdbPacket(AdbProtocol.AUTH, 3, 0, key), deadline); offered = true
                } else error("ADB_DEVICE_AUTHORIZATION_REJECTED")
                try { p = receive(deadline) } catch (error: Exception) {
                    if (offered && (error is SocketTimeoutException || error.message == "ADB_TIMEOUT"))
                        throw SocketTimeoutException("ADB_DEVICE_AUTHORIZATION_TIMEOUT") else throw error
                }
            }
        }
        beforeStep()
        require(p.command == AdbProtocol.CNXN && p.arg0 >= 0x01000000 && p.arg1 in 4096..AdbProtocol.MAX_DATA) { "ADB_AUTHENTICATION_FAILED" }
        skipChecksum = p.arg0 >= AdbProtocol.VERSION
        maxData = p.arg1
        val banner = String(p.payload, Charsets.UTF_8).trimEnd('\u0000')
        banner.substringAfter("features=", "").substringBefore(';').split(',').filter(String::isNotBlank).forEach(features::add)
        require("shell_v2" in features) { "ADB_SHELL_V2_REQUIRED" }
    }
    /** User-only setup control; never a shell command and never retried after submission. */
    fun restartTcpip(token: CancellationToken, beforeStep: () -> Unit = {}): AdbResult {
        val deadline = System.nanoTime() + 5_000_000_000L
        val output = ByteArrayOutputStream(); val local = allocateId(); var remote = 0; var submitted = false
        val cancellation = token.onCancel { close() }
        try {
            token.throwIfCancelled(); beforeStep(); submitted = true
            send(AdbPacket(AdbProtocol.OPEN, local, 0, "tcpip:5555\u0000".toByteArray()), deadline)
            while (true) {
                token.throwIfCancelled(); beforeStep()
                val p = receiveStream(local, remote, deadline) { token.throwIfCancelled(); beforeStep() }
                when (p.command) {
                    AdbProtocol.OKAY -> { require(remote == 0 && p.arg0 > 0) { "ADB_STREAM_PROTOCOL_INVALID" }; remote = p.arg0 }
                    AdbProtocol.WRTE -> {
                        require(remote > 0 && output.size() + p.payload.size <= 4096) { "ADB_CONTROL_RESPONSE_INVALID" }
                        output.write(p.payload); send(AdbPacket(AdbProtocol.OKAY, local, remote), deadline)
                    }
                    AdbProtocol.CLSE -> break
                    else -> error("ADB_CONTROL_PROTOCOL_INVALID")
                }
            }
        } catch (error: Exception) {
            return AdbResult(status = if (submitted) "UNKNOWN" else "NOT_SUBMITTED", stdout = output.toByteArray(),
                submitted = submitted, error = error.message ?: "ADB_TCPIP_REQUEST_FAILED")
        } finally { cancellation.close(); close() }
        val reply = String(output.toByteArray(), Charsets.UTF_8).trim()
        return AdbResult(status = if (reply == "restarting in TCP mode port: 5555") "RESTART_REQUEST_ACKNOWLEDGED" else "UNKNOWN",
            stdout = output.toByteArray(), submitted = submitted, error = if (reply == "restarting in TCP mode port: 5555") "" else "ADB_TCPIP_COMPLETION_UNKNOWN")
    }
    fun shell(command: String, token: CancellationToken, timeoutMs: Int = 30000, maxBytes: Int = 262144,
              stdin: ByteArray? = null, beforeSend: () -> Unit = {}): AdbResult {
        require(command.isNotBlank() && command.length <= 32768 && !command.contains('\u0000')) { "ADB_COMMAND_INVALID" }
        require(timeoutMs in 1..120000 && maxBytes in 1..16777216) { "ADB_LIMIT_INVALID" }
        val deadline = System.nanoTime() + timeoutMs.toLong() * 1_000_000
        val operation = UUID.randomUUID().toString(); val local = allocateId()
        val stdout = ByteArrayOutputStream(); val stderr = ByteArrayOutputStream()
        val decoder = ShellDecoder(maxBytes, stdout, stderr)
        var submitted = false; var remote = 0; var opened = false
        var inputOffset = 0; var stdinClosed = false; var awaitingAck = false
        val cancellation = token.onCancel { close() }
        try {
            token.throwIfCancelled(); beforeSend()
            // Mark uncertain before the first write: a partial transport write can still deliver a command.
            submitted = true
            send(AdbPacket(AdbProtocol.OPEN, local, 0, "shell,v2,raw:$command\u0000".toByteArray()), deadline)
            while (true) {
                token.throwIfCancelled(); beforeSend()
                val p = receiveStream(local, remote, deadline) { token.throwIfCancelled(); beforeSend() }
                when (p.command) {
                    AdbProtocol.OKAY -> {
                        if (!opened) { require(p.arg0 > 0) { "ADB_STREAM_ID_INVALID" }; remote = p.arg0; opened = true }
                        awaitingAck = false
                    }
                    AdbProtocol.WRTE -> {
                        require(opened) { "ADB_STREAM_NOT_OPEN" }
                        decoder.feed(p.payload)
                        token.throwIfCancelled(); beforeSend()
                        send(AdbPacket(AdbProtocol.OKAY, local, remote), deadline)
                    }
                    AdbProtocol.CLSE -> {
                        if (remote != 0) send(AdbPacket(AdbProtocol.CLSE, local, remote), deadline)
                        require(opened) { "ADB_SERVICE_OPEN_REJECTED" }
                        require(opened && decoder.exitCode != null && decoder.complete()) { "ADB_EXIT_STATUS_MISSING" }
                        retire(local, remote)
                        return AdbResult(operation, if (decoder.exitCode == 0) "COMPLETED" else "FAILED", stdout.toByteArray(),
                            stderr.toByteArray(), decoder.exitCode, decoder.truncated, submitted)
                    }
                    else -> error("ADB_STREAM_PROTOCOL_INVALID")
                }
                if (opened && !awaitingAck && !stdinClosed && decoder.exitCode == null) {
                    token.throwIfCancelled(); beforeSend()
                    val available = stdin?.size ?: 0
                    val frame = if (inputOffset < available) {
                        val end = minOf(available, inputOffset + minOf(65536, maxData - 5))
                        AdbProtocol.shellFrame(0, stdin!!.copyOfRange(inputOffset, end)).also { inputOffset = end }
                    } else { stdinClosed = true; AdbProtocol.shellFrame(4, byteArrayOf()) }
                    send(AdbPacket(AdbProtocol.WRTE, local, remote, frame), deadline); awaitingAck = true
                }
            }
        } catch (e: Exception) {
            close()
            return AdbResult(operation, if (submitted) "UNKNOWN" else if (token.isCancelled) "CANCELLED" else "NOT_SUBMITTED",
                stdout.toByteArray(), stderr.toByteArray(), truncated = decoder.truncated, submitted = submitted,
                error = if (token.isCancelled) "ADB_CANCELLED_COMPLETION_UNKNOWN" else e.message ?: "ADB_TRANSPORT_FAILED")
        } finally { cancellation.close() }
    }
    /** Dedicated connection only. This stream deliberately has no shell-v2 decoder. */
    fun openRaw(service: String, beforeStep: () -> Unit = {}): RawStream {
        require(service.matches(Regex("localabstract:vega_phone_[a-f0-9]{32}(_image|_lease)?"))) { "ADB_RAW_SERVICE_INVALID" }
        val deadline = System.nanoTime() + 10_000_000_000L
        val local = allocateId()
        beforeStep(); send(AdbPacket(AdbProtocol.OPEN, local, 0, (service + "\u0000").toByteArray()), deadline)
        val ready = receiveStream(local, 0, deadline, beforeStep)
        check(ready.command == AdbProtocol.OKAY) { "ADB_RAW_SERVICE_REJECTED" }
        return RawStream(local, ready.arg0, beforeStep)
    }
    /** Fixed first-party process only; owns stdin until close, with separate binary stdout/stderr. */
    fun openBridgeProcess(command: String, beforeStep: () -> Unit = {}): RawStream {
        val prefix="CLASSPATH='/data/local/tmp/vega-phone/bridge-[a-f0-9]{64}\\.jar' exec app_process / dev\\.wind\\.phonebridge\\."
        require(command.matches(Regex(prefix+"(Main|SocketRelay [a-f0-9]{32} (image|lease))"))) { "ADB_BRIDGE_PROCESS_COMMAND_INVALID" }
        val deadline=System.nanoTime()+10_000_000_000L
        val local=allocateId()
        beforeStep(); send(AdbPacket(AdbProtocol.OPEN,local,0,("shell,v2,raw:$command\u0000").toByteArray()),deadline)
        val ready=receiveStream(local,0,deadline,beforeStep)
        check(ready.command==AdbProtocol.OKAY) { "ADB_BRIDGE_PROCESS_OPEN_REJECTED" }
        return RawStream(local,ready.arg0,beforeStep,true)
    }
    inner class RawStream internal constructor(private val local: Int, private val remote: Int,
        private val authority: () -> Unit,private val shellV2: Boolean=false) : Closeable {
        private var pending = byteArrayOf()
        private val process=if(shellV2) AdbBridgePipeDecoder() else null
        fun processDiagnostic(): JSONObject = process?.diagnostic() ?: JSONObject().put("transport","LOCAL_ABSTRACT")
        private fun packet(deadline: Long): AdbPacket {
            authority()
            val p = receiveStream(local, remote, deadline, authority)
            if (p.command == AdbProtocol.WRTE) {
                check(pending.size + p.payload.size <= 16777220) { "ADB_RAW_BUFFER_LIMIT" }
                pending += if(process==null) p.payload else process.feed(p.payload)
                authority(); send(AdbPacket(AdbProtocol.OKAY, local, remote), deadline)
            } else when(p.command) {
                AdbProtocol.OKAY -> Unit
                AdbProtocol.CLSE -> throw EOFException(if(process==null) "ADB_RAW_CLOSED" else process.exitCode?.let { "ADB_BRIDGE_PROCESS_EXITED_$it" } ?: "ADB_BRIDGE_PROCESS_CLOSED")
                else -> error("ADB_RAW_PROTOCOL_INVALID")
            }
            return p
        }
        @Synchronized fun write(bytes: ByteArray, timeoutMs: Int = 10000) {
            require(bytes.size <= 1048580 && timeoutMs in 1..120000)
            val deadline = System.nanoTime() + timeoutMs.toLong() * 1_000_000
            var offset = 0
            while (offset < bytes.size) {
                authority(); val end = minOf(bytes.size, offset + maxData - if(shellV2) 5 else 0)
                val bytesToSend=bytes.copyOfRange(offset,end).let { if(shellV2) AdbProtocol.shellFrame(0,it) else it }
                send(AdbPacket(AdbProtocol.WRTE, local, remote, bytesToSend), deadline)
                do { val p = packet(deadline) } while (p.command != AdbProtocol.OKAY)
                offset = end
            }
        }
        @Synchronized fun readExact(size: Int, timeoutMs: Int = 10000): ByteArray {
            authority()
            require(size in 0..16777216 && timeoutMs in 1..120000)
            val deadline = System.nanoTime() + timeoutMs.toLong() * 1_000_000
            while (pending.size < size) {
                process?.exitCode?.let { throw EOFException("ADB_BRIDGE_PROCESS_EXITED_$it") }
                packet(deadline)
            }
            return pending.copyOfRange(0, size).also { pending = pending.copyOfRange(size, pending.size); authority() }
        }
        override fun close() = this@AdbTransport.close()
    }
    override fun close() { closed = true; io.close() }
}

/** Incremental shell-v2 framing for a long-lived Bridge, never mixing stderr with RPC bytes. */
internal class AdbBridgePipeDecoder {
    private var pending=byteArrayOf()
    @Volatile var exitCode: Int?=null; private set
    @Volatile private var stderrBytes=0L
    fun diagnostic()=JSONObject().put("transport","MANAGED_SHELL_V2").put("process_exit_code",exitCode ?: JSONObject.NULL)
        .put("stderr_bytes",stderrBytes).put("stderr_content_returned",false)
    fun feed(bytes: ByteArray): ByteArray {
        require(pending.size+bytes.size<=2*AdbProtocol.MAX_DATA+5) { "ADB_SHELL_FRAME_TOO_LARGE" }
        pending+=bytes; val stdout=ByteArrayOutputStream(); var offset=0
        while(pending.size-offset>=5) {
            val id=pending[offset].toInt() and 255
            val size=ByteBuffer.wrap(pending,offset+1,4).order(ByteOrder.LITTLE_ENDIAN).int
            require(id in 1..3 && size in 0..AdbProtocol.MAX_DATA && (id!=3 || size==1)) { "ADB_SHELL_FRAME_INVALID" }
            if(pending.size-offset-5<size) break
            require(exitCode==null) { "ADB_DATA_AFTER_EXIT" }
            when(id) {
                1 -> stdout.write(pending,offset+5,size)
                2 -> stderrBytes=(stderrBytes+size).coerceAtMost(Long.MAX_VALUE/2)
                3 -> exitCode=pending[offset+5].toInt() and 255
            }
            offset+=5+size
        }
        pending=pending.copyOfRange(offset,pending.size); return stdout.toByteArray()
    }
}

/** Incremental shell framing survives arbitrary ADB packet fragmentation. */
internal class ShellDecoder(private val limit: Int, private val stdout: ByteArrayOutputStream, private val stderr: ByteArrayOutputStream) {
    private var pending = byteArrayOf()
    var exitCode: Int? = null; private set
    var truncated = false; private set
    fun complete() = pending.isEmpty()
    fun feed(data: ByteArray) {
        require(pending.size + data.size <= 2 * AdbProtocol.MAX_DATA + 5) { "ADB_SHELL_FRAME_TOO_LARGE" }
        pending += data
        var offset = 0
        while (pending.size - offset >= 5) {
            val id = pending[offset].toInt() and 255
            val size = ByteBuffer.wrap(pending, offset + 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
            require(id in 1..3 && size in 0..AdbProtocol.MAX_DATA && (id != 3 || size == 1)) { "ADB_SHELL_FRAME_INVALID" }
            if (pending.size - offset - 5 < size) break
            require(exitCode == null) { "ADB_DATA_AFTER_EXIT" }
            if (id == 3) exitCode = pending[offset + 5].toInt() and 255
            else {
                val available = (limit - stdout.size() - stderr.size()).coerceAtLeast(0)
                val count = minOf(size, available)
                (if (id == 1) stdout else stderr).write(pending, offset + 5, count)
                if (count < size) truncated = true
            }
            offset += 5 + size
        }
        pending = pending.copyOfRange(offset, pending.size)
    }
}
