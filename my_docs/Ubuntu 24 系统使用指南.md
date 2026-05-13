# Ubuntu 24 系统使用指南

## 概述

Operit AI 内置了完整的 Ubuntu 24 系统，提供了一个功能齐全的 Linux 环境。该环境已正确挂载 Android 的 sdcard 和 storage 目录，可以访问 Android 存储空间，同时具备完整的 Ubuntu 软件包管理能力。

## 核心特性

### 1. 运行环境
- **完整 Ubuntu 24 系统**：不是精简版或模拟器，是完整的 Ubuntu Linux 系统
- **存储挂载**：
  - `/sdcard`：Android 内部存储
  - `/storage`：Android 外部存储（如有）
- **会话保持**：所有命令在相同的会话中执行，上下文连贯，保持工作目录

### 2. 可用工具

通过 `super_admin` 工具包提供以下功能：

#### terminal - Ubuntu 终端命令执行
- **用途**：在 Ubuntu 环境中执行 Linux 命令
- **特点**：
  - 完整的 Ubuntu 环境
  - 可访问 Android 存储
  - 支持前台和后台执行模式
  - 会话上下文保持

**参数**：
- `command`（必需）：要执行的命令
- `background`（可选）：`"true"` 后台运行，`"false"` 或未提供则前台执行
- `timeoutMs`（可选）：超时时间（毫秒，最低 3000ms）
  - 前台默认：15000ms（15 秒）
  - 后台模式：无默认超时

**返回值**：
```json
{
  "command": "执行的命令",
  "output": "命令输出",
  "exitCode": 0,
  "sessionId": "会话 ID",
  "timedOut": false,
  "context_preserved": true
}
```

#### terminal_wait - 等待命令完成
- **用途**：等待上一条命令执行完成
- **适用场景**：安装/编译等长时间运行任务

**参数**：
- `sessionId`（可选）：目标会话 ID，默认使用 `super_admin_default_session`
- `timeoutMs`（可选）：超时时间，默认 300000ms（5 分钟）

#### terminal_getscreen - 获取终端屏幕内容
- **用途**：查看当前终端可见屏幕内容
- **特点**：仅返回当前可见屏幕，不包含历史滚动缓冲

#### terminal_input - 向终端发送输入
- **用途**：模拟键盘输入和控制键
- **参数**：
  - `input`（可选）：文本输入
  - `control`（可选）：控制键（enter/tab/esc/ctrl 等）

**常见用法**：
```javascript
// 发送文本
await super_admin.terminal_input({ input: "hello" });
// 发送 Enter 键
await super_admin.terminal_input({ control: "enter" });
// 发送 Ctrl+C
await super_admin.terminal_input({ control: "ctrl", input: "c" });
```

#### shell - Android Shell 命令执行
- **用途**：通过 Shizuku/Root 权限执行 Android 系统命令
- **运行环境**：直接访问 Android 系统，具有系统级权限
- **适用场景**：pm、am 等 Android 系统命令

---

## 经典使用场景

### 场景一：Python Web 服务器开发

#### 步骤 1：检查 Python 环境
```javascript
await super_admin.terminal({
  command: "python3 --version"
});
```

#### 步骤 2：安装依赖
```javascript
await super_admin.terminal({
  command: "pip3 install flask requests",
  timeoutMs: 60000  // 60 秒超时
});
```

#### 步骤 3：创建 Flask 应用
```javascript
await super_admin.terminal({
  command: "cat > /sdcard/app.py << 'EOF'\nfrom flask import Flask\napp = Flask(__name__)\n\n@app.route('/')\ndef hello():\n    return 'Hello from Operit Ubuntu!'\n\nif __name__ == '__main__':\n    app.run(host='0.0.0.0', port=5000)\nEOF"
});
```

#### 步骤 4：后台启动服务器
```javascript
// 后台启动，不等待完成
const result = await super_admin.terminal({
  command: "cd /sdcard && python3 app.py",
  background: "true"
});
console.log("服务器已启动，会话 ID:", result.sessionId);
```

#### 步骤 5：查看服务器状态
```javascript
// 查看终端屏幕
const screen = await super_admin.terminal_getscreen();
console.log(screen.content);
```

---

### 场景二：Node.js 项目开发

#### 步骤 1：安装 Node.js
```javascript
await super_admin.terminal({
  command: "apt update && apt install -y nodejs npm",
  timeoutMs: 120000  // 2 分钟超时
});
```

#### 步骤 2：创建项目
```javascript
await super_admin.terminal({
  command: "mkdir -p /sdcard/my_project && cd /sdcard/my_project && npm init -y"
});
```

#### 步骤 3：安装依赖
```javascript
await super_admin.terminal({
  command: "cd /sdcard/my_project && npm install express",
  timeoutMs: 60000
});
```

