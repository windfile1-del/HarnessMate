package dev.wind.agent

import dev.wind.agent.phone.*
import dev.wind.agent.permissions.*
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer

object PhoneBackendTests {
    private var checks=0
    private fun verify(value:Boolean,label:String) { checks++; check(value) { label } }
    private fun refused(label:String,operation:()->Unit) { var failed=false; try { operation() } catch(_:Exception) { failed=true }; verify(failed,label) }
    @JvmStatic fun main(args:Array<String>) {
        verify(PhoneBridgeProtocol.hmac("key","The quick brown fox jumps over the lazy dog")=="f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8","independent HMAC golden vector")
        for(n in listOf(1,4096,1048576)) {
            val input=ByteArray(n) { (it%256).toByte() }; val framed=PhoneBridgeProtocol.frame(input)
            verify(PhoneBridgeProtocol.length(framed.copyOfRange(0,4),1048576)==n && framed.copyOfRange(4,framed.size).contentEquals(input),"length framing preserves binary bytes")
        }
        refused("empty frames denied") { PhoneBridgeProtocol.frame(byteArrayOf()) }
        refused("request allocation bounded") { PhoneBridgeProtocol.frame(ByteArray(1048577)) }
        for(n in listOf(-1,0,1048577,Int.MAX_VALUE)) refused("invalid JSON size $n") { PhoneBridgeProtocol.length(ByteBuffer.allocate(4).putInt(n).array(),1048576) }
        verify(PhoneBridgeProtocol.length(ByteBuffer.allocate(4).putInt(16777216).array(),16777216)==16777216,"separate image budget")
        refused("image overflow denied") { PhoneBridgeProtocol.length(ByteBuffer.allocate(4).putInt(16777217).array(),16777216) }
        val store=AgentPermissions()
        val gate=AgentPermissionGate(store) { null }
        val visual=gate.needs("phone_observe",JSONObject().put("backend","adb").put("mode","visual")).map { it.capability }.toSet()
        verify(visual==setOf(AgentCapability.LOCAL_ADB,AgentCapability.SCREEN),"pure visual does not require UI tree authority")
        val ui=gate.needs("phone_observe",JSONObject().put("backend","adb").put("mode","ui")).map { it.capability }.toSet()
        verify(ui==setOf(AgentCapability.LOCAL_ADB,AgentCapability.ACCESSIBILITY),"UI default does not capture")
        val sequence=gate.needs("phone_sequence",JSONObject().put("backend","adb")).map { it.capability }.toSet()
        verify(sequence==setOf(AgentCapability.LOCAL_ADB,AgentCapability.ACCESSIBILITY),"sequence uses fixed device scope, no raw shell")
        verify(ToolRegistry.isMutating("phone_sequence") && !ToolRegistry.isMutating("phone_observe"),"PLAN treats sequence as mutation")
        fun observation(id:String,owner:String)=PhoneObservation(owner,"adb_bridge",7,9,JSONObject().put("observation_id",id).put("mode","ui").put("package","com.example.chat").put("nodes",JSONArray()))
        PhoneObservationStore.add(observation("a","task-a")); PhoneObservationStore.add(observation("b","task-b")); PhoneObservationStore.invalidate("task-a")
        verify(PhoneObservationStore.take("a")==null && PhoneObservationStore.take("b")?.owner=="task-b","task invalidation does not erase another owner")
        verify(PhoneObservationStore.take("b",true)!=null && PhoneObservationStore.take("b")==null,"consumed observation cannot be replayed")
        val before=JSONObject().put("package","com.example.chat").put("window","uia:12").put("width",1080).put("height",2400).put("rotation",0).put("display_id",0)
        PhoneBackends.samePage(before,JSONObject(before.toString())); verify(true,"matching structural identity")
        for(k in listOf("package","window","width","height","rotation","display_id")) refused("structural $k changed") { PhoneBackends.samePage(before,JSONObject(before.toString()).put(k,"different")) }
        refused("structural event change rejects old observations") { PhoneBackends.samePage(JSONObject(before.toString()).put("structural_epoch",1),JSONObject(before.toString()).put("structural_epoch",2)) }
        println("PhoneBackendTests: $checks checks PASS; actual bridge/OEM/IME NOT_RUN")
    }
}
