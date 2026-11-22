import Photos
import SwiftUI
import UIKit

/// 收藏页 4 列 UICollectionView：待整理分区（年份 / 日期头 + 「整理」胶囊 / 4 列照片）+ 已冲洗分组（分隔行 / 可展开只读行）。
struct FavoritesGridCollectionView: UIViewRepresentable {
    let sections: [PhotoYearGroup]
    let filedDays: [FiledDayRules.Day]
    let expandedFiled: String?
    let filedPhotosByDay: [String: [PhotoItem]]
    let initialIDs: Set<String>
    let chinese: Bool
    let locale: Locale
    let pipeline: ThumbnailPipeline
    let onOpenPhoto: (String) -> Void
    let onOpenSheet: (String) -> Void
    let onToggleFiled: (String) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(parent: self)
    }

    func makeUIView(context: Context) -> UICollectionView {
        let coordinator = context.coordinator
        let cv = UICollectionView(frame: .zero, collectionViewLayout: coordinator.makeLayout())
        cv.backgroundColor = DarkroomUIColors.room
        cv.showsVerticalScrollIndicator = false
        cv.alwaysBounceVertical = true
        cv.contentInsetAdjustmentBehavior = .never
        cv.contentInset = UIEdgeInsets(
            top: DarkroomDimens.headerHeight + 8,
            left: 0,
            bottom: 174,
            right: 0
        )
        cv.register(DarkroomDividerCell.self, forCellWithReuseIdentifier: DarkroomDividerCell.reuseIdentifier)
        cv.register(FavoriteDayHeaderCell.self, forCellWithReuseIdentifier: FavoriteDayHeaderCell.reuseIdentifier)
        cv.register(FiledDayRowCell.self, forCellWithReuseIdentifier: FiledDayRowCell.reuseIdentifier)
        cv.register(PhotoGridCell.self, forCellWithReuseIdentifier: PhotoGridCell.reuseIdentifier)
        cv.delegate = coordinator
        cv.prefetchDataSource = coordinator
        coordinator.collectionView = cv
        coordinator.configureDataSource(cv)
        coordinator.applySnapshot(from: self, animated: false)
        coordinator.registerSharedTransitionProvider()
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
        enum SectionKind: Hashable {
            case divider(String)
            case dayHeader(String)
            case dayGrid(String)
            case filedHeader
            case filedRow(String)
            case filedGrid(String)
        }

        enum RowItem: Hashable {
            case year(Int)
            case dayHeader(String)
            case photo(dayId: String, photoId: String)
            case filedHeader
            case filedRow(String)
            case filedPhoto(dayKey: String, photoId: String)
        }

        var parent: FavoritesGridCollectionView
        weak var collectionView: UICollectionView?
        private var dataSource: UICollectionViewDiffableDataSource<SectionKind, RowItem>?
        private let prefetch: ThumbnailPrefetchWindow
        private var sectionKinds: [SectionKind] = []
        private var dayHeadersById: [String: PhotoDaySection] = [:]
        private var photosById: [String: PhotoItem] = [:]
        private var photoIndexInDay: [String: Int] = [:]
        private var filedByDayKey: [String: FiledDayRules.Day] = [:]
        private var filedTotal = 0
        private var didInitialOffset = false
        private var rawResolveTask: Task<Void, Never>?

        private var lastSectionSignature: [String] = []
        private var lastExpandedFiled: String?
        private var lastLocaleId = ""
        private var lastChinese = true

        init(parent: FavoritesGridCollectionView) {
            self.parent = parent
            self.prefetch = ThumbnailPrefetchWindow(pipeline: parent.pipeline)
        }

        func registerSharedTransitionProvider() {
            PhotoOpenTransition.shared.registerProvider(
                key: "favorites",
                frame: { [weak self] id in
                    guard let self, let cv = self.collectionView, cv.window != nil else { return nil }
                    for case let cell as PhotoGridCell in cv.visibleCells where cell.representedAssetId == id {
                        return cell.convert(cell.bounds, to: nil)
                    }
                    return nil
                },
                image: { [weak self] id in
                    guard let self, let cv = self.collectionView, cv.window != nil else { return nil }
                    for case let cell as PhotoGridCell in cv.visibleCells where cell.representedAssetId == id {
                        if let img = cell.currentImage() { return img }
                    }
                    let side = self.tileSide(width: cv.bounds.width)
                    return self.parent.pipeline.cached(id: id, side: side, style: .normal)
                },
                onFlyingChange: { [weak self] flyingId in
                    guard let cv = self?.collectionView else { return }
                    for case let cell as PhotoGridCell in cv.visibleCells {
                        let hidden = (flyingId != nil && cell.representedAssetId == flyingId)
                        cell.contentView.alpha = hidden ? 0 : 1
                    }
                }
            )
        }

        func teardown() {
            rawResolveTask?.cancel()
            rawResolveTask = nil
            PhotoOpenTransition.shared.unregisterProvider(key: "favorites")
            prefetch.reset()
        }

        func makeLayout() -> UICollectionViewCompositionalLayout {
            UICollectionViewCompositionalLayout { [weak self] sectionIndex, env in
                guard let self, self.sectionKinds.indices.contains(sectionIndex) else { return nil }
                switch self.sectionKinds[sectionIndex] {
                case .divider:
                    let size = NSCollectionLayoutSize(widthDimension: .fractionalWidth(1.0), heightDimension: .absolute(32))
                    let item = NSCollectionLayoutItem(layoutSize: size)
                    let group = NSCollectionLayoutGroup.vertical(layoutSize: size, subitems: [item])
                    return NSCollectionLayoutSection(group: group)
                case .filedHeader:
                    let size = NSCollectionLayoutSize(widthDimension: .fractionalWidth(1.0), heightDimension: .absolute(44))
                    let item = NSCollectionLayoutItem(layoutSize: size)
                    let group = NSCollectionLayoutGroup.vertical(layoutSize: size, subitems: [item])
                    return NSCollectionLayoutSection(group: group)
                case .dayHeader:
                    let size = NSCollectionLayoutSize(widthDimension: .fractionalWidth(1.0), heightDimension: .absolute(58))
                    let item = NSCollectionLayoutItem(layoutSize: size)
                    let group = NSCollectionLayoutGroup.vertical(layoutSize: size, subitems: [item])
                    return NSCollectionLayoutSection(group: group)
                case .filedRow:
                    let size = NSCollectionLayoutSize(widthDimension: .fractionalWidth(1.0), heightDimension: .estimated(48))
                    let item = NSCollectionLayoutItem(layoutSize: size)
                    let group = NSCollectionLayoutGroup.vertical(layoutSize: size, subitems: [item])
                    return NSCollectionLayoutSection(group: group)
                case .dayGrid, .filedGrid:
                    let cols = 4
                    let gap = DarkroomDimens.gridGap
                    let width = env.container.effectiveContentSize.width
                    let side = (max(width, 1) - gap * CGFloat(cols - 1)) / CGFloat(cols)
                    let itemSize = NSCollectionLayoutSize(widthDimension: .absolute(side), heightDimension: .absolute(side))
                    let item = NSCollectionLayoutItem(layoutSize: itemSize)
                    let groupSize = NSCollectionLayoutSize(widthDimension: .fractionalWidth(1.0), heightDimension: .absolute(side))
                    let group = NSCollectionLayoutGroup.horizontal(layoutSize: groupSize, repeatingSubitem: item, count: cols)
                    group.interItemSpacing = .fixed(gap)
                    let sec = NSCollectionLayoutSection(group: group)
                    sec.interGroupSpacing = gap
                    sec.contentInsets = NSDirectionalEdgeInsets(top: 0, leading: 0, bottom: gap, trailing: 0)
                    return sec
                }
            }
        }

        func configureDataSource(_ cv: UICollectionView) {
            dataSource = UICollectionViewDiffableDataSource<SectionKind, RowItem>(collectionView: cv) { [weak self] cv, indexPath, row in
                guard let self else { return UICollectionViewCell() }
                switch row {
                case .year(let year):
                    let cell = cv.dequeueReusableCell(withReuseIdentifier: DarkroomDividerCell.reuseIdentifier, for: indexPath) as! DarkroomDividerCell
                    cell.configure(title: String(year), topPadding: 0)
                    return cell
                case .filedHeader:
                    let cell = cv.dequeueReusableCell(withReuseIdentifier: DarkroomDividerCell.reuseIdentifier, for: indexPath) as! DarkroomDividerCell
                    cell.configure(title: FilingCopy.filedHeader(self.filedTotal, locale: self.parent.locale), topPadding: 12)
                    return cell
                case .dayHeader(let dayId):
                    let cell = cv.dequeueReusableCell(withReuseIdentifier: FavoriteDayHeaderCell.reuseIdentifier, for: indexPath) as! FavoriteDayHeaderCell
                    if let day = self.dayHeadersById[dayId] {
                        cell.configure(day: day, chinese: self.parent.chinese, locale: self.parent.locale)
                    }
                    return cell
                case .filedRow(let dayKey):
                    let cell = cv.dequeueReusableCell(withReuseIdentifier: FiledDayRowCell.reuseIdentifier, for: indexPath) as! FiledDayRowCell
                    if let day = self.filedByDayKey[dayKey] {
                        cell.configure(day: day, open: self.parent.expandedFiled == dayKey, chinese: self.parent.chinese, locale: self.parent.locale)
                    }
                    return cell
                case .photo(let dayId, let photoId):
                    let cell = cv.dequeueReusableCell(withReuseIdentifier: PhotoGridCell.reuseIdentifier, for: indexPath) as! PhotoGridCell
                    if let item = self.photosById[photoId] {
                        let width = cv.bounds.width
                        let side = self.tileSide(width: width)
                        let idx = self.photoIndexInDay[dayId + "\n" + photoId] ?? indexPath.item
                        let develop = self.parent.initialIDs.contains(photoId)
                            && GridDevelopSession.shouldDevelop(grid: .favorites, id: photoId, isInitialPass: false)
                        let delay = GridDevelopSession.staggerDelay(row: idx / 4, col: idx % 4)
                        cell.configure(self.photoConfig(item: item, dayId: dayId, index: idx, side: side, develop: develop, delay: delay, interactive: true),
                                       pipeline: self.parent.pipeline, animated: false)
                        cell.contentView.alpha = PhotoOpenTransition.shared.flyingId == photoId ? 0 : 1
                    }
                    return cell
                case .filedPhoto(_, let photoId):
                    let cell = cv.dequeueReusableCell(withReuseIdentifier: PhotoGridCell.reuseIdentifier, for: indexPath) as! PhotoGridCell
                    if let item = self.photosById[photoId] {
                        let side = self.tileSide(width: cv.bounds.width)
                        cell.configure(self.photoConfig(item: item, dayId: "filed", index: indexPath.item, side: side, develop: false, delay: 0, interactive: false),
                                       pipeline: self.parent.pipeline, animated: false)
                        cell.contentView.alpha = 1
                    }
                    return cell
                }
            }
        }

        func update(with newParent: FavoritesGridCollectionView, collectionView cv: UICollectionView) {
            let expandedChanged = newParent.expandedFiled != lastExpandedFiled
            let localeChanged = newParent.locale.identifier != lastLocaleId || newParent.chinese != lastChinese
            let sig = signature(for: newParent)
            let dataChanged = sig != lastSectionSignature

            parent = newParent

            if !didInitialOffset && cv.bounds.height > 0 {
                didInitialOffset = true
                cv.setContentOffset(CGPoint(x: 0, y: -cv.contentInset.top), animated: false)
            }

            if dataChanged || expandedChanged {
                applySnapshot(from: newParent, animated: expandedChanged && !dataChanged && !UIAccessibility.isReduceMotionEnabled)
                return
            }

            if localeChanged {
                lastLocaleId = newParent.locale.identifier
                lastChinese = newParent.chinese
                var snap = dataSource?.snapshot() ?? NSDiffableDataSourceSnapshot<SectionKind, RowItem>()
                let headers = snap.itemIdentifiers.filter {
                    switch $0 {
                    case .dayHeader, .filedHeader, .filedRow: return true
                    case .year, .photo, .filedPhoto: return false
                    }
                }
                if !headers.isEmpty {
                    snap.reconfigureItems(headers)
                    dataSource?.apply(snap, animatingDifferences: false)
                }
            }
        }

        func applySnapshot(from source: FavoritesGridCollectionView, animated: Bool) {
            parent = source
            lastSectionSignature = signature(for: source)
            lastExpandedFiled = source.expandedFiled
            lastLocaleId = source.locale.identifier
            lastChinese = source.chinese

            dayHeadersById.removeAll(keepingCapacity: true)
            photosById.removeAll(keepingCapacity: true)
            photoIndexInDay.removeAll(keepingCapacity: true)
            filedByDayKey.removeAll(keepingCapacity: true)
            sectionKinds.removeAll(keepingCapacity: true)

            var snapshot = NSDiffableDataSourceSnapshot<SectionKind, RowItem>()

            for year in source.sections {
                if year.year > 0 {
                    let sec = SectionKind.divider(year.id)
                    sectionKinds.append(sec)
                    snapshot.appendSections([sec])
                    snapshot.appendItems([.year(year.year)], toSection: sec)
                }
                for day in year.days {
                    dayHeadersById[day.id] = day
                    let hSec = SectionKind.dayHeader(day.id)
                    sectionKinds.append(hSec)
                    snapshot.appendSections([hSec])
                    snapshot.appendItems([.dayHeader(day.id)], toSection: hSec)

                    let gSec = SectionKind.dayGrid(day.id)
                    sectionKinds.append(gSec)
                    snapshot.appendSections([gSec])
                    var rowItems: [RowItem] = []
                    rowItems.reserveCapacity(day.items.count)
                    for (idx, item) in day.items.enumerated() {
                        photosById[item.id] = item
                        photoIndexInDay[day.id + "\n" + item.id] = idx
                        rowItems.append(.photo(dayId: day.id, photoId: item.id))
                    }
                    snapshot.appendItems(rowItems, toSection: gSec)
                }
            }

            if !source.filedDays.isEmpty {
                filedTotal = source.filedDays.reduce(0) { $0 + $1.count }
                sectionKinds.append(.filedHeader)
                snapshot.appendSections([.filedHeader])
                snapshot.appendItems([.filedHeader], toSection: .filedHeader)

                for day in source.filedDays {
                    filedByDayKey[day.dayKey] = day
                    let rSec = SectionKind.filedRow(day.dayKey)
                    sectionKinds.append(rSec)
                    snapshot.appendSections([rSec])
                    snapshot.appendItems([.filedRow(day.dayKey)], toSection: rSec)

                    if source.expandedFiled == day.dayKey, let list = source.filedPhotosByDay[day.dayKey], !list.isEmpty {
                        let fgSec = SectionKind.filedGrid(day.dayKey)
                        sectionKinds.append(fgSec)
                        snapshot.appendSections([fgSec])
                        var rowItems: [RowItem] = []
                        rowItems.reserveCapacity(list.count)
                        for item in list {
                            photosById[item.id] = item
                            rowItems.append(.filedPhoto(dayKey: day.dayKey, photoId: item.id))
                        }
                        snapshot.appendItems(rowItems, toSection: fgSec)
                    }
                }
            } else {
                filedTotal = 0
            }

            dataSource?.apply(snapshot, animatingDifferences: animated) { [weak self] in
                guard let self, let cv = self.collectionView else { return }
                if !self.didInitialOffset && cv.bounds.height > 0 {
                    self.didInitialOffset = true
                    cv.setContentOffset(CGPoint(x: 0, y: -cv.contentInset.top), animated: false)
                }
                for ip in cv.indexPathsForVisibleItems {
                    if case .filedRow(let dayKey) = self.dataSource?.itemIdentifier(for: ip),
                       let cell = cv.cellForItem(at: ip) as? FiledDayRowCell,
                       let day = self.filedByDayKey[dayKey] {
                        cell.configure(day: day, open: self.parent.expandedFiled == dayKey, chinese: self.parent.chinese, locale: self.parent.locale)
                    }
                }
                var warmAssets: [PHAsset] = []
                warmAssets.reserveCapacity(48)
                outer: for year in self.parent.sections {
                    for day in year.days {
                        for item in day.items {
                            warmAssets.append(item.asset)
                            if warmAssets.count >= 48 { break outer }
                        }
                    }
                }
                self.prefetch.warmInitial(warmAssets, side: self.tileSide(width: cv.bounds.width), style: .normal, limit: 48)
                self.resolveVisibleRawIfIdle()
            }
        }

        private func signature(for source: FavoritesGridCollectionView) -> [String] {
            var sig: [String] = []
            for y in source.sections {
                sig.append("y:\(y.id)")
                for d in y.days {
                    sig.append("d:\(d.id):\(d.totalCount):\(d.items.map(\.id).joined(separator: ","))")
                }
            }
            for f in source.filedDays {
                sig.append("f:\(f.dayKey):\(f.count):\(f.albums.map { "\($0.name)+\($0.count)" }.joined(separator: ","))")
            }
            return sig
        }

        private func photoConfig(
            item: PhotoItem,
            dayId: String,
            index: Int,
            side: CGFloat,
            develop: Bool,
            delay: Double,
            interactive: Bool
        ) -> PhotoGridCellConfig {
            let rawName = AssetSizeCache.shared.cachedOriginalFilename(item.id)
            let rawText = ScopeRules.isRawFileName(rawName) ? "RAW" : nil
            let durationText = item.isVideo ? ScopeRules.formatDuration(seconds: item.duration) : nil
            return PhotoGridCellConfig(
                item: item,
                side: side,
                style: .normal,
                bottomLeftText: rawText,
                bottomRightText: durationText,
                showUnseen: false,
                selecting: false,
                selected: false,
                circled: false,
                develop: develop,
                developDelay: delay,
                interactive: interactive,
                locale: parent.locale,
                accessibilityId: interactive ? "tile-\(index)-\(dayId)" : nil
            )
        }

        private func tileSide(width: CGFloat) -> CGFloat {
            let effective = width > 1 ? width : UIScreen.main.bounds.width
            return (effective - DarkroomDimens.gridGap * 3) / 4
        }

        // MARK: - UICollectionViewDelegate

        func collectionView(_ collectionView: UICollectionView, shouldSelectItemAt indexPath: IndexPath) -> Bool {
            guard let item = dataSource?.itemIdentifier(for: indexPath) else { return false }
            switch item {
            case .dayHeader, .photo, .filedRow:
                return true
            case .year, .filedHeader, .filedPhoto:
                return false
            }
        }

        func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
            guard let item = dataSource?.itemIdentifier(for: indexPath) else { return }
            switch item {
            case .dayHeader(let dayId):
                parent.onOpenSheet(dayId)
            case .photo(_, let photoId):
                parent.onOpenPhoto(photoId)
            case .filedRow(let dayKey):
                parent.onToggleFiled(dayKey)
            case .year, .filedHeader, .filedPhoto:
                break
            }
        }

        func scrollViewDidScroll(_ scrollView: UIScrollView) {
            if !didInitialOffset && scrollView.bounds.height > 0 {
                didInitialOffset = true
            }
            if scrollView.isDragging || scrollView.isDecelerating || scrollView.contentOffset.y > -scrollView.contentInset.top + 8 {
                GridDevelopSession.markPlayed(.favorites)
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
            guard let cv = collectionView, let ds = dataSource else { return }
            let missing: [(id: String, asset: PHAsset)] = cv.indexPathsForVisibleItems.compactMap { ip in
                guard let row = ds.itemIdentifier(for: ip) else { return nil }
                let pid: String
                switch row {
                case .photo(_, let photoId), .filedPhoto(_, let photoId): pid = photoId
                default: return nil
                }
                guard let item = photosById[pid],
                      AssetSizeCache.shared.cachedOriginalFilename(item.id) == nil else { return nil }
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
                for case let tile as PhotoGridCell in cv.visibleCells {
                    guard let id = tile.representedAssetId, found.contains(id) else { continue }
                    tile.updateBottomLeft("RAW")
                }
            }
        }

        // MARK: - UICollectionViewDataSourcePrefetching

        func collectionView(_ collectionView: UICollectionView, prefetchItemsAt indexPaths: [IndexPath]) {
            guard let ds = dataSource else { return }
            let assets: [PHAsset] = indexPaths.compactMap { ip in
                switch ds.itemIdentifier(for: ip) {
                case .photo(_, let id), .filedPhoto(_, let id): return photosById[id]?.asset
                default: return nil
                }
            }
            prefetch.startCaching(assets, side: tileSide(width: collectionView.bounds.width), style: .normal)
        }

        func collectionView(_ collectionView: UICollectionView, cancelPrefetchingForItemsAt indexPaths: [IndexPath]) {
            guard let ds = dataSource else { return }
            let assets: [PHAsset] = indexPaths.compactMap { ip in
                switch ds.itemIdentifier(for: ip) {
                case .photo(_, let id), .filedPhoto(_, let id): return photosById[id]?.asset
                default: return nil
                }
            }
            prefetch.stopCaching(assets)
        }
    }
}

