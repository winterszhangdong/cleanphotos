package com.winters.cleanphotos.data.datasource

import org.junit.Assert.assertEquals
import org.junit.Test

/** createDeleteRequest / createWriteRequest / createFavoriteRequest 的 2000 张上限。只切下一批，不丢掉剩下的。 */
class MediaStoreRequestBatchesTest {

    @Test
    fun `上限写死 2000`() {
        assertEquals(2000, MEDIA_STORE_REQUEST_LIMIT)
    }

    @Test
    fun `空列表和正好 2000 张都不拆`() {
        assertEquals(MediaStoreBatch(emptyList<Int>(), emptyList()), firstMediaStoreBatch(emptyList<Int>()))
        val exact = (1..2000).toList()
        assertEquals(MediaStoreBatch(exact, emptyList()), firstMediaStoreBatch(exact))
    }

    @Test
    fun `2001 和 4001 只交出第一批`() {
        assertEquals(MediaStoreBatch((1..2000).toList(), listOf(2001)), firstMediaStoreBatch((1..2001).toList()))
        val first = firstMediaStoreBatch((1..4001).toList())
        assertEquals(2000, first.current.size)
        assertEquals(2001, first.remaining.size)
        assertEquals(1, first.current.first())
        assertEquals(2001, first.remaining.first())
    }

    @Test
    fun `连续取批盖住 4001 且顺序不丢不重`() {
        val input = (1..4001).toList()
        val sizes = mutableListOf<Int>()
        val flat = mutableListOf<Int>()
        var rest = input
        while (rest.isNotEmpty()) {
            val batch = firstMediaStoreBatch(rest)
            sizes += batch.current.size
            flat += batch.current
            rest = batch.remaining
        }
        assertEquals(listOf(2000, 2000, 1), sizes)
        assertEquals(input, flat)
    }
}
