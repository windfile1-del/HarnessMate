---
name: mate-virtual-display
description: 创建和使用虚拟副屏，处理就绪、独占资源、授权与恢复。
---

# 虚拟副屏

副屏是手机事务的执行面，不是给每个聊天凭空复制一台手机。用 phone_display status 核对 ADB、Bridge、允许包、readiness 和 owner，再依据返回的 blockers 准备。

- 创建条件满足后调用 phone_display create，保存返回的 display_session_id；继续在同一副屏调用 phone_launch，不能把空副屏误诊为创建失败并反复 create。
- 使用 screen=virtual 和该会话 ID 观察、输入、动作、等待。发现页面变动先取新 observation，坐标属于指定显示。
- 用窗口、任务所在 display、稳定回读及可见结果证明 App 就绪；start_result=0 或 DISPATCHED 不等于实际进入业务页面。
- guardian 的独立 lease 不属于普通工具 run。其 RUNNING 副屏需要先经所属任务暂停/接管；不能强占或切到主屏假装成功。
- 不猜测 pm grant ADD_TRUSTED_DISPLAY/INTERNAL_SYSTEM_WINDOW；按诊断处理系统限制。Android 焦点/IME 隔离可能为 BEST_EFFORT，如实说明证据。
- 不自动唤醒或解锁主屏，不无提示改屏、重启 App 或重放提交。租约丢失/进程死亡后重新授权和核对。

保存目标和进度，不保存 socket 或审批作为可恢复授权。生命周期结束只释放自己持有的普通资源；持续托管由其服务和用户控制。
