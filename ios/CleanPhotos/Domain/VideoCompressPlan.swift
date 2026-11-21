import Foundation

/// 单条视频压到 1080p 的纯计算。宽高是套用 preferredTransform 之后的显示尺寸。
/// 不读文件，不碰 PhotoKit / AVFoundation。
enum VideoCompressPlan {
    static let maxShortSide = 1080
    static let maxLongSide = 1920
    static let maxFrameRate = 60.0
    static let unknownFrameRate = 30.0
    static let audioBitrate = 160_000
    static let referenceBitrate = 8_000_000.0
    static let minVideoBitrate = 500_000
    static let reserveBytes: Int64 = 32 * 1024 * 1024

    struct Output: Equatable {
        var width: Int
        var height: Int
        var framesPerSecond: Double
        var videoBitrate: Int
    }

    /// 清理页是否给出入口。实况照片和已经在 App 垃圾桶里的不给。
    static func offersEntry(isVideo: Bool, isLivePhoto: Bool, isTrashed: Bool) -> Bool {
        isVideo && !isLivePhoto && !isTrashed
    }

    static func frameRate(_ playback: Double?) -> Double {
        guard let playback, playback > 0, playback.isFinite else { return unknownFrameRate }
        return min(playback, maxFrameRate)
    }

    /// 等比缩小。短边不超过 1080，长边不超过 1920，不放大。宽高向下取偶数，至少 2。
    static func fittedSize(width: Int, height: Int) -> (width: Int, height: Int) {
        let w0 = max(width, 1)
        let h0 = max(height, 1)
        let shortSide = min(w0, h0)
        let longSide = max(w0, h0)
        var scale = 1.0
        if shortSide > maxShortSide { scale = min(scale, Double(maxShortSide) / Double(shortSide)) }
        if longSide > maxLongSide { scale = min(scale, Double(maxLongSide) / Double(longSide)) }
        scale = min(scale, 1)
        return (evenDown(Double(w0) * scale), evenDown(Double(h0) * scale))
    }

    static func videoBitrate(outWidth: Int, outHeight: Int, outFps: Double, byteSize: Int64, duration: TimeInterval) -> Int {
        let pixels = Double(max(outWidth, 1) * max(outHeight, 1))
        let fps = frameRate(outFps)
        let base = referenceBitrate * (pixels / Double(maxLongSide * maxShortSide)) * sqrt(fps / unknownFrameRate)
        guard byteSize > 0, duration > 0, duration.isFinite else { return Int(base.rounded()) }
        let original = Double(byteSize) * 8.0 / duration
        let audio = Double(audioBitrate)
        if base + audio > original * 0.85 {
            return Int(max(Double(minVideoBitrate), original * 0.7 - audio).rounded())
        }
        return Int(base.rounded())
    }

    static func output(width: Int, height: Int, framesPerSecond: Double?, byteSize: Int64, duration: TimeInterval) -> Output {
        let size = fittedSize(width: width, height: height)
        let fps = frameRate(framesPerSecond)
        let bitrate = videoBitrate(outWidth: size.width, outHeight: size.height, outFps: fps, byteSize: byteSize, duration: duration)
        return Output(width: size.width, height: size.height, framesPerSecond: fps, videoBitrate: bitrate)
    }

    /// 短边、长边都已经在 1080p 框里，并且原总码率不超过目标视频码率加 160 kbps。
    static func shouldSkip(width: Int, height: Int, byteSize: Int64, duration: TimeInterval, output: Output) -> Bool {
        let shortSide = min(width, height)
        let longSide = max(width, height)
        guard shortSide <= maxShortSide, longSide <= maxLongSide else { return false }
        guard byteSize > 0, duration > 0, duration.isFinite else { return false }
        let original = Double(byteSize) * 8.0 / duration
        return original <= Double(output.videoBitrate) + Double(audioBitrate)
    }

    /// `abs(输出 - 原时长) > max(500ms, 原时长的 3%)` 时不入库。
    static func durationAccepted(original: TimeInterval, output: TimeInterval) -> Bool {
        guard original.isFinite, output.isFinite else { return false }
        return abs(output - original) <= max(0.5, original * 0.03)
    }

