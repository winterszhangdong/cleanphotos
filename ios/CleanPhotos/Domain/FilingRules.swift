import Foundation

// 收藏整理的纯规则（无 PhotoKit）。对应 Android AlbumRules / TrayRules / BuildFiledDaysUseCase。
// iOS 底片袋是相册标题，不是 DCIM/Pictures 路径。

struct FilingAlbumRef: Hashable {
    var id: String
    var title: String
    var count: Int
    var lastFiledAt: TimeInterval?
}

struct FilingLogRow: Codable, Hashable {
    var assetId: String
    var albumTitle: String
    var dayKey: String
    var filedAt: TimeInterval
}

struct FavoriteDayRef: Hashable {
    var dayKey: String
    var count: Int
    var coverId: String
}

enum AlbumNameRules {
    static let maxLength = 64

    /// 与 Android AlbumRules.FORBIDDEN 相同，再加换行。相册标题本身可以更宽；新建时收紧，避免和同名判断打架。
    private static let forbidden = CharacterSet(charactersIn: "/\\:*?\"<>|\n")

    /// 去首尾空白、NFKC、POSIX 小写。同名判断和搜索都用这个。
    static func normalize(_ raw: String) -> String {
        raw.trimmingCharacters(in: .whitespacesAndNewlines)
            .precomposedStringWithCompatibilityMapping
            .lowercased(with: Locale(identifier: "en_US_POSIX"))
    }

    static func sanitize(_ raw: String) -> String {
        let kept = raw.unicodeScalars.filter { !forbidden.contains($0) }
        return String(String.UnicodeScalarView(kept).prefix(maxLength))
    }

    static func isValid(_ name: String) -> Bool {
        let t = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty, t != ".", t != "..", !t.hasPrefix(".") else { return false }
        guard t.count <= maxLength else { return false }
        return t.unicodeScalars.allSatisfy { !forbidden.contains($0) }
    }

    /// 已有相册标题匹配（忽略大小写和首尾空白）时复用那一只，不另建。
    /// 多个同名时取 `albums` 里的第一只（调用方按托盘顺序传：最近装过的在前）。
    static func existing(name: String, albums: [FilingAlbumRef]) -> FilingAlbumRef? {
        let wanted = normalize(name)
        guard !wanted.isEmpty else { return nil }
        return albums.first { normalize($0.title) == wanted }
    }
}

enum TrayRules {
    static let limit = 8

    static func moreCount(total: Int) -> Int { max(0, total - limit) }

    /// 最后装进去的在前；没装过的按张数从多到少，再按标题、id。
    static func sorted(_ albums: [FilingAlbumRef]) -> [FilingAlbumRef] {
        albums.sorted { a, b in
            let af = a.lastFiledAt ?? -.greatestFiniteMagnitude
            let bf = b.lastFiledAt ?? -.greatestFiniteMagnitude
            if af != bf { return af > bf }
            if a.count != b.count { return a.count > b.count }
            let at = AlbumNameRules.normalize(a.title)
            let bt = AlbumNameRules.normalize(b.title)
            if at != bt { return at < bt }
            return a.id < b.id
        }
    }

    /// 本次进入的托盘顺序。`session` 是进入时定下的 id；`promoted` 是从「还有 N 个」装进去、插到最前的 id（最近的在前）。
    /// 不在 session 里、也不在 promoted 里的新相册排在最前（保持 `albums` 的顺序）。
    static func arrange(albums: [FilingAlbumRef], session: [String], promoted: [String]) -> [FilingAlbumRef] {
        let byId = Dictionary(uniqueKeysWithValues: albums.map { ($0.id, $0) })
        let known = Set(session)
        let promotedSet = Set(promoted)
        let fresh = albums.filter { !known.contains($0.id) && !promotedSet.contains($0.id) }
        let head = promoted.compactMap { byId[$0] }
        let rest = session.filter { !promotedSet.contains($0) }.compactMap { byId[$0] }
        return fresh + head + rest
    }

    static func visible(_ arranged: [FilingAlbumRef]) -> [FilingAlbumRef] {
        Array(arranged.prefix(limit))
    }

    /// 已经露在前 [limit] 只里的不动；否则插到 promoted 的第一格。
    static func promote(tray: [FilingAlbumRef], promoted: [String], id: String) -> [String] {
        if tray.prefix(limit).contains(where: { $0.id == id }) { return promoted }
        return [id] + promoted.filter { $0 != id }
    }

    /// 第一次拿到非空列表时定下顺序；之后新出现的 id 插到最前。`current == nil` 表示还没定过。
    static func sessionOrder(current: [String]?, albums: [FilingAlbumRef]) -> [String] {
        let ids = albums.map(\.id)
        guard let current else { return ids }
        let known = Set(current)
        let fresh = ids.filter { !known.contains($0) }
        return fresh.isEmpty ? current : fresh + current
    }
}

enum AlbumSearch {
    static func filter(_ albums: [FilingAlbumRef], query: String) -> [FilingAlbumRef] {
        let q = AlbumNameRules.normalize(query)
        if q.isEmpty { return albums }
        return albums.filter { AlbumNameRules.normalize($0.title).contains(q) }
    }

    /// 搜索框里「新建」那一行：名字合法、且没有同名相册时才有。不做拼音。
    static func createName(_ albums: [FilingAlbumRef], query: String) -> String? {
        let name = AlbumNameRules.sanitize(query).trimmingCharacters(in: .whitespacesAndNewlines)
        guard AlbumNameRules.isValid(name) else { return nil }
        return AlbumNameRules.existing(name: name, albums: albums) == nil ? name : nil
    }
}

