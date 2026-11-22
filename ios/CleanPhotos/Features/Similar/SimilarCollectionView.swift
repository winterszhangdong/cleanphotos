import Combine
import Photos
import SwiftUI
import UIKit

/// 相似范围页面：扫描与空态显示轻量 SwiftUI 状态页；就绪且有分组时切换为纯 UIKit 竖向 UICollectionView（无毛玻璃，横向缩略条直连 ThumbnailPipeline）。
@MainActor
final class SimilarPageView: UIView, UICollectionViewDataSource, UICollectionViewDelegateFlowLayout, UICollectionViewDataSourcePrefetching {
    private let session = SimilarSession.shared
    private let pipeline: ThumbnailPipeline
    private let prefetch: ThumbnailPrefetchWindow
    private var cancellables: Set<AnyCancellable> = []

    private var zh: Bool
    private var locale: Locale
    private var chromeHeight: CGFloat
    private var photosVisible: Bool
    private var isActiveScope: Bool

    private var cards: [SimilarCard] = []
    private var marksByGroup: [String: [String: SimilarMark]] = [:]
    private var isMoving = false
    private var lastCardIds: [String] = []
    private var lastCardPhotoIds: [[String]] = []
    private var lastWidth: CGFloat = 0

    private let collectionView: UICollectionView
    private let flowLayout = UICollectionViewFlowLayout()
    private let statusModel = SimilarStatusModel()
    private lazy var statusHost: UIHostingController<SimilarStatusContainerView> = {
        let vc = UIHostingController(rootView: SimilarStatusContainerView(model: statusModel))
        vc.view.backgroundColor = .clear
        return vc
    }()

    private var rawResolveTask: Task<Void, Never>?
    private var revealAnimator: UIViewPropertyAnimator?
    private var programmaticScroll = false
    private var lastReportedCanScroll: Bool?

    var onOpenPhoto: ((_ groupKey: String, _ photoId: String) -> Void)?
    var onScrollState: ((_ y: CGFloat, _ moving: Bool, _ programmatic: Bool, _ canScroll: Bool) -> Void)?
    var onScrollSettled: (() -> Void)?

