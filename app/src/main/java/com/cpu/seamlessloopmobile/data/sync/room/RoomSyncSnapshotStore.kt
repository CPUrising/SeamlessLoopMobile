package com.cpu.seamlessloopmobile.data.sync.room

import androidx.room.withTransaction
import com.cpu.seamlessloopmobile.db.AppDatabase
import com.cpu.seamlessloopmobile.data.stats.ListenStatsContribution
import com.cpu.seamlessloopmobile.data.stats.ListenStatsDevice
import com.cpu.seamlessloopmobile.data.stats.ListenStatsLocalPayload
import com.cpu.seamlessloopmobile.data.stats.ListenStatsRepository
import com.cpu.seamlessloopmobile.data.stats.ListenStatsSongNode
import com.cpu.seamlessloopmobile.data.stats.ListenStatsSource
import com.cpu.seamlessloopmobile.data.stats.ListenStatsTombstone
import com.cpu.seamlessloopmobile.data.stats.ListenStatsUnresolvedNode
import com.cpu.seamlessloopmobile.data.stats.normalizedStatsFileName
import com.cpu.seamlessloopmobile.model.Playlist
import com.cpu.seamlessloopmobile.model.PlaylistDao
import com.cpu.seamlessloopmobile.model.Song
import com.cpu.seamlessloopmobile.model.SongDao
import com.cpu.seamlessloopmobile.model.LoopPoint
import com.cpu.seamlessloopmobile.model.UserRating
import com.cpu.seamlessloopmobile.data.sync.SyncConflict
import com.cpu.seamlessloopmobile.data.sync.SyncLoopPoint
import com.cpu.seamlessloopmobile.data.sync.SyncLoopPointEntry
import com.cpu.seamlessloopmobile.data.sync.SyncPlaylist
import com.cpu.seamlessloopmobile.data.sync.SyncPlaylistItem
import com.cpu.seamlessloopmobile.data.sync.SyncRating
import com.cpu.seamlessloopmobile.data.sync.SyncRatingEntry
import com.cpu.seamlessloopmobile.data.sync.SyncReport
import com.cpu.seamlessloopmobile.data.sync.SyncSnapshot
import com.cpu.seamlessloopmobile.data.sync.SyncSongIdentity
import com.cpu.seamlessloopmobile.data.sync.SyncSongStableKey
import com.cpu.seamlessloopmobile.data.sync.SyncSnapshotStore
import com.cpu.seamlessloopmobile.data.sync.stableKey
import com.cpu.seamlessloopmobile.data.sync.SyncPlaybackStats
import com.cpu.seamlessloopmobile.data.sync.SyncPlaybackStatsContribution
import com.cpu.seamlessloopmobile.data.sync.SyncPlaybackStatsDevice
import com.cpu.seamlessloopmobile.data.sync.SyncPlaybackStatsSong
import com.cpu.seamlessloopmobile.data.sync.SyncPlaybackStatsTombstone
import com.cpu.seamlessloopmobile.data.sync.isSemanticallyValid
import com.cpu.seamlessloopmobile.data.sync.reducePlaybackSongIdentity
import com.google.gson.Gson
import kotlin.math.abs

/**
 * Room 数据库驱动的 [SyncSnapshotStore] 实现。
 *
 * 导出时从 Room 读取播放列表、循环点、评分数据；
 * 应用时将合并后的快照写回 Room。
 */
