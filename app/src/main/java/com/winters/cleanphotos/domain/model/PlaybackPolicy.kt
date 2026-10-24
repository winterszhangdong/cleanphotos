package com.winters.cleanphotos.domain.model

enum class PlaybackKind {
    Video,
    MotionPhoto,
}

/**
 * 自动播放只由系统动画开关决定。视频和动态照片都不循环。
 * 系统关闭动画时两者都不自动播放。
 */
data class PlaybackPolicy(
    val autoplay: Boolean,
)

fun playbackPolicy(kind: PlaybackKind, animationsEnabled: Boolean): PlaybackPolicy =
    when (kind) {
        PlaybackKind.Video, PlaybackKind.MotionPhoto -> PlaybackPolicy(autoplay = animationsEnabled)
    }
