import Photos
import QuartzCore
import SwiftUI
import UIKit

/// 拖动照片叠、名签和飞入。位置每帧写在 layer 上，不推动 SwiftUI 重组。
@MainActor
final class FilingStageController: ObservableObject {
    @Published var hoverKey: String?
    @Published var fanKey: String?
    @Published var dwellProgress: Double = 0
    @Published var dragging = false
    @Published var closedFlapId: String?
    @Published var bumpId: String?
    var frames: [String: CGRect] = [:]
    var sheetRowFrames: [String: CGRect] = [:]
    var rowFrame: CGRect = .zero
    weak var view: FilingStageView?
    var bridge: SheetBridge?
    var strip: EnvelopeStripController?
    var pipeline: ThumbnailPipeline?
    var fileAlbum: ((String, Bool, Bool) -> Void)?
    var openNew: ((Bool) -> Void)?
    var openMore: ((Bool) -> Void)?
    var titleFor: (String) -> String = { $0 }
    var released: (() -> Void)?
    var finished: ((FilingFlightEvent) -> Void)?
    private var hoverSince: TimeInterval?
    private var lastHover: String?
    private let selection = UISelectionFeedbackGenerator()

    func pickUp(photos: [PhotoItem], finger: CGPoint, homes: [String: CGRect], total: Int) {
        guard let view, let pipeline else { return }
        dragging = true
        fanKey = nil
        hoverKey = nil
        lastHover = nil
        hoverSince = nil
        dwellProgress = 0
        view.begin(photos: photos, homes: homes, finger: finger, pipeline: pipeline, bridge: bridge, badge: total)
    }

    func drag(_ finger: CGPoint) {
        view?.finger = finger
        updateHover(finger)
        if let key = hoverKey { view?.setTagTitle(titleFor(key)) }
    }

    func drop(_ finger: CGPoint) {
        view?.finger = finger
        let key = hit(finger)
        dragging = false
        hoverKey = nil
        lastHover = nil
        fanKey = nil
        hoverSince = nil
        dwellProgress = 0
        guard let key else {
            view?.putBack()
            return
        }
        switch key {
        case EnvelopeKey.newSleeve:
            view?.hold()
            openNew?(true)
        case EnvelopeKey.more:
            view?.hold()
            openMore?(true)
        default:
            view?.hold()
            fileAlbum?(key, false, true)
        }
    }

    func putBack() {
        dragging = false
        hoverKey = nil
        lastHover = nil
        fanKey = nil
        hoverSince = nil
        dwellProgress = 0
        view?.putBack()
    }

    func startFlight(_ event: FilingFlightEvent, photos: [PhotoItem]) {
        guard let view, let pipeline else { return }
        let target = event.intoSheet ? (sheetRowFrames[event.albumId] ?? frames[event.albumId]) : frames[event.albumId]
        view.fly(event: event, photos: photos, target: target, pipeline: pipeline, bridge: bridge) { [weak self] in
            if !event.intoSheet {
                self?.closedFlapId = event.albumId
                self?.bumpId = event.albumId
            }
            self?.released?()
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        } done: { [weak self] in
            self?.closedFlapId = nil
            self?.bumpId = nil
            self?.finished?(event)
        }
    }

    func auditHover(photos: [PhotoItem], finger: CGPoint, homes: [String: CGRect], key: String) {
        pickUp(photos: photos, finger: finger, homes: homes, total: photos.count)
        view?.snapLifted(finger: finger)
        hoverKey = key
        lastHover = key
        hoverSince = CACurrentMediaTime() - FilingMotion.envelopeDwell
        let nonAlbum = key == EnvelopeKey.newSleeve || key == EnvelopeKey.more
        dwellProgress = nonAlbum ? 0 : 1
        fanKey = nonAlbum ? nil : key
        view?.setTagTitle(titleFor(key))
        view?.refreshTag()
        dragging = true
    }

