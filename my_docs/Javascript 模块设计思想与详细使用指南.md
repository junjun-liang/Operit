# Javascript 模块设计思想与详细使用指南

## 一、模块概述

`javascript` 模块是 Operit AI 的核心脚本执行引擎，基于 **QuickJS** 嵌入式 JavaScript 引擎构建，为 Android 平台提供高性能、沙箱化的 JavaScript 运行环境。该模块实现了完整的 JavaScript 执行生命周期管理、Java-JS 双向桥接、CommonJS 模块系统、工具调用接口以及 Compose DSL 渲染能力。

### 1.1 核心定位

- **脚本执行引擎**：执行 toolpkg 中的 JavaScript 工具脚本
- **桥接中枢**：实现 Java/Kotlin 与 JavaScript 的双向互操作
- **模块加载器**：支持 CommonJS 风格的 `require()` 模块系统
- **UI 渲染桥**：支持通过 JavaScript 定义 Compose DSL 界面
- **工具调用网关**：JavaScript 中调用原生工具能力的统一入口

### 1.2 模块结构

```
javascript/
├── JsEngine.kt                    # 核心引擎：QuickJS 封装与执行管理
├── JsToolManager.kt               # 工具管理：脚本执行调度与参数转换
├── JsJavaBridge.kt                # Java 桥接：Java/Kotlin ↔ JS 双向调用
├── JsExecutionScriptBuilder.kt    # 执行运行时：模块加载与脚本执行沙箱
├── JsInitRuntimeScriptBuilder.kt  # 初始化运行时：核心库与错误处理
├── JsLibraries.kt                 # 库加载：第三方库与工具库初始化
├── JsComposeDslBridge.kt          # Compose DSL：UI 渲染桥接
├── JsAssetLoader.kt               # 资源加载：assets 中的 JS 文件读取
├── JsEmbeddedLibraryLoader.kt     # 嵌入式库：CryptoJS、Jimp、pako 等
├── JsTimeoutConfig.kt             # 超时配置：执行超时与预超时策略
├── JsToolPkgExecutionContext.kt   # 执行上下文：日志与错误追踪
├── ScriptExecutionReceiver.kt     # 广播接收器：外部脚本执行入口
└── ...                            # 其他辅助类
```

---

## 二、核心设计思想

### 2.1 QuickJS 引擎集成

#### 设计目标
- **轻量高效**：QuickJS 是 Fabrice Bellard 开发的高性能 JavaScript 引擎，启动速度快、内存占用低
- **ES2020 支持**：完整支持现代 JavaScript 语法，包括 async/await、Promise、Proxy 等
- **Android 原生集成**：通过 JNI 绑定到 Android 平台，提供原生性能

#### 线程模型
```kotlin
class JsEngine(private val context: Context) {
    // 单线程执行器，确保 QuickJS 操作在同一线程执行
    private val quickJsExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "OperitQuickJsEngine").apply {
            isDaemon = true
            quickJsThread = this
        }
    }
    
    // 转换为 Coroutine Dispatcher，支持 Kotlin 协程
    private val quickJsDispatcher = quickJsExecutor.asCoroutineDispatcher()
    private val engineScope = CoroutineScope(SupervisorJob() + quickJsDispatcher)
}
```

**设计要点**：
- **单线程约束**：QuickJS 实例必须在同一线程创建和使用，通过 `quickJsThread` 跟踪
- **协程集成**：将 Executor 转换为 CoroutineDispatcher，支持 suspend 函数
- **生命周期绑定**：引擎销毁时关闭线程池，释放资源

### 2.2 Java-JS 双向桥接

#### 架构设计

JavaScript 模块实现了完整的双向桥接机制：

```
┌─────────────────┐     JNI      ┌──────────────────┐
│   Java/Kotlin   │ ◄──────────► │     QuickJS      │
│   JsEngine      │              │   JS Runtime     │
└────────┬────────┘              └────────┬─────────┘
         │                                │
    @JavascriptInterface              NativeInterface
         │                                │
    JsToolCallInterface            globalThis.Java
         │                                │
    JsJavaBridgeDelegates          JsJavaBridge (JS)
```

