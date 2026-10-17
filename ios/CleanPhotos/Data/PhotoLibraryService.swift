import Foundation
import Photos
import UIKit
import Combine

@MainActor
final class PhotoLibraryService: ObservableObject {
    @Published private(set) var authorization: PHAuthorizationStatus
    @Published private(set) var sections: [PhotoYearGroup] = []
    @Published private(set) var favoriteSections: [PhotoYearGroup] = []
    @Published private(set) var trashItems: [PhotoItem] = []
    @Published private(set) var totalCount: Int = 0
    @Published private(set) var unseenCount: Int = 0
    @Published private(set) var favoriteCount: Int = 0
    @Published private(set) var trashCount: Int = 0
    @Published private(set) var trashBytes: Int64 = 0
    @Published private(set) var isLoading = false
    @Published private(set) var loadError: String?
    @Published var undoBanner: UndoBanner?
    /// 清理页在前台时为 true：照片 / 收藏 / 垃圾桶页不显示撤回条（Android 的单张撤回属于 ReviewViewModel，离开清理页即消失）。
    @Published var reviewActive = false
    @Published var sortOrder: PhotoSortOrder = PhotoSortOrder(rawValue: UserDefaults.standard.string(forKey: "cleanphotos.sortOrder") ?? "") ?? .newestFirst {
        didSet {
            UserDefaults.standard.set(sortOrder.rawValue, forKey: "cleanphotos.sortOrder")
            if oldValue != sortOrder { rebuildDerived() }
        }
    }
    @Published var filterMode: PhotoFilterMode = .allPhotos {
        didSet { if oldValue != filterMode { rebuildDerived() } }
    }
    /// 筛选后列表张数（「仅未看过」时顶栏大数字）。
    @Published private(set) var visibleCount: Int = 0

    let seen = SeenStore()
    let favorites = FavoriteStore()
    let trash = TrashStore()
    let imageManager = PHCachingImageManager()

    /// Flat cache of all library photos keyed by id (including favorited / trashed).
    private(set) var allById: [String: PhotoItem] = [:]
    /// Photos tab ordered list (excludes favorited + trashed), newest first.
    private(set) var photosOrdered: [PhotoItem] = []
    /// Photos tab after sort + filter, in display order (drag-select range / review queue).
    private(set) var visiblePhotos: [PhotoItem] = []
    /// 整库按拍摄时间倒序（PhotoKit 取回的顺序），重算派生数据时不用再排序。
    private var fetchOrdered: [PhotoItem] = []
    /// 首屏先出的那一批还没补全时为 true（顶栏按钮置灰，计数是估算值）。
    @Published private(set) var isPartial = false

    /// 共享元素算大图位置时按 id 找照片（只在打开 / 关闭清理页时调用一次）。
    func item(id: String) -> PhotoItem? {
        photosOrdered.first { $0.id == id } ?? trashItems.first { $0.id == id }
    }
    private var undoTask: Task<Void, Never>?
    private var loadTask: Task<Void, Never>?
    /// 首屏先建多少张（够铺满两三屏），其余在后台补齐。
    private static let firstChunk = 240

    init() {
        authorization = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        imageManager.allowsCachingHighQualityImages = false
    }

    func refreshAuthorization() async {
        authorization = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        if authorization == .authorized || authorization == .limited {
            await reload()
        }
    }

    func requestAccess() async {
        let status = await PHPhotoLibrary.requestAuthorization(for: .readWrite)
        authorization = status
        if status == .authorized || status == .limited {
            await reload()
        }
    }

