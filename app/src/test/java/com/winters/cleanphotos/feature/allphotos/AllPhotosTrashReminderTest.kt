package com.winters.cleanphotos.feature.allphotos

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import app.cash.turbine.test
import com.winters.cleanphotos.R
import com.winters.cleanphotos.TestPhotoData
import com.winters.cleanphotos.core.text.UiText
import com.winters.cleanphotos.domain.model.AppTrashEntry
import com.winters.cleanphotos.domain.model.TrashReminderRules
import com.winters.cleanphotos.domain.usecase.BatchFavoritePhotosUseCase
import com.winters.cleanphotos.domain.usecase.BatchMovePhotosToTrashUseCase
import com.winters.cleanphotos.domain.usecase.BuildPhotoSectionsUseCase
import com.winters.cleanphotos.domain.usecase.FilterPhotosUseCase
import com.winters.cleanphotos.domain.usecase.MarkTrashReminderShownUseCase
import com.winters.cleanphotos.domain.usecase.ObserveAllPhotosUseCase
import com.winters.cleanphotos.domain.usecase.ObserveAllSeenStatsUseCase
import com.winters.cleanphotos.domain.usecase.ObserveSeenIdsUseCase
import com.winters.cleanphotos.domain.usecase.ObserveTrashMovedAtUseCase
import com.winters.cleanphotos.domain.usecase.ObserveTrashReminderUseCase
import com.winters.cleanphotos.domain.usecase.ObserveTrashSummaryUseCase
import com.winters.cleanphotos.domain.usecase.ResolveBatchFavoriteUseCase
import com.winters.cleanphotos.domain.usecase.SortPhotosUseCase
import com.winters.cleanphotos.domain.usecase.UndoBatchActionUseCase
import com.winters.cleanphotos.fake.FakePhotoRepository
import com.winters.cleanphotos.fake.FakeReviewRepository
import com.winters.cleanphotos.fake.FakeSeenStateRepository
import com.winters.cleanphotos.fake.FakeUserPreferencesRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AllPhotosTrashReminderTest {

    private val testDispatcher = StandardTestDispatcher()
    private val fakePhotoRepository = FakePhotoRepository()
    private val fakeSeen = FakeSeenStateRepository()
    private val fakeReviewRepository = FakeReviewRepository()
    private val fakePrefs = FakeUserPreferencesRepository()
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(ContextCompat::class)
        every { ContextCompat.checkSelfPermission(any<Context>(), any<String>()) } returns PackageManager.PERMISSION_GRANTED
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers { mockk(relaxed = true) }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(ContextCompat::class)
        unmockkStatic(Uri::class)
    }

    @Test
    fun `满 1 GB 时出现并写入 shownAt`() = runTest(testDispatcher) {
        fakePhotoRepository.setPhotos(TestPhotoData.createTestPhotos(1))
        putTrash(bytes = TrashReminderRules.SIZE_THRESHOLD_BYTES, movedAt = now)
        val vm = createViewModel()
        advanceUntilIdle()
        val message = vm.uiState.value.trashReminder as UiText.Res
        assertEquals(R.string.trash_reminder_size, message.id)
        assertEquals(listOf(UiText.Bytes(TrashReminderRules.SIZE_THRESHOLD_BYTES)), message.args)
        assertTrue(fakePrefs.trashReminderShownAt.value != null)
    }

    @Test
    fun `只有天数到期时用天数文案`() = runTest(testDispatcher) {
        fakePhotoRepository.setPhotos(TestPhotoData.createTestPhotos(1))
        putTrash(bytes = 1L, movedAt = now - TrashReminderRules.AGE_THRESHOLD_MS)
        val vm = createViewModel()
        advanceUntilIdle()
        assertEquals(R.string.trash_reminder_age, (vm.uiState.value.trashReminder as UiText.Res).id)
    }

    @Test
    fun `照片页没有照片时不出现也不写入`() = runTest(testDispatcher) {
        putTrash(bytes = TrashReminderRules.SIZE_THRESHOLD_BYTES, movedAt = now)
        val vm = createViewModel()
        advanceUntilIdle()
        assertNull(vm.uiState.value.trashReminder)
        assertNull(fakePrefs.trashReminderShownAt.value)
    }

    @Test
    fun `多选时不出现_退出后才写入`() = runTest(testDispatcher) {
        fakePhotoRepository.setPhotos(TestPhotoData.createTestPhotos(2))
        val vm = createViewModel()
        advanceUntilIdle()
        vm.onLongPressPhoto(1L)
        putTrash(bytes = TrashReminderRules.SIZE_THRESHOLD_BYTES, movedAt = now)
        advanceUntilIdle()
        assertNull(vm.uiState.value.trashReminder)
        assertNull(fakePrefs.trashReminderShownAt.value)
        vm.onExitSelection()
        advanceUntilIdle()
        assertEquals(R.string.trash_reminder_size, (vm.uiState.value.trashReminder as UiText.Res).id)
        assertTrue(fakePrefs.trashReminderShownAt.value != null)
    }

    @Test
    fun `撤回胶囊显示时不出现`() = runTest(testDispatcher) {
        fakePhotoRepository.setPhotos(TestPhotoData.createTestPhotos(2))
        val vm = createViewModel()
        advanceUntilIdle()
        vm.onLongPressPhoto(1L)
        vm.onBatchFavorite()
        advanceUntilIdle()
        putTrash(bytes = TrashReminderRules.SIZE_THRESHOLD_BYTES, movedAt = now)
        advanceUntilIdle()
        assertTrue(vm.selection.value.undo != null)
        assertNull(vm.uiState.value.trashReminder)
        assertNull(fakePrefs.trashReminderShownAt.value)
    }

    @Test
    fun `菜单打开时不出现`() = runTest(testDispatcher) {
        fakePhotoRepository.setPhotos(TestPhotoData.createTestPhotos(1))
        putTrash(bytes = TrashReminderRules.SIZE_THRESHOLD_BYTES, movedAt = now)
        val vm = createViewModel()
        vm.onTrashReminderBlocked(true)
        advanceUntilIdle()
        assertNull(vm.uiState.value.trashReminder)
        assertNull(fakePrefs.trashReminderShownAt.value)
        vm.onTrashReminderBlocked(false)
        advanceUntilIdle()
        assertEquals(R.string.trash_reminder_size, (vm.uiState.value.trashReminder as UiText.Res).id)
    }

    @Test
    fun `关闭后这次进程不再出现`() = runTest(testDispatcher) {
        fakePhotoRepository.setPhotos(TestPhotoData.createTestPhotos(1))
        putTrash(bytes = TrashReminderRules.SIZE_THRESHOLD_BYTES, movedAt = now)
        val vm = createViewModel()
        advanceUntilIdle()
        val shownAt = fakePrefs.trashReminderShownAt.value
        assertTrue(shownAt != null)
        vm.onDismissTrashReminder()
        putTrash(bytes = TrashReminderRules.SIZE_THRESHOLD_BYTES * 2, movedAt = now - TrashReminderRules.AGE_THRESHOLD_MS)
        advanceUntilIdle()
        assertNull(vm.uiState.value.trashReminder)
        assertEquals(shownAt, fakePrefs.trashReminderShownAt.value)
    }

    @Test
    fun `去看看触发导航并收起`() = runTest(testDispatcher) {
        fakePhotoRepository.setPhotos(TestPhotoData.createTestPhotos(1))
        putTrash(bytes = TrashReminderRules.SIZE_THRESHOLD_BYTES, movedAt = now)
        val vm = createViewModel()
        advanceUntilIdle()
        vm.reviewTrash.test {
            vm.onReviewTrashReminder()
            awaitItem()
        }
        assertNull(vm.uiState.value.trashReminder)
    }

    private fun putTrash(bytes: Long?, movedAt: Long) {
        fakePhotoRepository.setTrashedPhotos(listOf(TestPhotoData.createTestPhoto(id = 50L).copy(size = bytes)))
        fakePhotoRepository.trashEntriesFlow.value = listOf(AppTrashEntry(photoId = 50L, movedToTrashAt = movedAt))
    }

    private fun createViewModel(): AllPhotosViewModel {
        val summary = ObserveTrashSummaryUseCase(fakePhotoRepository)
        return AllPhotosViewModel(
            application = mockk<Application>(relaxed = true),
            photoRepository = fakePhotoRepository,
            observeAllPhotosUseCase = ObserveAllPhotosUseCase(fakePhotoRepository),
            sortPhotosUseCase = SortPhotosUseCase(),
            filterPhotosUseCase = FilterPhotosUseCase(fakeSeen),
            buildPhotoSectionsUseCase = BuildPhotoSectionsUseCase(),
            observeSeenIdsUseCase = ObserveSeenIdsUseCase(fakeSeen),
            observeAllSeenStatsUseCase = ObserveAllSeenStatsUseCase(fakePhotoRepository, summary),
            batchFavoritePhotosUseCase = BatchFavoritePhotosUseCase(ResolveBatchFavoriteUseCase(), fakeReviewRepository, fakeSeen),
            batchMovePhotosToTrashUseCase = BatchMovePhotosToTrashUseCase(fakeReviewRepository, fakeSeen),
            undoBatchActionUseCase = UndoBatchActionUseCase(fakeReviewRepository),
            observeTrashReminderUseCase = ObserveTrashReminderUseCase(summary, ObserveTrashMovedAtUseCase(fakePhotoRepository), fakePrefs),
            markTrashReminderShownUseCase = MarkTrashReminderShownUseCase(fakePrefs),
            computeDispatcher = testDispatcher,
        )
    }
}
