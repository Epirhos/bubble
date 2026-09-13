import Foundation
import Shared

/// Point d'accès unique au graphe KMP (IosBubbleGraph) côté Swift. Créé une seule fois ;
/// tous les ViewModels observent `AppGraph.shared.manager`.
enum AppGraph {
    static let shared = IosBubbleGraph(signalingUrl: signalingURL)

    /// Adresse de la machine qui fait tourner `server/signaling-server.js`, utilisée
    /// uniquement quand on lance un build Debug sur un iPhone réel.
    ///
    /// À REMPLACER par l'IP de ta machine sur le réseau local (`ipconfig` sous Windows,
    /// `ipconfig getifaddr en0` sur un Mac). Le téléphone et la machine doivent être sur
    /// le même Wi-Fi, et le pare-feu doit laisser passer le port 8787.
    ///
    /// Laisser une valeur fausse ici ne casse pas la compilation : l'app démarre et le
    /// pairage reste bloqué en « connexion », ce qui est le symptôme à reconnaître.
    private static let devLanHost = "192.168.1.42"

    private static var signalingURL: String {
        #if DEBUG
        #if targetEnvironment(simulator)
        // Le simulateur partage la pile réseau du Mac : l'hôte est joignable en local.
        return "ws://127.0.0.1:8787"
        #else
        // Appareil réel en Debug : le relai de dev est sur la machine, pas sur le téléphone.
        // (Auparavant ce cas retombait sur le relai de production, qui n'existe pas encore —
        // le pairage échouait donc sans raison lisible.)
        return "ws://\(devLanHost):8787"
        #endif
        #else
        // Release : relai de production en TLS. Aucune tolérance au trafic en clair.
        return "wss://relay.bubble.app"
        #endif
    }

    static var manager: BubbleStateManager { shared.manager }
}
