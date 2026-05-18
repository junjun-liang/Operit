# Climode 模块设计思想与详细使用指南

## 一、模块概述

`climode` 模块是 Operit AI 的 **CLI 工具模式支持系统**，专门为本地模型（如 LMStudio、Ollama、llama.cpp 等）设计。这些本地模型通常不支持函数调用（Function Calling），因此 Operit 采用了一种创新的 **"隐藏工具目录 + 代理执行"** 架构：只向 AI 暴露两个公开工具（`search` 和 `proxy`），所有真实能力都隐藏在代理层后面，由 AI 通过搜索发现后间接调用。

### 1.1 核心定位

- **本地模型适配器**：为不支持函数调用的本地模型提供工具使用能力
- **工具目录管理器**：构建、维护和搜索隐藏工具目录
- **代理执行网关**：`proxy` 工具作为统一入口，转发 AI 的请求到实际工具
- **权限控制中间件**：基于角色卡的工具访问权限控制

### 1.2 模块结构

```
climode/
└── CliToolModeSupport.kt    # CLI 工具模式支持（单文件完整实现）
```

### 1.3 核心概念

| 概念 | 说明 |
|------|------|
| **公开工具** | AI 直接可见的工具，只有 `search` 和 `proxy` |
| **隐藏工具** | AI 不可直接见，需要通过 `search` 发现后通过 `proxy` 调用的工具 |
| **工具目录** | 所有隐藏工具的元数据集合，支持搜索和发现 |
| **代理目标** | `proxy` 工具实际转发的目标工具名 |
| **角色卡权限** | 基于当前角色卡配置的工具访问控制 |

---

## 二、核心设计思想

### 2.1 双工具暴露模式

Operit 根据 AI Provider 类型自动选择工具暴露模式：

```
┌─────────────────────────────────────────────────────────────┐
│                    ToolExposureMode                          │
├─────────────────────────────────────────────────────────────┤
│  FULL 模式                                                   │
│  - 所有工具直接暴露给 AI                                      │
│  - 支持函数调用的模型（OpenAI、Gemini、Claude 等）             │
│  - AI 可以直接调用任意工具                                    │
├─────────────────────────────────────────────────────────────┤
│  CLI 模式                                                    │
│  - 只暴露 search 和 proxy 两个工具                            │
│  - 本地模型（LMStudio、Ollama、llama.cpp、MNN 等）            │
│  - AI 必须先搜索，再通过 proxy 间接调用                       │
└─────────────────────────────────────────────────────────────┘
```

**自动判断逻辑**：

```kotlin
enum class ToolExposureMode {
    FULL, CLI;

    companion object {
        fun resolve(providerType: ApiProviderType): ToolExposureMode {
            return when (providerType) {
                ApiProviderType.LMSTUDIO,
                ApiProviderType.OLLAMA,
                ApiProviderType.OPENAI_LOCAL,
                ApiProviderType.MNN,
                ApiProviderType.LLAMA_CPP -> CLI
                else -> FULL
            }
        }
    }
}
```

### 2.2 隐藏工具目录架构

```
AI 请求
    │
    ▼
┌─────────────────┐
│   search 工具    │  ← AI 可见
│  "搜索隐藏工具"   │
└─────────────────┘
    │
    ▼
┌─────────────────────────────────────────┐
│         Hidden Tool Catalog              │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐   │
│  │Built-in │ │ Package │ │  MCP    │   │
│  │  Tools  │ │  Tools  │ │ Servers │   │
│  └─────────┘ └─────────┘ └─────────┘   │
│  ┌─────────┐ ┌─────────┐               │
│  │  Skill  │ │Activation│               │
│  │Packages │ │ Entries  │               │
│  └─────────┘ └─────────┘               │
└─────────────────────────────────────────┘
    │
    ▼
┌─────────────────┐
│   proxy 工具     │  ← AI 可见
│  "执行隐藏工具"   │
└─────────────────┘
    │
    ▼
实际工具执行（read_file、shell、package 工具等）
```

### 2.3 目录条目来源

隐藏工具目录包含 5 种来源的工具条目：

| 来源 | 类型 | 说明 |
|------|------|------|
| `BUILTIN` | 内置工具 | 系统内置的 AI 工具（如 read_file、shell 等） |
| `INTERNAL` | 内部工具 | 应用内部工具（非 AI 直接可见的辅助工具） |
| `PACKAGE` | 包工具 | 已启用的传统 JS 包和 ToolPkg 容器中的工具 |
| `MCP` | MCP 工具 | MCP 服务器提供的工具（已缓存工具列表） |
| `ACTIVATION` | 激活条目 | 需要激活的包/技能/MCP 服务器（无具体工具） |

