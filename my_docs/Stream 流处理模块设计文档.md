# Stream 流处理模块设计文档

> 基于 Operit 项目 `util/stream` 模块代码分析
> 
> 文档生成时间：2026-05-15

---

## 一、模块概述

### 1.1 设计定位

`Stream` 是一个受 Kotlin Flow 启发的**轻量级异步数据流处理库**，专为 Operit AI Agent 项目设计。它提供了一套简洁、强大且易于使用的 API，用于创建、转换和消费异步数据序列。

### 1.2 核心设计思想

| 设计理念 | 说明 |
|---------|------|
| **冷流 vs 热流** | 冷流（`Stream`）仅在收集时执行；热流（`SharedStream`/`StateStream`）可共享数据 |
| **插件化解析** | 通过 `StreamPlugin` 实现对结构化文本（XML/Markdown）的流式解析 |
| **修订追踪** | 支持 `SAVEPOINT/ROLLBACK` 机制，用于工具调用时的内容回滚 |
| **模式匹配** | 基于 KMP 算法的高效模式匹配，支持捕获组和贪心匹配 |
| **操作符链式调用** | 借鉴函数式编程，支持 `map/filter/flatMap` 等操作符链式组合 |

### 1.3 应用场景

- AI 流式响应的实时解析（工具调用 XML 标签提取）
- Markdown 文本的增量渲染
- 用户输入事件的节流/防抖处理
- 多路数据流的合并与组合
- 结构化协议解析（键值对、JSON、XML 等）

---

## 二、核心架构

### 2.1 类图结构

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Stream 核心接口                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Stream<T> (核心接口)                                                       │
│      ├─ val isLocked: Boolean          // 流是否锁定                        │
│      ├─ val bufferedCount: Int         // 缓存元素数量                      │
│      ├─ suspend fun lock()             // 锁定流                            │
│      ├─ suspend fun unlock()           // 解锁流                            │
│      ├─ fun clearBuffer()              // 清空缓存                          │
│      └─ suspend fun collect(collector) // 收集流                            │
│                                                                             │
│  StreamCollector<T> (收集器接口)                                             │
│      └─ suspend fun emit(value: T)     // 发射值                            │
│                                                                             │
│  AbstractStream<T> (抽象基类)                                                │
│      ├─ Mutex + AtomicBoolean          // 线程安全                          │
│      ├─ ConcurrentLinkedQueue<T>       // 缓冲队列                          │
│      ├─ tryBuffer(value)               // 锁定时的缓冲逻辑                  │
│      └─ emitBufferedItem(item)         // 解锁时的发送逻辑                  │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 热流体系

```
SharedStream<T> (共享流)
    ├─ MutableSharedStream<T> (可变共享流) ← 手动发射事件
    └─ StateStream<T> (状态流)
        └─ MutableStateStream<T> (可变状态流) ← 维护应用状态

转换函数：
    • share(scope, replay, started) → SharedStream
    • state(scope, initialValue) → StateStream
```

### 2.3 修订流体系

```
TextStreamEvent
    ├─ eventType: TextStreamEventType (SAVEPOINT / ROLLBACK)
    └─ id: String (修订标识)

RevisableTextStream (可修订文本流)
    ├─ eventChannel: SharedStream<TextStreamEvent>
    └─ 支持 SAVEPOINT 标记和 ROLLBACK 回滚

TextStreamRevisionTracker (修订追踪器)
    ├─ contentBuffer: StringBuilder       // 当前内容
    ├─ savepoints: LinkedHashMap          // 快照存储
    ├─ savepoint(id)                      // 创建快照
    └─ rollback(id)                       // 回滚到快照
```

---

## 三、Stream 创建方式

### 3.1 从现有数据创建

```kotlin
// 从单个值
val single = streamOf("Hello")

// 从多个值
val multi = streamOf(1, 2, 3, 4, 5)

// 从集合
val listStream = listOf("A", "B", "C").asStream()

// 从序列
val sequenceStream = generateSequence(0) { it + 1 }.asStream()

// 从字符串（逐字符）
val charStream = "Hello".stream()  // Stream<Char>
```

### 3.2 使用构建器创建

```kotlin
// 通用构建器
val counterStream = stream<Int> {
    var count = 0
    while (true) {
        emit(count++)
        delay(1.seconds)
    }
}

// 范围流
val range = rangeStream(start = 1, count = 5)  // 1, 2, 3, 4, 5

// 间隔流
val interval = intervalStream(period = 2.seconds)  // 每 2 秒发射 0, 1, 2...

// 空流
val empty = emptyStream<String>()

// 错误流
val errorStream = streamError<String>(IllegalStateException("Error"))
```