class RoomSyncSnapshotStore(
    private val database: AppDatabase,
    private val songDao: SongDao,
    private val playlistDao: PlaylistDao,
    private val playlistIdMapper: PlaylistIdMapper,
    private val listenStatsRepository: ListenStatsRepository
) : SyncSnapshotStore {
    private val gson = Gson()

    // ===================================================================
    // exportSnapshot
    // ===================================================================

    override suspend fun exportSnapshot(
        deviceId: String,
        now: Long
    ): SyncSnapshot {
        val allSongs = songDao.getAllSongs() // IsAbPartB = 0

        // 循环点：仅导出有实质内容的（非 0,0）
        val loopPoints = allSongs
            .filter { it.loopStart != 0L || it.loopEnd != 0L }
            .map { song ->
                SyncLoopPointEntry(
                    song = identityFromSong(song),
                    loopPoint = SyncLoopPoint(
                        loopStart = song.loopStart,
                        loopEnd = song.loopEnd,
                        lastModified = now // 替代值：目前 LoopPoint 表无 lastModified
                    )
                )
            }

        // 评分：仅导出非零评分
        val ratings = allSongs
            .filter { it.rating != 0 }
            .map { song ->
                SyncRatingEntry(
                    song = identityFromSong(song),
                    rating = SyncRating(
                        rating = song.rating,
                        lastModified = song.userRating?.lastModified ?: now
                    )
                )
            }

        // 播放列表
        val playlistWithCounts = playlistDao.getPlaylistsWithCounts()
        val playlists = playlistWithCounts.map { pc ->
            val playlist = pc.playlist
            val songs = playlistDao.getSongsInPlaylist(playlist.id)
            val items = songs.mapIndexed { index, song ->
                SyncPlaylistItem(
                    song = identityFromSong(song),
                    sortOrder = index
                )
            }
            val fingerprint = computePlaylistFingerprint(playlist.name, items)
            val record = playlistIdMapper.getOrCreateSyncIdForExport(
                localId = playlist.id,
                fingerprint = fingerprint,
                now = now
            )
            SyncPlaylist(
                id = record.syncId,
                name = playlist.name,
                createdAt = playlist.createdAt,
                modifiedAt = record.modifiedAt,
                items = items
            )
        }
        val playbackStats = listenStatsRepository.exportLocalPayload().toSyncPlaybackStats(
            listenStatsRepository.currentSource()
        )

        return SyncSnapshot(
            deviceId = deviceId,
            exportedAt = now,
            playlists = playlists,
            loopPoints = loopPoints,
            ratings = ratings,
            playbackStats = playbackStats
        )
    }

    // ===================================================================
    // applySnapshot
    // ===================================================================

    override suspend fun applySnapshot(
        snapshot: SyncSnapshot,
        trackLocalMutation: Boolean
    ): SyncReport {
        validatePlaybackStats(snapshot.playbackStats)
        val report = database.withTransaction {
            applySnapshotInternal(snapshot)
        }
        applyPlaybackStats(snapshot.playbackStats, trackLocalMutation)
        // A scan may have completed while the merged payload was being persisted.
        reAssociatePlaybackStats(songDao.getAllSongs())
        return report
    }

    /** Rebinds persisted playback statistics to the current Room song metadata. */
    suspend fun rebindPlaybackStats() {
        reAssociatePlaybackStats(songDao.getAllSongs())
    }

    private fun validatePlaybackStats(stats: SyncPlaybackStats) {
        stats.songs.forEachIndexed { index, song ->
            require(song.isSemanticallyValid()) {
                "Invalid playback statistics song at index $index"
            }
        }
        val stableKeys = stats.songs.map { it.song.stableKey() }
        require(stableKeys.toSet().size == stableKeys.size) {
            "Duplicate playback statistics song stable key"
        }
    }

    private suspend fun applySnapshotInternal(snapshot: SyncSnapshot): SyncReport {
        val conflicts = mutableListOf<SyncConflict>()
        val allLocalSongs = songDao.getAllSongs() // IsAbPartB = 0
        val matcher = SongMatcher(allLocalSongs)

        // ---- 循环点 ----
        var loopDownloaded = 0
        for (entry in snapshot.loopPoints) {
            val localSong = matcher.match(entry.song)
            if (localSong == null) {
                conflicts.add(SyncConflict(
                    songIdentity = entry.song,
                    field = "loopPoint",
                    remoteValue = "(${entry.loopPoint.loopStart}, ${entry.loopPoint.loopEnd})",
                    localValue = null,
                    resolution = "skipped-unmatched"
                ))
                continue
            }

            // 仅当远程有实质内容时才应用
            if (!entry.loopPoint.isSubstantive) continue

            val existingLoop = songDao.getLoopPointBySongId(localSong.id)
            if (existingLoop?.loopStart == entry.loopPoint.loopStart &&
                existingLoop.loopEnd == entry.loopPoint.loopEnd
            ) {
                continue
            }

            // 远程是实质内容时才覆盖本地；未设置值在上方直接跳过。
            songDao.insertLoopPoint(LoopPoint(
                songId = localSong.id,
                loopStart = entry.loopPoint.loopStart,
                loopEnd = entry.loopPoint.loopEnd
            ))
            loopDownloaded++
        }

        // ---- 评分 ----
        var ratingDownloaded = 0
        for (entry in snapshot.ratings) {
            val localSong = matcher.match(entry.song)
            if (localSong == null) {
                conflicts.add(SyncConflict(
                    songIdentity = entry.song,
                    field = "rating",
                    remoteValue = "${entry.rating.rating}",
                    localValue = null,
                    resolution = "skipped-unmatched"
                ))
                continue
            }

            // 评分 0 是未设置 → 跳过
            if (entry.rating.rating == 0) continue

            val existingRating = songDao.getUserRatingBySongId(localSong.id)

            // 仅当远程评分更新或等新时才覆盖
            if (existingRating != null &&
                existingRating.rating != 0 &&
                existingRating.lastModified > entry.rating.lastModified
            ) {
                // 本地评分更新且非零 → 保留本地
                continue
            }

            songDao.insertUserRating(UserRating(
                songId = localSong.id,
                rating = entry.rating.rating,
                lastModified = entry.rating.lastModified
            ))
            ratingDownloaded++
        }

        // ---- 播放列表 ----
        val allLocalPlaylists = playlistDao.getPlaylistsWithCounts()
        val validLocalIds = allLocalPlaylists.map { it.playlist.id }.toSet()
        playlistIdMapper.removeStaleMappings(validLocalIds)

        var playlistDownloaded = 0

        for (playlist in snapshot.playlists) {
            // 解析本地歌单 ID
            val resolvedId = resolvePlaylistId(playlist)

            if (resolvedId == null) {
                conflicts.add(SyncConflict(
                    playlistName = playlist.name,
                    resolution = "skipped-unmatched-local-playlist"
                ))
                continue
            }

            // 更新歌单名称
            val existingPlaylist = playlistDao.getPlaylistById(resolvedId)
            if (existingPlaylist != null && existingPlaylist.name != playlist.name) {
                playlistDao.updatePlaylist(existingPlaylist.copy(name = playlist.name))
            }

            // 匹配歌曲
            val orderedItems = playlist.items.sortedWith(
                compareBy<SyncPlaylistItem> { it.sortOrder }
                    .thenBy { it.song.fileName.lowercase() }
                    .thenBy { it.song.durationMs }
            )
            val matchedSongIds = orderedItems.distinctBy { it.song.stableKey() }.mapNotNull { item ->
                val song = matcher.match(item.song)
                if (song == null) {
                    conflicts.add(SyncConflict(
                        songIdentity = item.song,
                        field = "playlistItem",
                        playlistName = playlist.name,
                        resolution = "skipped-unmatched"
                    ))
                    null
                } else {
                    song.id
                }
            }

            // 清空并同步
            playlistDao.clearAndSyncPlaylist(resolvedId, matchedSongIds)
            playlistDownloaded++

            // 保存或更新映射
            val fingerprint = computePlaylistFingerprint(playlist.name, playlist.items)
            playlistIdMapper.saveMapping(
                syncId = playlist.id,
                localId = resolvedId,
                modifiedAt = playlist.modifiedAt,
                fingerprint = fingerprint
            )
        }
        return SyncReport(
            playlistsDownloaded = playlistDownloaded,
            loopPointsDownloaded = loopDownloaded,
            ratingsDownloaded = ratingDownloaded,
            conflicts = conflicts
        )
    }

    // ===================================================================
    // 解析播放列表
    // ===================================================================

    /**
     * 解析远程歌单对应的本地歌单 ID。
     * 1. 通过映射查找
     * 2. 如果映射失效（已删除），按名称匹配
     * 3. 如果仍找不到，创建新歌单
     */
    private suspend fun resolvePlaylistId(playlist: SyncPlaylist): Int? {
        // 步骤 1：通过 syncId 查找映射
        val mappedLocalId = playlistIdMapper.findLocalId(playlist.id)
        if (mappedLocalId != null) {
            val existing = playlistDao.getPlaylistById(mappedLocalId)
            if (existing != null) return existing.id
        }

        // 步骤 2：按名称匹配
        val byName = playlistDao.getPlaylistByName(playlist.name)
        if (byName != null) return byName.id

        // 步骤 3：创建新歌单
        val newId = playlistDao.insertPlaylist(Playlist(
            name = playlist.name,
            createdAt = playlist.createdAt
        ))
        return newId.toInt()
    }

    // ===================================================================
    // 歌曲身份匹配
    // ===================================================================

    /**
     * 本地歌曲匹配器。
     * 对传入的 [SyncSongIdentity] 执行多级匹配，返回匹配的本地 [Song] 或 null。
     */
    private class SongMatcher(allLocalSongs: List<Song>) {
        /** fileName (小写) → 候选列表 */
        private val byName: Map<String, List<Song>> =
            allLocalSongs.groupBy { it.fileName.lowercase() }

        /** fileName+duration → candidates. Only unique matches are accepted. */
        private val byFingerprint: Map<Pair<String, Long>, List<Song>> =
            allLocalSongs.groupBy { it.fileName.lowercase() to it.duration }

        /** fileName+totalSamples → candidates (仅 non-zero samples). Only unique matches are accepted. */
        private val bySamples: Map<Pair<String, Long>, List<Song>> =
            allLocalSongs
                .filter { it.totalSamples != 0L }
                .groupBy { it.fileName.lowercase() to it.totalSamples }

        /**
         * 执行多级匹配。
         * 顺序：精确 fileName+duration → 精确 fileName+totalSamples →
         *       fileName+totalSamples ±10000 → fileName+duration ±200ms → 唯一同名。
         */
        fun match(identity: SyncSongIdentity): Song? {
            val key = identity.fileName.lowercase()

            // 1. 精确 fileName + duration
            byFingerprint[key to identity.durationMs]
                ?.takeIf { it.size == 1 }
                ?.first()
                ?.let { return it }

            // 2. 精确 fileName + totalSamples
            identity.totalSamples?.let { samples ->
                bySamples[key to samples]
                    ?.takeIf { it.size == 1 }
                    ?.first()
                    ?.let { return it }
            }

            // 3. fileName + totalSamples ±10000
            val candidates = byName[key].orEmpty()
            identity.totalSamples?.let { samples ->
                val sampleToleranceMatches = candidates.filter {
                    it.totalSamples > 0L && abs(it.totalSamples - samples) <= TOTAL_SAMPLES_TOLERANCE
                }
                if (sampleToleranceMatches.size == 1) return sampleToleranceMatches.first()
            }

            // 4. fileName + duration ±200ms
            if (candidates.isNotEmpty()) {
                val toleranceMatches = candidates.filter {
                    abs(it.duration - identity.durationMs) <= 200L
                }
                if (toleranceMatches.size == 1) return toleranceMatches.first()
            }

            // 5. 唯一同名（只有一个候选）
            if (candidates.size == 1) return candidates.first()

            return null
        }

        companion object {
            private const val TOTAL_SAMPLES_TOLERANCE = 10_000L
        }
    }

    private suspend fun applyPlaybackStats(
        stats: SyncPlaybackStats,
        trackLocalMutation: Boolean
    ) {
        val localSongs = songDao.getAllSongs()
        val matcher = PlaybackStatsSongMatcher(localSongs)
        val current = listenStatsRepository.exportLocalPayload()
        val resolved = current.songs.toMutableList()
        val unresolved = current.unresolvedNodes.toMutableList()

        stats.songs.forEach { remote ->
            resolved.add(remote.toLocalNode(matcher.match(remote.song)))
        }
        listenStatsRepository.applyLocalPayload(
            ListenStatsLocalPayload(
                currentDeviceId = current.currentDeviceId,
                currentGeneration = current.currentGeneration,
                devices = stats.devices.map { it.toLocalDevice() },
                songs = resolved,
                tombstones = stats.tombstones.map { it.toLocalTombstone() },
                unresolvedNodes = mergeUnresolvedNodes(unresolved)
            ),
            trackMutation = trackLocalMutation
        )
    }

    private suspend fun reAssociatePlaybackStats(localSongs: List<Song>) {
        val current = listenStatsRepository.exportLocalPayload()
        val matcher = PlaybackStatsSongMatcher(localSongs)
        val parseable = current.unresolvedNodes.mapNotNull { unresolved ->
            parseUnresolvedSyncSong(unresolved)
        }
        val malformed = current.unresolvedNodes.filter { unresolved ->
            parseUnresolvedSyncSong(unresolved) == null
        }
        val candidates = current.songs + parseable.map { it.toLocalNode(null) }
        val rebound = candidates.map { node ->
            val local = matcher.match(node.toSyncIdentity())
            if (local == null) {
                // The wire identity and last-known presentation metadata survive a stale binding.
                node.copy(boundSongId = 0L)
            } else {
                node.copy(
                    boundSongId = local.id,
                    displayName = local.displayName,
                    artist = local.artist,
                    album = local.album,
                    coverPath = local.coverPath,
                    filePath = local.filePath
                )
            }
        }
        listenStatsRepository.applyLocalPayload(current.copy(
            songs = mergeLocalStatsNodes(rebound),
            unresolvedNodes = malformed
        ), trackMutation = false)
    }

    private fun ListenStatsLocalPayload.toSyncPlaybackStats(currentSource: ListenStatsSource): SyncPlaybackStats = SyncPlaybackStats(
        devices = devices.map { device ->
            val generation = if (device.deviceId == currentSource.device.deviceId) currentSource.currentGeneration
            else device.currentGeneration
            SyncPlaybackStatsDevice(device.deviceId, device.displayName, device.createdAt,
                device.lastSeenAt, generation, device.platform, device.displayNameUpdatedAtUtcMs)
        },
        songs = mergeSyncPlaybackStatsSongs(
            songs.map { it.toSyncSong(tombstones) } + unresolvedNodes.mapNotNull(::parseUnresolvedSyncSong),
            tombstones
        ),
        tombstones = tombstones.map { tombstone -> SyncPlaybackStatsTombstone(tombstone.deviceId, tombstone.generation,
            tombstone.tombstonedAtUtcMs, tombstonedByDeviceId = tombstone.operatorDeviceId, reason = tombstone.reason) }
    ).sorted()

    private fun mergeLocalStatsNodes(nodes: List<ListenStatsSongNode>): List<ListenStatsSongNode> =
        nodes.groupBy { it.wireKey() }
            .toSortedMap(compareBy<SyncSongStableKey> { it.fileNameKey }.thenBy { it.durationMs })
            .values.map { duplicates ->
                val identity = reducePlaybackSongIdentity(duplicates.map { it.toSyncIdentity() })
                val binding = duplicates.minWithOrNull(
                    compareBy<ListenStatsSongNode> { if (it.boundSongId > 0L) 0 else 1 }
                        .thenBy { it.boundSongId }
                        .thenBy { it.filePath }
                        .thenBy { it.displayName }
                )!!
                binding.copy(
                    identityKey = identity.localIdentityKey(),
                    normalizedFileName = identity.normalizedFileName,
                    fileName = identity.fileName,
                    durationMs = identity.durationMs,
                    totalSamples = identity.totalSamples,
                    contentHash = identity.contentHash
                ).withMergedContributions(duplicates.flatMap { it.contributions })
            }

    /** Collapses parseable revisions while retaining malformed payloads verbatim for later recovery. */
    private fun mergeUnresolvedNodes(nodes: List<ListenStatsUnresolvedNode>): List<ListenStatsUnresolvedNode> {
        val parsed = mutableMapOf<SyncSongStableKey, SyncPlaybackStatsSong>()
        val malformed = mutableListOf<ListenStatsUnresolvedNode>()
        nodes.forEach { node ->
            val song = parseUnresolvedSyncSong(node)
            if (song == null) {
                malformed += node
            } else {
                val key = song.song.stableKey()
                parsed[key] = parsed[key]?.let { existing ->
                    existing.copy(
                        song = reducePlaybackSongIdentity(listOf(existing.song, song.song)),
                        contributions = mergeSyncContributions(existing.contributions + song.contributions)
                    )
                } ?: song.copy(contributions = mergeSyncContributions(song.contributions))
            }
        }
        return parsed.values.map { it.toUnresolvedNode() } + malformed
    }

    /** Gson permits missing Kotlin non-null fields, so validate before using an unresolved node. */
    private fun parseUnresolvedSyncSong(node: ListenStatsUnresolvedNode): SyncPlaybackStatsSong? =
        runCatching {
            gson.fromJson(node.payloadJson, SyncPlaybackStatsSong::class.java)
        }.getOrNull()?.takeIf { it.isSemanticallyValid() }

    private fun mergeSyncPlaybackStatsSongs(
        songs: List<SyncPlaybackStatsSong>,
        tombstones: List<ListenStatsTombstone>
    ): List<SyncPlaybackStatsSong> = songs.groupBy { it.song.stableKey() }
        .toSortedMap(compareBy<SyncSongStableKey> { it.fileNameKey }.thenBy { it.durationMs }).values.map { duplicates ->
        val identity = reducePlaybackSongIdentity(duplicates.map { it.song })
        duplicates.first().copy(
            song = identity,
            contributions = mergeSyncContributions(duplicates.flatMap { it.contributions })
        ).let { song ->
            song.copy(contributions = song.contributions.filterNot { contribution -> tombstones.any {
                it.deviceId == contribution.deviceId && it.generation == contribution.generation
            } })
        }
    }

    private fun mergeLocalContributions(contributions: List<ListenStatsContribution>): List<ListenStatsContribution> =
        contributions.groupBy { it.deviceId to it.generation }.toSortedMap(compareBy<Pair<String, Long>> { it.first }.thenBy { it.second }).values.map { duplicates ->
            duplicates.drop(1).fold(duplicates.first()) { first, second ->
                ListenStatsContribution(
                    deviceId = first.deviceId,
                    generation = first.generation,
                    dailyListenMs = (first.dailyListenMs.keys + second.dailyListenMs.keys).associateWith { date ->
                        maxOf(first.dailyListenMs[date] ?: 0L, second.dailyListenMs[date] ?: 0L)
                    },
                    undatedListenMs = maxOf(first.undatedListenMs, second.undatedListenMs),
                    firstPlayedAtUtcMs = earliestMeaningful(first.firstPlayedAtUtcMs, second.firstPlayedAtUtcMs),
                    lastPlayedAtUtcMs = maxOf(first.lastPlayedAtUtcMs, second.lastPlayedAtUtcMs),
                    updatedAtUtcMs = maxOf(first.updatedAtUtcMs, second.updatedAtUtcMs)
                )
            }
        }

    private fun mergeSyncContributions(contributions: List<SyncPlaybackStatsContribution>): List<SyncPlaybackStatsContribution> =
        contributions.groupBy { it.deviceId to it.generation }.toSortedMap(compareBy<Pair<String, Long>> { it.first }.thenBy { it.second }).values.map { duplicates ->
            duplicates.drop(1).fold(duplicates.first()) { first, second ->
                SyncPlaybackStatsContribution(
                    deviceId = first.deviceId,
                    generation = first.generation,
                    datedListenMs = (first.datedListenMs.keys + second.datedListenMs.keys).associateWith { date ->
                        maxOf(first.datedListenMs[date] ?: 0L, second.datedListenMs[date] ?: 0L)
                    },
                    undatedListenMs = maxOf(first.undatedListenMs, second.undatedListenMs),
                    firstPlayedAtUtcMs = earliestMeaningful(first.firstPlayedAtUtcMs, second.firstPlayedAtUtcMs),
                    lastPlayedAtUtcMs = maxOf(first.lastPlayedAtUtcMs, second.lastPlayedAtUtcMs),
                    updatedAtUtcMs = maxOf(first.updatedAtUtcMs, second.updatedAtUtcMs)
                )
            }
        }

    private fun earliestMeaningful(first: Long, second: Long): Long = when {
        first == 0L -> second
        second == 0L -> first
        else -> minOf(first, second)
    }

    private fun ListenStatsSongNode.wireKey(): SyncSongStableKey =
        SyncSongStableKey(normalizedFileName, durationMs)

    private fun ListenStatsSongNode.toSyncIdentity() = SyncSongIdentity(
        fileName = fileName,
        durationMs = durationMs,
        totalSamples = totalSamples,
        normalizedFileName = normalizedFileName,
        contentHash = contentHash
    )

    private fun SyncSongIdentity.localIdentityKey(): String =
        "${normalizedFileName}|${durationMs}"

    private fun ListenStatsSongNode.toSyncSong(tombstones: List<ListenStatsTombstone>) = SyncPlaybackStatsSong(
        song = SyncSongIdentity(
            fileName = fileName,
            durationMs = durationMs,
            totalSamples = totalSamples,
            normalizedFileName = normalizedFileName,
            contentHash = contentHash
        ),
        contributions = contributions.filterNot { contribution -> tombstones.any {
            it.deviceId == contribution.deviceId && it.generation == contribution.generation
        } }.map { contribution -> SyncPlaybackStatsContribution(contribution.deviceId,
            contribution.generation, contribution.dailyListenMs, contribution.undatedListenMs,
            contribution.firstPlayedAtUtcMs, contribution.lastPlayedAtUtcMs, contribution.updatedAtUtcMs) }
    )

    private fun SyncPlaybackStatsSong.toLocalNode(local: Song?) = ListenStatsSongNode(
        identityKey = song.localIdentityKey(),
        normalizedFileName = song.normalizedFileName,
        fileName = song.fileName,
        boundSongId = local?.id ?: 0L,
        displayName = local?.displayName ?: song.fileName,
        artist = local?.artist ?: "",
        album = local?.album ?: "",
        coverPath = local?.coverPath,
        durationMs = song.durationMs,
        totalSamples = song.totalSamples,
        contentHash = song.contentHash,
        filePath = local?.filePath ?: "",
        contributions = contributions.map { it.toLocalContribution() }
    ).withMergedContributions(contributions.map { it.toLocalContribution() })

    private fun SyncPlaybackStatsSong.toUnresolvedNode() = ListenStatsUnresolvedNode(
        normalizedFileName = song.normalizedFileName, durationMs = song.durationMs, payloadJson = gson.toJson(this)
    )

    private fun SyncPlaybackStatsContribution.toLocalContribution() = ListenStatsContribution(deviceId, generation,
        datedListenMs, undatedListenMs, firstPlayedAtUtcMs, lastPlayedAtUtcMs, updatedAtUtcMs)

    private fun ListenStatsSongNode.withMergedContributions(
        contributions: List<ListenStatsContribution>
    ): ListenStatsSongNode {
        val merged = mergeLocalContributions(contributions)
        return copy(
            contributions = merged,
            firstPlayedAt = merged.map { it.firstPlayedAtUtcMs }.filter { it > 0L }.minOrNull() ?: 0L,
            lastPlayedAt = merged.maxOfOrNull { it.lastPlayedAtUtcMs } ?: 0L
        )
    }

    private fun SyncPlaybackStatsDevice.toLocalDevice() = ListenStatsDevice(deviceId, displayName,
        displayNameUpdatedAtUtcMs, platform, currentGeneration = currentGeneration, createdAt = firstSeenAtUtcMs,
        lastSeenAt = lastSeenAtUtcMs, updatedAtUtcMs = displayNameUpdatedAtUtcMs)

    private fun SyncPlaybackStatsTombstone.toLocalTombstone() = ListenStatsTombstone(deviceId, generation,
        tombstonedAtUtcMs, tombstonedByDeviceId, reason)

    private class PlaybackStatsSongMatcher(songs: List<Song>) {
        private val byName: Map<String, List<Song>> = songs.groupBy {
            normalizedStatsFileName(it.fileName)
        }
        private val byDuration: Map<Pair<String, Long>, List<Song>> = songs.groupBy {
            normalizedStatsFileName(it.fileName) to it.duration
        }
        private val bySamples: Map<Pair<String, Long>, List<Song>> = songs
            .filter { it.totalSamples != 0L }
            .groupBy { normalizedStatsFileName(it.fileName) to it.totalSamples }

        fun match(identity: SyncSongIdentity): Song? {
            val name = normalizedStatsFileName(identity.fileName)
            val candidates = byName[name].orEmpty()

            byDuration[name to identity.durationMs]
                ?.singleOrNull()
                ?.let { return it }

            identity.totalSamples?.let { samples ->
                bySamples[name to samples]
                    ?.singleOrNull()
                    ?.let { return it }

                candidates.filter { song ->
                    song.totalSamples > 0L && withinDistance(song.totalSamples, samples, SAMPLE_TOLERANCE)
                }.singleOrNull()?.let { return it }
            }

            candidates.filter { song ->
                withinDistance(song.duration, identity.durationMs, DURATION_TOLERANCE)
            }.singleOrNull()?.let { return it }

            return candidates.singleOrNull()
        }

        private companion object {
            const val SAMPLE_TOLERANCE = 10_000L
            const val DURATION_TOLERANCE = 200L

            /** Returns false when the mathematical difference cannot fit in Long. */
            fun withinDistance(first: Long, second: Long, tolerance: Long): Boolean {
                val lower = minOf(first, second)
                val upper = maxOf(first, second)
                val difference = try {
                    Math.subtractExact(upper, lower)
                } catch (_: ArithmeticException) {
                    return false
                }
                return difference <= tolerance
            }
        }
    }

    // ===================================================================
    // 工具方法
    // ===================================================================

    private fun identityFromSong(song: Song): SyncSongIdentity =
        SyncSongIdentity(
            fileName = song.fileName,
            durationMs = song.duration,
            totalSamples = song.totalSamples.takeIf { it != 0L }
        )

    /**
     * 计算歌单内容指纹。
     * 格式：name|file1,dur1;file2,dur2;...
     */
    private fun computePlaylistFingerprint(
        name: String,
        items: List<SyncPlaylistItem>
    ): String {
        val itemsPart = items
            .distinctBy { it.song.stableKey() }
            .sortedWith(
                compareBy<SyncPlaylistItem> { it.sortOrder }
                    .thenBy { it.song.fileName.lowercase() }
                    .thenBy { it.song.durationMs }
            )
            .joinToString(";") { item ->
                "${item.sortOrder},${item.song.fileName},${item.song.durationMs}"
        }
        return "$name|$itemsPart"
    }
}
