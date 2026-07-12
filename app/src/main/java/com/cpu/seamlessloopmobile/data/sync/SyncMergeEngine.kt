package com.cpu.seamlessloopmobile.data.sync

/**
 * 合并操作的结果。
 * @property snapshot 合并后的快照（保留本地 deviceId 和 exportedAt）
 * @property report 合并操作报告
 */
data class MergeResult(
    val snapshot: SyncSnapshot,
    val report: SyncReport
)

/**
 * 双向同步合并引擎。
 *
 * 接收远端（可能为 null）和本地快照，按稳定歌曲身份标识和歌单 ID 进行合并，
 * 使用可注入的策略对象处理各数据类型的冲突。
 */
class SyncMergeEngine(
    private val playlistPolicy: PlaylistMergePolicy = DefaultPlaylistMergePolicy,
    private val loopPointPolicy: LoopPointMergePolicy = DefaultLoopPointMergePolicy,
    private val ratingPolicy: RatingMergePolicy = DefaultRatingMergePolicy
) {

    /**
     * 合并远端和本地快照。
     * - 如果远端为 null，直接返回本地快照（首次同步）
     * - 按歌单 ID 合并播放列表
     * - 按 fileName(忽略大小写)+durationMs 合并循环点和评分；totalSamples 仅作辅助匹配字段
     * - 合并结果使用 schema 2，并保留本地 deviceId、exportedAt
     *
     * @param remote 来自远端的快照（可能为 null）
     * @param local 本地导出的快照
     * @return 合并后的快照及报告
     */
    suspend fun merge(remote: SyncSnapshot?, local: SyncSnapshot): MergeResult {
        if (remote == null) {
            return MergeResult(
                snapshot = local.prepareV2Egress(),
                report = SyncReport(
                    playlistsUploaded = local.playlists.size,
                    loopPointsUploaded = local.loopPoints.size,
                    ratingsUploaded = local.ratings.size
                )
            )
        }

        val conflicts = mutableListOf<SyncConflict>()

        val mergedPlaylists = mergePlaylists(remote.playlists, local.playlists, conflicts)
        val mergedLoopPoints = mergeEntries(
            remote.loopPoints,
            local.loopPoints,
            { it.song.stableKey() },
            { r, l ->
                val merged = loopPointPolicy.resolve(r?.loopPoint, l?.loopPoint)
                val song = preferStableSongIdentity(remote = r?.song, local = l?.song)
                if (song != null && merged != null) SyncLoopPointEntry(song, merged) else null
            },
            conflicts
        )
        val mergedRatings = mergeEntries(
            remote.ratings,
            local.ratings,
            { it.song.stableKey() },
            { r, l ->
                val merged = ratingPolicy.resolve(r?.rating, l?.rating)
                val song = preferStableSongIdentity(remote = r?.song, local = l?.song)
                if (song != null && merged != null) SyncRatingEntry(song, merged) else null
            },
            conflicts
        )

        val mergedSnapshot = SyncSnapshot(
            schemaVersion = SYNC_SCHEMA_VERSION_V2,
            deviceId = local.deviceId,
            exportedAt = local.exportedAt,
            playlists = mergedPlaylists,
            loopPoints = mergedLoopPoints,
            ratings = mergedRatings,
            playbackStats = mergePlaybackStatistics(remote.playbackStats, local.playbackStats)
        ).canonicalized()

        val report = SyncReport(
            playlistsUploaded = local.playlists.size,
            playlistsDownloaded = remote.playlists.size,
            loopPointsUploaded = local.loopPoints.size,
            loopPointsDownloaded = remote.loopPoints.size,
            ratingsUploaded = local.ratings.size,
            ratingsDownloaded = remote.ratings.size,
            conflicts = conflicts
        )

        return MergeResult(mergedSnapshot, report)
    }

    // -------------------------------------------------------------------
    // 内部辅助方法
    // -------------------------------------------------------------------

    /**
     * 按歌单 ID 合并播放列表。
     */
    private fun mergePlaylists(
        remote: List<SyncPlaylist>,
        local: List<SyncPlaylist>,
        conflicts: MutableList<SyncConflict>
    ): List<SyncPlaylist> {
        val localById = local.associateBy { it.id }
        val remoteById = remote.associateBy { it.id }
        val allIds = (localById.keys + remoteById.keys).toSet()

        return allIds.mapNotNull { id ->
            val localPlaylist = localById[id]
            val remotePlaylist = remoteById[id]

            if (localPlaylist == null) return@mapNotNull remotePlaylist?.withoutDuplicateStableItems()
            if (remotePlaylist == null) return@mapNotNull localPlaylist.withoutDuplicateStableItems()

            // Record conflict if names differ
            if (localPlaylist.name != remotePlaylist.name) {
                conflicts.add(
                    SyncConflict(
                        playlistName = localPlaylist.name,
                        remoteValue = remotePlaylist.name,
                        localValue = localPlaylist.name,
                        resolution = if (remotePlaylist.modifiedAt >= localPlaylist.modifiedAt)
                            "remote" else "local"
                    )
                )
            }
            playlistPolicy.resolve(remotePlaylist, localPlaylist).withoutDuplicateStableItems()
        }.sortedBy { it.name }
    }

    private fun SyncPlaylist.withoutDuplicateStableItems(): SyncPlaylist =
        copy(items = items.distinctBy { it.song.stableKey() })

    private fun mergePlaybackStatistics(
        remote: SyncPlaybackStats,
        local: SyncPlaybackStats
    ): SyncPlaybackStats {
        val tombstones = (remote.tombstones + local.tombstones)
            .groupBy { it.deviceId to it.generation }
            .map { (_, entries) -> entries.maxWith(compareBy<SyncPlaybackStatsTombstone> { it.tombstonedAtUtcMs }
                .thenBy { it.tombstonedByDeviceId }.thenBy { it.reason }) }
        val suppressed = tombstones.map { it.deviceId to it.generation }.toSet()
        val devices = (remote.devices + local.devices)
            .groupBy { it.deviceId }
            .map { (_, entries) -> entries.reduce(::mergeDevice) }
        val songs = (remote.songs + local.songs)
            .groupBy { it.song.stableKey() }
            .map { (_, entries) ->
                val song = reducePlaybackSongIdentity(entries.map { it.song })
                val contributions = entries.flatMap { it.contributions }
                    .groupBy { it.deviceId to it.generation }
                    .filterKeys { it !in suppressed }
                    .map { (_, values) -> values.reduce(::mergeContribution) }
                SyncPlaybackStatsSong(song, contributions)
            }
        return SyncPlaybackStats(
            dateBucketBasis = SyncDateBucketBasis.SOURCE_LOCAL,
            devices = devices,
            songs = songs,
            tombstones = tombstones
        ).sorted()
    }

    private fun mergeDevice(
        first: SyncPlaybackStatsDevice,
        second: SyncPlaybackStatsDevice
    ): SyncPlaybackStatsDevice {
        val display = listOf(first, second).maxWith(
            compareBy<SyncPlaybackStatsDevice> { it.displayNameUpdatedAtUtcMs }
                .thenBy { it.displayName }
        )
        val platform = listOf(first, second).maxWith(
            compareBy<SyncPlaybackStatsDevice> { it.lastSeenAtUtcMs }
                .thenBy { it.platform }
        )
        return SyncPlaybackStatsDevice(
            deviceId = first.deviceId,
            displayName = display.displayName,
            firstSeenAtUtcMs = minOf(first.firstSeenAtUtcMs, second.firstSeenAtUtcMs),
            lastSeenAtUtcMs = maxOf(first.lastSeenAtUtcMs, second.lastSeenAtUtcMs),
            currentGeneration = maxOf(first.currentGeneration, second.currentGeneration),
            platform = platform.platform,
            displayNameUpdatedAtUtcMs = maxOf(
                first.displayNameUpdatedAtUtcMs,
                second.displayNameUpdatedAtUtcMs
            )
        )
    }

    private fun mergeContribution(
        first: SyncPlaybackStatsContribution,
        second: SyncPlaybackStatsContribution
    ): SyncPlaybackStatsContribution = SyncPlaybackStatsContribution(
        deviceId = first.deviceId,
        generation = first.generation,
        datedListenMs = (first.datedListenMs.keys + second.datedListenMs.keys).associateWith { date ->
            maxOf(first.datedListenMs[date] ?: 0L, second.datedListenMs[date] ?: 0L)
        },
        undatedListenMs = maxOf(first.undatedListenMs, second.undatedListenMs),
        firstPlayedAtUtcMs = earliestMeaningful(
            first.firstPlayedAtUtcMs,
            second.firstPlayedAtUtcMs
        ),
        lastPlayedAtUtcMs = maxOf(first.lastPlayedAtUtcMs, second.lastPlayedAtUtcMs),
        updatedAtUtcMs = maxOf(first.updatedAtUtcMs, second.updatedAtUtcMs)
    )

    private fun earliestMeaningful(first: Long, second: Long): Long = when {
        first == 0L -> second
        second == 0L -> first
        else -> minOf(first, second)
    }

    /**
     * 泛型条目合并辅助方法。
     * @param remote 远端条目列表
     * @param local 本地条目列表
     * @param keySelector 从条目中提取匹配键的函数
     * @param resolver 合并两个可选条目为一个可选结果
     * @param conflicts 冲突列表（用于记录）
     */
    private fun <T, K> mergeEntries(
        remote: List<T>,
        local: List<T>,
        keySelector: (T) -> K,
        resolver: (T?, T?) -> T?,
        conflicts: MutableList<SyncConflict>
    ): List<T> {
        val localByKey = local.associateBy(keySelector)
        val remoteByKey = remote.associateBy(keySelector)
        val allKeys = (localByKey.keys + remoteByKey.keys).toSet()

        return allKeys.mapNotNull { key ->
            val localEntry = localByKey[key]
            val remoteEntry = remoteByKey[key]
            resolver(remoteEntry, localEntry)
        }
    }
}
