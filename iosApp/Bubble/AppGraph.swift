import Foundation
import Shared

/// Point d'accès unique au graphe KMP (IosBubbleGraph) côté Swift. Créé une seule fois ;
/// tous les ViewModels observent `AppGraph.shared.manager`.
///
/// Dev : émulateur/simulateur → hôte local (server/signaling-server.js). Prod : wss:// via TLS.
enum AppGraph {
    static let shared = IosBubbleGraph(signalingUrl: signalingURL)

    private static var signalingURL: String {
        #if targetEnvironment(simulator)
        return "ws://127.0.0.1:8787"
        #else
        return "wss://relay.bubble.app"
        #endif
    }

    static var manager: BubbleStateManager { shared.manager }
}
