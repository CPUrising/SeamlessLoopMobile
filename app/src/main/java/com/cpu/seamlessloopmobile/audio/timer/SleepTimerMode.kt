package com.cpu.seamlessloopmobile.audio.timer

/**
 * Defines how the sleep timer behaves once the trigger condition is met.
 */
enum class SleepTimerMode {
    /** Countdown expires and playback stops immediately. */
    COUNTDOWN,

    /** Stops after the currently playing track finishes. */
    FINISH_CURRENT,

    /** Stops after the entire playlist reaches its end. */
    FINISH_PLAYLIST
}
