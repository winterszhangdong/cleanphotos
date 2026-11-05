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
 * 清理页一倍时不解原图的静态图。
 * JPEG / HEIC 可以边解码边缩小；相机 RAW 会整张去马赛克，PNG 要整文件解压。
 * 只看最后一个扩展名，不看 MIME。没有扩展名时不是。
 */
fun isHeavyStill(displayName: String?): Boolean {
    val ext = fileExtension(displayName) ?: return false
    return ext in RAW_EXTENSIONS || ext == "png"
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
