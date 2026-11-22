import Photos
import QuartzCore
import UIKit

/// 照片页自定义 UICollectionViewLayout：直接复用 `PhotosGridGeometry.BuiltLayout`，
/// 垂直滚动不失效布局，`layoutAttributesForElements(in:)` 用 O(log S) 二分裁切并按行裁剪大图 flat/immersive 分区。
final class PhotosGridLayout: UICollectionViewLayout {
    var built = PhotosGridGeometry.build(
        width: UIScreen.main.bounds.width,
        chromeHeight: DarkroomDimens.photosHeaderHeight + DarkroomDimens.scopeRowHeight,
        bottomReserve: DarkroomDimens.listFade + 48,
        yearHeight: DarkroomDimens.yearHeight,
        headerHeight: DarkroomDimens.dayHeight,
        moreHeight: DarkroomDimens.minTouch,
        gridGap: DarkroomDimens.gridGap,
        columns: DarkroomDimens.gridColumns,
        specs: []
    )

    override var collectionViewContentSize: CGSize {
        CGSize(width: built.width, height: built.totalHeight)
    }

    override func shouldInvalidateLayout(forBoundsChange newBounds: CGRect) -> Bool {
        abs(newBounds.width - built.width) > 0.5
    }

    override func layoutAttributesForElements(in rect: CGRect) -> [UICollectionViewLayoutAttributes]? {
        let range = PhotosGridGeometry.sliceRange(in: built.slices, minY: rect.minY, maxY: rect.maxY)
        guard !range.isEmpty else { return nil }
        var attrs: [UICollectionViewLayoutAttributes] = []
        attrs.reserveCapacity(36)
        let cols = DarkroomDimens.gridColumns
        let gap = DarkroomDimens.gridGap
        let side = built.tileSide
        let pitch = built.rowPitch

        for sIdx in range {
            let slice = built.slices[sIdx]
            switch slice.kind {
            case .year, .dayHeader, .monthHeader, .more:
                let attr = UICollectionViewLayoutAttributes(forCellWith: IndexPath(item: 0, section: sIdx))
                attr.frame = CGRect(x: 0, y: slice.yStart, width: built.width, height: slice.height)
                attrs.append(attr)
            case .tiles(let count, _):
                guard count > 0, pitch > 0 else { continue }
                let totalRows = (count + cols - 1) / cols
                let minRow = max(0, Int(floor((rect.minY - slice.yStart) / pitch)))
                let maxRow = min(totalRows - 1, Int(floor((rect.maxY - slice.yStart) / pitch)))
                guard minRow <= maxRow else { continue }
                for row in minRow...maxRow {
                    let rowStart = row * cols
                    let rowEnd = min(count, rowStart + cols)
                    let y = slice.yStart + CGFloat(row) * pitch
                    for itemIndex in rowStart..<rowEnd {
                        let col = itemIndex - rowStart
                        let attr = UICollectionViewLayoutAttributes(forCellWith: IndexPath(item: itemIndex, section: sIdx))
                        attr.frame = CGRect(
                            x: CGFloat(col) * (side + gap),
                            y: y,
                            width: side,
                            height: side
                        )
                        attrs.append(attr)
                    }
                }
            }
        }
        return attrs
    }

    override func layoutAttributesForItem(at indexPath: IndexPath) -> UICollectionViewLayoutAttributes? {
        guard built.slices.indices.contains(indexPath.section) else { return nil }
        let slice = built.slices[indexPath.section]
        switch slice.kind {
        case .year, .dayHeader, .monthHeader, .more:
            guard indexPath.item == 0 else { return nil }
            let attr = UICollectionViewLayoutAttributes(forCellWith: indexPath)
            attr.frame = CGRect(x: 0, y: slice.yStart, width: built.width, height: slice.height)
            return attr
        case .tiles(let count, _):
            guard indexPath.item >= 0, indexPath.item < count else { return nil }
            let cols = DarkroomDimens.gridColumns
            let row = indexPath.item / cols
            let col = indexPath.item % cols
            let gap = DarkroomDimens.gridGap
            let attr = UICollectionViewLayoutAttributes(forCellWith: indexPath)
            attr.frame = CGRect(
                x: CGFloat(col) * (built.tileSide + gap),
                y: slice.yStart + CGFloat(row) * built.rowPitch,
                width: built.tileSide,
                height: built.tileSide
            )
            return attr
        }
    }
}

/// 照片页全范围共享纯 UIKit 网格视图（支持日期分组、月份分组、沉浸 / 大文件平铺、那年今天展开/收起、原生拖选与预取）。
@MainActor
final class PhotosGridView: UIView, UICollectionViewDataSource, UICollectionViewDelegate, UICollectionViewDataSourcePrefetching, UIGestureRecognizerDelegate {
    private enum SectionData {
        case year(Int)
        case dayHeader(PhotoDaySection)
        case monthHeader(PhotoDaySection)
        case tiles(items: [PhotoItem], dayId: String, showSize: Bool)
        case more(PhotoDaySection)
    }

