package com.winters.cleanphotos.domain.usecase

import android.net.Uri
import com.winters.cleanphotos.TestPhotoData
import com.winters.cleanphotos.fake.FakePhotoRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * ObserveAllPhotosUseCase 单元测试。
 *
 * 验证 UseCase 正确转发 repository 中的照片列表 Flow。
 */
class ObserveAllPhotosUseCaseTest {

    private lateinit var fakePhotoRepository: FakePhotoRepository
    private lateinit var useCase: ObserveAllPhotosUseCase

    @Before
    fun setUp() {
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } returns mockk<Uri>(relaxed = true)

        fakePhotoRepository = FakePhotoRepository()
        useCase = ObserveAllPhotosUseCase(fakePhotoRepository)
    }

    @After
    fun tearDown() {
        unmockkStatic(Uri::class)
    }

    @Test
    fun `返回空列表`() = runTest {
        val result = useCase().first()
        assertEquals(emptyList<TestPhotoData>(), result)
    }

    @Test
    fun `返回repository中的照片列表`() = runTest {
        val photos = TestPhotoData.createTestPhotos(3)
        fakePhotoRepository.setPhotos(photos)

        val result = useCase().first()
        assertEquals(photos, result)
    }

    @Test
    fun `数据更新后Flow发射新列表`() = runTest {
        val photos1 = TestPhotoData.createTestPhotos(2)
        val photos2 = TestPhotoData.createTestPhotos(5)

        fakePhotoRepository.setPhotos(photos1)
        assertEquals(photos1, useCase().first())

        fakePhotoRepository.setPhotos(photos2)
        assertEquals(photos2, useCase().first())
    }
}
