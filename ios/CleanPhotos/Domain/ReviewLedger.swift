import Foundation

// 清理会话的纯逻辑（无 SwiftUI / PhotoKit 依赖，可单测）。逐条对应 Android：
//   domain/model/DayReview.kt            → ReviewLedger / ReviewOutcome / DayCellState / MonthDayState
//   domain/usecase/ShouldInsertDaySummaryUseCase.kt → DaySummaryRule.shouldInsert
//   domain/usecase/BuildReviewPagesUseCase.kt       → ReviewPagesBuilder.build
//   domain/usecase/BuildSessionDayProgressUseCase.kt → ReviewLedger.dayCells
//   domain/usecase/BuildMonthProgressUseCase.kt     → MonthProgressBuilder.build
//   feature/review/ReviewViewModel.kt (act / onPageSettled / onUndo) → ReviewSessionCore

enum ReviewRules {
    /// 小结插页最少张数（Android ReviewRules.DAY_SUMMARY_MIN_PHOTOS）。
    static let daySummaryMinPhotos = 6
    /// 当天进度条超过 40 张改为连续比例条。
    static let progressContinuousThreshold = 40
    /// 「未知日期」天键：不生成小结。
    static let unknownDay = "unknown"
}

/// 账本里的一张照片：只要 id、所在天（yyyy-MM-dd）和大小。
struct LedgerPhoto: Hashable {
    let id: String
    let day: String
    var size: Int64 = 0
}

enum ReviewOutcome: Hashable { case favorited, unfavorited, trashed }

enum DayCellState: Hashable { case favorited, unfavorited, trashed, kept, current, pending }

enum MonthDayState: Hashable { case done, highlight, pending, empty }

/// 会话账本：会话**初始**顺序（继承入口排序 / 筛选）+ 本次会话看过 / 处理过的记录。
struct ReviewLedger {
    let ordered: [LedgerPhoto]
    var viewed: Set<String> = []
    var outcomes: [String: ReviewOutcome] = [:]
    var summarized: Set<String> = []
    private let index: Index

    final class Index {
        let byId: [String: Int]
        let byDay: [String: [LedgerPhoto]]
        let daysInOrder: [String]
        init(_ photos: [LedgerPhoto]) {
            var m: [String: Int] = [:]; m.reserveCapacity(photos.count)
            var d: [String: [LedgerPhoto]] = [:]
            var order: [String] = []
            for (i, p) in photos.enumerated() {
                m[p.id] = i
                if d[p.day] == nil { order.append(p.day) }
                d[p.day, default: []].append(p)
            }
            byId = m; byDay = d; daysInOrder = order
        }
    }

    init(ordered: [LedgerPhoto]) {
        self.ordered = ordered
        self.index = Index(ordered)
    }

    func indexOf(_ id: String) -> Int? { index.byId[id] }
    func photo(_ id: String) -> LedgerPhoto? { index.byId[id].map { ordered[$0] } }
    func photosOfDay(_ day: String) -> [LedgerPhoto] { index.byDay[day] ?? [] }
    var daysInOrder: [String] { index.daysInOrder }

    func markingViewed(_ id: String) -> ReviewLedger { var l = self; l.viewed.insert(id); return l }
    mutating func markViewed(_ id: String) { viewed.insert(id) }
    mutating func record(_ id: String, _ outcome: ReviewOutcome) { outcomes[id] = outcome; viewed.insert(id) }
    /// 撤回：去掉处理结果，照片仍算看过。
    mutating func undoOutcome(_ id: String) { outcomes.removeValue(forKey: id) }
    mutating func markSummarized(_ day: String) { summarized.insert(day) }
    func isTouched(_ id: String) -> Bool { viewed.contains(id) || outcomes[id] != nil }

    /// 当天进度条每格（BuildSessionDayProgressUseCase）。current 为 nil 时没有「当前」格（小结页）。
    func dayCells(day: String, current: String?) -> [DayCellState] {
        photosOfDay(day).map { p in
            if p.id == current { return .current }
            switch outcomes[p.id] {
            case .favorited: return .favorited
            case .unfavorited: return .unfavorited
            case .trashed: return .trashed
            case nil: return viewed.contains(p.id) ? .kept : .pending
            }
        }
    }
}

