import SwiftUI
import UIKit
import Photos
import CoreImage

/// 清理页手势与离场的纯计算：数值全部对齐 Android `ReviewGestureMath` / `ExitPath` / `GestureTokens`。
enum ReviewTokens {
    static let verticalThreshold: CGFloat = 120
    static let verticalFling: CGFloat = 1000
    static let minFlingDistance: CGFloat = 24
    static let horizontalFraction: CGFloat = 0.12
    static let horizontalMin: CGFloat = 48
    static let horizontalMax: CGFloat = 96
    static let horizontalFling: CGFloat = 600
    static let effectCap: CGFloat = 170
    static let directionRatio: CGFloat = 1.2
    static let touchSlop: CGFloat = 8
    static let dragMinScale: CGFloat = 0.9
    static let dragMinSaturation: CGFloat = 0.35
    static let dragMinBrightness: CGFloat = 0.55
    static let pageEnterBrightness: CGFloat = 0.7
    static let liftCorner: CGFloat = 32
    static let upGlowPre: CGFloat = 0.38
    static let downVignettePre: CGFloat = 0.5
    static let popOvershoot: CGFloat = 1.14
    static let exitDuration: Double = 0.250
    static let releaseCueFade: Double = 0.266
    static let riseFraction: Double = 0.28
    static let exitEndScale: CGFloat = 0.05
    static let trashEndBrightness: CGFloat = 0.14
    static let entryScaleFrom: CGFloat = 0.94
    static let entryBrightnessFrom: CGFloat = 0.6
    static let entryTween: Double = 0.264
    static let undoFlight: Double = 0.450
    /// 两层琥珀余影：延迟比例 / 透明度（50 / 100ms of 250ms）。
    static let afterimages: [(Double, Double)] = [(0.2, 0.4), (0.4, 0.18)]
}

/// CSS / Compose 同款 cubic-bezier。
struct CubicBezier {
    let x1, y1, x2, y2: Double
    init(_ x1: Double, _ y1: Double, _ x2: Double, _ y2: Double) { self.x1 = x1; self.y1 = y1; self.x2 = x2; self.y2 = y2 }
    private func bez(_ t: Double, _ a: Double, _ b: Double) -> Double {
        let u = 1 - t
        return 3 * u * u * t * a + 3 * u * t * t * b + t * t * t
    }
    func callAsFunction(_ x: Double) -> Double {
        let x = min(max(x, 0), 1)
        if x == 0 || x == 1 { return x }
        var lo = 0.0, hi = 1.0, t = x
        for _ in 0..<24 {
            let v = bez(t, x1, x2)
            if abs(v - x) < 1e-5 { break }
            if v < x { lo = t } else { hi = t }
            t = (lo + hi) / 2
        }
        return bez(t, y1, y2)
    }
    static let standard = CubicBezier(0.2, 0, 0, 1)
    static let decelerate = CubicBezier(0.05, 0.7, 0.1, 1)
    static let accelerate = CubicBezier(0.3, 0, 0.8, 0.15)
    static let develop = CubicBezier(0.45, 0, 0.2, 1)
}

/// mass = 1 的弹簧从 0 到 1（初速 0）在 t 秒时的值（Android SpringToken.valueAt 解析解）。
struct SpringToken {
    let damping: Double
    let stiffness: Double
    func value(at t: Double) -> Double {
        if t <= 0 { return 0 }
        let w = stiffness.squareRoot()
        if damping < 1 {
            let wd = w * (1 - damping * damping).squareRoot()
            return 1 - exp(-damping * w * t) * (cos(wd * t) + damping * w / wd * sin(wd * t))
        }
        return 1 - (1 + w * t) * exp(-w * t)
    }
    /// SwiftUI 动画（带松手速度，速度为「每秒走完全程的比例」）。
    func animation(initialVelocity: Double = 0) -> Animation {
        .interpolatingSpring(mass: 1, stiffness: stiffness, damping: 2 * damping * stiffness.squareRoot(), initialVelocity: initialVelocity)
    }
    static let snappy = SpringToken(damping: 1.0, stiffness: 1500)
    static let settle = SpringToken(damping: 0.75, stiffness: 400)
    static let bouncy = SpringToken(damping: 0.5, stiffness: 400)
    /// 舒缓 0.85 / 200：小结、对话框。
    static let gentle = SpringToken(damping: 0.85, stiffness: 200)
}

