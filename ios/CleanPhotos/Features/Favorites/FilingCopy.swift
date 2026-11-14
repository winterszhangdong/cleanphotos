import Foundation

/// 整理 / 印样表的新文案。不进 Localizable.xcstrings，也不走 L10n.plural 的新 key。
enum FilingCopy {
    static func chinese(_ locale: Locale) -> Bool {
        locale.identifier.lowercased().hasPrefix("zh")
    }

    static func favoriteUnit(count: Int, locale: Locale) -> String {
        if chinese(locale) { return "张" }
        return count == 1 ? "favorite" : "favorites"
    }

    static func daysPending(_ n: Int, locale: Locale) -> String {
        if chinese(locale) { return "\(n) 天待整理" }
        return n == 1 ? "\(n) day to sort" : "\(n) days to sort"
    }

    static func sortDay(_ locale: Locale) -> String { chinese(locale) ? "整理" : "Sort" }

    static func filedHeader(_ n: Int, locale: Locale) -> String {
        chinese(locale) ? "已冲洗 · \(n) 张" : "Filed · \(n)"
    }

    static func sheetHint(_ locale: Locale) -> String {
        chinese(locale) ? "点照片圈选 · 手指划过连圈 · 长按看大图" : "Tap to circle · Swipe across for more · Hold to view"
    }

    static func circleAll(_ locale: Locale) -> String { chinese(locale) ? "全圈" : "Circle all" }
    static func clear(_ locale: Locale) -> String { chinese(locale) ? "清除" : "Clear" }

    static func pending(_ n: Int, locale: Locale) -> String {
        if chinese(locale) { return "\(n) 张待整理" }
        return "\(n) to sort"
    }

    static func circled(_ n: Int, locale: Locale) -> String {
        chinese(locale) ? "已圈 \(n) 张" : "\(n) circled"
    }

    static func trayTitle(_ locale: Locale) -> String { chinese(locale) ? "放进相册" : "Put in album" }
    static func systemFavorite(_ locale: Locale) -> String { chinese(locale) ? "加系统收藏" : "Gallery favorite" }
    static func newSleeve(_ locale: Locale) -> String { chinese(locale) ? "新袋子" : "New sleeve" }

    static func more(_ n: Int, locale: Locale) -> String {
        chinese(locale) ? "还有 \(n) 个" : (n == 1 ? "\(n) more" : "\(n) more")
    }

    static func searchHint(_ locale: Locale) -> String { chinese(locale) ? "搜索相册" : "Search albums" }
    static func searchClear(_ locale: Locale) -> String { chinese(locale) ? "清空" : "Clear" }
    static func parkedHint(_ locale: Locale) -> String {
        chinese(locale) ? "点一个相册放进去" : "Tap an album to put them in"
    }

    static func createRow(_ name: String, locale: Locale) -> String {
        chinese(locale) ? "新建「\(name)」" : "New “\(name)”"
    }

    static func newAlbumTitle(_ n: Int, locale: Locale) -> String {
        if chinese(locale) { return "新相册 · 放进 \(n) 张" }
        return n == 1 ? "New album · \(n) photo" : "New album · \(n) photos"
    }

    static func albumPlaceholder(_ locale: Locale) -> String { chinese(locale) ? "相册名" : "Album name" }
    static func mergeHint(_ locale: Locale) -> String { chinese(locale) ? "已有，合并进去" : "Already exists, will merge" }
    static func pathNew(_ name: String, locale: Locale) -> String {
        chinese(locale) ? "\(name) · 新建" : "\(name) · new"
    }

    static func putIn(_ locale: Locale) -> String { chinese(locale) ? "放进" : "Put in" }

    static func summaryTitle(_ locale: Locale) -> String { chinese(locale) ? "这一天冲洗完了" : "This day is filed" }
    static func summaryTotal(_ n: Int, locale: Locale) -> String {
        chinese(locale) ? " · 共 \(n)" : " · \(n) total"
    }
    static func summaryNew(_ locale: Locale) -> String { chinese(locale) ? " · 新建" : " · new" }
    static func summaryUnfavoriteRest(_ locale: Locale) -> String {
        chinese(locale) ? " · 回到照片" : " · back in Photos"
    }

    static func nextDay(_ label: String, locale: Locale) -> String {
        chinese(locale) ? "整理 \(label)" : "Sort \(label)"
    }

    static func backToFavorites(_ locale: Locale) -> String {
        chinese(locale) ? "回到收藏" : "Back to Favorites"
    }

    static func undoFiled(_ n: Int, name: String, locale: Locale) -> String {
        if chinese(locale) { return "已放进「\(name)」\(n) 张" }
        return n == 1 ? "Put \(n) photo in “\(name)”" : "Put \(n) photos in “\(name)”"
    }

    static func filingFailed(_ n: Int, locale: Locale) -> String {
        if chinese(locale) { return "\(n) 张没能放进去" }
        return n == 1 ? "\(n) photo couldn’t be moved" : "\(n) photos couldn’t be moved"
    }

    static func undoFailed(_ n: Int, locale: Locale) -> String {
        if chinese(locale) { return "\(n) 张没能移回原处" }
        return n == 1 ? "\(n) photo couldn’t be moved back" : "\(n) photos couldn’t be moved back"
    }

    static func timeToday(_ locale: Locale) -> String { chinese(locale) ? "今天" : "Today" }
    static func timeDaysAgo(_ n: Int, locale: Locale) -> String {
        if chinese(locale) { return "\(n) 天前" }
        return n == 1 ? "\(n) day ago" : "\(n) days ago"
    }

    static func albumCount(_ n: Int, locale: Locale) -> String {
        if chinese(locale) { return "\(n) 张" }
        return n == 1 ? "\(n) photo" : "\(n) photos"
    }

    static func emptyDay(_ locale: Locale) -> String {
        chinese(locale) ? "这一天没有待整理的照片" : "Nothing left to sort this day"
    }

    static func chip(_ name: String, count: Int) -> String { "\(name) +\(count)" }
}
