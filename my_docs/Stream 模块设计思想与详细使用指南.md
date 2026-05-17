# Stream 模块设计思想与详细使用指南

## 目录

1. [模块概述](#1-模块概述)
2. [核心设计思想](#2-核心设计思想)
3. [架构与代码结构](#3-架构与代码结构)
4. [核心 API 详解](#4-核心-api-详解)
5. [操作符参考](#5-操作符参考)
6. [插件系统与流分割](#6-插件系统与流分割)
7. [KMP 模式匹配引擎](#7-kmp-模式匹配引擎)
8. [使用示例](#8-使用示例)
9. [最佳实践](#9-最佳实践)

---

## 1. 模块概述

Stream 模块是 Operit 项目中一个受 Kotlin Flow 启发的**轻量级异步数据流处理库**。它提供了一套简洁、强大且易于使用的 API，用于创建、转换和消费异步数据序列。

### 1.1 为什么需要 Stream？

虽然 Kotlin 已经提供了强大的 Flow API，但 Stream 模块在以下场景提供了补充价值：

- **流锁定机制**：支持 `lock()`/`unlock()` 控制数据流，适合需要暂停/恢复数据处理的场景（如打字机效果）
- **字符流分割**：基于 KMP 算法的插件系统，支持将字符流按模式分割成语义化的子流
- **更轻量的依赖**：不强制依赖 kotlinx.coroutines 的特定版本
- **与 Flow 的互操作**：无缝转换为 Kotlin Flow，兼容现有生态

### 1.2 三种流类型

| 类型 | 特点 | 类比 |
|------|------|------|
| `Stream<T>` | 冷流，按需执行，支持锁定 | Kotlin Flow |
| `SharedStream<T>` | 热流，多订阅者共享 | SharedFlow |
| `StateStream<T>` | 状态流，始终持有当前值 | StateFlow |

---

## 2. 核心设计思想

### 2.1 冷流与热流分离

```
┌─────────────────────────────────────────────────────────────┐
│                        冷流 (Cold Stream)                     │
│  ┌──────────┐      ┌──────────┐      ┌──────────┐          │
│  │ 创建代码  │ ──▶  │ 收集器启动 │ ──▶  │ 代码执行   │          │
│  └──────────┘      └──────────┘      └──────────┘          │
│  特点：每个收集器独立执行，无收集器时代码不运行                    │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│                        热流 (Hot Stream)                      │
│  ┌──────────┐      ┌──────────┐      ┌──────────┐          │
│  │ 数据产生  │ ──▶  │ 广播给    │ ──▶  │ 多个订阅者 │          │
│  │ (独立运行)│      │ 所有订阅者 │      │          │          │
│  └──────────┘      └──────────┘      └──────────┘          │
│  特点：数据产生与订阅解耦，支持重放(replay)                      │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 流锁定机制（独特设计）

Stream 模块引入了 **流锁定** 概念，这是与 Kotlin Flow 最显著的区别：

```
正常流：  数据 ──▶ 处理 ──▶ 输出

锁定流：  数据 ──▶ [锁定] ──▶ 缓冲区 ──▶ [解锁] ──▶ 处理 ──▶ 输出
                ↑                                    ↑
              lock()                              unlock()
```

**应用场景**：
- 打字机效果：锁定流，用户点击"暂停"时数据进入缓冲区，点击"继续"时批量输出
- 数据节流：临时缓存数据，避免下游处理过载
- 状态同步：在特定操作完成前暂停数据处理

### 2.3 插件驱动的流分割

Stream 模块的核心创新是**基于插件的字符流分割系统**：

```
输入字符流： "Hello <b>world</b>!"
                │
                ▼
┌─────────────────────────────────────┐
│ 插件系统 (XML插件 + 文本默认处理)      │
│                                     │
│  "Hello "  ──▶  StreamGroup(tag=null) │
│  "<b>"     ──▶  StreamGroup(tag=XML)  │
│  "world"   ──▶  StreamGroup(tag=XML)  │
│  "</b>"    ──▶  StreamGroup(tag=XML)  │
│  "!"       ──▶  StreamGroup(tag=null) │
└─────────────────────────────────────┘
```

---

## 3. 架构与代码结构

### 3.1 文件组织

```
app/src/main/java/com/ai/assistance/operit/util/streamnative/stream/
├── Stream.kt                 # 核心 Stream 接口与实现
├── StreamBuilders.kt         # 流构建函数
├── StreamOperators.kt        # 操作符实现
├── HotStream.kt              # 热流 (SharedStream/StateStream)
├── StreamGroup.kt            # 流分组与处理器
├── StringExtensions.kt       # 字符串扩展
├── TextStreamRevisionTracker.kt  # 文本修订追踪
├── RevisableTextStream.kt    # 可修订文本流
├── StreamKmpGraph.kt         # KMP 模式匹配图
├── StreamKmpMatchResult.kt   # KMP 匹配结果
├── StreamOperators.kt        # 操作符
├── plugins/                  # 插件目录
│   ├── StreamPlugin.kt       # 插件接口
│   ├── BaseJsonPlugin.kt     # JSON 基础插件
│   ├── StreamJsonPlugin.kt   # JSON 流插件
│   ├── StreamPureJsonPlugin.kt
│   ├── StreamXmlPlugin.kt    # XML 插件
│   └── StreamMarkdownPlugin.kt  # Markdown 插件
└── README.md                 # 原始文档
```

### 3.2 核心类图

```
┌─────────────────────────────────────────────────────────────┐
│                      Stream<T> (接口)                         │
│  - isLocked: Boolean                                        │
│  - bufferedCount: Int                                       │
│  - lock() / unlock() / clearBuffer()                        │
│  - collect(collector)                                       │
└─────────────────────────────────────────────────────────────┘
                              ▲
                              │ implements
┌─────────────────────────────────────────────────────────────┐
│                 AbstractStream<T> (抽象类)                    │
│  - 实现锁定机制 (Mutex + AtomicBoolean)                      │
│  - 缓冲区管理 (ConcurrentLinkedQueue)                        │
│  - tryBuffer() / emitBufferedItem()                         │
└─────────────────────────────────────────────────────────────┘
                              ▲
              ┌───────────────┼───────────────┐
              │               │               │
    ┌─────────┘       ┌───────┘       ┌───────┘
    │                 │               │
FlowAsStream      stream{}构建器    具体实现...
```

---

## 4. 核心 API 详解

### 4.1 创建 Stream

#### 从现有数据创建

```kotlin
// 从单个值创建
val singleValueStream = streamOf("Hello, Stream!")

// 从多个值创建
val multiValueStream = streamOf(1, 2, 3, 4, 5)

// 从集合创建
val list = listOf("A", "B", "C")
val streamFromList = list.asStream()

// 从序列创建
val streamFromSequence = generateSequence(0) { it + 1 }.asStream()
```

#### 使用构建器创建

```kotlin
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

// 创建一个每秒发射一次递增数字的 Stream
val counterStream = stream<Int> {
    var count = 0
    while (true) {
        emit(count++)
        delay(1.seconds)
    }
}
```

#### 使用预设构建器

```kotlin
// 发射指定范围整数
val rangeStream = rangeStream(start = 1, count = 5) // 发射 1, 2, 3, 4, 5

// 按时间间隔发射
val intervalStream = intervalStream(period = 2.seconds) // 每2秒发射 0, 1, 2, ...
```

### 4.2 消费 Stream

#### collect 收集

```kotlin
suspend fun main() {
    rangeStream(1, 3)
        .collect { number ->
            println("Received: $number")
        }
    // 输出:
    // Received: 1
    // Received: 2
    // Received: 3
}
```

#### launchIn 在协程作用域中启动

```kotlin
// 在 ViewModel 的 CoroutineScope 中启动
counterStream.launchIn(viewModelScope) { count ->
    // 更新 UI
    _uiState.value = "Current count: $count"
}
```

### 4.3 字符串到字符流

```kotlin
// 将字符串转换为字符流
val text = "Hello, Stream!"
val charStream = text.stream()

// 收集字符流
charStream.collect { c ->
    print(c)  // 逐字符输出: Hello, Stream!
}
```

---

## 5. 操作符参考

### 5.1 流控制操作

| 操作符 | 功能 | 示例 |
|--------|------|------|
| `lock()` | 锁定流，暂停接收新数据 | `stream.lock()` |
| `unlock()` | 解锁流，发送缓存数据 | `stream.unlock()` |
| `clearBuffer()` | 清空缓存 | `stream.clearBuffer()` |
| `isLocked` | 检查锁定状态 | `if (stream.isLocked) ...` |
| `bufferedCount` | 查看缓存数量 | `println(stream.bufferedCount)` |

### 5.2 转换操作

```kotlin
// map: 转换每个元素
streamOf(1, 2, 3)
    .map { "Item #$it" }
    .collect { println(it) } // Item #1, Item #2, Item #3

// flatMap: 将每个元素转换为 Stream 并合并
streamOf("A", "B")
    .flatMap { letter ->
        streamOf("${letter}1", "${letter}2")
    }
    .collect { println(it) } // A1, A2, B1, B2
```

### 5.3 过滤操作

```kotlin
streamOf(1, 2, 2, 3, 3, 3, 4, 3)
    .distinctUntilChanged()
    .collect { print("$it ") } // 输出: 1 2 3 4 3
```

### 5.4 组合操作

```kotlin
val streamA = streamOf("A", "B").onEach { delay(100) }
val streamB = streamOf(1, 2).onEach { delay(150) }

// merge: 合并两个流
streamA.merge(streamB)

// combine: 组合两个流的最新值
streamA.combine(streamB) { letter, number -> "$letter$number" }
    .collect { println(it) } // A1, B1, B2

// concatWith: 连接两个流
streamA.concatWith(streamB)
```

### 5.5 时间控制操作

```kotlin
// throttleFirst: 时间窗口内只发射第一个
stream.throttleFirst(windowDuration = 1.seconds)

// throttleLast: 时间窗口内只发射最后一个
stream.throttleLast(windowDuration = 1.seconds)

// debounce: 防抖，指定时间内无新值才发射
stream.debounce(timeout = 500.milliseconds)

// sample: 固定间隔采样
stream.sample(period = 1.seconds)

// timeout: 超时限制
stream.timeout(timeout = 5.seconds)

// delay: 每个元素延迟发射
stream.delay(duration = 100.milliseconds)
```

### 5.6 错误处理

```kotlin
stream<Int> {
    emit(1)
    emit(2)
    throw IllegalStateException("Something went wrong")
}
.catch { error ->
    println("Caught error: ${error.message}")
}
.finally {
    println("Stream completed")
}
.collect {
    println("Received: $it")
}
```

---

## 6. 插件系统与流分割

### 6.1 核心概念

```
┌─────────────────────────────────────────────────────────────┐
│                     插件状态机                                │
│                                                             │
│   IDLE ──▶ TRYING ──▶ PROCESSING ──▶ IDLE                  │
│    ↑         │            │                                 │
│    │         │            └── 匹配完成，处理内容              │
│    │         └── 开始匹配，验证中                             │
│    └────────── 匹配失败，重置                                 │
│                                                             │
│   WAITFOR: 特殊等待状态，积累字符等待确认                      │
└─────────────────────────────────────────────────────────────┘
```

### 6.2 插件接口

```kotlin
interface StreamPlugin {
    val state: PluginState  // IDLE / TRYING / PROCESSING / WAITFOR
    
    // 处理单个字符，返回是否应发射该字符
    fun processChar(c: Char, atStartOfLine: Boolean): Boolean
    
    fun initPlugin(): Boolean
    fun destroy()
    fun reset()
}
```

### 6.3 使用 splitBy 分割流

```kotlin
// 流包含前导文本、一个XML块和尾随文本
val mixedContentStream = "Some leading text<item>Content</item>Some trailing text".asCharStream()

val plugins = listOf(StreamXmlPlugin())

mixedContentStream.splitBy(plugins)
    .collect { group ->
        val groupType = when (group.tag) {
            is StreamXmlPlugin -> "XML"
            null -> "Text" // tag为null表示默认的文本组
        }
        print("发现组 '$groupType': ")
        
        val content = StringBuilder()
        group.stream.collect { content.append(it) }
        
        println(content.toString())
    }

// 输出:
// 发现组 'Text': Some leading text
// 发现组 'XML': <item>Content</item>
// 发现组 'Text': Some trailing text
```

### 6.4 两阶段 Markdown 解析策略

```kotlin
// 1. 块级解析
markdownStream.splitBy(blockPlugins).collect { blockGroup ->
    val blockType = NestedMarkdownProcessor.getTypeForPlugin(blockGroup.tag)
    val node = MarkdownNode(type = blockType, content = "", children = mutableListOf())
    
    // 收集块内容
    val contentBuilder = StringBuilder()
    blockGroup.stream.collect { contentBuilder.append(it) }
    
    // 2. 内联解析
    val blockContent = contentBuilder.toString()
    if (blockContent.isNotEmpty()) {
        val inlineChildren = mutableListOf<MarkdownNode>()
        val charStream = blockContent.toCharStream()
        
        charStream.splitBy(inlinePlugins).collect { inlineGroup ->
            val inlineType = NestedMarkdownProcessor.getTypeForPlugin(inlineGroup.tag)
            val inlineContent = inlineGroup.stream.collectToString()
            
            if (inlineContent.isNotEmpty()) {
                inlineChildren.add(MarkdownNode(type = inlineType, content = inlineContent))
            }
        }
    }
}
```

### 6.5 可用的 Markdown 插件

| 插件类 | 功能 | 主要参数 |
|--------|------|----------|
| `StreamMarkdownHeaderPlugin` | ATX标题 (`# ...`) | `includeMarker` |
| `StreamMarkdownFencedCodeBlockPlugin` | 代码块 (```...```) | `includeFences` |
| `StreamMarkdownBlockQuotePlugin` | 引用块 (`> ...`) | `includeMarker` |
| `StreamMarkdownOrderedListPlugin` | 有序列表 (`1. ...`) | `includeMarker` |
| `StreamMarkdownUnorderedListPlugin` | 无序列表 (`- ...`) | `includeMarker` |
| `StreamMarkdownBoldPlugin` | 粗体 (`**...**`) | `includeAsterisks` |
| `StreamMarkdownItalicPlugin` | 斜体 (`*...*`) | `includeAsterisks` |
| `StreamMarkdownInlineCodePlugin` | 行内代码 (`` `...` ``) | `includeTicks` |
| `StreamMarkdownLinkPlugin` | 链接 (`[text](url)`) | `includeDelimiters` |
| `StreamMarkdownImagePlugin` | 图片 (`![alt](url)`) | `includeDelimiters` |

---

## 7. KMP 模式匹配引擎

### 7.1 设计原理

Stream 模块使用 **Knuth-Morris-Pratt (KMP)** 算法作为模式匹配的核心引擎，相比正则表达式：

- **线性时间复杂度**：O(n) 扫描字符流
- **无需回溯**：利用失败函数 (failure function) 高效跳转
- **流式处理**：支持逐个字符处理，无需预加载整个文本

### 7.2 KMP 图结构

```
┌─────────────────────────────────────────────────────────────┐
│                    KMP 状态转移图                             │
│                                                             │
│    start ──[a]──▶ n1 ──[b]──▶ n2 ──[c]──▶ n3 (final)       │
│      │           │           │                              │
│      │           │           └── failure ──▶ n1             │
│      │           └── failure ──▶ start                      │
│      └── failure ──▶ start                                  │
│                                                             │
│   失败转移：当匹配失败时，利用已匹配前缀跳转到合适状态           │
└─────────────────────────────────────────────────────────────┘
```

### 7.3 kmpPattern DSL

```kotlin
// 定义匹配模式
val matcher = StreamKmpGraphBuilder().build(kmpPattern {
    char('[')
    group(GROUP_KEY) { greedyStar { noneOf(':') } }
    char(':')
    group(GROUP_VALUE) { greedyStar { noneOf(']') } }
    char(']')
})

// 处理字符
when (val result = matcher.processChar(c)) {
    is StreamKmpMatchResult.Match -> {
        // 完全匹配，提取捕获组
        val key = result.groups[GROUP_KEY]
        val value = result.groups[GROUP_VALUE]
    }
    is StreamKmpMatchResult.InProgress -> {
        // 匹配进行中
    }
    is StreamKmpMatchResult.NoMatch -> {
        // 匹配失败
    }
}
```

### 7.4 条件组合

```kotlin
// 基础条件
val digit = PredicateCondition("digit") { it.isDigit() }
val letter = PredicateCondition("letter") { it.isLetter() }

// 条件组合
val alphanumeric = digit + letter        // OR
val complex = digit * letter             // AND
val notDigit = !digit                    // NOT

// 预定义条件
val condition = DIGITS                   // 数字
val condition = LETTERS                  // 字母
val condition = ALPHANUMERIC             // 字母或数字
val condition = WHITESPACE               // 空白字符
val condition = ANY_CHAR                 // 任意字符
```

---

## 8. 使用示例

### 8.1 基础流处理

```kotlin
class StreamExample {
    
    // 创建简单流
    fun createSimpleStream(): Stream<Int> = streamOf(1, 2, 3, 4, 5)
    
    // 流转换
    suspend fun transformExample() {
        createSimpleStream()
            .filter { it % 2 == 0 }      // 只保留偶数
            .map { it * it }              // 平方
            .take(2)                      // 取前2个
            .collect { println(it) }      // 输出: 4, 16
    }
    
    // 流锁定示例
    suspend fun lockExample() {
        val stream = intervalStream(100.milliseconds)
        
        // 锁定流
        stream.lock()
        
        // 此时数据进入缓冲区...
        delay(1.seconds)
        println("缓冲区大小: ${stream.bufferedCount}")
        
        // 解锁，缓存数据按顺序输出
        stream.unlock()
    }
}
```

### 8.2 热流状态管理

```kotlin
class MyViewModel : ViewModel() {
    // 管理一次性事件
    private val _events = MutableSharedStream<String>()
    val events: SharedStream<String> = _events

    // 管理UI状态
    private val _uiState = MutableStateStream("Initial State")
    val uiState: StateStream<String> = _uiState

    fun performAction() {
        viewModelScope.launch {
            _uiState.value = "Loading..."
            delay(1000)
            _uiState.value = "Action Successful"
            _events.emit("Show success toast")
        }
    }
}
```

### 8.3 自定义插件

```kotlin
class KeyValuePlugin : StreamPlugin {
    override var state: PluginState = PluginState.IDLE
    private var matcher: StreamKmpGraph

    init {
        matcher = StreamKmpGraphBuilder().build(kmpPattern {
            char('[')
            group(1) { greedyStar { noneOf(':') } }
            char(':')
            group(2) { greedyStar { noneOf(']') } }
            char(']')
        })
        reset()
    }

    override fun processChar(c: Char, atStartOfLine: Boolean): Boolean {
        return when (val result = matcher.processChar(c)) {
            is StreamKmpMatchResult.Match -> {
                state = PluginState.IDLE
                matcher.reset()
                true
            }
            is StreamKmpMatchResult.InProgress -> {
                state = if (result.isMatchStarted) 
                    PluginState.PROCESSING 
                else 
                    PluginState.TRYING
                true
            }
            is StreamKmpMatchResult.NoMatch -> {
                if (state != PluginState.IDLE) {
                    state = PluginState.IDLE
                    matcher.reset()
                }
                false
            }
        }
    }

    override fun reset() {
        matcher.reset()
        state = PluginState.IDLE
    }
    
    override fun initPlugin() = true
    override fun destroy() {}
}
```

### 8.4 与 Flow 互操作

```kotlin
// Flow 转 Stream
val flow = flowOf(1, 2, 3)
val stream = flow.asStream()

// Stream 转 Flow
val stream = streamOf(1, 2, 3)
val flow = stream.asFlow()

// 在 ViewModel 中使用
class MyViewModel : ViewModel() {
    fun observeData() {
        dataStream
            .asFlow()
            .flowOn(Dispatchers.IO)
            .collect { data ->
                // 使用 Flow 的操作符
            }
    }
}
```

---

## 9. 最佳实践

### 9.1 选择合适的流类型

```kotlin
// 短暂、一次性的数据转换 → 冷流
val apiResponseStream = stream {
    val response = api.fetchData()
    emit(response)
}

// 需要在多个地方共享的数据 → 热流
val userState = MutableStateStream(User())

// 一次性事件 → SharedStream
val toastEvents = MutableSharedStream<String>()
```

### 9.2 异常处理

```kotlin
stream {
    emit(fetchData())
}
.catch { error ->
    // 优雅处理异常
    emit(defaultValue)
}
.finally {
    // 清理资源
    cleanup()
}
.collect { data ->
    updateUI(data)
}
```

### 9.3 生命周期管理

```kotlin
class MyActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 使用 lifecycleScope 自动管理生命周期
        dataStream.launchIn(lifecycleScope) { data ->
            updateUI(data)
        }
    }
}
```

### 9.4 调试技巧

```kotlin
// 使用 onEach 观察流的数据
stream
    .onEach { println("Before map: $it") }
    .map { it * 2 }
    .onEach { println("After map: $it") }
    .collect()

// 启用详细日志
StreamLogger.setVerboseEnabled(true)
```

### 9.5 性能优化

```kotlin
// 使用 throttle 减少频繁更新
searchQueryStream
    .debounce(300.milliseconds)  // 等待用户停止输入
    .flatMap { query -> searchApi(query) }
    .collect { results ->
        updateSearchResults(results)
    }

// 使用 chunked 批量处理
sensorDataStream
    .chunked(100)  // 每100个数据点处理一次
    .collect { batch ->
        processBatch(batch)
    }
```

---

## 总结

Stream 模块通过以下设计提供了强大的异步数据流处理能力：

1. **冷流/热流分离**：满足不同场景的数据处理需求
2. **流锁定机制**：独特的暂停/恢复能力，适合打字机效果等场景
3. **插件系统**：基于 KMP 算法的高效字符流分割，支持复杂的文本解析
4. **与 Flow 互操作**：无缝集成 Kotlin 协程生态
5. **丰富的操作符**：提供完整的流转换、过滤、组合和时间控制能力

该模块在 Operit 项目中主要用于：
- Markdown 文本的流式解析和渲染
- AI 回复的打字机效果输出
- 实时数据流的处理和转换
- 复杂文本协议的解析
