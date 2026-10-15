package dev.wind.agent

import dev.wind.agent.phone.*
import dev.wind.agent.permissions.*
import org.json.JSONArray
import org.json.JSONObject

object PhoneWorkflowTests {
    private var checks=0
    private fun verify(ok:Boolean,message:String) { checks++; check(ok) { message } }
    private fun node(id:String,text:String,left:Int=10)=JSONObject().put("node_id",id).put("path","0.1").put("text",text).put("class","Button")
        .put("resource_id","com.example:id/button").put("bounds",JSONArray(listOf(left,100,left+100,200))).put("clickable",true).put("enabled",true).put("visible",true)
    private fun page(pkg:String="com.example",window:Int=1,nodes:List<JSONObject> = listOf(node("current","Search")))=JSONObject().put("status","COMPLETED")
        .put("observation_id","fresh-$window").put("mode","ui").put("package",pkg).put("window",window).put("width",1080).put("height",2400)
        .put("rotation",0).put("display_id",0).put("nodes",JSONArray(nodes)).put("structural_epoch",window)
    private fun target(text:String)=JSONObject().put("text",text).put("exact",true)
    private fun step(text:String)=JSONObject().put("action","click").put("expected_package","com.example").put("target",target(text))
    private fun plan(vararg steps:JSONObject)=JSONObject().put("steps",JSONArray(steps.toList()))
    @JvmStatic fun main(args:Array<String>) {
        val gate=AgentPermissionGate(AgentPermissions()) { null }
        verify(gate.needs("phone_flow",JSONObject().put("backend","adb")).map { it.capability }.toSet()==setOf(AgentCapability.LOCAL_ADB,AgentCapability.ACCESSIBILITY),"flow needs UI + ADB, no raw shell or screen")
        verify(ToolRegistry.isMutating("phone_flow"),"flow denied in PLAN")
        verify(PhoneReady.resolve(page(),target("Search"))=="current","unique exact fresh selector")
        val legacy=node("unused","Search").also { it.remove("node_id"); it.remove("path"); it.put("id","n25") }
        verify(PhoneReady.resolve(page(nodes=listOf(legacy)),target("Search"))=="n25","legacy id schema resolves without ADB node_id")
        verify(PhoneObservation("task","legacy",0,0,page(nodes=listOf(legacy))).nodes.single().first=="n25","both backend schemas preserve action IDs")
        verify(PhoneReady.resolve(page(nodes=listOf(node("a","Search"),node("b","Search"))),target("Search"))==null,"duplicates refused")
        verify(PhoneReady.resolve(page().put("truncated",true),target("Search"))==null,"truncated tree cannot prove unique")
        verify(PhoneReady.key(page())==PhoneReady.key(page(nodes=listOf(node("current","live changing counter")))),"feed text does not prolong layout wait")
        verify(PhoneReady.key(page())!=PhoneReady.key(page(nodes=listOf(node("current","Search",35)))),"moving control restarts stability")
        var time=0L; var reads=0
        val ready=PhoneReady.await({ reads++; if(time<270) page(pkg="com.android.launcher") else page() },{ time+=it },{ time },900,"com.example",target("Search"))
        verify(ready.getString("status")=="MET" && time>=450 && reads>1,"waits through launcher transition before target action")
        time=0; val timeout=PhoneReady.await({ page(pkg="com.other") },{ time+=it },{ time },450,"com.example",target("Search"))
        verify(timeout.getString("status")=="TIMEOUT" && time==450L,"wrong package bounded wait")
        time=0; reads=0
        val transient=PhoneReady.await({ reads++; if(reads<3) error("PHONE_WINDOW_CHANGED: transition") else page() },{ time+=it },{ time },900,"com.example",target("Search"))
        verify(transient.getString("status")=="MET" && reads>=5,"transient read race polls locally without action replay")
        var denied=false
        try { PhoneReady.await({ error("AGENT_PERMISSION_REVOKED") },{ time+=it },{ time },900) } catch(_:Exception) { denied=true }
        verify(denied,"permission failure is never treated as a transient animation")
        time=0; var state=0; val actions=ArrayList<JSONObject>()
        val flow=PhoneFlowRunner({ if(state==0) page() else page(window=2,nodes=listOf(node("menu-delete","Delete"))) },
            { a -> actions.add(JSONObject(a.toString())); state++; JSONObject().put("status","DISPATCHED").put("submitted",true) },{ time+=it },{ time })
        val output=flow.run(plan(step("Search"),step("Delete")))
        verify(output.getString("status")=="DISPATCHED" && actions.size==2,"cross-window flow runs in one host call")
        verify(actions[1].getString("node_id")=="menu-delete" && actions[1].getString("observation_id")=="fresh-2","menu step uses new node and structural snapshot")
        verify(output.getJSONObject("observation").getInt("window")==2 && output.getInt("completed_steps")==2,"final evidence plus receipts")
        val oldSize=actions.size
        var refused=false; try { flow.run(plan(step("Search"),JSONObject().put("action","arbitrary-shell"))) } catch(_:Exception) { refused=true }
        verify(refused && actions.size==oldSize,"entire invalid plan rejected before first mutation")
        time=0; var submissions=0
        val unknown=PhoneFlowRunner({ page() },{ submissions++; JSONObject().put("status","UNKNOWN").put("submitted",true) },{ time+=it },{ time })
            .run(plan(step("Search"),step("Search")))
        verify(unknown.getString("status")=="STOPPED" && submissions==1 && unknown.getInt("completed_steps")==0,"UNKNOWN never retried or continues")
        time=0; submissions=0
        val lost=PhoneFlowRunner({ page() },{ submissions++; error("transport lost after commit") },{ time+=it },{ time }).run(plan(step("Search"),step("Search")))
        verify(submissions==1 && lost.getJSONArray("steps").getJSONObject(0).getString("status")=="UNKNOWN","ambiguous thrown commit retained without replay")
        time=0; submissions=0
        val blocked=PhoneFlowRunner({ page(nodes=listOf(node("a","Search"),node("b","Search"))) },{ submissions++; JSONObject() },{ time+=it },{ time }).run(plan(step("Search").put("timeout_ms",450)))
        verify(blocked.getString("status")=="STOPPED" && submissions==0 && blocked.has("observation"),"ambiguous selector stops with evidence and no action")
        time=0; submissions=0
        val restore=PhoneFlowRunner({ error("self app must not be observed") },{ submissions++; JSONObject().put("status","DISPATCHED").put("restored_app",true) },{ time+=it },{ time })
            .run(plan(JSONObject().put("action","launch").put("package","dev.wind.agent")))
        verify(restore.getString("status")=="DISPATCHED" && submissions==1,"final restore avoids self-control and stale launcher snapshot")
        println("PhoneWorkflowTests: $checks checks PASS; real app transitions/model round-trips NOT_MEASURED")
    }
}
