package com.cpu.seamlessloopmobile.data.sync

/**
 * 远程快照操作接口（用于 Repository 可测性）。
 * 由 [github.GitHubContentsSyncBackend] 实现。
 */
interface GitHubSnapshotRemote {
    suspend fun downloadSnapshot(snapshotId: String?): SyncResult
    suspend fun uploadSnapshot(snapshot: SyncSnapshot, expectedRevision: String?): SyncResult
    suspend fun deleteSnapshot(): SyncResult
}

// ===================================================================
// 数据摘要
// ===================================================================

/** 本地同步数据摘要。 */
data class LocalSyncDataSummary(
    val songCount: Int,
    val playlistCount: Int,
    val playlistItemCount: Int,
    val loopPointCount: Int,
    val ratingCount: Int
)

/** 云端同步数据预览。 */
data class CloudSyncDataPreview(
    val exists: Boolean,
    val deviceId: String = "",
    val exportedAt: Long = 0L,
    val playlists: Int = 0,
    val playlistItems: Int = 0,
    val loopPointCount: Int = 0,
    val ratingCount: Int = 0,
    val matchedSongReferenceCount: Int = 0,
    val missingSongReferenceCount: Int = 0,
    val missingSongReferences: List<SyncSongIdentity> = emptyList()
)

/** 同步数据管理预览（本地 + 云端）。 */
data class SyncDataManagementPreview(
    val local: LocalSyncDataSummary,
    val cloud: CloudSyncDataPreview?
)

/** Local playback-stat source device information for data-management screens. */
data class PlaybackStatsSourceDeviceSummary(
    val deviceId: String,
    val displayName: String,
    val fallbackLabel: String,
    val platform: String,
    val currentGeneration: Long?,
    val isCurrentDevice: Boolean,
    val contributedListenMs: Long,
    val hasEffectiveContributions: Boolean,
    val allKnownGenerationsRemoved: Boolean
)

/** 清除本地同步数据的选择。 */
data class ClearLocalSyncDataSelection(
    val clearPlaylists: Boolean = false,
    val clearLoopPoints: Boolean = false,
    val clearRatings: Boolean = false,
    val clearListenStats: Boolean = false
) {
    val hasSelection: Boolean
        get() = clearPlaylists || clearLoopPoints || clearRatings || clearListenStats
}

// ===================================================================
// 通用结果封装
// ===================================================================

sealed class SyncDataManagementResult<out T> {
    data class Success<T>(val data: T) : SyncDataManagementResult<T>()
    data class Failure(
        val message: String,
        val code: SyncErrorCode = SyncErrorCode.UNKNOWN
    ) : SyncDataManagementResult<Nothing>()
}
