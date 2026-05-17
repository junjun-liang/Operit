# LLMProvider 模块设计思想与详细使用指南

## 目录

1. [模块概述](#1-模块概述)
2. [核心设计思想](#2-核心设计思想)
3. [架构与代码结构](#3-架构与代码结构)
4. [AIService 接口详解](#4-aiservice-接口详解)
5. [服务工厂与创建流程](#5-服务工厂与创建流程)
6. [Provider 实现分类](#6-provider-实现分类)
7. [API Key 管理](#7-api-key-管理)
8. [限流与并发控制](#8-限流与并发控制)
9. [Tool Call 桥接层](#9-tool-call-桥接层)
10. [插件化 Provider](#10-插件化-provider)
11. [使用示例](#11-使用示例)
12. [最佳实践](#12-最佳实践)

***

## 1. 模块概述

LLMProvider 是 Operit 项目的**AI 服务接入层**，负责统一封装各种大语言模型（LLM）提供商的 API 差异，为上层业务提供一致的调用接口。

### 1.1 支持的提供商

| 类型   | 提供商                                                | 协议格式      |
| ---- | -------------------------------------------------- | --------- |
| 国际主流 | OpenAI, Anthropic(Claude), Google(Gemini), Mistral | 原生 API    |
| 国内主流 | 阿里云(通义千问), DeepSeek, 月之暗面(Kimi), 豆包, 硅基流动          | OpenAI 兼容 |
| 本地部署 | Ollama, LM Studio, llama.cpp, MNN                  | 本地推理      |
| 聚合平台 | OpenRouter, FourRouter, NousPortal, NvidiaAI       | 代理转发      |
| 插件扩展 | ToolPkg JS Provider                                | 动态脚本      |

### 1.2 核心能力

- **统一接口**：所有提供商通过 `AIService` 接口对外暴露一致的能力
- **流式输出**：统一返回 `Stream<String>`，支持实时打字机效果
- **多模态支持**：文本、图片、音频、视频的统一处理
- **Tool Call**：XML 格式与原生 Tool Call API 的双向转换
- **API Key 池**：支持多 Key 轮询和可用性检测
- **限流保护**：滑动窗口限流 + 并发控制

***

## 2. 核心设计思想

### 2.1 策略模式 + 工厂模式

```
┌─────────────────────────────────────────────────────────────┐
│                    统一接口层                                  │
│  ┌─────────────────────────────────────────────────────────┐│
│  │  interface AIService                                    ││
│  │  - sendMessage() → Stream<String>                       ││
│  │  - getModelsList() → Result<List<ModelOption>>          ││
│  │  - testConnection() → Result<String>                    ││
│  │  - calculateInputTokens() → Int                         ││
│  └─────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
                              ▲
              ┌───────────────┼───────────────┐
              │               │               │
    ┌─────────┘       ┌───────┘       ┌───────┘
    │                 │               │
OpenAIProvider  ClaudeProvider  GeminiProvider  ...
    │                 │               │
    └─────────────────┴───────────────┘
              各提供商具体实现
```

### 2.2 装饰器模式增强

```
基础 Provider
    │
    ▼
┌─────────────────┐
│ OpenAIProvider  │  ← 核心实现
└─────────────────┘
    │
    ▼ 包装
┌─────────────────────────┐
│ RateLimitedAIService    │  ← 添加限流能力
│ (delegate + rateLimiter)│
└─────────────────────────┘
    │
    ▼ 包装
┌─────────────────────────┐
│ Retry-enabled Service   │  ← 添加重试能力
│ (exponential backoff)   │
└─────────────────────────┘
```

### 2.3 流式数据统一抽象

无论底层提供商是否支持流式输出，LLMProvider 统一返回 `Stream<String>`：

```kotlin
// 流式输出（OpenAI, Claude 等支持）
provider.sendMessage(..., stream = true)
    .collect { chunk -> println(chunk) }  // 实时接收片段

// 非流式输出也包装为 Stream
provider.sendMessage(..., stream = false)
    .collect { fullText -> println(fullText) }  // 一次性返回完整文本
```

***

## 3. 架构与代码结构

### 3.1 文件组织

```
app/src/main/java/com/ai/assistance/operit/api/chat/llmprovider/
├── AIService.kt                    # 核心接口定义
├── AIServiceFactory.kt             # 服务工厂
├── ApiKeyProvider.kt               # API Key 管理（单 Key/多 Key 轮询）
├── RateLimitedAIService.kt         # 限流装饰器
├── LlmRetryPolicy.kt               # 重试策略
├── SlidingWindowRateLimiter.kt     # 滑动窗口限流器
├── RateLimiterRegistry.kt          # 限流器注册表
├── RequestConcurrencyRegistry.kt   # 并发控制注册表
├── StructuredToolCallBridge.kt     # Tool Call 格式转换桥
├── ToolPkgJsAiProviderService.kt   # JS 插件 Provider
├── EndpointCompleter.kt            # 端点 URL 自动补全
├── MediaLinkBuilder.kt             # 媒体链接构建
├── MediaLinkParser.kt              # 媒体链接解析
├── ModelListFetcher.kt             # 模型列表获取
├── ModelConfigConnectionTester.kt  # 连接测试
├── ApiKeyPoolAvailabilityTester.kt # Key 池可用性测试
├── UnsafeModelSsl.kt               # SSL 证书忽略（开发用）
│
├── OpenAIProvider.kt               # OpenAI 格式 Provider（基类）
├── OpenAIResponsesProvider.kt      # OpenAI Responses API
├── ClaudeProvider.kt               # Anthropic Claude
├── GeminiProvider.kt               # Google Gemini
├── DeepseekProvider.kt             # DeepSeek
├── KimiProvider.kt                 # 月之暗面 Moonshot
├── QwenAIProvider.kt               # 阿里云通义千问
├── DoubaoAIProvider.kt             # 字节豆包
├── MistralProvider.kt              # Mistral AI
├── OpenRouterProvider.kt           # OpenRouter 聚合
├── FourRouterProvider.kt           # FourRouter 聚合
├── NousPortalProvider.kt           # NousPortal
├── NvidiaAIProvider.kt             # Nvidia AI
├── OllamaProvider.kt               # Ollama 本地
├── LlamaProvider.kt                # llama.cpp 本地
├── MNNProvider.kt                  # MNN 本地推理
└── ...
```

### 3.2 核心类图

```
┌─────────────────────────────────────────────────────────────┐
│                      <<interface>>                           │
│                      AIService                               │
│  + sendMessage(...) → Stream<String>                        │
│  + getModelsList(...) → Result<List<ModelOption>>           │
│  + testConnection(...) → Result<String>                     │
│  + calculateInputTokens(...) → Int                          │
│  + cancelStreaming()                                         │
│  + release()                                                 │
└─────────────────────────────────────────────────────────────┘
        ▲                           ▲
        │ implements                │ delegates
        │                           │
┌───────┴───────┐          ┌────────┴────────┐
│ OpenAIProvider│          │ RateLimitedAIService│
│ ClaudeProvider│          │ (装饰器模式)        │
│ GeminiProvider│          └───────────────────┘
│ MNNProvider   │
│ LlamaProvider │
│ ToolPkgJs...  │
│ ...           │
└───────────────┘
```

***

## 4. AIService 接口详解

### 4.1 核心方法

```kotlin
interface AIService {
    /** Token 计数器 */
    val inputTokenCount: Int           // 输入 token 数
    val cachedInputTokenCount: Int     // 缓存命中 token 数
    val outputTokenCount: Int          // 输出 token 数
    val providerModel: String          // 提供商:模型标识

    /** 重置计数器 */
    fun resetTokenCounts()

    /** 取消当前流式传输 */
    fun cancelStreaming()

    /**
     * 发送消息（核心方法）
     * @param chatHistory 完整聊天历史（已包含本次输入）
     * @param modelParameters 模型参数（温度、top_p 等）
     * @param enableThinking 是否启用思考模式
     * @param stream 是否流式输出
     * @param availableTools 可用工具列表
     * @param preserveThinkInHistory 是否保留历史中的思考过程
     * @param onTokensUpdated Token 更新回调
     * @param onNonFatalError 非致命错误回调
     * @param enableRetry 是否启用重试
     * @return 流式响应内容
     */
    suspend fun sendMessage(
        context: Context,
        chatHistory: List<PromptTurn> = emptyList(),
        modelParameters: List<ModelParameter<*>> = emptyList(),
        enableThinking: Boolean = false,
        stream: Boolean = true,
        availableTools: List<ToolPrompt>? = null,
        preserveThinkInHistory: Boolean = false,
        onTokensUpdated: suspend (input: Int, cachedInput: Int, output: Int) -> Unit = { _, _, _ -> },
        onNonFatalError: suspend (error: String) -> Unit = {},
        enableRetry: Boolean = true
    ): Stream<String>

    /** 获取模型列表 */
    suspend fun getModelsList(context: Context): Result<List<ModelOption>>

    /** 测试连接 */
    suspend fun testConnection(context: Context): Result<String>

    /** 计算输入 Token 数 */
    suspend fun calculateInputTokens(
        chatHistory: List<PromptTurn>,
        availableTools: List<ToolPrompt>? = null
    ): Int

    /** 释放资源 */
    fun release() {}
}
```

### 4.2 PromptTurn 数据结构

```kotlin
// 聊天历史中的每一轮对话
data class PromptTurn(
    val kind: PromptTurnKind,    // 角色类型
    val content: String,          // 内容
    val toolName: String? = null, // 工具名称（Tool Call 用）
    val metadata: Map<String, Any?> = emptyMap() // 元数据
)

enum class PromptTurnKind {
    SYSTEM,       // 系统提示词
    USER,         // 用户输入
    ASSISTANT,    // AI 回复
    TOOL_CALL,    // 工具调用
    TOOL_RESULT,  // 工具执行结果
    SUMMARY       // 历史摘要
}
```

***

## 5. 服务工厂与创建流程

### 5.1 AIServiceFactory 创建流程

```kotlin
object AIServiceFactory {
    fun createService(
        config: ModelConfigData,           // 模型配置
        modelConfigManager: ModelConfigManager,
        context: Context
    ): AIService {
        // 1. 检查是否是插件 Provider
        ToolPkgAiProviderRegistry.get(providerTypeId)?.let { provider ->
            return ToolPkgJsAiProviderService(config, provider)
        }

        // 2. 创建共享 HTTP 客户端
        val httpClient = SharedHttpClient.instance

        // 3. 解析自定义请求头
        val customHeaders = parseCustomHeaders(config.customHeaders)

        // 4. 创建 API Key 提供器（单 Key / 多 Key 轮询）
        val apiKeyProvider = if (config.useMultipleApiKeys) {
            MultiApiKeyProvider(config.id, modelConfigManager)
        } else {
            SingleApiKeyProvider(config.apiKey)
        }

        // 5. 根据提供商类型创建对应 Provider
        return when (providerType) {
            ApiProviderType.OPENAI -> OpenAIProvider(...)
            ApiProviderType.ANTHROPIC -> ClaudeProvider(...)
            ApiProviderType.GOOGLE -> GeminiProvider(...)
            ApiProviderType.MNN -> MNNProvider(...)
            ApiProviderType.LLAMA_CPP -> LlamaProvider(...)
            // ... 其他提供商
        }
    }
}
```

### 5.2 共享 HTTP 客户端配置

```kotlin
private object SharedHttpClient {
    val instance: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(1000, TimeUnit.SECONDS)      // 长连接支持流式
            .writeTimeout(1000, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .apply { UnsafeModelSsl.apply(this) }     // 开发环境忽略证书
            .build()
    }
}
```

***

## 6. Provider 实现分类

### 6.1 OpenAI 兼容格式（最广泛）

大多数国内和国际提供商都兼容 OpenAI API 格式：

```kotlin
// 标准 OpenAI
OpenAIProvider(
    apiEndpoint = "https://api.openai.com/v1",
    apiKeyProvider = apiKeyProvider,
    modelName = "gpt-4o",
    client = httpClient,
    supportsVision = true,
    enableToolCall = true
)

// 兼容 OpenAI 格式的提供商：
// - DeepSeek, Kimi, Mistral, Qwen, Doubao
// - 硅基流动, OpenRouter, LM Studio, Ollama
// - 百度, 讯飞, 智谱, 百川等
```

### 6.2 原生格式 Provider

| Provider                  | 特点                   | 特殊处理                |
| ------------------------- | -------------------- | ------------------- |
| `ClaudeProvider`          | Anthropic 原生格式       | 特殊的 system 字段处理     |
| `GeminiProvider`          | Google 原生格式          | 支持 Google Search 扩展 |
| `OpenAIResponsesProvider` | OpenAI Responses API | 新的对话状态管理            |

### 6.3 本地推理 Provider

| Provider         | 引擎              | 适用场景    |
| ---------------- | --------------- | ------- |
| `MNNProvider`    | 阿里巴巴 MNN        | 移动端本地推理 |
| `LlamaProvider`  | llama.cpp       | 跨平台本地推理 |
| `OllamaProvider` | Ollama HTTP API | 本地服务化部署 |

**MNNProvider 示例**：

```kotlin
MNNProvider(
    context = context,
    modelName = "Qwen2-1.5B-Instruct-MNN",
    forwardType = MNN_FORWARD_CPU,  // 或 MNN_FORWARD_OPENCL
    threadCount = 4,
    supportsVision = true
)
```

***

## 7. API Key 管理

### 7.1 单 Key 模式

```kotlin
class SingleApiKeyProvider(private val apiKey: String) : ApiKeyProvider {
    override suspend fun getApiKey(): String = apiKey
}
```

### 7.2 多 Key 轮询模式

```kotlin
class MultiApiKeyProvider(
    private val configId: String,
    private val modelConfigManager: ModelConfigManager
) : ApiKeyProvider {

    override suspend fun getApiKey(): String = mutex.withLock {
        val config = modelConfigManager.getModelConfig(configId)
        val enabledKeys = config.apiKeyPool.filter { it.isEnabled }

        // 优先使用已标记为 AVAILABLE 的 Key
        val candidateKeys = if (enabledKeys.any { it.availabilityStatus != UNTESTED }) {
            enabledKeys.filter { it.availabilityStatus == AVAILABLE }
        } else {
            enabledKeys
        }

        // 轮询选择 Key
        val startIndex = config.currentKeyIndex % candidateKeys.size
        val selectedKey = candidateKeys[startIndex]

        // 更新索引（持久化）
        modelConfigManager.updateConfigKeyIndex(configId, (startIndex + 1) % candidateKeys.size)

        selectedKey.key
    }
}
```

### 7.3 Key 可用性检测流程

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   UNTESTED  │────▶│  TESTING    │────▶│  AVAILABLE  │
│   (未测试)   │     │  (测试中)    │     │  (可用)      │
└─────────────┘     └─────────────┘     └─────────────┘
       │                                    │
       │                                    │
       └────────────────────────────────────┘
                    测试失败
              ┌─────────────┐
              │   FAILED    │
              │  (不可用)    │
              └─────────────┘
```

***

## 8. 限流与并发控制

### 8.1 滑动窗口限流器

```kotlin
class SlidingWindowRateLimiter(
    val maxRequestsPerMinute: Int,
    private val windowMs: Long = 60_000L
) {
    private val timestamps = ArrayDeque<Long>()

    suspend fun acquire() {
        while (true) {
            val retryAfterMs = tryAcquire()
            if (retryAfterMs <= 0L) return
            delay(retryAfterMs)  // 等待窗口释放
        }
    }

    suspend fun tryAcquire(nowMs: Long = System.currentTimeMillis()): Long {
        return mutex.withLock {
            // 清理过期时间戳
            while (timestamps.isNotEmpty() && nowMs - timestamps.first() >= windowMs) {
                timestamps.removeFirst()
            }

            if (timestamps.size >= maxRequestsPerMinute) {
                // 计算需要等待的时间
                val oldest = timestamps.first()
                (windowMs - (nowMs - oldest)).coerceAtLeast(1L)
            } else {
                timestamps.addLast(nowMs)
                0L  // 无需等待
            }
        }
    }
}
```

### 8.2 并发控制

```kotlin
object RequestConcurrencyRegistry {
    fun getOrCreate(key: String, maxConcurrentRequests: Int): Semaphore {
        return semaphores.compute(key) { _, existing ->
            if (existing == null || existing.maxConcurrentRequests != maxConcurrentRequests) {
                Entry(
                    maxConcurrentRequests = maxConcurrentRequests,
                    semaphore = Semaphore(maxConcurrentRequests)
                )
            } else {
                existing
            }
        }!!.semaphore
    }
}
```

#### Semaphore 详解

`Semaphore` 来自 `kotlinx.coroutines.sync` 包，是 Kotlin 协程专用的**并发控制工具**，用于限制同时执行的协程数量。

**核心作用**：
- 防止 API 速率限制触发（RPM 限制）
- 避免资源耗尽（内存、连接池）
- 保证服务质量，避免响应时间恶化

**工作原理**：

```kotlin
// 创建允许 maxConcurrentRequests 个并发许可的信号量
semaphore = Semaphore(maxConcurrentRequests)

// 请求前获取许可（无可用许可时协程挂起等待）
semaphore.acquire()

try {
    // 执行 LLM 请求
    return delegate.sendMessage(...)
} finally {
    // 确保释放许可
    semaphore.release()
}
```

**与 Java Semaphore 的区别**：

| 特性 | Kotlin `kotlinx.coroutines.sync.Semaphore` | Java `java.util.concurrent.Semaphore` |
|------|-------------------------------------------|---------------------------------------|
| `acquire()` 性质 | **挂起函数**（suspend），协程让出调度器 | 阻塞操作系统线程 |
| 线程影响 | 不阻塞线程，线程可执行其他协程 | 阻塞线程，降低线程池利用率 |
| 适用场景 | 高并发协程环境 | 传统线程并发 |

**使用示例**：

```kotlin
class RateLimitedAIService(
    private val delegate: AIService,
    maxConcurrentRequests: Int
) : AIService {
    private val semaphore = RequestConcurrencyRegistry.getOrCreate(
        key = delegate.providerModel,
        maxConcurrentRequests = maxConcurrentRequests
    )

    override suspend fun sendMessage(...): Stream<String> {
        semaphore.acquire()
        try {
            return delegate.sendMessage(...)
        } finally {
            semaphore.release()
        }
    }
}
```

**关键特性**：
- **协程友好**：挂起时不阻塞线程，线程可调度其他协程
- **按服务隔离**：不同 LLM 服务使用独立信号量，互不影响
- **动态调整**：`RequestConcurrencyRegistry` 支持根据配置变化重建信号量
- **公平性**：默认非公平模式，可通过构造函数参数配置公平性

### 8.3 装饰器组装

```kotlin
// 工厂创建基础 Provider
val baseProvider = AIServiceFactory.createService(config, modelConfigManager, context)

// 包装限流器
val rateLimitedProvider = RateLimitedAIService(
    delegate = baseProvider,
    rateLimiter = RateLimiterRegistry.getOrCreate(
        key = config.id,
        maxRequestsPerMinute = config.requestLimitPerMinute
    ),
    concurrencySemaphore = RequestConcurrencyRegistry.getOrCreate(
        key = config.id,
        maxConcurrentRequests = config.maxConcurrentRequests
    )
)
```

***

## 9. Tool Call 桥接层

### 9.1 设计目标

Operit 内部使用 XML 格式表示工具调用，但许多模型原生支持 OpenAI 的 Tool Call API。`StructuredToolCallBridge` 负责**双向格式转换**：

```
内部 XML 格式                      OpenAI Tool Call 格式
─────────────                     ───────────────────
<tool name="search">              {
  <param name="query">AI</param>    "tool_calls": [{
</tool>                               "id": "call_xxx",
                                      "type": "function",
                                      "function": {
                                        "name": "search",
                                        "arguments": "{\"query\":\"AI\"}"
                                      }
                                    }]
                                  }
```

### 9.2 历史记录转换

```kotlin
object StructuredToolCallBridge {
    // 将内部 PromptTurn 历史转换为 OpenAI messages 格式
    fun buildStructuredMessages(
        history: List<PromptTurn>,
        preserveThinkInHistory: Boolean
    ): JSONArray

    // 构建 tools 定义
    fun buildToolsArray(toolPrompts: List<ToolPrompt>?): JSONArray

    // 将 API 返回的 tool_calls 转换回 XML
    fun convertToolCallPayloadToXml(content: String): String
}
```

### 9.3 转换流程

```
发送请求:
  PromptTurn[] ──▶ compileHistoryForProvider() ──▶ 合并相邻消息
       │                                              │
       │         ┌────────────────────────────────────┘
       │         ▼
       │    buildStructuredMessages()
       │    - SYSTEM → {role: "system"}
       │    - USER → {role: "user"}
       │    - ASSISTANT + tool_calls → {role: "assistant", tool_calls: [...]}
       │    - TOOL_RESULT → {role: "tool", tool_call_id: "..."}
       │
       └──────────────────────────────────────────────▶ OpenAI API

接收响应:
  API 返回 tool_calls ──▶ normalizeToolCalls() ──▶ convertToolCallsToXml()
                                                              │
                                                              ▼
                                                    <tool name="xxx">
                                                      <param name="yyy">zzz</param>
                                                    </tool>
```

***

## 10. 插件化 Provider

### 10.1 ToolPkg JS Provider 架构

ToolPkg 是 Operit 的插件系统，允许通过 JavaScript 动态扩展 AI Provider：

```
┌─────────────────────────────────────────────────────────────┐
│                  ToolPkg JS Provider 架构                     │
│                                                             │
│  Kotlin 层                                                  │
│  ┌─────────────────────────────────────────────────────────┐│
│  │ ToolPkgJsAiProviderService                               ││
│  │ - 实现 AIService 接口                                    ││
│  │ - 通过 toolPkgPackageManager 调用 JS 函数                ││
│  └─────────────────────────────────────────────────────────┘│
│                          │                                  │
│                          ▼ JNI/IPC                          │
│  JS 运行时层                                               │
│  ┌─────────────────────────────────────────────────────────┐│
│  │ JS Provider 脚本                                         ││
│  │ - sendMessageFunction                                    ││
│  │ - testConnectionFunction                                 ││
│  │ - listModelsFunction                                     ││
│  │ - calculateInputTokensFunction                           ││
│  └─────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
```

### 10.2 JS Provider 接口约定

```javascript
// JS Provider 需要实现的函数
function sendMessage(payload) {
    // payload 包含: chatHistory, modelParameters, availableTools, config 等
    // 返回: { chunk: "...", usage: {input: 10, output: 20} }
    // 或流式返回多个 intermediate result
}

function testConnection(payload) {
    // 返回: { success: true, message: "Connected" }
    // 或: { success: false, error: "API Key invalid" }
}

function listModels(payload) {
    // 返回: [{ id: "model-id", name: "Model Name" }]
}

function calculateInputTokens(payload) {
    // 返回: { tokens: 100 }
}
```

***

## 11. 使用示例

### 11.1 基础对话

```kotlin
class ChatViewModel(private val context: Context) : ViewModel() {

    private lateinit var aiService: AIService

    fun initializeProvider(config: ModelConfigData) {
        aiService = AIServiceFactory.createService(
            config = config,
            modelConfigManager = modelConfigManager,
            context = context
        )
    }

    fun sendUserMessage(message: String) {
        viewModelScope.launch {
            val chatHistory = listOf(
                PromptTurn(kind = PromptTurnKind.USER, content = message)
            )

            aiService.sendMessage(
                context = context,
                chatHistory = chatHistory,
                stream = true,
                onTokensUpdated = { input, cached, output ->
                    _tokenCounts.value = Triple(input, cached, output)
                },
                onNonFatalError = { error ->
                    _errorEvents.emit(error)
                }
            ).collect { chunk ->
                _responseText.value += chunk
            }
        }
    }
}
```

### 11.2 多轮对话

```kotlin
suspend fun multiTurnChat() {
    val history = mutableListOf<PromptTurn>()

    // 系统提示词
    history.add(PromptTurn(
        kind = PromptTurnKind.SYSTEM,
        content = "You are a helpful assistant."
    ))

    // 第一轮
    history.add(PromptTurn(kind = PromptTurnKind.USER, content = "Hello!"))
    val response1 = collectResponse(history)
    history.add(PromptTurn(kind = PromptTurnKind.ASSISTANT, content = response1))

    // 第二轮
    history.add(PromptTurn(kind = PromptTurnKind.USER, content = "Tell me a joke"))
    val response2 = collectResponse(history)
    history.add(PromptTurn(kind = PromptTurnKind.ASSISTANT, content = response2))
}

private suspend fun collectResponse(history: List<PromptTurn>): String {
    val builder = StringBuilder()
    aiService.sendMessage(
        context = context,
        chatHistory = history
    ).collect { chunk ->
        builder.append(chunk)
    }
    return builder.toString()
}
```

### 11.3 Tool Call 使用

```kotlin
suspend fun toolCallExample() {
    val tools = listOf(
        ToolPrompt(
            name = "search",
            description = "Search the web",
            parametersStructured = listOf(
                ToolParameterSchema(
                    name = "query",
                    type = "string",
                    description = "Search query",
                    required = true
                )
            )
        )
    )

    aiService.sendMessage(
        context = context,
        chatHistory = history,
        availableTools = tools,
        enableRetry = true
    ).collect { chunk ->
        // 检查是否包含工具调用 XML
        if (chunk.contains("<tool")) {
            val toolCall = parseToolCall(chunk)
            executeTool(toolCall)
        } else {
            displayText(chunk)
        }
    }
}
```

### 11.4 模型列表获取

```kotlin
suspend fun fetchModels() {
    when (val result = aiService.getModelsList(context)) {
        is Result.Success -> {
            val models = result.getOrThrow()
            models.forEach { model ->
                println("${model.id}: ${model.name}")
            }
        }
        is Result.Failure -> {
            val error = result.exceptionOrNull()
            println("Failed to fetch models: ${error?.message}")
        }
    }
}
```

### 11.5 连接测试

```kotlin
suspend fun testProviderConnection() {
    when (val result = aiService.testConnection(context)) {
        is Result.Success -> {
            val message = result.getOrThrow()
            showToast("Connection successful: $message")
        }
        is Result.Failure -> {
            val error = result.exceptionOrNull()
            showToast("Connection failed: ${error?.message}")
        }
    }
}
```

***

## 12. 最佳实践

### 12.1 Provider 生命周期管理

```kotlin
class ChatViewModel : ViewModel() {
    private var aiService: AIService? = null

    fun switchProvider(config: ModelConfigData) {
        // 1. 释放旧 Provider
        aiService?.release()

        // 2. 创建新 Provider
        aiService = AIServiceFactory.createService(config, modelConfigManager, context)
    }

    override fun onCleared() {
        super.onCleared()
        aiService?.release()
    }
}
```

### 12.2 错误处理策略

```kotlin
aiService.sendMessage(...)
    .catch { error ->
        when (error) {
            is UserCancellationException -> {
                // 用户主动取消，无需处理
            }
            is SocketTimeoutException -> {
                _errorEvents.emit("连接超时，请检查网络")
            }
            is UnknownHostException -> {
                _errorEvents.emit("无法连接到服务器")
            }
            else -> {
                _errorEvents.emit("请求失败: ${error.message}")
            }
        }
    }
    .collect { chunk ->
        _responseText.value += chunk
    }
```

### 12.3 Token 使用监控

```kotlin
aiService.sendMessage(
    context = context,
    chatHistory = history,
    onTokensUpdated = { input, cachedInput, output ->
        val total = input + cachedInput + output
        val cost = calculateCost(input, output)

        _tokenUsage.value = TokenUsageInfo(
            inputTokens = input,
            cachedTokens = cachedInput,
            outputTokens = output,
            totalTokens = total,
            estimatedCost = cost
        )
    }
).collect { chunk ->
    // 处理响应
}
```

### 12.4 重试配置

```kotlin
// 指数退避重试策略
object LlmRetryPolicy {
    const val MAX_RETRY_ATTEMPTS = 5
    private const val RETRY_BASE_DELAY_MS = 1000L

    fun nextDelayMs(retryAttempt: Int): Long {
        val normalizedAttempt = retryAttempt.coerceAtLeast(1)
        return RETRY_BASE_DELAY_MS * (1L shl (normalizedAttempt - 1))
        // 结果: 1s, 2s, 4s, 8s, 16s
    }
}
```

### 12.5 多模态输入

```kotlin
// 图片 + 文本输入
val history = listOf(
    PromptTurn(
        kind = PromptTurnKind.USER,
        content = "Describe this image",
        metadata = mapOf(
            "images" to listOf("file:///path/to/image.jpg")
        )
    )
)

// 配置支持视觉的 Provider
val config = ModelConfigData(
    apiProviderTypeId = "OPENAI",
    modelName = "gpt-4o",
    enableDirectImageProcessing = true
)
```

***

## 总结

LLMProvider 模块通过以下设计提供了强大的 AI 服务接入能力：

1. **统一接口**：`AIService` 接口屏蔽所有提供商差异
2. **工厂模式**：`AIServiceFactory` 根据配置自动创建对应 Provider
3. **装饰器增强**：限流、并发控制通过装饰器透明叠加
4. **流式抽象**：统一返回 `Stream<String>`，支持实时输出
5. **Tool Call 桥接**：XML 与原生 API 格式的双向自动转换
6. **插件扩展**：ToolPkg JS Provider 支持动态扩展新提供商
7. **Key 池管理**：支持多 Key 轮询和可用性检测
8. **本地推理**：MNN/llama.cpp 支持端侧部署

该模块在 Operit 项目中主要用于：

- AI 聊天功能的后端服务调用
- 多模型管理和切换
- Tool Call 工具调用链
- 本地模型推理（离线场景）
- 插件化扩展第三方 Provider

