import SwiftUI

/// Une empreinte de frottement : centre, rayon, instant de naissance.
private struct RevealStamp: Identifiable {
    let id = UUID()
    let center: CGPoint
    let radius: CGFloat
    let bornAt: TimeInterval
}

private enum Heal {
    static let plateau: TimeInterval = 1.8
    static let fade: TimeInterval = 1.4
    static var total: TimeInterval { plateau + fade }

    /// H(age) : 1 pendant le plateau, puis ease-out quadratique vers 0 — le flou "repousse".
    static func factor(age: TimeInterval) -> Double {
        guard age >= 0 else { return 0 }
        if age <= plateau { return 1 }
        if age >= total { return 0 }
        let t = (age - plateau) / fade
        return (1 - t) * (1 - t)
    }
}

/// L'Overlay Live full-bleed (miroir SwiftUI de LiveOverlayScreen.kt).
///
/// Composition : flux net simulé → voile de verre percé par le frottement → halo
/// d'Aura → unique pilule flottante → rituels de pairage. Aucune barre, aucun layout.
///
/// Masque de révélation : M(p,t) = clamp01( Σᵢ G(‖p−cᵢ‖/r) · H(t−tᵢ) ), le voile est
/// dessiné dans un Canvas puis percé par des dégradés radiaux en `.destinationOut`
/// (la somme et le clamp sont faits par le blending GPU).
struct LiveOverlayView: View {
    @EnvironmentObject private var live: LiveViewModel
    @Environment(\.dismiss) private var dismiss

    @StateObject private var camera = CameraGestureController()
    @State private var stamps: [RevealStamp] = []
    @State private var lastStamp: CGPoint?
    @State private var glowPulse: Double = 0

    private let brushRadius: CGFloat = 96

    var body: some View {
        TimelineView(.animation(paused: stamps.isEmpty)) { timeline in
            let now = timeline.date.timeIntervalSinceReferenceDate

            ZStack {
                simulatedFeed

                // Le flux réel s'installe discrètement sous le voile de flou.
                CameraPreviewView(session: camera.session)
                    .ignoresSafeArea()

                frostLayer(now: now)

                AuraGlowView(intensity: min(1, live.auraIntensity + glowPulse))

                // Jeux de Complicité : amorce gravée + coupon (aucune vue de chat).
                PromptWatermark(text: live.activePrompt)
                    .animation(.easeInOut, value: live.activePrompt)

                VStack {
                    CouponReveal(text: live.activeCoupon, onRead: { live.couponRead() })
                        .animation(.easeInOut, value: live.activeCoupon)
                    Spacer()
                    Button("fermer la bulle") { live.closePortal() }
                        .buttonStyle(GlassPillStyle())
                        .padding(.bottom, 24)
                }

                PairingRitualView(ritual: live.ritual)
            }
            .ignoresSafeArea()
            .contentShape(Rectangle()) // tout l'écran capte le geste, aucune zone morte
            .gesture(rubGesture(now: now))
        }
        .background(BubblePalette.night)
        .onAppear {
            live.portalOpened()
            // Pont vision → moteur : seuls des triggers numériques traversent, jamais une frame.
            camera.onGesture = { gesture in live.gestureDetectedLocally(gesture) }
            camera.onFacePresence = { visible in live.facePresence(visible) }
            camera.start()
        }
        .onDisappear { camera.stop() } // coupe-circuit : la session meurt avec la vue
        .onChange(of: live.isLive) { _, isLive in
            if !isLive { dismiss() } // retour Ambient → la vue s'efface vers le widget
        }
        .onChange(of: live.lastGesture) { _, gesture in
            guard gesture != nil else { return }
            withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) { glowPulse = 1 }
            withAnimation(.easeOut(duration: 2.4).delay(0.3)) { glowPulse = 0 }
        }
    }

    /// Flux net simulé : nappes de dégradés, en attendant la caméra.
    private var simulatedFeed: some View {
        ZStack {
            RadialGradient(
                colors: [BubblePalette.nightHigh, BubblePalette.night],
                center: .center, startRadius: 0, endRadius: 700
            )
            LinearGradient(
                colors: [
                    BubblePalette.auraRose.opacity(0.10),
                    .clear,
                    BubblePalette.auraWarm.opacity(0.08),
                ],
                startPoint: .topLeading, endPoint: .bottomTrailing
            )
        }
    }

    /// Le voile flouté, percé par les empreintes de frottement.
    private func frostLayer(now: TimeInterval) -> some View {
        Canvas { context, size in
            context.fill(Path(CGRect(origin: .zero, size: size)),
                         with: .color(BubblePalette.night.opacity(0.86)))
            context.blendMode = .destinationOut
            for stamp in stamps {
                let strength = Heal.factor(age: now - stamp.bornAt)
                guard strength > 0 else { continue }
                let rect = CGRect(
                    x: stamp.center.x - stamp.radius, y: stamp.center.y - stamp.radius,
                    width: stamp.radius * 2, height: stamp.radius * 2
                )
                context.fill(
                    Path(ellipseIn: rect),
                    with: .radialGradient(
                        Gradient(stops: [
                            .init(color: .black.opacity(strength), location: 0),
                            .init(color: .black.opacity(strength * 0.55), location: 0.5),
                            .init(color: .clear, location: 1),
                        ]),
                        center: stamp.center, startRadius: 0, endRadius: stamp.radius
                    )
                )
            }
        }
        .allowsHitTesting(false)
        .background(.ultraThinMaterial) // le "flou" réel : backdrop blur natif iOS
        .compositingGroup()
    }

    /// Frottement : ré-échantillonnage à rayon/3, empreintes purgées après cicatrisation.
    /// Alimente aussi le Radar Haptique (coordonnées normalisées, coût négligeable).
    private func rubGesture(now: TimeInterval) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                let point = value.location
                // Radar : le moteur KMP consomme la position à sa propre cadence (non bloquant).
                let bounds = UIScreen.main.bounds.size
                live.radarFingerMove(x: Float(point.x / bounds.width), y: Float(point.y / bounds.height))

                let farEnough = lastStamp.map {
                    hypot(point.x - $0.x, point.y - $0.y) > brushRadius / 3
                } ?? true
                guard farEnough else { return }
                lastStamp = point
                stamps.append(RevealStamp(center: point, radius: brushRadius, bornAt: now))
                stamps.removeAll { now - $0.bornAt > Heal.total }
            }
            .onEnded { _ in lastStamp = nil }
    }
}
