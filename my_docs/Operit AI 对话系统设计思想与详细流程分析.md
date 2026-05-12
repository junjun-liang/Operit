# Operit AI 对话系统设计思想与详细流程分析

## 一、设计思想概述

Operit 的 AI 对话系统采用**"分层架构 + 流式处理 + 插件扩展 + 多模态支持"**的设计思想，核心设计理念包括：

1. **无状态核心管理器**：`AIMessageManager` 作为单例对象，不持有任何特定聊天的状态，所有数据通过方法参数传入，确保高并发场景下的线程安全与可复用性
2. **委托-代理模式**：`MessageProcessingDelegate` 负责端到端的消息处理流程编排，将 UI 状态管理、数据持久化、AI 服务调用解耦
3. **流式响应优先**：支持 `SharedStream` 流式输出与实时渲染，通过 `TextStreamRevisionTracker` 实现内容回溯与修订
4. **上下文智能管理**：通过总结生成、媒体链接裁剪、角色隔离等策略，有效控制上下文窗口大小
5. **插件化扩展**：`MessageProcessingPluginRegistry` 提供可插拔的消息处理机制，允许第三方插件接管消息流程
6. **多模态支持**：原生支持文本、图片、音频、视频、附件、工作区等多种输入输出形式
7. **并发安全**：使用 `ConcurrentHashMap`、`Mutex` 等机制保护聊天与消息的写入操作

---

## 二、软件架构图

### 2.1 整体架构分层

```mermaid
graph TB
    subgraph "前端层 (TypeScript/React)"
        WC[WebChat 前端]
        CVM[ChatViewModel.ts<br/>消息分页/定位/手动摘要]
        UIS[UiStateDelegate.ts<br/>上下文统计/限制提示]
    end

    subgraph "服务层 (Kotlin/Android)"
        FCS[FloatingChatService<br/>前台服务/窗口/状态桥接]
        MPD[MessageProcessingDelegate<br/>消息处理委托/状态流转]
        AIMM[AIMessageManager<br/>消息构建/发送/上下文窗口估算]
        MR[MessageProcessingPluginRegistry<br/>插件注册表]
        CRH[ChatRuntimeHolder<br/>运行时持有者/跨会话同步]
    end

    subgraph "AI 服务层"
        EAS[EnhancedAIService<br/>增强型AI服务]
        MSM[MultiServiceManager<br/>多模型服务管理]
        AIS[AIService<br/>LLM提供商抽象]
        CS[ConversationService<br/>对话管理服务]
        FBS[FileBindingService<br/>文件绑定服务]
    end

    subgraph "工具层"
        ATH[AIToolHandler<br/>工具注册/执行/权限/钩子]
        TR[ToolRegistration<br/>集中注册默认工具]
        SCT[StandardChatManagerTool<br/>对话管理工具集]
        SWT[StandardWorkflowTools<br/>工作流工具集]
        MQE[MemoryQueryToolExecutor<br/>记忆查询工具集]
    end

    subgraph "数据层"
        CHM[ChatHistoryManager<br/>聊天历史持久化/归档]
        DAO[MessageDao<br/>消息DAO/查询/复制/更新]
        MR2[MemoryRepository<br/>记忆仓库]
    end

    subgraph "数据模型"
        CM[ChatMessage<br/>消息实体]
        ME[MessageEntity<br/>数据库实体]
        CE[ChatEntity<br/>聊天实体]
        CH[ChatHistory<br/>聊天历史DTO]
    end

    WC --> CVM
    CVM --> UIS
    FCS --> MPD
    MPD --> AIMM
    MPD --> MR
    MPD --> CHM
    AIMM --> MR
    CRH --> MPD
    EAS --> MSM
    EAS --> CS
    EAS --> FBS
    MSM --> AIS
    ATH --> SCT
    ATH --> SWT
    ATH --> MQE
    SCT --> FCS
    SCT --> CHM
    CHM --> DAO
    DAO --> ME
    CHM --> CE
    CE --> CH
    ME --> CM
```

### 2.2 核心组件依赖关系

