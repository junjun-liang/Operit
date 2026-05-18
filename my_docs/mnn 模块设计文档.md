# mnn 模块软件设计架构与使用文档

## 目录

1. [模块概述](#1-模块概述)
2. [整体架构](#2-整体架构)
3. [MNN 引擎核心架构](#3-mnn-引擎核心架构)
4. [JNI 桥接层](#4-jni-桥接层)
5. [Kotlin API 层](#5-kotlin-api-层)
6. [LLM 引擎集成](#6-llm-引擎集成)
7. [构建配置](#7-构建配置)
8. [项目中的使用方式](#8-项目中的使用方式)
9. [使用方法](#9-使用方法)
10. [文件索引](#10-文件索引)

---

## 1. 模块概述

**mnn** 模块是 Operit 项目中集成的 **阿里巴巴 MNN（Mobile Neural Network）** 深度学习推理引擎的 Android 封装模块。MNN 是一个轻量级、高性能的深度学习推理引擎，支持多端（iOS/Android/嵌入式设备）部署。

### 1.1 核心能力

| 能力 | 说明 |
|------|------|
| 通用模型推理 | 支持 CNN/RNN/Transformer 等主流神经网络模型的推理 |
| LLM 本地推理 | 集成 MNN LLM 引擎，支持大语言模型本地运行（Qwen 系列等） |
| 多后端支持 | CPU（ARM NEON）、OpenCL、Vulkan、OpenGL 等 |
| 动态形状 | 支持动态输入形状的模型推理 |
| 图像预处理 | 内置图像格式转换、归一化、裁剪缩放等 |
| 低内存模式 | 支持权重量化、KV-Cache 优化等低内存推理 |

### 1.2 模块定位

```
┌─────────────────────────────────────────────┐
│           Operit 应用层                       │
│  ┌─────────────┐  ┌─────────────────────┐  │
│  │ AI Chat     │  │ 图像识别             │  │
│  │ 本地LLM     │  │ 语音合成             │  │
│  └─────────────┘  └─────────────────────┘  │
├─────────────────────────────────────────────┤
│           业务封装层                          │
│  ┌─────────────────────────────────────────┐│
│  │ MNNProvider (LLM Provider)             ││
│  │ AIService 接口实现                      ││
│  └─────────────────────────────────────────┘│
├─────────────────────────────────────────────┤
│           mnn 模块 (Kotlin + JNI Wrapper)    │
│  ┌─────────────┐  ┌─────────────────────┐  │
│  │ MNNLlmSession│  │ MNNNetInstance      │  │
│  │ MNNLlmNative │  │ MNNModule           │  │
│  │ MNNNetNative │  │ MNNModuleNative     │  │
│  │ MNNImageProcess│  │ MNNForwardType    │  │
│  └─────────────┘  └─────────────────────┘  │
├─────────────────────────────────────────────┤
│           JNI 桥接层 (C++)                   │
│  ┌─────────────┐  ┌─────────────────────┐  │
│  │ mnnnetnative.cpp                      │  │
│  │ mnnmodulennative.cpp                  │  │
│  │ mnnllmnative.cpp                      │  │
│  └─────────────┘  └─────────────────────┘  │
├─────────────────────────────────────────────┤
│           MNN 引擎核心 (C++)                 │
│  ┌─────────────┐  ┌─────────────────────┐  │
│  │ Interpreter │  │ Module (Express)    │  │
│  │ Session     │  │ LLM Engine          │  │
│  │ Tensor      │  │ Transformer Fuse    │  │
│  └─────────────┘  └─────────────────────┘  │
├─────────────────────────────────────────────┤
│           后端加速层                          │
│  CPU (ARM82/NEON) / OpenCL / Vulkan / OpenGL │
└─────────────────────────────────────────────┘
```

---

## 2. 整体架构

### 2.1 分层架构

```
┌─────────────────────────────────────────────────────────────┐
│  应用层                                                      │
│  - AI Chat 界面                                             │
│  - 本地大模型对话                                           │
│  - 多模态输入（文本/图像/音频）                              │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  AIService 接口层                                            │
│  interface AIService {                                      │
│    suspend fun sendMessage(...)                              │
│    fun cancelStreaming()                                     │
│    val inputTokenCount: Int                                  │
│    val outputTokenCount: Int                                 │
│  }                                                          │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  MNNProvider (业务封装)                                      │
│  - 模型目录管理                                              │
│  - 后端类型映射 (forwardType → backend_type)                │
│  - 内存模式选择 (CPU=low, GPU=normal)                        │
│  - Token 计数统计                                            │
│  - 流式输出适配                                              │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  Kotlin API 层 (com.ai.assistance.mnn)                      │
│  ┌─────────────────┐  ┌─────────────────────────────────────┐│
│  │ MNNLlmSession   │  │ MNNNetInstance                      ││
│  │ - create()      │  │ - createFromFile()                  ││
│  │ - generate()    │  │ - createSession()                   ││
│  │ - generateStream│  │ - Session.Tensor                    ││
│  │ - chat()        │  │ - run()                             ││
│  │ - tokenize()    │  │ - getInput()/getOutput()            ││
│  │ - cancel()      │  │ - release()                         ││
│  └─────────────────┘  └─────────────────────────────────────┘│
│  ┌─────────────────┐  ┌─────────────────────────────────────┐│
│  │ MNNModule       │  │ MNNImageProcess                     ││
│  │ - load()        │  │ - convertBitmap()                   ││
│  │ - forward()     │  │ - convertBuffer()                   ││
│  │ - createInputVar│  │ - Config (mean/normal/format)       ││
│  └─────────────────┘  └─────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼ JNI
┌─────────────────────────────────────────────────────────────┐
│  C++ JNI 桥接层                                              │
│  ┌─────────────────┐  ┌─────────────────────────────────────┐│
│  │ mnnnetnative.cpp│  │ mnnmodulennative.cpp                ││
│  │ - Interpreter   │  │ - Module::load()                    ││
│  │ - Session       │  │ - Module::onForward()               ││
│  │ - Tensor        │  │ - VARP 管理                         ││
│  │ - ImageProcess  │  │                                     ││
│  └─────────────────┘  └─────────────────────────────────────┘│
│  ┌─────────────────────────────────────────────────────────┐│
│  │ mnnllmnative.cpp                                        ││
│  │ - Llm::createLLM()                                      ││
│  │ - Llm::load() / response() / generate()                 ││
│  │ - tokenizer_encode / tokenizer_decode                   ││
│  │ - ChatTemplate (minja)                                  ││
│  │ - Cancellation / AudioCallback                          ││
│  └─────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  MNN 引擎核心 (C++)                                          │
│  ┌─────────────────┐  ┌─────────────────────────────────────┐│
│  │ Interpreter     │  │ Express::Module                     ││
│  │ - createFromFile│  │ - load() / onForward()              ││
│  │ - createSession │  │ - VARP (Variable)                   ││
│  │ - runSession    │  │ - Executor::RuntimeManager          ││
│  │ - getSessionIO  │  │                                     ││
│  └─────────────────┘  └─────────────────────────────────────┘│
│  ┌─────────────────────────────────────────────────────────┐│
│  │ MNN::Transformer::Llm                                   ││
│  │ - createLLM() / load()                                  ││
│  │ - response() / generate()                               ││
│  │ - forward() / forwardRaw()                              ││
│  │ - tokenizer_encode / tokenizer_decode                   ││
│  │ - KV-Cache 管理                                         ││
│  │ - ChatTemplate (apply_chat_template)                    ││
│  └─────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
```

---

## 3. MNN 引擎核心架构

### 3.1 Interpreter（解释器）

[Interpreter.hpp](file:///home/meizu/Documents/my_agent_projects/Operit/mnn/src/main/cpp/MNN/include/MNN/Interpreter.hpp)

```cpp
class Interpreter {
public:
    static Interpreter* createFromFile(const char* file);
    static Interpreter* createFromBuffer(const void* buffer, size_t size);
    static void destroy(Interpreter* net);

    Session* createSession(const ScheduleConfig& config);
    ErrorCode runSession(Session* session);
    ErrorCode resizeSession(Session* session);

    Tensor* getSessionInput(Session* session, const char* name);
    Tensor* getSessionOutput(Session* session, const char* name);

    void releaseSession(Session* session);
};
```

**设计要点**：
- `Interpreter` 是模型的容器，一个模型文件对应一个 Interpreter
- 多个 `Session` 可共享同一个 Interpreter（多线程推理）
- `Session` 是推理执行的上下文，包含输入输出张量

### 3.2 Session 与 Tensor

```cpp
struct ScheduleConfig {
    MNNForwardType type = MNN_FORWARD_CPU;  // 推理后端
    int numThread = 4;                       // 线程数
    std::vector<std::string> saveTensors;    // 保留中间张量
    Path path;                               // 子图路径
    BackendConfig* backendConfig = nullptr;  // 后端配置
};

class Tensor {
public:
    void copyFromHostTensor(const Tensor* hostTensor);
    void copyToHostTensor(Tensor* hostTensor);
    const std::vector<int>& shape() const;
    void* host<float>();   // 获取浮点数据指针
    void* host<int>();     // 获取整型数据指针
};
```

### 3.3 Express::Module（动态形状模型）

[Module.hpp](file:///home/meizu/Documents/my_agent_projects/Operit/mnn/src/main/cpp/MNN/include/MNN/expr/Module.hpp)

```cpp
class Module {
public:
    static Module* load(
        const std::vector<std::string>& inputs,
        const std::vector<std::string>& outputs,
        const char* fileName,
        const std::shared_ptr<Executor::RuntimeManager>& rtMgr,
        const Config* config = nullptr
    );

    std::vector<VARP> onForward(const std::vector<VARP>& inputs);
    static void destroy(Module* m);

    struct Config {
        bool dynamic = false;      // 动态模式
        bool shapeMutable = true;  // 形状可变
        bool rearrange = false;    // 权重重排
    };
};
```

**与 Interpreter 的区别**：

| 维度 | Interpreter API | Express::Module API |
|------|----------------|---------------------|
| 适用场景 | 固定形状模型 | 动态形状模型 |
| 输入设置 | 直接操作 Tensor | 通过 VARP（Variable） |
| 形状变化 | 需调用 resizeSession | 自动处理 |
| 灵活性 | 低 | 高 |
| 性能 | 略高（预分配） | 略低（动态分配） |

### 3.4 MNN::Transformer::Llm（大语言模型引擎）

[llm.hpp](file:///home/meizu/Documents/my_agent_projects/Operit/mnn/src/main/cpp/MNN/transformers/llm/engine/include/llm/llm.hpp)

```cpp
class Llm {
public:
    static Llm* createLLM(const std::string& config_path);
    static void destroy(Llm* llm);

    bool load();
    void reset();

    // 文本生成
    void response(const std::string& user_content, std::ostream* os = &std::cout,
                  const char* end_with = nullptr, int max_new_tokens = -1);
    void response(const ChatMessages& chat_prompts, std::ostream* os = &std::cout,
                  const char* end_with = nullptr, int max_new_tokens = -1);

    std::vector<int> generate(const std::vector<int>& input_ids, int max_new_tokens = -1);

    // Tokenizer
    std::vector<int> tokenizer_encode(const std::string& query);
    std::string tokenizer_decode(int token);

    // 聊天模板
    std::string apply_chat_template(const ChatMessages& chat_prompts) const;

    // 配置
    std::string dump_config();
    bool set_config(const std::string& content);

    const LlmContext* getContext() const;
};

struct LlmContext {
    int prompt_len = 0;
    int gen_seq_len = 0;
    int all_seq_len = 0;
    int64_t load_us = 0;
    int64_t prefill_us = 0;
    int64_t decode_us = 0;
    int64_t sample_us = 0;
    LlmStatus status = LlmStatus::NOT_LOADED;
    std::string generate_str;
    std::vector<int> history_tokens;
    std::vector<int> output_tokens;
};
```

**LLM 推理流程**：

```
创建 Llm (createLLM)
    │
    ▼
设置配置 (set_config: tmp_path, backend_type, thread_num, precision, memory)
    │
    ▼
加载模型 (load)
    │
    ▼
编码输入 (tokenizer_encode / apply_chat_template)
    │
    ▼
预填充 (Prefill) ──▶ forward(input_ids, is_prefill=true)
    │
    ▼
逐 token 解码 (Decode) ──▶ while (!isEndpoint) { forward(input_ids, is_prefill=false) }
    │
    ▼
采样输出 (sample) ──▶ tokenizer_decode(token)
    │
    ▼
返回生成文本
```

---

## 4. JNI 桥接层

### 4.1 库加载

[MNNLibraryLoader.kt](file:///home/meizu/Documents/my_agent_projects/Operit/mnn/src/main/java/com/ai/assistance/mnn/MNNLibraryLoader.kt)

```kotlin
internal object MNNLibraryLoader {
    fun loadLibraries() {
        System.loadLibrary("MNN")         // MNN 核心库
        System.loadLibrary("MNNWrapper")  // JNI 包装库
    }
}
```

### 4.2 mnnnetnative.cpp — 基础推理 JNI

[mnnnetnative.cpp](file:///home/meizu/Documents/my_agent_projects/Operit/mnn/src/main/cpp/mnnnetnative.cpp)

```cpp
// 从文件创建 Interpreter
JNIEXPORT jlong JNICALL
Java_com_ai_assistance_mnn_MNNNetNative_nativeCreateNetFromFile(JNIEnv* env, jclass type, jstring modelName_) {
    const char* modelName = env->GetStringUTFChars(modelName_, 0);
    auto interpreter = MNN::Interpreter::createFromFile(modelName);
    env->ReleaseStringUTFChars(modelName_, modelName);
    return (jlong)interpreter;
}

// 创建 Session
JNIEXPORT jlong JNICALL
Java_com_ai_assistance_mnn_MNNNetNative_nativeCreateSession(
    JNIEnv* env, jclass type, jlong netPtr, jint forwardType, jint numThread,
    jobjectArray jsaveTensors, jobjectArray joutputTensors) {
    MNN::ScheduleConfig config;
    config.type = (MNNForwardType)forwardType;
    config.numThread = numThread;
    // ... 解析 saveTensors / outputTensors
    auto session = ((MNN::Interpreter*)netPtr)->createSession(config);
    return (jlong)session;
}

// 运行推理
JNIEXPORT jint JNICALL
Java_com_ai_assistance_mnn_MNNNetNative_nativeRunSession(JNIEnv* env, jclass type, jlong netPtr, jlong sessionPtr) {
    auto net = (MNN::Interpreter*)netPtr;
    auto session = (MNN::Session*)sessionPtr;
    return net->runSession(session);
}

// 图像预处理
JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_mnn_MNNNetNative_nativeConvertBitmapToTensor(...)
```

### 4.3 mnnmodulennative.cpp — Module API JNI

[mnnmodulennative.cpp](file:///home/meizu/Documents/my_agent_projects/Operit/mnn/src/main/cpp/mnnmodulennative.cpp)

```cpp
// 从文件创建 Module
JNIEXPORT jlong JNICALL
Java_com_ai_assistance_mnn_MNNModuleNative_nativeCreateModuleFromFile(...) {
    ScheduleConfig config;
    config.type = (MNNForwardType)forwardType;
    config.numThread = numThread;

    BackendConfig backendConfig;
    backendConfig.precision = (BackendConfig::PrecisionMode)precision;
    backendConfig.memory = (BackendConfig::MemoryMode)memoryMode;
    config.backendConfig = &backendConfig;

    auto rtmgr = Executor::RuntimeManager::createRuntimeManager(config);
    rtmgr->setMode(Interpreter::Session_Input_Inside);

    Module::Config moduleConfig;
    moduleConfig.shapeMutable = true;
    moduleConfig.rearrange = true;

    Module* module = Module::load(inputNames, outputNames, modelPath.c_str(), rtmgr, &moduleConfig);
    return reinterpret_cast<jlong>(module);
}

// 前向推理
JNIEXPORT jlongArray JNICALL
Java_com_ai_assistance_mnn_MNNModuleNative_nativeForward(JNIEnv* env, jclass clazz, jlong modulePtr, jlongArray jinputVarPtrs) {
    Module* module = reinterpret_cast<Module*>(modulePtr);
    std::vector<VARP> inputs = ...;  // 从 jlongArray 构造
    std::vector<VARP> outputs = module->onForward(inputs);
    // 返回 jlongArray
}
```

### 4.4 mnnllmnative.cpp — LLM 引擎 JNI

[mnnllmnative.cpp](file:///home/meizu/Documents/my_agent_projects/Operit/mnn/src/main/cpp/mnnllmnative.cpp)

```cpp
// 创建 LLM 实例
JNIEXPORT jlong JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeCreateLlm(JNIEnv* env, jclass clazz, jstring jconfigPath) {
    std::string configPath = jstringToString(env, jconfigPath);
    Llm* llm = Llm::createLLM(configPath);
    return reinterpret_cast<jlong>(llm);
}

// 加载模型
JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeLoadLlm(JNIEnv* env, jclass clazz, jlong llmPtr) {
    Llm* llm = reinterpret_cast<Llm*>(llmPtr);
    return llm->load() ? JNI_TRUE : JNI_FALSE;
}

// 流式生成（带历史记录）
JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeGenerateStream(...) {
    Llm* llm = reinterpret_cast<Llm*>(llmPtr);
    ChatMessages history = parseChatHistory(env, jhistory);

    // 设置取消检查回调
    llm->setStopCallback([llmPtr]() { return checkCancelFlag(llmPtr); });

    // 执行生成
    llm->response(history, &oss, nullptr, maxTokens);

    // 逐 token 回调
    // 通过 std::ostream 子类实现流式输出
}

// Token 计数
JNIEXPORT jint JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeCountTokens(JNIEnv* env, jclass clazz, jlong llmPtr, jstring jtext) {
    Llm* llm = reinterpret_cast<Llm*>(llmPtr);
    std::string text = jstringToString(env, jtext);
    std::vector<int> tokens = llm->tokenizer_encode(text);
    return static_cast<jint>(tokens.size());
}
```

**关键设计**：
- 取消机制：全局 `gCancelFlags` map，通过 `nativeCancel()` 设置标志，推理循环中检查
- 音频回调：`gAudioCallbacks` map，支持从 LLM 获取语音波形输出
- 聊天模板：集成 minja 库，支持 Jinja2 格式的 chat_template

---

## 5. Kotlin API 层

### 5.1 MNNNetInstance — 基础推理封装

[MNNNetInstance.kt](file:///home/meizu/Documents/my_agent_projects/Operit/mnn/src/main/java/com/ai/assistance/mnn/MNNNetInstance.kt)

```kotlin
class MNNNetInstance private constructor(private var netInstance: Long) {

    companion object {
        fun createFromFile(fileName: String): MNNNetInstance?
        fun createFromBuffer(buffer: ByteArray): MNNNetInstance?
    }

    data class Config(
        var forwardType: Int = MNNForwardType.FORWARD_CPU.type,
        var numThread: Int = 4,
        var saveTensors: Array<String>? = null,
        var outputTensors: Array<String>? = null
    )

    fun createSession(config: Config? = null): Session?

    inner class Session internal constructor(private var sessionInstance: Long) {
        fun run()
        fun reshape()
        fun getInput(name: String?): Tensor?
        fun getOutput(name: String?): Tensor?

        inner class Tensor internal constructor(private val tensorInstance: Long) {
            fun reshape(dims: IntArray)
            fun setInputFloatData(data: FloatArray)
            fun setInputIntData(data: IntArray)
            fun getFloatData(): FloatArray
            fun getIntData(): IntArray
            fun getUINT8Data(): ByteArray
            fun getDimensions(): IntArray
        }
    }

    fun release()
}
```

### 5.2 MNNModule — 动态形状模型封装

[MNNModule.kt](file:///home/meizu/Documents/my_agent_projects/Operit/mnn/src/main/java/com/ai/assistance/mnn/MNNModule.kt)

```kotlin
class MNNModule private constructor(private var modulePtr: Long, ...) {

    companion object {
        fun load(filePath: String, config: Config): MNNModule?
    }

    data class Config(
        val inputNames: List<String>,
        val outputNames: List<String>,
        val forwardType: Int = MNNForwardType.FORWARD_CPU.type,
        val numThread: Int = 4,
        val precision: Int = PrecisionMode.NORMAL,
        val memoryMode: Int = MemoryMode.NORMAL
    )

    object PrecisionMode {
        const val NORMAL = 0
        const val HIGH = 1
        const val LOW = 2
        const val LOW_BF16 = 3
    }

    object MemoryMode {
        const val NORMAL = 0
        const val LOW = 1
    }

    class Variable internal constructor(private var varPtr: Long) {
        fun setFloatData(data: FloatArray): Boolean
        fun setIntData(data: IntArray): Boolean
        fun getFloatData(): FloatArray?
        fun getShape(): IntArray?
        fun release()
    }

    fun createInputVariable(shape: IntArray, dataFormat: Int, dataType: Int): Variable?
    fun forward(inputs: List<Variable>): List<Variable>?
    fun release()
}
```

### 5.3 MNNForwardType — 推理后端枚举

[MNNForwardType.kt](file:///home/meizu/Documents/my_agent_projects/Operit/mnn/src/main/java/com/ai/assistance/mnn/MNNForwardType.kt)

```kotlin
enum class MNNForwardType(val type: Int) {
    FORWARD_CPU(0),      // CPU
    FORWARD_OPENCL(3),   // OpenCL
    FORWARD_AUTO(4),     // 自动选择
    FORWARD_OPENGL(6),   // OpenGL
    FORWARD_VULKAN(7)    // Vulkan
}
```

### 5.4 MNNImageProcess — 图像预处理

[MNNImageProcess.kt](file:///home/meizu/Documents/my_agent_projects/Operit/mnn/src/main/java/com/ai/assistance/mnn/MNNImageProcess.kt)

```kotlin
object MNNImageProcess {
    enum class Format(val type: Int) { RGBA(0), RGB(1), BGR(2), GRAY(3), BGRA(4) }
    enum class Filter(val type: Int) { NEAREST(0), BILINEAL(1), BICUBIC(2) }
    enum class Wrap(val type: Int) { CLAMP_TO_EDGE(0), ZERO(1), REPEAT(2) }

    data class Config(
        var mean: FloatArray = floatArrayOf(0f, 0f, 0f, 0f),
        var normal: FloatArray = floatArrayOf(1f, 1f, 1f, 1f),
        var source: Format = Format.RGBA,
        var dest: Format = Format.BGR,
        var filter: Filter = Filter.NEAREST,
        var wrap: Wrap = Wrap.CLAMP_TO_EDGE
    )

    fun convertBitmap(sourceBitmap: Bitmap, tensor: MNNNetInstance.Session.Tensor, config: Config, matrix: Matrix? = null): Boolean
    fun convertBuffer(buffer: ByteArray, width: Int, height: Int, tensor: MNNNetInstance.Session.Tensor, config: Config, matrix: Matrix? = null): Boolean
}
```

---

## 6. LLM 引擎集成

### 6.1 MNNLlmNative — LLM JNI 接口

[MNNLlmNative.kt](file:///home/meizu/Documents/my_agent_projects/Operit/mnn/src/main/java/com/ai/assistance/mnn/MNNLlmNative.kt)

```kotlin
object MNNLlmNative {

    external fun nativeCreateLlm(configPath: String): Long
    external fun nativeLoadLlm(llmPtr: Long): Boolean
    external fun nativeReleaseLlm(llmPtr: Long)

    external fun nativeTokenize(llmPtr: Long, text: String): IntArray?
    external fun nativeDetokenize(llmPtr: Long, token: Int): String?
    external fun nativeCountTokens(llmPtr: Long, text: String): Int

    external fun nativeGenerate(llmPtr: Long, prompt: String, maxTokens: Int, callback: GenerationCallback?): String?
    external fun nativeGenerateStream(llmPtr: Long, history: List<Pair<String, String>>, maxTokens: Int, callback: GenerationCallback): Boolean
    external fun nativeGenerateStreamStructured(llmPtr: Long, messagesJson: String, toolsJson: String?, maxTokens: Int, callback: GenerationCallback): Boolean

    external fun nativeApplyChatTemplate(llmPtr: Long, userContent: String): String?
    external fun nativeApplyChatTemplateWithHistory(llmPtr: Long, history: List<Pair<String, String>>): String?
    external fun nativeApplyChatTemplateWithStructuredMessages(llmPtr: Long, messagesJson: String, toolsJson: String?): String?

    external fun nativeDumpConfig(llmPtr: Long): String?
    external fun nativeGetContextInfo(llmPtr: Long): String?

    external fun nativeSetConfig(llmPtr: Long, configJson: String): Boolean
    external fun nativeReset(llmPtr: Long)
    external fun nativeCancel(llmPtr: Long)

    external fun nativeSetAudioDataCallback(llmPtr: Long, callback: AudioDataCallback?): Boolean
    external fun nativeGenerateWavform(llmPtr: Long): Boolean

    interface GenerationCallback {
        fun onToken(token: String): Boolean  // true=继续, false=停止
    }

    interface AudioDataCallback {
        fun onAudioData(audioData: FloatArray, isLastChunk: Boolean): Boolean
    }
}
```

### 6.2 MNNLlmSession — LLM 会话封装

[MNNLlmSession.kt](file:///home/meizu/Documents/my_agent_projects/Operit/mnn/src/main/java/com/ai/assistance/mnn/MNNLlmSession.kt)

```kotlin
class MNNLlmSession private constructor(private var llmPtr: Long, private val modelPath: String) {

    companion object {
        fun create(
            modelDir: String,
            backendType: String = "cpu",
            threadNum: Int = 4,
            precision: String = "low",
            memory: String = "low",
            tmpPath: String? = null
        ): MNNLlmSession?
    }

    fun tokenize(text: String): IntArray
    fun detokenize(token: Int): String
    fun countTokens(text: String): Int

    fun generate(prompt: String, maxTokens: Int = -1): String
    fun generateStream(history: List<Pair<String, String>>, maxTokens: Int = -1, onToken: (String) -> Boolean): Boolean
    fun generateStreamStructured(messagesJson: String, toolsJson: String?, maxTokens: Int = -1, onToken: (String) -> Boolean): Boolean

    fun chat(userContent: String, maxTokens: Int = -1, onToken: (String) -> Boolean): Boolean

    fun applyChatTemplate(userContent: String): String
    fun applyChatTemplate(history: List<Pair<String, String>>): String

    fun dumpConfig(): String
    fun getContextInfo(): MNNLlmContextInfo?

    fun setConfig(configJson: String): Boolean
    fun setMaxNewTokens(maxNewTokens: Int): Boolean
    fun setSystemPrompt(systemPrompt: String): Boolean
    fun setThinkingMode(enabled: Boolean): Boolean

    fun setAudioDataCallback(callback: MNNLlmNative.AudioDataCallback?): Boolean
    fun generateWavform(): Boolean

    fun reset()
    fun cancel()
    fun release()
}
```

**创建流程**：

```kotlin
// 1. 创建 LLM 实例（不加载模型）
val llmPtr = MNNLlmNative.nativeCreateLlm(configFile.absolutePath)

// 2. 设置配置（必须在 load 之前！）
MNNLlmNative.nativeSetConfig(llmPtr, """{"tmp_path":"$cachePath"}""")
MNNLlmNative.nativeSetConfig(llmPtr, """{"async":false}""")
MNNLlmNative.nativeSetConfig(llmPtr, """{"precision":"low"}""")
MNNLlmNative.nativeSetConfig(llmPtr, """{"memory":"low"}""")
MNNLlmNative.nativeSetConfig(llmPtr, """{"backend_type":"$backendType"}""")
MNNLlmNative.nativeSetConfig(llmPtr, """{"thread_num":$threadNum}""")

// 3. 加载模型
MNNLlmNative.nativeLoadLlm(llmPtr)
```

### 6.3 MNNLlmContextInfo — 推理上下文信息

[MNNLlmContextInfo.kt](file:///home/meizu/Documents/my_agent_projects/Operit/mnn/src/main/java/com/ai/assistance/mnn/MNNLlmContextInfo.kt)

```kotlin
data class MNNLlmContextInfo(
    val promptLen: Int,           // prompt token 数
    val generatedSeqLen: Int,     // 生成序列长度
    val totalSeqLen: Int,         // 总序列长度
    val loadUs: Long,             // 加载耗时 (μs)
    val visionUs: Long,           // 视觉处理耗时
    val audioUs: Long,            // 音频处理耗时
    val prefillUs: Long,          // 预填充耗时
    val decodeUs: Long,           // 解码耗时
    val sampleUs: Long,           // 采样耗时
    val pixelsMp: Double,         // 处理的像素数 (MP)
    val audioInputSeconds: Double,// 音频输入时长
    val currentToken: Int,        // 当前 token
    val statusCode: Int,          // 状态码
    val status: String,           // 状态描述
    val generatedText: String,    // 已生成文本
    val historyTokens: IntArray,  // 历史 token
    val outputTokens: IntArray    // 输出 token
)
```

---

## 7. 构建配置

### 7.1 mnn/build.gradle.kts

[build.gradle.kts](file:///home/meizu/Documents/my_agent_projects/Operit/mnn/build.gradle.kts)

```kotlin
android {
    namespace = "com.ai.assistance.mnn"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        ndk { abiFilters.addAll(listOf("arm64-v8a")) }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fno-emulated-tls")
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DANDROID_PLATFORM=android-26",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DMNN_BUILD_SHARED_LIBS=ON",
                    "-DMNN_SEP_BUILD=OFF",           // 所有后端编入 libMNN.so
                    "-DMNN_BUILD_TOOLS=OFF",
                    "-DMNN_BUILD_DEMO=OFF",
                    "-DMNN_BUILD_CONVERTER=OFF",
                    "-DMNN_USE_LOGCAT=ON",
                    "-DMNN_BUILD_TEST=OFF",
                    "-DMNN_BUILD_BENCHMARK=OFF",
                    "-DMNN_BUILD_QUANTOOLS=OFF",
                    "-DMNN_OPENCL=OFF",              // Android 端禁用 GPU 后端
                    "-DMNN_OPENGL=OFF",
                    "-DMNN_VULKAN=OFF",
                    "-DMNN_ARM82=ON",                // ARMv8.2 FP16 加速
                    // LLM 支持
                    "-DMNN_BUILD_LLM=ON",
                    "-DMNN_SUPPORT_TRANSFORMER_FUSE=ON",
                    "-DMNN_LOW_MEMORY=ON",
                    "-DMNN_CPU_WEIGHT_DEQUANT_GEMM=ON"
                )
            }
        }
    }
}
```

### 7.2 mnn/CMakeLists.txt

[CMakeLists.txt](file:///home/meizu/Documents/my_agent_projects/Operit/mnn/CMakeLists.txt)

```cmake
cmake_minimum_required(VERSION 3.22.1)
project("mnn_jni")

set(MNN_SOURCE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/MNN)

# MNN 构建选项
set(MNN_BUILD_FOR_ANDROID_COMMAND ON CACHE BOOL "Build from command" FORCE)
set(MNN_BUILD_LLM ON CACHE BOOL "Build LLM support" FORCE)
set(MNN_LOW_MEMORY ON CACHE BOOL "Use low memory mode" FORCE)
set(MNN_SUPPORT_TRANSFORMER_FUSE ON CACHE BOOL "Support transformer fuse" FORCE)
set(MNN_CPU_WEIGHT_DEQUANT_GEMM ON CACHE BOOL "CPU weight dequant gemm" FORCE)

# GPU 后端（项目级 CMake 中启用，但 build.gradle 中禁用）
set(MNN_VULKAN ON CACHE BOOL "Enable Vulkan backend" FORCE)
set(MNN_OPENCL ON CACHE BOOL "Enable OpenCL backend" FORCE)
set(MNN_OPENGL ON CACHE BOOL "Enable OpenGL backend" FORCE)
set(MNN_SUPPORT_RENDER ON CACHE BOOL "Enable render backend" FORCE)

# 禁用分离编译
set(MNN_SEP_BUILD OFF CACHE BOOL "Build backends separately" FORCE)

# 禁用不需要的功能
set(MNN_BUILD_BENCHMARK OFF CACHE BOOL "Build benchmark" FORCE)
set(MNN_BUILD_TEST OFF CACHE BOOL "Build test" FORCE)
set(MNN_BUILD_TOOLS OFF CACHE BOOL "Build tools" FORCE)
set(MNN_BUILD_QUANTOOLS OFF CACHE BOOL "Build quantools" FORCE)
set(MNN_EVALUATION OFF CACHE BOOL "Build evaluation" FORCE)
set(MNN_BUILD_CONVERTER OFF CACHE BOOL "Build converter" FORCE)
set(MNN_BUILD_TRAIN OFF CACHE BOOL "Build train" FORCE)

# 添加 MNN 主项目
add_subdirectory(${MNN_SOURCE_DIR} ${CMAKE_CURRENT_BINARY_DIR}/MNN)

# JNI 包装库
add_library(
    MNNWrapper
    SHARED
    src/main/cpp/mnnnetnative.cpp
    src/main/cpp/mnnmodulennative.cpp
    src/main/cpp/mnnllmnative.cpp
)

target_include_directories(MNNWrapper PRIVATE
    ${MNN_SOURCE_DIR}/include
    ${MNN_SOURCE_DIR}/source
    ${MNN_SOURCE_DIR}/express
    ${MNN_SOURCE_DIR}/transformers/llm/engine/include
    ${MNN_SOURCE_DIR}/transformers/llm/engine/src
    ${MNN_SOURCE_DIR}/3rd_party
)

# 编译选项
target_compile_options(MNNWrapper PRIVATE -fno-emulated-tls)
target_compile_options(MNN PRIVATE -fno-emulated-tls)
if(TARGET llm)
    target_compile_options(llm PRIVATE -fno-emulated-tls)
endif()

# 链接
target_link_libraries(MNNWrapper MNN android log jnigraphics)
if(TARGET llm)
    target_link_libraries(MNNWrapper llm)
endif()

# 16KB 页面大小支持（Android 15+）
target_link_options(MNNWrapper PRIVATE "-Wl,-z,max-page-size=16384")
target_link_options(MNN PRIVATE "-Wl,-z,max-page-size=16384")
if(TARGET llm)
    target_link_options(llm PRIVATE "-Wl,-z,max-page-size=16384")
endif()
```

### 7.3 关键构建选项

| 选项 | 值 | 说明 |
|------|-----|------|
| MNN_BUILD_LLM | ON | 启用 LLM 引擎 |
| MNN_LOW_MEMORY | ON | 低内存模式（权重量化） |
| MNN_SUPPORT_TRANSFORMER_FUSE | ON | Transformer 算子融合 |
| MNN_CPU_WEIGHT_DEQUANT_GEMM | ON | CPU 权重量化解量化 GEMM |
| MNN_SEP_BUILD | OFF | 所有后端编入单一 so |
| MNN_ARM82 | ON | ARMv8.2 FP16 指令加速 |
| MNN_OPENCL/VULKAN/OPENGL | OFF | Android 端禁用 GPU 后端 |
| -fno-emulated-tls | 编译选项 | 解决 TLS 兼容性问题 |
| max-page-size=16384 | 链接选项 | Android 15+ 16KB 页对齐 |

---

## 8. 项目中的使用方式

### 8.1 MNNProvider — AI 服务实现

[MNNProvider.kt](file:///home/meizu/Documents/my_agent_projects/Operit/app/src/main/java/com/ai/assistance/operit/api/chat/llmprovider/MNNProvider.kt)

```kotlin
class MNNProvider(
    private val context: Context,
    private val modelName: String,        // 模型文件夹名称
    private val forwardType: Int,         // 推理后端类型
    private val threadCount: Int,
    private val providerType: ApiProviderType = ApiProviderType.MNN,
    private val enableToolCall: Boolean = false,
    private val supportsVision: Boolean = false,
    private val supportsAudio: Boolean = false,
    private val supportsVideo: Boolean = false
) : AIService {

    private var llmSession: MNNLlmSession? = null

    // 初始化模型
    private suspend fun initModel(): Result<Unit> = withContext(Dispatchers.IO) {
        val modelDir = getModelDir(context, modelName)
        val backendType = when (forwardType) {
            0 -> "cpu"
            3 -> "opencl"
            4 -> "auto"
            6 -> "opengl"
            7 -> "vulkan"
            else -> "cpu"
        }

        // GPU 后端使用 normal 内存模式避免 Clone error
        val memoryMode = if (backendType in listOf("vulkan", "opencl", "opengl")) "normal" else "low"

        val cacheDir = File(context.cacheDir, "mnn_cache")

        llmSession = MNNLlmSession.create(
            modelDir = modelDir,
            backendType = backendType,
            threadNum = threadCount,
            precision = "low",
            memory = memoryMode,
            tmpPath = cacheDir.absolutePath
        )
    }

    // 流式生成
    override suspend fun sendMessageStream(
        messages: List<PromptTurn>,
        modelOption: ModelOption,
        onToken: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        initModel()

        val history = messages.map { it.role to it.content }

        val success = llmSession?.generateStream(
            history = history,
            maxTokens = modelOption.maxTokens ?: -1,
            onToken = { token ->
                onToken(token)
                !isCancelled  // 返回 false 停止生成
            }
        ) ?: false

        // 获取上下文统计
        val contextInfo = llmSession?.getContextInfo()
        _inputTokenCount = contextInfo?.promptLen ?: 0
        _outputTokenCount = contextInfo?.generatedSeqLen ?: 0
    }

    override fun cancelStreaming() {
        isCancelled = true
        llmSession?.cancel()
    }
}
```

### 8.2 后端类型与内存模式映射

```kotlin
val backendType = when (forwardType) {
    0 -> "cpu"      // CPU 后端，可用 low 内存模式
    3 -> "opencl"   // OpenCL，需 normal 内存模式
    4 -> "auto"     // 自动选择
    6 -> "opengl"   // OpenGL，需 normal 内存模式
    7 -> "vulkan"   // Vulkan，需 normal 内存模式
    else -> "cpu"
}

val memoryMode = if (backendType in listOf("vulkan", "opencl", "opengl")) {
    "normal"  // GPU 后端使用 normal 避免 Clone error
} else {
    "low"     // CPU 后端使用 low 节省内存
}
```

---

## 9. 使用方法

### 9.1 基础模型推理

```kotlin
import com.ai.assistance.mnn.*

// 1. 加载模型
val netInstance = MNNNetInstance.createFromFile("/path/to/model.mnn") ?: return

// 2. 创建 Session
val session = netInstance.createSession(
    MNNNetInstance.Config(
        forwardType = MNNForwardType.FORWARD_CPU.type,
        numThread = 4
    )
) ?: return

// 3. 获取输入输出张量
val inputTensor = session.getInput("input")
val outputTensor = session.getOutput("output")

// 4. 设置输入数据
inputTensor?.setInputFloatData(floatArrayOf(...))

// 5. 运行推理
session.run()

// 6. 获取输出
val outputData = outputTensor?.getFloatData()

// 7. 释放资源
session.release()
netInstance.release()
```

### 9.2 图像预处理与推理

```kotlin
import com.ai.assistance.mnn.MNNImageProcess
import android.graphics.Matrix

// 图像预处理配置
val config = MNNImageProcess.Config(
    mean = floatArrayOf(103.94f, 116.78f, 123.68f, 0f),
    normal = floatArrayOf(0.017f, 0.017f, 0.017f, 0f),
    source = MNNImageProcess.Format.RGBA,
    dest = MNNImageProcess.Format.BGR,
    filter = MNNImageProcess.Filter.BILINEAL
)

// 缩放矩阵
val matrix = Matrix()
matrix.postScale(224f / bitmap.width, 224f / bitmap.height)

// 转换 Bitmap 到 Tensor
MNNImageProcess.convertBitmap(bitmap, inputTensor, config, matrix)

// 运行推理
session.run()
```

### 9.3 动态形状模型推理

```kotlin
import com.ai.assistance.mnn.MNNModule

// 加载动态形状模型
val module = MNNModule.load(
    filePath = "/path/to/dynamic_model.mnn",
    config = MNNModule.Config(
        inputNames = listOf("input"),
        outputNames = listOf("output"),
        forwardType = MNNForwardType.FORWARD_CPU.type,
        numThread = 4,
        precision = MNNModule.PrecisionMode.LOW,
        memoryMode = MNNModule.MemoryMode.LOW
    )
) ?: return

// 创建输入变量
val inputVar = module.createInputVariable(
    shape = intArrayOf(1, 3, 224, 224),
    dataFormat = MNNModule.DataFormat.NCHW,
    dataType = MNNModule.DataType.FLOAT32
) ?: return

// 设置数据
inputVar.setFloatData(floatArrayOf(...))

// 推理
val outputs = module.forward(listOf(inputVar))
val result = outputs?.first()?.getFloatData()

// 释放
inputVar.release()
module.release()
```

### 9.4 LLM 本地推理

```kotlin
import com.ai.assistance.mnn.MNNLlmSession

// 创建 LLM 会话
val session = MNNLlmSession.create(
    modelDir = "/path/to/Qwen2-1.5B-Instruct-MNN",
    backendType = "cpu",
    threadNum = 4,
    precision = "low",
    memory = "low",
    tmpPath = context.cacheDir.absolutePath
) ?: return

// 非流式生成
val response = session.generate("你好，请介绍一下自己", maxTokens = 512)
println(response)

// 流式生成
val history = listOf(
    "user" to "你好",
    "assistant" to "你好！有什么可以帮助你的吗？",
    "user" to "请讲个笑话"
)

session.generateStream(
    history = history,
    maxTokens = 256,
    onToken = { token ->
        print(token)
        true  // 继续生成
    }
)

// Token 计数
val tokenCount = session.countTokens("这是一段测试文本")
println("Token count: $tokenCount")

// 获取推理统计
val contextInfo = session.getContextInfo()
println("Prefill: ${contextInfo?.prefillUs}μs, Decode: ${contextInfo?.decodeUs}μs")

// 释放
session.release()
```

### 9.5 聊天模板与结构化消息

```kotlin
// 应用聊天模板
val templated = session.applyChatTemplate("你好")

// 结构化消息（支持 tool_calls）
val messagesJson = """
[{"role":"user","content":"你好"},
 {"role":"assistant","content":"你好！"},
 {"role":"user","content":"天气怎么样？"}]
""".trimIndent()

val toolsJson = """
[{"type":"function","function":{"name":"get_weather","description":"获取天气"}}]
""".trimIndent()

session.generateStreamStructured(
    messagesJson = messagesJson,
    toolsJson = toolsJson,
    maxTokens = 512,
    onToken = { token ->
        print(token)
        true
    }
)
```

---

## 10. 文件索引

### Kotlin API 层

| 文件 | 路径 | 说明 |
|------|------|------|
| MNNLibraryLoader.kt | `mnn/src/main/java/com/ai/assistance/mnn/MNNLibraryLoader.kt` | 库加载器 |
| MNNNetNative.kt | `mnn/src/main/java/com/ai/assistance/mnn/MNNNetNative.kt` | 基础推理 JNI 接口 |
| MNNNetInstance.kt | `mnn/src/main/java/com/ai/assistance/mnn/MNNNetInstance.kt` | 基础推理封装 |
| MNNModuleNative.kt | `mnn/src/main/java/com/ai/assistance/mnn/MNNModuleNative.kt` | Module API JNI 接口 |
| MNNModule.kt | `mnn/src/main/java/com/ai/assistance/mnn/MNNModule.kt` | 动态形状模型封装 |
| MNNLlmNative.kt | `mnn/src/main/java/com/ai/assistance/mnn/MNNLlmNative.kt` | LLM JNI 接口 |
| MNNLlmSession.kt | `mnn/src/main/java/com/ai/assistance/mnn/MNNLlmSession.kt` | LLM 会话封装 |
| MNNLlmContextInfo.kt | `mnn/src/main/java/com/ai/assistance/mnn/MNNLlmContextInfo.kt` | 推理上下文信息 |
| MNNImageProcess.kt | `mnn/src/main/java/com/ai/assistance/mnn/MNNImageProcess.kt` | 图像预处理 |
| MNNForwardType.kt | `mnn/src/main/java/com/ai/assistance/mnn/MNNForwardType.kt` | 推理后端枚举 |

### C++ JNI 桥接层

| 文件 | 路径 | 说明 |
|------|------|------|
| mnnnetnative.cpp | `mnn/src/main/cpp/mnnnetnative.cpp` | Interpreter/Session/Tensor JNI |
| mnnmodulennative.cpp | `mnn/src/main/cpp/mnnmodulennative.cpp` | Module/VARP JNI |
| mnnllmnative.cpp | `mnn/src/main/cpp/mnnllmnative.cpp` | LLM 引擎 JNI |

### 构建配置

| 文件 | 路径 | 说明 |
|------|------|------|
| CMakeLists.txt | `mnn/CMakeLists.txt` | 项目级 CMake |
| build.gradle.kts | `mnn/build.gradle.kts` | Gradle 构建配置 |
| CMakeLists.txt | `mnn/src/main/cpp/MNN/CMakeLists.txt` | MNN 引擎 CMake |

### 业务封装层

| 文件 | 路径 | 说明 |
|------|------|------|
| MNNProvider.kt | `app/src/main/java/com/ai/assistance/operit/api/chat/llmprovider/MNNProvider.kt` | AIService 实现 |
