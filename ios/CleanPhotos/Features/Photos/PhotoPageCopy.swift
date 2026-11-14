import Foundation

/// 照片页这次新增的文案。已有的 key 继续走 L10n，不往 Localizable.xcstrings 加词条。
enum PhotoPageCopy {
    static func scopeName(_ scope: LibraryScope, chinese: Bool) -> String {
        switch scope {
        case .all: return chinese ? "全部" : "All"
        case .similar: return chinese ? "相似" : "Similar"
        case .largeFiles: return chinese ? "大文件" : "Large files"
        case .screenshots: return chinese ? "截图" : "Screenshots"
        case .chat: return chinese ? "聊天" : "Chat"
        case .onThisDay: return chinese ? "那年今天" : "On this day"
        }
    }

    static func emptyTitle(_ scope: LibraryScope, chinese: Bool) -> String {
        switch scope {
        case .screenshots: return chinese ? "没有截图" : "No screenshots"
        case .largeFiles: return chinese ? "没有大文件" : "No large files"
        case .chat: return chinese ? "没有聊天软件存图" : "No chat app images"
        case .onThisDay: return chinese ? "那年今天没有照片" : "No photos from this day"
        case .all, .similar: return ""
        }
    }

    static func unit(count: Int, chinese: Bool) -> String {
        if chinese { return "" }
        return count == 1 ? "photo" : "photos"
    }

    static func subBytes(_ size: String) -> String { "· \(size)" }

    static func subSelected(chinese: Bool) -> String {
        chinese ? "· 已选择" : "· Selected"
    }

    static func menuView(chinese: Bool) -> String { chinese ? "视图" : "View" }
    static func viewByDate(chinese: Bool) -> String { chinese ? "日期分组" : "By date" }
    static func viewImmersive(chinese: Bool) -> String { chinese ? "沉浸" : "Immersive" }
    static func unseenOnly(chinese: Bool) -> String { chinese ? "只看没看过" : "Unseen only" }
    static func scopeSeenTitle(chinese: Bool) -> String { chinese ? "这里都看过了" : "Everything here is seen" }
    static func showSeen(chinese: Bool) -> String { chinese ? "显示看过的" : "Show seen" }
    static func sectionAllSeen(chinese: Bool) -> String { chinese ? "已看完" : "All seen" }
    static func sectionSlash(_ total: Int) -> String { " / \(total)" }
    static func dayMore(_ hidden: Int, chinese: Bool) -> String {
        chinese ? "还有 \(hidden) 张" : "\(hidden) more"
    }
    static func dayCollapse(chinese: Bool) -> String { chinese ? "收起" : "Collapse" }
    static func monthUnit(chinese: Bool) -> String { chinese ? "月" : "" }
}
