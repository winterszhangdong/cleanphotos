import Combine
import Foundation
import Photos

struct FilingSummary {
    struct Line: Identifiable {
        var id: String
        var name: String
        var added: Int
        var total: Int
        var created: Bool
        /// 迷你袋子露角。装完之后这个相册按拍摄时间最新的一张。
        var coverId: String?
    }

    var lines: [Line]
    var unfavorited: Int
    var next: FavoriteDayRef?
}

struct FilingUndoBanner: Identifiable {
    let id = UUID()
    var title: String
    var amber: Bool
    var sampleId: String?
    var shownAt = Date()
}

struct FilingNotice: Identifiable, Equatable {
    let id = UUID()
    var text: String
}

/// 写入成功后交给视图播飞入。托盘顺序和小结要等 `finishFlight`。
struct FilingFlightEvent: Identifiable, Equatable {
    let id = UUID()
    var albumId: String
    var title: String
    var photoIds: [String]
    var fromMore: Bool
    var created: Bool
    var fromDrag: Bool
    var intoSheet: Bool

    static func == (lhs: FilingFlightEvent, rhs: FilingFlightEvent) -> Bool { lhs.id == rhs.id }
}

/// 写入失败或取消。拖放落回格子；面板里点的行收回高亮、面板留着。
struct FilingBounceEvent: Identifiable, Equatable {
    let id = UUID()
    var keepsSheet: Bool
}

/// 印样表：圈选这一天还在收藏里的照片，放进一个 Photos 相册（或只取消收藏）。
/// 放进相册 = 把 PHAsset 加进 PHAssetCollection，并从 FavoriteStore 移除。不搬文件，localIdentifier 不变。
@MainActor
final class ContactSheetModel: ObservableObject {
    @Published var dayId: String
    @Published private(set) var photos: [PhotoItem] = []
    @Published var circled: Set<String> = []
    @Published private(set) var tray: [FilingAlbumRef] = []
    @Published private(set) var allAlbums: [FilingAlbumRef] = []
    @Published private(set) var moreCount = 0
    @Published private(set) var summary: FilingSummary?
    @Published private(set) var undo: FilingUndoBanner?
    @Published private(set) var notice: FilingNotice?
    @Published private(set) var busy = false
    /// 只在这一次进入有效，默认关。下一天会清掉。
    @Published var systemFavorite = false
    /// 合盖之前停住的张数和露角。视图用 `HeldEnvelope.shown`。
    @Published private(set) var heldPick: HeldAlbumSnap?
    @Published private(set) var heldFlight: HeldAlbumSnap?
    @Published private(set) var flightEvent: FilingFlightEvent?
    @Published private(set) var bounceEvent: FilingBounceEvent?

    let filingLog: FilingLog

    private var library: PhotoLibraryService?
    private var membership: [String: AlbumMembership] = [:]
    private var session: [String]?
    private var promoted: [String] = []
    private var acted = false
    private var filedOrder: [String] = []
    private var filedCounts: [String: Int] = [:]
    private var filedNames: [String: String] = [:]
    private var createdIds = Set<String>()
    private var unfavoritedCount = 0
    private var optimistic: [PhotoItem] = []
    private var undoTask: Task<Void, Never>?
    private var payload: UndoPayload?
    /// 飞入没播完时，这些照片已经不在收藏里，格子仍留着。
    private var flightHold: [PhotoItem] = []
    private var flightActive = false
    private var pendingUndo: (UndoPayload, Locale)?

    private struct UndoPayload {
        enum Kind { case filed, unfavorited }
        var kind: Kind
        var items: [PhotoItem]
        var addedIds: [String]
        var alreadyIds: [String]
        var entries: [FavoriteEntry]
        var albumId: String
        var albumTitle: String
    }

    init(dayId: String, filingLog: FilingLog) {
        self.dayId = dayId
        self.filingLog = filingLog
    }

    func bind(_ library: PhotoLibraryService) {
        self.library = library
        libraryDidChange()
    }

