# Speech 模块设计思想与详细使用指南

## 一、模块概述

`speech` 模块是 Operit 应用的**语音识别（Speech-to-Text, STT）核心**，提供统一的语音识别服务接口，并支持多种识别引擎的灵活切换。模块同时集成了**语音活动检测（VAD）**和**个性化唤醒词（Personal Wake Word）**能力，构建了从音频采集到文本输出的完整语音处理链路。

模块包含以下核心组件：
- **`SpeechService`**：统一的语音识别服务接口
- **`SpeechServiceFactory`**：语音识别服务工厂，管理多引擎实例生命周期
- **`SpeechPrerollStore`**：音频预缓冲存储，支持唤醒词后的音频回溯
- **`OnnxSileroVad`**：基于 ONNX 的 Silero VAD 实现，用于语音活动检测
- **`SherpaSpeechProvider`**：基于 sherpa-ncnn 的本地流式语音识别
- **`SherpaMnnSpeechProvider`**：基于 sherpa-mnn 的本地流式语音识别
- **`OpenAISttProvider`**：基于 OpenAI API 的云端语音识别
- **`DeepgramSttProvider`**：基于 Deepgram API 的云端语音识别
- **`PersonalWakeEnrollment`** / **`PersonalWakeFeatureExtractor`** / **`PersonalWakeListener`**：个性化唤醒词注册与监听

---

## 二、核心设计思想

### 2.1 统一接口 + 多引擎策略

模块采用**策略模式（Strategy Pattern）**，通过 `SpeechService` 接口统一所有语音识别引擎的行为：

- 所有引擎（本地/云端）都实现相同的 `SpeechService` 接口
- 上层业务无需关心底层引擎差异，通过工厂方法获取实例
- 支持运行时切换引擎，工厂自动管理旧实例释放和新实例创建

### 2.2 本地优先 + 云端降级

模块设计了**本地引擎优先**的策略：

- 唤醒词检测必须使用本地引擎（`createWakeSpeechService` 强制回退到 `SHERPA_NCNN`）
- 本地引擎支持流式识别，延迟低、无需网络
- 云端引擎（OpenAI/Deepgram）识别精度高，但依赖网络且延迟较大
- 用户可在设置中自由切换

### 2.3 VAD 驱动的智能录音

所有识别引擎都集成了 **Silero VAD**（语音活动检测）：

- 录音时实时检测人声，过滤环境噪音
- 检测到语音开始后才真正送入识别器，减少无效计算
- 检测到静音后自动停止录音（非连续模式下）
- 支持预缓冲（Preroll）机制，避免漏掉唤醒词后的前半句

### 2.4 预缓冲（Preroll）机制

`SpeechPrerollStore` 实现了环形缓冲区，持续缓存最近 2.5 秒的音频：

- 唤醒词触发时，可以回溯获取唤醒词之前的音频片段
- 避免用户说唤醒词和指令之间的音频丢失
- 支持挂起（capturePending）→ 激活（armPending）→ 消费（consumePending）的三阶段状态机

### 2.5 个性化唤醒词

模块支持用户自定义唤醒词（非预设关键词）：

- 用户录制 1-N 条唤醒词模板
- 提取 MFCC + Delta + Delta2 特征序列
- 使用 DTW（动态时间规整）算法与实时音频进行相似度匹配
- 支持动态阈值调整、模板一致性校验、时长比例校验等多重过滤

---

## 三、核心架构与数据流

### 3.1 语音识别完整流程

```
┌─────────────────┐
│   用户点击录音   │
└────────┬────────┘
         ▼
┌─────────────────┐
│ 工厂创建服务实例 │  (SpeechServiceFactory.createSpeechService)
└────────┬────────┘
         ▼
┌─────────────────┐
│   初始化引擎    │  (load model / validate config)
└────────┬────────┘
         ▼
┌─────────────────┐
│   开始识别      │  (startRecognition)
└────────┬────────┘
         ▼
┌─────────────────┐
│ 应用预缓冲音频   │  (SpeechPrerollStore.consumePending)
└────────┬────────┘
         ▼
┌─────────────────┐
│ 启动 AudioRecord│
└────────┬────────┘
         ▼
┌─────────────────┐
│ 录音循环 + VAD   │
└────────┬────────┘
         ▼
┌─────────────────┐
│ 送入识别器      │  (本地流式 / 云端文件)
└────────┬────────┘
         ▼
┌─────────────────┐
│ 输出识别结果    │  (partial / final → StateFlow)
└─────────────────┘
```

