import Photos
import SwiftUI
import UIKit

struct SheetRowFrameKey: PreferenceKey {
    static var defaultValue: [String: CGRect] = [:]
    static func reduce(value: inout [String: CGRect], nextValue: () -> [String: CGRect]) {
        value.merge(nextValue(), uniquingKeysWith: { $1 })
    }
}

/// 相册面板：暗房叠层，不是系统 sheet。
struct AlbumSheetOverlay: View {
    var albums: [FilingAlbumRef]
    var locale: Locale
    var pipeline: ThumbnailPipeline
    var parked: Bool
    var parkedCount: Int
    var parkedAssets: [PHAsset]
    var highlighted: String?
    var heldPick: HeldAlbumSnap? = nil
    var heldFlight: HeldAlbumSnap? = nil
    var asset: (String) -> PHAsset?
    var onPick: (FilingAlbumRef) -> Void
    var onCreate: (String) -> Void
    var onDismiss: () -> Void

    @State private var query = ""
    @State private var order: [String] = []
    @State private var pull: CGFloat = 0
    @State private var keyboard: CGFloat = 0
    @FocusState private var focused: Bool

    private var locked: [FilingAlbumRef] {
        let map = Dictionary(uniqueKeysWithValues: albums.map { ($0.id, $0) })
        let base = order.compactMap { map[$0] }
        return base.isEmpty ? albums : base
    }

    private var filtered: [FilingAlbumRef] { AlbumSearch.filter(locked, query: query) }
    private var draft: String? { AlbumSearch.createName(locked, query: query) }

