# DefaultTool 模块设计思想与详细使用指南

## 一、模块概述

`defaultTool` 模块是 Operit AI 的**内置工具实现集合**，提供了 AI 助手与 Android 系统交互的核心能力。该模块采用**五级权限分层架构**，根据设备的权限级别（Standard/Accessibility/Debugger/Admin/Root）提供不同能力的工具实现，同时通过 `ToolGetter` 统一入口进行权限适配和工具分发。

### 1.1 核心定位

- **系统交互网关**：提供文件系统、Shell、UI 自动化、系统操作等核心能力
- **权限分层实现**：同一工具在不同权限级别下有不同的实现和能力范围
- **AI 工具执行器**：将 AI 的工具调用请求转换为实际的系统操作
- **Web 会话管理**：完整的浏览器自动化和网页交互能力
- **工作流引擎**：支持可视化工作流的创建、管理和执行

### 1.2 模块结构

```
defaultTool/
├── accessbility/          # Accessibility 权限级别工具实现
│   ├── AccessibilityDeviceInfoToolExecutor.kt
│   ├── AccessibilityFileSystemTools.kt
│   ├── AccessibilitySystemOperationTools.kt
│   └── AccessibilityUITools.kt
├── admin/                 # Admin 权限级别工具实现
│   ├── AdminDeviceInfoToolExecutor.kt
│   ├── AdminFileSystemTools.kt
│   ├── AdminSystemOperationTools.kt
│   └── AdminUITools.kt
├── debugger/              # Debugger 权限级别工具实现
│   ├── DebuggerDeviceInfoToolExecutor.kt
│   ├── DebuggerFileSystemTools.kt
│   ├── DebuggerSystemOperationTools.kt
│   └── DebuggerUITools.kt
├── root/                  # Root 权限级别工具实现
│   ├── RootDeviceInfoToolExecutor.kt
│   ├── RootFileSystemTools.kt
│   ├── RootSystemOperationTools.kt
│   └── RootUITools.kt
├── standard/              # Standard 权限级别工具实现（基础能力）
│   ├── LinuxFileSystemTools.kt
│   ├── MemoryQueryToolExecutor.kt
│   ├── SafFileSystemTools.kt
│   ├── StandardBrowserSessionTools.kt      # Web 会话/浏览器自动化
│   ├── StandardCalculator.kt               # 计算器工具
│   ├── StandardChatManagerTool.kt          # 对话管理工具
│   ├── StandardDeviceInfoToolExecutor.kt   # 设备信息工具
│   ├── StandardFFmpegTool.kt               # FFmpeg 音视频工具
│   ├── StandardFileSystemTools.kt          # 文件系统工具
│   ├── StandardHttpTools.kt                # HTTP 网络工具
│   ├── StandardIntentToolExecutor.kt       # Intent 执行工具
│   ├── StandardSendBroadcastToolExecutor.kt # 广播发送工具
│   ├── StandardShellToolExecutor.kt        # Shell 命令工具
│   ├── StandardSoftwareSettingsModifyTools.kt # 软件设置修改
│   ├── StandardSystemOperationTools.kt     # 系统操作工具
│   ├── StandardTerminalCommandExecutor.kt  # 终端命令工具
│   ├── StandardUITools.kt                  # UI 自动化工具
│   ├── StandardWebVisitTool.kt             # Web 访问工具
│   └── StandardWorkflowTools.kt            # 工作流管理工具
├── websession/            # Web 会话子模块
│   ├── browser/           # 浏览器核心实现
│   └── userscript/        # 用户脚本管理
├── PathValidator.kt       # 路径验证工具
└── ToolGetter.kt          # 工具获取器（权限适配入口）
```

---

## 二、核心设计思想

### 2.1 五级权限分层架构

DefaultTool 模块根据 Android 系统的五种权限级别，提供对应能力的工具实现：

