import SwiftUI

/// 撤回胶囊的共同表面（ui-design 4.1 / Android UndoCapsule.kt）。
/// 清理页、照片页批量、相似「已留下」、印样表装袋共用这一块。
struct UndoCapsuleSurface: View {
    let countdownKey: AnyHashable
    let sampleId: String?
    let title: String
    let subtitle: String?
    let amber: Bool
    let keepColor: Bool
    let shownAt: Date
    let locale: Locale
    let onUndo: () -> Void

    @EnvironmentObject private var library: PhotoLibraryService
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var thumb: UIImage?
    @State private var thumbToken: ThumbnailToken?
    @State private var countdown: CGFloat = 1

    private var colored: Bool { amber || keepColor }

    var body: some View {
        HStack(spacing: 12) {
            thumbView
            VStack(alignment: .leading, spacing: 0) {
                Text(title)
                    .font(Font.custom("NotoSansSC-Medium", size: 14))
                    .foregroundStyle(DarkroomColors.paper)
                    .lineLimit(2)
                if let subtitle {
                    Text(subtitle)
                        .font(Font.custom("NotoSansSC-Regular", size: 11))
                        .foregroundStyle(DarkroomColors.dim)
                        .lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Button(action: onUndo) {
                HStack(spacing: 4) {
                    DarkroomIconView(icon: .undo, size: 16)
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
        .frame(minHeight: 56)
        .frame(maxWidth: .infinity)
        .overlay(alignment: .bottom) { timer }
        .clipShape(Capsule())
        .background { glass }
        .overlay(Capsule().strokeBorder(DarkroomColors.menuRim, lineWidth: 1))
        .environment(\.locale, locale)
        .task(id: thumbKey) { loadThumb() }
        .onAppear { startCountdown() }
        .onChange(of: countdownKey) { _, _ in startCountdown() }
        .onChange(of: shownAt) { _, _ in startCountdown() }
        .onChange(of: reduceMotion) { _, _ in startCountdown() }
        .onDisappear {
            thumbToken?.cancel()
            thumbToken = nil
        }
    }

    /// 外层投影不裁切；模糊裁成胶囊；上面只叠一层 78% 暖玻璃。
    private var glass: some View {
        Capsule()
            .fill(UndoCapsule.glass)
            .background { DarkroomBlurBackdrop().clipShape(Capsule()) }
            .shadow(color: .black.opacity(0.5), radius: 20, y: 16)
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
            }
        }
        .frame(width: 40, height: 40)
        .clipShape(Circle())
        .overlay(
            Circle().strokeBorder(
                amber ? DarkroomColors.amber : DarkroomColors.paper.opacity(0.16),
                lineWidth: amber ? 1.5 : 1
            )
        )
    }

    private var timer: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(amber ? DarkroomColors.amber.opacity(0.25) : DarkroomColors.paper.opacity(0.12))
                Rectangle()
                    .fill(amber ? DarkroomColors.amber : DarkroomColors.fade)
                    .frame(width: geo.size.width)
                    .scaleEffect(x: countdown, y: 1, anchor: .leading)
            }
        }
        .frame(height: 2)
        .padding(.horizontal, 28)
        .allowsHitTesting(false)
    }

    /// 减弱动态效果时细线保持满格。否则按这次操作的剩余时间接着走，藏起再露出不重新满格。
    private func startCountdown() {
        if reduceMotion {
            var transaction = Transaction()
            transaction.disablesAnimations = true
            withTransaction(transaction) { countdown = 1 }
            return
        }
        let remaining = max(0, UndoBanner.lifetime - Date().timeIntervalSince(shownAt))
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) { countdown = CGFloat(remaining / UndoBanner.lifetime) }
        guard remaining > 0 else { return }
        DispatchQueue.main.async {
            withAnimation(.linear(duration: remaining)) { countdown = 0 }
        }
    }

    private var thumbKey: String {
        "\(sampleId ?? "")-\(colored)"
    }

    private func loadThumb() {
        thumbToken?.cancel()
        thumbToken = nil
        guard let id = sampleId, let asset = library.item(id: id)?.asset else {
            thumb = nil
            return
        }
        let style: ThumbnailStyle = colored ? .normal : .trashed
        if let hit = library.thumbnails.cached(id: id, side: 40, style: style) {
            thumb = hit
            return
        }
        thumbToken = library.thumbnails.request(asset: asset, side: 40, style: style) { img, _ in
            self.thumb = img
        }
    }
}

/// 撤回胶囊（ui-design 4.1 / Android UndoCapsule.kt）：
/// 56pt 玻璃胶囊 #1A1816 78%，左 40pt 圆形小样，主文案 14 500 + 可选副文案 11 dim，
/// 右侧相纸白「撤回」40pt 胶囊（13 600 暗室色）；底部 2pt 4 秒倒计时细线（左右内缩 28pt）。
/// 移到垃圾桶：小样去色压暗、描边 / 倒计时中性灰；收藏：琥珀。
struct UndoCapsule: View {
    let banner: UndoBanner
    var locale: Locale
    var onUndo: () -> Void

    private var fav: Bool { banner.isAmber }

    var body: some View {
        UndoCapsuleSurface(
            countdownKey: banner.id,
            sampleId: banner.sampleId,
            title: title,
            subtitle: banner.subtitle,
            amber: fav,
            keepColor: fav,
            shownAt: banner.shownAt,
            locale: locale,
            onUndo: onUndo
        )
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

    static let glass = Color(red: 26/255, green: 24/255, blue: 22/255).opacity(0.78)
}
