# Operit 工作流功能使用指南

## 概述

Operit AI 提供了强大的工作流（Workflow）引擎，允许用户通过可视化的方式编排自动化任务。工作流由多个节点和连线组成，支持多种触发方式、条件分支、数据处理和工具调用。

## 核心概念

### 1. 工作流架构

```
┌─────────────────────────────────────────────────────────┐
│                    Workflow (工作流)                      │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐             │
│  │ Trigger  │─▶│ Execute  │─▶│Condition │             │
│  │  触发节点 │  │  执行节点 │  │  条件节点 │             │
│  └──────────┘  └──────────┘  └──────────┘             │
│                      │                                  │
│                      ▼                                  │
│                ┌──────────┐                            │
│                │  Logic   │                            │
│                │  逻辑节点 │                            │
│                └──────────┘                            │
└─────────────────────────────────────────────────────────┘
```

### 2. 节点类型

| 节点类型 | 英文 | 职责 | 示例 |
|----------|------|------|------|
| **触发节点** | `TriggerNode` | 触发工作流执行 | 手动、定时、Intent、语音 |
| **执行节点** | `ExecuteNode` | 执行具体工具 | 调用 `http_request`、`send_notification` |
| **条件节点** | `ConditionNode` | 条件判断 | `EQ`、`GT`、`CONTAINS`、`IN` |
| **逻辑节点** | `LogicNode` | 逻辑运算 | `AND`、`OR` |
| **提取节点** | `ExtractNode` | 数据提取/运算 | 正则、JSONPath、子串、随机数 |

### 3. 触发节点类型

| 触发类型 | 说明 | 配置参数 |
|----------|------|----------|
| **manual** | 手动触发 | 无（UI 点击触发） |
| **schedule** | 定时触发 | `schedule_type`、`interval_ms`、`cron_expression` 等 |
| **tasker** | Tasker 事件触发 | `command`（Tasker 参数字符串） |
| **intent** | 系统广播 Intent 触发 | `action`（Intent Action） |
| **speech** | 语音识别事件触发 | `pattern`（正则匹配）、`ignore_case` 等 |

---

## 工具清单

通过 `workflow` 工具包提供以下功能：

### 1. usage_advice
获取工作流使用建议和最佳实践。

### 2. get_all_workflows
获取所有工作流列表（概要信息）。

**参数**：无

**返回值**：
```json
{
  "success": true,
  "message": "成功获取工作流列表",
  "data": [
    {
      "id": "workflow_id_1",
      "name": "工作流名称",
      "enabled": true,
      "totalExecutions": 10,
      "successfulExecutions": 8,
      "failedExecutions": 2
    }
  ]
}
```

### 3. get_workflow
获取指定工作流完整详情（包含 nodes 和 connections）。

**参数**：
- `workflow_id`（必需）：工作流 ID

**返回值**：
```json
{
  "success": true,
  "message": "成功获取工作流详情",
  "data": {
    "id": "workflow_id",
    "name": "工作流名称",
    "description": "描述",
    "enabled": true,
    "nodes": [...],
    "connections": [...]
  }
}
```

### 4. create_workflow
创建新工作流。

**参数**：
- `name`（必需）：工作流名称
- `description`（可选）：工作流描述
- `nodes`（可选）：节点 JSON 数组（可直接传对象数组）
- `connections`（可选）：连线 JSON 数组
- `enabled`（可选）：是否启用，默认 `true`

**示例**：
```javascript
await workflow.create_workflow({
  name: "我的第一个工作流",
  description: "自动发送通知",
  nodes: [
    {
      id: "trigger_1",
      type: "trigger",
      name: "手动触发",
      triggerType: "manual",
      position: { x: 80, y: 80 }
    },
    {
      id: "exec_1",
      type: "execute",
      name: "发送通知",
      actionType: "send_notification",
      actionConfig: {
        message: "工作流执行成功！"
      },
      position: { x: 320, y: 80 }
    }
  ],
  connections: [
    {
      sourceNodeId: "trigger_1",
      targetNodeId: "exec_1",
      condition: "on_success"
    }
  ],
  enabled: true
});
```

