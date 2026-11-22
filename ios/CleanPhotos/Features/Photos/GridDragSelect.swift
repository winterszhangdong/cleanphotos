import SwiftUI
import UIKit

/// Tile frames in the "photoGrid" coordinate space (the LazyVStack's own space).
/// Reference type, written from geometry callbacks without triggering view updates.
final class TileFrameRegistry {
    var frames: [String: CGRect] = [:]

    func id(at point: CGPoint) -> String? {
        frames.first { $0.value.contains(point) }?.key
    }
}

/// Callbacks for the long-press → drag-select gesture (Android PhotoGridSelectGesture).
final class DragSelectHandlers {
    /// Return true if the long press landed on a tile and selection started.
    var onBegin: (CGPoint) -> Bool = { _ in false }
    var onMove: (CGPoint) -> Void = { _ in }
    var onEnd: () -> Void = {}
    /// Height of the opaque header that overlaps the top of the scroll view.
    var topInset: CGFloat = 0
    /// 列表正在拖动或惯性滑动（UIScrollView 状态，由 Coordinator 接上）。
    var listMoving: () -> Bool = { false }
    /// 最后一次 contentOffset 变化的时间（CACurrentMediaTime）；惯性停下的时刻就是最后一次变化。
    var lastScrollTime: () -> CFTimeInterval = { 0 }
    /// 滚动偏移、是否手指按着、是否惯性、这次是不是代码设的偏移。
    var onScroll: ((CGFloat, Bool, Bool, Bool) -> Void)?
    /// 横滑换范围。translation、是否结束、水平速度（pt/s）。
    var onScopeDrag: ((CGFloat, Bool, CGFloat) -> Void)?
    var scopeDragEnabled: () -> Bool = { true }
    var readOffset: () -> CGFloat = { 0 }
    var writeOffset: (CGFloat) -> Void = { _ in }
    var readContentHeight: () -> CGFloat = { 0 }
    var readViewportHeight: () -> CGFloat = { 0 }
}

/// Invisible probe placed as background of the grid's LazyVStack. It finds the hosting
/// UIScrollView and installs a UILongPressGestureRecognizer:
/// - long press (0.35s, ≤10pt movement) on a tile → `onBegin` (enter multi-select + anchor)
/// - while held, list scrolling is disabled; each move → `onMove` with the finger point
///   in grid space; within 64pt of the top/bottom edge the list auto-scrolls (≤14pt/frame,
///   proportional to depth), re-hit-testing every frame.
struct DragSelectProbe: UIViewRepresentable {
    let handlers: DragSelectHandlers

    func makeCoordinator() -> Coordinator { Coordinator(handlers: handlers) }

    func makeUIView(context: Context) -> ProbeView {
        let v = ProbeView()
        v.coordinator = context.coordinator
        v.isUserInteractionEnabled = false
        v.backgroundColor = .clear
        return v
    }

    func updateUIView(_ uiView: ProbeView, context: Context) {
        context.coordinator.handlers = handlers
        context.coordinator.rewire()
        uiView.coordinator = context.coordinator
        let token = context.coordinator.token
        DispatchQueue.main.async {
            guard context.coordinator.token == token else { return }
            context.coordinator.attach(probe: uiView)
        }
    }

    static func dismantleUIView(_ uiView: ProbeView, coordinator: Coordinator) {
        coordinator.detach()
        uiView.coordinator = nil
    }

    final class ProbeView: UIView {
        weak var coordinator: Coordinator?
        override func didMoveToWindow() {
            super.didMoveToWindow()
            coordinator?.attach(probe: self)
        }
    }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        var handlers: DragSelectHandlers
        weak var probe: UIView?
        weak var scrollView: UIScrollView?
        private var recognizer: UILongPressGestureRecognizer?
        private var link: CADisplayLink?
        private var active = false

        private let edge: CGFloat = 64
        private let maxStep: CGFloat = 14
        private var offsetObservation: NSKeyValueObservation?
        private var lastScroll: CFTimeInterval = 0
        private var scopePan: UIPanGestureRecognizer?
        private var adjusting = false
        fileprivate var token = UUID()

        init(handlers: DragSelectHandlers) { self.handlers = handlers }

        private func wireScrollState() {
            handlers.listMoving = { [weak self] in
                guard let sv = self?.scrollView else { return false }
                return sv.isDragging || sv.isDecelerating
            }
            handlers.lastScrollTime = { [weak self] in self?.lastScroll ?? 0 }
            handlers.readOffset = { [weak self] in self?.scrollView?.contentOffset.y ?? 0 }
            handlers.readContentHeight = { [weak self] in self?.scrollView?.contentSize.height ?? 0 }
            handlers.readViewportHeight = { [weak self] in self?.scrollView?.bounds.height ?? 0 }
            handlers.writeOffset = { [weak self] y in self?.setOffset(y) }
        }

        private func setOffset(_ y: CGFloat) {
            guard let sv = scrollView else { return }
            let minY = -sv.adjustedContentInset.top
            let maxY = max(minY, sv.contentSize.height - sv.bounds.height + sv.adjustedContentInset.bottom)
            let next = min(maxY, max(minY, y))
            guard next != sv.contentOffset.y else { return }
            adjusting = true
            sv.setContentOffset(CGPoint(x: 0, y: next), animated: false)
        }

