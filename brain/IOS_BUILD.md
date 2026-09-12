# IOS_BUILD — Lever la dette iOS (procédure)

> Le code iOS n'est PAS bloqué : l'outillage l'est. Kotlin/Native (link du framework) et
> Xcode/Swift ne tournent que sur macOS. « Faire ça » = exécuter la recette ci-dessous sur
> un macOS. Tout ce qui est en `commonMain` (moteur, crypto E2EE, jeux, radar) compile déjà
> pour iOS ; il ne reste que 2 adaptateurs natifs + l'assemblage Xcode.

## 0. Obtenir un macOS (3 options)
- **Mac physique / VM** : le plus simple si dispo.
- **Mac cloud** (MacStadium, MacinCloud, AWS EC2 mac) : location à l'heure.
- **CI GitHub Actions `macos-14`** (recommandé, zéro Mac local) : voir `.github/workflows/ios.yml`.
  La CI compile le XCFramework + l'app à chaque push sur `main` touchant `shared/` ou `iosApp/`
  — c'est ce qui « débloque structurellement ». **Coût et diagnostic : /brain/CI.md.**

## 1. Générer le framework partagé (SKIE inclus)
```bash
# Variante Release seule : `assembleSharedXCFramework` linkerait aussi le debug (~2x le temps
# Kotlin/Native) pour un artefact que project.yml ne consomme pas. La CI utilise celle-ci.
./gradlew :shared:assembleSharedReleaseXCFramework
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
`group.app.bubble.shared` (pont app↔widget, via les `.entitlements`), le `WebRTC.xcframework`
de `Vendor/` (embarqué : dynamique) et le `Shared.xcframework` (NON embarqué : statique, fondu
au link). Ni CocoaPods ni SPM. Le widget ne lie pas `Shared` — voir /brain/CI.md.

## 3. Dette de code — ÉCRITE, BRANCHÉE, ET COMPILÉE EN CI
> État au 12/09/2026, établi par les deux premiers runs macOS réels (`.github/workflows/ios.yml`).
> **Compile et linke** : cinterop WebRTC, tout `iosMain`, le link Kotlin/Native du framework
> statique, SKIE, l'assemblage du XCFramework, et sa consommation par Xcode 16.2.
> **Le Swift de la cible `Bubble` compile aussi en arm64**, bindings SKIE inclus : le 3e run
> n'a échoué que sur la variante **x86_64**, pour laquelle le XCFramework n'a pas de tranche
> (voir /brain/CI.md, entrée `ARCHS=arm64`). Les `for await` sur les flux SKIE sont donc
> corrects.
> **Pas encore prouvé** : l'édition de liens finale et l'empaquetage de l'app. Aucun
> comportement à l'exécution n'est testé — rien n'a jamais tourné sur un simulateur.
>
> Conséquence pratique : **le simulateur n'est supporté qu'en arm64** (Mac Apple Silicon).
> Pour un Mac Intel, il faudrait ajouter la cible `iosX64()` dans `shared/build.gradle.kts`,
> au prix d'un link Kotlin/Native supplémentaire à chaque build.
- **`IosPeerLink`** (iosMain, `signal/IosPeerLink.kt`) : `PeerLink` en Kotlin/Native via **cinterop
  direct** vers WebRTC.framework (pas de pont Swift). Pilote `RTCPeerConnection`, implémente les
  delegates ObjC (`RTCPeerConnectionDelegateProtocol`/`RTCDataChannelDelegateProtocol`) en Kotlin,
  garde flows + E2EE (`AeadMessageCipher`) + rôle déterministe + reconnexion backoff. Symétrie
  totale avec AndroidPeerLink. cinterop déclaré dans `shared/build.gradle.kts` + `webrtc.def`.
  Deux corrections ont été nécessaires au 1er run (voir /brain/CI.md) : `dataChannelForLabel`
  vient d'une **catégorie** ObjC, donc d'une extension Kotlin qu'un import de classe n'apporte
  pas (`import webrtc.*`) ; et `didAddStream`/`didRemoveStream` se projettent sur la même
  signature Kotlin, d'où `@ObjCSignatureOverride`.
- **`IosSignalingClient`** (iosMain) : `SignalingClient` via `NSURLSessionWebSocketTask`.
- **`IosPairedIdentityStore`** (iosMain, `crypto/`) : identité chiffrée au repos dans le **Keychain**
  (`kSecAttrAccessibleWhenUnlockedThisDeviceOnly`, clé de classe Secure Enclave = équivalent de
  l'enveloppe AES-GCM Android). Pont CoreFoundation : **compile et linke** en CI ; le
  comportement au runtime (accès réel au Keychain) reste à vérifier sur simulateur/appareil.
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
par la CI. Gradle reçoit le chemin via `-Pwebrtc.framework.dir.<target>=<slice>`.

Version **épinglée à `153.0.0`** dans `ios.yml` (`WEBRTC_RELEASE`). Ce n'est pas de la prudence
gratuite : les noms de classes/méthodes du cinterop viennent de l'entête ObjC de cette build
précise. En `latest`, une release amont peut casser la compilation sans qu'une ligne du repo ait
bougé — indiscernable d'une régression qu'on aurait introduite. Pour monter de version :
`workflow_dispatch` avec `webrtc_release: latest`, constater, puis épingler le nouveau tag.

Slices réelles de 153.0.0 (relevées dans l'archive, pas devinées) :
`ios-arm64` (device), `ios-x86_64_arm64-simulator` (simulateur — noter l'ordre des archis, qui
diffère du nommage Apple habituel), plus `ios-x86_64_arm64-maccatalyst` et `macos-x86_64_arm64`
qu'on n'utilise pas. Le workflow les localise par motif (`*simulator*`, `/ios-arm64/…$`) plutôt
qu'en dur, précisément parce que ces noms varient d'une build à l'autre.

Astuce vérification sans macOS : l'archive fait ~45 Mo et contient les entêtes ObjC. La
télécharger et lire `Headers/RTCPeerConnection.h` permet de trancher un doute de nommage
cinterop **sans payer de run** — c'est comme ça que les deux erreurs du 1er run ont été
diagnostiquées.

## 4. Brancher via SKIE (déjà généré en 1)
- `import Shared` puis remplacer les `TODO(SKIE)` de `LiveViewModel.swift`/`PairingViewModel.swift` :
  - `for await s in manager.state { … }` (Flow→AsyncSequence)
  - `try await handshake.complete(peerPublicKey: pub)` (suspend→async)
  - `switch payload { case .snapshot: … }` (sealed→enum Swift)

## 5. Lancer
Cmd+R au simulateur, ou `xcodebuild -scheme Bubble -destination 'generic/platform=iOS Simulator'`
(destination générique : ne dépend d'aucun nom de device, qui varie d'un Xcode à l'autre).

## Résumé : ce qui reste vraiment
Aucune logique métier. Uniquement : lancer la recette sur macOS + écrire `IosPeerLink`,
`IosPairedIdentityStore`, et les vues SwiftUI des jeux (mécanique, guidée par les pendants Android).