    func libraryDidChange() {
        guard let library else { return }
        let fromSections = library.favoriteSections.lazy.flatMap(\.days).first { $0.id == dayId }?.allItems ?? []
        let have = Set(fromSections.map(\.id))
        var list = fromSections
        for item in optimistic where !have.contains(item.id) && library.favorites.contains(item.id) && DayKeyRule.key(item.creationDate) == dayId {
            list.append(item)
        }
        for item in flightHold where !have.contains(item.id) {
            list.append(item)
        }
        photos = list.sorted { a, b in
            if a.creationDate != b.creationDate { return a.creationDate < b.creationDate }
            return a.id < b.id
        }
        let ids = Set(photos.map(\.id))
        circled = circled.intersection(ids)
        optimistic.removeAll { have.contains($0.id) || !library.favorites.contains($0.id) }
        refreshSummary()
    }

    func loadAlbums() async {
        await reloadMembership()
    }

    func toggle(_ id: String) {
        guard !busy, photos.contains(where: { $0.id == id }) else { return }
        if circled.contains(id) { circled.remove(id) } else { circled.insert(id) }
    }

    /// 横着划过：只圈不擦。
    func circle(_ id: String) {
        guard !busy, photos.contains(where: { $0.id == id }) else { return }
        circled.insert(id)
    }

    func circleAll() {
        guard !busy else { return }
        circled = Set(photos.map(\.id))
    }

    func clearCircles() {
        guard !busy else { return }
        circled = []
    }

    func openDay(_ id: String) {
        guard id != dayId else { return }
        dayId = id
        circled = []
        acted = false
        filedOrder = []
        filedCounts = [:]
        filedNames = [:]
        createdIds = []
        unfavoritedCount = 0
        session = nil
        promoted = []
        systemFavorite = false
        optimistic = []
        flightHold = []
        flightActive = false
        heldPick = nil
        heldFlight = nil
        flightEvent = nil
        summary = nil
        notice = nil
        clearUndo()
        libraryDidChange()
        realign()
    }

    func dismissNotice(_ id: UUID) {
        if notice?.id == id { notice = nil }
    }

    func fileExisting(id: String, fromMore: Bool, fromDrag: Bool, intoSheet: Bool, locale: Locale) async {
        guard let album = allAlbums.first(where: { $0.id == id }) ?? tray.first(where: { $0.id == id }) else { return }
        await commit(albumId: id, title: album.title, create: false, fromMore: fromMore, fromDrag: fromDrag, intoSheet: intoSheet, locale: locale)
    }

    func fileNew(name: String, fromDrag: Bool, locale: Locale) async {
        let trimmed = AlbumNameRules.sanitize(name).trimmingCharacters(in: .whitespacesAndNewlines)
        guard AlbumNameRules.isValid(trimmed) else { return }
        if let existing = AlbumNameRules.existing(name: trimmed, albums: allAlbums) {
            await commit(albumId: existing.id, title: existing.title, create: false, fromMore: true, fromDrag: fromDrag, intoSheet: false, locale: locale)
            return
        }
        await commit(albumId: nil, title: trimmed, create: true, fromMore: true, fromDrag: fromDrag, intoSheet: false, locale: locale)
    }

    func recentAssets(for albumId: String) -> [PHAsset] {
        membership[albumId]?.recentAssets ?? []
    }

    func asset(id: String) -> PHAsset? {
        if let item = photos.first(where: { $0.id == id }) { return item.asset }
        for album in membership.values {
            if let asset = album.recentAssets.first(where: { $0.localIdentifier == id }) { return asset }
        }
        return library?.item(id: id)?.asset
    }

    /// 封口合上：放开停住的张数和露角，撤回胶囊出现。托盘顺序仍等到飞入结束。
    func markFlightReleased() {
        heldFlight = nil
        heldPick = nil
        patchDisplayedCounts()
        if let pending = pendingUndo {
            pendingUndo = nil
            armUndo(pending.0, locale: pending.1)
        }
    }

    /// 飞入整段结束：插队、重排、允许小结。
    func finishFlight(albumId: String, fromMore: Bool) async {
        flightHold = []
        flightEvent = nil
        flightActive = false
        busy = false
        libraryDidChange()
        if fromMore {
            promoteIfNeeded(albumId)
        } else {
            realign()
        }
        refreshSummary()
        let createdTitle = createdIds.contains(albumId) ? filedNames[albumId] : nil
        await reloadMembership()
        if let createdTitle, membership[albumId] == nil,
           let match = membership.values.first(where: { AlbumNameRules.normalize($0.title) == AlbumNameRules.normalize(createdTitle) }) {
            remap(from: albumId, to: match.id)
            realign()
        }
        refreshSummary()
    }

