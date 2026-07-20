package com.bubble.shared.core

import com.bubble.shared.signal.HapticGesture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/**
 * Écho Haptique : geste reçu pendant que l'appareil était verrouillé/inactif,
 * stocké pour être rejoué au prochain déverrouillage.
 */
@Serializable
data class HapticEcho(
    val gesture: HapticGesture,
    /** Horodatage de RÉCEPTION locale (epoch millis), pas d'émission. */
    val receivedAtEpochMillis: Long,
)

/**
 * File des échos en attente. Bornée et dédupliquée : une rafale du même geste dans la
 * fenêtre [dedupWindowMillis] ne produit qu'une impulsion au réveil (Calm Technology —
 * le replay ne doit jamais devenir une mitraillette haptique).
 *
 * Aucun accusé de réception n'est émis vers l'émetteur, ni à l'enqueue ni au drain.
 */
class EchoQueue(
    private val maxSize: Int = 8,
    private val dedupWindowMillis: Long = 2_000,
) {
    private val _pending = MutableStateFlow<List<HapticEcho>>(emptyList())
    val pending: StateFlow<List<HapticEcho>> = _pending.asStateFlow()

    fun enqueue(echo: HapticEcho) {
        _pending.value = _pending.value
            .let { queue ->
                val isDuplicate = queue.lastOrNull()?.let {
                    it.gesture == echo.gesture &&
                        echo.receivedAtEpochMillis - it.receivedAtEpochMillis < dedupWindowMillis
                } ?: false
                if (isDuplicate) queue else queue + echo
            }
            .takeLast(maxSize)
    }

    /** Vide la file et retourne les échos à rejouer, dans l'ordre de réception. */
    fun drain(): List<HapticEcho> {
        val drained = _pending.value
        _pending.value = emptyList()
        return drained
    }

    fun clear() {
        _pending.value = emptyList()
    }
}
