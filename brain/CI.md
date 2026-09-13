# CI — ce qui tourne, ce que ça coûte, comment ne pas payer deux fois

Trois workflows, un principe : **tout ce qui peut échouer sur Linux échoue sur Linux.**
Une minute de runner macOS est facturée **10x** une minute Linux (Windows : 2x). Un run iOS
raté sur une faute qu'un runner Linux aurait attrapée, c'est de l'argent brûlé.

| Fichier | Déclencheur | Runner | Rôle |
|---|---|---|---|
| `.github/workflows/ci.yml` | push `main`, **toute PR** (hors docs) | ubuntu (1x) | tests moteur + APK debug + smoke test du relai |
| `.github/workflows/ios.yml` | push `main` (chemins iOS), manuel | ubuntu puis macOS (10x) | XCFramework SKIE + app iOS simulateur |
| `.github/dependabot.yml` | hebdo / mensuel | — | mises à jour groupées |

## Le budget, concrètement

- **`concurrency: cancel-in-progress`** sur les deux workflows : trois pushs d'affilée
  n'engagent qu'un seul run. C'est le réglage qui économise le plus.
- **`timeout-minutes`** sur chaque job (45 / 75). Sans ça, un job bloqué tourne jusqu'à
  **6 heures** — sur macOS c'est l'équivalent de 60 h Linux facturées pour rien.
- **`needs: precheck`** dans `ios.yml` : le runner macOS n'est réservé que si les tests du
  moteur passent sur Linux.
- **iOS ne se déclenche pas sur les PR.** Les PR Dependabot ne coûtent donc que du Linux.
- **`paths:`** sur `ios.yml` : toucher `androidApp/` ou `server/` ne réveille pas macOS.
- **Caches** : `~/.gradle` (setup-gradle), `~/.konan` (~1 Go de toolchain LLVM, sinon
  retéléchargé à chaque run), et `iosApp/Vendor` (WebRTC.xcframework, clé = tag de release).
- **`paths-ignore` sur `ci.yml`** : un commit qui ne touche que des `.md`, `brain/` ou le
  `.gitignore` ne déclenche rien. Corollaire à ne pas oublier : ne pas ériger CI en status check
  obligatoire, un run « skipped » bloquerait la fusion d'une PR purement documentaire.
- **`assembleSharedReleaseXCFramework`** et non `assembleSharedXCFramework` : ce dernier
  linke aussi la variante debug, soit ~2x le temps Kotlin/Native, pour un artefact que
  `project.yml` ne consomme jamais.

## Vérifier en local avant de pousser (gratuit)

Le job Linux se reproduit intégralement sur la machine de dev — c'est le meilleur
investissement anti-facture :

```bash
./gradlew :shared:jvmTest          # commonTest, donc aussi la logique iOS partagée
./gradlew :androidApp:assembleDebug
cd server && npm ci && npm run smoke
```

La CI tourne sur **JDK 21**. Sous Windows, le JBR d'Android Studio fait l'affaire :

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
```

Ce qui **ne peut pas** être vérifié localement depuis Windows : le cinterop WebRTC, le link
Kotlin/Native, SKIE, et tout le Swift. C'est exactement le périmètre du job macOS — et la
raison pour laquelle ce job est écrit pour livrer un maximum de diagnostics par run.

## Un run macOS rate : quoi faire avant d'en relancer un

Le workflow est conçu pour qu'un seul run suffise à comprendre. Dans l'ordre :

1. **Résumé du run** — la version exacte de WebRTC utilisée y est écrite.
2. **Artefact `logs-xcode`** — `xcodebuild.log` complet + le `project.pbxproj` généré.
3. **Étape « Environnement du runner »** — version de macOS, d'Xcode, du SDK simulateur.
4. **Le log `xcodebuild` est filtré pour toi** : la console affiche les lignes `error:`/
   `warning:`, le verdict `** BUILD … **` et les 40 dernières lignes. Le log brut (des dizaines
   de milliers de lignes, où l'erreur se noie) part dans l'artefact.

### Diagnostiquer un problème de cinterop SANS payer de run

C'est la technique qui a résolu le 1er échec. `WebRTC.xcframework` fait ~45 Mo et **contient les
entêtes Objective-C**. Les télécharger et les lire tranche n'importe quel doute de nommage,
gratuitement, depuis Windows :

```bash
# 1. recuperer l'URL de l'asset, 2. le telecharger, 3. n'extraire que les entetes
URL=$(curl -fsSL https://api.github.com/repos/stasel/WebRTC/releases/tags/153.0.0 \
  | grep browser_download_url | grep -i xcframework.zip | head -1 | cut -d'"' -f4)
