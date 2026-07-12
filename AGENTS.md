# AGENTS.md - SeamlessLoopMobile

## 项目概览

Android 无缝循环音频播放器，Kotlin + C++ (Oboe) 混合架构。
单模块项目 (`:app`)，Min SDK 26，Compile/Target SDK 35。
Kotlin 2.1.0 + AGP 9.0.1 + Gradle 9.1.0；使用 `org.jetbrains.kotlin.plugin.compose` 插件，`composeCompiler.includeSourceInformation=true` 便于 Layout Inspector 溯源。

## 构建与测试

```bash
gradlew.bat -q assembleDebug
gradlew.bat -q testDebugUnitTest
gradlew.bat -q testDebugUnitTest --tests "com.cpu.seamlessloopmobile.viewmodel.PlayModeTest"
gradlew.bat -q connectedAndroidTest            # 需要设备
```

**测试前提**：`PcDatabaseImporterTest` 需要 `app/src/test/resources/pc_db_samples/pc_3nf_sample.db` 存在才能运行（仅 `pc_3nf_sample.db` 存在且有内容）；若文件缺失则测试静默通过而不报错！

**快速部署**（需 root 设备）：`run.bat` — adb push → pm install → am start。

## 架构要点

- **导航/UI**：自定义 `MusicUiState` 密封类 + `AnimatedContent(targetState = uiState)` 统一承载 `Home`、`SongList`、`Search`、`Settings`、`PlaybackStats`，未使用 Navigation 组件（虽依赖 navigation-compose）。底部 `MiniPlayer` 与 `MainBottomNavigation` 是页面动画层的 sibling，不参与页面缩放。

- **底部毛玻璃约束**：`MainScreen` 页面内容层是唯一 `.haze(...)` source；`MiniPlayer` 在上层使用 `hazeChild(...)`。不要把 `MiniPlayer` 自身放进 Haze source，否则容易出现毛玻璃存在但控件内容不可见的问题。

- **服务**：`PlaybackService` (MediaBrowserService) 后台播放，`MediaControlManager` 管理媒体会话；`SystemMediaProgressSyncController` 在服务端定时同步播放进度到系统媒体状态/通知栏

- **无缝循环次数限制**：`SettingsManager.seamlessLoopCountLimit` 控制当前歌曲完成多少次无缝回绕后触发调度，默认 0 表示无限循环，上限 `SettingsManager.MAX_SEAMLESS_LOOP_COUNT_LIMIT`（当前 9999）。`PlaybackManager` 通过 native `EVENT_LOOP_JUMP` + 实际进度回绕确认来计数；达到上限后 LIST_LOOP/SHUFFLE 切下一首，SINGLE_LOOP 重新播放当前歌曲并重置计数。

- **播放统计**：`PlaybackStatsTracker` 只统计处于 `AudioPlayState.PLAYING` 的真实墙钟收听时长，数据由 `ListenStatsRepository` 写入 `filesDir/listen_stats.json` 的本地 ListenStatsStore schema 3。日期桶使用来源设备本地日期，并保留无日期时长；不统计播放次数或循环次数。GitHub 云端只接受 schema 2，按 exact wire identity 和 per-device/per-generation 累计 contribution、永久 generation tombstone 同步；可解析但未匹配的歌曲保留为 `boundSongId = 0` 的 typed node，无法安全解析的 payload 才进入 `unresolvedNodes`。本地 fuzzy relink 是非破坏性绑定，多个 wire identity 只在 presentation 层聚合。删除/丢失文件时保留历史并在统计页显示缺失状态，来源设备管理与清理入口位于 `GitHub 同步 → 数据管理`。完整规则见 [播放统计与 GitHub 同步 Schema V2](docs/2026-07-12_播放统计与GitHub同步SchemaV2.md)。

- **GitHub 同步**：设置页含 `GitHub 同步` 页面，用 GitHub Contents API 在单个 JSON 快照文件中同步歌单、循环点、评分和播放统计。`GitHubSyncCoordinator` 负责导出本地 → 下载远端 → 合并 → 应用 → 带 SHA 乐观锁上传；`RoomSyncSnapshotStore` 负责 Room 快照转换；`SharedPreferencesPlaylistIdMapper` 维护歌单本地 ID 与同步 ID 映射。同步不包含音频文件、播放队列、封面/格式展示字段或 App 设置。

