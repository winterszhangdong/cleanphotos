import SwiftUI

struct SimilarCompareRoute: Identifiable {
    let groupKey: String
    let photoId: String
    var id: String { groupKey + "\n" + photoId }
}

struct SimilarSkeletonCard: View {
    var delay: Double
    var active: Bool = true
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var epoch = Date()

    var body: some View {
        TimelineView(.animation(paused: reduceMotion || !active)) { tl in
            VStack(alignment: .leading, spacing: 8) {
                RoundedRectangle(cornerRadius: 4)
                    .fill(SkeletonBreath.color(delay: delay, now: tl.date, epoch: epoch))
                    .frame(width: 120, height: 14)
                HStack(spacing: 8) {
                    ForEach(0..<4, id: \.self) { col in
                        RoundedRectangle(cornerRadius: 2)
                            .fill(SkeletonBreath.color(delay: delay + Double(col + 1) * 0.2, now: tl.date, epoch: epoch))
                            .frame(width: 72, height: 72)
                    }
                }
                Capsule()
                    .fill(SkeletonBreath.color(delay: delay, now: tl.date, epoch: epoch))
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
        }
    }
}

/// 「都留下」没有移走，现成的撤回条文案对不上，这里单独写「已留下」。
struct KeptUndoCapsule: View {
    let undo: SimilarKeptUndo
    let zh: Bool
    let onUndo: () -> Void

    var body: some View {
        UndoCapsuleSurface(
            countdownKey: undo.id,
            sampleId: undo.sampleId,
            title: SimilarCopy.kept(zh: zh),
            subtitle: nil,
            amber: false,
            keepColor: false,
            shownAt: undo.shownAt,
            locale: zh ? Locale(identifier: "zh-Hans") : Locale(identifier: "en"),
            onUndo: onUndo
        )
    }
}
