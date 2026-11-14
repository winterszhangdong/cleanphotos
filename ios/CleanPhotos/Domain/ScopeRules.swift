import Foundation

/// 照片页范围。单选，不持久化。相似在这个构建里只占刻度上的一页。
enum LibraryScope: String, CaseIterable, Hashable {
    case all
    case similar
    case largeFiles
    case screenshots
    case chat
    case onThisDay

    var showsSortMenu: Bool { self != .largeFiles }

    /// 顶栏副文字是可见照片的大小合计。
    var showsByteTotal: Bool { self == .largeFiles || self == .screenshots || self == .chat }

    var isNarrow: Bool { self != .all && self != .similar }

    /// 网格怎么分组。相似不画这张网格。
    var groupsByDay: Bool { self == .all || self == .onThisDay || self == .similar }
    var groupsByMonth: Bool { self == .screenshots || self == .chat }
    var isFlat: Bool { self == .largeFiles }
}

/// 照片页中间画什么。判断顺序与 Android `libraryContent` 一致（这个构建没有单独的错误页）。
enum PhotosBody: Equatable {
    case loading
    case similar
    case allSeen
    case scopeEmpty
    case scopeSeen
    case libraryEmpty
    case grid
}

/**
 * 范围、RAW、时长、日期行吸附。只有 Foundation，方便单测。
 *
 * iOS 没有 MediaStore 的 relativePath：
 * 截图靠 PHAsset.mediaSubtypes.photoScreenshot、相册 localizedTitle，或原始文件名；
 * 聊天只看用户相册的 localizedTitle。文件大小不在首次读库时逐张算。
 */
enum ScopeRules {
    static let largeFileMinBytes: Int64 = 10 * 1024 * 1024
    static let dayVisibleCap = 8

    static let screenshotNeedles = ["screenshot", "截屏", "截图"]
    /// Android 的聊天路径是 weixin / whatsapp / telegram，以及 Pictures/QQ/。
    /// iOS 相册标题里微信也可能写成 WeChat，所以多一个 wechat。
    static let chatNeedles = ["weixin", "wechat", "whatsapp", "telegram", "qq"]

    /// 与 Android RawFile.kt 同一份扩展名。
    static let rawExtensions: Set<String> = [
        "dng", "cr2", "cr3", "nef", "nrw", "arw", "srf", "sr2",
        "orf", "raf", "pef", "rw2", "srw", "gpr",
    ]

    static func visibleScopes(onThisDayCount: Int, current: LibraryScope) -> [LibraryScope] {
        var list: [LibraryScope] = [.all, .similar, .largeFiles, .screenshots, .chat]
        if onThisDayCount > 0 || current == .onThisDay {
            list.append(.onThisDay)
        }
        return list
    }

    static func photosBody(
        scope: LibraryScope,
        isLoading: Bool,
        pageReady: Bool,
        unseenOnly: Bool,
        visibleCount: Int,
        scopeCount: Int,
        totalCount: Int
    ) -> PhotosBody {
        if scope == .similar { return .similar }
        if (isLoading && visibleCount == 0 && scopeCount == 0) || !pageReady { return .loading }
        if scope == .all && unseenOnly && visibleCount == 0 && totalCount > 0 { return .allSeen }
        if scope.isNarrow && scopeCount == 0 && totalCount > 0 { return .scopeEmpty }
        if scope.isNarrow && unseenOnly && visibleCount == 0 && scopeCount > 0 { return .scopeSeen }
        if visibleCount == 0 { return .libraryEmpty }
        return .grid
    }

    /// size 为 nil 不算大文件。
    static func isLargeFile(bytes: Int64?) -> Bool {
        guard let bytes else { return false }
        return bytes >= largeFileMinBytes
    }

    static func isScreenshot(systemScreenshot: Bool, albumTitle: String?, filename: String?) -> Bool {
        if systemScreenshot { return true }
        if containsAny(albumTitle, screenshotNeedles) { return true }
        if containsAny(filename, screenshotNeedles) { return true }
        return false
    }