- **自动同步**：`GitHubAutoSyncScheduler` + `GitHubAutoSyncWorker` 使用 WorkManager 周期任务实现，默认关闭；开启后在网络可用时约每小时同步一次。配置/token 不完整时 UI 不允许开启；清除 GitHub 配置会关闭并取消 WorkManager 任务。当前不做 mutation-triggered 同步，因为评分/歌单/循环点的本地修改入口尚未全部统一接入 mutation hook。

- **ViewModel/子管家**：`MainViewModel` 作为协调者，由 `MainViewModelFactory` 创建并通过属性赋值持有四个子管家（`LibraryViewModel`、`SelectionViewModel`、`PlaylistViewModel`、`LoopDetectionViewModel`）。注意：`LibraryViewModel` 与 `PlaylistViewModel` 是普通 class，共享 `MainViewModel.viewModelScope`；`SelectionViewModel` 与 `LoopDetectionViewModel` 继承 `ViewModel`，但也是由工厂直接创建后挂到 `MainViewModel` 上。自动循环点探测与试听调度由 `LoopDetectionViewModel` 管理，耗时调用需剥离出主线程以防止音频锁与 UI 锁争用。

- **Native 层**：`app/src/main/cpp/` — 包含两个核心引擎：
  1. **播放引擎**：Oboe 1.9.3 + NDK 解码器(minimp3)
  2. **探测引擎**：`loopfinder` (基于 FFT/Chroma 分析)
  - 统一通过 `NativeAudio.kt` 进行 JNI 桥接
  - 注意：`NativeAudio` 的 `init` 块中需同时加载 `seamlessloopmobile` 和 `loopfinder` 两个原生库

- **UI**：Jetpack Compose + Material3，状态通过 `MainViewModel` 的 LiveData、子管家的 StateFlow/LiveData、`MediaControlManager` 的 StateFlow/SharedFlow 驱动；引入 Haze 0.7.0 与 Coil 2.7.0，封面统一通过 `SongArtwork` 展示。

- **对话框**：统一 `MusicDialog` 密封类 + `CentralizedDialogHost` 集中管理

## 数据层（重要 — 近期大规模重构）

**数据库**：Room 2.7.0-alpha11，version 13，`fallbackToDestructiveMigration()`，DB 存在 `getExternalFilesDir(null)/databases/seamless_loop_db`

**3NF 表结构**（9 张表）：

| 表 | 实体 | 说明 |
|----|------|------|
| `Songs` | `SongEntity` | 主表，FK → Artists.Id, Albums.Id；索引：FilePath, FileName+duration, ArtistId, AlbumId, IsAbPartB；含封面 URI、MIME、采样率、码率展示字段 |
| `Artists` | `Artist` | — |
| `Albums` | `Album` | — |
| `LoopPoints` | `LoopPoint` | 1:1 与 Songs，FK CASCADE |
| `UserRatings` | `UserRating` | 1:1 与 Songs，FK CASCADE |
| `Playlists` | `Playlist` | — |
| `PlaylistItems` | `PlaylistItem` | 关联 Playlist↔Song，有 SortOrder |
| `PlaylistFolders` | `PlaylistFolder` | Playlist→Folder 映射 |
| `PlayQueue` | `PlayQueueItem` | 持久化当前播放队列 |

**DAO 层**（3 个，都在 `model/`）：

- `SongDao` — 最复杂的 DAO。含 `insertOrUpdateSong()`（双指纹匹配：优先 fileName+duration，回退 filePath）、`updateSongsMetadataBatch()`（批量同步，包含封面和音频格式展示字段）、`getOrCreateArtist/Album()`、`Song` POJO（`@Relation` 聚合 SongEntity+Artist+Album+LoopPoint+UserRating）
- `PlaylistDao` — 含 `clearAndSyncPlaylist()`、`addSongsToPlaylist()`（去重）
- `PlayQueueDao` — `replacePlayQueue()` 事务方法

