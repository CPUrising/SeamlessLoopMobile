package com.cpu.seamlessloopmobile.data.stats

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class ListenStatsRepositoryTest {

    @Test
    fun recordListenDeltaNowAccumulatesTotalAndTodaysBucket() = runTest {
        val today = LocalDate.of(2026, 7, 10)
        val repository = repositoryAt(today)
        val stat = stat()

        repository.recordListenDeltaNow(stat, 1_000L)
        repository.recordListenDeltaNow(stat, 2_000L)

        val saved = repository.getByIdentityKey(stat.identityKey)!!
        assertEquals(3_000L, saved.totalListenMs)
        assertEquals(3_000L, saved.dailyListenMs[today.toString()])
    }

    @Test
    fun listenMsForUsesMondayBasedWeekAndCalendarBoundaries() {
        val stat = stat(
            totalListenMs = 99_000L,
            dailyListenMs = mapOf(
                "2026-07-05" to 100L,
                "2026-07-06" to 200L,
                "2026-07-12" to 300L,
                "2026-07-13" to 400L,
                "2026-06-30" to 500L,
                "not-a-date" to 600L
            )
        )
        val today = LocalDate.of(2026, 7, 10)

        assertEquals(0L, stat.listenMsFor(ListenStatsPeriod.DAY, today))
        assertEquals(500L, stat.listenMsFor(ListenStatsPeriod.WEEK, today))
        assertEquals(1_000L, stat.listenMsFor(ListenStatsPeriod.MONTH, today))
        assertEquals(1_500L, stat.listenMsFor(ListenStatsPeriod.YEAR, today))
    }

    @Test
    fun listenMsForAllRetainsUndatedTotalWithoutDailyHistory() {
        val stat = stat(totalListenMs = 12_345L)

        assertEquals(12_345L, stat.listenMsFor(ListenStatsPeriod.ALL, LocalDate.of(2026, 7, 10)))
        assertEquals(0L, stat.listenMsFor(ListenStatsPeriod.MONTH, LocalDate.of(2026, 7, 10)))
    }

    @Test
    fun loadsCurrentStorePreservingDatedUndatedPeriodsGenerationsTombstonesAndUnresolvedNodes() = runTest {
        val file = File.createTempFile("current_listen_stats_", ".json").apply { deleteOnExit() }
        val persisted = ListenStatsStore(
            schemaVersion = ListenStatsStore.SCHEMA_VERSION,
            devices = listOf(
                ListenStatsDevice(
                    deviceId = "device-a",
                    displayName = "Pixel Test",
                    platform = "android",
                    currentGeneration = 3L,
                    createdAt = 100L,
                    lastSeenAt = 200L,
                    updatedAtUtcMs = 200L
                )
            ),
            currentDeviceId = "device-a",
            currentGeneration = 3L,
            songs = listOf(
                ListenStatsSongNode(
                    identityKey = "test.mp3|1000",
                    normalizedFileName = "test.mp3",
                    fileName = "test.mp3",
                    durationMs = 1_000L,
                    contributions = listOf(
                        ListenStatsContribution(
                            deviceId = "device-a",
                            generation = 3L,
                            dailyListenMs = mapOf("2026-07-10" to 2_000L),
                            undatedListenMs = 5_000L
                        )
                    )
                )
            ),
            tombstones = listOf(
                ListenStatsTombstone("device-a", 2L, 300L, "device-a", "local_clear")
            ),
            unresolvedNodes = listOf(
                ListenStatsUnresolvedNode("missing.mp3", 2_000L, "{\"unresolved\":true}")
            )
        )
        file.writeText(Gson().toJson(persisted))

        val repository = ListenStatsRepository(
            jsonFile = file,
            currentDeviceIdProvider = { "device-a" },
            wallClockMillis = { 400L },
            zoneId = ZoneId.of("UTC")
        )

        val loaded = repository.getByIdentityKey("test.mp3|1000")!!
        val payload = repository.exportLocalPayload()
        assertEquals(7_000L, loaded.totalListenMs)
        assertEquals(7_000L, loaded.listenMsFor(ListenStatsPeriod.ALL, LocalDate.of(2026, 7, 10)))
        assertEquals(2_000L, loaded.listenMsFor(ListenStatsPeriod.DAY, LocalDate.of(2026, 7, 10)))
        assertEquals(2_000L, loaded.listenMsFor(ListenStatsPeriod.MONTH, LocalDate.of(2026, 7, 10)))
        assertEquals(3L, payload.currentGeneration)
        assertEquals(1, payload.tombstones.size)
        assertEquals(1, payload.unresolvedNodes.size)
        assertTrue(payload.devices.all { it.displayName.isNotBlank() })
        assertEquals("Pixel Test", payload.devices.single().displayName)
    }

    @Test
    fun rootArrayResetsToFreshStoreWithoutRewritingFile() = assertInvalidStoreStartsFresh("[]")

    @Test
    fun missingSchemaResetsToFreshStoreWithoutRewritingFile() =
        assertInvalidStoreStartsFresh("{\"devices\":[]}")

    @Test
    fun nullSchemaResetsToFreshStoreWithoutRewritingFile() =
        assertInvalidStoreStartsFresh("{\"schemaVersion\":null}")

    @Test
    fun mismatchedSchemaResetsToFreshStoreWithoutRewritingFile() =
        assertInvalidStoreStartsFresh("{\"schemaVersion\":2}")

    @Test
    fun malformedJsonResetsToFreshStoreWithoutRewritingFile() =
        assertInvalidStoreStartsFresh("{broken")

    @Test
    fun structurallyInvalidObjectResetsToFreshStoreWithoutRewritingFile() =
        assertInvalidStoreStartsFresh("{\"schemaVersion\":2,\"devices\":null}")

    @Test
    fun successfulWriteReplacesInvalidFileWithCurrentObjectSchema() = runTest {
        val file = File.createTempFile("invalid_listen_stats_", ".json").apply {
            deleteOnExit()
            writeText("[]")
        }
        val repository = ListenStatsRepository(file)

        repository.recordListenDeltaNow(stat(), 100L)

        val root = JsonParser.parseString(file.readText()).asJsonObject
        assertEquals(ListenStatsStore.SCHEMA_VERSION, root.get("schemaVersion").asInt)
        assertTrue(root.has("devices"))
        assertTrue(root.has("songs"))
    }

    @Test
    fun schemaThreeStorePersistsAcrossRepositoryReload() = runTest {
        val file = File.createTempFile("schema_three_listen_stats_", ".json").apply {
            deleteOnExit()
        }
        val first = ListenStatsRepository(file, zoneId = ZoneId.of("UTC"))
        first.recordListenDeltaNow(stat(), 123L)

        assertEquals(3, JsonParser.parseString(file.readText()).asJsonObject
            .get("schemaVersion").asInt)
        assertEquals(123L, ListenStatsRepository(file, zoneId = ZoneId.of("UTC"))
            .getByIdentityKey("test.mp3|1000")!!.totalListenMs)
    }

    @Test
    fun recordListenDeltaNowSplitsAcrossLocalMidnight() = runTest {
        val zone = ZoneId.of("America/New_York")
        val end = ZonedDateTime.of(2026, 7, 11, 0, 0, 30, 0, zone)
        val file = File.createTempFile("listen_stats_", ".json").apply { deleteOnExit() }
        val repository = ListenStatsRepository(
            jsonFile = file,
            wallClockMillis = { end.toInstant().toEpochMilli() },
            zoneId = zone
        )

        repository.recordListenDeltaNow(stat(), 60_000L)

        val saved = repository.getByIdentityKey("test.mp3|1000")!!
        assertEquals(60_000L, saved.totalListenMs)
        assertEquals(30_000L, saved.dailyListenMs["2026-07-10"])
        assertEquals(30_000L, saved.dailyListenMs["2026-07-11"])
    }

    @Test
    fun recordListenDeltaNowSplitsAcrossSpringForwardDay() = runTest {
        val zone = ZoneId.of("America/New_York")
        val end = ZonedDateTime.of(2026, 3, 9, 0, 0, 0, 0, zone)
        val file = File.createTempFile("listen_stats_", ".json").apply { deleteOnExit() }
        val repository = ListenStatsRepository(
            jsonFile = file,
            wallClockMillis = { end.toInstant().toEpochMilli() },
            zoneId = zone
        )

        repository.recordListenDeltaNow(stat(), 24 * 60 * 60 * 1_000L)

        val saved = repository.getByIdentityKey("test.mp3|1000")!!
        assertEquals(24 * 60 * 60 * 1_000L, saved.totalListenMs)
        assertEquals(60 * 60 * 1_000L, saved.dailyListenMs["2026-03-07"])
        assertEquals(23 * 60 * 60 * 1_000L, saved.dailyListenMs["2026-03-08"])
    }

    @Test
    fun recordListenDeltaNowUsesZoneProviderForEachRecord() = runTest {
        val instant = ZonedDateTime.of(2026, 7, 10, 2, 0, 0, 0, ZoneId.of("UTC")).toInstant()
        var currentZone = ZoneId.of("UTC")
        val file = File.createTempFile("listen_stats_", ".json").apply { deleteOnExit() }
        val repository = ListenStatsRepository(
            jsonFile = file,
            wallClockMillis = { instant.toEpochMilli() },
            zoneIdProvider = { currentZone }
        )

        repository.recordListenDeltaNow(stat(), 1L)
        currentZone = ZoneId.of("America/Los_Angeles")
        repository.recordListenDeltaNow(stat(), 1L)

        val saved = repository.getByIdentityKey("test.mp3|1000")!!
        assertEquals(1L, saved.dailyListenMs["2026-07-10"])
        assertEquals(1L, saved.dailyListenMs["2026-07-09"])
    }

    @Test
    fun clearAllPersistsEmptyStateBeforeEmittingClearEvent() = runTest {
        val file = File.createTempFile("listen_stats_", ".json").apply { deleteOnExit() }
        val repository = ListenStatsRepository(file)
        repository.recordListenDeltaNow(stat(), 1_000L)
        val clearEvent = async { repository.clearEvents.first() }
        runCurrent()

        repository.clearAll()

        clearEvent.await()
        assertEquals(emptyList<TrackStat>(), repository.allStats.value)
        val store = Gson().fromJson(file.readText(), ListenStatsStore::class.java)
        assertEquals(1, store.tombstones.size)
        assertEquals(1L, store.currentGeneration)
    }

    @Test
    fun clearCurrentDeviceStatsTombstonesGenerationAndRotatesSource() = runTest {
        val repository = repositoryWithDevice("device-a", now = 1_000L)
        repository.recordListenDeltaNow(stat(), 100L)

        repository.clearCurrentDeviceStats("user_requested")

        val source = repository.currentSource()
        val payload = repository.exportLocalPayload()
        assertEquals("device-a", source.device.deviceId)
        assertEquals(1L, source.currentGeneration)
        assertEquals(1, payload.tombstones.size)
        assertEquals(0L, payload.tombstones.single().generation)
        assertEquals("device-a", payload.tombstones.single().operatorDeviceId)
        assertEquals("user_requested", payload.tombstones.single().reason)
        assertTrue(repository.allStats.value.isEmpty())
    }

    @Test
    fun staleFenceAfterRotationCannotRepopulateTombstonedGeneration() = runTest {
        val repository = repositoryWithDevice("device-a", now = 1_000L)
        val fence = repository.captureWriteFence()

        repository.clearCurrentDeviceStats()
        val accepted = repository.recordListenDeltaNow(stat(), 100L, fence)

        assertFalse(accepted)
        assertTrue(repository.allStats.value.isEmpty())
        assertEquals(1L, repository.currentSource().currentGeneration)
    }

    @Test
    fun remoteTombstoneOfCurrentGenerationRotatesSourceAndInvalidatesStaleFence() = runTest {
        val repository = repositoryWithDevice("device-a", now = 1_000L)
        repository.recordListenDeltaNow(stat(), 100L)
        val staleFence = repository.captureWriteFence()
        val payload = repository.exportLocalPayload()

        repository.applyLocalPayload(payload.copy(
            tombstones = payload.tombstones + ListenStatsTombstone(
                deviceId = "device-a",
                generation = 0L,
                tombstonedAtUtcMs = 2_000L,
                operatorDeviceId = "remote-device",
                reason = "remote_clear"
            )
        ))

        assertEquals(1L, repository.currentSource().currentGeneration)
        assertTrue(repository.allStats.value.isEmpty())
        assertFalse(repository.recordListenDeltaNow(stat(), 100L, staleFence))
    }

    @Test
    fun materialStatsMutationsNotifySyncFence() = runTest {
        var mutations = 0
        val file = File.createTempFile("listen_stats_", ".json").apply { deleteOnExit() }
        val repository = ListenStatsRepository(
            jsonFile = file,
            onMaterialMutation = { mutations++ },
            zoneId = ZoneId.of("UTC")
        )

        repository.recordListenDeltaNow(stat(), 100L)
        repository.clearAll()

        assertEquals(2, mutations)
    }

    @Test
    fun exportedPayloadIncludesContributionTimestampsAndSourceMetadata() = runTest {
        val repository = repositoryWithDevice("device-a", now = 12_345L)
        repository.recordListenDeltaNow(stat(), 100L)

        val payload = repository.exportLocalPayload()
        val device = payload.devices.single()
        val contribution = payload.songs.single().contributions.single()
        assertEquals("device-a", device.deviceId)
        assertEquals("android", device.platform)
        assertEquals(12_345L, device.updatedAtUtcMs)
        assertEquals(12_345L, contribution.firstPlayedAtUtcMs)
        assertEquals(12_345L, contribution.lastPlayedAtUtcMs)
        assertEquals(12_345L, contribution.updatedAtUtcMs)
    }

    @Test
    fun applyStalePayloadPreservesLaterLocalDeltaAndMergesRemoteContribution() = runTest {
        val repository = repositoryWithDevice("phone", now = 1_000L)
        repository.recordListenDeltaNow(stat(), 1_000L)
        val stalePayload = repository.exportLocalPayload()

        repository.recordListenDeltaNow(stat(), 500L)
        val remoteNode = songNode(
            fileName = "test.mp3",
            durationMs = 1_000L,
            deviceId = "desktop",
            daily = mapOf("2026-07-10" to 2_000L)
        )

        repository.applyLocalPayload(stalePayload.copy(
            songs = stalePayload.songs + remoteNode,
            tombstones = stalePayload.tombstones + ListenStatsTombstone("retired", 0L),
            unresolvedNodes = stalePayload.unresolvedNodes + ListenStatsUnresolvedNode(
                "missing.mp3", 2_000L, "{\"unresolved\":true}"
            )
        ))

        val node = repository.exportLocalPayload().songs.single { it.identityKey == "test.mp3|1000" }
        val contributions = node.contributions.associateBy { it.deviceId to it.generation }
        assertEquals(1_500L, contributions["phone" to 0L]!!.dailyListenMs["1970-01-01"])
        assertEquals(2_000L, contributions["desktop" to 0L]!!.dailyListenMs["2026-07-10"])
        assertEquals(3_500L, repository.getByIdentityKey("test.mp3|1000")!!.totalListenMs)
        assertTrue(repository.exportLocalPayload().tombstones.any { it.deviceId == "retired" })
        assertTrue(repository.exportLocalPayload().unresolvedNodes.any { it.normalizedFileName == "missing.mp3" })
    }

    @Test
    fun boundNodesForSameSongAggregateDatedAndUndatedTotals() = runTest {
        val repository = repositoryWithStore(
            songs = listOf(
                songNode("exact.mp3", 239_986L, boundSongId = 42L,
                    daily = mapOf("2026-07-10" to 100L), undated = 200L),
                songNode("exact.mp3", 239_987L, boundSongId = 42L,
                    daily = mapOf("2026-07-10" to 300L), undated = 400L)
            )
        )

        val stats = repository.allStats.value
        assertEquals(1, stats.size)
        assertEquals(42L, stats.single().songId)
        assertEquals(1_000L, stats.single().totalListenMs)
        assertEquals(400L, stats.single().dailyListenMs["2026-07-10"])
        assertEquals(TrackStat.boundPresentationIdentityKey(42L), stats.single().identityKey)
        assertEquals(stats.single(), repository.getByIdentityKey("exact.mp3|239986"))
    }

    @Test
    fun distinctWireIdentitiesSumSameDeviceAndGenerationInsteadOfTakingMax() = runTest {
        val repository = repositoryWithStore(
            songs = listOf(
                songNode("first.mp3", 1_000L, boundSongId = 7L, daily = mapOf("2026-07-10" to 10L)),
                songNode("second.mp3", 1_000L, boundSongId = 7L, daily = mapOf("2026-07-10" to 20L))
            )
        )

        assertEquals(30L, repository.allStats.value.single().dailyListenMs["2026-07-10"])
    }

    @Test
    fun tombstonesAreAppliedBeforeBoundAggregation() = runTest {
        val repository = repositoryWithStore(
            songs = listOf(
                songNode("active.mp3", 1_000L, boundSongId = 7L, daily = mapOf("2026-07-10" to 30L)),
                songNode("cleared.mp3", 1_001L, boundSongId = 7L, deviceId = "cleared", daily = mapOf("2026-07-10" to 90L))
            ),
            tombstones = listOf(ListenStatsTombstone("cleared", 0L))
        )

        assertEquals(30L, repository.allStats.value.single().totalListenMs)
    }

    @Test
    fun boundAggregationSaturatesDailyAndUndatedTotals() = runTest {
        val repository = repositoryWithStore(
            songs = listOf(
                songNode("first.mp3", 1_000L, boundSongId = 7L,
                    daily = mapOf("2026-07-10" to Long.MAX_VALUE), undated = Long.MAX_VALUE),
                songNode("second.mp3", 1_001L, boundSongId = 7L,
                    daily = mapOf("2026-07-10" to 1L), undated = 1L)
            )
        )

        val stat = repository.allStats.value.single()
        assertEquals(Long.MAX_VALUE, stat.dailyListenMs["2026-07-10"])
        assertEquals(Long.MAX_VALUE, stat.totalListenMs)
    }

    @Test
    fun unboundRowsRemainSeparateByExactWireIdentityAndExportUnchanged() = runTest {
        val songs = listOf(
            songNode("exact.mp3", 239_986L, daily = mapOf("2026-07-10" to 3_000L)),
            songNode("exact.mp3", 239_987L, daily = mapOf("2026-07-10" to 5_000L))
        )
        val repository = repositoryWithStore(songs = songs)

        assertEquals(
            listOf("exact.mp3|239986", "exact.mp3|239987"),
            repository.allStats.value.map { it.identityKey }
        )
        assertEquals(songs, repository.exportLocalPayload().songs)
    }

    @Test
    fun newStoreUsesProvidedCurrentDeviceDisplayName() = runTest {
        val file = File.createTempFile("listen_stats_", ".json").apply { deleteOnExit() }
        val repository = ListenStatsRepository(
            jsonFile = file,
            currentDeviceIdProvider = { "android-1" },
            currentDeviceDisplayNameProvider = { "Pixel Test" },
            wallClockMillis = { 100L }
        )

        assertEquals("Pixel Test", repository.currentSource().device.displayName)
    }

    @Test
    fun normalizedStoreBackfillsBlankDeviceNamesWithoutOverwritingExistingNames() = runTest {
        val file = File.createTempFile("listen_stats_", ".json").apply {
            deleteOnExit()
            writeText(Gson().toJson(ListenStatsStore(
                devices = listOf(
                    ListenStatsDevice("current", displayName = "", platform = "android"),
                    ListenStatsDevice("remote", displayName = "", platform = "windows"),
                    ListenStatsDevice("named", displayName = "Desktop", platform = "windows")
                ),
                currentDeviceId = "current"
            )))
        }

        val repository = ListenStatsRepository(
            jsonFile = file,
            currentDeviceIdProvider = { "current" },
            currentDeviceDisplayNameProvider = { "Pixel Test" },
            wallClockMillis = { 100L }
        )

        val devices = repository.exportLocalPayload().devices.associateBy { it.deviceId }
        assertEquals("Pixel Test", devices.getValue("current").displayName)
        assertEquals("Device remote", devices.getValue("remote").displayName)
        assertEquals("Desktop", devices.getValue("named").displayName)
        val persisted = Gson().fromJson(file.readText(), ListenStatsStore::class.java)
        assertEquals("Pixel Test", persisted.devices.first { it.deviceId == "current" }.displayName)
        assertEquals("Device remote", persisted.devices.first { it.deviceId == "remote" }.displayName)
    }

    private fun assertInvalidStoreStartsFresh(raw: String) {
        val file = File.createTempFile("invalid_listen_stats_", ".json").apply {
            deleteOnExit()
            writeText(raw)
        }
        val repository = ListenStatsRepository(file)

        assertTrue(repository.allStats.value.isEmpty())
        assertEquals(raw, file.readText())
    }

    private fun repositoryAt(date: LocalDate): ListenStatsRepository {
        val file = File.createTempFile("listen_stats_", ".json").apply { deleteOnExit() }
        val zone = ZoneId.of("UTC")
        return ListenStatsRepository(
            jsonFile = file,
            wallClockMillis = { date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli() },
            zoneId = zone
        )
    }

    private fun repositoryWithDevice(deviceId: String, now: Long): ListenStatsRepository {
        val file = File.createTempFile("listen_stats_", ".json").apply { deleteOnExit() }
        return ListenStatsRepository(
            jsonFile = file,
            currentDeviceIdProvider = { deviceId },
            wallClockMillis = { now },
            zoneId = ZoneId.of("UTC")
        )
    }

    private fun repositoryWithStore(
        songs: List<ListenStatsSongNode>,
        tombstones: List<ListenStatsTombstone> = emptyList()
    ): ListenStatsRepository {
        val file = File.createTempFile("listen_stats_aggregation_", ".json").apply { deleteOnExit() }
        val store = ListenStatsStore(
            devices = listOf(ListenStatsDevice(deviceId = "device-a")),
            currentDeviceId = "device-a",
            songs = songs,
            tombstones = tombstones
        )
        file.writeText(Gson().toJson(store))
        return ListenStatsRepository(
            jsonFile = file,
            currentDeviceIdProvider = { "device-a" },
            zoneId = ZoneId.of("UTC")
        )
    }

    private fun songNode(
        fileName: String,
        durationMs: Long,
        boundSongId: Long = 0L,
        deviceId: String = "device-a",
        daily: Map<String, Long> = emptyMap(),
        undated: Long = 0L
    ) = ListenStatsSongNode(
        identityKey = "$fileName|$durationMs",
        normalizedFileName = fileName,
        fileName = fileName,
        boundSongId = boundSongId,
        durationMs = durationMs,
        contributions = listOf(
            ListenStatsContribution(
                deviceId = deviceId,
                generation = 0L,
                dailyListenMs = daily,
                undatedListenMs = undated
            )
        )
    )

    private fun stat(
        totalListenMs: Long = 0L,
        dailyListenMs: Map<String, Long> = emptyMap()
    ) = TrackStat(
        displayName = "Test song",
        fileName = "test.mp3",
        durationMs = 1_000L,
        totalListenMs = totalListenMs,
        dailyListenMs = dailyListenMs,
        identityKey = "test.mp3|1000"
    )
}
