# Operit Linux 环境运行架构设计分析

## 一、设计思想概述

Operit 项目对 Linux 环境的支持采用**"分层抽象 + 插件化工具包 + 多模式执行"**的架构设计，核心设计思想包括：

1. **本地与远程统一抽象**：通过 `FileSystemProvider` 接口统一本地 Linux 环境（Termux/Terminal）和远程 SSH 环境的文件操作
2. **权限分级执行**：通过 `ShellExecutor` 接口实现 STANDARD、ROOT、DEBUGGER、ADMIN、ACCESSIBILITY 五级权限的 Shell 命令执行
3. **SSH 远程连接插件化**：`linux_ssh` 工具包作为独立插件，提供完整的 SSH 连接、tmux 会话管理、远程文件操作能力
4. **终端集成能力复用**：基于 `terminal` 模块（独立子项目）提供本地终端会话管理，复用于本地 Linux 环境执行
5. **隐藏执行器隔离**：通过 `hiddenExec` 机制在独立终端会话中执行命令，避免污染用户交互会话

---

## 二、软件架构图

### 2.1 整体架构分层

```mermaid
graph TB
    subgraph "前端层 (Compose UI)"
        UI[ToolboxScreen.kt]
        CS[ComputerScreen.kt]
        FM[FileManagerScreen.kt]
    end

    subgraph "工具包层 (ToolPkg JS Plugin)"
        LSP[linux_ssh.ts<br/>SSH远程工具包]
        LUI[index.ui.ts<br/>SSH配置界面]
    end

    subgraph "AI 工具层 (Kotlin)"
        AIT[AIToolHandler.kt]
        LFT[LinuxFileSystemTools.kt]
        SFT[StandardFileSystemTools.kt]
        STE[StandardTerminalCommandExecutor.kt]
    end

    subgraph "系统工具层 (Kotlin)"
        TERM[Terminal.kt<br/>终端管理器]
        ASE[AndroidShellExecutor.kt]
        SEF[ShellExecutorFactory.kt]
        OTM[OperitTerminalManager.kt]
    end

    subgraph "Shell 执行层 (Kotlin)"
        SSE[StandardShellExecutor.kt]
        RSE[RootShellExecutor.kt]
        DSE[DebuggerShellExecutor.kt]
        ADE[AdminShellExecutor.kt]
        ACE[AccessibilityShellExecutor.kt]
    end

    subgraph "终端子项目 (terminal module)"
        TM[TerminalManager.kt<br/>终端会话管理]
        TSM[TerminalScreen.kt<br/>终端UI]
        FS[FileSystemProvider.kt<br/>文件系统抽象]
        SSHM[SSHFileConnectionManager.kt<br/>SSH连接管理]
        SM[SourceManager.kt<br/>源码管理]
    end

    subgraph "数据层"
        APP[Android本地文件系统]
        SSH[远程SSH Linux服务器]
        TMX[tmux会话]
    end

    UI --> LSP
    UI --> TERM
    CS --> TM
    FM --> LFT
    LSP --> AIT
    AIT --> LFT
    LFT --> SFT
    SFT --> FS
    SFT --> SSHM
    STE --> TERM
    TERM --> TM
    TERM --> ASE
    ASE --> SEF
    SEF --> SSE
    SEF --> RSE
    SEF --> DSE
    SEF --> ADE
    SEF --> ACE
    SSE --> APP
    RSE --> APP
    TM --> FS
    TM --> SSHM
    SSHM --> SSH
    SSH --> TMX
    LSP --> SSH
    LSP --> TMX
```

### 2.2 Linux 环境核心组件关系