#### NativeInterface（JS → Java）

JavaScript 通过 `NativeInterface` 全局对象调用 Java 方法：

```javascript
// JS 端调用示例
var result = NativeInterface.callTool('default', 'http_request', JSON.stringify({
    url: 'https://api.example.com/data'
}));
```

对应的 Java 接口：

```kotlin
@Keep
inner class JsToolCallInterface {
    @JavascriptInterface
    fun callTool(toolType: String, toolName: String, paramsJson: String): String {
        // 同步工具调用
    }
    
    @JavascriptInterface
    fun callToolAsync(callbackId: String, toolType: String, toolName: String, paramsJson: String) {
        // 异步工具调用，通过回调返回结果
    }
}
```

#### Java 全局对象（Java → JS）

JavaScript 通过 `Java` / `Kotlin` 全局对象访问 Java 类：

```javascript
// 获取 Java 类
var ArrayList = Java.type('java.util.ArrayList');
var list = ArrayList.newInstance();
list.add('Hello');
list.add('World');

// 访问静态方法
var System = Java.type('java.lang.System');
System.callStatic('currentTimeMillis');

// 获取 Android Context
var context = Java.getApplicationContext();
```

**核心实现**（`JsJavaBridge.kt`）：

```javascript
var JavaApi = {
    type: function(className) { return createClassProxy(normalized); },
    package: function(packageName) { return createPackageProxy(normalized.split('.')); },
    implement: function(interfaceNameOrNames, impl) { /* 接口实现代理 */ },
    newInstance: function(className) { /* Java 对象实例化 */ },
    callStatic: function(className, methodName) { /* 静态方法调用 */ },
    getApplicationContext: function() { /* 获取应用上下文 */ },
    getCurrentActivity: function() { /* 获取当前 Activity */ }
};
```

#### 对象代理机制

Java 对象通过 Proxy 机制代理到 JavaScript：

```javascript
// Java 对象代理
var proxy = createInstanceProxy('java.util.ArrayList', handle);

// 代理特性：
// 1. 方法调用自动桥接到 Java
// 2. 字段访问自动映射到 getter/setter
// 3. 支持 FinalizationRegistry 自动释放
// 4. 支持 Kotlin Companion 对象自动回退
```

### 2.3 CommonJS 模块系统

#### 设计目标
- 支持 `require()` 函数加载模块
- 支持模块缓存，避免重复加载
- 支持相对路径（`./module`）和绝对路径（`/module`）
- 支持 `.js` 和 `.json` 文件

#### 模块加载流程

```
require("./utils")
    │
    ▼
resolveModulePath("./utils", "/current/path")
    │
    ▼
buildCandidatePaths("utils") → ["utils", "utils.js", "utils.json", "utils/index.js", "utils/index.json"]
    │
    ▼
readToolPkgModule(candidate) → 从 toolpkg 读取模块源码
    │
    ▼
executeModule(path, source, requireInternal)
    │
    ▼
factory(module, module.exports, localRequire, callRuntime)
```

#### 核心实现

```javascript
function requireInternal(moduleName, fromPath) {
    var request = text(moduleName).trim();
    
    // 内置模块映射
    if (request === 'lodash') return root._;
    if (request === 'uuid') return { v4: function() { ... } };
    if (request === 'axios') return { get: function(url, config) { ... } };
    
    // 路径解析
    var resolvedPath = resolveModulePath(request, fromPath || screenPath);
    
    // 全局模块代理（UI 运行时）
    if (isUiModuleRuntime() && !isLocalUiModulePath(resolvedPath)) {
        return buildGlobalModuleValue(resolvedPath, [], globalDescriptor);
    }
    
    // 本地模块执行
    var loaded = readToolPkgModule(resolvedPath);
    return executeModule(loaded.path, loaded.text, requireInternal);
}
```

### 2.4 沙箱执行环境

#### 执行入口函数

所有脚本通过统一的入口函数执行：