    init(
        pipeline: ThumbnailPipeline,
        zh: Bool,
        locale: Locale,
        chromeHeight: CGFloat,
        photosVisible: Bool,
        isActiveScope: Bool
    ) {
        self.pipeline = pipeline
        self.prefetch = ThumbnailPrefetchWindow(pipeline: pipeline)
        self.zh = zh
        self.locale = locale
        self.chromeHeight = chromeHeight
        self.photosVisible = photosVisible
        self.isActiveScope = isActiveScope

        flowLayout.scrollDirection = .vertical
        flowLayout.minimumLineSpacing = 8
        flowLayout.minimumInteritemSpacing = 0

        collectionView = UICollectionView(frame: .zero, collectionViewLayout: flowLayout)
        super.init(frame: .zero)

        backgroundColor = DarkroomUIColors.room

        collectionView.backgroundColor = DarkroomUIColors.room
        collectionView.showsVerticalScrollIndicator = false
        collectionView.alwaysBounceVertical = true
        collectionView.contentInsetAdjustmentBehavior = .never
        collectionView.register(SimilarCardCell.self, forCellWithReuseIdentifier: SimilarCardCell.reuseIdentifier)
        collectionView.dataSource = self
        collectionView.delegate = self
        collectionView.prefetchDataSource = self
        addSubview(collectionView)

        statusHost.view.frame = bounds
        addSubview(statusHost.view)

        bindSession()
        syncFromSession()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    deinit {
        rawResolveTask?.cancel()
    }

    func teardown() {
        cancellables.removeAll()
        rawResolveTask?.cancel()
        rawResolveTask = nil
        revealAnimator?.stopAnimation(true)
        revealAnimator = nil
        prefetch.reset()
    }

    func update(zh: Bool, locale: Locale, chromeHeight: CGFloat, photosVisible: Bool, isActiveScope: Bool) {
        let localeChanged = self.zh != zh || self.locale.identifier != locale.identifier
        let chromeChanged = abs(self.chromeHeight - chromeHeight) > 0.5
        let wasActive = self.isActiveScope
        let wasPhotosVisible = self.photosVisible
        let showedList = !collectionView.isHidden
        self.zh = zh
        self.locale = locale
        self.chromeHeight = chromeHeight
        self.photosVisible = photosVisible
        self.isActiveScope = isActiveScope
        if wasActive && showedList && (!isActiveScope || (wasPhotosVisible && !photosVisible)) {
            GridDevelopSession.markPlayed(.similar)
        }

        if isActiveScope && ScrollProbe.enabled {
            ScrollProbe.shared.page = "photos-similar"
        }

        updateLayoutInsetsIfNeeded()
        updateStatusModel()

        if (localeChanged || chromeChanged) && !collectionView.isHidden {
            refreshVisibleCards()
        }
        if isActiveScope && !collectionView.isHidden {
            resolveVisibleRawIfIdle()
        }
    }

    func currentOffsetY() -> CGFloat {
        collectionView.contentOffset.y
    }

    func canScrollNow() -> Bool {
        guard !collectionView.isHidden else { return false }
        let h = collectionView.bounds.height
        return h > 0 && collectionView.contentSize.height > h + 1
    }

    func setScrollOffset(_ y: CGFloat, programmatic: Bool) {
        guard !collectionView.isHidden else { return }
        let maxY = max(0, collectionView.contentSize.height - max(collectionView.bounds.height, UIScreen.main.bounds.height))
        let target = min(maxY, max(0, y))
        programmaticScroll = programmatic
        collectionView.setContentOffset(CGPoint(x: 0, y: target), animated: false)
        programmaticScroll = false
        resolveVisibleRawIfIdle()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let b = bounds
        if collectionView.frame != b {
            collectionView.frame = b
        }
        if statusHost.view.frame != b {
            statusHost.view.frame = b
        }
        if b.width > 1 && abs(b.width - lastWidth) > 0.5 {
            lastWidth = b.width
            updateLayoutMetrics()
        }
    }

    // MARK: Session Binding

    private func bindSession() {
        session.$phase
            .sink { [weak self] _ in
                Task { @MainActor in self?.syncFromSession() }
            }
            .store(in: &cancellables)
        session.$done
            .sink { [weak self] _ in
                Task { @MainActor in self?.updateStatusModel() }
            }
            .store(in: &cancellables)
        session.$total
            .sink { [weak self] _ in
                Task { @MainActor in self?.updateStatusModel() }
            }
            .store(in: &cancellables)
        session.$groups
            .sink { [weak self] _ in
                Task { @MainActor in self?.syncFromSession() }
            }
            .store(in: &cancellables)
        session.$marks
            .sink { [weak self] _ in
                Task { @MainActor in self?.syncMarksAndMoving() }
            }
            .store(in: &cancellables)
        session.$moving
            .sink { [weak self] _ in
                Task { @MainActor in self?.syncMarksAndMoving() }
            }
            .store(in: &cancellables)
    }

    private func syncFromSession() {
        let ready = session.phase == .ready
        let listed = ready ? session.listedGroups : []
        marksByGroup = session.marks
        isMoving = session.moving

        let showList = ready && !listed.isEmpty
        updateStatusModel()

        guard showList else {
            revealAnimator?.stopAnimation(true)
            revealAnimator = nil
            collectionView.isHidden = true
            collectionView.alpha = 1
            statusHost.view.isHidden = false
            statusHost.view.alpha = 1
            cards = []
            lastCardIds = []
            lastCardPhotoIds = []
            if lastReportedCanScroll != false {
                lastReportedCanScroll = false
            }
            return
        }

        let revealing = collectionView.isHidden
        if revealing {
            collectionView.alpha = 0
            collectionView.isHidden = false
        }
        applyListedCards(listed)
        if revealing {
            revealList(animated: !UIAccessibility.isReduceMotionEnabled)
        }
        reportCanScrollIfChanged()
    }

    private func applyListedCards(_ listed: [SimilarCard]) {
        let nextIds = listed.map(\.key)
        let nextPhotoIds = listed.map { $0.photos.map(\.id) }
        guard nextIds != lastCardIds || nextPhotoIds != lastCardPhotoIds else {
            cards = listed
            refreshVisibleCards()
            return
        }
        if collectionView.numberOfItems(inSection: 0) == lastCardIds.count,
           let removals = deletionIndexPaths(nextIds: nextIds, nextPhotoIds: nextPhotoIds) {
            cards = listed
            lastCardIds = nextIds
            lastCardPhotoIds = nextPhotoIds
            collectionView.performBatchUpdates {
                self.collectionView.deleteItems(at: removals)
            }
            return
        }
        cards = listed
        lastCardIds = nextIds
        lastCardPhotoIds = nextPhotoIds
        updateLayoutMetrics()
        collectionView.reloadData()
        warmInitialCards()
        resolveVisibleRawIfIdle()
    }

    /// `nextIds` 是上一帧卡片去掉若干项、顺序不变，且留下的卡片照片数组没变。
    private func deletionIndexPaths(nextIds: [String], nextPhotoIds: [[String]]) -> [IndexPath]? {
        guard !lastCardIds.isEmpty, nextIds.count < lastCardIds.count else { return nil }
        var previousPhotos: [String: [String]] = [:]
        previousPhotos.reserveCapacity(lastCardIds.count)
        for (index, id) in lastCardIds.enumerated() where lastCardPhotoIds.indices.contains(index) {
            previousPhotos[id] = lastCardPhotoIds[index]
        }
        var removals: [IndexPath] = []
        var cursor = 0
        for (index, id) in lastCardIds.enumerated() {
            if cursor < nextIds.count && nextIds[cursor] == id {
                guard nextPhotoIds.indices.contains(cursor), previousPhotos[id] == nextPhotoIds[cursor] else { return nil }
                cursor += 1
            } else {
                removals.append(IndexPath(item: index, section: 0))
            }
        }
        guard cursor == nextIds.count, !removals.isEmpty else { return nil }
        return removals
    }

    private func revealList(animated: Bool) {
        revealAnimator?.stopAnimation(true)
        revealAnimator = nil
        if !animated {
            collectionView.alpha = 1
            statusHost.view.isHidden = true
            statusHost.view.alpha = 1
            return
        }
        statusHost.view.isHidden = false
        statusHost.view.alpha = 1
        let anim = UIViewPropertyAnimator(
            duration: DarkroomMotion.short,
            controlPoint1: CGPoint(x: 0.2, y: 0),
            controlPoint2: CGPoint(x: 0, y: 1)
        ) { [weak self] in
            self?.collectionView.alpha = 1
            self?.statusHost.view.alpha = 0
        }
        anim.addCompletion { [weak self] position in
            guard let self else { return }
            if position == .end && !self.collectionView.isHidden {
                self.statusHost.view.isHidden = true
            }
            self.statusHost.view.alpha = 1
            self.revealAnimator = nil
        }
        revealAnimator = anim
        anim.startAnimation()
    }

    private func syncMarksAndMoving() {
        marksByGroup = session.marks
        isMoving = session.moving
        if !collectionView.isHidden {
            refreshVisibleCards()
        }
    }

    private func updateStatusModel() {
        statusModel.chromeHeight = chromeHeight
        statusModel.phase = session.phase
        statusModel.done = session.done
        statusModel.total = session.total
        statusModel.empty = (session.phase == .ready && session.listedGroups.isEmpty)
        statusModel.zh = zh
        statusModel.skeletonActive = photosVisible && isActiveScope
    }

    private func updateLayoutInsetsIfNeeded() {
        let top = chromeHeight + 4
        let bottom = DarkroomDimens.listFade + 48
        if abs(flowLayout.sectionInset.top - top) > 0.5 || abs(flowLayout.sectionInset.bottom - bottom) > 0.5 {
            updateLayoutMetrics()
        }
    }

    private func updateLayoutMetrics() {
        let w = bounds.width > 1 ? bounds.width : UIScreen.main.bounds.width
        flowLayout.sectionInset = UIEdgeInsets(top: chromeHeight + 4, left: 16, bottom: DarkroomDimens.listFade + 48, right: 16)
        flowLayout.itemSize = CGSize(width: max(1, w - 32), height: SimilarCardCell.cardHeight)
        flowLayout.invalidateLayout()
    }

    private func refreshVisibleCards() {
        for cell in collectionView.visibleCells {
            guard let ip = collectionView.indexPath(for: cell),
                  cards.indices.contains(ip.item),
                  let cardCell = cell as? SimilarCardCell else { continue }
            let card = cards[ip.item]
            cardCell.configure(
                card: card,
                marks: marksByGroup[card.key] ?? [:],
                moving: isMoving,
                zh: zh,
                locale: locale,
                pipeline: pipeline,
                cardIndex: ip.item
            )
        }
    }

    private func warmInitialCards() {
        var assets: [PHAsset] = []
        assets.reserveCapacity(32)
        outer: for card in cards {
            for photo in card.photos {
                assets.append(photo.asset)
                if assets.count >= 32 { break outer }
            }
        }
        prefetch.warmInitial(assets, side: SimilarCardCell.thumbSide, style: .normal, limit: 32)
    }

    private func reportCanScrollIfChanged() {
        let can = canScrollNow()
        if lastReportedCanScroll != can {
            lastReportedCanScroll = can
        }
    }

    // MARK: UICollectionViewDataSource

    func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
        cards.count
    }

