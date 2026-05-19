# Backup 模块设计思想与详细使用指南

## 1. 模块概述

`backup` 模块位于 `com.ai.assistance.operit.data.backup` 包下，是 Operit AI 项目的**数据备份与恢复系统**。它提供了两套独立的备份机制：

1. **原始快照备份（Raw Snapshot Backup）**：完整打包应用的所有私有数据（files、shared_prefs、datastore、databases），用于整机迁移或灾难恢复。
2. **Room 数据库备份（Room Database Backup）**：针对 Room 数据库的增量式自动/手动备份，用于日常数据保护。

模块设计遵循**分层隔离**、**非破坏性恢复**和**并发安全**原则，确保备份过程不影响应用正常运行，恢复过程不丢失用户数据。

---

## 2. 核心设计思想

### 2.1 双轨备份策略

| 维度 | Raw Snapshot Backup | Room Database Backup |
|------|---------------------|----------------------|
| **范围** | 全量（files + shared_prefs + datastore + databases） | 仅 Room 数据库（含 WAL/SHM） |
| **频率** | 手动触发 | 每日自动 + 手动触发 |
| **存储位置** | `/sdcard/Download/Operit/backup/raw_snapshot/` | `/sdcard/Download/Operit/backup/room_db/` |
| **适用场景** | 换机迁移、完整数据归档 | 日常防误删、回滚到昨日 |
| **恢复方式** | 完全替换应用数据 | 仅替换数据库文件 |

### 2.2 非破坏性恢复（Non-Destructive Restore）

Raw Snapshot 恢复时采用**合并而非清空**策略：

```kotlin
// 只覆盖备份中存在的文件，保留本地新增的文件
private fun replaceDirContents(fromDir: File, toDir: File, preservedTopLevelDirNames: Set<String>) {
    // Non-destructive restore: only overwrite files present in the backup.
    // Files not present in the backup are preserved.
    copyDir(fromDir, toDir, preservedTopLevelDirNames)
}
```

这意味着：
- 恢复后，本地新增但未备份的文件仍然保留
- 可选择性保留特定目录（如终端数据 `usr/`、`tmp/`、`bin/`）

### 2.3 WAL 安全备份

备份 SQLite 数据库前强制执行 `PRAGMA wal_checkpoint(FULL)`，确保 WAL 文件中的数据完全写入主数据库：

```kotlin
try {
    val sqliteDb = AppDatabase.getDatabase(context).openHelper.writableDatabase
    sqliteDb.query("PRAGMA wal_checkpoint(FULL)").close()
} catch (e: Exception) {
    AppLogger.w(TAG, "wal_checkpoint failed", e)
}
```

### 2.4 互斥锁保护

所有备份和恢复操作通过 `RoomDatabaseBackupRestoreLock.mutex` 串行化，防止并发操作导致数据损坏：

```kotlin
object RoomDatabaseBackupRestoreLock {
    val mutex = Mutex()
}
```

### 2.5 临时文件原子写入

备份文件先写入 `.tmp` 临时文件，完成后原子重命名，避免中断产生不完整备份：

```kotlin
val tmpFile = File(operitDir, "${targetFile.name}.tmp")
// ... 写入 ZIP ...
if (!tmpFile.renameTo(targetFile)) {
    tmpFile.copyTo(targetFile, overwrite = true)
    tmpFile.delete()
}
```

---

## 3. 目录结构

```
/sdcard/Download/Operit/
├── backup/
│   ├── raw_snapshot/           # 原始快照备份
│   │   └── operit_raw_snapshot_2024-01-15_10-30-00.zip
│   ├── room_db/                # Room 数据库备份
│   │   ├── room_db_backup_2024-01-15.zip      # 自动备份
│   │   └── room_db_manual_backup_2024-01-15_10-30-00.zip  # 手动备份
│   ├── chat/                   # 聊天记录备份（预留）
│   ├── memory/                 # 记忆备份（预留）
│   ├── model_config/           # 模型配置备份（预留）
│   └── character_cards/        # 角色卡备份（预留）
└── ...
```

---

## 4. 核心类详解

### 4.1 OperitBackupDirs — 备份目录管理

