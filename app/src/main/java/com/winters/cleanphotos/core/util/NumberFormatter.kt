package com.winters.cleanphotos.core.util

import java.text.NumberFormat
import java.util.Locale

/** 张数等计数：按界面语言做千分位（中文、英文都是「1,234」）。 */
object NumberFormatter {
    fun formatCount(count: Int, locale: Locale = Locale.US): String {
        return NumberFormat.getIntegerInstance(locale).format(count)
    }
}