```javascript
root.__operitExecuteScriptFunction = function(
    callId,           // 调用 ID，用于追踪
    params,           // 传入参数
    scriptText,       // 脚本源码
    targetFunctionName, // 目标函数名
    timeoutSec,       // 超时时间（秒）
    preTimeoutMs      // 预超时时间（毫秒）
) {
    // 1. 注册调用会话
    var callState = registerCallSession(callId, params);
    
    // 2. 创建执行运行时
    var callRuntime = createRuntime();
    
    // 3. 编译并执行主模块
    var mainFactory = getFactory('main', packageTarget + ':' + screenPath, scriptText);
    mainFactory(module, module.exports, require, callRuntime);
    
    // 4. 调用目标函数
    var functionResult = targetFunction(params);
    
    // 5. 处理异步结果
    if (!handleAsync(functionResult)) {
        complete(functionResult);
    }
};
```

#### 运行时上下文

每个脚本执行时拥有独立的运行时上下文：

```javascript
function createRuntime() {
    return {
        // 结果输出
        emit: emitIntermediate,           // 发送中间结果
        delta: emitIntermediate,          // 增量更新
        done: complete,                   // 完成并返回结果
        complete: complete,               // 完成（别名）
        
        // 环境访问
        getEnv: function(key) { ... },    // 获取环境变量
        getState: function() { ... },     // 获取包状态
        getLang: function() { ... },      // 获取当前语言
        getCallerName: function() { ... },// 获取调用者名称
        getChatId: function() { ... },    // 获取聊天 ID
        
        // 错误报告
        reportDetailedError: callRuntimeReport,
        
        // 异步处理
        handleAsync: handleAsync,
        
        // 控制台
        console: {
            log: function() { ... },
            info: function() { ... },
            warn: function() { ... },
            error: function() { ... }
        }
    };
}
```

### 2.5 工具调用机制

#### 同步调用

```javascript
// 同步调用（阻塞直到返回）
var result = NativeInterface.callTool('default', 'http_request', JSON.stringify({
    url: 'https://api.example.com'
}));
```

#### 异步调用（Promise）

```javascript
// 异步调用（返回 Promise）
toolCall('http_request', { url: 'https://api.example.com' })
    .then(function(result) {
        console.log('Success:', result);
    })
    .catch(function(error) {
        console.error('Error:', error);
    });

// 或使用 async/await
var result = await toolCall('http_request', { url: 'https://api.example.com' });
```

#### 带中间结果的流式调用

```javascript
toolCall('llm_chat', { message: 'Hello' }, {
    onIntermediateResult: function(delta) {
        console.log('Delta:', delta);
    }
});
```

### 2.6 Compose DSL 桥接

#### 设计目标
- 允许使用 JavaScript 定义 Android Compose UI
- 支持状态管理（useState、useMutable、useRef、useMemo）
- 支持事件处理和导航
- 支持 WebView 控制器

#### 核心 API

```javascript
// 创建 Compose DSL 运行时上下文
var ctx = OperitComposeDslRuntime.createContext({
    state: { count: 0 },
    memo: {},
    packageName: 'my.package'
});

// 状态管理
var [count, setCount] = ctx.useState('count', 0);

// 创建 UI 节点
var node = ctx.UI.Column({
    modifier: ctx.Modifier.padding(16).dp
}, [
    ctx.UI.Text({ text: 'Count: ' + count }),
    ctx.UI.Button({
        onClick: function() { setCount(count + 1); }
    }, 'Increment')
]);

// 导航
ctx.navigate('chat_screen', { chatId: '123' });

// 工具调用
ctx.callTool('toast', { message: 'Hello!' });
```

---

## 三、详细使用方法

### 3.1 基础脚本执行

#### 执行函数脚本

```kotlin
val engine = JsEngine(context)

val result = engine.executeScriptFunction(
    script = """
        function greet(params) {
            var name = params.name || 'World';
            return 'Hello, ' + name + '!';
        }
    """.trimIndent(),
    functionName = "greet",
    params = mapOf("name" to "Operit"),
    timeoutSec = 30L
)

// result: "Hello, Operit!"
```

#### 执行内联代码

```kotlin
val result = engine.executeScriptCode(
    script = """
        var sum = params.a + params.b;
        complete(sum);
    """.trimIndent(),
    params = mapOf("a" to 10, "b" to 20)
)

// result: "30"
```