```mermaid
classDiagram
    class Terminal {
        +getInstance(context)
        +createSession(title)
        +executeCommand(sessionId, command)
        +executeHiddenCommand(command, executorKey, timeoutMs)
        +executeCommandFlow(sessionId, command)
        +sendInput(sessionId, input)
        +sendInterruptSignal(sessionId)
    }

    class AndroidShellExecutor {
        +setContext(appContext)
        +executeShellCommand(command)
        +startShellProcess(command)
    }

    class ShellExecutorFactory {
        +getExecutor(context, permissionLevel)
        +getHighestAvailableExecutor(context)
        +getUserPreferredExecutor(context)
        +clearCache(permissionLevel)
    }

    class ShellExecutor {
        <<interface>>
        +executeCommand(command, identity)
        +getPermissionLevel()
        +isAvailable()
        +hasPermission()
        +requestPermission(onResult)
        +initialize()
        +startProcess(command)
    }

    class StandardShellExecutor {
        +executeCommand(command, identity)
        +startProcess(command)
        -executeWithShell(command)
        -containsShellOperators(command)
    }

    class RootShellExecutor {
        +executeCommand(command, identity)
        +startProcess(command)
        +setUseExecMode(useExec)
        +setExecSuCommand(command)
        -executeCommandWithExec(command)
        -ensureShellLauncherInstalled()
        -checkExecSuAvailable()
    }

    class LinuxFileSystemTools {
        +listFiles(tool)
        +readFile(tool)
        +readFileFull(tool)
        +readFileBinary(tool)
        +writeFile(tool)
        +deleteFile(tool)
        +moveFile(tool)
        +copyFile(tool)
        +makeDirectory(tool)
        +findFiles(tool)
        +fileInfo(tool)
        +grepCode(tool)
        +grepContext(tool)
    }

    class StandardFileSystemTools {
        +getLinuxFileSystem()
        -sshFileManager
        -terminalManager
    }

    class SSHFileConnectionManager {
        +getInstance(context)
        +getFileSystemProvider()
        +connect(host, port, username, password, privateKey)
        +disconnect()
        +isConnected()
    }

    class FileSystemProvider {
        <<interface>>
        +exists(path)
        +isFile(path)
        +isDirectory(path)
        +readFile(path)
        +writeFile(path, content, append)
        +listDirectory(path)
        +getFileSize(path)
        +delete(path, recursive)
        +move(source, dest)
        +copy(source, dest, recursive)
    }

    Terminal --> AndroidShellExecutor
    AndroidShellExecutor --> ShellExecutorFactory
    ShellExecutorFactory --> ShellExecutor
    ShellExecutor <|-- StandardShellExecutor
    ShellExecutor <|-- RootShellExecutor
    LinuxFileSystemTools --> StandardFileSystemTools
    StandardFileSystemTools --> SSHFileConnectionManager
    StandardFileSystemTools --> TerminalManager
    SSHFileConnectionManager --> FileSystemProvider
    TerminalManager --> FileSystemProvider
```

---

## 三、Linux 环境运行详细流程

### 3.1 本地 Linux 环境执行流程

```mermaid
sequenceDiagram
    participant UI as "用户/AI"
    participant TERM as "Terminal.kt"
    participant TM as "TerminalManager"
    participant ASE as "AndroidShellExecutor"
    participant SEF as "ShellExecutorFactory"
    participant SSE as "StandardShellExecutor"
    participant RSE as "RootShellExecutor"
    participant PROC as "Android Runtime"

    UI->>TERM: executeCommand(sessionId, command)
    TERM->>TM: sendCommandToSession(sessionId, command, commandId)
    TM->>PROC: 执行本地Shell命令
    PROC-->>TM: 返回输出流
    TM-->>TERM: CommandExecutionEvent
    TERM-->>UI: 返回执行结果

    alt 需要Root权限
        UI->>ASE: executeShellCommand(command, ROOT)
        ASE->>SEF: getExecutor(context, ROOT)
        SEF->>RSE: 创建RootShellExecutor
        RSE->>RSE: isAvailable() 检查Root
        alt libsu模式
            RSE->>PROC: Shell.cmd(command).exec()
        else exec模式
            RSE->>PROC: Runtime.exec("su -c command")
        end
        PROC-->>RSE: 返回结果
        RSE-->>ASE: CommandResult
        ASE-->>UI: 返回结果
    else 标准权限
        UI->>ASE: executeShellCommand(command)
        ASE->>SEF: getExecutor(context, STANDARD)
        SEF->>SSE: 创建StandardShellExecutor
        SSE->>SSE: containsShellOperators(command)
        alt 含Shell操作符
            SSE->>PROC: Runtime.exec(["sh", "-c", command])
        else 简单命令
            SSE->>PROC: Runtime.exec(command)
        end
        PROC-->>SSE: 返回结果
        SSE-->>ASE: CommandResult
        ASE-->>UI: 返回结果
    end
```

