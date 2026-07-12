package com.cpu.seamlessloopmobile.audio.stats

import com.cpu.seamlessloopmobile.data.stats.ListenStatsRepository
import com.cpu.seamlessloopmobile.data.stats.TrackStat
import com.cpu.seamlessloopmobile.model.Song
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackStatsTrackerTest {

    private lateinit var repo: ListenStatsRepository
    private lateinit var tracker: PlaybackStatsTracker
    private var fakeElapsed: Long = 0L

    @Before
    fun setUp() {
        // Use a temporary JSON file for each test
        val tmpFile = File.createTempFile("listen_stats_test_", ".json")
        tmpFile.deleteOnExit()
        repo = ListenStatsRepository(
            jsonFile = tmpFile
        )
        fakeElapsed = 0L
        tracker = PlaybackStatsTracker(
            repository = repo,
            timeSource = { fakeElapsed }
        )
    }

    // --- Song helpers --------------------------------------------------------

    private fun song(
        id: Long = 1L,
        fileName: String = "test.mp3",
        filePath: String = "/music/test.mp3",
        displayName: String = "Test Song",
        artist: String = "Test Artist",
        album: String = "Test Album",
        duration: Long = 200_000L,
        coverPath: String? = null
    ): Song = Song(
        id = id,
        fileName = fileName,
        filePath = filePath,
        displayName = displayName,
        duration = duration,
        artist = artist,
        album = album,
        coverPath = coverPath,
        mediaId = 100L
    )

    // --- Tests ---------------------------------------------------------------

    @Test
    fun initialTrackingIsFalse() {
        assertFalse(tracker.isTracking)
    }

    @Test
    fun onSongChangedSetsCurrentStatWithoutTracking() = runTest {
        val s = song()
        tracker.onSongChanged(s)
        assertFalse(tracker.isTracking)
    }

    @Test
    fun onPlayingChangedTrueStartsTracking() {
        tracker.onSongChanged(song())
        assertFalse(tracker.isTracking)

        tracker.onPlayingChanged(true)
        assertTrue(tracker.isTracking)
    }

    @Test
    fun onPlayingChangedFalseStopsTracking() {
        tracker.onSongChanged(song())
        tracker.onPlayingChanged(true)
        assertTrue(tracker.isTracking)

        tracker.onPlayingChanged(false)
        assertFalse(tracker.isTracking)
    }

    @Test
    fun accumulatesTimeAndFlushesOnSongChange() = runTest {
        val s = song(id = 1, fileName = "song_a.mp3", duration = 200_000L)
        tracker.onSongChanged(s)
        tracker.onPlayingChanged(true)

        fakeElapsed = 5_000L // 5 seconds of listening

        val s2 = song(id = 2, fileName = "song_b.mp3", duration = 180_000L)
        tracker.onSongChanged(s2)
        tracker.onPlayingChanged(true)

        // song_a should have 5000ms tracked
        val keyA = TrackStat.identityKey("song_a.mp3", 200_000L, "/music/song_a.mp3")
        val statA = repo.getByIdentityKey(keyA)
        assertEquals(5_000L, statA?.totalListenMs ?: 0L)
    }

    @Test
    fun flushPeriodicPersistsAccumulatedTimeWhileTracking() = runTest {
        val s = song(id = 1, fileName = "track.mp3", duration = 300_000L)
        tracker.onSongChanged(s)

        fakeElapsed = 2_000L
        tracker.onPlayingChanged(true)

        fakeElapsed = 12_000L // 10s of accumulated time

        tracker.flushPeriodic()

        val key = TrackStat.identityKey("track.mp3", 300_000L, "/music/track.mp3")
        val stat = repo.getByIdentityKey(key)
        assertEquals(10_000L, stat?.totalListenMs ?: 0L)

        // Tracking should still be active
        assertTrue(tracker.isTracking)
    }

    @Test
    fun flushFinalStopsTrackingAndPersists() = runTest {
        val s = song(id = 1, fileName = "final.mp3", duration = 120_000L)
        tracker.onSongChanged(s)
        fakeElapsed = 100L
        tracker.onPlayingChanged(true)

        fakeElapsed = 7_100L // 7s accumulated

        tracker.flushFinal()

        assertFalse(tracker.isTracking)

        val key = TrackStat.identityKey("final.mp3", 120_000L, "/music/final.mp3")
        val stat = repo.getByIdentityKey(key)
        assertEquals(7_000L, stat?.totalListenMs ?: 0L)
    }

    @Test
    fun repeatedPlaySongsAccumulatesTotalListenTime() = runTest {
        val s = song(id = 1, fileName = "replay.mp3", duration = 100_000L)

        // Session 1
        tracker.onSongChanged(s)
        fakeElapsed = 10_000L
        tracker.onPlayingChanged(true)
        fakeElapsed = 25_000L
        tracker.onPlayingChanged(false) // 15s listened

        // Session 2
        fakeElapsed = 30_000L
        tracker.onSongChanged(s) // same song
        tracker.onPlayingChanged(true)
        fakeElapsed = 50_000L
        tracker.onPlayingChanged(false) // 20s listened

        val key = TrackStat.identityKey("replay.mp3", 100_000L, "/music/replay.mp3")
        val stat = repo.getByIdentityKey(key)
        assertEquals(35_000L, stat?.totalListenMs ?: 0L)
    }

    @Test
    fun shouldFlushPeriodicallyReturnsTrackingState() {
        assertFalse(tracker.shouldFlushPeriodically())

        tracker.onSongChanged(song())
        tracker.onPlayingChanged(true)
        assertTrue(tracker.shouldFlushPeriodically())

        tracker.onPlayingChanged(false)
        assertFalse(tracker.shouldFlushPeriodically())
    }

    @Test
    fun playPausePlaySameSongPreservesAccumulatedTime() = runTest {
        val s = song(id = 1, fileName = "same.mp3", duration = 60_000L)

        tracker.onSongChanged(s)
        tracker.onPlayingChanged(true)
        fakeElapsed = 3_000L
        tracker.onPlayingChanged(false) // pause after 3s

        fakeElapsed = 10_000L
        tracker.onPlayingChanged(true) // resume
        fakeElapsed = 18_000L
        tracker.onPlayingChanged(false) // pause after 8s more

        val key = TrackStat.identityKey("same.mp3", 60_000L, "/music/same.mp3")
        val stat = repo.getByIdentityKey(key)
        assertEquals(11_000L, stat?.totalListenMs ?: 0L)
    }

    @Test
    fun repeatedSongChangedForSameSongDoesNotResetActiveSegment() = runTest {
        val s = song(id = 1, fileName = "stable.mp3", duration = 90_000L)

        tracker.onSongChanged(s)
        tracker.onPlayingChanged(true)
        fakeElapsed = 4_000L

        tracker.onSongChanged(s.copy(displayName = "Stable Song Updated"))
        fakeElapsed = 9_000L
        tracker.flushPeriodic()

        val key = TrackStat.identityKey("stable.mp3", 90_000L, "/music/stable.mp3")
        val stat = repo.getByIdentityKey(key)
        assertEquals(9_000L, stat?.totalListenMs ?: 0L)
    }

    @Test
    fun statsClearDiscardsPreClearPendingTimeAndPersistsOnlyPostClearTime() = runTest {
        val s = song(id = 1, fileName = "clear.mp3", duration = 90_000L)
        tracker.onSongChanged(s)
        tracker.onPlayingChanged(true)

        fakeElapsed = 5_000L
        repo.clearAll()
        tracker.onStatsCleared()

        fakeElapsed = 8_000L
        tracker.flushPeriodic()

        val key = TrackStat.identityKey("clear.mp3", 90_000L, "/music/clear.mp3")
        assertEquals(3_000L, repo.getByIdentityKey(key)?.totalListenMs ?: 0L)
        assertTrue(tracker.isTracking)
    }

    @Test
    fun staleFlushBeforeClearEventCannotRepopulateRotatedGeneration() = runTest {
        val s = song(id = 1, fileName = "rotated.mp3", duration = 90_000L)
        tracker.onSongChanged(s)
        tracker.onPlayingChanged(true)

        fakeElapsed = 5_000L
        repo.clearAll()

        // The service collector may not have delivered clearEvents yet. The active fence must
        // still reject this delayed periodic flush.
        tracker.flushPeriodic()
        val key = TrackStat.identityKey("rotated.mp3", 90_000L, "/music/rotated.mp3")
        assertEquals(0L, repo.getByIdentityKey(key)?.totalListenMs ?: 0L)

        tracker.onStatsCleared()
        fakeElapsed = 8_000L
        tracker.flushFinal()
        assertEquals(3_000L, repo.getByIdentityKey(key)?.totalListenMs ?: 0L)
    }

    @Test
    fun identityKeyUsesFileNameAndDurationWhenDurationPositive() {
        val key = TrackStat.identityKey("song.mp3", 200_000L, "/ignored/path.mp3")
        assertEquals("song.mp3|200000", key)
    }

    @Test
    fun identityKeyFallsBackToFilePathWhenDurationZero() {
        val key = TrackStat.identityKey("song.mp3", 0L, "/fallback/path.mp3")
        assertEquals("/fallback/path.mp3", key)
    }

    @Test
    fun identityKeyFallsBackToFilePathWhenDurationNegative() {
        val key = TrackStat.identityKey("song.mp3", -1L, "/fallback/neg.mp3")
        assertEquals("/fallback/neg.mp3", key)
    }
}