enum ReviewMath {
    static func effectFraction(_ dy: CGFloat) -> CGFloat { min(1, abs(dy) / ReviewTokens.effectCap) }
    static func dragScale(_ dy: CGFloat) -> CGFloat { 1 - (1 - ReviewTokens.dragMinScale) * effectFraction(dy) }
    static func dragSaturation(_ dy: CGFloat) -> CGFloat { dy <= 0 ? 1 : 1 - (1 - ReviewTokens.dragMinSaturation) * effectFraction(dy) }
    static func dragBrightness(_ dy: CGFloat) -> CGFloat { dy <= 0 ? 1 : 1 - (1 - ReviewTokens.dragMinBrightness) * effectFraction(dy) }
    static func cueProgress(_ dy: CGFloat) -> CGFloat { min(1, abs(dy) / ReviewTokens.verticalThreshold) }
    static func enteringBrightness(_ dx: CGFloat, width: CGFloat) -> CGFloat {
        let f = min(1, abs(dx) / max(1, width))
        return ReviewTokens.pageEnterBrightness + (1 - ReviewTokens.pageEnterBrightness) * f
    }
    static func horizontalThreshold(width: CGFloat) -> CGFloat {
        min(max(width * ReviewTokens.horizontalFraction, ReviewTokens.horizontalMin), ReviewTokens.horizontalMax)
    }
    /// 会话首尾越界阻尼 offset = L·(1 − 1/(d·0.55/L + 1))，L = 屏宽 × 0.3。
    static func edgeResistance(_ d: CGFloat, length: CGFloat) -> CGFloat {
        let s: CGFloat = d < 0 ? -1 : 1
        let a = abs(d)
        return s * length * (1 - 1 / (a * 0.55 / length + 1))
    }
    enum VerticalRelease { case up, down, none }
    static func verticalRelease(dy: CGFloat, vy: CGFloat) -> VerticalRelease {
        let byDistance = abs(dy) > ReviewTokens.verticalThreshold
        let byFling = abs(dy) >= ReviewTokens.minFlingDistance && abs(vy) > ReviewTokens.verticalFling && (vy * dy > 0)
        if !byDistance && !byFling { return .none }
        if byDistance && vy != 0 && (vy * dy < 0) && abs(vy) > ReviewTokens.verticalFling { return .none }
        return dy < 0 ? .up : .down
    }
    /// -1 = 下一张（左滑），1 = 上一张（右滑），0 = 回位。
    static func horizontalRelease(dx: CGFloat, vx: CGFloat, width: CGFloat) -> Int {
        if dx == 0 { return 0 }
        let sameDir = vx == 0 || (vx * dx > 0)
        let byFling = abs(vx) > ReviewTokens.horizontalFling && (vx * dx > 0)
        let byDistance = abs(dx) > horizontalThreshold(width: width) && (sameDir || abs(vx) <= ReviewTokens.horizontalFling)
        return (byFling || byDistance) ? (dx < 0 ? -1 : 1) : 0
    }
    static func fitSize(pixelWidth: Int, pixelHeight: Int, in box: CGSize) -> CGSize {
        guard pixelWidth > 0, pixelHeight > 0 else { return box }
        let s = min(box.width / CGFloat(pixelWidth), box.height / CGFloat(pixelHeight))
        return CGSize(width: CGFloat(pixelWidth) * s, height: CGFloat(pixelHeight) * s)
    }
}

enum ExitKind { case up, down }

/// 离场层：独立播放 250ms，不持有输入（当前页立刻换成下一张）。
struct ExitFlight: Identifiable {
    let id = UUID()
    let page: ReviewPage
    let kind: ExitKind
    let startY: CGFloat
    let startScale: CGFloat
    let startSaturation: CGFloat
    let startBrightness: CGFloat
    let rise: CGFloat
    let target: CGPoint
    let start: Date

    struct Pose { var x: CGFloat; var y: CGFloat; var scale: CGFloat; var saturation: CGFloat; var brightness: CGFloat; var alpha: CGFloat }

    func progress(_ now: Date) -> Double { min(1, max(0, now.timeIntervalSince(start) / ReviewTokens.exitDuration)) }

