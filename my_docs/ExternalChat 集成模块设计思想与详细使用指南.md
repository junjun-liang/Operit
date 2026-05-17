# ExternalChat 集成模块设计思想与详细使用指南

## 目录

1. [模块概述](#1-模块概述)
2. [核心设计思想](#2-核心设计思想)
3. [架构总览](#3-架构总览)
4. [数据模型详解](#4-数据模型详解)
5. [请求执行器详解](#5-请求执行器详解)
6. [响应清洗器详解](#6-响应清洗器详解)
7. [关键流程](#7-关键流程)
8. [使用示例](#8-使用示例)
9. [最佳实践](#9-最佳实践)

---

## 1. 模块概述

`externalchat` 模块是 Operit 项目与**外部应用**的对话集成层，提供 HTTP API 风格的接口，允许第三方应用通过发送请求与 Operit 的 AI 进行对话。模块负责请求解析、对话准备、消息发送和响应清洗的完整流程。

模块位于 `com.ai.assistance.operit.integrations.externalchat` 包下，包含 3 个核心文件：

| 文件 | 核心类 | 职责 |
|------|--------|------|
| `ExternalChatModels.kt` | 6 个数据类 + 1 个枚举 | 请求/响应/流式数据模型 |
| `ExternalChatRequestExecutor.kt` | `ExternalChatRequestExecutor` | 请求准备与执行 |
| `ExternalChatResponseSanitizer.kt` | `ExternalChatResponseSanitizer` | 响应内容清洗（去除工具标签） |

---

## 2. 核心设计思想

### 2.1 请求-响应模型分离

模块定义了两组请求模型：

- **`ExternalChatRequest`**：内部执行用，不含 HTTP 特有字段
- **`ExternalChatHttpRequest`**：HTTP 层用，扩展了 `stream`、`responseMode`、`callbackUrl`

HTTP 层请求通过 `toExecutionRequest()` 转换为内部请求，实现**关注点分离**。

### 2.2 两种响应模式

| 模式 | 枚举值 | 说明 |
|------|--------|------|
| 同步 | `SYNC` | 阻塞等待 AI 完整响应后返回 |
| 异步回调 | `ASYNC_CALLBACK` | 立即返回 `accepted`，完成后回调 `callbackUrl` |

同步模式适用于短对话，异步模式适用于长时间运行的 AI 任务。

### 2.3 两种执行模式

| 模式 | 方法 | 说明 |
|------|------|------|
| 一次性 | `execute()` | 阻塞等待完整结果 |
| 流式 | `startStreaming()` | 返回流式 Session，逐块接收 AI 响应 |

### 2.4 响应清洗（Sanitization）

AI 响应中可能包含工具调用标签（`<tool>`、`<status>`、`<tool_result>`），这些是内部实现细节，对外部调用者无意义。`ExternalChatResponseSanitizer` 根据请求参数 `returnToolStatus` 决定是否清洗：

- `returnToolStatus = true`：保留原始响应（含工具标签）
- `returnToolStatus = false`：去除工具标签，仅保留纯文本

### 2.5 流式清洗

清洗不仅支持完整字符串，还支持**流式清洗**：在流式传输过程中实时过滤 XML 标签组，无需等待完整响应。这通过 `Stream.splitBy(StreamXmlPlugin)` 实现，将流分为 XML 组和文本组，实时过滤。

---

## 3. 架构总览

```
┌─────────────────────────────────────────────────────────────┐
│                    HTTP 层（外部调用者）                       │
│                                                              │
│  POST /chat                                                 │
│  Body: ExternalChatHttpRequest (JSON)                       │
│                                                              │
├─────────────────────────────────────────────────────────────┤
│                    数据模型层                                 │
│                                                              │
│  ExternalChatHttpRequest                                    │
│    ├─ toExecutionRequest() → ExternalChatRequest            │
│    ├─ normalizedResponseMode() → SYNC / ASYNC_CALLBACK      │
│    └─ resolvedRequestId() → 自动生成 UUID                   │
│                                                              │
│  ExternalChatAcceptedResponse  ← 异步模式立即返回            │
│  ExternalChatResult            ← 同步模式最终结果            │
│  ExternalChatStreamEnvelope    ← 流式模式事件信封            │
│  ExternalChatHealthResponse    ← 健康检查响应                │
│                                                              │
├─────────────────────────────────────────────────────────────┤
│                    执行层                                     │
│                                                              │
│  ExternalChatRequestExecutor                                │
│  ├─ execute(request) → ExternalChatResult                   │
│  │   └─ prepareRequest() → PreparationResult                │
│  │       ├─ 验证消息非空                                     │
│  │       ├─ 可选：启动浮动窗口（showFloating）               │
│  │       ├─ 可选：创建新对话（createNewChat）                │
│  │       ├─ 可选：检查现有对话（createIfNone）               │
│  │       └─ 构建 send_message_to_ai 工具                    │
│  │                                                          │
│  └─ startStreaming(request) → ExternalChatStreamingStartResult│
│      └─ 返回 ExternalChatStreamingSession                   │
│                                                              │
├─────────────────────────────────────────────────────────────┤
│                    清洗层                                     │
│                                                              │
│  ExternalChatResponseSanitizer                              │
│  ├─ sanitize(content, returnToolStatus) → String?           │
│  └─ sanitizeStream(rawStream, returnToolStatus) → Stream    │
│      └─ splitBy(StreamXmlPlugin) → 分组过滤                 │
│          ├─ XML 组：<tool>/<status>/<tool_result> → 剥离    │
│          └─ 文本组：保留，按自然边界分块                      │
└─────────────────────────────────────────────────────────────┘
```

---

## 4. 数据模型详解

### 4.1 ExternalChatRequest（内部执行请求）

```kotlin
@Serializable
data class ExternalChatRequest(
    val requestId: String? = null,        // 请求 ID
    val message: String? = null,          // 用户消息（必填）
    val group: String? = null,            // 对话分组
    val createNewChat: Boolean = false,   // 是否创建新对话
    val chatId: String? = null,           // 指定对话 ID
    val createIfNone: Boolean = true,     // 无对话时是否自动创建
    val showFloating: Boolean = false,    // 是否显示浮动窗口
    val returnToolStatus: Boolean = true, // 是否返回工具状态
    val initialMode: String? = null,      // 浮动窗口初始模式
    val autoExitAfterMs: Long = -1L,      // 浮动窗口自动退出时间
    val timeoutMs: Long = -1L,            // 请求超时时间
    val stopAfter: Boolean = false        // 完成后是否停止服务
)
```

### 4.2 ExternalChatHttpRequest（HTTP 层请求）

在 `ExternalChatRequest` 基础上扩展了 3 个 HTTP 特有字段：

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `stream` | `Boolean` | `false` | 是否启用流式响应 |
| `responseMode` | `String` | `"sync"` | 响应模式：`sync` / `async_callback` |
| `callbackUrl` | `String?` | `null` | 异步回调 URL |

**关键方法**：

| 方法 | 说明 |
|------|------|
| `normalizedResponseMode()` | 将 `responseMode` 字符串转为 `ExternalChatResponseMode` 枚举 |
| `resolvedRequestId()` | 返回请求 ID，为空时自动生成 UUID |
| `toExecutionRequest(resolvedRequestId)` | 转换为 `ExternalChatRequest`（剥离 HTTP 特有字段） |

### 4.3 ExternalChatResult（执行结果）

```kotlin
@Serializable
data class ExternalChatResult(
    val requestId: String? = null,
    val success: Boolean,
    val chatId: String? = null,
    val aiResponse: String? = null,   // AI 响应（可能已清洗）
    val error: String? = null
)
```

### 4.4 ExternalChatAcceptedResponse（异步接受响应）

```kotlin
@Serializable
data class ExternalChatAcceptedResponse(
    val requestId: String,
    val accepted: Boolean = true,
    val status: String = "accepted"
)
```

异步模式下立即返回，告知调用者请求已接受。

### 4.5 ExternalChatHealthResponse（健康检查响应）

```kotlin
@Serializable
data class ExternalChatHealthResponse(
    val status: String = "ok",
    val enabled: Boolean,
    val serviceRunning: Boolean,
    val port: Int,
    val versionName: String
)
```

### 4.6 ExternalChatStreamEnvelope（流式事件信封）

```kotlin
@Serializable
data class ExternalChatStreamEnvelope(
    val event: String,           // 事件类型
    val requestId: String,
    val chatId: String? = null,
    val delta: String? = null,   // 增量文本
    val aiResponse: String? = null,  // 完整响应（完成时）
    val success: Boolean? = null,
    val error: String? = null
)
```

**事件类型**：

| event 值 | 说明 | 携带字段 |
|----------|------|---------|
| `delta` | 增量文本 | `delta` |
| `done` | 完成 | `aiResponse`, `success` |
| `error` | 错误 | `error` |

### 4.7 ExternalChatResponseMode（响应模式枚举）

| 枚举值 | 说明 |
|--------|------|
| `SYNC` | 同步：阻塞等待完整响应 |
| `ASYNC_CALLBACK` | 异步：立即返回 accepted，完成后回调 |

---

## 5. 请求执行器详解

### 5.1 ExternalChatRequestExecutor

**核心职责**：将外部请求转换为内部工具调用，执行并返回结果。

#### execute() — 一次性执行

```kotlin
suspend fun execute(request: ExternalChatRequest): ExternalChatResult
```

流程：
1. `prepareRequest()` — 验证和准备
2. `chatTool.sendMessageToAI(sendTool)` — 发送消息
3. `toExternalChatResult()` — 转换结果（含清洗）
4. `cleanup()` — 可选停止服务

#### startStreaming() — 流式执行

```kotlin
suspend fun startStreaming(request: ExternalChatRequest): ExternalChatStreamingStartResult
```

流程：
1. `prepareRequest()` — 验证和准备
2. `chatTool.startMessageToAIStream(sendTool)` — 启动流式发送
3. 返回 `ExternalChatStreamingSession`（含流式响应 Session）
4. 调用方通过 Session 逐块收集响应

### 5.2 prepareRequest() — 请求准备

准备过程按顺序执行以下步骤：

```
1. 验证消息非空
   └─ message 为空 → 返回 Failed("Missing extra: message")

2. 可选：启动浮动窗口（showFloating = true）
   └─ chatTool.startChatService(initialMode, autoExitAfterMs)

3. 可选：检查现有对话（createIfNone = false）
   └─ chatTool.listChats() → 检查 currentChatId
   └─ 无对话 → 返回 Failed("No current chat and create_if_none=false")

4. 可选：创建新对话（createNewChat = true）
   └─ chatTool.createNewChat(group)

5. 构建 send_message_to_ai 工具参数
   ├─ message（必填）
   ├─ chat_id（可选，非 createNewChat 时）
   └─ timeout_ms（可选）

6. 返回 PreparationResult.Ready
```

### 5.3 PreparationResult — 准备结果

```kotlin
private sealed class PreparationResult {
    data class Ready(
        val requestId: String?,
        val resolvedRequestId: String,
        val message: String,
        val returnToolStatus: Boolean,
        val chatTool: StandardChatManagerTool,
        val sendTool: AITool,
        val cleanupAction: () -> Unit
    ) : PreparationResult()

    data class Failed(val result: ExternalChatResult) : PreparationResult()
}
```

**cleanupAction**：当 `stopAfter = true` 时，cleanup 会调用 `chatTool.stopChatService()` 停止聊天服务。

### 5.4 ExternalChatStreamingSession — 流式会话

```kotlin
class ExternalChatStreamingSession(
    val requestId: String,
    val message: String,
    val chatId: String,
    val responseStreamSession: MessageSendStreamSession,
    private val cleanupAction: () -> Unit
) {
    private val cleanedUp = AtomicBoolean(false)

    fun cleanup() {
        if (cleanedUp.compareAndSet(false, true)) {
            cleanupAction()
        }
    }
}
```

**关键点**：
- 持有 `MessageSendStreamSession`，可逐块收集 AI 响应
- `cleanup()` 使用 `AtomicBoolean` 防止重复清理
- 清理时执行 `stopAfter` 逻辑

---

## 6. 响应清洗器详解

### 6.1 ExternalChatResponseSanitizer

**核心职责**：根据 `returnToolStatus` 参数清洗 AI 响应中的工具标签。

#### sanitize() — 完整字符串清洗

```kotlin
suspend fun sanitize(content: String?, returnToolStatus: Boolean): String?
```

- `returnToolStatus = true` → 直接返回原始内容
- `returnToolStatus = false` → 去除 `<tool>`/`<status>`/`<tool_result>` 标签，保留纯文本

#### sanitizeStream() — 流式清洗

```kotlin
fun sanitizeStream(rawStream: Stream<String>, returnToolStatus: Boolean): Stream<String>
```

流式版本，实时过滤 XML 标签组，无需等待完整响应。

### 6.2 清洗算法

```
原始流
    │
    ├─ splitBy(StreamXmlPlugin) → Stream<StreamGroup<StreamPlugin?>>
    │
    ├─ 遍历每个 StreamGroup：
    │   ├─ tag = StreamXmlPlugin（XML 组）
    │   │   ├─ 收集完整 XML 内容
    │   │   ├─ shouldStripXmlGroup(xml) → 检查是否需要剥离
    │   │   │   ├─ 标签名 = "status" → 剥离
    │   │   │   ├─ 标签名 = "tool" → 剥离
    │   │   │   ├─ 标签名 = "tool_result" → 剥离
    │   │   │   └─ 其他 → 保留
    │   │   └─ 不剥离 → emitChunk(xml)
    │   │
    │   ├─ tag = null（文本组）
    │   │   ├─ 逐块收集文本
    │   │   ├─ 达到刷新条件时 emitChunk：
    │   │   │   ├─ 缓冲区 ≥ 32 字符
    │   │   │   ├─ 遇到换行符
    │   │   │   └─ 遇到自然边界（。！？；：.!?;:）
    │   │   └─ 最后 flush 剩余文本
    │   │
    │   └─ tag = 其他 → 直接 emitChunk
    │
    └─ sanitizeResidualWhitespace()
        ├─ \r\n → \n
        ├─ 行尾空格 → 去除
        ├─ 行首空格 → 去除
        ├─ 3+ 连续换行 → 2 换行
        └─ trim()
```

### 6.3 自然边界字符

清洗器在以下标点符号处刷新文本缓冲区，确保流式输出时句子不会在中间被截断：

| 中文标点 | 英文标点 |
|---------|---------|
| 。 ， ！ ？ ； ： | . , ! ? ; : |

### 6.4 清洗示例

**输入**（AI 原始响应）：

```
我来帮你查看天气。<tool name="get_weather">
<param name="city">北京</param>
</tool>
<tool_result name="get_weather">
北京：晴，25°C
</tool_result>
北京今天晴，气温25度。
```

**输出**（`returnToolStatus = false`）：

```
我来帮你查看天气。
北京今天晴，气温25度。
```

**输出**（`returnToolStatus = true`）：

```
我来帮你查看天气。<tool name="get_weather">
<param name="city">北京</param>
</tool>
<tool_result name="get_weather">
北京：晴，25°C
</tool_result>
北京今天晴，气温25度。
```

---

## 7. 关键流程

### 7.1 同步请求完整流程

```
外部应用发送 HTTP 请求
    │
    ├─ 1. 解析 ExternalChatHttpRequest
    │   ├─ resolvedRequestId() → 生成/确认请求 ID
    │   ├─ normalizedResponseMode() → SYNC
    │   └─ toExecutionRequest() → ExternalChatRequest
    │
    ├─ 2. ExternalChatRequestExecutor.execute(request)
    │   ├─ prepareRequest()
    │   │   ├─ 验证 message 非空
    │   │   ├─ 可选：startChatService()
    │   │   ├─ 可选：createNewChat()
    │   │   └─ 构建 send_message_to_ai 工具
    │   │
    │   ├─ chatTool.sendMessageToAI(sendTool)
    │   │   └─ 阻塞等待 AI 完整响应
    │   │
    │   ├─ toExternalChatResult()
    │   │   └─ ExternalChatResponseSanitizer.sanitize(aiResponse, returnToolStatus)
    │   │
    │   └─ cleanup()（stopAfter 时停止服务）
    │
    └─ 3. 返回 ExternalChatResult (JSON)
        ├─ success: true/false
        ├─ chatId: "xxx"
        ├─ aiResponse: "清洗后的响应"
        └─ error: null
```

### 7.2 流式请求完整流程

```
外部应用发送 HTTP 请求（stream = true）
    │
    ├─ 1. 解析 ExternalChatHttpRequest → ExternalChatRequest
    │
    ├─ 2. ExternalChatRequestExecutor.startStreaming(request)
    │   ├─ prepareRequest()
    │   └─ chatTool.startMessageToAIStream(sendTool)
    │       └─ 返回 ExternalChatStreamingSession
    │
    ├─ 3. 流式收集响应
    │   ├─ session.responseStreamSession.stream.collect { chunk ->
    │   │   ├─ ExternalChatResponseSanitizer.sanitizeStream()
    │   │   └─ 发送 ExternalChatStreamEnvelope(event="delta", delta=chunk)
    │   └─ 完成时发送 ExternalChatStreamEnvelope(event="done", aiResponse=full)
    │
    └─ 4. session.cleanup()
```

### 7.3 异步回调流程

```
外部应用发送 HTTP 请求（responseMode = "async_callback", callbackUrl = "https://..."）
    │
    ├─ 1. 立即返回 ExternalChatAcceptedResponse
    │   └─ { requestId: "xxx", accepted: true, status: "accepted" }
    │
    ├─ 2. 后台执行 AI 对话
    │   └─ execute(request) → ExternalChatResult
    │
    └─ 3. 完成后回调 callbackUrl
        └─ POST callbackUrl with ExternalChatResult (JSON)
```

---

## 8. 使用示例

### 8.1 基础同步请求

```kotlin
val executor = ExternalChatRequestExecutor(context)

val result = executor.execute(
    ExternalChatRequest(
        message = "你好，今天天气怎么样？",
        returnToolStatus = false
    )
)

if (result.success) {
    println("AI: ${result.aiResponse}")
    println("Chat ID: ${result.chatId}")
} else {
    println("Error: ${result.error}")
}
```

### 8.2 指定对话 ID 发送消息

```kotlin
val result = executor.execute(
    ExternalChatRequest(
        message = "继续刚才的话题",
        chatId = "existing-chat-id",
        returnToolStatus = true
    )
)
```

### 8.3 创建新对话并发送消息

```kotlin
val result = executor.execute(
    ExternalChatRequest(
        message = "开始新的对话",
        createNewChat = true,
        group = "work"
    )
)
```

### 8.4 显示浮动窗口

```kotlin
val result = executor.execute(
    ExternalChatRequest(
        message = "帮我打开设置",
        showFloating = true,
        initialMode = "agent",
        autoExitAfterMs = 30000,
        stopAfter = true
    )
)
```

### 8.5 流式请求

```kotlin
val startResult = executor.startStreaming(
    ExternalChatRequest(
        message = "写一首诗",
        returnToolStatus = false
    )
)

when (startResult) {
    is ExternalChatStreamingStartResult.Started -> {
        val session = startResult.session
        session.responseStreamSession.stream.collect { chunk ->
            print(chunk)  // 逐块输出
        }
        session.cleanup()
    }
    is ExternalChatStreamingStartResult.Failed -> {
        println("Error: ${startResult.result.error}")
    }
}
```

### 8.6 HTTP 请求示例（JSON）

**同步请求**：

```json
POST /chat
{
    "message": "你好",
    "return_tool_status": false
}
```

**响应**：

```json
{
    "request_id": "auto-generated-uuid",
    "success": true,
    "chat_id": "chat-123",
    "ai_response": "你好！有什么我可以帮你的吗？",
    "error": null
}
```

**流式请求**：

```json
POST /chat
{
    "message": "写一首诗",
    "stream": true,
    "response_mode": "sync",
    "return_tool_status": false
}
```

**流式响应**（SSE 风格）：

```json
{"event":"delta","request_id":"xxx","delta":"春"}
{"event":"delta","request_id":"xxx","delta":"风"}
{"event":"delta","request_id":"xxx","delta":"拂面"}
{"event":"done","request_id":"xxx","ai_response":"春风拂面...","success":true}
```

**异步请求**：

```json
POST /chat
{
    "message": "分析这份报告",
    "response_mode": "async_callback",
    "callback_url": "https://myapp.com/callback"
}
```

**立即响应**：

```json
{
    "request_id": "xxx",
    "accepted": true,
    "status": "accepted"
}
```

### 8.7 健康检查

```json
GET /health
```

**响应**：

```json
{
    "status": "ok",
    "enabled": true,
    "service_running": true,
    "port": 8765,
    "version_name": "1.0.0"
}
```

---

## 9. 最佳实践

### 9.1 请求参数选择

| 场景 | 推荐参数 |
|------|---------|
| 简单问答 | `createNewChat=false`, `returnToolStatus=false` |
| 长对话 | `chatId="xxx"`, `returnToolStatus=false` |
| 需要工具详情 | `returnToolStatus=true` |
| 需要可视化 | `showFloating=true`, `initialMode="agent"` |
| 后台任务 | `responseMode="async_callback"`, `callbackUrl="..."` |
| 实时显示 | `stream=true` |

### 9.2 returnToolStatus 选择

- **设为 false**（推荐）：外部应用通常只关心 AI 的最终回复，不需要工具调用细节
- **设为 true**：调试场景或需要了解工具执行过程时使用

### 9.3 对话管理

- **createNewChat = true**：每次请求创建新对话，适用于独立任务
- **chatId 指定**：复用已有对话，适用于多轮对话
- **createIfNone = false**：严格模式，无对话时失败而非自动创建

### 9.4 资源清理

- **stopAfter = true**：请求完成后自动停止聊天服务，适用于一次性任务
- **流式 Session**：使用完毕后务必调用 `session.cleanup()`
- **autoExitAfterMs**：设置浮动窗口自动退出时间，避免窗口残留

### 9.5 错误处理

- **message 为空**：返回 `success=false, error="Missing extra: message"`
- **无对话且 createIfNone=false**：返回 `success=false, error="No current chat and create_if_none=false"`
- **AI 服务异常**：返回 `success=false, error=exception.message`
- **流式启动失败**：返回 `ExternalChatStreamingStartResult.Failed`

### 9.6 性能考虑

- **流式 vs 同步**：流式模式用户体验更好，但实现复杂度更高
- **清洗开销**：`returnToolStatus=false` 时需要 XML 解析和过滤，但开销很小
- **自然边界刷新**：流式清洗在标点符号处刷新缓冲区，平衡延迟和完整性

---

*文档生成时间：2026-05-16*
*基于 Operit 项目 externalchat 集成模块源代码分析*