### 5. update_workflow
更新工作流（整体覆盖 nodes/connections）。

**参数**：
- `workflow_id`（必需）：工作流 ID
- `name`（可选）：新名称
- `description`（可选）：新描述
- `nodes`（可选）：新节点数组（整体覆盖）
- `connections`（可选）：新连线数组（整体覆盖）
- `enabled`（可选）：是否启用

**注意**：`nodes` 和 `connections` 是整体覆盖，如果只修改部分节点，推荐使用 `patch_workflow`。

### 6. patch_workflow
差异更新工作流（增量 patch）。

**参数**：
- `workflow_id`（必需）：工作流 ID
- `name`（可选）：新名称
- `description`（可选）：新描述
- `enabled`（可选）：是否启用
- `node_patches`（可选）：节点 patch JSON 数组
- `connection_patches`（可选）：连线 patch JSON 数组

**Patch 操作类型**：
- `op: "add"`：添加节点/连线
- `op: "update"`：更新节点/连线
- `op: "remove"`：删除节点/连线

**示例**：
```javascript
await workflow.patch_workflow({
  workflow_id: "wf_123",
  node_patches: [
    {
      op: "add",
      node: {
        id: "exec_2",
        type: "execute",
        name: "写入日志",
        actionType: "write_file",
        actionConfig: {
          path: "/sdcard/workflow.log",
          content: "工作流执行完成"
        },
        position: { x: 560, y: 80 }
      }
    }
  ]
});
```

### 7. enable_workflow
启用指定工作流。

**参数**：
- `workflow_id`（必需）：工作流 ID

### 8. disable_workflow
禁用指定工作流。

**参数**：
- `workflow_id`（必需）：工作流 ID

### 9. delete_workflow
删除指定工作流。

**参数**：
- `workflow_id`（必需）：工作流 ID

### 10. trigger_workflow
手动触发工作流执行。

**参数**：
- `workflow_id`（必需）：工作流 ID

---

## 节点详细配置

### 1. 触发节点（TriggerNode）

#### manual - 手动触发
```json
{
  "id": "trigger_1",
  "type": "trigger",
  "name": "手动触发",
  "triggerType": "manual",
  "position": { "x": 80, "y": 80 }
}
```

#### schedule - 定时触发
```json
{
  "id": "schedule_trigger",
  "type": "trigger",
  "name": "定时任务",
  "triggerType": "schedule",
  "triggerConfig": {
    "schedule_type": "interval",
    "interval_ms": "900000",  // 15 分钟
    "repeat": "true",
    "enabled": "true"
  },
  "position": { "x": 80, "y": 80 }
}
```

**定时配置选项**：
- `schedule_type`: `interval` | `specific_time` | `cron`
- `interval_ms`: 间隔时间（毫秒）
- `specific_time`: 指定时间（如 "2026-01-04 10:30"）
- `cron_expression`: Cron 表达式（如 "15 * * * *"）
- `repeat`: 是否重复
- `enabled`: 是否启用

#### intent - Intent 广播触发
```json
{
  "id": "intent_trigger",
  "type": "trigger",
  "name": "Intent 触发",
  "triggerType": "intent",
  "triggerConfig": {
    "action": "com.example.myapp.TRIGGER_WORKFLOW"
  },
  "position": { "x": 80, "y": 80 }
}
```

**外部触发方式**：
```bash
# adb 发送广播
adb shell am broadcast \
  -n com.ai.assistance.operit/.integrations.tasker.WorkflowTaskerReceiver \
  -a com.example.myapp.TRIGGER_WORKFLOW \
  --es message "hello from adb"
```

#### speech - 语音触发
```json
{
  "id": "speech_trigger",
  "type": "trigger",
  "name": "语音触发",
  "triggerType": "speech",
  "triggerConfig": {
    "pattern": ".*(打开 | 启动).*(对话 | 聊天).*",
    "ignore_case": "true",
    "require_final": "true",
    "cooldown_ms": "3000"
  },
  "position": { "x": 80, "y": 80 }
}
```

### 2. 执行节点（ExecuteNode）

执行节点用于调用各种工具。

