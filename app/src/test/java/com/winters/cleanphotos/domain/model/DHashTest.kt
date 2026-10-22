package com.winters.cleanphotos.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DHashTest {

    @Test
    fun `从左到右变亮则哈希为 0`() {
        val luma = IntArray(SimilarRules.DHASH_LENGTH) { it }
        assertEquals(0L, DHash.fromLuminance(luma))
    }

    @Test
    fun `从左到右变暗则 64 位全是 1`() {
        val luma = IntArray(SimilarRules.DHASH_LENGTH) { SimilarRules.DHASH_LENGTH - it }
        assertEquals(-1L, DHash.fromLuminance(luma))
        assertEquals(64, DHash.hamming(0L, -1L))
    }

    @Test
    fun `只点亮指定的一对相邻像素`() {
        val luma = IntArray(SimilarRules.DHASH_LENGTH) { 1 }
        // 第二行最左一对：bit 下标 = 8。右边保持 1，避免连带点亮下一对。
        luma[SimilarRules.DHASH_WIDTH] = 2
        assertEquals(1L shl 8, DHash.fromLuminance(luma))
    }

    @Test
    fun `相等的相邻像素不置位，相同哈希距离为 0`() {
        val luma = IntArray(SimilarRules.DHASH_LENGTH) { 40 }
        val hash = DHash.fromLuminance(luma)
        assertEquals(0L, hash)
        assertEquals(0, DHash.hamming(hash, hash))
    }

    @Test
    fun `汉明距离等于不同的位数`() {
        assertEquals(10, DHash.hamming(0L, (1L shl 10) - 1))
        assertEquals(11, DHash.hamming(0L, (1L shl 11) - 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `亮度个数不对直接失败`() {
        DHash.fromLuminance(IntArray(3))
    }
}
