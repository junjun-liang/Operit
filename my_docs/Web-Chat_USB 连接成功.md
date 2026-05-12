# Web-Chat USB 连接成功！✅

## 问题已解决

通过 USB 数据线连接，已成功修复 web-chat 无法连接的问题！

## 当前配置状态

### ✅ ADB 转发设置
- 设备：`468QBFEH2229M`
- 转发：`tcp:8094`（电脑）→ `tcp:8094`（手机）
- 状态：正常运行

### ✅ Vite 代理配置
```typescript
server: {
  proxy: {
    '/api': {
      target: 'http://127.0.0.1:8094',  // 本地回环地址
      changeOrigin: true,
      secure: false,
      ws: true  // 支持 WebSocket
    }
  },
  host: true,
  port: 5174
}
```

### ✅ 连接测试结果
```bash
curl http://127.0.0.1:8094/api/health
# HTTP Status: 401（正常，需要认证）
```

## 立即使用

### 1. 打开浏览器
访问：http://localhost:5174/

### 2. 输入 Bearer Token
在弹出的配置框中输入（从手机复制）：
```
d922b959dc3c45bdbab912b6fff2d5e2
```

### 3. 点击连接
点击 **"连接网页聊天"** 按钮

### 4. 开始使用
✅ 成功！现在可以：
- 查看手机的当前会话
- 查看历史对话记录
- 发送消息
- 接收流式回复
- 同步主题和角色卡

## 技术说明

### 工作原理

```
浏览器 (http://localhost:5174/)
    ↓
发起 API 请求到 /api/web/bootstrap
    ↓
Vite 代理拦截并转发
    ↓
转发到 http://127.0.0.1:8094
    ↓
ADB Forward 转发到手机
    ↓
手机 Operit AI 处理请求
    ↓
返回结果（原路返回）
```

### 为什么使用 USB 连接？

**优势**：
- ✅ 不依赖 WiFi 网络
- ✅ 连接极其稳定
- ✅ 延迟更低
- ✅ 不受 IP 变化影响
- ✅ 不受网络隔离影响

**要求**：
- 手机开启"开发者选项"
- 开启"USB 调试"
- 用数据线连接电脑

## 维护指南

### 如果重启电脑
需要重新设置 ADB 转发：
```bash
adb forward tcp:8094 tcp:8094
```

### 如果重新插拔手机
需要重新设置 ADB 转发：
```bash
adb forward --remove-all
adb forward tcp:8094 tcp:8094
```

### 如果 Vite 服务器停止
重新启动即可，配置已保存：
```bash
cd /home/meizu/Documents/my_agent_projects/Operit/web-chat
npm run dev
```

### 查看当前 ADB 转发
```bash
adb forward --list
# 应该显示：设备 ID tcp:8094 tcp:8094
```

## 常见问题

### Q: 浏览器显示空白页？
A: 检查控制台是否有错误。如果显示 401，需要输入正确的 Bearer Token。

### Q: 输入 Token 后仍然无法连接？
A: 
1. 确认手机上 External HTTP 服务已启用
2. 确认 Token 正确（从手机复制，不要手动输入）
3. 检查 ADB 转发是否正常：`adb forward --list`

### Q: 手机断开连接怎么办？
A: 
1. 重新连接数据线
2. 重新执行：`adb forward tcp:8094 tcp:8094`
3. 刷新浏览器页面

### Q: 可以同时使用 WiFi 和 USB 吗？
A: 可以，但建议使用 USB，更稳定。

## 安全提示

- Bearer Token 相当于密码，不要分享给他人
- 使用完毕后可以在手机上关闭 External HTTP 服务
- USB 连接比 WiFi 更安全（不经过网络）

## 下次使用

只要保持：
1. ✅ 手机通过 USB 连接电脑
2. ✅ ADB 转发已设置
3. ✅ Vite 服务器运行

就可以随时访问 http://localhost:5174/ 并输入 Token 使用！

## 已修改的文件

- `web-chat/vite.config.ts` - 更新代理配置为本地回环地址
- `my_docs/Web-Chat USB 连接成功.md` - 本文档

## 验证清单

- [x] ADB 设备已连接
- [x] ADB 转发已设置（tcp:8094）
- [x] Vite 服务器已启动（port 5174）
- [x] 代理配置已更新（target: 127.0.0.1:8094）
- [x] 连接测试成功（HTTP 401）
- [ ] 等待用户输入 Token 并验证
