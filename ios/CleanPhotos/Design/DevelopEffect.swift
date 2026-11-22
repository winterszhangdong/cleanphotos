import Accelerate
import QuartzCore
import UIKit

/// 照片 / 收藏 / 相似各播一次首屏显影。滚动或离页之后 `markPlayed`，后来的格子直接出图。
@MainActor
enum GridDevelopSession {
    typealias Grid = DevelopGrid

    private static var tracker = DevelopSessionTracker()

    static func seed(grid: Grid, id: String) {
        tracker.seed(grid: grid, id: id)
    }

    static func claim(grid: Grid, id: String) -> Bool {
        tracker.claim(grid: grid, id: id)
    }

    static func release(grid: Grid, id: String) {
        tracker.release(grid: grid, id: id)
    }

    static func markPlayed(_ grid: Grid) {
        tracker.markPlayed(grid)
    }

    static func hasPlayed(_ grid: Grid) -> Bool {
        tracker.hasPlayed(grid)
    }

    static func staggerDelay(row: Int, col: Int) -> Double {
        DevelopGridProfile.staggerDelay(row: row, col: col)
    }
}

/// 小缩略图一次 Rec.709 灰度。和 `TrashedColorMatrix` 同一套小端 BGRX 权重，不做 `CIFilter`。
@MainActor
enum DevelopGrayscale {
    private static let cache = NSCache<NSString, UIImage>()

    static func image(from source: UIImage, assetId: String) -> UIImage {
        guard let cg = source.cgImage else { return source }
        let key = "\(assetId)#\(cg.width)x\(cg.height)" as NSString
        if let hit = cache.object(forKey: key) { return hit }
        let gray = convert(cg, scale: source.scale, orientation: source.imageOrientation) ?? source
        cache.setObject(gray, forKey: key, cost: cg.width * cg.height * 4)
        return gray
    }

    static func removeAll() {
        cache.removeAllObjects()
    }

    private static func convert(_ cg: CGImage, scale: CGFloat, orientation: UIImage.Orientation) -> UIImage? {
        let width = cg.width
        let height = cg.height
        guard width > 0, height > 0 else { return nil }
        let rowBytes = ((width * 4 + 63) / 64) * 64
        let colorSpace = CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB()
        let bitmapInfo = CGBitmapInfo.byteOrder32Little.rawValue | CGImageAlphaInfo.noneSkipFirst.rawValue
        guard let ctx = CGContext(
            data: nil,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: rowBytes,
            space: colorSpace,
            bitmapInfo: bitmapInfo
        ) else { return nil }
        ctx.interpolationQuality = .medium
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: width, height: height))
        guard let data = ctx.data else { return nil }
        var buffer = vImage_Buffer(
            data: data,
            height: vImagePixelCount(height),
            width: vImagePixelCount(width),
            rowBytes: rowBytes
        )
        let error = vImageMatrixMultiply_ARGB8888(
            &buffer,
            &buffer,
            DevelopGrayscaleMatrix.bgraMatrix,
            DevelopGrayscaleMatrix.divisor,
            nil,
            nil,
            vImage_Flags(kvImageNoFlags)
        )
        guard error == kvImageNoError, let out = ctx.makeImage() else { return nil }
        return UIImage(cgImage: out, scale: scale, orientation: orientation)
    }
}

/// 全进程一个 120Hz `CADisplayLink`。没有在播的格子就停表。
@MainActor
final class DevelopClock {
    static let shared = DevelopClock()

    private struct Item {
        let start: CFTimeInterval
        let delay: Double
        let apply: (Double) -> Void
        let complete: () -> Void
    }

    private var link: CADisplayLink?
    private var items: [ObjectIdentifier: Item] = [:]

    /// 模拟器审计：`-cpDevelopFreezeMs 320` 把每一格钉在这个时刻，不自动收掉。
    static var freezeSeconds: Double {
        guard AuditHooks.enabled else { return 0 }
        let stored = UserDefaults.standard.integer(forKey: "cpDevelopFreezeMs")
        if stored > 0 { return Double(stored) / 1000 }
        let args = ProcessInfo.processInfo.arguments
        guard let flag = args.firstIndex(of: "-cpDevelopFreezeMs"),
              flag + 1 < args.count,
              let ms = Int(args[flag + 1]),
              ms > 0 else { return 0 }
        return Double(ms) / 1000
    }

    func register(
        id: ObjectIdentifier,
        delay: Double,
        apply: @escaping (Double) -> Void,
        complete: @escaping () -> Void
    ) {
        let start = CACurrentMediaTime()
        items[id] = Item(start: start, delay: delay, apply: apply, complete: complete)
        apply(fraction(for: items[id]!, now: CACurrentMediaTime()))
        startLinkIfNeeded()
    }

    func unregister(_ id: ObjectIdentifier) {
        items.removeValue(forKey: id)
        if items.isEmpty {
            stopLink(clearGrays: true)
        }
    }

