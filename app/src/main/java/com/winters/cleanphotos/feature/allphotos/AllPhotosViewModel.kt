package com.winters.cleanphotos.feature.allphotos

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.winters.cleanphotos.core.permission.PermissionChecker
import com.winters.cleanphotos.domain.model.PhotoFilterMode
import com.winters.cleanphotos.domain.model.PhotoSortOrder
import com.winters.cleanphotos.domain.repository.PhotoRepository
import com.winters.cleanphotos.domain.usecase.BuildPhotoSectionsUseCase
import com.winters.cleanphotos.domain.usecase.FilterPhotosUseCase
import com.winters.cleanphotos.domain.usecase.ObserveAllPhotosUseCase
import com.winters.cleanphotos.domain.usecase.ObserveAllSeenStatsUseCase
import com.winters.cleanphotos.domain.usecase.ObserveSeenIdsUseCase
import com.winters.cleanphotos.domain.usecase.SortPhotosUseCase
import com.winters.cleanphotos.core.coroutines.DefaultDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 照片页。数据链：ObserveAllPhotos + ObserveSeenIds + ObserveAllSeenStats → 排序 → 筛选 → BuildPhotoSections。
 * 「看过」状态只经由 UseCase 读取。
 */
@HiltViewModel
class AllPhotosViewModel @Inject constructor(
    application: Application,
    private val photoRepository: PhotoRepository,
    private val observeAllPhotosUseCase: ObserveAllPhotosUseCase,
    private val sortPhotosUseCase: SortPhotosUseCase,
    private val filterPhotosUseCase: FilterPhotosUseCase,
    private val buildPhotoSectionsUseCase: BuildPhotoSectionsUseCase,
    private val observeSeenIdsUseCase: ObserveSeenIdsUseCase,
    private val observeAllSeenStatsUseCase: ObserveAllSeenStatsUseCase,
    @DefaultDispatcher private val computeDispatcher: CoroutineDispatcher,
) : AndroidViewModel(application) {

    /** 排序、筛选不持久化，进入页面时为默认值。 */
    private val _sortOrder = MutableStateFlow(PhotoSortOrder.NewestFirst)
    private val _filterMode = MutableStateFlow(PhotoFilterMode.AllPhotos)

    private val initialPermission = PermissionChecker.hasPhotoPermission(application)
    private val _uiState = MutableStateFlow(
        AllPhotosUiState(hasPermission = initialPermission, isLoading = initialPermission),
    )
    val uiState: StateFlow<AllPhotosUiState> = _uiState.asStateFlow()

    init {
        observePhotos()
        if (initialPermission) refreshPhotos()
    }

    private fun observePhotos() {
        viewModelScope.launch {
            combine(
                observeAllPhotosUseCase(),
                _sortOrder,
                _filterMode,
                observeSeenIdsUseCase(),
                observeAllSeenStatsUseCase(),
            ) { allPhotos, sortOrder, filterMode, seenIds, stats ->
                val sorted = sortPhotosUseCase(allPhotos, sortOrder)
                val filtered = filterPhotosUseCase(sorted, filterMode, seenIds)
                val current = _uiState.value
                current.copy(
                    totalCount = allPhotos.size,
                    unseenCount = allPhotos.count { it.id !in seenIds },
                    visibleCount = filtered.size,
                    sortOrder = sortOrder,
                    filterMode = filterMode,
                    sections = buildPhotoSectionsUseCase(filtered, dayPool = allPhotos, seenIds = seenIds),
                    seenIds = seenIds,
                    allSeenStats = stats,
                    hangPhotos = sorted.take(HANG_PHOTO_COUNT),
                )
            }
                // 排序 / 分组整个相册不在主线程：本页留在返回栈里时，清理页每次滑动都会让这里重算一遍
                .flowOn(computeDispatcher)
                .collect { state ->
                    // 数据流更新不覆盖加载 / 权限 / 错误这几个由刷新流程维护的字段
                    val now = _uiState.value
                    _uiState.value = state.copy(
                        isLoading = now.isLoading,
                        isInitialLoad = now.isInitialLoad,
                        hasPermission = now.hasPermission,
                        errorMessage = now.errorMessage,
                    )
                }
        }
    }

    fun onPermissionResult(granted: Boolean) {
        if (granted && !_uiState.value.hasPermission) {
            _uiState.value = _uiState.value.copy(hasPermission = true)
            refreshPhotos()
        }
    }

    /** 从系统设置回来时重新检查（拒绝且不再询问 → 去系统设置开启）。 */
    fun recheckPermission() {
        onPermissionResult(PermissionChecker.hasPhotoPermission(getApplication()))
    }

    fun onSortOrderChanged(order: PhotoSortOrder) {
        _sortOrder.value = order
    }

    fun onFilterModeChanged(mode: PhotoFilterMode) {
        _filterMode.value = mode
    }

    private fun refreshPhotos() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, hasPermission = true, errorMessage = null)
            try {
                photoRepository.refreshPhotos()
                _uiState.value = _uiState.value.copy(isLoading = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = "加载照片失败")
            } finally {
                _uiState.value = _uiState.value.copy(isInitialLoad = false)
            }
        }
    }

    private companion object {
        const val HANG_PHOTO_COUNT = 3
    }
}
