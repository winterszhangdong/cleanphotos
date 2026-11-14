package com.winters.cleanphotos.domain.model

/**
 * 相机原始文件。只看文件名扩展名，不看 MIME：系统经常把 DNG 标成 `application/octet-stream`。
 * 词表是 Android 会索引的相机原始格式，外加 CR3、GPR。
 */
private val RAW_EXTENSIONS = setOf(
    "dng",
    "cr2",
    "cr3",
    "nef",
    "nrw",
    "arw",
    "srf",
    "sr2",
    "orf",
    "raf",
    "pef",
    "rw2",
    "srw",
    "gpr",
)

fun isRawFileName(displayName: String?): Boolean = fileExtension(displayName) in RAW_EXTENSIONS

/**
 * 长边不超过这个值的 PNG 按屏幕解原图。手机截图到不了。
 * 再长，或者根本不知道边长，一倍先出系统缩略图，停在当前张再解到屏幕大小。
 */
const val PNG_SCREEN_MAX_EDGE = 4096

fun isPngFileName(displayName: String?): Boolean = fileExtension(displayName) == "png"

/**
 * 一倍时不该直接解原图的 PNG。不知道边长、边长不是正数、或长边超过 [PNG_SCREEN_MAX_EDGE]。
 * 不是 PNG 时不是。
 */
fun isLargePng(displayName: String?, width: Int?, height: Int?): Boolean {
    if (!isPngFileName(displayName)) return false
    val w = width ?: return true
    val h = height ?: return true
    if (w <= 0 || h <= 0) return true
    return maxOf(w, h) > PNG_SCREEN_MAX_EDGE
}

/**
 * 一倍时先显示的那张从哪来。
 * JPEG / HEIC 和不大的 PNG 直接解原图。RAW 读文件里的预览。大 PNG 用系统缩略图。
 */
enum class GlanceKind { Original, RawEmbedded, SystemThumb }

fun glanceKind(displayName: String?, width: Int?, height: Int?): GlanceKind = when {
    isRawFileName(displayName) -> GlanceKind.RawEmbedded
    isLargePng(displayName, width, height) -> GlanceKind.SystemThumb
    else -> GlanceKind.Original
}

/**
 * 放大超过 1 倍时要另解一张更清楚原图。
 * RAW 会整张去马赛克。大 PNG 要整文件解压。不大的 PNG 已经按屏幕解过，和 JPEG 一样不再另解。
 * 不传宽高时，PNG 当成大图。
 */
fun isHeavyStill(displayName: String?, width: Int? = null, height: Int? = null): Boolean =
    isRawFileName(displayName) || isLargePng(displayName, width, height)

/**
 * 当前该显示哪一张。已经解好的更清楚图不再退回更糊的一倍图。
 * 放大且 3 倍图好了，用 3 倍。否则屏幕大小的原图好了就用它。再否则 3 倍图好了也用它。都不行就用一倍那张。
 */
enum class StillFrame { Glance, Screen, Sharp }

fun stillFrame(
    zoomed: Boolean,
    eligibleScreen: Boolean,
    eligibleSharp: Boolean,
    screenReady: Boolean,
    sharpReady: Boolean,
): StillFrame = when {
    zoomed && eligibleSharp && sharpReady -> StillFrame.Sharp
    eligibleScreen && screenReady -> StillFrame.Screen
    eligibleSharp && sharpReady -> StillFrame.Sharp
    else -> StillFrame.Glance
}

/**
 * 放大到上限时要的解码边长。3 和清理页缩放上限（`GestureTokens.ZOOM_MAX`）一致，领域层不引用设计 token。
 * 边长至少 1。
 */
const val SHARP_STILL_SCALE = 3

fun sharpStillSize(width: Int, height: Int, scale: Int = SHARP_STILL_SCALE): Pair<Int, Int> =
    (width * scale).coerceAtLeast(1) to (height * scale).coerceAtLeast(1)

private fun fileExtension(displayName: String?): String? {
    if (displayName.isNullOrEmpty()) return null
    val dot = displayName.lastIndexOf('.')
    if (dot < 0 || dot == displayName.lastIndex) return null
    return displayName.substring(dot + 1).lowercase()
}

/**
 * 左下角一行。空的不写，用「 · 」连起来。三个都空时返回 null，调用方不画。
 * [rawLabel] 固定是「RAW」；[motionLabel] 用界面语言里的「动态」。
 */
fun photoMarkLine(rawLabel: String?, motionLabel: String?, sizeLabel: String? = null): String? {
    val parts = listOfNotNull(rawLabel, motionLabel, sizeLabel).filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    return parts.joinToString(" · ")
}