/// 年份 / 已冲洗分隔行：左标题 + 右侧 1pt 细线（纯 frame 布局，零约束解算）。
final class DarkroomDividerCell: UICollectionViewCell {
    static let reuseIdentifier = "DarkroomDividerCell"

    private let titleLabel: UILabel = {
        let l = UILabel()
        l.font = DarkroomUIFonts.plexMono(size: 11)
        l.textColor = DarkroomUIColors.dim
        l.numberOfLines = 1
        return l
    }()

    private let lineView: UIView = {
        let v = UIView()
        v.backgroundColor = DarkroomUIColors.rim
        return v
    }()

    private var topPadding: CGFloat = 0
    private var currentTitle: String?

    override init(frame: CGRect) {
        super.init(frame: frame)
        contentView.backgroundColor = .clear
        contentView.addSubview(titleLabel)
        contentView.addSubview(lineView)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func preferredLayoutAttributesFitting(_ layoutAttributes: UICollectionViewLayoutAttributes) -> UICollectionViewLayoutAttributes {
        layoutAttributes
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let b = contentView.bounds
        let availH = max(0, b.height - topPadding)
        let maxLabelW = max(0, b.width - 32 - 20)
        let labelSize = titleLabel.sizeThatFits(CGSize(width: maxLabelW, height: availH))
        let lw = min(maxLabelW, ceil(labelSize.width))
        titleLabel.frame = CGRect(x: 16, y: topPadding, width: lw, height: availH)
        let lineX = 16 + lw + 12
        let lineW = max(0, b.width - 16 - lineX)
        lineView.frame = CGRect(x: lineX, y: topPadding + (availH - 1) / 2, width: lineW, height: 1)
    }

    func configure(title: String, topPadding: CGFloat) {
        guard title != currentTitle || abs(topPadding - self.topPadding) > 0.5 else { return }
        currentTitle = title
        self.topPadding = topPadding
        titleLabel.text = title
        setNeedsLayout()
    }
}

/// 收藏页待整理天头：日号 + 月·星期 + 张数 + 「整理」玻璃胶囊（纯 frame 布局 + 进程级缓存图标）。
final class FavoriteDayHeaderCell: UICollectionViewCell {
    static let reuseIdentifier = "FavoriteDayHeaderCell"

