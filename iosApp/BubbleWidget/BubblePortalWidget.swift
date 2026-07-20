import SwiftUI
import WidgetKit

/// Entrée de timeline : un instantané de l'état du portail lu depuis l'App Group.
struct PortalEntry: TimelineEntry {
    let date: Date
    let snapshot: UIImage?
    let auraIntensity: Float
}

/// Provider WidgetKit. Contrainte Timeline : on ne peut pas pousser en continu ; on lit
/// l'état déposé par l'app et on programme un simple refresh d'entretien. Les mises à jour
/// réelles (nouveau snapshot / Aura) sont déclenchées par l'app via WidgetCenter.reloadTimelines
/// quand le pipeline écrit — pas par un polling coûteux ici (économie de batterie).
struct PortalProvider: TimelineProvider {
    func placeholder(in context: Context) -> PortalEntry {
        PortalEntry(date: Date(), snapshot: nil, auraIntensity: 0)
    }

    func getSnapshot(in context: Context, completion: @escaping (PortalEntry) -> Void) {
        completion(currentEntry())
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<PortalEntry>) -> Void) {
        // Entrée unique + refresh d'entretien dans 30 min (l'app force un reload avant si besoin).
        let entry = currentEntry()
        let next = Calendar.current.date(byAdding: .minute, value: 30, to: Date()) ?? Date()
        completion(Timeline(entries: [entry], policy: .after(next)))
    }

    private func currentEntry() -> PortalEntry {
        PortalEntry(
            date: Date(),
            snapshot: WidgetStorage.readSnapshot(),
            auraIntensity: WidgetStorage.readAura()
        )
    }
}

/// Portail passif : snapshot flouté + halo d'Aura, tap → deep link bubble://live.
struct BubblePortalWidgetView: View {
    var entry: PortalEntry

    var body: some View {
        ZStack {
            Color(red: 0.039, green: 0.039, blue: 0.059)

            if let image = entry.snapshot {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                Text("· ·").foregroundStyle(.white.opacity(0.4))
            }

            // Halo d'Aura : voile chaud dont l'opacité suit la présence du partenaire.
            if entry.auraIntensity > 0.01 {
                RadialGradient(
                    colors: [.clear, Color(red: 1.0, green: 0.70, blue: 0.48)
                        .opacity(Double(entry.auraIntensity) * 0.4)],
                    center: .center, startRadius: 0, endRadius: 160
                )
            }
        }
        .widgetURL(URL(string: "bubble://live")) // tout le widget = deep link vers l'Overlay Live
        .containerBackground(for: .widget) { Color.black }
    }
}

struct BubblePortalWidget: Widget {
    let kind = "BubblePortalWidget"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: PortalProvider()) { entry in
            BubblePortalWidgetView(entry: entry)
        }
        .configurationDisplayName("Portail Bubble")
        .description("L'Aura et le dernier instant de ton partenaire.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}
