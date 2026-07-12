# 播放统计与 GitHub 同步 Schema V2

## 1. 范围与当前状态

本文是当前 Android 实现的权威说明和用户参考，适用于 2026-07-12 的代码状态。

- 本地播放统计持久化格式是 `ListenStatsStore.SCHEMA_VERSION = 3`。
- GitHub 单文件快照格式严格是 schema 2；线上字段名为 `playbackStatistics`。
- 当前正式同步内容包括歌单、循环点、评分和播放统计。
- 同步不上传音频文件，也不复制 Room 数据库或设备设置。
- 当前只接受本地 schema 3 与云端 schema 2，不声明旧格式兼容或迁移能力。

相关实现集中在 `app/src/main/java/com/cpu/seamlessloopmobile/data/stats/` 和
`app/src/main/java/com/cpu/seamlessloopmobile/data/sync/`。

## 2. 用户可见的同步内容

### 会同步

- 歌单、歌单名称及歌曲顺序。
- 有实质值的循环点；`loopStart = 0` 且 `loopEnd = 0` 表示未设置。
- 非零评分；评分 `0` 表示未设置。
- 播放统计：歌曲身份、设备目录、按设备和代际累计的收听贡献、日期桶、无日期时长和永久代际 tombstone。

### 不会同步

- 音频文件本体、文件 URI/path 和播放队列。
- 播放位置、播放模式、无缝循环开关及其他 App 设置。
- 封面 URI、MIME、采样率、码率等扫描展示字段。
- Room 数据库文件、设备本地缓存和 GitHub token。

云端快照是可移植的 JSON，不是任意一台手机 Room 文件的备份。

## 3. 本地 ListenStatsStore schema 3

本地文件位于 `filesDir/listen_stats.json`，由 `ListenStatsRepository` 和
`ListenStatsStore` 管理。统计只累计 `AudioPlayState.PLAYING` 状态下的真实墙钟收听时长，不统计播放次数或循环次数。

schema 3 的主要结构如下：

| 字段/节点 | 作用 |
| --- | --- |
| `schemaVersion` | 固定为 `3`。 |
| `devices` | 已知来源设备的 `deviceId`、展示名、平台、首次/最近出现时间和当前代际。 |
| `currentDeviceId` / `currentGeneration` | 当前安装实例使用的设备和代际。 |
| `songs` | 本地歌曲统计节点，保存线上身份、可选的 Room `boundSongId` 和展示元数据；未匹配节点使用 `boundSongId = 0`。 |
| `contributions` | `(deviceId, generation)` 唯一的累计贡献。 |
| `tombstones` | 永久屏蔽某个设备代际的删除记录。 |
| `unresolvedNodes` | 无法安全转换为 typed song node 的原始 payload，供后续恢复或诊断。 |

每个 contribution 包含：

- `dailyListenMs`：按来源设备本地日期保存的 `YYYY-MM-DD -> milliseconds` 桶。
- `undatedListenMs`：无法归入日期桶的历史时长，不能因为日期缺失而丢弃。
- `firstPlayedAtUtcMs`、`lastPlayedAtUtcMs`、`updatedAtUtcMs`：UTC 时间戳。

日期桶的含义是 `sourceLocal`：日期使用产生该收听记录的来源设备本地时区，而不是合并设备的时区。日、周、月、年和总排行在本地展示层计算。

## 4. 云端 schema 2 与 canonical `playbackStatistics`

`SyncSnapshot.schemaVersion` 必须为 `2`，且必须存在对象字段 `playbackStatistics`。上传前由 `prepareV2Egress()` 和序列化器生成 canonical 形式。

`playbackStatistics` 的主要结构为：

```json
{
  "dateBucketBasis": "sourceLocal",
  "devices": [],
  "songs": [
    {
      "song": {
        "fileName": "Example.ogg",
        "normalizedFileName": "example.ogg",
        "durationMs": 123456,
        "totalSamples": 12345678
      },
      "contributions": []
    }
  ],
  "tombstones": []
}
```

