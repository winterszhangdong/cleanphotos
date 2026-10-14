package com.winters.cleanphotos.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test
    fun fromTags_emptyMeansFollowSystem() {
        assertEquals(AppLanguage.System, AppLanguage.fromTags(""))
        assertEquals(AppLanguage.System, AppLanguage.fromTags(null))
    }

    @Test
    fun fromTags_mapsChineseVariantsToSimplifiedChinese() {
        assertEquals(AppLanguage.SimplifiedChinese, AppLanguage.fromTags("zh-CN"))
        assertEquals(AppLanguage.SimplifiedChinese, AppLanguage.fromTags("zh-Hans-CN"))
        assertEquals(AppLanguage.SimplifiedChinese, AppLanguage.fromTags("zh_CN"))
    }

    @Test
    fun fromTags_mapsEnglishAndUsesFirstOnly() {
        assertEquals(AppLanguage.English, AppLanguage.fromTags("en"))
        assertEquals(AppLanguage.English, AppLanguage.fromTags("en-US,zh-CN"))
        assertEquals(AppLanguage.SimplifiedChinese, AppLanguage.fromTags("zh-CN,en"))
    }

    @Test
    fun fromTags_unsupportedLanguageFallsBackToSystem() {
        assertEquals(AppLanguage.System, AppLanguage.fromTags("ja"))
    }

    @Test
    fun tags_roundTrip() {
        AppLanguage.entries.forEach { assertEquals(it, AppLanguage.fromTags(it.tag)) }
    }

    @Test
    fun resolveForSystem_chineseOrEnglish() {
        assertEquals(AppLanguage.SimplifiedChinese, AppLanguage.resolveForSystem("zh"))
        assertEquals(AppLanguage.English, AppLanguage.resolveForSystem("ja"))
        assertEquals(AppLanguage.English, AppLanguage.resolveForSystem("en"))
    }
}
