package com.bubble.shared.haptics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HapticWaveformTest {
    @Test
    fun kissRendersTwoTapsWithBreathBetween() {
        val waveform = HapticWaveform.render(HapticScores.KISS)
        // 45 ms on, 70 ms off, 45 ms on — la partition exacte de commonMain.
        assertEquals(listOf(45L, 70L, 45L), waveform.timings)
        assertEquals(listOf(115, 0, 166), waveform.amplitudes) // 0.45*255, silence, 0.65*255
    }

    @Test
    fun heartbeatKeepsLubDubSkeleton() {
        val waveform = HapticWaveform.render(HapticScores.HEARTBEAT)
        assertEquals(listOf(55L, 180L, 70L), waveform.timings)
        assertEquals(0, waveform.amplitudes[1])
        assertTrue(waveform.amplitudes[0] > waveform.amplitudes[2], "lub plus fort que dub")
    }

    @Test
    fun continuousAuraGetsSineEnvelope() {
        val waveform = HapticWaveform.render(HapticScores.AURA_PULSE)
        // 400 ms > seuil continu → subdivisée en pas avec enveloppe montée/descente.
        assertTrue(waveform.timings.size >= 3)
        assertEquals(400L, waveform.timings.sum())
        val amps = waveform.amplitudes
        assertTrue(amps.first() < amps[amps.size / 2], "montée douce")
        assertTrue(amps.last() < amps[amps.size / 2], "descente douce")
        assertTrue(amps.max() <= (0.25f * 255).toInt() + 1, "l'aura reste discrète")
    }

    @Test
    fun totalDurationMatchesScore() {
        assertEquals(160L, HapticScores.KISS.totalDurationMillis)
        assertEquals(305L, HapticScores.HEARTBEAT.totalDurationMillis)
        assertEquals(400L, HapticScores.AURA_PULSE.totalDurationMillis)
    }
}
