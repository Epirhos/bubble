package com.bubble.shared.play

import com.bubble.shared.signal.PeerMessage
import com.bubble.shared.signal.PeerWire
import com.bubble.shared.signal.SignalPayload
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayfulTest {

    @Test
    fun playfulPayloadSurvivesWireRoundTrip() {
        val message: PeerMessage = PeerMessage.Signal(
            SignalPayload.Playful(1L, PlayfulPayload.CanvasPrompt("Et si…", promptId = "p1")),
        )
        assertEquals(message, PeerWire.decode(PeerWire.encode(message)))

        val radar: PeerMessage = PeerMessage.Signal(SignalPayload.Playful(2L, PlayfulPayload.HapticRadar(0.3f, 0.7f)))
        assertEquals(radar, PeerWire.decode(PeerWire.encode(radar)))
    }

    @Test
    fun radarFeedbackGrowsWithProximity() {
        val far = RadarFeedback.forDistance(0.5f)
        val near = RadarFeedback.forDistance(0.05f)
        val cold = RadarFeedback.forDistance(0.9f)

        assertNull(cold, "hors portée : rien")
        assertTrue(near != null && far != null)
        assertTrue(near.intensity > far.intensity, "plus proche = plus fort")
        assertTrue(near.cadenceMillis < far.cadenceMillis, "plus proche = plus rapproché")
    }

    @Test
    fun onTargetGivesMaxIntensity() {
        val onIt = RadarFeedback.forDistance(0f)
        assertTrue(onIt != null && onIt.intensity >= 0.99f)
    }

    @Test
    fun repositoryPicksFromDefaultsAndCustom() = runTest {
        val store = InMemoryPromptStore()
        store.add("Notre code secret ?")
        // random forcé sur le dernier élément (le custom ajouté).
        val repo = PromptRepository(store, Random(0))
        val prompts = PromptRepository.DEFAULT_PROMPTS + store.all()
        assertTrue(repo.randomPrompt() in prompts)
        assertTrue(store.all().contains("Notre code secret ?"))
    }

    @Test
    fun customPromptDedupesAndTrims() = runTest {
        val store = InMemoryPromptStore()
        store.add("  Et si  ")
        store.add("Et si")
        assertEquals(listOf("Et si"), store.all(), "trim + déduplication")
    }
}
