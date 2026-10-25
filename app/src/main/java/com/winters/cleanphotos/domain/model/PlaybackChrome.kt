package com.winters.cleanphotos.domain.model

/** 视频页只有这四态。手势把播放器按住、但用户没有暂停时，仍算 [Playing]。 */
enum class PlaybackPhase {
    Playing,
    UserPaused,
    NotStarted,
    Ended,
}

/** 详情怎么跟播放态走。静态图和动态照片保持页面自己的开关。 */
enum class DetailsMode {
    Hidden,
    Shown,
    Keep,
}

data class PlaybackChrome(
    val showCenter: Boolean,
    val details: DetailsMode,
)

/**
 * 用户暂停优先，其次播完，其次还没开始。
 * [playing] 不参与判定：已经开始、这一刻却没在播（手势暂停）仍是 [PlaybackPhase.Playing]。
 */
@Suppress("UNUSED_PARAMETER")
fun playbackPhase(started: Boolean, userPaused: Boolean, ended: Boolean, playing: Boolean): PlaybackPhase =
    when {
        userPaused -> PlaybackPhase.UserPaused
        ended -> PlaybackPhase.Ended
        !started -> PlaybackPhase.NotStarted
        else -> PlaybackPhase.Playing
    }

/**
 * 停稳后、自动播放还没开始的那一段延迟。
 * 这段不算「未开始」：中心键不出现，避免每落到一段视频上都闪一下。
 * 系统关闭动画时 [animationsEnabled] 为 false，这里返回 false，中心键直接出现。
 */
fun awaitingAutoplay(
    animationsEnabled: Boolean,
    settled: Boolean,
    resumed: Boolean,
    started: Boolean,
    userPaused: Boolean,
    ended: Boolean,
): Boolean = animationsEnabled && settled && resumed && !started && !userPaused && !ended

fun playbackChrome(
    isVideo: Boolean,
    phase: PlaybackPhase,
    gestureActive: Boolean,
    pinnedOrExiting: Boolean,
    awaitingAutoplay: Boolean = false,
): PlaybackChrome {
    val warmingUp = awaitingAutoplay && phase == PlaybackPhase.NotStarted
    val showCenter = isVideo && !gestureActive && !pinnedOrExiting && phase != PlaybackPhase.Playing && !warmingUp
    val details = when {
        !isVideo -> DetailsMode.Keep
        gestureActive || pinnedOrExiting -> DetailsMode.Hidden
        phase == PlaybackPhase.Playing -> DetailsMode.Hidden
        phase == PlaybackPhase.UserPaused -> DetailsMode.Shown
        else -> DetailsMode.Keep
    }
    return PlaybackChrome(showCenter, details)
}

sealed interface ReviewPhotoTap {
    /** 视频单击：切换播放。 [showDetails] 是切换前是否正在播放，也就是切换后的用户暂停。 */
    data class TogglePlay(val showDetails: Boolean) : ReviewPhotoTap

    /** 静态图 / 动态照片单击：只切换详情。 */
    data class ToggleDetails(val showDetails: Boolean) : ReviewPhotoTap
}

fun reviewPhotoTap(isVideo: Boolean, playing: Boolean, detailsOpen: Boolean): ReviewPhotoTap =
    if (isVideo) {
        ReviewPhotoTap.TogglePlay(showDetails = playing)
    } else {
        ReviewPhotoTap.ToggleDetails(showDetails = !detailsOpen)
    }

/** 视频播完：回到第 0 帧并暂停，中心播放键出现。 */
data class VideoEndedPose(
    val positionMs: Long,
    val showCenter: Boolean,
)

fun videoEndedPose(): VideoEndedPose = VideoEndedPose(positionMs = 0L, showCenter = true)

/**
 * 用户拖动或点按进度之后的播放态。
 * 不论之前是暂停、播完还是播放中，都从该位置开始播放。
 */
data class UserSeekPose(
    val ended: Boolean,
    val userPaused: Boolean,
    val started: Boolean,
)

fun poseAfterUserSeek(): UserSeekPose =
    UserSeekPose(ended = false, userPaused = false, started = true)
