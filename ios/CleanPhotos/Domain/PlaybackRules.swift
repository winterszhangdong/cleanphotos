import Foundation

/// 视频和动态照片（实况照片，以及嵌在 JPEG / HEIC 里的厂商视频）的播放判定。和安卓 `PlaybackPolicy` / `PlaybackChrome` 同一套。
/// 不读文件、不碰播放器。
enum PlaybackKind: Equatable {
    case video
    case motion
}

struct PlaybackPolicy: Equatable {
    var autoplay: Bool
}

enum PlaybackPhase: Equatable {
    case playing
    case userPaused
    case notStarted
    case ended
}

/// 详情怎么跟播放态走。静态图和动态照片保持页面自己的开关。
enum PlaybackDetails: Equatable {
    case hidden
    case shown
    case keep
}

struct PlaybackChromeState: Equatable {
    var showCenter: Bool
    /// 底部播放进度行。和中心键一起出现。
    var showProgress: Bool
    /// 中心键画暂停。否则画播放。
    var showPauseIcon: Bool
    var details: PlaybackDetails
}

enum ReviewPlaybackTap: Equatable {
    /// 视频播放中单击：只切换暂停键和进度行，不改播放。
    case toggleTransport(Bool)
    /// 静态图 / 动态照片单击：只切换详情。
    case toggleDetails(Bool)
}

enum TrashPlaybackTap: Equatable {
    /// 垃圾桶里只有正在播的视频单击才切换控件。动态照片和静态图单击不做任何事。
    case toggleTransport(Bool)
}

struct VideoEndedPose: Equatable {
    var positionMs: Int
    var showCenter: Bool
}

struct UserSeekPose: Equatable {
    var ended: Bool
    var userPaused: Bool
    var started: Bool
}

enum PlaybackRules {
    /// 自动播放只由系统动画开关决定。视频和动态照片都不循环。
    static func policy(kind: PlaybackKind, animationsEnabled: Bool) -> PlaybackPolicy {
        switch kind {
        case .video, .motion:
            return PlaybackPolicy(autoplay: animationsEnabled)
        }
    }

    /// 用户暂停优先，其次播完，其次还没开始。
    /// `playing` 不参与判定：已经开始、这一刻却没在播（手势暂停）仍是播放中。
    static func phase(started: Bool, userPaused: Bool, ended: Bool, playing: Bool) -> PlaybackPhase {
        _ = playing
        if userPaused { return .userPaused }
        if ended { return .ended }
        if !started { return .notStarted }
        return .playing
    }

    /// 停稳后、自动播放还没开始的那一段延迟。这段不算「未开始」：中心键不出现。
    /// 系统关闭动画时返回 false，中心键直接出现。
    static func awaitingAutoplay(
        animationsEnabled: Bool,
        settled: Bool,
        resumed: Bool,
        started: Bool,
        userPaused: Bool,
        ended: Bool
    ) -> Bool {
        animationsEnabled && settled && resumed && !started && !userPaused && !ended
    }

    static func chrome(
        isVideo: Bool,
        phase: PlaybackPhase,
        gestureActive: Bool,
        pinnedOrExiting: Bool,
        awaitingAutoplay: Bool = false,
        transportShown: Bool = false
    ) -> PlaybackChromeState {
        let warmingUp = awaitingAutoplay && phase == .notStarted
        let blocked = gestureActive || pinnedOrExiting
        let showPause = isVideo && !blocked && phase == .playing && transportShown
        let showPlay = isVideo && !blocked && phase != .playing && !warmingUp
        let details: PlaybackDetails
        if !isVideo {
            details = .keep
        } else if blocked {
            details = .hidden
        } else if phase == .playing {
            details = .hidden
        } else if phase == .userPaused {
            details = .shown
        } else {
            details = .keep
        }
        return PlaybackChromeState(
            showCenter: showPause || showPlay,
            showProgress: showPause || showPlay,
            showPauseIcon: showPause,
            details: details
        )
    }

    /// 视频没在播时返回 nil：开播和继续只走中心键，单击空白不暂停也不播放。
    static func reviewPhotoTap(
        isVideo: Bool,
        playing: Bool,
        transportShown: Bool,
        detailsOpen: Bool
    ) -> ReviewPlaybackTap? {
        if isVideo && playing { return .toggleTransport(!transportShown) }
        if isVideo { return nil }
        return .toggleDetails(!detailsOpen)
    }

    static func trashPhotoTap(isVideo: Bool, playing: Bool, transportShown: Bool) -> TrashPlaybackTap? {
        guard isVideo && playing else { return nil }
        return .toggleTransport(!transportShown)
    }

    /// 视频播完：回到第 0 帧并暂停，中心播放键出现。
    static func videoEndedPose() -> VideoEndedPose {
        VideoEndedPose(positionMs: 0, showCenter: true)
    }

    /// 用户拖动或点按进度之后，不论之前是暂停、播完还是播放中，都从该位置开始播放。
    static func poseAfterUserSeek() -> UserSeekPose {
        UserSeekPose(ended: false, userPaused: false, started: true)
    }
}
