import Foundation
import Combine

struct FilingSummary {
    struct Line: Identifiable {
        var id: String
        var name: String
        var added: Int
        var total: Int
        var created: Bool
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
        summary = nil
        notice = nil
        clearUndo()
        libraryDidChange()
        realign()
    }

    func dismissNotice(_ id: UUID) {
        if notice?.id == id { notice = nil }
    }

    func fileExisting(id: String, fromMore: Bool, locale: Locale) async {
        guard let album = allAlbums.first(where: { $0.id == id }) else { return }
        await commit(albumId: id, title: album.title, create: false, fromMore: fromMore, locale: locale)
    }

    func fileNew(name: String, locale: Locale) async {
        let trimmed = AlbumNameRules.sanitize(name).trimmingCharacters(in: .whitespacesAndNewlines)
        guard AlbumNameRules.isValid(trimmed) else { return }
        if let existing = AlbumNameRules.existing(name: trimmed, albums: allAlbums) {
            await commit(albumId: existing.id, title: existing.title, create: false, fromMore: true, locale: locale)
            return
        }
        await commit(albumId: nil, title: trimmed, create: true, fromMore: true, locale: locale)
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

    private func commit(albumId: String?, title: String, create: Bool, fromMore: Bool, locale: Locale) async {
        guard !busy, let library else { return }
        let selected = photos.filter { circled.contains($0.id) }
        guard !selected.isEmpty else { return }
        busy = true
        defer { busy = false }

        let members = albumId.flatMap { membership[$0]?.memberIds } ?? []
        let split = FilingPartition.split(selected: selected.map(\.id), members: members)

        var resultAlbum = albumId ?? ""
        var added: [String] = []
        var failed: [String] = []
        let didCreate = create

        if create {
            do {
                let result = try await AlbumLibrary.create(title: title, assetIds: split.toAdd)
                guard !result.albumId.isEmpty, !result.added.isEmpty else {
                    notice = FilingNotice(text: FilingCopy.filingFailed(max(split.toAdd.count, 1), locale: locale))
                    return
                }
                resultAlbum = result.albumId
                added = result.added
                failed = result.failed
            } catch {
                notice = FilingNotice(text: FilingCopy.filingFailed(max(split.toAdd.count, 1), locale: locale))
                return
            }
        } else if !split.toAdd.isEmpty {
            do {
                let result = try await AlbumLibrary.add(assetIds: split.toAdd, albumId: resultAlbum)
                added = result.added
                failed = result.failed
            } catch {
                // 整批没放进去：已经在相册里的也不算装好，圈选留着，可以再点一次。
                notice = FilingNotice(text: FilingCopy.filingFailed(split.toAdd.count, locale: locale))
                return
            }
        }

        let filedIds = split.already + added
        guard !filedIds.isEmpty else {
            if !failed.isEmpty { notice = FilingNotice(text: FilingCopy.filingFailed(failed.count, locale: locale)) }
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
        rememberMembers(albumId: resultAlbum, title: title, added: added, created: didCreate)

        circled.subtract(filedSet)
        armUndo(UndoPayload(
            kind: .filed, items: items, addedIds: added, alreadyIds: split.already,
            entries: entries, albumId: resultAlbum, albumTitle: title
        ), locale: locale)
        if !failed.isEmpty {
            notice = FilingNotice(text: FilingCopy.filingFailed(failed.count, locale: locale))
        }
        libraryDidChange()
        if fromMore { promoteIfNeeded(resultAlbum) } else { realign() }

        if systemFavorite {
            _ = await AlbumLibrary.markSystemFavorite(filedIds)
        }
        let createdId = didCreate ? resultAlbum : nil
        await reloadMembership()
        if let createdId, membership[createdId] == nil,
           let match = membership.values.first(where: { AlbumNameRules.normalize($0.title) == AlbumNameRules.normalize(title) }) {
            remap(from: createdId, to: match.id)
            realign()
            refreshSummary()
        }
    }

    private func rememberMembers(albumId: String, title: String, added: [String], created: Bool) {
        if created {
            membership[albumId] = AlbumMembership(id: albumId, title: title, count: added.count, memberIds: Set(added))
            return
        }
        guard var current = membership[albumId] else { return }
        current.memberIds.formUnion(added)
        current.count = current.memberIds.count
        membership[albumId] = current
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
                lastFiledAt: last[AlbumNameRules.normalize(album.title)]
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
                created: createdIds.contains(id)
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
