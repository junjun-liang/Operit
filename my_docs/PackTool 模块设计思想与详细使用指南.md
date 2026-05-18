# PackTool 模块设计思想与详细使用指南

## 一、模块概述

`packTool` 模块是 Operit AI 的**包管理核心**，负责工具包（Tool Package）的全生命周期管理，包括发现、加载、解析、注册、启用/禁用、执行和卸载。它支持两种主要的包格式：

- **传统 JS 包**：单文件 JavaScript/HJSON 格式，包含元数据和工具定义
- **ToolPkg 容器**：ZIP 归档格式（`.toolpkg`），支持多子包、资源文件、UI 模块、工作流模板等高级特性

### 1.1 核心定位

- **包发现引擎**：扫描 assets 和外部存储中的包文件
- **包解析器**：解析 JS/HJSON 元数据和 ToolPkg 归档结构
- **包注册中心**：管理包的启用状态和工具注册
- **工具执行网关**：将包中的工具注册到 AI 工具处理器
- **资源管理器**：管理 ToolPkg 中的资源文件和缓存
- **UI 运行时桥梁**：提供 Compose DSL 渲染和导航入口

### 1.2 模块结构

```
packTool/
├── PackageManager.kt                    # 核心管理器：包生命周期管理
├── PackageManagerToolPkgFacade.kt       # ToolPkg 外观：高级功能封装
├── ToolPkgParser.kt                     # ToolPkg 解析器：ZIP 归档解析
├── ToolPkgMainRegistrationScriptParser.kt # 主注册脚本解析：JS 注册函数解析
├── ToolPkgComposeDslParser.kt           # Compose DSL 解析器：UI 树解析
├── ToolPkgHookModels.kt                 # Hook 数据模型：事件结果定义
├── ToolPkgCommonPluginConstants.kt      # 常量定义：事件类型和注册名称
├── ToolPkgTemplateModels.kt             # 模板数据模型
├── PackageDebugRefreshReceiver.kt       # 调试刷新广播接收器
├── ToolPkgDebugInstallReceiver.kt       # 调试安装广播接收器
└── ToolPkgComposeDslDebugDumpReceiver.kt # Compose DSL 调试转储接收器
```

---

## 二、核心设计思想

### 2.1 三层包生命周期模型

PackageManager 采用清晰的三层生命周期模型：

```
┌─────────────────────────────────────────────────────────────┐
│                    Available Packages                        │
│         (所有可发现的包：assets + 外部存储)                     │
├─────────────────────────────────────────────────────────────┤
│                    Enabled Packages                          │
│         (用户启用的包，可加载使用但不一定激活)                   │
├─────────────────────────────────────────────────────────────┤
│                     Used Packages                            │
│         (当前会话中已激活并注册到 AI 的包)                      │
└─────────────────────────────────────────────────────────────┘
```

**设计要点**：
- **Available**：通过扫描 `assets/packages/` 和外部存储 `Android/data/<pkg>/files/packages/` 发现
- **Enabled**：用户显式启用的包，持久化到 SharedPreferences
- **Used**：通过 `usePackage()` 激活，工具注册到 `AIToolHandler`，生成系统提示词

### 2.2 双格式包支持

#### 传统 JS 包

```javascript
/* METADATA
{
    "name": "myPackage",
    "display_name": "My Package",
    "description": "A sample package",
    "version": "1.0.0",
    "author": ["Author Name"],
    "tools": [
        {
            "name": "greet",
            "description": "Greet someone",
            "parameters": [
                { "name": "name", "type": "string", "required": true }
            ]
        }
    ]
}
*/

function greet(params) {
    return "Hello, " + params.name + "!";
}
```

#### ToolPkg 容器

ToolPkg 是 ZIP 归档，包含：

```
myPackage.toolpkg/
├── manifest.hjson          # 包清单
├── main.js                 # 主入口脚本（注册 UI、Hook 等）
├── subpackages/
│   ├── tools.js            # 子包：工具定义
│   └── utils.js            # 子包：工具定义
├── resources/
│   ├── icon.png            # 资源文件
│   └── templates/
│       └── workflow.json   # 工作流模板
└── screens/
    └── home.js             # UI 屏幕脚本
```