```
┌─────────────────────────────────────────────────────────────────┐
│                    AndroidPermissionLevel                        │
├─────────────────────────────────────────────────────────────────┤
│  ROOT                                                            │
│  - 最高权限，可执行任意 Shell 命令                                 │
│  - 可访问系统分区、修改系统文件                                    │
│  - 完整的 UI 自动化能力（输入事件注入）                            │
├─────────────────────────────────────────────────────────────────┤
│  ADMIN                                                           │
│  - 设备管理员权限                                                 │
│  - 可执行部分系统级操作                                           │
│  - 增强的文件系统访问能力                                         │
├─────────────────────────────────────────────────────────────────┤
│  DEBUGGER                                                        │
│  - ADB 调试权限                                                  │
│  - 通过 ADB 执行 Shell 命令                                      │
│  - 访问调试接口                                                   │
├─────────────────────────────────────────────────────────────────┤
│  ACCESSIBILITY                                                   │
│  - 无障碍服务权限                                                 │
│  - UI 元素遍历和操作                                              │
│  - 模拟点击、滑动等操作                                           │
├─────────────────────────────────────────────────────────────────┤
│  STANDARD (默认)                                                  │
│  - 普通应用权限                                                   │
│  - 基础文件操作（SAF/应用私有目录）                                │
│  - 基础 Shell（通过 Shizuku）                                     │
│  - 有限的 UI 操作（截图、MediaProjection）                         │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 ToolGetter 权限适配模式

`ToolGetter` 是工具获取的统一入口，根据当前首选权限级别返回对应实现：

```kotlin
object ToolGetter {
    fun getFileSystemTools(context: Context): StandardFileSystemTools {
        return when (androidPermissionPreferences.getPreferredPermissionLevel()) {
            AndroidPermissionLevel.ROOT -> RootFileSystemTools(context)
            AndroidPermissionLevel.ADMIN -> AdminFileSystemTools(context)
            AndroidPermissionLevel.DEBUGGER -> DebuggerFileSystemTools(context)
            AndroidPermissionLevel.ACCESSIBILITY -> AccessibilityFileSystemTools(context)
            AndroidPermissionLevel.STANDARD -> StandardFileSystemTools(context)
            null -> StandardFileSystemTools(context)
        }
    }
    // ... 其他工具类似
}
```

**设计优势**：
- **统一接口**：调用方无需关心底层权限级别
- **自动降级**：高权限实现可继承标准实现，只重写增强方法
- **运行时切换**：用户可随时切换权限级别，工具行为自动适配

### 2.3 工具分类体系

| 工具类别 | 标准权限 | 增强权限 | 说明 |
|---------|---------|---------|------|
| **文件系统** | `StandardFileSystemTools` | `RootFileSystemTools` | 文件读写、目录操作 |
| **Shell 执行** | `StandardShellToolExecutor` | 同标准 | 通过 Shizuku 执行 ADB Shell |
| **UI 自动化** | `StandardUITools` | `AccessibilityUITools` | 截图、点击、输入等 |
| **系统操作** | `StandardSystemOperationTools` | `RootSystemOperationTools` | 应用管理、设置修改 |
| **设备信息** | `StandardDeviceInfoToolExecutor` | `RootDeviceInfoToolExecutor` | 硬件/软件信息获取 |

### 2.4 浏览器会话架构

`StandardBrowserSessionTools` 实现了完整的浏览器自动化能力：

```
BrowserSessionTools
    │
    ├── WebSession 管理
    │       ├── createSessionTab()     # 创建新标签页
    │       ├── closeSession()         # 关闭标签页
    │       ├── activateSession()      # 切换活动标签页
    │       └── sessions: ConcurrentHashMap<String, WebSession>
    │
    ├── 页面操作
    │       ├── browserNavigate()      # 页面导航
    │       ├── browserClick()         # 点击元素
    │       ├── browserType()          # 输入文本
    │       ├── browserSnapshot()      # 页面快照（YAML 格式）
    │       └── browserTakeScreenshot() # 页面截图
    │
    ├── JavaScript 执行
    │       ├── browserEvaluate()      # 执行 JS 函数
    │       ├── browserRunCode()       # 运行 Playwright-like 代码
    │       └── evaluateJavascriptAsync() # 异步 JS 求值
    │
    └── 高级功能
            ├── browserFileUpload()    # 文件上传
            ├── browserHandleDialog()  # 对话框处理
            ├── browserConsoleMessages() # 控制台消息
            ├── browserNetworkRequests() # 网络请求监控
            └── userscriptManager      # 用户脚本管理
