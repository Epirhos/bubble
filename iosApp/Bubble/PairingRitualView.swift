import SwiftUI

/// Rituels du lien (invariant : lien 1:1 strict, rupture obligatoire avant un nouveau
/// lien — appliqué par le moteur KMP, chorégraphié ici) :
///  - `.bubblesMerged` : deux bulles glissent l'une vers l'autre (spring, léger rebond)
///    et fusionnent en une seule dans un halo chaud.
///  - `.bubbleSplit` : la bulle se scinde, les deux moitiés s'éloignent et s'éteignent.
///
/// `progress` 0→1 pilote toute la scène — uniquement des interpolations, aucune étape.
struct PairingRitualView: View {
    var ritual: PairingRitual?

    @State private var progress: CGFloat = 0
    @State private var opacity: Double = 0

    var body: some View {
        GeometryReader { geo in
            if ritual != nil {
                let radius = min(geo.size.width, geo.size.height) * 0.11
                let apart = geo.size.width * 0.30
                let centerY = geo.size.height * 0.42
                // MERGE : apart→0 ; SPLIT : 0→apart (même scène, temps inversé).
                let distance = ritual == .bubblesMerged ? apart * (1 - progress) : apart * progress
                let closeness = 1 - min(1, distance / apart)

                ZStack {
                    // Halo de fusion : culmine quand les bulles se touchent.
                    RadialGradient(
                        colors: [BubblePalette.auraWarm.opacity(0.35 * closeness * opacity), .clear],
                        center: .center, startRadius: 0, endRadius: radius * 3.2
                    )
                    .frame(width: radius * 6.4, height: radius * 6.4)
                    .position(x: geo.size.width / 2, y: centerY)

                    bubble(tint: BubblePalette.auraRose, radius: radius)
                        .position(x: geo.size.width / 2 - distance, y: centerY)
                    bubble(tint: BubblePalette.auraWarm, radius: radius)
                        .position(x: geo.size.width / 2 + distance, y: centerY)
                }
                .opacity(opacity)
                .allowsHitTesting(false)
            }
        }
        .ignoresSafeArea()
        .onChange(of: ritual) { _, newRitual in
            guard newRitual != nil else { return }
            progress = 0
            opacity = 1
            withAnimation(.spring(response: 0.9, dampingFraction: 0.65)) { progress = 1 }
            withAnimation(.easeOut(duration: 0.9).delay(1.1)) { opacity = 0 }
        }
    }

    private func bubble(tint: Color, radius: CGFloat) -> some View {
        Circle()
            .fill(
                RadialGradient(
                    colors: [.white.opacity(0.55), tint.opacity(0.30), .clear],
                    center: .center, startRadius: 0, endRadius: radius
                )
            )
            .frame(width: radius * 2, height: radius * 2)
    }
}
