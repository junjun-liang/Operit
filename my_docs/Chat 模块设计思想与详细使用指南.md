# Chat 模块设计思想与详细使用指南

## 目录

1. [模块概述](#1-模块概述)
2. [核心设计思想](#2-核心设计思想)
3. [架构分层](#3-架构分层)
4. [核心类详解](#4-核心类详解)
5. [关键流程](#5-关键流程)
6. [使用示例](#6-使用示例)
7. [扩展机制](#7-扩展机制)
8. [最佳实践](#8-最佳实践)

---

## 1. 模块概述

`chat` 模块是整个 AI 助手应用的核心对话引擎，负责管理从用户输入到 AI 响应的完整生命周期。它整合了 LLM 服务调用、工具执行、对话历史管理、记忆系统、多轮对话协调等复杂功能，为上层 UI 提供统一、简洁的接口。

模块位于 `com.ai.assistance.operit.api.chat` 和 `com.ai.assistance.operit.core.chat` 包下，按职责分为多个子包：

| 子包 | 职责 |
|------|------|
| `api/chat` | 对外暴露的核心服务接口 |
| `api/chat/llmprovider` | LLM 服务提供商抽象与实现 |
| `api/chat/enhance` | 对话增强功能（工具执行、文件绑定、多服务管理等） |
| `api/chat/library` | 记忆库管理 |
| `core/chat` | 内部核心逻辑（消息管理、Hook 系统、插件系统） |
| `core/chat/hooks` | Prompt 与 Summary 的 Hook 注册表 |
| `core/chat/plugins` | 消息处理插件机制 |

---

## 2. 核心设计思想

### 2.1 分层架构与关注点分离

Chat 模块采用严格的分层设计：

- **核心层 (`core.chat`)**：处理消息构建、历史管理、Hook/插件扩展，无 Android UI 依赖
- **API 层 (`api.chat`)**：整合所有能力，提供面向业务的高级接口
- **增强层 (`api.chat.enhance`)**：工具执行、文件操作、多模型协调等特定功能
- **提供层 (`api.chat.llmprovider`)**：对接各种 LLM API（OpenAI、Claude、Gemini 等）

### 2.2 统一 Prompt 抽象 (`PromptTurn`)

所有对话历史统一抽象为 `PromptTurn`，包含：

```kotlin
data class PromptTurn(
    val kind: PromptTurnKind,  // SYSTEM/USER/ASSISTANT/TOOL_CALL/TOOL_RESULT/SUMMARY
    val content: String,
    val toolName: String? = null,
    val metadata: Map<String, Any?> = emptyMap()
)
```

这种设计使得不同 LLM 提供商可以统一处理对话历史，同时支持工具调用、总结消息等特殊类型。

### 2.3 Hook 系统（面向扩展）

通过 `PromptHookRegistry` 和 `SummaryHookRegistry` 提供多阶段 Hook 机制：

- **Prompt 阶段**：输入处理、历史处理、系统提示词组合、工具提示词组合、最终确认
- **Summary 阶段**：总结生成前/后的上下文修改

Hook 采用"上下文 + 变更"模式，支持链式调用和异常隔离。

### 2.4 插件化消息处理

`MessageProcessingPluginRegistry` 允许注册自定义插件接管消息处理流程。插件可以：
- 拦截特定消息模式
- 完全替代默认的 AI 请求流程
- 返回自定义的流式响应

### 2.5 多服务管理 (`MultiServiceManager`)

支持按功能类型（聊天、总结、翻译、记忆等）配置不同的 LLM 模型，实现：
- 功能级别的模型隔离
- 动态服务刷新
- Token 计数独立统计

### 2.6 流式响应与修订追踪

所有 AI 响应均以 `Stream<String>` 形式返回，支持：
- 实时流式输出到 UI
- `TextStreamRevisionTracker` 实现内容的 savepoint/rollback
- 事件通道传递元数据（如工具调用事件）

---

## 3. 架构分层

```
┌─────────────────────────────────────────────────────────────┐
│  UI Layer (ViewModel/Activity)                               │
│  - 调用 AIMessageManager / EnhancedAIService                 │
├─────────────────────────────────────────────────────────────┤
│  API Layer                                                   │
│  ├─ AIMessageManager      : 消息构建与发送的统一入口          │
│  ├─ EnhancedAIService     : 增强 AI 服务（工具/记忆/总结）    │
│  ├─ ChatRuntimeHolder     : 多会话运行时管理                  │
│  └─ AIForegroundService   : 前台服务保活与通知                │
├─────────────────────────────────────────────────────────────┤
│  Enhance Layer                                               │
│  ├─ MultiServiceManager   : 多 LLM 服务管理                  │
│  ├─ ToolExecutionManager  : 工具调用执行                     │
│  ├─ ConversationService   : 对话历史准备与总结               │
│  ├─ ConversationRoundManager: 多轮对话内容管理               │
│  ├─ FileBindingService    : 文件绑定与补丁应用               │
│  └─ InputProcessor        : 用户输入预处理                   │
├─────────────────────────────────────────────────────────────┤
│  Provider Layer                                              │
│  ├─ AIService (interface) : 统一 LLM 接口                    │
│  ├─ AIServiceFactory      : 服务工厂                        │
│  └─ *Provider             : 各平台实现（OpenAI/Claude等）    │
├─────────────────────────────────────────────────────────────┤
│  Core Layer                                                  │
│  ├─ PromptHookRegistry    : Prompt 多阶段 Hook               │
│  ├─ SummaryHookRegistry   : 总结 Hook                       │
│  ├─ MessageProcessingPluginRegistry: 消息处理插件            │
│  └─ PromptTurn / PromptTurnKind : 对话回合抽象               │
└─────────────────────────────────────────────────────────────┘
```

---

## 4. 核心类详解

### 4.1 AIMessageManager（消息管理中枢）

**定位**：单例对象，负责管理与 `EnhancedAIService` 的所有通信。

**核心职责**：
- 构建用户消息的完整内容（附件、工作区、回复引用、代理发送者标签）
- 发送消息并处理流式响应
- 请求生成对话总结
- 管理按 chatId 隔离的活跃服务实例

**设计原则**：
- **无状态**：本身不持有特定聊天状态，所有数据通过参数传入
- **职责明确**：仅处理与 AI 服务的交互，UI 更新和数据持久化由调用方负责
- **性能监控**：内置消息处理各阶段耗时日志

**关键方法**：

```kotlin
// 构建用户消息（含附件、工作区、回复标签等）
suspend fun buildUserMessageContent(
    messageText: String,
    attachments: List<AttachmentInfo>,
    enableWorkspaceAttachment: Boolean = false,
    workspacePath: String? = null,
    replyToMessage: ChatMessage? = null,
    enableDirectImageProcessing: Boolean = false,
    chatId: String? = null
): String

// 发送消息给 AI
suspend fun sendMessage(
    enhancedAiService: EnhancedAIService,
    chatId: String? = null,
    messageContent: String,
    chatHistory: List<ChatMessage>,
    workspacePath: String?,
    promptFunctionType: PromptFunctionType,
    enableThinking: Boolean,
    enableMemoryAutoUpdate: Boolean,
    maxTokens: Int,
    tokenUsageThreshold: Double,
    onNonFatalError: suspend (error: String) -> Unit,
    onTokenLimitExceeded: (suspend () -> Unit)? = null,
    // ... 更多参数
): SharedStream<String>

// 生成对话总结
suspend fun summarizeMemory(
    enhancedAiService: EnhancedAIService,
    messages: List<ChatMessage>,
    autoContinue: Boolean = false,
    isGroupChat: Boolean = false
): ChatMessage?

// 取消操作
fun cancelCurrentOperation()
fun cancelOperation(chatId: String)
fun cancelAllOperations()
```

**附件处理策略**：
- 普通附件：生成 `<attachment>` XML 标签
- 直接图片处理：通过 `ImagePoolManager` 转换为 `<link>` 标签
- 直接音视频处理：通过 `MediaPoolManager` 转换为 `<link>` 标签

**历史裁剪**：
- 限制图片链接历史轮数（`maxImageHistoryUserTurns`）
- 限制媒体链接历史轮数（`maxMediaHistoryUserTurns`）
- 保留最近 N 个用户轮次中的媒体内容

---

### 4.2 EnhancedAIService（增强 AI 服务）

**定位**：核心服务类，整合工具执行、对话管理、用户偏好、记忆库等高级功能。

**实例管理**：
- 全局单例（`INSTANCE`）用于通用功能
- 按 `chatId` 隔离的实例（`CHAT_INSTANCES`）支持多会话并发

**核心能力**：

| 能力 | 说明 |
|------|------|
| 流式对话 | 通过 `Stream<String>` 实时返回 AI 响应 |
| 工具调用 | 自动检测 XML 工具标签，执行后递归调用 AI |
| 多轮对话 | `ConversationRoundManager` 管理多轮内容 |
| 记忆系统 | 自动/手动保存对话到记忆库 |
| 文件绑定 | 支持 AI 生成代码的模糊匹配补丁应用 |
| 翻译 | 独立翻译功能 |
| 总结生成 | 对话历史智能总结 |
| Token 统计 | 精确统计输入/输出/缓存 Token |

**执行上下文 (`MessageExecutionContext`)**：

每次 `sendMessage` 创建独立的执行上下文，包含：
- `executionId`：唯一标识
- `streamBuffer`：响应内容缓冲区
- `roundManager`：轮次管理器
- `isConversationActive`：原子标志控制会话生命周期
- `conversationHistory`：内部对话历史（独立于传入历史）
- `eventChannel`：流事件通道（savepoint/rollback）

**关键方法**：

```kotlin
// 发送消息（主要入口）
suspend fun sendMessage(options: SendMessageOptions): Stream<String>

// 估算请求窗口大小
suspend fun estimateRequestWindowFromMemory(...): Int

// 生成总结
suspend fun generateSummary(messages: List<Pair<String, String>>): String

// 取消当前对话
fun cancelConversation()

// 文件绑定
suspend fun applyFileBinding(originalContent: String, aiGeneratedCode: String): Pair<String, String>

// 翻译
suspend fun translateText(text: String): String

// 保存到记忆库
fun saveConversationToMemoryAsync(...)

// 图片/音频/视频分析
suspend fun analyzeImageWithIntent(imagePath: String, userIntent: String?): String
```

**SendMessageOptions 主要参数**：

```kotlin
data class SendMessageOptions(
    var message: String = "",
    var chatId: String? = null,
    var chatHistory: List<PromptTurn> = emptyList(),
    var workspacePath: String? = null,
    var promptFunctionType: PromptFunctionType = PromptFunctionType.CHAT,
    var enableThinking: Boolean = false,
    var enableMemoryAutoUpdate: Boolean = true,
    var maxTokens: Int = 0,
    var tokenUsageThreshold: Double = 0.0,
    var characterName: String? = null,
    var avatarUri: String? = null,
    var roleCardId: String? = null,
    var stream: Boolean = true,
    var onNonFatalError: suspend (error: String) -> Unit = {},
    var onToolInvocation: (suspend (String) -> Unit)? = null,
    // ... 更多参数
)
```

---

### 4.3 ChatRuntimeHolder（运行时管理器）

**定位**：管理多个 `ChatServiceCore` 实例，支持主界面和悬浮窗两种运行时槽位。

**设计特点**：
- 双槽位设计：`MAIN`（主界面）和 `FLOATING`（悬浮窗）
- 跨槽位同步：聊天选择同步、轮次完成同步
- 统计聚合：活跃对话数、当前轮次工具调用数

```kotlin
enum class ChatRuntimeSlot { MAIN, FLOATING }

class ChatRuntimeHolder private constructor(context: Context) {
    fun getCore(slot: ChatRuntimeSlot): ChatServiceCore
    fun syncMainChatSelectionToFloating(chatId: String)
    val activeConversationCount: StateFlow<Int>
    val currentSessionToolCount: StateFlow<Int>
}
```

---

### 4.4 AIForegroundService（前台服务）

**定位**：Android 前台服务，在 AI 进行长时间处理时保持应用活跃。

**核心功能**：
- 持久通知显示 AI 运行状态
- 唤醒词监听（ always listening ）
- 外部 HTTP API 服务保活
- 后台保活 overlay
- 回复完成通知（含声音/振动配置）
- 录音状态监控（避免与外部录音冲突）

**通知动作**：
- 打开悬浮窗语音对话
- 切换唤醒监听开关
- 取消当前 AI 操作
- 退出应用

---

### 4.5 MultiServiceManager（多服务管理器）

**定位**：按功能类型管理不同的 `AIService` 实例。

**功能映射**：
- 通过 `FunctionalConfigManager` 获取功能→配置映射
- 支持按 `FunctionType`（CHAT/SUMMARY/TRANSLATE/MEMORY 等）获取对应服务
- 支持自定义配置 ID + 模型索引创建独立服务

**服务包装**：
- `RateLimitedAIService`：速率限制包装
- `RequestConcurrencyRegistry`：并发请求控制

```kotlin
class MultiServiceManager(private val context: Context) {
    suspend fun getServiceForFunction(functionType: FunctionType): AIService
    suspend fun getServiceForConfig(configId: String, modelIndex: Int): AIService
    suspend fun getModelConfigForFunction(functionType: FunctionType): ModelConfigData
    suspend fun refreshServiceForFunction(functionType: FunctionType)
    suspend fun cancelAllStreaming()
}
```

---

### 4.6 ToolExecutionManager（工具执行管理器）

**定位**：统一管理 AI 工具调用的解析与执行。

**核心能力**：
- 从 AI 响应中提取 XML 工具调用
- 支持 `package_proxy` 代理工具
- 角色卡工具权限控制
- CLI 模式工具暴露控制
- 并发工具执行
- 工具结果格式化与截断

**执行流程**：
1. 解析 AI 响应中的 `<tool>` 标签
2. 权限校验（角色卡、CLI 模式）
3. 并发执行多个工具
4. 格式化结果为 XML 工具结果消息
5. 将结果注入对话历史，递归调用 AI

---

### 4.7 ConversationService（对话服务）

**定位**：处理对话历史准备、系统提示词组合、总结生成。

**核心职责**：
- 组合系统提示词（角色卡、工具提示词、功能提示词）
- 处理工作区附件、记忆注入
- 群聊编排模式支持
- 总结生成（调用 Summary 功能模型）
- 图片/音频/视频分析（调用对应功能模型）

---

### 4.8 ConversationRoundManager（轮次管理器）

**定位**：管理多轮对话中的内容分段。

**设计**：
- 每轮对话有独立的内容存储（`roundContents`）
- 支持 `startNewRound()` 开始新轮次
- 显示内容自动去除轮次分隔符

---

### 4.9 ConversationMarkupManager（对话标记管理器）

**定位**：生成标准化的对话标记（XML 格式）。

**主要功能**：
- 工具错误状态：`createToolErrorStatus()`
- 警告状态：`createWarningStatus()`
- 工具结果格式化：`formatToolResultForMessage()`
- 多工具结果聚合：`buildBoundedToolResultMessage()`

---

### 4.10 FileBindingService（文件绑定服务）

**定位**：处理 AI 生成代码与原始文件的智能合并。

**核心算法**：
- 解析 `[START-REPLACE]` / `[START-DELETE]` 编辑块
- 基于内容模糊匹配（非行号）定位修改位置
- 使用 LCS（最长公共子序列）算法计算相似度
- 支持并行搜索优化（大规模内容）

```kotlin
suspend fun processFileBinding(
    originalContent: String,
    aiGeneratedCode: String,
    onProgress: ((Float, String) -> Unit)? = null
): Pair<String, String>  // (合并后内容, diff字符串)
```

---

### 4.11 InputProcessor（输入处理器）

**定位**：用户输入预处理，通过 Hook 机制支持扩展。

```kotlin
object InputProcessor {
    suspend fun processUserInput(input: String, chatId: String? = null): String
}
```

处理流程：
1. 派发 `before_process` Hook
2. 获取处理后输入
3. 派发 `after_process` Hook
4. 返回最终输入

---

### 4.12 PromptHookRegistry（Prompt Hook 注册表）

**定位**：提供 Prompt 构建过程的多阶段扩展点。

**Hook 类型**：

| Hook 接口 | 触发时机 |
|-----------|----------|
| `PromptInputHook` | 用户输入处理阶段 |
| `PromptHistoryHook` | 对话历史准备阶段 |
| `PromptEstimateHistoryHook` | 窗口估算时的历史准备 |
| `SystemPromptComposeHook` | 系统提示词组合阶段 |
| `ToolPromptComposeHook` | 工具提示词组合阶段 |
| `PromptFinalizeHook` | Prompt 最终确认阶段 |
| `PromptEstimateFinalizeHook` | 窗口估算时的最终确认 |

**使用方式**：

```kotlin
// 注册 Hook
PromptHookRegistry.registerPromptInputHook(object : PromptInputHook {
    override val id = "my_input_hook"
    override fun onEvent(context: PromptHookContext): PromptHookMutation? {
        // 修改输入...
        return PromptHookMutation(processedInput = modifiedInput)
    }
})

// 注销 Hook
PromptHookRegistry.unregisterPromptInputHook("my_input_hook")
```

---

### 4.13 SummaryHookRegistry（总结 Hook 注册表）

**定位**：提供对话总结生成过程的扩展点。

```kotlin
interface SummaryGenerateHook {
    val id: String
    fun onEvent(context: SummaryHookContext): SummaryHookMutation?
}
```

---

### 4.14 MessageProcessingPluginRegistry（消息处理插件注册表）

**定位**：允许插件完全接管消息处理流程。

```kotlin
interface MessageProcessingPlugin {
    val id: String
    suspend fun createExecutionIfMatched(params: MessageProcessingHookParams): MessageProcessingExecution?
}

interface MessageProcessingController {
    fun cancel()
}

data class MessageProcessingExecution(
    val controller: MessageProcessingController,
    val stream: Stream<String>
)
```

插件在 `AIMessageManager.sendMessage()` 中被优先检查，如果匹配则完全替代默认流程。

---

### 4.15 MemoryLibrary（记忆库）

**定位**：分析对话内容并存储为结构化记忆图谱。

**核心能力**：
- 自动提取实体、关系、用户偏好
- 支持记忆的更新、合并、分类
- 异步保存，不阻塞主流程

```kotlin
object MemoryLibrary {
    fun saveMemoryAsync(...)
    fun autoCategorizeMemoriesAsync(context: Context, aiService: AIService)
}
```

---

## 5. 关键流程

### 5.1 消息发送完整流程

```
用户输入
  │
  ▼
AIMessageManager.buildUserMessageContent()
  ├─ InputProcessor.processUserInput()  [Hook: PromptInputHook]
  ├─ 构建回复标签 (reply_to)
  ├─ 构建工作区标签 (workspace_attachment)
  ├─ 构建附件标签 (attachment/link)
  └─ 组合最终消息
  │
  ▼
AIMessageManager.sendMessage()
  ├─ 从 chatHistory 提取记忆 (getMemoryFromMessages)
  ├─ 裁剪图片/媒体历史链接
  ├─ 匹配消息处理插件 (MessageProcessingPluginRegistry)
  │   └─ 如果匹配，插件接管流程
  └─ 否则进入默认流程
      │
      ▼
EnhancedAIService.sendMessage()
  ├─ 启动前台服务 (startAiService)
  ├─ 准备对话历史 (prepareConversationHistory)
  │   ├─ [Hook: PromptHistoryHook]
  │   ├─ 组合系统提示词 [Hook: SystemPromptComposeHook]
  │   ├─ 组合工具提示词 [Hook: ToolPromptComposeHook]
  │   └─ [Hook: PromptFinalizeHook]
  ├─ 获取模型参数
  ├─ 获取 AIService 实例
  ├─ 获取可用工具列表
  ├─ 估算请求窗口大小
  ├─ 发送请求到 LLM (AIService.sendMessage)
  │   └─ 流式接收响应
  ├─ 收集响应内容
  │   ├─ 修订追踪 (TextStreamRevisionTracker)
  │   └─ 更新 RoundManager
  │
  ▼
processStreamCompletion()
  ├─ 纯思考输出检测 → 注入警告并递归
  ├─ 增强工具检测 (enhanceToolDetection)
  ├─ 截断工具修复 (detectAndRepairTruncatedToolRound)
  ├─ 提取工具调用 (ToolExecutionManager.extractToolInvocations)
  │
  ├─ 如果有工具调用
  │   ├─ 执行工具 (ToolExecutionManager.executeInvocations)
  │   ├─ 格式化工具结果
  │   ├─ 注入结果到历史
  │   └─ 递归调用 processToolResults() → 再次请求 AI
  │
  └─ 如果没有工具调用
      └─ finalizeAssistantResponse()
          ├─ 自动保存记忆 (enableMemoryAutoUpdate)
          ├─ 发送回复通知
          └─ 停止前台服务
```

### 5.2 对话历史准备流程

```
prepareConversationHistory()
  │
  ├─ 获取后端识图/音频/视频服务配置
  ├─ 获取当前模型配置（是否支持 Tool Call、直接图片处理等）
  ├─ 调用 ConversationService.prepareConversationHistory()
  │   ├─ 应用角色卡系统提示词
  │   ├─ 应用功能提示词（CHAT/REASONING/TRANSLATE 等）
  │   ├─ 应用工具提示词（如果启用 Tool Call）
  │   ├─ 注入工作区内容
  │   ├─ 注入记忆内容
  │   ├─ 处理群聊编排提示词
  │   └─ 处理代理发送者标签
  │
  └─ 返回准备好的 PromptTurn 列表
```

### 5.3 工具执行流程

```
ToolExecutionManager.executeInvocations()
  │
  ├─ 权限校验（角色卡、CLI 模式）
  ├─ 解析代理工具（package_proxy / proxy）
  ├─ 并发执行所有工具
  │   ├─ 内置工具 → AIToolHandler
  │   ├─ 包工具 → PackageManager
  │   └─ JS 包工具 → 注入上下文参数
  │
  └─ 收集结果
      ├─ 成功 → `<tool_result name="xxx" status="success">`
      └─ 失败 → `<tool_result name="xxx" status="error">`
```

---

## 6. 使用示例

### 6.1 基础对话

```kotlin
// 获取服务实例
val aiService = EnhancedAIService.getInstance(context)

// 构建用户消息
val messageContent = AIMessageManager.buildUserMessageContent(
    messageText = "你好，请介绍一下自己",
    attachments = emptyList(),
    chatId = chatId
)

// 发送消息
val responseStream = AIMessageManager.sendMessage(
    enhancedAiService = aiService,
    chatId = chatId,
    messageContent = messageContent,
    chatHistory = chatHistory,
    workspacePath = null,
    promptFunctionType = PromptFunctionType.CHAT,
    enableThinking = false,
    enableMemoryAutoUpdate = true,
    maxTokens = 8192,
    tokenUsageThreshold = 0.8,
    onNonFatalError = { error ->
        Log.e("Chat", "非致命错误: $error")
    },
    roleCardId = "default"
)

// 收集流式响应
responseStream.collect { chunk ->
    // 更新 UI
    appendToChatBubble(chunk)
}
```

### 6.2 带工具调用的对话

```kotlin
val responseStream = AIMessageManager.sendMessage(
    enhancedAiService = aiService,
    // ... 其他参数
    onToolInvocation = { toolName ->
        // UI 显示正在执行工具
        showToolExecutingIndicator(toolName)
    }
)
```

当 AI 响应包含工具调用时，系统会自动：
1. 暂停流输出
2. 执行工具
3. 将结果注入对话历史
4. 再次请求 AI 获取最终响应

### 6.3 生成对话总结

```kotlin
val summaryMessage = AIMessageManager.summarizeMemory(
    enhancedAiService = aiService,
    messages = chatMessages,
    autoContinue = false,
    isGroupChat = false
)

if (summaryMessage != null) {
    // 将总结消息插入到对话历史
    insertSummaryMessage(summaryMessage)
}
```

### 6.4 取消操作

```kotlin
// 取消当前活跃对话
AIMessageManager.cancelCurrentOperation()

// 取消指定对话
AIMessageManager.cancelOperation(chatId)

// 取消所有对话
AIMessageManager.cancelAllOperations()
```

### 6.5 注册 Prompt Hook

```kotlin
// 注册输入处理 Hook
PromptHookRegistry.registerPromptInputHook(object : PromptInputHook {
    override val id = "custom_input_processor"
    
    override fun onEvent(context: PromptHookContext): PromptHookMutation? {
        val input = context.rawInput ?: return null
        // 自定义处理逻辑
        val processed = input.replace("自定义命令", "替换内容")
        return PromptHookMutation(processedInput = processed)
    }
})

// 注册系统提示词 Hook
PromptHookRegistry.registerSystemPromptComposeHook(object : SystemPromptComposeHook {
    override val id = "custom_system_prompt"
    
    override fun onEvent(context: PromptHookContext): PromptHookMutation? {
        val currentPrompt = context.systemPrompt ?: ""
        val enhancedPrompt = currentPrompt + "\n[额外指令] 请使用中文回复"
        return PromptHookMutation(systemPrompt = enhancedPrompt)
    }
})
```

### 6.6 注册消息处理插件

```kotlin
MessageProcessingPluginRegistry.register(object : MessageProcessingPlugin {
    override val id = "custom_plugin"
    
    override suspend fun createExecutionIfMatched(
        params: MessageProcessingHookParams
    ): MessageProcessingExecution? {
        // 判断是否匹配
        if (!params.messageContent.startsWith("/custom")) {
            return null
        }
        
        // 创建自定义流
        val stream = stream {
            emit("自定义插件响应")
        }
        
        return MessageProcessingExecution(
            controller = object : MessageProcessingController {
                override fun cancel() {
                    // 取消逻辑
                }
            },
            stream = stream
        )
    }
})
```

### 6.7 文件绑定

```kotlin
val (mergedContent, diff) = EnhancedAIService.applyFileBinding(
    context = context,
    originalContent = originalFileContent,
    aiGeneratedCode = aiGeneratedCode
)

// mergedContent: 合并后的文件内容
// diff: 差异字符串
```

### 6.8 估算请求窗口

```kotlin
val windowSize = AIMessageManager.calculateStableContextWindow(
    enhancedAiService = aiService,
    chatId = chatId,
    messageContent = messageContent,
    chatHistory = chatHistory,
    promptFunctionType = PromptFunctionType.CHAT,
    roleCardId = roleCardId
)

// windowSize: 估算的 Token 数量
```

---

## 7. 扩展机制

### 7.1 Hook 系统扩展

Hook 系统支持在 Prompt 构建的 7 个阶段进行干预：

1. **输入处理**：修改用户原始输入
2. **历史处理**：修改对话历史（如过滤、裁剪、重排）
3. **估算历史**：窗口估算时的历史处理
4. **系统提示词**：修改系统提示词内容
5. **工具提示词**：修改工具描述
6. **最终确认**：发送前的最后修改
7. **估算最终确认**：窗口估算时的最后修改

每个 Hook 返回 `PromptHookMutation`，只包含需要修改的字段，未修改字段保持原值。

### 7.2 插件系统扩展

消息处理插件可以完全替代默认的 AI 请求流程：

- **匹配条件**：由插件自行判断
- **接管流程**：返回 `MessageProcessingExecution` 替代默认流程
- **流式响应**：通过 `Stream<String>` 返回内容
- **取消控制**：通过 `MessageProcessingController` 支持取消

### 7.3 新增 LLM 提供商

实现 `AIService` 接口：

```kotlin
class MyProvider(private val config: ModelConfigData) : AIService {
    override val inputTokenCount: Int = 0
    override val cachedInputTokenCount: Int = 0
    override val outputTokenCount: Int = 0
    override val providerModel: String = "MY_PROVIDER:model-name"
    
    override fun resetTokenCounts() { }
    override fun cancelStreaming() { }
    
    override suspend fun sendMessage(
        context: Context,
        chatHistory: List<PromptTurn>,
        modelParameters: List<ModelParameter<*>>,
        enableThinking: Boolean,
        stream: Boolean,
        availableTools: List<ToolPrompt>?,
        preserveThinkInHistory: Boolean,
        onTokensUpdated: suspend (input: Int, cachedInput: Int, output: Int) -> Unit,
        onNonFatalError: suspend (error: String) -> Unit,
        enableRetry: Boolean
    ): Stream<String> {
        // 实现发送逻辑
    }
    
    override suspend fun testConnection(context: Context): Result<String> {
        // 实现连接测试
    }
    
    override suspend fun calculateInputTokens(
        chatHistory: List<PromptTurn>,
        availableTools: List<ToolPrompt>?
    ): Int {
        // 实现 Token 计算
    }
}
```

然后在 `AIServiceFactory` 中注册新提供商。

---

## 8. 最佳实践

### 8.1 生命周期管理

- 每个 `chatId` 使用独立的 `EnhancedAIService` 实例（通过 `getChatInstance`）
- 聊天结束后调用 `releaseChatInstance(chatId)` 释放资源
- 使用 `AIMessageManager.cancelOperation(chatId)` 取消指定对话

### 8.2 性能优化

- 启用流式输出（`stream = true`）以获得更好的响应体验
- 合理设置 `maxTokens` 和 `tokenUsageThreshold` 控制成本
- 使用历史裁剪减少 Token 消耗
- 工具结果设置合理的截断上限

### 8.3 错误处理

- 始终提供 `onNonFatalError` 回调处理非致命错误
- 使用 `onTokenLimitExceeded` 处理 Token 超限情况
- 捕获 `CancellationException` 处理用户取消

### 8.4 安全与权限

- 角色卡工具权限通过 `CharacterCardToolAccessResolver` 控制
- CLI 模式限制工具暴露范围
- JS 包工具自动注入调用上下文（调用者名称、chatId、角色卡ID）

### 8.5 调试与监控

- 启用 `messageTimingNow()` 日志监控各阶段耗时
- 使用 `perRequestTokenCounts` StateFlow 监控 Token 使用
- 通过 `inputProcessingState` 跟踪处理状态

---

## 附录：主要类图

```
┌─────────────────────┐
│   AIMessageManager  │◄────── 统一入口
│   (Object, 单例)    │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐     ┌─────────────────────┐
│  EnhancedAIService  │◄────┤  ChatRuntimeHolder  │
│  (单例 + chat隔离)   │     │  (MAIN/FLOATING)    │
└──────────┬──────────┘     └─────────────────────┘
           │
     ┌─────┴─────┐
     ▼           ▼
┌─────────┐  ┌─────────────┐
│MultiService│  │ToolExecution│
│Manager   │  │Manager      │
└────┬────┘  └──────┬──────┘
     │              │
     ▼              ▼
┌─────────┐  ┌─────────────┐
│AIService│  │AIToolHandler│
│(接口)   │  │PackageManager│
└────┬────┘  └─────────────┘
     │
     ▼
┌─────────────────────────────┐
│ 各 LLM 提供商实现            │
│ (OpenAI/Claude/Gemini/...)  │
└─────────────────────────────┘
```

---

*文档生成时间：2026-05-16*
*基于 Operit 项目 chat 模块源代码分析*
