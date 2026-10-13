package com.winters.cleanphotos.core.util

import java.text.NumberFormat
import java.util.Locale

object NumberFormatter {
    private val numberFormat = NumberFormat.getNumberInstance(Locale.US)

    fun formatCount(count: Int): String {
        return numberFormat.format(count)
    }
}