    fileprivate func step() {
        guard !items.isEmpty else {
            stopLink(clearGrays: true)
            return
        }
        let now = CACurrentMediaTime()
        let frozen = Self.freezeSeconds > 0
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        var finished: [ObjectIdentifier] = []
        for (id, item) in items {
            let fraction = fraction(for: item, now: now)
            item.apply(fraction)
            if !frozen && fraction >= 1 {
                finished.append(id)
            }
        }
        CATransaction.commit()
        guard !frozen else { return }
        for id in finished {
            let item = items.removeValue(forKey: id)
            item?.complete()
        }
        if items.isEmpty {
            stopLink(clearGrays: true)
        }
    }

    private func fraction(for item: Item, now: CFTimeInterval) -> Double {
        let freeze = Self.freezeSeconds
        let sample = freeze > 0 ? item.start + freeze : now
        return DevelopCompositor.elapsedFraction(
            now: sample,
            start: item.start,
            delay: item.delay,
            duration: DevelopGridProfile.duration
        )
    }

    private func startLinkIfNeeded() {
        guard link == nil else { return }
        let link = CADisplayLink(target: DevelopClockProxy.shared, selector: #selector(DevelopClockProxy.tick(_:)))
        link.preferredFrameRateRange = CAFrameRateRange(minimum: 60, maximum: 120, preferred: 120)
        link.add(to: .main, forMode: .common)
        self.link = link
    }

    private func stopLink(clearGrays: Bool) {
        link?.invalidate()
        link = nil
        if clearGrays, items.isEmpty {
            DevelopGrayscale.removeAll()
        }
    }
}

private final class DevelopClockProxy: NSObject {
    static let shared = DevelopClockProxy()

    @objc func tick(_ link: CADisplayLink) {
        MainActor.assumeIsolated {
            DevelopClock.shared.step()
        }
    }
}

/// 格子上的灰度图 + 纯灰幕。角标留在这两层上面。非显影格子不创建子视图。
@MainActor
final class TileDevelopOverlay {
    private var grayView: UIImageView?
    private var veilView: UIView?
    private var clockId: ObjectIdentifier?
    private(set) var isActive = false

    func start(
        image: UIImage,
        assetId: String,
        delay: Double,
        in container: UIView,
        above imageView: UIImageView,
        bounds: CGRect
    ) {
        let gray = ensureGray(in: container, above: imageView)
        let veil = ensureVeil(in: container, above: gray)
        gray.image = DevelopGrayscale.image(from: image, assetId: assetId)
        gray.frame = bounds
        veil.frame = bounds
        gray.isHidden = false
        veil.isHidden = false
        isActive = true
        guard clockId == nil else { return }
        let id = ObjectIdentifier(self)
        clockId = id
        // register 在返回前同步套用第 0 帧（或审计冻结时刻），第一帧就是暗雾。
        DevelopClock.shared.register(id: id, delay: delay, apply: { [weak self] value in
            self?.apply(elapsedFraction: value)
        }, complete: { [weak self] in
            self?.finish()
        })
    }

    func updateImage(_ image: UIImage, assetId: String) {
        guard isActive, let grayView else { return }
        grayView.image = DevelopGrayscale.image(from: image, assetId: assetId)
    }

    func reset() {
        if let clockId {
            DevelopClock.shared.unregister(clockId)
            self.clockId = nil
        }
        isActive = false
        grayView?.image = nil
        grayView?.isHidden = true
        grayView?.alpha = 0
        veilView?.isHidden = true
        veilView?.backgroundColor = .clear
    }

    func layout(in bounds: CGRect) {
        grayView?.frame = bounds
        veilView?.frame = bounds
    }

    private func apply(elapsedFraction: Double) {
        guard isActive, let grayView, let veilView else { return }
        let veil = DevelopCompositor.overlay(elapsedFraction: elapsedFraction)
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        grayView.alpha = CGFloat(veil.grayAlpha)
        veilView.backgroundColor = UIColor(white: CGFloat(veil.veilWhite), alpha: CGFloat(veil.veilAlpha))
        CATransaction.commit()
    }

    private func finish() {
        clockId = nil
        isActive = false
        grayView?.isHidden = true
        grayView?.image = nil
        grayView?.alpha = 0
        veilView?.isHidden = true
        veilView?.backgroundColor = .clear
    }

    private func ensureGray(in container: UIView, above imageView: UIImageView) -> UIImageView {
        if let grayView { return grayView }
        let view = UIImageView()
        view.contentMode = .scaleAspectFill
        view.clipsToBounds = true
        view.isUserInteractionEnabled = false
        view.isAccessibilityElement = false
        view.layer.actions = ["opacity": NSNull()]
        container.insertSubview(view, aboveSubview: imageView)
        grayView = view
        return view
    }

    private func ensureVeil(in container: UIView, above gray: UIImageView) -> UIView {
        if let veilView { return veilView }
        let view = UIView()
        view.isUserInteractionEnabled = false
        view.isAccessibilityElement = false
        view.backgroundColor = .clear
        view.layer.actions = ["backgroundColor": NSNull()]
        container.insertSubview(view, aboveSubview: gray)
        veilView = view
        return view
    }
}
