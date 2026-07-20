# ARCHITECTURE — Bubble (Calm Technology, couples à distance)

> Lire ce fichier en premier à chaque session. Ne jamais dupliquer cette info dans le code.

## Principe : KMP core + UI 100% native

```
bubles/
├── shared/                  # Kotlin Multiplatform — TOUTE la logique métier
│   └── src/
│       ├── commonMain/kotlin/com/bubble/shared/
│       │   ├── core/        # BubbleStateManager, BubbleState, EchoQueue (voir STATE_MACHINE.md)
│       │   ├── crypto/      # E2EE pur Kotlin : X25519 (ECDH), Sha256/Hkdf, PairingHandshake, PairingSession
│       │   ├── haptics/     # HapticEngine (interface), HapticScores (partition unique), HapticWaveform
│       │   ├── ml/          # GestureRecognizer (Flow<VisionEvent>), alimente le FaceWatchdog
│       │   ├── play/        # Jeux de Complicité : PlayfulPayload, HapticRadar, PromptRepository
│       │   ├── media/       # Pipeline snapshots (capture→flou→chiffrement), Daily Bubble
│       │   ├── signal/      # SignalPayload, SignalTransport/BlobRelay, SignalingClient (WebRTC)
│       │   ├── time/        # PurgeScheduler (règle "fuseau le plus tardif"), schedulers 21h/23h30
│       │   └── purge/       # PurgeEngine + exceptions (Daily Bubble non visionné)
│       ├── androidMain/     # actual: MediaPipe, Keystore, WorkManager bridges
│       └── iosMain/         # actual: CoreML/Vision, Keychain, BGTaskScheduler bridges
├── androidApp/              # Jetpack Compose + widget Glance (com.bubble.app.widget)
├── iosApp/                  # SwiftUI (Bubble/) + WidgetKit (BubbleWidget/)
└── server/                  # Relai de signalisation Node.js (rooms strictes de 2, zéro persistance)
```

## Règles de séparation
- `shared` = Kotlin pur, zéro dépendance UI. Exposé à iOS via XCFramework.
- Tout ce qui touche capteurs/OS passe par `expect/actual` (ML, crypto keystore, haptique, background tasks).
- Les apps natives sont des "coquilles" : elles rendent l'état émis par `shared` (flows → StateFlow/Combine) et remontent les intents utilisateur.
- IA 100% on-device : MediaPipe (Android) / CoreML+Vision (iOS) derrière l'interface commune `ml.GestureDetector`. Aucun frame ne quitte l'appareil en clair.

## Stratégie Widget : "Portail Live" par snapshots (pas de vidéo)
Contrainte OS : ni iOS (WidgetKit = timeline statique) ni Android (Glance/RemoteViews) ne permettent un flux 60fps en background.

Solution :
1. L'émetteur capture des **images clés** à fréquence élevée mais adaptative (batterie/réseau), les **floute côté émetteur** (le flou est cuit dans l'image — jamais de version nette sur le widget), les chiffre (E2EE) et les pousse.
2. Réception : push silencieux → déchiffrement → écriture dans le storage partagé App Group (iOS) / fichier interne (Android) → `WidgetCenter.reloadTimelines` / `GlanceAppWidget.update`.
3. **Défloutage par frottement** : uniquement dans l'app (le widget est un portail d'entrée ; le geste de frottement ouvre l'app en mode Live avec transition continue). Le widget n'affiche que la version floutée.
4. **Canvas Créatif** : même canal, politique "last-write-wins" — chaque envoi écrase le fichier précédent, un seul asset vivant par couple.
5. **Aura** : le widget ne reçoit qu'un scalar (teinte/intensité), jamais de donnée identifiable.

## Confidentialité par construction
- Aucun accusé "Vu" : le protocole n'a tout simplement pas de message de lecture.
- E2EE sur tout payload (snapshots, canvas, signaux haptiques, aura).
- Serveur = relais aveugle + signaling WebRTC. Purge quotidienne totale (voir DATA_FLOW.md).
- FaceWatchdog : coupe le flux Live si aucun visage détecté pendant 120s (détection locale).