    static func isChatAlbumTitle(_ title: String?) -> Bool {
        containsAny(title, chatNeedles)
    }

    static func filenameLooksLikeScreenshot(_ filename: String?) -> Bool {
        containsAny(filename, screenshotNeedles)
    }

    /**
     * 本地月日等于今天，且年份更早。2 月 29 日只有今天也是 2 月 29 日才相等。
     * 时间戳 <= 0（含缺失拍摄时间）不算。
     */
    static func isOnThisDay(date: Date, today: Date, calendar: Calendar = .current) -> Bool {
        if date.timeIntervalSince1970 <= 0 { return false }
        let day = calendar.dateComponents([.year, .month, .day], from: date)
        let now = calendar.dateComponents([.year, .month, .day], from: today)
        guard let year = day.year, let month = day.month, let dayNumber = day.day,
              let todayYear = now.year, let todayMonth = now.month, let todayDay = now.day else { return false }
        return month == todayMonth && dayNumber == todayDay && year < todayYear
    }

    static func isRawFileName(_ displayName: String?) -> Bool {
        guard let ext = fileExtension(displayName) else { return false }
        return rawExtensions.contains(ext)
    }

    /// 最后一个扩展名，小写。没有点、或点在末尾，就不是。
    static func fileExtension(_ displayName: String?) -> String? {
        guard let displayName, !displayName.isEmpty else { return nil }
        guard let dot = displayName.lastIndex(of: ".") else { return nil }
        if displayName.index(after: dot) == displayName.endIndex { return nil }
        return displayName[displayName.index(after: dot)...].lowercased()
    }

    /// 左下角一行。空的不写，用「 · 」连起来。都空时返回 nil。
    /// motion 是「动态」。相似对比的当前大图会带上它；网格不写。
    static func photoMarkLine(raw: String?, motion: String? = nil, size: String?) -> String? {
        let parts = [raw, motion, size].compactMap { $0 }.filter { !$0.isEmpty }
        if parts.isEmpty { return nil }
        return parts.joined(separator: " · ")
    }

    /// 「m:ss」，满 1 小时为「h:mm:ss」。不足 1 秒舍去。负数直接出错。
    static func formatDuration(seconds: TimeInterval) -> String {
        precondition(seconds >= 0, "时长不能为负")
        let total = Int(seconds)
        let secs = total % 60
        let totalMinutes = total / 60
        let minutes = totalMinutes % 60
        let hours = totalMinutes / 60
        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, minutes, secs)
        }
        return String(format: "%d:%02d", minutes, secs)
    }

    private static func containsAny(_ value: String?, _ needles: [String]) -> Bool {
        guard let value else { return false }
        return needles.contains { value.range(of: $0, options: .caseInsensitive) != nil }
    }
}

/// 范围刻度的位置和透明度。进度以页为单位。颜色是相纸白乘 alpha。
enum ScopeDialMath {
    static let minAlpha: CGFloat = 0.22
    static let falloff: CGFloat = 0.55
    static let pageDim: CGFloat = 0.3
    /// 慢滑过视口大约三成就进下一页。
    static let snapFraction: CGFloat = 0.3

    static func alpha(distance: CGFloat) -> CGFloat {
        max(minAlpha, 1 - falloff * distance)
    }

    static func center(index: Int, widths: [CGFloat], gap: CGFloat) -> CGFloat {
        var x: CGFloat = 0
        if index > 0 {
            for i in 0..<index { x += widths[i] + gap }
        }
        let width = index >= 0 && index < widths.count ? widths[index] : 0
        return x + width / 2
    }

    static func centerAt(progress: CGFloat, widths: [CGFloat], gap: CGFloat) -> CGFloat {
        if widths.isEmpty { return 0 }
        let last = widths.count - 1
        if last == 0 { return widths[0] / 2 + progress * (widths[0] + gap) }
        let i0 = Int(floor(progress))
        let t = progress - CGFloat(i0)
        return at(i0, widths, gap, last) + (at(i0 + 1, widths, gap, last) - at(i0, widths, gap, last)) * t
    }

