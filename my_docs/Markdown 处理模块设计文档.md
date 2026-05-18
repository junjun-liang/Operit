# Markdown 处理模块设计文档

> 基于 Operit 项目 `util/markdown` 模块代码分析
>
> 文档生成时间：2026-05-15

---

## 一、模块概述

### 1.1 设计定位

Markdown 处理模块是 Operit AI Agent 的**文本解析与渲染基础设施**，负责将 AI 生成的 Markdown 格式文本解析为结构化的节点树，并绑定到 Compose UI 进行渲染。模块与 `util/stream` 流处理系统深度集成，支持流式增量解析。

### 1.2 核心设计思想

| 设计理念 | 说明 |
|---------|------|
| **流式解析** | 基于 `Stream<Char>` 流式处理，支持增量渲染，无需等待完整文本 |
| **两阶段解析** | 先解析块级元素（Block），再解析内联元素（Inline），处理嵌套结构 |
| **插件化架构** | 使用 `StreamPlugin` 系统识别 Markdown 语法标记，可扩展新语法 |
| **Compose 集成** | `MarkdownNode` 使用 `SnapshotStateList`，支持响应式 UI 更新 |
| **SmartString** | 带缓存的字符串构建器，优化流式场景下的字符串拼接性能 |

### 1.3 架构总览

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              输入层                                          │
│  AI 流式响应 / 本地 Markdown 文本                                            │
│       │                                                                     │
│       ├──► String.toCharStream() → Stream<Char>                            │
│       └──► Stream<String>.toCharStream() → Stream<Char>                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                              块级解析层                                       │
│  Stream<Char>.splitBy(blockPlugins)                                         │
│       ├──► StreamMarkdownHeaderPlugin        → HEADER                      │
│       ├──► StreamMarkdownFencedCodeBlockPlugin → CODE_BLOCK                │
│       ├──► StreamMarkdownBlockQuotePlugin    → BLOCK_QUOTE                 │
│       ├──► StreamMarkdownOrderedListPlugin   → ORDERED_LIST                │
│       ├──► StreamMarkdownUnorderedListPlugin → UNORDERED_LIST              │
│       ├──► StreamMarkdownHorizontalRulePlugin → HORIZONTAL_RULE            │
│       ├──► StreamMarkdownBlockLaTeXPlugin    → BLOCK_LATEX                 │
│       ├──► StreamMarkdownTablePlugin         → TABLE                       │
│       ├──► StreamXmlPlugin                   → XML_BLOCK                   │
│       └──► (无匹配)                          → PLAIN_TEXT                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                              内联解析层                                       │
│  对每个 Block 内容 splitBy(inlinePlugins)                                   │
│       ├──► StreamMarkdownBoldPlugin          → BOLD                        │
│       ├──► StreamMarkdownItalicPlugin        → ITALIC                      │
│       ├──► StreamMarkdownInlineCodePlugin    → INLINE_CODE                 │
│       ├──► StreamMarkdownLinkPlugin          → LINK                        │
│       ├──► StreamMarkdownStrikethroughPlugin → STRIKETHROUGH               │
│       ├──► StreamMarkdownUnderlinePlugin     → UNDERLINE                   │
│       ├──► StreamMarkdownInlineLaTeXPlugin   → INLINE_LATEX                │
│       └──► (无匹配)                          → PLAIN_TEXT                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                              数据模型层                                       │
│  MarkdownNode                                                               │
│       ├── type: MarkdownProcessorType                                       │
│       ├── content: SmartString                                              │
│       └── children: SnapshotStateList<MarkdownNode>                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                              UI 渲染层                                        │
│  Compose 组件根据 MarkdownNode 类型渲染对应样式                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 二、核心组件详解

### 2.1 MarkdownProcessorType（处理器类型枚举）

