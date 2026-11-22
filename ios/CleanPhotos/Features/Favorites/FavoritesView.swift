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

    private var filedDays: [FiledDayRules.Day] {
        let present = Set(filingLog.rows.lazy.map(\.assetId).filter { library.allById[$0] != nil })
        return FiledDayRules.build(filingLog.rows, present: present)
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
                let filedList = filedDays
                let filedPhotoMap: [String: [PhotoItem]] = {
                    guard let expandedFiled, let day = filedList.first(where: { $0.dayKey == expandedFiled }) else { return [:] }
                    return [expandedFiled: filedPhotos(day)]
                }()
                FavoritesGridCollectionView(
                    sections: library.favoriteSections,
                    filedDays: filedList,
                    expandedFiled: expandedFiled,
                    filedPhotosByDay: filedPhotoMap,
                    initialIDs: initialIDs,
                    chinese: language.isChineseUI,
                    locale: language.effectiveLocale,
                    pipeline: library.thumbnails,
                    onOpenPhoto: { id in
                        reviewLaunch = ReviewLaunch(scope: .favorites, startPhotoId: id, dayId: nil)
                    },
                    onOpenSheet: { dayId in
                        sheetDay = SheetDay(id: dayId)
                    },
                    onToggleFiled: { dayKey in
                        expandedFiled = expandedFiled == dayKey ? nil : dayKey
                    }
                )
                .ignoresSafeArea(.container, edges: .bottom)
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
                        ZStack {
                            Circle()
                                .fill(
                                    RadialGradient(
                                        colors: [DarkroomColors.amberGlow, .clear],
                                        center: .center,
                                        startRadius: 0,
                                        endRadius: 8.5
                                    )
                                )
                                .frame(width: 17, height: 17)
                            Circle()
                                .fill(DarkroomColors.amber)
                                .frame(width: 5, height: 5)
                        }
                        .frame(width: 5, height: 5)
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
        .background(DarkroomColors.room)
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
                    GridDevelopSession.seed(grid: .favorites, id: i.id)
                    if n >= 32 { break }
                }
                if n >= 32 { break }
            }
            if n >= 32 { break }
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
}
