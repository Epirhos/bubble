import AVFoundation
import UIKit

/// Compresseur de journée iOS (miroir de MediaCodecDailyComposer) — AVAssetWriter H.264.
///
/// Mémoire (contrainte étape 7) : encodage en flux via un pixel buffer pool réutilisé
/// (`AVAssetWriterInputPixelBufferAdaptor`). Chaque snapshot est décodé, dessiné dans un
/// buffer du pool avec l'ambiance douce, appended, puis relâché — jamais toute la journée
/// en RAM. `autoreleasepool` par frame borne le pic mémoire.
///
/// Correspond à l'interface KMP `DailyBubbleComposer` ; branché via SKIE quand le framework
/// `shared` est lié (macOS/Xcode uniquement).
struct DailyBubbleSpecSwift {
    var fps: Int32 = 12
    var targetDurationSeconds: Int = 15
    var width = 720
    var height = 1280
    var mood = DailyMood.warmDusk
}

enum DailyMood {
    case warmDusk, softFilm, moonlight

    var tint: UIColor {
        switch self {
        case .warmDusk: return UIColor(red: 1.0, green: 0.70, blue: 0.48, alpha: 0.16)
        case .softFilm: return UIColor(red: 1.0, green: 0.94, blue: 0.86, alpha: 0.12)
        case .moonlight: return UIColor(red: 0.59, green: 0.67, blue: 1.0, alpha: 0.16)
        }
    }
}

enum DailyComposeResult {
    case success(path: String, durationMillis: Int)
    case empty
    case failure(reason: String)
}

final class DailyBubbleComposer {

    func compile(
        fragmentPaths: [String],
        outputPath: String,
        spec: DailyBubbleSpecSwift = DailyBubbleSpecSwift()
    ) async -> DailyComposeResult {
        let frames = resample(fragmentPaths, spec: spec)
        guard !frames.isEmpty else { return .empty }

        let outputURL = URL(fileURLWithPath: outputPath)
        try? FileManager.default.removeItem(at: outputURL)

        do {
            let writer = try AVAssetWriter(outputURL: outputURL, fileType: .mp4)
            let settings: [String: Any] = [
                AVVideoCodecKey: AVVideoCodecType.h264,
                AVVideoWidthKey: spec.width,
                AVVideoHeightKey: spec.height,
            ]
            let input = AVAssetWriterInput(mediaType: .video, outputSettings: settings)
            input.expectsMediaDataInRealTime = false
            let adaptor = AVAssetWriterInputPixelBufferAdaptor(
                assetWriterInput: input,
                sourcePixelBufferAttributes: [
                    kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32ARGB,
                    kCVPixelBufferWidthKey as String: spec.width,
                    kCVPixelBufferHeightKey as String: spec.height,
                ]
            )
            writer.add(input)
            writer.startWriting()
            writer.startSession(atSourceTime: .zero)

            let frameDuration = CMTime(value: 1, timescale: spec.fps)
            for (index, path) in frames.enumerated() {
                autoreleasepool {
                    guard let image = UIImage(contentsOfFile: path),
                          let buffer = pixelBuffer(from: image, spec: spec, pool: adaptor.pixelBufferPool)
                    else { return }
                    while !input.isReadyForMoreMediaData { usleep(2_000) }
                    let time = CMTimeMultiply(frameDuration, multiplier: Int32(index))
                    adaptor.append(buffer, withPresentationTime: time)
                    // buffer relâché en sortie de autoreleasepool : jamais deux frames vivantes
                }
            }
            input.markAsFinished()
            await writer.finishWriting()

            if writer.status == .completed {
                let durationMillis = frames.count * 1000 / Int(spec.fps)
                return .success(path: outputPath, durationMillis: durationMillis)
            }
            return .failure(reason: writer.error?.localizedDescription ?? "write failed")
        } catch {
            return .failure(reason: error.localizedDescription)
        }
    }

    private func resample(_ paths: [String], spec: DailyBubbleSpecSwift) -> [String] {
        let target = Int(spec.fps) * spec.targetDurationSeconds
        guard paths.count > target else { return paths }
        let step = Double(paths.count) / Double(target)
        return (0..<target).map { paths[Int(Double($0) * step)] }
    }

    /// Dessine l'image (center-crop) + overlay d'ambiance dans un buffer du pool.
    private func pixelBuffer(from image: UIImage, spec: DailyBubbleSpecSwift, pool: CVPixelBufferPool?) -> CVPixelBuffer? {
        guard let pool else { return nil }
        var buffer: CVPixelBuffer?
        CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault, pool, &buffer)
        guard let pixelBuffer = buffer else { return nil }

        CVPixelBufferLockBaseAddress(pixelBuffer, [])
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, []) }

        let colorSpace = CGColorSpaceCreateDeviceRGB()
        guard let context = CGContext(
            data: CVPixelBufferGetBaseAddress(pixelBuffer),
            width: spec.width, height: spec.height,
            bitsPerComponent: 8, bytesPerRow: CVPixelBufferGetBytesPerRow(pixelBuffer),
            space: colorSpace,
            bitmapInfo: CGImageAlphaInfo.noneSkipFirst.rawValue
        ), let cgImage = image.cgImage else { return nil }

        context.setFillColor(UIColor.black.cgColor)
        context.fill(CGRect(x: 0, y: 0, width: spec.width, height: spec.height))
        context.draw(cgImage, in: centerCropRect(cgImage, spec: spec))
        context.setFillColor(spec.mood.tint.cgColor)
        context.fill(CGRect(x: 0, y: 0, width: spec.width, height: spec.height))
        return pixelBuffer
    }

    private func centerCropRect(_ image: CGImage, spec: DailyBubbleSpecSwift) -> CGRect {
        let dstRatio = CGFloat(spec.width) / CGFloat(spec.height)
        let srcRatio = CGFloat(image.width) / CGFloat(image.height)
        // Rempli le cadre en couvrant (aspect fill) — les débordements sortent du contexte.
        if srcRatio > dstRatio {
            let h = CGFloat(spec.height)
            let w = h * srcRatio
            return CGRect(x: (CGFloat(spec.width) - w) / 2, y: 0, width: w, height: h)
        } else {
            let w = CGFloat(spec.width)
            let h = w / srcRatio
            return CGRect(x: 0, y: (CGFloat(spec.height) - h) / 2, width: w, height: h)
        }
    }
}
