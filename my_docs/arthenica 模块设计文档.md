# arthenica 模块软件设计架构与使用文档

## 目录

1. [模块概述](#1-模块概述)
2. [整体架构](#2-整体架构)
3. [核心类设计](#3-核心类设计)
4. [FFmpeg 执行流程](#4-ffmpeg-执行流程)
5. [FFprobe 媒体信息解析](#5-ffprobe-媒体信息解析)
6. [项目中的使用方式](#6-项目中的使用方式)
7. [使用方法](#7-使用方法)
8. [文件索引](#8-文件索引)

---

## 1. 模块概述

**arthenica** 模块是 Operit 项目中用于封装 **FFmpeg / FFprobe** 多媒体处理能力的 Kotlin 包装层。该模块基于开源项目 [ffmpeg-kit](https://github.com/arthenica/ffmpeg-kit) 的 API 风格设计，为上层提供统一的音视频处理接口。

### 1.1 核心能力

| 能力 | 说明 |
|------|------|
| FFmpeg 命令执行 | 同步/异步执行 FFmpeg 命令行 |
| FFprobe 媒体分析 | 获取音视频文件的格式、码率、时长、流信息等 |
| 会话管理 | 通过 Session 对象封装单次执行的状态和结果 |
| 配置管理 | 版本信息、日志回调、字体目录等全局配置 |

### 1.2 模块定位

```
┌─────────────────────────────────────────────┐
│           Operit 应用层                       │
│  ┌─────────────┐  ┌─────────────────────┐  │
│  │ UI 界面     │  │ AI Tool 系统         │  │
│  │ 媒体预览    │  │ (StandardFFmpegTool) │  │
│  └─────────────┘  └─────────────────────┘  │
├─────────────────────────────────────────────┤
│           业务封装层                          │
│  ┌─────────────┐  ┌─────────────────────┐  │
│  │ FFmpegUtil  │  │ StandardFFmpegTool  │  │
│  │ 工具方法    │  │ Executor 实现        │  │
│  └─────────────┘  └─────────────────────┘  │
├─────────────────────────────────────────────┤
│           arthenica 模块 (Kotlin Wrapper)    │
│  ┌─────────────┐  ┌─────────────┐          │
│  │ FFmpegKit   │  │ FFprobeKit  │          │
│  │ FFmpegSession│  │ FFprobeSession        │
│  │ ReturnCode  │  │ MediaInformation      │
│  └─────────────┘  └─────────────┘          │
├─────────────────────────────────────────────┤
│           底层原生库 (FFmpeg C Library)       │
│         libffmpeg.so / ffmpeg 可执行文件      │
└─────────────────────────────────────────────┘
```

---

## 2. 整体架构

### 2.1 分层设计

```
┌─────────────────────────────────────────────────────────────┐
│  应用层 (Application)                                        │
│  - 视频转换、音频提取、媒体信息展示                            │
│  - AI Agent 通过 Tool 系统调用 FFmpeg                         │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  业务封装层 (Business Wrapper)                               │
│  ┌─────────────────────────────────────────────────────────┐│
│  │ FFmpegUtil                                               ││
│  │ - executeCommand(): 执行命令并返回布尔结果                ││
│  │ - getMediaInfo(): 获取媒体文件信息                        ││
│  │ - scaleFilterMaxWidth(): 构建缩放滤镜表达式               ││
│  └─────────────────────────────────────────────────────────┘│
│  ┌─────────────────────────────────────────────────────────┐│
│  │ StandardFFmpegToolExecutor                               ││
│  │ - 通用 FFmpeg 命令执行                                    ││
│  │ StandardFFmpegInfoToolExecutor                           ││
│  │ - 获取 FFmpeg 版本和编解码器列表                          ││
│  │ StandardFFmpegConvertToolExecutor                        ││
│  │ - 视频转换（格式、分辨率、码率、编解码器）                  ││
│  └─────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  arthenica API 层 (Kotlin API)                              │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐ │
│  │ FFmpegKit   │  │ FFprobeKit  │  │ FFmpegKitConfig     │ │
│  │ - execute() │  │ - execute() │  │ - getVersion()      │ │
│  │ - executeAsync()│ - getMediaInformation()│ - setLogCallback()│ │
│  └─────────────┘  └─────────────┘  └─────────────────────┘ │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐ │
│  │ FFmpegSession│  │ FFprobeSession│  │ ReturnCode         │ │
│  │ - returnCode │  │ - returnCode │  │ - isSuccess()       │ │
│  │ - output     │  │ - output     │  │ - isCancel()        │ │
│  │ - duration   │  │ - mediaInformation│                 │ │
│  └─────────────┘  └─────────────┘  └─────────────────────┘ │
│  ┌─────────────────────────────────────────────────────────┐│
│  │ MediaInformation / StreamInformation / FormatInformation││
│  └─────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼ JNI / 进程调用
┌─────────────────────────────────────────────────────────────┐
│  底层 FFmpeg 原生库                                          │
│  - ffmpeg 命令行工具 / libffmpeg.so                          │
│  - ffprobe 命令行工具 / libffprobe.so                        │
└─────────────────────────────────────────────────────────────┘
```

---

## 3. 核心类设计

### 3.1 FFmpegKit — FFmpeg 命令执行入口

[FFmpegKit.kt](file:///home/meizu/Documents/my_agent_projects/Operit/app/src/main/java/com/arthenica/ffmpegkit/FFmpegKit.kt)

```kotlin
object FFmpegKit {
    /**
     * 同步执行 FFmpeg 命令
     * @param command FFmpeg 命令字符串（如 "-i input.mp4 -c:v libx264 output.mp4"）
     * @return FFmpegSession 包含执行结果
     */
    fun execute(command: String): FFmpegSession

    /**
     * 异步执行 FFmpeg 命令
     * @param command FFmpeg 命令字符串
     * @param completeCallback 执行完成后的回调
     */
    fun executeAsync(command: String, completeCallback: (FFmpegSession) -> Unit)
}
```

**设计要点**：
- 单例对象（`object`），提供全局访问点
- 支持同步和异步两种执行模式
- 命令参数为完整字符串，由调用方负责转义和拼接

### 3.2 FFmpegSession — 执行会话与结果

[FFmpegSession.kt](file:///home/meizu/Documents/my_agent_projects/Operit/app/src/main/java/com/arthenica/ffmpegkit/FFmpegSession.kt)

```kotlin
class FFmpegSession {
    /** 返回码：0 表示成功，其他表示错误或取消 */
    val returnCode: ReturnCode = ReturnCode(-1)

    /** 命令执行的完整输出（stdout + stderr） */
    val output: String? = null

    /** 会话开始时间戳 */
    val startTime: Long = 0

    /** 执行耗时（毫秒） */
    val duration: Long = 0
}
```

**设计要点**：
- 不可变数据类风格（当前为占位实现，实际应由底层填充真实数据）
- `returnCode` 是判断执行成功与否的唯一依据
- `output` 包含 FFmpeg 的完整控制台输出，可用于调试

### 3.3 FFprobeKit — 媒体信息分析入口

[FFprobeKit.kt](file:///home/meizu/Documents/my_agent_projects/Operit/app/src/main/java/com/arthenica/ffmpegkit/FFprobeKit.kt)

```kotlin
object FFprobeKit {
    /**
     * 同步执行 FFprobe 命令
     */
    fun execute(command: String): FFprobeSession

    /**
     * 获取媒体文件的完整信息
     * @param path 媒体文件路径
     * @return FFprobeSession 包含解析后的 MediaInformation
     */
    fun getMediaInformation(path: String): FFprobeSession
}
```

### 3.4 FFprobeSession — 分析会话与结果

```kotlin
class FFprobeSession {
    val returnCode: ReturnCode = ReturnCode(-1)
    val output: String? = null

    /** 解析后的媒体信息对象 */
    val mediaInformation: MediaInformation? = null
}
```

### 3.5 MediaInformation — 媒体信息数据模型

```kotlin
class MediaInformation {
    /** 格式信息 */
    fun getFormat(): FormatInformation

    /** 所有流（视频/音频/字幕）列表 */
    val streams: List<StreamInformation>

    /** 容器格式名称（如 "mp4", "matroska"） */
    val format: String?

    /** 时长字符串 */
    val duration: String?

    /** 总码率字符串 */
    val bitrate: String?
}

class StreamInformation {
    /** 通过键获取原始属性值 */
    fun get(key: String): String?

    /** 所有原始属性 */
    val allProperties: Map<String, String>?

    /** 流索引 */
    val index: String?

    /** 流类型："video" / "audio" / "subtitle" */
    val type: String

    /** 编解码器名称 */
    val codec: String

    /** 视频宽度 */
    val width: Int

    /** 视频高度 */
    val height: Int

    /** 流大小 */
    val size: Long

    /** 视频帧率 */
    val frameRate: Double

    /** 音频采样率 */
    val sampleRate: Int

    /** 音频通道数 */
    val channels: Int
}

class FormatInformation {
    fun get(key: String): String?
    val format: String?
    val duration: String?
    val bitrate: String?
}
```

### 3.6 ReturnCode — 返回码封装

[ReturnCode.kt](file:///home/meizu/Documents/my_agent_projects/Operit/app/src/main/java/com/arthenica/ffmpegkit/ReturnCode.kt)

```kotlin
class ReturnCode(val value: Int) {
    companion object {
        /** 判断是否为成功（返回码 0） */
        fun isSuccess(returnCode: ReturnCode?): Boolean

        /** 判断是否为取消（返回码 -1） */
        fun isCancel(returnCode: ReturnCode?): Boolean
    }
}
```

### 3.7 FFmpegKitConfig — 全局配置

[FFmpegKitConfig.kt](file:///home/meizu/Documents/my_agent_projects/Operit/app/src/main/java/com/arthenica/ffmpegkit/FFmpegKitConfig.kt)

```kotlin
object FFmpegKitConfig {
    /** 获取 FFmpeg 版本 */
    fun getVersion(): String

    /** 获取构建日期 */
    fun getBuildDate(): String

    /** 设置日志回调 */
    fun setLogCallback(callback: (String) -> Unit)

    /** 设置字体目录（用于字幕渲染） */
    fun setFontDirectory(path: String, recursive: Boolean)
}
```

---

## 4. FFmpeg 执行流程

### 4.1 同步执行流程

```
调用方
    │
    ▼
FFmpegKit.execute(command: String)
    │
    ▼
创建 FFmpegSession 实例
    │
    ▼
底层调用 ffmpeg 进程 / JNI 调用 libffmpeg
    │
    ▼
等待执行完成
    │
    ▼
填充 FFmpegSession
    ├─ returnCode: 0 (成功) / 非 0 (失败) / -1 (取消)
    ├─ output: 完整控制台输出
    ├─ startTime: 开始时间戳
    └─ duration: 执行耗时
    │
    ▼
返回 FFmpegSession
    │
    ▼
调用方通过 ReturnCode.isSuccess() 判断结果
```

### 4.2 异步执行流程

```
调用方
    │
    ▼
FFmpegKit.executeAsync(command, callback)
    │
    ▼
在后台线程启动 ffmpeg 执行
    │
    ▼
立即返回（不阻塞调用方）
    │
    ▼
执行完成后 ──▶ 回调 callback(FFmpegSession)
```

### 4.3 错误处理模式

```kotlin
val session = FFmpegKit.execute(command)
val returnCode = session.returnCode

when {
    ReturnCode.isSuccess(returnCode) -> {
        // 执行成功
    }
    ReturnCode.isCancel(returnCode) -> {
        // 用户取消
    }
    else -> {
        // 执行失败，错误信息在 session.output 中
        val errorOutput = session.output
    }
}
```

---

## 5. FFprobe 媒体信息解析

### 5.1 信息获取流程

```
文件路径
    │
    ▼
FFprobeKit.getMediaInformation(path)
    │
    ▼
底层执行 ffprobe -v quiet -print_format json -show_format -show_streams <path>
    │
    ▼
解析 JSON 输出
    │
    ▼
构建 MediaInformation 对象
    ├─ format: 容器格式信息
    ├─ streams: 视频/音频/字幕流列表
    │   ├─ StreamInformation (video)
    │   │   ├─ codec: "h264"
    │   │   ├─ width: 1920
    │   │   ├─ height: 1080
    │   │   └─ frameRate: 30.0
    │   └─ StreamInformation (audio)
    │       ├─ codec: "aac"
    │       ├─ sampleRate: 48000
    │       └─ channels: 2
    └─ duration / bitrate
```

### 5.2 数据模型关系

```
FFprobeSession
    ├── returnCode: ReturnCode
    ├── output: String (原始 JSON/文本输出)
    └── mediaInformation: MediaInformation
            ├── format: String (容器格式)
            ├── duration: String
            ├── bitrate: String
            ├── formatInfo: FormatInformation
            │       └── get(key): String?
            └── streams: List<StreamInformation>
                    ├── index: String
                    ├── type: String ("video"/"audio"/"subtitle")
                    ├── codec: String
                    ├── width: Int (视频)
                    ├── height: Int (视频)
                    ├── frameRate: Double (视频)
                    ├── sampleRate: Int (音频)
                    ├── channels: Int (音频)
                    ├── size: Long
                    ├── allProperties: Map<String, String>
                    └── get(key): String?
```

---

## 6. 项目中的使用方式

### 6.1 FFmpegUtil — 通用工具封装

[FFmpegUtil.kt](file:///home/meizu/Documents/my_agent_projects/Operit/app/src/main/java/com/ai/assistance/operit/util/FFmpegUtil.kt)

`FFmpegUtil` 是对 arthenica 模块的轻量级封装，提供业务友好的 API：

```kotlin
object FFmpegUtil {
    /**
     * 构建缩放滤镜表达式
     * 注意：FFmpeg 表达式中的逗号需要转义
     */
    fun scaleFilterMaxWidth(maxWidth: Int): String
        = "scale=min(${maxWidth}\\,iw):-2"

    /**
     * 执行 FFmpeg 命令，返回是否成功
     */
    fun executeCommand(command: String): Boolean

    /**
     * 获取媒体文件信息
     */
    fun getMediaInfo(filePath: String): MediaInformation?
}
```

**使用示例**：

```kotlin
// 执行视频压缩
val success = FFmpegUtil.executeCommand(
    "-i input.mp4 -c:v libx264 -crf 23 -preset fast output.mp4"
)

// 获取媒体信息
val mediaInfo = FFmpegUtil.getMediaInfo("/path/to/video.mp4")
mediaInfo?.streams?.forEach { stream ->
    println("Stream ${stream.index}: ${stream.type} - ${stream.codec}")
}
```

### 6.2 StandardFFmpegToolExecutor — AI Tool 集成

[StandardFFmpegTool.kt](file:///home/meizu/Documents/my_agent_projects/Operit/app/src/main/java/com/ai/assistance/operit/core/tools/defaultTool/standard/StandardFFmpegTool.kt)

Operit 的 AI Agent 系统通过 `ToolExecutor` 接口调用 FFmpeg：

#### 通用命令执行器 (StandardFFmpegToolExecutor)

```kotlin
class StandardFFmpegToolExecutor(private val context: Context) : ToolExecutor {
    override fun invoke(tool: AITool): ToolResult {
        val command = tool.parameters.find { it.name == "command" }?.value ?: ""
        val session = FFmpegKit.execute(command)
        val returnCode = session.returnCode
        val output = session.output ?: ""

        return if (ReturnCode.isSuccess(returnCode)) {
            ToolResult(
                toolName = tool.name,
                success = true,
                result = FFmpegResultData(command, returnCode.value, output, duration)
            )
        } else {
            ToolResult(
                toolName = tool.name,
                success = false,
                error = "FFmpeg execution failed, return code: ${returnCode.value}\nOutput:\n$output"
            )
        }
    }
}
```

**AI 调用示例**：

```
用户: "把这段视频转成 MP3"
AI 生成 Tool 调用:
{
    "tool": "ffmpeg",
    "parameters": {
        "command": "-i /path/to/video.mp4 -vn -c:a libmp3lame -q:a 2 /path/to/output.mp3"
    }
}
```

#### 信息查询执行器 (StandardFFmpegInfoToolExecutor)

```kotlin
class StandardFFmpegInfoToolExecutor : ToolExecutor {
    override fun invoke(tool: AITool): ToolResult {
        val info = StringBuilder()
        info.appendLine("FFmpeg version: ${FFmpegKitConfig.getVersion()}")
        info.appendLine("Build configuration: ${FFmpegKitConfig.getBuildDate()}")

        val codecsSession = FFmpegKit.execute("-codecs")
        info.appendLine("\nSupported codecs:")
        info.appendLine(codecsSession.output ?: "")

        return ToolResult(success = true, result = FFmpegResultData(...))
    }
}
```

#### 视频转换执行器 (StandardFFmpegConvertToolExecutor)

```kotlin
class StandardFFmpegConvertToolExecutor(private val context: Context) : ToolExecutor {
    override fun invoke(tool: AITool): ToolResult {
        val inputPath = tool.parameters.find { it.name == "input_path" }?.value ?: ""
        val outputPath = tool.parameters.find { it.name == "output_path" }?.value ?: ""
        val format = tool.parameters.find { it.name == "format" }?.value
        val resolution = tool.parameters.find { it.name == "resolution" }?.value
        val bitrate = tool.parameters.find { it.name == "bitrate" }?.value
        val audioCodec = tool.parameters.find { it.name == "audio_codec" }?.value
        val videoCodec = tool.parameters.find { it.name == "video_codec" }?.value

        // 构建命令: -i "input" -c:v h264 -c:a aac -s 1280x720 -b:v 2M "output"
        val command = buildCommand(...)
        val session = FFmpegKit.execute(command)

        // 成功后通过 FFprobe 获取输出文件的详细媒体信息
        val mediaSession = FFprobeKit.getMediaInformation(outputPath)
        val mediaInfo = mediaSession?.mediaInformation

        // 构造包含视频流/音频流详细信息的 FFmpegResultData
        return ToolResult(success = true, result = ffmpegResult)
    }
}
```

**AI 调用示例**：

```
用户: "把 input.mp4 转成 720p 的 H.264 视频"
AI 生成 Tool 调用:
{
    "tool": "ffmpeg_convert",
    "parameters": {
        "input_path": "/path/to/input.mp4",
        "output_path": "/path/to/output.mp4",
        "video_codec": "libx264",
        "resolution": "1280x720"
    }
}
```

### 6.3 架构关系图

```
用户请求
    │
    ▼
AI Agent (LLM)
    │
    ▼ 生成 Tool 调用
┌─────────────────────────────────────────────┐
│ ToolExecutor 路由                            │
│  - tool.name == "ffmpeg"                     │
│    → StandardFFmpegToolExecutor              │
│  - tool.name == "ffmpeg_info"                │
│    → StandardFFmpegInfoToolExecutor          │
│  - tool.name == "ffmpeg_convert"             │
│    → StandardFFmpegConvertToolExecutor       │
└─────────────────────────────────────────────┘
    │
    ▼
arthenica 模块 (FFmpegKit / FFprobeKit)
    │
    ▼
底层 FFmpeg / FFprobe 执行
    │
    ▼
结果封装为 ToolResult → 返回给 AI Agent → 展示给用户
```

---

## 7. 使用方法

### 7.1 执行 FFmpeg 命令

```kotlin
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode

// 同步执行
val session = FFmpegKit.execute("-i input.mp4 -c:v libx264 output.mp4")
if (ReturnCode.isSuccess(session.returnCode)) {
    println("Success: ${session.output}")
} else {
    println("Failed: ${session.output}")
}

// 异步执行
FFmpegKit.executeAsync("-i input.mp4 output.mp4") { session ->
    if (ReturnCode.isSuccess(session.returnCode)) {
        // 处理成功
    }
}
```

### 7.2 获取媒体信息

```kotlin
import com.arthenica.ffmpegkit.FFprobeKit

val session = FFprobeKit.getMediaInformation("/path/to/video.mp4")
val mediaInfo = session.mediaInformation

println("Format: ${mediaInfo?.format}")
println("Duration: ${mediaInfo?.duration}")
println("Bitrate: ${mediaInfo?.bitrate}")

mediaInfo?.streams?.forEach { stream ->
    when (stream.type) {
        "video" -> {
            println("Video: ${stream.width}x${stream.height}, ${stream.codec}")
        }
        "audio" -> {
            println("Audio: ${stream.codec}, ${stream.sampleRate}Hz, ${stream.channels}ch")
        }
    }
}
```

### 7.3 使用 FFmpegUtil 工具类

```kotlin
import com.ai.assistance.operit.util.FFmpegUtil

// 执行命令
val success = FFmpegUtil.executeCommand(
    "-i input.mp4 -vf ${FFmpegUtil.scaleFilterMaxWidth(1280)} output.mp4"
)

// 获取媒体信息
val info = FFmpegUtil.getMediaInfo("/path/to/file.mp4")
```

### 7.4 配置日志回调

```kotlin
import com.arthenica.ffmpegkit.FFmpegKitConfig

FFmpegKitConfig.setLogCallback { logMessage ->
    println("FFmpeg Log: $logMessage")
}
```

---

## 8. 文件索引

### arthenica 模块（API 层）

| 文件 | 路径 | 说明 |
|------|------|------|
| FFmpegKit.kt | `app/src/main/java/com/arthenica/ffmpegkit/FFmpegKit.kt` | FFmpeg 命令执行入口 |
| FFmpegSession.kt | `app/src/main/java/com/arthenica/ffmpegkit/FFmpegSession.kt` | 执行会话与结果 |
| FFprobeKit.kt | `app/src/main/java/com/arthenica/ffmpegkit/FFprobeKit.kt` | FFprobe 媒体分析入口、媒体信息数据模型 |
| ReturnCode.kt | `app/src/main/java/com/arthenica/ffmpegkit/ReturnCode.kt` | 返回码封装 |
| FFmpegKitConfig.kt | `app/src/main/java/com/arthenica/ffmpegkit/FFmpegKitConfig.kt` | 全局配置 |

### 业务封装层

| 文件 | 路径 | 说明 |
|------|------|------|
| FFmpegUtil.kt | `app/src/main/java/com/ai/assistance/operit/util/FFmpegUtil.kt` | 通用 FFmpeg 工具方法 |
| StandardFFmpegTool.kt | `app/src/main/java/com/ai/assistance/operit/core/tools/defaultTool/standard/StandardFFmpegTool.kt` | AI Tool 系统的 FFmpeg 执行器 |
