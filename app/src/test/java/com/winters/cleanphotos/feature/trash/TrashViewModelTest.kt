package com.winters.cleanphotos.feature.trash

import com.winters.cleanphotos.R
import com.winters.cleanphotos.core.text.UiText
import android.net.Uri
import app.cash.turbine.test
import com.winters.cleanphotos.TestPhotoData
import com.winters.cleanphotos.domain.model.PhotoOperationFailureReason
import com.winters.cleanphotos.domain.model.PhotoOperationResult
import com.winters.cleanphotos.domain.usecase.DeletePhotosPermanentlyUseCase
import com.winters.cleanphotos.domain.usecase.ObserveTrashedPhotosUseCase
import com.winters.cleanphotos.domain.usecase.RestorePhotoFromTrashUseCase
import com.winters.cleanphotos.domain.usecase.RestorePhotosFromTrashUseCase
import com.winters.cleanphotos.domain.usecase.AddFreedBytesUseCase
import com.winters.cleanphotos.domain.usecase.ObserveTotalFreedBytesUseCase
import com.winters.cleanphotos.fake.FakePhotoRepository
import com.winters.cleanphotos.fake.FakeReviewRepository
import com.winters.cleanphotos.fake.FakeUserPreferencesRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * TrashViewModel 单元测试。
 *
 * 测试垃圾桶页面的核心逻辑：
 * - 加载垃圾桶照片列表
 * - 空垃圾桶显示空状态
 * - 预览模式恢复照片
 * - 多选模式批量恢复
 * - 清空垃圾桶
 * - 错误信息清除
 */
class TrashViewModelTest {

    private val testDispatcher: TestDispatcher = StandardTestDispatcher()

    private lateinit var fakePhotoRepository: FakePhotoRepository
    private lateinit var fakeReviewRepository: FakeReviewRepository
    private lateinit var fakePreferences: FakeUserPreferencesRepository

    @Before
    fun setUp() {
        fakePhotoRepository = FakePhotoRepository()
        fakeReviewRepository = FakeReviewRepository()
        fakePreferences = FakeUserPreferencesRepository()

        Dispatchers.setMain(testDispatcher)

        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers {
            mockk<Uri>(relaxed = true)
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Uri::class)
    }

    private fun createViewModel(): TrashViewModel {
        return TrashViewModel(
            observeTrashedPhotosUseCase = ObserveTrashedPhotosUseCase(fakePhotoRepository),
            restorePhotoFromTrashUseCase = RestorePhotoFromTrashUseCase(fakeReviewRepository),
            restorePhotosFromTrashUseCase = RestorePhotosFromTrashUseCase(fakeReviewRepository),
            deletePhotosPermanentlyUseCase = DeletePhotosPermanentlyUseCase(fakeReviewRepository),
            photoRepository = fakePhotoRepository,
            observeTrashSummaryUseCase = com.winters.cleanphotos.domain.usecase.ObserveTrashSummaryUseCase(fakePhotoRepository),
            observeTrashMovedAtUseCase = com.winters.cleanphotos.domain.usecase.ObserveTrashMovedAtUseCase(fakePhotoRepository),
            sumPhotoBytesUseCase = com.winters.cleanphotos.domain.usecase.SumPhotoBytesUseCase(),
            observeTotalFreedBytesUseCase = ObserveTotalFreedBytesUseCase(fakePreferences),
            addFreedBytesUseCase = AddFreedBytesUseCase(fakePreferences),
            computeDispatcher = testDispatcher,
        )
    }

    // ====== 加载测试 ======

