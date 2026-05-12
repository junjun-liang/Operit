# Operit 本地模块系统设计思想与详细流程分析

## 一、概述

Operit 的本地模块系统（Local Module System）是整个应用的核心扩展架构，它提供了一套完整的插件化机制，允许开发者通过 JavaScript/TypeScript 编写可动态加载、卸载、执行的模块。该系统不仅支持传统的 JS 工具包（ToolPackage），还引入了更先进的 `.toolpkg` 容器格式，并集成了 MCP（Model Context Protocol）服务器作为外部模块来源。

### 1.1 设计目标

- **动态扩展性**：支持运行时动态加载和卸载模块，无需重新编译应用
- **多格式兼容**：同时支持传统 JS 文件、HJSON 文件和 `.toolpkg` 压缩包格式
- **沙箱执行**：通过 QuickJS 引擎提供安全的 JavaScript 执行环境
- **生命周期管理**：完整的模块生命周期控制（发现、加载、启用、激活、禁用、卸载）
- **UI 集成**：支持模块注册 Compose DSL 界面、导航入口、桌面小部件
- **Hook 系统**：提供丰富的扩展点（消息处理、提示词组合、工具生命周期等）
- **MCP 集成**：支持将外部 MCP 服务器作为模块接入

### 1.2 核心术语

| 术语 | 说明 |
|------|------|
| **ToolPackage** | 传统 JS 格式的工具包，包含元数据和工具定义 |
| **ToolPkg** | 新一代模块容器格式（`.toolpkg`），支持子包、资源、UI 等 |
| **PackageManager** | 模块管理核心类，负责所有模块的生命周期管理 |
| **JsEngine** | JavaScript 执行引擎，基于 QuickJS 实现 |
| **AIToolHandler** | AI 工具处理器，负责注册和管理可执行工具 |
| **MCP** | Model Context Protocol，外部 AI 工具服务协议 |

---

## 二、软件架构图

### 2.1 整体架构

```mermaid
graph TB
    subgraph "应用层"
        A[PackageManagerScreen<br/>包管理界面]
        B[ChatScreen<br/>聊天界面]
        C[ToolboxPlugin<br/>工具箱插件]
    end

    subgraph "核心管理层"
        D[PackageManager<br/>包管理器]
        E[AIToolHandler<br/>AI工具处理器]
        F[PluginRegistry<br/>插件注册表]
    end

    subgraph "模块运行时"
        G[JsEngine<br/>JS执行引擎]
        H[JsToolManager<br/>JS工具管理器]
        I[ToolPkgArchiveParser<br/>ToolPkg解析器]
        J[JsToolPkgRegistrationSession<br/>注册会话]
    end

    subgraph "底层引擎"
        K[OperitQuickJsEngine<br/>QuickJS引擎]
        L[QuickJsNativeRuntime<br/>原生运行时]
    end

    subgraph "存储层"
        M[Assets<br/>内置资源]
        N[ExternalStorage<br/>外部存储]
        O[SharedPreferences<br/>配置存储]
    end

    subgraph "外部集成"
        P[MCPManager<br/>MCP管理器]
        Q[MCPBridgeClient<br/>MCP桥接客户端]
        R[External MCPServers<br/>外部MCP服务器]
    end

    A --> D
    B --> E
    C --> D
    D --> E
    D --> G
    D --> I
    D --> P
    E --> H
    F --> C
    G --> K
    H --> G
    I --> J
    J --> G
    K --> L
    D --> M
    D --> N
    D --> O
    P --> Q
    Q --> R
```

### 2.2 模块层次结构

```mermaid
graph TB
    subgraph "模块来源"
        A1[内置模块<br/>Assets]
        A2[外部模块<br/>External Storage]
        A3[MCP服务器<br/>Remote]
    end

    subgraph "模块类型"
        B1[传统JS包<br/>*.js/*.ts/*.hjson]
        B2[ToolPkg容器<br/>*.toolpkg]
        B3[MCP包<br/>Remote]
    end

    subgraph "ToolPkg内部结构"
        C1[Manifest<br/>manifest.hjson]
        C2[主脚本<br/>main.js]
        C3[子包<br/>Subpackages]
        C4[资源文件<br/>Resources]
        C5[UI模块<br/>UI Modules]
    end

    subgraph "子包结构"
        D1[JS脚本<br/>*.js]
        D2[工具定义<br/>Tools]
        D3[状态定义<br/>States]
    end

    A1 --> B1
    A1 --> B2
    A2 --> B1
    A2 --> B2
    A3 --> B3
    B2 --> C1
    B2 --> C2
    B2 --> C3
    B2 --> C4
    B2 --> C5
    C3 --> D1
    D1 --> D2
    D1 --> D3
```

