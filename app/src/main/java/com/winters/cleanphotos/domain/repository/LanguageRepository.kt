package com.winters.cleanphotos.domain.repository

import com.winters.cleanphotos.domain.model.AppLanguage

/** 应用语言。保存由系统 / AppCompat 负责（Android 13+ 存在系统里，8–12 由 AppCompat 自动保存）。 */
interface LanguageRepository {
    fun current(): AppLanguage
    fun set(language: AppLanguage)
}
