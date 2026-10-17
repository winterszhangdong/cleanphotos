import SwiftUI

/// 小结插页（b-darkroom 05 / Android ReviewSummaryPage）：
/// 背景是当天第一张照片的模糊暗光；顶部当天进度条全部点亮；eyebrow「9月21日 · 周一」+ 28/36 标题；
/// 6pt 比例条（收藏琥珀 / 取消收藏空心琥珀 / 移走退影灰 / 保留相纸白 70%）；36pt 数字（3 列，有取消收藏时 4 列；移走带「释放 X」）；
/// 前 8 张方形缩略（收藏琥珀描边，移走去色压到 30%）；月份条（16pt 竖条，done 60% / 小结那天发光 / 未整理 20% / 无照片 4pt 底座）；
/// 相纸白「下一天 · 日期」卡（底部 88），「回到照片」（底部 40）。横屏不显示缩略条和月份条。
/// 入场编排（Android 同一条 1.9s 时间轴）：标题升起 0ms → 数字滚动 200ms 起每列 +90ms（700ms，三次减速）→
/// 比例条 300ms 起每段 +280ms（标准曲线长出）→ 缩略条 700ms 淡入 → 月份条 800ms 起每根 +22ms（落定弹簧长高）→ 下一天卡 1000ms 升起。
struct DaySummaryView: View {
    let summary: DaySummary
    var locale: Locale
    var insets: EdgeInsets = EdgeInsets()
    var isCurrent: Bool = true
    var landscape: Bool = false
    var onNext: () -> Void
    var onBack: () -> Void
    @EnvironmentObject private var library: PhotoLibraryService
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var start: Date?

    /// 已经播过入场的那几天：翻走再翻回来直接停在终态（Android 的 clock 按 dayKey remember）。
    @MainActor static var played: Set<String> = []

    private static let totalMs = 1900.0
    private static let numStart = 200.0, stackStart = 300.0, stripStart = 700.0, tickStart = 800.0, nextStart = 1000.0

    private var isZh: Bool { locale.identifier.lowercased().hasPrefix("zh") }

    var body: some View {
        TimelineView(.animation(paused: start == nil || (start.map { Date().timeIntervalSince($0) * 1000 > Self.totalMs } ?? true))) { tl in
            let ms: Double = {
                if reduceMotion || Self.played.contains(summary.dayId) && start == nil { return Self.totalMs }
                guard let start else { return 0 }
                return min(Self.totalMs, tl.date.timeIntervalSince(start) * 1000)
            }()
            content(ms: ms)
        }
        .environment(\.locale, locale)
        .onAppear { kick() }
        .onChange(of: isCurrent) { _, _ in kick() }
    }

    private func kick() {
        guard isCurrent, start == nil, !Self.played.contains(summary.dayId) else { return }
        start = Date()
        Self.played.insert(summary.dayId)
    }

    private func window(_ ms: Double, _ s: Double, _ d: Double) -> Double { min(1, max(0, (ms - s) / d)) }
    private func rise(_ ms: Double, _ s: Double) -> Double { SpringToken.gentle.value(at: (ms - s) / 1000) }