enum DaySummaryRule {
    /// 是否在离开 from 时插入它那一天的小结（决定 2）：
    /// 1. 往后走且跨天（to == nil 表示会话末尾，也算离开）；往回不触发；
    /// 2. 那一天会话内全部看过或处理过（跳过 = 保留）；
    /// 3. 那一天至少 6 张；4. 同一天一个会话最多一次；5. 未知日期不生成。
    /// 调用方先把 from 记为看过 / 处理过。
    static func shouldInsert(_ ledger: ReviewLedger, from: String, to: String?) -> Bool {
        guard let f = ledger.photo(from), let fi = ledger.indexOf(from) else { return false }
        if let to {
            guard let t = ledger.photo(to), let ti = ledger.indexOf(to) else { return false }
            if ti <= fi { return false }
            if t.day == f.day { return false }
        }
        let day = f.day
        if day == ReviewRules.unknownDay { return false }
        if ledger.summarized.contains(day) { return false }
        let photos = ledger.photosOfDay(day)
        if photos.count < ReviewRules.daySummaryMinPhotos { return false }
        return photos.allSatisfy { ledger.isTouched($0.id) }
    }
}

/// 翻页序列里的一页：照片或某天的小结插页。key 与 Android ReviewPage.key 同构。
enum LedgerPage: Hashable {
    case photo(String)
    case summary(String)
    var key: String {
        switch self {
        case .photo(let id): return "p-\(id)"
        case .summary(let d): return "s-\(d)"
        }
    }
}

enum ReviewPagesBuilder {
    /// 会话初始顺序里没被移走的照片 + 小结插页：
    /// - 正在看的小结（activeSummaryDay）放在那一天最后一张之后、下一组日期第一张之前；
    /// - 预判：当前是照片，且「离开它往后走」就满足小结条件 → 小结提前放在它后面（拖动时侧边就是小结）。
    static func build(_ ledger: ReviewLedger, removed: Set<String>, current: String?, activeSummaryDay: String?) -> [LedgerPage] {
        let remaining = ledger.ordered.filter { !removed.contains($0.id) }
        var pages = remaining.map { LedgerPage.photo($0.id) }
        if let day = activeSummaryDay {
            let lastOfDay = ledger.ordered.lastIndex { $0.day == day } ?? -1
            let at = remaining.firstIndex { (ledger.indexOf($0.id) ?? 0) > lastOfDay } ?? pages.count
            pages.insert(.summary(day), at: at)
            return pages
        }
        if let current, !removed.contains(current), let at = remaining.firstIndex(where: { $0.id == current }) {
            let next = at + 1 < remaining.count ? remaining[at + 1].id : nil
            if DaySummaryRule.shouldInsert(ledger.markingViewed(current), from: current, to: next),
               let day = ledger.photo(current)?.day {
                pages.insert(.summary(day), at: at + 1)
            }
        }
        return pages
    }
}

/// 会话状态机（ReviewViewModel 的 act / onPageSettled / onUndo）。值类型，可单测。
struct ReviewSessionCore {
    private(set) var ledger: ReviewLedger
    private(set) var removed: Set<String> = []
    private(set) var currentKey: String?
    private(set) var activeSummaryDay: String?
    private(set) var pages: [LedgerPage] = []

    init(photos: [LedgerPhoto], startId: String?) {
        ledger = ReviewLedger(ordered: photos)
        let start = startId.flatMap { id in photos.contains { $0.id == id } ? id : nil } ?? photos.first?.id
        currentKey = start.map { LedgerPage.photo($0).key }
        rebuild()
    }

    /// 按枚举比较（不为每一页拼 "p-…" 字符串）：会话几千张时每次收藏 / 翻页都要找一遍。
    var currentIndex: Int? {
        guard let k = currentKey else { return nil }
        let target: LedgerPage
        if let id = Self.photoId(of: k) { target = .photo(id) }
        else if let d = Self.summaryDay(of: k) { target = .summary(d) }
        else { return pages.firstIndex { $0.key == k } }
        return pages.firstIndex(of: target)
    }
    var currentPage: LedgerPage? { currentIndex.map { pages[$0] } }
    var isCompleted: Bool { currentKey == nil }

    static func photoId(of key: String?) -> String? {
        guard let key, key.hasPrefix("p-") else { return nil }
        return String(key.dropFirst(2))
    }
    static func summaryDay(of key: String?) -> String? {
        guard let key, key.hasPrefix("s-") else { return nil }
        return String(key.dropFirst(2))
    }

    private mutating func rebuild() {
        pages = ReviewPagesBuilder.build(ledger, removed: removed, current: Self.photoId(of: currentKey), activeSummaryDay: activeSummaryDay)
    }