    func pose(_ p: Double) -> Pose {
        let p = min(max(p, 0), 1)
        let acc = CubicBezier.accelerate
        let fade: CGFloat = p < 0.8 ? 1 : CGFloat(1 - (p - 0.8) / 0.2)
        switch kind {
        case .up:
            var x: CGFloat = 0, y: CGFloat
            let rf = ReviewTokens.riseFraction
            if p < rf {
                y = startY - rise * CGFloat(CubicBezier.decelerate(p / rf))
            } else {
                let q = CGFloat(acc((p - rf) / (1 - rf)))
                y = (startY - rise) + (target.y - (startY - rise)) * q
                x = target.x * q
            }
            let s = startScale + (ReviewTokens.exitEndScale - startScale) * CGFloat(acc(p))
            return Pose(x: x, y: y, scale: s, saturation: startSaturation, brightness: startBrightness, alpha: fade)
        case .down:
            let q = CGFloat(acc(p))
            return Pose(
                x: target.x * q,
                y: startY + (target.y - startY) * q,
                scale: startScale + (ReviewTokens.exitEndScale - startScale) * q,
                saturation: startSaturation * (1 - q),
                brightness: startBrightness + (ReviewTokens.trashEndBrightness - startBrightness) * q,
                alpha: fade
            )
        }
    }
}

/// 下一张出场：0.94 → 1（settle 弹簧），亮度 0.6 → 1（264ms 显影曲线）。撤回时从去处减速飞回。
struct EntryAnim {
    let pageId: String
    let start: Date
    /// 撤回飞回的起点（相对屏幕中心）；nil = 普通「下一张升起」。
    var undoFrom: CGPoint? = nil
    var undoKind: ExitKind? = nil

    func scale(_ now: Date) -> CGFloat {
        let t = now.timeIntervalSince(start)
        if undoFrom != nil {
            let q = CGFloat(CubicBezier.decelerate(t / ReviewTokens.undoFlight))
            return ReviewTokens.exitEndScale + (1 - ReviewTokens.exitEndScale) * q
        }
        return ReviewTokens.entryScaleFrom + (1 - ReviewTokens.entryScaleFrom) * CGFloat(SpringToken.settle.value(at: t))
    }
    func brightness(_ now: Date) -> CGFloat {
        let t = now.timeIntervalSince(start)
        if let k = undoKind {
            let develop = CGFloat(min(1, max(0, (t / ReviewTokens.undoFlight - 0.5) / 0.5)))
            let b0: CGFloat = k == .down ? ReviewTokens.trashEndBrightness : ReviewTokens.entryBrightnessFrom
            return b0 + (1 - b0) * develop
        }
        let q = CGFloat(CubicBezier.develop(t / ReviewTokens.entryTween))
        return ReviewTokens.entryBrightnessFrom + (1 - ReviewTokens.entryBrightnessFrom) * q
    }
    func saturation(_ now: Date) -> CGFloat {
        guard undoKind == .down else { return 1 }
        let t = now.timeIntervalSince(start)
        return CGFloat(min(1, max(0, (t / ReviewTokens.undoFlight - 0.5) / 0.5)))
    }
    func offset(_ now: Date) -> CGPoint {
        guard let from = undoFrom else { return .zero }
        let q = CGFloat(CubicBezier.decelerate(now.timeIntervalSince(start) / ReviewTokens.undoFlight))
        return CGPoint(x: from.x * (1 - q), y: from.y * (1 - q))
    }
    func alpha(_ now: Date) -> CGFloat {
        guard undoFrom != nil else { return 1 }
        return CGFloat(min(1, now.timeIntervalSince(start) / ReviewTokens.undoFlight / 0.15))
    }
    var duration: Double { undoFrom != nil ? ReviewTokens.undoFlight : 0.6 }
}

struct ReleaseCue {
    let up: Bool
    let start: Date
    func alpha(_ now: Date) -> CGFloat { CGFloat(max(0, 1 - now.timeIntervalSince(start) / ReviewTokens.releaseCueFade)) }
}

/// 当前 / 前后几张的大图缓存，按照片 id 取（不按「当前页」取，避免换页时显示上一张的图）。
@MainActor
final class ReviewImageStore: ObservableObject {
    @Published private(set) var images: [String: UIImage] = [:]
    /// 预渲染好的环境光位图（放大 1.2、模糊 36、饱和 1.3、亮度 40%），翻页时只平移贴图、不再每帧实时模糊。
    @Published private(set) var glows: [String: UIImage] = [:]
    /// 放大查看时才要的高清图（长边 = 屏幕长边像素，与改动前的请求尺寸一致）。
    @Published private(set) var zoomed: [String: UIImage] = [:]
    private var requests: [String: PHImageRequestID] = [:]
    private var zoomRequests: [String: PHImageRequestID] = [:]
    private var revs: [String: Int] = [:]
    nonisolated private static let work = DispatchQueue(label: "review.decode", qos: .userInitiated)
    /// 调试 / 像素对比：-cpLiveGlow YES 退回实时模糊
    nonisolated static let liveGlow = UserDefaults.standard.bool(forKey: "cpLiveGlow") || PerfLegacy.on

