import Foundation
import WidgetKit

/// Implémentation iOS du `WidgetBridge` (KMP) : écrit l'état dans l'App Group puis force
/// un reload de la timeline. Le débit est déjà limité par le WidgetRefreshPipeline (commonMain).
///
/// Duplique la lecture/écriture de WidgetStorage (l'app et l'extension sont deux cibles
/// distinctes) ; l'App Group `group.app.bubble.shared` est le pont partagé.
///
/// Branché via SKIE quand le framework `shared` est lié :
///   WidgetRefreshPipeline(scope, peerLink.incomingSnapshots, manager.aura, IOSWidgetBridge())
final class IOSWidgetBridge {
    private static let appGroup = "group.app.bubble.shared"
    private static let auraKey = "portal_aura_intensity"
    private static let snapshotFile = "portal_snapshot.jpg"

    /// Correspond à `WidgetBridge.writeSnapshot(bytes:receivedAtMillis:)`.
    func writeSnapshot(bytes: Data, receivedAtMillis: Int64) {
        guard let url = FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: Self.appGroup)?
            .appendingPathComponent(Self.snapshotFile) else { return }
        try? bytes.write(to: url, options: .atomic)
        WidgetCenter.shared.reloadTimelines(ofKind: "BubblePortalWidget")
    }

    /// Correspond à `WidgetBridge.writeAura(intensity:)`.
    func writeAura(intensity: Float) {
        UserDefaults(suiteName: Self.appGroup)?.set(intensity, forKey: Self.auraKey)
        WidgetCenter.shared.reloadTimelines(ofKind: "BubblePortalWidget")
    }
}
