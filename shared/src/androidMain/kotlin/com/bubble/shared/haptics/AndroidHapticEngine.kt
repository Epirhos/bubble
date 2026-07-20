package com.bubble.shared.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Traduction Android des [HapticScores] via `VibrationEffect.createWaveform`.
 *
 * La partition (timings/enveloppes) vient de commonMain [HapticWaveform] ; ce fichier ne
 * fait que le pont matériel. Intensité 0..1 → amplitude 1..255. Sur les moteurs sans
 * contrôle d'amplitude, repli sur un pattern on/off aux mêmes timings (la "musique"
 * reste la même, seule la nuance disparaît).
 */
class AndroidHapticEngine(context: Context) : HapticEngine {

    private val vibrator: Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    override fun playKiss() = play(HapticScores.KISS)

    override fun playHeartbeat() = play(HapticScores.HEARTBEAT)

    override fun playAuraPulse() = play(HapticScores.AURA_PULSE)

    override fun playRadarPulse(intensity: Float) {
        if (!vibrator.hasVibrator()) return
        val amplitude = (intensity.coerceIn(0f, 1f) * 255).toInt().coerceIn(1, 255)
        val effect = if (vibrator.hasAmplitudeControl()) {
            VibrationEffect.createOneShot(RADAR_PULSE_MILLIS, amplitude)
        } else {
            VibrationEffect.createOneShot(RADAR_PULSE_MILLIS, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        vibrator.vibrate(effect)
    }

    private fun play(score: HapticScore) {
        if (!vibrator.hasVibrator()) return
        val waveform = HapticWaveform.render(score)
        val effect = if (vibrator.hasAmplitudeControl()) {
            VibrationEffect.createWaveform(
                waveform.timings.toLongArray(),
                waveform.amplitudes.toIntArray(),
                NO_REPEAT,
            )
        } else {
            // Format on/off : commence par une durée "off" (0 ici car la partition démarre à t=0).
            val onOffTimings = LongArray(waveform.timings.size + 1)
            waveform.timings.forEachIndexed { i, t -> onOffTimings[i + 1] = t }
            VibrationEffect.createWaveform(onOffTimings, NO_REPEAT)
        }
        vibrator.vibrate(effect)
    }

    private companion object {
        const val NO_REPEAT = -1
        const val RADAR_PULSE_MILLIS = 40L
    }
}