    var body: some View {
        GeometryReader { geo in
            let height = min(geo.size.height * FilingMotion.sheetHeight, FilingMotion.sheetMaxHeight)
            let lift = min(keyboard, max(0, geo.size.height - height - geo.safeAreaInsets.top - FilingMotion.sheetTopClear))
            ZStack(alignment: .bottom) {
                DarkroomColors.room.opacity(Double(FilingMotion.sheetScrim))
                    .ignoresSafeArea()
                    .onTapGesture(perform: onDismiss)
                sheet(height: height)
                    .offset(y: pull - lift)
                    .gesture(drag)
            }
        }
        .onAppear { order = albums.map(\.id) }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillChangeFrameNotification)) { note in
            guard let frame = note.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect else { return }
            let screen = UIScreen.main.bounds.height
            keyboard = max(0, screen - frame.minY)
        }
    }

    private func sheet(height: CGFloat) -> some View {
        VStack(spacing: 0) {
            Capsule().fill(DarkroomColors.rim2).frame(width: 32, height: 4).padding(.top, 8)
            HStack(spacing: 10) {
                if parked {
                    parkedStack
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(FilingCopy.trayTitle(locale))
                        .font(DarkroomType.trayTitle)
                        .foregroundStyle(DarkroomColors.paper)
                        .lineLimit(1)
                    if parked {
                        Text(FilingCopy.parkedHint(locale))
                            .font(DarkroomType.dayMeta)
                            .foregroundStyle(DarkroomColors.dim)
                            .lineLimit(1)
                            .transition(.opacity)
                    }
                }
                Spacer()
                Text(query.isEmpty ? FilingCopy.albumTotal(albums.count, locale: locale) : FilingCopy.searchCount(filtered.count, locale: locale))
                    .font(DarkroomType.dayRight)
                    .foregroundStyle(DarkroomColors.dim)
            }
            .padding(.horizontal, 16)
            .padding(.top, 12)
            .frame(minHeight: 40)
            HStack(spacing: 8) {
                DarkroomIconView(icon: .search, size: 16).foregroundStyle(DarkroomColors.dim)
                TextField(FilingCopy.searchHint(locale), text: $query)
                    .font(DarkroomType.body)
                    .foregroundStyle(DarkroomColors.paper)
                    .focused($focused)
                if !query.isEmpty {
                    Button { query = "" } label: {
                        DarkroomIconView(icon: .close, size: 14).foregroundStyle(DarkroomColors.dim)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(FilingCopy.searchClear(locale))
                }
            }
            .padding(.horizontal, 14)
            .frame(height: 44)
            .background(DarkroomColors.tray2, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
            .padding(.horizontal, 16)
            .padding(.top, 10)
            ScrollView {
                LazyVStack(spacing: 0) {
                    if let draft {
                        rowButton(id: nil, title: FilingCopy.createRow(draft, locale: locale), count: nil, when: nil, assets: [], dashed: true) {
                            onCreate(draft)
                        }
                    }
                    ForEach(filtered, id: \.id) { album in
                        let current = HeldAlbumSnap(id: album.id, count: album.count, coverId: album.recentIds.first)
                        let shown = HeldEnvelope.shown(album: current, picked: heldPick, inFlight: heldFlight)
                        let coverAssets: [PHAsset] = {
                            var ids = album.recentIds
                            if let coverId = shown.coverId, ids.first != coverId {
                                ids = [coverId] + ids.filter { $0 != coverId }
                            }
                            return ids.prefix(3).compactMap { asset($0) }
                        }()
                        rowButton(
                            id: album.id,
                            title: album.title.isEmpty ? "—" : album.title,
                            count: shown.count,
                            when: filedWhen(album),
                            assets: coverAssets,
                            dashed: false,
                            highlighted: highlighted == album.id
                        ) { onPick(album) }
                    }
                }
                .padding(.top, 8)
            }
        }
        .frame(maxWidth: .infinity)
        .frame(height: height)
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

    private var parkedStack: some View {
        let items = Array(parkedAssets.prefix(2).enumerated().reversed())
        return ZStack(alignment: .topLeading) {
            ForEach(items, id: \.offset) { index, photoAsset in
                WhitePrint(asset: photoAsset, pipeline: pipeline, width: 26, height: 26, tilt: index == 0 ? -8 : 4)
                    .offset(x: CGFloat(4 + index * 4), y: 2)
            }
            Text("\(parkedCount)")
                .font(DarkroomType.envelopeCount)
                .foregroundStyle(DarkroomColors.room)
                .frame(width: parkedCount >= 10 ? 22 : 16, height: 16)
                .background(DarkroomColors.paper, in: Capsule())
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
        }
        .frame(width: 40, height: 32)
    }

    private var drag: some Gesture {
        DragGesture()
            .onChanged { pull = max(0, $0.translation.height) }
            .onEnded { value in
                if value.translation.height > FilingMotion.sheetDismissDrag { onDismiss() }
                pull = 0
            }
    }

    private func rowButton(id: String?, title: String, count: Int?, when: String?, assets: [PHAsset], dashed: Bool, highlighted: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 14) {
                PrintStack(assets: assets, pipeline: pipeline, opened: highlighted, dashedPlus: dashed, faded: dashed)
                    .background(GeometryReader { proxy in
                        if let id {
                            Color.clear.preference(key: SheetRowFrameKey.self, value: [id: proxy.frame(in: .global)])
                        } else {
                            Color.clear
                        }
                    })
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(DarkroomType.sheetRowName)
                        .foregroundStyle(DarkroomColors.paper)
                        .lineLimit(1)
                    if let count {
                        OdometerText(value: Int64(count), text: FilingCopy.albumCount(count, locale: locale), font: DarkroomType.dayRight, color: DarkroomColors.dim)
                    }
                }
                Spacer(minLength: 8)
                if let when {
                    Text(when)
                        .font(DarkroomType.dayRight)
                        .foregroundStyle(DarkroomColors.faint)
                }
            }
            .padding(.horizontal, 12)
            .frame(height: 64)
            .background(highlighted ? DarkroomColors.paper.opacity(0.07) : Color.clear, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay {
                if highlighted {
                    RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(DarkroomColors.rim2, lineWidth: 1)
                }
            }
        }
        .buttonStyle(.plain)
        .padding(.horizontal, 8)
    }

    private func filedWhen(_ album: FilingAlbumRef) -> String? {
        guard let kind = LastFiledRules.kind(filedAt: album.lastFiledAt, now: Date().timeIntervalSince1970, calendar: .current) else { return nil }
        switch kind {
        case .today: return FilingCopy.timeToday(locale)
        case .daysAgo(let n): return FilingCopy.timeDaysAgo(n, locale: locale)
        case .on(let date):
            return DateFormatCache.string(date, format: locale.identifier.hasPrefix("zh") ? "M月d日" : "MMM d", locale: locale)
        }
    }
}

/// 新袋子全屏。盖在相册面板上面。
struct NewAlbumOverlay: View {
    var count: Int
    var albums: [FilingAlbumRef]
    var photos: [PhotoItem]
    var pipeline: ThumbnailPipeline
    var locale: Locale
    var prefill: String
    var onCancel: () -> Void
    var onCommit: (String) -> Void

