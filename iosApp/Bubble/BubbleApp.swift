import SwiftUI
import Shared

/// Point d'entrée iOS. Dark natif obligatoire, contenu edge-to-edge.
/// Le framework `Shared` (KMP) est lié via SKIE — flows exposés en AsyncSequence, etc.
@main
struct BubbleApp: App {
    @StateObject private var root = RootViewModel()

    init() {
        AppGraph.shared.restore() // rétablit un couple déjà pairé (identité Keychain)
    }

    var body: some Scene {
        WindowGroup {
            Group {
                if root.paired {
                    LiveOverlayView()
                        .environmentObject(LiveViewModel())
                } else {
                    PairingView()
                }
            }
            .preferredColorScheme(.dark)
            .ignoresSafeArea()
            .persistentSystemOverlays(.hidden)
        }
    }
}

/// Observe l'état de pairage (Unpaired → écran de pairage ; sinon Portail).
@MainActor
final class RootViewModel: ObservableObject {
    @Published var paired = false
    private var task: Task<Void, Never>?

    init() {
        // `pairing.phase == .paired` OU un lien déjà restauré ⇒ on montre le Portail.
        task = Task { [weak self] in
            for await state in AppGraph.shared.pairing {
                if state.phase == .paired { self?.paired = true }
            }
        }
        task = Task { [weak self] in
            for await live in AppGraph.shared.isLive {
                if live.boolValue { self?.paired = true }
            }
        }
    }

    deinit { task?.cancel() }
}
