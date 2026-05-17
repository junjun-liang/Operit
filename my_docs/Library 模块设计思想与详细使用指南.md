# Library 模块设计思想与详细使用指南

## 一、模块概述

`library` 模块是 Operit 应用的**长期记忆（Long-term Memory）管理核心**，负责将对话内容自动分析、提取并存储为结构化的**记忆图谱（Memory Graph）**。该模块实现了从原始对话到可检索、可关联的知识网络的完整闭环。

模块包含两个核心类：
- **`MemoryLibrary`**：记忆库管理主类，提供对话分析、记忆提取、图谱构建、自动分类等功能
- **`MemoryAutoSaveScheduler`**：长期记忆自动保存调度器，按配置周期轮询并批量处理记忆候选

---

## 二、核心设计思想

### 2.1 知识图谱驱动的记忆模型

不同于简单的键值对或文本块存储，`library` 模块采用**知识图谱（Knowledge Graph）**模型来组织记忆：

- **节点（Entity）**：每个记忆是一个节点，包含标题、内容、标签、可信度、重要性等属性
- **关系（Link）**：记忆之间可以建立带权重的有向链接，描述它们之间的语义关联
- **主问题（Main Problem）**：每次对话分析会提取一个核心问题作为图谱的根节点
- **别名消解（Alias Resolution）**：LLM 自动识别新实体是否为已有实体的别名，避免重复创建

### 2.2 混合检索策略（Hybrid Search）

在分析对话前，模块会执行**本地粗检索 + LLM 精决策**的两阶段策略：

1. **本地粗检索**：使用向量相似度 + 关键词 + 标签 + 图谱边权重等多维度评分，从本地数据库检索出 Top-15 候选记忆
2. **LLM 精决策**：将候选记忆作为上下文提供给 LLM，由 LLM 判断哪些需要更新、合并或创建新节点

这种设计既保证了检索效率，又利用了 LLM 的语义理解能力进行精细决策。

### 2.3 自动去重与合并

模块内置了多种去重机制：

- **标题级去重**：检索候选记忆时，主动发现同标题的多条记忆并提示 LLM 合并
- **别名识别**：LLM 可以标记新实体为已有实体的别名，系统会自动关联到原始节点
- **显式合并指令**：LLM 可以输出 `merge` 指令，将多个源记忆合并为一个新记忆

### 2.4 用户偏好自动提取

对话分析过程中，LLM 会自动识别用户透露的个人信息（年龄、性别、性格、职业、AI 风格偏好等），并自动更新到用户偏好配置中。这使得 AI 助手能够越用越"懂"用户。

### 2.5 周期性自动保存

`MemoryAutoSaveScheduler` 实现了后台定时轮询机制：

- 按用户配置的时间间隔（默认 30 分钟）扫描记忆候选
- 按聊天会话分组，批量提取对话历史
- 满足最小候选数阈值后才触发保存，避免频繁调用 AI
- 支持多 Profile 隔离，每个用户配置独立调度

---

## 三、核心架构与数据流

### 3.1 记忆保存完整流程

```
┌─────────────────┐
│  用户对话结束    │
└────────┬────────┘
         ▼
┌─────────────────┐
│ 创建保存候选     │  (由外部触发，插入 MemoryAutoSaveCandidate)
└────────┬────────┘
         ▼
┌─────────────────┐
│ 调度器轮询      │  (MemoryAutoSaveScheduler.runOnce)
└────────┬────────┘
         ▼
┌─────────────────┐
│ 按聊天分组候选   │
└────────┬────────┘
         ▼
┌─────────────────┐
│ 提取对话历史    │  (最多 48 条消息)
└────────┬────────┘
         ▼
┌─────────────────┐
│ 内容预处理      │  (去除工具结果、清理标记、去除系统消息)
└────────┬────────┘
         ▼
┌─────────────────┐
│ 构建检索查询    │  (提取核心问题 + 归一化)
└────────┬────────┘
         ▼
┌─────────────────┐
│ 本地混合检索    │  (向量+关键词+标签+边权重 → Top-15 候选)
└────────┬────────┘
         ▼
┌─────────────────┐
│ 检测重复标题    │
└────────┬────────┘
         ▼
┌─────────────────┐
│ 构造 LLM Prompt │  (系统提示 + 候选记忆 + 现有文件夹 + 用户偏好)
└────────┬────────┘
         ▼
┌─────────────────┐
│ LLM 结构化分析  │  (返回 JSON: main/new/links/update/merge/user)
└────────┬────────┘
         ▼
┌─────────────────┐
│ 解析并应用结果  │  (创建/更新/合并节点，建立链接，更新偏好)
└─────────────────┘
```

### 3.2 自动分类流程