### 2.3 核心类关系图

```mermaid
classDiagram
    class PackageManager {
        +getInstance(context, aiToolHandler)
        +loadAvailablePackages()
        +enablePackage(packageName)
        +usePackage(packageName)
        +disablePackage(packageName)
        +getAvailablePackages()
        +getEnabledPackageNames()
        -scanAssetPackages()
        -scanExternalPackages()
        -parseJsPackage(jsContent)
        -registerPackageTools(toolPackage)
    }

    class ToolPackage {
        +String name
        +LocalizedText description
        +List~PackageTool~ tools
        +List~ToolPackageState~ states
        +List~EnvVar~ env
        +Boolean isBuiltIn
        +Boolean enabledByDefault
    }

    class PackageTool {
        +String name
        +LocalizedText description
        +List~PackageToolParameter~ parameters
        +String script
        +Boolean advice
    }

    class PackageToolExecutor {
        +invoke(tool)
        +invokeAndStream(tool)
        +validateParameters(tool)
    }

    class JsEngine {
        +evaluate(script)
        +callFunction(functionName, args)
        +executeToolPkgMainRegistration()
        -quickJs: OperitQuickJsEngine
    }

    class JsToolManager {
        +getInstance(context, packageManager)
        +executeScript(script, tool)
        -engines: List~JsEngine~
        -enginePool: Channel~JsEngine~
    }

    class ToolPkgContainerRuntime {
        +String packageName
        +String version
        +String mainEntry
        +ToolPkgSourceType sourceType
        +List~ToolPkgSubpackageRuntime~ subpackages
        +List~ToolPkgResourceRuntime~ resources
        +List~ToolPkgUiRouteRuntime~ uiRoutes
        +List~ToolPkgNavigationEntryRuntime~ navigationEntries
    }

    class ToolPkgArchiveParser {
        +parseToolPkgFromEntries()
        +readZipEntries()
        +extractZipEntries()
    }

    class MCPPackage {
        +fromServer(context, serverConfig)
        +toToolPackage()
        +serverConfig: MCPServerConfig
        +mcpTools: List~MCPTool~
    }

    class PluginRegistry {
        +register(plugin)
        +initializeBuiltins()
        +installAll()
    }

    PackageManager --> ToolPackage
    PackageManager --> PackageToolExecutor
    PackageManager --> JsEngine
    PackageManager --> ToolPkgContainerRuntime
    PackageManager --> MCPPackage
    PackageToolExecutor --> JsToolManager
    JsToolManager --> JsEngine
    JsEngine --> OperitQuickJsEngine
    ToolPkgArchiveParser --> ToolPkgContainerRuntime
    PluginRegistry --> PackageManager
```

---

## 三、模块生命周期流程图

### 3.1 完整生命周期流程