```kotlin
enum class MarkdownProcessorType {
    // 块级处理器
    HEADER,           // 标题 # ## ###
    BLOCK_QUOTE,      // 引用块 >
    CODE_BLOCK,       // 代码块 ```
    ORDERED_LIST,     // 有序列表 1. 2. 3.
    UNORDERED_LIST,   // 无序列表 - * +
    HORIZONTAL_RULE,  // 分割线 --- ***
    BLOCK_LATEX,      // LaTeX 块级公式 $$...$$
    TABLE,            // 表格 |a|b|
    XML_BLOCK,        // XML 块级元素 <tool>...</tool>

    // 内联处理器
    BOLD,             // 粗体 **text**
    ITALIC,           // 斜体 *text*
    INLINE_CODE,      // 行内代码 `code`
    LINK,             // 链接 [text](url)
    IMAGE,            // 图片 ![alt](url)
    STRIKETHROUGH,    // 删除线 ~~text~~
    UNDERLINE,        // 下划线 __text__
    INLINE_LATEX,     // LaTeX 行内公式 $...$

    // 纯文本
    PLAIN_TEXT,       // 普通文本
    HTML_BREAK        // HTML 换行 <br>
}
```

### 2.2 MarkdownNode（数据模型）

```kotlin
class MarkdownNode(val type: MarkdownProcessorType, initialContent: String = "") {
    val content: SmartString = SmartString(initialContent)
    val children: SnapshotStateList<MarkdownNode> = mutableStateListOf()
}
```

**设计特点**：
- `content` 使用 `SmartString` 而非普通 `String`，支持高效追加和缓存
- `children` 使用 `SnapshotStateList`，与 Compose 的响应式系统无缝集成
- 节点树结构支持无限嵌套（如列表中的引用块中的粗体文本）

**稳定版本**：

```kotlin
@Stable
data class MarkdownNodeStable(
    val type: MarkdownProcessorType,
    val content: String,
    val children: List<MarkdownNodeStable>
)
```

用于需要稳定快照的场景（如跨线程传递、序列化）。

### 2.3 NestedMarkdownProcessor（嵌套处理器）

`NestedMarkdownProcessor` 是 Markdown 解析的核心协调器，定义了块级和内联插件列表，并提供插件到处理器类型的映射。

#### 块级插件列表

```kotlin
fun getBlockPlugins(): List<StreamPlugin> = listOf(
    StreamMarkdownHeaderPlugin(),
    StreamMarkdownFencedCodeBlockPlugin(),
    StreamMarkdownBlockQuotePlugin(includeMarker = false),
    StreamMarkdownOrderedListPlugin(),
    StreamMarkdownUnorderedListPlugin(includeMarker = false),
    StreamMarkdownHorizontalRulePlugin(),
    // LaTeX 块级公式：同时支持 $$...$$ 和 \[...\]
    StreamMarkdownBlockLaTeXPlugin(includeDelimiters = false),
    StreamMarkdownBlockBracketLaTeXPlugin(includeDelimiters = true),
    StreamMarkdownTablePlugin(),
    StreamMarkdownImagePlugin(),
    StreamXmlPlugin(includeTagsInOutput = true)
)
```

#### 内联插件列表

```kotlin
fun getInlinePlugins(): List<StreamPlugin> = listOf(
    StreamMarkdownBoldPlugin(includeAsterisks = false),
    StreamMarkdownItalicPlugin(includeAsterisks = false),
    StreamMarkdownInlineCodePlugin(includeTicks = false),
    StreamMarkdownLinkPlugin(),
    StreamMarkdownStrikethroughPlugin(includeDelimiters = false),
    StreamMarkdownUnderlinePlugin(),
    // LaTeX 行内公式：支持 $...$ 与 \(...\)
    StreamMarkdownInlineLaTeXPlugin(includeDelimiters = false),
    StreamMarkdownInlineParenLaTeXPlugin(includeDelimiters = true)
)
```

#### 插件类型映射

```kotlin
internal fun getTypeForPlugin(plugin: StreamPlugin?): MarkdownProcessorType {
    return when (plugin) {
        is StreamMarkdownHeaderPlugin -> MarkdownProcessorType.HEADER
        is StreamMarkdownBlockQuotePlugin -> MarkdownProcessorType.BLOCK_QUOTE
        is StreamMarkdownFencedCodeBlockPlugin -> MarkdownProcessorType.CODE_BLOCK
        is StreamMarkdownOrderedListPlugin -> MarkdownProcessorType.ORDERED_LIST
        is StreamMarkdownUnorderedListPlugin -> MarkdownProcessorType.UNORDERED_LIST
        is StreamMarkdownHorizontalRulePlugin -> MarkdownProcessorType.HORIZONTAL_RULE
        is StreamMarkdownBoldPlugin -> MarkdownProcessorType.BOLD
        is StreamMarkdownItalicPlugin -> MarkdownProcessorType.ITALIC
        is StreamMarkdownInlineCodePlugin -> MarkdownProcessorType.INLINE_CODE
        is StreamMarkdownLinkPlugin -> MarkdownProcessorType.LINK
        is StreamMarkdownImagePlugin -> MarkdownProcessorType.IMAGE
        is StreamMarkdownStrikethroughPlugin -> MarkdownProcessorType.STRIKETHROUGH
        is StreamMarkdownUnderlinePlugin -> MarkdownProcessorType.UNDERLINE
        is StreamMarkdownInlineLaTeXPlugin -> MarkdownProcessorType.INLINE_LATEX
        is StreamMarkdownInlineParenLaTeXPlugin -> MarkdownProcessorType.INLINE_LATEX
        is StreamMarkdownBlockLaTeXPlugin -> MarkdownProcessorType.BLOCK_LATEX
        is StreamMarkdownBlockBracketLaTeXPlugin -> MarkdownProcessorType.BLOCK_LATEX
        is StreamMarkdownTablePlugin -> MarkdownProcessorType.TABLE
        is StreamXmlPlugin -> MarkdownProcessorType.XML_BLOCK
        else -> MarkdownProcessorType.PLAIN_TEXT
    }
}
```

### 2.4 流处理器

#### StringCollectorProcessor

```kotlin
class StringCollectorProcessor : StreamProcessor<String, String> {
    override suspend fun process(stream: Stream<String>): String {
        val content = StringBuilder()
        stream.collect { content.append(it) }
        return content.toString()
    }
}
```

将字符流收集为单个字符串，用于获取块级元素的完整内容。

#### MarkdownNodeProcessor

```kotlin
class MarkdownNodeProcessor(private val type: MarkdownProcessorType) :
    StreamProcessor<String, MarkdownNode> {

    override suspend fun process(stream: Stream<String>): MarkdownNode =
        withContext(Dispatchers.Default) {
            val contentBuilder = StringBuilder()
            stream.collect { contentBuilder.append(it) }
            MarkdownNode(type, initialContent = contentBuilder.toString())
        }
}
```

将字符流转换为 `MarkdownNode`，在 `Dispatchers.Default` 上执行以避免阻塞主线程。

### 2.5 MarkdownUIBinder（UI 绑定器）

```kotlin
class MarkdownUIBinder<T>(
    private val component: T,
    private val renderStrategy: suspend (T, MarkdownNode) -> Unit
) {
    suspend fun bind(group: StreamGroup<MarkdownProcessorType>) {
        val nodes = processGroupToNodes(group)
        renderStrategy(component, nodes)
    }

    private suspend fun processGroupToNodes(
        group: StreamGroup<MarkdownProcessorType>
    ): MarkdownNode {
        val content = StringBuilder()
        group.stream.collect { content.append(it) }

        val node = MarkdownNode(group.tag, initialContent = content.toString())

        // 递归处理子组
        for (child in group.children) {
            val childGroup = child as StreamGroup<MarkdownProcessorType>
            val childNode = processGroupToNodes(childGroup)
            node.children.add(childNode)
        }

        return node
    }
}
```

**设计特点**：
- 泛型设计 `T`，可绑定到任意 UI 组件（Compose、View 系统等）
- `renderStrategy` 为渲染策略回调，由调用方实现具体渲染逻辑
- 递归处理子组，构建完整的节点树

---

## 三、SmartString（智能字符串）

### 3.1 设计定位

`SmartString` 是专为流式文本处理设计的高性能字符串构建器，通过缓存 `toString()` 结果避免频繁创建 String 对象。

### 3.2 核心实现

```kotlin
class SmartString(initialContent: String = "") {
    private val builder = StringBuilder(initialContent)
    private var cachedString: String? = if (initialContent.isEmpty()) "" else initialContent
    private var lastLength = initialContent.length

