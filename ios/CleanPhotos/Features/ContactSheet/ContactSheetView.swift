import Photos
import SwiftUI
import UIKit

/// 某一天的印样表。胶片在 UIKit 网格里，托盘、面板、飞入叠在上面。
struct ContactSheetScreen: View {
    @EnvironmentObject private var library: PhotoLibraryService
    @EnvironmentObject private var language: LanguageStore
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @StateObject private var model: ContactSheetModel
    @StateObject private var stage = FilingStageController()
    @State private var bridge = SheetBridge()
    @State private var strip = EnvelopeStripController()
    @State private var showSheet = false
    @State private var showNew = false
    @State private var newPrefill = ""
    @State private var parked = false
    @State private var peekPhoto: PhotoItem?
    @State private var peekAlbum: FilingAlbumRef?
    @State private var highlighted: String?
    @State private var trayUp = false
    @State private var rolling = false
    var onClose: () -> Void

    init(dayId: String, filingLog: FilingLog, onClose: @escaping () -> Void) {
        self.onClose = onClose
        _model = StateObject(wrappedValue: ContactSheetModel(dayId: dayId, filingLog: filingLog))
    }

    private var locale: Locale { language.effectiveLocale }
    private var dayDate: Date? { NextDayRule.date(from: model.dayId) }

    private var trayRaised: Bool {
        model.summary == nil && (
            !model.circled.isEmpty || model.busy || stage.dragging || showSheet || showNew || peekAlbum != nil || model.flightEvent != nil
        )
    }

