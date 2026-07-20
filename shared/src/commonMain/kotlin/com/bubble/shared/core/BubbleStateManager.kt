package com.bubble.shared.core

import com.bubble.shared.crypto.PairingSession
import com.bubble.shared.haptics.HapticEngine
import com.bubble.shared.play.HapticRadarEngine
import com.bubble.shared.play.PlayfulPayload
import com.bubble.shared.signal.HapticGesture
import com.bubble.shared.signal.SignalPayload
import com.bubble.shared.time.PurgeScheduler
import kotlinx.datetime.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Référence vers le dernier asset affichable par le widget (blob relais, déjà flouté/chiffré). */
data class PortalAsset(val blobKey: String, val sentAtEpochMillis: Long)

/**
 * Machine d'état centrale de Bubble (source de vérité : /brain/STATE_MACHINE.md).
 *
 * Les UI natives (SwiftUI/Compose) observent [state], [aura], [portalSnapshot], [canvas],
 * [dailyBubble] et [echoQueue].pending, et remontent des intents via les méthodes `on*`.
 * Aucune logique métier ne doit vivre côté natif.
 */
class BubbleStateManager(
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val faceTimeout: Duration = 120.seconds,
    /** Moteur natif injecté par la plateforme (AndroidHapticEngine / IosHapticEngine). */
    private val hapticEngine: HapticEngine? = null,
    /** Respiration entre deux échos rejoués — jamais de rafale (Calm Technology). */
    private val echoReplaySpacing: Duration = 600.milliseconds,
) {
    private val _state = MutableStateFlow<BubbleState>(BubbleState.Unpaired)
    val state: StateFlow<BubbleState> = _state.asStateFlow()

    private val _aura = MutableStateFlow(AuraGlow.NEUTRAL)
    val aura: StateFlow<AuraGlow> = _aura.asStateFlow()

    private val _portalSnapshot = MutableStateFlow<PortalAsset?>(null)
    val portalSnapshot: StateFlow<PortalAsset?> = _portalSnapshot.asStateFlow()

    private val _canvas = MutableStateFlow<PortalAsset?>(null)
    val canvas: StateFlow<PortalAsset?> = _canvas.asStateFlow()

    private val _dailyBubble = MutableStateFlow(DailyBubbleStatus.NONE)
    val dailyBubble: StateFlow<DailyBubbleStatus> = _dailyBubble.asStateFlow()

    /** Phase du cycle temporel quotidien (21h génération / 23h30 purge / persistance). */
    private val _dailyPhase = MutableStateFlow(DailyPhase.IDLE)
    val dailyPhase: StateFlow<DailyPhase> = _dailyPhase.asStateFlow()

    /**
     * Gestes joués à l'instant — temps réel OU replay d'échos. Les UI s'y abonnent pour
     * les retours visuels (halo d'Aura) ; la vibration est déjà déclenchée par le manager.
     */
    private val _immediateHaptics = MutableSharedFlow<HapticGesture>(extraBufferCapacity = 4)
    val immediateHaptics: SharedFlow<HapticGesture> = _immediateHaptics.asSharedFlow()

    /** Gestes locaux confirmés, à destination du partenaire (consommés par le transport E2EE). */
    private val _outgoingGestures = MutableSharedFlow<HapticGesture>(extraBufferCapacity = 4)
    val outgoingGestures: SharedFlow<HapticGesture> = _outgoingGestures.asSharedFlow()

    // ── Jeux de Complicité (étape 10) — pilotent le Canvas/Aura/Haptique existants ─────

    /** Amorce en filigrane gravée sur le Canvas ; null = pas de jeu de narration en cours. */
    private val _activePrompt = MutableStateFlow<PlayfulPayload.CanvasPrompt?>(null)
    val activePrompt: StateFlow<PlayfulPayload.CanvasPrompt?> = _activePrompt.asStateFlow()

    /** Coupon révélé (mini-défi) ; s'évapore à la lecture (onCouponRead). */
    private val _activeCoupon = MutableStateFlow<PlayfulPayload.ActionCoupon?>(null)
    val activeCoupon: StateFlow<PlayfulPayload.ActionCoupon?> = _activeCoupon.asStateFlow()

    /** True quand on cherche un point caché reçu (Radar Haptique côté récepteur). */
    private val _radarSeeking = MutableStateFlow(false)
    val radarSeeking: StateFlow<Boolean> = _radarSeeking.asStateFlow()

    private val radarEngine = HapticRadarEngine(scope, hapticEngine)

    /** Playful sortants, à destination du partenaire (consommés par le transport E2EE). */
    private val _outgoingPlayful = MutableSharedFlow<PlayfulPayload>(extraBufferCapacity = 4)
    val outgoingPlayful: SharedFlow<PlayfulPayload> = _outgoingPlayful.asSharedFlow()

    val echoQueue = EchoQueue()

    private val _pairingSession = MutableStateFlow<PairingSession?>(null)
    val pairingSession: StateFlow<PairingSession?> = _pairingSession.asStateFlow()

    /** Rituels d'animation (fusion/scission de bulles) consommés par les UI natives. */
    private val _pairingEvents = MutableSharedFlow<PairingEvent>(extraBufferCapacity = 4)
    val pairingEvents: SharedFlow<PairingEvent> = _pairingEvents.asSharedFlow()

    /** Offset UTC du partenaire (minutes), reçu via la poignée de main P2P. */
    private val _partnerUtcOffsetMinutes = MutableStateFlow<Int?>(null)
    val partnerUtcOffsetMinutes: StateFlow<Int?> = _partnerUtcOffsetMinutes.asStateFlow()

    private var faceWatchdog: Job? = null
    private var purgeRequested = false

    // ── Pairage ────────────────────────────────────────────────────────────────

    /**
     * Affiliation strictement 1:1 : refusée (retourne false) si un lien existe déjà —
     * la désaffiliation via [onUnpaired] est un préalable obligatoire.
     * Une réussite émet [PairingEvent.BubblesMerged] (animation de fusion des deux bulles).
     */
    fun onPaired(session: PairingSession): Boolean {
        if (_state.value !is BubbleState.Unpaired) return false
        _pairingSession.value = session
        _state.value = BubbleState.Ambient
        _pairingEvents.tryEmit(PairingEvent.BubblesMerged(session))
        return true
    }

    /**
     * Rupture du lien : retour à Unpaired + effacement immédiat de tout état en mémoire.
     * Émet [PairingEvent.BubbleSplit] (animation de scission de la bulle).
     */
    fun onUnpaired() {
        if (_state.value is BubbleState.Unpaired) return
        faceWatchdog?.cancel()
        purgeRequested = false
        echoQueue.clear()
        _aura.value = AuraGlow.NEUTRAL
        _portalSnapshot.value = null
        _canvas.value = null
        _dailyBubble.value = DailyBubbleStatus.NONE
        _dailyPhase.value = DailyPhase.IDLE
        stopRadar()
        _activePrompt.value = null
        _activeCoupon.value = null
        _pairingSession.value = null
        _partnerUtcOffsetMinutes.value = null
        _state.value = BubbleState.Unpaired
        _pairingEvents.tryEmit(PairingEvent.BubbleSplit)
    }

    // ── Portail Live ───────────────────────────────────────────────────────────

    /** Widget → deep link → vue Live. Retourne false si la transition est illégale. */
    fun onPortalOpened(): Boolean {
        if (_state.value !is BubbleState.Ambient) return false
        _state.value = BubbleState.Live(since = clock.now())
        restartFaceWatchdog()
        // Ouvrir le portail vaut réveil : les bisous arrivés pendant l'absence se rejouent.
        replayEchoes(echoQueue.drain())
        return true
    }

    /** À appeler par la couche ML à chaque détection de visage pendant Live. */
    fun onFaceDetected() {
        if (_state.value is BubbleState.Live) restartFaceWatchdog()
    }

    /**
     * Geste formellement identifié par l'IA locale (déjà débouncé côté vision).
     * N'accepte qu'en Live ; seul le trigger numérique sort d'ici — jamais une frame.
     * Le transport (étape réseau) s'abonnera à [outgoingGestures] pour l'envoyer chiffré.
     */
    fun onLocalGestureDetected(gesture: HapticGesture) {
        if (_state.value !is BubbleState.Live) return
        _outgoingGestures.tryEmit(gesture)
    }

    fun onLiveEnded(reason: LiveEndReason) {
        if (_state.value !is BubbleState.Live) return
        faceWatchdog?.cancel()
        faceWatchdog = null
        stopRadar() // le Radar Haptique ne survit pas à la sortie de session
        // Une purge arrivée à échéance pendant la session Live s'exécute maintenant.
        _state.value = if (purgeRequested) BubbleState.PurgePending else BubbleState.Ambient
    }

    private fun restartFaceWatchdog() {
        faceWatchdog?.cancel()
        faceWatchdog = scope.launch {
            delay(faceTimeout)
            onLiveEnded(LiveEndReason.NO_FACE_TIMEOUT)
        }
    }

    // ── Signaux entrants ───────────────────────────────────────────────────────

    /**
     * Routage d'un payload déchiffré. [deviceInteractive] = écran allumé et déverrouillé ;
     * décide entre haptique immédiate et Écho Haptique différé.
     */
    fun onSignalReceived(payload: SignalPayload, deviceInteractive: Boolean) {
        if (_state.value is BubbleState.Unpaired) return
        when (payload) {
            is SignalPayload.Snapshot ->
                _portalSnapshot.value = PortalAsset(payload.blobKey, payload.sentAtEpochMillis)

            is SignalPayload.CanvasTrace ->
                _canvas.value = PortalAsset(payload.blobKey, payload.sentAtEpochMillis)

            is SignalPayload.Haptic ->
                if (deviceInteractive) {
                    _immediateHaptics.tryEmit(payload.gesture)
                    playGesture(payload.gesture)
                } else {
                    echoQueue.enqueue(
                        HapticEcho(payload.gesture, receivedAtEpochMillis = clock.now().toEpochMilliseconds()),
                    )
                }

            is SignalPayload.Presence ->
                _aura.value = if (payload.active) AuraGlow(lastPresenceAt = clock.now()) else _aura.value

            is SignalPayload.Playful -> onPlayfulReceived(payload.payload)
        }
    }

    // ── Jeux de Complicité ───────────────────────────────────────────────────────

    private fun onPlayfulReceived(playful: PlayfulPayload) {
        when (playful) {
            is PlayfulPayload.CanvasPrompt -> _activePrompt.value = playful
            is PlayfulPayload.ActionCoupon -> _activeCoupon.value = playful
            is PlayfulPayload.HapticRadar -> {
                // Récepteur : le point est INVISIBLE, on le cherche au doigt guidé par l'haptique.
                _radarSeeking.value = true
                radarEngine.start(playful.x, playful.y)
            }
        }
    }

    /**
     * Émet un jeu vers le partenaire. Pour un [PlayfulPayload.CanvasPrompt], l'amorce
     * s'affiche AUSSI localement (les deux dessinent par-dessus la même gravure).
     */
    fun sendPlayful(playful: PlayfulPayload) {
        if (_state.value is BubbleState.Unpaired) return
        if (playful is PlayfulPayload.CanvasPrompt) _activePrompt.value = playful
        _outgoingPlayful.tryEmit(playful)
    }

    /** Récepteur du Radar : déplacement du doigt (0..1). Coût négligeable, ne bloque jamais l'UI. */
    fun onRadarFingerMove(x: Float, y: Float) {
        if (_radarSeeking.value) radarEngine.onFingerMove(x, y)
    }

    /** Lecture du coupon → il s'évapore aussitôt (éphémérité). */
    fun onCouponRead() {
        _activeCoupon.value = null
    }

    /** Fin d'un jeu de narration (nouvelle amorce ou sortie). */
    fun clearPrompt() {
        _activePrompt.value = null
    }

    private fun stopRadar() {
        if (_radarSeeking.value) {
            radarEngine.stop()
            _radarSeeking.value = false
        }
    }

    /** Déverrouillage local : rejoue les échos en attente. Aucun signal réseau n'est émis ici. */
    fun onDeviceUnlocked(): List<HapticEcho> {
        val drained = echoQueue.drain()
        replayEchoes(drained)
        return drained
    }

    /** Rejoue les échos séquentiellement, espacés de [echoReplaySpacing] (jamais de rafale). */
    private fun replayEchoes(echoes: List<HapticEcho>) {
        if (echoes.isEmpty() || hapticEngine == null) return
        scope.launch {
            echoes.forEachIndexed { index, echo ->
                if (index > 0) delay(echoReplaySpacing)
                _immediateHaptics.tryEmit(echo.gesture)
                playGesture(echo.gesture)
            }
        }
    }

    private fun playGesture(gesture: HapticGesture) {
        when (gesture) {
            HapticGesture.KISS -> hapticEngine?.playKiss()
            HapticGesture.HEART -> hapticEngine?.playHeartbeat()
        }
    }

    // ── Daily Bubble (cycle temporel, étape 7) ──────────────────────────────────

    /** 21h : le worker natif commence à compiler le montage → phase GENERATING_SUMMARY. */
    fun onDailySummaryStarted() {
        if (_state.value is BubbleState.Unpaired) return
        _dailyPhase.value = DailyPhase.GENERATING_SUMMARY
    }

    /** Montage prêt (non visionné). Retour phase IDLE ; le badge s'affiche via [dailyBubble]. */
    fun onDailyBubbleGenerated() {
        _dailyBubble.value = DailyBubbleStatus(exists = true, viewed = false)
        if (_dailyPhase.value == DailyPhase.GENERATING_SUMMARY) _dailyPhase.value = DailyPhase.IDLE
    }

    /** Génération sans fragment exploitable : pas de montage, retour IDLE. */
    fun onDailySummaryEmpty() {
        if (_dailyPhase.value == DailyPhase.GENERATING_SUMMARY) _dailyPhase.value = DailyPhase.IDLE
    }

    /**
     * L'utilisateur ouvre le montage. Marque `viewed=true` : le fichier devient purgeable.
     * S'il était conservé après la purge (PERSISTED_UNWATCHED), le visionnage déclenche sa
     * suppression définitive puis la réinitialisation sur la page blanche.
     */
    fun onDailyBubbleViewed() {
        if (!_dailyBubble.value.exists) return
        _dailyBubble.value = _dailyBubble.value.copy(viewed = true)
        if (_dailyPhase.value == DailyPhase.PERSISTED_UNWATCHED) {
            // Le worker natif supprime le fichier chiffré puis appelle onDailyBubblePurged().
            _dailyPhase.value = DailyPhase.IDLE
        }
    }

    /** Appelé par le worker natif quand le fichier montage a été supprimé (page blanche). */
    fun onDailyBubblePurged() {
        _dailyBubble.value = DailyBubbleStatus.NONE
    }

    // ── Timezone Sync ──────────────────────────────────────────────────────────

    /** Reçu à chaque Hello P2P — mis à jour si le partenaire voyage ou change d'heure (DST). */
    fun onPartnerTimezone(offsetMinutes: Int) {
        _partnerUtcOffsetMinutes.value = offsetMinutes
    }

    /**
     * Instant de la prochaine purge : 23h30 dans le fuseau le plus en retard du couple.
     * Tant que l'offset partenaire est inconnu, repli prudent sur le fuseau local seul.
     * Les schedulers natifs (WorkManager/BGTaskScheduler) planifient sur cette valeur.
     */
    fun nextPurgeInstant(selfOffsetMinutes: Int, now: Instant = clock.now()): Instant {
        val partnerOffset = _partnerUtcOffsetMinutes.value ?: selfOffsetMinutes
        return PurgeScheduler.nextCouplePurgeInstant(now, selfOffsetMinutes, partnerOffset)
    }

    // ── Purge ──────────────────────────────────────────────────────────────────

    /**
     * L'instant 23h30 (fuseau le plus tardif) est atteint.
     * Si une session Live est en cours, la purge est différée à la fin de la session.
     */
    fun onPurgeDue() {
        when (_state.value) {
            is BubbleState.Unpaired, BubbleState.PurgePending, BubbleState.Purging -> return
            is BubbleState.Live -> purgeRequested = true
            is BubbleState.Ambient -> {
                _dailyPhase.value = DailyPhase.PURGE_READY
                _state.value = BubbleState.PurgePending
            }
        }
    }

    fun onPurgeStarted(): Boolean {
        if (_state.value != BubbleState.PurgePending) return false
        purgeRequested = false
        _dailyPhase.value = DailyPhase.PURGE_READY
        _state.value = BubbleState.Purging
        return true
    }

    /**
     * Purge terminée. Efface tout l'état volatil ; le Daily Bubble non visionné est la
     * seule exception (l'effacement du fichier est fait par le PurgeEngine, le flag survit).
     * Si un montage non vu subsiste → phase PERSISTED_UNWATCHED (il attend le visionnage).
     */
    fun onPurgeCompleted() {
        if (_state.value != BubbleState.Purging) return
        echoQueue.clear()
        _portalSnapshot.value = null
        _canvas.value = null
        _aura.value = AuraGlow.NEUTRAL
        // Éphémérité : les jeux du jour s'effacent aussi (les tracés sont déjà dans le montage 21h).
        stopRadar()
        _activePrompt.value = null
        _activeCoupon.value = null
        _dailyPhase.value =
            if (_dailyBubble.value.protectedFromPurge) DailyPhase.PERSISTED_UNWATCHED else DailyPhase.IDLE
        _state.value = BubbleState.Ambient
    }
}