    struct Config {
        var scope: LibraryScope
        var page: ScopePage
        var immersive: Bool
        var expandedDayId: String?
        var isLoading: Bool
        var initialIDs: Set<String>
        var selecting: Bool
        var selected: Set<String>
        var chinese: Bool
        var locale: Locale
        var chromeHeight: CGFloat
        var pinY: CGFloat
        var menuOpen: Bool
        var showLanguageSheet: Bool
        var stuckDayId: String?
    }

    private let gridLayout = PhotosGridLayout()
    let collectionView: UICollectionView
    private let pipeline: ThumbnailPipeline
    private let prefetch: ThumbnailPrefetchWindow

    private var config: Config
    private var sections: [SectionData] = []
    private(set) var built: PhotosGridGeometry.BuiltLayout
    private(set) var drawnOrder: [PhotoItem] = []
    private var photoIndexById: [String: Int] = [:]
    private var tileSliceIndices: [Int] = []
    private var isActiveScope = false

    private var lastStructureSig = ""
    private var lastWidth: CGFloat = 0
    private var lastLocaleId = ""
    private var lastChinese = true
    private var lastSelecting = false
    private var lastSelected: Set<String> = []
    private var lastIsMoving = false
    private var lastUserScrollTime: CFTimeInterval = -10
    private var suppressTapUntil: CFTimeInterval = 0
    private var programmaticScroll = false
    private var lastReportedCanScroll: Bool?

    private var rawResolveTask: Task<Void, Never>?