    var body: some View {
        ZStack {
            DarkroomColors.room.ignoresSafeArea()
            if model.summary == nil {
                film
            }
            if let summary = model.summary {
                summaryPage(summary)
                    .opacity(rolling ? 0 : 1)
            }
            if rolling { rollStrip }
            if peekPhoto != nil { photoPeek }
            if peekAlbum != nil { albumPeek }
        }
        .safeAreaInset(edge: .top, spacing: 0) {
            if model.summary == nil { header }
        }
        .overlay(alignment: .bottom) { bottomStack }
        .overlay {
            if let fanId = peekAlbum?.id ?? stage.fanKey,
               let rect = stage.frames[fanId],
               let album = model.tray.first(where: { $0.id == fanId }) ?? model.allAlbums.first(where: { $0.id == fanId }) {
                let peeking = peekAlbum?.id == fanId
                let assets = Array(model.recentAssets(for: album.id).prefix(peeking ? 5 : 3))
                if !assets.isEmpty {
                    EnvelopeFanOverlay(assets: assets, envelopeRect: rect, peeking: peeking, pipeline: library.thumbnails)
                }
            }
        }
        .overlay {
            FilingStageRepresentable(controller: stage)
                .ignoresSafeArea()
                .allowsHitTesting(false)
        }
        .overlay {
            if showSheet {
                albumSheet
                    .ignoresSafeArea()
                    .onPreferenceChange(SheetRowFrameKey.self) { stage.sheetRowFrames = $0 }
            }
            if showNew { newAlbum }
        }
        .environment(\.locale, locale)
        .preferredColorScheme(.dark)
        .reduceMotionGate()
        .onAppear {
            model.bind(library)
            wireStage()
            if AuditHooks.enabled { AuditHooks.mark("sheet-ready") }
        }
        .task { await model.loadAlbums() }
        .onChange(of: library.favoriteSections) { _, _ in model.libraryDidChange() }
        .onChange(of: trayRaised) { _, up in
            withAnimation(SpringToken.settle.animation()) { trayUp = up }
            if !up {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) {
                    if !trayRaised { strip.scrollToStart(animated: !reduceMotion) }
                }
            }
        }
        .onChange(of: model.flightEvent?.id) { _, _ in
            guard let event = model.flightEvent else { return }
            let photos = event.photoIds.compactMap { id in model.photos.first { $0.id == id } }
            highlighted = event.intoSheet ? event.albumId : nil
            stage.startFlight(event, photos: photos)
        }
        .onChange(of: model.bounceEvent?.id) { _, _ in
            highlighted = nil
            stage.putBack()
        }
        .onChange(of: model.summary != nil) { _, shown in
            guard shown else {
                rolling = false
                return
            }
            if reduceMotion || UIAccessibility.isReduceMotionEnabled {
                rolling = false
            } else {
                rolling = true
                DispatchQueue.main.asyncAfter(deadline: .now() + DarkroomMotion.long) { rolling = false }
            }
            if AuditHooks.enabled { AuditHooks.mark("summary") }
        }
        .onPreferenceChange(EnvelopeFrameKey.self) { frames in
            stage.frames = frames
        }
        .onReceive(NotificationCenter.default.publisher(for: .cleanphotosContactSheetAudit)) { note in
            guard AuditHooks.enabled else { return }
            handleAudit(note.object as? String)
        }
        .task(id: model.notice?.id) {
            guard let id = model.notice?.id else { return }
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            model.dismissNotice(id)
        }
    }

    private func wireStage() {
        stage.bridge = bridge
        stage.strip = strip
        stage.pipeline = library.thumbnails
        stage.titleFor = { key in
            if key == EnvelopeKey.newSleeve { return FilingCopy.newSleeve(locale) }
            if key == EnvelopeKey.more { return FilingCopy.more(model.moreCount, locale: locale) }
            return model.tray.first { $0.id == key }?.title ?? model.allAlbums.first { $0.id == key }?.title ?? ""
        }
        stage.fileAlbum = { id, fromMore, fromDrag in
            Task { await model.fileExisting(id: id, fromMore: fromMore, fromDrag: fromDrag, intoSheet: false, locale: locale) }
        }
        stage.openNew = { _ in
            newPrefill = ""
            showNew = true
        }
        stage.openMore = { _ in
            parked = true
            showSheet = true
        }
        stage.released = { model.markFlightReleased() }
        stage.finished = { event in
            highlighted = nil
            if event.intoSheet || event.created { showSheet = false }
            Task { await model.finishFlight(albumId: event.albumId, fromMore: event.fromMore || event.created) }
        }
    }

    private var header: some View {
        HStack(alignment: .bottom, spacing: 10) {
            ToolIconButton(icon: .back, glass: true, diameter: 40, action: onClose)
                .accessibilityLabel(L10n.string("cd_back", locale: locale))
                .disabled(model.busy)
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(dayNumber)
                        .font(DarkroomType.countBig)
                        .foregroundStyle(DarkroomColors.paper)
                    Text(dayMeta)
                        .font(DarkroomType.countUnit)
                        .foregroundStyle(DarkroomColors.dim)
                }
                Text(model.circled.isEmpty
                     ? FilingCopy.pending(model.photos.count, locale: locale)
                     : FilingCopy.circled(model.circled.count, locale: locale))
                    .font(DarkroomType.countSub)
                    .foregroundStyle(DarkroomColors.dim)
            }
            Spacer(minLength: 4)
            if !model.photos.isEmpty && model.summary == nil {
                let all = model.circled.count == model.photos.count && !model.photos.isEmpty
                TrayTextButton(title: all ? FilingCopy.clear(locale) : FilingCopy.circleAll(locale), enabled: !model.busy) {
                    if all { model.clearCircles() } else { model.circleAll() }
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 12)
        .frame(maxWidth: .infinity)
        .background(DarkroomColors.room)
    }

    @ViewBuilder
    private var film: some View {
        if model.photos.isEmpty {
            Text(FilingCopy.emptyDay(locale))
                .font(DarkroomType.body)
                .foregroundStyle(DarkroomColors.dim)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            ContactSheetGridView(
                photos: model.photos,
                circled: model.circled,
                busy: model.busy,
                edgeDate: dayDate,
                pipeline: library.thumbnails,
                bottomInset: trayUp ? 260 : 48,
                bridge: bridge,
                onToggle: { model.toggle($0) },
                onCircle: { model.circle($0) },
                onPeek: { peekPhoto = $0 },
                onPickUp: { finger in
                    let homes = bridge.grid?.visibleCircledFrames() ?? [:]
                    let visible = model.photos.filter { homes[$0.id] != nil }
                    stage.pickUp(photos: visible, finger: finger, homes: homes, total: model.circled.count)
                },
                onDrag: { stage.drag($0) },
                onDrop: { stage.drop($0) }
            )
        }
    }

    private var bottomStack: some View {
        VStack(spacing: 8) {
            if let undo = model.undo {
                FilingUndoBar(banner: undo, locale: locale) {
                    Task { await model.undo(locale: locale) }
                }
                .padding(.horizontal, 16)
            }
            if let notice = model.notice { DarkToast(text: notice.text) }
            if model.summary == nil && model.circled.isEmpty && model.undo == nil && !model.photos.isEmpty && !trayUp {
                Text(FilingCopy.sheetHint(locale))
                    .font(DarkroomType.eyebrow)
                    .foregroundStyle(DarkroomColors.dim)
                    .padding(.bottom, 8)
            }
            if trayUp || !model.circled.isEmpty || model.busy {
                tray
                    .offset(y: trayUp ? 0 : 320)
                    .allowsHitTesting(trayUp)
            }
        }
        .frame(maxWidth: .infinity)
    }

    private var tray: some View {
        VStack(spacing: 0) {
            Capsule().fill(DarkroomColors.rim2).frame(width: 32, height: 4).padding(.top, 8)
            HStack {
                Text(FilingCopy.trayTitle(locale))
                    .font(DarkroomType.trayTitle)
                    .foregroundStyle(DarkroomColors.paper)
                Spacer()
                SystemFavoriteSwitch(on: $model.systemFavorite, title: FilingCopy.systemFavorite(locale))
                    .disabled(model.busy)
            }
            .padding(.horizontal, 16)
            .padding(.top, 10)
            .frame(height: 32)
            .zIndex(1)
            EnvelopeStrip(controller: strip, content: trayRow.onPreferenceChange(EnvelopeFrameKey.self) { stage.frames = $0 })
                .frame(height: 120)
                .padding(.top, 8)
                .background(GeometryReader { proxy in
                    Color.clear.preference(key: RowFrameKey.self, value: proxy.frame(in: .global))
                })
                .onPreferenceChange(RowFrameKey.self) { stage.rowFrame = $0 }
            HStack {
                Button {
                    model.unfavoriteCircled(locale: locale)
                } label: {
                    HStack(spacing: 6) {
                        DarkroomIconView(icon: .star, size: 16)
                        Text(L10n.string("action_unfavorite", locale: locale))
                            .font(Font.custom("NotoSansSC-Medium", size: 13))
                    }
                    .foregroundStyle(DarkroomColors.paper)
                }
                .buttonStyle(.plain)
                .disabled(model.busy)
                Spacer()
                Text(FilingCopy.sleeveHint(locale))
                    .font(Font.custom("NotoSansSC-Regular", size: 12))
                    .foregroundStyle(DarkroomColors.faint)
            }
            .padding(.horizontal, 16)
            .padding(.top, 6)
            Spacer(minLength: 0)
        }
        .frame(height: 248)
        .padding(.bottom, 0)
        .frame(maxWidth: .infinity)
        .background {
            UnevenRoundedRectangle(topLeadingRadius: 24, topTrailingRadius: 24)
                .fill(DarkroomColors.tray)
                .ignoresSafeArea(edges: .bottom)
        }
        .overlay {
            UnevenRoundedRectangle(topLeadingRadius: 24, topTrailingRadius: 24)
                .strokeBorder(DarkroomColors.rim2, lineWidth: 1)
                .ignoresSafeArea(edges: .bottom)
        }
    }

    private var trayRow: some View {
        HStack(alignment: .bottom, spacing: 12) {
            NewSleeveCard(title: FilingCopy.newSleeve(locale), leaning: stage.hoverKey == EnvelopeKey.newSleeve, dimmed: dimmed(EnvelopeKey.newSleeve)) {
                guard !model.busy else { return }
                newPrefill = ""
                showNew = true
            }
            ForEach(model.tray, id: \.id) { album in
                let shown = shown(album)
                KraftEnvelope(
                    title: album.title,
                    count: shown.count,
                    cover: model.asset(id: shown.coverId ?? ""),
                    coverId: shown.coverId,
                    pipeline: library.thumbnails,
                    leaning: stage.hoverKey == album.id || peekAlbum?.id == album.id,
                    peeking: peekAlbum?.id == album.id,
                    dimmed: dimmed(album.id),
                    dwellProgress: stage.hoverKey == album.id ? stage.dwellProgress : 0,
                    flapClosed: stage.closedFlapId == album.id,
                    bump: stage.bumpId == album.id,
                    locale: locale,
                    onTap: {
                        guard !model.busy else { return }
                        Task { await model.fileExisting(id: album.id, fromMore: false, fromDrag: false, intoSheet: false, locale: locale) }
                    },
                    onPeek: { pressing in peekAlbum = pressing ? album : nil },
                    frameKey: album.id
                )
            }
            if model.moreCount > 0 {
                MoreSleeveCard(
                    title: FilingCopy.more(model.moreCount, locale: locale),
                    search: FilingCopy.searchWord(locale),
                    leaning: stage.hoverKey == EnvelopeKey.more,
                    dimmed: dimmed(EnvelopeKey.more)
                ) {
                    parked = false
                    showSheet = true
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }

    private func dimmed(_ key: String) -> Bool {
        guard let focus = stage.hoverKey ?? peekAlbum?.id else { return false }
        return focus != key
    }

    private func shown(_ album: FilingAlbumRef) -> HeldAlbumSnap {
        let current = HeldAlbumSnap(id: album.id, count: album.count, coverId: album.recentIds.first)
        return HeldEnvelope.shown(album: current, picked: model.heldPick, inFlight: model.heldFlight)
    }

    private var albumSheet: some View {
        AlbumSheetOverlay(
            albums: model.allAlbums,
            locale: locale,
            pipeline: library.thumbnails,
            parked: parked,
            parkedCount: model.circled.count,
            parkedAssets: model.photos.filter { model.circled.contains($0.id) }.map(\.asset),
            highlighted: highlighted,
            heldPick: model.heldPick,
            heldFlight: model.heldFlight,
            asset: { model.asset(id: $0) },
            onPick: { album in
                highlighted = album.id
                Task { await model.fileExisting(id: album.id, fromMore: true, fromDrag: stage.dragging, intoSheet: true, locale: locale) }
            },
            onCreate: { name in
                newPrefill = name
                showNew = true
            },
            onDismiss: {
                showSheet = false
                parked = false
                if stage.dragging { stage.putBack() }
            }
        )
        .transition(.move(edge: .bottom))
        .zIndex(2)
    }

    private var newAlbum: some View {
        NewAlbumOverlay(
            count: max(model.circled.count, 1),
            albums: model.allAlbums,
            photos: model.photos.filter { model.circled.contains($0.id) },
            pipeline: library.thumbnails,
            locale: locale,
            prefill: newPrefill,
            onCancel: {
                showNew = false
                if stage.dragging && !showSheet { stage.putBack() }
            },
            onCommit: { name in
                showNew = false
                if showSheet { strip.scrollToStart(animated: !reduceMotion) }
                Task { await model.fileNew(name: name, fromDrag: stage.dragging, locale: locale) }
            }
        )
        .zIndex(3)
    }

    private var photoPeek: some View {
        ZStack {
            Color.black.opacity(0.92).ignoresSafeArea()
            if let peekPhoto {
                PipelineImage(asset: peekPhoto.asset, side: 360, pipeline: library.thumbnails)
                    .aspectRatio(contentMode: .fit)
                    .padding(24)
            }
        }
        .allowsHitTesting(false)
    }

    private var albumPeek: some View {
        ZStack {
            DarkroomColors.dialogScrim.ignoresSafeArea()
            if let album = peekAlbum {
                VStack(spacing: 12) {
                    Text(album.title.isEmpty ? "—" : album.title)
                        .font(DarkroomType.peekName)
                        .foregroundStyle(DarkroomColors.paper)
                    Text(FilingCopy.albumCount(album.count, locale: locale))
                        .font(DarkroomType.dayRight)
                        .foregroundStyle(DarkroomColors.dim)
                }
                .padding(.bottom, 280)
            }
        }
        .allowsHitTesting(false)
    }

    private var rollStrip: some View {
        RoundedRectangle(cornerRadius: 2)
            .fill(DarkroomColors.film)
            .frame(height: 88)
            .padding(.horizontal, 16)
            .rotation3DEffect(.degrees(rolling ? FilingMotion.rollAngle : 0), axis: (x: 0, y: 1, z: 0), perspective: FilingMotion.perspective)
            .scaleEffect(x: rolling ? FilingMotion.rollScaleX : 1, y: 1)
            .animation(.timingCurve(0.3, 0, 0.8, 0.15, duration: DarkroomMotion.long), value: rolling)
    }

    private func summaryPage(_ summary: FilingSummary) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(dayMetaLine)
                .font(DarkroomType.eyebrow)
                .tracking(1.4)
                .foregroundStyle(DarkroomColors.dim)
            Text(FilingCopy.summaryTitle(locale))
                .font(Font.custom("NotoSansSC-SemiBold", size: 28))
                .foregroundStyle(DarkroomColors.paper)
                .padding(.top, 8)
            VStack(spacing: 10) {
                ForEach(summary.lines) { line in
                    HStack(spacing: 12) {
                        MiniEnvelope(asset: line.coverId.flatMap { model.asset(id: $0) }, pipeline: library.thumbnails)
                        Text(line.name.isEmpty ? "—" : line.name)
                            .font(Font.custom("NotoSansSC-Medium", size: 15))
                            .foregroundStyle(DarkroomColors.paper)
                            .lineLimit(1)
                        Spacer(minLength: 8)
                        Text("+\(line.added)")
                            .font(DarkroomFonts.plexMono(size: 13, weight: .medium))
                            .foregroundStyle(DarkroomColors.paper)
                        Text(line.created ? FilingCopy.summaryNew(locale) : FilingCopy.summaryTotal(line.total, locale: locale))
                            .font(DarkroomType.dayMeta)
                            .foregroundStyle(DarkroomColors.dim)
                    }
                    .padding(.horizontal, 12)
                    .frame(height: 56)
                    .background(DarkroomColors.tray, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(DarkroomColors.rim, lineWidth: 1))
                }
                if summary.unfavorited > 0 {
                    HStack(spacing: 12) {
                        DarkroomIconView(icon: .star, size: 16).foregroundStyle(DarkroomColors.dim).frame(width: 28)
                        Text(L10n.string("action_unfavorite", locale: locale))
                            .font(Font.custom("NotoSansSC-Medium", size: 15))
                            .foregroundStyle(DarkroomColors.dim)
                        Spacer()
                        Text("\(summary.unfavorited)")
                            .font(DarkroomFonts.plexMono(size: 13, weight: .medium))
                            .foregroundStyle(DarkroomColors.paper)
                        Text(FilingCopy.summaryUnfavoriteRest(locale))
                            .font(DarkroomType.dayMeta)
                            .foregroundStyle(DarkroomColors.dim)
                    }
                    .padding(.horizontal, 12)
                    .frame(height: 56)
                    .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(DarkroomColors.rim, lineWidth: 1))
                }
            }
            .padding(.top, 32)
            Spacer(minLength: 12)
            if let next = summary.next {
                Button { model.openDay(next.dayKey) } label: {
                    HStack(spacing: 12) {
                        PipelineImage(asset: model.asset(id: next.coverId) ?? library.item(id: next.coverId)?.asset, side: 48, pipeline: library.thumbnails)
                            .frame(width: 48, height: 48)
                            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                        VStack(alignment: .leading, spacing: 2) {
                            Text(FilingCopy.nextDay(shortLabel(next.dayKey), locale: locale))
                                .font(Font.custom("NotoSansSC-SemiBold", size: 15))
                                .foregroundStyle(DarkroomColors.room)
                            Text(FilingCopy.pending(next.count, locale: locale))
                                .font(DarkroomType.dayMeta)
                                .foregroundStyle(DarkroomColors.fade)
                        }
                        Spacer()
                        DarkroomIconView(icon: .arrow, size: 20).foregroundStyle(DarkroomColors.room)
                    }
                    .padding(.leading, 8)
                    .padding(.trailing, 16)
                    .frame(height: 64)
                    .background(DarkroomColors.paper, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                }
                .buttonStyle(.plain)
            }
            Button(action: onClose) {
                Text(FilingCopy.backToFavorites(locale))
                    .font(Font.custom("NotoSansSC-Medium", size: 14))
                    .foregroundStyle(DarkroomColors.dim)
                    .frame(maxWidth: .infinity)
                    .frame(height: 44)
            }
            .buttonStyle(.plain)
            .padding(.top, 8)
        }
        .padding(.horizontal, 24)
        .padding(.top, 56)
        .padding(.bottom, model.undo == nil ? 16 : 88)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }

    private var dayNumber: String {
        guard let dayDate else { return L10n.string("date_unknown", locale: locale) }
        return DateFormatCache.string(dayDate, format: "d", locale: locale)
    }

    private var dayMeta: String {
        guard let dayDate else { return "" }
        return DateFormatCache.string(dayDate, format: language.isChineseUI ? "M月 · EEE" : "MMM · EEE", locale: locale)
    }

    private var dayMetaLine: String {
        guard let dayDate else { return L10n.string("date_unknown", locale: locale) }
        return DateFormatCache.string(dayDate, format: language.isChineseUI ? "M月d日 · EEE" : "MMM d · EEE", locale: locale)
    }

    private func shortLabel(_ dayKey: String) -> String {
        guard let date = NextDayRule.date(from: dayKey) else { return dayKey }
        return DateFormatCache.string(date, format: language.isChineseUI ? "M月d日" : "MMM d", locale: locale)
    }

    private func handleAudit(_ command: String?) {
        switch command {
        case "circle":
            let ids = model.photos.prefix(3).map(\.id)
            for id in ids { model.circle(id) }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { AuditHooks.mark("circled") }
        case "drag":
            let ids = Array(model.photos.prefix(3).map(\.id))
            for id in ids where !model.circled.contains(id) { model.circle(id) }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) {
                let homes = bridge.grid?.visibleCircledFrames() ?? [:]
                let visible = model.photos.filter { homes[$0.id] != nil }
                let key = model.tray.first?.id ?? EnvelopeKey.newSleeve
                let finger = stage.frames[key].map { CGPoint(x: $0.midX, y: $0.minY + 36) } ?? CGPoint(x: 180, y: 640)
                stage.auditHover(photos: visible, finger: finger, homes: homes, key: key)
                AuditHooks.mark("drag")
            }
        case "undrag":
            stage.putBack()
            AuditHooks.mark("undrag")
        case "sheet":
            parked = !model.circled.isEmpty
            showSheet = true
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { AuditHooks.mark("sheet") }
        case "new":
            newPrefill = "海边"
            showNew = true
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.55) { AuditHooks.mark("new") }
        case "dismiss":
            showNew = false
            showSheet = false
            stage.putBack()
            AuditHooks.mark("dismissed")
        case "file-all":
            model.circleAll()
            let target = model.tray.first?.id ?? model.allAlbums.first?.id
            guard let target else { AuditHooks.mark("summary"); return }
            Task {
                await model.fileExisting(id: target, fromMore: false, fromDrag: false, intoSheet: false, locale: locale)
            }
        default:
            break
        }
    }
}

private struct RowFrameKey: PreferenceKey {
    static var defaultValue: CGRect = .zero
    static func reduce(value: inout CGRect, nextValue: () -> CGRect) {
        let next = nextValue()
        if next != .zero { value = next }
    }
}

struct FilingStageRepresentable: UIViewRepresentable {
    var controller: FilingStageController

    func makeUIView(context: Context) -> FilingStageView {
        let view = FilingStageView()
        bind(view)
        return view
    }

    func updateUIView(_ uiView: FilingStageView, context: Context) { bind(uiView) }

    private func bind(_ view: FilingStageView) {
        controller.view = view
        view.nudge = { [weak controller] dx in controller?.strip?.nudge(dx) ?? true }
        view.rowFrame = { [weak controller] in controller?.rowFrame ?? .zero }
        view.envelopeFrames = { [weak controller] in controller?.frames ?? [:] }
        view.hoverKey = { [weak controller] in controller?.hoverKey }
        view.titleFor = { [weak controller] in controller?.titleFor($0) ?? $0 }
        view.tickHover = { [weak controller] in controller?.tickHoverDwell() }
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