### 3.2 远程 SSH Linux 环境执行流程

```mermaid
sequenceDiagram
    participant AI as "AI/用户"
    participant PKG as "linux_ssh.ts (ToolPkg)"
    participant CFG as "resolveSshConfig()"
    participant RUN as "runRemoteCommandHidden()"
    participant LOC as "本地终端 hiddenExec"
    participant SSH as "SSH 远程服务器"
    participant TMX as "tmux 会话"

    AI->>PKG: linux_ssh_configure(params)
    PKG->>CFG: 解析并持久化配置
    CFG-->>PKG: 返回配置对象
    PKG-->>AI: 配置成功

    AI->>PKG: linux_ssh_exec(params)
    PKG->>CFG: resolveStoredSshConfig(params)
    CFG-->>PKG: 返回host/port/username等
    PKG->>RUN: 构建SSH命令
    RUN->>LOC: Tools.System.terminal.hiddenExec(ssh命令)
    LOC->>SSH: 通过ssh执行远程命令
    SSH-->>LOC: 返回输出
    LOC-->>RUN: 返回结果
    RUN-->>PKG: 解析输出
    PKG-->>AI: 返回执行结果

    alt tmux长任务
        AI->>PKG: linux_ssh_tmux_run(params)
        PKG->>PKG: ensureRemoteTmux(config)
        PKG->>SSH: 检查/安装tmux
        SSH-->>PKG: tmux就绪
        PKG->>PKG: ensureRemoteTmuxWindow(config, windowName)
        PKG->>SSH: 创建tmux窗口
        SSH-->>PKG: 窗口就绪
        PKG->>RUN: 发送任务到tmux窗口
        RUN->>SSH: tmux send-keys 任务命令
        SSH->>TMX: 在tmux中执行任务
        TMX-->>SSH: 任务在后台运行
        SSH-->>RUN: 返回确认
        RUN-->>PKG: 返回结果
        PKG-->>AI: 任务已启动

        AI->>PKG: linux_ssh_tmux_capture(params)
        PKG->>RUN: 构建tmux capture命令
        RUN->>SSH: tmux capture-pane
        SSH->>TMX: 抓取窗口输出
        TMX-->>SSH: 返回输出内容
        SSH-->>RUN: 返回输出
        RUN-->>PKG: 解析输出块
        PKG-->>AI: 返回tmux输出
    end
```

### 3.3 文件系统操作流程（本地/远程统一）

