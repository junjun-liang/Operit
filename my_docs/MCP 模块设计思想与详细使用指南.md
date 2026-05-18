# MCP 模块设计思想与详细使用指南

## 一、模块概述

`mcp` 模块是 Operit AI 的 **MCP (Model Context Protocol) 服务器集成层**，负责将外部 MCP 服务器的能力无缝接入 Operit 的工具生态。该模块通过桥接客户端与 MCP 服务器通信，将 MCP 工具转换为 Operit 内部的标准 `ToolPackage` 格式，使 AI 能够像使用内置工具一样使用 MCP 服务器提供的工具。

### 1.1 核心定位

- **MCP 服务器连接器**：通过 `MCPBridgeClient` 与外部 MCP 服务器建立连接
- **工具适配器**：将 MCP 工具定义转换为 Operit 标准 `PackageTool` 格式
- **工具执行器**：处理 MCP 工具的调用、参数转换和结果解析
- **连接管理器**：缓存和管理 MCP 客户端连接，支持自动重连
- **结果解析器**：智能解析 MCP 返回的 content 数组（文本、图片、资源）

### 1.2 模块结构

```
mcp/
├── MCPJson.kt               # MCP JSON 序列化配置
├── MCPServerConfig.kt       # MCP 服务器配置数据类
├── MCPTool.kt               # MCP 工具定义数据类
├── MCPToolParameter.kt      # MCP 工具参数定义 + 智能类型转换
├── MCPPackage.kt            # MCP 包：连接服务器并转换为 ToolPackage
└── MCPToolExecutor.kt       # MCP 工具执行器 + MCP 管理器
```

### 1.3 架构关系

```
Operit AI
    │
    ├── MCPManager (单例)
    │       ├── registerServer() → 注册服务器配置
    │       ├── getOrCreateClient() → 获取/创建桥接客户端
    │       ├── clientCache: ConcurrentHashMap<String, MCPBridgeClient>
    │       └── serverConfigCache: ConcurrentHashMap<String, MCPServerConfig>
    │
    ├── MCPPackage
    │       ├── fromServer() → 连接 MCP 服务器获取工具列表
    │       ├── toToolPackage() → 转换为标准 ToolPackage
    │       └── LoadResult (成功/失败结果)
    │
    └── MCPToolExecutor (ToolExecutor 实现)
            ├── invoke() → 执行 MCP 工具调用
            ├── validateParameters() → 参数验证
            ├── extractContentFromResult() → 结果解析
            └── convertParameterTypes() → 参数类型转换
```

---

## 二、核心设计思想

### 2.1 桥接模式集成

MCP 模块采用**桥接模式**，通过 `MCPBridgeClient` 作为底层通信桥梁，将 MCP 协议与 Operit 内部工具系统解耦：

```
┌─────────────────────────────────────────────────────────────┐
│                      Operit AI                               │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │ PackageTool │  │  ToolPackage│  │   ToolExecutor      │  │
│  │   (标准)     │  │   (标准)     │  │    (标准接口)        │  │
│  └─────────────┘  └─────────────┘  └─────────────────────┘  │
│           ▲                ▲                    ▲            │
│           │                │                    │            │
│  ┌────────┴────────────────┴────────────────────┘            │
│  │              MCPPackage / MCPToolExecutor                 │
│  │         (适配层：MCP 格式 ↔ Operit 标准格式)               │
│  └──────────────────────────────────────────────────────────┘
│                              │
│                              ▼
│  ┌─────────────────────────────────────────────────────────┐
│  │              MCPBridgeClient (桥接客户端)                │
│  │         connect() / getTools() / callToolSync()          │
│  └─────────────────────────────────────────────────────────┘
│                              │
│                              ▼
│  ┌─────────────────────────────────────────────────────────┐
│  │              External MCP Server (外部服务器)            │
│  │         SSE / HTTP Endpoint (Model Context Protocol)     │
│  └─────────────────────────────────────────────────────────┘
└─────────────────────────────────────────────────────────────┘
```

**设计优势**：
- **协议无关**：上层代码不感知 MCP 协议细节
- **无缝集成**：MCP 工具与内置工具使用相同的调用接口
- **易于扩展**：新增 MCP 服务器只需注册配置即可

