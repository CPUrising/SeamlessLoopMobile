package com.cpu.seamlessloopmobile.audio.timer

/**
 * Immutable snapshot of the sleep timer at a point in time.
 */
data class SleepTimerState(
    val isActive: Boolean = false,
    val mode: SleepTimerMode = SleepTimerMode.COUNTDOWN,
    val remainingMillis: Long = 0L,
    val totalMillis: Long = 0L
)
