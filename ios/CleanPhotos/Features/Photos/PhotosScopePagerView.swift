import QuartzCore
import SwiftUI
import UIKit

/// 范围横向分页 UIScrollView：仅水平拖动时接管，主动礼让相似卡片可滚动缩略图条，且多选/菜单/拖动选择时锁死。
final class PhotosPagerScrollView: UIScrollView {
    var scopeDragEnabled = true
    var pageCount = 1

    override func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        guard gestureRecognizer === panGestureRecognizer else {
            return super.gestureRecognizerShouldBegin(gestureRecognizer)
        }
        guard scopeDragEnabled, pageCount > 1 else { return false }

        let loc = gestureRecognizer.location(in: self)
        var hit: UIView? = hitTest(loc, with: nil)
        while let v = hit, v !== self {
            if let strip = v as? SimilarThumbStripView, strip.isScrollEnabled {
                return false
            }
            hit = v.superview
        }

        let velocity = panGestureRecognizer.velocity(in: self)
        guard abs(velocity.x) > abs(velocity.y) * 1.1 else { return false }

        let maxX = max(0, CGFloat(pageCount - 1) * bounds.width)
        if contentOffset.x <= 0.5 && velocity.x > 0 { return false }
        if contentOffset.x >= maxX - 0.5 && velocity.x < 0 { return false }
        return true
    }
}

/// 照片页 UIKit 分页与全范围槽位控制器：
/// - 原生横向滑页（120Hz `CADisplayLink` 弹簧 + `ScopeDialUIView` 1:1 实时联动，零 SwiftUI 帧重算）
/// - 原生 UIKit 吸顶日期叠层（跨天零 SwiftUI 重组）
/// - 当前 ± 1 挂载窗口，远端槽位停稳后卸载同时保留各范围滚动位置
/// - 停稳于 `.similar` 时才启动相似扫描
@MainActor
final class PhotosPagerController: NSObject, UIScrollViewDelegate {
    struct Props {
        var scopes: [LibraryScope]
        var currentScope: LibraryScope
        var pages: [LibraryScope: ScopePage]
        var immersive: Bool
        var expandedDayId: String?
        var isLoading: Bool
        var isPartial: Bool
        var sizesReady: Bool
        var totalCount: Int
        var unseenOnly: Bool
        var initialIDs: Set<String>
        var selecting: Bool
        var selected: Set<String>
        var menuOpen: Bool
        var showLanguageSheet: Bool
        var photosVisible: Bool
        var scrubActive: Bool
        var reduceMotion: Bool
        var chinese: Bool
        var locale: Locale
        var chromeHeight: CGFloat
        var library: PhotoLibraryService
        var overlay: ScrollChromeDriver
    }

    struct Callbacks {
        var onScopeChanged: ((LibraryScope) -> Void)?
        var onCanScrollChanged: ((Bool) -> Void)?
        var onTapPhoto: ((PhotoItem) -> Void)?
        var onOpenDay: ((PhotoDaySection) -> Void)?
        var onCollapseDay: ((String) -> Void)?
        var onExpandDay: ((String) -> Void)?
        var onToggleSelectDay: ((PhotoDaySection) -> Void)?
        var onSelectionChanged: ((_ nextSelected: Set<String>, _ enterSelecting: Bool) -> Void)?
        var onShowAllSeen: (() -> Void)?
        var onSelectAllScope: (() -> Void)?
        var onOpenTrash: (() -> Void)?
        var onOpenSimilarPhoto: ((_ groupKey: String, _ photoId: String) -> Void)?
    }

    private enum SnapMode {
        case none
        case pageSpring(fromX: CGFloat, toX: CGFloat, velocity: CGFloat, start: CFTimeInterval, destIndex: Int)
        case dialOnlySpring(fromProgress: CGFloat, toProgress: CGFloat, start: CFTimeInterval)
    }

    private final class DisplayProxy: NSObject {
        weak var owner: PhotosPagerController?
        @objc func tick() {
            let owner = owner
            MainActor.assumeIsolated { owner?.tickSnap() }
        }
    }

    final class ContainerView: UIView {
        weak var controller: PhotosPagerController?
        override func layoutSubviews() {
            super.layoutSubviews()
            controller?.layoutContainerSubviews()
        }
    }

    let containerView = ContainerView()
    let pagerScrollView: PhotosPagerScrollView = {
        let sv = PhotosPagerScrollView()
        sv.isPagingEnabled = false
        sv.decelerationRate = .fast
        sv.bounces = false
        sv.isDirectionalLockEnabled = true
        sv.showsHorizontalScrollIndicator = false
        sv.showsVerticalScrollIndicator = false
        sv.contentInsetAdjustmentBehavior = .never
        sv.backgroundColor = DarkroomUIColors.room
        return sv
    }()

    let stickyHeaderView: PhotosDayHeaderView = {
        let v = PhotosDayHeaderView()
        v.backgroundColor = DarkroomUIColors.room
        v.isOpaque = true
        v.isHidden = true
        return v
    }()

    weak var dialView: ScopeDialUIView?
    var callbacks = Callbacks()

    private var props: Props?
    private var slots: [LibraryScope: ScopePageSlot] = [:]
    private var scrollMemory: [LibraryScope: CGFloat] = [:]
    private var pendingOffsets: [LibraryScope: CGFloat] = [:]

    private var lastPagerWidth: CGFloat = 0
    private var lastScopes: [LibraryScope] = []
    private var isAdjustingPagerLayout = false
    private var dragStartPage = 0
    private var dialDragging = false
    private var dialDragStartX: CGFloat = 0
    private var animatingToIndex: Int?
    private var snapMode: SnapMode = .none
    private var snapLink: CADisplayLink?
    private var snapProxy: DisplayProxy?
    private var dragSelectActive = false
    private var currentStuckDayId: String?
    private var scrubHideTask: Task<Void, Never>?

    override init() {
        super.init()
        containerView.backgroundColor = DarkroomUIColors.room
        containerView.controller = self
        pagerScrollView.delegate = self
        containerView.addSubview(pagerScrollView)
        containerView.addSubview(stickyHeaderView)
        registerSharedTransitionProvider()
    }

