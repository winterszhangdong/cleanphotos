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
    /// 列表正在甩动时先不查 PHAssetResource，停稳后再判 RAW。
    var pauseRaw: Bool = false

    @EnvironmentObject private var library: PhotoLibraryService
    @State private var image: UIImage?
    @State private var requestID: PHImageRequestID?
    @State private var raw = false
    @State private var rawTask: Task<Void, Never>?

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
        .modifier(SharedTileHide(flag: PhotoOpenTransition.shared.hideFlag(for: item.id)))
        .background { TileFrameSink(id: item.id) }
        .onAppear { load(); if !pauseRaw { resolveRaw() } }
        .onChange(of: pauseRaw) { _, pause in if !pause { resolveRaw() } }
        .onDisappear {
            cancel()
            rawTask?.cancel()
            PhotoOpenTransition.shared.unregister(item.id)
        }
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

    /// 缓存里没有原始文件名时才查 PHAssetResource。列表还在甩时先不查，停稳后只查当时还在的格子。
    private func resolveRaw() {
        if let name = AssetSizeCache.shared.cachedOriginalFilename(item.id) {
            raw = ScopeRules.isRawFileName(name)
            return
        }
        let asset = item.asset
        rawTask?.cancel()
        rawTask = Task { @MainActor in
            if ListScrollGate.shared.isMoving {
                await ListScrollGate.shared.waitUntilIdle()
            }
            guard !Task.isCancelled else { return }
            let info = await Task.detached(priority: .utility) {
                AssetSizeCache.shared.info(for: asset)
            }.value
            guard !Task.isCancelled else { return }
            raw = ScopeRules.isRawFileName(info.originalFilename)
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
/// 只观察这一格自己的 flag，flyingId 变化不会刷其它格子。
private struct SharedTileHide: ViewModifier {
    @ObservedObject var flag: TileHideFlag
    func body(content: Content) -> some View {
        content.opacity(flag.hidden ? 0 : 1)
    }
}

/// 列表还在拖或惯性滑动时，新出现的格子先不查 PHAssetResource。
/// 状态在引用类型里，滚动帧不写 SwiftUI。
@MainActor
final class ListScrollGate {
    static let shared = ListScrollGate()
    private let moving = NSHashTable<UIScrollView>.weakObjects()
    private struct Waiter {
        let id: UUID
        let cont: CheckedContinuation<Void, Never>
    }
    private var idleWaiters: [Waiter] = []

    var isMoving: Bool { !moving.allObjects.isEmpty }

    func note(_ scrollView: UIScrollView) {
        if scrollView.isDragging || scrollView.isDecelerating {
            moving.add(scrollView)
        } else {
            moving.remove(scrollView)
        }
        guard moving.allObjects.isEmpty else { return }
        let waiters = idleWaiters
        idleWaiters.removeAll()
        waiters.forEach { $0.cont.resume() }
    }

    /// 格子已经离开列表时把等待取消掉，避免停在未恢复的 continuation 上。
    func cancel(_ id: UUID) {
        guard let i = idleWaiters.firstIndex(where: { $0.id == id }) else { return }
        idleWaiters.remove(at: i).cont.resume()
    }

    func waitUntilIdle() async {
        if !isMoving { return }
        let id = UUID()
        await withTaskCancellationHandler {
            await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
                if !isMoving || Task.isCancelled {
                    cont.resume()
                    return
                }
                idleWaiters.append(Waiter(id: id, cont: cont))
            }
        } onCancel: {
            Task { @MainActor in ListScrollGate.shared.cancel(id) }
        }
    }
}

/// 全局 frame 记在引用类型里。滚动时由 contentOffset 的 KVO 改坐标，不把格子标成需要刷新。
private struct TileFrameSink: UIViewRepresentable {
    let id: String

    func makeUIView(context: Context) -> Sink {
        let v = Sink()
        v.isUserInteractionEnabled = false
        v.backgroundColor = .clear
        v.id = id
        return v
    }

    func updateUIView(_ uiView: Sink, context: Context) {
        uiView.id = id
    }

    final class Sink: UIView {
        var id = ""
        private var observations: [NSKeyValueObservation] = []
        private weak var watched: UIScrollView?

        override func didMoveToWindow() {
            super.didMoveToWindow()
            watchScroll()
            publish()
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            if observations.isEmpty { watchScroll() }
            publish()
        }

        private func watchScroll() {
            guard window != nil else {
                observations.forEach { $0.invalidate() }
                observations = []
                watched = nil
                return
            }
            var v: UIView? = superview
            while let cur = v, !(cur is UIScrollView) { v = cur.superview }
            guard let sv = v as? UIScrollView else { return }
            if watched === sv, !observations.isEmpty { return }
            observations.forEach { $0.invalidate() }
            watched = sv
            observations = [
                sv.observe(\.contentOffset, options: [.new]) { [weak self] sv, _ in
                    guard Thread.isMainThread else { return }
                    MainActor.assumeIsolated {
                        self?.publish()
                        ListScrollGate.shared.note(sv)
                    }
                },
                // 最后一次 contentOffset 变化时，isDecelerating 往往还是 true。
                // 这两个标志自己变 false 时不会再改 offset，要单独看，否则 RAW 查询一直等着。
                sv.observe(\.isDragging, options: [.new]) { sv, _ in
                    guard Thread.isMainThread else { return }
                    MainActor.assumeIsolated { ListScrollGate.shared.note(sv) }
                },
                sv.observe(\.isDecelerating, options: [.new]) { sv, _ in
                    guard Thread.isMainThread else { return }
                    MainActor.assumeIsolated { ListScrollGate.shared.note(sv) }
                },
            ]
        }

        private func publish() {
            guard window != nil else { return }
            let host: UIView = bounds.width > 1 ? self : (superview ?? self)
            guard host.bounds.width > 1 else { return }
            let frame = host.convert(host.bounds, to: nil)
            if PhotoOpenTransition.shared.tileFrames[id] != frame {
                PhotoOpenTransition.shared.register(id, frame: frame)
            }
        }
    }
}
