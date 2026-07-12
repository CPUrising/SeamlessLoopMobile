package com.cpu.seamlessloopmobile.data.sync

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SyncMergeEngine 单元测试。
 * 验证双向合并的正确性：按歌单 ID 和歌曲身份标识合并，
 * 保护本地元数据，以及报告冲突。
 */
class SyncMergeEngineTest {

    private val engine = SyncMergeEngine()

    @Test
    fun `merge playback statistics uses cumulative maxima and deterministic ordering`() {
        val songA = SyncSongIdentity("a.mp3", 1L)
        val songB = SyncSongIdentity("B.mp3", 2L)
        val remote = statsSnapshot(
            songs = listOf(
                SyncPlaybackStatsSong(songB, listOf(contribution("device-b", 1L, 4L))),
                SyncPlaybackStatsSong(songA, listOf(contribution("device-a", 0L, 2L)))
            ),
            devices = listOf(device("device-b", "Old", 1L, 4L), device("device-a", "A", 2L, 2L))
        )
        val local = statsSnapshot(
            songs = listOf(SyncPlaybackStatsSong(songA, listOf(contribution("device-a", 0L, 5L)))),
            devices = listOf(device("device-b", "New", 2L, 5L))
        )

        val stats = runBlockingTest { engine.merge(remote, local).snapshot }.playbackStats!!

        assertEquals(listOf("a.mp3", "b.mp3"), stats.songs.map { it.song.normalizedFileName })
        assertEquals(5L, stats.songs.first().contributions.single().datedListenMs["2026-01-01"])
        assertEquals(listOf("device-a", "device-b"), stats.devices.map { it.deviceId })
        assertEquals("New", stats.devices.last().displayName)
    }

    @Test
    fun `merge playback tombstone permanently suppresses contribution regardless of side`() {
        val song = SyncSongIdentity("a.mp3", 1L)
        val remote = statsSnapshot(
            songs = listOf(SyncPlaybackStatsSong(song, listOf(contribution("device", 3L, 10L)))),
            tombstones = listOf(SyncPlaybackStatsTombstone("device", 3L, 10L))
        )
        val local = statsSnapshot(
            songs = listOf(SyncPlaybackStatsSong(song, listOf(contribution("device", 3L, 20L))))
        )

        val stats = runBlockingTest { engine.merge(remote, local).snapshot }.playbackStats!!

        assertTrue(stats.songs.single().contributions.isEmpty())
        assertEquals(listOf("device"), stats.tombstones.map { it.deviceId })
    }

    @Test
    fun `fixture tombstone suppresses stale contribution in both merge directions`() {
        val serializer = SyncSnapshotSerializer()
        val androidGolden = deserializeFixture(serializer, "sync/playback_stats_v2_android_golden.json")
        val staleCollision = deserializeFixture(serializer, "sync/playback_stats_v2_tombstone_collision.json")

        listOf(
            runBlockingTest { engine.merge(androidGolden, staleCollision).snapshot },
            runBlockingTest { engine.merge(staleCollision, androidGolden).snapshot }
        ).forEach { merged ->
            val stats = merged.playbackStats!!
            val cafe = stats.songs.single { it.song.normalizedFileName == "café.mp3" }

            assertTrue(cafe.contributions.none {
                it.deviceId == "android-pixel-8" && it.generation == 1L
            })
            assertTrue(stats.tombstones.any {
                it.deviceId == "android-pixel-8" && it.generation == 1L
            })
            assertTrue(stats.songs.any { it.song.normalizedFileName == "unresolved mix.flac" })

            val serialized = serializer.serialize(merged)
            val root = JsonParser.parseString(serialized).asJsonObject
            assertEquals(SYNC_SCHEMA_VERSION_V2, root.get("schemaVersion").asInt)
            assertTrue(root.has("playbackStatistics"))
            assertTrue(!root.has("playbackStats"))
        }
    }

    @Test
    fun `wpf and android canonical playback statistics merge both directions`() {
        val serializer = SyncSnapshotSerializer()
        val wpf = deserializeFixture(serializer, "sync/playback_stats_v2_wpf_canonical.json")
        val android = deserializeFixture(serializer, "sync/playback_stats_v2_android_golden.json")

        listOf(
            runBlockingTest { engine.merge(wpf, android).snapshot },
            runBlockingTest { engine.merge(android, wpf).snapshot }
        ).forEach { merged ->
            assertEquals(wpf.playbackStats, merged.playbackStats)
            assertTrue(merged.playbackStats!!.songs.any {
                it.song.normalizedFileName == "unresolved mix.flac"
            })
            assertTrue(merged.playbackStats.tombstones.any {
                it.deviceId == "android-pixel-8" && it.generation == 1L
            })
            assertTrue(merged.playbackStats.songs.single {
                it.song.normalizedFileName == "café.mp3"
            }.contributions.none {
                it.deviceId == "android-pixel-8" && it.generation == 1L
            })
        }
    }

