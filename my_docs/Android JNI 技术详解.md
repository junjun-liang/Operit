# Android JNI 技术详解

> 基于 Operit AI Agent 项目 JNI 实践深度分析
>
> 文档生成时间：2026-05-15

---

## 一、JNI 概述

### 1.1 什么是 JNI

**JNI（Java Native Interface）** 是 Java 平台提供的一套标准接口，允许 Java 代码与用 C/C++ 编写的本地代码进行交互。在 Android 开发中，JNI 是连接 Java/Kotlin 层与原生代码（Native Code）的桥梁。

### 1.2 为什么需要 JNI

| 场景 | 说明 |
|------|------|
| **性能敏感** | C/C++ 执行效率高于 Java，适合计算密集型任务（图像处理、AI 推理） |
| **复用现有库** | 大量优秀的 C/C++ 开源库（OpenCV、TensorFlow、llama.cpp） |
| **系统级操作** | 访问 Java 无法直接调用的底层 API（fork、ioctl、PTY） |
| **跨平台** | 同一套 C/C++ 代码可在 Android/iOS/Desktop 复用 |
| **硬件加速** | 直接调用 GPU（Vulkan/OpenCL/OpenGL ES）进行并行计算 |

### 1.3 JNI 调用流程

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Java/Kotlin 层                                  │
│  class MyNative {                                                           │
│      static { System.loadLibrary("mynative"); }                             │
│      private external fun nativeAdd(a: Int, b: Int): Int                    │
│  }                                                                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                              JNI 层（胶水代码）                               │
│  JNIEXPORT jint JNICALL                                                     │
│  Java_com_example_mynative_nativeAdd(JNIEnv* env, jobject thiz,             │
│                                      jint a, jint b) {                      │
│      return a + b;                                                          │
│  }                                                                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                              C/C++ 原生层                                    │
│  高性能计算 / 系统调用 / 第三方库调用                                         │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 二、JNI 基础语法

### 2.1 声明 Native 方法

```kotlin
// Kotlin 中声明 native 方法
class MyNative {
    companion object {
        init {
            // 加载本地库（加载 libmynative.so）
            System.loadLibrary("mynative")
        }
    }

    // 声明 native 方法
    external fun nativeAdd(a: Int, b: Int): Int
    external fun nativeString(input: String): String
    external fun nativeArray(arr: IntArray): IntArray
}
```

### 2.2 C++ 端实现

```cpp
#include <jni.h>

// 方法命名规则：Java_包名_类名_方法名
// 包名中的点替换为下划线
extern "C" JNIEXPORT jint JNICALL
Java_com_example_mynative_nativeAdd(JNIEnv* env, jobject thiz, jint a, jint b) {
    return a + b;
}

// 字符串处理
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_mynative_nativeString(JNIEnv* env, jobject thiz, jstring input) {
    // 将 jstring 转为 C 字符串
    const char* cstr = env->GetStringUTFChars(input, nullptr);
    std::string result = std::string("Hello, ") + cstr;
    
    // 释放 C 字符串（必须！）
    env->ReleaseStringUTFChars(input, cstr);
    
    // 将 C 字符串转为 jstring
    return env->NewStringUTF(result.c_str());
}

// 数组处理
extern "C" JNIEXPORT jintArray JNICALL
Java_com_example_mynative_nativeArray(JNIEnv* env, jobject thiz, jintArray arr) {
    // 获取数组长度
    jsize len = env->GetArrayLength(arr);
    
    // 获取数组元素（拷贝模式）
    jint* elements = env->GetIntArrayElements(arr, nullptr);
    
    // 创建新数组
    jintArray result = env->NewIntArray(len);
    jint* resultElements = new jint[len];
    
    for (int i = 0; i < len; i++) {
        resultElements[i] = elements[i] * 2;  // 每个元素乘以 2
    }
    
    // 释放原数组（必须！）
    env->ReleaseIntArrayElements(arr, elements, JNI_ABORT);
    
    // 填充新数组
    env->SetIntArrayRegion(result, 0, len, resultElements);
    delete[] resultElements;
    
    return result;
}
```

### 2.3 方法签名规则

