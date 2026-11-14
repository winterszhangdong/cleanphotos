import SwiftUI
import UIKit

private enum PhotosViewMode: Equatable { case dateGrouped, immersive }

private struct ItemSpan: Equatable { var y: CGFloat; var height: CGFloat }

private struct GridMark: Equatable {
    var scope: String
    var section: String
    /// 0 日期行，1 这一天最后一张。
    var kind: Int
    var y: CGFloat
    var height: CGFloat
}

private struct GridMarksKey: PreferenceKey {
    static var defaultValue: [GridMark] = []
    static func reduce(value: inout [GridMark], nextValue: () -> [GridMark]) {
        value.append(contentsOf: nextValue())
    }
}

/// 滚动偏移记在引用类型里，避免每一帧都把照片页标成需要刷新。
private final class ScrollMemory {
    var lastOffset: CGFloat = 0
    var skip = false
    var saved: [LibraryScope: CGFloat] = [:]
}

struct PhotosView: View {
    @EnvironmentObject private var library: PhotoLibraryService
    @EnvironmentObject private var language: LanguageStore
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Binding var reviewLaunch: ReviewLaunch?
    @Binding var hideTabBar: Bool
    @Binding var dimTabBar: Bool
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
    @State private var collapse: CGFloat = 0
    @State private var scrollY: CGFloat = 0
    @State private var canScroll = false
    @State private var viewportH: CGFloat = UIScreen.main.bounds.height
    @State private var memory = ScrollMemory()
    @State private var headerY: [String: CGFloat] = [:]
    @State private var headerH: [String: CGFloat] = [:]
    @State private var lastItem: [String: ItemSpan] = [:]
    @State private var scrubberShown = false
    @State private var scrubIndex: Int?
    @State private var scrubTask: Task<Void, Never>?
    @State private var settleTask: Task<Void, Never>?

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
    private var pinY: CGFloat { chromeHeight - collapse }
    private var scopes: [LibraryScope] { ScopeRules.visibleScopes(onThisDayCount: library.onThisDayCount, current: scope) }
    private var currentIndex: Int { scopes.firstIndex(of: scope) ?? 0 }

