import Photos
import SwiftUI
import UIKit

/// 垃圾桶 3 列缩略图网格（UICollectionView + DiffableDataSource + 预取窗口）。
/// Section 0 用 UIHostingConfiguration 承载顶部卡片 / 多选摘要，Section 1 承载复用瓦片。
struct TrashGridCollectionView: UIViewRepresentable {
    let items: [PhotoItem]
    let selecting: Bool
    let selected: Set<String>
    let totalBytes: Int64
    let oldestDays: Int?
    let selectedBytes: Int64
    let locale: Locale
    let pipeline: ThumbnailPipeline
    let bytesProvider: (String) -> Int64
    let onTapItem: (PhotoItem) -> Void
    let onEmptyTap: () -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(parent: self)
    }

    func makeUIView(context: Context) -> UICollectionView {
        let coordinator = context.coordinator
        let layout = coordinator.makeLayout()
        let cv = UICollectionView(frame: .zero, collectionViewLayout: layout)
        cv.backgroundColor = DarkroomUIColors.room
        cv.showsVerticalScrollIndicator = false
        cv.alwaysBounceVertical = true
        cv.contentInsetAdjustmentBehavior = .never
        cv.contentInset = UIEdgeInsets(
            top: DarkroomDimens.headerHeight,
            left: 0,
            bottom: DarkroomDimens.navBottom + DarkroomDimens.navHeight + 82,
            right: 0
        )
        cv.register(UICollectionViewCell.self, forCellWithReuseIdentifier: Coordinator.headerReuseId)
        cv.register(PhotoGridCell.self, forCellWithReuseIdentifier: PhotoGridCell.reuseIdentifier)
        cv.delegate = coordinator
        cv.prefetchDataSource = coordinator
        coordinator.collectionView = cv
        coordinator.configureDataSource(cv)
        coordinator.applySnapshot(from: self)
        return cv
    }

    func updateUIView(_ uiView: UICollectionView, context: Context) {
        context.coordinator.update(with: self, collectionView: uiView)
    }

    static func dismantleUIView(_ uiView: UICollectionView, coordinator: Coordinator) {
        coordinator.teardown()
    }

    @MainActor
    final class Coordinator: NSObject, UICollectionViewDelegate, UICollectionViewDataSourcePrefetching {
        static let headerReuseId = "TrashHeaderCell"

        enum Section: Int, Hashable {
            case top
            case grid
        }

        enum RowItem: Hashable {
            case freeCard
            case selectionSummary
            case photo(String)
        }

        var parent: TrashGridCollectionView
        weak var collectionView: UICollectionView?
        private var dataSource: UICollectionViewDiffableDataSource<Section, RowItem>?
        private let prefetch: ThumbnailPrefetchWindow
        private var itemsById: [String: PhotoItem] = [:]
        private var indexById: [String: Int] = [:]
        private var currentOrder: [String] = []
        private var currentSelecting = false
        private var currentSelected: Set<String> = []
        private var currentTotalBytes: Int64 = -1
        private var currentOldestDays: Int?
        private var currentSelectedBytes: Int64 = -1
        private var currentLocaleId = ""
        private var didInitialOffset = false
        private var rawResolveTask: Task<Void, Never>?

        init(parent: TrashGridCollectionView) {
            self.parent = parent
            self.prefetch = ThumbnailPrefetchWindow(pipeline: parent.pipeline)
        }

        func teardown() {
            rawResolveTask?.cancel()
            rawResolveTask = nil
            prefetch.reset()
        }

        func makeLayout() -> UICollectionViewCompositionalLayout {
            UICollectionViewCompositionalLayout { [weak self] sectionIndex, env in
                guard let section = Section(rawValue: sectionIndex) else { return nil }
                switch section {
                case .top:
                    let size = NSCollectionLayoutSize(
                        widthDimension: .fractionalWidth(1.0),
                        heightDimension: .estimated(self?.parent.selecting == true ? 40 : 140)
                    )
                    let item = NSCollectionLayoutItem(layoutSize: size)
                    let group = NSCollectionLayoutGroup.vertical(layoutSize: size, subitems: [item])
                    return NSCollectionLayoutSection(group: group)
                case .grid:
                    let cols = 3
                    let gap = DarkroomDimens.gridGap
                    let width = env.container.effectiveContentSize.width
                    let side = (max(width, 1) - gap * CGFloat(cols - 1)) / CGFloat(cols)
                    let itemSize = NSCollectionLayoutSize(
                        widthDimension: .absolute(side),
                        heightDimension: .absolute(side)
                    )
                    let item = NSCollectionLayoutItem(layoutSize: itemSize)
                    let groupSize = NSCollectionLayoutSize(
                        widthDimension: .fractionalWidth(1.0),
                        heightDimension: .absolute(side)
                    )
                    let group = NSCollectionLayoutGroup.horizontal(
                        layoutSize: groupSize,
                        repeatingSubitem: item,
                        count: cols
                    )
                    group.interItemSpacing = .fixed(gap)
                    let sec = NSCollectionLayoutSection(group: group)
                    sec.interGroupSpacing = gap
                    return sec
                }
            }
        }

        func configureDataSource(_ cv: UICollectionView) {
            dataSource = UICollectionViewDiffableDataSource<Section, RowItem>(collectionView: cv) { [weak self] cv, indexPath, row in
                guard let self else { return UICollectionViewCell() }
                switch row {
                case .freeCard, .selectionSummary:
                    let cell = cv.dequeueReusableCell(withReuseIdentifier: Self.headerReuseId, for: indexPath)
                    self.configureHeaderCell(cell)
                    return cell
                case .photo(let id):
                    let cell = cv.dequeueReusableCell(withReuseIdentifier: PhotoGridCell.reuseIdentifier, for: indexPath) as! PhotoGridCell
                    if let item = self.itemsById[id] {
                        let width = cv.bounds.width
                        let side = self.tileSide(width: width)
                        let idx = self.indexById[id] ?? indexPath.item
                        cell.configure(self.cellConfig(item: item, index: idx, side: side), pipeline: self.parent.pipeline, animated: false)
                    }
                    return cell
                }
            }
        }

        func update(with newParent: TrashGridCollectionView, collectionView cv: UICollectionView) {
            let newOrder = newParent.items.map(\.id)
            let orderChanged = newOrder != currentOrder
            let selectingChanged = newParent.selecting != currentSelecting
            let selectedChanged = newParent.selected != currentSelected
            let headerChanged = selectingChanged
                || newParent.totalBytes != currentTotalBytes
                || newParent.oldestDays != currentOldestDays
                || newParent.selectedBytes != currentSelectedBytes
                || newParent.locale.identifier != currentLocaleId
            let oldSelected = currentSelected

            parent = newParent

            if !didInitialOffset && cv.bounds.height > 0 {
                didInitialOffset = true
                cv.setContentOffset(CGPoint(x: 0, y: -cv.contentInset.top), animated: false)
            }

            if orderChanged || selectingChanged {
                applySnapshot(from: newParent)
                return
            }

            if selectedChanged {
                currentSelected = newParent.selected
                let diff = oldSelected.symmetricDifference(newParent.selected)
                let side = tileSide(width: cv.bounds.width)
                for id in diff {
                    guard let idx = indexById[id],
                          let item = itemsById[id],
                          let cell = cv.cellForItem(at: IndexPath(item: idx, section: Section.grid.rawValue)) as? PhotoGridCell else {
                        continue
                    }
                    cell.configure(cellConfig(item: item, index: idx, side: side), pipeline: parent.pipeline, animated: true)
                }
            }

            if headerChanged {
                currentTotalBytes = newParent.totalBytes
                currentOldestDays = newParent.oldestDays
                currentSelectedBytes = newParent.selectedBytes
                currentLocaleId = newParent.locale.identifier
                if let headerCell = cv.cellForItem(at: IndexPath(item: 0, section: Section.top.rawValue)) {
                    configureHeaderCell(headerCell)
                    cv.collectionViewLayout.invalidateLayout()
                }
            }
        }

        func applySnapshot(from source: TrashGridCollectionView) {
            parent = source
            currentOrder = source.items.map(\.id)
            itemsById = Dictionary(uniqueKeysWithValues: source.items.map { ($0.id, $0) })
            indexById = Dictionary(uniqueKeysWithValues: source.items.enumerated().map { ($0.element.id, $0.offset) })
            currentSelecting = source.selecting
            currentSelected = source.selected
            currentTotalBytes = source.totalBytes
            currentOldestDays = source.oldestDays
            currentSelectedBytes = source.selectedBytes
            currentLocaleId = source.locale.identifier

            var snapshot = NSDiffableDataSourceSnapshot<Section, RowItem>()
            snapshot.appendSections([.top, .grid])
            snapshot.appendItems([source.selecting ? .selectionSummary : .freeCard], toSection: .top)
            snapshot.appendItems(currentOrder.map { .photo($0) }, toSection: .grid)
            dataSource?.apply(snapshot, animatingDifferences: false) { [weak self] in
                guard let self, let cv = self.collectionView else { return }
                if !self.didInitialOffset && cv.bounds.height > 0 {
                    self.didInitialOffset = true
                    cv.setContentOffset(CGPoint(x: 0, y: -cv.contentInset.top), animated: false)
                }
                if let headerCell = cv.cellForItem(at: IndexPath(item: 0, section: Section.top.rawValue)) {
                    self.configureHeaderCell(headerCell)
                }
                cv.collectionViewLayout.invalidateLayout()
                self.reconfigureVisibleTiles(in: cv)
                self.prefetch.warmInitial(
                    self.parent.items.map(\.asset),
                    side: self.tileSide(width: cv.bounds.width),
                    style: .trashed,
                    limit: 48
                )
                self.resolveVisibleRawIfIdle()
            }
        }

        private func configureHeaderCell(_ cell: UICollectionViewCell) {
            let p = parent
            cell.backgroundConfiguration = .clear()
            cell.contentConfiguration = UIHostingConfiguration {
                if p.selecting {
                    TrashSelectionSummaryView(selectedBytes: p.selectedBytes, locale: p.locale)
                } else {
                    TrashFreeCardView(
                        bytes: p.totalBytes,
                        enabled: true,
                        oldestDays: p.oldestDays,
                        locale: p.locale,
                        onEmpty: p.onEmptyTap
                    )
                }
            }
            .margins(.all, 0)
        }

        private func reconfigureVisibleTiles(in cv: UICollectionView) {
            let side = tileSide(width: cv.bounds.width)
            for ip in cv.indexPathsForVisibleItems where ip.section == Section.grid.rawValue {
                guard ip.item < parent.items.count,
                      let cell = cv.cellForItem(at: ip) as? PhotoGridCell else { continue }
                let item = parent.items[ip.item]
                cell.configure(cellConfig(item: item, index: ip.item, side: side), pipeline: parent.pipeline, animated: false)
            }
        }

        private func cellConfig(item: PhotoItem, index: Int, side: CGFloat) -> PhotoGridCellConfig {
            let isSel = parent.selecting && parent.selected.contains(item.id)
            let rawName = AssetSizeCache.shared.cachedOriginalFilename(item.id)
            let rawText = ScopeRules.isRawFileName(rawName) ? "RAW" : nil
            let rightText: String?
            if item.isVideo {
                rightText = ScopeRules.formatDuration(seconds: item.duration)
            } else if isSel {
                rightText = nil
            } else {
                rightText = FileSizeFormatter.format(parent.bytesProvider(item.id))
            }
            return PhotoGridCellConfig(
                item: item,
                side: side,
                style: isSel ? .normal : .trashed,
                bottomLeftText: rawText,
                bottomRightText: rightText,
                showUnseen: false,
                selecting: parent.selecting,
                selected: isSel,
                circled: false,
                develop: false,
                developDelay: 0,
                interactive: true,
                locale: parent.locale,
                accessibilityId: "trash-tile-\(index)"
            )
        }

        private func tileSide(width: CGFloat) -> CGFloat {
            let effective = width > 1 ? width : UIScreen.main.bounds.width
            return (effective - DarkroomDimens.gridGap * 2) / 3
        }

        // MARK: - UICollectionViewDelegate

        func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
            guard indexPath.section == Section.grid.rawValue, indexPath.item < parent.items.count else { return }
            parent.onTapItem(parent.items[indexPath.item])
        }

        func scrollViewDidScroll(_ scrollView: UIScrollView) {
            if !didInitialOffset && scrollView.bounds.height > 0 {
                didInitialOffset = true
            }
            if ScrollProbe.enabled {
                ScrollProbe.shared.offsetCallback()
            }
        }

        func scrollViewDidEndDragging(_ scrollView: UIScrollView, willDecelerate decelerate: Bool) {
            if !decelerate { resolveVisibleRawIfIdle() }
        }

        func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) {
            resolveVisibleRawIfIdle()
        }

        private func resolveVisibleRawIfIdle() {
            rawResolveTask?.cancel()
            guard let cv = collectionView else { return }
            let missing: [(id: String, asset: PHAsset)] = cv.indexPathsForVisibleItems.compactMap { ip in
                guard ip.section == Section.grid.rawValue, ip.item < parent.items.count else { return nil }
                let item = parent.items[ip.item]
                guard AssetSizeCache.shared.cachedOriginalFilename(item.id) == nil else { return nil }
                return (item.id, item.asset)
            }
            guard !missing.isEmpty else { return }
            rawResolveTask = Task { @MainActor [weak self] in
                let found = await Task.detached(priority: .utility) {
                    var out: [String] = []
                    for entry in missing {
                        if Task.isCancelled { break }
                        let info = AssetSizeCache.shared.info(for: entry.asset)
                        if ScopeRules.isRawFileName(info.originalFilename) {
                            out.append(entry.id)
                        }
                    }
                    return Set(out)
                }.value
                guard !Task.isCancelled, !found.isEmpty, let self, let cv = self.collectionView else { return }
                for cell in cv.visibleCells {
                    guard let tile = cell as? PhotoGridCell,
                          let id = tile.representedAssetId,
                          found.contains(id) else { continue }
                    tile.updateBottomLeft("RAW")
                }
            }
        }

        // MARK: - UICollectionViewDataSourcePrefetching

        func collectionView(_ collectionView: UICollectionView, prefetchItemsAt indexPaths: [IndexPath]) {
            let assets: [PHAsset] = indexPaths.compactMap { ip in
                guard ip.section == Section.grid.rawValue, ip.item < parent.items.count else { return nil }
                return parent.items[ip.item].asset
            }
            prefetch.startCaching(assets, side: tileSide(width: collectionView.bounds.width), style: .trashed)
        }

        func collectionView(_ collectionView: UICollectionView, cancelPrefetchingForItemsAt indexPaths: [IndexPath]) {
            let assets: [PHAsset] = indexPaths.compactMap { ip in
                guard ip.section == Section.grid.rawValue, ip.item < parent.items.count else { return nil }
                return parent.items[ip.item].asset
            }
            prefetch.stopCaching(assets)
        }
    }
}

