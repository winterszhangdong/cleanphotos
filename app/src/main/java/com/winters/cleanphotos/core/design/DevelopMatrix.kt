package com.winters.cleanphotos.core.design

/**
 * 显影的纯数学部分（可单元测试）。
 *
 * 与 CSS `filter: brightness(b) contrast(c) saturate(s)` 等价：先亮度、再反差、最后饱和，
 * 合成为一个 4×5 颜色矩阵（行主序，偏移量以 0..255 计，与 android ColorMatrix 一致）。
 */
object DevelopMatrix {

    data class Values(val brightness: Float, val contrast: Float, val saturation: Float, val scale: Float, val grain: Float)

    /** elapsedFraction 为 0..1 的线性时间；各通道在自己的时间窗内按 profile.easing 走。 */
    fun values(profile: DevelopProfile, elapsedFraction: Float): Values {
        val t = elapsedFraction.coerceIn(0f, 1f) * profile.durationMs
        fun ch(c: DevelopChannel): Float {
            val local = ((t - c.startMs) / (c.endMs - c.startMs).toFloat()).coerceIn(0f, 1f)
            return c.start + (1f - c.start) * profile.easing.transform(local)
        }
        val overall = profile.easing.transform(elapsedFraction.coerceIn(0f, 1f))
        return Values(
            brightness = ch(profile.brightness),
            contrast = ch(profile.contrast),
            saturation = ch(profile.saturation),
            scale = profile.scaleFrom + (1f - profile.scaleFrom) * overall,
            grain = profile.grainFrom * (1f - overall),
        )
    }

    /** CSS brightness → contrast → saturate 合成，写入 out（长度 20）。 */
    fun compose(brightness: Float, contrast: Float, saturation: Float, out: FloatArray = FloatArray(20)): FloatArray {
        require(out.size == 20)
        // saturate（CSS Filter Effects 规范系数）
        val s = saturation
        val sat = floatArrayOf(
            0.213f + 0.787f * s, 0.715f - 0.715f * s, 0.072f - 0.072f * s,
            0.213f - 0.213f * s, 0.715f + 0.285f * s, 0.072f - 0.072f * s,
            0.213f - 0.213f * s, 0.715f - 0.715f * s, 0.072f + 0.928f * s,
        )
        // contrast(c) ∘ brightness(b)：x' = c·b·x + 255·(0.5 − 0.5c)
        val k = contrast * brightness
        val offset = 255f * (0.5f - 0.5f * contrast)
        for (r in 0 until 3) {
            for (col in 0 until 3) out[r * 5 + col] = sat[r * 3 + col] * k
            out[r * 5 + 3] = 0f
            // 饱和矩阵每行和为 1，所以常量偏移经过 saturate 后不变
            out[r * 5 + 4] = offset
        }
        out[15] = 0f; out[16] = 0f; out[17] = 0f; out[18] = 1f; out[19] = 0f
        return out
    }
}
