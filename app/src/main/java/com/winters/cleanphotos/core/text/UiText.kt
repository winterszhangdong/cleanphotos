package com.winters.cleanphotos.core.text

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalResources
import com.winters.cleanphotos.core.util.DayDisplay
import com.winters.cleanphotos.core.util.FileSizeFormatter
import java.util.Locale

/**
 * ViewModel 产出的界面文案。只记资源 id 和参数，到界面层才按当前语言解析，
 * 这样切换语言重建界面时，存活下来的 ViewModel 状态也会跟着换语言。
 * 参数本身也可以是 [UiText]，解析时递归展开。
 */
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText {
        constructor(@StringRes id: Int, vararg args: Any) : this(id, args.toList())
    }

    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = listOf(count)) : UiText

    /** 文件大小「2.4 MB」，按当前语言格式化数字。 */
    data class Bytes(val bytes: Long) : UiText

    /** 「9月21日」/「Sep 21」；epochDay 为 Long.MIN_VALUE 时是「未知日期」。 */
    data class Date(val epochDay: Long) : UiText

    fun resolve(resources: Resources): String {
        val locale = resources.configuration.locales[0] ?: Locale.getDefault()
        return when (this) {
            is Res -> resources.getString(id, *args.map { it.resolveArg(resources) }.toTypedArray())
            is Plural -> resources.getQuantityString(id, count, *args.map { it.resolveArg(resources) }.toTypedArray())
            is Bytes -> FileSizeFormatter.format(bytes, locale)
            is Date -> DayDisplay.cleanDateOfEpochDay(epochDay, locale)
                ?: resources.getString(com.winters.cleanphotos.R.string.date_unknown)
        }
    }

    private fun Any.resolveArg(resources: Resources): Any = if (this is UiText) resolve(resources) else this
}

@Composable
@ReadOnlyComposable
fun UiText.asString(): String {
    return resolve(LocalResources.current)
}