### 3.2 工具脚本执行（JsToolManager）

#### 执行 toolpkg 工具

```kotlin
val toolManager = JsToolManager.getInstance(context, packageManager)

// 执行脚本并获取 Flow 结果
toolManager.executeScript(script = scriptContent, tool = aiTool)
    .collect { toolResult ->
        when (toolResult.result) {
            is StringResultData -> {
                println("Result: ${toolResult.result.value}")
            }
            is ScriptExecutionTraceData -> {
                println("Log [${toolResult.result.level}]: ${toolResult.result.message}")
            }
        }
    }
```

#### 参数类型转换

JsToolManager 自动将字符串参数转换为声明的类型：

```javascript
// tool 定义中的参数类型
{
    "name": "count",
    "type": "integer",
    "required": true
}
```

```kotlin
// 传入字符串参数，自动转换
val tool = AITool(
    name = "myPackage:greet",
    parameters = listOf(
        ToolParameter(name = "count", value = "42"),
        ToolParameter(name = "enabled", value = "true"),
        ToolParameter(name = "data", value = "{\"key\": \"value\"}")
    )
)
```

### 3.3 Java-JS 互操作

#### 从 JavaScript 调用 Java

```javascript
// 1. 获取 Java 类
var ArrayList = Java.type('java.util.ArrayList');

// 2. 创建实例
var list = ArrayList.newInstance();

// 3. 调用方法
list.add('Hello');
list.add('World');
var size = list.size();

// 4. 访问字段
var System = Java.type('java.lang.System');
var out = System.getStatic('out');
out.println('Hello from JS!');

// 5. 获取 Context
var context = Java.getApplicationContext();

// 6. 调用静态方法
var result = Java.callStatic('java.lang.Math', 'max', 10, 20);
```

#### 从 JavaScript 实现 Java 接口

```javascript
// 实现 Runnable 接口
var runnable = Java.implement('java.lang.Runnable', {
    run: function() {
        console.log('Running!');
    }
});

// 传递给 Java 方法
var Thread = Java.type('java.lang.Thread');
var thread = Thread.newInstance(runnable);
thread.start();
```

#### 异步 Java 调用

```javascript
// 调用 suspend 函数
var result = await Java.callSuspend('com.example.MyClass', 'fetchData', { id: 123 });
```

### 3.4 模块加载与使用

#### 创建模块

```javascript
// utils.js
function formatDate(date) {
    return date.toISOString().split('T')[0];
}

function sum(a, b) {
    return a + b;
}

module.exports = {
    formatDate: formatDate,
    sum: sum
};
```

#### 使用模块

```javascript
// main.js
var utils = require('./utils');

function main(params) {
    var today = new Date();
    var formatted = utils.formatDate(today);
    var total = utils.sum(10, 20);
    
    return {
        date: formatted,
        sum: total
    };
}
```

#### 加载 JSON 模块

```javascript
var config = require('./config.json');
console.log('API URL:', config.apiUrl);
```

### 3.5 内置库使用

#### Lodash 风格工具库（`_`）

```javascript
var _ = globalThis._;

_.isEmpty([]);        // true
_.isString('hello');  // true
_.isNumber(42);       // true
_.forEach([1, 2, 3], function(item, index) {
    console.log(index, item);
});
_.map([1, 2, 3], function(item) {
    return item * 2;
}); // [2, 4, 6]
```

#### 数据工具（`dataUtils`）

```javascript
var dataUtils = globalThis.dataUtils;

var obj = dataUtils.parseJson('{"key": "value"}');
var str = dataUtils.stringifyJson({ key: 'value' });
var now = dataUtils.formatDate(); // "2024-01-15 10:30:00"
```

#### CryptoJS

```javascript
var CryptoJS = globalThis.CryptoJS;

// MD5
var hash = CryptoJS.MD5('message').toString();

// AES 加密
var encrypted = CryptoJS.AES.encrypt('message', 'secret').toString();

// AES 解密
var decrypted = CryptoJS.AES.decrypt(encrypted, 'secret').toString(CryptoJS.enc.Utf8);
```