### 2.4 搜索评分算法

`searchHiddenToolCatalog` 实现了多维度评分搜索：

```
评分维度（按优先级排序）：

1. 精确匹配 (+300)
   - 显示名或目标工具名完全等于查询词

2. 前缀匹配 (+140)
   - 显示名或目标工具名以查询词开头

3. 包含匹配 (+100)
   - 显示名或目标工具名包含查询词

4. 描述/关键词匹配 (+40)
   - 描述或关键词包含查询词

5. 参数匹配 (+25)
   - 参数提示包含查询词

6. 多词匹配 (+40/词)
   - 每个匹配的词额外加分

7. 全词匹配奖励 (+30)
   - 所有查询词都匹配时额外奖励
```

### 2.5 角色卡权限控制

基于当前角色卡配置，控制 AI 可以访问哪些隐藏工具：

```kotlin
fun isToolNameAllowedForRoleCard(
    toolName: String,
    usePackageSourceName: String?,
    roleCardToolAccess: ResolvedCharacterCardToolAccess
): Boolean {
    return when {
        // use_package 工具需要特殊处理
        toolName == "use_package" -> {
            if (!roleCardToolAccess.isBuiltinToolAllowed("use_package")) {
                false
            } else {
                usePackageSourceName.isNullOrBlank() ||
                    roleCardToolAccess.isExternalSourceAllowed(usePackageSourceName)
            }
        }
        // 带冒号的工具名（如 packageName:toolName）检查外部源权限
        toolName.contains(':') -> {
            val sourceName = toolName.substringBefore(':').trim()
            sourceName.isBlank() || roleCardToolAccess.isExternalSourceAllowed(sourceName)
        }
        // 普通工具名检查内置工具权限
        else -> roleCardToolAccess.isBuiltinToolAllowed(toolName)
    }
}
```

---

## 三、详细使用方法

### 3.1 判断当前模式

```kotlin
import com.ai.assistance.operit.core.tools.climode.ToolExposureMode
import com.ai.assistance.operit.data.model.ApiProviderType

// 根据 Provider 类型判断工具暴露模式
val mode = ToolExposureMode.resolve(ApiProviderType.OLLAMA)
when (mode) {
    ToolExposureMode.FULL -> println("使用 FULL 模式，所有工具直接暴露")
    ToolExposureMode.CLI -> println("使用 CLI 模式，仅暴露 search 和 proxy")
}
```

### 3.2 构建 CLI 模式提示词

```kotlin
import com.ai.assistance.operit.core.tools.climode.CliToolModeSupport

// 生成 CLI 模式的系统提示词（中文）
val cliPromptCn = CliToolModeSupport.buildCliModePrompt(useEnglish = false)
println(cliPromptCn)

// 生成 CLI 模式的系统提示词（英文）
val cliPromptEn = CliToolModeSupport.buildCliModePrompt(useEnglish = true)
println(cliPromptEn)
```

**生成的提示词示例（中文）**：

```
CLI 工具模式
- 当前只有两个公开工具：`search` 和 `proxy`。
- `search` 只搜索隐藏工具目录，不会直接读文件、搜代码或访问网页。
- 所有真实能力都隐藏在 `proxy` 后面。
- 不要直接调用隐藏工具。先用 `search`，再用发现到的目标工具名和 JSON 参数调用 `proxy`。

公开工具
- search: 仅搜索隐藏工具目录。先用它发现隐藏工具名和参数形态。
  参数:
    - query [string, required]: 要搜索的工具能力或隐藏工具名
    - limit [integer, optional]: 可选，返回的最大结果数，默认: 8
- proxy: 在 search 发现目标工具名和参数形态后，代理执行隐藏工具。
  参数:
    - tool_name [string, required]: 隐藏目标工具名，例如 read_file 或 packageName:toolName
    - params [object, required]: 转发给隐藏目标工具的 JSON 参数对象
```

### 3.3 构建隐藏工具目录

```kotlin
import com.ai.assistance.operit.core.tools.climode.CliToolModeSupport
import com.ai.assistance.operit.core.tools.packTool.PackageManager
import com.ai.assistance.operit.data.preferences.ResolvedCharacterCardToolAccess

// 构建隐藏工具目录
val catalog = CliToolModeSupport.buildHiddenToolCatalog(
    context = context,
    packageManager = PackageManager.getInstance(context),
    roleCardToolAccess = ResolvedCharacterCardToolAccess(/* 角色卡配置 */),
    useEnglish = false
)

// 查看目录内容
catalog.forEach { entry ->
    println("工具: ${entry.displayName}")
    println("目标: ${entry.targetToolName}")
    println("来源: ${entry.sourceKind.label(false)}")
    println("描述: ${entry.description}")
    println("参数: ${entry.parameterHints.joinToString(", ")}")
    println("---")
}
```