    func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        let cell = collectionView.dequeueReusableCell(withReuseIdentifier: SimilarCardCell.reuseIdentifier, for: indexPath) as! SimilarCardCell
        let card = cards[indexPath.item]
        cell.configure(
            card: card,
            marks: marksByGroup[card.key] ?? [:],
            moving: isMoving,
            zh: zh,
            locale: locale,
            pipeline: pipeline,
            cardIndex: indexPath.item
        )
        cell.onOpenPhoto = { [weak self] photoId in
            self?.onOpenPhoto?(card.key, photoId)
        }
        cell.onCommit = {
            _ = SimilarSession.shared.commit(groupKey: card.key)
        }
        return cell
    }

    // MARK: UICollectionViewDataSourcePrefetching

    func collectionView(_ collectionView: UICollectionView, prefetchItemsAt indexPaths: [IndexPath]) {
        var assets: [PHAsset] = []
        for ip in indexPaths where cards.indices.contains(ip.item) {
            assets.append(contentsOf: cards[ip.item].photos.map(\.asset))
        }
        prefetch.startCaching(assets, side: SimilarCardCell.thumbSide, style: .normal)
    }

    func collectionView(_ collectionView: UICollectionView, cancelPrefetchingForItemsAt indexPaths: [IndexPath]) {
        var assets: [PHAsset] = []
        for ip in indexPaths where cards.indices.contains(ip.item) {
            assets.append(contentsOf: cards[ip.item].photos.map(\.asset))
        }
        prefetch.stopCaching(assets)
    }

    // MARK: UIScrollViewDelegate

    func scrollViewWillBeginDragging(_ scrollView: UIScrollView) {
        GridDevelopSession.markPlayed(.similar)
        if isActiveScope {
            onScrollState?(scrollView.contentOffset.y, true, false, canScrollNow())
        }
    }

    func scrollViewDidScroll(_ scrollView: UIScrollView) {
        if scrollView.contentOffset.y > 1 {
            GridDevelopSession.markPlayed(.similar)
        }
        guard isActiveScope else { return }
        if ScrollProbe.enabled {
            ScrollProbe.shared.offsetCallback()
        }
        let moving = scrollView.isDragging || scrollView.isDecelerating
        onScrollState?(scrollView.contentOffset.y, moving, programmaticScroll, canScrollNow())
    }

    func scrollViewDidEndDragging(_ scrollView: UIScrollView, willDecelerate decelerate: Bool) {
        if !decelerate {
            resolveVisibleRawIfIdle()
            if isActiveScope { onScrollSettled?() }
        }
    }

    func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) {
        resolveVisibleRawIfIdle()
        if isActiveScope { onScrollSettled?() }
    }

    private func resolveVisibleRawIfIdle() {
        guard !collectionView.isDragging, !collectionView.isDecelerating else { return }
        rawResolveTask?.cancel()
        var missing: [(id: String, asset: PHAsset)] = []
        for case let cardCell as SimilarCardCell in collectionView.visibleCells {
            for photo in cardCell.currentPhotos() where AssetSizeCache.shared.cachedOriginalFilename(photo.id) == nil {
                missing.append((photo.id, photo.asset))
            }
        }
        guard !missing.isEmpty else { return }
        rawResolveTask = Task { @MainActor [weak self] in
            let raws = await Task.detached(priority: .utility) {
                var set = Set<String>()
                for entry in missing {
                    if Task.isCancelled { break }
                    let info = AssetSizeCache.shared.info(for: entry.asset)
                    if ScopeRules.isRawFileName(info.originalFilename) {
                        set.insert(entry.id)
                    }
                }
                return set
            }.value
            guard !Task.isCancelled, !raws.isEmpty, let self else { return }
            for case let cardCell as SimilarCardCell in self.collectionView.visibleCells {
                cardCell.refreshRawBadges(rawIds: raws)
            }
        }
    }
}

