package com.winters.cleanphotos.core.navigation

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.winters.cleanphotos.core.coroutines.DefaultDispatcher
import com.winters.cleanphotos.core.permission.PermissionChecker
import com.winters.cleanphotos.domain.repository.PhotoRepository
import com.winters.cleanphotos.domain.usecase.ObserveTrashSummaryUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 公共层（共享底部导航）的状态：垃圾桶计数跨页连续，里程表才能在切页时接着滚。 */
@HiltViewModel
class AppShellViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val photoRepository: PhotoRepository,
    observeTrashSummaryUseCase: ObserveTrashSummaryUseCase,
    @DefaultDispatcher computeDispatcher: CoroutineDispatcher,
) : ViewModel() {
    val trashCount: StateFlow<Int> = observeTrashSummaryUseCase()
        .map { it.count }
        // 上游按全库照片筛垃圾桶，常驻的公共层不在主线程算
        .flowOn(computeDispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private var resumedOnce = false

    /**
     * 回到前台：在系统相册 / 其它 App 里增删了照片，回来时重新读一次图库（interaction.md「回到前台刷新」）。
     * 首次进入不算（各页面自己会加载）；距上次刷新不足 [RESUME_REFRESH_MIN_INTERVAL_MS] 跳过（例如刚从系统删除确认回来）。
     */
    fun onAppResumed() {
        if (!resumedOnce) {
            resumedOnce = true
            return
        }
        if (!PermissionChecker.hasPhotoPermission(context)) return
        viewModelScope.launch {
            try {
                photoRepository.refreshPhotosIfStale(RESUME_REFRESH_MIN_INTERVAL_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 静默：回到前台的后台刷新失败不打断当前页面，下次回来或手动重试再刷
                Log.w(TAG, "回到前台刷新图库失败", e)
            }
        }
    }

    private companion object {
        const val TAG = "AppShellViewModel"
        const val RESUME_REFRESH_MIN_INTERVAL_MS = 2_000L
    }
}
