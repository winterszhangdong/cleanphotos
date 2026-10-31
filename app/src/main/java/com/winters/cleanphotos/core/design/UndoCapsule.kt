package com.winters.cleanphotos.core.design

import com.winters.cleanphotos.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay

/**
 * 撤回胶囊的内容。[seq] 每次有效操作递增，用来重启 4 秒倒计时；[favorite] 决定琥珀 / 中性灰。
 * [subtitle] 为 null 时只有一行主文案（照片页批量操作）。
 * [keepColor]：中性小样但不去色（印样表装袋：照片只是换了文件夹，不是离开）。
 * [deadlineUptimeMillis]：这次操作的到期时刻（单调时钟）。由持有撤回的状态写入，离开组合不再重算。
 */
data class UndoCapsuleContent(
    val seq: Long,
    val thumbKey: Long,
    val thumbModel: Any?,
    val favorite: Boolean,
    val title: String,
    val subtitle: String?,
    val keepColor: Boolean = false,
    val deadlineUptimeMillis: Long,
)

/**
 * 玻璃胶囊 56dp + 40dp 圆形小样 + 文案 + 相纸白「撤回」+ 底部 2dp 4 秒倒计时（ui-design 4.1）。
 * 移入垃圾桶：小样去色压暗、中性灰；收藏：琥珀。入场 280ms 减速，收起 200ms 加速离场。
 */
@Composable
fun UndoBar(
    content: UndoCapsuleContent?,
    visible: Boolean,
    onUndo: () -> Unit,
    onExpired: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val last = remember { arrayOfNulls<UndoCapsuleContent>(1) }
    if (content != null) last[0] = content
    // 截止跟操作走，不跟这次组合。切 tab / 范围会拆掉胶囊；从操作时刻换算剩余，不再在这里记起点。
    val seq = content?.seq
    val deadline = content?.deadlineUptimeMillis
    val total = MotionDurations.UNDO_COUNTDOWN
    val expired by rememberUpdatedState(onExpired)
    val undo by rememberUpdatedState(onUndo)
    if (seq != null && deadline != null) {
        LaunchedEffect(seq, deadline) {
            // 真实时间：Compose 动画会乘系统动画缩放，「移除动画」时 tween 立刻结束；过期用不受缩放影响的 delay
            val wait = undoRemainingMillis(deadline - total, SystemClock.uptimeMillis(), total)
            if (wait > 0L) delay(wait)
            expired(seq)
        }
    }
    val open = deadline != null && undoRemainingMillis(deadline - total, SystemClock.uptimeMillis(), total) > 0L
    AnimatedVisibility(
        visible = content != null && visible && open,
        modifier = modifier,
        enter = undoCapsuleEnter(),
        exit = undoCapsuleExit(),
    ) {
        // content 变 null 时外层 deadline 也是 null。退出 200ms 仍要画上次的胶囊，截止用这次操作自己的时刻。
        val info = content ?: last[0] ?: return@AnimatedVisibility
        val barDeadline = info.deadlineUptimeMillis
        UndoCapsule(info, barDeadline) {
            val wait = undoRemainingMillis(barDeadline - total, SystemClock.uptimeMillis(), total)
            if (wait > 0L) undo() else expired(info.seq)
        }
    }
}

