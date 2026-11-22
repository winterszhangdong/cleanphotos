import Photos
import SwiftUI
import UIKit

enum EnvelopeKey {
    static let newSleeve = "+new"
    static let more = "+more"
}

struct PipelineImage: UIViewRepresentable {
    var asset: PHAsset?
    var side: CGFloat
    var pipeline: ThumbnailPipeline

    func makeCoordinator() -> Coordinator { Coordinator() }
    func makeUIView(context: Context) -> UIImageView {
        let view = UIImageView()
        view.contentMode = .scaleAspectFill
        view.clipsToBounds = true
        view.backgroundColor = DarkroomUIColors.tray2
        return view
    }
    func updateUIView(_ uiView: UIImageView, context: Context) {
        context.coordinator.load(asset, side: side, pipeline: pipeline, into: uiView)
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: UIImageView, context: Context) -> CGSize? {
        CGSize(width: proposal.width ?? side, height: proposal.height ?? side)
    }
    static func dismantleUIView(_ uiView: UIImageView, coordinator: Coordinator) { coordinator.token?.cancel() }

    @MainActor
    final class Coordinator {
        var token: ThumbnailToken?
        var id: String?
        func load(_ asset: PHAsset?, side: CGFloat, pipeline: ThumbnailPipeline, into view: UIImageView) {
            let next = asset?.localIdentifier
            guard next != id else { return }
            token?.cancel()
            id = next
            view.image = nil
            guard let asset else { return }
            if let cached = pipeline.cached(id: asset.localIdentifier, side: side, style: .normal) {
                view.image = cached
            }
            token = pipeline.request(asset: asset, side: side, style: .normal) { [weak view] image, _ in
                view?.image = image
            }
        }
    }
}

struct WhitePrint: View {
    var asset: PHAsset?
    var pipeline: ThumbnailPipeline
    var width: CGFloat
    var height: CGFloat
    var tilt: CGFloat = 0
    var empty: Bool = false

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 2, style: .continuous).fill(DarkroomColors.printBorder)
            if empty {
                RoundedRectangle(cornerRadius: 1, style: .continuous)
                    .strokeBorder(DarkroomColors.rim2, style: StrokeStyle(lineWidth: 1, dash: [4, 3]))
                    .padding(.init(top: 2, leading: 2, bottom: 6, trailing: 2))
            } else {
                PipelineImage(asset: asset, side: width, pipeline: pipeline)
                    .padding(.init(top: 2, leading: 2, bottom: 6, trailing: 2))
                    .clipShape(RoundedRectangle(cornerRadius: 1, style: .continuous))
            }
        }
        .frame(width: width, height: height)
        .clipped()
        .rotationEffect(.degrees(tilt))
    }
}

struct EnvelopeFrontShape: Shape {
    var notch: CGFloat = 10
    var bottomCorner: CGFloat = 4

    func path(in rect: CGRect) -> Path {
        let r = min(notch, rect.width / 2)
        let cx = rect.midX
        let c = min(bottomCorner, rect.height / 2, rect.width / 2)
        var path = Path()
        path.move(to: CGPoint(x: rect.minX, y: rect.minY))
        path.addLine(to: CGPoint(x: cx - r, y: rect.minY))
        path.addArc(
            center: CGPoint(x: cx, y: rect.minY),
            radius: r,
            startAngle: .degrees(180),
            endAngle: .degrees(0),
            clockwise: true
        )
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.minY))
        if c > 0 {
            path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY - c))
            path.addQuadCurve(to: CGPoint(x: rect.maxX - c, y: rect.maxY), control: CGPoint(x: rect.maxX, y: rect.maxY))
            path.addLine(to: CGPoint(x: rect.minX + c, y: rect.maxY))
            path.addQuadCurve(to: CGPoint(x: rect.minX, y: rect.maxY - c), control: CGPoint(x: rect.minX, y: rect.maxY))
        } else {
            path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
            path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY))
        }
        path.closeSubpath()
        return path
    }
}

struct EnvelopeTopHighlightShape: Shape {
    var notch: CGFloat = 10

    func path(in rect: CGRect) -> Path {
        let r = min(notch, rect.width / 2)
        let cx = rect.midX
        var path = Path()
        path.move(to: CGPoint(x: rect.minX, y: rect.minY + 0.5))
        path.addLine(to: CGPoint(x: cx - r, y: rect.minY + 0.5))
        path.move(to: CGPoint(x: cx + r, y: rect.minY + 0.5))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.minY + 0.5))
        return path
    }
}

