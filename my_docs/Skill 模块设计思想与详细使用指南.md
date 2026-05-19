# Skill 模块设计思想与详细使用指南

## 1. 模块概述

`skill` 模块是 Operit AI 的**技能（Skill）插件系统**，允许用户通过 Markdown 文件（`SKILL.md`）定义可复用的 AI 指令集、工作流脚本和知识库。Skill 可以被 AI 动态加载，扩展 AI 的能力边界，实现从简单提示词模板到复杂自动化任务的 anything-in-markdown 插件生态。

模块核心路径：
- 数据层：`com.ai.assistance.operit.data.skill`
- 核心逻辑：`com.ai.assistance.operit.core.tools.skill`
- 配置层：`com.ai.assistance.operit.data.preferences`
- 市场层：`com.ai.assistance.operit.ui.features.packages`

---

## 2. 核心设计思想

### 2.1 Markdown-First 插件格式

Skill 采用纯 Markdown 作为插件格式，降低创作门槛：

```markdown
---
name: "代码审查助手"
description: "帮助审查代码变更，提供改进建议"
---

# 代码审查助手

## 角色设定
你是一位经验丰富的代码审查专家...

## 审查清单
- [ ] 代码风格一致性
- [ ] 潜在的空指针风险
- [ ] 性能优化建议
- [ ] 安全漏洞检查

## 输出格式
```
## 审查结果

**文件**: {filename}
**风险等级**: {low|medium|high}
**建议**: {suggestion}
```
```

### 2.2 文件系统即数据库

Skill 不依赖数据库存储内容，而是直接扫描文件系统：

```
/sdcard/Download/Operit/skills/
├── code-reviewer/
│   ├── SKILL.md          # 主技能文件
│   ├── assets/
│   │   └── template.json # 附加资源
│   └── scripts/
│       └── analyze.sh    # 辅助脚本
├── git-helper/
│   └── SKILL.md
└── ...
```

### 2.3 三层架构（Manager → Repository → ViewModel）

| 层级 | 职责 | 核心类 |
|------|------|--------|
| **核心层** | 文件扫描、解析、系统提示词生成 | `SkillManager` |
| **数据层** | 导入/导出、GitHub 集成、ZIP 处理 | `SkillRepository` |
| **UI 层** | 市场浏览、发布、安装、配置 | `SkillMarketViewModel` |

### 2.4 可见性控制

每个 Skill 可以独立设置是否对 AI 可见，通过 `SkillVisibilityPreferences` 持久化：

```kotlin
// 默认所有 Skill 对 AI 可见
val isVisible = skillVisibilityPreferences.isSkillVisibleToAi("code-reviewer")

// 关闭特定 Skill
skillVisibilityPreferences.setSkillVisibleToAi("code-reviewer", false)
```

---

## 3. 核心类详解

### 3.1 SkillPackage — 技能包数据模型

```kotlin
data class SkillPackage(
    val name: String,           // 技能名称（来自 Frontmatter 或目录名）
    val description: String,    // 技能描述
    val directory: File,        // 技能根目录
    val skillFile: File         // SKILL.md 文件路径
)
```

### 3.2 SkillManager — 技能管理器

`SkillManager` 是单例类，负责 Skill 的**本地生命周期管理**：

#### 核心职责

| 方法 | 职责 |
|------|------|
| `refreshAvailableSkills()` | 扫描 skills 目录，解析所有 SKILL.md |
| `getAvailableSkills()` | 获取所有可用技能（自动刷新） |
| `readSkillContent(name)` | 读取指定技能的 SKILL.md 内容 |
| `deleteSkill(name)` | 删除技能（递归删除目录） |
| `getSkillSystemPrompt(name)` | 生成供 AI 使用的系统提示词 |
| `importSkillFromZip(zip, subDir?)` | 从 ZIP 导入技能 |

#### Frontmatter 解析

```kotlin
private fun parseSkillMetadata(skillFile: File): Pair<String, String> {
    // 支持 YAML Frontmatter 格式
    // ---
    // name: "技能名称"
    // description: "技能描述"
    // ---

    // 回退：在文件前 40 行搜索 name:/description:
}
```

