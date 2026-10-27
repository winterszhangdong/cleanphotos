package com.winters.cleanphotos.feature.contactsheet

import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.winters.cleanphotos.R
import com.winters.cleanphotos.core.design.DarkroomColors
import com.winters.cleanphotos.core.design.DarkroomIcons
import com.winters.cleanphotos.core.design.DarkroomText
import com.winters.cleanphotos.core.design.Dimens
import com.winters.cleanphotos.core.design.FilingMotion
import com.winters.cleanphotos.core.design.MotionDurations
import com.winters.cleanphotos.core.design.MotionEasings
import com.winters.cleanphotos.core.design.MotionSprings
import com.winters.cleanphotos.core.design.OdometerText
import com.winters.cleanphotos.core.design.blockTouchesBelow
import com.winters.cleanphotos.core.design.photoThumbKey
import com.winters.cleanphotos.core.design.plainClickable
import com.winters.cleanphotos.core.image.GridThumb
import com.winters.cleanphotos.domain.model.PhotoAlbum
import com.winters.cleanphotos.domain.model.PhotoItem

/** 底片袋尺寸（ui-design 3.20）：76×104，袋口（正面上沿）在 24，正面高 80，标签 36。 */
internal object EnvelopeGeometry {
    val Width = 76.dp
    val Height = 104.dp
    val BackTop = 18.dp
    val MouthY = 24.dp
    val FrontHeight = 80.dp
    val NotchRadius = 10.dp
    val LabelInset = 7.dp
    val LabelBottom = 9.dp
    val LabelHeight = 36.dp
    val FlapHeight = 34.dp
    val Print = 50.dp
    val PrintLeft = 13.dp
    val PrintTop = 2.dp
    val PrintBorder = 2.dp
    val Gap = 12.dp
    val TrayHeight = 248.dp
    /** 托盘里袋子那一行的上沿（相对托盘顶）。 */
    val RowTop = 64.dp
}

/** 「新袋子」在悬停 / 位置表里用的键（真实路径都以 / 结尾，不会撞）。 */
internal const val NEW_ENVELOPE_KEY = "+new"

/**
 * 托盘：「放进相册」+ 系统收藏开关；一排底片袋（最左「新袋子」，最多 [TrayRules.LIMIT] 只相册，相册更多时最右「还有 N 个」）；底部「取消收藏」。
 * 点袋子 = 放进去；长按 = 偷看（托盘只在有圈选时出现，两者不冲突）。袋子换位置（插队、挤出）用 settle 滑动 / SHORT 淡入淡出。
 */
