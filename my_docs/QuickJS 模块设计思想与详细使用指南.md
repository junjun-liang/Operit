# QuickJS 模块设计思想与详细使用指南

## 目录

1. [模块概述](#1-模块概述)
2. [核心设计思想](#2-核心设计思想)
3. [架构总览](#3-架构总览)
4. [核心类详解](#4-核心类详解)
5. [JNI 层详解](#5-jni-层详解)
6. [兼容层详解](#6-兼容层详解)
7. [构建配置](#7-构建配置)
8. [关键流程](#8-关键流程)
9. [使用示例](#9-使用示例)
10. [最佳实践](#10-最佳实践)

---

## 1. 模块概述

`quickjs` 模块是 Operit 项目的 **JavaScript 运行时基础设施**，基于 Fabrice Bellard 的 QuickJS 引擎（版本 2025-09-13），通过 JNI 桥接为 Android 平台提供高性能的 JS 脚本执行能力。该模块是 `core.tools.javascript` 子系统中 `JsEngine` 的底层实现，支撑工具包（ToolPkg）的脚本执行、Compose DSL 渲染和 Java 桥接等核心功能。

模块位于项目根目录 `/quickjs`，作为独立的 Android Library 模块存在。

**目录结构**：

```
quickjs/
├── src/main/
│   ├── cpp/
│   │   ├── CMakeLists.txt           # 原生库构建脚本
│   │   └── quickjs_jni.cpp          # QuickJS JNI Runtime（C++ 层）
│   └── java/.../javascript/
│       ├── QuickJsNativeRuntime.kt   # Kotlin 运行时封装
│       ├── QuickJsNativeHostDispatcher.kt  # 默认 HostBridge（console + timer）
│       └── QuickJsNativeCompatScriptBuilder.kt  # JS 兼容层
├── thirdparty/quickjs/              # upstream QuickJS C 源码
├── build.gradle.kts                 # 模块构建配置
└── README.md
```

---

## 2. 核心设计思想

### 2.1 Native Handle 模式

QuickJS 运行时通过 **Native Handle**（`Long` 类型指针）在 Kotlin 和 C++ 之间传递：

```
Kotlin 层                    C++ 层
QuickJsNativeRuntime  ←──→  QuickJsVm*
(handle: Long)               (reinterpret_cast<jlong>)
```

- `nativeCreate()` 在 C++ 堆上创建 `QuickJsVm` 实例，返回指针作为 `Long`
- 所有后续操作通过 `handle` 定位 C++ 对象
- `nativeDestroy()` 释放 C++ 对象，Kotlin 侧通过 `Closeable` 和 `AtomicBoolean` 防止重复释放

**设计优势**：
- 零拷贝传递，性能最优
- C++ 对象生命周期完全由 Kotlin 侧控制
- 线程安全（C++ 内部使用 `std::mutex` 保护）

### 2.2 JSON 信封协议（Eval Envelope）

所有 JS 执行结果统一通过 **JSON 信封** 返回，无论成功或失败：

```json
{
    "success": true,
    "valueJson": "{\"result\": 42}",
    "errorMessage": null,
    "errorStack": null,
    "errorDetailsJson": null
}
```

```json
{
    "success": false,
    "valueJson": null,
    "errorMessage": "ReferenceError: foo is not defined",
    "errorStack": "    at <eval> (line 1)\n    ...",
    "errorDetailsJson": "{...详细诊断信息...}"
}
```

**设计优势**：
- 统一的成功/失败处理路径
- 丰富的错误诊断信息（含 Host Call 追踪）
- Kotlin 侧只需一次 `JSONObject` 解析

### 2.3 HostBridge 双向桥接

JS 脚本通过 `NativeInterface.__call(method, argsJson)` 调用 Java/Kotlin 代码，Java 侧通过 `HostBridge.onCall(method, argsJson)` 接收并处理：

```
JS 脚本                     C++ 层                    Kotlin 层
NativeInterface.foo(args) → HostCallEntry() → HostBridge.onCall("foo", "[args]")
                                                    ↓
                          ← CallHost()    ← 返回 String? 结果
```

**设计优势**：
- JS 侧无需知道 Java 类型系统，所有参数通过 JSON 传递
- Java 侧可以灵活处理任意方法调用
- 支持多层分发（`QuickJsNativeHostDispatcher` 先处理 console/timer，其余转发）

### 2.4 兼容层注入

QuickJS 原生环境缺少浏览器 API（`console`、`setTimeout`、`localStorage` 等），通过 `installCompatLayerOrThrow()` 注入兼容层：

- `globalThis` / `window` / `self` / `global` 全局对象统一
- `console.log/info/warn/error/debug` → 通过 `NativeInterface.__call("console.xxx", args)` 转发
- `setTimeout/setInterval/clearTimeout/clearInterval` → 通过 `NativeInterface.scheduleTimer/cancelTimer` 实现
- `localStorage/sessionStorage` → 内存实现
- `performance.now()` → 基于 `Date.now()` 的模拟
- `queueMicrotask()` → 基于 `Promise.resolve().then()` 的模拟
- `NativeInterface` Proxy 化 → `NativeInterface.foo(a, b)` 自动转为 `NativeInterface.__call("foo", [a, b])`

### 2.5 中断机制

通过 `JS_SetInterruptHandler` 注册中断处理器，支持从外部中断正在执行的 JS 脚本：

- Kotlin 侧调用 `interrupt()` → C++ 侧设置 `interrupted_` 标志
- QuickJS 引擎在每次循环迭代时检查中断标志
- 适用于超时控制和用户取消操作

### 2.6 错误诊断追踪

C++ 层在每次执行时记录详细的诊断信息：

- 当前执行的文件名和脚本长度
- 脚本内容预览（前 240 字符，换行符替换为空格）
- 最近的 Host Call 记录（最多 24 条，含调用深度）
- 异常的完整属性（name/message/stack/fileName/lineNumber/cause/dump）

这些信息打包到 `errorDetailsJson` 中，便于开发者定位问题。

---

## 3. 架构总览

```
┌─────────────────────────────────────────────────────────────┐
│                    Kotlin 层                                 │
│                                                              │
│  QuickJsNativeRuntime                                       │
│  ├── eval(script, fileName) → EvalResult                    │
│  ├── callFunction(name, argsJson, callSite) → EvalResult    │
│  ├── installCompatLayerOrThrow()                            │
│  ├── executePendingJobs(maxJobs)                            │
│  ├── dispatchTimer(timerId) → EvalResult                    │
│  ├── clearAllTimers() → EvalResult                          │
│  ├── interrupt()                                            │
│  └── close()                                                │
│       │                                                      │
│       │ HostBridge 接口                                      │
│       ▼                                                      │
│  QuickJsNativeHostDispatcher                                │
│  ├── console.* → 忽略（返回 null）                           │
│  ├── scheduleTimer → ScheduledExecutorService               │
│  ├── cancelTimer → 取消定时器                                │
│  └── 其他 → forwardCall(method, argsJson)                   │
│                                                              │
├─────────────────────────────────────────────────────────────┤
│                    JNI 桥接层                                 │
│                                                              │
│  QuickJsNativeBridge (internal)                             │
│  ├── nativeCreate(hostBridge) → Long                        │
│  ├── nativeDestroy(handle)                                  │
│  ├── nativeEvaluate(handle, script, fileName) → String      │
│  ├── nativeCallFunction(handle, name, argsJson, site) → Str │
│  ├── nativeExecutePendingJobs(handle, maxJobs) → Int        │
│  └── nativeInterrupt(handle)                                │
│                                                              │
├─────────────────────────────────────────────────────────────┤
│                    C++ 层                                     │
│                                                              │
│  QuickJsVm                                                  │
│  ├── Eval(script, fileName) → JSON envelope                 │
│  ├── CallFunction(name, argsJson, callSite) → JSON envelope │
│  ├── ExecutePendingJobs(maxJobs) → Int                      │
│  ├── Interrupt()                                            │
│  ├── HostCall(method, argsJson) → JSValue                   │
│  ├── InstallNativeInterface() → 注册 NativeInterface 对象    │
│  ├── SerializeValue(value) → JSON string                    │
│  └── TakeExceptionEnvelope() → JSON error envelope          │
│                                                              │
├─────────────────────────────────────────────────────────────┤
│                    QuickJS C 引擎                             │
│                                                              │
│  JSRuntime + JSContext                                       │
│  ├── JS_Eval()                                              │
│  ├── JS_Call()                                              │
│  ├── JS_ExecutePendingJob()                                 │
│  ├── JS_SetInterruptHandler()                               │
│  └── JS_JSONStringify()                                     │
└─────────────────────────────────────────────────────────────┘
```

---

## 4. 核心类详解

### 4.1 QuickJsNativeRuntime（Kotlin 运行时）

**定位**：QuickJS 引擎的 Kotlin 封装，提供类型安全的 API。

**创建与销毁**：

```kotlin
// 创建
val runtime = QuickJsNativeRuntime.create(hostBridge)

// 销毁（实现 Closeable，支持 use {} 语法）
runtime.close()
```

**核心方法**：

| 方法 | 参数 | 返回值 | 说明 |
|------|------|--------|------|
| `eval` | `script: String, fileName: String` | `EvalResult` | 执行 JS 脚本 |
| `callFunction` | `functionName: String, argsJson: String, callSite: String` | `EvalResult` | 调用全局函数 |
| `installCompatLayerOrThrow` | 无 | 无 | 注入浏览器 API 兼容层 |
| `executePendingJobs` | `maxJobs: Int = 128` | `Int` | 执行待处理的 Promise/async 回调 |
| `dispatchTimer` | `timerId: Int` | `EvalResult` | 触发定时器回调 |
| `clearAllTimers` | 无 | `EvalResult` | 清除所有定时器 |
| `interrupt` | 无 | 无 | 中断当前执行 |

**EvalResult 数据类**：

```kotlin
data class EvalResult(
    val success: Boolean,          // 是否成功
    val valueJson: String?,        // 成功时的 JSON 值
    val errorMessage: String?,     // 失败时的错误消息
    val errorStack: String?,       // 失败时的调用栈
    val errorDetailsJson: String?  // 失败时的详细诊断信息
)
```

**HostBridge 接口**：

```kotlin
interface HostBridge {
    fun onCall(method: String, argsJson: String?): String?
}
```

JS 脚本通过 `NativeInterface.__call(method, argsJson)` 调用时，会触发 `HostBridge.onCall`。

### 4.2 QuickJsNativeHostDispatcher（默认 HostBridge）

**定位**：默认的 `HostBridge` 实现，处理 console 和 timer，其余转发给业务层。

**方法分发**：

| 方法前缀 | 处理方式 | 说明 |
|---------|---------|------|
| `console.*` | 返回 `null`（忽略） | console 输出不转发 |
| `scheduleTimer` | 创建 `ScheduledFuture` | 通过 `ScheduledExecutorService` 调度 |
| `cancelTimer` | 取消 `ScheduledFuture` | 从 `timerTasks` 中移除 |
| 其他 | 调用 `forwardCall(method, argsJson)` | 转发给业务层处理 |

**Timer 实现**：

```kotlin
// scheduleTimer 的处理逻辑
val timerId = args[0].toInt()
val delayMs = args[1].toLong()
val repeat = args[2].toBoolean()

if (repeat) {
    scheduler.scheduleAtFixedRate({ dispatchTimer(timerId) }, delayMs, delayMs, MILLISECONDS)
} else {
    scheduler.schedule({ dispatchTimer(timerId) }, delayMs, MILLISECONDS)
}
```

定时器触发时调用 `dispatchTimer(timerId)`，最终通过 `QuickJsNativeRuntime.dispatchTimer()` 执行 JS 侧的 `__operitDispatchTimer(timerId)` 回调。

### 4.3 QuickJsNativeCompatScriptBuilder（兼容层构建器）

**定位**：生成浏览器 API 兼容层的 JS 脚本。

**注入的 API**：

| API | 实现方式 |
|-----|---------|
| `globalThis` / `window` / `self` / `global` | 统一指向 `globalThis` |
| `NativeInterface` | Proxy 化，`NativeInterface.foo(args)` → `NativeInterface.__call("foo", [args])` |
| `console.log/info/warn/error/debug` | 通过 `callHost('console.xxx', args)` 转发 |
| `setTimeout/setInterval` | 通过 `NativeInterface.scheduleTimer(id, delay, repeat)` 调度 |
| `clearTimeout/clearInterval` | 通过 `NativeInterface.cancelTimer(id)` 取消 |
| `localStorage/sessionStorage` | 内存实现（`createStorage()`） |
| `performance.now()` | 基于 `Date.now()` 的模拟 |
| `queueMicrotask` | 基于 `Promise.resolve().then()` |

**NativeInterface Proxy**：

```javascript
root.NativeInterface = new Proxy({}, {
    get: function(_, property) {
        if (property === '__call') return nativeBridge.__call;
        return function() {
            return callHost(String(property), Array.prototype.slice.call(arguments));
        };
    }
});
```

这使得 JS 脚本可以直接写 `NativeInterface.readFile("/path/to/file")` 而非 `NativeInterface.__call("readFile", ["/path/to/file"])`。

---

## 5. JNI 层详解

### 5.1 QuickJsVm 类（C++ 核心）

**构造函数**：

```cpp
QuickJsVm(JavaVM* java_vm, JNIEnv* env, jobject host_bridge)
```

1. 创建 `JSRuntime` 和 `JSContext`
2. 创建 `host_bridge` 的全局引用（`NewGlobalRef`）
3. 缓存 `HostBridge.onCall` 方法 ID
4. 设置 `JS_SetRuntimeOpaque` 和 `JS_SetInterruptHandler`
5. 调用 `InstallNativeInterface()` 注册 `NativeInterface` 对象

**InstallNativeInterface**：

```cpp
void InstallNativeInterface() {
    JSValue global = JS_GetGlobalObject(context_);
    JSValue native_interface = JS_NewObject(context_);
    JSValue host_call = JS_NewCFunction(context_, &QuickJsVm::HostCallEntry, "__call", 2);
    JS_SetPropertyStr(context_, native_interface, "__call", host_call);
    JS_SetPropertyStr(context_, global, "NativeInterface", native_interface);
    JS_FreeValue(context_, global);
}
```

在 JS 全局对象上注册 `NativeInterface.__call(method, argsJson)` 函数。

### 5.2 Eval 执行流程

```
nativeEvaluate(handle, script, fileName)
    │
    ├─ vm->Eval(script, fileName)
    │   ├─ lock_guard<mutex>  // 线程安全
    │   ├─ BeginExecutionTrace(fileName, script.size(), script)  // 记录诊断信息
    │   ├─ JS_Eval(context_, script, size, fileName, JS_EVAL_TYPE_GLOBAL)
    │   │
    │   ├─ 成功 → SerializeValue(result) → BuildEvalEnvelope(true, valueJson, ...)
    │   └─ 异常 → TakeExceptionEnvelope()
    │       ├─ JS_GetException(context_)
    │       ├─ 提取 message/stack/name/fileName/lineNumber/cause
    │       ├─ 构建 errorDetailsJson（含 Host Call 追踪）
    │       └─ BuildEvalEnvelope(false, null, message, stack, details)
    │
    └─ 返回 JSON 信封字符串
```

### 5.3 CallFunction 执行流程

```
nativeCallFunction(handle, functionName, argsJson, callSite)
    │
    ├─ vm->CallFunction(functionName, argsJson, callSite)
    │   ├─ lock_guard<mutex>
    │   ├─ JS_GetGlobalObject → JS_GetPropertyStr(functionName) → 检查是否为函数
    │   ├─ JS_ParseJSON(argsJson) → 检查是否为数组
    │   ├─ 遍历数组构建 argv
    │   ├─ JS_Call(context_, function, global, argc, argv)
    │   │
    │   ├─ 成功 → SerializeValue(result) → BuildEvalEnvelope(true, ...)
    │   └─ 异常 → TakeExceptionEnvelope()
    │
    └─ 返回 JSON 信封字符串
```

### 5.4 Host Call 流程

```
JS: NativeInterface.__call("method", "argsJson")
    │
    ├─ HostCallEntry(context, this_val, argc, argv)  [static]
    │   └─ 从 JS_GetRuntimeOpaque 获取 QuickJsVm*
    │
    ├─ vm->HostCall(context, argc, argv)
    │   ├─ 提取 method 和 argsJson 参数
    │   ├─ RecordHostCall(method, argsJson)  // 记录诊断信息
    │   ├─ active_host_call_depth_ += 1
    │   │
    │   ├─ vm->CallHost(method, argsJson)
    │   │   ├─ 获取/附加 JNIEnv
    │   │   ├─ 创建 JNI 字符串参数
    │   │   ├─ env->CallObjectMethod(host_bridge_, on_call_method_, j_method, j_args)
    │   │   │   └─ 调用 Kotlin HostBridge.onCall(method, argsJson)
    │   │   ├─ 提取返回值字符串
    │   │   └─ 清理 JNI 局部引用
    │   │
    │   ├─ active_host_call_depth_ -= 1
    │   ├─ 错误 → JS_ThrowInternalError
    │   └─ 成功 → JS_NewStringLen(result)
    │
    └─ 返回 JSValue
```

### 5.5 中断机制

```cpp
// 注册中断处理器
JS_SetInterruptHandler(runtime_, &QuickJsVm::HandleInterrupt, this);

// 中断检查回调
static int HandleInterrupt(JSRuntime* runtime, void* opaque) {
    auto* vm = static_cast<QuickJsVm*>(opaque);
    return vm->interrupted_.load() ? 1 : 0;  // 返回 1 中断执行
}

// Kotlin 侧触发中断
fun interrupt() {
    QuickJsNativeBridge.nativeInterrupt(handle)  // → vm->Interrupt()
}

void Interrupt() {
    interrupted_.store(true);  // 设置原子标志
}
```

### 5.6 错误诊断追踪

C++ 层在每次执行时维护以下诊断信息：

| 字段 | 说明 |
|------|------|
| `current_file_name_` | 当前执行的脚本文件名 |
| `current_script_length_` | 脚本长度 |
| `current_script_preview_` | 脚本预览（前 240 字符，换行替换为空格） |
| `recent_host_calls_` | 最近的 Host Call 记录（最多 24 条） |
| `host_call_counter_` | Host Call 计数器 |
| `active_host_call_depth_` | 当前 Host Call 嵌套深度 |

异常时打包到 `errorDetailsJson`：

```json
{
    "evalFileName": "main.js",
    "scriptLength": 1024,
    "scriptPreview": "function process(data) { ...",
    "exceptionName": "InternalError",
    "exceptionMessage": "Host call failed for readFile: Permission denied",
    "exceptionStack": "...",
    "exceptionFileName": "main.js",
    "exceptionLineNumber": 42,
    "exceptionCause": null,
    "exceptionDump": "InternalError: Host call failed...",
    "recentHostCalls": ["#1 depth=1 method=readFile args=[\"/path\"]", ...],
    "activeHostCallDepth": 1
}
```

---

## 6. 兼容层详解

### 6.1 Timer 实现

Timer 系统采用 **JS 侧状态 + Java 侧调度** 的双层架构：

```
JS 侧（状态管理）                    Java 侧（时间调度）
__operitTimerState                  QuickJsNativeHostDispatcher
├── nextId: 1                       ├── scheduler (ScheduledExecutorService)
└── entries: {                      └── timerTasks (ConcurrentHashMap)
      1: { callback, args, repeat }     ├── 1 → ScheduledFuture
    }                                   └── 2 → ScheduledFuture

setTimeout(cb, 100) 流程:
1. JS: scheduleTimer(cb, 100, false, args)
   → timerState.entries[id] = { callback, args, repeat: false }
   → NativeInterface.scheduleTimer(id, 100, false)
2. Java: onCall("scheduleTimer", [id, 100, false])
   → scheduler.schedule({ dispatchTimer(id) }, 100ms)
3. 100ms 后:
   Java: dispatchTimer(id) → runtime.dispatchTimer(id)
   → JS: __operitDispatchTimer(id)
   → 执行 entry.callback
   → delete entries[id]（非 repeat）
```

### 6.2 Console 实现

```javascript
root.console.log = function() {
    var args = Array.prototype.slice.call(arguments).map(function(value) {
        if (typeof value === 'string') return value;
        try { return JSON.stringify(value); } catch (_error) { return String(value); }
    });
    callHost('console.log', args);  // → NativeInterface.__call("console.log", "[...]")
};
```

`QuickJsNativeHostDispatcher` 对 `console.*` 方法返回 `null`（静默忽略），但业务层可以通过自定义 `forwardCall` 拦截并处理。

### 6.3 Storage 实现

```javascript
function createStorage() {
    var store = {};
    return {
        get length() { return Object.keys(store).length; },
        key(index) { ... },
        getItem(key) { return store[key] || null; },
        setItem(key, value) { store[key] = String(value); },
        removeItem(key) { delete store[key]; },
        clear() { store = {}; }
    };
}

root.localStorage = createStorage();    // 独立实例
root.sessionStorage = createStorage();  // 独立实例
```

纯内存实现，`runtime.close()` 后数据丢失。

---

## 7. 构建配置

### 7.1 build.gradle.kts

| 配置项 | 值 | 说明 |
|--------|-----|------|
| namespace | `com.ai.assistance.quickjs` | 模块命名空间 |
| compileSdk | 36 | 编译 SDK 版本 |
| minSdk | 26 | 最低 SDK 版本 |
| abiFilters | `arm64-v8a` | 仅编译 64 位 ARM |
| C++ 标准 | C++17 | JNI 层使用 C++17 |
| C 标准 | C11 | QuickJS 引擎使用 C11 |
| JVM Target | 17 | Kotlin 编译目标 |

### 7.2 CMakeLists.txt

| 配置项 | 值 | 说明 |
|--------|-----|------|
| 库名 | `quickjsjni` | 生成的 .so 文件名 |
| 编译源文件 | `quickjs_jni.cpp` + 5 个 QuickJS C 源文件 | 合并为单个 .so |
| 优化级别 | `-O3` | 即使 debug 构建也使用最高优化 |
| 编译定义 | `_GNU_SOURCE`, `CONFIG_VERSION`, `NDEBUG` | QuickJS 配置 |
| 链接库 | `android`, `log`, `m` | Android 日志 + 数学库 |
| Page Size | 16384 | 适配 Android 15 的 16KB 页面大小 |

**编译的 QuickJS 源文件**：

| 文件 | 说明 |
|------|------|
| `quickjs.c` | QuickJS 引擎核心 |
| `quickjs-libc.c` | 标准库（未直接编译，由 quickjs.c 包含） |
| `cutils.c` | 通用工具函数 |
| `dtoa.c` | 浮点数转字符串 |
| `libregexp.c` | 正则表达式引擎 |
| `libunicode.c` | Unicode 支持 |

---

## 8. 关键流程

### 8.1 运行时创建与初始化

```
QuickJsNativeRuntime.create(hostBridge)
    │
    ├─ QuickJsNativeBridge.nativeCreate(hostBridge)
    │   ├─ env->GetJavaVM(&java_vm)
    │   ├─ new QuickJsVm(java_vm, env, host_bridge)
    │   │   ├─ JS_NewRuntime()
    │   │   ├─ JS_NewContext(runtime)
    │   │   ├─ env->NewGlobalRef(host_bridge)
    │   │   ├─ 缓存 onCall 方法 ID
    │   │   ├─ JS_SetRuntimeOpaque(runtime, this)
    │   │   ├─ JS_SetInterruptHandler(runtime, HandleInterrupt, this)
    │   │   └─ InstallNativeInterface()  // 注册 NativeInterface 对象
    │   └─ return reinterpret_cast<jlong>(vm)
    │
    └─ return QuickJsNativeRuntime(handle, hostBridge)

// 初始化兼容层
runtime.installCompatLayerOrThrow()
    │
    ├─ eval(buildQuickJsCompatScript(), "<quickjs-compat>")
    │   └─ 注入 globalThis/window/console/setTimeout/localStorage/...
    │
    └─ executePendingJobs(128)
```

### 8.2 完整的脚本执行流程

```
// 1. 创建运行时
val hostDispatcher = QuickJsNativeHostDispatcher(
    dispatchTimer = { timerId -> runtime.dispatchTimer(timerId) },
    forwardCall = { method, argsJson -> /* 业务处理 */ }
)
val runtime = QuickJsNativeRuntime.create(hostDispatcher)
runtime.installCompatLayerOrThrow()

// 2. 执行脚本
val result = runtime.eval("""
    const data = NativeInterface.readFile("/path/to/file");
    const parsed = JSON.parse(data);
    setTimeout(() => { console.log("done"); }, 1000);
    parsed.result;
""", "main.js")

// 3. 处理结果
if (result.success) {
    println("Result: ${result.valueJson}")
} else {
    println("Error: ${result.errorMessage}")
    println("Stack: ${result.errorStack}")
}

// 4. 执行待处理的 Promise/Timer 回调
runtime.executePendingJobs()

// 5. 清理
runtime.clearAllTimers()
runtime.close()
hostDispatcher.close()
```

### 8.3 函数调用流程

```
// 1. 先定义函数
runtime.eval("function add(a, b) { return a + b; }", "math.js")

// 2. 调用函数
val result = runtime.callFunction(
    functionName = "add",
    argsJson = "[1, 2]",
    callSite = "main.js:10"
)

// 3. result.success == true, result.valueJson == "3"
```

---

## 9. 使用示例

### 9.1 基础脚本执行

```kotlin
QuickJsNativeRuntime.create(object : QuickJsNativeRuntime.HostBridge {
    override fun onCall(method: String, argsJson: String?): String? {
        return null  // 不处理任何 Host Call
    }
}).use { runtime ->
    runtime.installCompatLayerOrThrow()
    val result = runtime.eval("1 + 2", "test.js")
    println(result.valueJson)  // "3"
}
```

### 9.2 带 HostBridge 的脚本执行

```kotlin
val runtime = QuickJsNativeRuntime.create(object : QuickJsNativeRuntime.HostBridge {
    override fun onCall(method: String, argsJson: String?): String? {
        return when (method) {
            "readFile" -> {
                val path = JSONArray(argsJson).getString(0)
                File(path).readText()
            }
            "getTimestamp" -> System.currentTimeMillis().toString()
            else -> null
        }
    }
})

runtime.installCompatLayerOrThrow()

val result = runtime.eval("""
    const content = NativeInterface.readFile("/data/config.json");
    const config = JSON.parse(content);
    config.version;
""", "app.js")
```

### 9.3 使用 QuickJsNativeHostDispatcher

```kotlin
val runtime = QuickJsNativeRuntime.create(
    QuickJsNativeHostDispatcher(
        dispatchTimer = { timerId ->
            runtime.dispatchTimer(timerId)
            runtime.executePendingJobs()
        },
        forwardCall = { method, argsJson ->
            when (method) {
                "readFile" -> {
                    val path = JSONArray(argsJson).getString(0)
                    File(path).readText()
                }
                else -> null
            }
        }
    )
)

runtime.installCompatLayerOrThrow()

// 使用 setTimeout
runtime.eval("""
    setTimeout(() => {
        NativeInterface.log("1 second later");
    }, 1000);
""", "timer.js")

// 等待定时器触发...
// QuickJsNativeHostDispatcher 内部调度，到时调用 dispatchTimer
```

### 9.4 调用全局函数

```kotlin
runtime.eval("""
    function registerToolPkg(config) {
        return JSON.stringify({
            tools: config.tools,
            status: "registered"
        });
    }
""", "register.js")

val result = runtime.callFunction(
    functionName = "registerToolPkg",
    argsJson = """[{"tools": ["tool1", "tool2"]}]""",
    callSite = "main-registration"
)

if (result.success) {
    println(result.valueJson)  // {"tools":["tool1","tool2"],"status":"registered"}
}
```

### 9.5 中断长时间运行的脚本

```kotlin
val runtime = QuickJsNativeRuntime.create(hostBridge)
runtime.installCompatLayerOrThrow()

// 在另一个线程中断
Thread {
    Thread.sleep(5000)
    runtime.interrupt()
}.start()

// 这个脚本会运行很久
val result = runtime.eval("while(true) {}", "infinite.js")
// result.success == false
// result.errorMessage 包含 "interrupted" 相关信息
```

### 9.6 错误诊断

```kotlin
val result = runtime.eval("""
    function process(data) {
        const value = NativeInterface.fetchData("key");
        return JSON.parse(value).result;
    }
    process(null);
""", "app.js")

if (!result.success) {
    println("Error: ${result.errorMessage}")
    println("Stack: ${result.errorStack}")

    // 详细诊断信息
    val details = JSONObject(result.errorDetailsJson!!)
    println("File: ${details.getString("evalFileName")}")
    println("Script preview: ${details.getString("scriptPreview")}")
    println("Recent host calls: ${details.getJSONArray("recentHostCalls")}")
    println("Host call depth: ${details.getInt("activeHostCallDepth")}")
}
```

---

## 10. 最佳实践

### 10.1 资源管理

- **始终使用 `use {}`**：`QuickJsNativeRuntime` 实现了 `Closeable`，使用 `use` 确保 `close()` 被调用
- **同时关闭 HostDispatcher**：`QuickJsNativeHostDispatcher` 也实现了 `Closeable`，需要关闭 `ScheduledExecutorService`
- **清除定时器**：关闭前调用 `clearAllTimers()` 避免定时器回调到已关闭的运行时

```kotlin
val hostDispatcher = QuickJsNativeHostDispatcher(...)
val runtime = QuickJsNativeRuntime.create(hostDispatcher)
try {
    runtime.installCompatLayerOrThrow()
    // ... 使用 runtime
} finally {
    runtime.clearAllTimers()
    runtime.close()
    hostDispatcher.close()
}
```

### 10.2 线程安全

- **C++ 层互斥**：`QuickJsVm` 内部使用 `std::mutex`，同一时刻只有一个线程可以执行 JS
- **Kotlin 侧同步**：建议在固定线程（如 `Dispatchers.Default`）上操作 runtime
- **中断线程安全**：`interrupted_` 使用 `std::atomic_bool`，可以从任意线程调用

### 10.3 兼容层初始化

- **创建后立即安装**：`installCompatLayerOrThrow()` 应在 `create()` 后立即调用
- **检查安装结果**：使用 `OrThrow` 变体，安装失败时抛出异常
- **执行 Pending Jobs**：安装后调用 `executePendingJobs()` 确保兼容层的 Promise 回调完成

### 10.4 Host Call 设计

- **方法名命名**：使用 `category.action` 格式（如 `file.read`、`http.request`）
- **参数传递**：所有参数通过 JSON 数组传递，在 Kotlin 侧用 `JSONArray` 解析
- **返回值**：返回 `String?`，`null` 表示无返回值（JS 侧收到 `null`）
- **错误处理**：抛出异常时 C++ 层会捕获并转为 `JS_ThrowInternalError`

### 10.5 性能优化

- **O3 优化**：CMakeLists.txt 中即使 debug 构建也使用 `-O3`，确保 QuickJS 引擎性能
- **单 ABI**：仅编译 `arm64-v8a`，减少构建时间和包体积
- **避免频繁创建/销毁**：`QuickJsNativeRuntime` 创建开销较大，建议复用实例
- **批量执行**：将多个小脚本合并为一个大脚本执行，减少 JNI 调用次数

### 10.6 错误处理

- **检查 EvalResult.success**：始终检查执行结果是否成功
- **利用诊断信息**：`errorDetailsJson` 包含 Host Call 追踪，对调试非常有价值
- **中断保护**：长时间运行的脚本应设置超时中断，避免阻塞

---

*文档生成时间：2026-05-16*
*基于 Operit 项目 quickjs 模块源代码分析*