```json
{
  "id": "exec_1",
  "type": "execute",
  "name": "发送通知",
  "actionType": "send_notification",
  "actionConfig": {
    "message": "这是一条通知"
  },
  "position": { "x": 320, "y": 80 }
}
```

**常用工具类型**：
- `send_notification`：发送通知
- `http_request`：HTTP 请求
- `visit_web`：访问网页
- `list_files`：列出文件
- `get_system_setting`：获取系统设置
- `send_message_to_ai`：发送消息给 AI
- `write_file`：写入文件
- `read_file`：读取文件

### 3. 条件节点（ConditionNode）

条件节点用于判断条件，支持多种运算符。

```json
{
  "id": "condition_1",
  "type": "condition",
  "name": "判断数值",
  "left": {
    "nodeId": "exec_1"
  },
  "operator": "GT",
  "right": {
    "value": "100"
  },
  "position": { "x": 320, "y": 200 }
}
```

**支持的运算符**：
- `EQ`：等于
- `NE`：不等于
- `GT`：大于
- `GTE`：大于等于
- `LT`：小于
- `LTE`：小于等于
- `CONTAINS`：包含
- `NOT_CONTAINS`：不包含
- `IN`：在...中
- `NOT_IN`：不在...中

### 4. 逻辑节点（LogicNode）

逻辑节点用于组合多个条件。

```json
{
  "id": "logic_1",
  "type": "logic",
  "name": "与逻辑",
  "operator": "AND",
  "position": { "x": 480, "y": 200 }
}
```

**支持的运算符**：
- `AND`：与
- `OR`：或

### 5. 提取节点（ExtractNode）

提取节点用于数据提取和转换。

#### REGEX - 正则提取
```json
{
  "id": "extract_1",
  "type": "extract",
  "name": "正则提取",
  "source": {
    "nodeId": "exec_1"
  },
  "mode": "REGEX",
  "expression": "用户 ID: (\\d+)",
  "group": "1",
  "defaultValue": "未找到",
  "position": { "x": 320, "y": 320 }
}
```

#### JSON - JSON 路径提取
```json
{
  "id": "extract_json",
  "type": "extract",
  "name": "JSON 提取",
  "source": {
    "nodeId": "http_response"
  },
  "mode": "JSON",
  "expression": "data.user.name",
  "defaultValue": "未知用户",
  "position": { "x": 320, "y": 320 }
}
```

#### SUB - 子串提取
```json
{
  "id": "extract_sub",
  "type": "extract",
  "name": "截取子串",
  "source": {
    "nodeId": "exec_1"
  },
  "mode": "SUB",
  "startIndex": "0",
  "length": "10",
  "defaultValue": "",
  "position": { "x": 320, "y": 320 }
}
```

#### CONCAT - 字符串拼接
```json
{
  "id": "extract_concat",
  "type": "extract",
  "name": "拼接字符串",
  "mode": "CONCAT",
  "others": [
    { "nodeId": "exec_1" },
    { "value": " - " },
    { "nodeId": "exec_2" }
  ],
  "position": { "x": 320, "y: 320 }
}
```

#### RANDOM_INT - 随机整数
```json
{
  "id": "extract_random",
  "type": "extract",
  "name": "生成随机数",
  "mode": "RANDOM_INT",
  "randomMin": "1",
  "randomMax": "100",
  "position": { "x": 320, "y": 320 }
}
```

#### RANDOM_STRING - 随机字符串
```json
{
  "id": "extract_random_str",
  "type": "extract",
  "name": "生成随机字符串",
  "mode": "RANDOM_STRING",
  "randomStringLength": "10",
  "randomStringCharset": "abcdefghijklmnopqrstuvwxyz",
  "position": { "x": 320, "y": 320 }
}
```

---

## 连线（Connection）配置

连线用于连接节点，定义执行流程和条件分支。

### 基本结构

```json
{
  "id": "conn_1",
  "sourceNodeId": "trigger_1",
  "targetNodeId": "exec_1",
  "condition": "on_success"
}
```

### Condition 条件类型

#### 通用关键字（适用于任何节点）

| Condition | 说明 |
|-----------|------|
| `on_success` / `success` / `ok` | 源节点成功时触发 |
| `on_error` / `error` / `failed` | 源节点失败时触发 |