    func unfavoriteCircled(locale: Locale) {
        guard !busy, let library else { return }
        let items = photos.filter { circled.contains($0.id) }
        guard !items.isEmpty else { return }
        let ids = items.map(\.id)
        let entries = ids.compactMap { library.favorites.entries[$0] }
        library.unfavorite(ids: ids, showUndo: false)
        acted = true
        unfavoritedCount += entries.count
        circled = []
        armUndo(UndoPayload(
            kind: .unfavorited, items: items, addedIds: [], alreadyIds: [],
            entries: entries, albumId: "", albumTitle: ""
        ), locale: locale)
        libraryDidChange()
    }

    func undo(locale: Locale) async {
        guard !busy, let library, let current = payload else { return }
        if let undo, Date().timeIntervalSince(undo.shownAt) >= UndoBanner.lifetime {
            clearUndo()
            return
        }
        clearUndo()
        busy = true
        defer { busy = false }
        switch current.kind {
        case .unfavorited:
            restore(current.items, entries: current.entries, library: library)
            unfavoritedCount = max(0, unfavoritedCount - current.entries.count)
            libraryDidChange()
            await library.reload(fresh: true)
            libraryDidChange()
        case .filed:
            var failedRemove = 0
            if !current.addedIds.isEmpty {
                do {
                    try await AlbumLibrary.remove(assetIds: current.addedIds, albumId: current.albumId)
                } catch {
                    failedRemove = current.addedIds.count
                }
            }
            let restoreIds: Set<String>
            if failedRemove > 0 {
                restoreIds = Set(current.alreadyIds)
                notice = FilingNotice(text: FilingCopy.undoFailed(failedRemove, locale: locale))
            } else {
                restoreIds = Set(current.addedIds + current.alreadyIds)
            }
            let back = current.entries.filter { restoreIds.contains($0.photoId) }
            if !back.isEmpty {
                let items = current.items.filter { restoreIds.contains($0.id) }
                restore(items, entries: back, library: library)
            }
            if !restoreIds.isEmpty {
                filingLog.remove(assetIds: Array(restoreIds))
                filedCounts[current.albumId, default: 0] -= restoreIds.count
                if (filedCounts[current.albumId] ?? 0) <= 0 {
                    filedCounts[current.albumId] = 0
                    createdIds.remove(current.albumId)
                }
            }
            libraryDidChange()
            await library.reload(fresh: true)
            libraryDidChange()
            await reloadMembership()
        }
    }