```

### 2.5 工作流引擎架构

`StandardWorkflowTools` 提供可视化工作流的管理和执行：

```
Workflow
    │
    ├── 节点类型 (WorkflowNode)
    │       ├── TriggerNode     # 触发节点（手动/定时/事件）
    │       ├── ExecuteNode     # 执行节点（动作/JS 代码）
    │       ├── ConditionNode   # 条件节点（比较运算）
    │       ├── LogicNode       # 逻辑节点（AND/OR）
    │       └── ExtractNode     # 提取节点（正则/JSON/随机）
    │
    ├── 连接 (WorkflowNodeConnection)
    │       └── sourceNodeId → targetNodeId (带条件)
    │
    └── CRUD 操作
            ├── createWorkflow()   # 创建工作流
            ├── getWorkflow()      # 获取详情
            ├── updateWorkflow()   # 全量更新
            ├── patchWorkflow()    # 增量更新（add/update/remove）
            ├── deleteWorkflow()   # 删除
            └── triggerWorkflow()  # 触发执行
```

---

## 三、详细使用方法

### 3.1 获取工具实例

```kotlin
import com.ai.assistance.operit.core.tools.defaultTool.ToolGetter

// 文件系统工具（自动适配权限级别）
val fileSystemTools = ToolGetter.getFileSystemTools(context)

// Shell 工具执行器
val shellExecutor = ToolGetter.getShellToolExecutor(context)

// UI 工具
val uiTools = ToolGetter.getUITools(context)

// 系统操作工具
val systemOps = ToolGetter.getSystemOperationTools(context)

// 设备信息工具
val deviceInfo = ToolGetter.getDeviceInfoToolExecutor(context)

// HTTP 工具
val httpTools = ToolGetter.getHttpTools(context)

// 浏览器会话工具
val browserTools = ToolGetter.getBrowserSessionTools(context)

// 工作流工具
val workflowTools = ToolGetter.getWorkflowTools(context)

// 计算器
val calculator = ToolGetter.getCalculator()

// 对话管理工具
val chatManager = ToolGetter.getChatManagerTool(context)
```

### 3.2 文件系统操作

```kotlin
val fileTools = ToolGetter.getFileSystemTools(context)

// 读取文件
val readTool = AITool(
    name = "read_file",
    parameters = listOf(
        AITool.Parameter("file_path", "/sdcard/test.txt")
    )
)
val result = fileTools.readFile(readTool)

// 写入文件
val writeTool = AITool(
    name = "write_file",
    parameters = listOf(
        AITool.Parameter("file_path", "/sdcard/output.txt"),
        AITool.Parameter("content", "Hello, World!")
    )
)
val result = fileTools.writeFile(writeTool)

// 列出目录
val listTool = AITool(
    name = "list_directory",
    parameters = listOf(
        AITool.Parameter("path", "/sdcard/Download")
    )
)
val result = fileTools.listDirectory(listTool)
```

### 3.3 Shell 命令执行

```kotlin
val shellExecutor = ToolGetter.getShellToolExecutor(context)

// 执行 Shell 命令
val shellTool = AITool(
    name = "shell",
    parameters = listOf(
        AITool.Parameter("command", "ls -la /sdcard")
    )
)
val result = shellExecutor.invoke(shellTool)

