package com.winters.cleanphotos.feature.allphotos

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.mutableFloatStateOf
import com.winters.cleanphotos.core.design.MotionSprings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 照片页顶栏 + 范围行的 quick return 偏移。
 * 0 是完全展开，负到 [max] 是完全收起。只在 graphicsLayer 里读 [offsetPx]。
 */
class PhotoChromeCollapse {
    private val px = mutableFloatStateOf(0f)
    private var settleJob: Job? = null

    val offsetPx: Float get() = px.floatValue

    /** 跟手。正 dy 展开，负 dy 收起。 */
    fun follow(dy: Float, max: Float) {
        settleJob?.cancel()
        settleJob = null
        px.floatValue = (px.floatValue + dy).coerceIn(-max, 0f)
    }

    fun snapExpanded() {
        settleJob?.cancel()
        settleJob = null
        px.floatValue = 0f
    }

    /**
     * 停在半途时吸到完全展开或完全收起。过半收起，否则展开。
     * 列表在顶部时强制展开。[animate] 为 false（系统关闭动画）时直接跳到终点。
     */
    fun settle(scope: CoroutineScope, max: Float, atTop: Boolean, animate: Boolean) {
        if (atTop || max <= 0f) {
            snapExpanded()
            return
        }
        val current = px.floatValue
        if (current == 0f || current == -max) return
        val target = if (-current > max / 2f) -max else 0f
        if (!animate) {
            settleJob?.cancel()
            settleJob = null
            px.floatValue = target
            return
        }
        settleJob?.cancel()
        settleJob = scope.launch {
            val anim = Animatable(current)
            anim.animateTo(target, MotionSprings.Settle.spec()) {
                px.floatValue = value
            }
        }
    }
}