| Java 类型 | JNI 类型 | 签名字符 |
|-----------|----------|----------|
| `boolean` | `jboolean` | `Z` |
| `byte` | `jbyte` | `B` |
| `char` | `jchar` | `C` |
| `short` | `jshort` | `S` |
| `int` | `jint` | `I` |
| `long` | `jlong` | `J` |
| `float` | `jfloat` | `F` |
| `double` | `jdouble` | `D` |
| `void` | `void` | `V` |
| `Object` | `jobject` | `Ljava/lang/Object;` |
| `String` | `jstring` | `Ljava/lang/String;` |
| `int[]` | `jintArray` | `[I` |
| `Object[]` | `jobjectArray` | `[Ljava/lang/Object;` |

**方法签名示例**：

```
Java 方法：int add(int a, int b)
JNI 签名：(II)I

Java 方法：String concat(String a, String b)
JNI 签名：(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;

Java 方法：void process(int[] arr, Object obj)
JNI 签名：([ILjava/lang/Object;)V
```

### 2.4 JNIEnv 指针

`JNIEnv*` 是 JNI 的核心接口指针，提供了所有 JNI 函数的访问入口：

```cpp
// 字符串操作
env->NewStringUTF("hello");
env->GetStringUTFChars(jstr, nullptr);
env->ReleaseStringUTFChars(jstr, cstr);

// 数组操作
env->NewIntArray(10);
env->GetIntArrayElements(arr, nullptr);
env->SetIntArrayRegion(arr, 0, len, data);

// 对象操作
env->FindClass("java/lang/String");
env->GetMethodID(cls, "toString", "()Ljava/lang/String;");
env->CallObjectMethod(obj, methodID);

// 字段操作
env->GetFieldID(cls, "name", "Ljava/lang/String;");
env->GetObjectField(obj, fieldID);
env->SetObjectField(obj, fieldID, value);
```

---

## 三、JNI 高级用法

### 3.1 访问 Java 对象字段和方法

```cpp
// 1. 获取类引用
jclass cls = env->FindClass("com/example/MyClass");

// 2. 获取字段 ID
jfieldID fieldId = env->GetFieldID(cls, "name", "Ljava/lang/String;");

// 3. 读取字段值
jstring name = (jstring)env->GetObjectField(obj, fieldId);

// 4. 获取方法 ID
jmethodID methodId = env->GetMethodID(cls, "getAge", "()I");

// 5. 调用方法
jint age = env->CallIntMethod(obj, methodId);

// 6. 调用静态方法
jmethodID staticMethodId = env->GetStaticMethodID(cls, "create", "()Lcom/example/MyClass;");
jobject newObj = env->CallStaticObjectMethod(cls, staticMethodId);
```

### 3.2 局部引用与全局引用

```cpp
// 局部引用（函数返回后自动释放）
jstring localRef = env->NewStringUTF("temp");

// 全局引用（跨函数使用，必须手动释放）
jstring globalRef = (jstring)env->NewGlobalRef(localRef);
// ... 在其他函数中使用 globalRef ...
env->DeleteGlobalRef(globalRef);  // 必须释放！

// 弱全局引用（可被 GC 回收）
jstring weakRef = (jstring)env->NewWeakGlobalRef(localRef);
env->DeleteWeakGlobalRef(weakRef);
```

### 3.3 异常处理

```cpp
// 检查是否有异常
if (env->ExceptionCheck()) {
    env->ExceptionDescribe();  // 打印异常堆栈
    env->ExceptionClear();     // 清除异常
}

// 抛出异常
jclass exCls = env->FindClass("java/lang/RuntimeException");
env->ThrowNew(exCls, "Something went wrong in native code");
```

### 3.4 线程管理

```cpp
// 获取 JavaVM（通常在 JNI_OnLoad 中保存）
JavaVM* g_vm = nullptr;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

// 在子线程中附加 JNIEnv
JNIEnv* env = nullptr;
g_vm->AttachCurrentThread(&env, nullptr);

// 使用 env 进行 JNI 调用...

// 分离线程
g_vm->DetachCurrentThread();
```

### 3.5 性能优化：直接缓冲区

```cpp
// Java 层创建直接缓冲区
ByteBuffer buffer = ByteBuffer.allocateDirect(1024);

// C++ 层直接访问内存（零拷贝）
extern "C" JNIEXPORT void JNICALL
Java_com_example_processBuffer(JNIEnv* env, jobject thiz, jobject buffer) {
    void* ptr = env->GetDirectBufferAddress(buffer);
    jlong capacity = env->GetDirectBufferCapacity(buffer);
    
    // 直接操作内存，无需拷贝
    memset(ptr, 0, capacity);
}
```

