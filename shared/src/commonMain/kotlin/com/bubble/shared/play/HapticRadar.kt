package com.bubble.shared.play

import com.bubble.shared.haptics.HapticEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Mapping distance → retour haptique du Radar (pur, testable). Plus le doigt approche du
 * point caché, plus la pulsation est FORTE et RAPPROCHÉE (métaphore du battement cardiaque).
 */
object RadarFeedback {
    /** Au-delà de cette distance normalisée, on ne sent rien (le doigt est "froid"). */
    const val RANGE = 0.6f

    data class Pulse(val intensity: Float, val cadenceMillis: Long)

    fun forDistance(distance: Float): Pulse? {
        if (distance > RANGE) return null
        val proximity = (1f - distance / RANGE).coerceIn(0f, 1f) // 0 loin … 1 sur la cible
        val intensity = (0.25f + 0.75f * proximity)
        val cadence = (500L - (380L * proximity).toLong()).coerceIn(120L, 500L)
        return Pulse(intensity, cadence)
    }

    fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }
}

/**
 * Moteur du Radar Haptique. DÉCOUPLE le thread UI du moteur haptique :
 *  - l'UI n'appelle que [onFingerMove] (écriture d'un StateFlow, coût négligeable, jamais bloquant) ;
 *  - une unique coroutine sur [scope] lit la DERNIÈRE position connue, calcule la distance au
 *    point caché et pilote le [HapticEngine] à cadence contrôlée (jamais un vibrate par frame).
 *
 * Ainsi le déplacement du doigt (fréquent) ne déclenche jamais directement l'haptique : la
 * boucle consomme l'état à son propre rythme (120–500 ms selon la proximité), le thread UI
 * reste libre. Un seul appel `playRadarPulse` par intervalle.
 */
class HapticRadarEngine(
    private val scope: CoroutineScope,
    private val hapticEngine: HapticEngine?,
) {
    private val fingerPosition = MutableStateFlow<Pair<Float, Float>?>(null)
    private var target: Pair<Float, Float>? = null
    private var loop: Job? = null

    val isActive: Boolean get() = loop?.isActive == true

    fun start(targetX: Float, targetY: Float) {
        target = targetX to targetY
        fingerPosition.value = null
        loop?.cancel()
        loop = scope.launch {
            while (isActive) {
                val finger = fingerPosition.value
                val hidden = target
                val pulse = if (finger != null && hidden != null) {
                    RadarFeedback.forDistance(
                        RadarFeedback.distance(finger.first, finger.second, hidden.first, hidden.second),
                    )
                } else {
                    null
                }
                if (pulse != null) {
                    hapticEngine?.playRadarPulse(pulse.intensity)
                    delay(pulse.cadenceMillis)
                } else {
                    delay(IDLE_POLL_MILLIS) // doigt froid ou absent : sondage lent
                }
            }
        }
    }

    /** Appelé par l'UI à chaque déplacement du doigt (coordonnées normalisées 0..1). */
    fun onFingerMove(x: Float, y: Float) {
        fingerPosition.value = x to y
    }

    fun stop() {
        loop?.cancel()
        loop = null
        target = null
        fingerPosition.value = null
    }

    private companion object {
        const val IDLE_POLL_MILLIS = 140L
    }
}
