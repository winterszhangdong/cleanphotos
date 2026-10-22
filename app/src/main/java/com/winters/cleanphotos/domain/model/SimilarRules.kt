package com.winters.cleanphotos.domain.model

/**
 * 相似照片的判定。时间窗和距离都是闭区间：刚好 10 秒、汉明距离刚好 10 仍算同一组。
 * 哈希只对「10 秒内有邻居」的照片计算，单张孤立的照片不算。
 */
object SimilarRules {
    const val MAX_GAP_MS = 10_000L
    const val MAX_HAMMING = 10
    /** 同时解码的张数。再高会和网格缩略图抢 IO。 */
    const val HASH_PARALLELISM = 2

    const val DHASH_WIDTH = 9
    const val DHASH_HEIGHT = 8
    const val DHASH_LENGTH = DHASH_WIDTH * DHASH_HEIGHT
}

/**
 * 64 位 dHash：9×8 亮度按行比较相邻像素，左比右亮则该位置 1。
 * bit 0 是第一行最左一对，从低位往高位排，方便测试用移位构造距离。
 */
object DHash {
    fun fromLuminance(luma: IntArray): Long {
        require(luma.size == SimilarRules.DHASH_LENGTH) {
            "dHash 需要 ${SimilarRules.DHASH_LENGTH} 个亮度值，实际 ${luma.size}"
        }
        var hash = 0L
        var bit = 0
        for (y in 0 until SimilarRules.DHASH_HEIGHT) {
            val row = y * SimilarRules.DHASH_WIDTH
            for (x in 0 until SimilarRules.DHASH_WIDTH - 1) {
                if (luma[row + x] > luma[row + x + 1]) hash = hash or (1L shl bit)
                bit++
            }
        }
        return hash
    }

    fun hamming(a: Long, b: Long): Int = (a xor b).countOneBits()
}