    private func content(ms: Double) -> some View {
        GeometryReader { geo in
            ZStack(alignment: .topLeading) {
                Color.clear
                ReviewProgressBar(cells: summary.cells)
                    .frame(height: 2)
                    .padding(.horizontal, 16)
                    .padding(.top, insets.top + 8)

                VStack(alignment: .leading, spacing: 0) {
                    VStack(alignment: .leading, spacing: 0) {
                        Text(eyebrow)
                            .font(DarkroomFonts.plexMono(size: 12))
                            .tracking(1.44)
                            .foregroundStyle(DarkroomColors.dim)
                            .frame(height: 16, alignment: .leading)
                            .padding(.bottom, 8)
                        Text(L10n.string("summary_title", locale: locale))
                            .font(Font.custom("NotoSansSC-SemiBold", size: 28))
                            .tracking(-0.28)
                            .foregroundStyle(DarkroomColors.paper)
                            .frame(height: 36, alignment: .leading)
                    }
                    .riseIn(rise(ms, 0))
                    ratioBar(ms: ms).frame(height: 6).padding(.top, 32).padding(.bottom, 24)
                    stats(ms: ms)
                    if !landscape {
                        strip(width: geo.size.width - 48).padding(.top, 32)
                            .opacity(CubicBezier.decelerate(window(ms, Self.stripStart, 400)))
                        month(ms: ms).padding(.top, 32)
                    }
                }
                .padding(.horizontal, 24)
                .padding(.top, insets.top + (landscape ? 40 : 64))

                VStack(spacing: 0) {
                    Spacer()
                    if !summary.isLastDay {
                        nextCard.padding(.horizontal, 16)
                            .riseIn(rise(ms, Self.nextStart))
                    }
                    Button(action: onBack) {
                        Text(L10n.string("back_to_photos", locale: locale))
                            .font(Font.custom("NotoSansSC-Regular", size: 13))
                            .foregroundStyle(DarkroomColors.dim)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 4)
                            .frame(height: 26)
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("summary-back")
                    .opacity(min(1, max(0, rise(ms, Self.nextStart))))
                    // 卡片底 88、文字底 40：卡与文字之间 88 − 40 − 26 = 22
                    .padding(.top, (landscape ? 56 - 20 : 88 - 40) - 26)
                    .padding(.bottom, insets.bottom + (landscape ? 20 : 40) - 4)
                }
                .frame(maxWidth: .infinity)
            }
            .frame(width: geo.size.width, height: geo.size.height)
            .background {
                ZStack {
                    DarkroomColors.room
                    SummaryBackdrop(id: summary.tally.previewIds.first)
                        .frame(width: geo.size.width, height: geo.size.height)
                        .scaleEffect(1.2)
                }
                .frame(width: geo.size.width, height: geo.size.height)
                .clipped()
            }
        }
    }

    private var eyebrow: String {
        DateFormatCache.string(summary.date, format: isZh ? "M月d日 · EEE" : "MMM d · EEE", locale: locale)
    }

    private func ratioBar(ms: Double) -> some View {
        let s = summary
        let parts: [(Int, Color, Bool)] = [
            (s.favorited, DarkroomColors.amber, false),
            (s.unfavorited, DarkroomColors.amber, true),
            (s.trashed, DarkroomColors.fade, false),
            (s.kept, DarkroomColors.paper.opacity(0.7), false),
        ].filter { $0.0 > 0 }
        let total = max(1, parts.reduce(0) { $0 + $1.0 })
        return GeometryReader { g in
            let avail = g.size.width - CGFloat(max(0, parts.count - 1)) * 2
            HStack(spacing: 2) {
                ForEach(Array(parts.enumerated()), id: \.offset) { i, p in
                    let w = avail * CGFloat(p.0) / CGFloat(total)
                    let grow = CGFloat(CubicBezier.standard(window(ms, Self.stackStart + Double(i) * 280, 280)))
                    ZStack(alignment: .leading) {
                        if p.2 {
                            RoundedRectangle(cornerRadius: 3).strokeBorder(p.1, lineWidth: 1.5).frame(width: max(0, w * grow))
                        } else {
                            RoundedRectangle(cornerRadius: 3).fill(p.1).frame(width: max(0, w * grow))
                        }
                    }
                    .frame(width: w, alignment: .leading)
                }
            }
        }
    }

    /// Android Stats：收藏（有取消收藏且收藏为 0 时不显示）/ 取消收藏（> 0 才显示，琥珀 70%）/ 移走 + 「释放 X」/ 保留。
    private func stats(ms: Double) -> some View {
        var cols: [(Int, String, Color, String?)] = []
        if summary.favorited > 0 || summary.unfavorited == 0 { cols.append((summary.favorited, "stat_favorited", DarkroomColors.amber, nil)) }
        if summary.unfavorited > 0 { cols.append((summary.unfavorited, "stat_unfavorited", DarkroomColors.amber.opacity(0.7), nil)) }
        cols.append((summary.trashed, "stat_removed", DarkroomColors.fadeText, L10n.format("stat_freed", locale: locale, bytes(summary.bytesFreed))))
        cols.append((summary.kept, "stat_kept", DarkroomColors.paper, nil))
        return HStack(alignment: .top, spacing: 8) {
            ForEach(Array(cols.enumerated()), id: \.offset) { i, c in
                let p = window(ms, Self.numStart + Double(i) * 90, 700)
                let eased = 1 - pow(1 - p, 3)
                stat(Int((Double(c.0) * eased).rounded()), c.1, c.2, sub: c.3)
            }
        }
    }