    @State private var name = ""
    @FocusState private var focused: Bool
    @State private var shown = false

    private var trimmed: String { AlbumNameRules.sanitize(name).trimmingCharacters(in: .whitespacesAndNewlines) }
    private var valid: Bool { AlbumNameRules.isValid(trimmed) }
    private var merge: Bool { AlbumNameRules.existing(name: trimmed, albums: albums) != nil }

    var body: some View {
        ZStack {
            DarkroomColors.room.opacity(0.96).ignoresSafeArea().onTapGesture { focused = false }
            VStack(spacing: 0) {
                HStack {
                    Button(action: onCancel) {
                        Text(FilingCopy.cancel(locale)).font(DarkroomType.body).foregroundStyle(DarkroomColors.paper)
                    }
                    .buttonStyle(.plain)
                    Spacer()
                    Text(FilingCopy.newAlbumTitle(count, locale: locale))
                        .font(DarkroomType.body)
                        .foregroundStyle(DarkroomColors.paper)
                    Spacer()
                    Color.clear.frame(width: 44, height: 20)
                }
                .padding(.horizontal, 20)
                .padding(.top, 12)
                Spacer()
                bigBag
                    .scaleEffect(shown ? 1 : 0.35, anchor: .bottom)
                    .offset(y: shown ? 0 : 320)
                    .rotationEffect(.degrees(shown ? -2 : 0))
                Spacer()
                Button { if valid { onCommit(trimmed) } } label: {
                    Text(FilingCopy.putIn(locale))
                        .font(Font.custom("NotoSansSC-SemiBold", size: 16))
                        .foregroundStyle(DarkroomColors.room)
                        .frame(maxWidth: .infinity)
                        .frame(height: 52)
                        .background(DarkroomColors.paper, in: Capsule())
                        .opacity(valid ? 1 : 0.35)
                }
                .buttonStyle(.plain)
                .disabled(!valid)
                .padding(.horizontal, 24)
                .padding(.bottom, 24)
            }
        }
        .onAppear {
            name = prefill
            withAnimation(SpringToken.settle.animation()) { shown = true }
        }
    }

    private var bigBag: some View {
        ZStack(alignment: .top) {
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .fill(DarkroomColors.kraftInside)
                .frame(width: 220, height: 240)
                .padding(.top, 40)
            HStack(spacing: -10) {
                ForEach(Array(photos.prefix(3).enumerated()), id: \.offset) { index, photo in
                    WhitePrint(asset: photo.asset, pipeline: pipeline, width: 64, height: 64, tilt: [-9.6, 4.8, 14.4][index])
                }
            }
            .padding(.top, 2)
            EnvelopeFrontShape(notch: 22, bottomCorner: 4)
                .fill(LinearGradient(colors: [DarkroomColors.kraftHigh, DarkroomColors.kraft], startPoint: .top, endPoint: .bottom))
                .frame(width: 220, height: 224)
                .padding(.top, 56)
                .overlay(alignment: .top) {
                    EnvelopeTopHighlightShape(notch: 22)
                        .stroke(DarkroomColors.paper.opacity(0.16), lineWidth: 1)
                        .frame(width: 220, height: 224)
                        .padding(.top, 56)
                }
            VStack(alignment: .leading, spacing: 6) {
                TextField(FilingCopy.albumPlaceholder(locale), text: $name)
                    .focused($focused)
                    .font(DarkroomType.newAlbumInput)
                    .foregroundStyle(DarkroomColors.labelInk)
                    .onChange(of: name) { _, new in
                        let clean = AlbumNameRules.sanitize(new)
                        if clean != new { name = clean }
                    }
                Rectangle().fill(DarkroomColors.labelInk.opacity(0.45)).frame(height: 1.5)
                Text(status)
                    .font(DarkroomType.newAlbumPath)
                    .foregroundStyle(DarkroomColors.labelInk.opacity(0.6))
                    .lineLimit(1)
            }
            .padding(14)
            .frame(width: 180, height: 96, alignment: .leading)
            .background(DarkroomColors.label, in: RoundedRectangle(cornerRadius: 3, style: .continuous))
            .padding(.top, 280 - 28 - 96)
        }
        .frame(width: 220, height: 280)
    }

    private var status: String {
        if !valid { return trimmed.isEmpty ? "" : trimmed }
        if merge { return "\(trimmed) · \(FilingCopy.mergeHint(locale))" }
        return FilingCopy.pathNew(trimmed, locale: locale)
    }
}