    func tickHoverDwell() {
        guard view?.scrolling != true,
              let key = hoverKey,
              key != EnvelopeKey.newSleeve,
              key != EnvelopeKey.more,
              let since = hoverSince else {
            if dwellProgress != 0 { dwellProgress = 0 }
            return
        }
        let progress = min(1, max(0, (CACurrentMediaTime() - since) / FilingMotion.envelopeDwell))
        if abs(progress - dwellProgress) > 0.02 || (progress >= 1 && dwellProgress < 1) {
            dwellProgress = progress
        }
        if progress >= 1 && fanKey != key {
            fanKey = key
        }
    }

    private func updateHover(_ finger: CGPoint) {
        guard view?.scrolling != true else {
            hoverKey = nil
            lastHover = nil
            fanKey = nil
            hoverSince = nil
            dwellProgress = 0
            return
        }
        let key = hit(finger)
        if key != lastHover {
            lastHover = key
            hoverKey = key
            fanKey = nil
            dwellProgress = 0
            hoverSince = key == nil ? nil : CACurrentMediaTime()
            if key != nil { selection.selectionChanged() }
        }
        tickHoverDwell()
    }

    private func hit(_ finger: CGPoint) -> String? {
        guard rowFrame != .zero else { return nil }
        let band = rowFrame.insetBy(dx: 0, dy: -FilingMotion.edgeBand)
        guard finger.y >= band.minY && finger.y <= band.maxY else { return nil }
        let visibleRow = rowFrame.insetBy(dx: -8, dy: -FilingMotion.edgeBand)
        var best: (String, CGFloat)?
        for (key, frame) in frames where frame.intersects(visibleRow) {
            let expanded = frame.insetBy(dx: -8, dy: -8)
            guard expanded.contains(finger) || abs(finger.x - frame.midX) < frame.width / 2 + 8 else { continue }
            let distance = abs(finger.x - frame.midX)
            if best == nil || distance < best!.1 { best = (key, distance) }
        }
        return best?.0
    }
}

@MainActor
final class FilingStageView: UIView {
    var finger: CGPoint = .zero
    private(set) var scrolling = false
    private var photos: [PhotoItem] = []
    private var homes: [String: CGRect] = [:]
    private var tiles: [UIImageView] = []
    private var tokens: [ThumbnailToken] = []
    private let badge = UILabel()
    private let nameTag = TagView()
    private var link: CADisplayLink?
    private var mode = Mode.idle
    private var modeStart = CACurrentMediaTime()
    private var bridge: SheetBridge?
    private var flightEvent: FilingFlightEvent?
    private var flightTarget: CGRect = .zero
    private var flightStartCenter: CGPoint = .zero
    private var onClosed: (() -> Void)?
    private var onDone: (() -> Void)?
    private var closedSent = false
    private let scroller = EdgeScroller()
    private var lastTick = CACurrentMediaTime()
    var nudge: ((CGFloat) -> Bool)?
    var rowFrame: () -> CGRect = { .zero }
    var envelopeFrames: () -> [String: CGRect] = { [:] }
    var hoverKey: () -> String? = { nil }
    var titleFor: (String) -> String = { $0 }
    var tickHover: (() -> Void)?

    private enum Mode { case idle, lift, drag, back, flight }

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        badge.backgroundColor = DarkroomUIColors.paper
        badge.textColor = DarkroomUIColors.room
        badge.font = DarkroomUIFonts.plexMono(size: 11, weight: .medium)
        badge.textAlignment = .center
        badge.layer.cornerRadius = 10
        badge.layer.zPosition = 100
        badge.clipsToBounds = true
        badge.isHidden = true
        addSubview(badge)
        nameTag.isHidden = true
        nameTag.layer.zPosition = 101
        addSubview(nameTag)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func begin(photos: [PhotoItem], homes: [String: CGRect], finger: CGPoint, pipeline: ThumbnailPipeline, bridge: SheetBridge?, badge badgeCount: Int) {
        clearTiles()
        self.photos = photos
        self.homes = homes
        self.finger = finger
        self.bridge = bridge
        for id in photos.map(\.id) { bridge?.grid?.setSlotAlpha(0, id: id) }
        makeTiles(photos, pipeline: pipeline, side: 160)
        badge.text = "\(badgeCount)"
        badge.isHidden = badgeCount < 2
        mode = UIAccessibility.isReduceMotionEnabled ? .drag : .lift
        modeStart = CACurrentMediaTime()
        lastTick = modeStart
        startLink()
        if UIAccessibility.isReduceMotionEnabled { layout(lift: 1, returning: false) }
    }