curl -fL -o webrtc.zip "$URL"
# puis : unzip -q webrtc.zip "WebRTC.xcframework/ios-arm64/WebRTC.framework/Headers/*"
#        grep -B8 dataChannelForLabel Headers/RTCPeerConnection.h
```

Vérifier en particulier si la méthode est déclarée dans une **catégorie** (`@interface X (Nom)`)
plutôt que sur l'interface principale : c'est la source d'erreur la plus sournoise (voir plus bas).

La version WebRTC est **épinglée** (`WEBRTC_RELEASE` dans `ios.yml`). Pour en essayer une autre :
`workflow_dispatch` avec `webrtc_release: latest` ou un tag précis, puis épingler une fois vert.

## Pièges déjà désamorcés (ne pas les réintroduire)

- **`| xcpretty || true`** : masquait l'échec du build. Un run vert qui ne prouve rien coûte
  autant qu'un run rouge. L'étape capture désormais le statut de `xcodebuild` dans `$STATUT` et
  se termine par `exit $STATUT` : le filtrage du log ne peut plus avaler l'échec. (Un `| tee`
  naïf ne suffisait pas non plus : sous `pipefail`, un `grep` sans correspondance aurait fait
  rougir un build sain.)
- **`-destination 'platform=iOS Simulator,name=iPhone 15'`** : le nom du device disparaît
  d'une image d'Xcode à l'autre → `generic/platform=iOS Simulator`, qui ne boote rien.
- **API GitHub sans jeton** : 60 requêtes/heure **partagées entre tous les runners** de
  l'IP. Le `Authorization: Bearer ${{ github.token }}` monte à 5000/h.
- **`Shared.xcframework` avec `embed: true`** : le framework Kotlin est statique
  (`isStatic = true`), il est fondu au link. L'embarquer casse codesign/validation.
- **`Shared.xcframework` lié à `BubbleWidget`** : l'extension n'importe pas `Shared` et ne
  lie pas WebRTC ; les symboles WebRTC du cinterop seraient alors indéfinis au link.
- **Widget sans `NSExtensionPointIdentifier`** : Xcode refuse d'embarquer l'extension.
- **`org.gradle.jvmargs=-Xmx2g`** : suffisant pour Android, pas pour le link Kotlin/Native
  + SKIE → 4 Go.
- **Méthode ObjC issue d'une CATÉGORIE** : Kotlin/Native traduit les catégories en **fonctions
  d'extension**, et importer la classe n'apporte pas ses extensions. `dataChannelForLabel`
  (catégorie `RTCPeerConnection (DataChannel)`) échouait en « Unresolved reference » alors que
  tout le reste résolvait. D'où `import webrtc.*` plutôt que 18 imports nommés — un import large
  est ici le choix robuste, pas de la paresse : il couvre les catégories futures.
- **Deux sélecteurs ObjC, une seule signature Kotlin** : `-peerConnection:didAddStream:` et
  `-peerConnection:didRemoveStream:` se projettent tous deux sur
  `(RTCPeerConnection, RTCMediaStream)` ; seul le nom du paramètre change, ce qui ne distingue
  pas deux surcharges → `@ObjCSignatureOverride` sur les deux.
- **`iosMain` n'est PAS vérifiable sous Windows.** `kotlin.native.ignoreDisabledTargets=true`
  désactive les cibles natives : `compileIosMainKotlinMetadata` ressort **SKIPPED** et le build
  est « SUCCESSFUL » sans avoir lu une ligne d'`iosMain`. Ne jamais prendre ce vert pour une
  validation du code iOS.
- **CRLF sur `gradlew`** : `.gitattributes` force LF, sinon le runner macOS répond
  `bad interpreter: ^M`.
- **`generic/platform=iOS Simulator` sans `ARCHS=arm64`** — le piège le plus trompeur
  rencontré. `shared/build.gradle.kts` ne déclare que `iosArm64` + `iosSimulatorArm64` : le
  `Shared.xcframework` n'a **aucune tranche x86_64**. Mais la destination générique compile
  arm64 **et** x86_64. En x86_64, `import Shared` réussit quand même — l'entête ObjC générée
  est indépendante de l'archi — alors que les conformances `AsyncSequence` de SKIE vivent dans
  un module **Swift** compilé, lui arch-spécifique. Résultat : les types SKIE sont visibles
  *sans* leur conformance, et chaque `for await` échoue sur
  `requires 'SkieKotlinStateFlow<…>' to conform to 'AsyncSequence'`. On croit à un problème de
  SKIE ou de bindings ; c'est une architecture manquante. Le tell est en fin de log :
  `note: … is missing architecture(s) required by this target (x86_64)`, et **tous** les
  `SwiftCompile` en échec portent `normal x86_64`. Un dev sur Mac Apple Silicon ne le voit
  jamais (en Debug, Xcode ne compile que l'archi active).

## Mises à jour (Dependabot)

Groupes volontairement serrés : **Kotlin, SKIE et le plugin Compose montent ensemble** —
SKIE est publié pour une version exacte de Kotlin, les séparer casse la compilation.
Après un merge de `kotlin-toolchain`, lancer `ios.yml` à la main : c'est le seul groupe qui
peut casser le cinterop et SKIE sans que le job Linux s'en aperçoive.

## Faire tourner l'app sur un appareil

La CI compile ; elle n'exécute rien. Pour éprouver Bubble sur un téléphone Android ou un iPhone,
voir **/brain/TESTER.md** — deux clients sont toujours nécessaires (rooms strictes de 2).

## Modèles MediaPipe

`androidApp/src/main/assets/*.task` (~11 Mo) sont hors git. `./scripts/fetch-models.sh` les
récupère pour un lancement sur appareil. La CI ne les télécharge pas : son APK sert à valider
la compilation, pas à être installé.
