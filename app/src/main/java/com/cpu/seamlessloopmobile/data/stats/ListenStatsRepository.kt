package com.cpu.seamlessloopmobile.data.stats

import android.content.Context
import android.os.Build
import com.cpu.seamlessloopmobile.data.sync.SharedPreferencesGitHubSyncStore
import com.cpu.seamlessloopmobile.data.sync.SyncSongIdentity
import com.cpu.seamlessloopmobile.data.sync.reducePlaybackSongIdentity
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId

/**
 * Persists a versioned local contribution store while exposing aggregated [TrackStat]s.
 *
 * All mutations are serialised through a [Mutex] and run on [Dispatchers.IO].
 *
 * @param jsonFile  The file that holds the current object schema.
 * @param gson      Gson instance for serialisation.
 * @param wallClockMillis Current wall-clock epoch milliseconds.
 * @param zoneId    Optional fixed zone used to assign deltas to local calendar days.
 * @param zoneIdProvider Supplies the current system zone when [zoneId] is not fixed.
 */
class ListenStatsRepository(
    private val jsonFile: File,
    private val currentDeviceIdProvider: (() -> String)? = null,
    private val currentDeviceDisplayNameProvider: (() -> String)? = null,
    private val onMaterialMutation: (suspend () -> Unit)? = null,
    private val gson: Gson = Gson(),
    private val wallClockMillis: () -> Long = { System.currentTimeMillis() },
    private val zoneId: ZoneId? = null,
    private val zoneIdProvider: () -> ZoneId = { ZoneId.systemDefault() }
) {
    companion object {
        @Volatile
        private var instance: ListenStatsRepository? = null
        private const val DEFAULT_LOCAL_DEVICE_ID = "local-device"

        fun getInstance(context: Context): ListenStatsRepository {
            return instance ?: synchronized(this) {
                instance ?: ListenStatsRepository(
                    jsonFile = File(context.applicationContext.filesDir, "listen_stats.json"),
                    currentDeviceIdProvider = {
                        SharedPreferencesGitHubSyncStore.getOrCreateDeviceId(context.applicationContext)
                    },
                    currentDeviceDisplayNameProvider = {
                        listOf(Build.MANUFACTURER, Build.MODEL)
                            .map { it.orEmpty().trim() }
                            .filter { it.isNotBlank() }
                            .joinToString(" ")
                            .ifBlank { "Android device" }
                    },
                    onMaterialMutation = {
                        SharedPreferencesGitHubSyncStore(context.applicationContext).markMutation()
                    }
                ).also { instance = it }
            }
        }
    }

    private val mutex = Mutex()
    private var store = loadStoreFromDisk()
    private var writeEpoch = 0L
    private val _allStats = MutableStateFlow(store.toTrackStats())
    private val _clearEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Observable presentation snapshot of all tracked songs. UI sorts and filters externally. */
    val allStats: StateFlow<List<TrackStat>> = _allStats.asStateFlow()

    /** Emitted after statistics have been durably cleared from disk. */
    val clearEvents: SharedFlow<Unit> = _clearEvents

    // --- Public API ----------------------------------------------------------

    /**
     * Records (adds or merges) a listened-time delta for the song identified
     * by the exact normalized filename and raw duration from [stat].
     * the delta is accumulated into [TrackStat.totalListenMs] and the played-at
     * timestamps are updated. Otherwise a new entry is added.
     *
     * @param stat       A [TrackStat] built from the current [Song] + delta.
     * @param listenedMs The wall-clock milliseconds to add (>= 0).
     */
    suspend fun recordListenDeltaNow(
        stat: TrackStat,
        listenedMs: Long,
        writeFence: ListenStatsWriteFence? = null
    ): Boolean {
        if (listenedMs <= 0L) return false
        return withContext(Dispatchers.IO) {
            val now = wallClockMillis()
            val dailyDeltas = splitDeltaAcrossLocalDates(
                end = Instant.ofEpochMilli(now),
                listenedMs = listenedMs,
                zone = zoneId ?: zoneIdProvider()
            )
            mutex.withLock {
                if (writeFence != null && !writeFence.matches(store, writeEpoch)) return@withLock false
                val current = store.songs.toMutableList()
                val wireIdentity = SyncSongIdentity(
                    fileName = stat.fileName,
                    durationMs = stat.durationMs,
                    normalizedFileName = normalizedStatsFileName(stat.fileName)
                )
                val wireKey = wireIdentity.stableKeyForLocalNode()
                val idx = current.indexOfFirst { it.wireKey() == wireKey }
                if (idx >= 0) {
                    val existing = current[idx]
                    val contributionIndex = existing.contributions.indexOfFirst {
                        it.deviceId == store.currentDeviceId && it.generation == store.currentGeneration
                    }
                    val contributions = existing.contributions.toMutableList()
                    val existingContribution = contributions.getOrNull(contributionIndex)
                        ?: ListenStatsContribution(store.currentDeviceId, store.currentGeneration)
                    val updatedContribution = existingContribution.copy(
                        dailyListenMs = existingContribution.dailyListenMs.withAddedDeltas(dailyDeltas),
                        firstPlayedAtUtcMs = existingContribution.firstPlayedAtUtcMs.takeIf { it > 0L } ?: now,
                        lastPlayedAtUtcMs = now,
                        updatedAtUtcMs = now
                    )
                    if (contributionIndex >= 0) contributions[contributionIndex] = updatedContribution
                    else contributions.add(updatedContribution)
                    val reducedIdentity = reducePlaybackSongIdentity(listOf(existing.toSyncIdentity(), wireIdentity))
                    current[idx] = existing.copy(
                        identityKey = reducedIdentity.localIdentityKey(),
                        normalizedFileName = reducedIdentity.normalizedFileName,
                        fileName = reducedIdentity.fileName,
                        durationMs = reducedIdentity.durationMs,
                        boundSongId = if (stat.songId > 0L) stat.songId else existing.boundSongId,
                        displayName = stat.displayName.ifEmpty { existing.displayName },
                        artist = stat.artist.ifEmpty { existing.artist },
                        album = stat.album.ifEmpty { existing.album },
                        coverPath = stat.coverPath ?: existing.coverPath,
                        lastPlayedAt = now,
                        firstPlayedAt = if (existing.firstPlayedAt == 0L) now else existing.firstPlayedAt,
                        filePath = stat.filePath.ifEmpty { existing.filePath },
                        totalSamples = reducedIdentity.totalSamples,
                        contentHash = reducedIdentity.contentHash,
                        contributions = contributions
                    )
                } else {
                    current.add(
                        ListenStatsSongNode(
                            identityKey = wireIdentity.localIdentityKey(),
                            normalizedFileName = wireIdentity.normalizedFileName,
                            boundSongId = stat.songId,
                            displayName = stat.displayName,
                            artist = stat.artist,
                            album = stat.album,
                            coverPath = stat.coverPath,
                            durationMs = stat.durationMs,
                            lastPlayedAt = now,
                            firstPlayedAt = now,
                            filePath = stat.filePath,
                            fileName = stat.fileName,
                            totalSamples = wireIdentity.totalSamples,
                            contentHash = wireIdentity.contentHash,
                            contributions = listOf(
                                ListenStatsContribution(
                                    deviceId = store.currentDeviceId,
                                    generation = store.currentGeneration,
                                    dailyListenMs = dailyDeltas,
                                    firstPlayedAtUtcMs = now,
                                    lastPlayedAtUtcMs = now,
                                    updatedAtUtcMs = now
                                )
                            )
                        )
                    )
                }
                store = store.copy(songs = current).normalized()
                saveToDisk(store)
                _allStats.value = store.toTrackStats()
                onMaterialMutation?.invoke()
                true
            }
        }
    }

    /** Clears the current device by tombstoning its generation and rotating to a new one. */
    suspend fun clearAll() {
        clearCurrentDeviceStats()
    }

    /** Emits [clearEvents] only after tombstone and generation rotation are durable. */
    suspend fun clearCurrentDeviceStats(reason: String = "local_clear") {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val now = wallClockMillis()
                val tombstone = ListenStatsTombstone(
                    deviceId = store.currentDeviceId,
                    generation = store.currentGeneration,
                    tombstonedAtUtcMs = now,
                    operatorDeviceId = store.currentDeviceId,
                    reason = reason
                )
                val nextGeneration = store.allKnownGenerations().let {
                    if (it == Long.MAX_VALUE) Long.MAX_VALUE else it + 1L
                }
                store = store.copy(
                    currentGeneration = nextGeneration,
                    tombstones = (store.tombstones + tombstone).distinctBy { it.deviceId to it.generation }
                )
                writeEpoch = writeEpoch.saturatingAdd(1L)
                saveToDiskOrThrow(store)
                _allStats.value = store.toTrackStats()
                onMaterialMutation?.invoke()
                _clearEvents.tryEmit(Unit)
            }
        }
    }

    /** Captures a fence which a later playback flush must still match to be accepted. */
    suspend fun captureWriteFence(): ListenStatsWriteFence = withContext(Dispatchers.IO) {
        mutex.withLock { ListenStatsWriteFence(store.currentDeviceId, store.currentGeneration, writeEpoch) }
    }

    suspend fun currentSource(): ListenStatsSource = withContext(Dispatchers.IO) {
        mutex.withLock {
            val device = store.devices.first { it.deviceId == store.currentDeviceId }
            ListenStatsSource(device, store.currentGeneration, true)
        }
    }

    suspend fun knownSources(): List<ListenStatsSource> = withContext(Dispatchers.IO) {
        mutex.withLock {
            store.devices.map { device ->
                ListenStatsSource(device, if (device.deviceId == store.currentDeviceId) store.currentGeneration else -1L,
                    device.deviceId == store.currentDeviceId)
            }
        }
    }

    suspend fun exportLocalPayload(): ListenStatsLocalPayload = withContext(Dispatchers.IO) {
        mutex.withLock {
            ListenStatsLocalPayload(
                currentDeviceId = store.currentDeviceId,
                currentGeneration = store.currentGeneration,
                devices = store.devices,
                songs = store.songs,
                tombstones = store.tombstones,
                unresolvedNodes = store.unresolvedNodes
            )
        }
    }

    /** Applies a previously merged local payload; snapshot serialization remains outside this repository. */
    suspend fun applyLocalPayload(
        payload: ListenStatsLocalPayload,
        trackMutation: Boolean = true
    ) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val merged = store.copy(
                    currentDeviceId = payload.currentDeviceId.ifBlank { store.currentDeviceId },
                    currentGeneration = payload.currentGeneration.coerceAtLeast(store.currentGeneration),
                    devices = mergeDevices(store.devices, payload.devices),
                    // The payload can have been exported before a playback flush. Keep the
                    // current nodes too; normalized() collapses exact wire identities and
                    // takes the per-(device, generation) cumulative maximum.
                    songs = mergePayloadSongs(store.songs, payload.songs),
                    tombstones = mergeTombstones(store.tombstones, payload.tombstones),
                    unresolvedNodes = mergeUnresolvedNodes(
                        store.unresolvedNodes,
                        payload.unresolvedNodes,
                        payload.songs
                    )
                ).normalized()
                val activeGenerationWasTombstoned = merged.tombstones.any {
                    it.deviceId == merged.currentDeviceId && it.generation == merged.currentGeneration
                }
                val nextStore = if (activeGenerationWasTombstoned) {
                    val nextGeneration = merged.allKnownGenerations().let {
                        if (it == Long.MAX_VALUE) Long.MAX_VALUE else it + 1L
                    }
                    writeEpoch = writeEpoch.saturatingAdd(1L)
                    merged.copy(currentGeneration = nextGeneration)
                } else merged
                if (nextStore != store) {
                    store = nextStore
                    saveToDiskOrThrow(store)
                    _allStats.value = store.toTrackStats()
                    if (trackMutation) onMaterialMutation?.invoke()
                }
                if (activeGenerationWasTombstoned) _clearEvents.tryEmit(Unit)
            }
        }
    }

    /**
     * Convenience accessor for querying a single song's stats by presentation
     * or exact recording identity. Exact wire lookups for a bound node return
     * that node's aggregated presentation row.
     */
    suspend fun getByIdentityKey(identityKey: String): TrackStat? {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                _allStats.value.find { it.identityKey == identityKey }
                    ?: store.songs.firstOrNull { it.identityKey == identityKey }
                        ?.let { node ->
                            if (node.boundSongId > 0L) {
                                _allStats.value.find {
                                    it.identityKey == TrackStat.boundPresentationIdentityKey(node.boundSongId)
                                }
                            } else {
                                null
                            }
                        }
            }
        }
    }

    // --- Internal IO ----------------------------------------------------------

    private fun loadStoreFromDisk(): ListenStatsStore {
        if (!jsonFile.exists() || jsonFile.length() == 0L) {
            return newStore()
        }
        return try {
            val text = jsonFile.readText()
            val root = JsonParser.parseString(text)
            if (!root.isJsonObject) return newStore()
            val schema = root.asJsonObject.get("schemaVersion")
            if (schema == null || schema.isJsonNull || !schema.isJsonPrimitive ||
                !schema.asJsonPrimitive.isNumber ||
                schema.asString.toIntOrNull() != ListenStatsStore.SCHEMA_VERSION
            ) return newStore()

            val parsed = gson.fromJson(root, ListenStatsStore::class.java) ?: return newStore()
            val normalized = parsed.normalized()
            if (parsed.requiresDeviceDisplayNameBackfill()) saveToDiskOrThrow(normalized)
            normalized
        } catch (e: Exception) {
            newStore()
        }
    }

    private fun newStore(): ListenStatsStore {
        val deviceId = resolveCurrentDeviceId()
        val now = wallClockMillis()
        return ListenStatsStore(
            devices = listOf(
                ListenStatsDevice(
                    deviceId = deviceId,
                    displayName = resolveCurrentDeviceDisplayName(),
                    displayNameUpdatedAtUtcMs = now,
                    platform = "android",
                    currentGeneration = 0L,
                    createdAt = now,
                    lastSeenAt = now,
                    updatedAtUtcMs = now
                )
            ),
            currentDeviceId = deviceId,
            currentGeneration = 0L
        )
    }

    private fun ListenStatsStore.normalized(): ListenStatsStore {
        val preferredDeviceId = resolveCurrentDeviceId()
        val deviceId = preferredDeviceId.ifBlank { currentDeviceId }.ifBlank { resolveCurrentDeviceId() }
        val existingDevice = devices.find { it.deviceId == deviceId }
        val normalizedCurrentGeneration = currentGeneration.coerceAtLeast(0L)
        val deviceList = if (existingDevice != null) {
            devices.map {
                if (it.deviceId == deviceId) {
                    it.copy(
                        displayName = it.displayName.ifBlank { resolveCurrentDeviceDisplayName() },
                        displayNameUpdatedAtUtcMs = if (it.displayName.isBlank()) wallClockMillis() else it.displayNameUpdatedAtUtcMs,
                        platform = if (it.platform.isBlank()) "android" else it.platform,
                        currentGeneration = normalizedCurrentGeneration,
                        lastSeenAt = wallClockMillis(),
                        updatedAtUtcMs = wallClockMillis()
                    )
                } else {
                    it.copy(
                        displayName = it.displayName.ifBlank { fallbackDisplayName(it.deviceId) },
                        displayNameUpdatedAtUtcMs = if (it.displayName.isBlank()) wallClockMillis() else it.displayNameUpdatedAtUtcMs
                    )
                }
            }
        } else {
            devices + ListenStatsDevice(
                deviceId = deviceId,
                displayName = resolveCurrentDeviceDisplayName(),
                displayNameUpdatedAtUtcMs = wallClockMillis(),
                platform = "android",
                currentGeneration = normalizedCurrentGeneration,
                createdAt = wallClockMillis(),
                lastSeenAt = wallClockMillis(),
                updatedAtUtcMs = wallClockMillis()
            )
        }
        return copy(
            schemaVersion = ListenStatsStore.SCHEMA_VERSION,
            currentDeviceId = deviceId,
            currentGeneration = normalizedCurrentGeneration,
            devices = deviceList.sortedBy { it.deviceId },
            songs = collapseDuplicateSongNodes(songs),
            tombstones = tombstones.sortedWith(compareBy<ListenStatsTombstone> { it.deviceId }.thenBy { it.generation }),
            unresolvedNodes = unresolvedNodes.sortedWith(compareBy<ListenStatsUnresolvedNode> { it.normalizedFileName }.thenBy { it.durationMs })
        )
    }

    private fun mergeDevices(
        current: List<ListenStatsDevice>,
        incoming: List<ListenStatsDevice>
    ): List<ListenStatsDevice> = (current + incoming)
        .groupBy { it.deviceId }
        .values
        .map { devices ->
            devices.maxWithOrNull(
                compareBy<ListenStatsDevice> { it.updatedAtUtcMs }
                    .thenBy { it.lastSeenAt }
                    .thenBy { it.currentGeneration }
            )!!
        }

    private fun mergeTombstones(
        current: List<ListenStatsTombstone>,
        incoming: List<ListenStatsTombstone>
    ): List<ListenStatsTombstone> = (current + incoming)
        .groupBy { it.deviceId to it.generation }
        .values
        .map { tombstones -> tombstones.maxBy { it.tombstonedAtUtcMs } }

    /**
     * The incoming payload owns relinking and presentation metadata. Current nodes only
     * supply deltas recorded after export, merged under the existing cumulative-max rule.
     */
    private fun mergePayloadSongs(
        current: List<ListenStatsSongNode>,
        incoming: List<ListenStatsSongNode>
    ): List<ListenStatsSongNode> {
        val currentByWire = collapseDuplicateSongNodes(current).associateBy { it.wireKey() }
        val incomingByWire = collapseDuplicateSongNodes(incoming).associateBy { it.wireKey() }
        return (currentByWire.keys + incomingByWire.keys).sortedWith(
            compareBy<Pair<String, Long>> { it.first }.thenBy { it.second }
        ).mapNotNull { wireKey ->
            val currentNode = currentByWire[wireKey]
            val incomingNode = incomingByWire[wireKey]
            when {
                incomingNode == null -> currentNode
                currentNode == null -> incomingNode
                else -> {
                    val identity = reducePlaybackSongIdentity(
                        listOf(currentNode.toSyncIdentity(), incomingNode.toSyncIdentity())
                    )
                    val contributions = mergeLocalContributions(
                        currentNode.contributions + incomingNode.contributions
                    )
                    incomingNode.copy(
                        identityKey = identity.localIdentityKey(),
                        normalizedFileName = identity.normalizedFileName,
                        fileName = identity.fileName,
                        durationMs = identity.durationMs,
                        totalSamples = identity.totalSamples,
                        contentHash = identity.contentHash,
                        contributions = contributions,
                        firstPlayedAt = contributions.map { it.firstPlayedAtUtcMs }
                            .filter { it > 0L }.minOrNull() ?: 0L,
                        lastPlayedAt = contributions.maxOfOrNull { it.lastPlayedAtUtcMs } ?: 0L
                    )
                }
            }
        }
    }

    private fun mergeUnresolvedNodes(
        current: List<ListenStatsUnresolvedNode>,
        incoming: List<ListenStatsUnresolvedNode>,
        incomingSongs: List<ListenStatsSongNode>
    ): List<ListenStatsUnresolvedNode> {
        val resolvedWireKeys = incomingSongs.map {
            normalizedStatsFileName(it.fileName) to it.durationMs
        }.toSet()
        return (current.filterNot { node ->
            (normalizedStatsFileName(node.normalizedFileName) to node.durationMs) in resolvedWireKeys
        } + incoming).distinct()
    }

    private fun collapseDuplicateSongNodes(
        nodes: List<ListenStatsSongNode>
    ): List<ListenStatsSongNode> {
        val prepared = nodes.map { node ->
            val normalizedFileName = normalizedStatsFileName(node.fileName)
            node.copy(
                identityKey = localIdentityKey(normalizedFileName, node.durationMs),
                normalizedFileName = normalizedFileName,
                contributions = node.contributions.sortedWith(
                    compareBy<ListenStatsContribution> { it.deviceId }.thenBy { it.generation }
                )
            )
        }
        return prepared.groupBy { it.wireKey() }
            .toSortedMap(compareBy<Pair<String, Long>> { it.first }.thenBy { it.second })
            .values
            .map { duplicates ->
                val identity = reducePlaybackSongIdentity(duplicates.map { it.toSyncIdentity() })
                val binding = duplicates.minWithOrNull(
                    compareBy<ListenStatsSongNode> { if (it.boundSongId > 0L) 0 else 1 }
                        .thenBy { it.boundSongId }
                        .thenBy { it.filePath }
                        .thenBy { it.displayName }
                )!!
                val contributions = mergeLocalContributions(
                    duplicates.flatMap { it.contributions }
                )
                binding.copy(
                    identityKey = identity.localIdentityKey(),
                    normalizedFileName = identity.normalizedFileName,
                    fileName = identity.fileName,
                    durationMs = identity.durationMs,
                    totalSamples = identity.totalSamples,
                    contentHash = identity.contentHash,
                    contributions = contributions,
                    firstPlayedAt = contributions.map { it.firstPlayedAtUtcMs }
                        .filter { it > 0L }.minOrNull() ?: 0L,
                    lastPlayedAt = contributions.maxOfOrNull { it.lastPlayedAtUtcMs } ?: 0L
                )
            }
    }

    private fun mergeLocalContributions(
        contributions: List<ListenStatsContribution>
    ): List<ListenStatsContribution> = contributions
        .groupBy { it.deviceId to it.generation }
        .toSortedMap(compareBy<Pair<String, Long>> { it.first }.thenBy { it.second })
        .values
        .map { duplicates ->
            duplicates.drop(1).fold(duplicates.first()) { first, second ->
                ListenStatsContribution(
                    deviceId = first.deviceId,
                    generation = first.generation,
                    dailyListenMs = (first.dailyListenMs.keys + second.dailyListenMs.keys)
                        .associateWith { date ->
                            maxOf(first.dailyListenMs[date] ?: 0L, second.dailyListenMs[date] ?: 0L)
                        },
                    undatedListenMs = maxOf(first.undatedListenMs, second.undatedListenMs),
                    firstPlayedAtUtcMs = earliestMeaningful(
                        first.firstPlayedAtUtcMs,
                        second.firstPlayedAtUtcMs
                    ),
                    lastPlayedAtUtcMs = maxOf(first.lastPlayedAtUtcMs, second.lastPlayedAtUtcMs),
                    updatedAtUtcMs = maxOf(first.updatedAtUtcMs, second.updatedAtUtcMs)
                )
            }
        }

    private fun ListenStatsSongNode.toSyncIdentity() = SyncSongIdentity(
        fileName = fileName,
        durationMs = durationMs,
        totalSamples = totalSamples,
        normalizedFileName = normalizedFileName,
        contentHash = contentHash
    )

    private fun ListenStatsSongNode.wireKey(): Pair<String, Long> =
        normalizedFileName to durationMs

    private fun SyncSongIdentity.stableKeyForLocalNode(): Pair<String, Long> =
        normalizedFileName to durationMs

    private fun SyncSongIdentity.localIdentityKey(): String =
        localIdentityKey(normalizedFileName, durationMs)

    private fun localIdentityKey(normalizedFileName: String, durationMs: Long): String =
        "$normalizedFileName|$durationMs"

    private data class EffectiveSongStats(
        val node: ListenStatsSongNode,
        val dailyListenMs: Map<String, Long>,
        val undatedListenMs: Long,
        val firstPlayedAt: Long,
        val lastPlayedAt: Long
    )

    private fun ListenStatsStore.toTrackStats(): List<TrackStat> {
        // Tombstones must be applied before bound nodes are combined. The
        // backing store remains untouched so sync/export stays lossless.
        val effectiveNodes = songs.mapNotNull { node ->
            val contributions = node.contributions.filterNot { contribution ->
                tombstones.any { it.deviceId == contribution.deviceId && it.generation == contribution.generation }
            }
            if (contributions.isEmpty()) return@mapNotNull null

            val daily = contributions.fold(emptyMap<String, Long>()) { total, contribution ->
                total.withAddedDeltas(contribution.dailyListenMs)
            }
            val hasContributionFirstPlayedAt = node.contributions.any {
                it.firstPlayedAtUtcMs > 0L
            }
            val hasContributionLastPlayedAt = node.contributions.any {
                it.lastPlayedAtUtcMs > 0L
            }
            EffectiveSongStats(
                node = node,
                dailyListenMs = daily,
                undatedListenMs = contributions.fold(0L) { total, contribution ->
                    total.saturatingAdd(contribution.undatedListenMs.coerceAtLeast(0L))
                },
                firstPlayedAt = contributions.map { it.firstPlayedAtUtcMs }
                    .filter { it > 0L }
                    .minOrNull()
                    ?: if (!hasContributionFirstPlayedAt) node.firstPlayedAt.takeIf { it > 0L } ?: 0L else 0L,
                lastPlayedAt = contributions.map { it.lastPlayedAtUtcMs }
                    .filter { it > 0L }
                    .maxOrNull()
                    ?: if (!hasContributionLastPlayedAt) node.lastPlayedAt.takeIf { it > 0L } ?: 0L else 0L
            )
        }

        val boundRows = effectiveNodes
            .filter { it.node.boundSongId > 0L }
            .groupBy { it.node.boundSongId }
            .toSortedMap()
            .values
            .map { it.toBoundTrackStat() }
        val unboundRows = effectiveNodes
            .filter { it.node.boundSongId <= 0L }
            .map { it.toTrackStat(it.node.identityKey) }

        return (boundRows + unboundRows).sortedBy { it.identityKey }
    }

    private fun List<EffectiveSongStats>.toBoundTrackStat(): TrackStat {
        val representative = minWithOrNull(
            compareBy<EffectiveSongStats> { -bindingMetadataScore(it.node) }
                .thenBy { it.node.identityKey }
                .thenBy { it.node.filePath }
                .thenBy { it.node.fileName }
        )!!
        val daily = fold(emptyMap<String, Long>()) { total, stats ->
            total.withAddedDeltas(stats.dailyListenMs)
        }
        val undated = fold(0L) { total, stats -> total.saturatingAdd(stats.undatedListenMs) }
        val firstPlayedAt = map { it.firstPlayedAt }.filter { it > 0L }.minOrNull() ?: 0L
        val lastPlayedAt = maxOfOrNull { it.lastPlayedAt } ?: 0L
        return representative.toTrackStat(
            identityKey = TrackStat.boundPresentationIdentityKey(representative.node.boundSongId),
            dailyListenMs = daily,
            totalListenMs = daily.values.fold(0L) { total, value ->
                total.saturatingAdd(value.coerceAtLeast(0L))
            }.saturatingAdd(undated)
                .coerceAtLeast(0L),
            firstPlayedAt = firstPlayedAt,
            lastPlayedAt = lastPlayedAt
        )
    }

    private fun EffectiveSongStats.toTrackStat(
        identityKey: String,
        dailyListenMs: Map<String, Long> = this.dailyListenMs,
        totalListenMs: Long = this.effectiveTotalListenMs(),
        firstPlayedAt: Long = this.firstPlayedAt,
        lastPlayedAt: Long = this.lastPlayedAt
    ): TrackStat = TrackStat(
        songId = node.boundSongId,
        displayName = node.displayName,
        fileName = node.fileName,
        artist = node.artist,
        album = node.album,
        coverPath = node.coverPath,
        durationMs = node.durationMs,
        totalListenMs = totalListenMs,
        lastPlayedAt = lastPlayedAt,
        firstPlayedAt = firstPlayedAt,
        filePath = node.filePath,
        identityKey = identityKey,
        dailyListenMs = dailyListenMs
    )

    private fun EffectiveSongStats.effectiveTotalListenMs(): Long =
        dailyListenMs.values.fold(0L) { total, value ->
            total.saturatingAdd(value.coerceAtLeast(0L))
        }.saturatingAdd(undatedListenMs)

    private fun bindingMetadataScore(node: ListenStatsSongNode): Int = listOf(
        node.displayName,
        node.artist,
        node.album,
        node.coverPath.orEmpty(),
        node.filePath,
        node.fileName
    ).count { it.isNotBlank() } + if (node.durationMs > 0L) 1 else 0

    private fun saveToDisk(store: ListenStatsStore) {
        try {
            saveToDiskOrThrow(store)
        } catch (e: Exception) {
            // Swallow IO errors — stats are best-effort
        }
    }

    private fun saveToDiskOrThrow(store: ListenStatsStore) {
        jsonFile.parentFile?.mkdirs()
        val tempFile = File(jsonFile.parentFile, "${jsonFile.name}.tmp")
        tempFile.writeText(gson.toJson(store.normalized()))
        try {
            Files.move(tempFile.toPath(), jsonFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            Files.move(tempFile.toPath(), jsonFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun splitDeltaAcrossLocalDates(
        end: Instant,
        listenedMs: Long,
        zone: ZoneId
    ): Map<String, Long> {
        val start = end.minusMillis(listenedMs)
        val deltas = mutableMapOf<String, Long>()
        var cursor = start
        while (cursor < end) {
            val date = cursor.atZone(zone).toLocalDate()
            val nextMidnight = date.plusDays(1).atStartOfDay(zone).toInstant()
            val sliceEnd = if (nextMidnight < end) nextMidnight else end
            val sliceMs = java.time.Duration.between(cursor, sliceEnd).toMillis()
            deltas[date.toString()] = (deltas[date.toString()] ?: 0L).saturatingAdd(sliceMs)
            cursor = sliceEnd
        }
        return deltas
    }

    private fun Map<String, Long>.withAddedDeltas(deltas: Map<String, Long>): Map<String, Long> {
        return toMutableMap().apply {
            deltas.forEach { (dateKey, delta) ->
                this[dateKey] = (this[dateKey] ?: 0L).coerceAtLeast(0L).saturatingAdd(delta)
            }
        }
    }

    private fun Long.saturatingAdd(other: Long): Long {
        return if (this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other
    }

    private fun earliestMeaningful(first: Long, second: Long): Long = when {
        first == 0L -> second
        second == 0L -> first
        else -> minOf(first, second)
    }

    private fun ListenStatsWriteFence.matches(store: ListenStatsStore, currentEpoch: Long): Boolean =
        deviceId == store.currentDeviceId && generation == store.currentGeneration && epoch == currentEpoch

    private fun ListenStatsStore.allKnownGenerations(): Long = buildList {
        add(currentGeneration)
        devices.forEach { add(it.currentGeneration) }
        songs.flatMapTo(this) { node -> node.contributions.map { it.generation } }
        tombstones.forEach { add(it.generation) }
    }.maxOrNull() ?: 0L

    private fun resolveCurrentDeviceId(): String =
        currentDeviceIdProvider?.invoke()?.takeIf { it.isNotBlank() }
            ?: DEFAULT_LOCAL_DEVICE_ID

    private fun resolveCurrentDeviceDisplayName(): String =
        currentDeviceDisplayNameProvider?.invoke()?.trim()?.takeIf { it.isNotBlank() }
            ?: "Android device"

    private fun fallbackDisplayName(deviceId: String): String =
        "Device ${deviceId.ifBlank { "unknown" }}"

    private fun ListenStatsStore.requiresDeviceDisplayNameBackfill(): Boolean =
        devices.any { it.displayName.isBlank() }
}
