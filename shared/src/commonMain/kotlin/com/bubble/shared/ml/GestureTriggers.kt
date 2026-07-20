package com.bubble.shared.ml

import com.bubble.shared.signal.HapticGesture
import kotlin.math.sqrt

/** Point de repère normalisé (0..1, y vers le bas — convention MediaPipe/image). */
data class Landmark(val x: Float, val y: Float)

/**
 * Triggers gestuels : géométrie pure sur landmarks normalisés, AUCUNE dépendance ML.
 * Les extracteurs natifs (MediaPipe Android, Vision iOS) ne font que fournir les points ;
 * la décision est ici, identique sur les deux OS et testée sur JVM.
 */
object GestureTriggers {
    /** Distance max entre pointes (index↔index, pouce↔pouce) pour fermer le cœur. */
    const val HEART_TIPS_MAX_DISTANCE = 0.14f

    /** Bouche "en cul de poule" : largeur bouche / largeur visage sous ce ratio. */
    const val KISS_PUCKER_MAX_RATIO = 0.30f

    /** Main portée près de la bouche : distance max d'un point de main au centre des lèvres. */
    const val KISS_HAND_MAX_DISTANCE = 0.22f

    /** Marge verticale : les index doivent dessiner le HAUT du cœur, pas un simple pincement. */
    private const val HEART_VERTICAL_MARGIN = 0.03f

    fun distance(a: Landmark, b: Landmark): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Cœur à deux mains : les deux index se touchent en haut, les deux pouces se touchent
     * en bas, et la pointe (index) est nettement au-dessus de la base (pouces).
     */
    fun isHeart(thumbA: Landmark, indexA: Landmark, thumbB: Landmark, indexB: Landmark): Boolean {
        val indexesTouch = distance(indexA, indexB) < HEART_TIPS_MAX_DISTANCE
        val thumbsTouch = distance(thumbA, thumbB) < HEART_TIPS_MAX_DISTANCE
        val indexesMidY = (indexA.y + indexB.y) / 2f
        val thumbsMidY = (thumbA.y + thumbB.y) / 2f
        val tipOnTop = indexesMidY < thumbsMidY - HEART_VERTICAL_MARGIN
        return indexesTouch && thumbsTouch && tipOnTop
    }

    /**
     * Bisou : lèvres resserrées (pucker) ET une main portée près de la bouche —
     * le geste d'envoyer un baiser, pas juste une moue.
     */
    fun isKiss(
        mouthLeft: Landmark,
        mouthRight: Landmark,
        cheekLeft: Landmark,
        cheekRight: Landmark,
        mouthCenter: Landmark,
        handPoints: List<Landmark>,
    ): Boolean {
        val faceWidth = distance(cheekLeft, cheekRight)
        if (faceWidth <= 0f) return false
        val pucker = distance(mouthLeft, mouthRight) / faceWidth < KISS_PUCKER_MAX_RATIO
        if (!pucker) return false
        return handPoints.any { distance(it, mouthCenter) < KISS_HAND_MAX_DISTANCE }
    }
}

/**
 * Anti-faux-positifs : un geste n'est "formellement identifié" que s'il tient
 * [holdFrames] frames d'inférence consécutives, puis silence de [cooldownMillis]
 * avant de pouvoir re-déclencher (un cœur tenu 10 s = UN signal, pas vingt).
 */
class GestureDebouncer(
    private val holdFrames: Int = 3,
    private val cooldownMillis: Long = 3_000,
) {
    private var candidate: HapticGesture? = null
    private var streak = 0
    private var lastFiredAtMillis = Long.MIN_VALUE / 2

    /** À appeler à chaque frame d'inférence ; retourne le geste confirmé ou null. */
    fun onFrame(detected: HapticGesture?, nowMillis: Long): HapticGesture? {
        if (detected != candidate) {
            candidate = detected
            streak = if (detected == null) 0 else 1
            return null
        }
        if (detected == null) return null
        streak++
        if (streak >= holdFrames) {
            // La série repart toujours de zéro : re-déclencher exige de re-tenir le geste.
            streak = 0
            if (nowMillis - lastFiredAtMillis >= cooldownMillis) {
                lastFiredAtMillis = nowMillis
                return detected
            }
        }
        return null
    }
}
