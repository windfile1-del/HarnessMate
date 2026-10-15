package dev.wind.agent

import android.content.Context

/**
 * Every string the interface shows, in both languages it ships in.
 *
 * ### One line, one string, two languages
 *
 * Each entry is a computed getter over a pair of literals. That is the whole
 * design, and it is chosen against the two shapes this file has had before:
 *
 *  - v6 held every string THREE times — a Persian field default, an English
 *    overlay applied by `apply()`, and a Persian restore — kept in step only by
 *    a test that compared the three key sets. Adding a string meant editing
 *    three places and any miss was a silently untranslated label.
 *  - v1 collapsed that to one immutable English table, which was correct for an
 *    English-only build and has no room for a second language at all.
 *
 * A getter has neither problem. The two renderings sit on the same line, so
 * they cannot drift and a missing translation is a compile error rather than a
 * runtime surprise. Nothing is cached, so switching language takes effect on the
 * next read with no table to rebuild and no screen able to disagree with
 * another about which language it is in.
 *
 * ### What is NOT translated
 *
 * The product name. `APP_NAME` is "Vega Safe" in both languages, as are the
 * protocol names and the version string — they are identifiers, not prose.
 *
 * ### This file is about the INTERFACE
 *
 * What the model writes is a separate matter: it answers in whatever language it
 * is written to, and every prose view lays its text out from its own first
 * strong character, so a Persian answer reads correctly even with the interface
 * in English, and an English answer reads correctly with the interface in
 * Persian.
 */
object Fa {

    /**
     * True when the interface is Persian.
     *
     * Read by every getter below and by [Lang], which derives layout direction
     * and numerals from it. Volatile because [apply] runs on whichever thread
     * entered a screen while getters are read from the UI thread.
     */
    @Volatile
    internal var farsi: Boolean = false
        private set

    /**
     * True when the interface is Chinese.
     *
     * FALSE until [apply] runs, like [farsi], and that is load-bearing rather
     * than incidental. The default LANGUAGE is Chinese (see [Prefs.language]),
     * but every screen and the service call [apply] on entry, so the stored
     * default is what a user actually sees. The initial value is what a caller
     * that never applied gets — the tests, and any code path that formats a
     * string before a window exists — and it was left at `true` on the way in,
     * which quietly turned the pre-apply table Chinese and broke the one suite
     * that reads an error message as text. English is the safe pre-apply state:
     * it is the upstream behaviour, and a missing [apply] shows a wart rather
     * than a third language nobody chose.
     */
    @Volatile
    internal var chinese: Boolean = false
        private set

    fun apply(context: Context) {
        val lang = Prefs(context).language()
        farsi = lang == "fa"
        chinese = lang == "zh"
        Lang.invalidate()
    }