    /// 左右翻页落定（或点「下一天」）。
    mutating func settle(on key: String) {
        guard key != currentKey else { return }
        if let prev = Self.photoId(of: currentKey), !removed.contains(prev) { ledger.markViewed(prev) }
        let day = Self.summaryDay(of: key)
        if let day { ledger.markSummarized(day) }
        currentKey = key
        activeSummaryDay = day
        rebuild()
    }

    /// 上下滑处理当前照片。返回切到的页 key（下一页，没有则上一页；都没有 → nil，会话结束）。
    @discardableResult
    mutating func act(_ id: String, _ outcome: ReviewOutcome) -> String? {
        guard !removed.contains(id), let i = pages.firstIndex(of: .photo(id)) else { return currentKey }
        let next = i + 1 < pages.count ? pages[i + 1] : (i > 0 ? pages[i - 1] : nil)
        ledger.record(id, outcome)
        var summaryDay: String? = nil
        if case .summary(let d)? = next { summaryDay = d; ledger.markSummarized(d) }
        removed.insert(id)
        currentKey = next?.key
        activeSummaryDay = summaryDay
        rebuild()
        return currentKey
    }

    /// 撤回：照片插回原位（序列按初始顺序生成）并切回它；若当前正停在它那天的小结上，小结一起撤掉。
    @discardableResult
    mutating func undo(_ id: String) -> String? {
        guard removed.contains(id), let p = ledger.photo(id) else { return nil }
        ledger.undoOutcome(id)
        if activeSummaryDay == p.day { ledger.summarized.remove(p.day) }
        removed.remove(id)
        currentKey = LedgerPage.photo(id).key
        activeSummaryDay = nil
        rebuild()
        return currentKey
    }

    /// 把当前照片记为看过（翻走之前；与 Android 在 settle 时补记一致，这里给 UI 预先标记用）。
    mutating func markViewed(_ id: String) { ledger.markViewed(id); rebuild() }
}

/// 小结统计（BuildDaySummaryUseCase 的会话部分）。
struct DayTally: Hashable {
    var favorited = 0, unfavorited = 0, trashed = 0, kept = 0
    var freedBytes: Int64 = 0
    var previewIds: [String] = []
    var previewOutcomes: [ReviewOutcome?] = []

    static func of(_ ledger: ReviewLedger, day: String, previewCount: Int = 8) -> DayTally {
        var t = DayTally()
        let photos = ledger.photosOfDay(day)
        for p in photos {
            switch ledger.outcomes[p.id] {
            case .favorited: t.favorited += 1
            case .unfavorited: t.unfavorited += 1
            case .trashed: t.trashed += 1; t.freedBytes += p.size
            case nil: t.kept += 1
            }
        }
        t.previewIds = photos.prefix(previewCount).map(\.id)
        t.previewOutcomes = photos.prefix(previewCount).map { ledger.outcomes[$0.id] }
        return t
    }
}

enum MonthProgressBuilder {
    /// 决定 12：月份条「整理完的天」跨会话计算——这一天相册里的照片全部看过或处理过即 Done。
    /// - photosByDay: 相册全部照片（照片页可见 + 收藏 + 垃圾桶）按 yyyy-MM-dd 分组的 id
    /// - 晚于今天的日子是 Empty；highlightDay 发光。
    static func build(monthOf highlightDay: String, photosByDay: [String: [String]], processed: Set<String>,
                      seen: Set<String>, today: String, calendar: Calendar = .current) -> (days: [MonthDayState], done: Int, withPhotos: Int) {
        let parts = highlightDay.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3,
              let first = calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: 1)),
              let range = calendar.range(of: .day, in: .month, for: first) else { return ([], 0, 0) }
        var states: [MonthDayState] = []
        var done = 0, withPhotos = 0
        for d in range {
            let key = String(format: "%04d-%02d-%02d", parts[0], parts[1], d)
            let ids = photosByDay[key] ?? []
            let future = key > today
            let isDone = !ids.isEmpty && ids.allSatisfy { seen.contains($0) || processed.contains($0) }
            if !ids.isEmpty && !future { withPhotos += 1; if isDone { done += 1 } }
            if key == highlightDay { states.append(.highlight) }
            else if ids.isEmpty || future { states.append(.empty) }
            else if isDone { states.append(.done) }
            else { states.append(.pending) }
        }
        return (states, done, withPhotos)
    }
}
