package com.bubble.shared.core

import com.bubble.shared.crypto.PairingSession
import com.bubble.shared.haptics.HapticEngine
import com.bubble.shared.signal.HapticGesture
import com.bubble.shared.signal.SignalPayload
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val testSession = PairingSession("couple-test", "fp-self", "fp-partner")

private class FakeHapticEngine : HapticEngine {
    val played = mutableListOf<String>()

    override fun playKiss() {
        played += "kiss"
    }

    override fun playHeartbeat() {
        played += "heartbeat"
    }

    override fun playAuraPulse() {
        played += "aura"
    }

    override fun playRadarPulse(intensity: Float) {
        played += "radar:$intensity"
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BubbleStateManagerTest {
    @Test
    fun pairingMovesFromUnpairedToAmbient() = runTest {
        val manager = BubbleStateManager(backgroundScope)
        assertIs<BubbleState.Unpaired>(manager.state.value)
        manager.onPaired(testSession)
        assertIs<BubbleState.Ambient>(manager.state.value)
    }

    @Test
    fun pairingIsStrictlyOneToOne() = runTest {
        val manager = BubbleStateManager(backgroundScope)
        assertTrue(manager.onPaired(testSession))

        val otherSession = PairingSession("couple-2", "fp-self", "fp-other")
        assertFalse(manager.onPaired(otherSession), "désaffiliation obligatoire avant un nouveau lien")
        assertEquals("couple-test", manager.pairingSession.value?.coupleId)

        manager.onUnpaired()
        assertTrue(manager.onPaired(otherSession), "à nouveau libre après la rupture")
        assertEquals("couple-2", manager.pairingSession.value?.coupleId)
    }

    @Test
    fun pairingLifecycleEmitsMergeAndSplitAnimationEvents() = runTest {
        val manager = BubbleStateManager(backgroundScope)
        val events = mutableListOf<PairingEvent>()
        backgroundScope.launch { manager.pairingEvents.collect { events += it } }
        runCurrent() // abonnement actif avant les émissions

        manager.onPaired(testSession)
        manager.onUnpaired()
        manager.onUnpaired() // idempotent : pas de second BubbleSplit
        runCurrent()

        assertEquals(
            listOf(PairingEvent.BubblesMerged(testSession), PairingEvent.BubbleSplit),
            events,
        )
    }

    @Test
    fun portalOpensOnlyFromAmbient() = runTest {
        val manager = BubbleStateManager(backgroundScope)
        assertFalse(manager.onPortalOpened(), "Portail interdit depuis Unpaired")
        manager.onPaired(testSession)
        assertTrue(manager.onPortalOpened())
        assertIs<BubbleState.Live>(manager.state.value)
    }

    @Test
    fun faceWatchdogEndsLiveAfter120sWithoutFace() = runTest {
        val manager = BubbleStateManager(backgroundScope)
        manager.onPaired(testSession)
        manager.onPortalOpened()

        advanceTimeBy(119_000)
        manager.onFaceDetected() // le visage réapparaît juste avant l'échéance → reset
        advanceTimeBy(119_000)
        assertIs<BubbleState.Live>(manager.state.value)

        advanceTimeBy(2_000) // 121 s sans visage depuis le dernier reset
        assertIs<BubbleState.Ambient>(manager.state.value)
    }

    @Test
    fun purgeDueDuringLiveIsDeferredUntilLiveEnds() = runTest {
        val manager = BubbleStateManager(backgroundScope)
        manager.onPaired(testSession)
        manager.onPortalOpened()

        manager.onPurgeDue()
        assertIs<BubbleState.Live>(manager.state.value, "La purge ne coupe jamais une session Live")

        manager.onLiveEnded(LiveEndReason.USER_EXIT)
        assertIs<BubbleState.PurgePending>(manager.state.value)

        assertTrue(manager.onPurgeStarted())
        assertIs<BubbleState.Purging>(manager.state.value)
        manager.onPurgeCompleted()
        assertIs<BubbleState.Ambient>(manager.state.value)
    }

    @Test
    fun hapticGoesToEchoQueueWhenDeviceInactiveAndReplaysOnUnlock() = runTest {
        val manager = BubbleStateManager(backgroundScope)
        manager.onPaired(testSession)

        manager.onSignalReceived(SignalPayload.Haptic(1L, HapticGesture.KISS), deviceInteractive = false)
        assertEquals(1, manager.echoQueue.pending.value.size)

        val replayed = manager.onDeviceUnlocked()
        assertEquals(listOf(HapticGesture.KISS), replayed.map { it.gesture })
        assertTrue(manager.echoQueue.pending.value.isEmpty())
    }

    @Test
    fun unlockReplaysEchoesThroughHapticEngineWithSpacing() = runTest {
        val engine = FakeHapticEngine()
        val manager = BubbleStateManager(backgroundScope, hapticEngine = engine)
        manager.onPaired(testSession)

        // Gestes différents pour ne pas déclencher la déduplication de l'EchoQueue.
        manager.onSignalReceived(SignalPayload.Haptic(1L, HapticGesture.KISS), deviceInteractive = false)
        manager.onSignalReceived(SignalPayload.Haptic(2L, HapticGesture.HEART), deviceInteractive = false)

        manager.onDeviceUnlocked()
        advanceTimeBy(1) // le premier écho part immédiatement
        assertEquals(listOf("kiss"), engine.played)

        advanceTimeBy(700) // le second attend l'espacement de 600 ms
        assertEquals(listOf("kiss", "heartbeat"), engine.played)
        assertTrue(manager.echoQueue.pending.value.isEmpty())
    }

    @Test
    fun interactiveHapticPlaysImmediately() = runTest {
        val engine = FakeHapticEngine()
        val manager = BubbleStateManager(backgroundScope, hapticEngine = engine)
        manager.onPaired(testSession)

        manager.onSignalReceived(SignalPayload.Haptic(1L, HapticGesture.HEART), deviceInteractive = true)

        assertEquals(listOf("heartbeat"), engine.played)
        assertTrue(manager.echoQueue.pending.value.isEmpty(), "pas d'écho si l'appareil est actif")
    }

    @Test
    fun openingPortalDrainsAndReplaysPendingEchoes() = runTest {
        val engine = FakeHapticEngine()
        val manager = BubbleStateManager(backgroundScope, hapticEngine = engine)
        manager.onPaired(testSession)
        manager.onSignalReceived(SignalPayload.Haptic(1L, HapticGesture.KISS), deviceInteractive = false)

        manager.onPortalOpened()
        advanceTimeBy(1_000)

        assertEquals(listOf("kiss"), engine.played)
        assertTrue(manager.echoQueue.pending.value.isEmpty())
    }

    @Test
    fun localGestureIsEmittedOnlyDuringLive() = runTest {
        val manager = BubbleStateManager(backgroundScope)
        val outgoing = mutableListOf<HapticGesture>()
        backgroundScope.launch { manager.outgoingGestures.collect { outgoing += it } }
        runCurrent()

        manager.onPaired(testSession)
        manager.onLocalGestureDetected(HapticGesture.KISS) // Ambient : ignoré
        runCurrent()
        assertTrue(outgoing.isEmpty(), "aucun envoi hors session Live")

        manager.onPortalOpened()
        manager.onLocalGestureDetected(HapticGesture.HEART)
        runCurrent()
        assertEquals(listOf(HapticGesture.HEART), outgoing)
    }

    @Test
    fun playfulPromptShowsLocallyWhenSentAndClearsOnPurge() = runTest {
        val manager = BubbleStateManager(backgroundScope)
        manager.onPaired(testSession)

        manager.sendPlayful(com.bubble.shared.play.PlayfulPayload.CanvasPrompt("Et si…"))
        assertEquals("Et si…", manager.activePrompt.value?.text, "l'amorce s'affiche chez l'émetteur aussi")

        manager.onPurgeDue()
        manager.onPurgeStarted()
        manager.onPurgeCompleted()
        assertEquals(null, manager.activePrompt.value, "les jeux s'effacent à la purge")
    }

    @Test
    fun incomingCouponRevealsThenEvaporatesOnRead() = runTest {
        val manager = BubbleStateManager(backgroundScope)
        manager.onPaired(testSession)

        manager.onSignalReceived(
            SignalPayload.Playful(1L, com.bubble.shared.play.PlayfulPayload.ActionCoupon("Bon pour un câlin")),
            deviceInteractive = true,
        )
        assertEquals("Bon pour un câlin", manager.activeCoupon.value?.text)

        manager.onCouponRead()
        assertEquals(null, manager.activeCoupon.value, "le coupon s'évapore à la lecture")
    }

    @Test
    fun incomingRadarStartsSeekingAndStopsWhenLiveEnds() = runTest {
        val engine = FakeHapticEngine()
        val manager = BubbleStateManager(backgroundScope, hapticEngine = engine)
        manager.onPaired(testSession)
        manager.onPortalOpened()

        manager.onSignalReceived(
            SignalPayload.Playful(1L, com.bubble.shared.play.PlayfulPayload.HapticRadar(0.5f, 0.5f)),
            deviceInteractive = true,
        )
        assertTrue(manager.radarSeeking.value, "le récepteur cherche le point caché")

        manager.onLiveEnded(LiveEndReason.USER_EXIT)
        assertFalse(manager.radarSeeking.value, "le radar ne survit pas à la sortie de session")
    }

    @Test
    fun canvasIsLastWriteWins() = runTest {
        val manager = BubbleStateManager(backgroundScope)
        manager.onPaired(testSession)

        manager.onSignalReceived(SignalPayload.CanvasTrace(1L, blobKey = "couple-canvas"), deviceInteractive = true)
        manager.onSignalReceived(SignalPayload.CanvasTrace(2L, blobKey = "couple-canvas"), deviceInteractive = true)

        assertEquals(2L, manager.canvas.value?.sentAtEpochMillis)
    }

    @Test
    fun purgeCompletedClearsAssetsButKeepsUnviewedDailyBubbleFlag() = runTest {
        val manager = BubbleStateManager(backgroundScope)
        manager.onPaired(testSession)
        manager.onSignalReceived(SignalPayload.Snapshot(1L, "snap-1"), deviceInteractive = true)
        manager.onDailyBubbleGenerated()

        manager.onPurgeDue()
        manager.onPurgeStarted()
        manager.onPurgeCompleted()

        assertEquals(null, manager.portalSnapshot.value)
        assertTrue(manager.dailyBubble.value.protectedFromPurge, "Daily Bubble non visionné protégé")
    }
}