    // 重载 + 操作符追加字符串
    operator fun plus(content: String): SmartString {
        if (content.isNotEmpty()) {
            builder.append(content)
            cachedString = null  // 标记缓存失效
        }
        return this
    }

    // 重载 + 操作符追加字符
    operator fun plus(char: Char): SmartString {
        builder.append(char)
        cachedString = null  // 标记缓存失效
        return this
    }

    // 智能 toString - 只在内容变化时重新生成
    override fun toString(): String {
        val currentLength = builder.length

        // 如果长度没变且有缓存，直接返回缓存
        if (currentLength == lastLength && cachedString != null) {
            return cachedString!!
        }

        // 内容已变化，重新生成字符串并缓存
        lastLength = currentLength
        cachedString = builder.toString()
        return cachedString!!
    }

    val length: Int get() = builder.length
    fun isEmpty(): Boolean = builder.isEmpty()
    fun isNotEmpty(): Boolean = builder.isNotEmpty()
    fun clear() { builder.clear(); cachedString = ""; lastLength = 0 }
    fun take(n: Int): String { /* ... */ }
    fun append(content: String): SmartString = this + content
    fun append(char: Char): SmartString = this + char
}
```

### 3.3 性能优化原理

```
普通 StringBuilder 场景：
    每次 toString() → 创建新 String 对象 → GC 压力