`devices`、歌曲和 contribution 会按稳定规则排序；日期桶按日期排序。每个歌曲身份和每个 `(deviceId, generation)` contribution 在同一个 `playbackStatistics` 中只能出现一次。

### 累计贡献与代际

一个设备的一次安装实例使用一个 `deviceId`，并在同一设备上用 `generation` 区分清除前后的历史。贡献是单调累计值，合并时对每个日期桶和 `undatedListenMs` 取最大值，而不是把两个快照简单相加。这样同一批数据被重复上传不会重复计时。

清理当前设备统计时，应用会：

1. 为当前 `(deviceId, generation)` 写入 tombstone。
2. 持久化 tombstone。
3. 将当前 generation 旋转到下一代。
4. 之后的新收听写入新代际。

tombstone 是永久的。它会阻止该代际的 contribution 重新进入合并结果，也会使使用旧 write fence 的延迟播放写入失效。远端删除来源历史同样按代际 tombstone 表示，不删除线上其他设备的贡献。

### 严格线上歌曲身份

播放统计的 wire identity 精确为：

```text
(NFC + Locale.ROOT normalized basename, exact durationMs)
```

也就是文件 basename 经 trim、Unicode NFC 规范化、`Locale.ROOT` 小写化，再与精确的 `durationMs` 组成主键。`totalSamples` 是辅助信息，绝不能成为 wire identity 的组成部分；同一首歌在 Android/native 与桌面端可能产生略有差异的 sample 数。

线上 identity 和 contribution 一旦写入，不能因为本地重新扫描、绑定或展示元数据刷新而被改写或拆分。

## 5. 本地重绑定与展示聚合

云端歌曲应用到本地时，`RoomSyncSnapshotStore` 只做本地匹配，不改变 wire identity 或 contribution。匹配顺序如下，每一级只有唯一候选才绑定：

1. 精确 basename + 精确 `durationMs`。
2. 精确 basename + 精确 `totalSamples`。
3. 同名且 `totalSamples` 差值不超过 `10000`。
4. 同名且 `durationMs` 差值不超过 `200ms`。
5. 唯一同名候选。

任一级出现多个候选都会停止该级并继续后续规则；最终同名仍有歧义时保持未绑定。重新扫描或歌曲出现后会再次尝试绑定。绑定只更新本地 `boundSongId`、路径、标题、艺术家、专辑和封面等展示关系。

多个 wire identity 可以绑定到同一个本地 Room song。它们只在播放统计页等 presentation 层按本地歌曲聚合，云端仍分别保留各自 identity 和 contribution；presentation 聚合不能反向合并或改写 wire 数据。

可解析但无法匹配的歌曲仍保留为 typed song node，并设置 `boundSongId = 0`；重新扫描后会再次尝试绑定。文件暂时缺失、被删除或名称发生变化时，历史仍保留并在统计页显示缺失状态。无法安全解析的原始 payload 才保留在 `unresolvedNodes`，且不会作为有效统计再次导出。

## 6. 确定性 ACI 元数据归约

多个来源为同一个 exact wire key 提供了不同的非 key metadata 时，`reducePlaybackSongIdentity()` 使用确定性规则：

- `fileName` 选择规范化结果仍匹配 key 的有效原始名称中，按 Kotlin `String.compareTo` 的 UTF-16 字典序最小者。
- `normalizedFileName` 和 `durationMs` 始终来自 stable key。
- `totalSamples` 取最大的非空值。
- `contentHash` 取最大的非空字符串。

这是非 key metadata 的确定性 reduction，不是“远端优先”；远端原始 sample 数不会因为来源位置而优先覆盖本地值。

## 7. 设备身份、展示名与来源删除

`SharedPreferencesGitHubSyncStore` 在 App 私有存储中保存随机 UUID 形式的 `deviceId`。设备展示名、平台及时间戳用于来源管理和统计展示，不能用展示名替代 `deviceId`。清空 App 存储会一并清除该 ID，因此下一次启动会创建新的 `deviceId`；这属于新来源设备，不会自动继承旧来源的代际。

