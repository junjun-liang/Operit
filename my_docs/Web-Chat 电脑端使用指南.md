# Web-Chat 电脑端使用指南

## 问题诊断

当前 web-chat 显示 **HTTP 404** 错误的原因是：
- web-chat 前端已在电脑浏览器成功加载 ✅
- 但无法连接到后端的聊天服务（运行在 Android 手机上）❌

**已解决**：已配置 Vite 代理，自动转发 API 请求到手机 ✅

## 系统架构

```
┌─────────────────┐         自动代理转发        ┌──────────────────┐
│  电脑浏览器      │ ─────────────────────────> │  Android 手机     │
│  http://localhost:5174 │   API 请求走代理      │  Operit AI 应用   │
│  web-chat 前端   │ <───────────────────────── │  端口：8094      │
└─────────────────┘      JSON API + SSE 流       └──────────────────┘
```

## 使用步骤

### 第一步：确认 Android 手机配置

根据截图，你的手机已正确配置：
- ✅ **外部 HTTP 服务**：已启用
- ✅ **监听端口**：8094
- ✅ **服务状态**：运行中
- ✅ **Bearer Token**：`d922b959dc3c45bdbab912b6fff2d5e2`
- ✅ **局域网地址**：`http://172.29.30.78:8094`

### 第二步：确保手机和电脑在同一网络

**当前网络状态**：
- 手机 IP：`172.29.30.78`
- 端口：`8094`

如果网络环境变化，请确保：
- 手机和电脑连接到同一个 WiFi
- 或者使用 USB 网络转发：`adb reverse tcp:8094 tcp:8094`

### 第三步：在 web-chat 中输入 Token

1. 打开电脑浏览器访问：~~`http://localhost:5174/`~~
2. 点击右上角的 **配置按钮**（会弹出配置对话框）
3. **只需输入 Bearer Token**：
   ```
   d922b959dc3c45bdbab912b6fff2d5e2
   ```
4. 点击 **连接网页聊天**

**注意**：服务器地址已自动配置为 `http://172.29.30.78:8094/`，无需手动输入！

### 第四步：验证连接

- 如果配置正确，web-chat 会显示当前的角色卡和历史对话
- 可以开始发送消息进行测试

## 常见问题排查

### 1. 仍然显示 404 错误

**检查项**：
- 确认 Android 应用中的 External HTTP API 服务已启动
- 确认端口号正确（默认 8094）
- 确认 Bearer Token 完全一致（区分大小写）

### 2. 网络无法连接

**检查项**：
- 手机和电脑是否在同一网络
- 防火墙是否阻止了 8094 端口
- 尝试 ping 手机 IP：`ping 192.168.1.100`

### 3. 使用 USB 转发

如果 WiFi 连接不稳定，可以使用 USB 转发：

```bash
# 确保已安装 ADB 工具
adb devices  # 确认设备已连接
adb reverse tcp:8094 tcp:8094

# 然后在 web-chat 中使用地址：http://127.0.0.1:8094/
```

### 4. 查看 Android 日志

在 Android Studio 或命令行中查看应用日志：

```bash
adb logcat | grep -i "ExternalChat\|WebChat"
```

## 技术细节

### API 端点

web-chat 使用的主要 API 端点：

- `GET /api/web/bootstrap` - 获取应用初始状态
- `GET /api/web/chats` - 获取对话列表
- `POST /api/web/chats/{id}/messages/stream` - 发送消息（SSE 流式响应）
- `GET /api/web/character-selector` - 获取角色卡选择器
- `GET /api/web/model-selector` - 获取模型选择器

### 认证方式

所有 API 请求都需要在 HTTP Header 中包含：
```
Authorization: Bearer <your-token>
```

### 数据格式

所有请求和响应都使用 JSON 格式，除了流式响应使用 SSE（Server-Sent Events）。

## 安全提示

- Bearer Token 应该设置得足够复杂，避免被猜测
- 不要在公共网络上暴露此服务
- 使用完毕后建议在 Android 应用中关闭 External HTTP API 服务

## 开发调试

如果你需要修改 web-chat 代码：

```bash
cd /home/meizu/Documents/my_agent_projects/Operit/web-chat

# 开发模式（已启动）
npm run dev

# 构建生产版本
npm run build

# 同步到 Android assets
npm run sync:android-assets
```

构建后的文件会打包到 Android 应用的 assets 目录中。