**manifest.hjson 示例**：

```hjson
{
    schema_version: 1
    toolpkg_id: "my.toolpkg"
    version: "1.0.0"
    main: "main.js"
    display_name: {
        default: "My ToolPkg"
        zh: "我的工具包"
    }
    description: {
        default: "A comprehensive tool package"
        zh: "一个综合工具包"
    }
    enabled_by_default: true
    subpackages: [
        { id: "tools", entry: "subpackages/tools.js" }
        { id: "utils", entry: "subpackages/utils.js" }
    ]
    resources: [
        { key: "icon", path: "resources/icon.png", mime: "image/png" }
        { key: "templates", path: "resources/templates", mime: "inode/directory" }
    ]
    workflow_templates: [
        { id: "sample", resource_key: "templates", display_name: "Sample Workflow" }
    ]
}
```

### 2.3 ToolPkg 解析架构

#### 解析流程

```
ZIP 文件
    │
    ▼
ToolPkgArchiveParser.readZipEntries() → Map<String, ByteArray>
    │
    ▼
查找 manifest.hjson / manifest.json
    │
    ▼
解析 manifest → ToolPkgManifest
    │
    ▼
解析 main.js 中的 registerToolPkg() → ToolPkgMainRegistration
    │
    ▼
解析 subpackages/*.js → List<ToolPackage>
    │
    ▼
验证资源路径、UI 路由、导航入口
    │
    ▼
构建 ToolPkgContainerRuntime
```

#### 核心解析器

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
    ): ToolPkgLoadResult
}
```

### 2.4 主注册脚本机制

ToolPkg 的 `main.js` 通过 `registerToolPkg()` 函数注册扩展能力：

```javascript
function registerToolPkg(params) {
    return {
        toolboxUiModules: [
            { id: "home", screen: "screens/home.js", title: "Home" }
        ],
        uiRoutes: [
            { id: "settings", route: "toolpkg:my.toolpkg:ui:settings", screen: "screens/settings.js", title: "Settings" }
        ],
        navigationEntries: [
            { id: "home_nav", route: "toolpkg:my.toolpkg:ui:home", surface: "toolbox", title: "Home" }
        ],
        appLifecycleHooks: [
            { id: "init", event: "application_on_create", function: "onAppCreate" }
        ],
        messageProcessingPlugins: [
            { id: "processor", function: "processMessage" }
        ],
        aiProviders: [
            {
                id: "custom_ai",
                displayName: "Custom AI",
                listModels: { function: "listModels" },
                sendMessage: { function: "sendMessage" },
                testConnection: { function: "testConnection" },
                calculateInputTokens: { function: "calculateTokens" }
            }
        ]
    };
}
```

**解析过程**：
1. `ToolPkgMainRegistrationScriptParser` 使用 `JsEngine` 执行 `registerToolPkg()`
2. 提取返回的注册信息
3. 验证 ID 唯一性、路由有效性、屏幕文件存在性
4. 构建 `ToolPkgMainRegistration` 对象

### 2.5 缓存与性能优化

#### 文件签名缓存

```kotlin
private data class ExternalPackageScanCacheEntry(
    val signature: String,  // "path|size|lastModified"
    val result: PackageScanCandidateResult
)
```

外部包扫描使用文件签名缓存，避免重复解析未变更的文件。

#### ToolPkg 缓存目录

```kotlin
private fun ensureToolPkgCache(runtime: ToolPkgContainerRuntime): File? {
    val signature = buildToolPkgCacheSignature(runtime)  // "external|path|size|modified|version|mainEntry"
    // 检查签名是否匹配，不匹配则重新解压
    // 缓存目录：/data/data/<pkg>/files/toolpkg_cache/<safeName-hash>/
}
```

ToolPkg 归档解压到缓存目录，避免每次读取 ZIP。

### 2.6 外观模式封装

`PackageManagerToolPkgFacade` 为 ToolPkg 特定功能提供统一入口：

```kotlin
internal class PackageManagerToolPkgFacade(private val packageManager: PackageManager) {
    fun getToolPkgUiRoutes(runtime: String = "compose_dsl"): List<ToolPkgUiRoute>
    fun getToolPkgNavigationEntries(): List<ToolPkgNavigationEntry>
    fun getToolPkgDesktopWidgets(): List<ToolPkgDesktopWidget>
    fun getToolPkgWorkflowTemplates(): List<ToolPkgWorkflowTemplate>
    fun importToolPkgWorkflowTemplate(containerPackageName: String, templateId: String): Result<Workflow>
    fun getToolPkgComposeDslScript(containerPackageName: String, uiModuleId: String?): String?
    fun runToolPkgMainHook(...): Result<Any?>
    fun copyToolPkgResourceToFile(...): Boolean
}
```

---

## 三、详细使用方法

### 3.1 包管理基础操作

#### 获取 PackageManager 实例

```kotlin
val packageManager = PackageManager.getInstance(context, aiToolHandler)
```

#### 发现可用包

```kotlin
// 获取所有可用包（缓存）
val availablePackages = packageManager.getAvailablePackages()