“GitHub 同步 → 数据管理”会按来源设备汇总当前仍有效的 contribution。用户删除来源设备历史时，底层仍按该设备已知的每个 generation 写入 tombstone。完全删除的来源只在 UI 中作为来源汇总/历史状态分组，不会在 wire schema 中创建一个额外的“已删除来源”身份。

应用内清除当前播放统计会旋转 generation；清除歌单、循环点或评分则清除对应 Room 数据并清理同步元数据。清除本地数据不会删除音频文件。

## 8. 手动同步、自动同步与首次初始化

### 普通手动同步

`GitHubSyncCoordinator` 的普通流程是：导出本地 → 下载云端 → 校验 schema 2 → 合并歌单/循环点/评分/播放统计 → 应用合并快照 → 带 SHA 上传 → 保存最新 revision 和同步时间。

云端文件不存在时，普通同步会把本地 schema 2 快照作为初次内容上传。云端已存在时，始终使用双向合并，不会用本机数据无条件覆盖云端。

### 自动同步

自动同步默认关闭。配置完整且用户开启后，`GitHubAutoSyncScheduler` 使用 WorkManager 注册唯一任务 `com.cpu.seamlessloopmobile.GITHUB_AUTO_SYNC_PERIODIC`：约每小时一次，要求网络可用，使用 `ExistingPeriodicWorkPolicy.KEEP`。清除 GitHub 配置会关闭并取消该任务。当前没有对每一次歌单/循环点/评分修改立即触发同步的统一入口，因此自动同步仍是周期任务。

### Seed-cloud 安全规则

“数据管理 → 用本机数据初始化云端”只允许在 GitHub 文件确实 `NOT_FOUND` 时执行。若云端文件已经存在，即使用户希望覆盖，也必须使用普通同步，或先明确删除云端文件再重新 seed；seed 不携带 expected SHA，也不会绕过这个存在性检查。

## 9. 并发、冲突与增量保护

- 同一 `GitHubSyncCoordinator` 内用 `Mutex` 串行化同步。
- GitHub Contents API 上传携带下载到的文件 SHA 作为 expected revision。SHA 冲突时重新下载远端，重新合并并重试，避免覆盖其他设备的更新。
- 同步开始时记录 `mutationVersion`。导出、合并、应用或上传前发现本地 mutationVersion 已变化，会返回 `LocalMutationDuringSync`，不继续发布过时结果。
- 手动同步和 WorkManager Worker 当前各自创建 coordinator，没有共享跨入口 mutex；依靠 SHA 乐观锁、Room 事务和下一轮同步收敛。
- 应用 stale payload 时，`ListenStatsRepository.applyLocalPayload()` 会保留当前本地节点，并按 `(deviceId, generation)` 的累计最大值合并同步期间产生的新 contribution；不会让旧导出覆盖较新的本地收听记录。

## 10. 严格校验与 identity backfill

`SyncSnapshotSerializer` 在 Gson 反序列化前校验：

- JSON 必须是对象，`schemaVersion` 必须为 `2`。
- 必须使用 `playbackStatistics`；旧别名 `playbackStats`、其他 schema 和缺失字段直接拒绝。
- `dateBucketBasis` 必须是 `sourceLocal`。
- 身份、设备、代际、时间、时长、sample 数和收听时长不能为负；日期桶必须是合法 ISO 日期。
- playback songs、contributions 和 tombstones 不能有重复 stable key。
- playback 的 `normalizedFileName` 必须严格等于 `fileName` 的 NFC + `Locale.ROOT` 规范化值。

只有通用的歌单/循环点/评分歌曲 identity 允许在 Gson 之前补写缺失的 `normalizedFileName`，再进行规范化校验。播放统计 identity 不做 backfill，缺失或不一致直接失败，以保证线上播放主键严格不变。

## 11. 故障排查