    func snapLifted(finger: CGPoint) {
        self.finger = finger
        mode = .drag
        layout(lift: 1, returning: false)
    }

    func hold() {
        mode = .drag
    }

    func refreshTag() {
        placeTag()
    }

    func putBack() {
        mode = .back
        modeStart = CACurrentMediaTime()
        if UIAccessibility.isReduceMotionEnabled {
            finishBack()
        }
    }

    func fly(event: FilingFlightEvent, photos: [PhotoItem], target: CGRect?, pipeline: ThumbnailPipeline, bridge: SheetBridge?, closed: @escaping () -> Void, done: @escaping () -> Void) {
        let existingCenter = tiles.first?.center
        if tiles.isEmpty {
            self.photos = photos
            self.bridge = bridge
            homes = [:]
            for photo in photos {
                if let frame = bridge?.grid?.photoFrameInWindow(photo.id) { homes[photo.id] = frame }
            }
            for id in photos.map(\.id) { bridge?.grid?.setSlotAlpha(0, id: id) }
            makeTiles(photos, pipeline: pipeline, side: 160)
        }
        flightEvent = event
        flightTarget = target.map { local($0) } ?? CGRect(x: bounds.midX - 38, y: bounds.maxY - 180, width: 76, height: 104)
        let rowLocal = local(rowFrame())
        let restingY = rowLocal.height > 1 ? max(120, rowLocal.minY - FilingMotion.stackLift * 2.4) : max(120, bounds.midY)
        let fallbackCenter = CGPoint(x: bounds.midX, y: restingY)
        flightStartCenter = event.fromDrag ? (existingCenter ?? fallbackCenter) : fallbackCenter
        nameTag.isHidden = true
        onClosed = closed
        onDone = done
        closedSent = false
        mode = .flight
        modeStart = CACurrentMediaTime()
        startLink()
        if UIAccessibility.isReduceMotionEnabled {
            closed()
            finishFlight()
        }
    }