**Repository 层**（`data/` + 子目录）：

- `MusicRepository` — Facade，聚合 3 个子 Repository + PlayQueueDao
- `SongRepository` — 歌曲 CRUD
- `PlaylistRepository` — 播放列表基础 CRUD 与歌单歌曲关联；不负责 A/B 检测或 PC 数据库匹配
- `LoopDetectionRepository` — **新版逻辑核心**！负责音频临时文件安全拷贝、跨线程 JNI 调用与 JSON 缓存管理。
- `MusicScannerRepository` — 扫描逻辑，含 `getInitialScannedSongs()`（全量扫描+批量更新+Artist/Album 预创建+A/B 标记+多级匹配）、`findAbPair()` / `findAbPairRobust()`（DB + MediaStore）
- `SettingsManager` — 单例 `getInstance(context)`，Gson 序列化，持久化 lastSongPath/lastPosition/playMode/isAbMode 等
- `SettingsManager` 同时持久化 `isSeamlessLoopEnabled`、`seamlessLoopCountLimit`、`themePreference`、`buttonHapticFeedbackEnabled`；循环次数上限 0 表示无限循环，设置 UI 需校验为 `0..MAX_SEAMLESS_LOOP_COUNT_LIMIT` 的整数。
- `data/stats/` — `TrackStat` + `ListenStatsRepository` + `ListenStatsStore`，使用本地 schema 3 JSON 保存真实收听时长、source-local 日期桶、无日期时长、设备/代际贡献、永久 tombstone 和 unresolved 节点。App 私有存储重置会创建新的 `deviceId`；应用内清除当前统计会 tombstone 当前 generation 并旋转到下一代。
- `data/sync/` — GitHub/云同步模型、portable identity、合并策略、同步协调器、数据管理仓库、WorkManager 自动同步 Worker；`github/` 是 GitHub Contents API 后端，`room/` 是 Room 快照转换与歌单 ID 映射。

**GitHub 同步注意事项**：
- Token 当前由 `SharedPreferencesGitHubSyncStore` 以 `MODE_PRIVATE` 明文保存，只是 MVP；后续应迁移到 EncryptedSharedPreferences/Android KeyStore。
- `GitHubSyncConfig.DEFAULT_BRANCH = "main"`，`DEFAULT_PATH = "seamless-loop/sync.json"`。
- 需要 `INTERNET` 权限；依赖 OkHttp 与 WorkManager (`work-runtime-ktx`)。
- 云端只接受 canonical schema 2：`playbackStatistics` 必须存在，`dateBucketBasis` 必须是 `sourceLocal`，其他 schema 直接拒绝。`prepareV2Egress()` 在上传前统一规范化快照。播放 wire identity 严格是 NFC + `Locale.ROOT` normalized basename 与 exact `durationMs`；`totalSamples` 仅为辅助字段。`RoomSyncSnapshotStore` 本地重绑定顺序为 exact duration、exact/tolerant samples ±10000、duration ±200ms、唯一同名，歧义即停止；绑定不改 wire identity 或 contributions，多个 wire identity 只在 presentation 聚合。非 key ACI metadata 使用确定性 reducer：有效原始 `fileName` 取 ordinal 最小值，`totalSamples` 与 `contentHash` 各取非空最大值。歌单/循环点/评分的通用 identity 可在 Gson 前 backfill `normalizedFileName`，播放 identity 不得 backfill。`seedCloudFromLocal()` 仅在云端文件不存在时创建初始快照，云端已有文件必须使用普通同步或先删除云端文件。
- 播放统计合并按 `(deviceId, generation)` 做累计最大值；永久 tombstone 抑制对应代际。来源设备完全删除的分组只存在于数据管理 UI，不创建额外 wire identity。应用 stale payload 时必须保留当前本地节点并合并同步期间产生的 current deltas。
- 循环点 `0/0` 与评分 `0` 视为未设置，不能覆盖远端/本地已有实质数据。
- 自动同步唯一任务名为 `com.cpu.seamlessloopmobile.GITHUB_AUTO_SYNC_PERIODIC`，周期 1 小时，网络可用约束，`ExistingPeriodicWorkPolicy.KEEP`。
- Worker 和手动同步当前没有共享同一个 coordinator mutex；依赖 GitHub SHA 乐观锁、Room 事务与下次周期同步收敛。若要更强一致性，需要新增跨入口同步锁。

