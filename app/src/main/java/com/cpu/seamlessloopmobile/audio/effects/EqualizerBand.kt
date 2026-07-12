package com.cpu.seamlessloopmobile.audio.effects

/**
 * Represents a single equalizer band with a center frequency and gain level.
 *
 * @param index       Zero-based Android Equalizer band index.
 * @param frequencyHz Center frequency of the band in Hertz.
 * @param gainMb      Gain in millibels (mB). 0 mB means no adjustment.
 */
data class EqualizerBand(
    val index: Int,
    val frequencyHz: Int,
    val gainMb: Int
)
