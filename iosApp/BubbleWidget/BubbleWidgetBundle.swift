import SwiftUI
import WidgetKit

/// Point d'entrée de l'extension WidgetKit. À ajouter comme cible "Widget Extension"
/// dans le projet Xcode, partageant l'App Group group.app.bubble.shared avec l'app.
@main
struct BubbleWidgetBundle: WidgetBundle {
    var body: some Widget {
        BubblePortalWidget()
    }
}
