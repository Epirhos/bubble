import AVFoundation
import SwiftUI
import Vision

/// Capture + vision iOS (miroir de AndroidGestureRecognizer) — AVFoundation + Vision,
/// aucun framework tiers. Requiert `NSCameraUsageDescription` dans l'Info.plist.
///
/// Cyber-hygiène (contrat strict) :
///  - L'inférence tourne sur `inferenceQueue` (GCD, hors Main Thread).
///  - Aucune frame n'est copiée ni stockée : le `CMSampleBuffer` est lu par Vision puis
///    libéré en sortie de scope. Seuls des triggers numériques sortent (closures).
///  - Frame dropping à deux étages : `alwaysDiscardsLateVideoFrames` (backlog jeté par
///    AVFoundation) + cadence adaptative (miroir de AdaptiveSamplingPolicy, commonMain) :
///    IDLE 2 Hz visage seul (sonde mains 1×/s) ↔ ACTIVE ~7 Hz visage + mains,
///    retour IDLE après 3 s sans main.
///  - Basse consommation : preset .vga640x480 — largement assez pour des landmarks.
///
/// Géométrie des triggers : mêmes seuils que `GestureTriggers` (commonMain KMP).
/// Attention : Vision travaille en y-vers-le-HAUT (origine en bas à gauche),
/// les comparaisons verticales sont donc inversées par rapport à MediaPipe.
final class CameraGestureController: NSObject, ObservableObject {
    let session = AVCaptureSession()

    /// Geste sortant confirmé → TODO(shared): manager.onLocalGestureDetected(...)
    var onGesture: ((ReceivedGesture) -> Void)?
    /// Présence visage (ré-émise à chaque inférence) → TODO(shared): manager.onFaceDetected()
    var onFacePresence: ((Bool) -> Void)?

    private let inferenceQueue = DispatchQueue(label: "com.bubble.vision", qos: .userInitiated)
    private let output = AVCaptureVideoDataOutput()
    private var configured = false

    // Miroir de GestureDebouncer (commonMain) : hold 3 frames, cooldown 3 s.
    private var candidate: ReceivedGesture?
    private var streak = 0
    private var lastFiredAt = Date.distantPast
    private var faceVisible = false

    // Miroir de AdaptiveSamplingPolicy (commonMain) — mêmes constantes, même logique.
    private enum SamplingMode { case idle, active }
    private var samplingMode = SamplingMode.idle
    private var lastFrameAt = Date.distantPast
    private var lastHandProbeAt = Date.distantPast
    private var lastHandSeenAt = Date.distantPast
    private let idleInterval: TimeInterval = 0.500
    private let activeInterval: TimeInterval = 0.150
    private let handProbeInterval: TimeInterval = 1.0
    private let activeHold: TimeInterval = 3.0

    // Seuils identiques à GestureTriggers (KMP).
    private let heartTipsMaxDistance: CGFloat = 0.14
    private let kissPuckerMaxRatio: CGFloat = 0.36 // outerLips/bbox — à calibrer sur device
    private let kissHandMaxDistance: CGFloat = 0.22
    private let heartVerticalMargin: CGFloat = 0.03

    func start() {
        inferenceQueue.async { [self] in
            configureIfNeeded()
            if !session.isRunning { session.startRunning() }
        }
    }

    /// Coupe-circuit : appelé quand la vue Live disparaît (retour Ambient, watchdog 120 s).
    func stop() {
        inferenceQueue.async { [self] in
            if session.isRunning { session.stopRunning() }
        }
    }

    private func configureIfNeeded() {
        guard !configured else { return }
        configured = true
        session.beginConfiguration()
        session.sessionPreset = .vga640x480
        if let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .front),
           let input = try? AVCaptureDeviceInput(device: device),
           session.canAddInput(input) {
            session.addInput(input)
        }
        output.alwaysDiscardsLateVideoFrames = true
        output.setSampleBufferDelegate(self, queue: inferenceQueue)
        if session.canAddOutput(output) { session.addOutput(output) }
        session.commitConfiguration()
    }
}

extension CameraGestureController: AVCaptureVideoDataOutputSampleBufferDelegate {
    func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        let now = Date()
        guard let runHandModel = planFrame(now: now) else { return } // frame dropping adaptatif

        let faceRequest = VNDetectFaceLandmarksRequest()
        var requests: [VNRequest] = [faceRequest]
        let handRequest = VNDetectHumanHandPoseRequest()
        if runHandModel {
            handRequest.maximumHandCount = 2
            requests.append(handRequest)
        }

        let handler = VNImageRequestHandler(cmSampleBuffer: sampleBuffer, orientation: .leftMirrored)
        try? handler.perform(requests)
        // La frame n'est jamais copiée : sampleBuffer sort de scope ici, détruite.

        let face = faceRequest.results?.first
        emitFacePresence(face != nil)

