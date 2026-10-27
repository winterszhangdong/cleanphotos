package com.winters.cleanphotos.feature.contactsheet

import android.text.format.DateFormat
import androidx.compose.animation.core.Animatable
import coil.compose.AsyncImage
import androidx.compose.ui.unit.Density
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.runtime.LaunchedEffect
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

/**
 * 面板行左边的相片卡座 56×52（ui-design 3.20「相册面板」方案 A）：相册最新的 3 张小相片（40×44，白边 2、下边 6）
 * 叠着插在一条 10dp 高的牛皮纸卡座里。[Rest] / [Open] 是每张相片常态 / 打开时的位移、转角、缩放和亮度
 * （下标 0 = 最上面的封面，1 = 右后，2 = 左后），打开只动 graphicsLayer。
 */
internal object PrintHolder {
    val Width = 56.dp
    val Height = 52.dp
    val PrintLeft = 8.dp
    val PrintTop = 2.dp
    val PrintWidth = 40.dp
    val PrintHeight = 44.dp
    val PrintBorder = 2.dp
    val PrintChin = 6.dp
    val BaseInset = 3.dp
    val BaseHeight = 10.dp
    val RowHeight = 64.dp
    /** 打开时封面上浮多少（飞入落点也在这里，收拢时跟着落回）。 */
    val OpenLift = 7.dp

    /** 一张相片的姿态：位移 dp、转角、缩放、亮度（1 = 原色）、影子 dp。 */
    data class Pose(val dx: Float, val dy: Float, val rotation: Float, val scale: Float, val brightness: Float, val shadow: Float)

    val Rest = listOf(
        Pose(0f, 0f, -1.5f, 1f, 1f, 2f),
        Pose(6f, 2f, 8f, 1f, 0.8f, 2f),
        Pose(-6f, 3f, -10f, 1f, 0.62f, 2f),
    )
    val Open = listOf(
        Pose(0f, -OpenLift.value, 0f, 1.06f, 1f, 6f),
        Pose(14f, -3f, 16f, 1f, 0.95f, 3f),
        Pose(-14f, -3f, -18f, 1f, 0.8f, 3f),
    )

    /** 飞入落点：最上面那张相片的图片中心（打开时上浮后的位置），根坐标。 */
    fun landCenter(holder: Rect, density: Density): Offset = with(density) {
        Offset(
            holder.left + (PrintLeft + PrintWidth / 2).toPx(),
            holder.top + (PrintTop + PrintBorder + (PrintHeight - PrintBorder - PrintChin) / 2 - OpenLift).toPx(),
        )
    }
}

/**
 * 相册面板（ui-design 3.20「相册面板」）：从底部升起盖住托盘，背后印样表压暗 64%。
 * 标题「放进相册」+「共 N 个」（照片叠松在「还有 N 个」上时左边是停靠小叠，标题下一行淡入「点一个相册放进去」），
 * 搜索框，全部相册按最后装进去的时间排（打开时定下，开着时不变）。每行左边是相册最新 3 张相片插在牛皮纸卡座里。
 * 点一行 = 放进去：等授权时这一行高亮、相片叠打开；被拒绝时面板留着、照片叠仍停靠，可以再选（用户 2026-10-27 决定）。
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
                    if (parked != null) ParkedStack(parked, visuals)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.tray_title), style = DarkroomText.TrayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (parked != null) ParkedHint()
                    }
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
                    .weight(1f),
                contentPadding = PaddingValues(bottom = with(density) { nav.getBottom(this).toDp() } + 8.dp),
            ) {
                if (create != null) {
                    item(key = NEW_ROW_KEY) {
                        CreateRow(create, onClick = { onCreate(create) })
                    }
                }
                items(results, key = { it.relativePath }) { album ->
                    TrackRect(visuals.rowHolderRects, album.relativePath)
                    val time = when (val t = LastFiled.of(album.lastFiledAt, now, zone)) {
                        null -> null
                        LastFiled.Today -> stringResource(R.string.sheet_time_today)
                        is LastFiled.DaysAgo -> pluralStringResource(R.plurals.sheet_time_days_ago, t.days, t.days)
                        is LastFiled.On -> dateFormat.format(t.date)
                    }
                    AlbumRow(album, time, picked = visuals.sheetPicked == album.relativePath, visuals = visuals, onClick = { onPick(album) })
                }
            }
        }
    }
}

/** 停靠小叠：0.4 倍照片叠 + 张数（照片叠松在「还有 N 个」上之后停在这里，等点一行）。 */
@Composable
private fun ParkedStack(parked: DragStack, visuals: FilingVisuals) {
    Box(
        Modifier
            .size(40.dp, 32.dp)
            .onGloballyPositioned { visuals.parkAt = it.boundsInRoot().center },
    ) {
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
    }
}