```mermaid
sequenceDiagram
    participant AI as "AI/用户"
    participant LFT as "LinuxFileSystemTools"
    participant SFT as "StandardFileSystemTools"
    participant SSHM as "SSHFileConnectionManager"
    participant TM as "TerminalManager"
    participant FSS as "SSH FileSystemProvider"
    participant FSL as "Local FileSystemProvider"
    participant SSH as "远程SSH服务器"
    participant APP as "Android本地文件"

    AI->>LFT: listFiles(path=/home/user)
    LFT->>SFT: getLinuxFileSystem()
    SFT->>SSHM: getFileSystemProvider()
    alt SSH已连接
        SSHM-->>SFT: 返回SSH FileSystemProvider
        SFT-->>LFT: 返回SSH fs
        LFT->>FSS: listDirectory(/home/user)
        FSS->>SSH: SFTP/SCP 列出目录
        SSH-->>FSS: 返回文件列表
        FSS-->>LFT: 返回FileInfo列表
        LFT-->>AI: 返回DirectoryListingData
    else SSH未连接
        SSHM-->>SFT: 返回null
        SFT->>TM: getFileSystemProvider()
        TM-->>SFT: 返回Local FileSystemProvider
        SFT-->>LFT: 返回本地fs
        LFT->>FSL: listDirectory(/home/user)
        FSL->>APP: 本地文件系统操作
        APP-->>FSL: 返回文件列表
        FSL-->>LFT: 返回FileInfo列表
        LFT-->>AI: 返回DirectoryListingData
    end
```

### 3.4 SSH 配置与连接状态机

```mermaid
stateDiagram-v2
    [*] --> Unconfigured: 初始状态
    Unconfigured --> Configured: linux_ssh_configure 设置参数
    Configured --> Testing: linux_ssh_test_connection 测试连接
    Testing --> Connected: 连接成功
    Testing --> Failed: 连接失败
    Failed --> Configured: 修改配置重试
    Connected --> Executing: linux_ssh_exec 执行命令
    Connected --> TmuxRunning: linux_ssh_tmux_run 启动任务
    TmuxRunning --> Capturing: linux_ssh_tmux_capture 抓取输出
    Capturing --> TmuxRunning: 继续监控
    TmuxRunning --> Connected: 任务完成/关闭窗口
    Executing --> Connected: 命令完成
    Connected --> Disconnected: 连接断开/超时
    Disconnected --> Configured: 自动重连
    Configured --> [*]: 清除配置
```

### 3.5 权限级别与执行器选择流程

```mermaid
flowchart TD
    A["用户请求执行命令"] --> B{"指定权限级别?"}
    B -- 是 --> C["使用指定级别"]
    B -- 否 --> D["读取用户偏好设置"]
    D --> E["getPreferredPermissionLevel()"]
    E --> C
    C --> F["ShellExecutorFactory.getExecutor(context, level)"]
    F --> G{"缓存中已有?"}
    G -- 是 --> H["返回缓存的执行器"]
    G -- 否 --> I["创建新执行器"]
    I --> J{"权限级别"}
    J -- ROOT --> K["RootShellExecutor"]
    J -- ADMIN --> L["AdminShellExecutor"]
    J -- DEBUGGER --> M["DebuggerShellExecutor"]
    J -- ACCESSIBILITY --> N["AccessibilityShellExecutor"]
    J -- STANDARD --> O["StandardShellExecutor"]
    K --> P["initialize()"]
    L --> P
    M --> P
    N --> P
    O --> P
    P --> Q["缓存执行器"]
    Q --> H
    H --> R{"hasPermission()?"}
    R -- 是 --> S["executeCommand(command, identity)"]
    R -- 否 --> T["返回权限拒绝错误"]
    S --> U["返回CommandResult"]
```

---

## 四、核心机制详解

### 4.1 本地终端执行机制（Terminal.kt）

```kotlin
class Terminal private constructor(private val context: Context) {
    private val terminalManager = TerminalManager.getInstance(context)
    
    suspend fun executeCommand(sessionId: String, command: String): String? {
        val deferred = CompletableDeferred<String>()
        val commandId = UUID.randomUUID().toString()
        
        // 订阅命令执行事件
        val job = scope.launch {
            commandEvents
                .filter { it.sessionId == sessionId && it.commandId == commandId }
                .collect { event ->
                    if (event.isCompleted) {
                        deferred.complete(event.outputChunk ?: output.toString())
                    } else {
                        output.append(event.outputChunk)
                    }
                }
        }
        
        // 发送命令到指定会话
        terminalManager.sendCommandToSession(sessionId, command, commandId)
        return deferred.await()
    }
    
    suspend fun executeHiddenCommand(
        command: String,
        executorKey: String = "default",
        timeoutMs: Long = 120000L
    ): HiddenExecResult {
        return terminalManager.executeHiddenCommand(command, executorKey, timeoutMs)
    }
}
```