统一管理和创建备份相关的所有目录：

```kotlin
object OperitBackupDirs {
    fun backupRootDir(): File        // /sdcard/Download/Operit/backup/
    fun rawSnapshotDir(): File       // /sdcard/Download/Operit/backup/raw_snapshot/
    fun roomDbDir(): File            // /sdcard/Download/Operit/backup/room_db/
    fun chatDir(): File              // /sdcard/Download/Operit/backup/chat/
    fun memoryDir(): File            // /sdcard/Download/Operit/backup/memory/
    fun modelConfigDir(): File       // /sdcard/Download/Operit/backup/model_config/
    fun characterCardsDir(): File    // /sdcard/Download/Operit/backup/character_cards/
}
```

### 4.2 RawSnapshotBackupManager — 原始快照备份管理器

单例对象，负责应用全量数据的打包和恢复。

#### 导出流程

```kotlin
suspend fun exportToBackupDir(
    context: Context,
    options: SnapshotOptions = SnapshotOptions(),
    onProgress: ((ExportProgressInfo) -> Unit)? = null
): File
```

**导出阶段**：

| 阶段 | 说明 |
|------|------|
| `PREPARING` | 初始化，创建临时文件 |
| `SCANNING_FILES` | 扫描 files 目录，统计文件数量 |
| `ZIPPING_FILES` | 打包 files 目录（支持进度百分比） |
| `ZIPPING_SHARED_PREFS` | 打包 shared_prefs |
| `ZIPPING_DATASTORE` | 打包 DataStore 文件 |
| `ZIPPING_DATABASES` | 打包 databases 目录 |
| `FINALIZING` | 原子重命名临时文件 |

**ZIP 内部结构**：

```
operit_raw_snapshot_2024-01-15_10-30-00.zip
├── manifest.json               # 备份元数据
└── payload/
    ├── files/                  # context.filesDir 内容
    ├── shared_prefs/           # SharedPreferences XML 文件
    ├── datastore/              # DataStore 文件
    └── databases/              # SQLite 数据库文件
```

**manifest.json 格式**：

```json
{
  "formatVersion": 1,
  "packageName": "com.ai.assistance.operit",
  "createdAt": 1705312200000,
  "includes": ["payload/files/", "payload/shared_prefs/", "payload/datastore/", "payload/databases/"],
  "includeTerminalData": true
}
```

#### 智能排除规则

导出时自动排除以下文件/目录，避免备份过大或包含临时数据：

| 排除项 | 原因 |
|--------|------|
| `.sherpa_ncnn_models/` | 语音模型（体积大，可重新下载） |
| `.vector_index/` | 向量索引（可重建） |
| `image_pool/` | 图片缓存 |
| `media_pool/` | 媒体缓存 |
| `skill_repo_zip_pool/` | Skill ZIP 缓存（可重新下载） |
| `sherpa-ncnn-*` | NCNN 模型文件 |
| `ubuntu-*.tar.xz` | Ubuntu rootfs（体积巨大） |
| `memory_hnsw_*.idx` | HNSW 索引文件 |
| `doc_index_*.hnsw` | 文档索引文件 |
| `objectbox/lock.mdb` | ObjectBox 锁文件 |
| `usr/`, `tmp/`, `bin/` | 终端数据（可选排除） |

#### 恢复流程

```kotlin
suspend fun restoreFromBackupUri(
    context: Context,
    uri: Uri,
    onProgress: ((RestoreProgress) -> Unit)? = null
)
```

**恢复阶段**：

| 阶段 | 说明 |
|------|------|
| `PREPARING` | 准备恢复环境 |
| `READING_ZIP` | 将 ZIP 复制到缓存 |
| `EXTRACTING` | 解压到工作目录，验证 manifest |
| `REPLACING_FILES` | 合并 files 目录 |
| `REPLACING_SHARED_PREFS` | 合并 shared_prefs |
| `REPLACING_DATASTORE` | 合并 datastore |
| `REPLACING_DATABASES` | 合并 databases |
| `FINALIZING` | 清理临时文件 |

**恢复前关闭数据库**：

```kotlin
AppDatabase.closeDatabase()
ObjectBoxManager.closeAll()
```

