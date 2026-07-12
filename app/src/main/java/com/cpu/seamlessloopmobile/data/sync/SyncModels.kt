package com.cpu.seamlessloopmobile.data.sync

import java.text.Normalizer
import java.time.LocalDate
import java.util.Locale

/**
 * 同步用的歌曲身份标识。
 * 不包含 filePath，使用 fileName + durationMs 作为跨设备匹配主键。
 * totalSamples 作为辅助匹配字段（可选）。
 */
data class SyncSongIdentity(
    val fileName: String,
    val durationMs: Long,
    val totalSamples: Long? = null,
    val normalizedFileName: String = normalizeSyncFileName(fileName),
    val contentHash: String? = null
)


fun normalizeSyncFileName(fileName: String): String =
    Normalizer.normalize(fileName.trim(), Normalizer.Form.NFC).lowercase(Locale.ROOT)

/**
 * 循环点快照数据（已与 songId 解耦）。
 */
data class SyncLoopPoint(
    val loopStart: Long,
    val loopEnd: Long,
    val lastModified: Long
) {
    /** True when this entry carries a real loop range rather than an unset sentinel. */
    val isSubstantive: Boolean get() = !isUnset(loopStart, loopEnd)

    companion object {
        /** Sentinel value indicating an unset/zero loop point. */
        fun isUnset(loopStart: Long, loopEnd: Long): Boolean =
            loopStart == 0L && loopEnd == 0L
    }
}

/**
 * 用户评分快照数据（已与 songId 解耦）。
 */
data class SyncRating(
    val rating: Int,
    val lastModified: Long
) {
    companion object {
        /** Sentinel value: rating 0 means unset. */
        const val UNSET_RATING: Int = 0
    }
}

/**
 * 歌单中的单曲条目，用 SyncSongIdentity 标识歌曲。
 */
data class SyncPlaylistItem(
    val song: SyncSongIdentity,
    val sortOrder: Int
)

/**
 * 歌单快照。
 * id 使用字符串以保证跨设备可移植性（如 UUID 或 name+createdAt 哈希）。
 */
data class SyncPlaylist(
    val id: String,
    val name: String,
    val createdAt: Long,
    val modifiedAt: Long,
    val items: List<SyncPlaylistItem> = emptyList()
)

/**
 * SyncSnapshot 中使用的（歌曲身份 -> 循环点）条目对。
 * 使用列表而非 Map 以便 Gson 序列化。
 */
data class SyncLoopPointEntry(
    val song: SyncSongIdentity,
    val loopPoint: SyncLoopPoint
)

/**
 * SyncSnapshot 中使用的（歌曲身份 -> 评分）条目对。
 * 使用列表而非 Map 以便 Gson 序列化。
 */
data class SyncRatingEntry(
    val song: SyncSongIdentity,
    val rating: SyncRating
)

/**
 * 当前支持的快照 schema 版本。
 */
const val SYNC_SCHEMA_VERSION_V2 = 2
const val CURRENT_SYNC_SCHEMA_VERSION = SYNC_SCHEMA_VERSION_V2

enum class SyncDateBucketBasis {
    @com.google.gson.annotations.SerializedName("sourceLocal")
    SOURCE_LOCAL
}

/** A playback-stat source device registered by a v2 snapshot. */
data class SyncPlaybackStatsDevice(
    val deviceId: String,
    val displayName: String,
    val firstSeenAtUtcMs: Long,
    val lastSeenAtUtcMs: Long,
    val currentGeneration: Long = 0L,
    val platform: String = "unknown",
    val displayNameUpdatedAtUtcMs: Long = firstSeenAtUtcMs
)

/** A monotonic contribution for one source device and generation. */
data class SyncPlaybackStatsContribution(
    val deviceId: String,
    val generation: Long,
    val datedListenMs: Map<String, Long> = emptyMap(),
    val undatedListenMs: Long = 0L,
    val firstPlayedAtUtcMs: Long = 0L,
    val lastPlayedAtUtcMs: Long = 0L,
    val updatedAtUtcMs: Long = 0L
)

data class SyncPlaybackStatsSong(
    val song: SyncSongIdentity,
    val contributions: List<SyncPlaybackStatsContribution> = emptyList()
)