**关键设计**：
- **会话隔离**：每个命令在独立会话中执行，通过 `sessionId` 区分
- **命令追踪**：使用 `commandId` 追踪特定命令的输出事件
- **隐藏执行器**：`hiddenExec` 在后台终端会话中执行，不干扰用户交互
- **流式输出**：支持 `executeCommandFlow` 返回 Flow，实时获取输出

### 4.2 SSH 远程执行机制（linux_ssh.ts）

```typescript
async function runRemoteCommandHidden(config, remoteCommand, timeoutMs, scope) {
    const runner = async function execute(command, commandTimeoutMs) {
        return await runLocalHiddenCommand(command, commandTimeoutMs, effectiveScope);
    };
    
    // 1. 确保本地SSH依赖已安装
    await ensureLocalSshDependencies(config, runner);
    
    // 2. 构建SSH命令
    const command = buildSshCommand(config, remoteCommand, false);
    
    // 3. 在本地终端通过ssh执行
    const result = await runner(command, timeoutMs || config.timeoutMs);
    return result;
}

function buildSshCommand(config, remoteCommand, interactive) {
    const authPrefix = (config.password && !config.privateKeyPath)
        ? `SSHPASS=${shellQuote(config.password)} sshpass -e `
        : "";
    const keyPart = config.privateKeyPath ? ` -i ${shellQuote(config.privateKeyPath)}` : "";
    const target = `${config.username}@${config.host}`;
    const options = buildSshOptions(config);
    const tty = interactive ? " -tt" : "";
    return `${authPrefix}ssh${tty}${keyPart} ${options} -p ${config.port} ${shellQuote(target)} ${shellQuote(remoteCommand)}`;
}
```

**关键设计**：
- **本地SSH代理**：在 Android 本地终端执行 `ssh` 命令，通过 SSH 协议连接远程服务器
- **自动依赖安装**：自动检测并安装 `openssh-client` 和 `sshpass`
- **密码/密钥双认证**：支持密码认证（通过 sshpass）和私钥认证
- **配置持久化**：通过环境变量 `LINUX_SSH_*` 持久化连接配置

### 4.3 tmux 会话管理机制

```typescript
async function ensureRemoteTmux(config) {
    const installScript = [
        "if command -v tmux >/dev/null 2>&1; then",
        "  echo '__TMUX_READY__'",
        "  exit 0",
        "fi",
        // 自动安装tmux（apt/dnf/yum/pacman）
        "...",
        "echo '__TMUX_INSTALL_FAILED__'"
    ].join("\n");
    
    const result = await runRemoteCommandHidden(config, buildRemoteShellCommand(installScript), 240_000, "tmux");
    return result.output.includes("__TMUX_READY__");
}

async function linux_ssh_tmux_run(params) {
    // 1. 确保tmux已安装
    const tmuxReady = await ensureRemoteTmux(config);
    // 2. 创建/获取tmux窗口
    const windowReady = await ensureRemoteTmuxWindow(config, requestedWindowName);
    // 3. 构建任务启动脚本
    const script = buildTmuxLaunchScript(command, workdir);
    // 4. 通过ssh发送任务到tmux窗口
    const result = await runRemoteCommandWithLocalStdinHidden(config, command, script, timeoutMs, "tmux");
    return result;
}
```

**关键设计**：
- **会话持久化**：tmux 会话在 SSH 断开后保持运行
- **窗口自动管理**：自动创建 `operit_ai` 会话和 `task-N` 窗口
- **输入/输出分离**：通过 `tmux send-keys` 发送输入，`tmux capture-pane` 抓取输出
- **控制键映射**：支持 Enter、Tab、Ctrl+C 等控制键的远程发送