    private static func at(_ index: Int, _ widths: [CGFloat], _ gap: CGFloat, _ last: Int) -> CGFloat {
        if index < 0 {
            let step = center(index: 1, widths: widths, gap: gap) - center(index: 0, widths: widths, gap: gap)
            return center(index: 0, widths: widths, gap: gap) + CGFloat(index) * step
        }
        if index > last {
            let step = center(index: last, widths: widths, gap: gap) - center(index: last - 1, widths: widths, gap: gap)
            return center(index: last, widths: widths, gap: gap) + CGFloat(index - last) * step
        }
        return center(index: index, widths: widths, gap: gap)
    }

    static func translationX(viewportCenter: CGFloat, progress: CGFloat, widths: [CGFloat], gap: CGFloat) -> CGFloat {
        viewportCenter - centerAt(progress: progress, widths: widths, gap: gap)
    }

    static func pageStride(progress: CGFloat, widths: [CGFloat], gap: CGFloat) -> CGFloat {
        if widths.count <= 1 {
            let width = widths.first ?? 0
            return max(1, width + gap)
        }
        let i = min(max(0, Int(floor(progress))), widths.count - 2)
        return max(1, center(index: i + 1, widths: widths, gap: gap) - center(index: i, widths: widths, gap: gap))
    }

    /// 向左为负的 dx 得到正的页数。
    static func dragPages(dx: CGFloat, stride: CGFloat) -> CGFloat {
        -dx / max(1, stride)
    }

    static func hitWidth(textWidth: CGFloat, gap: CGFloat, minTouch: CGFloat) -> CGFloat {
        max(textWidth + gap, minTouch)
    }

    /// 点在热区外返回 -1。重叠时取离文字中心更近的。
    static func hitIndex(contentX: CGFloat, widths: [CGFloat], gap: CGFloat, minTouch: CGFloat) -> Int {
        var best = -1
        var bestDist = CGFloat.greatestFiniteMagnitude
        for i in widths.indices {
            let c = center(index: i, widths: widths, gap: gap)
            let distance = abs(contentX - c)
            if distance <= hitWidth(textWidth: widths[i], gap: gap, minTouch: minTouch) / 2 && distance < bestDist {
                best = i
                bestDist = distance
            }
        }
        return best
    }
}

/**
 * 日期行叠层该画在哪。返回 nil 表示不叠。
 * 有下一天时这一行停在针上，不往上滑；下一天的顶边到了针才让位。
 * 没有下一天才跟着最后一项的底边离开。
 * 与 Android `dayHeaderStickY` / `inFlowDateHidden` 同一套。
 */
enum DateStick {
    static func stickY(
        pinY: CGFloat,
        headerHeight: CGFloat,
        headerTop: CGFloat?,
        nextHeaderTop: CGFloat?,
        lastItemTop: CGFloat?,
        lastItemHeight: CGFloat?,
        sectionVisible: Bool
    ) -> CGFloat? {
        if headerTop == nil && !sectionVisible { return nil }
        if let headerTop, headerTop >= pinY { return nil }
        if let nextHeaderTop {
            if nextHeaderTop <= pinY { return nil }
            return pinY
        }
        let end: CGFloat
        if let lastItemTop, let lastItemHeight {
            end = lastItemTop + lastItemHeight
        } else {
            end = CGFloat.greatestFiniteMagnitude
        }
        let y = min(pinY, end - headerHeight)
        if y <= -headerHeight { return nil }
        return y
    }

    /// 流里的日期行顶边已经高于针线：交给吸顶叠层画，这一行藏掉。
    static func inFlowDateHidden(headerTop: CGFloat, pinY: CGFloat) -> Bool {
        headerTop < pinY
    }
}
