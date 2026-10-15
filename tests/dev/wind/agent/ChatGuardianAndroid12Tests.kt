package dev.wind.agent

import dev.wind.agent.adb.*
import dev.wind.agent.phone.*
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.io.DataInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.json.JSONObject
import org.json.JSONArray

/** API31 incident regression through the production shell-v2 transport; no phone, accounts or messages. */
object ChatGuardianAndroid12Tests {
    private var checks=0
    private fun ok(value: Boolean,why: String) { checks++; check(value) { why } }
    private fun refused(why: String,block: ()->Unit) { ok(runCatching(block).isFailure,why) }
    private fun reply(id: String="request",display: Int=64,session: String="display-session")=JSONObject()
        .put("status","COMPLETED").put("version",2).put("request_id",id).put("display_session_id",session)
        .put("display_id",display).put("event_epoch",0).put("package","")
    private fun readFrames(o: JSONObject,delay: Long=0,clock: ()->Long={0L}): JSONObject {
        val payload=o.toString().toByteArray(Charsets.UTF_8); var stage=0
        return ChatEventStreamPolicy.readResponse({ n,ms->
            if(stage++==0) { ok(ms>ChatEventStreamPolicy.LEGACY_WAIT_MS,"metadata deadline exceeds legacy server hold");
                ok(n==4,"length prefix read"); ByteBuffer.allocate(4).putInt(payload.size).array() }
            else { ok(ms>0 && n==payload.size,"body bounded"); if(delay>=ms) throw SocketTimeoutException("ADB_TIMEOUT"); payload }
        },ChatEventStreamPolicy.readTimeout(10000),"request","display-session",64,clock)
    }
    @JvmStatic fun main(args: Array<String>) {
        ok(ChatEventStreamPolicy.readTimeout(5000)==15000,"new server wait has 10-second transport margin")
        ok(ChatEventStreamPolicy.readTimeout(10000)==15000,"old payload wait has 5-second margin")
        refused("bad server wait") { ChatEventStreamPolicy.readTimeout(0) }
        refused("unbounded server wait") { ChatEventStreamPolicy.readTimeout(10001) }
        ok(!ChatEventStreamPolicy.fatal("ADB_TIMEOUT",true),"healthy display survives timeout")
        ok(!ChatEventStreamPolicy.fatal("ADB_BRIDGE_PROCESS_CLOSED",true),"relay EOF does not destroy healthy control owner")
        ok(ChatEventStreamPolicy.fatal("ADB_TIMEOUT",false),"actual lost authority remains terminal")
        for(code in listOf("CHAT_EVENT_RESPONSE_INVALID","PHONE_BRIDGE_AUTH_FAILED","VIRTUAL_DISPLAY_SESSION_CHANGED","PHONE_BRIDGE_FRAME_LIMIT"))
            ok(ChatEventStreamPolicy.fatal(code,true),"invalid authenticated scope is terminal: $code")
        ok((1..8).map { ChatEventStreamPolicy.delay(it) }==listOf(1000L,3000L,10000L,30000L,30000L,30000L,30000L,30000L),"bounded backoff")
        ok(ChatEventStreamPolicy.state(1)=="RECONNECTING" && ChatEventStreamPolicy.state(4)=="POLLING","repeated failures retain local reconciliation")
        ok(ChatEventStreamPolicy.POLL_INTERVAL_MS==10000L,"local polling stays finite and does not call a model")
        ok(readFrames(reply()).getLong("event_epoch")==0L,"idle snapshot valid")
        for(o in listOf(reply(id="other"),reply(display=0),reply(display=65),reply(session="wrong"),reply().put("version",1),reply().put("event_epoch",-1)))
            refused("reject altered metadata scope") { readFrames(o) }
        refused("frame cannot bypass limit") {
            ChatEventStreamPolicy.readResponse({ _,_->ByteBuffer.allocate(4).putInt(PhoneBridgeProtocol.JSON_LIMIT+1).array() },15000,"request","display-session",64)
        }
        var time=0L; val bytes=reply().toString().toByteArray(); val budgets=mutableListOf<Int>()
        ChatEventStreamPolicy.readResponse({ n,ms->budgets.add(ms); if(n==4) { time=11000; ByteBuffer.allocate(4).putInt(bytes.size).array() } else bytes },15000,"request","display-session",64) { time }
        ok(budgets==listOf(15000,4000),"header and body share a total deadline")
        val navigation=ChatSetupReadiness.diagnostic("CHAT_EXACT_PEER_REQUIRED")!!
        ok(navigation.first=="NAVIGATION_REQUIRED" && navigation.second.contains("screen=virtual"),"missing exact chat remains SETUP on the original display")
        ok(ChatSetupReadiness.diagnostic("CHAT_SINGLE_TEXT_EDITOR_REQUIRED")!!.first=="TEXT_EDITOR_REQUIRED","editor prerequisite differs from transport failure")
        ok(ChatSetupReadiness.diagnostic("CHAT_DRAFT_PRESENT")!!.first=="USER_DRAFT_PRESENT","draft requires takeover")
        for(code in listOf("ADB_TIMEOUT","CHAT_DISPLAY_LOST","CHAT_LEASE_REVOKED","CHAT_NEW_USER_AUTHORIZATION_REQUIRED"))
            ok(ChatSetupReadiness.diagnostic(code)==null,"real errors not disguised as setup: $code")
        val list=JSONObject().put("package","com.tencent.mm").put("display_id",64).put("width",720).put("height",1280)
            .put("nodes",JSONArray().put(JSONObject().put("node_id","0.1").put("text","微信").put("bounds",JSONArray(listOf(200,90,400,140)))))
        val failure=runCatching { ChatAppAdapter.parse(list,"com.tencent.mm","江素心🐷") }.exceptionOrNull()
        ok(failure?.message=="CHAT_EXACT_PEER_REQUIRED","message list diagnosis checks recipient before editor")
        idleTransport()
        println("PASS ChatGuardianAndroid12Tests: $checks checks; real loopback shell-v2 idle 10.2s; Android API31 service/device NOT_RUN")
    }
    private fun idleTransport() {
        val server=ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())
        var serverFailure: Throwable?=null
        val thread=Thread({
            try { server.accept().use { peer->
                val input=DataInputStream(peer.getInputStream()); val output=peer.getOutputStream()
                fun packet(): AdbPacket { val header=ByteArray(24); input.readFully(header); val h=AdbProtocol.decodeHeader(header); val data=ByteArray(h[3]); input.readFully(data); return AdbPacket(h[0],h[1],h[2],data) }
                fun send(p: AdbPacket) { output.write(AdbProtocol.encode(p)); output.flush() }
                check(packet().command==AdbProtocol.CNXN)
                send(AdbPacket(AdbProtocol.CNXN,AdbProtocol.VERSION,4096,"device::features=shell_v2\u0000".toByteArray()))
                val open=packet(); check(open.command==AdbProtocol.OPEN)
                val local=open.arg0; send(AdbPacket(AdbProtocol.OKAY,11,local))
                check(packet().command==AdbProtocol.WRTE); send(AdbPacket(AdbProtocol.OKAY,11,local))
                Thread.sleep(10200) // old default 10s read would time out before this response arrives
                val payload=reply().toString().toByteArray(Charsets.UTF_8)
                val framed=PhoneBridgeProtocol.frame(payload)
                send(AdbPacket(AdbProtocol.WRTE,11,local,AdbProtocol.shellFrame(1,framed)))
                check(packet().command==AdbProtocol.OKAY)
            } } catch(e:Throwable) { serverFailure=e }
        },"api31-event-idle-fixture").apply { isDaemon=true; start() }
        val socket=Socket(java.net.InetAddress.getLoopbackAddress(),server.localPort)
        val transport=AdbTransport(object: AdbWireIo {
            override fun read(data: ByteArray,offset: Int,length: Int,timeoutMs: Int): Int { socket.soTimeout=timeoutMs; return socket.getInputStream().read(data,offset,length) }
            override fun write(data: ByteArray,timeoutMs: Int) { socket.getOutputStream().write(data); socket.getOutputStream().flush() }
            override fun upgradeTls(timeoutMs: Int) { error("TCP-only fixture") }
            override fun close() { socket.close() }
        })
        try {
            transport.connect(mode=AdbConnectionMode.TCP)
            val path="/data/local/tmp/vega-phone/bridge-"+("a".repeat(64))+".jar"
            val raw=transport.openBridgeProcess("CLASSPATH='$path' exec app_process / dev.wind.phonebridge.SocketRelay "+("b".repeat(32))+" lease")
            raw.write(PhoneBridgeProtocol.frame(JSONObject().put("method","events_wait").toString().toByteArray()))
            val started=System.nanoTime()
            val result=ChatEventStreamPolicy.readResponse({ n,ms->raw.readExact(n,ms) },15000,"request","display-session",64)
            ok((System.nanoTime()-started)/1000000>=10000,"real idle reproducer exceeded old 10-second deadline")
            ok(result.getInt("display_id")==64,"same display survived idle reply")
            thread.join(2000); ok(serverFailure==null && !thread.isAlive,"shell-v2 WRTE fully acknowledged without stream loss")
        } finally { transport.close(); server.close() }
    }
}
