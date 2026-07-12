# 2026-07-06 NeriPlayer 风格 UI 与播放统计改造

## 背景

本轮改造以 `temp/NeriPlayer-master` 为视觉与交互参考，但保留本项目自定义 `MusicUiState` 导航、Native Oboe 播放核心和现有主色系。目标是把主界面从单一媒体库页扩展为更完整的本地播放器体验：底部主导航、独立搜索/设置/统计页面、毛玻璃迷你播放器、封面图、音频文件信息、触感反馈和真实播放时长统计。

## 主要变更

### 主界面与导航

- `MainScreen` 改为用单个 `AnimatedContent(targetState = uiState)` 承载 `Home`、`SongList`、`Search`、`Settings`、`PlaybackStats` 等页面。
- 页面切换统一为向外弹出的 `scaleIn + fadeIn` / `scaleOut + fadeOut`，因此从二级页回媒体库时媒体库本身也会作为 incoming page 入场，而不是只被露出来。
- 底部 `MiniPlayer` 和 `MainBottomNavigation` 是页面动画层的 sibling，不参与页面缩放，避免切页时底部控件闪烁或被 Haze 采样层吞掉。
- 新增 `MainBottomNavigation`，底部主入口为 `媒体库 / 搜索 / 设置`；统计页不高亮任何底部主入口。

### Haze 与 MiniPlayer

- 引入 Haze `0.7.0`，页面内容层作为唯一 `.haze(...)` source。
- `MiniPlayer` 在上层使用 `hazeChild(...)`，保持真实背景采样；不要把 `MiniPlayer` 自身放进 `.haze(...)` source 内。
- `MiniPlayer` 保留上一首/播放暂停/下一首，叠加播放进度、封面图、标题和时间信息。

### 搜索、设置与主题

- `SearchScreen` 成为独立页面，不自动聚焦输入框，避免进入搜索页时自动弹出键盘。
- 删除旧 `SettingsDrawer`，设置改为二级页面结构，覆盖主题、数据、播放、界面等分组入口。
- 新增 `ThemePreference`：`跟随系统 / 浅色 / 深色`，由 `SettingsManager.themePreference` 持久化。
- 新增按钮触感反馈开关，设置项为 `点击触感反馈`，通过 `LocalButtonHapticFeedbackEnabled` 和 `rememberHapticClick` 下发到常用按钮。

### 播放页、封面与音频元数据

- Room 数据库版本升至 `13`，`SongEntity` / `Song` / `SongMetadataUpdate` 增加 `coverPath`、`mimeType`、`sampleRateHz`、`bitrateKbps` 等展示字段。
- `AudioScanner` 通过 MediaStore 与 `MediaExtractor` 写入封面 URI、MIME、采样率和码率；`MusicScannerRepository` 在扫描合并时保留并刷新这些字段。
- 新增 `SongArtwork`，供 `MiniPlayer`、歌曲列表、全屏播放页复用。
- `PlayingPanel` 增加封面、文件信息行、改进后的进度条、循环点标记和更大的顶部按钮点击区域。

### 播放统计

- 新增 `TrackStat`、`ListenStatsRepository`、`PlaybackStatsTracker`。
- 统计数据存储在 `filesDir/listen_stats.json`，不进入 Room。
- 只统计真实处于 `PLAYING` 状态的墙钟播放时长；不统计播放次数、不统计循环次数。
- `PlaybackService` 在播放、暂停、停止、切歌和销毁时刷新统计。
- 新增 `PlaybackStatsScreen`：总览、Top 5 横向条形图、按累计收听时长排序的排行列表；歌曲文件缺失时保留历史并显示缺失状态。
- 设置页新增清除播放统计入口，清除前有确认。

### 预留架构契约

- 新增睡眠定时器模型与管理器：`audio/timer/*`。
- 新增音效控制契约：`audio/effects/*`，当前为 No-Op 实现，保留均衡器/预设/配置模型。
- 新增同步契约与合并策略：`data/sync/*`，为后续 GitHub/云同步预留 portable identity 和 merge policy。
- 新增 `MediaSessionPlaybackStateThrottler`，约束系统媒体状态刷新频率。

## 测试与验证

- 新增或更新单元测试：
  - `SleepTimerManagerTest`
  - `MediaSessionPlaybackStateThrottlerTest`
  - `SystemMediaProgressSyncControllerTest`
  - `SyncMergePolicyTest`
  - `PlaybackStatsTrackerTest`
- 本轮由用户侧编译与真机视觉验证；本地只执行 `git diff --check`，未本地运行 Gradle 构建。

## 后续维护注意

- 页面切换动画现在由 `MainScreen` 顶层 `AnimatedContent(targetState = uiState)` 统一管理，避免再额外叠加二级页 overlay 动画。
- Haze 层级必须保持“页面内容是 source，MiniPlayer 是上层 hazeChild sibling”。如果把 MiniPlayer 放进 source，容易出现毛玻璃存在但控件内容不可见的问题。
- 播放统计的排序语义是累计真实收听时长，不要引入播放次数或循环次数作为排行指标。
- 音频探测仍必须遵守 Native `fopen` 限制：传给 `NativeAudio.analyzeLoopPoints` 的必须是私有 cache 临时物理路径，并在 `finally` 中清理。
