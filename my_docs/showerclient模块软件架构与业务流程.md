# Operit AI — showerclient 模块软件架构与业务流程快速上手

## 一、项目定位

`showerclient` 模块是 **Operit AI** 的 **虚拟屏（Virtual Display）客户端模块**，负责与 `tools/shower` 服务端的 AIDL 服务进行 IPC 通信，实现虚拟显示屏的创建、视频流接收与渲染、以及触摸/按键输入注入。它是整个 **Shower 投屏系统** 的客户端组件，为 AI 自动化（UI 自动化、远程控制）提供底层基础设施。

### 核心特性

| 特性 | 说明 |
|------|------|
| **AIDL IPC 通信** | 通过 `IShowerService` 与 Shower 服务端跨进程通信 |
| **虚拟屏管理** | 创建/销毁虚拟显示屏（VirtualDisplay） |
| **H.264 视频解码** | MediaCodec 硬件解码视频流并渲染到 Surface |
| **WebSocket