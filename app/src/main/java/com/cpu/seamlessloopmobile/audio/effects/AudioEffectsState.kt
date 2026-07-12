package com.cpu.seamlessloopmobile.audio.effects

/**
 * Observable snapshot of the audio-effects subsystem.
 *
 * @param appliedConfig           The config currently applied to hardware or stored in the controller.
 * @param equalizerAvailable      Whether a hardware equalizer is present on this device.
 * @param loudnessEnhancerAvailable Whether a hardware loudness enhancer is present.
 * @param availableBands          List of equalizer bands the hardware supports.
 * @param attachedAudioSessionId  The audio session ID the controller is bound to, or null.
 */
data class AudioEffectsState(
    val appliedConfig: AudioEffectsConfig = AudioEffectsConfig(),
    val equalizerAvailable: Boolean = false,
    val loudnessEnhancerAvailable: Boolean = false,
    val availableBands: List<EqualizerBand> = emptyList(),
    val attachedAudioSessionId: Int? = null
)
