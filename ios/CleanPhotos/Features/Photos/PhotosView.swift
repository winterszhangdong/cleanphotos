import SwiftUI
import UIKit

enum PhotosViewMode: Equatable { case dateGrouped, immersive }

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
    @State private var unseenOnly = false
    @State private var viewMode: PhotosViewMode = .dateGrouped
    @State private var expandedDayId: String?
    @State private var menuOpen = false
    @State private var showLanguageSheet = false
    @State private var canScroll = false
    @State private var viewportH: CGFloat = UIScreen.main.bounds.height
    @State private var listWidth: CGFloat = UIScreen.main.bounds.width

    @State private var overlay = ScrollChromeDriver()
    @State private var pager = PhotosPagerController()
    @State private var scrubIndex: Int?
    @State private var similarCompare: SimilarCompareRoute?

    @State private var initialIDs: Set<String> = []
    @State private var didSeedDevelop = false

    @State private var selecting = false
    @State private var selected: Set<String> = []

    @State private var toastText: String?
    @State private var toastShown = false
    @State private var toastTask: Task<Void, Never>?

    private var locale: Locale { language.effectiveLocale }
    private var chinese: Bool { language.isChineseUI }
    private var chromeHeight: CGFloat { DarkroomDimens.photosHeaderHeight + DarkroomDimens.scopeRowHeight }
    private var scopes: [LibraryScope] { ScopeRules.visibleScopes(onThisDayCount: library.onThisDayCount, current: scope) }

    var body: some View {
        ZStack(alignment: .top) {
            DarkroomColors.room.ignoresSafeArea()

            PhotosScopePagerView(
                controller: pager,
                props: pagerProps,
                callbacks: pagerCallbacks
            )
            .ignoresSafeArea(.container, edges: .bottom)

            if kind(scope) == .grid {
                listFade
                    .zIndex(1)
            }

            if scope == .all && canScroll && kind(scope) == .grid {
                scrubber
                    .zIndex(2)
            }

            if menuOpen && !showLanguageSheet {
                Color(red: 0x0A/255, green: 0x09/255, blue: 0x08/255)
                    .opacity(0.48)
                    .ignoresSafeArea()
                    .accessibilityHidden(true)
                    .onTapGesture {
                        withAnimation(DarkroomMotion.menuIn) { menuOpen = false }
                    }
                    .zIndex(2.5)
            }

            chrome
                .background { OverlayAnchor(driver: overlay, slot: .chrome) }
                .zIndex(3)

            overlays
        }
        .background {
            GeometryReader { g in
                Color.clear
                    .onAppear { adoptSafeBounds(g.size) }
                    .onChange(of: g.size) { _, size in adoptSafeBounds(size) }
            }
        }
        .environment(\.locale, locale)
        .animation(DarkroomMotion.menuIn, value: menuOpen)
        .animation(DarkroomMotion.menuIn, value: selecting)
        .task {
            library.setPage(scope: scope, unseenOnly: unseenOnly)
            if library.sections.isEmpty { await library.reload() }
            if scope == .similar { SimilarSession.shared.start(library) }
            seedDevelopIfNeeded()
        }
        .onChange(of: scrubIndex) { _, index in
            overlay.scrubLockIndex = index
            pager.setScrubActive(index != nil)
        }
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
            if !shown { leavePhotosPage() }
        }
        .onReceive(NotificationCenter.default.publisher(for: .cleanphotosDragSelect)) { note in
            pager.simulateDragSelect(steps: (note.object as? Int) ?? 6)
        }
        .onReceive(NotificationCenter.default.publisher(for: .cleanphotosHarnessMenu)) { note in
            switch note.object as? String {
            case "language":
                guard AuditHooks.enabled else { return }
                exitSelection()
                withAnimation(DarkroomMotion.menuIn) {
                    menuOpen = false
                    showLanguageSheet = true
                }
                overlay.setCollapse(0, animated: false)
            case "sort", "filter", "more":
                withAnimation(DarkroomMotion.menuIn) { menuOpen = true }
                overlay.setCollapse(0, animated: false)
            default:
                exitSelection()
                withAnimation(DarkroomMotion.menuIn) {
                    menuOpen = false
                    showLanguageSheet = false
                }
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: .cleanphotosHarnessScope)) { note in
            guard let raw = note.object as? String else { return }
            menuOpen = false
            exitSelection()
            if raw == "immersive" {
                pager.jumpToScope(.all)
                selectView(.immersive)
                return
            }
            guard let next = LibraryScope(rawValue: raw) else { return }
            if next == .onThisDay && !scopes.contains(.onThisDay) {
                scope = .onThisDay
                library.setPage(scope: .onThisDay, unseenOnly: unseenOnly)
            } else {
                pager.jumpToScope(next)
            }
        }
    }

    private func adoptSafeBounds(_ size: CGSize) {
        if size.width > 0 { listWidth = size.width }
        if size.height > 0 {
            overlay.viewportH = size.height
            if abs(size.height - viewportH) > 0.5 { viewportH = size.height }
        }
    }

    // MARK: Pager Props & Callbacks

    private var pagerProps: PhotosPagerController.Props {
        PhotosPagerController.Props(
            scopes: scopes,
            currentScope: scope,
            pages: library.pages,
            immersive: viewMode == .immersive,
            expandedDayId: expandedDayId,
            isLoading: library.isLoading,
            isPartial: library.isPartial,
            sizesReady: library.sizesReady,
            totalCount: library.totalCount,
            unseenOnly: unseenOnly,
            initialIDs: initialIDs,
            selecting: selecting,
            selected: selected,
            menuOpen: menuOpen,
            showLanguageSheet: showLanguageSheet,
            photosVisible: photosVisible,
            scrubActive: scrubIndex != nil,
            reduceMotion: reduceMotion,
            chinese: chinese,
            locale: locale,
            chromeHeight: chromeHeight,
            library: library,
            overlay: overlay
        )
    }

    private var pagerCallbacks: PhotosPagerController.Callbacks {
        PhotosPagerController.Callbacks(
            onScopeChanged: { nextScope in
                if scope != nextScope {
                    scope = nextScope
                    library.setPage(scope: nextScope, unseenOnly: unseenOnly)
                }
            },
            onCanScrollChanged: { can in
                if canScroll != can { canScroll = can }
            },
            onTapPhoto: { item in
                tap(item)
            },
            onOpenDay: { day in
                openDay(day)
            },
            onCollapseDay: { dayId in
                pager.applyCollapseDay(dayId, viewportH: viewportH) {
                    expandedDayId = nil
                }
            },
            onExpandDay: { dayId in
                expandedDayId = dayId
            },
            onToggleSelectDay: { day in
                let ids = Set(day.allItems.map(\.id))
                let allOn = !ids.isEmpty && ids.isSubset(of: selected)
                if allOn { selected.subtract(ids) } else { selected.formUnion(ids) }
                if selected.isEmpty { selecting = false }
            },
            onSelectionChanged: { nextSelected, enterSelecting in
                selected = nextSelected
                if selecting != enterSelecting {
                    selecting = enterSelecting
                } else if nextSelected.isEmpty && !enterSelecting {
                    selecting = false
                }
            },
            onShowAllSeen: {
                setUnseen(false)
            },
            onSelectAllScope: {
                if let idx = scopes.firstIndex(of: .all) {
                    pager.tapScope(idx)
                }
            },
            onOpenTrash: onOpenTrash,
            onOpenSimilarPhoto: { groupKey, photoId in
                similarCompare = SimilarCompareRoute(groupKey: groupKey, photoId: photoId)
            }
        )
    }

    // MARK: Chrome

    private var chrome: some View {
        VStack(spacing: 0) {
            if scope == .similar && !selecting {
                SimilarPhotosHeaderBar(
                    chinese: chinese,
                    locale: locale,
                    menuOpen: menuOpen,
                    pip: unseenOnly,
                    onMore: toggleMenu,
                    onClose: exitSelection
                )
            } else {
                PhotoHeaderBar(
                    count: headerCount,
                    unit: unitText,
                    sub: headerSub,
                    locale: locale,
                    skeleton: headerSkeleton,
                    loadingText: nil,
                    menuOpen: menuOpen,
                    pip: unseenOnly,
                    selecting: selecting,
                    onMore: toggleMenu,
                    onClose: exitSelection
                )
            }
            ScopeDialView(
                scopes: scopes,
                chinese: chinese,
                interactionEnabled: !selecting && !menuOpen && !showLanguageSheet && scrubIndex == nil,
                onAttach: { dial in pager.attachDial(dial) },
                onSelect: { idx in pager.tapScope(idx) },
                onDrag: { dx, stride, ended, vx in pager.dialDrag(dx: dx, stride: stride, ended: ended, velocityX: vx) }
            )
            .frame(height: DarkroomDimens.scopeRowHeight)
            .opacity(menuOpen ? 0.52 : 1)
        }
    }

    private var headerCount: Int {
        selecting ? selected.count : library.visibleCount
    }

    private var unitText: String {
        if chinese { return L10n.string("header_count_unit", locale: locale) }
        return PhotoPageCopy.unit(count: headerCount, chinese: false)
    }

    private var headerSub: String? {
        if selecting { return PhotoPageCopy.subSelected(chinese: chinese) }
        if headerSkeleton { return nil }
        guard scope.showsByteTotal else { return nil }
        return PhotoPageCopy.subBytes(FileSizeFormatter.format(library.visibleBytes))
    }

    private var headerSkeleton: Bool {
        if selecting { return false }
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

    // MARK: Scope helpers

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

    private func setUnseen(_ on: Bool) {
        unseenOnly = on
        library.setPage(scope: scope, unseenOnly: on)
        overlay.setCollapse(0, animated: false)
        closeMenu()
    }

    // MARK: Actions

    private func openDay(_ day: PhotoDaySection) {
        guard let first = day.allItems.first else { return }
        reviewLaunch = ReviewLaunch(scope: .allPhotos, startPhotoId: first.id, dayId: day.id)
    }

    private func tap(_ item: PhotoItem) {
        guard scope != .similar else { return }
        if selecting {
            if selected.contains(item.id) { selected.remove(item.id) } else { selected.insert(item.id) }
            if selected.isEmpty { selecting = false }
        } else {
            reviewLaunch = ReviewLaunch(scope: .allPhotos, startPhotoId: item.id, dayId: nil)
        }
    }

    private func exitSelection() {
        selecting = false
        selected = []
    }

    private func pruneSelection() {
        guard selecting else { return }
        let page = pageData(scope)
        let scopeIds: Set<String>
        if scope == .largeFiles || (scope == .all && viewMode == .immersive) {
            scopeIds = Set(page.flat.map(\.id))
        } else {
            scopeIds = Set(page.groups.flatMap { $0.days.flatMap { $0.allItems.map(\.id) } })
        }
        let keep = SelectionPrune.remaining(selected: selected, scope: scopeIds)
        if keep != selected { selected = keep }
        if selected.isEmpty { selecting = false }
    }

    private func seedDevelopIfNeeded() {
        guard !didSeedDevelop else { return }
        let page = pageData(scope)
        let items: [PhotoItem]
        if scope == .largeFiles || (scope == .all && viewMode == .immersive) {
            items = Array(page.flat.prefix(32))
        } else {
            items = Array(page.groups.flatMap { $0.days.flatMap(\.items) }.prefix(32))
        }
        guard !items.isEmpty else { return }
        let ids = Set(items.map(\.id))
        for id in ids { GridDevelopSession.seed(grid: .photos, id: id) }
        initialIDs = ids
        didSeedDevelop = true
    }

    // MARK: Scrubber

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
                        }
                )
        }
    }

    private var scrubDays: [PhotoDaySection] {
        pageData(.all).groups.flatMap(\.days).filter { $0.header == .day }
    }

    private func scrub(to y: CGFloat, days: [PhotoDaySection], top: CGFloat, bottom: CGFloat) {
        guard !days.isEmpty else { return }
        let span = max(1, bottom - top)
        let t = min(1, max(0, (y - top) / span))
        let index = days.count == 1 ? 0 : min(days.count - 1, Int(floor(t * CGFloat(days.count))))
        overlay.scrubLockIndex = index
        scrubIndex = index
        pager.setScrubActive(true)
        pager.scrubTo(allDayIndex: index, dayId: days[index].id, pinTop: top)
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

    // MARK: Leave & Restore

    private func leavePhotosPage() {
        let metrics = gridMetrics()
        let allOffset = allRestoreOffset(metrics: metrics)
        let todayOffset = todayRestoreOffset(metrics: metrics)
        pager.prepareLeave(metrics: metrics, allOffset: allOffset, todayOffset: todayOffset)
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) {
            viewMode = .dateGrouped
            expandedDayId = nil
            menuOpen = false
            showLanguageSheet = false
        }
        overlay.setCollapse(0, animated: false)
        dimTabBar = false
        if !selecting { hideTabBar = false }
    }

    private func gridMetrics() -> PhotosLeave.Metrics {
        let cols = CGFloat(DarkroomDimens.gridColumns)
        let gap = DarkroomDimens.gridGap
        let side = (max(listWidth, 1) - gap * (cols - 1)) / cols
        return PhotosLeave.Metrics(
            chrome: chromeHeight,
            year: DarkroomDimens.yearHeight,
            header: DarkroomDimens.dayHeight,
            more: DarkroomDimens.minTouch,
            tilePitch: side + gap,
            columns: DarkroomDimens.gridColumns
        )
    }

    private func leaveSections(_ s: LibraryScope) -> [PhotosGridSection] {
        pageData(s).groups.flatMap { year in
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

    private func contentLine(for s: LibraryScope) -> CGFloat {
        let pin = s == scope ? overlay.pinY : chromeHeight
        return pager.currentScrollY(for: s) + pin
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
            .zIndex(3.2)
        }
        if scope == .similar {
            SimilarKeptUndoOverlay(chinese: chinese)
                .zIndex(3.2)
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
            .zIndex(3.2)
        }
        sortToast
            .zIndex(3.5)
        if menuOpen {
            menus
                .zIndex(4)
        }
        if showLanguageSheet {
            Color(red: 0x0A/255, green: 0x09/255, blue: 0x08/255)
                .opacity(0.64)
                .ignoresSafeArea()
                .accessibilityHidden(true)
                .onTapGesture {
                    withAnimation(DarkroomMotion.menuIn) { showLanguageSheet = false }
                }
                .zIndex(5)
            VStack(spacing: 0) {
                Spacer(minLength: 0)
                LanguageSheetView(selection: language.preference) { pref in
                    if pref != language.preference { language.select(pref) }
                    withAnimation(DarkroomMotion.menuIn) { showLanguageSheet = false }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
            .transition(.move(edge: .bottom).combined(with: .opacity))
            .zIndex(6)
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
        if mode != viewMode {
            let pin = overlay.pinY
            let anchorId = pager.topVisiblePhotoIdOnAll(below: pin)
            pager.applyViewModeSwitch(anchorPhotoId: anchorId, pinY: pin) {
                var t = Transaction(); t.disablesAnimations = true
                withTransaction(t) {
                    viewMode = mode
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
                    DarkroomIconView(icon: .starSolid, size: 16)
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
                    DarkroomIconView(icon: .trash, size: 16)
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
        .background { DarkroomGlassCapsuleBackground() }
        .opacity(selected.isEmpty ? 0.5 : 1)
        .disabled(selected.isEmpty)
    }

    private var languageLabel: String {
        language.preference.fixedTitle(followSystemLocalized: L10n.string("language_follow_system", locale: locale))
    }
}

/// 隔离观察 `SimilarSession.shared`，避免扫描期间高频 `done += 1` 触发整个 `PhotosView` 与网格重算。
private struct SimilarPhotosHeaderBar: View {
    @ObservedObject private var similarSession = SimilarSession.shared
    var chinese: Bool
    var locale: Locale
    var menuOpen: Bool
    var pip: Bool
    var onMore: () -> Void
    var onClose: () -> Void

    private var count: Int {
        similarSession.phase == .ready ? similarSession.listedGroups.count : 0
    }

    private var unit: String {
        if similarSession.phase == .ready {
            return SimilarCopy.groupUnit(count: count, zh: chinese)
        }
        return chinese ? L10n.string("header_count_unit", locale: locale) : PhotoPageCopy.unit(count: 0, chinese: false)
    }

    private var removableCount: Int {
        similarSession.listedGroups.reduce(0) { sum, card in
            let map = similarSession.marks[card.key] ?? [:]
            return sum + card.photos.filter { map[$0.id] == .remove }.count
        }
    }

    private var sub: String? {
        guard similarSession.phase == .ready else { return nil }
        return SimilarCopy.canMove(removableCount, zh: chinese)
    }

    private var loadingText: String? {
        guard similarSession.phase != .ready, similarSession.total > 0 else { return nil }
        return SimilarCopy.comparing(done: similarSession.done, total: similarSession.total, zh: chinese)
    }

    var body: some View {
        PhotoHeaderBar(
            count: count,
            unit: unit,
            sub: sub,
            locale: locale,
            skeleton: similarSession.phase != .ready,
            loadingText: loadingText,
            menuOpen: menuOpen,
            pip: pip,
            selecting: false,
            onMore: onMore,
            onClose: onClose
        )
    }
}

/// 隔离观察 `SimilarSession.shared.keptUndo`，仅相似范围且出现「已留下」撤回条时挂在底部。
private struct SimilarKeptUndoOverlay: View {
    @ObservedObject private var similarSession = SimilarSession.shared
    var chinese: Bool

    var body: some View {
        if let undo = similarSession.keptUndo {
            VStack {
                Spacer()
                KeptUndoCapsule(undo: undo, zh: chinese) { similarSession.undoKept() }
                    .padding(.horizontal, 16)
                    .padding(.bottom, DarkroomDimens.navBottom + DarkroomDimens.navHeight + 16)
                    .task(id: undo.id) {
                        try? await Task.sleep(nanoseconds: UInt64(UndoBanner.lifetime * 1_000_000_000))
                        similarSession.expireKept(undo.id)
                    }
            }
        }
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
