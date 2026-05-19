# Data 数据模块设计文档

## 1. 模块概述

`com.ai.assistance.operit.data` 是 Operit AI 应用的**核心数据层**，负责所有数据的持久化、查询、备份恢复以及跨格式数据转换。该模块采用**分层架构**设计，将数据库访问、业务仓库、数据模型、导入导出、备份恢复等职责清晰分离。

---

## 2. 整体架构

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           data 模块架构                                   │
├─────────────────────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────────┐ │
│  │   model     │  │    dao      │  │     db      │  │   repository    │ │
│  │  数据实体    │  │  数据访问   │  │  数据库管理  │  │   业务仓库      │ │
│  │  (Entity)   │  │   (DAO)     │  │ (Room/OB)   │  │  (Repository)   │ │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘  └────────┬────────┘ │
│         │                │                │                  │          │
│  ┌──────▼──────┐  ┌──────▼──────┐  ┌──────▼──────┐  ┌───────▼────────┐ │
│  │  converter  │  │  exporter   │  │   backup    │  │  preferences   │ │
│  │  格式转换   │  │   导出器    │  │   备份恢复   │  │   偏好设置     │ │
│  │ (Import)    │  │  (Export)   │  │             │  │                │ │
│  └─────────────┘  └─────────────┘  └─────────────┘  └────────────────┘ │
│                                                                         │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────────┐ │
│  │    api      │  │   collects  │  │    mcp      │  │    updates      │ │
│  │  远程API    │  │  数据收集   │  │  MCP桥接    │  │   更新管理      │ │
│  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────────┘ │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 3. 核心子模块详解

### 3.1 db — 数据库管理

#### 3.1.1 AppDatabase（Room 数据库）