    deinit {
        snapLink?.invalidate()
        scrubHideTask?.cancel()
    }

    func teardown() {
        stopSnap()
        scrubHideTask?.cancel()
        scrubHideTask = nil
        PhotoOpenTransition.shared.unregisterProvider(key: "photos")
        for slot in slots.values {
            slot.teardown()
            slot.removeFromSuperview()
        }
        slots.removeAll()
    }

    func attachDial(_ dial: ScopeDialUIView) {
        if dialView !== dial {
            dialView = dial
            if let props, let idx = props.scopes.firstIndex(of: props.currentScope) {
                if case .none = snapMode, !pagerScrollView.isDragging, !dialDragging {
                    dial.setProgress(CGFloat(idx))
                }
            }
        }
    }

    func bindOverlayIfNeeded(_ overlay: ScrollChromeDriver) {
        if overlay.stickyView !== stickyHeaderView {
            overlay.attach(.sticky, stickyHeaderView)
        }
        overlay.onStuckDay = { [weak self] dayId in
            self?.handleStuckDayChanged(dayId)
        }
    }

    func syncProps(_ next: Props) {
        let prev = props
        props = next
        bindOverlayIfNeeded(next.overlay)

        pagerScrollView.pageCount = next.scopes.count
        let dragAllowed = !next.selecting && !next.menuOpen && !next.showLanguageSheet && !dragSelectActive && !next.scrubActive
        pagerScrollView.scopeDragEnabled = dragAllowed

        let scopesChanged = next.scopes != lastScopes
        lastScopes = next.scopes

        let targetIdx = next.scopes.firstIndex(of: next.currentScope) ?? 0
        let width = pagerScrollView.bounds.width

        if width > 1 {
            isAdjustingPagerLayout = true
            let contentW = width * CGFloat(max(1, next.scopes.count))
            if abs(pagerScrollView.contentSize.width - contentW) > 0.5 || abs(pagerScrollView.contentSize.height - pagerScrollView.bounds.height) > 0.5 {
                pagerScrollView.contentSize = CGSize(width: contentW, height: pagerScrollView.bounds.height)
            }
            if scopesChanged || prev == nil {
                if !pagerScrollView.isDragging && !dialDragging && animatingToIndex == nil {
                    pagerScrollView.contentOffset.x = CGFloat(targetIdx) * width
                    dialView?.setProgress(CGFloat(targetIdx))
                }
            } else if let prev, prev.currentScope != next.currentScope {
                if animatingToIndex != targetIdx && !pagerScrollView.isDragging && !dialDragging {
                    stopSnap()
                    pagerScrollView.contentOffset.x = CGFloat(targetIdx) * width
                    dialView?.setProgress(CGFloat(targetIdx))
                }
            }
            isAdjustingPagerLayout = false
        } else {
            dialView?.setProgress(CGFloat(targetIdx))
        }

        let extraMounted: [Int] = animatingToIndex.map { [$0] } ?? []
        let allowEvict = !pagerScrollView.isDragging && !dialDragging && animatingToIndex == nil
        syncMounted(around: targetIdx, extra: extraMounted, evictFar: allowEvict)

        for (scope, slot) in slots {
            applyProps(next, to: slot, scope: scope)
        }

        if prev?.currentScope != next.currentScope {
            syncActiveChromeState(scope: next.currentScope, resetOffset: true)
        } else {
            syncActiveChromeState(scope: next.currentScope, resetOffset: false)
        }

        refreshStickyHeaderContent()
        if next.photosVisible {
            flushPendingOffset(for: next.currentScope)
        }
    }

    // MARK: External Navigation & Operations

    func tapScope(_ index: Int) {
        guard let props, props.scopes.indices.contains(index) else { return }
        let curIdx = props.scopes.firstIndex(of: props.currentScope) ?? 0
        guard index != curIdx else { return }
        if abs(index - curIdx) == 1 {
            page(to: index)
        } else {
            jump(to: index)
        }
    }

    func jumpToScope(_ target: LibraryScope) {
        guard let props, let index = props.scopes.firstIndex(of: target) else { return }
        jump(to: index)
    }

    func dialDrag(dx: CGFloat, stride: CGFloat, ended: Bool, velocityX: CGFloat) {
        guard let props, !props.scopes.isEmpty else { return }
        let width = pagerScrollView.bounds.width > 1 ? pagerScrollView.bounds.width : UIScreen.main.bounds.width
        let maxOffsetX = CGFloat(max(0, props.scopes.count - 1)) * width

        if !ended {
            if !dialDragging {
                dialDragging = true
                stopSnap()
                animatingToIndex = nil
                dialDragStartX = pagerScrollView.contentOffset.x
                dragStartPage = Int(round(dialDragStartX / max(width, 1)))
                props.overlay.setCollapse(0, animated: !props.reduceMotion)
            }
            let pagesDelta = ScopeDialMath.dragPages(dx: dx, stride: stride)
            let clamped = min(maxOffsetX, max(0, dialDragStartX + pagesDelta * width))
            isAdjustingPagerLayout = true
            pagerScrollView.contentOffset.x = clamped
            isAdjustingPagerLayout = false
            let progress = clamped / max(width, 1)
            dialView?.setProgress(progress)
            let lo = max(0, Int(floor(progress)))
            let hi = min(props.scopes.count - 1, Int(ceil(progress)))
            syncMounted(around: dragStartPage, extra: [lo, hi], evictFar: false)
            return
        }

        dialDragging = false
        let currentX = pagerScrollView.contentOffset.x
        let offsetVelocity = (-velocityX / max(stride, 1)) * width
        let dest = ScopePagerMath.targetPage(
            startPage: dragStartPage,
            currentOffsetX: currentX,
            pageWidth: width,
            velocityPtsPerSec: offsetVelocity,
            pageCount: props.scopes.count
        )
        finishInteractiveDrag(to: dest, initialVelocity: offsetVelocity)
    }

