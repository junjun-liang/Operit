# Model 模块设计思想与详细使用指南

## 1. 模块概述

`model` 模块位于 `com.ai.assistance.operit.data.model` 包下，是 Operit AI 项目的**核心数据层**。它定义了所有跨组件共享的数据结构，包括 AI 对话、角色配置、工作流、记忆系统、模型配置等。该模块的设计遵循**单一职责**和**不可变数据优先**原则，大量使用 Kotlin `data class` 和 `sealed class` 来保证类型安全和代码简洁。

---

## 2. 核心设计思想

### 2.1 分层数据模型（Database ↔ Domain ↔ UI）

Model 模块内部实现了清晰的分层转换：

- **数据库层 (Entity)**：如 `ChatEntity`、`MessageEntity`，使用 Room 注解，专门用于持久化。
- **领域层 (Domain)**：如 `ChatHistory`、`ChatMessage`，用于业务逻辑和 UI 展示，支持序列化（`kotlinx.serialization`）和跨进程传递（`Parcelable`）。
- **归档/传输层**：如 `OperitChatArchive`、`MemoryExportData`，用于数据导出、备份和跨设备同步。

**转换示例**：
```kotlin
// Entity → Domain
val chatHistory: ChatHistory = chatEntity.toChatHistory(messages)

// Domain → Entity
val entity: ChatEntity = ChatEntity.fromChatHistory(chatHistory)
```

### 2.2 双数据库策略（Room + ObjectBox）

- **Room**：用于关系型数据，如聊天记录（`ChatEntity`、`MessageEntity`）、角色卡（`CharacterCard`）。
- **ObjectBox**：用于高性能 NoSQL 场景，特别是记忆系统（`Memory`、`MemoryTag`、`MemoryLink`）和向量搜索。

这种混合策略兼顾了 SQL 的严谨性和 NoSQL 的灵活性。

### 2.3 不可变数据与拷贝模式

所有核心数据类均为 `data class`，默认不可变。更新时采用 `copy()` 方法，确保线程安全和可预测的状态管理。

```kotlin
val updatedMessage = message.copy(content = "新内容", outputTokens = 150)
```

### 2.4 类型安全的状态表达

使用 `sealed class` 和 `sealed interface` 表达有限状态，编译器可自动检查 exhaustive when 分支。

```kotlin
sealed class InputProcessingState {
    object Idle : InputProcessingState()
    data class Processing(val message: String) : InputProcessingState()
    data class Error(val message: String) : InputProcessingState()
}
```

---

## 3. 子系统详解与使用指南

### 3.1 聊天系统 (Chat System)

#### 核心类

| 类名 | 职责 | 存储方式 |
|------|------|----------|
| `ChatEntity` | 聊天会话元数据（标题、时间、Token 统计） | Room (`chats` 表) |
| `MessageEntity` | 单条消息的持久化表示 | Room (`messages` 表) |
| `ChatMessage` | 单条消息的领域模型，支持 Parcelable | 内存 / Parcel |
| `ChatHistory` | 完整聊天记录（含消息列表） | 内存 / JSON |
| `MessageVariantEntity` | 消息的多版本变体存储 | Room (`message_variants` 表) |
| `ChatMessageTimestampAllocator` | 保证消息时间戳单调递增 | 单例工具 |

#### ChatMessage 关键字段说明

```kotlin
data class ChatMessage(
    val sender: String,              // "user" 或 "ai"
    var content: String = "",
    val timestamp: Long = ChatMessageTimestampAllocator.next(), // 唯一标识
    val roleName: String = "",       // AI 角色名称
    val selectedVariantIndex: Int = 0,  // 当前选中的回答版本
    val variantCount: Int = 1,       // 可切换版本数量
    val provider: String = "",       // API 提供商
    val modelName: String = "",      // 模型名称
    val inputTokens: Int = 0,        // 输入 token 数
    val outputTokens: Int = 0,       // 输出 token 数
    val cachedInputTokens: Int = 0,  // 缓存命中 token
    val sentAt: Long = 0L,           // 请求发送时间
    val outputDurationMs: Long = 0L, // 输出耗时
    val waitDurationMs: Long = 0L,   // 首包等待耗时
    val displayMode: ChatMessageDisplayMode = ChatMessageDisplayMode.NORMAL,
    val isFavorite: Boolean = false,
    @Transient var contentStream: Stream<String>? = null // 流式输出
) : Parcelable
```