// 强制刷新（重新扫描外部存储）
val refreshedPackages = packageManager.getAvailablePackages(forceRefresh = true)

// 获取顶层包（排除子包）
val topLevelPackages = packageManager.getTopLevelAvailablePackages()
```

#### 启用/禁用包

```kotlin
// 启用包
val message = packageManager.enablePackage("myPackage")
// 返回: "Successfully enabled package: myPackage"

// 禁用包
val message = packageManager.disablePackage("myPackage")
// 返回: "Successfully disabled package: myPackage"

// 检查包是否启用
val isEnabled = packageManager.isPackageEnabled("myPackage")
```

#### 激活包（usePackage）

```kotlin
// 激活包，注册工具到 AI
val systemPrompt = packageManager.usePackage("myPackage")
// 返回: "Using package: myPackage\nUse Time: ...\nDescription: ...\nAvailable tools: ..."

// 执行 use_package 工具
val toolResult = packageManager.executeUsePackageTool("use_package", "myPackage")
```

### 3.2 包导入与删除

#### 从外部存储导入

```kotlin
// 导入包文件（支持 .toolpkg, .js, .ts, .hjson）
val result = packageManager.addPackageFileFromExternalStorage("/sdcard/myPackage.toolpkg")
// 返回: "Successfully imported toolpkg: my.toolpkg\nStored at: ..."
```

#### 删除包

```kotlin
// 删除外部包
val deleted = packageManager.deletePackage("myPackage")

// 删除外部包源文件
val deleted = packageManager.deleteExternalPackageSource("/sdcard/myPackage.toolpkg")
```

### 3.3 ToolPkg 子包管理

#### 子包状态控制

```kotlin
// 启用/禁用子包
val success = packageManager.setToolPkgSubpackageEnabled("my.toolpkg:tools", true)

// 查找子包首选包名
val packageName = packageManager.findPreferredPackageNameForSubpackageId("tools")

// 获取子包信息
val containerDetails = packageManager.getToolPkgContainerDetails("my.toolpkg")
containerDetails?.subpackages?.forEach { subpackage ->
    println("Subpackage: ${subpackage.displayName}, Enabled: ${subpackage.enabled}")
}
```

### 3.4 资源访问

#### 读取资源文件

```kotlin
// 通过容器包名读取资源
val text = packageManager.readToolPkgTextResource(
    packageNameOrSubpackageId = "my.toolpkg",
    resourcePath = "resources/config.json"
)

// 通过子包 ID 读取资源
val text = packageManager.readToolPkgTextResource(
    packageNameOrSubpackageId = "tools",
    resourcePath = "assets/data.json"
)

// 复制资源到文件
val success = packageManager.copyToolPkgResourceToFile(
    containerPackageName = "my.toolpkg",
    resourceKey = "icon",
    destinationFile = File("/sdcard/icon.png")
)

