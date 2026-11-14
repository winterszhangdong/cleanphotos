import SwiftUI

/// 范围刻度。选中项居中，短线固定在行正中。点邻居翻页，点更远的名字直接跳。
struct ScopeDialView: View {
    var scopes: [LibraryScope]
    var progress: CGFloat
    var chinese: Bool
    var onSelect: (Int) -> Void
    var onDrag: (CGFloat, Bool, CGFloat) -> Void
    var onWidths: ([CGFloat]) -> Void

    @State private var widths: [CGFloat] = []
    @State private var dragging = false

    var body: some View {
        GeometryReader { geo in
            let gap = DarkroomDimens.scopeDialGap
            let tx = ScopeDialMath.translationX(
                viewportCenter: geo.size.width / 2,
                progress: progress,
                widths: widths.count == scopes.count ? widths : Array(repeating: 36, count: scopes.count),
                gap: gap
            )
            ZStack(alignment: .topLeading) {
                HStack(spacing: gap) {
                    ForEach(scopes.indices, id: \.self) { i in
                        let distance = abs(CGFloat(i) - progress)
                        Text(PhotoPageCopy.scopeName(scopes[i], chinese: chinese))
                            .font(DarkroomType.scopeDial)
                            .foregroundStyle(DarkroomColors.paper.opacity(Double(ScopeDialMath.alpha(distance: distance))))
                            .lineLimit(1)
                            .fixedSize()
                            .padding(.top, DarkroomDimens.scopeDialTextTop)
                            .frame(minHeight: DarkroomDimens.minTouch, alignment: .top)
                            .background {
                                GeometryReader { g in
                                    Color.clear.preference(key: DialWidthsKey.self, value: [i: g.size.width])
                                }
                            }
                    }
                }
                .offset(x: tx)

                Capsule()
                    .fill(DarkroomColors.paper)
                    .frame(width: DarkroomDimens.navIndicatorWidth, height: DarkroomDimens.navIndicatorHeight)
                    .position(x: geo.size.width / 2, y: DarkroomDimens.scopeDialIndicatorTop + DarkroomDimens.navIndicatorHeight / 2)
            }
            .frame(width: geo.size.width, height: DarkroomDimens.scopeRowHeight, alignment: .topLeading)
            .mask(edgeFade(width: geo.size.width))
            .contentShape(Rectangle())
            .gesture(drag(width: geo.size.width, translationX: tx))
            .onPreferenceChange(DialWidthsKey.self) { map in
                let next = scopes.indices.map { map[$0] ?? 36 }
                if next != widths {
                    widths = next
                    let report = next
                    DispatchQueue.main.async { onWidths(report) }
                }
            }
        }
        .frame(height: DarkroomDimens.scopeRowHeight)
        .background(DarkroomColors.room)
    }

    private func edgeFade(width: CGFloat) -> some View {
        let fade = DarkroomDimens.scopeDialEdgeFade / max(width, 1)
        return LinearGradient(
            stops: [
                .init(color: .clear, location: 0),
                .init(color: .black, location: min(0.49, fade)),
                .init(color: .black, location: max(0.51, 1 - fade)),
                .init(color: .clear, location: 1),
            ],
            startPoint: .leading,
            endPoint: .trailing
        )
    }

    private func drag(width: CGFloat, translationX: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                let moved = hypot(value.translation.width, value.translation.height) > 8
                if moved {
                    dragging = true
                    onDrag(value.translation.width, false, 0)
                }
            }
            .onEnded { value in
                if dragging {
                    let vx = (value.predictedEndTranslation.width - value.translation.width) / 0.2
                    onDrag(value.translation.width, true, vx)
                } else {
                    let contentX = value.location.x - translationX
                    let used = widths.count == scopes.count ? widths : Array(repeating: 36, count: scopes.count)
                    let hit = ScopeDialMath.hitIndex(
                        contentX: contentX,
                        widths: used,
                        gap: DarkroomDimens.scopeDialGap,
                        minTouch: DarkroomDimens.minTouch
                    )
                    if hit >= 0 { onSelect(hit) }
                }
                dragging = false
            }
    }
}

private struct DialWidthsKey: PreferenceKey {
    static var defaultValue: [Int: CGFloat] = [:]
    static func reduce(value: inout [Int: CGFloat], nextValue: () -> [Int: CGFloat]) {
        value.merge(nextValue(), uniquingKeysWith: { $1 })
    }
}
