package com.winters.cleanphotos.core.util

import android.os.SystemClock
import android.util.Log
import com.winters.cleanphotos.BuildConfig

/**
 * 清理页验证用的结构化日志（仅 debug 包输出，tag = CPTrace）。
 * 用于对照录屏核对：显影是否只在首次磁盘解码触发、跨天 CLOCK_TICK 次数、下一张出场时长。
 */
object ReviewTrace {
    private const val TAG = "CPTrace"

    fun log(event: String, vararg fields: Pair<String, Any?>) {
        if (!BuildConfig.DEBUG) return
        val body = fields.joinToString(" ") { (k, v) -> "$k=$v" }
        Log.d(TAG, "t=${SystemClock.uptimeMillis()} $event $body")
    }
}