/** 停靠时标题下一行淡入的提示（MEDIUM 淡入，停靠结束跟着小叠一起消失）。 */
@Composable
private fun ParkedHint() {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) { alpha.animateTo(1f, tween(MotionDurations.MEDIUM, easing = MotionEasings.Standard)) }
    Text(
        stringResource(R.string.sheet_parked_hint),
        style = DarkroomText.SheetParkedHint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.graphicsLayer { this.alpha = alpha.value },
    )
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

/** 行底：圆角 14 的相纸白 7% + `Rim2` 细边，[t] 0..1 只在绘制阶段读。 */
private fun Modifier.rowHighlight(t: () -> Float) = drawBehind {
    val v = t().coerceIn(0f, 1f)
    if (v <= 0f) return@drawBehind
    val r = CornerRadius(14.dp.toPx())
    drawRoundRect(DarkroomColors.Paper.copy(alpha = 0.07f * v), cornerRadius = r)
    val w = Dimens.Hairline.toPx()
    drawRoundRect(
        DarkroomColors.Rim2.copy(alpha = DarkroomColors.Rim2.alpha * v),
        topLeft = Offset(w / 2f, w / 2f),
        size = Size(size.width - w, size.height - w),
        cornerRadius = r,
        style = Stroke(w),
    )
}

