# Skill 模块设计思想与详细使用指南

## 一、模块概述

`skill` 模块是 Operit AI 的**技能包管理系统**，负责发现、加载和管理基于 Markdown 的 Skill 包。Skill 是一种轻量级的 AI 能力扩展方式，开发者通过编写 `SKILL.md` 文件来定义 AI 的行为模式、专业知识和任务执行策略，无需编写 JavaScript 代码即可为 AI 注入新的专业能力。

### 1.1 核心定位

- **技能发现引擎**：扫描外部存储中的技能目录
- **技能加载器**：解析 SKILL.md 元数据和内容
- **系统提示词生成器**：将 Skill 内容转换为 AI 系统提示词
- **技能包管理器**：支持导入、删除和浏览技能包

### 1.2 模块结构

```
skill/
├── SkillManager.kt    # 核心管理器：技能生命周期管理
└── SkillPackage.kt    # 数据模型：技能包定义
```

### 1.3 与传统包的区别

| 特性 | Skill 包 | 传统 JS 包 | ToolPkg 容器 |
|------|---------|-----------|-------------|
| **格式** | Markdown (SKILL.md) | JavaScript/HJSON | ZIP 归档 (.toolpkg) |
| **复杂度** | 低（纯文本） | 中（代码+元数据） | 高（多文件+资源） |
| **执行方式** | 注入系统提示词 | JavaScript 执行 | JavaScript 执行 |
| **工具定义** | 无（依赖现有工具） | 可定义新工具 | 可定义新工具 |
| **适用场景** | 行为模式、专业知识 | 自定义工具逻辑 | 完整应用扩展 |

---

## 二、核心设计思想

### 2.1 目录驱动发现模型

Skill 模块采用**目录驱动**的发现模型，所有技能包统一存放在固定目录结构中：

```
/sdcard/Download/Operit/skills/          # 技能根目录
├── my-skill/                            # 技能包目录
│   ├── SKILL.md                         # 技能定义文件（必需）
│   ├── README.md                        # 补充说明（可选）
│   └── examples/                        # 示例文件（可选）
│       └── sample.txt
└── another-skill/
    ├── skill.md                         # 小写文件名也支持
    └── ...
```

**设计要点**：
- **固定路径**：`Download/Operit/skills/`，便于用户手动管理
- **目录即包**：每个子目录代表一个独立的技能包
- **SKILL.md 为入口**：通过解析 SKILL.md 获取技能元数据和内容
- **大小写不敏感**：支持 `SKILL.md` 和 `skill.md`

### 2.2 元数据解析机制

SkillManager 支持两种元数据格式：

#### Frontmatter 格式（推荐）

```markdown
---
name: "Python Expert"
description: "A skill for Python code review and optimization"
---

# Python Expert

You are an expert Python developer with deep knowledge of...
```

#### 头部键值对格式（兼容）

```markdown
name: "Python Expert"
description: "A skill for Python code review and optimization"

# Python Expert

You are an expert Python developer with deep knowledge of...
```

**解析逻辑**：
1. 首先检查是否有 YAML Frontmatter（`---` 包围）
2. 如果没有，扫描前 40 行查找 `name:` 和 `description:` 键
3. 支持引号包裹的值（自动去除引号）

### 2.3 系统提示词生成

Skill 的核心价值在于将 SKILL.md 内容转换为 AI 系统提示词：

```kotlin
fun getSkillSystemPrompt(skillName: String): String? {
    val content = skill.skillFile.readText()
    
    return buildString {
        appendLine("Using package (Skill): ${skill.name}")
        appendLine("Use Time: ${java.time.LocalDateTime.now()}")
        appendLine("Execution policy:")
        appendLine("Prioritize using the skill-provided instructions and bundled scripts, and complete tasks with terminal-related tools.")
        if (skill.description.isNotBlank()) {
            appendLine("Description: ${skill.description}")
        }
        appendLine("SKILL.md path: ${skill.skillFile.absolutePath}")
        appendLine("Skill directory: ${skill.directory.absolutePath}")
        appendLine("Directory structure:")
        appendLine(buildDirectoryTreeText(skill.directory))
        appendLine()
        appendLine("SKILL.md:")
        appendLine(content)
    }
}
```

**生成的提示词结构**：

```
Using package (Skill): Python Expert
Use Time: 2024-01-15T10:30:00
Execution policy:
Prioritize using the skill-provided instructions and bundled scripts, and complete tasks with terminal-related tools.
Description: A skill for Python code review and optimization
SKILL.md path: /sdcard/Download/Operit/skills/python-expert/SKILL.md
Skill directory: /sdcard/Download/Operit/skills/python-expert
Directory structure:
- SKILL.md
- README.md
- examples/
  - sample.py
  - output.txt

SKILL.md:
---
name: "Python Expert"
description: "A skill for Python code review and optimization"
---

You are an expert Python developer...
```

### 2.4 轻量级架构设计

Skill 模块 intentionally 保持极简设计：

