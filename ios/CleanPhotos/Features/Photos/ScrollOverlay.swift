import SwiftUI
import UIKit

/// 滚动位移和顶栏收起量。不发 SwiftUI 通知。
/// 吸顶日期、顶栏、滑块拇指的位置在 UIKit 上改 transform，吸顶与滑块均用 O(log D) 查找。
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

    private var sortedDayMarks: [ScrollChrome.DayMark] = []
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

    /// 直接接收按 `headerY` 升序排列的列表布局切片元数据（零每帧字典分配与重建）。
    func replaceLayout(dayMarks: [ScrollChrome.DayMark], anchors: [CGFloat]) {
        sortedDayMarks = dayMarks
        self.anchors = anchors
        applyAndPublish()
        updateThumb()
    }

    /// 切范围时对齐到那一页已经记住的偏移，下一次增量不算进收起。
    func reset(scrollY: CGFloat) {
        cancelSnap()
        skipDelta = true
        lastOffset = scrollY
        self.scrollY = scrollY
        collapse = 0
        applyAndPublish()
        updateThumb()
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
            applyAndPublish()
            updateThumb()
            if ScrollProbe.enabled { ScrollProbe.shared.ingest(CACurrentMediaTime() - t0) }
            return
        }
        if moving { cancelSnap() }
        collapse = ScrollChrome.collapseWhileMoving(
            current: collapse, delta: delta, scrollY: y, chromeHeight: chromeHeight, forceOpen: forceOpen
        )
        applyAndPublish()
        updateThumb()
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
        applyAndPublish()
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
        applyAndPublish()
        updateThumb()
    }

    private func animateCollapse(to target: CGFloat) {
        cancelSnap()
        guard abs(collapse - target) > 0.5 else {
            collapse = target
            applyAndPublish()
            updateThumb()
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
        link.preferredFrameRateRange = CAFrameRateRange(minimum: 80, maximum: 120, preferred: 120)
        link.add(to: .main, forMode: .common)
        self.link = link
    }

    fileprivate func tickSnap() {
        let spring = SpringToken.settle
        let span = log(1000) / (spring.damping * spring.stiffness.squareRoot())
        let t = CACurrentMediaTime() - snapStart
        let p = min(1, spring.value(at: t))
        collapse = snapFrom + (snapTo - snapFrom) * CGFloat(p)
        applyAndPublish()
        updateThumb()
        if t >= span {
            collapse = snapTo
            applyAndPublish()
            updateThumb()
            cancelSnap()
        }
    }

    private func cancelSnap() {
        link?.invalidate()
        link = nil
        snapProxy = nil
    }

    private func applyAndPublish() {
        chromeView?.transform = CGAffineTransform(translationX: 0, y: -collapse)
        let stuck = sticks ? ScrollChrome.stuckSorted(pinY: pinY, scrollY: scrollY, days: sortedDayMarks) : nil
        let nextId = stuck?.id
        if nextId != stuckId {
            stuckId = nextId
            onStuckDay?(nextId)
        }
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
