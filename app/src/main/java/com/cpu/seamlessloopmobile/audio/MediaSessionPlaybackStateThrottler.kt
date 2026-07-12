package com.cpu.seamlessloopmobile.audio

import android.support.v4.media.session.PlaybackStateCompat

/**
 * Tracks the last-dispatched playback state and decides whether a new dispatch
 * should be sent to [android.support.v4.media.session.MediaSessionCompat].
 *
 * This prevents flooding system UI (notification / lock screen) with redundant
 * or near-identical progress updates.
 *
 * @param minUpdateIntervalMs    Minimum elapsed wall-clock time between dispatches.
 * @param positionDriftThresholdMs Allowed drift before a forced dispatch, even if
 *                                 the minimum interval has not elapsed.
 */
internal class MediaSessionPlaybackStateThrottler(
    private val minUpdateIntervalMs: Long = 1000L,
    private val positionDriftThresholdMs: Long = 1500L
) {
    private var hasLastDispatch = false
    private var lastDispatchElapsedMs: Long = 0L
    private var lastState: Int = PlaybackStateCompat.STATE_NONE
    private var lastPositionMs: Long = 0L
    private var lastSpeed: Float = 0f
    private var lastControlFingerprint: Int = 0

    /**
     * Returns `true` if the proposed playback-state update should be dispatched
     * to the media session.
     *
     * Dispatch triggers:
     * 1. No prior dispatch has been recorded.
     * 2. The core playback state ([PlaybackStateCompat] state) has changed.
     * 3. Playback speed has changed.
     * 4. The control fingerprint has changed (seeks, mode toggles, etc.).
     * 5. Enough wall-clock time has passed since the last dispatch.
     * 6. The expected position has drifted beyond [positionDriftThresholdMs].
     *
     * @param state               The proposed [PlaybackStateCompat] state value.
     * @param positionMs          The proposed playback position in milliseconds.
     * @param speed               The proposed playback speed.
     * @param controlFingerprint  Opaque token that changes when user-control
     *                            parameters (e.g. play-mode, AB-mode, loop-limit)
     *                            are altered.
     * @param nowElapsedMs        Monotonic elapsed time (e.g. [System.nanoTime]
     *                            converted to ms, or [SystemClock.elapsedRealtime]).
     */
    fun shouldDispatch(
        state: Int,
        positionMs: Long,
        speed: Float,
        controlFingerprint: Int,
        nowElapsedMs: Long
    ): Boolean {
        // 1. First dispatch
        if (!hasLastDispatch) return true

        // 2. Core state change
        if (state != lastState) return true

        // 3. Speed change
        if (speed != lastSpeed) return true

        // 4. Control fingerprint change (seeks, mode toggles)
        if (controlFingerprint != lastControlFingerprint) return true

        // 5. Minimum interval has passed
        val elapsedSinceLastDispatch = (nowElapsedMs - lastDispatchElapsedMs).coerceAtLeast(0L)
        if (elapsedSinceLastDispatch >= minUpdateIntervalMs) return true

        // 6. Position drift exceeds threshold
        val expectedPositionMs = if (lastState == PlaybackStateCompat.STATE_PLAYING && lastSpeed > 0f) {
            lastPositionMs + (elapsedSinceLastDispatch * lastSpeed).toLong()
        } else {
            lastPositionMs
        }
        val drift = kotlin.math.abs(positionMs - expectedPositionMs)
        if (drift >= positionDriftThresholdMs) return true

        return false
    }

    /**
     * Records that a dispatch was just performed. Must be called immediately
     * after dispatching so that subsequent [shouldDispatch] calls have accurate
     * reference values.
     */
    fun recordDispatch(
        state: Int,
        positionMs: Long,
        speed: Float,
        controlFingerprint: Int,
        nowElapsedMs: Long
    ) {
        hasLastDispatch = true
        lastDispatchElapsedMs = nowElapsedMs
        lastState = state
        lastPositionMs = positionMs
        lastSpeed = speed
        lastControlFingerprint = controlFingerprint
    }
}
