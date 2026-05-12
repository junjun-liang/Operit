# Magisk 完整安装指南

> 本指南详细介绍如何在 Android 设备上完整安装 Magisk，解决"超级用户功能灰色无法使用"的问题。

**适用场景**：
- ✅ Magisk App 已安装，但显示"无法获取"
- ✅ 超级用户功能灰色，无法使用
- ✅ 需要完整安装 Magisk 环境
- ✅ 系统更新后 Magisk 失效

---

## 目录

1. [准备工作](#1-准备工作)
2. [步骤 1：下载 Magisk](#2-步骤 1 下载 magisk)
3. [步骤 2：获取 boot 镜像](#3-步骤 2 获取 boot 镜像)
4. [步骤 3：修补 boot 镜像](#4-步骤 3 修补 boot 镜像)
5. [步骤 4：刷入修补后的镜像](#5-步骤 4 刷入修补后的镜像)
6. [步骤 5：验证安装](#6-步骤 5 验证安装)
7. [安装后配置](#7-安装后配置)
8. [常见问题](#8-常见问题)

---

## 1. 准备工作

### 1.1 必要条件

- ✅ 手机 Bootloader 已解锁
- ✅ 电脑（Windows/Linux/Mac）
- ✅ USB 数据线
- ✅ 手机电量 > 50%
- ✅ 备份重要数据（刷机有风险）

### 1.2 安装工具

#### Windows 用户

1. **下载 ADB 和 Fastboot 工具**
   - 下载地址：[Minimal ADB and Fastboot](https://forum.xda-developers.com/t/official-minimal-adb-and-fastboot-2-9-18.2317790/)
   - 或使用 [15 秒 ADB 安装器](https://forum.xda-developers.com/t/tool-minimal-adb-and-fastboot-2-9-18.2317790/)

2. **安装 USB 驱动**
   - 下载对应品牌的 USB 驱动
   - 或使用 [Universal ADB Driver](http://adb.clockworkmod.com/)

#### Linux 用户

```bash
# Ubuntu/Debian
sudo apt install adb fastboot

# Fedora
sudo dnf install android-tools

# Arch Linux
sudo pacman -S android-tools
```

#### Mac 用户

```bash
# 使用 Homebrew
brew install --cask android-platform-tools
```

### 1.3 开启开发者选项

1. **进入手机设置**
2. **关于手机** → 连续点击 **"版本号"** 7 次
3. **返回设置** → **系统和更新** → **开发者选项**
4. **开启以下选项**：
   - ✅ USB 调试
   - ✅ OEM 解锁（如果已解锁可忽略）

---

## 2. 步骤 1：下载 Magisk

### 方式 A：通过 GitHub 下载（推荐）

1. **访问 Magisk GitHub Releases**
   ```
   https://github.com/topjohnwu/Magisk/releases
   ```

2. **下载最新版本**
   - 找到最新的 Release（如 v30.6）
   - 下载 `Magisk-v30.6.apk` 文件
   - 将文件传输到手机

3. **安装 Magisk App**
   ```bash
   # 通过 ADB 安装
   adb install Magisk-v30.6.apk
   ```
   
   或者直接在手机上安装 APK

### 方式 B：通过 Magisk App 下载

1. **打开 Magisk App**
2. **点击设置图标**（右上角）
3. **下载通道** → 选择 **Stable**（稳定版）或 **Beta**（测试版）
4. **返回主页**
5. **点击"安装"按钮** → 自动下载并安装

---

## 3. 步骤 2：获取 boot 镜像

### 方式 A：从官方固件提取（推荐）

#### 3.1 下载官方固件

1. **查找固件下载站点**（根据手机品牌）：
   - **小米**：http://miuirom.com/ 或 https://xiaomirom.com/
   - **华为/荣耀**：https://professorjtj.github.io/
   - **OPPO/Realme**：https://colorosrom.com/
   - **vivo**：https://vivofirmwareupdater.com/
   - **三星**：https://samfw.com/ 或 https://sammobile.com/
   - **一加**：https://www.oneplus.com/support/softwareupgrade
   - **Google Pixel**：https://developers.google.com/android/ota

2. **下载与当前系统版本一致的固件**
   - 进入手机设置 → 关于手机 → 查看版本号
   - 下载相同版本的固件包

#### 3.2 提取 boot.img

**小米/红米手机**：
```bash
# 固件包通常是 .zip 格式
# 解压后直接找到 boot.img
unzip xiaomi_rom.zip
# boot.img 在解压后的目录中
```

**华为/荣耀手机**：
```bash
# 固件包可能是 .app 或 .zip
# 需要使用 Huawei Update Extractor 工具
# 下载地址：https://github.com/AndroPlus-org/update_extractor
python3 update_extractor.py firmware.zip --target boot
```

**OPPO/Realme/一加手机**：
```bash
# 固件包通常是 .ozip 格式
# 需要解密工具：https://github.com/bkerler/oppo_ozip_decrypt
python3 oppo_ozip_decrypt.py firmware.ozip
# 解密后提取 boot.img
```

**三星手机**：
```bash
# 三星固件包含 5 个文件：AP、BL、CP、CSC、HOME_CSC
# 需要提取 AP 文件（包含 boot 镜像）
# AP_xxxx.tar.md5 就是需要的文件
```

**Google Pixel**：
```bash
# 下载工厂镜像（factory image）
# 解压后找到 boot.img 文件
unzip factory_image.zip
tar -xf image-*.tar
# boot.img 在解压后的目录中
```

### 方式 B：从当前设备提取（需要 Root）

如果手机已经 Root，可以直接提取：

```bash
# 需要 TWRP 或 Root 权限
adb shell
su

# 提取 boot 镜像
dd if=/dev/block/bootdevice/by-name/boot /sdcard/boot.img

# 或者
cat /dev/block/mmcblk0pXX > /sdcard/boot.img
# XX 是 boot 分区编号，因设备而异
```

### 方式 C：使用第三方 Recovery

如果已安装 TWRP：

1. **进入 TWRP Recovery**
2. **备份** → 选择 **Boot**
3. **备份完成后**，在 `/sdcard/TWRP/BACKUPS/` 找到 boot 镜像

---

## 4. 步骤 3：修补 boot 镜像

### 4.1 传输 boot.img 到手机

```bash
# 将 boot.img 传输到手机
adb push boot.img /sdcard/Download/
```

### 4.2 使用 Magisk 修补

1. **打开 Magisk App**

2. **点击 Magisk 卡片旁边的"安装"按钮**
   ```
   如果显示"无法获取"，说明 Magisk 环境未安装
   但仍然可以点击"安装"按钮进行修补
   ```

3. **选择修补方式**
   
   点击 **"选择并修补文件"**
   
   其他选项说明：
   - ❌ **自动安装（推荐）**：需要已 Root 的设备
   - ❌ ** inactive 插槽后安装**：A/B 分区设备使用
   - ✅ **选择并修补文件**：手动选择 boot 镜像（推荐）

4. **选择 boot.img 文件**
   ```
   浏览到 /sdcard/Download/
   选择 boot.img
   ```

5. **等待修补完成**
   ```
   Magisk 会自动修补 boot 镜像
   完成后会显示"已修补"
   文件保存在：/sdcard/Download/magisk_patched_[随机字符].img
   ```

6. **复制修补后的文件到电脑**
   ```bash
   adb pull /sdcard/Download/magisk_patched_*.img ~/Downloads/
   ```

---

## 5. 步骤 4：刷入修补后的镜像

### 5.1 进入 Fastboot 模式

**方式 A：通过 ADB 命令**
```bash
adb reboot bootloader
```

**方式 B：手动按键**
```
1. 关机
2. 按住 音量- + 电源键
3. 出现 Fastboot 图标后松手
```

### 5.2 验证设备连接

```bash
# 在电脑上执行
fastboot devices

# 应该显示设备序列号
# 例如：ABC123DEF456    fastboot
```

如果未显示设备：
- 检查 USB 驱动是否安装
- 尝试更换 USB 端口
- 使用原装数据线

### 5.3 刷入修补后的 boot 镜像

```bash
# 进入 fastboot 目录
cd ~/Downloads/  # Linux/Mac
# 或
cd %USERPROFILE%\Downloads  # Windows

# 刷入镜像
fastboot flash boot magisk_patched_[随机字符].img
```

**示例**：
```bash
fastboot flash boot magisk_patched_2B3C4D5E.img
```

**输出示例**：
```
Sending 'boot_a' (65536 KB)                      OK [  1.234s]
Writing 'boot_a'                                 OK [  0.567s]
Finished. Total time: 1.801s
```

### 5.4 A/B 分区设备

如果你的手机是 A/B 分区（如 Pixel、一加等），需要刷入两个分区：

```bash
# 刷入 slot a
fastboot flash boot_a magisk_patched_[随机字符].img

# 刷入 slot b
fastboot flash boot_b magisk_patched_[随机字符].img

# 切换当前 slot（可选）
fastboot --set-active=a
```

### 5.5 三星设备特殊处理

三星设备使用 Odin 工具刷入：

1. **下载 Odin**
   - 下载地址：https://odindownload.com/

2. **进入 Download 模式**
   ```
   关机 → 按住 音量- + 音量+ → 插入 USB 线
   ```

3. **打开 Odin**
   - 手机连接电脑
   - Odin 应该显示"Added!"

4. **加载 AP 文件**
   - 点击 **AP** 按钮
   - 选择修补后的 AP 文件

5. **开始刷入**
   - 点击 **Start**
   - 等待完成

### 5.6 重启设备

```bash
# 刷入完成后重启
fastboot reboot
```

或者长按电源键手动重启。

---

## 6. 步骤 5：验证安装

### 6.1 检查 Magisk 状态

1. **打开 Magisk App**

2. **查看主页状态卡片**

   **✅ 成功的标志**：
   ```
   Magisk
   当前：30.6(30600)     ← 显示版本号
   Zygisk: 是/否
   Ramdisk: 是
   ```

   **❌ 失败的标志**：
   ```
   Magisk
   当前：无法获取        ← 仍然显示无法获取
   Zygisk: 否
   Ramdisk: 是
   ```

3. **检查底部导航栏**
   - ✅ **主页**：正常显示
   - ✅ **超级用户**：图标可用（不再灰色）
   - ✅ **日志**：可以查看
   - ✅ **模块**：可以管理模块

### 6.2 验证 Root 权限

#### 方式 A：使用 Root Checker

1. **下载 Root Checker App**
   - Google Play: https://play.google.com/store/apps/details?id=com.joeykrim.rootcheck

2. **打开 Root Checker**
3. **点击"验证 Root"**
4. **授予 Root 权限**（Magisk 会弹出授权对话框）
5. **查看结果**：应该显示"Root 已正确工作"

#### 方式 B：使用 ADB 命令

```bash
# 连接设备
adb shell

# 切换到 root
su

# 检查用户 ID
id

# 应该输出：uid=0(root) gid=0(root) ...
```

#### 方式 C：在 Operit AI 中测试

1. **打开 Operit AI**
2. **进入 AI 对话**
3. **输入**：`执行 root 命令 pm list packages`
4. **如果返回应用列表**，说明 Root 生效

### 6.3 检查超级用户功能

1. **打开 Magisk App**
2. **点击底部"超级用户"图标**
3. **应该看到应用列表**
4. **找到"Operit AI"** 或 **"com.ai.assistance.operit"**
5. **开启右侧开关**

---

## 7. 安装后配置

### 7.1 启用 Zygisk（可选）

Zygisk 是 Magisk 的新一代 Zygote 注入框架：

1. **打开 Magisk 设置**
2. **找到"Zygisk"**
3. **开启开关**
4. **重启手机**

**注意**：
- Zygisk 可能导致某些应用检测到 Root
- 如果需要隐藏 Root，建议开启
- 如果遇到兼容性问题，可以关闭

### 7.2 配置超级用户

1. **打开 Magisk App**
2. **点击"超级用户"**
3. **点击右上角"⋮" → "设置"**
4. **配置以下选项**：
   - ✅ **超级用户访问通知**：开启
   - ✅ **超级用户访问日志**：开启
   - ✅ **自动拒绝超时**：10 秒
   - ✅ **默认通知模式**：通知

### 7.3 配置排除列表（遵守排除列表）

排除列表用于隐藏 Root，防止某些应用检测到：

1. **打开 Magisk 设置**
2. **点击"配置排除列表"**
3. **添加需要隐藏 Root 的应用**：
   - 银行应用
   - 支付应用
   - 游戏（有反作弊的）
   - 工作应用

4. **关闭"遵守排除列表"**（推荐）
   - 关闭后，排除列表中的应用无法使用 Root
   - 但仍然可以正常使用

**注意**：
- 确保 **Operit AI 不在排除列表中**
- 如果在，取消勾选

### 7.4 安装 Magisk 模块（可选）

Magisk 模块可以扩展功能：

#### 常用模块推荐

1. **MagiskHide Props Config**
   - 修改设备指纹
   - 绕过 SafetyNet 检测

2. **Universal SafetyNet Fix**
   - 修复 SafetyNet 认证
   - 通过 Google Pay 认证

3. **Audio Modification Library**
   - 修改系统音效
   - 提升音质

4. **Greenify4Magisk**
   - 绿色守护
   - 优化电池续航

#### 安装模块

1. **下载模块 zip 文件**
2. **打开 Magisk App**
3. **进入"模块"**
4. **点击"从本地安装"**
5. **选择模块 zip 文件**
6. **重启手机**

### 7.5 为 Operit AI 授予 Root 权限

1. **打开 Operit AI App**
2. **进入"权限授予"页面**
3. **点击"Root 访问权限"的刷新按钮**
4. **Magisk 弹出授权对话框**
5. **选择"允许"** 或 **"始终允许"**
6. **状态变为"已授权"**（绿色）

---

## 8. 常见问题

### Q1: 刷入后无法开机（卡在开机动画）

**解决方案**：

1. **进入 Fastboot 模式**
   ```bash
   # 长按 音量- + 电源键 10 秒
   ```

2. **刷回原始 boot 镜像**
   ```bash
   fastboot flash boot boot_original.img
   fastboot reboot
   ```

3. **重新尝试安装**
   - 确认 boot 镜像正确
   - 确认修补过程无误

### Q2: 刷入后 Magisk 仍然显示"无法获取"

**解决方案**：

1. **检查是否刷入正确的分区**
   ```bash
   # A/B 分区设备需要刷入两个分区
   fastboot flash boot_a magisk_patched.img
   fastboot flash boot_b magisk_patched.img
   ```

2. **清除 Magisk App 数据**
   ```
   设置 → 应用管理 → Magisk → 存储 → 清除数据
   ```

3. **重新安装 Magisk App**
   - 卸载当前版本
   - 重新安装最新 APK

4. **重启手机**

### Q3: 点击"安装"按钮没反应

**解决方案**：

1. **检查下载通道设置**
   ```
   Magisk 设置 → 下载通道 → 选择 Stable
   ```

2. **手动下载 Magisk 安装包**
   - 访问 GitHub Releases
   - 下载最新 APK
   - 安装到手机

3. **使用"选择并修补文件"方式**

### Q4: 超级用户图标仍然是灰色

**解决方案**：

1. **确认 Magisk 已正确安装**
   - 主页应该显示版本号
   - Ramdisk 应该是"是"

2. **重启 Magisk App**
   - 强制停止 App
   - 重新打开

3. **重启手机**

4. **如果仍然灰色，重新安装**
   - 刷回原始 boot 镜像
   - 重新执行完整安装流程

### Q5: Magisk 授权对话框不弹出

**解决方案**：

1. **检查超级用户设置**
   ```
   Magisk → 超级用户 → 设置
   确保"超级用户访问通知"已开启
   ```

2. **清除 Magisk 数据**
   ```
   设置 → 应用管理 → Magisk → 存储 → 清除数据
   ```

3. **重启手机**

4. **手动触发授权**
   - 打开 Operit AI
   - 尝试执行需要 Root 的命令
   - Magisk 应该会弹出对话框

### Q6: 某些应用检测到 Root

**解决方案**：

1. **开启 Zygisk**
   ```
   Magisk 设置 → Zygisk → 开启
   重启手机
   ```

2. **启用隐藏**
   ```
   Magisk 设置 → 遵守排除列表 → 开启
   配置排除列表 → 添加检测到 Root 的应用
   ```

3. **安装隐藏模块**
   - MagiskHide Props Config
   - Universal SafetyNet Fix

4. **重启手机**

### Q7: OTA 更新后 Magisk 失效

**解决方案**：

1. **不要重启手机**（如果已经重启，需要重新修补）

2. **在 Magisk App 中操作**
   ```
   点击"安装" → 选择"安装到 inactive 插槽 (OTA 后)"
   重启手机
   ```

3. **如果已经重启，重新执行完整安装流程**

### Q8: 刷入时提示"分区大小不匹配"

**解决方案**：

1. **确认 boot 镜像来源正确**
   - 必须是与当前系统版本一致的固件
   - 不同版本的 boot 镜像可能不兼容

2. **尝试使用 recovery 模式刷入**
   ```bash
   adb reboot recovery
   # 在 TWRP 中刷入修补后的镜像
   ```

3. **使用 Odin（三星设备）**

---

## 附录

### A. 各品牌手机进入 Fastboot 方法

| 品牌 | 进入方法 |
|------|----------|
| 小米/红米 | 关机 → 音量- + 电源键 |
| 华为/荣耀 | 关机 → 音量- + 电源键 |
| OPPO/Realme | 关机 → 音量- + 电源键 |
| vivo | 关机 → 音量- + 电源键 |
| 一加 | 关机 → 音量+ + 电源键 |
| Google Pixel | 关机 → 音量- + 电源键 |
| 三星 | 关机 → 音量- + 音量+ → 插入 USB |
| 索尼 | 关机 → 音量+ → 插入 USB |

### B. 常用命令速查

```bash
# 重启到 bootloader
adb reboot bootloader

# 重启到 recovery
adb reboot recovery

# 重启到 download 模式（三星）
adb reboot download

# 查看设备列表
fastboot devices

# 查看分区信息
fastboot getvar all

# 刷入 boot 镜像
fastboot flash boot boot.img

# 重启设备
fastboot reboot

# 重启到系统
fastboot reboot bootloader

# 切换 active 分区（A/B 设备）
fastboot --set-active=a
```

### C. 资源链接

- **Magisk 官方 GitHub**: https://github.com/topjohnwu/Magisk
- **Magisk 下载**: https://github.com/topjohnwu/Magisk/releases
- **Magisk 使用教程**: https://topjohnwu.github.io/Magisk/
- **XDA Developers**: https://forum.xda-developers.com/
- **TWRP Recovery**: https://twrp.me/
- **KernelSU**: https://kernelsu.org/

### D. 固件下载站点

- **小米**：https://xiaomirom.com/
- **华为**：https://professorjtj.github.io/
- **OPPO**：https://colorosrom.com/
- **vivo**：https://vivofirmwareupdater.com/
- **三星**：https://samfw.com/
- **一加**：https://www.oneplus.com/support/softwareupgrade
- **Google Pixel**：https://developers.google.com/android/ota

---

## 总结

### 安装流程

```
1. 下载 Magisk App
   ↓
2. 获取官方 boot 镜像
   ↓
3. 使用 Magisk 修补 boot 镜像
   ↓
4. Fastboot 刷入修补后的镜像
   ↓
5. 重启并验证安装
   ↓
6. 配置超级用户权限
```

### 成功标志

- ✅ Magisk 主页显示版本号
- ✅ 超级用户图标可用（不再灰色）
- ✅ 可以为应用授予 Root 权限
- ✅ Root Checker 验证通过

### 注意事项

- ⚠️ 刷机有风险，操作需谨慎
- ⚠️ 备份重要数据
- ⚠️ 确保电量充足
- ⚠️ 使用与系统版本一致的固件

---

**文档版本**: v1.0  
**最后更新**: 2026-05-12  
**参考资源**: 
- [Magisk 官方文档](https://topjohnwu.github.io/Magisk/)
- [XDA Developers](https://forum.xda-developers.com/)