#### 使用示例

```kotlin
// 创建用户消息
val userMessage = ChatMessage(sender = "user", content = "你好")

// 创建 AI 消息（流式）
val aiMessage = ChatMessage(sender = "ai", contentStream = aiResponseStream)

// 更新消息内容（不可变拷贝）
val finalizedMessage = aiMessage.copy(content = "完整回答", outputTokens = 128)

// 消息变体切换
val variant = MessageVariantEntity.fromChatMessage(chatId, timestamp, 1, alternativeMessage)
```

---

### 3.2 角色卡系统 (Character Card System)

#### 核心类

| 类名 | 职责 |
|------|------|
| `CharacterCard` | 角色卡定义（设定、开场白、工具权限） |
| `CharacterCardToolAccessConfig` | 角色级别的工具白名单配置 |
| `CharacterGroupCard` | 群组角色卡（多角色编排） |
| `TavernCharacterCard` | 兼容 Tavern AI 格式的角色卡导入 |

#### CharacterCard 结构

```kotlin
@Entity(tableName = "character_cards")
data class CharacterCard(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    val characterSetting: String = "",      // 角色设定/引导词
    val openingStatement: String = "",       // 开场白
    val otherContentChat: String = "",       // 聊天附加内容
    val otherContentVoice: String = "",      // 语音附加内容
    val attachedTagIds: List<String> = emptyList(),
    val advancedCustomPrompt: String = "",
    val marks: String = "",                  // 备注（不进入提示词）
    val chatModelBindingMode: String = CharacterCardChatModelBindingMode.FOLLOW_GLOBAL,
    val chatModelConfigId: String? = null,   // 固定绑定的模型配置
    val chatModelIndex: Int = 0,
    val memoryProfileBindingMode: String = CharacterCardMemoryProfileBindingMode.FOLLOW_GLOBAL,
    val memoryProfileId: String? = null,     // 固定绑定的记忆配置
    val toolAccessConfig: CharacterCardToolAccessConfig = CharacterCardToolAccessConfig(),
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
```

#### 工具权限配置

```kotlin
data class CharacterCardToolAccessConfig(
    val enabled: Boolean = false,
    val allowedBuiltinTools: List<String> = emptyList(),  // 内置工具白名单
    val allowedPackages: List<String> = emptyList(),      // 包级工具白名单
    val allowedSkills: List<String> = emptyList(),        // Skill 白名单
    val allowedMcpServers: List<String> = emptyList()     // MCP 服务器白名单
)
```

#### 使用示例

```kotlin
// 创建角色卡
val assistant = CharacterCard(
    id = UUID.randomUUID().toString(),
    name = "编程助手",
    characterSetting = "你是一位经验丰富的 Android 开发专家...",
    openingStatement = "你好！我是你的编程助手，有什么可以帮你的？",
    toolAccessConfig = CharacterCardToolAccessConfig(
        enabled = true,
        allowedBuiltinTools = listOf("file_search", "code_analysis")
    ),
    chatModelBindingMode = CharacterCardChatModelBindingMode.FIXED_CONFIG,
    chatModelConfigId = "deepseek-config-001"
)

// 创建群组角色卡
val group = CharacterGroupCard(
    id = "group-1",
    name = "开发团队",
    members = listOf(
        GroupMemberConfig(characterCardId = "architect-id", orderIndex = 0),
        GroupMemberConfig(characterCardId = "coder-id", orderIndex = 1)
    )
)
```

---

### 3.3 工作流系统 (Workflow System)

#### 核心类