    val ACT_ARCHIVE: String get() = if (farsi) "بررسی آرشیو" else if (chinese) "查看压缩包" else "Inspect archive"
    val ACT_DELETE: String get() = if (farsi) "حذف کردن" else if (chinese) "删除" else "Delete"
    val ACT_DOWNLOAD: String get() = if (farsi) "دانلود فایل" else if (chinese) "下载文件" else "Download a file"
    val ACT_EDIT: String get() = if (farsi) "ویرایش فایل" else if (chinese) "编辑文件" else "Edit a file"
    val ACT_LIST: String get() = if (farsi) "دیدن محتوای پوشه" else if (chinese) "列出文件夹内容" else "List a folder"
    val ACT_MEMORY: String get() = if (farsi) "حافظه‌ی بلندمدت" else if (chinese) "长期记忆" else "Long-term memory"
    val ACT_MKDIR: String get() = if (farsi) "ساخت پوشه" else if (chinese) "新建文件夹" else "Create a folder"
    val ACT_MOVE: String get() = if (farsi) "جابه‌جایی یا تغییر نام" else if (chinese) "移动或重命名" else "Move or rename"
    val ACT_OTHER: String get() = if (farsi) "اجرای ابزار" else if (chinese) "运行工具" else "Run a tool"
    val ACT_PDF: String get() = if (farsi) "خواندن PDF" else if (chinese) "读取 PDF" else "Read a PDF"
    val ACT_READ: String get() = if (farsi) "خواندن فایل" else if (chinese) "读取文件" else "Read a file"
    val ACT_RUN: String get() = if (farsi) "اجرای ساخت یا آزمون" else if (chinese) "构建或测试" else "Build or test"
    // ---- P4 coding loop phases -------------------------------------------
    // The activity strip is where a run explains itself, and a coding task has
    // more states worth naming than an ordinary turn. They live here with every
    // other string so none of them can be English-only by accident.
    val P4_INSPECTED: String get() = if (farsi) "پروژه شناسایی شد" else if (chinese) "已识别项目" else "Identified the project"
    val P4_EDITING: String get() = if (farsi) "ویرایش کد" else if (chinese) "编辑代码" else "Editing"
    val P4_BUILDING: String get() = if (farsi) "ساخت: %s" else if (chinese) "构建：%s" else "Building: %s"
    val P4_CHECK_PASSED: String get() = if (farsi) "%s با موفقیت گذشت" else if (chinese) "%s 通过" else "%s passed"
    val P4_FIXING: String get() = if (farsi) "رفع %s خطا" else if (chinese) "修复 %s 个错误" else "Fixing %s error(s)"
    val P4_REPAIR_N: String get() = if (farsi) "تلاش ترمیم %s" else if (chinese) "第 %s 轮修复" else "Repair attempt %s"
    val P4_VERIFYING: String get() = if (farsi) "تأیید نهایی" else if (chinese) "最终验证" else "Final verification"
    val P4_VERIFIED: String get() = if (farsi) "تأیید شد" else if (chinese) "已验证" else "Verified"
    val P4_VERIFY_FAILED: String get() = if (farsi) "تأیید ناموفق" else if (chinese) "验证失败" else "Verification failed"
    val P4_DIFF_READY: String get() = if (farsi) "آماده اعمال تغییرات" else if (chinese) "变更待应用" else "Changes ready to apply"
    val P4_APPLYING: String get() = if (farsi) "اعمال تغییرات" else if (chinese) "正在应用" else "Applying"
    val P4_APPLIED: String get() = if (farsi) "اعمال شد" else if (chinese) "已应用" else "Applied"
    val P4_BLOCKED: String get() = if (farsi) "بدون امکان تأیید" else if (chinese) "无法验证" else "Cannot verify"
    val P4_DONE: String get() = if (farsi) "پایان یافت" else if (chinese) "已完成" else "Completed"
    // A coding task whose durable state could not be loaded is reported, never
    // papered over: starting a fresh task there would silently discard a
    // verification the user already paid for.
    val P4_STATE_UNUSABLE: String get() = if (farsi) "وضعیت کار کدنویسی قابل بازیابی نیست، اجرا متوقف شد." else if (chinese) "编码任务状态无法恢复，本次运行已停止。" else "The coding task's state could not be recovered, so the run was stopped."
    // The gate has been pushed back as often as it is allowed to be; the model
    // still has unverified changes, so the run ends as blocked rather than
    // quietly becoming a success.
    val P4_GATE_EXHAUSTED: String get() = if (farsi) "چندین بار گفته شد که کار تأیید نشده است؛ اجرا بدون تأیید پایان نمی‌یابد." else if (chinese) "已多次提示改动尚未验证；未经验证的运行不会结束。" else "The changes were reported as unverified repeatedly; the run cannot end without verification."
    val ACT_SEARCH: String get() = if (farsi) "جستجو در فایل‌ها" else if (chinese) "搜索文件" else "Search files"
    val ACT_SHELL_CAPS: String get() = if (farsi) "بررسی محیط اجرا" else if (chinese) "检查执行环境" else "Check shell capabilities"
    val ACT_SHELL_EXEC: String get() = if (farsi) "اجرای دستور" else if (chinese) "执行命令" else "Execute command"
    val ACT_SHELL_APPLY: String get() = if (farsi) "اعمال تغییرات دستور" else if (chinese) "应用命令修改" else "Apply command changes"
    val ACT_SHELL_DISCARD: String get() = if (farsi) "لغو تغییرات دستور" else if (chinese) "放弃命令修改" else "Discard command changes"
    val ACT_TASK: String get() = if (farsi) "اجرای زیروظیفه" else if (chinese) "委派的子任务" else "Delegated sub-task"
    val ACT_WEB_FETCH: String get() = if (farsi) "باز کردن صفحه‌ی وب" else if (chinese) "打开网页" else "Open a web page"
    val ACT_WEB_SEARCH: String get() = if (farsi) "جستجوی وب" else if (chinese) "网络搜索" else "Web search"
    val ACT_WRITE: String get() = if (farsi) "ساخت فایل تازه" else if (chinese) "新建文件" else "Create a new file"
    val APPROVE_ALWAYS: String get() = if (farsi) "همیشه اجازه بده" else if (chinese) "始终允许" else "Always allow"
    val APPROVE_ALWAYS_NOTE: String get() = if (farsi) "این نوع عملیات تا شروع گفتگوی تازه دیگر پرسیده نمی‌شود." else if (chinese) "本次对话中不再询问此类操作。" else "This kind of action won't be asked about again until you start a new chat."
    val CAP_ALLOW_ONCE: String get() = if (farsi) "فقط یک‌بار" else if (chinese) "仅本次" else "Allow once"
    val CAP_ALLOW_TASK: String get() = if (farsi) "برای این کار" else if (chinese) "允许本任务" else "Allow for task"
    val CAP_ALLOW_SESSION: String get() = if (farsi) "این نشست" else if (chinese) "允许本次会话" else "Allow this session"
    val CAP_SCOPE_NOTE: String get() = if (farsi) "دامنه‌ی اجازه را انتخاب کنید؛ دسترسی به همین پروژه و قابلیت محدود می‌ماند." else if (chinese) "请选择授权时限；权限仅绑定当前项目和当前能力。" else "Choose how long to allow it; authority stays bound to this project and capability."
    val ACTIVE_CAPABILITIES: String get() = if (farsi) "مجوزهای فعال وظیفه" else if (chinese) "当前任务权限" else "Active task permissions"
    val ACTIVE_CAPABILITIES_H: String get() = if (farsi) "مجوزهای همین وظیفه را ببینید یا لغو کنید." else if (chinese) "查看或撤销当前任务的能力授权。" else "View or revoke capabilities for the active task."
    val CAP_REVOKE_EXECUTION: String get() = if (farsi) "لغو اجرا" else if (chinese) "撤销执行" else "Revoke execution"
    val CAP_REVOKE_WRITE: String get() = if (farsi) "لغو نوشتن" else if (chinese) "撤销写入" else "Revoke writes"
    val CAP_REVOKE_NETWORK: String get() = if (farsi) "لغو شبکه" else if (chinese) "撤销网络" else "Revoke network"
    val CAP_REVOKE_ALL: String get() = if (farsi) "لغو همه" else if (chinese) "撤销全部任务权限" else "Revoke all task grants"
    val CAP_REVOKED: String get() = if (farsi) "مجوز لغو شد" else if (chinese) "权限已撤销" else "Capability revoked"
    val APPROVE_ASK: String get() = if (farsi) "اجازه می‌دهید؟" else if (chinese) "是否允许？" else "Do you allow this?"
    val CHAT_MENU_DELETE: String get() = if (farsi) "حذف گفتگو" else if (chinese) "删除" else "Delete"
    val CHAT_MENU_PIN: String get() = if (farsi) "سنجاق کردن" else if (chinese) "置顶" else "Pin"
    val CHAT_MENU_RENAME: String get() = if (farsi) "تغییر نام" else if (chinese) "重命名" else "Rename"
    val CHAT_MENU_UNPIN: String get() = if (farsi) "برداشتن سنجاق" else if (chinese) "取消置顶" else "Unpin"
    val CHAT_RENAME_TITLE: String get() = if (farsi) "نام تازه" else if (chinese) "新名称" else "New name"
    val EDIT_PROGRESS: String get() = if (farsi) "در حال ویرایش" else if (chinese) "编辑中" else "Editing"
    val ERR_NO_BROWSER: String get() = if (farsi) "مرورگری برای باز کردن این پیوند پیدا نشد" else if (chinese) "没有可打开此链接的应用" else "No app found to open this link"
    val ERR_SAVE: String get() = if (farsi) "ذخیره نشد؛ فضای دستگاه را بررسی کنید" else if (chinese) "无法保存 — 请检查设备存储空间" else "Could not save — check the device's storage"
    val PLAN_BEST: String get() = if (farsi) "پیشنهاد" else if (chinese) "推荐" else "Recommended"
    val APPROVE_REJECT: String get() = if (farsi) "رد" else if (chinese) "拒绝" else "Reject"
    val APPROVE_RUN: String get() = if (farsi) "تأیید و اجرا" else if (chinese) "允许并执行" else "Approve and run"
    val APPROVE_SUBTITLE: String get() = if (farsi) "دستیار می\u200cخواهد این عملیات را اجرا کند" else if (chinese) "助手想要执行此操作" else "The assistant wants to run this operation"
    val APPROVE_TITLE: String get() = if (farsi) "تأیید عملیات" else if (chinese) "操作审批" else "Approve operation"
    val APP_NAME: String get() = "Harness Mate"
    val ATTACH_FILE: String get() = if (farsi) "افزودن فایل" else if (chinese) "添加文件" else "Add file"
    val ATTACH_IMAGE: String get() = if (farsi) "افزودن عکس" else if (chinese) "添加图片" else "Add image"
    val BROWSER_EMPTY: String get() = if (farsi) "این پوشه خالی است" else if (chinese) "此文件夹为空" else "This folder is empty"
    val BROWSER_PICK: String get() = if (farsi) "انتخاب همین پوشه" else if (chinese) "选择此文件夹" else "Select this folder"
    val BROWSER_SUB: String get() = if (farsi) "پوشه‌ها را باز کنید و فایلی را برگزینید" else if (chinese) "打开文件夹并选择文件" else "Open folders and select a file"
    val BROWSER_TITLE: String get() = if (farsi) "انتخاب فایل" else if (chinese) "选择文件" else "Select file"
    val BROWSER_UP: String get() = if (farsi) "پوشه بالاتر" else if (chinese) "上级文件夹" else "Parent folder"
    val CANCEL: String get() = if (farsi) "انصراف" else if (chinese) "取消" else "Cancel"
    val CHATS: String get() = if (farsi) "گفتگوها" else if (chinese) "对话列表" else "Chats"
    val CLOSE: String get() = if (farsi) "بستن" else if (chinese) "关闭" else "Close"
    val CONFIRM: String get() = if (farsi) "تأیید" else if (chinese) "确认" else "Confirm"
    val COPIED: String get() = if (farsi) "کپی شد" else if (chinese) "已复制" else "Copied"
    val COPY: String get() = if (farsi) "کپی" else if (chinese) "复制" else "Copy"
    val DELETE: String get() = if (farsi) "حذف" else if (chinese) "删除" else "Delete"
    val DELETE_CHAT_MSG: String get() = if (farsi) "این گفتگو برای همیشه پاک می‌شود. این کار قابل بازگشت نیست." else if (chinese) "该对话将被永久删除，不可恢复。" else "This chat will be permanently deleted."
    val DELETE_CHAT_TITLE: String get() = if (farsi) "حذف گفتگو؟" else if (chinese) "确认删除对话？" else "Delete chat?"
    val DIFF_ADDED: String get() = if (farsi) "افزوده\u200cشده" else if (chinese) "新增" else "Added"
    val DIFF_REMOVED: String get() = if (farsi) "حذف\u200cشده" else if (chinese) "删除" else "Removed"
    val EDIT: String get() = if (farsi) "اصلاح" else if (chinese) "编辑" else "Edit"
    val ERR_AUTH: String get() = if (farsi) "کلید API نامعتبر است یا منقضی شده." else if (chinese) "API 密钥无效或已过期。" else "The API key is invalid or expired."
    val ERR_BADREQ: String get() = if (farsi) "درخواست نامعتبر است" else if (chinese) "请求无效" else "Invalid request"
    val ERR_FORBIDDEN: String get() = if (farsi) "دسترسی رد شد. کلید API مجوز این عملیات را ندارد." else if (chinese) "访问被拒绝。此 API 密钥无权执行该操作。" else "Access denied. The API key is not authorized for this operation."
    val ERR_MAXSTEPS: String get() = if (farsi) "به بیشینه گام\u200cهای این نوبت رسیدم. اگر بخواهید، بگویید تا ادامه دهم." else if (chinese) "本轮已达最大步数。如需继续，请告诉我。" else "I reached the maximum steps for this turn. Ask me to continue if you want."
    val ERR_NOTFOUND: String get() = if (farsi) "آدرس یا نام مدل یافت نشد. تنظیمات را بررسی کنید." else if (chinese) "找不到该地址或模型。请检查设置。" else "The URL or model was not found. Check your settings."
    val ERR_REDIRECT: String get() = if (farsi) "نشانی سرویس بارها تغییر مسیر داد؛ آدرس پایه را در تنظیمات بررسی کنید." else if (chinese) "服务地址重定向次数过多 — 请检查设置中的基础地址。" else "The endpoint redirected too many times — check the base URL in Settings."
    val SET_TEST_UNREADABLE: String get() = if (farsi) "اتصال برقرار شد اما پاسخ قابل خواندن نبود." else if (chinese) "已连接，但无法读取返回内容。" else "Connected, but the reply could not be read."
    val ERR_NO_NET: String get() = if (farsi) "دسترسی به اینترنت برقرار نیست. اتصال شبکه را بررسی کنید." else if (chinese) "无网络连接。请检查网络。" else "No internet connection. Check your network."
    val ERR_NO_PICKER: String get() = if (farsi) "برنامه انتخاب فایل یافت نشد" else if (chinese) "未找到文件选择器" else "No file picker found"
    val ERR_OVERLOAD: String get() = if (farsi) "سرور پرترافیک است. کمی بعد دوباره تلاش می\u200cشود." else if (chinese) "服务器繁忙，稍后重试。" else "The server is busy. Retrying shortly."
    val ERR_RATE: String get() = if (farsi) "محدودیت نرخ درخواست. کمی صبر کنید و دوباره تلاش کنید." else if (chinese) "已达到速率限制，请稍后再试。" else "Rate limit reached. Wait a moment and try again."
    val ERR_READ_FILE: String get() = if (farsi) "فایل خوانده نشد" else if (chinese) "无法读取文件" else "Could not read file"
    val ERR_SERVER: String get() = if (farsi) "خطای سرور. لحظاتی بعد دوباره تلاش کنید." else if (chinese) "服务器错误，请稍后重试。" else "Server error. Please try again shortly."
    val ERR_TIMEOUT: String get() = if (farsi) "زمان پاسخ\u200cگویی سرور تمام شد. دوباره تلاش کنید." else if (chinese) "服务器超时，请重试。" else "The server timed out. Please try again."
    val ERR_UNKNOWN: String get() = if (farsi) "خطای ناشناخته" else if (chinese) "未知错误" else "Unknown error"
    val INPUT_HINT: String get() = if (farsi) "پیام خود را بنویسید…" else if (chinese) "输入任务指令或消息…" else "Write a message…"
    val MODE_ACCEPT: String get() = if (farsi) "تأییدی" else if (chinese) "审阅模式" else "Reviewing"
    val MODE_ACCEPT_DESC: String get() = if (farsi) "پیش از هر تغییر، اجازه می\u200cگیرد." else if (chinese) "每次改动前都会请求授权。" else "Asks for permission before every change."
    val MODE_AUTO: String get() = if (farsi) "خودکار" else if (chinese) "自动模式" else "Automatic"
    val MODE_AUTO_DESC: String get() = if (farsi) "دستیار مستقل عمل می\u200cکند و فایل\u200cها را آزادانه ویرایش می\u200cکند." else if (chinese) "自主执行，可自由修改文件。" else "Acts independently and edits files freely."
    val MODE_PLAN: String get() = if (farsi) "برنامه\u200cریزی" else if (chinese) "规划模式" else "Planning"
    val MODE_PLAN_DESC: String get() = if (farsi) "فقط\u200cخواندنی؛ ابتدا یک نقشه پیشنهاد می\u200cدهد و منتظر تأیید می\u200cماند." else if (chinese) "只读；先给出方案并等待确认。" else "Read-only; proposes a plan and waits for approval."
    val MODE_SUBTITLE: String get() = if (farsi) "میزان اختیار دستیار را انتخاب کنید" else if (chinese) "选择 Agent 的执行权限与安全模式" else "Choose how much freedom the assistant has"
    val MODE_TITLE: String get() = if (farsi) "حالت دستیار" else if (chinese) "安全运行模式" else "Assistant mode"
    val NEW_CHAT: String get() = if (farsi) "گفتگوی جدید" else if (chinese) "新建对话" else "New chat"
    val NEW_FILE: String get() = if (farsi) "فایل جدید" else if (chinese) "新文件" else "New file"
    val NO_CHATS: String get() = if (farsi) "هنوز گفتگویی نیست" else if (chinese) "暂无对话" else "No chats yet"
    val PERM_GRANT: String get() = if (farsi) "انتخاب پوشه" else if (chinese) "选择文件夹" else "Choose a folder"
    val PERM_LATER: String get() = if (farsi) "فعلاً نمی‌خواهم" else if (chinese) "稍后" else "Not now"
    val PERM_HINT: String get() = if (farsi) "دستیار فقط پوشه‌ای را می‌بیند که خودتان انتخاب کنید." else if (chinese) "助手只在你选择的文件夹内工作。" else "The assistant works only inside the folder you choose."
    val PERM_MSG: String get() = if (farsi) "پوشه‌ای را برای کار دستیار انتخاب کنید" else if (chinese) "为助手选择一个工作文件夹" else "Choose a folder for the assistant to work in"
    val PLAN_KEEP: String get() = if (farsi) "ادامه گفتگو" else if (chinese) "继续对话" else "Continue chat"
    val PLAN_QUESTIONS: String get() = if (farsi) "پرسش\u200cها" else if (chinese) "疑问" else "Questions"
    val PLAN_RUN: String get() = if (farsi) "اجرای نقشه" else if (chinese) "执行方案" else "Run plan"
    val PLAN_RUN_MSG: String get() = if (farsi) "نقشه را تأیید می\u200cکنم. لطفاً آن را گام\u200cبه\u200cگام اجرا کن." else if (chinese) "我同意该方案，请逐步执行。" else "I approve the plan. Please execute it step by step."
    val PLAN_STEPS: String get() = if (farsi) "گام\u200cها" else if (chinese) "步骤" else "Steps"
    val PLAN_SUBTITLE: String get() = if (farsi) "دستیار این برنامه را پیشنهاد می\u200cدهد" else if (chinese) "助手提出以下方案" else "The assistant proposes this plan"
    val PLAN_TITLE: String get() = if (farsi) "نقشه پیشنهادی" else if (chinese) "建议方案" else "Proposed plan"
    val Q_APPLY_NOTE: String get() = if (farsi) "پس از انتخاب، در صورت نیاز تغییرات با تأیید شما اعمال می\u200cشود." else if (chinese) "选择后，如需改动会先征得你的同意。" else "After selection, changes are applied with your approval when needed."
    val Q_CUSTOM: String get() = if (farsi) "پیشنهاد خودم" else if (chinese) "我自己填写" else "My own suggestion"
    val Q_CUSTOM_HINT: String get() = if (farsi) "راه\u200cحل دلخواه خود را بنویسید…" else if (chinese) "写下你希望的做法…" else "Write your preferred solution…"
    val Q_SEND: String get() = if (farsi) "ارسال پاسخ" else if (chinese) "发送回答" else "Send answer"
    val Q_TITLE: String get() = if (farsi) "یک تصمیم لازم است" else if (chinese) "需要你决定" else "A decision is needed"
    val RETRY: String get() = if (farsi) "تلاش دوباره" else if (chinese) "重试" else "Retry"
    val RETRYING: String get() = if (farsi) "در حال تلاش مجدد" else if (chinese) "重试中" else "Retrying"
    val RISK_DELETE: String get() = if (farsi) "حذفی" else if (chinese) "删除性操作" else "Destructive"
    val RISK_NETWORK: String get() = if (farsi) "شبکه" else if (chinese) "网络" else "Network"
    val RISK_READ: String get() = if (farsi) "فقط‌خواندنی" else if (chinese) "只读" else "Read-only"
    val RISK_WRITE: String get() = if (farsi) "تغییر فایل" else if (chinese) "修改文件" else "Changes files"
    val RISK_EXECUTE: String get() = if (farsi) "اجرای دستور" else if (chinese) "命令执行" else "Command Execution"
    val RISK_NETWORK_EXECUTE: String get() = if (farsi) "دستور با دسترسی شبکه" else if (chinese) "网络命令执行" else "Network Command"
    val SAVE: String get() = if (farsi) "ذخیره" else if (chinese) "保存" else "Save"
    val SEND: String get() = if (farsi) "ارسال" else if (chinese) "发送" else "Send"
    val SETTINGS: String get() = if (farsi) "تنظیمات" else if (chinese) "设置" else "Settings"
    val SETUP_MSG: String get() = if (farsi) "پیش از گفتگو، کلید API، آدرس پایه و مدل را در تنظیمات وارد کنید." else if (chinese) "请先在设置中填写 API 密钥、基础地址和模型，然后开始对话。" else "Set your API key, base URL, and model in Settings before chatting."
    val SETUP_OPEN: String get() = if (farsi) "باز کردن تنظیمات" else if (chinese) "打开设置" else "Open Settings"
    val SETUP_TITLE: String get() = if (farsi) "نیاز به راه‌اندازی" else if (chinese) "需要先完成设置" else "Setup required"
    val SET_API_KEY: String get() = if (farsi) "کلید API" else if (chinese) "API 密钥" else "API key"
    val SET_BRAVE_KEY: String get() = if (farsi) "کلید Brave Search" else if (chinese) "Brave Search API 密钥" else "Brave Search API key"
    val SET_SEARXNG_URL: String get() = if (farsi) "آدرس پایه SearXNG" else if (chinese) "SearXNG 基础地址" else "SearXNG Base URL"
    val SET_SEARCH_TEST: String get() = if (farsi) "آزمایش جستجو" else if (chinese) "测试搜索" else "Test Search"
    val SET_BASE_URL: String get() = if (farsi) "آدرس پایه (Base URL)" else if (chinese) "基础地址" else "Base URL"
    val SET_BEHAVIOR: String get() = if (farsi) "رفتار" else if (chinese) "行为" else "Behavior"
    val SET_CUSTOM: String get() = if (farsi) "دستورهای سفارشی" else if (chinese) "自定义指令" else "Custom instructions"
    val SET_CUSTOM_HINT: String get() = if (farsi) "دستورهای اضافه که به پرامپت سیستم افزوده می‌شود…" else if (chinese) "附加系统指令…" else "Additional system instructions…"
    val SET_HELP: String get() = if (farsi) "برای endpoint سفارشی، پروتکل را دستی انتخاب کنید." else if (chinese) "自定义服务地址时请手动选择协议。" else "Select the protocol manually for custom endpoints."
    val SET_KEYSTORE_UNAVAILABLE: String get() = if (farsi) "کلید ذخیره شد، ولی این دستگاه رمزگذاری سخت‌افزاری ندارد." else if (chinese) "密钥已保存，但此设备不支持硬件加密。" else "Key saved, but this device has no hardware encryption."
    val SET_MAXTOK: String get() = if (farsi) "بیشینه توکن پاسخ" else if (chinese) "最大回复长度（token）" else "Maximum response tokens"
    val SET_TIMEOUT: String get() = if (farsi) "زمان انتظار پاسخ (ثانیه)" else if (chinese) "响应超时（秒）" else "Response timeout (seconds)"
    val SET_TIMEOUT_H: String get() = if (farsi) "اگر سرویس این مدت هیچ داده‌ای نفرستد، درخواست لغو می‌شود. پاسخ در حال دریافت این زمان را صفر می‌کند، پس پاسخ‌های طولانی مشکلی ندارند. برای مدل‌های کند یا محلی مقدار بیشتری بگذارید. بازه: ۱۰ تا ۱۸۰۰ ثانیه." else if (chinese) "服务在这段时间内没有返回任何数据就取消请求。收到新内容会重新计时，因此长回答不受影响。较慢或本地模型可调大。范围：10-1800 秒。" else "Cancel the request if the service sends no data for this long. Incoming output resets the clock, so long answers are fine. Raise it for slow or local models. Range: 10-1800 seconds."
    val SET_MODEL: String get() = if (farsi) "نام مدل" else if (chinese) "模型名称" else "Model name"
    val SET_NEED_FIELDS: String get() = if (farsi) "آدرس پایه و نام مدل الزامی است" else if (chinese) "基础地址和模型为必填项" else "Base URL and model are required"
    val SET_PRESET: String get() = if (farsi) "انتخاب سریع" else if (chinese) "快速预设" else "Quick presets"
    val SET_PROTOCOL: String get() = if (farsi) "پروتکل" else if (chinese) "协议" else "Protocol"
    val SET_PROTO_ANTHRO: String get() = "Anthropic"
    val SET_PROTO_AUTO: String get() = if (farsi) "خودکار" else if (chinese) "自动" else "Auto"
    val SET_PROTO_GEMINI: String get() = "Gemini"
    val SET_PROTO_OPENAI: String get() = "OpenAI"
    val SET_PROVIDER: String get() = if (farsi) "ارائه‌دهنده هوش مصنوعی" else if (chinese) "AI 服务商" else "AI provider"
    val SET_SAVE: String get() = if (farsi) "ذخیره تنظیمات" else if (chinese) "保存设置" else "Save settings"
    val SET_SAVED: String get() = if (farsi) "ذخیره شد" else if (chinese) "已保存" else "Saved"
    val SET_TEMP: String get() = if (farsi) "خلاقیت (Temperature)" else if (chinese) "温度" else "Temperature"
    val SET_TEST: String get() = if (farsi) "تست اتصال" else if (chinese) "测试连接" else "Test connection"
    val SET_TESTING: String get() = if (farsi) "در حال بررسی…" else if (chinese) "测试中…" else "Testing…"
    val SET_TEST_OK: String get() = if (farsi) "اتصال برقرار شد ✓" else if (chinese) "已连接 ✓" else "Connected ✓"
    val SET_THINKING: String get() = if (farsi) "استدلال گام\u200cبه\u200cگام (Thinking)" else if (chinese) "逐步推理（Thinking）" else "Step-by-step reasoning (Thinking)"
    val SET_THINKING_H: String get() = if (farsi) "فقط برای مدل\u200cهای Claude؛ استدلال درونی مدل را نمایش می\u200cدهد." else if (chinese) "仅适用于 Claude 模型；显示模型的内部推理过程。" else "For Claude models only; shows the model's internal reasoning."
    val SET_THINK_LEVEL: String get() = if (farsi) "میزان تفکر" else if (chinese) "推理强度" else "Reasoning effort"
    val SET_THINK_LEVEL_H: String get() = if (farsi) "هرچه بالاتر، مدل پیش از پاسخ عمیق‌تر فکر می‌کند. استدلالش در بخش «بررسی درخواست» نمایش داده می‌شود." else if (chinese) "强度越高，模型在作答前推理越久。推理内容显示在审阅区中。" else "Higher effort makes the model reason longer before it answers. Its reasoning appears inside the review section."
    val SET_WEB: String get() = if (farsi) "ابزار جست‌وجوی وب" else if (chinese) "网络搜索工具" else "Web search tool"
    val SET_WORKFLOW: String get() = if (farsi) "گردش‌کار پویا" else if (chinese) "动态工作流" else "Dynamic Workflow"
    val SET_WORKFLOW_H: String get() = if (farsi) "کار را مرحله‌به‌مرحله به ایجنت‌های کوچک می‌سپارد. دقیق‌تر، ولی کندتر." else if (chinese) "把任务拆成多个阶段，分别交给专注的子代理执行。更彻底，但更慢。" else "Splits a job into phases and delegates each to a focused sub-agent. More thorough, but slower."
    val NET_BLOCK_EMPTY: String get() = if (farsi) "نشانی شبکه خالی است" else if (chinese) "网络地址为空" else "The network address is empty"
    val NET_BLOCK_MALFORMED: String get() = if (farsi) "نشانی شبکه معتبر نیست" else if (chinese) "网络地址无效" else "The network address is not valid"
    val NET_BLOCK_SCHEME: String get() = if (farsi) "فقط نشانی https پذیرفته می‌شود" else if (chinese) "只接受 https 地址" else "Only https addresses are accepted"
    val NET_BLOCK_PLAINTEXT: String get() = if (farsi) "برای نشانی اینترنتی باید https باشد تا کلید شما رمزنگاری‌شده ارسال شود" else if (chinese) "公网地址必须使用 https，以确保密钥加密传输" else "A public endpoint must use https so your key is sent encrypted"
    val NET_BLOCK_LOCAL: String get() = if (farsi) "دسترسی ابزارها به شبکهٔ محلی خاموش است؛ در تنظیمات آن را روشن کنید" else if (chinese) "工具的内网访问已关闭 — 请在设置中开启" else "Local-network access for tools is off — turn it on in Settings"
    val NET_BLOCK_METADATA: String get() = if (farsi) "دسترسی به سرویس متادیتای ابری مجاز نیست" else if (chinese) "不允许访问云元数据服务" else "Access to cloud metadata services is not allowed"
    val SET_LOCAL_NET: String get() = if (farsi) "دسترسی به شبکهٔ محلی" else if (chinese) "内网访问" else "Local network access"
    val SET_LOCAL_NET_H: String get() = if (farsi) "اجازهٔ دسترسی ابزارها به شبکهٔ محلی. سرویس خودتان همیشه آزاد است." else if (chinese) "允许工具访问本地网络。你自己的服务地址始终允许。" else "Lets tools reach your local network. Your own endpoint is always allowed."
    val SET_KEY_ROUTER: String get() = if (farsi) "روتر کلیدها" else if (chinese) "密钥轮换" else "Key Router"
    val SET_KEY_ROUTER_H: String get() = if (farsi) "کلیدهای جایگزین اضافه کنید (تا ۵۰ کلید). اگر کلید فعال هنگام گفتگو به محدودیت (Rate Limit) بخورد، برنامه بی‌صدا و بدون قطع شدن کار، کلید بعدی را امتحان می‌کند." else if (chinese) "添加备用密钥（最多 50 个）。对话中当前密钥触发速率限制时，会静默改用下一个密钥 — 工作不会中断。" else "Add fallback keys (up to 50). If the active key hits a rate limit while chatting, the next key is tried silently — the work never stops."
    val SET_KEY_ADD: String get() = if (farsi) "افزودن" else if (chinese) "添加" else "Add"
    val SET_KEY_EMPTY: String get() = if (farsi) "هنوز کلیدی اضافه نشده است" else if (chinese) "尚未添加密钥" else "No keys added yet"
    val SET_KEY_FULL: String get() = if (farsi) "حداکثر ۵۰ کلید می‌توانید اضافه کنید" else if (chinese) "最多可添加 50 个密钥" else "You can add up to 50 keys"
    val SET_KEY_DUP: String get() = if (farsi) "این کلید قبلاً اضافه شده است" else if (chinese) "该密钥已添加" else "This key is already added"
    val SET_RESET: String get() = if (farsi) "بازنشانی تنظیمات" else if (chinese) "重置设置" else "Reset settings"
    val SET_RESET_H: String get() = if (farsi) "همه تنظیمات به حالت اولیه برمی‌گردد؛ گفتگوها حفظ می‌شوند." else if (chinese) "所有设置恢复默认值；对话记录保留。" else "Every setting returns to its default; your chats are kept."
    val SET_RESET_MSG: String get() = if (farsi) "این کار همه تنظیمات (از جمله کلیدهای API) را پاک می‌کند و قابل بازگشت نیست." else if (chinese) "这将清空所有设置（包括 API 密钥），且无法撤销。" else "This wipes all settings (including API keys) and cannot be undone."
    val SET_RESET_DONE: String get() = if (farsi) "تنظیمات بازنشانی شد" else if (chinese) "设置已重置" else "Settings were reset"
    val SUG_1: String get() = if (farsi) "پوشه دانلود را نشان بده" else if (chinese) "显示我的下载文件夹" else "Show my Downloads folder"
    val SUG_3: String get() = if (farsi) "در وب جست‌وجو کن" else if (chinese) "搜索网络" else "Search the web"
    val SVC_CHANNEL: String get() = if (farsi) "اجرای وظیفه" else if (chinese) "任务执行" else "Task execution"
    val SVC_DONE: String get() = if (farsi) "وظیفه کامل شد" else if (chinese) "任务已完成" else "Task completed"
    val SVC_TEXT: String get() = if (farsi) "وظیفه در پس\u200cزمینه اجرا می\u200cشود" else if (chinese) "任务正在后台运行" else "Task running in the background"
    val SVC_TITLE: String get() = if (farsi) "Vega Agent در حال کار است" else if (chinese) "Harness Mate 正在工作" else "Harness Mate is working"
    val TAGLINE: String get() = if (farsi) "دستیار کدنویسی هوشمند روی دستگاه شما" else if (chinese) "你的本地智能编程助手" else "Your on-device intelligent coding assistant"
    val SET_THEME: String get() = if (farsi) "پوسته (تم)" else if (chinese) "主题" else "Theme"
    val SET_THEME_H: String get() = if (farsi) "از تم سیستم پیروی کنید یا حالت روشن/تاریک را انتخاب کنید." else if (chinese) "跟随系统主题，或选择浅色/深色模式。" else "Follow the system theme or choose light/dark mode."
    val THEME_SYSTEM: String get() = if (farsi) "سیستم" else if (chinese) "跟随系统" else "System"
    val THEME_LIGHT: String get() = if (farsi) "روشن" else if (chinese) "浅色" else "Light"
    val THEME_DARK: String get() = if (farsi) "تاریک" else if (chinese) "深色" else "Dark"
    val SECURITY_REDACTED: String get() = if (farsi) "\n\n[هشدار امنیتی: محتوای حاوی کلید API شناسایی و قبل از نوشتن حذف شد. کلید هرگز نباید در فایل ذخیره شود.]" else if (chinese) "\n\n[安全提示：检测到内容中包含 API 密钥，已在写入前移除。密钥绝不能保存到文件中。]" else "\n\n[Security warning: content containing an API key was detected and removed before writing. The key must never be stored in a file.]"
    val THINKING_LABEL: String get() = if (farsi) "استدلال مدل" else if (chinese) "模型推理" else "Model reasoning"
    val TL_HIGH: String get() = if (farsi) "زیاد" else if (chinese) "高" else "High"
    val TL_LOW: String get() = if (farsi) "کم" else if (chinese) "低" else "Low"
    val TL_MAX: String get() = if (farsi) "حداکثر" else if (chinese) "最高" else "Maximum"
    val TL_MED: String get() = if (farsi) "متوسط" else if (chinese) "中" else "Medium"
    val TL_XHIGH: String get() = if (farsi) "خیلی زیاد" else if (chinese) "很高" else "Very high"
    val TOOL_RAN: String get() = if (farsi) "ابزار اجرا شد" else if (chinese) "工具已执行" else "Tool ran"
    val TOOL_RUNNING: String get() = if (farsi) "در حال اجرا" else if (chinese) "运行中" else "Running"
    val TRAIL_DELEGATING: String get() = if (farsi) "واگذاری به زیرایجنت" else if (chinese) "正在委派" else "Delegating"
    val TRAIL_OPENING: String get() = if (farsi) "باز کردن صفحه" else if (chinese) "已打开页面" else "Opened page"
    val TRAIL_PAGES: String get() = if (farsi) "صفحه" else if (chinese) "个页面" else "pages"
    val TRAIL_PANEL_TITLE: String get() = if (farsi) "روند بررسی" else if (chinese) "思考过程" else "Thoughts"
    val TRAIL_RESULTS: String get() = if (farsi) "نتیجه" else if (chinese) "条结果" else "results"
    val TRAIL_RESULTS_TITLE: String get() = if (farsi) "نتایج وب" else if (chinese) "网页结果" else "Web Results"
    val TRAIL_RETRYING: String get() = if (farsi) "تلاش دوباره" else if (chinese) "重试中" else "Retrying"
    val TRAIL_SEARCHING: String get() = if (farsi) "جست‌وجو" else if (chinese) "搜索中" else "Searching"
    val TRAIL_SECONDS: String get() = if (farsi) "%s ثانیه" else if (chinese) "%s 秒" else "%ss"
    val TRAIL_THINKING: String get() = if (farsi) "بررسی درخواست" else if (chinese) "正在理解请求" else "Reviewing request"
    val TRAIL_THOUGHT_FOR: String get() = if (farsi) "بررسی در %s ثانیه" else if (chinese) "思考用时 %s 秒" else "Reviewed in %ss"
    val TRAIL_WORKING: String get() = if (farsi) "در حال کار" else if (chinese) "处理中" else "Working"
    val UNDO_CHANGES: String get() = if (farsi) "بازگرداندن این تغییرات" else if (chinese) "撤销这些改动" else "Undo these changes"
    val UNDO_DONE: String get() = if (farsi) "تغییرات بازگردانده شد" else if (chinese) "改动已撤销" else "Changes undone"
    val UNDO_FAILED: String get() = if (farsi) "بخشی از تغییرات بازگردانده نشد" else if (chinese) "部分改动无法撤销" else "Some changes could not be undone"
    val UNDO_NOTE: String get() = if (farsi) "فایل‌هایی که این اجرا تغییر داده بود، از نسخه‌ی پیش از هر تغییر بازگردانده می‌شوند." else if (chinese) "使用每项改动前保存的副本还原本次运行修改的文件。只影响本次运行。" else "Restores the files this run changed, from the copies taken before each one. Only this run is affected."
    val WELCOME_READY: String get() = if (farsi) "دستیار کد روی دستگاه شما؛ برای بررسی و ویرایش فایل‌ها، جست‌وجوی وب و تکمیل کارها." else if (chinese) "一个本地编程助手：可查看文件、修改项目、搜索网络并完成任务。" else "An on-device coding assistant that can inspect files, edit projects, search the web, and complete tasks."
    val WELCOME_SETUP: String get() = if (farsi) "برای شروع، کلید API خود را در تنظیمات وارد کنید." else if (chinese) "请先在设置中填写 API 密钥。" else "Set your API key in Settings to begin."
    val WF_DONE: String get() = if (farsi) "انجام شد" else if (chinese) "已完成" else "Done"
    val WF_FAILED: String get() = if (farsi) "ناموفق" else if (chinese) "失败" else "Failed"
    val WF_PENDING: String get() = if (farsi) "در انتظار" else if (chinese) "排队中" else "Queued"
    val WF_RUNNING: String get() = if (farsi) "در حال اجرا" else if (chinese) "运行中" else "Running"
    val WF_STEPS: String get() = if (farsi) "%s گام" else if (chinese) "%s 步" else "%s steps"
    val WF_TITLE: String get() = if (farsi) "گردش‌کار پویا" else if (chinese) "动态工作流" else "Dynamic Workflow"
    val TRAIL_CHANGES: String get() = if (farsi) "تغییر فایل" else if (chinese) "文件改动" else "File changes"
    val TRAIL_COPY: String get() = if (farsi) "کپی روند" else if (chinese) "复制活动记录" else "Copy activity"
    val TRAIL_COPIED: String get() = if (farsi) "روند بررسی کپی شد" else if (chinese) "活动记录已复制" else "Activity copied"
    val TRAIL_EDITED: String get() = if (farsi) "%s فایل" else if (chinese) "%s 个文件" else "%s files"
    val TRAIL_FAILED: String get() = if (farsi) "ناموفق" else if (chinese) "失败" else "Failed"
    val TRAIL_STOPPED: String get() = if (farsi) "متوقف شد" else if (chinese) "已停止" else "Stopped"
    val TRAIL_STEPS: String get() = if (farsi) "%s گام" else if (chinese) "%s 步" else "%s steps"
    val TRAIL_SUMMARY_NONE: String get() = if (farsi) "بدون تغییر" else if (chinese) "无改动" else "No changes"
    val TRAIL_THOUGHTS: String get() = if (farsi) "%s استدلال" else if (chinese) "%s 段思考" else "%s thoughts"
    val TRAIL_MS: String get() = if (farsi) "%s میلی‌ثانیه" else if (chinese) "%s 毫秒" else "%sms"
    val TRAIL_CLIPPED: String get() = if (farsi) "بخشی از تغییرات" else if (chinese) "仅显示部分改动" else "Part of the change"
    val TRAIL_REASONING: String get() = if (farsi) "استدلال" else if (chinese) "推理" else "Reasoning"
    val TRAIL_SHOW_MORE: String get() = if (farsi) "نمایش کامل" else if (chinese) "显示全部" else "Show all"
    val TRAIL_LIVE: String get() = if (farsi) "در حال انجام" else if (chinese) "进行中" else "In progress"
    val TRAIL_EMPTY: String get() = if (farsi) "کاری ثبت نشده است" else if (chinese) "暂无记录" else "Nothing recorded"
    val TRAIL_PROG_READING: String get() = if (farsi) "خواندن فایل" else if (chinese) "正在读取文件" else "Reading file"
    val TRAIL_PROG_WRITING: String get() = if (farsi) "نوشتن فایل" else if (chinese) "正在写入文件" else "Writing file"
    val TRAIL_PROG_SCANNING: String get() = if (farsi) "پویش پوشه‌ها" else if (chinese) "正在扫描文件夹" else "Scanning folders"
    val TRAIL_PROG_MATCHED: String get() = if (farsi) "%s مورد" else if (chinese) "%s 个匹配" else "%s matches"
    val TRAIL_PROG_DOWNLOAD: String get() = if (farsi) "دریافت %s" else if (chinese) "已下载 %s" else "Downloaded %s"
    val TRAIL_PROG_PAGES: String get() = if (farsi) "صفحه %s" else if (chinese) "第 %s 页" else "Page %s"
    val TRAIL_PROG_ENTRIES: String get() = if (farsi) "%s ورودی" else if (chinese) "%s 项" else "%s entries"
    val WF_STOPPED: String get() = if (farsi) "متوقف شد" else if (chinese) "已停止" else "Stopped"
    val WF_QUEUED: String get() = if (farsi) "%s در انتظار" else if (chinese) "%s 个排队中" else "%s queued"
    val WF_LIVE: String get() = if (farsi) "در حال اجرا" else if (chinese) "进行中" else "Live"
    val WF_HISTORY: String get() = if (farsi) "پایان‌یافته" else if (chinese) "已完成" else "Finished"
    val CHAT_MENU_TITLE: String get() = if (farsi) "گفتگو" else if (chinese) "对话" else "Conversation"
    val SET_VERSION: String get() = "v1"
    val PERM_TITLE: String get() = if (farsi) "دسترسی به فایل‌ها" else if (chinese) "文件访问" else "File access"
    val PRE_NO_KEY: String get() = if (farsi) "کلید API تنظیم نشده است. بدون آن هیچ درخواستی فرستاده نمی‌شود." else if (chinese) "尚未设置 API 密钥，没有密钥无法发送任何内容。" else "No API key is set. Nothing can be sent without one."
    val PRE_NO_MODEL: String get() = if (farsi) "نام مدل خالی است." else if (chinese) "模型名称为空。" else "The model name is empty."
    val PRE_BAD_ENDPOINT: String get() = if (farsi) "آدرس سرور معتبر نیست." else if (chinese) "服务地址不是有效的 URL。" else "The server address is not a valid URL."
    val PRE_MISMATCH: String get() = if (farsi) "کلید شما مال %s است ولی درخواست به %s فرستاده می‌شود؛ این سرور آن کلید را نمی‌پذیرد." else if (chinese) "你的密钥属于 %s，但请求发往 %s，对方不会接受该密钥。" else "Your key belongs to %s but the request goes to %s, which will not accept it."
    val PRE_MISMATCH_FIX: String get() = if (farsi) "در تنظیمات آدرس سرور را روی %s بگذارید: %s" else if (chinese) "请在设置中把服务地址改为 %s：%s" else "In Settings, point the server address at %s: %s"
    val PRE_OPEN_SETTINGS: String get() = if (farsi) "رفتن به تنظیمات" else if (chinese) "打开设置" else "Open Settings"
    val PRE_TITLE: String get() = if (farsi) "درخواست فرستاده نشد" else if (chinese) "请求未发送" else "Request not sent"
    val RUN_CONNECTING: String get() = if (farsi) "اتصال به سرویس" else if (chinese) "正在连接" else "Connecting"
    val RUN_RETRY_N: String get() = if (farsi) "تلاش دوباره (%s از %s)" else if (chinese) "正在重试（第 %s 次，共 %s 次）" else "Retrying (%s of %s)"
    val RUN_FAILED_STEP: String get() = if (farsi) "درخواست ناموفق" else if (chinese) "请求失败" else "Request failed"
    val RUN_GAVE_UP: String get() = if (farsi) "پاسخی از سرویس نرسید" else if (chinese) "服务无响应" else "No reply from the service"
    val ERR_DETAILS: String get() = if (farsi) "جزئیات" else if (chinese) "详情" else "Details"
    val SET_CONNECTION: String get() = if (farsi) "اتصال" else if (chinese) "连接" else "Connection"
    val SET_KEY_PLAIN: String get() = if (farsi) "این کلید بدون رمزگذاری سخت‌افزاری ذخیره شده است." else if (chinese) "该密钥未使用硬件加密保存。" else "This key is stored without hardware encryption."
    val SET_SUBTITLE: String get() = if (farsi) "سرویس، مدل و رفتار دستیار" else if (chinese) "服务地址、模型以及助手的工作方式" else "Endpoint, model and how the agent works"
    val SET_ABOUT: String get() = if (farsi) "درباره" else if (chinese) "关于" else "About"
    val SET_REASONING: String get() = if (farsi) "استدلال" else if (chinese) "推理" else "Reasoning"
    val SET_TOOLS: String get() = if (farsi) "ابزارها و دسترسی‌ها" else if (chinese) "工具与权限" else "Tools and access"
    val SET_GENERATION: String get() = if (farsi) "تولید پاسخ" else if (chinese) "生成" else "Generation"
    val SET_TEST_SHORT: String get() = if (farsi) "آزمایش" else if (chinese) "测试" else "Test"
    val SET_CONN_UNTESTED: String get() = if (farsi) "هنوز آزمایش نشده" else if (chinese) "尚未测试" else "Not tested yet"
    val SET_TEMP_H: String get() = if (farsi) "مقدار کم پاسخ‌ها را دقیق و تکرارپذیر می‌کند؛ مقدار زیاد آن‌ها را متنوع‌تر." else if (chinese) "数值低使回答更聚焦、更稳定；数值高则更富变化。" else "Low keeps answers focused and repeatable; high makes them more varied."
    val SET_TEMP_LOW: String get() = if (farsi) "دقیق" else if (chinese) "精确" else "Precise"
    val SET_TEMP_HIGH: String get() = if (farsi) "متنوع" else if (chinese) "多样" else "Varied"
    val SET_PROTOCOL_H: String get() = if (farsi) "حالت خودکار از آدرس سرویس و نام مدل تشخیص می‌دهد. برای gateway یا سرور شخصی خودتان یکی را دستی انتخاب کنید." else if (chinese) "自动模式会根据服务地址和模型名判断。使用网关或自建服务时请自行选择。" else "Auto reads the endpoint and the model name. Choose one yourself for a gateway or a self-hosted server."
    val SET_CUSTOM_H: String get() = if (farsi) "اختیاری. به همهٔ درخواست‌ها، در کنار دستورهای پیش‌فرض، افزوده می‌شود." else if (chinese) "可选。会在内置指令之外附加到每次请求中。" else "Optional. Added to every request, on top of the built-in instructions."
    val WILL_DELETE: String get() = if (farsi) "این مسیر حذف خواهد شد" else if (chinese) "该路径将被删除" else "This path will be deleted"
    val WORKING: String get() = if (farsi) "در حال کار…" else if (chinese) "处理中…" else "Working…"
    val STOP: String get() = if (farsi) "توقف اجرا" else if (chinese) "停止" else "Stop"
    val STOPPING: String get() = if (farsi) "در حال توقف…" else if (chinese) "正在停止…" else "Stopping…"
    val STOPPED: String get() = if (farsi) "اجرا متوقف شد" else if (chinese) "已停止" else "Stopped"
    val RUN_STALLED: String get() = if (farsi) "پاسخ نیمه‌کاره ماند\nبرای این گام چیزی تولید نشد. «ادامه» را بزنید تا از همان‌جا که ماند کار را تمام کنم." else if (chinese) "回复未完成\n这一步没有产生任何内容。点击「继续」，我会从停止的地方接着做。" else "The response was left unfinished\nNothing was produced for this step. Tap Continue and I will resume from exactly where it stopped."
    val RUN_INTERRUPTED: String get() = if (farsi) "اجرا نیمه‌کاره ماند\nاحتمالاً سیستم برنامه را در پس‌زمینه بست. می‌توانید از همان‌جا که ماند ادامه دهید." else if (chinese) "运行被中断\n很可能是系统在应用处于后台时关闭了它。你可以从停止的地方继续。" else "The run was cut short\nThe system most likely closed the app while it was in the background. You can pick up from where it stopped."
    val RUN_CONTINUE: String get() = if (farsi) "ادامه بده" else if (chinese) "继续" else "Continue"
    val RUN_CONTINUE_MSG: String get() = if (farsi) "ادامه بده و کار قبلی را دقیقاً از همان‌جا که ماند تا پایان کامل کن." else if (chinese) "从上次中断的地方继续完成之前的任务，直到结束。" else "Continue the previous task exactly from where it stopped until it is complete."
    val RUN_DISMISS: String get() = if (farsi) "بستن" else if (chinese) "忽略" else "Dismiss"
    val BATT_TITLE: String get() = if (farsi) "اجرای بدون وقفه" else if (chinese) "不间断运行" else "Uninterrupted execution"
    val BATT_MSG: String get() = if (farsi) "برای جلوگیری از توقف کارهای طولانی در پس‌زمینه، برنامه را از بهینه‌سازی باتری معاف کنید." else if (chinese) "允许忽略电池优化，以免长任务在后台被中断。" else "Allow battery-optimization exemption so long tasks are not stopped in the background."
    val BATT_ALLOW: String get() = if (farsi) "اجازه می‌دهم" else if (chinese) "允许" else "Allow"
    val BATT_LATER: String get() = if (farsi) "بعداً" else if (chinese) "稍后" else "Later"