### 2.2 客户端连接缓存

`MCPManager` 使用**并发哈希映射**缓存客户端连接，避免重复创建：

```kotlin
class MCPManager(private val context: Context) {
    private val clientCache = ConcurrentHashMap<String, MCPBridgeClient>()
    private val serverConfigCache = ConcurrentHashMap<String, MCPServerConfig>()
    private val connectionFailureReasons = ConcurrentHashMap<String, String>()

    fun getOrCreateClient(serverName: String): MCPBridgeClient? {
        // 1. 检查缓存
        val cachedClient = clientCache[serverName]
        if (cachedClient != null && cachedClient.isConnected()) {
            return cachedClient  // 复用现有连接
        }

        // 2. 尝试重连
        if (cachedClient != null) {
            val reconnected = runBlocking { cachedClient.connect() }
            if (reconnected) return cachedClient
            clientCache.remove(serverName)
        }

        // 3. 创建新连接
        val client = MCPBridgeClient(context, serverName)
        if (runBlocking { client.connect() }) {
            clientCache[serverName] = client
            return client
        }
        return null
    }
}
```

**设计特点**：
- **连接复用**：同一会话期间保持连接活跃
- **自动重连**：缓存客户端断开后尝试重新连接
- **故障隔离**：连接失败记录原因，不影响其他服务器
- **线程安全**：使用 `ConcurrentHashMap` 保证并发安全

### 2.3 智能参数类型转换

`MCPToolParameter` 提供**智能类型转换**，自动将字符串参数转换为 MCP 期望的类型：

```kotlin
// 实例方法：基于已知类型转换
fun convertParameterValue(value: Any): Any {
    return when (type.lowercase()) {
        "number" -> value.toDouble() / value.toLong()
        "boolean" -> value.lowercase() == "true"
        "integer" -> value.toInt()
        "array" -> parseArray(value)
        "object" -> parseObject(value)
        else -> value
    }
}

// 伴生对象方法：智能猜测类型
fun smartConvert(value: Any, typeName: String?): Any {
    return when (typeName?.lowercase()) {
        // 已知类型按类型转换
        "number" / "boolean" / "integer" / "array" / "object" -> ...
        // 未知类型智能猜测
        else -> {
            when {
                value looks like JSON object -> parseObject()
                value looks like JSON array -> parseArray()
                value looks like number -> toNumber()
                value looks like boolean -> toBoolean()
                else -> value
            }
        }
    }
}
```

**转换支持**：
- 基本类型：`string` → `number` / `boolean` / `integer` / `float` / `double`
- 复合类型：`string` → `array` / `object`（JSON 解析）
- 智能猜测：未指定类型时自动识别 JSON 格式、数字、布尔值
- 递归处理：数组和对象内部元素递归转换

### 2.4 结果内容智能提取

`MCPToolExecutor` 智能解析 MCP 返回的 `content` 数组，支持多种内容类型：

```kotlin
private fun extractContentFromResult(resultData: JSONObject?): String {
    val contentArray = resultData.optJSONArray("content")

    return contentArray.map { contentItem ->
        when (contentItem.type) {
            "text" -> {
                // 文本内容：如果是 JSON 字符串则格式化
                val text = contentItem.optString("text")
                if (isJsonString(text)) formatJson(text) else text
            }
            "image" -> {
                // 图片内容：Base64 解码存入 ImagePoolManager
                val base64Data = contentItem.optString("data")
                val imageId = ImagePoolManager.addImageFromBase64(base64Data, mimeType)
                "<link type=\"image\" id=\"$imageId\"></link>"
            }
            "resource" -> {
                // 资源内容：提取文本或图片
                val resource = contentItem.optJSONObject("resource")
                if (isImageResource(resource)) {
                    // 图片资源处理
                } else {
                    resource.optString("text")
                }
            }
        }
    }.joinToString("\n")
}
```

**内容类型支持**：