struct TrashFreeCardView: View {
    let bytes: Int64
    let enabled: Bool
    let oldestDays: Int?
    let locale: Locale
    let onEmpty: () -> Void

    var body: some View {
        HStack(alignment: .center, spacing: 16) {
            VStack(alignment: .leading, spacing: 0) {
                Text(L10n.string("free_label", locale: locale))
                    .font(Font.custom("NotoSansSC-Regular", size: 12))
                    .foregroundStyle(DarkroomColors.dim)
                    .frame(height: 16)
                let parts = FileSizeFormatter.parts(bytes)
                HStack(alignment: .firstTextBaseline, spacing: 4) {
                    Text(parts.number)
                        .font(DarkroomFonts.schibsted(size: 32, weight: .semibold))
                        .tracking(-0.64)
                        .foregroundStyle(DarkroomColors.paper)
                        .monospacedDigit()
                    Text(parts.unit)
                        .font(Font.custom("NotoSansSC-Medium", size: 14))
                        .foregroundStyle(DarkroomColors.dim)
                }
                .frame(height: 36)
                .padding(.top, 4)
                Text(oldestDays.map { TrashCopy.oldestDays($0, locale: locale) } ?? L10n.string("free_sub", locale: locale))
                    .font(Font.custom("NotoSansSC-Regular", size: 11))
                    .foregroundStyle(DarkroomColors.faint)
                    .lineLimit(2)
                    .frame(minHeight: 16, alignment: .leading)
                    .padding(.top, 4)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            DeleteOutlineButton(title: L10n.string("empty_trash", locale: locale), enabled: enabled, action: onEmpty)
                .accessibilityIdentifier("trash-empty")
        }
        .padding(16)
        .background(DarkroomColors.tray, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(DarkroomColors.rim, lineWidth: 1))
        .padding(.horizontal, DarkroomDimens.screenSide)
        .padding(.top, 4)
        .padding(.bottom, 16)
    }
}

struct TrashSelectionSummaryView: View {
    let selectedBytes: Int64
    let locale: Locale

    var body: some View {
        HStack {
            Text(L10n.string("sel_sum_hint", locale: locale))
                .font(Font.custom("NotoSansSC-Regular", size: 12))
                .foregroundStyle(DarkroomColors.dim)
            Spacer()
            (Text(FileSizeFormatter.format(selectedBytes))
                .foregroundColor(DarkroomColors.paper)
                .font(DarkroomFonts.plexMono(size: 12, weight: .medium))
             + Text(L10n.string("sel_sum_back", locale: locale))
                .foregroundColor(DarkroomColors.dim)
                .font(DarkroomFonts.plexMono(size: 12)))
        }
        .padding(.horizontal, DarkroomDimens.screenSide)
        .frame(height: 40 - DarkroomDimens.gridGap)
        .padding(.bottom, DarkroomDimens.gridGap)
    }
}