| 现象 | 处理 |
| --- | --- |
| 无法开启自动同步 | 检查 Owner、Repository、Branch、Path 和 token 是否都已保存；自动同步默认关闭。 |
| `NOT_FOUND` | 确认仓库、分支和 path；若确实是首次使用，可执行普通同步或 seed。 |
| 云端已存在但 seed 失败 | 这是保护行为。使用普通“立即同步”，或确认后删除云端文件再 seed。 |
| `Unsupported remote schema version` / `INVALID_REMOTE` | 云端不是严格 schema 2，先备份并检查 JSON；不要把旧格式强行改名后上传。 |
| `playbackStatistics` 校验失败 | 检查字段名、`sourceLocal`、规范化文件名、负数、重复 identity/contribution 和非法日期。 |
| 统计显示未绑定或缺失 | 重新扫描本地音乐；匹配只按本文第 5 节规则进行，歧义不会强行绑定。 |
| 同步提示本地变更 | 同步期间发生了新的本地 mutation，重新执行“立即同步”。 |
| 上传冲突 | SHA 已变化，协调器会自动重试；连续失败时检查网络和是否有其他客户端持续写入同一文件。 |
| 清空 App 后来源变多 | App 私有存储重置会创建新的 `deviceId`，旧来源历史仍由 wire tombstone/贡献保留。 |

## 12. 相关文件、测试与命令

主要实现文件：

- `app/src/main/java/com/cpu/seamlessloopmobile/data/stats/ListenStatsStore.kt`
- `app/src/main/java/com/cpu/seamlessloopmobile/data/stats/ListenStatsRepository.kt`
- `app/src/main/java/com/cpu/seamlessloopmobile/data/sync/SyncModels.kt`
- `app/src/main/java/com/cpu/seamlessloopmobile/data/sync/SyncSongIdentityKey.kt`
- `app/src/main/java/com/cpu/seamlessloopmobile/data/sync/SyncMergeEngine.kt`
- `app/src/main/java/com/cpu/seamlessloopmobile/data/sync/SyncSnapshotSerializer.kt`
- `app/src/main/java/com/cpu/seamlessloopmobile/data/sync/GitHubSyncCoordinator.kt`
- `app/src/main/java/com/cpu/seamlessloopmobile/data/sync/SyncDataManagementRepository.kt`
- `app/src/main/java/com/cpu/seamlessloopmobile/data/sync/room/RoomSyncSnapshotStore.kt`
- `app/src/main/java/com/cpu/seamlessloopmobile/data/sync/SharedPreferencesGitHubSyncStore.kt`

重点测试：

- `ListenStatsRepositoryTest`
- `SyncMergeEngineTest`
- `SyncSnapshotSerializerTest`
- `GitHubSyncCoordinatorTest`
- `SyncDataManagementRepositoryTest`
- `RoomSyncSnapshotStoreTest`

常用验证命令：

```powershell
.\gradlew.bat -q testDebugUnitTest --tests "com.cpu.seamlessloopmobile.data.stats.ListenStatsRepositoryTest"
.\gradlew.bat -q testDebugUnitTest --tests "com.cpu.seamlessloopmobile.data.sync.SyncMergeEngineTest"
.\gradlew.bat -q testDebugUnitTest --tests "com.cpu.seamlessloopmobile.data.sync.SyncSnapshotSerializerTest"
.\gradlew.bat -q testDebugUnitTest --tests "com.cpu.seamlessloopmobile.data.sync.GitHubSyncCoordinatorTest"
.\gradlew.bat -q testDebugUnitTest --tests "com.cpu.seamlessloopmobile.data.sync.SyncDataManagementRepositoryTest"
.\gradlew.bat -q testDebugUnitTest --tests "com.cpu.seamlessloopmobile.data.sync.room.RoomSyncSnapshotStoreTest"
.\gradlew.bat -q testDebugUnitTest
.\gradlew.bat -q assembleDebug
```

本文与工程约束见 [AGENTS.md](../AGENTS.md)；用户入口和配置步骤见 [README.md](../README.md)。
