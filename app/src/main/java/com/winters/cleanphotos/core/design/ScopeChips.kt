package com.winters.cleanphotos.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics

data class ScopeChipItem(val label: String, val selected: Boolean, val onClick: () -> Unit)

/**
 * 照片页范围按钮行（task032 G3）。
 * 高 40（含下边 8），胶囊高 32、左右 14、间距 8、13sp。
 * 未选：托盘底 + 9% 细线 + dim 字；选中：相纸白底 + 暗室色字。一行放不下就横向滑动。
 */
@Composable
fun ScopeChipRow(items: List<ScopeChipItem>, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(Dimens.ScopeRowHeight)
            .horizontalScroll(rememberScrollState())
            .padding(start = Dimens.ScreenSide, end = Dimens.ScreenSide, bottom = Dimens.ScopeChipGap),
        horizontalArrangement = Arrangement.spacedBy(Dimens.ScopeChipGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            val shape = RoundedCornerShape(Dimens.ScopeChipHeight / 2)
            Box(
                Modifier
                    .height(Dimens.ScopeChipHeight)
                    .clip(shape)
                    .background(if (item.selected) DarkroomColors.Paper else DarkroomColors.Tray)
                    .border(Dimens.Hairline, DarkroomColors.Rim, shape)
                    .semantics { selected = item.selected }
                    .plainClickable(onClick = item.onClick)
                    .padding(horizontal = Dimens.ScopeChipPadH),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    item.label,
                    style = DarkroomText.Button13.copy(color = if (item.selected) DarkroomColors.Room else DarkroomColors.Dim),
                )
            }
        }
    }
}
