import Foundation

/**
 * 日期滑块气泡的一行日期。中文「2024年8月3日」，英文「Aug 3, 2024」。
 * 月、日不补零；界面给它们各留两位，个位数靠右。英文月份另留十二个短名里最宽的那个。
 * 只有数字变，气泡宽度才不会跟着变。繁体同样用「年月日」，不换成别的单位。
 */
enum ScrubDateFormat {
    struct Label: Equatable {
        var year: String
        var month: String
        var day: String
        var chinese: Bool
    }

    static func isChinese(_ locale: Locale) -> Bool {
        locale.language.languageCode == .chinese
    }

    static func label(year: Int, month: Int, day: Int, locale: Locale) -> Label {
        let chinese = isChinese(locale)
        return Label(
            year: String(year),
            month: chinese ? String(month) : shortMonth(month, locale: locale),
            day: String(day),
            chinese: chinese
        )
    }

    /// 读屏用的整句，不含占位空格。
    static func phrase(_ label: Label) -> String {
        if label.chinese { return "\(label.year)年\(label.month)月\(label.day)日" }
        return "\(label.month) \(label.day), \(label.year)"
    }

    static func shortMonths(locale: Locale) -> [String] {
        let key = locale.identifier
        lock.lock()
        if let cached = monthNames[key] {
            lock.unlock()
            return cached
        }
        lock.unlock()
        let names = (1...12).map { shortMonth($0, locale: locale) }
        lock.lock()
        monthNames[key] = names
        lock.unlock()
        return names
    }

    private static let lock = NSLock()
    private static var monthNames: [String: [String]] = [:]
    private static var formatters: [String: DateFormatter] = [:]

    private static func shortMonth(_ month: Int, locale: Locale) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.locale = locale
        let date = calendar.date(from: DateComponents(year: 2000, month: month, day: 1))!
        return monthFormatter(locale).string(from: date)
    }

    private static func monthFormatter(_ locale: Locale) -> DateFormatter {
        let key = locale.identifier
        lock.lock()
        defer { lock.unlock() }
        if let cached = formatters[key] { return cached }
        var calendar = Calendar(identifier: .gregorian)
        calendar.locale = locale
        let created = DateFormatter()
        created.locale = locale
        created.calendar = calendar
        created.dateFormat = "MMM"
        formatters[key] = created
        return created
    }
}

enum PhotosGridHeader: Equatable {
    case day, month, none
}

/// 离开照片页时用来对滚动锚点的一天。照片 id 用字符串，好单测，也不碰 PhotoKit。
struct PhotosGridSection: Equatable {
    var dayKey: String
    var yearHeader: Int?
    var photoIds: [String]
    var overflowIds: [String]
    var header: PhotosGridHeader
}

enum PhotosGridKey: Equatable {
    case year(String)
    case header(String)
    case photo(String)
    case more(String)
}

/**
 * 离开照片页之后，沉浸和展开过的那天该滚到哪。
 * 下标和安卓 `DayExpand` 的网格项同一套：年份、日期行、照片、没展开时的「还有 N 张」。
 * 本来就是日期分组、也没有展开时返回 nil，调用方别滚回顶。锚点不在也返回 nil。
 */
enum PhotosLeave {
    struct Metrics: Equatable {
        var chrome: CGFloat
        var year: CGFloat
        var header: CGFloat
        var more: CGFloat
        var tilePitch: CGFloat
        var columns: Int
    }

    static func yearKey(year: Int, dayKey: String) -> String { "year-\(year)-\(dayKey)" }
    static func moreKey(dayKey: String) -> String { "more-\(dayKey)" }

    static func displayedIds(_ section: PhotosGridSection, expanded: Bool) -> [String] {
        if expanded && !section.overflowIds.isEmpty {
            return section.photoIds + section.overflowIds
        }
        return section.photoIds
    }

    static func gridKeys(_ sections: [PhotosGridSection], expandedDayKey: String?) -> [PhotosGridKey] {
        var keys: [PhotosGridKey] = []
        for section in sections {
            if let year = section.yearHeader {
                keys.append(.year(yearKey(year: year, dayKey: section.dayKey)))
            }
            if section.header != .none {
                keys.append(.header(section.dayKey))
            }
            let expanded = section.dayKey == expandedDayKey && !section.overflowIds.isEmpty
            for id in displayedIds(section, expanded: expanded) {
                keys.append(.photo(id))
            }
            if !expanded && !section.overflowIds.isEmpty && section.header == .day {
                keys.append(.more(moreKey(dayKey: section.dayKey)))
            }
        }
        return keys
    }

    /// 沉浸网格只有照片。下标按露出来的照片往下数，不含被 8 张挡住的溢出。
    static func photoIdOnImmersiveGrid(_ sections: [PhotosGridSection], index: Int) -> String? {
        var cursor = 0
        for section in sections {
            for id in section.photoIds {
                if cursor == index { return id }
                cursor += 1
            }
        }
        return nil
    }

