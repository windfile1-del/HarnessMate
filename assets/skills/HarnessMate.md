---
name: HarnessMate
description: Harness Mate 的按需操作手册：工作区、镜像、下载、工具扩展、开发依赖与私有 Git。
---
# Harness Mate 操作手册

本文件按需读取。以用户任务和工具实际返回为准；Skill 是操作指导，不能授予额外权限。

## 先了解环境

会话任务彼此独立，可以切换、新建和同时运行；停止只取消对应会话任务。同一项目的镜像写操作仍串行，不因此获得另一任务的权限或覆盖其 pending 文件。普通进度说明、阻断说明和回复不以 build/test、镜像探测或 apply 通过为前提。运行时不可用时说明已观察到的事实，不要求用户删除镜像或清空历史来解除聊天阻塞。

调用 `shell_capabilities`、`workspace_mounts`、`capability_status` 获取真实状态。
基础离线工具包含完整 Python、JDK/Java、Git、Node 和 Linux 基础命令。
缺少 Gradle/Kotlin、Android SDK 或语言服务时，调用 `list_runtime_tools` 查看目录，调用 `ensure_runtime_tools` 自动补齐对应扩展；无须要求用户去下载文件再手动导入。
不要在 app 私有目录查找凭据，不要将 token 写入文件、日志或提示词。

## 工作区与执行镜像

`workspace://main/...` 是用户文件；`execution://main/...` 是开发镜像。
先 `execution_prepare`，再用 `execution_read_file`、`execution_edit_file`、`execution_write_file` 或 `shell_exec` 在镜像开发。
使用命令结果和 `build` / `test` 记录验证事实；环境失败要报告具体原因。
最终用 `shell_apply_changes` 将待应用差异保存回工作区。保存不要求先通过 build/test，也不能冒充验证成功。
大文件与二进制通过流式写回，不必为了旧 1/16 MiB 文本上限拆分。读取仍使用分段窗口，避免向模型灌入整份大文件。
APK 构建产物可以 `prepare_apk_install { execution_path: ... }`，再 `install_apk`；系统安装有独立授权。

## 工具扩展安装

`list_runtime_tools {}` 返回内置固定目录、依赖和安装状态。
`ensure_runtime_tools { tools: ["gradle", "kotlin"] }` 准备 JVM 构建扩展。
`ensure_runtime_tools { tools: ["android-build"] }` 自动按基础环境、JVM、Android 的顺序准备依赖。
`ensure_runtime_tools { tools: ["code-intelligence"] }` 准备语言服务扩展。
若返回 `READY_TO_INSTALL`，继续调用 `install_runtime_tools { plan_id: "返回的计划 ID" }`。这是同一自动补齐流程中的精确安装授权步骤，不要把它误解为要求用户下载或手动安装。返回 `READY` 表示无需安装。
下载、清单校验、原子版本安装、功能探测和能力刷新由 Harness 完成。下载错误不得将项目镜像标为 BROKEN。
JVM/Android 扩展部署只验证包和必要文件，不自动编译示例工程，不要求 Debug/Release、Lint 或单元测试先通过。项目构建是安装后按需执行的独立任务；缺依赖时通过 `project_packages manager=gradle` 并按任务授权联网解析，不能把安装失败当作修项目代码的理由。
任务结束只报告已记录的工具结果，不额外扫描或校验整个镜像。停止后若后台清理尚未确认，会明确返回清理待确认；下次 `execution_prepare` 可重整中断状态并保留原有改动，不删除工作区或清空镜像来解除聊天。系统强杀与普通停止不同，未保存数据和未完成写入不能冒充已成功。
`download_sha256` 校验下载的整个 `.hm-pack` ZIP，`payload_sha256` 只校验包内的 `tools.tar.gz`，不能混用。优先使用上述自动安装流程，不要为了哈希错误关闭校验或将包解压到工作区冒充已安装 SDK。HTTP 500/502/503/504 会有限重试；持续失败时报告下载服务响应，不能据此认定项目代码错误或要求反复构建。
目录里的工具包与当前 APK 固定清单匹配；网络下载成功不代表可以执行任意第三方工具。

## npm 与 pip / PyPI

