import Foundation

/// 相似页文案。不进 Localizable.xcstrings，也不走 L10n.plural。
enum SimilarCopy {
    static func comparing(done: Int, total: Int, zh: Bool) -> String {
        zh ? "正在比对 \(done) / \(total)" : "Comparing \(done) / \(total)"
    }

    static func groupUnit(count: Int, zh: Bool) -> String {
        if zh { return "组" }
        return count == 1 ? "group" : "groups"
    }

    /// 顶栏副文字，和安卓 `header_sub_can_move` 一样带前导间隔点。
    static func canMove(_ count: Int, zh: Bool) -> String {
        if zh { return "· 可移走 \(count) 张" }
        return count == 1 ? "· \(count) photo can be removed" : "· \(count) photos can be removed"
    }

    static func empty(zh: Bool) -> String {
        zh ? "没有找到相似照片" : "No similar photos"
    }

    static func commit(removeCount: Int, zh: Bool) -> String {
        if removeCount <= 0 { return zh ? "都留下" : "Keep all" }
        return zh ? "移走 \(removeCount) 张" : "Move \(removeCount) to trash"
    }

    static func position(index: Int, count: Int) -> String {
        "\(index) / \(count)"
    }

    static func recommended(zh: Bool) -> String {
        zh ? "推荐 · 最清晰" : "Recommended · sharpest"
    }

    static func captionRemove(zh: Bool) -> String {
        zh ? "移走" : "remove"
    }

    static func kept(zh: Bool) -> String {
        zh ? "已留下" : "Kept"
    }

    static func actionKeep(zh: Bool) -> String {
        zh ? "留下" : "Keep"
    }

    static func mark(_ mark: SimilarMark, zh: Bool) -> String {
        switch mark {
        case .keep: return zh ? "留下" : "keep"
        case .remove: return zh ? "移走" : "remove"
        }
    }

    static func film(date: String, mark: String, zh: Bool) -> String {
        zh ? "\(date)，\(mark)" : "\(date), \(mark)"
    }

    static func cue(up: Bool, past: Bool, zh: Bool) -> String {
        if zh {
            if up { return past ? "松手留下" : "继续上滑留下" }
            return past ? "松手移到垃圾桶" : "继续下滑移到垃圾桶"
        }
        if up { return past ? "Release to keep" : "Keep swiping up to keep" }
        return past ? "Release to move to trash" : "Keep swiping down to trash"
    }
}
