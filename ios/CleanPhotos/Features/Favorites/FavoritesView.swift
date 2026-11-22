import SwiftUI
import Photos

private struct SheetDay: Identifiable {
    let id: String
}

struct FavoritesView: View {
    @EnvironmentObject private var library: PhotoLibraryService
    @EnvironmentObject private var language: LanguageStore
    @Binding var reviewLaunch: ReviewLaunch?
    /// 空状态「去清理」：回到照片页（Android onNavigateBack）。
    var onGoReview: () -> Void = {}
    @StateObject private var filingLog = FilingLog()
    @State private var sheetDay: SheetDay?
    @State private var expandedFiled: String?
    @State private var initialIDs: Set<String> = []
    @State private var didSeed = false
    /// 网格宽量自列表（横屏左右安全区；竖屏 = 屏宽）。
    @State private var listWidth: CGFloat = UIScreen.main.bounds.width

    private var filedDays: [FiledDayRules.Day] {
        FiledDayRules.build(filingLog.rows, present: Set(library.allById.keys))
    }

    /// 收藏空了但「已冲洗」还在时，不显示收藏空状态。
    private var showEmpty: Bool {
        guard library.favoriteCount == 0 else { return false }
        if filingLog.rows.isEmpty { return true }
        if library.isLoading || library.isPartial { return false }
        return filedDays.isEmpty
    }

    private var pendingDays: Int {
        library.favoriteSections.reduce(0) { $0 + $1.days.count }
    }

    var body: some View {
        ZStack(alignment: .top) {
            DarkroomColors.room.ignoresSafeArea()
            if showEmpty {
                empty
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        Color.clear.frame(height: DarkroomDimens.headerHeight + 8)
                            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { w in if w > 0 { listWidth = w } }
                        // 每天的格子拆成行，作为 LazyVStack 的直接子视图。套着的 ForEach 会把整天一次排完。
                        ForEach(lines) { line in
                            lineView(line)
                        }
                        Color.clear.frame(height: 140)
                    }
                }
                .scrollIndicators(.hidden)
            }

            VStack {
                header
                Spacer()
            }