#### Jimp（图像处理）

```javascript
var Jimp = globalThis.Jimp;

// 读取图片
var image = await Jimp.read('path/to/image.png');

// 缩放
image.resize(256, 256);

// 获取 Base64
var base64 = await image.getBase64Async(Jimp.MIME_PNG);
```

#### pako（压缩）

```javascript
var pako = globalThis.pako;

// 压缩
var compressed = pako.deflate('Hello World');

// 解压
var decompressed = pako.inflate(compressed);
```

### 3.6 Android 工具类

#### Android 工具

```javascript
var Android = globalThis.Android;

// 显示 Toast
Android.toast('Hello!');

// 启动 Activity
Android.startActivity({
    action: 'android.intent.action.VIEW',
    data: 'https://example.com'
});
```

#### Intent

```javascript
var Intent = globalThis.Intent;

var intent = Intent.create({
    action: 'android.intent.action.SEND',
    type: 'text/plain'
});
intent.putExtra('android.intent.extra.TEXT', 'Hello!');
Android.startActivity(intent);
```

#### SystemManager

```javascript
var SystemManager = globalThis.SystemManager;

// 获取设备信息
var info = SystemManager.getDeviceInfo();

// 执行 Shell 命令
var result = SystemManager.exec('ls /sdcard');
```

### 3.7 Compose DSL 渲染

#### 定义 UI 组件

```javascript
function MyScreen(params) {
    var ctx = OperitComposeDslRuntime.createContext({
        state: params.state || {},
        memo: params.memo || {},
        packageName: params.packageName
    });
    
    var [count, setCount] = ctx.useState('count', 0);
    
    return {
        composeDsl: {
            screen: ctx.UI.Scaffold({
                topBar: ctx.UI.TopAppBar({
                    title: 'My Screen'
                })
            }, [
                ctx.UI.Column({
                    modifier: ctx.Modifier.padding(16).dp
                }, [
                    ctx.UI.Text({
                        text: 'Count: ' + count,
                        style: ctx.UI.TextStyle.headlineMedium
                    }),
                    ctx.UI.Button({
                        onClick: function() {
                            setCount(count + 1);
                        }
                    }, 'Increment'),
                    ctx.UI.Button({
                        onClick: function() {
                            ctx.navigate('home');
                        }
                    }, 'Go Home')
                ])
            ])
        }
    };
}
```

#### 使用 WebView

```javascript
function WebScreen(params) {
    var ctx = OperitComposeDslRuntime.createContext({});
    var controller = ctx.createWebViewController('main');
    
    // 加载 URL
    controller.loadUrl('https://example.com');
    
    // 执行 JavaScript
    var result = await controller.evaluateJavascript('document.title');
    
    return {
        composeDsl: {
            screen: ctx.UI.WebView({
                controller: controller
            })
        }
    };
}
```

### 3.8 错误处理与调试

#### 错误报告

```javascript
function riskyOperation() {
    try {
        // 可能出错的代码
        var result = JSON.parse(invalidJson);
    } catch (error) {
        // 报告详细错误
        reportDetailedError(error, 'Parsing JSON');
        
        // 或者使用运行时报告
        throw new Error('Failed to parse: ' + error.message);
    }
}
```

#### 日志输出

```javascript
// 使用 console
console.log('Info message');
console.warn('Warning message');
console.error('Error message');

// 使用运行时 log
log('Intermediate result:', result);

// 发送中间结果
sendIntermediateResult(partialData);
```

#### 调试技巧

```javascript
// 1. 查看可用函数
function main(params) {
    // 如果函数找不到，错误信息会列出可用函数
}

// 2. 使用 getEnv 读取环境变量
var debugMode = getEnv('DEBUG_MODE');

// 3. 使用 getState 获取包状态
var state = getState();

// 4. 使用 getLang 获取当前语言
var lang = getLang(); // 'zh' 或 'en'
```

---

## 四、高级特性

### 4.1 全局模块代理

在 UI 运行时中，跨包模块通过全局代理访问：