### 3.4 搜索隐藏工具

```kotlin
// 搜索工具目录
val results = CliToolModeSupport.searchHiddenToolCatalog(
    catalog = catalog,
    query = "文件读取",
    limit = 8
)

// 格式化搜索结果
val formatted = CliToolModeSupport.formatSearchResults(
    query = "文件读取",
    results = results,
    useEnglish = false
)
println(formatted)
```

**搜索结果示例**：

```
"文件读取"的隐藏工具搜索结果：
1. `read_file` [内置]
   读取文件内容
   目标工具：`read_file`
   参数：file_path [string, required]: 要读取的文件路径
2. `file_manager:read` [包]
   文件管理器读取功能
   目标工具：`file_manager:read`
   参数：path [string, required]: 文件路径
```

### 3.5 检查工具可见性

```kotlin
// 检查是否是公开工具
val isPublic = CliToolModeSupport.isCliPublicTool("search")     // true
val isPublic2 = CliToolModeSupport.isCliPublicTool("read_file") // false

// 检查是否是保留的代理目标
val isReserved = CliToolModeSupport.isReservedProxyTarget("search")        // true
val isReserved2 = CliToolModeSupport.isReservedProxyTarget("proxy")        // true
val isReserved3 = CliToolModeSupport.isReservedProxyTarget("read_file")    // false
```

### 3.6 构建错误消息

```kotlin
// 工具被隐藏时的错误消息
val errorMsg = CliToolModeSupport.buildCliTopLevelRestrictionErrorMessage(
    attemptedToolName = "read_file",
    useEnglish = false
)
// 输出: 工具"read_file"在 CLI 工具模式下是隐藏的。请先用 `search` 查找隐藏目标工具，再调用 `proxy`。

// 代理目标不可用
val unavailableMsg = CliToolModeSupport.buildProxyTargetUnavailableMessage(
    targetToolName = "unknown_tool",
    useEnglish = false
)
// 输出: 隐藏目标工具"unknown_tool"不可用。请先用 `search` 发现有效的隐藏工具名和参数。

// 保留目标错误
val reservedMsg = CliToolModeSupport.buildReservedProxyTargetMessage(
    targetToolName = "search",
    useEnglish = false
)
// 输出: 隐藏目标工具"search"是保留目标，不能通过 proxy 调用。

// 角色卡权限拒绝
val accessDeniedMsg = CliToolModeSupport.buildRoleAccessDeniedMessage(useEnglish = false)
// 输出: 当前角色卡无权访问这个隐藏工具。
```

### 3.7 角色卡权限检查

```kotlin
// 检查工具是否允许当前角色卡访问
val allowed = CliToolModeSupport.isToolNameAllowedForRoleCard(
    toolName = "read_file",
    usePackageSourceName = null,
    roleCardToolAccess = roleCardAccess
)

// 检查包工具
val packageAllowed = CliToolModeSupport.isToolNameAllowedForRoleCard(
    toolName = "my_package:custom_tool",
    usePackageSourceName = "my_package",
    roleCardToolAccess = roleCardAccess
)
```

---

## 四、隐藏工具目录数据结构

### 4.1 HiddenToolCatalogEntry

```kotlin
data class HiddenToolCatalogEntry(
    val targetToolName: String,        // 目标工具名（proxy 转发时使用）
    val displayName: String,           // 显示名称
    val description: String,           // 工具描述
    val parameterHints: List<String>,  // 参数提示列表
    val sourceKind: HiddenToolSourceKind,  // 来源类型
    val keywords: List<String> = emptyList(),  // 搜索关键词
    val suggestedParamsJson: String? = null   // 建议参数 JSON
)
```

### 4.2 HiddenToolSourceKind

| 类型 | 标签（中文） | 标签（英文） | 说明 |
|------|------------|------------|------|
| `BUILTIN` | 内置 | built-in | 系统内置工具 |
| `INTERNAL` | 内部 | internal | 应用内部工具 |
| `PACKAGE` | 包 | package | 传统 JS 包和 ToolPkg 工具 |
| `MCP` | MCP | mcp | MCP 服务器工具 |
| `ACTIVATION` | 激活 | activation | 需要激活的条目 |

### 4.3 目录构建流程

