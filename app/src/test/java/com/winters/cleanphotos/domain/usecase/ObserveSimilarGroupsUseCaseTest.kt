package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.domain.model.MediaType
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.SimilarHashProgress
import com.winters.cleanphotos.domain.model.SimilarScan
import com.winters.cleanphotos.domain.model.SimilarSharpnessProgress
import com.winters.cleanphotos.domain.repository.SimilarPhotoRepository
import com.winters.cleanphotos.fake.FakePhotoRepository
import com.winters.cleanphotos.fake.FakeSimilarResolvedRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObserveSimilarGroupsUseCaseTest {

    @Test
    fun `只给邻居算哈希，比对中报进度，算完出组`() = runTest {
        val photos = FakePhotoRepository()
        val hashes = FakeSimilarPhotoRepository(mapOf(1L to 0L, 2L to 0L, 3L to 0L))
        photos.setPhotos(
            listOf(
                shot(1, 1_000),
                shot(2, 2_000),
                shot(3, 80_000),
                shot(4, 1_500, MediaType.Video),
            ),
        )
        val uc = useCase(photos, hashes)
        val scans = uc().transformWhile {
            emit(it)
            it !is SimilarScan.Ready
        }.toList()
        assertEquals(listOf(1L, 2L), hashes.requested.single())
        assertTrue(scans.first() is SimilarScan.Comparing)
        val ready = scans.last() as SimilarScan.Ready
        assertEquals(listOf(1L, 2L), ready.groups.single().photos.map { it.id })
    }

    @Test
    fun `没有邻居时不算哈希，直接空结果`() = runTest {
        val photos = FakePhotoRepository()
        val hashes = FakeSimilarPhotoRepository(emptyMap())
        photos.setPhotos(listOf(shot(1, 1_000)))
        val uc = useCase(photos, hashes)
        val ready = uc().transformWhile {
            emit(it)
            it !is SimilarScan.Ready
        }.toList().single()
        assertTrue(hashes.requested.isEmpty())
        assertTrue((ready as SimilarScan.Ready).groups.isEmpty())
    }

    @Test
    fun `只给组内照片算清晰度，进度总数加上组内张数`() = runTest {
        val photos = FakePhotoRepository()
        val hashes = FakeSimilarPhotoRepository(mapOf(1L to 0L, 2L to 0L, 3L to 0L))
        photos.setPhotos(listOf(shot(1, 1_000), shot(2, 2_000), shot(3, 80_000)))
        val scans = useCase(photos, hashes)().transformWhile {
            emit(it)
            it !is SimilarScan.Ready
        }.toList()
        assertEquals(listOf(1L, 2L), hashes.sharpnessIds)
        assertTrue(scans.any { it is SimilarScan.Comparing && it.total == 4 })
    }

    @Test
    fun `已处理记录是超集时藏起这组`() = runTest {
        val photos = FakePhotoRepository()
        val hashes = FakeSimilarPhotoRepository(mapOf(1L to 0L, 2L to 0L))
        val resolved = FakeSimilarResolvedRepository()
        photos.setPhotos(listOf(shot(1, 1_000), shot(2, 2_000)))
        resolved.addResolved(setOf(1L, 2L, 9L))
        val ready = useCase(photos, hashes, resolved)().transformWhile {
            emit(it)
            it !is SimilarScan.Ready
        }.toList().last() as SimilarScan.Ready
        assertTrue(ready.groups.isEmpty())
    }

    @Test
    fun `已处理记录变化不重新哈希`() = runTest {
        val photos = FakePhotoRepository()
        val hashes = FakeSimilarPhotoRepository(mapOf(1L to 0L, 2L to 0L))
        val resolved = FakeSimilarResolvedRepository()
        photos.setPhotos(listOf(shot(1, 1_000), shot(2, 2_000)))
        val seen = mutableListOf<SimilarScan>()
        val job = launch { useCase(photos, hashes, resolved)().collect { seen += it } }
        advanceUntilIdle()
        assertEquals(1, hashes.requested.size)
        resolved.addResolved(setOf(1L, 2L))
        advanceUntilIdle()
        assertEquals(1, hashes.requested.size)
        assertTrue((seen.last() as SimilarScan.Ready).groups.isEmpty())
        job.cancel()
    }

    private fun useCase(
        photos: FakePhotoRepository,
        hashes: FakeSimilarPhotoRepository,
        resolved: FakeSimilarResolvedRepository = FakeSimilarResolvedRepository(),
    ) = ObserveSimilarGroupsUseCase(
        ObserveAllPhotosUseCase(photos),
        hashes,
        GroupSimilarPhotosUseCase(),
        resolved,
    )
}

private class FakeSimilarPhotoRepository(private val known: Map<Long, Long>) : SimilarPhotoRepository {
    val requested = mutableListOf<List<Long>>()
    var sharpnessIds: List<Long> = emptyList()
    override fun hashPhotos(photos: List<PhotoItem>): Flow<SimilarHashProgress> = flow {
        requested += photos.map { it.id }
        emit(SimilarHashProgress(0, photos.size, emptyMap(), finished = false))
        val got = photos.mapNotNull { photo -> known[photo.id]?.let { photo.id to it } }.toMap()
        emit(SimilarHashProgress(photos.size, photos.size, got, finished = true))
    }

    override fun measureSharpness(photos: List<PhotoItem>): Flow<SimilarSharpnessProgress> = flow {
        sharpnessIds = photos.map { it.id }
        emit(SimilarSharpnessProgress(0, photos.size, emptyMap(), finished = false))
        emit(SimilarSharpnessProgress(photos.size, photos.size, emptyMap(), finished = true))
    }
}

private fun shot(id: Long, taken: Long, type: MediaType = MediaType.Image) = com.winters.cleanphotos.TestPhotoData.createTestPhoto(id).copy(
    dateTaken = taken,
    mediaType = type,
)
