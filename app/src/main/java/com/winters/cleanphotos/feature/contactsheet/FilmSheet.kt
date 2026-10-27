package com.winters.cleanphotos.feature.contactsheet

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.winters.cleanphotos.core.design.DarkroomColors
import com.winters.cleanphotos.core.design.DarkroomText
import com.winters.cleanphotos.core.design.DevelopProfiles
import com.winters.cleanphotos.core.design.FilingMotion
import com.winters.cleanphotos.core.design.GestureTokens
import com.winters.cleanphotos.core.design.Haptics
import com.winters.cleanphotos.core.design.MotionDurations
import com.winters.cleanphotos.core.design.MotionEasings
import com.winters.cleanphotos.core.design.MotionSprings
import com.winters.cleanphotos.core.design.develop
import com.winters.cleanphotos.core.design.photoThumbKey
import com.winters.cleanphotos.core.design.rememberDevelopState
import com.winters.cleanphotos.core.image.GridThumb
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.feature.allphotos.PhotoHitRects
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs

/** 胶片几何（ui-design 3.20）：上下齿孔带 12，两端留 8，格间 6，格子 4:3；条与条之间 14。 */
internal object FilmGeometry {
    val Band = 12.dp
    val EndInset = 8.dp
    val FrameGap = 6.dp
    val StripGap = 14.dp
    val HoleWidth = 5.dp
    val HolePitch = 10.dp
    val HoleHeight = 4.dp
    val HoleInset = 4.dp
    /** 油性笔圈在照片外扩 9dp。 */
    val PenMargin = 9.dp
    val PenStroke = 2.4.dp
    /** 超过这么多张每条 4 格。 */
    const val DENSE_AFTER = 30

    fun columns(count: Int) = if (count > DENSE_AFTER) 4 else 3

    /** 一格照片宽：整条宽减两端和格间，再均分。 */
    fun frameWidth(stripWidth: Dp, cols: Int): Dp = (stripWidth - EndInset * 2 - FrameGap * (cols - 1)) / cols

    /** 第 [col] 格照片在自己那一段胶片里的左边距（每段宽 = 整条 / cols，照片按整条坐标排）。 */
    fun frameLeft(stripWidth: Dp, cols: Int, col: Int): Dp =
        EndInset + (frameWidth(stripWidth, cols) + FrameGap) * col - stripWidth / cols * col
}

/**
 * 印样表：一张张胶片条（LazyVerticalGrid，每格一张照片，同一行的格子拼成一条胶片，key = photoId，
 * 照片离开时后面的格子用 animateItem 补上）。点按 / 横划连圈 / 长按看大图或拿起照片叠都在外层一个 pointerInput 里。
 */
