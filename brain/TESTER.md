# TESTER — faire tourner Bubble pour de vrai

Bubble est une application à deux personnes : rooms strictes de 2, WebRTC pair-à-pair, chiffrement
de bout en bout. Cela veut dire qu'aucun test sérieux ne se fait avec une seule instance. Il faut
toujours deux clients et un relai de signalisation joignable par les deux.

Ce document décrit trois niveaux, du moins coûteux au plus coûteux. Les coûts et les contraintes
de la CI sont dans `/brain/CI.md` ; la recette de compilation iOS est dans `/brain/IOS_BUILD.md`.

## Niveau 1 — le moteur, sans aucun appareil

C'est gratuit, instantané, et cela couvre la partie la plus difficile du projet.

```bash
./gradlew :shared:jvmTest
```

68 tests passent actuellement. Ils valident le chiffrement de bout en bout (ChaCha20-Poly1305,
X25519, poignée de main de pairage), la machine à états, le moteur de purge, l'ordonnanceur,
la politique d'échantillonnage adaptatif et les déclencheurs de gestes. Le rapport lisible est
dans `shared/build/reports/tests/jvmTest/index.html`.

Autrement dit, toute la logique métier est déjà vérifiée en isolation. Ce qui reste à éprouver sur
appareil, c'est l'intégration : le matériel, le réseau réel, et l'interface.

## Niveau 2 — Android

### Ce qu'il faut savoir avant de commencer

Le relai de développement écoute en `ws://` non chiffré. Depuis `targetSdk 28`, Android interdit
le trafic en clair par défaut, et la connexion échouerait sans rien afficher dans l'interface.
C'est pourquoi `androidApp/src/debug/AndroidManifest.xml` autorise le clair **pour la variante
debug uniquement** ; la release reste stricte.

Les modèles MediaPipe (`androidApp/src/main/assets/*.task`, environ 11 Mo) ne sont pas dans git.
Après un clone frais, il faut les récupérer :

```bash
./scripts/fetch-models.sh
```

### Préparer la machine qui héberge le relai

Relève l'adresse de ta machine sur le réseau local. Sous Windows, `ipconfig` puis l'adresse IPv4
de l'adaptateur Wi-Fi ; sur un Mac, `ipconfig getifaddr en0`.

Ouvre le port 8787 en entrée, sinon le téléphone ne joindra jamais le relai. Sous Windows, dans un
terminal administrateur :

```powershell
netsh advfirewall firewall add rule name="Bubble signaling" dir=in action=allow protocol=TCP localport=8787
```

Renseigne cette adresse dans `androidApp/.../BubbleApplication.kt`, constante
`DEV_SIGNALING_URL` : `ws://192.168.X.Y:8787`. Utiliser l'IP du réseau local plutôt que
`10.0.2.2` présente un avantage : elle fonctionne **aussi** depuis un émulateur, dont le trafic
sort par la machine hôte. Un même réglage sert donc aux deux types de clients.

Lance le relai et laisse-le tourner :

```bash
cd server && npm start      # écoute sur 0.0.0.0:8787, zéro persistance
```

### Installer sur deux clients

Active le débogage USB sur les téléphones, branche-les, et vérifie qu'ils sont vus :

