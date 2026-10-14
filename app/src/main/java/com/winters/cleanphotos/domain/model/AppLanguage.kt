package com.winters.cleanphotos.domain.model

/**
 * App 内可选语言。[System] 表示跟随系统：系统是中文显示简体中文，其他语言显示英文
 * （英文是默认资源）。[tag] 是交给 AppCompatDelegate / LocaleManager 的 BCP 47 标签。
 */
enum class AppLanguage(val tag: String) {
    System(""),
    SimplifiedChinese("zh-CN"),
    English("en"),
    ;

    companion object {
        /**
         * 由已保存的应用语言标签（逗号分隔的 LocaleList，可能为空）还原选项。
         * 只看第一个语言：zh 开头 → 简体中文，en 开头 → English，空或其他 → 跟随系统。
         */
        fun fromTags(tags: String?): AppLanguage {
            val first = tags?.split(',')?.firstOrNull()?.trim().orEmpty()
            if (first.isEmpty()) return System
            val language = first.substringBefore('-').substringBefore('_').lowercase()
            return when (language) {
                "zh" -> SimplifiedChinese
                "en" -> English
                else -> System
            }
        }

        /** 系统语言 → 实际显示语言：中文显示简体中文，其他一律英文。 */
        fun resolveForSystem(systemLanguage: String): AppLanguage =
            if (systemLanguage.lowercase() == "zh") SimplifiedChinese else English
    }
}
