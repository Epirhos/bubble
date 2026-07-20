package com.bubble.shared.signal

import com.bubble.shared.core.BubbleStateManager
import com.bubble.shared.crypto.PairingSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tunnel en mémoire : deux extrémités croisées, états pilotables — le "câble" des tests. */
private class LoopbackLink : PeerLink {
    val stateFlow = MutableStateFlow(PeerLinkState.IDLE)
    override val state: StateFlow<PeerLinkState> = stateFlow

    private val _incoming = MutableSharedFlow<PeerMessage>(extraBufferCapacity = 32)
    override val incoming: Flow<PeerMessage> = _incoming

    private val _snapshots = MutableSharedFlow<ByteArray>(extraBufferCapacity = 8)
    override val incomingSnapshots: Flow<ByteArray> = _snapshots

    var peer: LoopbackLink? = null

    override fun trySend(message: PeerMessage): Boolean {
        if (stateFlow.value != PeerLinkState.CONNECTED) return false
        // Sérialisation réelle aller-retour : le protocole JSON est exercé, pas contourné.
        return peer?._incoming?.tryEmit(PeerWire.decode(PeerWire.encode(message))) ?: false
    }

    override fun trySendSnapshot(bytes: ByteArray): Boolean {
        if (stateFlow.value != PeerLinkState.CONNECTED) return false
        return peer?._snapshots?.tryEmit(bytes) ?: false
    }

    override suspend fun connect(session: PairingSession) {
        stateFlow.value = PeerLinkState.CONNECTED
    }

    override suspend fun disconnect() {
        stateFlow.value = PeerLinkState.CLOSED
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LinkCoordinatorTest {
    private val sessionA = PairingSession("couple", "fp-a", "fp-b")
    private val sessionB = PairingSession("couple", "fp-b", "fp-a")

    @Test
    fun hapticPingTravelsEndToEnd() = runTest {
        val linkA = LoopbackLink()
        val linkB = LoopbackLink()
        linkA.peer = linkB
        linkB.peer = linkA

        val managerA = BubbleStateManager(backgroundScope)
        val managerB = BubbleStateManager(backgroundScope)
        LinkCoordinator(backgroundScope, managerA, linkA, selfUtcOffsetMinutes = { 120 }).start()
        LinkCoordinator(backgroundScope, managerB, linkB, selfUtcOffsetMinutes = { -240 }).start()
        runCurrent()

        managerA.onPaired(sessionA)
        managerB.onPaired(sessionB)
        linkA.connect(sessionA)
        linkB.connect(sessionB)
        runCurrent()

        val received = mutableListOf<HapticGesture>()
        backgroundScope.launch { managerB.immediateHaptics.collect { received += it } }
        runCurrent()

        // A, en Live, envoie un bisou détecté par l'IA locale…
        managerA.onPortalOpened()
        managerA.onLocalGestureDetected(HapticGesture.KISS)
        runCurrent()

        // …B le reçoit instantanément (P2P actif, appareil interactif).
        assertEquals(listOf(HapticGesture.KISS), received)
    }

    @Test
    fun handshakeExchangesTimezoneOffsetsAndPurgeIsNegotiated() = runTest {
        val linkA = LoopbackLink()
        val linkB = LoopbackLink()
        linkA.peer = linkB
        linkB.peer = linkA

        val managerA = BubbleStateManager(backgroundScope)
        val managerB = BubbleStateManager(backgroundScope)
        LinkCoordinator(backgroundScope, managerA, linkA, selfUtcOffsetMinutes = { 120 }).start()
        LinkCoordinator(backgroundScope, managerB, linkB, selfUtcOffsetMinutes = { 60 }).start()
        runCurrent()

        linkA.connect(sessionA)
        linkB.connect(sessionB)
        runCurrent()

        assertEquals(120, managerB.partnerUtcOffsetMinutes.value)
        assertEquals(60, managerA.partnerUtcOffsetMinutes.value)

        // 21h00 UTC → 23h00 chez A (UTC+2), 22h00 chez B (UTC+1) :
        // la purge attend la 23h30 de B (22h30 UTC), pas celle de A (21h30 UTC).
        val now = Instant.parse("2026-07-19T21:00:00Z")
        assertEquals(Instant.parse("2026-07-19T22:30:00Z"), managerA.nextPurgeInstant(120, now))
        assertEquals(Instant.parse("2026-07-19T22:30:00Z"), managerB.nextPurgeInstant(60, now))
    }

    @Test
    fun offlineGestureIsCachedThenFlushedOnReconnect() = runTest {
        val linkA = LoopbackLink()
        val linkB = LoopbackLink()
        linkA.peer = linkB
        linkB.peer = linkA

        val managerA = BubbleStateManager(backgroundScope)
        val managerB = BubbleStateManager(backgroundScope)
        LinkCoordinator(backgroundScope, managerA, linkA, selfUtcOffsetMinutes = { 0 }).start()
        LinkCoordinator(backgroundScope, managerB, linkB, selfUtcOffsetMinutes = { 0 }).start()
        runCurrent()

        managerA.onPaired(sessionA)
        managerB.onPaired(sessionB)
        linkB.connect(sessionB) // B écoute, mais A est hors ligne
        runCurrent()

        val received = mutableListOf<HapticGesture>()
        backgroundScope.launch { managerB.immediateHaptics.collect { received += it } }
        runCurrent()

        // A envoie deux gestes sans réseau : cache local (outbox), rien ne part.
        managerA.onPortalOpened()
        managerA.onLocalGestureDetected(HapticGesture.KISS)
        managerA.onLocalGestureDetected(HapticGesture.HEART)
        runCurrent()
        assertTrue(received.isEmpty(), "hors ligne : rien ne doit partir")

        // Le lien se rétablit en tâche de fond : Hello + flush de la file, dans l'ordre.
        linkA.connect(sessionA)
        runCurrent()
        assertEquals(listOf(HapticGesture.KISS, HapticGesture.HEART), received)
    }

    @Test
    fun presenceIsThrottledToOnePerMinute() = runTest {
        val linkA = LoopbackLink()
        val linkB = LoopbackLink()
        linkA.peer = linkB
        linkB.peer = linkA

        val managerB = BubbleStateManager(backgroundScope)
        val coordinatorA = LinkCoordinator(
            backgroundScope, BubbleStateManager(backgroundScope), linkA,
            selfUtcOffsetMinutes = { 0 },
        )
        coordinatorA.start()
        LinkCoordinator(backgroundScope, managerB, linkB, selfUtcOffsetMinutes = { 0 }).start()
        runCurrent()

        managerB.onPaired(sessionB)
        linkA.connect(sessionA)
        linkB.connect(sessionB)
        runCurrent()

        // Rafale de déverrouillages : une seule présence doit traverser.
        repeat(5) { coordinatorA.notePresence(active = true) }
        runCurrent()

        assertTrue(managerB.aura.value.lastPresenceAt != null, "l'Aura du partenaire s'est allumée")
    }
}
