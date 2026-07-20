package com.bubble.shared.signal

import com.bubble.shared.core.BubbleStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/**
 * Orchestrateur entre le moteur ([BubbleStateManager]) et le tunnel P2P ([PeerLink]).
 *
 * Responsabilités :
 *  - Poignée de main : à chaque (re)connexion, envoie [PeerMessage.Hello] avec l'offset
 *    UTC local (Timezone Sync), puis vide l'outbox dans l'ordre.
 *  - Sortant : les gestes confirmés (`outgoingGestures`) partent instantanément si le
 *    canal est ouvert ; sinon ils sont mis en cache local (outbox bornée, FIFO) et
 *    transmis dès que la connexion se rétablit — le miroir émetteur de l'Écho Haptique.
 *    (Le chiffrement au repos de cette file arrive avec la couche E2EE.)
 *  - Entrant : Hello → offset partenaire ; Signal → routage moteur (haptique immédiate
 *    ou EchoQueue selon [isDeviceInteractive]).
 *  - Présence (Aura) : [notePresence] throttlée à 1/min, jamais en rafale.
 */
class LinkCoordinator(
    private val scope: CoroutineScope,
    private val manager: BubbleStateManager,
    private val link: PeerLink,
    private val clock: Clock = Clock.System,
    private val selfUtcOffsetMinutes: () -> Int,
    private val isDeviceInteractive: () -> Boolean = { true },
    private val outboxCapacity: Int = 32,
) {
    private val outbox = ArrayDeque<PeerMessage.Signal>()
    private var lastPresenceSentAtMillis = Long.MIN_VALUE / 2

    fun start() {
        scope.launch {
            link.incoming.collect { message ->
                when (message) {
                    is PeerMessage.Hello -> manager.onPartnerTimezone(message.utcOffsetMinutes)
                    is PeerMessage.Signal -> manager.onSignalReceived(message.payload, isDeviceInteractive())
                }
            }
        }
        scope.launch {
            manager.outgoingGestures.collect { gesture ->
                deliverOrQueue(
                    PeerMessage.Signal(
                        SignalPayload.Haptic(clock.now().toEpochMilliseconds(), gesture),
                    ),
                )
            }
        }
        scope.launch {
            manager.outgoingPlayful.collect { playful ->
                deliverOrQueue(
                    PeerMessage.Signal(
                        SignalPayload.Playful(clock.now().toEpochMilliseconds(), playful),
                    ),
                )
            }
        }
        scope.launch {
            link.state.collect { state ->
                if (state == PeerLinkState.CONNECTED) {
                    link.trySend(PeerMessage.Hello(selfUtcOffsetMinutes()))
                    flushOutbox()
                }
            }
        }
    }

    /** Déverrouillage/usage local : nourrit l'Aura du partenaire. Throttlé, jamais mis en file. */
    fun notePresence(active: Boolean) {
        val now = clock.now().toEpochMilliseconds()
        if (now - lastPresenceSentAtMillis < PRESENCE_THROTTLE_MILLIS) return
        lastPresenceSentAtMillis = now
        // La présence est périssable : si le lien est fermé, elle est simplement perdue.
        link.trySend(PeerMessage.Signal(SignalPayload.Presence(now, active)))
    }

    private fun deliverOrQueue(message: PeerMessage.Signal) {
        if (link.state.value == PeerLinkState.CONNECTED && link.trySend(message)) return
        outbox.addLast(message)
        while (outbox.size > outboxCapacity) outbox.removeFirst()
    }

    private fun flushOutbox() {
        while (outbox.isNotEmpty()) {
            if (!link.trySend(outbox.first())) return // le lien a rechuté : on garde le reste
            outbox.removeFirst()
        }
    }

    private companion object {
        const val PRESENCE_THROTTLE_MILLIS = 60_000L
    }
}