@Composable
internal fun FilmSheet(
    photos: List<PhotoItem>,
    circled: Set<Long>,
    date: LocalDate?,
    gridState: LazyGridState,
    contentPadding: PaddingValues,
    stripWidth: Dp,
    hits: PhotoHitRects,
    entering: Boolean,
    slotAlpha: (Long) -> Float,
    gestures: SheetGestureCallbacks,
    modifier: Modifier = Modifier,
) {
    val cols = FilmGeometry.columns(photos.size)
    val view = LocalView.current
    val cb = rememberUpdatedState(gestures)
    val circledNow = rememberUpdatedState(circled)
    Box(
        modifier
            .onGloballyPositioned {
                val p = it.positionInRoot()
                hits.originX = p.x
                hits.originY = p.y
            }
            .pointerInput(hits) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val hit = hits.hit(down.position)
                    fun root(local: Offset) = Offset(local.x + hits.originX, local.y + hits.originY)
                    // 长按超时前：抬起 = 点按；横向先过阈值 = 连圈；纵向 / 被列表消费 = 交给滚动
                    var tapped = false
                    var stroke = false
                    val decided = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
                            if (!change.pressed) {
                                tapped = !change.isConsumed
                                return@withTimeoutOrNull true
                            }
                            if (change.isConsumed) return@withTimeoutOrNull true
                            val d = change.position - down.position
                            if (d.getDistance() > slop) {
                                stroke = hit != null && abs(d.x) > abs(d.y) * GestureTokens.DIRECTION_RATIO
                                return@withTimeoutOrNull true
                            }
                        }
                        @Suppress("UNREACHABLE_CODE") true
                    }
                    when {
                        decided != null && tapped -> if (hit != null) cb.value.onTap(hit)
                        decided != null && stroke -> {
                            cb.value.onCircle(hit!!)
                            while (true) {
                                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                if (!change.pressed) break
                                hits.hit(change.position)?.let { cb.value.onCircle(it) }
                            }
                        }
                        decided != null -> Unit
                        hit == null -> Unit
                        hit in circledNow.value -> {
                            // 拿起所有圈中的照片：手指下收成一叠，拖到袋子上松手
                            Haptics.longPress(view)
                            cb.value.onPickUp(root(down.position))
                            while (true) {
                                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                if (!change.pressed) break
                                cb.value.onDragMove(root(change.position))
                            }
                            cb.value.onDrop()
                        }
                        else -> {
                            // 没圈的长按 = 看大图，松手收起
                            Haptics.longPress(view)
                            cb.value.onPreview(hit)
                            while (true) {
                                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                if (!change.pressed) break
                            }
                            cb.value.onPreviewEnd()
                        }
                    }
                }
            },
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(cols),
            state = gridState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(FilmGeometry.StripGap),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(photos, key = { _, p -> p.id }, contentType = { _, _ -> "frame" }) { index, photo ->
                val row = index / cols
                val col = index % cols
                val lastInRow = col == cols - 1 || index == photos.lastIndex
                FilmFrame(
                    photo = photo,
                    number = index + 1,
                    row = row,
                    col = col,
                    cols = cols,
                    stripWidth = stripWidth,
                    edgeText = if (lastInRow && row % 2 == 0) edgePrint(date) else null,
                    circled = photo.id in circled,
                    entering = entering,
                    slotAlpha = slotAlpha,
                    // 落笔一下轻触感（与跨天同一档）
                    onPenLanded = { Haptics.dayChange(view) },
                    modifier = Modifier
                        .animateItem(
                            fadeInSpec = tween(MotionDurations.SHORT, easing = MotionEasings.Standard),
                            placementSpec = MotionSprings.Settle.spec(androidx.compose.ui.unit.IntOffset.VisibilityThreshold),
                            fadeOutSpec = tween(MotionDurations.SHORT, easing = MotionEasings.Standard),
                        ),
                    hits = hits,
                )
            }
        }
    }
}

internal class SheetGestureCallbacks(
    val onTap: (Long) -> Unit,
    val onCircle: (Long) -> Unit,
    val onPickUp: (Offset) -> Unit,
    val onDragMove: (Offset) -> Unit,
    val onDrop: () -> Unit,
    val onPreview: (Long) -> Unit,
    val onPreviewEnd: () -> Unit,
)

/** 片边字：CP 400 · 10.24（日期未知时只有 CP 400）。 */
private fun edgePrint(date: LocalDate?): String =
    if (date == null) "CP 400" else String.format(Locale.US, "CP 400 · %d.%02d", date.monthValue, date.dayOfMonth)

/**
 * 一格胶片：这一段的胶片底 + 齿孔 + 格号 + 照片 + 油性笔圈。同一行的格子按整条的中心转同一个摆角，拼起来是一条直胶片。
 */
