package com.bubble.shared.haptics

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Traduction d'une [HapticScore] vers le format `VibrationEffect.createWaveform`
 * (segments alternés durée/amplitude). En commonMain pour être testable sur JVM ;
 * consommée par l'implémentation androidMain.
 */
object HapticWaveform {
    /** Résultat : listes parallèles (durées ms, amplitudes 0..maxAmplitude). */
    data class Waveform(val timings: List<Long>, val amplitudes: List<Int>)

    /**
     * Les impulsions longues (> [continuousThresholdMillis]) sont subdivisées avec une
     * enveloppe sinusoïdale (montée/descente douce) pour imiter la nappe continue de
     * CoreHaptics — un moteur Android ne sachant faire que des segments d'amplitude fixe.
     */
    fun render(
        score: HapticScore,
        maxAmplitude: Int = 255,
        continuousThresholdMillis: Long = 150,
        fadeStepMillis: Long = 80,
    ): Waveform {
        val timings = mutableListOf<Long>()
        val amplitudes = mutableListOf<Int>()
        var cursor = 0L

        for (pulse in score.pulses.sortedBy { it.atMillis }) {
            val gap = pulse.atMillis - cursor
            if (gap > 0) {
                timings += gap
                amplitudes += 0
            }
            if (pulse.durationMillis > continuousThresholdMillis) {
                val steps = ((pulse.durationMillis + fadeStepMillis - 1) / fadeStepMillis)
                    .coerceAtLeast(3)
                    .toInt()
                val stepDuration = pulse.durationMillis / steps
                repeat(steps) { i ->
                    val envelope = sin(PI * (i + 0.5) / steps).toFloat()
                    timings += stepDuration
                    amplitudes += (pulse.intensity * envelope * maxAmplitude).roundToInt().coerceIn(0, maxAmplitude)
                }
            } else {
                timings += pulse.durationMillis
                amplitudes += (pulse.intensity * maxAmplitude).roundToInt().coerceIn(0, maxAmplitude)
            }
            cursor = pulse.atMillis + pulse.durationMillis
        }
        return Waveform(timings, amplitudes)
    }
}