struct FlapShape: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(to: CGPoint(x: rect.minX, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.minX + rect.width * 0.86, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.minX + rect.width * 0.14, y: rect.maxY))
        path.closeSubpath()
        return path
    }
}

/// 封口 3D 翻合。翻过 90° 后藏掉，平时不露在袋子上方。
struct FlapFoldModifier: AnimatableModifier {
    var progress: Double

    var animatableData: Double {
        get { progress }
        set { progress = newValue }
    }

    func body(content: Content) -> some View {
        let angle = 180 * (1 - progress)
        content
            .rotation3DEffect(.degrees(angle), axis: (x: 1, y: 0, z: 0), anchor: .top, perspective: FilingMotion.perspective)
            .opacity(angle >= 90 ? 0 : 1)
    }
}

/// 76×104 牛皮纸底片袋。
struct KraftEnvelope: View {
    var title: String
    var count: Int
    var cover: PHAsset?
    var coverId: String?
    var pipeline: ThumbnailPipeline
    var leaning: Bool
    var peeking: Bool = false
    var dimmed: Bool
    var dwellProgress: Double = 0
    var flapClosed: Bool
    var bump: Bool
    var locale: Locale
    var onTap: () -> Void
    var onPeek: (Bool) -> Void

    var body: some View {
        let tilt = FilmGeometryRules.coverTilt(id: coverId ?? title)
        ZStack(alignment: .top) {
            RoundedRectangle(cornerRadius: 4, style: .continuous)
                .fill(DarkroomColors.kraftInside)
                .frame(width: 76, height: 86)
                .padding(.top, 18)
            WhitePrint(asset: cover, pipeline: pipeline, width: 50, height: 50, tilt: tilt, empty: cover == nil)
                .padding(.top, 2)
            EnvelopeFrontShape(notch: 10, bottomCorner: 4)
                .fill(LinearGradient(colors: [DarkroomColors.kraftHigh, DarkroomColors.kraft], startPoint: .top, endPoint: .bottom))
                .frame(width: 76, height: 80)
                .padding(.top, 24)
                .overlay(alignment: .top) {
                    EnvelopeTopHighlightShape(notch: 10)
                        .stroke(DarkroomColors.paper.opacity(0.14), lineWidth: 1)
                        .frame(width: 76, height: 80)
                        .padding(.top, 24)
                }
            VStack(spacing: 1) {
                Text(title.isEmpty ? "—" : title)
                    .font(DarkroomType.envelopeName)
                    .foregroundStyle(DarkroomColors.labelInk)
                    .lineLimit(1)
                OdometerText(
                    value: Int64(count),
                    text: FilingCopy.albumCount(count, locale: locale),
                    font: DarkroomType.envelopeCount,
                    color: DarkroomColors.labelInk.opacity(0.72)
                )
            }
            .padding(.horizontal, 6)
            .frame(width: 62, height: 36)
            .background(DarkroomColors.label, in: RoundedRectangle(cornerRadius: 2, style: .continuous))
            .padding(.top, 104 - 9 - 36)
            FlapShape()
                .fill(LinearGradient(colors: [DarkroomColors.kraftFlapHigh, DarkroomColors.kraftFlap], startPoint: .top, endPoint: .bottom))
                .frame(width: 76, height: 34)
                .modifier(FlapFoldModifier(progress: flapClosed ? 1 : 0))
                .padding(.top, 24)
            if dwellProgress > 0 && dwellProgress < 1 {
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .trim(from: 0, to: dwellProgress)
                    .stroke(DarkroomColors.paper.opacity(0.5), lineWidth: 1.5)
                    .frame(width: 88, height: 98)
                    .padding(.top, 12)
            }
        }
        .frame(width: 76, height: 104)
        .compositingGroup()
        .offset(y: (leaning ? -FilingMotion.leanLift : 0) + (peeking ? -FilingMotion.peekLift : 0) + (bump ? FilingMotion.flapBump : 0))
        .scaleEffect((leaning ? FilingMotion.leanScale : 1) * (peeking ? FilingMotion.peekScale : 1), anchor: .bottom)
        .rotation3DEffect(.degrees(leaning ? FilingMotion.leanTiltX : 0), axis: (x: 1, y: 0, z: 0), perspective: FilingMotion.perspective)
        .rotation3DEffect(.degrees(leaning ? -FilingMotion.leanTiltYMax * 0.75 : 0), axis: (x: 0, y: 1, z: 0), perspective: FilingMotion.perspective)
        .opacity(dimmed ? Double(FilingMotion.dimOthers) : 1)
        .animation(SpringToken.snappy.animation(), value: leaning)
        .animation(SpringToken.settle.animation(), value: peeking)
        .animation(SpringToken.settle.animation(), value: flapClosed)
        .animation(SpringToken.bouncy.animation(), value: bump)
        .contentShape(Rectangle())
        .onTapGesture(perform: onTap)
        .onLongPressGesture(minimumDuration: 0.4, maximumDistance: 12, perform: {}, onPressingChanged: onPeek)
        .background(GeometryReader { proxy in
            Color.clear.preference(key: EnvelopeFrameKey.self, value: [frameKey: proxy.frame(in: .global)])
        })
        .accessibilityLabel(title)
    }