    @Test
    fun `初始化后加载垃圾桶照片列表`() = runTest(testDispatcher) {
        val trashedPhotos = TestPhotoData.createTestPhotos(3).map { it.copy(isTrashed = true) }
        fakePhotoRepository.setTrashedPhotos(trashedPhotos)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("应加载 3 张垃圾桶照片", 3, state.photos.size)
            assertFalse("加载完成后 isLoading 应为 false", state.isLoading)
            assertNull("不应有错误信息", state.errorMessage)
        }
    }

    @Test
    fun `垃圾桶为空时显示空列表`() = runTest(testDispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("空垃圾桶应显示 0 张照片", 0, state.photos.size)
            assertFalse("加载完成后 isLoading 应为 false", state.isLoading)
        }
    }

    @Test
    fun `加载垃圾桶失败时显示错误信息`() = runTest(testDispatcher) {
        fakePhotoRepository.shouldThrowOnRefresh = true

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse("加载完成后 isLoading 应为 false", state.isLoading)
            assertEquals(UiText.Res(R.string.error_load_trash), state.errorMessage)
        }
    }

    // ====== 预览模式测试 ======

    @Test
    fun `点击缩略图进入预览模式`() = runTest(testDispatcher) {
        val trashedPhotos = TestPhotoData.createTestPhotos(3).map { it.copy(isTrashed = true) }
        fakePhotoRepository.setTrashedPhotos(trashedPhotos)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onPhotoClick(trashedPhotos[0])

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(TrashScreenMode.Preview, state.screenMode)
            assertEquals(trashedPhotos[0].id, state.previewPhotoId)
        }
    }

    @Test
    fun `预览模式恢复成功后显示成功消息`() = runTest(testDispatcher) {
        val trashedPhotos = TestPhotoData.createTestPhotos(3).map { it.copy(isTrashed = true) }
        fakePhotoRepository.setTrashedPhotos(trashedPhotos)

        val viewModel = createViewModel()
        advanceUntilIdle()

        // 进入预览模式并恢复
        viewModel.onPhotoClick(trashedPhotos[0])
        viewModel.onRestorePreviewPhoto()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse("恢复完成后 isProcessingAction 应为 false", state.isProcessingAction)
            assertEquals(UiText.Res(R.string.msg_restored), state.actionMessage)
            assertNull("不应有错误信息", state.errorMessage)
        }
    }

    @Test
    fun `预览模式恢复失败显示错误信息`() = runTest(testDispatcher) {
        val trashedPhotos = TestPhotoData.createTestPhotos(3).map { it.copy(isTrashed = true) }
        fakePhotoRepository.setTrashedPhotos(trashedPhotos)
        fakeReviewRepository.restoreResult = PhotoOperationResult.Failure(
            PhotoOperationFailureReason.MediaStoreFailed
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onPhotoClick(trashedPhotos[0])
        viewModel.onRestorePreviewPhoto()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isProcessingAction)
            assertNotNull("应有错误信息", state.errorMessage)
        }
    }

    @Test
    fun `预览恢复最后一张后退出预览`() = runTest(testDispatcher) {
        val trashedPhotos = TestPhotoData.createTestPhotos(1).map { it.copy(isTrashed = true) }
        fakePhotoRepository.setTrashedPhotos(trashedPhotos)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onPhotoClick(trashedPhotos[0])
        viewModel.onRestorePreviewPhoto()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("恢复最后一张后应回到网格模式", TrashScreenMode.Grid, state.screenMode)
            assertNull("previewPhotoId 应为 null", state.previewPhotoId)
        }
    }

    // ====== 预览恢复竞态回归测试 ======

    @Test
    fun `恢复中间照片后自动切到原下一张`() = runTest(testDispatcher) {
        val trashedPhotos = TestPhotoData.createTestPhotos(3).map { it.copy(isTrashed = true) }
        fakePhotoRepository.setTrashedPhotos(trashedPhotos)

        val viewModel = createViewModel()
        advanceUntilIdle()

        // 进入预览，停留在第一张（id=1）
        viewModel.onPhotoClick(trashedPhotos[0])
        viewModel.onRestorePreviewPhoto()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("恢复后应保持预览模式", TrashScreenMode.Preview, state.screenMode)
            // 恢复前索引 0，恢复后应显示原下一张（索引 0 位置的 photo_2）
            assertEquals("应切到原下一张", trashedPhotos[1].id, state.previewPhotoId)
            assertEquals(UiText.Res(R.string.msg_restored), state.actionMessage)
        }
    }

    @Test
    fun `恢复最后一张后切到新的最后一张`() = runTest(testDispatcher) {
        val trashedPhotos = TestPhotoData.createTestPhotos(3).map { it.copy(isTrashed = true) }
        fakePhotoRepository.setTrashedPhotos(trashedPhotos)

        val viewModel = createViewModel()
        advanceUntilIdle()

        // 进入预览，停留在最后一张（id=3, 索引 2）
        viewModel.onPhotoClick(trashedPhotos[2])
        viewModel.onRestorePreviewPhoto()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("恢复后应保持预览模式", TrashScreenMode.Preview, state.screenMode)
            // 恢复前索引 2 已是最后一张，应显示新的最后一张（photo_2, id=2）
            assertEquals("应切到新的最后一张", trashedPhotos[1].id, state.previewPhotoId)
        }
    }

    @Test
    fun `恢复成功时即使 Flow 先更新也不闪退`() = runTest(testDispatcher) {
        val trashedPhotos = TestPhotoData.createTestPhotos(3).map { it.copy(isTrashed = true) }
        fakePhotoRepository.setTrashedPhotos(trashedPhotos)

        val viewModel = createViewModel()
        advanceUntilIdle()

        // 进入预览，停留在中间张（id=2, 索引 1）
        viewModel.onPhotoClick(trashedPhotos[1])
        viewModel.onRestorePreviewPhoto()
        // 恢复成功后，handlePreviewRestoreSuccess 基于快照计算目标，
        // 即使 refreshPhotoLists 触发 Flow 更新也不会导致索引失效
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            // 基于恢复前快照：索引 1 恢复后，原下一张（索引 1 位置）是 photo_3
            assertEquals("应切到原下一张", trashedPhotos[2].id, state.previewPhotoId)
            assertFalse("不应处于处理中", state.isProcessingAction)
            assertNull("不应有错误", state.errorMessage)
        }
    }

    // ====== 多选模式测试 ======

    @Test
    fun `进入多选模式后可选中照片`() = runTest(testDispatcher) {
        val trashedPhotos = TestPhotoData.createTestPhotos(3).map { it.copy(isTrashed = true) }
        fakePhotoRepository.setTrashedPhotos(trashedPhotos)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onEnterSelectionMode()
        viewModel.onTogglePhotoSelection(trashedPhotos[0])
        viewModel.onTogglePhotoSelection(trashedPhotos[1])

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(TrashScreenMode.Selection, state.screenMode)
            assertEquals(2, state.selectedPhotoIds.size)
        }
    }

    @Test
    fun `多选模式批量恢复成功后退出多选`() = runTest(testDispatcher) {
        val trashedPhotos = TestPhotoData.createTestPhotos(3).map { it.copy(isTrashed = true) }
        fakePhotoRepository.setTrashedPhotos(trashedPhotos)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onEnterSelectionMode()
        viewModel.onTogglePhotoSelection(trashedPhotos[0])
        viewModel.onTogglePhotoSelection(trashedPhotos[1])
        viewModel.onBatchRestore()
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("批量恢复后应回到网格模式", TrashScreenMode.Grid, state.screenMode)
            assertEquals("选中集合应清空", 0, state.selectedPhotoIds.size)
            assertEquals(UiText.Res(R.string.msg_restored), state.actionMessage)
        }
    }

    // ====== 清空垃圾桶测试 ======

    @Test
    fun `清空垃圾桶确认后永久删除`() = runTest(testDispatcher) {
        val trashedPhotos = TestPhotoData.createTestPhotos(3).map { it.copy(isTrashed = true) }
        fakePhotoRepository.setTrashedPhotos(trashedPhotos)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onShowEmptyTrashConfirm()
        assertEquals("确认框应显示", true, viewModel.uiState.value.showEmptyTrashConfirmDialog)

        viewModel.onConfirmEmptyTrash()
        // 模拟系统永久删除成功后，垃圾桶列表清空
        fakePhotoRepository.setTrashedPhotos(emptyList())
        advanceUntilIdle()

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isProcessingAction)
            assertEquals(UiText.Res(R.string.msg_trash_emptied), state.actionMessage)
            assertEquals(TrashScreenMode.Grid, state.screenMode)
        }
    }

    @Test
    fun `清空成功挂上溶解快照，播完后清掉且不会被写回`() = runTest(testDispatcher) {
        val trashedPhotos = TestPhotoData.createTestPhotos(3).map { it.copy(isTrashed = true) }
        fakePhotoRepository.setTrashedPhotos(trashedPhotos)
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onShowEmptyTrashConfirm()
        viewModel.onConfirmEmptyTrash()
        fakePhotoRepository.setTrashedPhotos(emptyList())
        advanceUntilIdle()

        val dissolving = viewModel.uiState.value.dissolving
        assertNotNull("清空成功后要有溶解快照", dissolving)
        assertEquals(3, dissolving!!.photos.size)
        assertNotNull(viewModel.uiState.value.justFreedBytes)

        viewModel.onDissolveFinished()
        advanceUntilIdle()
        assertNull("溶解播完后快照清掉，空状态才会出现", viewModel.uiState.value.dissolving)
        assertNotNull("空状态要显示「刚刚释放了」", viewModel.uiState.value.justFreedBytes)
    }

    @Test
    fun `删除过程中列表先被刷新为空，释放大小仍按删除前计算`() = runTest(testDispatcher) {
        fakePhotoRepository.setTrashedPhotos(TestPhotoData.createTestPhotos(3).map { it.copy(isTrashed = true) })
        val viewModel = createViewModel()
        advanceUntilIdle()
        val bytesBefore = viewModel.uiState.value.totalBytes
        assert(bytesBefore > 0)

        fakeReviewRepository.onDeletePermanently = { fakePhotoRepository.setTrashedPhotos(emptyList()) }
        viewModel.onShowEmptyTrashConfirm()
        viewModel.onConfirmEmptyTrash()
        advanceUntilIdle()

        assertEquals(bytesBefore, viewModel.uiState.value.justFreedBytes)
        assertEquals(3, viewModel.uiState.value.dissolving!!.photos.size)
    }

    @Test
    fun `缺写权限时先申请，同意后重试并清空`() = runTest(testDispatcher) {
        fakePhotoRepository.setTrashedPhotos(TestPhotoData.createTestPhotos(3).map { it.copy(isTrashed = true) })
        fakeReviewRepository.deletePermanentlyResult =
            PhotoOperationResult.Failure(PhotoOperationFailureReason.WritePermissionRequired)
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.effects.test {
            viewModel.onShowEmptyTrashConfirm()
            viewModel.onConfirmEmptyTrash()
            advanceUntilIdle()
            assertEquals(TrashEffect.RequestWritePermission, awaitItem())
            assertNull("申请权限时不提示错误", viewModel.uiState.value.errorMessage)
            assertEquals("申请期间保持处理中，防止连点", true, viewModel.uiState.value.isProcessingAction)

            fakeReviewRepository.deletePermanentlyResult = PhotoOperationResult.Success
            viewModel.onWritePermissionResult(true)
            fakePhotoRepository.setTrashedPhotos(emptyList())
            advanceUntilIdle()
            expectNoEvents()
        }
        assertEquals(UiText.Res(R.string.msg_trash_emptied), viewModel.uiState.value.actionMessage)
        assertFalse(viewModel.uiState.value.isProcessingAction)
    }

    @Test
    fun `拒绝写权限时提示错误且列表不变`() = runTest(testDispatcher) {
        fakePhotoRepository.setTrashedPhotos(TestPhotoData.createTestPhotos(3).map { it.copy(isTrashed = true) })
        fakeReviewRepository.deletePermanentlyResult =
            PhotoOperationResult.Failure(PhotoOperationFailureReason.WritePermissionRequired)
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onShowEmptyTrashConfirm()
        viewModel.onConfirmEmptyTrash()
        advanceUntilIdle()
        viewModel.onWritePermissionResult(false)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(UiText.Res(R.string.fail_write_permission), state.errorMessage)
        assertFalse(state.isProcessingAction)
        assertEquals(3, state.photos.size)
        assertNull(state.dissolving)
    }

    @Test
    fun `同意后仍缺写权限时只提示一次，不循环申请`() = runTest(testDispatcher) {
        fakePhotoRepository.setTrashedPhotos(TestPhotoData.createTestPhotos(2).map { it.copy(isTrashed = true) })
        fakeReviewRepository.deletePermanentlyResult =
            PhotoOperationResult.Failure(PhotoOperationFailureReason.WritePermissionRequired)
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.effects.test {
            viewModel.onShowEmptyTrashConfirm()
            viewModel.onConfirmEmptyTrash()
            advanceUntilIdle()
            assertEquals(TrashEffect.RequestWritePermission, awaitItem())
            viewModel.onWritePermissionResult(true)
            advanceUntilIdle()
            expectNoEvents()
        }
        assertEquals(UiText.Res(R.string.fail_write_permission), viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.isProcessingAction)
    }

    @Test
    fun `取消清空垃圾桶关闭确认框`() = runTest(testDispatcher) {
        val trashedPhotos = TestPhotoData.createTestPhotos(3).map { it.copy(isTrashed = true) }
        fakePhotoRepository.setTrashedPhotos(trashedPhotos)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onShowEmptyTrashConfirm()
        viewModel.onDismissEmptyTrashConfirm()

        assertEquals("确认框应关闭", false, viewModel.uiState.value.showEmptyTrashConfirmDialog)
    }

    // ====== 错误清除测试 ======

    @Test
    fun `dismissError清除错误信息`() = runTest(testDispatcher) {
        fakePhotoRepository.shouldThrowOnRefresh = true

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertNotNull("加载失败后应有错误信息", viewModel.uiState.value.errorMessage)

        viewModel.dismissError()

        viewModel.uiState.test {
            val state = awaitItem()
            assertNull("dismissError 后错误信息应为 null", state.errorMessage)
        }
    }

    @Test
    fun `dismissActionMessage清除成功消息`() = runTest(testDispatcher) {
        val trashedPhotos = TestPhotoData.createTestPhotos(2).map { it.copy(isTrashed = true) }
        fakePhotoRepository.setTrashedPhotos(trashedPhotos)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onPhotoClick(trashedPhotos[0])
        viewModel.onRestorePreviewPhoto()
        advanceUntilIdle()

        assertNotNull("恢复成功后应有成功消息", viewModel.uiState.value.actionMessage)

        viewModel.dismissActionMessage()

        viewModel.uiState.test {
            val state = awaitItem()
            assertNull("dismissActionMessage 后消息应为 null", state.actionMessage)
        }
    }

    // ====== 清空垃圾桶收尾（A-D7 / A-D8 / A-D10）======

    @Test
    fun `直接删除部分失败_刷新列表并清掉已删掉照片的记录_提示失败`() = runTest(testDispatcher) {
        val photos = TestPhotoData.createTestPhotos(3).map { it.copy(isTrashed = true) }
        fakePhotoRepository.setTrashedPhotos(photos)
        fakeReviewRepository.deletePermanentlyResult = PhotoOperationResult.Failure(PhotoOperationFailureReason.MediaStoreFailed)
        // 删掉了 1、2，3 删不掉
        fakeReviewRepository.onDeletePermanently = { fakePhotoRepository.setTrashedPhotos(photos.filter { it.id == 3L }) }
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onShowEmptyTrashConfirm()
        viewModel.onConfirmEmptyTrash()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(UiText.Res(R.string.fail_generic), state.errorMessage)
        assertFalse(state.isProcessingAction)
        assertEquals(listOf(3L), state.photos.map { it.id })
        assertEquals(listOf(setOf(1L, 2L)), fakeReviewRepository.forgotten)
    }

    @Test
    fun `系统授权删除成功_清掉被删照片的记录_部分照片权限下也不残留`() = runTest(testDispatcher) {
        val photos = TestPhotoData.createTestPhotos(3).map { it.copy(isTrashed = true) }
        fakePhotoRepository.setTrashedPhotos(photos)
        fakeReviewRepository.deletePermanentlyResult = PhotoOperationResult.NeedSystemApproval(
            com.winters.cleanphotos.domain.model.SystemMediaActionRequest(
                photos = photos,
                actionType = com.winters.cleanphotos.domain.model.SystemMediaActionType.DeletePermanently,
                requestToken = "t",
                intentSender = mockk(relaxed = true),
            ),
        )
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.effects.test {
            viewModel.onShowEmptyTrashConfirm()
            viewModel.onConfirmEmptyTrash()
            advanceUntilIdle()
            awaitItem()
        }
        fakePhotoRepository.setTrashedPhotos(emptyList())
        viewModel.onSystemMediaActionResult(true)
        advanceUntilIdle()

        assertEquals(listOf(setOf(1L, 2L, 3L)), fakeReviewRepository.forgotten)
        assertEquals(UiText.Res(R.string.msg_trash_emptied), viewModel.uiState.value.actionMessage)
    }

    @Test
    fun `系统删除确认期间进程重建_收到授权结果仍刷新并提示已清空`() = runTest(testDispatcher) {
        fakePhotoRepository.setTrashedPhotos(emptyList())
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onSystemMediaActionResult(true)
        advanceUntilIdle()

        assertEquals(UiText.Res(R.string.msg_trash_emptied), viewModel.uiState.value.actionMessage)
        assertFalse(viewModel.uiState.value.isProcessingAction)
        assertEquals("进程重建没有快照，不计入累计", 0L, fakePreferences.totalFreedBytesFlow.value)
    }

    @Test
    fun `清空全部成功_累计等于快照总大小`() = runTest(testDispatcher) {
        val photos = TestPhotoData.createTestPhotos(3).map { it.copy(isTrashed = true) }
        fakePhotoRepository.setTrashedPhotos(photos)
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onShowEmptyTrashConfirm()
        viewModel.onConfirmEmptyTrash()
        fakePhotoRepository.setTrashedPhotos(emptyList())
        advanceUntilIdle()

        val expected = photos.sumOf { it.size!! }
        assertEquals(expected, fakePreferences.totalFreedBytesFlow.value)
        assertEquals(expected, viewModel.uiState.value.totalFreedBytes)
    }

    @Test
    fun `部分残留_只累计消失的_size 为 null 记 0`() = runTest(testDispatcher) {
        val photos = listOf(
            TestPhotoData.createTestPhoto(1).copy(isTrashed = true, size = 100L),
            TestPhotoData.createTestPhoto(2).copy(isTrashed = true, size = null),
            TestPhotoData.createTestPhoto(3).copy(isTrashed = true, size = 50L),
        )
        fakePhotoRepository.setTrashedPhotos(photos)
        fakeReviewRepository.deletePermanentlyResult =
            PhotoOperationResult.Failure(PhotoOperationFailureReason.MediaStoreFailed)
        fakeReviewRepository.onDeletePermanently = {
            fakePhotoRepository.setTrashedPhotos(photos.filter { it.id == 3L })
        }
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onShowEmptyTrashConfirm()
        viewModel.onConfirmEmptyTrash()
        advanceUntilIdle()

        assertEquals(100L, fakePreferences.totalFreedBytesFlow.value)
        assertEquals(100L, viewModel.uiState.value.totalFreedBytes)
    }

    @Test
    fun `刷新后没有照片消失_不写入累计`() = runTest(testDispatcher) {
        val photos = TestPhotoData.createTestPhotos(2).map { it.copy(isTrashed = true) }
        fakePhotoRepository.setTrashedPhotos(photos)
        fakeReviewRepository.deletePermanentlyResult =
            PhotoOperationResult.Failure(PhotoOperationFailureReason.MediaStoreFailed)
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onShowEmptyTrashConfirm()
        viewModel.onConfirmEmptyTrash()
        advanceUntilIdle()

        assertEquals(0L, fakePreferences.totalFreedBytesFlow.value)
        assertEquals(0L, viewModel.uiState.value.totalFreedBytes)
    }
}