### 3.3 从 Flow 转换

```kotlin
// Flow → Stream
val flow: Flow<Int> = flowOf(1, 2, 3)
val stream = flow.asStream()

// Stream → Flow
val backToFlow = stream.asFlow()
```

---

## 四、Stream 操作符

### 4.1 转换操作

| 操作符 | 说明 | 示例 |
|--------|------|------|
| `map { }` | 类型转换 | `streamOf(1,2,3).map { "Item #$it" }` |
| `flatMap { }` | 扁平化转换 | `streamOf("A","B").flatMap { streamOf("${it}1", "${it}2") }` |
| `chunked(size)` | 分块 | `streamOf(1,2,3,4,5).chunked(3)` → `[1,2,3], [4,5]` |

### 4.2 过滤操作

| 操作符 | 说明 | 示例 |
|--------|------|------|
| `filter { }` | 条件过滤 | `streamOf(1,2,3).filter { it > 1 }` |
| `take(n)` | 取前 n 个 | `streamOf(1,2,3,4).take(2)` → 1, 2 |
| `drop(n)` | 丢弃前 n 个 | `streamOf(1,2,3,4).drop(2)` → 3, 4 |
| `distinctUntilChanged()` | 去重（连续） | `streamOf(1,2,2,3).distinctUntilChanged()` → 1, 2, 3 |

### 4.3 组合操作

| 操作符 | 说明 | 示例 |
|--------|------|------|
| `merge(other)` | 合并（顺序不确定） | `streamA.merge(streamB)` |
| `concatWith(other)` | 连接（顺序确定） | `streamA.concatWith(streamB)` |
| `combine(other) { a,b -> }` | 组合最新值 | `streamA.combine(streamB) { a,b -> "$a$b" }` |

### 4.4 流控制操作

| 操作符 | 说明 | 示例 |
|--------|------|------|
| `lock()` | 锁定流（暂停接收） | `stream.lock()` |
| `unlock()` | 解锁流（发送缓存） | `stream.unlock()` |
| `clearBuffer()` | 清空缓存 | `stream.clearBuffer()` |
| `isLocked` | 检查锁定状态 | `if (stream.isLocked) { ... }` |
| `bufferedCount` | 缓存数量 | `println("缓存：${stream.bufferedCount}")` |

**锁定机制详解**：

```kotlin
val stream = intervalStream(100.milliseconds)

// 锁定时：数据被缓存到 ConcurrentLinkedQueue
stream.lock()

// 解锁时：按顺序发送所有缓存数据
stream.unlock()

// 可选择不发送缓存数据
stream.clearBuffer()
stream.unlock()
```

### 4.5 时间操作

| 操作符 | 说明 | 示例 |
|--------|------|------|
| `throttleFirst(duration)` | 节流（保留窗口内第一个） | `stream.throttleFirst(1.seconds)` |
| `throttleLast(duration)` | 节流（保留窗口内最后一个） | `stream.throttleLast(1.seconds)` |
| `debounce(duration)` | 防抖（静默期后发射） | `stream.debounce(500.milliseconds)` |
| `sample(period)` | 采样（固定间隔发射） | `stream.sample(1.seconds)` |
| `fixedRate(period)` | 固定频率（无值则等待） | `stream.fixedRate(1.seconds)` |
| `timeout(duration)` | 超时异常 | `stream.timeout(5.seconds)` |
| `timeoutTrigger(duration, value)` | 超时发射指定值 | `stream.timeoutTrigger(5.seconds, "TIMEOUT")` |
| `delay(duration)` | 延迟发射 | `stream.delay(1.seconds)` |

### 4.6 错误处理

```kotlin
// 捕获异常
stream
    .catch { error ->
        println("Caught error: ${error.message}")
    }
    .collect { println(it) }

// 最终执行
stream
    .finally {
        println("Stream completed or cancelled")
    }
    .collect { }
```

### 4.7 工具操作

```kotlin
// 副作用
stream.onEach { value ->
    println("Processing: $value")
}

// 超时
stream.timeout(5.seconds)
```

---

## 五、插件系统与流分割

### 5.1 核心概念

`splitBy` 是 Stream 库最强大的特性，允许基于模式匹配将字符流分割成多个带语义的子流。

