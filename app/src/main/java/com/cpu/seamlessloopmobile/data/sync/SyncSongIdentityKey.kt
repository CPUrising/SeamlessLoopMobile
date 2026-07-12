package com.cpu.seamlessloopmobile.data.sync

/**
 * Stable sync identity for cross-device merge de-duplication.
 *
 * `totalSamples` can differ slightly between Android/native decoders and the
 * desktop importer for the same file, so it must remain an auxiliary matching
 * field rather than part of the primary sync key.
 */
internal data class SyncSongStableKey(
    val fileNameKey: String,
    val durationMs: Long
)

internal fun SyncSongIdentity.stableKey(): SyncSongStableKey =
    SyncSongStableKey(
        fileNameKey = normalizedFileName.ifBlank { normalizeSyncFileName(fileName) },
        durationMs = durationMs
    )

internal fun areSameSongIdentity(
    first: SyncSongIdentity,
    second: SyncSongIdentity
): Boolean = first.stableKey() == second.stableKey()

/**
 * Reduces metadata for playback entries with the same exact stable key.
 *
 * The raw filename winner is the lexicographically smallest UTF-16 code-unit
 * string (`String.compareTo`, an ordinal comparison). This is deterministic
 * across Android and desktop implementations after valid normalization. The
 * largest non-null sample count and largest nonblank content hash are kept.
 */
internal fun reducePlaybackSongIdentity(
    identities: Iterable<SyncSongIdentity>
): SyncSongIdentity {
    val entries = identities.toList()
    require(entries.isNotEmpty()) { "At least one playback identity is required" }
    val key = entries.first().stableKey()
    require(entries.all { it.stableKey() == key }) {
        "Playback identity reducer requires one exact stable key"
    }

    val validRawNames = entries.filter {
        it.fileName.isNotBlank() && normalizeSyncFileName(it.fileName) == key.fileNameKey
    }
    val fileName = (validRawNames.ifEmpty { entries })
        .minWithOrNull(compareBy<SyncSongIdentity> { it.fileName })!!
        .fileName
    val contentHash = entries.mapNotNull { it.contentHash?.takeIf(String::isNotBlank) }
        .maxOrNull()

    return SyncSongIdentity(
        fileName = fileName,
        durationMs = key.durationMs,
        totalSamples = entries.mapNotNull { it.totalSamples }.maxOrNull(),
        normalizedFileName = key.fileNameKey,
        contentHash = contentHash
    )
}

internal fun preferStableSongIdentity(
    remote: SyncSongIdentity?,
    local: SyncSongIdentity?
): SyncSongIdentity? = remote ?: local