```
┌─────────────────────────────────────┐
│           SkillManager              │
│  ┌─────────────────────────────┐    │
│  │    availableSkills          │    │
│  │    Map<String, SkillPackage>│    │
│  └─────────────────────────────┘    │
│  ┌─────────────────────────────┐    │
│  │    skillLoadErrors          │    │
│  │    Map<String, String>      │    │
│  └─────────────────────────────┘    │
└─────────────────────────────────────┘
              │
              ▼
┌─────────────────────────────────────┐
│           SkillPackage              │
│  - name: String                     │
│  - description: String              │
│  - directory: File                  │
│  - skillFile: File                  │
└─────────────────────────────────────┘
```

**设计原则**：
- **无状态**：每次调用 `refreshAvailableSkills()` 重新扫描
- **无持久化**：不依赖 SharedPreferences，完全基于文件系统
- **无执行引擎**：不编译或执行代码，纯文本注入
- **单例模式**：`SkillManager.getInstance(context)` 获取唯一实例

---

## 三、详细使用方法

### 3.1 基础操作

#### 获取 SkillManager 实例

```kotlin
val skillManager = SkillManager.getInstance(context)
```

#### 获取可用技能列表

```kotlin
// 获取所有可用技能（自动刷新）
val skills = skillManager.getAvailableSkills()
skills.forEach { (name, skill) ->
    println("Skill: $name, Description: ${skill.description}")
}

// 获取技能和错误信息
val (skills, errors) = skillManager.getAvailableSkillsSnapshot()
errors.forEach { (dirName, error) ->
    println("Error loading $dirName: $error")
}
```

#### 读取技能内容

```kotlin
// 读取 SKILL.md 完整内容
val content = skillManager.readSkillContent("Python Expert")
println(content)
```

#### 获取技能系统提示词

```kotlin
// 生成用于 AI 的系统提示词
val systemPrompt = skillManager.getSkillSystemPrompt("Python Expert")
// 返回完整的提示词文本，包含目录结构和 SKILL.md 内容
```

### 3.2 技能导入

#### 从 ZIP 导入

```kotlin
// 导入 ZIP 文件中的技能
val result = skillManager.importSkillFromZip(File("/sdcard/Download/python-expert.zip"))
// 返回: "Skill imported: python-expert" 或错误信息

// 从 ZIP 中的子目录导入
val result = skillManager.importSkillFromZip(
    zipFile = File("/sdcard/Download/bundle.zip"),
    subDirPathInZip = "skills/python-expert"
)
```

**ZIP 导入逻辑**：
1. 解压 ZIP 到临时目录
2. 查找 SKILL.md / skill.md（支持子目录指定）
3. 解析元数据获取技能名称
4. 复制到 `Download/Operit/skills/<name>/`
5. 刷新技能缓存

#### 手动放置

用户也可以手动创建技能目录：

```bash
mkdir -p /sdcard/Download/Operit/skills/my-skill
cat > /sdcard/Download/Operit/skills/my-skill/SKILL.md << 'EOF'
---
name: "My Skill"
description: "A custom skill for specific tasks"
---

# My Skill

You are a helpful assistant specialized in...
EOF
```

### 3.3 技能删除

```kotlin
// 删除技能（递归删除技能目录）
val deleted = skillManager.deleteSkill("Python Expert")
if (deleted) {
    println("Skill deleted successfully")
} else {
    println("Failed to delete skill")
}
```

### 3.4 获取技能目录路径

```kotlin
// 获取技能根目录路径（用于 UI 展示）
val path = skillManager.getSkillsDirectoryPath()
// 返回: "/sdcard/Download/Operit/skills"
```

---

## 四、SKILL.md 编写规范

### 4.1 基本结构

```markdown
---
name: "Skill Name"
description: "Brief description of what this skill does"
---

# Skill Name

## Role

You are a [role description]...

## Capabilities

- Capability 1
- Capability 2
- Capability 3

## Workflow

1. Step one
2. Step two
3. Step three

## Constraints

- Constraint 1
- Constraint 2

## Examples

### Example 1

Input: ...
Output: ...
```

### 4.2 元数据字段

| 字段 | 必需 | 说明 |
|------|------|------|
| `name` | 是 | 技能名称，用于标识和调用 |
| `description` | 否 | 技能描述，显示在技能列表中 |

### 4.3 最佳实践

1. **使用 Frontmatter**：推荐 YAML Frontmatter 格式，结构清晰
2. **命名规范**：技能名称应简洁明了，避免特殊字符
3. **目录组织**：相关文件放在技能目录下，便于打包分享
4. **示例丰富**：提供具体的输入输出示例，帮助 AI 理解预期行为
5. **约束明确**：明确说明技能的能力和限制边界

### 4.4 示例 SKILL.md