#### 步骤 4：创建服务器文件
```javascript
await super_admin.terminal({
  command: "cat > /sdcard/my_project/server.js << 'EOF'\nconst express = require('express');\nconst app = express();\nconst PORT = 3000;\n\napp.get('/', (req, res) => {\n  res.send('Express Server Running!');\n});\n\napp.listen(PORT, () => {\n  console.log(`Server running on port ${PORT}`);\n});\nEOF"
});
```

#### 步骤 5：启动开发服务器
```javascript
const result = await super_admin.terminal({
  command: "cd /sdcard/my_project && node server.js",
  background: "true"
});
```

---

### 场景三：文件处理和数据分析

#### 步骤 1：查看 Android 存储文件
```javascript
await super_admin.terminal({
  command: "ls -la /sdcard/Download/"
});
```

#### 步骤 2：处理 CSV 文件
```javascript
// 安装 pandas
await super_admin.terminal({
  command: "pip3 install pandas",
  timeoutMs: 60000
});

// 创建数据处理脚本
await super_admin.terminal({
  command: "cat > /sdcard/process_data.py << 'EOF'\nimport pandas as pd\n\n# 读取 CSV\ndf = pd.read_csv('/sdcard/Download/data.csv')\n\n# 数据统计\nprint(df.describe())\n\n# 保存结果\ndf.to_csv('/sdcard/Download/result.csv', index=False)\nprint('处理完成')\nEOF"
});

// 执行脚本
await super_admin.terminal({
  command: "python3 /sdcard/process_data.py",
  timeoutMs: 30000
});
```

#### 步骤 3：使用 Linux 工具处理文本
```javascript
// 统计文件行数
await super_admin.terminal({
  command: "wc -l /sdcard/Download/large_file.txt"
});

// 搜索关键字
await super_admin.terminal({
  command: "grep -n 'error' /sdcard/Download/log.txt | head -20"
});

// 提取特定列
await super_admin.terminal({
  command: "cut -d',' -f1,3 /sdcard/Download/data.csv | head -10"
});
```

---

### 场景四：Git 版本控制

#### 步骤 1：安装 Git
```javascript
await super_admin.terminal({
  command: "apt update && apt install -y git",
  timeoutMs: 60000
});
```

#### 步骤 2：配置 Git
```javascript
await super_admin.terminal({
  command: "git config --global user.name 'Your Name'"
});
await super_admin.terminal({
  command: "git config --global user.email 'your@email.com'"
});
```

#### 步骤 3：克隆仓库
```javascript
await super_admin.terminal({
  command: "cd /sdcard && git clone https://github.com/user/repo.git",
  timeoutMs: 120000
});
```

#### 步骤 4：查看状态和提交
```javascript
await super_admin.terminal({
  command: "cd /sdcard/repo && git status"
});

await super_admin.terminal({
  command: "cd /sdcard/repo && git add ."
});

await super_admin.terminal({
  command: "cd /sdcard/repo && git commit -m 'Update files'"
});
```

---

### 场景五：数据库操作

#### 步骤 1：安装 SQLite
```javascript
await super_admin.terminal({
  command: "apt update && apt install -y sqlite3",
  timeoutMs: 60000
});
```

#### 步骤 2：创建数据库
```javascript
await super_admin.terminal({
  command: "sqlite3 /sdcard/mydb.db << 'EOF'\nCREATE TABLE users (\n  id INTEGER PRIMARY KEY,\n  name TEXT,\n  email TEXT\n);\nINSERT INTO users VALUES (1, 'John', 'john@example.com');\nINSERT INTO users VALUES (2, 'Jane', 'jane@example.com');\nEOF"
});
```

#### 步骤 3：查询数据
```javascript
await super_admin.terminal({
  command: "sqlite3 /sdcard/mydb.db 'SELECT * FROM users'"
});
```

#### 步骤 4：安装和使用 PostgreSQL（高级）
```javascript
// 安装 PostgreSQL
await super_admin.terminal({
  command: "apt install -y postgresql postgresql-contrib",
  timeoutMs: 120000
});

// 启动 PostgreSQL 服务（后台）
await super_admin.terminal({
  command: "service postgresql start",
  background: "true"
});

// 等待服务启动
await super_admin.terminal_wait({ timeoutMs: 10000 });
```

---

### 场景六：网络调试和 API 测试

#### 步骤 1：安装工具
```javascript
await super_admin.terminal({
  command: "apt install -y curl wget netcat-openbsd",
  timeoutMs: 60000
});
```