**扫描流程**：
`AudioScanner.scan()` (MediaStore) → `MusicScannerRepository.getInitialScannedSongs()` → 多级匹配（优先 fileName+duration，兼顾 mediaId/filePath/容差匹配）→ Artist/Album 预创建 → 批量写入 → A/B 标记（文件后缀 _B/_b/_loop/_Loop 设 `isAbPartB=true`，被大多数查询过滤）

**PC 数据库同步**：
- `PcDatabaseImporter`（顶层 object）支持 3NF 和 flat 两种 schema，负责 PC song 匹配、循环点/候选循环点 JSON/评分/歌单导入；三级流程（预加载 → 容差匹配 → 事务批量写入）。导入时遵循“有实质循环点才覆盖、PC 评分为 0 不清空手机评分”的保护策略。测试时可以覆盖 `ioDispatcher`/`mainDispatcher` 属性为 `Dispatchers.Unconfined` 来避免死锁。
- `PcDatabaseExporter`（顶层 object）负责将手机端 Room 数据转换为 PC 端可识别的 3NF SQLite 数据库（`Tracks`/`LoopPoints.TrackId`/`UserRatings.TrackId`/`Playlists`/`PlaylistItems` 等），不是原样复制手机 Room 文件。导出时会将手机端 `loopStart`/`loopEnd`/`score`/`noteDiff` 候选点 JSON 转换为 PC 端 `LoopStart`/`LoopEnd`/`Score`/`NoteDifference` 键名。

## 开发注意事项

- **Kotlin 源码目录**：所有 Kotlin 文件实际放在 `app/src/main/java/` 下（非 `kotlin/`），这是历史遗留的目录结构。搜索源码时需注意。
- **Kotlin Android 插件被注释**：`app/build.gradle.kts` 中 `alias(libs.plugins.kotlin.android)` 被注释掉了（AGP 9.x 内置 Kotlin 支持 + compose-compiler 插件足以编译）。
- **Compose compiler 插件**：根 `build.gradle.kts` 与 `app/build.gradle.kts` 使用 `alias(libs.plugins.compose.compiler)`，不要删除；Layout Inspector 依赖 `includeSourceInformation=true`。
- **Room KSP**：`ksp { arg("room.generateKotlin", "true") }`，构建前需生成代码
- **Robolectric 测试**：`@Config(sdk = [34])`，JVM 模拟 Android 环境
- **CMake**：3.22.1，C++17，prefab 启用
- **配置缓存**：`org.gradle.configuration-cache=true`（默认开启，缓存问题可临时禁用）
- **调试安装**：`android.injected.testOnly=false` 解决调试弹窗
- **JVM 参数**：`org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8 -XX:MaxMetaspaceSize=512m -Xss4m`
- **KSP 兼容**：`android.disallowKotlinSourceSets=false` 解决 KSP 与 AGP 9.0+ 内置 Kotlin 的 sourceSets 兼容冲突
- **代码风格**：`kotlin.code.style=official`
- **双指纹去重**：`insertOrUpdateSong` 先用 fileName+duration 匹配，失败再用 filePath 匹配
- **Native 库加载**：`NativeAudio` 需同时加载 `seamlessloopmobile` 和 `loopfinder` 两个原生库
- **A/B 过滤**：含 `isAbPartB=true` 的歌曲默认在 UI 列表查询中被排除（由 `SongDao` 查询逻辑保证）
- **GEMINI.md**：为 `AGENTS.md` 的过时副本，应忽略之。
- **PcDatabaseImporter 测试**：需要 `app/src/test/resources/pc_db_samples/pc_3nf_sample.db` 存在（目前仅 `pc_3nf_sample.db` 存在，`pc_flat_sample.db` 不存在），测试中会覆盖 `ioDispatcher` 和 `mainDispatcher`。
- **docs/ 目录**：主要是阶段记录/研究日志，可能过时；改架构说明时优先更新本文件，除非用户明确要求，不要批量改写 `docs/`。
- **快速部署**：项目根目录下的 `run.bat` 提供 root 设备的 adb push + pm install + am start 一键部署流程。
- **播放页进度条约束**：全屏播放页 `PlaybackProgressBar` 需要直接轮询 `NativeAudio` 以保证拖动低延迟；不要将其完全改成依赖 `MediaControlManager`/MediaSession 状态流。清除暂停通知导致 native engine 销毁时，应保留 UI 当前进度或使用 MediaSession 进度兜底，避免用 native 返回的 0 覆盖进度。
- **页面动画约束**：主页面、歌曲列表、搜索、设置、统计页都通过 `MainScreen` 顶层 `AnimatedContent(targetState = uiState)` 做统一 scale/fade 切换。不要再额外叠一层二级页 overlay 动画，否则回媒体库时会退化成“底层露出”而不是媒体库入场。
- **⚠️ 物理路径与 JNI 避坑硬约束**：探测引擎的 C++ `fopen` 无法直接读取 `content://` 或 MediaStore URI！自动探测循环点时，必须先使用 `LoopDetectionRepository` 将音频拷贝至私有 cache 目录，再将物理路径传入 `NativeAudio.analyzeLoopPoints`；分析完毕后必须在 `finally` 块中立即彻底删除临时文件，防止磁盘残留。

