package com.winters.cleanphotos.core.locale

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winters.cleanphotos.R
import com.winters.cleanphotos.core.design.DarkroomColors
import com.winters.cleanphotos.core.design.DarkroomIcons
import com.winters.cleanphotos.core.design.DarkroomText
import com.winters.cleanphotos.core.design.Dimens
import com.winters.cleanphotos.core.design.MotionDurations
import com.winters.cleanphotos.core.design.MotionEasings
import com.winters.cleanphotos.core.design.plainClickable
import com.winters.cleanphotos.domain.model.AppLanguage

/** 菜单和语言弹层里显示的语言名。简体中文 / English 用各自的语言写。 */
@StringRes
fun AppLanguage.labelRes(): Int = when (this) {
    AppLanguage.System -> R.string.language_follow_system
    AppLanguage.SimplifiedChinese -> R.string.language_zh
    AppLanguage.English -> R.string.language_en
}

/**
 * 语言底部弹层：64% 压暗 + 托盘色面板（上圆角 24），标题「语言」+ 三行选项（56dp），当前项相纸白 500 + 琥珀勾。
 * 从底部滑入 / 淡入 180ms，点压暗或返回关闭。所有系统版本都用这个弹层，不跳系统设置；选中后立即生效（系统 / AppCompat 重建界面）。
 */
@Composable
fun LanguageSheet(
    visible: Boolean,
    current: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(MotionDurations.MENU, easing = MotionEasings.Standard)),
            exit = fadeOut(tween(MotionDurations.SHORT, easing = MotionEasings.Standard)),
        ) {
            Box(Modifier.fillMaxSize().background(DarkroomColors.DialogScrim).plainClickable(onClick = onDismiss))
        }
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(MotionDurations.MENU, easing = MotionEasings.Standard)) { it } +
                fadeIn(tween(MotionDurations.MENU, easing = MotionEasings.Standard)),
            exit = slideOutVertically(tween(MotionDurations.SHORT, easing = MotionEasings.Standard)) { it } +
                fadeOut(tween(MotionDurations.SHORT, easing = MotionEasings.Standard)),
        ) {
            val shape = RoundedCornerShape(topStart = Dimens.DialogCorner, topEnd = Dimens.DialogCorner)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(DarkroomColors.Tray)
                    .border(Dimens.Hairline, DarkroomColors.Rim, shape)
                    .plainClickable(onClick = {})
                    .navigationBarsPadding()
                    .padding(start = 8.dp, end = 8.dp, top = 24.dp, bottom = 16.dp),
            ) {
                Text(
                    stringResource(R.string.language_title),
                    style = DarkroomText.DialogTitle,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
                AppLanguage.entries.forEach { language ->
                    val selected = language == current
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clip(RoundedCornerShape(Dimens.MenuItemCorner))
                            .background(if (selected) DarkroomColors.MenuItemOn else Color.Transparent)
                            // 读屏：单选项，读出「已选中」（琥珀勾是装饰，不单独朗读）
                            .semantics(mergeDescendants = true) {
                                role = Role.RadioButton
                                this.selected = selected
                            }
                            .plainClickable(onClick = { onSelect(language) })
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(language.labelRes()),
                            style = if (selected) {
                                DarkroomText.MenuItem.copy(color = DarkroomColors.Paper, fontWeight = FontWeight.Medium)
                            } else {
                                DarkroomText.MenuItem
                            },
                            maxLines = 1,
                        )
                        Spacer(Modifier.weight(1f))
                        if (selected) {
                            Icon(DarkroomIcons.CheckBold, contentDescription = null, tint = DarkroomColors.Amber, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}