SmartString 优化：
    第一次 toString() → 生成 String 并缓存
    后续 toString()（无变化）→ 直接返回缓存
    有变化时 → 重新生成并更新缓存
```

**适用场景**：
- AI 流式响应的逐字追加
- Markdown 节点内容的增量更新
- 任何频繁追加但偶尔读取的场景

### 3.4 使用示例

```kotlin
val smart = SmartString("Hello")

// 追加操作（缓存失效）
smart + ", "
smart + "World!"

// 第一次 toString() → 生成并缓存
println(smart.toString())  // "Hello, World!"

// 第二次 toString() → 直接返回缓存（无变化）
println(smart.toString())  // 直接返回缓存，无新对象创建
```

---

## 四、两阶段解析流程

### 4.1 完整解析流程

```
输入 Markdown 文本
    │
    ├──► Stage 1: 块级解析
    │       String.toCharStream()
    │           .splitBy(NestedMarkdownProcessor.getBlockPlugins())
    │           .collect { blockGroup ->
    │               val blockType = getTypeForPlugin(blockGroup.tag)
    │               val node = MarkdownNode(blockType)
    │
    │               // 流式追加内容
    │               blockGroup.stream.collect { chunk ->
    │                   node.content + chunk
    │               }
    │
    │               // Stage 2: 内联解析（对可包含内联的块）
    │               if (blockType != CODE_BLOCK && blockType != BLOCK_LATEX) {
    │                   val blockContent = collectToString(blockGroup.stream)
    │                   blockContent.toCharStream()
    │                       .splitBy(NestedMarkdownProcessor.getInlinePlugins())
    │                       .collect { inlineGroup ->
    │                           val inlineType = getTypeForPlugin(inlineGroup.tag)
    │                           val inlineNode = MarkdownNode(inlineType)
    │                           inlineGroup.stream.collect { chunk ->
    │                               inlineNode.content + chunk
    │                           }
    │                           node.children.add(inlineNode)
    │                       }
    │               }
    │           }
    │
    └──► 输出 MarkdownNode 树
