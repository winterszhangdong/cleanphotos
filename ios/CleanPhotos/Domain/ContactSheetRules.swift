import CoreGraphics
import Foundation

/// 印样表胶片几何、油性笔路径和片边字。纯 Foundation / CoreGraphics，对齐 Android `FilmGeometry`。
enum FilmGeometryRules {
    static let band: CGFloat = 12
    static let endInset: CGFloat = 8
    static let frameGap: CGFloat = 6
    static let stripGap: CGFloat = 14
    static let holeWidth: CGFloat = 5
    static let holePitch: CGFloat = 10
    static let holeHeight: CGFloat = 4
    static let holeInset: CGFloat = 4
    static let penMargin: CGFloat = 9
    static let penStroke: CGFloat = 2.4
    static let denseAfter = 30
    static let pageInset: CGFloat = 16

    static func columns(count: Int) -> Int { count > denseAfter ? 4 : 3 }

    /// 一格照片宽：整条宽减两端和格间，再均分。
    static func frameWidth(stripWidth: CGFloat, cols: Int) -> CGFloat {
        guard cols > 0 else { return 0 }
        return (stripWidth - endInset * 2 - frameGap * CGFloat(cols - 1)) / CGFloat(cols)
    }

    /// 第 `col` 格照片在自己那一段胶片里的左边距（每段宽 = 整条 / cols）。
    static func frameLeft(stripWidth: CGFloat, cols: Int, col: Int) -> CGFloat {
        guard cols > 0 else { return 0 }
        let width = frameWidth(stripWidth: stripWidth, cols: cols)
        return endInset + (width + frameGap) * CGFloat(col) - stripWidth / CGFloat(cols) * CGFloat(col)
    }

    static func stripHeight(stripWidth: CGFloat, cols: Int) -> CGFloat {
        frameWidth(stripWidth: stripWidth, cols: cols) * 3 / 4 + band * 2
    }

    /// 露角按 id 固定歪在 −5...5 度。数字 id 用 Android `id * 7919 mod 11`；其它 id 用同一套稳定哈希。
    static func coverTilt(id: String) -> CGFloat {
        let mixed: Int64
        if let n = Int64(id) {
            mixed = n &* 7919
        } else {
            var hash: Int64 = 0
            for byte in id.utf8 {
                hash = hash &* 7919 &+ Int64(byte)
            }
            mixed = hash
        }
        let mod = mixed % 11
        let positive = mod >= 0 ? mod : mod + 11
        return CGFloat(positive - 5)
    }

    /// `CP 400 · M.dd`。没有日期时只有 `CP 400`。月不补零，日补零。
    static func edgePrint(date: Date?, calendar: Calendar = .current) -> String {
        guard let date else { return "CP 400" }
        let month = calendar.component(.month, from: date)
        let day = calendar.component(.day, from: date)
        return String(format: "CP 400 · %d.%02d", month, day)
    }