---

## 四、CMake 构建配置

### 4.1 基本 CMakeLists.txt

```cmake
cmake_minimum_required(VERSION 3.22.1)
project("mynative")

# 创建共享库
add_library(
    mynative
    SHARED
    native_add.cpp
    native_string.cpp
)

# 查找日志库
find_library(log-lib log)

# 链接库
target_link_libraries(mynative ${log-lib})

# Android 15+ 16KB 页面大小支持
target_link_options(mynative PRIVATE "-Wl,-z,max-page-size=16384")
```

### 4.2 Gradle 配置

```kotlin
// build.gradle.kts (Module: app)
android {
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    
    defaultConfig {
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }
}
```

### 4.3 预编译库集成

```cmake
# 添加预编译的 .so 库
add_library(prebuilt_lib SHARED IMPORTED)
set_target_properties(prebuilt_lib PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libprebuilt.so
)

target_link_libraries(mynative prebuilt_lib)
```

### 4.4 第三方源码集成

```cmake
# 添加第三方源码子目录
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/third_party/quickjs)

# 链接第三方库
target_link_libraries(mynative quickjs)
```

---

## 五、Operit 项目 JNI 实践

### 5.1 模块总览

Operit 项目使用了 **9 个 JNI 模块**，覆盖了 AI 推理、文本解析、终端、3D 渲染等多个领域：

| 模块 | 库名 | 用途 | 核心技术 |
|------|------|------|----------|
| **StreamNative** | `libstreamnative.so` | 高性能文本解析 | C++ 状态机、KMP 匹配 |
| **QuickJS** | `libquickjsjni.so` | JavaScript 运行时 | QuickJS 引擎、HostCall |
| **Terminal** | `libpty.so` | PTY 伪终端 | forkpty、termios |
| **MNN** | `libMNNWrapper.so` | 深度学习推理 | MNN 框架、Vulkan GPU |
| **Llama** | `libLlamaWrapper.so` | 本地 LLM 推理 | llama.cpp、GGUF |
| **MMD** | `libMmdWrapper.so` | 3D 模型查看 | Saba 引擎、Bullet3 |
| **FBX** | `libFbxWrapper.so` | FBX 模型预览 | ufbx 解析器 |
| **DragonBones** | `libdragonbones_native.so` | 2D 骨骼动画 | DragonBones C++ |
| **Sherpa** | `libsherpa-ncnn-jni.so` | 语音识别 | sherpa-ncnn |

### 5.2 StreamNative 模块（文本解析）

**Kotlin 层**：

```kotlin
object NativeMarkdownSplitter {
    init {
        System.loadLibrary("streamnative")
    }

    private external fun nativeCreateBlockSession(): Long
    private external fun nativeDestroySession(handle: Long)
    private external fun nativePush(handle: Long, chunk: String): IntArray
}
```

**C++ 层**：

```cpp
extern "C" JNIEXPORT jlong JNICALL
Java_com_ai_assistance_operit_util_streamnative_NativeMarkdownSplitter_nativeCreateBlockSession(
        JNIEnv* env, jobject /*thiz*/) {
    auto* s = streamnative::createMarkdownBlockSession();
    return reinterpret_cast<jlong>(s);  // 指针转 jlong 传递
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_ai_assistance_operit_util_streamnative_NativeMarkdownSplitter_nativePush(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jstring chunk) {
    // 指针还原
    auto* s = reinterpret_cast<streamnative::MarkdownSession*>(handle);
    
    // 获取字符串（UTF-16 编码）
    const jsize len = env->GetStringLength(chunk);
    const jchar* chars = env->GetStringChars(chunk, nullptr);
    
    // 调用 C++ 引擎
    std::vector<streamnative::Segment> segments = 
        streamnative::markdownSessionPush(s, chars, static_cast<int>(len));
    
    // 释放字符串（必须！）
    env->ReleaseStringChars(chunk, chars);
    
    // 结果转 jintArray
    return segmentsToJIntArray(env, segments);
}
```