## 包结构速查

| 路径 | 职责 |
|------|------|
| `audio/` | PlaybackService, PlaybackManager (IMultiPlayer), MediaControlManager, QueueManager, AudioFocusManager, Notify, HeadsetPlugReceiver, SystemMediaProgressSyncController, MediaSessionPlaybackStateThrottler；`timer/` 睡眠定时器契约；`effects/` 音效控制契约；`stats/` 播放统计追踪 |
| `data/` | MusicRepository + SongRepository + PlaylistRepository + MusicScannerRepository + SettingsManager + LoopDetectionRepository；`stats/` 收听时长 JSON 仓库；`sync/` GitHub 同步契约、合并、后端、Room 快照与自动同步 |
| `db/` | AppDatabase + DateConverter + PcDatabaseImporter + PcDatabaseExporter（实体/DAO 已移到 model/） |
| `model/` | 9 个 Room 实体 + 3 个 DAO + Song(Lookup POJO) + `SongMetadataUpdate`（定义在 Song.kt）+ LibraryItem + Folder 等 15 个 `.kt` 文件 |
| `ui/screen/` | MainScreen + MainAppBar + MainTabsPager + PlaylistTabScreen + search/settings/stats/songlist 子目录 |
| `ui/components/` | app/common/dialogs 三类组件目录：CentralizedDialogHost, MiniPlayer, MainBottomNavigation, PlayingPanel, MultiSelectBar, PlayingComponents, SongArtwork, FineTuneComponents, ListItems, LoopCandidatesDialog 等 |
| `ui/state/` | DataUiState 等 UI 数据状态封装 |
| `viewmodel/` | MainViewModel + LibraryViewModel + SelectionViewModel + PlaylistViewModel + LoopDetectionViewModel + MainViewModelFactory + MusicDialog |
| `scanner/` | AudioScanner（Object，MediaStore 扫描；采样点在后续原生层处理） |
| `jni/` | NativeAudio（Kotlin object，JNI 入口）、LoopPoint（原生层返回的数据类） |
| `utils/` | TimeUtils, HapticFeedback |

## 注意

**AB式音乐**在本项目专指一首可循环歌曲分为两个音乐文件，A段是intro，B段是loop，与其他的一首可循环歌曲一个音乐文件不同。
文件名含 `_B`、`_b`、`_loop`、`_Loop` 后缀的文件被标记为 B 段（`isAbPartB=true`），在 UI 列表中默认隐藏。

命名避坑：`model/LoopPoint.kt` 是 Room 实体；`jni/LoopPoint.kt` 是原生循环点分析返回的数据类，二者不要混用。