    // MARK: Drag Select State
    private lazy var longPressRecognizer: UILongPressGestureRecognizer = {
        let g = UILongPressGestureRecognizer(target: self, action: #selector(handleLongPress(_:)))
        g.minimumPressDuration = 0.35
        g.allowableMovement = 10
        g.cancelsTouchesInView = true
        g.delegate = self
        return g
    }()

    private final class AutoScrollProxy: NSObject {
        weak var owner: PhotosGridView?
        @objc func tick() {
            let owner = owner
            MainActor.assumeIsolated { owner?.tickAutoScroll() }
        }
    }

    private var dragActive = false
    private var dragAnchorIndex: Int?
    private var dragLastId: String?
    private var dragBaseSelection: Set<String> = []
    private var autoScrollLink: CADisplayLink?
    private var autoScrollProxy: AutoScrollProxy?

    var onTapPhoto: ((PhotoItem) -> Void)?
    var onOpenDay: ((PhotoDaySection) -> Void)?
    var onCollapseDay: ((String) -> Void)?
    var onExpandDay: ((String) -> Void)?
    var onToggleSelectDay: ((PhotoDaySection) -> Void)?
    var onSelectionChanged: ((_ nextSelected: Set<String>, _ enterSelecting: Bool) -> Void)?
    var onDragSelectActiveChanged: ((Bool) -> Void)?
    var onScrollState: ((_ y: CGFloat, _ moving: Bool, _ programmatic: Bool, _ canScroll: Bool) -> Void)?
    var onScrollSettled: (() -> Void)?
    var onLayoutChanged: ((_ built: PhotosGridGeometry.BuiltLayout, _ canScroll: Bool) -> Void)?

    init(config: Config, pipeline: ThumbnailPipeline) {
        self.config = config
        self.pipeline = pipeline
        self.prefetch = ThumbnailPrefetchWindow(pipeline: pipeline)
        self.built = gridLayout.built
        self.collectionView = UICollectionView(frame: .zero, collectionViewLayout: gridLayout)
        super.init(frame: .zero)

        backgroundColor = DarkroomUIColors.room
        collectionView.backgroundColor = DarkroomUIColors.room
        collectionView.showsVerticalScrollIndicator = false
        collectionView.alwaysBounceVertical = true
        collectionView.contentInsetAdjustmentBehavior = .never
        collectionView.delaysContentTouches = true
        collectionView.canCancelContentTouches = true

        collectionView.register(DarkroomDividerCell.self, forCellWithReuseIdentifier: DarkroomDividerCell.reuseIdentifier)
        collectionView.register(PhotosDayHeaderCell.self, forCellWithReuseIdentifier: PhotosDayHeaderCell.reuseIdentifier)
        collectionView.register(PhotosMoreRowCell.self, forCellWithReuseIdentifier: PhotosMoreRowCell.reuseIdentifier)
        collectionView.register(PhotoGridCell.self, forCellWithReuseIdentifier: PhotoGridCell.reuseIdentifier)

        collectionView.dataSource = self
        collectionView.delegate = self
        collectionView.prefetchDataSource = self
        collectionView.addGestureRecognizer(longPressRecognizer)

        addSubview(collectionView)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    deinit {
        autoScrollLink?.invalidate()
        rawResolveTask?.cancel()
    }

    func teardown() {
        stopAutoScroll()
        rawResolveTask?.cancel()
        rawResolveTask = nil
        prefetch.reset()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let b = bounds
        if collectionView.frame != b {
            collectionView.frame = b
        }
        if b.width > 1 && abs(b.width - lastWidth) > 0.5 {
            rebuildDataAndLayout(width: b.width, keepOffset: true)
        } else {
            reportCanScrollIfChanged()
        }
    }

    func setActiveScope(_ active: Bool) {
        isActiveScope = active
        if active {
            if ScrollProbe.enabled {
                ScrollProbe.shared.page = "photos-\(config.scope.rawValue)"
            }
            resolveVisibleRawIfIdle()
        }
    }

    func update(_ next: Config) {
        let structureChanged = structureSignature(next) != lastStructureSig
        let selectionChanged = next.selecting != lastSelecting || next.selected != lastSelected
        let localeChanged = next.locale.identifier != lastLocaleId || next.chinese != lastChinese
        let stuckChanged = next.stuckDayId != config.stuckDayId
        let initialAppeared = next.initialIDs != config.initialIDs

        config = next

        let width = bounds.width > 1 ? bounds.width : (lastWidth > 1 ? lastWidth : UIScreen.main.bounds.width)
        if structureChanged || abs(width - lastWidth) > 0.5 || initialAppeared {
            rebuildDataAndLayout(width: width, keepOffset: true)
            return
        }

        if stuckChanged {
            updateStuckDayId(next.stuckDayId)
        }

        if selectionChanged || localeChanged {
            lastSelecting = next.selecting
            lastSelected = next.selected
            lastLocaleId = next.locale.identifier
            lastChinese = next.chinese
            refreshVisibleCells(animated: selectionChanged && !localeChanged)
        }
    }

    func updateStuckDayId(_ id: String?) {
        config.stuckDayId = id
        for case let headerCell as PhotosDayHeaderCell in collectionView.visibleCells {
            headerCell.setStuckHidden(headerCell.dayId == id)
        }
    }

    func currentOffsetY() -> CGFloat {
        collectionView.contentOffset.y
    }

    func canScrollNow() -> Bool {
        let h = collectionView.bounds.height
        return h > 0 && built.totalHeight > h + 1
    }

    func isListMoving() -> Bool {
        collectionView.isDragging || collectionView.isDecelerating
    }

    func setScrollOffset(_ y: CGFloat, programmatic: Bool) {
        if collectionView.window != nil && (collectionView.bounds.width <= 1 || abs(collectionView.bounds.width - lastWidth) > 0.5) {
            collectionView.layoutIfNeeded()
        }
        let maxY = max(0, built.totalHeight - max(collectionView.bounds.height, UIScreen.main.bounds.height))
        let target = min(maxY, max(0, y))
        programmaticScroll = programmatic
        collectionView.setContentOffset(CGPoint(x: 0, y: target), animated: false)
        programmaticScroll = false
        resolveVisibleRawIfIdle()
    }

    /// 获取 pinY 下方的第一张可见照片（供「日期分组 ↔ 沉浸」视图切换无缝对齐锚点）。
    func topVisiblePhotoId(below pinY: CGFloat) -> String? {
        let targetY = collectionView.contentOffset.y + pinY
        var bestId: String?
        var bestY = CGFloat.greatestFiniteMagnitude
        for ip in collectionView.indexPathsForVisibleItems {
            guard sections.indices.contains(ip.section),
                  case .tiles(let items, _, _) = sections[ip.section],
                  items.indices.contains(ip.item),
                  let attr = gridLayout.layoutAttributesForItem(at: ip) else { continue }
            if attr.frame.maxY > targetY && attr.frame.minY < bestY {
                bestY = attr.frame.minY
                bestId = items[ip.item].id
            }
        }
        return bestId
    }

    func contentTop(forPhotoId id: String) -> CGFloat? {
        for sIdx in tileSliceIndices {
            guard sections.indices.contains(sIdx),
                  case .tiles(let items, _, _) = sections[sIdx],
                  let itemIdx = items.firstIndex(where: { $0.id == id }) else { continue }
            return gridLayout.layoutAttributesForItem(at: IndexPath(item: itemIdx, section: sIdx))?.frame.minY
        }
        return nil
    }

    func contentTop(forDayId id: String) -> CGFloat? {
        for (i, sec) in sections.enumerated() {
            if case .dayHeader(let day) = sec, day.id == id {
                return built.slices[i].yStart
            }
        }
        return nil
    }

    // MARK: Shared Transition Query

    func sharedTransitionFrame(for id: String) -> CGRect? {
        guard collectionView.window != nil else { return nil }
        for case let cell as PhotoGridCell in collectionView.visibleCells where cell.representedAssetId == id {
            return cell.convert(cell.bounds, to: nil)
        }
        return nil
    }

    func sharedTransitionImage(for id: String) -> UIImage? {
        guard collectionView.window != nil else { return nil }
        for case let cell as PhotoGridCell in collectionView.visibleCells where cell.representedAssetId == id {
            if let img = cell.currentImage() { return img }
        }
        return pipeline.cached(id: id, side: built.tileSide, style: .normal)
    }

    func applyFlyingId(_ flyingId: String?) {
        for case let cell as PhotoGridCell in collectionView.visibleCells {
            let hidden = (flyingId != nil && cell.representedAssetId == flyingId)
            cell.contentView.alpha = hidden ? 0 : 1
        }
    }

    // MARK: Test Harness Support

    func simulateDragSelect(steps: Int) {
        guard config.scope != .similar, !drawnOrder.isEmpty else { return }
        let count = min(max(1, steps), drawnOrder.count)
        let picked = Set(drawnOrder.prefix(count).map(\.id))
        onSelectionChanged?(picked, true)
    }

    // MARK: Layout & Data Building

    private func structureSignature(_ cfg: Config) -> String {
        let groupSig = cfg.page.groups.map { year in
            let days = year.days.map { "\($0.id):\($0.items.count):\($0.allItems.count):\($0.seenCount):\($0.overflow.count):\($0.header)" }.joined(separator: ",")
            return "\(year.id)[\(days)]"
        }.joined(separator: ";")
        let flatSig = "\(cfg.page.flat.first?.id ?? "")-\(cfg.page.flat.last?.id ?? "")-\(cfg.page.flat.count)"
        return "\(cfg.scope.rawValue)|\(cfg.immersive)|\(cfg.expandedDayId ?? "")|\(cfg.page.visibleCount)|\(cfg.chromeHeight)|\(flatSig)|\(groupSig)"
    }

    private func rebuildDataAndLayout(width: CGFloat, keepOffset: Bool) {
        let oldOffset = collectionView.contentOffset.y
        lastStructureSig = structureSignature(config)
        lastWidth = width
        lastLocaleId = config.locale.identifier
        lastChinese = config.chinese
        lastSelecting = config.selecting
        lastSelected = config.selected

        var nextSections: [SectionData] = []
        var specs: [PhotosGridGeometry.SectionSpec] = []
        var nextDrawnOrder: [PhotoItem] = []
        var nextIndexById: [String: Int] = [:]
        var nextTileSlices: [Int] = []

        if config.scope == .largeFiles || config.immersive {
            let items = config.page.flat
            let dayId = config.scope == .largeFiles ? "flat" : "immersive"
            let showSize = config.scope == .largeFiles
            if !items.isEmpty {
                nextTileSlices.append(nextSections.count)
                nextSections.append(.tiles(items: items, dayId: dayId, showSize: showSize))
                specs.append(.init(kind: .tiles(count: items.count, dayId: nil)))
                nextDrawnOrder = items
                nextIndexById.reserveCapacity(items.count)
                for (i, item) in items.enumerated() {
                    nextIndexById[item.id] = i
                }
            }
        } else {
            let daysAll = config.page.groups.flatMap(\.days)
            let lastDayId = daysAll.last { $0.header == .day }?.id
            for year in config.page.groups {
                if year.year > 0 {
                    nextSections.append(.year(year.year))
                    specs.append(.init(kind: .year))
                }
                for day in year.days {
                    if day.header == .month {
                        nextSections.append(.monthHeader(day))
                        specs.append(.init(kind: .monthHeader))
                    } else if day.header == .day {
                        nextSections.append(.dayHeader(day))
                        specs.append(.init(kind: .dayHeader(id: day.id)))
                    }
                    let drawn = (config.scope == .onThisDay && config.expandedDayId == day.id) ? day.allItems : day.items
                    if !drawn.isEmpty {
                        nextTileSlices.append(nextSections.count)
                        nextSections.append(.tiles(items: drawn, dayId: day.id, showSize: false))
                        let markDay = (day.id == lastDayId) ? day.id : nil
                        specs.append(.init(kind: .tiles(count: drawn.count, dayId: markDay)))
                        for item in drawn {
                            nextIndexById[item.id] = nextDrawnOrder.count
                            nextDrawnOrder.append(item)
                        }
                    }
                    if config.scope == .onThisDay && config.expandedDayId != day.id && !day.overflow.isEmpty {
                        nextSections.append(.more(day))
                        specs.append(.init(kind: .more))
                    }
                }
            }
        }

        var nextBuilt = PhotosGridGeometry.build(
            width: width,
            chromeHeight: config.chromeHeight,
            bottomReserve: DarkroomDimens.listFade + 48,
            yearHeight: DarkroomDimens.yearHeight,
            headerHeight: DarkroomDimens.dayHeight,
            moreHeight: DarkroomDimens.minTouch,
            gridGap: DarkroomDimens.gridGap,
            columns: DarkroomDimens.gridColumns,
            specs: specs
        )

        if config.immersive {
            let allDays = config.page.groups.flatMap(\.days).filter { $0.header == .day }
            nextBuilt.dayAnchors = PhotosGridGeometry.immersiveDayAnchors(
                dayItemCounts: allDays.map(\.allItems.count),
                chromeHeight: config.chromeHeight,
                rowPitch: nextBuilt.rowPitch,
                columns: DarkroomDimens.gridColumns
            )
        }

        sections = nextSections
        drawnOrder = nextDrawnOrder
        photoIndexById = nextIndexById
        tileSliceIndices = nextTileSlices
        built = nextBuilt
        gridLayout.built = nextBuilt
        gridLayout.invalidateLayout()
        collectionView.reloadData()

        if keepOffset {
            let maxY = max(0, nextBuilt.totalHeight - max(collectionView.bounds.height, UIScreen.main.bounds.height))
            let clamped = min(maxY, max(0, oldOffset))
            if abs(clamped - collectionView.contentOffset.y) > 0.5 {
                programmaticScroll = true
                collectionView.setContentOffset(CGPoint(x: 0, y: clamped), animated: false)
                programmaticScroll = false
            }
        }

        let canScroll = canScrollNow()
        lastReportedCanScroll = canScroll
        onLayoutChanged?(nextBuilt, canScroll)

        if !nextDrawnOrder.isEmpty {
            prefetch.warmInitial(nextDrawnOrder.prefix(48).map(\.asset), side: nextBuilt.tileSide, style: .normal, limit: 48)
        }
        resolveVisibleRawIfIdle()
    }

    private func refreshVisibleCells(animated: Bool) {
        let flying = PhotoOpenTransition.shared.flyingId
        for ip in collectionView.indexPathsForVisibleItems {
            guard sections.indices.contains(ip.section) else { continue }
            switch sections[ip.section] {
            case .dayHeader(let day):
                if let cell = collectionView.cellForItem(at: ip) as? PhotosDayHeaderCell {
                    let showSelect = config.selecting && (config.scope != .all || !config.immersive)
                    let ids = Set(day.allItems.map(\.id))
                    let allOn = !ids.isEmpty && ids.isSubset(of: config.selected)
                    cell.configure(
                        day: day,
                        chinese: config.chinese,
                        locale: config.locale,
                        showSelectAll: showSelect,
                        allSelected: allOn,
                        selecting: config.selecting,
                        showCollapse: config.scope == .onThisDay && config.expandedDayId == day.id && !config.selecting,
                        hiddenAsStuck: day.id == config.stuckDayId
                    )
                }
            case .monthHeader(let day):
                if let cell = collectionView.cellForItem(at: ip) as? PhotosDayHeaderCell {
                    let showSelect = config.selecting && (config.scope != .all || !config.immersive)
                    let ids = Set(day.allItems.map(\.id))
                    let allOn = !ids.isEmpty && ids.isSubset(of: config.selected)
                    cell.configure(
                        day: day,
                        chinese: config.chinese,
                        locale: config.locale,
                        showSelectAll: showSelect,
                        allSelected: allOn,
                        selecting: config.selecting,
                        showCollapse: false,
                        hiddenAsStuck: false
                    )
                }
            case .more(let day):
                if let cell = collectionView.cellForItem(at: ip) as? PhotosMoreRowCell {
                    cell.configure(overflowCount: day.overflow.count, chinese: config.chinese)
                }
            case .tiles(let items, let dayId, let showSize):
                guard items.indices.contains(ip.item),
                      let cell = collectionView.cellForItem(at: ip) as? PhotoGridCell else { continue }
                let item = items[ip.item]
                cell.configure(
                    tileConfig(item: item, dayId: dayId, index: ip.item, showSize: showSize),
                    pipeline: pipeline,
                    animated: animated
                )
                cell.contentView.alpha = (flying == item.id) ? 0 : 1
            case .year:
                break
            }
        }
    }

    private func reportCanScrollIfChanged() {
        let can = canScrollNow()
        if lastReportedCanScroll != can {
            lastReportedCanScroll = can
            onLayoutChanged?(built, can)
        }
    }

    // MARK: UICollectionViewDataSource

    func numberOfSections(in collectionView: UICollectionView) -> Int {
        sections.count
    }

    func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
        switch sections[section] {
        case .year, .dayHeader, .monthHeader, .more:
            return 1
        case .tiles(let items, _, _):
            return items.count
        }
    }

    func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        switch sections[indexPath.section] {
        case .year(let year):
            let cell = collectionView.dequeueReusableCell(withReuseIdentifier: DarkroomDividerCell.reuseIdentifier, for: indexPath) as! DarkroomDividerCell
            cell.configure(title: String(year), topPadding: 0, tracking: 0.88)
            return cell
        case .dayHeader(let day):
            let cell = collectionView.dequeueReusableCell(withReuseIdentifier: PhotosDayHeaderCell.reuseIdentifier, for: indexPath) as! PhotosDayHeaderCell
            let showSelect = config.selecting && (config.scope != .all || !config.immersive)
            let ids = Set(day.allItems.map(\.id))
            let allOn = !ids.isEmpty && ids.isSubset(of: config.selected)
            cell.configure(
                day: day,
                chinese: config.chinese,
                locale: config.locale,
                showSelectAll: showSelect,
                allSelected: allOn,
                selecting: config.selecting,
                showCollapse: config.scope == .onThisDay && config.expandedDayId == day.id && !config.selecting,
                hiddenAsStuck: day.id == config.stuckDayId
            )
            cell.headerView.onOpenDay = { [weak self] in
                guard let self, self.tapsAllowed() else { return }
                self.onOpenDay?(day)
            }
            cell.headerView.onCollapse = { [weak self] in self?.onCollapseDay?(day.id) }
            cell.headerView.onToggleSelectAll = { [weak self] in self?.onToggleSelectDay?(day) }
            return cell
        case .monthHeader(let day):
            let cell = collectionView.dequeueReusableCell(withReuseIdentifier: PhotosDayHeaderCell.reuseIdentifier, for: indexPath) as! PhotosDayHeaderCell
            let showSelect = config.selecting && (config.scope != .all || !config.immersive)
            let ids = Set(day.allItems.map(\.id))
            let allOn = !ids.isEmpty && ids.isSubset(of: config.selected)
            cell.configure(
                day: day,
                chinese: config.chinese,
                locale: config.locale,
                showSelectAll: showSelect,
                allSelected: allOn,
                selecting: config.selecting,
                showCollapse: false,
                hiddenAsStuck: false
            )
            cell.headerView.onOpenDay = { [weak self] in
                guard let self, self.tapsAllowed() else { return }
                self.onOpenDay?(day)
            }
            cell.headerView.onCollapse = nil
            cell.headerView.onToggleSelectAll = { [weak self] in self?.onToggleSelectDay?(day) }
            return cell
        case .more(let day):
            let cell = collectionView.dequeueReusableCell(withReuseIdentifier: PhotosMoreRowCell.reuseIdentifier, for: indexPath) as! PhotosMoreRowCell
            cell.configure(overflowCount: day.overflow.count, chinese: config.chinese)
            return cell
        case .tiles(let items, let dayId, let showSize):
            let cell = collectionView.dequeueReusableCell(withReuseIdentifier: PhotoGridCell.reuseIdentifier, for: indexPath) as! PhotoGridCell
            let item = items[indexPath.item]
            cell.configure(
                tileConfig(item: item, dayId: dayId, index: indexPath.item, showSize: showSize),
                pipeline: pipeline,
                animated: false
            )
            cell.contentView.alpha = (PhotoOpenTransition.shared.flyingId == item.id) ? 0 : 1
            return cell
        }
    }

