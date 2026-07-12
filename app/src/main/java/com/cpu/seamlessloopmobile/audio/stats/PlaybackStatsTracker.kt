package com.cpu.seamlessloopmobile.audio.stats

import android.os.SystemClock
import com.cpu.seamlessloopmobile.data.stats.ListenStatsRepository
import com.cpu.seamlessloopmobile.data.stats.ListenStatsWriteFence
import com.cpu.seamlessloopmobile.data.stats.TrackStat
import com.cpu.seamlessloopmobile.model.Song

/**
 * Tracks real wall-clock listened time while [AudioPlayState.PLAYING].
 *
 * Pure enough for unit testing — inject a custom [timeSource] (e.g.
 * a fake that returns controlled values) and a [repository] backed by
 * a temporary file or in-memory store.
 *
 * Lifecycle contract:
 * 1. Call [onSongChanged] when the current song changes (even if paused).
 * 2. Call [onPlayingChanged(true)] when playback starts.
 * 3. Call [onPlayingChanged(false)] when playback pauses/stops/errors.
 * 4. Call [flushPeriodic] from a ~15s timer while playing to persist
 *    accumulated time.
 * 5. Call [flushFinal] when the service is destroyed or the session ends.
 *
 * @param repository  The persistence layer.
 * @param timeSource  A millisecond monotonic clock; default [SystemClock.elapsedRealtime].
 */
class PlaybackStatsTracker(
    private val repository: ListenStatsRepository,
    private val timeSource: () -> Long = { SystemClock.elapsedRealtime() }
) {
    /** True when the tracker is actively accumulating wall-clock time. */
    var isTracking: Boolean = false
        private set

    private var currentStat: TrackStat? = null
    // This is always the exact recording identity derived from the current Song;
    // it must not use a bound presentation identity from allStats.
    private var currentIdentityKey: String? = null
    private var sessionStartElapsed: Long = 0L
    private var accumulatedThisSession: Long = 0L
    private var writeFence: ListenStatsWriteFence? = null

    // --- Public API ----------------------------------------------------------

    /**
     * Notifies the tracker that the current song has changed.
     * Any in-flight accumulation for the previous song is flushed first.
     */
    fun onSongChanged(song: Song) {
        val nextStat = buildTrackStat(song)
        if (nextStat.identityKey == currentIdentityKey) {
            currentStat = nextStat
            return
        }

        val wasTracking = isTracking
        collectActiveSegment()
        persistAccumulatedDelta()
        currentStat = nextStat
        currentIdentityKey = nextStat.identityKey

        if (wasTracking) {
            isTracking = true
            sessionStartElapsed = timeSource()
            writeFence = captureWriteFence()
        }
    }

    /**
     * Notifies the tracker that playback has started or stopped.
     *
     * @param playing `true` when transitioning to PLAYING, `false` otherwise.
     */
    fun onPlayingChanged(playing: Boolean) {
        if (playing) {
            if (currentStat != null && !isTracking) {
                isTracking = true
                sessionStartElapsed = timeSource()
                writeFence = captureWriteFence()
            }
        } else {
            if (isTracking) {
                collectActiveSegment()
                isTracking = false
                persistAccumulatedDelta()
            }
        }
    }

    /**
     * Persists the current session's accumulated time to the repository.
     * Safe to call while tracking is active; does not reset the session clock.
     */
    fun flushPeriodic() {
        collectActiveSegment()
        persistAccumulatedDelta()
    }

    /**
     * Flushes the final accumulated delta and resets the session.
     * Call this when the song ends, the service stops, or the app is destroyed.
     */
    fun flushFinal() {
        collectActiveSegment()
        persistAccumulatedDelta()
        isTracking = false
        currentStat = null
        currentIdentityKey = null
        writeFence = null
    }

    /**
     * Discards unpersisted time after statistics are cleared externally.
     * Active tracking continues from the clear point instead of restoring pre-clear time.
     */
    fun onStatsCleared() {
        accumulatedThisSession = 0L
        writeFence = captureWriteFence()
        if (isTracking) {
            sessionStartElapsed = timeSource()
        }
    }

    /** Returns `true` if periodic flushes should be scheduled (tracking is active). */
    fun shouldFlushPeriodically(): Boolean = isTracking

    // --- Internal helpers ----------------------------------------------------

    private fun collectActiveSegment() {
        if (!isTracking || currentStat == null) return
        val now = timeSource()
        val elapsed = now - sessionStartElapsed
        accumulatedThisSession += elapsed.coerceAtLeast(0L)
        sessionStartElapsed = now
    }

    private fun persistAccumulatedDelta() {
        val delta = accumulatedThisSession
        val stat = currentStat ?: return
        accumulatedThisSession = 0L

        if (delta > 0L) {
            kotlinx.coroutines.runBlocking {
                repository.recordListenDeltaNow(stat, delta, writeFence)
            }
        }
    }

    private fun captureWriteFence(): ListenStatsWriteFence = kotlinx.coroutines.runBlocking {
        repository.captureWriteFence()
    }

    private fun buildTrackStat(song: Song): TrackStat {
        val fileName = song.fileName
        val filePath = song.filePath
        val durationMs = song.duration.coerceAtLeast(0L)
        return TrackStat(
            songId = song.id,
            displayName = song.displayName,
            fileName = fileName,
            artist = song.artist,
            album = song.album,
            coverPath = song.coverPath,
            durationMs = durationMs,
            filePath = filePath,
            // Playback recording remains keyed by the exact wire identity.
            identityKey = TrackStat.identityKey(fileName, durationMs, filePath)
        )
    }
}
