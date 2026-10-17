import Foundation
import SwiftUI

struct ReviewLaunch: Identifiable, Hashable {
    let id = UUID()
    let scope: ReviewScope
    let startPhotoId: String?
    let dayId: String?
}

enum ReviewPage: Identifiable, Hashable {
    case photo(PhotoItem)
    case summary(DaySummary)

    var id: String {
        switch self {
        case .photo(let p): return LedgerPage.photo(p.id).key
        case .summary(let s): return LedgerPage.summary(s.dayId).key
        }
    }
}

/// 小结插页内容（Android BuildDaySummaryUseCase）。
struct DaySummary: Hashable {
    let dayId: String
    let date: Date
    let tally: DayTally
    let month: [MonthDayState]
    let monthDone: Int
    let monthWithPhotos: Int
    let nextDayId: String?
    let nextDayCoverId: String?
    let nextDayUnseen: Int
    let cells: [DayCellState]

    var favorited: Int { tally.favorited }
    var trashed: Int { tally.trashed }
    var kept: Int { tally.kept }
    var unfavorited: Int { tally.unfavorited }
    var bytesFreed: Int64 { tally.freedBytes }
    var isLastDay: Bool { nextDayId == nil }
}

/// 清理会话的 UI 适配层：状态全部在纯 Swift 的 `ReviewSessionCore`（与 Android ReviewViewModel 同规则、有单测），
/// 这里只把 key 映射回 PhotoItem / DaySummary，并把处理结果写进 PhotoLibraryService。
@MainActor
final class ReviewSessionModel: ObservableObject {
    @Published private(set) var pages = ReviewPageList()
    @Published private(set) var index: Int = 0

    let scope: ReviewScope
    private var core: ReviewSessionCore
    private let photosById: [String: PhotoItem]
    private weak var library: PhotoLibraryService?

    init(scope: ReviewScope, items: [PhotoItem], startIndex: Int, library: PhotoLibraryService? = nil) {
        self.scope = scope
        self.library = library
        var byId: [String: PhotoItem] = [:]; byId.reserveCapacity(items.count)
        for p in items { byId[p.id] = p }
        photosById = byId
        let ledgerPhotos = items.map { LedgerPhoto(id: $0.id, day: Self.dayKey($0.creationDate), size: 0) }
        let start = items.indices.contains(startIndex) ? items[startIndex].id : items.first?.id
        core = ReviewSessionCore(photos: ledgerPhotos, startId: start)
        publish()
    }

    typealias CellState = DayCellState

    var currentPhoto: PhotoItem? {
        guard pages.indices.contains(index), case .photo(let p) = pages[index] else { return nil }
        return p
    }

    var isOnSummary: Bool {
        guard pages.indices.contains(index), case .summary = pages[index] else { return false }
        return true
    }

    var isCompleted: Bool { core.isCompleted }

    /// 「yyyy-MM-dd」（本地时区）。清理页每帧要算好几次（进度条 / 小结 / 月历），Calendar + String(format:) 不便宜，按时间戳缓存；
    /// 系统时区变了清空。
    static func dayKey(_ date: Date) -> String {
        let t = date.timeIntervalSinceReferenceDate
        _ = DayKeyCache.observer
        DayKeyCache.lock.lock()
        if !PerfLegacy.on, let v = DayKeyCache.map[t] { DayKeyCache.lock.unlock(); return v }
        DayKeyCache.lock.unlock()
        let c = Calendar.current.dateComponents([.year, .month, .day], from: date)
        let v = String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
        DayKeyCache.lock.lock()
        if DayKeyCache.map.count > 50_000 { DayKeyCache.map.removeAll(keepingCapacity: true) }
        DayKeyCache.map[t] = v
        DayKeyCache.lock.unlock()
        return v
    }

    func cells(for photo: PhotoItem) -> [DayCellState] {
        core.ledger.dayCells(day: Self.dayKey(photo.creationDate), current: photo.id)
    }

    func positionInDay(_ photo: PhotoItem) -> Int {
        (core.ledger.photosOfDay(Self.dayKey(photo.creationDate)).firstIndex { $0.id == photo.id } ?? 0) + 1
    }

    /// 看过由翻走时（settle）记账，这里不需要再做。保留接口给视图调用。
    func markKeptCurrent() {}

    func applyVertical(favorite: Bool, unfavorite: Bool, trash: Bool, library: PhotoLibraryService) {
        guard let photo = currentPhoto else { return }
        let outcome: ReviewOutcome
        if favorite {
            _ = library.favorite(ids: [photo.id], showUndo: true); outcome = .favorited
        } else if unfavorite {
            _ = library.unfavorite(ids: [photo.id], showUndo: true); outcome = .unfavorited
        } else {
            _ = library.moveToTrash(ids: [photo.id], showUndo: true); outcome = .trashed
        }
        library.markSeen([photo.id])
        core.act(photo.id, outcome)
        if let next = ReviewSessionCore.photoId(of: core.currentKey) { library.markSeen([next]) }
        publish()
    }