    private func tileConfig(item: PhotoItem, dayId: String, index: Int, showSize: Bool) -> PhotoGridCellConfig {
        let rawName = AssetSizeCache.shared.cachedOriginalFilename(item.id)
        let rawText = ScopeRules.isRawFileName(rawName) ? "RAW" : nil
        let sizeText = showSize ? FileSizeFormatter.format(AssetSizeCache.shared.cachedBytes(item.id) ?? 0) : nil
        let leftText = ScopeRules.photoMarkLine(raw: rawText, size: sizeText)
        let durationText = item.isVideo ? ScopeRules.formatDuration(seconds: item.duration) : nil
        let moving = isListMoving()
        let develop = !moving
            && !config.selecting
            && config.initialIDs.contains(item.id)
            && GridDevelopSession.shouldDevelop(grid: .photos, id: item.id, isInitialPass: false)
        let delay = GridDevelopSession.staggerDelay(
            row: index / DarkroomDimens.gridColumns,
            col: index % DarkroomDimens.gridColumns
        )
        return PhotoGridCellConfig(
            item: item,
            side: built.tileSide,
            style: .normal,
            bottomLeftText: leftText,
            bottomRightText: durationText,
            showUnseen: false,
            selecting: config.selecting,
            selected: config.selecting && config.selected.contains(item.id),
            circled: false,
            develop: develop,
            developDelay: delay,
            interactive: true,
            locale: config.locale,
            accessibilityId: "tile-\(index)-\(dayId)"
        )
    }

