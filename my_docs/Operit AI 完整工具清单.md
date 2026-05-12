# Operit AI 完整工具清单

> 本文档列出了 Operit AI 项目支持的所有内置工具，包含工具名称、详细描述、参数说明。

**统计信息**:
- 工具包数量：**31 个**
- 工具总数：**150+ 个**
- 文档更新时间：**2026-05-12**

---

## 目录

1. [日常生活工具包 (daily_life)](#1-日常生活工具包-daily_life)
2. [系统工具 (system_tools)](#2-系统工具-system_tools)
3. [超级管理员 (super_admin)](#3-超级管理员-super_admin)
4. [浏览器工具 (browser)](#4-浏览器工具-browser)
5. [工作流工具 (workflow)](#5-工作流工具-workflow)
6. [扩展聊天工具 (extended_chat)](#6-扩展聊天工具-extended_chat)
7. [自动 UI 基础工具 (automatic_ui_base)](#7-自动 ui 基础工具-automatic_ui_base)
8. [自动 UI 子代理 (automatic_ui_subagent)](#8-自动 ui 子代理-automatic_ui_subagent)
9. [代码运行器 (code_runner)](#9-代码运行器-code_runner)
10. [FFmpeg 工具 (ffmpeg)](#10-ffmpeg 工具-ffmpeg)
11. [文件转换工具 (file_converter)](#11-文件转换工具-file_converter)
12. [扩展文件工具 (extended_file_tools)](#12-扩展文件工具-extended_file_tools)
13. [扩展 HTTP 工具 (extended_http_tools)](#13-扩展 http 工具-extended_http_tools)
14. [扩展内存工具 (extended_memory_tools)](#14-扩展内存工具-extended_memory_tools)
15. [GitHub 工具 (github)](#15-github 工具-github)
16. [Google 搜索 (google_search)](#16-google 搜索-google_search)
17. [DuckDuckGo 搜索 (duckduckgo)](#17-duckduckgo 搜索-duckduckgo)
18. [Tavily 搜索 (tavily)](#18-tavily 搜索-tavily)
19. [智谱搜索 (zhipu_search)](#19-智谱搜索-zhipu_search)
20. [各种搜索 (various_search)](#20-各种搜索-various_search)
21. [CrossRef 工具 (crossref)](#21-crossref 工具-crossref)
22. [OpenAI 绘画 (openai_draw)](#22-openai 绘画-openai_draw)
23. [Qwen 绘画 (qwen_draw)](#23-qwen 绘画-qwen_draw)
24. [智谱绘画 (zhipu_draw)](#24-智谱绘画-zhipu_draw)
25. [Minimax 绘画 (minimax_draw)](#25-minimax 绘画-minimax_draw)
26. [XAI 绘画 (xai_draw)](#26-xai 绘画-xai_draw)
27. [SiliconFlow 绘画 (siliconflow_draw)](#27-siliconflow 绘画-siliconflow_draw)
28. [Nanobanana 绘画 (nanobanana_draw)](#28-nanobanana 绘画-nanobanana_draw)
29. [时间工具 (time)](#29-时间工具-time)
30. [Operit 编辑器 (operit_editor)](#30-operit 编辑器-operit_editor)
31. [12306 工具 (12306)](#31-12306 工具-12306)

---

## 1. 日常生活工具包 (daily_life)

**包描述**: 日常生活工具集合：日期时间、设备状态、电量/内存概况、天气查询、提醒、闹钟、短信、电话、微信单条消息发送、QQ 单条消息发送、朋友圈发布、手电筒、音量调节、Wi‑Fi 开关、截图、拍照、深色模式、指定时间唤醒 AI 执行一次性定时任务。

### 1.1 get_current_date

**描述**: 获取当前日期和时间，支持多种格式展示

**参数**:
| 参数名 | 类型 | 必需 | 描述 |
|--------|------|------|------|
| format | string | 否 | 日期格式（'short'简短格式，'medium'中等格式，'long'完整格式，或自定义格式） |

**示例**:
```javascript
await Tools.DailyLife.get_current_date({ format: 'long' });
```

### 1.2 device_status

**描述**: 获取设备状态信息，包括电池和内存使用情况

**参数**: 无

**示例**:
```javascript
await Tools.DailyLife.device_status({});
```

### 1.3 set_reminder

**描述**: 创建提醒或待办事项

**参数**:
| 参数名 | 类型 | 必需 | 描述 |
|--------|------|------|------|
| title | string | ✅ 是 | 提醒或待办事项的标题 |
| description | string | 否 | 提醒的附加详细信息 |
| due_date | string | 否 | 提醒的到期日期（ISO 字符串格式） |

**示例**:
```javascript
await Tools.DailyLife.set_reminder({
    title: '开会',
    description: '下午 3 点会议室',
    due_date: '2026-05-13T15:00:00'
});
```

### 1.4 schedule_one_time_task

**描述**: 在指定时间唤醒 AI 执行一次性定时任务

**参数**:
| 参数名 | 类型 | 必需 | 描述 |
|--------|------|------|------|
| trigger_time | string | ✅ 是 | 触发时间（格式示例：2026-01-04 10:30, 2026-01-04 10:30:00, 2026-01-04T10:30:00, 2026-01-04） |
| message | string | 否 | 唤醒消息内容 |

**示例**:
```javascript
await Tools.DailyLife.schedule_one_time_task({
    trigger_time: '2026-05-13 08:00:00',
    message: '提醒我吃药'
});
```

### 1.5 set_alarm

**描述**: 在设备上设置闹钟

**参数**:
| 参数名 | 类型 | 必需 | 描述 |
|--------|------|------|------|
| hour | number | ✅ 是 | 闹钟小时（0-23） |
| minute | number | ✅ 是 | 闹钟分钟（0-59） |
| message | string | ✅ 是 | 闹钟标签 |
| days | array | 否 | 重复闹钟的天数（数字数组，1=周日，7=周六） |

**示例**:
```javascript
await Tools.DailyLife.set_alarm({
    hour: 7,
    minute: 30,
    message: '起床',
    days: [1, 2, 3, 4, 5]
});
```

### 1.6 send_message

**描述**: 发送短信

**参数**:
| 参数名 | 类型 | 必需 | 描述 |
|--------|------|------|------|
| phone_number | string | ✅ 是 | 接收者电话号码 |
| message | string | ✅ 是 | 短信内容 |

**示例**:
```javascript
await Tools.DailyLife.send_message({
    phone_number: '13800138000',
    message: '你好，这是一条测试短信'
});
```

### 1.7 wechat_send_message

**描述**: 通过微信发送文本消息，调起微信分享界面，由用户选择联系人并确认发送

**参数**:
| 参数名 | 类型 | 必需 | 描述 |
|--------|------|------|------|
| message | string | ✅ 是 | 要发送的文本内容 |

**示例**:
```javascript
await Tools.DailyLife.wechat_send_message({
    message: '你好，这是一条测试消息'
});
```

### 1.8 qq_send_message

**描述**: 通过 QQ 发送文本消息，调起 QQ 分享界面，由用户选择联系人并确认发送

**参数**:
| 参数名 | 类型 | 必需 | 描述 |
|--------|------|------|------|
| message | string | ✅ 是 | 要发送的文本内容 |

**示例**:
```javascript
await Tools.DailyLife.qq_send_message({
    message: '你好，QQ 消息测试'
});
```

### 1.9 wechat_post_moments

**描述**: 通过微信朋友圈发表文本内容，调起朋友圈编辑界面并预填文案，由用户确认后发送

**参数**:
| 参数名 | 类型 | 必需 | 描述 |
|--------|------|------|------|
| message | string | ✅ 是 | 要发布到朋友圈的文本内容 |

**示例**:
```javascript
await Tools.DailyLife.wechat_post_moments({
    message: '今天天气真好'
});
```

### 1.10 make_phone_call

**描述**: 拨打电话

**参数**:
| 参数名 | 类型 | 必需 | 描述 |
|--------|------|------|------|
| phone_number | string | ✅ 是 | 要拨打的电话号码 |
| emergency | boolean | 否 | 是否为紧急呼叫 |

**示例**:
```javascript
await Tools.DailyLife.make_phone_call({
    phone_number: '13800138000',
    emergency: false
});
```

### 1.11 search_weather

**描述**: 搜索当前天气信息

**参数**:
| 参数名 | 类型 | 必需 | 描述 |
|--------|------|------|------|
| location | string | 否 | 要查询天气的位置（城市名称或'current'表示当前位置） |

**示例**:
```javascript
await Tools.DailyLife.search_weather({
    location: 'current'
});
```

### 1.12 toggle_flashlight

**描述**: 打开或关闭手电筒

**参数**:
| 参数名 | 类型 | 必需 | 描述 |
|--------|------|------|------|
| state | string | ✅ 是 | 手电筒状态：'on'表示打开，'off'表示关闭 |

**示例**:
```javascript
await Tools.DailyLife.toggle_flashlight({
    state: 'on'
});
```

### 1.13 adjust_volume

**描述**: 调节设备音量，通过模拟按键点击实现

**参数**:
| 参数名 | 类型 | 必需 | 描述 |
|--------|------|------|------|
| action | string | ✅ 是 | 音量调节动作：'up'增加音量，'down'减小音量，'mute'静音 |
| count | number | 否 | 按键次数，默认为 1 次 |

**示例**:
```javascript
await Tools.DailyLife.adjust_volume({
    action: 'up',
    count: 3
});
```

### 1.14 toggle_wifi

**描述**: 打开或关闭 Wi-Fi

**参数**:
| 参数名 | 类型 | 必需 | 描述 |
|--------|------|------|------|
| state | string | ✅ 是 | Wi-Fi 状态：'on'表示打开，'off'表示关闭 |

**示例**:
```javascript
await Tools.DailyLife.toggle_wifi({
    state: 'on'
});
```

### 1.15 take_screenshot

**描述**: 截取当前屏幕

**参数**:
| 参数名 | 类型 | 必需 | 描述 |
|--------|------|------|------|
| file_path | string | 否 | 截图保存路径，例如 /sdcard/Pictures/screenshot.png。如果未提供，将使用默认路径和时间戳文件名 |

**示例**:
```javascript
await Tools.DailyLife.take_screenshot({
    file_path: '/sdcard/Pictures/screenshot.png'
});
```

### 1.16 take_photo

**描述**: 打开相机应用拍照

**参数**: 无

**示例**:
```javascript
await Tools.DailyLife.take_photo({});
```

### 1.17 toggle_dark_mode

**描述**: 切换系统深夜模式（暗色主题）

**参数**:
| 参数名 | 类型 | 必需 | 描述 |
|--------|------|------|------|
| state | string | ✅ 是 | 模式：'on'开启，'off'关闭，'auto'自动 |

**示例**:
```javascript
await Tools.DailyLife.toggle_dark_mode({
    state: 'on'
});
```

---

## 2. 系统工具 (system_tools)

**包描述**: 提供系统级操作工具，包括设置管理、应用安装卸载与启动、通知获取、位置服务、设备信息查询，以及 Intent/广播调用。

### 2.1 get_system_setting

**描述**: 获取系统设置的值。需要用户授权。

**参数**:
| 参数名 | 类型 | 必需 | 描述 |
|--------|------|------|------|
| setting | string | ✅ 是 | 设置名称 |
| namespace | string | 否 | 命名空间：system/secure/global，默认 system |

**示例**:
```javascript
await Tools.SystemTools.get_system_setting({
    setting: 'screen_brightness',
    namespace: 'system'
});
```

### 2.2 modify_system_setting

**描述**: 修改系统设置的值。需要用户授权。

**参数**:
| 参数名 | 类型 | 必需 | 描述 |
|--------|------|------|------|
| setting | string | ✅ 是 | 设置名称 |
| value | string | ✅ 是 | 设置值 |
| namespace | string | 否 | 命名空间：system/secure/global，默认 system |

**示例**:
```javascript
await Tools.SystemTools.modify_system_setting({
    setting: 'screen_brightness',
    value: '200',
    namespace: 'system'
});
```

### 2.3 install_app

**描述**: 安装应用程序。需要用户授权。

**参数**:
| 参数名 | 类型 | 必需 | 描述 |
|--------|------|------|------|
| path | string | ✅ 是 | APK 文件路径 |

**示例**:
```javascript
await Tools.SystemTools.install_app({
    path: '/sdcard/Download/app.apk'
});
```

### 2.4 uninstall_app

**描述**: 卸载应用程序。需要用户授权。

**参数**:
| 参数名 | 类型 | 必需 | 描述 |
|--------|------|------|------|
| package_name | string | ✅ 是 | 应用包名 |
| keep_data | boolean | 否 | 是否保留数据，默认 false |

**示例**:
```javascript
await Tools.SystemTools.uninstall_app({
    package_name: 'com.example.app',
    keep_data: false
});
```

### 2.5 list_installed_apps

**描述**: 获取已安装应用程序列表。需要用户授权。

**参数**:
| 参数名 | 类型 | 必需 | 描述 |
|--------|------|------|------|
| include_system_apps | boolean | 否 | 是否包含系统应用，默认 false |

**示例**:
```javascript
await Tools.SystemTools.list_installed_apps({
    include_system_apps: false
});
```

### 2.6 start_app

**描述**: 启动应用程序。需要用户授权。

**参数**:
| 参数名 | 类型 | 必需 | 描述 |
|--------|------|------|------|
| package_name | string | ✅ 是 | 应用包名 |
| activity | string | 否 | 可选活动名称 |

**示例**:
```javascript
await Tools.SystemTools.start_app({
    package_name: 'com.tencent.mm'
});
```

### 2.7 stop_app

**描述**: 停止正在运行的应用程序。需要用户授权。

**参数**:
| 参数名 | 类型 | 必需 | 描述 |
|--------|------|------|------|
| package_name | string | ✅ 是 | 应用包名 |

**示例**:
```javascript
await Tools.SystemTools.stop_app({
    package_name: 'com.example.app'
});
```

### 2.8 send_broadcast

**描述**: 发送广播（Broadcast Intent）。需要用户授权。

**参数**:
| 参数名 | 类型 | 必需 | 描述 |
|--------|------|------|------|
| action | string | ✅ 是 | Intent action，例如 android.intent.action.VIEW |
| package_name | string | 否 | 可选：限制广播目标包名 |
| component | string | 否 | 可选：组件名 package/class，优先于 package_name |
| uri | string | 否 | 可选：data uri |
| extras | object | 否 | 可选：extras（对象，可用于传参） |

**示例**:
```javascript
await Tools.SystemTools.send_broadcast({
    action: 'com.example.MY_ACTION',
    extras: { key: 'value' }
});
```

### 2.9 execute_intent

**描述**: 执行 Intent（Activity/Service/Broadcast），支持 extras 传参。需要用户授权。

**参数**:
| 参数名 | 类型 | 必需 | 描述 |
|--------|------|------|------|
| type | string | 否 | 类型：activity/broadcast/service，默认 activity |
| action | string | 否 | Intent action（action 或 component 至少一个必填） |
| package_name | string | 否 | 可选：包名 |
| component | string | 否 | 可选：组件名 package/class |
| uri | string | 否 | 可选：data uri |
| flags | string | 否 | 可选：flags（整数或 JSON 数组字符串） |
| extras | object | 否 | 可选：extras（对象，可用于传参） |

**示例**:
```javascript
await Tools.SystemTools.execute_intent({
    type: 'activity',
    action: 'android.intent.action.VIEW',
    uri: 'https://www.example.com'
});
```

### 2.10 get_notifications

**描述**: 获取设备通知内容。

**参数**:
| 参数名 | 类型 | 必需 | 描述 |
|--------|------|------|------|
| limit | number | 否 | 最大返回条数，默认 10 |
| include_ongoing | boolean | 否 | 是否包含常驻通知，默认 false |

**示例**:
```javascript
await Tools.SystemTools.get_notifications({
    limit: 5,
    include_ongoing: false
});
```

### 2.11 get_app_usage_time

**描述**: 获取应用前台使用时长。需要授予"使用情况访问权限"。

**参数**:
| 参数名 | 类型 | 必需 | 描述 |
|--------|------|------|------|
| package_name | string | 否 | 可选：精确应用包名 |
| since_hours | number | 否 | 向前统计多少小时，默认 24 |
| limit | number | 否 | 不传包名时最多返回多少个应用，默认 10 |
| include_system_apps | boolean | 否 | 不传包名时是否包含系统应用，默认 false |

**示例**:
```javascript
await Tools.SystemTools.get_app_usage_time({
    since_hours: 24,
    limit: 10
});
```

### 2.12 get_device_location

**描述**: 获取设备当前位置信息。

**参数**:
| 参数名 | 类型 | 必需 | 描述 |
|--------|------|------|------|
| high_accuracy | boolean | 否 | 是否使用高精度模式，默认 false |
| timeout | number | 否 | 超时时间（秒），默认 10 |

**示例**:
```javascript
await Tools.SystemTools.get_device_location({
    high_accuracy: false,
    timeout: 10
});
```

### 2.13 get_device_info

**描述**: 获取详细的设备信息，包括型号、操作系统版本、内存、存储、网络状态等。

**参数**: 无

**示例**:
```javascript
await Tools.SystemTools.get_device_info({});
```

---

## 3. 超级管理员 (super_admin)

**包描述**: 超级管理员工具集，提供终端命令和 Shell 操作的高级功能。terminal 工具运行在 Ubuntu 环境中（已正确挂载 sdcard 和 storage），shell 工具通过 Shizuku/Root 直接执行 Android 系统命令。

### 3.1 terminal

**描述**: 在 Ubuntu 环境中执行命令并收集输出结果

**参数**:
| 参数名 | 类型 | 必需 | 描述 |
|--------|------|------|------|
| command | string | ✅ 是 | 要执行的命令 |
| background | string | 否 | 是否在后台运行命令，"true" 表示后台执行，"false" 或未提供则前台执行 |
| timeoutMs | string | 否 | 可选超时（毫秒，最低 3000ms）。前台默认 15000ms |

**示例**:
```javascript
await Tools.SuperAdmin.terminal({
    command: 'ls -la',
    background: 'false',
    timeoutMs: '15000'
});
```

### 3.2 terminal_wait

**描述**: 等待同一终端会话中的上一条命令执行完成

**参数**:
| 参数名 | 类型 | 必需 | 描述 |
|--------|------|------|------|
| sessionId | string | 否 | 可选目标会话 ID，不传则使用默认会话 |
| timeoutMs | string | 否 | 可选超时（毫秒，最低 3000ms），默认 300000ms（5 分钟） |

**示例**:
```javascript
await Tools.SuperAdmin.terminal_wait({
    sessionId: 'session_1',
    timeoutMs: '60000'
});
```

### 3.3 terminal_getscreen

**描述**: 获取当前终端会话可见屏幕内容（仅一屏，不包含历史滚动缓冲）

**参数**: 无

**示例**:
```javascript
await Tools.SuperAdmin.terminal_getscreen({});
```

### 3.4 terminal_input

**描述**: 向当前终端会话写入输入

**参数**:
| 参数名 | 类型 | 必需 | 描述 |
|--------|------|------|------|
| input | string | 否 | 写入终端的文本 |
| control | string | 否 | 控制键，例如 enter / tab / esc / ctrl |

**示例**:
```javascript
await Tools.SuperAdmin.terminal_input({
    input: 'ls -la',
    control: 'enter'
});
```

### 3.5 shell

**描述**: 通过 Shizuku/Root 权限直接在 Android 系统中执行 Shell 命令

**参数**:
| 参数名 | 类型 | 必需 | 描述 |
|--------|------|------|------|
| command | string | ✅ 是 | 要执行的 Shell 命令 |

**示例**:
```javascript
await Tools.SuperAdmin.shell({
    command: 'pm list packages'
});
```

---

由于文档篇幅限制，以下是其他工具包的简表。如需某个工具包的详细信息，请告诉我。

## 4-31. 其他工具包总览

### 4. 浏览器工具 (browser)
- 打开网页、页面导航、历史管理、书签管理、下载管理、Cookie 管理、用户脚本支持等

### 5. 工作流工具 (workflow)
- 执行工作流、管理工作流列表、触发工作流等

### 6. 扩展聊天工具 (extended_chat)
- 多角色对话、群组聊天、消息队列管理、聊天上下文管理等

### 7. 自动 UI 基础工具 (automatic_ui_base)
- UI 元素识别、自动点击、滑动、文本输入、手势操作等

### 8. 自动 UI 子代理 (automatic_ui_subagent)
- 并行子代理执行、多任务处理、虚拟屏支持等

### 9. 代码运行器 (code_runner)
- Node.js 代码执行、Python 代码执行、代码沙箱、依赖管理等

### 10. FFmpeg 工具 (ffmpeg)
- 视频剪辑、音频提取、格式转换、视频合并、添加水印等

### 11. 文件转换工具 (file_converter)
- 图片格式转换、文档格式转换、音频格式转换等

### 12. 扩展文件工具 (extended_file_tools)
- 文件压缩、解压、搜索、预览等

### 13. 扩展 HTTP 工具 (extended_http_tools)
- HTTP GET/POST 请求、自定义 Headers、文件上传下载、Cookie 管理等

### 14. 扩展内存工具 (extended_memory_tools)
- 记忆管理、记忆搜索、记忆存储等

### 15. GitHub 工具 (github)
- 仓库管理、Issue 管理、Pull Request 管理、文件操作、搜索等

### 16. Google 搜索 (google_search)
- Google 网页搜索、图片搜索、新闻搜索等

### 17. DuckDuckGo 搜索 (duckduckgo)
- DuckDuckGo 隐私保护搜索

### 18. Tavily 搜索 (tavily)
- AI 优化搜索、内容提取、网站爬取、地图搜索等

### 19. 智谱搜索 (zhipu_search)
- 智谱 AI 中文优化搜索

### 20. 各种搜索 (various_search)
- 多种搜索引擎集成

### 21. CrossRef 工具 (crossref)
- 学术论文搜索、DOI 查询等

### 22-28. AI 绘画工具
- **openai_draw**: OpenAI DALL-E 图像生成
- **qwen_draw**: Qwen（通义万相）图像生成
- **zhipu_draw**: Zhipu（ cogview）图像生成
- **minimax_draw**: Minimax 图像生成
- **xai_draw**: XAI 图像生成
- **siliconflow_draw**: SiliconFlow 图像生成
- **nanobanana_draw**: Nanobanana 图像生成

**通用参数**:
| 参数名 | 类型 | 必需 | 描述 |
|--------|------|------|------|
| prompt | string | ✅ 是 | 图像生成提示词 |
| size | string | 否 | 图像尺寸（如 1024x1024） |
| negative_prompt | string | 否 | 负面提示词 |

### 29. 时间工具 (time)
- 时间转换、时区计算、时间格式化等

### 30. Operit 编辑器 (operit_editor)
- MCP 服务器配置、Skill 包管理、配置文件编辑等

### 31. 12306 工具 (12306)
- 火车票查询、余票查询、时刻表查询等

---

## 工具使用通用说明

### 调用方式

所有工具通过统一的 API 调用：

```javascript
await Tools.<ToolPkgName>.<toolName>(params);
```

**示例**:
```javascript
// 调用日常生活工具包
await Tools.DailyLife.get_current_date({ format: 'long' });

// 调用系统工具包
await Tools.SystemTools.get_device_info({});

// 调用超级管理员
await Tools.SuperAdmin.terminal({ command: 'ls -la' });
```

### 返回值格式

所有工具返回统一格式：

```typescript
{
    success: boolean,      // 是否成功
    message: string,       // 结果描述
    data?: any,           // 返回数据（可选）
    error?: string        // 错误信息（可选）
}
```

### 权限要求

**标准权限**（无需额外授权）:
- 日常生活工具
- 基础系统查询
- 网络请求
- 文件读写

**需要用户授权**:
- 系统设置修改
- 应用安装/卸载
- 位置信息
- 通知读取
- 应用使用时长

**需要特殊权限**:
- Root 功能（需要 Root 授权）
- Shizuku 功能（需要 Shizuku 授权）
- 无障碍功能（需要无障碍服务授权）

---

## 完整详细文档

由于工具数量众多（150+ 个），以上只列出了最常用的工具。

**如需查看某个工具包的完整详细文档**，请告诉我具体的工具包名称，我会为你提供该工具包的完整工具清单和详细说明。

---

**文档版本**: v1.0  
**最后更新**: 2026-05-12  
**维护者**: Operit AI Team
