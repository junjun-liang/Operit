# Subpack 模块设计思想与详细使用指南

## 目录

1. [模块概述](#1-模块概述)
2. [核心设计思想](#2-核心设计思想)
3. [架构与类图](#3-架构与类图)
4. [核心类详解](#4-核心类详解)
5. [关键流程](#5-关键流程)
6. [使用示例](#6-使用示例)
7. [最佳实践](#7-最佳实践)

---

## 1. 模块概述

`subpack` 模块是 Operit 项目的**包体编辑与签名工具集**，专注于 APK 和 EXE 文件的二次打包、资源替换、图标修改和数字签名。该模块不依赖外部命令行工具，完全基于 Java/Kotlin 生态的纯代码实现，可在 Android 设备上直接完成 APK 的解包、修改、重打包和签名全流程。

模块位于 `com.ai.assistance.operit.core.subpack` 包下，包含 5 个核心类：

| 类名 | 职责 |
|------|------|
| `ApkEditor` | APK 编辑的链式 API 封装 |
| `ApkReverseEngineer` | APK 底层逆向工程实现（AXML 解析、Zip 操作、签名） |
| `ExeEditor` | EXE 编辑的链式 API 封装 |
| `ExeIconChanger` | PE 文件图标更换实现 |
| `KeyStoreHelper` | 密钥库加载、验证与 Provider 管理 |

---

## 2. 核心设计思想

### 2.1 链式调用 API（Builder 模式）

`ApkEditor` 和 `ExeEditor` 采用**链式调用设计**，将复杂的文件编辑流程简化为流畅的 DSL 风格代码：

```kotlin
ApkEditor.fromAsset(context, "template.apk")
    .changePackageName("com.example.newapp")
    .changeAppName("新应用")
    .changeVersionName("1.0.0")
    .changeVersionCode("100")
    .changeIcon(iconBitmap)
    .withSignature(keystoreFile, password, alias, keyPassword)
    .setOutput(outputFile)
    .repackAndSignWithWebContent(webContentDir)
```

**设计优势**：
- 调用顺序自由，参数配置与执行分离
- 每个修改方法返回 `this`，支持连续链式调用
- 最终通过 `repackXxx()` 或 `process()` 触发实际执行

### 2.2 不落地解压（Stream Processing）

APK 重打包采用**不落地解压策略**：
- 直接读取原始 APK 的 Zip 条目流
- 修改后的内容直接写入新 Zip 输出流
- 无需在磁盘上创建中间解压目录
- 显著减少 I/O 开销和存储占用

### 2.3 底层二进制操作（AXML / PE）

- **AXML 解析**：使用 `pxb.android.axml` 库直接操作 Android 二进制 XML，避免转换为文本 XML
- **PE 文件解析**：手动解析 DOS 头、PE 头，定位资源表位置
- **Zip 精细控制**：使用 Apache Commons Compress 精确控制压缩方法（STORED/DEFLATED）和 CRC 校验

### 2.4 多格式密钥兼容

`KeyStoreHelper` 自动兼容多种密钥库格式：
- **PKCS12**（`.p12` / `.keystore`）
- **JKS**（`.jks`）
- 自动回退：PKCS12 失败时自动尝试 JKS
- 别名容错：指定别名不存在时自动使用第一个可用别名

### 2.5 资源替换策略

APK 图标替换采用**多分辨率自适应**：
- 根据路径中的 DPI 标识（`mdpi`/`hdpi`/`xhdpi`/`xxhdpi`/`xxxhdpi`）自动计算目标尺寸
- 支持 PNG / WEBP / JPEG 格式自适应
- 自动缩放源位图到目标尺寸

---

## 3. 架构与类图

```
┌─────────────────────────────────────────────────────────────┐
│  API Layer（链式调用入口）                                    │
│  ├─ ApkEditor          : APK 编辑链式 API                    │
│  └─ ExeEditor          : EXE 编辑链式 API                    │
├─────────────────────────────────────────────────────────────┤
│  Engine Layer（底层实现）                                     │
│  ├─ ApkReverseEngineer : APK 解包/修改/打包/签名/对齐        │
│  └─ ExeIconChanger     : PE 文件图标替换                     │
├─────────────────────────────────────────────────────────────┤
│  Utility Layer（工具支持）                                    │
│  └─ KeyStoreHelper     : 密钥库加载与 Provider 管理          │
└─────────────────────────────────────────────────────────────┘
```

**协作关系**：

```
ApkEditor ──uses──► ApkReverseEngineer ──uses──► KeyStoreHelper
   │                    │
   │                    ├─ AxmlReader/AxmlWriter (AXML 解析)
   │                    ├─ ZipArchiveOutputStream (Zip 操作)
   │                    ├─ ApkSigner (Google apksig 签名)
   │                    └─ zipalign-java (Zip 对齐)
   │
   └─ Bitmap (图标处理)

ExeEditor ──uses──► ExeIconChanger
   │                    ├─ RandomAccessFile (PE 解析)
   │                    └─ ByteBuffer (ICO 生成)
   │
   └─ Bitmap (图标处理)
```

---

## 4. 核心类详解

### 4.1 ApkEditor（APK 链式编辑器）

**定位**：面向业务层的 APK 编辑入口，提供流畅的链式 API。

**创建方式**：

```kotlin
// 从 assets 创建
val editor = ApkEditor.fromAsset(context, "templates/myapp.apk")

// 从 File 创建
val editor = ApkEditor.fromFile(context, apkFile)

// 从路径创建
val editor = ApkEditor.fromPath(context, "/path/to/app.apk")
```

**链式配置方法**：

| 方法 | 参数 | 说明 |
|------|------|------|
| `changePackageName()` | `String` | 修改 Android 包名 |
| `changeAppName()` | `String` | 修改应用名称（`application@android:label`） |
| `changeVersionName()` | `String` | 修改版本名称 |
| `changeVersionCode()` | `String` | 修改版本号（转为整数） |
| `changeIcon()` | `Bitmap` / `InputStream` | 修改应用图标 |
| `changeIconFromAsset()` | `String` | 从 assets 加载图标 |
| `withSignature()` | `File, String, String, String` | 配置签名密钥 |
| `setOutput()` | `File` / `String` | 设置输出文件路径 |

**执行方法**：

```kotlin
// 仅重打包（不签名）
val unsignedApk = editor.repackWithWebContent(webContentDir)

// 重打包并签名
val signedApk = editor.repackAndSignWithWebContent(webContentDir)
```

**资源清理**：

```kotlin
editor.cleanup()  // 回收 Bitmap 资源
```

---

### 4.2 ApkReverseEngineer（APK 逆向引擎）

**定位**：底层 APK 操作引擎，处理 Zip 流、AXML 修改、签名和对齐。

#### 4.2.1 快速重打包（`repackageApkWithWebContent`）

**核心流程**：

```
1. 创建临时未对齐 APK 文件
2. 打开原始 APK 的 ZipFile 读取流
3. 创建 ZipArchiveOutputStream 写入新 APK
4. 遍历原始条目：
   ├─ 跳过 META-INF/（旧签名）
   ├─ 跳过 assets/flutter_assets/assets/web_content/（旧 Web 内容）
   ├─ 替换图标条目（如果提供了新图标）
   ├─ 修改 AndroidManifest.xml（AXML 二进制修改）
   └─ 直接复制其他条目（保留压缩方法和元数据）
5. 添加新的 Web 内容目录到 assets/flutter_assets/assets/web_content/
6. 关闭 Zip 流
7. 执行 zipalign 对齐
8. 删除临时文件
```

**AXML 修改内容**：

| 属性 | 修改位置 | 说明 |
|------|----------|------|
| `package` | `manifest` 节点 | 包名替换，同时递归替换所有引用旧包名的属性 |
| `android:versionName` | `manifest` 节点 | 版本名称 |
| `android:versionCode` | `manifest` 节点 | 版本号（转为 `TYPE_INT_HEX`） |
| `android:label` | `application` 节点 | 应用名称 |

**包名引用替换策略**：
- 递归遍历 AXML 所有节点的所有属性
- 替换包含旧包名的字符串值
- **特殊保护**：`MainActivity` 的完整类名引用保持不变

#### 4.2.2 Zip 对齐（`zipalign`）

使用 `com.iyxan23.zipalignjava` 库进行对齐：
- 普通文件：4 字节对齐
- `.so` 文件：16KB 对齐（适配 Android 15 的 16KB 页面大小）

#### 4.2.3 APK 签名（`signApk`）

**签名流程**：

```
1. 验证输入文件存在
2. 尝试 PKCS12 格式加载密钥库
   ├─ 成功 → 执行签名
   └─ 失败 → 尝试 JKS 格式
3. 加载密钥库后：
   ├─ 获取别名列表
   ├─ 指定别名不存在时，自动使用第一个可用别名
   └─ 获取私钥和证书链
4. 使用 Google ApkSigner 进行 v1/v2/v3 签名
5. 输出签名后的 APK
```

**密钥库加载容错**：
- 自动注册 BouncyCastle Provider（PKCS12 需要）
- 别名不存在时自动回退到第一个别名
- 详细的错误日志和本地化错误消息

#### 4.2.4 Zip 条目精细控制

**压缩方法决策**：

```kotlin
private fun shouldStoreWithoutCompression(filePath: String): Boolean = when {
    filePath.endsWith("AndroidManifest.xml") -> true   // 必须不压缩
    filePath.endsWith("resources.arsc") -> true        // 必须不压缩
    filePath.endsWith(".dex") -> true                  // 必须不压缩
    filePath.startsWith("META-INF/") && 
        (filePath.endsWith(".SF") || 
         filePath.endsWith(".RSA") || 
         filePath.endsWith(".DSA") || 
         filePath == "META-INF/MANIFEST.MF") -> true   // 签名文件不压缩
    else -> false
}
```

**STORED 模式要求**：
- 必须设置 `size`、`compressedSize`、`crc`
- 通过 `calculateBytesCrc32()` 或 `calculateStreamCrcAndSize()` 计算

---

### 4.3 ExeEditor（EXE 链式编辑器）

**定位**：面向业务层的 EXE 图标更换入口，采用与 `ApkEditor` 一致的链式 API 设计。

**创建方式**：

```kotlin
val editor = ExeEditor.fromAsset(context, "templates/myapp.exe")
// 或
val editor = ExeEditor.fromFile(context, exeFile)
// 或
val editor = ExeEditor.fromPath(context, "/path/to/app.exe")
```

**链式配置**：

```kotlin
editor
    .changeIcon(iconBitmap)           // 或 changeIcon(inputStream) / changeIconFromAsset(path)
    .setOutput(outputFile)
```

**执行**：

```kotlin
val modifiedExe = editor.process()
```

**注意**：`ExeIconChanger.changeIcon()` 当前为**模拟实现**（`simulateResourceReplacement` 直接返回 `true`），因为 Android 平台无法直接修改 Windows PE 文件的资源段。实际资源替换需要在 Windows 环境下使用 Win32 API 或专用工具实现。

---

### 4.4 ExeIconChanger（EXE 图标更换器）

**定位**：PE 文件格式解析和 ICO 文件生成。

#### 4.4.1 PE 文件验证（`isPEFile`）

```kotlin
fun isPEFile(file: File): Boolean {
    // 1. 检查 DOS 签名 "MZ" (0x5A4D)
    // 2. 读取 PE 头偏移量（位于 0x3C）
    // 3. 跳转到 PE 头位置
    // 4. 检查 PE 签名 "PE\0\0" (0x00004550)
}
```

#### 4.4.2 ICO 文件生成（`createIcoFile`）

生成标准 ICO 文件格式：
- **文件头**（6 字节）：保留(0) + 类型(1=ICO) + 图像数量(1)
- **图像目录**（16 字节）：宽/高/调色板/平面/位深/大小/偏移
- **图像数据**：PNG 格式压缩的位图数据

支持的最大尺寸：256x256（超过时按 256 处理，ICO 目录中记为 0）

---

### 4.5 KeyStoreHelper（密钥库辅助类）

**定位**：统一处理密钥库加载、格式兼容和 BouncyCastle Provider 管理。

#### 4.5.1 BouncyCastle Provider 管理

```kotlin
// 注册 BC Provider（插入到首位确保优先级）
KeyStoreHelper.registerBouncyCastleProvider()

// 实现细节：
// 1. Security.removeProvider("BC")  // 先移除避免重复
// 2. val provider = BouncyCastleProvider()
// 3. Security.insertProviderAt(provider, 1)  // 插入到第一位
```

#### 4.5.2 密钥库实例获取

```kotlin
// PKCS12 类型自动注册 BC Provider
val keyStore = KeyStoreHelper.getKeyStoreInstance("PKCS12")

// JKS 类型直接获取
val keyStore = KeyStoreHelper.getKeyStoreInstance("JKS")
```

#### 4.5.3 密钥库验证

```kotlin
val isValid = KeyStoreHelper.validateKeystore(file, "PKCS12", "password")
// 验证内容：
// 1. 能否成功加载
// 2. 是否包含至少一个别名
```

#### 4.5.4 应用签名密钥库获取

```kotlin
val keystoreFile = KeyStoreHelper.getOrCreateKeystore(context)
```

**获取优先级**：
1. 已存在的 `pkcs12.keystore`（验证有效）
2. 已存在的 `jks.jks`（验证有效）
3. 从 assets 加载 `pkcs12.keystore`
4. 从 assets 加载 `jks.jks`
5. 返回默认路径（可能不存在，需调用方处理）

---

## 5. 关键流程

### 5.1 APK 重打包并签名完整流程

```
ApkEditor.fromAsset(context, "template.apk")
    │
    ▼
changePackageName("com.example.app")
changeAppName("示例应用")
changeVersionName("1.0.0")
changeVersionCode("1")
changeIcon(bitmap)
withSignature(keystore, "password", "alias", "keyPassword")
setOutput(outputFile)
    │
    ▼
repackAndSignWithWebContent(webContentDir)
    │
    ├─► repackWithWebContent(webContentDir)
    │   │
    │   ├─► ApkReverseEngineer.repackageApkWithWebContent()
    │   │   │
    │   │   ├─ 创建未对齐临时 APK
    │   │   ├─ 打开原始 APK ZipFile
    │   │   ├─ 创建 ZipArchiveOutputStream
    │   │   ├─ 遍历原始条目：
    │   │   │   ├─ 跳过 META-INF/
    │   │   │   ├─ 跳过旧 web_content/
    │   │   │   ├─ 替换图标（多 DPI 自适应）
    │   │   │   ├─ 修改 AndroidManifest.xml（AXML）
    │   │   │   │   ├─ 替换 package
    │   │   │   │   ├─ 替换 versionName
    │   │   │   │   ├─ 替换 versionCode
    │   │   │   │   └─ 替换 application label
    │   │   │   └─ 复制其他条目（保留压缩方法）
    │   │   ├─ 添加新 web_content/
    │   │   ├─ 关闭 Zip 流
    │   │   ├─ zipalign 对齐
    │   │   └─ 返回未签名 APK
    │   │
    │   └─ 返回未签名 APK 文件
    │
    ├─ 验证未签名 APK 存在且非空
    ├─ 验证签名信息完整
    │
    ├─► ApkReverseEngineer.signApk()
    │   │
    │   ├─ 尝试 PKCS12 格式
    │   │   ├─► KeyStoreHelper.getKeyStoreInstance("PKCS12")
    │   │   │   └─ 自动注册 BouncyCastle Provider
    │   │   ├─ 加载密钥库
    │   │   ├─ 获取别名列表
    │   │   ├─ 别名不存在 → 使用第一个别名
    │   │   ├─ 获取私钥和证书链
    │   │   └─ 使用 ApkSigner 签名
    │   │
    │   └─ PKCS12 失败 → 尝试 JKS 格式
    │
    ├─ 签名成功 → 复制到最终输出路径
    ├─ 清理临时文件
    │
    └─ 返回签名后的 APK 文件
```

### 5.2 AXML 修改流程

```
modifyManifestBytes(manifestBytes, newPackageName, newAppName, newVersionName, newVersionCode)
    │
    ├─ AxmlReader(manifestBytes).accept(axml)
    ├─ 定位 manifest 节点
    │
    ├─ 修改 package 属性
    │   ├─ 找到/创建 package 属性
    │   ├─ 记录旧包名
    │   └─ 递归替换所有引用旧包名的属性值
    │       ├─ 遍历所有节点
    │       ├─ 遍历所有属性
    │       ├─ 跳过 MainActivity 完整类名
    │       └─ 替换包含旧包名的字符串
    │
    ├─ 修改 versionName 属性
    ├─ 修改 versionCode 属性（TYPE_INT_HEX）
    ├─ 修改 application@label 属性
    │
    ├─ AxmlWriter().toByteArray()
    │
    └─ 返回修改后的二进制 XML
```

---

## 6. 使用示例

### 6.1 APK 重打包并签名

```kotlin
// 准备资源
val iconBitmap = BitmapFactory.decodeResource(resources, R.drawable.new_icon)
val keystoreFile = KeyStoreHelper.getOrCreateKeystore(context)
val webContentDir = File(context.filesDir, "web_content")

// 链式编辑
val signedApk = try {
    ApkEditor.fromAsset(context, "templates/flutter_web.apk")
        .changePackageName("com.mycompany.newapp")
        .changeAppName("新应用名称")
        .changeVersionName("2.0.0")
        .changeVersionCode("200")
        .changeIcon(iconBitmap)
        .withSignature(keystoreFile, "android", "mykey", "android")
        .setOutput(File(context.filesDir, "output.apk"))
        .repackAndSignWithWebContent(webContentDir)
} catch (e: Exception) {
    Log.e("ApkEditor", "打包失败", e)
    null
} finally {
    iconBitmap.recycle()
}
```

### 6.2 APK 仅重打包（不签名）

```kotlin
val unsignedApk = ApkEditor.fromFile(context, originalApk)
    .changePackageName("com.example.test")
    .changeAppName("测试应用")
    .setOutput(File(context.cacheDir, "unsigned.apk"))
    .repackWithWebContent(webContentDir)

// 稍后签名
val signResult = ApkReverseEngineer(context).signApk(
    unsignedApk,
    keystoreFile,
    "password",
    "alias",
    "keyPassword",
    signedOutputFile
)
```

### 6.3 从 assets 加载图标

```kotlin
val signedApk = ApkEditor.fromAsset(context, "template.apk")
    .changeIconFromAsset("icons/new_icon.png")
    .withSignature(keystoreFile, "pass", "alias", "keypass")
    .repackAndSignWithWebContent(webContentDir)
```

### 6.4 使用 KeyStoreHelper 获取密钥库

```kotlin
// 获取或创建应用签名密钥库
val keystore = KeyStoreHelper.getOrCreateKeystore(context)

// 验证密钥库
val isValid = KeyStoreHelper.validateKeystore(keystore, "PKCS12", "android")

// 从 assets 加载特定密钥库
val customKeystore = KeyStoreHelper.loadKeystoreFromAsset(
    context,
    "mykeystore.p12",
    "output.p12"
)
```

### 6.5 EXE 图标更换（模拟）

```kotlin
val modifiedExe = try {
    ExeEditor.fromAsset(context, "templates/app.exe")
        .changeIconFromAsset("icons/new_icon.ico")
        .setOutput(File(context.filesDir, "output.exe"))
        .process()
} catch (e: Exception) {
    Log.e("ExeEditor", "处理失败", e)
    null
} finally {
    editor.cleanup()
}

// 验证 PE 格式
val isValidPE = ExeIconChanger(context).isPEFile(modifiedExe)
```

---

## 7. 最佳实践

### 7.1 资源管理

- **及时回收 Bitmap**：调用 `editor.cleanup()` 或手动 `bitmap.recycle()`
- **清理临时文件**：`ApkReverseEngineer` 会自动清理未对齐临时文件，但建议检查缓存目录
- **输出文件存在检查**：`setOutput()` 前确保父目录存在

### 7.2 签名最佳实践

- **密钥库密码安全**：避免硬编码密码，使用 Android Keystore System 或安全存储
- **别名容错**：依赖 `ApkReverseEngineer` 的别名自动回退机制
- **格式兼容**：优先使用 PKCS12，失败自动回退 JKS

### 7.3 错误处理

```kotlin
try {
    val apk = editor.repackAndSignWithWebContent(webContentDir)
} catch (e: IllegalArgumentException) {
    // webContentDir 不存在或不是目录
} catch (e: IllegalStateException) {
    // 签名信息不完整
} catch (e: RuntimeException) {
    // 重打包失败或签名失败（含详细错误消息）
}
```

### 7.4 性能优化

- **不落地解压**：`repackageApkWithWebContent` 已优化为流式处理，无需额外优化
- **图标预缩放**：如果已知目标 DPI，可预先缩放 Bitmap 减少运行时计算
- **Web 内容精简**：减少 `webContentDir` 中的文件数量和大小

### 7.5 安全注意事项

- **密钥库文件权限**：确保密钥库文件存储在应用私有目录（`context.filesDir`）
- **密码传输**：避免通过 Intent 或日志传递密码
- **签名验证**：发布前使用 `apksigner verify` 验证签名有效性

---

## 附录：依赖库

| 库 | 用途 |
|----|------|
| `pxb.android:axml` | AXML 二进制 XML 解析与写入 |
| `org.apache.commons:commons-compress` | Zip 归档精细控制 |
| `com.android.tools.build:apksig` | APK v1/v2/v3 签名 |
| `com.iyxan23:zipalign-java` | Zip 对齐（4字节/16KB） |
| `org.bouncycastle:bcprov-jdk18on` | BouncyCastle 安全 Provider |

---

*文档生成时间：2026-05-16*
*基于 Operit 项目 subpack 模块源代码分析*
