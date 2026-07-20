package com.bubble.shared.haptics

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreHaptics.CHHapticEngine
import platform.CoreHaptics.CHHapticEvent
import platform.CoreHaptics.CHHapticEventParameter
import platform.CoreHaptics.CHHapticEventParameterIDHapticIntensity
import platform.CoreHaptics.CHHapticEventParameterIDHapticSharpness
import platform.CoreHaptics.CHHapticEventTypeHapticContinuous
import platform.CoreHaptics.CHHapticEventTypeHapticTransient
import platform.CoreHaptics.CHHapticPattern
import platform.Foundation.NSError

/**
 * Traduction iOS des [HapticScores] via CoreHaptics.
 *
 * Même partition temporelle que Android ([HapticWaveform] n'est pas utilisé ici :
 * CoreHaptics consomme directement intensité/sharpness normalisées 0..1, qui sont le
 * vocabulaire natif de [HapticPulse]). Impulsion courte (≤ 100 ms) → événement transient ;
 * longue → événement continuous (nappe), CoreHaptics gérant nativement l'enveloppe douce.
 *
 * NOTE : compile uniquement sur macOS (cible iosArm64/iosSimulatorArm64).
 */
@OptIn(ExperimentalForeignApi::class)
class IosHapticEngine : HapticEngine {

    private var engine: CHHapticEngine? = null

    override fun playKiss() = play(HapticScores.KISS)

    override fun playHeartbeat() = play(HapticScores.HEARTBEAT)

    override fun playAuraPulse() = play(HapticScores.AURA_PULSE)

    override fun playRadarPulse(intensity: Float) {
        // Battement bref d'intensité paramétrable (Radar Haptique) : un transient CoreHaptics.
        val clamped = intensity.coerceIn(0f, 1f)
        play(
            HapticScore(
                listOf(HapticPulse(atMillis = 0, durationMillis = 40, intensity = clamped, sharpness = 0.5f)),
            ),
        )
    }

    private fun play(score: HapticScore) {
        val engine = ensureEngine() ?: return
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            val events = score.pulses.map { it.toHapticEvent() }
            val pattern = CHHapticPattern(events = events, parameters = emptyList<Any>(), error = error.ptr)
            if (error.value != null) return
            val player = engine.createPlayerWithPattern(pattern, error.ptr) ?: return
            if (error.value != null) return
            player.startAtTime(0.0, error.ptr)
        }
    }

    private fun HapticPulse.toHapticEvent(): CHHapticEvent {
        val parameters = listOf(
            CHHapticEventParameter(parameterID = CHHapticEventParameterIDHapticIntensity, value = intensity),
            CHHapticEventParameter(parameterID = CHHapticEventParameterIDHapticSharpness, value = sharpness),
        )
        val relativeTime = atMillis / 1000.0
        return if (durationMillis <= TRANSIENT_THRESHOLD_MILLIS) {
            CHHapticEvent(
                eventType = CHHapticEventTypeHapticTransient,
                parameters = parameters,
                relativeTime = relativeTime,
            )
        } else {
            CHHapticEvent(
                eventType = CHHapticEventTypeHapticContinuous,
                parameters = parameters,
                relativeTime = relativeTime,
                duration = durationMillis / 1000.0,
            )
        }
    }

    private fun ensureEngine(): CHHapticEngine? {
        if (CHHapticEngine.capabilitiesForHardware().supportsHaptics.not()) return null
        engine?.let { return it }
        return memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            val created = CHHapticEngine(andReturnError = error.ptr)
            if (error.value != null) return null
            created.startAndReturnError(error.ptr)
            if (error.value != null) return null
            engine = created
            created
        }
    }

    private companion object {
        const val TRANSIENT_THRESHOLD_MILLIS = 100L
    }
}
