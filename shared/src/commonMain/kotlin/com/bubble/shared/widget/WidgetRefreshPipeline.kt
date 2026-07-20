package com.bubble.shared.widget

import com.bubble.shared.core.AuraGlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Service de rafraîchissement du widget (étape 8, tâche 3).
 *
 * Relie les snapshots floutés reçus via le DataChannel WebRTC ([snapshots]) et l'Aura du
 * partenaire ([aura]) au [WidgetBridge], à BASSE FRÉQUENCE pour économiser la batterie et
 * éviter le kill par l'OS (les widgets qui se réveillent trop souvent sont bridés).
 *
 * Débit limité par canaux CONFLATED : seul le dernier état compte (last-write-wins, cohérent
 * avec la sémantique snapshot/canvas), et on écrit au plus une fois par intervalle — le tout
 * dernier snapshot finit toujours par s'afficher, les intermédiaires sont abandonnés.
 */
class WidgetRefreshPipeline(
    private val scope: CoroutineScope,
    private val snapshots: Flow<ByteArray>,
    private val aura: StateFlow<AuraGlow>,
    private val bridge: WidgetBridge,
    private val clock: Clock = Clock.System,
    private val snapshotMinInterval: Duration = 30.seconds,
    private val auraMinInterval: Duration = 60.seconds,
) {
    private val snapshotInbox = Channel<ByteArray>(Channel.CONFLATED)
    private val auraInbox = Channel<Float>(Channel.CONFLATED)

    fun start() {
        scope.launch { snapshots.collect { snapshotInbox.trySend(it) } }
        scope.launch {
            aura.collect { glow -> auraInbox.trySend(glow.intensityAt(clock.now())) }
        }

        scope.launch {
            for (bytes in snapshotInbox) {
                bridge.writeSnapshot(bytes, clock.now().toEpochMilliseconds())
                delay(snapshotMinInterval) // fenêtre anti-rafale : au plus 1 écriture / intervalle
            }
        }
        scope.launch {
            for (intensity in auraInbox) {
                bridge.writeAura(intensity)
                delay(auraMinInterval)
            }
        }
    }
}