### 4.4 文件系统统一抽象

```kotlin
// StandardFileSystemTools.kt
protected fun getLinuxFileSystem(): FileSystemProvider {
    // 先尝试获取SSH连接的文件系统
    val sshProvider = sshFileManager.getFileSystemProvider()
    if (sshProvider != null) {
        return sshProvider  // 使用SSH远程文件系统
    }
    // 否则使用本地Terminal的文件系统
    return terminalManager.getFileSystemProvider()
}

// LinuxFileSystemTools.kt
class LinuxFileSystemTools(context: Context) : StandardFileSystemTools(context) {
    private val fs get() = getLinuxFileSystem()
    
    override suspend fun listFiles(tool: AITool): ToolResult {
        val path = tool.parameters.find { it.name == "path" }?.value ?: ""
        val fileInfoList = fs.listDirectory(path)
        // ...
    }
}
```

**关键设计**：
- **动态切换**：运行时自动检测 SSH 连接状态，优先使用 SSH 文件系统
- **接口统一**：`FileSystemProvider` 接口统一本地和远程文件操作
- **路径验证**：`PathValidator.validateLinuxPath()` 验证 Linux 路径格式（`/` 或 `~` 开头）
- **环境标识**：返回数据包含 `env = "linux"` 标识

### 4.5 权限分级执行机制

```kotlin
// ShellExecutorFactory.kt
fun getExecutor(context: Context, permissionLevel: AndroidPermissionLevel): ShellExecutor {
    executors[permissionLevel]?.let { return it }
    
    val executor = when (permissionLevel) {
        AndroidPermissionLevel.ROOT -> RootShellExecutor(context)
        AndroidPermissionLevel.ADMIN -> AdminShellExecutor(context)
        AndroidPermissionLevel.DEBUGGER -> DebuggerShellExecutor(context)
        AndroidPermissionLevel.ACCESSIBILITY -> AccessibilityShellExecutor(context)
        AndroidPermissionLevel.STANDARD -> StandardShellExecutor(context)
    }
    executor.initialize()
    executors[permissionLevel] = executor
    return executor
}

// RootShellExecutor.kt
override suspend fun executeCommand(command: String, identity: ShellIdentity): ShellExecutor.CommandResult {
    return when (identity) {
        ShellIdentity.SHELL -> {
            // 使用shell launcher二进制执行
            val launcherPath = ensureShellLauncherInstalled()
            if (useExecMode) {
                // exec模式: su -c "launcher command"
                val process = Runtime.getRuntime().exec(buildSuExecCommand("$launcherPath $command"))
                // ...
            } else {
                // libsu模式: Shell.cmd("launcher command").exec()
                val shellResult = Shell.cmd("$launcherPath $command").exec()
                // ...
            }
        }
        ShellIdentity.ROOT, ShellIdentity.DEFAULT, ShellIdentity.APP -> {
            if (useExecMode) {
                executeCommandWithExec(command)
            } else {
                Shell.cmd(command).exec()
            }
        }
    }
}
```

**关键设计**：
- **五级权限**：STANDARD → ACCESSIBILITY → DEBUGGER → ADMIN → ROOT
- **双模式Root执行**：支持 `libsu` 库模式和传统 `exec su` 模式
- **Shell身份隔离**：通过 `ShellIdentity` 区分 APP/ROOT/SHELL 执行上下文
- **缓存机制**：执行器实例按权限级别缓存，避免重复初始化

---

## 五、数据模型设计

### 5.1 SSH 配置数据流

