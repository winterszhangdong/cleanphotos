package com.winters.cleanphotos.core.design

import android.annotation.SuppressLint
import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.graphicsLayer
import coil.decode.DataSource

/**
 * 显影状态。进度 0..1 为线性时间，各通道曲线由 [DevelopProfile] 决定。
 * 只在绘制阶段读取进度，显影期间不触发重组，也不拦截手势（本 Modifier 不处理指针事件）。
 */
@Stable
class DevelopState internal constructor(val profile: DevelopProfile, developed: Boolean) {
    internal val progress = Animatable(if (developed) 1f else 0f)

    val isRunning: Boolean get() = progress.isRunning

    /** 从暗处显影到终态。系统关闭动画时 MotionDurationScale = 0，直接跳终态。 */
    suspend fun develop(delayMs: Int = 0) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(profile.durationMs, delayMillis = delayMs, easing = LinearEasing))
    }

    /** 直接显示终态（命中缓存、往回翻、左右翻）。 */
    suspend fun showDeveloped() {
        progress.snapTo(1f)
    }
}

@Composable
fun rememberDevelopState(profile: DevelopProfile, developed: Boolean = true): DevelopState =
    remember(profile) { DevelopState(profile, developed) }

/**
 * 决定 7：单张显影只在第一次从磁盘解码时触发，命中内存缓存一律不显影。
 */
fun DataSource.requiresDevelop(): Boolean = when (this) {
    DataSource.MEMORY_CACHE, DataSource.MEMORY -> false
    DataSource.DISK, DataSource.NETWORK -> true
}

/**
 * 显影 Modifier：色调与 scale 都是 graphicsLayer 属性（colorFilter 让这一层走离屏合成）。
 * 内容只在自身变化时重画一次，显影每帧只改合成参数——不再每帧 saveLayer 重画整张图（P8 卡顿排查：
 * 网格首屏几十格同时显影、清理页 1440p 全屏大图，每帧离屏重画是主要的 GPU 开销）。
 * API 33+ 且 profile 有颗粒时，在这一层外面叠一层 AGSL 颗粒（只在显影进行中绘制，不受色调影响）。
 */
// GrainShader 只在 tier.hasDevelopGrain（ApiTier 统一分级，API 33+）为 true 时创建。
@SuppressLint("NewApi")
fun Modifier.develop(state: DevelopState, tier: ApiTier = ApiTier.Current): Modifier {
    val toned = graphicsLayer {
        val p = state.progress.value
        if (p >= 1f) {
            colorFilter = null
            scaleX = 1f
            scaleY = 1f
            return@graphicsLayer
        }
        val v = DevelopMatrix.values(state.profile, p)
        // 每帧新数组：Compose 的 ColorFilter 按矩阵内容判等，复用同一个数组会让 layer 认为「没变」而不更新
        colorFilter = ColorFilter.colorMatrix(ColorMatrix(DevelopMatrix.compose(v.brightness, v.contrast, v.saturation)))
        scaleX = v.scale
        scaleY = v.scale
    }
    if (!tier.hasDevelopGrain || state.profile.grainFrom <= 0f) return this.then(toned)
    return this
        .drawWithCache {
            val grain = GrainShader.create()
            onDrawWithContent {
                drawContent()
                val p = state.progress.value
                if (p >= 1f) return@onDrawWithContent
                val g = DevelopMatrix.values(state.profile, p).grain
                if (g > 0f) {
                    grain.update(size.width, size.height, p)
                    drawRect(brush = grain.brush, alpha = g)
                }
            }
        }
        .then(toned)
}

/** AGSL 颗粒，仅 API 33+。 */
@RequiresApi(33)
private class GrainShader {
    private val shader = RuntimeShader(SOURCE)
    val brush = ShaderBrush(shader)

    fun update(w: Float, h: Float, seed: Float) {
        shader.setFloatUniform("size", w, h)
        shader.setFloatUniform("seed", seed)
    }

    companion object {
        fun create(): GrainShader = GrainShader()

        private const val SOURCE = """
            uniform float2 size;
            uniform float seed;
            half4 main(float2 p) {
                float n = fract(sin(dot(floor(p) + seed * 97.0, float2(12.9898, 78.233))) * 43758.5453);
                return half4(half3(n), 1.0) * 0.5;
            }
        """
    }
}