// 验证参数
val validation = shellExecutor.validateParameters(shellTool)
if (!validation.valid) {
    println("参数错误: ${validation.errorMessage}")
}
```

**安全限制**：
- 禁止执行 `rm -rf` 等危险命令
- 禁止执行 `format` 命令
- 命令为空时返回错误

### 3.4 系统操作

```kotlin
val systemOps = ToolGetter.getSystemOperationTools(context)

// 显示 Toast
val toastTool = AITool(
    name = "toast",
    parameters = listOf(
        AITool.Parameter("message", "Hello from AI!")
    )
)
val result = systemOps.toast(toastTool)

// 发送通知
val notificationTool = AITool(
    name = "send_notification",
    parameters = listOf(
        AITool.Parameter("title", "AI 通知"),
        AITool.Parameter("message", "任务已完成")
    )
)
val result = systemOps.sendNotification(notificationTool)

// 修改系统设置
val settingTool = AITool(
    name = "modify_system_setting",
    parameters = listOf(
        AITool.Parameter("setting", "screen_brightness"),
        AITool.Parameter("value", "128"),
        AITool.Parameter("namespace", "system")
    )
)
val result = systemOps.modifySystemSetting(settingTool)

// 获取系统设置
val getSettingTool = AITool(
    name = "get_system_setting",
    parameters = listOf(
        AITool.Parameter("setting", "screen_brightness"),
        AITool.Parameter("namespace", "system")
    )
)
val result = systemOps.getSystemSetting(getSettingTool)

// 安装应用
val installTool = AITool(
    name = "install_app",
    parameters = listOf(
        AITool.Parameter("path", "/sdcard/app.apk")
    )
)
val result = systemOps.installApp(installTool)

// 卸载应用
val uninstallTool = AITool(
    name = "uninstall_app",
    parameters = listOf(
        AITool.Parameter("package_name", "com.example.app")
    )
)
val result = systemOps.uninstallApp(uninstallTool)

// 启动应用
val startTool = AITool(
    name = "start_app",
    parameters = listOf(
        AITool.Parameter("package_name", "com.tencent.mm"),
        AITool.Parameter("activity", "") // 可选，指定 Activity
    )
)
val result = systemOps.startApp(startTool)

// 获取应用列表
val listAppsTool = AITool(
    name = "list_installed_apps",
    parameters = listOf(
        AITool.Parameter("include_system_apps", "false")
    )
)
val result = systemOps.listInstalledApps(listAppsTool)

// 获取应用使用时长
val usageTool = AITool(
    name = "get_app_usage_time",
    parameters = listOf(
        AITool.Parameter("since_hours", "24"),
        AITool.Parameter("limit", "10"),
        AITool.Parameter("include_system_apps", "false")
    )
)
val result = systemOps.getAppUsageTime(usageTool)

// 获取位置信息
val locationTool = AITool(
    name = "get_device_location",
    parameters = listOf(
        AITool.Parameter("timeout", "10"),
        AITool.Parameter("high_accuracy", "true"),
        AITool.Parameter("include_address", "true")
    )
)
val result = systemOps.getDeviceLocation(locationTool)
```

### 3.5 设备信息获取

```kotlin
val deviceInfo = ToolGetter.getDeviceInfoToolExecutor(context)

// 获取完整设备信息
val infoTool = AITool(name = "get_device_info", parameters = emptyList())
val result = deviceInfo.invoke(infoTool)

// 返回信息包括：
// - 设备型号、制造商
// - Android 版本、SDK 版本
// - 屏幕分辨率、密度
// - 内存信息（总/可用）
// - 存储信息（总/可用）
// - 电池电量、充电状态
// - CPU 架构
// - 网络类型
```

### 3.6 HTTP 网络请求

```kotlin
val httpTools = ToolGetter.getHttpTools(context)

