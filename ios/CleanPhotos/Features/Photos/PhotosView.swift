import SwiftUI
import UIKit

struct PhotosView: View {
    @EnvironmentObject private var library: PhotoLibraryService
    @EnvironmentObject private var language: LanguageStore
    @Binding var reviewLaunch: ReviewLaunch?
    @Binding var hideTabBar: Bool
    @Binding var dimTabBar: Bool
    var onOpenTrash: () -> Void = {}

    @State private var openMenu: PhotoMenu = .none
    @State private var showLanguageSheet = false
    @State private var initialIDs: Set<String> = []
    @State private var didSeedDevelop = false
    @State private var isScrolling = false
    /// 网格列宽量自列表本身（横屏有左右安全区时比屏宽窄；竖屏与屏宽相同）。
    @State private var listWidth: CGFloat = UIScreen.main.bounds.width

    // Multi-select
    @State private var selecting = false
    @State private var selected: Set<String> = []
    @State private var dragAnchor: String?
    @State private var dragLast: String?
    @State private var dragBase: Set<String> = []
    @State private var suppressTapUntil = Date.distantPast
    @State private var registry = TileFrameRegistry()
    @State private var handlers = DragSelectHandlers()

    // Sort toast
    @State private var toastText: String?
    @State private var toastShown = false
    @State private var toastTask: Task<Void, Never>?

    private var locale: Locale { language.effectiveLocale }
    private var unseenOnly: Bool { library.filterMode == .unseenOnly }
    private var allSeen: Bool {
        !library.isLoading && unseenOnly && library.visibleCount == 0 && library.totalCount > 0
    }

    var body: some View {
        ZStack(alignment: .top) {
            DarkroomColors.room.ignoresSafeArea()

            if allSeen {
                AllSeenView(locale: locale, onTrash: onOpenTrash) {
                    library.filterMode = .allPhotos
                }
            } else {
                list
            }

            VStack(spacing: 0) {
                header
                Spacer()
            }

            if !allSeen {
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
                        // 底边在导航胶囊上方 16pt
                        .padding(.bottom, DarkroomDimens.navBottom + DarkroomDimens.navHeight + 16)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                        .id(banner.id)
                }
                .animation(DarkroomMotion.standard, value: banner.id)
            }

            sortToast

            if openMenu != .none || showLanguageSheet {
                Color(red: 0x0A/255, green: 0x09/255, blue: 0x08/255)
                    .opacity(showLanguageSheet ? 0.64 : 0.48)
                    .ignoresSafeArea()
                    .accessibilityHidden(true)
                    .onTapGesture {
                        withAnimation(DarkroomMotion.menuIn) {
                            openMenu = .none
                            showLanguageSheet = false
                        }
                    }
                    .transition(.opacity)
                // Header stays above the scrim so the inverted button reads as "open"
                VStack(spacing: 0) {
                    if !showLanguageSheet { header }
                    Spacer()
                }
            }

            menus

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
        .environment(\.locale, locale)
        .animation(DarkroomMotion.menuIn, value: openMenu)
        .animation(DarkroomMotion.menuIn, value: selecting)
        .task {
            if library.sections.isEmpty { await library.reload() }
            seedDevelopIfNeeded()
        }
        .onChange(of: library.sections) { _, _ in
            seedDevelopIfNeeded()
            pruneSelection()
        }
        .onChange(of: selecting) { _, on in
            hideTabBar = on || showLanguageSheet
            if on { openMenu = .none }
        }
        .onChange(of: showLanguageSheet) { _, on in hideTabBar = on || selecting }
        .onChange(of: openMenu) { _, m in dimTabBar = m != .none }
        .onChange(of: library.sortOrder) { _, order in
            showToast(L10n.string(order == .oldestFirst ? "sort_oldest" : "sort_newest", locale: locale))
        }
        .onReceive(NotificationCenter.default.publisher(for: .cleanphotosEnterMultiselect)) { _ in
            let ids = Array(library.visiblePhotos.prefix(4).map(\.id))
            guard !ids.isEmpty else { return }
            selecting = true
            selected = Set(ids)
        }
        .onReceive(NotificationCenter.default.publisher(for: .cleanphotosDragSelect)) { note in
            simulateDragSelect(steps: (note.object as? Int) ?? 6)
        }
        .onReceive(NotificationCenter.default.publisher(for: .cleanphotosHarnessMenu)) { note in
            let m: PhotoMenu
            switch note.object as? String {
            case "sort": m = .sort
            case "filter": m = .filter
            case "more": m = .more
            default: m = .none
            }
            if m == .none { exitSelection() }
            withAnimation(DarkroomMotion.menuIn) { openMenu = m }
        }
    }

