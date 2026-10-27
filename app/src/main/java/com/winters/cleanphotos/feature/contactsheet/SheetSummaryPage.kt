package com.winters.cleanphotos.feature.contactsheet

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.winters.cleanphotos.R
import com.winters.cleanphotos.core.design.DarkroomColors
import com.winters.cleanphotos.core.design.DarkroomIcons
import com.winters.cleanphotos.core.design.DarkroomText
import com.winters.cleanphotos.core.design.DevelopProfiles
import com.winters.cleanphotos.core.design.Dimens
import com.winters.cleanphotos.core.design.FilingMotion
import com.winters.cleanphotos.core.design.MotionDurations
import com.winters.cleanphotos.core.design.MotionEasings
import com.winters.cleanphotos.core.design.blockTouchesBelow
import com.winters.cleanphotos.core.design.develop
import com.winters.cleanphotos.core.design.plainClickable
import com.winters.cleanphotos.core.design.rememberDevelopState
import com.winters.cleanphotos.core.locale.currentLocale
import com.winters.cleanphotos.core.util.DayDisplay
import java.time.LocalDate

/**
 * 这一天冲洗完了：剩下的那条空胶片朝左卷起（rotationY + scaleX，明暗渐变假装成圆柱），然后小结按单张显影浮出。
 * 一行一个袋子（露角、名字、本次张数、袋子总数），取消收藏单独一行；主按钮接着整理下一天。
 */
@Composable
internal fun SheetSummaryPage(
    summary: SheetSummary,
    date: LocalDate?,
    stripWidth: Dp,
    cols: Int,
    onNext: (String) -> Unit,
    onBack: () -> Unit,
) {
    val roll = remember { Animatable(0f) }
    val develop = rememberDevelopState(DevelopProfiles.Single, developed = false)
    LaunchedEffect(Unit) {
        roll.animateTo(1f, tween(MotionDurations.LONG, easing = MotionEasings.Accelerate))
        develop.develop()
    }
    val locale = currentLocale()
    Box(
        Modifier
            .fillMaxSize()
            .background(DarkroomColors.Room)
            .blockTouchesBelow(),
    ) {
        // 最后一条空胶片卷起收走
        Box(
            Modifier
                .statusBarsPadding()
                .padding(start = Dimens.ScreenSide, top = Dimens.HeaderHeight + 20.dp)
                .size(stripWidth, stripHeight(stripWidth, cols))
                .graphicsLayer {
                    val t = roll.value
                    transformOrigin = TransformOrigin(0f, 0.5f)
                    cameraDistance = FilingMotion.CAMERA_DISTANCE * density
                    rotationY = FilingMotion.ROLL_ANGLE * t
                    scaleX = 1f - (1f - FilingMotion.ROLL_SCALE_X) * t
                    alpha = if (t >= 1f) 0f else 1f
                }
                .drawBehind { drawRect(DarkroomColors.Film) }
                .drawWithContent {
                    drawContent()
                    val t = roll.value
                    drawRect(
                        Brush.horizontalGradient(
                            0.4f to Color.Transparent, 0.8f to Color.Black.copy(alpha = 0.65f),
                            0.92f to DarkroomColors.Paper.copy(alpha = 0.12f), 1f to Color.Black.copy(alpha = 0.8f),
                        ),
                        alpha = t,
                    )
                },
        ) {
            Row(
                Modifier.padding(horizontal = FilmGeometry.EndInset, vertical = FilmGeometry.Band),
                horizontalArrangement = Arrangement.spacedBy(FilmGeometry.FrameGap),
            ) {
                repeat(cols) {
                    Box(
                        Modifier
                            .size(FilmGeometry.frameWidth(stripWidth, cols), FilmGeometry.frameWidth(stripWidth, cols) * 3f / 4f)
                            .border(Dimens.Hairline, DarkroomColors.Rim),
                    )
                }
            }
        }

        Column(
            Modifier
                .statusBarsPadding()
                .padding(start = 24.dp, end = 24.dp, top = 96.dp)
                .graphicsLayer { alpha = if (roll.value >= 1f) 1f else 0f }
                .develop(develop),
        ) {
            Text(
                date?.let { DayDisplay.cleanDate(it, locale) + " · " + DayDisplay.weekdayOf(it, locale) } ?: stringResource(R.string.date_unknown),
                style = DarkroomText.SumEyebrow,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Text(stringResource(R.string.summary_filed_title), style = DarkroomText.SumTitle)
            Column(Modifier.padding(top = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                summary.albums.forEach { a ->
                    SummaryRow(
                        lead = { MiniEnvelope(a.cover) },
                        title = a.name,
                        bold = "+${a.added}",
                        rest = if (a.created) stringResource(R.string.summary_filed_new) else stringResource(R.string.summary_filed_total, a.total),
                        filled = true,
                    )
                }
                if (summary.unfavorited > 0) {
                    SummaryRow(
                        lead = { Icon(DarkroomIcons.Star, null, tint = DarkroomColors.Dim, modifier = Modifier.size(16.dp)) },
                        title = stringResource(R.string.action_unfavorite),
                        bold = "${summary.unfavorited}",
                        rest = stringResource(R.string.summary_unfavorited_rest),
                        filled = false,
                    )
                }
            }
        }

        val next = summary.next
        if (next != null) {
            NextDayCard(
                next,
                onClick = { onNext(next.dayKey) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 88.dp)
                    .graphicsLayer { alpha = if (roll.value >= 1f) 1f else 0f }
                    .develop(develop),
            )
        }
        Text(
            stringResource(R.string.back_to_favorites),
            style = DarkroomText.Later,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 40.dp)
                .plainClickable(onClick = onBack)
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun SummaryRow(lead: @Composable () -> Unit, title: String, bold: String, rest: String, filled: Boolean) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(shape)
            .then(if (filled) Modifier.background(DarkroomColors.Tray) else Modifier)
            .border(Dimens.Hairline, DarkroomColors.Rim, shape)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) { lead() }
        Text(title, style = DarkroomText.Body.copy(color = if (filled) DarkroomColors.Paper else DarkroomColors.Dim), modifier = Modifier.weight(1f), maxLines = 1)
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = DarkroomColors.Paper, fontWeight = FontWeight.Medium)) { append(bold) }
                append(rest)
            },
            style = DarkroomText.SelSumMono,
        )
    }
}

