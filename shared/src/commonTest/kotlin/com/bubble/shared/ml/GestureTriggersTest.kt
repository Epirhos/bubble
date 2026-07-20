package com.bubble.shared.ml

import com.bubble.shared.signal.HapticGesture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GestureTriggersTest {
    @Test
    fun heartShapeIsDetected() {
        assertTrue(
            GestureTriggers.isHeart(
                thumbA = Landmark(0.45f, 0.55f), indexA = Landmark(0.49f, 0.30f),
                thumbB = Landmark(0.55f, 0.55f), indexB = Landmark(0.51f, 0.30f),
            ),
        )
    }

    @Test
    fun heartRejectedWhenThumbsApartOrTipsBelow() {
        // Pouces trop écartés.
        assertFalse(
            GestureTriggers.isHeart(
                thumbA = Landmark(0.30f, 0.55f), indexA = Landmark(0.49f, 0.30f),
                thumbB = Landmark(0.70f, 0.55f), indexB = Landmark(0.51f, 0.30f),
            ),
        )
        // Index sous les pouces : simple pincement, pas un cœur.
        assertFalse(
            GestureTriggers.isHeart(
                thumbA = Landmark(0.49f, 0.30f), indexA = Landmark(0.49f, 0.55f),
                thumbB = Landmark(0.51f, 0.30f), indexB = Landmark(0.51f, 0.55f),
            ),
        )
    }

    @Test
    fun kissNeedsPuckerAndHandNearMouth() {
        val cheekL = Landmark(0.30f, 0.50f)
        val cheekR = Landmark(0.70f, 0.50f)
        val mouthCenter = Landmark(0.50f, 0.62f)
        val pucker = Pair(Landmark(0.455f, 0.60f), Landmark(0.545f, 0.60f)) // ratio 0.225
        val wide = Pair(Landmark(0.40f, 0.60f), Landmark(0.60f, 0.60f)) // ratio 0.5

        assertTrue(
            GestureTriggers.isKiss(pucker.first, pucker.second, cheekL, cheekR, mouthCenter, listOf(Landmark(0.55f, 0.70f))),
        )
        // Pas de main près de la bouche.
        assertFalse(
            GestureTriggers.isKiss(pucker.first, pucker.second, cheekL, cheekR, mouthCenter, listOf(Landmark(0.10f, 0.10f))),
        )
        // Bouche détendue.
        assertFalse(
            GestureTriggers.isKiss(wide.first, wide.second, cheekL, cheekR, mouthCenter, listOf(Landmark(0.55f, 0.70f))),
        )
    }

    @Test
    fun debouncerRequiresHoldThenCooldown() {
        val debouncer = GestureDebouncer(holdFrames = 3, cooldownMillis = 3_000)

        assertNull(debouncer.onFrame(HapticGesture.HEART, 0))
        assertNull(debouncer.onFrame(HapticGesture.HEART, 150))
        assertEquals(HapticGesture.HEART, debouncer.onFrame(HapticGesture.HEART, 300), "confirmé au 3e frame")

        // Le geste continue : silence pendant le cooldown.
        assertNull(debouncer.onFrame(HapticGesture.HEART, 450))
        assertNull(debouncer.onFrame(HapticGesture.HEART, 600))
        assertNull(debouncer.onFrame(HapticGesture.HEART, 750))

        // Après le cooldown, un geste tenu peut re-déclencher.
        assertNull(debouncer.onFrame(HapticGesture.HEART, 3_400))
        assertNull(debouncer.onFrame(HapticGesture.HEART, 3_550))
        assertEquals(HapticGesture.HEART, debouncer.onFrame(HapticGesture.HEART, 3_700))
    }

    @Test
    fun debouncerResetsWhenGestureChangesOrDisappears() {
        val debouncer = GestureDebouncer(holdFrames = 2, cooldownMillis = 0)
        assertNull(debouncer.onFrame(HapticGesture.HEART, 0))
        assertNull(debouncer.onFrame(null, 100)) // perte → la série repart de zéro
        assertNull(debouncer.onFrame(HapticGesture.HEART, 200))
        assertEquals(HapticGesture.HEART, debouncer.onFrame(HapticGesture.HEART, 300))
    }
}