@Composable
private fun UndoCapsule(info: UndoCapsuleContent, deadlineUptimeMillis: Long, onUndo: () -> Unit) {
    val fav = info.favorite
    val total = MotionDurations.UNDO_COUNTDOWN
    // 重新出现时细线从剩余比例接着走，不回到满格。动画关掉时细线保持满格，过期仍由外层 delay 按真实时间走
    val remainingMs = undoRemainingMillis(deadlineUptimeMillis - total, SystemClock.uptimeMillis(), total)
    val animations = LocalAnimationsEnabled.current
    val countdown = remember(info.seq) { Animatable(if (animations) remainingMs.toFloat() / total else 1f) }
    LaunchedEffect(info.seq) {
        // 细线只做显示（过期由外层 delay 计时）。动画关闭时细线保持满格。
        if (animations && remainingMs > 0) {
            countdown.animateTo(0f, tween(remainingMs.toInt(), easing = LinearEasing))
        } else if (!animations) {
            countdown.snapTo(1f)
        }
    }
    val shape = RoundedCornerShape(28.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .dropShadow(shape, Shadow(radius = 40.dp, color = Color.Black.copy(alpha = 0.5f), offset = DpOffset(0.dp, 16.dp)))
            .clip(shape)
            .glassBlur(UndoGlassSpec)
            .border(1.dp, DarkroomColors.MenuRim, shape),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp).height(56.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UndoThumb(info.thumbKey, info.thumbModel, fav, info.keepColor)
            Column(Modifier.weight(1f)) {
                Text(info.title, style = DarkroomText.UndoTitle, maxLines = 1)
                if (info.subtitle != null) Text(info.subtitle, style = DarkroomText.UndoSub, maxLines = 1)
            }
            Row(
                Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(DarkroomColors.Paper)
                    .plainClickable(onClick = onUndo)
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(DarkroomIcons.Undo, null, tint = DarkroomColors.Room, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.undo_action), style = DarkroomText.UndoAct)
            }
        }
        Canvas(
            Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 28.dp)
                .fillMaxWidth()
                .height(2.dp),
        ) {
            val track = if (fav) DarkroomColors.Amber.copy(alpha = 0.25f) else DarkroomColors.Paper.copy(alpha = 0.12f)
            val bar = if (fav) DarkroomColors.Amber else DarkroomColors.Fade
            drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2f))
            drawRect(bar, size = Size(size.width * countdown.value, size.height))
        }
    }
}

/** 撤回条剩余时间：从 [startedAt] 起算满 [total] ms，按真实时间扣，不因隐藏 / 重新出现而重置。 */
internal fun undoRemainingMillis(startedAt: Long, now: Long, total: Int): Long =
    (total - (now - startedAt)).coerceIn(0L, total.toLong())

@Composable
private fun UndoThumb(key: Long, model: Any?, fav: Boolean, keepColor: Boolean) {
    val context = LocalContext.current
    val request = remember(key) {
        ImageRequest.Builder(context).data(model).size(THUMB_PX).memoryCacheKey("review-thumb-$key").crossfade(false).build()
    }
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(DarkroomColors.Tray2),
    ) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            colorFilter = if (fav || keepColor) null else UNDO_TRASH_FILTER,
            modifier = Modifier.size(40.dp),
        )
        Box(
            Modifier
                .size(40.dp)
                .border(if (fav) 1.5.dp else 1.dp, if (fav) DarkroomColors.Amber else DarkroomColors.Paper.copy(alpha = 0.16f), CircleShape),
        )
    }
}

/** 入场 280ms 减速上移，收起 200ms 加速离场。垃圾桶提醒胶囊共用，不另写一套时长。 */
internal fun undoCapsuleEnter(): EnterTransition =
    fadeIn(tween(MotionDurations.MEDIUM, easing = MotionEasings.Standard)) +
        slideInVertically(tween(MotionDurations.MEDIUM, easing = MotionEasings.Decelerate)) { it / 3 }

internal fun undoCapsuleExit(): ExitTransition =
    fadeOut(tween(MotionDurations.UNDO_BAR_EXIT, easing = MotionEasings.Accelerate)) +
        slideOutVertically(tween(MotionDurations.UNDO_BAR_EXIT, easing = MotionEasings.Accelerate)) { it / 3 }

/** 撤回胶囊玻璃：rgba(26,24,22,0.78) + blur(24)。 */
private val UndoGlassSpec = GlassSpec(DarkroomColors.UndoGlass, Dimens.CapsuleBlur)
/** .undo .thumb img：grayscale(0.6) brightness(0.7)。 */
private val UNDO_TRASH_FILTER: ColorFilter = saturateBrightness(0.4f, 0.7f)
private const val THUMB_PX = 160
