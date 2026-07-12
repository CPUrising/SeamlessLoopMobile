package com.cpu.seamlessloopmobile.data.stats

import java.text.Normalizer
import java.util.Locale

/** Local-only persistence schema for playback statistics. */
data class ListenStatsStore(
    val schemaVersion: Int = SCHEMA_VERSION,
    val devices: List<ListenStatsDevice> = emptyList(),
    val currentDeviceId: String = "",
    val currentGeneration: Long = 0L,
    val songs: List<ListenStatsSongNode> = emptyList(),
    val tombstones: List<ListenStatsTombstone> = emptyList(),
    val unresolvedNodes: List<ListenStatsUnresolvedNode> = emptyList()
) {
    companion object {
        const val SCHEMA_VERSION = 3
    }
}

data class ListenStatsDevice(
    val deviceId: String,
    val displayName: String = "",
    val displayNameUpdatedAtUtcMs: Long = 0L,
    val platform: String = "android",
    val appVersion: String = "",
    val currentGeneration: Long = 0L,
    val createdAt: Long = 0L,
    val lastSeenAt: Long = 0L,
    val updatedAtUtcMs: Long = 0L
)

data class ListenStatsSongNode(
    /** Canonical local key for immutable wire (normalized filename, duration) identity. */
    val identityKey: String,
    /** Immutable wire identity fields. */
    val normalizedFileName: String,
    val fileName: String = "",
    /** Local Room binding; never part of the immutable wire identity. */
    val boundSongId: Long = 0L,
    /** Optional immutable wire auxiliaries. */
    val totalSamples: Long? = null,
    val contentHash: String? = null,
    /** Local binding/presentation metadata, refreshed from the bound Room song. */
    val displayName: String = "",
    val artist: String = "",
    val album: String = "",
    val coverPath: String? = null,
    val durationMs: Long = 0L,
    val lastPlayedAt: Long = 0L,
    val firstPlayedAt: Long = 0L,
    val filePath: String = "",
    val contributions: List<ListenStatsContribution> = emptyList()
)

data class ListenStatsContribution(
    val deviceId: String,
    val generation: Long,
    val dailyListenMs: Map<String, Long> = emptyMap(),
    val undatedListenMs: Long = 0L,
    val firstPlayedAtUtcMs: Long = 0L,
    val lastPlayedAtUtcMs: Long = 0L,
    val updatedAtUtcMs: Long = 0L
)

data class ListenStatsTombstone(
    val deviceId: String,
    val generation: Long,
    val tombstonedAtUtcMs: Long = 0L,
    val operatorDeviceId: String = "",
    val reason: String = "",
    val scope: String = SCOPE_DEVICE_GENERATION
) {
    companion object {
        const val SCOPE_DEVICE_GENERATION = "device_generation"
    }
}

/** A lossless placeholder for a synced node that cannot yet be matched locally. */
data class ListenStatsUnresolvedNode(
    val normalizedFileName: String,
    val durationMs: Long,
    val payloadJson: String,
    val receivedAt: Long = 0L
)

fun normalizedStatsFileName(fileName: String): String =
    Normalizer.normalize(fileName.trim(), Normalizer.Form.NFC).lowercase(Locale.ROOT)

/** Local intermediate shape for later snapshot-store integration. */
data class ListenStatsLocalPayload(
    val currentDeviceId: String,
    val currentGeneration: Long,
    val devices: List<ListenStatsDevice>,
    val songs: List<ListenStatsSongNode>,
    val tombstones: List<ListenStatsTombstone>,
    val unresolvedNodes: List<ListenStatsUnresolvedNode>
)

data class ListenStatsSource(
    val device: ListenStatsDevice,
    val currentGeneration: Long,
    val isCurrentDevice: Boolean
)

/** Captures the current local generation for rejecting stale playback flushes. */
data class ListenStatsWriteFence internal constructor(
    internal val deviceId: String,
    internal val generation: Long,
    internal val epoch: Long
)
