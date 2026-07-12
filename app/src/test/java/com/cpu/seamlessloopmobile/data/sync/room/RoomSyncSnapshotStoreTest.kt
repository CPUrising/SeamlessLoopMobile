package com.cpu.seamlessloopmobile.data.sync.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cpu.seamlessloopmobile.data.sync.SyncLoopPoint
import com.cpu.seamlessloopmobile.data.sync.SyncLoopPointEntry
import com.cpu.seamlessloopmobile.data.sync.SyncPlaylist
import com.cpu.seamlessloopmobile.data.sync.SyncPlaylistItem
import com.cpu.seamlessloopmobile.data.sync.SyncRating
import com.cpu.seamlessloopmobile.data.sync.SyncRatingEntry
import com.cpu.seamlessloopmobile.data.sync.SyncSnapshot
import com.cpu.seamlessloopmobile.data.sync.SyncSnapshotSerializer
import com.cpu.seamlessloopmobile.data.sync.SyncSongIdentity
import com.cpu.seamlessloopmobile.data.sync.prepareV2Egress
import com.cpu.seamlessloopmobile.data.sync.SyncPlaybackStats
import com.cpu.seamlessloopmobile.data.sync.SyncPlaybackStatsContribution
import com.cpu.seamlessloopmobile.data.sync.SyncPlaybackStatsDevice
import com.cpu.seamlessloopmobile.data.sync.SyncPlaybackStatsSong
import com.cpu.seamlessloopmobile.data.sync.SyncPlaybackStatsTombstone
import com.cpu.seamlessloopmobile.data.stats.ListenStatsRepository
import com.cpu.seamlessloopmobile.data.stats.ListenStatsContribution
import com.cpu.seamlessloopmobile.data.stats.ListenStatsSongNode
import com.cpu.seamlessloopmobile.data.stats.ListenStatsTombstone
import com.cpu.seamlessloopmobile.data.stats.ListenStatsUnresolvedNode
import com.cpu.seamlessloopmobile.data.stats.TrackStat
import com.cpu.seamlessloopmobile.db.AppDatabase
import com.cpu.seamlessloopmobile.model.Playlist
import com.cpu.seamlessloopmobile.model.PlaylistDao
import com.cpu.seamlessloopmobile.model.Song
import com.cpu.seamlessloopmobile.model.SongDao
import com.cpu.seamlessloopmobile.model.UserRating
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomSyncSnapshotStoreTest {

    private lateinit var db: AppDatabase
    private lateinit var songDao: SongDao
    private lateinit var playlistDao: PlaylistDao
    private lateinit var mapper: FakePlaylistIdMapper
    private lateinit var listenStatsRepository: ListenStatsRepository
    private lateinit var listenStatsFile: File
    private lateinit var store: RoomSyncSnapshotStore

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        songDao = db.songDao()
        playlistDao = db.playlistDao()
        mapper = FakePlaylistIdMapper()
        listenStatsFile = File.createTempFile("room_sync_stats", ".json")
        listenStatsRepository = ListenStatsRepository(listenStatsFile)
        store = RoomSyncSnapshotStore(db, songDao, playlistDao, mapper, listenStatsRepository)
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun exportSnapshotUsesPortableSongIdentityAndPlaylistSyncId() = runBlocking {
        val loopSongId = insertSong(
            fileName = "loop.mp3",
            filePath = "/storage/device-a/loop.mp3",
            duration = 123_000L,
            totalSamples = 5_424_300L,
            loopStart = 1_000L,
            loopEnd = 120_000L,
            rating = 4
        )
        val plainSongId = insertSong(
            fileName = "plain.mp3",
            filePath = "/storage/device-a/plain.mp3",
            duration = 90_000L,
            totalSamples = 3_969_000L
        )
        val playlistId = playlistDao.insertPlaylist(
            Playlist(name = "Favorites", createdAt = 1_000L)
        ).toInt()
        playlistDao.clearAndSyncPlaylist(playlistId, listOf(loopSongId, plainSongId))

        val snapshot = store.exportSnapshot(deviceId = "device-a", now = 10_000L)

        assertEquals("device-a", snapshot.deviceId)
        assertEquals(10_000L, snapshot.exportedAt)
        assertEquals(1, snapshot.loopPoints.size)
        assertEquals(
            SyncSongIdentity("loop.mp3", 123_000L, 5_424_300L),
            snapshot.loopPoints.single().song
        )
        assertEquals(10_000L, snapshot.loopPoints.single().loopPoint.lastModified)
        assertEquals(1, snapshot.ratings.size)
        assertEquals(SyncSongIdentity("loop.mp3", 123_000L, 5_424_300L), snapshot.ratings.single().song)
        assertEquals(1, snapshot.playlists.size)
        assertEquals("playlist-sync-1", snapshot.playlists.single().id)
        assertEquals("Favorites", snapshot.playlists.single().name)
        assertEquals(
            listOf(
                SyncSongIdentity("loop.mp3", 123_000L, 5_424_300L),
                SyncSongIdentity("plain.mp3", 90_000L, 3_969_000L)
            ),
            snapshot.playlists.single().items.map { it.song }
        )
    }

    @Test
    fun applySnapshotWritesMatchedDataAndSkipsUnmatchedSongs() = runBlocking {
        val localSongId = insertSong(
            fileName = "remote.mp3",
            filePath = "/local/remote.mp3",
            duration = 100_000L,
            totalSamples = 4_410_000L
        )
        val matched = SyncSongIdentity("remote.mp3", 100_000L, 4_410_000L)
        val missing = SyncSongIdentity("missing.mp3", 80_000L, 3_528_000L)
        val snapshot = SyncSnapshot(
            deviceId = "device-b",
            exportedAt = 20_000L,
            loopPoints = listOf(
                SyncLoopPointEntry(matched, SyncLoopPoint(2_000L, 98_000L, 20_000L)),
                SyncLoopPointEntry(missing, SyncLoopPoint(1_000L, 70_000L, 20_000L))
            ),
            ratings = listOf(
                SyncRatingEntry(matched, SyncRating(5, 20_000L))
            ),
            playlists = listOf(
                SyncPlaylist(
                    id = "remote-playlist-1",
                    name = "Remote List",
                    createdAt = 12_000L,
                    modifiedAt = 20_000L,
                    items = listOf(
                        SyncPlaylistItem(matched, sortOrder = 0),
                        SyncPlaylistItem(missing, sortOrder = 1)
                    )
                )
            )
        )

        val report = store.applySnapshot(snapshot)

        assertEquals(1, report.loopPointsDownloaded)
        assertEquals(1, report.ratingsDownloaded)
        assertEquals(1, report.playlistsDownloaded)
        assertEquals(2, report.conflicts.size)
        assertEquals(1, songDao.getAllSongs().size)

        val updatedSong = songDao.getSongById(localSongId)
        assertNotNull(updatedSong)
        assertEquals(2_000L, updatedSong?.loopStart)
        assertEquals(98_000L, updatedSong?.loopEnd)
        assertEquals(5, updatedSong?.rating)

        val playlists = playlistDao.getPlaylistsWithCounts()
        assertEquals(1, playlists.size)
        assertEquals("Remote List", playlists.single().playlist.name)
        val playlistSongs = playlistDao.getSongsInPlaylist(playlists.single().playlist.id)
        assertEquals(listOf(localSongId), playlistSongs.map { it.id })
    }

    @Test
    fun applySnapshotRejectsInvalidPlaybackStatsBeforeApplyingRoomData() = runBlocking {
        val identity = SyncSongIdentity("invalid.mp3", 60_000L)
        val snapshot = SyncSnapshot(
            deviceId = "device-b",
            exportedAt = 20_000L,
            loopPoints = listOf(
                SyncLoopPointEntry(identity, SyncLoopPoint(2_000L, 58_000L, 20_000L))
            ),
            ratings = listOf(SyncRatingEntry(identity, SyncRating(4, 20_000L))),
            playbackStats = SyncPlaybackStats(
                songs = listOf(
                    SyncPlaybackStatsSong(
                        song = identity.copy(normalizedFileName = "wrong.mp3"),
                        contributions = listOf(
                            SyncPlaybackStatsContribution(
                                deviceId = "device-b",
                                generation = 0L,
                                datedListenMs = mapOf("not-a-date" to 1L)
                            )
                        )
                    )
                )
            )
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.applySnapshot(snapshot) }
        }
        assertTrue(songDao.getAllSongs().isEmpty())
        assertTrue(playlistDao.getPlaylistsWithCounts().isEmpty())
        assertTrue(listenStatsRepository.exportLocalPayload().songs.isEmpty())
    }

    @Test
    fun applySnapshotRejectsDuplicatePlaybackStatsStableKeys() = runBlocking {
        val identity = SyncSongIdentity("duplicate.mp3", 60_000L)
        val snapshot = statsSnapshot(
            SyncPlaybackStatsSong(identity),
            SyncPlaybackStatsSong(identity.copy(fileName = "DUPLICATE.mp3"))
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.applySnapshot(snapshot) }
        }
        assertTrue(listenStatsRepository.exportLocalPayload().songs.isEmpty())
    }

    @Test
    fun applySnapshotMatchesByExactDurationBeforeTotalSamplesAndToleratesSampleDifferences() = runBlocking {
        val localSongId = insertSong(
            fileName = "1-02. Summer Pockets.flac",
            filePath = "/local/1-02. Summer Pockets.flac",
            duration = 239_987L,
            totalSamples = 10_583_412L
        )
        val remoteIdentity = SyncSongIdentity(
            fileName = "1-02. Summer Pockets.flac",
            durationMs = 239_987L,
            totalSamples = 10_583_426L
        )

        val report = store.applySnapshot(
            SyncSnapshot(
                deviceId = "desktop",
                exportedAt = 20_000L,
                ratings = listOf(
                    SyncRatingEntry(remoteIdentity, SyncRating(4, 20_000L))
                ),
                loopPoints = listOf(
                    SyncLoopPointEntry(remoteIdentity, SyncLoopPoint(1_000L, 10_000L, 20_000L))
                ),
                playlists = listOf(
                    SyncPlaylist(
                        id = "desktop-playlist",
                        name = "Desktop Playlist",
                        createdAt = 10_000L,
                        modifiedAt = 20_000L,
                        items = listOf(SyncPlaylistItem(remoteIdentity, sortOrder = 0))
                    )
                )
            )
        )

        assertEquals(1, report.ratingsDownloaded)
        assertEquals(1, report.loopPointsDownloaded)
        assertEquals(1, report.playlistsDownloaded)
        assertTrue(report.conflicts.isEmpty())
        val updatedSong = songDao.getSongById(localSongId)
        assertNotNull(updatedSong)
        assertEquals(4, updatedSong?.rating)
        assertEquals(1_000L, updatedSong?.loopStart)
        assertEquals(10_000L, updatedSong?.loopEnd)
        val playlistSongs = playlistDao.getSongsInPlaylist(playlistDao.getPlaylistsWithCounts().single().playlist.id)
        assertEquals(listOf(localSongId), playlistSongs.map { it.id })
    }

    @Test
    fun applySnapshotKeepsSubstantiveLocalDataWhenRemoteIsUnsetOrOlder() = runBlocking {
        val localSongId = insertSong(
            fileName = "protected.mp3",
            filePath = "/local/protected.mp3",
            duration = 60_000L,
            totalSamples = 2_646_000L,
            loopStart = 500L,
            loopEnd = 58_000L,
            rating = 5
        )
        songDao.insertUserRating(UserRating(localSongId, rating = 5, lastModified = 30_000L))

        val identity = SyncSongIdentity("protected.mp3", 60_000L, 2_646_000L)
        val snapshot = SyncSnapshot(
            deviceId = "device-b",
            exportedAt = 20_000L,
            loopPoints = listOf(
                SyncLoopPointEntry(identity, SyncLoopPoint(0L, 0L, 20_000L))
            ),
            ratings = listOf(
                SyncRatingEntry(identity, SyncRating(1, 20_000L))
            )
        )

        val report = store.applySnapshot(snapshot)

        assertEquals(0, report.loopPointsDownloaded)
        assertEquals(0, report.ratingsDownloaded)
        val updatedSong = songDao.getSongById(localSongId)
        assertNotNull(updatedSong)
        assertEquals(500L, updatedSong?.loopStart)
        assertEquals(58_000L, updatedSong?.loopEnd)
        assertEquals(5, updatedSong?.rating)
    }

    @Test
    fun applySnapshotSkipsAmbiguousSongMatches() = runBlocking {
        val firstId = insertSong(
            fileName = "duplicate.mp3",
            filePath = "/local/a/duplicate.mp3",
            duration = 120_000L,
            totalSamples = 5_292_000L
        )
        val secondId = songDao.insertSongEntity(
            com.cpu.seamlessloopmobile.model.SongEntity(
                fileName = "duplicate.mp3",
                filePath = "/local/b/duplicate.mp3",
                duration = 120_000L,
                totalSamples = 5_292_000L
            )
        )
        val identity = SyncSongIdentity("duplicate.mp3", 120_000L, 5_292_000L)

        val report = store.applySnapshot(
            SyncSnapshot(
                deviceId = "device-b",
                exportedAt = 20_000L,
                loopPoints = listOf(
                    SyncLoopPointEntry(identity, SyncLoopPoint(1_000L, 119_000L, 20_000L))
                )
            )
        )

        assertEquals(0, report.loopPointsDownloaded)
        assertEquals(1, report.conflicts.size)
        assertEquals(0L, songDao.getSongById(firstId)?.loopStart)
        assertEquals(0L, songDao.getSongById(secondId)?.loopStart)
    }

    @Test
    fun exportSnapshotIncludesPlaybackStatisticsDeviceContributionAndTombstone() = runBlocking {
        val songId = insertSong("stats.mp3", "/local/stats.mp3", 60_000L, 2_646_000L)
        listenStatsRepository.recordListenDeltaNow(
            TrackStat(songId, "Stats", "stats.mp3", durationMs = 60_000L, filePath = "/local/stats.mp3",
                identityKey = "stats.mp3|60000"), 1_000L
        )
        val snapshot = store.exportSnapshot("device-a", 10_000L)
        val payload = snapshot.playbackStats!!
        assertEquals(1, payload.devices.size)
        assertEquals(1, payload.songs.size)
        assertEquals("stats.mp3", payload.songs.single().song.normalizedFileName)
        assertEquals(1, payload.songs.single().contributions.size)

        listenStatsRepository.clearCurrentDeviceStats()
        val clearedPayload = store.exportSnapshot("device-a", 20_000L).playbackStats!!
        assertEquals(1, clearedPayload.tombstones.size)
        assertTrue(clearedPayload.songs.single().contributions.isEmpty())
    }

    @Test
    fun applySnapshotImportsPlaybackStatisticsAndPreservesThenReassociatesUnmatchedNodes() = runBlocking {
        val matchedId = insertSong("matched.mp3", "/local/matched.mp3", 90_000L, 3_969_000L)
        val matched = SyncPlaybackStatsSong(
            SyncSongIdentity("matched.mp3", 90_000L),
            listOf(SyncPlaybackStatsContribution("desktop", 2L, mapOf("2026-07-10" to 7_000L), firstPlayedAtUtcMs = 100L, lastPlayedAtUtcMs = 700L))
        )
        val missing = SyncPlaybackStatsSong(
            SyncSongIdentity("later.mp3", 80_000L),
            listOf(SyncPlaybackStatsContribution("desktop", 2L, mapOf("2026-07-10" to 3_000L)))
        )
        store.applySnapshot(statsSnapshot(matched, missing))

        assertEquals(7_000L, listenStatsRepository.getByIdentityKey("matched.mp3|90000")!!.totalListenMs)
        assertEquals(100L, listenStatsRepository.getByIdentityKey("matched.mp3|90000")!!.firstPlayedAt)
        assertEquals(700L, listenStatsRepository.getByIdentityKey("matched.mp3|90000")!!.lastPlayedAt)
        assertTrue(listenStatsRepository.exportLocalPayload().unresolvedNodes.isEmpty())
        assertEquals(0L, listenStatsRepository.exportLocalPayload().songs.single {
            it.identityKey == "later.mp3|80000"
        }.boundSongId)

        insertSong("later.mp3", "/local/later.mp3", 80_000L, 3_528_000L)
        store.applySnapshot(statsSnapshot(matched, missing))

        assertEquals(3_000L, listenStatsRepository.getByIdentityKey("later.mp3|80000")!!.totalListenMs)
        assertTrue(listenStatsRepository.exportLocalPayload().unresolvedNodes.isEmpty())
        assertTrue(matchedId > 0L)
    }

    @Test
    fun applyPlaybackStatsBindsExactDurationAndReexportsRemoteWireMetadata() = runBlocking {
        val nearbyId = insertSong(
            "exact.mp3", "/local/nearby.mp3", 239_986L, 10_583_382L
        )
        val exactId = insertSong(
            "exact.mp3", "/local/exact.mp3", 239_987L, 10_583_412L
        )
        val identity = SyncSongIdentity(
            fileName = "exact.mp3",
            durationMs = 239_987L,
            totalSamples = 10_583_426L,
            contentHash = "remote-hash"
        )
        val remote = SyncPlaybackStatsSong(
            identity,
            listOf(SyncPlaybackStatsContribution(
                deviceId = "desktop",
                generation = 2L,
                datedListenMs = mapOf("2026-07-10" to 7_000L),
                firstPlayedAtUtcMs = 100L,
                lastPlayedAtUtcMs = 700L,
                updatedAtUtcMs = 700L
            ))
        )

        store.applySnapshot(statsSnapshot(remote))

        val localNode = listenStatsRepository.exportLocalPayload().songs.single()
        assertEquals(exactId, localNode.boundSongId)
        assertEquals(239_987L, localNode.durationMs)
        assertEquals(10_583_426L, localNode.totalSamples)
        assertEquals("remote-hash", localNode.contentHash)
        assertEquals(7_000L, localNode.contributions.single().dailyListenMs["2026-07-10"])
        assertTrue(nearbyId != localNode.boundSongId)

        val exported = store.exportSnapshot("phone", 2L).playbackStats!!
        assertEquals(1, exported.songs.size)
        assertEquals(identity, exported.songs.single().song)
        assertEquals(1, exported.songs.single().contributions.size)
        assertEquals(7_000L, exported.songs.single().contributions.single()
            .datedListenMs["2026-07-10"])
    }

    @Test
    fun applyPlaybackStatsBindsUniqueNearbySongAndReexportsExactWireIdentity() = runBlocking {
        val localId = insertSong(
            "observed.mp3", "/local/observed.mp3", 239_986L, 10_583_412L
        )
        val identity = SyncSongIdentity(
            "observed.mp3", 239_987L, 10_583_426L, contentHash = "wire-hash"
        )

        store.applySnapshot(statsSnapshot(SyncPlaybackStatsSong(
            identity,
            listOf(SyncPlaybackStatsContribution("desktop", 2L, mapOf("2026-07-10" to 1_000L)))
        )))

        val node = listenStatsRepository.exportLocalPayload().songs.single()
        assertEquals(localId, node.boundSongId)
        assertEquals(identity.fileName, node.fileName)
        assertEquals(identity.durationMs, node.durationMs)
        assertEquals(identity.totalSamples, node.totalSamples)
        assertEquals(identity.contentHash, node.contentHash)
        assertEquals(identity, store.exportSnapshot("phone", 2L).playbackStats!!.songs.single().song)
    }

    @Test
    fun playbackMatcherUsesSampleExactThenSampleTolerance() = runBlocking {
        val sampleExactId = insertSong("sample.mp3", "/local/exact.mp3", 100_001L, 1_000_000L)
        insertSong("sample.mp3", "/local/tolerance.mp3", 100_002L, 2_000_000L)

        store.applySnapshot(statsSnapshot(SyncPlaybackStatsSong(
            SyncSongIdentity("sample.mp3", 100_999L, 1_000_000L), emptyList()
        )))
        assertEquals(sampleExactId, listenStatsRepository.exportLocalPayload().songs.single().boundSongId)

        listenStatsRepository.clearCurrentDeviceStats()
        val toleranceId = songDao.getSongsByName("sample.mp3").single { it.totalSamples == 2_000_000L }.id
        store.applySnapshot(statsSnapshot(SyncPlaybackStatsSong(
            SyncSongIdentity("sample.mp3", 100_999L, 2_009_999L), emptyList()
        )))
        assertEquals(toleranceId, listenStatsRepository.exportLocalPayload().songs.single {
            it.totalSamples == 2_009_999L
        }.boundSongId)
    }

    @Test
    fun playbackMatcherUsesInclusiveDurationToleranceAndUniqueNameFallback() = runBlocking {
        val boundaryId = insertSong("boundary.mp3", "/local/boundary.mp3", 100_200L, 1_000_000L)
        store.applySnapshot(statsSnapshot(SyncPlaybackStatsSong(
            SyncSongIdentity("boundary.mp3", 100_000L), emptyList()
        )))
        assertEquals(boundaryId, listenStatsRepository.exportLocalPayload().songs.single().boundSongId)

        val fallbackId = insertSong("fallback.mp3", "/local/fallback.mp3", 900_000L, 2_000_000L)
        store.applySnapshot(statsSnapshot(SyncPlaybackStatsSong(
            SyncSongIdentity("fallback.mp3", 100_000L), emptyList()
        )))
        assertEquals(fallbackId, listenStatsRepository.exportLocalPayload().songs.first {
            it.normalizedFileName == "fallback.mp3"
        }.boundSongId)
    }

    @Test
    fun playbackMatcherLeavesAmbiguousNameUnbound() = runBlocking {
        insertSong("ambiguous.mp3", "/local/a/ambiguous.mp3", 100_000L, 1_000_000L)
        songDao.insertSongEntity(com.cpu.seamlessloopmobile.model.SongEntity(
            fileName = "ambiguous.mp3",
            filePath = "/local/b/ambiguous.mp3",
            duration = 100_000L,
            totalSamples = 1_000_000L
        ))

        store.applySnapshot(statsSnapshot(SyncPlaybackStatsSong(
            SyncSongIdentity("ambiguous.mp3", 200_000L), emptyList()
        )))

        assertEquals(0L, listenStatsRepository.exportLocalPayload().songs.single().boundSongId)
    }

    @Test
    fun reassociationClearsInvalidBindingAndRebindsAfterScan() = runBlocking {
        val firstId = insertSong("rescan.mp3", "/local/first.mp3", 60_000L, 2_000_000L)
        val identity = SyncSongIdentity("rescan.mp3", 60_000L, 2_000_000L)
        store.applySnapshot(statsSnapshot(SyncPlaybackStatsSong(identity, emptyList())))
        assertEquals(firstId, listenStatsRepository.exportLocalPayload().songs.single().boundSongId)

        songDao.deleteSongsByIds(listOf(firstId))
        store.applySnapshot(statsSnapshot())
        val stale = listenStatsRepository.exportLocalPayload().songs.single()
        assertEquals(0L, stale.boundSongId)
        assertEquals("rescan.mp3", stale.displayName)

        val replacementId = insertSong("rescan.mp3", "/local/replacement.mp3", 60_000L, 2_000_000L)
        store.applySnapshot(statsSnapshot())
        assertEquals(replacementId, listenStatsRepository.exportLocalPayload().songs.single().boundSongId)
    }

    @Test
    fun reassociationClearsBindingWhenCandidateBecomesAmbiguous() = runBlocking {
        val firstId = insertSong("ambiguous-rescan.mp3", "/local/first.mp3", 60_000L, 2_000_000L)
        store.applySnapshot(statsSnapshot(SyncPlaybackStatsSong(
            SyncSongIdentity("ambiguous-rescan.mp3", 60_000L, 2_000_000L), emptyList()
        )))
        assertEquals(firstId, listenStatsRepository.exportLocalPayload().songs.single().boundSongId)

        songDao.insertSongEntity(com.cpu.seamlessloopmobile.model.SongEntity(
            fileName = "ambiguous-rescan.mp3",
            filePath = "/local/second.mp3",
            duration = 60_000L,
            totalSamples = 2_000_000L
        ))
        store.applySnapshot(statsSnapshot())
        assertEquals(0L, listenStatsRepository.exportLocalPayload().songs.single().boundSongId)
    }

    @Test
    fun playbackNodesWithNearbyDurationsRemainSeparateOnLocalCollapse() = runBlocking {
        val payload = listenStatsRepository.exportLocalPayload()
        listenStatsRepository.applyLocalPayload(payload.copy(songs = payload.songs + listOf(
            ListenStatsSongNode(
                identityKey = "exact.mp3|239986",
                normalizedFileName = "exact.mp3",
                fileName = "exact.mp3",
                durationMs = 239_986L,
                contributions = listOf(
                    ListenStatsContribution("desktop", 1L, mapOf("2026-07-10" to 3_000L))
                )
            ),
            ListenStatsSongNode(
                identityKey = "exact.mp3|239987",
                normalizedFileName = "exact.mp3",
                fileName = "exact.mp3",
                durationMs = 239_987L,
                contributions = listOf(
                    ListenStatsContribution("desktop", 1L, mapOf("2026-07-10" to 5_000L))
                )
            )
        )), trackMutation = false)

        val exported = store.exportSnapshot("phone", 2L).playbackStats!!
        assertEquals(listOf(239_986L, 239_987L), exported.songs.map { it.song.durationMs })
        assertEquals(listOf(3_000L, 5_000L), exported.songs.map {
            it.contributions.single().datedListenMs["2026-07-10"]
        })
    }

    @Test
    fun applyWpfFixtureKeepsUnresolvedStatsGenerationsDatesTombstonesAndV2Egress() = runBlocking {
        insertSong("CAFÉ.MP3", "/local/CAFÉ.MP3", 123_456L, 5_444_400L)
        val stale = ListenStatsSongNode(
            identityKey = "café.mp3|123456",
            normalizedFileName = "café.mp3",
            fileName = "CAFÉ.MP3",
            durationMs = 123_456L,
            contributions = listOf(
                ListenStatsContribution(
                    deviceId = "android-pixel-8",
                    generation = 1L,
                    dailyListenMs = mapOf("2026-07-09" to 99_000L)
                )
            )
        )
        val localPayload = listenStatsRepository.exportLocalPayload()
        listenStatsRepository.applyLocalPayload(localPayload.copy(songs = listOf(stale)), trackMutation = false)

        val fixture = requireNotNull(javaClass.classLoader?.getResourceAsStream(
            "sync/playback_stats_v2_wpf_canonical.json"
        )).bufferedReader().use { SyncSnapshotSerializer().deserialize(it.readText()) }

        store.applySnapshot(fixture)

        val afterApply = listenStatsRepository.exportLocalPayload()
        assertTrue(afterApply.songs.any {
            it.normalizedFileName == "unresolved mix.flac" && it.boundSongId == 0L
        })
        assertTrue(afterApply.tombstones.any {
            it.deviceId == "android-pixel-8" && it.generation == 1L
        })

        val exported = store.exportSnapshot("android-pixel-8", fixture.exportedAt)
        val exportedStats = exported.playbackStats!!
        val cafe = exportedStats.songs.single { it.song.normalizedFileName == "café.mp3" }
        assertTrue(cafe.contributions.none {
            it.deviceId == "android-pixel-8" && it.generation == 1L
        })
        val androidGeneration = cafe.contributions.single {
            it.deviceId == "android-pixel-8" && it.generation == 2L
        }
        assertEquals(60_000L, androidGeneration.datedListenMs["2026-07-10"])
        assertEquals(30_000L, androidGeneration.datedListenMs["2026-07-11"])
        assertEquals(12_000L, androidGeneration.undatedListenMs)
        assertTrue(exportedStats.tombstones.any {
            it.deviceId == "android-pixel-8" && it.generation == 1L
        })
        assertTrue(exportedStats.songs.any {
            it.song.normalizedFileName == "unresolved mix.flac"
        })

        val prepared = exported.prepareV2Egress()
        assertEquals(com.cpu.seamlessloopmobile.data.sync.SYNC_SCHEMA_VERSION_V2, prepared.schemaVersion)
        val restored = SyncSnapshotSerializer().deserialize(
            SyncSnapshotSerializer().serialize(prepared)
        )
        val restoredStats = restored.playbackStats!!
        assertEquals(androidGeneration, restoredStats.songs.single {
            it.song.normalizedFileName == "café.mp3"
        }.contributions.single {
            it.deviceId == "android-pixel-8" && it.generation == 2L
        })
        assertTrue(restoredStats.tombstones.any {
            it.deviceId == "android-pixel-8" && it.generation == 1L
        })
        assertTrue(restoredStats.songs.any {
            it.song.normalizedFileName == "unresolved mix.flac"
        })
    }

    @Test
    fun exportPlaybackStatsMergesResolvedAndUnresolvedNodesWithSameIdentity() = runBlocking {
        val remote = SyncPlaybackStatsSong(
            SyncSongIdentity("duplicate-stats.mp3", 60_000L),
            listOf(
                SyncPlaybackStatsContribution("desktop", 2L, mapOf("2026-07-10" to 7_000L), 500L, 100L, 600L, 600L),
                SyncPlaybackStatsContribution("tablet", 1L, mapOf("2026-07-10" to 3_000L))
            )
        )
        store.applySnapshot(statsSnapshot(remote))
        val songId = insertSong("duplicate-stats.mp3", "/local/duplicate-stats.mp3", 60_000L, 2_646_000L)
        val payload = listenStatsRepository.exportLocalPayload()
        listenStatsRepository.applyLocalPayload(payload.copy(songs = payload.songs + listOf(
            ListenStatsSongNode(
                identityKey = "duplicate-stats.mp3|60000", normalizedFileName = "duplicate-stats.mp3",
                fileName = "duplicate-stats.mp3", boundSongId = songId, displayName = "Local metadata",
                durationMs = 60_000L, filePath = "/local/duplicate-stats.mp3",
                contributions = listOf(
                    ListenStatsContribution("desktop", 2L, mapOf("2026-07-10" to 4_000L, "2026-07-11" to 9_000L),
                        800L, 0L, 700L, 800L),
                    ListenStatsContribution("phone", 1L, mapOf("2026-07-10" to 2_000L))
                )
            )
        )), trackMutation = false)

        val contributions = store.exportSnapshot("phone", 1L).playbackStats!!.songs.single().contributions.associateBy {
            it.deviceId to it.generation
        }
        assertEquals(3, contributions.size)
        assertEquals(7_000L, contributions["desktop" to 2L]!!.datedListenMs["2026-07-10"])
        assertEquals(9_000L, contributions["desktop" to 2L]!!.datedListenMs["2026-07-11"])
        assertEquals(800L, contributions["desktop" to 2L]!!.undatedListenMs)
        assertEquals(100L, contributions["desktop" to 2L]!!.firstPlayedAtUtcMs)
        assertEquals(700L, contributions["desktop" to 2L]!!.lastPlayedAtUtcMs)
    }

    @Test
    fun exportPlaybackStatsExcludesTombstonedDuplicateContributionAcrossResolvedAndUnresolvedNodes() = runBlocking {
        val song = SyncPlaybackStatsSong(
            SyncSongIdentity("tombstoned-duplicate.mp3", 60_000L),
            listOf(SyncPlaybackStatsContribution("desktop", 2L, mapOf("2026-07-10" to 7_000L)))
        )
        val songId = insertSong("tombstoned-duplicate.mp3", "/local/tombstoned-duplicate.mp3", 60_000L, 2_646_000L)
        val payload = listenStatsRepository.exportLocalPayload()
        listenStatsRepository.applyLocalPayload(
            payload.copy(
                songs = listOf(
                    ListenStatsSongNode(
                        identityKey = "tombstoned-duplicate.mp3|60000",
                        normalizedFileName = "tombstoned-duplicate.mp3",
                        fileName = "tombstoned-duplicate.mp3",
                        boundSongId = songId,
                        durationMs = 60_000L,
                        filePath = "/local/tombstoned-duplicate.mp3",
                        contributions = listOf(ListenStatsContribution("desktop", 2L, mapOf("2026-07-10" to 4_000L)))
                    )
                ),
                unresolvedNodes = listOf(
                    ListenStatsUnresolvedNode("tombstoned-duplicate.mp3", 60_000L, com.google.gson.Gson().toJson(song))
                ),
                tombstones = listOf(ListenStatsTombstone("desktop", 2L, 1L))
            ),
            trackMutation = false
        )

        val exported = store.exportSnapshot("phone", 1L).playbackStats!!

        assertEquals(1, exported.songs.size)
        assertTrue(exported.songs.single().contributions.isEmpty())
    }

    @Test
    fun reassociatePlaybackStatsMergesOntoExistingLocalNodeAndPreservesMetadata() = runBlocking {
        val remote = SyncPlaybackStatsSong(
            SyncSongIdentity(
                fileName = "Reassociate.MP3",
                durationMs = 70_000L,
                totalSamples = 3_100_000L,
                contentHash = "remote-hash"
            ),
            listOf(SyncPlaybackStatsContribution("desktop", 2L, mapOf("2026-07-10" to 7_000L),
                firstPlayedAtUtcMs = 100L, lastPlayedAtUtcMs = 700L))
        )
        store.applySnapshot(statsSnapshot(remote))
        val songId = insertSong(
            "reassociate.mp3", "/local/reassociate.mp3", 70_000L, 3_087_000L,
            displayName = "Existing local metadata"
        )
        val payload = listenStatsRepository.exportLocalPayload()
        listenStatsRepository.applyLocalPayload(payload.copy(songs = payload.songs + listOf(
            ListenStatsSongNode(
                identityKey = "reassociate.mp3|70000", normalizedFileName = "reassociate.mp3",
                fileName = "reassociate.mp3", boundSongId = songId, displayName = "Existing local metadata",
                durationMs = 70_000L, totalSamples = 3_087_000L, contentHash = "local-hash",
                filePath = "/local/reassociate.mp3",
                contributions = listOf(
                    ListenStatsContribution("desktop", 2L, mapOf("2026-07-10" to 4_000L, "2026-07-11" to 9_000L),
                        firstPlayedAtUtcMs = 50L, lastPlayedAtUtcMs = 500L)
                )
            )
        )), trackMutation = false)

        val localMetadata = listenStatsRepository.exportLocalPayload().songs.single { it.boundSongId == songId }
        store.applySnapshot(statsSnapshot())

        val reassociated = listenStatsRepository.exportLocalPayload()
        assertTrue(reassociated.unresolvedNodes.isEmpty())
        val node = reassociated.songs.single()
        assertEquals(songId, node.boundSongId)
        assertEquals(localMetadata.displayName, node.displayName)
        assertEquals(localMetadata.filePath, node.filePath)
        assertEquals("Reassociate.MP3", node.fileName)
        assertEquals(3_100_000L, node.totalSamples)
        assertEquals("remote-hash", node.contentHash)
        val contributions = node.contributions.associateBy { it.deviceId to it.generation }
        assertEquals(1, contributions.size)
        assertEquals(7_000L, contributions["desktop" to 2L]!!.dailyListenMs["2026-07-10"])
        assertEquals(9_000L, contributions["desktop" to 2L]!!.dailyListenMs["2026-07-11"])
        assertEquals(50L, node.firstPlayedAt)
        assertEquals(700L, node.lastPlayedAt)

        val exported = store.exportSnapshot("phone", 2L).playbackStats!!
        assertEquals(
            SyncSongIdentity(
                fileName = "Reassociate.MP3",
                durationMs = 70_000L,
                totalSamples = 3_100_000L,
                contentHash = "remote-hash"
            ),
            exported.songs.single().song
        )
    }

    @Test
    fun rebindPlaybackStatsBindsAnUnboundNodeAfterLocalSongAppears() = runBlocking {
        val identity = SyncSongIdentity("rebind-later.mp3", 70_000L)
        store.applySnapshot(statsSnapshot(
            SyncPlaybackStatsSong(
                identity,
                listOf(SyncPlaybackStatsContribution("desktop", 1L, undatedListenMs = 5_000L))
            )
        ))
        assertEquals(0L, listenStatsRepository.exportLocalPayload().songs.single().boundSongId)

        val songId = insertSong("rebind-later.mp3", "/local/rebind-later.mp3", 70_000L, 3_087_000L)
        store.rebindPlaybackStats()

        assertEquals(songId, listenStatsRepository.exportLocalPayload().songs.single().boundSongId)
        assertEquals(5_000L, listenStatsRepository.exportLocalPayload().songs.single()
            .contributions.single().undatedListenMs)
    }

    @Test
    fun applySnapshotCollapsesParseableUnresolvedRevisionsButKeepsMalformedPayloads() = runBlocking {
        val older = SyncPlaybackStatsSong(
            SyncSongIdentity("missing.mp3", 60_000L),
            listOf(SyncPlaybackStatsContribution("desktop", 1L, mapOf("2026-07-10" to 2_000L), 100L, 200L, 200L))
        )
        val newer = older.copy(contributions = listOf(
            SyncPlaybackStatsContribution("desktop", 1L, mapOf("2026-07-10" to 5_000L), 300L, 100L, 400L, 400L)
        ))
        val payload = listenStatsRepository.exportLocalPayload()
        listenStatsRepository.applyLocalPayload(payload.copy(unresolvedNodes = listOf(
            ListenStatsUnresolvedNode("broken", 1L, "{not json}"),
            ListenStatsUnresolvedNode("structurally-broken", 2L, "{}"),
            ListenStatsUnresolvedNode("missing.mp3", 60_000L, com.google.gson.Gson().toJson(older))
        )), trackMutation = false)

        store.applySnapshot(statsSnapshot(newer))

        val afterApply = listenStatsRepository.exportLocalPayload()
        val unresolved = afterApply.unresolvedNodes
        assertEquals(2, unresolved.size)
        assertTrue(unresolved.any { it.payloadJson == "{not json}" })
        assertTrue(unresolved.any { it.payloadJson == "{}" })
        val contribution = afterApply.songs.single { it.normalizedFileName == "missing.mp3" }
            .contributions.single()
        assertEquals(5_000L, contribution.dailyListenMs["2026-07-10"])
        assertEquals(300L, contribution.undatedListenMs)
        assertEquals(100L, contribution.firstPlayedAtUtcMs)
        assertEquals(400L, contribution.lastPlayedAtUtcMs)
        val exported = store.exportSnapshot("phone", 2L).playbackStats!!
        assertEquals(1, exported.songs.size)
        assertEquals("missing.mp3", exported.songs.single().song.fileName)
    }

    @Test
    fun invalidSemanticUnresolvedPayloadsRemainVerbatimAndAreNotExported() = runBlocking {
        val invalidDate = """{"song":{"fileName":"bad-date.mp3","durationMs":1,"normalizedFileName":"bad-date.mp3"},"contributions":[{"deviceId":"desktop","generation":0,"datedListenMs":{"not-a-date":1}}]}"""
        val negativeValues = """{"song":{"fileName":"negative.mp3","durationMs":1,"normalizedFileName":"negative.mp3"},"contributions":[{"deviceId":"desktop","generation":-1,"datedListenMs":{"2026-07-10":-1}}]}"""
        val payload = listenStatsRepository.exportLocalPayload()
        listenStatsRepository.applyLocalPayload(payload.copy(unresolvedNodes = listOf(
            ListenStatsUnresolvedNode("bad-date.mp3", 1L, invalidDate),
            ListenStatsUnresolvedNode("negative.mp3", 1L, negativeValues)
        )), trackMutation = false)

        store.applySnapshot(statsSnapshot())

        val unresolved = listenStatsRepository.exportLocalPayload().unresolvedNodes
        assertEquals(listOf(invalidDate, negativeValues), unresolved.map { it.payloadJson })
        assertTrue(store.exportSnapshot("phone", 2L).playbackStats!!.songs.isEmpty())
    }

    private fun statsSnapshot(vararg songs: SyncPlaybackStatsSong) = SyncSnapshot(
        deviceId = "desktop",
        exportedAt = 1L,
        playbackStats = SyncPlaybackStats(
            devices = listOf(SyncPlaybackStatsDevice("desktop", "Desktop", 1L, 1L, 2L, "desktop")),
            songs = songs.toList(),
            tombstones = listOf(SyncPlaybackStatsTombstone("old", 1L, 1L))
        )
    )

    private suspend fun insertSong(
        fileName: String,
        filePath: String,
        duration: Long,
        totalSamples: Long,
        loopStart: Long = 0L,
        loopEnd: Long = 0L,
        rating: Int = 0,
        displayName: String? = null
    ): Long {
        return songDao.insertOrUpdateSong(
            Song(
                fileName = fileName,
                filePath = filePath,
                duration = duration,
                totalSamples = totalSamples,
                displayName = displayName,
                loopStart = loopStart,
                loopEnd = loopEnd,
                rating = rating
            )
        )
    }

    private class FakePlaylistIdMapper : PlaylistIdMapper {
        private val records = mutableMapOf<String, PlaylistSyncRecord>()
        private var nextId = 1

        override suspend fun getOrCreateSyncIdForExport(
            localId: Int,
            fingerprint: String,
            now: Long
        ): PlaylistSyncRecord {
            val existing = records.values.firstOrNull { it.localId == localId }
            if (existing != null) {
                val updated = if (existing.fingerprint == fingerprint) {
                    existing
                } else {
                    existing.copy(modifiedAt = now, fingerprint = fingerprint)
                }
                records[updated.syncId] = updated
                return updated
            }

            val record = PlaylistSyncRecord(
                syncId = "playlist-sync-${nextId++}",
                localId = localId,
                modifiedAt = now,
                fingerprint = fingerprint
            )
            records[record.syncId] = record
            return record
        }

        override suspend fun findLocalId(syncId: String): Int? = records[syncId]?.localId

        override suspend fun findSyncId(localId: Int): String? =
            records.values.firstOrNull { it.localId == localId }?.syncId

        override suspend fun saveMapping(
            syncId: String,
            localId: Int,
            modifiedAt: Long,
            fingerprint: String
        ) {
            records.entries.removeAll { it.key == syncId || it.value.localId == localId }
            records[syncId] = PlaylistSyncRecord(syncId, localId, modifiedAt, fingerprint)
        }

        override suspend fun removeStaleMappings(validLocalIds: Set<Int>) {
            records.entries.removeAll { it.value.localId !in validLocalIds }
        }

        override suspend fun clearAllMappings() {
            records.clear()
        }
    }
}