**包名校验**：恢复时验证 manifest 中的 `packageName` 与当前应用一致，防止误恢复其他应用的备份。

### 4.3 RoomDatabaseBackupManager — Room 数据库备份管理器

#### 备份类型

| 类型 | 文件名格式 | 触发方式 |
|------|-----------|----------|
| 自动备份 | `room_db_backup_2024-01-15.zip` | WorkManager 每日定时 |
| 手动备份 | `room_db_manual_backup_2024-01-15_10-30-00.zip` | 用户手动触发 |

#### 核心方法

```kotlin
object RoomDatabaseBackupManager {
    // 按需备份（每日只备份一次）
    suspend fun backupIfNeeded(context: Context, force: Boolean): BackupResult

    // 清理超出数量限制的备份
    suspend fun pruneExcessBackups(context: Context)
}
```

**备份内容**：

```kotlin
val entries = mapOf(
    "app_database" to dbFile,           // 主数据库文件
    "app_database-wal" to walFile,      // WAL 日志
    "app_database-shm" to shmFile       // 共享内存
)
```

**保留策略**：

```kotlin
// 默认保留最近 10 个备份
val DEFAULT_MAX_BACKUP_COUNT = 10

// 超出限制时删除最旧的备份
enforceMaxBackupCount(context, keepLatest = maxBackupCount)
```

### 4.4 RoomDatabaseRestoreManager — Room 数据库恢复管理器

```kotlin
object RoomDatabaseRestoreManager {
    // 从 URI 恢复（用户选择文件）
    suspend fun restoreFromBackupUri(context: Context, uri: Uri)

    // 从本地文件恢复
    suspend fun restoreFromBackupFile(context: Context, zipFile: File)

    // 列出最近的自动备份
    fun listRecentAutoBackups(context: Context, limit: Int = 3): List<File>

    // 列出所有最近的备份（自动+手动）
    fun listRecentBackups(context: Context, limit: Int = 3): List<File>
}
```

**恢复流程**：

1. 关闭当前数据库连接
2. 解压 ZIP 到临时文件（`*.restore.tmp`）
3. 删除旧的数据库文件（db + wal + shm）
4. 原子替换临时文件为目标文件
5. 应用重启后自动加载新数据库

### 4.5 RoomDatabaseBackupPreferences — 备份偏好设置

使用 Android DataStore 持久化备份配置：

```kotlin
class RoomDatabaseBackupPreferences private constructor(context: Context) {
    val enableDailyBackupFlow: Flow<Boolean>    // 是否启用每日备份（默认 true）
    val lastBackupDayFlow: Flow<String?>        // 上次备份日期
    val lastSuccessTimeFlow: Flow<Long>         // 上次成功时间戳
    val lastErrorFlow: Flow<String>             // 上次错误信息
    val maxBackupCountFlow: Flow<Int>           // 最大保留数量（默认 10）

    suspend fun isDailyBackupEnabled(): Boolean
    suspend fun setDailyBackupEnabled(enabled: Boolean)
    suspend fun markSuccess(day: String, timestamp: Long)
    suspend fun markFailure(error: String)
    suspend fun getMaxBackupCount(): Int
    suspend fun setMaxBackupCount(count: Int)
}
```

### 4.6 RoomDatabaseBackupScheduler — 备份调度器

基于 WorkManager 实现定时备份：

```kotlin
object RoomDatabaseBackupScheduler {
    // 注册每日凌晨 3 点的周期性备份任务
    fun ensureScheduled(context: Context)

    // 取消定时备份
    fun cancelScheduled(context: Context)

    // 立即执行一次手动备份
    fun enqueueManualBackup(context: Context, force: Boolean)
}
```

**约束条件**：

```kotlin
val constraints = Constraints.Builder()
    .setRequiresStorageNotLow(true)  // 存储空间充足时才执行
    .build()
```

**初始延迟计算**：

```kotlin
private fun calculateInitialDelayToHour(targetHour: Int): Long {
    val now = LocalDateTime.now()
    var next = now.withHour(targetHour).withMinute(0).withSecond(0).withNano(0)
    if (!next.isAfter(now)) {
        next = next.plusDays(1)  // 如果已经过了 3 点，则排到明天
    }
    return Duration.between(now, next).toMillis()
}
```