    private func commit(albumId: String?, title: String, create: Bool, fromMore: Bool, fromDrag: Bool, intoSheet: Bool, locale: Locale) async {
        guard !busy, let library else { return }
        let selected = photos.filter { circled.contains($0.id) }
        guard !selected.isEmpty else { return }
        busy = true
        var flying = false
        defer { if !flying { busy = false } }

        let members = albumId.flatMap { membership[$0]?.memberIds } ?? []
        let split = FilingPartition.split(selected: selected.map(\.id), members: members)
        let before = albumId.flatMap { membership[$0] }
        let frozen = HeldAlbumSnap(id: albumId ?? "", count: before?.count ?? 0, coverId: before?.recentIds.first)
        heldPick = albumId == nil ? nil : frozen
        heldFlight = heldPick

        var resultAlbum = albumId ?? ""
        var added: [String] = []
        var failed: [String] = []
        let didCreate = create

        if create {
            do {
                let result = try await AlbumLibrary.create(title: title, assetIds: split.toAdd)
                guard !result.albumId.isEmpty, !result.added.isEmpty else {
                    notice = FilingNotice(text: FilingCopy.filingFailed(max(split.toAdd.count, 1), locale: locale))
                    failCommit(keepsSheet: intoSheet)
                    return
                }
                resultAlbum = result.albumId
                added = result.added
                failed = result.failed
            } catch {
                notice = FilingNotice(text: FilingCopy.filingFailed(max(split.toAdd.count, 1), locale: locale))
                failCommit(keepsSheet: intoSheet)
                return
            }
        } else if !split.toAdd.isEmpty {
            do {
                let result = try await AlbumLibrary.add(assetIds: split.toAdd, albumId: resultAlbum)
                added = result.added
                failed = result.failed
            } catch {
                notice = FilingNotice(text: FilingCopy.filingFailed(split.toAdd.count, locale: locale))
                failCommit(keepsSheet: intoSheet)
                return
            }
        }

        let filedIds = split.already + added
        guard !filedIds.isEmpty else {
            if !failed.isEmpty { notice = FilingNotice(text: FilingCopy.filingFailed(failed.count, locale: locale)) }
            failCommit(keepsSheet: intoSheet)
            return
        }

        let filedSet = Set(filedIds)
        let items = selected.filter { filedSet.contains($0.id) }
        let entries = filedIds.compactMap { library.favorites.entries[$0] }
        library.unfavorite(ids: filedIds, showUndo: false)
        filingLog.record(assetIds: filedIds, albumTitle: title, dayKey: dayId)

        acted = true
        if filedCounts[resultAlbum] == nil { filedOrder.append(resultAlbum) }
        filedCounts[resultAlbum, default: 0] += filedIds.count
        filedNames[resultAlbum] = title
        if didCreate { createdIds.insert(resultAlbum) }
        rememberMembers(albumId: resultAlbum, title: title, added: items.filter { Set(added).contains($0.id) }, created: didCreate)
        let held = HeldAlbumSnap(id: resultAlbum, count: frozen.count, coverId: frozen.coverId)
        heldPick = held
        heldFlight = held

        circled.subtract(filedSet)
        flightHold = items
        pendingUndo = (UndoPayload(
            kind: .filed, items: items, addedIds: added, alreadyIds: split.already,
            entries: entries, albumId: resultAlbum, albumTitle: title
        ), locale)
        if !failed.isEmpty {
            notice = FilingNotice(text: FilingCopy.filingFailed(failed.count, locale: locale))
        }
        libraryDidChange()
        flying = true
        flightActive = true
        flightEvent = FilingFlightEvent(
            albumId: resultAlbum, title: title, photoIds: items.map(\.id),
            fromMore: fromMore, created: didCreate, fromDrag: fromDrag, intoSheet: intoSheet
        )

        if systemFavorite {
            _ = await AlbumLibrary.markSystemFavorite(filedIds)
        }
    }

    private func failCommit(keepsSheet: Bool) {
        heldPick = nil
        heldFlight = nil
        bounceEvent = FilingBounceEvent(keepsSheet: keepsSheet)
    }

    /// 合盖后把托盘上看得见的张数和露角换成装完的，不改顺序。
    private func patchDisplayedCounts() {
        func patch(_ album: FilingAlbumRef) -> FilingAlbumRef {
            guard let current = membership[album.id] else { return album }
            var copy = album
            copy.count = current.count
            copy.recentIds = current.recentIds
            return copy
        }
        tray = tray.map(patch)
        allAlbums = allAlbums.map(patch)
    }

    private func rememberMembers(albumId: String, title: String, added: [PhotoItem], created: Bool) {
        if created {
            let recent = SheetPrints.of(added.map { SheetPrintRef(id: $0.id, taken: $0.creationDate.timeIntervalSince1970) }, limit: 5)
            let assets = recent.compactMap { ref in added.first { $0.id == ref.id }?.asset }
            membership[albumId] = AlbumMembership(
                id: albumId, title: title, count: added.count, memberIds: Set(added.map(\.id)),
                recentIds: assets.map(\.localIdentifier), recentAssets: assets
            )
            return
        }
        guard var current = membership[albumId] else { return }
        current.memberIds.formUnion(added.map(\.id))
        current.count = current.memberIds.count
        let merged = mergedRecent(existing: current.recentAssets, added: added)
        current.recentIds = merged.map(\.localIdentifier)
        current.recentAssets = merged
        membership[albumId] = current
    }

    private func mergedRecent(existing: [PHAsset], added: [PhotoItem]) -> [PHAsset] {
        var byId: [String: (Date, PHAsset)] = [:]
        for asset in existing {
            byId[asset.localIdentifier] = (asset.creationDate ?? .distantPast, asset)
        }
        for item in added {
            byId[item.id] = (item.creationDate, item.asset)
        }
        return byId.sorted { a, b in
            if a.value.0 != b.value.0 { return a.value.0 > b.value.0 }
            return SheetPrints.idIsAfter(a.key, b.key)
        }.prefix(5).map(\.value.1)
    }

