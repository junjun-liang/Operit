# Tools 模块设计思想与详细使用指南

## 目录

1. [模块概述](#1-模块概述)
2. [核心设计思想](#2-核心设计思想)
3. [架构总览](#3-架构总览)
4. [顶层核心类详解](#4-顶层核心类详解)
5. [权限分层体系（defaultTool + system）](#5-权限分层体系defaulttool--system)
6. [工具包系统（packTool + javascript）](#6-工具包系统packtool--javascript)
7. [MCP 协议集成（mcp）](#7-mcp-协议集成mcp)
8. [UI 自动化代理（agent）](#8-ui-自动化代理agent)
9. [其他子模块](#9-其他子模块)
10. [关键流程](#10-关键流程)
11. [使用示例](#11-使用示例)
12. [最佳实践](#12-最佳实践)

---

## 1. 模块概述

`tools` 模块是 Operit 项目的**工具执行核心**，负责 AI 工具的注册、调度、执行和结果处理。该模块涵盖了从 Shell 命令执行、UI 自动化、文件操作到 HTTP 请求等数十种内置工具，同时支持通过 JavaScript 脚本和 MCP 协议动态扩展工具能力。

模块位于 `com.ai.assistance.operit.core.tools` 包下，包含 8 个子模块：

| 子模块 | 职责 |
|--------|------|
| `agent` | UI 自动化代理（PhoneAgent），驱动手机操作的 AI Agent |
| `calculator` | 数学表达式计算器 |
| `climode` | CLI 工具模式支持（搜索/代理隐藏工具） |
| `condition` | 条件表达式评估器 |
| `defaultTool` | 内置工具实现（按权限分层） |
| `javascript` | JavaScript 引擎与工具包执行 |
| `mcp` | MCP（Model Context Protocol）协议集成 |
| `packTool` | 工具包解析、加载与生命周期管理 |
| `skill` | 技能包管理 |
| `system` | 系统级操作（Shell 执行、UI 操作、截屏等） |

---

## 2. 核心设计思想

### 2.1 统一工具接口（ToolExecutor）

所有工具——无论是内置 Kotlin 实现、JS 脚本还是 MCP 远程工具——都通过 `ToolExecutor` 接口统一执行：

```kotlin
interface ToolExecutor {
    fun invoke(tool: AITool): ToolResult
    fun invokeAndStream(tool: AITool): Flow<ToolResult> = flowOf(invoke(tool))
    fun validateParameters(tool: AITool): ToolValidationResult
}
```

**设计优势**：
- 调用方无需关心工具的实现语言或运行位置
- 支持同步执行（`invoke`）和流式执行（`invokeAndStream`）
- 参数验证与执行解耦

### 2.2 权限分层架构（5 级权限模型）

工具实现根据 Android 权限级别分层，形成递增的继承链：

```
Standard → Accessibility → Debugger → Admin → Root
（低权限）                                      （高权限）
```

- `ToolGetter` 根据用户首选权限级别分发对应实现
- 高权限层继承低权限层，选择性覆盖方法
- Shell 执行和 UI 操作各自拥有独立的工厂和策略链

### 2.3 工具包动态扩展（ToolPackage + JS 引擎）

工具不仅限于内置实现，还支持通过以下方式动态扩展：

- **JS 工具包**（`.js` 文件）：从注释头部提取元数据，通过 QuickJS 引擎执行
- **ToolPkg 归档**（`.toolpkg` 文件）：ZIP 格式的容器包，包含多个子包、UI 模块、Hook 等
- **MCP 服务器**：通过 MCP 协议连接远程工具服务器

所有扩展格式最终都适配为统一的 `ToolPackage` 数据模型。

### 2.4 包生命周期三层模型

```
Available（可用）→ Enabled（已启用）→ Active（已激活）
```

- **Available**：扫描发现的包，元数据已加载
- **Enabled**：用户启用，持久化到 SharedPreferences
- **Active**：在当前 AI 会话中加载并注册了工具到 AIToolHandler

### 2.5 Hook 观察者模式（AIToolHook）

工具执行的生命周期事件通过 `AIToolHook` 接口通知：

```kotlin
interface AIToolHook {
    fun onToolCallRequested(tool: AITool)          // 工具调用请求
    fun onToolPermissionChecked(tool: AITool, granted: Boolean, reason: String?)  // 权限检查
    fun onToolExecutionStarted(tool: AITool)       // 执行开始
    fun onToolExecutionResult(tool: AITool, result: ToolResult)  // 执行结果
    fun onToolExecutionError(tool: AITool, throwable: Throwable) // 执行异常
    fun onToolExecutionFinished(tool: AITool)      // 执行完成
}
```

Hook 是**通知专用**的，不拦截或阻塞执行，用于 UI 状态更新和日志记录。

### 2.6 结构化结果数据（ToolResultData 体系）

所有工具结果使用密封类 `ToolResultData` 的子类表示，提供结构化数据和人类可读的 `toString()`：

- `StringResultData` / `BooleanResultData` / `IntResultData` — 基础类型
- `DirectoryListingData` / `FileContentData` / `FileInfoData` — 文件操作
- `HttpResponseData` / `HttpStreamEventData` — HTTP 请求
- `UIPageResultData` / `UIActionResultData` — UI 自动化
- `DeviceInfoResultData` / `LocationData` / `NotificationData` — 设备信息
- `TerminalCommandResultData` / `TerminalStreamEventData` — 终端命令
- `GrepResultData` / `FindFilesResultData` — 代码搜索
- `WorkflowResultData` / `MemoryQueryResultData` — 工作流/记忆
- 等 40+ 种结果类型

---

## 3. 架构总览

```
┌─────────────────────────────────────────────────────────────────────┐
│                        AIToolHandler（核心调度器）                     │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │  availableTools: ConcurrentHashMap<String, ToolExecutor>      │  │
│  │  toolHooks: CopyOnWriteArrayList<AIToolHook>                  │  │
│  │  toolPermissionSystem: ToolPermissionSystem                   │  │
│  │  packageManager: PackageManager                                │  │
│  └───────────────────────────────────────────────────────────────┘  │
│         │                    │                    │                   │
│    ┌────▼────┐        ┌─────▼─────┐       ┌─────▼─────┐            │
│    │ 内置工具  │        │ JS 工具包  │       │ MCP 工具   │            │
│    │ (defaultTool)│    │ (packTool) │       │ (mcp)     │            │
│    └────┬────┘        └─────┬─────┘       └─────┬─────┘            │
│         │                    │                    │                   │
│    ┌────▼────────────────────▼────────────────────▼────┐            │
│    │              ToolExecutor 统一接口                   │            │
│    └───────────────────────────────────────────────────┘            │
│                                                                     │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                    system（系统级操作）                        │   │
│  │  ShellExecutorFactory ──► ShellExecutor (5级)                │   │
│  │  ActionListenerFactory ──► ActionListener (5级)              │   │
│  │  MediaProjectionCaptureManager                               │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                                                                     │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                    agent（UI 自动化代理）                      │   │
│  │  PhoneAgent ──► ActionHandler ──► ShowerController           │   │
│  │  PhoneAgentJobRegistry  │  VirtualDisplayManager             │   │
│  │  ShowerServerManager    │  ShowerVideoRenderer               │   │
│  └──────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 4. 顶层核心类详解

### 4.1 AIToolHandler（工具调度器）

**定位**：单例，所有工具的注册和执行入口。

**核心职责**：
- 工具注册（`registerTool`）
- 工具执行（`executeTool` / `executeToolAndStream`）
- 工具查找与自动激活（`getToolExecutorOrActivate`）
- Hook 通知（6 个生命周期事件）
- 权限系统集成（`ToolPermissionSystem`）
- 包管理器集成（`PackageManager`）

**工具自动激活机制**：
当 AI 调用的工具名包含 `:`（如 `packageName:toolName`）时，`getToolExecutorOrActivate` 会：
1. 确保默认工具已注册
2. 自动激活对应的包（`packageManager.usePackage()`）
3. 对 MCP 服务，检查是否活跃，不活跃则自动重连

### 4.2 ToolRegistration（工具注册表）

**定位**：`registerAllTools()` 函数集中注册所有内置工具。

**注册的工具类别**：

| 类别 | 工具名示例 | 说明 |
|------|-----------|------|
| Shell 执行 | `execute_shell` | 执行 Shell 命令 |
| 终端管理 | `create_terminal_session`, `execute_in_terminal_session`, `close_terminal_session` | 交互式终端 |
| 环境变量 | `read_environment_variable`, `write_environment_variable` | 环境变量读写 |
| 沙箱管理 | `list_sandbox_packages`, `set_sandbox_package_enabled`, `execute_sandbox_script_direct` | 工具包管理 |
| MCP 管理 | `restart_mcp_with_logs` | MCP 服务重启 |
| 语音配置 | `get_speech_services_config`, `set_speech_services_config`, `test_tts_playback` | TTS/STT 配置 |
| 模型配置 | `list_model_configs`, `create_model_config`, `update_model_config`, `delete_model_config` | LLM 配置管理 |
| 功能绑定 | `list_function_model_configs`, `get_function_model_config`, `set_function_model_config` | 功能-模型映射 |
| 记忆库 | `query_memory`, `create_memory`, `update_memory`, `delete_memory`, `link_memories` | 记忆 CRUD + 链接 |
| 用户偏好 | `update_user_preferences` | 用户偏好更新 |
| 包管理 | `use_package` | 激活工具包 |
| CLI 模式 | `search_hidden_tools`, `proxy_hidden_tool` | 隐藏工具搜索与代理 |
| 包代理 | `package_proxy` | 包工具代理调用 |
| 计算 | `calculate` | 数学表达式计算 |
| Web 访问 | `visit_web` | 网页访问 |
| 浏览器 | `browser_navigate`, `browser_click`, `browser_snapshot`, `browser_take_screenshot` 等 20+ 个 | Playwright 风格浏览器操作 |
| 休眠 | `sleep` | 延时等待 |
| Intent | `execute_intent`, `send_broadcast` | Android Intent 执行 |
| 设备信息 | `device_info` | 设备硬件/系统信息 |
| Tasker | `trigger_tasker_event` | Tasker 事件触发 |
| 工作流 | `get_all_workflows`, `create_workflow`, `trigger_workflow` 等 | 工作流 CRUD + 执行 |
| 对话管理 | `start_chat_service`, `create_new_chat`, `send_message_to_ai`, `list_chats` 等 | 多对话管理 |
| 文件系统 | `list_files`, `read_file`, `write_file`, `delete_file`, `find_files`, `grep_code` 等 15+ 个 | 文件操作全套 |
| UI 自动化 | `click_element`, `tap`, `swipe`, `press_key`, `capture_screenshot`, `run_ui_subagent` | UI 操作 |
| HTTP | `http_request`, `multipart_request`, `manage_cookies` | HTTP 请求 |
| 系统操作 | `toast`, `send_notification`, `modify_system_setting`, `install_app`, `start_app` 等 | 系统级操作 |
| FFmpeg | `ffmpeg_execute`, `ffmpeg_info`, `ffmpeg_convert` | 音视频处理 |

### 4.3 ToolPackage（工具包数据模型）

**定位**：工具包的统一数据模型，支持 JS 包、ToolPkg 和 MCP 包。

```kotlin
data class ToolPackage(
    val name: String,
    val description: LocalizedText,     // 多语言描述
    val tools: List<PackageTool>,       // 包含的工具列表
    val states: List<ToolPackageState>, // 条件状态变体
    val env: List<EnvVar>,              // 环境变量声明
    val isBuiltIn: Boolean,             // 是否内置
    val enabledByDefault: Boolean,      // 默认启用
    val displayName: LocalizedText,     // 显示名称
    val category: String,               // 分类
    val author: List<String>            // 作者
)
```

**LocalizedText**：支持多语言的文本，按语言标签优先级解析（精确匹配 > 语言前缀 > default > en > zh）。

**EnvVar**：环境变量声明，支持 required/optional 标记和默认值，PackageManager 在激活包前验证必需变量。

### 4.4 ToolResultData（结构化结果体系）

密封类层次结构，40+ 种结果类型，每种都提供：
- 结构化字段（便于程序处理）
- `toString()` 人类可读输出（用于 AI 理解）
- `toJson()` 序列化支持

### 4.5 ToolProgressBus（进度总线）

全局单例，用于工具执行进度的实时通知：

```kotlin
object ToolProgressBus {
    val progress: StateFlow<ToolProgressEvent?>
    fun update(toolName: String, progress: Float, message: String = "")
    fun clear()
}
```

**优先级机制**：不同工具有不同的进度显示优先级（如 `__SUMMARY__` 优先级 1000，`grep_context` 优先级 100）。

### 4.6 ToolExecutionLimits（执行限制）

```kotlin
object ToolExecutionLimits {
    const val MAX_FILE_READ_BYTES = 32_000
    const val DEFAULT_FILE_READ_PART_LINES = 200
    const val MAX_TEXT_RESULT_LENGTH = 5_000
    const val MAX_FINAL_TOOL_RESULT_MESSAGE_CHARS = 64_000
}
```

---

## 5. 权限分层体系（defaultTool + system）

### 5.1 五级权限模型

| 级别 | 枚举值 | 所需条件 | Shell 能力 | UI 操作能力 |
|------|--------|---------|-----------|------------|
| 标准 | `STANDARD` | 无 | `Runtime.exec()`（应用沙箱） | 仅截图（MediaProjection） |
| 无障碍 | `ACCESSIBILITY` | 开启无障碍服务 | 同 STANDARD | 无障碍 API（点击/滑动/输入/获取页面） |
| 调试 | `DEBUGGER` | ADB/Shizuku 连接 | ADB shell 命令 | `input` 命令 + `uiautomator`（智能回退无障碍） |
| 管理员 | `ADMIN` | 设备管理员激活 | 同 DEBUGGER | 同 DEBUGGER |
| Root | `ROOT` | 设备已 Root | `su` 完整 root 权限 | root shell `input` 命令（不回退无障碍） |

### 5.2 ToolGetter（工具分发器）

根据用户首选权限级别分发对应的工具实现：

```kotlin
ToolGetter.getFileSystemTools(context)  // 按权限分发
ToolGetter.getUITools(context)          // 按权限分发
ToolGetter.getSystemOperationTools(context)  // 按权限分发
ToolGetter.getDeviceInfoToolExecutor(context) // 按权限分发
ToolGetter.getHttpTools(context)        // 仅标准版
ToolGetter.getWebVisitTool(context)     // 仅标准版
```

**按权限分发的工具**遵循继承链：

```
Standard* ← Accessibility* ← Debugger* ← Admin* ← Root*
```

### 5.3 Shell 执行体系

```
ShellExecutorFactory
  ├── StandardShellExecutor    → Runtime.exec()，30s 超时
  ├── AccessibilityShellExecutor → 无法执行 Shell（预留扩展点）
  ├── DebuggerShellExecutor    → Shizuku IShizukuService.newProcess()
  ├── AdminShellExecutor       → 仅 lockscreen/wipe
  └── RootShellExecutor        → libsu Shell.cmd() / su -c
```

**自动降级**：`getHighestAvailableExecutor()` 按 ROOT → ADMIN → DEBUGGER → ACCESSIBILITY → STANDARD 顺序查找第一个可用且已授权的执行器。

### 5.4 UI 操作体系

```
ActionListenerFactory
  ├── StandardActionListener       → 仅应用内事件
  ├── AccessibilityActionListener  → 无障碍服务（系统级 UI 事件）
  ├── DebuggerActionListener       → Shizuku 级别
  ├── AdminActionListener          → 设备管理员级别
  └── RootActionListener           → getevent 内核输入事件
```

**ActionManager**：单例管理器，封装 `ActionListenerFactory`，提供自动选择最高权限、多回调广播、状态变化通知。

### 5.5 各层实现差异要点

| 层 | FileSystemTools | UITools | SystemOperationTools |
|----|----------------|---------|---------------------|
| Standard | Java File API + SAF | 仅截图 + PhoneAgent | Android SDK API |
| Accessibility | 同 Standard | **核心增强**：无障碍 API 实现 UI 操作 | 同 Standard |
| Debugger | **核心增强**：ADB shell 命令替代 Java API | **核心增强**：`input` 命令 + 智能回退无障碍 | **核心增强**：`settings`/`pm`/`am` shell 命令 |
| Admin | 同 Debugger | 同 Debugger | 同 Debugger |
| Root | 同 Debugger | **核心增强**：root shell 身份，不回退无障碍 | 同 Debugger |

**智能回退**（DebuggerUITools）：当无障碍服务可用时优先使用无障碍 API（更精准），不可用时回退到 shell 命令。

**Operit 内部路径保护**（DebuggerFileSystemTools）：对 Operit 自身数据目录始终使用 Java API 而非 shell 命令，确保安全性。

---

## 6. 工具包系统（packTool + javascript）

### 6.1 PackageManager（包管理器）

**定位**：单例，管理工具包的完整生命周期。

**三层状态模型**：

```
Available（可用）──enablePackage()──► Enabled（已启用）──usePackage()──► Active（已激活）
     ▲                                  │                              │
     │          disablePackage()         │     unregisterPackageTools() │
     └──────────────────────────────────┘◄─────────────────────────────┘
```

**包激活流程（`usePackage`）**：
1. 验证环境变量（必需变量是否已设置）
2. 选择包状态（`selectToolPackageState`，根据条件评估选择状态变体）
3. 注册工具到 AIToolHandler（`packageName:toolName` 格式）
4. 生成系统提示词

**包扫描**：
- `scanAssetPackages()`：扫描 `assets/packages/` 目录
- `scanExternalPackages()`：扫描外部存储 `packages/` 目录，带缓存签名机制

**支持的文件格式**：
- `.js` 文件：从 `/* METADATA ... */` 注释头部提取元数据
- `.hjson` 文件：HJSON 格式的包定义
- `.toolpkg` 文件：ZIP 归档格式，包含清单、脚本和资源

### 6.2 ToolPkg 归档格式

`.toolpkg` 文件本质是 ZIP 归档，结构如下：

```
manifest.hjson (或 manifest.json)  — 必需，包清单
main.js                           — 必需，由 manifest.main 指定
subpackage_a.js                    — 可选，子包入口脚本
screens/                           — 可选，UI 屏幕定义
resources/                         — 可选，资源文件
```

**清单文件（manifest.hjson）**：

```hjson
{
    toolpkg_id: "com.example.my-tools"
    version: "1.0.0"
    main: "main.js"
    subpackages: [
        {
            name: "weather"
            script: "weather.js"
        }
    ]
    resources: ["icons/"]
}
```

### 6.3 ToolPkgParser（归档解析器）

**解析流程**：

```
1. readZipEntries() → 读取 ZIP 所有条目到 Map<String, ByteArray>
2. findManifestEntry() → 查找清单文件（优先 manifest.hjson > manifest.json）
3. parseToolPkgManifest() → 解析清单
4. 读取 main 入口脚本
5. 解析子包 JS 脚本
6. ToolPkgMainRegistrationScriptParser.parse() → 执行 registerToolPkg() 函数
7. 构建 ToolPkgContainerRuntime（UI 模块、路由、Hook、AI Provider 等）
```

### 6.4 JavaScript 引擎体系

**JsEngine**：基于 QuickJS 的 JS 执行引擎，提供：
- 脚本执行（`evaluate`）
- 工具包主注册函数执行（`executeToolPkgMainRegistrationFunction`）
- Java 桥接（`JsJavaBridge`）
- Compose DSL 渲染支持（`JsComposeDslBridge`）
- 超时控制（`JsTimeoutConfig`）

**JsToolManager**：JS 引擎池管理器，负责：
- 引擎实例的创建和复用
- 工具脚本的执行（`executeScript`）
- 流式结果收集

**JsJavaBridge**：Java/JS 桥接机制，允许 JS 脚本调用 Android 原生功能：
- 文件操作
- HTTP 请求
- UI 操作
- 系统功能

### 6.5 容器-子包架构

一个 `.toolpkg` 容器可包含多个子包：

- **容器（Container）**：包含主注册脚本、UI 模块、路由、导航条目、桌面小部件、各种 Hook、AI Provider
- **子包（Subpackage）**：独立的 `ToolPackage`，拥有自己的工具集

**注册项类型**：

| 类型 | 说明 |
|------|------|
| UI 模块 | 工具箱中的 UI 界面 |
| UI 路由 | 可导航的页面路由 |
| 导航条目 | 侧边栏/底部导航的入口 |
| 桌面小部件 | 桌面 Widget |
| 应用生命周期 Hook | 应用启动/退出等事件 |
| 函数 Hook | 消息处理、工具生命周期、提示词各阶段 |
| XML 渲染插件 | 自定义 XML 标签渲染 |
| AI Provider | 自定义 LLM 提供商 |

---

## 7. MCP 协议集成（mcp）

### 7.1 架构概览

MCP（Model Context Protocol）模块将远程 MCP 服务器适配为与现有 `ToolPackage` 兼容的格式：

```
MCPServerConfig → MCPPackage.loadFromServer() → MCPBridgeClient → getTools()
    → 解析 inputSchema → List<MCPTool> → toToolPackage() → ToolPackage
```

### 7.2 核心类

| 类名 | 职责 |
|------|------|
| `MCPServerConfig` | 服务器配置（名称、端点、描述、能力） |
| `MCPTool` | 工具定义（名称、描述、参数列表） |
| `MCPToolParameter` | 参数定义 + 智能类型转换 |
| `MCPPackage` | 将 MCP 服务器封装为 ToolPackage |
| `MCPToolExecutor` | 执行 MCP 工具调用 |
| `MCPManager` | 客户端生命周期管理（单例） |

### 7.3 工具执行流程

```
AITool(name="server:tool") → MCPToolExecutor.invoke()
    → 解析 serverName/toolName
    → MCPManager.getOrCreateClient(serverName)
    → isActive() 检查
    → convertParameterTypes() 智能类型转换
    → callToolSync() 同步调用
    → extractContentFromResult() 解析 content 数组（text/image/resource）
    → 返回 ToolResult
```

### 7.4 智能参数类型转换

`MCPToolParameter.smartConvert()` 自动将字符串参数转换为目标类型：

- `number`/`integer`/`float` → 数值类型
- `boolean` → 布尔类型
- `array` → JSON 数组（支持非标准格式）
- `object` → JSON 对象
- `null` 类型时自动猜测（检测 JSON 格式、数字、布尔值）

---

## 8. UI 自动化代理（agent）

### 8.1 PhoneAgent（手机操作代理）

**定位**：驱动手机 UI 操作的 AI Agent，执行"截图 → AI 推理 → 动作执行"的循环。

**核心循环（`run()`）**：
1. 注册 Job 到 `PhoneAgentJobRegistry`
2. 确保虚拟屏幕（`ensureRequiredVirtualScreenOrError`）
3. 预热 Shower（`prewarmShowerIfNeeded`）
4. 循环执行步骤直到完成或达到最大步数：
   - 截图
   - 构造消息（包含截图和任务描述）
   - 调用 AI 服务推理
   - 解析 AI 输出为动作
   - 执行动作

**支持的动作类型**：

| 动作 | 说明 |
|------|------|
| `Launch` | 启动应用 |
| `Tap` | 点击坐标 |
| `Type` | 输入文本 |
| `Swipe` | 滑动手势 |
| `Back` | 返回键 |
| `Home` | 主页键 |
| `Wait` | 等待 |
| `Take_over` | 接管控制 |

**双路径执行**：根据是否拥有 Shower 虚拟显示，选择 Shower 输入路径或传统 AITool 输入路径。

### 8.2 Shower 体系

| 组件 | 职责 |
|------|------|
| `ShowerServerManager` | Shower 服务器进程的启动和停止（委托 `CoreShowerServerManager`） |
| `ShowerController` | 输入/显示控制器（外观模式封装 `ClientShowerController`），按 agentId 管理多实例 |
| `ShowerVideoRenderer` | 视频帧渲染 |
| `ShowerBinderRegistry` | Binder 接收器注册表 |
| `VirtualDisplayManager` | 基于 Android 原生 API 的虚拟显示管理（备选方案） |

### 8.3 PhoneAgentJobRegistry（Job 管理器）

集中管理所有 Agent 的协程 Job：
- `register(agentId, job)`：注册 Job，完成时自动注销
- `cancelAgent(agentId)`：取消指定 Agent 的所有 Job
- `cancelAll()`：取消所有 Agent 的所有 Job

---

## 9. 其他子模块

### 9.1 calculator（计算器）

| 类名 | 职责 |
|------|------|
| `Calculator` | 计算器接口 |
| `ExpressionParser` | 表达式词法分析 |
| `ExpressionNode` | AST 节点 |
| `ExpressionContext` | 表达式求值上下文（变量支持） |
| `JsCalculator` | 基于 JS 引擎的计算器实现 |

### 9.2 climode（CLI 工具模式）

`CliToolModeSupport`：支持 CLI 模式下的隐藏工具搜索和代理调用：
- `search_hidden_tools`：搜索隐藏工具目录
- `proxy_hidden_tool`：代理调用隐藏工具（带权限检查和角色卡访问控制）

### 9.3 condition（条件评估）

`ConditionEvaluator`：评估工具包状态的条件表达式（如权限等级、Shizuku 可用性等）。

### 9.4 skill（技能包）

| 类名 | 职责 |
|------|------|
| `SkillManager` | 技能包管理器 |
| `SkillPackage` | 技能包数据模型 |

### 9.5 websession（浏览器会话）

完整的浏览器自动化支持，包括：
- **Browser 工具**：20+ 个 Playwright 风格操作（navigate、click、snapshot、screenshot 等）
- **Userscript 支持**：用户脚本管理（安装、运行、存储、匹配）
- **下载支持**：浏览器文件下载

---

## 10. 关键流程

### 10.1 工具执行完整流程

```
AI 输出包含工具调用
    │
    ▼
AIToolHandler.executeTool(tool)
    │
    ├─ notifyToolCallRequested(tool)
    │
    ├─ getToolExecutorOrActivate(tool.name)
    │   ├─ 查找已注册的 executor
    │   ├─ 未找到 → registerDefaultTools() → 重试
    │   ├─ 包名:工具名格式 → packageManager.usePackage() → 重试
    │   └─ MCP 服务不活跃 → 自动重连 → 重试
    │
    ├─ executor.validateParameters(tool)
    │   └─ 验证失败 → 返回错误结果
    │
    ├─ notifyToolExecutionStarted(tool)
    │
    ├─ executor.invoke(tool) 或 executor.invokeAndStream(tool)
    │   │
    │   ├─ 内置工具 → ToolGetter 分发到对应权限级别实现
    │   ├─ JS 工具包 → PackageToolExecutor → JsToolManager.executeScript()
    │   └─ MCP 工具 → MCPToolExecutor → MCPBridgeClient.callToolSync()
    │
    ├─ notifyToolExecutionResult(tool, result)
    │
    └─ notifyToolExecutionFinished(tool)
```

### 10.2 工具包激活流程

```
usePackage(packageName)
    │
    ├─ 验证环境变量（必需变量是否已设置）
    │
    ├─ selectToolPackageState()
    │   └─ 根据 ConditionEvaluator 评估条件选择状态变体
    │
    ├─ registerPackageTools()
    │   ├─ 遍历包中所有工具
    │   ├─ 以 "packageName:toolName" 格式注册到 AIToolHandler
    │   └─ 注册描述生成器
    │
    └─ 生成系统提示词（工具描述 + 参数说明）
```

### 10.3 PhoneAgent 执行流程

```
PhoneAgent.run(intent, config)
    │
    ├─ PhoneAgentJobRegistry.register(agentId, job)
    │
    ├─ ensureRequiredVirtualScreenOrError()
    │   ├─ ShowerServerManager.ensureServerStarted()
    │   ├─ ShowerController.ensureDisplay(agentId, ...)
    │   └─ VirtualDisplayOverlay.show(displayId)
    │
    ├─ prewarmShowerIfNeeded()
    │
    └─ 循环 _executeStep() 直到完成或 maxSteps:
        ├─ captureScreenshotForAgent()
        │   ├─ ShowerController.requestScreenshot(agentId)
        │   └─ 或 ToolImplementations.captureScreenshot()
        │
        ├─ 构造消息（截图 + 任务描述 + 历史步骤）
        │
        ├─ AIService.sendMessage()（AI 推理）
        │
        ├─ parseAgentAction()（解析 AI 输出）
        │   ├─ do(action=..., ...) → 继续执行
        │   └─ finish(message=...) → 循环结束
        │
        └─ ActionHandler.executeAgentAction(parsed)
            ├─ Shower 路径 → ShowerController.tap/swipe/key/...
            └─ 传统路径 → ToolImplementations.tap/swipe/pressKey/...
```

---

## 11. 使用示例

### 11.1 注册自定义工具

```kotlin
val handler = AIToolHandler.getInstance(context)

// 简单工具
handler.registerTool(
    name = "my_tool",
    descriptionGenerator = { tool ->
        val param = tool.parameters.find { it.name == "input" }?.value ?: ""
        "My tool: $param"
    },
    executor = { tool ->
        val input = tool.parameters.find { it.name == "input" }?.value ?: ""
        ToolResult(
            toolName = tool.name,
            success = true,
            result = StringResultData("Processed: $input")
        )
    }
)

// 流式工具
handler.registerTool(
    name = "my_streaming_tool",
    executor = object : ToolExecutor {
        override fun invoke(tool: AITool): ToolResult {
            return ToolResult(toolName = tool.name, success = true, result = StringResultData("done"))
        }
        override fun invokeAndStream(tool: AITool): Flow<ToolResult> = flow {
            repeat(10) { i ->
                emit(ToolResult(toolName = tool.name, success = true, result = StringResultData("chunk $i")))
            }
        }
    }
)
```

### 11.2 执行工具

```kotlin
val tool = AITool(
    name = "read_file",
    parameters = listOf(ToolParameter(name = "path", value = "/sdcard/test.txt"))
)

// 同步执行
val result = handler.executeTool(tool)
if (result.success) {
    println(result.result)
}

// 流式执行
handler.executeToolAndStream(tool).collect { partialResult ->
    updateUI(partialResult)
}
```

### 11.3 注册 Hook 观察工具执行

```kotlin
handler.addToolHook(object : AIToolHook {
    override fun onToolCallRequested(tool: AITool) {
        Log.d("Hook", "Tool requested: ${tool.name}")
    }
    override fun onToolExecutionResult(tool: AITool, result: ToolResult) {
        Log.d("Hook", "Tool result: ${tool.name} success=${result.success}")
    }
    override fun onToolExecutionError(tool: AITool, throwable: Throwable) {
        Log.e("Hook", "Tool error: ${tool.name}", throwable)
    }
})
```

### 11.4 使用 PackageManager 管理工具包

```kotlin
val packageManager = handler.getOrCreatePackageManager()

// 获取所有可用包
val available = packageManager.getAvailablePackages()

// 启用包
packageManager.enablePackage("weather_tools")

// 激活包（注册工具到 AI 会话）
packageManager.usePackage("weather_tools")

// 禁用包
packageManager.disablePackage("weather_tools")
```

### 11.5 使用 MCP 工具

```kotlin
val mcpManager = MCPManager.getInstance(context)

// 注册 MCP 服务器
mcpManager.registerServer(
    "my_mcp_server",
    MCPServerConfig(
        name = "my_mcp_server",
        endpoint = "http://localhost:3000/mcp",
        description = "My MCP Server",
        capabilities = listOf("tools"),
        extraData = emptyMap()
    )
)

// 发现工具
val mcpPackage = MCPPackage.loadFromServer(context, serverConfig)
if (mcpPackage is MCPPackage.LoadResult.Success) {
    val toolPackage = mcpPackage.mcpPackage.toToolPackage()
    // 注册到 PackageManager
}
```

### 11.6 使用 PhoneAgent 执行 UI 自动化

```kotlin
val agent = PhoneAgent(
    context = context,
    aiService = aiService,
    toolImplementations = object : ToolImplementations {
        override suspend fun tap(x: Int, y: Int) = uiTools.tap(buildTapTool(x, y))
        override suspend fun swipe(sx: Int, sy: Int, ex: Int, ey: Int) = uiTools.swipe(buildSwipeTool(sx, sy, ex, ey))
        override suspend fun pressKey(keyCode: Int) = uiTools.pressKey(buildKeyTool(keyCode))
        override suspend fun captureScreenshot(tool: AITool) = uiTools.captureScreenshot(tool)
    },
    isMainScreenAgent = false,
    onStep = { stepResult ->
        Log.d("Agent", "Step: ${stepResult.action} - ${stepResult.message}")
    }
)

val result = agent.run(
    intent = "打开设置并关闭蓝牙",
    config = AgentConfig(maxSteps = 20)
)
```

---

## 12. 最佳实践

### 12.1 工具注册

- **描述生成器**：始终提供 `descriptionGenerator`，用于权限对话框显示
- **参数验证**：覆盖 `validateParameters()` 进行前置检查，避免无效执行
- **流式支持**：长时间运行的工具实现 `invokeAndStream()` 提供中间结果

### 12.2 权限处理

- **优先使用 ToolGetter**：不要直接创建工具实例，使用 `ToolGetter.getXxxTools(context)` 自动适配权限
- **检查权限可用性**：执行前检查 `isAvailable()` 和 `hasPermission()`
- **优雅降级**：高权限操作失败时，考虑回退到低权限方案

### 12.3 工具包开发

- **环境变量声明**：在 `env` 中声明所有外部依赖，`required` 标记必需变量
- **条件状态**：利用 `states` 根据运行时条件提供不同工具集
- **多语言支持**：使用 `LocalizedText` 提供多语言描述
- **缓存签名**：外部包使用签名机制避免重复解压

### 12.4 错误处理

- **结构化错误**：使用 `ToolResult(success=false, error=message)` 返回错误
- **Hook 通知**：异常时通过 `notifyToolExecutionError` 通知 Hook
- **超时控制**：Shell 执行设置合理超时（StandardShellExecutor 默认 30s）

### 12.5 性能优化

- **工具延迟注册**：默认工具使用 `AtomicBoolean` 防止重复注册
- **包缓存**：ToolPkg 使用缓存目录和签名机制避免重复解压
- **JS 引擎池**：`JsToolManager` 管理引擎实例复用
- **MCP 客户端缓存**：`MCPManager` 缓存客户端连接，避免重复创建

### 12.6 安全注意事项

- **路径验证**：使用 `PathValidator` 验证文件操作路径
- **Operit 内部路径保护**：Debugger 层对内部路径使用 Java API 而非 shell
- **权限系统**：通过 `ToolPermissionSystem` 控制工具执行权限
- **角色卡访问控制**：CLI 模式下通过 `CharacterCardToolAccessResolver` 限制工具访问

---

*文档生成时间：2026-05-16*
*基于 Operit 项目 tools 模块源代码分析*
