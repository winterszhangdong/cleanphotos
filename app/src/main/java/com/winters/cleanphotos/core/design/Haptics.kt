package com.winters.cleanphotos.core.design

import android.view.View

/** 触感统一入口，常量按 ApiTier 分级选取。用户关闭系统触感时 View 自己不震，只保留形状变化。 */
object Haptics {
    fun thresholdActivate(view: View, tier: ApiTier = ApiTier.Current) {
        view.performHapticFeedback(tier.thresholdActivateHaptic)
    }

    fun thresholdDeactivate(view: View, tier: ApiTier = ApiTier.Current) {
        view.performHapticFeedback(tier.thresholdDeactivateHaptic)
    }

    /** 照片页长按进入多选。 */
    fun longPress(view: View) {
        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    fun dayChange(view: View, tier: ApiTier = ApiTier.Current) {
        view.performHapticFeedback(tier.dayChangeHaptic)
    }
}
