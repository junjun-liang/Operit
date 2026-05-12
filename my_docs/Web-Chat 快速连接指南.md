# Web-Chat 快速连接指南

## 问题已解决 ✅

通过分析你的手机配置，我已经修复了连接问题！

## 你的手机配置

- **服务状态**：✅ 运行中
- **监听地址**：`0.0.0.0:8094`
- **局域网 IP**：`http://172.29.30.78:8094`
- **Bearer Token**：`d922b959dc3c45bdbab912b6fff2d5e2`

## 问题原因

web-chat 使用相对路径调用 API（如 `/api/web/bootstrap`），默认会访问 `http://localhost:8094`，但实际服务运行在手机上（`172.29.30.78:8094`）。

## 解决方案

已修改 `vite.config.ts`，添加了 API 代理配置：
```typescript
server: {
  proxy: {
    '/api': {
      target: 'http://172.29.30.78:8094',
      changeOrigin: true,
      secure: false
    }
  }
}
```

现在所有 `/api/*` 请求都会自动转发到手机！

## 立即使用

### 1. 打开浏览器
访问：http://localhost:5174/

### 2. 输入 Token
在弹出的配置框中粘贴：
```
d922b959dc3c45bdbab912b6fff2d5e2
```

### 3. 点击连接
点击 **"连接网页聊天"** 按钮

### 4. 开始使用
✅ 成功！现在可以看到手机的聊天记录、主题和流式回复

## 注意事项

### 网络要求
- 手机和电脑必须在**同一 WiFi 网络**
- 如果网络环境变化（IP 改变），需要更新代理配置

### 如果连接失败

**检查清单**：
1. ✅ 手机服务是否运行（截图显示已运行）
2. ✅ 手机 IP 是否正确（当前：172.29.30.78）
3. ✅ Token 是否正确（当前：d922b959dc3c45bdbab912b6fff2d5e2）
4. ✅ 防火墙是否允许 8094 端口

**备用方案 - USB 转发**：
```bash
adb reverse tcp:8094 tcp:8094
```
然后在 web-chat 中使用 `http://127.0.0.1:8094/`

## 技术细节

### 修改的文件
- `web-chat/vite.config.ts` - 添加了 Vite 开发服务器代理配置

### 工作原理
1. 浏览器访问 `http://localhost:5174/`
2. 前端发起请求到 `/api/web/bootstrap`
3. Vite 代理拦截请求，转发到 `http://172.29.30.78:8094/api/web/bootstrap`
4. 手机处理请求并返回结果
5. Vite 将结果返回给浏览器

### 优势
- ✅ 无需修改前端代码
- ✅ 无需手动配置服务器地址
- ✅ 只需输入 Token 即可连接
- ✅ 开发调试更方便

## 下次启动

如果重启了开发服务器，配置仍然有效：
```bash
cd /home/meizu/Documents/my_agent_projects/Operit/web-chat
npm run dev
```

然后重复上述步骤输入 Token 即可。

## 安全提示

- Bearer Token 相当于密码，不要分享给他人
- 此配置仅在开发环境使用
- 生产环境需要打包到 Android assets