    func image(_ id: String) -> UIImage? { images[id] }
    func glow(_ id: String) -> UIImage? { glows[id] }
    func zoomImage(_ id: String) -> UIImage? { zoomed[id] }
    /// 页内容的版本号：图 / 环境光 / 高清图任一更新都 +1（页视图按它判断要不要重画）。
    func rev(_ id: String) -> Int { revs[id, default: 0] }

    /// 共享元素展开时先垫上网格缩略图（Android placeholderMemoryCacheKey），大图到了再替换，过程中没有空白帧。
    func seed(_ id: String, _ img: UIImage?) {
        guard let img, images[id] == nil else { return }
        images[id] = img
        revs[id, default: 0] += 1
    }

    /// 预取当前页前后几张：按「在屏幕上实际显示的像素尺寸」请求，后台解码成位图（preparingThumbnail），
    /// 没铺满的再在后台算好环境光，主线程只做一次字典替换 —— 拖动 / 翻页过程中不再有解码和模糊。
    func ensure(_ items: [PhotoItem], keep: Set<String>, library: PhotoLibraryService, page: CGSize, box: CGSize) {
        let want = Set(items.map(\.id)).union(keep)
        let scale = UIScreen.main.scale
        for item in items where requests[item.id] == nil {
            let id = item.id
            let fit = ReviewMath.fitSize(pixelWidth: item.asset.pixelWidth, pixelHeight: item.asset.pixelHeight, in: box)
            let letterboxed = fit.width < box.width - 1 || fit.height < box.height - 1
            let px = CGSize(width: (fit.width * scale).rounded(.up), height: (fit.height * scale).rounded(.up))
            if PerfLegacy.on {
                // 改之前：按屏幕长边请求、主线程直接用（不预解码）
                requests[id] = library.requestFullImage(for: item.asset, maxSide: max(page.width, page.height)) { [weak self] img in
                    Task { @MainActor in
                        guard let self, let img, self.requests[id] != nil else { return }
                        if let cur = self.images[id], img.size.width * img.scale < cur.size.width * cur.scale { return }
                        self.images[id] = img
                        self.revs[id, default: 0] += 1
                    }
                }
                continue
            }
            requests[id] = library.requestFullImage(for: item.asset, maxSide: max(fit.width, fit.height)) { [weak self] img in
                guard let img else { return }
                Self.work.async {
                    let shown: UIImage
                    if img.size.width * img.scale > px.width * 1.05 || img.size.height * img.scale > px.height * 1.05 {
                        shown = img.preparingThumbnail(of: px) ?? img
                    } else {
                        shown = img.preparingForDisplay() ?? img
                    }
                    let glow = (letterboxed && !Self.liveGlow) ? Self.makeGlow(shown, page: page) : nil
                    DispatchQueue.main.async {
                        MainActor.assumeIsolated {
                            guard let self, self.requests[id] != nil else { return }
                            // opportunistic 先给低清：不拿它覆盖已经垫上的缩略图里更清楚的那张
                            if let cur = self.images[id], shown.size.width * shown.scale < cur.size.width * cur.scale {
                                if glow != nil, self.glows[id] == nil { self.glows[id] = glow; self.revs[id, default: 0] += 1 }
                                return
                            }
                            self.images[id] = shown
                            if let glow { self.glows[id] = glow }
                            self.revs[id, default: 0] += 1
                        }
                    }
                }
            }
        }
        for (id, req) in requests where !want.contains(id) {
            library.cancelThumbnail(req)
            requests[id] = nil
            images[id] = nil
            glows[id] = nil
            revs[id] = nil
        }
        for (id, req) in zoomRequests where !want.contains(id) {
            library.cancelThumbnail(req)
            zoomRequests[id] = nil
            zoomed[id] = nil
        }
    }

