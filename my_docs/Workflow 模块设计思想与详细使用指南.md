# Workflow 模块设计思想与详细使用指南

## 目录

1. [模块概述](#1-模块概述)
2. [核心设计思想](#2-核心设计思想)
3. [架构总览](#3-架构总览)
4. [核心类详解](#4-核心类详解)
5. [节点类型与执行逻辑](#5-节点类型与执行逻辑)
6. [调度系统](#6-调度系统)
7. [关键流程](#7-关键流程)
8. [使用示例](#8-使用示例)
9. [最佳实践](#9-最佳实践)

---

## 1. 模块概述

`workflow` 模块是 Operit 项目的**工作流执行引擎**，负责解析、调度和执行基于有向无环图（DAG）的工作流。工作流由多种类型的节点（触发器、执行、条件、逻辑、提取）通过连接线组成，支持条件分支、错误处理、数据提取和定时调度。

模块位于 `com.ai.assistance.operit.core.workflow` 包下，包含 4 个核心类：

| 类名 | 职责 |
|------|------|
| `WorkflowExecutor` | 工作流执行器，解析 DAG 并拓扑排序执行节点 |
| `WorkflowScheduler` | 工作流调度器，基于 WorkManager 实现定时/周期/Cron 调度 |
| `WorkflowSchedulerInitializer` | 应用启动时重新调度所有已启用的工作流 |
| `WorkflowWorker` | WorkManager Worker，在后台执行定时触发的工作流 |

---

## 2. 核心设计思想

### 2.1 DAG 执行模型

工作流以**有向无环图（DAG）**表示，节点之间通过连接线（`WorkflowNodeConnection`）和参数引用（`ParameterValue.NodeReference`）建立依赖关系：

- **显式依赖**：连接线定义节点间的数据/控制流
- **隐式依赖**：参数引用（如引用前序节点的输出）自动建立执行顺序
- **环检测**：执行前通过 DFS 检测环，存在环则拒绝执行

### 2.2 拓扑排序执行

采用 **Kahn 算法（BFS 拓扑排序）** 执行节点：

1. 计算每个节点的入度（依赖数量）
2. 入度为 0 的节点加入执行队列
3. 执行节点后，将后继节点的入度减 1
4. 入度降为 0 的后继节点加入队列
5. 重复直到队列为空

**设计优势**：
- 保证每个节点在其所有前置依赖完成后才执行
- 支持并行分支（无依赖关系的节点可同时入队）
- 条件不满足时跳过节点，仍正确传播入度

### 2.3 可达性剪枝

从触发节点出发，仅执行**可达的节点**：

1. 从触发节点正向 BFS 找到所有后继节点
2. 从后继节点反向 BFS 找到所有前驱节点
3. 取交集 = 可达节点集合
4. 不可达节点不参与执行

这避免了无关分支的无效执行。

### 2.4 条件路由与错误处理

连接线支持**条件标签**，控制节点间的执行路由：

| 条件值 | 含义 |
|--------|------|
| 空 / `"success"` / `"ok"` | 前序节点成功时执行 |
| `"error"` / `"failed"` / `"on_error"` | 前序节点失败时执行 |
| `"true"` / `"false"` | 匹配前序节点的布尔输出 |
| 正则表达式 | 匹配前序节点的字符串输出 |

**错误处理**：如果失败节点有 `on_error` 连接且目标节点执行成功，则视为"已处理"，不影响整体结果。

### 2.5 参数引用与值解析

节点参数支持两种值类型：

- **`ParameterValue.StaticValue`**：静态字符串值
- **`ParameterValue.NodeReference`**：引用前序节点的输出结果

解析时，`NodeReference` 会查找被引用节点的执行结果：
- `Success` → 返回结果字符串
- `Skipped` → 返回跳过原因
- `Failed` → 抛出异常（引用了失败节点）
- 未完成 → 抛出异常

### 2.6 三种调度模式

基于 Android WorkManager 实现三种调度：

| 模式 | 说明 | WorkManager 类型 |
|------|------|-----------------|
| `interval` | 固定间隔执行（最少 15 分钟） | `PeriodicWorkRequest` |
| `specific_time` | 一次性定时执行 | `OneTimeWorkRequest` |
| `cron` | Cron 表达式调度（简化实现） | 取决于模式 |

---

## 3. 架构总览

```
┌─────────────────────────────────────────────────────────────┐
│                    调度层                                     │
│                                                              │
│  WorkflowSchedulerInitializer                                │
│  └─ Application.onCreate() 时重新调度所有已启用工作流          │
│                                                              │
│  WorkflowScheduler                                           │
│  ├─ scheduleWorkflow(workflow)                               │
│  │   ├─ interval → PeriodicWorkRequest                       │
│  │   ├─ specific_time → OneTimeWorkRequest                   │
│  │   └─ cron → 根据 pattern 选择                             │
│  ├─ cancelWorkflow(workflowId)                               │
│  └─ getNextExecutionTime(workflow)                           │
│                                                              │
│  WorkflowWorker (CoroutineWorker)                            │
│  └─ doWork() → repository.triggerWorkflow(id, triggerNodeId)│
│                                                              │
├─────────────────────────────────────────────────────────────┤
│                    执行层                                     │
│                                                              │
│  WorkflowExecutor                                            │
│  ├─ executeWorkflow(workflow, triggerNodeId, ...)            │
│  │   ├─ 1. 找到触发节点                                      │
│  │   ├─ 2. 构建依赖图（显式连接 + 隐式引用）                  │
│  │   ├─ 3. DFS 环检测                                        │
│  │   ├─ 4. 标记触发节点为成功                                 │
│  │   └─ 5. 拓扑排序执行后续节点                               │
│  │       ├─ 可达性剪枝                                       │
│  │       ├─ 条件路由判断                                      │
│  │       ├─ 执行节点（按类型分发）                             │
│  │       └─ 更新入度，推进队列                                 │
│  │                                                          │
│  ├─ 节点执行：executeNode()                                  │
│  │   ├─ TriggerNode → 标记成功                               │
│  │   ├─ ConditionNode → 比较值，输出 true/false              │
│  │   ├─ LogicNode → AND/OR 逻辑运算                          │
│  │   ├─ ExtractNode → 数据提取/转换                          │
│  │   └─ ExecuteNode → 调用 AIToolHandler 执行工具            │
│  │                                                          │
│  └─ 辅助功能                                                 │
│      ├─ WorkflowRunLogger（执行日志记录）                     │
│      ├─ NodeExecutionState（节点状态追踪）                    │
│      └─ 条件比较 / 数据提取 / 参数解析                        │
└─────────────────────────────────────────────────────────────┘
```

---

## 4. 核心类详解

### 4.1 WorkflowExecutor（工作流执行器）

**定位**：工作流的核心执行引擎，负责 DAG 解析、拓扑排序和节点执行。

**核心方法**：

| 方法 | 说明 |
|------|------|
| `executeWorkflow(workflow, triggerNodeId, triggerExtras, onNodeStateChange)` | 执行工作流，返回 `WorkflowExecutionResult` |

**执行流程**：

```
1. 找到触发节点
   ├─ 指定 triggerNodeId → 使用该触发节点（定时任务）
   └─ 未指定 → 使用所有 manual 类型触发节点（手动触发）

2. 构建依赖图
   ├─ 显式连接（WorkflowNodeConnection）
   └─ 隐式引用（ParameterValue.NodeReference）

3. DFS 环检测
   └─ 存在环 → 返回失败

4. 标记触发节点为 Success（携带 triggerExtras）

5. 拓扑排序执行
   ├─ 可达性剪枝
   ├─ 条件路由判断
   ├─ 执行节点
   └─ 错误处理（on_error 连接）
```

### 4.2 WorkflowScheduler（工作流调度器）

**定位**：基于 Android WorkManager 的工作流定时调度。

**调度类型**：

| 类型 | 配置键 | 说明 |
|------|--------|------|
| `interval` | `interval_ms`, `repeat` | 固定间隔，最少 15 分钟 |
| `specific_time` | `specific_time`, `repeat` | 一次性定时，支持多种日期格式 |
| `cron` | `cron_expression`, `repeat` | 简化 Cron 表达式 |

**Cron 支持的模式**：

| 模式 | 示例 | 说明 |
|------|------|------|
| 每日定时 | `0 8 * * *` | 每天 8:00 |
| 每 N 小时 | `0 */2 * * *` | 每 2 小时 |
| 每 N 分钟 | `*/15 * * * *` | 每 15 分钟 |

**核心方法**：

| 方法 | 说明 |
|------|------|
| `scheduleWorkflow(workflow)` | 调度工作流 |
| `cancelWorkflow(workflowId)` | 取消调度 |
| `isWorkflowScheduled(workflowId)` | 检查是否已调度 |
| `getNextExecutionTime(workflow)` | 获取下次执行时间 |

### 4.3 WorkflowSchedulerInitializer（调度初始化器）

**定位**：应用启动时重新调度所有已启用的工作流。

**设计原因**：Android 可能在应用强制停止或更新后清除 WorkManager 的调度，需要在启动时重新注册。

```kotlin
object WorkflowSchedulerInitializer {
    fun initialize(context: Context) {
        // 1. 获取所有工作流
        // 2. 过滤已启用的
        // 3. 逐个重新调度
    }
}
```

应在 `Application.onCreate()` 中调用。

### 4.4 WorkflowWorker（后台执行器）

**定位**：WorkManager 的 `CoroutineWorker` 实现，在后台执行定时触发的工作流。

```kotlin
class WorkflowWorker(appContext, workerParams) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val workflowId = inputData.getString(KEY_WORKFLOW_ID)
        val triggerNodeId = inputData.getString(KEY_TRIGGER_NODE_ID)
        val repository = WorkflowRepository(applicationContext)
        val result = repository.triggerWorkflow(workflowId, triggerNodeId)
        return if (result.isSuccess) Result.success() else Result.failure()
    }
}
```

---

## 5. 节点类型与执行逻辑

### 5.1 节点类型总览

| 节点类型 | 类名 | 输出 | 说明 |
|---------|------|------|------|
| 触发节点 | `TriggerNode` | triggerExtras JSON | 工作流入口，标记为成功 |
| 执行节点 | `ExecuteNode` | 工具执行结果 | 调用 AIToolHandler 执行工具 |
| 条件节点 | `ConditionNode` | `"true"` / `"false"` | 比较两个值 |
| 逻辑节点 | `LogicNode` | `"true"` / `"false"` | AND/OR 逻辑运算 |
| 提取节点 | `ExtractNode` | 提取后的字符串 | 数据提取/转换/生成 |

### 5.2 TriggerNode（触发节点）

触发节点是工作流的入口，本身不执行任何操作，直接标记为 `Success`。

**触发类型**：

| 类型 | 说明 | 触发方式 |
|------|------|---------|
| `manual` | 手动触发 | 用户点击执行 |
| `schedule` | 定时触发 | WorkManager 调度 |

**triggerExtras**：触发时携带的额外参数，以 JSON 字符串形式传递给后续节点。

### 5.3 ExecuteNode（执行节点）

执行节点调用 `AIToolHandler.executeTool()` 执行指定工具：

```kotlin
val tool = AITool(name = node.actionType, parameters = resolveParameters(node, ...))
val result = toolHandler.executeTool(tool)
```

**参数解析**：
- 静态值直接使用
- 节点引用从 `nodeResults` 中查找
- 可选参数为空时自动跳过（基于 `ToolParameterSchema.required`）
- 支持内置工具和包工具（`packageName:toolName` 格式）

**包工具自动激活**：如果 `actionType` 包含 `:`，会自动启用并激活对应的工具包。

### 5.4 ConditionNode（条件节点）

比较两个值，输出 `"true"` 或 `"false"`。

**支持的比较运算符**：

| 运算符 | 说明 | 数值比较 | 字符串比较 |
|--------|------|---------|-----------|
| `EQ` | 等于 | ✅ | ✅ |
| `NE` | 不等于 | ✅ | ✅ |
| `GT` | 大于 | ✅ | ✅（字典序） |
| `GTE` | 大于等于 | ✅ | ✅ |
| `LT` | 小于 | ✅ | ✅ |
| `LTE` | 小于等于 | ✅ | ✅ |
| `CONTAINS` | 包含 | ❌ | ✅ |
| `NOT_CONTAINS` | 不包含 | ❌ | ✅ |
| `IN` | 在列表中 | ✅ | ✅ |
| `NOT_IN` | 不在列表中 | ✅ | ✅ |

**类型推断**：两端都能解析为数值时按数值比较，否则按字符串比较。混合类型抛出异常。

**IN/NOT_IN**：右侧支持 JSON 数组或逗号分隔列表。

### 5.5 LogicNode（逻辑节点）

对前序节点的布尔输出进行逻辑运算：

| 运算符 | 说明 |
|--------|------|
| `AND` | 所有输入都为 true 时输出 true |
| `OR` | 任一输入为 true 时输出 true |

**输入来源**：从入站连接的前序节点结果中解析布尔值（支持 `true/1/yes/y/on` 和 `false/0/no/n/off`）。

### 5.6 ExtractNode（提取节点）

数据提取/转换/生成，支持 6 种模式：

| 模式 | 说明 | 输入 | 输出 |
|------|------|------|------|
| `REGEX` | 正则提取 | source + expression + group | 匹配组值 |
| `JSON` | JSON Path 提取 | source + expression | 路径对应的值 |
| `SUB` | 子串截取 | source + startIndex + length | 子字符串 |
| `CONCAT` | 字符串拼接 | source + others[] | 拼接结果 |
| `RANDOM_INT` | 随机整数 | randomMin + randomMax | 随机整数 |
| `RANDOM_STRING` | 随机字符串 | randomStringLength + randomStringCharset | 随机字符串 |

**JSON Path 语法**：

```
data.items[0].name     → 访问 data.items 数组第一个元素的 name
config.servers[0][1]   → 支持多维数组索引
result                 → 直接访问对象属性
```

**固定值模式**：`RANDOM_INT` 和 `RANDOM_STRING` 支持 `useFixed` 标记，启用时使用 `fixedValue` 替代随机生成。

---

## 6. 调度系统

### 6.1 调度配置

调度配置存储在 `TriggerNode.triggerConfig` 中：

| 配置键 | 说明 | 适用类型 |
|--------|------|---------|
| `schedule_type` | 调度类型 | 所有 |
| `interval_ms` | 间隔毫秒数 | interval |
| `specific_time` | 目标时间 | specific_time |
| `cron_expression` | Cron 表达式 | cron |
| `repeat` | 是否重复 | 所有 |
| `end_time` | 结束时间 | 所有 |
| `enabled` | 是否启用 | 所有 |

### 6.2 日期格式支持

`specific_time` 支持以下格式：

| 格式 | 示例 |
|------|------|
| ISO 8601 | `2025-05-16T08:30:00` |
| 常用格式 | `2025-05-16 08:30:00` |
| 简短格式 | `2025-05-16 08:30` |
| 仅日期 | `2025-05-16` |

### 6.3 WorkManager 集成

| 调度类型 | WorkManager 请求类型 | 唯一工作名 |
|---------|---------------------|-----------|
| interval | `PeriodicWorkRequest` | `workflow_{workflowId}` |
| specific_time | `OneTimeWorkRequest` | `workflow_{workflowId}` |
| cron (周期) | `PeriodicWorkRequest` | `workflow_{workflowId}` |
| cron (一次性) | `OneTimeWorkRequest` | `workflow_{workflowId}` |

**约束条件**：当前未设置电池约束（`setRequiresBatteryNotLow(false)`）。

**最小间隔**：WorkManager 限制 `PeriodicWorkRequest` 最小间隔为 15 分钟。

---

## 7. 关键流程

### 7.1 工作流执行完整流程

```
executeWorkflow(workflow, triggerNodeId, triggerExtras, onNodeStateChange)
    │
    ├─ 1. 找到触发节点
    │   ├─ triggerNodeId != null → 使用指定触发节点
    │   └─ triggerNodeId == null → 使用所有 manual 触发节点
    │
    ├─ 2. 构建依赖图
    │   ├─ 显式连接 → addEdge(source, target)
    │   └─ 隐式引用 → buildReferenceDependencies()
    │       ├─ ExecuteNode 的参数引用
    │       ├─ ConditionNode 的 left/right 引用
    │       └─ ExtractNode 的 source/others 引用
    │
    ├─ 3. DFS 环检测
    │   └─ 存在环 → 返回失败
    │
    ├─ 4. 标记触发节点
    │   └─ nodeResults[triggerId] = Success(triggerExtras JSON)
    │
    └─ 5. 拓扑排序执行
        ├─ 可达性剪枝（正向 BFS + 反向 BFS）
        ├─ 计算入度（排除触发节点）
        ├─ 入度 0 的节点入队
        │
        └─ 循环处理队列：
            ├─ 检查条件路由
            │   ├─ 无入站连接 → 执行
            │   ├─ 有入站连接 → 检查条件
            │   │   ├─ "error" → 前序失败时执行
            │   │   ├─ "success" → 前序成功时执行
            │   │   ├─ "true"/"false" → 匹配布尔输出
            │   │   ├─ 正则 → 匹配字符串输出
            │   │   └─ 空 → 默认成功时执行
            │   └─ 条件不满足 → Skipped，仍传播入度
            │
            ├─ 执行节点（按类型分发）
            │   ├─ TriggerNode → Success
            │   ├─ ConditionNode → 比较值 → true/false
            │   ├─ LogicNode → AND/OR → true/false
            │   ├─ ExtractNode → 提取/转换/生成
            │   └─ ExecuteNode → AIToolHandler.executeTool()
            │
            └─ 更新后继节点入度，入度 0 入队
```

### 7.2 定时调度流程

```
Application.onCreate()
    │
    └─ WorkflowSchedulerInitializer.initialize(context)
        ├─ 获取所有工作流
        ├─ 过滤已启用的
        └─ 逐个 scheduleWorkflow()
            ├─ 找到 schedule 类型的 TriggerNode
            ├─ 读取 triggerConfig
            └─ 根据 schedule_type 创建 WorkManager 请求
                ├─ interval → PeriodicWorkRequest（最少 15 分钟）
                ├─ specific_time → OneTimeWorkRequest（带延迟）
                └─ cron → 根据 pattern 选择

WorkManager 触发时：
    │
    └─ WorkflowWorker.doWork()
        ├─ 读取 workflowId 和 triggerNodeId
        ├─ repository.triggerWorkflow(workflowId, triggerNodeId)
        │   └─ WorkflowExecutor.executeWorkflow(workflow, triggerNodeId, ...)
        └─ 返回 Result.success() 或 Result.failure()
```

### 7.3 错误处理流程

```
节点执行失败
    │
    ├─ nodeResults[nodeId] = Failed(errorMsg)
    │
    ├─ 检查是否有 on_error 连接
    │   ├─ 有 → 目标节点执行 → 成功则视为"已处理"
    │   └─ 无 → 标记为"未处理"
    │
    └─ 最终判断
        ├─ 所有失败都有 on_error 处理 → 整体成功
        └─ 存在未处理的失败 → 整体失败
```

---

## 8. 使用示例

### 8.1 手动触发工作流

```kotlin
val executor = WorkflowExecutor(context)

val result = executor.executeWorkflow(
    workflow = workflow,
    triggerNodeId = null,  // 执行所有 manual 触发节点
    triggerExtras = emptyMap(),
    onNodeStateChange = { nodeId, state ->
        when (state) {
            is NodeExecutionState.Running -> println("Node $nodeId: Running")
            is NodeExecutionState.Success -> println("Node $nodeId: Success - ${state.result}")
            is NodeExecutionState.Failed -> println("Node $nodeId: Failed - ${state.error}")
            is NodeExecutionState.Skipped -> println("Node $nodeId: Skipped")
            is NodeExecutionState.Pending -> {}
        }
    }
)

if (result.success) {
    println("Workflow executed successfully")
} else {
    println("Workflow failed: ${result.message}")
}

// 查看执行记录
result.executionRecord?.let { record ->
    println("Run ID: ${record.runId}")
    println("Duration: ${record.finishedAt - record.startedAt}ms")
    record.logs.forEach { log ->
        println("[${log.level}] ${log.message}")
    }
}
```

### 8.2 定时触发工作流

```kotlin
val scheduler = WorkflowScheduler(context)

// 调度工作流
val success = scheduler.scheduleWorkflow(workflow)

// 检查调度状态
val isScheduled = scheduler.isWorkflowScheduled(workflow.id)

// 获取下次执行时间
val nextTime = scheduler.getNextExecutionTime(workflow)

// 取消调度
scheduler.cancelWorkflow(workflow.id)
```

### 8.3 应用启动时初始化调度

```kotlin
// 在 Application.onCreate() 中
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        WorkflowSchedulerInitializer.initialize(this)
    }
}
```

### 8.4 条件分支工作流示例

```
[TriggerNode: manual]
        │
        ▼
[ExecuteNode: read_file] → 读取配置文件
        │
        ▼
[ConditionNode: 检查配置项]
    │           │
    │(true)     │(false)
    ▼           ▼
[ExecuteNode: [ExecuteNode:
 执行操作A]   执行操作B]
    │           │
    └─────┬─────┘
          ▼
    [ExecuteNode: 发送通知]
```

### 8.5 错误处理工作流示例

```
[TriggerNode: schedule]
        │
        ▼
[ExecuteNode: http_request] → 调用 API
    │           │
    │(success)  │(error)
    ▼           ▼
[ExtractNode: [ExecuteNode:
 提取数据]     发送错误通知]
    │
    ▼
[ExecuteNode: 保存数据]
```

---

## 9. 最佳实践

### 9.1 工作流设计

- **单一触发入口**：每个工作流建议只有一个触发节点，避免复杂的触发逻辑
- **条件节点输出布尔值**：条件节点的输出为 `"true"` / `"false"` 字符串，后续连接线用 `"true"` / `"false"` 条件路由
- **错误处理**：关键执行节点添加 `on_error` 连接到错误处理节点
- **数据提取**：使用 ExtractNode 从工具输出中提取需要的数据，而非传递整个输出

### 9.2 参数引用

- **引用已完成节点**：确保被引用的节点在当前节点之前执行（通过连接线或自动依赖）
- **避免引用失败节点**：引用失败节点会抛出异常，使用条件分支避免
- **静态值与引用混用**：同一个 ExecuteNode 的不同参数可以分别使用静态值和节点引用

### 9.3 调度配置

- **最小间隔 15 分钟**：WorkManager 限制周期任务最小间隔
- **应用启动重新调度**：在 `Application.onCreate()` 中调用 `WorkflowSchedulerInitializer.initialize()`
- **Cron 简化实现**：当前仅支持基本的 Cron 模式（每日、每 N 小时、每 N 分钟），复杂模式需使用 interval 替代

### 9.4 性能优化

- **可达性剪枝**：执行器自动跳过不可达节点，无需手动优化
- **跳过传播**：被跳过的节点仍正确传播入度，不会阻塞后续节点
- **协程取消**：每个节点执行前检查 `ensureActive()`，支持及时取消

### 9.5 调试技巧

- **执行日志**：`WorkflowExecutionResult.executionRecord.logs` 包含每个节点的详细日志
- **节点状态**：`nodeResults` 记录每个节点的最终状态和输出
- **错误诊断**：`NodeExecutionState.Failed.error` 包含具体错误信息
- **Host Call 追踪**：对于 JS 工具包的执行，QuickJS 引擎提供 Host Call 追踪

---

*文档生成时间：2026-05-16*
*基于 Operit 项目 workflow 模块源代码分析*
