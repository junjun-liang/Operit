# Widget 桌面小组件模块设计文档

> 基于 Operit 项目 `widget` 模块代码分析
>
> 文档生成时间：2026-05-15

---

## 一、模块概述

### 1.1 设计定位

Widget 模块是 Operit AI Agent 的**桌面小组件系统**，基于 Android Jetpack Glance 框架构建，提供两种类型的小组件：

1. **ToolPkg Desktop Widget**：允许 ToolPkg（JS 工具包）通过 Compose DSL 自定义桌面小组件内容
2. **Voice Assistant Widget**：快速启动语音助手的快捷入口

### 1.2 核心设计思想

| 设计理念 | 说明 |
|---------|------|
| **Glance 框架** | 使用 Jetpack Glance 替代传统 RemoteViews，支持 Compose 声明式 UI |
| **DSL 驱动** | ToolPkg 通过 Compose DSL 描述 Widget 布局，实现动态渲染 |
| **JS 引擎渲染** | Widget 内容由 QuickJS 引擎执行 DSL 脚本生成 |
| **配置持久化** | 使用 SharedPreferences 保存 Widget 与 ToolPkg 的绑定关系 |
| **生命周期管理** | Receiver 处理 Widget 添加/删除事件，自动清理资源 |

### 1.3 架构总览

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Android 桌面                                     │
│  ┌─────────────────────┐  ┌─────────────────────┐                          │
│  │ ToolPkg Widget #1   │  │ Voice Assistant     │                          │
│  │ [DSL 渲染的内容]     │  │ [麦克风图标]         │                          │
│  │                     │  │                     │                          │
│  │ 点击 → 打开 App     │  │ 点击 → 启动语音      │                          │
│  └─────────────────────┘  └─────────────────────┘                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                              Widget 层                                       │
│  ToolPkgDesktopGlanceWidget        VoiceAssistantGlanceWidget               │
│       │                                    │                                 │
│       ├──► provideGlance()               ├──► provideGlance()               │
│       │       │                          │       │                         │
│       │       ├──► resolveSelection()    │       └──► VoiceAssistantContent │
│       │       ├──► loadRenderData()      │                                     │
│       │       └──► Render DSL            │                                     │
│       │                                    │                                     │
│       └──► ToolPkgDesktopWidgetHost        │                                     │
│               ├──► save/clear Selection    │                                     │
│               ├──► build Launch Intent     │                                     │
│               └──► refreshAll()            │                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                              DSL 渲染层                                      │
│  ToolPkgDesktopWidgetDslRenderer                                            │
│       ├──► loadToolPkgDesktopWidgetRenderData()                             │
│       │       ├──► 查找 ToolPkg UI Route                                    │
│       │       ├──► 获取 DSL 脚本                                            │
│       │       ├──► QuickJS 执行脚本                                         │
│       │       ├──► ToolPkgComposeDslParser 解析                             │
│       │       └──► 处理 onLoad Action                                       │
│       └──► RenderToolPkgDesktopWidgetDsl()                                  │
│               ├──► column/row/box → Glance Column/Row/Box                   │
│               ├──► text → Glance Text                                       │
│               ├──► button → Glance Box + Text                               │
│               ├──► spacer → Glance Spacer                                   │
│               └──► linear/circular progress → Glance Text                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                              配置层                                          │
│  ToolPkgDesktopWidgetConfigActivity                                         │
│       ├──► 列出可用 ToolPkg Widgets                                         │
│       ├──► 用户选择并保存                                                   │
│       └──► 刷新 Widget 显示                                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                              生命周期层                                      │
│  ToolPkgDesktopWidgetReceiver        VoiceAssistantWidgetReceiver           │
│       ├──► onDeleted → clearSelection      └──► 绑定 GlanceAppWidget        │
│       └──► 绑定 GlanceAppWidget                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 二、核心组件详解

### 2.1 GlanceAppWidget（小组件主体）

#### ToolPkgDesktopGlanceWidget

