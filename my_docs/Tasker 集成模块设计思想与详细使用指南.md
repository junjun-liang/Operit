# Tasker 集成模块设计思想与详细使用指南

## 目录

1. [模块概述](#1-模块概述)
2. [核心设计思想](#2-核心设计思想)
3. [架构总览](#3-架构总览)
4. [核心类详解](#4-核心类详解)
5. [两种集成方式](#5-两种集成方式)
6. [关键流程](#6-关键流程)
7. [使用示例](#7-使用示例)
8. [最佳实践](#8-最佳实践)

---

## 1. 模块概述

`tasker` 模块是 Operit 项目与 **Tasker**（Android 自动化工具）的集成层，提供双向通信能力：

- **Operit → Tasker**：AI Agent 执行动作时触发 Tasker 事件（Condition/Event 模式）
- **Tasker → Operit**：Tasker 任务触发 Operit 工作流（Action 模式 + Broadcast 模式）

模块位于 `com.ai.assistance.operit.integrations.tasker` 包下，包含 3 个核心文件：

| 文件 | 核心类 | 职责 |
|------|--------|------|
| `AIAgentTasker.kt` | `AIAgentActionEvent` 系列 | AI Agent 动作事件 → Tasker 条件触发 |
| `WorkflowTaskerActivity.kt` | `WorkflowTaskerRunner` 系列 | Tasker Action → 触发 Operit 工作流 |
| `WorkflowTaskerReceiver.kt` | `WorkflowTaskerReceiver` + `WorkflowBootReceiver` | Broadcast 触发工作流 + 开机重调度 |

**依赖库**：`com.joaomgcd.taskerpluginlibrary`（Tasker Plugin Library），简化 Tasker 插件开发。

---

## 2. 核心设计思想

### 2.1 双向集成模型

```
┌─────────────────────────────────────────────────────┐
│                    Tasker                            │
│                                                      │
│  ┌─────────────────┐    ┌─────────────────────────┐ │
│  │  Event/Condition │    │      Action (Task)       │ │
│  │  "AI Agent 动作" │    │  "触发 Operit 工作流"    │ │
│  └────────┬────────┘    └────────────┬────────────┘ │
│           │                          │               │
└───────────┼──────────────────────────┼───────────────┘
            │                          │
     事件触发（被动）            动作执行（主动）
            │                          │
            ▼                          ▼
┌─────────────────────────────────────────────────────┐
│                   Operit                             │
│                                                      │
│  AIAgentTasker              WorkflowTaskerActivity   │
│  (triggerAIAgentAction)    (WorkflowTaskerRunner)    │
│  + WorkflowTaskerReceiver                            │
└─────────────────────────────────────────────────────┘
```

### 2.2 事件驱动架构

AI Agent 执行工具时，通过 `triggerAIAgentAction()` 将动作信息推送给 Tasker。Tasker 侧配置对应的 Event 条件，当收到事件时执行 Tasker 任务。这使得 Operit 的 AI 能力可以与 Tasker 的设备自动化能力无缝结合。

### 2.3 通用参数模型

AI Agent 动作事件采用**通用参数模型**：

- `taskType`：动作类型标识（如 `send_message`、`open_app`）
- `arg1` ~ `arg5`：5 个位置参数
- `argsJson`：完整参数的 JSON 序列化

这种设计兼顾了 Tasker 变量系统的简单性（仅支持字符串）和复杂参数传递的需求。

### 2.4 多入口触发工作流

Tasker 触发 Operit 工作流支持两种入口：

1. **Tasker Plugin Action**：通过 Tasker Plugin Library 的标准 Action 接口
2. **Broadcast Receiver**：通过发送 `Intent` 广播

两种方式最终都调用 `WorkflowRepository.triggerWorkflowsByXxx()` 方法，按不同匹配逻辑查找并执行工作流。

---

## 3. 架构总览

```
┌─────────────────────────────────────────────────────────────┐
│                     Tasker 集成层                             │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐  │
│  │  AIAgentTasker（Operit → Tasker）                      │  │
│  │                                                        │  │
│  │  AIAgentActionUpdate    ← Tasker 输入数据模型           │  │
│  │  AIAgentActionOutput    ← Tasker 输出数据模型           │  │
│  │  AIAgentActionEventRunner ← 事件 Runner               │  │
│  │  AIAgentActionHelper    ← Config Helper                │  │
│  │  ActivityConfigAIAgentAction ← 配置 Activity           │  │
│  │  triggerAIAgentAction() ← 触发扩展函数                  │  │
│  └────────────────────────────────────────────────────────┘  │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐  │
│  │  WorkflowTaskerActivity（Tasker → Operit：Action 模式） │  │
│  │                                                        │  │
│  │  WorkflowTaskerInput    ← Tasker 输入数据模型           │  │
│  │  WorkflowTaskerRunner   ← Action Runner                │  │
│  │  WorkflowTaskerConfigHelper ← Config Helper            │  │
│  │  WorkflowTaskerActivityConfig ← 配置 Activity           │  │
│  └────────────────────────────────────────────────────────┘  │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐  │
│  │  WorkflowTaskerReceiver（Tasker → Operit：Broadcast）   │  │
│  │                                                        │  │
│  │  WorkflowTaskerReceiver ← BroadcastReceiver            │  │
│  │  WorkflowBootReceiver   ← 开机重调度                    │  │
│  └────────────────────────────────────────────────────────┘  │
│                                                              │
├──────────────────────────────────────────────────────────────┤
│                     WorkflowRepository                       │
│  ├─ triggerWorkflowsByTaskerEvent(params)                    │
│  └─ triggerWorkflowsByIntentEvent(intent)                    │
└──────────────────────────────────────────────────────────────┘
```

---

## 4. 核心类详解

### 4.1 AIAgentActionUpdate（Tasker 输入数据模型）

```kotlin
@TaskerInputRoot
class AIAgentActionUpdate @JvmOverloads constructor(
    @field:TaskerInputField("task_type") var taskType: String? = null,
    @field:TaskerInputField("arg1")      var arg1: String? = null,
    @field:TaskerInputField("arg2")      var arg2: String? = null,
    @field:TaskerInputField("arg3")      var arg3: String? = null,
    @field:TaskerInputField("arg4")      var arg4: String? = null,
    @field:TaskerInputField("arg5")      var arg5: String? = null,
    @field:TaskerInputField("args_json") var argsJson: String? = null
)
```

**注解说明**：
- `@TaskerInputRoot`：标记为 Tasker 插件的输入根对象
- `@TaskerInputField("name")`：映射到 Tasker 变量名

**字段说明**：

| 字段 | Tasker 变量名 | 说明 |
|------|-------------|------|
| `taskType` | `%task_type` | 动作类型标识 |
| `arg1` ~ `arg5` | `%arg1` ~ `%arg5` | 位置参数 |
| `argsJson` | `%args_json` | 完整参数的 JSON 序列化 |

### 4.2 AIAgentActionOutput（Tasker 输出数据模型）

```kotlin
@TaskerOutputObject
class AIAgentActionOutput(
    private val taskType: String?,
    private val arg1: String?,
    // ... arg2 ~ arg5
    private val argsJson: String?
) {
    @get:TaskerOutputVariable("task_type")
    val outTaskType get() = taskType ?: ""
    // ... 其他输出变量
}
```

**注解说明**：
- `@TaskerOutputObject`：标记为 Tasker 插件的输出对象
- `@TaskerOutputVariable("name")`：映射到 Tasker 输出变量名

输出变量在 Tasker 中以 `%task_type`、`%arg1` 等名称访问，空值默认返回空字符串。

### 4.3 AIAgentActionEventRunner（事件 Runner）

```kotlin
class AIAgentActionEventRunner :
    TaskerPluginRunnerConditionNoInput<AIAgentActionOutput, AIAgentActionUpdate>() {
    override val isEvent: Boolean get() = true

    override fun getSatisfiedCondition(
        context: Context,
        input: TaskerInput<Unit>,
        update: AIAgentActionUpdate?
    ): TaskerPluginResultCondition<AIAgentActionOutput> {
        val u = update ?: AIAgentActionUpdate()
        val output = AIAgentActionOutput(u.taskType, u.arg1, ...)
        return TaskerPluginResultConditionSatisfied(context, output)
    }
}
```

**关键点**：
- 继承 `TaskerPluginRunnerConditionNoInput`（无需用户配置输入的条件型插件）
- `isEvent = true`：标记为事件类型（而非状态条件）
- 收到 `update` 时返回 `Satisfied`，Tasker 触发对应任务

### 4.4 triggerAIAgentAction（触发扩展函数）

```kotlin
fun Context.triggerAIAgentAction(taskType: String, args: Map<String, String?> = emptyMap()) {
    val update = AIAgentActionUpdate(
        taskType = taskType,
        arg1 = args["arg1"],
        arg2 = args["arg2"],
        arg3 = args["arg3"],
        arg4 = args["arg4"],
        arg5 = args["arg5"],
        argsJson = try { Gson().toJson(args) } catch (_: Exception) { null }
    )
    ActivityConfigAIAgentAction::class.java.requestQuery(this, update)
}
```

**调用方式**：在任何拥有 `Context` 的地方调用：

```kotlin
context.triggerAIAgentAction("send_message", mapOf("arg1" to "Hello"))
```

**内部流程**：
1. 构建 `AIAgentActionUpdate` 对象
2. 将 `args` Map 序列化为 JSON（`argsJson` 字段）
3. 调用 `requestQuery()` 通知 Tasker Plugin Library
4. Tasker 收到事件，执行匹配的任务

### 4.5 WorkflowTaskerInput（工作流触发输入）

```kotlin
data class WorkflowTaskerInput(
    val params: ArrayList<String>? = arrayListOf()
)
```

Tasker Action 的输入参数，`params` 为字符串列表，用于匹配工作流的触发条件。

### 4.6 WorkflowTaskerRunner（工作流触发 Runner）

```kotlin
class WorkflowTaskerRunner : TaskerPluginRunnerAction<WorkflowTaskerInput, Unit>() {
    override fun run(context: Context, input: TaskerInput<WorkflowTaskerInput>): TaskerPluginResult<Unit> {
        val params = input.regular.params
        if (params.isNullOrEmpty()) return TaskerPluginResultSucess()

        return try {
            val repository = WorkflowRepository(context)
            runBlocking { repository.triggerWorkflowsByTaskerEvent(params) }
            TaskerPluginResultSucess()
        } catch (e: Exception) {
            TaskerPluginResultError(e)
        }
    }
}
```

**关键点**：
- 继承 `TaskerPluginRunnerAction`（Action 类型插件）
- 使用 `runBlocking` 在同步上下文中执行协程
- 调用 `repository.triggerWorkflowsByTaskerEvent(params)` 查找并触发匹配的工作流

### 4.7 WorkflowTaskerReceiver（广播触发器）

```kotlin
class WorkflowTaskerReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_TRIGGER_WORKFLOW = "com.ai.assistance.operit.TRIGGER_WORKFLOW"

        fun createTriggerIntent(context: Context, extras: Bundle? = null): Intent {
            return Intent(ACTION_TRIGGER_WORKFLOW).apply {
                setPackage(context.packageName)
                extras?.let { putExtras(it) }
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository = WorkflowRepository(context.applicationContext)
                repository.triggerWorkflowsByIntentEvent(intent)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
```

**关键点**：
- 使用 `goAsync()` 延长 BroadcastReceiver 生命周期（默认 10 秒限制）
- 使用 `CoroutineScope(Dispatchers.IO)` 在 IO 线程执行异步操作
- `setPackage(context.packageName)` 限制广播仅对本应用生效
- `createTriggerIntent()` 工厂方法便于外部构建触发 Intent

### 4.8 WorkflowBootReceiver（开机重调度）

```kotlin
class WorkflowBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val repository = WorkflowRepository(context.applicationContext)
            val result = repository.getAllWorkflows()
            result.getOrNull()?.forEach { workflow ->
                if (workflow.enabled) {
                    repository.scheduleWorkflow(workflow.id)
                }
            }
            pendingResult.finish()
        }
    }
}
```

**设计原因**：Android 在设备重启后会清除 WorkManager 的调度，需要在开机完成后重新注册所有已启用的工作流定时任务。

---

## 5. 两种集成方式

### 5.1 AI Agent 动作事件（Operit → Tasker）

**类型**：Tasker Event/Condition 插件

**数据流**：

```
Operit AI Agent 执行工具
    │
    ├─ context.triggerAIAgentAction("send_message", mapOf("arg1" to "Hello"))
    │
    ├─ 构建 AIAgentActionUpdate
    │   ├─ taskType = "send_message"
    │   ├─ arg1 = "Hello"
    │   └─ argsJson = {"arg1":"Hello"}
    │
    ├─ requestQuery() → 通知 Tasker Plugin Library
    │
    └─ Tasker 收到事件
        ├─ 匹配 Event 条件
        ├─ 输出变量可用：%task_type, %arg1, %args_json
        └─ 执行 Tasker 任务
```

**Tasker 侧配置**：
1. 添加 Profile → Event → Plugin → Operit → AI Agent Action
2. 在关联 Task 中使用 `%task_type`、`%arg1` 等变量

### 5.2 工作流触发（Tasker → Operit）

#### 方式一：Tasker Plugin Action

**类型**：Tasker Action 插件

**数据流**：

```
Tasker Task
    │
    ├─ 添加 Action → Plugin → Operit → Trigger Workflow
    │   └─ 配置 params（字符串列表）
    │
    ├─ WorkflowTaskerRunner.run()
    │   └─ repository.triggerWorkflowsByTaskerEvent(params)
    │
    └─ 查找匹配的工作流并执行
```

#### 方式二：Broadcast Receiver

**类型**：Intent 广播

**数据流**：

```
Tasker Task / 外部应用
    │
    ├─ 发送广播
    │   Intent(action = "com.ai.assistance.operit.TRIGGER_WORKFLOW")
    │       .setPackage("com.ai.assistance.operit")
    │       .putExtra("key", "value")
    │
    ├─ WorkflowTaskerReceiver.onReceive()
    │   └─ repository.triggerWorkflowsByIntentEvent(intent)
    │
    └─ 根据 Intent 的 action 和 extras 查找匹配的工作流并执行
```

**Broadcast 方式的优势**：
- 不需要 Tasker Plugin Library
- 任何应用都可以发送广播触发
- 支持 Intent extras 传递复杂参数

---

## 6. 关键流程

### 6.1 AI Agent 动作触发 Tasker 事件

```
1. AI Agent 执行工具（如 send_message）
    │
    ├─ 2. 工具执行代码中调用
    │   context.triggerAIAgentAction("send_message", mapOf("arg1" to "Hello"))
    │
    ├─ 3. triggerAIAgentAction() 内部
    │   ├─ 构建 AIAgentActionUpdate(taskType="send_message", arg1="Hello", argsJson=...)
    │   └─ ActivityConfigAIAgentAction::class.java.requestQuery(context, update)
    │
    ├─ 4. Tasker Plugin Library 处理
    │   ├─ 找到注册的 AIAgentActionEventRunner
    │   └─ 调用 getSatisfiedCondition() → 返回 Satisfied + Output
    │
    └─ 5. Tasker 执行匹配的 Profile 任务
        └─ 可用变量：%task_type="send_message", %arg1="Hello", %args_json="{...}"
```

### 6.2 Tasker Action 触发 Operit 工作流

```
1. 用户在 Tasker 中创建 Task
    │
    ├─ 2. 添加 Action → Plugin → Operit → Trigger Workflow
    │   └─ 配置 params = ["event_type", "param1"]
    │
    ├─ 3. Tasker 执行 Task 时
    │   └─ WorkflowTaskerRunner.run(context, input)
    │
    ├─ 4. Runner 内部
    │   ├─ 创建 WorkflowRepository(context)
    │   └─ runBlocking { repository.triggerWorkflowsByTaskerEvent(params) }
    │
    └─ 5. WorkflowRepository 查找匹配工作流并执行
```

### 6.3 Broadcast 触发工作流

```
1. Tasker / 外部应用发送广播
    │
    ├─ sendBroadcast(Intent("com.ai.assistance.operit.TRIGGER_WORKFLOW").apply {
    │       setPackage("com.ai.assistance.operit")
    │       putExtra("workflow_name", "my_workflow")
    │   })
    │
    ├─ 2. WorkflowTaskerReceiver.onReceive()
    │   ├─ goAsync() 延长生命周期
    │   └─ CoroutineScope(Dispatchers.IO).launch {
    │           repository.triggerWorkflowsByIntentEvent(intent)
    │       }
    │
    └─ 3. WorkflowRepository 根据 Intent 匹配工作流并执行
```

### 6.4 开机重调度工作流

```
1. Android 系统发送 ACTION_BOOT_COMPLETED
    │
    ├─ 2. WorkflowBootReceiver.onReceive()
    │   ├─ goAsync()
    │   └─ CoroutineScope(Dispatchers.IO).launch {
    │           val workflows = repository.getAllWorkflows().getOrNull()
    │           workflows?.filter { it.enabled }?.forEach {
    │               repository.scheduleWorkflow(it.id)
    │           }
    │       }
    │
    └─ 3. 所有已启用的工作流重新注册到 WorkManager
```

---

## 7. 使用示例

### 7.1 在工具执行中触发 Tasker 事件

```kotlin
class SendMessageToolExecutor : ToolExecutor {
    override fun invoke(tool: AITool): ToolResult {
        val recipient = tool.parameters.find { it.name == "recipient" }?.value ?: ""
        val message = tool.parameters.find { it.name == "message" }?.value ?: ""

        // 触发 Tasker 事件
        context.triggerAIAgentAction(
            taskType = "send_message",
            args = mapOf(
                "arg1" to recipient,
                "arg2" to message
            )
        )

        return ToolResult(toolName = tool.name, success = true, result = StringResultData("已触发发送消息事件"))
    }
}
```

### 7.2 在 Tasker 中配置 AI Agent Action 事件

```
1. 打开 Tasker
2. 创建新 Profile
3. 选择 Event → Plugin → Operit → AI Agent Action
4. （无需配置，自动接收所有 AI Agent 动作）
5. 关联 Task
6. 在 Task 中使用变量：
   - %task_type → 动作类型
   - %arg1 ~ %arg5 → 位置参数
   - %args_json → 完整参数 JSON
7. 可根据 %task_type 值添加 If 条件分支
```

### 7.3 在 Tasker 中触发 Operit 工作流（Action 方式）

```
1. 打开 Tasker
2. 创建新 Task
3. 添加 Action → Plugin → Operit → Trigger Workflow
4. 配置 params（字符串列表，如 ["morning_routine"]）
5. 保存
```

### 7.4 通过 Broadcast 触发工作流

```kotlin
// 从 Tasker 的 "Send Intent" Action 或其他应用
val intent = Intent("com.ai.assistance.operit.TRIGGER_WORKFLOW").apply {
    setPackage("com.ai.assistance.operit")
    putExtra("trigger_event", "morning_routine")
    putExtra("param1", "value1")
}
sendBroadcast(intent)
```

或使用 `WorkflowTaskerReceiver.createTriggerIntent()`：

```kotlin
val intent = WorkflowTaskerReceiver.createTriggerIntent(context, Bundle().apply {
    putString("trigger_event", "morning_routine")
    putString("param1", "value1")
})
context.sendBroadcast(intent)
```

### 7.5 在 Tasker 中根据 AI 动作类型分支处理

```
Profile: AI Agent Action Handler
    Event: Plugin → Operit → AI Agent Action

Enter Task: Handle AI Action
    A1: If %task_type ~ send_message
        A2: Send SMS [ Number:%arg1 Message:%arg2 ]
    A3: Else If %task_type ~ open_app
        A4: Launch App [ Package:%arg1 ]
    A5: Else If %task_type ~ set_volume
        A6: Set Volume [ Level:%arg1 ]
    A7: End If
```

---

## 8. 最佳实践

### 8.1 AI Agent 动作事件设计

- **taskType 命名**：使用 `snake_case` 格式（如 `send_message`、`open_app`、`set_volume`）
- **参数约定**：`arg1` ~ `arg5` 用于简单参数，`argsJson` 用于复杂结构
- **argsJson 序列化**：`triggerAIAgentAction()` 自动将 `Map<String, String?>` 序列化为 JSON
- **空值处理**：Tasker 输出变量空值默认返回空字符串，Tasker 侧需检查

### 8.2 工作流触发匹配

- **Tasker Action 方式**：通过 `params` 字符串列表匹配工作流的触发条件
- **Broadcast 方式**：通过 Intent 的 `action` 和 `extras` 匹配工作流
- **优先使用 Plugin Action**：类型安全、配置界面友好
- **Broadcast 用于外部集成**：其他应用或 Tasker 的 "Send Intent" Action

### 8.3 开机重调度

- `WorkflowBootReceiver` 需在 `AndroidManifest.xml` 中注册并声明 `<intent-filter>` 监听 `BOOT_COMPLETED`
- 需要 `RECEIVE_BOOT_COMPLETED` 权限
- `goAsync()` 确保异步操作在 BroadcastReceiver 生命周期内完成

### 8.4 线程安全与错误处理

- **WorkflowTaskerRunner**：使用 `runBlocking` 在同步上下文中执行协程（Tasker Plugin Library 要求同步返回）
- **WorkflowTaskerReceiver**：使用 `CoroutineScope(Dispatchers.IO)` + `goAsync()` 异步执行
- **错误传播**：Runner 中异常通过 `TaskerPluginResultError(e)` 返回给 Tasker，便于调试
- **BroadcastReceiver**：异常通过 `AppLogger` 记录，不影响系统稳定性

### 8.5 扩展新的事件类型

如需添加新的 Tasker 集成点：

1. **新 Event 类型**：创建新的 `@TaskerInputRoot` 类 + `TaskerPluginRunnerCondition` + `Activity` 配置
2. **新 Action 类型**：创建新的 `TaskerPluginRunnerAction` + `Activity` 配置
3. **新 Broadcast**：定义新的 `ACTION` 常量 + 在 `AndroidManifest.xml` 中注册 Receiver

---

*文档生成时间：2026-05-16*
*基于 Operit 项目 tasker 集成模块源代码分析*