| 类名 | 职责 |
|------|------|
| `Workflow` | 工作流定义（节点、连接、执行统计） |
| `WorkflowNode` | 节点基类（sealed class） |
| `TriggerNode` | 触发节点（手动、定时、Intent 等） |
| `ExecuteNode` | 执行节点（调用工具或执行 JS） |
| `ConditionNode` | 条件节点（比较运算） |
| `LogicNode` | 逻辑节点（AND/OR） |
| `ExtractNode` | 提取节点（正则、JSON、随机等） |
| `WorkflowNodeConnection` | 节点间连接关系 |
| `WorkflowExecutionRecord` | 执行记录 |
| `WorkflowExecutionLogEntry` | 执行日志条目 |

#### 节点类型详解

```kotlin
// 触发节点
val trigger = TriggerNode(
    triggerType = "manual",  // manual, schedule, tasker, intent, speech, app_open
    triggerConfig = mapOf("cron" to "0 9 * * *")
)

// 执行节点
val execute = ExecuteNode(
    actionType = "http_request",
    actionConfig = mapOf(
        "url" to ParameterValue.StaticValue("https://api.example.com"),
        "method" to ParameterValue.StaticValue("GET")
    ),
    jsCode = "return result.data;"
)

// 条件节点
val condition = ConditionNode(
    left = ParameterValue.NodeReference("node-1"),
    operator = ConditionOperator.GT,
    right = ParameterValue.StaticValue("100")
)

// 提取节点
val extract = ExtractNode(
    source = ParameterValue.NodeReference("node-2"),
    mode = ExtractMode.REGEX,
    expression = "\\d+",
    group = 0
)
```

#### 使用示例

```kotlin
val workflow = Workflow(
    name = "早安播报",
    nodes = listOf(trigger, execute, condition),
    connections = listOf(
        WorkflowNodeConnection(
            sourceNodeId = trigger.id,
            targetNodeId = execute.id
        ),
        WorkflowNodeConnection(
            sourceNodeId = execute.id,
            targetNodeId = condition.id,
            condition = "success"
        )
    )
)
```

---

### 3.4 记忆系统 (Memory System)

#### 核心类

| 类名 | 职责 | 存储方式 |
|------|------|----------|
| `Memory` | 核心记忆单元 | ObjectBox |
| `MemoryTag` | 标签（支持层级） | ObjectBox |
| `MemoryLink` | 记忆间关系 | ObjectBox |
| `MemoryProperty` | 键值对属性扩展 | ObjectBox |
| `DocumentChunk` | 文档分块（用于 RAG） | ObjectBox |
| `Embedding` | 向量嵌入包装 | ObjectBox (ByteArray) |
| `EmbeddingConverter` | ObjectBox 类型转换器 | - |
| `MemorySearchConfig` | 搜索配置（权重、模式） | 内存 |
| `MemoryExportData` | 导出/导入数据容器 | JSON |

#### Memory 结构

```kotlin
@Entity
data class Memory(
    @Id var id: Long = 0,
    var uuid: String = UUID.randomUUID().toString(),
    var title: String = "",
    var content: String = "",
    var contentType: String = "text/plain",
    var source: String = "unknown",      // user_input, chat_summary, web_scrape
    var credibility: Float = 0.5f,       // 可信度 0.0-1.0
    var importance: Float = 0.5f,        // 重要性 0.0-1.0
    var documentPath: String? = null,    // 外部文档路径
    var isDocumentNode: Boolean = false,
    var chunkIndexFilePath: String? = null,
    @Index var folderPath: String? = null,  // 分类路径，如 "工作/项目A"
    @Convert(converter = EmbeddingConverter::class, dbType = ByteArray::class)
    var embedding: Embedding? = null,    // 向量嵌入
    var createdAt: Date = Date(),
    var updatedAt: Date = Date(),
    var lastAccessedAt: Date = Date()
) {
    lateinit var tags: ToMany<MemoryTag>
    lateinit var properties: ToMany<MemoryProperty>
    lateinit var links: ToMany<MemoryLink>
    @Backlink(to = "target") lateinit var backlinks: ToMany<MemoryLink>
    @Backlink(to = "memory") lateinit var documentChunks: ToMany<DocumentChunk>
}
```