Node 和 Python 是基础工具，先确认实际可用版本。优先用 `project_packages` 在指定镜像目录操作；复杂工作流仍可用授权的 `shell_exec`，不在用户工作区外随意安装。
Node 已有 package-lock.json 时用 `project_packages { manager: "npm", action: "ci", project_path: "execution://main" }`；首次按 package.json 安装用 action="install"。新增依赖用 action="add", packages=["ms@2.1.3"]，需要开发依赖时加 dev=true。npm 会保存 package.json/package-lock.json；不要声称 ci 会修正不一致的锁文件。
Python 新增依赖用 `project_packages { manager: "pip", action: "add", project_path: "execution://main", packages: ["six==1.17.0"] }`，工具自动创建隔离的 `.harnessmate-venv`，通过它的 Python 调用 pip。有带哈希的 requirements 文件时用 action="install", requirements_path="requirements.lock"；require_hashes 默认 true，普通无哈希 requirements 可以显式设 false。action="freeze" 返回版本清单，模型按项目约定用 execution_write_file 保存；freeze 清单本身不是完整哈希锁。
两种管理器均支持 action="list"/"check"/"remove"。移除 packages 只填包名。网络访问默认指向 registry.npmjs.org 或 pypi.org/files.pythonhosted.org；可设置 HTTPS registry_url 和额外 network_domains。当前 network_domains 是授权/审计元数据，运行时网络隔离仍为 POLICY_ONLY，不代表系统逐域名拦截。
离线使用 network=false：npm 使用 --offline 缓存，pip 使用 --no-index，并可设置相对 wheelhouse。Python 源码包、requirements 中直接 URL、项目配置与安装脚本可能有额外外部行为，按实际内容和所需域名判断，不把离线参数误当硬网络隔离。
npm 默认不执行安装脚本，包需要构建脚本时显式 allow_scripts=true；pip 可设 wheel_only=true 只用兼容 wheel。工具不自动重试失败安装，部分依赖或声明可能已改变，先检查结果与 diff。
依赖缓存、node_modules 与工具管理的 Python 环境留在镜像，不经 SAF 写回。package.json、锁文件和模型保存的 requirements 通过 shell_apply_changes 写回；不要删除环境来满足完成门。使用 Python 时执行 `.harnessmate-venv/bin/python` 或其工具入口。成功安装不等于项目 build/test 已通过。
包安装失败时区分网络、版本、缺少适配 Android 的二进制 wheel 和构建依赖，不要把系统缺失谎报为源码错误。

## Maven 与 Android dependencies

已有 `resolve_maven_dependencies` / `install_maven_dependencies` 用于工具明确支持的依赖与锁定发布。
Android Gradle 项目先用 ensure_runtime_tools/install_runtime_tools 准备 Gradle 与 Android 扩展。依赖仍由项目的 google()/mavenCentral()、版本目录和 Gradle 插件声明管理，不需要手动下载 AAR 或把它当 JAR 拆开。
先用 `project_packages { manager: "gradle", action: "list", project_path: "execution://main" }` 查看模块与可解析配置；默认离线，插件尚未缓存时可显式加 network=true。Android 常见配置为 `:app` 的 debugCompileClasspath/debugRuntimeClasspath，其他 variant 以实际 list 结果为准。
声明好依赖之后，用 `project_packages { manager: "gradle", action: "resolve", project_path: "execution://main", module: ":app", configurations: ["debugCompileClasspath", "debugRuntimeClasspath"] }` 下载实际 AAR/JAR 与传递依赖；常用 AndroidX、Material、Compose 等仍按项目兼容的固定版本或版本目录添加。工具返回坐标、大小和观察到的 SHA-256，资源、manifest、JNI 合并由后续 Android Gradle 构建负责。
`action="check"` 默认离线复查所选依赖能否解析；`action="lock"` 使用 Gradle --write-locks 保存所选配置锁，再按正常流程 apply 锁文件。要让后续普通 Gradle 构建也强制使用锁，请在项目声明中启用 dependencyLocking；已有 gradle/verification-metadata.xml 会按 strict 模式检查，单纯观察到的 SHA-256 不代表发布者签名信任。自定义仓库/CDN可补 network_domains，不支持自动注入私有仓库凭据。
不要声称当前原生 Maven JAR 安装器已经支持所有 AAR、Android 资源或 Gradle/Maven 完整语义；不支持的输入使用相应构建系统，并报告真实验证结果。

