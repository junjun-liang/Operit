# System 模块设计思想与详细使用指南

## 一、模块概述

`system` 模块是 Operit AI 的**系统能力层**，负责提供 Android 底层的系统级操作能力，包括 Shell 命令执行、UI 操作监听、屏幕捕获、权限管理和终端管理等。该模块采用**分层权限架构**，支持从标准权限到 Root 权限的五种权限级别，为上层应用提供统一的系统操作抽象。

### 1.1 核心定位

- **Shell 执行引擎**：多权限级别的 Shell 命令执行（Standard/Accessibility/Debugger/Admin/Root）
- **UI 操作监听**：通过无障碍服务、ADB 等方式监听系统 UI 事件
- **屏幕捕获**：基于 MediaProjection API 的屏幕截图能力
- **权限管理**：Root、Shizuku、无障碍服务等权限的获取和管理
- **终端管理**：与 OperitTerminal 子应用集成的终端会话管理
- **辅助工具安装**：内置 Shizuku 和无障碍服务提供者的安装管理

### 1.2 模块结构

```
system/
├── action/                              # UI 操作监听
│   ├── ActionManager.kt                 # 操作管理器：统一管理监听器
│   ├── ActionListener.kt                # 监听接口：统一事件定义
│   ├── ActionListenerFactory.kt         # 监听器工厂：按权限级别创建
│   ├── StandardActionListener.kt        # 标准监听器：应用内事件
│   ├── AccessibilityActionListener.kt   # 无障碍监听器：系统级事件
│   ├── AdminActionListener.kt           # 管理员监听器：设备管理员
│   ├── DebuggerActionListener.kt        # 调试监听器：ADB 调试
│   └── RootActionListener.kt            # Root 监听器：Root 权限事件
├── shell/                               # Shell 命令执行
│   ├── ShellExecutor.kt                 # 执行器接口：统一命令执行
│   ├── ShellExecutorFactory.kt          # 执行器工厂：按权限级别创建
│   ├── StandardShellExecutor.kt         # 标准执行器：Runtime.exec()
│   ├── AccessibilityShellExecutor.kt    # 无障碍执行器：UI 自动化
│   ├── DebuggerShellExecutor.kt         # 调试执行器：ADB Shell
│   ├── AdminShellExecutor.kt            # 管理员执行器：设备管理员
│   └── RootShellExecutor.kt             # Root 执行器：su/libsu
├── shower/                              # Shower 桥接
│   └── OperitShowerShellRunner.kt       # Shower Shell 运行器桥接
├── Terminal.kt                          # 终端封装：OperitTerminal 集成
├── OperitTerminalManager.kt             # 终端管理：版本检查和更新
├── AndroidShellExecutor.kt              # Shell 执行门面：向后兼容封装
├── AndroidPermissionLevel.kt            # 权限级别枚举：五级权限定义
├── RootAuthorizer.kt                    # Root 授权器：Root 检测和请求
├── ShizukuAuthorizer.kt                 # Shizuku 授权器：Shizuku 权限管理
├── ShizukuInstaller.kt                  # Shizuku 安装器：内置 APK 安装
├── AccessibilityProviderInstaller.kt    # 无障碍安装器：内置服务安装
├── MediaProjectionCaptureManager.kt     # 屏幕捕获：MediaProjection 截图
├── MediaProjectionHolder.kt             # 投影持有者：全局 Token 管理
├── ScreenCaptureActivity.kt             # 截图 Activity：权限请求界面
├── ScreenCaptureService.kt              # 截图服务：前台服务保活
└── ...                                  # 其他辅助类
```

---

## 二、核心设计思想

### 2.1 五级权限分层架构

System 模块采用清晰的五级权限分层模型：

```
┌─────────────────────────────────────────────────────────────┐
│  ROOT          │ 最高权限，通过 su/libsu 执行任意系统命令      │
├─────────────────────────────────────────────────────────────┤
│  ADMIN         │ 设备管理员权限，执行受限的系统管理操作          │
├─────────────────────────────────────────────────────────────┤
│  DEBUGGER      │ ADB 调试权限，通过无线调试执行 Shell 命令       │
├─────────────────────────────────────────────────────────────┤
│  ACCESSIBILITY │ 无障碍服务权限，执行 UI 自动化操作              │
├─────────────────────────────────────────────────────────────┤
│  STANDARD      │ 标准权限，仅执行应用沙箱内的基本命令            │
└─────────────────────────────────────────────────────────────┘
```