| 类型 | 处理方式 | 输出格式 |
|------|---------|---------|
| `text` | 直接提取，JSON 格式化 | 纯文本 |
| `image` | Base64 → ImagePoolManager | `<link type="image" id="xxx">` |
| `resource` | 提取 text/blob，图片转存 | 文本或图片链接 |

### 2.5 统一工具命名

MCP 工具采用 `serverName:toolName` 的命名格式，与包工具命名一致：

```kotlin
// 工具名称格式
val toolName = "filesystem:read_file"  // filesystem 服务器 read_file 工具
val toolName = "github:create_issue"   // github 服务器 create_issue 工具

// 名称解析
val parts = toolName.split(":")
val serverName = parts[0]      // "filesystem"
val actualToolName = parts[1]  // "read_file"
```

---

## 三、详细使用方法

### 3.1 注册 MCP 服务器

```kotlin
import com.ai.assistance.operit.core.tools.mcp.MCPManager
import com.ai.assistance.operit.core.tools.mcp.MCPServerConfig

val mcpManager = MCPManager.getInstance(context)

// 方式 1：使用完整配置注册
val serverConfig = MCPServerConfig(
    name = "filesystem",
    endpoint = "http://localhost:3000/sse",
    description = "文件系统操作服务器",
    capabilities = listOf("tools", "resources"),
    extraData = mapOf("version" to "1.0.0")
)
mcpManager.registerServer("filesystem", serverConfig)

// 方式 2：使用简化接口注册
mcpManager.registerServer(
    serverName = "github",
    endpoint = "http://localhost:3001/sse",
    description = "GitHub 操作服务器"
)
```

### 3.2 获取 MCP 包

```kotlin
import com.ai.assistance.operit.core.tools.mcp.MCPPackage

// 从服务器加载 MCP 包
val loadResult = MCPPackage.loadFromServer(context, serverConfig)

when {
    loadResult.mcpPackage != null -> {
        val mcpPackage = loadResult.mcpPackage
        println("服务器: ${mcpPackage.serverConfig.name}")
        println("工具数量: ${mcpPackage.mcpTools.size}")
        mcpPackage.mcpTools.forEach { tool ->
            println("  - ${tool.name}: ${tool.description}")
        }
    }
    loadResult.errorMessage != null -> {
        println("加载失败: ${loadResult.errorMessage}")
    }
}

// 简写方式
val mcpPackage = MCPPackage.fromServer(context, serverConfig)
```

### 3.3 转换为标准工具包

```kotlin
// 将 MCP 包转换为 Operit 标准 ToolPackage
val toolPackage = mcpPackage.toToolPackage()

println("包名: ${toolPackage.name}")
println("描述: ${toolPackage.description}")
println("分类: ${toolPackage.category}")
toolPackage.tools.forEach { tool ->
    println("工具: ${tool.name}")
    println("参数: ${tool.parameters.joinToString { it.name }}")
}
```

### 3.4 执行 MCP 工具

```kotlin
import com.ai.assistance.operit.core.tools.mcp.MCPToolExecutor
import com.ai.assistance.operit.data.model.AITool

// 创建执行器
val executor = MCPToolExecutor(context, mcpManager)

// 构建 AI 工具请求
val aiTool = AITool(
    name = "filesystem:read_file",
    parameters = listOf(
        AITool.Parameter("file_path", "/sdcard/test.txt")
    )
)

// 执行工具
val result = executor.invoke(aiTool)

if (result.success) {
    println("结果: ${result.result}")
} else {
    println("错误: ${result.error}")
}
```

### 3.5 参数类型转换

```kotlin
import com.ai.assistance.operit.core.tools.mcp.MCPToolParameter

// 智能转换（无需实例）
val converted1 = MCPToolParameter.smartConvert("42", "number")        // Long: 42
val converted2 = MCPToolParameter.smartConvert("3.14", "number")      // Double: 3.14
val converted3 = MCPToolParameter.smartConvert("true", "boolean")     // Boolean: true
val converted4 = MCPToolParameter.smartConvert("[1,2,3]", "array")    // List: [1, 2, 3]
val converted5 = MCPToolParameter.smartConvert("""{"a":1}""", "object") // Map: {a=1}

// 未指定类型时智能猜测
val guessed1 = MCPToolParameter.smartConvert("123", null)             // Long: 123
val guessed2 = MCPToolParameter.smartConvert("false", null)           // Boolean: false
val guessed3 = MCPToolParameter.smartConvert("[a,b,c]", null)         // List: ["a", "b", "c"]
```