## 网络、下载与 Git

`web_search` 用设置内的搜索服务；`web_fetch` 获取页面文本。
`download_file` / `download_github_repository` 将文件放入隔离缓存，返回 artifact:// 地址；公共源码 ZIP 不是完整 git clone。
`list_downloads`、`inspect_artifact` 查看下载。保存到工作区用 `prepare_download_apply`、`apply_download`。
私有 Git：用户在设置 → Git / 下载凭据配置精确 HTTPS 仓库地址、用户名、Token、期限及写权限。调用 `list_secrets` 取得元数据和 `secret://credential/...` 引用，不要求用户在聊天中提供 Token，不读取凭据存储文件。
调用 `remote_git { action: "refs"|"clone"|"fetch"|"push", repository_url, secret_id? }`。clone/fetch 返回包含 Git 历史的任务 artifact ZIP，使用已有下载发布/导入流程保存；fetch 只更新远端引用，显式 `ref` 才 checkout。公网读取可不指定凭据，push 必须具备 git_write。
推送本地修改：在 execution 镜像生成源码 ZIP，排除 `.git`、`.harnessmate-package-cache`、`.harnessmate-venv`；用 `publish_execution_artifact { execution_path }` 流式取得 artifact_uri，再传给 `remote_git { action: "push", repository_url, secret_id, source_artifact_uri, branch, commit_message }`。`replace_tree: true` 会删除 ZIP 中缺失的已跟踪文件，只在任务明确需要删除时使用。push 为普通非 force 推送，失败/结果不明时先检查 refs，不能自动重试。子模块和 Git LFS 不会自动下载。
`download_file` 的可选 secret_id 支持 artifact_read 凭据对精确 HTTPS 文件地址的原生 Basic 认证，不转发重定向凭据；不是任意站点登录/私有注册源认证接口。凭据不进入 shell 参数、环境或工程配置。
扩展包经 APK 内置发布者公钥验证独立签名，再按内置清单校验载荷；没有签名、签名错误或过期时不可导入/激活。旧未签名缓存会由工具安装闭环获取签名版本。下载摘要一致不表示任意第三方包已获得发布者信任。

## 后台手机副屏

用户已选择后台虚拟屏幕时，先 `phone_display { action: "status" }`。无需校准；读取 readiness.blockers、missing_capabilities、ADB 连接阶段和 platform_apis。若有阻塞，按 user_action 引导用户进入“设置 → 终端权限 → 后台虚拟屏幕”，完成授权、连接和选择允许应用；不能反复调用 create，也不能自动授予能力。设备接口检测为可选，不是创建前置步骤。
焦点策略为 BEST_EFFORT：存在并获准使用独立焦点接口时启用其标志，否则使用 SHARED_FOCUS。Android 12/13 不因缺少这些可选接口而拒绝创建；主屏焦点、键盘和应用行为可能受影响，不能承诺主屏不受干扰。输入仍绑定副屏，不将动作改发主屏。
仅当 readiness.creation_ready=true 时 `phone_display { action: "create" }` 获取任务的 display_session_id。必须先 phone_launch 允许应用，再观察或执行流程；尚未创建时不会自动创建，已关闭或失效后不能在本任务重新创建。

创建进程是 Local ADB 启动的 `com.android.shell`（UID 2000），不是 `dev.wind.agent`。不要通过 adb_shell/adb_permission 执行 pm grant/revoke ADD_TRUSTED_DISPLAY 或 INTERNAL_SYSTEM_WINDOW，也不要要求用户授予这些系统权限；它们不是普通应用的运行时权限。缺少 trusted、焦点或 IME 隔离时允许 BEST_EFFORT，按返回的 focus_mode、ime_policy 描述主屏/键盘影响。实际 create 失败时保留 diagnostic.failure_stage、authority 和 status.last_creation_failure，不虚构权限修复。