#### 搜索配置

```kotlin
data class MemorySearchConfig(
    val scoreMode: MemoryScoreMode = MemoryScoreMode.BALANCED,
    val keywordWeight: Float = 10.0f,    // 关键词权重
    val tagWeight: Float = 0.0f,         // 标签权重
    val vectorWeight: Float = 0.0f,      // 向量语义权重
    val edgeWeight: Float = 0.4f         // 图关系权重
)
```

#### 使用示例

```kotlin
// 创建记忆
val memory = Memory(
    title = "Kotlin 协程最佳实践",
    content = "使用 Dispatchers.IO 处理网络请求...",
    source = "user_input",
    credibility = 0.9f,
    importance = 0.8f,
    folderPath = "技术/Kotlin"
)

// 添加标签
memory.tags.add(MemoryTag(name = "Kotlin"))
memory.tags.add(MemoryTag(name = "Coroutine"))

// 创建关联
val link = MemoryLink(type = "related", weight = 0.9f)
link.source.target = memory
link.target.target = anotherMemory

// 文档分块
val chunk = DocumentChunk(
    content = "段落内容...",
    chunkIndex = 0,
    embedding = Embedding(floatArrayOf(0.1f, 0.2f, ...))
)
chunk.memory.target = memory

// 导出记忆
val exportData = MemoryExportData(
    memories = memories.map { it.toSerializable() },
    links = links.map { it.toSerializable() },
    exportDate = Date(),
    version = "1.0"
)
```

---

### 3.5 模型配置系统 (Model Config System)

#### 核心类

| 类名 | 职责 |
|------|------|
| `ModelConfigData` | 完整模型配置（API、参数、上下文管理） |
| `ModelConfigSummary` | 简化版配置（列表展示） |
| `ApiProviderType` | 提供商类型枚举（39+ 种） |
| `ApiKeyInfo` | API Key 详情（支持多 Key 轮询） |
| `StandardModelParameters` | 标准参数定义（单例） |
| `ModelParameter` | 泛型参数类 |
| `CustomParameterData` | 自定义参数 JSON 表示 |
| `CloudEmbeddingConfig` | 云端嵌入模型配置 |

#### ModelConfigData 关键字段

```kotlin
data class ModelConfigData(
    val id: String,
    val name: String,
    val apiKey: String = "",
    val apiEndpoint: String = "",
    val modelName: String = "",
    val apiProviderType: ApiProviderType = ApiProviderType.DEEPSEEK,

    // 多 API Key 轮询
    val useMultipleApiKeys: Boolean = false,
    val apiKeyPool: List<ApiKeyInfo> = emptyList(),
    val keyRotationMode: String = "ROUND_ROBIN",  // ROUND_ROBIN / RANDOM

    // 模型参数（带启用开关）
    val maxTokensEnabled: Boolean = false,
    val maxTokens: Int = 4096,
    val temperatureEnabled: Boolean = false,
    val temperature: Float = 1.0f,
    val topPEnabled: Boolean = false,
    val topP: Float = 1.0f,

    // 上下文管理
    val contextLength: Float = 64.0f,
    val maxContextLength: Float = 200.0f,
    val enableSummary: Boolean = true,
    val summaryMessageCountThreshold: Int = 16,

    // 本地推理配置（llama.cpp）
    val llamaThreadCount: Int = 4,
    val llamaContextSize: Int = 2048,
    val llamaGpuLayers: Int = 0,

    // 功能开关
    val enableToolCall: Boolean = false,
    val enableGoogleSearch: Boolean = false,
    val enableDirectImageProcessing: Boolean = false,

    // 限流
    val requestLimitPerMinute: Int = 0,
    val maxConcurrentRequests: Int = 0
)
```

#### 使用示例