```mermaid
stateDiagram-v2
    [*] --> 发现: 启动应用/刷新
    
    发现 --> 扫描: 扫描模块来源
    
    state 扫描 {
        [*] --> 扫描Assets
        扫描Assets --> 扫描外部存储
        扫描外部存储 --> 扫描MCP服务器
        扫描外部存储 --> [*]
    }
    
    扫描 --> 解析: 解析模块文件
    
    state 解析 {
        [*] --> 解析JS包
        [*] --> 解析ToolPkg
        [*] --> 解析MCP包
        解析JS包 --> 验证元数据
        解析ToolPkg --> 读取Manifest
        读取Manifest --> 解析主脚本
        解析主脚本 --> 注册UI组件
        注册UI组件 --> 解析子包
        解析MCP包 --> 连接服务器
        连接服务器 --> 获取工具列表
    }
    
    解析 --> 可用: 加入可用列表
    可用 --> 启用: 用户启用/默认启用
    
    state 启用 {
        [*] --> 检查环境变量
        检查环境变量 --> 验证通过
        检查环境变量 --> 验证失败
        验证失败 --> [*]
        验证通过 --> 激活子包
        激活子包 --> 缓存资源
    }
    
    启用 --> 激活: usePackage()
    
    state 激活 {
        [*] --> 选择状态
        选择状态 --> 注册工具
        注册工具 --> 生成系统提示
    }
    
    激活 --> 执行: AI调用工具
    
    state 执行 {
        [*] --> 参数转换
        参数转换 --> JS引擎执行
        JS引擎执行 --> 返回结果
    }
    
    执行 --> 激活: 继续对话
    激活 --> 禁用: disablePackage()
    
    state 禁用 {
        [*] --> 注销工具
        注销工具 --> 清理缓存
        清理缓存 --> 保存状态
    }
    
    禁用 --> 可用: 保留元数据
    可用 --> 删除: deletePackage()
    删除 --> [*]
```

### 3.2 模块加载详细流程

```mermaid
sequenceDiagram
    participant User as 用户/系统
    participant PM as PackageManager
    participant Scanner as 扫描器
    participant Parser as 解析器
    participant Reg as 注册器
    participant AITool as AIToolHandler
    participant JsEng as JsEngine

    User->>PM: ensureInitialized()
    PM->>PM: 检查初始化状态
    
    alt 未初始化
        PM->>PM: 创建初始化Future
        PM->>Scanner: loadAvailablePackages()
        
        Scanner->>Scanner: scanAssetPackages()
        Scanner->>Scanner: 读取assets/packages目录
        loop 遍历每个文件
            Scanner->>Parser: loadPackageFromJsAsset()
            Scanner->>Parser: loadToolPkgFromAsset()
        end
        
        Scanner->>Scanner: scanExternalPackages()
        Scanner->>Scanner: 读取外部存储packages目录
        loop 遍历每个文件
            Scanner->>Parser: loadPackageFromJsFile()
            Scanner->>Parser: loadToolPkgFromExternalFile()
        end
        
        Parser->>JsEng: 解析主脚本(registerToolPkg)
        JsEng-->>Parser: 返回注册信息
        Parser-->>Scanner: 返回ToolPkgLoadResult
        
        Scanner-->>PM: 返回PackageScanSnapshot
        PM->>Reg: registerToolPkgInto()
        Reg->>PM: 更新availablePackages
        Reg->>PM: 更新toolPkgContainers
        
        PM->>PM: initializeDefaultPackages()
        PM->>PM: reconcileToolPkgCaches()
        PM->>PM: 标记初始化完成
    end
    
    PM-->>User: 返回初始化结果
```

### 3.3 工具执行流程

```mermaid
sequenceDiagram
    participant AI as AI模型
    participant AITool as AIToolHandler
    participant PM as PackageManager
    participant JsTM as JsToolManager
    participant JsEng as JsEngine
    participant QJS as QuickJS引擎

    AI->>AITool: 调用工具 packageName:toolName
    AITool->>AITool: 查找工具执行器
    AITool->>PM: executeUsePackageTool()
    PM->>PM: usePackage(packageName)
    
    alt 包未激活
        PM->>PM: 加载包数据
        PM->>PM: 验证环境变量
        PM->>PM: 选择包状态
        PM->>AITool: registerPackageTools()
        AITool->>AITool: 注册工具执行器
    end
    
    PM-->>AITool: 返回系统提示
    AITool->>JsTM: invoke(tool)
    JsTM->>JsTM: 解析工具名(packageName:toolName)
    JsTM->>JsTM: 转换参数类型
    JsTM->>JsEng: 获取可用引擎
    
    JsEng->>JsEng: 构建执行参数
    JsEng->>QJS: evaluate(script)
    QJS->>QJS: 执行JavaScript函数
    QJS-->>JsEng: 返回执行结果
    
    JsEng-->>JsTM: 返回ToolResult
    JsTM-->>AITool: 返回ToolResult
    AITool-->>AI: 返回执行结果
```