    private func startLink() {
        guard link == nil else { return }
        let link = CADisplayLink(target: self, selector: #selector(tick))
        link.add(to: .main, forMode: .common)
        self.link = link
    }

    @objc private func tick() {
        let now = CACurrentMediaTime()
        let dt = min(0.05, now - lastTick)
        lastTick = now
        let elapsed = now - modeStart
        switch mode {
        case .idle:
            break
        case .lift:
            let t = min(1, elapsed / FilingMotion.pickUp)
            layout(lift: CubicBezier.decelerate(t), returning: false)
            if t >= 1 { mode = .drag }
        case .drag:
            layout(lift: 1, returning: false)
            stepEdge(dt)
            tickHover?()
            placeTag()
        case .back:
            let t = min(1, elapsed / FilingMotion.putBack)
            layout(lift: 1 - CubicBezier.standard(t), returning: true)
            if t >= 1 { finishBack() }
        case .flight:
            layoutFlight(elapsed)
        }
    }

    private func stepEdge(_ dt: Double) {
        let row = rowFrame()
        guard row.width > 1 else { return }
        let band = row.insetBy(dx: 0, dy: -FilingMotion.edgeBand)
        let inside = finger.y >= band.minY && finger.y <= band.maxY
        let depth = inside ? EdgeScroll.depth(pos: finger.x, start: row.minX, end: row.maxX, zone: FilingMotion.edgeZone) : 0
        let dx = scroller.step(depth: depth, dtMs: dt * 1000)
        scrolling = abs(dx) > 0.2
        if scrolling, nudge?(CGFloat(dx)) == true { scroller.hitEnd() }
        if !scrolling && abs(scroller.velocity) < 1 { scrolling = false }
    }

    private func layout(lift: Double, returning: Bool) {
        let count = min(3, tiles.count)
        for (index, tile) in tiles.enumerated() {
            let progress = FilingLiftRules.liftProgress(lift: lift, index: index, duration: returning ? FilingMotion.putBack : FilingMotion.pickUp)
            let home = homes[photos[index].id].map(local) ?? tile.frame
            let stack = stackFrame(index: index, count: count)
            let inStack = index < 3
            let end = inStack ? stack : stack.offsetBy(dx: 0, dy: 18)
            let rect = lerp(home, end, CGFloat(progress))
            tile.bounds.size = rect.size
            tile.center = CGPoint(x: rect.midX, y: rect.midY)
            let pop = 1 + FilingMotion.liftPop * sin(.pi * CGFloat(progress))
            let angle = inStack ? FilingMotion.stackRotations[index % 3] * CGFloat(progress) : 0
            var transform = CATransform3DIdentity
            transform.m34 = -1 / (FilingMotion.cameraDistance * 40)
            transform = CATransform3DScale(transform, pop, pop, 1)
            transform = CATransform3DRotate(transform, FilingMotion.stackTilt * .pi / 180 * CGFloat(inStack ? progress : 0), 1, 0, 0)
            transform = CATransform3DRotate(transform, angle * .pi / 180, 0, 0, 1)
            tile.layer.transform = transform
            tile.alpha = inStack ? 1 : CGFloat(1 - progress)
            let slot = FilingLiftRules.slotAlphaFor(returning: returning, t: progress)
            if returning { bridge?.grid?.setSlotAlpha(slot, id: photos[index].id) }
            tile.layer.shadowOpacity = Float(0.35 * progress)
            tile.layer.shadowRadius = 4 + FilingMotion.liftShadow * CGFloat(progress)
        }
        if count > 0 {
            let stack = stackFrame(index: 0, count: count)
            badge.bounds = CGRect(x: 0, y: 0, width: 22, height: 20)
            badge.center = CGPoint(x: stack.maxX, y: stack.minY)
            badge.alpha = CGFloat(lift)
        }
        placeTag()
    }

    private func layoutFlight(_ elapsed: TimeInterval) {
        let intoSheet = flightEvent?.intoSheet == true
        let gather = flightEvent?.fromDrag == true ? 0 : FilingMotion.gather
        let hold = FilingMotion.stackHold
        let insert = DarkroomMotion.medium
        let close = DarkroomMotion.short
        let wait = FilingMotion.flapReopenDelay
        let sheetDelay = intoSheet ? FilingMotion.sheetCloseDelay : 0
        var t = elapsed
        let target = flightTarget
        let stackCenter = flightStartCenter
        if t < gather {
            let p = CubicBezier.decelerate(t / max(gather, 0.001))
            for (index, tile) in tiles.enumerated() {
                let home = homes[photos[index].id].map(local) ?? tile.frame
                let stack = stackFrame(index: index, count: min(3, tiles.count), center: stackCenter)
                place(tile, from: home, to: index < 3 ? stack : stack.offsetBy(dx: 0, dy: 16), progress: CGFloat(p), index: index, tiltProgress: CGFloat(p))
            }
            if tiles.count > 1 {
                let stack = stackFrame(index: 0, count: min(3, tiles.count), center: stackCenter)
                badge.bounds = CGRect(x: 0, y: 0, width: 22, height: 20)
                badge.center = CGPoint(x: stack.maxX, y: stack.minY)
                badge.alpha = CGFloat(p)
            }
            return
        }
        t -= gather
        if t < hold { return }
        t -= hold
        if t < insert {
            let p = CubicBezier.accelerate(t / insert)
            let mouthTop = target.minY + 24
            let land: CGRect = intoSheet
                ? CGRect(x: target.midX - 18, y: target.minY + 17 - 18, width: 36, height: 36)
                : CGRect(x: target.midX - 16, y: mouthTop - 2, width: 32, height: 24)
            badge.alpha = max(0, 1 - CGFloat(p) / 0.3)
            for (index, tile) in tiles.prefix(3).enumerated() {
                let stack = stackFrame(index: index, count: min(3, tiles.count), center: stackCenter)
                place(tile, from: stack, to: land, progress: CGFloat(p), index: index, tiltProgress: CGFloat(1 - p))
                tile.layer.mask = intoSheet ? nil : mouthMask(on: tile, mouthY: mouthTop)
            }
            return
        }
        t -= insert
        if !closedSent {
            closedSent = true
            badge.isHidden = true
            if !intoSheet { tiles.forEach { $0.alpha = 0 } }
            onClosed?()
        }
        if intoSheet {
            let fade = min(1, t / max(FilingMotion.sheetLandFade, 0.001))
            let a = CGFloat(1 - fade)
            for tile in tiles.prefix(3) { tile.alpha = a }
        }
        if t < close + wait + sheetDelay { return }
        finishFlight()
    }

    private func place(_ tile: UIImageView, from: CGRect, to: CGRect, progress: CGFloat, index: Int, tiltProgress: CGFloat) {
        let rect = lerp(from, to, progress)
        tile.bounds.size = CGSize(width: max(8, rect.width), height: max(8, rect.height))
        tile.center = CGPoint(x: rect.midX, y: rect.midY)
        let angle = FilingMotion.stackRotations[index % 3] * tiltProgress
        var transform = CATransform3DIdentity
        transform.m34 = -1 / (FilingMotion.cameraDistance * 40)
        transform = CATransform3DRotate(transform, FilingMotion.stackTilt * .pi / 180 * tiltProgress, 1, 0, 0)
        transform = CATransform3DRotate(transform, angle * .pi / 180, 0, 0, 1)
        tile.layer.transform = transform
        tile.alpha = index < 3 ? 1 : 0
    }

    private func mouthMask(on tile: UIImageView, mouthY: CGFloat) -> CALayer {
        let mask = CALayer()
        mask.backgroundColor = UIColor.white.cgColor
        let maxLocalY = tile.convert(CGPoint(x: 0, y: mouthY), from: self).y
        mask.frame = CGRect(x: 0, y: 0, width: tile.bounds.width, height: max(0, maxLocalY))
        return mask
    }

    private func stackFrame(index: Int, count: Int, center: CGPoint? = nil) -> CGRect {
        let home = homes[photos.first?.id ?? ""].map(local) ?? CGRect(x: 0, y: 0, width: 120, height: 90)
        let width = home.width * FilingMotion.stackScale
        let height = home.height * FilingMotion.stackScale
        let at = localPoint(finger)
        let center = center ?? CGPoint(x: at.x, y: at.y - FilingMotion.stackLift)
        return CGRect(x: center.x - width / 2, y: center.y - height / 2, width: width, height: height)
    }

    private func placeTag() {
        guard mode == .drag || mode == .lift, let key = hoverKey(), let frame = envelopeFrames()[key] else {
            nameTag.isHidden = true
            return
        }
        nameTag.isHidden = false
        nameTag.setTitle(titleFor(key))
        let width = nameTag.fittedWidth
        let box = local(frame)
        let at = localPoint(finger)
        let home = homes[photos.first?.id ?? ""].map(local) ?? CGRect(x: 0, y: 0, width: 120, height: 90)
        let halfH = TagPlacement.stackHalfHeight(
            frameW: Double(home.width),
            frameH: Double(home.height),
            scale: Double(FilingMotion.stackScale),
            maxRotationDeg: 12,
            badgeHalf: 10
        )
        let gap = TagPlacement.fingerGap(lift: FilingMotion.stackLift, stackHalfHeight: halfH, gap: FilingMotion.tagStackGap)
        let bottom = TagPlacement.bottom(fingerY: at.y, envelopeTop: box.minY, fingerGap: gap, fanClear: FilingMotion.tagFanClear)
        let left = TagPlacement.left(centerX: box.midX, width: width, screenWidth: bounds.width, margin: FilingMotion.tagMargin)
        nameTag.frame = CGRect(x: left, y: bottom - FilingMotion.tagHeight - FilingMotion.tagTongueHeight, width: width, height: FilingMotion.tagHeight + FilingMotion.tagTongueHeight)
    }

    func setTagTitle(_ title: String) { nameTag.titleFallback = title }

    private func finishBack() {
        for id in photos.map(\.id) { bridge?.grid?.setSlotAlpha(1, id: id) }
        teardown()
    }

    private func finishFlight() {
        teardown()
        onDone?()
    }

    private func teardown() {
        link?.invalidate()
        link = nil
        mode = .idle
        clearTiles()
        photos = []
        badge.isHidden = true
        nameTag.isHidden = true
    }

    private func clearTiles() {
        tokens.forEach { $0.cancel() }
        tokens.removeAll()
        tiles.forEach { $0.removeFromSuperview() }
        tiles.removeAll()
    }

    private func makeTiles(_ photos: [PhotoItem], pipeline: ThumbnailPipeline, side: CGFloat) {
        let items = Array(photos.prefix(12))
        for (index, photo) in items.enumerated() {
            let tile = UIImageView()
            tile.contentMode = .scaleAspectFill
            tile.clipsToBounds = true
            tile.layer.borderWidth = 2
            tile.layer.borderColor = DarkroomUIColors.printBorder.cgColor
            tile.layer.shadowColor = UIColor.black.cgColor
            tile.layer.shadowOffset = CGSize(width: 0, height: 8)
            tile.layer.zPosition = CGFloat(items.count - index)
            addSubview(tile)
            tiles.append(tile)
            if let cached = pipeline.cached(id: photo.id, side: side, style: .normal) { tile.image = cached }
            let token = pipeline.request(asset: photo.asset, side: side, style: .normal) { [weak tile] image, _ in
                tile?.image = image
            }
            tokens.append(token)
        }
        bringSubviewToFront(badge)
        bringSubviewToFront(nameTag)
    }

    private func localPoint(_ window: CGPoint) -> CGPoint {
        window == .zero ? window : convert(window, from: nil)
    }

    private func local(_ window: CGRect) -> CGRect {
        window == .zero ? window : convert(window, from: nil)
    }

    private func lerp(_ a: CGRect, _ b: CGRect, _ t: CGFloat) -> CGRect {
        CGRect(
            x: a.minX + (b.minX - a.minX) * t,
            y: a.minY + (b.minY - a.minY) * t,
            width: a.width + (b.width - a.width) * t,
            height: a.height + (b.height - a.height) * t
        )
    }
}

private final class TagView: UIView {
    var titleFallback = ""
    private let label = UILabel()
    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        backgroundColor = .clear
        label.font = DarkroomUIFonts.notoSC(size: 13, weight: .semibold)
        label.textColor = DarkroomUIColors.labelInk
        label.textAlignment = .center
        label.lineBreakMode = .byTruncatingTail
        addSubview(label)
        layer.shadowColor = UIColor.black.cgColor
        layer.shadowOpacity = 0.5
        layer.shadowRadius = 10
        layer.shadowOffset = CGSize(width: 0, height: 8)
    }
    @available(*, unavailable) required init?(coder: NSCoder) { fatalError() }
    func setTitle(_ title: String) {
        label.text = title.isEmpty ? titleFallback : title
        setNeedsDisplay()
    }
    var fittedWidth: CGFloat {
        let text = (label.text ?? "") as NSString
        let w = text.size(withAttributes: [.font: label.font as Any]).width + 24
        return min(FilingMotion.tagMaxWidth, max(FilingMotion.tagMinWidth, ceil(w)))
    }
    override func layoutSubviews() {
        let tongue = FilingMotion.tagTongueHeight
        label.frame = CGRect(x: 8, y: 0, width: bounds.width - 16, height: bounds.height - tongue)
    }
    override func draw(_ rect: CGRect) {
        let tongue = FilingMotion.tagTongueHeight
        let body = CGRect(x: 0, y: 0, width: rect.width, height: rect.height - tongue)
        let path = UIBezierPath(roundedRect: body, cornerRadius: 4)
        path.move(to: CGPoint(x: rect.midX - FilingMotion.tagTongueWidth / 2, y: body.maxY))
        path.addLine(to: CGPoint(x: rect.midX, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.midX + FilingMotion.tagTongueWidth / 2, y: body.maxY))
        path.close()
        DarkroomUIColors.label.setFill()
        path.fill()
    }
}