```javascript
// 在 UI 模块中访问其他包的模块
var utils = require('/shared/utils');

// 访问全局模块成员
var value = utils.someFunction();

// 全局模块是惰性加载的 Proxy，实际调用时才通过桥接执行
```

### 4.2 外部代码加载

```javascript
// 加载 DEX 文件
Java.loadDex('/sdcard/mylib.dex', {
    nativeLibraryDir: '/sdcard/libs'
});

// 加载 JAR 文件
Java.loadJar('/sdcard/mylib.jar');

// 查看已加载的代码路径
var paths = Java.listLoadedCodePaths();
```

### 4.3 图片处理

```javascript
// 从 Base64 注册图片
var imageLink = NativeInterface.registerImageFromBase64(base64String, 'image/png');

// 从路径注册图片
var imageLink = NativeInterface.registerImageFromPath('/sdcard/image.png');

// 图片处理
NativeInterface.image_processing('callbackId', 'resize', JSON.stringify({
    width: 256,
    height: 256
}));
```

### 4.4 路由导航

```javascript
// 从 JavaScript 导航到路由
NativeInterface.navigateToRoute('chat_screen', JSON.stringify({
    chatId: '12345'
}));

// 列出可用路由
var routes = JSON.parse(NativeInterface.listRoutes());
```

### 4.5 包管理

```javascript
// 检查包是否已导入
var imported = NativeInterface.isPackageImported('my.package');

// 导入包
NativeInterface.importPackage('my.package');

// 使用包
NativeInterface.usePackage('my.package');

// 移除包
NativeInterface.removePackage('my.package');

// 列出已导入的包
var packages = JSON.parse(NativeInterface.listImportedPackagesJson());
```

---

## 五、性能与超时配置

### 5.1 超时配置

```kotlin
object JsTimeoutConfig {
    const val MAIN_TIMEOUT_SECONDS = 1800L      // 主超时：30 分钟
    const val PRE_TIMEOUT_SECONDS = 1795L       // 预超时：25 分钟（提前 5 秒警告）
    const val SCRIPT_TIMEOUT_MS = 1800000L      // 脚本超时（毫秒）
    const val TOOL_CALL_TIMEOUT_MS = 1800000L   // 工具调用超时（毫秒）
}
```

### 5.2 引擎池

```kotlin
class JsToolManager {
    companion object {
        private const val MAX_CONCURRENT_ENGINES = 4  // 最大并发引擎数
    }
    
    private val engines = List(MAX_CONCURRENT_ENGINES) { JsEngine(context) }
    private val enginePool = Channel<JsEngine>(capacity = MAX_CONCURRENT_ENGINES)
}
```

### 5.3 性能优化建议

1. **避免频繁创建引擎**：使用 JsToolManager 的引擎池复用引擎
2. **合理使用模块缓存**：模块加载后会缓存，避免重复编译
3. **控制脚本大小**：大脚本会增加编译时间，考虑拆分为模块
4. **及时释放资源**：使用 `engine.destroy()` 释放不再使用的引擎
5. **避免主线程阻塞**：使用异步工具调用而非同步调用

---

## 六、安全设计

### 6.1 沙箱隔离

- 每个脚本在独立的执行上下文中运行
- 通过 `callId` 隔离不同调用的状态
- 模块缓存按包目标隔离

### 6.2 超时保护

- 双层超时机制：预超时警告 + 最终超时终止
- 安全定时器在 QuickJS 层面实现
- 超时后自动清理执行会话

### 6.3 资源限制

- 引擎池限制并发数（默认 4 个）
- 二进制数据大小限制（32KB 阈值）
- 主线程禁止同步 JS 回调

### 6.4 错误隔离

- 脚本错误不会崩溃应用
- 详细的错误上下文捕获（文件、行号、堆栈）
- 自动清理失败的执行会话

---

## 七、调试与故障排查

### 7.1 常见问题

#### 问题 1：函数未找到

```
Error: Function 'xxx' not found in script. Available functions: func1, func2
```

**解决方案**：
- 检查函数名拼写
- 确保函数已导出到 `module.exports`
- 检查脚本是否正确加载

#### 问题 2：模块未找到