    func scrubTo(allDayIndex: Int, dayId: String, pinTop: CGFloat) {
        guard let props, let slot = slots[.all], let grid = slot.gridView else { return }
        let anchors = grid.built.dayAnchors
        let anchorY: CGFloat
        if anchors.indices.contains(allDayIndex) {
            anchorY = anchors[allDayIndex]
        } else if let found = grid.contentTop(forDayId: dayId) {
            anchorY = found
        } else {
            anchorY = props.chromeHeight
        }
        let targetY = max(0, anchorY - pinTop)
        grid.setScrollOffset(targetY, programmatic: true)
        scrollMemory[.all] = grid.currentOffsetY()
        props.overlay.ingest(y: grid.currentOffsetY(), moving: false, programmatic: true, forceOpen: props.selecting || props.menuOpen || props.showLanguageSheet)
    }

    func setScrubActive(_ active: Bool) {
        guard let props else { return }
        if active {
            scrubHideTask?.cancel()
            scrubHideTask = nil
            props.overlay.setScrubVisible(true)
        } else {
            scheduleScrubHide()
        }
    }

    /// 「日期分组 ↔ 沉浸」无缝锚点对齐。
    func applyViewModeSwitch(anchorPhotoId: String?, pinY: CGFloat, applyModeChange: () -> Void) {
        applyModeChange()
        guard let props, let slot = slots[.all] else { return }
        var updated = props
        updated.immersive = !props.immersive
        self.props = updated
        applyProps(updated, to: slot, scope: .all)
        if let anchorPhotoId, let grid = slot.gridView, let y = grid.contentTop(forPhotoId: anchorPhotoId) {
            grid.setScrollOffset(max(0, y - pinY), programmatic: true)
            scrollMemory[.all] = grid.currentOffsetY()
            updated.overlay.reset(scrollY: grid.currentOffsetY())
        }
        syncActiveChromeState(scope: updated.currentScope, resetOffset: false)
    }

    func topVisiblePhotoIdOnAll(below pinY: CGFloat) -> String? {
        slots[.all]?.gridView?.topVisiblePhotoId(below: pinY)
    }

    func applyCollapseDay(_ dayId: String, viewportH: CGFloat, applyCollapse: () -> Void) {
        applyCollapse()
        guard let props, let slot = slots[.onThisDay] else { return }
        var updated = props
        updated.expandedDayId = nil
        self.props = updated
        applyProps(updated, to: slot, scope: .onThisDay)
        if let grid = slot.gridView, let y = grid.contentTop(forDayId: dayId) {
            let pin = updated.overlay.pinY
            let curTop = y - grid.currentOffsetY()
            if curTop < pin || curTop > viewportH - DarkroomDimens.dayHeight {
                grid.setScrollOffset(max(0, y - pin), programmatic: true)
                scrollMemory[.onThisDay] = grid.currentOffsetY()
                updated.overlay.reset(scrollY: grid.currentOffsetY())
            }
        }
        syncActiveChromeState(scope: updated.currentScope, resetOffset: false)
    }

    func prepareLeave(metrics: PhotosLeave.Metrics, allOffset: CGFloat?, todayOffset: CGFloat?) {
        if let allOffset {
            scrollMemory[.all] = allOffset
            pendingOffsets[.all] = allOffset
        }
        if let todayOffset {
            scrollMemory[.onThisDay] = todayOffset
            pendingOffsets[.onThisDay] = todayOffset
        }
    }

    func currentScrollY(for scope: LibraryScope) -> CGFloat {
        if let slot = slots[scope], let y = slot.currentOffsetY() {
            scrollMemory[scope] = y
            return y
        }
        return scrollMemory[scope] ?? 0
    }

    func simulateDragSelect(steps: Int) {
        guard let scope = props?.currentScope else { return }
        slots[scope]?.gridView?.simulateDragSelect(steps: steps)
    }

    func isActiveListMoving() -> Bool {
        guard let scope = props?.currentScope else { return false }
        return slots[scope]?.gridView?.isListMoving() ?? false
    }

    // MARK: Container Layout

    private func layoutContainerSubviews() {
        let b = containerView.bounds
        guard b.width > 1, b.height > 1 else { return }
        if pagerScrollView.frame != b {
            pagerScrollView.frame = b
        }

        let stickyFrame = CGRect(x: 0, y: 0, width: b.width, height: DarkroomDimens.dayHeight)
        if stickyHeaderView.bounds.size != stickyFrame.size {
            stickyHeaderView.bounds = CGRect(origin: .zero, size: stickyFrame.size)
            stickyHeaderView.center = CGPoint(x: b.width / 2, y: DarkroomDimens.dayHeight / 2)
        }

        let widthChanged = abs(b.width - lastPagerWidth) > 0.5
        lastPagerWidth = b.width

        let pageCount = max(1, props?.scopes.count ?? lastScopes.count)
        let targetContentSize = CGSize(width: b.width * CGFloat(pageCount), height: b.height)
        isAdjustingPagerLayout = true
        if pagerScrollView.contentSize != targetContentSize {
            pagerScrollView.contentSize = targetContentSize
        }

        let scopeList = props?.scopes ?? lastScopes
        for (i, scope) in scopeList.enumerated() {
            if let slot = slots[scope] {
                slot.frame = CGRect(x: CGFloat(i) * b.width, y: 0, width: b.width, height: b.height)
            }
        }

        if widthChanged, let props, let curIdx = props.scopes.firstIndex(of: props.currentScope),
           !pagerScrollView.isDragging, !dialDragging, animatingToIndex == nil {
            pagerScrollView.contentOffset.x = CGFloat(curIdx) * b.width
            dialView?.setProgress(CGFloat(curIdx))
        }
        isAdjustingPagerLayout = false
    }

    // MARK: Paging & 120Hz Springs

