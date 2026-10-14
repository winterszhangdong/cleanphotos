package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.domain.model.AppLanguage
import com.winters.cleanphotos.domain.repository.LanguageRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageUseCasesTest {
    private class FakeLanguageRepository(var value: AppLanguage = AppLanguage.System) : LanguageRepository {
        var setCalls = 0
        override fun current() = value
        override fun set(language: AppLanguage) { setCalls++; value = language }
    }

    @Test
    fun set_changesLanguage() {
        val repo = FakeLanguageRepository()
        SetAppLanguageUseCase(repo)(AppLanguage.English)
        assertEquals(AppLanguage.English, GetAppLanguageUseCase(repo)())
        assertEquals(1, repo.setCalls)
    }

    @Test
    fun set_sameLanguage_doesNotTouchRepository() {
        val repo = FakeLanguageRepository(AppLanguage.SimplifiedChinese)
        SetAppLanguageUseCase(repo)(AppLanguage.SimplifiedChinese)
        assertEquals(0, repo.setCalls)
    }
}