```markdown
---
name: "Code Reviewer"
description: "A skill for reviewing code and providing improvement suggestions"
---

# Code Reviewer

## Role

You are an expert code reviewer with deep knowledge of software engineering best practices, design patterns, and code quality standards.

## Capabilities

- Review code for bugs, security issues, and performance problems
- Suggest refactoring opportunities and design improvements
- Check code style compliance and naming conventions
- Identify potential edge cases and error handling gaps

## Review Workflow

1. **Understand Context**: Read the code and understand its purpose
2. **Check Correctness**: Verify logic correctness and potential bugs
3. **Evaluate Quality**: Assess code structure, readability, and maintainability
4. **Security Scan**: Identify security vulnerabilities
5. **Performance Check**: Point out performance bottlenecks
6. **Provide Suggestions**: Give concrete improvement suggestions with code examples

## Output Format

For each issue found, provide:
- **Severity**: Critical / Warning / Suggestion
- **Location**: File and line number
- **Description**: Clear explanation of the issue
- **Suggestion**: Concrete code example showing the fix

## Constraints

- Focus on substantive issues, not trivial style preferences
- Always provide constructive feedback with examples
- Consider the trade-offs of each suggestion
- Respect the existing codebase conventions
```

---

## 五、错误处理

### 5.1 常见错误类型

| 错误场景 | 错误信息 | 解决方案 |
|---------|---------|---------|
| 技能目录不存在 | `Cannot access skills directory: ...` | 创建 `Download/Operit/skills/` 目录 |
| 缺少 SKILL.md | `Missing SKILL.md in ...` | 在技能目录中添加 SKILL.md 文件 |
| 重复技能名 | `Duplicate skill name: ...` | 修改技能名称或合并技能 |
| ZIP 导入失败 | `Failed to import skill: ...` | 检查 ZIP 文件完整性和格式 |
| 路径遍历 | `Invalid path` | 确保子目录路径在 ZIP 内 |

### 5.2 获取加载错误

```kotlin
val errors = skillManager.getSkillLoadErrors()
errors.forEach { (name, error) ->
    println("Failed to load skill '$name': $error")
}
```

---

## 六、与 PackageManager 的集成

Skill 模块与 PackageManager 紧密集成：

```kotlin
// 在 PackageManager.usePackage() 中
fun usePackage(packageName: String): String {
    // ...
    
    // 检查是否是 Skill
    if (skillManager.getAvailableSkills().containsKey(normalizedPackageName) &&
        !skillVisibilityPreferences.isSkillVisibleToAi(normalizedPackageName)
    ) {
        return "Skill '$normalizedPackageName' is set to not show to AI"
    }
    
    val skillPrompt = skillManager.getSkillSystemPrompt(normalizedPackageName)
    if (skillPrompt != null) {
        return skillPrompt
    }
    
    // ...
}
```

**集成点**：
- `usePackage()` 方法优先检查 Skill，然后检查传统包和 MCP 服务器
- `SkillVisibilityPreferences` 控制 Skill 是否对 AI 可见
- Skill 生成的系统提示词与包的系统提示词格式一致

---

## 七、最佳实践

### 7.1 技能开发建议

1. **专注单一职责**：每个技能应聚焦一个专业领域
2. **明确输入输出**：定义清晰的输入格式和预期输出
3. **提供上下文**：在 SKILL.md 中提供足够的背景信息
4. **使用示例驱动**：通过示例说明技能的使用方式
5. **版本控制**：使用 Git 管理技能，便于追踪变更

### 7.2 技能组织建议

```
skills/
├── coding/                          # 编程相关技能
│   ├── python-expert/
│   ├── java-reviewer/
│   └── frontend-architect/
├── writing/                         # 写作相关技能
│   ├── technical-writer/
│   └── copy-editor/
└── analysis/                        # 分析相关技能
    ├── data-analyst/
    └── security-auditor/
```

### 7.3 性能考虑

1. **文件大小**：SKILL.md 不宜过大（建议 < 100KB），避免提示词过长
2. **目录深度**：保持扁平结构，避免过深的目录嵌套
3. **刷新频率**：`getAvailableSkills()` 每次都会刷新，UI 层应适当缓存

### 7.4 分享与分发

1. **ZIP 打包**：将整个技能目录打包为 ZIP 便于分享
2. **Git 仓库**：将技能放在 Git 仓库中，便于版本管理和协作
3. **社区共享**：建立技能市场或仓库，促进技能共享

---

## 八、总结

Skill 模块是 Operit AI 的轻量级能力扩展系统，通过简单的 Markdown 文件即可为 AI 注入专业能力。其设计特点包括：

1. **极简设计**：仅 2 个文件（SkillManager + SkillPackage），无复杂依赖
2. **零代码**：纯文本定义，无需编程知识即可创建技能
3. **目录驱动**：基于文件系统的自然组织方式
4. **提示词注入**：将 SKILL.md 内容直接注入 AI 系统提示词
5. **与包系统无缝集成**：通过 `usePackage()` 统一调用

通过本模块，非技术用户也能轻松创建和分享 AI 技能，极大地扩展了 Operit AI 的应用场景和专业能力。
