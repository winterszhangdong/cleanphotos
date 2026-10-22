package com.winters.cleanphotos.domain.usecase

import android.net.Uri
import com.winters.cleanphotos.domain.model.MediaType
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.SimilarRules
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupSimilarPhotosUseCaseTest {

    private val group = GroupSimilarPhotosUseCase()
    private val t0 = 1_700_000_000_000L

    @Test
    fun `间隔和距离都刚好在边界上成组，超出则拆开`() {
        val within = listOf(photo(1, t0), photo(2, t0 + SimilarRules.MAX_GAP_MS))
        assertEquals(listOf(1L, 2L), ids(group(within, hashes(1L to 0L, 2L to bits(10)))))
        assertTrue(group(listOf(photo(1, t0), photo(2, t0 + SimilarRules.MAX_GAP_MS + 1)), hashes(1L to 0L, 2L to 0L)).isEmpty())
        assertTrue(group(within, hashes(1L to 0L, 2L to bits(11))).isEmpty())
    }

    @Test
    fun `只和前一张比，时间连得上就成一串`() {
        // A-C 相隔 20 秒，但 A-B、B-C 各 10 秒且距离刚好 10
        val photos = listOf(
            photo(1, t0),
            photo(2, t0 + SimilarRules.MAX_GAP_MS),
            photo(3, t0 + SimilarRules.MAX_GAP_MS * 2),
        )
        // B 与 A 距离 10；C 与 B 距离 10，与 A 距离 12。只和前一张比才会三张一组。
        val b = bits(10)
        val c = (b and 0xFL.inv()) or (0x3FL shl 10)
        val hashes = hashes(1L to 0L, 2L to b, 3L to c)
        val groups = group(photos, hashes)
        assertEquals(1, groups.size)
        assertEquals(listOf(1L, 2L, 3L), groups.single().photos.map { it.id })
    }

    @Test
    fun `中间一张对不上就从这里断开`() {
        val photos = listOf(photo(1, t0), photo(2, t0 + 1_000), photo(3, t0 + 2_000))
        val groups = group(photos, hashes(1L to 0L, 2L to 0L, 3L to bits(11)))
        assertEquals(listOf(1L, 2L), ids(groups))
    }

    @Test
    fun `单张不成组，输入顺序打乱仍按时间排`() {
        assertTrue(group(listOf(photo(1, t0)), hashes(1L to 0L)).isEmpty())
        val photos = listOf(photo(2, t0 + 4_000), photo(1, t0))
        assertEquals(listOf(1L, 2L), ids(group(photos, hashes(1L to 0L, 2L to 0L))))
    }

    @Test
    fun `视频和没有拍摄时间的不进组`() {
        val photos = listOf(
            photo(1, t0),
            photo(2, t0 + 1_000, type = MediaType.Video),
            photo(3, null),
            photo(4, 0L),
        )
        assertTrue(group(photos, hashes(1L to 0L, 2L to 0L, 3L to 0L, 4L to 0L)).isEmpty())
    }

    @Test
    fun `取图失败的一张被跳过，两侧仍在 10 秒内就连上`() {
        val photos = listOf(photo(1, t0), photo(2, t0 + 5_000), photo(3, t0 + SimilarRules.MAX_GAP_MS))
        val groups = group(photos, hashes(1L to 0L, 3L to 0L))
        assertEquals(listOf(1L, 3L), ids(groups))
    }

    @Test
    fun `两段连拍分成两组，较新的在前`() {
        val photos = listOf(
            photo(1, t0),
            photo(2, t0 + 1_000),
            photo(3, t0 + 100_000),
            photo(4, t0 + 101_000),
        )
        val groups = group(photos, hashes(1L to 0L, 2L to 0L, 3L to 0L, 4L to 0L))
        assertEquals(listOf(listOf(3L, 4L), listOf(1L, 2L)), groups.map { it.photos.map { p -> p.id } })
    }

    @Test
    fun `只给 10 秒内有邻居的照片算哈希`() {
        val photos = listOf(
            photo(1, t0),
            photo(2, t0 + SimilarRules.MAX_GAP_MS),
            photo(3, t0 + SimilarRules.MAX_GAP_MS + 1 + 60_000),
            photo(4, t0, type = MediaType.Video),
        )
        assertEquals(listOf(1L, 2L), group.hashCandidates(photos).map { it.id })
        assertTrue(group.hashCandidates(listOf(photo(9, 5_000))).isEmpty())
    }

    @Test
    fun `默认留下像素最多的，其次更大的文件，再其次更早的`() {
        val biggerPixels = photo(1, 2_000, width = 200, height = 100, size = 1)
        val biggerFile = photo(2, 3_000, width = 10, height = 10, size = 50)
        val earlier = photo(3, 1_000, width = 10, height = 10, size = 50)
        val laterSame = photo(4, 4_000, width = 10, height = 10, size = 50)
        assertEquals(1L, chooseSimilarKeep(listOf(biggerFile, biggerPixels, earlier)).id)
        assertEquals(3L, chooseSimilarKeep(listOf(laterSame, earlier, biggerFile)).id)
        val same = listOf(
            photo(8, 1_000, width = 10, height = 10, size = 10),
            photo(5, 1_000, width = 10, height = 10, size = 10),
        )
        assertEquals(5L, chooseSimilarKeep(same).id)
        val grouped = group(listOf(biggerPixels, earlier), hashes(1L to 0L, 3L to 0L))
        assertEquals(1L, grouped.single().keepId)
    }

    private fun ids(groups: List<com.winters.cleanphotos.domain.model.SimilarGroup>): List<Long> =
        groups.single().photos.map { it.id }

    private fun hashes(vararg pairs: Pair<Long, Long>) = mapOf(*pairs)

    private fun bits(n: Int) = (1L shl n) - 1

    private fun photo(
        id: Long,
        taken: Long?,
        width: Int? = 10,
        height: Int? = 10,
        size: Long? = 10,
        type: MediaType = MediaType.Image,
    ) = PhotoItem(
        id = id,
        uri = mockk<Uri>(relaxed = true),
        displayName = "$id",
        dateTaken = taken,
        dateGroup = "2024-05-20",
        size = size,
        width = width,
        height = height,
        mediaType = type,
    )
}
