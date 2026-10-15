package dev.wind.agent.skills

/** Explicit shipped manifest; arbitrary Markdown never becomes an installed skill. */
object BuiltinMateSkills {
    val descriptions=linkedMapOf(
        "mate-phone-tasks" to "手机 App 办事：观察、操作、回读；主屏和副屏工具协同。",
        "mate-virtual-display" to "虚拟副屏：就绪、创建、绑定、资源冲突与恢复。",
        "mate-chat-guardian" to "持续代聊：准确联系人、独立托管、重要待决、接管和报告。",
        "mate-adb-support" to "ADB 准备和结构化诊断，支撑手机与副屏；连接不等于授权。",
        "mate-shopping-comparison" to "真实网页比价：同规格、到手价、优惠资格与证据表。",
        "mate-document-work" to "文本、文档、表格和文件产物；按需使用脚本与执行环境。",
        "mate-travel-research" to "旅行日期、预算、交通住宿核实与临时约束。",
        "mate-personal-admin" to "个人事务与表单：跨工具进度、人工接管和最终核对。"
    )
}