    @Test
    fun `merge playback contribution first played uses earliest non-zero timestamp`() {
        val song = SyncSongIdentity("first-played.mp3", 1L)
        fun mergedFirstPlayedAt(remoteFirstPlayedAt: Long, localFirstPlayedAt: Long): Long {
            val remote = statsSnapshot(
                songs = listOf(
                    SyncPlaybackStatsSong(
                        song,
                        listOf(contribution("device", 1L, 1L).copy(firstPlayedAtUtcMs = remoteFirstPlayedAt))
                    )
                )
            )
            val local = statsSnapshot(
                songs = listOf(
                    SyncPlaybackStatsSong(
                        song,
                        listOf(contribution("device", 1L, 2L).copy(firstPlayedAtUtcMs = localFirstPlayedAt))
                    )
                )
            )
            return runBlockingTest { engine.merge(remote, local).snapshot }
                .playbackStats!!.songs.single().contributions.single().firstPlayedAtUtcMs
        }

        assertEquals(500L, mergedFirstPlayedAt(0L, 500L))
        assertEquals(300L, mergedFirstPlayedAt(700L, 300L))
    }

    @Test
    fun `playback identity metadata reducer is commutative and contributions use maxima`() {
        val firstIdentity = SyncSongIdentity(
            fileName = " Track.MP3",
            durationMs = 239_987L,
            totalSamples = 10_583_412L,
            contentHash = "hash-a"
        )
        val secondIdentity = SyncSongIdentity(
            fileName = "track.mp3",
            durationMs = 239_987L,
            totalSamples = 10_583_426L,
            contentHash = "hash-z"
        )
        val first = statsSnapshot(
            songs = listOf(
                SyncPlaybackStatsSong(firstIdentity, listOf(contribution("device", 1L, 4L)))
            )
        )
        val second = statsSnapshot(
            songs = listOf(
                SyncPlaybackStatsSong(secondIdentity, listOf(contribution("device", 1L, 7L)))
            )
        )

        val left = runBlockingTest { engine.merge(first, second).snapshot }.playbackStats!!
        val right = runBlockingTest { engine.merge(second, first).snapshot }.playbackStats!!

        assertEquals(left, right)
        assertEquals(" Track.MP3", left.songs.single().song.fileName)
        assertEquals(10_583_426L, left.songs.single().song.totalSamples)
        assertEquals("hash-z", left.songs.single().song.contentHash)
        assertEquals(7L, left.songs.single().contributions.single().datedListenMs["2026-01-01"])
    }

    private fun statsSnapshot(
        songs: List<SyncPlaybackStatsSong> = emptyList(),
        devices: List<SyncPlaybackStatsDevice> = emptyList(),
        tombstones: List<SyncPlaybackStatsTombstone> = emptyList()
    ) = SyncSnapshot(
        schemaVersion = SYNC_SCHEMA_VERSION_V2,
        deviceId = "device",
        exportedAt = 1L,
        playbackStats = SyncPlaybackStats(devices = devices, songs = songs, tombstones = tombstones)
    )

    private fun device(id: String, name: String, displayUpdatedAt: Long, lastSeenAt: Long) =
        SyncPlaybackStatsDevice(id, name, 1L, lastSeenAt, 0L, "android", displayUpdatedAt)

    private fun contribution(deviceId: String, generation: Long, value: Long) =
        SyncPlaybackStatsContribution(
            deviceId = deviceId,
            generation = generation,
            datedListenMs = mapOf("2026-01-01" to value),
            firstPlayedAtUtcMs = 1L,
            lastPlayedAtUtcMs = value,
            updatedAtUtcMs = value
        )