// 获取资源输出文件名
val fileName = packageManager.getToolPkgResourceOutputFileName("my.toolpkg", "icon")
```

### 3.5 UI 路由与导航

#### 获取 UI 路由

```kotlin
// 获取所有 Compose DSL 路由
val routes = packageManager.getToolPkgUiRoutes(runtime = "compose_dsl")
routes.forEach { route ->
    println("Route: ${route.routeId}, Screen: ${route.screen}, Title: ${route.title}")
}

// 获取导航入口
val entries = packageManager.getToolPkgNavigationEntries()
entries.forEach { entry ->
    println("Entry: ${entry.entryId}, Surface: ${entry.surface}, Route: ${entry.routeId}")
}

// 获取桌面小部件
val widgets = packageManager.getToolPkgDesktopWidgets()
```

#### 获取 Compose DSL 脚本

```kotlin
// 获取 UI 模块脚本
val script = packageManager.getToolPkgComposeDslScript("my.toolpkg", "home")

// 通过子包 ID 获取
val script = packageManager.getToolPkgComposeDslScriptBySubpackageId("tools", "home")

// 获取屏幕路径
val screenPath = packageManager.getToolPkgComposeDslScreenPath("my.toolpkg", "home")
```

### 3.6 Hook 执行

#### 运行主 Hook

```kotlin
// 执行 ToolPkg 主脚本中的 Hook 函数
val result = packageManager.runToolPkgMainHook(
    containerPackageName = "my.toolpkg",
    functionName = "processMessage",
    event = "toolpkg_message_processing",
    eventPayload = mapOf(
        "message" to "Hello",
        "chatId" to "123"
    ),
    onIntermediateResult = { delta ->
        println("Delta: $delta")
    }
)

result.onSuccess { value ->
    println("Result: $value")
}.onFailure { error ->
    println("Error: ${error.message}")
}
```

#### 导航入口 Action

```kotlin
val result = packageManager.runToolPkgNavigationEntryAction(
    containerPackageName = "my.toolpkg",
    entryId = "home_nav",
    functionName = "onNavigate",
    eventPayload = mapOf("route" to "home")
)
```

### 3.7 模板导入

#### 工作流模板

```kotlin
// 获取工作流模板
val templates = packageManager.getToolPkgWorkflowTemplates()

// 导入工作流模板
val result = packageManager.importToolPkgWorkflowTemplate("my.toolpkg", "sample")
result.onSuccess { workflow ->
    println("Imported workflow: ${workflow.name}")
}
```

#### 工作空间模板

```kotlin
// 获取工作空间模板
val templates = packageManager.getToolPkgWorkspaceTemplates()