    // MARK: UICollectionViewDelegate

    func collectionView(_ collectionView: UICollectionView, shouldSelectItemAt indexPath: IndexPath) -> Bool {
        switch sections[indexPath.section] {
        case .tiles, .more:
            return true
        case .year, .dayHeader, .monthHeader:
            return false
        }
    }

    func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        collectionView.deselectItem(at: indexPath, animated: false)
        switch sections[indexPath.section] {
        case .more(let day):
            onExpandDay?(day.id)
        case .tiles(let items, _, _):
            guard items.indices.contains(indexPath.item),
                  CACurrentMediaTime() >= suppressTapUntil else { return }
            let item = items[indexPath.item]
            if config.selecting {
                onTapPhoto?(item)
            } else if tapsAllowed() {
                onTapPhoto?(item)
            }
        case .year, .dayHeader, .monthHeader:
            break
        }
    }

    // MARK: UIScrollViewDelegate

    func scrollViewWillBeginDragging(_ scrollView: UIScrollView) {
        lastIsMoving = true
        lastUserScrollTime = CACurrentMediaTime()
        if isActiveScope {
            onScrollState?(scrollView.contentOffset.y, true, false, canScrollNow())
        }
    }

    func scrollViewDidScroll(_ scrollView: UIScrollView) {
        let moving = scrollView.isDragging || scrollView.isDecelerating
        if moving {
            lastIsMoving = true
            lastUserScrollTime = CACurrentMediaTime()
        }
        guard isActiveScope else { return }
        if ScrollProbe.enabled {
            ScrollProbe.shared.offsetCallback()
        }
        onScrollState?(scrollView.contentOffset.y, moving, programmaticScroll, canScrollNow())
    }

    func scrollViewDidEndDragging(_ scrollView: UIScrollView, willDecelerate decelerate: Bool) {
        if !decelerate {
            handleScrollIdle()
        }
    }

    func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) {
        handleScrollIdle()
    }

    private func handleScrollIdle() {
        lastIsMoving = false
        GridDevelopSession.markPlayed(.photos)
        resolveVisibleRawIfIdle()
        if isActiveScope {
            onScrollSettled?()
        }
    }

    func tapsAllowed() -> Bool {
        if isListMoving() { return false }
        return CACurrentMediaTime() - lastUserScrollTime >= DarkroomMotion.scrollSettleIgnore
    }

    // MARK: Lazy RAW Resolution (Idle Only)

    private func resolveVisibleRawIfIdle() {
        rawResolveTask?.cancel()
        guard !isListMoving(), !config.isLoading else { return }
        let showSize = config.scope == .largeFiles
        var missing: [(id: String, asset: PHAsset)] = []
        for ip in collectionView.indexPathsForVisibleItems {
            guard sections.indices.contains(ip.section),
                  case .tiles(let items, _, _) = sections[ip.section],
                  items.indices.contains(ip.item) else { continue }
            let item = items[ip.item]
            if AssetSizeCache.shared.cachedOriginalFilename(item.id) == nil {
                missing.append((item.id, item.asset))
            }
        }
        guard !missing.isEmpty else { return }
        rawResolveTask = Task { @MainActor [weak self] in
            let infos = await Task.detached(priority: .utility) {
                var out: [String: AssetSizeCache.Info] = [:]
                for entry in missing {
                    if Task.isCancelled { break }
                    let info = AssetSizeCache.shared.info(for: entry.asset)
                    if ScopeRules.isRawFileName(info.originalFilename) {
                        out[entry.id] = info
                    }
                }
                return out
            }.value
            guard !Task.isCancelled, !infos.isEmpty, let self else { return }
            for case let cell as PhotoGridCell in self.collectionView.visibleCells {
                guard let id = cell.representedAssetId, let info = infos[id] else { continue }
                let sizeText = showSize ? FileSizeFormatter.format(info.bytes) : nil
                cell.updateBottomLeft(ScopeRules.photoMarkLine(raw: "RAW", size: sizeText))
            }
        }
    }

    // MARK: UICollectionViewDataSourcePrefetching

    func collectionView(_ collectionView: UICollectionView, prefetchItemsAt indexPaths: [IndexPath]) {
        var assets: [PHAsset] = []
        assets.reserveCapacity(indexPaths.count)
        for ip in indexPaths {
            guard sections.indices.contains(ip.section),
                  case .tiles(let items, _, _) = sections[ip.section],
                  items.indices.contains(ip.item) else { continue }
            assets.append(items[ip.item].asset)
        }
        prefetch.startCaching(assets, side: built.tileSide, style: .normal)
    }

    func collectionView(_ collectionView: UICollectionView, cancelPrefetchingForItemsAt indexPaths: [IndexPath]) {
        var assets: [PHAsset] = []
        assets.reserveCapacity(indexPaths.count)
        for ip in indexPaths {
            guard sections.indices.contains(ip.section),
                  case .tiles(let items, _, _) = sections[ip.section],
                  items.indices.contains(ip.item) else { continue }
            assets.append(items[ip.item].asset)
        }
        prefetch.stopCaching(assets)
    }

    // MARK: Long Press Drag-Select + Edge Auto-Scroll

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool {
        false
    }

    @objc private func handleLongPress(_ g: UILongPressGestureRecognizer) {
        switch g.state {
        case .began:
            guard !config.isLoading, !config.menuOpen, !config.showLanguageSheet,
                  !collectionView.isDecelerating,
                  config.selecting || tapsAllowed(),
                  let hit = photoItem(at: g.location(in: collectionView), strictVertical: true),
                  let anchorIdx = photoIndexById[hit.id] else {
                g.isEnabled = false
                g.isEnabled = true
                return
            }
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            dragActive = true
            dragAnchorIndex = anchorIdx
            dragLastId = hit.id
            dragBaseSelection = config.selecting ? config.selected : []
            suppressTapUntil = .greatestFiniteMagnitude
            collectionView.panGestureRecognizer.isEnabled = false
            collectionView.panGestureRecognizer.isEnabled = true
            collectionView.isScrollEnabled = false
            onDragSelectActiveChanged?(true)
            let initialSel = dragBaseSelection.union([hit.id])
            onSelectionChanged?(initialSel, true)
            startAutoScroll()
        case .changed:
            guard dragActive else { return }
            updateDragSelection(at: g.location(in: collectionView))
        case .ended, .cancelled, .failed:
            finishDragSelect()
        default:
            break
        }
    }

    private func updateDragSelection(at contentPoint: CGPoint) {
        guard dragActive, let anchorIdx = dragAnchorIndex,
              let hit = photoItem(at: contentPoint, strictVertical: false),
              hit.id != dragLastId,
              let toIdx = photoIndexById[hit.id] else { return }
        dragLastId = hit.id
        let lo = min(anchorIdx, toIdx)
        let hi = max(anchorIdx, toIdx)
        var next = dragBaseSelection
        next.reserveCapacity(dragBaseSelection.count + (hi - lo + 1))
        for idx in lo...hi {
            next.insert(drawnOrder[idx].id)
        }
        if next != config.selected {
            UISelectionFeedbackGenerator().selectionChanged()
            onSelectionChanged?(next, true)
        }
    }

    private func finishDragSelect() {
        guard dragActive else { return }
        dragActive = false
        dragAnchorIndex = nil
        dragLastId = nil
        dragBaseSelection.removeAll(keepingCapacity: false)
        stopAutoScroll()
        collectionView.isScrollEnabled = true
        suppressTapUntil = CACurrentMediaTime() + 0.3
        onDragSelectActiveChanged?(false)
    }

    private func startAutoScroll() {
        stopAutoScroll()
        let proxy = AutoScrollProxy()
        proxy.owner = self
        autoScrollProxy = proxy
        let link = CADisplayLink(target: proxy, selector: #selector(AutoScrollProxy.tick))
        link.preferredFrameRateRange = CAFrameRateRange(minimum: 80, maximum: 120, preferred: 120)
        link.add(to: .main, forMode: .common)
        autoScrollLink = link
    }

    private func stopAutoScroll() {
        autoScrollLink?.invalidate()
        autoScrollLink = nil
        autoScrollProxy = nil
    }

    private func tickAutoScroll() {
        guard dragActive else { return }
        let inSelf = longPressRecognizer.location(in: self)
        let edge: CGFloat = 64
        let maxStep: CGFloat = 14
        let top = safeAreaInsets.top + config.pinY
        let bottom = bounds.height - safeAreaInsets.bottom
        var step: CGFloat = 0
        if inSelf.y < top + edge {
            let t = min(1, max(0, (top + edge - inSelf.y) / edge))
            step = -maxStep * t
        } else if inSelf.y > bottom - edge {
            let t = min(1, max(0, (inSelf.y - (bottom - edge)) / edge))
            step = maxStep * t
        }
        guard step != 0 else { return }
        let maxY = max(0, built.totalHeight - collectionView.bounds.height)
        let nextY = min(maxY, max(0, collectionView.contentOffset.y + step))
        guard abs(nextY - collectionView.contentOffset.y) > 0.01 else { return }
        programmaticScroll = true
        collectionView.contentOffset.y = nextY
        programmaticScroll = false
        updateDragSelection(at: longPressRecognizer.location(in: collectionView))
    }

    private func photoItem(at contentPoint: CGPoint, strictVertical: Bool) -> PhotoItem? {
        let x = contentPoint.x
        let y = contentPoint.y
        guard x >= 0, x <= built.width, !tileSliceIndices.isEmpty else { return nil }
        let cols = DarkroomDimens.gridColumns
        let gap = DarkroomDimens.gridGap
        let pitch = built.rowPitch
        let side = built.tileSide
        let range = PhotosGridGeometry.sliceRange(in: built.slices, minY: y - gap, maxY: y + gap)
        for sIdx in range {
            guard case .tiles(let count, _) = built.slices[sIdx].kind, count > 0,
                  case .tiles(let items, _, _) = sections[sIdx] else { continue }
            let slice = built.slices[sIdx]
            let relY = y - slice.yStart
            if strictVertical && (relY < -gap || relY > slice.height) { continue }
            if !strictVertical && (relY < -gap || relY > slice.height) { continue }
            let totalRows = (count + cols - 1) / cols
            let row = min(max(0, Int(floor(relY / pitch))), totalRows - 1)
            let yInRow = relY - CGFloat(row) * pitch
            if yInRow < -gap || yInRow > side + gap { continue }
            let colPitch = side + gap
            let col = min(max(0, Int(floor(x / colPitch))), cols - 1)
            let xInCol = x - CGFloat(col) * colPitch
            if xInCol > side + gap { continue }
            let idx = row * cols + col
            guard items.indices.contains(idx) else { continue }
            return items[idx]
        }
        return nil
    }
}
