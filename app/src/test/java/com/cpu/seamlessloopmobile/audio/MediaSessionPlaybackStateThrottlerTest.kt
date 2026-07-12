package com.cpu.seamlessloopmobile.audio

import android.support.v4.media.session.PlaybackStateCompat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSessionPlaybackStateThrottlerTest {

    private val throttler = MediaSessionPlaybackStateThrottler(
        minUpdateIntervalMs = 1000L,
        positionDriftThresholdMs = 1500L
    )

    @Test
    fun firstDispatchAlwaysAllowed() {
        assertTrue(
            throttler.shouldDispatch(
                state = PlaybackStateCompat.STATE_PLAYING,
                positionMs = 0L,
                speed = 1.0f,
                controlFingerprint = 0,
                nowElapsedMs = 100L
            )
        )
    }

    @Test
    fun stateChangeTriggersDispatch() {
        throttler.recordDispatch(
            state = PlaybackStateCompat.STATE_PLAYING,
            positionMs = 0L,
            speed = 1.0f,
            controlFingerprint = 0,
            nowElapsedMs = 100L
        )

        assertTrue(
            throttler.shouldDispatch(
                state = PlaybackStateCompat.STATE_PAUSED,
                positionMs = 500L,
                speed = 0f,
                controlFingerprint = 0,
                nowElapsedMs = 200L
            )
        )
    }

    @Test
    fun speedChangeTriggersDispatch() {
        throttler.recordDispatch(
            state = PlaybackStateCompat.STATE_PLAYING,
            positionMs = 0L,
            speed = 1.0f,
            controlFingerprint = 0,
            nowElapsedMs = 100L
        )

        assertTrue(
            throttler.shouldDispatch(
                state = PlaybackStateCompat.STATE_PLAYING,
                positionMs = 10L,
                speed = 2.0f,
                controlFingerprint = 0,
                nowElapsedMs = 200L
            )
        )
    }

    @Test
    fun fingerprintChangeTriggersDispatch() {
        throttler.recordDispatch(
            state = PlaybackStateCompat.STATE_PLAYING,
            positionMs = 0L,
            speed = 1.0f,
            controlFingerprint = 0,
            nowElapsedMs = 100L
        )

        assertTrue(
            throttler.shouldDispatch(
                state = PlaybackStateCompat.STATE_PLAYING,
                positionMs = 10L,
                speed = 1.0f,
                controlFingerprint = 42,
                nowElapsedMs = 200L
            )
        )
    }

    @Test
    fun minIntervalElapsedTriggersDispatch() {
        throttler.recordDispatch(
            state = PlaybackStateCompat.STATE_PLAYING,
            positionMs = 0L,
            speed = 1.0f,
            controlFingerprint = 0,
            nowElapsedMs = 1000L
        )

        assertTrue(
            throttler.shouldDispatch(
                state = PlaybackStateCompat.STATE_PLAYING,
                positionMs = 10L,
                speed = 1.0f,
                controlFingerprint = 0,
                nowElapsedMs = 3000L // > 1000ms elapsed
            )
        )
    }

    @Test
    fun withinMinIntervalAndNoChangeIsSuppressed() {
        throttler.recordDispatch(
            state = PlaybackStateCompat.STATE_PLAYING,
            positionMs = 0L,
            speed = 1.0f,
            controlFingerprint = 0,
            nowElapsedMs = 1000L
        )

        assertFalse(
            throttler.shouldDispatch(
                state = PlaybackStateCompat.STATE_PLAYING,
                positionMs = 100L,
                speed = 1.0f,
                controlFingerprint = 0,
                nowElapsedMs = 1100L // only 100ms elapsed, drift well below 1500ms
            )
        )
    }

    @Test
    fun positionDriftExceedsThresholdForcesDispatch() {
        throttler.recordDispatch(
            state = PlaybackStateCompat.STATE_PLAYING,
            positionMs = 0L,
            speed = 1.0f,
            controlFingerprint = 0,
            nowElapsedMs = 1000L
        )

        // Within min interval, but drift >= 1500ms threshold
        assertTrue(
            throttler.shouldDispatch(
                state = PlaybackStateCompat.STATE_PLAYING,
                positionMs = 2000L,
                speed = 1.0f,
                controlFingerprint = 0,
                nowElapsedMs = 1100L
            )
        )
    }

    @Test
    fun recordDispatchUpdatesLastState() {
        throttler.recordDispatch(
            state = PlaybackStateCompat.STATE_PLAYING,
            positionMs = 100L,
            speed = 1.0f,
            controlFingerprint = 1,
            nowElapsedMs = 500L
        )

        // Same state, same fingerprint, same speed: should be suppressed
        assertFalse(
            throttler.shouldDispatch(
                state = PlaybackStateCompat.STATE_PLAYING,
                positionMs = 200L,
                speed = 1.0f,
                controlFingerprint = 1,
                nowElapsedMs = 600L
            )
        )
    }

    @Test
    fun customIntervalAndThreshold() {
        val fastThrottler = MediaSessionPlaybackStateThrottler(
            minUpdateIntervalMs = 50L,
            positionDriftThresholdMs = 200L
        )

        fastThrottler.recordDispatch(
            state = PlaybackStateCompat.STATE_PLAYING,
            positionMs = 0L,
            speed = 1.0f,
            controlFingerprint = 0,
            nowElapsedMs = 1000L
        )

        // 60ms later: above min interval -> allowed
        assertTrue(
            fastThrottler.shouldDispatch(
                state = PlaybackStateCompat.STATE_PLAYING,
                positionMs = 10L,
                speed = 1.0f,
                controlFingerprint = 0,
                nowElapsedMs = 1060L
            )
        )
    }
}