            if let banner = library.undoBanner, !banner.single, !library.reviewActive {
                VStack {
                    Spacer()
                    UndoCapsule(banner: banner, locale: language.effectiveLocale) {
                        library.undoLast()
                    }
                    .padding(.horizontal, 16)
                    .padding(.bottom, DarkroomDimens.navBottom + DarkroomDimens.navHeight + 16)
                }
            }
        }
        .environment(\.locale, language.effectiveLocale)
        .onAppear {
            if ScrollProbe.enabled { ScrollProbe.shared.page = "favorites" }
            seed(); Task { await pruneIfNeeded() }
        }
        .onChange(of: library.favoriteSections) { _, _ in seed() }
        .onChange(of: library.isLoading) { _, loading in
            if !loading { Task { await pruneIfNeeded() } }
        }
        .onReceive(NotificationCenter.default.publisher(for: .cleanphotosHarnessFiling)) { note in
            if (note.object as? String) == "close" {
                sheetDay = nil
            } else if let id = library.favoriteSections.first?.days.first?.id {
                sheetDay = SheetDay(id: id)
            }
        }
        .fullScreenCover(item: $sheetDay) { day in
            ContactSheetScreen(dayId: day.id, filingLog: filingLog) { sheetDay = nil }
                .environmentObject(library)
                .environmentObject(language)
        }
    }

    private var header: some View {
        HStack(alignment: .bottom) {
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                OdometerText(value: Int64(library.favoriteCount), text: formatted(library.favoriteCount), font: DarkroomType.countBig, color: DarkroomColors.paper)
                Text(unitText)
                    .font(DarkroomType.countUnit)
                    .foregroundStyle(DarkroomColors.dim)
                if pendingDays > 0 {
                    HStack(alignment: .center, spacing: 6) {
                        Circle()
                            .fill(DarkroomColors.amber)
                            .frame(width: 5, height: 5)
                            .shadow(color: DarkroomColors.amberGlow, radius: 6)
                            .alignmentGuide(.firstTextBaseline) { d in d.height * 0.85 }
                        Text(FilingCopy.daysPending(pendingDays, locale: language.effectiveLocale))
                            .font(DarkroomType.countSub)
                            .foregroundStyle(DarkroomColors.dim)
                    }
                    .padding(.leading, 2)
                }
            }
            Spacer()
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 12)
        .frame(height: DarkroomDimens.headerHeight, alignment: .bottom)
        .background(DarkroomColors.room.opacity(0.92))
    }

    private var unitText: String {
        if language.isChineseUI {
            return L10n.string("header_count_unit", locale: language.effectiveLocale)
        }
        return FilingCopy.favoriteUnit(count: library.favoriteCount, locale: language.effectiveLocale)
    }

    /// 收藏空状态（mock 07 / Android FavoritesScreen）：一叠相纸（顶卡上推 + 星标弹跳）+ 文案 + 「去清理 · N 张未看」。
    private var empty: some View {
        let loc = language.effectiveLocale
        return EmptyStateArea {
            EmptyPileArt(sink: false).padding(.bottom, 32)
            EmptyTitleAndBody(L10n.string("favorites_empty_title", locale: loc), L10n.string("favorites_empty_body", locale: loc))
            GhostButton(title: L10n.string("go_review", locale: loc),
                        mono: L10n.pluralFormat("unseen_count", count: library.unseenCount, locale: loc,
                                                NumberFormatter.localizedString(from: NSNumber(value: library.unseenCount), number: .decimal)),
                        action: onGoReview)
                .accessibilityIdentifier("favorites-go-review")
        }
    }

    private struct FavLine: Identifiable {
        enum Kind {
            case year(Int)
            case header(PhotoDaySection)
            case tiles(items: [PhotoItem], dayId: String, start: Int)
            case filedHeader(Int)
            case filedRow(FiledDayRules.Day)
            case filedTiles(items: [PhotoItem], dayKey: String, start: Int)
        }
        let id: String
        let kind: Kind
    }

    private var lines: [FavLine] {
        let cols = 4
        var out: [FavLine] = []
        for year in library.favoriteSections {
            if year.year > 0 {
                out.append(FavLine(id: "y-\(year.id)", kind: .year(year.year)))
            }
            for day in year.days {
                out.append(FavLine(id: "h-\(day.id)", kind: .header(day)))
                var start = 0
                while start < day.items.count {
                    let end = min(start + cols, day.items.count)
                    out.append(FavLine(
                        id: "t-\(day.id)-\(start)",
                        kind: .tiles(items: Array(day.items[start..<end]), dayId: day.id, start: start)
                    ))
                    start = end
                }
            }
        }
        let filed = filedDays
        if !filed.isEmpty {
            let total = filed.reduce(0) { $0 + $1.count }
            out.append(FavLine(id: "filed-h", kind: .filedHeader(total)))
            for day in filed {
                out.append(FavLine(id: "filed-\(day.dayKey)", kind: .filedRow(day)))
                if expandedFiled == day.dayKey {
                    let photos = filedPhotos(day)
                    var start = 0
                    while start < photos.count {
                        let end = min(start + cols, photos.count)
                        out.append(FavLine(
                            id: "ft-\(day.dayKey)-\(start)",
                            kind: .filedTiles(items: Array(photos[start..<end]), dayKey: day.dayKey, start: start)
                        ))
                        start = end
                    }
                }
            }
        }
        return out
    }

    @ViewBuilder
    private func lineView(_ line: FavLine) -> some View {
        switch line.kind {
        case .year(let year):
            yearRow(year)
        case .header(let day):
            dayHeader(day)
        case .tiles(let items, let dayId, let start):
            tileRow(items, start: start, dayId: dayId, tappable: true)
        case .filedHeader(let total):
            filedHeader(total)
        case .filedRow(let day):
            filedRow(day)
        case .filedTiles(let items, _, let start):
            tileRow(items, start: start, dayId: "filed", tappable: false)
                .allowsHitTesting(false)
                .accessibilityElement(children: .ignore)
        }
    }

    private func tileRow(_ items: [PhotoItem], start: Int, dayId: String, tappable: Bool) -> some View {
        let cols = 4
        let gap: CGFloat = 2
        let side = (max(listWidth, 1) - gap * CGFloat(cols - 1)) / CGFloat(cols)
        return HStack(spacing: gap) {
            ForEach(Array(items.enumerated()), id: \.element.id) { offset, item in
                let index = start + offset
                let develop = tappable && initialIDs.contains(item.id)
                PhotoTileView(
                    item: item,
                    side: side,
                    develop: develop,
                    developDelay: GridDevelopSession.staggerDelay(row: index / cols, col: index % cols),
                    showUnseen: false
                )
                .onTapGesture {
                    guard tappable else { return }
                    reviewLaunch = ReviewLaunch(scope: .favorites, startPhotoId: item.id, dayId: nil)
                }
            }
            if items.count < cols { Spacer(minLength: 0) }
        }
        .padding(.bottom, gap)
    }

    private func yearRow(_ year: Int) -> some View {
        HStack(spacing: 12) {
            Text(String(year)).font(DarkroomType.countSub).foregroundStyle(DarkroomColors.dim)
            Rectangle().fill(DarkroomColors.rim).frame(height: 1)
        }
        .frame(height: 32)
        .padding(.horizontal, 16)
    }

    private func dayHeader(_ day: PhotoDaySection) -> some View {
        HStack(spacing: 10) {
            Text(day.id == DayKeyRule.unknown ? "—" : dayNum(day.date)).font(DarkroomType.dayNumber).foregroundStyle(DarkroomColors.paper)
            Text(day.id == DayKeyRule.unknown ? L10n.string("date_unknown", locale: language.effectiveLocale) : dayMeta(day.date)).font(DarkroomType.dayMeta).foregroundStyle(DarkroomColors.dim)
            Spacer(minLength: 8)
            Text(L10n.plural("favorites_section_count", count: day.totalCount, locale: language.effectiveLocale))
                .font(DarkroomType.dayRight)
                .foregroundStyle(DarkroomColors.dim)
            sortPill
        }
        .padding(.horizontal, 16)
        .padding(.top, 16)
        .padding(.bottom, 10)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
        .accessibilityHint(FilingCopy.sortDay(language.effectiveLocale))
        .onTapGesture { sheetDay = SheetDay(id: day.id) }
    }

    private var sortPill: some View {
        HStack(spacing: 6) {
            Image(systemName: "film")
                .font(.system(size: 11, weight: .medium))
            Text(FilingCopy.sortDay(language.effectiveLocale))
                .font(Font.custom("NotoSansSC-Medium", size: 12))
        }
        .foregroundStyle(DarkroomColors.paper)
        .padding(.leading, 10)
        .padding(.trailing, 12)
        .frame(height: 32)
        .background(DarkroomColors.tray, in: Capsule())
        .overlay(Capsule().strokeBorder(DarkroomColors.rim2, lineWidth: 1))
    }

    private func filedHeader(_ total: Int) -> some View {
        HStack(spacing: 12) {
            Text(FilingCopy.filedHeader(total, locale: language.effectiveLocale))
                .font(DarkroomType.countSub)
                .foregroundStyle(DarkroomColors.dim)
                .lineLimit(1)
            Rectangle().fill(DarkroomColors.rim).frame(height: 1)
        }
        .frame(height: 32)
        .padding(.horizontal, 16)
        .padding(.top, 12)
    }

    private func filedRow(_ day: FiledDayRules.Day) -> some View {
        let open = expandedFiled == day.dayKey
        let date = NextDayRule.date(from: day.dayKey)
        return HStack(spacing: 10) {
            Text(date == nil ? "—" : dayNum(date!)).font(DarkroomType.dayNumber).foregroundStyle(DarkroomColors.paper)
            Text(date == nil ? L10n.string("date_unknown", locale: language.effectiveLocale) : dayMeta(date!))
                .font(DarkroomType.dayMeta)
                .foregroundStyle(DarkroomColors.dim)
                .lineLimit(1)
            Spacer(minLength: 8)
            HStack(spacing: 6) {
                ForEach(Array(day.albums.prefix(2).enumerated()), id: \.offset) { _, chip in
                    Text(FilingCopy.chip(chip.name, count: chip.count))
                        .font(Font.custom("NotoSansSC-Medium", size: 11))
                        .foregroundStyle(DarkroomColors.paper)
                        .lineLimit(1)
                        .padding(.horizontal, 8)
                        .frame(height: 22)
                        .background(DarkroomColors.tray2, in: RoundedRectangle(cornerRadius: 3, style: .continuous))
                }
            }
            Image(systemName: "chevron.right")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(DarkroomColors.dim)
                .rotationEffect(.degrees(open ? 90 : 0))
        }
        .padding(.horizontal, 16)
        .frame(minHeight: 48)
        .contentShape(Rectangle())
        .onTapGesture {
            withAnimation(DarkroomMotion.standard) {
                expandedFiled = open ? nil : day.dayKey
            }
        }
    }

    private func filedPhotos(_ day: FiledDayRules.Day) -> [PhotoItem] {
        day.assetIds.compactMap { library.allById[$0] }.sorted { a, b in
            if a.creationDate != b.creationDate { return a.creationDate < b.creationDate }
            return a.id < b.id
        }
    }

    private func seed() {
        guard !didSeed else { return }
        var ids: Set<String> = []
        var n = 0
        for y in library.favoriteSections {
            for d in y.days {
                for i in d.items {
                    ids.insert(i.id); n += 1
                    _ = GridDevelopSession.shouldDevelop(grid: .favorites, id: i.id, isInitialPass: true)
                    if n >= 24 { break }
                }
                if n >= 24 { break }
            }
            if n >= 24 { break }
        }
        if !ids.isEmpty { initialIDs = ids; didSeed = true }
    }

    /// 完整访问权、而且这一轮图库已经读完，才清掉已经不在图库里的装袋记录。
    private func pruneIfNeeded() async {
        guard library.authorization == .authorized, !library.isLoading, !library.isPartial else { return }
        let ids = filingLog.rows.map(\.assetId)
        guard !ids.isEmpty else { return }
        let found = await AlbumLibrary.foundIds(ids)
        filingLog.pruneDisappeared(candidates: ids, found: found, libraryCount: library.allById.count)
    }

    private func formatted(_ n: Int) -> String {
        let f = NumberFormatter(); f.numberStyle = .decimal; f.locale = language.effectiveLocale
        return f.string(from: NSNumber(value: n)) ?? "\(n)"
    }
    private func dayNum(_ d: Date) -> String {
        DateFormatCache.string(d, format: "d", locale: language.effectiveLocale)
    }
    private func dayMeta(_ d: Date) -> String {
        DateFormatCache.string(d, format: language.isChineseUI ? "M月 · EEE" : "MMM · EEE", locale: language.effectiveLocale)
    }
}