    var frameKey: String = ""
}

struct NewSleeveCard: View {
    var title: String
    var leaning: Bool
    var dimmed: Bool
    var onTap: () -> Void

    var body: some View {
        VStack(spacing: 6) {
            DarkroomIconView(icon: .plus, size: 22)
                .foregroundStyle(DarkroomColors.paper)
            Text(title)
                .font(DarkroomType.envelopeName)
                .foregroundStyle(DarkroomColors.paper)
        }
        .frame(width: 76, height: 104)
        .overlay(RoundedRectangle(cornerRadius: 4, style: .continuous).strokeBorder(DarkroomColors.rim2, style: StrokeStyle(lineWidth: 1.5, dash: [5, 4])))
        .compositingGroup()
        .offset(y: leaning ? -FilingMotion.leanLift : 0)
        .scaleEffect(leaning ? FilingMotion.leanScale : 1, anchor: .bottom)
        .rotation3DEffect(.degrees(leaning ? FilingMotion.leanTiltX : 0), axis: (x: 1, y: 0, z: 0), perspective: FilingMotion.perspective)
        .opacity(dimmed ? Double(FilingMotion.dimOthers) : 1)
        .animation(SpringToken.snappy.animation(), value: leaning)
        .contentShape(Rectangle())
        .onTapGesture(perform: onTap)
        .background(GeometryReader { proxy in
            Color.clear.preference(key: EnvelopeFrameKey.self, value: [EnvelopeKey.newSleeve: proxy.frame(in: .global)])
        })
    }
}

struct MoreSleeveCard: View {
    var title: String
    var search: String
    var leaning: Bool
    var dimmed: Bool
    var onTap: () -> Void

    var body: some View {
        ZStack(alignment: .topLeading) {
            RoundedRectangle(cornerRadius: 4, style: .continuous)
                .fill(DarkroomColors.kraft.opacity(0.24))
                .frame(width: 68, height: 86)
                .offset(x: 8, y: 10)
            RoundedRectangle(cornerRadius: 4, style: .continuous)
                .fill(DarkroomColors.kraft.opacity(0.40))
                .frame(width: 72, height: 86)
                .offset(x: 4, y: 14)
            VStack(spacing: 4) {
                DarkroomIconView(icon: .search, size: 20)
                    .foregroundStyle(DarkroomColors.paper.opacity(0.88))
                Text(title)
                    .font(DarkroomType.envelopeName)
                    .foregroundStyle(DarkroomColors.paper)
                    .multilineTextAlignment(.center)
                    .lineLimit(1)
                Text(search)
                    .font(DarkroomType.envelopeCount)
                    .foregroundStyle(DarkroomColors.dim)
            }
            .frame(width: 68, height: 86)
            .background(DarkroomColors.tray2, in: RoundedRectangle(cornerRadius: 4, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 4, style: .continuous).strokeBorder(DarkroomColors.rim2, lineWidth: 1))
            .offset(y: 18)
        }
        .frame(width: 76, height: 104, alignment: .topLeading)
        .compositingGroup()
        .offset(y: leaning ? -FilingMotion.leanLift : 0)
        .scaleEffect(leaning ? FilingMotion.leanScale : 1, anchor: .bottom)
        .rotation3DEffect(.degrees(leaning ? FilingMotion.leanTiltX : 0), axis: (x: 1, y: 0, z: 0), perspective: FilingMotion.perspective)
        .opacity(dimmed ? Double(FilingMotion.dimOthers) : 1)
        .animation(SpringToken.snappy.animation(), value: leaning)
        .contentShape(Rectangle())
        .onTapGesture(perform: onTap)
        .background(GeometryReader { proxy in
            Color.clear.preference(key: EnvelopeFrameKey.self, value: [EnvelopeKey.more: proxy.frame(in: .global)])
        })
    }
}

