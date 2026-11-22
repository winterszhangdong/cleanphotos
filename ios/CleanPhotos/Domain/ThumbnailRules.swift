import CoreGraphics
import Foundation

enum ThumbnailStyle: Int, Hashable, Sendable {
    case normal = 0
    case trashed = 1
}

/// 缩略图缓存键与像素边长计算（纯 Foundation，供单元测试直接覆盖）。
enum ThumbnailCacheKey {
    static func pixelSide(points: CGFloat, scale: CGFloat) -> Int {
        max(1, Int((max(0, points) * max(scale, 1)).rounded(.up)))
    }

    static func make(assetId: String, pixelSide: Int, style: ThumbnailStyle) -> String {
        "\(assetId)|\(pixelSide)|\(style.rawValue)"
    }
}

/// 滑动预取窗口的增量差集：只对新增资产开缓存，只对移出窗口资产停缓存。
enum ThumbnailWindowDiff {
    static func delta(previous: Set<String>, next: Set<String>) -> (start: Set<String>, stop: Set<String>) {
        (next.subtracting(previous), previous.subtracting(next))
    }
}

/// 解码后缩略图几何尺寸规则：按短边缩放到目标像素（永不放大），长边超过 `maxAspect` 时居中裁切以封顶单张内存。
enum ThumbnailSizeRule {
    struct DecodedGeometry: Equatable {
        let width: Int
        let height: Int
        let drawRect: CGRect
    }

    static func decodedGeometry(
        sourceWidth: Int,
        sourceHeight: Int,
        targetPixelSide: Int,
        maxAspect: CGFloat = 3.0
    ) -> DecodedGeometry {
        let side = max(1, targetPixelSide)
        guard sourceWidth > 0, sourceHeight > 0, targetPixelSide > 0 else {
            return DecodedGeometry(
                width: side,
                height: side,
                drawRect: CGRect(x: 0, y: 0, width: side, height: side)
            )
        }
        let scale = min(1.0, CGFloat(side) / CGFloat(min(sourceWidth, sourceHeight)))
        let rawW = CGFloat(sourceWidth) * scale
        let rawH = CGFloat(sourceHeight) * scale
        let maxLong = CGFloat(side) * max(1.0, maxAspect)
        let outW = max(1, Int(min(rawW, maxLong).rounded()))
        let outH = max(1, Int(min(rawH, maxLong).rounded()))
        let drawRect = CGRect(
            x: (CGFloat(outW) - rawW) / 2,
            y: (CGFloat(outH) - rawH) / 2,
            width: rawW,
            height: rawH
        )
        return DecodedGeometry(width: outW, height: outH, drawRect: drawRect)
    }
}

/// 垃圾桶「退影」色彩矩阵：Rec.709 亮度权重下单遍完成 `saturation(0.7)` 后乘 `0.86`，适配小端 `BGRX8888`（内存 `[B, G, R, X]`）。
enum TrashedColorMatrix {
    static let divisor: Int32 = 10_000

    static let bgraMatrix: [Int16] = {
        let w = [0.0722, 0.7152, 0.2126]
        let s = 0.7
        let k = 1.0 - s
        let m = 0.86
        let scale = Double(divisor)
        var matrix = [Int16](repeating: 0, count: 16)
        for row in 0..<3 {
            for col in 0..<3 {
                let weight = m * (k * w[row] + (row == col ? s : 0))
                matrix[row * 4 + col] = Int16((weight * scale).rounded())
            }
        }
        matrix[15] = Int16(divisor)
        return matrix
    }()
}
