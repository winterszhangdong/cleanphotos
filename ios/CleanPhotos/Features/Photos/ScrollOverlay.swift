import SwiftUI
import UIKit

/// 滚动位移和顶栏收起量。不发 SwiftUI 通知。
/// 吸顶日期、顶栏、滑块拇指的位置在 UIKit 上改 transform。
@MainActor
final class ScrollChromeDriver: NSObject {
    var chromeHeight: CGFloat = 96
    var viewportH: CGFloat = 0
    var scrubBottomReserve: CGFloat = 0
    var thumbHeight: CGFloat = 36
    var sticks = false
    var scrubEnabled = false
    var reduceMotion = false
    /// 手指按在滑块上时锁住下标，不跟着内容偏移再算一次。
    var scrubLockIndex: Int?

    private(set) var scrollY: CGFloat = 0
    private(set) var collapse: CGFloat = 0
    var pinY: CGFloat { chromeHeight - collapse }

    private var order: [String] = []
    private var headerY: [String: CGFloat] = [:]
    private var headerH: [String: CGFloat] = [:]
    private var lastY: [String: CGFloat] = [:]
    private var lastH: [String: CGFloat] = [:]
    private var anchors: [CGFloat] = []
    private var lastOffset: CGFloat = 0
    private var skipDelta = false
    private var stuckId: String?
    private var snapProxy: SnapProxy?
    private var link: CADisplayLink?
    private var snapFrom: CGFloat = 0
    private var snapTo: CGFloat = 0
    private var snapStart: CFTimeInterval = 0
    private var scrubShown = false
    var showsScrubber: Bool { scrubShown }

    /// DisplayLink 的 target。弱引用 driver，避免和 CADisplayLink 互相留住。
    private final class SnapProxy: NSObject {
        weak var driver: ScrollChromeDriver?
        /// CADisplayLink 加在主运行循环上。回调本身不是 MainActor 隔离的。
        @objc func tick() {
            let driver = driver
            MainActor.assumeIsolated { driver?.tickSnap() }
        }
    }

    weak var chromeView: UIView?
    weak var stickyView: UIView?
    weak var scrubThumb: UIView?
    weak var scrubHit: UIView?

    /// 吸住的日期变了才调用。滚动的中间帧不调用。
    var onStuckDay: ((String?) -> Void)?

    func headerTop(_ id: String) -> CGFloat? { headerY[id] }
    func headerHeight(_ id: String) -> CGFloat { headerH[id] ?? DarkroomDimens.dayHeight }

    func forgetMeasurements() {
        headerY.removeAll()
        lastY.removeAll()
        lastH.removeAll()
    }

    func replaceDays(order: [String], anchors: [CGFloat], defaultHeight: CGFloat) {
        self.order = order
        self.anchors = anchors
        let alive = Set(order)
        headerY = headerY.filter { alive.contains($0.key) }
        headerH = headerH.filter { alive.contains($0.key) }
        lastY = lastY.filter { alive.contains($0.key) }
        lastH = lastH.filter { alive.contains($0.key) }
        for id in order where headerH[id] == nil { headerH[id] = defaultHeight }
        apply()
        updateThumb()
        publishStuck()
    }

    func noteHeader(id: String, y: CGFloat, height: CGFloat, scrubAnchor: Bool) {
        if let old = headerY[id], abs(old - y) < 0.5, abs((headerH[id] ?? 0) - height) < 0.5 { return }
        headerY[id] = y
        headerH[id] = height
        if scrubAnchor, let i = order.firstIndex(of: id), anchors.indices.contains(i) {
            anchors[i] = y
        }
        apply()
        updateThumb()
        publishStuck()
    }

    func noteLastItem(id: String, y: CGFloat, height: CGFloat) {
        if let old = lastY[id], abs(old - y) < 0.5, abs((lastH[id] ?? 0) - height) < 0.5 { return }
        lastY[id] = y
        lastH[id] = height
        apply()
        publishStuck()
    }

    /// 切范围时对齐到那一页已经记住的偏移，下一次增量不算进收起。
    func reset(scrollY: CGFloat) {
        cancelSnap()
        skipDelta = true
        lastOffset = scrollY
        self.scrollY = scrollY
        collapse = 0
        apply()
        updateThumb()
        publishStuck()
    }

    func setCollapse(_ value: CGFloat, animated: Bool) {
        if animated { animateCollapse(to: value) } else { jumpCollapse(value) }
    }

    func ingest(y: CGFloat, moving: Bool, programmatic: Bool, forceOpen: Bool) {
        let t0 = ScrollProbe.enabled ? CACurrentMediaTime() : 0
        scrollY = y
        let delta = y - lastOffset
        let skipped = skipDelta
        skipDelta = false
        lastOffset = y
        if skipped || programmatic {
            if programmatic { cancelSnap() }
            apply()
            updateThumb()
            publishStuck()
            if ScrollProbe.enabled { ScrollProbe.shared.ingest(CACurrentMediaTime() - t0) }
            return
        }
        if moving { cancelSnap() }
        collapse = ScrollChrome.collapseWhileMoving(
            current: collapse, delta: delta, scrollY: y, chromeHeight: chromeHeight, forceOpen: forceOpen
        )
        apply()
        updateThumb()
        publishStuck()
        if ScrollProbe.enabled { ScrollProbe.shared.ingest(CACurrentMediaTime() - t0) }
    }

    func snap(forceOpen: Bool) {
        let target = ScrollChrome.collapseSnap(
            current: collapse, scrollY: scrollY, chromeHeight: chromeHeight, forceOpen: forceOpen
        )
        animateCollapse(to: target)
    }

