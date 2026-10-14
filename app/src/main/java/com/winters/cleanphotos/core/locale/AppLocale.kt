package com.winters.cleanphotos.core.locale

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale

/** 当前界面语言（跟随 App 语言设置），日期和数字格式化都用它，而不是 JVM 默认 Locale。 */
@Composable
@ReadOnlyComposable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