```kotlin
class ToolPkgDesktopGlanceWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        
        // 1. 解析已保存的 Widget 配置
        val selection = ToolPkgDesktopWidgetHost.resolveSelection(context, appWidgetId)
        
        // 2. 加载 DSL 渲染数据
        val renderData = selection?.let {
            loadToolPkgDesktopWidgetRenderData(context, appWidgetId, it)
        }
        
        // 3. 提供 Compose 内容
        provideContent {
            ToolPkgDesktopWidgetContent(
                context = context,
                appWidgetId = appWidgetId,
                selection = selection,
                renderData = renderData
            )
        }
    }
}
```

**渲染优先级**：

```
1. DSL 渲染结果（最高优先级）
   └── renderData?.renderResult != null
       └── RenderToolPkgDesktopWidgetDsl(node, clickAction)

2. 错误状态
   └── renderData?.errorMessage != null
       └── 显示标题 + 错误信息

3. 未配置状态
   └── selection == null
       └── 显示"未配置"提示

4. 默认状态（最低优先级）
   └── 显示 widget.title + widget.subtitle + "打开"
```

#### VoiceAssistantGlanceWidget

```kotlin
class VoiceAssistantGlanceWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            VoiceAssistantWidgetContent(context)
        }
    }
}
```

**特点**：
- 无需配置，固定显示麦克风图标
- 点击直接启动 `FloatingChatService`（全屏语音模式）
- 使用半透明蓝色背景，适配深色/浅色主题

### 2.2 GlanceAppWidgetReceiver（生命周期接收器）

#### ToolPkgDesktopWidgetReceiver

```kotlin
class ToolPkgDesktopWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget
        get() = ToolPkgDesktopGlanceWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        // Widget 被删除时清理持久化数据
        appWidgetIds.forEach { appWidgetId ->
            ToolPkgDesktopWidgetHost.clearSelection(context, appWidgetId)
        }
    }
}
```

#### VoiceAssistantWidgetReceiver

```kotlin
class VoiceAssistantWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget
        get() = VoiceAssistantGlanceWidget()
}
```

### 2.3 WidgetHost（配置管理）

`ToolPkgDesktopWidgetHost` 是 ToolPkg Widget 的核心管理类，负责配置的持久化和解析：

```kotlin
object ToolPkgDesktopWidgetHost {
    // SharedPreferences 存储键前缀
    private const val PREFS_NAME = "toolpkg_desktop_widget_host"
    private const val KEY_PREFIX_SELECTION = "selection:"
    private const val KEY_PREFIX_ROUTE = "route:"
    // ... 更多键前缀

    data class WidgetSelection(
        val key: String,                          // 选择键：containerPackageName:widgetId
        val widget: PackageManager.ToolPkgDesktopWidget
    )

    // 构建选择键
    fun buildSelectionKey(containerPackageName: String, widgetId: String): String

    // 列出可用的 ToolPkg Widgets
    fun listAvailableWidgets(context: Context): List<ToolPkgDesktopWidget>

    // 解析已保存的选择
    fun resolveSelection(context: Context, appWidgetId: Int): WidgetSelection?

    // 保存选择
    fun saveSelection(context: Context, appWidgetId: Int, widget: ToolPkgDesktopWidget): Boolean

    // 清除选择
    fun clearSelection(context: Context, appWidgetId: Int)

    // 构建启动 Intent（打开 App 指定路由）
    fun buildLaunchIntent(context: Context, routeId: String, routeArgsJson: String? = null): Intent

    // 构建配置 Intent（打开配置页面）
    fun buildConfigIntent(context: Context, appWidgetId: Int): Intent

    // 刷新所有 Widget
    fun refreshAll(context: Context)
}
```

**持久化数据结构**：

```
SharedPreferences: "toolpkg_desktop_widget_host"
├── "selection:{appWidgetId}" → "com.example.pkg:widgetId"
├── "route:{appWidgetId}" → "routeId"
├── "render_route:{appWidgetId}" → "renderRouteId"
├── "container:{appWidgetId}" → "containerPackageName"
├── "widget_id:{appWidgetId}" → "widgetId"
├── "title:{appWidgetId}" → "Widget Title"
├── "subtitle:{appWidgetId}" → "Widget Subtitle"
├── "description:{appWidgetId}" → "Description"
├── "icon:{appWidgetId}" → "iconName"
└── "order:{appWidgetId}" → 0
```

### 2.4 配置 Activity

`ToolPkgDesktopWidgetConfigActivity` 提供 Widget 配置界面：