// 发送 HTTP 请求
val requestTool = AITool(
    name = "http_request",
    parameters = listOf(
        AITool.Parameter("url", "https://api.example.com/data"),
        AITool.Parameter("method", "GET"),
        AITool.Parameter("headers", "{}"),
        AITool.Parameter("body", ""),
        AITool.Parameter("body_type", "json"),
        AITool.Parameter("connect_timeout", "15"),
        AITool.Parameter("read_timeout", "20"),
        AITool.Parameter("use_cookies", "true")
    )
)
val result = httpTools.httpRequest(requestTool)

// 流式 HTTP 请求
val streamTool = AITool(
    name = "http_request_stream",
    parameters = listOf(
        AITool.Parameter("url", "https://api.example.com/stream")
    )
)
val flow = httpTools.httpRequestStream(streamTool)
flow.collect { chunkResult ->
    println(chunkResult.result)
}

// 多部分表单请求（文件上传）
val multipartTool = AITool(
    name = "multipart_request",
    parameters = listOf(
        AITool.Parameter("url", "https://api.example.com/upload"),
        AITool.Parameter("method", "POST"),
        AITool.Parameter("form_data", """{"key": "value"}"""),
        AITool.Parameter("files", """[{"field_name": "file", "file_path": "/sdcard/test.jpg"}]""")
    )
)
val result = httpTools.multipartRequest(multipartTool)

// Cookie 管理
val cookieTool = AITool(
    name = "manage_cookies",
    parameters = listOf(
        AITool.Parameter("action", "get"),  // get/set/clear
        AITool.Parameter("domain", "example.com"),
        AITool.Parameter("cookies", "{}")
    )
)
val result = httpTools.manageCookies(cookieTool)
```

### 3.7 Intent 执行

```kotlin
val intentExecutor = ToolGetter.getIntentToolExecutor(context)

// 启动 Activity
val intentTool = AITool(
    name = "intent",
    parameters = listOf(
        AITool.Parameter("action", "android.intent.action.VIEW"),
        AITool.Parameter("uri", "https://www.example.com"),
        AITool.Parameter("type", "activity")
    )
)
val result = intentExecutor.invoke(intentTool)

// 发送广播
val broadcastTool = AITool(
    name = "intent",
    parameters = listOf(
        AITool.Parameter("action", "com.example.ACTION_CUSTOM"),
        AITool.Parameter("type", "broadcast")
    )
)
val result = intentExecutor.invoke(broadcastTool)

// 启动 Service
val serviceTool = AITool(
    name = "intent",
    parameters = listOf(
        AITool.Parameter("component", "com.example/.MyService"),
        AITool.Parameter("type", "service")
    )
)
val result = intentExecutor.invoke(serviceTool)
```

### 3.8 浏览器会话操作

```kotlin
val browserTools = ToolGetter.getBrowserSessionTools(context)

// 导航到页面
val navigateTool = AITool(
    name = "browser_navigate",
    parameters = listOf(
        AITool.Parameter("url", "https://www.example.com")
    )
)
val result = browserTools.invoke(navigateTool)

// 点击元素
val clickTool = AITool(
    name = "browser_click",
    parameters = listOf(
        AITool.Parameter("ref", "element_ref_123"),  // 从快照获取的 ref
        AITool.Parameter("button", "left"),
        AITool.Parameter("doubleClick", "false")
    )
)
val result = browserTools.invoke(clickTool)

// 输入文本
val typeTool = AITool(
    name = "browser_type",
    parameters = listOf(
        AITool.Parameter("ref", "element_ref_456"),
        AITool.Parameter("text", "Hello World"),
        AITool.Parameter("submit", "true"),
        AITool.Parameter("slowly", "false")
    )
)
val result = browserTools.invoke(typeTool)

// 获取页面快照
val snapshotTool = AITool(
    name = "browser_snapshot",
    parameters = listOf(
        AITool.Parameter("selector", ""),  // 可选，限定范围
        AITool.Parameter("depth", "3")     // 可选，遍历深度
    )
)
val result = browserTools.invoke(snapshotTool)

