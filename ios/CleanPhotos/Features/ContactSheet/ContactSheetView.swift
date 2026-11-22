import SwiftUI
import Photos
import UIKit

/// 某一天的印样表。点圈 / 横滑只圈，底部托盘点相册放进去。拖进袋子不是唯一路径。
struct ContactSheetScreen: View {
    @EnvironmentObject private var library: PhotoLibraryService
    @EnvironmentObject private var language: LanguageStore
    @StateObject private var model: ContactSheetModel
    var onClose: () -> Void

    @State private var peek: PhotoItem?
    @State private var showNew = false
    @State private var showSearch = false

    init(dayId: String, filingLog: FilingLog, onClose: @escaping () -> Void) {
        self.onClose = onClose
        _model = StateObject(wrappedValue: ContactSheetModel(dayId: dayId, filingLog: filingLog))
    }

    private var locale: Locale { language.effectiveLocale }

    var body: some View {
        ZStack {
            DarkroomColors.room.ignoresSafeArea()
            if let summary = model.summary {
                summaryPage(summary)
            } else {
                photoScroll
            }
            if let peek {
                PeekOverlay(item: peek)
            }
        }
        .safeAreaInset(edge: .top, spacing: 0) { header }
        .safeAreaInset(edge: .bottom, spacing: 0) { bottomChrome }
        .environment(\.locale, locale)
        .preferredColorScheme(.dark)
        .reduceMotionGate()
        .onAppear { model.bind(library) }
        .task { await model.loadAlbums() }
        .onChange(of: library.favoriteSections) { _, _ in model.libraryDidChange() }
        .task(id: model.notice?.id) {
            guard let id = model.notice?.id else { return }
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            model.dismissNotice(id)
        }
        .sheet(isPresented: $showNew) { newAlbumSheet }
        .sheet(isPresented: $showSearch) { searchSheet }
    }

    private var header: some View {
        HStack(alignment: .bottom, spacing: 10) {
            ToolIconButton(icon: .back, action: onClose)
                .accessibilityLabel(L10n.string("cd_back", locale: locale))
                .disabled(model.busy)
            VStack(alignment: .leading, spacing: 2) {
                Text(headerDate)
                    .font(Font.custom("NotoSansSC-SemiBold", size: 18))
                    .foregroundStyle(DarkroomColors.paper)
                    .lineLimit(1)
                Text(model.circled.isEmpty
                     ? FilingCopy.pending(model.photos.count, locale: locale)
                     : FilingCopy.circled(model.circled.count, locale: locale))
                    .font(DarkroomType.countSub)
                    .foregroundStyle(DarkroomColors.dim)
            }
            Spacer(minLength: 4)
            if !model.photos.isEmpty && model.summary == nil {
                TrayTextButton(
                    title: FilingCopy.circleAll(locale),
                    enabled: !model.busy && model.circled.count < model.photos.count,
                    action: { model.circleAll() }
                )
                TrayTextButton(
                    title: FilingCopy.clear(locale),
                    enabled: !model.busy && !model.circled.isEmpty,
                    action: { model.clearCircles() }
                )
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 12)
        .frame(maxWidth: .infinity)
        .background(DarkroomColors.room)
    }

    @ViewBuilder
    private var photoScroll: some View {
        if model.photos.isEmpty {
            ScrollView {
                Text(FilingCopy.emptyDay(locale))
                    .font(DarkroomType.body)
                    .foregroundStyle(DarkroomColors.dim)
                    .frame(maxWidth: .infinity)
                    .padding(.top, 48)
            }
            .scrollIndicators(.hidden)
        } else {
            ContactSheetGridView(
                photos: model.photos,
                circled: model.circled,
                busy: model.busy,
                locale: locale,
                pipeline: library.thumbnails,
                onToggle: { model.toggle($0) },
                onCircle: { model.circle($0) },
                onPeek: { peek = $0 }
            )
        }
    }

    private var bottomChrome: some View {
        VStack(spacing: 8) {
            if let undo = model.undo {
                FilingUndoBar(banner: undo, locale: locale) {
                    Task { await model.undo(locale: locale) }
                }
                .padding(.horizontal, 16)
            }
            if let notice = model.notice {
                DarkToast(text: notice.text)
            }
            if model.summary == nil {
                if !model.circled.isEmpty {
                    tray
                } else if !model.photos.isEmpty && model.undo == nil {
                    Text(FilingCopy.sheetHint(locale))
                        .font(Font.custom("NotoSansSC-Regular", size: 12))
                        .foregroundStyle(DarkroomColors.dim)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 24)
                        .padding(.bottom, 4)
                }
            }
        }
        .padding(.bottom, 8)
        .frame(maxWidth: .infinity)
        .background(model.circled.isEmpty || model.summary != nil ? Color.clear : DarkroomColors.room)
    }

