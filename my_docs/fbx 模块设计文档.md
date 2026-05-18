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

---

## 1. 模块概述

**fbx** 模块是 Operit 项目中用于 **FBX 3D 模型加载、动画预览与渲染** 的 Android 封装模块。它基于开源项目 [ufbx](https://github.com/ufbx/ufbx)（一个零依赖、单文件的 FBX 解析库）构建，支持在 Android 设备上加载 FBX 模型文件，进行实时动画预览和 OpenGL ES 2.0 渲染。

### 1.1 核心能力

| 能力 | 说明 |
|------|------|
| FBX 模型加载 | 支持 .fbx 格式（二进制/ASCII）的 3D 模型解析 |
| 动画栈解析 | 提取 FBX 文件中的多个动画栈（AnimStack），获取名称和时长 |
| 骨骼动画评估 | 基于时间轴评估骨骼动画，支持蒙皮（Skinning）变形 |
| 材质与纹理 | 支持 PBR 和 FBX 传统材质，支持内嵌/外部纹理 |
| OpenGL ES 2.0 渲染 | 实时预览渲染，支持顶点法线、纹理映射、透明度混合 |
| 相机控制 | 支持轨道相机（Orbit Camera），可调整俯仰、偏航、距离、目标高度 |

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

---

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

---

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

---

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

### 4.2 fbx_jni.cpp — 核心 JNI 实现

[fbx_jni.cpp](file:///home/meizu/Documents/my_agent_projects/Operit/fbx/src/main/cpp/fbx_jni.cpp)

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
    float