// 执行 JavaScript
val evalTool = AITool(
    name = "browser_evaluate",
    parameters = listOf(
        AITool.Parameter("function", "document.title"),
        AITool.Parameter("ref", "")  // 可选，在指定元素上执行
    )
)
val result = browserTools.invoke(evalTool)

// 截图
val screenshotTool = AITool(
    name = "browser_take_screenshot",
    parameters = listOf(
        AITool.Parameter("type", "png"),
        AITool.Parameter("fullPage", "false"),
        AITool.Parameter("filename", "screenshot")
    )
)
val result = browserTools.invoke(screenshotTool)

// 管理标签页
val tabsTool = AITool(
    name = "browser_tabs",
    parameters = listOf(
        AITool.Parameter("action", "list")  // list/create/select/close
    )
)
val result = browserTools.invoke(tabsTool)

// 等待条件
val waitTool = AITool(
    name = "browser_wait_for",
    parameters = listOf(
        AITool.Parameter("time", "5"),        // 等待秒数
        AITool.Parameter("text", "Loaded"),   // 等待文本出现
        AITool.Parameter("textGone", "Loading") // 等待文本消失
    )
)
val result = browserTools.invoke(waitTool)
```

### 3.9 工作流管理

```kotlin
val workflowTools = ToolGetter.getWorkflowTools(context)

// 获取所有工作流
val listTool = AITool(name = "get_all_workflows", parameters = emptyList())
val result = workflowTools.getAllWorkflows(listTool)

// 创建工作流
val createTool = AITool(
    name = "create_workflow",
    parameters = listOf(
        AITool.Parameter("name", "My Workflow"),
        AITool.Parameter("description", "A sample workflow"),
        AITool.Parameter("nodes", """[
            {"type": "trigger", "triggerType": "manual", "name": "Start"},
            {"type": "execute", "actionType": "shell", "name": "Run Command"}
        ]"""),
        AITool.Parameter("connections", """[
            {"sourceNodeId": "node1", "targetNodeId": "node2"}
        ]"""),
        AITool.Parameter("enabled", "true")
    )
)
val result = workflowTools.createWorkflow(createTool)

// 获取工作流详情
val getTool = AITool(
    name = "get_workflow",
    parameters = listOf(
        AITool.Parameter("workflow_id", "workflow_uuid")
    )
)
val result = workflowTools.getWorkflow(getTool)

// 更新工作流
val updateTool = AITool(
    name = "update_workflow",
    parameters = listOf(
        AITool.Parameter("workflow_id", "workflow_uuid"),
        AITool.Parameter("name", "Updated Name"),
        AITool.Parameter("enabled", "true")
    )
)
val result = workflowTools.updateWorkflow(updateTool)

// 增量更新（Patch）
val patchTool = AITool(
    name = "patch_workflow",
    parameters = listOf(
        AITool.Parameter("workflow_id", "workflow_uuid"),
        AITool.Parameter("node_patches", """[
            {"op": "add", "node": {"type": "execute", "name": "New Action"}}
        ]"""),
        AITool.Parameter("connection_patches", """[
            {"op": "add", "connection": {"sourceNodeId": "node1", "targetNodeId": "node3"}}
        ]""")
    )
)
val result = workflowTools.patchWorkflow(patchTool)

// 删除工作流
val deleteTool = AITool(
    name = "delete_workflow",
    parameters = listOf(
        AITool.Parameter("workflow_id", "workflow_uuid")
    )
)
val result = workflowTools.deleteWorkflow(deleteTool)

// 触发工作流
val triggerTool = AITool(
    name = "trigger_workflow",
    parameters = listOf(
        AITool.Parameter("workflow_id", "workflow_uuid")
    )
)
val result = workflowTools.triggerWorkflow(triggerTool)
```

### 3.10 计算器使用

```kotlin
val calculator = ToolGetter.getCalculator()