@Composable
private fun FilmFrame(
    photo: PhotoItem,
    number: Int,
    row: Int,
    col: Int,
    cols: Int,
    stripWidth: Dp,
    edgeText: String?,
    circled: Boolean,
    entering: Boolean,
    slotAlpha: (Long) -> Float,
    onPenLanded: () -> Unit,
    hits: PhotoHitRects,
    modifier: Modifier = Modifier,
) {
    val frameW = FilmGeometry.frameWidth(stripWidth, cols)
    val frameH = frameW * 3f / 4f
    val cellW = stripWidth / cols
    val left = FilmGeometry.frameLeft(stripWidth, cols, col)
    val skew = FilingMotion.STRIP_SKEW[row % FilingMotion.STRIP_SKEW.size]
    // 进场：整条从左滑入、带角度转正（只在第一次打开这一页时）
    val enter = remember { Animatable(if (entering) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (enter.value < 1f) {
            enter.animateTo(
                1f,
                tween(MotionDurations.MEDIUM, delayMillis = row.coerceAtMost(MotionDurations.STAGGER_MAX_STEPS) * MotionDurations.STAGGER, easing = MotionEasings.Decelerate),
            )
        }
    }
    val develop = rememberDevelopState(DevelopProfiles.Single, developed = !entering)
    LaunchedEffect(Unit) {
        if (entering) develop.develop(row.coerceAtMost(MotionDurations.STAGGER_MAX_STEPS) * MotionDurations.STAGGER)
    }
    val pen = remember { Animatable(if (circled) 1f else 0f) }
    LaunchedEffect(circled) {
        if (circled && pen.targetValue < 1f) {
            pen.animateTo(1f, tween(FilingMotion.PEN_STROKE, easing = MotionEasings.Decelerate))
            onPenLanded()
        } else if (!circled && pen.targetValue > 0f) {
            pen.animateTo(0f, tween(MotionDurations.SHORT, easing = MotionEasings.Standard))
        }
    }
    DisposableEffect(photo.id) { onDispose { hits.remove(photo.id) } }
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
    Box(
        modifier
            .size(cellW, frameH + FilmGeometry.Band * 2)
            .graphicsLayer {
                val e = enter.value
                // 整条绕胶片中心转：按这一格在条里的位置换算转轴
                transformOrigin = TransformOrigin((stripWidth.toPx() / 2f - cellW.toPx() * col) / cellW.toPx(), 0.5f)
                rotationZ = FilingMotion.STRIP_ENTER_TILT * (1f - e) + skew * e
                translationX = -FilingMotion.StripEnterShift.toPx() * (1f - e)
                alpha = e
            }
            .drawBehind { drawFilm(col, cols, cellW.toPx(), stripWidth.toPx()) },
    ) {
        Text(
            String.format(Locale.US, "▸%02d", number),
            style = DarkroomText.FrameNo,
            modifier = Modifier.offset(x = left + 2.dp, y = 2.dp),
        )
        if (edgeText != null) {
            Text(
                edgeText,
                style = DarkroomText.FrameNo,
                modifier = Modifier.align(Alignment.BottomEnd).offset(x = -FilmGeometry.EndInset, y = -1.dp),
            )
        }
        Box(
            Modifier
                .offset(x = left, y = FilmGeometry.Band)
                .size(frameW, frameH)
                .onGloballyPositioned { hits.put(photo.id, it.boundsInRoot()) }
                // 照片被拿起后这一格空着：只留一圈细框，不重排
                .drawBehind {
                    val a = 1f - slotAlpha(photo.id)
                    if (a > 0f) {
                        val w = 1.dp.toPx()
                        drawRect(DarkroomColors.Rim2.copy(alpha = DarkroomColors.Rim2.alpha * a), Offset(w / 2f, w / 2f), Size(size.width - w, size.height - w), style = Stroke(width = w))
                    }
                }
                .graphicsLayer { alpha = slotAlpha(photo.id) }
                .drawWithContent {
                    drawContent()
                    val t = pen.value
                    if (t > 0f) drawPen(photo.id, t, FilmGeometry.PenMargin.toPx(), FilmGeometry.PenStroke.toPx())
                },
        ) {
            AsyncImage(
                model = model,
                contentDescription = photo.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().develop(develop),
            )
        }
    }
}

/** 胶片底 + 上下齿孔。齿孔按整条坐标排，拼起来连续；两端留出 [FilmGeometry.HoleInset]。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFilm(col: Int, cols: Int, cellW: Float, stripW: Float) {
    drawRect(DarkroomColors.Film)
    val pitch = FilmGeometry.HolePitch.toPx()
    val holeW = FilmGeometry.HoleWidth.toPx()
    val holeH = FilmGeometry.HoleHeight.toPx()
    val inset = FilmGeometry.HoleInset.toPx() + 2f
    val start = cellW * col
    val end = start + cellW
    var x = inset + kotlin.math.ceil(((start - inset) / pitch).coerceAtLeast(0f)) * pitch
    while (x + holeW <= minOf(end, stripW - inset)) {
        val local = x - start
        drawRect(DarkroomColors.FilmHole, Offset(local, FilmGeometry.HoleInset.toPx()), Size(holeW, holeH))
        drawRect(DarkroomColors.FilmHole, Offset(local, size.height - FilmGeometry.HoleInset.toPx() - holeH), Size(holeW, holeH))
        x += pitch
    }
}

/**
 * 油性笔圈：一笔画回起点再多走一截（首尾交叉），三种手势按照片 id 轮换。
 * 路径在 114×90 的框里定义（照片 96×72 外扩 9），按实际大小缩放。[t] 是路径裁剪进度。
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPen(id: Long, t: Float, margin: Float, stroke: Float) {
    val w = size.width + margin * 2
    val h = size.height + margin * 2
    val sx = w / 114f
    val sy = h / 90f
    val src = PEN_PATHS[Math.floorMod(id, PEN_PATHS.size.toLong()).toInt()]
    val path = Path()
    src.forEachIndexed { i, seg ->
        if (i == 0) path.moveTo(seg[0] * sx - margin, seg[1] * sy - margin)
        else path.cubicTo(seg[0] * sx - margin, seg[1] * sy - margin, seg[2] * sx - margin, seg[3] * sy - margin, seg[4] * sx - margin, seg[5] * sy - margin)
    }
    val measure = PathMeasure().apply { setPath(path, false) }
    val out = Path()
    measure.getSegment(0f, measure.length * t, out, true)
    drawPath(out, DarkroomColors.Pen, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** 三支手绘圈（设计稿 pen-a / b / c）：第一项是起点，之后每项一段三次贝塞尔。 */
private val PEN_PATHS = listOf(
    listOf(
        floatArrayOf(64f, 7f),
        floatArrayOf(94f, 6f, 110f, 23f, 109f, 44f),
        floatArrayOf(108f, 68f, 84f, 84f, 55f, 83f),
        floatArrayOf(25f, 82f, 5f, 66f, 6f, 43f),
        floatArrayOf(7f, 21f, 29f, 7f, 60f, 9f),
        floatArrayOf(72f, 9.6f, 81f, 12.4f, 88f, 16f),
    ),
    listOf(
        floatArrayOf(52f, 8f),
        floatArrayOf(80f, 4f, 108f, 18f, 108f, 42f),
        floatArrayOf(108f, 66f, 88f, 85f, 58f, 84f),
        floatArrayOf(27f, 83f, 7f, 69f, 6f, 46f),
        floatArrayOf(5f, 22f, 26f, 9f, 56f, 7f),
        floatArrayOf(68f, 6f, 77f, 8f, 86f, 12f),
    ),
    listOf(
        floatArrayOf(60f, 6f),
        floatArrayOf(90f, 7f, 109f, 25f, 108f, 46f),
        floatArrayOf(107f, 70f, 82f, 85f, 53f, 83f),
        floatArrayOf(23f, 81f, 6f, 63f, 7f, 41f),
        floatArrayOf(9f, 19f, 31f, 6f, 62f, 8f),
        floatArrayOf(73f, 9f, 80f, 11.5f, 86f, 15f),
    ),
)

/** 印样表空格高度（卷起动画里那条空胶片用）。 */
internal fun stripHeight(stripWidth: Dp, cols: Int): Dp = FilmGeometry.frameWidth(stripWidth, cols) * 3f / 4f + FilmGeometry.Band * 2
