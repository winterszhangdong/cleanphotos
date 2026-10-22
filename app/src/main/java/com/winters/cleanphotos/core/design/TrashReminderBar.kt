package com.winters.cleanphotos.core.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.winters.cleanphotos.R

/**
 * 照片页垃圾桶提醒。外观与撤回胶囊相同（玻璃 56dp、相纸白按钮），没有小样和倒计时。
 * 进出动画用撤回胶囊已有的时长；系统关掉动画时由 Compose 的时长缩放直接跳到终态。
 */
@Composable
fun TrashReminderBar(
    message: String?,
    visible: Boolean,
    onReview: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val last = remember { arrayOfNulls<String>(1) }
    if (message != null) last[0] = message
    AnimatedVisibility(
        visible = message != null && visible,
        modifier = modifier,
        enter = undoCapsuleEnter(),
        exit = undoCapsuleExit(),
    ) {
        val text = message ?: last[0] ?: return@AnimatedVisibility
        TrashReminderCapsule(text, onReview, onDismiss)
    }
}

@Composable
private fun TrashReminderCapsule(message: String, onReview: () -> Unit, onDismiss: () -> Unit) {
    val shape = RoundedCornerShape(Dimens.CapsuleHeight / 2)
    Box(
        Modifier
            .fillMaxWidth()
            .height(Dimens.CapsuleHeight)
            .dropShadow(
                shape,
                Shadow(
                    radius = Dimens.CapsuleShadowBlur,
                    color = DarkroomColors.NavShadow,
                    offset = DpOffset(0.dp, Dimens.CapsuleShadowY),
                ),
            )
            .clip(shape)
            .glassBlur(GlassSpec(DarkroomColors.UndoGlass, Dimens.CapsuleBlur))
            .border(Dimens.Hairline, DarkroomColors.MenuRim, shape),
    ) {
        Row(
            Modifier.padding(horizontal = Dimens.CapsuleInset).height(Dimens.CapsuleHeight),
            horizontalArrangement = Arrangement.spacedBy(Dimens.CapsuleGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(Dimens.GlassButton)
                    .clip(CircleShape)
                    .background(DarkroomColors.Tray2)
                    .border(Dimens.Hairline, DarkroomColors.Rim2, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    DarkroomIcons.Trash,
                    contentDescription = null,
                    tint = DarkroomColors.Fade,
                    modifier = Modifier.size(Dimens.ToolIcon),
                )
            }
            Text(
                message,
                style = DarkroomText.UndoTitle,
                maxLines = 1,
                softWrap = false,
                autoSize = TextAutoSize.StepBased(
                    minFontSize = DarkroomText.UndoSub.fontSize,
                    maxFontSize = DarkroomText.UndoTitle.fontSize,
                ),
                modifier = Modifier.weight(1f),
            )
            Row(
                Modifier
                    .height(Dimens.GlassButton)
                    .clip(RoundedCornerShape(Dimens.FreeCorner))
                    .background(DarkroomColors.Paper)
                    .plainClickable(onClick = onReview)
                    .padding(horizontal = Dimens.TextButtonPaddingH),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.trash_reminder_review), style = DarkroomText.UndoAct, maxLines = 1)
            }
            Box(
                Modifier
                    .size(Dimens.ReminderDismiss)
                    .clip(CircleShape)
                    .plainClickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    DarkroomIcons.Close,
                    contentDescription = stringResource(R.string.cd_dismiss_trash_reminder),
                    tint = DarkroomColors.Fade,
                    modifier = Modifier.size(Dimens.ToolIcon),
                )
            }
        }
    }
}