// 计算表达式
val result = StandardCalculator.evalExpression("2 + 3 * 4")  // 14.0

// 获取结构化结果
val resultData = StandardCalculator.calculateExpression("sin(pi/2)")
println(resultData.result)           // 1.0
println(resultData.formattedResult)  // "1"
println(resultData.variables)        // {ans=1.0, pi=3.14..., e=2.71...}

// 变量操作
StandardCalculator.setVariable("x", 10.0)
val result = StandardCalculator.evalExpression("x * 2")  // 20.0
val x = StandardCalculator.getVariable("x")  // 10.0

// 清除变量
StandardCalculator.clearVariables()

// 获取支持的函数
val units = StandardCalculator.getSupportedUnits()
val dateFunctions = StandardCalculator.getSupportedDateFunctions()
val statFunctions = StandardCalculator.getSupportedStatFunctions()
val jsFeatures = StandardCalculator.getSupportedJsFeatures()
```

### 3.11 路径验证

```kotlin
import com.ai.assistance.operit.core.tools.defaultTool.PathValidator

// 验证 Android 路径
val error = PathValidator.validateAndroidPath("/sdcard/test.txt", "read_file")
if (error != null) {
    println("路径无效: ${error.error}")
}

// 验证 Linux 路径
val error = PathValidator.validateLinuxPath("/home/user/file.txt", "read_file")
if (error != null) {
    println("路径无效: ${error.error}")
}
```

---

## 四、权限级别详解

### 4.1 各权限级别能力对比

| 能力 | STANDARD | ACCESSIBILITY | DEBUGGER | ADMIN | ROOT |
|------|----------|---------------|----------|-------|------|
| 文件系统 | SAF/私有目录 | 扩展访问 | ADB 访问 | 系统分区 | 完整访问 |
| Shell 执行 | Shizuku | Shizuku | ADB | ADB | 直接执行 |
| UI 点击 | MediaProjection | 无障碍注入 | ADB 注入 | ADB 注入 | 直接注入 |
| 应用管理 | 标准 API | 标准 API | 扩展 | 系统级 | 系统级 |
| 系统设置 | 需授权 | 需授权 | 部分 | 大部分 | 全部 |

### 4.2 权限切换

```kotlin
// 在设置中切换权限级别
androidPermissionPreferences.setPreferredPermissionLevel(AndroidPermissionLevel.ROOT)

