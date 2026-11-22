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
            ToolIconButton(systemName: "chevron.left", action: onClose)
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
                    trayChip(title: FilingCopy.newSleeve(locale), subtitle: nil, systemImage: "plus") {
                        showNew = true
                    }
                    ForEach(model.tray, id: \.id) { album in
                        trayChip(title: album.title.isEmpty ? "—" : album.title, subtitle: FilingCopy.albumCount(album.count, locale: locale), systemImage: nil) {
                            Task { await model.fileExisting(id: album.id, fromMore: false, locale: locale) }
                        }
                    }
                    if model.moreCount > 0 {
                        trayChip(title: FilingCopy.more(model.moreCount, locale: locale), subtitle: nil, systemImage: nil) {
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

    private func trayChip(title: String, subtitle: String?, systemImage: String?, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 4) {
                if let systemImage {
                    Image(systemName: systemImage)
                        .font(.system(size: 14, weight: .medium))
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
    @State private var requestID: PHImageRequestID?

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
            let side = max(UIScreen.main.bounds.width, UIScreen.main.bounds.height) * UIScreen.main.scale
            requestID = library.requestFullImage(for: item.asset, maxSide: side) { img in
                Task { @MainActor in image = img }
            }
        }
        .onDisappear {
            if let requestID { library.cancelThumbnail(requestID) }
        }
    }
}

/// 印样表自己的 4 秒撤回条（样式对齐 UndoCapsule）。装袋是中性色；取消收藏沿用收藏的琥珀。
private struct FilingUndoBar: View {
    let banner: FilingUndoBanner
    let locale: Locale
    let onUndo: () -> Void
    @EnvironmentObject private var library: PhotoLibraryService
    @State private var thumb: UIImage?
    @State private var countdown: CGFloat = 1

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                Circle().fill(DarkroomColors.tray2)
                if let thumb {
                    Image(uiImage: thumb)
                        .resizable()
                        .scaledToFill()
                        .frame(width: 40, height: 40)
                        .saturation(banner.amber ? 1 : 0.4)
                        .brightness(banner.amber ? 0 : -0.3)
                }
            }
            .frame(width: 40, height: 40)
            .clipShape(Circle())
            .overlay(Circle().strokeBorder(banner.amber ? DarkroomColors.amber : DarkroomColors.paper.opacity(0.16), lineWidth: banner.amber ? 1.5 : 1))

            Text(banner.title)
                .font(Font.custom("NotoSansSC-Medium", size: 14))
                .foregroundStyle(DarkroomColors.paper)
                .lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)

            Button(action: onUndo) {
                HStack(spacing: 4) {
                    Image(systemName: "arrow.uturn.backward")
                        .font(.system(size: 13, weight: .semibold))
                    Text(L10n.string("undo_action", locale: locale))
                        .font(Font.custom("NotoSansSC-SemiBold", size: 13))
                }
                .foregroundStyle(DarkroomColors.room)
                .padding(.horizontal, 16)
                .frame(height: 40)
                .background(DarkroomColors.paper, in: Capsule())
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 8)
        .frame(minHeight: 56)
        .frame(maxWidth: .infinity)
        .background {
            Capsule().fill(UndoCapsule.glass).background(.ultraThinMaterial, in: Capsule())
        }
        .clipShape(Capsule())
        .overlay(Capsule().strokeBorder(DarkroomColors.menuRim, lineWidth: 1))
        .overlay(alignment: .bottom) {
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(banner.amber ? DarkroomColors.amber.opacity(0.25) : DarkroomColors.paper.opacity(0.12))
                    Rectangle()
                        .fill(banner.amber ? DarkroomColors.amber : DarkroomColors.fade)
                        .frame(width: geo.size.width)
                        .scaleEffect(x: countdown, y: 1, anchor: .leading)
                }
            }
            .frame(height: 2)
            .padding(.horizontal, 28)
            .padding(.bottom, 6)
            .allowsHitTesting(false)
        }
        .shadow(color: .black.opacity(0.5), radius: 20, y: 16)
        .task(id: banner.sampleId) { loadThumb() }
        .onAppear { startCountdown() }
        .onChange(of: banner.id) { _, _ in startCountdown() }
    }

    private func startCountdown() {
        let remaining = max(0, UndoBanner.lifetime - Date().timeIntervalSince(banner.shownAt))
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) { countdown = CGFloat(remaining / UndoBanner.lifetime) }
        guard remaining > 0 else { return }
        DispatchQueue.main.async {
            withAnimation(.linear(duration: remaining)) { countdown = 0 }
        }
    }

    private func loadThumb() {
        guard let id = banner.sampleId, let asset = library.item(id: id)?.asset else { return }
        _ = library.requestThumbnail(for: asset, side: 40) { img in
            Task { @MainActor in thumb = img }
        }
    }
}
