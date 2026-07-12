package com.cpu.seamlessloopmobile.audio.effects

import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for controlling audio effects (speed, pitch, equalizer, loudness).
 * Implementations may be real (backed by Android audio effects) or no-op.
 */
interface IAudioEffectsController {
    /** Observable state of effects subsystem. */
    val state: StateFlow<AudioEffectsState>

    /**
     * Updates the desired configuration and returns the resulting state.
     * The implementation may clamp or ignore unsupported values.
     */
    fun updateConfig(config: AudioEffectsConfig): AudioEffectsState

    /**
     * Attaches or detaches the controller to/from a given audio session.
     * Returns the resulting state after attachment.
     */
    fun attachAudioSessionId(sessionId: Int?): AudioEffectsState

    /** Releases all held resources (audio effect sessions, etc.). After this the controller is unusable. */
    fun release()
}