    /// 三支手绘圈（114×90 框）。第一点是起点，之后每段三次贝塞尔。
    static let penPaths: [PenPath] = [
        PenPath(start: CGPoint(x: 64, y: 7), cubics: [
            PenCubic(c1: CGPoint(x: 94, y: 6), c2: CGPoint(x: 110, y: 23), end: CGPoint(x: 109, y: 44)),
            PenCubic(c1: CGPoint(x: 108, y: 68), c2: CGPoint(x: 84, y: 84), end: CGPoint(x: 55, y: 83)),
            PenCubic(c1: CGPoint(x: 25, y: 82), c2: CGPoint(x: 5, y: 66), end: CGPoint(x: 6, y: 43)),
            PenCubic(c1: CGPoint(x: 7, y: 21), c2: CGPoint(x: 29, y: 7), end: CGPoint(x: 60, y: 9)),
            PenCubic(c1: CGPoint(x: 72, y: 9.6), c2: CGPoint(x: 81, y: 12.4), end: CGPoint(x: 88, y: 16)),
        ]),
        PenPath(start: CGPoint(x: 52, y: 8), cubics: [
            PenCubic(c1: CGPoint(x: 80, y: 4), c2: CGPoint(x: 108, y: 18), end: CGPoint(x: 108, y: 42)),
            PenCubic(c1: CGPoint(x: 108, y: 66), c2: CGPoint(x: 88, y: 85), end: CGPoint(x: 58, y: 84)),
            PenCubic(c1: CGPoint(x: 27, y: 83), c2: CGPoint(x: 7, y: 69), end: CGPoint(x: 6, y: 46)),
            PenCubic(c1: CGPoint(x: 5, y: 22), c2: CGPoint(x: 26, y: 9), end: CGPoint(x: 56, y: 7)),
            PenCubic(c1: CGPoint(x: 68, y: 6), c2: CGPoint(x: 77, y: 8), end: CGPoint(x: 86, y: 12)),
        ]),
        PenPath(start: CGPoint(x: 60, y: 6), cubics: [
            PenCubic(c1: CGPoint(x: 90, y: 7), c2: CGPoint(x: 109, y: 25), end: CGPoint(x: 108, y: 46)),
            PenCubic(c1: CGPoint(x: 107, y: 70), c2: CGPoint(x: 82, y: 85), end: CGPoint(x: 53, y: 83)),
            PenCubic(c1: CGPoint(x: 23, y: 81), c2: CGPoint(x: 6, y: 63), end: CGPoint(x: 7, y: 41)),
            PenCubic(c1: CGPoint(x: 9, y: 19), c2: CGPoint(x: 31, y: 6), end: CGPoint(x: 62, y: 8)),
            PenCubic(c1: CGPoint(x: 73, y: 9), c2: CGPoint(x: 80, y: 11.5), end: CGPoint(x: 86, y: 15)),
        ]),
    ]

    static func penPath(for id: String) -> PenPath {
        penPaths[stableIndex(id, modulo: penPaths.count)]
    }

    static func stableIndex(_ id: String, modulo: Int) -> Int {
        guard modulo > 0 else { return 0 }
        if let n = Int64(id) {
            let mod = n % Int64(modulo)
            return Int(mod >= 0 ? mod : mod + Int64(modulo))
        }
        var hash: UInt64 = 0
        for byte in id.utf8 { hash = hash &* 131 &+ UInt64(byte) }
        return Int(hash % UInt64(modulo))
    }
}

struct PenCubic: Equatable {
    var c1: CGPoint
    var c2: CGPoint
    var end: CGPoint
}

struct PenPath: Equatable {
    var start: CGPoint
    var cubics: [PenCubic]

    func cgPath(in photo: CGRect, margin: CGFloat = FilmGeometryRules.penMargin) -> CGPath {
        let box = photo.insetBy(dx: -margin, dy: -margin)
        let sx = box.width / 114
        let sy = box.height / 90
        func map(_ p: CGPoint) -> CGPoint {
            CGPoint(x: box.minX + p.x * sx, y: box.minY + p.y * sy)
        }
        let path = CGMutablePath()
        path.move(to: map(start))
        for cubic in cubics {
            path.addCurve(to: map(cubic.end), control1: map(cubic.c1), control2: map(cubic.c2))
        }
        return path
    }
}

/// 贴边自动滚动。速度单位 pt/s。对齐 Android `EdgeScroll`。
enum EdgeScroll {
    static let delayMs: Double = 150
    static let rampMs: Double = 120
    static let stopMs: Double = 40
    static let maxSpeed: Double = 600
    static let snapSpeed: Double = 20

    static func depth(pos: Double, start: Double, end: Double, zone: Double) -> Double {
        if zone <= 0 || end - start <= 2 * zone { return 0 }
        if pos < start + zone { return -min(1, max(0, (start + zone - pos) / zone)) }
        if pos > end - zone { return min(1, max(0, (pos - (end - zone)) / zone)) }
        return 0
    }

    static func targetSpeed(depth: Double) -> Double {
        let sign: Double = depth < 0 ? -1 : (depth > 0 ? 1 : 0)
        return sign * maxSpeed * depth * depth
    }

    static func approach(v: Double, target: Double, dtMs: Double) -> Double {
        let speedingUp = abs(target) >= abs(v) && (v == 0 || (v > 0) == (target > 0))
        let tau = speedingUp ? rampMs : stopMs
        let next = v + (target - v) * (1 - exp(-dtMs / tau))
        if target == 0 && abs(next) < snapSpeed { return 0 }
        return next
    }
}

