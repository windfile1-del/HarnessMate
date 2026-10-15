package dev.wind.agent.phone

import org.json.JSONArray
import org.json.JSONObject

/** Local checks only. A ready configuration does not prove OEM isolation or a remote live session. */
internal object VirtualDisplayReadiness {
    const val SETTINGS_PATH="设置 → 终端权限 → 后台虚拟屏幕"
    const val AUTHORITY_GUIDANCE="副屏由本机 ADB 的 com.android.shell（UID 2000）进程创建，不要求给 dev.wind.agent 授予 ADD_TRUSTED_DISPLAY 或 INTERNAL_SYSTEM_WINDOW。这些系统权限不能通过 pm grant 授予普通应用；Agent 的四项授权是工具授权。缺少 trusted/键盘隔离权限时允许共享焦点。请保留 create 的 diagnostic.failure_stage 和 authority，不要用 adb_shell 授权修复。"
    fun evaluate(api: Int,allowed: List<String>,missing: List<String>,adb: JSONObject,
        task: Boolean,state: String,busy: Boolean,targetLockedToMain: Boolean,platform: JSONObject?=null): JSONObject {
        val blocks=JSONArray()
        fun block(code: String,step: String) { blocks.put(JSONObject().put("code",code).put("user_action",step)) }
        if(api<30) block("VIRTUAL_DISPLAY_REQUIRES_ANDROID_11","当前内置副屏后端需要 Android 11 以上。")
        val knownUnsupported=platform!=null && (!platform.optBoolean("supported") || !platform.optBoolean("ui_automation_available",true))
        if(knownUnsupported) {
            val missingApis=platform?.optJSONArray("missing_apis") ?: JSONArray()
            val names=(0 until missingApis.length()).map { missingApis.getString(it) }.joinToString(", ")
            block(if(platform?.optBoolean("supported")==true) "UI_AUTOMATION_UNAVAILABLE" else platform?.optString("error","VIRTUAL_DISPLAY_REQUIRED_APIS_UNAVAILABLE").orEmpty(),
                "设备基础能力检测未通过，查看检测结果。"+if(names.isEmpty()) "" else "缺失：$names")
        }
        if(!adb.optBoolean("native_available")) block("ADB_NATIVE_COMPONENT_UNAVAILABLE","当前安装包缺少可用的内置 ADB 组件。")
        if(missing.isNotEmpty()) block("AGENT_PERMISSION_REQUIRED","在副屏设置的 Agent 权限中授予："+missing.joinToString(", "))
        if(!adb.optBoolean("available")) block("ADB_CONNECTION_REQUIRED","在副屏设置中连接本机无线 ADB；配对成功后仍须连接。")
        if(allowed.isEmpty()) block("VIRTUAL_DISPLAY_ALLOWLIST_REQUIRED","结束当前任务，在副屏设置中选择允许的应用。")
        val setupReady=blocks.length()==0
        if(!task) block("PHONE_DISPLAY_TASK_REQUIRED","准备完成后回到聊天，开启任务并调用 phone_display create。")
        if(state=="CLOSED_OR_LOST") block("VIRTUAL_DISPLAY_SESSION_LOST","本任务副屏已关闭或失效；停止操作，结束任务后再创建新任务。")
        if(busy) block("VIRTUAL_DISPLAY_DEVICE_BUSY","其他任务持有副屏，请先结束该任务或由用户关闭副屏。")
        val result=JSONObject().put("setup_ready",setupReady).put("creation_ready",blocks.length()==0 && state=="NOT_CREATED")
            .put("blockers",blocks).put("missing_capabilities",JSONArray(missing)).put("settings_path",SETTINGS_PATH)
            .put("calibration_required",false).put("focus_policy","BEST_EFFORT").put("focus_contention_possible",true)
            .put("aosp_focus_baseline_api",34).put("platform_api_status",if(platform==null) "NOT_CHECKED" else if(knownUnsupported) "UNAVAILABLE" else "API_READY")
            .put("remote_session_verified",false).put("main_screen_fallback",false)
            .put("app_system_permission_grant_required",false).put("authority_guidance",AUTHORITY_GUIDANCE)
        if(blocks.length()==0 && state=="NOT_CREATED") result.put("next_tool",JSONObject().put("name","phone_display").put("arguments",JSONObject().put("action","create")))
        else if(blocks.length()==0 && state=="READY") result.put("next_step","使用允许应用的实际包名调用 phone_launch，再观察并操作副屏。")
        return result
    }
    fun guidance(code: String): String = when {
        code=="VIRTUAL_DISPLAY_APP_REQUIRED" -> "副屏已创建但尚未启动应用；选择允许包调用 phone_launch，再 observe/action。空副屏不是会话丢失，不要重新创建。"
        code.startsWith("PHONE_BRIDGE_STARTUP") || code.startsWith("ADB_BRIDGE_PROCESS") || code.startsWith("PHONE_BRIDGE_RELAY") -> "Bridge 启动或进程通道失败；保存 bridge.bootstrap、process、server_exit 中的具体阶段/异常类/退出码。无需校准或向 App 授予系统签名权限。"
        code.startsWith("VIRTUAL_DISPLAY_TASK_") || code.startsWith("VIRTUAL_DISPLAY_RESOLVE_") -> "应用启动预检失败；保存 launch diagnostic.failure_stage 和 exception_class，以区分任务格式、应用解析和系统接口。效果未执行时不要用权限 grant 或主屏兜底。"
        code=="VIRTUAL_DISPLAY_SYSTEM_LAUNCH_DENIED" -> "Android 系统明确拒绝在该副屏启动应用；保存 SYSTEM_LAUNCH_POLICY 诊断和实际 shell 权限，不修改全局设置或切换主屏。"
        code=="VIRTUAL_DISPLAY_SESSION_LOST" -> "停止本任务，不重放效果、不切换主屏；查看 virtual_display.bridge.close_reason、前后 permission_revision / adb_epoch 和 server_exit（异常类型、线程、代码栈），不能仅凭 ADB_RAW_CLOSED 判定厂商强杀。"
        code=="VIRTUAL_DISPLAY_EVENT_ROUTING_FAILED" -> "副屏事件的窗口归属查询失败，已停止效果；保存完整错误和 Bridge 诊断，不重放、不切换主屏或申请系统签名权限。"
        code=="VIRTUAL_DISPLAY_SHELL_PERMISSION_DENIED" || code=="SHELL_UID_REQUIRED" || code.contains("not a changeable permission type") -> AUTHORITY_GUIDANCE
        code=="VIRTUAL_DISPLAY_FOCUS_OR_IME_API_UNAVAILABLE" -> "这是旧版本的焦点隔离检查错误。当前版本已取消校准和独立焦点门槛，Android 12/13 可尝试共享焦点副屏。"
        code=="VIRTUAL_DISPLAY_REQUIRED_APIS_UNAVAILABLE" -> "缺少副屏输入或读取所需的基础接口；在副屏设置中检查设备兼容性，查看具体 missing_apis。独立焦点接口缺失本身不阻止使用。"
        code=="UI_AUTOMATION_UNAVAILABLE" -> "shell UiAutomation 未就绪，查看连接与设备兼容性检测结果；不能执行副屏操作。"
        code.startsWith("AGENT_PERMISSION_REQUIRED") -> "打开副屏设置中的 Agent 权限，分别授予 Local ADB、后台虚拟屏幕、屏幕读取和自动化。"
        code=="VIRTUAL_DISPLAY_CREATE_REQUIRED" -> "先调用 phone_display status；准备完成后调用 phone_display create，再使用返回的 display_session_id。"
        code.contains("NOT_SUPPORTED") || code.contains("UNSUPPORTED") || code.contains("ISOLATION") || code.contains("UNVERIFIED") -> "设备未满足此次副屏操作所需的基础接口、权限或目标校验；保留错误码和设备能力报告。"
        code.startsWith("ADB_") || code=="PHONE_BRIDGE_LEASE_REVOKED" -> "检查副屏设置内的 ADB 连接状态、连接阶段和权限；不要只确认已经配对。"
        else -> "查看副屏设置中的就绪检查与设备能力报告，按错误码处理；不要自动重放已提交的操作。"
    }
}
