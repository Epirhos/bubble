import SwiftUI

/// Vues des Jeux de Complicité — pendant SwiftUI de PlayfulOverlays.kt.
/// Aucune interface de chat : uniquement des surcouches sur le Canvas/Aura/Haptique.

/// Amorce de jeu en filigrane GRAVÉ dans le verre (pas un pop-up bloquant) :
/// flou léger + faible opacité. Les partenaires répondent en dessinant par-dessus.
struct PromptWatermark: View {
    let text: String?

    var body: some View {
        if let text {
            Text(text)
                .font(.system(size: 26, weight: .light, design: .rounded))
                .foregroundStyle(BubblePalette.moon.opacity(0.28))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)
                .blur(radius: 0.7)
                .transition(.opacity)
                .allowsHitTesting(false) // on écrit PAR-DESSUS, la gravure ne capte rien
        }
    }
}

/// Révélation d'un coupon (mini-défi) : pilule de verre en haut. Tap → évaporation.
struct CouponReveal: View {
    let text: String?
    let onRead: () -> Void

    var body: some View {
        if let text {
            Button(action: onRead) {
                Text(text)
            }
            .buttonStyle(GlassPillStyle(glow: BubblePalette.auraWarm))
            .padding(.top, 20)
            .transition(.opacity)
        }
    }
}
