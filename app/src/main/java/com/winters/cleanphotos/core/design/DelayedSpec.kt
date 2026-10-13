package com.winters.cleanphotos.core.design

import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorizedFiniteAnimationSpec

/**
 * 给任意有限动画（包括 spring）加起步延迟。用于网格重排「落定弹簧 + 每格 14ms 步长」：
 * Compose 的 spring() 本身没有 delay 参数。延迟期间保持起点值与初速度。
 */
class DelayedSpec<T>(private val delayMillis: Int, private val inner: FiniteAnimationSpec<T>) : FiniteAnimationSpec<T> {
    init {
        require(delayMillis >= 0)
    }

    override fun <V : AnimationVector> vectorize(converter: TwoWayConverter<T, V>): VectorizedFiniteAnimationSpec<V> =
        DelayedVectorizedSpec(delayMillis * NANOS_PER_MILLI, inner.vectorize(converter))

    override fun equals(other: Any?): Boolean =
        other is DelayedSpec<*> && other.delayMillis == delayMillis && other.inner == inner

    override fun hashCode(): Int = 31 * delayMillis + inner.hashCode()
}

private class DelayedVectorizedSpec<V : AnimationVector>(
    private val delayNanos: Long,
    private val inner: VectorizedFiniteAnimationSpec<V>,
) : VectorizedFiniteAnimationSpec<V> {
    override fun getValueFromNanos(playTimeNanos: Long, initialValue: V, targetValue: V, initialVelocity: V): V =
        if (playTimeNanos < delayNanos) initialValue
        else inner.getValueFromNanos(playTimeNanos - delayNanos, initialValue, targetValue, initialVelocity)

    override fun getVelocityFromNanos(playTimeNanos: Long, initialValue: V, targetValue: V, initialVelocity: V): V =
        if (playTimeNanos < delayNanos) initialVelocity
        else inner.getVelocityFromNanos(playTimeNanos - delayNanos, initialValue, targetValue, initialVelocity)

    override fun getDurationNanos(initialValue: V, targetValue: V, initialVelocity: V): Long =
        delayNanos + inner.getDurationNanos(initialValue, targetValue, initialVelocity)
}

private const val NANOS_PER_MILLI = 1_000_000L