```

### 4.2 流式增量解析

```kotlin
// AI 流式响应逐字追加
aiResponseStream.toCharStream().splitBy(blockPlugins).collect { blockGroup ->
    val blockType = getTypeForPlugin(blockGroup.tag)
    val node = findOrCreateNode(blockType)

    // 实时追加内容到 UI
    blockGroup.stream.collect { chunk ->
        node.content + chunk  // SmartString 高效追加
        // Compose 自动重组，UI 实时更新
    }
}
```

### 4.3 支持的 Markdown 语法

#### 块级语法

| 语法 | 示例 | 处理器类型 |
|------|------|-----------|
| 标题 | `# H1` `## H2` | `HEADER` |
| 代码块 | ` ```kotlin ` | `CODE_BLOCK` |
| 引用块 | `> quote` | `BLOCK_QUOTE` |
| 有序列表 | `1. item` | `ORDERED_LIST` |
| 无序列表 | `- item` | `UNORDERED_LIST` |
| 分割线 | `---` | `HORIZONTAL_RULE` |
| 表格 | `\|a\|b\|` | `TABLE` |
| LaTeX 块 | `$$E=mc^2$$` | `BLOCK_LATEX` |
| XML 块 | `<tool>...</tool>` | `XML_BLOCK` |

#### 内联语法

| 语法 | 示例 | 处理器类型 |
|------|------|-----------|
| 粗体 | `**text**` | `BOLD` |
| 斜体 | `*text*` | `ITALIC` |
| 行内代码 | `` `code` `` | `INLINE_CODE` |
| 链接 | `[text](url)` | `LINK` |
| 图片 | `![alt](url)` | `IMAGE` |
| 删除线 | `~~text~~` | `STRIKETHROUGH` |
| 下划线 | `__text__` | `UNDERLINE` |
| LaTeX 行内 | `$E=mc^2$` | `INLINE_LATEX` |

---

## 五、与 Stream 模块的集成

### 5.1 字符流转换

```kotlin
// String → Stream<Char>
fun String.toCharStream(): Stream<Char> {
    return stream {
        for (c in this@toCharStream) {
            emit(c)
        }
    }
}

// Stream<String> → Stream<Char>
fun Stream<String>.toCharStream(): Stream<Char> {
    val charStream = stream {
        this@toCharStream.collect { str ->
            for (c in str) {
                emit(c)
            }
        }
    }
    // 保留事件通道（SAVEPOINT/ROLLBACK）
    val carrier = this as? TextStreamEventCarrier ?: return charStream
    return charStream.withTextEventChannel(carrier.eventChannel)
}
```

### 5.2 与修订追踪集成

```kotlin
// 带修订事件的字符流
val revisableStream = aiResponseStream.toCharStream()

// 当收到 SAVEPOINT 事件时保存节点状态
// 当收到 ROLLBACK 事件时回滚到保存的状态
// 实现工具调用时的内容回滚
```

---

## 六、使用方法

### 6.1 解析 Markdown 文本

```kotlin
// 块级解析
val markdownText = "# Hello\n\nThis is **bold** text."

markdownText.toCharStream()
    .splitBy(NestedMarkdownProcessor.getBlockPlugins())
    .collect { blockGroup ->
        val blockType = NestedMarkdownProcessor.getTypeForPlugin(blockGroup.tag)
        println("Block: $blockType")

        blockGroup.stream.collect { chunk ->
            print(chunk)
        }
        println()
    }
```

### 6.2 完整解析为节点树

```kotlin
// 块级解析
val nodes = mutableStateListOf<MarkdownNode>()

markdownText.toCharStream()
    .splitBy(NestedMarkdownProcessor.getBlockPlugins())
    .collect { blockGroup ->
        val blockType = NestedMarkdownProcessor.getTypeForPlugin(blockGroup.tag)
        val node = MarkdownNode(blockType)

        // 收集块级内容
        blockGroup.stream.collect { chunk ->
            node.content + chunk
        }

        // 内联解析
        if (blockType != MarkdownProcessorType.CODE_BLOCK
            && blockType != MarkdownProcessorType.BLOCK_LATEX) {

            val blockContent = node.content.toString()
            blockContent.toCharStream()
                .splitBy(NestedMarkdownProcessor.getInlinePlugins())
                .collect { inlineGroup ->
                    val inlineType = NestedMarkdownProcessor.getTypeForPlugin(inlineGroup.tag)
                    val inlineNode = MarkdownNode(inlineType)
                    inlineGroup.stream.collect { chunk ->
                        inlineNode.content + chunk
                    }
                    node.children.add(inlineNode)
                }
        }

        nodes.add(node)
    }
