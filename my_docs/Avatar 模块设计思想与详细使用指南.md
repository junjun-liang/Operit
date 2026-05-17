# Avatar 模块设计思想与详细使用指南

## 目录

1. [模块概述](#1-模块概述)
2. [核心设计思想](#2-核心设计思想)
3. [架构总览](#3-架构总览)
4. [公共接口层详解](#4-公共接口层详解)
5. [六种渲染引擎实现](#5-六种渲染引擎实现)
6. [情绪与动画映射机制](#6-情绪与动画映射机制)
7. [关键流程](#7-关键流程)
8. [使用示例](#8-使用示例)
9. [最佳实践](#9-最佳实践)

---

## 1. 模块概述

`avatar` 模块是 Operit 项目的**虚拟形象系统**，支持 6 种渲染技术的虚拟形象展示与控制。从 2D 帧序列动画到 3D 骨骼模型，模块通过统一的抽象层屏蔽底层差异，使上层业务代码无需关心具体的渲染技术。

模块位于 `com.ai.assistance.operit.core.avatar` 包下，采用**公共接口 + 独立实现**的分层架构：

| 层 | 包路径 | 职责 |
|----|--------|------|
| 公共层 | `common/` | 定义接口、模型、状态、工厂抽象 |
| 实现层 | `impl/` | 6 种渲染技术的具体实现 |

**支持的渲染技术**：

| 类型 | 枚举值 | 渲染技术 | 说明 |
|------|--------|---------|------|
| 2D 骨骼 | `DRAGONBONES` | DragonBones 引擎 | 骨骼动画，支持命名动画 |
| 帧序列 | `WEBP` | WebP 动图 | 按情绪切换不同 WebP 文件 |
| 视频 | `MP4` | Android MediaPlayer | 按情绪切换不同 MP4 文件 |
| 3D 模型 | `GLTF` | glTF/GLB 渲染引擎 | 标准 3D 模型格式 |
| 3D 模型 | `FBX` | FBX 渲染引擎 | Autodesk 3D 格式 |
| 3D 模型 | `MMD` | MikuMikuDance PMX | 日系 3D 模型格式 |

---

## 2. 核心设计思想

### 2.1 接口抽象层（Strategy + Bridge 模式）

模块通过三层抽象将"什么形象"与"如何渲染"完全解耦：

- **AvatarModel**：描述"什么形象"（资源路径、类型）
- **AvatarController**：描述"如何控制"（情绪、动画、状态）
- **AvatarRenderer**：描述"如何渲染"（Compose 渲染函数）

上层代码只依赖公共接口，通过工厂获取具体实现，实现**依赖倒置**。

### 2.2 工厂模式（三工厂分工）

| 工厂 | 职责 | 输入 | 输出 |
|------|------|------|------|
| `AvatarModelFactory` | 从数据创建模型 | `Map<String, Any>` / 数据对象 | `AvatarModel` |
| `AvatarControllerFactory` | 创建控制器 | `AvatarModel` | `AvatarController` |
| `AvatarRendererFactory` | 创建渲染器 | `AvatarModel` | `@Composable (Modifier, Controller) -> Unit` |

三个工厂独立工作，各自按 `AvatarType` 枚举分发到具体实现。

### 2.3 状态驱动渲染（StateFlow + playbackNonce）

所有 Controller 通过 `StateFlow<AvatarState>` 暴露状态：

```kotlin
data class AvatarState(
    val emotion: AvatarEmotion = AvatarEmotion.IDLE,
    val currentAnimation: String? = null,
    val isLooping: Boolean = false,
    val playbackNonce: Long = 0L  // 单调递增，触发重复动画重启
)
```

Renderer 收集 `StateFlow`，当 `playbackNonce` 变化时重新开始播放（即使动画名相同）。

### 2.4 情绪抽象与双层映射

**标准情绪**（7 种）：`IDLE`、`LISTENING`、`THINKING`、`HAPPY`、`SAD`、`CONFUSED`、`SURPRISED`

**自定义 Mood**（5 种内置 + 可扩展）：`angry`、`happy`、`shy`、`aojiao`、`cry`

双层映射机制：
- `emotionAnimationMapping`：标准情绪 → 动画名
- `triggerAnimationMapping`：自定义触发器 → 动画名

自定义 Mood 有 `fallbackEmotion`（如 `angry` → `SAD`），当无直接映射时回退到标准情绪。

### 2.5 模型分类：骨骼动画 vs 帧序列

```
AvatarModel (基础接口)
├── ISkeletalAvatarModel (骨骼动画)
│   └── skeletonPath, textureAtlasPath, texturePath
└── IFrameSequenceAvatarModel (帧序列)
    └── animationPath, shouldLoop, repeatCount
```

- **骨骼动画**（DragonBones）：通过骨骼数据 + 纹理集渲染，支持丰富的命名动画
- **帧序列**（WebP）：通过切换不同动画文件实现情绪表达
- **视频**（MP4）、**3D 模型**（glTF/FBX/MMD）：直接实现 `AvatarModel`，各有专属属性

---

## 3. 架构总览

```
┌─────────────────────────────────────────────────────────────────┐
│                        UI 层                                     │
│                                                                  │
│  AvatarView (Composable)                                        │
│  ├─ rendererFactory.createRenderer(model) → Renderer Composable │
│  └─ controller.state.collect() → 驱动渲染更新                    │
│                                                                  │
├─────────────────────────────────────────────────────────────────┤
│                      公共接口层 (common/)                         │
│                                                                  │
│  AvatarModel ── ISkeletalAvatarModel ── IFrameSequenceAvatarModel│
│  AvatarController (state, setEmotion, playAnimation, ...)       │
│  AvatarState (emotion, currentAnimation, isLooping, playbackNonce)│
│  AvatarEmotion (IDLE/LISTENING/THINKING/HAPPY/SAD/CONFUSED/...)  │
│  AvatarMoodTypes (angry/happy/shy/aojiao/cry + 自定义)           │
│  AvatarSettingKeys (scale/translate/camera 参数)                 │
│                                                                  │
│  AvatarModelFactory    ── createModel(type, data)                │
│  AvatarControllerFactory ── createController(model)              │
│  AvatarRendererFactory ── createRenderer(model)                  │
│                                                                  │
├─────────────────────────────────────────────────────────────────┤
│                      实现层 (impl/)                               │
│                                                                  │
│  ┌──────────────┐ ┌──────────┐ ┌──────────┐                     │
│  │ DragonBones  │ │  WebP    │ │   MP4    │                     │
│  │ (2D 骨骼)    │ │ (帧序列) │ │ (视频)   │                     │
│  ├──────────────┤ ├──────────┤ ├──────────┤                     │
│  │   glTF       │ │   FBX    │ │   MMD    │                     │
│  │ (3D 模型)    │ │ (3D 模型)│ │ (3D 模型)│                     │
│  └──────────────┘ └──────────┘ └──────────┘                     │
│                                                                  │
│  AvatarControllerFactoryImpl ── when(type) 分发                  │
│  AvatarModelFactoryImpl      ── when(type) 分发                  │
│  AvatarRendererFactoryImpl   ── when(type) 分发                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 4. 公共接口层详解

### 4.1 AvatarModel（模型接口）

```kotlin
interface AvatarModel {
    val id: String       // 唯一标识
    val name: String     // 显示名称
    val type: AvatarType // 渲染技术类型
}
```

### 4.2 ISkeletalAvatarModel（骨骼动画模型）

```kotlin
interface ISkeletalAvatarModel : AvatarModel {
    val skeletonPath: String       // 骨骼数据文件路径
    val textureAtlasPath: String   // 纹理集 JSON 路径
    val texturePath: String        // 纹理图片路径
}
```

### 4.3 IFrameSequenceAvatarModel（帧序列模型）

```kotlin
interface IFrameSequenceAvatarModel : AvatarModel {
    val animationPath: String      // 动画文件路径
    val shouldLoop: Boolean        // 是否循环（默认 true）
    val repeatCount: Int           // 重复次数（0 = 无限）
}
```

### 4.4 AvatarController（控制器接口）

| 方法 | 说明 |
|------|------|
| `state: StateFlow<AvatarState>` | 当前状态流 |
| `availableAnimations: List<String>` | 可用动画列表 |
| `setEmotion(emotion)` | 设置情绪状态 |
| `playEmotion(emotion, loop)` | 播放情绪对应动画 |
| `playAnimation(name, loop)` | 直接播放指定动画 |
| `playTrigger(triggerName, loop)` | 播放触发器动画 |
| `estimateEmotionDurationMillis(emotion)` | 估算情绪动画时长 |
| `estimateTriggerDurationMillis(triggerName)` | 估算触发器动画时长 |
| `lookAt(x, y)` | 视线追踪（部分支持） |
| `updateSettings(settings)` | 更新设置（缩放/位移/相机） |
| `updateEmotionAnimationMapping(mapping)` | 更新情绪映射 |
| `updateTriggerAnimationMapping(mapping)` | 更新触发器映射 |

### 4.5 AvatarState（状态快照）

```kotlin
data class AvatarState(
    val emotion: AvatarEmotion = AvatarEmotion.IDLE,
    val currentAnimation: String? = null,
    val isLooping: Boolean = false,
    val playbackNonce: Long = 0L  // 单调递增，驱动重复动画重启
)
```

**playbackNonce 的作用**：当需要重新播放同一个动画时（如从 HAPPY 切换回 IDLE），动画名不变但 `playbackNonce` 递增，Renderer 检测到变化后重新开始播放。

### 4.6 AvatarEmotion（标准情绪）

| 情绪 | 说明 |
|------|------|
| `IDLE` | 默认/空闲 |
| `LISTENING` | 正在倾听 |
| `THINKING` | 正在思考 |
| `HAPPY` | 开心 |
| `SAD` | 难过 |
| `CONFUSED` | 困惑 |
| `SURPRISED` | 惊讶 |

### 4.7 AvatarMoodTypes（自定义 Mood）

| Mood Key | 显示名 | 回退情绪 | 触发场景 |
|----------|--------|---------|---------|
| `angry` | Angry | SAD | 侮辱、不公、责备 |
| `happy` | Happy | HAPPY | 表扬、达成目标 |
| `shy` | Shy | CONFUSED | 夸奖、暧昧 |
| `aojiao` | Aojiao | CONFUSED | 调侃、嘴硬 |
| `cry` | Cry | SAD | 失落、难过 |

自定义 Mood 通过 `AvatarMoodTypes.isValidCustomKey()` 验证键名格式（`^[a-z][a-z0-9_\-]{0,31}$`），且不能与内置键名冲突。

### 4.8 AvatarSettingKeys（设置键）

| 键 | 适用类型 | 说明 |
|----|---------|------|
| `scale` | 全部 | 缩放比例 |
| `translateX` / `translateY` | 全部 | 平移偏移 |
| `mmd.initialRotationX/Y/Z` | MMD | 模型初始旋转 |
| `mmd.cameraDistanceScale` | MMD | 相机距离缩放 |
| `mmd.cameraTargetHeight` | MMD | 相机目标高度 |
| `gltf.cameraPitch/Yaw` | glTF | 相机俯仰/偏航角 |
| `gltf.cameraDistanceScale` | glTF | 相机距离缩放 |
| `gltf.cameraTargetHeight` | glTF | 相机目标高度 |
| `fbx.cameraPitch/Yaw` | FBX | 相机俯仰/偏航角 |
| `fbx.cameraDistanceScale` | FBX | 相机距离缩放 |
| `fbx.cameraTargetHeight` | FBX | 相机目标高度 |

---

## 5. 六种渲染引擎实现

### 5.1 DragonBones（2D 骨骼动画）

| 组件 | 类名 | 说明 |
|------|------|------|
| 模型 | `DragonBonesAvatarModel` | 实现 `ISkeletalAvatarModel`，提供骨骼/纹理路径 |
| 控制器 | `DragonBonesAvatarController` | 包装 `DragonBonesLibController`（JNI 原生库） |
| 渲染器 | `DragonBonesRenderer` | Composable，渲染 DragonBones 视图 |

**特点**：
- 底层使用 JNI 原生库（`com.dragonbones.DragonBonesController`）
- 动画时长通过 `JniBridge.getAnimationDuration()` 获取
- 情绪解析：自定义映射 → 动画名直接匹配（如 `emotion.name.lowercase()`）→ IDLE 回退
- `lookAt()` 不支持

**必填数据**：`folderPath`、`skeletonFile`、`textureJsonFile`、`textureImageFile`

### 5.2 WebP（帧序列动画）

| 组件 | 类名 | 说明 |
|------|------|------|
| 模型 | `WebPAvatarModel` | 实现 `IFrameSequenceAvatarModel`，含 `emotionToFileMap` |
| 控制器 | `WebPAvatarController` | 通过 `playbackNonce` 驱动 Renderer 切换文件 |
| 渲染器 | `WebPRenderer` | Composable，播放 WebP 帧序列 |

**特点**：
- 模型持有 `emotionToFileMap: Map<AvatarEmotion, String>`，情绪直接映射到文件
- 非循环动画播放完成后自动回到 IDLE（`onAnimationPlaybackCompleted`）
- 情绪解析：自定义映射 → 模型内置映射 → IDLE 回退 → 第一个可用文件
- `lookAt()` 不支持

**必填数据**：`basePath`

**情绪推断**（`inferEmotionToMediaFileMap`）：当无显式映射时，按文件名推断（如 `idle.webp` → IDLE，`happy.webp` → HAPPY）。

### 5.3 MP4（视频动画）

| 组件 | 类名 | 说明 |
|------|------|------|
| 模型 | `Mp4AvatarModel` | 直接实现 `AvatarModel`，结构与 WebP 几乎一致 |
| 控制器 | `Mp4AvatarController` | 与 WebP 逻辑一致，时长估算使用 `MediaMetadataRetriever` |
| 渲染器 | `Mp4Renderer` | Composable，使用 Android `MediaPlayer` |

**与 WebP 的差异**：
- 时长估算：使用 `MediaMetadataRetriever.extractMetadata(METADATA_KEY_DURATION)` 而非原生库
- 渲染器使用 `MediaPlayer` 而非 WebP 解码器

**必填数据**：`basePath`

### 5.4 glTF（3D 模型动画）

| 组件 | 类名 | 说明 |
|------|------|------|
| 模型 | `GltfAvatarModel` | 含 `defaultAnimation`、`declaredAnimationNames`、`isBinaryGlb` |
| 控制器 | `GltfAvatarController` | 支持运行时动画发现（`updateAnimationMetadata`） |
| 渲染器 | `GltfRenderer` + `GltfSurfaceView` | Composable，使用 OpenGL ES 渲染 |

**特点**：
- 支持运行时动画发现：`updateAvailableAnimations()` 和 `updateAnimationMetadata()` 动态更新动画列表
- `animationDurationMillisByName` 缓存动画时长
- 相机参数：`cameraPitch`、`cameraYaw`、`cameraDistanceScale`、`cameraTargetHeight`
- `lookAt()` 不支持

**必填数据**：`basePath`、`modelFile`

### 5.5 FBX（3D 模型动画）

| 组件 | 类名 | 说明 |
|------|------|------|
| 模型 | `FbxAvatarModel` | 与 GltfAvatarModel 完全对称 |
| 控制器 | `FbxAvatarController` | 与 GltfAvatarController 几乎一致，设置键名不同 |
| 渲染器 | `FbxRenderer` | Composable，FBX 渲染 |

**与 glTF 的差异**：
- 设置键名：`fbx.*` 而非 `gltf.*`
- `_cameraDistanceScale` 默认值 `1.0f`（glTF 为 `0.5f`）
- 无默认动画时选择第一个可用动画（glTF 选择 null）

**必填数据**：`basePath`、`modelFile`

### 5.6 MMD（MikuMikuDance 3D 动画）

| 组件 | 类名 | 说明 |
|------|------|------|
| 模型 | `MmdAvatarModel` | 含 PMX 模型路径、动作文件列表 |
| 控制器 | `MmdAvatarController` | 使用 `MmdNative` JNI 渲染 |
| 渲染器 | `MmdRenderer` | Composable，MMD 原生渲染 |

**特点**：
- 模型格式：PMX，动作格式：VMD
- 动画时长通过 `MmdNative.nativeReadMotionMaxFrame()` 读取帧数，按 30fps 换算
- 模型初始旋转：`initialRotationX/Y/Z`（其他 3D 类型无此参数）
- `playAnimation()` 先清空 `currentAnimation` 再设置（触发 Renderer 重新加载动作）
- `lookAt()` 不支持

**必填数据**：`basePath`、`modelFile`

---

## 6. 情绪与动画映射机制

### 6.1 双层映射表

```
┌─────────────────────────────────────────────┐
│  emotionAnimationMapping                    │
│  ├─ IDLE → "idle_anim"                     │
│  ├─ LISTENING → "listen_anim"              │
│  ├─ THINKING → "think_anim"                │
│  ├─ HAPPY → "happy_anim"                   │
│  ├─ SAD → "sad_anim"                       │
│  ├─ CONFUSED → "confused_anim"             │
│  └─ SURPRISED → "surprise_anim"            │
│                                             │
│  triggerAnimationMapping                    │
│  ├─ "angry" → "angry_anim"                 │
│  ├─ "shy" → "shy_anim"                     │
│  ├─ "aojiao" → "tsundere_anim"             │
│  └─ "cry" → "crying_anim"                  │
└─────────────────────────────────────────────┘
```

### 6.2 情绪解析优先级

```
playEmotion(HAPPY)
    │
    ├─ 1. emotionAnimationMapping[HAPPY] → "happy_anim"
    │      └─ 验证动画名存在于 availableAnimations
    │
    ├─ 2. 模型内置映射（WebP/MP4 的 emotionToFileMap）
    │      └─ model.animationFileForEmotion(HAPPY)
    │
    ├─ 3. 动画名直接匹配（DragonBones）
    │      └─ "happy" in availableAnimations
    │
    ├─ 4. 回退到 IDLE 映射
    │
    └─ 5. 回退到第一个可用动画（WebP/MP4/glTF/FBX）
        或返回 null（DragonBones/MMD）
```

### 6.3 触发器解析流程

```
playTrigger("angry")
    │
    ├─ normalizeKey("angry") → "angry"
    │
    ├─ triggerAnimationMapping["angry"] → "angry_anim"
    │
    ├─ 未找到 → builtInFallbackEmotion("angry") → SAD
    │
    └─ playEmotion(SAD) → 按情绪解析流程处理
```

### 6.4 播放完成回调

| 类型 | 行为 |
|------|------|
| WebP/MP4 | 非循环动画播放完成后自动回到 IDLE（`onAnimationPlaybackCompleted`） |
| DragonBones | 由底层引擎管理循环 |
| glTF/FBX/MMD | 由底层引擎管理循环 |

---

## 7. 关键流程

### 7.1 形象创建与展示完整流程

```
1. 从数据源获取形象配置
   └─ Map<String, Any> 含 type、basePath、modelFile 等

2. 创建模型
   modelFactory.createModel(id, name, type, data)
   └─ when(type) → DragonBonesAvatarModel / WebPAvatarModel / ...

3. 创建控制器
   controllerFactory.createController(model)
   └─ when(model.type) → rememberDragonBonesAvatarController() / ...

4. 注入情绪映射
   controller.updateEmotionAnimationMapping(mapping)
   controller.updateTriggerAnimationMapping(mapping)

5. 渲染
   AvatarView(
       model = model,
       controller = controller,
       rendererFactory = rendererFactory
   )
   └─ rendererFactory.createRenderer(model) → Renderer Composable
       └─ controller.state.collect() → 驱动渲染更新
```

### 7.2 情绪切换流程

```
用户/AI 设置情绪
    │
    ├─ controller.setEmotion(HAPPY)
    │   └─ _state.update { it.copy(emotion = HAPPY) }
    │
    ├─ controller.playEmotion(HAPPY)
    │   ├─ resolveAnimationForEmotion(HAPPY) → "happy_anim"
    │   ├─ _state.update {
    │   │     it.copy(
    │   │         emotion = HAPPY,
    │   │         currentAnimation = "happy_anim",
    │   │         isLooping = false,
    │   │         playbackNonce = it.playbackNonce + 1
    │   │     )
    │   │ }
    │   └─ Renderer 检测到 state 变化 → 播放 "happy_anim"
    │
    └─ 动画播放完成（非循环）
        └─ onAnimationPlaybackCompleted()
            └─ playEmotion(IDLE) → 自动回到空闲状态
```

### 7.3 自定义 Mood 触发流程

```
AI 输出 <mood>shy</mood> 标签
    │
    ├─ 解析 mood 值 → "shy"
    │
    ├─ controller.playTrigger("shy")
    │   ├─ normalizeKey("shy") → "shy"
    │   ├─ triggerAnimationMapping["shy"] → "shy_anim"
    │   │
    │   ├─ 找到 → playAnimation("shy_anim")
    │   └─ 未找到 → builtInFallbackEmotion("shy") → CONFUSED
    │       └─ playEmotion(CONFUSED)
```

---

## 8. 使用示例

### 8.1 基础使用

```kotlin
// 创建工厂
val modelFactory = AvatarModelFactoryImpl()
val controllerFactory = AvatarControllerFactoryImpl()
val rendererFactory = AvatarRendererFactoryImpl()

// 创建模型
val model = modelFactory.createModel(
    id = "my_avatar",
    name = "My Avatar",
    type = AvatarType.WEBP,
    data = mapOf("basePath" to "avatars/my_pet")
) as? WebPAvatarModel ?: return

// 在 Composable 中使用
@Composable
fun AvatarScreen() {
    val controller = controllerFactory.createController(model) ?: return
    
    AvatarView(
        model = model,
        controller = controller,
        rendererFactory = rendererFactory
    )
}
```

### 8.2 情绪控制

```kotlin
// 设置情绪
controller.setEmotion(AvatarEmotion.HAPPY)

// 播放情绪动画
controller.playEmotion(AvatarEmotion.THINKING, loop = 0)  // 无限循环

// 播放指定动画
controller.playAnimation("wave", loop = 1)

// 播放自定义触发器
controller.playTrigger("shy", loop = 0)

// 估算动画时长
val duration = controller.estimateEmotionDurationMillis(AvatarEmotion.HAPPY)
```

### 8.3 自定义情绪映射

```kotlin
// 标准情绪映射
controller.updateEmotionAnimationMapping(
    mapOf(
        AvatarEmotion.IDLE to "breathing",
        AvatarEmotion.HAPPY to "smile",
        AvatarEmotion.THINKING to "head_tilt",
        AvatarEmotion.SAD to "look_down"
    )
)

// 自定义触发器映射
controller.updateTriggerAnimationMapping(
    mapOf(
        "angry" to "furious_pose",
        "shy" to "blush_animation",
        "aojiao" to "turn_away"
    )
)
```

### 8.4 调整形象设置

```kotlin
// 通用设置
controller.updateSettings(mapOf(
    AvatarSettingKeys.SCALE to 1.5f,
    AvatarSettingKeys.TRANSLATE_X to 10f,
    AvatarSettingKeys.TRANSLATE_Y to -20f
))

// glTF 专属相机设置
controller.updateSettings(mapOf(
    AvatarSettingKeys.GLTF_CAMERA_PITCH to 15f,
    AvatarSettingKeys.GLTF_CAMERA_YAW to 30f,
    AvatarSettingKeys.GLTF_CAMERA_DISTANCE_SCALE to 0.8f
))

// MMD 专属设置
controller.updateSettings(mapOf(
    AvatarSettingKeys.MMD_INITIAL_ROTATION_Y to 180f,
    AvatarSettingKeys.MMD_CAMERA_DISTANCE_SCALE to 2.0f
))
```

### 8.5 监听状态变化

```kotlin
LaunchedEffect(controller) {
    controller.state.collect { state ->
        when (state.emotion) {
            AvatarEmotion.IDLE -> showStatus("空闲中")
            AvatarEmotion.LISTENING -> showStatus("正在倾听...")
            AvatarEmotion.THINKING -> showStatus("思考中...")
            AvatarEmotion.HAPPY -> showStatus("开心！")
            AvatarEmotion.SAD -> showStatus("有点难过")
            else -> {}
        }
    }
}
```

### 8.6 创建 DragonBones 形象

```kotlin
val model = modelFactory.createModel(
    id = "db_avatar",
    name = "DragonBones Character",
    type = AvatarType.DRAGONBONES,
    data = mapOf(
        "folderPath" to "avatars/db_char",
        "skeletonFile" to "character_ske.json",
        "textureJsonFile" to "character_tex.json",
        "textureImageFile" to "character_tex.png"
    )
)
```

### 8.7 创建 glTF 形象

```kotlin
val model = modelFactory.createModel(
    id = "gltf_avatar",
    name = "3D Character",
    type = AvatarType.GLTF,
    data = mapOf(
        "basePath" to "avatars/3d_char",
        "modelFile" to "character.glb",
        "defaultAnimation" to "idle",
        "animationNames" to "idle,walk,wave,dance"
    )
)
```

---

## 9. 最佳实践

### 9.1 资源命名约定

**WebP/MP4 帧序列**：按情绪命名文件，自动推断映射

```
avatars/my_pet/
├── idle.webp       → IDLE
├── listening.webp  → LISTENING
├── thinking.webp   → THINKING
├── happy.webp      → HAPPY
├── sad.webp        → SAD
├── confused.webp   → CONFUSED
└── surprised.webp  → SURPRISED
```

**DragonBones 骨骼**：动画名与情绪名一致

```
动画名: idle, listening, thinking, happy, sad, confused, surprised
```

### 9.2 情绪映射配置

- **优先使用显式映射**：避免依赖文件名推断，显式配置更可靠
- **为所有 7 种标准情绪提供映射**：确保任何情绪切换都有对应动画
- **自定义 Mood 设置 fallbackEmotion**：确保无直接映射时能回退

### 9.3 性能优化

- **WebP/MP4**：非循环动画结束后自动回到 IDLE，避免资源浪费
- **glTF/FBX**：使用 `updateAnimationMetadata()` 缓存动画时长，避免重复计算
- **MMD**：动画时长通过 `nativeReadMotionMaxFrame()` 一次性读取
- **DragonBones**：底层 JNI 原生库性能最优

### 9.4 扩展新渲染类型

1. 在 `AvatarType` 枚举中添加新类型
2. 创建 `XxxAvatarModel` 实现 `AvatarModel`（或 `ISkeletalAvatarModel` / `IFrameSequenceAvatarModel`）
3. 创建 `XxxAvatarController` 实现 `AvatarController`
4. 创建 `XxxRenderer` Composable
5. 在三个工厂实现中添加 `when` 分支

### 9.5 自定义 Mood 扩展

```kotlin
// 验证键名
val key = AvatarMoodTypes.normalizeKey("Sleepy")
val isValid = AvatarMoodTypes.isValidCustomKey(key)  // true

// 清理自定义定义
val sanitized = AvatarMoodTypes.sanitizeCustomDefinitions(
    listOf(AvatarCustomMoodDefinition(key = "sleepy", promptHint = "角色困倦时使用"))
)

// 注册触发器映射
controller.updateTriggerAnimationMapping(
    mapOf("sleepy" to "yawn_animation")
)
```

---

*文档生成时间：2026-05-16*
*基于 Operit 项目 avatar 模块源代码分析*
