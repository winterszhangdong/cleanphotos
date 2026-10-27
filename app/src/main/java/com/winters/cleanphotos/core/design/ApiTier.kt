package com.winters.cleanphotos.core.design

import android.annotation.SuppressLint
import android.os.Build
import android.view.HapticFeedbackConstants

/**
 * API 分级的唯一判断处，严格按 docs/ui-design.md 2.6 的分级：
 *
 * | | 26–29 | 30–32 | 33 | 34+ |
 * | 显影 | ColorMatrix | ColorMatrix | + AGSL 颗粒 | + AGSL 颗粒 |
 * | 模糊 | 压暗代替 | 31+ RenderEffect | RenderEffect | RenderEffect |
 * | 触感 | VIRTUAL_KEY | CONFIRM / REJECT | CONFIRM / REJECT | GESTURE_THRESHOLD_* |
 * | 返回 | 共享元素收回 | 共享元素收回 | 共享元素收回 | 预测性返回 + 收回 |
 *
 * 业务代码不得再写 Build.VERSION.SDK_INT 判断。
 */
// 常量按 sdkInt 分支选取，只在对应 API 上被使用，InlinedApi 警告在此处统一抑制。
@SuppressLint("InlinedApi")
data class ApiTier(val sdkInt: Int) {
    init {
        require(sdkInt >= MIN_SDK) { "minSdk 是 $MIN_SDK，收到 $sdkInt" }
    }

    /** AGSL 颗粒（RuntimeShader）。 */
    val hasDevelopGrain: Boolean get() = sdkInt >= 33
    /** RenderEffect 模糊；否则用压暗代替。 */
    val hasBlur: Boolean get() = sdkInt >= 31
    /** 预测性返回跟手预览。 */
    val hasPredictiveBack: Boolean get() = sdkInt >= 34

    /** 过阈值触感。 */
    val thresholdActivateHaptic: Int
        get() = when {
            sdkInt >= 34 -> HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
            sdkInt >= 30 -> HapticFeedbackConstants.CONFIRM
            else -> HapticFeedbackConstants.VIRTUAL_KEY
        }

    /** 拖回阈值内的更轻触感。 */
    val thresholdDeactivateHaptic: Int
        get() = when {
            sdkInt >= 34 -> HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE
            sdkInt >= 30 -> HapticFeedbackConstants.REJECT
            else -> HapticFeedbackConstants.VIRTUAL_KEY
        }

    /** 跨进新的一天（决定 1）：CLOCK_TICK 全版本可用（API 21+）。 */
    val dayChangeHaptic: Int get() = HapticFeedbackConstants.CLOCK_TICK

    /** 拖着照片叠换了目标（袋子 / 面板里的行）：14+ 更轻的分段滴答，更早 CLOCK_TICK。 */
    val hoverTickHaptic: Int
        get() = if (sdkInt >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK

    companion object {
        const val MIN_SDK = 26
        val Current: ApiTier by lazy { ApiTier(Build.VERSION.SDK_INT) }
    }
}