### 3.4 ToolPkg 解析流程

```mermaid
sequenceDiagram
    participant PM as PackageManager
    participant Parser as ToolPkgArchiveParser
    participant Manifest as Manifest解析
    participant MainReg as 主脚本注册
    participant SubPkg as 子包解析
    participant Reg as 注册会话

    PM->>Parser: loadToolPkgFromExternalFile(file)
    Parser->>Parser: 读取ZIP条目
    Parser->>Manifest: findManifestEntry()
    Manifest->>Manifest: 解析manifest.hjson
    Manifest-->>Parser: 返回ToolPkgManifest
    
    Parser->>MainReg: 读取main.js
    MainReg->>Reg: 执行registerToolPkg()
    Reg->>Reg: 捕获UI模块注册
    Reg->>Reg: 捕获路由注册
    Reg->>Reg: 捕获导航入口注册
    Reg->>Reg: 捕获生命周期Hook注册
    Reg->>Reg: 捕获AI Provider注册
    Reg-->>MainReg: 返回ToolPkgMainRegistrationCapture
    
    MainReg->>MainReg: 解析注册信息
    MainReg-->>Parser: 返回ToolPkgMainRegistration
    
    loop 遍历子包
        Parser->>SubPkg: 读取子包JS文件
        SubPkg->>SubPkg: parseJsPackage()
        SubPkg-->>Parser: 返回ToolPackage
    end
    
    Parser->>Parser: 构建ToolPkgContainerRuntime
    Parser-->>PM: 返回ToolPkgLoadResult
```

---

## 四、核心设计思想

### 4.1 三层状态管理

模块系统采用三层状态模型：

1. **Available（可用）**：所有扫描到的模块，无论是否启用
2. **Enabled（已启用）**：用户明确启用的模块，但工具尚未注册到 AI
3. **Active（已激活）**：当前会话中已注册工具的模块

```mermaid
graph LR
    A[Available<br/>可用列表] --> B[Enabled<br/>启用列表]
    B --> C[Active<br/>激活列表]
    
    style A fill:#e1f5fe
    style B fill:#fff3e0
    style C fill:#e8f5e9
```

### 4.2 双格式兼容架构

系统同时支持传统 JS 包和新一代 ToolPkg 容器：

| 特性 | 传统 JS 包 | ToolPkg 容器 |
|------|-----------|-------------|
| 文件格式 | `.js`/`.ts`/`.hjson` | `.toolpkg` (ZIP) |
| 元数据位置 | 文件头部注释 | `manifest.hjson` |
| 子包支持 | 否 | 是 |
| 资源文件 | 否 | 是 |
| UI 模块 | 否 | 是 |
| 生命周期 Hook | 否 | 是 |
| 桌面小部件 | 否 | 是 |

### 4.3 沙箱执行模型

JavaScript 代码在隔离的 QuickJS 环境中执行：

- **线程隔离**：每个 JsEngine 运行在独立的单线程执行器中
- **引擎池化**：JsToolManager 维护最多 4 个引擎的池，避免频繁创建销毁
- **资源隔离**：Bitmap、二进制数据、Java 对象分别存储在独立的注册表中
- **超时控制**：支持执行超时和取消机制

### 4.4 Hook 扩展系统

ToolPkg 支持丰富的 Hook 注册点：

```mermaid
graph TB
    subgraph "消息处理"
        A[messageProcessingPlugins<br/>消息处理插件]
    end
    
    subgraph "提示词处理"
        B[promptInputHooks<br/>输入Hook]
        C[promptHistoryHooks<br/>历史Hook]
        D[systemPromptComposeHooks<br/>系统提示组合]
        E[toolPromptComposeHooks<br/>工具提示组合]
        F[promptFinalizeHooks<br/>提示词最终化]
    end
    
    subgraph "工具生命周期"
        G[toolLifecycleHooks<br/>工具生命周期]
    end
    
    subgraph "UI扩展"
        H[xmlRenderPlugins<br/>XML渲染插件]
        I[inputMenuTogglePlugins<br/>输入菜单切换]
    end
    
    subgraph "应用生命周期"
        J[appLifecycleHooks<br/>应用生命周期]
    end
    
    subgraph "AI Provider"
        K[aiProviders<br/>AI提供者]
    end
```

