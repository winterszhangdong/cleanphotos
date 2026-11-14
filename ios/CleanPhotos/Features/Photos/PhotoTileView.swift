import SwiftUI
import Photos

struct PhotoTileView: View {
    let item: PhotoItem
    let side: CGFloat
    var develop: Bool
    var developDelay: Double
    var showUnseen: Bool = false
    var selecting: Bool = false
    var selected: Bool = false
    /// 大文件格子左下角的大小。nil 不写大小。RAW 另查文件名，和大小合成一行。
    var sizeText: String? = nil

    @EnvironmentObject private var library: PhotoLibraryService
    @State private var image: UIImage?
    @State private var requestID: PHImageRequestID?
    @State private var raw = false

    private static let pickCurve = Animation.timingCurve(0.2, 0, 0, 1, duration: 0.16)
    /// 有角标时底部 28pt 暗角，字才看得清。
    private static let scrimHeight: CGFloat = 28

    var body: some View {
        ZStack(alignment: .topLeading) {
            (selecting ? DarkroomColors.room : DarkroomColors.tileFill)
            ZStack {
                DarkroomColors.tileFill
                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .accessibilityHidden(true)
                        .scaledToFill()
                        .frame(width: side, height: side)
                        .clipped()
                        .darkroomDevelop(enabled: develop && !selecting, delay: developDelay)
                }
            }
            .frame(width: side, height: side)
            .overlay(alignment: .bottom) {
                if markLine != nil || durationText != nil {
                    LinearGradient(colors: [.clear, .black.opacity(0.55)], startPoint: .top, endPoint: .bottom)
                        .frame(height: Self.scrimHeight)
                        .allowsHitTesting(false)
                }
            }
            .overlay(alignment: .bottomLeading) {
                if let markLine {
                    Text(markLine)
                        .font(DarkroomType.tileBadge)
                        .foregroundStyle(DarkroomColors.paper)
                        .lineLimit(1)
                        .padding(DarkroomDimens.unseenInset)
                }
            }
            .overlay(alignment: .bottomTrailing) {
                if let durationText {
                    Text(durationText)
                        .font(DarkroomType.tileBadge)
                        .foregroundStyle(DarkroomColors.paper)
                        .padding(DarkroomDimens.unseenInset)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: selected ? 6 : 0, style: .continuous))
            .scaleEffect(selected ? 0.86 : 1)
            .animation(Self.pickCurve, value: selected)

            if showUnseen && item.isUnseen {
                UnseenDot()
                    .padding(DarkroomDimens.unseenInset)
            }
        }
        .overlay(alignment: .topTrailing) {
            if selecting && selected {
                SelectionCheck()
                    .padding(8)
                    .transition(.selectPop)
            }
        }
        .animation(Self.pickCurve, value: selected)
        .frame(width: side, height: side)
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(a11yLabel))
        .accessibilityAddTraits(selecting && selected ? [.isButton, .isSelected] : .isButton)
        .modifier(SharedTileHide(id: item.id))
        .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { f in
            PhotoOpenTransition.shared.register(item.id, frame: f)
        }
        .onAppear { load(); resolveRaw() }
        .onDisappear { cancel(); PhotoOpenTransition.shared.unregister(item.id) }
    }

    private var markLine: String? {
        ScopeRules.photoMarkLine(raw: raw ? "RAW" : nil, size: sizeText)
    }

    private var durationText: String? {
        guard item.isVideo else { return nil }
        return ScopeRules.formatDuration(seconds: item.duration)
    }

    @Environment(\.locale) private var locale
    private var a11yLabel: String {
        var base = Self.formatter(locale).string(from: item.creationDate)
        if showUnseen && item.isUnseen { base += ", " + L10n.string("a11y_unseen", locale: locale) }
        if let durationText { base += ", " + durationText }
        return base
    }
    private static var formatters: [String: DateFormatter] = [:]
    private static func formatter(_ locale: Locale) -> DateFormatter {
        if let f = formatters[locale.identifier] { return f }
        let f = DateFormatter()
        f.locale = locale
        f.setLocalizedDateFormatFromTemplate("MMMdjmm")
        formatters[locale.identifier] = f
        return f
    }

    /// 缓存里没有原始文件名时才查 PHAssetResource。不在首次读库时对全库做这件事。
    private func resolveRaw() {
        if let name = AssetSizeCache.shared.cachedOriginalFilename(item.id) {
            raw = ScopeRules.isRawFileName(name)
            return
        }
        let asset = item.asset
        Task.detached(priority: .utility) {
            let info = AssetSizeCache.shared.info(for: asset)
            let isRaw = ScopeRules.isRawFileName(info.originalFilename)
            await MainActor.run { raw = isRaw }
        }
    }

    private func load() {
        guard image == nil else { PhotoOpenTransition.shared.tileImages[item.id] = image; return }
        requestID = library.requestThumbnail(for: item.asset, side: side) { img in
            Task { @MainActor in
                if let img { PerfLog.once("firstThumb"); self.image = img; PhotoOpenTransition.shared.tileImages[item.id] = img }
            }
        }
    }

    private func cancel() {
        if let requestID { library.cancelThumbnail(requestID) }
    }
}

/// 共享元素飞行期间，起点 / 终点瓦片让出位置（只露出暗房底）。
private struct SharedTileHide: ViewModifier {
    let id: String
    @ObservedObject private var shared = PhotoOpenTransition.shared
    func body(content: Content) -> some View {
        content.opacity(shared.flyingId == id ? 0 : 1)
    }
}
