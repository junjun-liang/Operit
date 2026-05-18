# Agent 模块设计思想与详细使用指南

## 目录

1. [模块概述](#1-模块概述)
2. [核心设计思想](#2-核心设计思想)
3. [架构总览](#3-架构总览)
4. [核心类详解](#4-核心类详解)
5. [关键流程](#5-关键流程)
6. [使用示例](#6-使用示例)
7. [最佳实践](#7-最佳实践)

---

## 1. 模块概述

`agent` 模块是 Operit 项目的**AI 手机自动化代理**核心，实现了基于视觉语言模型（VLM）的 Android UI 自动化。Agent 通过循环执行"截图 → AI 推理 → 解析动作 → 执行"来完成用户指定的任务。

模块位于 `com.ai.assistance.operit.core.tools.agent` 包下，包含 8 个核心文件：

| 文件 | 核心类 | 职责 |
|------|--------|------|
| `PhoneAgent.kt` | `PhoneAgent`, `ActionHandler`, `ToolImplementations` | Agent 主循环、动作执行 |
| `PhoneAgentJobRegistry.kt` | `PhoneAgentJobRegistry` | Agent 协程作业注册与管理 |
| `ShowerController.kt` | `ShowerController` | 虚拟显示/输入控制（多实例） |
| `ShowerServerManager.kt` | `ShowerServerManager` | Shower 服务器生命周期管理 |
| `ShowerBinderRegistry.kt` | `ShowerBinderRegistry` | AIDL Binder 服务注册表 |
| `ShowerBinderReceiver.kt` | `ShowerBinderReceiver` | Binder 就绪广播接收器 |
| `VirtualDisplayManager.kt` | `VirtualDisplayManager` | Android VirtualDisplay 管理 |
| `ShowerVideoRenderer.kt` | `ShowerVideoRenderer` | H.264 视频流解码渲染 |

**依赖模块**：
- `:showerclient` — Shower 客户端库（AIDL 通信、视频解码）
- `core.tools` — 工具执行层（AIToolHandler、StandardUITools）
- `api.chat.llmprovider` — AI 服务（AIService）
- `services.FloatingChatService` — 浮动窗口服务

---

## 2. 核心设计思想

### 2.1 视觉语言模型驱动的自动化

Agent 不依赖无障碍服务（Accessibility）的节点树，而是通过**截图 + VLM 推理**来理解屏幕内容并决定操作：

```
用户任务: "打开设置并开启蓝牙"
    │
    ├─ 1. 截图当前屏幕 → 发送给 VLM
    ├─ 2. VLM 推理: "需要点击设置图标"
    ├─ 3. 解析动作: do(action="Tap", element="[120, 450]")
    ├─ 4. 执行点击
    ├─ 5. 截图新屏幕 → 发送给 VLM
    ├─ 6. VLM 推理: "需要点击蓝牙开关"
    ├─ 7. 解析动作: do(action="Tap", element="[500, 800]")
    ├─ 8. 执行点击
    └─ 9. VLM 推理: finish(message="蓝牙已开启")
```

### 2.2 双模式运行（主屏幕 vs 虚拟屏幕）

| 模式 | agentId | 截图来源 | 输入方式 | 适用场景 |
|------|---------|---------|---------|---------|
| 主屏幕 | `"default"` / 空 | 主屏幕截图 | 无障碍服务 / ADB | 直接控制用户可见屏幕 |
| 虚拟屏幕 | 自定义 ID | Shower 虚拟显示 | Shower 注入 | 后台自动化、多任务并行 |

- **主屏幕模式**：Agent 直接操作用户当前看到的屏幕，需要无障碍服务权限
- **虚拟屏幕模式**：Agent 在独立的 VirtualDisplay 上运行，不影响用户当前操作，需要 ADB/Root 权限

### 2.3 Shower 子系统（虚拟显示 + 远程输入）

Shower 是一个独立的 Java 进程（`shower-server.jar`），通过 `app_process` 在设备上运行：

```
Operit App                    Shower Server (shower-server.jar)
    │                                │
    ├─ AIDL Binder ────────────────┤
    │   (IShowerService)             │
    │                                │
    ├─ createVirtualDisplay() ─────┤
    │                                ├─ MediaProjection → VirtualDisplay
    │                                │
    ├─ launchApp(packageName) ─────┤
    │                                ├─ am start --display <id>
    │                                │
    ├─ tap(x, y) ──────────────────┤
    │                                ├─ InputManager.injectInputEvent()
    │                                │
    ├─ requestScreenshot() ←───────┤
    │   (WebSocket 或 AIDL)          ├─ ImageReader → PNG
    │                                │
    └─ WebSocket 视频流 ←──────────┤
                                     ├─ MediaCodec → H.264 → WebSocket
```

Shower 需要 **ADB/Root/Debugger** 权限才能注入输入事件。

### 2.4 动作解析协议

AI 响应使用特定的动作格式，Agent 通过正则表达式解析：

```
do(action="Tap", element="[120, 450]")
do(action="Launch", app="设置")
do(action="Type", text="Hello World")
do(action="Swipe", start="[100, 500]", end="[100, 200]")
do(action="Back")
do(action="Home")
do(action="Wait", duration="2")
finish(message="任务完成")
```

解析逻辑支持：
- `do(...)` 动作执行
- `finish(...)` 任务完成
- `<think>...</think><answer>...</answer>` XML 标签格式

### 2.5 上下文历史管理

Agent 维护对话历史，每轮包含：
- `system`：系统提示词
- `user`：当前截图 + 任务描述
- `assistant`：AI 的思考和动作（`<think>` + `<answer>`）

截图以 `<link type="image" id="xxx">` 格式嵌入，执行后从历史中移除以节省 Token。

### 2.6 暂停/恢复机制

Agent 支持通过 `StateFlow<Boolean>` 实现暂停：
- 用户点击"暂停" → `isPausedFlow.value = true`
- Agent 在关键检查点调用 `awaitIfPaused()` → 挂起协程
- 用户点击"恢复" → `isPausedFlow.value = false` → 协程继续

---

## 3. 架构总览

```
┌─────────────────────────────────────────────────────────────────────┐
│                         PhoneAgent                                   │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  Agent Loop (maxSteps, default 20)                            │   │
│  │                                                               │   │
│  │  1. captureScreenshotForAgent()                               │   │
│  │     ├─ Shower 截图 (虚拟屏幕模式)                             │   │
│  │     └─ 无障碍截图 (主屏幕模式)                                │   │
│  │                                                               │   │
│  │  2. buildUserMessage(task + screenshot)                       │   │
│  │                                                               │   │
│  │  3. uiService.sendMessage(chatHistory, stream=true)           │   │
│  │     └─ VLM 推理，流式收集响应                                 │   │
│  │                                                               │   │
│  │  4. parseThinkingAndAction(response)                          │   │
│  │     ├─ 提取 <think> 和 <answer>                               │   │
│  │     └─ 或从 finish()/do() 格式解析                            │   │
│  │                                                               │   │
│  │  5. parseAgentAction(answer) → ParsedAgentAction              │   │
│  │     ├─ metadata: "finish" / "do" / "unknown"                  │   │
│  │     ├─ actionName: "Tap" / "Launch" / "Type" ...              │   │
│  │     └─ fields: Map<String, String>                            │   │
│  │                                                               │   │
│  │  6. actionHandler.executeAgentAction(parsed)                  │   │
│  │     └─ ActionExecResult(success, shouldFinish, message)       │   │
│  │                                                               │   │
│  │  7. removeImagesFromLastUserMessage()                         │   │
│  │                                                               │   │
│  │  8. 更新 UI 进度指示器                                        │   │
│  │                                                               │   │
│  │  9. 检查 finished → 返回结果 / 继续循环                       │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  ActionHandler                                               │   │
│  │  ├─ captureScreenshotForAgent()                              │   │
│  │  ├─ executeAgentAction(parsed)                               │   │
│  │  │   ├─ Launch: 启动应用（支持 Shower 虚拟显示）             │   │
│  │  │   ├─ Tap: 点击坐标                                        │   │
│  │  │   ├─ Type: 输入文本（剪贴板 + 粘贴）                      │   │
│  │  │   ├─ Swipe: 滑动                                          │   │
│  │  │   ├─ Back/Home: 按键                                      │   │
│  │  │   ├─ Wait: 等待                                          │   │
│  │  │   └─ Take_over: 用户接管                                  │   │
│  │  └─ ToolImplementations (接口，由外部提供)                   │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                                                                      │
├──────────────────────────────────────────────────────────────────────┤
│                         Shower 子系统                                │
│                                                                      │
│  ShowerServerManager          ShowerController          ShowerVideoRenderer │
│  ├─ ensureServerStarted()     ├─ ensureDisplay()        ├─ attach()       │
│  └─ stopServer()              ├─ launchApp()            ├─ onFrame()      │
│                               ├─ tap/swipe/key()        └─ captureCurrentFramePng()│
│                               ├─ requestScreenshot()                  │
│                               └─ shutdown()                           │
│                                                                      │
│  ShowerBinderRegistry         ShowerBinderReceiver                    │
│  ├─ setService()              └─ onReceive(ACTION_SHOWER_BINDER_READY)│
│  └─ getService()                                                      │
│                                                                      │
├──────────────────────────────────────────────────────────────────────┤
│                         辅助组件                                     │
│                                                                      │
│  PhoneAgentJobRegistry          VirtualDisplayManager                │
│  ├─ register(agentId, job)      ├─ ensureVirtualDisplay()            │
│  ├─ cancelAgent(agentId)        ├─ captureLatestFrameBitmap()        │
│  └─ cancelAll()                 └─ release()                         │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 4. 核心类详解

### 4.1 PhoneAgent（Agent 主循环）

**核心职责**：管理 Agent 生命周期，执行截图-AI-动作循环。

#### 关键属性

| 属性 | 类型 | 说明 |
|------|------|------|
| `config` | `AgentConfig` | 配置（maxSteps 等） |
| `uiService` | `AIService` | AI 服务（VLM） |
| `actionHandler` | `ActionHandler` | 动作执行器 |
| `agentId` | `String` | Agent 标识（"default" 为主屏幕） |
| `cleanupOnFinish` | `Boolean` | 完成后是否清理资源 |

#### run() — 主入口

```kotlin
suspend fun run(
    task: String,                    // 用户任务描述
    systemPrompt: String,            // 系统提示词
    onStep: (suspend (StepResult) -> Unit)? = null,  // 每步回调
    isPausedFlow: StateFlow<Boolean>? = null,         // 暂停控制
    targetApp: String? = null        // 目标应用（预启动）
): String
```

**执行流程**：
1. 注册 Job 到 `PhoneAgentJobRegistry`
2. `ensureRequiredVirtualScreenOrError()` — 确保虚拟屏幕（虚拟屏幕模式）
3. `prewarmMainScreenShowerIfPossible()` — 预热身主屏幕 Shower
4. `prewarmShowerIfNeeded()` — 预启动目标应用（虚拟屏幕模式）
5. 设置 UI 指示器（Shower 叠加层或全屏进度条）
6. 执行 Agent 循环（最多 `maxSteps` 步）
7. `finally` 块恢复 UI、清理资源

#### _executeStep() — 单步执行

```kotlin
private suspend fun _executeStep(userPrompt: String?, isFirst: Boolean): StepResult
```

1. `actionHandler.captureScreenshotForAgent()` — 截图
2. 构建用户消息（任务 + 截图链接）
3. `uiService.sendMessage(...)` — 发送给 VLM
4. 流式收集响应
5. `parseThinkingAndAction()` — 提取思考和动作
6. `parseAgentAction()` — 解析为结构化动作
7. `actionHandler.executeAgentAction()` — 执行动作
8. `removeImagesFromLastUserMessage()` — 移除历史中的截图

#### 动作解析

**parseThinkingAndAction()**：
- 优先查找 `finish(message=...)` 或 `do(action=...)` 标记
- 回退到 `<think>...</think><answer>...</answer>` XML 格式

**parseAgentAction()**：
- `finish(...)` → `ParsedAgentAction(metadata="finish", ...)`
- `do(action="xxx", ...)` → `ParsedAgentAction(metadata="do", actionName="xxx", fields=...)`
- 其他 → `metadata="unknown"`

### 4.2 ActionHandler（动作执行器）

**核心职责**：将解析后的动作转换为实际的 Android 操作。

#### 支持的动作

| 动作 | 参数 | 说明 |
|------|------|------|
| `Launch` | `app` | 启动应用（支持 Shower 虚拟显示） |
| `Tap` | `element` | 点击坐标 `[x, y]`（相对 1000x1000） |
| `Type` | `text` | 输入文本（剪贴板 + 粘贴） |
| `Swipe` | `start`, `end` | 滑动 |
| `Back` | - | 返回键 |
| `Home` | - | Home 键 |
| `Wait` | `duration` | 等待（秒） |
| `Take_over` | `message` | 用户接管，结束 Agent |

#### 双路径执行

每个动作都有两条执行路径：

1. **Shower 路径**（虚拟屏幕模式，ADB/Root）：
   - `ShowerController.tap()` / `swipe()` / `key()`
   - 直接调用 `InputManager.injectInputEvent()`

2. **无障碍路径**（主屏幕模式）：
   - `toolImplementations.tap()` / `swipe()` / `pressKey()`
   - 通过无障碍服务模拟点击/滑动

#### 截图策略

```
captureScreenshotForAgent()
    │
    ├─ 1. 隐藏 UI 指示器（避免截图中包含进度条）
    │
    ├─ 2. Shower 截图（如果可用）
    │   ├─ ShowerController.requestScreenshot() → PNG bytes
    │   └─ 或 VirtualDisplayOverlay.captureCurrentFramePng()
    │
    ├─ 3. 回退到无障碍截图
    │   └─ toolImplementations.captureScreenshotBitmap()
    │
    ├─ 4. 压缩并注册到 ImagePoolManager
    │   └─ 返回 <link type="image" id="xxx">
    │
    └─ 5. 恢复 UI 指示器
```

#### 坐标解析

`parseRelativePoint("[120, 450]")` 将相对坐标（基于 1000x1000）转换为实际屏幕像素：

```kotlin
val relX = parts[0].toInt()  // 0-1000
val relY = parts[1].toInt()  // 0-1000
val actualX = (relX / 1000.0 * screenWidth).toInt()
val actualY = (relY / 1000.0 * screenHeight).toInt()
```

### 4.3 PhoneAgentJobRegistry（作业注册表）

**核心职责**：管理所有 Agent 协程作业，支持取消。

```kotlin
object PhoneAgentJobRegistry {
    private val jobsByAgentId = ConcurrentHashMap<String, MutableSet<Job>>()

    fun register(agentId: String, job: Job)     // 注册作业
    fun unregister(agentId: String, job: Job)   // 注销作业
    fun cancelAgent(agentId: String, reason)    // 取消指定 Agent
    fun cancelAll(reason)                       // 取消所有 Agent
}
```

**特点**：
- `ConcurrentHashMap` 保证线程安全
- 作业完成时自动注销（`invokeOnCompletion`）
- 支持按 Agent ID 批量取消

### 4.4 ShowerController（虚拟显示控制器）

**核心职责**：管理多个 Shower 实例（按 agentId 隔离），提供虚拟显示和输入注入。

```kotlin
object ShowerController {
    private val instances = ConcurrentHashMap<String, ClientShowerController>()

    fun getInstance(agentId: String): ClientShowerController  // 获取/创建实例
    fun getDisplayId(agentId: String): Int?                   // 获取显示 ID
    suspend fun ensureDisplay(agentId, context, w, h, dpi): Boolean  // 创建虚拟显示
    suspend fun launchApp(agentId, packageName): Boolean      // 启动应用
    suspend fun tap(agentId, x, y): Boolean                   // 点击
    suspend fun swipe(agentId, sx, sy, ex, ey): Boolean       // 滑动
    suspend fun key(agentId, keyCode): Boolean                // 按键
    suspend fun requestScreenshot(agentId): ByteArray?        // 请求截图
    fun shutdown(agentId)                                     // 关闭
}
```

**多实例隔离**：每个 `agentId` 拥有独立的 `ClientShowerController` 实例，支持并发运行多个 Agent。

### 4.5 ShowerServerManager（服务器管理器）

**核心职责**：管理 Shower 服务器进程的生命周期。

```kotlin
object ShowerServerManager {
    suspend fun ensureServerStarted(context: Context): Boolean  // 确保服务器已启动
    suspend fun stopServer(): Boolean                           // 停止服务器
}
```

**启动方式**：
```bash
CLASSPATH=/data/user/<uid>/<pkg>/files/shower-server.jar app_process / com.ai.assistance.shower.Main
```

服务器 JAR 打包在应用 assets 中，运行时复制到 files 目录。

### 4.6 ShowerBinderRegistry & ShowerBinderReceiver（Binder 通信）

**ShowerBinderReceiver**：接收 `ACTION_SHOWER_BINDER_READY` 广播，提取 AIDL Binder 并注册到 `ShowerBinderRegistry`。

```kotlin
class ShowerBinderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SHOWER_BINDER_READY) return
        val container = intent.getParcelableExtra<ShowerBinderContainer>(EXTRA_BINDER_CONTAINER)
        val service = container?.binder?.let { IShowerService.Stub.asInterface(it) }
        ShowerBinderRegistry.setService(service)
    }
}
```

**ShowerBinderRegistry**：全局单例，持有 `IShowerService` 引用。

### 4.7 VirtualDisplayManager（虚拟显示管理器）

**核心职责**：创建和管理 Android `VirtualDisplay`（独立于 Shower 的备用方案）。

```kotlin
class VirtualDisplayManager private constructor(context: Context) {
    fun ensureVirtualDisplay(): Int?              // 创建虚拟显示
    fun captureLatestFrameBitmap(): Bitmap?       // 捕获最新帧
    fun captureLatestFrameToFile(file: File): Boolean  // 捕获到文件
    fun release()                                 // 释放资源
}
```

**创建参数**：
- 尺寸：屏幕宽高（减去状态栏高度）
- 格式：`PixelFormat.RGBA_8888`
- 标志：`VIRTUAL_DISPLAY_FLAG_PUBLIC | VIRTUAL_DISPLAY_FLAG_PRESENTATION`

### 4.8 ShowerVideoRenderer（视频渲染器）

**核心职责**：解码 Shower 服务器通过 WebSocket 发送的 H.264 视频流。

```kotlin
object ShowerVideoRenderer {
    fun attach(surface: Surface, videoWidth: Int, videoHeight: Int)  // 绑定 Surface
    fun detach()                                                       // 解绑
    fun onFrame(data: ByteArray)                                       // 接收视频帧
    suspend fun captureCurrentFramePng(): ByteArray?                   // 捕获当前帧
}
```

**解码假设**：前两个二进制帧为 codec 配置（csd-0 和 csd-1），后续为普通 access unit。

---

## 5. 关键流程

### 5.1 Agent 完整执行流程

```
用户调用 phoneAgent.run("打开设置并开启蓝牙", systemPrompt)
    │
    ├─ 1. 注册 Job 到 PhoneAgentJobRegistry
    │
    ├─ 2. ensureRequiredVirtualScreenOrError()
    │   ├─ 检查权限（ADB/Root/Debugger + Shizuku）
    │   ├─ ShowerServerManager.ensureServerStarted()
    │   ├─ ShowerController.ensureDisplay() → 创建 VirtualDisplay
    │   └─ VirtualDisplayOverlay.show(displayId)
    │
    ├─ 3. prewarmMainScreenShowerIfPossible()
    │   └─ ShowerController.prepareMainDisplay() → displayId=0
    │
    ├─ 4. prewarmShowerIfNeeded()
    │   └─ actionHandler.executeAgentAction(Launch app="设置")
    │
    ├─ 5. 设置 UI 指示器
    │   ├─ 隐藏浮动窗口
    │   ├─ 显示 Shower 叠加层或全屏进度条
    │   └─ 绑定暂停/取消回调
    │
    ├─ 6. Agent 循环（最多 maxSteps 步）
    │   │
    │   ├─ Step 1:
    │   │   ├─ captureScreenshotForAgent() → <link type="image" id="img1">
    │   │   ├─ buildUserMessage("打开设置并开启蓝牙\n\n[SCREENSHOT]\n<link...>")
    │   │   ├─ uiService.sendMessage(chatHistory) → VLM 推理
    │   │   ├─ 收集响应: "我看到设置图标在 [120, 450]。do(action=\"Tap\", element=\"[120, 450]\")"
    │   │   ├─ parseAgentAction() → ParsedAgentAction("do", "Tap", {element="[120, 450]"})
    │   │   ├─ executeAgentAction() → 点击设置图标
    │   │   ├─ removeImagesFromLastUserMessage() → 移除截图链接
    │   │   └─ 更新进度: "正在执行 Tap"
    │   │
    │   ├─ Step 2:
    │   │   ├─ captureScreenshotForAgent() → <link type="image" id="img2">
    │   │   ├─ buildUserMessage("** Screen Info **\n\n[SCREENSHOT]\n<link...>")
    │   │   ├─ uiService.sendMessage(chatHistory) → VLM 推理
    │   │   ├─ 收集响应: "蓝牙开关在 [500, 800]。do(action=\"Tap\", element=\"[500, 800]\")"
    │   │   ├─ executeAgentAction() → 点击蓝牙开关
    │   │   └─ 更新进度: "正在执行 Tap"
    │   │
    │   ├─ Step 3:
    │   │   ├─ captureScreenshotForAgent() → <link type="image" id="img3">
    │   │   ├─ uiService.sendMessage(chatHistory) → VLM 推理
    │   │   ├─ 收集响应: "蓝牙已开启。finish(message=\"蓝牙已开启\")"
    │   │   ├─ parseAgentAction() → ParsedAgentAction("finish", null, {message="蓝牙已开启"})
    │   │   └─ return StepResult(finished=true, message="蓝牙已开启")
    │   │
    │   └─ 检测到 finished=true → 跳出循环
    │
    ├─ 7. finally 块
    │   ├─ 恢复浮动窗口可见
    │   ├─ 隐藏状态指示器
    │   ├─ 隐藏 Shower 叠加层 / 进度条
    │   └─ cleanupOnFinish → VirtualDisplayOverlay.hide() + ShowerController.shutdown()
    │
    └─ 8. 返回 "蓝牙已开启"
```

### 5.2 截图流程（双路径）

```
captureScreenshotForAgent()
    │
    ├─ 1. 隐藏 UI 指示器
    │   ├─ floatingService.setStatusIndicatorVisible(false)
    │   └─ progressOverlay.setOverlayVisible(false)
    │
    ├─ 2. 延迟 200ms（等待 UI 消失）
    │
    ├─ 3. 尝试 Shower 截图
    │   ├─ isMainScreenAgent?
    │   │   ├─ true → ShowerController.requestScreenshot() → PNG bytes
    │   │   └─ false → VirtualDisplayOverlay.captureCurrentFramePng() → PNG bytes
    │   ├─ decodeByteArray → Bitmap
    │   └─ saveCompressedScreenshotFromBitmap() → <link type="image" id="xxx">
    │
    ├─ 4. Shower 失败则回退到无障碍截图
    │   └─ toolImplementations.captureScreenshotBitmap() → Bitmap
    │       └─ saveCompressedScreenshotFromBitmap()
    │
    ├─ 5. 恢复 UI 指示器
    │
    └─ 6. 更新 screenWidth/screenHeight
```

### 5.3 动作执行流程（以 Tap 为例）

```
executeAgentAction(ParsedAgentAction("do", "Tap", {element="[120, 450]"}))
    │
    ├─ 1. 解析坐标
    │   └─ parseRelativePoint("[120, 450]") → (x=120, y=450)
    │       └─ 转换为实际像素: (screenWidth*0.12, screenHeight*0.45)
    │
    ├─ 2. 确定执行路径
    │   └─ resolveShowerUsageContext()
    │       ├─ isMainScreenAgent? → 主屏幕用无障碍
    │       └─ 虚拟屏幕 + ADB → Shower
    │
    ├─ 3. 执行（Shower 路径）
    │   └─ withAgentUiHiddenForAction {
    │       └─ ShowerController.tap(agentId, x, y)
    │           └─ ClientShowerController.tap() → InputManager.injectInputEvent()
    │       }
    │
    ├─ 4. 执行（无障碍路径）
    │   └─ withAgentUiHiddenForAction {
    │       └─ toolImplementations.tap(AITool("tap", [x, y]))
    │       }
    │
    ├─ 5. 延迟（非等待动作）
    │   └─ delay(500ms)
    │
    └─ 6. 返回 ActionExecResult(success=true, shouldFinish=false)
```

### 5.4 Type 动作详细流程

```
executeAgentAction(Type, text="Hello World")
    │
    ├─ Shower 路径:
    │   ├─ 1. Ctrl+A 全选
    │   ├─ 2. DEL 删除（如果失败则 CLEAR，再失败则 MOVE_END + 200次 DEL）
    │   ├─ 3. 延迟 300ms
    │   ├─ 4. 设置剪贴板: clipboard.setPrimaryClip("Hello World")
    │   ├─ 5. 延迟 100ms
    │   └─ 6. KEYCODE_PASTE 粘贴
    │
    └─ 无障碍路径:
        └─ toolImplementations.setInputText(AITool("set_input_text", [text]))
```

---

## 6. 使用示例

### 6.1 创建并运行 Agent（主屏幕模式）

```kotlin
val agent = PhoneAgent(
    context = context,
    config = AgentConfig(maxSteps = 20),
    uiService = aiService,
    actionHandler = ActionHandler(
        context = context,
        screenWidth = displayMetrics.widthPixels,
        screenHeight = displayMetrics.heightPixels,
        toolImplementations = MyToolImplementations(context)
    ),
    agentId = "default",  // 主屏幕模式
    cleanupOnFinish = false
)

val result = agent.run(
    task = "打开微信并发送消息给张三说你好",
    systemPrompt = "你是一个 Android UI 自动化助手...",
    onStep = { stepResult ->
        Log.d("Agent", "Step ${agent.stepCount}: ${stepResult.action?.actionName}")
    }
)

println("Agent result: $result")
```

### 6.2 创建并运行 Agent（虚拟屏幕模式）

```kotlin
val agent = PhoneAgent(
    context = context,
    config = AgentConfig(maxSteps = 20),
    uiService = aiService,
    actionHandler = actionHandler,
    agentId = "agent_001",  // 自定义 ID → 虚拟屏幕模式
    cleanupOnFinish = true   // 完成后清理资源
)

val result = agent.run(
    task = "在浏览器中搜索 Kotlin 教程",
    systemPrompt = "...",
    targetApp = "浏览器"  // 预启动目标应用
)
```

### 6.3 暂停和恢复 Agent

```kotlin
val isPaused = MutableStateFlow(false)

// 启动 Agent
val job = scope.launch {
    agent.run(
        task = "...",
        systemPrompt = "...",
        isPausedFlow = isPaused
    )
}

// 用户点击暂停按钮
pauseButton.setOnClickListener {
    isPaused.value = true
}

// 用户点击恢复按钮
resumeButton.setOnClickListener {
    isPaused.value = false
}

// 用户点击取消按钮
cancelButton.setOnClickListener {
    PhoneAgentJobRegistry.cancelAgent("default", "User cancelled")
}
```

### 6.4 实现 ToolImplementations

```kotlin
class MyToolImplementations(private val context: Context) : ToolImplementations {

    override suspend fun tap(tool: AITool): ToolResult {
        val x = tool.parameters.find { it.name == "x" }?.value?.toIntOrNull() ?: 0
        val y = tool.parameters.find { it.name == "y" }?.value?.toIntOrNull() ?: 0
        // 通过无障碍服务执行点击
        return AccessibilityUITools.tap(x, y)
    }

    override suspend fun swipe(tool: AITool): ToolResult {
        // ...
    }

    override suspend fun setInputText(tool: AITool): ToolResult {
        // ...
    }

    override suspend fun pressKey(tool: AITool): ToolResult {
        // ...
    }

    override suspend fun captureScreenshot(tool: AITool): Pair<String?, Pair<Int, Int>?> {
        // 截图并保存，返回文件路径和尺寸
    }
}
```

### 6.5 使用 Shower 虚拟显示

```kotlin
// 1. 确保 Shower 服务器已启动
val serverStarted = ShowerServerManager.ensureServerStarted(context)

// 2. 创建虚拟显示
val metrics = context.resources.displayMetrics
val created = ShowerController.ensureDisplay(
    agentId = "agent_001",
    context = context,
    width = metrics.widthPixels,
    height = metrics.heightPixels,
    dpi = metrics.densityDpi,
    bitrateKbps = 3000
)

// 3. 启动应用
ShowerController.launchApp("agent_001", "com.android.settings")

// 4. 执行操作
ShowerController.tap("agent_001", 500, 800)
ShowerController.swipe("agent_001", 100, 500, 100, 200)
ShowerController.key("agent_001", KeyEvent.KEYCODE_BACK)

// 5. 请求截图
val pngBytes = ShowerController.requestScreenshot("agent_001")

// 6. 关闭
ShowerController.shutdown("agent_001")
```

### 6.6 注册 Binder 服务

```kotlin
// 在 Application.onCreate() 或 Service 中
val intentFilter = IntentFilter(ShowerBinderReceiver.ACTION_SHOWER_BINDER_READY)
registerReceiver(ShowerBinderReceiver(), intentFilter)

// Shower 服务器启动后会发送广播
// ShowerBinderReceiver 自动提取 Binder 并注册到 ShowerBinderRegistry
```

---

## 7. 最佳实践

### 7.1 Agent 配置

| 参数 | 推荐值 | 说明 |
|------|--------|------|
| `maxSteps` | 20 | 防止无限循环，复杂任务可适当增加 |
| `cleanupOnFinish` | `agentId != "default"` | 虚拟屏幕模式清理资源，主屏幕模式保留 |
| `targetApp` | 任务相关应用 | 预启动可加速任务执行 |

### 7.2 权限要求

| 模式 | 所需权限 | 说明 |
|------|---------|------|
| 主屏幕 | 无障碍服务 | 模拟点击、滑动、截图 |
| 虚拟屏幕 | ADB/Root/Debugger + Shizuku | Shower 服务器需要 shell 权限注入输入 |

### 7.3 截图优化

- **隐藏 UI 指示器**：截图前隐藏进度条和状态指示器，避免 VLM 被干扰
- **压缩**：使用 `ImagePoolManager` 压缩截图，控制 Token 消耗
- **移除历史截图**：每步执行后从历史中移除截图链接，节省上下文空间

### 7.4 错误处理

- **权限不足**：`ensureRequiredVirtualScreenOrError()` 返回用户友好的错误消息
- **Shower 启动失败**：回退到无障碍服务模式
- **动作执行失败**：`ActionExecResult` 包含错误信息，Agent 可据此调整策略
- **协程取消**：正确处理 `CancellationException`，避免资源泄漏

### 7.5 性能优化

- **延迟**：动作执行后延迟 500ms（`POST_NON_WAIT_ACTION_DELAY_MS`），等待 UI 稳定
- **启动延迟**：应用启动后延迟 1000ms（`POST_LAUNCH_DELAY_MS`），等待应用加载
- **并发**：不同 `agentId` 的 Agent 可并发运行，互不干扰

### 7.6 安全注意事项

- **ADB/Root 权限**：Shower 服务器需要高权限，仅在可信设备上使用
- **Binder 安全**：`ShowerBinderReceiver` 验证广播 action，防止恶意绑定
- **输入注入**：`InputManager.injectInputEvent()` 可模拟任意输入，注意权限控制

---

*文档生成时间：2026-05-16*
*基于 Operit 项目 agent 模块源代码分析*
