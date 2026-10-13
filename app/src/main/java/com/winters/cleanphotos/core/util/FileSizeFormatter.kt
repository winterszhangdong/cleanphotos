package com.winters.cleanphotos.core.util

import java.util.Locale

/**
 * 文件大小文案：「3.1 MB」「31 MB」「1.2 GB」。
 * 1024 进制；数值 < 10 保留一位小数，≥ 10 取整；最小单位 KB。
 */
object FileSizeFormatter {
    private val units = listOf("KB", "MB", "GB", "TB")

    fun format(bytes: Long): String {
        require(bytes >= 0) { "bytes 不能为负：$bytes" }
        var value = bytes / 1024.0
        var unit = 0
        // 按四舍五入后的值进位，避免出现「1024 KB」「10.0 MB」
        while (value >= 1023.5 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        val text = if (value < 9.95) String.format(Locale.US, "%.1f", value) else String.format(Locale.US, "%.0f", value)
        return "$text ${units[unit]}"
    }

    /** 数字与单位分开（「清空后可释放 1.2 GB」里数字用 .num 大字、单位用小字）。 */
    fun parts(bytes: Long): Pair<String, String> {
        val text = format(bytes)
        val space = text.lastIndexOf(' ')
        return text.substring(0, space) to text.substring(space + 1)
    }
}