```mermaid
classDiagram
    class AIMessageManager {
        +initialize(context)
        +buildUserMessageContent(...)
        +sendMessage(...)
        +calculateStableContextWindow(...)
        +summarizeMemory(...)
        +cancelCurrentOperation()
        +cancelOperation(chatId)
        +getMemoryFromMessages(...)
        +shouldGenerateSummary(...)
    }

    class MessageProcessingDelegate {
        +sendUserMessage(...)
        +getResponseStream(chatId)
        +cancelMessage(chatId)
        +regenerateAiMessageVariant(...)
        -chatRuntimes: ConcurrentHashMap
        -tryEmitScrollToBottomThrottled()
        -cancelMessageInternal(...)
    }

    class MessageProcessingPluginRegistry {
        +register(plugin)
        +unregister(id)
        +createExecutionIfMatched(params)
    }

    class EnhancedAIService {
        +sendMessage(options)
        +generateSummary(...)
        +cancelConversation()
        +getCurrentInputTokenCount()
        +getCurrentOutputTokenCount()
        +getCurrentCachedInputTokenCount()
        +estimateRequestWindowFromMemory(...)
        -multiServiceManager: MultiServiceManager
        -conversationService: ConversationService
        -fileBindingService: FileBindingService
    }

    class AIToolHandler {
        +registerTool(name, executor)
        +executeTool(tool)
        +executeToolAndStream(tool)
        +getToolExecutorOrActivate(toolName)
        +addToolHook(hook)
        +removeToolHook(hook)
    }

    class ChatHistoryManager {
        +saveChatHistory(history)
        +updateChatLocked(chatId, locked)
        +importOperitChatHistoriesStream(...)
        +exportOperitArchiveJsonStream(...)
        -globalMutex: Mutex
        -chatMutexes: ConcurrentHashMap
    }

    class MultiServiceManager {
        +getServiceForFunction(functionType)
        +getModelConfigForFunction(functionType)
        +refreshServiceForFunction(functionType)
        +resetAllTokenCounters()
    }

    class ConversationService {
        +startConversation(chatId)
        +endConversation(chatId)
        +getConversationHistory(chatId)
    }

    AIMessageManager --> MessageProcessingPluginRegistry : "匹配插件"
    MessageProcessingDelegate --> AIMessageManager : "调用"
    MessageProcessingDelegate --> ChatHistoryManager : "归档"
    MessageProcessingDelegate --> AIToolHandler : "工具调用计数"
    ChatRuntimeHolder --> MessageProcessingDelegate : "跨会话同步"
    AIToolHandler --> ChatHistoryManager : "工具结果持久化"
    EnhancedAIService --> MultiServiceManager : "管理"
    EnhancedAIService --> ConversationService : "对话管理"
    EnhancedAIService --> AIToolHandler : "工具执行"
```

---

## 三、数据模型设计

### 3.1 ER 关系图

```mermaid
erDiagram
    CHATS ||--o{ MESSAGES : "一对多"
    CHATS {
        string id PK
        string title
        long createdAt
        long updatedAt
        int inputTokens
        int outputTokens
        int currentWindowSize
        string group
        long displayOrder
        string workspace
        string workspaceEnv
        string parentChatId
        string characterCardName
        string characterGroupId
        boolean locked
    }
    MESSAGES {
        long messageId PK
        string chatId FK
        string sender
        string content
        long timestamp
        int orderIndex
        string roleName
        int selectedVariantIndex
        string provider
        string modelName
        int inputTokens
        int outputTokens
        int cachedInputTokens
        long sentAt
        long outputDurationMs
        long waitDurationMs
        string displayMode
        boolean isFavorite
    }
    MESSAGES ||--o{ MESSAGE_VARIANTS : "一对多"
    MESSAGE_VARIANTS {
        long variantId PK
        long messageTimestamp FK
        string chatId FK
        int variantIndex
        string content
        string provider
        string modelName
    }
```

### 3.2 核心数据类

| 类名 | 职责 | 关键字段 |
|------|------|----------|
| `ChatMessage` | 运行态消息，跨进程传输 | sender, content, timestamp, roleName, contentStream, inputTokens, outputTokens, cachedInputTokens |
| `MessageEntity` | Room 数据库实体 | messageId, chatId, sender, content, orderIndex, displayMode |
| `ChatEntity` | 聊天元数据实体 | id, title, createdAt, characterCardName, characterGroupId, locked |
| `ChatHistory` | 聊天历史 DTO | id, title, messages, inputTokens, outputTokens, currentWindowSize |
| `PromptTurn` | AI 请求的历史回合 | kind (USER/ASSISTANT/SUMMARY), content |
| `MessageExecutionContext` | 单次请求执行上下文 | executionId, streamBuffer, roundManager, isConversationActive, eventChannel |