### 4.5 MCP 集成架构

MCP（Model Context Protocol）服务器作为外部模块来源：

```mermaid
graph LR
    A[PackageManager] --> B[MCPManager]
    B --> C[MCPBridgeClient]
    C --> D[MCP Server 1]
    C --> E[MCP Server 2]
    C --> F[MCP Server N]
    
    D --> G[MCPTool]
    E --> H[MCPTool]
    F --> I[MCPTool]
    
    G --> J[ToolPackage]
    H --> J
    I --> J
    
    J --> K[AIToolHandler]
```

---

## 五、关键代码解析

### 5.1 PackageManager 初始化逻辑

```kotlin
private fun ensureInitializationStarted(): CompletableFuture<Unit> {
    synchronized(initLock) {
        if (isInitialized) {
            return CompletableFuture.completedFuture(Unit)
        }
        initializationFuture?.let { return it }

        val future = CompletableFuture<Unit>()
        initializationFuture = future

        initializationScope.launch {
            try {
                runtimeCachesReady = false
                // 创建外部包目录
                externalPackagesDir
                // 加载可用包信息
                loadAvailablePackages()
                // 自动导入默认包
                initializeDefaultPackages()
                reconcileToolPkgCaches()

                synchronized(initLock) {
                    isInitialized = true
                }
                refreshToolPkgRuntimeState(persistIfChanged = true)
                future.complete(Unit)
            } catch (e: Exception) {
                future.completeExceptionally(e)
            }
        }
        return future
    }
}
```

### 5.2 工具注册机制

```kotlin
private fun registerPackageTools(toolPackage: ToolPackage) {
    val packageToolExecutor = PackageToolExecutor(toolPackage, context, this)
    val executableTools = toolPackage.tools.filter { !it.advice }
    val newToolNames = executableTools.map { "${toolPackage.name}:${it.name}" }.toSet()
    val oldToolNames = activePackageToolNames[toolPackage.name] ?: emptySet()
    
    // 注销旧工具
    (oldToolNames - newToolNames).forEach { toolName ->
        aiToolHandler.unregisterTool(toolName)
    }
    activePackageToolNames[toolPackage.name] = newToolNames

    // 注册新工具
    executableTools.forEach { packageTool ->
        val toolName = "${toolPackage.name}:${packageTool.name}"
        aiToolHandler.registerTool(toolName) { tool ->
            packageToolExecutor.invoke(tool)
        }
    }
}
```

### 5.3 JsEngine 执行模型

```kotlin
class JsEngine(private val context: Context) {
    private val quickJsExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "OperitQuickJsEngine").apply {
            isDaemon = true
            quickJsThread = this
        }
    }
    private val quickJsDispatcher = quickJsExecutor.asCoroutineDispatcher()

    private fun <T> runOnQuickJsThreadBlocking(block: () -> T): T {
        return if (Thread.currentThread() === quickJsThread) {
            block()
        } else {
            runBlocking(quickJsDispatcher) { block() }
        }
    }

    fun <T> evaluate(script: String, fileName: String = "<eval>"): T? {
        return runOnQuickJsThreadBlocking {
            val result = runtime.eval(script, fileName)
            runtime.executePendingJobs()
            if (!result.success) {
                error(result.describeFailure("QuickJS evaluation failed"))
            }
            decodeJsonValue(result.valueJson) as T?
        }
    }
}
```

### 5.4 ToolPkg 解析核心