        private func emitScroll(_ sv: UIScrollView) {
            let programmatic = adjusting
            adjusting = false
            if ScrollProbe.enabled { ScrollProbe.shared.offsetCallback() }
            handlers.onScroll?(sv.contentOffset.y, sv.isDragging, sv.isDecelerating, programmatic)
        }

        func detach() {
            token = UUID()
            if let recognizer, let sv = scrollView { sv.removeGestureRecognizer(recognizer) }
            if let scopePan, let sv = scrollView { sv.removeGestureRecognizer(scopePan) }
            offsetObservation?.invalidate()
            offsetObservation = nil
            recognizer = nil
            scopePan = nil
            scrollView = nil
            stopLink()
        }

        func rewire() { if scrollView != nil { wireScrollState() } }

        func attach(probe: UIView) {
            self.probe = probe
            guard recognizer == nil || scrollView == nil else { return }
            var v: UIView? = probe.superview
            while let cur = v, !(cur is UIScrollView) { v = cur.superview }
            guard let sv = v as? UIScrollView else { return }
            scrollView = sv
            // 只记一个时间戳（滚动时每帧一次，不触发任何 SwiftUI 刷新）
            offsetObservation = sv.observe(\.contentOffset, options: [.new]) { [weak self] sv, _ in
                self?.lastScroll = CACurrentMediaTime()
                if Thread.isMainThread {
                    self?.emitScroll(sv)
                } else {
                    DispatchQueue.main.async { self?.emitScroll(sv) }
                }
            }
            wireScrollState()
            emitScroll(sv)
            let lp = UILongPressGestureRecognizer(target: self, action: #selector(handle(_:)))
            lp.minimumPressDuration = 0.35
            lp.allowableMovement = 10
            lp.cancelsTouchesInView = true
            lp.delegate = self
            sv.addGestureRecognizer(lp)
            recognizer = lp
            if scopePan == nil {
                let pan = UIPanGestureRecognizer(target: self, action: #selector(scopePanned(_:)))
                pan.delegate = self
                pan.cancelsTouchesInView = false
                sv.addGestureRecognizer(pan)
                scopePan = pan
            }
        }

        func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
            guard gestureRecognizer === scopePan, let sv = scrollView, let pan = scopePan else { return true }
            guard handlers.scopeDragEnabled() else { return false }
            let v = pan.velocity(in: sv)
            return abs(v.x) > abs(v.y)
        }

        @objc private func scopePanned(_ g: UIPanGestureRecognizer) {
            guard let sv = scrollView else { return }
            let translation = g.translation(in: sv).x
            switch g.state {
            case .began:
                sv.isScrollEnabled = false
                handlers.onScopeDrag?(translation, false, 0)
            case .changed:
                handlers.onScopeDrag?(translation, false, 0)
            case .ended:
                let velocity = g.velocity(in: sv).x
                sv.isScrollEnabled = true
                handlers.onScopeDrag?(translation, true, velocity)
            default:
                sv.isScrollEnabled = true
                handlers.onScopeDrag?(0, true, 0)
            }
        }

        func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
            true
        }

        @objc private func handle(_ g: UILongPressGestureRecognizer) {
            guard let probe, let sv = scrollView else { return }
            switch g.state {
            case .began:
                // A list that is still decelerating: treat as a fling, not a long press.
                if sv.isDecelerating { cancel(g); return }
                let p = g.location(in: probe)
                guard handlers.onBegin(p) else { cancel(g); return }
                active = true
                // Freeze list scrolling while the finger paints the selection.
                sv.panGestureRecognizer.isEnabled = false
                sv.panGestureRecognizer.isEnabled = true
                sv.isScrollEnabled = false
                startLink()
            case .changed:
                guard active else { return }
                handlers.onMove(g.location(in: probe))
            case .ended, .cancelled, .failed:
                finish()
            default:
                break
            }
        }

        private func cancel(_ g: UIGestureRecognizer) {
            g.isEnabled = false
            g.isEnabled = true
        }

        private func finish() {
            guard active else { return }
            active = false
            stopLink()
            scrollView?.isScrollEnabled = true
            handlers.onEnd()
        }

        private func startLink() {
            stopLink()
            let l = CADisplayLink(target: self, selector: #selector(tick))
            l.add(to: .main, forMode: .common)
            link = l
        }

        private func stopLink() {
            link?.invalidate()
            link = nil
        }

        @objc private func tick() {
            guard active, let sv = scrollView, let g = recognizer, let probe else { return }
            let inView = g.location(in: sv.superview ?? sv)
            let frame = sv.frame
            let y = inView.y - frame.minY
            let top = sv.safeAreaInsets.top + handlers.topInset
            let bottom = frame.height - sv.safeAreaInsets.bottom
            var step: CGFloat = 0
            if y < top + edge {
                step = -maxStep * min(1, max(0, (top + edge - y) / edge))
            } else if y > bottom - edge {
                step = maxStep * min(1, max(0, (y - (bottom - edge)) / edge))
            }
            guard step != 0 else { return }
            let minY = -sv.adjustedContentInset.top
            let maxY = max(minY, sv.contentSize.height - sv.bounds.height + sv.adjustedContentInset.bottom)
            let next = min(maxY, max(minY, sv.contentOffset.y + step))
            guard next != sv.contentOffset.y else { return }
            sv.contentOffset.y = next
            handlers.onMove(g.location(in: probe))
        }
    }
}