#### 对 ConditionNode / LogicNode

| Condition | 说明 |
|-----------|------|
| `""`（空） | 默认为 `true` 分支 |
| `"true"` | true 分支 |
| `"false"` | false 分支 |
| 其他字符串 | 作为正则匹配源节点输出 |

#### 对非 Condition/Logic 节点

| Condition | 说明 |
|-----------|------|
| `""`（空）或省略 | 等价于 `on_success` |
| `"on_error"` | 源节点失败时触发 |

### 连线 ID 引用方式

在 `connections` 中引用节点时，支持多种方式：

```javascript
// 推荐方式
{
  sourceNodeId: "node_1",
  targetNodeId: "node_2"
}

// 兼容方式
{
  source: "node_1",
  target: "node_2"
}

// 或使用索引
{
  sourceIndex: 0,
  targetIndex: 1
}

// 或使用名称（不推荐，可能重名）
{
  sourceNodeName: "触发节点",
  targetNodeName: "执行节点"
}
```

---

## 参数引用（ParameterValue）

执行节点的 `actionConfig` 支持参数引用。

### 静态值

直接写字面量：
```json
{
  "message": "这是一条静态消息"
}
```

### 引用其他节点输出

```json
{
  "message": {
    "nodeId": "extract_1"
  }
}
```

**兼容字段**：`nodeId` / `ref` / `refNodeId`

### 混合使用

```json
{
  "actionConfig": {
    "url": "https://api.example.com/data",
    "method": "POST",
    "body": {
      "userId": {
        "nodeId": "extract_user_id"
      },
      "timestamp": {
        "nodeId": "extract_timestamp"
      },
      "static_field": "固定值"
    }
  }
}
```

---

## 经典使用场景

### 场景一：定时发送天气通知

#### 步骤 1：创建工作流

```javascript
await workflow.create_workflow({
  name: "定时天气通知",
  description: "每天早上 8 点发送天气通知",
  nodes: [
    {
      id: "schedule_trigger",
      type: "trigger",
      name: "定时触发",
      triggerType: "schedule",
      triggerConfig: {
        schedule_type: "cron",
        cron_expression: "0 8 * * *",  // 每天 8 点
        repeat: "true",
        enabled: "true"
      },
      position: { x: 80, y: 80 }
    },
    {
      id: "http_weather",
      type: "execute",
      name: "获取天气",
      actionType: "http_request",
      actionConfig: {
        url: "https://api.weather.com/v3/w/conditions/local",
        method: "GET",
        params: {
          apiKey: "YOUR_API_KEY",
          format: "json",
          language: "zh-CN"
        }
      },
      position: { x: 320, y: 80 }
    },
    {
      id: "extract_weather",
      type: "extract",
      name: "提取天气信息",
      source: {
        "nodeId": "http_weather"
      },
      mode: "JSON",
      expression: "narrative",
      defaultValue: "天气数据获取失败"
    },
    {
      id: "send_notification",
      type: "execute",
      name: "发送通知",
      actionType: "send_notification",
      actionConfig: {
        message: {
          "nodeId": "extract_weather"
        },
        title: "今日天气"
      },
      position: { x: 800, y: 80 }
    }
  ],
  connections: [
    {
      sourceNodeId: "schedule_trigger",
      targetNodeId: "http_weather",
      condition: "on_success"
    },
    {
      sourceNodeId: "http_weather",
      targetNodeId: "extract_weather",
      condition: "on_success"
    },
    {
      sourceNodeId: "extract_weather",
      targetNodeId: "send_notification",
      condition: "on_success"
    }
  ],
  enabled: true
});
```

---

### 场景二：Intent 触发自动回复

#### 步骤 1：创建工作流