#### 系统提示词生成

`getSkillSystemPrompt()` 将 Skill 转换为 AI 可理解的系统提示词：

```
Using package (Skill): 代码审查助手
Use Time: 2024-01-15T10:30:00
Execution policy:
Prioritize using the skill-provided instructions and bundled scripts, and complete tasks with terminal-related tools.
Description: 帮助审查代码变更，提供改进建议
SKILL.md path: /sdcard/Download/Operit/skills/code-reviewer/SKILL.md
Skill directory: /sdcard/Download/Operit/skills/code-reviewer
Directory structure:
- SKILL.md
- assets/
  - template.json
- scripts/
  - analyze.sh

SKILL.md:
[完整 SKILL.md 内容]
```

### 3.3 SkillRepository — 技能仓库

`SkillRepository` 封装了**所有外部导入渠道**，是 Skill 的入口网关：

#### 导入渠道

| 方法 | 来源 | 场景 |
|------|------|------|
| `importSkillFromZip(zip)` | 本地 ZIP 文件 | 用户手动导入 |
| `importSkillFromGitHubRepo(url)` | GitHub 仓库 | 从市场或链接安装 |
| `importSkillFromDirectInput(...)` | 直接输入 | 快速创建简单 Skill |

#### GitHub 导入流程

```kotlin
suspend fun importSkillFromGitHubRepo(repoUrl: String): String {
    // 1. 解析 GitHub URL
    //    支持: github.com/owner/repo
    //          github.com/owner/repo/tree/branch/subdir
    //          raw.githubusercontent.com/owner/repo/branch/path

    // 2. 获取默认分支（如果未指定）
    val ref = getGithubDefaultBranch(owner, repoName) ?: "main"

    // 3. 下载 ZIP（带缓存池复用）
    val zipUrl = "https://codeload.github.com/$owner/$repoName/zip/$ref"
    val zipFile = SkillRepoZipPoolManager.getOrDownloadZip(key) { downloadTo -> ... }

    // 4. 解压并导入
    val result = skillManager.importSkillFromZip(zipFile, subDir)

    // 5. 写入 .operit_repo_url 标记文件（用于检测已安装状态）
    File(skillDir, ".operit_repo_url").writeText(repoUrl)
}
```

#### 直接输入导入

```kotlin
suspend fun importSkillFromDirectInput(
    skillId: String,           // 技能标识（如 "my-helper"）
    description: String,       // 技能描述
    content: String,           // SKILL.md 内容
    attachmentUris: List<Uri> = emptyList()  // 附件文件
): String
```

自动生成的 SKILL.md 格式：

```markdown
---
name: "my-helper"
description: "我的自定义助手"
---

[用户输入的 content]
```

### 3.4 SkillVisibilityPreferences — 可见性偏好

使用 SHA-256 哈希作为键，避免特殊字符问题：

```kotlin
class SkillVisibilityPreferences private constructor(context: Context) {
    fun isSkillVisibleToAi(skillName: String): Boolean
    fun setSkillVisibleToAi(skillName: String, visible: Boolean)
}
```

键生成逻辑：

```kotlin
private fun keyForSkillName(skillName: String): String {
    val hash = SHA256(skillName.trim().toByteArray())
    return "skill_visible_${hex.take(16)}"
}
```

### 3.5 SkillRepoZipPoolManager — ZIP 缓存池

用于缓存从 GitHub 下载的 ZIP 文件，避免重复下载：

```kotlin
object SkillRepoZipPoolManager {
    var maxPoolSize = 6  // 最大缓存数量

    suspend fun getOrDownloadZip(
        key: String,                           // 缓存键（如 "owner/repo@branch"）
        downloadTo: suspend (File) -> Boolean  // 下载逻辑
    ): File?
}
```

**淘汰策略**：LRU（按最后修改时间排序，超出限制时删除最旧的）。

---

## 4. Skill 市场系统

### 4.1 市场架构

Skill 市场基于 **GitHub Issues** 构建，使用 `OperitSkillMarket` 仓库作为中心市场：

