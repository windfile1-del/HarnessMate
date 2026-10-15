package dev.wind.agent

import dev.wind.agent.memory.*
import dev.wind.agent.profile.MessageOrigin
import org.json.JSONObject

object MemoryRulesTests {
    @JvmStatic fun main(args: Array<String>) {
        var checks=0
        fun verify(ok: Boolean, reason: String) { check(ok){reason};checks++ }
        fun rejects(block: ()->Unit) { verify(runCatching(block).isFailure,"Expected policy rejection") }
        fun clause(source: String,evidence: String): String {val at=source.indexOf(evidence);return MemoryRules.evidenceContext(source,at,at+evidence.length)}
        verify(MemoryRules.projectFact("fact",clause("记住：这个项目使用 Kotlin","Kotlin")),"Narrow project quote lost its qualifier")
        verify(MemoryRules.temporary(clause("记住：这次预算3000元","3000")),"Narrow budget quote lost its temporary qualifier")
        verify(!MemoryRules.projectFact("fact",clause("这个项目使用 Kotlin。我偏好中文回复","中文")),"Separate personal clause inherited project scope")
        verify(!MemoryRules.projectFact("fact",clause("这个项目使用 Kotlin，我偏好中文回复","中文")),"Comma-separated personal clause inherited project scope")
        verify(MemoryRules.sensitive(clause("记住：我的过敏原是花生","花生")),"Narrow health quote skipped sensitivity review")
        rejects {MemoryRules.evidenceContext("short",-1,2)}
        val user=Message("user","记住我喜欢简短中文回复")
        verify(MemoryRules.direct(user),"Direct user")
        for(origin in MessageOrigin.entries.filter { it!=MessageOrigin.USER_DIRECT }) { user.origin=origin;verify(!MemoryRules.direct(user),"Promoted $origin") }
        user.origin=MessageOrigin.USER_DIRECT;user.modelOnly=true;verify(!MemoryRules.direct(user),"Hidden promoted");user.modelOnly=false
        user.chatAssistEventId="peer";verify(!MemoryRules.direct(user),"Peer promoted");user.chatAssistEventId=null
        verify(MemoryRules.explicit(user.content),"Chinese explicit save")
        verify(!MemoryRules.explicit("不要记住我喜欢简短回复"),"Negated remember intent accepted")
        verify(!MemoryRules.explicit("Don't remember this preference"),"English negated remember accepted")
        verify(MemoryRules.temporary("记住这次预算600元"),"One-trip budget promoted to permanent preference")
        verify(MemoryRules.sensitive("记住我对花生过敏"),"Health fact skipped review")
        verify(MemoryRules.quotedOrOtherPerson("她说记住我喜欢酒店"),"Another person's preference promoted")
        verify(!MemoryRules.quotedOrOtherPerson("记住我喜欢酒店"),"Own preference classified as quoted")
        verify(!MemoryRules.explicit("这次请比较价格"),"Temporary request promoted")
        verify(MemoryRules.secret("api_key: abcd"),"Key accepted")
        verify(MemoryRules.secret("Cookie=abc"),"Cookie accepted")
        for(secret in listOf("我的密码是 abc123","验证码为123456","password is abc123"))verify(MemoryRules.secret(secret),"Natural credential accepted")
        verify(!MemoryRules.secret("不要保存我的密码"),"Discussion incorrectly classified as credential")
        rejects { MemoryRules.validate(MemoryScope.PERSONAL,"project","response.style","简短") }
        rejects { MemoryRules.validate(MemoryScope.PROJECT,"","response.style","简短") }
        rejects { MemoryRules.validate(MemoryScope.PERSONAL,"","bad key","简短") }
        rejects { MemoryRules.validate(MemoryScope.PERSONAL,"","response.style","password: plain") }
        rejects { MemoryRules.validate(MemoryScope.PERSONAL,"","response.style","x".repeat(2001)) }
        val source=MemorySource.from("chat_test",user)
        verify(MemorySource.parse(source.json())==source,"Source roundtrip")
        val combined=Message("user","记住我喜欢安静酒店；预算600元，未经确认不要下单。")
        val span=MemorySource.from("chat_combined",combined,"我喜欢安静酒店")
        verify(MemorySource.parse(span.json())==span,"Evidence metadata roundtrip")
        val projected=MemoryRedactions.apply(combined.content,listOf(span))
        verify(!projected.contains("喜欢安静酒店") && projected.contains("预算600元") && projected.contains("未经确认不要下单"),"Redaction destroyed unrelated goal/authority")
        verify(MemoryRedactions.apply("新增目标；"+combined.content+"另外我喜欢安静酒店",listOf(span)).count { it=='[' }==2,"Shifted/repeated evidence remained")
        verify(!span.json().toString().contains("安静酒店"),"Forgotten plaintext persisted in deletion metadata")
        val amended=Message("user","新增目标；"+combined.content).apply { messageId=combined.messageId }
        verify(MemorySource.from("chat_combined",amended,"我喜欢安静酒店").fingerprint()==span.fingerprint(),"Unrelated edit defeated suppression")
        verify(MemoryRedactions.apply("内容完全不同",listOf(span))=="内容完全不同","Unrelated text redacted")
        val emoji=Message("user","记住我喜欢😀；目标🚀保留")
        val emojiSource=MemorySource.from("chat_emoji",emoji,"我喜欢😀")
        verify(MemoryRedactions.apply(emoji.content,listOf(emojiSource)).endsWith("目标🚀保留"),"Unicode boundary corrupted goal")
        rejects { MemorySource.parse(span.json().put("end",Int.MAX_VALUE)) }
        val dependency=Message("assistant","derived").apply { memoryRefs["mem_test"]=2;memoryNamespace="ns_test" }
        verify(Message.fromJson(dependency.toJson()).memoryNamespace=="ns_test","Memory namespace dependency lost")
        verify(Message.fromJson(dependency.toJson()).memoryRefs["mem_test"]==2L,"Memory revision dependency lost")
        dependency.memoryEpochDependency=7;dependency.memoryProjectDependency="project_a"
        verify(Message.fromJson(dependency.toJson()).memoryEpochDependency==7L && Message.fromJson(dependency.toJson()).memoryProjectDependency=="project_a","History dependency lost")
        val twin=Message("user",user.content)
        verify(MemorySource.from("chat_test",twin).fingerprint()!=source.fingerprint(),"New explicit source suppressed by old text")
        val r=MemoryRecord("mem_test","ns_test",MemoryScope.PROJECT,"project_a","build.language","Kotlin项目",1,2,"ACTIVE","CODE",1,0,listOf(source))
        verify(MemoryRecord.parse(r.json())==r,"Record roundtrip")
        verify(!MemoryRules.compatible(r,"project_b",null,1),"Project leak")
        verify(!MemoryRules.compatible(r,null,null,1),"Unbound project leak")
        verify(MemoryRules.compatible(r,"project_a",null,1),"Bound project unavailable")
        verify(!MemoryRules.compatible(r.copy(expiresAt=10),"project_a",null,10),"Expired record returned")
        verify(!MemoryRules.compatible(r.copy(status="CANDIDATE"),"project_a",null,1),"Candidate recalled")
        verify(MemoryRules.terms("旅行住宿偏好").intersect(MemoryRules.terms("推荐旅行住宿")).isNotEmpty(),"Chinese ngrams missing")
        verify(MemoryRules.terms("酒店").intersect(MemoryRules.terms("Java编译")).isEmpty(),"Unrelated Chinese match")
        verify(MemoryRules.terms("KOTLIN debugging").contains("kotlin"),"English normalization")
        rejects { MemoryRecord.parse(JSONObject(r.json().toString()).put("status","SYSTEM")) }
        rejects { MemoryRecord.parse(JSONObject(r.json().toString()).put("namespace","../escape")) }
        println("MemoryRulesTests PASS: $checks checks")
    }
}