/** 小结里的迷你袋子：露角 + 牛皮纸正面。 */
@Composable
private fun MiniEnvelope(cover: com.winters.cleanphotos.domain.model.PhotoItem?) {
    Box(Modifier.size(28.dp, 40.dp)) {
        if (cover != null) {
            Print(cover, size = 20.dp, modifier = Modifier.offset(x = 4.dp).graphicsLayer { rotationZ = -6f })
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .size(28.dp, 28.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Brush.verticalGradient(listOf(DarkroomColors.KraftHigh, DarkroomColors.Kraft))),
        )
    }
}

@Composable
private fun NextDayCard(next: NextDay, onClick: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(shape)
            .background(DarkroomColors.Paper)
            .plainClickable(onClick = onClick)
            .padding(start = 8.dp, end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val request = remember(next.cover.id) {
            ImageRequest.Builder(context).data(next.cover.uri).size(NEXT_COVER_PX).crossfade(false).build()
        }
        AsyncImage(request, null, contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)))
        Column(Modifier.weight(1f)) {
            val locale = currentLocale()
            Text(
                stringResource(R.string.summary_next_day, next.date?.let { DayDisplay.cleanDate(it, locale) } ?: stringResource(R.string.date_unknown)),
                style = DarkroomText.NextTitle,
            )
            Text(pluralStringResource(R.plurals.sheet_pending, next.count, next.count), style = DarkroomText.NextSub)
        }
        Icon(DarkroomIcons.Arrow, null, tint = DarkroomColors.Room, modifier = Modifier.size(20.dp))
    }
}

private const val NEXT_COVER_PX = 144
