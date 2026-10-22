package com.winters.cleanphotos.data.datasource

/**
 * 把 MediaStore `DATA` 绝对路径收成与 `RELATIVE_PATH` 相同的目录（以 `/` 结尾）。
 * [storageRoot] 是外部存储根，例如 `/storage/emulated/0`。
 * 不在该根下、或文件直接放在根目录时返回 null（没有可对齐的目录）。
 */
internal fun relativePathFromData(dataPath: String?, storageRoot: String): String? {
    if (dataPath.isNullOrEmpty()) return null
    require(storageRoot.isNotEmpty()) { "storageRoot 为空" }
    val root = storageRoot.trimEnd('/')
    val prefix = "$root/"
    if (!dataPath.startsWith(prefix)) return null
    val relativeFile = dataPath.substring(prefix.length)
    val slash = relativeFile.lastIndexOf('/')
    if (slash < 0) return null
    return relativeFile.substring(0, slash + 1)
}

/** MediaStore 的 RELATIVE_PATH 偶发不带结尾 `/`；空串表示根目录，没有文件夹。 */
internal fun normalizeRelativePath(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    return if (raw.endsWith("/")) raw else "$raw/"
}