### 3.6 管理服务器连接

```kotlin
// 检查服务器是否已注册
val isRegistered = mcpManager.isServerRegistered("filesystem")

// 获取所有已注册服务器
val servers = mcpManager.getRegisteredServers()
servers.forEach { (name, config) ->
    println("$name -> ${config.endpoint}")
}

// 获取连接失败原因
val failureReason = mcpManager.getLastConnectionFailureReason("filesystem")

// 注销服务器（会断开连接）
mcpManager.unregisterServer("github")

// 关闭所有连接
mcpManager.shutdown()
```

### 3.7 验证工具参数

```kotlin
// 验证 MCP 工具名称格式
val validation = executor.validateParameters(aiTool)
if (!validation.valid) {
    println("参数无效: ${validation.errorMessage}")
}
```

---

## 四、MCP 结果解析

### 4.1 结果结构

MCP 工具返回的标准结果结构：

```json
{
  "success": true,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "文件内容..."
      },
      {
        "type": "image",
        "mimeType": "image/png",
        "data": "iVBORw0KGgo..."
      },
      {
        "type": "resource",
        "resource": {
          "uri": "file:///path/to/file",
          "text": "资源内容...",
          "mimeType": "text/plain"
        }
      }
    ],
    "metadata": "额外元数据"
  }
}
```

### 4.2 内容类型处理

| 类型 | 字段 | 处理方式 |
|------|------|---------|
| `text` | `text` | 提取文本，JSON 字符串自动格式化 |
| `image` | `mimeType`, `data` | Base64 解码，存入 ImagePoolManager |
| `resource` | `uri`, `text`, `blob`, `mimeType` | 提取文本或图片，图片转存 |

### 4.3 错误处理

```json
{
  "success": false,
  "error": {
    "code": -32600,
    "message": "Invalid Request"
  }
}
```

错误格式：`[code] message`

---

## 五、JSON 序列化配置

### 5.1 McpJson 配置

```kotlin
val McpJson = Json {
    ignoreUnknownKeys = true    // 忽略未知键，兼容不同版本
    encodeDefaults = true       // 序列化默认值
    isLenient = true            // 宽松解析
}
```

**配置说明**：
- `ignoreUnknownKeys = true`：兼容不同 MCP 服务器实现
- `encodeDefaults = true`：确保所有字段都被序列化
- `isLenient = true`：允许非标准 JSON 格式

---

## 六、数据模型

### 6.1 MCPServerConfig

```kotlin
@Serializable
data class MCPServerConfig(
    val name: String,              // 服务器名称（唯一标识）
    val endpoint: String,          // SSE/HTTP 端点 URL
    val description: String,       // 服务器描述
    val capabilities: List<String>,// 能力列表（tools, resources 等）
    val extraData: Map<String, String> // 额外配置数据
)
```

### 6.2 MCPTool

```kotlin
@Serializable
data class MCPTool(
    val name: String,              // 工具名称
    val description: String,       // 工具描述
    val parameters: List<MCPToolParameter> = emptyList() // 参数列表
)
```

### 6.3 MCPToolParameter

```kotlin
@Serializable
data class MCPToolParameter(
    val name: String,              // 参数名称
    val type: String,              // 参数类型（string/number/boolean/integer/array/object）
    val description: String,       // 参数描述
    val required: Boolean = false, // 是否必需
    val defaultValue: String? = null // 默认值
)
```

### 6.4 MCPPackage

```kotlin
@Serializable
data class MCPPackage(
    val serverConfig: MCPServerConfig,    // 服务器配置
    val mcpTools: List<MCPTool> = emptyList() // 工具列表
)
```

---

## 七、与系统其他模块的集成

### 7.1 与 PackageManager 集成

