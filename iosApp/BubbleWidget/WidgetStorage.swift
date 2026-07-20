import Foundation
import UIKit

/// Stockage partagé App Group entre l'app (écriture via le pipeline) et l'extension widget
/// (lecture). L'extension widget n'a pas accès au conteneur de l'app : l'App Group est le
/// seul pont. À déclarer dans les entitlements des deux cibles : group.app.bubble.shared.
enum WidgetStorage {
    static let appGroup = "group.app.bubble.shared"
    private static let auraKey = "portal_aura_intensity"
    private static let snapshotFile = "portal_snapshot.jpg"

    private static var container: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup)
    }

    private static var defaults: UserDefaults? {
        UserDefaults(suiteName: appGroup)
    }

    // ── Écriture (app) ───────────────────────────────────────────────────────────

    static func writeAura(_ intensity: Float) {
        defaults?.set(intensity, forKey: auraKey)
    }

    /// Écriture atomique du snapshot (déjà flouté, prêt à afficher).
    static func writeSnapshot(_ data: Data) {
        guard let url = container?.appendingPathComponent(snapshotFile) else { return }
        try? data.write(to: url, options: .atomic)
    }

    // ── Lecture (widget) ─────────────────────────────────────────────────────────

    static func readAura() -> Float {
        min(1, max(0, defaults?.float(forKey: auraKey) ?? 0))
    }

    static func readSnapshot() -> UIImage? {
        guard let url = container?.appendingPathComponent(snapshotFile),
              let data = try? Data(contentsOf: url) else { return nil }
        return UIImage(data: data)
    }

    static func clear() {
        defaults?.removeObject(forKey: auraKey)
        if let url = container?.appendingPathComponent(snapshotFile) {
            try? FileManager.default.removeItem(at: url)
        }
    }
}
