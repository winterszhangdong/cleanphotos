import Foundation

/// 网格首屏显影曲线，与 `docs/ui-design.md` 2.4 / Android `MotionEasings.Develop` 一致：`cubic-bezier(0.45, 0, 0.2, 1)`。
enum DevelopCurve {
    static func develop(_ t: Double) -> Double {
        if t <= 0 { return 0 }
        if t >= 1 { return 1 }
        var lo = 0.0
        var hi = 1.0
        var u = t
        for _ in 0..<24 {
            let x = sample(u, 0.45, 0.2)
            if abs(x - t) < 1e-5 { break }
            if x < t { lo = u } else { hi = u }
            u = (lo + hi) / 2
        }
        return sample(u, 0, 1)
    }

    private static func sample(_ u: Double, _ a: Double, _ b: Double) -> Double {
        let v = 1 - u
        return 3 * v * v * u * a + 3 * v * u * u * b + u * u * u
    }
}

/// Android `DevelopProfiles.GridFirstScreen`：单格 520ms，对角线步长 55ms，最多 12 步。
enum DevelopGridProfile {
    static let duration: Double = 0.520
    static let stagger: Double = 0.055
    static let maxSteps: Int = 12
    static let brightnessStart: Double = 0.08
    static let contrastStart: Double = 0.60
    static let saturationStart: Double = 0.0

    static func staggerDelay(row: Int, col: Int) -> Double {
        Double(min(max(0, row + col), maxSteps)) * stagger
    }
}

/// 线性进度上的亮度 / 反差 / 饱和。饱和从 0 走到 1，亮度和反差从起点走到 1。
struct DevelopTone: Equatable {
    var brightness: Double
    var contrast: Double
    var saturation: Double

    static func at(elapsedFraction: Double) -> DevelopTone {
        let q = DevelopCurve.develop(min(1, max(0, elapsedFraction)))
        return DevelopTone(
            brightness: DevelopGridProfile.brightnessStart + (1 - DevelopGridProfile.brightnessStart) * q,
            contrast: DevelopGridProfile.contrastStart + (1 - DevelopGridProfile.contrastStart) * q,
            saturation: DevelopGridProfile.saturationStart + (1 - DevelopGridProfile.saturationStart) * q
        )
    }
}

/// 两层硬件叠层的参数。灰度层盖住彩色，幕层负责压暗和反差。
struct DevelopVeil: Equatable {
    var grayAlpha: Double
    var veilAlpha: Double
    var veilWhite: Double
}

/// `out = c·b·(s·RGB + (1−s)·L) + 0.5·(1−c)` 的代数分解，避免每帧 `CIFilter`。
enum DevelopCompositor {
    static func overlay(brightness: Double, contrast: Double, saturation: Double) -> DevelopVeil {
        let k = contrast * brightness
        let veilAlpha = 1 - k
        let veilWhite = veilAlpha > 1e-6 ? (0.5 * (1 - contrast)) / veilAlpha : 0
        return DevelopVeil(grayAlpha: 1 - saturation, veilAlpha: veilAlpha, veilWhite: veilWhite)
    }

    static func overlay(elapsedFraction: Double) -> DevelopVeil {
        let tone = DevelopTone.at(elapsedFraction: elapsedFraction)
        return overlay(brightness: tone.brightness, contrast: tone.contrast, saturation: tone.saturation)
    }

    /// 错开等待期间返回 0，第一帧就是暗雾起点，不会先亮一下。
    static func elapsedFraction(now: Double, start: Double, delay: Double, duration: Double) -> Double {
        let elapsed = now - start
        if elapsed <= delay { return 0 }
        if duration <= 0 { return 1 }
        let t = (elapsed - delay) / duration
        if t >= 1 { return 1 }
        if t <= 0 { return 0 }
        return t
    }
}

enum DevelopGrid: Equatable, Hashable {
    case photos, favorites, similar
}

/// 每个网格本进程只播一次首屏。照片 / 收藏必须先登记种子；相似允许滚动前绑定的任意缩略图。
struct DevelopSessionTracker {
    private var played: Set<DevelopGrid> = []
    private var seeded: [DevelopGrid: Set<String>] = [:]
    private var claimed: [DevelopGrid: Set<String>] = [:]

    mutating func seed(grid: DevelopGrid, id: String) {
        guard !played.contains(grid) else { return }
        seeded[grid, default: []].insert(id)
    }

    mutating func claim(grid: DevelopGrid, id: String) -> Bool {
        if played.contains(grid) { return false }
        if claimed[grid, default: []].contains(id) { return false }
        switch grid {
        case .photos, .favorites:
            guard seeded[grid, default: []].contains(id) else { return false }
        case .similar:
            break
        }
        claimed[grid, default: []].insert(id)
        return true
    }

    /// 格子在动画结束前被复用时交还名额。已经播完或该网格已结束时不交还。
    mutating func release(grid: DevelopGrid, id: String) {
        guard !played.contains(grid) else { return }
        claimed[grid]?.remove(id)
    }

    mutating func markPlayed(_ grid: DevelopGrid) {
        played.insert(grid)
    }

    func hasPlayed(_ grid: DevelopGrid) -> Bool {
        played.contains(grid)
    }
}

/// 显影灰度叠加层的 Rec.709 亮度矩阵，适配小端 `BGRX8888`（内存 `[B, G, R, X]`）及 `vImageMatrixMultiply_ARGB8888` 列主序。
enum DevelopGrayscaleMatrix {
    static let divisor: Int32 = 10_000

    static let bgraMatrix: [Int16] = {
        let weights = [0.0722, 0.7152, 0.2126]
        let scale = Double(divisor)
        var matrix = [Int16](repeating: 0, count: 16)
        for row in 0..<3 {
            for col in 0..<3 {
                matrix[row * 4 + col] = Int16((weights[row] * scale).rounded())
            }
        }
        matrix[15] = Int16(divisor)
        return matrix
    }()
}
