package com.cpu.seamlessloopmobile.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cpu.seamlessloopmobile.data.stats.ListenStatsRepository
import com.cpu.seamlessloopmobile.data.stats.ListenStatsContribution
import com.cpu.seamlessloopmobile.data.stats.ListenStatsDevice
import com.cpu.seamlessloopmobile.data.stats.ListenStatsUnresolvedNode
import com.cpu.seamlessloopmobile.data.stats.ListenStatsSongNode
import com.cpu.seamlessloopmobile.data.stats.TrackStat
import com.cpu.seamlessloopmobile.data.sync.room.PlaylistIdMapper
import com.cpu.seamlessloopmobile.data.sync.room.PlaylistSyncRecord
import com.cpu.seamlessloopmobile.data.sync.room.RoomSyncSnapshotStore
import com.cpu.seamlessloopmobile.db.AppDatabase
import com.cpu.seamlessloopmobile.model.LoopPoint
import com.cpu.seamlessloopmobile.model.Playlist
import com.cpu.seamlessloopmobile.model.PlaylistDao
import com.cpu.seamlessloopmobile.model.Song
import com.cpu.seamlessloopmobile.model.SongDao
import com.cpu.seamlessloopmobile.model.SongEntity
import com.cpu.seamlessloopmobile.model.UserRating
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * SyncDataManagementRepository 单元测试。
 *
 * 验证预览、初始云端写入、云端删除、本地清除等管理操作。
 * 使用 Robolectric 内存数据库和 fake 依赖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncDataManagementRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var songDao: SongDao
    private lateinit var playlistDao: PlaylistDao
    private lateinit var mapper: FakePlaylistIdMapper
    private lateinit var snapshotStore: RoomSyncSnapshotStore
    private lateinit var fakeBackend: FakeGitHubSnapshotRemote
    private lateinit var fakeMetadata: FakeSyncMetadataStore
    private lateinit var listenStatsRepository: ListenStatsRepository
    private lateinit var listenStatsFile: File
    private lateinit var repo: SyncDataManagementRepository
    private lateinit var localRepo: SyncDataManagementRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        songDao = db.songDao()
        playlistDao = db.playlistDao()
        mapper = FakePlaylistIdMapper()
        fakeBackend = FakeGitHubSnapshotRemote()
        fakeMetadata = FakeSyncMetadataStore()
        listenStatsFile = File.createTempFile("listen_stats", ".json")
        listenStatsRepository = ListenStatsRepository(listenStatsFile)
        snapshotStore = RoomSyncSnapshotStore(db, songDao, playlistDao, mapper, listenStatsRepository)
        repo = SyncDataManagementRepository(
            database = db,
            songDao = songDao,
            playlistDao = playlistDao,
            snapshotStore = snapshotStore,
            backend = fakeBackend,
            metadataStore = fakeMetadata,
            playlistIdMapper = mapper,
            listenStatsRepository = listenStatsRepository
        )
        localRepo = SyncDataManagementRepository(
            database = db,
            songDao = songDao,
            playlistDao = playlistDao,
            snapshotStore = snapshotStore,
            metadataStore = fakeMetadata,
            playlistIdMapper = mapper,
            listenStatsRepository = listenStatsRepository
        )
    }

    @After
    fun tearDown() {
        db.close()
        listenStatsFile.delete()
    }

    // ===================================================================
    // Helper
    // ===================================================================

    /** Insert a test song and optionally loop point + rating. Returns song id. */
    private suspend fun insertSong(
        fileName: String,
        filePath: String = "/storage/$fileName",
        duration: Long = 100_000L,
        totalSamples: Long = 0L,
        loopStart: Long = 0L,
        loopEnd: Long = 0L,
        rating: Int = 0,
        ratingLastModified: Long = 0L
    ): Long {
        val id = songDao.insertSongEntity(SongEntity(
            fileName = fileName,
            filePath = filePath,
            duration = duration,
            totalSamples = totalSamples
        ))
        if (loopStart != 0L || loopEnd != 0L) {
            songDao.insertLoopPoint(LoopPoint(id, loopStart, loopEnd))
        }
        if (rating != 0) {
            songDao.insertUserRating(UserRating(id, rating, ratingLastModified))
        }
        return id
    }

    // ===================================================================
    // 1. preview matched / missing cloud song references
    // ===================================================================

    @Test
    fun `preview counts matched and missing cloud song references`() = runBlocking {
        // Insert a local song that will be matched
        insertSong(fileName = "matched.mp3", filePath = "/a/matched.mp3", duration = 100_000L)

        // Remote snapshot referencing matched + missing songs
        val matchedRef = SyncSongIdentity("matched.mp3", 100_000L)
        val missingRef = SyncSongIdentity("missing.mp3", 200_000L)

        val cloudSnapshot = SyncSnapshot(
            deviceId = "cloud-device",
            exportedAt = 5000L,
            playlists = listOf(
                SyncPlaylist(
                    id = "sync-pl-1",
                    name = "Cloud Playlist",
                    createdAt = 100L,
                    modifiedAt = 200L,
                    items = listOf(
                        SyncPlaylistItem(matchedRef, 0),
                        SyncPlaylistItem(missingRef, 1)
                    )
                )
            ),
            loopPoints = listOf(
                SyncLoopPointEntry(matchedRef, SyncLoopPoint(1000L, 5000L, 100L))
            ),
            ratings = listOf(
                SyncRatingEntry(missingRef, SyncRating(5, 200L))
            )
        )

        fakeBackend.downloadResult = SyncResult.Success(
            report = SyncReport(),
            snapshot = cloudSnapshot,
            remoteRevision = "sha-1"
        )

        val result = repo.preview()

        assertTrue("Expected Success", result is SyncDataManagementResult.Success)
        val preview = (result as SyncDataManagementResult.Success).data

        // Local: 1 song, 0 playlists
        assertEquals(1, preview.local.songCount)
        assertEquals(0, preview.local.playlistCount)

        // Cloud: exists, 1 playlist, 1 playlistItem, 1 loop, 1 rating
        val cloud = preview.cloud
        assertNotNull(cloud)
        assertEquals(true, cloud!!.exists)
        assertEquals("cloud-device", cloud.deviceId)
        assertEquals(1, cloud.playlists)
        assertEquals(2, cloud.playlistItems)     // 2 items across all playlists
        assertEquals(1, cloud.loopPointCount)
        assertEquals(1, cloud.ratingCount)

        // 2 unique song refs: matched.mp3 + missing.mp3
        //   matched.mp3 matches local → matched=1
        //   missing.mp3 has no local → missing=1
        assertEquals(1, cloud.matchedSongReferenceCount)
        assertEquals(1, cloud.missingSongReferenceCount)
        assertEquals(1, cloud.missingSongReferences.size)
        assertEquals(missingRef, cloud.missingSongReferences[0])
    }

    @Test
    fun `preview de-duplicates identical song refs across playlists loop and rating`() = runBlocking {
        insertSong(fileName = "common.mp3", filePath = "/a/common.mp3", duration = 90_000L)

        val commonRef = SyncSongIdentity("common.mp3", 90_000L)
        val missingRef = SyncSongIdentity("unique.mp3", 80_000L)

        val cloudSnapshot = SyncSnapshot(
            deviceId = "d", exportedAt = 100L,
            playlists = listOf(
                SyncPlaylist("p1", "P1", 0L, 0L, items = listOf(
                    SyncPlaylistItem(commonRef, 0),
                    SyncPlaylistItem(commonRef, 1) // same ref twice
                )),
                SyncPlaylist("p2", "P2", 0L, 0L, items = listOf(
                    SyncPlaylistItem(missingRef, 0)
                ))
            ),
            loopPoints = listOf(
                SyncLoopPointEntry(commonRef, SyncLoopPoint(100L, 200L, 10L))
            ),
            ratings = listOf(
                SyncRatingEntry(commonRef, SyncRating(3, 20L))
            )
        )

        fakeBackend.downloadResult = SyncResult.Success(
            report = SyncReport(), snapshot = cloudSnapshot, remoteRevision = "s"
        )

        val result = repo.preview()
        assertTrue(result is SyncDataManagementResult.Success)
        val cloud = (result as SyncDataManagementResult.Success).data.cloud!!
        assertTrue(cloud.exists)

        // 2 unique refs total (common.mp3 + unique.mp3)
        // common.mp3 matched, missing.mp3 unmatched
        assertEquals(1, cloud.matchedSongReferenceCount)
        assertEquals(1, cloud.missingSongReferenceCount)
        // Total items = 3 (2 items from p1 + 1 item from p2)
        assertEquals(3, cloud.playlistItems)
    }

    @Test
    fun `preview treats same file and duration with different totalSamples as matched same reference`() = runBlocking {
        insertSong(
            fileName = "1-02. Summer Pockets.flac",
            filePath = "/a/1-02. Summer Pockets.flac",
            duration = 239_987L,
            totalSamples = 10_583_412L
        )

        val phoneRef = SyncSongIdentity("1-02. Summer Pockets.flac", 239_987L, 10_583_412L)
        val desktopRef = SyncSongIdentity("1-02. Summer Pockets.flac", 239_987L, 10_583_426L)
        val cloudSnapshot = SyncSnapshot(
            deviceId = "desktop",
            exportedAt = 100L,
            playlists = listOf(
                SyncPlaylist(
                    id = "p1",
                    name = "P1",
                    createdAt = 0L,
                    modifiedAt = 0L,
                    items = listOf(
                        SyncPlaylistItem(desktopRef, 0),
                        SyncPlaylistItem(phoneRef, 1)
                    )
                )
            ),
            ratings = listOf(
                SyncRatingEntry(desktopRef, SyncRating(4, 20L))
            )
        )

        fakeBackend.downloadResult = SyncResult.Success(
            report = SyncReport(), snapshot = cloudSnapshot, remoteRevision = "s"
        )

        val result = repo.preview()
        assertTrue(result is SyncDataManagementResult.Success)
        val cloud = (result as SyncDataManagementResult.Success).data.cloud!!

        assertEquals(1, cloud.matchedSongReferenceCount)
        assertEquals(0, cloud.missingSongReferenceCount)
        assertTrue(cloud.missingSongReferences.isEmpty())
        assertEquals(2, cloud.playlistItems)
    }

    // ===================================================================
    // 2. preview returns non-existing cloud on NOT_FOUND
    // ===================================================================

    @Test
    fun `preview returns non-existing cloud on not found`() = runBlocking {
        // Insert a local song to verify local summary still populated
        insertSong(fileName = "local.mp3", filePath = "/a/local.mp3", duration = 50_000L)

        fakeBackend.downloadResult = SyncResult.Failure(
            "Not found", code = SyncErrorCode.NOT_FOUND
        )

        val result = repo.preview()

        assertTrue("Expected Success", result is SyncDataManagementResult.Success)
        val preview = (result as SyncDataManagementResult.Success).data

        assertEquals(1, preview.local.songCount)

        val cloud = preview.cloud
        assertNotNull(cloud)
        assertEquals(false, cloud!!.exists)
    }

    @Test
    fun `preview treats successful null snapshot as non-existing cloud`() = runBlocking {
        fakeBackend.downloadResult = SyncResult.Success(
            report = SyncReport(),
            snapshot = null,
            remoteRevision = "sha-empty"
        )

        val result = repo.preview()

        assertTrue("Expected Success", result is SyncDataManagementResult.Success)
        val cloud = (result as SyncDataManagementResult.Success).data.cloud
        assertNotNull(cloud)
        assertEquals(false, cloud!!.exists)
    }

    @Test
    fun `preview propagates download failure other than not found`() = runBlocking {
        fakeBackend.downloadResult = SyncResult.Failure(
            "Network error", code = SyncErrorCode.NETWORK
        )

        val result = repo.preview()

        assertTrue("Expected Failure", result is SyncDataManagementResult.Failure)
        assertEquals(SyncErrorCode.NETWORK, (result as SyncDataManagementResult.Failure).code)
    }

    // ===================================================================
    // 3. seed cloud only when the remote snapshot does not exist
    // ===================================================================

    @Test
    fun `seed cloud creates v2 snapshot when remote is not found`() = runBlocking {
        // Insert local data
        val songId = insertSong(
            fileName = "track.mp3", filePath = "/a/track.mp3",
            duration = 120_000L, loopStart = 1000L, loopEnd = 60_000L, rating = 4
        )
        val playlistId = playlistDao.insertPlaylist(
            Playlist(name = "My Playlist", createdAt = 100L)
        ).toInt()
        playlistDao.clearAndSyncPlaylist(playlistId, listOf(songId))

        fakeBackend.downloadResult = SyncResult.Failure("Not found", code = SyncErrorCode.NOT_FOUND)
        fakeBackend.uploadResult = SyncResult.Success(
            report = SyncReport(playlistsUploaded = 1, loopPointsUploaded = 1, ratingsUploaded = 1),
            remoteRevision = "sha-new"
        )

        val result = repo.seedCloudFromLocal()

        assertTrue("Expected Success", result is SyncDataManagementResult.Success)
        val report = (result as SyncDataManagementResult.Success).data

        // Seed upload must use no revision because the file is absent.
        assertEquals(1, fakeBackend.uploadCallCount)
        assertNull(fakeBackend.lastExpectedRevision)

        // Uploaded snapshot has the right content
        assertNotNull(fakeBackend.lastUploadedSnapshot)
        assertEquals(1, fakeBackend.lastUploadedSnapshot!!.playlists.size)
        assertEquals(1, fakeBackend.lastUploadedSnapshot!!.loopPoints.size)
        assertEquals(1, fakeBackend.lastUploadedSnapshot!!.ratings.size)
        assertEquals("test-device", fakeBackend.lastUploadedSnapshot!!.deviceId)
        assertEquals(SYNC_SCHEMA_VERSION_V2, fakeBackend.lastUploadedSnapshot!!.schemaVersion)
        assertNotNull(fakeBackend.lastUploadedSnapshot!!.playbackStats)

        // Report reflects uploaded counts
        assertEquals(1, report.playlistsUploaded)
        assertEquals(1, report.loopPointsUploaded)
        assertEquals(1, report.ratingsUploaded)

        // Metadata was saved
        assertEquals("sha-new", fakeMetadata.lastRemoteRevision)
        assertTrue(fakeMetadata.lastSyncTime > 0)
    }

    @Test
    fun `seed cloud rejects any existing remote snapshot without uploading`() = runBlocking {
        insertSong(fileName = "a.mp3", filePath = "/a/a.mp3", duration = 50_000L)

        fakeBackend.downloadResult = SyncResult.Success(
            SyncReport(),
            SyncSnapshot(deviceId = "remote", exportedAt = 1L),
            remoteRevision = "sha-existing"
        )

        val result = repo.seedCloudFromLocal()

        assertTrue(result is SyncDataManagementResult.Failure)
        assertEquals(SyncErrorCode.INVALID_REMOTE, (result as SyncDataManagementResult.Failure).code)
        assertTrue(result.message.contains("normal sync"))
        assertTrue(result.message.contains("delete"))
        assertEquals(0, fakeBackend.uploadCallCount)
    }

    @Test
    fun `seed cloud propagates upload conflict without retry`() = runBlocking {
        insertSong(fileName = "a.mp3", filePath = "/a/a.mp3", duration = 50_000L)
        fakeBackend.downloadResult = SyncResult.Failure("Not found", code = SyncErrorCode.NOT_FOUND)
        fakeBackend.uploadResult = SyncResult.Failure(
            "Conflict",
            code = SyncErrorCode.CONFLICT
        )

        val result = repo.seedCloudFromLocal()

        assertTrue("Expected Failure", result is SyncDataManagementResult.Failure)
        assertEquals(SyncErrorCode.CONFLICT, (result as SyncDataManagementResult.Failure).code)
        assertEquals(1, fakeBackend.uploadCallCount)
        assertNull(fakeBackend.lastExpectedRevision)
        assertEquals("sha-old", fakeMetadata.lastRemoteRevision)
    }

    // ===================================================================
    // 4. delete cloud clears metadata
    // ===================================================================

    @Test
    fun `delete cloud clears metadata on success`() = runBlocking {
        fakeMetadata.lastRemoteRevision = "sha-existing"
        fakeMetadata.lastSyncTime = 5000L

        fakeBackend.deleteResult = SyncResult.Success(SyncReport())

        val result = repo.deleteCloudSnapshot()

        assertTrue("Expected Success", result is SyncDataManagementResult.Success)

        // Metadata cleared
        assertNull(fakeMetadata.lastRemoteRevision)
        assertEquals(0L, fakeMetadata.lastSyncTime)
    }

    @Test
    fun `delete cloud propagates failure`() = runBlocking {
        fakeBackend.deleteResult = SyncResult.Failure(
            "Unauthorized", code = SyncErrorCode.UNAUTHORIZED
        )

        val result = repo.deleteCloudSnapshot()

        assertTrue("Expected Failure", result is SyncDataManagementResult.Failure)
        assertEquals(SyncErrorCode.UNAUTHORIZED, (result as SyncDataManagementResult.Failure).code)

        // Metadata should NOT be cleared on failure
        assertEquals("sha-old", fakeMetadata.lastRemoteRevision)
    }

    // ===================================================================
    // 5. clear local selected data
    // ===================================================================

    @Test
    fun `clear local loop and rating preserves song and playlist`() = runBlocking {
        // Insert data
        val songId = insertSong(
            fileName = "s.mp3", filePath = "/a/s.mp3", duration = 100_000L,
            loopStart = 1000L, loopEnd = 5000L, rating = 4, ratingLastModified = 100L
        )
        // Verify initial state
        var allSongs = songDao.getAllSongs()
        assertEquals(1, allSongs.size)
        assertEquals(1000L, allSongs[0].loopStart)
        assertEquals(4, allSongs[0].rating)

        // Clear loop points + ratings only
        val result = repo.clearLocalSyncData(
            ClearLocalSyncDataSelection(clearLoopPoints = true, clearRatings = true)
        )

        assertTrue("Expected Success", result is SyncDataManagementResult.Success)
        val summary = (result as SyncDataManagementResult.Success).data

        // Song count unchanged
        assertEquals(1, summary.songCount)
        // Loop and rating should be gone
        assertEquals(0, summary.loopPointCount)
        assertEquals(0, summary.ratingCount)

        // Verify actual DB state
        allSongs = songDao.getAllSongs()
        assertEquals(1, allSongs.size)
        assertEquals(0L, allSongs[0].loopStart)
        assertEquals(0, allSongs[0].rating)

        // Metadata should be cleared
        assertNull(fakeMetadata.lastRemoteRevision)
        assertEquals(0L, fakeMetadata.lastSyncTime)
    }

    @Test
    fun `clear local playlists also clears mapper mappings and leaves songs`() = runBlocking {
        // Insert data
        val songId = insertSong(
            fileName = "s.mp3", filePath = "/a/s.mp3", duration = 100_000L,
            loopStart = 1000L, loopEnd = 5000L, rating = 3
        )
        val playlistId = playlistDao.insertPlaylist(
            Playlist(name = "Test", createdAt = 100L)
        ).toInt()
        playlistDao.clearAndSyncPlaylist(playlistId, listOf(songId))

        // Save a mapping to verify it's cleared
        mapper.saveMapping("sync-1", playlistId, 200L, "fp")

        // Verify initial state
        assertEquals(1, songDao.getAllSongs().size)
        assertEquals(1, playlistDao.getPlaylistsWithCounts().size)
        assertNotNull(mapper.findLocalId("sync-1"))

        // Clear only playlists
        val result = repo.clearLocalSyncData(
            ClearLocalSyncDataSelection(clearPlaylists = true)
        )

        assertTrue("Expected Success", result is SyncDataManagementResult.Success)
        val summary = (result as SyncDataManagementResult.Success).data

        // Playlist should be gone
        assertEquals(0, summary.playlistCount)
        assertEquals(0, summary.playlistItemCount)
        // Song count unchanged
        assertEquals(1, summary.songCount)
        // Loop and rating still present
        assertEquals(1, summary.loopPointCount)
        assertEquals(1, summary.ratingCount)

        // Mapper mappings cleared
        assertNull(mapper.findLocalId("sync-1"))

        // Metadata cleared
        assertNull(fakeMetadata.lastRemoteRevision)
        assertEquals(0L, fakeMetadata.lastSyncTime)
        // Mutation version was incremented
        assertEquals(6, fakeMetadata.mutationVersion)
    }

    @Test
    fun `clear local with empty selection returns failure`() = runBlocking {
        val result = repo.clearLocalSyncData(
            ClearLocalSyncDataSelection()
        )

        assertTrue("Expected Failure", result is SyncDataManagementResult.Failure)
    }

    @Test
    fun `clear local stats only clears stats without changing synced data or metadata`() = runBlocking {
        val songId = insertSong(
            fileName = "s.mp3",
            loopStart = 1000L,
            loopEnd = 5000L,
            rating = 4
        )
        val playlistId = playlistDao.insertPlaylist(Playlist(name = "Test", createdAt = 100L)).toInt()
        playlistDao.clearAndSyncPlaylist(playlistId, listOf(songId))
        listenStatsRepository.recordListenDeltaNow(
            TrackStat(fileName = "s.mp3", durationMs = 100_000L, identityKey = "s.mp3|100000"),
            1_000L
        )

        val result = localRepo.clearLocalSyncData(
            ClearLocalSyncDataSelection(clearListenStats = true)
        )

        assertTrue("Expected Success", result is SyncDataManagementResult.Success)
        assertTrue(listenStatsRepository.allStats.value.isEmpty())
        assertEquals(1, songDao.getAllSongs().size)
        assertEquals(1, playlistDao.getPlaylistsWithCounts().size)
        assertEquals(1000L, songDao.getAllSongs().single().loopStart)
        assertEquals(4, songDao.getAllSongs().single().rating)
        assertEquals("sha-old", fakeMetadata.lastRemoteRevision)
        assertEquals(1000L, fakeMetadata.lastSyncTime)
        assertEquals(5, fakeMetadata.mutationVersion)
    }

    @Test
    fun `clear local combined selection clears stats and synced data`() = runBlocking {
        insertSong(fileName = "s.mp3", loopStart = 1000L, loopEnd = 5000L, rating = 4)
        listenStatsRepository.recordListenDeltaNow(
            TrackStat(fileName = "s.mp3", durationMs = 100_000L, identityKey = "s.mp3|100000"),
            1_000L
        )

        val result = repo.clearLocalSyncData(
            ClearLocalSyncDataSelection(clearLoopPoints = true, clearListenStats = true)
        )

        assertTrue("Expected Success", result is SyncDataManagementResult.Success)
        assertTrue(listenStatsRepository.allStats.value.isEmpty())
        assertEquals(0L, songDao.getAllSongs().single().loopStart)
        assertNull(fakeMetadata.lastRemoteRevision)
        assertEquals(6, fakeMetadata.mutationVersion)
    }

    @Test
    fun `local repository clears all selected local data without remote backend`() = runBlocking {
        val songId = insertSong(fileName = "s.mp3", loopStart = 1000L, loopEnd = 5000L, rating = 4)
        val playlistId = playlistDao.insertPlaylist(Playlist(name = "Test", createdAt = 100L)).toInt()
        playlistDao.clearAndSyncPlaylist(playlistId, listOf(songId))
        listenStatsRepository.recordListenDeltaNow(
            TrackStat(fileName = "s.mp3", durationMs = 100_000L, identityKey = "s.mp3|100000"),
            1_000L
        )

        val result = localRepo.clearLocalSyncData(
            ClearLocalSyncDataSelection(
                clearPlaylists = true,
                clearLoopPoints = true,
                clearRatings = true,
                clearListenStats = true
            )
        )

        assertTrue(result is SyncDataManagementResult.Success)
        assertTrue(listenStatsRepository.allStats.value.isEmpty())
        assertEquals(1, songDao.getAllSongs().size)
        assertEquals(0, playlistDao.getPlaylistsWithCounts().size)
        assertEquals(0L, songDao.getAllSongs().single().loopStart)
        assertEquals(0, songDao.getAllSongs().single().rating)
        assertNull(fakeMetadata.lastRemoteRevision)
        assertEquals(6, fakeMetadata.mutationVersion)
    }

    @Test
    fun `local repository rejects remote operations without backend`() = runBlocking {
        assertTrue(localRepo.preview() is SyncDataManagementResult.Failure)
        assertTrue(localRepo.seedCloudFromLocal() is SyncDataManagementResult.Failure)
        assertTrue(localRepo.deleteCloudSnapshot() is SyncDataManagementResult.Failure)
    }

    // ===================================================================
    // 6. playback-stat source device management
    // ===================================================================

    @Test
    fun `lists source devices with aggregated effective contributions`() = runBlocking {
        seedStatsSources()

        val result = localRepo.getLocalPlaybackStatsSourceDevices()

        assertTrue(result is SyncDataManagementResult.Success)
        val sources = (result as SyncDataManagementResult.Success).data
        assertEquals(2, sources.size)
        val current = sources.first { it.isCurrentDevice }
        val other = sources.first { it.deviceId == "desktop" }
        assertEquals(1_500L, current.contributedListenMs)
        assertTrue(current.hasEffectiveContributions)
        assertEquals("android", current.platform)
        assertEquals("Desktop", other.displayName)
        assertEquals(2_500L, other.contributedListenMs)
        assertTrue(other.hasEffectiveContributions)
    }

    @Test
    fun `deleting current source rotates generation and removes effective contribution`() = runBlocking {
        seedStatsSources()
        val before = listenStatsRepository.exportLocalPayload()

        val result = localRepo.deleteLocalPlaybackStatsSourceDeviceHistories(
            setOf(before.currentDeviceId)
        )

        assertTrue(result is SyncDataManagementResult.Success)
        val current = (result as SyncDataManagementResult.Success).data.first { it.isCurrentDevice }
        val after = listenStatsRepository.exportLocalPayload()
        assertTrue(after.currentGeneration > before.currentGeneration)
        assertEquals(0L, current.contributedListenMs)
        assertTrue(!current.hasEffectiveContributions)
    }

    @Test
    fun `deleting another source suppresses its contribution without rotating current generation`() = runBlocking {
        seedStatsSources()
        val before = listenStatsRepository.exportLocalPayload()

        val result = localRepo.deleteLocalPlaybackStatsSourceDeviceHistories(setOf("desktop"))

        assertTrue(result is SyncDataManagementResult.Success)
        val sources = (result as SyncDataManagementResult.Success).data
        val desktop = sources.first { it.deviceId == "desktop" }
        val current = sources.first { it.isCurrentDevice }
        assertEquals(0L, desktop.contributedListenMs)
        assertTrue(!desktop.hasEffectiveContributions)
        assertTrue(desktop.allKnownGenerationsRemoved)
        assertEquals(before.currentGeneration, current.currentGeneration)
        assertEquals(1_500L, current.contributedListenMs)
    }

    @Test
    fun `source device summary includes unresolved contributions`() = runBlocking {
        seedStatsSources(includeUnresolvedDesktopGeneration = true)
        val payload = listenStatsRepository.exportLocalPayload()
        listenStatsRepository.applyLocalPayload(payload.copy(unresolvedNodes = payload.unresolvedNodes +
            ListenStatsUnresolvedNode("structurally-broken", 0L, "{}")), trackMutation = false)

        val result = localRepo.getLocalPlaybackStatsSourceDevices()

        assertTrue(result is SyncDataManagementResult.Success)
        val desktop = (result as SyncDataManagementResult.Success).data.first { it.deviceId == "desktop" }
        assertEquals(5_500L, desktop.contributedListenMs)
        assertTrue(desktop.hasEffectiveContributions)
    }

    @Test
    fun `source device summary collapses duplicate unresolved cumulative revisions`() = runBlocking {
        val payload = listenStatsRepository.exportLocalPayload()
        val older = SyncPlaybackStatsSong(
            SyncSongIdentity("missing.mp3", 60_000L),
            listOf(SyncPlaybackStatsContribution("desktop", 3L, mapOf("2026-01-01" to 2_000L), 100L))
        )
        val newer = older.copy(contributions = listOf(
            SyncPlaybackStatsContribution("desktop", 3L, mapOf("2026-01-01" to 5_000L), 400L)
        ))
        listenStatsRepository.applyLocalPayload(payload.copy(unresolvedNodes = listOf(
            ListenStatsUnresolvedNode("missing.mp3", 60_000L, com.google.gson.Gson().toJson(older)),
            ListenStatsUnresolvedNode("missing.mp3", 60_000L, com.google.gson.Gson().toJson(newer))
        )), trackMutation = false)

        val result = localRepo.getLocalPlaybackStatsSourceDevices()

        assertTrue(result is SyncDataManagementResult.Success)
        val desktop = (result as SyncDataManagementResult.Success).data.single { it.deviceId == "desktop" }
        assertEquals(5_400L, desktop.contributedListenMs)
    }

    @Test
    fun `source device summary ignores semantically invalid unresolved contributions`() = runBlocking {
        val invalidDate = """{"song":{"fileName":"bad-date.mp3","durationMs":1,"normalizedFileName":"bad-date.mp3"},"contributions":[{"deviceId":"desktop","generation":0,"datedListenMs":{"not-a-date":1}}]}"""
        val negativeCumulative = """{"song":{"fileName":"negative-total.mp3","durationMs":1,"normalizedFileName":"negative-total.mp3"},"contributions":[{"deviceId":"desktop","generation":0,"undatedListenMs":-1}]}"""
        val negativeGeneration = """{"song":{"fileName":"negative-generation.mp3","durationMs":1,"normalizedFileName":"negative-generation.mp3"},"contributions":[{"deviceId":"desktop","generation":-1,"datedListenMs":{}}]}"""
        val payload = listenStatsRepository.exportLocalPayload()
        listenStatsRepository.applyLocalPayload(payload.copy(unresolvedNodes = listOf(
            ListenStatsUnresolvedNode("bad-date.mp3", 1L, invalidDate),
            ListenStatsUnresolvedNode("negative-total.mp3", 1L, negativeCumulative),
            ListenStatsUnresolvedNode("negative-generation.mp3", 1L, negativeGeneration)
        )), trackMutation = false)

        val result = localRepo.getLocalPlaybackStatsSourceDevices()

        assertTrue(result is SyncDataManagementResult.Success)
        val sources = (result as SyncDataManagementResult.Success).data
        assertTrue(sources.none { it.deviceId == "desktop" })
    }

    @Test
    fun `deleting another source tombstones unresolved generations too`() = runBlocking {
        seedStatsSources(includeUnresolvedDesktopGeneration = true)

        val result = localRepo.deleteLocalPlaybackStatsSourceDeviceHistories(setOf("desktop"))

        assertTrue(result is SyncDataManagementResult.Success)
        val desktop = (result as SyncDataManagementResult.Success).data.first { it.deviceId == "desktop" }
        val payload = listenStatsRepository.exportLocalPayload()
        val desktopTombstones = payload.tombstones.filter { it.deviceId == "desktop" }.map { it.generation }.toSet()
        assertEquals(setOf(3L, 4L), desktopTombstones)
        assertEquals(0L, desktop.contributedListenMs)
        assertTrue(desktop.allKnownGenerationsRemoved)
    }

    @Test
    fun `clear local stats failure returns failure without claiming success`() = runBlocking {
        val failingRepo = repositoryWithUnwritableStatsFile()

        val result = failingRepo.clearLocalSyncData(
            ClearLocalSyncDataSelection(clearListenStats = true)
        )

        assertTrue(result is SyncDataManagementResult.Failure)
        assertEquals("sha-old", fakeMetadata.lastRemoteRevision)
        assertEquals(5, fakeMetadata.mutationVersion)
    }

    @Test
    fun `combined clear preserves sync metadata mutation when stats clear fails`() = runBlocking {
        insertSong(fileName = "s.mp3", loopStart = 1_000L, loopEnd = 5_000L)
        val failingRepo = repositoryWithUnwritableStatsFile()

        val result = failingRepo.clearLocalSyncData(
            ClearLocalSyncDataSelection(clearLoopPoints = true, clearListenStats = true)
        )

        assertTrue(result is SyncDataManagementResult.Failure)
        assertEquals(0L, songDao.getAllSongs().single().loopStart)
        assertNull(fakeMetadata.lastRemoteRevision)
        assertEquals(6, fakeMetadata.mutationVersion)
    }

    // ===================================================================
    // 7. getLocalSummary returns correct counts
    // ===================================================================

    @Test
    fun `getLocalSummary returns accurate counts`() = runBlocking {
        // No data → all zeros
        var summary = repo.getLocalSummary()
        assertEquals(0, summary.songCount)
        assertEquals(0, summary.playlistCount)
        assertEquals(0, summary.loopPointCount)
        assertEquals(0, summary.ratingCount)

        // Insert data
        val sA = insertSong(fileName = "a.mp3", filePath = "/a/a.mp3", duration = 100_000L)
        val sB = insertSong(fileName = "b.mp3", filePath = "/a/b.mp3", duration = 200_000L,
            loopStart = 500L, loopEnd = 1000L, rating = 4)
        val sC = insertSong(fileName = "c.mp3", filePath = "/a/c.mp3", duration = 300_000L,
            loopStart = 100L, loopEnd = 200L) // no rating
        val plId = playlistDao.insertPlaylist(Playlist(name = "P1", createdAt = 100L)).toInt()
        playlistDao.clearAndSyncPlaylist(plId, listOf(sA, sB))

        summary = repo.getLocalSummary()
        assertEquals(3, summary.songCount)
        assertEquals(1, summary.playlistCount)
        assertEquals(2, summary.playlistItemCount)
        assertEquals(2, summary.loopPointCount)
        assertEquals(1, summary.ratingCount)
    }

    private suspend fun seedStatsSources(includeUnresolvedDesktopGeneration: Boolean = false) {
        val initial = listenStatsRepository.exportLocalPayload()
        val currentId = initial.currentDeviceId
        listenStatsRepository.applyLocalPayload(
            initial.copy(
                devices = initial.devices + ListenStatsDevice(
                    deviceId = "desktop",
                    displayName = "Desktop",
                    platform = "windows",
                    currentGeneration = 3L
                ),
                songs = listOf(
                    ListenStatsSongNode(
                        identityKey = "one|1",
                        normalizedFileName = "one.mp3",
                        contributions = listOf(
                            ListenStatsContribution(
                                deviceId = currentId,
                                generation = initial.currentGeneration,
                                dailyListenMs = mapOf("2026-01-01" to 1_000L),
                                undatedListenMs = 500L
                            ),
                            ListenStatsContribution(
                                deviceId = "desktop",
                                generation = 3L,
                                dailyListenMs = mapOf("2026-01-01" to 2_000L),
                                undatedListenMs = 500L
                            )
                        )
                    )
                ),
                unresolvedNodes = if (includeUnresolvedDesktopGeneration) {
                    listOf(
                        ListenStatsUnresolvedNode(
                            normalizedFileName = "ghost.mp3",
                            durationMs = 10L,
                            payloadJson = com.google.gson.Gson().toJson(
                                SyncPlaybackStatsSong(
                                    SyncSongIdentity("ghost.mp3", 10L),
                                    listOf(
                                        SyncPlaybackStatsContribution(
                                            deviceId = "desktop",
                                            generation = 4L,
                                            datedListenMs = mapOf("2026-01-02" to 3_000L)
                                        )
                                    )
                                )
                            )
                        )
                    )
                } else {
                    initial.unresolvedNodes
                }
            )
        )
    }

    private fun repositoryWithUnwritableStatsFile(): SyncDataManagementRepository {
        val statsParentAsFile = File.createTempFile("listen_stats_parent", ".tmp").apply {
            deleteOnExit()
        }
        return SyncDataManagementRepository(
            database = db,
            songDao = songDao,
            playlistDao = playlistDao,
            snapshotStore = snapshotStore,
            backend = fakeBackend,
            metadataStore = fakeMetadata,
            playlistIdMapper = mapper,
            listenStatsRepository = ListenStatsRepository(File(statsParentAsFile, "listen_stats.json"))
        )
    }

    // ===================================================================
    // Fake implementations
    // ===================================================================

    class FakeGitHubSnapshotRemote : GitHubSnapshotRemote {
        var downloadResult: SyncResult = SyncResult.Failure(
            "Not found", code = SyncErrorCode.NOT_FOUND
        )
        var uploadResult: SyncResult = SyncResult.Success(
            SyncReport(), remoteRevision = "fake-sha"
        )
        var deleteResult: SyncResult = SyncResult.Success(SyncReport())

        var downloadCallCount = 0
        var uploadCallCount = 0
        var lastUploadedSnapshot: SyncSnapshot? = null
        var lastExpectedRevision: String? = null

        override suspend fun downloadSnapshot(snapshotId: String?): SyncResult {
            downloadCallCount++
            return downloadResult
        }

        override suspend fun uploadSnapshot(
            snapshot: SyncSnapshot,
            expectedRevision: String?
        ): SyncResult {
            uploadCallCount++
            lastUploadedSnapshot = snapshot
            lastExpectedRevision = expectedRevision
            return uploadResult
        }

        override suspend fun deleteSnapshot(): SyncResult {
            return deleteResult
        }
    }

    class FakeSyncMetadataStore : SyncMetadataStore {
        var deviceId: String = "test-device"
        var lastSyncTime: Long = 1000L
        var lastRemoteRevision: String? = "sha-old"
        var mutationVersion: Int = 5

        override suspend fun getDeviceId(): String = deviceId
        override suspend fun getLastSyncTime(): Long = lastSyncTime
        override suspend fun getLastRemoteRevision(): String? = lastRemoteRevision
        override suspend fun getMutationVersion(): Int = mutationVersion
        override suspend fun markMutation() { mutationVersion++ }
        override suspend fun saveSuccessfulSync(remoteRevision: String, syncTime: Long) {
            lastRemoteRevision = remoteRevision
            lastSyncTime = syncTime
        }
        override suspend fun clearSyncMetadata() {
            lastRemoteRevision = null
            lastSyncTime = 0L
        }
    }

    class FakePlaylistIdMapper : PlaylistIdMapper {
        private val records = mutableMapOf<String, PlaylistSyncRecord>()

        override suspend fun getOrCreateSyncIdForExport(
            localId: Int, fingerprint: String, now: Long
        ): PlaylistSyncRecord {
            val existing = records.values.firstOrNull { it.localId == localId }
            if (existing != null) {
                val updated = if (existing.fingerprint == fingerprint) existing
                else existing.copy(modifiedAt = now, fingerprint = fingerprint)
                records[updated.syncId] = updated
                return updated
            }
            val syncId = "sync-${localId}-$now"
            val rec = PlaylistSyncRecord(syncId, localId, now, fingerprint)
            records[syncId] = rec
            return rec
        }

        override suspend fun findLocalId(syncId: String): Int? = records[syncId]?.localId
        override suspend fun findSyncId(localId: Int): String? =
            records.values.firstOrNull { it.localId == localId }?.syncId

        override suspend fun saveMapping(
            syncId: String, localId: Int, modifiedAt: Long, fingerprint: String
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
