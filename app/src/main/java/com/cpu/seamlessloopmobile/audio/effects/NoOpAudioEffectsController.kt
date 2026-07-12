package com.cpu.seamlessloopmobile.audio.effects

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * No-op implementation of [IAudioEffectsController] that never claims hardware
 * capabilities. It stores the last config and session ID in state but does not
 * interact with Android audio effects.
 */
class NoOpAudioEffectsController : IAudioEffectsController {

    private val _state = MutableStateFlow(AudioEffectsState())
    override val state: StateFlow<AudioEffectsState> = _state.asStateFlow()

    override fun updateConfig(config: AudioEffectsConfig): AudioEffectsState {
        val updated = _state.value.copy(appliedConfig = config)
        _state.value = updated
        return updated
    }

    override fun attachAudioSessionId(sessionId: Int?): AudioEffectsState {
        val updated = _state.value.copy(attachedAudioSessionId = sessionId)
        _state.value = updated
        return updated
    }

    override fun release() {
        _state.value = AudioEffectsState()
    }
}