    private func page(to index: Int) {
        guard let props, props.scopes.indices.contains(index) else { return }
        let width = pagerScrollView.bounds.width
        let destScope = props.scopes[index]
        syncMounted(around: index, extra: [], evictFar: false)

        if props.reduceMotion || width < 1 {
            stopSnap()
            animatingToIndex = nil
            isAdjustingPagerLayout = true
            pagerScrollView.contentOffset.x = CGFloat(index) * width
            isAdjustingPagerLayout = false
            dialView?.setProgress(CGFloat(index))
            emitScopeChangeIfNeeded(destScope)
            commitSettled(index: index)
            return
        }

        props.overlay.setCollapse(0, animated: true)
        emitScopeChangeIfNeeded(destScope)
        startPageSpring(to: index, initialVelocity: 0)
    }

    private func jump(to index: Int) {
        guard let props, props.scopes.indices.contains(index) else { return }
        let width = pagerScrollView.bounds.width
        let destScope = props.scopes[index]
        stopSnap()
        animatingToIndex = nil

        syncMounted(around: index, extra: [], evictFar: true)
        isAdjustingPagerLayout = true
        if width > 0 {
            pagerScrollView.contentOffset.x = CGFloat(index) * width
        }
        isAdjustingPagerLayout = false

        props.overlay.setCollapse(0, animated: false)
        emitScopeChangeIfNeeded(destScope)
        commitSettled(index: index)

        if props.reduceMotion {
            dialView?.setProgress(CGFloat(index))
        } else {
            if let destSlot = slots[destScope] {
                destSlot.alpha = 0.7
                UIView.animate(withDuration: DarkroomMotion.short, delay: 0, options: [.allowUserInteraction]) {
                    destSlot.alpha = 1
                }
            }
            let fromProgress = dialView?.progress ?? CGFloat(index)
            if abs(fromProgress - CGFloat(index)) > 0.01 {
                snapMode = .dialOnlySpring(fromProgress: fromProgress, toProgress: CGFloat(index), start: CACurrentMediaTime())
                startSnapLink()
            } else {
                dialView?.setProgress(CGFloat(index))
            }
        }
    }

    private func finishInteractiveDrag(to dest: Int, initialVelocity: CGFloat) {
        guard let props, props.scopes.indices.contains(dest) else { return }
        let width = pagerScrollView.bounds.width > 1 ? pagerScrollView.bounds.width : UIScreen.main.bounds.width
        let targetX = CGFloat(dest) * width
        let curX = pagerScrollView.contentOffset.x
        let destScope = props.scopes[dest]

        emitScopeChangeIfNeeded(destScope)

        if props.reduceMotion || (abs(curX - targetX) < 0.5 && abs(initialVelocity) < 5) {
            isAdjustingPagerLayout = true
            pagerScrollView.contentOffset.x = targetX
            isAdjustingPagerLayout = false
            dialView?.setProgress(CGFloat(dest))
            animatingToIndex = nil
            commitSettled(index: dest)
            return
        }

        startPageSpring(to: dest, initialVelocity: initialVelocity)
    }

    private func startPageSpring(to destIndex: Int, initialVelocity: CGFloat) {
        let width = pagerScrollView.bounds.width > 1 ? pagerScrollView.bounds.width : UIScreen.main.bounds.width
        let fromX = pagerScrollView.contentOffset.x
        let toX = CGFloat(destIndex) * width
        stopSnap()
        animatingToIndex = destIndex
        snapMode = .pageSpring(
            fromX: fromX,
            toX: toX,
            velocity: initialVelocity,
            start: CACurrentMediaTime(),
            destIndex: destIndex
        )
        startSnapLink()
    }