    private func stat(_ n: Int, _ key: String, _ color: Color, sub: String?) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("\(n)")
                .font(DarkroomFonts.schibsted(size: 36, weight: .regular))
                .tracking(-0.72)
                .foregroundStyle(color)
                .frame(height: 40, alignment: .leading)
            Text(L10n.string(key, locale: locale))
                .font(Font.custom("NotoSansSC-Regular", size: 12))
                .foregroundStyle(DarkroomColors.dim)
                .padding(.top, 4)
            if let sub {
                Text(sub)
                    .font(DarkroomFonts.plexMono(size: 10.5))
                    .foregroundStyle(DarkroomColors.dim)
                    .padding(.top, 4)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func strip(width: CGFloat) -> some View {
        let side = (width - 4 * 7) / 8
        return VStack(spacing: 0) {
            Rectangle().fill(DarkroomColors.rim).frame(height: 1)
            HStack(spacing: 4) {
                ForEach(Array(zip(summary.tally.previewIds, summary.tally.previewOutcomes)), id: \.0) { id, outcome in
                    SummaryThumb(id: id, favorited: outcome == .favorited, unfavorited: outcome == .unfavorited, trashed: outcome == .trashed, side: side)
                }
                Spacer(minLength: 0)
            }
            .padding(.top, 24)
        }
    }

    private func month(ms: Double) -> some View {
        let f = DateFormatCache.formatter(isZh ? "M月" : "MMMM", locale: locale)
        return VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .firstTextBaseline) {
                Text(f.string(from: summary.date))
                    .font(Font.custom("NotoSansSC-Regular", size: 12))
                    .foregroundStyle(DarkroomColors.dim)
                Spacer()
                (Text("\(summary.monthDone)").foregroundColor(DarkroomColors.paper).fontWeight(.medium)
                 + Text(L10n.plural("summary_days_done", count: summary.monthWithPhotos, locale: locale)).foregroundColor(DarkroomColors.dim))
                    .font(DarkroomFonts.plexMono(size: 12))
            }
            HStack(alignment: .bottom, spacing: 4) {
                ForEach(Array(summary.month.enumerated()), id: \.offset) { i, s in
                    // 落定弹簧 scaleY 0 → 1（底边为原点），透明度 0.2 → 1
                    let p = CGFloat(SpringToken.settle.value(at: (ms - Self.tickStart - Double(i) * 22) / 1000))
                    MonthTick(state: s, future: isFuture(day: i + 1))
                        .scaleEffect(x: 1, y: max(0, p), anchor: .bottom)
                        .opacity(Double(0.2 + 0.8 * min(1, max(0, p))))
                }
            }
            .frame(height: 16, alignment: .bottom)
        }
    }

    private func isFuture(day: Int) -> Bool {
        let key = String(format: "%@-%02d", String(summary.dayId.prefix(7)), day)
        return key > ReviewSessionModel.dayKey(Date())
    }

    private var nextCard: some View {
        Button(action: onNext) {
            HStack(spacing: 12) {
                if let cover = summary.nextDayCoverId {
                    SummaryThumb(id: cover, favorited: false, unfavorited: false, trashed: false, side: 48, corner: 12)
                }
                VStack(alignment: .leading, spacing: 0) {
                    Text(L10n.format("next_day", locale: locale, nextDayTitle))
                        .font(Font.custom("NotoSansSC-SemiBold", size: 15))
                        .foregroundStyle(DarkroomColors.room)
                        .frame(height: 20)
                    Text(summary.nextDayUnseen > 0 ? L10n.plural("next_unseen", count: summary.nextDayUnseen, locale: locale) : L10n.string("next_all_seen", locale: locale))
                        .font(Font.custom("NotoSansSC-Regular", size: 12))
                        .foregroundStyle(Color(red: 0x6E/255, green: 0x67/255, blue: 0x5F/255))
                        .frame(height: 16)
                }
                Spacer(minLength: 0)
                Image(systemName: "arrow.right")
                    .font(.system(size: 17, weight: .medium))
                    .frame(width: 20, height: 20)
                    .foregroundStyle(DarkroomColors.room)
            }
            .padding(.leading, 8)
            .padding(.trailing, 16)
            .frame(height: 64)
            .background(DarkroomColors.paper, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            .shadow(color: .black.opacity(0.4), radius: 20, y: 16)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("summary-next")
    }

    private var nextDayTitle: String {
        guard let id = summary.nextDayId else { return "" }
        let parts = id.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3, let d = Calendar.current.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2])) else { return id }
        return DateFormatCache.string(d, format: isZh ? "M月d日" : "MMM d", locale: locale)
    }

    private func bytes(_ b: Int64) -> String { FileSizeFormatter.format(b) }
}