```
Stream<Char>.splitBy(plugins: List<StreamPlugin>)
    │
    └──► Stream<StreamGroup<StreamPlugin?>>
            ├─ tag: StreamPlugin?  (匹配成功的插件，null 表示默认文本组)
            └─ stream: Stream<String>  (匹配到的内容流)
```

### 5.2 插件状态机

```kotlin
enum class PluginState {
    IDLE,       // 空闲状态，等待匹配开始
    TRYING,     // 尝试匹配中（部分匹配）
    PROCESSING, // 处理中（完全匹配）
    WAITFOR     // 等待确认（积累字符，等待决定去留）
}

interface StreamPlugin {
    val state: PluginState
    fun processChar(c: Char, atStartOfLine: Boolean): Boolean
    fun initPlugin(): Boolean
    fun destroy()
    fun reset()
}
```

**状态转换流程**：

```
IDLE ──[匹配开始]──> TRYING ──[验证成功]──> PROCESSING ──[模式结束]──> IDLE
  │                    │                        │
  │<──[验证失败]────────┘                        │
  │<─────────────────────────────────────────────┘
```

### 5.3 splitBy 实现原理

```kotlin
fun Stream<Char>.splitBy(plugins: List<StreamPlugin>): Stream<StreamGroup<StreamPlugin?>> {
    // 核心逻辑：
    // 1. 所有插件并行处理每个字符
    // 2. 根据插件状态决定字符归属：
    //    - 无插件匹配 → 默认文本组 (tag=null)
    //    - 单个插件匹配 → 该插件组
    // 3. 状态转换时回放缓冲区字符
    // 4. 使用 Channel 实现异步分组
}
```

**关键机制**：

| 机制 | 说明 |
|------|------|
| **缓冲区回放** | 插件从 TRYING→PROCESSING 时，回放缓冲区字符到成功插件 |
| **插件重置** | 匹配失败时重置所有插件状态 |
| **Channel 分组** | 每个组使用独立 Channel，通过 `consumeAsFlow()` 转为 Stream |
| **行首标记** | `atStartOfLine` 参数支持对位置敏感的语法（如 Markdown 标题） |

### 5.4 KMP 模式匹配 DSL

```kotlin
// 定义模式
val pattern = kmpPattern {
    char('[')
    group(1) { greedyStar { noneOf(':') } }  // 捕获 KEY
    char(':')
    group(2) { greedyStar { noneOf(']') } }  // 捕获 VALUE
    char(']')
}

// 构建图
val graph = StreamKmpGraphBuilder().build(pattern)

// 处理字符
val result = graph.processChar('K')
// result: StreamKmpMatchResult.InProgress

// 完全匹配后提取捕获组
if (result is StreamKmpMatchResult.Match) {
    val key = result.groups[1]  // "KEY"
    val value = result.groups[2]  // "VALUE"
}
```

**条件类型**：

```kotlin
// 字符匹配
char('[')
charIgnoreCase('a')

// 范围匹配
range('0', '9')
'a'..'z'

// 集合匹配
anyOf('a', 'e', 'i', 'o', 'u')
chars('a', 'b', 'c')

// 谓词匹配
predicate("digit") { it.isDigit() }
digit()
letter()
whitespace()

// 逻辑组合
notChar(']')
noneOf(':', ']')
notDigit()
```

### 5.5 内置插件

#### StreamXmlPlugin

```kotlin
class StreamXmlPlugin(private val includeTagsInOutput: Boolean = true) : StreamPlugin

// 匹配 XML 标签：<tagName attr="value">content</tagName>
// 支持：
//   - 标签名捕获（GROUP_TAG_NAME）
//   - 内容捕获（GROUP_CONTENT）
//   - 自关闭标签检测（<br/> 视为文本）
//   - Emoji 和标点后的标签识别
```

#### Markdown 插件系列

| 插件类 | 功能 | 示例 |
|--------|------|------|
| `StreamMarkdownHeaderPlugin` | 标题 | `# Heading` |
| `StreamMarkdownFencedCodeBlockPlugin` | 代码块 | ```code``` |
| `StreamMarkdownBlockQuotePlugin` | 引用块 | `> quote` |
| `StreamMarkdownOrderedListPlugin` | 有序列表 | `1. item` |
| `StreamMarkdownUnorderedListPlugin` | 无序列表 | `- item` |
| `StreamMarkdownHorizontalRulePlugin` | 分割线 | `---` |
| `StreamMarkdownBoldPlugin` | 粗体 | `**text**` |
| `StreamMarkdownItalicPlugin` | 斜体 | `*text*` |
| `StreamMarkdownInlineCodePlugin` | 行内代码 | `` `code` `` |
| `StreamMarkdownLinkPlugin` | 链接 | `[text](url)` |
| `StreamMarkdownImagePlugin` | 图片 | `![alt](url)` |
| `StreamMarkdownStrikethroughPlugin` | 删除线 | `~~text~~` |
| `StreamMarkdownUnderlinePlugin` | 下划线 | `__text__` |