```kotlin
internal object ToolPkgArchiveParser {
    fun parseToolPkgFromEntries(
        entries: Map<String, ByteArray>,
        sourceType: ToolPkgSourceType,
        sourcePath: String,
        isBuiltIn: Boolean,
        parseJsPackage: (String, (String, String) -> Unit) -> ToolPackage?,
        parseMainRegistration: (String, String, String) -> ToolPkgMainRegistrationParseResult,
        reportPackageLoadError: (String, String) -> Unit
    ): ToolPkgLoadResult {
        // 1. 查找并解析 manifest
        val manifestEntryName = findManifestEntry(entries)
            ?: throw IllegalArgumentException("manifest.hjson or manifest.json not found")
        val manifest = parseToolPkgManifest(manifestText, manifestEntryName)

        // 2. 读取主脚本
        val mainScriptText = findZipEntryContent(entries, normalizedMainEntry)
            ?.toString(StandardCharsets.UTF_8)
            ?: throw IllegalArgumentException("Cannot find manifest.main entry")

        // 3. 执行主脚本注册
        val mainRegistrationResult = parseMainRegistration(mainScriptText, manifest.toolpkgId, normalizedMainEntry)
        
        // 4. 解析子包
        val subpackagePackages = mutableListOf<ToolPackage>()
        manifest.subpackages.forEach { subpackage ->
            val entryBytes = findZipEntryContent(entries, normalizedSubpackageEntry)
            val jsContent = entryBytes.toString(StandardCharsets.UTF_8)
            val parsedPackage = parseJsPackage(jsContent) { _, error -> reportPackageLoadError(packageName, error) }
            subpackagePackages.add(parsedPackage)
        }

        // 5. 构建运行时对象
        val containerRuntime = ToolPkgContainerRuntime(
            packageName = manifest.toolpkgId,
            displayName = containerDisplayName,
            // ... 其他属性
        )

        return ToolPkgLoadResult(
            containerPackage = containerPackage,
            subpackagePackages = subpackagePackages,
            containerRuntime = containerRuntime
        )
    }
}
```

### 5.5 MCP 包转换

```kotlin
data class MCPPackage(
    val serverConfig: MCPServerConfig,
    val mcpTools: List<MCPTool> = emptyList()
) {
    companion object {
        fun loadFromServer(context: Context, serverConfig: MCPServerConfig): LoadResult {
            val bridgeClient = MCPBridgeClient(context, serverConfig.name)
            val connected = runBlocking { bridgeClient.connect() }
            if (!connected) {
                return LoadResult(null, bridgeClient.getLastConnectionFailureDetail())
            }
            
            val jsonTools = runBlocking { bridgeClient.getTools() }
            val mcpTools = jsonTools.mapNotNull { jsonTool ->
                val name = jsonTool.optString("name", "")
                val description = jsonTool.optString("description", "")
                // 提取参数信息
                val params = mutableListOf<MCPToolParameter>()
                val inputSchema = jsonTool.optJSONObject("inputSchema")
                // ... 参数解析
                MCPTool(name, description, params)
            }
            
            return LoadResult(MCPPackage(serverConfig, mcpTools))
        }
    }

    fun toToolPackage(): ToolPackage {
        val tools = mcpTools.map { mcpTool ->
            PackageTool(
                name = mcpTool.name,
                description = LocalizedText.of(mcpTool.description),
                parameters = mcpTool.parameters.map { /* 转换参数 */ },
                script = generateScriptPlaceholder(serverConfig.name, mcpTool.name)
            )
        }
        
        return ToolPackage(
            name = serverConfig.name,
            description = LocalizedText.of(serverConfig.description),
            tools = tools,
            category = "MCP"
        )
    }
}
```

---

## 六、数据流图

### 6.1 模块加载数据流

```mermaid
flowchart LR
    A[文件系统] --> B[ZIP解析]
    B --> C[Manifest读取]
    C --> D[主脚本执行]
    D --> E[注册信息捕获]
    E --> F[运行时对象构建]
    F --> G[内存缓存]
    G --> H[SharedPreferences]
```

### 6.2 工具执行数据流

```mermaid
flowchart LR
    A[AI请求] --> B[参数解析]
    B --> C[类型转换]
    C --> D[引擎池获取]
    D --> E[QuickJS执行]
    E --> F[结果序列化]
    F --> G[返回AI]
```

---

## 七、缓存机制

### 7.1 ToolPkg 缓存策略

```mermaid
graph TB
    subgraph "缓存层级"
        A[内存缓存<br/>availablePackages]
        B[运行时缓存<br/>toolPkgContainers]
        C[文件缓存<br/>toolpkg_cache目录]
        D[扫描缓存<br/>externalPackageScanCache]
    end
    
    subgraph "缓存键"
        E[包名+版本+文件大小+修改时间]
    end
    
    A --> C
    B --> C
    C --> E
    D --> E
```

