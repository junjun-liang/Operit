# k2fsa 模块软件设计架构与使用文档

## 目录

1. [模块概述](#1-模块概述)
2. [整体架构](#2-整体架构)
3. [sherpa-ncnn 子模块](#3-sherpa-ncnn-子模块)
4. [sherpa-mnn 子模块](#4-sherpa-mnn-子模块)
5. [项目中的使用方式](#5-项目中的使用方式)
6. [VAD 语音活动检测](#6-vad-语音活动检测)
7. [使用方法](#7-使用方法)
8. [文件索引](#8-文件索引)

---

## 1. 模块概述

**k2fsa** 模块是 Operit 项目中用于**本地语音识别（ASR）**的核心引擎封装层。它基于 [k2-fsa](https://github.com/k2-fsa) 开源社区的 **sherpa-ncnn** 和 **sherpa-mnn** 项目，提供在 Android 设备上离线运行的流式语音识别能力。

### 1.1 核心能力

| 能力 | 说明 |
|------|------|
| 流式语音识别 | 实时从麦克风输入识别语音，边录边识别 |
| 多模型支持 | 支持 Zipformer、Paraformer、LSTM、NeMo CTC 等多种模型架构 |
| 多语言支持 | 中英双语、中文、英文、法语、韩语等 |
| VAD 集成 | 集成 Silero VAD 进行语音活动检测，减少无效识别 |
| 端点检测 | 自动检测语音结束，支持连续识别模式 |
| 热词支持 | 支持热词文件加载，提升特定词汇识别率 |

### 1.2 模块定位

```
┌─────────────────────────────────────────────┐
│           Operit 应用层                       │
│  ┌─────────────┐  ┌─────────────────────┐  │
│  │ UI 界面     │  │ AI Agent 语音输入    │  │
│  │ 语音转文字   │  │ 语音唤醒             │  │
│  └─────────────┘  └─────────────────────┘  │
├─────────────────────────────────────────────┤
│           业务封装层                          │
│  ┌─────────────────────────────────────────┐│
│  │ SherpaSpeechProvider (ncnn)            ││
│  │ SherpaMnnSpeechProvider (mnn)          ││
│  │ OnnxSileroVad (ONNX VAD)               ││
│  └─────────────────────────────────────────┘│
├─────────────────────────────────────────────┤
│           k2fsa API 层 (Kotlin JNI Wrapper)  │
│  ┌─────────────┐  ┌─────────────────────┐  │
│  │ sherpa-ncnn │  │ sherpa-mnn          │  │
│  │ SherpaNcnn  │  │ OnlineRecognizer    │  │
│  │ ModelConfig │  │ OnlineModelConfig   │  │
│  │ RecognizerConfig│ OnlineRecognizerConfig││
│  └─────────────┘  └─────────────────────┘  │
│  ┌─────────────┐  ┌─────────────────────┐  │
│  │ 通用组件     │  │ VAD 组件            │  │
│  │ FeatureConfig│  │ Vad                 │  │
│  │ DecoderConfig│  │ VadModelConfig      │  │
│  │ EndpointConfig│  │ SileroVadModelConfig│  │
│  └─────────────┘  └─────────────────────┘  │
├─────────────────────────────────────────────┤
│           底层原生库                          │
│  lib sherpa-ncnn-jni.so / lib sherpa-mnn-jni.so│
│  ncnn 推理引擎 / MNN 推理引擎                  │
│  ONNX Runtime (VAD)                          │
└─────────────────────────────────────────────┘
```

---

## 2. 整体架构

### 2.1 分层架构

```
┌─────────────────────────────────────────────────────────────┐
│  应用层                                                      │
│  - SpeechToTextScreen (Compose UI)                          │
│  - AI Agent 语音输入                                        │
│  - 语音唤醒 (PersonalWakeListener)                          │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  SpeechService 接口层                                        │
│  interface SpeechService {                                  │
│    val recognitionStateFlow: StateFlow<RecognitionState>    │
│    val recognitionResultFlow: StateFlow<RecognitionResult>  │
│    val volumeLevelFlow: StateFlow<Float>                    │
│    suspend fun initialize(): Boolean                        │
│    suspend fun startRecognition(...): Boolean               │
│    suspend fun stopRecognition(): Boolean                   │
│    suspend fun cancelRecognition()                          │
│    fun shutdown()                                           │
│  }                                                          │
└─────────────────────────────────────────────────────────────┘
                              │
              ┌───────────────┴───────────────┐
              ▼                               ▼
┌─────────────────────────┐   ┌─────────────────────────────┐
│ SherpaSpeechProvider    │   │ SherpaMnnSpeechProvider     │
│ (sherpa-ncnn)           │   │ (sherpa-mnn)                │
│ - 轻量级、高性能         │   │ - 支持更多模型架构           │
│ - 支持 GPU 加速          │   │ - 支持 ONNX VAD             │
│ - 4 线程优化             │   │ - 流式 Stream 管理           │
└─────────────────────────┘   └─────────────────────────────┘
                              │
              ┌───────────────┴───────────────┐
              ▼                               ▼
┌─────────────────────────┐   ┌─────────────────────────────┐
│ com.k2fsa.sherpa.ncnn   │   │ com.k2fsa.sherpa.mnn        │
│ SherpaNcnn              │   │ OnlineRecognizer            │
│ - acceptSamples()       │   │ - createStream()            │
│ - decode()              │   │ - decode(stream)            │
│ - isEndpoint()          │   │ - isEndpoint(stream)        │
│ - getText()             │   │ - getResult(stream)         │
│ - reset()               │   │ - reset(stream)             │
└─────────────────────────┘   └─────────────────────────────┘
                              │
                              ▼ JNI
┌─────────────────────────────────────────────────────────────┐
│  底层 C++ 引擎                                               │
│  - sherpa-ncnn (基于 ncnn 推理框架)                          │
│  - sherpa-mnn (基于 MNN 推理框架)                            │
│  - ONNX Runtime (Silero VAD)                                 │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 数据流

```
麦克风音频输入 (PCM 16bit, 16kHz, 单声道)
    │
    ▼
┌─────────────────────────────────────────────┐
│ AudioRecord.read() → ShortArray             │
│ 音量计算 → volumeLevelFlow                   │
│ 预滚动缓存 → SpeechPrerollStore              │
└─────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────┐
│ VAD 检测 (OnnxSileroVad)                    │
│ - 512 样本帧 → isSpeech()                    │
│ - 语音/静音状态切换                          │
└─────────────────────────────────────────────┘
    │ 有语音
    ▼
┌─────────────────────────────────────────────┐
│ ShortArray → FloatArray (除以 32768.0f)     │
│ acceptSamples() / acceptWaveform()          │
└─────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────┐
│ 识别引擎 (SherpaNcnn / OnlineRecognizer)    │
│ while (isReady()) { decode() }              │
│ text = getText() / getResult()              │
└─────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────┐
│ 端点检测 (isEndpoint())                     │
│ - 规则1: 尾随静音 2.4s                      │
│ - 规则2: 有非静音后尾随静音 1.0-1.2s        │
│ - 规则3: 最大 utterance 长度 20s            │
└─────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────┐
│ recognitionResultFlow.emit(RecognitionResult)│
│ - text: 识别文本                             │
│ - isFinal: 是否最终结果                       │
└─────────────────────────────────────────────┘
```

---

## 3. sherpa-ncnn 子模块

### 3.1 文件位置

`sherpa-ncnn` 位于 `com.k2fsa.sherpa.ncnn` 包下：

| 文件 | 路径 |
|------|------|
| SherpaNcnn.kt | `app/src/main/java/com/k2fsa/sherpa/ncnn/SherpaNcnn.kt` |

### 3.2 配置数据类

```kotlin
/** 特征提取配置 */
data class FeatureExtractorConfig(
    var sampleRate: Float,   // 采样率，通常 16000.0f
    var featureDim: Int,     // 特征维度，通常 80
)

/** 模型配置 */
data class ModelConfig(
    var encoderParam: String,  // encoder .param 文件路径
    var encoderBin: String,    // encoder .bin 文件路径
    var decoderParam: String,  // decoder .param 文件路径
    var decoderBin: String,    // decoder .bin 文件路径
    var joinerParam: String,   // joiner .param 文件路径
    var joinerBin: String,     // joiner .bin 文件路径
    var tokens: String,        // tokens.txt 路径
    var numThreads: Int = 1,
    var useGPU: Boolean = true,
)

/** 解码器配置 */
data class DecoderConfig(
    var method: String = "modified_beam_search", // greedy_search / modified_beam_search
    var numActivePaths: Int = 4,
)

/** 识别器配置 */
data class RecognizerConfig(
    var featConfig: FeatureExtractorConfig,
    var modelConfig: ModelConfig,
    var decoderConfig: DecoderConfig,
    var enableEndpoint: Boolean = true,
    var rule1MinTrailingSilence: Float = 2.4f,
    var rule2MinTrailingSilence: Float = 1.0f,
    var rule3MinUtteranceLength: Float = 30.0f,
    var hotwordsFile: String = "",
    var hotwordsScore: Float = 1.5f,
)
```

### 3.3 SherpaNcnn 类

[SherpaNcnn.kt](file:///home/meizu/Documents/my_agent_projects/Operit/app/src/main/java/com/k2fsa/sherpa/ncnn/SherpaNcnn.kt)

```kotlin
class SherpaNcnn(
    var config: RecognizerConfig,
    assetManager: AssetManager? = null,
) {
    /** 送入音频样本 */
    fun acceptSamples(samples: FloatArray)

    /** 检查是否有足够数据解码 */
    fun isReady(): Boolean

    /** 执行一次解码 */
    fun decode()

    /** 标记输入结束 */
    fun inputFinished()

    /** 检查是否到达端点 */
    fun isEndpoint(): Boolean

    /** 重置识别器状态 */
    fun reset(recreate: Boolean = false)

    /** 当前识别文本 */
    val text: String

    /** 释放资源 */
    fun release()
}
```

**设计要点**：
- 单实例管理：一个 `SherpaNcnn` 对象对应一个底层 C++ 识别器
- 无 Stream 概念：音频直接送入识别器，内部自动管理缓冲区
- `reset(false)` 重置状态但不重新创建底层对象，适合连续识别
- `reset(true)` 完全重新创建识别器

### 3.4 预置模型配置

```kotlin
fun getModelConfig(type: Int, useGPU: Boolean): ModelConfig?
```

| type | 模型 | 语言 | 说明 |
|------|------|------|------|
| 0 | sherpa-ncnn-2022-09-30 | 中文 | 早期模型 |
| 1 | sherpa-ncnn-conv-emformer-transducer | 中英双语 | ConvEmformer 架构 |
| 2 | sherpa-ncnn-streaming-zipformer-bilingual-zh-en | 中英双语 | **项目默认使用** |
| 3 | sherpa-ncnn-streaming-zipformer-en | 英文 | Zipformer 英文 |
| 4 | sherpa-ncnn-streaming-zipformer-fr | 法文 | Zipformer 法文 |
| 5 | sherpa-ncnn-streaming-zipformer-zh-14M | 中文 | 小模型（14M） |
| 6 | sherpa-ncnn-streaming-zipformer-small-bilingual | 中英双语 | 小型双语模型 |

---

## 4. sherpa-mnn 子模块

### 4.1 文件位置

`sherpa-mnn` 位于 `com.k2fsa.sherpa.mnn` 包下：

| 文件 | 路径 |
|------|------|
| OnlineRecognizer.kt | `app/src/main/java/com/k2fsa/sherpa/mnn/OnlineRecognizer.kt` |
| OnlineStream.kt | `app/src/main/java/com/k2fsa/sherpa/mnn/OnlineStream.kt` |
| FeatureConfig.kt | `app/src/main/java/com/k2fsa/sherpa/mnn/FeatureConfig.kt` |
| Vad.kt | `app/src/main/java/com/k2fsa/sherpa/mnn/Vad.kt` |

### 4.2 配置数据类

```kotlin
/** 特征配置 */
data class FeatureConfig(
    var sampleRate: Int = 16000,
    var featureDim: Int = 80,
)

/** 端点规则 */
data class EndpointRule(
    var mustContainNonSilence: Boolean,
    var minTrailingSilence: Float,
    var minUtteranceLength: Float,
)

/** 端点配置 */
data class EndpointConfig(
    var rule1: EndpointRule = EndpointRule(false, 2.4f, 0.0f),
    var rule2: EndpointRule = EndpointRule(true, 1.4f, 0.0f),
    var rule3: EndpointRule = EndpointRule(false, 0.0f, 20.0f)
)

/** 模型配置（支持多种架构） */
data class OnlineModelConfig(
    var transducer: OnlineTransducerModelConfig = OnlineTransducerModelConfig(),
    var paraformer: OnlineParaformerModelConfig = OnlineParaformerModelConfig(),
    var zipformer2Ctc: OnlineZipformer2CtcModelConfig = OnlineZipformer2CtcModelConfig(),
    var neMoCtc: OnlineNeMoCtcModelConfig = OnlineNeMoCtcModelConfig(),
    var tokens: String = "",
    var numThreads: Int = 1,
    var debug: Boolean = false,
    var provider: String = "cpu",
    var modelType: String = "",
)

/** 识别器配置 */
data class OnlineRecognizerConfig(
    var featConfig: FeatureConfig = FeatureConfig(),
    var modelConfig: OnlineModelConfig = OnlineModelConfig(),
    var lmConfig: OnlineLMConfig = OnlineLMConfig(),
    var ctcFstDecoderConfig: OnlineCtcFstDecoderConfig = OnlineCtcFstDecoderConfig(),
    var endpointConfig: EndpointConfig = EndpointConfig(),
    var enableEndpoint: Boolean = true,
    var decodingMethod: String = "greedy_search",
    var maxActivePaths: Int = 4,
    var hotwordsFile: String = "",
    var hotwordsScore: Float = 1.5f,
)

/** 识别结果 */
data class OnlineRecognizerResult(
    val text: String,
    val tokens: Array<String>,
    val timestamps: FloatArray,
)
```

### 4.3 OnlineRecognizer 类

[OnlineRecognizer.kt](file:///home/meizu/Documents/my_agent_projects/Operit/app/src/main/java/com/k2fsa/sherpa/mnn/OnlineRecognizer.kt)

```kotlin
class OnlineRecognizer(
    assetManager: AssetManager? = null,
    val config: OnlineRecognizerConfig,
) {
    /** 创建识别流 */
    fun createStream(hotwords: String = ""): OnlineStream

    /** 重置流 */
    fun reset(stream: OnlineStream)

    /** 解码 */
    fun decode(stream: OnlineStream)

    /** 检查是否到达端点 */
    fun isEndpoint(stream: OnlineStream): Boolean

    /** 检查是否可以解码 */
    fun isReady(stream: OnlineStream): Boolean

    /** 获取识别结果 */
    fun getResult(stream: OnlineStream): OnlineRecognizerResult

    /** 释放资源 */
    fun release()
}
```

**设计要点**：
- 支持多 Stream：一个 `OnlineRecognizer` 可创建多个 `OnlineStream`
- Stream 隔离：每个 Stream 有独立的识别状态，适合多路识别场景
- 结果包含 `tokens` 和 `timestamps`，支持细粒度分析

### 4.4 OnlineStream 类

[OnlineStream.kt](file:///home/meizu/Documents/my_agent_projects/Operit/app/src/main/java/com/k2fsa/sherpa/mnn/OnlineStream.kt)

```kotlin
class OnlineStream(var ptr: Long = 0) {
    /** 送入音频波形数据 */
    fun acceptWaveform(samples: FloatArray, sampleRate: Int)

    /** 标记输入结束 */
    fun inputFinished()

    /** 释放资源 */
    fun release()

    /** use 扩展函数 */
    fun use(block: (OnlineStream) -> Unit)
}
```

### 4.5 Vad 类（sherpa-mnn 内置 VAD）

[Vad.kt](file:///home/meizu/Documents/my_agent_projects/Operit/app/src/main/java/com/k2fsa/sherpa/mnn/Vad.kt)

```kotlin
class Vad(
    assetManager: AssetManager? = null,
    var config: VadModelConfig,
) {
    /** 送入音频 */
    fun acceptWaveform(samples: FloatArray)

    /** 检查是否有语音片段 */
    fun empty(): Boolean

    /** 弹出最早的一个语音片段 */
    fun pop()

    /** 查看最早的语音片段 */
    fun front(): SpeechSegment

    /** 清空所有片段 */
    fun clear()

    /** 是否检测到语音 */
    fun isSpeechDetected(): Boolean

    /** 重置 */
    fun reset()

    /** 刷新缓冲区 */
    fun flush()

    /** 释放资源 */
    fun release()
}

class SpeechSegment(val start: Int, val samples: FloatArray)
```

**注意**：当前项目中 sherpa-mnn 内置 VAD 因兼容性问题已禁用，改用 `OnnxSileroVad`。

---

## 5. 项目中的使用方式

### 5.1 SpeechService 接口

[SpeechService.kt](file:///home/meizu/Documents/my_agent_projects/Operit/app/src/main/java/com/ai/assistance/operit/api/speech/SpeechService.kt)

```kotlin
interface SpeechService {
    enum class RecognitionState {
        UNINITIALIZED, IDLE, PREPARING, RECOGNIZING, PROCESSING, ERROR
    }

    data class RecognitionResult(val text: String, val isFinal: Boolean = false, val confidence: Float = 0f)
    data class RecognitionError(val code: Int, val message: String)

    val isInitialized: StateFlow<Boolean>
    val isRecognizing: Boolean
    val currentState: RecognitionState
    val recognitionStateFlow: StateFlow<RecognitionState>
    val recognitionResultFlow: StateFlow<RecognitionResult>
    val recognitionErrorFlow: StateFlow<RecognitionError>
    val volumeLevelFlow: StateFlow<Float>

    suspend fun initialize(): Boolean
    suspend fun startRecognition(languageCode: String, continuousMode: Boolean, partialResults: Boolean, audioSource: Int): Boolean
    suspend fun stopRecognition(): Boolean
    suspend fun cancelRecognition()
    fun shutdown()
    suspend fun getSupportedLanguages(): List<String>
    suspend fun recognize(audioData: FloatArray)
}
```

### 5.2 SherpaSpeechProvider（ncnn 实现）

[SherpaSpeechProvider.kt](file:///home/meizu/Documents/my_agent_projects/Operit/app/src/main/java/com/ai/assistance/operit/api/speech/SherpaSpeechProvider.kt)

**初始化流程**：

```kotlin
override suspend fun initialize(): Boolean {
    // 1. 从 assets 复制模型文件到本地缓存
    val localModelDir = AssetCopyUtils.copyAssetDirRecursive(
        context, "models/sherpa-ncnn-streaming-zipformer-bilingual-zh-en-2023-02-13", targetDir
    )

    // 2. 构建配置
    val featConfig = getFeatureExtractorConfig(sampleRate = 16000.0f, featureDim = 80)
    val modelConfig = ModelConfig(
        encoderParam = File(localModelDir, "encoder_jit_trace-pnnx.ncnn.param").absolutePath,
        encoderBin = File(localModelDir, "encoder_jit_trace-pnnx.ncnn.bin").absolutePath,
        // ... decoder, joiner, tokens
        numThreads = 4,
        useGPU = false
    )
    val decoderConfig = getDecoderConfig(method = "greedy_search", numActivePaths = 4)
    val recognizerConfig = RecognizerConfig(
        featConfig = featConfig,
        modelConfig = modelConfig,
        decoderConfig = decoderConfig,
        enableEndpoint = true,
        rule1MinTrailingSilence = 2.4f,
        rule2MinTrailingSilence = 1.2f,
        rule3MinUtteranceLength = 20.0f
    )

    // 3. 创建识别器
    recognizer = SherpaNcnn(config = recognizerConfig, assetManager = null)
}
```

**识别流程**：

```kotlin
// 录音循环中
while (isActive && state == RECOGNIZING) {
    val ret = audioRecord.read(audioBuffer, 0, audioBuffer.size)
    if (ret <= 0) break

    // VAD 检测（512 样本帧）
    val isSpeech = vadInstance.isSpeech(vadFrame)

    if (isSpeech) {
        // 送入识别器
        val samples = FloatArray(ret) { i -> audioBuffer[i] / 32768.0f }
        recognizer.acceptSamples(samples)

        // 解码
        while (recognizer.isReady()) {
            recognizer.decode()
        }

        // 获取结果
        val text = recognizer.text
        val isEndpoint = recognizer.isEndpoint()

        // 发射结果
        _recognitionResult.value = RecognitionResult(text = text, isFinal = isEndpoint)

        // 端点处理
        if (isEndpoint) {
            recognizer.reset(false)
            if (!continuousMode) break
        }
    }
}
```

### 5.3 SherpaMnnSpeechProvider（mnn 实现）

[SherpaMnnSpeechProvider.kt](file:///home/meizu/Documents/my_agent_projects/Operit/app/src/main/java/com/ai/assistance/operit/api/speech/SherpaMnnSpeechProvider.kt)

**与 ncnn 实现的主要区别**：

| 维度 | SherpaSpeechProvider (ncnn) | SherpaMnnSpeechProvider (mnn) |
|------|----------------------------|------------------------------|
| 识别器 | `SherpaNcnn` | `OnlineRecognizer` |
| 音频送入 | `recognizer.acceptSamples(samples)` | `stream.acceptWaveform(samples, sampleRate)` |
| 解码 | `recognizer.decode()` | `recognizer.decode(stream)` |
| 结果获取 | `recognizer.text` | `recognizer.getResult(stream)` |
| Stream 管理 | 无（内部管理） | 显式创建/释放 `OnlineStream` |
| 预滚动 | `recognizer.acceptSamples(preroll)` | `stream.acceptWaveform(preroll)` |
| VAD | 内置 `OnnxSileroVad` | 内置 `OnnxSileroVad`（sherpa-mnn VAD 已禁用） |

**Stream 生命周期**：

```kotlin
// startRecognition 中创建 Stream
stream = recognizer?.createStream()

// 送入音频
stream?.acceptWaveform(samples, 16000)
recognizer?.decode(stream)
val result = recognizer?.getResult(stream)

// stopRecognition 中释放 Stream
stream?.inputFinished()
while (recognizer?.isReady(stream) == true) {
    recognizer.decode(stream)
}
val finalResult = recognizer?.getResult(stream)
stream?.release()
stream = null
```

---

## 6. VAD 语音活动检测

### 6.1 OnnxSileroVad

[OnnxSileroVad.kt](file:///home/meizu/Documents/my_agent_projects/Operit/app/src/main/java/com/ai/assistance/operit/api/speech/OnnxSileroVad.kt)

`OnnxSileroVad` 是基于 ONNX Runtime 的 Silero VAD 实现，独立于 sherpa-ncnn/mnn 的 VAD。

```kotlin
class OnnxSileroVad(
    context: Context,
    sampleRate: Int = 16000,
    frameSize: Int = 512,
    mode: Mode = Mode.NORMAL,
    speechDurationMs: Int = 50,
    silenceDurationMs: Int = 300,
    modelAssetPath: String = "models/silero_vad.onnx",
) : AutoCloseable {

    enum class Mode { OFF, NORMAL, AGGRESSIVE, VERY_AGGRESSIVE }

    /** 检测一帧是否为语音 */
    fun isSpeech(frame: ShortArray): Boolean

    /** 重置状态 */
    fun reset()

    /** 设置语音持续时间阈值 */
    fun setSpeechDurationMs(ms: Int)

    /** 设置静音持续时间阈值 */
    fun setSilenceDurationMs(ms: Int)

    override fun close()
}
```

### 6.2 VAD 工作原理

```
输入: ShortArray (512 样本, 16kHz, ~32ms)
    │
    ▼
Short → Float (除以 32768.0f)
    │
    ▼
拼接上下文 (如果有 contextSize)
    │
    ▼
ONNX 推理 → 语音概率 (0.0 - 1.0)
    │
    ▼
阈值判断:
  NORMAL: 0.5
  AGGRESSIVE: 0.8
  VERY_AGGRESSIVE: 0.95
    │
    ▼
连续性检测:
  - 需要连续 speechDurationMs 的语音才确认为 speech
  - 需要连续 silenceDurationMs 的静音才确认为 silence
    │
    ▼
输出: Boolean (true = 语音, false = 静音)
```

### 6.3 VAD 在识别流程中的作用

```
音频缓冲区 (ShortArray)
    │
    ▼
┌─────────────────────────────────────────────┐
│ VAD 帧处理 (512 样本/帧)                     │
│ - 填充 vadFrame 到 512 样本                  │
│ - 调用 vad.isSpeech(vadFrame)               │
└─────────────────────────────────────────────┘
    │
    ├── 语音状态 ──▶ acceptSamples() → decode() → 获取结果
    │
    └── 静音状态 ──▶ 如果之前有语音:
                      inputFinished() → decode() → 获取最终结果
                      reset() → 准备下一段识别
```

**优势**：
- 减少无效计算：静音期间不送入识别器
- 自动分段：根据语音/静音边界自动切分 utterance
- 节省电量：降低 CPU 占用

---

## 7. 使用方法

### 7.1 初始化识别器（ncnn）

```kotlin
import com.k2fsa.sherpa.ncnn.*

// 1. 准备模型文件（从 assets 复制到本地）
val modelDir = File(context.filesDir, "sherpa-ncnn-model")

// 2. 构建配置
val featConfig = getFeatureExtractorConfig(sampleRate = 16000.0f, featureDim = 80)
val modelConfig = ModelConfig(
    encoderParam = File(modelDir, "encoder.param").absolutePath,
    encoderBin = File(modelDir, "encoder.bin").absolutePath,
    decoderParam = File(modelDir, "decoder.param").absolutePath,
    decoderBin = File(modelDir, "decoder.bin").absolutePath,
    joinerParam = File(modelDir, "joiner.param").absolutePath,
    joinerBin = File(modelDir, "joiner.bin").absolutePath,
    tokens = File(modelDir, "tokens.txt").absolutePath,
    numThreads = 4,
    useGPU = false
)
val decoderConfig = getDecoderConfig(method = "greedy_search", numActivePaths = 4)
val recognizerConfig = RecognizerConfig(
    featConfig = featConfig,
    modelConfig = modelConfig,
    decoderConfig = decoderConfig,
    enableEndpoint = true,
    rule1MinTrailingSilence = 2.4f,
    rule2MinTrailingSilence = 1.2f,
    rule3MinUtteranceLength = 20.0f
)

// 3. 创建识别器
val recognizer = SherpaNcnn(config = recognizerConfig, assetManager = null)
```

### 7.2 流式识别

```kotlin
// 送入音频样本（FloatArray，范围 [-1.0, 1.0]）
val samples = FloatArray(bufferSize) { i -> audioBuffer[i] / 32768.0f }
recognizer.acceptSamples(samples)

// 解码
while (recognizer.isReady()) {
    recognizer.decode()
}

// 获取当前文本
val text = recognizer.text

// 检查端点
if (recognizer.isEndpoint()) {
    // 处理最终结果
    recognizer.reset(false) // 重置准备下一段
}
```

### 7.3 使用预置模型配置

```kotlin
// 使用预置的中英双语模型（type = 2）
val modelConfig = getModelConfig(type = 2, useGPU = false)
```

### 7.4 初始化识别器（mnn）

```kotlin
import com.k2fsa.sherpa.mnn.*

val featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80)
val modelConfig = OnlineModelConfig(
    transducer = OnlineTransducerModelConfig(
        encoder = "encoder.mnn",
        decoder = "decoder.mnn",
        joiner = "joiner.mnn"
    ),
    tokens = "tokens.txt",
    numThreads = 2,
    modelType = "zipformer"
)
val recognizerConfig = OnlineRecognizerConfig(
    featConfig = featConfig,
    modelConfig = modelConfig,
    decodingMethod = "greedy_search"
)

val recognizer = OnlineRecognizer(assetManager = null, config = recognizerConfig)
val stream = recognizer.createStream()

// 送入音频
stream.acceptWaveform(samples, 16000)
recognizer.decode(stream)
val result = recognizer.getResult(stream)
println(result.text)

// 释放
stream.release()
recognizer.release()
```

### 7.5 使用 VAD

```kotlin
import com.ai.assistance.operit.api.speech.OnnxSileroVad

val vad = OnnxSileroVad(
    context = context,
    sampleRate = 16000,
    frameSize = 512,
    mode = OnnxSileroVad.Mode.NORMAL,
    speechDurationMs = 50,
    silenceDurationMs = 300
)

// 检测一帧
val isSpeech = vad.isSpeech(shortArrayOf(...))

// 重置
vad.reset()

// 释放
vad.close()
```

---

## 8. 文件索引

### k2fsa API 层

| 文件 | 路径 | 说明 |
|------|------|------|
| SherpaNcnn.kt | `app/src/main/java/com/k2fsa/sherpa/ncnn/SherpaNcnn.kt` | ncnn 识别器、配置数据类、预置模型 |
| OnlineRecognizer.kt | `app/src/main/java/com/k2fsa/sherpa/mnn/OnlineRecognizer.kt` | mnn 识别器、配置数据类、预置模型 |
| OnlineStream.kt | `app/src/main/java/com/k2fsa/sherpa/mnn/OnlineStream.kt` | mnn 识别流 |
| FeatureConfig.kt | `app/src/main/java/com/k2fsa/sherpa/mnn/FeatureConfig.kt` | mnn 特征配置 |
| Vad.kt | `app/src/main/java/com/k2fsa/sherpa/mnn/Vad.kt` | mnn 内置 VAD |

### 业务封装层

| 文件 | 路径 | 说明 |
|------|------|------|
| SpeechService.kt | `app/src/main/java/com/ai/assistance/operit/api/speech/SpeechService.kt` | 语音识别服务接口 |
| SherpaSpeechProvider.kt | `app/src/main/java/com/ai/assistance/operit/api/speech/SherpaSpeechProvider.kt` | ncnn 实现 |
| SherpaMnnSpeechProvider.kt | `app/src/main/java/com/ai/assistance/operit/api/speech/SherpaMnnSpeechProvider.kt` | mnn 实现 |
| OnnxSileroVad.kt | `app/src/main/java/com/ai/assistance/operit/api/speech/OnnxSileroVad.kt` | ONNX VAD 实现 |
| SpeechPrerollStore.kt | `app/src/main/java/com/ai/assistance/operit/api/speech/SpeechPrerollStore.kt` | 预滚动音频缓存 |
| PersonalWakeListener.kt | `app/src/main/java/com/ai/assistance/operit/api/speech/PersonalWakeListener.kt` | 语音唤醒 |
| PersonalWakeEnrollment.kt | `app/src/main/java/com/ai/assistance/operit/api/speech/PersonalWakeEnrollment.kt` | 唤醒词注册 |
