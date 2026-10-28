package com.winters.cleanphotos.feature.allphotos

import com.winters.cleanphotos.core.design.ScopeDialMath
import kotlin.math.abs

/** 范围刻度或 ViewModel 要求换页时，pager 怎么过去。 */
enum class ScopeJump {
    /** 已经在这一页。 */
    Stay,

    /** 相邻：跟手同一条 snappy。 */
    Adjacent,

    /** 隔了至少两页：内容直接换，刻度自己滑。 */
    Far,

    /** 系统移除动画：直接落到终点，不弹簧、不淡入。 */
    Snap,
}

fun scopeJump(from: Int, to: Int, animationsEnabled: Boolean): ScopeJump {
    val distance = abs(to - from)
    return when {
        distance == 0 -> ScopeJump.Stay
        !animationsEnabled -> ScopeJump.Snap
        distance == 1 -> ScopeJump.Adjacent
        else -> ScopeJump.Far
    }
}

/**
 * 页上暗室色遮罩的透明度。拖动时跟 |页偏移|，隔远跳转时跟淡入（1 → 0）。
 * 两者同时有时取更暗的那个。结果乘 [ScopeDialMath.PAGE_DIM]，等效亮度 1→0.7。
 */
fun pageShadeAlpha(pageOffset: Float, enter: Float): Float {
    val offset = abs(pageOffset).coerceIn(0f, 1f)
    val enterDim = (1f - enter).coerceIn(0f, 1f)
    return maxOf(offset, enterDim) * ScopeDialMath.PAGE_DIM
}