### 7.2 缓存签名构建

```kotlin
private fun buildToolPkgCacheSignature(runtime: ToolPkgContainerRuntime): String? {
    return when (runtime.sourceType) {
        ToolPkgSourceType.EXTERNAL -> {
            buildString {
                append("external|")
                append(sourceFile.absolutePath)
                append('|')
                append(sourceFile.length())
                append('|')
                append(sourceFile.lastModified())
                append('|')
                append(runtime.version)
                append('|')
                append(runtime.mainEntry)
            }
        }
        ToolPkgSourceType.ASSET -> {
            buildString {
                append("asset|")
                append(runtime.sourcePath)
                append('|')
                append(apkFile.length())
                append('|')
                append(apkFile.lastModified())
            }
        }
    }
}
```

---

## 八、安全设计

### 8.1 执行隔离

- **线程隔离**：每个 JsEngine 运行在独立线程
- **引擎隔离**：不同模块使用不同的引擎实例
- **资源隔离**：通过注册表管理共享资源

### 8.2 包来源验证

```kotlin
private fun isExternalPackageSourcePath(sourcePath: String?): Boolean {
    if (sourcePath.isNullOrBlank()) return false
    val candidateCanonicalPath = runCatching { File(sourcePath).canonicalPath }.getOrElse { return false }
    val externalRootCanonicalPath = runCatching { externalPackagesDir.canonicalPath }.getOrElse { externalPackagesDir.absolutePath }
    return candidateCanonicalPath.equals(externalRootCanonicalPath, ignoreCase = true) ||
        candidateCanonicalPath.startsWith(externalRootCanonicalPath + File.separator, ignoreCase = true)
}
```

### 8.3 环境变量控制

```kotlin
// 验证必需的环境变量
val missingRequiredEnv = mutableListOf<String>()
toolPackage.env.forEach { envVar ->
    val value = envPreferences.getEnv(envVar.name)
    if (envVar.required && value.isNullOrEmpty()) {
        missingRequiredEnv.add(envVar.name)
    }
}
if (missingRequiredEnv.isNotEmpty()) {
    return "Package requires environment variables: ${missingRequiredEnv.joinToString(", ")}"
}
```

---

## 九、UI 集成架构

### 9.1 Compose DSL 渲染流程

```mermaid
sequenceDiagram
    participant UI as UI层
    participant PM as PackageManager
    participant Parser as ToolPkgComposeDslParser
    participant Renderer as ComposeDslRenderer

    UI->>PM: getToolPkgComposeDslScript()
    PM->>PM: 读取ToolPkg资源
    PM-->>UI: 返回DSL脚本
    
    UI->>Parser: parseRenderResult()
    Parser->>Parser: 解析JSON树
    Parser-->>UI: 返回ToolPkgComposeDslRenderResult
    
    UI->>Renderer: 渲染节点树
    Renderer->>Renderer: 递归渲染每个节点
    Renderer-->>UI: 返回Compose UI
```

### 9.2 导航入口注册

```kotlin
// ToolPkg 可以注册导航入口
data class ToolPkgNavigationEntryRuntime(
    val id: String,
    val routeId: String,
    val surface: String,  // "toolbox" 或 "main_sidebar_plugins"
    val title: LocalizedText,
    val action: ToolPkgNavigationActionHookRuntime? = null,
    val icon: String? = null,
    val order: Int = 0
)
```

---

## 十、总结

Operit 的本地模块系统是一个设计精良、功能丰富的插件化架构，具有以下特点：

1. **高度可扩展**：支持多种模块格式和丰富的扩展点
2. **安全可靠**：通过 QuickJS 沙箱和线程隔离保证执行安全
3. **性能优化**：引擎池化、多级缓存、异步加载
4. **生态友好**：支持传统 JS 包、ToolPkg 容器和 MCP 服务器
5. **UI 深度集成**：支持 Compose DSL、导航入口、桌面小部件
6. **生命周期完整**：从发现到卸载的完整生命周期管理

该系统为 Operit 提供了强大的扩展能力，使第三方开发者能够轻松地为应用添加新功能，同时保持了核心应用的稳定性和安全性。
