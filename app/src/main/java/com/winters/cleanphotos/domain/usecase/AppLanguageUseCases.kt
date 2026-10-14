package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.domain.model.AppLanguage
import com.winters.cleanphotos.domain.repository.LanguageRepository
import javax.inject.Inject

class GetAppLanguageUseCase @Inject constructor(
    private val languageRepository: LanguageRepository,
) {
    operator fun invoke(): AppLanguage = languageRepository.current()
}

class SetAppLanguageUseCase @Inject constructor(
    private val languageRepository: LanguageRepository,
) {
    /** 选的就是当前语言时不动，避免无谓地重建界面。 */
    operator fun invoke(language: AppLanguage) {
        if (languageRepository.current() == language) return
        languageRepository.set(language)
    }
}