    private func startSnapLink() {
        snapLink?.invalidate()
        let proxy = DisplayProxy()
        proxy.owner = self
        snapProxy = proxy
        let link = CADisplayLink(target: proxy, selector: #selector(DisplayProxy.tick))
        link.preferredFrameRateRange = CAFrameRateRange(minimum: 80, maximum: 120, preferred: 120)
        link.add(to: .main, forMode: .common)
        snapLink = link
    }

    private func stopSnap() {
        snapLink?.invalidate()
        snapLink = nil
        snapProxy = nil
        snapMode = .none
    }

    private func tickSnap() {
        switch snapMode {
        case .none:
            stopSnap()
        case .pageSpring(let fromX, let toX, let velocity, let start, let destIndex):
            let width = max(1, pagerScrollView.bounds.width)
            let elapsed = CACurrentMediaTime() - start
            let step = ScopePagerMath.springStep(from: fromX, to: toX, initialVelocity: velocity, elapsed: elapsed)
            let maxX = CGFloat(max(0, lastScopes.count - 1)) * width
            let clampedX = min(maxX, max(0, step.x))
            isAdjustingPagerLayout = true
            pagerScrollView.contentOffset.x = clampedX
            isAdjustingPagerLayout = false
            dialView?.setProgress(clampedX / width)
            if step.settled {
                isAdjustingPagerLayout = true
                pagerScrollView.contentOffset.x = toX
                isAdjustingPagerLayout = false
                dialView?.setProgress(CGFloat(destIndex))
                animatingToIndex = nil
                stopSnap()
                commitSettled(index: destIndex)
            }
        case .dialOnlySpring(let fromP, let toP, let start):
            let elapsed = CACurrentMediaTime() - start
            let step = ScopePagerMath.springStep(from: fromP * 100, to: toP * 100, initialVelocity: 0, elapsed: elapsed)
            dialView?.setProgress(step.x / 100)
            if step.settled {
                dialView?.setProgress(toP)
                stopSnap()
            }
        }
    }

    private func emitScopeChangeIfNeeded(_ dest: LibraryScope) {
        guard let props, props.currentScope != dest else { return }
        if dest == .similar && props.selecting {
            callbacks.onSelectionChanged?([], false)
        }
        callbacks.onScopeChanged?(dest)
    }

    private func commitSettled(index: Int) {
        guard let props, props.scopes.indices.contains(index) else { return }
        let dest = props.scopes[index]
        syncMounted(around: index, extra: [], evictFar: true)
        if dest == .similar {
            SimilarSession.shared.start(props.library)
        }
        syncActiveChromeState(scope: dest, resetOffset: true)
        flushPendingOffset(for: dest)
    }

    // MARK: UIScrollViewDelegate (Horizontal Pager)

    func scrollViewWillBeginDragging(_ scrollView: UIScrollView) {
        guard scrollView === pagerScrollView else { return }
        stopSnap()
        animatingToIndex = nil
        let width = max(1, scrollView.bounds.width)
        dragStartPage = Int(round(scrollView.contentOffset.x / width))
    }

    func scrollViewDidScroll(_ scrollView: UIScrollView) {
        guard scrollView === pagerScrollView, !isAdjustingPagerLayout, let props else { return }
        let width = max(1, scrollView.bounds.width)
        let progress = scrollView.contentOffset.x / width
        dialView?.setProgress(progress)

        let activeIdx = props.scopes.firstIndex(of: props.currentScope) ?? 0
        if scrollView.isDragging && abs(progress - CGFloat(activeIdx)) > 0.02 {
            props.overlay.setCollapse(0, animated: !props.reduceMotion)
        }

        let lo = max(0, Int(floor(progress)))
        let hi = min(props.scopes.count - 1, Int(ceil(progress)))
        syncMounted(around: activeIdx, extra: [lo, hi], evictFar: false)
    }

    func scrollViewWillEndDragging(_ scrollView: UIScrollView, withVelocity velocity: CGPoint, targetContentOffset: UnsafeMutablePointer<CGPoint>) {
        guard scrollView === pagerScrollView, let props else { return }
        targetContentOffset.pointee = scrollView.contentOffset
        let width = max(1, scrollView.bounds.width)
        let panVx = scrollView.panGestureRecognizer.velocity(in: scrollView.superview ?? scrollView).x
        let offsetVelocity = -panVx
        let dest = ScopePagerMath.targetPage(
            startPage: dragStartPage,
            currentOffsetX: scrollView.contentOffset.x,
            pageWidth: width,
            velocityPtsPerSec: offsetVelocity,
            pageCount: props.scopes.count
        )
        finishInteractiveDrag(to: dest, initialVelocity: offsetVelocity)
    }

    func scrollViewDidEndDragging(_ scrollView: UIScrollView, willDecelerate decelerate: Bool) {
        guard scrollView === pagerScrollView else { return }
        if decelerate {
            // 立刻止住 UIKit 原生水平减速，完全交由 120Hz CADisplayLink 弹簧接管
            isAdjustingPagerLayout = true
            scrollView.setContentOffset(scrollView.contentOffset, animated: false)
            isAdjustingPagerLayout = false
        }
    }

    // MARK: Slot Mounting & Chrome Binding

    private func syncMounted(around index: Int, extra: [Int], evictFar: Bool) {
        guard let props, !props.scopes.isEmpty else { return }
        var wantedIndices = Set<Int>()
        let clamped = min(max(0, index), props.scopes.count - 1)
        wantedIndices.insert(clamped)
        if clamped > 0 { wantedIndices.insert(clamped - 1) }
        if clamped + 1 < props.scopes.count { wantedIndices.insert(clamped + 1) }
        for e in extra where props.scopes.indices.contains(e) {
            wantedIndices.insert(e)
        }
        let wantedScopes = Set(wantedIndices.map { props.scopes[$0] })

        let width = pagerScrollView.bounds.width
        let height = pagerScrollView.bounds.height

        for (i, scope) in props.scopes.enumerated() where wantedScopes.contains(scope) {
            if slots[scope] == nil {
                let slot = ScopePageSlot(scope: scope, library: props.library)
                wireSlot(slot, scope: scope)
                if width > 1 && height > 1 {
                    slot.frame = CGRect(x: CGFloat(i) * width, y: 0, width: width, height: height)
                }
                pagerScrollView.addSubview(slot)
                slots[scope] = slot
                applyProps(props, to: slot, scope: scope)
                if let savedY = scrollMemory[scope], savedY > 0.5 {
                    slot.setScrollOffset(savedY, programmatic: true)
                }
            }
        }

        if evictFar {
            for (scope, slot) in Array(slots) where !wantedScopes.contains(scope) {
                if let y = slot.currentOffsetY() {
                    scrollMemory[scope] = y
                }
                slot.teardown()
                slot.removeFromSuperview()
                slots.removeValue(forKey: scope)
            }
        }
    }

    private func wireSlot(_ slot: ScopePageSlot, scope: LibraryScope) {
        slot.onGridTapPhoto = { [weak self] item in self?.callbacks.onTapPhoto?(item) }
        slot.onGridOpenDay = { [weak self] day in self?.callbacks.onOpenDay?(day) }
        slot.onGridCollapseDay = { [weak self] id in self?.callbacks.onCollapseDay?(id) }
        slot.onGridExpandDay = { [weak self] id in self?.callbacks.onExpandDay?(id) }
        slot.onGridToggleSelectDay = { [weak self] day in self?.callbacks.onToggleSelectDay?(day) }
        slot.onGridSelectionChanged = { [weak self] nextSel, enter in self?.callbacks.onSelectionChanged?(nextSel, enter) }
        slot.onGridDragSelectActive = { [weak self] active in
            guard let self else { return }
            self.dragSelectActive = active
            if let props = self.props {
                let allowed = !props.selecting && !props.menuOpen && !props.showLanguageSheet && !active && !props.scrubActive
                self.pagerScrollView.scopeDragEnabled = allowed
            }
        }
        slot.onOpenSimilarPhoto = { [weak self] key, id in self?.callbacks.onOpenSimilarPhoto?(key, id) }
        slot.onShowAllSeen = { [weak self] in self?.callbacks.onShowAllSeen?() }
        slot.onSelectAllScope = { [weak self] in self?.callbacks.onSelectAllScope?() }
        slot.onOpenTrash = { [weak self] in self?.callbacks.onOpenTrash?() }

        slot.onScrollState = { [weak self] y, moving, programmatic, canScroll in
            guard let self, let props = self.props, props.currentScope == scope, props.photosVisible else { return }
            self.scrollMemory[scope] = y
            let forceOpen = props.selecting || props.menuOpen || props.showLanguageSheet
            props.overlay.ingest(y: y, moving: moving, programmatic: programmatic, forceOpen: forceOpen)
            self.callbacks.onCanScrollChanged?(canScroll)
            if moving {
                self.scrubHideTask?.cancel()
                self.scrubHideTask = nil
                props.overlay.setScrubVisible(true)
            } else if !programmatic {
                self.scheduleScrubHide()
            }
        }

        slot.onScrollSettled = { [weak self] in
            guard let self, let props = self.props, props.currentScope == scope, props.photosVisible else { return }
            let forceOpen = props.selecting || props.menuOpen || props.showLanguageSheet
            props.overlay.snap(forceOpen: forceOpen)
            self.scheduleScrubHide()
        }

        slot.onGridLayoutChanged = { [weak self] _, _ in
            guard let self, let props = self.props, props.currentScope == scope else { return }
            self.syncActiveChromeState(scope: scope, resetOffset: false)
        }
    }

    private func applyProps(_ props: Props, to slot: ScopePageSlot, scope: LibraryScope) {
        let page = props.pages[scope] ?? ScopePage(groups: [], flat: [], scopeCount: 0, visibleCount: 0, visibleBytes: 0)
        let body: PhotosBody
        if scope != .similar && props.isPartial && page.visibleCount == 0 {
            body = .loading
        } else {
            let ready = scope != .largeFiles || props.sizesReady || props.totalCount == 0
            body = ScopeRules.photosBody(
                scope: scope,
                isLoading: props.isLoading || props.isPartial,
                pageReady: ready,
                unseenOnly: props.unseenOnly,
                visibleCount: page.visibleCount,
                scopeCount: page.scopeCount,
                totalCount: props.totalCount
            )
        }

        let isActive = props.photosVisible && props.currentScope == scope
        slot.configure(
            body: body,
            page: page,
            isActiveScope: isActive,
            photosVisible: props.photosVisible,
            immersive: props.immersive && scope == .all,
            expandedDayId: props.expandedDayId,
            isLoading: props.isLoading || props.isPartial,
            initialIDs: props.initialIDs,
            selecting: props.selecting,
            selected: props.selected,
            chinese: props.chinese,
            locale: props.locale,
            chromeHeight: props.chromeHeight,
            pinY: props.overlay.pinY,
            menuOpen: props.menuOpen,
            showLanguageSheet: props.showLanguageSheet,
            stuckDayId: currentStuckDayId
        )

        if props.photosVisible, let pendingY = pendingOffsets[scope], body == .grid, let grid = slot.gridView {
            pendingOffsets[scope] = nil
            grid.setScrollOffset(pendingY, programmatic: true)
            scrollMemory[scope] = grid.currentOffsetY()
            if scope == props.currentScope {
                props.overlay.reset(scrollY: grid.currentOffsetY())
            }
        }
    }

    private func flushPendingOffset(for scope: LibraryScope) {
        guard let props, props.photosVisible,
              let pendingY = pendingOffsets[scope],
              let slot = slots[scope],
              let grid = slot.gridView else { return }
        pendingOffsets[scope] = nil
        grid.setScrollOffset(pendingY, programmatic: true)
        scrollMemory[scope] = grid.currentOffsetY()
        if scope == props.currentScope {
            props.overlay.reset(scrollY: grid.currentOffsetY())
        }
    }

    private func syncActiveChromeState(scope: LibraryScope, resetOffset: Bool) {
        guard let props else { return }
        let overlay = props.overlay
        let slot = slots[scope]
        let isGrid = slot?.currentBody == .grid
        let canScroll = slot?.canScrollNow() ?? false

        overlay.chromeHeight = props.chromeHeight
        overlay.scrubBottomReserve = DarkroomDimens.navBottom + DarkroomDimens.navHeight
        overlay.thumbHeight = DarkroomDimens.scrubberThumbHeight
        overlay.reduceMotion = props.reduceMotion
        overlay.sticks = (scope == .onThisDay) || (scope == .all && !props.immersive)
        overlay.scrubEnabled = (scope == .all && canScroll && isGrid)

        if let grid = slot?.gridView {
            overlay.replaceLayout(dayMarks: grid.built.dayMarks, anchors: grid.built.dayAnchors)
            if resetOffset {
                overlay.reset(scrollY: grid.currentOffsetY())
            }
        } else if let sim = slot?.similarView {
            overlay.replaceLayout(dayMarks: [], anchors: [])
            if resetOffset {
                overlay.reset(scrollY: sim.currentOffsetY())
            }
        } else {
            overlay.replaceLayout(dayMarks: [], anchors: [])
            if resetOffset {
                overlay.reset(scrollY: 0)
            }
        }

        if scope != .all || !canScroll || !isGrid {
            scrubHideTask?.cancel()
            scrubHideTask = nil
            overlay.setScrubVisible(false)
        }
        callbacks.onCanScrollChanged?(canScroll)
    }

    private func scheduleScrubHide() {
        guard let props else { return }
        guard props.currentScope == .all, !props.scrubActive, props.overlay.scrubEnabled else { return }
        guard scrubHideTask == nil else { return }
        scrubHideTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(DarkroomMotion.scrubberHold * 1_000_000_000))
            guard !Task.isCancelled, let self, let p = self.props, !p.scrubActive else { return }
            p.overlay.setScrubVisible(false)
            self.scrubHideTask = nil
        }
    }

    // MARK: UIKit Sticky Header

    private func handleStuckDayChanged(_ dayId: String?) {
        currentStuckDayId = dayId
        if let props {
            slots[props.currentScope]?.gridView?.updateStuckDayId(dayId)
        }
        refreshStickyHeaderContent()
    }

    private func refreshStickyHeaderContent() {
        guard let props,
              let dayId = currentStuckDayId,
              let slot = slots[props.currentScope],
              slot.currentBody == .grid else {
            stickyHeaderView.isHidden = true
            return
        }
        let page = props.pages[props.currentScope]
        guard let day = page?.groups.lazy.flatMap(\.days).first(where: { $0.id == dayId }) else {
            stickyHeaderView.isHidden = true
            return
        }
        let showSelect = props.selecting && (props.currentScope != .all || !props.immersive)
        let ids = Set(day.allItems.map(\.id))
        let allOn = !ids.isEmpty && ids.isSubset(of: props.selected)
        stickyHeaderView.configure(
            day: day,
            chinese: props.chinese,
            locale: props.locale,
            showSelectAll: showSelect,
            allSelected: allOn,
            selecting: props.selecting,
            showCollapse: props.currentScope == .onThisDay && props.expandedDayId == day.id && !props.selecting
        )
        stickyHeaderView.onOpenDay = { [weak self] in self?.callbacks.onOpenDay?(day) }
        stickyHeaderView.onCollapse = { [weak self] in self?.callbacks.onCollapseDay?(day.id) }
        stickyHeaderView.onToggleSelectAll = { [weak self] in self?.callbacks.onToggleSelectDay?(day) }
    }

    // MARK: Shared Transition Provider

    private func registerSharedTransitionProvider() {
        PhotoOpenTransition.shared.registerProvider(
            key: "photos",
            frame: { [weak self] id in
                guard let self, let props = self.props, props.photosVisible,
                      let grid = self.slots[props.currentScope]?.gridView else { return nil }
                return grid.sharedTransitionFrame(for: id)
            },
            image: { [weak self] id in
                guard let self, let props = self.props, props.photosVisible,
                      let grid = self.slots[props.currentScope]?.gridView else { return nil }
                return grid.sharedTransitionImage(for: id)
            },
            onFlyingChange: { [weak self] flyingId in
                guard let self, let props = self.props else { return }
                self.slots[props.currentScope]?.gridView?.applyFlyingId(flyingId)
            }
        )
    }
}