    private static let pillIconImage = UIImage(
        systemName: "film",
        withConfiguration: UIImage.SymbolConfiguration(pointSize: 11, weight: .medium)
    )

    private let dayNumLabel: UILabel = {
        let l = UILabel()
        l.font = DarkroomUIFonts.schibsted(size: 24, weight: .semibold)
        l.textColor = DarkroomUIColors.paper
        return l
    }()

    private let metaLabel: UILabel = {
        let l = UILabel()
        l.font = DarkroomUIFonts.notoSC(size: 12)
        l.textColor = DarkroomUIColors.dim
        l.lineBreakMode = .byTruncatingTail
        return l
    }()

    private let countLabel: UILabel = {
        let l = UILabel()
        l.font = DarkroomUIFonts.plexMono(size: 11)
        l.textColor = DarkroomUIColors.dim
        return l
    }()

    private let pillView: UIView = {
        let v = UIView()
        v.backgroundColor = DarkroomUIColors.tray
        v.layer.cornerRadius = 16
        v.layer.borderWidth = 1
        v.layer.borderColor = DarkroomUIColors.rim2.cgColor
        v.isUserInteractionEnabled = false
        return v
    }()

    private let pillIcon: UIImageView = {
        let iv = UIImageView(image: FavoriteDayHeaderCell.pillIconImage)
        iv.tintColor = DarkroomUIColors.paper
        iv.contentMode = .center
        return iv
    }()