// 导入工作空间模板
val result = packageManager.importToolPkgWorkspaceTemplate(
    containerPackageName = "my.toolpkg",
    templateId = "project_template",
    destinationDir = File("/sdcard/workspace")
)
result.onSuccess { importResult ->
    println("Workspace path: ${importResult.workspacePath}")
}
```

### 3.8 调试开发

#### 调试安装 ToolPkg

```kotlin
// 开发调试：安装/重新加载 ToolPkg
val message = packageManager.installDebugToolPkg(
    containerPackageName = "my.toolpkg",
    externalFilePath = "/sdcard/Android/data/<pkg>/files/packages/my.toolpkg.toolpkg",
    resetSubpackageStatesToManifest = true
)
```

#### 刷新外部包

```kotlin
// 调试刷新外部包
val message = packageManager.refreshExternalPackagesForDebug(reactivateActivePackages = true)
```

---

## 四、事件类型与 Hook 系统

### 4.1 应用生命周期事件

| 事件常量 | 说明 |
|---------|------|
| `application_on_create` | 应用创建时 |
| `application_on_foreground` | 应用进入前台时 |
| `application_on_background` | 应用进入后台时 |
| `application_on_low_memory` | 内存不足时 |
| `application_on_trim_memory` | 内存回收时 |
| `application_on_terminate` | 应用终止时 |
| `activity_on_create` | Activity 创建时 |
| `activity_on_start` | Activity 启动时 |
| `activity_on_resume` | Activity 恢复时 |
| `activity_on_pause` | Activity 暂停时 |
| `activity_on_stop` | Activity 停止时 |
| `activity_on_destroy` | Activity 销毁时 |

### 4.2 消息处理事件

| 事件常量 | 说明 |
|---------|------|
| `toolpkg_message_processing` | 消息处理时 |
| `toolpkg_xml_render` | XML 渲染时 |
| `toolpkg_input_menu_toggle` | 输入菜单切换时 |
| `toolpkg_navigation_entry_action` | 导航入口点击时 |
| `toolpkg_tool_lifecycle` | 工具生命周期事件时 |

### 4.3 Prompt 处理事件

| 事件常量 | 说明 |
|---------|------|
| `toolpkg_prompt_input` | Prompt 输入时 |
| `toolpkg_prompt_history` | Prompt 历史处理时 |
| `toolpkg_prompt_estimate_history` | Prompt 估算历史时 |
| `toolpkg_system_prompt_compose` | 系统 Prompt 组合时 |
| `toolpkg_tool_prompt_compose` | 工具 Prompt 组合时 |
| `toolpkg_prompt_finalize` | Prompt 最终确定时 |
| `toolpkg_prompt_estimate_finalize` | Prompt 估算最终确定时 |
| `toolpkg_summary_generate` | 摘要生成时 |

### 4.4 AI Provider 事件

| 事件常量 | 说明 |
|---------|------|
| `toolpkg_ai_provider_list_models` | 列出模型时 |
| `toolpkg_ai_provider_send_message` | 发送消息时 |
| `toolpkg_ai_provider_test_connection` | 测试连接时 |
| `toolpkg_ai_provider_calculate_input_tokens` | 计算输入 Token 时 |

---

## 五、Compose DSL 解析

### 5.1 解析 UI 树

`ToolPkgComposeDslParser` 将 JavaScript 返回的 UI 定义解析为 Kotlin 数据类：

```kotlin
data class ToolPkgComposeDslNode(
    val type: String,                    // 组件类型：Column, Text, Button 等
    val props: Map<String, Any?>,        // 组件属性
    val children: List<ToolPkgComposeDslNode>,  // 子组件
    val slots: Map<String, List<ToolPkgComposeDslNode>> = emptyMap()  // 插槽
)