---

## 四、AI 对话详细流程

### 4.1 消息发送主流程

```mermaid
sequenceDiagram
    participant UI as "前端界面"
    participant VM as "ChatViewModel.ts"
    participant MPD as "MessageProcessingDelegate"
    participant AIMM as "AIMessageManager"
    participant EAS as "EnhancedAIService"
    participant MSM as "MultiServiceManager"
    participant AIS as "AIService (LLM)"
    participant DAO as "MessageDao"
    participant CHM as "ChatHistoryManager"

    UI->>VM: 输入消息/附件
    VM->>MPD: sendUserMessage(...)
    MPD->>MPD: 检查聊天是否正在处理中
    MPD->>MPD: 更新输入处理状态为 Processing
    MPD->>AIMM: buildUserMessageContent(...)
    AIMM-->>MPD: 返回格式化后的消息内容
    MPD->>DAO: 插入用户消息（可选）
    MPD->>AIMM: sendMessage(...)
    AIMM->>AIMM: getMemoryFromMessages()<br/>提取历史记忆
    AIMM->>AIMM: limitImageLinksInChatHistory()<br/>裁剪图片链接
    AIMM->>AIMM: limitMediaLinksInChatHistory()<br/>裁剪媒体链接
    AIMM->>MR: createExecutionIfMatched()<br/>检查插件匹配
    alt 插件匹配
        AIMM-->>MPD: 返回插件流
    else 默认模式
        AIMM->>EAS: sendMessage(options)<br/>发送AI请求
        EAS->>EAS: prepareConversationHistory()<br/>准备对话历史
        EAS->>MSM: getServiceForFunction(CHAT)<br/>获取AI服务
        EAS->>EAS: getAvailableToolsForFunction()<br/>获取可用工具
        EAS->>EAS: applyPromptFinalizeHooks()<br/>应用提示词钩子
        EAS->>AIS: sendMessage(context, chatHistory, modelParameters, ...)<br/>调用LLM
        AIS-->>EAS: 返回响应流
        EAS-->>AIMM: 返回Stream<String>
        AIMM-->>MPD: 返回SharedStream
    end
    MPD->>MPD: 创建AI消息对象（带contentStream）
    MPD->>DAO: 持续写入/更新AI响应
    MPD->>CHM: 保存/归档（可选）
    MPD-->>VM: 状态/流事件
    VM-->>UI: 渲染消息/滚动到底部
```

### 4.2 EnhancedAIService 内部消息处理流程

```mermaid
sequenceDiagram
    participant Caller as "AIMessageManager"
    participant EAS as "EnhancedAIService"
    participant MSM as "MultiServiceManager"
    participant AIS as "AIService"
    participant TEM as "ToolExecutionManager"
    participant ATH as "AIToolHandler"
    participant Ctx as "MessageExecutionContext"

    Caller->>EAS: sendMessage(options)
    EAS->>EAS: ensureInitialized()
    EAS->>Ctx: 创建MessageExecutionContext
    EAS->>EAS: prepareConversationHistory()<br/>准备系统提示+历史
    EAS->>EAS: applyPromptFinalizeHooks()<br/>应用before_finalize_prompt钩子
    EAS->>EAS: applyPromptFinalizeHooks()<br/>应用before_send_to_model钩子
    EAS->>MSM: getServiceForFunction(CHAT)<br/>获取对应功能的AIService
    EAS->>EAS: getAvailableToolsForFunction()<br/>获取可用工具列表
    EAS->>EAS: estimatePreparedRequestWindow()<br/>估算请求窗口大小
    EAS->>AIS: sendMessage(context, chatHistory, modelParameters, availableTools, ...)
    AIS-->>EAS: 返回Stream<String>
    
    loop 流式收集响应
        EAS->>EAS: revisionTracker.append(chunk)
        EAS->>Ctx: streamBuffer.append(chunk)
        EAS->>Ctx: roundManager.updateContent()
        EAS->>EAS: emit(chunk) 发射到上层流
        
        alt 检测到工具调用
            EAS->>TEM: executeTool(tool)
            TEM->>ATH: executeTool(tool)
            ATH-->>TEM: 返回ToolResult
            TEM-->>EAS: 返回工具结果
            EAS->>EAS: 将结果注入到响应中
        end
    end
    
    EAS->>EAS: 更新token统计
    EAS-->>Caller: 流完成
```

