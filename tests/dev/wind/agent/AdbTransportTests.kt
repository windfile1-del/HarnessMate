package dev.wind.agent

import dev.wind.agent.adb.*
import dev.wind.agent.permissions.*
import java.io.ByteArrayOutputStream
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AdbTransportTests {
    private var checks = 0
    private fun verify(ok: Boolean, message: String) { checks++; check(ok) { message } }
    private fun refused(message: String, action: () -> Unit) {
        var rejected = false
        try { action() } catch (_: Exception) { rejected = true }
        verify(rejected, message)
    }
    private class Wire(bytes: ByteArray, private val fragment: Int = 7) : AdbWireIo {
        var incoming = bytes
        var offset = 0
        var closed = false
        var upgrades = 0
        var writeFailure = false
        var timeoutWhenEmpty = false
        val sent = mutableListOf<AdbPacket>()
        override fun read(data: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
            check(timeoutMs > 0)
            if (timeoutWhenEmpty && this.offset == incoming.size) throw java.io.IOException("ADB_TIMEOUT")
            if (closed || this.offset == incoming.size) return -1
            val count = minOf(length, fragment, incoming.size - this.offset)
            incoming.copyInto(data, offset, this.offset, this.offset + count); this.offset += count
            return count
        }
        override fun write(data: ByteArray, timeoutMs: Int) {
            if (writeFailure) throw SocketTimeoutException("fixture partial write")
            check(!closed)
            val h = AdbProtocol.decodeHeader(data.copyOfRange(0, 24))
            sent.add(AdbPacket(h[0], h[1], h[2], data.copyOfRange(24, data.size)))
        }
        override fun upgradeTls(timeoutMs: Int) { upgrades++ }
        override fun close() { closed = true }
    }
    private fun packets(vararg p: AdbPacket) = p.fold(byteArrayOf()) { a, b -> a + AdbProtocol.encode(b) }
    private fun handshake(features: String = "shell_v2") = packets(AdbPacket(AdbProtocol.STLS, 0x01000000, 0),
        AdbPacket(AdbProtocol.CNXN, AdbProtocol.VERSION, 4096, "device::features=$features\u0000".toByteArray()))
    private fun stream(frames: List<ByteArray>, exit: Boolean = true, local: Int = 1): ByteArray {
        val entries = mutableListOf(AdbPacket(AdbProtocol.OKAY, 11, local))
        frames.forEach { entries.add(AdbPacket(AdbProtocol.WRTE, 11, local, it)) }
        if (exit) entries.add(AdbPacket(AdbProtocol.CLSE, 11, local))
        return packets(*entries.toTypedArray())
    }
    @JvmStatic fun main(args: Array<String>) {
        rawStreamTests()
        bridgeProcessTests()
        localRouteTests()
        streamReuseTests()
        classicAuthTests()
        for (fragment in listOf(1, 2, 7, 24, 65536)) {
            val binary = byteArrayOf(0, 10, 13, -1, 65)
            val stdout = AdbProtocol.shellFrame(1, binary)
            val stderr = AdbProtocol.shellFrame(2, "error\n".toByteArray())
            val end = AdbProtocol.shellFrame(3, byteArrayOf(7))
            val all = stdout + stderr + end
            val wire = Wire(handshake() + stream(listOf(all.copyOfRange(0, 3), all.copyOfRange(3, 8), all.copyOfRange(8, all.size))), fragment)
            val transport = AdbTransport(wire); transport.connect()
            val result = transport.shell("printf fixture", CancellationToken())
            verify(wire.upgrades == 1, "only authenticated TLS transport")
            verify(result.status == "FAILED" && result.exitCode == 7, "exit code controls success")
            verify(result.stdout.contentEquals(binary), "binary output preserved")
            verify(String(result.stderr) == "error\n", "stderr separate")
            verify(result.submitted && !result.json().getBoolean("retry_safe"), "submitted write not automatically replayable")
            verify(wire.sent.first().command == AdbProtocol.CNXN && wire.sent[1].command == AdbProtocol.STLS, "STLS negotiated before service")
            verify(wire.sent.any { it.command == AdbProtocol.OPEN && String(it.payload).startsWith("shell,v2,raw:") }, "shell v2 requested")
            verify(wire.sent.count { it.command == AdbProtocol.OKAY } == 3, "acknowledge every received chunk")
            transport.close(); verify(wire.closed, "native transport closed")
        }
        val limitedWire = Wire(handshake() + stream(listOf(AdbProtocol.shellFrame(1, "abcdef".toByteArray()) +
            AdbProtocol.shellFrame(2, "xyz".toByteArray()) + AdbProtocol.shellFrame(3, byteArrayOf(0)))))
        val limited = AdbTransport(limitedWire); limited.connect()
        val capped = limited.shell("echo", CancellationToken(), maxBytes = 4)
        verify(capped.truncated && String(capped.stdout) == "abcd" && capped.stderr.isEmpty(), "combined output bounded and drained")
        verify(capped.status == "COMPLETED", "truncation preserves exit receipt")
        val unknownWire = Wire(handshake() + stream(listOf(AdbProtocol.shellFrame(1, "started".toByteArray()))))
        val unknown = AdbTransport(unknownWire); unknown.connect()
        val missing = unknown.shell("settings put secure key value", CancellationToken())
        verify(missing.status == "UNKNOWN" && missing.submitted && missing.exitCode == null, "disconnect cannot report failed mutation or retry")
        verify(unknownWire.closed, "uncertain stream invalidates connection")
        val deniedWire = Wire(handshake())
        val denied = AdbTransport(deniedWire); denied.connect()
        val rejected = denied.shell("pm uninstall fixture", CancellationToken(), beforeSend = { error("revoked") })
        verify(rejected.status == "NOT_SUBMITTED" && !rejected.submitted, "revoke before commit sends nothing")
        verify(deniedWire.sent.none { it.command == AdbProtocol.OPEN }, "no OPEN after denied authority")
        val revokedWire = Wire(handshake() + stream(listOf(AdbProtocol.shellFrame(3, byteArrayOf(0)))))
        val revokedTransport = AdbTransport(revokedWire); revokedTransport.connect()
        var authorityChecks = 0
        val revokedResult = revokedTransport.shell("mutation", CancellationToken(), beforeSend = {
            if (++authorityChecks > 1) error("authority revoked after OPEN")
        })
        verify(revokedResult.status == "UNKNOWN" && revokedResult.submitted && !revokedResult.json().getBoolean("retry_safe"),
            "revocation after submission retains uncertainty and prevents automatic retry")
        verify(revokedWire.closed && revokedWire.sent.none { it.command == AdbProtocol.WRTE },
            "no stdin transfer or further commands after authority revocation")
        val brokenWire = Wire(handshake()); val broken = AdbTransport(brokenWire); broken.connect(); brokenWire.writeFailure = true
        verify(broken.shell("mutation", CancellationToken()).status == "UNKNOWN", "partial first write is uncertain")
        val cancelledWire = Wire(handshake()); val cancelled = AdbTransport(cancelledWire); cancelled.connect()
        val token = CancellationToken().apply { cancel() }
        val cancelledResult = cancelled.shell("mutation", token)
        verify(cancelledResult.status == "CANCELLED" && !cancelledResult.submitted, "pre-cancel never submits")
        refused("legacy unencrypted connection rejected") { AdbTransport(Wire(packets(AdbPacket(AdbProtocol.CNXN, AdbProtocol.VERSION, 4096)))).connect() }
        refused("shell v2 absence explicit") { AdbTransport(Wire(handshake("cmd"))).connect() }
        val malformed = AdbProtocol.encode(AdbPacket(AdbProtocol.STLS, 0, 0)).apply { this[20] = 0 }
        refused("malformed magic rejected") { AdbTransport(Wire(malformed)).connect() }
        for (length in listOf(-1, AdbProtocol.MAX_DATA + 1, Int.MAX_VALUE)) {
            val h = AdbProtocol.encode(AdbPacket(AdbProtocol.WRTE, 1, 1)).copyOfRange(0, 24)
            ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN).putInt(12, length)
            refused("oversized and negative payload rejected before allocation") { AdbProtocol.decodeHeader(h) }
        }
        for (bad in listOf(AdbProtocol.shellFrame(7, byteArrayOf()), AdbProtocol.shellFrame(3, byteArrayOf(0, 1)),
            byteArrayOf(1, -1, -1, -1, 127))) {
            val w = Wire(handshake() + stream(listOf(bad))); val t = AdbTransport(w); t.connect()
            verify(t.shell("query", CancellationToken()).status == "UNKNOWN", "bad frame never masquerades as successful completion")
        }
        val mismatched = Wire(handshake() + packets(AdbPacket(AdbProtocol.OKAY, 11, 999)))
        val mismatch = AdbTransport(mismatched); mismatch.connect()
        verify(mismatch.shell("query", CancellationToken()).status == "UNKNOWN", "stream IDs verified")
        val afterExit = Wire(handshake() + stream(listOf(AdbProtocol.shellFrame(3, byteArrayOf(0)) + AdbProtocol.shellFrame(1, byteArrayOf(1)))))
        val after = AdbTransport(afterExit); after.connect()
        verify(after.shell("query", CancellationToken()).status == "UNKNOWN", "data after exit refused")
        val input = ByteArray(10000) { (it % 251).toByte() }
        val stdinWire = Wire(handshake() + packets(AdbPacket(AdbProtocol.OKAY, 11, 1),
            AdbPacket(AdbProtocol.OKAY, 11, 1), AdbPacket(AdbProtocol.OKAY, 11, 1), AdbPacket(AdbProtocol.OKAY, 11, 1),
            AdbPacket(AdbProtocol.OKAY, 11, 1), AdbPacket(AdbProtocol.WRTE, 11, 1,
                AdbProtocol.shellFrame(1, "Success\n".toByteArray()) + AdbProtocol.shellFrame(3, byteArrayOf(0))), AdbPacket(AdbProtocol.CLSE, 11, 1)))
        val uploader = AdbTransport(stdinWire); uploader.connect()
        val upload = uploader.shell("cmd package install -S 10000", CancellationToken(), stdin = input)
        verify(upload.status == "COMPLETED", "streamed APK stdin exits")
        val inputFrames = stdinWire.sent.filter { it.command == AdbProtocol.WRTE }
        verify(inputFrames.all { it.payload.size <= 4096 }, "stdin respects negotiated maxdata")
        verify(inputFrames.last().payload.contentEquals(AdbProtocol.shellFrame(4, byteArrayOf())), "stdin explicit EOF")
        val reconstructed = inputFrames.filter { it.payload.first() == 0.toByte() }.fold(byteArrayOf()) { a, p -> a + p.payload.copyOfRange(5, p.payload.size) }
        verify(reconstructed.contentEquals(input), "input bytes transferred exactly once")
        var notifications = 0
        val store = AgentPermissions()
        val listener = store.observeChanges { notifications++ }
        store.set(AgentCapability.LOCAL_ADB, PermissionScope.TASK, true, taskId = "task")
        store.endTask("task")
        verify(notifications == 2 && !store.enabled(AgentCapability.LOCAL_ADB, "task"), "task end notifies native cancellation immediately")
        listener.close(); store.reset(); verify(notifications == 2, "listener can be removed")
        println("AdbTransportTests: $checks assertions passed")
    }
    private fun rawStreamTests() {
        val session = "a".repeat(32)
        val greeting = dev.wind.agent.phone.PhoneBridgeProtocol.frame("challenge\n中文".toByteArray(Charsets.UTF_8))
        val response = byteArrayOf(0, 13, 10, -1, 65)
        for (fragment in listOf(1,7,65536)) for(suffix in listOf("","_image","_lease")) {
            val wire = Wire(handshake() + stream(listOf(AdbProtocol.shellFrame(3,byteArrayOf(0)))) + packets(
                AdbPacket(AdbProtocol.WRTE,11,1,byteArrayOf(99)), // retired shell frame
                AdbPacket(AdbProtocol.OKAY,22,2),
                AdbPacket(AdbProtocol.WRTE,22,2,greeting.copyOfRange(0,2)),
                AdbPacket(AdbProtocol.WRTE,22,2,greeting.copyOfRange(2,greeting.size)),
                AdbPacket(AdbProtocol.WRTE,22,2,response), // arrives during outgoing ACK wait
                AdbPacket(AdbProtocol.OKAY,22,2)), fragment)
            val transport=AdbTransport(wire); transport.connect()
            verify(transport.shell("id -u",CancellationToken()).status=="COMPLETED","seed retired shell")
            val raw=transport.openRaw("localabstract:vega_phone_$session$suffix")
            val size=dev.wind.agent.phone.PhoneBridgeProtocol.length(raw.readExact(4),1048576)
            verify(String(raw.readExact(size),Charsets.UTF_8)=="challenge\n中文","raw service survives fragmentation without shell decoder")
            raw.write(byteArrayOf(42)); verify(raw.readExact(5).contentEquals(response),"raw binary response retained while waiting for ACK")
            verify(wire.sent.count { it.command==AdbProtocol.OPEN }==2,"no service replay on stale frames")
            raw.close(); verify(wire.closed,"raw closes its dedicated connection")
        }
        refused("raw cannot open arbitrary daemon/shell services") { AdbTransport(Wire(byteArrayOf())).openRaw("shell:reboot") }
        refused("raw cannot open arbitrary sibling endpoint") { AdbTransport(Wire(byteArrayOf())).openRaw("localabstract:vega_phone_${session}_commands") }
        val closedWire=Wire(handshake()+packets(AdbPacket(AdbProtocol.OKAY,22,1),AdbPacket(AdbProtocol.CLSE,0,1)))
        val closedTransport=AdbTransport(closedWire); closedTransport.connect(); val ended=closedTransport.openRaw("localabstract:vega_phone_${session}_lease")
        val closeError=runCatching { ended.readExact(1) }.exceptionOrNull()
        verify(closeError is java.io.EOFException && closeError.message=="ADB_RAW_CLOSED","raw CLOSED identifies a peer CLSE, without asserting process death"); ended.close()
        val wire=Wire(handshake()+packets(AdbPacket(AdbProtocol.OKAY,22,1),AdbPacket(AdbProtocol.WRTE,23,1,byteArrayOf(1))))
        val transport=AdbTransport(wire); transport.connect(); val raw=transport.openRaw("localabstract:vega_phone_$session")
        refused("raw rejects different remote stream ID") { raw.readExact(1) }; raw.close()
    }
    private fun bridgeProcessTests() {
        val digest="a".repeat(64); val session="b".repeat(32)
        val command="CLASSPATH='/data/local/tmp/vega-phone/bridge-$digest.jar' exec app_process / dev.wind.phonebridge.Main"
        val message=byteArrayOf(0,0,0,5,0,10,13,-1,65)
        val framed=AdbProtocol.shellFrame(2,"private stderr must not enter protocol".toByteArray())+AdbProtocol.shellFrame(1,message)
        for(fragment in listOf(1,2,7,24,4096)) {
            val wire=Wire(handshake()+packets(AdbPacket(AdbProtocol.OKAY,41,1),AdbPacket(AdbProtocol.OKAY,41,1),
                AdbPacket(AdbProtocol.WRTE,41,1,framed.copyOfRange(0,4)),AdbPacket(AdbProtocol.WRTE,41,1,framed.copyOfRange(4,framed.size))),fragment)
            val transport=AdbTransport(wire); transport.connect(); val process=transport.openBridgeProcess(command)
            process.write("private config\n".toByteArray())
            verify(process.readExact(message.size).contentEquals(message),"managed shell preserves binary Bridge stdout under fragmentation")
            val open=wire.sent.single { it.command==AdbProtocol.OPEN }
            verify(String(open.payload)=="shell,v2,raw:$command\u0000","foreground process uses shell-v2, not localabstract or background launcher")
            val input=wire.sent.single { it.command==AdbProtocol.WRTE }.payload
            verify(input[0]==0.toByte() && String(input.copyOfRange(5,input.size))=="private config\n","config stays in shell stdin frame")
            val diagnostic=process.processDiagnostic()
            verify(diagnostic.getLong("stderr_bytes")>0 && !diagnostic.toString().contains("private stderr"),"stderr is counted without leaking text or corrupting RPC")
            verify(!wire.sent.any { it.command==AdbProtocol.CLSE },"process owner stays open between requests")
            process.close(); verify(wire.closed,"closing task owner closes foreground process transport")
        }
        for(channel in listOf("image","lease")) {
            val relay="CLASSPATH='/data/local/tmp/vega-phone/bridge-$digest.jar' exec app_process / dev.wind.phonebridge.SocketRelay $session $channel"
            val wire=Wire(handshake()+packets(AdbPacket(AdbProtocol.OKAY,41,1)))
            val transport=AdbTransport(wire); transport.connect(); transport.openBridgeProcess(relay).close()
            verify(String(wire.sent.last().payload).contains("SocketRelay $session $channel"),"only fixed image/lease relay is opened")
        }
        for(commandBad in listOf(command+" &",command+"; id",command.replace(digest,"../escape"),command.replace("Main","SocketRelay $session control"),command.replace("Main","Other"))) {
            val wire=Wire(handshake()); val transport=AdbTransport(wire); transport.connect()
            refused("arbitrary process command rejected") { transport.openBridgeProcess(commandBad) }
            verify(wire.sent.none { it.command==AdbProtocol.OPEN },"invalid command has no submitted process")
            transport.close()
        }
        val exited=Wire(handshake()+packets(AdbPacket(AdbProtocol.OKAY,41,1),AdbPacket(AdbProtocol.WRTE,41,1,AdbProtocol.shellFrame(3,byteArrayOf(7)))))
        val transport=AdbTransport(exited); transport.connect(); val pipe=transport.openBridgeProcess(command)
        val failure=runCatching { pipe.readExact(1) }.exceptionOrNull()
        verify(failure?.message=="ADB_BRIDGE_PROCESS_EXITED_7" && pipe.processDiagnostic().getInt("process_exit_code")==7,"startup process exit is distinguished from socket service rejection"); pipe.close()
        val rejected=Wire(handshake()+packets(AdbPacket(AdbProtocol.CLSE,0,1))); val rejectTransport=AdbTransport(rejected); rejectTransport.connect()
        val rejectedError=runCatching { rejectTransport.openBridgeProcess(command) }.exceptionOrNull()
        verify(rejectedError?.message=="ADB_BRIDGE_PROCESS_OPEN_REJECTED","shell process service rejection is distinguished"); rejectTransport.close()
        val altered=Wire(handshake()+packets(AdbPacket(AdbProtocol.OKAY,41,1),AdbPacket(AdbProtocol.WRTE,42,1,AdbProtocol.shellFrame(1,message))))
        val strict=AdbTransport(altered); strict.connect(); val strictPipe=strict.openBridgeProcess(command)
        refused("managed process does not relax stream-id checks") { strictPipe.readExact(1) }; strictPipe.close()
        val decoder=AdbBridgePipeDecoder()
        decoder.feed(AdbProtocol.shellFrame(3,byteArrayOf(0)))
        refused("managed stdout after exit is invalid") { decoder.feed(AdbProtocol.shellFrame(1,byteArrayOf(1))) }
    }
    private fun localRouteTests() {
        val wifi = AdbRouteCandidate(101, "WIFI", true)
        val cell = AdbRouteCandidate(102, "CELLULAR", true)
        val vpn = AdbRouteCandidate(103, "WIFI", false)
        verify(AdbLocalNetwork.select(false, listOf(wifi)).handle == 0L, "no VPN preserves successful default loopback path")
        val bound = AdbLocalNetwork.select(true, listOf(cell, vpn, wifi))
        verify(bound.handle == 101L && bound.name == "LOOPBACK_THEN_WIFI" && bound.vpnDetected, "VPN chooses physical WiFi fallback without selecting VPN transport")
        verify(AdbLocalNetwork.select(true, listOf(vpn, cell)).handle == 102L, "local-only cellular route available when WiFi is absent")
        verify(AdbLocalNetwork.select(true, listOf(AdbRouteCandidate(0, "WIFI", true), vpn)).name == "VPN_DEFAULT_LOOPBACK", "unavailable physical route is labeled, never fabricated")
        verify(AdbLocalNetwork.select(true, listOf(AdbRouteCandidate(104, "ETHERNET", true), cell)).handle == 104L, "Ethernet preferred over cellular")
    }
    private fun streamReuseTests() {
        val cnxn = packets(AdbPacket(AdbProtocol.CNXN, AdbProtocol.VERSION, 4096,
            "device::features=shell_v2\u0000".toByteArray()))
        fun reply(local: Int, remote: Int, text: String, zeroClose: Boolean = false) = packets(
            AdbPacket(AdbProtocol.OKAY, remote, local),
            AdbPacket(AdbProtocol.WRTE, remote, local, AdbProtocol.shellFrame(1, text.toByteArray()) + AdbProtocol.shellFrame(3, byteArrayOf(0))),
            AdbPacket(AdbProtocol.CLSE, if (zeroClose) 0 else remote, local))
        for (tls in listOf(false, true)) for (fragment in listOf(1, 7, 65536)) {
            val bytes = (if (tls) handshake() else cnxn) + reply(1, 11, "2000\n") +
                // A delayed stdin ACK and close/data for the verified id -u probe
                // are still queued when the next service OPEN is sent.
                packets(AdbPacket(AdbProtocol.OKAY, 11, 1), AdbPacket(AdbProtocol.CLSE, 0, 1),
                    AdbPacket(AdbProtocol.WRTE, 11, 1, "retired data".toByteArray())) +
                reply(2, 22, "properties\n", zeroClose = true) +
                packets(AdbPacket(AdbProtocol.CLSE, 22, 2), AdbPacket(AdbProtocol.OKAY, 11, 1)) +
                reply(3, 33, "third\n")
            val wire = Wire(bytes, fragment); val transport = AdbTransport(wire)
            transport.connect(mode = if (tls) AdbConnectionMode.TLS else AdbConnectionMode.TCP)
            val probe = transport.shell("id -u", CancellationToken())
            val query = transport.shell("getprop", CancellationToken())
            val third = transport.shell("echo third", CancellationToken())
            verify(probe.status == "COMPLETED" && String(probe.stdout) == "2000\n", "identity probe completes")
            verify(query.status == "COMPLETED" && String(query.stdout) == "properties\n", "late probe frames do not break next service")
            verify(third.status == "COMPLETED" && String(third.stdout) == "third\n" && !wire.closed, "three commands reuse the same verified connection")
            verify(wire.sent.filter { it.command == AdbProtocol.OPEN }.map { it.arg0 } == listOf(1, 2, 3), "fresh local stream IDs, one OPEN per operation")
            verify(wire.sent.any { it.command == AdbProtocol.CLSE && it.arg0 == 2 && it.arg1 == 22 }, "legacy sender-zero CLOSE acknowledged using known remote ID")
        }
        val future = Wire(cnxn + reply(1, 11, "2000\n") + packets(AdbPacket(AdbProtocol.OKAY, 33, 3)))
        val unknown = AdbTransport(future); unknown.connect(mode = AdbConnectionMode.TCP); unknown.shell("id -u", CancellationToken())
        val invalid = unknown.shell("getprop", CancellationToken())
        verify(invalid.status == "UNKNOWN" && invalid.error.startsWith("ADB_STREAM_ID_INVALID") && future.closed, "unallocated receiver is still rejected, not treated as stale")
        verify(invalid.error.contains("expected_local=2") && invalid.error.contains("received_local=3"), "ID mismatch exposes metadata without command or output")
        val wrong = Wire(cnxn + reply(1, 11, "2000\n") + packets(AdbPacket(AdbProtocol.WRTE, 99, 1, byteArrayOf(1))))
        val spoof = AdbTransport(wrong); spoof.connect(mode = AdbConnectionMode.TCP); spoof.shell("id -u", CancellationToken())
        verify(spoof.shell("query", CancellationToken()).error.startsWith("ADB_STREAM_ID_INVALID") && wrong.closed, "retired stream still verifies sender identity")
        val zeroWrite = Wire(cnxn + packets(AdbPacket(AdbProtocol.OKAY, 11, 1), AdbPacket(AdbProtocol.WRTE, 0, 1, byteArrayOf(1))))
        val zero = AdbTransport(zeroWrite); zero.connect(mode = AdbConnectionMode.TCP)
        verify(zero.shell("query", CancellationToken()).error.startsWith("ADB_STREAM_ID_INVALID"), "zero sender is only allowed for CLOSE")
        val refusedWire = Wire(cnxn + packets(AdbPacket(AdbProtocol.CLSE, 0, 1)))
        val unopened = AdbTransport(refusedWire); unopened.connect(mode = AdbConnectionMode.TCP)
        verify(unopened.shell("query", CancellationToken()).error == "ADB_SERVICE_OPEN_REJECTED", "failed OPEN distinguished from stream mismatch")
        val payloadWire = Wire(cnxn + packets(AdbPacket(AdbProtocol.OKAY, 11, 1, byteArrayOf(1))))
        val payload = AdbTransport(payloadWire); payload.connect(mode = AdbConnectionMode.TCP)
        verify(payload.shell("query", CancellationToken()).error == "ADB_STREAM_CONTROL_INVALID", "nonempty legacy control payload rejected")
        val flood = Array(129) { AdbPacket(AdbProtocol.CLSE, 11, 1) }
        val floodWire = Wire(cnxn + reply(1, 11, "2000\n") + packets(*flood))
        val bounded = AdbTransport(floodWire); bounded.connect(mode = AdbConnectionMode.TCP); bounded.shell("id -u", CancellationToken())
        verify(bounded.shell("query", CancellationToken()).error == "ADB_STALE_STREAM_FRAME_LIMIT", "stale frame flood is bounded without service replay")
    }
    private fun classicAuthTests() {
        val cnxn = AdbPacket(AdbProtocol.CNXN, AdbProtocol.VERSION, 4096, "device::features=shell_v2\u0000".toByteArray())
        val challenge = AdbPacket(AdbProtocol.AUTH, 1, 0, ByteArray(20) { it.toByte() })
        val publicKey = "fixture-base64 user@localhost\u0000".toByteArray()
        var signatures = 0; var offered = 0
        val auth = object : AdbAuthenticator {
            override fun sign(token: ByteArray): ByteArray { verify(token.contentEquals(challenge.payload), "AUTH token forwarded without hashing"); signatures++; return ByteArray(256) { 42 } }
            override fun publicKey(): ByteArray { offered++; return publicKey }
        }
        verify(AdbConnectionMode.AUTO.resolve(5555) == AdbConnectionMode.TCP, "5555 auto selects classic TCP without TLS pairing")
        verify(AdbConnectionMode.AUTO.resolve(37123) == AdbConnectionMode.TLS && AdbConnectionMode.TLS.resolve(5555) == AdbConnectionMode.TLS, "TLS selection does not silently downgrade")
        val knownWire = Wire(packets(challenge, cnxn), 1)
        AdbTransport(knownWire, auth).connect(mode = AdbConnectionMode.TCP)
        verify(signatures == 1 && offered == 0 && knownWire.upgrades == 0, "known RSA identity reconnects without new authorization or TLS")
        verify(knownWire.sent[1].command == AdbProtocol.AUTH && knownWire.sent[1].arg0 == 2 && knownWire.sent[1].payload.size == 256, "AUTH signature frame")
        val newWire = Wire(packets(challenge, challenge, cnxn), 2); val phases = mutableListOf<String>()
        AdbTransport(newWire, auth).connect(mode = AdbConnectionMode.TCP, onProgress = phases::add)
        verify(signatures == 2 && offered == 1 && phases.last() == "WAITING_DEVICE_AUTHORIZATION", "new App key prompts system authorization once")
        verify(newWire.sent.last().arg0 == 3 && newWire.sent.last().payload.contentEquals(publicKey), "AUTH public key includes terminator")
        val zeroChecksumAuth = AdbProtocol.encode(challenge).apply { ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putInt(16, 0) }
        val modernWire = Wire(zeroChecksumAuth + packets(cnxn), 1)
        AdbTransport(modernWire, auth).connect(mode = AdbConnectionMode.TCP)
        verify(modernWire.sent.any { it.command == AdbProtocol.AUTH && it.arg0 == 2 }, "modern adbd AUTH permits checksum omission before peer CNXN")
        refused("TLS port never falls back to AUTH") { AdbTransport(Wire(packets(challenge)), auth).connect() }
        refused("classic mode cannot connect TLS port") { AdbTransport(Wire(handshake()), auth).connect(mode = AdbConnectionMode.TCP) }
        refused("AUTH token length validated before signing") { AdbTransport(Wire(packets(challenge.copy(payload = ByteArray(19)))), auth).connect(mode = AdbConnectionMode.TCP) }
        refused("AUTH challenge type cannot be peer signature") { AdbTransport(Wire(packets(challenge.copy(arg0 = 2))), auth).connect(mode = AdbConnectionMode.TCP) }
        refused("repeated rejected key is bounded") { AdbTransport(Wire(packets(challenge, challenge, challenge)), auth).connect(mode = AdbConnectionMode.TCP) }
        val timeoutWire = Wire(packets(challenge, challenge)).apply { timeoutWhenEmpty = true }
        var authError = ""
        try { AdbTransport(timeoutWire, auth).connect(mode = AdbConnectionMode.TCP) } catch (e: Exception) { authError = e.message.orEmpty() }
        verify(authError == "ADB_DEVICE_AUTHORIZATION_TIMEOUT", "native IOException timeout becomes visible system authorization timeout")
        var steps = 0
        refused("cancelled setup cannot offer public key") { AdbTransport(Wire(packets(challenge, challenge)), auth).connect(mode = AdbConnectionMode.TCP, beforeStep = { if (++steps > 2) error("cancelled") }) }
        val response = "restarting in TCP mode port: 5555\n".toByteArray()
        val restartWire = Wire(packets(challenge, cnxn) + packets(AdbPacket(AdbProtocol.OKAY, 11, 1), AdbPacket(AdbProtocol.WRTE, 11, 1, response), AdbPacket(AdbProtocol.CLSE, 11, 1)))
        val restart = AdbTransport(restartWire, auth); restart.connect(mode = AdbConnectionMode.TCP)
        val receipt = restart.restartTcpip(CancellationToken())
        verify(receipt.status == "RESTART_REQUEST_ACKNOWLEDGED" && receipt.submitted && !receipt.json().getBoolean("retry_safe"), "TCP activation requires actual daemon acknowledgement, not shell exit")
        verify(restartWire.closed && restartWire.sent.count { it.command == AdbProtocol.OPEN } == 1 && restartWire.sent.any { it.command == AdbProtocol.OPEN && String(it.payload) == "tcpip:5555\u0000" }, "setup restart service submitted once then closes connection")
        val lostWire = Wire(packets(challenge, cnxn)); val lost = AdbTransport(lostWire, auth); lost.connect(mode = AdbConnectionMode.TCP)
        verify(lost.restartTcpip(CancellationToken()).status == "UNKNOWN" && lostWire.closed, "lost restart receipt cannot prove activation or trigger replay")
    }
}