/// 标识相似卡片内部可横向滚动的缩略图条，供外层 `PhotosPagerScrollView` 识别并让出横向滑动权限。
final class SimilarThumbStripView: UICollectionView {}

/// 单张相似分组卡片（纯 UIKit frame 布局，无毛玻璃提交胶囊）。
private final class SimilarCardCell: UICollectionViewCell, UICollectionViewDataSource, UICollectionViewDelegate {
    static let reuseIdentifier = "SimilarCardCell"
    static let thumbSide: CGFloat = 72
    static let cardHeight: CGFloat = 176

    private let titleLabel: UILabel = {
        let l = UILabel()
        l.font = DarkroomUIFonts.notoSC(size: 14, weight: .regular)
        l.textColor = DarkroomUIColors.paper
        l.numberOfLines = 1
        return l
    }()

    private let stripLayout: UICollectionViewFlowLayout = {
        let f = UICollectionViewFlowLayout()
        f.scrollDirection = .horizontal
        f.itemSize = CGSize(width: thumbSide, height: thumbSide)
        f.minimumLineSpacing = 8
        f.minimumInteritemSpacing = 8
        f.sectionInset = .zero
        return f
    }()

    private let stripView: SimilarThumbStripView

    private lazy var commitButton: UIButton = {
        let b = UIButton(type: .system)
        b.backgroundColor = DarkroomUIColors.glass
        b.layer.cornerRadius = 18
        b.layer.cornerCurve = .continuous
        b.layer.borderWidth = 1
        b.layer.borderColor = DarkroomUIColors.glassRim.cgColor
        b.titleLabel?.font = DarkroomUIFonts.notoSC(size: 13, weight: .medium)
        b.titleLabel?.lineBreakMode = .byTruncatingTail
        b.setTitleColor(DarkroomUIColors.paper, for: .normal)
        b.addTarget(self, action: #selector(tappedCommit), for: .touchUpInside)
        return b
    }()

    private var card: SimilarCard?
    private var marks: [String: SimilarMark] = [:]
    private var zh = true
    private var locale: Locale = .current
    private weak var pipeline: ThumbnailPipeline?
    private var lastPhotoIds: [String] = []
    private var lastMarks: [String: SimilarMark] = [:]
    private var lastDateString = ""
    private var lastCommitTitle = ""
    private var lastMoving = false
    private var cardIndex = 0

    var onOpenPhoto: ((String) -> Void)?
    var onCommit: (() -> Void)?

    override init(frame: CGRect) {
        stripView = SimilarThumbStripView(frame: .zero, collectionViewLayout: stripLayout)
        super.init(frame: frame)

        contentView.backgroundColor = DarkroomUIColors.tray
        contentView.isOpaque = true
        contentView.layer.cornerRadius = 20
        contentView.layer.cornerCurve = .continuous
        contentView.layer.borderWidth = 1
        contentView.layer.borderColor = DarkroomUIColors.rim.cgColor
        contentView.clipsToBounds = true

        stripView.backgroundColor = DarkroomUIColors.tray
        stripView.showsHorizontalScrollIndicator = false
        stripView.bounces = true
        stripView.alwaysBounceHorizontal = false
        stripView.contentInsetAdjustmentBehavior = .never
        stripView.register(SimilarStripThumbCell.self, forCellWithReuseIdentifier: SimilarStripThumbCell.reuseIdentifier)
        stripView.dataSource = self
        stripView.delegate = self

        contentView.addSubview(titleLabel)
        contentView.addSubview(stripView)
        contentView.addSubview(commitButton)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func preferredLayoutAttributesFitting(_ layoutAttributes: UICollectionViewLayoutAttributes) -> UICollectionViewLayoutAttributes {
        layoutAttributes
    }

    override func prepareForReuse() {
        super.prepareForReuse()
        card = nil
        marks = [:]
        lastPhotoIds = []
        lastMarks = [:]
        lastDateString = ""
        lastCommitTitle = ""
        onOpenPhoto = nil
        onCommit = nil
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let b = contentView.bounds
        let innerW = max(0, b.width - 32)
        titleLabel.frame = CGRect(x: 16, y: 16, width: innerW, height: 20)
        stripView.frame = CGRect(x: 16, y: 44, width: innerW, height: Self.thumbSide)
        commitButton.frame = CGRect(x: 16, y: 44 + Self.thumbSide + 8, width: innerW, height: 36)
        updateStripScrollability(availableWidth: innerW)
    }

    func currentPhotos() -> [PhotoItem] {
        card?.photos ?? []
    }

    func refreshRawBadges(rawIds: Set<String>) {
        for case let thumbCell as SimilarStripThumbCell in stripView.visibleCells {
            if let pid = thumbCell.representedAssetId, rawIds.contains(pid) {
                thumbCell.showRawBadge()
            }
        }
    }

    func configure(
        card: SimilarCard,
        marks: [String: SimilarMark],
        moving: Bool,
        zh: Bool,
        locale: Locale,
        pipeline: ThumbnailPipeline,
        cardIndex: Int
    ) {
        self.cardIndex = cardIndex
        let photoIds = card.photos.map(\.id)
        let photosChanged = photoIds != lastPhotoIds || self.card?.key != card.key
        let marksChanged = marks != lastMarks
        let langChanged = self.zh != zh || self.locale.identifier != locale.identifier

        self.card = card
        self.marks = marks
        self.zh = zh
        self.locale = locale
        self.pipeline = pipeline

        let pattern = zh ? "M月d日 · EEE · HH:mm" : "MMM d · EEE · HH:mm"
        let dateStr = DateFormatCache.string(card.earliest, format: pattern, locale: locale)
        if dateStr != lastDateString {
            lastDateString = dateStr
            titleLabel.text = dateStr
        }

        let removeCount = card.photos.filter { marks[$0.id] == .remove }.count
        let commitTitle = SimilarCopy.commit(removeCount: removeCount, zh: zh)
        if commitTitle != lastCommitTitle {
            lastCommitTitle = commitTitle
            commitButton.setTitle(commitTitle, for: .normal)
        }
        if moving != lastMoving {
            lastMoving = moving
            commitButton.isEnabled = !moving
            commitButton.alpha = moving ? 0.45 : 1
        }

        if photosChanged {
            lastPhotoIds = photoIds
            lastMarks = marks
            updateStripScrollability(availableWidth: max(0, contentView.bounds.width - 32))
            stripView.reloadData()
            stripView.setContentOffset(.zero, animated: false)
        } else if marksChanged || langChanged {
            lastMarks = marks
            for ip in stripView.indexPathsForVisibleItems {
                guard let photos = self.card?.photos, photos.indices.contains(ip.item),
                      let cell = stripView.cellForItem(at: ip) as? SimilarStripThumbCell else { continue }
                let photo = photos[ip.item]
                let mark = marks[photo.id] ?? .keep
                cell.configure(item: photo, mark: mark, zh: zh, locale: locale, pipeline: pipeline, cardIndex: cardIndex, thumbIndex: ip.item)
            }
        }
    }

    private func updateStripScrollability(availableWidth: CGFloat) {
        let count = card?.photos.count ?? 0
        let contentW = CGFloat(count) * Self.thumbSide + CGFloat(max(0, count - 1)) * 8
        let canScrollHorizontally = availableWidth > 1 && contentW > availableWidth + 0.5
        if stripView.isScrollEnabled != canScrollHorizontally {
            stripView.isScrollEnabled = canScrollHorizontally
        }
    }

    @objc private func tappedCommit() {
        onCommit?()
    }

    // MARK: Strip DataSource & Delegate

    func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
        card?.photos.count ?? 0
    }

    func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        let cell = collectionView.dequeueReusableCell(withReuseIdentifier: SimilarStripThumbCell.reuseIdentifier, for: indexPath) as! SimilarStripThumbCell
        if let card, card.photos.indices.contains(indexPath.item), let pipeline {
            let photo = card.photos[indexPath.item]
            let mark = marks[photo.id] ?? .keep
            cell.configure(item: photo, mark: mark, zh: zh, locale: locale, pipeline: pipeline, cardIndex: cardIndex, thumbIndex: indexPath.item)
        }
        return cell
    }

