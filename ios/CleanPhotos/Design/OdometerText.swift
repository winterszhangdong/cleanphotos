import SwiftUI

/// 里程表数字（Android `OdometerText`）：右对齐逐位比较，变化的位独立滚动——数值增加向上、减少向下；
/// 位移走 settle 弹簧（0.75 / 400），透明度 150ms Standard；整行裁切。Reduce Motion 下直接换字。
struct OdometerText: View {
    let value: Int64
    let text: String
    var font: Font
    var color: Color
    var tracking: CGFloat = 0

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var lastValue: Int64?
    @State private var oldText: String = ""
    @State private var change: Date?
    @State private var direction: Int = 0 // 1 上滚（增加），-1 下滚（减少），0 只淡入淡出

    private static let settleSpan: Double = 0.6

    var body: some View {
        let chars = Array(text)
        let old = Array(oldText)
        TimelineView(.animation(paused: change == nil)) { tl in
            let t = change.map { tl.date.timeIntervalSince($0) } ?? 10
            let q = change == nil ? 1 : SpringToken.settle.value(at: t)
            let fade = change == nil ? 1 : CubicBezier.standard(min(1, t / DarkroomMotion.short))
            HStack(spacing: 0) {
                ForEach(chars.indices, id: \.self) { i in
                    let fromEnd = chars.count - i
                    let oldIndex = old.count - fromEnd
                    let prev: Character? = old.indices.contains(oldIndex) ? old[oldIndex] : nil
                    let rolling = change != nil && prev != chars[i]
                    digit(chars[i], prev: rolling ? prev : nil, rolling: rolling, q: q, fade: fade)
                        .id(fromEnd)
                }
            }
            .clipped()
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(text))
        .onAppear { lastValue = value }
        .onChange(of: text) { previous, _ in
            guard !reduceMotion else { lastValue = value; return }
            let before = lastValue ?? value
            direction = value > before ? 1 : (value < before ? -1 : 0)
            oldText = previous
            let start = Date()
            change = start
            lastValue = value
            DispatchQueue.main.asyncAfter(deadline: .now() + Self.settleSpan) {
                if change == start { change = nil }
            }
        }
    }

    @ViewBuilder
    private func digit(_ c: Character, prev: Character?, rolling: Bool, q: Double, fade: Double) -> some View {
        let dir = CGFloat(direction)
        ZStack {
            if let prev {
                Text(String(prev))
                    .font(font).tracking(tracking).foregroundStyle(color)
                    .opacity(1 - fade)
                    .visualEffect { content, proxy in
                        content.offset(y: -dir * proxy.size.height * CGFloat(q))
                    }
            }
            Text(String(c))
                .font(font).tracking(tracking).foregroundStyle(color)
                .opacity(rolling ? fade : 1)
                .visualEffect { content, proxy in
                    content.offset(y: rolling ? dir * proxy.size.height * CGFloat(1 - q) : 0)
                }
        }
    }
}