/// 一次拖动里的贴边滚动：进入贴边区的时间和当前速度。
final class EdgeScroller {
    private(set) var velocity: Double = 0
    private var inZoneMs: Double = 0

    /// 推进 `dtMs`。返回这一帧该滚的 pt（带方向）。
    func step(depth: Double, dtMs: Double) -> Double {
        inZoneMs = depth == 0 ? 0 : inZoneMs + dtMs
        let target = (depth != 0 && inZoneMs >= EdgeScroll.delayMs) ? EdgeScroll.targetSpeed(depth: depth) : 0
        velocity = EdgeScroll.approach(v: velocity, target: target, dtMs: dtMs)
        return velocity * dtMs / 1000
    }

    func hitEnd() {
        velocity = 0
    }
}

/// 名签位置，单位 pt。
enum TagPlacement {
    static func bottom(fingerY: Double, envelopeTop: Double, fingerGap: Double, fanClear: Double) -> Double {
        min(fingerY - fingerGap, envelopeTop - fanClear)
    }

    static func fingerGap(lift: Double, stackHalfHeight: Double, gap: Double) -> Double {
        lift + stackHalfHeight + gap
    }

    static func stackHalfHeight(frameW: Double, frameH: Double, scale: Double, maxRotationDeg: Double, badgeHalf: Double) -> Double {
        let r = maxRotationDeg * .pi / 180
        let w = frameW * scale / 2
        let h = frameH * scale / 2
        let rotated = h * cos(r) + w * sin(r)
        return max(rotated, h + badgeHalf)
    }

    static func left(centerX: Double, width: Double, screenWidth: Double, margin: Double) -> Double {
        let raw = centerX - width / 2
        let upper = max(margin, screenWidth - margin - width)
        return min(max(raw, margin), upper)
    }
}

struct SheetPrintRef: Equatable {
    var id: String
    /// 没有拍摄时间是 nil，排在最后。
    var taken: TimeInterval?
}

/// 面板和袋口按拍摄时间取最新几张。第一张是封面。
enum SheetPrints {
    static let count = 3

    static func of(_ recent: [SheetPrintRef], limit: Int = count) -> [SheetPrintRef] {
        recent.sorted { a, b in
            switch (a.taken, b.taken) {
            case let (l?, r?) where l != r: return l > r
            case (.some, .none): return true
            case (.none, .some): return false
            default: return idIsAfter(a.id, b.id)
            }
        }.prefix(limit).map { $0 }
    }

    /// 数字 id 按数值倒序，和 Android `Long` 一致；其它按字符串倒序。
    static func idIsAfter(_ a: String, _ b: String) -> Bool {
        if let ia = Int(a), let ib = Int(b) { return ia > ib }
        return a > b
    }
}

/// 袋子上停住的张数和露角。`id` 是相册 localIdentifier。
struct HeldAlbumSnap: Equatable {
    var id: String
    var count: Int
    var coverId: String?
}

enum HeldEnvelope {
    static func shown(album: HeldAlbumSnap, picked: HeldAlbumSnap?, inFlight: HeldAlbumSnap?) -> HeldAlbumSnap {
        if let inFlight, inFlight.id == album.id { return inFlight }
        if let picked, picked.id == album.id { return picked }
        return album
    }
}

/// 拿起 / 落回的错开进度。`duration` 与 `liftStagger` 同一单位。
enum FilingLiftRules {
    static let liftStagger: Double = 0.020
    static let liftStaggerSteps = 4
    static let landHandoff: Double = 0.2

    static func liftProgress(lift: Double, index: Int, duration: Double) -> Double {
        guard duration > 0 else { return lift >= 1 ? 1 : 0 }
        let step = liftStagger / duration
        let span = 1 - Double(liftStaggerSteps) * step
        let k = min(index, liftStaggerSteps)
        guard span > 0 else { return min(1, max(0, lift)) }
        return min(1, max(0, (lift - Double(k) * step) / span))
    }

    static func slotAlphaFor(returning: Bool, t: Double) -> Double {
        guard returning else { return 0 }
        guard landHandoff > 0 else { return t <= 0 ? 1 : 0 }
        return min(1, max(0, 1 - t / landHandoff))
    }
}
