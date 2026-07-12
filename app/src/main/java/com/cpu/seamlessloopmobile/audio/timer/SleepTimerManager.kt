package com.cpu.seamlessloopmobile.audio.timer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Manages sleep-timer countdown logic independently of [android.content.Context]
 * or [com.cpu.seamlessloopmobile.data.SettingsManager].
 *
 * @param scope      CoroutineScope used for the internal countdown job.
 * @param onTimerExpired Callback invoked when the timer naturally expires
 *                       (COUNTDOWN reaches zero, or FINISH_CURRENT/FINISH_PLAYLIST
 *                       triggers via [shouldStopOnTrackEnd]).
 */
class SleepTimerManager(
    private val scope: CoroutineScope,
    private val onTimerExpired: () -> Unit,
    private val millisPerMinute: Long = 60_000L,
    private val tickIntervalMs: Long = 250L
) {
    private val _timerState = MutableStateFlow(SleepTimerState())
    val timerState: StateFlow<SleepTimerState> = _timerState.asStateFlow()

    private var countdownJob: Job? = null

    /**
     * Starts a countdown-based sleep timer.
     * Clamps [minutes] to be positive (values <= 0 are treated as no-op).
     */
    fun startCountdown(minutes: Int) {
        if (minutes <= 0) return
        cancel()

        val totalMs = minutes.toLong() * millisPerMinute.coerceAtLeast(1L)
        _timerState.value = SleepTimerState(
            isActive = true,
            mode = SleepTimerMode.COUNTDOWN,
            remainingMillis = totalMs,
            totalMillis = totalMs
        )

        countdownJob = scope.launch {
            var remaining = totalMs
            while (isActive && remaining > 0L) {
                val delayMillis = minOf(tickIntervalMs.coerceAtLeast(1L), remaining)
                delay(delayMillis)
                remaining -= delayMillis
                _timerState.value = _timerState.value.copy(remainingMillis = remaining)
            }
            if (isActive && remaining <= 0L) {
                _timerState.value = SleepTimerState()
                onTimerExpired()
            }
        }
    }

    /**
     * Starts a sleep timer that expires after the current track finishes.
     * Remaining time is set to a sentinel value of -1 to indicate indefinite
     * track-bound mode; [shouldStopOnTrackEnd] handles the actual trigger.
     */
    fun startFinishCurrent() {
        cancel()
        _timerState.value = SleepTimerState(
            isActive = true,
            mode = SleepTimerMode.FINISH_CURRENT,
            remainingMillis = -1L,
            totalMillis = -1L
        )
    }

    /**
     * Starts a sleep timer that expires after the playlist finishes.
     * Remaining time is set to a sentinel value of -1.
     */
    fun startFinishPlaylist() {
        cancel()
        _timerState.value = SleepTimerState(
            isActive = true,
            mode = SleepTimerMode.FINISH_PLAYLIST,
            remainingMillis = -1L,
            totalMillis = -1L
        )
    }

    /** Cancels any active sleep timer and resets state. */
    fun cancel() {
        countdownJob?.cancel()
        countdownJob = null
        _timerState.value = SleepTimerState()
    }

    /**
     * Called when a track ends or the playlist advances.
     * Returns `true` if the sleep timer should stop playback.
     *
     * @param isLastInPlaylist Whether the current track is the last one
     *                         in the active playlist/queue.
     */
    fun shouldStopOnTrackEnd(isLastInPlaylist: Boolean): Boolean {
        val state = _timerState.value
        if (!state.isActive) return false
        return when (state.mode) {
            SleepTimerMode.COUNTDOWN -> {
                // COUNTDOWN mode stopped playback when the countdown reached zero;
                // this guard is a safety net in case the timer expired mid-track.
                if (state.remainingMillis <= 0L) {
                    cancel()
                    onTimerExpired()
                    true
                } else false
            }
            SleepTimerMode.FINISH_CURRENT -> {
                cancel()
                onTimerExpired()
                true
            }
            SleepTimerMode.FINISH_PLAYLIST -> {
                if (isLastInPlaylist) {
                    cancel()
                    onTimerExpired()
                    true
                } else false
            }
        }
    }

    /**
     * Formats the remaining time as "MM:SS" or "--:--" for track/playlist-bound modes.
     */
    fun formatRemainingTime(): String {
        val state = _timerState.value
        if (!state.isActive) return ""
        return when (state.mode) {
            SleepTimerMode.COUNTDOWN -> {
                val totalSec = state.remainingMillis / 1000L
                val min = totalSec / 60L
                val sec = totalSec % 60L
                "%02d:%02d".format(min, sec)
            }
            SleepTimerMode.FINISH_CURRENT,
            SleepTimerMode.FINISH_PLAYLIST -> "--:--"
        }
    }
}