```kotlin
// 创建 DeepSeek 配置
val config = ModelConfigData(
    id = "deepseek-001",
    name = "DeepSeek V3",
    apiKey = "sk-...",
    apiEndpoint = "https://api.deepseek.com/v1",
    modelName = "deepseek-chat",
    apiProviderType = ApiProviderType.DEEPSEEK,
    temperature = 0.7f,
    temperatureEnabled = true,
    maxTokens = 8192,
    enableToolCall = true
)

// 多 Key 轮询
val configWithPool = config.copy(
    useMultipleApiKeys = true,
    apiKeyPool = listOf(
        ApiKeyInfo(id = "key-1", key = "sk-aaa", name = "主密钥"),
        ApiKeyInfo(id = "key-2", key = "sk-bbb", name = "备用密钥")
    ),
    keyRotationMode = "ROUND_ROBIN"
)

// 获取模型列表（逗号分隔支持）
val models = getModelList("gpt-4,gpt-4-turbo,gpt-3.5-turbo")
val selectedModel = getModelByIndex("gpt-4,gpt-4-turbo", 1) // "gpt-4-turbo"
```

---

### 3.6 工具系统 (Tool System)

#### 核心类

| 类名 | 职责 |
|------|------|
| `AITool` | AI 可调用的工具定义 |
| `ToolParameter` | 工具参数键值对 |
| `ToolInvocation` | AI 响应中的工具调用实例 |
| `ToolResult` | 工具执行结果 |
| `ToolValidationResult` | 参数验证结果 |
| `ToolPrompt` | 工具提示词（用于系统提示） |
| `ToolParameterSchema` | 工具参数模式定义 |

#### 使用示例

```kotlin
// 定义工具
val searchTool = AITool(
    name = "web_search",
    description = "搜索互联网信息",
    parameters = listOf(
        ToolParameter(name = "query", value = "Kotlin 协程"),
        ToolParameter(name = "limit", value = "5")
    )
)

// 工具调用
val invocation = ToolInvocation(
    tool = searchTool,
    rawText = "<tool>web_search(query=\"Kotlin\")</tool>",
    responseLocation = 10..50
)

// 执行结果
val result = ToolResult(
    toolName = "web_search",
    success = true,
    result = ToolResultData(text = "搜索结果..."),
    error = null
)

// 工具提示词（用于系统提示）
val toolPrompt = ToolPrompt(
    name = "web_search",
    description = "搜索互联网",
    parametersStructured = listOf(
        ToolParameterSchema(
            name = "query",
            type = "string",
            description = "搜索关键词",
            required = true
        )
    )
)
```

---

### 3.7 辅助与工具类

| 类名 | 职责 |
|------|------|
| `ChatMessageTimestampAllocator` | 保证消息时间戳单调递增（CAS 实现） |
| `ChatMessageDisplayMode` | 消息显示模式（NORMAL / HIDDEN_PLACEHOLDER） |
| `ChatTurnOptions` | 单轮对话选项（是否持久化、是否隐藏等） |
| `InputProcessingState` | AI 处理状态（空闲、连接中、执行工具等） |
| `FunctionType` | 功能类型枚举（CHAT、SUMMARY、MEMORY 等） |
| `ActivePrompt` | 当前激活的提示词类型（角色卡/群组） |
| `AttachmentInfo` | 消息附件信息 |
| `AiReference` | AI 回答中的引用链接 |
| `CustomEmoji` | 自定义表情数据 |
| `PreferenceProfile` | 用户偏好配置 |
| `OperitNodeInfo` | 无障碍节点信息（UI 自动化） |
| `DragonBonesModel` / `DragonBonesConfig` | 骨骼动画模型配置 |
| `SerializableColorScheme` / `SerializableTypography` | Compose 主题序列化 |
| `WorkspaceRenameResult` | 工作区重命名结果 |
| `BillingMode` | 计费模式（TOKEN / COUNT） |
| `EmbeddingDimensionUsage` | 嵌入维度使用统计 |

---

## 4. 序列化与持久化策略

### 4.1 Kotlinx Serialization

所有领域模型使用 `@Serializable` 注解，支持 JSON 序列化：

```kotlin
@Serializable
data class ChatHistory(...)

// 序列化
val json = Json.encodeToString(chatHistory)

// 反序列化
val history = Json.decodeFromString<ChatHistory>(json)
```

