# DATA_FLOW — Bubble

## 1. Routage des snapshots (Portail Live → Widget)

```
[Émetteur]                                        [Récepteur]
Caméra → keyframe (cadence adaptative)
  → flou gaussien ON-DEVICE (irréversible dans l'asset envoyé)
  → chiffrement E2EE (clé de session dérivée du pairage)
  → upload relais (blob opaque, TTL court)
  → push silencieux (APNs content-available / FCM data)
                                                  → déchiffrement en extension/worker
                                                  → écriture App Group (iOS) / filesDir (Android)
                                                  → reload widget (WidgetKit timeline / Glance update)
```
- Le serveur relais ne voit que des blobs chiffrés + tokens push. Aucune métadonnée de contenu.
- Version nette : n'existe que dans la session `Live` in-app (WebRTC P2P quand possible, sinon relais TURN chiffré).
- Canvas Créatif : même pipeline, clé d'objet fixe par couple → **écrasement systématique** (last-write-wins), pas d'historique.

## 2. Flux haptique (bisou / cœur) — implémenté étape 3

```
GestureRecognizer local (Flow<VisionEvent>, MediaPipe/CoreML) → SignalPayload.Haptic chiffré
  → push haute priorité → BubbleStateManager.onSignalReceived(payload, deviceInteractive) :
      deviceInteractive = true  → HapticEngine.playKiss()/playHeartbeat() immédiat
      deviceInteractive = false → EchoQueue (bornée 8, dédup 2 s)
                                  → replay à onDeviceUnlocked() OU onPortalOpened()
                                    séquentiel, espacé de 600 ms (jamais de rafale)
```
- Payload minimal : jamais d'image dans un signal geste.
- Pas d'ACK de lecture ni de statut "Vu" — le protocole est unidirectionnel fire-and-forget.
- **Patterns haptiques : source unique `haptics/HapticScores` (commonMain)** — partition en
  `HapticPulse(atMillis, durationMillis, intensity 0..1, sharpness 0..1)` :
  * KISS : 45 ms @ 0.45, silence 70 ms, 45 ms @ 0.65 (double tape douce).
  * HEARTBEAT : 55 ms @ 0.90, silence 180 ms, 70 ms @ 0.60 (lub-dub).
  * AURA_PULSE : nappe continue 400 ms @ 0.25.
- Traduction Android : `HapticWaveform.render` (commonMain, testé) → `VibrationEffect.createWaveform`
  (intensité×255 ; nappes subdivisées avec enveloppe sinusoïdale ; repli on/off sans amplitude control).
- Traduction iOS : CoreHaptics direct (≤ 100 ms → transient, sinon continuous ; intensity/sharpness natifs).
- Les timings sont identiques au ms près sur les deux OS ; seule la traduction matérielle diffère.

## 3. Aura d'Activité

- Déclencheur : événement local de déverrouillage/usage (ACTION_USER_PRESENT / UIApplication events).
- Émission : ping chiffré `{presence: active}` throttlé (max 1/min) → le widget partenaire interpole une teinte chaude qui décroît (fenêtre ~15 min).
- Jamais de contenu, jamais de notification, pas d'historique de présence stocké.

## 4. Daily Bubble (21h00 locale)