```bash
adb devices
./gradlew :androidApp:installDebug          # installe sur tout ce qui est connecté
adb -s <serial> install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

Si tu n'as qu'un seul téléphone, un émulateur peut servir de second client, avec une réserve
importante décrite juste en dessous.

### La réserve sur les émulateurs x86_64

L'APK n'embarque **pas** `libmediapipe_tasks_vision_jni.so` pour l'ABI x86_64 ; cette
bibliothèque n'existe que pour `arm64-v8a`, `armeabi-v7a` et `x86`. Sur un émulateur x86_64, la
couche vision ne peut donc pas se charger.

Depuis le passage de `AndroidGestureRecognizer.start()` en `catch (e: Throwable)`, cela ne fait
plus planter l'application : l'`UnsatisfiedLinkError` est capturé, un avertissement part dans
logcat, et Bubble continue sans détection de gestes. Un émulateur reste donc utilisable pour
tester le pairage, le lien chiffré et l'interface — mais **jamais les gestes ni le visage**, qui
exigent un appareil arm64.

WebRTC (`libjingle_peerconnection_so.so`) et le scan de QR de ML Kit (`libbarhopper_v3.so`) sont,
eux, bien présents en x86_64 : tout le chemin réseau et la cérémonie de pairage fonctionnent.

### Dérouler le test

Lance l'application sur les deux clients et accorde la permission caméra. Tant que l'état est
`Unpaired`, `MainActivity` affiche `PairingScreen` : un client montre son QR, l'autre le scanne.
La poignée de main X25519 s'effectue ensuite via le relai, puis l'écran du Portail
(`LiveOverlayScreen`) prend le relais.

Pour suivre ce qui se passe :

```bash
adb logcat --pid=$(adb shell pidof com.bubble.app)
```

### Déclencher les cycles de 21 h et 23 h 30 sans attendre

Le montage du Daily Bubble et la purge sont des `OneTimeWorkRequest` à délai, qui se réarment
d'eux-mêmes via `DailyBubbleScheduler.scheduleNextGeneration`. Les forcer ne casse donc pas le
cycle.

```bash
adb shell dumpsys jobscheduler | grep -A20 com.bubble.app     # relever le jobId
adb shell cmd jobscheduler run -f com.bubble.app <jobId>
```

## Niveau 3 — iPhone

### Un Mac est obligatoire

Déployer sur un iPhone demande Xcode, donc un Mac. La CI GitHub compile l'application mais ne
l'exécute jamais, et un simulateur n'a pas de caméra. Contrainte supplémentaire établie par la
CI : le `Shared.xcframework` ne contient que des tranches **arm64**, donc le simulateur exige un
**Mac Apple Silicon** (un Mac Intel demanderait d'ajouter la cible `iosX64()` dans
`shared/build.gradle.kts`).

Un iPhone physique est de toute façon préférable au simulateur, qui n'a ni caméra ni moteur
haptique.

### La recette sur le Mac

Le `WebRTC.xcframework` n'est pas dans git ; la CI le télécharge, et il faut faire de même en
local. On épingle la même version que la CI pour rester cohérent :

```bash
URL=$(curl -fsSL https://api.github.com/repos/stasel/WebRTC/releases/tags/153.0.0 \
  | grep browser_download_url | grep -i xcframework.zip | head -1 | cut -d'"' -f4)
curl -fL -o webrtc.zip "$URL" && mkdir -p iosApp/Vendor && unzip -q webrtc.zip -d iosApp/Vendor

SIM=$PWD/iosApp/Vendor/WebRTC.xcframework/ios-x86_64_arm64-simulator
DEV=$PWD/iosApp/Vendor/WebRTC.xcframework/ios-arm64

./gradlew :shared:assembleSharedReleaseXCFramework \
  -Pwebrtc.framework.dir.iosArm64="$DEV" \
  -Pwebrtc.framework.dir.iosSimulatorArm64="$SIM"

brew install xcodegen
cd iosApp && xcodegen generate && open Bubble.xcodeproj
```

Dans Xcode, sélectionne un Signing Team (un simple Apple ID suffit, avec une signature valable
sept jours), choisis ton iPhone comme destination, puis lance avec Cmd+R.

### Deux réglages propres à iOS

Renseigne l'adresse de ta machine dans `iosApp/Bubble/AppGraph.swift`, constante `devLanHost`.
Les builds Debug sur appareil réel l'utilisent ; le simulateur, lui, passe par `127.0.0.1`.

**App Transport Security reste à traiter.** iOS refuse le trafic en clair, donc un `ws://` vers
une IP privée sera bloqué tant que l'`Info.plist` ne porte pas `NSAppTransportSecurity` avec
`NSAllowsLocalNetworking = true`. Cette clé est un dictionnaire imbriqué, que les réglages
`INFOPLIST_KEY_*` de XcodeGen ne savent pas exprimer : il faut déclarer un bloc `info:` explicite
dans `iosApp/project.yml`. Deux façons de s'en sortir :

- ajouter ce bloc `info:` en reprenant les clés aujourd'hui fournies par `INFOPLIST_KEY_*`
  (caméra, écran de lancement, réseau local) ;
- ou servir le relai de développement en `wss://` avec un certificat de confiance, ce qui rend
  toute exception inutile.

La permission de réseau local, elle, est déjà en place :
`INFOPLIST_KEY_NSLocalNetworkUsageDescription` est déclarée, sans quoi iOS ne pourrait même pas
afficher la demande de consentement et la connexion échouerait en silence.

## Ce qu'aucun de ces niveaux ne prouve

Deux clients sur le même réseau, ou deux émulateurs sur la même machine, ne testent pas la
traversée de NAT entre deux réseaux distincts, même en passant par STUN. Le moteur haptique ne
peut s'évaluer que sur un appareil réel — `minSdk 26` existe précisément pour les amplitudes de
`VibrationEffect`. Enfin, la purge de 23 h 30 sur des fuseaux horaires différents demande deux
appareils réglés sur deux fuseaux.