```
用户长按桌面 → 添加 Widget → 选择 ToolPkg Widget
    │
    └──► 系统启动 ToolPkgDesktopWidgetConfigActivity
            │
            ├──► 从 PackageManager 获取所有可用的 ToolPkgDesktopWidget
            ├──► 显示 LazyColumn 列表（标题/副标题/包名/描述）
            ├──► 用户点击选择
            ├──► 调用 ToolPkgDesktopWidgetHost.saveSelection()
            ├──► 设置 RESULT_OK
            └──► finish() → onStop() → delay(300ms) → refreshAll()
```

**关键设计**：
- `setResult(RESULT_CANCELED)` 作为默认值，确保用户取消时系统知道
- `shouldRefreshAfterFinish` 标记控制是否在 finish 后刷新 Widget
- `delay(300)` 等待系统创建 Widget 实例后再刷新

---

## 三、DSL 渲染系统

### 3.1 渲染流程

```
loadToolPkgDesktopWidgetRenderData()
    │
    ├──► 1. 获取 PackageManager 实例
    │
    ├──► 2. 查找匹配的 UI Route
    │       packageManager.getToolPkgUiRoutes()
    │           .firstOrNull { route.containerPackageName == selection.widget.containerPackageName
    │                       && route.routeId == selection.widget.renderRouteId }
    │
    ├──► 3. 获取 DSL 脚本
    │       packageManager.getToolPkgComposeDslScript(containerPackageName, uiModuleId)
    │
    ├──► 4. 获取 screenPath
    │       packageManager.getToolPkgComposeDslScreenPath(containerPackageName, uiModuleId)
    │
    ├──► 5. 创建执行上下文
    │       executionContextKey = "toolpkg_widget:{appWidgetId}:{containerPackageName}:{uiModuleId}"
    │       jsEngine = packageManager.getToolPkgExecutionEngine(executionContextKey)
    │
    ├──► 6. 构建运行时选项
    │       runtimeOptions = {
    │           packageName, toolPkgId, uiModuleId,
    │           __operit_ui_package_name, __operit_ui_toolpkg_id,
    │           __operit_ui_module_id, __operit_script_screen,
    │           moduleSpec, state: {}, memo: {}
    │       }
    │
    ├──► 7. 执行 DSL 脚本
    │       initialRaw = jsEngine.executeComposeDslScript(script, runtimeOptions)
    │       initialParsed = ToolPkgComposeDslParser.parseRenderResult(initialRaw)
    │
    ├──► 8. 处理 onLoad Action（如果有）
    │       onLoadActionId = extractActionId(initialParsed.tree.props["onLoad"])
    │       if (onLoadActionId != null) {
    │           finalRaw = jsEngine.executeComposeDslAction(actionId, runtimeOptions)
    │           finalParsed = ToolPkgComposeDslParser.parseRenderResult(finalRaw)
    │       }
    │
    ├──► 9. 释放 JS 引擎
    │       packageManager.releaseToolPkgExecutionEngine(executionContextKey)
    │
    └──► 10. 返回 ToolPkgDesktopWidgetRenderData
```

### 3.2 DSL 节点到 Glance 组件映射

```kotlin
@Composable
private fun renderToolPkgDesktopWidgetDslNode(
    node: ToolPkgComposeDslNode,
    routeClickAction: Action,
    defaultClickable: Boolean = false
) {
    when (normalizeToken(node.type)) {
        // 布局容器
        "column", "lazycolumn", "scaffold", "surface" → Glance Column
        "row" → Glance Row
        "box", "card", "elevatedcard", "outlinedcard" → Glance Box
        
        // 文本
        "text" → Glance Text（支持 color、fontSize、fontWeight）
        
        // 按钮（统一渲染为蓝色 Box + 白色 Text）
        "button", "textbutton", "outlinedbutton", 
        "filledtonalbutton", "elevatedbutton" → Glance Box + Text
        
        // 间距
        "spacer" → Glance Spacer
        
        // 进度指示器（简化为百分比文本）
        "linearprogressindicator" → Glance Text("{progress}%")
        "circularprogressindicator" → Glance Text("Loading")
        
        // 未知类型：收集子元素并放入 Column
        else → Glance Column + 递归渲染子元素
    }
}
```

### 3.3 支持的 DSL 属性