    // MARK: List

    private var list: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                Color.clear.frame(height: DarkroomDimens.headerHeight + 8)
                    .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { w in if w > 0 { listWidth = w } }
                ForEach(library.sections) { year in
                    if year.year > 0 { yearRow(year.year) }
                    ForEach(year.days) { day in
                        dayHeader(day)
                        dayGrid(day)
                    }
                }
                if library.isLoading && library.sections.isEmpty { loadingSkeleton }
                Color.clear.frame(height: DarkroomDimens.listFade + 48)
            }
            .coordinateSpace(name: "photoGrid")
            .background(DragSelectProbe(handlers: configuredHandlers()))
        }
        .scrollIndicators(.hidden)
        .simultaneousGesture(
            DragGesture(minimumDistance: 8)
                .onChanged { _ in
                    if dragAnchor == nil { isScrolling = true }
                }
                .onEnded { _ in
                    if dragAnchor == nil {
                        isScrolling = false
                        GridDevelopSession.markPlayed(.photos)
                    }
                }
        )
    }

    // MARK: Drag select

    private func configuredHandlers() -> DragSelectHandlers {
        let h = handlers
        h.topInset = DarkroomDimens.headerHeight
        h.onBegin = { point in beginDrag(at: point) }
        h.onMove = { point in moveDrag(to: point) }
        h.onEnd = { endDrag() }
        return h
    }

    private var visibleIds: Set<String> {
        Set(library.sections.flatMap { $0.days.flatMap { $0.items.map(\.id) } })
    }

    @discardableResult
    private func beginDrag(at point: CGPoint) -> Bool {
        guard !library.isLoading, openMenu == .none, !showLanguageSheet else { return false }
        guard let id = registry.id(at: point), visibleIds.contains(id) else { return false }
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
        // visiblePhotos 含每天 8 张以后没画出来的照片。用它做闭区间会把手指没经过的也选上。
        let order = library.sections.flatMap { $0.days.flatMap(\.items) }
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

    /// Screenshot harness: run the same begin/move path the gesture uses, across `steps` tiles.
    private func simulateDragSelect(steps: Int) {
        let ids = library.visiblePhotos.prefix(max(1, steps)).map(\.id)
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
        let keep = selected.intersection(visibleIds)
        if keep != selected { selected = keep }
        if selected.isEmpty && dragAnchor == nil { selecting = false }
    }

    // MARK: Header

    @ViewBuilder
    private var header: some View {
        if selecting {
            HStack(alignment: .bottom) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(L10n.string("eyebrow_selected", locale: locale))
                        .font(DarkroomType.eyebrow)
                        .tracking(1.44)
                        .foregroundStyle(DarkroomColors.dim)
                    HStack(alignment: .firstTextBaseline, spacing: 4) {
                        OdometerText(value: Int64(selected.count), text: "\(selected.count)", font: DarkroomType.countBig.monospacedDigit(), color: DarkroomColors.paper, tracking: -0.6)
                        if language.isChineseUI {
                            Text(L10n.string("header_count_unit", locale: locale))
                                .font(DarkroomType.countUnit)
                                .foregroundStyle(DarkroomColors.dim)
                        }
                    }
                }
                Spacer()
                ToolIconButton(systemName: "xmark") { exitSelection() }
                    .accessibilityLabel(Text(L10n.string("cd_exit_selection", locale: locale)))
                    .accessibilityIdentifier("select-exit")
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 12)
            .frame(height: DarkroomDimens.headerHeight, alignment: .bottom)
            .background(DarkroomColors.room.opacity(0.92))
        } else {
            PhotoHeaderBar(
                eyebrow: L10n.string(unseenOnly ? "eyebrow_photos_unseen" : "tab_photos", locale: locale),
                count: unseenOnly ? library.visibleCount : library.totalCount,
                sub: unseenOnly
                    ? L10n.format("header_sub_total", locale: locale, formatted(library.totalCount))
                    : L10n.format("header_sub_unseen", locale: locale, formatted(library.unseenCount)),
                locale: locale,
                isChinese: language.isChineseUI,
                openMenu: openMenu,
                sortOldest: library.sortOrder == .oldestFirst,
                unseenOnly: unseenOnly,
                loading: library.isLoading && (library.sections.isEmpty || library.isPartial),
                skeleton: library.isLoading && library.sections.isEmpty,
                onSort: { toggleMenu(.sort) },
                onFilter: { toggleMenu(.filter) },
                onMore: { toggleMenu(.more) }
            )
        }
    }

    private func toggleMenu(_ m: PhotoMenu) {
        withAnimation(DarkroomMotion.menuIn) {
            openMenu = openMenu == m ? .none : m
            showLanguageSheet = false
        }
    }

    @ViewBuilder
    private var menus: some View {
        Group {
            switch openMenu {
            case .sort:
                DarkroomMenuView(header: L10n.string("menu_sort_header", locale: locale), options: [
                    DarkroomMenuOption(label: L10n.string("sort_newest", locale: locale), trailing: L10n.string("sort_default", locale: locale), selected: library.sortOrder == .newestFirst) {
                        library.sortOrder = .newestFirst; closeMenu()
                    },
                    DarkroomMenuOption(label: L10n.string("sort_oldest", locale: locale), trailing: nil, selected: library.sortOrder == .oldestFirst) {
                        library.sortOrder = .oldestFirst; closeMenu()
                    },
                ])
            case .filter:
                DarkroomMenuView(header: L10n.string("menu_filter_header", locale: locale), options: [
                    DarkroomMenuOption(label: L10n.string("filter_all", locale: locale), trailing: formatted(library.totalCount), selected: !unseenOnly) {
                        library.filterMode = .allPhotos; closeMenu()
                    },
                    DarkroomMenuOption(label: L10n.string("filter_unseen", locale: locale), trailing: formatted(library.unseenCount), selected: unseenOnly) {
                        library.filterMode = .unseenOnly; closeMenu()
                    },
                ])
            case .more:
                DarkroomMenuView(header: L10n.string("menu_more_header", locale: locale), options: moreOptions)
            case .none:
                EmptyView()
            }
        }
        // 距状态栏下 80pt、右 16pt
        .padding(.top, 80)
        .padding(.trailing, DarkroomDimens.screenSide)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
    }

    private var moreOptions: [DarkroomMenuOption] {
        var options = [
            DarkroomMenuOption(label: L10n.string("menu_language", locale: locale), trailing: languageLabel, selected: false) {
                withAnimation(DarkroomMotion.menuIn) {
                    openMenu = .none
                    showLanguageSheet = true
                }
            },
        ]
        // 「部分照片」访问：系统不再自动弹「选择更多照片」（Info.plist PHPhotoLibraryPreventAutomaticLimitedAccessAlert），入口放这里
        if library.authorization == .limited {
            options.append(DarkroomMenuOption(label: L10n.string("menu_manage_selection", locale: locale), trailing: nil, selected: false) {
                closeMenu()
                library.presentLimitedLibraryPicker()
            })
        }
        return options
    }

    private func closeMenu() {
        withAnimation(DarkroomMotion.menuIn) { openMenu = .none }
    }

    // MARK: Sort toast

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

    // MARK: Selection bar

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
        language.preference.fixedTitle(
            followSystemLocalized: L10n.string("language_follow_system", locale: locale)
        )
    }

    /// 误触保护（Android scrollSettleIgnore）：列表在拖 / 在惯性滑、或刚停下不到 300ms 时不响应点按。
    /// 状态直接读 UIScrollView（isDragging / isDecelerating + 最后一次滚动的时间）：SwiftUI 的 DragGesture 被滚动视图接管后
    /// 常常收不到 onEnded，而且手指抬起后惯性还在滑，按抬手时间算会放过甩动中的误触。
    private func tapsAllowed() -> Bool {
        if handlers.listMoving() { return false }
        return CACurrentMediaTime() - handlers.lastScrollTime() >= DarkroomMotion.scrollSettleIgnore
    }

    private func formatted(_ n: Int) -> String {
        let f = NumberFormatter(); f.numberStyle = .decimal; f.locale = locale
        return f.string(from: NSNumber(value: n)) ?? "\(n)"
    }

    // MARK: List pieces

    private func yearRow(_ year: Int) -> some View {
        HStack(spacing: 12) {
            Text(String(year)).font(DarkroomType.countSub).tracking(0.88).foregroundStyle(DarkroomColors.dim)
            Rectangle().fill(DarkroomColors.rim).frame(height: 1)
        }
        .frame(height: DarkroomDimens.yearHeight)
        .padding(.horizontal, DarkroomDimens.screenSide)
    }

    private func dayHeader(_ day: PhotoDaySection) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text(day.id == DayKeyRule.unknown ? "—" : dayNumber(day.date)).font(DarkroomType.dayNumber).tracking(-0.24).monospacedDigit().foregroundStyle(DarkroomColors.paper)
            Text(day.id == DayKeyRule.unknown ? L10n.string("date_unknown", locale: locale) : dayMonthWeekday(day.date)).font(DarkroomType.dayMeta).foregroundStyle(DarkroomColors.dim)
            Spacer()
            if selecting {
                let ids = Set(day.allItems.map(\.id))
                let allOn = !ids.isEmpty && ids.isSubset(of: selected)
                Button {
                    if allOn { selected.subtract(ids) } else { selected.formUnion(ids) }
                    if selected.isEmpty { selecting = false }
                } label: {
                    Text(L10n.string(allOn ? "deselect_all" : "select_all", locale: locale))
                        .font(DarkroomType.dayRight)
                        .fontWeight(.medium)
                        .foregroundStyle(DarkroomColors.paper)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            } else {
                HStack(spacing: 4) {
                    if day.isDone {
                        Text(L10n.format("section_done", locale: locale, day.totalCount))
                            .font(DarkroomType.dayRight).foregroundStyle(DarkroomColors.dim)
                    } else {
                        (Text("\(day.unseenCount)").font(DarkroomType.dayRight).fontWeight(.medium).foregroundStyle(DarkroomColors.paper)
                         + Text(L10n.format("section_unseen", locale: locale, day.totalCount))
                            .font(DarkroomType.dayRight).foregroundStyle(DarkroomColors.dim))
                    }
                    Image(systemName: "chevron.right").font(.system(size: 12, weight: .medium)).foregroundStyle(DarkroomColors.dim)
                }
                .contentShape(Rectangle())
                .accessibilityElement(children: .combine)
                .accessibilityHint(Text(L10n.string("cd_enter_day", locale: locale)))
                .accessibilityAddTraits(.isButton)
                .onTapGesture {
                    guard tapsAllowed() else { return }
                    if let first = day.allItems.first {
                        reviewLaunch = ReviewLaunch(scope: .allPhotos, startPhotoId: first.id, dayId: day.id)
                    }
                }
            }
        }
        .padding(.horizontal, DarkroomDimens.screenSide)
        .padding(.top, DarkroomDimens.dayTop)
        .padding(.bottom, DarkroomDimens.dayBottom)
    }

    private func dayGrid(_ day: PhotoDaySection) -> some View {
        let cols = DarkroomDimens.gridColumns
        let gap = DarkroomDimens.gridGap
        return GeometryReader { geo in
            let side = (geo.size.width - gap * CGFloat(cols - 1)) / CGFloat(cols)
            LazyVGrid(columns: Array(repeating: GridItem(.fixed(side), spacing: gap), count: cols), spacing: gap) {
                ForEach(Array(day.items.enumerated()), id: \.element.id) { index, item in
                    let row = index / cols
                    let col = index % cols
                    let develop = initialIDs.contains(item.id)
                        && GridDevelopSession.shouldDevelop(grid: .photos, id: item.id, isInitialPass: false)
                    PhotoTileView(
                        item: item,
                        side: side,
                        develop: develop && !isScrolling && !selecting,
                        developDelay: GridDevelopSession.staggerDelay(row: row, col: col),
                        selecting: selecting,
                        selected: selecting && selected.contains(item.id)
                    )
                    .background(
                        GeometryReader { g in
                            Color.clear
                                .onAppear { registry.frames[item.id] = g.frame(in: .named("photoGrid")) }
                                .onChange(of: g.frame(in: .named("photoGrid"))) { _, f in registry.frames[item.id] = f }
                                .onDisappear { registry.frames[item.id] = nil }
                        }
                    )
                    .onTapGesture { tap(item) }
                    .accessibilityIdentifier("tile-\(index)-\(day.id)")
                }
            }
        }
        .frame(height: gridHeight(count: day.items.count))
    }

    private func tap(_ item: PhotoItem) {
        guard Date() >= suppressTapUntil else { return }
        if selecting {
            if selected.contains(item.id) { selected.remove(item.id) } else { selected.insert(item.id) }
            if selected.isEmpty { selecting = false }
        } else {
            guard tapsAllowed() else { return }
            reviewLaunch = ReviewLaunch(scope: .allPhotos, startPhotoId: item.id, dayId: nil)
        }
    }

    private func gridHeight(count: Int) -> CGFloat {
        let cols = CGFloat(DarkroomDimens.gridColumns)
        let rows = ceil(CGFloat(count) / cols)
        let w = listWidth
        let side = (w - DarkroomDimens.gridGap * (cols - 1)) / cols
        return rows * side + max(0, rows - 1) * DarkroomDimens.gridGap
    }

    /// Android LoadingSkeleton：三组呼吸格。
    private var loadingSkeleton: some View { LoadingSkeletonGrid() }

    private func seedDevelopIfNeeded() {
        guard !didSeedDevelop else { return }
        var ids: Set<String> = []
        var count = 0
        outer: for year in library.sections {
            for day in year.days {
                for item in day.items {
                    ids.insert(item.id); count += 1
                    if count >= 24 { break outer }
                }
            }
        }
        guard !ids.isEmpty else { return }
        initialIDs = ids
        didSeedDevelop = true
        for id in ids { _ = GridDevelopSession.shouldDevelop(grid: .photos, id: id, isInitialPass: true) }
    }

    private func dayNumber(_ date: Date) -> String {
        DateFormatCache.string(date, format: "d", locale: locale)
    }
    private func dayMonthWeekday(_ date: Date) -> String {
        DateFormatCache.string(date, format: language.isChineseUI ? "M月 · EEE" : "MMM · EEE", locale: locale)
    }
}

/// 全部看过了（ui-design 3.13 / Android AllSeenContent，「仅未看过」筛到 0 张）：
/// 晾绳 + 文案 + 收藏 N / 垃圾桶 N 张 / 待释放 X + 「看看垃圾桶」（带待清空大小）+ 「显示全部照片」。
struct AllSeenView: View {
    @EnvironmentObject private var library: PhotoLibraryService
    var locale: Locale
    var onTrash: () -> Void
    var onShowAll: () -> Void

    var body: some View {
        EmptyStateArea {
            ClotheslineArt(photoIds: library.hangPhotoIds)
                .padding(.bottom, 24)
            EmptyTitleAndBody(
                L10n.string("all_seen_title", locale: locale),
                L10n.pluralFormat("all_seen_body", count: library.totalCount, locale: locale, NumberFormatter.localizedString(from: NSNumber(value: library.totalCount), number: .decimal))
            )
            let parts = TrashView.splitBytes(library.trashBytes, locale: locale)
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
                Text(L10n.string("show_all_photos", locale: locale))
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