    func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        guard let card, card.photos.indices.contains(indexPath.item) else { return }
        onOpenPhoto?(card.photos[indexPath.item].id)
    }
}

/// 相似横向缩略图条的纯 UIKit Cell：72×72 缩略图 + 右上相纸白移除圆标 + 左下 RAW 标。
private final class SimilarStripThumbCell: UICollectionViewCell {
    static let reuseIdentifier = "SimilarStripThumbCell"
    private static let side: CGFloat = SimilarCardCell.thumbSide
    private static let scrimHeight: CGFloat = 24
    private static let trashImage = DarkroomIcons.template(.trashMark, size: 14)

    private static let scrimImage: UIImage = {
        let fmt = UIGraphicsImageRendererFormat()
        fmt.opaque = false
        return UIGraphicsImageRenderer(size: CGSize(width: 1, height: scrimHeight), format: fmt).image { ctx in
            let cg = ctx.cgContext
            let colors = [UIColor.clear.cgColor, DarkroomUIColors.scrimBottom.cgColor] as CFArray
            if let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors, locations: [0, 1]) {
                cg.drawLinearGradient(gradient, start: CGPoint(x: 0.5, y: 0), end: CGPoint(x: 0.5, y: scrimHeight), options: [])
            }
        }
    }()

    private(set) var representedAssetId: String?
    private var token: ThumbnailToken?
    private var hasFinalImage = false
    private var didRunDevelop = false
    private var claimedId: String?
    private let developOverlay = TileDevelopOverlay()

    private let imageView: UIImageView = {
        let iv = UIImageView()
        iv.backgroundColor = DarkroomUIColors.tileFill
        iv.isOpaque = true
        iv.contentMode = .scaleAspectFill
        iv.clipsToBounds = true
        iv.isAccessibilityElement = false
        return iv
    }()

    private var removeBadgeView: UIView?
    private var removeIconView: UIImageView?
    private var scrimView: UIImageView?
    private var rawLabel: UILabel?

    override init(frame: CGRect) {
        super.init(frame: frame)
        contentView.backgroundColor = DarkroomUIColors.tileFill
        contentView.isOpaque = true
        contentView.clipsToBounds = true
        contentView.addSubview(imageView)
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

    override func prepareForReuse() {
        super.prepareForReuse()
        token?.cancel()
        token = nil
        abandonDevelopClaim()
        developOverlay.reset()
        didRunDevelop = false
        representedAssetId = nil
        hasFinalImage = false
        imageView.image = nil
        removeBadgeView?.isHidden = true
        scrimView?.isHidden = true
        rawLabel?.isHidden = true
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let b = contentView.bounds
        imageView.frame = b
        if let badge = removeBadgeView, !badge.isHidden {
            badge.frame = CGRect(x: b.width - 8 - 22, y: 8, width: 22, height: 22)
            removeIconView?.frame = badge.bounds
        }
        if let scrim = scrimView, !scrim.isHidden {
            scrim.frame = CGRect(x: 0, y: b.height - Self.scrimHeight, width: b.width, height: Self.scrimHeight)
        }
        if let rawLabel, !rawLabel.isHidden {
            let inset = DarkroomDimens.unseenInset
            let s = rawLabel.sizeThatFits(CGSize(width: b.width - inset * 2, height: b.height))
            rawLabel.frame = CGRect(x: inset, y: b.height - inset - ceil(s.height), width: ceil(s.width), height: ceil(s.height))
        }
        developOverlay.layout(in: b)
        raiseDevelopChrome()
    }

    func showRawBadge() {
        if scrimView == nil {
            let sv = UIImageView(image: Self.scrimImage)
            sv.contentMode = .scaleToFill
            sv.isUserInteractionEnabled = false
            contentView.insertSubview(sv, aboveSubview: imageView)
            scrimView = sv
        }
        if rawLabel == nil {
            let l = UILabel()
            l.font = DarkroomUIFonts.plexMono(size: 10)
            l.textColor = DarkroomUIColors.paper
            l.text = "RAW"
            l.isAccessibilityElement = false
            contentView.addSubview(l)
            rawLabel = l
        }
        scrimView?.isHidden = false
        rawLabel?.isHidden = false
        raiseDevelopChrome()
        setNeedsLayout()
    }

    func configure(item: PhotoItem, mark: SimilarMark, zh: Bool, locale: Locale, pipeline: ThumbnailPipeline, cardIndex: Int, thumbIndex: Int) {
        let sameAsset = representedAssetId == item.id
        representedAssetId = item.id

        let pattern = zh ? "M月d日 HH:mm" : "MMM d HH:mm"
        let dateStr = DateFormatCache.string(item.creationDate, format: pattern, locale: locale)
        accessibilityLabel = SimilarCopy.film(date: dateStr, mark: SimilarCopy.mark(mark, zh: zh), zh: zh)

        if mark == .remove {
            if removeBadgeView == nil {
                let badge = UIView()
                badge.backgroundColor = DarkroomUIColors.paper
                badge.layer.cornerRadius = 11
                badge.isUserInteractionEnabled = false
                let icon = UIImageView(image: Self.trashImage)
                icon.tintColor = DarkroomUIColors.room
                icon.contentMode = .center
                badge.addSubview(icon)
                contentView.addSubview(badge)
                removeBadgeView = badge
                removeIconView = icon
            }
            removeBadgeView?.isHidden = false
            setNeedsLayout()
        } else {
            removeBadgeView?.isHidden = true
        }

        let isRaw = ScopeRules.isRawFileName(AssetSizeCache.shared.cachedOriginalFilename(item.id))
        if isRaw {
            showRawBadge()
        } else {
            scrimView?.isHidden = true
            rawLabel?.isHidden = true
        }
        raiseDevelopChrome()

        if sameAsset && (imageView.image != nil || token != nil) {
            if developOverlay.isActive, let image = imageView.image {
                developOverlay.updateImage(image, assetId: item.id)
            }
            return
        }

        token?.cancel()
        token = nil
        hasFinalImage = false

        let delay = GridDevelopSession.staggerDelay(row: cardIndex, col: thumbIndex)
        if let hit = pipeline.cached(id: item.id, side: Self.side, style: .normal) {
            imageView.image = hit
            hasFinalImage = true
            maybeDevelop(image: hit, assetId: item.id, delay: delay)
            return
        }

        imageView.image = nil
        let targetId = item.id
        token = pipeline.request(asset: item.asset, side: Self.side, style: .normal) { [weak self] img, degraded in
            guard let self, self.representedAssetId == targetId else { return }
            if degraded && self.hasFinalImage { return }
            if !degraded { self.hasFinalImage = true }
            self.imageView.image = img
            self.maybeDevelop(image: img, assetId: targetId, delay: delay)
        }
    }

    private func maybeDevelop(image: UIImage, assetId: String, delay: Double) {
        if developOverlay.isActive {
            developOverlay.updateImage(image, assetId: assetId)
            return
        }
        if didRunDevelop { return }
        guard !UIAccessibility.isReduceMotionEnabled,
              GridDevelopSession.claim(grid: .similar, id: assetId) else { return }
        claimedId = assetId
        didRunDevelop = true
        developOverlay.start(
            image: image,
            assetId: assetId,
            delay: delay,
            in: contentView,
            above: imageView,
            bounds: contentView.bounds.width > 0 ? contentView.bounds : CGRect(x: 0, y: 0, width: Self.side, height: Self.side)
        )
        raiseDevelopChrome()
    }

    private func abandonDevelopClaim() {
        if developOverlay.isActive, let id = claimedId {
            GridDevelopSession.release(grid: .similar, id: id)
        }
        claimedId = nil
    }

    private func raiseDevelopChrome() {
        if let scrimView { contentView.bringSubviewToFront(scrimView) }
        if let rawLabel { contentView.bringSubviewToFront(rawLabel) }
        if let removeBadgeView { contentView.bringSubviewToFront(removeBadgeView) }
    }
}

