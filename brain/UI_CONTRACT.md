# UI_CONTRACT — Bubble (contrat strict, non négociable)

1. **Immersif & full-bleed** : le flux/canvas = 100 % de l'écran, edge-to-edge, barres système transparentes (`enableEdgeToEdge` + thème transparent / `.ignoresSafeArea()` + `.persistentSystemOverlays(.hidden)`).
2. **Zéro barre** : pas de Top/BottomAppBar, pas de navigation classique. Contrôles = pilules de verre flottantes uniquement (`GlassPill` / `GlassPillStyle`).
3. **Glassmorphism dark natif** : fond translucide + liseré blanc 15 % + reflet. iOS = `.ultraThinMaterial` (vrai backdrop blur) ; Android = simulation gradient (RenderEffect API 31+ quand un vrai flux existera). Palette `BubbleColors`/`BubblePalette` : Night #0A0A0F, AuraWarm #FFB27A, AuraRose #F48FB1, Moon #E8E6F0.
4. **Aura & Glow** : les retours d'état sont des halos (radial gradients animés), JAMAIS du texte/badge/compteur. Pulse spring sur geste reçu, lueur persistante ∝ présence du partenaire.
5. **Geste plein écran** : tout l'écran capte le toucher (pointerInput plein écran / `contentShape(Rectangle())`), aucune zone cliquable exclusive.

## Défloutage par frottement — modèle (identique sur les deux OS)
Masque de révélation : `M(p,t) = clamp01( Σᵢ G(‖p−cᵢ‖/r) · H(t−tᵢ) )`
- Empreintes `cᵢ` : ré-échantillonnées à `r/3` le long du doigt (r = 96 dp/pt).
- `G` : atténuation radiale (dégradé 1 → 0.55 → 0, centre → bord).
- `H` (cicatrisation, éphémérité) : plateau 1,8 s puis ease-out quadratique `(1−t)²` sur 1,4 s — le flou "repousse" ; les empreintes mortes sont purgées de la liste.
- Opacité du voile en p : `voile × (1 − M)`.
- GPU : voile composé hors écran (CompositingStrategy.Offscreen / compositingGroup), empreintes dessinées en `BlendMode.DstOut`/`.destinationOut` → somme et clamp gratuits par blending. Horloge : `withFrameMillis` (ne tourne que si empreintes vivantes) / `TimelineView(.animation(paused:))`.

## Rituels de pairage (chorégraphie UI des PairingEvent du moteur)
- `BubblesMerged` : deux bulles (rose/ambre) convergent en spring LowBouncy, halo de fusion ∝ proximité, fondu de sortie 0,9 s.
- `BubbleSplit` : même scène, temps inversé (0→apart).
- Fichiers : `PairingRitualOverlay.kt` / `PairingRitualView.swift`.

## Branchement KMP
- Android : `BubbleApplication` instancie le manager (scope Main.immediate) ; `LiveRoute` ouvre le portail au montage et `finish()` quand l'état repasse Ambient ; `immediateHaptics` → pulse de glow (la vibration est jouée par le manager).
- iOS : `LiveViewModel` = adaptateur mince (TODO SKIE : StateFlow → AsyncSequence) ; mêmes règles.
- Deep link widget : `bubble://live` (manifest Android ; Universal Link iOS à venir).