```kotlin
private val MARKET_DEFINITION = GitHubIssueMarketDefinition(
    owner = "AAswordman",
    repo = "OperitSkillMarket",
    label = "skill-plugin",
    pageSize = 50
)
```

### 4.2 SkillMarketViewModel — 市场视图模型

| 功能 | 说明 |
|------|------|
| `loadSkillMarketData()` | 加载市场列表（带排序、分页） |
| `searchSkillMarketIssues(query)` | 搜索市场内容 |
| `installSkill(item)` | 安装 Skill |
| `publishSkill(...)` | 发布 Skill 到市场 |
| `updatePublishedSkill(...)` | 更新已发布的 Skill |
| `removeSkillFromMarket(issueNumber)` | 下架 Skill |
| `loadUserPublishedSkills()` | 加载用户已发布的 Skill |

### 4.3 发布格式

发布的 Issue Body 包含嵌入式 JSON 元数据：

```markdown
<!-- operit-skill-json: {"description":"...","repositoryUrl":"...","version":"v1"} -->
<!-- operit-parser-version: v1 -->

## Skill 信息

描述内容...

## 仓库信息

- 仓库地址: https://github.com/owner/repo

## 安装方法

1. 复制仓库地址
2. 在 Operit 中粘贴安装
3. 完成

## 技术信息

| 项目 | 内容 |
|------|------|
| 平台 | Operit AI |
| 解析版本 | v1 |
| 发布时间 | 2024-01-15 10:30:00 |
```

### 4.4 SkillIssueParser — Issue 解析器

```kotlin
object SkillIssueParser {
    data class ParsedSkillInfo(
        val title: String,
        val description: String,
        val repositoryUrl: String = "",
        val category: String = "",
        val tags: String = "",
        val version: String = "",
        val repositoryOwner: String = ""
    )

    fun parseSkillInfo(issue: GitHubIssue): ParsedSkillInfo
}
```

---

## 5. 使用指南

### 5.1 创建本地 Skill

**方式一：手动创建文件**

```bash
mkdir -p /sdcard/Download/Operit/skills/my-skill
cat > /sdcard/Download/Operit/skills/my-skill/SKILL.md << 'EOF'
---
name: "我的助手"
description: "一个示例 Skill"
---

# 我的助手

当用户询问天气时，调用 weather_search 工具获取实时天气数据。
EOF
```

**方式二：通过代码导入**

```kotlin
val result = skillRepository.importSkillFromDirectInput(
    skillId = "weather-helper",
    description = "天气查询助手",
    content = """
        # 天气查询助手
        
        当用户询问天气时：
        1. 提取城市名称
        2. 调用 weather_search(city) 工具
        3. 以友好方式返回结果
    """.trimIndent()
)
```

### 5.2 从 GitHub 安装 Skill

```kotlin
// 完整仓库
val result = skillRepository.importSkillFromGitHubRepo(
    "https://github.com/username/my-skill-repo"
)

// 子目录
val result = skillRepository.importSkillFromGitHubRepo(
    "https://github.com/username/repo/tree/main/skills/weather"
)

// 原始文件
val result = skillRepository.importSkillFromGitHubRepo(
    "https://raw.githubusercontent.com/username/repo/main/skills/weather/SKILL.md"
)
```

### 5.3 在 AI 对话中使用 Skill

```kotlin
// 1. 获取 Skill 系统提示词
val systemPrompt = skillManager.getSkillSystemPrompt("code-reviewer")

// 2. 拼接进 AI 请求
val fullPrompt = """
    $baseSystemPrompt
    
    $systemPrompt
    
    用户问题: $userQuery
""".trimIndent()

// 3. 发送给 AI
aiService.sendMessage(fullPrompt)
```

### 5.4 控制 Skill 可见性

```kotlin
// 获取 AI 可见的 Skill 列表
val visibleSkills = skillRepository.getAiVisibleSkillPackages()

// 切换可见性
skillVisibilityPreferences.setSkillVisibleToAi("code-reviewer", false)

// 检查状态
val isVisible = skillVisibilityPreferences.isSkillVisibleToAi("code-reviewer")
```