    private func restore(_ items: [PhotoItem], entries: [FavoriteEntry], library: PhotoLibraryService) {
        library.favorites.restore(entries)
        optimistic.append(contentsOf: items)
    }

    private func reloadMembership() async {
        let fetched = await AlbumLibrary.fetchUserAlbums()
        membership = Dictionary(uniqueKeysWithValues: fetched.map { ($0.id, $0) })
        realign()
        refreshSummary()
    }

    private func remap(from old: String, to new: String) {
        guard old != new else { return }
        if let count = filedCounts.removeValue(forKey: old) {
            filedCounts[new, default: 0] += count
        }
        if let index = filedOrder.firstIndex(of: old) { filedOrder[index] = new }
        filedNames[new] = filedNames[new] ?? filedNames[old]
        if createdIds.remove(old) != nil { createdIds.insert(new) }
        session = session?.map { $0 == old ? new : $0 }
        promoted = promoted.map { $0 == old ? new : $0 }
        if var current = payload, current.albumId == old {
            current.albumId = new
            payload = current
        }
    }

    private func realign() {
        let last = filingLog.lastFiledAtByTitle()
        let refs = membership.values.map { album in
            FilingAlbumRef(
                id: album.id,
                title: album.title,
                count: album.count,
                lastFiledAt: last[AlbumNameRules.normalize(album.title)],
                recentIds: album.recentIds
            )
        }
        let sorted = TrayRules.sorted(refs)
        allAlbums = sorted
        if session == nil {
            if !sorted.isEmpty { session = sorted.map(\.id) }
        } else {
            session = TrayRules.sessionOrder(current: session, albums: sorted)
        }
        let arranged = TrayRules.arrange(albums: sorted, session: session ?? [], promoted: promoted)
        tray = TrayRules.visible(arranged)
        moreCount = TrayRules.moreCount(total: sorted.count)
    }

    private func promoteIfNeeded(_ id: String) {
        let arranged = TrayRules.arrange(albums: allAlbums, session: session ?? [], promoted: promoted)
        promoted = TrayRules.promote(tray: arranged, promoted: promoted, id: id)
        realign()
    }

    private func refreshSummary() {
        guard photos.isEmpty, acted else {
            summary = nil
            return
        }
        guard let library else { summary = nil; return }
        let lines: [FilingSummary.Line] = filedOrder.compactMap { id in
            let added = filedCounts[id] ?? 0
            guard added > 0 else { return nil }
            return FilingSummary.Line(
                id: id,
                name: filedNames[id] ?? membership[id]?.title ?? "",
                added: added,
                total: membership[id]?.count ?? added,
                created: createdIds.contains(id),
                coverId: membership[id]?.recentIds.first
            )
        }
        var days: [FavoriteDayRef] = []
        for year in library.favoriteSections {
            for day in year.days {
                days.append(FavoriteDayRef(dayKey: day.id, count: day.totalCount, coverId: day.allItems.first?.id ?? ""))
            }
        }
        summary = FilingSummary(lines: lines, unfavorited: unfavoritedCount, next: NextDayRule.next(after: dayId, daysNewestFirst: days))
    }

    private func armUndo(_ next: UndoPayload, locale: Locale) {
        payload = next
        undoTask?.cancel()
        let title: String
        let amber: Bool
        switch next.kind {
        case .filed:
            let count = next.addedIds.count + next.alreadyIds.count
            title = FilingCopy.undoFiled(count, name: next.albumTitle, locale: locale)
            amber = false
        case .unfavorited:
            title = L10n.plural("batch_undo_unfavorite", count: next.entries.count, locale: locale)
            amber = true
        }
        undo = FilingUndoBanner(title: title, amber: amber, sampleId: next.items.first?.id)
        undoTask = Task {
            try? await Task.sleep(nanoseconds: UInt64(UndoBanner.lifetime * 1_000_000_000))
            if !Task.isCancelled { clearUndo() }
        }
    }

    private func clearUndo() {
        undoTask?.cancel()
        undoTask = nil
        undo = nil
        payload = nil
    }
}
