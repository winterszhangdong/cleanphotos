import SwiftUI
import UIKit

private enum PhotosViewMode: Equatable { case dateGrouped, immersive }

/// 每个范围记住的滚动偏移。不放进 SwiftUI 状态。
private final class ScrollMemory {
    var saved: [LibraryScope: CGFloat] = [:]
}

struct PhotosView: View {
    @EnvironmentObject private var library: PhotoLibraryService
    @EnvironmentObject private var language: LanguageStore
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Binding var reviewLaunch: ReviewLaunch?
    @Binding var hideTabBar: Bool
    @Binding var dimTabBar: Bool
    /// 底部切走收藏或垃圾桶时为 false。视图还在，只是不画出来。
    var photosVisible: Bool = true
    var onOpenTrash: () -> Void = {}

    @State private var scope: LibraryScope = .all
    @ObservedObject private var similarSession = SimilarSession.shared
    @State private var unseenOnly = false
    @State private var viewMode: PhotosViewMode = .dateGrouped
    @State private var expandedDayId: String?
    @State private var menuOpen = false
    @State private var showLanguageSheet = false
    @State private var mounted: Set<LibraryScope> = [.all]
    @State private var pagerX: CGFloat = 0
    @State private var animatedProgress: CGFloat = 0
    @State private var pageWidth: CGFloat = UIScreen.main.bounds.width
    @State private var pageToken = 0
    @State private var scopeDragging = false
    @State private var jumpDim = false
    @State private var dialWidths: [CGFloat] = []
    @State private var canScroll = false
    @State private var viewportH: CGFloat = UIScreen.main.bounds.height
    @State private var memory = ScrollMemory()
    /// 滚动位移和顶栏收起。改它不会让照片页重算。
    @State private var overlay = ScrollChromeDriver()
    /// 吸住的那一天。只在换天时写入，用来藏流里那一行、换叠层内容。
    @State private var stuckDayId: String?
    @State private var scrubIndex: Int?
    @State private var similarCompare: SimilarCompareRoute?
    @State private var scrubTask: Task<Void, Never>?
    @State private var settleTask: Task<Void, Never>?
    /// 离开时算好的滚动偏移。写进去之前列表可能还没换成收起后的高度。
    @State private var pendingOffsets: [LibraryScope: CGFloat] = [:]
    @State private var restoreToken = 0

    @State private var initialIDs: Set<String> = []
    @State private var didSeedDevelop = false
    @State private var isScrolling = false
    @State private var listWidth: CGFloat = UIScreen.main.bounds.width

    @State private var selecting = false
    @State private var selected: Set<String> = []
    @State private var dragAnchor: String?
    @State private var dragLast: String?
    @State private var dragBase: Set<String> = []
    @State private var suppressTapUntil = Date.distantPast
    @State private var registry = TileFrameRegistry()
    @State private var handlers = DragSelectHandlers()

    @State private var toastText: String?
    @State private var toastShown = false
    @State private var toastTask: Task<Void, Never>?

    private static let flickSpeed: CGFloat = 900

    private var locale: Locale { language.effectiveLocale }
    private var chinese: Bool { language.isChineseUI }
    private var chromeHeight: CGFloat { DarkroomDimens.photosHeaderHeight + DarkroomDimens.scopeRowHeight }
    private var pinY: CGFloat { overlay.pinY }
    private var scopes: [LibraryScope] { ScopeRules.visibleScopes(onThisDayCount: library.onThisDayCount, current: scope) }
    private var currentIndex: Int { scopes.firstIndex(of: scope) ?? 0 }

    var body: some View {
        ZStack(alignment: .top) {
            DarkroomColors.room.ignoresSafeArea()
            pager
            if kind(scope) == .grid {
                listFade
            }
            if sticks, let id = stuckDayId, let day = daySection(id) {
                dateRow(day, measure: false)
                    .background(DarkroomColors.room)
                    .background { OverlayAnchor(driver: overlay, slot: .sticky) }
                    .zIndex(1)
            }
            if scope == .all && canScroll && kind(scope) == .grid {
                scrubber
                    .zIndex(2)
            }
            chrome
                .background { OverlayAnchor(driver: overlay, slot: .chrome) }
                .zIndex(3)
            overlays
        }
        .environment(\.locale, locale)
        .animation(DarkroomMotion.menuIn, value: menuOpen)
        .animation(DarkroomMotion.menuIn, value: selecting)
        .task {
            bindOverlay()
            library.setPage(scope: scope, unseenOnly: unseenOnly)
            refreshMounted(around: .all)
            if library.sections.isEmpty { await library.reload() }
            if scope == .similar { similarSession.start(library) }
            seedDevelopIfNeeded()
            syncOverlayDays()
        }
        .onChange(of: scrubIndex) { _, index in
            overlay.scrubLockIndex = index
        }
        .onChange(of: expandedDayId) { _, _ in syncOverlayDays() }
        .fullScreenCover(item: $similarCompare) { route in
            SimilarCompareView(groupKey: route.groupKey, photoId: route.photoId) {
                similarCompare = nil
            }
            .environmentObject(library)
            .environmentObject(language)
        }
        .onChange(of: library.sections) { _, _ in
            seedDevelopIfNeeded()
            pruneSelection()
            syncOverlayDays()
            if !pendingOffsets.isEmpty { flushPendingOffset() }
        }
        .onChange(of: library.onThisDayCount) { _, _ in
            refreshMounted(around: scope)
            if !scopeDragging, let i = scopes.firstIndex(of: scope) { animatedProgress = CGFloat(i) }
        }
        .onChange(of: selecting) { _, on in
            hideTabBar = on || showLanguageSheet
            if on { menuOpen = false; overlay.setCollapse(0, animated: false) }
        }
        .onChange(of: showLanguageSheet) { _, on in
            hideTabBar = on || selecting
            if on { overlay.setCollapse(0, animated: false) }
        }
        .onChange(of: menuOpen) { _, open in
            dimTabBar = open
            if open { overlay.setCollapse(0, animated: false) }
        }
        .onChange(of: library.sortOrder) { _, order in
            showToast(L10n.string(order == .oldestFirst ? "sort_oldest" : "sort_newest", locale: locale))
        }
        .onChange(of: reviewLaunch) { _, launch in
            if launch != nil { leavePhotosPage() }
        }
        .onChange(of: photosVisible) { _, shown in
            if shown { flushPendingOffset() } else { leavePhotosPage() }
        }
        .onReceive(NotificationCenter.default.publisher(for: .cleanphotosDragSelect)) { note in
            simulateDragSelect(steps: (note.object as? Int) ?? 6)
        }
        .onReceive(NotificationCenter.default.publisher(for: .cleanphotosHarnessMenu)) { note in
            switch note.object as? String {
            case "sort", "filter", "more":
                withAnimation(DarkroomMotion.menuIn) { menuOpen = true }
                overlay.setCollapse(0, animated: false)
            default:
                exitSelection()
                withAnimation(DarkroomMotion.menuIn) { menuOpen = false }
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: .cleanphotosHarnessScope)) { note in
            guard let raw = note.object as? String else { return }
            menuOpen = false
            exitSelection()
            if raw == "immersive" {
                if scope != .all, let i = scopes.firstIndex(of: .all) { jump(to: i) }
                selectView(.immersive)
                return
            }
            guard let next = LibraryScope(rawValue: raw) else { return }
            if next == .onThisDay, !scopes.contains(.onThisDay) { scope = .onThisDay }
            if let i = scopes.firstIndex(of: next) { jump(to: i) }
        }
    }

    // MARK: Chrome

    private var chrome: some View {
        VStack(spacing: 0) {
            PhotoHeaderBar(
                count: headerCount,
                unit: unitText,
                sub: headerSub,
                locale: locale,
                skeleton: headerSkeleton,
                loadingText: headerLoadingText,
                menuOpen: menuOpen,
                pip: unseenOnly,
                selecting: selecting,
                onMore: toggleMenu,
                onClose: exitSelection
            )
            ScopeDialView(
                scopes: scopes,
                progress: animatedProgress,
                chinese: chinese,
                onSelect: tapScope,
                onDrag: scopeDrag,
                onWidths: { dialWidths = $0 }
            )
        }
    }

    private var headerCount: Int {
        if selecting { return selected.count }
        if scope == .similar, similarSession.phase == .ready { return similarSession.listedGroups.count }
        if scope == .similar { return 0 }
        return library.visibleCount
    }