        let hands = runHandModel ? (handRequest.results ?? []).compactMap { try? handPoints($0) } : []
        if runHandModel { onHandsResult(handsPresent: !hands.isEmpty, now: now) }

        let detected = detectCandidate(face: face, hands: hands)
        if let confirmed = debounce(detected, now: now) {
            DispatchQueue.main.async { self.onGesture?(confirmed) }
        }
    }

    /// Miroir de AdaptiveSamplingPolicy.planFrame : nil = frame jetée, sinon "faut-il les mains ?".
    private func planFrame(now: Date) -> Bool? {
        let interval = samplingMode == .active ? activeInterval : idleInterval
        guard now.timeIntervalSince(lastFrameAt) >= interval else { return nil }
        lastFrameAt = now
        let runHands: Bool
        switch samplingMode {
        case .active: runHands = true
        case .idle: runHands = now.timeIntervalSince(lastHandProbeAt) >= handProbeInterval
        }
        if runHands { lastHandProbeAt = now }
        return runHands
    }

    private func onHandsResult(handsPresent: Bool, now: Date) {
        if handsPresent {
            lastHandSeenAt = now
            samplingMode = .active
        } else if samplingMode == .active, now.timeIntervalSince(lastHandSeenAt) >= activeHold {
            samplingMode = .idle
        }
    }

    private func emitFacePresence(_ hasFace: Bool) {
        if hasFace || faceVisible != hasFace {
            faceVisible = hasFace
            DispatchQueue.main.async { self.onFacePresence?(hasFace) }
        }
    }

    private struct HandPoints {
        let thumbTip: CGPoint
        let indexTip: CGPoint
        let all: [CGPoint]
    }

    private func handPoints(_ observation: VNHumanHandPoseObservation) throws -> HandPoints? {
        let points = try observation.recognizedPoints(.all)
        guard let thumb = points[.thumbTip], thumb.confidence > 0.3,
              let index = points[.indexTip], index.confidence > 0.3 else { return nil }
        let all = points.values.filter { $0.confidence > 0.3 }.map(\.location)
        return HandPoints(thumbTip: thumb.location, indexTip: index.location, all: all)
    }

    private func detectCandidate(face: VNFaceObservation?, hands: [HandPoints?]) -> ReceivedGesture? {
        let valid = hands.compactMap { $0 }
        if valid.count >= 2 {
            let a = valid[0], b = valid[1]
            let indexesTouch = dist(a.indexTip, b.indexTip) < heartTipsMaxDistance
            let thumbsTouch = dist(a.thumbTip, b.thumbTip) < heartTipsMaxDistance
            let indexMidY = (a.indexTip.y + b.indexTip.y) / 2
            let thumbMidY = (a.thumbTip.y + b.thumbTip.y) / 2
            // y vers le haut : la pointe du cœur (index) doit être AU-DESSUS des pouces.
            if indexesTouch, thumbsTouch, indexMidY > thumbMidY + heartVerticalMargin {
                return .heart
            }
        }
        if let face, let lips = face.landmarks?.outerLips, !valid.isEmpty {
            let box = face.boundingBox
            let xs = lips.normalizedPoints.map(\.x)
            guard let minX = xs.min(), let maxX = xs.max(), box.width > 0 else { return nil }
            let pucker = (maxX - minX) < kissPuckerMaxRatio // largeur lèvres / largeur visage
            let mouthCenter = CGPoint(x: box.midX, y: box.minY + box.height * 0.30)
            let handNear = valid.flatMap(\.all).contains { dist($0, mouthCenter) < kissHandMaxDistance }
            if pucker, handNear { return .kiss }
        }
        return nil
    }

    /// Miroir exact de GestureDebouncer (commonMain KMP).
    private func debounce(_ detected: ReceivedGesture?, now: Date) -> ReceivedGesture? {
        if detected != candidate {
            candidate = detected
            streak = detected == nil ? 0 : 1
            return nil
        }
        guard let detected else { return nil }
        streak += 1
        if streak >= 3 {
            streak = 0
            if now.timeIntervalSince(lastFiredAt) >= 3.0 {
                lastFiredAt = now
                return detected
            }
        }
        return nil
    }

    private func dist(_ a: CGPoint, _ b: CGPoint) -> CGFloat {
        hypot(a.x - b.x, a.y - b.y)
    }
}

/// Le flux caméra sous le voile de flou (AVCaptureVideoPreviewLayer, aspect-fill).
struct CameraPreviewView: UIViewRepresentable {
    let session: AVCaptureSession

    final class PreviewUIView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }

    func makeUIView(context: Context) -> PreviewUIView {
        let view = PreviewUIView()
        view.previewLayer.session = session
        view.previewLayer.videoGravity = .resizeAspectFill
        return view
    }

    func updateUIView(_ uiView: PreviewUIView, context: Context) {}
}