| 属性 | 类型 | 说明 | 示例 |
|------|------|------|------|
| `width` | Number | 宽度（dp） | `width: 100` |
| `height` | Number | 高度（dp） | `height: 50` |
| `fillMaxSize` | Boolean | 填满父容器 | `fillMaxSize: true` |
| `fillMaxWidth` | Boolean | 填满宽度 | `fillMaxWidth: true` |
| `padding` | Number/Map | 内边距 | `padding: 16` 或 `padding: {horizontal: 8, vertical: 4}` |
| `paddingHorizontal` | Number | 水平内边距 | `paddingHorizontal: 12` |
| `paddingVertical` | Number | 垂直内边距 | `paddingVertical: 8` |
| `backgroundColor` | String/Number/Map | 背景色 | `backgroundColor: "#1E88E5"` |
| `containerColor` | String/Number/Map | 容器色（别名） | `containerColor: "primary"` |
| `color` | String/Number/Map | 文本颜色 | `color: "onSurface"` |
| `fontSize` | Number | 字体大小（sp） | `fontSize: 14` |
| `fontWeight` | String | 字重 | `fontWeight: "bold"` |
| `text` | String | 文本内容 | `text: "Hello"` |
| `progress` | Number | 进度（0-1） | `progress: 0.75` |
| `value` | Number | 进度值（别名） | `value: 0.5` |
| `onClick` | Action | 点击动作 | `onClick: {actionId: "navigate"}` |

### 3.4 颜色系统

**Color Token 映射**：

| Token | 颜色值 | 说明 |
|-------|--------|------|
| `primary` | `#1E88E5` | 主色（蓝色） |
| `onprimary` | `White` | 主色上的文字 |
| `surface` | `#F6F4EE` | 表面色（浅色主题背景） |
| `onsurface` | `#1E2A35` | 表面上的文字 |
| `onsurfacevariant` | `#52606D` | 表面变体文字 |
| `secondary` | `#26A69A` | 次色（青色） |
| `tertiary` | `#F59E0B` | 第三色（橙色） |
| `error` | `#D32F2F` | 错误色（红色） |

**颜色解析优先级**：

```
1. Color Token（如 "primary"）
2. 十六进制字符串（如 "#1E88E5"）
3. Android Color 常量（如 "@android:color/black"）
4. 数字（ARGB 值）
```

### 3.5 Modifier 处理

```kotlin
private fun widgetModifierFromNode(
    node: ToolPkgComposeDslNode,
    routeClickAction: Action,
    clickable: Boolean = defaultClickable
): GlanceModifier {
    var modifier = GlanceModifier
    
    // 尺寸
    width?.let { modifier = modifier.width(it.dp) }
    height?.let { modifier = modifier.height(it.dp) }
    fillMaxSize?.let { modifier = modifier.fillMaxSize() }
    fillMaxWidth?.let { modifier = modifier.fillMaxWidth() }
    
    // 内边距
    modifier = applyWidgetPadding(modifier, props["padding"], props)
    
    // 背景色
    parseColorProvider(props["backgroundColor"])?.let { 
        modifier = modifier.background(it) 
    }
    
    // 点击（如果节点有 onClick 或默认可点击）
    if (clickable || hasExplicitClick) {
        modifier = modifier.clickable(routeClickAction)
    }
    
    return modifier
}
```

---

## 四、两种 Widget 对比

| 维度 | ToolPkg Desktop Widget | Voice Assistant Widget |
|------|----------------------|----------------------|
| **用途** | 显示 ToolPkg 自定义内容 | 快速启动语音助手 |
| **配置** | 需要配置（选择 ToolPkg） | 无需配置 |
| **内容来源** | QuickJS 执行 DSL 脚本 | 固定 UI（Compose 代码） |
| **动态性** | 高（每次刷新重新执行脚本） | 低（静态内容） |
| **交互** | 点击打开 App 指定路由 | 点击启动 FloatingChatService |
| **组件** | Glance + DSL 渲染器 | 纯 Glance Compose |
| **生命周期** | 需管理 Selection 持久化 | 简单绑定 |

---

## 五、使用方法

### 5.1 添加 Voice Assistant Widget

1. 长按桌面空白处 → 选择"小组件"
2. 找到 Operit → 选择"语音助手"
3. 添加到桌面
4. 点击即可启动全屏语音助手