    // ---- v1.1: failure reasons, real workflow, language, OEM survival ----
    val WF_PLANNED: String get() = if (farsi) "برنامه‌ریزی‌شده" else if (chinese) "已规划" else "Planned"
    val WF_TOPIC_MORE: String get() = if (farsi) "%s مورد دیگر" else if (chinese) "还有 %s 项" else "+%s more"
    val APPROVE_QUEUED_N: String get() = if (farsi) "%s مورد دیگر در انتظار" else if (chinese) "还有 %s 项在等待" else "%s more waiting"
    val TRAIL_REJECTED: String get() = if (farsi) "رد شد" else if (chinese) "已拒绝" else "Declined"
    val TRAIL_REASON: String get() = if (farsi) "دلیل خطا" else if (chinese) "失败原因" else "Why it failed"
    val TRAIL_OUTPUT: String get() = if (farsi) "خروجی ابزار" else if (chinese) "工具输出" else "Tool output"
    val TRAIL_TAP_REASON: String get() = if (farsi) "برای دیدن دلیل بزنید" else if (chinese) "点击查看原因" else "Tap to see why"
    val TRAIL_PROG_EDITING: String get() = if (farsi) "ویرایش فایل" else if (chinese) "正在编辑文件" else "Editing file"
    val TRAIL_PROG_BYTES: String get() = if (farsi) "تا اینجا %s" else if (chinese) "已完成 %s" else "%s so far"
    val PLAN_APPROVED: String get() = if (farsi) "نقشه تأیید شد — تغییر به حالت تأییدی" else if (chinese) "方案已通过 — 正在切换到执行模式" else "Plan approved — switching to Accepting"
    val MODE_ESCALATED: String get() = if (farsi) "تأییدی" else if (chinese) "执行模式" else "Accepting"
    val WF_AGENTS_LIVE: String get() = if (farsi) "%s در حال کار" else if (chinese) "%s 个进行中" else "%s working"
    val WF_AGENTS_DONE: String get() = if (farsi) "%s تکمیل شد" else if (chinese) "%s 个已完成" else "%s finished"
    val WF_AGENTS_NONE: String get() = if (farsi) "هنوز ایجنتی شروع نشده" else if (chinese) "尚未启动任何子代理" else "No agents started yet"
    val WF_AGENT: String get() = if (farsi) "ایجنت %s" else if (chinese) "子代理 %s" else "Agent %s"
    val WF_TOPIC: String get() = if (farsi) "در حال کار روی" else if (chinese) "正在处理" else "Working on"
    val WF_PARALLEL: String get() = if (farsi) "%s همزمان" else if (chinese) "同时 %s 个" else "%s at a time"
    val SET_LANGUAGE: String get() = if (farsi) "زبان" else if (chinese) "语言" else "Language"
    val SET_LANGUAGE_H: String get() = if (farsi) "زبان رابط برنامه. دستیار همیشه به همان زبانی پاسخ می‌دهد که شما می‌نویسید." else if (chinese) "界面语言。助手始终用你书写时所用的语言回答。" else "The interface language. The assistant always answers in whatever language you write to it."
    val SET_LANGUAGE_FA: String get() = if (farsi) "فارسی" else if (chinese) "波斯语" else "Persian"
    val SET_LANGUAGE_EN: String get() = "English"
    val LANG_TITLE: String get() = if (farsi) "زبان خود را انتخاب کنید" else if (chinese) "选择语言" else "Choose your language"
    val LANG_SUBTITLE: String get() = if (farsi) "بعداً می‌توانید از تنظیمات تغییرش دهید." else if (chinese) "之后可在设置中更改。" else "You can change this later in Settings."
    val LANG_FA_NOTE: String get() = "رابط کامل فارسی و راست‌چین"
    val LANG_EN_NOTE: String get() = "Full English interface"
    val LANG_ZH_NOTE: String get() = "完整的简体中文界面"
    val CHAT_EARLIER: String get() = if (farsi) "نمایش پیام‌های پیشین" else if (chinese) "显示更早的消息" else "Show earlier messages"
    val CHAT_HIDDEN_N: String get() = if (farsi) "%s پیام پیشین" else if (chinese) "%s 条更早的消息" else "%s earlier messages"
    val BATT_AUTOSTART: String get() = if (farsi) "اجازهٔ اجرای خودکار" else if (chinese) "允许自启动" else "Allow autostart"
    val BATT_AUTOSTART_MSG: String get() = if (farsi) "سازندهٔ گوشی شما جدا از تنظیم خود اندروید، کار در پس‌زمینه را هم محدود می‌کند. صفحهٔ سازنده را باز کنید و به Vega اجازهٔ اجرای خودکار بدهید، وگرنه کارهای طولانی باز هم قطع می‌شوند." else if (chinese) "手机厂商还会在 Android 自身的设置之外单独限制后台运行。请打开厂商的自启动管理页面，允许 Harness Mate 自启动，否则长任务仍会被终止。" else "Your phone's manufacturer also blocks background work separately from Android's own setting. Open the manufacturer's screen and allow Harness Mate to start on its own, or long tasks will still be killed."
    val BATT_OPEN: String get() = if (farsi) "باز کردن تنظیمات" else if (chinese) "打开设置" else "Open settings"
    val ERR_NO_TEXT: String get() = if (farsi) "سرویس پاسخی برگرداند که هیچ متنی در آن نبود." else if (chinese) "服务器返回的 JSON 中没有文本内容。" else "The server returned a JSON response with no text in it."
    val ERR_EMPTY_REPLY: String get() = if (farsi) "سرویس پاسخ خالی برگرداند. دوباره تلاش نشد تا همان درخواست دو بار محاسبه نشود." else if (chinese) "服务器返回了空响应。未做重试，因此同一请求不会被重复计费。" else "The server returned an empty response. It was not retried, so the same request is not billed twice."
    val ERR_STREAM_GAP: String get() = if (farsi) "سرویس بیش از زمان مجاز هیچ داده‌ای نفرستاد." else if (chinese) "服务器停止发送数据的时间超过了超时上限。" else "The server stopped sending data for longer than the timeout allows."
    val ERR_BAD_STREAM: String get() = if (farsi) "سرویس جریانی فرستاد که برنامه نتوانست بخواند: " else if (chinese) "服务返回了本应用无法解析的数据流：" else "The service sent a stream this app could not read: "
    val ERR_TOO_LARGE: String get() = if (farsi) "پیام یا تاریخچهٔ گفتگو از حد مجاز سرویس بزرگ‌تر است. کوتاهش کنید یا گفتگوی تازه‌ای شروع کنید." else if (chinese) "消息或对话历史超出了服务接受的长度。请精简内容，或新建对话。" else "The message or the conversation history is larger than the service accepts. Shorten it, or start a new chat."
    val ERR_UNPROCESSABLE: String get() = if (farsi) "سرویس نتوانست پارامترهای درخواست را پردازش کند. مدل و تنظیماتش را در تنظیمات بررسی کنید." else if (chinese) "服务无法处理请求参数。请在设置中检查模型及其选项。" else "The service could not process the request parameters. Check the model and its options in Settings."
    val ERR_NOT_READY: String get() = if (farsi) "سرویس هنوز آمادهٔ این درخواست نیست. کمی بعد دوباره تلاش کنید." else if (chinese) "服务尚未准备好处理该请求，请稍后再试。" else "The service is not ready to handle this request yet. Try again in a moment."
    val ERR_NO_CAPACITY: String get() = if (farsi) "ظرفیت سرویس موقتاً پر است." else if (chinese) "服务暂时无可用容量。" else "The service is temporarily out of capacity."
    val ERR_QUOTA: String get() = if (farsi) "سرویس خطای ۴۲۹ داد: سهمیه یا اعتبار حساب تمام شده است." else if (chinese) "服务返回 HTTP 429：账户额度或余额不足。" else "The service returned HTTP 429: the account is out of quota or credit."
    val ERR_MODEL_BUSY: String get() = if (farsi) "سرویس خطای ۴۲۹ داد: ظرفیت مدل موقتاً پر است." else if (chinese) "服务返回 HTTP 429：该模型暂时无可用容量。" else "The service returned HTTP 429: the model is temporarily out of capacity."
    val ERR_RATE_ACTIVE: String get() = if (farsi) "سرویس خطای ۴۲۹ داد: محدودیت نرخ درخواست یا توکن فعال است." else if (chinese) "服务返回 HTTP 429：已触发请求或 token 速率限制。" else "The service returned HTTP 429: a request or token rate limit is in effect."
    val ERR_STOPPED_BY_YOU: String get() = if (farsi) "به درخواست شما متوقف شد." else if (chinese) "已按你的要求停止。" else "Stopped at your request."
    val ERR_TLS: String get() = if (farsi) "اتصال امن TLS برقرار نشد: " else if (chinese) "无法建立安全的 TLS 连接：" else "Could not establish a secure TLS connection: "
    val ERR_DETAILS_LINE: String get() = if (farsi) "\nجزئیات سرویس: " else if (chinese) "\n服务详情：" else "\nService details: "
    val ERR_RETRY_IN: String get() = if (farsi) "\nدوباره تلاش کنید در %s" else if (chinese) "\n请在 %s 后重试" else "\nTry again in %s"
    val ERR_REQ_ID: String get() = if (farsi) "\nشناسهٔ درخواست: " else if (chinese) "\n请求 ID：" else "\nRequest ID: "
    val ERR_SECOND: String get() = if (farsi) "%s ثانیه" else if (chinese) "%s 秒" else "%s second"
    val ERR_SECONDS: String get() = if (farsi) "%s ثانیه" else if (chinese) "%s 秒" else "%s seconds"
    val ERR_PATH_OUTSIDE: String get() = if (farsi) "این مسیر بیرون از پوشه‌ای است که برنامه اجازهٔ استفاده از آن را دارد." else if (chinese) "该路径超出本应用可使用的文件夹范围。" else "That path is outside the folder this app may use."
    val ERR_PATH_BAD: String get() = if (farsi) "این مسیر فایل قابل تشخیص نبود" else if (chinese) "无法解析该文件路径" else "That file path could not be resolved"
    val ERR_PATH_PRIVATE: String get() = if (farsi) "دادهٔ خصوصی خود برنامه (کلیدها و تنظیمات) خارج از دسترس است: " else if (chinese) "应用自身的私有数据（密钥与设置）不可访问：" else "The app's own private data (keys and settings) is off limits: "
    val ERR_CAPTCHA: String get() = if (farsi) "این صفحه پشت یک کپچای تعاملی است که فقط انسان می‌تواند از آن بگذرد. پیوند مستقیم محتوا یا سایت دیگری را امتحان کنید." else if (chinese) "该页面有交互式验证码，只能由人通过。请尝试内容的直链，或改用其他网站。" else "This page is behind an interactive CAPTCHA, which only a person can clear. Try a direct link to the content, or another site."