```mermaid
erDiagram
    ENV["环境变量"] ||--o{ CFG["SSH配置"] : "存储"
    CFG ||--o{ CONN["SSH连接"] : "建立"
    CONN ||--o{ SESS["终端会话"] : "创建"
    SESS ||--o{ CMD["命令执行"] : "发送"
    CMD ||--o{ OUT["输出结果"] : "返回"
    
    ENV {
        string LINUX_SSH_HOST
        string LINUX_SSH_PORT
        string LINUX_SSH_USERNAME
        string LINUX_SSH_PASSWORD
        string LINUX_SSH_PRIVATE_KEY_PATH
        string LINUX_SSH_TIMEOUT_MS
    }
    
    CFG {
        string host
        int port
        string username
        string password
        string privateKeyPath
        int timeoutMs
    }
    
    CONN {
        string connectionId
        string status
        long connectedAt
    }
    
    SESS {
        string sessionId
        string executorKey
        string scope
    }
    
    CMD {
        string command
        int timeoutMs
        string scope
    }
    
    OUT {
        int exitCode
        boolean timedOut
        string output
        string error
    }
```

---

## 六、关键文件索引

| 文件路径 | 职责 |
|----------|------|
| `examples/linux_ssh/src/packages/linux_ssh.ts` | SSH远程工具包核心实现（16个工具函数） |
| `examples/linux_ssh/src/linux_ssh_setup/index.ui.ts` | SSH配置UI界面（Compose DSL） |
| `examples/linux_ssh/manifest.json` | 工具包清单配置 |
| `app/src/main/java/com/ai/assistance/operit/core/tools/system/Terminal.kt` | 终端管理器封装（单例） |
| `app/src/main/java/com/ai/assistance/operit/core/tools/system/OperitTerminalManager.kt` | OperitTerminal应用管理 |
| `app/src/main/java/com/ai/assistance/operit/core/tools/system/AndroidShellExecutor.kt` | Shell执行统一入口 |
| `app/src/main/java/com/ai/assistance/operit/core/tools/system/shell/ShellExecutor.kt` | Shell执行器接口定义 |
| `app/src/main/java/com/ai/assistance/operit/core/tools/system/shell/ShellExecutorFactory.kt` | 执行器工厂（缓存+分级） |
| `app/src/main/java/com/ai/assistance/operit/core/tools/system/shell/StandardShellExecutor.kt` | 标准权限执行器 |
| `app/src/main/java/com/ai/assistance/operit/core/tools/system/shell/RootShellExecutor.kt` | Root权限执行器（libsu/exec双模式） |
| `app/src/main/java/com/ai/assistance/operit/core/tools/defaultTool/standard/LinuxFileSystemTools.kt` | Linux文件系统工具集 |
| `app/src/main/java/com/ai/assistance/operit/core/tools/defaultTool/standard/StandardFileSystemTools.kt` | 文件系统工具基类（SSH/本地切换） |
| `app/src/main/java/com/ai/assistance/operit/core/tools/defaultTool/PathValidator.kt` | 路径验证（Linux/Android） |
| `app/src/main/java/com/ai/assistance/operit/core/tools/defaultTool/standard/StandardTerminalCommandExecutor.kt` | 终端命令执行工具 |

---

## 七、总结

Operit 的 Linux 环境运行架构通过**分层抽象**和**插件化设计**，实现了以下核心能力：

1. **本地Linux执行**：基于 Android Runtime 的 Shell 执行，支持 STANDARD/ROOT 等多级权限
2. **远程SSH连接**：通过 `linux_ssh` 工具包实现完整的 SSH 远程操作，支持密码/密钥认证
3. **tmux会话管理**：远程任务通过 tmux 持久化运行，支持断线重连和输出抓取
4. **文件系统统一**：`FileSystemProvider` 接口统一本地和远程文件操作，AI 工具透明切换
5. **终端集成复用**：`terminal` 子项目提供本地终端能力，复用于 SSH 代理和本地执行
6. **隐藏执行隔离**：`hiddenExec` 机制确保后台命令不污染用户交互会话

整个系统的设计充分体现了**"统一抽象、分层实现、插件扩展"**的思想，使得 AI 能够以一致的方式操作本地和远程 Linux 环境。
