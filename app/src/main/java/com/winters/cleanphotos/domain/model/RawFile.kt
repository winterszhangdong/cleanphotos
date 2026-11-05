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

fun isRawFileName(displayName: String?): Boolean {
    if (displayName.isNullOrEmpty()) return false
    val dot = displayName.lastIndexOf('.')
    if (dot < 0 || dot == displayName.lastIndex) return false
    return displayName.substring(dot + 1).lowercase() in RAW_EXTENSIONS
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
