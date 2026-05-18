# fbx 模块软件设计架构与使用文档

## 目录

1. [模块概述](#1-模块概述)
2. [整体架构](#2-整体架构)
3. [ufbx 引擎核心架构](#3-ufbx-引擎核心架构)
4. [JNI 桥接层](#4-jni-桥接层)
5. [Kotlin API 层](#5-kotlin-api-层)
6. [项目中的使用方式](#6-项目中的使用方式)
7. [构建配置](#7-构建配置)
8. [使用方法](#8-使用方法)
9. [文件索引](#9-文件索引)

***

## 1. 模块概述

**fbx** 模块是 Operit 项目中用于 **FBX 3D 模型加载、动画预览与渲染** 的 Android 封装模块。它基于开源项目 [ufbx](https://github.com/ufbx/ufbx)（一个零依赖、单文件的 FBX 解析库）构建，支持在 Android 设备上加载 FBX 模型文件，进行实时动画预览和 OpenGL ES 2.0 渲染。

### 1.1 核心能力

| 能力               | 说明                                    |
| ---------------- | ------------------------------------- |
| FBX 模型加载         | 支持 .fbx 格式（二进制/ASCII）的 3D 模型解析        |
| 动画栈解析            | 提取 FBX 文件中的多个动画栈（AnimStack），获取名称和时长   |
| 骨骼动画评估           | 基于时间轴评估骨骼动画，支持蒙皮（Skinning）变形          |
| 材质与纹理            | 支持 PBR 和 FBX 传统材质，支持内嵌/外部纹理           |
| OpenGL ES 2.0 渲染 | 实时预览渲染，支持顶点法线、纹理映射、透明度混合              |
| 相机控制             | 支持轨道相机（Orbit Camera），可调整俯仰、偏航、距离、目标高度 |

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
│  │ FbxAvatarController                     ││
│  │ FbxRenderer (Compose)                   ││
│  │ FbxAvatarModel                          ││
│  └─────────────────────────────────────────┘│
├─────────────────────────────────────────────┤
│           fbx 模块 (Kotlin + JNI Wrapper)    │
│  ┌─────────────┐  ┌─────────────────────┐  │
│  │ FbxGlSurfaceView                       │  │
│  │ FbxNative   │  │ FbxInspector        │  │
│  │ FbxPreviewRenderer                     │  │
│  └─────────────┘  └─────────────────────┘  │
├─────────────────────────────────────────────┤
│           JNI 桥接层 (C++)                   │
│  ┌─────────────┐  ┌─────────────────────┐  │
│  │ fbx_jni.cpp                            │  │
│  │ - PreviewSession                       │  │
│  │ - LoadScene / BuildSessionGeometry     │  │
│  │ - UpdatePreviewFrame                   │  │
│  └─────────────┘  └─────────────────────┘  │
├─────────────────────────────────────────────┤
│           ufbx 引擎核心 (C)                  │
│  ┌─────────────┐  ┌─────────────────────┐  │
│  │ ufbx.c      │  │ ufbx.h              │  │
│  │ - ufbx_load_file                       │  │
│  │ - ufbx_evaluate_scene                  │  │
│  │ - ufbx_triangulate_face                │  │
│  └─────────────┘  └─────────────────────┘  │
└─────────────────────────────────────────────┘
```

***

## 2. 整体架构

### 2.1 分层架构

```
┌─────────────────────────────────────────────────────────────┐
│  应用层                                                      │
│  - Compose UI (FbxRenderer)                                │
│  - Avatar 系统 (FbxAvatarController)                        │
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
│  FbxAvatarController (业务封装)                              │
│  - 情绪到动画映射 (emotionAnimationMapping)                  │
│  - 触发器到动画映射 (triggerAnimationMapping)                │
│  - 动画时长估算 (基于 FBX 动画栈时长)                        │
│  - 相机姿态管理 (pitch/yaw/distance/targetHeight)            │
│  - 缩放/平移参数管理                                        │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  Kotlin API 层 (com.ai.assistance.fbx)                      │
│  ┌─────────────────┐  ┌─────────────────────────────────────┐│
│  │ FbxGlSurfaceView│  │ FbxInspector                        ││
│  │ - setModelPath()│  │ - isAvailable()                     ││
│  │ - setAnimation  │  │ - inspectModel()                    ││
│  │ - setCameraPose │  │                                     ││
│  └─────────────────┘  └─────────────────────────────────────┘│
│  ┌─────────────────────────────────────────────────────────┐│
│  │ FbxNative                                               ││
│  │ - nativeInspectModel()                                  ││
│  │ - nativeCreatePreviewSession() / nativeDestroyPreviewSession()││
│  │ - nativeReadPreviewInfo()                               ││
│  │ - nativeBuildPreviewFrame()                             ││
│  │ - nativeReadEmbeddedTextureBytes()                      ││
│  └─────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼ JNI
┌─────────────────────────────────────────────────────────────┐
│  C++ JNI 桥接层                                              │
│  ┌─────────────────────────────────────────────────────────┐│
│  │ fbx_jni.cpp                                             ││
│  │ - LoadScene (ufbx_load_file)                            ││
│  │ - BuildInspectSceneData                                 ││
│  │ - PreviewSession (scene + geometry + textures)          ││
│  │ - BuildSessionGeometry                                  ││
│  │ - UpdatePreviewFrame (ufbx_evaluate_scene)              ││
│  │ - BuildInspectJson / BuildPreviewInfoJson               ││
│  └─────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  ufbx 引擎核心 (C)                                         │
│  ┌─────────────────┐  ┌─────────────────────────────────────┐│
│  │ ufbx_load_file  │  │ ufbx_evaluate_scene                 ││
│  │ ufbx_free_scene │  │ ufbx_evaluate_anim                  ││
│  │ ufbx_triangulate_face│  │ ufbx_get_vertex_vec3          ││
│  └─────────────────┘  └─────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
```

***

## 3. ufbx 引擎核心架构

### 3.1 ufbx 简介

ufbx 是一个 **零依赖、单文件** 的 FBX 解析库（`ufbx.c` + `ufbx.h`），由 C 语言编写。它支持：

- FBX 二进制和 ASCII 格式
- 模型几何（网格、顶点、法线、UV）
- 骨骼和蒙皮（Skinning）
- 动画栈（AnimStack）和动画曲线
- 材质（PBR 和 FBX 传统材质）
- 纹理（内嵌和外部引用）
- 节点层级和变换

### 3.2 核心数据结构

```c
// ufbx_scene: 解析后的场景
typedef struct ufbx_scene {
    ufbx_string name;
    ufbx_node_list nodes;           // 所有节点
    ufbx_mesh_list meshes;          // 所有网格
    ufbx_material_list materials;   // 所有材质
    ufbx_anim_stack_list anim_stacks; // 动画栈列表
    ufbx_texture_file_list texture_files; // 纹理文件列表
} ufbx_scene;

// ufbx_anim_stack: 动画栈
typedef struct ufbx_anim_stack {
    ufbx_string name;
    double time_begin;              // 动画起始时间
    double time_end;                // 动画结束时间
    ufbx_anim *anim;                // 动画数据
} ufbx_anim_stack;

// ufbx_mesh: 网格
typedef struct ufbx_mesh {
    ufbx_face_list faces;           // 面列表
    ufbx_vertex_vec3 vertex_position; // 顶点位置
    ufbx_vertex_vec3 vertex_normal;   // 顶点法线
    ufbx_vertex_vec2 vertex_uv;       // 顶点 UV
    ufbx_vertex_vec3 skinned_position; // 蒙皮后位置
    ufbx_vertex_vec3 skinned_normal;   // 蒙皮后法线
    bool skinned_is_local;          // 蒙皮坐标系
    ufbx_mesh_part_list material_parts; // 材质分区
} ufbx_mesh;

// ufbx_material: 材质
typedef struct ufbx_material {
    ufbx_material_pbr pbr;          // PBR 属性
    ufbx_material_fbx fbx;          // FBX 传统属性
    ufbx_material_features features; // 特性开关
} ufbx_material;
```

### 3.3 解析流程

```
FBX 文件路径
    │
    ▼
ufbx_load_file(path, &opts, &error)
    │
    ▼
ufbx_scene
    ├─ nodes: 节点层级
    ├─ meshes: 网格数据
    ├─ materials: 材质属性
    ├─ anim_stacks: 动画栈
    └─ texture_files: 纹理文件
    │
    ▼
BuildInspectSceneData(scene)
    ├─ 提取动画名称和时长
    ├─ 解析外部纹理文件路径
    └─ 检测缺失的外部文件
    │
    ▼
BuildSessionGeometry(session)
    ├─ 遍历所有节点和网格
    ├─ 三角化面（ufbx_triangulate_face）
    ├─ 收集顶点引用（位置、法线、UV）
    ├─ 按材质分区（Segment）
    └─ 构建材质和纹理缓存
    │
    ▼
UpdatePreviewFrame(session, animation_name, time_seconds)
    ├─ 如有动画：ufbx_evaluate_scene(scene, anim, time)
    ├─ 获取蒙皮后顶点位置（skinned_position）
    ├─ 变换到世界坐标（geometry_to_world）
    ├─ 计算法线
    └─ 填充 FloatArray [x,y,z, nx,ny,nz, u,v]
```

### 3.4 顶点布局

每个顶点 8 个 float：

```
[0] position.x
[1] position.y
[2] position.z
[3] normal.x
[4] normal.y
[5] normal.z
[6] texCoord.u
[7] texCoord.v
```

***

## 4. JNI 桥接层

### 4.1 库加载

[FbxLibraryLoader.kt](file:///home/meizu/Documents/my_agent_projects/Operit/fbx/src/main/java/com/ai/assistance/fbx/FbxLibraryLoader.kt)

```kotlin
internal object FbxLibraryLoader {
    fun loadLibraries() {
        System.loadLibrary("FbxWrapper")
    }
}
```

### 4.2 fbx\_jni.cpp — 核心 JNI 实现

[fbx\_jni.cpp](file:///home/meizu/Documents/my_agent_projects/Operit/fbx/src/main/cpp/fbx_jni.cpp)

#### 4.2.1 错误处理

```cpp
std::mutex g_error_mutex;
std::string g_last_error;

void SetLastError(const std::string &message) {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    g_last_error = message;
}

std::string GetLastError() {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    return g_last_error;
}
```

#### 4.2.2 场景加载

```cpp
bool LoadScene(const std::string &model_path, ufbx_scene **out_scene) {
    ufbx_load_opts opts = {};
    opts.target_axes = ufbx_axes_right_handed_y_up;  // 右手 Y 朝上
    opts.target_unit_meters = 1.0f;                  // 目标单位：米
    opts.generate_missing_normals = true;            // 自动生成缺失法线
    opts.evaluate_skinning = true;                   // 评估蒙皮
    opts.evaluate_caches = true;                     // 评估缓存
    opts.load_external_files = true;                 // 加载外部文件
    opts.ignore_missing_external_files = true;       // 忽略缺失的外部文件

    ufbx_error error;
    ufbx_scene *scene = ufbx_load_file(model_path.c_str(), &opts, &error);
    if (!scene) {
        SetLastError(FormatUfbxError(error));
        return false;
    }
    *out_scene = scene;
    return true;
}
```

#### 4.2.3 模型检测

```cpp
JNIEXPORT jstring JNICALL
Java_com_ai_assistance_fbx_FbxNative_nativeInspectModel(JNIEnv *env, jobject, jstring path_model) {
    ufbx_scene *scene = nullptr;
    if (!LoadScene(model_path, &scene)) return nullptr;

    InspectSceneData data = BuildInspectSceneData(model_path, scene);
    ufbx_free_scene(scene);
    return NewJavaString(env, BuildInspectJson(data));
}
```

返回 JSON 格式：

```json
{
  "modelName": "ModelName",
  "animationNames": ["Idle", "Walk", "Run"],
  "animationDurationsMillis": [2000, 1500, 1000],
  "requiredExternalFiles": ["textures/diffuse.png"],
  "missingExternalFiles": []
}
```

#### 4.2.4 PreviewSession

```cpp
struct PreviewSession {
    std::string model_path;
    ufbx_scene *scene = nullptr;
    std::vector<std::string> animation_names;
    std::vector<int64_t> animation_durations_millis;
    std::unordered_map<std::string, size_t> animation_name_to_index;
    std::vector<TextureSlotData> textures;
    std::vector<MaterialData> materials;
    std::vector<SegmentData> segments;
    std::vector<VertexReference> vertex_references;
    std::vector<float> current_vertices;
    float center[3] = {0.0f, 0.0f, 0.0f};
    float radius = 1.0f;

    std::unordered_map<const ufbx_material *, int> material_cache;
    std::unordered_map<std::string, int> texture_cache;

    ~PreviewSession() {
        if (scene) { ufbx_free_scene(scene); scene = nullptr; }
    }
};
```

#### 4.2.5 几何构建

```cpp
bool BuildSessionGeometry(PreviewSession *session) {
    // 遍历所有节点和网格
    for (size_t node_index = 0; node_index < session->scene->nodes.count; ++node_index) {
        const ufbx_node *node = session->scene->nodes.data[node_index];
        if (!node || !node->mesh) continue;

        const ufbx_mesh *mesh = node->mesh;
        std::vector<uint32_t> triangulated_indices(mesh->max_face_triangles * 3);

        // 按材质分区处理
        for (const uint32_t part_index : ordered_part_indices) {
            const ufbx_material *material = ...;
            const int material_index = EnsureMaterial(session, material);

            // 三角化并收集顶点
            for (face in part) {
                size_t triangle_count = ufbx_triangulate_face(
                    triangulated_indices.data(), triangulated_indices.size(), mesh, face);
                for (triangle in triangles) {
                    session->vertex_references.push_back(VertexReference{
                        node->element_id,
                        mesh_vertex_index,
                        uv.x, uv.y
                    });
                }
            }
        }
    }
}
```

#### 4.2.6 帧更新

```cpp
bool UpdatePreviewFrame(PreviewSession *session, const std::string *animation_name, double time_seconds) {
    // 如有动画，评估场景
    ufbx_scene *evaluated_scene = nullptr;
    if (animation_name && !animation_name->empty()) {
        const ufbx_anim_stack *anim_stack = ...;
        evaluated_scene = ufbx_evaluate_scene(session->scene, anim_stack->anim, time_seconds, &opts, &error);
        active_scene = evaluated_scene;
    }

    // 遍历所有顶点引用，获取蒙皮后位置
    for (size_t index = 0; index < session->vertex_references.size(); ++index) {
        const VertexReference &reference = session->vertex_references[index];
        const ufbx_node *node = nodes_by_element_id[reference.node_element_id];
        const ufbx_mesh *mesh = node->mesh;

        ufbx_vec3 position = ufbx_get_vertex_vec3(&mesh->skinned_position, reference.mesh_vertex_index);
        ufbx_vec3 normal = ufbx_get_vertex_vec3(&mesh->skinned_normal, reference.mesh_vertex_index);

        if (mesh->skinned_is_local) {
            position = ufbx_transform_position(&node->geometry_to_world, position);
            normal = ufbx_transform_direction(&normal_matrix, normal);
        }

        // 填充顶点数据 [x,y,z, nx,ny,nz, u,v]
        session->current_vertices[base + 0] = position.x;
        session->current_vertices[base + 1] = position.y;
        session->current_vertices[base + 2] = position.z;
        session->current_vertices[base + 3] = normal.x;
        session->current_vertices[base + 4] = normal.y;
        session->current_vertices[base + 5] = normal.z;
        session->current_vertices[base + 6] = reference.u;
        session->current_vertices[base + 7] = reference.v;
    }

    // 计算包围盒中心和半径
    session->center[0] = (min_x + max_x) * 0.5f;
    session->center[1] = (min_y + max_y) * 0.5f;
    session->center[2] = (min_z + max_z) * 0.5f;
    session->radius = max(extent) * 0.5f;

    if (evaluated_scene) ufbx_free_scene(evaluated_scene);
    return true;
}
```

#### 4.2.7 材质处理

```cpp
int EnsureMaterial(PreviewSession *session, const ufbx_material *material) {
    MaterialData material_data;

    // PBR base_color 优先
    if (pbr_base_color.has_value && pbr_base_color.value_components >= 3) {
        material_data.base_color[0] = pbr_base_color.value_vec4.x;
        material_data.base_color[1] = pbr_base_color.value_vec4.y;
        material_data.base_color[2] = pbr_base_color.value_vec4.z;
        material_data.base_color[3] = pbr_base_color.value_vec4.w;
    }
    // 回退到 FBX diffuse_color
    else if (fbx_diffuse_color.has_value && fbx_diffuse_color.value_components >= 3) {
        material_data.base_color[0] = fbx_diffuse_color.value_vec4.x;
        ...
    }

    // 应用 base_factor
    if (pbr_base_factor.has_value) {
        material_data.base_color[0] *= pbr_base_factor.value_real;
        ...
    }

    // 透明度处理
    float opacity = material_data.base_color[3];
    if (pbr_opacity.has_value) opacity *= Clamp01(pbr_opacity.value_real);
    else if (fbx_transparency_factor.has_value) opacity *= 1.0f - Clamp01(fbx_transparency_factor.value_real);
    material_data.base_color[3] = Clamp01(opacity);

    // 纹理
    material_data.texture_index = EnsureTextureSlot(session, texture);

    // Alpha Blend 判断
    material_data.alpha_blend =
        material_data.base_color[3] < 0.999f ||
        pbr_opacity.texture_enabled ||
        material->features.opacity.enabled;

    return material_index;
}
```

#### 4.2.8 纹理处理

```cpp
int EnsureTextureSlot(PreviewSession *session, const ufbx_texture *texture) {
    // 提取内嵌字节
    std::vector<uint8_t> embedded_bytes;
    TryCopyEmbeddedBytes(file_texture->content, &embedded_bytes);
    if (embedded_bytes.empty() && file_texture->video) {
        TryCopyEmbeddedBytes(file_texture->video->content, &embedded_bytes);
    }

    // 解析外部文件路径
    ExternalFileInfo external = ResolveExternalFileInfo(
        session->model_path,
        StringFromUfbx(file_texture->filename),
        StringFromUfbx(file_texture->absolute_filename),
        StringFromUfbx(file_texture->relative_filename));

    // 缓存键
    std::string key;
    if (!embedded_bytes.empty()) key = "embedded:" + element_id;
    else if (!external.resolved_path.empty()) key = "path:" + external.resolved_path;
    else key = "texture:" + element_id;

    // 检查缓存
    auto existing = session->texture_cache.find(key);
    if (existing != session->texture_cache.end()) return existing->second;

    // 创建新纹理槽
    TextureSlotData slot;
    slot.label = external.display_path.empty() ? texture_name : external.display_path;
    slot.resolved_path = external.resolved_path;
    slot.embedded_bytes = std::move(embedded_bytes);

    int index = session->textures.size();
    session->textures.push_back(std::move(slot));
    session->texture_cache.emplace(key, index);
    return index;
}
```

***

## 5. Kotlin API 层

### 5.1 FbxNative — JNI 接口对象

[FbxNative.kt](file:///home/meizu/Documents/my_agent_projects/Operit/fbx/src/main/java/com/ai/assistance/fbx/FbxNative.kt)

```kotlin
object FbxNative {

    init { FbxLibraryLoader.loadLibraries() }

    @JvmStatic external fun nativeIsAvailable(): Boolean
    @JvmStatic external fun nativeGetUnavailableReason(): String
    @JvmStatic external fun nativeGetLastError(): String

    // 模型检测
    @JvmStatic external fun nativeInspectModel(pathModel: String): String?

    // 预览会话管理
    @JvmStatic external fun nativeCreatePreviewSession(pathModel: String): Long
    @JvmStatic external fun nativeDestroyPreviewSession(sessionHandle: Long)
    @JvmStatic external fun nativeReadPreviewInfo(sessionHandle: Long): String?

    // 帧构建
    @JvmStatic external fun nativeBuildPreviewFrame(
        sessionHandle: Long,
        animationName: String?,
        timeSeconds: Double
    ): FloatArray?

    // 内嵌纹理读取
    @JvmStatic external fun nativeReadEmbeddedTextureBytes(
        sessionHandle: Long,
        textureIndex: Int
    ): ByteArray?
}
```

### 5.2 FbxInspector — 模型检测

[FbxInspector.kt](file:///home/meizu/Documents/my_agent_projects/Operit/fbx/src/main/java/com/ai/assistance/fbx/FbxInspector.kt)

```kotlin
data class FbxModelInfo(
    val modelName: String,
    val animationNames: List<String>,
    val animationDurationMillisByName: Map<String, Long>,
    val requiredExternalFiles: List<String>,
    val missingExternalFiles: List<String>
) {
    val defaultAnimation: String?
        get() = animationNames.firstOrNull()
}

object FbxInspector {
    fun isAvailable(): Boolean = FbxNative.nativeIsAvailable()
    fun unavailableReason(): String = FbxNative.nativeGetUnavailableReason()
    fun getLastError(): String = FbxNative.nativeGetLastError()

    fun inspectModel(pathModel: String): FbxModelInfo? {
        val rawJson = FbxNative.nativeInspectModel(pathModel) ?: return null
        return runCatching {
            val root = JSONObject(rawJson)
            val animationNames = root.optJSONArray("animationNames").toStringList()
            val animationDurations = root.optJSONArray("animationDurationsMillis")
            val durationMap = buildMap {
                animationNames.forEachIndexed { index, animationName ->
                    val durationMillis = animationDurations?.optLong(index, 0L)?.coerceAtLeast(0L) ?: 0L
                    if (durationMillis > 0L) put(animationName, durationMillis)
                }
            }

            FbxModelInfo(
                modelName = root.optString("modelName").ifBlank {
                    pathPath.substringAfterLast('/').substringBeforeLast('.')
                },
                animationNames = animationNames,
                animationDurationMillisByName = durationMap,
                requiredExternalFiles = root.optJSONArray("requiredExternalFiles").toStringList(),
                missingExternalFiles = root.optJSONArray("missingExternalFiles").toStringList()
            )
        }.getOrNull()
    }
}
```

### 5.3 FbxGlSurfaceView — OpenGL 渲染视图

[FbxGlSurfaceView.kt](file:///home/meizu/Documents/my_agent_projects/Operit/fbx/src/main/java/com/ai/assistance/fbx/FbxGlSurfaceView.kt)

```kotlin
class FbxGlSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private val renderer = FbxPreviewRenderer(context.applicationContext)

    init {
        setEGLContextClientVersion(2)           // OpenGL ES 2.0
        setEGLConfigChooser(8, 8, 8, 8, 16, 0) // RGBA8, Depth16
        setZOrderOnTop(true)
        setBackgroundColor(Color.TRANSPARENT)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun setModelPath(path: String) {
        queueEvent { renderer.setModelPath(path) }
    }

    fun setAnimationState(animationName: String?, isLooping: Boolean, playbackNonce: Long) {
        queueEvent { renderer.setAnimationState(animationName, isLooping, playbackNonce) }
    }

    fun setCameraPose(pitchDegrees: Float, yawDegrees: Float, distanceScale: Float, targetHeightOffset: Float) {
        queueEvent { renderer.setCameraPose(pitchDegrees, yawDegrees, distanceScale, targetHeightOffset) }
    }

    fun setOnRenderErrorListener(listener: ((String) -> Unit)?) {
        renderer.setOnErrorListener(listener)
    }

    fun setOnAnimationsDiscoveredListener(listener: ((List<String>, Map<String, Long>) -> Unit)?) {
        renderer.setOnAnimationsDiscoveredListener(listener)
    }

    override fun onDetachedFromWindow() {
        queueEvent { renderer.release() }
        super.onDetachedFromWindow()
    }
}
```

### 5.4 FbxPreviewRenderer — GL 渲染器实现

`FbxPreviewRenderer` 是 `GLSurfaceView.Renderer` 的实现，负责：

- **Shader 编译**：顶点着色器 + 片段着色器
- **顶点缓冲管理**：`FloatBuffer`，每顶点 8 float（位置 3 + 法线 3 + UV 2）
- **纹理加载**：从文件或内嵌字节加载 `Bitmap` 并上传到 GPU
- **相机控制**：透视投影 + LookAt 轨道相机
- **动画播放**：基于时间计算当前帧，调用 `nativeBuildPreviewFrame`
- **透明度排序**：先绘制不透明物体，再绘制透明物体（关闭深度写入）

**Shader 代码**：

```glsl
// 顶点着色器
uniform mat4 uViewProjectionMatrix;
attribute vec3 aPosition;
attribute vec3 aNormal;
attribute vec2 aTexCoord;
varying vec3 vNormal;
varying vec2 vTexCoord;
void main() {
    vNormal = normalize(aNormal);
    vTexCoord = aTexCoord;
    gl_Position = uViewProjectionMatrix * vec4(aPosition, 1.0);
}

// 片段着色器
precision mediump float;
varying vec3 vNormal;
varying vec2 vTexCoord;
uniform vec4 uBaseColor;
uniform sampler2D uBaseTexture;
uniform float uUseBaseTexture;
void main() {
    vec3 lightDir = normalize(vec3(0.25, 0.80, 1.0));
    float diffuse = max(dot(normalize(vNormal), lightDir), 0.0);
    float lighting = 0.38 + diffuse * 0.62;
    vec4 textureColor = vec4(1.0);
    if (uUseBaseTexture > 0.5) {
        textureColor = texture2D(uBaseTexture, vec2(vTexCoord.x, 1.0 - vTexCoord.y));
    }
    vec4 color = vec4(uBaseColor.rgb * textureColor.rgb * lighting, uBaseColor.a * textureColor.a);
    gl_FragColor = vec4(clamp(color.rgb, 0.0, 1.0), clamp(color.a, 0.0, 1.0));
}
```

**相机计算**：

```kotlin
// 透视投影
Matrix.perspectiveM(projectionMatrix, 0, 45f, aspectRatio, radius/200f, radius*40f)

// 轨道相机位置
val pitchRadians = Math.toRadians(cameraPitchDegrees.toDouble())
val yawRadians = Math.toRadians(cameraYawDegrees.toDouble())
val distance = max(info.radius * 3.0f, 1.25f) * cameraDistanceScale
val eyeX = targetX + (distance * cos(pitchRadians) * sin(yawRadians)).toFloat()
val eyeY = targetY + (distance * sin(pitchRadians)).toFloat()
val eyeZ = targetZ + (distance * cos(pitchRadians) * cos(yawRadians)).toFloat()

Matrix.setLookAtM(viewMatrix, 0, eyeX, eyeY, eyeZ, targetX, targetY, targetZ, 0f, 1f, 0f)
Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
```

**动画时间计算**：

```kotlin
val animationName = requestedAnimationName?.takeIf { info.animationNames.contains(it) }
val durationSeconds = animationName?.let { info.animationDurationMillisByName[it] }?.let { it / 1000.0 }
val elapsedSeconds = (System.nanoTime() - animationStartNanos).toDouble() / 1_000_000_000.0
val sampleTimeSeconds = when {
    durationSeconds == null -> elapsedSeconds
    requestedLooping -> elapsedSeconds % durationSeconds
    else -> min(elapsedSeconds, durationSeconds)
}
```

***

## 6. 项目中的使用方式

### 6.1 FbxRenderer — Compose 视图

[FbxRenderer.kt](file:///home/meizu/Documents/my_agent_projects/Operit/app/src/main/java/com/ai/assistance/operit/core/avatar/impl/fbx/view/FbxRenderer.kt)

```kotlin
@Composable
fun FbxRenderer(
    modifier: Modifier,
    model: FbxAvatarModel,
    controller: AvatarController,
    onError: (String) -> Unit
) {
    val fbxController = controller as? FbxAvatarController
        ?: throw IllegalArgumentException("FbxRenderer requires a FbxAvatarController")

    val scale by fbxController.scale.collectAsState()
    val translateX by fbxController.translateX.collectAsState()
    val translateY by fbxController.translateY.collectAsState()
    val cameraPitch by fbxController.cameraPitch.collectAsState()
    val cameraYaw by fbxController.cameraYaw.collectAsState()
    val cameraDistanceScale by fbxController.cameraDistanceScale.collectAsState()
    val cameraTargetHeight by fbxController.cameraTargetHeight.collectAsState()
    val avatarState by fbxController.state.collectAsState()

    val safeScale = scale.coerceIn(0.2f, 5.0f)

    // 生命周期管理
    val lifecycleOwner = LocalLifecycleOwner.current
    val surfaceViewState = remember { mutableStateOf<FbxGlSurfaceView?>(null) }

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
                FbxGlSurfaceView(context).apply {
                    surfaceViewState.value = this
                    setOnRenderErrorListener { message ->
                        renderErrorState.value = message
                        onError(message)
                    }
                    setOnAnimationsDiscoveredListener { animationNames, durationMillisByName ->
                        fbxController.updateAnimationMetadata(animationNames, durationMillisByName)
                    }
                    setModelPath(model.modelPath)
                    setAnimationState(avatarState.currentAnimation, avatarState.isLooping, avatarState.playbackNonce)
                    setCameraPose(cameraPitch, cameraYaw, cameraDistanceScale, cameraTargetHeight)
                    onResume()
                }
            },
            update = { view ->
                surfaceViewState.value = view
                view.setModelPath(model.modelPath)
                view.setAnimationState(avatarState.currentAnimation, avatarState.isLooping, avatarState.playbackNonce)
                view.setCameraPose(cameraPitch, cameraYaw, cameraDistanceScale, cameraTargetHeight)
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

***

## 7. 构建配置

### 7.1 fbx/CMakeLists.txt

[CMakeLists.txt](file:///home/meizu/Documents/my_agent_projects/Operit/fbx/CMakeLists.txt)

```cmake
cmake_minimum_required(VERSION 3.22.1)
project("fbx_jni")

add_library(
    FbxWrapper
    SHARED
    src/main/cpp/fbx_jni.cpp
    third_party/ufbx/ufbx.c
)

set_target_properties(
    FbxWrapper
    PROPERTIES
    C_STANDARD 99
    C_STANDARD_REQUIRED ON
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
)

target_include_directories(
    FbxWrapper
    PRIVATE
    third_party/ufbx
)

target_link_libraries(
    FbxWrapper
    log
)

# 16KB page size support (Android 15+)
target_link_options(FbxWrapper PRIVATE "-Wl,-z,max-page-size=16384")
```

### 7.2 fbx/build.gradle.kts

[build.gradle.kts](file:///home/meizu/Documents/my_agent_projects/Operit/fbx/build.gradle.kts)

```kotlin
android {
    namespace = "com.ai.assistance.fbx"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        targetSdk = 34
        ndk { abiFilters.addAll(listOf("arm64-v8a")) }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17")
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

| 选项                  | 值    | 说明                  |
| ------------------- | ---- | ------------------- |
| C\_STANDARD         | 99   | ufbx 使用 C99         |
| CXX\_STANDARD       | 17   | JNI 桥接使用 C++17      |
| max-page-size=16384 | 链接选项 | Android 15+ 16KB 对齐 |
| ufbx.c              | 单文件库 | 零依赖 FBX 解析          |

***

## 8. 使用方法

### 8.1 检测模型信息

```kotlin
import com.ai.assistance.fbx.FbxInspector

// 检查模块是否可用
if (!FbxInspector.isAvailable()) {
    println("不可用: ${FbxInspector.unavailableReason()}")
    return
}

// 检测模型
val modelInfo = FbxInspector.inspectModel("/path/to/model.fbx")
modelInfo?.let {
    println("模型名称: ${it.modelName}")
    println("动画列表: ${it.animationNames}")
    it.animationDurationMillisByName.forEach { (name, duration) ->
        println("  $name: ${duration}ms")
    }
    println("需要的外部文件: ${it.requiredExternalFiles}")
    println("缺失的外部文件: ${it.missingExternalFiles}")
}
```

### 8.2 在 Compose 中使用 FbxGlSurfaceView

```kotlin
import com.ai.assistance.fbx.FbxGlSurfaceView
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun FbxViewer(
    modelPath: String,
    animationName: String? = null,
    isLooping: Boolean = false
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            FbxGlSurfaceView(context).apply {
                setModelPath(modelPath)
                setAnimationState(animationName, isLooping, playbackNonce = 0L)
                setCameraPose(pitchDegrees = 8f, yawDegrees = 0f, distanceScale = 1f, targetHeightOffset = 0f)
                setOnRenderErrorListener { error ->
                    Log.e("FbxViewer", "Render error: $error")
                }
                setOnAnimationsDiscoveredListener { animationNames, durations ->
                    Log.d("FbxViewer", "Discovered animations: $animationNames")
                }
            }
        },
        update = { view ->
            view.setModelPath(modelPath)
            view.setAnimationState(animationName, isLooping, playbackNonce = 0L)
        }
    )
}
```

### 8.3 Avatar 系统集成

```kotlin
// 创建 Avatar 模型
val fbxModel = FbxAvatarModel(
    modelPath = "/path/to/model.fbx",
    basePath = "/path/to/textures/",
    displayMotionNames = listOf("Idle", "Walk", "Run")
)

// 创建控制器
val controller = rememberFbxAvatarController(fbxModel)

// 设置情绪动画映射
controller.updateEmotionAnimationMapping(mapOf(
    AvatarEmotion.IDLE to "Idle",
    AvatarEmotion.HAPPY to "Happy",
    AvatarEmotion.SAD to "Sad"
))

// 播放情绪
controller.setEmotion(AvatarEmotion.HAPPY)

// 更新相机设置
controller.updateSettings(mapOf(
    AvatarSettingKeys.SCALE to 1.5f,
    AvatarSettingKeys.FBX_CAMERA_DISTANCE_SCALE to 2.0f,
    AvatarSettingKeys.FBX_CAMERA_PITCH to 15f,
    AvatarSettingKeys.FBX_CAMERA_YAW to 45f
))

// Compose 渲染
FbxRenderer(
    modifier = Modifier.fillMaxSize(),
    model = fbxModel,
    controller = controller,
    onError = { error -> Log.e("Fbx", error) }
)
```

***

## 9. 文件索引

### Kotlin API 层

| 文件                   | 路径                                                            | 说明                |
| -------------------- | ------------------------------------------------------------- | ----------------- |
| FbxLibraryLoader.kt  | `fbx/src/main/java/com/ai/assistance/fbx/FbxLibraryLoader.kt` | 库加载器              |
| FbxNative.kt         | `fbx/src/main/java/com/ai/assistance/fbx/FbxNative.kt`        | JNI 接口对象          |
| FbxInspector.kt      | `fbx/src/main/java/com/ai/assistance/fbx/FbxInspector.kt`     | 模型检测              |
| FbxGlSurfaceView\.kt | `fbx/src/main/java/com/ai/assistance/fbx/FbxGlSurfaceView.kt` | OpenGL 渲染视图 + 渲染器 |

### C++ JNI 桥接层

| 文件           | 路径                             | 说明        |
| ------------ | ------------------------------ | --------- |
| fbx\_jni.cpp | `fbx/src/main/cpp/fbx_jni.cpp` | 核心 JNI 实现 |

### ufbx 引擎核心

| 文件     | 路径                            | 说明          |
| ------ | ----------------------------- | ----------- |
| ufbx.c | `fbx/third_party/ufbx/ufbx.c` | ufbx 单文件库实现 |
| ufbx.h | `fbx/third_party/ufbx/ufbx.h` | ufbx 头文件    |

### 业务封装层

| 文件             | 路径                                                          | 说明         |
| -------------- | ----------------------------------------------------------- | ---------- |
| FbxRenderer.kt | `app/src/main/java/.../avatar/impl/fbx/view/FbxRenderer.kt` | Compose 视图 |

### 构建配置

| 文件               | 路径                     | 说明          |
| ---------------- | ---------------------- | ----------- |
| CMakeLists.txt   | `fbx/CMakeLists.txt`   | CMake 构建配置  |
| build.gradle.kts | `fbx/build.gradle.kts` | Gradle 构建配置 |

