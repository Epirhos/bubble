# IOS_BUILD — Lever la dette iOS (procédure)

> Le code iOS n'est PAS bloqué : l'outillage l'est. Kotlin/Native (link du framework) et
> Xcode/Swift ne tournent que sur macOS. « Faire ça » = exécuter la recette ci-dessous sur
> un macOS. Tout ce qui est en `commonMain` (moteur, crypto E2EE, jeux, radar) compile déjà
> pour iOS ; il ne reste que 2 adaptateurs natifs + l'assemblage Xcode.

## 0. Obtenir un macOS (3 options)
- **Mac physique / VM** : le plus simple si dispo.
- **Mac cloud** (MacStadium, MacinCloud, AWS EC2 mac) : location à l'heure.
- **CI GitHub Actions `macos-14`** (recommandé, zéro Mac local) : voir `.github/workflows/ios.yml`.
  La CI compile le XCFramework + l'app à chaque push — c'est ce qui « débloque structurellement ».

## 1. Générer le framework partagé (SKIE inclus)
```bash
./gradlew :shared:assembleSharedXCFramework
# → shared/build/XCFrameworks/release/Shared.xcframework  (bindings Swift SKIE cuits dedans)
```

## 2. Générer le projet Xcode
Le repo ne versionne pas de `.xcodeproj` (fragile en diff) : il est généré depuis `iosApp/project.yml`
via **XcodeGen**.
```bash
brew install xcodegen
cd iosApp && xcodegen generate      # → Bubble.xcodeproj
open Bubble.xcodeproj
```
`project.yml` déclare : la cible app `Bubble`, l'extension widget `BubbleWidget`, l'App Group
`group.app.bubble.shared` (pont app↔widget), le package SPM WebRTC (stasel/WebRTC) et le lien
vers `Shared.xcframework`. Aucun CocoaPods.

## 3. Dette de code — ÉCRITE ET BRANCHÉE (à valider au 1er passage CI/Mac)
- **`IosPeerLink`** (iosMain, `signal/IosPeerLink.kt`) : `PeerLink` en Kotlin/Native via **cinterop
  direct** vers WebRTC.framework (pas de pont Swift). Pilote `RTCPeerConnection`, implémente les
  delegates ObjC (`RTCPeerConnectionDelegateProtocol`/`RTCDataChannelDelegateProtocol`) en Kotlin,
  garde flows + E2EE (`AeadMessageCipher`) + rôle déterministe + reconnexion backoff. Symétrie
  totale avec AndroidPeerLink. cinterop déclaré dans `shared/build.gradle.kts` + `webrtc.def`.
- **`IosSignalingClient`** (iosMain) : `SignalingClient` via `NSURLSessionWebSocketTask`.
- **`IosPairedIdentityStore`** (iosMain, `crypto/`) : identité chiffrée au repos dans le **Keychain**
  (`kSecAttrAccessibleWhenUnlockedThisDeviceOnly`, clé de classe Secure Enclave = équivalent de
  l'enveloppe AES-GCM Android). Pont CoreFoundation à confirmer en CI.
- **`IosBubbleGraph`** (iosMain, `di/`) : racine de composition (pendant de BubbleApplication) —
  moteur + IosHapticEngine (CoreHaptics) + Keychain + cycle de vie du lien E2EE + orchestration
  complète de la cérémonie de pairage, exposée à Swift en **flux simples (bool/data class)** pour
  éviter le décodage des sealed via SKIE (`isLive`, `ritualMerged`, `pairing: StateFlow<IosPairingState>`).
- **UI SwiftUI branchée (plus de TODO)** : `AppGraph.swift` (singleton), `LiveViewModel`/`RootViewModel`/
  `PairingViewModel` consomment les flux via `for await` ; `PlayfulOverlays.swift` (filigrane + coupon) ;
  radar câblé dans `LiveOverlayView.swift` (`radarFingerMove`, CoreHaptics via IosHapticEngine).
- **`IosDailyBubbleComposer`** (AVAssetWriter) et **`CameraGestureController`** (Vision) déjà présents.

## 3bis. Provisionnement WebRTC (cinterop + lien app)
Le cinterop et l'app utilisent le MÊME `WebRTC.xcframework` (stasel), placé dans `iosApp/Vendor/`
par la CI. Slices : `ios-arm64` (device) et `ios-arm64_x86_64-simulator`. Gradle reçoit le chemin
via `-Pwebrtc.framework.dir.<target>=<slice>`. Le premier run CI confirmera les noms exacts de
classes/méthodes cinterop (dépendants de l'entête ObjC du build WebRTC).

## 4. Brancher via SKIE (déjà généré en 1)
- `import Shared` puis remplacer les `TODO(SKIE)` de `LiveViewModel.swift`/`PairingViewModel.swift` :
  - `for await s in manager.state { … }` (Flow→AsyncSequence)
  - `try await handshake.complete(peerPublicKey: pub)` (suspend→async)
  - `switch payload { case .snapshot: … }` (sealed→enum Swift)

## 5. Lancer
Cmd+R au simulateur, ou `xcodebuild -scheme Bubble -destination 'platform=iOS Simulator,name=iPhone 15'`.

## Résumé : ce qui reste vraiment
Aucune logique métier. Uniquement : lancer la recette sur macOS + écrire `IosPeerLink`,
`IosPairedIdentityStore`, et les vues SwiftUI des jeux (mécanique, guidée par les pendants Android).