/// 扫描阶段与零相似分组空态的局部 SwiftUI 容器，脱离 `PhotosView` 主体独立刷新。
private final class SimilarStatusModel: ObservableObject {
    @Published var chromeHeight: CGFloat = 96
    @Published var phase: SimilarSession.Phase = .idle
    @Published var done = 0
    @Published var total = 0
    @Published var empty = false
    @Published var zh = true
    @Published var skeletonActive = false
}

private struct SimilarStatusContainerView: View {
    @ObservedObject var model: SimilarStatusModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Color.clear.frame(height: model.chromeHeight)
                if model.phase != .ready {
                    Text(SimilarCopy.comparing(done: model.done, total: model.total, zh: model.zh))
                        .font(DarkroomType.countSub)
                        .foregroundStyle(DarkroomColors.dim)
                        .padding(.horizontal, 16)
                        .padding(.top, 8)
                    ForEach(0..<3, id: \.self) { i in
                        SimilarSkeletonCard(delay: Double(i) * 0.2, active: model.skeletonActive)
                            .padding(.horizontal, 16)
                    }
                } else if model.empty {
                    Text(SimilarCopy.empty(zh: model.zh))
                        .font(DarkroomType.title)
                        .foregroundStyle(DarkroomColors.paper)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity)
                        .padding(.horizontal, 24)
                        .padding(.top, 48)
                }
                Color.clear.frame(height: DarkroomDimens.listFade + 48)
            }
        }
        .scrollIndicators(.hidden)
        .background(DarkroomColors.room)
    }
}
