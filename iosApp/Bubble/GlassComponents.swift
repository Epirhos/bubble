import SwiftUI

/// Palette "calm" — miroir de BubbleColors (Compose).
enum BubblePalette {
    static let night = Color(red: 0.039, green: 0.039, blue: 0.059)
    static let nightHigh = Color(red: 0.078, green: 0.070, blue: 0.118)
    static let auraWarm = Color(red: 1.0, green: 0.698, blue: 0.478)
    static let auraRose = Color(red: 0.957, green: 0.561, blue: 0.694)
    static let moon = Color(red: 0.910, green: 0.902, blue: 0.941)
}

/// Pilule de verre dépoli : seule forme de contrôle autorisée (aucune barre, rien d'opaque).
/// Sur iOS le verre est natif : `.ultraThinMaterial` fait un vrai backdrop blur.
struct GlassPillStyle: ButtonStyle {
    var glow: Color = .clear

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 14, weight: .regular, design: .rounded))
            .foregroundStyle(BubblePalette.moon.opacity(0.8))
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
            .background(.ultraThinMaterial, in: Capsule())
            .overlay(Capsule().strokeBorder(.white.opacity(0.15), lineWidth: 1))
            .shadow(color: glow.opacity(0.45), radius: 18) // halo, jamais de texte d'état
            .scaleEffect(configuration.isPressed ? 0.96 : 1.0)
            .animation(.spring(response: 0.25, dampingFraction: 0.7), value: configuration.isPressed)
    }
}

/// Halo d'Aura plein écran : lueur chaude radiale, intensité 0...1.
struct AuraGlowView: View {
    var intensity: Double

    var body: some View {
        RadialGradient(
            colors: [.clear, BubblePalette.auraWarm.opacity(0.35 * intensity)],
            center: .center,
            startRadius: 0,
            endRadius: 600
        )
        .allowsHitTesting(false)
        .animation(.easeInOut(duration: 1.2), value: intensity)
    }
}
