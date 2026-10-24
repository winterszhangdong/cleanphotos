package com.winters.cleanphotos.domain.model

enum class PlaybackKind {
    Video,
    MotionPhoto,
}

/**
 * 自动播放和循环只由媒体类型和系统动画开关决定。
 * 视频循环；动态照片播一次。系统关闭动画时两者都不自动播放。
 */
data class PlaybackPolicy(
    val autoplay: Boolean,
    val loop: Boolean,
)

fun playbackPolicy(kind: PlaybackKind, animationsEnabled: Boolean): PlaybackPolicy =
    PlaybackPolicy(
        autoplay = animationsEnabled,
        loop = kind == PlaybackKind.Video,
    )