    private fun deserializeFixture(serializer: SyncSnapshotSerializer, path: String): SyncSnapshot =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(path))
            .bufferedReader().use { serializer.deserialize(it.readText()) }

    // -------------------------------------------------------------------
    // Null remote (initial sync)
    // -------------------------------------------------------------------

    @Test
    fun `merge with null remote returns local snapshot`() {
        val local = SyncSnapshot(
            deviceId = "local-device",
            exportedAt = 1000L,
            playlists = listOf(
                SyncPlaylist("p1", "Local P1", 100L, 200L)
            )
        )

        val result = runBlockingTest { engine.merge(null, local) }

        assertEquals(local, result.snapshot)
        assertEquals(1, result.report.playlistsUploaded)
        assertEquals(0, result.report.playlistsDownloaded)
    }

    @Test
    fun `merge with null remote and empty local returns empty`() {
        val local = SyncSnapshot(deviceId = "d", exportedAt = 100L)

        val result = runBlockingTest { engine.merge(null, local) }

        assertEquals(local, result.snapshot)
        assertEquals(0, result.report.playlistsUploaded)
        assertTrue(result.report.conflicts.isEmpty())
    }

    // -------------------------------------------------------------------
    // Playlist merge by id
    // -------------------------------------------------------------------

    @Test
    fun `merge combines playlists from both sides`() {
        val local = SyncSnapshot(
            deviceId = "local",
            exportedAt = 100L,
            playlists = listOf(
                SyncPlaylist("p1", "Local Only", 100L, 200L)
            )
        )
        val remote = SyncSnapshot(
            deviceId = "remote",
            exportedAt = 200L,
            playlists = listOf(
                SyncPlaylist("p2", "Remote Only", 300L, 400L)
            )
        )

        val result = runBlockingTest { engine.merge(remote, local) }

        assertEquals(2, result.snapshot.playlists.size)
        val names = result.snapshot.playlists.map { it.name }.sorted()
        assertEquals(listOf("Local Only", "Remote Only"), names)
    }

    @Test
    fun `merge same playlist id picks newer`() {
        val local = SyncSnapshot(
            deviceId = "local",
            exportedAt = 100L,
            playlists = listOf(
                SyncPlaylist("p1", "Old Name", 100L, 200L)
            )
        )
        val remote = SyncSnapshot(
            deviceId = "remote",
            exportedAt = 200L,
            playlists = listOf(
                SyncPlaylist("p1", "New Name", 100L, 400L)
            )
        )

        val result = runBlockingTest { engine.merge(remote, local) }

        assertEquals(1, result.snapshot.playlists.size)
        assertEquals("New Name", result.snapshot.playlists[0].name)
    }

    @Test
    fun `merge playlist with same id but newer local keeps local name`() {
        val local = SyncSnapshot(
            deviceId = "local",
            exportedAt = 100L,
            playlists = listOf(
                SyncPlaylist("p1", "Local Name", 100L, 500L)
            )
        )
        val remote = SyncSnapshot(
            deviceId = "remote",
            exportedAt = 200L,
            playlists = listOf(
                SyncPlaylist("p1", "Remote Name", 100L, 300L)
            )
        )

        val result = runBlockingTest { engine.merge(remote, local) }

        assertEquals("Local Name", result.snapshot.playlists[0].name)
    }

    // -------------------------------------------------------------------
    // Merge by song identity
    // -------------------------------------------------------------------

    @Test
    fun `merge loop points by song identity`() {
        val song = SyncSongIdentity("track.mp3", 30000L)
        val local = SyncSnapshot(
            deviceId = "local",
            exportedAt = 100L,
            loopPoints = listOf(
                SyncLoopPointEntry(song, SyncLoopPoint(100L, 200L, 1000L))
            )
        )
        val remote = SyncSnapshot(
            deviceId = "remote",
            exportedAt = 200L,
            loopPoints = listOf(
                SyncLoopPointEntry(song, SyncLoopPoint(150L, 250L, 2000L))
            )
        )

        // remote loop point is newer → merged should use remote
        val result = runBlockingTest { engine.merge(remote, local) }

        assertEquals(1, result.snapshot.loopPoints.size)
        val mergedEntry = result.snapshot.loopPoints[0]
        assertEquals(song, mergedEntry.song)
        assertEquals(150L, mergedEntry.loopPoint.loopStart)
        assertEquals(250L, mergedEntry.loopPoint.loopEnd)
    }

    @Test
    fun `merge ratings by song identity`() {
        val song = SyncSongIdentity("track.mp3", 30000L)
        val local = SyncSnapshot(
            deviceId = "local",
            exportedAt = 100L,
            ratings = listOf(
                SyncRatingEntry(song, SyncRating(5, 1000L))
            )
        )
        val remote = SyncSnapshot(
            deviceId = "remote",
            exportedAt = 200L,
            ratings = listOf(
                SyncRatingEntry(song, SyncRating(3, 2000L))
            )
        )

        // remote rating is newer → merged should use remote
        val result = runBlockingTest { engine.merge(remote, local) }

        assertEquals(1, result.snapshot.ratings.size)
        assertEquals(3, result.snapshot.ratings[0].rating.rating)
    }

    @Test
    fun `merge ratings with same file and duration ignores totalSamples mismatch and keeps remote identity`() {
        val remoteSong = SyncSongIdentity("1-02. Summer Pockets.flac", 239_987L, 10_583_412L)
        val localSong = SyncSongIdentity("1-02. Summer Pockets.flac", 239_987L, 10_583_426L)
        val local = SyncSnapshot(
            deviceId = "local",
            exportedAt = 100L,
            ratings = listOf(
                SyncRatingEntry(localSong, SyncRating(4, 1_000L))
            )
        )
        val remote = SyncSnapshot(
            deviceId = "remote",
            exportedAt = 200L,
            ratings = listOf(
                SyncRatingEntry(remoteSong, SyncRating(3, 2_000L))
            )
        )

        val result = runBlockingTest { engine.merge(remote, local) }

        assertEquals(1, result.snapshot.ratings.size)
        assertEquals(remoteSong, result.snapshot.ratings.single().song)
        assertEquals(3, result.snapshot.ratings.single().rating.rating)
    }

    @Test
    fun `merge loop points with same file and duration ignores totalSamples mismatch and keeps remote identity`() {
        val remoteSong = SyncSongIdentity("loop.flac", 120_000L, 5_292_000L)
        val localSong = SyncSongIdentity("loop.flac", 120_000L, 5_292_014L)
        val local = SyncSnapshot(
            deviceId = "local",
            exportedAt = 100L,
            loopPoints = listOf(
                SyncLoopPointEntry(localSong, SyncLoopPoint(100L, 1_000L, 1_000L))
            )
        )
        val remote = SyncSnapshot(
            deviceId = "remote",
            exportedAt = 200L,
            loopPoints = listOf(
                SyncLoopPointEntry(remoteSong, SyncLoopPoint(200L, 2_000L, 2_000L))
            )
        )

        val result = runBlockingTest { engine.merge(remote, local) }

        assertEquals(1, result.snapshot.loopPoints.size)
        assertEquals(remoteSong, result.snapshot.loopPoints.single().song)
        assertEquals(200L, result.snapshot.loopPoints.single().loopPoint.loopStart)
    }

    @Test
    fun `merge playlist items with same file and duration ignores totalSamples mismatch and keeps remote identity`() {
        val remoteSong = SyncSongIdentity("track.flac", 180_000L, 7_938_000L)
        val localSong = SyncSongIdentity("track.flac", 180_000L, 7_938_014L)
        val local = SyncSnapshot(
            deviceId = "local",
            exportedAt = 100L,
            playlists = listOf(
                SyncPlaylist(
                    id = "p1",
                    name = "Playlist",
                    createdAt = 100L,
                    modifiedAt = 3_000L,
                    items = listOf(SyncPlaylistItem(localSong, sortOrder = 0))
                )
            )
        )
        val remote = SyncSnapshot(
            deviceId = "remote",
            exportedAt = 200L,
            playlists = listOf(
                SyncPlaylist(
                    id = "p1",
                    name = "Playlist",
                    createdAt = 100L,
                    modifiedAt = 2_000L,
                    items = listOf(SyncPlaylistItem(remoteSong, sortOrder = 0))
                )
            )
        )

        val result = runBlockingTest { engine.merge(remote, local) }

        assertEquals(1, result.snapshot.playlists.single().items.size)
        assertEquals(remoteSong, result.snapshot.playlists.single().items.single().song)
    }

    @Test
    fun `merge loop points from different songs`() {
        val songA = SyncSongIdentity("a.mp3", 10000L)
        val songB = SyncSongIdentity("b.mp3", 20000L)
        val local = SyncSnapshot(
            deviceId = "local",
            exportedAt = 100L,
            loopPoints = listOf(
                SyncLoopPointEntry(songA, SyncLoopPoint(100L, 200L, 1000L))
            )
        )
        val remote = SyncSnapshot(
            deviceId = "remote",
            exportedAt = 200L,
            loopPoints = listOf(
                SyncLoopPointEntry(songB, SyncLoopPoint(300L, 400L, 2000L))
            )
        )

        val result = runBlockingTest { engine.merge(remote, local) }

        assertEquals(2, result.snapshot.loopPoints.size)
    }

    // -------------------------------------------------------------------
    // Preserve local metadata
    // -------------------------------------------------------------------

    @Test
    fun `merge preserves local deviceId and exportedAt`() {
        val local = SyncSnapshot(
            deviceId = "my-device",
            exportedAt = 5000L,
            playlists = listOf(
                SyncPlaylist("p1", "My List", 100L, 200L)
            )
        )
        val remote = SyncSnapshot(
            deviceId = "other-device",
            exportedAt = 3000L,
            playlists = listOf(
                SyncPlaylist("p1", "Remote List", 100L, 400L)
            )
        )

        val result = runBlockingTest { engine.merge(remote, local) }

        // Local metadata should be preserved
        assertEquals("my-device", result.snapshot.deviceId)
        assertEquals(5000L, result.snapshot.exportedAt)
    }

    @Test
    fun `merge always emits schema two`() {
        val local = SyncSnapshot(
            deviceId = "local",
            exportedAt = 100L,
            playlists = listOf(SyncPlaylist("p1", "Test", 100L, 200L))
        )
        val remote = SyncSnapshot(
            deviceId = "remote",
            exportedAt = 200L,
            playlists = listOf(SyncPlaylist("p1", "Updated", 100L, 400L))
        )

        val result = runBlockingTest { engine.merge(remote, local) }

        assertEquals(SYNC_SCHEMA_VERSION_V2, result.snapshot.schemaVersion)
        assertEquals(SyncPlaybackStats(), result.snapshot.playbackStats)
    }

    // -------------------------------------------------------------------
    // Report counts
    // -------------------------------------------------------------------

    @Test
    fun `merge report has correct counts`() {
        val local = SyncSnapshot(
            deviceId = "local",
            exportedAt = 100L,
            playlists = listOf(
                SyncPlaylist("p1", "Local", 100L, 200L)
            ),
            loopPoints = listOf(
                SyncLoopPointEntry(
                    SyncSongIdentity("a.mp3", 10000L),
                    SyncLoopPoint(100L, 200L, 100L)
                )
            ),
            ratings = listOf(
                SyncRatingEntry(
                    SyncSongIdentity("b.mp3", 20000L),
                    SyncRating(4, 200L)
                )
            )
        )
        val remote = SyncSnapshot(
            deviceId = "remote",
            exportedAt = 200L,
            playlists = listOf(
                SyncPlaylist("p2", "Remote", 300L, 400L)
            ),
            loopPoints = listOf(
                SyncLoopPointEntry(
                    SyncSongIdentity("c.mp3", 30000L),
                    SyncLoopPoint(300L, 400L, 300L)
                )
            ),
            ratings = listOf(
                SyncRatingEntry(
                    SyncSongIdentity("d.mp3", 40000L),
                    SyncRating(3, 400L)
                )
            )
        )

        val result = runBlockingTest { engine.merge(remote, local) }

        assertEquals(1, result.report.playlistsUploaded)   // local has 1
        assertEquals(1, result.report.playlistsDownloaded) // remote has 1
        assertEquals(1, result.report.loopPointsUploaded)
        assertEquals(1, result.report.loopPointsDownloaded)
        assertEquals(1, result.report.ratingsUploaded)
        assertEquals(1, result.report.ratingsDownloaded)
    }

    // -------------------------------------------------------------------
    // Conflict detection
    // -------------------------------------------------------------------

    @Test
    fun `merge detects playlist name conflict`() {
        val local = SyncSnapshot(
            deviceId = "local",
            exportedAt = 100L,
            playlists = listOf(
                SyncPlaylist("p1", "Local Name", 100L, 200L)
            )
        )
        val remote = SyncSnapshot(
            deviceId = "remote",
            exportedAt = 200L,
            playlists = listOf(
                SyncPlaylist("p1", "Remote Name", 100L, 300L)
            )
        )

        val result = runBlockingTest { engine.merge(remote, local) }

        assertTrue(result.report.conflicts.isNotEmpty())
        assertEquals("Local Name", result.report.conflicts[0].playlistName)
        assertEquals("Remote Name", result.report.conflicts[0].remoteValue)
        assertEquals("Local Name", result.report.conflicts[0].localValue)
    }

    @Test
    fun `merge no conflict when playlist names match`() {
        val local = SyncSnapshot(
            deviceId = "local",
            exportedAt = 100L,
            playlists = listOf(
                SyncPlaylist("p1", "Same Name", 100L, 200L)
            )
        )
        val remote = SyncSnapshot(
            deviceId = "remote",
            exportedAt = 200L,
            playlists = listOf(
                SyncPlaylist("p1", "Same Name", 100L, 300L)
            )
        )

        val result = runBlockingTest { engine.merge(remote, local) }

        assertTrue(result.report.conflicts.isEmpty())
    }

    // -------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------

    /**
     * Small inline helper to run suspend code in tests without
     * requiring kotlinx-coroutines-test runTest integration.
     */
    private fun <T> runBlockingTest(block: suspend () -> T): T {
        return kotlinx.coroutines.runBlocking { block() }
    }
}
