package dev.wind.agent.webprovider

import android.content.Context
import dev.wind.agent.Lang

object WebStrings {
    fun t(context: Context, english: String, chinese: String): String = Lang.text(context, english, english, chinese)
    fun state(context: Context, code: String): String {
        val zh = when (code) {
            "LOADING" -> "网页正在加载"
            "PAGE_LOADED" -> "网页已加载；请完成登录后交还 Harness"
            "SITE_STORAGE_COMPATIBILITY" -> "兼容模式：按网站隔离存储，共享浏览器 Profile；同域服务可能共享登录状态"
            "COMPATIBILITY_CLEANUP_REQUIRES_ALL" -> "当前 WebView 无法单独清除服务登录状态；请使用“清除全部网页数据”。未清除其他服务的数据。"
            "MULTI_PROFILE_UNSUPPORTED" -> "请更新应用以使用网页兼容模式，或升级系统 WebView"
            "WEBVIEW_UNAVAILABLE" -> "系统 WebView 不可用，请安装或启用 Android System WebView 后重试"
            "SEND_CONTROL_NOT_FOUND" -> "未识别网页发送按钮；问题已填入，请手动点击发送，看到网页开始回答后点击继续，应用将读取回复。"
            "UPLOAD_CONTROL_NOT_FOUND" -> "未识别截图或文件的上传入口；请在此网页点击上传按钮，应用会提供本次附件。等待上传完成后点击继续。"
            "TAKEOVER_TIMEOUT_OPEN_ORIGINAL_PAGE" -> "等待登录或验证超时；请打开原网页完成后重试"
            "READY_EXPERIMENTAL", "READY" -> "实验可用（适配仍待实机验证）"
            "UNVERIFIED" -> "尚未验证"
            "DISABLED" -> "已停用"
            "NOT_LOGGED_IN", "LOGIN_REQUIRED" -> "需要登录；打开原网页完成后点击继续"
            "HUMAN_VERIFICATION_REQUIRED" -> "需要用户验证；打开原网页处理后点击继续"
            "INPUT_NOT_FOUND", "ADAPTER_INCOMPATIBLE" -> "未识别输入区，可能需要手动处理或更新适配"
            "UPLOAD_UNCONFIRMED", "UPLOAD_REQUIRES_TAKEOVER" -> "附件上传未确认；打开原网页检查"
            "RATE_LIMITED" -> "服务提示限流，请稍后重试"
            "CONTEXT_TRUNCATED" -> "部分历史或引用上下文已裁剪，当前问题保持完整"
            "CLEANUP_PENDING_RESTART" -> "登录状态已隔离，剩余站点数据将在重启应用并打开网页功能后清理"
            "NETWORK_FAILED", "PAGE_LOAD_FAILED", "PAGE_HTTP_ERROR" -> "网页加载失败，请检查网络并重试"
            "RENDERER_GONE" -> "网页进程已退出，请重新打开原网页"
            "EXTERNAL_NAVIGATION_BLOCKED" -> "网页跳转超出当前服务范围，可由用户选择外部打开"
            "TLS_ERROR" -> "网站安全连接失败"
            else -> code
        }
        return t(context, code.replace('_', ' ').lowercase(), zh)
    }
    fun risk(context: Context): String = t(context,
        "Experimental Web Providers operate third-party websites with your own account. Login data stays in the device browser, but submitted questions and attachments are received by the service. Service rules, quotas, region availability and page compatibility are controlled by that service. This is not an official API integration and does not promise free, unlimited or permanent access. Automation must be permitted by the service. Captchas and login require manual takeover. Never use a developer-shared account.",
        "实验性 Web Provider 使用第三方网页和你自己的账号。登录状态保存在设备浏览器中，但发出的问题和附件会交给第三方服务。服务规则、额度、地区限制和网页兼容性由第三方决定。这不是官方 API 集成，不承诺永久免费、无限额度或长期可用。使用自动化须符合服务允许的范围；登录和验证码由你手动完成。不能使用开发者共享账号。")
}