### 3.2 本地引擎 vs 云端引擎对比

| 特性 | 本地引擎 (Sherpa) | 云端引擎 (OpenAI/Deepgram) |
|------|------------------|---------------------------|
| 识别方式 | 流式实时识别 | 录音完成后整文件上传 |
| 延迟 | 低（实时输出） | 高（需等待上传+识别） |
| 网络依赖 | 无 | 必须有网络 |
| 精度 | 中等 | 高 |
| 资源占用 | 较高（本地模型） | 低（仅录音+上传） |
| 文件大小限制 | 无 | 25MB |
| 支持语言 | zh, en | zh, en（取决于服务商） |

### 3.3 个性化唤醒词检测流程

```
┌─────────────────┐
│ 用户录制唤醒词   │  (PersonalWakeEnrollment.recordOneTemplate)
└────────┬────────┘
         ▼
┌─────────────────┐
│ VAD 截取语音段  │
└────────┬────────┘
         ▼
┌─────────────────┐
│ 提取特征序列    │  (MFCC + Delta + Delta2)
└────────┬────────┘
         ▼
┌─────────────────┐
│ 保存为模板      │
└────────┬────────┘
         ▼
┌─────────────────┐
│ 启动监听循环    │  (PersonalWakeListener.runLoop)
└────────┬────────┘
         ▼
┌─────────────────┐
│ 实时 VAD + 分段 │
└────────┬────────┘
         ▼
┌─────────────────┐
│ 提取特征并匹配   │  (DTW 相似度计算)
└────────┬────────┘
         ▼
┌─────────────────┐
│ 动态阈值判决    │
└────────┬────────┘
         ▼
┌─────────────────┐
│ 触发回调        │  (onTriggered)
└─────────────────┘
```

---

## 四、核心类详解

### 4.1 SpeechService（统一接口）

**类型**：`interface`

**核心状态枚举**：
```kotlin
enum class RecognitionState {
    UNINITIALIZED,  // 未初始化
    IDLE,           // 空闲
    PREPARING,      // 准备中
    RECOGNIZING,    // 识别中
    PROCESSING,     // 等待最终结果
    ERROR           // 出错
}
```

**核心数据类**：
```kotlin
data class RecognitionResult(
    val text: String,
    val isFinal: Boolean = false,
    val confidence: Float = 0f
)

data class RecognitionError(val code: Int, val message: String)
```

**核心 Flow 属性**：
| 属性 | 类型 | 说明 |
|------|------|------|
| `isInitialized` | `StateFlow<Boolean>` | 引擎是否初始化完成 |
| `recognitionStateFlow` | `StateFlow<RecognitionState>` | 识别状态变化 |
| `recognitionResultFlow` | `StateFlow<RecognitionResult>` | 识别结果（中间+最终） |
| `recognitionErrorFlow` | `StateFlow<RecognitionError>` | 错误信息 |
| `volumeLevelFlow` | `StateFlow<Float>` | 音量级别 0.0-1.0 |

**核心方法**：
- `initialize()`: 初始化引擎
- `startRecognition(languageCode, continuousMode, partialResults, audioSource)`: 开始识别
- `stopRecognition()`: 停止识别并返回最终结果
- `cancelRecognition()`: 取消识别，不返回结果
- `shutdown()`: 释放资源
- `recognize(audioData)`: 识别预录音频（主要用于本地引擎）

### 4.2 SpeechServiceFactory（服务工厂）

**类型**：`object`（单例）

**支持的引擎类型**：
```kotlin
enum class SpeechServiceType {
    SHERPA_NCNN,    // 本地 sherpa-ncnn
    OPENAI_STT,     // OpenAI Whisper API
    DEEPGRAM_STT,   // Deepgram API
}
```

**核心方法**：

#### `createSpeechService(context: Context): SpeechService`
根据用户偏好配置创建对应类型的服务实例。

#### `createWakeSpeechService(context: Context): SpeechService`
创建用于唤醒词检测的语音服务。如果用户选择了云端引擎，会**强制回退到本地引擎**（`SHERPA_NCNN`），因为唤醒词检测必须在本地实时运行。

