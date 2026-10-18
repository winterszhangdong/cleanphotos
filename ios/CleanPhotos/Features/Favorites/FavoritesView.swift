import SwiftUI

struct FavoritesView: View {
    @EnvironmentObject private var library: PhotoLibraryService
    @EnvironmentObject private var language: LanguageStore
    @Binding var reviewLaunch: ReviewLaunch?
    /// 空状态「去清理」：回到照片页（Android onNavigateBack）。
    var onGoReview: () -> Void = {}
    @State private var initialIDs: Set<String> = []
    @State private var didSeed = false
    /// 网格宽量自列表（横屏左右安全区；竖屏 = 屏宽）。
    @State private var listWidth: CGFloat = UIScreen.main.bounds.width

    var body: some View {
        ZStack(alignment: .top) {
            DarkroomColors.room.ignoresSafeArea()
            if library.favoriteCount == 0 {
                empty
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        Color.clear.frame(height: DarkroomDimens.headerHeight + 8)
                            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { w in if w > 0 { listWidth = w } }
                        ForEach(library.favoriteSections) { year in
                            if year.year > 0 { yearRow(year.year) }
                            ForEach(year.days) { day in
                                dayHeader(day)
                                dayGrid(day)
                            }
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
        .onAppear { seed() }
        .onChange(of: library.favoriteSections) { _, _ in seed() }
    }

    private var header: some View {
        HStack(alignment: .bottom) {
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text("tab_favorites")
                        .font(DarkroomType.eyebrow)
                        .tracking(1.4)
                        .foregroundStyle(DarkroomColors.dim)
                    // Android FavPip：只在有收藏时显示
                    if library.favoriteCount > 0 {
                    Circle()
                        .fill(DarkroomColors.amber)
                        .frame(width: 5, height: 5)
                        .shadow(color: DarkroomColors.amberGlow, radius: 6)
                    }
                }
                HStack(alignment: .firstTextBaseline, spacing: 4) {
                    OdometerText(value: Int64(library.favoriteCount), text: formatted(library.favoriteCount), font: DarkroomType.countBig, color: DarkroomColors.paper)
                    if language.isChineseUI {
                        Text("header_count_unit")
                            .font(DarkroomType.countUnit)
                            .foregroundStyle(DarkroomColors.dim)
                    }
                }
            }
            Spacer()
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 12)
        .frame(height: DarkroomDimens.headerHeight, alignment: .bottom)
        .background(DarkroomColors.room.opacity(0.92))
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

    private func yearRow(_ year: Int) -> some View {
        HStack(spacing: 12) {
            Text(String(year)).font(DarkroomType.countSub).foregroundStyle(DarkroomColors.dim)
            Rectangle().fill(DarkroomColors.rim).frame(height: 1)
        }
        .frame(height: 32)
        .padding(.horizontal, 16)
    }

    private func dayHeader(_ day: PhotoDaySection) -> some View {
        HStack {
            Text(day.id == DayKeyRule.unknown ? "—" : dayNum(day.date)).font(DarkroomType.dayNumber).foregroundStyle(DarkroomColors.paper)
            Text(day.id == DayKeyRule.unknown ? L10n.string("date_unknown", locale: language.effectiveLocale) : dayMeta(day.date)).font(DarkroomType.dayMeta).foregroundStyle(DarkroomColors.dim)
            Spacer()
            Text(L10n.plural("favorites_section_count", count: day.totalCount, locale: language.effectiveLocale))
                .font(DarkroomType.dayRight)
                .foregroundStyle(DarkroomColors.dim)
            Image(systemName: "chevron.right").font(.system(size: 12)).foregroundStyle(DarkroomColors.dim)
        }
        .padding(.horizontal, 16)
        .padding(.top, 16)
        .padding(.bottom, 10)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
        .onTapGesture {
            if let first = day.allItems.first {
                reviewLaunch = ReviewLaunch(scope: .favorites, startPhotoId: first.id, dayId: day.id)
            }
        }
    }

    private func dayGrid(_ day: PhotoDaySection) -> some View {
        let cols = 4
        let gap: CGFloat = 2
        return GeometryReader { geo in
            let side = (geo.size.width - gap * CGFloat(cols - 1)) / CGFloat(cols)
            LazyVGrid(columns: Array(repeating: GridItem(.fixed(side), spacing: gap), count: cols), spacing: gap) {
                ForEach(Array(day.items.enumerated()), id: \.element.id) { index, item in
                    let develop = initialIDs.contains(item.id)
                    PhotoTileView(item: item, side: side, develop: develop, developDelay: GridDevelopSession.staggerDelay(row: index/cols, col: index%cols), showUnseen: false)
                        .onTapGesture {
                            reviewLaunch = ReviewLaunch(scope: .favorites, startPhotoId: item.id, dayId: nil)
                        }
                }
            }
        }
        .frame(height: gridHeight(count: day.items.count))
    }

    private func gridHeight(count: Int) -> CGFloat {
        let cols: CGFloat = 4
        let rows = ceil(CGFloat(count) / cols)
        let w = listWidth
        let side = (w - 2 * (cols - 1)) / cols
        return rows * side + max(0, rows - 1) * 2
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
