import AVFoundation
import SwiftUI

/// 视频层。首帧之前透明，底下的静图照常显示。动态照片播完后按 short 淡出。
struct PlaybackSurface: UIViewRepresentable {
    let player: AVPlayer
    let fadeOut: Bool
    let animateFade: Bool

    func makeUIView(context: Context) -> PlayerHost {
        let view = PlayerHost()
        view.attach(player)
        return view
    }

    func updateUIView(_ view: PlayerHost, context: Context) {
        view.attach(player)
        view.setFadeOut(fadeOut, animated: animateFade)
    }
}

final class PlayerHost: UIView {
    override static var layerClass: AnyClass { AVPlayerLayer.self }
    private var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    private var readyObs: NSKeyValueObservation?
    private var ready = false
    private var fading = false

    override init(frame: CGRect) {
        super.init(frame: frame)
        isOpaque = false
        backgroundColor = .clear
        isUserInteractionEnabled = false
        playerLayer.videoGravity = .resizeAspect
        playerLayer.isOpaque = false
        playerLayer.backgroundColor = UIColor.clear.cgColor
        alpha = 0
    }

    required init?(coder: NSCoder) { fatalError() }

    func attach(_ player: AVPlayer) {
        guard playerLayer.player !== player else { return }
        ready = false
        fading = false
        alpha = 0
        playerLayer.player = player
        readyObs = playerLayer.observe(\.isReadyForDisplay, options: [.initial, .new]) { [weak self] layer, _ in
            let ready = layer.isReadyForDisplay
            DispatchQueue.main.async { self?.readyChanged(ready) }
        }
    }

    func setFadeOut(_ on: Bool, animated: Bool) {
        if on {
            guard !fading else { return }
            fading = true
            guard animated else { alpha = 0; return }
            let timing = UICubicTimingParameters(controlPoint1: CGPoint(x: 0.2, y: 0), controlPoint2: CGPoint(x: 0, y: 1))
            let anim = UIViewPropertyAnimator(duration: DarkroomMotion.short, timingParameters: timing)
            anim.addAnimations { self.alpha = 0 }
            anim.startAnimation()
        } else if fading || alpha == 0 {
            fading = false
            alpha = ready ? 1 : 0
        }
    }

    private func readyChanged(_ ready: Bool) {
        self.ready = ready
        if !fading { alpha = ready ? 1 : 0 }
    }
}

struct PlaybackCenterButton: View {
    let visible: Bool
    let showPause: Bool
    let label: String
    let action: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Button(action: action) {
            Image(systemName: showPause ? "pause.fill" : "play.fill")
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(DarkroomColors.paper)
                .offset(x: showPause ? 0 : 1)
                .frame(width: DarkroomDimens.playbackCenter, height: DarkroomDimens.playbackCenter)
                .background {
                    Circle()
                        .fill(DarkroomColors.glass)
                        .overlay(Circle().strokeBorder(DarkroomColors.glassRim, lineWidth: 1))
                }
        }
        .buttonStyle(.plain)
        .scaleEffect(visible ? 1 : DarkroomMotion.revealScale)
        .opacity(visible ? 1 : 0)
        .animation(reduceMotion ? nil : DarkroomMotion.shortCurve, value: visible)
        .accessibilityLabel(Text(label))
        .accessibilityIdentifier("playback-center")
        .allowsHitTesting(visible)
    }
}

struct PlaybackMotionPill: View {
    let title: String
    let label: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: DarkroomDimens.playbackPillGap) {
                ZStack {
                    Circle().stroke(DarkroomColors.paper, lineWidth: 1.5)
                    Circle().fill(DarkroomColors.paper).frame(width: 5, height: 5)
                }
                .frame(width: DarkroomDimens.playbackMotionIcon, height: DarkroomDimens.playbackMotionIcon)
                Text(title)
                    .font(Font.custom("NotoSansSC-Medium", size: 13))
            }
            .foregroundStyle(DarkroomColors.paper)
            .padding(.horizontal, DarkroomDimens.playbackPillPadH)
            .frame(height: 40)
            .background {
                Capsule()
                    .fill(DarkroomColors.glass)
                    .overlay(Capsule().strokeBorder(DarkroomColors.glassRim, lineWidth: 1))
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(label))
        .accessibilityIdentifier("playback-motion")
    }
}

/// 进度行自己轮询播放头。拖动和点按都在这一行里，不进照片手势。
struct PlaybackProgressRow: View {
    let engine: PlaybackEngine
    let muted: Bool
    let zh: Bool
    let onMute: () -> Void
    let onSeeked: () -> Void

    @State private var position: Double = 0
    @State private var duration: Double = 0
    @State private var scrub: Double?