### 4.3 流式响应处理流程

```mermaid
sequenceDiagram
    participant MPD as "MessageProcessingDelegate"
    participant Stream as "SharedStream<String>"
    participant Tracker as "TextStreamRevisionTracker"
    participant AutoRead as "TtsSegmenter"
    participant Waifu as "WaifuMessageProcessor"
    participant DB as "MessageDao"

    MPD->>Stream: collect { chunk }
    alt 首次收到chunk
        Stream->>MPD: 记录 firstResponseElapsed
    end
    MPD->>Tracker: append(chunk)
    Tracker-->>MPD: 返回当前内容
    MPD->>MPD: 更新 aiMessage.content
    MPD->>DB: persistStreamingSnapshot(content)<br/>按间隔持久化
    alt 普通模式
        MPD->>AutoRead: 尝试分段朗读
    else Waifu模式
        MPD->>Waifu: streamSegments()
        Waifu-->>MPD: 返回分段消息
        MPD->>DB: 添加分段消息
    end
    MPD->>MPD: tryEmitScrollToBottomThrottled()
    Stream-->>MPD: 流完成
    MPD->>MPD: 读取token统计
    MPD->>MPD: finalizeMessageAndNotify()
    MPD->>DB: 写入最终消息（contentStream=null）
```

### 4.4 上下文窗口管理流程

```mermaid
flowchart TD
    A["输入历史消息"] --> B["统计用户轮次"]
    B --> C{"是否超过图片限制?"}
    C -- 是 --> D["limitImageLinksInChatHistory"]
    D --> E["移除超出轮次的图片链接"]
    E --> F["替换为省略提示"]
    C -- 否 --> G
    F --> G["limitMediaLinksInChatHistory"]
    G --> H{"是否超过媒体限制?"}
    H -- 是 --> I["移除超出轮次的媒体链接"]
    I --> J["替换为省略提示"]
    H -- 否 --> K
    J --> K["getMemoryFromMessages"]
    K --> L{"是否启用角色隔离?"}
    L -- 是 --> M["processAiMessage: 桥接其他角色消息"]
    L -- 否 --> N["直接返回历史"]
    M --> O["输出裁剪后历史"]
    N --> O
```

### 4.5 工具调用处理流程

```mermaid
sequenceDiagram
    participant AI as "AI 生成器"
    participant Parser as "MessageContentParser"
    participant ATH as "AIToolHandler"
    participant Exec as "ToolExecutor"
    participant Hook as "AIToolHook"
    participant PM as "PackageManager"

    AI->>Parser: 生成包含 <tool> 的响应
    Parser->>ATH: 提取 AITool 对象
    ATH->>Hook: notifyToolCallRequested(tool)
    ATH->>ATH: getToolExecutorOrActivate(toolName)
    alt 工具未注册且含包前缀
        ATH->>PM: usePackage(packageName)
        PM-->>ATH: 自动激活包
    end
    ATH->>Exec: validateParameters(tool)
    Exec-->>ATH: 返回验证结果
    alt 验证失败
        ATH-->>AI: 返回错误结果
    else 验证通过
        ATH->>Hook: notifyToolExecutionStarted(tool)
        ATH->>Exec: invoke(tool)
        Exec-->>ATH: 返回 ToolResult
        ATH->>Hook: notifyToolExecutionResult(tool, result)
    end
    ATH->>Hook: notifyToolExecutionFinished(tool)
    ATH-->>AI: 返回最终结果
```

### 4.6 消息生命周期状态机

```mermaid
stateDiagram-v2
    [*] --> Idle
    Idle --> Processing: 用户发送消息
    Processing --> Connecting: 连接AI服务
    Connecting --> Receiving: 收到首包响应
    Processing --> ExecutingTool: AI调用工具
    ExecutingTool --> Processing: 工具执行完成
    Processing --> Summarizing: 触发总结
    Summarizing --> Completed: 总结完成
    Processing --> Completed: 流式响应完成
    Processing --> Error: 发生错误
    ExecutingTool --> Error: 工具执行失败
    Error --> Idle: 错误处理完成
    Completed --> Idle: 回合结束
    Processing --> Idle: 用户取消
    Summarizing --> Idle: 取消
```

### 4.7 取消与清理流程