    private var tray: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                Text(FilingCopy.trayTitle(locale))
                    .font(Font.custom("NotoSansSC-SemiBold", size: 15))
                    .foregroundStyle(DarkroomColors.paper)
                Spacer()
                Button {
                    model.unfavoriteCircled(locale: locale)
                } label: {
                    Text(L10n.string("action_unfavorite", locale: locale))
                        .font(Font.custom("NotoSansSC-Medium", size: 13))
                        .foregroundStyle(DarkroomColors.paper)
                }
                .buttonStyle(.plain)
                .disabled(model.busy)
                .opacity(model.busy ? 0.3 : 1)
            }
            Text(FilingCopy.parkedHint(locale))
                .font(DarkroomType.dayMeta)
                .foregroundStyle(DarkroomColors.dim)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    trayChip(title: FilingCopy.newSleeve(locale), subtitle: nil, icon: .plus) {
                        showNew = true
                    }
                    ForEach(model.tray, id: \.id) { album in
                        trayChip(title: album.title.isEmpty ? "—" : album.title, subtitle: FilingCopy.albumCount(album.count, locale: locale), icon: nil) {
                            Task { await model.fileExisting(id: album.id, fromMore: false, locale: locale) }
                        }
                    }
                    if model.moreCount > 0 {
                        trayChip(title: FilingCopy.more(model.moreCount, locale: locale), subtitle: nil, icon: nil) {
                            showSearch = true
                        }
                    }
                }
            }
            Toggle(isOn: $model.systemFavorite) {
                Text(FilingCopy.systemFavorite(locale))
                    .font(Font.custom("NotoSansSC-Regular", size: 13))
                    .foregroundStyle(DarkroomColors.paper)
            }
            .tint(DarkroomColors.paper)
            .disabled(model.busy)
        }
        .padding(.horizontal, 16)
        .padding(.top, 14)
        .padding(.bottom, 10)
        .background(DarkroomColors.tray)
        .overlay(alignment: .top) { Rectangle().fill(DarkroomColors.rim).frame(height: 1) }
    }

    private func trayChip(title: String, subtitle: String?, icon: DarkroomIcon?, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 4) {
                if let icon {
                    DarkroomIconView(icon: icon, size: 16)
                        .foregroundStyle(DarkroomColors.paper)
                }
                Text(title)
                    .font(Font.custom("NotoSansSC-Medium", size: 13))
                    .foregroundStyle(DarkroomColors.paper)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                if let subtitle {
                    Text(subtitle)
                        .font(DarkroomType.dayRight)
                        .foregroundStyle(DarkroomColors.dim)
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            .padding(10)
            .frame(width: 96, height: 84, alignment: .topLeading)
            .background(DarkroomColors.tray2, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(DarkroomColors.rim2, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .disabled(model.busy)
        .opacity(model.busy ? 0.4 : 1)
    }

    private var newAlbumSheet: some View {
        NewAlbumSheet(count: model.circled.count, albums: model.allAlbums, locale: locale, onCancel: { showNew = false }) { name in
            showNew = false
            Task { await model.fileNew(name: name, locale: locale) }
        }
    }

    private var searchSheet: some View {
        AlbumSearchSheet(albums: model.allAlbums, locale: locale, showHint: !model.circled.isEmpty, onPick: { album in
            showSearch = false
            Task { await model.fileExisting(id: album.id, fromMore: true, locale: locale) }
        }, onCreate: { name in
            showSearch = false
            Task { await model.fileNew(name: name, locale: locale) }
        })
    }

    private func summaryPage(_ summary: FilingSummary) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(headerDate)
                .font(DarkroomType.eyebrow)
                .foregroundStyle(DarkroomColors.dim)
            Text(FilingCopy.summaryTitle(locale))
                .font(DarkroomType.permTitle)
                .foregroundStyle(DarkroomColors.paper)
                .padding(.top, 8)
            VStack(spacing: 10) {
                ForEach(summary.lines) { line in
                    summaryRow(
                        title: line.name.isEmpty ? "—" : line.name,
                        bold: "+\(line.added)",
                        rest: line.created ? FilingCopy.summaryNew(locale) : FilingCopy.summaryTotal(line.total, locale: locale)
                    )
                }
                if summary.unfavorited > 0 {
                    summaryRow(
                        title: L10n.string("action_unfavorite", locale: locale),
                        bold: "\(summary.unfavorited)",
                        rest: FilingCopy.summaryUnfavoriteRest(locale)
                    )
                }
            }
            .padding(.top, 28)
            Spacer(minLength: 16)
            if let next = summary.next {
                Button {
                    model.openDay(next.dayKey)
                } label: {
                    Text(FilingCopy.nextDay(shortDate(next.dayKey), locale: locale))
                        .font(Font.custom("NotoSansSC-SemiBold", size: 15))
                        .foregroundStyle(DarkroomColors.room)
                        .frame(maxWidth: .infinity)
                        .frame(height: 48)
                        .background(DarkroomColors.paper, in: Capsule())
                }
                .buttonStyle(.plain)
                .disabled(model.busy)
                .padding(.bottom, 12)
            }
            Button(action: onClose) {
                Text(FilingCopy.backToFavorites(locale))
                    .font(Font.custom("NotoSansSC-Medium", size: 14))
                    .foregroundStyle(DarkroomColors.dim)
                    .frame(maxWidth: .infinity)
                    .frame(height: 40)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 24)
        .padding(.top, 12)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }

    private func summaryRow(title: String, bold: String, rest: String) -> some View {
        HStack(spacing: 8) {
            Text(title)
                .font(Font.custom("NotoSansSC-Medium", size: 15))
                .foregroundStyle(DarkroomColors.paper)
                .lineLimit(1)
            Spacer(minLength: 8)
            Text(bold)
                .font(DarkroomFonts.plexMono(size: 13, weight: .medium))
                .foregroundStyle(DarkroomColors.paper)
            Text(rest)
                .font(DarkroomType.dayMeta)
                .foregroundStyle(DarkroomColors.dim)
                .lineLimit(1)
        }
        .padding(.horizontal, 16)
        .frame(height: 56)
        .frame(maxWidth: .infinity)
        .background(DarkroomColors.tray, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private var headerDate: String {
        if model.dayId == DayKeyRule.unknown { return L10n.string("date_unknown", locale: locale) }
        return shortDate(model.dayId)
    }

    private func shortDate(_ dayKey: String) -> String {
        guard let date = NextDayRule.date(from: dayKey) else { return L10n.string("date_unknown", locale: locale) }
        let day = DateFormatCache.string(date, format: "d", locale: locale)
        let meta = DateFormatCache.string(date, format: language.isChineseUI ? "M月 · EEE" : "MMM · EEE", locale: locale)
        return day + "  " + meta
    }
}

private struct PeekOverlay: View {
    let item: PhotoItem
    @EnvironmentObject private var library: PhotoLibraryService
    @State private var image: UIImage?
    @State private var token: ThumbnailToken?

    var body: some View {
        ZStack {
            Color.black.opacity(0.92).ignoresSafeArea()
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .padding(24)
            } else {
                ProgressView().tint(DarkroomColors.paper)
            }
        }
        .allowsHitTesting(false)
        .onAppear {
            token?.cancel()
            let screenW = UIScreen.main.bounds.width
            let stripSide = max(1, (screenW - DarkroomDimens.gridGap * 2) / 3)
            if let seed = library.thumbnails.cached(id: item.id, side: stripSide, style: .normal) {
                image = seed
            }
            let bounds = UIScreen.main.bounds.size
            let box = CGSize(width: max(1, bounds.width - 48), height: max(1, bounds.height - 48))
            let fit = ReviewMath.fitSize(pixelWidth: item.asset.pixelWidth, pixelHeight: item.asset.pixelHeight, in: box)
            let scale = UIScreen.main.scale
            let px = CGSize(width: (fit.width * scale).rounded(.up), height: (fit.height * scale).rounded(.up))
            token = library.thumbnails.requestFitImage(
                asset: item.asset,
                maxSidePoints: max(fit.width, fit.height),
                fitPixels: px,
                priority: .veryHigh
            ) { img, _ in
                if let cur = image, img.size.width * img.scale < cur.size.width * cur.scale { return }
                image = img
            }
        }
        .onDisappear {
            token?.cancel()
            token = nil
        }
    }
}

/// 印样表自己的 4 秒撤回条。装袋描边中性，小样保留原色；取消收藏沿用琥珀。
private struct FilingUndoBar: View {
    let banner: FilingUndoBanner
    let locale: Locale
    let onUndo: () -> Void

    var body: some View {
        UndoCapsuleSurface(
            countdownKey: banner.id,
            sampleId: banner.sampleId,
            title: banner.title,
            subtitle: nil,
            amber: banner.amber,
            keepColor: true,
            shownAt: banner.shownAt,
            locale: locale,
            onUndo: onUndo
        )
    }
}
