package com.bubble.shared.ml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdaptiveSamplingPolicyTest {
    private fun policy() = AdaptiveSamplingPolicy(
        idleIntervalMillis = 500,
        activeIntervalMillis = 150,
        handProbeIntervalMillis = 1_000,
        activeHoldMillis = 3_000,
    )

    @Test
    fun idleDropsFramesTo2Hz() {
        val p = policy()
        assertNotNull(p.planFrame(0))
        assertNull(p.planFrame(200), "frame jetée : intervalle IDLE de 500 ms")
        assertNull(p.planFrame(499))
        assertNotNull(p.planFrame(500))
    }

    @Test
    fun idleProbesHandModelOncePerSecond() {
        val p = policy()
        assertTrue(p.planFrame(0)!!.runHandModel, "première frame : sonde mains")
        assertFalse(p.planFrame(500)!!.runHandModel, "sonde suivante pas avant 1 s")
        assertTrue(p.planFrame(1_000)!!.runHandModel)
        assertFalse(p.planFrame(1_500)!!.runHandModel)
    }

    @Test
    fun handPresencePromotesToActiveAt7Hz() {
        val p = policy()
        p.planFrame(0)
        p.onHandsResult(handsPresent = true, nowMillis = 0)
        assertEquals(AdaptiveSamplingPolicy.Mode.ACTIVE, p.mode)

        assertNull(p.planFrame(100), "toujours du frame dropping en ACTIVE")
        val plan = p.planFrame(160)
        assertNotNull(plan)
        assertTrue(plan.runHandModel, "en ACTIVE le modèle mains tourne à chaque frame retenue")
    }

    @Test
    fun activeFallsBackToIdleAfterHoldWithoutHands() {
        val p = policy()
        p.planFrame(0)
        p.onHandsResult(handsPresent = true, nowMillis = 0)

        p.onHandsResult(handsPresent = false, nowMillis = 2_000)
        assertEquals(AdaptiveSamplingPolicy.Mode.ACTIVE, p.mode, "3 s de grâce pas écoulées")

        p.onHandsResult(handsPresent = false, nowMillis = 3_100)
        assertEquals(AdaptiveSamplingPolicy.Mode.IDLE, p.mode)

        // De retour en IDLE : cadence 2 Hz.
        p.planFrame(3_200)
        assertNull(p.planFrame(3_400))
    }

    @Test
    fun handSeenAgainRefreshesTheHold() {
        val p = policy()
        p.planFrame(0)
        p.onHandsResult(handsPresent = true, nowMillis = 0)
        p.onHandsResult(handsPresent = true, nowMillis = 2_500) // main revue → le hold repart
        p.onHandsResult(handsPresent = false, nowMillis = 4_000)
        assertEquals(AdaptiveSamplingPolicy.Mode.ACTIVE, p.mode, "2 500 + 3 000 > 4 000")
        p.onHandsResult(handsPresent = false, nowMillis = 5_600)
        assertEquals(AdaptiveSamplingPolicy.Mode.IDLE, p.mode)
    }
}
