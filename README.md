<div align="center">

# HarnessMate

### Turn AI conversations into real actions on Android.

**一个面向真实任务的 Android AI 助手：观察、规划、操作、核验。**

[![Android](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white)](#requirements)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-7F52FF?logo=kotlin&logoColor=white)](#project-layout)
[![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL--3.0-blue)](LICENSE)
[![Source only](https://img.shields.io/badge/Distribution-source--only-orange)](#build-from-source)

[中文](#简体中文) · [English](#english) · [安全说明](SECURITY.md) · [许可证](LICENSE)

</div>

---

<a id="简体中文"></a>

## 简体中文

**HarnessMate** 是一个 Android-first、用户可控的 AI Agent。它的目标不是给聊天应用再加一个对话窗口，而是让语言模型在用户授权范围内调度工具，协助完成手机操作、网页任务、文件管理和软件开发。

它由早期 Vega Agent 代码基础演进而来，现采用 **HarnessMate** 品牌和独立的产品定位。

### 两种模式，一套工具能力

| 模式 | 面向场景 | 默认行为 |
| --- | --- | --- |
| **Mate** | 日常事务、手机和网页流程 | 理解目标，选择可用工具，执行后核对结果；必要时请求用户审批 |
| **Code** | 源码编辑、项目操作、开发任务 | 更关注工作区、编程工具、代码变更与构建验证 |

Code / Mate 是两种**工作定位**，不是安全权限等级：切换模式不会自行授予 ADB、文件修改或浏览器操作权限。

### 核心功能

- **📱 受控 Android 自动化**：屏幕观察、无障碍交互、手机操作工具以及内置 Local ADB；高级能力需用户授权和设备实际支持。
- **🪟 虚拟副屏**：在兼容设备上将特定操作绑定到虚拟显示目标，支持观察、输入和预览。它不是虚拟机；焦点、输入法和跨应用兼容性受 Android 版本及 OEM 限制。
- **🌐 真正的 WebView 浏览器 Agent**：导航、观察页面、点击、填写、下拉选择、滚动、等待、标签页和人工接管，按可验证的结果汇报。**按钮点击成功不代表购买、发布或付款成功。**
- **💬 聊天托管基础能力**：面向授权联系人的文字消息监测、审批、发送状态记录和汇报机制；目前不是“所有微信/QQ账号、联系人和系统版本全自动支持”的承诺。
- **🧠 长期记忆与上下文管理**：个人/项目/任务范围、记忆查询、来源和遗忘控制、历史召回、会话归档与独立目录恢复。
- **🧩 Skills 与按需工具发现**：显式安装、列出和读取 Skill；Skill 不会自动获得文件、设备或网络权限。
- **🛠️ 本地开发工具**：文件搜索与修改、Diff、受控执行环境、项目分析和移动端编译相关工具链。
- **🤖 多模型接入**：API/兼容接口、多账户和推理参数；实验性网页模型适配是单独通道，**网页模型不自动获得本地工具访问**。

### 工作原则

1. **设备在用户手中**：对文件、屏幕、应用、ADB、浏览器等能力逐项授权，可在任务结束或用户撤销时失效。
2. **先核实，再报告**：工具执行、页面确认和外部业务成功是不同的状态；未知结果不能伪装为成功。
3. **尊重系统边界**：不需要 Root 才能使用基本功能，但也不会通过 Local ADB、SAF 或虚拟副屏绕过 Android 的系统权限。
4. **本地优先而非默认离线**：工具与数据管理主要在本机；使用云端模型时，任务上下文和必要数据仍可能发往用户配置的模型供应商。
5. **敏感操作需人参与**：与账号、隐私、消息发送、承诺、付款或删除有关的操作，应检查权限与审批结果。

### Requirements

- 工程声明的最低安装版本是 **Android 6.0 / API 23**。副屏、后台生命周期、特定自动化和模型接入可能要求更高系统能力。
- 使用云端模型需要相应账户、密钥或支持的授权方式；模型可能产生供应商费用。
- 手机上的自动化和后台任务依赖可用的系统服务与用户授予的权限。
- Local ADB 功能在部分设备上仍需要无线调试配对、USB 初始化或其他系统允许的设置。

### 源码发布说明

**此仓库是从私人开发仓库整理出的全新开源快照，而不是 Fork。** 它只有新仓库自己的发布提交，不包含原仓库的 Git 历史、内部开发日志、验收报告、个人配置、历史 APK、签名文件或本地环境数据。

为减少不必要的发布和许可证风险，本公开快照也**没有包含大型预编译 Android/Kotlin/ADB 工具链、native `*.so`、第三方 `*.jar`/`*.aar`、OCR 模型、内置字库及其他打包运行时文件**。这些是原私有工程的一部分；当前仓库侧重公开**第一方源码、界面资源、构建脚本和测试代码**。

**Google OAuth 说明：** 此公开快照已移除原私人工程中的 Google OAuth Client ID 与 Client Secret。Google 订阅授权入口默认会明确拒绝启动，开发者必须自行注册并在本地配置有权使用的 OAuth 应用凭据；不要将凭据提交到公开仓库。

**因此，首次克隆不能保证直接编译出完整 APK。** 有些脚本使用私有工程中已预置的构建依赖路径；运行前须从具有合法分发和使用权的来源补齐对应版本、资源和本地 Android SDK。也不要把本仓库理解为已经上传可安装的正式 Release。

### 从源码开始

```bash
git clone https://github.com/windfile1-del/HarnessMate.git
cd HarnessMate
```

工程布局为根目录 `src/`、`res/`、`assets/`、`AndroidManifest.xml`，并包含 `build.gradle.kts` 和 `mkapk.sh` / `mkapk.ps1` 等构建入口。

准备好 JDK、Android SDK、工程脚本要求的依赖及签名配置后，可查看以下构建入口：

```bash
# Linux / compatible Bash environment
./mkapk.sh

# Host test entry point (requires development dependencies)
./runtests.sh
```

Windows 用户可查看 `mkapk.ps1`。**如果缺少仓库未提供的预编译依赖，构建会失败；不要使用不明来源的 APK、运行时组件或签名密钥代替。** Gradle 和独立打包脚本的默认版本参数也可能与某次内部验收包不同，应以实际产物为准。

### 项目结构

| 目录 | 内容 |
| --- | --- |
| `src/dev/wind/agent/` | Agent、模型适配、UI、权限、记忆、浏览器及手机工具 |
| `phone-bridge/src/` | 手机与虚拟显示器相关的 Bridge 源码 |
| `runtime-companion/src/` | 可选运行时组件的源码 |
| `native/` | 项目本地 native 源码 |
| `res/`、`assets/` | Android 资源、WebView 脚本及公共文字资源（非完整二进制发布资产） |
| `tools/` | 源码级构建、验证、辅助脚本 |
| `tests/` | 保留的源码测试，不含内部验收日志 |

### 当前成熟度与限制

HarnessMate 已开展 Code/Mate、长期记忆、后台任务、浏览器与手机控制等多阶段开发和分层测试。**开发验收、模拟器通过以及部分设备反馈，不意味着已经通过任意 OEM、微信/QQ 账号、网站交易或长时间后台运行的全面验证。**

用户应在测试设备上逐项启用功能、检查授权，并对发消息、修改文件、安装软件、发布内容及交易类操作自行复核。本项目对使用第三方网站、模型和系统功能时的具体可用性不作保证。

### 许可证与致谢

本仓库采用 [GNU AGPL-3.0](LICENSE)。项目源自 [Vega Agent](https://github.com/Vegxz/Vega-Agent) 的早期代码基础，并持续进行独立开发。保留原项目与第三方组件应有的版权、许可证及归属说明。第三方资料见 [assets/THIRD-PARTY-NOTICES.txt](assets/THIRD-PARTY-NOTICES.txt)。

报告漏洞请参阅 [SECURITY.md](SECURITY.md)，避免在公开 Issue 中粘贴真实账号、Token、验证码或敏感日志。

---

<a id="english"></a>

## English

**HarnessMate** is an Android-first AI agent designed to help users accomplish real tasks, not just chat. It combines LLM reasoning with user-authorized device actions, a WebView browser agent, filesystem tools, mobile development workflows, long-term memory, and Skills.

**Two task profiles:** **Mate** focuses on everyday tasks and cross-tool workflows; **Code** focuses on software and project operations. These profiles do **not** grant extra permissions. Filesystem, screen, Accessibility, ADB, browser, and other capabilities remain independently controlled.

**What is implemented:** Android automation interfaces, embedded Local ADB, an experimental virtual secondary display, structured browser interactions with manual takeover, a text-chat guardian workflow, scoped long-term memory, session backups, on-demand Skills, and multiple model connection options. Features vary by model, device, Android version, and granted permissions.

**Important limits:** This is an **independent source-only public snapshot**, not a mirror or a ready-to-install release. It excludes the private repository's commit history, internal development evidence, signing material, and large prebuilt toolchains/binary dependencies. You will need to obtain appropriate dependencies to build a complete APK. Android 6.0 / API 23 is the project's declared minimum, but some features require newer system capabilities. Real-world account, payment, posting, and long-running automation success is not universally verified.

### Build from source

```bash
git clone https://github.com/windfile1-del/HarnessMate.git
cd HarnessMate
# Restore permitted SDK / compiler / native dependencies before attempting builds
./mkapk.sh
```

Please review [SECURITY.md](SECURITY.md), preserve backups, and explicitly approve high-impact operations. Cloud model usage may transmit selected task content to the configured provider. Experimental web-provider sessions do not automatically expose native tools.

**License:** [AGPL-3.0](LICENSE). **Original project acknowledgement:** [Vega Agent](https://github.com/Vegxz/Vega-Agent). See [third-party notices](assets/THIRD-PARTY-NOTICES.txt).

---

<div align="center">

**HarnessMate — An AI companion that can act, with you in control.**

</div>
