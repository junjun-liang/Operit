# Web-Chat 连接问题排查与解决

## 当前状态

- ✅ Vite 开发服务器：正常运行（http://localhost:5174/）
- ❌ 手机连接：**ETIMEDOUT 172.29.30.78:8094**（连接超时）

## 问题诊断

### 可能原因

1. **手机 IP 已变化** - 手机的局域网 IP 可能不再是 `172.29.30.78`
2. **网络隔离** - 手机和电脑不在同一 WiFi 网络
3. **防火墙阻止** - 手机防火墙阻止了 8094 端口
4. **服务停止** - Android 应用中的 External HTTP 服务已停止

## 快速解决方案

### 方案一：检查并更新手机 IP（推荐）

#### 1. 在手机上查看当前 IP
打开 Operit AI → 外部 HTTP 调用 → 查看"当前检测到的局域网地址"

#### 2. 更新 Vite 代理配置
根据实际 IP 修改 `vite.config.ts`：

```typescript
server: {
  proxy: {
    '/api': {
      target: 'http://新的 IP:8094',  // 替换为实际 IP
      changeOrigin: true,
      secure: false
    }
  }
}
```

#### 3. 重启开发服务器
```bash
# 停止当前服务器（Ctrl+C）
# 然后重新启动
npm run dev
```

### 方案二：使用 USB 网络转发（最稳定）

如果 WiFi 连接不稳定，使用 USB 转发：

#### 1. 连接 USB 并启用调试
- 用数据线连接手机和电脑
- 确保手机已开启"开发者选项"和"USB 调试"

#### 2. 执行 ADB 转发命令
```bash
adb reverse tcp:8094 tcp:8094
```

#### 3. 修改 Vite 配置
```typescript
server: {
  proxy: {
    '/api': {
      target: 'http://127.0.0.1:8094',  // 改为本地回环地址
      changeOrigin: true,
      secure: false
    }
  }
}
```

#### 4. 重启开发服务器
```bash
npm run dev
```

### 方案三：临时禁用代理（直接访问手机）

如果不想用代理，可以直接访问手机：

#### 1. 在浏览器中打开
```
http://172.29.30.78:8094/
```

**注意**：这需要手机上的服务也提供静态文件服务（当前版本支持）

## 网络诊断命令

### 检查手机 IP
```bash
# Windows
ping 172.29.30.78

# Linux/Mac
ping 172.29.30.78
```

### 检查端口连通性
```bash
# Windows (PowerShell)
Test-NetConnection 172.29.30.78 -Port 8094

# Linux/Mac
nc -zv 172.29.30.78 8094
```

### 检查 ADB 连接
```bash
adb devices
```

## 验证步骤

### 1. 确认手机服务运行
在手机上查看：
- 外部 HTTP 服务：已启用 ✓
- 服务运行中，监听 0.0.0.0:8094 ✓
- 记下当前显示的局域网地址

### 2. 确认网络连通
```bash
# 替换为手机实际 IP
ping 手机 IP
```

### 3. 更新配置并重启
根据实际网络环境选择上述方案之一

### 4. 在浏览器中测试
访问 http://localhost:5174/ 并输入 Token

## 常见问题

### Q: 为什么 IP 会变？
A: 如果路由器使用 DHCP 动态分配 IP，手机重启或重新连接 WiFi 后 IP 可能变化。

### Q: 如何固定手机 IP？
A: 在路由器中设置静态 IP 分配，或在手机上配置静态 IP。

### Q: USB 转发有什么优势？
A: 
- 不依赖 WiFi 网络
- 连接更稳定
- 延迟更低
- 不需要知道手机 IP

## 联系支持

如果以上方法都无法解决，请提供：
1. 手机当前显示的局域网地址
2. `ping 手机 IP` 的结果
3. 手机和电脑是否在同一 WiFi 网络
4. 是否使用了 VPN 或代理软件