    /**
     * Every rendering of [NEW_CHAT] this app has ever persisted.
     *
     * A chat's stored title is only a PLACEHOLDER until the first message names
     * it, and builds before v1 persisted the TRANSLATED text — so a transcript
     * written in Persian still carries the Persian placeholder on disk today.
     * Comparing against every known form keeps the auto-title check working on
     * those chats instead of leaving them stuck on a stale name.
     */
    private val PLACEHOLDER_TITLES = setOf("New chat", "\u06af\u0641\u062a\u06af\u0648\u06cc \u062c\u062f\u06cc\u062f")

    /** True when [title] is absent or still an unnamed placeholder. */
    /**
     * The risk tier in the interface's own language.
     *
     * [ToolRisk] carries an English label of its own, which is the right thing
     * for a log line and the wrong thing on the one screen that has to be
     * read before a decision.
     */
    fun riskLabel(risk: dev.wind.agent.security.ToolRisk): String = when (risk) {
        dev.wind.agent.security.ToolRisk.INTERNAL -> "Internal"
        dev.wind.agent.security.ToolRisk.READ -> RISK_READ
        dev.wind.agent.security.ToolRisk.NETWORK -> RISK_NETWORK
        dev.wind.agent.security.ToolRisk.WRITE -> RISK_WRITE
        dev.wind.agent.security.ToolRisk.EXECUTE -> RISK_EXECUTE
        dev.wind.agent.security.ToolRisk.NETWORK_EXECUTE -> RISK_NETWORK_EXECUTE
        dev.wind.agent.security.ToolRisk.DELETE -> RISK_DELETE
        dev.wind.agent.security.ToolRisk.DENY -> "Forbidden"
    }

