package com.winters.cleanphotos.feature.contactsheet

import android.text.format.DateFormat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
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
import com.winters.cleanphotos.core.design.plainClickable
import com.winters.cleanphotos.core.locale.currentLocale
import com.winters.cleanphotos.domain.model.AlbumRules
import com.winters.cleanphotos.domain.model.PhotoAlbum
import com.winters.cleanphotos.domain.model.PhotoItem
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 面板行里的迷你袋子 36×48：袋背上沿 8，袋口 11，正面 37，指口 5，封口 16，露角 24。 */
internal object MiniEnvelope {
    val Width = 36.dp
    val Height = 48.dp
    val BackTop = 8.dp
    val MouthY = 11.dp
    val FrontHeight = 37.dp
    val Notch = 5.dp
    val FlapHeight = 16.dp
    val Print = 24.dp
    val RowHeight = 64.dp
}

/**
 * 相册面板（ui-design 3.20「相册面板」）：从底部升起盖住托盘，背后印样表压暗 64%。
 * 标题「放进相册」+「共 N 个」（拖着照片叠时左边是停靠小叠），搜索框，全部相册按最后装进去的时间排（打开时定下，开着时不变）。
 * 拖着照片叠时行只高亮、迷你袋子翻开封口（不弹名签：2026-10-27 用户决定）；点一行 = 放进去。
 * 升起 / 降下的进度 [progress] 和下拉的位移只在绘制阶段读。
 */
@Composable
internal fun AlbumSheet(
    albums: List<PhotoAlbum>,
    query: String,
    onQuery: (String) -> Unit,
    progress: () -> Float,
    visuals: FilingVisuals,
    listState: LazyListState,
    parked: DragStack?,
    showPark: Boolean,
    now: Long,
    onPick: (PhotoAlbum) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val pull = remember { Animatable(0f) }
    // 面板开着时顺序不动（打开那一刻的顺序）：装完一袋列表不跳，飞入能对准那一行
    val order = remember { albums.map { it.relativePath } }
    val ordered = remember(albums) { TrayRules.arrange(albums, order, emptyList()) }
    val results = remember(ordered, query) { AlbumSearch.filter(ordered, query) }
    val create = remember(albums, query) { if (query.isBlank()) null else AlbumSearch.createName(albums, query) }
    val ime = WindowInsets.ime
    val status = WindowInsets.statusBars
    val nav = WindowInsets.navigationBars
    val locale = currentLocale()
    val dateFormat = remember(locale) { DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMd"), locale) }
    val zone = remember { ZoneId.systemDefault() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sheetMax = minOf(maxHeight * FilingMotion.SHEET_HEIGHT, FilingMotion.SheetMaxHeight)
        // 压暗：点一下关掉
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = FilingMotion.SHEET_SCRIM * progress().coerceIn(0f, 1f) }
                .background(DarkroomColors.Room)
                .plainClickable(onClick = onDismiss),
        )
        val shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        Column(
            Modifier
                // 跟着键盘上移，顶部不高过状态栏下 56dp；键盘高度只在布局阶段读
                .layout { measurable, constraints ->
                    val keyboard = ime.getBottom(this)
                    val top = status.getTop(this) + FilingMotion.SheetTopClear.roundToPx()
                    val h = minOf(sheetMax.roundToPx(), constraints.maxHeight - keyboard - top).coerceAtLeast(0)
                    val p = measurable.measure(Constraints.fixed(constraints.maxWidth, h))
                    layout(constraints.maxWidth, constraints.maxHeight) { p.place(0, constraints.maxHeight - keyboard - h) }
                }
                .graphicsLayer {
                    translationY = (1f - progress()) * size.height + pull.value
                }
                .onGloballyPositioned { visuals.sheetRect = it.boundsInRoot() }
                .clip(shape)
                .background(DarkroomColors.Tray)
                .border(Dimens.Hairline, DarkroomColors.Rim2, shape)
                .blockTouchesBelow(),
        ) {
            // 把手 + 标题：下拉关掉
            Column(
                Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (pull.value > FilingMotion.SheetDismissDrag.toPx()) onDismiss()
                                else scope.launch { pull.animateTo(0f, MotionSprings.Settle.spec()) }
                            },
                            onDragCancel = { scope.launch { pull.animateTo(0f, MotionSprings.Settle.spec()) } },
                            onVerticalDrag = { change, dy ->
                                change.consume()
                                scope.launch { pull.snapTo((pull.value + dy).coerceAtLeast(0f)) }
                            },
                        )
                    },
            ) {
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 8.dp)
                        .size(32.dp, 4.dp)
                        .clip(CircleShape)
                        .background(DarkroomColors.Rim2),
                )
                Row(
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp).fillMaxWidth().height(40.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (showPark) ParkedStack(parked, visuals)
                    Text(stringResource(R.string.tray_title), style = DarkroomText.TrayTitle, modifier = Modifier.weight(1f))
                    Text(
                        if (query.isBlank()) pluralStringResource(R.plurals.sheet_album_total, albums.size, albums.size)
                        else pluralStringResource(R.plurals.sheet_album_results, results.size, results.size),
                        style = DarkroomText.SheetRowMeta,
                    )
                }
            }
            SearchField(query, onQuery, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp))
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onGloballyPositioned { visuals.sheetList = it.boundsInRoot() },
                contentPadding = PaddingValues(bottom = with(density) { nav.getBottom(this).toDp() } + 8.dp),
            ) {
                if (create != null) {
                    item(key = NEW_ROW_KEY) {
                        TrackRect(visuals.rowRects, NEW_ROW_KEY)
                        CreateRow(create, hovered = visuals.sheetHovered == NEW_ROW_KEY, visuals = visuals, onClick = { onCreate(create) })
                    }
                }
                items(results, key = { it.relativePath }) { album ->
                    TrackRect(visuals.rowRects, album.relativePath)
                    TrackRect(visuals.rowMouthRects, album.relativePath)
                    val time = when (val t = LastFiled.of(album.lastFiledAt, now, zone)) {
                        null -> null
                        LastFiled.Today -> stringResource(R.string.sheet_time_today)
                        is LastFiled.DaysAgo -> pluralStringResource(R.plurals.sheet_time_days_ago, t.days, t.days)
                        is LastFiled.On -> dateFormat.format(t.date)
                    }
                    AlbumRow(album, time, hovered = visuals.sheetHovered == album.relativePath, visuals = visuals, onClick = { onPick(album) })
                }
            }
        }
    }
}

