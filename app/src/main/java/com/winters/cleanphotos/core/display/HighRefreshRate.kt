package com.winters.cleanphotos.core.display

import android.app.Activity
import android.os.Build
import android.view.Display

/**
 * 向系统申请当前屏幕支持的最高刷新率（120 / 90 / …）。
 * - API 23+：[WindowManager.LayoutParams.preferredDisplayModeId] = 刷新率最高的 mode
 * - 同时写 [WindowManager.LayoutParams.preferredRefreshRate] 作兜底
 * 模拟器通常仍锁 60；真机高刷屏会升到最高档。不支持时静默忽略。
 */
fun Activity.requestHighestRefreshRate() {
    val window = window ?: return
    val currentDisplay: Display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        display ?: return
    } else {
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay
    }
    val best = currentDisplay.supportedModes.maxByOrNull { it.refreshRate } ?: return
    val attrs = window.attributes
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        attrs.preferredDisplayModeId = best.modeId
    }
    @Suppress("DEPRECATION")
    attrs.preferredRefreshRate = best.refreshRate
    window.attributes = attrs
}
