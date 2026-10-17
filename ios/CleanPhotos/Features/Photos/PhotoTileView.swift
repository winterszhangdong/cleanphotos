import SwiftUI
import Photos

struct PhotoTileView: View {
    let item: PhotoItem
    let side: CGFloat
    var develop: Bool
    var developDelay: Double
    var showUnseen: Bool = true
    var selecting: Bool = false
    var selected: Bool = false

    @EnvironmentObject private var library: PhotoLibraryService
    @State private var image: UIImage?
    @State private var requestID: PHImageRequestID?

    private static let pickCurve = Animation.timingCurve(0.2, 0, 0, 1, duration: 0.16)

    var body: some View {
        ZStack(alignment: .topLeading) {
            // 选中时缩小露出的是暗房底（.grid4.sel .tile 背景 room）
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
            .clipShape(RoundedRectangle(cornerRadius: selected ? 6 : 0, style: .continuous))
            .scaleEffect(selected ? 0.86 : 1)
            .animation(Self.pickCurve, value: selected)

            // 未看光点：多选中照常显示（ui-design 3.1a）
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
        // 读屏：整格一个按钮，读拍摄时间（Android 读文件名；这里不为每格查 PHAssetResource）+「未看过」；多选时带「已选中」
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(a11yLabel))
        .accessibilityAddTraits(selecting && selected ? [.isButton, .isSelected] : .isButton)
        .modifier(SharedTileHide(id: item.id))
        // 共享元素起点 / 终点：登记全局 frame（不触发刷新）
        .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { f in
            PhotoOpenTransition.shared.register(item.id, frame: f)
        }
        .onAppear { load() }
        .onDisappear { cancel(); PhotoOpenTransition.shared.unregister(item.id) }
    }

    @Environment(\.locale) private var locale
    private var a11yLabel: String {
        let base = Self.formatter(locale).string(from: item.creationDate)
        return showUnseen && item.isUnseen ? base + ", " + L10n.string("a11y_unseen", locale: locale) : base
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
