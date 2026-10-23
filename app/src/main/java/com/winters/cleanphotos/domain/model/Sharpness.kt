package com.winters.cleanphotos.domain.model

/**
 * 清晰度：灰度图的拉普拉斯方差。边缘越锐，方差越大；纯色为 0。
 * 输入是一行一行的亮度，0–255。
 */
object Sharpness {
    fun laplacianVariance(luma: IntArray, width: Int, height: Int): Double {
        require(luma.size == width * height) { "亮度个数 ${luma.size} 与 ${width}×${height} 不符" }
        require(width >= 3 && height >= 3) { "拉普拉斯至少需要 3×3，实际 ${width}×${height}" }
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val v = (
                    luma[row - width + x] + luma[row + width + x] + luma[row + x - 1] + luma[row + x + 1] -
                        4 * luma[row + x]
                    ).toDouble()
                sum += v
                sumSq += v * v
                n++
            }
        }
        val mean = sum / n
        return sumSq / n - mean * mean
    }
}
