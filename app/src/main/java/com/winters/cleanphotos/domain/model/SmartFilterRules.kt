package com.winters.cleanphotos.domain.model

/**
 * 智能筛选的判定。三个模式与「全部照片 / 仅未看过」互斥，且不看「看过」状态。
 * 匹配都不区分大小写；路径和文件夹名为 null 时不命中。
 */
object SmartFilterRules {
    /** 图片和视频共用的大文件阈值。 */
    const val LARGE_FILE_MIN_BYTES = 10L * 1024 * 1024

    private val screenshotNeedles = listOf("screenshot", "截屏", "截图")
    private val chatNeedles = listOf("weixin", "whatsapp", "telegram")
    private const val QQ_PREFIX = "Pictures/QQ/"

    fun isScreenshot(photo: PhotoItem): Boolean =
        containsAny(photo.relativePath, screenshotNeedles) || containsAny(photo.bucketName, screenshotNeedles)

    /** size 为 null 不算大文件。 */
    fun isLargeFile(photo: PhotoItem): Boolean {
        val size = photo.size ?: return false
        return size >= LARGE_FILE_MIN_BYTES
    }

    fun isChatApp(photo: PhotoItem): Boolean {
        val path = photo.relativePath ?: return false
        return containsAny(path, chatNeedles) || path.startsWith(QQ_PREFIX, ignoreCase = true)
    }

    fun matches(photo: PhotoItem, mode: PhotoFilterMode): Boolean = when (mode) {
        PhotoFilterMode.Screenshots -> isScreenshot(photo)
        PhotoFilterMode.LargeFiles -> isLargeFile(photo)
        PhotoFilterMode.ChatApps -> isChatApp(photo)
        PhotoFilterMode.OnThisDay -> error("那年今天不走 SmartFilterRules")
        PhotoFilterMode.AllPhotos, PhotoFilterMode.UnseenOnly ->
            error("$mode 不是智能筛选")
    }

    private fun containsAny(value: String?, needles: List<String>): Boolean {
        if (value == null) return false
        return needles.any { value.contains(it, ignoreCase = true) }
    }
}