    private var unitText: String {
        if scope == .similar, !selecting, similarSession.phase == .ready {
            return SimilarCopy.groupUnit(count: similarSession.listedGroups.count, zh: chinese)
        }
        if chinese { return L10n.string("header_count_unit", locale: locale) }
        return PhotoPageCopy.unit(count: headerCount, chinese: false)
    }

    private var headerSub: String? {
        if selecting { return PhotoPageCopy.subSelected(chinese: chinese) }
        if scope == .similar, similarSession.phase == .ready {
            return SimilarCopy.canMove(similarRemovable, zh: chinese)
        }
        if headerSkeleton { return nil }
        guard scope.showsByteTotal else { return nil }
        return PhotoPageCopy.subBytes(FileSizeFormatter.format(library.visibleBytes))
    }

    private var similarRemovable: Int {
        similarSession.listedGroups.reduce(0) { sum, card in
            let map = similarSession.marks[card.key] ?? [:]
            return sum + card.photos.filter { map[$0.id] == .remove }.count
        }
    }

    private var headerLoadingText: String? {
        guard scope == .similar, similarSession.phase != .ready, similarSession.total > 0 else { return nil }
        return SimilarCopy.comparing(done: similarSession.done, total: similarSession.total, zh: chinese)
    }

    private var headerSkeleton: Bool {
        if selecting { return false }
        if scope == .similar && similarSession.phase != .ready { return true }
        if scope == .largeFiles && !library.sizesReady && library.totalCount > 0 { return true }
        return library.isLoading && library.totalCount == 0
    }

    private func toggleMenu() {
        withAnimation(DarkroomMotion.menuIn) {
            menuOpen.toggle()
            showLanguageSheet = false
            if menuOpen { overlay.setCollapse(0, animated: !reduceMotion) }
        }
    }

    private func closeMenu() {
        withAnimation(DarkroomMotion.menuIn) { menuOpen = false }
    }

    // MARK: Pager

    private var pager: some View {
        GeometryReader { geo in
            ZStack(alignment: .topLeading) {
                ForEach(scopes, id: \.self) { s in
                    if mounted.contains(s) {
                        scopePage(s)
                            .frame(width: geo.size.width, height: geo.size.height)
                            .offset(x: CGFloat((scopes.firstIndex(of: s) ?? 0) - currentIndex) * geo.size.width + pagerX)
                            .opacity(s == scope && jumpDim ? 0.7 : 1)
                            .brightness(s == scope && jumpDim ? -0.3 : 0)
                            .allowsHitTesting(s == scope)
                    }
                }
            }
            .onAppear { adopt(geo.size) }
            .onChange(of: geo.size) { _, size in adopt(size) }
        }
    }

    private func adopt(_ size: CGSize) {
        if size.width > 0 {
            let changed = abs(size.width - listWidth) > 0.5
            listWidth = size.width
            pageWidth = size.width
            if changed { syncOverlayDays() }
        }
        if size.height > 0 {
            overlay.viewportH = size.height
            if abs(size.height - viewportH) > 0.5 { viewportH = size.height }
        }
    }

