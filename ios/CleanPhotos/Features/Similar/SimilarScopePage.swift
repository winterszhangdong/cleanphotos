import SwiftUI

struct SimilarCompareRoute: Identifiable {
    let groupKey: String
    let photoId: String
    var id: String { groupKey + "\n" + photoId }
}

/// 照片页范围切到「相似」时放进来。自己不进导航，对比页用全屏盖住底部导航。
struct SimilarScopePage: View {
    @EnvironmentObject var library: PhotoLibraryService
    @EnvironmentObject private var language: LanguageStore
    @ObservedObject private var session = SimilarSession.shared
    @State private var compare: SimilarCompareRoute?

    private var zh: Bool { language.isChineseUI }

    var body: some View {
        ZStack {
            DarkroomColors.room
            if session.phase != .ready {
                comparing
            } else if session.groups.isEmpty {
                Text(SimilarCopy.empty(zh: zh))
                    .font(DarkroomType.title)
                    .foregroundStyle(DarkroomColors.paper)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)
            } else {
                list
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .onAppear { session.start(library) }
        .fullScreenCover(item: $compare) { route in
            SimilarCompareView(groupKey: route.groupKey, photoId: route.photoId) {
                compare = nil
            }
            .environmentObject(library)
            .environmentObject(language)
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if let undo = session.keptUndo {
                keptCapsule(undo)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 8)
            }
        }
    }

    private var comparing: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(SimilarCopy.comparing(done: session.done, total: session.total, zh: zh))
                .font(DarkroomType.countSub)
                .foregroundStyle(DarkroomColors.dim)
                .padding(.horizontal, 16)
                .padding(.top, 8)
            ForEach(0..<3, id: \.self) { i in
                SimilarSkeletonCard(delay: Double(i) * 0.2)
            }
            Spacer(minLength: 0)
        }
    }

    private var list: some View {
        ScrollView {
            LazyVStack(spacing: 8) {
                ForEach(session.groups) { card in
                    cardView(card)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
        }
    }

    private func cardView(_ card: SimilarCard) -> some View {
        let map = session.marks[card.key] ?? [:]
        let removeCount = card.photos.filter { map[$0.id] == .remove }.count
        return VStack(alignment: .leading, spacing: 8) {
            Text(takenLabel(card.earliest))
                .font(DarkroomType.body)
                .foregroundStyle(DarkroomColors.paper)
                .lineLimit(1)
            thumbRow(card, marks: map)
            SimilarCommitButton(title: SimilarCopy.commit(removeCount: removeCount, zh: zh), enabled: !session.moving) {
                _ = session.commit(groupKey: card.key)
            }
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
                        onTap: { compare = SimilarCompareRoute(groupKey: card.key, photoId: photo.id) }
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
        return DateFormatCache.string(date, format: pattern, locale: language.effectiveLocale)
    }

    private func thumbLabel(_ photo: PhotoItem, mark: SimilarMark) -> String {
        let pattern = zh ? "M月d日 HH:mm" : "MMM d HH:mm"
        let date = DateFormatCache.string(photo.creationDate, format: pattern, locale: language.effectiveLocale)
        return SimilarCopy.film(date: date, mark: SimilarCopy.mark(mark, zh: zh), zh: zh)
    }

    private func keptCapsule(_ undo: SimilarKeptUndo) -> some View {
        KeptUndoCapsule(undo: undo, zh: zh) { session.undoKept() }
            .task(id: undo.id) {
                try? await Task.sleep(nanoseconds: UInt64(UndoBanner.lifetime * 1_000_000_000))
                session.expireKept(undo.id)
            }
    }
}

private struct SimilarSkeletonCard: View {
    var delay: Double
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
        .onAppear {
            guard !reduceMotion else { return }
            withAnimation(.easeInOut(duration: 2.7).repeatForever(autoreverses: true).delay(delay)) {
                high = true
            }
        }
    }
}

/// 「都留下」没有移走，现成的撤回条文案对不上，这里单独写「已留下」。
private struct KeptUndoCapsule: View {
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