### 4.7 RoomDatabaseBackupWorker — 备份工作器

```kotlin
class RoomDatabaseBackupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val force = inputData.getBoolean(KEY_FORCE, false)
        val result = RoomDatabaseBackupManager.backupIfNeeded(applicationContext, force)
        // 成功返回 Result.success()，失败返回 Result.failure()
    }
}
```

### 4.8 RoomDatabaseBackupRestoreLock — 并发锁

```kotlin
object RoomDatabaseBackupRestoreLock {
    val mutex = Mutex()
}
```

所有 Room 数据库的备份和恢复操作都必须通过此锁串行执行。

---

## 5. 使用指南

### 5.1 执行原始快照备份

```kotlin
// 基本导出
val backupFile = RawSnapshotBackupManager.exportToBackupDir(context)

// 带进度回调
val backupFile = RawSnapshotBackupManager.exportToBackupDir(
    context = context,
    options = RawSnapshotBackupManager.SnapshotOptions(
        includeTerminalData = false  // 不包含终端数据（usr/ tmp/ bin/）
    ),
    onProgress = { progress ->
        when (progress.stage) {
            RawSnapshotBackupManager.ExportProgress.SCANNING_FILES ->
                println("扫描文件: ${progress.scannedFiles} 个")
            RawSnapshotBackupManager.ExportProgress.ZIPPING_FILES ->
                println("打包进度: ${progress.percent}%")
            else -> println("阶段: ${progress.stage}")
        }
    }
)
```

### 5.2 从原始快照恢复

```kotlin
// 从用户选择的文件恢复
val uri: Uri = ... // 从文件选择器获取
RawSnapshotBackupManager.restoreFromBackupUri(
    context = context,
    uri = uri,
    onProgress = { stage ->
        println("恢复阶段: $stage")
    }
)

// 恢复后需要重启应用以重新加载数据库
```

### 5.3 执行 Room 数据库手动备份

```kotlin
// 方式一：直接调用 Manager
val result = RoomDatabaseBackupManager.backupIfNeeded(context, force = true)
if (result.performed) {
    println("备份成功: ${result.backupFile?.absolutePath}")
} else {
    println("跳过备份: ${result.skippedReason}")
}

// 方式二：通过 WorkManager 异步执行
RoomDatabaseBackupScheduler.enqueueManualBackup(context, force = true)
```

### 5.4 从 Room 备份恢复

```kotlin
// 从 URI 恢复
val uri: Uri = ...
RoomDatabaseRestoreManager.restoreFromBackupUri(context, uri)

// 从本地文件恢复
val zipFile = File("/path/to/room_db_backup_2024-01-15.zip")
RoomDatabaseRestoreManager.restoreFromBackupFile(context, zipFile)
```

### 5.5 列出可用备份

```kotlin
// 最近的自动备份
val autoBackups = RoomDatabaseRestoreManager.listRecentAutoBackups(context, limit = 5)

// 所有备份（自动+手动）
val allBackups = RoomDatabaseRestoreManager.listRecentBackups(context, limit = 10)
```

### 5.6 配置备份偏好

```kotlin
val preferences = RoomDatabaseBackupPreferences.getInstance(context)

// 关闭自动备份
preferences.setDailyBackupEnabled(false)

// 设置保留数量
preferences.setMaxBackupCount(20)

// 查询上次备份时间
val lastDay = preferences.lastBackupDayFlow.first()
val lastTime = preferences.lastSuccessTimeFlow.first()
```

### 5.7 注册定时备份

```kotlin
// 在 Application.onCreate() 或首次启动时调用
RoomDatabaseBackupScheduler.ensureScheduled(context)

// 取消定时备份
RoomDatabaseBackupScheduler.cancelScheduled(context)
```

---

## 6. 安全设计

### 6.1 ZIP Slip 防护

```kotlin
val target = File(workDir, name)
val workCanonical = workDir.canonicalFile
val targetCanonical = target.canonicalFile
if (!targetCanonical.path.startsWith(workCanonical.path + File.separator)) {
    throw IllegalArgumentException("Invalid zip entry path: $name")
}
```