#### `getInstance(context: Context): SpeechService`
获取单例实例。如果引擎类型发生变化，会自动释放旧实例并创建新实例。

#### 本地引擎引用计数管理
工厂对本地引擎（`SHERPA_NCNN`）采用**引用计数 + Lease 模式**：
- `acquireLocalSpeechService`: 增加引用计数，返回包装后的 Lease 实例
- `releaseLocalSpeechService`: 减少引用计数，归零时真正释放
- 防止多个组件同时持有本地引擎时的重复创建/释放问题

### 4.3 OnnxSileroVad（语音活动检测）

**类型**：`class`

基于 ONNX Runtime 加载 Silero VAD 模型，实现语音/非语音分类。

**检测模式**：
```kotlin
enum class Mode {
    OFF,            // 关闭 VAD
    NORMAL,         // 正常模式 (threshold=0.5)
    AGGRESSIVE,     // 激进模式 (threshold=0.8)
    VERY_AGGRESSIVE // 非常激进 (threshold=0.95)
}
```

**核心参数**：
| 参数 | 默认值 | 说明 |
|------|--------|------|
| `sampleRate` | 16000 | 采样率 |
| `frameSize` | 512 | 每帧采样数（16kHz下约32ms） |
| `speechDurationMs` | 50 | 判定为语音所需的最短持续时间 |
| `silenceDurationMs` | 300 | 判定为静音所需的最短持续时间 |

**使用方法**：
```kotlin
val vad = OnnxSileroVad(context, mode = OnnxSileroVad.Mode.NORMAL)
val isSpeech = vad.isSpeech(shortArrayOf(...)) // 传入 512 个采样点
```

### 4.4 SherpaSpeechProvider（本地 ncnn 引擎）

**类型**：`class`

