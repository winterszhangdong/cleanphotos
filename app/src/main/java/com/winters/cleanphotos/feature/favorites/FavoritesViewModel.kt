package com.winters.cleanphotos.feature.favorites

import kotlinx.coroutines.CancellationException
import com.winters.cleanphotos.core.text.UiText
import com.winters.cleanphotos.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.winters.cleanphotos.domain.model.PhotoSortOrder
import com.winters.cleanphotos.domain.repository.PhotoRepository
import com.winters.cleanphotos.domain.usecase.BuildFiledDaysUseCase
import com.winters.cleanphotos.domain.usecase.BuildPhotoSectionsUseCase
import com.winters.cleanphotos.domain.usecase.ObserveFilingLogUseCase
import com.winters.cleanphotos.domain.usecase.ObserveUnseenCountUseCase
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
 * 收藏页。收藏缩略图不画未看光点（收藏本身就是处理过）。
 */
@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val photoRepository: PhotoRepository,
    private val sortPhotosUseCase: SortPhotosUseCase,
    private val buildPhotoSectionsUseCase: BuildPhotoSectionsUseCase,
    private val observeUnseenCountUseCase: ObserveUnseenCountUseCase,
    private val observeFilingLog: ObserveFilingLogUseCase,
    private val buildFiledDays: BuildFiledDaysUseCase,
    @DefaultDispatcher private val computeDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FavoritesUiState())
    val uiState: StateFlow<FavoritesUiState> = _uiState.asStateFlow()

    init {
        observeFavorites()
        refreshFavorites()
    }

    private fun observeFavorites() {
        viewModelScope.launch {
            combine(
                photoRepository.observeFavoritedPhotos(),
                photoRepository.observeAllPhotos(),
                observeUnseenCountUseCase(),
                observeFilingLog(),
            ) { favoritedPhotos, others, unseen, log ->
                val sorted = sortPhotosUseCase(favoritedPhotos, PhotoSortOrder.NewestFirst)
                val sections = buildPhotoSectionsUseCase(sorted, dayPool = favoritedPhotos, seenIds = emptySet())
                FavoritesUiState(
                    totalCount = favoritedPhotos.size,
                    sections = sections,
                    pendingDays = sections.size,
                    filedDays = buildFiledDays(log, (favoritedPhotos + others).associateBy { it.id }),
                    unseenCount = unseen,
                )
            }
                // 排序 / 分组不在主线程（同 AllPhotosViewModel）
                .flowOn(computeDispatcher)
                .collect { state ->
                    // 加载中 / 错误由刷新流程维护：首次刷新完成前照片表还是空的，不能先闪「还没有收藏」
                    val now = _uiState.value
                    _uiState.value = state.copy(isLoading = now.isLoading, errorMessage = now.errorMessage)
                }
        }
    }

    private fun refreshFavorites() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            try {
                photoRepository.refreshPhotos()
                _uiState.value = _uiState.value.copy(isLoading = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = UiText.Res(R.string.error_load_favorites))
            }
        }
    }
}