```javascript
await workflow.create_workflow({
  name: "Intent 自动回复",
  description: "接收外部 Intent 并自动回复",
  nodes: [
    {
      id: "intent_trigger",
      type: "trigger",
      name: "Intent 触发",
      triggerType: "intent",
      triggerConfig: {
        action: "com.example.AUTO_REPLY"
      },
      position: { x: 80, y: 80 }
    },
    {
      id: "extract_message",
      type: "extract",
      name: "提取消息内容",
      source: {
        "nodeId": "intent_trigger"
      },
      mode: "JSON",
      expression: "message",
      defaultValue: "默认消息"
    },
    {
      id: "send_reply",
      type: "execute",
      name: "发送回复",
      actionType: "send_notification",
      actionConfig: {
        message: {
          "nodeId": "extract_message"
        },
        title: "自动回复"
      },
      position: { x: 560, y: 80 }
    },
    {
      id: "send_broadcast",
      type: "execute",
      name: "回传结果",
      actionType: "send_broadcast",
      actionConfig: {
        action: "com.example.AUTO_REPLY_RESULT",
        extras: {
          result: {
            "nodeId": "extract_message"
          },
          status: "success"
        }
      },
      position: { x: 800, y: 80 }
    }
  ],
  connections: [
    {
      sourceNodeId: "intent_trigger",
      targetNodeId: "extract_message",
      condition: "on_success"
    },
    {
      sourceNodeId: "extract_message",
      targetNodeId: "send_reply",
      condition: "on_success"
    },
    {
      sourceNodeId: "send_reply",
      targetNodeId: "send_broadcast",
      condition: "on_success"
    }
  ],
  enabled: true
});
```

#### 步骤 2：外部触发

```bash
# 使用 adb 发送广播触发
adb shell am broadcast \
  -n com.ai.assistance.operit/.integrations.tasker.WorkflowTaskerReceiver \
  -a com.example.AUTO_REPLY \
  --es message "你好，这是测试消息"
```

---

### 场景三：条件分支处理

```javascript
await workflow.create_workflow({
  name: "条件分支示例",
  description: "根据数值大小执行不同逻辑",
  nodes: [
    {
      id: "trigger_1",
      type: "trigger",
      name: "手动触发",
      triggerType: "manual",
      position: { x: 80, y: 80 }
    },
    {
      id: "generate_random",
      type: "extract",
      name: "生成随机数",
      mode: "RANDOM_INT",
      randomMin: "1",
      randomMax: "100",
      position: { x: 320, y: 80 }
    },
    {
      id: "condition_check",
      type: "condition",
      name: "判断大小",
      left: {
        "nodeId": "generate_random"
      },
      "operator": "GT",
      "right": {
        "value": "50"
      },
      position: { x: 560, y: 80 }
    },
    {
      id: "exec_large",
      type: "execute",
      name: "大于 50",
      actionType: "send_notification",
      actionConfig: {
        message: "随机数大于 50！",
        title: "条件判断结果"
      },
      position: { x: 800, y: 40 }
    },
    {
      id: "exec_small",
      type: "execute",
      name: "小于等于 50",
      actionType: "send_notification",
      actionConfig: {
        message: "随机数小于等于 50",
        title: "条件判断结果"
      },
      position: { x: 800, y: 160 }
    }
  ],
  connections: [
    {
      sourceNodeId: "trigger_1",
      targetNodeId: "generate_random",
      condition: "on_success"
    },
    {
      sourceNodeId: "generate_random",
      targetNodeId: "condition_check",
      condition: "on_success"
    },
    {
      sourceNodeId: "condition_check",
      targetNodeId: "exec_large",
      condition: "true"  // 条件为真时
    },
    {
      sourceNodeId: "condition_check",
      targetNodeId: "exec_small",
      condition: "false"  // 条件为假时
    }
  ],
  enabled: true
});
```

---

### 场景四：语音触发工作流

```javascript
await workflow.create_workflow({
  name: "语音启动对话",
  description: "说出\"打开对话\"自动启动 Operit",
  nodes: [
    {
      id: "speech_trigger",
      type: "trigger",
      name: "语音触发",
      triggerType: "speech",
      triggerConfig: {
        pattern: ".*(打开 | 启动).*(对话 | 聊天|Operit).*",
        ignore_case: "true",
        require_final: "true",
        cooldown_ms: "3000"
      },
      position: { x: 80, y: 80 }
    },
    {
      id: "send_message",
      type: "execute",
      name: "发送消息",
      actionType: "send_message_to_ai",
      actionConfig: {
        message: "你好！我已经准备好了，有什么可以帮你的吗？"
      },
      position: { x: 320, y: 80 }
    },
    {
      id: "show_toast",
      type: "execute",
      name: "显示提示",
      actionType: "show_toast",
      actionConfig: {
        message: "Operit 已启动"
      },
      position: { x: 560, y: 80 }
    }
  ],
  connections: [
    {
      sourceNodeId: "speech_trigger",
      targetNodeId: "send_message",
      condition: "on_success"
    },
    {
      sourceNodeId: "send_message",
      targetNodeId: "show_toast",
      condition: "on_success"
    }
  ],
  enabled: true
});
```