/// 单个范围槽位：按需在 UIKit `PhotosGridView` / `SimilarPageView` / 轻量空态 `UIHostingController` 间切换并保持各自偏移。
@MainActor
private final class ScopePageSlot: UIView {
    private let scope: LibraryScope
    private let library: PhotoLibraryService

    private(set) var currentBody: PhotosBody?
    private(set) var gridView: PhotosGridView?
    private(set) var similarView: SimilarPageView?
    private let emptyModel = PhotosEmptyHostModel()
    private var emptyHost: UIHostingController<AnyView>?

    var onGridTapPhoto: ((PhotoItem) -> Void)?
    var onGridOpenDay: ((PhotoDaySection) -> Void)?
    var onGridCollapseDay: ((String) -> Void)?
    var onGridExpandDay: ((String) -> Void)?
    var onGridToggleSelectDay: ((PhotoDaySection) -> Void)?
    var onGridSelectionChanged: ((Set<String>, Bool) -> Void)?
    var onGridDragSelectActive: ((Bool) -> Void)?
    var onGridLayoutChanged: ((PhotosGridGeometry.BuiltLayout, Bool) -> Void)?
    var onOpenSimilarPhoto: ((String, String) -> Void)?
    var onShowAllSeen: (() -> Void)?
    var onSelectAllScope: (() -> Void)?
    var onOpenTrash: (() -> Void)?
    var onScrollState: ((CGFloat, Bool, Bool, Bool) -> Void)?
    var onScrollSettled: (() -> Void)?