    static func estimatedBytes(videoBitrate: Int, duration: TimeInterval) -> Int64 {
        guard duration > 0, duration.isFinite else { return 0 }
        let bits = (Double(videoBitrate) + Double(audioBitrate)) * duration
        return Int64((bits / 8.0).rounded(.up))
    }

    static func hasRoom(freeBytes: Int64, estimated: Int64) -> Bool {
        freeBytes >= estimated + reserveBytes
    }

    /// `IMG_0001.MOV` → `IMG_0001_1080p.mp4`。没有原名时用 `video_1080p.mp4`。
    static func outputFilename(_ original: String?) -> String {
        let trimmed = original?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let stem: String
        if trimmed.isEmpty {
            stem = "video"
        } else {
            let ns = trimmed as NSString
            stem = ns.pathExtension.isEmpty ? trimmed : ns.deletingPathExtension
        }
        let base = stem.isEmpty ? "video" : stem
        return base + "_1080p.mp4"
    }

    private static func evenDown(_ value: Double) -> Int {
        let floored = Int(value.rounded(.down))
        let even = floored - (floored % 2)
        return max(even, 2)
    }
}

/// preferredTransform 之后再缩进目标宽高。分量与 CGAffineTransform 一致，Domain 不 import CoreGraphics。
struct VideoCompressAffine: Equatable {
    var a, b, c, d, tx, ty: Double

    static let identity = VideoCompressAffine(a: 1, b: 0, c: 0, d: 1, tx: 0, ty: 0)

    func apply(x: Double, y: Double) -> (x: Double, y: Double) {
        (a * x + c * y + tx, b * x + d * y + ty)
    }

    /// 先自己，再 `next`。和 `CGAffineTransform.concatenating` 同一顺序。
    func concatenated(with next: VideoCompressAffine) -> VideoCompressAffine {
        VideoCompressAffine(
            a: next.a * a + next.c * b,
            b: next.b * a + next.d * b,
            c: next.a * c + next.c * d,
            d: next.b * c + next.d * d,
            tx: next.a * tx + next.c * ty + next.tx,
            ty: next.b * tx + next.d * ty + next.ty
        )
    }
}

enum VideoCompressGeometry {
    struct Box: Equatable {
        var x, y, width, height: Double
    }

    static func boundingBox(naturalWidth: Double, naturalHeight: Double, transform: VideoCompressAffine) -> Box {
        let corners = [(0.0, 0.0), (naturalWidth, 0.0), (0.0, naturalHeight), (naturalWidth, naturalHeight)]
        let mapped = corners.map { transform.apply(x: $0.0, y: $0.1) }
        let minX = mapped.map(\.x).min() ?? 0
        let minY = mapped.map(\.y).min() ?? 0
        let maxX = mapped.map(\.x).max() ?? 0
        let maxY = mapped.map(\.y).max() ?? 0
        return Box(x: minX, y: minY, width: maxX - minX, height: maxY - minY)
    }

    static func displaySize(naturalWidth: Double, naturalHeight: Double, transform: VideoCompressAffine) -> (width: Double, height: Double) {
        let box = boundingBox(naturalWidth: naturalWidth, naturalHeight: naturalHeight, transform: transform)
        return (abs(box.width), abs(box.height))
    }

    /// 把轨道自然尺寸经 preferredTransform 摆正，再缩放到输出尺寸。输出原点在左上，不额外旋转。
    static func outputTransform(
        naturalWidth: Double,
        naturalHeight: Double,
        preferred: VideoCompressAffine,
        outWidth: Double,
        outHeight: Double
    ) -> VideoCompressAffine {
        let box = boundingBox(naturalWidth: naturalWidth, naturalHeight: naturalHeight, transform: preferred)
        let dw = abs(box.width)
        let dh = abs(box.height)
        let sx = dw > 0 ? outWidth / dw : 1
        let sy = dh > 0 ? outHeight / dh : 1
        let translated = preferred.concatenated(with: VideoCompressAffine(a: 1, b: 0, c: 0, d: 1, tx: -box.x, ty: -box.y))
        return translated.concatenated(with: VideoCompressAffine(a: sx, b: 0, c: 0, d: sy, tx: 0, ty: 0))
    }
}