/** Safely validates a playback-stat song parsed from untrusted JSON. */
fun SyncPlaybackStatsSong.isSemanticallyValid(): Boolean = runCatching {
    require(song.fileName.isNotBlank())
    require(song.normalizedFileName.isNotBlank())
    require(song.normalizedFileName == normalizeSyncFileName(song.fileName))
    require(song.durationMs >= 0L)
    require(song.totalSamples == null || song.totalSamples >= 0L)

    val contributionKeys = mutableSetOf<Pair<String, Long>>()
    contributions.forEach { contribution ->
        require(contribution.deviceId.isNotBlank())
        require(contribution.generation >= 0L)
        require(contribution.undatedListenMs >= 0L)
        require(contribution.firstPlayedAtUtcMs >= 0L)
        require(contribution.lastPlayedAtUtcMs >= 0L)
        require(contribution.updatedAtUtcMs >= 0L)
        require(contributionKeys.add(contribution.deviceId to contribution.generation))
        contribution.datedListenMs.forEach { (date, listenMs) ->
            LocalDate.parse(date)
            require(listenMs >= 0L)
        }
    }
}.isSuccess

/** Permanently suppresses contributions for a device generation. */
enum class SyncPlaybackStatsTombstoneScope {
    @com.google.gson.annotations.SerializedName("deviceGeneration")
    DEVICE_GENERATION
}

data class SyncPlaybackStatsTombstone(
    val deviceId: String,
    val generation: Long,
    val tombstonedAtUtcMs: Long,
    val scope: SyncPlaybackStatsTombstoneScope = SyncPlaybackStatsTombstoneScope.DEVICE_GENERATION,
    val tombstonedByDeviceId: String = deviceId,
    val reason: String = "deleted"
)

data class SyncPlaybackStats(
    val dateBucketBasis: SyncDateBucketBasis = SyncDateBucketBasis.SOURCE_LOCAL,
    val devices: List<SyncPlaybackStatsDevice> = emptyList(),
    val songs: List<SyncPlaybackStatsSong> = emptyList(),
    val tombstones: List<SyncPlaybackStatsTombstone> = emptyList()
) {
    fun sorted(): SyncPlaybackStats = copy(
        devices = devices.sortedBy { it.deviceId },
        songs = songs.sortedWith(
            compareBy<SyncPlaybackStatsSong> { it.song.normalizedFileName }
                .thenBy { it.song.durationMs }
        ).map { song ->
            song.copy(contributions = song.contributions.sortedWith(
                compareBy<SyncPlaybackStatsContribution> { it.deviceId }.thenBy { it.generation }
            ).map { it.copy(datedListenMs = it.datedListenMs.toSortedMap()) })
        },
        tombstones = tombstones.sortedWith(
            compareBy<SyncPlaybackStatsTombstone> { it.deviceId }.thenBy { it.generation }
        )
    )
}

fun SyncSnapshot.canonicalized(): SyncSnapshot = copy(
    playlists = playlists.map { playlist ->
        playlist.copy(items = playlist.items.map { item -> item.copy(song = item.song.normalized()) })
    },
    loopPoints = loopPoints.map { it.copy(song = it.song.normalized()) },
    ratings = ratings.map { it.copy(song = it.song.normalized()) },
    playbackStats = playbackStats.sorted()
)

fun SyncSongIdentity.normalized(): SyncSongIdentity = copy(
    normalizedFileName = normalizedFileName.takeUnless { it.isBlank() }
        ?: normalizeSyncFileName(fileName)
)

/**
 * 完整的同步快照，包含播放列表、循环点和评分数据。
 * 不包含设备特定设置或原始 Room 数据库路径。
 */
data class SyncSnapshot(
    val schemaVersion: Int = CURRENT_SYNC_SCHEMA_VERSION,
    val deviceId: String,
    val exportedAt: Long,
    val playlists: List<SyncPlaylist> = emptyList(),
    val loopPoints: List<SyncLoopPointEntry> = emptyList(),
    val ratings: List<SyncRatingEntry> = emptyList(),
    @com.google.gson.annotations.SerializedName("playbackStatistics")
    val playbackStats: SyncPlaybackStats = SyncPlaybackStats()
)

/**
 * Lightweight remote snapshot listing entry for sync backends.
 */
data class SyncSnapshotSummary(
    val id: String,
    val schemaVersion: Int,
    val deviceId: String,
    val exportedAt: Long
)
