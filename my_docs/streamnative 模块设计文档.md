# streamnative 模块软件设计架构与使用文档

## 目录

1. [模块概述](#1-模块概述)
2. [整体架构](#2-整体架构)
3. [JNI 桥接层设计](#3-jni-桥接层设计)
4. [C++ 原生引擎层](#4-c-原生引擎层)
5. [Kotlin 流式操作符层](#5-kotlin-流式操作符层)
6. [核心数据结构](#6-核心数据结构)
7. [插件系统详解](#7-插件系统详解)
8. [Session 状态机](#8-session-状态机)
9. [构建配置](#9-构建配置)
10. [使用方法](#10-使用方法)
11. [性能特性](#11-性能特性)
12. [文件索引](#12-文件索引)

---

## 1. 模块概述

**streamnative** 是 Operit 项目中基于 **JNI + C++** 实现的高性能文本解析模块，专门用于：

- **Markdown 流式解析**：支持块级（Block）与内联（Inline）双级解析
- **XML 标签分割**：提取 XML 标签内容与纯文本片段
- **增量/流式处理**：通过 Session 机制保持跨 chunk 的解析状态

### 1.1 设计动机

| 维度 | 纯 Kotlin Stream 方案 | streamnative 方案 |
|------|----------------------|-------------------|
| 字符处理 | JVM Char/对象开销 | C++ 直接操作 UTF-16 数组 |
| 状态机内存 | 堆分配，GC 压力大 | 栈/原生堆分配，零 GC |
| 跨 chunk 状态 | 无状态，每次重建 | Session 有状态，增量解析 |
| 模式匹配 | Kotlin KMP 实现 | C++ KMP，无运行时开销 |
| 适用场景 | 短文本、简单结构 | 长文本、流式输入、复杂 Markdown |

### 1.2 模块边界

```
┌─────────────────────────────────────────────┐
│           Kotlin 应用层 (UI / ViewModel)       │
│    Stream<Char> / Stream<String> 操作符链      │
├─────────────────────────────────────────────┤
│           Kotlin JNI 接口层                    │
│    NativeMarkdownSplitter / NativeXmlSplitter │
├─────────────────────────────────────────────┤
│           JNI 桥接 (C++)                       │
│    native_markdown_splitter.cpp               │
│    native_xml_splitter.cpp                    │
├─────────────────────────────────────────────┤
│           C++ 原生引擎层                       │
│    StreamOperators.cpp / MarkdownSession      │
│    StreamPlugin 体系 / KmpMatcher             │
└─────────────────────────────────────────────┘
```

---

## 2. 整体架构

### 2.1 分层架构图

```
输入流 (Char/String)
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│  Kotlin 层                                                   │
│  ┌─────────────────┐  ┌─────────────────────────────────────┐│
│  │ NativeMarkdown  │  │ NativeMarkdownStreamOperators       ││
│  │ Splitter        │  │ (nativeMarkdownSplitByBlock/Inline) ││
│  │ - Session 管理   │  │ - 流式分组                          ││
│  │ - 一次性解析     │  │ - Channel 桥接                      ││
│  └─────────────────┘  └─────────────────────────────────────┘│
│  ┌─────────────────┐                                        │
│  │ NativeXmlSplitter                                       ││
│  │ - XML 标签分割   │                                        │
│  └─────────────────┘                                        │
└─────────────────────────────────────────────────────────────┘
                              │ JNI
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  C++ 层                                                      │
│  ┌─────────────────────────────────────────────────────────┐│
│  │ native_markdown_splitter.cpp / native_xml_splitter.cpp  ││
│  │ - JNI 方法实现                                           ││
│  │ - jchar* 直接读取，IntArray 返回                         ││
│  └─────────────────────────────────────────────────────────┘│
│  ┌─────────────────────────────────────────────────────────┐│
│  │ StreamOperators.cpp                                      ││
│  │ - MarkdownSession 状态机                                 ││
│  │ - splitByXml 实现                                        ││
│  └─────────────────────────────────────────────────────────┘│
│  ┌─────────────────────────────────────────────────────────┐│
│  │ plugins/                                                 ││
│  │ - StreamPlugin.h (基类接口)                               ││
│  │ - StreamMarkdownPlugin.h/.cpp (20+ Markdown 解析器)       ││
│  │ - StreamXmlPlugin.h/.cpp (XML 标签解析器)                 ││
│  │ - StreamJsonPlugin.h/.cpp / BaseJsonPlugin.h/.cpp         ││
│  │ - StreamPureJsonPlugin.h/.cpp                            ││
│  └─────────────────────────────────────────────────────────┘│
│  ┌─────────────────────────────────────────────────────────┐│
│  │ StreamKmpGraph.h (KMP 模式匹配引擎)                       ││
│  └─────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
```

### 2.2 数据流

```
Kotlin String/chunk
       │
       ▼
┌──────────────┐     JNI      ┌─────────────────────┐
│ nativePush() │ ───────────▶ │ markdownSessionPush │
│  (IntArray)  │              │  - 解析 chunk        │
└──────────────┘              │  - 更新状态机        │
       │                      │  - 返回 Segment 数组 │
       │                      └─────────────────────┘
       │                               │
       │                               ▼
       │                      ┌─────────────────────┐
       │                      │ Segment {type, start, end}
       │                      │ type: MarkdownProcessorType ordinal
       │                      │ start/end: 全局字符索引
       │                      └─────────────────────┘
       │                               │
       │                               ▼ JNI 返回
       │                      IntArray [t1,s1,e1, t2,s2,e2, ...]
       │
       ▼
┌─────────────────────────────┐
│ IntArray → Kotlin 业务处理   │
│ - toInlineStableNodes()     │
│ - StreamGroup 分组输出       │
└─────────────────────────────┘
```

---

## 3. JNI 桥接层设计

### 3.1 库加载

所有 JNI 入口类均通过 `System.loadLibrary("streamnative")` 加载原生库：

```kotlin
// NativeMarkdownSplitter.kt / NativeXmlSplitter.kt
init {
    System.loadLibrary("streamnative")
}
```

对应 CMake 构建产物为 `libstreamnative.so`。

### 3.2 NativeMarkdownSplitter

[NativeMarkdownSplitter.kt](file:///home/meizu/Documents/my_agent_projects/Operit/app/src/main/java/com/ai/assistance/operit/util/streamnative/NativeMarkdownSplitter.kt)

```kotlin
object NativeMarkdownSplitter {

    init { System.loadLibrary("streamnative") }

    private external fun nativeCreateBlockSession(): Long
    private external fun nativeCreateInlineSession(): Long
    private external fun nativeDestroySession(handle: Long)
    private external fun nativePush(handle: Long, chunk: String): IntArray

    class Session internal constructor(private val handle: Long) {
        fun push(chunk: String): IntArray = nativePush(handle, chunk)
        fun destroy() = nativeDestroySession(handle)
    }

    fun createBlockSession(): Session = Session(nativeCreateBlockSession())
    fun createInlineSession(): Session = Session(nativeCreateInlineSession())

    // 便捷方法：一次性解析内联元素
    fun parseInlineToStableNodes(content: String): List<MarkdownNodeStable> {
        if (content.isEmpty()) return emptyList()
        val session = createInlineSession()
        return try {
            session.push(content).toInlineStableNodes(content)
        } finally {
            session.destroy()
        }
    }
}
```

**设计要点**：
- `Session` 封装原生指针（`jlong handle`），对外隐藏 C++ 对象生命周期
- `push()` 返回 `IntArray`，每 3 个 int 构成一个 `Segment`（type, start, end）
- 必须显式调用 `destroy()` 释放 C++ 内存，避免原生内存泄漏

### 3.3 NativeXmlSplitter

[NativeXmlSplitter.kt](file:///home/meizu/Documents/my_agent_projects/Operit/app/src/main/java/com/ai/assistance/operit/util/streamnative/NativeXmlSplitter.kt)

```kotlin
object NativeXmlSplitter {
    init { System.loadLibrary("streamnative") }

    private external fun nativeSplitXmlSegments(content: String): IntArray

    fun splitXmlTag(content: String): List<List<String>> {
        val results = mutableListOf<List<String>>()
        val segments = nativeSplitXmlSegments(content)
        // type == 1 → XML 标签，提取 tagName
        // type == 0 → 普通文本
        // 返回 [[tagName, chunk], ["text", chunk], ...]
    }
}
```

### 3.4 JNI 方法签名映射

| Kotlin 声明 | C++ 实现 | 说明 |
|------------|---------|------|
| `nativeCreateBlockSession()` | `createMarkdownBlockSession()` | 创建块级 Session |
| `nativeCreateInlineSession()` | `createMarkdownInlineSession()` | 创建内联 Session |
| `nativeDestroySession(handle)` | `destroyMarkdownSession(session)` | 释放 C++ 内存 |
| `nativePush(handle, chunk)` | `markdownSessionPush(session, chars, len)` | 推送文本并解析 |
| `nativeSplitXmlSegments(content)` | `splitByXml(chars, len)` | XML 分割 |

### 3.5 C++ JNI 实现片段

[native_markdown_splitter.cpp](file:///home/meizu/Documents/my_agent_projects/Operit/app/src/main/cpp/streamnative/native_markdown_splitter.cpp)

```cpp
// 辅助函数：将 C++ Segment 数组转换为 jintArray
inline jintArray segmentsToJIntArray(
    JNIEnv* env,
    const std::vector<streamnative::Segment>& segments
) {
    jintArray out = env->NewIntArray(static_cast<jsize>(segments.size() * 3));
    std::vector<jint> flat;
    flat.reserve(segments.size() * 3);
    for (const auto& s : segments) {
        flat.push_back(static_cast<jint>(s.type));
        flat.push_back(static_cast<jint>(s.start));
        flat.push_back(static_cast<jint>(s.end));
    }
    env->SetIntArrayRegion(out, 0, static_cast<jsize>(flat.size()), flat.data());
    return out;
}

// 创建块级 Session
extern "C" JNIEXPORT jlong JNICALL
Java_com_ai_assistance_operit_util_streamnative_NativeMarkdownSplitter_nativeCreateBlockSession(
    JNIEnv* /*env*/, jobject /*thiz*/
) {
    auto* s = streamnative::createMarkdownBlockSession();
    return reinterpret_cast<jlong>(s);
}

// 推送文本
extern "C" JNIEXPORT jintArray JNICALL
Java_com_ai_assistance_operit_util_streamnative_NativeMarkdownSplitter_nativePush(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jstring chunk
) {
    auto* s = reinterpret_cast<streamnative::MarkdownSession*>(handle);
    const jsize len = env->GetStringLength(chunk);
    const jchar* chars = env->GetStringChars(chunk, nullptr);
    std::vector<streamnative::Segment> segments =
        streamnative::markdownSessionPush(s, chars, static_cast<int>(len));
    env->ReleaseStringChars(chunk, chars);
    return segmentsToJIntArray(env, segments);
}
```

**关键设计**：
- 使用 `GetStringChars` / `ReleaseStringChars` 直接获取 JVM 字符串的 UTF-16 数组，零拷贝
- Session 指针通过 `reinterpret_cast<jlong>` 在 Kotlin 与 C++ 之间传递

---

## 4. C++ 原生引擎层

### 4.1 核心文件

| 文件 | 职责 |
|------|------|
| [StreamOperators.h](file:///home/meizu/Documents/my_agent_projects/Operit/app/src/main/cpp/streamnative/StreamOperators.h) / .cpp | MarkdownSession 定义、Session 工厂、XML 分割实现 |
| [StreamGroup.h](file:///home/meizu/Documents/my_agent_projects/Operit/app/src/main/cpp/streamnative/StreamGroup.h) | `Segment` 结构体定义 `{int type; int start; int end;}` |
| [StreamKmpGraph.h](file:///home/meizu/Documents/my_agent_projects/Operit/app/src/main/cpp/streamnative/StreamKmpGraph.h) | KMP 模式匹配引擎 `KmpMatcher` |
| [StreamPlugin.h](file:///home/meizu/Documents/my_agent_projects/Operit/app/src/main/cpp/streamnative/plugins/StreamPlugin.h) | 插件基类接口与状态枚举 |

### 4.2 Segment 编码

```cpp
struct Segment {
    int type;   // MarkdownProcessorType ordinal，或 -1 表示分组边界 (SEG_BREAK)
    int start;  // 全局字符起始索引（含）
    int end;    // 全局字符结束索引（不含）
};
```

JNI 返回时展平为 `IntArray`：`[type1, start1, end1, type2, start2, end2, ...]`

### 4.3 MarkdownSession 状态机

[StreamOperators.cpp](file:///home/meizu/Documents/my_agent_projects/Operit/app/src/main/cpp/streamnative/StreamOperators.cpp) 中的 `MarkdownSession` 类是核心解析引擎。

#### 4.3.1 状态变量

```cpp
class MarkdownSession {
    std::vector<PluginEntry> plugins_;   // 注册的插件列表
    int globalOffset_ = 0;               // 全局字符偏移计数器
    bool atStartOfLine_ = true;          // 下一字符是否位于行首

    // 活跃插件（已确认匹配）
    StreamPlugin* activePlugin_ = nullptr;
    int activeTag_ = MD_PLAIN_TEXT;
    int activeIndex_ = -1;

    // 评估缓冲区（多插件竞争阶段）
    int evalStartGlobal_ = -1;
    std::vector<char16_t> evaluationBuffer_;
    std::vector<uint32_t> evaluationEmitMask_;

    // WAITFOR 延迟决策机制
    bool waitforActive_ = false;
    bool waitforAtStartOfLine_ = false;
    std::vector<WaitforPending> waitforPending_;
    std::deque<PendingChar> pendingChars_;
};
```

#### 4.3.2 处理流程

```
输入字符 c
    │
    ▼
┌─────────────────┐
│ 1. WAITFOR 处理 │ ← 上一字符触发了插件的 WAITFOR 状态，
│    (延迟决策)    │   需要当前字符来决定是确认还是回退
└─────────────────┘
    │
    ▼ (无 WAITFOR)
┌─────────────────┐
│ 2. 活跃插件处理 │ ← 已有插件进入 PROCESSING，直接消费字符
│    (PROCESSING) │
└─────────────────┘
    │
    ▼ (无活跃插件)
┌─────────────────┐
│ 3. 评估模式      │ ← 所有插件竞争匹配起始模式
│    (Evaluation) │   - 缓冲字符到 evaluationBuffer_
│                 │   - 记录每个插件的 emitMask
│                 │   - 一旦有插件进入 PROCESSING，
│                 │     刷新缓冲区并激活该插件
└─────────────────┘
    │
    ▼ (无插件匹配)
┌─────────────────┐
│ 4. 回退为纯文本  │ ← 所有插件都放弃（IDLE），
│    (Plain Text) │   将缓冲区内容作为 PLAIN_TEXT 输出
└─────────────────┘
```

#### 4.3.3 关键机制：WAITFOR

某些 Markdown 结构（如链接 `[text](url)`）在起始模式匹配后，需要看到后续字符才能确认是否为真正的该结构。WAITFOR 机制允许插件在匹配起始模式后进入“待定”状态，将是否发射（emit）当前字符的决策推迟到下一个字符到来时。

```cpp
if (activePlugin_->state() == PluginState::WAITFOR) {
    // 延迟决策：记录当前字符的 globalIndex 和 shouldEmit
    waitforActive_ = true;
    waitforAtStartOfLine_ = (c == u'\n');
    waitforPending_.push_back({globalIndex, shouldEmit});
    return; // 不立即输出
}
```

如果下一个字符确认匹配（插件进入 PROCESSING），则发射所有 pending 字符；
如果确认不匹配（插件回到 IDLE），则将 pending 字符作为 PLAIN_TEXT 发射，并重置所有插件重新评估当前字符。

#### 4.3.4 HTML_BREAK 特殊处理

```cpp
constexpr int MD_HTML_BREAK = 18;  // <br>, <br/>, <br />, <br >

// 在评估阶段，如果缓冲区完全匹配 HTML break 标签，
// 直接作为 MD_HTML_BREAK 输出，不参与插件竞争
if (isHtmlBreakFullMatch(evaluationBuffer_)) {
    // 发射为 MD_HTML_BREAK
    // 清空缓冲区，重置插件
}
```

### 4.4 XML 分割实现

`splitByXml()` 是一个无状态函数，直接遍历字符：

```cpp
std::vector<Segment> splitByXml(const jchar* chars, int len) {
    StreamXmlPlugin xmlPlugin(true);
    // type 0: 默认文本
    // type 1: XML 标签内容
    // 使用 evalStart / activePlugin 两段式识别
}
```

与 MarkdownSession 不同，XML 分割不需要跨 chunk 保持状态，因此不采用 Session 模式。

---

## 5. Kotlin 流式操作符层

### 5.1 文件

[NativeMarkdownStreamOperators.kt](file:///home/meizu/Documents/my_agent_projects/Operit/app/src/main/java/com/ai/assistance/operit/util/streamnative/NativeMarkdownStreamOperators.kt)

### 5.2 核心操作符

```kotlin
// 对 Char 流进行块级 Markdown 分割
fun Stream<Char>.nativeMarkdownSplitByBlock(
    flushIntervalMs: Long? = null,
    maxDeltaChars: Int? = null,
): Stream<StreamGroup<MarkdownProcessorType?>>

// 对 Char 流进行内联 Markdown 分割
fun Stream<Char>.nativeMarkdownSplitByInline(
    flushIntervalMs: Long? = null,
    maxDeltaChars: Int? = null,
): Stream<StreamGroup<MarkdownProcessorType?>>

// 对 String 流进行块级/内联分割（重载）
@JvmName("nativeMarkdownSplitByBlockString")
fun Stream<String>.nativeMarkdownSplitByBlock(...)

@JvmName("nativeMarkdownSplitByInlineString")
fun Stream<String>.nativeMarkdownSplitByInline(...)
```

### 5.3 内部实现架构

```
上游 Stream<Char> / Stream<String>
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│ coroutineScope                                               │
│  ┌─────────────────────────────────────────────────────────┐│
│  │ launch (解析协程)                                        ││
│  │  - 创建 Native Session                                   ││
│  │  - fullContent: 完整内容 StringBuilder                   ││
│  │  - deltaBuffer: 待推送内容 StringBuilder                 ││
│  │  - mutex / flushMutex: 线程安全                          ││
│  │                                                          ││
│  │  Channel 管理:                                           ││
│  │    defaultTextChannel  → StreamGroup(null, stream)       ││
│  │    activePluginChannel   → StreamGroup(tag, stream)      ││
│  │                                                          ││
│  │  flushDelta():                                           ││
│  │    1. 提取 deltaBuffer                                   ││
│  │    2. session.push(delta) → IntArray segments            ││
│  │    3. 解析 segments → List<Action(type, text)>           ││
│  │    4. 根据 type 开关 Channel，发送 text                  ││
│  │                                                          ││
│  │  刷新触发条件:                                            ││
│  │    - 字符 '\n'                                           ││
│  │    - maxDeltaChars 达到阈值                              ││
│  │    - flushIntervalMs 定时器                              ││
│  │    - 上游流结束                                          ││
│  └─────────────────────────────────────────────────────────┘│
│  ┌─────────────────────────────────────────────────────────┐│
│  │ for (group in groupChannel)                              ││
│  │   collector.emit(group)  // 向下游发射 StreamGroup       ││
│  └─────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
```

### 5.4 StreamGroup 语义

```kotlin
data class StreamGroup<T>(
    val tag: T?,           // null 表示默认文本组
    val stream: Stream<R>  // 该组的内容流
)
```

- `tag == null`：PLAIN_TEXT 组，通过 `defaultTextChannel` 发送
- `tag != null`：特定 Markdown 类型组，通过 `activePluginChannel` 发送
- `typeOrdinal < 0`（即 `SEG_BREAK`）：关闭当前组，准备开启新组

---

## 6. 核心数据结构

### 6.1 MarkdownProcessorType（Kotlin）

[MarkdownProcessor.kt](file:///home/meizu/Documents/my_agent_projects/Operit/app/src/main/java/com/ai/assistance/operit/util/markdown/MarkdownProcessor.kt)

```kotlin
enum class MarkdownProcessorType {
    // 块级 (ordinals 0-8)
    HEADER,           // 0
    BLOCK_QUOTE,      // 1
    CODE_BLOCK,       // 2
    ORDERED_LIST,     // 3
    UNORDERED_LIST,   // 4
    HORIZONTAL_RULE,  // 5
    BLOCK_LATEX,      // 6
    TABLE,            // 7
    XML_BLOCK,        // 8

    // 内联 (ordinals 9-16)
    BOLD,             // 9
    ITALIC,           // 10
    INLINE_CODE,      // 11
    LINK,             // 12
    IMAGE,            // 13
    STRIKETHROUGH,    // 14
    UNDERLINE,        // 15
    INLINE_LATEX,     // 16

    // 特殊
    PLAIN_TEXT,       // 17
    HTML_BREAK        // 18
}
```

**C++ 侧常量必须与 Kotlin 枚举 ordinal 严格一致**：

```cpp
// StreamOperators.cpp
constexpr int MD_HEADER = 0;
constexpr int MD_BLOCK_QUOTE = 1;
constexpr int MD_CODE_BLOCK = 2;
// ...
constexpr int MD_PLAIN_TEXT = 17;
constexpr int MD_HTML_BREAK = 18;
constexpr int SEG_BREAK = -1;  // 分组边界标记
```

### 6.2 Segment（C++）

```cpp
struct Segment {
    int type;
    int start;
    int end;
};
```

### 6.3 PluginState（C++）

```cpp
enum class PluginState {
    IDLE,       // 空闲，未开始匹配
    TRYING,     // 正在尝试匹配起始模式
    PROCESSING, // 已确认匹配，正在处理内容
    WAITFOR,    // 等待下一个字符以确认/回退
};
```

---

## 7. 插件系统详解

### 7.1 基类接口

[StreamPlugin.h](file:///home/meizu/Documents/my_agent_projects/Operit/app/src/main/cpp/streamnative/plugins/StreamPlugin.h)

```cpp
class StreamPlugin {
public:
    virtual ~StreamPlugin() = default;
    virtual PluginState state() const = 0;
    virtual bool processChar(char16_t c, bool atStartOfLine) = 0;
    virtual bool initPlugin() = 0;
    virtual void reset() = 0;
};
```

| 方法 | 说明 |
|------|------|
| `state()` | 返回当前状态（IDLE / TRYING / PROCESSING / WAITFOR） |
| `processChar(c, atStartOfLine)` | 处理单个字符，返回 `shouldEmit`（该字符是否应被输出） |
| `initPlugin()` | 初始化插件状态 |
| `reset()` | 重置到 IDLE 状态 |

### 7.2 Markdown 插件列表

[StreamMarkdownPlugin.h](file:///home/meizu/Documents/my_agent_projects/Operit/app/src/main/cpp/streamnative/plugins/StreamMarkdownPlugin.h) / [.cpp](file:///home/meizu/Documents/my_agent_projects/Operit/app/src/main/cpp/streamnative/plugins/StreamMarkdownPlugin.cpp)

| 插件类 | 匹配模式 | 块/内联 | includeFences/Delimiters |
|--------|---------|--------|--------------------------|
| `StreamMarkdownHeaderPlugin` | `# ...` / `## ...` | 块级 | includeMarker |
| `StreamMarkdownFencedCodeBlockPlugin` | `` ```...``` `` | 块级 | includeFences |
| `StreamMarkdownBlockQuotePlugin` | `> ...` | 块级 | includeMarker |
| `StreamMarkdownOrderedListPlugin` | `1. ...` | 块级 | includeMarker |
| `StreamMarkdownUnorderedListPlugin` | `- ...` / `* ...` | 块级 | includeMarker |
| `StreamMarkdownHorizontalRulePlugin` | `---` / `***` / `___` | 块级 | includeMarker |
| `StreamMarkdownTablePlugin` | `\|...\|` | 块级 | includeDelimiters |
| `StreamMarkdownBlockLaTeXPlugin` | `$$...$$` | 块级 | includeDelimiters |
| `StreamMarkdownBlockBracketLaTeXPlugin` | `\[...\]` | 块级 | includeDelimiters |
| `StreamMarkdownImagePlugin` | `![alt](url)` | 块级 | includeDelimiters |
| `StreamMarkdownBoldPlugin` | `**...**` | 内联 | includeAsterisks |
| `StreamMarkdownItalicPlugin` | `*...*` / `_..._` | 内联 | includeAsterisks |
| `StreamMarkdownInlineCodePlugin` | `` `...` `` | 内联 | includeTicks |
| `StreamMarkdownLinkPlugin` | `[text](url)` | 内联 | - |
| `StreamMarkdownStrikethroughPlugin` | `~~...~~` | 内联 | includeDelimiters |
| `StreamMarkdownUnderlinePlugin` | `__...__` | 内联 | includeDelimiters |
| `StreamMarkdownInlineLaTeXPlugin` | `$...$` | 内联 | includeDelimiters |
| `StreamMarkdownInlineParenLaTeXPlugin` | `\(...\)` | 内联 | includeDelimiters |

### 7.3 XML 插件

[StreamXmlPlugin.h](file:///home/meizu/Documents/my_agent_projects/Operit/app/src/main/cpp/streamnative/plugins/StreamXmlPlugin.h) / [.cpp](file:///home/meizu/Documents/my_agent_projects/Operit/app/src/main/cpp/streamnative/plugins/StreamXmlPlugin.cpp)

```cpp
class StreamXmlPlugin final : public StreamPlugin {
    // 起始匹配状态机:
    // WAIT_LT → WAIT_FIRST_LETTER → IN_TAG_NAME → IN_ATTRS → (遇到 '>')
    // 确认后进入 PROCESSING，构建结束标签模式 </tagName>，使用 KMP 匹配
};
```

**特殊规则**：
- XML 标签只能在行首、结束标签后或标点符号后开始（避免将 `<` 比较运算符误识别）
- 自闭合标签（如 `<br/>`）被视为纯文本
- Emoji 及其后续字符（ZWJ、变体选择符）会触发 `allowStartAfterPunctuation_`

### 7.4 KMP 模式匹配引擎

[StreamKmpGraph.h](file:///home/meizu/Documents/my_agent_projects/Operit/app/src/main/cpp/streamnative/StreamKmpGraph.h)

```cpp
class KmpMatcher {
public:
    void setPattern(std::u16string p);  // 预处理前缀函数
    void reset();
    bool process(char16_t c);           // 流式处理字符，返回是否匹配完成

private:
    std::u16string pattern_;
    std::vector<int> pi_;  // 前缀函数 (failure function)
    int j_ = 0;            // 当前匹配位置
};
```

XML 插件使用 `KmpMatcher` 来流式匹配结束标签（如 `</div>`）。

---

## 8. Session 状态机

### 8.1 Session 生命周期

```
创建 Session
    │ createBlockSession() / createInlineSession()
    ▼
┌─────────────┐
│   ACTIVE    │ ◄── 可以多次调用 push(chunk)
│  (活跃状态)  │     每次 push 返回当前已解析的 Segment 数组
└─────────────┘
    │ destroy()
    ▼
┌─────────────┐
│  DESTROYED  │ ◄── C++ 内存已释放，不可再用
│  (已销毁)    │
└─────────────┘
```

### 8.2 跨 chunk 状态保持

```kotlin
val session = NativeMarkdownSplitter.createBlockSession()

// Chunk 1: "# Hel"
session.push("# Hel")  // → [SEG_BREAK, 0,0, MD_HEADER, 0,5]
                       // 注意：Header 尚未结束，但已确认匹配

// Chunk 2: "lo\n\nWorld"
session.push("lo\n\nWorld")  // → [MD_HEADER, 0,7, SEG_BREAK, 7,7,
                             //    MD_PLAIN_TEXT, 7,12]
                             // 跨 chunk 保持了 globalOffset_ 和 atStartOfLine_
```

### 8.3 块级 vs 内联 Session

| 维度 | Block Session | Inline Session |
|------|--------------|----------------|
| 插件集合 | Header, CodeBlock, Quote, List, HR, Table, BlockLaTeX, Image, XML | Bold, Italic, InlineCode, Link, Strikethrough, Underline, InlineLaTeX |
| 行首敏感 | 是（大多数块级元素要求 SOL） | 否 |
| 用途 | 第一级解析，将文本分割为块级元素 | 第二级解析，处理块级元素内部的内联样式 |

---

## 9. 构建配置

### 9.1 CMakeLists.txt

[CMakeLists.txt](file:///home/meizu/Documents/my_agent_projects/Operit/app/src/main/cpp/CMakeLists.txt)

```cmake
add_library(
    streamnative
    SHARED
    streamnative/native_xml_splitter.cpp
    streamnative/native_markdown_splitter.cpp
    streamnative/StreamOperators.cpp
    streamnative/plugins/StreamXmlPlugin.cpp
    streamnative/plugins/BaseJsonPlugin.cpp
    streamnative/plugins/StreamJsonPlugin.cpp
    streamnative/plugins/StreamPureJsonPlugin.cpp
    streamnative/plugins/StreamMarkdownPlugin.cpp
    streamnative/StreamBuilders.cpp
    streamnative/HotStream.cpp
    streamnative/StringExtensions.cpp
)

target_include_directories(
    streamnative
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(streamnative ${log-lib})
enable_16kb_page_alignment(streamnative)
```

### 9.2 构建特性

- **16KB 页对齐**：通过 `enable_16kb_page_alignment()` 适配 Android 15+ 的 16KB 页面大小要求
- **Release 优化**：`force_release_flags_for_debug()` 确保 Debug 构建下仍使用 `-O3` 优化原生库
- **最小依赖**：仅链接 Android `log` 库

---

## 10. 使用方法

### 10.1 一次性解析（内联元素）

```kotlin
import com.ai.assistance.operit.util.streamnative.NativeMarkdownSplitter

val text = "Hello **world** and *italic* text"
val nodes = NativeMarkdownSplitter.parseInlineToStableNodes(text)

// 结果:
// nodes[0]: MarkdownNodeStable(PLAIN_TEXT, "Hello ")
// nodes[1]: MarkdownNodeStable(BOLD, "world")
// nodes[2]: MarkdownNodeStable(PLAIN_TEXT, " and ")
// nodes[3]: MarkdownNodeStable(ITALIC, "italic")
// nodes[4]: MarkdownNodeStable(PLAIN_TEXT, " text")
```

### 10.2 流式解析（块级 + 内联）

```kotlin
import com.ai.assistance.operit.util.streamnative.nativeMarkdownSplitByBlock
import com.ai.assistance.operit.util.streamnative.nativeMarkdownSplitByInline

aiResponseStream
    .nativeMarkdownSplitByBlock(
        flushIntervalMs = 100,  // 每 100ms 刷新
        maxDeltaChars = 50      // 或累积 50 个字符
    )
    .collect { blockGroup ->
        when (blockGroup.tag) {
            MarkdownProcessorType.CODE_BLOCK -> {
                renderCodeBlock(blockGroup.stream)
            }
            MarkdownProcessorType.PLAIN_TEXT -> {
                // 对纯文本块进一步做内联解析
                blockGroup.stream
                    .nativeMarkdownSplitByInline()
                    .collect { inlineGroup ->
                        renderInlineElement(inlineGroup)
                    }
            }
            else -> renderBlockElement(blockGroup)
        }
    }
```

### 10.3 XML 分割

```kotlin
import com.ai.assistance.operit.util.streamnative.NativeXmlSplitter

val content = "Hello <b>world</b>!"
val segments = NativeXmlSplitter.splitXmlTag(content)

// 结果:
// segments[0]: ["text", "Hello "]
// segments[1]: ["b", "<b>world</b>"]
// segments[2]: ["text", "!"]
```

### 10.4 手动管理 Session

```kotlin
val session = NativeMarkdownSplitter.createBlockSession()
try {
    val fullContent = StringBuilder()
    for (chunk in chunks) {
        fullContent.append(chunk)
        val segments = session.push(chunk)
        // 解析 segments...
    }
} finally {
    session.destroy()  // 必须释放！
}
```

---

## 11. 性能特性

### 11.1 零拷贝设计

- C++ 通过 `GetStringChars` 直接读取 JVM String 的 UTF-16 数组，无数据复制
- 返回的 `IntArray` 是展平的原始类型数组，无对象装箱

### 11.2 栈分配与零 GC

- C++ 侧所有解析状态（`evaluationBuffer_`、`waitforPending_` 等）使用 `std::vector` 在原生堆/栈上分配
- 解析过程中不产生 JVM 对象，完全避免 GC 压力

### 11.3 Session 复用

- 流式场景下，一个 Session 可处理任意数量的 chunk，避免重复创建解析器对象
- `globalOffset_` 持续累加，Segment 的 `start/end` 始终指向全局内容位置

### 11.4 批处理策略

| 参数组合 | 行为 | 适用场景 |
|---------|------|---------|
| `null, null` | 逐字符实时处理 | 打字机效果，最低延迟 |
| `100ms, null` | 每 100ms 刷新一次 | 平衡实时性与吞吐量 |
| `null, 50` | 累积 50 字符后刷新 | 按数据量批量处理 |
| `100ms, 50` | 两者任一满足即刷新 | 综合策略 |

---

## 12. 文件索引

### Kotlin 层

| 文件 | 路径 |
|------|------|
| NativeMarkdownSplitter.kt | `app/src/main/java/.../util/streamnative/NativeMarkdownSplitter.kt` |
| NativeMarkdownStreamOperators.kt | `app/src/main/java/.../util/streamnative/NativeMarkdownStreamOperators.kt` |
| NativeXmlSplitter.kt | `app/src/main/java/.../util/streamnative/NativeXmlSplitter.kt` |
| MarkdownProcessorType | `app/src/main/java/.../util/markdown/MarkdownProcessor.kt` |

### C++ 层

| 文件 | 路径 |
|------|------|
| native_markdown_splitter.cpp | `app/src/main/cpp/streamnative/native_markdown_splitter.cpp` |
| native_xml_splitter.cpp | `app/src/main/cpp/streamnative/native_xml_splitter.cpp` |
| StreamOperators.h / .cpp | `app/src/main/cpp/streamnative/StreamOperators.h` / `.cpp` |
| StreamGroup.h | `app/src/main/cpp/streamnative/StreamGroup.h` |
| StreamKmpGraph.h | `app/src/main/cpp/streamnative/StreamKmpGraph.h` |
| StreamPlugin.h | `app/src/main/cpp/streamnative/plugins/StreamPlugin.h` |
| StreamMarkdownPlugin.h / .cpp | `app/src/main/cpp/streamnative/plugins/StreamMarkdownPlugin.h` / `.cpp` |
| StreamXmlPlugin.h / .cpp | `app/src/main/cpp/streamnative/plugins/StreamXmlPlugin.h` / `.cpp` |
| StreamJsonPlugin.h / .cpp | `app/src/main/cpp/streamnative/plugins/StreamJsonPlugin.h` / `.cpp` |
| BaseJsonPlugin.h / .cpp | `app/src/main/cpp/streamnative/plugins/BaseJsonPlugin.h` / `.cpp` |
| StreamPureJsonPlugin.h / .cpp | `app/src/main/cpp/streamnative/plugins/StreamPureJsonPlugin.h` / `.cpp` |
| CMakeLists.txt | `app/src/main/cpp/CMakeLists.txt` |
