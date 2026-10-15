---
name: mate-phone-tasks
description: 手机 App 办事，组合主屏和副屏操作并回读实际结果。
---

# 手机办事

把用户的目标办成可核对的结果，而不是交付一套工具教程。先读 phone_status、adb_status、phone_apps；缺少能力时使用返回的原生设置/授权入口，不猜权限或包名。

1. 明确本次事务的 App、目标页面和用户约束。多个 App 可服务同一生活任务，Code 工具和脚本也可使用。
2. 显式选择 screen=main 或 virtual；副屏还需当前 display_session_id。正在托管的副屏不可抢占。
3. phone_launch 后观察真实窗口；用观察得到的 observation_id、node/ref 或坐标执行动作。先 tool_describe 读取所用工具的完整字段。
4. 页面/窗口变化后重新观察。优先节点、普通输入和已观察到的短 phone_flow；不要盲发一长串 tap、sleep 或 shell input。
5. 填写后回读值，点击后回读页面或业务记录。DISPATCHED 只证明派发，不是发送、办理或付款成功。
6. 发送/提交结果 UNKNOWN 时停止该动作，保留证据并走用户接管。新 request_id 不能作为重试未知动作的理由。

用 mate_task note 保存核对到的进度；缺少必要信息时请求真实澄清。待用户处理时保留任务和等待原因；恢复先观察新页面，旧快照、批准和租约不能重放。