### 5.5 发布 Skill 到市场

```kotlin
viewModel.publishSkill(
    title = "代码审查助手",
    description = "自动审查代码变更，提供改进建议",
    repositoryUrl = "https://github.com/username/code-reviewer-skill",
    version = "v1"
)
```

### 5.6 从市场安装 Skill

```kotlin
// 通过市场条目安装
viewModel.installSkill(marketItem)

// 通过 Issue 安装
viewModel.installSkillFromIssue(githubIssue)

// 直接通过仓库地址安装
viewModel.installSkillFromRepoUrl("https://github.com/username/repo")
```

---

## 6. 安全设计

### 6.1 ZIP 解压安全

```kotlin
private fun unzipToDirectory(zipFile: File, destinationDir: File) {
    // 防止 Zip Slip 攻击
    val outCanonical = outFile.canonicalFile
    if (!outCanonical.path.startsWith(destCanonical.path + File.separator)) {
        throw IllegalArgumentException("Zip entry is outside target dir")
    }
}
```

### 6.2 路径遍历防护

```kotlin
// GitHub 子目录导入时验证路径
val resolvedCanonical = resolved.canonicalFile
if (!resolvedCanonical.path.startsWith(baseCanonical.path + File.separator)) {
    return context.getString(R.string.skill_error_import_invalid_path)
}
```

### 6.3 Skill ID 校验

```kotlin
private val SKILL_ID_PATTERN = Regex("^[A-Za-z0-9._-]+$")

private fun isValidSkillId(skillId: String): Boolean {
    return SKILL_ID_PATTERN.matches(skillId) && skillId != "." && skillId != ".."
}
```

---

## 7. 模块关系图

```
skill 模块
├── 核心层 (core.tools.skill)
│   ├── SkillManager          # 本地 Skill 扫描、解析、系统提示词生成
│   └── SkillPackage          # Skill 数据模型
├── 数据层 (data.skill)
│   ├── SkillRepository       # 导入/导出网关（ZIP、GitHub、直接输入）
│   └── SkillVisibilityPreferences  # AI 可见性配置
├── 工具层 (util)
│   └── SkillRepoZipPoolManager     # GitHub ZIP 缓存池（LRU）
└── UI 层 (ui.features.packages)
    ├── SkillMarketViewModel  # 市场浏览、搜索、安装、发布
    ├── SkillIssueParser      # GitHub Issue 元数据解析
    └── SkillMarketBrowseItem # 市场列表项数据模型
```

---

## 8. 最佳实践

### 8.1 Skill 命名规范

- Skill ID 仅允许 `A-Za-z0-9._-`
- 名称应简洁明了，如 `code-reviewer`、`git-commit-helper`
- 避免与内置工具重名

### 8.2 Frontmatter 必填字段

```markdown
---
name: "skill-name"        # 唯一标识
description: "描述"       # 一句话说明用途
---
```

### 8.3 资源组织

```
skill-name/
├── SKILL.md              # 主文件（必须）
├── assets/               # 图片、模板等静态资源
│   └── example.png
└── scripts/              # 辅助脚本（可选）
    └── helper.sh
```

### 8.4 版本管理

- 使用 Git 管理 Skill 仓库
- 通过 GitHub Release 标记版本
- 市场发布时填写 `version` 字段

### 8.5 性能优化

- 大型 Skill 建议分块，避免单文件过大
- 利用 `SkillRepoZipPoolManager` 缓存避免重复下载
- 定期调用 `refreshAvailableSkills()` 同步本地状态

---

## 9. 总结

Skill 模块通过 **Markdown-First** 的设计哲学，将 AI 能力扩展的门槛降到最低。其核心优势包括：

1. **零编译**：纯 Markdown + 文件系统，无需构建工具
2. **即插即用**：扫描目录即可发现新 Skill，无需重启应用
3. **社区驱动**：基于 GitHub Issues 的市场机制，天然支持协作和版本管理
4. **安全可控**：ZIP 解压防护、路径遍历检查、细粒度可见性控制
5. **生态兼容**：支持从任意 Git 仓库导入，与现有开发工作流无缝集成
