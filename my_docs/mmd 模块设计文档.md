# mmd 模块软件设计架构与使用文档

## 目录

1. [模块概述](#1-模块概述)
2. [整体架构](#2-整体架构)
3. [Saba 引擎核心架构](#3-saba-引擎核心架构)
4. [JNI 桥接层](#4-jni-桥接层)
5. [Kotlin API 层](#5-kotlin-api-层)
6. [项目中的使用方式](#6-项目中的使用方式)
7. [构建配置](#7-构建配置)
8. [使用方法](#8-使用方法)
9. [文件索引](#9-文件索引)

---

## 1. 模块概述

**mmd** 模块是 Operit 项目中用于 **MMD（MikuMikuDance）3D 模型渲染与动画播放** 的 Android 封装模块。它基于开源项目 [saba](https://github.com/benikabocha/saba) 构建，集成了 Bullet3 物理引擎，支持在 Android 设备上加载、渲染和播放 MMD 模型（.pmd/.pmx）和动作数据（.vmd）。

### 1.1 核心能力

| 能力 | 说明 |
|------|------|
| MMD 模型加载 | 支持 PMD（旧格式）和 PMX（新格式）两种模型文件 |
| VMD 动作播放 | 支持 VMD 动作文件，包含骨骼动画、表情变形、相机运动 |
| 物理模拟 | 集成 Bullet3 物理引擎，支持刚体和关节的物理模拟 |
| OpenGL ES 3.0 渲染 | 基于 GLESv3 的实时渲染，支持材质、纹理、边缘线、阴影 |
| 表情/变形系统 | 支持 MMD 的顶点变形（Morph）系统 |
| 模型检测 | 支持解析模型和动作文件的元数据信息 |

### 1.2 模块定位

```
┌─────────────────────────────────────────────┐
│           Operit 应用层                       │
│  ┌─────────────┐  ┌─────────────────────┐  │
│  │ AI Chat     │  │ 虚拟形象 (Avatar)    │  │
│  │ 3D 预览      │  │ 表情/动作驱动        │  │
│  └─────────────┘  └─────────────────────┘  │
├─────────────────────────────────────────────┤
│           业务封装层                          │
│  ┌─────────────────────────────────────────┐│
│  │ MmdAvatarController                     ││
│  │ MmdRenderer (Compose)                   ││
│  │ MmdAvatarModel                          ││
│  └─────────────────────────────────────────┘│
├─────────────────────────────────────────────┤
│           mmd 模块 (Kotlin + JNI Wrapper)    │
│  ┌─────────────┐  ┌─────────────────────┐  │
│  │ MmdGlSurfaceView                       │  │
│  │ MmdNative   │  │ MmdInspector        │  │
│  │ NativeMmdRenderer                      │  │
│  └─────────────┘  └─────────────────────┘  │
├─────────────────────────────────────────────┤
│           JNI 桥接层 (C++)                   │
│  ┌─────────────┐  ┌─────────────────────┐  │
│  │ MmdRendererBridge.cpp                  │  │
│  │ MmdInspectorBridge.cpp                 │  │
│  │ AndroidAssetSupport.cpp                │  │
│  └─────────────┘  └─────────────────────┘  │
├─────────────────────────────────────────────┤
│           Saba 引擎核心 (C++)                │
│  ┌─────────────┐  ┌─────────────────────┐  │
│  │ Viewer      │  │ GLMMDModel          │  │
│  │ Camera      │  │ GLMMDModelDrawer    │  │
│  │ Light       │  │ ShadowMap           │  │
│  └─────────────┘  └─────────────────────┘  │
├─────────────────────────────────────────────┤
│           物理引擎                            │
│  Bullet3 (BulletDynamics / BulletCollision)  │
└─────────────────────────────────────────────┘
```

---

## 2. 整体架构

### 2.1 分层架构

```
┌─────────────────────────────────────────────────────────────┐
│  应用层                                                      │
│  - Compose UI (MmdRenderer)                                │
│  - Avatar 系统 (MmdAvatarController)                        │
│  - 表情/动作驱动                                            │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  AvatarController 接口层                                     │
│  interface AvatarController {                               │
│    fun setEmotion(emotion: AvatarEmotion)                   │
│    fun playAnimation(name: String, loop: Int)               │
│    fun lookAt(x: Float, y: Float)                           │
│    fun updateSettings(settings: Map<String, Any>)           │
│    val availableAnimations: List<String>                    │
│  }                                                          │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  MmdAvatarController (业务封装)                              │
│  - 情绪到动画映射 (emotionAnimationMapping)                  │
│  - 触发器到动画映射 (triggerAnimationMapping)                │
│  - 动画时长估算 (基于 VMD 帧数)                              │
│  - 缩放/平移/旋转/相机参数管理                                │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  Kotlin API 层 (com.ai.assistance.mmd)                      │
│  ┌─────────────────┐  ┌─────────────────────────────────────┐│
│  │ MmdGlSurfaceView│  │ MmdInspector                        ││
│  │ - setModelPath()│  │ - isAvailable()                     ││
│  │ - setAnimation  │  │ - inspectModel()                    ││
│  │ - setRotation() │  │ - inspectMotion()                   ││
│  │ - setCamera...  │  │                                     ││
│  └─────────────────┘  └─────────────────────────────────────┘│
│  ┌─────────────────────────────────────────────────────────┐│
│  │ MmdNative                                               ││
│  │ - nativeCreateRenderer() / nativeDestroyRenderer()      ││
│  │ - nativeOnSurfaceCreated() / nativeOnSurfaceChanged()   ││
│  │ - nativeRender() / nativePause() / nativeResume()       ││
│  │ - nativeSetModelPath() / nativeSetAnimationState()      ││
│  │ - nativeReadModelName() / nativeReadModelSummary()      ││
│  │ - nativeReadMotionModelName() / nativeReadMotionSummary ││
│  └─────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼ JNI
┌─────────────────────────────────────────────────────────────┐
│  C++ JNI 桥接层                                              │
│  ┌─────────────────────────────────────────────────────────┐│
│  │ MmdRendererBridge.cpp                                   ││
│  │ - RendererHandle (mutex + Viewer + lastError)           ││
│  │ - nativeCreateRenderer → new Viewer()                   ││
│  │ - nativeOnSurfaceCreated → viewer->OnSurfaceCreated()   ││
│  │ - nativeRender → viewer->RenderFrame()                  ││
│  │ - nativeSetModelPath → viewer->SetModelPath()           ││
│  │ - nativeSetAnimationState → viewer->SetAnimationState() ││
│  └─────────────────────────────────────────────────────────┘│
│  ┌─────────────────────────────────────────────────────────┐│
│  │ MmdInspectorBridge.cpp                                  ││
│  │ - ParseModelFile (PMD/PMX)                              ││
│  │ - ParseMotionFile (VMD)                                 ││
│  │ - ReadMotionMaxFrame                                    ││
│  └─────────────────────────────────────────────────────────┘│
│  ┌─────────────────────────────────────────────────────────┐│
│  │ AndroidAssetSupport.cpp                                 ││
│  │ - SetAssetManager / GetAssetManager                     ││
│  │ - AssetExists / ReadTextAsset / ReadBinaryAsset         ││
│  │ - ResolveBuiltinAssetPath                               ││
│  └─────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  Saba 引擎核心 (C++)                                         │
│  ┌─────────────────┐  ┌─────────────────────────────────────┐│
│  │ Viewer          │  │ GLMMDModel                          ││
│  │ - Initialize    │  │ - Create (from MMDModel)            ││
│  │ - OnSurfaceCreated│  │ - LoadAnimation (VMD)             ││
│  │ - RenderFrame   │  │ - UpdateAnimation                   ││
│  │ - SetModelPath  │  │ - UpdateMorph                       ││
│  │ - SetAnimation  │  │ - EnablePhysics / EnableEdge        ││
│  └─────────────────┘  └─────────────────────────────────────┘│
│  ┌─────────────────┐  ┌─────────────────────────────────────┐│
│  │ Camera          │  │ GLMMDModelDrawer                    ││
│  │ - Update        │  │ - Draw (with materials)             ││
│  │ - SetTarget     │  │ - ShadowMap support                 ││
│  └─────────────────┘  └─────────────────────────────────────┘│
│  ┌─────────────────┐  ┌─────────────────────────────────────┐│
│  │ MMDModel        │  │ MMDPhysics (Bullet3)                ││
│  │ - PMDModel      │  │ - RigidBody / Joint                 ││
│  │ - PMXModel      │  │ - PhysicsWorld                      ││
│  └─────────────────┘  └─────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
```

---

## 3. Saba 引擎核心架构

### 3.1 Viewer — 渲染主控制器

[Viewer.h](file:///home/meizu/Documents/my_agent_projects/Operit/mmd/src/main/cpp/Saba/Viewer/Viewer.h)

```cpp
class Viewer {
public:
    Viewer();
    ~Viewer();

    bool Initialize(std::string* outError);
    void Destroy();

    bool OnSurfaceCreated(std::string* outError);
    bool OnSurfaceChanged(int width, int height, std::string* outError);
    bool RenderFrame(std::string* outError);

    bool SetModelPath(const std::string& modelPath, std::string* outError);
    bool SetAnimationState(const std::string& animationName, bool isLooping, std::string* outError);
    void SetModelRotation(float rotationX, float rotationY, float rotationZ);
    void SetCameraDistanceScale(float scale);
    void SetCameraTargetHeight(float height);
    void Pause();
    void Resume();

private:
    ViewerContext m_viewerContext;
    std::unique_ptr<GLMMDModelDrawContext> m_mmdDrawContext;
    std::shared_ptr<GLMMDModel> m_glMmdModel;
    std::shared_ptr<GLMMDModelDrawer> m_mmdDrawer;
    std::shared_ptr<MMDModel> m_mmdModel;

    std::string m_modelPath;
    std::string m_animationName;
    std::string m_motionPath;

    bool m_initialized = false;
    bool m_surfaceCreated = false;
    bool m_paused = false;
    bool m_animationLooping = false;

    float m_rotationX = 0.0f;
    float m_rotationY = 0.0f;
    float m_rotationZ = 0.0f;
    float m_cameraDistanceScale = 1.0f;
    float m_cameraTargetHeight = 0.0f;
    float m_baseCameraDistance = 3.0f;

    int32_t m_maxMotionFrame = 0;
    std::chrono::steady_clock::time_point m_lastFrameAt;
};
```

**设计要点**：
- `Viewer` 是渲染的核心控制器，管理模型加载、动画播放、相机控制和渲染循环
- 生命周期：Create → Initialize → OnSurfaceCreated → OnSurfaceChanged → RenderFrame → Destroy
- 支持 Pause/Resume 控制动画播放
- 动画基于时间推进，自动计算帧率

### 3.2 GLMMDModel — OpenGL MMD 模型

[GLMMDModel.h](file:///home/meizu/Documents/my_agent_projects/Operit/mmd/src/main/cpp/Saba/GL/Model/MMD/GLMMDModel.h)

```cpp
class GLMMDModel {
public:
    bool Create(std::shared_ptr<MMDModel> mmdModel);
    void Destroy();

    bool LoadAnimation(const VMDFile& vmd);
    void LoadPose(const VPDFile& vpd, int frameCount = 30);

    void ResetAnimation();
    void ClearAnimation();

    void SetAnimationTime(double time);
    double GetAnimationTime() const;
    void EvaluateAnimation(double animTime);
    void UpdateAnimation(double animTime, double elapsed);
    void UpdateAnimationIgnoreVMD(double elapsed);
    void UpdateMorph();
    void Update();

    // VBO/IBO 访问
    const GLBufferObject& GetPositionVBO() const;
    const GLBufferObject& GetNormalVBO() const;
    const GLBufferObject& GetUVVBO() const;
    const GLBufferObject& GetIBO() const;

    // 材质和子网格
    const std::vector<GLMMDMaterial>& GetMaterials() const;
    const std::vector<MMDSubMesh>& GetSubMeshes() const;

    // 开关
    void EnablePhysics(bool enable);
    void EnableEdge(bool enable);
    void EnableGroundShadow(bool enable);

    struct PerfInfo {
        double m_setupAnimTime;
        double m_updateMorphAnimTime;
        double m_updateNodeAnimTime;
        double m_updatePhysicsAnimTime;
        double m_updateModelTime;
        double m_updateGLBufferTime;
    };
};
```

**渲染管线**：

```
模型加载 (PMD/PMX)
    │
    ▼
创建 GLMMDModel
    │
    ▼
加载 VMD 动画
    │
    ▼
每帧渲染循环:
    ├─ UpdateAnimationClock()     // 计算时间增量
    ├─ UpdateAnimation(time, elapsed)  // 更新骨骼动画
    │   ├─ SetupAnimation()       // 设置动画状态
    │   ├─ UpdateMorphAnim()      // 更新表情变形
    │   ├─ UpdateNodeAnim()       // 更新节点动画
    │   └─ UpdatePhysicsAnim()    // 更新物理模拟 (Bullet3)
    ├─ UpdateModel()              // 更新顶点数据
    ├─ UpdateGLBuffer()           // 更新 GPU 缓冲区
    ├─ Draw()                     // OpenGL 绘制
    │   ├─ 边缘线绘制 (Edge)
    │   ├─ 地面阴影 (GroundShadow)
    │   └─ 主模型绘制 (Materials)
    └─ SwapBuffers()
```

### 3.3 模型格式支持

| 格式 | 扩展名 | 说明 |
|------|--------|------|
| PMD | `.pmd` | MMD 旧版模型格式，固定结构 |
| PMX | `.pmx` | MMD 新版模型格式，灵活结构，支持更多功能 |
| VMD | `.vmd` | MMD 动作数据格式，包含骨骼/表情/相机/灯光/IK |
| VPD | `.vpd` | MMD 姿势数据格式 |

### 3.4 物理引擎集成

Saba 引擎通过 Bullet3 实现 MMD 的物理模拟：

```cpp
// MMDPhysics.cpp
class MMDPhysics {
    btDiscreteDynamicsWorld* m_dynamicsWorld;
    std::vector<std::unique_ptr<MMDRigidBody>> m_rigidBodies;
    std::vector<std::unique_ptr<MMDJoint>> m_joints;
};
```

- **刚体（RigidBody）**：模拟头发、裙子等部位的物理效果
- **关节（Joint）**：连接刚体，限制运动范围
- **物理世界**：每帧更新物理状态，影响骨骼变换

---

## 4. JNI 桥接层

### 4.1 库加载

[MmdLibraryLoader.kt](file:///home/meizu/Documents/my_agent_projects/Operit/mmd/src/main/java/com/ai/assistance/mmd/MmdLibraryLoader.kt)

```kotlin
internal object MmdLibraryLoader {
    fun loadLibraries() {
        System.loadLibrary("MmdWrapper")
    }
}
```

### 4.2 MmdRendererBridge.cpp — 渲染器 JNI

[MmdRendererBridge.cpp](file:///home/meizu/Documents/my_agent_projects/Operit/mmd/src/main/cpp/android/MmdRendererBridge.cpp)

```cpp
struct RendererHandle {
    std::mutex mutex;
    std::unique_ptr<saba::Viewer> viewer;
    std::string lastError;
};

// 创建渲染器
JNIEXPORT jlong JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeCreateRenderer(JNIEnv*, jclass) {
    auto* handle = new RendererHandle();
    handle->viewer = std::make_unique<saba::Viewer>();
    return reinterpret_cast<jlong>(handle);
}

// 销毁渲染器
JNIEXPORT void JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeDestroyRenderer(JNIEnv*, jclass, jlong handleValue) {
    delete FromHandle(handleValue);
}

// Surface 创建
JNIEXPORT void JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeOnSurfaceCreated(
    JNIEnv* env, jclass, jlong handleValue, jobject assetManager) {
    auto* handle = FromHandle(handleValue);
    operit::androidbridge::SetAssetManager(AAssetManager_fromJava(env, assetManager));
    std::string error;
    if (!handle->viewer->OnSurfaceCreated(&error)) {
        SetError(handle, error);
    }
}

// 渲染一帧
JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeRender(JNIEnv*, jclass, jlong handleValue) {
    auto* handle = FromHandle(handleValue);
    std::string error;
    if (!handle->viewer->RenderFrame(&error)) {
        SetError(handle, error);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

// 设置模型路径
JNIEXPORT void JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeSetModelPath(
    JNIEnv* env, jclass, jlong handleValue, jstring pathModel) {
    auto* handle = FromHandle(handleValue);
    const std::string modelPath = JStringToString(env, pathModel);
    std::string error;
    if (!handle->viewer->SetModelPath(modelPath, &error)) {
        SetError(handle, error);
    }
}

// 设置动画状态
JNIEXPORT void JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeSetAnimationState(
    JNIEnv* env, jclass, jlong handleValue, jstring animationName, jboolean isLooping) {
    auto* handle = FromHandle(handleValue);
    const std::string name = JStringToString(env, animationName);
    std::string error;
    if (!handle->viewer->SetAnimationState(name, isLooping == JNI_TRUE, &error)) {
        SetError(handle, error);
    }
}
```

**关键设计**：
- `RendererHandle` 封装 `Viewer` 实例，带 `mutex` 保护线程安全
- 错误处理：通过 `SetError`/`GetError` 机制传递 C++ 错误到 Kotlin
- 所有操作都通过 `handle` 指针访问对应的 `Viewer` 实例

### 4.3 MmdInspectorBridge.cpp — 模型检测 JNI

[MmdInspectorBridge.cpp](file:///home/meizu/Documents/my_agent_projects/Operit/mmd/src/main/cpp/android/MmdInspectorBridge.cpp)

```cpp
// 检测模块是否可用
JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeIsAvailable(JNIEnv*, jclass) {
#if defined(OPERIT_HAS_SABA) && OPERIT_HAS_SABA
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

// 读取模型名称
JNIEXPORT jstring JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeReadModelName(JNIEnv* env, jclass, jstring pathModel) {
    const std::string modelPath = JStringToString(env, pathModel);
    ModelParseResult parsedModel;
    std::string parseError;
    if (!ParseModelFile(modelPath, &parsedModel, &parseError)) {
        SetLastError(parseError);
        return nullptr;
    }
    return StringToJString(env, parsedModel.modelName);
}

// 读取模型摘要
JNIEXPORT jlongArray JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeReadModelSummary(JNIEnv* env, jclass, jstring pathModel) {
    // 返回 [format, vertexCount, faceCount, materialCount, boneCount, morphCount, rigidBodyCount, jointCount]
}

// 读取动作模型名称
JNIEXPORT jstring JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeReadMotionModelName(JNIEnv* env, jclass, jstring pathMotion);

// 读取动作摘要
JNIEXPORT jlongArray JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeReadMotionSummary(JNIEnv* env, jclass, jstring pathMotion);
// 返回 [motionCount, morphCount, cameraCount, lightCount, shadowCount, ikCount]

// 读取动作最大帧数
JNIEXPORT jint JNICALL
Java_com_ai_assistance_mmd_MmdNative_nativeReadMotionMaxFrame(JNIEnv* env, jclass, jstring pathMotion);
```

### 4.4 AndroidAssetSupport.cpp — Android Assets 支持

[AndroidAssetSupport.h](file:///home/meizu/Documents/my_agent_projects/Operit/mmd/src/main/cpp/android/AndroidAssetSupport.h)

```cpp
namespace operit::androidbridge {

void SetAssetManager(AAssetManager* assetManager);
AAssetManager* GetAssetManager();

bool AssetExists(const std::string& assetPath);
bool ReadTextAsset(const std::string& assetPath, std::string* outText);
bool ReadBinaryAsset(const std::string& assetPath, std::vector<std::uint8_t>* outData);

std::string NormalizeAssetPath(std::string assetPath);
std::string ResolveBuiltinAssetPath(const std::string& logicalPath);

}
```

**作用**：
- 将 Android `AssetManager` 传递给 C++ 层
- C++ 代码可以通过 `AssetManager` 读取 APK assets 中的资源（如 toon 纹理）
- 支持内置资源路径解析（如 `mmd_common_toon/toon01.bmp`）

---

## 5. Kotlin API 层

### 5.1 MmdNative — JNI 接口对象

[MmdNative.kt](file:///home/meizu/Documents/my_agent_projects/Operit/mmd/src/main/java/com/ai/assistance/mmd/MmdNative.kt)

```kotlin
object MmdNative {

    init { MmdLibraryLoader.loadLibraries() }

    // 可用性检测
    external fun nativeIsAvailable(): Boolean
    external fun nativeGetUnavailableReason(): String
    external fun nativeGetLastError(): String

    // 模型检测
    external fun nativeReadModelName(pathModel: String): String?
    external fun nativeReadModelSummary(pathModel: String): LongArray?

    // 动作检测
    external fun nativeReadMotionModelName(pathMotion: String): String?
    external fun nativeReadMotionSummary(pathMotion: String): LongArray?
    external fun nativeReadMotionMaxFrame(pathMotion: String): Int

    // 渲染器管理
    external fun nativeCreateRenderer(): Long
    external fun nativeDestroyRenderer(handle: Long)

    // Surface 生命周期
    external fun nativeOnSurfaceCreated(handle: Long, assetManager: AssetManager)
    external fun nativeOnSurfaceChanged(handle: Long, width: Int, height: Int)
    external fun nativeRender(handle: Long): Boolean

    // 播放控制
    external fun nativePause(handle: Long)
    external fun nativeResume(handle: Long)

    // 模型/动画设置
    external fun nativeSetModelPath(handle: Long, pathModel: String?)
    external fun nativeSetAnimationState(handle: Long, animationName: String?, isLooping: Boolean)
    external fun nativeSetModelRotation(handle: Long, rotationX: Float, rotationY: Float, rotationZ: Float)
    external fun nativeSetCameraDistanceScale(handle: Long, scale: Float)
    external fun nativeSetCameraTargetHeight(handle: Long, height: Float)

    // 错误获取
    external fun nativeGetRendererLastError(handle: Long): String
}
```

### 5.2 MmdInspector — 模型/动作信息检测

[MmdInspector.kt](file:///home/meizu/Documents/my_agent_projects/Operit/mmd/src/main/java/com/ai/assistance/mmd/MmdInspector.kt)

```kotlin
enum class MmdModelFormat { PMD, PMX, UNKNOWN }

data class MmdModelInfo(
    val format: MmdModelFormat,
    val modelName: String,
    val vertexCount: Int,
    val faceCount: Int,
    val materialCount: Int,
    val boneCount: Int,
    val morphCount: Int,
    val rigidBodyCount: Int,
    val jointCount: Int
)

data class VmdMotionInfo(
    val modelName: String,
    val motionCount: Int,
    val morphCount: Int,
    val cameraCount: Int,
    val lightCount: Int,
    val shadowCount: Int,
    val ikCount: Int
)

object MmdInspector {
    fun isAvailable(): Boolean = MmdNative.nativeIsAvailable()
    fun unavailableReason(): String = MmdNative.nativeGetUnavailableReason()
    fun getLastError(): String = MmdNative.nativeGetLastError()

    fun inspectModel(pathModel: String): MmdModelInfo? {
        val summary = MmdNative.nativeReadModelSummary(pathModel) ?: return null
        val format = when (summary[0].toInt()) {
            1 -> MmdModelFormat.PMD
            2 -> MmdModelFormat.PMX
            else -> MmdModelFormat.UNKNOWN
        }
        val modelName = MmdNative.nativeReadModelName(pathModel).orEmpty()
        return MmdModelInfo(
            format = format,
            modelName = modelName,
            vertexCount = summary[1].toInt(),
            faceCount = summary[2].toInt(),
            materialCount = summary[3].toInt(),
            boneCount = summary[4].toInt(),
            morphCount = summary[5].toInt(),
            rigidBodyCount = summary[6].toInt(),
            jointCount = summary[7].toInt()
        )
    }

    fun inspectMotion(pathMotion: String): VmdMotionInfo? {
        val summary = MmdNative.nativeReadMotionSummary(pathMotion) ?: return null
        val modelName = MmdNative.nativeReadMotionModelName(pathMotion).orEmpty()
        return VmdMotionInfo(
            modelName = modelName,
            motionCount = summary[0].toInt(),
            morphCount = summary[1].toInt(),
            cameraCount = summary[2].toInt(),
            lightCount = summary[3].toInt(),
            shadowCount = summary[4].toInt(),
            ikCount = summary[5].toInt()
        )
    }
}
```

### 5.3 MmdGlSurfaceView — OpenGL 渲染视图

[MmdGlSurfaceView.kt](file:///home/meizu/Documents/my_agent_projects/Operit/mmd/src/main/java/com/ai/assistance/mmd/MmdGlSurfaceView.kt)

```kotlin
class MmdGlSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private val renderer = NativeMmdRenderer(context.applicationContext, instanceId)

    init {
        setEGLContextClientVersion(3)           // OpenGL ES 3.0
        setEGLConfigChooser(8, 8, 8, 8, 24, 8) // RGBA8, Depth24, Stencil8
        setZOrderOnTop(true)
        setBackgroundColor(Color.TRANSPARENT)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        requestHighRefreshRateIfSupported()      // 120Hz
    }

    fun setModelPath(path: String) {
        queueEvent { renderer.setModelPath(path) }
    }

    fun setAnimationState(animationName: String?, isLooping: Boolean) {
        queueEvent { renderer.setAnimationState(animationName, isLooping) }
    }

    fun setModelRotation(rotationX: Float, rotationY: Float, rotationZ: Float) {
        queueEvent { renderer.setModelRotation(rotationX, rotationY, rotationZ) }
    }

    fun setCameraDistanceScale(scale: Float) {
        queueEvent { renderer.setCameraDistanceScale(scale) }
    }

    fun setCameraTargetHeight(height: Float) {
        queueEvent { renderer.setCameraTargetHeight(height) }
    }

    fun setOnRenderErrorListener(listener: ((String) -> Unit)?) {
        renderer.setOnErrorListener(listener)
    }
}
```

**设计要点**：
- 继承 `GLSurfaceView`，使用 OpenGL ES 3.0 上下文
- 透明背景：`setZOrderOnTop(true)` + `PixelFormat.TRANSLUCENT`
- 高刷新率：尝试设置 120Hz 帧率
- 所有 GL 操作通过 `queueEvent` 在 GL 线程执行
- `preserveEGLContextOnPause = true` 保持上下文

### 5.4 NativeMmdRenderer — GL 渲染器实现

```kotlin
private class NativeMmdRenderer(
    private val appContext: Context,
    private val instanceId: Int
) : GLSurfaceView.Renderer {

    private var rendererHandle: Long = MmdNative.nativeCreateRenderer()

    // GLSurfaceView.Renderer 回调
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        if (rendererHandle == 0L) {
            rendererHandle = MmdNative.nativeCreateRenderer()
        }
        MmdNative.nativeOnSurfaceCreated(rendererHandle, appContext.assets)
        syncRequestedState()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        MmdNative.nativeOnSurfaceChanged(rendererHandle, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val renderSuccess = MmdNative.nativeRender(rendererHandle)
        if (!renderSuccess) {
            val error = MmdNative.nativeGetRendererLastError(rendererHandle)
            dispatchError(error)
        }
    }

    // 状态同步
    private fun syncRequestedState() {
        MmdNative.nativeSetModelRotation(rendererHandle, rotationX, rotationY, rotationZ)
        MmdNative.nativeSetCameraDistanceScale(rendererHandle, cameraDistanceScale)
        MmdNative.nativeSetCameraTargetHeight(rendererHandle, cameraTargetHeight)
        MmdNative.nativeSetModelPath(rendererHandle, requestedModelPath)
        MmdNative.nativeSetAnimationState(rendererHandle, requestedAnimationName, requestedAnimationLooping)
    }
}
```

---

## 6. 项目中的使用方式

### 6.1 MmdAvatarController — Avatar 控制器实现

[MmdAvatarController.kt](file:///home/meizu/Documents/my_agent_projects/Operit/app/src/main/java/com/ai/assistance/operit/core/avatar/impl/mmd/control/MmdAvatarController.kt)

```kotlin
class MmdAvatarController(
    private val model: MmdAvatarModel
) : AvatarController {

    private val _state = MutableStateFlow(AvatarState())
    override val state: StateFlow<AvatarState> = _state.asStateFlow()

    // 变换参数
    private val _scale = MutableStateFlow(1.0f)
    val scale: StateFlow<Float> = _scale.asStateFlow()
    private val _translateX = MutableStateFlow(0.0f)
    private val _translateY = MutableStateFlow(0.0f)
    private val _initialRotationX = MutableStateFlow(0.0f)
    private val _initialRotationY = MutableStateFlow(0.0f)
    private val _initialRotationZ = MutableStateFlow(0.0f)
    private val _cameraDistanceScale = MutableStateFlow(1.0f)
    private val _cameraTargetHeight = MutableStateFlow(0.0f)

    // 动画映射
    private var emotionAnimationMapping: Map<AvatarEmotion, String> = emptyMap()
    private var triggerAnimationMapping: Map<String, String> = emptyMap()

    override val availableAnimations: List<String>
        get() = model.displayMotionNames

    // 设置情绪 → 播放对应动画
    override fun setEmotion(newEmotion: AvatarEmotion) {
        playEmotion(newEmotion, loop = 0)
    }

    override fun playEmotion(emotion: AvatarEmotion, loop: Int) {
        _state.value = _state.value.copy(emotion = emotion)
        resolveAnimationForEmotion(emotion)?.let { animationName ->
            playAnimation(animationName, loop)
        }
    }

    // 播放触发器动画
    override fun playTrigger(triggerName: String, loop: Int): Boolean {
        val normalizedTrigger = AvatarMoodTypes.normalizeKey(triggerName)
        val animationName = resolveAnimationForTrigger(normalizedTrigger) ?: return false
        playAnimation(animationName, loop)
        return true
    }

    // 估算动画时长（基于 VMD 帧数，30fps）
    override fun estimateEmotionDurationMillis(emotion: AvatarEmotion): Long? {
        val animationName = resolveAnimationForEmotion(emotion) ?: return null
        val motionPath = File(model.basePath, animationName).absolutePath
        val maxFrame = MmdNative.nativeReadMotionMaxFrame(motionPath)
        if (maxFrame <= 0) return null
        return ((maxFrame / 30f) * 1000f).roundToLong().coerceAtLeast(1L)
    }

    // 播放动画
    override fun playAnimation(animationName: String, loop: Int) {
        if (!availableAnimations.contains(animationName)) return
        _state.value = _state.value.copy(
            currentAnimation = animationName,
            isLooping = loop == 0
        )
    }

    // 更新设置
    override fun updateSettings(settings: Map<String, Any>) {
        settings[AvatarSettingKeys.SCALE]?.let { if (it is Number) _scale.value = it.toFloat() }
        settings[AvatarSettingKeys.TRANSLATE_X]?.let { if (it is Number) _translateX.value = it.toFloat() }
        settings[AvatarSettingKeys.TRANSLATE_Y]?.let { if (it is Number) _translateY.value = it.toFloat() }
        settings[AvatarSettingKeys.MMD_INITIAL_ROTATION_X]?.let { if (it is Number) _initialRotationX.value = it.toFloat() }
        settings[AvatarSettingKeys.MMD_INITIAL_ROTATION_Y]?.let { if (it is Number) _initialRotationY.value = it.toFloat() }
        settings[AvatarSettingKeys.MMD_INITIAL_ROTATION_Z]?.let { if (it is Number) _initialRotationZ.value = it.toFloat() }
        settings[AvatarSettingKeys.MMD_CAMERA_DISTANCE_SCALE]?.let {
            if (it is Number) _cameraDistanceScale.value = it.toFloat().coerceIn(0.02f, 12.0f)
        }
        settings[AvatarSettingKeys.MMD_CAMERA_TARGET_HEIGHT]?.let {
            if (it is Number) _cameraTargetHeight.value = it.toFloat().coerceIn(-2.0f, 2.0f)
        }
    }

    // 动画解析
    private fun resolveAnimationForEmotion(emotion: AvatarEmotion): String? {
        val preferred = emotionAnimationMapping[emotion]
        if (!preferred.isNullOrBlank() && availableAnimations.contains(preferred)) {
            return preferred
        }
        val idleFallback = emotionAnimationMapping[AvatarEmotion.IDLE]
        if (!idleFallback.isNullOrBlank() && availableAnimations.contains(idleFallback)) {
            return idleFallback
        }
        return null
    }

    private fun resolveAnimationForTrigger(triggerName: String): String? {
        val preferred = triggerAnimationMapping[triggerName]
        if (!preferred.isNullOrBlank() && availableAnimations.contains(preferred)) {
            return preferred
        }
        return availableAnimations.firstOrNull { it.equals(triggerName, ignoreCase = true) }
    }
}
```

### 6.2 MmdRenderer — Compose 视图

[MmdRenderer.kt](file:///home/meizu/Documents/my_agent_projects/Operit/app/src/main/java/com/ai/assistance/operit/core/avatar/impl/mmd/view/MmdRenderer.kt)

```kotlin
@Composable
fun MmdRenderer(
    modifier: Modifier,
    model: MmdAvatarModel,
    controller: AvatarController,
    onError: (String) -> Unit
) {
    val mmdController = controller as? MmdAvatarController
        ?: throw IllegalArgumentException("MmdRenderer requires a MmdAvatarController")

    // 收集状态
    val scale by mmdController.scale.collectAsState()
    val translateX by mmdController.translateX.collectAsState()
    val translateY by mmdController.translateY.collectAsState()
    val initialRotationX by mmdController.initialRotationX.collectAsState()
    val initialRotationY by mmdController.initialRotationY.collectAsState()
    val initialRotationZ by mmdController.initialRotationZ.collectAsState()
    val cameraDistanceScale by mmdController.cameraDistanceScale.collectAsState()
    val cameraTargetHeight by mmdController.cameraTargetHeight.collectAsState()
    val avatarState by mmdController.state.collectAsState()

    val safeScale = scale.coerceIn(0.2f, 5.0f)

    // 生命周期管理
    val lifecycleOwner = LocalLifecycleOwner.current
    val surfaceViewState = remember { mutableStateOf<MmdGlSurfaceView?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> surfaceViewState.value?.onResume()
                Lifecycle.Event.ON_PAUSE -> surfaceViewState.value?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            surfaceViewState.value?.onPause()
        }
    }

    // 渲染视图
    Box(
        modifier = modifier
            .fillMaxSize()
            .scale(safeScale)
            .offset(x = translateX.dp, y = translateY.dp)
            .background(Color.Transparent)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                MmdGlSurfaceView(context).apply {
                    surfaceViewState.value = this
                    setOnRenderErrorListener { message ->
                        renderErrorState.value = message
                        onError(message)
                    }
                    setModelPath(model.modelPath)
                    setAnimationState(avatarState.currentAnimation, avatarState.isLooping)
                    setModelRotation(initialRotationX, initialRotationY, initialRotationZ)
                    setCameraDistanceScale(cameraDistanceScale)
                    setCameraTargetHeight(cameraTargetHeight)
                    onResume()
                }
            },
            update = { view ->
                surfaceViewState.value = view
                view.setModelPath(model.modelPath)
                view.setAnimationState(avatarState.currentAnimation, avatarState.isLooping)
                view.setModelRotation(initialRotationX, initialRotationY, initialRotationZ)
                view.setCameraDistanceScale(cameraDistanceScale)
                view.setCameraTargetHeight(cameraTargetHeight)
            }
        )

        // 错误提示
        renderErrorState.value?.let { error ->
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.92f)
            ) {
                Text(text = error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
```

---

## 7. 构建配置

### 7.1 mmd/CMakeLists.txt

[CMakeLists.txt](file:///home/meizu/Documents/my_agent_projects/Operit/mmd/CMakeLists.txt)

```cmake
cmake_minimum_required(VERSION 3.22.1)
project("mmd_jni")

set(SABA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/saba")
set(BULLET_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/bullet3")

# Bullet3 构建选项（禁用不需要的功能）
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(USE_GRAPHICAL_BENCHMARK OFF CACHE BOOL "" FORCE)
set(BUILD_CPU_DEMOS OFF CACHE BOOL "" FORCE)
set(BUILD_BULLET3 OFF CACHE BOOL "" FORCE)
set(BUILD_OPENGL3_DEMOS OFF CACHE BOOL "" FORCE)
set(BUILD_BULLET2_DEMOS OFF CACHE BOOL "" FORCE)
set(BUILD_EXTRAS OFF CACHE BOOL "" FORCE)
set(BUILD_UNIT_TESTS OFF CACHE BOOL "" FORCE)
set(INSTALL_LIBS OFF CACHE BOOL "" FORCE)

add_subdirectory("${OPERIT_BULLET_DIR}" bullet3-build)

add_library(
    MmdWrapper
    SHARED
    # Android 桥接
    src/main/cpp/android/AndroidAssetSupport.cpp
    src/main/cpp/android/MmdInspectorBridge.cpp
    src/main/cpp/android/MmdRendererBridge.cpp
    # GL 工具
    src/main/cpp/Saba/GL/GLShaderUtil.cpp
    src/main/cpp/Saba/GL/GLSLUtil.cpp
    src/main/cpp/Saba/GL/GLTextureUtil.cpp
    # MMD 渲染
    src/main/cpp/Saba/GL/Model/MMD/GLMMDModel.cpp
    src/main/cpp/Saba/GL/Model/MMD/GLMMDModelDrawContext.cpp
    src/main/cpp/Saba/GL/Model/MMD/GLMMDModelDrawer.cpp
    # Viewer
    src/main/cpp/Saba/Viewer/Camera.cpp
    src/main/cpp/Saba/Viewer/CameraOverrider.cpp
    src/main/cpp/Saba/Viewer/Grid.cpp
    src/main/cpp/Saba/Viewer/Light.cpp
    src/main/cpp/Saba/Viewer/ModelDrawer.cpp
    src/main/cpp/Saba/Viewer/ShadowMap.cpp
    src/main/cpp/Saba/Viewer/Viewer.cpp
    src/main/cpp/Saba/Viewer/ViewerCommand.cpp
    src/main/cpp/Saba/Viewer/ViewerContext.cpp
    src/main/cpp/Saba/Viewer/VMDCameraOverrider.cpp
    # Saba 核心（从 third_party 引入）
    ${OPERIT_SABA_DIR}/src/Saba/Base/File.cpp
    ${OPERIT_SABA_DIR}/src/Saba/Base/Log.cpp
    ${OPERIT_SABA_DIR}/src/Saba/Base/Path.cpp
    ${OPERIT_SABA_DIR}/src/Saba/Base/Singleton.cpp
    ${OPERIT_SABA_DIR}/src/Saba/Base/Time.cpp
    ${OPERIT_SABA_DIR}/src/Saba/Base/UnicodeUtil.cpp
    ${OPERIT_SABA_DIR}/src/Saba/Model/MMD/SjisToUnicode.cpp
    ${OPERIT_SABA_DIR}/src/Saba/Model/MMD/MMDIkSolver.cpp
    ${OPERIT_SABA_DIR}/src/Saba/Model/MMD/MMDMaterial.cpp
    ${OPERIT_SABA_DIR}/src/Saba/Model/MMD/MMDModel.cpp
    ${OPERIT_SABA_DIR}/src/Saba/Model/MMD/MMDMorph.cpp
    ${OPERIT_SABA_DIR}/src/Saba/Model/MMD/MMDNode.cpp
    ${OPERIT_SABA_DIR}/src/Saba/Model/MMD/MMDPhysics.cpp
    ${OPERIT_SABA_DIR}/src/Saba/Model/MMD/MMDCamera.cpp
    ${OPERIT_SABA_DIR}/src/Saba/Model/MMD/PMDFile.cpp
    ${OPERIT_SABA_DIR}/src/Saba/Model/MMD/PMDModel.cpp
    ${OPERIT_SABA_DIR}/src/Saba/Model/MMD/PMXFile.cpp
    ${OPERIT_SABA_DIR}/src/Saba/Model/MMD/PMXModel.cpp
    ${OPERIT_SABA_DIR}/src/Saba/Model/MMD/VMDAnimation.cpp
    ${OPERIT_SABA_DIR}/src/Saba/Model/MMD/VMDCameraAnimation.cpp
    ${OPERIT_SABA_DIR}/src/Saba/Model/MMD/VMDFile.cpp
    ${OPERIT_SABA_DIR}/src/Saba/Model/MMD/VPDFile.cpp
)

target_compile_definitions(MmdWrapper PRIVATE OPERIT_HAS_SABA=1)

target_include_directories(MmdWrapper PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp"
    "${OPERIT_SABA_DIR}/src"
    "${OPERIT_SABA_DIR}/external/glm/include"
    "${OPERIT_SABA_DIR}/external/spdlog/include"
    "${OPERIT_SABA_DIR}/external/stb/include"
    "${OPERIT_SABA_DIR}/external/tinyddsloader/include"
    "${OPERIT_BULLET_DIR}/src"
)

target_link_libraries(
    MmdWrapper
    android
    log
    GLESv3
    BulletDynamics
    BulletCollision
    LinearMath
)

# 16KB page size support (Android 15+)
target_link_options(MmdWrapper PRIVATE "-Wl,-z,max-page-size=16384")
```

### 7.2 mmd/build.gradle.kts

[build.gradle.kts](file:///home/meizu/Documents/my_agent_projects/Operit/mmd/build.gradle.kts)

```kotlin
android {
    namespace = "com.ai.assistance.mmd"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        targetSdk = 34
        ndk { abiFilters.addAll(listOf("arm64-v8a")) }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fno-emulated-tls")
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DANDROID_PLATFORM=android-26",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
                )
            }
        }
    }
}
```

### 7.3 关键构建选项

| 选项 | 值 | 说明 |
|------|-----|------|
| BUILD_SHARED_LIBS | OFF | 静态链接 Bullet3 |
| BUILD_BULLET3 | OFF | 禁用 Bullet3 高级功能 |
| BUILD_EXTRAS | OFF | 禁用 Bullet3 额外功能 |
| BUILD_UNIT_TESTS | OFF | 禁用测试 |
| OPERIT_HAS_SABA | 1 | 启用 Saba 引擎 |
| -fno-emulated-tls | 编译选项 | TLS 兼容性 |
| max-page-size=16384 | 链接选项 | Android 15+ 16KB 对齐 |

---

## 8. 使用方法

### 8.1 检测模型信息

```kotlin
import com.ai.assistance.mmd.MmdInspector
import com.ai.assistance.mmd.MmdModelFormat

// 检查模块是否可用
if (!MmdInspector.isAvailable()) {
    println("不可用: ${MmdInspector.unavailableReason()}")
    return
}

// 检测模型
val modelInfo = MmdInspector.inspectModel("/path/to/model.pmx")
modelInfo?.let {
    println("模型名称: ${it.modelName}")
    println("格式: ${it.format}") // PMD 或 PMX
    println("顶点数: ${it.vertexCount}")
    println("面数: ${it.faceCount}")
    println("材质数: ${it.materialCount}")
    println("骨骼数: ${it.boneCount}")
    println("变形数: ${it.morphCount}")
    println("刚体数: ${it.rigidBodyCount}")
    println("关节数: ${it.jointCount}")
}

// 检测动作
val motionInfo = MmdInspector.inspectMotion("/path/to/motion.vmd")
motionInfo?.let {
    println("动作模型名: ${it.modelName}")
    println("动作数: ${it.motionCount}")
    println("变形数: ${it.morphCount}")
    println("相机数: ${it.cameraCount}")
}

// 获取动作最大帧数
val maxFrame = MmdNative.nativeReadMotionMaxFrame("/path/to/motion.vmd")
val durationMs = ((maxFrame / 30f) * 1000f).toLong()
println("动画时长: ${durationMs}ms")
```

### 8.2 在 Compose 中使用 MmdGlSurfaceView

```kotlin
import com.ai.assistance.mmd.MmdGlSurfaceView
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun MmdViewer(
    modelPath: String,
    animationName: String? = null,
    isLooping: Boolean = false
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            MmdGlSurfaceView(context).apply {
                setModelPath(modelPath)
                setAnimationState(animationName, isLooping)
                setModelRotation(0f, 0f, 0f)
                setCameraDistanceScale(1.0f)
                setCameraTargetHeight(0.0f)
                setOnRenderErrorListener { error ->
                    Log.e("MmdViewer", "Render error: $error")
                }
            }
        },
        update = { view ->
            view.setModelPath(modelPath)
            view.setAnimationState(animationName, isLooping)
        }
    )
}
```

### 8.3 Avatar 系统集成

```kotlin
// 创建 Avatar 模型
val mmdModel = MmdAvatarModel(
    modelPath = "/path/to/model.pmx",
    basePath = "/path/to/motions/",
    displayMotionNames = listOf("idle.vmd", "happy.vmd", "sad.vmd")
)

// 创建控制器
val controller = rememberMmdAvatarController(mmdModel)

// 设置情绪动画映射
controller.updateEmotionAnimationMapping(mapOf(
    AvatarEmotion.IDLE to "idle.vmd",
    AvatarEmotion.HAPPY to "happy.vmd",
    AvatarEmotion.SAD to "sad.vmd"
))

// 设置触发器映射
controller.updateTriggerAnimationMapping(mapOf(
    "wave" to "wave.vmd",
    "jump" to "jump.vmd"
))

// 播放情绪
controller.setEmotion(AvatarEmotion.HAPPY)

// 播放触发器
controller.playTrigger("wave", loop = 1)

// 估算动画时长
val duration = controller.estimateEmotionDurationMillis(AvatarEmotion.HAPPY)

// 更新设置
controller.updateSettings(mapOf(
    AvatarSettingKeys.SCALE to 1.5f,
    AvatarSettingKeys.MMD_CAMERA_DISTANCE_SCALE to 2.0f,
    AvatarSettingKeys.MMD_INITIAL_ROTATION_Y to 45f
))

// Compose 渲染
MmdRenderer(
    modifier = Modifier.fillMaxSize(),
    model = mmdModel,
    controller = controller,
    onError = { error -> Log.e("Mmd", error) }
)
```

---

## 9. 文件索引

### Kotlin API 层

| 文件 | 路径 | 说明 |
|------|------|------|
| MmdLibraryLoader.kt | `mmd/src/main/java/com/ai/assistance/mmd/MmdLibraryLoader.kt` | 库加载器 |
| MmdNative.kt | `mmd/src/main/java/com/ai/assistance/mmd/MmdNative.kt` | JNI 接口对象 |
| MmdInspector.kt | `mmd/src/main/java/com/ai/assistance/mmd/MmdInspector.kt` | 模型/动作检测 |
| MmdGlSurfaceView.kt | `mmd/src/main/java/com/ai/assistance/mmd/MmdGlSurfaceView.kt` | OpenGL 渲染视图 |

### C++ JNI 桥接层

| 文件 | 路径 | 说明 |
|------|------|------|
| MmdRendererBridge.cpp | `mmd/src/main/cpp/android/MmdRendererBridge.cpp` | 渲染器 JNI |
| MmdInspectorBridge.cpp | `mmd/src/main/cpp/android/MmdInspectorBridge.cpp` | 检测 JNI |
| AndroidAssetSupport.cpp | `mmd/src/main/cpp/android/AndroidAssetSupport.cpp` | Assets 支持 |

### Saba 引擎核心

| 文件 | 路径 | 说明 |
|------|------|------|
| Viewer.h/.cpp | `mmd/src/main/cpp/Saba/Viewer/Viewer.cpp` | 渲染主控制器 |
| GLMMDModel.h/.cpp | `mmd/src/main/cpp/Saba/GL/Model/MMD/GLMMDModel.cpp` | OpenGL MMD 模型 |
| GLMMDModelDrawer.cpp | `mmd/src/main/cpp/Saba/GL/Model/MMD/GLMMDModelDrawer.cpp` | 模型绘制器 |
| Camera.cpp | `mmd/src/main/cpp/Saba/Viewer/Camera.cpp` | 相机控制 |
| Light.cpp | `mmd/src/main/cpp/Saba/Viewer/Light.cpp` | 灯光 |
| ShadowMap.cpp | `mmd/src/main/cpp/Saba/Viewer/ShadowMap.cpp` | 阴影贴图 |

### 业务封装层

| 文件 | 路径 | 说明 |
|------|------|------|
| MmdAvatarController.kt | `app/src/main/java/.../avatar/impl/mmd/control/MmdAvatarController.kt` | Avatar 控制器 |
| MmdRenderer.kt | `app/src/main/java/.../avatar/impl/mmd/view/MmdRenderer.kt` | Compose 视图 |

### 构建配置

| 文件 | 路径 | 说明 |
|------|------|------|
| CMakeLists.txt | `mmd/CMakeLists.txt` | CMake 构建配置 |
| build.gradle.kts | `mmd/build.gradle.kts` | Gradle 构建配置 |