    private let pillLabel: UILabel = {
        let l = UILabel()
        l.font = DarkroomUIFonts.notoSC(size: 12, weight: .medium)
        l.textColor = DarkroomUIColors.paper
        return l
    }()

    private var currentNum: String?
    private var currentMeta: String?
    private var currentCount: String?
    private var currentPill: String?

    override init(frame: CGRect) {
        super.init(frame: frame)
        contentView.backgroundColor = .clear
        contentView.addSubview(dayNumLabel)
        contentView.addSubview(metaLabel)
        contentView.addSubview(countLabel)
        pillView.addSubview(pillIcon)
        pillView.addSubview(pillLabel)
        contentView.addSubview(pillView)

        isAccessibilityElement = true
        accessibilityTraits = .button
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func preferredLayoutAttributesFitting(_ layoutAttributes: UICollectionViewLayoutAttributes) -> UICollectionViewLayoutAttributes {
        layoutAttributes
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let b = contentView.bounds
        let w = b.width
        let h = b.height
        let topPad: CGFloat = 16
        let bottomPad: CGFloat = 10
        let availH = max(0, h - topPad - bottomPad)
        let cy = topPad + availH / 2

        let iconSize = pillIcon.sizeThatFits(CGSize(width: 24, height: 24))
        let iconW = ceil(iconSize.width)
        let iconH = ceil(iconSize.height)
        let pillTextSize = pillLabel.sizeThatFits(CGSize(width: 140, height: 32))
        let pillTextW = ceil(pillTextSize.width)
        let pillTextH = ceil(pillTextSize.height)
        let pillW = 10 + iconW + 6 + pillTextW + 12
        let pillH: CGFloat = 32
        let pillX = w - 16 - pillW
        pillView.frame = CGRect(x: pillX, y: cy - pillH / 2, width: pillW, height: pillH)
        pillIcon.frame = CGRect(x: 10, y: (pillH - iconH) / 2, width: iconW, height: iconH)
        pillLabel.frame = CGRect(x: 10 + iconW + 6, y: (pillH - pillTextH) / 2, width: pillTextW, height: pillTextH)

        let countSize = countLabel.sizeThatFits(CGSize(width: max(0, pillX - 24), height: availH))
        let countW = ceil(countSize.width)
        let countH = ceil(countSize.height)
        let countX = pillX - 10 - countW
        countLabel.frame = CGRect(x: countX, y: cy - countH / 2, width: countW, height: countH)

        let maxLeftEnd = countX - 8
        let numSize = dayNumLabel.sizeThatFits(CGSize(width: max(0, maxLeftEnd - 16), height: availH))
        let numW = min(max(0, maxLeftEnd - 16), ceil(numSize.width))
        let numH = ceil(numSize.height)
        dayNumLabel.frame = CGRect(x: 16, y: cy - numH / 2, width: numW, height: numH)

        let metaX: CGFloat = 16 + numW + 10
        let maxMetaW = max(0, maxLeftEnd - metaX)
        let metaSize = metaLabel.sizeThatFits(CGSize(width: maxMetaW, height: availH))
        let metaW = min(maxMetaW, ceil(metaSize.width))
        let metaH = ceil(metaSize.height)
        metaLabel.frame = CGRect(x: metaX, y: cy - metaH / 2, width: metaW, height: metaH)
    }

    func configure(day: PhotoDaySection, chinese: Bool, locale: Locale) {
        let num = day.id == DayKeyRule.unknown ? "—" : DateFormatCache.string(day.date, format: "d", locale: locale)
        let meta = day.id == DayKeyRule.unknown
            ? L10n.string("date_unknown", locale: locale)
            : DateFormatCache.string(day.date, format: chinese ? "M月 · EEE" : "MMM · EEE", locale: locale)
        let count = L10n.plural("favorites_section_count", count: day.totalCount, locale: locale)
        let pill = FilingCopy.sortDay(locale)

        guard num != currentNum || meta != currentMeta || count != currentCount || pill != currentPill else { return }
        currentNum = num
        currentMeta = meta
        currentCount = count
        currentPill = pill

        dayNumLabel.text = num
        metaLabel.text = meta
        countLabel.text = count
        pillLabel.text = pill

        accessibilityLabel = "\(num) \(meta), \(count)"
        accessibilityHint = pill
        setNeedsLayout()
    }
}

/// 收藏页「已冲洗」折叠天行：日期 + 最多两枚牛皮纸标签 + 展开箭头。
final class FiledDayRowCell: UICollectionViewCell {
    static let reuseIdentifier = "FiledDayRowCell"