@Composable
internal fun FilingTray(
    albums: List<PhotoAlbum>,
    moreCount: Int,
    rowState: LazyListState,
    visuals: FilingVisuals,
    systemFavoriteSupported: Boolean,
    systemFavorite: Boolean,
    onSystemFavoriteToggle: () -> Unit,
    onUnfavorite: () -> Unit,
    onFile: (PhotoAlbum) -> Unit,
    onNew: () -> Unit,
    onMore: () -> Unit,
    onPeek: (PhotoAlbum) -> Unit,
    onPeekEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(DarkroomColors.Tray)
            .border(Dimens.Hairline, DarkroomColors.Rim2, shape)
            .blockTouchesBelow()
            .navigationBarsPadding()
            .height(EnvelopeGeometry.TrayHeight),
    ) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
                .size(32.dp, 4.dp)
                .clip(CircleShape)
                .background(DarkroomColors.Rim2),
        )
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp).fillMaxWidth().height(32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.tray_title), style = DarkroomText.TrayTitle, modifier = Modifier.weight(1f))
            if (systemFavoriteSupported) SystemFavoriteSwitch(systemFavorite, onSystemFavoriteToggle)
        }
        LazyRow(
            state = rowState,
            modifier = Modifier
                .padding(top = EnvelopeGeometry.RowTop)
                .fillMaxWidth()
                .height(EnvelopeGeometry.Height + 16.dp)
                .onGloballyPositioned { visuals.trayRow = it.boundsInRoot() },
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(EnvelopeGeometry.Gap),
            verticalAlignment = Alignment.Bottom,
        ) {
            item(key = NEW_ENVELOPE_KEY) {
                TrackRect(visuals.envelopeRects, NEW_ENVELOPE_KEY)
                NewEnvelope(
                    dim = visuals.dimFor(NEW_ENVELOPE_KEY),
                    onClick = onNew,
                    modifier = Modifier.onGloballyPositioned { visuals.envelopeRects[NEW_ENVELOPE_KEY] = it.boundsInRoot() },
                )
            }
            items(albums.take(TrayRules.LIMIT), key = { it.relativePath }) { album ->
                val path = album.relativePath
                val flight = visuals.flight?.takeIf { it.relativePath == path && !it.intoSheet }
                // 从点下 / 松手到合盖都停在放进去之前的张数和露角（授权通过后相册数据先于飞入更新），合盖那一刻再滚
                val shown = HeldEnvelope.shown(
                    album = album,
                    picked = visuals.trayPicked,
                    inFlight = flight?.takeIf { !it.released }?.let { HeldEnvelope(path, it.heldCount, it.heldCover) },
                )
                TrackRect(visuals.envelopeRects, path)
                Envelope(
                    name = album.name,
                    count = shown.count,
                    cover = shown.cover,
                    state = visuals.stateFor(path),
                    modifier = Modifier
                        .animateItem(
                            fadeInSpec = tween(MotionDurations.SHORT, easing = MotionEasings.Standard),
                            placementSpec = MotionSprings.Settle.spec(IntOffset.VisibilityThreshold),
                            fadeOutSpec = tween(MotionDurations.SHORT, easing = MotionEasings.Standard),
                        )
                        .onGloballyPositioned { visuals.envelopeRects[path] = it.boundsInRoot() }
                        .semantics {
                            role = Role.Button
                            onLongClick { onPeek(album); true }
                        }
                        .pointerInput(album.relativePath) {
                            detectTapGestures(
                                onTap = { onFile(album) },
                                onLongPress = { onPeek(album) },
                                onPress = {
                                    tryAwaitRelease()
                                    onPeekEnd()
                                },
                            )
                        },
                )
            }
            if (moreCount > 0) {
                item(key = MORE_KEY) {
                    TrackRect(visuals.envelopeRects, MORE_KEY)
                    MoreCard(
                        count = moreCount,
                        state = visuals.stateFor(MORE_KEY),
                        onClick = onMore,
                        modifier = Modifier
                            .animateItem(
                                fadeInSpec = tween(MotionDurations.SHORT, easing = MotionEasings.Standard),
                                placementSpec = MotionSprings.Settle.spec(IntOffset.VisibilityThreshold),
                                fadeOutSpec = tween(MotionDurations.SHORT, easing = MotionEasings.Standard),
                            )
                            .onGloballyPositioned { visuals.envelopeRects[MORE_KEY] = it.boundsInRoot() },
                    )
                }
            }
        }
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 8.dp, end = 16.dp, bottom = 4.dp)
                .fillMaxWidth()
                .height(Dimens.MinTouch),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.height(Dimens.MinTouch).plainClickable(onClick = onUnfavorite).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(DarkroomIcons.Star, null, tint = DarkroomColors.Dim, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.action_unfavorite), style = DarkroomText.TextButton)
            }
            Text(
                stringResource(R.string.tray_hint),
                style = DarkroomText.TraySwitch.copy(color = DarkroomColors.Faint),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
    }
}

/** 「加系统收藏」：32dp 胶囊开关，开着时星标和圆钮是琥珀（琥珀 = 收藏）。 */
@Composable
private fun SystemFavoriteSwitch(on: Boolean, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    // 圆钮位置只在 layout 阶段读，不触发重组
    val knob = animateFloatAsState(if (on) 1f else 0f, MotionSprings.Snappy.spec(), label = "sys-fav-knob")
    Row(
        Modifier
            .height(Dimens.MinTouch)
            .plainClickable(role = Role.Switch, onClick = onToggle)
            .semantics { stateDescription = if (on) "on" else "off" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .height(32.dp)
                .clip(shape)
                .background(DarkroomColors.Tray2)
                .border(Dimens.Hairline, if (on) DarkroomColors.Amber.copy(alpha = 0.4f) else DarkroomColors.Rim, shape)
                .padding(start = 10.dp, end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (on) DarkroomIcons.StarSolid else DarkroomIcons.Star, null,
                tint = if (on) DarkroomColors.Amber else DarkroomColors.Dim,
                modifier = Modifier.size(14.dp),
            )
            Text(stringResource(R.string.tray_system_favorite), style = DarkroomText.TraySwitch.copy(color = if (on) DarkroomColors.Paper else DarkroomColors.Dim))
            Box(
                Modifier
                    .size(24.dp, 14.dp)
                    .clip(CircleShape)
                    .background(if (on) DarkroomColors.Amber.copy(alpha = 0.35f) else DarkroomColors.Paper.copy(alpha = 0.14f)),
            ) {
                Box(
                    Modifier
                        .offset { IntOffset((2.dp + 10.dp * knob.value).roundToPx(), 2.dp.roundToPx()) }
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (on) DarkroomColors.Amber else DarkroomColors.Dim),
                )
            }
        }
    }
}