create 成功后空副屏尚无应用窗口。先显式 phone_launch，收到 VIRTUAL_DISPLAY_APP_REQUIRED 时继续当前会话并启动允许包，不要重建。会话真的失效时查看 status.bridge.close_reason、bound/current_permission_revision、bound/current_adb_epoch、lifetime_cancelled、task_ended 和 server_exit。修订 7 的未捕获异常记录还包括 exception_class、thread、cause_classes、stack_frames（代码类/方法/行号，无用户内容）。ADB_RAW_CLOSED 只表示 raw 通道收到 CLOSE；没有证据不能归因为 OEM 强杀。platform_apis.checked_at 是能力缓存记录时间，display 是创建时的元数据快照，不能用来计算失效时间或证明副屏仍然存活。独立心跳仅续租并检查副屏资源/会话状态，锁屏不释放副屏，不能替代实际 launch/observe/action 验收。

修订 8 使用 MANAGED_SHELL_V2 受管理进程通道。启动失败保存 bridge.bootstrap.failure_stage / exception_class / pid、bridge.process.process_exit_code 与 server_exit；stderr 不作为协议或用户结果输出。phone_launch 使用系统 Binder 的显式副屏启动；singleTask/singleTop 或不可缩放声明不再单独被 Harness 判定不兼容。启动预检失败查看 diagnostic.failure_stage（TASK_INVENTORY / RESOLVE_ACTIVITY / RESOLVE_USER / SYSTEM_LAUNCH_POLICY / RESOLVE_START_API），不要把 VIRTUAL_DISPLAY_*_FAILED 泛化成缺少权限。launch.task_display_verified 仅证明任务已在请求屏幕，不证明前台页面或业务完成；继续 observe 后确认。修订12默认 task_policy:reuse_or_move 可将请求应用的单个主屏任务迁入副屏；new_only 保留跨屏拒绝。不要用 raw ADB force-stop、清数据或重放未知启动。
把该字段用于 phone_launch、phone_observe、phone_action、phone_wait、phone_flow、phone_sequence；省略屏幕选择时采用设置默认值；screen:main 或 screen:virtual 可每次明确选择，副屏失效不会自动改走主屏，明确选择主屏仍可使用。
启动应用使用已设置的允许列表；同一应用的已有主屏任务通过 phone_launch task_policy:reuse_or_move 迁入副屏；不 force-stop 或清数据，正常关闭时尽力归还借入任务。singleTask/singleTop、不可缩放应用由实际系统启动结果和任务屏幕回读判断。

修订 9 为测试放宽副屏窗口策略：观察和操作当前副屏的实际窗口不受包名黑名单/启动白名单限制，权限控制器和安装器等系统弹窗可观察。按副屏窗口焦点、活动状态和层级选择窗口，键盘、分屏分隔条和放大辅助窗口不作为应用树目标；系统窗口存在本身不报 PHONE_PROTECTED_WINDOW。普通观察未就绪或启动预检拒绝保留副屏，可继续 observe，不重放已提交动作；错误包含 diagnostic.window_routing，launch 的后置观察失败包含 observation_diagnostic。状态为 DISPLAY_CLOSED 的诊断表示 Harness 主动释放资源，code 是原始原因，不代表进程崩溃。锁屏或熄屏保留会话；会话身份/几何/真实显示器失效仍停止，不将动作切到主屏。

修订10允许type=-1等未知类型、active=false/focused=false的副屏窗口通过本窗口root探测进入观察与节点操作。type=-1不是“未绘制”的证据，不先注入触摸、抢焦点或反复启动。诊断window_routing.root_probes记录窗口根是否可读；ROOT_UNAVAILABLE表示已探测但节点树暂不可读，可继续同会话观察。diagnostic.render只报告副屏缓冲区/帧序号，不证明应用内容或业务成功。不要因未聚焦就要求用户补系统权限。
文字优先用新观察里的编辑节点 set_text/type_text；没有节点的 WebView 先视觉点击输入框，再重新观察并用无 node_id 的 type_text/set_text/paste_text。Unicode 路径会更新共享剪贴板，不切换键盘；发送需点击观察到的按钮。
副屏不支持 Home/Recents/通知栏/全局手机工具/raw ADB 写；等待使用 UI，OCR anchor 第一版仅供观察。必要时报告不支持，不绕过。
完成时 `phone_display { action: "close", display_session_id: "当前会话" }`。UNKNOWN 不重发，不重跑整个 flow，也不自动重新创建会话。status/前台验证/实际业务完成分别描述。