### 6.2 包名校验

```kotlin
if (manifest.packageName != expectedPackageName) {
    throw IllegalArgumentException("Backup package mismatch: ${manifest.packageName}")
}
```

### 6.3 格式版本校验

```kotlin
if (manifest.formatVersion != FORMAT_VERSION) {
    throw IllegalArgumentException("Unsupported backup version: ${manifest.formatVersion}")
}
```

### 6.4 大文件防护

```kotlin
private fun ZipInputStream.readBytesSafely(maxBytes: Int): ByteArray {
    if (out.size() + read > maxBytes) {
        throw IllegalArgumentException("Zip entry too large")
    }
}
```

Manifest 文件限制为 512KB，防止恶意 ZIP 导致内存溢出。

### 6.5 数据库关闭保护

恢复前强制关闭所有数据库连接：

```kotlin
AppDatabase.closeDatabase()
ObjectBoxManager.closeAll()
```

---

## 7. 模块关系图

```
backup 模块
├── OperitBackupDirs              # 备份目录统一管理
├── RawSnapshotBackupManager      # 全量快照备份/恢复
│   ├── Manifest                  # 备份元数据（JSON）
│   ├── SnapshotOptions           # 导出选项
│   ├── ExportProgress            # 导出阶段枚举
│   └── RestoreProgress           # 恢复阶段枚举
├── RoomDatabaseBackupManager     # Room 数据库备份
│   ├── BackupResult              # 备份结果
│   └── 自动/手动备份创建
├── RoomDatabaseRestoreManager    # Room 数据库恢复
│   ├── listRecentAutoBackups()   # 列出自动备份
│   └── listRecentBackups()       # 列出所有备份
├── RoomDatabaseBackupPreferences # DataStore 偏好设置
├── RoomDatabaseBackupScheduler   # WorkManager 调度器
├── RoomDatabaseBackupWorker      # 后台备份 Worker
└── RoomDatabaseBackupRestoreLock # 并发互斥锁
```

---

## 8. 最佳实践

### 8.1 备份前检查存储空间

WorkManager 约束已包含 `setRequiresStorageNotLow(true)`，确保存储空间充足时才执行备份。

### 8.2 定期清理旧备份

```kotlin
// 在设置中提供清理选项
RoomDatabaseBackupManager.pruneExcessBackups(context)
```

### 8.3 恢复后重启应用

Raw Snapshot 恢复后必须重启应用，因为：
- 数据库文件已被替换，现有连接失效
- SharedPreferences 缓存需要重新加载
- DataStore 需要重新初始化

### 8.4 终端数据选择性保留

```kotlin
// 不包含终端数据（推荐，减少备份体积）
val options = RawSnapshotBackupManager.SnapshotOptions(
    includeTerminalData = false
)

// 恢复时自动保留本地终端数据
```

### 8.5 备份文件命名规范

| 类型 | 命名示例 |
|------|----------|
| 原始快照 | `operit_raw_snapshot_2024-01-15_10-30-00.zip` |
| Room 自动 | `room_db_backup_2024-01-15.zip` |
| Room 手动 | `room_db_manual_backup_2024-01-15_10-30-00.zip` |

### 8.6 跨版本兼容性

- `formatVersion` 用于标识备份格式版本
- 恢复时校验版本号，不兼容时抛出异常
- 升级备份格式时应递增 `FORMAT_VERSION`

---

## 9. 总结

Backup 模块通过**双轨备份策略**覆盖了从日常数据保护到完整迁移的全场景需求：

1. **Raw Snapshot** 提供**全量、完整、可迁移**的备份方案，适合换机、归档和灾难恢复
2. **Room Database Backup** 提供**轻量、自动、高频**的备份方案，适合日常防误删
3. **非破坏性恢复**确保用户数据安全，不会因恢复操作丢失新增内容
4. **WAL 安全机制**保证数据库备份的一致性
5. **WorkManager 集成**实现可靠的定时备份，即使应用未运行也能执行
6. **多重安全校验**（ZIP Slip 防护、包名校验、版本校验）确保恢复操作的安全性