    func reinsert(_ photo: PhotoItem, kind: ExitKind) {
        core.undo(photo.id)
        publish()
    }

    func goNext() { move(+1) }
    func goPrevious() { move(-1) }

    func skipSummaryToNext() { if isOnSummary { move(+1) } }

    private func move(_ d: Int) {
        let j = index + d
        guard core.pages.indices.contains(j) else { return }
        core.settle(on: core.pages[j].key)
        publish()
    }

    // MARK: - 映射

    /// 页序只交出 ledger 的 key 列表，照片页按下标取时才映射（见 ReviewPageList）；小结页（最多一两张）在这里算好。
    /// 之前每次收藏 / 翻页都把整个会话（几千张）重新映射、拷贝一遍，正好落在松手那一帧。
    private func publish() {
        let lib = library
        var monthCache: [String: (days: [MonthDayState], done: Int, withPhotos: Int)] = [:]
        var summaries: [String: DaySummary] = [:]
        for page in core.pages {
            if case .summary(let day) = page { summaries[day] = buildSummary(day, library: lib, monthCache: &monthCache) }
        }
        pages = ReviewPageList(keys: core.pages, photos: photosById, summaries: summaries)
        index = core.currentIndex ?? 0
    }

    private func buildSummary(_ day: String, library: PhotoLibraryService?, monthCache: inout [String: (days: [MonthDayState], done: Int, withPhotos: Int)]) -> DaySummary {
        let ledger = core.ledger
        var tally = DayTally.of(ledger, day: day)
        // 大小在会话里懒取（AssetSizeCache），小结时补齐「释放」。
        tally.freedBytes = ledger.photosOfDay(day).filter { ledger.outcomes[$0.id] == .trashed }
            .reduce(Int64(0)) { $0 + (photosById[$1.id]?.byteSize ?? 0) }
        let days = ledger.daysInOrder
        let nextDay = days.firstIndex(of: day).flatMap { $0 + 1 < days.count ? days[$0 + 1] : nil }
        let nextPhotos = nextDay.map { ledger.photosOfDay($0) } ?? []
        let seen = library?.seen.seenIds ?? []
        let unseen = nextPhotos.filter { !seen.contains($0.id) && !ledger.viewed.contains($0.id) }.count
        let monthKey = String(day.prefix(7))
        let month: (days: [MonthDayState], done: Int, withPhotos: Int)
        if let m = monthCache[monthKey] { month = m } else {
            var byDay: [String: [String]] = [:]
            var processed = Set<String>()
            if let library {
                for (id, p) in library.allById {
                    let k = Self.dayKey(p.creationDate)
                    guard k.hasPrefix(monthKey) else { continue }
                    byDay[k, default: []].append(id)
                    if library.favorites.contains(id) || library.trash.contains(id) { processed.insert(id) }
                }
            }
            month = MonthProgressBuilder.build(monthOf: day, photosByDay: byDay, processed: processed,
                                               seen: seen.union(ledger.viewed), today: Self.dayKey(Date()))
            monthCache[monthKey] = month
        }
        let date = ledger.photosOfDay(day).first.flatMap { photosById[$0.id]?.creationDate } ?? Date()
        return DaySummary(dayId: day, date: date, tally: tally, month: month.days, monthDone: month.done,
                          monthWithPhotos: month.withPhotos, nextDayId: nextDay, nextDayCoverId: nextPhotos.first?.id,
                          nextDayUnseen: unseen, cells: ledger.dayCells(day: day, current: nil))
    }
}

/// 清理会话的页序（RandomAccessCollection）：持有 ledger key 列表 + 照片字典（写时复制，O(1) 赋值），下标访问时才映射成 ReviewPage。
/// 会话里的照片与 ledger 同源（ReviewSessionModel.init 用同一组 items 建），照片 key 总能在字典里找到。
struct ReviewPageList: RandomAccessCollection {
    private let keys: [LedgerPage]
    private let photos: [String: PhotoItem]
    private let summaries: [String: DaySummary]

    init(keys: [LedgerPage] = [], photos: [String: PhotoItem] = [:], summaries: [String: DaySummary] = [:]) {
        self.photos = photos
        self.summaries = summaries
        self.keys = keys
    }

    var startIndex: Int { 0 }
    var endIndex: Int { keys.count }
    subscript(i: Int) -> ReviewPage {
        switch keys[i] {
        case .photo(let id): return .photo(photos[id]!)
        case .summary(let d): return .summary(summaries[d]!)
        }
    }
}

enum DayKeyCache {
    nonisolated(unsafe) static var map: [TimeInterval: String] = [:]
    static let lock = NSLock()
    nonisolated(unsafe) static let observer: NSObjectProtocol = NotificationCenter.default.addObserver(
        forName: .NSSystemTimeZoneDidChange, object: nil, queue: nil) { _ in
        lock.lock(); map.removeAll(); lock.unlock()
    }
}
