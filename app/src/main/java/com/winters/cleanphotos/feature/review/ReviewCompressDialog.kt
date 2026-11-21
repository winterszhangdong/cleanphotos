package com.winters.cleanphotos.feature.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.winters.cleanphotos.R
import com.winters.cleanphotos.core.design.DarkroomColors
import com.winters.cleanphotos.core.design.DarkroomText
import com.winters.cleanphotos.core.design.Dimens
import com.winters.cleanphotos.core.design.plainClickable

/**
 * 压缩确认。结构和清空垃圾桶确认框一样（暗房托盘、遮罩、取消），确认钮用相纸白。
 */
@Composable
internal fun CompressConfirmDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Box(Modifier.fillMaxSize().zIndex(30f)) {
        Box(Modifier.fillMaxSize().background(DarkroomColors.DialogScrim).plainClickable(onClick = onDismiss))
        val shape = RoundedCornerShape(Dimens.DialogCorner)
        Column(
            Modifier
                .align(Alignment.Center)
                .padding(horizontal = Dimens.DialogSide)
                .fillMaxWidth()
                .dropShadow(shape, Shadow(radius = 48.dp, color = DarkroomColors.DialogShadow, offset = DpOffset(0.dp, 24.dp)))
                .clip(shape)
                .background(DarkroomColors.Tray)
                .border(Dimens.Hairline, DarkroomColors.Rim, shape)
                .plainClickable(onClick = {})
                .padding(24.dp),
        ) {
            Text(stringResource(R.string.compress_confirm_title), style = DarkroomText.DialogTitle)
            Text(
                stringResource(R.string.compress_confirm_body),
                style = DarkroomText.DialogBody,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                CompressDialogButton(stringResource(R.string.cancel), DarkroomColors.Paper, null, onDismiss)
                CompressDialogButton(stringResource(R.string.compress_action), DarkroomColors.Room, DarkroomColors.Paper, onConfirm)
            }
        }
    }
}

@Composable
private fun CompressDialogButton(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    fill: androidx.compose.ui.graphics.Color?,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .then(if (fill != null) Modifier.background(fill) else Modifier)
            .plainClickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = DarkroomText.Button14.copy(color = color)) }
}