/** 行的外框：左右各留 8，里面左右 12，左边卡座和文字之间 14（草稿 c-album-sheet-options 方案 A）。 */
@Composable
private fun SheetRowFrame(highlight: () -> Float, onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(PrintHolder.RowHeight)
            .padding(horizontal = 8.dp)
            .rowHighlight(highlight)
            .plainClickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

@Composable
private fun AlbumRow(album: PhotoAlbum, time: String?, picked: Boolean, visuals: FilingVisuals, onClick: () -> Unit) {
    val path = album.relativePath
    val pick = animateFloatAsState(if (picked) 1f else 0f, MotionSprings.Snappy.spec(), label = "row-picked")
    val flight = visuals.flight?.takeIf { it.intoSheet && it.relativePath == path }
    val held = flight != null && !flight.released
    val current = remember(album.recent) { SheetPrints.of(album.recent) }
    // 点下那一刻的张数和相片：授权通过后相册数据会先于飞入更新，等飞入合上那一刻再换，不先跳一下
    val atPick = remember(picked) { if (picked) album.count to current else null }
    // 点了等授权时打开；飞进来时保持打开，落定那一下（封口时间线）收拢，封面换成新的
    val open = {
        val f = visuals.flight?.takeIf { it.intoSheet && it.relativePath == path }
        if (f != null) (if (f.released) 0f else 1f - f.flap.value) else pick.value
    }
    SheetRowFrame(highlight = open, onClick = onClick) {
        PrintStack(
            prints = when {
                held -> flight?.heldPrints.orEmpty()
                atPick != null -> atPick.second
                else -> current
            },
            open = open,
            press = { visuals.flight?.takeIf { it.intoSheet && it.relativePath == path }?.bump?.value ?: 0f },
            modifier = Modifier.onGloballyPositioned { visuals.rowHolderRects[path] = it.boundsInRoot() },
        )
        Column(Modifier.weight(1f)) {
            Text(album.name, style = DarkroomText.SheetRowName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row {
                val count = when {
                    held -> flight?.heldCount ?: album.count
                    atPick != null -> atPick.first
                    else -> album.count
                }
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

/** 「+ 新建」：同一个卡座，相片位置是一张虚线空相片 + 加号，卡座压暗一半。 */
@Composable
private fun CreateRow(name: String, onClick: () -> Unit) {
    SheetRowFrame(highlight = { 0f }, onClick = onClick) {
        Box(Modifier.size(PrintHolder.Width, PrintHolder.Height)) {
            EmptyPrint(Modifier.offset(PrintHolder.PrintLeft, PrintHolder.PrintTop)) {
                Icon(DarkroomIcons.Plus, null, tint = DarkroomColors.Paper, modifier = Modifier.size(16.dp))
            }
            HolderBase(open = { 0f }, alpha = 0.5f, modifier = Modifier.align(Alignment.BottomCenter))
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

/**
 * 卡座里的相片叠：[prints] 第一张在最上面；不到 3 张少画几张，空相册是一张虚线空相片。
 * [open] 0..1 打开（后两张散开、封面上浮放大），[press] 落定时整叠轻压（scaleY 0.97）。都只在绘制阶段读。
 */
@Composable
private fun PrintStack(prints: List<PhotoItem>, open: () -> Float, press: () -> Float, modifier: Modifier = Modifier) {
    val h = PrintHolder
    Box(
        modifier
            .size(h.Width, h.Height)
            .graphicsLayer {
                transformOrigin = TransformOrigin(0.5f, 1f)
                scaleY = 1f - 0.03f * press()
            },
    ) {
        if (prints.isEmpty()) EmptyPrint(Modifier.offset(h.PrintLeft, h.PrintTop))
        // 从下往上画：左后、右后、封面
        for (i in prints.indices.reversed()) {
            key(prints[i].id) { SheetPrint(prints[i], i, open) }
        }
        HolderBase(open = open, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun SheetPrint(photo: PhotoItem, index: Int, open: () -> Float) {
    val h = PrintHolder
    val rest = h.Rest[index]
    val to = h.Open[index]
    val model = rememberThumbModel(photo)
    val shape = RoundedCornerShape(2.dp)
    Box(
        Modifier
            .offset(h.PrintLeft, h.PrintTop)
            .size(h.PrintWidth, h.PrintHeight)
            .graphicsLayer {
                val t = open().coerceIn(0f, 1f)
                translationX = lerpF(rest.dx, to.dx, t).dp.toPx()
                translationY = lerpF(rest.dy, to.dy, t).dp.toPx()
                rotationZ = lerpF(rest.rotation, to.rotation, t)
                val sc = lerpF(rest.scale, to.scale, t)
                scaleX = sc
                scaleY = sc
                shadowElevation = lerpF(rest.shadow, to.shadow, t).dp.toPx()
                this.shape = shape
            }
            // 后面两张压暗：盖一层黑，透明度只在绘制阶段读
            .drawWithContent {
                drawContent()
                val dim = 1f - lerpF(rest.brightness, to.brightness, open().coerceIn(0f, 1f))
                if (dim > 0.001f) drawRect(Color.Black.copy(alpha = dim))
            }
            .background(DarkroomColors.PrintBorder, shape)
            .padding(start = h.PrintBorder, top = h.PrintBorder, end = h.PrintBorder, bottom = h.PrintChin),
    ) {
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().background(DarkroomColors.Tray2),
        )
    }
}

/** 空相册 / 新建：一张虚线描边的空相片（Rim2，虚线 4/3）。 */
@Composable
private fun EmptyPrint(modifier: Modifier = Modifier, content: @Composable () -> Unit = {}) {
    Box(
        modifier
            .size(PrintHolder.PrintWidth, PrintHolder.PrintHeight)
            .drawBehind {
                val w = 1.2.dp.toPx()
                drawRoundRect(
                    DarkroomColors.Rim2,
                    topLeft = Offset(w / 2f, w / 2f),
                    size = Size(size.width - w, size.height - w),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                    style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                )
            }
            .padding(bottom = PrintHolder.PrintChin - PrintHolder.PrintBorder),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** 牛皮纸卡座：圆角 2/3，上沿一道亮边，正中一小条标签纸；打开时外面描一圈相纸白 25% 细边。 */
@Composable
private fun HolderBase(open: () -> Float, modifier: Modifier = Modifier, alpha: Float = 1f) {
    val h = PrintHolder
    Box(
        modifier
            .padding(horizontal = h.BaseInset)
            .fillMaxWidth()
            .height(h.BaseHeight)
            .graphicsLayer { this.alpha = alpha }
            .drawBehind {
                val r = CornerRadius(2.5.dp.toPx())
                drawRoundRect(DarkroomColors.Kraft, cornerRadius = r)
                drawRect(DarkroomColors.Paper.copy(alpha = 0.16f), size = Size(size.width, 1.dp.toPx()), topLeft = Offset(0f, 0f))
                val slotW = 14.dp.toPx()
                drawRoundRect(
                    DarkroomColors.Label.copy(alpha = 0.55f),
                    topLeft = Offset((size.width - slotW) / 2f, 3.dp.toPx()),
                    size = Size(slotW, 3.dp.toPx()),
                    cornerRadius = CornerRadius(1.dp.toPx()),
                )
                val t = open().coerceIn(0f, 1f)
                if (t > 0f) {
                    val w = 1.dp.toPx()
                    drawRoundRect(
                        DarkroomColors.Paper.copy(alpha = 0.25f * t),
                        topLeft = Offset(w / 2f, w / 2f),
                        size = Size(size.width - w, size.height - w),
                        cornerRadius = r,
                        style = Stroke(w),
                    )
                }
            },
    )
}

private fun lerpF(a: Float, b: Float, t: Float): Float = a + (b - a) * t

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
    fingerGap: Float,
    labelFor: (String) -> String?,
) {
    val inKey = visuals.tagKey
    val outKey = visuals.tagOutKey
    Box(Modifier.fillMaxSize()) {
        if (outKey != null && outKey != inKey) {
            key(outKey) { labelFor(outKey)?.let { HoverTag(it, outKey, { visuals.tagOut.value }, visuals, drag, origin, screenWidth, fingerGap) } }
        }
        if (inKey != null) {
            key(inKey) { labelFor(inKey)?.let { HoverTag(it, inKey, { visuals.tagIn.value }, visuals, drag, origin, screenWidth, fingerGap) } }
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
    fingerGap: Float,
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
                val bottom = TagPlacement.bottom(drag.pointer.y, rect.top, fingerGap, m.TagFanClear.toPx())
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