enum LastFiledKind: Equatable {
    case today
    case daysAgo(Int)
    case on(Date)
}

enum LastFiledRules {
    /// 今天 → today；1–6 天前 → daysAgo；更早 → 那一天的起点。没装过是 nil。
    static func kind(filedAt: TimeInterval?, now: TimeInterval, calendar: Calendar) -> LastFiledKind? {
        guard let filedAt else { return nil }
        let filedDay = calendar.startOfDay(for: Date(timeIntervalSince1970: filedAt))
        let today = calendar.startOfDay(for: Date(timeIntervalSince1970: now))
        let days = calendar.dateComponents([.day], from: filedDay, to: today).day ?? 0
        if days <= 0 { return .today }
        if days < 7 { return .daysAgo(days) }
        return .on(filedDay)
    }
}

enum NextDayRule {
    /// `yyyy-MM-dd` → yyyymmdd。未知日期或解析不了是 nil。
    static func ordinal(_ dayKey: String) -> Int? {
        guard dayKey != DayKeyRule.unknown else { return nil }
        let parts = dayKey.split(separator: "-")
        guard parts.count == 3,
              let y = Int(parts[0]), let m = Int(parts[1]), let d = Int(parts[2]),
              m >= 1, m <= 12, d >= 1, d <= 31 else { return nil }
        return y * 10_000 + m * 100 + d
    }

    static func date(from dayKey: String, calendar: Calendar = .current) -> Date? {
        guard let ordinal = ordinal(dayKey) else { return nil }
        var c = DateComponents()
        c.year = ordinal / 10_000
        c.month = (ordinal / 100) % 100
        c.day = ordinal % 100
        return calendar.date(from: c)
    }

    /// 收藏页顺序（新 → 旧）里这一天之后更早的一天；没有更早的就回到最新的一天。没有别的天是 nil。
    static func next(after dayKey: String, daysNewestFirst: [FavoriteDayRef]) -> FavoriteDayRef? {
        let others = daysNewestFirst.filter { $0.dayKey != dayKey }
        guard let first = others.first else { return nil }
        guard let here = ordinal(dayKey) else { return first }
        if let older = others.first(where: { (ordinal($0.dayKey) ?? Int.min) < here }) {
            return older
        }
        return first
    }
}

enum FilingPartition {
    /// 已经在目标相册里的算装好，不再加一次。顺序跟 `selected` 走。
    static func split(selected: [String], members: Set<String>) -> (already: [String], toAdd: [String]) {
        var already: [String] = []
        var toAdd: [String] = []
        already.reserveCapacity(selected.count)
        for id in selected {
            if members.contains(id) { already.append(id) } else { toAdd.append(id) }
        }
        return (already, toAdd)
    }
}

enum FilingLogRules {
    static let maxEntries = 1000

    /// 同一 asset 只留这次写入的新行。按 filedAt 倒序，超过上限丢最旧的。
    static func upsert(_ existing: [FilingLogRow], adding: [FilingLogRow]) -> [FilingLogRow] {
        guard !adding.isEmpty else { return existing }
        let ids = Set(adding.map(\.assetId))
        let kept = existing.filter { !ids.contains($0.assetId) }
        return Array((kept + adding).sorted { $0.filedAt > $1.filedAt }.prefix(maxEntries))
    }

    static func remove(_ existing: [FilingLogRow], ids: Set<String>) -> [FilingLogRow] {
        guard !ids.isEmpty else { return existing }
        return existing.filter { !ids.contains($0.assetId) }
    }
}

enum FilingPrune {
    /// 图库里查不到的 id 可以丢掉。`found == nil`（查询失败）不清理。
    /// 库不是空的、却一个都没查到：多半是查询异常，不清理。
    static func idsToDrop(stored: [String], found: Set<String>?, libraryCount: Int) -> [String]? {
        guard let found else { return nil }
        guard !stored.isEmpty else { return [] }
        if libraryCount > 0 && found.isEmpty { return nil }
        return stored.filter { !found.contains($0) }
    }
}

enum FiledDayRules {
    struct Chip: Hashable {
        var name: String
        var count: Int
    }

    struct Day: Hashable {
        var dayKey: String
        var albums: [Chip]
        var assetIds: [String]
        var lastFiledAt: TimeInterval
        var count: Int { assetIds.count }
    }

    /// 只留还在图库里的照片。同一天按袋子汇总；最近装过的天在前。
    static func build(_ rows: [FilingLogRow], present: Set<String>) -> [Day] {
        let live = rows.filter { present.contains($0.assetId) }
        let grouped = Dictionary(grouping: live, by: \.dayKey)
        let days = grouped.map { key, records -> Day in
            let byName = Dictionary(grouping: records, by: \.albumTitle)
            let chips = byName.map { Chip(name: $0.key, count: $0.value.count) }
                .sorted { a, b in
                    if a.count != b.count { return a.count > b.count }
                    return a.name < b.name
                }
            return Day(
                dayKey: key,
                albums: chips,
                assetIds: records.map(\.assetId),
                lastFiledAt: records.map(\.filedAt).max() ?? 0
            )
        }
        return days.sorted { $0.lastFiledAt > $1.lastFiledAt }
    }
}
