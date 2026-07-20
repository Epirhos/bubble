import Combine
import SwiftUI
import Shared

/// Gestes reçus, miroir Swift de `HapticGesture` (KMP).
enum ReceivedGesture {
    case kiss
    case heart
}

/// Rituels du lien, miroir Swift de `PairingEvent` (KMP).
enum PairingRitual: Equatable {
    case bubblesMerged
    case bubbleSplit
}

/// Adaptateur mince entre le `BubbleStateManager` (KMP) et SwiftUI — zéro logique métier.
/// Consomme les StateFlow/SharedFlow via SKIE (`for await`). Les actions appellent directement
/// le moteur ; la vibration est jouée par le manager (IosHapticEngine / CoreHaptics).
@MainActor
final class LiveViewModel: ObservableObject {
    @Published var isLive: Bool = true
    @Published var auraIntensity: Double = 0
    @Published var lastGesture: ReceivedGesture?
    @Published var ritual: PairingRitual?

    // Jeux de Complicité (étape 10).
    @Published var activePrompt: String?
    @Published var activeCoupon: String?
    @Published var radarSeeking: Bool = false

    private let manager = AppGraph.manager
    private var tasks: [Task<Void, Never>] = []

    init() {
        observe()
    }

    deinit {
        tasks.forEach { $0.cancel() }
    }

    private func observe() {
        // Flux simples (bool) exposés par le graphe → pas de sealed à décoder côté Swift.
        tasks.append(Task { [weak self] in
            for await live in AppGraph.shared.isLive {
                self?.isLive = live.boolValue
            }
        })
        tasks.append(Task { [weak self] in
            for await aura in AppGraph.manager.aura {
                self?.auraIntensity = Double(AppGraph.shared.auraIntensity(aura: aura))
            }
        })
        tasks.append(Task { [weak self] in
            for await gesture in AppGraph.manager.immediateHaptics {
                self?.lastGesture = (gesture == .kiss) ? .kiss : .heart
            }
        })
        tasks.append(Task { [weak self] in
            for await prompt in AppGraph.manager.activePrompt {
                self?.activePrompt = prompt?.text
            }
        })
        tasks.append(Task { [weak self] in
            for await coupon in AppGraph.manager.activeCoupon {
                self?.activeCoupon = coupon?.text
            }
        })
        tasks.append(Task { [weak self] in
            for await seeking in AppGraph.manager.radarSeeking {
                self?.radarSeeking = seeking.boolValue
            }
        })
        tasks.append(Task { [weak self] in
            for await merged in AppGraph.shared.ritualMerged {
                self?.ritual = merged.boolValue ? .bubblesMerged : .bubbleSplit
            }
        })
    }

    /// Ouverture du portail (deep link widget) : draine et rejoue aussi les Échos Haptiques.
    func portalOpened() {
        _ = manager.onPortalOpened()
    }

    func closePortal() {
        manager.onLiveEnded(reason: .userExit)
    }

    /// Geste sortant confirmé par la vision locale (déjà débouncé).
    func gestureDetectedLocally(_ gesture: ReceivedGesture) {
        manager.onLocalGestureDetected(gesture: gesture == .kiss ? .kiss : .heart)
    }

    /// Présence visage : chaque émission réarme le watchdog 120 s.
    func facePresence(_ visible: Bool) {
        if visible { manager.onFaceDetected() }
    }

    /// Radar Haptique : déplacement du doigt (0..1). Non bloquant ; le moteur pilote CoreHaptics.
    func radarFingerMove(x: Float, y: Float) {
        manager.onRadarFingerMove(x: x, y: y)
    }

    /// Lecture du coupon → il s'évapore aussitôt.
    func couponRead() {
        manager.onCouponRead()
    }
}
