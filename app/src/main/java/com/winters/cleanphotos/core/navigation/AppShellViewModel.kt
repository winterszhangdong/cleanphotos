package com.winters.cleanphotos.core.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.winters.cleanphotos.domain.usecase.ObserveTrashSummaryUseCase
import com.winters.cleanphotos.core.coroutines.DefaultDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** 公共层（共享底部导航）的状态：垃圾桶计数跨页连续，里程表才能在切页时接着滚。 */
@HiltViewModel
class AppShellViewModel @Inject constructor(
    observeTrashSummaryUseCase: ObserveTrashSummaryUseCase,
    @DefaultDispatcher computeDispatcher: CoroutineDispatcher,
) : ViewModel() {
    val trashCount: StateFlow<Int> = observeTrashSummaryUseCase()
        .map { it.count }
        // 上游按全库照片筛垃圾桶，常驻的公共层不在主线程算
        .flowOn(computeDispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}
