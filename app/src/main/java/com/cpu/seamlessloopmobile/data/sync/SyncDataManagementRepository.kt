package com.cpu.seamlessloopmobile.data.sync

import androidx.room.withTransaction
import com.cpu.seamlessloopmobile.data.stats.ListenStatsContribution
import com.cpu.seamlessloopmobile.data.stats.ListenStatsLocalPayload
import com.cpu.seamlessloopmobile.data.stats.ListenStatsTombstone
import com.cpu.seamlessloopmobile.data.stats.ListenStatsRepository
import com.cpu.seamlessloopmobile.db.AppDatabase
import com.cpu.seamlessloopmobile.model.PlaylistDao
import com.cpu.seamlessloopmobile.model.Song
import com.cpu.seamlessloopmobile.model.SongDao
import com.cpu.seamlessloopmobile.data.sync.room.PlaylistIdMapper
import com.cpu.seamlessloopmobile.data.sync.room.RoomSyncSnapshotStore
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlin.math.abs

/**
 * 同步数据管理仓库。
 *
 * 提供本地/云端数据预览、初始云端写入、远程删除、本地数据清除等管理操作，
 * 不涉及自动同步或合并流程。
 */
class SyncDataManagementRepository(
    private val database: AppDatabase,
    private val songDao: SongDao,
    private val playlistDao: PlaylistDao,
    private val snapshotStore: RoomSyncSnapshotStore,
    private val backend: GitHubSnapshotRemote? = null,
    private val metadataStore: SyncMetadataStore,
    private val playlistIdMapper: PlaylistIdMapper,
    private val listenStatsRepository: ListenStatsRepository
) {
    private val gson = Gson()

    // ===================================================================
    // 本地摘要
    // ===================================================================

    /**
     * 获取本地同步数据摘要。
     */
    suspend fun getLocalSummary(): LocalSyncDataSummary {
        val allSongs = songDao.getAllSongs()
        val playlists = playlistDao.getPlaylistsWithCounts()
        val totalPlaylistItems = playlists.sumOf { it.songCount }
        val loopPointCount = allSongs.count { it.loopStart != 0L || it.loopEnd != 0L }
        val ratingCount = allSongs.count { it.rating != 0 }

        return LocalSyncDataSummary(
            songCount = allSongs.size,
            playlistCount = playlists.size,
            playlistItemCount = totalPlaylistItems,
            loopPointCount = loopPointCount,
            ratingCount = ratingCount
        )
    }

    // ===================================================================
    // 预览
    // ===================================================================

    /**
     * 预览本地与云端数据摘要。
     * 不修改任何数据。
     */
    suspend fun preview(): SyncDataManagementResult<SyncDataManagementPreview> {
        val local = getLocalSummary()
        val backend = backend ?: return remoteBackendUnavailable()

        val cloudResult = when (val result = backend.downloadSnapshot(null)) {
            is SyncResult.Success -> {
                val snapshot = result.snapshot
                if (snapshot == null) {
                    SyncDataManagementPreview(local, CloudSyncDataPreview(exists = false))
                } else {
                    if (snapshot.schemaVersion != SYNC_SCHEMA_VERSION_V2) {
                        return SyncDataManagementResult.Failure(
                            "Unsupported remote schema version ${snapshot.schemaVersion}",
                            SyncErrorCode.INVALID_REMOTE
                        )
                    }
                    val cloud = buildCloudPreview(snapshot)
                    SyncDataManagementPreview(local, cloud)
                }
            }
            is SyncResult.Failure -> {
                if (result.code == SyncErrorCode.NOT_FOUND) {
                    SyncDataManagementPreview(local, CloudSyncDataPreview(exists = false))
                } else {
                    return SyncDataManagementResult.Failure(result.message, result.code)
                }
            }
            is SyncResult.Cancelled -> {
                return SyncDataManagementResult.Failure("Operation cancelled", SyncErrorCode.UNKNOWN)
            }
        }

        return SyncDataManagementResult.Success(cloudResult)
    }

    /**
     * 根据云端快照构建预览数据。
     */
    private suspend fun buildCloudPreview(snapshot: SyncSnapshot): CloudSyncDataPreview {
        val allLocalSongs = songDao.getAllSongs()
        val matcher = SongIdentityMatcher(allLocalSongs)

        // 收集所有唯一的歌曲身份引用
        val allSongRefs = linkedMapOf<SyncSongStableKey, SyncSongIdentity>()
        snapshot.playlists.forEach { pl ->
            pl.items.forEach { allSongRefs.putIfAbsent(it.song.stableKey(), it.song) }
        }
        snapshot.loopPoints.forEach { allSongRefs.putIfAbsent(it.song.stableKey(), it.song) }
        snapshot.ratings.forEach { allSongRefs.putIfAbsent(it.song.stableKey(), it.song) }

        val matched = mutableSetOf<SyncSongIdentity>()
        val missing = mutableListOf<SyncSongIdentity>()

        for (ref in allSongRefs.values) {
            if (matcher.match(ref) != null) {
                matched.add(ref)
            } else {
                missing.add(ref)
            }
        }

        val totalPlaylistItems = snapshot.playlists.sumOf { it.items.size }

        return CloudSyncDataPreview(
            exists = true,
            deviceId = snapshot.deviceId,
            exportedAt = snapshot.exportedAt,
            playlists = snapshot.playlists.size,
            playlistItems = totalPlaylistItems,
            loopPointCount = snapshot.loopPoints.size,
            ratingCount = snapshot.ratings.size,
            matchedSongReferenceCount = matched.size,
            missingSongReferenceCount = missing.size,
            missingSongReferences = missing
        )
    }

    // ===================================================================
    // 初始云端写入
    // ===================================================================

    /**
     * 仅当云端同步文件不存在时，用当前本地数据创建初始快照。
     */
    suspend fun seedCloudFromLocal(): SyncDataManagementResult<SyncReport> {
        val backend = backend ?: return remoteBackendUnavailable()
        when (val result = backend.downloadSnapshot(null)) {
            is SyncResult.Success -> return SyncDataManagementResult.Failure(
                "Cloud snapshot already exists; use normal sync or delete the cloud snapshot first",
                SyncErrorCode.INVALID_REMOTE
            )
            is SyncResult.Failure -> if (result.code != SyncErrorCode.NOT_FOUND) {
                return SyncDataManagementResult.Failure(result.message, result.code)
            }
            is SyncResult.Cancelled -> return SyncDataManagementResult.Failure(
                "Operation cancelled", SyncErrorCode.UNKNOWN
            )
        }

        val deviceId = metadataStore.getDeviceId()
        val localSnapshot = snapshotStore.exportSnapshot(deviceId)

        val preparedSnapshot = localSnapshot.prepareV2Egress()

        val uploadResult = backend.uploadSnapshot(preparedSnapshot, expectedRevision = null)

        return when (uploadResult) {
            is SyncResult.Success -> {
                val newRev = uploadResult.remoteRevision
                if (newRev != null) {
                    metadataStore.saveSuccessfulSync(newRev, System.currentTimeMillis())
                }
                SyncDataManagementResult.Success(
                    uploadResult.report.copy(
                        playlistsUploaded = localSnapshot.playlists.size,
                        loopPointsUploaded = localSnapshot.loopPoints.size,
                        ratingsUploaded = localSnapshot.ratings.size
                    )
                )
            }
            is SyncResult.Failure -> {
                SyncDataManagementResult.Failure(uploadResult.message, uploadResult.code)
            }
            is SyncResult.Cancelled -> SyncDataManagementResult.Failure(
                "Operation cancelled", SyncErrorCode.UNKNOWN
            )
        }
    }

    // ===================================================================
    // 删除远程快照
    // ===================================================================

    /**
     * 删除 GitHub 上的远程快照文件。
     */
    suspend fun deleteCloudSnapshot(): SyncDataManagementResult<Unit> {
        val backend = backend ?: return remoteBackendUnavailable()
        val result = backend.deleteSnapshot()

        return when (result) {
            is SyncResult.Success -> {
                metadataStore.clearSyncMetadata()
                SyncDataManagementResult.Success(Unit)
            }
            is SyncResult.Failure -> {
                SyncDataManagementResult.Failure(result.message, result.code)
            }
            is SyncResult.Cancelled -> SyncDataManagementResult.Failure(
                "Operation cancelled", SyncErrorCode.UNKNOWN
            )
        }
    }

    // ===================================================================
    // 清除本地同步数据
    // ===================================================================

    /**
     * 按选择清除本机数据，不会删除歌曲本身。
     * Room 数据在同一事务内清除；播放统计 JSON 在事务外单独清除。
     */
    suspend fun clearLocalSyncData(
        selection: ClearLocalSyncDataSelection
    ): SyncDataManagementResult<LocalSyncDataSummary> {
        if (!selection.hasSelection) {
            return SyncDataManagementResult.Failure(
                "No data type selected for clearing",
                SyncErrorCode.UNKNOWN
            )
        }

        val clearsSyncedData = selection.clearPlaylists ||
            selection.clearLoopPoints || selection.clearRatings

        if (clearsSyncedData) {
            database.withTransaction {
                if (selection.clearLoopPoints) {
                    songDao.deleteAllLoopPoints()
                }
                if (selection.clearRatings) {
                    songDao.deleteAllUserRatings()
                }
                if (selection.clearPlaylists) {
                    playlistDao.deleteAllPlaylists()
                    playlistIdMapper.clearAllMappings()
                }
            }
            metadataStore.clearSyncMetadata()
            metadataStore.markMutation()
        }

        if (selection.clearListenStats) {
            try {
                listenStatsRepository.clearAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = if (clearsSyncedData) {
                    "Synced data was cleared, but playback statistics could not be cleared: ${e.message ?: "unknown error"}"
                } else {
                    "Playback statistics could not be cleared: ${e.message ?: "unknown error"}"
                }
                return SyncDataManagementResult.Failure(message, SyncErrorCode.UNKNOWN)
            }
        }

        val updated = getLocalSummary()
        return SyncDataManagementResult.Success(updated)
    }

    /** Lists local playback-stat sources and their non-tombstoned listen-time contributions. */
    suspend fun getLocalPlaybackStatsSourceDevices():
        SyncDataManagementResult<List<PlaybackStatsSourceDeviceSummary>> = try {
        SyncDataManagementResult.Success(buildPlaybackStatsSourceDevices())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SyncDataManagementResult.Failure(
            "Unable to load playback-stat source devices: ${e.message ?: "unknown error"}"
        )
    }

    /**
     * Tombstones every locally known generation for the selected source devices.
     * Tombstoning the current source delegates generation rotation to [ListenStatsRepository].
     */
    suspend fun deleteLocalPlaybackStatsSourceDeviceHistories(
        deviceIds: Set<String>
    ): SyncDataManagementResult<List<PlaybackStatsSourceDeviceSummary>> {
        val selectedIds = deviceIds.filter { it.isNotBlank() }.toSet()
        if (selectedIds.isEmpty()) {
            return SyncDataManagementResult.Failure(
                "No playback-stat source device selected",
                SyncErrorCode.UNKNOWN
            )
        }

        return try {
            val payload = listenStatsRepository.exportLocalPayload()
            val knownDeviceIds = payload.knownSourceDeviceIds()
            val existingSelectedIds = selectedIds.intersect(knownDeviceIds)
            if (existingSelectedIds.isEmpty()) {
                return SyncDataManagementResult.Failure(
                    "Selected playback-stat source devices no longer exist",
                    SyncErrorCode.UNKNOWN
                )
            }

            val now = System.currentTimeMillis()
            val tombstones = payload.tombstones.toMutableList()
            existingSelectedIds.forEach { deviceId ->
                payload.knownGenerations(deviceId).forEach { generation ->
                    tombstones += ListenStatsTombstone(
                        deviceId = deviceId,
                        generation = generation,
                        tombstonedAtUtcMs = now,
                        operatorDeviceId = payload.currentDeviceId,
                        reason = "source_history_deleted"
                    )
                }
            }
            listenStatsRepository.applyLocalPayload(
                payload.copy(tombstones = tombstones.distinctBy { it.deviceId to it.generation })
            )
            SyncDataManagementResult.Success(buildPlaybackStatsSourceDevices())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncDataManagementResult.Failure(
                "Unable to delete playback-stat source histories: ${e.message ?: "unknown error"}"
            )
        }
    }

    private suspend fun buildPlaybackStatsSourceDevices(): List<PlaybackStatsSourceDeviceSummary> {
        val payload = listenStatsRepository.exportLocalPayload()
        val tombstonedGenerations = payload.tombstones
            .map { it.deviceId to it.generation }
            .toSet()
        val devices = payload.devices.associateBy { it.deviceId }
        return payload.knownSourceDeviceIds().map { deviceId ->
            val effectiveContributions = payload.mergedSourceContributions(deviceId).filter {
                (it.deviceId to it.generation) !in tombstonedGenerations
            }
            val knownGenerations = payload.knownGenerations(deviceId)
            val device = devices[deviceId]
            PlaybackStatsSourceDeviceSummary(
                deviceId = deviceId,
                displayName = device?.displayName.orEmpty(),
                fallbackLabel = device?.displayName?.takeIf { it.isNotBlank() } ?: deviceId,
                platform = device?.platform?.ifBlank { "unknown" } ?: "unknown",
                currentGeneration = when {
                    deviceId == payload.currentDeviceId -> payload.currentGeneration
                    device != null -> device.currentGeneration
                    else -> null
                },
                isCurrentDevice = deviceId == payload.currentDeviceId,
                contributedListenMs = effectiveContributions.sumOf { it.totalListenMs() },
                hasEffectiveContributions = effectiveContributions.isNotEmpty(),
                allKnownGenerationsRemoved = knownGenerations.isNotEmpty() &&
                    knownGenerations.all { (deviceId to it) in tombstonedGenerations }
            )
        }.sortedWith(compareByDescending<PlaybackStatsSourceDeviceSummary> { it.isCurrentDevice }
            .thenBy { it.fallbackLabel })
    }

    private fun com.cpu.seamlessloopmobile.data.stats.ListenStatsLocalPayload.knownSourceDeviceIds(): Set<String> =
        buildSet {
            addAll(devices.map { it.deviceId })
            addAll(songs.flatMap { song -> song.contributions.map { it.deviceId } })
            addAll(tombstones.map { it.deviceId })
            addAll(unresolvedNodes.flatMap { node ->
                unresolvedSyncSong(node)?.contributions.orEmpty().map { it.deviceId }
            })
        }.filter { it.isNotBlank() }.toSet()

    private fun com.cpu.seamlessloopmobile.data.stats.ListenStatsLocalPayload.knownGenerations(
        deviceId: String
    ): Set<Long> = buildSet {
        if (deviceId == currentDeviceId) add(currentGeneration)
        devices.find { it.deviceId == deviceId }?.let { add(it.currentGeneration) }
        songs.forEach { song -> song.contributions.filter { it.deviceId == deviceId }.forEach { add(it.generation) } }
        tombstones.filter { it.deviceId == deviceId }.forEach { add(it.generation) }
        unresolvedNodes.forEach { node ->
            unresolvedSyncSong(node)?.contributions.orEmpty()
                .filter { it.deviceId == deviceId }
                .forEach { add(it.generation) }
        }
    }

    private fun ListenStatsLocalPayload.mergedSourceContributions(deviceId: String): List<ListenStatsContribution> =
        (songs.map { song ->
            SyncPlaybackStatsSong(
                SyncSongIdentity(song.fileName, song.durationMs, normalizedFileName = song.normalizedFileName),
                song.contributions.map { contribution ->
                    SyncPlaybackStatsContribution(
                        contribution.deviceId, contribution.generation, contribution.dailyListenMs,
                        contribution.undatedListenMs, contribution.firstPlayedAtUtcMs,
                        contribution.lastPlayedAtUtcMs, contribution.updatedAtUtcMs
                    )
                }
            )
        } + unresolvedNodes.mapNotNull(::unresolvedSyncSong))
            .groupBy { it.song.stableKey() }
            .values
            .flatMap { revisions ->
                revisions.flatMap { it.contributions }
                    .filter { it.deviceId == deviceId }
                    .groupBy { it.deviceId to it.generation }
                    .values
                    .map(::mergeSourceContributions)
            }
            .map { contribution ->
                ListenStatsContribution(
                    deviceId = contribution.deviceId,
                    generation = contribution.generation,
                    dailyListenMs = contribution.datedListenMs,
                    undatedListenMs = contribution.undatedListenMs,
                    firstPlayedAtUtcMs = contribution.firstPlayedAtUtcMs,
                    lastPlayedAtUtcMs = contribution.lastPlayedAtUtcMs,
                    updatedAtUtcMs = contribution.updatedAtUtcMs
                )
            }

    private fun mergeSourceContributions(
        revisions: List<SyncPlaybackStatsContribution>
    ): SyncPlaybackStatsContribution = revisions.reduce { first, second ->
        first.copy(
            datedListenMs = (first.datedListenMs.keys + second.datedListenMs.keys).associateWith { date ->
                maxOf(first.datedListenMs[date] ?: 0L, second.datedListenMs[date] ?: 0L)
            },
            undatedListenMs = maxOf(first.undatedListenMs, second.undatedListenMs),
            firstPlayedAtUtcMs = earliestMeaningful(first.firstPlayedAtUtcMs, second.firstPlayedAtUtcMs),
            lastPlayedAtUtcMs = maxOf(first.lastPlayedAtUtcMs, second.lastPlayedAtUtcMs),
            updatedAtUtcMs = maxOf(first.updatedAtUtcMs, second.updatedAtUtcMs)
        )
    }

    private fun earliestMeaningful(first: Long, second: Long): Long = when {
        first == 0L -> second
        second == 0L -> first
        else -> minOf(first, second)
    }

    /** Gson permits missing Kotlin non-null fields, so validate before using unresolved contributions. */
    private fun unresolvedSyncSong(
        node: com.cpu.seamlessloopmobile.data.stats.ListenStatsUnresolvedNode
    ): SyncPlaybackStatsSong? = runCatching {
        gson.fromJson(node.payloadJson, SyncPlaybackStatsSong::class.java)
    }.getOrNull()?.takeIf { it.isSemanticallyValid() }

    private fun ListenStatsContribution.totalListenMs(): Long =
        (dailyListenMs.values + undatedListenMs).fold(0L) { total, value ->
            if (value <= 0L) total else if (Long.MAX_VALUE - total < value) Long.MAX_VALUE else total + value
        }

    private fun <T> remoteBackendUnavailable(): SyncDataManagementResult<T> {
        return SyncDataManagementResult.Failure(
            "GitHub remote backend is unavailable",
            SyncErrorCode.UNKNOWN
        )
    }

    // ===================================================================
    // 歌曲身份匹配器（独立副本，与 RoomSyncSnapshotStore 中逻辑一致）
    // 顺序：精确 fileName+duration → 精确 fileName+totalSamples →
    //       fileName+totalSamples ±10000 → fileName+duration ±200ms → 唯一同名。
    // ===================================================================

    private class SongIdentityMatcher(allLocalSongs: List<Song>) {
        private val byName: Map<String, List<Song>> =
            allLocalSongs.groupBy { it.fileName.lowercase() }
        private val byFingerprint: Map<Pair<String, Long>, List<Song>> =
            allLocalSongs.groupBy { it.fileName.lowercase() to it.duration }
        private val bySamples: Map<Pair<String, Long>, List<Song>> =
            allLocalSongs.filter { it.totalSamples != 0L }
                .groupBy { it.fileName.lowercase() to it.totalSamples }

        fun match(identity: SyncSongIdentity): Song? {
            val key = identity.fileName.lowercase()
            byFingerprint[key to identity.durationMs]
                ?.takeIf { it.size == 1 }
                ?.first()
                ?.let { return it }
            identity.totalSamples?.let { samples ->
                bySamples[key to samples]
                    ?.takeIf { it.size == 1 }
                    ?.first()
                    ?.let { return it }
            }
            val candidates = byName[key].orEmpty()
            identity.totalSamples?.let { samples ->
                val sampleToleranceMatches = candidates.filter {
                    it.totalSamples > 0L && abs(it.totalSamples - samples) <= TOTAL_SAMPLES_TOLERANCE
                }
                if (sampleToleranceMatches.size == 1) return sampleToleranceMatches.first()
            }
            val toleranceMatches = candidates.filter {
                abs(it.duration - identity.durationMs) <= 200L
            }
            if (toleranceMatches.size == 1) return toleranceMatches.first()
            if (candidates.size == 1) return candidates.first()
            return null
        }

        companion object {
            private const val TOTAL_SAMPLES_TOLERANCE = 10_000L
        }
    }
}
