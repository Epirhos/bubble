# CI — ce qui tourne, ce que ça coûte, comment ne pas payer deux fois

Trois workflows, un principe : **tout ce qui peut échouer sur Linux échoue sur Linux.**
Une minute de runner macOS est facturée **10x** une minute Linux (Windows : 2x). Un run iOS
raté sur une faute qu'un runner Linux aurait attrapée, c'est de l'argent brûlé.

| Fichier | Déclencheur | Runner | Rôle |
|---|---|---|---|
| `.github/workflows/ci.yml` | push `main`, **toute PR** | ubuntu (1x) | tests moteur + APK debug + smoke test du relai |
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

Les erreurs de cinterop (nom de classe / de méthode ObjC absent) dépendent de la build WebRTC.
Si `latest` a bougé et casse, relancer **`workflow_dispatch`** en renseignant `webrtc_release`
avec le dernier tag connu bon, puis figer ce tag une fois vert.

## Pièges déjà désamorcés (ne pas les réintroduire)

- **`| xcpretty || true`** : masquait l'échec du build. Un run vert qui ne prouve rien coûte
  autant qu'un run rouge. Le `xcodebuild` est maintenant sous `set -o pipefail`, sans filet.
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
- **CRLF sur `gradlew`** : `.gitattributes` force LF, sinon le runner macOS répond
  `bad interpreter: ^M`.

## Mises à jour (Dependabot)

Groupes volontairement serrés : **Kotlin, SKIE et le plugin Compose montent ensemble** —
SKIE est publié pour une version exacte de Kotlin, les séparer casse la compilation.
Après un merge de `kotlin-toolchain`, lancer `ios.yml` à la main : c'est le seul groupe qui
peut casser le cinterop et SKIE sans que le job Linux s'en aperçoive.

## Modèles MediaPipe

`androidApp/src/main/assets/*.task` (~11 Mo) sont hors git. `./scripts/fetch-models.sh` les
récupère pour un lancement sur appareil. La CI ne les télécharge pas : son APK sert à valider
la compilation, pas à être installé.
