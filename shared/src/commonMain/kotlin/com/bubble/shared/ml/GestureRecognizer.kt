package com.bubble.shared.ml

import com.bubble.shared.signal.HapticGesture
import kotlinx.coroutines.flow.Flow

/** Événement produit par la vision locale pendant une session Live. */
sealed interface VisionEvent {
    /** Geste reconnu (bisou, cœur avec les mains) → à transformer en SignalPayload.Haptic. */
    data class GestureDetected(val gesture: HapticGesture) : VisionEvent

    /** Alimente le FaceWatchdog (reset du compte à rebours 120 s). */
    data object FaceAppeared : VisionEvent

    data object FaceLost : VisionEvent
}

/**
 * Contrat de l'IA on-device (MediaPipe côté Android, Vision/CoreML côté iOS).
 *
 * Invariants :
 *  - Les frames caméra ne quittent JAMAIS ce composant : seuls des [VisionEvent] en sortent.
 *  - Actif uniquement en état Live ; [stop] doit libérer la caméra immédiatement.
 *
 * Le cahier des charges parle d'un callback `onGestureDetected(gestureType)` : il est réalisé
 * ici par le flux froid [events] (style du projet — voir /brain/PROMPTS.md), que le
 * BubbleStateManager collecte.
 */
interface GestureRecognizer {
    val events: Flow<VisionEvent>

    fun start()

    fun stop()
}