**设计要点**：
- 使用 `jlong` 传递 C++ 对象指针（Session Handle 模式）
- `GetStringChars`/`ReleaseStringChars` 处理 UTF-16 字符串
- 结果通过 `IntArray` 返回（避免频繁创建 Java 对象）

### 5.3 QuickJS 模块（JS 运行时）

**Kotlin 层**：

```kotlin
internal object QuickJsNativeBridge {
    init {
        System.loadLibrary("quickjsjni")
    }

    @JvmStatic external fun nativeCreate(hostBridge: QuickJsNativeRuntime.HostBridge): Long
    @JvmStatic external fun nativeDestroy(handle: Long)
    @JvmStatic external fun nativeEvaluate(handle: Long, script: String, fileName: String): String
    @JvmStatic external fun nativeCallFunction(handle: Long, functionName: String, 
                                                argsJson: String, callSite: String): String
    @JvmStatic external fun nativeExecutePendingJobs(handle: Long, maxJobs: Int): Int
    @JvmStatic external fun nativeInterrupt(handle: Long)
}
```

**C++ 层**：

```cpp
extern "C" JNIEXPORT jlong JNICALL
Java_com_ai_assistance_operit_core_tools_javascript_QuickJsNativeBridge_nativeCreate(
    JNIEnv* env, jclass, jobject host_bridge) {
    
    // 保存 JavaVM 用于跨线程回调
    JavaVM* java_vm = nullptr;
    if (env->GetJavaVM(&java_vm) != JNI_OK) { return 0; }
    
    // 创建 VM 实例，保存 host_bridge 全局引用
    auto* vm = new QuickJsVm(java_vm, env, host_bridge);
    return reinterpret_cast<jlong>(vm);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_operit_core_tools_javascript_QuickJsNativeBridge_nativeEvaluate(
    JNIEnv* env, jclass, jlong handle, jstring script, jstring file_name) {
    
    auto* vm = FromHandle(handle);
    
    // jstring → const char*
    const char* script_chars = env->GetStringUTFChars(script, nullptr);
    const char* file_name_chars = env->GetStringUTFChars(file_name, nullptr);
    
    // 执行 JS 代码
    std::string result = vm->Eval(script_chars, file_name_chars);
    
    // 释放字符串
    env->ReleaseStringUTFChars(script, script_chars);
    env->ReleaseStringUTFChars(file_name, file_name_chars);
    
    // 结果转 jstring
    return env->NewStringUTF(result.c_str());
}
```

**设计要点**：
- 保存 `JavaVM` 用于子线程回调 Java
- `host_bridge` 使用全局引用保持 Java 对象存活
- 实现 JS → Java 的 HostCall 桥接（双向通信）

### 5.4 Terminal 模块（PTY 伪终端）

**Kotlin 层**：

```kotlin
companion object {
    init {
        try {
            System.loadLibrary("pty")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load libpty.so", e)
        }
    }

    private external fun createSubprocess(
        cmdArray: Array<String>, 
        envArray: Array<String>, 
        workingDir: String
    ): IntArray
    
    private external fun waitFor(pid: Int): Int
    private external fun getTerminalFlags(fd: Int): Int
    private external fun getAvailableBytes(fd: Int): Int
}

private external fun setPtyWindowSize(fd: Int, rows: Int, cols: Int): Int
```

**C 层**：

```c
JNIEXPORT jintArray JNICALL
Java_com_ai_assistance_operit_terminal_Pty_00024Companion_createSubprocess(
    JNIEnv *env, jobject thiz,
    jobjectArray cmdarray, jobjectArray envarray, jstring workingDir) {
    
    // forkpty 创建伪终端
    pid = forkpty(&master_fd, NULL, &tt, &ws);
    
    if (pid == 0) {
        // 子进程：执行 Shell
        chdir(cwd);
        execve(argv[0], argv, envp);
        _exit(1);
    } else {
        // 父进程：返回 pid 和 master_fd
        jint fill[2];
        fill[0] = pid;
        fill[1] = master_fd;
        (*env)->SetIntArrayRegion(env, result, 0, 2, fill);
        return result;
    }
}

JNIEXPORT jint JNICALL
Java_com_ai_assistance_operit_terminal_Pty_setPtyWindowSize(
    JNIEnv *env, jobject thiz, jint fd, jint rows, jint cols) {
    
    struct winsize ws;
    ws.ws_row = rows;
    ws.ws_col = cols;
    ioctl(fd, TIOCSWINSZ, &ws);
    return 0;
}
```