---

## 最佳实践

### 1. 节点命名规范

- 使用有意义的名称，如 `trigger_manual`、`extract_user_id`
- 包含节点类型前缀，便于识别
- 保持名称唯一性，避免混淆

### 2. 错误处理

使用 `on_error` 分支处理错误：

```javascript
{
  connections: [
    {
      sourceNodeId: "http_request",
      targetNodeId: "error_handler",
      condition: "on_error"
    },
    {
      sourceNodeId: "http_request",
      targetNodeId: "success_handler",
      condition: "on_success"
    }
  ]
}
```

### 3. 冷却时间配置

对于语音触发等频繁触发场景，设置合理的冷却时间：

```json
{
  "triggerConfig": {
    "cooldown_ms": "5000"  // 5 秒冷却
  }
}
```

### 4. 使用 Patch 进行增量更新

避免频繁使用 `update_workflow` 整体覆盖，优先使用 `patch_workflow`：

```javascript
// 添加新节点
await workflow.patch_workflow({
  workflow_id: "wf_123",
  node_patches: [
    {
      op: "add",
      node: { /* 新节点 */ }
    }
  ]
});

// 更新现有节点
await workflow.patch_workflow({
  workflow_id: "wf_123",
  node_patches: [
    {
      op: "update",
      id: "node_1",
      node: { /* 更新后的节点 */ }
    }
  ]
});

// 删除节点
await workflow.patch_workflow({
  workflow_id: "wf_123",
  node_patches: [
    {
      op: "remove",
      id: "node_1"
    }
  ]
});
```

### 5. 调试技巧

1. **先获取工作流列表**：
   ```javascript
   const workflows = await workflow.get_all_workflows();
   console.log(workflows.data);
   ```

2. **查看工作流详情**：
   ```javascript
   const detail = await workflow.get_workflow({ workflow_id: "wf_123" });
   console.log(JSON.stringify(detail.data, null, 2));
   ```

3. **手动触发测试**：
   ```javascript
   const result = await workflow.trigger_workflow({ workflow_id: "wf_123" });
   console.log(result);
   ```

---

## 故障排查

### 问题 1：工作流无法触发

**可能原因**：
- 工作流未启用（`enabled: false`）
- 触发器配置错误
- Intent Action 不匹配

**解决方案**：
```javascript
// 检查工作流状态
const workflows = await workflow.get_all_workflows();
console.log(workflows.data.filter(w => w.enabled));

// 启用工作流
await workflow.enable_workflow({ workflow_id: "wf_123" });
```

### 问题 2：条件分支不执行

**可能原因**：
- Condition 配置错误
- 连线 condition 设置不当
- 数据类型不匹配

**解决方案**：
- 检查 `left` 和 `right` 的值类型
- 确认 `operator` 选择正确
- 验证连线 `condition` 设置（`true`/`false`/`on_success`）

### 问题 3：参数引用失败

**可能原因**：
- `nodeId` 引用错误
- 节点执行顺序问题
- 节点输出为空

**解决方案**：
- 确认 `nodeId` 与目标节点 ID 一致
- 检查节点依赖关系，确保执行顺序正确
- 添加 `defaultValue` 兜底值

---

## 相关文档

- [Workflow Intent 触发](../docs/workflow_intent_trigger.md)
- [API 文档：workflow.d.ts](../docs/package_dev/workflow.md)
- [工作流模板示例](../examples/template_try/resources/workflow_template.json)
- [Operit AI 项目功能全景文档](./Operit%20AI 项目功能全景文档.md)