```
Error: Cannot resolve module "./utils" from "/main"
```

**解决方案**：
- 检查模块路径是否正确
- 确保模块文件存在于 toolpkg 中
- 检查文件扩展名（`.js` 或 `.json`）

#### 问题 3：Java 类未找到

```
Error: class not found: java.util.ArrayList
```

**解决方案**：
- 检查类名拼写
- 确保类在 classpath 中
- 使用 `Java.classExists()` 检查类是否存在

#### 问题 4：超时

```
Error: Script execution timed out after 1800 seconds
```

**解决方案**：
- 优化脚本性能
- 使用异步操作替代同步阻塞
- 增加超时时间（不推荐）

### 7.2 日志查看

```kotlin
// 启用详细日志
AppLogger.d(TAG, "Debug message")
AppLogger.i(TAG, "Info message")
AppLogger.w(TAG, "Warning message")
AppLogger.e(TAG, "Error message")
```

### 7.3 执行追踪

```kotlin
// 使用 JsExecutionListener 追踪执行
val listener = object : JsExecutionListener {
    override fun onCallLog(callId: String, level: String, message: String) {
        println("[$level] $message")
    }
    
    override fun onIntermediateResult(callId: String, value: Any?) {
        println("Intermediate: $value")
    }
    
    override fun onCompleted(callId: String, result: String) {
        println("Completed: $result")
    }
    
    override fun onFailed(callId: String, error: String) {
        println("Failed: $error")
    }
}

engine.executeScriptFunction(
    script = script,
    functionName = "main",
    params = params,
    executionListener = listener
)
```

---

## 八、最佳实践

### 8.1 脚本编写建议

1. **使用模块组织代码**：
```javascript
// 好的做法
var utils = require('./utils');
var api = require('./api');

function main(params) {
    var data = api.fetchData();
    return utils.process(data);
}
```

2. **处理异步操作**：
```javascript
async function main(params) {
    try {
        var result = await toolCall('http_request', { url: params.url });
        return result;
    } catch (error) {
        reportDetailedError(error, 'HTTP Request');
        return { error: error.message };
    }
}
```

3. **使用类型检查**：
```javascript
function processData(data) {
    if (!data || typeof data !== 'object') {
        throw new Error('Invalid data: expected object');
    }
    // 处理数据
}
```

### 8.2 性能优化

1. **缓存重复计算**：
```javascript
var memo = {};

function expensiveOperation(key) {
    if (memo[key]) {
        return memo[key];
    }
    var result = /* 昂贵计算 */;
    memo[key] = result;
    return result;
}
```

2. **批量处理**：
```javascript
// 好的做法：批量发送
var results = [];
for (var i = 0; i < items.length; i++) {
    results.push(process(items[i]));
}
sendIntermediateResult(results);

// 避免：频繁发送
for (var i = 0; i < items.length; i++) {
    sendIntermediateResult(process(items[i])); // 性能差
}
```

### 8.3 错误处理

```javascript
function main(params) {
    try {
        var result = riskyOperation();
        complete(result);
    } catch (error) {
        // 记录详细错误
        console.error('Operation failed:', error);
        
        // 返回友好的错误信息
        complete({
            success: false,
            error: error.message,
            timestamp: new Date().toISOString()
        });
    }
}
```

---

## 九、总结

Javascript 模块是 Operit AI 的核心执行引擎，通过 QuickJS 提供高性能的 JavaScript 运行环境，并通过完善的桥接机制实现 Java/Kotlin 与 JavaScript 的深度互操作。其设计特点包括：

1. **高性能**：QuickJS 引擎 + 单线程调度 + 引擎池复用
2. **易用性**：CommonJS 模块系统 + 丰富的内置库 + 完整的类型支持
3. **安全性**：沙箱执行 + 超时保护 + 资源限制 + 错误隔离
4. **扩展性**：Java-JS 双向桥接 + 动态代码加载 + Compose DSL 支持
5. **可调试性**：详细的错误上下文 + 执行追踪 + 中间结果输出

通过本模块，开发者可以使用 JavaScript 快速开发 AI 工具、自定义 UI 界面，并与 Android 原生能力深度集成。
