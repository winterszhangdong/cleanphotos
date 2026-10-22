package com.winters.cleanphotos.core.util

import java.util.Locale

/**
 * 视频时长：「m:ss」，满 1 小时为「h:mm:ss」。不足 1 秒舍去。
 * 用 Locale.US，避免随系统语言换成非 ASCII 数字。
 */
object DurationFormatter {
    fun format(durationMs: Long): String {
        require(durationMs >= 0) { "durationMs 不能为负：$durationMs" }
        val totalSeconds = durationMs / 1000
        val seconds = totalSeconds % 60
        val totalMinutes = totalSeconds / 60
        val minutes = totalMinutes % 60
        val hours = totalMinutes / 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }
}