// 获取当前权限级别
val currentLevel = androidPermissionPreferences.getPreferredPermissionLevel()
```

---

## 五、Web 会话工具详解

### 5.1 支持的浏览器操作

| 工具名 | 说明 | 关键参数 |
|--------|------|---------|
| `browser_navigate` | 页面导航 | `url`, `headers` |
| `browser_click` | 点击元素 | `ref`/`selector`, `button`, `doubleClick` |
| `browser_type` | 输入文本 | `ref`, `text`, `submit`, `slowly` |
| `browser_fill_form` | 填充表单 | `fields` (JSON 数组) |
| `browser_select_option` | 选择选项 | `ref`, `values` |
| `browser_press_key` | 按键 | `key` |
| `browser_hover` | 悬停 | `ref` |
| `browser_drag` | 拖拽 | `startRef`, `endRef` |
| `browser_snapshot` | 页面快照 | `selector`, `depth`, `filename` |
| `browser_take_screenshot` | 截图 | `type`, `fullPage`, `ref` |
| `browser_evaluate` | 执行 JS | `function`, `ref` |
| `browser_run_code` | 运行代码 | `code` |
| `browser_wait_for` | 等待条件 | `time`, `text`, `textGone` |
| `browser_tabs` | 标签页管理 | `action` (list/create/select/close) |
| `browser_console_messages` | 控制台消息 | `level` |
| `browser_network_requests` | 网络请求 | `includeStatic` |
| `browser_handle_dialog` | 处理对话框 | `accept`, `promptText` |
| `browser_file_upload` | 文件上传 | `paths` |
| `browser_resize` | 调整视口 | `width`, `height` |

### 5.2 页面快照格式

浏览器快照以 YAML 格式返回，包含：
- 页面标题和 URL
- 可交互元素列表（带 ref 标识）
- 元素属性（类型、文本、位置等）
- 表单元素和状态

---

## 六、工作流节点类型

### 6.1 节点类型说明

| 类型 | 说明 | 关键配置 |
|------|------|---------|
| `trigger` | 触发节点 | `triggerType`: manual/scheduled/event |
| `execute` | 执行节点 | `actionType`: shell/http/intent/js 等 |
| `condition` | 条件节点 | `left`, `operator`, `right` |
| `logic` | 逻辑节点 | `operator`: AND/OR |
| `extract` | 提取节点 | `mode`: regex/json/random/fixed 等 |

### 6.2 连接条件

连接可以带条件表达式：
```json
{
  "sourceNodeId": "node1",
  "targetNodeId": "node2",
  "condition": "result == 'success'"
}
```

---

## 七、最佳实践

### 7.1 工具调用建议

1. **优先使用标准权限**：除非必要，避免要求过高权限
2. **验证参数**：调用前使用 `validateParameters()` 验证
3. **处理错误**：始终检查 `ToolResult.success` 字段
4. **路径验证**：使用 `PathValidator` 验证路径格式

### 7.2 浏览器自动化建议

1. **先快照后操作**：先获取页面快照，再根据 ref 操作
2. **使用 ref 而非 selector**：ref 更稳定，不受页面结构变化影响
3. **等待页面加载**：操作后使用 `browser_wait_for` 等待加载完成
4. **管理标签页**：及时关闭不需要的标签页

### 7.3 工作流设计建议

1. **模块化设计**：将复杂逻辑拆分为多个工作流
2. **错误处理**：使用条件节点处理错误分支
3. **版本控制**：使用 `patchWorkflow` 进行增量更新
4. **测试验证**：触发执行后检查执行状态

---

## 八、错误处理

### 8.1 常见错误

| 错误场景 | 错误信息 | 解决方案 |
|---------|---------|---------|
| 权限不足 | `SecurityException` | 提升权限级别或请求用户授权 |
| 路径无效 | `Invalid path` | 使用 `PathValidator` 验证路径 |
| 文件不存在 | `File does not exist` | 检查路径是否正确 |
| 命令危险 | `Potentially dangerous command` | 避免使用危险命令 |
| 网络错误 | `Error executing HTTP request` | 检查网络连接和 URL |
| 元素未找到 | `ref_not_found` | 重新获取页面快照 |

### 8.2 调试技巧

```kotlin
// 查看详细错误信息
val result = toolExecutor.invoke(tool)
if (!result.success) {
    println("工具: ${result.toolName}")
    println("错误: ${result.error}")
}

// 查看日志
AppLogger.d("TAG", "Debug message")
AppLogger.e("TAG", "Error message", exception)
```

---

## 九、总结

DefaultTool 模块是 Operit AI 的核心工具实现层，通过五级权限分层架构提供了丰富的系统交互能力。其设计特点包括：

1. **权限分层**：Standard/Accessibility/Debugger/Admin/Root 五级权限自动适配
2. **统一入口**：`ToolGetter` 根据权限级别自动分发正确的工具实现
3. **完整浏览器**：支持 Playwright-like 的浏览器自动化操作
4. **工作流引擎**：可视化工作流的创建、管理和执行
5. **类型安全**：所有工具返回统一的 `ToolResult` 结构
6. **错误隔离**：每个工具独立执行，错误不影响其他工具

通过本模块，Operit AI 能够在各种权限环境下与 Android 系统进行深度交互，为 AI 助手提供了强大的系统操作能力。