### 5.6 两阶段解析策略

对于嵌套结构（如 Markdown），采用两阶段解析：

```
Markdown 文本
    │
    ├──► Stage 1: 块级解析（splitBy blockPlugins）
    │       ├─ Header 块
    │       ├─ CodeBlock 块
    │       ├─ List 块
    │       └─ Paragraph 块
    │
    └──► Stage 2: 内联解析（对每个块的内容 splitBy inlinePlugins）
            ├─ Bold
            ├─ Italic
            ├─ Link
            └─ Code
```

**示例代码**：

```kotlin
// 1. 块级解析
markdownStream.splitBy(blockPlugins).collect { blockGroup ->
    val blockType = getTypeForPlugin(blockGroup.tag)
    val node = MarkdownNode(type = blockType, content = "")
    
    // 流式更新 UI
    blockGroup.stream.collect { chunk ->
        nodes[nodeIndex] = nodes[nodeIndex].copy(
            content = nodes[nodeIndex].content + chunk
        )
    }
    
    // 2. 内联解析（对可包含内联元素的块）
    if (blockType != CODE_BLOCK) {
        val blockContent = collectToString(blockGroup.stream)
        blockContent.stream().splitBy(inlinePlugins).collect { inlineGroup ->
            val inlineType = getTypeForPlugin(inlineGroup.tag)
            val inlineContent = inlineGroup.stream.collectToString()
            node.children.add(MarkdownNode(type = inlineType, content = inlineContent))
        }
    }
}
```

---

## 六、修订追踪机制

### 6.1 使用场景

在 AI 工具调用场景中，AI 可能先输出一段文本，然后发现需要调用工具，此时需要**回滚**已输出的文本，插入工具调用标签，再继续输出。

### 6.2 核心 API

```kotlin
// 创建修订追踪器
val tracker = TextStreamRevisionTracker(initialContent = "")

// 追加内容
tracker.append("Hello, ")
tracker.append("World!")
println(tracker.currentContent())  // "Hello, World!"

// 创建快照
tracker.savepoint("before_tool_call")

// 继续追加
tracker.append(" [tool calling...]")

// 回滚
tracker.rollback("before_tool_call")
println(tracker.currentContent())  // "Hello, World!"

// 替换内容
tracker.replace("New content")
```

### 6.3 事件通道集成

```kotlin
// 创建带事件通道的修订流
val eventChannel = MutableSharedStream<TextStreamEvent>()
val revisableStream = charStream.withTextEventChannel(eventChannel)

// 发送 SAVEPOINT 事件
eventChannel.emit(TextStreamEvent(TextStreamEventType.SAVEPOINT, "id123"))

// 发送 ROLLBACK 事件
eventChannel.emit(TextStreamEvent(TextStreamEventType.ROLLBACK, "id123"))

// 收集器监听事件
eventChannel.collect { event ->
    when (event.eventType) {
        TextStreamEventType.SAVEPOINT → { /* 保存快照 */ }
        TextStreamEventType.ROLLBACK → { /* 回滚内容 */ }
    }
}
```

### 6.4 shareRevisable 扩展

```kotlin
// 将冷流转换为可修订的共享流
val sharedRevisable = coldStream.shareRevisable(
    scope = viewModelScope,
    replay = 0,
    started = StreamStart.EAGERLY,
    onComplete = { /* 完成回调 */ }
)
```

---

## 七、StreamGroup 与流处理器

### 7.1 StreamGroup 结构

```kotlin
class StreamGroup<TAG>(
    val tag: TAG,                    // 标签（插件实例或 null）
    val stream: Stream<String>,      // 数据流
    val processor: StreamProcessor<String, *>? = null,  // 绑定的处理器
    val children: MutableList<StreamGroup<*>> = mutableListOf()  // 子分组
)
```

### 7.2 StreamProcessor 接口