```
┌─────────────────┐
│ 触发自动分类    │  (手动调用 autoCategorizeMemoriesAsync)
└────────┬────────┘
         ▼
┌─────────────────┐
│ 查询未分类记忆   │  (folderPath 为空的记忆)
└────────┬────────┘
         ▼
┌─────────────────┐
│ 分批处理(10条)  │
└────────┬────────┘
         ▼
┌─────────────────┐
│ 构造分类 Prompt │  (现有文件夹 + 记忆摘要)
└────────┬────────┘
         ▼
┌─────────────────┐
│ LLM 返回分类    │  (JSON 数组: [{title, folder}])
└────────┬────────┘
         ▼
┌─────────────────┐
│ 更新记忆分类    │  (updateMemory → 自动重新生成 embedding)
└─────────────────┘
```

---

## 四、核心类详解

### 4.1 MemoryLibrary（记忆库管理）

**类型**：`object`（单例）

**主要职责**：
- 对话内容的结构化分析与记忆提取
- 知识图谱的构建与维护
- 记忆的自动分类
- 用户偏好的自动提取与更新

**核心方法**：

#### `initialize(context: Context)`
初始化记忆库，加载 API 偏好配置。

#### `saveMemoryAsync(...)` / `saveMemoryNow(...)`
保存记忆的异步/同步入口。接受对话历史、内容、AI 服务等参数，执行完整的分析-提取-存储流程。

**参数说明**：
| 参数 | 类型 | 说明 |
|------|------|------|
| `context` | `Context` | Android 上下文 |
| `toolHandler` | `AIToolHandler` | 工具处理器实例 |
| `conversationHistory` | `List<Pair<String, String>>` | 对话历史（role → content） |
| `content` | `String` | 需要分析的对话内容（通常是 AI 回复） |
| `aiService` | `AIService` | 用于分析的 AI 服务实例 |
| `profileIdOverride` | `String?` | 可选的 Profile ID 覆盖 |

#### `autoCategorizeMemoriesAsync(context: Context, aiService: AIService)`
自动为未分类记忆分配文件夹路径。后台异步执行，分批处理（每批 10 条）。

**内部关键流程**：
1. `saveMemory()` → 内容预处理 → `generateAnalysis()` → 解析并应用结果
2. `autoCategorizeMemories()` → 分批检索 → `categorizeBatch()` → `parseAndApplyCategorization()`

### 4.2 MemoryAutoSaveScheduler（自动保存调度器）

**类型**：`class`

**主要职责**：
- 按配置周期后台轮询记忆候选
- 按聊天会话分组批量处理
- 管理多 Profile 的独立调度状态

**核心方法**：

#### `start()`
启动调度器循环。在 IO 线程上每分钟检查一次。

#### `runOnce()`
执行一轮扫描和处理。使用原子标志防止并发执行。

#### `scanAndProcessCandidates()`
扫描所有 Profile 的候选，满足条件则调用 `processChatCandidateGroup()`。

**处理条件**：
- 当前时间 >= 下次运行时间
- 候选总数 >= `MIN_TOTAL_CANDIDATES_TO_EXTRACT`（5 条）

#### `processChatCandidateGroup(...)`
处理单个聊天会话的候选组：
1. 标记为处理中
2. 查询对应消息（最多 48 条，按时间倒序）
3. 过滤有效对话历史（必须包含 user + assistant）
4. 调用 `MemoryLibrary.saveMemoryNow()`
5. 成功则删除候选，失败则标记失败状态

---

## 五、AI 分析输出格式

LLM 返回的 JSON 结构如下：

```json
{
  "main": ["标题", "内容", ["标签1", "标签2"], "文件夹路径"],
  "new": [
    ["实体标题", "实体内容", ["标签"], "文件夹路径", "别名指向(可选)"]
  ],
  "links": [
    ["源标题", "目标标题", "关系类型", "描述", 权重]
  ],
  "update": [
    ["要更新的标题", "新内容", "更新原因", 新可信度(可选), 新重要性(可选)]
  ],
  "merge": [
    {
      "source_titles": ["源标题1", "源标题2"],
      "new_title": "合并后标题",
      "new_content": "合并后内容",
      "new_tags": ["标签"],
      "folder_path": "文件夹路径",
      "reason": "合并原因"
    }
  ],
  "user": {
    "age": "25",
    "gender": "male",
    "personality": "开朗",
    "identity": "程序员",
    "occupation": "软件工程师",
    "aiStyle": "简洁专业"
  }
}
```

**字段说明**：
- `main`：核心问题节点（整个对话的中心主题）
- `new`：新发现的实体/知识点
- `links`：实体之间的关系链接
- `update`：对已有记忆的更新
- `merge`：将多个记忆合并为一个
- `user`：提取的用户偏好信息（`<UNCHANGED>` 表示不变）

---

## 六、内容预处理策略

### 6.1 工具结果剪枝

对话中的 `<tool_result>` 标签内容会被替换为占位符，大幅减少 Token 消耗：