/** 一个底片袋在某一刻的 3D 状态：全部只在绘制阶段读取。 */
internal class EnvelopeState(
    /** 朝手指倾斜 0..1。 */
    val lean: () -> Float,
    /** 偷看时上浮放大 0..1。 */
    val lift: () -> Float,
    /** 封口 0 = 开（翻在背后）、1 = 合上。 */
    val flap: () -> Float,
    /** 合盖时被压下去 0..1。 */
    val bump: () -> Float,
    /** 悬停计时圈 0..1；0 不画。 */
    val dwell: () -> Float,
    val alpha: () -> Float,
)

@Composable
internal fun Envelope(
    name: String,
    count: Int,
    cover: PhotoItem?,
    state: EnvelopeState,
    modifier: Modifier = Modifier,
) {
    val g = EnvelopeGeometry
    Box(
        modifier
            .size(g.Width, g.Height)
            .graphicsLayer {
                val lean = state.lean()
                val lift = state.lift()
                alpha = state.alpha()
                cameraDistance = FilingMotion.CAMERA_DISTANCE * density
                transformOrigin = TransformOrigin(0.5f, 1f)
                rotationX = FilingMotion.LEAN_TILT_X * lean
                rotationY = -FilingMotion.LEAN_TILT_Y_MAX * lean * 0.75f
                val s = (1f + (FilingMotion.LEAN_SCALE - 1f) * lean) * (1f + (FilingMotion.PEEK_SCALE - 1f) * lift)
                scaleX = s
                scaleY = s
                translationY = -FilingMotion.LeanLift.toPx() * lean - FilingMotion.PeekLift.toPx() * lift + FilingMotion.FlapBump.toPx() * state.bump()
            }
            .drawWithContent {
                drawContent()
                val d = state.dwell()
                if (d > 0f && d < 1f) drawDwellRing(d)
            },
    ) {
        Box(
            Modifier
                .offset(y = g.BackTop)
                .size(g.Width, g.Height - g.BackTop)
                .clip(RoundedCornerShape(4.dp))
                .background(DarkroomColors.KraftInside),
        )
        if (cover != null) Print(cover, Modifier.offset(g.PrintLeft, g.PrintTop).graphicsLayer { rotationZ = coverTilt(cover.id) })
        Box(
            Modifier
                .offset(y = g.MouthY)
                .size(g.Width, g.FrontHeight)
                .drawBehind { drawFront() },
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(start = g.LabelInset, end = g.LabelInset, bottom = g.LabelBottom)
                .fillMaxWidth()
                .height(g.LabelHeight)
                .clip(RoundedCornerShape(2.dp))
                .background(DarkroomColors.Label)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            Text(name, style = DarkroomText.EnvelopeName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row {
                OdometerText(count.toLong(), style = DarkroomText.EnvelopeCount)
                Text(pluralStringResource(R.plurals.envelope_count_unit, count), style = DarkroomText.EnvelopeCount)
            }
        }
        // 封口：合页在袋口，打开时翻到背后（转过 90° 前看不见），合上时盖住正面上沿
        Box(
            Modifier
                .offset(y = g.MouthY)
                .size(g.Width, g.FlapHeight)
                .graphicsLayer {
                    val f = state.flap()
                    val angle = 180f * (1f - f)
                    cameraDistance = FilingMotion.CAMERA_DISTANCE * density
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    rotationX = angle
                    alpha = if (angle >= 90f) 0f else 1f
                    clip = true
                    shape = FlapShape
                }
                .background(Brush.verticalGradient(listOf(DarkroomColors.KraftFlapHigh, DarkroomColors.KraftFlap))),
        )
    }
}

/** 滚出列表（被回收）的袋子不再留着旧位置，免得悬停判定到看不见的袋子。 */
@Composable
internal fun TrackRect(rects: MutableMap<String, Rect>, key: String) {
    DisposableEffect(rects, key) { onDispose { rects.remove(key) } }
}

/**
 * 「还有 N 个」：和袋子同一个 76×104 占位，`Tray2` 底 + `Rim2` 描边；背后错开两只压暗的牛皮纸袋子边，
 * 正中放大镜 +「还有 N 个」+ mono「搜索」。悬停时和袋子一样倾斜、弹名签，不计时、不自己打开（松手或点它才打开面板）。
 */
@Composable
private fun MoreCard(count: Int, state: EnvelopeState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val g = EnvelopeGeometry
    val label = pluralStringResource(R.plurals.tray_more, count, count)
    Box(
        modifier
            .size(g.Width, g.Height)
            .graphicsLayer {
                val lean = state.lean()
                alpha = state.alpha()
                cameraDistance = FilingMotion.CAMERA_DISTANCE * density
                transformOrigin = TransformOrigin(0.5f, 1f)
                rotationX = FilingMotion.LEAN_TILT_X * lean
                val s = 1f + (FilingMotion.LEAN_SCALE - 1f) * lean
                scaleX = s
                scaleY = s
                translationY = -FilingMotion.LeanLift.toPx() * lean
            }
            .drawWithContent {
                drawContent()
                val d = state.dwell()
                if (d > 0f && d < 1f) drawDwellRing(d)
            }
            .semantics { role = Role.Button }
            .plainClickable(onClick = onClick),
    ) {
        // 背后错开的两只袋子边（向右上各错 4dp）
        Box(
            Modifier
                .offset(x = 8.dp, y = g.BackTop - 8.dp)
                .size(g.Width - 8.dp, g.Height - g.BackTop)
                .clip(RoundedCornerShape(4.dp))
                .background(DarkroomColors.Kraft.copy(alpha = 0.24f)),
        )
        Box(
            Modifier
                .offset(x = 4.dp, y = g.BackTop - 4.dp)
                .size(g.Width - 4.dp, g.Height - g.BackTop)
                .clip(RoundedCornerShape(4.dp))
                .background(DarkroomColors.Kraft.copy(alpha = 0.4f)),
        )
        Column(
            Modifier
                .offset(y = g.BackTop)
                .size(g.Width - 8.dp, g.Height - g.BackTop)
                .clip(RoundedCornerShape(4.dp))
                .background(DarkroomColors.Tray2)
                .border(Dimens.Hairline, DarkroomColors.Rim2, RoundedCornerShape(4.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(DarkroomIcons.Search, null, tint = DarkroomColors.Paper.copy(alpha = 0.88f), modifier = Modifier.size(20.dp))
            Text(
                label,
                style = DarkroomText.MoreCount,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp),
            )
            Text(stringResource(R.string.tray_more_search), style = DarkroomText.GhostMono.copy(fontSize = 10.sp), maxLines = 1)
        }
    }
}

/** 「新袋子」：虚线框 + 加号。 */
@Composable
private fun NewEnvelope(dim: () -> Float, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .size(EnvelopeGeometry.Width, EnvelopeGeometry.Height)
            .graphicsLayer { alpha = dim() }
            .drawBehind {
                drawRoundRect(
                    DarkroomColors.Rim2,
                    cornerRadius = CornerRadius(4.dp.toPx()),
                    style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
                )
            }
            .plainClickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Icon(DarkroomIcons.Plus, null, tint = DarkroomColors.Paper, modifier = Modifier.size(22.dp))
        Text(stringResource(R.string.envelope_new), style = DarkroomText.TraySwitch.copy(fontSize = DarkroomText.EnvelopeName.fontSize))
    }
}

/** 白边小相片（露角 / 扇形滑出的照片）。 */
@Composable
internal fun Print(photo: PhotoItem, modifier: Modifier = Modifier, size: Dp = EnvelopeGeometry.Print, lift: () -> Float = { 0f }) {
    val model = rememberThumbModel(photo)
    Box(
        modifier
            .size(size)
            .graphicsLayer {
                shadowElevation = 4.dp.toPx() + FilingMotion.LiftShadow.toPx() * lift()
                shape = androidx.compose.ui.graphics.RectangleShape
            }
            .background(DarkroomColors.PrintBorder)
            .padding(EnvelopeGeometry.PrintBorder),
    ) {
        AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/**
 * 袋子 / 面板行里小相片的取图请求：走系统缩略图（按显示尺寸解码、RGB565），和胶片格子共用内存缓存 key，
 * 已经解码过的直接命中；不交叉淡入，滚动列表时不额外动画。
 */
@Composable
internal fun rememberThumbModel(photo: PhotoItem): ImageRequest {
    val context = LocalContext.current
    return remember(photo.id) {
        ImageRequest.Builder(context)
            .data(GridThumb(photo.uri))
            .memoryCacheKey(photoThumbKey(photo.id))
            .placeholderMemoryCacheKey(photoThumbKey(photo.id))
            .allowRgb565(true)
            .crossfade(false)
            .build()
    }
}

/** 露角每个袋子歪得不一样（±5°），按照片 id 固定。 */
internal fun coverTilt(id: Long): Float = (Math.floorMod(id * 7919L, 11L) - 5L).toFloat()

/** 封口：上宽下窄的梯形。 */
internal val FlapShape = GenericShape { size, _ ->
    moveTo(0f, 0f)
    lineTo(size.width, 0f)
    lineTo(size.width * 0.86f, size.height)
    lineTo(size.width * 0.14f, size.height)
    close()
}

/** 正面：上沿中间一个半圆指口（方便抽照片），渐变牛皮纸 + 1dp 高光。 */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFront(notch: Dp = EnvelopeGeometry.NotchRadius) {
    val r = notch.toPx()
    val cx = size.width / 2f
    val corner = 4.dp.toPx()
    val path = Path().apply {
        moveTo(0f, 0f)
        lineTo(cx - r, 0f)
        arcTo(Rect(cx - r, -r, cx + r, r), 180f, -180f, false)
        lineTo(size.width, 0f)
        lineTo(size.width, size.height - corner)
        quadraticTo(size.width, size.height, size.width - corner, size.height)
        lineTo(corner, size.height)
        quadraticTo(0f, size.height, 0f, size.height - corner)
        close()
    }
    drawPath(path, Brush.verticalGradient(listOf(DarkroomColors.KraftHigh, DarkroomColors.Kraft)))
    drawLine(DarkroomColors.Paper.copy(alpha = 0.14f), Offset(0f, 0.5f), Offset(cx - r, 0.5f), 1.dp.toPx())
    drawLine(DarkroomColors.Paper.copy(alpha = 0.14f), Offset(cx + r, 0.5f), Offset(size.width, 0.5f), 1.dp.toPx())
}

/** 悬停计时：沿袋子外沿走一圈细线，满了袋子自己打开。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDwellRing(t: Float) {
    val inset = -6.dp.toPx()
    val path = Path().apply {
        addRoundRect(
            androidx.compose.ui.geometry.RoundRect(
                Rect(Offset(inset, EnvelopeGeometry.BackTop.toPx() + inset), Size(size.width - inset * 2, size.height - EnvelopeGeometry.BackTop.toPx() - inset * 2)),
                CornerRadius(8.dp.toPx()),
            ),
        )
    }
    val measure = PathMeasure().apply { setPath(path, false) }
    val out = Path()
    measure.getSegment(0f, measure.length * t, out, true)
    drawPath(out, DarkroomColors.Paper.copy(alpha = 0.5f), style = Stroke(1.5.dp.toPx()))
}

/**
 * 从袋口滑出的照片（悬停打开 3 张、偷看 5 张）。画在托盘之上的浮层里（横向列表会裁掉超出的部分），
 * 按袋口那条线裁掉下面，看上去是从袋子正面后面抽出来的。[rect] 是袋子的根坐标边界，[origin] 是浮层的根坐标。
 */
@Composable
internal fun EnvelopeFanLayer(
    photos: List<PhotoItem>,
    rect: Rect,
    origin: Offset,
    peek: Boolean,
    progress: () -> Float,
) {
    val g = EnvelopeGeometry
    val size = if (peek) PEEK_PRINT else g.Print
    val spread = if (peek) 66.dp else 22.dp
    val rise = if (peek) 118.dp else 34.dp
    val arc = if (peek) 22.dp else 8.dp
    val deg = if (peek) FilingMotion.PEEK_FAN_DEG else FilingMotion.HOVER_FAN_DEG
    Box(
        Modifier
            .fillMaxSize()
            .drawWithContent {
                val lift = if (peek) FilingMotion.PeekLift.toPx() * progress().coerceAtMost(1f) else FilingMotion.LeanLift.toPx()
                clipRect(bottom = rect.top - origin.y + g.MouthY.toPx() - lift) { this@drawWithContent.drawContent() }
            },
    ) {
        photos.forEachIndexed { i, photo ->
            val n = photos.size
            val k = if (n == 1) 0f else i / (n - 1f) * 2f - 1f // -1..1
            Print(
                photo,
                size = size,
                modifier = Modifier.graphicsLayer {
                    val t = progress()
                    val lift = if (peek) FilingMotion.PeekLift.toPx() * t.coerceAtMost(1f) else FilingMotion.LeanLift.toPx()
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    translationX = rect.center.x - origin.x - size.toPx() / 2f + k * spread.toPx() * t
                    translationY = rect.top - origin.y + g.MouthY.toPx() - lift - (rise + (1f - k * k) * arc).toPx() * t
                    rotationZ = k * deg * t
                    alpha = if (t <= 0f) 0f else 1f
                },
            )
        }
    }
}

private val PEEK_PRINT = 60.dp