## 用户 Skill

调用 `list_skills` 发现名称、简介和 skills:// 路径；调用 `read_skill` 按页读取正文和相对 references 文件。
用户在设置中导入 Markdown/Skill ZIP，或在聊天选择 Skill 后，只向模型暴露所选路径。
不要将所有 Skill 正文常驻加入系统提示；只读当前任务需要的内容。Skill 中的示例或脚本不自动执行。

### 副屏修订 11：每次选择屏幕与 WebView 输入

`phone_observe/action/wait/launch/flow/sequence` 接受 `screen: "main"|"virtual"`，设置只是默认值，同一聊天可自由选择。`display_session_id` 明确选择副屏，与 `screen:"main"` 同时提供会报参数冲突；切换屏幕后重新观察，不复用另一屏的 observation_id。副屏存活时主屏 ADB 控制复用同一 Bridge，不再因会话选过副屏而禁用主屏。

封闭 WebView 没有可编辑节点时，先用视觉观察点击输入框，再重新观察。`phone_action {screen:"virtual",display_session_id:"...",observation_id:"...",action:"type_text",text:"上海"}` 不需要 node_id。`type_text` 在光标处插入，`set_text` 全选替换，`paste_text` 显式粘贴（replace 可选）。字符全部能映射时用指定屏幕的物理按键，否则整段走 Unicode 剪贴板粘贴，避免部分输入后自动重放；不切换 IME。剪贴板是系统共享资源，此路径会更新它，返回 input.clipboard_changed。DISPATCHED 只证明派发，须用视觉检查真实文字。

主屏事件和副屏事件分别计数，纯焦点/active 切换不会更新页面时钟；真实副屏内容与窗口结构变化仍需重新观察。Android 12 共享焦点、键盘和剪贴板不等同于硬隔离，不承诺所有 OEM 上完全互不干扰。PACKAGE_PRESENT_ON_OTHER_DISPLAY 说明已有任务在另一屏，不能仅凭此判断微信自身不支持副屏；不使用 raw ADB force-stop；有意在副屏使用同一应用实例时采用明确副屏 phone_launch 的任务迁移路径。

Android 12 的物理按键输入在必要时短暂借用目标任务焦点，操作后仅当焦点仍属于副屏才归还原主屏任务；用户已切换的新焦点优先。触摸后同样尝试归还先前主屏焦点，节点动作不主动切换焦点。input.focus_borrowed / focus_restored / focus_lease_ms 报告实际路径；这是兼容性缓解，不是双屏独立焦点。

### 副屏修订12：锁屏保留与单实例任务启动

phone_launch 默认 task_policy:"reuse_or_move"：仅将请求包的同一用户、单个完整主屏任务迁入副屏；不是双开，主屏不再同时保留同一实例。系统接受启动后若应用入口跳回主屏，在同一次提交中只做一次任务归位，不重复启动Intent。正常close先尽力归还借入的任务；新创建任务仍随副屏关闭销毁。进程被强杀时不能保证归还。task_policy:"new_only"保留不迁移主屏任务的行为。检查launch.task_moves、tasks_before/tasks_after、task_display_verified，以及close.task_returns；存在多个候选、混合包或另一副屏任务时不猜测搬移。LAUNCH_NOT_VERIFIED且submitted:true表示已派发而绑定未确认，不自动重放，副屏可保留。

锁屏/熄屏不再因PHONE_LOCKED或PHONE_SCREEN_NOT_INTERACTIVE释放副屏或断开独立心跳。display.host_interactive/host_keyguard_locked/display_state/lock_policy返回当前状态。Android12没有独立ALWAYS_UNLOCKED显示组能力，保留副屏不等于绕过锁屏或保证锁屏期间所有App仍绘制；若树或帧不可用，等待用户解锁后重新观察同一会话。不唤醒/解锁手机，不向主屏发送输入，不自动新建副屏，不新增系统授权。
