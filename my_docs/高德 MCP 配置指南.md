# 高德 MCP 配置指南

## 概述

Operit AI 项目支持通过 **MCP Bridge** 连接第三方 MCP 服务，包括高德地图 MCP。

## 前置要求

1. **获取高德 API Key**
   - 访问 [高德开放平台](https://lbs.amap.com/)
   - 注册账号并创建应用
   - 获取 API Key（Web 服务 API）

2. **安装高德 MCP Server**
   - 高德 MCP 通常是一个独立的 Node.js 服务
   - 需要单独安装和配置

## 配置步骤

### 方案一：通过 MCP Bridge 配置（推荐）

#### 1. 注册高德 MCP 服务

使用 `register` 命令注册高德 MCP 服务：

```json
{
  "command": "register",
  "id": "unique-id-123",
  "params": {
    "name": "amap",
    "type": "local",
    "command": "node",
    "args": ["/path/to/amap-mcp-server.js"],
    "env": {
      "AMAP_API_KEY": "你的高德 API Key",
      "AMAP_API_BASE_URL": "https://restapi.amap.com/v3"
    },
    "description": "高德地图 MCP 服务"
  }
}
```

#### 2. 启动高德 MCP 服务

使用 `spawn` 命令启动服务：

```json
{
  "command": "spawn",
  "id": "unique-id-456",
  "params": {
    "name": "amap",
    "timeoutMs": 30000
  }
}
```

#### 3. 查看可用工具

```json
{
  "command": "listtools",
  "id": "unique-id-789",
  "params": {
    "name": "amap"
  }
}
```

#### 4. 调用高德工具

```json
{
  "command": "toolcall",
  "id": "unique-id-012",
  "params": {
    "name": "amap",
    "method": "geocode",
    "params": {
      "address": "北京市朝阳区"
    }
  }
}
```

### 方案二：通过配置文件（如果项目支持）

检查是否有 MCP 配置文件：

```bash
# 可能的配置文件位置
~/.operit/mcp_config.json
# 或
/home/meizu/Documents/my_agent_projects/Operit/config/mcp_servers.json
```

配置示例：

```json
{
  "mcpServers": {
    "amap": {
      "command": "node",
      "args": ["/path/to/amap-mcp-server.js"],
      "env": {
        "AMAP_API_KEY": "你的高德 API Key"
      },
      "enabled": true
    }
  }
}
```

## 环境变量说明

### 必需的环境变量

| 变量名 | 说明 | 示例 |
|--------|------|------|
| `AMAP_API_KEY` | 高德 API Key | `a1b2c3d4e5f6g7h8i9` |

### 可选的环境变量

| 变量名 | 说明 | 默认值 |
|--------|------|--------|
| `AMAP_API_BASE_URL` | API 基础 URL | `https://restapi.amap.com/v3` |
| `AMAP_SECRET` | API 密钥（某些服务需要） | - |

## 完整示例

### TypeScript/JavaScript 示例

```typescript
import * as net from 'net';

// 连接到 MCP Bridge
const client = new net.Socket();
client.connect(8752, '127.0.0.1', () => {
  console.log('Connected to MCP Bridge');
  
  // 1. 注册高德服务
  const registerCmd = {
    command: 'register',
    id: 'reg-001',
    params: {
      name: 'amap',
      type: 'local',
      command: 'node',
      args: ['/path/to/amap-mcp-server.js'],
      env: {
        AMAP_API_KEY: 'your-api-key-here'
      },
      description: '高德地图服务'
    }
  };
  
  client.write(JSON.stringify(registerCmd) + '\n');
});

// 接收响应
client.on('data', (data) => {
  const response = JSON.parse(data.toString());
  console.log('Response:', response);
  
  if (response.command === 'register' && response.success) {
    // 2. 启动服务
    const spawnCmd = {
      command: 'spawn',
      id: 'spawn-001',
      params: {
        name: 'amap'
      }
    };
    client.write(JSON.stringify(spawnCmd) + '\n');
  }
});
```

### Shell 脚本示例

```bash
#!/bin/bash

# 注册高德 MCP 服务
echo '{"command":"register","id":"reg-001","params":{"name":"amap","type":"local","command":"node","args":["/opt/mcp/amap-server.js"],"env":{"AMAP_API_KEY":"your-key-here"}}}' | nc localhost 8752

# 启动服务
echo '{"command":"spawn","id":"spawn-001","params":{"name":"amap"}}' | nc localhost 8752

# 查看工具列表
echo '{"command":"listtools","id":"list-001","params":{"name":"amap"}}' | nc localhost 8752

# 调用地理编码工具
echo '{"command":"toolcall","id":"call-001","params":{"name":"amap","method":"geocode","params":{"address":"北京市朝阳区"}}}' | nc localhost 8752
```

## 获取高德 API Key

### 步骤

1. **访问高德开放平台**
   - URL: https://lbs.amap.com/

2. **注册/登录账号**
   - 使用手机号或邮箱注册

3. **进入控制台**
   - 点击 "控制台" → "应用管理"

4. **创建应用**
   - 应用名称：随意填写（如 "Operit AI"）
   - 应用类型：选择 "其他"
   - 提交

5. **添加 Key**
   - 在应用下点击 "添加 Key"
   - 服务平台：选择 "Web 服务"
   - 提交

6. **复制 Key**
   - 复制生成的 Key
   - 保存到安全位置

### Key 类型说明

| 类型 | 用途 | 是否需要 |
|------|------|----------|
| Web 服务 API | 后端服务器调用 | ✅ 必需 |
| Android 平台 | Android 应用 | ❌ 不需要 |
| iOS 平台 | iOS 应用 | ❌ 不需要 |
| Web 端 (JS API) | 浏览器前端 | ❌ 不需要 |

## 常见问题

### Q1: 找不到高德 MCP Server？

**A**: 高德官方可能没有提供官方的 MCP Server，你可以：

1. **使用第三方实现**
   - 搜索 GitHub：`amap-mcp-server`
   - 例如：https://github.com/search?q=amap+mcp

2. **自己实现**
   - 参考 MCP SDK
   - 封装高德 API 为 MCP 工具

### Q2: 提示 "API Key is required"？

**A**: 确保：
- 环境变量 `AMAP_API_KEY` 已正确设置
- Key 没有拼写错误
- Key 已启用 Web 服务 API

### Q3: 服务启动失败？

**A**: 检查：
- MCP Server 路径是否正确
- Node.js 是否已安装
- 依赖是否已安装（`npm install`）
- 查看日志：使用 `logs` 命令

```json
{
  "command": "logs",
  "id": "log-001",
  "params": {
    "name": "amap"
  }
}
```

### Q4: 如何查看服务状态？

**A**: 使用 `list` 命令：

```json
{
  "command": "list",
  "id": "list-002",
  "params": {
    "name": "amap"
  }
}
```

## 高德 API 参考

### 常用 API

1. **地理编码**
   - 地址转坐标
   - Endpoint: `/geocode/geo`

2. **逆地理编码**
   - 坐标转地址
   - Endpoint: `/regeo`

3. **天气查询**
   - 查询天气信息
   - Endpoint: `/weather/weatherInfo`

4. **路径规划**
   - 驾车/公交/步行路线
   - Endpoint: `/direction/driving`

5. **POI 搜索**
   - 搜索兴趣点
   - Endpoint: `/place/text`

### API 调用示例

```bash
# 地理编码
curl "https://restapi.amap.com/v3/geocode/geo?address=北京市朝阳区&key=YOUR_API_KEY"

# 天气查询
curl "https://restapi.amap.com/v3/weather/weatherInfo?city=110101&key=YOUR_API_KEY"

# POI 搜索
curl "https://restapi.amap.com/v3/place/text?keywords=餐厅&city=北京&key=YOUR_API_KEY"
```

## MCP Bridge 命令参考

| 命令 | 说明 | 参数 |
|------|------|------|
| `register` | 注册服务 | name, type, command, args, env |
| `unregister` | 注销服务 | name |
| `spawn` | 启动服务 | name, timeoutMs |
| `unspawn` | 停止服务（不注销） | name |
| `shutdown` | 停止并注销服务 | name |
| `list` | 列出服务 | name（可选） |
| `listtools` | 列出工具 | name（可选） |
| `toolcall` | 调用工具 | name, method, params |
| `logs` | 查看日志 | name |
| `reset` | 重置所有服务 | - |

## 相关文档

- [MCP Bridge 源码](file:///home/meizu/Documents/my_agent_projects/Operit/tools/mcp_bridge/index.ts)
- [Operit AI 功能全景](file:///home/meizu/Documents/my_agent_projects/Operit/my_docs/Operit AI 项目功能全景文档.md)

## 总结

1. ✅ **获取高德 API Key** - 在高德开放平台申请
2. ✅ **安装高德 MCP Server** - 自行安装或实现
3. ✅ **注册服务** - 通过 MCP Bridge 注册
4. ✅ **配置环境变量** - 设置 `AMAP_API_KEY`
5. ✅ **启动服务** - 使用 `spawn` 命令
6. ✅ **调用工具** - 使用 `toolcall` 命令

---

**文档版本**: v1.0  
**最后更新**: 2026-05-12