```kotlin
// 原始内容
<tool_result id="search" status="success">...大量搜索结果...</tool_result>

// 处理后
[工具结果已剪枝: id=search, status=success]
```

### 6.2 历史消息清理

- 移除所有 `system` 角色消息
- 移除用户消息中的 `<memory>` 标签内容（避免循环引用）
- 截取最近 10 条历史作为分析上下文
- 对 AI 回复截取前 3000 字符

### 6.3 检索查询构建

从对话中提取核心问题文本，优先使用标记格式：

```
问题: xxx
解决方案: xxx
```

或英文格式：
```
Question: xxx
Solution: xxx
```

去除工具标签、URL、Markdown 标记后，取前 500 字符作为检索查询。

---

## 七、使用示例

### 7.1 手动保存记忆

```kotlin
// 初始化
MemoryLibrary.initialize(context)

// 准备对话历史
val conversationHistory = listOf(
    "user" to "如何学习 Kotlin？",
    "assistant" to "Kotlin 是一种现代 JVM 语言..."
)

// 获取专用的记忆分析 AI 服务
val memoryService = EnhancedAIService.getAIServiceForFunction(context, FunctionType.MEMORY)

// 异步保存
MemoryLibrary.saveMemoryAsync(
    context = context,
    toolHandler = AIToolHandler.getInstance(context),
    conversationHistory = conversationHistory,
    content = "Kotlin 是一种现代 JVM 语言...",
    aiService = memoryService,
    onSuccess = {
        Log.d("Memory", "记忆保存成功")
    },
    onError = { e ->
        Log.e("Memory", "记忆保存失败", e)
    }
)

// 同步保存（在协程中）
lifecycleScope.launch {
    MemoryLibrary.saveMemoryNow(
        context = context,
        toolHandler = toolHandler,
        conversationHistory = conversationHistory,
        content = aiResponse,
        aiService = memoryService
    )
}
```

### 7.2 启动自动保存调度器

```kotlin
class MyApplication : Application() {
    private lateinit var memoryScheduler: MemoryAutoSaveScheduler

    override fun onCreate() {
        super.onCreate()
        
        // 在应用启动时初始化调度器
        memoryScheduler = MemoryAutoSaveScheduler(
            context = applicationContext,
            scope = GlobalScope // 或使用应用级 CoroutineScope
        )
        memoryScheduler.start()
    }
}
```

### 7.3 触发自动分类

```kotlin
// 在设置页面或后台任务中触发
val memoryService = EnhancedAIService.getAIServiceForFunction(context, FunctionType.MEMORY)
MemoryLibrary.autoCategorizeMemoriesAsync(context, memoryService)
```

### 7.4 查询下次自动保存时间

```kotlin
val scheduler = MemoryAutoSaveScheduler.getInstance()
val minutesUntilNext = scheduler?.getMinutesUntilNextRun(profileId)
// 返回距离下次执行的分钟数
```

---

## 八、配置与调优

### 8.1 自动保存间隔

通过 `MemorySearchSettingsPreferences` 配置：

```kotlin
val settings = MemorySearchSettingsPreferences(context, profileId)
settings.saveAutoSaveIntervalMinutes(30) // 设置 30 分钟间隔
```

### 8.2 检索评分权重

```kotlin
val searchConfig = MemorySearchSettingsPreferences(context, profileId).load()
// 可调整以下权重：
// - keywordWeight: 关键词匹配权重
// - tagWeight: 标签匹配权重
// - semanticWeight: 向量语义权重
// - edgeWeight: 图谱边权重
```

### 8.3 关键常量

| 常量 | 值 | 说明 |
|------|-----|------|
| `LOOP_TICK_MS` | 60,000ms | 调度器轮询间隔 |
| `DEFAULT_POLL_INTERVAL_MS` | 1,800,000ms (30min) | 默认保存间隔 |
| `MAX_MESSAGES_PER_CHAT` | 48 | 每次处理最大消息数 |
| `MIN_TOTAL_CANDIDATES_TO_EXTRACT` | 5 | 触发保存的最小候选数 |

---

## 九、最佳实践

1. **及时初始化**：应用启动时调用 `MemoryLibrary.initialize(context)`
2. **使用专用 AI 服务**：记忆分析应使用 `FunctionType.MEMORY` 对应的 AI 服务，可配置专用轻量级模型以降低成本
3. **合理设置间隔**：自动保存间隔不宜过短（建议 >= 15 分钟），避免频繁调用 AI
4. **错误处理**：保存失败时，候选会被标记为失败状态，可在设置中提供重试入口
5. **多 Profile 隔离**：每个用户配置有独立的记忆库和调度状态，切换用户时无需额外处理
6. **监控 Token 消耗**：每次 AI 调用后会自动更新 Token 统计，可通过 `ApiPreferences` 查看