```mermaid
flowchart TD
    A["用户触发取消"] --> B["cancelMessageInternal"]
    B --> C["读取取消快照"]
    C --> D["取消sendJob"]
    D --> E["取消stateCollectionJob"]
    E --> F["取消streamCollectionJob"]
    F --> G["AIMessageManager.cancelOperation"]
    G --> H["取消插件执行"]
    G --> I["取消EnhancedAIService对话"]
    G --> J["取消ToolPkg JS执行"]
    H --> K["等待所有Job完成"]
    I --> K
    J --> K
    K --> L{"保留部分响应?"}
    L -- 是 --> M["detachStreamingAiMessage"]
    M --> N["使用快照更新消息统计"]
    L -- 否 --> O["直接清理"]
    N --> P["清理运行时状态"]
    O --> P
    P --> Q["更新全局加载状态"]
    Q --> R["设置输入处理状态为Idle"]
```

---

## 五、核心机制详解

### 5.1 历史记忆提取（getMemoryFromMessages）

```kotlin
fun getMemoryFromMessages(
    messages: List<ChatMessage>,
    splitByRole: Boolean = false,
    targetRoleName: String? = null,
    groupOrchestrationMode: Boolean = false
): List<PromptTurn>
```

**处理逻辑**：
1. 找到最后一条总结消息，只处理总结之后的消息
2. 判断是否启用角色隔离模式（`splitByRole && targetRoleName != null`）
3. 处理每条消息：
   - **AI消息**：清理思考内容，角色隔离模式下将其他角色消息桥接为用户消息（添加 `[From role: xxx]` 前缀）
   - **用户消息**：群组编排模式下添加 `[From user]` 前缀
   - **总结消息**：转换为 `PromptTurnKind.SUMMARY`

### 5.2 媒体链接裁剪策略

```kotlin
private fun limitImageLinksInChatHistory(
    history: List<PromptTurn>,
    keepLastUserImageTurns: Int
): List<PromptTurn>
```

**策略说明**：
- 按用户轮次统计，只保留最近 N 轮用户消息中的图片链接
- 超出限制的图片链接被移除，内容替换为 `[图片内容已省略]`
- 同理适用于音视频链接（`limitMediaLinksInChatHistory`）

### 5.3 并发安全机制

```kotlin
// ChatHistoryManager 中的互斥锁
private val globalMutex = Mutex()
private val chatMutexes = ConcurrentHashMap<String, Mutex>()

private fun chatMutex(chatId: String): Mutex {
    return chatMutexes.getOrPut(chatId) { Mutex() }
}
```

**保护措施**：
- 每个聊天有独立的 `Mutex`，确保同一聊天内的写入串行化
- 归档保存前对消息时间戳进行去重校验
- 批量插入消息与变体时，先删除旧数据再插入，保证原子性

### 5.4 跨会话同步机制

```kotlin
class ChatRuntimeHolder {
    private val cores = ConcurrentHashMap<ChatRuntimeSlot, ChatServiceCore>()
    
    private fun setupCrossSessionSync() {
        registerChatSelectionSync(MAIN -> FLOATING)
        registerTurnSync(MAIN -> FLOATING)
        registerTurnSync(FLOATING -> MAIN)
    }
}
```

**同步内容**：
- **聊天选择同步**：主会话切换聊天时，自动同步到浮动窗口
- **回合完成同步**：回合结束后，同步 token 统计和消息重载

### 5.5 插件扩展机制

```kotlin
interface MessageProcessingPlugin {
    val id: String
    suspend fun createExecutionIfMatched(
        params: MessageProcessingHookParams
    ): MessageProcessingExecution?
}

object MessageProcessingPluginRegistry {
    private val plugins = CopyOnWriteArrayList<MessageProcessingPlugin>()
    
    suspend fun createExecutionIfMatched(params): MessageProcessingExecution? {
        for (plugin in plugins) {
            val execution = plugin.createExecutionIfMatched(params)
            if (execution != null) return execution
        }
        return null
    }
}
```

**扩展点**：
- 开发者可注册自定义插件接管消息处理流程
- 插件匹配成功后，完全接管后续的消息发送与响应处理

### 5.6 提示词钩子系统（PromptHookRegistry）

```kotlin
object PromptHookRegistry {
    fun dispatchPromptFinalizeHooks(context: PromptHookContext): PromptHookContext
    fun dispatchPromptEstimateHistoryHooks(context: PromptHookContext): PromptHookContext
    fun dispatchPromptEstimateFinalizeHooks(context: PromptHookContext): PromptHookContext
}
```