```kotlin
// PackageManager 将 MCP 服务器作为包使用
val mcpPackage = MCPPackage.fromServer(context, serverConfig)
val toolPackage = mcpPackage.toToolPackage()

// 注册到 PackageManager
packageManager.registerPackage(toolPackage.name, toolPackage)
```

### 7.2 与 Climode 集成

```kotlin
// Climode 将 MCP 工具作为隐藏工具目录条目
val cachedTools = mcpLocalServer.getCachedTools(serverName)
// 添加 MCP 条目到隐藏工具目录
addCachedMcpToolEntries(entries, serverName, serverDescription, cachedTools)
```

### 7.3 与 ToolExecutor 体系集成

```kotlin
// MCPToolExecutor 实现标准 ToolExecutor 接口
class MCPToolExecutor(private val context: Context, private val mcpManager: MCPManager) : ToolExecutor {
    override fun invoke(tool: AITool): ToolResult { ... }
    override fun validateParameters(tool: AITool): ToolValidationResult { ... }
}
```

---

## 八、最佳实践

### 8.1 服务器注册建议

1. **命名规范**：使用简洁有意义的服务器名称
```kotlin
// 好的做法
mcpManager.registerServer("filesystem", ...)
mcpManager.registerServer("github", ...)

// 避免
mcpManager.registerServer("mcp_server_1", ...)
```

2. **错误处理**：始终检查连接结果
```kotlin
val result = MCPPackage.loadFromServer(context, config)
if (result.errorMessage != null) {
    // 处理错误，提示用户
}
```

3. **连接管理**：及时注销不需要的服务器
```kotlin
// 页面销毁时清理
override fun onDestroy() {
    mcpManager.unregisterServer(serverName)
}
```

### 8.2 参数传递建议

1. **使用智能转换**：让系统自动处理类型转换
```kotlin
val params = mapOf(
    "count" to "10",      // 自动转为 Integer
    "enabled" to "true",  // 自动转为 Boolean
    "data" to "[1,2,3]"   // 自动转为 List
)
```

2. **JSON 参数**：复杂对象使用 JSON 字符串
```kotlin
val params = mapOf(
    "config" to """{"timeout": 30, "retries": 3}"""
)
```

### 8.3 结果处理建议

1. **检查 success 字段**：始终检查调用是否成功
2. **处理图片内容**：图片会返回 `<link>` 标签，需要前端支持渲染
3. **截断长结果**：MCPToolExecutor 会自动截断过长结果

---

## 九、错误处理

### 9.1 常见错误

| 错误场景 | 错误信息 | 解决方案 |
|---------|---------|---------|
| 连接失败 | `Cannot connect to MCP server` | 检查服务器地址和网络 |
| 服务未激活 | `MCP service is not activated` | 先使用 `use_package` 激活 |
| 工具名格式错误 | `Invalid MCP tool name format` | 使用 `serverName:toolName` 格式 |
| 参数类型错误 | 转换失败 | 检查参数类型和值 |
| 调用异常 | `Exception occurred while calling tool` | 查看详细错误日志 |

### 9.2 调试技巧

```kotlin
// 查看连接失败详情
val reason = mcpManager.getLastConnectionFailureReason(serverName)
println("失败原因: $reason")

// 查看 MCP 包加载结果
val result = MCPPackage.loadFromServer(context, config)
result.errorMessage?.let { println("错误: $it") }
```

---

## 十、总结

MCP 模块是 Operit AI 的外部能力扩展网关，通过标准化的 MCP 协议将外部服务器的能力无缝集成到 Operit 工具生态中。其设计特点包括：

1. **桥接模式**：通过 `MCPBridgeClient` 解耦 MCP 协议与内部工具系统
2. **连接缓存**：`ConcurrentHashMap` 缓存客户端，支持自动重连
3. **智能类型转换**：自动识别和转换参数类型，支持 JSON 解析
4. **多内容类型支持**：文本、图片、资源统一解析处理
5. **标准接口兼容**：MCP 工具与内置工具使用相同的 `ToolExecutor` 接口
6. **统一命名**：`serverName:toolName` 格式与包工具命名一致

通过本模块，Operit AI 能够轻松接入任意符合 MCP 协议的外部服务，极大地扩展了应用的能力边界。