    var body: some View {
        ZStack(alignment: .top) {
            DarkroomColors.room.ignoresSafeArea()
            pager
            if kind(scope) == .grid {
                listFade
            }
            if let stuck = stuckHeader() {
                dateRow(stuck.0, measure: false)
                    .background(DarkroomColors.room)
                    .offset(y: stuck.1)
                    .zIndex(1)
            }
            if scope == .all && canScroll && kind(scope) == .grid {
                scrubber
                    .opacity(scrubberShown ? 1 : 0)
                    .allowsHitTesting(scrubberShown || scrubIndex != nil)
                    .zIndex(2)
            }
            chrome
                .offset(y: -collapse)
                .zIndex(3)
            overlays
        }
        .environment(\.locale, locale)
        .animation(DarkroomMotion.menuIn, value: menuOpen)
        .animation(DarkroomMotion.menuIn, value: selecting)
        .task {
            library.setPage(scope: scope, unseenOnly: unseenOnly)
            refreshMounted(around: .all)
            if library.sections.isEmpty { await library.reload() }
            seedDevelopIfNeeded()
        }
        .onChange(of: library.sections) { _, _ in
            seedDevelopIfNeeded()
            pruneSelection()
        }
        .onChange(of: library.onThisDayCount) { _, _ in
            refreshMounted(around: scope)
            if !scopeDragging, let i = scopes.firstIndex(of: scope) { animatedProgress = CGFloat(i) }
        }
        .onChange(of: selecting) { _, on in
            hideTabBar = on || showLanguageSheet
            if on { menuOpen = false; collapse = 0 }
        }
        .onChange(of: showLanguageSheet) { _, on in
            hideTabBar = on || selecting
            if on { collapse = 0 }
        }
        .onChange(of: menuOpen) { _, open in
            dimTabBar = open
            if open { collapse = 0 }
        }
        .onChange(of: library.sortOrder) { _, order in
            showToast(L10n.string(order == .oldestFirst ? "sort_oldest" : "sort_newest", locale: locale))
        }
        .onChange(of: reviewLaunch) { _, launch in
            guard launch != nil else { return }
            var t = Transaction(); t.disablesAnimations = true
            withTransaction(t) {
                viewMode = .dateGrouped
                expandedDayId = nil
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: .cleanphotosDragSelect)) { note in
            simulateDragSelect(steps: (note.object as? Int) ?? 6)
        }
        .onReceive(NotificationCenter.default.publisher(for: .cleanphotosHarnessMenu)) { note in
            switch note.object as? String {
            case "sort", "filter", "more":
                withAnimation(DarkroomMotion.menuIn) { menuOpen = true; collapse = 0 }
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
        .onPreferenceChange(GridMarksKey.self) { mergeMarks($0) }
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
        if scope == .similar, similarSession.phase == .ready { return similarSession.groups.count }
        if scope == .similar { return 0 }
        return library.visibleCount
    }

    private var unitText: String {
        if scope == .similar, !selecting, similarSession.phase == .ready {
            return SimilarCopy.groupUnit(count: similarSession.groups.count, zh: chinese)
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
        similarSession.marks.values.reduce(0) { sum, group in
            sum + group.values.filter { $0 == .remove }.count
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
            if menuOpen { collapse = 0 }
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
        if size.width > 0 { listWidth = size.width; pageWidth = size.width }
        if size.height > 0 { viewportH = size.height }
    }

    private func scopePage(_ s: LibraryScope) -> some View {
        let active = s == scope
        let space = "grid-\(s.rawValue)"
        return ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                Color.clear.frame(height: chromeHeight)
                pageBody(s, active: active, space: space)
                Color.clear.frame(height: DarkroomDimens.listFade + 48)
            }
            .coordinateSpace(name: space)
            .background { if active { DragSelectProbe(handlers: configuredHandlers()) } }
        }
        .scrollIndicators(.hidden)
    }

    @ViewBuilder
    private func pageBody(_ s: LibraryScope, active: Bool, space: String) -> some View {
        switch kind(s) {
        case .similar:
            SimilarScopePage()
                .frame(maxWidth: .infinity, minHeight: max(0, viewportH - chromeHeight - 48))
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
        case .grid:
            grid(s, active: active, space: space)
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
                withAnimation(DarkroomMotion.settle) { collapse = 0 }
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
            collapse = 0
        }
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
            collapse = 0
            jumpDim = false
            if let i = scopes.firstIndex(of: dest) { animatedProgress = CGFloat(i) }
        }
        finishScope(dest)
    }

    private func finishScope(_ dest: LibraryScope) {
        let y = memory.saved[dest] ?? 0
        memory.skip = true
        memory.lastOffset = y
        scrollY = y
        headerY = [:]
        headerH = [:]
        lastItem = [:]
        library.setPage(scope: dest, unseenOnly: unseenOnly)
        refreshMounted(around: dest)
        if dest == .similar { exitSelection() }
        collapse = 0
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
        collapse = 0
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
        let moving = dragging || decelerating
        if isScrolling != moving { isScrolling = moving }
        if !moving && isScrolling == false { GridDevelopSession.markPlayed(.photos) }
        let contentH = handlers.readContentHeight()
        let viewH = handlers.readViewportHeight()
        if viewH > 0 { viewportH = viewH }
        let fits = contentH > viewH + 1 && viewH > 0
        if fits != canScroll { canScroll = fits }
        memory.saved[scope] = y
        if memory.skip {
            memory.skip = false
            memory.lastOffset = y
            scrollY = y
            return
        }
        let delta = y - memory.lastOffset
        memory.lastOffset = y
        if abs(y - scrollY) >= 0.5 { scrollY = y }
        guard !programmatic else {
            settleTask?.cancel()
            return
        }
        if selecting || menuOpen || showLanguageSheet || y <= 1 {
            if collapse != 0 { collapse = 0 }
        } else if moving {
            let next = min(chromeHeight, max(0, collapse + delta))
            if abs(next - collapse) >= 0.5 { collapse = next }
        }
        // 松手后 contentOffset 不再变，KVO 不会再来。停一下再看列表是不是已经不动了。
        settleTask?.cancel()
        settleTask = Task { @MainActor in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 80_000_000)
                guard !Task.isCancelled else { return }
                if !handlers.listMoving() { break }
            }
            guard !Task.isCancelled else { return }
            isScrolling = false
            GridDevelopSession.markPlayed(.photos)
            if !selecting && !menuOpen && !showLanguageSheet && memory.lastOffset > 1 {
                let target: CGFloat = collapse > chromeHeight / 2 ? chromeHeight : 0
                if abs(collapse - target) > 0.5 {
                    withAnimation(DarkroomMotion.settle) { collapse = target }
                }
            }
            noteScrubber(moving: false)
        }
        if moving { noteScrubber(moving: true) }
    }

    private func noteScrubber(moving: Bool) {
        guard scope == .all, canScroll, kind(scope) == .grid else {
            scrubTask?.cancel(); scrubTask = nil
            if scrubberShown { scrubberShown = false }
            return
        }
        if moving || scrubIndex != nil {
            scrubTask?.cancel(); scrubTask = nil
            if !scrubberShown {
                withAnimation(.linear(duration: DarkroomMotion.short)) { scrubberShown = true }
            }
        } else if scrubberShown && scrubTask == nil {
            scrubTask = Task { @MainActor in
                try? await Task.sleep(nanoseconds: UInt64(DarkroomMotion.scrubberHold * 1_000_000_000))
                guard !Task.isCancelled else { return }
                withAnimation(.linear(duration: DarkroomMotion.short)) { scrubberShown = false }
                scrubTask = nil
            }
        }
        if !moving { GridDevelopSession.markPlayed(.photos) }
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
    private func grid(_ s: LibraryScope, active: Bool, space: String) -> some View {
        let page = pageData(s)
        let side = tileSide(listWidth)
        if s == .largeFiles || (s == .all && viewMode == .immersive) {
            rows(page.flat, side: side, dayId: s == .largeFiles ? "flat" : "immersive", active: active, space: space, section: nil, showSize: s == .largeFiles)
        } else {
            let lines = gridLines(page, scope: s)
            ForEach(lines) { line in
                switch line.kind {
                case .year(let year):
                    yearRow(year)
                case .day(let day):
                    dateRow(day, measure: active && s == scope)
                case .month(let day):
                    monthRow(day)
                case .tiles(let items, let dayId, let start, let dayCount, let section):
                    tileRow(items, start: start, dayCount: dayCount, side: side, dayId: dayId, active: active && s == scope, space: space, section: section, showSize: false)
                case .more(let day):
                    moreRow(day)
                }
            }
        }
    }

    private func rows(_ items: [PhotoItem], side: CGFloat, dayId: String, active: Bool, space: String, section: String?, showSize: Bool) -> some View {
        let starts = Array(stride(from: 0, to: items.count, by: DarkroomDimens.gridColumns))
        return ForEach(starts, id: \.self) { start in
            let end = min(start + DarkroomDimens.gridColumns, items.count)
            tileRow(Array(items[start..<end]), start: start, dayCount: items.count, side: side, dayId: dayId, active: active, space: space,
                    section: section, showSize: showSize)
        }
    }

    private func tileRow(_ items: [PhotoItem], start: Int, dayCount: Int, side: CGFloat, dayId: String, active: Bool, space: String, section: String?, showSize: Bool) -> some View {
        HStack(spacing: DarkroomDimens.gridGap) {
            ForEach(Array(items.enumerated()), id: \.element.id) { offset, item in
                let index = start + offset
                cell(item, side: side, index: index, dayId: dayId, active: active, space: space,
                     measureSection: (section != nil && index == dayCount - 1) ? section : nil, showSize: showSize)
            }
            if items.count < DarkroomDimens.gridColumns {
                Spacer(minLength: 0)
            }
        }
        .padding(.bottom, DarkroomDimens.gridGap)
    }

    private func cell(_ item: PhotoItem, side: CGFloat, index: Int, dayId: String, active: Bool, space: String, measureSection: String?, showSize: Bool) -> some View {
        let develop = initialIDs.contains(item.id) && GridDevelopSession.shouldDevelop(grid: .photos, id: item.id, isInitialPass: false)
        return PhotoTileView(
            item: item,
            side: side,
            develop: develop && !isScrolling && !selecting,
            developDelay: GridDevelopSession.staggerDelay(row: index / DarkroomDimens.gridColumns, col: index % DarkroomDimens.gridColumns),
            selecting: selecting,
            selected: selecting && selected.contains(item.id),
            sizeText: showSize ? FileSizeFormatter.format(AssetSizeCache.shared.cachedBytes(item.id) ?? 0) : nil
        )
        .frame(width: side, height: side)
        .background { if active { frameReader(item.id, space: space) } }
        .background { if let measureSection { mark(section: measureSection, kind: 1, space: space) } }
        .onTapGesture { tap(item) }
        .accessibilityIdentifier("tile-\(index)-\(dayId)")
    }

    private func frameReader(_ id: String, space: String) -> some View {
        GeometryReader { g in
            Color.clear
                .onAppear { registry.frames[id] = g.frame(in: .named(space)) }
                .onChange(of: g.frame(in: .named(space))) { _, f in registry.frames[id] = f }
                .onDisappear { registry.frames[id] = nil }
        }
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
        let hidden = measure && inFlowHidden(day)
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
        .background { if measure { mark(section: day.id, kind: 0, space: "grid-\(scope.rawValue)") } }
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
            guard let y = headerY[id] else { return }
            let top = y - handlers.readOffset()
            let height = headerH[id] ?? DarkroomDimens.dayHeight
            if top < pin || top > viewportH - height {
                handlers.writeOffset(y - pin)
            }
        }
    }

    private func mark(section: String, kind: Int, space: String) -> some View {
        GeometryReader { g in
            Color.clear.preference(
                key: GridMarksKey.self,
                value: [GridMark(scope: scope.rawValue, section: section, kind: kind, y: g.frame(in: .named(space)).minY, height: g.size.height)]
            )
        }
    }

    private func mergeMarks(_ marks: [GridMark]) {
        var headers = headerY
        var heights = headerH
        var lasts = lastItem
        for m in marks where m.scope == scope.rawValue {
            if m.kind == 0 {
                headers[m.section] = m.y
                heights[m.section] = m.height
            } else {
                lasts[m.section] = ItemSpan(y: m.y, height: m.height)
            }
        }
        let alive = Set(pageData(scope).groups.flatMap(\.days).map(\.id))
        if !alive.isEmpty {
            headers = headers.filter { alive.contains($0.key) }
            heights = heights.filter { alive.contains($0.key) }
            lasts = lasts.filter { alive.contains($0.key) }
        }
        if headers != headerY { headerY = headers }
        if heights != headerH { headerH = heights }
        if lasts != lastItem { lastItem = lasts }
    }

    private func inFlowHidden(_ day: PhotoDaySection) -> Bool {
        guard sticks, let y = headerY[day.id] else { return false }
        return DateStick.inFlowDateHidden(headerTop: y - scrollY, pinY: pinY)
    }

    private var sticks: Bool {
        if scope == .onThisDay { return true }
        return scope == .all && viewMode == .dateGrouped
    }

    private func stuckHeader() -> (PhotoDaySection, CGFloat)? {
        guard sticks, kind(scope) == .grid else { return nil }
        let days = pageData(scope).groups.flatMap(\.days).filter { $0.header == .day }
        guard let lastId = days.last?.id else { return nil }
        let pin = pinY
        for i in days.indices {
            let day = days[i]
            guard let contentY = headerY[day.id] else { continue }
            let nextTop: CGFloat? = {
                for j in (i + 1)..<days.count {
                    if let y = headerY[days[j].id] { return y - scrollY }
                }
                return nil
            }()
            let last = day.id == lastId ? lastItem[day.id] : nil
            if let y = DateStick.stickY(
                pinY: pin,
                headerHeight: headerH[day.id] ?? DarkroomDimens.dayHeight,
                headerTop: contentY - scrollY,
                nextHeaderTop: nextTop,
                lastItemTop: last.map { $0.y - scrollY },
                lastItemHeight: last?.height,
                sectionVisible: true
            ) {
                return (day, y)
            }
        }
        return nil
    }

    // MARK: Scrubber

    private var scrubber: some View {
        let days = scrubDays
        let index = scrubIndex ?? currentScrubIndex(days)
        let top = pinY
        let bottom = max(top + DarkroomDimens.scrubberThumbHeight, viewportH - DarkroomDimens.navBottom - DarkroomDimens.navHeight)
        let travel = max(1, bottom - top - DarkroomDimens.scrubberThumbHeight)
        let t = days.count <= 1 ? 0 : CGFloat(index) / CGFloat(days.count - 1)
        let thumbY = top + t * travel
        return HStack {
            Spacer()
            ZStack(alignment: .topTrailing) {
                Capsule()
                    .fill(DarkroomColors.paper)
                    .frame(width: DarkroomDimens.scrubberThumbWidth, height: DarkroomDimens.scrubberThumbHeight)
                    .padding(.trailing, DarkroomDimens.scrubberInset)
                    .offset(y: thumbY)
                if scrubIndex != nil, days.indices.contains(index) {
                    Text(scrubLabel(days[index]))
                        .font(DarkroomType.dayRight)
                        .foregroundStyle(DarkroomColors.paper)
                        .padding(.horizontal, 8)
                        .frame(height: 28)
                        .background(Capsule().fill(DarkroomColors.tray))
                        .offset(x: -(DarkroomDimens.minTouch + DarkroomDimens.scrubberBubbleGap - DarkroomDimens.screenSide), y: thumbY)
                }
            }
            .frame(width: DarkroomDimens.minTouch)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in scrub(to: value.location.y, days: days, top: top, bottom: bottom) }
                    .onEnded { value in
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

    private func currentScrubIndex(_ days: [PhotoDaySection]) -> Int {
        var index = 0
        var before = 0
        for (i, day) in days.enumerated() {
            if scrubAnchor(day, itemsBefore: before) - scrollY <= pinY + 1 { index = i }
            before += day.allItems.count
        }
        return index
    }

    private func scrubAnchor(_ day: PhotoDaySection, itemsBefore: Int) -> CGFloat {
        if viewMode == .dateGrouped {
            if let y = headerY[day.id] { return y }
            return estimatedDateAnchor(day)
        }
        let row = itemsBefore / DarkroomDimens.gridColumns
        let side = tileSide(listWidth)
        return chromeHeight + CGFloat(row) * (side + DarkroomDimens.gridGap)
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
        scrubberShown = true
        let span = max(1, bottom - top)
        let t = min(1, max(0, (y - top) / span))
        let index = min(days.count - 1, Int(floor(t * CGFloat(days.count))))
        scrubIndex = days.count == 1 ? 0 : index
        var before = 0
        for day in days.prefix(index) { before += day.allItems.count }
        let anchor = scrubAnchor(days[index], itemsBefore: before)
        handlers.writeOffset(anchor - pinY)
    }

    private func scrubLabel(_ day: PhotoDaySection) -> String {
        if day.id == DayKeyRule.unknown { return L10n.string("date_unknown", locale: locale) }
        return dayNumber(day.date) + " " + dayMonthWeekday(day.date)
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
                chrome.offset(y: -collapse)
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
                    collapse = 0
                }
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
                headerY = [:]
                headerH = [:]
                lastItem = [:]
            }
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


