package com.winters.cleanphotos.ui.photopager

/**
 * 共享横向预览的缩放参数。
 *
 * 这里集中管理实机调优后的缩放体验，避免 ReviewScreen 和 TrashPreviewOverlay
 * 通过不同路径接入时出现参数漂移。
 */
internal const val PhotoPagerMinZoom = 1.0f
internal const val PhotoPagerDoubleTapZoom = 2.0f
internal const val PhotoPagerMaxZoom = 3.0f
internal const val PhotoPagerPinchSensitivity = 1.4f

/**
 * 根据灵敏度对 pinch 的原始缩放增量做放大或缩小。
 *
 * `rawZoomDelta = 1.0` 表示本帧没有缩放。
 * 当值大于 1.0 时表示放大，小于 1.0 时表示缩小。
 */
internal fun adjustPinchZoomDelta(
    rawZoomDelta: Float,
    pinchSensitivity: Float = PhotoPagerPinchSensitivity
): Float {
    if (rawZoomDelta == 1f) return 1f
    return 1f + (rawZoomDelta - 1f) * pinchSensitivity
}

/**
 * 将缩放值限制在清理页允许的范围内。
 */
internal fun clampPhotoPagerZoom(scale: Float): Float {
    return scale.coerceIn(PhotoPagerMinZoom, PhotoPagerMaxZoom)
}