data class ToolPkgComposeDslRenderResult(
    val tree: ToolPkgComposeDslNode,
    val state: Map<String, Any?>,
    val memo: Map<String, Any?>
)
```

### 5.2 解析示例

```javascript
// JavaScript 返回的 UI 定义
return {
    composeDsl: {
        screen: {
            type: "Scaffold",
            props: { topBar: { type: "TopAppBar", props: { title: "My Screen" } } },
            children: [
                {
                    type: "Column",
                    props: { modifier: "padding(16).dp" },
                    children: [
                        { type: "Text", props: { text: "Hello" } },
                        { type: "Button", props: { onClick: "__action:increment" }, children: ["Click me"] }
                    ]
                }
            ]
        }
    },
    state: { count: 0 },
    memo: {}
};
```

### 5.3 Action ID 提取

```kotlin
// 从 onClick 等事件属性中提取 Action ID
val actionId = ToolPkgComposeDslParser.extractActionId("__action:increment")
// 返回: "increment"
```

---

## 六、Hook 结果数据模型

### 6.1 XML 渲染 Hook 结果

```kotlin
data class ToolPkgXmlRenderHookObjectResult(
    val handled: Boolean? = null,        // 是否已处理
    val text: String? = null,            // 纯文本结果
    val content: String? = null,         // 内容结果
    val composeDsl: ToolPkgXmlRenderHookComposeDslResult? = null  // Compose DSL 结果
)
```

### 6.2 工具生命周期事件

```kotlin
data class ToolPkgToolLifecycleEventPayload(
    val toolName: String,
    val parameters: Map<String, String> = emptyMap(),
    val description: String? = null,
    val granted: Boolean? = null,        // 权限是否授予
    val reason: String? = null,
    val success: Boolean? = null,        // 执行是否成功
    val errorMessage: String? = null,
    val resultText: String? = null,
    val resultJson: Map<String, Any?>? = null
)
```

### 6.3 Prompt Hook 结果

```kotlin
data class ToolPkgPromptHookObjectResult(
    val rawInput: String? = null,
    val processedInput: String? = null,
    val chatHistory: List<ToolPkgPromptTurn>? = null,
    val preparedHistory: List<ToolPkgPromptTurn>? = null,
    val systemPrompt: String? = null,
    val toolPrompt: String? = null,
    val metadata: Map<String, Any?> = emptyMap()
)
```

---

## 七、状态与条件系统

### 7.1 包状态选择

PackageManager 支持基于条件的包状态切换：

```kotlin
// 在 ToolPackage 中定义多个状态
{
    "states": [
        {
            "id": "standard",
            "condition": "android.permission_level == 'standard'",
            "inheritTools": true,
            "tools": [...]
        },
        {
            "id": "root",
            "condition": "android.permission_level == 'root'",
            "inheritTools": true,
            "excludeTools": ["limited_tool"],
            "tools": [...]
        }
    ]
}
```

### 7.2 条件评估

```kotlin
private fun buildConditionCapabilitiesSnapshot(): Map<String, Any?> {
    return mapOf(
        "ui.virtual_display" to virtualDisplayCapable,
        "android.permission_level" to permissionLevel,
        "android.shizuku_available" to shizukuAvailable,
        "ui.shower_display" to showerDisplayAvailable
    )
}
```

---

## 八、MCP 服务器集成

PackageManager 支持将 MCP（Model Context Protocol）服务器作为包使用：

```kotlin
// 使用 MCP 服务器
val prompt = packageManager.useMCPServer("myMcpServer")

// 获取可用 MCP 服务器
val servers = packageManager.getAvailableServerPackages()
```

---

## 九、最佳实践

### 9.1 包开发建议

1. **使用 ToolPkg 格式**：对于复杂包，使用 `.toolpkg` 格式支持资源、UI 和多子包
2. **合理划分子包**：将相关工具组织到子包中，用户可按需启用
3. **提供完整元数据**：包括 `display_name`、`description`、`author` 等
4. **使用本地化文本**：支持多语言显示

### 9.2 资源管理

1. **使用资源键引用**：在 manifest 中定义资源，通过键访问
2. **目录资源用于模板**：工作空间模板使用目录资源
3. **文件资源用于工作流**：工作流模板使用文件资源

### 9.3 Hook 开发

1. **处理事件参数**：Hook 函数接收 `event`、`eventPayload` 等参数
2. **返回标准格式**：根据 Hook 类型返回对应的结果格式
3. **使用 inline function source**：对于简单逻辑，可直接提供函数源码

### 9.4 调试技巧

1. **使用调试安装**：开发时使用 `installDebugToolPkg()` 快速迭代
2. **查看加载错误**：`getPackageLoadErrors()` 获取详细的加载错误信息
3. **检查缓存状态**：ToolPkg 缓存位于 `/data/data/<pkg>/files/toolpkg_cache/`

---

## 十、总结

PackTool 模块是 Operit AI 的包管理中枢，通过清晰的三层生命周期模型、双格式包支持、完善的缓存机制和丰富的 Hook 系统，为工具包的开发、分发和使用提供了完整的解决方案。其设计特点包括：

1. **灵活性**：支持传统 JS 包和高级 ToolPkg 容器两种格式
2. **扩展性**：通过 Hook 系统支持消息处理、Prompt 处理、AI Provider 等扩展点
3. **性能**：文件签名缓存和 ToolPkg 解压缓存避免重复解析
4. **本地化**：完整的本地化文本支持
5. **调试友好**：提供调试安装、刷新、错误报告等开发工具

通过本模块，开发者可以创建功能丰富的工具包，包括工具、UI 界面、工作流模板、工作空间模板和 AI Provider 等。
