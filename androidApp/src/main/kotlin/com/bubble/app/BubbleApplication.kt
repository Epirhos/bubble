package com.bubble.app

import android.app.Application
import com.bubble.app.media.DailyBubbleScheduler
import com.bubble.app.media.DailyGraph
import com.bubble.app.play.WeeklyCouponWorker
import com.bubble.app.session.SessionCoordinator
import com.bubble.app.widget.AndroidWidgetBridge
import com.bubble.shared.core.BubbleStateManager
import com.bubble.shared.core.PairingEvent
import com.bubble.shared.crypto.AndroidPairedIdentityStore
import com.bubble.shared.crypto.PairedIdentity
import com.bubble.shared.crypto.PairingResult
import com.bubble.shared.haptics.AndroidHapticEngine
import com.bubble.shared.play.PlayfulPayload
import com.bubble.shared.signal.SignalPayload
import com.bubble.shared.widget.WidgetRefreshPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Racine de composition : instancie le moteur KMP une seule fois pour tout le process
 * (activités, widget Glance et workers partageront ce même graphe).
 */
class BubbleApplication : Application() {

    lateinit var stateManager: BubbleStateManager
        private set

    /** Sous-système du cycle temporel (Daily Bubble + purge), accessible aux workers. */
    var dailyGraph: DailyGraph? = null
        private set

    /**
     * Point d'entrée des snapshots floutés déchiffrés. L'AndroidPeerLink (branché à l'étape
     * pairage) y ré-émettra `incomingSnapshots` ; le WidgetRefreshPipeline les affiche au widget.
     */
    private val _incomingWidgetSnapshots = MutableSharedFlow<ByteArray>(extraBufferCapacity = 8)
    val incomingWidgetSnapshots: SharedFlow<ByteArray> = _incomingWidgetSnapshots.asSharedFlow()

    fun pushWidgetSnapshot(bytes: ByteArray) {
        _incomingWidgetSnapshots.tryEmit(bytes)
    }

    /** Lien P2P E2EE : établi après pairage, restauré au démarrage, coupé à la désaffiliation. */
    lateinit var sessionCoordinator: SessionCoordinator
        private set

    /** Appelé par le PairingController à la réussite de la cérémonie. */
    fun onPairingComplete(result: PairingResult) {
        sessionCoordinator.onPaired(PairedIdentity(result.session, result.sessionKey))
    }

    /** Coupon hebdomadaire pioché localement (WeeklyCouponWorker) → révélé via le moteur. */
    fun armWeeklyCoupon(text: String) {
        stateManager.onSignalReceived(
            SignalPayload.Playful(System.currentTimeMillis(), PlayfulPayload.ActionCoupon(text)),
            deviceInteractive = true,
        )
    }

    override fun onCreate() {
        super.onCreate()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        stateManager = BubbleStateManager(
            scope = appScope,
            hapticEngine = AndroidHapticEngine(this),
        )
        val graph = DailyGraph(this, stateManager)
        dailyGraph = graph
        DailyBubbleScheduler.scheduleAll(this, graph.selfUtcOffsetMinutes())

        // Rafraîchissement basse fréquence du widget : Aura + snapshots floutés déchiffrés.
        WidgetRefreshPipeline(
            scope = appScope,
            snapshots = incomingWidgetSnapshots,
            aura = stateManager.aura,
            bridge = AndroidWidgetBridge(this),
        ).start()

        // Lien P2P E2EE : identité rangée chiffrée (Keystore), snapshots routés vers le widget.
        sessionCoordinator = SessionCoordinator(
            context = this,
            scope = appScope,
            manager = stateManager,
            identityStore = AndroidPairedIdentityStore(this),
            signalingUrl = DEV_SIGNALING_URL,
            onSnapshotBytes = ::pushWidgetSnapshot,
        )
        sessionCoordinator.restore() // rétablit un couple déjà pairé sans refaire la cérémonie

        WeeklyCouponWorker.schedule(this) // La Roulette Asynchrone : 1 coupon-cadeau / semaine

        // Désaffiliation : couper le lien et effacer l'identité chiffrée.
        appScope.launch {
            stateManager.pairingEvents.collect { event ->
                if (event is PairingEvent.BubbleSplit) sessionCoordinator.teardown()
            }
        }
    }

    private companion object {
        // Dev : émulateur → hôte local. Prod : wss://<relai> via TLS.
        const val DEV_SIGNALING_URL = "ws://10.0.2.2:8787"
    }
}