- Scheduler local (`shared/time`) : à 21h00 **fuseau local de chaque utilisateur**, montage accéléré généré on-device depuis les keyframes du jour (pas d'upload du montage).
- Flag `viewed` local ; conditionne l'exception de purge.

## 5. Purge synchronisée (23h30, règle du fuseau le plus tardif)

### Logique Timezone Sync (`shared/time/TimezoneSync`)
1. Chaque partenaire partage son offset UTC courant (donnée chiffrée, mise à jour au changement de fuseau).
2. `purgeInstant = 23:30 dans le fuseau le PLUS EN RETARD sur l'horloge` (celui où il est le plus tôt) → aucun des deux ne voit sa soirée coupée avant sa propre 23h30.
   - Ex : Paris (UTC+2) & Montréal (UTC−4) → purge à 23h30 Montréal = 05h30 Paris.
3. L'instant est recalculé quotidiennement et à chaque changement d'offset (DST inclus). Les deux devices convergent vers le même instant UTC sans horloge serveur de contenu.

### Exécution
- Local : WorkManager (Android) / BGTaskScheduler (iOS) + filet de rattrapage au premier lancement après l'instant si l'OS a différé la tâche.
- Cibles : snapshots, canvas, files haptiques consommées, caches widget, montage **visionné**.
- Serveur : appel authentifié de purge des blobs du couple (le relais applique aussi un TTL autonome — la purge ne dépend pas de la bonne volonté du client).
- **Exceptions** : Daily Bubble `viewed=false`, clés de pairage, préférences.

## 6. Pipeline vision on-device (étape 5 — implémenté)

```
Caméra frontale (CameraX / AVFoundation, 640×480 basse conso)
  → frame dropping 2 étages : KEEP_ONLY_LATEST + AdaptiveSamplingPolicy (commonMain, testée) :
      IDLE 2 Hz, visage seul, sonde mains 1×/s  ↔  ACTIVE ~7 Hz visage+mains
      promotion dès qu'une main est vue ; retour IDLE après 3 s sans main (~3-4× de CPU moyen en moins)
  → inférence HORS Main Thread (executor dédié / DispatchQueue)
      Android : MediaPipe HandLandmarker + FaceLandmarker (assets .task, IMAGE mode)
      iOS     : Vision VNDetectHumanHandPoseRequest + VNDetectFaceLandmarksRequest
  → frame DÉTRUITE immédiatement (bitmap.recycle + close / sortie de scope CMSampleBuffer)
  → landmarks → GestureTriggers (commonMain, testé JVM) → GestureDebouncer (hold 3 frames, cooldown 3 s)
  → VisionEvent uniquement (jamais une image) :
      GestureDetected → manager.onLocalGestureDetected → flux outgoingGestures (transport E2EE plus tard)
      FaceAppeared (ré-émis chaque frame traité) → manager.onFaceDetected (réarme watchdog 120 s)
      120 s sans FaceAppeared → Live→Ambient → l'activité/vue se ferme → caméra déliée (coupe-circuit)
```
- Triggers : CŒUR = index↔index et pouce↔pouce < 0.14 (normalisé) + pointe au-dessus de la base ;
  BISOU = largeur bouche/visage < 0.30 (pucker) + un point de main à < 0.22 du centre des lèvres.
  Attention : Vision (iOS) est en y-vers-le-haut → comparaisons verticales inversées. Seuil pucker iOS 0.36 (outerLips/bbox), à calibrer.
- Indices MediaPipe : pouce 4, index 8 ; lèvres 13/14, commissures 61/291, joues 234/454.
- Modèles Android dans androidApp/src/main/assets/ (hand 7,8 Mo + face 3,8 Mo, float16).
- Le debouncer et la géométrie sont UNIQUES (commonMain) — jamais les dupliquer en natif (le miroir Swift de debounce est temporaire, à remplacer par le pont KMP).

## 7. Lien P2P WebRTC (étape 6 — implémenté)

```
BubbleStateManager ⇄ LinkCoordinator (commonMain) ⇄ PeerLink (interface)
                                                     ├─ AndroidPeerLink (stream-webrtc-android, org.webrtc)
                                                     └─ IosPeerLink (GoogleWebRTC — à venir, même interface)
Signalisation : SignalingClient ⇄ OkHttpSignalingClient (WS) ⇄ server/signaling-server.js
```
- **Serveur** (`server/signaling-server.js`, Node+ws, testé par `smoke-test.js`) : rooms STRICTES
  de 2 (3e fingerprint refusé code 4003, reconnexion même fingerprint remplace l'ancienne socket),
  relai aveugle octet-à-octet, zéro persistance, heartbeat 30 s.
- **Canaux** : "bubble-meta" (fiable/ordonné — PeerMessage JSON : Hello{utcOffsetMinutes}, Signal{SignalPayload}) ;
  "bubble-snapshots" (non fiable, maxRetransmits=0 — binaire E2EE, un snapshot perdu est remplacé par le suivant).
  DTLS obligatoire par spec WebRTC : rien ne transite en clair, le serveur ne voit que SDP/ICE.
- **Rôles** : initiateur = fingerprint le plus petit (déterministe, pas de collision offer/offer).
- **LinkCoordinator** (commonMain, testé en loopback) : à chaque CONNECTED → Hello (Timezone Sync) puis
  flush de l'outbox ; gestes sortants → envoi immédiat si canal ouvert, sinon **outbox FIFO bornée (32)**
  vidée à la reconnexion (miroir émetteur de l'Écho Haptique ; chiffrement au repos avec l'étape E2EE) ;
  présence throttlée 1/min. `manager.nextPurgeInstant(selfOffset)` = 23h30 du fuseau le plus en retard
  (repli fuseau local tant que l'offset partenaire est inconnu ; reset à la désaffiliation).
- **Reconnexion** (AndroidPeerLink) : sur FAILED/DISCONNECTED → renégociation complète avec backoff
  exponentiel 1 s→30 s + jitter 500 ms, annulée si `disconnect()` volontaire ; compteur remis à zéro
  à chaque ouverture du canal meta. La signalisation WS se rétablit indépendamment (heartbeat serveur).

## 8. Cycle temporel : Daily Bubble & Purge (étape 7 — implémenté)

```
21h locale (WorkManager / BGTaskScheduler) → DailyBubbleCoordinator.generateDailyBubble()
  → dailyPhase=GENERATING_SUMMARY → DailyFragmentSource.fragmentsForToday() (cache local, 100% on-device)
  → DailyBubbleComposer.compile() : time-lapse H.264, EN FLUX (1 image en RAM à la fois, recycle immédiat)
      Android : MediaCodec + MediaMuxer via Surface (MediaCodecDailyComposer + SurfaceEncoderDrain)
      iOS     : AVAssetWriter + pixelBufferPool, autoreleasepool/frame (DailyBubbleComposer.swift)
  → SecureDailyBubbleStore.persist() : chiffre AES-256-GCM (clé Android Keystore, non exportable),
      supprime le clair de travail → dailyBubble.exists=true, viewed=false → phase IDLE

Instant purge = manager.nextPurgeInstant(selfOffset) = 23h30 du fuseau le plus tardif (offset partenaire via Hello P2P)
  → DailyBubbleCoordinator.runPurge() → onPurgeDue → onPurgeStarted (différée si Live) → PurgeEngine.execute()
      efface : fragments du jour, montage de travail, caches ; relais applique son propre TTL
      EXCEPTION : Daily Bubble viewed=false JAMAIS supprimé (conservé chiffré) → phase PERSISTED_UNWATCHED
  → onPurgeCompleted

Visionnage du montage conservé → onDailyBubbleWatched() → SecureDailyBubbleStore.delete()
  → onDailyBubblePurged → dailyBubble=NONE → phase IDLE (page blanche)
```
- Filet de rattrapage : les workers se replanifient à chaque exécution ; si l'OS diffère la tâche,
  la purge reste due et s'exécute au réveil.
- Le PurgeEngine voit le montage via `DailyBubbleStore` (KeystoreDailyBubbleStore implémente les 2 facettes) ;
  l'exception "non visionné" est décidée par `dailyBubble.protectedFromPurge` (moteur).

## 9. Widgets d'écran d'accueil (étape 8 — implémenté)

```
PeerLink.incomingSnapshots (bytes floutés déchiffrés) + manager.aura
  → WidgetRefreshPipeline (commonMain, testé) : canaux CONFLATED, débit limité
      snapshot ≤ 1 écriture / 30 s, aura ≤ 1 / 60 s (last-write-wins, économie batterie)
  → WidgetBridge :
      Android AndroidWidgetBridge → WidgetStorage (filesDir/widget/, écriture atomique) → BubblePortalWidget().updateAll()
      iOS     IOSWidgetBridge → App Group (group.app.bubble.shared) → WidgetCenter.reloadTimelines("BubblePortalWidget")
  → Widget PASSIF : rend snapshot flouté + halo d'Aura (opacité ∝ présence). Aucun traitement, aucun geste.
  → Tap = deep link bubble://live → MainActivity (Android, singleTask) / .widgetURL (iOS) → LiveOverlayScreen
```
- Android : Glance (`BubblePortalWidget`/`BubblePortalWidgetReceiver`), provider `res/xml/bubble_portal_widget_info.xml`.
- iOS : WidgetKit `StaticConfiguration` + `TimelineProvider` (entrée unique + refresh d'entretien 30 min ;
  les vraies MAJ viennent de `reloadTimelines` déclenché par l'app, pas d'un polling).
- Seam Android : `BubbleApplication.pushWidgetSnapshot()` — l'AndroidPeerLink y ré-émettra `incomingSnapshots`
  à l'étape pairage (aujourd'hui l'Aura est déjà câblée sur `manager.aura`).
- Rappel contrainte : le widget ne défloute JAMAIS (pas de gestes continus) ; le défloutage est in-app.

## 10. Cérémonie de pairage 1:1 & E2EE (étape 9 — implémenté)

```
Crypto pur Kotlin (commonMain, validée par vecteurs RFC/NIST) :
  X25519 (RFC 7748, port TweetNaCl) + Sha256 (FIPS 180-4) + Hkdf (RFC 5869)

Émetteur A : PairingHandshake.initiator() → bi-clé X25519 + coupleId aléatoire
  → QR bubble1:<coupleId>:<pubA>  (généré à la volée, ZXing/CoreImage, jamais de cloud)
Récepteur B : scanne le QR (a donc déjà pubA, hors serveur = non-MITMable)
  → PairingHandshake.responder() → bi-clé, secret = X25519(privB, pubA), sessionKey = HKDF(secret, coupleId)
  → connecte room=coupleId, envoie SignalingMessage.PairingKey(pubB) via le serveur
A reçoit pubB → complete() → secret = X25519(privA, pubB) → MÊME sessionKey (jamais échangée)
  → safety number (SHA-256 des 2 pubs, ordre canonique) comparé de vive voix → anti-MITM
  → manager.onPaired(session) → Ambient. Verrou 1:1 : serveur (room stricte de 2) + moteur (refus 2e lien).
```
- Le QR ne porte que du public (coupleId + pubA) ; la clé privée ne quitte jamais l'appareil.
- Fichiers : crypto/{X25519,Sha256,PairingCrypto,PairingHandshake,PairingSession}.kt ;
  Android pairing/{PairingController,QrCode,QrScanner,PairingScreen}.kt ; iOS PairingView.swift.
- Dev signaling : ws://10.0.2.2:8787 (émulateur→hôte) ; prod wss://.

### E2EE de bout en bout (étape 9bis — implémenté)
- **Chiffrement des payloads** : `AeadMessageCipher` (ChaCha20-Poly1305, RFC 8439, Kotlin pur testé)
  scelle chaque PeerMessage ET chaque snapshot binaire avec la sessionKey : `nonce(12)||ct||tag(16)`.
  Câblé dans `AndroidPeerLink` (seal avant envoi, open à la réception ; tag invalide → message rejeté,
  jamais de repli sur le chiffré). Vient EN PLUS de DTLS : un relais TURN compromis ne voit que du scellé.
- **Clé au repos** : `AndroidPairedIdentityStore` range {PairingSession + sessionKey} chiffré AES-256-GCM
  par une clé Keystore non exportable (chiffrement enveloppe). Restauré au démarrage sans refaire la cérémonie.
- **Cycle de vie du lien** : `SessionCoordinator` (androidApp) monte l'AndroidPeerLink+LinkCoordinator après
  pairage (réutilise le signaling de la cérémonie, même room), route `incomingSnapshots` déchiffrés vers le
  widget, restaure au boot, coupe + efface l'identité sur `PairingEvent.BubbleSplit` (désaffiliation).
- Snapshots widget : désormais alimentés en bout de chaîne réelle (P2P chiffré → open → pushWidgetSnapshot).
- Reste iOS-only (bloqué macOS) : `IosPeerLink` (GoogleWebRTC) + `IosPairedIdentityStore` (Keychain) —
  toute la crypto (X25519/AEAD/handshake/MessageCipher) est en commonMain, donc déjà compilable pour iOS.

## 11. Jeux de Complicité (étape 10 — implémenté)

RÈGLE : aucun chat texte. Tout réutilise Canvas / Aura / Haptique existants + éphémérité 23h30.

- **Payloads** : `play/PlayfulPayload` (sealed, @Serializable) → `CanvasPrompt`, `HapticRadar(x,y)`,
  `ActionCoupon`, enveloppés dans `SignalPayload.Playful` (même canal E2EE, même LinkCoordinator).
- **CanvasPrompt** (Narration) : filigrane GRAVÉ dans le verre (blur léger + opacité 28 %, jamais un
  pop-up) via `PromptWatermark`. `manager.sendPlayful` l'affiche aussi chez l'émetteur ; on répond en
  dessinant (Canvas last-write-wins). Les tracés partent dans le montage 21h puis sont détruits.
- **HapticRadar** : émetteur cache un point (x,y normalisés) → reçu INVISIBLE chez le partenaire.
  `HapticRadarEngine` (commonMain) : l'UI n'appelle que `onRadarFingerMove` (écrit un StateFlow, non
  bloquant) ; une coroutine unique lit la dernière position, calcule la distance et pilote
  `HapticEngine.playRadarPulse(intensity)` à cadence contrôlée (500 ms loin → 120 ms proche). Mapping
  distance→pulsation dans `RadarFeedback` (pur, testé). Jamais un vibrate par frame UI.
- **ActionCoupon** (Roulette Asynchrone) : `WeeklyCouponWorker` (WorkManager, 1/semaine) pioche un
  coupon local → `armWeeklyCoupon` → `CouponReveal` (pilule de verre) ; tap → `onCouponRead` → évaporation.
- **Prompts locaux (zéro serveur)** : `PromptRepository` = liste embarquée (`DEFAULT_PROMPTS`) +
  `CustomPromptStore` (Android `FilePromptStore`, une phrase/ligne) ; piochage aléatoire testé.
- **Éphémérité** : `onLiveEnded`/`onPurgeCompleted`/`onUnpaired` arrêtent le radar et effacent
  prompt+coupon. Session courte : bornée par le FaceWatchdog 120 s (déjà en place).
- États moteur exposés : `activePrompt`, `activeCoupon`, `radarSeeking` (StateFlow) ; sortant `outgoingPlayful`.

## 12. Sécurité transversale
- Pairage 1:1 : échange X25519 par QR, TOFU + vérification manuelle du fingerprint. Une seule paire active par device.
- E2EE : clés de session dérivées (HKDF) et renouvelées à chaque purge quotidienne (forward secrecy journalière).
- FaceWatchdog : en `Live`, détection visage locale ; 120s sans visage → arrêt flux + retour Ambient.