#### 步骤 2：测试 API
```javascript
// GET 请求
await super_admin.terminal({
  command: "curl -X GET https://api.example.com/data"
});

// POST 请求
await super_admin.terminal({
  command: "curl -X POST -H 'Content-Type: application/json' -d '{\"key\":\"value\"}' https://api.example.com/data"
});

// 下载文件
await super_admin.terminal({
  command: "wget -O /sdcard/Download/file.zip https://example.com/file.zip",
  timeoutMs: 120000
});
```

#### 步骤 3：网络诊断
```javascript
// 检查端口连通性
await super_admin.terminal({
  command: "nc -zv 192.168.1.1 80"
});

// 查看网络接口
await super_admin.terminal({
  command: "ip addr show"
});

// DNS 查询
await super_admin.terminal({
  command: "nslookup example.com"
});
```

---

### 场景七：编译和构建

#### 步骤 1：安装编译工具
```javascript
await super_admin.terminal({
  command: "apt update && apt install -y build-essential gcc g++ make",
  timeoutMs: 120000
});
```

#### 步骤 2：编译 C 程序
```javascript
// 创建 C 文件
await super_admin.terminal({
  command: "cat > /sdcard/hello.c << 'EOF'\n#include <stdio.h>\nint main() {\n    printf(\"Hello from Ubuntu!\\n\");\n    return 0;\n}\nEOF"
});

// 编译
await super_admin.terminal({
  command: "gcc -o /sdcard/hello /sdcard/hello.c"
});

// 运行
await super_admin.terminal({
  command: "/sdcard/hello"
});
```

#### 步骤 3：编译大型项目（使用后台模式）
```javascript
// 后台编译
await super_admin.terminal({
  command: "cd /sdcard/large_project && make",
  background: "true"
});

// 等待编译完成多种方式：

// 方式 1：使用 terminal_wait
await super_admin.terminal_wait({ timeoutMs: 300000 });

// 方式 2：定期检查屏幕
let completed = false;
while (!completed) {
  const screen = await super_admin.terminal_getscreen();
  if (screen.content.includes('Building complete') || 
      screen.content.includes('make: ***')) {
    completed = true;
  } else {
    await new Promise(resolve => setTimeout(resolve, 5000));
  }
}
```

---

### 场景八：系统监控和日志分析

#### 步骤 1：查看系统信息
```javascript
// Ubuntu 系统信息
await super_admin.terminal({
  command: "uname -a"
});

await super_admin.terminal({
  command: "cat /etc/os-release"
});

// 内存使用
await super_admin.terminal({
  command: "free -h"
});

// 磁盘使用
await super_admin.terminal({
  command: "df -h"
});

// 进程列表
await super_admin.terminal({
  command: "ps aux | head -20"
});
```

#### 步骤 2：日志分析
```javascript
// 实时查看日志
await super_admin.terminal({
  command: "tail -f /sdcard/app.log",
  background: "true"
});

// 搜索错误
await super_admin.terminal({
  command: "grep -i 'error' /sdcard/app.log | tail -50"
});

// 统计日志
await super_admin.terminal({
  command: "awk '{print $1}' /sdcard/app.log | sort | uniq -c | sort -rn"
});
```

---

## 高级技巧

### 1. 长时间运行任务的最佳实践

```javascript
// 错误示例：前台执行长时间任务，容易超时
await super_admin.terminal({
  command: "pip install tensorflow",  // 可能超过 15 秒
  timeoutMs: 15000  // 太短，会超时
});

// 正确示例：后台执行 + 等待
const result = await super_admin.terminal({
  command: "pip install tensorflow",
  background: "true"
});

// 等待完成
await super_admin.terminal_wait({
  sessionId: result.sessionId,
  timeoutMs: 300000  // 5 分钟
});

// 查看结果
const screen = await super_admin.terminal_getscreen();
console.log(screen.content);
```

### 2. 交互式命令处理

对于需要交互的命令（如 vim、nano、htop 等）：

```javascript
// 启动 vim
await super_admin.terminal({
  command: "vim /sdcard/config.txt"
});

// 发送输入
await super_admin.terminal_input({ input: "i" });  // 进入插入模式
await super_admin.terminal_input({ input: "Hello World" });
await super_admin.terminal_input({ control: "esc" });  // 退出插入模式
await super_admin.terminal_input({ input: ":wq" });  // 保存退出
await super_admin.terminal_input({ control: "enter" });

// 查看结果
const screen = await super_admin.terminal_getscreen();
```

### 3. 大输出处理

当命令输出超过 12000 字符时，系统会自动保存到文件：

```javascript
const result = await super_admin.terminal({
  command: "find /sdcard -type f -ls"  // 可能产生大量输出
});

if (result.output_saved_to) {
  console.log("输出已保存到:", result.output_saved_to);
  console.log("输出字符数:", result.output_chars);
  
  // 使用 read_file_part 或 grep_code 查看
  const part = await Tools.Files.read_file_part({
    path: result.output_saved_to,
    offset: 0,
    limit: 100
  });
}
```