```
buildHiddenToolCatalog()
    │
    ├── 1. 收集内置和内部工具
    │      - 从 SystemToolPrompts 获取所有工具
    │      - 过滤掉 use_package、保留目标和公开工具
    │      - 根据角色卡权限过滤
    │      - 标记为 BUILTIN 或 INTERNAL
    │
    ├── 2. 收集已启用包的工具
    │      - 获取所有已启用的非 ToolPkg 包
    │      - 检查角色卡是否允许访问
    │      - 有工具 → 添加 PACKAGE 条目
    │      - 无工具 → 添加 ACTIVATION 条目
    │
    ├── 3. 收集技能包
    │      - 获取 AI 可见的技能包
    │      - 检查角色卡权限
    │      - 添加 ACTIVATION 条目（通过 use_package 激活）
    │
    └── 4. 收集 MCP 服务器工具
           - 获取可用的 MCP 服务器
           - 检查角色卡权限
           - 有缓存工具 → 添加 MCP 条目
           - 无缓存工具 → 添加 ACTIVATION 条目
```

---

## 五、AI 交互流程

### 5.1 标准 CLI 模式交互

```
用户: "帮我读取 /sdcard/test.txt 文件"

AI:
1. 发现需要 read_file 工具
2. 但 read_file 是隐藏工具，不能直接调用
3. 调用 search 工具搜索 "read_file" 或 "文件读取"

→ search(query="read file", limit=8)

系统返回:
"read_file"的隐藏工具搜索结果：
1. `read_file` [内置]
   读取文件内容
   目标工具：`read_file`
   参数：file_path [string, required]: 要读取的文件路径

AI:
4. 发现目标工具名是 "read_file"
5. 参数是 file_path
6. 调用 proxy 工具执行

→ proxy(tool_name="read_file", params={"file_path": "/sdcard/test.txt"})

系统执行 read_file 工具并返回结果

AI:
7. 将结果展示给用户
```

### 5.2 包工具交互

```
用户: "使用 my_package 的 calculate 工具"

AI:
1. 调用 search 搜索 "my_package calculate"

→ search(query="my_package calculate", limit=8)

系统返回:
"my_package calculate"的隐藏工具搜索结果：
1. `my_package:calculate` [包]
   计算工具
   目标工具：`my_package:calculate`
   参数：expression [string, required]: 计算表达式

AI:
2. 发现目标工具名是 "my_package:calculate"
3. 调用 proxy 执行

→ proxy(tool_name="my_package:calculate", params={"expression": "2+3"})
```

---

## 六、与系统其他模块的集成

### 6.1 与 PackageManager 集成

```kotlin
// 获取已启用包
val enabledPackages = packageManager.getEnabledPackageNames()
    .filter { !packageManager.isToolPkgContainer(it) }

// 获取包工具
val toolPackage = packageManager.getEffectivePackageTools(packageName)
```

### 6.2 与 SkillRepository 集成

```kotlin
// 获取 AI 可见的技能包
val skillPackages = SkillRepository.getInstance(context)
    .getAiVisibleSkillPackages()
```

### 6.3 与 MCPLocalServer 集成

```kotlin
// 获取 MCP 服务器缓存的工具
val cachedTools = mcpLocalServer.getCachedTools(serverName)
```

### 6.4 与 SystemToolPrompts 集成

```kotlin
// 获取系统工具提示词分类
val categories = SystemToolPrompts.getAllCategoriesCn()
```

---

## 七、最佳实践

### 7.1 目录构建优化

1. **缓存目录**：避免每次请求都重新构建目录
2. **增量更新**：包启用/禁用时只更新相关条目
3. **异步构建**：使用协程在后台构建目录

### 7.2 搜索优化

1. **关键词优化**：在 SKILL.md 和包描述中使用 AI 可能搜索的关键词
2. **参数描述**：清晰的参数描述帮助 AI 理解如何使用工具
3. **示例参数**：提供 `suggestedParamsJson` 帮助 AI 构造正确的参数

### 7.3 错误处理

1. **友好提示**：当 AI 尝试直接调用隐藏工具时，返回清晰的错误消息
2. **搜索引导**：错误消息中始终引导 AI 使用 `search` 工具
3. **权限说明**：当角色卡限制时，说明限制原因

---

## 八、总结

Climode 模块是 Operit AI 为本地模型设计的创新工具使用方案，通过 **"搜索发现 + 代理执行"** 的模式，让不支持函数调用的模型也能使用丰富的工具生态。其设计特点包括：

1. **双模式适配**：自动根据 Provider 类型选择 FULL 或 CLI 模式
2. **隐藏工具目录**：统一管理内置、包、MCP、技能等多种来源的工具
3. **智能搜索评分**：多维度评分算法帮助 AI 快速找到所需工具
4. **角色卡权限**：基于角色卡的细粒度工具访问控制
5. **统一代理入口**：`proxy` 工具作为所有隐藏工具的统一执行网关

通过本模块，Operit AI 实现了对本地模型的完整工具支持，扩展了本地模型的应用能力边界。
