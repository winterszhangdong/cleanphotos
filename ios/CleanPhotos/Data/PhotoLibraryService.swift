import Foundation
import Photos
import PhotosUI
import UIKit
import Combine

@MainActor
final class PhotoLibraryService: NSObject, ObservableObject, PHPhotoLibraryChangeObserver {
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
        // 全库字典：照片 / 收藏 / 垃圾桶三个入口都找得到（之前只查照片网格和垃圾桶，收藏页打开时没有共享元素飞入）
        allById[id] ?? trashItems.first { $0.id == id }
    }
    private var undoTask: Task<Void, Never>?
    /// 最近一次可撤回的「取消收藏」的原条目（撤回时按原收藏时间放回）。
    private var unfavoriteBackup: [FavoriteEntry] = []
    private var loadTask: Task<Void, Never>?
    private var reloadAgain = false
    /// 首屏先建多少张（够铺满两三屏），其余在后台补齐。
    private static let firstChunk = 240

    override init() {
        authorization = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        super.init()
        imageManager.allowsCachingHighQualityImages = false
    }

    private var hasAccess: Bool { authorization == .authorized || authorization == .limited }

    func refreshAuthorization() async {
        authorization = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        if hasAccess {
            registerChangeObserver()
            await reload()
        }
    }

    /// 回到前台（scenePhase → active）：从系统设置改了权限（拒绝 → 允许、部分 ↔ 完整、收回）后不用重开 App。
    /// 权限没变就什么都不做（图库内容的变化走 PHPhotoLibraryChangeObserver）。
    func refreshAuthorizationIfChanged() async {
        let now = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        guard now != authorization else { return }
        authorization = now
        if hasAccess {
            registerChangeObserver()
            if reviewActive { libraryDirty = true } else { await reload(fresh: true) }
        }
    }

    func requestAccess() async {
        let status = await PHPhotoLibrary.requestAuthorization(for: .readWrite)
        authorization = status
        if status == .authorized || status == .limited {
            registerChangeObserver()
            await reload(fresh: true)
        }
    }

    // MARK: - Library changes（在系统相册里增删照片、调整「部分照片」选择后自动刷新）

    private var observerRegistered = false
    /// 最近一次整库取数结果：变更通知用它判断「图片列表到底变没变」，相册 / 收藏等无关变化不重读。
    private var lastFetch: PHFetchResult<PHAsset>?
    private var changeTask: Task<Void, Never>?
    /// 清理页打开期间图库变了：先记着，关掉清理页时再重读（不在翻页 / 离场动画中途整库重算）。
    private var libraryDirty = false
    /// 自己永久删除期间的变更通知不再重复重读（删除流程最后会自己 reload）。
    private var ownDeleteUntil = Date.distantPast

    private func registerChangeObserver() {
        guard !observerRegistered else { return }
        observerRegistered = true
        PHPhotoLibrary.shared().register(self)
    }

    nonisolated func photoLibraryDidChange(_ changeInstance: PHChange) {
        let change = UncheckedBox(changeInstance)
        Task { @MainActor in self.libraryDidChange(change) }
    }

    private func libraryDidChange(_ change: UncheckedBox<PHChange>) {
        guard hasAccess, Date() >= ownDeleteUntil else { return }
        let fetch = lastFetch.map(UncheckedBox.init)
        changeTask?.cancel()
        changeTask = Task { @MainActor in
            // 变更差异在后台算（大图库时不便宜）；没有取过数（还在首次读取）就直接重读
            let relevant: Bool = await Task.detached(priority: .utility) {
                guard let fetch else { return true }
                return change.value.changeDetails(for: fetch.value) != nil
            }.value
            guard relevant, !Task.isCancelled else { return }
            // 连续的通知（iCloud 同步、批量导入）合并成一次
            try? await Task.sleep(nanoseconds: 400_000_000)
            guard !Task.isCancelled else { return }
            if self.reviewActive { self.libraryDirty = true; return }
            await self.reload(fresh: true)
        }
    }

    /// 「部分照片」访问时追加 / 调整选择；选完 PhotoKit 发变更通知，走 libraryDidChange 重读。
    func presentLimitedLibraryPicker() {
        guard authorization == .limited,
              let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first(where: { $0.activationState == .foregroundActive }),
              var top = scene.keyWindow?.rootViewController else { return }
        while let next = top.presentedViewController { top = next }
        PHPhotoLibrary.shared().presentLimitedLibraryPicker(from: top)
    }

    func openSystemSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }

    /// 重新读整库。并发调用（RootView + PhotosView 同时触发）合并成同一次。
    /// fresh：调用方知道图库刚变过（删除、外部变更、权限变化）——撞上进行中的读取时不能直接用它的结果。
    func reload(fresh: Bool = false) async {
        // 审计截图用：-cpForceLoading 让首屏一直停在读取中（骨架 + 「正在读取照片…」），不读相册
        if AuditHooks.enabled, ProcessInfo.processInfo.arguments.contains("-cpForceLoading") { isLoading = true; return }
        if let loadTask {
            // 正在读的那一轮可能是变化之前开始的：要求新数据时，读完再补一轮（合并成一次）
            if fresh { reloadAgain = true }
            await loadTask.value
            return
        }
        let task = Task {
            repeat {
                self.reloadAgain = false
                await self.performReload()
            } while self.reloadAgain
        }
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
        lastFetch = result
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
        await cleanupStores(libraryIds: Set(full.items.map(\.id)), libraryCount: total)
    }

    /// 失效条目清理（Android PhotoRepositoryImpl.cleanupStores）：收藏 / 看过 / 垃圾桶里已经不在图库的照片。
    /// 只有完整访问权才清理（StoreCleanupPolicy）；不在这次取数里的 id 再按 id 回查一次（含隐藏照片、任意类型），确实查不到才删。
    private func cleanupStores(libraryIds: Set<String>, libraryCount: Int) async {
        guard authorization == .authorized else { return }
        let seenIds = seen.seenIds, favIds = Set(favorites.entries.keys), trashIds = Set(trash.entries.keys)
        // 集合运算 + 按 id 回查都在后台（看过的 id 可能有几万个）
        let remove: Set<String>? = await Task.detached(priority: .utility) {
            let stored = seenIds.union(favIds).union(trashIds)
            let candidates = stored.subtracting(libraryIds)
            guard !candidates.isEmpty else { return [] }
            let opts = PHFetchOptions()
            opts.includeHiddenAssets = true
            let r = PHAsset.fetchAssets(withLocalIdentifiers: Array(candidates), options: opts)
            var found = Set<String>()
            r.enumerateObjects { a, _, _ in found.insert(a.localIdentifier) }
            let existing = stored.intersection(libraryIds).union(found)
            return StoreCleanupPolicy.idsToRemove(stored: stored, existing: existing, fullAccess: true, libraryCount: libraryCount)
        }.value
        guard authorization == .authorized, let remove, !remove.isEmpty else { return }
        let ids = Array(remove)
        PerfLog.mark("cleanupStores", "removed=\(ids.count)")
        favorites.remove(ids.filter { favorites.contains($0) })
        trash.remove(ids.filter { trash.contains($0) })
        seen.remove(ids)
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
        trashBytes = TrashTotals.bytes(entrySizes: trash.entries.mapValues(\.byteSize), present: d.trashed.map(\.id))
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
        if showUndo { unfavoriteBackup = targets.compactMap { favorites.entries[$0] } }
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
        // 文件大小是按需查的（PHAssetResource，每张几毫秒）：已缓存的直接用；少量（清理页单张，通常已预取）当场算；
        // 网格批量移入时没缓存的先记 0，后台多核算完再补进垃圾桶条目，不卡主线程
        let syncLimit = 4
        var sizes = items.map { AssetSizeCache.shared.cachedBytes($0.id) ?? -1 }
        let missing = sizes.indices.filter { sizes[$0] < 0 }
        if missing.count <= syncLimit {
            for i in missing { sizes[i] = AssetSizeCache.shared.bytes(for: items[i].asset) }
        } else {
            for i in missing { sizes[i] = 0 }
            let todo = UncheckedBox(missing.map { (items[$0].id, items[$0].asset) })
            Task.detached(priority: .utility) {
                var out = [Int64](repeating: 0, count: todo.value.count)
                out.withUnsafeMutableBufferPointer { buf in
                    let base = UncheckedBox(buf.baseAddress!)
                    DispatchQueue.concurrentPerform(iterations: todo.value.count) { i in
                        base.value[i] = AssetSizeCache.shared.bytes(for: todo.value[i].1)
                    }
                }
                let filled = Dictionary(zip(todo.value.map(\.0), out), uniquingKeysWith: { a, _ in a })
                await MainActor.run {
                    self.trash.fillSizes(filled)
                    self.trashBytes = TrashTotals.bytes(entrySizes: self.trash.entries.mapValues(\.byteSize), present: self.trashItems.map(\.id))
                }
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

    /// 永久删除：按 id 现查一遍 PHAsset（缓存的对象可能已在系统相册里被删掉 / 换过），只删还在的；
    /// 已经不在的直接从 App 状态里拿掉。用户在系统确认框点「不允许」会抛 PHPhotosError.userCancelled。
    func permanentlyDelete(ids: [String]) async throws {
        guard !ids.isEmpty else { return }
        let assets: [PHAsset] = await Task.detached(priority: .userInitiated) {
            let opts = PHFetchOptions()
            opts.includeHiddenAssets = true
            let r = PHAsset.fetchAssets(withLocalIdentifiers: ids, options: opts)
            var out: [PHAsset] = []
            r.enumerateObjects { a, _, _ in out.append(a) }
            return out
        }.value
        if !assets.isEmpty {
            ownDeleteUntil = .distantFuture
            defer { ownDeleteUntil = Date().addingTimeInterval(1.5) }
            try await PHPhotoLibrary.shared().performChanges {
                PHAssetChangeRequest.deleteAssets(assets as NSArray)
            }
        }
        trash.remove(ids)
        favorites.remove(ids)
        seen.markSeen(ids)
        // 先在本地拿掉（一次派生重算，和之前整库重读后的那次发布同一时刻），垃圾桶溶解马上开始；
        // 整库重读放到溶解播完之后（大图库要几百 ms 到几秒，之前溶解要干等它）
        let gone = Set(ids)
        fetchOrdered.removeAll { gone.contains($0.id) }
        allById = allById.filter { !gone.contains($0.key) }
        rebuildDerived()
        deferredReload?.cancel()
        deferredReload = Task {
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            guard !Task.isCancelled else { return }
            await self.reload(fresh: true)
        }
    }
    private var deferredReload: Task<Void, Never>?

    /// 删除失败的提示文案 key：用户取消 →「操作已取消」，其余 →「操作失败」。
    nonisolated static func deleteFailureKey(_ error: Error) -> String {
        let ns = error as NSError
        if ns.domain == PHPhotosErrorDomain && ns.code == PHPhotosError.Code.userCancelled.rawValue { return "fail_cancelled" }
        return "fail_generic"
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
            let back = unfavoriteBackup.filter { ids.contains($0.photoId) }
            favorites.restore(back)
            favorites.add(ids.filter { id in !back.contains { $0.photoId == id } })
            unfavoriteBackup = []
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
            try? await Task.sleep(nanoseconds: UInt64(UndoBanner.lifetime * 1_000_000_000))
            if !Task.isCancelled { undoBanner = nil }
        }
    }

    /// 离开清理页：单张撤回条随清理页一起消失（Android ReviewViewModel 作用域）。
    func endReview() {
        reviewActive = false
        flushDeferredSeen()
        if undoBanner?.single == true { clearUndo() }
        if libraryDirty {
            libraryDirty = false
            Task { await reload(fresh: true) }
        }
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
            if DayKeyRule.isUnknown(d) {
                // 没有拍摄时间：单独一组「未知日期」，键 -1（最新在前时排最后、最早在前时排最前，与 Android 按 0 排序一致）
                if dayMap[-1] == nil { dayMap[-1] = (d, []); dayOrder.append(-1) }
                dayMap[-1]!.1.append(item)
                curStart = .distantFuture; curEnd = .distantPast
                continue
            }
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
            let y = key < 0 ? 0 : key / 10000
            let id = key < 0 ? DayKeyRule.unknown : String(format: "%04d-%02d-%02d", y, (key / 100) % 100, key % 100)
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

/// 跨 actor 传 PhotoKit 对象（PHChange / PHFetchResult 本身线程安全，只是没标 Sendable）。
struct UncheckedBox<T>: @unchecked Sendable {
    let value: T
    init(_ value: T) { self.value = value }
}