    fun isPlaceholderTitle(title: String?): Boolean {
        val t = title?.trimJava() ?: ""
        return t.isEmpty() || PLACEHOLDER_TITLES.contains(t)
    }

    /**
     * Every rendering of [RUN_STALLED] this app has ever persisted.
     *
     * The marker is written into the transcript as a real assistant turn, and
     * `lastTurnUnfinished()` tests for it to decide whether to offer the continue
     * card. Both pre-v1 translations are on disk in existing chats and v1
     * reworded it again, so every form has to match — otherwise the continue card
     * disappears from exactly the message that asks you to tap it.
     *
     * The two live forms are read from the getters, so rewording either one keeps
     * this set correct automatically.
     */
    private val LEGACY_STALLED = setOf(
        "It seems no response was generated for this step. If the task is unfinished, tap Continue to resume from here.",
        "\u0628\u0647 \u0646\u0638\u0631 \u0645\u06cc\u200c\u0631\u0633\u062f \u067e\u0627\u0633\u062e\u06cc \u0628\u0631\u0627\u06cc \u0627\u06cc\u0646 \u06af\u0627\u0645 \u062a\u0648\u0644\u06cc\u062f \u0646\u0634\u062f. \u0627\u06af\u0631 \u06a9\u0627\u0631 \u0646\u0627\u062a\u0645\u0627\u0645 \u0627\u0633\u062a\u060c \u00ab\u0627\u062f\u0627\u0645\u0647\u00bb \u0631\u0627 \u0628\u0632\u0646\u06cc\u062f \u062a\u0627 \u0627\u0632 \u0647\u0645\u06cc\u0646\u200c\u062c\u0627 \u0627\u062f\u0627\u0645\u0647 \u062f\u0647\u0645."
    )

    /** True when [content] is the "nothing was produced" marker, in any build's wording. */
    fun isStalledMessage(content: String?): Boolean {
        val t = content?.trimJava() ?: ""
        if (t.isEmpty()) {
            return false
        }
        // Both live renderings, not just the active one: the language may have
        // been switched since the run that wrote this.
        val live = farsi
        try {
            farsi = false
            if (t == RUN_STALLED) return true
            farsi = true
            if (t == RUN_STALLED) return true
        } finally {
            farsi = live
        }
        return LEGACY_STALLED.contains(t)
    }
}