```kotlin
interface StreamProcessor<T, R> {
    suspend fun process(stream: Stream<T>): R
}

// 复合处理器
CompositeStreamProcessor.compose(
    processor1, processor2,  // 中间处理器
    final = finalProcessor   // 最终处理器
)
```

### 7.3 StreamInterceptor 拦截器

```kotlin
// 创建拦截器
val interceptor = StreamInterceptor<Char, String>(sourceStream) { c ->
    c.toString().uppercase()
}

// 获取拦截后的流
val intercepted = interceptor.interceptedStream

// 动态修改处理逻辑
interceptor.setOnEach { c ->
    c.toString().lowercase()
}
```

---

## 八、实际应用案例

### 8.1 AI 流式响应解析（工具调用提取）

```kotlin
// AI 响应流
val aiResponseStream: Stream<Char> = aiResponse.toCharStream()

// 定义 XML 插件
val xmlPlugin = StreamXmlPlugin(includeTagsInOutput = true)

// 分割流
aiResponseStream.splitBy(listOf(xmlPlugin)).collect { group ->
    when (group.tag) {
        is StreamXmlPlugin -> {
            // 提取工具调用
            val toolCallXml = group.stream.collectToString()
            val toolInvocation = parseToolCall(toolCallXml)
            executeTool(toolInvocation)
        }
        null -> {
            // 普通文本，直接显示
            val text = group.stream.collectToString()
            updateUI(text)
        }
    }
}
```

### 8.2 Markdown 流式渲染

```kotlin
// 获取预设插件
val blockPlugins = NestedMarkdownProcessor.getBlockPlugins()
val inlinePlugins = NestedMarkdownProcessor.getInlinePlugins()

// 流式解析
markdownStream.splitBy(blockPlugins).collect { blockGroup ->
    val blockType = getTypeForPlugin(blockGroup.tag)
    val node = MarkdownNode(type = blockType)
    
    // 实时追加内容
    blockGroup.stream.collect { chunk ->
        nodes.add(node.copy(content = node.content + chunk))
    }
    
    // 内联解析
    if (blockType.canContainInline) {
        val content = blockGroup.stream.collectToString()
        content.stream().splitBy(inlinePlugins).collect { inlineGroup ->
            val inlineType = getTypeForPlugin(inlineGroup.tag)
            node.children.add(MarkdownNode(type = inlineType, content = inlineGroup.stream.collectToString()))
        }
    }
}
```

### 8.3 用户输入节流

```kotlin
// 搜索框输入
searchTextStream
    .debounce(300.milliseconds)  // 防抖：停止输入 300ms 后搜索
    .distinctUntilChanged()       // 去重：相同内容不重复搜索
    .filter { it.length >= 2 }    // 过滤：至少 2 个字符
    .collect { query ->
        performSearch(query)
    }
```

### 8.4 多路数据合并

```kotlin
// 合并多个数据源
val combined = stream1
    .merge(stream2)
    .merge(stream3)
    .throttleFirst(100.milliseconds)  // 节流：100ms 内只发射第一个
    .collect { data ->
        updateUI(data)
    }
```

---

## 九、最佳实践

### 9.1 冷流 vs 热流选择

| 场景 | 推荐类型 | 原因 |
|------|---------|------|
| 一次性数据转换 | `Stream` (冷流) | 惰性求值，无需管理生命周期 |
| UI 状态管理 | `StateStream` | 总是有当前值，新收集器立即收到最新值 |
| 事件广播 | `SharedStream` | 多收集器共享，无需重复执行上游 |
| AI 流式响应 | `SharedStream` + 修订追踪 | 支持 SAVEPOINT/ROLLBACK |

### 9.2 异常处理

```kotlin
// 始终使用 catch 处理异常
stream
    .catch { e ->
        when (e) {
            is TimeoutException -> showError("Timeout")
            is CancellationException -> throw e  // 协程取消异常应重新抛出
            else -> showError("Unknown error: ${e.message}")
        }
    }
    .finally {
        cleanup()  // 清理资源
    }
    .launchIn(viewModelScope) { data ->
        updateUI(data)
    }
```

### 9.3 生命周期管理

```kotlin
// 在 ViewModel 中使用 launchIn
class MyViewModel : ViewModel() {
    init {
        dataStream
            .catch { e -> /* 处理错误 */ }
            .launchIn(viewModelScope) { data ->
                _uiState.value = data
            }
    }
    
    // 无需手动管理 Job，viewModelScope 自动取消
}
```

