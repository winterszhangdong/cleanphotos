package com.winters.cleanphotos.feature.favorites

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil.request.ImageRequest
import com.winters.cleanphotos.R
import com.winters.cleanphotos.core.design.DarkroomColors
import com.winters.cleanphotos.core.design.DarkroomIcons
import com.winters.cleanphotos.core.design.DarkroomText
import com.winters.cleanphotos.core.design.Dimens
import com.winters.cleanphotos.core.design.PhotoTile
import com.winters.cleanphotos.core.design.photoThumbKey
import com.winters.cleanphotos.core.design.plainClickable
import com.winters.cleanphotos.core.image.GridThumb
import com.winters.cleanphotos.core.locale.currentLocale
import com.winters.cleanphotos.core.util.DayDisplay
import com.winters.cleanphotos.domain.model.DayKey
import com.winters.cleanphotos.domain.model.FiledDay
import com.winters.cleanphotos.domain.model.MediaType

/**
 * 日期行右侧的「整理」：32dp 可见、热区由日期行右侧保证至少 48dp。小胶片图标 + 文字。
 */
@Composable
internal fun SortDayPill() {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .height(32.dp)
            .clip(shape)
            .background(DarkroomColors.Tray)
            .border(Dimens.Hairline, DarkroomColors.Rim2, shape)
            .padding(start = 10.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 14×10 的小胶片：中间一格，上下一排齿孔
        Box(
            Modifier.size(14.dp, 10.dp).drawBehind {
                val c = DarkroomColors.Paper
                val band = 2.dp.toPx()
                drawRect(c, Offset(0f, (size.height - 4.dp.toPx()) / 2f), Size(size.width, 4.dp.toPx()))
                var x = 0f
                while (x < size.width) {
                    drawRect(c, Offset(x, 0f), Size(band, band))
                    drawRect(c, Offset(x, size.height - band), Size(band, band))
                    x += band * 2
                }
            },
        )
        Text(stringResource(R.string.favorites_sort_day), style = DarkroomText.Button13.copy(fontSize = DarkroomText.TraySwitch.fontSize))
    }
}

/**
 * 收藏页底部「已冲洗」：分隔线 + 每天一行（右侧是去了哪几个袋子、各几张）。点行展开这一天放过的照片，只读。
 */
internal fun LazyGridScope.filedDaysSection(
    days: List<FiledDay>,
    expanded: String?,
    onToggle: (String) -> Unit,
) {
    if (days.isEmpty()) return
    item(key = "filed-header", span = { GridItemSpan(maxLineSpan) }, contentType = "filed-header") {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp).height(Dimens.YearHeight).padding(horizontal = Dimens.ScreenSide),
            horizontalArrangement = Arrangement.spacedBy(Dimens.YearGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.favorites_filed_header, days.sumOf { it.count }), style = DarkroomText.Year)
            Box(Modifier.weight(1f).height(Dimens.Hairline).background(DarkroomColors.Rim))
        }
    }
    days.forEach { day ->
        item(key = "filed-day-${day.dayKey}", span = { GridItemSpan(maxLineSpan) }, contentType = "filed-day") {
            FiledDayRow(day, open = expanded == day.dayKey, onClick = { onToggle(day.dayKey) })
        }
        if (expanded == day.dayKey) {
            day.photos.forEach { photo ->
                item(key = "filed-${photo.id}", contentType = "photo") {
                    val context = LocalContext.current
                    val model = remember(photo.id) {
                        ImageRequest.Builder(context)
                            .data(GridThumb(photo.uri))
                            .memoryCacheKey(photoThumbKey(photo.id))
                            .placeholderMemoryCacheKey(photoThumbKey(photo.id))
                            .allowRgb565(true)
                            .crossfade(false)
                            .build()
                    }
                    PhotoTile(
                        model = model,
                        contentDescription = photo.displayName,
                        isVideo = photo.mediaType == MediaType.Video,
                        durationMs = photo.durationMs,
                        developProfile = null,
                        modifier = Modifier.padding(top = Dimens.GridGap),
                    )
                }
            }
        }
    }
}

@Composable
private fun FiledDayRow(day: FiledDay, open: Boolean, onClick: () -> Unit) {
    val locale = currentLocale()
    val date = DayKey.parse(day.dayKey)
    val label = date?.let { DayDisplay.dayLabel(it, locale) } ?: DayDisplay.unknownDayLabel(stringResource(R.string.date_unknown))
    Row(
        Modifier
            .fillMaxWidth()
            .height(Dimens.MinTouch)
            .plainClickable(onClick = onClick)
            .padding(horizontal = Dimens.ScreenSide),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label.dayNumeral, style = DarkroomText.Num.copy(fontSize = DarkroomText.SelNum.fontSize))
        Text(label.monthLabel, style = DarkroomText.SelSum, modifier = Modifier.weight(1f), maxLines = 1)
        day.albums.take(MAX_CHIPS).forEach { a ->
            Row(
                Modifier
                    .height(22.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(DarkroomColors.Kraft)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    buildAnnotatedString {
                        append(a.name)
                        append(" ")
                        withStyle(SpanStyle(fontFamily = DarkroomText.Mono.fontFamily, fontWeight = FontWeight.Medium)) { append("+${a.count}") }
                    },
                    style = DarkroomText.TraySwitch.copy(color = DarkroomColors.Label),
                    maxLines = 1,
                )
            }
        }
        Icon(
            DarkroomIcons.Chevron, null, tint = DarkroomColors.Dim,
            modifier = Modifier.size(Dimens.DayChevron).graphicsLayer { rotationZ = if (open) 90f else 0f },
        )
    }
}

/** 一行最多两个袋子，再多就挤了。 */
private const val MAX_CHIPS = 2