**设计要点**：
- **权限降级**：当高权限不可用时，自动降级到低权限（可配置严格模式禁用降级）
- **身份区分**：支持 `DEFAULT`/`APP`/`SHELL`/`ROOT` 四种 Shell 执行身份
- **统一接口**：所有权限级别通过统一的 `ShellExecutor` 和 `ActionListener` 接口操作
- **工厂模式**：`ShellExecutorFactory` 和 `ActionListenerFactory` 按权限级别创建实例

### 2.2 双模式 Root 执行

RootShellExecutor 支持两种执行模式：

#### libsu 模式（默认）
- 使用 [topjohnwu/libsu](https://github.com/topjohnwu/libsu) 库
- 支持异步执行、回调列表、自动挂载 master
- 适用于 Magisk、SuperSU 等标准 Root 环境

#### exec 模式
- 使用 `Runtime.exec("su -c command")` 直接执行
- 适用于 KernelSU 等特殊 Root 环境
- 支持自定义 su 命令（如 `ksu`/`suu`）

```kotlin
// 自动检测并切换模式
val rootExecutionMode = when {
    Shell.isAppGrantedRoot() -> use libsu
    checkKernelSu() -> use exec mode
    else -> unavailable
}
```

### 2.3 工厂模式与策略模式结合

```
┌─────────────────────────────────────────┐
│         ShellExecutorFactory            │
│  getExecutor(level) → ShellExecutor     │
└─────────────────────────────────────────┘
              │
    ┌─────────┼─────────┬─────────┐
    ▼         ▼         ▼         ▼
┌───────┐ ┌───────┐ ┌───────┐ ┌───────┐
│Standard│ │Root   │ │Admin  │ │Debug  │
│Shell   │ │Shell  │ │Shell  │ │Shell  │
│Executor│ │Executor│ │Executor│ │Executor│
└───────┘ └───────┘ └───────┘ └───────┘
```

**设计优势**：
- **开闭原则**：新增权限级别只需添加新的 Executor/Listener 实现
- **单一职责**：每个 Executor 只负责一种权限级别的命令执行
- **缓存复用**：工厂缓存已创建的实例，避免重复初始化

### 2.4 MediaProjection 屏幕捕获

Android 14+ 要求 MediaProjection 必须在前台服务中运行：

```
用户请求截图
    │
    ▼
ScreenCaptureActivity 启动
    │
    ▼
请求 MediaProjection 权限（系统弹窗）
    │
    ▼
用户授权 → 启动 ScreenCaptureService（前台服务）
    │
    ▼
等待 FGS 就绪（轮询检查，最多 1.5 秒）
    │
    ▼
获取 MediaProjection 实例 → 存储到 MediaProjectionHolder
    │
    ▼
MediaProjectionCaptureManager 创建 VirtualDisplay
    │
    ▼
ImageReader 捕获帧 → Bitmap → 保存文件
```

### 2.5 终端子应用集成

Operit 将终端功能拆分为独立的子应用 `OperitTerminal`：

```
Operit (主应用)
    │
    ├── Terminal.kt (封装层)
    │       ├── createSession() → 创建终端会话
    │       ├── executeCommand() → 执行命令并等待结果
    │       ├── executeCommandFlow() → 流式执行命令
    │       └── sendInput() → 发送交互输入
    │
    └── OperitTerminalManager (管理器)
            ├── isInstalled() → 检查是否安装
            ├── getInstalledVersion() → 获取版本
            └── fetchLatestReleaseInfo() → 获取最新版本
```

---

## 三、详细使用方法

### 3.1 Shell 命令执行

#### 基础执行

```kotlin
// 初始化（Application 中）
AndroidShellExecutor.setContext(context)

// 执行简单命令（使用首选权限级别）
val result = AndroidShellExecutor.executeShellCommand("ls /sdcard")
println("Success: ${result.success}")
println("Stdout: ${result.stdout}")
println("Stderr: ${result.stderr}")
println("ExitCode: ${result.exitCode}")
```

#### 指定执行身份

```kotlin
// 以 ROOT 身份执行
val result = AndroidShellExecutor.executeShellCommand(
    command = "cat /data/data/com.example.app/shared_prefs/config.xml",
    identityOverride = ShellIdentity.ROOT
)

// 以 SHELL 身份执行（通过 operit_shell_exec 包装器）
val result = AndroidShellExecutor.executeShellCommand(
    command = "ps -A",
    identityOverride = ShellIdentity.SHELL
)
```

#### 启动持久进程

```kotlin
// 启动 logcat 进程并流式读取输出
val process = AndroidShellExecutor.startShellProcess("logcat -d")

// 读取标准输出
process.stdout.collect { line ->
    println("LOG: $line")
}

// 读取错误输出
process.stderr.collect { line ->
    println("ERR: $line")
}

// 等待进程结束
val exitCode = process.waitFor()

// 强制终止
process.destroy()
```

### 3.2 UI 操作监听

#### 启动监听

```kotlin
val actionManager = ActionManager.getInstance(context)

// 使用最高可用权限启动监听
val result = actionManager.startListeningWithHighestPermission { event ->
    when (event.actionType) {
        ActionListener.ActionType.CLICK -> {
            println("Click at (${event.coordinates?.first}, ${event.coordinates?.second})")
            println("Element: ${event.elementInfo?.text}")
        }
        ActionListener.ActionType.TEXT_INPUT -> {
            println("Text input: ${event.inputText}")
        }
        ActionListener.ActionType.SCREEN_CHANGE -> {
            println("Screen changed")
        }
        else -> println("Other action: ${event.actionType}")
    }
}

if (result.success) {
    println("Listening started: ${result.message}")
} else {
    println("Failed: ${result.message}")
}
```

#### 使用指定权限级别

```kotlin
// 使用无障碍服务权限
val result = actionManager.startListeningWithPermissionLevel(
    permissionLevel = AndroidPermissionLevel.ACCESSIBILITY,
    callback = { event -> /* 处理事件 */ }
)
```

#### 注册额外回调

```kotlin
// 注册多个回调
actionManager.registerEventCallback("logger") { event ->
    AppLogger.d("ActionLogger", "Event: $event")
}

actionManager.registerEventCallback("analytics") { event ->
    // 上报分析数据
}

// 移除回调
actionManager.unregisterEventCallback("logger")
```

#### 停止监听

```kotlin
val stopped = actionManager.stopListening()
```

### 3.3 权限管理

#### Root 权限

```kotlin
// 初始化 Root 授权器
RootAuthorizer.initialize(context)

// 检查 Root 状态
val hasRoot = RootAuthorizer.checkRootStatus(context)

// 监听 Root 状态变化
RootAuthorizer.addStateChangeListener {
    val isRooted = RootAuthorizer.isRooted.value
    val hasAccess = RootAuthorizer.hasRootAccess.value
    println("Root state changed: rooted=$isRooted, access=$hasAccess")
}

// 请求 Root 权限
RootAuthorizer.requestRootPermission { granted ->
    println("Root permission: $granted")
}

// 执行 Root 命令
val (success, output) = RootAuthorizer.executeRootCommand("reboot", context)
```

#### Shizuku 权限

```kotlin
// 初始化 Shizuku
ShizukuAuthorizer.initialize()

// 检查 Shizuku 是否安装
val installed = ShizukuAuthorizer.isShizukuInstalled(context)

// 检查 Shizuku 服务是否运行
val running = ShizukuAuthorizer.isShizukuServiceRunning()

// 检查是否有 Shizuku 权限
val hasPermission = ShizukuAuthorizer.hasShizukuPermission()

// 请求 Shizuku 权限
ShizukuAuthorizer.requestShizukuPermission { granted ->
    println("Shizuku permission: $granted")
}

// 获取启动说明
val instructions = ShizukuAuthorizer.getShizukuStartupInstructions(context)
```

#### 安装内置 Shizuku

```kotlin
// 检查是否需要更新
val needUpdate = ShizukuInstaller.isShizukuUpdateNeeded(context)

// 获取版本信息
val bundledVersion = ShizukuInstaller.getBundledShizukuVersion(context)
val installedVersion = ShizukuInstaller.getInstalledShizukuVersion(context)

// 安装或更新
val success = ShizukuInstaller.installBundledShizuku(context)
```

### 3.4 屏幕捕获

#### 请求权限并截图

```kotlin
// 启动权限请求 Activity
ScreenCaptureActivity.cleanStart(context)

// 在权限授予后（通过 MediaProjectionHolder 获取实例）
val mediaProjection = MediaProjectionHolder.mediaProjection
if (mediaProjection != null) {
    val captureManager = MediaProjectionCaptureManager(context, mediaProjection)
    captureManager.setupDisplay()
    
    // 捕获到 Bitmap
    val bitmap = captureManager.captureToBitmap()
    
    // 捕获到文件
    val file = File(context.cacheDir, "screenshot.png")
    val success = captureManager.captureToFile(file)
    
    // 释放资源
    captureManager.release()
}

// 清理资源
MediaProjectionHolder.clear(context)
```

### 3.5 终端管理

```kotlin
val terminal = Terminal.getInstance(context)

// 初始化环境
val initialized = terminal.initialize()

// 创建会话
val sessionId = terminal.createSession("My Session")

// 执行命令并等待结果
val output = terminal.executeCommand(sessionId, "ls -la")

// 流式执行命令
terminal.executeCommandFlow(sessionId, "ping -c 4 google.com")
    .collect { event ->
        if (event.isCompleted) {
            println("Command completed: ${event.outputChunk}")
        } else {
            print(event.outputChunk)
        }
    }

// 发送交互输入
terminal.sendInput(sessionId, "y\n")

// 发送中断信号 (Ctrl+C)
terminal.sendInterruptSignal(sessionId)

// 切换会话
terminal.switchToSession(sessionId)

// 关闭会话
terminal.closeSession(sessionId)

// 销毁
terminal.destroy()
```

### 3.6 检查权限状态

```kotlin
// 获取所有可用执行器状态
val executors = ShellExecutorFactory.getAvailableExecutors(context)
executors.forEach { (level, pair) ->
    val (executor, status) = pair
    println("$level: available=${executor.isAvailable()}, granted=${status.granted}, reason=${status.reason}")
}

// 获取所有可用监听器状态
val listeners = actionManager.getAvailableListenersStatus()
listeners.forEach { (level, pair) ->
    val (available, status) = pair
    println("$level: available=$available, granted=${status.granted}")
}
```

---

## 四、权限级别详解

### 4.1 STANDARD（标准权限）

- **执行方式**：`Runtime.exec()` 或 `ProcessBuilder`
- **能力范围**：应用沙箱内的命令执行
- **特殊处理**：自动检测 shell 操作符（`|`, `&&`, `>`, `<`, `;`），使用 `sh -c` 执行复杂命令
- **超时**：默认 30 秒
- **适用场景**：文件操作、基本命令执行

### 4.2 ACCESSIBILITY（无障碍服务）

- **执行方式**：UI 自动化操作（非真正 Shell）
- **能力范围**：模拟点击、滑动、输入等 UI 操作
- **依赖**：无障碍服务提供者应用（`com.ai.assistance.operit.provider`）
- **适用场景**：无法获取 Root/ADB 时的 UI 自动化替代方案

### 4.3 DEBUGGER（调试权限）

- **执行方式**：无线调试 ADB Shell
- **能力范围**：完整的 ADB Shell 权限（接近 Root，但有限制）
- **依赖**：启用无线调试并授权
- **适用场景**：开发调试、无需 Root 的系统操作

### 4.4 ADMIN（设备管理员）

- **执行方式**：设备管理员 API
- **能力范围**：设备管理操作（锁屏、擦除数据等）
- **依赖**：激活设备管理员
- **适用场景**：企业设备管理

### 4.5 ROOT（Root 权限）

- **执行方式**：`su -c command`（exec 模式）或 libsu
- **能力范围**：完整的系统 Root 权限
- **依赖**：设备已 Root 且应用已授权
- **特殊功能**：支持 `run-as` 命令提取、shell 身份执行
- **适用场景**：系统级文件操作、修改系统配置

---

## 五、Shell 身份系统

### 5.1 身份类型

| 身份 | 说明 |
|------|------|
| `DEFAULT` | 默认身份，由执行器决定 |
| `APP` | 应用自身身份（无特权） |
| `SHELL` | Shell 用户身份（通过 operit_shell_exec 包装） |
| `ROOT` | Root 超级用户身份 |

### 5.2 使用场景

```kotlin
// 以 APP 身份执行（应用自身权限）
AndroidShellExecutor.executeShellCommand("ls", ShellIdentity.APP)

// 以 SHELL 身份执行（通过包装器获取 shell 用户权限）
AndroidShellExecutor.executeShellCommand("ps -A", ShellIdentity.SHELL)

// 以 ROOT 身份执行（完整 Root 权限）
AndroidShellExecutor.executeShellCommand("cat /proc/meminfo", ShellIdentity.ROOT)
```

---

## 六、事件类型

### 6.1 UI 操作事件

```kotlin
data class ActionEvent(
    val timestamp: Long,                    // 事件时间戳
    val actionType: ActionType,             // 操作类型
    val coordinates: Pair<Int, Int>?,       // 坐标（点击/滑动）
    val elementInfo: ElementInfo?,          // UI 元素信息
    val inputText: String?,                 // 输入文本
    val additionalData: Map<String, Any>    // 额外数据
)
```

### 6.2 操作类型

| 类型 | 说明 |
|------|------|
| `CLICK` | 点击操作 |
| `LONG_CLICK` | 长按操作 |
| `SWIPE` | 滑动操作 |
| `TEXT_INPUT` | 文本输入 |
| `KEY_PRESS` | 按键按下 |
| `SCROLL` | 滚动操作 |
| `GESTURE` | 手势操作 |
| `APP_SWITCH` | 应用切换 |
| `SCREEN_CHANGE` | 屏幕变化 |
| `SYSTEM_EVENT` | 系统事件 |

### 6.3 元素信息

```kotlin
data class ElementInfo(
    val resourceId: String?,           // 资源 ID
    val className: String?,            // 类名
    val text: String?,                 // 文本内容
    val contentDescription: String?,   // 内容描述
    val bounds: String?,               // 边界范围
    val packageName: String?           // 包名
)
```

---

## 七、Shower 桥接

`OperitShowerShellRunner` 将 System 模块的 Shell 执行能力桥接到 Shower 客户端库：

```kotlin
object OperitShowerShellRunner : ShellRunner {
    override suspend fun run(command: String, identity: ShellIdentity): ShellCommandResult {
        val appIdentity = when (identity) {
            ShellIdentity.DEFAULT -> AppShellIdentity.DEFAULT
            ShellIdentity.SHELL -> AppShellIdentity.SHELL
            ShellIdentity.ROOT -> AppShellIdentity.ROOT
        }
        
        val result = AndroidShellExecutor.executeShellCommand(command, appIdentity)
        return ShellCommandResult(
            success = result.success,
            stdout = result.stdout,
            stderr = result.stderr,
            exitCode = result.exitCode
        )
    }
}
```

---

## 八、最佳实践

### 8.1 Shell 执行建议

1. **使用门面类**：优先使用 `AndroidShellExecutor` 而非直接使用具体执行器
2. **处理超时**：长时间运行的命令使用 `startShellProcess()` 获取流式输出
3. **检查权限**：执行前检查权限状态，提供友好的错误提示
4. **清理资源**：使用完 `ShellProcess` 后调用 `destroy()`

### 8.2 UI 监听建议

1. **按需启动**：仅在需要时启动监听，用完后立即停止
2. **权限引导**：权限不足时引导用户到系统设置
3. **事件过滤**：根据业务需求过滤不需要的事件类型
4. **避免重复**：启动新监听前先停止旧监听

### 8.3 屏幕捕获建议

1. **及时释放**：捕获完成后立即调用 `MediaProjectionHolder.clear()`
2. **前台服务**：Android 14+ 必须启动前台服务才能获取 MediaProjection
3. **Bitmap 回收**：手动创建的 Bitmap 使用完后调用 `recycle()`

### 8.4 Root 使用建议

1. **自动检测**：使用 `RootAuthorizer.checkRootStatus()` 自动检测 Root 环境
2. **模式选择**：KernelSU 用户手动切换到 exec 模式
3. **自定义命令**：支持自定义 su 命令适配不同 Root 方案

---

## 九、错误处理

### 9.1 常见错误

| 错误场景 | 错误信息 | 解决方案 |
|---------|---------|---------|
| Root 不可用 | `Root access not available on this device` | 检查设备是否已 Root |
| Shizuku 未运行 | `Shizuku service not running` | 启动 Shizuku 服务 |
| 无障碍未启用 | `Accessibility service is not enabled` | 引导用户启用无障碍服务 |
| 命令超时 | `Command timed out after 30 seconds` | 增加超时时间或使用流式执行 |
| MediaProjection 失败 | `FGS mediaProjection not ready` | 确保前台服务已启动 |

### 9.2 权限状态检查

```kotlin
val executor = ShellExecutorFactory.getExecutor(context, AndroidPermissionLevel.ROOT)
val status = executor.hasPermission()
if (!status.granted) {
    println("Permission denied: ${status.reason}")
    // 引导用户授权
}
```

---

## 十、总结

System 模块是 Operit AI 的系统能力基石，通过五级权限分层架构为应用提供了从标准到 Root 的完整系统操作能力。其设计特点包括：

1. **分层权限**：五级权限级别（Standard → Accessibility → Debugger → Admin → Root）
2. **统一接口**：所有权限级别通过统一的 `ShellExecutor` 和 `ActionListener` 接口操作
3. **工厂模式**：`ShellExecutorFactory` 和 `ActionListenerFactory` 按需创建实例
4. **双模式 Root**：支持 libsu 和 exec 两种 Root 执行模式
5. **Android 14 兼容**：MediaProjection 通过前台服务满足新系统要求
6. **权限桥接**：与 Shower 客户端库无缝集成

通过本模块，Operit AI 能够在不同权限环境下灵活执行系统操作，为用户提供强大的设备控制能力。