基于 [sherpa-ncnn](https://github.com/k2-fsa/sherpa-ncnn) 的本地流式语音识别实现。

**模型配置**：
- 默认模型：`sherpa-ncnn-streaming-zipformer-bilingual-zh-en-2023-02-13`
- 支持中英文双语识别
- 使用 Zipformer 架构，4 线程 CPU 推理
- 启用端点检测（Endpoint Detection）

**录音流程**：
1. 初始化 `AudioRecord`（16kHz, 单声道, 16bit PCM）
2. 应用预缓冲音频（如果有）
3. 启动录音循环
4. 每帧音频经过 VAD 检测
5. 语音帧送入 `SherpaNcnn` 识别器
6. 识别器输出中间结果（partial）和最终结果（final）
7. 检测到端点时自动分段（continuousMode 为 false 时停止）

### 4.5 SherpaMnnSpeechProvider（本地 MNN 引擎）

**类型**：`class`

基于 [sherpa-mnn](https://github.com/k2-fsa/sherpa-mnn) 的本地流式语音识别实现。

与 `SherpaSpeechProvider` 的主要区别：
- 使用 MNN 推理框架替代 ncnn
- 模型格式为 `.mnn`（int8 量化）
- 默认 2 线程 CPU 推理
- VAD 集成方式略有不同（当前内置 VAD 暂时禁用，使用外部 `OnnxSileroVad`）

### 4.6 OpenAISttProvider / DeepgramSttProvider（云端引擎）

**类型**：`class`

基于 HTTP API 的云端语音识别实现。

**工作流程**：
1. 启动录音，将音频写入临时 WAV 文件
2. 可选：使用 VAD 检测语音，只保留有效语音段（+ 预缓冲）
3. 停止录音后，补写 WAV 文件头
4. 通过 OkHttp 上传文件到 API
5. 解析 JSON 响应，提取识别文本
6. 删除临时文件

**API 配置**：
- 从 `SpeechServicesPreferences` 读取 endpointUrl、apiKey、model
- OpenAI 使用 `Authorization: Bearer $apiKey`
- Deepgram 使用 `Authorization: Token $apiKey`
- 支持语言参数自动映射（zh/en）

### 4.7 SpeechPrerollStore（预缓冲存储）

**类型**：`object`（单例）

环形缓冲区，持续缓存最近 2500ms 的音频（16kHz 采样率下约 40000 个采样点）。

**核心方法**：

| 方法 | 说明 |
|------|------|
| `appendPcm(pcm, length)` | 持续追加音频到环形缓冲区 |
| `capturePending(windowMs)` | 捕获最近 N 毫秒的音频，保存为 pending |
| `armPending()` | 激活 pending 音频，允许被消费 |
| `consumePending(maxAgeMs)` | 消费 pending 音频（获取并清空） |
| `setPendingWakePhrase(phrase, regexEnabled)` | 设置 pending 的唤醒词信息 |
| `consumePendingWakePhrase(maxAgeMs)` | 消费 pending 的唤醒词信息 |

**典型使用场景**：
```kotlin
// 唤醒词检测线程持续追加音频
SpeechPrerollStore.appendPcm(buffer, readSize)

// 唤醒词触发时，捕获预缓冲
SpeechPrerollStore.capturePending(windowMs = 1600)
SpeechPrerollStore.armPending()

// 语音识别启动时，消费预缓冲
val preroll = SpeechPrerollStore.consumePending()
if (preroll != null) recognizer.acceptSamples(preroll)
```

### 4.8 个性化唤醒词组件

#### PersonalWakeEnrollment（唤醒词注册）

引导用户录制唤醒词模板：
- 使用 VAD 自动截取语音段
- 最短语音 250ms，最长 6 秒
- 语音结束后 350ms 静音自动停止
- 返回提取的特征数组

#### PersonalWakeFeatureExtractor（特征提取）

纯 Kotlin 实现的音频特征提取：
- **预处理**：归一化、预加重、汉宁窗
- **FFT**： Cooley-Tukey 算法实现
- **Mel 滤波器组**：40 维 Mel 频谱
- **MFCC**：13 维 + Delta + Delta2 = 39 维特征
- **CMVN**：倒谱均值方差归一化
- **帧限制**：最多 64 帧，超出时平均池化

#### PersonalWakeListener（唤醒词监听）

持续监听麦克风，检测个性化唤醒词：
- VAD 分段后提取特征
- 使用 **DTW（动态时间规整）** 计算与模板的相似度
- 动态阈值：基于模板间一致性自适应调整
- 多重校验：
  - RMS 能量门限（过滤太弱的音频）
  - 时长比例校验（0.75x ~ 1.25x）
  - 模板间一致性校验
  - 最佳/次佳匹配差距校验

---

## 五、使用示例

### 5.1 基本语音识别

```kotlin
// 获取语音识别服务（根据用户设置自动选择引擎）
val speechService = SpeechServiceFactory.getInstance(context)

// 观察识别结果
lifecycleScope.launch {
    speechService.recognitionResultFlow.collect { result ->
        if (result.isFinal) {
            Log.d("Speech", "最终识别结果: ${result.text}")
        } else {
            Log.d("Speech", "中间结果: ${result.text}")
        }
    }
}

// 观察音量
lifecycleScope.launch {
    speechService.volumeLevelFlow.collect { volume ->
        // 更新 UI 音量指示器
        volumeIndicator.progress = (volume * 100).toInt()
    }
}

// 开始识别
lifecycleScope.launch {
    val success = speechService.startRecognition(
        languageCode = "zh-CN",
        continuousMode = false,  // 非连续模式，检测到端点自动停止
        partialResults = true     // 返回中间结果
    )
    if (!success) {
        Log.e("Speech", "启动识别失败")
    }
}

// 停止识别（手动触发）
lifecycleScope.launch {
    speechService.stopRecognition()
}
```

### 5.2 切换识别引擎

```kotlin
// 创建特定类型的服务
val localService = SpeechServiceFactory.createSpeechService(
    context, 
    SpeechServiceFactory.SpeechServiceType.SHERPA_NCNN
)

val openaiService = SpeechServiceFactory.createSpeechService(
    context,
    SpeechServiceFactory.SpeechServiceType.OPENAI_STT
)

// 重置单例（切换引擎时调用）
SpeechServiceFactory.resetInstance()
```

### 5.3 使用预缓冲机制

```kotlin
// 在唤醒词检测线程中持续追加音频
while (isActive) {
    val read = audioRecord.read(buffer, 0, buffer.size)
    if (read > 0) {
        SpeechPrerollStore.appendPcm(buffer, read)
    }
}

// 唤醒词触发时
capturePrerollAndStartRecognition()

fun capturePrerollAndStartRecognition() {
    // 捕获 1.6 秒的预缓冲音频
    SpeechPrerollStore.capturePending(windowMs = 1600)
    SpeechPrerollStore.armPending()
    
    // 启动语音识别，会自动消费预缓冲
    lifecycleScope.launch {
        speechService.startRecognition()
    }
}
```

### 5.4 注册个性化唤醒词

```kotlin
// 录制唤醒词模板（用户说 3 次唤醒词）
val templates = mutableListOf<FloatArray>()

repeat(3) { index ->
    showToast("请说唤醒词（第 ${index + 1} 次）")
    val feature = PersonalWakeEnrollment.recordOneTemplate(context)
    if (feature != null) {
        templates.add(feature)
    }
}

// 保存模板到本地存储
saveTemplates(templates)
```

### 5.5 启动个性化唤醒词监听

```kotlin
// 加载已保存的模板
val templates = loadTemplates()

// 创建监听器
val wakeListener = PersonalWakeListener(
    context = context,
    templatesProvider = { templates },
    onTriggered = { similarity ->
        Log.d("Wake", "唤醒词触发！相似度: $similarity")
        // 启动语音识别或执行其他操作
        startVoiceInteraction()
    }
)

// 在协程中启动监听
lifecycleScope.launch(Dispatchers.Default) {
    wakeListener.runLoop(
        PersonalWakeListener.Config(
            similarityThreshold = 0.865f,
            requiredTemplateMatches = 1
        )
    )
}

// 停止监听
wakeListener.stop()
```

---

## 六、配置与调优

### 6.1 引擎选择配置

通过 `SpeechServicesPreferences` 配置：

```kotlin
val prefs = SpeechServicesPreferences(context)

// 设置引擎类型
prefs.saveSttServiceType(SpeechServiceFactory.SpeechServiceType.SHERPA_NCNN)

// 配置 OpenAI/Deepgram
prefs.saveSttHttpConfig(
    endpointUrl = "https://api.openai.com/v1/audio/transcriptions",
    apiKey = "sk-...",
    modelName = "whisper-1"
)
```

### 6.2 VAD 调优

```kotlin
val vad = OnnxSileroVad(
    context = context,
    mode = OnnxSileroVad.Mode.NORMAL,  // 根据环境噪音调整
    speechDurationMs = 50,              // 语音最短持续时间
    silenceDurationMs = 300             // 静音判定时间
)
```

| 场景 | 推荐模式 | speechDurationMs | silenceDurationMs |
|------|---------|------------------|-------------------|
| 安静环境 | NORMAL | 50 | 300 |
| 嘈杂环境 | AGGRESSIVE | 100 | 500 |
| 快速交互 | NORMAL | 30 | 200 |

### 6.3 唤醒词调优

```kotlin
PersonalWakeListener.Config(
    similarityThreshold = 0.865f,      // 基础相似度阈值
    dynamicThresholdMargin = 0.02f,    // 动态阈值边距
    minDynamicThresholdFloor = 0.84f,  // 动态阈值下限
    maxBestSecondGap = 0.04f,          // 最佳/次佳差距上限
    minIntraSimilarity = 0.80f,        // 模板间最小一致性
    requiredTemplateMatches = 1,       // 需要匹配的模板数
    minDurationRatio = 0.75f,          // 最短时长比例
    maxDurationRatio = 1.25f,          // 最长时长比例
    dtwBand = 4                        // DTW 搜索带宽
)
```

---

## 七、最佳实践

1. **生命周期管理**：Activity/Fragment 销毁时调用 `speechService.shutdown()` 释放资源
2. **错误处理**：观察 `recognitionErrorFlow` 及时处理初始化失败、录音失败等错误
3. **权限申请**：使用语音识别前必须申请 `RECORD_AUDIO` 权限
4. **唤醒词优化**：建议在安静环境下录制 3 条以上模板，提高识别率
5. **引擎选择**：
   - 网络良好且对精度要求高 → OpenAI/Deepgram
   - 需要离线使用或低延迟 → Sherpa 本地引擎
   - 唤醒词检测 → 必须使用本地引擎
6. **预缓冲配合**：唤醒词检测和语音识别共用 `SpeechPrerollStore`，确保音频不丢失
7. **音量反馈**：使用 `volumeLevelFlow` 提供实时音量可视化，改善用户体验