    /// 放大当前照片时补一张高清（长边 = 屏幕长边像素）；缩回不丢，翻走时随窗口回收。
    func ensureZoom(_ item: PhotoItem, library: PhotoLibraryService, side: CGFloat) {
        let id = item.id
        guard zoomRequests[id] == nil else { return }
        zoomRequests[id] = library.requestFullImage(for: item.asset, maxSide: side) { [weak self] img in
            guard let img else { return }
            Self.work.async {
                let ready = img.preparingForDisplay() ?? img
                DispatchQueue.main.async {
                    MainActor.assumeIsolated {
                        guard let self, self.zoomRequests[id] != nil else { return }
                        if let cur = self.zoomed[id], ready.size.width * ready.scale <= cur.size.width * cur.scale { return }
                        self.zoomed[id] = ready
                        self.revs[id, default: 0] += 1
                    }
                }
            }
        }
    }

    nonisolated(unsafe) private static let ciContext = CIContext(options: [.workingColorSpace: CGColorSpace(name: CGColorSpace.sRGB)!,
                                                       .outputColorSpace: CGColorSpace(name: CGColorSpace.sRGB)!])
    /// 环境光的像素密度（相对 pt）：模糊半径 36pt，1/4 密度下 9px，放大回去肉眼与实时模糊一致。
    nonisolated static let glowDensity: CGFloat = 0.25

    /// 复刻原来的 SwiftUI 链：scaledToFill 到整页 → scaleEffect(1.2) → blur(36) → saturation(1.3) → colorMultiply(0.4) → 裁到整页。
    nonisolated static func makeGlow(_ img: UIImage, page: CGSize) -> UIImage? {
        let k = glowDensity
        let radius = 36 * k
        let pad = (radius * 3).rounded(.up)
        let w = max(1, (page.width * k).rounded(.up)), h = max(1, (page.height * k).rounded(.up))
        let iw = max(1, img.size.width), ih = max(1, img.size.height)
        let fill = max(w / iw, h / ih) * 1.2
        let dw = iw * fill, dh = ih * fill
        let canvas = CGSize(width: w + pad * 2, height: h + pad * 2)
        let fmt = UIGraphicsImageRendererFormat()
        fmt.scale = 1
        fmt.opaque = false
        let src = UIGraphicsImageRenderer(size: canvas, format: fmt).image { ctx in
            ctx.cgContext.interpolationQuality = .medium
            img.draw(in: CGRect(x: pad + (w - dw) / 2, y: pad + (h - dh) / 2, width: dw, height: dh))
        }
        guard let cg = src.cgImage else { return nil }
        let out = CIImage(cgImage: cg)
            .applyingFilter("CIGaussianBlur", parameters: [kCIInputRadiusKey: radius])
            .applyingFilter("CIColorControls", parameters: [kCIInputSaturationKey: 1.3, kCIInputBrightnessKey: 0, kCIInputContrastKey: 1])
            .applyingFilter("CIColorMatrix", parameters: [
                "inputRVector": CIVector(x: 0.4, y: 0, z: 0, w: 0),
                "inputGVector": CIVector(x: 0, y: 0.4, z: 0, w: 0),
                "inputBVector": CIVector(x: 0, y: 0, z: 0.4, w: 0),
            ])
            .cropped(to: CGRect(x: pad, y: pad, width: w, height: h))
        guard let res = ciContext.createCGImage(out, from: out.extent) else { return nil }
        return UIImage(cgImage: res, scale: 1, orientation: .up)
    }
}

/// 只在 key 变化时才重算内容的包装：拖动时父视图每帧都在算 body，页内容 / 顶栏 / 导航这些跟 dx 无关的部分直接跳过。
struct EquatableBox<Key: Equatable, Content: View>: View, Equatable {
    let key: Key
    @ViewBuilder let content: () -> Content
    static func == (a: Self, b: Self) -> Bool { !PerfLegacy.on && a.key == b.key }
    var body: some View { content() }
}

/// 复用同一组反馈生成器并在开始上下拖动时 prepare：每次过阈值临时 new 一个生成器要在主线程现起 Taptic 引擎，正好卡在拖动那一帧。
@MainActor
enum ReviewHaptics {
    private static let medium = UIImpactFeedbackGenerator(style: .medium)
    private static let soft = UIImpactFeedbackGenerator(style: .soft)
    private static let selection = UISelectionFeedbackGenerator()
    static func prepare() { medium.prepare(); soft.prepare() }
    static func cross() { medium.impactOccurred(); medium.prepare() }
    static func back() { soft.impactOccurred(intensity: 0.5); soft.prepare() }
    static func tick() { selection.selectionChanged() }
}
