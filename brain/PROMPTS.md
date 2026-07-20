# PROMPTS — Règles de style et de session

## Protocole de session (économie de tokens)
1. Au début de chaque session : lire uniquement les fichiers `/brain` pertinents pour la tâche (pas tout le repo).
2. Ne jamais re-générer un fichier existant en entier pour une petite modification — éditer chirurgicalement.
3. Une tâche = un périmètre. Ne pas toucher aux modules hors périmètre annoncé.
4. Toute décision d'architecture nouvelle doit être consignée dans le fichier `/brain` concerné (1–3 lignes), pas dans les commentaires de code.

## Style Kotlin (module `shared`)
- Kotlin pur en `commonMain` : aucune dépendance Android/iOS/UI.
- États : `sealed interface` + `data class` immuables ; exposition via `StateFlow` uniquement.
- Frontières plateforme : `expect/actual` fins (une capacité = une interface) ; jamais de `expect class` fourre-tout.
- Coroutines : `Dispatchers` injectés (testabilité), pas de `GlobalScope`, structured concurrency partout.
- Erreurs : types `Result`/sealed d'erreurs métier ; les exceptions ne traversent pas la frontière du module.
- Nommage : intentions métier (`EchoQueue`, `PurgeEngine`, `AuraEmitter`), pas de suffixes techniques vides (`Manager`, `Helper`, `Util`).

## Style Swift / SwiftUI
- SwiftUI pur, iOS 17+ : `@Observable`, pas de UIKit sauf nécessité (haptique CoreHaptics OK).
- Le ViewModel Swift est un adaptateur mince sur les flows KMP (via SKIE ou wrapper Combine) — zéro logique métier côté Swift.
- WidgetKit : timelines minimales, rendu depuis l'App Group uniquement, aucun appel réseau dans l'extension.

## Style Compose / Android
- Compose + Glance pour le widget ; Material 3, thème "calm" (animations lentes, palettes chaudes désaturées).
- Un seul module app ; les composables sont stateless, l'état vient des `StateFlow` du module `shared`.
- Background : WorkManager pour purge/scheduling, jamais de service foreground permanent.

## Invariants produit (à ne JAMAIS violer dans le code)
- Aucun statut "Vu", aucun accusé de lecture, aucun read receipt — sous aucune forme.
- Aucune image nette ne transite vers le widget : le flou est appliqué côté émetteur.
- Tout payload réseau est E2EE ; le serveur ne stocke que des blobs opaques à TTL.
- Toute IA (gestes, visage) tourne on-device ; aucun frame envoyé à un service tiers.
- La purge respecte ses exceptions (Daily Bubble non visionné, clés, préférences).
- Calm Technology : pas de notifications sonores/banner par défaut, pas de compteurs, pas de gamification.

## Definition of Done (par tâche)
- Compile sur les deux cibles (ou le sous-ensemble touché).
- Logique métier nouvelle en `commonMain` accompagnée de tests unitaires Kotlin.
- Invariants produit ci-dessus vérifiés mentalement avant de conclure.
