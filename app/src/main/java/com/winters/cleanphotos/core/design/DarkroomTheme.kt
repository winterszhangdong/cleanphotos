package com.winters.cleanphotos.core.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle

/**
 * 只有深色（暗房）主题。Material 槽位全部映射到 [DarkroomColors]，排版全部来自 [DarkroomFonts]。
 * 界面代码直接用 DarkroomColors / DarkroomText，不经过 MaterialTheme 读颜色。
 */
private val DarkroomColorScheme = darkColorScheme(
    primary = DarkroomColors.Paper,
    onPrimary = DarkroomColors.Room,
    secondary = DarkroomColors.Dim,
    onSecondary = DarkroomColors.Room,
    tertiary = DarkroomColors.Amber,
    background = DarkroomColors.Room,
    onBackground = DarkroomColors.Paper,
    surface = DarkroomColors.Room,
    onSurface = DarkroomColors.Paper,
    surfaceVariant = DarkroomColors.Tray,
    onSurfaceVariant = DarkroomColors.Dim,
    surfaceContainer = DarkroomColors.Tray,
    outline = DarkroomColors.Rim2,
    outlineVariant = DarkroomColors.Rim,
    error = DarkroomColors.Delete,
    onError = DarkroomColors.OnDelete,
)

private val base = TextStyle(fontFamily = DarkroomFonts.NotoSansSc)

private val DarkroomTypography = Typography(
    bodyLarge = DarkroomText.Body,
    bodyMedium = DarkroomText.Body,
    bodySmall = DarkroomText.Caption,
    labelLarge = DarkroomText.Body,
    labelMedium = DarkroomText.Eyebrow,
    labelSmall = DarkroomText.Mono,
    titleLarge = DarkroomText.EmptyTitle,
    titleMedium = DarkroomText.DialogTitle,
    titleSmall = base,
)

@Composable
fun CleanphotosTheme(content: @Composable () -> Unit) {
    ProvideAnimationScale {
        MaterialTheme(
            colorScheme = DarkroomColorScheme,
            typography = DarkroomTypography,
            content = content,
        )
    }
}