### 9.4 调试技巧

```kotlin
// 使用 onEach 观察每个阶段的数据
stream
    .onEach { v -> Log.d("Before", "Value: $v") }
    .map { transform(it) }
    .onEach { v -> Log.d("After", "Value: $v") }
    .collect { /* 最终处理 */ }
```

### 9.5 插件顺序

对于有重叠分隔符的语法（如 `**` 粗体 vs `*` 斜体），**必须将更长的分隔符插件放在前面**：

```kotlin
// 正确顺序
val inlinePlugins = listOf(
    StreamMarkdownBoldPlugin(),      // ** 在前
    StreamMarkdownItalicPlugin(),    // * 在后
    StreamMarkdownInlineCodePlugin()
)

// 错误顺序会导致 **text** 被解析为两个 * 斜体
```

---

## 十、与 Kotlin Flow 对比

| 特性 | Stream | Kotlin Flow |
|------|--------|-------------|
| **定位** | 轻量级异步流处理 | 完整的响应式流框架 |
| **锁定机制** | ✅ 内置 `lock()/unlock()` | ❌ 需手动实现缓冲 |
| **修订追踪** | ✅ `SAVEPOINT/ROLLBACK` | ❌ 不支持 |
| **插件化解析** | ✅ `splitBy(plugins)` | ❌ 需自定义操作符 |
| **KMP 模式匹配** | ✅ 内置 `StreamKmpGraph` | ❌ 需集成第三方库 |
| **背压处理** | ✅ 自动缓冲 | ✅ 内置背压策略 |
| **操作符丰富度** | ⭐⭐⭐ 基础操作符 | ⭐⭐⭐⭐⭐ 完整操作符集 |
| **性能** | 轻量，启动快 | 较重，功能全 |
| **适用场景** | AI 流式解析、文本处理 | 通用响应式编程 |

---

## 十一、关键文件路径

| 文件 | 路径 | 说明 |
|------|------|------|
| `Stream.kt` | `util/stream/Stream.kt` | 核心接口 + AbstractStream 实现 |
| `StreamBuilders.kt` | `util/stream/StreamBuilders.kt` | 构建器函数（stream/streamOf/intervalStream） |
| `StreamOperators.kt` | `util/stream/StreamOperators.kt` | 操作符实现（map/filter/splitBy） |
| `RevisableTextStream.kt` | `util/stream/RevisableTextStream.kt` | 修订流接口 + 扩展函数 |
| `TextStreamRevisionTracker.kt` | `util/stream/TextStreamRevisionTracker.kt` | 修订追踪器 |
| `StreamPlugin.kt` | `util/stream/plugins/StreamPlugin.kt` | 插件接口 + PluginState 枚举 |
| `StreamXmlPlugin.kt` | `util/stream/plugins/StreamXmlPlugin.kt` | XML 解析插件 |
| `StreamGroup.kt` | `util/stream/StreamGroup.kt` | 流分组 + 处理器 |
| `StreamKmpGraph.kt` | `util/stream/StreamKmpGraph.kt` | KMP 模式匹配图 |
| `README.md` | `util/stream/README.md` | 官方使用指南 |

---

## 十二、设计亮点总结

1. **锁定机制**：通过 `lock()/unlock()` 实现流的暂停与恢复，支持缓冲数据按序发送，适用于需要临时中断数据流的场景（如工具调用等待）。

2. **插件化解析**：`splitBy` 操作符结合 `StreamPlugin` 状态机，实现了高效的流式文本解析，支持 XML/Markdown 等结构化文本的增量处理。

3. **修订追踪**：`TextStreamRevisionTracker` 提供 `SAVEPOINT/ROLLBACK` 机制，完美支持 AI 工具调用场景中的内容回滚需求。

4. **KMP 模式匹配**：基于 Knuth-Morris-Pratt 算法的 `StreamKmpGraph`，支持捕获组、贪心匹配、条件组合，性能优于正则表达式。

5. **操作符链式调用**：借鉴函数式编程思想，提供 `map/filter/flatMap/combine` 等操作符，支持链式组合，代码简洁优雅。

6. **热流体系**：`SharedStream`/`StateStream` 提供响应式状态管理能力，与 ViewModel 无缝集成。

7. **与 Flow 互操作**：通过 `asFlow()/asStream()` 实现与 Kotlin Flow 的双向转换，可逐步引入或与现有 Flow 代码集成。

---

*文档结束*