文件：[AppDatabase.kt](file:///Users/liangyingjie/Documents/my_agent_projects/Operit/app/src/main/java/com/ai/assistance/operit/data/db/AppDatabase.kt)

应用主数据库，采用 **Room** 框架管理，当前版本 **18**，包含三张核心表：

| 表名 | 实体类 | 说明 |
|------|--------|------|
| `chats` | `ChatEntity` | 聊天会话元数据 |
| `messages` | `MessageEntity` | 聊天消息内容 |
| `message_variants` | `MessageVariantEntity` | AI 消息的多版本变体 |

**数据库迁移策略：**
- 定义了从 v1→v2 到 v17→v18 的完整迁移链
- 采用 `ALTER TABLE ADD COLUMN` 渐进式升级
- 关键迁移示例：
  - v2→v3：添加 `group` 分组列
  - v7→v8：添加 `parentChatId` 和 `characterCardName` 分支/角色卡支持
  - v12→v13：添加 Token 统计和耗时字段
  - v14→v15：添加消息变体支持
  - v17→v18：添加消息收藏 `isFavorite`

**单例获取：**
```kotlin
val database = AppDatabase.getDatabase(context)
```

#### 3.1.2 ObjectBoxManager（ObjectBox 数据库）

文件：[ObjectBox.kt](file:///Users/liangyingjie/Documents/my_agent_projects/Operit/app/src/main/java/com/ai/assistance/operit/data/db/ObjectBox.kt)

用于 **Memory（记忆系统）** 的 NoSQL 数据库，支持多 Profile 隔离：

```kotlin
object ObjectBoxManager {
    fun get(context: Context, profileId: String): BoxStore
    fun close(profileId: String)
    fun delete(context: Context, profileId: String)  // 物理删除
    fun closeAll()
}
```

- 使用 `ConcurrentHashMap` 缓存多个 `BoxStore` 实例
- Profile "default" 使用旧路径兼容已有数据
- 其他 Profile 使用 `objectbox_$profileId` 隔离存储

---

### 3.2 model — 数据实体

#### 3.2.1 ChatEntity / MessageEntity（Room 实体）

文件：[ChatEntity.kt](file:///Users/liangyingjie/Documents/my_agent_projects/Operit/app/src/main/java/com/ai/assistance/operit/data/model/ChatEntity.kt) / [MessageEntity.kt](file:///Users/liangyingjie/Documents/my_agent_projects/Operit/app/src/main/java/com/ai/assistance/operit/data/model/MessageEntity.kt)

**ChatEntity 核心字段：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | String (PK) | 聊天唯一标识 |
| `title` | String | 聊天标题 |
| `createdAt` / `updatedAt` | Long | 创建/更新时间戳 |
| `inputTokens` / `outputTokens` | Int | Token 消耗统计 |
| `currentWindowSize` | Int | 当前上下文窗口大小 |
| `group` | String? | 分组名称 |
| `displayOrder` | Long | 显示排序 |
| `workspace` / `workspaceEnv` | String? | 工作区路径/环境 |
| `parentChatId` | String? | 父对话ID（分支功能） |
| `characterCardName` | String? | 绑定的角色卡 |
| `characterGroupId` | String? | 绑定的角色群组 |
| `locked` | Boolean | 锁定状态（禁止删除） |

**MessageEntity 核心字段：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `messageId` | Long (PK, auto) | 消息自增ID |
| `chatId` | String (FK) | 所属聊天ID |
| `sender` | String | "user" / "ai" / "summary" |
| `content` | String | 消息内容 |
| `timestamp` | Long | 消息时间戳（排序键） |
| `orderIndex` | Int | 顺序索引 |
| `roleName` | String | 角色名称 |
| `selectedVariantIndex` | Int | 当前选中的变体版本 |
| `provider` / `modelName` | String | 供应商/模型 |
| `inputTokens` / `outputTokens` / `cachedInputTokens` | Int | Token统计 |
| `sentAt` / `outputDurationMs` / `waitDurationMs` | Long | 耗时统计 |
| `displayMode` | String | 显示模式 |
| `isFavorite` | Boolean | 收藏标记 |

**双向转换：**
```kotlin
// Entity → UI Model
chatEntity.toChatHistory(messages)
messageEntity.toChatMessage()

// UI Model → Entity
ChatEntity.fromChatHistory(chatHistory)
MessageEntity.fromChatMessage(chatId, message, orderIndex)
```

---

### 3.3 dao — 数据访问对象

#### 3.3.1 ChatDao

文件：[ChatDao.kt](file:///Users/liangyingjie/Documents/my_agent_projects/Operit/app/src/main/java/com/ai/assistance/operit/data/dao/ChatDao.kt)

提供聊天会话的完整 CRUD 操作，核心方法分类：

**基础查询：**
```kotlin
@Query("SELECT * FROM chats ORDER BY displayOrder ASC")
fun getAllChats(): Flow<List<ChatEntity>>

suspend fun getChatById(chatId: String): ChatEntity?
suspend fun getTotalChatCount(): Int
```

**分组管理：**
```kotlin
suspend fun updateChatGroup(chatId: String, group: String?)
suspend fun updateGroupName(oldName: String, newName: String)
suspend fun deleteChatsInGroup(groupName: String)  // 仅删除未锁定
suspend fun removeGroupFromChats(groupName: String)
```

**角色卡/群组绑定：**
```kotlin
suspend fun updateChatCharacterCardName(chatId: String, characterCardName: String?)
suspend fun updateChatCharacterGroupId(chatId: String, characterGroupId: String?)
suspend fun getChatsByCharacterCard(characterCardName: String): Flow<List<ChatEntity>>
suspend fun clearCharacterCardBinding(characterCardName: String)
```

**分支对话：**
```kotlin
suspend fun getBranchesByParentId(parentChatId: String): List<ChatEntity>
suspend fun getMainChats(): List<ChatEntity>
```

#### 3.3.2 MessageDao

文件：[MessageDao.kt](file:///Users/liangyingjie/Documents/my_agent_projects/Operit/app/src/main/java/com/ai/assistance/operit/data/dao/MessageDao.kt)

提供消息级别的精细操作：

**分页/范围查询：**
```kotlin
// 按时间范围查询
suspend fun getMessagesForChatWindowAsc(chatId, startTimestamp, endTimestamp)

// 分页加载（向前/向后）
suspend fun getMessagesForChatBeforeTimestampExclusiveDesc(chatId, beforeTimestamp, limit)
suspend fun getMessagesForChatAfterTimestampExclusiveAsc(chatId, afterTimestamp, limit)

// 是否存在更多消息
suspend fun existsMessagesBeforeTimestamp(chatId, beforeTimestamp): Boolean
suspend fun existsMessagesAfterTimestamp(chatId, afterTimestamp): Boolean
```

**Summary 支持：**
```kotlin
// 获取最新的 summary 消息时间戳
suspend fun getLatestSummaryTimestamp(chatId: String): Long?
suspend fun getLatestSummaryTimestampBefore(chatId, beforeTimestamp): Long?
```

**消息操作：**
```kotlin
suspend fun insertMessage(message: MessageEntity): Long
suspend fun insertMessages(messages: List<MessageEntity>)
suspend fun updateMessageContent(messageId: Long, content: String)
suspend fun deleteMessagesFrom(chatId: String, timestamp: Long)
suspend fun copyMessagesToChat(sourceChatId, targetChatId, upToTimestampInclusive)
```

**搜索：**
```kotlin
@Query("SELECT DISTINCT chatId FROM messages WHERE content LIKE '%' || :query || '%' ESCAPE '\\' COLLATE NOCASE")
suspend fun searchChatIdsByContent(query: String): List<String>
```

---

### 3.4 repository — 业务仓库

#### 3.4.1 ChatHistoryManager（核心仓库）

文件：[ChatHistoryManager.kt](file:///Users/liangyingjie/Documents/my_agent_projects/Operit/app/src/main/java/com/ai/assistance/operit/data/repository/ChatHistoryManager.kt)

**单例模式**，是 UI 层与数据层之间的主要桥梁，职责包括：

**1. 数据流暴露（Flow/StateFlow）：**
```kotlin
val chatHistoriesFlow: StateFlow<List<ChatHistory>>  // 所有聊天列表
val currentChatIdFlow: StateFlow<String?>            // 当前聊天ID
val characterCardStatsFlow: Flow<List<CharacterCardChatStats>>
```

**2. 消息水合（Hydrate）：**
```kotlin
// 将数据库中的 MessageEntity + MessageVariantEntity 组合为 UI 用的 ChatMessage
private fun hydrateMessages(messageEntities, variants): List<ChatMessage>
```

**3. 并发控制：**
```kotlin
private val globalMutex = Mutex()
private val chatMutexes = ConcurrentHashMap<String, Mutex>()

private fun chatMutex(chatId: String): Mutex {
    return chatMutexes.getOrPut(chatId) { Mutex() }
}
```
- 全局互斥锁用于跨聊天操作（如排序、分组重命名）
- 每聊天互斥锁用于单聊天内的消息操作

**4. 消息变体管理：**
```kotlin
suspend fun addMessageVariant(chatId, messageTimestamp, message): Int
suspend fun selectMessageVariant(chatId, messageTimestamp, selectedVariantIndex)
suspend fun deleteMessageVariant(chatId, messageTimestamp, variantIndex)
```

**5. 导入导出：**
```kotlin
// 导出到下载目录
suspend fun exportChatHistoriesToDownloads(format: ExportFormat): String?

// 从 URI 导入（支持多种格式）
suspend fun importChatHistoriesFromUri(uri: Uri, format: ChatFormat): ChatImportResult
```

**6. 分支对话：**
```kotlin
suspend fun createBranch(parentChatId: String, upToMessageTimestamp: Long?): ChatHistory
suspend fun getBranches(parentChatId: String): List<ChatHistory>
```

**7. 工作区管理：**
```kotlin
suspend fun updateChatWorkspace(chatId: String, workspace: String?, workspaceEnv: String?)
suspend fun renameManagedWorkspace(chatId: String, newWorkspaceName: String): WorkspaceRenameResult
```

#### 3.4.2 MemoryRepository（记忆仓库）

文件：[MemoryRepository.kt](file:///Users/liangyingjie/Documents/my_agent_projects/Operit/app/src/main/java/com/ai/assistance/operit/data/repository/MemoryRepository.kt)

基于 **ObjectBox** 的记忆系统仓库，支持：

**核心功能：**
- 记忆的 CRUD（创建、读取、更新、删除）
- 标签管理（Tag）
- 链接关系（Link）管理
- 文档分块（DocumentChunk）与嵌入
- 文件夹组织

**搜索系统（多维度混合评分）：**
```kotlin
suspend fun searchMemories(
    query: String,
    folderPath: String? = null,
    scoreMode: MemoryScoreMode = MemoryScoreMode.BALANCED,
    keywordWeight: Float = 10.0f,
    tagWeight: Float = 0.0f,
    semanticWeight: Float = 0.5f,
    edgeWeight: Float = 0.4f,
    relevanceThreshold: Double = 0.025
): List<Memory>
```

**评分维度：**
1. **关键词匹配**（标题包含查询片段）
2. **标签匹配**（标签名匹配）
3. **反向包含**（查询包含记忆标题）
4. **语义搜索**（基于 HNSW 向量索引的余弦相似度）
5. **图传播**（通过链接关系传播分数）

**向量索引管理：**
- 使用 `VectorIndexManager` + HNSW 索引文件
- 按维度分离索引文件（`memory_hnsw_${profile}_${dimension}.idx`）
- 文档区块独立索引（`doc_index_${profile}_${memoryId}_${dimension}.hnsw`）

---

### 3.5 converter — 格式转换

详见独立的 [聊天记录导入转换模块设计文档](file:///Users/liangyingjie/Documents/my_agent_projects/Operit/my_docs/聊天记录导入转换模块设计文档.md)。

核心转换器：
- `ChatGPTConverter` — ChatGPT conversations.json
- `ChatBoxConverter` — ChatBox 导出格式
- `GenericJsonConverter` — 通用 role-content JSON
- `MarkdownConverter` — Markdown 注释格式

---

### 3.6 exporter — 导出器

#### 3.6.1 MarkdownExporter

文件：[MarkdownExporter.kt](file:///Users/liangyingjie/Documents/my_agent_projects/Operit/app/src/main/java/com/ai/assistance/operit/data/exporter/MarkdownExporter.kt)

将聊天记录导出为 Markdown 格式，支持：
- HTML 注释元数据（`<!-- chat-info: ... -->` / `<!-- msg: ... -->`）
- YAML Front Matter 兼容
- 多对话合并导出

---

### 3.7 backup — 备份恢复

#### 3.7.1 RawSnapshotBackupManager（全量快照备份）

文件：[RawSnapshotBackupManager.kt](file:///Users/liangyingjie/Documents/my_agent_projects/Operit/app/src/main/java/com/ai/assistance/operit/data/backup/RawSnapshotBackupManager.kt)

**全应用数据快照**，将 `dataDir` 下的所有内容打包为 ZIP：

| 备份内容 | ZIP 路径 |
|---------|---------|
| files 目录 | `payload/files/` |
| SharedPreferences | `payload/shared_prefs/` |
| DataStore | `payload/datastore/` |
| 数据库文件 | `payload/databases/` |
| 清单文件 | `manifest.json` |

**关键特性：**
- 导出前执行 `PRAGMA wal_checkpoint(FULL)` 确保数据一致性
- 支持排除特定顶层目录（如 terminal 数据可选排除）
- 非破坏性恢复：仅覆盖备份中存在的文件
- 进度回调：`ExportProgressInfo` / `RestoreProgress`

#### 3.7.2 RoomDatabaseBackupManager / RoomDatabaseRestoreManager

文件：[RoomDatabaseBackupManager.kt](file:///Users/liangyingjie/Documents/my_agent_projects/Operit/app/src/main/java/com/ai/assistance/operit/data/backup/RoomDatabaseBackupManager.kt) / [RoomDatabaseRestoreManager.kt](file:///Users/liangyingjie/Documents/my_agent_projects/Operit/app/src/main/java/com/ai/assistance/operit/data/backup/RoomDatabaseRestoreManager.kt)

**仅备份 Room 数据库**，更轻量：

- 自动每日备份（`room_db_backup_YYYY-MM-DD.zip`）
- 手动备份（`room_db_manual_backup_YYYY-MM-DD_HH-mm-ss.zip`）
- 备份内容：数据库主文件 + WAL + SHM
- 保留策略：可配置最大备份数量，自动清理旧备份
- 恢复时关闭数据库 → 替换文件 → 重新打开

#### 3.7.3 OperitBackupDirs（备份目录管理）

文件：[OperitBackupDirs.kt](file:///Users/liangyingjie/Documents/my_agent_projects/Operit/app/src/main/java/com/ai/assistance/operit/data/backup/OperitBackupDirs.kt)

统一管理的备份目录结构：
```
/sdcard/Operit/backup/
├── raw_snapshot/      # 全量快照
├── room_db/           # Room 数据库备份
├── chat/              # 聊天记录导出
├── memory/            # 记忆导出
├── model_config/      # 模型配置备份
└── character_cards/   # 角色卡备份
```

---

## 4. 设计思路

### 4.1 双数据库策略

| 数据库 | 用途 | 特点 |
|--------|------|------|
| **Room (SQLite)** | 聊天记录、消息 | 关系型，支持复杂查询、Flow 响应式、成熟迁移机制 |
| **ObjectBox** | 记忆系统 | NoSQL，高性能，支持对象关系（ToMany/ToOne），适合图结构 |

### 4.2 数据模型分层

```
UI 层:     ChatHistory / ChatMessage     (包含业务逻辑，如 Parcelable)
              ↑↓ 双向转换
数据层:    ChatEntity / MessageEntity     (数据库实体，注解标记)
```

- UI 模型（`ChatHistory`）包含 `messages` 列表，便于界面展示
- 数据库实体（`ChatEntity`）不包含消息，通过外键关联，提高列表查询性能

### 4.3 并发安全

- **Repository 层**使用 `Mutex` 保护关键操作
- **DAO 层**使用 Room 的协程支持（`suspend` 函数）
- **数据库操作**统一在 `Dispatchers.IO` 上执行

### 4.4 响应式数据流

```kotlin
// DAO 返回 Flow，自动响应数据库变化
@Query("SELECT * FROM chats ORDER BY displayOrder ASC")
fun getAllChats(): Flow<List<ChatEntity>>

// Repository 转换为 StateFlow，供 UI 订阅
val chatHistoriesFlow = _chatHistoriesFlow.stateIn(
    scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    started = SharingStarted.Lazily,
    initialValue = emptyList()
)
```

### 4.5 备份策略分层

| 备份类型 | 适用场景 | 粒度 |
|---------|---------|------|
| 全量快照 | 换机迁移、完整备份 | 整个应用数据目录 |
| Room 数据库 | 日常保护、快速恢复 | 仅 SQLite 文件 |
| 聊天记录导出 | 分享、跨应用迁移 | 特定格式的聊天数据 |

---

## 5. 使用方法

### 5.1 获取 ChatHistoryManager

```kotlin
val chatHistoryManager = ChatHistoryManager.getInstance(context)
```

### 5.2 创建新聊天

```kotlin
val newChat = chatHistoryManager.createNewChat(
    group = "工作",
    characterCardName = "助手",
    setAsCurrentChat = true
)
```

### 5.3 添加消息

```kotlin
val message = ChatMessage(
    sender = "user",
    content = "Hello",
    modelName = "gpt-4"
)
chatHistoryManager.addMessage(chatId, message)
```

### 5.4 加载消息（分页）

```kotlin
// 加载最新消息（倒序，限制数量）
val messages = chatHistoryManager.loadChatMessagesDesc(chatId, limit = 50)

// 加载更早的消息
val olderMessages = chatHistoryManager.loadOlderChatMessages(
    chatId = chatId,
    beforeTimestampExclusive = oldestTimestamp,
    limit = 50
)
```

### 5.5 搜索聊天内容

```kotlin
val chatIds = chatHistoryManager.searchChatIdsByContent("关键词")
```

### 5.6 导入聊天记录

```kotlin
val result = chatHistoryManager.importChatHistoriesFromUri(uri, ChatFormat.CHATGPT)
// result.new / result.updated / result.skipped
```

### 5.7 导出聊天记录

```kotlin
val filePath = chatHistoryManager.exportChatHistoriesToDownloads(ExportFormat.MARKDOWN)
```

### 5.8 备份与恢复

```kotlin
// 全量快照备份
val backupFile = RawSnapshotBackupManager.exportToBackupDir(context)

// 全量快照恢复
RawSnapshotBackupManager.restoreFromBackupUri(context, uri)

// Room 数据库自动备份
val result = RoomDatabaseBackupManager.backupIfNeeded(context, force = false)

// Room 数据库恢复
RoomDatabaseRestoreManager.restoreFromBackupUri(context, uri)
```

### 5.9 记忆系统使用

```kotlin
val memoryRepo = MemoryRepository(context, profileId)

// 创建记忆
val memory = memoryRepo.createMemory(
    title = "重要概念",
    content = "这是需要记住的内容",
    tags = listOf("概念", "重要")
)

// 搜索记忆
val results = memoryRepo.searchMemories(
    query = "关键词",
    scoreMode = MemoryScoreMode.BALANCED
)

// 创建链接
memoryRepo.linkMemories(source, target, type = "related", weight = 0.7f)
```

---

## 6. 文件清单

| 目录 | 关键文件 | 职责 |
|------|---------|------|
| `db/` | `AppDatabase.kt` | Room 数据库定义与迁移 |
| `db/` | `ObjectBox.kt` | ObjectBox 多 Profile 管理 |
| `model/` | `ChatEntity.kt` | 聊天会话数据库实体 |
| `model/` | `MessageEntity.kt` | 消息数据库实体 |
| `dao/` | `ChatDao.kt` | 聊天会话数据访问 |
| `dao/` | `MessageDao.kt` | 消息数据访问 |
| `repository/` | `ChatHistoryManager.kt` | 聊天业务仓库（核心） |
| `repository/` | `MemoryRepository.kt` | 记忆系统仓库 |
| `converter/` | `ChatFormatConverter.kt` | 转换器接口 |
| `converter/` | `ChatFormatDetector.kt` | 格式检测器 |
| `converter/` | `ChatGPTConverter.kt` | ChatGPT 格式转换 |
| `converter/` | `ChatBoxConverter.kt` | ChatBox 格式转换 |
| `converter/` | `GenericJsonConverter.kt` | 通用 JSON 转换 |
| `converter/` | `MarkdownConverter.kt` | Markdown 格式转换 |
| `exporter/` | `MarkdownExporter.kt` | Markdown 导出 |
| `backup/` | `RawSnapshotBackupManager.kt` | 全量快照备份 |
| `backup/` | `RoomDatabaseBackupManager.kt` | Room 数据库备份 |
| `backup/` | `RoomDatabaseRestoreManager.kt` | Room 数据库恢复 |
| `backup/` | `OperitBackupDirs.kt` | 备份目录管理 |