```

### 6.3 Compose UI 渲染

```kotlin
@Composable
fun MarkdownContent(nodes: List<MarkdownNode>) {
    LazyColumn {
        items(nodes) { node ->
            when (node.type) {
                MarkdownProcessorType.HEADER -> {
                    val level = node.content.toString().count { it == '#' }
                    Text(
                        text = node.content.toString().removePrefix("#".repeat(level)).trim(),
                        style = when (level) {
                            1 -> MaterialTheme.typography.headlineLarge
                            2 -> MaterialTheme.typography.headlineMedium
                            else -> MaterialTheme.typography.headlineSmall
                        }
                    )
                }
                MarkdownProcessorType.BOLD -> {
                    Text(
                        text = node.content.toString(),
                        fontWeight = FontWeight.Bold
                    )
                }
                MarkdownProcessorType.CODE_BLOCK -> {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = node.content.toString(),
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
                MarkdownProcessorType.PLAIN_TEXT -> {
                    Text(text = node.content.toString())
                }
                // ... 其他类型
            }
        }
    }
}
```

### 6.4 流式增量渲染

```kotlin
@Composable
fun StreamingMarkdownContent(aiResponseStream: Stream<String>) {
    val nodes = remember { mutableStateListOf<MarkdownNode>() }

    LaunchedEffect(aiResponseStream) {
        aiResponseStream.toCharStream()
            .splitBy(NestedMarkdownProcessor.getBlockPlugins())
            .collect { blockGroup ->
                val blockType = NestedMarkdownProcessor.getTypeForPlugin(blockGroup.tag)
                val node = findOrCreateNode(nodes, blockType)

                blockGroup.stream.collect { chunk ->
                    node.content + chunk  // SmartString 高效追加
                    // Compose 自动检测到 SnapshotStateList 变化，触发重组
                }
            }
    }

    LazyColumn {
        items(nodes) { node ->
            RenderMarkdownNode(node)
        }
    }
}
```

---

## 七、关键文件路径

| 文件 | 路径 | 说明 |
|------|------|------|
| `MarkdownProcessor.kt` | `util/markdown/MarkdownProcessor.kt` | Markdown 解析核心 |
| `SmartString.kt` | `util/markdown/SmartString.kt` | 智能字符串构建器 |
| `StreamMarkdownPlugin.kt` | `util/stream/plugins/StreamMarkdownPlugin.kt` | Markdown 流式插件 |
| `StreamPlugin.kt` | `util/stream/plugins/StreamPlugin.kt` | 插件基类接口 |

---

## 八、设计亮点

1. **流式解析**：基于 `Stream<Char>` 的增量解析，支持 AI 流式响应的实时渲染，无需等待完整文本

2. **两阶段解析**：先块级后内联，清晰处理嵌套结构（如列表中的引用块中的粗体文本）

3. **Compose 集成**：`SnapshotStateList` 与 Compose 响应式系统无缝集成，内容变化自动触发 UI 重组

4. **SmartString 优化**：带缓存的字符串构建器，避免流式场景下频繁创建 String 对象，减少 GC 压力

5. **插件化扩展**：基于 `StreamPlugin` 架构，可轻松添加新的 Markdown 语法支持

6. **LaTeX 支持**：同时支持 `$...$` / `$$...$$` 和 `\(...\)` / `\[...\]` 两种 LaTeX 语法风格

7. **XML 集成**：通过 `StreamXmlPlugin` 支持 XML 标签的识别和处理，与 AI 工具调用格式兼容

8. **泛型 UI 绑定**：`MarkdownUIBinder<T>` 支持绑定到任意 UI 框架，不仅限于 Compose

---

*文档结束*
