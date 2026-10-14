package com.winters.cleanphotos.data.repository

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.winters.cleanphotos.domain.model.AppLanguage
import com.winters.cleanphotos.domain.repository.LanguageRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * 应用语言的读写。
 * - Android 13+：直接读写系统 LocaleManager（与系统「应用语言」设置、`cmd locale set-app-locales` 同一份），
 *   每次都读最新值，不依赖 AppCompat 是否已有活动的 Activity；系统改完会自行重建界面。
 * - Android 8–12：AppCompatDelegate 写入自己的存储（清单里 autoStoreLocales），重建 Activity 生效。
 */
class LanguageRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : LanguageRepository {
    override fun current(): AppLanguage {
        val tags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
        } else {
            AppCompatDelegate.getApplicationLocales().toLanguageTags()
        }
        return AppLanguage.fromTags(tags)
    }

    override fun set(language: AppLanguage) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = if (language == AppLanguage.System) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(language.tag)
            context.getSystemService(LocaleManager::class.java).applicationLocales = locales
        } else {
            val locales = if (language == AppLanguage.System) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(language.tag)
            }
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }
}
