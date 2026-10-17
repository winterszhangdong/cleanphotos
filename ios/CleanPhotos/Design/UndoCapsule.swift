import SwiftUI

/// 撤回胶囊（ui-design 4.1 / Android UndoCapsule.kt）：
/// 56pt 玻璃胶囊 #1A1816 78%，左 40pt 圆形小样，主文案 14 500 + 可选副文案 11 dim，
/// 右侧相纸白「撤回」40pt 胶囊（13 600 暗室色）；底部 2pt 4 秒倒计时细线（左右内缩 28pt）。
/// 移到垃圾桶：小样去色压暗、描边 / 倒计时中性灰；收藏：琥珀。
struct UndoCapsule: View {
    let banner: UndoBanner
    var locale: Locale
    var onUndo: () -> Void
    @EnvironmentObject private var library: PhotoLibraryService
    @State private var thumb: UIImage?
    @State private var countdown: CGFloat = 1

    private var fav: Bool { banner.isAmber }

    var body: some View {
        HStack(spacing: 12) {
            thumbView
            VStack(alignment: .leading, spacing: 0) {
                Text(title)
                    .font(Font.custom("NotoSansSC-Medium", size: 14))
                    .foregroundStyle(DarkroomColors.paper)
                    .lineLimit(1)
                if let sub = banner.subtitle {
                    Text(sub)
                        .font(Font.custom("NotoSansSC-Regular", size: 11))
                        .foregroundStyle(DarkroomColors.dim)
                        .lineLimit(1)
                }
            }
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
                .contentShape(Capsule())
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 8)
        .frame(height: 56)
        .frame(maxWidth: .infinity)
        .overlay(alignment: .bottom) { timer }
        .background {
            Capsule()
                .fill(Self.glass)
                .background(.ultraThinMaterial, in: Capsule())
        }
        .clipShape(Capsule())
        .overlay(Capsule().strokeBorder(DarkroomColors.menuRim, lineWidth: 1))
        .shadow(color: .black.opacity(0.5), radius: 20, y: 16)
        .environment(\.locale, locale)
        .task(id: banner.sampleId) { loadThumb() }
        .onAppear { startCountdown() }
        .onChange(of: banner.id) { _, _ in startCountdown() }
    }

    private var thumbView: some View {
        ZStack {
            Circle().fill(DarkroomColors.tray2)
            if let thumb {
                Image(uiImage: thumb)
                    .resizable()
                    .accessibilityHidden(true)
                    .scaledToFill()
                    .frame(width: 40, height: 40)
                    // .undo .thumb img：grayscale(0.6) brightness(0.7)
                    .saturation(fav ? 1 : 0.4)
                    .brightness(fav ? 0 : -0.3)
            }
        }
        .frame(width: 40, height: 40)
        .clipShape(Circle())
        .overlay(
            Circle().strokeBorder(fav ? DarkroomColors.amber : DarkroomColors.paper.opacity(0.16), lineWidth: fav ? 1.5 : 1)
        )
    }

    private var timer: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(fav ? DarkroomColors.amber.opacity(0.25) : DarkroomColors.paper.opacity(0.12))
                // 倒计时用横向缩放（左端锚点）而不是改宽度：4 秒里每帧不用重新布局
                Rectangle()
                    .fill(fav ? DarkroomColors.amber : DarkroomColors.fade)
                    .frame(width: geo.size.width)
                    .scaleEffect(x: countdown, y: 1, anchor: .leading)
            }
        }
        .frame(height: 2)
        .padding(.horizontal, 28)
        .allowsHitTesting(false)
    }

    private func startCountdown() {
        var t = Transaction(); t.disablesAnimations = true
        withTransaction(t) { countdown = 1 }
        DispatchQueue.main.async {
            withAnimation(.linear(duration: 4)) { countdown = 0 }
        }
    }

    private var title: String {
        if banner.single {
            switch banner.kind {
            case .favorited: return L10n.string("undo_favorited", locale: locale)
            case .unfavorited: return L10n.string("undo_unfavorited", locale: locale)
            case .movedToTrash: return L10n.string("undo_trashed", locale: locale)
            case .restored: return L10n.string("undo_restored", locale: locale)
            }
        }
        let (key, n): (String, Int) = {
            switch banner.kind {
            case .favorited(let ids): return ("batch_undo_favorite", ids.count)
            case .unfavorited(let ids): return ("batch_undo_unfavorite", ids.count)
            case .movedToTrash(let ids): return ("batch_undo_trash", ids.count)
            case .restored(let ids): return ("batch_undo_restore", ids.count)
            }
        }()
        return L10n.plural(key, count: n, locale: locale)
    }

    private func loadThumb() {
        guard let id = banner.sampleId, let asset = library.allById[id]?.asset else { return }
        _ = library.requestThumbnail(for: asset, side: 40) { img in
            Task { @MainActor in self.thumb = img }
        }
    }

    static let glass = Color(red: 26/255, green: 24/255, blue: 22/255).opacity(0.78)
}