/// 悬停打开（3 张）或长按偷看（5 张）从袋口背后扇形滑出的小相片。画在托盘上方、拖拽叠下方。
struct EnvelopeFanOverlay: View {
    var assets: [PHAsset]
    var envelopeRect: CGRect
    var peeking: Bool
    var pipeline: ThumbnailPipeline
    @State private var shown = false

    var body: some View {
        GeometryReader { geo in
            let origin = geo.frame(in: .global).origin
            let size: CGFloat = peeking ? 60 : 50
            let spread: CGFloat = peeking ? 66 : 22
            let rise: CGFloat = peeking ? 118 : 34
            let arc: CGFloat = peeking ? 22 : 8
            let maxDeg = peeking ? FilingMotion.peekFanDeg : FilingMotion.hoverFanDeg
            let lift = peeking ? FilingMotion.peekLift : FilingMotion.leanLift
            let t: CGFloat = shown ? 1 : 0
            let mouthY = envelopeRect.minY - origin.y + 24 - lift * t
            let cx = envelopeRect.midX - origin.x
            let list = Array(assets.prefix(peeking ? 5 : 3))

            ZStack(alignment: .topLeading) {
                ForEach(Array(list.enumerated()), id: \.offset) { index, asset in
                    let n = list.count
                    let k: CGFloat = n <= 1 ? 0 : CGFloat(index) / CGFloat(n - 1) * 2 - 1
                    let cardCx = cx + k * spread * t
                    let cardTopY = mouthY - (rise + (1 - k * k) * arc) * t
                    WhitePrint(asset: asset, pipeline: pipeline, width: size, height: size, tilt: 0)
                        .rotationEffect(.degrees(Double(k * maxDeg * t)), anchor: .bottom)
                        .position(x: cardCx, y: cardTopY + size / 2)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .mask(alignment: .top) {
                Rectangle()
                    .frame(height: max(0, mouthY))
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            }
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .onAppear {
            if UIAccessibility.isReduceMotionEnabled {
                shown = true
            } else {
                withAnimation(SpringToken.settle.animation()) { shown = true }
            }
        }
    }
}

struct EnvelopeFrameKey: PreferenceKey {
    static var defaultValue: [String: CGRect] = [:]
    static func reduce(value: inout [String: CGRect], nextValue: () -> [String: CGRect]) {
        value.merge(nextValue(), uniquingKeysWith: { $1 })
    }
}

struct SystemFavoriteSwitch: View {
    @Binding var on: Bool
    var title: String

    var body: some View {
        Button {
            withAnimation(SpringToken.snappy.animation()) { on.toggle() }
        } label: {
            HStack(spacing: 6) {
                DarkroomIconView(icon: on ? .starSolid : .star, size: 14)
                    .foregroundStyle(on ? DarkroomColors.amber : DarkroomColors.dim)
                Text(title)
                    .font(DarkroomType.traySwitch)
                    .foregroundStyle(on ? DarkroomColors.paper : DarkroomColors.dim)
                    .lineLimit(1)
                ZStack(alignment: .leading) {
                    Capsule()
                        .fill(on ? DarkroomColors.amber.opacity(0.35) : DarkroomColors.paper.opacity(0.14))
                        .frame(width: 24, height: 14)
                    Circle()
                        .fill(on ? DarkroomColors.amber : DarkroomColors.dim)
                        .frame(width: 10, height: 10)
                        .offset(x: on ? 12 : 2)
                }
            }
            .padding(.leading, 10)
            .padding(.trailing, 12)
            .frame(height: 32)
            .background(DarkroomColors.tray2, in: Capsule())
            .overlay(Capsule().strokeBorder(on ? DarkroomColors.amber.opacity(0.4) : DarkroomColors.rim, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityValue(on ? "on" : "off")
    }
}

/// 面板行左边的牛皮纸相片卡座。
struct PrintStack: View {
    var assets: [PHAsset]
    var pipeline: ThumbnailPipeline
    var opened: Bool
    var dashedPlus: Bool = false
    var faded: Bool = false

    var body: some View {
        ZStack {
            if assets.isEmpty {
                WhitePrint(asset: nil, pipeline: pipeline, width: 40, height: 44, empty: true)
                    .overlay {
                        if dashedPlus {
                            DarkroomIconView(icon: .plus, size: 14).foregroundStyle(DarkroomColors.dim)
                        }
                    }
            } else {
                ForEach(Array(assets.prefix(3).enumerated().reversed()), id: \.offset) { index, asset in
                    let pose = pose(index: index, count: min(3, assets.count))
                    WhitePrint(asset: asset, pipeline: pipeline, width: 40, height: 44, tilt: pose.tilt)
                        .offset(x: pose.x, y: pose.y)
                        .scaleEffect(index == 0 && opened ? 1.06 : 1)
                        .opacity(pose.alpha)
                }
            }
            VStack {
                Spacer()
                RoundedRectangle(cornerRadius: 2, style: .continuous)
                    .fill(DarkroomColors.kraft)
                    .frame(width: 52, height: 10)
                    .overlay(alignment: .top) { Rectangle().fill(DarkroomColors.paper.opacity(0.16)).frame(height: 1) }
                    .overlay { RoundedRectangle(cornerRadius: 1).fill(DarkroomColors.label.opacity(0.55)).frame(width: 14, height: 3) }
                    .scaleEffect(x: 1, y: opened ? 0.97 : 1, anchor: .bottom)
            }
        }
        .frame(width: 56, height: 52)
        .opacity(faded ? 0.5 : 1)
        .animation(SpringToken.snappy.animation(), value: opened)
    }

    private func pose(index: Int, count: Int) -> (x: CGFloat, y: CGFloat, tilt: CGFloat, alpha: Double) {
        if index == 0 { return (0, opened ? -7 : 0, -1.5, 1) }
        if opened {
            return index == 1 ? (14, -3, 16, 0.95) : (-14, -3, -18, 0.8)
        }
        if index == 1 { return (6, 2, 8, 0.8) }
        return (-6, 3, -10, 0.62)
    }
}

struct MiniEnvelope: View {
    var asset: PHAsset?
    var pipeline: ThumbnailPipeline

    var body: some View {
        ZStack(alignment: .top) {
            RoundedRectangle(cornerRadius: 1).fill(DarkroomColors.kraftInside).frame(width: 28, height: 32).padding(.top, 6)
            WhitePrint(asset: asset, pipeline: pipeline, width: 20, height: 20, tilt: -4, empty: asset == nil).padding(.top, 1)
            RoundedRectangle(cornerRadius: 1)
                .fill(LinearGradient(colors: [DarkroomColors.kraftHigh, DarkroomColors.kraft], startPoint: .top, endPoint: .bottom))
                .frame(width: 28, height: 22)
                .padding(.top, 18)
        }
        .frame(width: 28, height: 40)
    }
}

@MainActor
final class EnvelopeStripController {
    weak var scrollView: UIScrollView?
    func nudge(_ dx: CGFloat) -> Bool {
        guard let scrollView else { return true }
        let maxX = max(0, scrollView.contentSize.width - scrollView.bounds.width)
        let next = min(maxX, max(0, scrollView.contentOffset.x + dx))
        let hit = abs(next - scrollView.contentOffset.x) < 0.5 && abs(dx) > 0.5
        scrollView.contentOffset.x = next
        return hit
    }

    func scrollToStart(animated: Bool) {
        scrollView?.setContentOffset(.zero, animated: animated)
    }

    var viewportInWindow: CGRect {
        guard let scrollView else { return .zero }
        return scrollView.convert(scrollView.bounds, to: nil)
    }
}

struct EnvelopeStrip<Content: View>: UIViewRepresentable {
    var controller: EnvelopeStripController
    var content: Content

    func makeCoordinator() -> Coordinator { Coordinator(content: content) }

    func makeUIView(context: Context) -> UIScrollView {
        let scroll = UIScrollView()
        scroll.showsHorizontalScrollIndicator = false
        scroll.alwaysBounceHorizontal = true
        scroll.clipsToBounds = false
        scroll.backgroundColor = .clear
        let host = context.coordinator.host
        host.view.backgroundColor = .clear
        host.view.translatesAutoresizingMaskIntoConstraints = false
        scroll.addSubview(host.view)
        NSLayoutConstraint.activate([
            host.view.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor),
            host.view.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor),
            host.view.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor),
            host.view.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor),
            host.view.heightAnchor.constraint(equalTo: scroll.frameLayoutGuide.heightAnchor),
        ])
        controller.scrollView = scroll
        return scroll
    }

    func updateUIView(_ uiView: UIScrollView, context: Context) {
        controller.scrollView = uiView
        context.coordinator.host.rootView = content
    }

    final class Coordinator {
        let host: UIHostingController<Content>
        init(content: Content) {
            host = UIHostingController(rootView: content)
            host.view.backgroundColor = .clear
            if #available(iOS 16.0, *) { host.sizingOptions = .intrinsicContentSize }
        }
    }
}
