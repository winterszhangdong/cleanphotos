package com.winters.cleanphotos.data.repository

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.winters.cleanphotos.domain.model.AppLanguage
import com.winters.cleanphotos.domain.repository.LanguageRepository
import javax.inject.Inject

/**
 * 用 AppCompatDelegate 设置应用语言：Android 13+ 转交系统 LocaleManager（与系统「应用语言」设置同一份），
 * Android 8–12 由 AppCompat 写入自己的存储（清单里 autoStoreLocales），重建 Activity 生效。
 */
class LanguageRepositoryImpl @Inject constructor() : LanguageRepository {
    override fun current(): AppLanguage =
        AppLanguage.fromTags(AppCompatDelegate.getApplicationLocales().toLanguageTags())

    override fun set(language: AppLanguage) {
        val locales = if (language == AppLanguage.System) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(language.tag)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }
}
