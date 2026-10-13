package com.winters.cleanphotos.domain.model

/**
 * 清理页一次性事件。
 *
 * 一次性事件不能长期保存在 ReviewUiState 中，
 * 避免屏幕旋转或重组后重复启动授权页。
 */
sealed interface ReviewEffect {
    /** 启动系统媒体授权页 */
    data class LaunchSystemMediaAction(
        val request: SystemMediaActionRequest
    ) : ReviewEffect
}