### 4. 多会话管理

```javascript
// 默认会话（所有命令共享上下文）
await super_admin.terminal({ command: "cd /sdcard" });
await super_admin.terminal({ command: "pwd" });  // 输出：/sdcard

// 后台任务使用独立会话
const session1 = await super_admin.terminal({
  command: "python3 server1.py",
  background: "true"
});

const session2 = await super_admin.terminal({
  command: "python3 server2.py",
  background: "true"
});

// 分别查看不同会话
// 注意：当前实现只支持默认会话的 getscreen/input 操作
```

---

## 注意事项

### ⚠️ 禁止使用的命令

**严禁使用会改变 shell 退出行为的命令**，这会导致终端会话退出并卡死：

```javascript
// ❌ 错误示例
await super_admin.terminal({
  command: "set -e && echo hello"  // 错误时立即退出，导致会话卡死
});

await super_admin.terminal({
  command: "set -o errexit && make"  // 同样会导致问题
});

// ✅ 正确示例
await super_admin.terminal({
  command: "echo hello && make"  // 正常使用
});
```

### ⏱️ 超时处理

1. **前台命令**：默认 15 秒超时，建议根据任务类型显式设置
   - 快速查询：5000-10000ms
   - 安装包：60000-120000ms
   - 编译项目：120000-300000ms

2. **后台命令**：无默认超时，适合长时间任务

3. **超时后处理**：
   - 命令不会被自动取消
   - 继续在后台执行
   - 使用 `terminal_getscreen` 查看进度
   - 使用 `terminal_wait` 等待完成

### 📁 文件路径

- **Ubuntu 系统路径**：使用标准 Linux 路径（如 `/home`, `/tmp`）
- **Android 存储**：
  - `/sdcard`：Android 内部存储（对应手机存储）
  - `/storage`：Android 外部存储（如 SD 卡）

### 🔐 权限说明

- Ubuntu 环境具有完整的 Linux 用户权限
- 可以访问 Android 存储（已挂载）
- 不能直接执行 Android 系统命令（需使用 `shell` 工具）

---

## 故障排查

### 问题 1：命令执行超时

**现象**：返回 `timedOut: true`

**解决方案**：
```javascript
// 1. 增加超时时间
await super_admin.terminal({
  command: "大型命令",
  timeoutMs: 120000
});

// 2. 或使用后台模式
const result = await super_admin.terminal({
  command: "大型命令",
  background: "true"
});
await super_admin.terminal_wait({ timeoutMs: 300000 });
```

### 问题 2：终端会话卡住

**现象**：命令无响应，一直等待

**解决方案**：
```javascript
// 发送 Ctrl+C 中断命令
await super_admin.terminal_input({
  control: "ctrl",
  input: "c"
});

// 查看当前状态
const screen = await super_admin.terminal_getscreen();
```

### 问题 3：无法访问 Android 文件

**现象**：`/sdcard` 或 `/storage` 目录不存在或无法访问

**解决方案**：
```javascript
// 检查挂载
await super_admin.terminal({
  command: "ls -la /sdcard"
});

// 如果未挂载，可能需要重启 Operit 应用
```

### 问题 4：输出被截断

**现象**：命令输出不完整

**解决方案**：
```javascript
// 1. 检查是否保存到了文件
const result = await super_admin.terminal({
  command: "大量输出的命令"
});
if (result.output_saved_to) {
  console.log("输出已保存到:", result.output_saved_to);
}

// 2. 使用重定向到文件
await super_admin.terminal({
  command: "大量输出的命令 > /sdcard/output.txt"
});
// 然后读取文件
const content = await Tools.Files.read({
  path: "/sdcard/output.txt"
});
```

---

## 最佳实践总结

1. **显式设置超时**：始终根据任务类型设置合适的 `timeoutMs`
2. **后台执行长任务**：安装、编译等使用 `background: "true"`
3. **避免危险命令**：不使用 `set -e` 等改变 shell 行为的命令
4. **定期检查进度**：长任务使用 `terminal_getscreen` 查看状态
5. **合理使用会话**：理解默认会话的上下文保持特性
6. **大输出处理**：注意输出可能自动保存到文件
7. **文件路径**：使用 `/sdcard` 访问 Android 存储

---

## 相关文档

- [Operit Linux 环境运行架构设计分析](./Operit%20Linux 环境运行架构设计分析.md)
- [super_admin 工具包源码](../app/src/main/assets/packages/super_admin.js)
- [Operit AI 项目功能全景文档](./Operit%20AI 项目功能全景文档.md)
