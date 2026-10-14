package com.winters.cleanphotos.feature.language

import androidx.lifecycle.ViewModel
import com.winters.cleanphotos.domain.model.AppLanguage
import com.winters.cleanphotos.domain.usecase.GetAppLanguageUseCase
import com.winters.cleanphotos.domain.usecase.SetAppLanguageUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/** 照片页「⋯ → 语言」。语言由系统 / AppCompat 保存，这里只读写，不另存。 */
@HiltViewModel
class LanguageViewModel @Inject constructor(
    private val getAppLanguage: GetAppLanguageUseCase,
    private val setAppLanguage: SetAppLanguageUseCase,
) : ViewModel() {
    private val _current = MutableStateFlow(getAppLanguage())
    val current: StateFlow<AppLanguage> = _current.asStateFlow()

    /** 从系统设置页回来（Android 13+）时重新读一次。 */
    fun refresh() {
        _current.value = getAppLanguage()
    }

    fun select(language: AppLanguage) {
        _current.value = language
        setAppLanguage(language)
    }
}
