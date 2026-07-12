package com.cpu.seamlessloopmobile.audio.effects

/**
 * Desired audio-effects configuration.
 *
 * @param speed              Playback speed multiplier (1.0 = normal).
 * @param pitch              Playback pitch multiplier (1.0 = normal).
 * @param equalizerEnabled   Whether the built-in equalizer is active.
 * @param equalizerPresetId  Stable identifier for a preset curve.
 * @param customBandLevelsMb Per-band gain levels in millibels, ordered by band index.
 * @param loudnessGainMb     Loudness-enhancer gain in millibels.
 */
data class AudioEffectsConfig(
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f,
    val equalizerEnabled: Boolean = false,
    val equalizerPresetId: String = PRESET_FLAT,
    val customBandLevelsMb: List<Int> = emptyList(),
    val loudnessGainMb: Int = 0
) {
    companion object {
        const val PRESET_FLAT = "flat"
        const val PRESET_CUSTOM = "custom"
        const val PRESET_BASS_BOOST = "bass_boost"
        const val PRESET_TREBLE_BOOST = "treble_boost"
        const val PRESET_ROCK = "rock"
        const val PRESET_POP = "pop"
        const val PRESET_CLASSICAL = "classical"
        const val PRESET_JAZZ = "jazz"
        const val PRESET_VOCAL_BOOST = "vocal_boost"
    }
}