    func setScrubVisible(_ on: Bool) {
        guard on != scrubShown else { return }
        scrubShown = on
        let updates = {
            self.scrubThumb?.alpha = on ? 1 : 0
            self.scrubHit?.isUserInteractionEnabled = on
        }
        if reduceMotion {
            updates()
        } else {
            UIView.animate(withDuration: DarkroomMotion.short, animations: updates)
        }
    }

    func attach(_ slot: Slot, _ view: UIView?) {
        switch slot {
        case .chrome: chromeView = view
        case .sticky: stickyView = view
        case .scrubThumb: scrubThumb = view
        case .scrubHit: scrubHit = view
        }
        apply()
        updateThumb()
        if slot == .scrubThumb || slot == .scrubHit {
            scrubThumb?.alpha = scrubShown ? 1 : 0
            scrubHit?.isUserInteractionEnabled = scrubShown
        }
    }

    enum Slot { case chrome, sticky, scrubThumb, scrubHit }

    private func jumpCollapse(_ value: CGFloat) {
        cancelSnap()
        collapse = value
        apply()
        updateThumb()
        publishStuck()
    }

    private func animateCollapse(to target: CGFloat) {
        cancelSnap()
        guard abs(collapse - target) > 0.5 else {
            collapse = target
            apply()
            updateThumb()
            publishStuck()
            return
        }
        if reduceMotion {
            jumpCollapse(target)
            return
        }
        snapFrom = collapse
        snapTo = target
        snapStart = CACurrentMediaTime()
        let proxy = SnapProxy()
        proxy.driver = self
        snapProxy = proxy
        let link = CADisplayLink(target: proxy, selector: #selector(SnapProxy.tick))
        link.add(to: .main, forMode: .common)
        self.link = link
    }

    fileprivate func tickSnap() {
        let spring = SpringToken.settle
        let span = log(1000) / (spring.damping * spring.stiffness.squareRoot())
        let t = CACurrentMediaTime() - snapStart
        let p = min(1, spring.value(at: t))
        collapse = snapFrom + (snapTo - snapFrom) * CGFloat(p)
        apply()
        updateThumb()
        publishStuck()
        if t >= span {
            collapse = snapTo
            apply()
            updateThumb()
            publishStuck()
            cancelSnap()
        }
    }

    private func cancelSnap() {
        link?.invalidate()
        link = nil
        snapProxy = nil
    }

    private func dayMarks() -> [ScrollChrome.DayMark] {
        order.map { id in
            ScrollChrome.DayMark(
                id: id,
                headerY: headerY[id],
                headerHeight: headerH[id] ?? DarkroomDimens.dayHeight,
                lastY: lastY[id],
                lastHeight: lastH[id]
            )
        }
    }

    private func apply() {
        chromeView?.transform = CGAffineTransform(translationX: 0, y: -collapse)
        let stuck = sticks ? ScrollChrome.stuck(pinY: pinY, scrollY: scrollY, days: dayMarks()) : nil
        if let stuck {
            stickyView?.isHidden = false
            stickyView?.transform = CGAffineTransform(translationX: 0, y: stuck.y)
        } else {
            stickyView?.isHidden = true
        }
    }

    private func updateThumb() {
        guard scrubEnabled, !anchors.isEmpty else { return }
        let index = scrubLockIndex ?? ScrollChrome.scrubIndex(scrollY: scrollY, pinY: pinY, anchors: anchors)
        let top = pinY
        let bottom = max(top + thumbHeight, viewportH - scrubBottomReserve)
        let y = ScrollChrome.scrubThumbY(
            index: index, count: anchors.count, top: top, bottom: bottom, thumbHeight: thumbHeight
        )
        scrubThumb?.transform = CGAffineTransform(translationX: 0, y: y)
    }

    private func publishStuck() {
        let id = sticks ? ScrollChrome.stuck(pinY: pinY, scrollY: scrollY, days: dayMarks())?.id : nil
        guard id != stuckId else { return }
        stuckId = id
        onStuckDay?(id)
    }
}

/// 挂在叠层上，把宿主 UIView 交给 driver。滚动时不触发 SwiftUI 刷新。
struct OverlayAnchor: UIViewRepresentable {
    let driver: ScrollChromeDriver
    let slot: ScrollChromeDriver.Slot

    func makeUIView(context: Context) -> Probe {
        let v = Probe()
        v.isUserInteractionEnabled = false
        v.backgroundColor = .clear
        return v
    }

    func updateUIView(_ uiView: Probe, context: Context) {
        uiView.onLayout = { [weak uiView] in
            guard let host = uiView?.superview else { return }
            driver.attach(slot, host)
        }
        uiView.onLayout?()
    }

    final class Probe: UIView {
        var onLayout: (() -> Void)?
        override func didMoveToSuperview() {
            super.didMoveToSuperview()
            onLayout?()
        }
    }
}

/// 内容坐标。只在这一帧的位置真正变化时回调，不走 PreferenceKey。
struct ContentFrameReader: View {
    var space: String
    var onFrame: (CGRect) -> Void

    var body: some View {
        GeometryReader { g in
            Color.clear
                .onAppear {
                    if ScrollProbe.enabled { ScrollProbe.shared.geometry() }
                    onFrame(g.frame(in: .named(space)))
                }
                .onChange(of: g.frame(in: .named(space))) { _, frame in
                    if ScrollProbe.enabled { ScrollProbe.shared.geometry() }
                    onFrame(frame)
                }
        }
    }
}