    private let dayNumLabel: UILabel = {
        let l = UILabel()
        l.font = DarkroomUIFonts.schibsted(size: 24, weight: .semibold)
        l.textColor = DarkroomUIColors.paper
        return l
    }()

    private let metaLabel: UILabel = {
        let l = UILabel()
        l.font = DarkroomUIFonts.notoSC(size: 12)
        l.textColor = DarkroomUIColors.dim
        l.lineBreakMode = .byTruncatingTail
        return l
    }()

    private let chipsStack: UIStackView = {
        let s = UIStackView()
        s.axis = .horizontal
        s.spacing = 6
        s.alignment = .center
        return s
    }()

    private let chevronView: UIImageView = {
        let cfg = UIImage.SymbolConfiguration(pointSize: 12, weight: .semibold)
        let iv = UIImageView(image: UIImage(systemName: "chevron.right", withConfiguration: cfg))
        iv.tintColor = DarkroomUIColors.dim
        iv.contentMode = .center
        return iv
    }()

    override init(frame: CGRect) {
        super.init(frame: frame)
        contentView.backgroundColor = .clear

        let spacer = UIView()
        spacer.setContentHuggingPriority(.defaultLow, for: .horizontal)

        let row = UIStackView(arrangedSubviews: [dayNumLabel, metaLabel, spacer, chipsStack, chevronView])
        row.axis = .horizontal
        row.alignment = .center
        row.spacing = 8
        row.setCustomSpacing(10, after: dayNumLabel)
        row.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(row)

        NSLayoutConstraint.activate([
            row.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 16),
            row.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -16),
            row.topAnchor.constraint(equalTo: contentView.topAnchor),
            row.bottomAnchor.constraint(equalTo: contentView.bottomAnchor),
            row.heightAnchor.constraint(greaterThanOrEqualToConstant: 48),
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func configure(day: FiledDayRules.Day, open: Bool, chinese: Bool, locale: Locale) {
        let date = NextDayRule.date(from: day.dayKey)
        dayNumLabel.text = date.map { DateFormatCache.string($0, format: "d", locale: locale) } ?? "—"
        metaLabel.text = date.map {
            DateFormatCache.string($0, format: chinese ? "M月 · EEE" : "MMM · EEE", locale: locale)
        } ?? L10n.string("date_unknown", locale: locale)

        chipsStack.arrangedSubviews.forEach {
            chipsStack.removeArrangedSubview($0)
            $0.removeFromSuperview()
        }
        for chip in day.albums.prefix(2) {
            let label = PaddingLabel()
            label.text = FilingCopy.chip(chip.name, count: chip.count)
            label.font = DarkroomUIFonts.notoSC(size: 11, weight: .medium)
            label.textColor = DarkroomUIColors.paper
            label.backgroundColor = DarkroomUIColors.tray2
            label.layer.cornerRadius = 3
            label.layer.cornerCurve = .continuous
            label.clipsToBounds = true
            label.horizontalPadding = 8
            label.heightAnchor.constraint(equalToConstant: 22).isActive = true
            chipsStack.addArrangedSubview(label)
        }

        chevronView.transform = open ? CGAffineTransform(rotationAngle: .pi / 2) : .identity
    }
}

private final class PaddingLabel: UILabel {
    var horizontalPadding: CGFloat = 0

    override var intrinsicContentSize: CGSize {
        let s = super.intrinsicContentSize
        return CGSize(width: s.width + horizontalPadding * 2, height: s.height)
    }

    override func drawText(in rect: CGRect) {
        super.drawText(in: rect.insetBy(dx: horizontalPadding, dy: 0))
    }
}
