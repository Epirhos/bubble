# STATE_MACHINE — Bubble

Machine à états unique, implémentée dans `shared/core` (`BubbleState` sealed interface + `BubbleStateManager`, exposée en `StateFlow`). Les UI natives ne font que rendre l'état courant.

## Décisions d'implémentation (étape 2 — figées)
- **États exclusifs** : `Unpaired`, `Ambient`, `Live`, `PurgePending`, `Purging` uniquement.
- **`EchoPending` et `DailyBubbleReady` ne sont PAS des états exclusifs** : des échos peuvent être en attente pendant Ambient/Live, et le Daily Bubble peut être prêt dans n'importe quel état. Ils sont modélisés comme flux orthogonaux : `EchoQueue.pending` et `dailyBubble: StateFlow<DailyBubbleStatus>`. Idem `AuraGlow` (StateFlow séparé, décroissance 15 min calculée au rendu).
- **Purge pendant Live (cas limite)** : si l'instant de purge tombe pendant une session Live, on ne coupe jamais le moment partagé → flag interne `purgeRequested`, transition vers `PurgePending` à la fin de la session Live seulement.
- **`PurgePending`** = instant atteint, exécution pas encore lancée (attente fin de Live ou réveil de la tâche background). `Purging` = PurgeEngine en cours.
- L'`EchoQueue` est bornée (8) et dédupliquée (fenêtre 2 s) : une rafale de bisous ne devient pas une mitraillette haptique au déverrouillage.

## États

### 1. `Unpaired`
- Aucune clé partenaire. Seul écran : pairage (échange de clé X25519 via QR **ou code numérique** — `PairingMethod`).
- **Lien strictement 1:1** : `onPaired(session)` retourne `false` si un lien existe déjà. La désaffiliation (`onUnpaired()`) est un préalable obligatoire à tout nouveau lien ; elle efface immédiatement tout l'état en mémoire.
- **Rituels d'animation** (flux `pairingEvents`, chorégraphie côté UI natives) :
  * Affiliation réussie → `PairingEvent.BubblesMerged` : deux bulles se rejoignent et fusionnent en une seule.
  * Rupture du lien → `PairingEvent.BubbleSplit` : la bulle se scinde en deux bulles qui s'éloignent.
- Transition → `Ambient` après handshake vérifié.

### 2. `Ambient` (état par défaut, ~95% du temps)
- Widget affiche : dernier snapshot flouté OU canvas courant + teinte d'Aura.
- Sous-état `AuraGlow(intensity)` : le partenaire est actif sur son téléphone → lueur chaude. Décroît vers neutre après inactivité. Pas de notification, jamais.
- Événements entrants traités silencieusement : nouveau snapshot, nouveau canvas (écrasement), signal geste (bisou/cœur → haptique si téléphone actif, sinon → `EchoPending`).

### 3. `EchoPending` (Écho Haptique)
- Un signal haptique est arrivé téléphone verrouillé/inactif → stocké en file locale (chiffrée, max N impulsions, dédupliquées).
- Au prochain déverrouillage : lecture de la file → déclenchement haptique → retour `Ambient`.
- Aucun accusé de réception envoyé à l'émetteur.

### 4. `Live` (Portail)
- Entré depuis le widget (frottement → deep link) ou depuis l'app.
- Frottement tactile = défloutage progressif (rayon local au doigt).
- `GestureDetector` actif : bisou/cœur détecté → envoi signal au partenaire.
- `FaceWatchdog` : timer 120s sans visage → coupure automatique du flux → `Ambient`.
- Sortie app / background → `Ambient`.

### 5. Cycle Daily Bubble — flux orthogonal `dailyPhase: StateFlow<DailyPhase>` (étape 7)
Pas un état exclusif : la génération 21h ne coupe pas une session Live, la purge s'y superpose.
- `IDLE` : journée normale, ou page blanche après visionnage.
- `GENERATING_SUMMARY` : 21h, le compresseur natif assemble le time-lapse (worker de fond).
- `PURGE_READY` : instant de purge (23h30 fuseau le plus tardif) atteint, exécution imminente.
- `PERSISTED_UNWATCHED` : post-purge, le montage NON visionné est jalousement conservé (chiffré).
- Badge widget via `dailyBubble.exists`. Visionnage → `viewed=true` → si PERSISTED, suppression
  définitive du fichier chiffré puis retour IDLE (page blanche). Transitions dans `BubbleStateManager` ;
  orchestration fichiers dans `media/DailyBubbleCoordinator` (commonMain) + natifs.

### 6. `Purging`
- Déclenché à 23h30 **au fuseau le plus tardif du couple** (voir DATA_FLOW.md).
- Supprime : snapshots, canvas, files haptiques consommées, caches, blobs relais serveur.
- **Exception absolue** : Daily Bubble avec `viewed=false` est conservé (localement) jusqu'à visionnage.
- Fin de purge → `Ambient` (widget repasse à l'état neutre/canvas vide).

## Table de transitions (résumé)

| Depuis        | Événement                          | Vers              |
|---------------|------------------------------------|-------------------|
| Unpaired      | Handshake pairing OK               | Ambient           |
| Ambient       | Ouverture portail (frottement)     | Live              |
| Ambient       | Haptique reçu, device inactif      | EchoPending       |
| EchoPending   | Déverrouillage device              | Ambient (après replay) |
| Ambient/Live  | 21h00 locale                       | DailyBubbleReady  |
| Live          | 120s sans visage / background      | Ambient           |
| *tout état pairé* | 23h30 (fuseau le plus tardif)  | Purging           |
| Purging       | Purge terminée                     | Ambient           |
| *tout état*   | Unpair / clé révoquée              | Unpaired (+ purge totale immédiate) |

## Invariants
- Jamais de transition vers un état qui émettrait un "Vu".
- `Purging` ne supprime jamais un Daily Bubble non visionné ni les clés de pairage.
- `Live` ne peut exister sans visage détecté au-delà de 120s.