    private func scopePage(_ s: LibraryScope) -> some View {
        let active = s == scope
        let space = "grid-\(s.rawValue)"
        let pageRows = rows(for: s)
        return ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                Color.clear.frame(height: chromeHeight)
                // ForEach 必须是 LazyVStack 的直接子视图。套一层自定义 View 会把整页一次性排完。
                ForEach(pageRows) { row in
                    rowView(row, scope: s, active: active, space: space)
                }
                Color.clear.frame(height: DarkroomDimens.listFade + 48)
            }
            .coordinateSpace(name: space)
            .background { if active { DragSelectProbe(handlers: configuredHandlers()) } }
        }
        .scrollIndicators(.hidden)
    }

    private enum PageRow: Identifiable {
        case line(GridLine)
        case flat(start: Int, items: [PhotoItem], dayId: String, showSize: Bool)
        case message(PhotosBody)
        case similarCard(SimilarCard)
        case similarSkeleton(Int)
        case similarBanner
        case similarEmpty

        var id: String {
            switch self {
            case .line(let line): return line.id
            case .flat(let start, _, let dayId, let showSize): return "f-\(dayId)-\(start)-\(showSize)"
            case .message(let body): return "m-\(body)"
            case .similarCard(let card): return "sim-\(card.id)"
            case .similarSkeleton(let i): return "sim-sk-\(i)"
            case .similarBanner: return "sim-banner"
            case .similarEmpty: return "sim-empty"
            }
        }
    }

    private func rows(for s: LibraryScope) -> [PageRow] {
        let body = kind(s)
        switch body {
        case .similar:
            if similarSession.phase != .ready {
                return [.similarBanner] + (0..<3).map { PageRow.similarSkeleton($0) }
            }
            let cards = similarSession.listedGroups
            if cards.isEmpty { return [.similarEmpty] }
            return cards.map { .similarCard($0) }
        case .grid:
            let page = pageData(s)
            if s == .largeFiles || (s == .all && viewMode == .immersive) {
                return flatRows(page.flat, dayId: s == .largeFiles ? "flat" : "immersive", showSize: s == .largeFiles)
            }
            return gridLines(page, scope: s).map { .line($0) }
        default:
            return [.message(body)]
        }
    }

    private func flatRows(_ items: [PhotoItem], dayId: String, showSize: Bool) -> [PageRow] {
        let cols = DarkroomDimens.gridColumns
        var out: [PageRow] = []
        var start = 0
        while start < items.count {
            let end = min(start + cols, items.count)
            out.append(.flat(start: start, items: Array(items[start..<end]), dayId: dayId, showSize: showSize))
            start = end
        }
        return out
    }

    @ViewBuilder
    private func rowView(_ row: PageRow, scope s: LibraryScope, active: Bool, space: String) -> some View {
        switch row {
        case .line(let line):
            lineView(line, scope: s, active: active, space: space)
        case .flat(let start, let items, let dayId, let showSize):
            tileRow(items, start: start, dayCount: start + items.count, side: tileSide(listWidth), dayId: dayId,
                    active: active, space: space, section: nil, showSize: showSize)
        case .message(let body):
            messageView(body, scope: s)
        case .similarCard(let card):
            SimilarCardRow(
                card: card,
                marks: similarSession.marks[card.key] ?? [:],
                moving: similarSession.moving,
                zh: chinese,
                locale: locale,
                onOpen: { similarCompare = SimilarCompareRoute(groupKey: card.key, photoId: $0) },
                onCommit: { _ = similarSession.commit(groupKey: card.key) }
            )
            .padding(.horizontal, 16)
            .padding(.vertical, 4)
        case .similarSkeleton(let i):
            SimilarSkeletonCard(delay: Double(i) * 0.2, active: photosVisible && active)
                .padding(.horizontal, 16)
        case .similarBanner:
            Text(SimilarCopy.comparing(done: similarSession.done, total: similarSession.total, zh: chinese))
                .font(DarkroomType.countSub)
                .foregroundStyle(DarkroomColors.dim)
                .padding(.horizontal, 16)
                .padding(.top, 8)
        case .similarEmpty:
            Text(SimilarCopy.empty(zh: chinese))
                .font(DarkroomType.title)
                .foregroundStyle(DarkroomColors.paper)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 24)
                .padding(.top, 48)
        }
    }

    @ViewBuilder
    private func messageView(_ body: PhotosBody, scope s: LibraryScope) -> some View {
        switch body {
        case .loading:
            LoadingSkeletonGrid()
        case .allSeen:
            AllSeenView(locale: locale, onTrash: onOpenTrash) { setUnseen(false) }
        case .scopeSeen:
            scopeMessage(PhotoPageCopy.scopeSeenTitle(chinese: chinese), button: PhotoPageCopy.showSeen(chinese: chinese)) {
                setUnseen(false)
            }
        case .scopeEmpty:
            scopeMessage(PhotoPageCopy.emptyTitle(s, chinese: chinese), button: L10n.string("show_all_photos", locale: locale)) {
                tapScope(scopes.firstIndex(of: .all) ?? 0)
            }
        case .libraryEmpty:
            scopeMessage(L10n.string("photos_empty_title", locale: locale), body: L10n.string("photos_empty_body", locale: locale), button: nil, action: {})
        case .grid, .similar:
            EmptyView()
        }
    }

    private func kind(_ s: LibraryScope) -> PhotosBody {
        let page = pageData(s)
        if s != .similar && library.isPartial && page.visibleCount == 0 { return .loading }
        let ready = s != .largeFiles || library.sizesReady || library.totalCount == 0
        return ScopeRules.photosBody(
            scope: s,
            isLoading: library.isLoading || library.isPartial,
            pageReady: ready,
            unseenOnly: unseenOnly,
            visibleCount: page.visibleCount,
            scopeCount: page.scopeCount,
            totalCount: library.totalCount
        )
    }

    private func pageData(_ s: LibraryScope) -> ScopePage {
        library.pages[s] ?? ScopePage(groups: [], flat: [], scopeCount: 0, visibleCount: 0, visibleBytes: 0)
    }

    private func scopeMessage(_ title: String, body: String? = nil, button: String?, action: @escaping () -> Void) -> some View {
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

    // MARK: Scope changes

    private func tapScope(_ index: Int) {
        guard scopes.indices.contains(index), index != currentIndex else { return }
        if abs(index - currentIndex) == 1 { page(to: index) } else { jump(to: index) }
    }

    private func scopeDrag(_ dx: CGFloat, _ ended: Bool, _ velocity: CGFloat) {
        let dx = clampedDX(dx)
        let index = currentIndex
        if !ended {
            if !scopeDragging {
                scopeDragging = true
                pageToken += 1
                overlay.setCollapse(0, animated: !reduceMotion)
            }
            pagerX = dx
            animatedProgress = CGFloat(index) + ScopeDialMath.dragPages(dx: dx, stride: dialStride)
            return
        }
        scopeDragging = false
        let width = max(pageWidth, 1)
        let passed = abs(dx) > width * ScopeDialMath.snapFraction || abs(velocity) > Self.flickSpeed
        var dest = index
        if passed {
            let forward = dx < 0 || (dx == 0 && velocity < 0)
            dest = index + (forward ? 1 : -1)
        }
        dest = min(max(0, dest), scopes.count - 1)
        if dest == index {
            withAnimation(DarkroomMotion.settle) {
                pagerX = 0
                animatedProgress = CGFloat(index)
            }
        } else if abs(dest - index) == 1 {
            page(to: dest)
        } else {
            jump(to: dest)
        }
    }

    private func page(to index: Int) {
        guard scopes.indices.contains(index) else { return }
        let dest = scopes[index]
        if reduceMotion || pageWidth < 1 {
            commit(dest)
            return
        }
        pageToken += 1
        let token = pageToken
        let dx = CGFloat(currentIndex - index) * pageWidth
        withAnimation(DarkroomMotion.settle) {
            pagerX = dx
            animatedProgress = CGFloat(index)
        } completion: {
            guard token == pageToken else { return }
            commit(dest)
        }
    }

    private func jump(to index: Int) {
        guard scopes.indices.contains(index) else { return }
        pageToken += 1
        let dest = scopes[index]
        var t = Transaction(); t.disablesAnimations = true
        withTransaction(t) {
            jumpDim = !reduceMotion
            scope = dest
            pagerX = 0
        }
        overlay.setCollapse(0, animated: false)
        finishScope(dest)
        if reduceMotion {
            animatedProgress = CGFloat(index)
            jumpDim = false
        } else {
            withAnimation(DarkroomMotion.settle) { animatedProgress = CGFloat(index) }
            withAnimation(.linear(duration: DarkroomMotion.short)) { jumpDim = false }
        }
    }

    private func commit(_ dest: LibraryScope) {
        var t = Transaction(); t.disablesAnimations = true
        withTransaction(t) {
            scope = dest
            pagerX = 0
            jumpDim = false
            if let i = scopes.firstIndex(of: dest) { animatedProgress = CGFloat(i) }
        }
        finishScope(dest)
    }

    private func finishScope(_ dest: LibraryScope) {
        let y = memory.saved[dest] ?? 0
        overlay.reset(scrollY: y)
        library.setPage(scope: dest, unseenOnly: unseenOnly)
        refreshMounted(around: dest)
        if dest == .similar { exitSelection(); similarSession.start(library) }
        syncOverlayDays()
        guard pendingOffsets[dest] != nil else { return }
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 50_000_000)
            guard scope == dest else { return }
            flushPendingOffset()
        }
    }

    private func clampedDX(_ dx: CGFloat) -> CGFloat {
        if currentIndex <= 0 && dx > 0 { return 0 }
        if currentIndex >= scopes.count - 1 && dx < 0 { return 0 }
        return dx
    }

    private var dialStride: CGFloat {
        let widths = dialWidths.count == scopes.count ? dialWidths : Array(repeating: 36, count: max(scopes.count, 1))
        return ScopeDialMath.pageStride(progress: CGFloat(currentIndex), widths: widths, gap: DarkroomDimens.scopeDialGap)
    }

    private func refreshMounted(around current: LibraryScope) {
        var next = mounted
        let list = ScopeRules.visibleScopes(onThisDayCount: library.onThisDayCount, current: current)
        guard let i = list.firstIndex(of: current) else { return }
        next.insert(current)
        if i > 0 { next.insert(list[i - 1]) }
        if i + 1 < list.count { next.insert(list[i + 1]) }
        if next != mounted { mounted = next }
    }

    private func setUnseen(_ on: Bool) {
        unseenOnly = on
        library.setPage(scope: scope, unseenOnly: on)
        overlay.setCollapse(0, animated: false)
        syncOverlayDays()
        closeMenu()
    }

    // MARK: Scroll

    private func configuredHandlers() -> DragSelectHandlers {
        let h = handlers
        h.topInset = pinY
        h.onBegin = { beginDrag(at: $0) }
        h.onMove = { moveDrag(to: $0) }
        h.onEnd = { endDrag() }
        h.scopeDragEnabled = { scopeDragEnabled }
        h.isActive = { photosVisible }
        h.onScopeDrag = { scopeDrag($0, $1, $2) }
        h.onScroll = { y, dragging, decelerating, programmatic in
            noteScroll(y, dragging: dragging, decelerating: decelerating, programmatic: programmatic)
        }
        return h
    }

    private var scopeDragEnabled: Bool {
        !selecting && !menuOpen && !showLanguageSheet && dragAnchor == nil && scrubIndex == nil
    }

    private func noteScroll(_ y: CGFloat, dragging: Bool, decelerating: Bool, programmatic: Bool) {
        guard photosVisible else { return }
        if ScrollProbe.enabled { ScrollProbe.shared.page = "photos-\(scope.rawValue)" }
        let moving = dragging || decelerating
        if isScrolling != moving { isScrolling = moving }
        if !moving && !isScrolling { GridDevelopSession.markPlayed(.photos) }
        let contentH = handlers.readContentHeight()
        let viewH = handlers.readViewportHeight()
        if viewH > 0 {
            overlay.viewportH = viewH
            if abs(viewH - viewportH) > 0.5 { viewportH = viewH }
        }
        let fits = contentH > viewH + 1 && viewH > 0
        if fits != canScroll { canScroll = fits }
        prepareOverlay(scrubFits: fits)
        memory.saved[scope] = y
        let forceOpen = selecting || menuOpen || showLanguageSheet
        overlay.ingest(y: y, moving: moving, programmatic: programmatic, forceOpen: forceOpen)
        handlers.topInset = overlay.pinY
        guard !programmatic else {
            settleTask?.cancel()
            return
        }
        // 松手后 contentOffset 不再变，KVO 不会再来。停一下再看列表是不是已经不动了。
        settleTask?.cancel()
        if ScrollProbe.enabled { ScrollProbe.shared.taskSpawn() }
        settleTask = Task { @MainActor in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 80_000_000)
                guard !Task.isCancelled else { return }
                if !handlers.listMoving() { break }
            }
            guard !Task.isCancelled else { return }
            isScrolling = false
            GridDevelopSession.markPlayed(.photos)
            overlay.snap(forceOpen: selecting || menuOpen || showLanguageSheet)
            noteScrubber(moving: false)
        }
        if moving { noteScrubber(moving: true) }
    }

    private func prepareOverlay(scrubFits: Bool) {
        overlay.chromeHeight = chromeHeight
        overlay.scrubBottomReserve = DarkroomDimens.navBottom + DarkroomDimens.navHeight
        overlay.thumbHeight = DarkroomDimens.scrubberThumbHeight
        overlay.sticks = sticks
        overlay.reduceMotion = reduceMotion
        overlay.scrubEnabled = scope == .all && scrubFits && kind(scope) == .grid
    }

    private func noteScrubber(moving: Bool) {
        guard scope == .all, canScroll, kind(scope) == .grid else {
            scrubTask?.cancel(); scrubTask = nil
            overlay.setScrubVisible(false)
            return
        }
        if moving || scrubIndex != nil {
            scrubTask?.cancel(); scrubTask = nil
            overlay.setScrubVisible(true)
        } else if overlay.showsScrubber && scrubTask == nil {
            scrubTask = Task { @MainActor in
                try? await Task.sleep(nanoseconds: UInt64(DarkroomMotion.scrubberHold * 1_000_000_000))
                guard !Task.isCancelled else { return }
                overlay.setScrubVisible(false)
                scrubTask = nil
            }
        }
        if !moving { GridDevelopSession.markPlayed(.photos) }
    }

    private func bindOverlay() {
        overlay.chromeHeight = chromeHeight
        overlay.viewportH = viewportH
        overlay.onStuckDay = { id in
            if stuckDayId != id { stuckDayId = id }
        }
    }

    /// 吸顶和滑块用的日期顺序。测量结果留在 driver 里，这里只更新顺序和还没量到时的估计落点。
    private func syncOverlayDays() {
        bindOverlay()
        let groups = pageData(scope).groups
        let days = groups.flatMap(\.days).filter { $0.header == .day }
        var estimatedById: [String: CGFloat] = [:]
        let grouped = viewMode == .dateGrouped
        if grouped {
            let side = tileSide(listWidth)
            let gap = DarkroomDimens.gridGap
            let cols = CGFloat(DarkroomDimens.gridColumns)
            var y = chromeHeight
            for year in groups {
                if year.year > 0 { y += DarkroomDimens.yearHeight }
                for day in year.days {
                    estimatedById[day.id] = y
                    y += DarkroomDimens.dayHeight
                    let rows = ceil(CGFloat(max(day.allItems.count, 1)) / cols)
                    y += rows * (side + gap)
                }
            }
        }
        var anchors: [CGFloat] = []
        anchors.reserveCapacity(days.count)
        var before = 0
        for day in days {
            if grouped, let y = overlay.headerTop(day.id) {
                anchors.append(y)
            } else if grouped {
                anchors.append(estimatedById[day.id] ?? chromeHeight)
            } else {
                anchors.append(immersiveAnchor(itemsBefore: before))
            }
            before += day.allItems.count
        }
        overlay.sticks = sticks
        overlay.replaceDays(order: days.map(\.id), anchors: anchors, defaultHeight: DarkroomDimens.dayHeight)
    }

    private func immersiveAnchor(itemsBefore: Int) -> CGFloat {
        let row = itemsBefore / DarkroomDimens.gridColumns
        let side = tileSide(listWidth)
        return chromeHeight + CGFloat(row) * (side + DarkroomDimens.gridGap)
    }

    private func daySection(_ id: String) -> PhotoDaySection? {
        pageData(scope).groups.flatMap(\.days).first { $0.id == id }
    }

    // MARK: Grid

    /// LazyVStack 里再套一层「每天一个 ForEach」时，后面几天的格子不会真正挂上，只留下空白。
    /// 年、日期、每一行格子都铺成 LazyVStack 的直接子视图。
    private struct GridLine: Identifiable {
        enum Kind {
            case year(Int)
            case day(PhotoDaySection)
            case month(PhotoDaySection)
            case tiles(items: [PhotoItem], dayId: String, start: Int, dayCount: Int, section: String?)
            case more(PhotoDaySection)
        }
        let id: String
        let kind: Kind
    }

    private func gridLines(_ page: ScopePage, scope s: LibraryScope) -> [GridLine] {
        let days = page.groups.flatMap(\.days)
        let lastDay = days.last { $0.header == .day }?.id
        let cols = DarkroomDimens.gridColumns
        var lines: [GridLine] = []
        for year in page.groups {
            if year.year > 0 {
                lines.append(GridLine(id: "y-\(year.id)", kind: .year(year.year)))
            }
            for day in year.days {
                if day.header == .month {
                    lines.append(GridLine(id: "m-\(day.id)", kind: .month(day)))
                } else if day.header == .day {
                    lines.append(GridLine(id: "d-\(day.id)", kind: .day(day)))
                }
                let drawn = drawnItems(day, scope: s)
                var start = 0
                while start < drawn.count {
                    let end = min(start + cols, drawn.count)
                    let section = (day.id == lastDay && end == drawn.count) ? day.id : nil
                    lines.append(GridLine(
                        id: "t-\(s.rawValue)-\(day.id)-\(start)",
                        kind: .tiles(items: Array(drawn[start..<end]), dayId: day.id, start: start, dayCount: drawn.count, section: section)
                    ))
                    start = end
                }
                if s == .onThisDay && expandedDayId != day.id && !day.overflow.isEmpty {
                    lines.append(GridLine(id: "more-\(day.id)", kind: .more(day)))
                }
            }
        }
        return lines
    }

    @ViewBuilder
    private func lineView(_ line: GridLine, scope s: LibraryScope, active: Bool, space: String) -> some View {
        let side = tileSide(listWidth)
        switch line.kind {
        case .year(let year):
            yearRow(year)
        case .day(let day):
            dateRow(day, measure: active)
        case .month(let day):
            monthRow(day)
        case .tiles(let items, let dayId, let start, let dayCount, let section):
            tileRow(items, start: start, dayCount: dayCount, side: side, dayId: dayId, active: active, space: space, section: section, showSize: false)
        case .more(let day):
            moreRow(day)
        }
    }

    private func tileRow(_ items: [PhotoItem], start: Int, dayCount: Int, side: CGFloat, dayId: String, active: Bool, space: String, section: String?, showSize: Bool) -> some View {
        HStack(spacing: DarkroomDimens.gridGap) {
            ForEach(Array(items.enumerated()), id: \.element.id) { offset, item in
                let index = start + offset
                cell(item, side: side, index: index, dayId: dayId, showSize: showSize)
            }
            if items.count < DarkroomDimens.gridColumns {
                Spacer(minLength: 0)
            }
        }
        .padding(.bottom, DarkroomDimens.gridGap)
        .background {
            if active {
                ContentFrameReader(space: space) { frame in
                    let gap = DarkroomDimens.gridGap
                    for (offset, item) in items.enumerated() {
                        let x = frame.minX + CGFloat(offset) * (side + gap)
                        registry.frames[item.id] = CGRect(x: x, y: frame.minY, width: side, height: side)
                    }
                    if let section {
                        overlay.noteLastItem(id: section, y: frame.minY, height: side)
                    }
                }
            }
        }
        .onDisappear {
            guard active else { return }
            for item in items { registry.frames[item.id] = nil }
        }
    }

    private func cell(_ item: PhotoItem, side: CGFloat, index: Int, dayId: String, showSize: Bool) -> some View {
        let develop = initialIDs.contains(item.id) && GridDevelopSession.shouldDevelop(grid: .photos, id: item.id, isInitialPass: false)
        return PhotoTileView(
            item: item,
            side: side,
            develop: develop && !isScrolling && !selecting,
            developDelay: GridDevelopSession.staggerDelay(row: index / DarkroomDimens.gridColumns, col: index % DarkroomDimens.gridColumns),
            selecting: selecting,
            selected: selecting && selected.contains(item.id),
            sizeText: showSize ? FileSizeFormatter.format(AssetSizeCache.shared.cachedBytes(item.id) ?? 0) : nil,
            pauseRaw: isScrolling
        )
        .frame(width: side, height: side)
        .onTapGesture { tap(item) }
        .accessibilityIdentifier("tile-\(index)-\(dayId)")
    }

    private func drawnItems(_ day: PhotoDaySection, scope s: LibraryScope) -> [PhotoItem] {
        if s == .onThisDay && expandedDayId == day.id { return day.allItems }
        return day.items
    }

    private func tileSide(_ width: CGFloat) -> CGFloat {
        let cols = CGFloat(DarkroomDimens.gridColumns)
        let gap = DarkroomDimens.gridGap
        return (max(width, 1) - gap * (cols - 1)) / cols
    }

    private func yearRow(_ year: Int) -> some View {
        HStack(spacing: 12) {
            Text(String(year)).font(DarkroomType.countSub).tracking(0.88).foregroundStyle(DarkroomColors.dim)
            Rectangle().fill(DarkroomColors.rim).frame(height: 1)
        }
        .frame(height: DarkroomDimens.yearHeight)
        .padding(.horizontal, DarkroomDimens.screenSide)
    }

    private func dateRow(_ day: PhotoDaySection, measure: Bool) -> some View {
        let hidden = measure && day.id == stuckDayId
        return HStack(alignment: .center, spacing: 8) {
            Text(day.id == DayKeyRule.unknown ? "—" : dayNumber(day.date))
                .font(DarkroomType.dayNumber).tracking(-0.24).monospacedDigit()
                .foregroundStyle(DarkroomColors.paper)
            Text(day.id == DayKeyRule.unknown ? L10n.string("date_unknown", locale: locale) : dayMonthWeekday(day.date))
                .font(DarkroomType.dayMeta).foregroundStyle(DarkroomColors.dim)
                .lineLimit(1)
            Spacer(minLength: 0)
            trailing(day, collapse: scope == .onThisDay && expandedDayId == day.id && !selecting)
        }
        .padding(.horizontal, DarkroomDimens.screenSide)
        .frame(minHeight: DarkroomDimens.dayHeight)
        .opacity(hidden ? 0 : 1)
        .allowsHitTesting(!hidden)
        .background {
            if measure {
                ContentFrameReader(space: "grid-\(scope.rawValue)") { frame in
                    overlay.noteHeader(
                        id: day.id,
                        y: frame.minY,
                        height: frame.height,
                        scrubAnchor: scope == .all && viewMode == .dateGrouped
                    )
                }
            }
        }
    }

    private func monthRow(_ day: PhotoDaySection) -> some View {
        HStack(alignment: .center, spacing: 8) {
            monthTitle(day)
            Spacer(minLength: 0)
            trailing(day, collapse: false)
        }
        .padding(.horizontal, DarkroomDimens.screenSide)
        .frame(minHeight: DarkroomDimens.dayHeight)
    }

    @ViewBuilder
    private func monthTitle(_ day: PhotoDaySection) -> some View {
        if day.id == DayKeyRule.unknown {
            Text(L10n.string("date_unknown", locale: locale))
                .font(DarkroomType.dayMeta).foregroundStyle(DarkroomColors.dim)
        } else if chinese {
            HStack(alignment: .firstTextBaseline, spacing: 2) {
                Text("\(Calendar.current.component(.month, from: day.date))")
                    .font(DarkroomType.dayNumber).monospacedDigit().foregroundStyle(DarkroomColors.paper)
                Text(PhotoPageCopy.monthUnit(chinese: true))
                    .font(DarkroomType.dayMeta).foregroundStyle(DarkroomColors.dim)
            }
        } else {
            Text(DateFormatCache.string(day.date, format: "MMM", locale: locale))
                .font(DarkroomType.dayNumber).foregroundStyle(DarkroomColors.paper)
        }
    }

    @ViewBuilder
    private func trailing(_ day: PhotoDaySection, collapse showCollapse: Bool) -> some View {
        if selecting && viewMode == .dateGrouped || selecting && scope != .all {
            let ids = Set(day.allItems.map(\.id))
            let allOn = !ids.isEmpty && ids.isSubset(of: selected)
            Button {
                if allOn { selected.subtract(ids) } else { selected.formUnion(ids) }
                if selected.isEmpty { selecting = false }
            } label: {
                Text(L10n.string(allOn ? "deselect_all" : "select_all", locale: locale))
                    .font(DarkroomType.dayRight).fontWeight(.medium).foregroundStyle(DarkroomColors.paper)
                    .frame(minHeight: DarkroomDimens.minTouch)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        } else if !selecting {
            HStack(spacing: 0) {
                if showCollapse {
                    Button { collapseDay(day.id) } label: {
                        Text(PhotoPageCopy.dayCollapse(chinese: chinese))
                            .font(DarkroomType.dayRight).foregroundStyle(DarkroomColors.paper)
                            .frame(minWidth: DarkroomDimens.minTouch, minHeight: DarkroomDimens.minTouch)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
                Button { openDay(day) } label: {
                    countLabel(day)
                        .frame(minHeight: DarkroomDimens.minTouch)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityHint(Text(L10n.string("cd_enter_day", locale: locale)))
            }
        }
    }

    private func countLabel(_ day: PhotoDaySection) -> Text {
        if day.isDone {
            return Text(PhotoPageCopy.sectionAllSeen(chinese: chinese))
                .font(DarkroomType.dayRight).foregroundStyle(DarkroomColors.dim)
        }
        return Text("\(day.seenCount)").font(DarkroomType.dayRight).foregroundStyle(DarkroomColors.paper)
            + Text(PhotoPageCopy.sectionSlash(day.totalCount)).font(DarkroomType.dayRight).foregroundStyle(DarkroomColors.dim)
    }

    private func moreRow(_ day: PhotoDaySection) -> some View {
        Button { expandedDayId = day.id } label: {
            Text(PhotoPageCopy.dayMore(day.overflow.count, chinese: chinese))
                .font(DarkroomType.dayRight)
                .foregroundStyle(DarkroomColors.paper)
                .frame(maxWidth: .infinity, minHeight: DarkroomDimens.minTouch)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func openDay(_ day: PhotoDaySection) {
        guard tapsAllowed(), let first = day.allItems.first else { return }
        reviewLaunch = ReviewLaunch(scope: .allPhotos, startPhotoId: first.id, dayId: day.id)
    }

    private func collapseDay(_ id: String) {
        expandedDayId = nil
        let pin = pinY
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 50_000_000)
            guard let y = overlay.headerTop(id) else { return }
            let top = y - handlers.readOffset()
            let height = overlay.headerHeight(id)
            if top < pin || top > viewportH - height {
                handlers.writeOffset(y - pin)
            }
        }
    }

    private var sticks: Bool {
        if scope == .onThisDay { return true }
        return scope == .all && viewMode == .dateGrouped
    }

    // MARK: Scrubber

    /// 拇指的 y 由 driver 按滚动偏移改 transform。这里不读 scrollY，避免滑块把整页标脏。
    private var scrubber: some View {
        let days = scrubDays
        return HStack {
            Spacer()
            Color.clear
                .frame(width: DarkroomDimens.minTouch)
                .frame(maxHeight: .infinity)
                .contentShape(Rectangle())
                .background { OverlayAnchor(driver: overlay, slot: .scrubHit) }
                .overlay(alignment: .topTrailing) {
                    ZStack(alignment: .topTrailing) {
                        Capsule()
                            .fill(DarkroomColors.paper)
                            .frame(width: DarkroomDimens.scrubberThumbWidth, height: DarkroomDimens.scrubberThumbHeight)
                            .padding(.trailing, DarkroomDimens.scrubberInset)
                        if let scrubIndex, days.indices.contains(scrubIndex) {
                            scrubBubble(days[scrubIndex])
                                .fixedSize()
                                .offset(x: -(DarkroomDimens.minTouch + DarkroomDimens.scrubberBubbleGap - DarkroomDimens.screenSide))
                        }
                    }
                    .background { OverlayAnchor(driver: overlay, slot: .scrubThumb) }
                }
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { value in
                            let top = overlay.pinY
                            let bottom = max(top + DarkroomDimens.scrubberThumbHeight, overlay.viewportH - DarkroomDimens.navBottom - DarkroomDimens.navHeight)
                            scrub(to: value.location.y, days: days, top: top, bottom: bottom)
                        }
                        .onEnded { value in
                            let top = overlay.pinY
                            let bottom = max(top + DarkroomDimens.scrubberThumbHeight, overlay.viewportH - DarkroomDimens.navBottom - DarkroomDimens.navHeight)
                            scrub(to: value.location.y, days: days, top: top, bottom: bottom)
                            scrubIndex = nil
                            noteScrubber(moving: false)
                        }
                )
        }
    }

    private var scrubDays: [PhotoDaySection] {
        pageData(.all).groups.flatMap(\.days).filter { $0.header == .day }
    }

    private func scrubAnchor(_ day: PhotoDaySection, itemsBefore: Int) -> CGFloat {
        if viewMode == .dateGrouped {
            if let y = overlay.headerTop(day.id) { return y }
            return estimatedDateAnchor(day)
        }
        return immersiveAnchor(itemsBefore: itemsBefore)
    }

    /// 还没量到日期行时，按年份行 + 日期行 + 行高估一个落点。量到之后用真实位置。
    private func estimatedDateAnchor(_ target: PhotoDaySection) -> CGFloat {
        let side = tileSide(listWidth)
        let gap = DarkroomDimens.gridGap
        var y = chromeHeight
        for year in pageData(.all).groups {
            if year.year > 0 { y += DarkroomDimens.yearHeight }
            for day in year.days {
                if day.id == target.id { return y }
                y += DarkroomDimens.dayHeight
                let rows = ceil(CGFloat(max(day.allItems.count, 1)) / CGFloat(DarkroomDimens.gridColumns))
                y += rows * (side + gap)
            }
        }
        return y
    }

    private func scrub(to y: CGFloat, days: [PhotoDaySection], top: CGFloat, bottom: CGFloat) {
        guard !days.isEmpty else { return }
        scrubTask?.cancel(); scrubTask = nil
        overlay.setScrubVisible(true)
        let span = max(1, bottom - top)
        let t = min(1, max(0, (y - top) / span))
        let index = days.count == 1 ? 0 : min(days.count - 1, Int(floor(t * CGFloat(days.count))))
        overlay.scrubLockIndex = index
        scrubIndex = index
        var before = 0
        for day in days.prefix(index) { before += day.allItems.count }
        let anchor = scrubAnchor(days[index], itemsBefore: before)
        handlers.writeOffset(anchor - top)
    }

    @ViewBuilder
    private func scrubBubble(_ day: PhotoDaySection) -> some View {
        if day.id == DayKeyRule.unknown {
            scrubChrome(Text(L10n.string("date_unknown", locale: locale)))
        } else {
            let parts = Calendar.current.dateComponents([.year, .month, .day], from: day.date)
            let label = ScrubDateFormat.label(
                year: parts.year ?? 0,
                month: parts.month ?? 1,
                day: parts.day ?? 1,
                locale: locale
            )
            scrubChrome(ScrubDateLine(label: label, locale: locale))
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(ScrubDateFormat.phrase(label))
        }
    }

    private func scrubChrome<Content: View>(_ content: Content) -> some View {
        content
            .font(DarkroomType.menuItem.monospacedDigit())
            .foregroundStyle(DarkroomColors.paper)
            .lineLimit(1)
            .padding(.horizontal, 12)
            .padding(.vertical, DarkroomDimens.photosHeaderBottom)
            .background {
                RoundedRectangle(cornerRadius: DarkroomDimens.menuItemCorner, style: .continuous)
                    .fill(DarkroomColors.tray)
                    .overlay {
                        RoundedRectangle(cornerRadius: DarkroomDimens.menuItemCorner, style: .continuous)
                            .strokeBorder(DarkroomColors.rim, lineWidth: 1)
                    }
            }
    }

    /// 进清理、或切到收藏 / 垃圾桶。旋转不走这里。范围和「只看没看过」留在这个视图上。
    private func leavePhotosPage() {
        let metrics = gridMetrics()
        let allOffset = allRestoreOffset(metrics: metrics)
        let todayOffset = todayRestoreOffset(metrics: metrics)
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) {
            viewMode = .dateGrouped
            expandedDayId = nil
            menuOpen = false
            showLanguageSheet = false
        }
        overlay.setCollapse(0, animated: false)
        overlay.forgetMeasurements()
        dimTabBar = false
        if !selecting { hideTabBar = false }
        if let allOffset {
            memory.saved[.all] = allOffset
            pendingOffsets[.all] = allOffset
        }
        if let todayOffset {
            memory.saved[.onThisDay] = todayOffset
            pendingOffsets[.onThisDay] = todayOffset
        }
        syncOverlayDays()
        flushPendingOffset()
    }

    private func gridMetrics() -> PhotosLeave.Metrics {
        PhotosLeave.Metrics(
            chrome: chromeHeight,
            year: DarkroomDimens.yearHeight,
            header: DarkroomDimens.dayHeight,
            more: DarkroomDimens.minTouch,
            tilePitch: tileSide(listWidth) + DarkroomDimens.gridGap,
            columns: DarkroomDimens.gridColumns
        )
    }

    private func leaveSections(_ scope: LibraryScope) -> [PhotosGridSection] {
        pageData(scope).groups.flatMap { year in
            year.days.enumerated().map { index, day in
                let header: PhotosGridHeader
                switch day.header {
                case .day: header = .day
                case .month: header = .month
                case .none: header = .none
                }
                return PhotosGridSection(
                    dayKey: day.id,
                    yearHeader: (index == 0 && year.year > 0) ? year.year : nil,
                    photoIds: day.items.map(\.id),
                    overflowIds: day.overflow.map(\.id),
                    header: header
                )
            }
        }
    }

    private func contentLine(for scope: LibraryScope) -> CGFloat {
        let pin = scope == self.scope ? pinY : chromeHeight
        return currentScroll(scope) + pin
    }

    private func currentScroll(_ scope: LibraryScope) -> CGFloat {
        if scope == self.scope { return handlers.readOffset() }
        return memory.saved[scope] ?? 0
    }

    private func allRestoreOffset(metrics: PhotosLeave.Metrics) -> CGFloat? {
        guard viewMode == .immersive else { return nil }
        let sections = leaveSections(.all)
        let count = sections.reduce(0) { $0 + $1.photoIds.count }
        guard count > 0 else { return nil }
        let first = PhotosLeave.firstVisibleImmersiveIndex(
            contentLine: contentLine(for: .all),
            photoCount: count,
            metrics: metrics
        )
        guard let target = PhotosLeave.indexAfterLeave(
            sections: sections,
            immersive: true,
            expandedDayKey: nil,
            firstIndex: first
        ), let top = PhotosLeave.contentTop(index: target, sections: sections, expandedDayKey: nil, metrics: metrics) else {
            return nil
        }
        return max(0, top - metrics.chrome)
    }

    private func todayRestoreOffset(metrics: PhotosLeave.Metrics) -> CGFloat? {
        guard let day = expandedDayId else { return nil }
        let sections = leaveSections(.onThisDay)
        let first = PhotosLeave.firstVisibleIndex(
            contentLine: contentLine(for: .onThisDay),
            sections: sections,
            expandedDayKey: day,
            metrics: metrics
        )
        guard let target = PhotosLeave.indexAfterLeave(
            sections: sections,
            immersive: false,
            expandedDayKey: day,
            firstIndex: first
        ), let top = PhotosLeave.contentTop(index: target, sections: sections, expandedDayKey: nil, metrics: metrics) else {
            return nil
        }
        return max(0, top - metrics.chrome)
    }

    private func flushPendingOffset() {
        guard let y = pendingOffsets[scope] else { return }
        handlers.writeOffset(y)
        if abs(handlers.readOffset() - y) < 1.5 {
            pendingOffsets[scope] = nil
            return
        }
        scheduleRestore(scope)
    }

    private func scheduleRestore(_ target: LibraryScope) {
        restoreToken += 1
        let token = restoreToken
        Task { @MainActor in
            for _ in 0..<15 {
                try? await Task.sleep(nanoseconds: 50_000_000)
                guard token == restoreToken, scope == target, let y = pendingOffsets[target] else { return }
                handlers.writeOffset(y)
                if abs(handlers.readOffset() - y) < 1.5 {
                    pendingOffsets[target] = nil
                    return
                }
            }
            guard token == restoreToken else { return }
            // 切走时列表可能还没布局。露出来之后再试；已经在照片页上就停，免得过后把用户拽回去。
            if photosVisible && handlers.readContentHeight() >= 80 {
                pendingOffsets[target] = nil
            }
        }
    }

    // MARK: Menus and chrome overlays

    @ViewBuilder
    private var overlays: some View {
        if selecting {
            VStack {
                Spacer()
                selectionBar
                    .padding(.bottom, DarkroomDimens.navBottom)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        if scope == .similar, let undo = similarSession.keptUndo {
            VStack {
                Spacer()
                KeptUndoCapsule(undo: undo, zh: chinese) { similarSession.undoKept() }
                    .padding(.horizontal, 16)
                    .padding(.bottom, DarkroomDimens.navBottom + DarkroomDimens.navHeight + 8)
                    .task(id: undo.id) {
                        try? await Task.sleep(nanoseconds: UInt64(UndoBanner.lifetime * 1_000_000_000))
                        similarSession.expireKept(undo.id)
                    }
            }
        }
        if let banner = library.undoBanner, !selecting, !banner.single, !library.reviewActive {
            VStack {
                Spacer()
                UndoCapsule(banner: banner, locale: locale) { library.undoLast() }
                    .padding(.horizontal, DarkroomDimens.screenSide)
                    .padding(.bottom, DarkroomDimens.navBottom + DarkroomDimens.navHeight + 16)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .id(banner.id)
            }
            .animation(DarkroomMotion.standard, value: banner.id)
        }
        sortToast
        if menuOpen || showLanguageSheet {
            Color(red: 0x0A/255, green: 0x09/255, blue: 0x08/255)
                .opacity(showLanguageSheet ? 0.64 : 0.48)
                .ignoresSafeArea()
                .accessibilityHidden(true)
                .onTapGesture {
                    withAnimation(DarkroomMotion.menuIn) {
                        menuOpen = false
                        showLanguageSheet = false
                    }
                }
            if !showLanguageSheet {
                chrome
            }
        }
        if menuOpen { menus }
        if showLanguageSheet {
            VStack {
                Spacer()
                LanguageSheetView(selection: language.preference) { pref in
                    if pref != language.preference { language.select(pref) }
                    withAnimation(DarkroomMotion.menuIn) { showLanguageSheet = false }
                }
                .padding(.horizontal, 24)
                .padding(.bottom, 48)
            }
            .transition(.move(edge: .bottom).combined(with: .opacity))
        }
    }

    private var listFade: some View {
        VStack {
            Spacer()
            LinearGradient(
                stops: [
                    .init(color: .clear, location: 0),
                    .init(color: DarkroomColors.listFadeMid, location: 0.7),
                    .init(color: DarkroomColors.room, location: 1),
                ],
                startPoint: .top, endPoint: .bottom
            )
            .frame(height: DarkroomDimens.listFade)
            .allowsHitTesting(false)
        }
        .ignoresSafeArea(edges: .bottom)
    }

    private var menus: some View {
        DarkroomMenuView(sections: menuSections)
            .padding(.top, DarkroomDimens.photosHeaderHeight)
            .padding(.trailing, DarkroomDimens.screenSide)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
    }

    private var menuSections: [DarkroomMenuSection] {
        var sections: [DarkroomMenuSection] = []
        if scope == .all {
            sections.append(DarkroomMenuSection(header: PhotoPageCopy.menuView(chinese: chinese), options: [
                DarkroomMenuOption(label: PhotoPageCopy.viewByDate(chinese: chinese), trailing: L10n.string("sort_default", locale: locale), selected: viewMode == .dateGrouped) {
                    selectView(.dateGrouped)
                },
                DarkroomMenuOption(label: PhotoPageCopy.viewImmersive(chinese: chinese), trailing: nil, selected: viewMode == .immersive) {
                    selectView(.immersive)
                },
            ]))
        }
        if scope.showsSortMenu {
            sections.append(DarkroomMenuSection(header: L10n.string("menu_sort_header", locale: locale), options: [
                DarkroomMenuOption(label: L10n.string("sort_newest", locale: locale), trailing: L10n.string("sort_default", locale: locale), selected: library.sortOrder == .newestFirst) {
                    library.sortOrder = .newestFirst; closeMenu()
                },
                DarkroomMenuOption(label: L10n.string("sort_oldest", locale: locale), trailing: nil, selected: library.sortOrder == .oldestFirst) {
                    library.sortOrder = .oldestFirst; closeMenu()
                },
            ]))
        }
        sections.append(DarkroomMenuSection(header: nil, options: [
            DarkroomMenuOption(label: PhotoPageCopy.unseenOnly(chinese: chinese), trailing: nil, selected: false, toggle: unseenOnly) {
                setUnseen(!unseenOnly)
            },
        ]))
        var languageOptions = [
            DarkroomMenuOption(label: L10n.string("menu_language", locale: locale), trailing: languageLabel, selected: false) {
                withAnimation(DarkroomMotion.menuIn) {
                    menuOpen = false
                    showLanguageSheet = true
                }
                overlay.setCollapse(0, animated: false)
            },
        ]
        if library.authorization == .limited {
            languageOptions.append(DarkroomMenuOption(label: L10n.string("menu_manage_selection", locale: locale), trailing: nil, selected: false) {
                closeMenu()
                library.presentLimitedLibraryPicker()
            })
        }
        sections.append(DarkroomMenuSection(header: nil, options: languageOptions))
        return sections
    }

    private func selectView(_ mode: PhotosViewMode) {
        let pin = pinY
        let anchor = registry.frames.filter { $0.value.maxY > pin }.min { $0.value.minY < $1.value.minY }?.key
        if mode != viewMode {
            var t = Transaction(); t.disablesAnimations = true
            withTransaction(t) {
                viewMode = mode
            }
            overlay.forgetMeasurements()
            syncOverlayDays()
            if let anchor {
                Task { @MainActor in
                    try? await Task.sleep(nanoseconds: 80_000_000)
                    guard let frame = registry.frames[anchor] else { return }
                    handlers.writeOffset(frame.minY - pin)
                }
            }
        }
        closeMenu()
    }

    private var sortToast: some View {
        GeometryReader { geo in
            if let toastText {
                Text(toastText)
                    .font(Font.custom("NotoSansSC-Regular", size: 14))
                    .foregroundStyle(DarkroomColors.paper)
                    .padding(.horizontal, 19)
                    .frame(height: 38)
                    .background(Capsule().fill(Color(red: 0x1E/255, green: 0x1C/255, blue: 0x19/255).opacity(0.86)))
                    .overlay(Capsule().strokeBorder(DarkroomColors.menuRim, lineWidth: 1))
                    .opacity(toastShown ? 1 : 0)
                    .offset(y: toastShown ? 0 : 9.5)
                    .frame(maxWidth: .infinity)
                    .padding(.top, geo.size.height * 342 / 494)
            }
        }
        .allowsHitTesting(false)
    }

    private func showToast(_ text: String) {
        toastTask?.cancel()
        toastText = text
        toastShown = false
        toastTask = Task { @MainActor in
            withAnimation(.timingCurve(0, 0, 0, 1, duration: 0.224)) { toastShown = true }
            try? await Task.sleep(nanoseconds: 1_288_000_000)
            guard !Task.isCancelled else { return }
            withAnimation(.linear(duration: 0.224)) { toastShown = false }
            try? await Task.sleep(nanoseconds: 240_000_000)
            if !Task.isCancelled { toastText = nil }
        }
    }

    private var selectionBar: some View {
        HStack(spacing: 4) {
            Button {
                _ = library.favorite(ids: Array(selected))
                exitSelection()
            } label: {
                HStack(spacing: 4) {
                    Image(systemName: "star.fill")
                        .font(.system(size: 16, weight: .medium))
                        .foregroundStyle(DarkroomColors.amber)
                    Text(L10n.string("action_favorite", locale: locale))
                }
                .font(Font.custom("NotoSansSC-Medium", size: 13))
                .foregroundStyle(DarkroomColors.paper)
                .frame(width: 112, height: 36)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            Button {
                _ = library.moveToTrash(ids: Array(selected))
                exitSelection()
            } label: {
                HStack(spacing: 4) {
                    Image(systemName: "trash")
                        .font(.system(size: 16, weight: .regular))
                    Text(L10n.string("action_move_to_trash", locale: locale))
                        .lineLimit(1)
                        .minimumScaleFactor(0.85)
                }
                .font(Font.custom("NotoSansSC-Medium", size: 13))
                .foregroundStyle(DarkroomColors.paper)
                .frame(width: 112, height: 36)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .padding(DarkroomDimens.navPadding)
        .frame(height: DarkroomDimens.navHeight)
        .background {
            Capsule()
                .fill(DarkroomColors.navGlass)
                .overlay(Capsule().strokeBorder(DarkroomColors.navRim, lineWidth: 1))
                .shadow(color: DarkroomColors.navShadow, radius: 16, y: 12)
                .background(.ultraThinMaterial, in: Capsule())
        }
        .opacity(selected.isEmpty ? 0.5 : 1)
        .disabled(selected.isEmpty)
    }

    private func exitSelection() {
        selecting = false
        selected = []
        dragAnchor = nil
        dragLast = nil
    }

    private var languageLabel: String {
        language.preference.fixedTitle(followSystemLocalized: L10n.string("language_follow_system", locale: locale))
    }

    // MARK: Drag select

    private var drawnIds: Set<String> { Set(drawnOrder().map(\.id)) }

    private var scopeIds: Set<String> {
        let page = pageData(scope)
        if scope == .largeFiles || (scope == .all && viewMode == .immersive) {
            return Set(page.flat.map(\.id))
        }
        return Set(page.groups.flatMap { $0.days.flatMap { $0.allItems.map(\.id) } })
    }

    private func drawnOrder() -> [PhotoItem] {
        let page = pageData(scope)
        if scope == .similar { return [] }
        if scope == .largeFiles || (scope == .all && viewMode == .immersive) { return page.flat }
        return page.groups.flatMap { $0.days.flatMap { drawnItems($0, scope: scope) } }
    }

    @discardableResult
    private func beginDrag(at point: CGPoint) -> Bool {
        guard scope != .similar, !library.isLoading, !menuOpen, !showLanguageSheet else { return false }
        guard let id = registry.id(at: point), drawnIds.contains(id) else { return false }
        guard selecting || tapsAllowed() else { return false }
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        suppressTapUntil = .distantFuture
        dragBase = selecting ? selected : []
        selecting = true
        selected = dragBase.union([id])
        dragAnchor = id
        dragLast = id
        return true
    }

    private func moveDrag(to point: CGPoint) {
        guard let anchor = dragAnchor, let id = registry.id(at: point), id != dragLast else { return }
        dragLast = id
        let order = drawnOrder()
        guard let from = order.firstIndex(where: { $0.id == anchor }),
              let to = order.firstIndex(where: { $0.id == id }) else { return }
        let range = order[min(from, to)...max(from, to)].map(\.id)
        let next = dragBase.union(range)
        if next != selected {
            UISelectionFeedbackGenerator().selectionChanged()
            selected = next
        }
    }

    private func endDrag() {
        dragAnchor = nil
        dragLast = nil
        suppressTapUntil = Date().addingTimeInterval(0.3)
    }

    private func simulateDragSelect(steps: Int) {
        guard scope != .similar else { return }
        let ids = drawnOrder().prefix(max(1, steps)).map(\.id)
        guard let first = ids.first, let start = registry.frames[first] else { return }
        if beginDrag(at: CGPoint(x: start.midX, y: start.midY)) {
            for id in ids.dropFirst() {
                if let f = registry.frames[id] { moveDrag(to: CGPoint(x: f.midX, y: f.midY)) }
            }
            endDrag()
        }
    }

    private func pruneSelection() {
        guard selecting else { return }
        let keep = SelectionPrune.remaining(selected: selected, scope: scopeIds)
        if keep != selected { selected = keep }
        if selected.isEmpty && dragAnchor == nil { selecting = false }
    }

    private func tap(_ item: PhotoItem) {
        guard scope != .similar, Date() >= suppressTapUntil else { return }
        if selecting {
            if selected.contains(item.id) { selected.remove(item.id) } else { selected.insert(item.id) }
            if selected.isEmpty { selecting = false }
        } else {
            guard tapsAllowed() else { return }
            reviewLaunch = ReviewLaunch(scope: .allPhotos, startPhotoId: item.id, dayId: nil)
        }
    }

    private func tapsAllowed() -> Bool {
        if handlers.listMoving() { return false }
        return CACurrentMediaTime() - handlers.lastScrollTime() >= DarkroomMotion.scrollSettleIgnore
    }

    private func seedDevelopIfNeeded() {
        guard !didSeedDevelop else { return }
        var ids: Set<String> = []
        for item in drawnOrder().prefix(24) { ids.insert(item.id) }
        guard !ids.isEmpty else { return }
        initialIDs = ids
        didSeedDevelop = true
        for id in ids { _ = GridDevelopSession.shouldDevelop(grid: .photos, id: id, isInitialPass: true) }
    }

    private func dayNumber(_ date: Date) -> String {
        DateFormatCache.string(date, format: "d", locale: locale)
    }
    private func dayMonthWeekday(_ date: Date) -> String {
        DateFormatCache.string(date, format: chinese ? "M月 · EEE" : "MMM · EEE", locale: locale)
    }
}

/// 滑块气泡的一行。月、日各占两位，个位数靠右；英文月份占十二个短名里最宽的宽度。
private struct ScrubDateLine: View {
    let label: ScrubDateFormat.Label
    let locale: Locale

    var body: some View {
        HStack(spacing: 0) {
            if label.chinese {
                Text(label.year)
                Text("年")
                digitSlot(label.month)
                Text("月")
                digitSlot(label.day)
                Text("日")
            } else {
                monthSlot(label.month)
                Text(" ")
                digitSlot(label.day)
                Text(", ")
                Text(label.year)
            }
        }
        .lineLimit(1)
        .fixedSize(horizontal: true, vertical: true)
    }

    private func digitSlot(_ value: String) -> some View {
        ZStack(alignment: .trailing) {
            Text("00").hidden().accessibilityHidden(true)
            Text(value)
        }
    }

    private func monthSlot(_ name: String) -> some View {
        ZStack(alignment: .leading) {
            ForEach(Array(ScrubDateFormat.shortMonths(locale: locale).enumerated()), id: \.offset) { _, month in
                Text(month).hidden().accessibilityHidden(true)
            }
            Text(name)
        }
    }
}

/// 全部看过了。文字按钮关掉「只看没看过」。
struct AllSeenView: View {
    @EnvironmentObject private var library: PhotoLibraryService
    var locale: Locale
    var onTrash: () -> Void
    var onShowAll: () -> Void

    private var chinese: Bool { locale.identifier.lowercased().hasPrefix("zh") }

    var body: some View {
        EmptyStateArea {
            ClotheslineArt(photoIds: library.hangPhotoIds)
                .padding(.bottom, 24)
            EmptyTitleAndBody(
                L10n.string("all_seen_title", locale: locale),
                L10n.pluralFormat("all_seen_body", count: library.totalCount, locale: locale, NumberFormatter.localizedString(from: NSNumber(value: library.totalCount), number: .decimal))
            )
            let parts = FileSizeFormatter.parts(library.trashBytes)
            HStack(spacing: 24) {
                stat("\(library.favoriteCount)", unit: nil, color: DarkroomColors.amber, label: "stat_favorites")
                stat("\(library.trashCount)", unit: L10n.plural("count_unit", count: library.trashCount, locale: locale), color: DarkroomColors.fadeText, label: "stat_trash")
                stat(parts.number, unit: parts.unit, color: DarkroomColors.paper, label: "stat_to_free")
            }
            .padding(.bottom, 24)
            if library.trashCount > 0 {
                GhostButton(title: L10n.string("all_seen_view_trash", locale: locale),
                            mono: L10n.format("all_seen_trash_mono", locale: locale, FileSizeFormatter.format(library.trashBytes)),
                            action: onTrash)
                    .accessibilityIdentifier("all-seen-trash")
            }
            Button(action: onShowAll) {
                Text(PhotoPageCopy.showSeen(chinese: chinese))
                    .font(Font.custom("NotoSansSC-Regular", size: 13))
                    .foregroundStyle(DarkroomColors.dim)
                    .frame(height: 20)
            }
            .buttonStyle(.plain)
            .padding(.top, 12)
            .accessibilityIdentifier("all-seen-show-all")
        }
    }

    private func stat(_ value: String, unit: String?, color: Color, label: String) -> some View {
        VStack(spacing: 4) {
            HStack(alignment: .firstTextBaseline, spacing: 2) {
                Text(value).font(DarkroomFonts.schibsted(size: 20, weight: .semibold)).foregroundStyle(color)
                if let unit, !unit.isEmpty {
                    Text(unit).font(Font.custom("NotoSansSC-Regular", size: 12)).foregroundStyle(DarkroomColors.dim)
                }
            }
            Text(L10n.string(label, locale: locale))
                .font(Font.custom("NotoSansSC-Regular", size: 11))
                .foregroundStyle(DarkroomColors.dim)
        }
    }
}