### 4.2 Parcelable

`ChatMessage` 实现 `Parcelable`，支持跨进程传递（如 Service ↔ Activity）：

```kotlin
val bundle = Bundle().apply {
    putParcelable("message", chatMessage)
}
```

### 4.3 Room 数据库

```kotlin
@Entity(tableName = "chats")
data class ChatEntity(@PrimaryKey val id: String, ...)

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(
        entity = ChatEntity::class,
        parentColumns = ["id"],
        childColumns = ["chatId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class MessageEntity(...)
```

### 4.4 ObjectBox

```kotlin
@Entity
data class Memory(
    @Id var id: Long = 0,
    @Convert(converter = EmbeddingConverter::class, dbType = ByteArray::class)
    var embedding: Embedding? = null
)
```

---

## 5. 最佳实践

### 5.1 创建消息时始终使用 TimestampAllocator

```kotlin
// 正确
val message = ChatMessage(
    sender = "user",
    content = "你好",
    timestamp = ChatMessageTimestampAllocator.next()
)

// 错误（可能导致时间戳冲突）
val message = ChatMessage(
    sender = "user",
    content = "你好",
    timestamp = System.currentTimeMillis()
)
```

### 5.2 使用 copy() 更新不可变数据

```kotlin
val updatedConfig = config.copy(
    temperature = 0.8f,
    temperatureEnabled = true
)
```

### 5.3 数据转换时处理异常

```kotlin
val displayMode = runCatching {
    ChatMessageDisplayMode.valueOf(entity.displayMode)
}.getOrDefault(ChatMessageDisplayMode.NORMAL)
```

### 5.4 记忆搜索时配置合理权重

```kotlin
val searchConfig = MemorySearchConfig(
    scoreMode = MemoryScoreMode.BALANCED,
    keywordWeight = 10.0f,
    vectorWeight = 5.0f,
    edgeWeight = 0.4f
)
```

### 5.5 工具权限配置规范化

```kotlin
val normalizedConfig = toolAccessConfig.normalized()
// 自动去除空白、去重
```

---

## 6. 模块关系图

```
model 模块
├── 聊天系统
│   ├── ChatEntity (Room) ↔ ChatHistory (Domain)
│   ├── MessageEntity (Room) ↔ ChatMessage (Parcelable)
│   └── MessageVariantEntity (Room)
├── 角色系统
│   ├── CharacterCard (Room)
│   ├── CharacterGroupCard
│   └── TavernCharacterCard (兼容格式)
├── 工作流系统
│   ├── Workflow
│   ├── WorkflowNode (sealed)
│   │   ├── TriggerNode
│   │   ├── ExecuteNode
│   │   ├── ConditionNode
│   │   ├── LogicNode
│   │   └── ExtractNode
│   └── WorkflowExecutionRecord
├── 记忆系统
│   ├── Memory (ObjectBox)
│   ├── MemoryTag (ObjectBox)
│   ├── MemoryLink (ObjectBox)
│   ├── DocumentChunk (ObjectBox)
│   └── Embedding ↔ ByteArray
├── 模型配置
│   ├── ModelConfigData
│   ├── ApiProviderType
│   └── ApiKeyInfo
└── 工具系统
    ├── AITool
    ├── ToolResult
    └── ToolPrompt
```

---

## 7. 总结

Model 模块作为 Operit AI 的数据中枢，通过以下设计保证了系统的可维护性和扩展性：

1. **分层架构**：数据库层、领域层、UI 层清晰分离，通过转换器连接。
2. **双数据库**：Room 处理关系型数据，ObjectBox 处理高性能 NoSQL 和向量数据。
3. **类型安全**：大量使用 `sealed class`、`enum`、`data class` 消除运行时错误。
4. **不可变优先**：`copy()` 模式确保线程安全，便于状态管理。
5. **序列化统一**：`kotlinx.serialization` 覆盖所有跨层数据传输。
6. **扩展性强**：记忆系统的标签、属性、链接设计支持任意知识图谱结构；工作流的节点系统支持无限扩展。
