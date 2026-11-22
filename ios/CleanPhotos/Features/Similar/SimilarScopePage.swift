import SwiftUI

struct SimilarCompareRoute: Identifiable {
    let groupKey: String
    let photoId: String
    var id: String { groupKey + "\n" + photoId }
}

/// 相似分组的一张卡片。竖向列表由照片页的 LazyVStack 直接装，这里不再套一层 ScrollView。
struct SimilarCardRow: View {
    let card: SimilarCard
    let marks: [String: SimilarMark]
    var moving: Bool
    var zh: Bool
    var locale: Locale
    var onOpen: (String) -> Void
    var onCommit: () -> Void

    var body: some View {
        let removeCount = card.photos.filter { marks[$0.id] == .remove }.count
        return VStack(alignment: .leading, spacing: 8) {
            Text(takenLabel(card.earliest))
                .font(DarkroomType.body)
                .foregroundStyle(DarkroomColors.paper)
                .lineLimit(1)
            thumbRow(card, marks: marks)
            SimilarCommitButton(title: SimilarCopy.commit(removeCount: removeCount, zh: zh), enabled: !moving, action: onCommit)
        }
        .padding(16)
        .background {
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .fill(DarkroomColors.tray)
                .overlay(
                    RoundedRectangle(cornerRadius: 20, style: .continuous)
                        .strokeBorder(DarkroomColors.rim, lineWidth: 1)
                )
        }
    }

    private func thumbRow(_ card: SimilarCard, marks: [String: SimilarMark]) -> some View {
        GeometryReader { geo in
            let content = CGFloat(card.photos.count) * 72 + CGFloat(max(0, card.photos.count - 1)) * 8
            let row = HStack(spacing: 8) {
                ForEach(card.photos) { photo in
                    SimilarThumbView(
                        item: photo,
                        side: 72,
                        mark: marks[photo.id] ?? .keep,
                        label: thumbLabel(photo, mark: marks[photo.id] ?? .keep),
                        onTap: { onOpen(photo.id) }
                    )
                }
            }
            if content <= geo.size.width + 0.5 {
                row.frame(maxWidth: .infinity, alignment: .leading)
            } else {
                ScrollView(.horizontal, showsIndicators: false) { row }
            }
        }
        .frame(height: 72)
    }

    private func takenLabel(_ date: Date) -> String {
        let pattern = zh ? "M月d日 · EEE · HH:mm" : "MMM d · EEE · HH:mm"
        return DateFormatCache.string(date, format: pattern, locale: locale)
    }

    private func thumbLabel(_ photo: PhotoItem, mark: SimilarMark) -> String {
        let pattern = zh ? "M月d日 HH:mm" : "MMM d HH:mm"
        let date = DateFormatCache.string(photo.creationDate, format: pattern, locale: locale)
        return SimilarCopy.film(date: date, mark: SimilarCopy.mark(mark, zh: zh), zh: zh)
    }

}

struct SimilarSkeletonCard: View {
    var delay: Double
    var active: Bool = true
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var high = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            RoundedRectangle(cornerRadius: 4)
                .fill(DarkroomColors.skeletonHigh)
                .frame(width: 120, height: 14)
            HStack(spacing: 8) {
                ForEach(0..<4, id: \.self) { _ in
                    RoundedRectangle(cornerRadius: 2)
                        .fill(DarkroomColors.skeletonHigh)
                        .frame(width: 72, height: 72)
                }
            }
            Capsule()
                .fill(DarkroomColors.skeletonHigh)
                .frame(height: 36)
        }
        .padding(16)
        .background {
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .fill(DarkroomColors.tray)
                .overlay(
                    RoundedRectangle(cornerRadius: 20, style: .continuous)
                        .strokeBorder(DarkroomColors.rim, lineWidth: 1)
                )
        }
        .opacity(reduceMotion ? 1 : (high ? 1 : 0.55))
        .onAppear { syncAnimation() }
        .onChange(of: active) { _, _ in syncAnimation() }
    }

    private func syncAnimation() {
        if active && !reduceMotion {
            guard !high else { return }
            withAnimation(.easeInOut(duration: 2.7).repeatForever(autoreverses: true).delay(delay)) {
                high = true
            }
        } else {
            var tx = Transaction(animation: nil)
            tx.disablesAnimations = true
            withTransaction(tx) { high = false }
        }
    }
}

/// 「都留下」没有移走，现成的撤回条文案对不上，这里单独写「已留下」。
struct KeptUndoCapsule: View {
    let undo: SimilarKeptUndo
    let zh: Bool
    let onUndo: () -> Void
    @EnvironmentObject private var library: PhotoLibraryService
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
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
                        .saturation(0.4)
                        .brightness(-0.3)
                }
            }
            .frame(width: 40, height: 40)
            .clipShape(Circle())
            .overlay(Circle().strokeBorder(DarkroomColors.paper.opacity(0.16), lineWidth: 1))

            Text(SimilarCopy.kept(zh: zh))
                .font(Font.custom("NotoSansSC-Medium", size: 14))
                .foregroundStyle(DarkroomColors.paper)
                .frame(maxWidth: .infinity, alignment: .leading)

            Button(action: onUndo) {
                Text(L10n.string("undo_action", locale: zh ? Locale(identifier: "zh-Hans") : Locale(identifier: "en")))
                    .font(Font.custom("NotoSansSC-SemiBold", size: 13))
                    .foregroundStyle(DarkroomColors.room)
                    .padding(.horizontal, 16)
                    .frame(height: 40)
                    .background(DarkroomColors.paper, in: Capsule())
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 8)
        .frame(height: 56)
        .overlay(alignment: .bottom) {
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(DarkroomColors.paper.opacity(0.12))
                    Rectangle()
                        .fill(DarkroomColors.fade)
                        .frame(width: geo.size.width)
                        .scaleEffect(x: countdown, y: 1, anchor: .leading)
                }
            }
            .frame(height: 2)
            .padding(.horizontal, 28)
            .allowsHitTesting(false)
        }
        .background {
            Capsule()
                .fill(Color(red: 26 / 255, green: 24 / 255, blue: 22 / 255).opacity(0.78))
                .background(.ultraThinMaterial, in: Capsule())
        }
        .clipShape(Capsule())
        .overlay(Capsule().strokeBorder(DarkroomColors.menuRim, lineWidth: 1))
        .task(id: undo.sampleId) { loadThumb() }
        .onAppear { startCountdown() }
    }

    private func startCountdown() {
        let remaining = max(0, UndoBanner.lifetime - Date().timeIntervalSince(undo.shownAt))
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) { countdown = CGFloat(remaining / UndoBanner.lifetime) }
        guard remaining > 0, !reduceMotion else { return }
        DispatchQueue.main.async {
            withAnimation(.linear(duration: remaining)) { countdown = 0 }
        }
    }

    private func loadThumb() {
        guard let asset = library.item(id: undo.sampleId)?.asset else { return }
        _ = library.requestThumbnail(for: asset, side: 40) { img in
            Task { @MainActor in self.thumb = img }
        }
    }
}