    init(scope: LibraryScope, library: PhotoLibraryService) {
        self.scope = scope
        self.library = library
        super.init(frame: .zero)
        backgroundColor = DarkroomUIColors.room
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func teardown() {
        gridView?.teardown()
        gridView?.removeFromSuperview()
        gridView = nil
        similarView?.teardown()
        similarView?.removeFromSuperview()
        similarView = nil
        emptyHost?.view.removeFromSuperview()
        emptyHost = nil
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let b = bounds
        gridView?.frame = b
        similarView?.frame = b
        emptyHost?.view.frame = b
    }

    func currentOffsetY() -> CGFloat? {
        if currentBody == .grid { return gridView?.currentOffsetY() }
        if currentBody == .similar { return similarView?.currentOffsetY() }
        return 0
    }

    func canScrollNow() -> Bool {
        if currentBody == .grid { return gridView?.canScrollNow() ?? false }
        if currentBody == .similar { return similarView?.canScrollNow() ?? false }
        return false
    }

    func setScrollOffset(_ y: CGFloat, programmatic: Bool) {
        if currentBody == .grid { gridView?.setScrollOffset(y, programmatic: programmatic) }
        else if currentBody == .similar { similarView?.setScrollOffset(y, programmatic: programmatic) }
    }

    func configure(
        body: PhotosBody,
        page: ScopePage,
        isActiveScope: Bool,
        photosVisible: Bool,
        immersive: Bool,
        expandedDayId: String?,
        isLoading: Bool,
        initialIDs: Set<String>,
        selecting: Bool,
        selected: Set<String>,
        chinese: Bool,
        locale: Locale,
        chromeHeight: CGFloat,
        pinY: CGFloat,
        menuOpen: Bool,
        showLanguageSheet: Bool,
        stuckDayId: String?
    ) {
        currentBody = body

        switch body {
        case .grid:
            similarView?.isHidden = true
            emptyHost?.view.isHidden = true
            let gridConfig = PhotosGridView.Config(
                scope: scope,
                page: page,
                immersive: immersive,
                expandedDayId: expandedDayId,
                isLoading: isLoading,
                initialIDs: initialIDs,
                selecting: selecting,
                selected: selected,
                chinese: chinese,
                locale: locale,
                chromeHeight: chromeHeight,
                pinY: pinY,
                menuOpen: menuOpen,
                showLanguageSheet: showLanguageSheet,
                stuckDayId: stuckDayId
            )
            if let gridView {
                gridView.isHidden = false
                gridView.setActiveScope(isActiveScope)
                gridView.update(gridConfig)
            } else {
                let gv = PhotosGridView(config: gridConfig, pipeline: library.thumbnails)
                gv.frame = bounds
                gv.onTapPhoto = { [weak self] in self?.onGridTapPhoto?($0) }
                gv.onOpenDay = { [weak self] in self?.onGridOpenDay?($0) }
                gv.onCollapseDay = { [weak self] in self?.onGridCollapseDay?($0) }
                gv.onExpandDay = { [weak self] in self?.onGridExpandDay?($0) }
                gv.onToggleSelectDay = { [weak self] in self?.onGridToggleSelectDay?($0) }
                gv.onSelectionChanged = { [weak self] next, enter in self?.onGridSelectionChanged?(next, enter) }
                gv.onDragSelectActiveChanged = { [weak self] active in self?.onGridDragSelectActive?(active) }
                gv.onScrollState = { [weak self] y, m, prog, can in self?.onScrollState?(y, m, prog, can) }
                gv.onScrollSettled = { [weak self] in self?.onScrollSettled?() }
                gv.onLayoutChanged = { [weak self] built, can in self?.onGridLayoutChanged?(built, can) }
                gv.setActiveScope(isActiveScope)
                addSubview(gv)
                gridView = gv
            }

        case .similar:
            gridView?.isHidden = true
            emptyHost?.view.isHidden = true
            if let similarView {
                similarView.isHidden = false
                similarView.update(
                    zh: chinese,
                    locale: locale,
                    chromeHeight: chromeHeight,
                    photosVisible: photosVisible,
                    isActiveScope: isActiveScope
                )
            } else {
                let sv = SimilarPageView(
                    pipeline: library.thumbnails,
                    zh: chinese,
                    locale: locale,
                    chromeHeight: chromeHeight,
                    photosVisible: photosVisible,
                    isActiveScope: isActiveScope
                )
                sv.frame = bounds
                sv.onOpenPhoto = { [weak self] key, id in self?.onOpenSimilarPhoto?(key, id) }
                sv.onScrollState = { [weak self] y, m, prog, can in self?.onScrollState?(y, m, prog, can) }
                sv.onScrollSettled = { [weak self] in self?.onScrollSettled?() }
                addSubview(sv)
                similarView = sv
            }

        default:
            gridView?.isHidden = true
            similarView?.isHidden = true
            emptyModel.body = body
            emptyModel.scope = scope
            emptyModel.chromeHeight = chromeHeight
            emptyModel.chinese = chinese
            emptyModel.locale = locale
            if let emptyHost {
                emptyHost.view.isHidden = false
            } else {
                let view = PhotosEmptyHostView(
                    model: emptyModel,
                    onShowAllSeen: { [weak self] in self?.onShowAllSeen?() },
                    onSelectAllScope: { [weak self] in self?.onSelectAllScope?() },
                    onOpenTrash: { [weak self] in self?.onOpenTrash?() }
                )
                .environmentObject(library)
                let hc = UIHostingController(rootView: AnyView(view))
                hc.view.backgroundColor = DarkroomUIColors.room
                hc.view.frame = bounds
                addSubview(hc.view)
                emptyHost = hc
            }
        }
    }
}

private final class PhotosEmptyHostModel: ObservableObject {
    @Published var body: PhotosBody = .loading
    @Published var scope: LibraryScope = .all
    @Published var chromeHeight: CGFloat = 96
    @Published var chinese = true
    @Published var locale: Locale = .current
}

private struct PhotosEmptyHostView: View {
    @ObservedObject var model: PhotosEmptyHostModel
    var onShowAllSeen: () -> Void
    var onSelectAllScope: () -> Void
    var onOpenTrash: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Color.clear.frame(height: model.chromeHeight)
                switch model.body {
                case .loading:
                    LoadingSkeletonGrid()
                case .allSeen:
                    AllSeenView(locale: model.locale, onTrash: onOpenTrash, onShowAll: onShowAllSeen)
                case .scopeSeen:
                    message(PhotoPageCopy.scopeSeenTitle(chinese: model.chinese), button: PhotoPageCopy.showSeen(chinese: model.chinese), action: onShowAllSeen)
                case .scopeEmpty:
                    message(PhotoPageCopy.emptyTitle(model.scope, chinese: model.chinese), button: L10n.string("show_all_photos", locale: model.locale), action: onSelectAllScope)
                case .libraryEmpty:
                    message(L10n.string("photos_empty_title", locale: model.locale), body: L10n.string("photos_empty_body", locale: model.locale), button: nil, action: {})
                case .grid, .similar:
                    EmptyView()
                }
                Color.clear.frame(height: DarkroomDimens.listFade + 48)
            }
        }
        .scrollIndicators(.hidden)
        .background(DarkroomColors.room)
        .environment(\.locale, model.locale)
    }

    private func message(_ title: String, body: String? = nil, button: String?, action: @escaping () -> Void) -> some View {
        EmptyStateArea {
            Text(title)
                .font(DarkroomType.title)
                .foregroundStyle(DarkroomColors.paper)
                .multilineTextAlignment(.center)
            if let body {
                Text(body)
                    .font(DarkroomType.body)
                    .foregroundStyle(DarkroomColors.dim)
                    .multilineTextAlignment(.center)
                    .frame(width: 232)
                    .padding(.top, 8)
            }
            if let button {
                Button(action: action) {
                    Text(button)
                        .font(Font.custom("NotoSansSC-Regular", size: 13))
                        .foregroundStyle(DarkroomColors.dim)
                        .frame(minHeight: DarkroomDimens.minTouch)
                }
                .buttonStyle(.plain)
                .padding(.top, 4)
            }
        }
    }
}

/// 将 `PhotosPagerController` 挂载到 SwiftUI 树中的 UIViewRepresentable 桥梁。
struct PhotosScopePagerView: UIViewRepresentable {
    let controller: PhotosPagerController
    let props: PhotosPagerController.Props
    let callbacks: PhotosPagerController.Callbacks

    func makeUIView(context: Context) -> PhotosPagerController.ContainerView {
        controller.callbacks = callbacks
        controller.syncProps(props)
        return controller.containerView
    }

    func updateUIView(_ uiView: PhotosPagerController.ContainerView, context: Context) {
        controller.callbacks = callbacks
        controller.syncProps(props)
    }

    static func dismantleUIView(_ uiView: PhotosPagerController.ContainerView, coordinator: ()) {
        uiView.controller?.teardown()
    }
}