private struct MonthTick: View {
    let state: MonthDayState
    let future: Bool
    private static let now = Color(red: 1, green: 246/255, blue: 232/255)
    var body: some View {
        switch state {
        case .done: RoundedRectangle(cornerRadius: 2).fill(DarkroomColors.paper.opacity(0.6)).frame(height: 16)
        case .pending: RoundedRectangle(cornerRadius: 2).fill(DarkroomColors.paper.opacity(0.2)).frame(height: 16)
        case .highlight:
            RoundedRectangle(cornerRadius: 2).fill(DarkroomColors.paper).frame(height: 16)
                .shadow(color: Self.now.opacity(0.8), radius: 4)
        case .empty:
            RoundedRectangle(cornerRadius: 2).fill(DarkroomColors.paper.opacity(future ? 0.05 : 0.12)).frame(height: 4)
        }
    }
}

/// .sum-bg：当天照片 blur 36 · brightness 0.32 · saturate 1.2，四边外扩 40。
private struct SummaryBackdrop: View {
    let id: String?
    @EnvironmentObject private var library: PhotoLibraryService
    @State private var image: UIImage?
    var body: some View {
        ZStack {
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
                    .accessibilityHidden(true)
                    .blur(radius: 36)
                    .saturation(1.2)
                    .colorMultiply(Color(white: 0.32))
            }
        }
        .onAppear {
            if let id, let asset = library.allById[id]?.asset {
                _ = library.requestThumbnail(for: asset, side: 120) { img in Task { @MainActor in image = img } }
            }
        }
    }
}

private struct SummaryThumb: View {
    let id: String
    var favorited: Bool
    var unfavorited: Bool
    var trashed: Bool
    var side: CGFloat
    var corner: CGFloat = 4
    @EnvironmentObject private var library: PhotoLibraryService
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            DarkroomColors.tileFill
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .accessibilityHidden(true)
                    .saturation(trashed ? 0 : 1)
                    .colorMultiply(Color(white: trashed ? 0.3 : 1))
            }
        }
        .frame(width: side, height: side)
        .clipShape(RoundedRectangle(cornerRadius: corner, style: .continuous))
        .overlay {
            if favorited || unfavorited {
                RoundedRectangle(cornerRadius: corner, style: .continuous)
                    .strokeBorder(DarkroomColors.amber.opacity(unfavorited ? 0.5 : 1), lineWidth: 1.5)
            }
        }
        .onAppear {
            if let asset = library.allById[id]?.asset {
                _ = library.requestThumbnail(for: asset, side: side * 2) { img in
                    Task { @MainActor in image = img }
                }
            }
        }
    }
}

private extension View {
    /// 升起：translationY 24 → 0 + 淡入，q 为舒缓弹簧值（可略过冲）。
    func riseIn(_ q: Double) -> some View {
        self.opacity(min(1, max(0, q))).offset(y: CGFloat(1 - q) * 24)
    }
}