### 5.2 添加 ToolPkg Desktop Widget

1. 长按桌面空白处 → 选择"小组件"
2. 找到 Operit → 选择"ToolPkg 桌面组件"
3. 系统启动配置页面
4. 从列表中选择要显示的 ToolPkg Widget
5. 点击确认，Widget 显示在桌面

### 5.3 ToolPkg 开发 Widget

ToolPkg 开发者需要在 `manifest.json` 中声明桌面 Widget：

```json
{
  "desktopWidgets": [
    {
      "widgetId": "my-widget",
      "title": "我的组件",
      "subtitle": "显示实时数据",
      "description": "这是一个示例桌面组件",
      "routeId": "main",
      "renderRouteId": "widget-render",
      "icon": "widget_icon"
    }
  ],
  "uiRoutes": [
    {
      "routeId": "widget-render",
      "uiModuleId": "widget-dsl"
    }
  ]
}
```

然后在 DSL 脚本中定义 Widget 内容：

```javascript
// widget-dsl.js
export default function WidgetScreen() {
  return {
    type: 'column',
    props: {
      padding: 16,
      fillMaxSize: true
    },
    children: [
      {
        type: 'text',
        props: {
          text: '实时数据',
          fontSize: 18,
          fontWeight: 'bold',
          color: 'primary'
        }
      },
      {
        type: 'spacer',
        props: { height: 8 }
      },
      {
        type: 'text',
        props: {
          text: '当前温度: 25°C',
          fontSize: 14,
          color: 'onSurfaceVariant'
        }
      },
      {
        type: 'button',
        props: {
          text: '刷新',
          onClick: { actionId: 'refresh' }
        }
      }
    ]
  };
}
```

### 5.4 刷新 Widget

```kotlin
// 刷新所有 ToolPkg Widget
ToolPkgDesktopWidgetHost.refreshAll(context)

// 刷新单个 Widget（通过 Glance）
ToolPkgDesktopGlanceWidget().update(context, glanceId)
```

---

## 六、关键文件路径

| 文件 | 路径 | 说明 |
|------|------|------|
| `ToolPkgDesktopGlanceWidget.kt` | `widget/ToolPkgDesktopGlanceWidget.kt` | ToolPkg Widget 主体 |
| `ToolPkgDesktopWidgetDslRenderer.kt` | `widget/ToolPkgDesktopWidgetDslRenderer.kt` | DSL 渲染器 |
| `ToolPkgDesktopWidgetHost.kt` | `widget/ToolPkgDesktopWidgetHost.kt` | 配置管理 Host |
| `ToolPkgDesktopWidgetReceiver.kt` | `widget/ToolPkgDesktopWidgetReceiver.kt` | 生命周期接收器 |
| `ToolPkgDesktopWidgetConfigActivity.kt` | `widget/ToolPkgDesktopWidgetConfigActivity.kt` | 配置页面 |
| `VoiceAssistantGlanceWidget.kt` | `widget/VoiceAssistantGlanceWidget.kt` | 语音助手 Widget |
| `VoiceAssistantWidgetReceiver.kt` | `widget/VoiceAssistantWidgetReceiver.kt` | 语音助手接收器 |

---

## 七、设计亮点

1. **Glance 框架**：使用 Jetpack Glance 替代 RemoteViews，支持 Compose 声明式语法，代码更简洁

2. **DSL 驱动渲染**：ToolPkg 通过 JavaScript DSL 描述 Widget 布局，无需重新编译 App 即可更新 Widget 内容

3. **QuickJS 执行引擎**：Widget DSL 脚本由 QuickJS 引擎执行，支持动态逻辑和状态管理

4. **onLoad 机制**：支持初始渲染后执行 `onLoad` Action，实现数据加载和二次渲染

5. **配置持久化**：使用 SharedPreferences 保存 Widget 与 ToolPkg 的绑定关系，支持应用重启后恢复

6. **生命周期自动清理**：Widget 删除时自动调用 `clearSelection`，避免数据残留

7. **统一的点击处理**：所有可点击元素统一触发 `routeClickAction`，打开 App 指定路由

8. **颜色主题适配**：使用 `ColorProvider(day/night)` 自动适配深色/浅色主题

---

*文档结束*
