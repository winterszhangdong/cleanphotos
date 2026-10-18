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
        DispatchQueue.main.async { context.coordinator.attach(probe: uiView) }
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

        init(handlers: DragSelectHandlers) { self.handlers = handlers }

        private func wireScrollState() {
            handlers.listMoving = { [weak self] in
                guard let sv = self?.scrollView else { return false }
                return sv.isDragging || sv.isDecelerating
            }
            handlers.lastScrollTime = { [weak self] in self?.lastScroll ?? 0 }
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
            offsetObservation = sv.observe(\.contentOffset, options: []) { [weak self] _, _ in
                self?.lastScroll = CACurrentMediaTime()
            }
            wireScrollState()
            let lp = UILongPressGestureRecognizer(target: self, action: #selector(handle(_:)))
            lp.minimumPressDuration = 0.35
            lp.allowableMovement = 10
            lp.cancelsTouchesInView = true
            lp.delegate = self
            sv.addGestureRecognizer(lp)
            recognizer = lp
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