    var body: some View {
        let fraction = shownFraction
        HStack(spacing: DarkroomDimens.playbackGap) {
            Text(ScopeRules.formatDuration(seconds: shownPosition))
                .font(DarkroomFonts.plexMono(size: 10.5))
                .foregroundStyle(DarkroomColors.paper.opacity(0.62))
                .frame(width: 52, alignment: .leading)
            GeometryReader { geo in
                let width = max(geo.size.width, 1)
                ZStack {
                    Capsule().fill(DarkroomColors.playbackTrack).frame(height: DarkroomDimens.playbackTrackHeight)
                    Capsule()
                        .fill(DarkroomColors.paper)
                        .frame(width: max(DarkroomDimens.playbackTrackHeight, width * fraction), height: DarkroomDimens.playbackTrackHeight)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Circle()
                        .fill(DarkroomColors.paper)
                        .frame(
                            width: scrub == nil ? DarkroomDimens.playbackThumb : DarkroomDimens.playbackThumbDrag,
                            height: scrub == nil ? DarkroomDimens.playbackThumb : DarkroomDimens.playbackThumbDrag
                        )
                        .offset(x: thumbX(width: width, fraction: fraction))
                }
                .frame(maxHeight: .infinity)
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { value in
                            scrub = min(1, max(0, value.location.x / width))
                        }
                        .onEnded { value in
                            let fraction = min(1, max(0, value.location.x / width))
                            scrub = nil
                            if engine.seekFraction(fraction) { onSeeked() }
                        }
                )
                .accessibilityElement()
                .accessibilityLabel(Text(PlaybackCopy.progress(zh: zh)))
                .accessibilityValue(Text(ScopeRules.formatDuration(seconds: shownPosition)))
                .accessibilityAdjustableAction { direction in
                    let now = duration > 0 ? position / duration : 0
                    let next = direction == .increment ? min(1, now + 0.05) : max(0, now - 0.05)
                    if engine.seekFraction(next) { onSeeked() }
                }
            }
            .frame(height: DarkroomDimens.playbackRowHeight)
            Text(ScopeRules.formatDuration(seconds: duration))
                .font(DarkroomFonts.plexMono(size: 10.5))
                .foregroundStyle(DarkroomColors.paper.opacity(0.62))
                .frame(width: 52, alignment: .trailing)
            Button(action: onMute) {
                Image(systemName: muted ? "speaker.slash.fill" : "speaker.wave.2.fill")
                    .font(.system(size: DarkroomDimens.playbackSoundIcon, weight: .medium))
                    .foregroundStyle(DarkroomColors.paper)
                    .frame(width: 40, height: 40)
                    .background {
                        Circle()
                            .fill(DarkroomColors.glass)
                            .overlay(Circle().strokeBorder(DarkroomColors.glassRim, lineWidth: 1))
                    }
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text(muted ? PlaybackCopy.soundOn(zh: zh) : PlaybackCopy.soundOff(zh: zh)))
        }
        .frame(height: DarkroomDimens.playbackRowHeight)
        .accessibilityIdentifier("playback-progress")
        .task {
            while !Task.isCancelled {
                if scrub == nil {
                    position = engine.currentSeconds
                    duration = engine.durationSeconds
                }
                try? await Task.sleep(nanoseconds: UInt64(PlaybackEngine.positionPoll * 1_000_000_000))
            }
        }
    }

    private var shownPosition: Double {
        if let scrub, duration > 0 { return duration * scrub }
        return position
    }

    private var shownFraction: Double {
        if let scrub { return scrub }
        guard duration > 0 else { return 0 }
        return min(1, max(0, position / duration))
    }

    private func thumbX(width: CGFloat, fraction: Double) -> CGFloat {
        let travel = width
        return (CGFloat(fraction) - 0.5) * travel
    }
}

/// 停稳后等开播延迟再播。没停稳就暂停。系统减弱动态效果时不自动播。
struct PlaybackAutoplayGate: View {
    @ObservedObject var engine: PlaybackEngine
    let settled: Bool
    let animationsEnabled: Bool

    var body: some View {
        Color.clear
            .frame(width: 0, height: 0)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
            .task(id: taskID) { await run() }
            .onChange(of: settled) { _, on in
                if !on { engine.pauseForGesture() }
            }
    }

    private var taskID: String {
        let id = engine.loaded?.id ?? ""
        let kind = engine.loaded?.kind == .motion ? "m" : "v"
        return "\(id)-\(kind)-\(settled)-\(engine.resumed)-\(engine.userPaused)-\(engine.ended)-\(engine.loadFailed)-\(animationsEnabled)"
    }

    private func run() async {
        guard settled, engine.resumed, engine.loaded != nil, !engine.loadFailed else { return }
        guard let kind = engine.loaded?.kind else { return }
        let policy = PlaybackRules.policy(kind: kind, animationsEnabled: animationsEnabled)
        guard policy.autoplay, !engine.userPaused, !engine.ended else { return }
        let start = Date()
        while !engine.hasItem {
            if engine.loadFailed || Task.isCancelled { return }
            try? await Task.sleep(nanoseconds: 30_000_000)
        }
        guard !Task.isCancelled, !engine.userPaused, !engine.ended, !engine.loadFailed else { return }
        let remain = DarkroomMotion.playbackStartDelay - Date().timeIntervalSince(start)
        if remain > 0 {
            try? await Task.sleep(nanoseconds: UInt64(remain * 1_000_000_000))
        }
        guard !Task.isCancelled else { return }
        engine.playFromAutoplay()
    }
}
