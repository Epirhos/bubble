import SwiftUI
import CoreImage.CIFilterBuiltins
import AVFoundation
import Shared

/// ViewModel de pairage : observe l'état plat orchestré par IosBubbleGraph (toute la crypto,
/// le handshake et la signalisation vivent en Kotlin). Zéro logique métier ici.
@MainActor
final class PairingViewModel: ObservableObject {
    @Published var state: IosPairingState = IosPairingState(phase: .choice, qrPayload: nil, safetyNumber: nil, message: nil)

    private var task: Task<Void, Never>?

    init() {
        task = Task { [weak self] in
            for await s in AppGraph.shared.pairing {
                self?.state = s
            }
        }
    }

    deinit { task?.cancel() }

    func generateInvite() { AppGraph.shared.startInvite() }
    func startScanning() { AppGraph.shared.startScanning() }
    func onQrScanned(_ raw: String) { AppGraph.shared.onQrScanned(raw: raw) }
}

/// Écran de pairage épuré (glassmorphism, fond nuit) : générer ou scanner.
struct PairingView: View {
    @StateObject private var model = PairingViewModel()

    var body: some View {
        ZStack {
            BubblePalette.night.ignoresSafeArea()
            switch model.state.phase {
            case .choice: choice
            case .showingInvite: invite(model.state.qrPayload ?? "")
            case .scanning: scan
            case .paired: paired(model.state.safetyNumber ?? "")
            case .error: Text(model.state.message ?? "Erreur").foregroundStyle(BubblePalette.moon)
            default: choice
            }
        }
    }

    private var choice: some View {
        VStack(spacing: 16) {
            Text("Relier vos deux bulles")
                .font(.system(size: 22, design: .rounded)).foregroundStyle(BubblePalette.moon)
            Button("Générer mon invitation") { model.generateInvite() }
                .buttonStyle(GlassPillStyle())
            Button("Scanner l'invitation") { model.startScanning() }
                .buttonStyle(GlassPillStyle())
        }
    }

    private func invite(_ payload: String) -> some View {
        VStack(spacing: 20) {
            Text("Fais scanner ce code").foregroundStyle(BubblePalette.moon)
            if let image = QRGenerator.image(from: payload) {
                Image(uiImage: image)
                    .interpolation(.none).resizable()
                    .frame(width: 240, height: 240)
                    .padding(16).background(BubblePalette.nightHigh)
                    .clipShape(RoundedRectangle(cornerRadius: 20))
            }
            Text("En attente de ton partenaire…")
                .font(.footnote).foregroundStyle(BubblePalette.moon.opacity(0.6))
        }
    }

    private var scan: some View {
        ZStack {
            QRScannerView { model.onQrScanned($0) }.ignoresSafeArea()
            VStack {
                Spacer()
                Text("Vise l'invitation de ton partenaire")
                    .foregroundStyle(BubblePalette.moon).padding(.bottom, 48)
            }
        }
    }

    private func paired(_ safety: String) -> some View {
        VStack(spacing: 12) {
            Text("Vos bulles ne font qu'une")
                .font(.system(size: 20, design: .rounded)).foregroundStyle(BubblePalette.auraWarm)
            Text("Vérifiez ce nombre ensemble :")
                .font(.footnote).foregroundStyle(BubblePalette.moon.opacity(0.7))
            Text(safety).foregroundStyle(BubblePalette.moon)
        }
    }
}

/// Génère le QR localement (CoreImage, aucun réseau/cloud).
enum QRGenerator {
    static func image(from payload: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(payload.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 10, y: 10)),
              let cg = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}

/// Scanner de QR natif via AVCaptureMetadataOutput (aucune dépendance tierce).
struct QRScannerView: UIViewControllerRepresentable {
    let onScanned: (String) -> Void

    func makeUIViewController(context: Context) -> ScannerController {
        let controller = ScannerController()
        controller.onScanned = onScanned
        return controller
    }

    func updateUIViewController(_ controller: ScannerController, context: Context) {}

    final class ScannerController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
        var onScanned: ((String) -> Void)?
        private let session = AVCaptureSession()
        private var handled = false

        override func viewDidLoad() {
            super.viewDidLoad()
            guard let device = AVCaptureDevice.default(for: .video),
                  let input = try? AVCaptureDeviceInput(device: device),
                  session.canAddInput(input) else { return }
            session.addInput(input)
            let output = AVCaptureMetadataOutput()
            if session.canAddOutput(output) {
                session.addOutput(output)
                output.setMetadataObjectsDelegate(self, queue: .main)
                output.metadataObjectTypes = [.qr]
            }
            let preview = AVCaptureVideoPreviewLayer(session: session)
            preview.videoGravity = .resizeAspectFill
            preview.frame = view.bounds
            view.layer.addSublayer(preview)
            DispatchQueue.global(qos: .userInitiated).async { [session] in session.startRunning() }
        }

        func metadataOutput(
            _ output: AVCaptureMetadataOutput,
            didOutput objects: [AVMetadataObject],
            from connection: AVCaptureConnection
        ) {
            guard !handled,
                  let object = objects.first as? AVMetadataMachineReadableCodeObject,
                  let value = object.stringValue, value.hasPrefix("bubble1:") else { return }
            handled = true
            session.stopRunning()
            onScanned?(value)
        }
    }
}