    /// 收起后的日期分组里这个照片的下标。只在溢出里的，落到这一天的日期行。找不到返回 nil。
    static func indexInCollapsedGrid(_ sections: [PhotosGridSection], photoId: String) -> Int? {
        let keys = gridKeys(sections, expandedDayKey: nil)
        if let direct = keys.firstIndex(of: .photo(photoId)) { return direct }
        guard let section = sections.first(where: { $0.overflowIds.contains(photoId) }) else { return nil }
        if section.header == .none { return nil }
        return keys.firstIndex(of: .header(section.dayKey))
    }

    /**
     * 离开之后网格该停的下标。
     * 沉浸按照片对回日期分组。展开的那天收起后，视口已经滑进这一天就回到日期行。
     * 本来就是日期分组、也没有展开时返回 nil。锚点不在返回 nil，不回落到 0。
     */
    static func indexAfterLeave(
        sections: [PhotosGridSection],
        immersive: Bool,
        expandedDayKey: String?,
        firstIndex: Int
    ) -> Int? {
        if immersive {
            guard let id = photoIdOnImmersiveGrid(sections, index: firstIndex) else { return nil }
            return indexInCollapsedGrid(sections, photoId: id)
        }
        guard let expandedDayKey else { return nil }
        return scrollAfterCollapse(sections, expandedDayKey: expandedDayKey, firstIndex: firstIndex)
    }

    /// 视口内容坐标落在哪一行。`contentLine` 是滚过的偏移加上当时露出来的顶栏下沿。
    static func firstVisibleIndex(
        contentLine: CGFloat,
        sections: [PhotosGridSection],
        expandedDayKey: String?,
        metrics: Metrics
    ) -> Int {
        var found: Int?
        var last = 0
        var sawRow = false
        walk(sections, expandedDayKey: expandedDayKey, metrics: metrics) { index, top, height, _ in
            sawRow = true
            last = index
            if top + height > contentLine {
                found = index
                return true
            }
            return false
        }
        if let found { return found }
        return sawRow ? last : 0
    }

    /// 沉浸没有日期行。第 0 行从顶栏占位下面开始，一行 `columns` 张。
    static func firstVisibleImmersiveIndex(contentLine: CGFloat, photoCount: Int, metrics: Metrics) -> Int {
        guard photoCount > 0 else { return 0 }
        let into = contentLine - metrics.chrome
        let pitch = metrics.tilePitch
        let row: Int
        if into <= 0 || pitch <= 0 {
            row = 0
        } else {
            row = Int(into / pitch)
        }
        return min(row * max(metrics.columns, 1), photoCount - 1)
    }

    /// 这一项在滚动内容里的顶边（含顶栏占位）。同一行的照片共用一个顶边。越界返回 nil。
    static func contentTop(
        index: Int,
        sections: [PhotosGridSection],
        expandedDayKey: String?,
        metrics: Metrics
    ) -> CGFloat? {
        guard index >= 0 else { return nil }
        var top: CGFloat?
        walk(sections, expandedDayKey: expandedDayKey, metrics: metrics) { rowIndex, rowTop, _, count in
            if index >= rowIndex && index < rowIndex + count {
                top = rowTop
                return true
            }
            return false
        }
        return top
    }

    /// 纯收起，而且视口已经滑过这一天的日期行时，回到这一行。还停在日期行上、或锚点对不上时返回 nil。
    private static func scrollAfterCollapse(
        _ sections: [PhotosGridSection],
        expandedDayKey: String,
        firstIndex: Int
    ) -> Int? {
        let oldKeys = gridKeys(sections, expandedDayKey: expandedDayKey)
        let newKeys = gridKeys(sections, expandedDayKey: nil)
        guard firstIndex >= 0, firstIndex < oldKeys.count else { return nil }
        guard let header = newKeys.firstIndex(of: .header(expandedDayKey)) else { return nil }
        if firstIndex > header { return header }
        return nil
    }

    private static func walk(
        _ sections: [PhotosGridSection],
        expandedDayKey: String?,
        metrics: Metrics,
        _ body: (_ index: Int, _ top: CGFloat, _ height: CGFloat, _ count: Int) -> Bool
    ) {
        var y = metrics.chrome
        var index = 0
        let columns = max(metrics.columns, 1)
        func emit(_ height: CGFloat, _ count: Int) -> Bool {
            let stop = body(index, y, height, count)
            y += height
            index += count
            return stop
        }
        for section in sections {
            if section.yearHeader != nil {
                if emit(metrics.year, 1) { return }
            }
            if section.header != .none {
                if emit(metrics.header, 1) { return }
            }
            let expanded = section.dayKey == expandedDayKey && !section.overflowIds.isEmpty
            let photos = displayedIds(section, expanded: expanded)
            var cursor = 0
            while cursor < photos.count {
                let count = min(columns, photos.count - cursor)
                if emit(metrics.tilePitch, count) { return }
                cursor += count
            }
            if !expanded && !section.overflowIds.isEmpty && section.header == .day {
                if emit(metrics.more, 1) { return }
            }
        }
    }
}