**钩子阶段**：
1. **before_finalize_prompt**：在提示词最终确定前，允许修改 processedInput 和 preparedHistory
2. **before_send_to_model**：在发送到模型前，进行最后的调整
3. **estimate 阶段**：在估算窗口大小时应用钩子

### 5.7 流式修订机制（TextStreamRevisionTracker）

```kotlin
class TextStreamRevisionTracker {
    fun append(chunk: String): String
    fun savepoint(id: String)
    fun rollback(id: String): String?
}
```

**工作原理**：
- 支持 SAVEPOINT 和 ROLLBACK 事件
- 当 AI 决定回溯内容时，可以回退到之前的保存点
- 通过 `TextStreamEventCarrier` 在流中传递修订事件

---

## 六、性能优化策略

| 优化点 | 实现方式 | 效果 |
|--------|----------|------|
| 流式输出 | `SharedStream.shareRevisable(replay = Int.MAX_VALUE)` | 减少等待时间，支持UI重组后恢复 |
| 滚动节流 | `STREAM_SCROLL_THROTTLE_MS = 200L` | 降低UI抖动 |
| 持久化间隔 | `STREAM_PERSIST_INTERVAL_MS = 1000L` | 减少数据库IO压力 |
| 媒体链接裁剪 | 按用户轮次限制图片/音视频链接 | 显著降低上下文体积 |
| 稳定窗口估算 | `calculateStableContextWindow()` | 避免频繁超限重试 |
| 流式导入导出 | JsonReader 流式解析 | 降低内存峰值 |
| 并发执行上下文 | `MessageExecutionContext` 隔离 | 支持多请求并发处理 |

---

## 七、关键文件索引

| 文件路径 | 职责 |
|----------|------|
| `app/src/main/java/com/ai/assistance/operit/core/chat/AIMessageManager.kt` | 消息构建、发送、上下文管理、总结生成 |
| `app/src/main/java/com/ai/assistance/operit/services/core/MessageProcessingDelegate.kt` | 消息处理委托、流式响应收集、状态管理 |
| `app/src/main/java/com/ai/assistance/operit/data/repository/ChatHistoryManager.kt` | 聊天历史持久化、导入导出、并发控制 |
| `app/src/main/java/com/ai/assistance/operit/data/model/ChatMessage.kt` | 运行态消息数据类 |
| `app/src/main/java/com/ai/assistance/operit/data/model/MessageEntity.kt` | Room 消息实体 |
| `app/src/main/java/com/ai/assistance/operit/data/model/ChatEntity.kt` | Room 聊天实体 |
| `app/src/main/java/com/ai/assistance/operit/core/tools/AIToolHandler.kt` | 工具注册、执行、流式处理 |
| `app/src/main/java/com/ai/assistance/operit/api/chat/EnhancedAIService.kt` | 增强型AI服务、对话管理、工具执行 |
| `app/src/main/java/com/ai/assistance/operit/api/chat/ChatRuntimeHolder.kt` | 跨会话同步、运行时管理 |
| `app/src/main/java/com/ai/assistance/operit/core/chat/plugins/MessageProcessingPluginRegistry.kt` | 消息处理插件注册表 |
| `web-chat/src/ui/features/chat/viewmodel/ChatViewModel.ts` | 前端聊天视图模型 |
| `web-chat/src/ui/features/chat/viewmodel/UiStateDelegate.ts` | 前端上下文统计与状态委托 |

---

## 八、总结

Operit 的 AI 对话系统通过分层架构和精心设计的组件协作，实现了以下核心能力：

1. **高扩展性**：插件机制允许开发者自定义消息处理流程，PromptHook 系统支持提示词的动态修改
2. **强鲁棒性**：无状态设计 + 并发互斥 + 完善的错误处理 + 流式修订机制
3. **优性能**：流式处理 + 智能裁剪 + 节流控制 + 并发执行上下文隔离
4. **富交互**：支持角色隔离、群组编排、工具调用、自动朗读、Waifu 模式等高级特性
5. **数据安全**：Room 持久化 + 流式导入导出 + 变体管理 + 取消快照保留
6. **多模型支持**：MultiServiceManager 支持多种 LLM 提供商的动态切换

整个系统的设计充分体现了**"关注点分离"**和**"单一职责原则"**，每个组件只负责明确的职责，通过清晰的接口进行协作，从而构建出稳定、可扩展的 AI 对话能力。
