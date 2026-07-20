package com.bubble.app.session

import android.content.Context
import com.bubble.shared.core.BubbleStateManager
import com.bubble.shared.crypto.AeadMessageCipher
import com.bubble.shared.crypto.PairedIdentity
import com.bubble.shared.crypto.PairedIdentityStore
import com.bubble.shared.signal.AndroidPeerLink
import com.bubble.shared.signal.LinkCoordinator
import com.bubble.shared.signal.OkHttpSignalingClient
import com.bubble.shared.signal.SignalingClient
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Cycle de vie du lien P2P E2EE côté app (branchement de la dette d'étape 9).
 *
 * - Le [SignalingClient] est partagé avec la cérémonie de pairage : le lien WebRTC réutilise
 *   la connexion déjà ouverte (même room, aucune reconnexion).
 * - Après pairage, [attach] persiste l'identité chiffrée, monte l'AndroidPeerLink avec le
 *   [AeadMessageCipher] (E2EE réel), démarre le LinkCoordinator et route les snapshots
 *   déchiffrés vers le widget.
 * - [restore] rétablit tout au démarrage sans refaire la cérémonie ; [teardown] efface tout
 *   à la désaffiliation.
 */
class SessionCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val manager: BubbleStateManager,
    private val identityStore: PairedIdentityStore,
    signalingUrl: String,
    private val onSnapshotBytes: (ByteArray) -> Unit,
    private val isDeviceInteractive: () -> Boolean = { true },
) {
    /** Partagé avec le pairage ; la première connexion (pairage) fixe la room. */
    val signaling: SignalingClient = OkHttpSignalingClient(signalingUrl)

    private var link: AndroidPeerLink? = null

    /** Démarrage app : rétablit un couple déjà pairé (identité chiffrée sur disque). */
    fun restore() {
        scope.launch {
            val identity = identityStore.load() ?: return@launch
            if (manager.onPaired(identity.session)) attach(identity, persist = false)
        }
    }

    /** Pairage réussi : persiste + monte le lien E2EE. `manager.onPaired` a déjà été appelé. */
    fun onPaired(identity: PairedIdentity) {
        scope.launch { attach(identity, persist = true) }
    }

    private suspend fun attach(identity: PairedIdentity, persist: Boolean) {
        if (persist) identityStore.save(identity)
        val cipher = AeadMessageCipher(identity.sessionKey)
        val peer = AndroidPeerLink(context, signaling, scope, messageCipher = cipher)
        LinkCoordinator(
            scope = scope,
            manager = manager,
            link = peer,
            selfUtcOffsetMinutes = { selfUtcOffsetMinutes() },
            isDeviceInteractive = isDeviceInteractive,
        ).start()
        scope.launch { peer.incomingSnapshots.collect(onSnapshotBytes) }
        scope.launch { peer.connect(identity.session) }
        link = peer
    }

    /** Désaffiliation : coupe le lien et efface l'identité chiffrée. */
    fun teardown() {
        val current = link
        link = null
        scope.launch {
            current?.disconnect()
            identityStore.clear()
        }
    }

    private fun selfUtcOffsetMinutes(): Int =
        TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
}