/** 停靠小叠：0.4 倍照片叠 + 张数。拖着照片叠打开面板、还没放下时空着一个位置。 */
@Composable
private fun ParkedStack(parked: DragStack?, visuals: FilingVisuals) {
    Box(
        Modifier
            .size(40.dp, 32.dp)
            .onGloballyPositioned { visuals.parkAt = it.boundsInRoot().center },
    ) {
        if (parked != null) {
            parked.photos.take(2).reversed().forEachIndexed { i, p ->
                Print(p, size = 26.dp, modifier = Modifier.offset(x = (4 + i * 4).dp, y = 2.dp).graphicsLayer { rotationZ = if (i == 0) -8f else 4f })
            }
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(height = 16.dp, width = if (parked.photos.size >= 10) 22.dp else 16.dp)
                    .clip(CircleShape)
                    .background(DarkroomColors.Paper),
                contentAlignment = Alignment.Center,
            ) {
                Text("${parked.photos.size}", style = DarkroomText.EnvelopeCount.copy(color = DarkroomColors.Room))
            }
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        drawRoundRect(
                            DarkroomColors.Rim2,
                            cornerRadius = CornerRadius(4.dp.toPx()),
                            style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))),
                        )
                    },
            )
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(DarkroomColors.Tray2)
            .padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(DarkroomIcons.Search, null, tint = DarkroomColors.Dim, modifier = Modifier.size(16.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text(stringResource(R.string.sheet_search_hint), style = DarkroomText.SheetSearch.copy(color = DarkroomColors.Dim))
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = DarkroomText.SheetSearch,
                cursorBrush = SolidColor(DarkroomColors.Paper),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Box(
                Modifier.size(Dimens.MinTouch - 12.dp).plainClickable { onQuery("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(DarkroomIcons.Close, stringResource(R.string.sheet_search_clear), tint = DarkroomColors.Dim, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** 行悬停高亮：整行底色淡入 `Tray2`（SHORT），颜色只在绘制阶段读。 */
@Composable
private fun rowHighlight(hovered: Boolean) =
    animateFloatAsState(if (hovered) 1f else 0f, tween(MotionDurations.SHORT, easing = MotionEasings.Standard), label = "row-hover")

@Composable
private fun AlbumRow(album: PhotoAlbum, time: String?, hovered: Boolean, visuals: FilingVisuals, onClick: () -> Unit) {
    val path = album.relativePath
    val hi = rowHighlight(hovered)
    val flight = visuals.flight?.takeIf { it.intoSheet && it.relativePath == path }
    val held = flight != null && !flight.released
    Row(
        Modifier
            .fillMaxWidth()
            .height(MiniEnvelope.RowHeight)
            .onGloballyPositioned { visuals.rowRects[path] = it.boundsInRoot() }
            .drawBehind { drawRect(DarkroomColors.Tray2.copy(alpha = hi.value)) }
            .plainClickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MiniEnvelopeView(
            cover = if (held) flight?.heldCover else album.recent.firstOrNull(),
            // 平时合着；悬停翻开；飞进来时跟着这次飞入的封口走
            flap = { visuals.flight?.takeIf { it.intoSheet && it.relativePath == path }?.flap?.value ?: (1f - hi.value) },
            bump = { visuals.flight?.takeIf { it.intoSheet && it.relativePath == path }?.bump?.value ?: 0f },
            modifier = Modifier.onGloballyPositioned { visuals.rowMouthRects[path] = it.boundsInRoot() },
        )
        Column(Modifier.weight(1f)) {
            Text(album.name, style = DarkroomText.SheetRowName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row {
                val count = if (held) flight?.heldCount ?: album.count else album.count
                OdometerText(count.toLong(), style = DarkroomText.SheetRowMeta)
                Text(
                    stringResource(R.string.sheet_row_meta, pluralStringResource(R.plurals.envelope_count_unit, count), path.trimEnd('/')),
                    style = DarkroomText.SheetRowMeta,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (time != null) Text(time, style = DarkroomText.SheetRowTime, maxLines = 1)
    }
}

@Composable
private fun CreateRow(name: String, hovered: Boolean, visuals: FilingVisuals, onClick: () -> Unit) {
    val hi = rowHighlight(hovered)
    Row(
        Modifier
            .fillMaxWidth()
            .height(MiniEnvelope.RowHeight)
            .onGloballyPositioned { visuals.rowRects[NEW_ROW_KEY] = it.boundsInRoot() }
            .drawBehind { drawRect(DarkroomColors.Tray2.copy(alpha = hi.value)) }
            .plainClickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(MiniEnvelope.Width, MiniEnvelope.Height)
                .drawBehind {
                    drawRoundRect(
                        DarkroomColors.Rim2,
                        cornerRadius = CornerRadius(3.dp.toPx()),
                        style = Stroke(1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(DarkroomIcons.Plus, null, tint = DarkroomColors.Paper, modifier = Modifier.size(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.sheet_create, name), style = DarkroomText.SheetRowName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.new_album_path_new, AlbumRules.pathFor(name).trimEnd('/')),
                style = DarkroomText.SheetRowMeta,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 迷你袋子：[flap] 0 = 翻开、1 = 合上；[bump] 合盖时压下去。 */
@Composable
private fun MiniEnvelopeView(cover: PhotoItem?, flap: () -> Float, bump: () -> Float, modifier: Modifier = Modifier) {
    val m = MiniEnvelope
    Box(
        modifier
            .size(m.Width, m.Height)
            .graphicsLayer { translationY = 1.5.dp.toPx() * bump() },
    ) {
        Box(
            Modifier
                .offset(y = m.BackTop)
                .size(m.Width, m.Height - m.BackTop)
                .clip(RoundedCornerShape(2.dp))
                .background(DarkroomColors.KraftInside),
        )
        if (cover != null) {
            Print(cover, size = m.Print, modifier = Modifier.offset(6.dp, 0.dp).graphicsLayer { rotationZ = coverTilt(cover.id) })
        }
        Box(
            Modifier
                .offset(y = m.MouthY)
                .size(m.Width, m.FrontHeight)
                .drawBehind { drawFront(m.Notch) },
        )
        Box(
            Modifier
                .offset(y = m.MouthY)
                .size(m.Width, m.FlapHeight)
                .graphicsLayer {
                    val angle = 180f * (1f - flap())
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

/** 名签形状：圆角 4 的标签纸 + 下沿正中 10×6 的小舌头。 */
private val TagShape = GenericShape { size, _ ->
    val tongueH = size.height * (FilingMotion.TagTongueHeight.value / (FilingMotion.TagHeight.value + FilingMotion.TagTongueHeight.value))
    val bodyH = size.height - tongueH
    val r = bodyH * (4f / FilingMotion.TagHeight.value)
    val halfTongue = tongueH * (FilingMotion.TagTongueWidth.value / 2f / FilingMotion.TagTongueHeight.value)
    addRoundRect(RoundRect(Rect(0f, 0f, size.width, bodyH), CornerRadius(r)))
    moveTo(size.width / 2f - halfTongue, bodyH - 0.5f)
    lineTo(size.width / 2f, size.height)
    lineTo(size.width / 2f + halfTongue, bodyH - 0.5f)
    close()
}

/**
 * 拖动时手指下目标的名签（ui-design 3.20「名签弹出」）：弹出的和缩回的各一张，位置、缩放、透明度全在 graphicsLayer 里算，
 * 手指移动只让这一层重绘，不重组。[labelFor] 给出目标上写的字（相册名 /「新袋子」/「还有 N 个」）。
 */
@Composable
internal fun HoverTagLayer(
    visuals: FilingVisuals,
    drag: DragStack,
    origin: Offset,
    screenWidth: Float,
    labelFor: (String) -> String?,
) {
    val inKey = visuals.tagKey
    val outKey = visuals.tagOutKey
    Box(Modifier.fillMaxSize()) {
        if (outKey != null && outKey != inKey) {
            key(outKey) { labelFor(outKey)?.let { HoverTag(it, outKey, { visuals.tagOut.value }, visuals, drag, origin, screenWidth) } }
        }
        if (inKey != null) {
            key(inKey) { labelFor(inKey)?.let { HoverTag(it, inKey, { visuals.tagIn.value }, visuals, drag, origin, screenWidth) } }
        }
    }
}

@Composable
private fun HoverTag(
    text: String,
    target: String,
    progress: () -> Float,
    visuals: FilingVisuals,
    drag: DragStack,
    origin: Offset,
    screenWidth: Float,
) {
    val m = FilingMotion
    val g = EnvelopeGeometry
    Box(
        Modifier
            .graphicsLayer {
                val rect = visuals.envelopeRects[target]
                val t = progress()
                if (rect == null || t <= 0f) {
                    alpha = 0f
                    return@graphicsLayer
                }
                val w = size.width
                val h = size.height
                val bottom = TagPlacement.bottom(drag.pointer.y, rect.top, m.TagFingerGap.toPx(), m.TagFanClear.toPx())
                val left = TagPlacement.left(rect.center.x, w, screenWidth, m.TagMargin.toPx())
                // 从袋子标签的位置和大小（0.88 倍）放大、上移到位
                val fromCy = rect.bottom - (g.LabelBottom + g.LabelHeight / 2).toPx()
                val toCy = bottom - h / 2f
                val cx = rect.center.x + (left + w / 2f - rect.center.x) * t
                val cy = fromCy + (toCy - fromCy) * t
                translationX = cx - w / 2f - origin.x
                translationY = cy - h / 2f - origin.y
                val s = m.TAG_FROM_SCALE + (1f - m.TAG_FROM_SCALE) * t
                scaleX = s
                scaleY = s
                alpha = t.coerceIn(0f, 1f)
                shadowElevation = 8.dp.toPx() * t.coerceIn(0f, 1f)
                shape = TagShape
            }
            .background(DarkroomColors.Label, TagShape)
            .widthIn(min = m.TagMinWidth, max = m.TagMaxWidth)
            .height(m.TagHeight + m.TagTongueHeight)
            .padding(start = 12.dp, end = 12.dp, bottom = m.TagTongueHeight),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = DarkroomText.HoverTag, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
