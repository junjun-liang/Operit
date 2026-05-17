# Enhance 模块设计思想与详细使用指南

## 目录

1. [模块概述](#1-模块概述)
2. [核心设计思想](#2-核心设计思想)
3. [架构与代码结构](#3-架构与代码结构)
4. [ConversationService 对话服务](#4-conversationservice-对话服务)
5. [ConversationRoundManager 轮次管理](#5-conversationroundmanager-轮次管理)
6. [ConversationMarkupManager 标记管理](#6-conversationmarkupmanager-标记管理)
7. [MultiServiceManager 多服务管理](#7-multiservicemanager-多服务管理)
8. [ToolExecutionManager 工具执行](#8-toolexecutionmanager-工具执行)
9. [InputProcessor 输入处理](#9-inputprocessor-输入处理)
10. [FileBindingService 文件绑定](#10-filebindingservice-文件绑定)
11. [ReferenceManager 引用管理](#11-referencemanager-引用管理)
12. [使用示例](#12-使用示例)
13. [最佳实践](#13-最佳实践)

---

## 1. 模块概述

Enhance 模块是 Operit 项目的**对话增强层**，位于 LLMProvider 之上，负责处理对话生命周期中的高级功能。它将原始的 AI 服务调用包装成完整的对话体验，包括历史管理、工具调用链、多服务协调、文件操作等。

### 1.1 核心能力

| 功能 | 说明 |
|------|------|
| **对话历史准备** | 构建系统提示词、处理角色卡、注入用户偏好 |
| **对话总结** | 自动总结长对话，支持增量总结 |
| **工具执行链** | 解析 XML 工具调用、执行工具、处理结果 |
| **多服务管理** | 不同功能使用不同模型（聊天/总结/识图/翻译） |
| **文件绑定** | AI 生成代码的模糊匹配补丁应用 |
| **轮次管理** | 跟踪多轮对话，处理工具调用后的连续响应 |
| **输入处理** | 用户输入的预处理和钩子扩展 |

### 1.2 在架构中的位置

```
UI 层 (Compose)
    │
    ▼
ViewModel (状态管理)
    │
    ▼
Enhance 模块 (对话增强)
    │── ConversationService (对话服务)
    │── ToolExecutionManager (工具执行)
    │── MultiServiceManager (多服务协调)
    │── FileBindingService (文件操作)
    │── ...
    │
    ▼
LLMProvider 模块 (AI 服务接入)
    │── OpenAIProvider/ClaudeProvider/...
    │
    ▼
网络/本地推理引擎
```

---

## 2. 核心设计思想

### 2.1 分层处理管道

Enhance 模块采用**管道模式**处理对话请求，每个阶段负责特定的增强功能：

```
用户输入
    │
    ▼
┌─────────────────┐
│ InputProcessor  │  ──▶ 输入预处理（钩子扩展）
└─────────────────┘
    │
    ▼
┌─────────────────────────┐
│ ConversationService     │  ──▶ 构建对话历史
│ - 系统提示词组装         │
│ - 角色卡注入             │
│ - 用户偏好插入           │
│ - 工具提示词添加         │
└─────────────────────────┘
    │
    ▼
┌─────────────────────────┐
│ MultiServiceManager     │  ──▶ 选择对应功能的 AI 服务
│ - 聊天 → Chat 模型       │
│ - 总结 → Summary 模型    │
│ - 识图 → Vision 模型     │
└─────────────────────────┘
    │
    ▼
┌─────────────────────────┐
│ LLMProvider             │  ──▶ 调用 AI 服务
└─────────────────────────┘
    │
    ▼
┌─────────────────────────┐
│ ToolExecutionManager    │  ──▶ 解析/执行工具调用
│ - XML 解析               │
│ - 权限检查               │
│ - 并行/串行执行          │
│ - 结果格式化             │
└─────────────────────────┘
    │
    ▼
┌─────────────────────────┐
│ ConversationRoundManager│  ──▶ 管理对话轮次
│ - 跟踪当前轮次           │
│ - 合并连续消息           │
└─────────────────────────┘
    │
    ▼
响应输出
```

### 2.2 Hook 扩展机制

Enhance 模块大量使用 **Hook 机制** 实现可扩展性。Hook 系统采用**"上下文 + 变更"模式**：Hook 接收只读上下文，返回可选的变更对象，多个 Hook 链式调用，每个 Hook 的输出作为下一个 Hook 的输入。

#### 2.2.1 两套 Hook 注册表

| 注册表 | 所在包 | 用途 |
|--------|--------|------|
| `PromptHookRegistry` | `core.chat.hooks` | 对话历史准备的 7 个阶段 |
| `SummaryHookRegistry` | `core.chat.hooks` | 总结生成的各阶段 |

此外还有 `MessageProcessingPluginRegistry`（`core.chat.plugins`），属于**插件机制**而非 Hook，允许完全接管消息处理流程。

#### 2.2.2 PromptHookRegistry — 7 阶段 Hook

Prompt 构建过程分为 7 个阶段，每个阶段有独立的 Hook 接口和注册表：

| 阶段 | Hook 接口 | 触发时机 | 可变更字段 |
|------|----------|---------|-----------|
| 输入处理 | `PromptInputHook` | 用户输入预处理 | `rawInput`, `processedInput` |
| 历史准备 | `PromptHistoryHook` | 对话历史裁剪前/后 | `chatHistory`, `preparedHistory` |
| 估算历史 | `PromptEstimateHistoryHook` | Token 估算时的历史裁剪 | `chatHistory`, `preparedHistory` |
| 系统提示词 | `SystemPromptComposeHook` | 系统提示词组装时 | `systemPrompt` |
| 工具提示词 | `ToolPromptComposeHook` | 工具描述组装时 | `toolPrompt` |
| 最终确认 | `PromptFinalizeHook` | 所有内容组装完毕后 | 全部字段 |
| 估算确认 | `PromptEstimateFinalizeHook` | Token 估算的最终确认 | 全部字段 |

**调用位置**：

```
InputProcessor.processInput()
    └─ PromptHookRegistry.dispatchPromptInputHooks(context)

ConversationService.prepareConversationHistory()
    └─ PromptHookRegistry.dispatchPromptHistoryHooks(context)

EnhancedAIService.sendMessage()
    └─ PromptHookRegistry.dispatchPromptFinalizeHooks(context)

EnhancedAIService.estimateTokenCount()
    ├─ PromptHookRegistry.dispatchPromptEstimateHistoryHooks(context)
    └─ PromptHookRegistry.dispatchPromptEstimateFinalizeHooks(context)
```

#### 2.2.3 SummaryHookRegistry — 总结生成 Hook

| 阶段 | Hook 接口 | 触发时机 | 可变更字段 |
|------|----------|---------|-----------|
| 总结生成 | `SummaryGenerateHook` | 总结生成各阶段 | `chatHistory`, `preparedHistory`, `systemPrompt`, `summaryPrompt`, `summaryResult` |

**调用位置**：

```
ConversationService.generateSummary()
    ├─ before_prepare_history → SummaryHookRegistry.dispatchSummaryGenerateHooks(context)
    ├─ after_prepare_history  → SummaryHookRegistry.dispatchSummaryGenerateHooks(context)
    └─ after_generate_summary → SummaryHookRegistry.dispatchSummaryGenerateHooks(context)
```

#### 2.2.4 上下文与变更模型

**PromptHookContext**（只读上下文）：

```kotlin
data class PromptHookContext(
    val stage: String,                    // 当前阶段名
    val chatId: String? = null,           // 对话 ID
    val functionType: String? = null,     // 功能类型
    val promptFunctionType: String? = null,
    val useEnglish: Boolean? = null,
    val rawInput: String? = null,         // 原始输入
    val processedInput: String? = null,   // 处理后输入
    val chatHistory: List<PromptTurn>,    // 原始对话历史
    val preparedHistory: List<PromptTurn>,// 裁剪后历史
    val systemPrompt: String? = null,     // 系统提示词
    val toolPrompt: String? = null,       // 工具提示词
    val modelParameters: List<Map<String, Any?>>,
    val availableTools: List<Map<String, Any?>>,
    val metadata: Map<String, Any?>       // 扩展元数据
)
```

**PromptHookMutation**（变更对象，所有字段可选，null 表示不修改）：

```kotlin
data class PromptHookMutation(
    val rawInput: String? = null,
    val processedInput: String? = null,
    val chatHistory: List<PromptTurn>? = null,
    val preparedHistory: List<PromptTurn>? = null,
    val systemPrompt: String? = null,
    val toolPrompt: String? = null,
    val metadata: Map<String, Any?> = emptyMap()  // 合并到现有 metadata
)
```

#### 2.2.5 链式调用机制

Hook 的分发采用**管道模式**，多个 Hook 依次执行：

```
初始 Context
    │
    ├─ Hook A.onEvent(context) → Mutation A
    │   └─ applyMutation(context, mutationA) → Context A'
    │
    ├─ Hook B.onEvent(contextA') → Mutation B
    │   └─ applyMutation(contextA', mutationB) → Context B'
    │
    ├─ Hook C.onEvent(contextB') → null（不修改）
    │   └─ 跳过，Context B' 不变
    │
    └─ 最终 Context B'
```

**关键特性**：
- Hook 返回 `null` 表示不修改任何字段，直接跳过
- `applyMutation` 采用**非空覆盖**策略：`mutation.field ?: current.field`
- `metadata` 采用**合并**策略：`current.metadata + mutation.metadata`
- 单个 Hook 异常不会中断链式调用（`runCatching` 捕获并记录日志）

#### 2.2.6 PromptTurn — 统一对话历史模型

所有 Hook 操作的对话历史都是 `List<PromptTurn>`：

```kotlin
data class PromptTurn(
    val kind: PromptTurnKind,    // SYSTEM / USER / ASSISTANT / TOOL_CALL / TOOL_RESULT / SUMMARY
    val content: String,         // 消息内容
    val toolName: String? = null,// 工具名（TOOL_CALL/TOOL_RESULT 时使用）
    val metadata: Map<String, Any?> = emptyMap()
)
```

**扩展函数**：

| 函数 | 说明 |
|------|------|
| `withContent(newContent)` | 替换内容（内容相同时返回原对象） |
| `appendUserTurnIfMissing(message)` | 如果最后一条不是该用户消息则追加 |
| `mergeAdjacentTurns()` | 合并相邻的同类型消息（SYSTEM/TOOL_CALL/TOOL_RESULT 不合并） |
| `toRoleContentPairs()` | 转为 `List<Pair<String, String>>` |
| `List<Pair<String, String>>.toPromptTurns()` | 从角色-内容对列表构建 |

#### 2.2.7 MessageProcessingPluginRegistry — 消息处理插件

与 Hook 的**通知/修改**模式不同，`MessageProcessingPlugin` 是**完全接管**模式：

```kotlin
interface MessageProcessingPlugin {
    val id: String
    suspend fun createExecutionIfMatched(
        params: MessageProcessingHookParams
    ): MessageProcessingExecution?
}

data class MessageProcessingExecution(
    val controller: MessageProcessingController,  // 支持 cancel()
    val stream: Stream<String>                    // 自定义流式响应
)
```

**工作方式**：
1. `AIMessageManager` 在发送消息前，先遍历所有插件
2. 第一个返回非 null `MessageProcessingExecution` 的插件**完全接管**消息处理
3. 后续插件和默认流程不再执行
4. 如果所有插件都不匹配，走默认流程

**调用位置**：

```
AIMessageManager.sendMessage()
    ├─ MessageProcessingPluginRegistry.createExecutionIfMatched(params)
    │   ├─ 匹配成功 → 使用插件的 stream 作为响应
    │   └─ 全部不匹配 → 走默认 EnhancedAIService 流程
```

#### 2.2.8 ToolPkg 桥接层

ToolPkg 通过 `ToolPkgPromptHookBridge` 和 `ToolPkgSummaryHookBridge` 将 JS 工具包的 Hook 函数桥接到 Kotlin Hook 注册表：

```
ToolPkg JS 脚本注册的 Hook
    │
    ├─ ToolPkgPromptHookBridge（7 个 Bridge 对象）
    │   ├─ PromptInputBridge : PromptInputHook
    │   ├─ PromptHistoryBridge : PromptHistoryHook
    │   ├─ PromptEstimateHistoryBridge : PromptEstimateHistoryHook
    │   ├─ SystemPromptComposeBridge : SystemPromptComposeHook
    │   ├─ ToolPromptComposeBridge : ToolPromptComposeHook
    │   ├─ PromptFinalizeBridge : PromptFinalizeHook
    │   └─ PromptEstimateFinalizeBridge : PromptEstimateFinalizeHook
    │
    ├─ ToolPkgSummaryHookBridge（1 个 Bridge 对象）
    │   └─ SummaryGenerateBridge : SummaryGenerateHook
    │
    └─ Bridge 内部流程：
        ├─ 从 PackageManager 获取已启用容器的 Hook 注册信息
        ├─ 将 PromptHookContext 序列化为 Map 传给 JS 函数
        ├─ PackageManager.runToolPkgMainHook() 执行 JS Hook 函数
        ├─ 解析 JS 返回值（String/JSONArray/JSONObject）
        └─ 转换为 PromptHookMutation / SummaryHookMutation
```

**JS Hook 返回值解析规则**：

| Hook 类型 | 返回 String | 返回 JSONArray | 返回 JSONObject |
|-----------|------------|---------------|----------------|
| InputHook | → `processedInput` | — | 解析所有字段 |
| HistoryHook | — | → `chatHistory`/`preparedHistory` | 解析所有字段 |
| SystemPromptHook | → `systemPrompt` | — | 解析所有字段 |
| ToolPromptHook | → `toolPrompt` | — | 解析所有字段 |
| FinalizeHook | → `processedInput` | → `preparedHistory` | 解析所有字段 |
| SummaryHook | → `summaryPrompt`/`summaryResult` | — | 解析所有字段 |

**动态同步**：Bridge 监听 `PackageManager.ToolPkgRuntimeChangeListener`，当容器启用/禁用时自动更新 Hook 注册列表。

#### 2.2.9 注册与使用示例

```kotlin
// 注册 PromptInputHook
PromptHookRegistry.registerPromptInputHook(object : PromptInputHook {
    override val id = "my_input_processor"

    override fun onEvent(context: PromptHookContext): PromptHookMutation? {
        val input = context.rawInput ?: return null
        if (input.startsWith("/translate ")) {
            return PromptHookMutation(
                processedInput = "请翻译以下内容：${input.removePrefix("/translate ")}",
                metadata = mapOf("translation_mode" to true)
            )
        }
        return null
    }
})

// 注册 SystemPromptComposeHook
PromptHookRegistry.registerSystemPromptComposeHook(object : SystemPromptComposeHook {
    override val id = "my_system_prompt_enhancer"

    override fun onEvent(context: PromptHookContext): PromptHookMutation? {
        val current = context.systemPrompt ?: return null
        return PromptHookMutation(
            systemPrompt = "$current\n\n额外规则：始终使用中文回复。"
        )
    }
})

// 注册 MessageProcessingPlugin
MessageProcessingPluginRegistry.register(object : MessageProcessingPlugin {
    override val id = "my_echo_plugin"

    override suspend fun createExecutionIfMatched(
        params: MessageProcessingHookParams
    ): MessageProcessingExecution? {
        if (params.messageContent.startsWith("/echo ")) {
            val text = params.messageContent.removePrefix("/echo ")
            return MessageProcessingExecution(
                controller = object : MessageProcessingController {
                    override fun cancel() {}
                },
                stream = Stream.of(text)
            )
        }
        return null
    }
})

// 注销 Hook
PromptHookRegistry.unregisterPromptInputHook("my_input_processor")
MessageProcessingPluginRegistry.unregister("my_echo_plugin")
```

#### 2.2.10 线程安全与设计保障

| 特性 | 实现方式 |
|------|---------|
| 注册表线程安全 | `CopyOnWriteArrayList` + `@Synchronized` 注册/注销 |
| Hook 异常隔离 | `runCatching` 捕获，记录日志，不中断链式调用 |
| 不可变上下文 | `PromptHookContext` 和 `SummaryHookContext` 为 `data class`，每次 `copy` 产生新实例 |
| ID 去重 | 注册时先按 `id` 注销旧 Hook，确保同一 ID 只有一个实例 |
| ToolPkg 动态同步 | `@Volatile` + `ToolPkgRuntimeChangeListener`，容器变更时原子更新 |

### 2.3 多服务分工

不同功能使用不同的 AI 模型配置，实现**专业化分工**：

```
功能类型              模型用途                    配置示例
─────────           ──────────                  ─────────
CHAT                主对话模型                   GPT-4o / DeepSeek-V3
SUMMARY             对话总结（便宜/快速模型）     GPT-3.5 / 轻量本地模型
IMAGE_RECOGNITION   图片分析（视觉模型）          GPT-4o-vision / Qwen-VL
AUDIO_RECOGNITION   音频分析                    Whisper / 专用音频模型
VIDEO_RECOGNITION   视频分析                    Gemini Pro Vision
TRANSLATION         翻译（快速模型）              GPT-3.5 / 轻量模型
```

---

## 3. 架构与代码结构

### 3.1 文件组织

```
app/src/main/java/com/ai/assistance/operit/api/chat/enhance/
├── ConversationService.kt          # 核心对话服务
├── ConversationRoundManager.kt     # 对话轮次管理
├── ConversationMarkupManager.kt    # 对话标记/XML格式化
├── MultiServiceManager.kt          # 多服务管理器
├── ToolExecutionManager.kt         # 工具执行管理
├── InputProcessor.kt               # 输入处理器
├── FileBindingService.kt           # 文件绑定/补丁服务
└── ReferenceManager.kt             # 引用提取器
```

### 3.2 核心类图

```
┌─────────────────────────────────────────────────────────────┐
│                   ConversationService                        │
│  ── 对话历史准备、总结生成、翻译、识图等高级功能              │
│                                                              │
│  + prepareConversationHistory() → List<PromptTurn>          │
│  + generateSummary() → String                               │
│  + translateText() → String                                 │
│  + analyzeImageWithIntent() → String                        │
│  + processChatMessageWithTools()                            │
└─────────────────────────────────────────────────────────────┘
        │                           │
        ▼                           ▼
┌───────────────┐         ┌─────────────────┐
│ MultiService  │         │ ToolExecution   │
│ Manager       │         │ Manager         │
│ ── 服务路由    │         │ ── 工具解析执行  │
└───────────────┘         └─────────────────┘
        │                           │
        ▼                           ▼
┌───────────────┐         ┌─────────────────┐
│ AIService     │         │ ToolExecutor    │
│ (LLMProvider) │         │ (工具实现)       │
└───────────────┘         └─────────────────┘
```

---

## 4. ConversationService 对话服务

### 4.1 对话历史准备

`prepareConversationHistory()` 是核心方法，负责组装完整的对话历史：

```kotlin
suspend fun prepareConversationHistory(
    chatHistory: List<PromptTurn>,      // 原始聊天历史
    processedInput: String,              // 处理后的用户输入
    chatId: String?,                     // 聊天ID
    workspacePath: String?,              // 工作区路径
    packageManager: PackageManager,      // 包管理器
    promptFunctionType: PromptFunctionType, // 功能类型
    customSystemPromptTemplate: String? = null,  // 自定义系统提示模板
    roleCardId: String? = null,          // 角色卡ID
    enableGroupOrchestrationHint: Boolean = false, // 群聊编排
    hasImageRecognition: Boolean = false, // 是否配置识图
    hasAudioRecognition: Boolean = false, // 是否配置音频
    hasVideoRecognition: Boolean = false, // 是否配置视频
    useToolCallApi: Boolean = false,     // 是否使用Tool Call API
    toolExposureMode: ToolExposureMode = ToolExposureMode.FULL, // 工具暴露模式
    // ... 更多参数
): List<PromptTurn>
```

### 4.2 系统提示词组装流程

```
1. 检查是否已有 SYSTEM 消息
   └─ 没有则继续组装

2. 获取角色卡信息
   └─ roleCardId → CharacterCardManager → 组合提示词

3. 获取用户偏好
   └─ PreferenceProfile → 性别/年龄/性格/职业/AI风格

4. 构建系统提示词
   └─ SystemPromptConfig.getSystemPromptWithCustomPrompts()
      ├─ 工作区路径/环境变量
      ├─ SAF 书签名称
      ├─ 角色卡 intro 提示词
      ├─ 自定义系统提示模板
      ├─ 工具启用状态
      ├─ 多模态支持标志
      ├─ 工具暴露模式
      ├─ 群聊编排提示
      └─ 工具可见性控制

5. 添加特殊模式规则
   ├─ Waifu 模式规则（表情/自拍/自定义）
   └─ 语音头像 Mood 规则

6. 组装最终系统提示词
   └─ avatarMoodRules + systemPrompt + waifuRules + userPreferences

7. 替换占位符
   └─ {{user}} → 全局用户名
   └─ {{char}} → AI 名称
```

### 4.3 消息处理与工具结果规范化

```kotlin
// 处理包含 XML 标签的助手消息
fun processChatMessageWithTools(
    content: String,
    xmlTags: List<List<String>>,
    conversationHistory: MutableList<PromptTurn>,
    messageIndex: Int,
    totalMessages: Int
)
```

**标签类型映射**：

| XML 标签 | PromptTurnKind | 说明 |
|---------|---------------|------|
| `<text>` | ASSISTANT | 纯文本内容 |
| `<think>` / `<thinking>` | ASSISTANT | 推理内容（DeepSeek） |
| `<status type="complete">` | ASSISTANT | 任务完成状态 |
| `<status type="wait_for_user_need">` | ASSISTANT | 等待用户输入 |
| `<status>` (其他) | USER | 其他状态算作用户消息 |
| `<tool_result>` | TOOL_RESULT | 工具执行结果 |
| `<tool>` | TOOL_CALL | 工具调用 |

### 4.4 对话总结

```kotlin
suspend fun generateSummary(
    messages: List<PromptTurn>,
    previousSummary: String?,        // 上一次总结（增量总结）
    multiServiceManager: MultiServiceManager
): String
```

**总结生成流程**：

```
1. 构建总结提示词
   └─ FunctionalPrompts.buildSummarySystemPrompt(previousSummary)

2. Hook 扩展点
   ├─ before_prepare_summary_prompt
   ├─ before_send_to_model
   └─ after_generate_summary

3. 分阶段进度跟踪
   ├─ 0.05 准备中
   ├─ 0.20 撰写标题
   ├─ 0.40 核心任务
   ├─ 0.55 交互方式
   ├─ 0.70 进展状态
   ├─ 0.85 关键信息
   └─ 0.95 完成中

4. 使用 SUMMARY 功能类型的 AI 服务生成

5. 清理思考内容，返回最终总结
```

### 4.5 多模态分析

```kotlin
// 图片分析
suspend fun analyzeImageWithIntent(
    imagePath: String,
    userIntent: String?,           // 用户意图，如"图片里有什么"
    multiServiceManager: MultiServiceManager
): String

// 音频分析
suspend fun analyzeAudioWithIntent(
    audioPath: String,
    userIntent: String?,
    multiServiceManager: MultiServiceManager
): String

// 视频分析
suspend fun analyzeVideoWithIntent(
    videoPath: String,
    userIntent: String?,
    multiServiceManager: MultiServiceManager
): String
```

---

## 5. ConversationRoundManager 轮次管理

### 5.1 设计目的

管理多轮对话中的内容累积，特别是工具调用后的连续响应：

```
Round 0: 用户: "Hello"
         AI: "Hi! How can I help?"

Round 1: 用户: "Search for Kotlin docs"
         AI: "<tool name="search">...</tool>"  ← 工具调用
         
Round 2: (工具结果注入)
         AI: "Here are the Kotlin docs..."      ← 基于工具结果的新响应
```

### 5.2 核心 API

```kotlin
class ConversationRoundManager {
    /** 开始新对话，重置所有跟踪 */
    fun initializeNewConversation()

    /** 开始新一轮 */
    fun startNewRound(): Int

    /** 更新当前轮次内容 */
    fun updateContent(content: String): String

    /** 追加内容 */
    fun appendContent(content: String): String

    /** 获取显示内容（无轮次分隔符） */
    fun getDisplayContent(): String

    /** 获取原始内容（含轮次分隔符） */
    fun getRawContent(): String

    /** 获取当前轮次 */
    fun getCurrentRound(): Int
}
```

---

## 6. ConversationMarkupManager 标记管理

### 6.1 工具结果格式化

将工具执行结果格式化为标准化的 XML：

```kotlin
// 成功结果
<tool_result_xyz name="search" status="success">
  <content>搜索结果内容...</content>
</tool_result_xyz>

// 错误结果
<tool_result_abc name="search" status="error">
  <content><error>搜索失败: 网络错误</error></content>
</tool_result_abc>
```

### 6.2 核心 API

```kotlin
object ConversationMarkupManager {
    /** 创建工具错误状态 */
    fun createToolErrorStatus(toolName: String, errorMessage: String): String

    /** 创建警告状态 */
    fun createWarningStatus(warningMessage: String): String

    /** 格式化工具结果 */
    fun formatToolResultForMessage(result: ToolResult): String

    /** 批量构建工具结果消息 */
    fun buildBoundedToolResultMessage(results: List<ToolResult>): String

    /** 多工具警告 */
    fun createMultipleToolsWarning(context: Context, toolName: String): String

    /** 工具不可用错误 */
    fun createToolNotAvailableError(toolName: String, details: String? = null): String
}
```

---

## 7. MultiServiceManager 多服务管理

### 7.1 功能类型映射

```kotlin
enum class FunctionType {
    CHAT,               // 主对话
    SUMMARY,            // 对话总结
    IMAGE_RECOGNITION,  // 图片识别
    AUDIO_RECOGNITION,  // 音频识别
    VIDEO_RECOGNITION,  // 视频识别
    TRANSLATION         // 翻译
}
```

### 7.2 服务缓存与生命周期

```kotlin
class MultiServiceManager(private val context: Context) {
    // 功能类型 → AIService 缓存
    private val serviceInstances = mutableMapOf<FunctionType, AIService>()
    
    // 自定义配置 → AIService 缓存
    private val customServiceInstances = mutableMapOf<String, AIService>()

    /** 获取指定功能类型的服务 */
    suspend fun getServiceForFunction(functionType: FunctionType): AIService

    /** 刷新指定功能的服务（配置变更后） */
    suspend fun refreshServiceForFunction(functionType: FunctionType)

    /** 刷新所有服务 */
    suspend fun refreshAllServices()

    /** 取消所有流式传输 */
    suspend fun cancelAllStreaming()

    /** 获取模型参数 */
    suspend fun getModelParametersForFunction(functionType: FunctionType): List<ModelParameter<*>>
}
```

### 7.3 服务创建流程

```
1. 查询 FunctionalConfigManager 获取功能映射
   └─ FunctionType.SUMMARY → configId "summary-model-config"

2. 从 ModelConfigManager 获取配置
   └─ configId → ModelConfigData

3. 创建 AIService
   └─ AIServiceFactory.createService(config)

4. 包装限流器（如配置）
   └─ RateLimitedAIService(delegate, rateLimiter, semaphore)

5. 缓存服务实例
   └─ serviceInstances[functionType] = service
```

---

## 8. ToolExecutionManager 工具执行

### 8.1 工具调用解析

从 AI 响应中提取 XML 格式的工具调用：

```kotlin
suspend fun extractToolInvocations(response: String): List<ToolInvocation>
```

**解析流程**：

```
AI 响应文本
    │
    ▼
Stream<Char>.splitBy(StreamXmlPlugin)  ──▶ 分割 XML 标签
    │
    ▼
Regex: <tool name="xxx">...</tool>      ──▶ 提取工具名和参数
    │
    ▼
List<ToolInvocation>
    ├─ tool: AITool (name, parameters)
    ├─ rawText: 原始匹配文本
    └─ responseLocation: 在响应中的位置
```

### 8.2 工具执行管道

```kotlin
suspend fun executeInvocations(
    invocations: List<ToolInvocation>,
    context: Context,
    toolHandler: AIToolHandler,
    packageManager: PackageManager,
    collector: StreamCollector<String>,  // 实时输出收集器
    toolExposureMode: ToolExposureMode = ToolExposureMode.FULL,
    callerName: String? = null,           // 调用者名称
    callerChatId: String? = null,         // 聊天ID
    callerCardId: String? = null          // 角色卡ID
): List<ToolResult>
```

**执行流程**：

```
1. 工具暴露模式拦截
   ├─ CLI 模式: 只允许 CLI 公开工具
   └─ FULL 模式: 允许所有工具

2. 角色卡工具权限拦截
   └─ 检查角色卡是否有权限执行该工具

3. 权限检查
   └─ 弹出权限请求界面（如需要）

4. 注入包调用上下文
   └─ 为 JS 包工具添加 __operit_package_caller_name 等参数

5. 分组执行
   ├─ 并行工具: list_files, read_file, calculate, visit_web 等
   └─ 串行工具: 其他工具（有依赖关系）

6. 结果聚合
   └─ 按原始顺序返回所有结果
```

### 8.3 权限检查

```kotlin
suspend fun checkToolPermission(
    toolHandler: AIToolHandler,
    invocation: ToolInvocation,
    toolExposureMode: ToolExposureMode = ToolExposureMode.FULL
): Pair<Boolean, ToolResult?>
```

**权限流程**：

```
1. 检查 deny_tool 标记
   └─ 包含 deny_tool → 跳过权限检查

2. 检查工具权限系统
   └─ toolPermissionSystem.checkToolPermission(tool)

3. 无权限 → 返回错误结果
   └─ "User cancelled the tool execution."

4. 有权限 → 返回 (true, null)
```

### 8.4 工具执行安全包装

```kotlin
fun executeToolSafely(
    invocation: ToolInvocation,
    executor: ToolExecutor,
    toolHandler: AIToolHandler? = null
): Flow<ToolResult>
```

- 参数验证
- 异常捕获
- 错误通知

---

## 9. InputProcessor 输入处理

### 9.1 输入处理管道

```kotlin
object InputProcessor {
    suspend fun processUserInput(
        input: String,
        chatId: String? = null
    ): String
}
```

**处理流程**：

```
原始输入
    │
    ▼
before_process Hook
    │
    ▼
处理逻辑（可扩展）
    │
    ▼
after_process Hook
    │
    ▼
处理后的输入
```

---

## 10. FileBindingService 文件绑定

### 10.1 模糊补丁应用

FileBindingService 实现了**基于内容模糊匹配的代码补丁应用**，替代传统的基于行号的补丁：

```kotlin
class FileBindingService(context: Context) {
    /** 应用 AI 生成的代码补丁 */
    suspend fun processFileBinding(
        originalContent: String,     // 原始文件内容
        aiGeneratedCode: String,     // AI 生成的代码
        onProgress: ((Float, String) -> Unit)? = null
    ): Pair<String, String>         // (合并后内容, diff 字符串)
}
```

### 10.2 补丁格式

AI 生成的代码使用结构化编辑块：

```
[START-REPLACE]
[OLD]
旧代码内容
[/OLD]
[NEW]
新代码内容
[/NEW]
[END-REPLACE]

[START-DELETE]
[OLD]
要删除的代码
[/OLD]
[END-DELETE]
```

### 10.3 模糊匹配算法

```
1. 解析编辑操作
   └─ 提取 [OLD] 和 [NEW] 内容

2. 预计算与规范化
   ├─ 去除空白字符
   ├─ 构建 n-gram 集合
   └─ 计算行起始索引

3. 并行滑动窗口搜索
   ├─ 将文件分成多段
   ├─ 每段独立搜索最佳匹配
   └─ 使用 n-gram 相似度评分

4. 匹配评分
   ├─ 相似度 > 90%: 接受匹配
   ├─ 多个完美匹配: 拒绝（避免歧义）
   └─ 无匹配: 返回错误

5. 应用补丁
   ├─ 从下到上应用（避免行号偏移）
   ├─ 保留边界空白（缩进）
   └─ 生成统一 diff
```

### 10.4 Diff 生成

```kotlin
fun generateUnifiedDiff(original: String, modified: String): String
```

生成带行号的统一 diff 格式：

```
Changes: +5 -3 lines
@@ -10,7 +10,9 @@
  10|    fun oldFunction() {
- 11|        val x = 1
+ 11|        val x = 2
  12|        println(x)
+ 13|        // 新增行
+ 14|        return x
  13|    }
```

---

## 11. ReferenceManager 引用管理

### 11.1 引用提取

从 AI 响应中提取 Markdown 格式的链接引用：

```kotlin
object ReferenceManager {
    fun extractReferences(content: String): List<AiReference>
}
```

**提取格式**：

```markdown
[标题](https://example.com)  →  AiReference(title="标题", url="https://example.com")
```

---

## 12. 使用示例

### 12.1 基础对话流程

```kotlin
class ChatViewModel(private val context: Context) : ViewModel() {
    private val conversationService = ConversationService(context, customEmojiRepository)
    private val multiServiceManager = MultiServiceManager(context)
    private val roundManager = ConversationRoundManager()

    suspend fun sendMessage(userInput: String) {
        // 1. 处理用户输入
        val processedInput = InputProcessor.processUserInput(userInput, chatId)

        // 2. 准备对话历史
        val history = conversationService.prepareConversationHistory(
            chatHistory = currentHistory,
            processedInput = processedInput,
            chatId = chatId,
            workspacePath = "/path/to/workspace",
            packageManager = packageManager,
            promptFunctionType = PromptFunctionType.CHAT,
            roleCardId = selectedRoleCardId,
            hasImageRecognition = multiServiceManager.hasImageRecognitionConfigured()
        )

        // 3. 获取聊天服务
        val chatService = multiServiceManager.getServiceForFunction(FunctionType.CHAT)

        // 4. 发送消息
        val responseStream = chatService.sendMessage(
            context = context,
            chatHistory = history,
            availableTools = availableTools
        )

        // 5. 收集响应
        val responseBuilder = StringBuilder()
        responseStream.collect { chunk ->
            responseBuilder.append(chunk)
            _currentResponse.value = roundManager.updateContent(responseBuilder.toString())
        }

        // 6. 检查工具调用
        val invocations = ToolExecutionManager.extractToolInvocations(responseBuilder.toString())
        if (invocations.isNotEmpty()) {
            // 执行工具
            val results = ToolExecutionManager.executeInvocations(
                invocations = invocations,
                context = context,
                toolHandler = toolHandler,
                packageManager = packageManager,
                collector = object : StreamCollector<String> {
                    override suspend fun emit(value: String) {
                        _toolOutput.value += value
                    }
                }
            )

            // 将工具结果加入历史，继续对话
            val toolResults = results.map { result ->
                PromptTurn(
                    kind = PromptTurnKind.TOOL_RESULT,
                    content = ConversationMarkupManager.formatToolResultForMessage(result)
                )
            }
            currentHistory.addAll(toolResults)

            // 开始新一轮
            roundManager.startNewRound()
        }
    }
}
```

### 12.2 对话总结

```kotlin
suspend fun summarizeConversation(messages: List<PromptTurn>) {
    val summary = conversationService.generateSummary(
        messages = messages,
        previousSummary = existingSummary,  // 增量总结
        multiServiceManager = multiServiceManager
    )

    // 显示进度
    ToolProgressBus.update(
        ToolProgressBus.SUMMARY_PROGRESS_TOOL_NAME,
        progress = 0.5f,
        message = "总结生成中..."
    )
}
```

### 12.3 文件补丁应用

```kotlin
suspend fun applyCodePatch(originalFile: File, aiGeneratedCode: String) {
    val fileBindingService = FileBindingService(context)

    val (mergedContent, diff) = fileBindingService.processFileBinding(
        originalContent = originalFile.readText(),
        aiGeneratedCode = aiGeneratedCode,
        onProgress = { progress, message ->
            _patchProgress.value = progress to message
        }
    )

    if (diff.startsWith("Error:")) {
        _patchResult.value = PatchResult.Error(diff)
    } else {
        originalFile.writeText(mergedContent)
        _patchResult.value = PatchResult.Success(diff)
    }
}
```

### 12.4 多服务调用

```kotlin
suspend fun multiModalAnalysis(imagePath: String, userQuestion: String) {
    // 识图
    val imageAnalysis = conversationService.analyzeImageWithIntent(
        imagePath = imagePath,
        userIntent = userQuestion,
        multiServiceManager = multiServiceManager
    )

    // 翻译
    val translatedText = conversationService.translateText(
        text = imageAnalysis,
        multiServiceManager = multiServiceManager
    )
}
```

### 12.5 工具调用链

```kotlin
suspend fun executeToolChain(response: String) {
    // 1. 提取工具调用
    val invocations = ToolExecutionManager.extractToolInvocations(response)

    // 2. 执行工具
    val results = ToolExecutionManager.executeInvocations(
        invocations = invocations,
        context = context,
        toolHandler = toolHandler,
        packageManager = packageManager,
        collector = streamCollector,
        toolExposureMode = ToolExposureMode.FULL,
        callerCardId = currentRoleCardId
    )

    // 3. 处理结果
    results.forEach { result ->
        when {
            result.success -> handleSuccess(result)
            else -> handleError(result)
        }
    }
}
```

---

## 13. 最佳实践

### 13.1 对话历史管理

```kotlin
// 保持历史在合理长度
val MAX_HISTORY_LENGTH = 50

fun trimHistory(history: List<PromptTurn>): List<PromptTurn> {
    if (history.size <= MAX_HISTORY_LENGTH) return history

    // 保留系统消息和最近的消息
    val systemMessage = history.firstOrNull { it.kind == PromptTurnKind.SYSTEM }
    val recentMessages = history.takeLast(MAX_HISTORY_LENGTH - 1)

    return if (systemMessage != null) {
        listOf(systemMessage) + recentMessages
    } else {
        recentMessages
    }
}
```

### 13.2 错误处理

```kotlin
suspend fun safeSendMessage(history: List<PromptTurn>): String {
    return try {
        val stream = chatService.sendMessage(context, history)
        val builder = StringBuilder()
        stream.collect { builder.append(it) }
        builder.toString()
    } catch (e: UserCancellationException) {
        // 用户取消，无需处理
        ""
    } catch (e: Exception) {
        AppLogger.e("Chat", "发送消息失败", e)
        "Error: ${e.message}"
    }
}
```

### 13.3 服务生命周期

```kotlin
class ChatViewModel : ViewModel() {
    private val multiServiceManager = MultiServiceManager(context)

    fun onConfigChanged() {
        viewModelScope.launch {
            // 配置变更后刷新服务
            multiServiceManager.refreshAllServices()
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            multiServiceManager.cancelAllStreaming()
        }
    }
}
```

### 13.4 工具结果展示

```kotlin
fun formatToolResultsForDisplay(results: List<ToolResult>): String {
    return results.joinToString("\n") { result ->
        when {
            result.success -> "✅ ${result.toolName}: ${result.result}"
            else -> "❌ ${result.toolName}: ${result.error}"
        }
    }
}
```

### 13.5 文件补丁最佳实践

```kotlin
suspend fun safeApplyPatch(originalFile: File, patch: String): Boolean {
    // 1. 备份原文件
    val backup = File(originalFile.absolutePath + ".backup")
    originalFile.copyTo(backup, overwrite = true)

    try {
        val (merged, diff) = fileBindingService.processFileBinding(
            originalContent = originalFile.readText(),
            aiGeneratedCode = patch
        )

        // 2. 检查是否有错误
        if (diff.startsWith("Error:")) {
            return false
        }

        // 3. 应用补丁
        originalFile.writeText(merged)
        return true
    } catch (e: Exception) {
        // 4. 恢复备份
        backup.copyTo(originalFile, overwrite = true)
        return false
    } finally {
        backup.delete()
    }
}
```

---

## 总结

Enhance 模块通过以下设计提供了完整的对话增强能力：

1. **对话历史准备**：系统提示词组装、角色卡注入、用户偏好、工具提示词
2. **Hook 扩展机制**：在关键阶段提供扩展点，支持插件化定制
3. **多服务分工**：不同功能使用不同模型，实现专业化
4. **工具执行链**：XML 解析 → 权限检查 → 并行/串行执行 → 结果格式化
5. **模糊补丁应用**：基于内容匹配的代码补丁，无需精确行号
6. **轮次管理**：跟踪多轮对话，支持工具调用后的连续响应
7. **多模态支持**：图片、音频、视频的统一分析接口

该模块在 Operit 项目中主要用于：
- AI 聊天功能的对话流程控制
- 工具调用链的执行和管理
- 对话历史的构建和维护
- 代码文件的 AI 辅助编辑
- 多模型协同工作
