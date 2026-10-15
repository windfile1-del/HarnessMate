package dev.wind.agent

import dev.wind.agent.profile.*
import dev.wind.agent.prompt.*
import org.json.JSONArray
import org.json.JSONObject

object MateProfileTests {
    private var checks = 0
    private fun verify(condition: Boolean, reason: String) { check(condition) { reason }; checks++ }
    @JvmStatic fun main(args: Array<String>) {
        val old = JSONObject().put("id", "legacy-A").put("createdAt", 1).put("title", "old")
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "same"))
                .put(JSONObject().put("role", "user").put("content", "same")))
        val a = Chat.fromJson(old); val b = Chat.fromJson(old)
        verify(a.agentProfile == AgentProfile.CODE, "old chat changed positioning")
        verify(a.messages[0].messageId == b.messages[0].messageId, "legacy identity changed on read")
        verify(a.messages[0].messageId != a.messages[1].messageId, "identical turns collided")
        verify(a.messages[0].effectiveOrigin() == MessageOrigin.LEGACY_UNKNOWN, "unproven source promoted")
        a.agentProfile = AgentProfile.MATE; a.memoryPolicy = MemorySessionPolicy.READ_ONLY; a.projectMemoryKey = "project_1"
        val copy = Chat.fromJson(JSONObject(a.toJson().toString()))
        verify(copy.agentProfile == AgentProfile.MATE && copy.memoryPolicy == MemorySessionPolicy.READ_ONLY, "profile persistence failed")
        verify(copy.projectMemoryKey == "project_1", "project memory binding lost")
        val run = RunProfileSnapshot(a.agentProfile, Prefs.MODE_PLAN)
        a.agentProfile = AgentProfile.CODE
        verify(run.profile == AgentProfile.MATE && run.executionMode == Prefs.MODE_PLAN, "active positioning mutated")
        val direct = Message("user", "Please remember my style")
        verify(direct.effectiveOrigin() == MessageOrigin.USER_DIRECT, "direct user mislabeled")
        val revision = direct.sourceRevision; direct.content = "My updated style"
        verify(direct.sourceRevision == revision + 1, "edit did not change source revision")
        direct.content = direct.content
        verify(direct.sourceRevision == revision + 1, "unchanged source advanced revision")
        direct.modelOnly = true
        verify(direct.effectiveOrigin() == MessageOrigin.PROGRAMMATIC, "hidden continuation became user evidence")
        direct.chatAssistEventId = "incoming-peer"
        verify(direct.effectiveOrigin() == MessageOrigin.THIRD_PARTY_CHAT, "peer treated as local user")
        val skill = Message("tool", "skill text").apply { toolLog.add("read_skill") }
        verify(skill.effectiveOrigin() == MessageOrigin.SKILL_CONTEXT, "skill became user intent")
        val restored = Message.fromJson(JSONObject(direct.toJson().toString()))
        verify(restored.messageId == direct.messageId && restored.sourceRevision == direct.sourceRevision, "source metadata lost")
        verify(restored.effectiveOrigin() == MessageOrigin.THIRD_PARTY_CHAT, "source trust lost after restore")
        for (name in listOf("phone_observe", "phone_action", "phone_display", "phone_chat_guardian", "adb_status", "adb_shell", "browser_session")) {
            verify(PromptComposer.isCore(name), "$name not a default Mate core")
            val spec = ToolRegistry.spec(name)!!
            val contract = PromptComposer.shortContract(spec.promptDoc)
            verify(contract.contains(name) && contract.contains("{"), "$name arguments missing")
        }
        val nested = "- sample { target: { text, class? }, value? } Read a real page. " + "more ".repeat(500)
        val compact = PromptComposer.shortContract(nested)
        verify(compact.contains("value? }"), "nested schema cut at first closing brace")
        verify(compact.length < 230, "compact documentation unbounded")
        val metadata = JSONObject(ToolDiscovery.describe(JSONObject().put("names", JSONArray().put("phone_chat_guardian").put("adb_shell"))))
        verify(metadata.getJSONArray("tools").length() == 2, "full contracts inaccessible")
        verify(metadata.toString().contains("RAW_ADB_SHELL"), "ADB contract lost permission boundary")
        verify(ToolRegistry.spec("tool_search")!!.effect == ToolEffect.INTERNAL, "discovery treated as project access")
        verify(!ToolRegistry.spec("tool_describe")!!.mutating, "metadata requires mutation authority")
        verify(runCatching { ToolDiscovery.describe(JSONObject().put("names", JSONArray())) }.isFailure, "invalid description request accepted")
        println("PASS MateProfileTests: $checks checks")
    }
}