    func openSystemSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }

    /// 重新读整库。并发调用（RootView + PhotosView 同时触发）合并成同一次。
    func reload() async {
        // 审计截图用：-cpForceLoading 让首屏一直停在读取中（骨架 + 「正在读取照片…」），不读相册
        if ProcessInfo.processInfo.arguments.contains("-cpForceLoading") { isLoading = true; return }
        if let loadTask { await loadTask.value; return }
        let task = Task { await self.performReload() }
        loadTask = task
        await task.value
        loadTask = nil
    }

    private func performReload() async {
        PerfLog.mark("reloadStart")
        isLoading = true
        loadError = nil
        defer {
            isLoading = false
            isPartial = false
            PerfLog.mark("reloadEnd", "n=\(allById.count) sections=\(sections.count)")
        }

        // PhotoKit 取数 + 遍历全在后台，主线程只负责发布结果。
        let result: PHFetchResult<PHAsset> = await Task.detached(priority: .userInitiated) {
            let options = PHFetchOptions()
            options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
            options.includeHiddenAssets = false
            let r = PHAsset.fetchAssets(with: .image, options: options)
            _ = r.count
            return r
        }.value
        let count = result.count
        PerfLog.mark("fetchDone", "count=\(count)")

        let snap = snapshot()
        let total = count

        // 1) 首屏：只建最前（或「最早在前」时最后）一批，马上出格子。
        if sections.isEmpty && total > Self.firstChunk {
            let range = sortOrder == .oldestFirst ? (total - Self.firstChunk)..<total : 0..<Self.firstChunk
            let head: Derived = await Task.detached(priority: .userInitiated) {
                let items = Self.makeItems(result, range: range)
                return Self.derive(source: items, snap: snap)
            }.value
            apply(head, source: nil)
            // 计数先给估算值，补全后再校正
            let hidden = snap.favorites.count + snap.trash.count
            totalCount = max(head.photosOrdered.count, total - hidden)
            unseenCount = max(head.unseenCount, totalCount - snap.seen.count)
            visibleCount = snap.filter == .unseenOnly ? unseenCount : totalCount
            isPartial = true
            PerfLog.mark("firstChunk", "items=\(head.visible.count) sections=\(head.sections.count)")
            warmThumbnails(head.visible)
        }

        // 2) 全量：后台遍历 + 分组，完成后一次性替换。
        let full: (items: [PhotoItem], derived: Derived) = await Task.detached(priority: .userInitiated) {
            let items = Self.makeItems(result, range: 0..<total)
            return (items, Self.derive(source: items, snap: snap))
        }.value
        PerfLog.mark("fullBuilt", "items=\(full.items.count)")
        // 后台期间用户可能收藏/删除过：快照变了就按最新状态在主线程重算一次
        if snapshot() == snap {
            apply(full.derived, source: full.items)
        } else {
            fetchOrdered = full.items
            rebuildDerived()
        }
        if !isPartial { warmThumbnails(visiblePhotos) }
    }

    // MARK: - Derived state

    struct Snapshot: Equatable, Sendable {
        var seen: Set<String>
        var favorites: [String: FavoriteEntry]
        var trash: [String: TrashEntry]
        var filter: PhotoFilterMode
        var sort: PhotoSortOrder
    }

    struct Derived {
        var allById: [String: PhotoItem]
        var photosOrdered: [PhotoItem]
        var visible: [PhotoItem]
        var sections: [PhotoYearGroup]
        var favoriteSections: [PhotoYearGroup]
        var trashed: [PhotoItem]
        var unseenCount: Int
        var favoriteCount: Int
    }

    private func snapshot() -> Snapshot {
        Snapshot(seen: seen.seenIds, favorites: favorites.entries, trash: trash.entries, filter: filterMode, sort: sortOrder)
    }

    nonisolated private static func makeItems(_ result: PHFetchResult<PHAsset>, range: Range<Int>) -> [PhotoItem] {
        guard !range.isEmpty else { return [] }
        var out: [PhotoItem] = []
        out.reserveCapacity(range.count)
        result.enumerateObjects(at: IndexSet(integersIn: range), options: []) { asset, _, _ in
            out.append(PhotoItem(
                id: asset.localIdentifier,
                asset: asset,
                creationDate: asset.creationDate ?? Date.distantPast,
                isUnseen: true, isFavorite: false, isTrashed: false
            ))
        }
        return out
    }

    nonisolated private static func derive(source: [PhotoItem], snap: Snapshot) -> Derived {
        var byId: [String: PhotoItem] = [:]
        byId.reserveCapacity(source.count)
        var ordered: [PhotoItem] = []
        ordered.reserveCapacity(source.count)
        var favs: [PhotoItem] = []
        var trashed: [PhotoItem] = []
        var unseen = 0
        for var i in source {
            i.isUnseen = !snap.seen.contains(i.id)
            i.isFavorite = snap.favorites[i.id] != nil
            i.isTrashed = snap.trash[i.id] != nil
            byId[i.id] = i
            if i.isTrashed { trashed.append(i) }
            else if i.isFavorite { favs.append(i) }
            else {
                ordered.append(i)
                if i.isUnseen { unseen += 1 }
            }
        }
        favs.sort { (snap.favorites[$0.id]?.favoritedAt ?? 0) > (snap.favorites[$1.id]?.favoritedAt ?? 0) }
        trashed.sort { (snap.trash[$0.id]?.movedToTrashAt ?? 0) > (snap.trash[$1.id]?.movedToTrashAt ?? 0) }
        var visible = snap.filter == .unseenOnly ? ordered.filter(\.isUnseen) : ordered
        if snap.sort == .oldestFirst { visible.reverse() }
        return Derived(
            allById: byId,
            photosOrdered: ordered,
            visible: visible,
            sections: group(items: visible, gridCap: 8, ascending: snap.sort == .oldestFirst),
            favoriteSections: group(items: favs, gridCap: 8),
            trashed: trashed,
            unseenCount: unseen,
            favoriteCount: favs.count
        )
    }

    private func apply(_ d: Derived, source: [PhotoItem]?) {
        if let source { fetchOrdered = source }
        allById = d.allById
        photosOrdered = d.photosOrdered
        totalCount = d.photosOrdered.count
        unseenCount = d.unseenCount
        favoriteCount = d.favoriteCount
        trashCount = d.trashed.count
        trashBytes = trash.totalBytes
        visiblePhotos = d.visible
        visibleCount = d.visible.count
        sections = d.sections
        favoriteSections = d.favoriteSections
        trashItems = d.trashed
    }

    /// 「全部看过了」晾绳上的三张（Android hangPhotos = 按当前排序的前 3 张）。
    var hangPhotoIds: [String] {
        let source = fetchOrdered.isEmpty ? Array(allById.values).sorted { $0.creationDate > $1.creationDate } : fetchOrdered
        let ordered = sortOrder == .oldestFirst ? Array(source.reversed()) : source
        return Array(ordered.lazy.filter { !self.trash.contains($0.id) }.prefix(3).map(\.id))
    }

    private func rebuildDerived() {
        // 首屏批次还没补全时 fetchOrdered 为空：用已有的 allById 兜底
        let source = fetchOrdered.isEmpty ? Array(allById.values).sorted { $0.creationDate > $1.creationDate } : fetchOrdered
        apply(Self.derive(source: source, snap: snapshot()), source: nil)
    }

    // MARK: - Thumbnails

    /// 网格格子边长（和 PhotosView.dayGrid 的算法一致），缓存预热要用同一个尺寸才会命中。
    static var gridTileSide: CGFloat {
        let cols = CGFloat(DarkroomDimens.gridColumns)
        let w = UIScreen.main.bounds.width // 网格占满屏宽
        return (w - DarkroomDimens.gridGap * (cols - 1)) / cols
    }

    private func thumbTarget(side: CGFloat) -> CGSize {
        let px = (side * UIScreen.main.scale).rounded(.up)
        return CGSize(width: px, height: px)
    }

    private lazy var thumbOptions: PHImageRequestOptions = {
        let opts = PHImageRequestOptions()
        opts.deliveryMode = .opportunistic
        opts.resizeMode = .fast
        opts.isNetworkAccessAllowed = true
        return opts
    }()

    private var warmedIds: [PHAsset] = []

    /// 让 PHCachingImageManager 先把首屏几十张缩略图解好。
    private func warmThumbnails(_ visible: [PhotoItem]) {
        var assets: [PHAsset] = []
        outer: for year in sections {
            for day in year.days {
                for item in day.items {
                    assets.append(item.asset)
                    if assets.count >= 64 { break outer }
                }
            }
        }
        guard !assets.isEmpty else { return }
        if !warmedIds.isEmpty {
            imageManager.stopCachingImages(for: warmedIds, targetSize: thumbTarget(side: Self.gridTileSide), contentMode: .aspectFill, options: thumbOptions)
        }
        warmedIds = assets
        imageManager.startCachingImages(for: assets, targetSize: thumbTarget(side: Self.gridTileSide), contentMode: .aspectFill, options: thumbOptions)
    }

    func requestThumbnail(for asset: PHAsset, side: CGFloat, completion: @escaping (UIImage?) -> Void) -> PHImageRequestID {
        imageManager.requestImage(for: asset, targetSize: thumbTarget(side: side), contentMode: .aspectFill, options: thumbOptions) { image, _ in
            completion(image)
        }
    }

    func requestFullImage(for asset: PHAsset, maxSide: CGFloat, completion: @escaping (UIImage?) -> Void) -> PHImageRequestID {
        let scale = UIScreen.main.scale
        let target = CGSize(width: maxSide * scale, height: maxSide * scale)
        let opts = PHImageRequestOptions()
        // 先给一张快速低清图，高清好了再替换（iCloud 原图下载期间不至于黑屏）
        opts.deliveryMode = .opportunistic
        opts.resizeMode = .fast
        opts.isNetworkAccessAllowed = true
        return imageManager.requestImage(for: asset, targetSize: target, contentMode: .aspectFit, options: opts) { image, _ in
            completion(image)
        }
    }

    func cancelThumbnail(_ id: PHImageRequestID) {
        imageManager.cancelImageRequest(id)
    }

    // MARK: - Actions

    func markSeen(_ ids: [String]) {
        let before = seen.seenIds.count
        seen.markSeen(ids)
        guard seen.seenIds.count != before else { return }
        // 清理页每翻一页都标记看过：整库重算 + 9 个 @Published 会让清理页和下面被盖住的网格整棵重算，
        // 正好落在翻页弹簧的头几帧。清理页打开期间只记账，关掉时（endReview）再统一重算一次。
        if reviewActive && !PerfLegacy.on { seenDirty = true; return }
        rebuildDerived()
    }
    private var seenDirty = false

    /// 清理页里收藏 / 移到垃圾桶 / 撤回：收藏、垃圾桶记录照常立即写入，但整库派生数据（网格分组、计数，主线程约 20ms，
    /// 再加被盖住的网格整棵重算）正好落在离场动画第一帧。清理页打开期间只记脏，关闭时（flushDeferredSeen）后台统一重算。
    /// 清理页自己要的「垃圾桶数量」直接读 trash.count。
    private func rebuildDerivedUnlessReviewing() {
        if reviewActive && !PerfLegacy.on { seenDirty = true; return }
        rebuildDerived()
    }

    /// 清理页关闭时把攒着的「看过」补算进网格：后台重算、主线程一次替换（期间状态变了就按最新的同步重算）。
    func flushDeferredSeen() {
        guard seenDirty else { return }
        seenDirty = false
        let snap = snapshot()
        let source = fetchOrdered.isEmpty ? Array(allById.values).sorted { $0.creationDate > $1.creationDate } : fetchOrdered
        Task { @MainActor in
            let d = await Task.detached(priority: .userInitiated) { Self.derive(source: source, snap: snap) }.value
            if self.snapshot() == snap { self.apply(d, source: nil) } else { self.rebuildDerived() }
        }
    }

    @discardableResult
    func favorite(ids: [String], showUndo: Bool = true) -> Int {
        let targets = ids.filter { allById[$0] != nil && !trash.contains($0) && !favorites.contains($0) }
        guard !targets.isEmpty else { return 0 }
        favorites.add(targets)
        seen.markSeen(ids)
        rebuildDerivedUnlessReviewing()
        if showUndo {
            presentUndo(.favorited(targets), sample: targets.first)
        }
        return targets.count
    }

    @discardableResult
    func unfavorite(ids: [String], showUndo: Bool = true) -> Int {
        let targets = ids.filter { favorites.contains($0) }
        guard !targets.isEmpty else { return 0 }
        favorites.remove(targets)
        seen.markSeen(ids)
        rebuildDerivedUnlessReviewing()
        if showUndo {
            presentUndo(.unfavorited(targets), sample: targets.first)
        }
        return targets.count
    }

    @discardableResult
    func moveToTrash(ids: [String], showUndo: Bool = true) -> Int {
        let items = ids.compactMap { id -> PhotoItem? in
            guard let item = allById[id], !trash.contains(id) else { return nil }
            return item
        }
        // 文件大小是按需查的：批量删时多核并行算，别一张张串行卡主线程
        var sizes = [Int64](repeating: 0, count: items.count)
        sizes.withUnsafeMutableBufferPointer { buf in
            let base = buf.baseAddress!
            DispatchQueue.concurrentPerform(iterations: items.count) { i in
                base[i] = AssetSizeCache.shared.bytes(for: items[i].asset)
            }
        }
        let payloads: [(id: String, wasFavorited: Bool, byteSize: Int64)] = items.enumerated().map { i, item in
            (item.id, favorites.contains(item.id), sizes[i])
        }
        guard !payloads.isEmpty else { return 0 }
        let favIds = payloads.filter(\.wasFavorited).map(\.id)
        if !favIds.isEmpty { favorites.remove(favIds) }
        trash.move(payloads)
        seen.markSeen(ids)
        rebuildDerivedUnlessReviewing()
        if showUndo {
            let idsOnly = payloads.map(\.id)
            presentUndo(.movedToTrash(idsOnly), sample: idsOnly.first)
        }
        return payloads.count
    }

    @discardableResult
    func restoreFromTrash(ids: [String], showUndo: Bool = true) -> Int {
        let restored = trash.restore(ids)
        guard !restored.isEmpty else { return 0 }
        let reFav = restored.filter(\.wasFavorited).map(\.photoId)
        if !reFav.isEmpty { favorites.add(reFav) }
        seen.markSeen(ids)
        rebuildDerivedUnlessReviewing()
        if showUndo {
            let idsOnly = restored.map(\.photoId)
            presentUndo(.restored(idsOnly), sample: idsOnly.first)
        }
        return restored.count
    }

    func permanentlyDelete(ids: [String]) async throws {
        let assets = ids.compactMap { allById[$0]?.asset }
        guard !assets.isEmpty else { return }
        try await PHPhotoLibrary.shared().performChanges {
            PHAssetChangeRequest.deleteAssets(assets as NSArray)
        }
        trash.remove(ids)
        favorites.remove(ids)
        seen.markSeen(ids)
        await reload()
    }

    func undoLast() {
        guard let banner = undoBanner else { return }
        undoTask?.cancel()
        undoBanner = nil
        switch banner.kind {
        case .favorited(let ids):
            favorites.remove(ids)
            rebuildDerivedUnlessReviewing()
        case .unfavorited(let ids):
            favorites.add(ids)
            rebuildDerivedUnlessReviewing()
        case .movedToTrash(let ids):
            _ = restoreFromTrash(ids: ids, showUndo: false)
        case .restored(let ids):
            // Re-trash without undo loop
            _ = moveToTrash(ids: ids, showUndo: false)
        }
    }

    func presentUndo(_ kind: UndoKind, sample: String?) {
        undoTask?.cancel()
        undoBanner = UndoBanner(kind: kind, sampleId: sample)
        restartUndoTimer()
    }

    /// 清理页把刚出现的撤回条改成单张文案 + 副文案，并重启 4 秒倒计时。
    func decorateUndo(subtitle: String?) {
        guard var b = undoBanner else { return }
        b.subtitle = subtitle
        b.single = true
        undoBanner = b
    }

    private func restartUndoTimer() {
        undoTask = Task {
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            if !Task.isCancelled { undoBanner = nil }
        }
    }

    /// 离开清理页：单张撤回条随清理页一起消失（Android ReviewViewModel 作用域）。
    func endReview() {
        reviewActive = false
        flushDeferredSeen()
        if undoBanner?.single == true { clearUndo() }
    }

    func clearUndo() {
        undoTask?.cancel()
        undoBanner = nil
    }

    func reviewQueue(scope: ReviewScope, startingAt id: String? = nil, dayId: String? = nil) -> (items: [PhotoItem], startIndex: Int) {
        let base: [PhotoItem]
        switch scope {
        case .allPhotos:
            // Same order / filter the Photos grid shows
            base = visiblePhotos
        case .favorites:
            base = favoriteSections.flatMap { $0.days.flatMap(\.allItems) }
        }
        guard !base.isEmpty else { return ([], 0) }
        if let dayId {
            let dayItems: [PhotoItem]
            switch scope {
            case .allPhotos: dayItems = sections.lazy.flatMap(\.days).first { $0.id == dayId }?.allItems ?? []
            case .favorites: dayItems = favoriteSections.lazy.flatMap(\.days).first { $0.id == dayId }?.allItems ?? []
            }
            if !dayItems.isEmpty {
                let inDay = Set(dayItems.map(\.id))
                return (dayItems + base.filter { !inDay.contains($0.id) }, 0)
            }
        }
        if let id, let idx = base.firstIndex(where: { $0.id == id }) {
            return (base, idx)
        }
        return (base, 0)
    }

    /// 分组：输入已按时间排好，同一天的照片连续出现，所以只有跨天时才算一次日历区间，
    /// 不再每张照片建 DateFormatter / 格式化字符串（几万张时这是主要耗时之一）。
    nonisolated private static func group(items: [PhotoItem], gridCap: Int, ascending: Bool = false) -> [PhotoYearGroup] {
        let cal = Calendar.current
        var dayOrder: [Int] = []
        var dayMap: [Int: (Date, [PhotoItem])] = [:]
        var curStart = Date.distantFuture, curEnd = Date.distantPast, curKey = 0
        for item in items {
            let d = item.creationDate
            if !(d >= curStart && d < curEnd) {
                guard let iv = cal.dateInterval(of: .day, for: d) else { continue }
                curStart = iv.start; curEnd = iv.end
                let c = cal.dateComponents([.year, .month, .day], from: iv.start)
                curKey = (c.year ?? 0) * 10000 + (c.month ?? 0) * 100 + (c.day ?? 0)
                if dayMap[curKey] == nil { dayMap[curKey] = (iv.start, []); dayOrder.append(curKey) }
            }
            dayMap[curKey]!.1.append(item)
        }
        let dayKeys = ascending ? dayOrder.sorted(by: <) : dayOrder.sorted(by: >)
        var years: [Int] = []
        var yearMap: [Int: [PhotoDaySection]] = [:]
        for key in dayKeys {
            guard let (date, list) = dayMap[key] else { continue }
            let y = key / 10000
            let id = String(format: "%04d-%02d-%02d", y, (key / 100) % 100, key % 100)
            let section = PhotoDaySection(
                id: id,
                date: date,
                items: Array(list.prefix(gridCap)),
                allItems: list,
                totalCount: list.count,
                unseenCount: list.reduce(0) { $0 + ($1.isUnseen ? 1 : 0) }
            )
            if yearMap[y] == nil { years.append(y) }
            yearMap[y, default: []].append(section)
        }
        return years.map { PhotoYearGroup(id: String($0), year: $0, days: yearMap[$0] ?? []) }
    }
}
