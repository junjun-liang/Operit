# Operit AI Root 权限检测问题解决指南

## 问题现象

手机已经 Root，但 Operit AI App 提示：
- ❌ "设备未 Root，无法获取 Root 权限"
- ❌ "Root 访问权限：未授权"

## 根本原因分析

通过分析 [`RootAuthorizer.kt`](file:///home/meizu/Documents/my_agent_projects/Operit/app/src/main/java/com/ai/assistance/operit/core/tools/system/RootAuthorizer.kt) 和 [`RootShellExecutor.kt`](file:///home/meizu/Documents/my_agent_projects/Operit/app/src/main/java/com/ai/assistance/operit/core/tools/system/shell/RootShellExecutor.kt) 代码，发现问题可能出在以下几个环节：

### 1. Root 检测流程

App 使用**多层检测机制**：

```
检测顺序：
1. libsu 检测 (Shell.isAppGrantedRoot())
   ↓ 失败
2. KernelSU 检测 (su --version)
   ↓ 失败  
3. 文件系统检测 (/system/bin/su 等)
   ↓ 失败
4. which su 命令检测
   ↓ 失败
5. 结论：设备未 Root
```

### 2. 可能的失败点

#### 失败点 A: libsu 检测失败
**原因**:
- Magisk 没有授予 Operit AI Root 权限
- libsu 库与当前 Root 管理工具不兼容
- MagiskHide/Zygisk 隐藏了 Root

#### 失败点 B: KernelSU 检测失败
**原因**:
- 使用的不是 KernelSU
- `su --version` 命令输出不包含 "KernelSU"
- exec 模式配置不正确

#### 失败点 C: 文件系统检测失败
**原因**:
- su 文件不在常见路径
- 使用了非标准的 Root 管理工具

#### 失败点 D: 初始化时机问题
**原因**:
- App 启动时 Root 服务还未完全就绪
- 需要手动触发重新检测

## 解决方案

### 方案一：检查 Magisk 授权（最常见）

#### 1. 打开 Magisk 管理器
在手机桌面上找到并打开 Magisk App

#### 2. 检查超级用户授权
1. 点击底部的 **"超级用户"** 图标（面具图标）
2. 在列表中找到 **"Operit AI"** 或 **"com.ai.assistance.operit"**
3. 如果没有找到，点击右上角 **"⋮"** → **"刷新"**
4. 如果还是没有，继续下一步

#### 3. 手动触发授权请求
1. 打开 Operit AI App
2. 进入 **"权限授予"** 页面
3. 点击 **"Root 访问权限"** 旁边的刷新按钮
4. 此时 Magisk 应该会弹出授权对话框
5. 点击 **"允许"** 或 **"始终允许"**

#### 4. 如果 Magisk 没有弹出对话框
1. 打开 Magisk 设置（右上角齿轮）
2. 开启 **"超级用户访问通知"**
3. 重启 Operit AI App
4. 再次尝试使用需要 Root 的功能

### 方案二：检查 Magisk 配置

#### 1. 关闭 Zygisk（可能导致检测问题）
1. 打开 Magisk 设置
2. 找到 **"Zygisk"**
3. **关闭** Zygisk
4. 重启手机

#### 2. 检查遵守排除列表
1. 打开 Magisk 设置
2. 找到 **"遵守排除列表"**
3. **关闭** 此选项
4. 或者确保 Operit AI **不在**排除列表中

#### 3. 配置排除列表
1. 点击 **"配置排除列表"**
2. 找到 **Operit AI**
3. **取消勾选**（确保不在排除列表中）
4. 重启手机

### 方案三：检查 Root 执行模式

Operit AI 支持两种 Root 命令执行模式：

#### libsu 模式（默认）
- 使用 libsu 库执行 Root 命令
- 适用于大多数 Magisk 环境

#### exec 模式
- 使用传统 `su -c` 命令执行
- 适用于 KernelSU 或其他 Root 管理工具

#### 如何切换模式
1. 打开 Operit AI
2. 进入 **设置** → **权限设置**
3. 找到 **Root 命令执行模式**
4. 尝试切换模式：
   - **自动**（推荐）
   - **强制 libsu**
   - **强制 exec**
5. 重启 App

### 方案四：手动检查 Root 状态

#### 1. 通过 ADB 检查
```bash
# 连接到设备
adb shell

# 尝试切换到 root
su

# 如果提示符从 $ 变成 #，说明 Root 成功
# 如果显示 "Permission denied"，说明未授权
```

#### 2. 检查 su 命令路径
```bash
adb shell
which su

# 应该输出类似：/sbin/su 或 /system/xbin/su
```

#### 3. 检查 su 版本
```bash
adb shell
su --version

# Magisk 会显示版本号
# KernelSU 会显示 "KernelSU"
```

### 方案五：使用 Shizuku 作为替代

如果 Root 权限确实无法获取，可以使用 **Shizuku** 作为替代方案：

#### 1. 安装 Shizuku
1. 在 Google Play 或 GitHub 下载 Shizuku
2. 安装并打开 Shizuku

#### 2. 启动 Shizuku
**方式 A：无线调试（推荐）**
```bash
# 在开发者选项中开启"无线调试"
# 点击"使用配对码配对设备"
# 按照 Shizuku 提示完成配对和启动
```

**方式 B：ADB 启动**
```bash
adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh
```

#### 3. 在 Operit AI 中使用 Shizuku
1. 打开 Operit AI
2. 进入 **权限授予** 页面
3. 点击 **"Shizuku 权限"** 的刷新按钮
4. 确保 Shizuku 正在运行
5. 授予 Operit AI 使用 Shizuku 的权限

### 方案六：检查 App 初始化

如果上述方法都无效，可能是 App 初始化时机问题：

#### 1. 清除 App 数据
1. 设置 → 应用管理 → Operit AI
2. 存储 → 清除数据
3. 重新打开 App

#### 2. 重新授予权限
1. 打开 Operit AI
2. 进入 **权限授予** 页面
3. 依次重新授予：
   - ✅ 存储权限
   - ✅ 悬浮窗权限
   - ✅ 电池优化豁免
   - ✅ 位置权限
   - ✅ Operit 终端
4. 最后尝试 **Root 访问权限**

#### 3. 查看日志排查
```bash
# 连接设备并查看日志
adb logcat | grep -i "RootAuthorizer\|RootShellExecutor"

# 或者查看所有错误
adb logcat | grep -i "operit"
```

## 完整排查流程

按照以下顺序排查问题：

### 第一步：确认 Root 状态
```bash
adb shell
su
id

# 应该输出：uid=0(root) gid=0(root) ...
```

### 第二步：检查 Magisk 授权
- ✅ 打开 Magisk → 超级用户
- ✅ 确认 Operit AI 在列表中
- ✅ 确保已开启授权开关

### 第三步：检查 Magisk 配置
- ❌ 关闭 Zygisk
- ❌ 关闭遵守排除列表
- ✅ 确保 Operit AI 不在排除列表中

### 第四步：重启和重试
1. 重启手机
2. 打开 Magisk，确认服务正常运行
3. 打开 Operit AI
4. 进入权限授予页面
5. 点击 Root 权限刷新按钮

### 第五步：切换 Root 执行模式
1. 设置 → 权限设置 → Root 命令执行模式
2. 尝试切换到 **强制 exec**
3. 重启 App

### 第六步：使用 Shizuku 替代
如果 Root 确实无法使用，考虑使用 Shizuku 模式

## 特殊情况处理

### 情况 A：使用 KernelSU

如果你使用的是 KernelSU：

1. **确认 KernelSU 正常工作**
   ```bash
   adb shell
   su --version
   # 应该显示 KernelSU 相关信息
   ```

2. **在 Operit AI 中设置**
   - 设置 → 权限设置 → Root 命令执行模式
   - 选择 **强制 exec**
   - 重启 App

### 情况 B：使用 APatch

如果你使用的是 APatch：

1. **切换到 exec 模式**
   - APatch 可能需要使用 exec 模式
   - 设置 → 权限设置 → Root 命令执行模式 → 强制 exec

2. **检查授权**
   - 打开 APatch 管理器
   - 检查超级用户列表
   - 确保 Operit AI 已授权

### 情况 C：系统自带 Root（如 LineageOS）

某些 ROM 自带 Root 功能：

1. **启用 Root 访问**
   - 设置 → 系统和更新 → 开发者选项
   - 找到 **Root 访问**
   - 选择 **应用和 ADB**

2. **在 Operit AI 中设置**
   - 使用 **强制 exec** 模式

## 验证 Root 权限是否生效

### 方法 1：在 Operit AI 中测试

1. 打开 Operit AI
2. 进入 **AI 对话**
3. 输入："执行 root 命令 pm list packages"
4. 如果返回应用列表，说明 Root 生效

### 方法 2：使用超级管理员工具

```javascript
// 在 AI 对话中使用
await Tools.SuperAdmin.shell({
    command: 'id'
});
// 应该返回：uid=0(root) gid=0(root) ...
```

### 方法 3：查看权限状态

1. 打开 Operit AI
2. 进入 **权限授予** 页面
3. 检查 **"Root 访问权限"** 状态
4. 应该显示 **"已授权"**（绿色）

## 常见问题

### Q1: 为什么 ADB root 了但 App 检测不到？

**A**: `adb root` 只重启 ADB 守护进程为 root，不代表 App 有 Root 权限。App 需要通过 `su` 命令获取 Root 授权。

### Q2: Magisk 没有弹出授权对话框怎么办？

**A**: 
1. 检查 Magisk 设置中的"超级用户访问通知"
2. 清除 Magisk 数据后重试
3. 尝试使用 exec 模式

### Q3: 之前能用，突然不能用了？

**A**: 
1. 检查 Magisk 是否更新
2. 检查是否开启了 Zygisk 或排除列表
3. 重新授予 Root 权限

### Q4: 使用 Shizuku 和 Root 有区别吗？

**A**: 
- **Root**: 完全的系统权限，可以执行所有命令
- **Shizuku**: 系统级权限，但不能修改系统分区
- 推荐优先级：Root > Shizuku > 标准权限

## 技术细节

### Root 检测代码逻辑

```kotlin
// RootAuthorizer.kt 中的检测顺序
fun isDeviceRooted(): Boolean {
    // 1. libsu 检测
    if (Shell.isAppGrantedRoot() == true) return true
    
    // 2. KernelSU 检测
    if (checkKernelSu()) return true
    
    // 3. 文件系统检测
    val suPaths = ["/system/bin/su", "/system/xbin/su", ...]
    for (path in suPaths) {
        if (File(path).exists()) return true
    }
    
    // 4. which su 命令检测
    val process = Runtime.getRuntime().exec(arrayOf("which", "su"))
    if (process.waitFor() == 0) return true
    
    return false
}
```

### Root 权限获取流程

```kotlin
// requestRootPermission 流程
fun requestRootPermission(onResult: (Boolean) -> Unit) {
    if (useExecForCommands) {
        // exec 模式：通过 su -c 命令请求
        val process = Runtime.getRuntime().exec(su -c "echo granted")
        // 检查结果...
    } else {
        // libsu 模式：通过 Shell.getShell 请求
        Shell.getShell { shell ->
            val granted = shell.isRoot
            onResult(granted)
        }
    }
}
```

## 总结

**最常见的原因**：
1. ✅ Magisk 没有授予 Operit AI Root 权限
2. ✅ Zygisk 或排除列表隐藏了 Root
3. ✅ Root 执行模式不匹配

**最快的解决方案**：
1. 打开 Magisk → 超级用户 → 找到 Operit AI → 开启授权
2. 关闭 Zygisk 和遵守排除列表
3. 重启手机和 App

如果问题仍未解决，请提供：
- Root 管理工具类型（Magisk/KernelSU/APatch 等）
- Android 版本
- 日志输出（`adb logcat | grep -i "root"`）

---

**文档版本**: v1.0  
**最后更新**: 2026-05-12  
**参考代码**: 
- [RootAuthorizer.kt](file:///home/meizu/Documents/my_agent_projects/Operit/app/src/main/java/com/ai/assistance/operit/core/tools/system/RootAuthorizer.kt)
- [RootShellExecutor.kt](file:///home/meizu/Documents/my_agent_projects/Operit/app/src/main/java/com/ai/assistance/operit/core/tools/system/shell/RootShellExecutor.kt)