**设计要点**：
- 使用 `forkpty` 创建交互式 Shell（bash/busybox）
- `ioctl` 设置终端窗口大小
- 返回 `IntArray` 传递多个值（pid + fd）

### 5.5 MNN 模块（深度学习推理）

**Kotlin 层**：

```kotlin
@JvmStatic external fun nativeCreateLlm(configPath: String): Long
@JvmStatic external fun nativeLoadLlm(llmPtr: Long): Boolean
@JvmStatic external fun nativeReleaseLlm(llmPtr: Long)
@JvmStatic external fun nativeTokenize(llmPtr: Long, text: String): IntArray?
@JvmStatic external fun nativeDetokenize(llmPtr: Long, token: Int): String?
@JvmStatic external fun nativeCountTokens(llmPtr: Long, text: String): Int
@JvmStatic external fun nativeGenerate(
    llmPtr: Long, prompt: String, maxNewTokens: Int, callback: MNNLlmCallback
): Boolean
```

**C++ 层**：

```cpp
// 模型创建
extern "C" JNIEXPORT jlong JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeCreateLlm(
    JNIEnv* env, jclass, jstring configPath) {
    
    const char* path = env->GetStringUTFChars(configPath, nullptr);
    auto* llm = new MNNLlmEngine(path);
    env->ReleaseStringUTFChars(configPath, path);
    return reinterpret_cast<jlong>(llm);
}

// 流式生成 + Java 回调
extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeGenerate(
    JNIEnv* env, jclass, jlong llmPtr, jstring prompt, 
    jint maxNewTokens, jobject callback) {
    
    auto* llm = reinterpret_cast<MNNLlmEngine*>(llmPtr);
    
    // 获取回调方法 ID（只需获取一次，可缓存）
    jclass callbackCls = env->GetObjectClass(callback);
    jmethodID onTokenMethod = env->GetMethodID(callbackCls, "onToken", "(Ljava/lang/String;)Z");
    
    // 流式生成循环
    for (int i = 0; i < maxNewTokens; i++) {
        std::string token = llm->generateNextToken();
        
        // 调用 Java 回调
        jstring jtoken = env->NewStringUTF(token.c_str());
        jboolean keepGoing = env->CallBooleanMethod(callback, onTokenMethod, jtoken);
        env->DeleteLocalRef(jtoken);
        
        if (!keepGoing) break;  // Java 层要求停止
    }
    
    return JNI_TRUE;
}
```

**设计要点**：
- 使用 `jlong` 传递模型指针（Handle 模式）
- Java 回调接口实现流式输出
- `GetMethodID` 结果可缓存以提高性能

### 5.6 Llama 模块（本地 LLM）

**Kotlin 层**：

```kotlin
@JvmStatic external fun nativeCreateSession(
    pathModel: String, nThreads: Int, nCtx: Int, ...
): Long

@JvmStatic external fun nativeGenerateStream(
    sessionPtr: Long, prompt: String, maxTokens: Int, callback: LlamaTokenCallback
): Boolean

@JvmStatic external fun nativeSetToolCallGrammar(
    sessionPtr: Long, grammar: String, triggerPatterns: Array<String>?
): Boolean
```

**C++ 层**：

```cpp
extern "C" JNIEXPORT jlong JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeCreateSession(
    JNIEnv *env, jclass clazz, jstring pathModel, jint nThreads, ...) {
    
    ensureBackendInit();  // 初始化后端（GGML）
    
    auto *session = new (std::nothrow) LlamaSessionNative();
    const char* modelPath = env->GetStringUTFChars(pathModel, nullptr);
    
    // 加载模型
    session->model = llama_model_load_from_file(modelPath, mparams);
    session->ctx = llama_init_from_model(session->model, cparams);
    
    env->ReleaseStringUTFChars(pathModel, modelPath);
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeGenerateStream(
    JNIEnv *env, jclass clazz, jlong sessionPtr, jstring prompt, 
    jint maxTokens, jobject callback) {
    
    auto* session = reinterpret_cast<LlamaSessionNative*>(sessionPtr);
    
    // 获取回调方法
    jclass callbackCls = env->FindClass("com/ai/assistance/llama/LlamaTokenCallback");
    jmethodID midOnToken = env->GetMethodID(callbackCls, "onToken", "(Ljava/lang/String;)Z");
    
    // 生成循环
    for (int i = 0; i < maxTokens; i++) {
        // 采样生成 token
        const llama_token newToken = llama_sampler_sample(session->sampler, session->ctx, -1);
        
        // Detokenize
        std::string delta = tokenToPiece(session->ctx, newToken);
        
        // 回调 Java
        jstring jdelta = env->NewStringUTF(delta.c_str());
        jboolean keepGoing = env->CallBooleanMethod(callback, midOnToken, jdelta);
        env->DeleteLocalRef(jdelta);
        
        if (!keepGoing) break;
    }
    
    return JNI_TRUE;
}
```

**设计要点**：
- `llama.cpp` 的完整 JNI 封装
- 支持 Tool Call 语法约束（`nativeSetToolCallGrammar`）
- 聊天模板支持（`nativeApplyChatTemplate`）

---

## 六、JNI 最佳实践

### 6.1 内存管理

```cpp
// ❌ 错误：忘记释放字符串
const char* cstr = env->GetStringUTFChars(jstr, nullptr);
// ... 使用 cstr ...
// 忘记调用 ReleaseStringUTFChars！

// ✅ 正确：使用 RAII 或确保释放
const char* cstr = env->GetStringUTFChars(jstr, nullptr);
// ... 使用 cstr ...
env->ReleaseStringUTFChars(jstr, cstr);

// ✅ 更好：使用智能指针封装
class JStringGuard {
    JNIEnv* env;
    jstring jstr;
    const char* cstr;
public:
    JStringGuard(JNIEnv* e, jstring s) : env(e), jstr(s) {
        cstr = env->GetStringUTFChars(jstr, nullptr);
    }
    ~JStringGuard() { env->ReleaseStringUTFChars(jstr, cstr); }
    const char* get() const { return cstr; }
};
```

### 6.2 缓存方法 ID 和字段 ID

```cpp
// ❌ 低效：每次调用都查找
jmethodID mid = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)Z");

// ✅ 高效：静态缓存（线程安全）
jmethodID GetOnTokenMethod(JNIEnv* env) {
    static jmethodID mid = nullptr;
    if (mid == nullptr) {
        jclass cls = env->FindClass("com/example/Callback");
        mid = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)Z");
    }
    return mid;
}
```

### 6.3 避免在 JNI 中做大量工作

```cpp
// ❌ 错误：在 JNI 中执行耗时操作，阻塞 Java 线程
extern "C" JNIEXPORT void JNICALL
Java_com_example_heavyWork(JNIEnv* env, jobject thiz) {
    for (int i = 0; i < 1000000; i++) {
        // 耗时计算...
    }
}

// ✅ 正确：在 C++ 子线程中执行，通过回调通知 Java
extern "C" JNIEXPORT void JNICALL
Java_com_example_heavyWorkAsync(JNIEnv* env, jobject thiz, jobject callback) {
    jobject globalCallback = env->NewGlobalRef(callback);
    
    std::thread([globalCallback]() {
        JNIEnv* env = nullptr;
        g_vm->AttachCurrentThread(&env, nullptr);
        
        for (int i = 0; i < 1000000; i++) {
            // 耗时计算...
            // 定期回调进度
            if (i % 1000 == 0) {
                env->CallVoidMethod(globalCallback, progressMethod, i);
            }
        }
        
        g_vm->DetachCurrentThread();
        // 注意：需要在 Java 层或合适时机释放 globalCallback
    }).detach();
}
```

### 6.4 异常检查

```cpp
// 每次 JNI 调用后检查异常
jstring result = (jstring)env->CallObjectMethod(obj, methodId);
if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
    return nullptr;
}
```

### 6.5 16KB 页面大小支持（Android 15+）

```cmake
# CMakeLists.txt 中添加
target_link_options(mynative PRIVATE "-Wl,-z,max-page-size=16384")
```

---

## 七、常见问题排查

### 7.1 `UnsatisfiedLinkError`

```
java.lang.UnsatisfiedLinkError: dlopen failed: library "libmynative.so" not found
```

**原因和解决**：
- ABI 不匹配：确保 `.so` 文件放在正确的 `jniLibs/arm64-v8a` 目录
- 库名错误：`System.loadLibrary("mynative")` 加载的是 `libmynative.so`
- 依赖缺失：检查 `target_link_libraries` 是否完整

### 7.2 `NoSuchMethodError`

```
java.lang.NoSuchMethodError: no non-static method "Lcom/example/MyClass;.nativeAdd(II)I"
```

**原因和解决**：
- 方法签名错误：使用 `javap -s` 检查签名
- 包名/类名不匹配：C++ 方法名必须严格对应 Java 包名和类名
- `extern "C"` 缺失：C++ 需要防止名称修饰

### 7.3 内存泄漏

**症状**：应用运行一段时间后 OOM

**排查**：
- 检查 `NewGlobalRef` 是否对应 `DeleteGlobalRef`
- 检查 `GetStringUTFChars` 是否对应 `ReleaseStringUTFChars`
- 检查 `GetIntArrayElements` 是否对应 `ReleaseIntArrayElements`
- 使用 Android Studio Memory Profiler 分析

### 7.4 线程问题

```
JNI ERROR: thread attaching/detaching mismatch
```

**解决**：
- 子线程必须使用 `AttachCurrentThread`/`DetachCurrentThread`
- 使用 `JavaVM` 而非 `JNIEnv` 跨线程
- 考虑使用 `pthread_key_create` 自动分离线程

---

## 八、Operit 项目 JNI 文件索引

| 模块 | Kotlin 接口 | C++ 实现 | CMakeLists.txt |
|------|------------|----------|----------------|
| StreamNative | `util/streamnative/NativeMarkdownSplitter.kt` | `cpp/streamnative/native_markdown_splitter.cpp` | `app/src/main/cpp/CMakeLists.txt` |
| StreamNative | `util/streamnative/NativeXmlSplitter.kt` | `cpp/streamnative/native_xml_splitter.cpp` | 同上 |
| QuickJS | `core/tools/javascript/QuickJsNativeRuntime.kt` | `quickjs/src/main/cpp/quickjs_jni.cpp` | `quickjs/src/main/cpp/CMakeLists.txt` |
| Terminal | `terminal/Pty.kt` | `terminal/src/main/jni/pty.c` | `terminal/src/main/jni/CMakeLists.txt` |
| MNN | `mnn/MNNLlmNative.kt` | `mnn/src/main/cpp/mnnllmnative.cpp` | `mnn/CMakeLists.txt` |
| Llama | `llama/LlamaNative.kt` | `llama/src/main/cpp/llama_jni_stub.cpp` | `llama/CMakeLists.txt` |
| MMD | `mmd/MmdNative.kt` | `mmd/src/main/cpp/android/MmdRendererBridge.cpp` | `mmd/CMakeLists.txt` |
| FBX | `fbx/FbxNative.kt` | `fbx/src/main/cpp/fbx_jni.cpp` | `fbx/CMakeLists.txt` |
| DragonBones | `dragonbones/JniBridge.kt` | `dragonbones/cpp/JniBridge.cpp` | `dragonbones/CMakeLists.txt` |
| Sherpa | `com/k2fsa/sherpa/ncnn/SherpaNcnn.kt` | `app/src/main/cpp/thirdparty/sherpa-ncnn` | `app/src/main/cpp/CMakeLists.txt` |

---

## 九、总结

Operit 项目充分展示了 JNI 在 Android 开发中的强大能力：

1. **性能优化**：通过 C++ 实现高性能文本解析（StreamNative）和 AI 推理（MNN/Llama）
2. **系统能力**：通过 JNI 调用 `forkpty`、`ioctl` 等底层 API 实现终端功能
3. **生态复用**：集成 QuickJS、llama.cpp、Bullet3 等优秀 C/C++ 库
4. **硬件加速**：通过 Vulkan/OpenCL 实现 GPU 加速推理
5. **跨平台**：同一套 C/C++ 代码可在多平台复用

JNI 是一把双刃剑，在带来性能和功能优势的同时，也增加了代码复杂性和内存管理风险。在实际开发中，应遵循最佳实践，做好异常处理和内存管理，确保应用的稳定性和安全性。

---

*文档结束*
