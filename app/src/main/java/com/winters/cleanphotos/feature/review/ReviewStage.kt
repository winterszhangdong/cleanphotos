package com.winters.cleanphotos.feature.review

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.winters.cleanphotos.core.navigation.sharedPhoto
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import com.winters.cleanphotos.core.design.ApiTier
import com.winters.cleanphotos.core.design.DarkroomColors
import com.winters.cleanphotos.core.design.DevelopMatrix
import com.winters.cleanphotos.core.design.DevelopProfiles
import com.winters.cleanphotos.core.design.develop
import com.winters.cleanphotos.core.design.rememberDevelopState
import com.winters.cleanphotos.core.design.requiresDevelop
import com.winters.cleanphotos.domain.model.DaySummary
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.ReviewPage
import kotlinx.coroutines.launch

/** 页在舞台上的角色。 */
internal enum class PageRole { Previous, Next, Beneath, Current, Exit, Recede }

private data class StageEntry(val page: ReviewPage, val role: PageRole, val flight: ExitFlight? = null)

/**
 * 自绘三页放在同一个 Box，不用 HorizontalPager；所有手势在同一个 pointerInput（[detectReviewGestures]）。
 * 每页用 key(page.key) 保持同一个组合节点，页角色变化（下一张 → 当前 → 离场）不重建图片请求。
 */
@Composable
internal fun ReviewStage(
    state: ReviewStageState,
    tutorialDim: Boolean,
    imageSize: IntSize,
    summaries: Map<String, DaySummary>,
    summaryContent: @Composable (DaySummary, isCurrent: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = buildList {
        val current = state.currentPage
        val undo = state.undoFlight
        val taken = HashSet<String>()
        fun add(page: ReviewPage?, role: PageRole, flight: ExitFlight? = null) {
            if (page != null && taken.add(page.key)) add(StageEntry(page, role, flight))
        }
        state.flights.forEach { add(it.page, PageRole.Exit, it) }
        add(current, PageRole.Current)
        if (undo?.recedeKey != null) add(state.pages.firstOrNull { it.key == undo.recedeKey }, PageRole.Recede)
        if (state.axis == DragAxis.Vertical) {
            add(state.beneathPage, PageRole.Beneath)
        } else {
            add(state.nextPage, PageRole.Next)
            add(state.previousPage, PageRole.Previous)
        }
    }
    Box(
        modifier
            .fillMaxSize()
            .background(DarkroomColors.Room)
            .pointerInput(state) { detectReviewGestures(state.handler) },
    ) {
        // 上滑琥珀显影光 / 下滑中性暗角：在当前页之下、垫底页之上
        SwipeGlow(state, Modifier.zIndex(1f))
        entries.forEach { entry ->
            androidx.compose.runtime.key(entry.page.key) {
                PageLayer(state, entry, tutorialDim, imageSize, summaries, summaryContent)
            }
        }
        state.flights.forEach { f -> if (f.kind == ExitKind.Up) AmberAfterimages(f) }
    }
}

@Composable
private fun PageLayer(
    state: ReviewStageState,
    entry: StageEntry,
    tutorialDim: Boolean,
    imageSize: IntSize,
    summaries: Map<String, DaySummary>,
    summaryContent: @Composable (DaySummary, isCurrent: Boolean) -> Unit,
) {
    val page = entry.page
    val role = entry.role
    val z = when (role) {
        PageRole.Beneath, PageRole.Recede -> 0f
        PageRole.Previous, PageRole.Next -> 2f
        PageRole.Current -> 3f
        PageRole.Exit -> 4f
    }
    val corner = with(androidx.compose.ui.platform.LocalDensity.current) { LIFT_CORNER.toPx() }
    val saturation: () -> Float = {
        when (role) {
            PageRole.Current -> {
                val u = state.undoFlight?.takeIf { it.key == page.key }
                (u?.let { ExitPath.undoPose(it, it.progress.value).saturation } ?: ReviewGestureMath.dragSaturation(state.dy, state.thresholds)) *
                    (if (tutorialDim) TUTORIAL_SATURATION else 1f)
            }
            PageRole.Exit -> entry.flight!!.let { ExitPath.pose(it, it.progress.value).saturation }
            else -> 1f
        }
    }
    val brightness: () -> Float = {
        when (role) {
            PageRole.Current -> {
                val u = state.undoFlight?.takeIf { it.key == page.key }
                val b = u?.let { ExitPath.undoPose(it, it.progress.value).brightness }
                    ?: (ReviewGestureMath.dragBrightness(state.dy, state.thresholds) * state.currentEntryBrightness(page.key))
                b * (if (tutorialDim) TUTORIAL_BRIGHTNESS else 1f)
            }
            PageRole.Next -> ReviewGestureMath.enteringBrightness(minOf(0f, state.dx), state.width)
            PageRole.Previous -> ReviewGestureMath.enteringBrightness(maxOf(0f, state.dx), state.width)
            // 垫底页 alpha = 0 看不见，不加色调（加了只会白占一张全屏离屏层）；松手成为当前页时从 BENEATH_BRIGHTNESS 起亮
            PageRole.Beneath -> 1f
            PageRole.Recede -> 1f + (ExitPath.RECEDE_BRIGHTNESS - 1f) * ExitPath.recede(state.undoFlight?.progress?.value ?: 1f)
            PageRole.Exit -> entry.flight!!.let { ExitPath.pose(it, it.progress.value).brightness }
        }
    }
    Box(
        Modifier
            .zIndex(z)
            .fillMaxSize()
            .graphicsLayer {
                val t = state.thresholds
                when (role) {
                    PageRole.Current -> {
                        val undo = state.undoFlight?.takeIf { it.key == page.key }
                        if (undo != null) {
                            val pose = ExitPath.undoPose(undo, undo.progress.value)
                            translationX = pose.x
                            translationY = pose.y
                            scaleX = pose.scale; scaleY = pose.scale
                            alpha = pose.alpha
                            shape = RoundedCornerShape(corner * (1f - undo.progress.value))
                            clip = true
                        } else {
                            val s = ReviewGestureMath.dragScale(state.dy, t) * state.currentEntryScale(page.key) * state.zoom
                            translationX = state.dx + state.zoomOffset.x
                            translationY = state.dy + state.zoomOffset.y + (if (page is ReviewPage.Summary) state.summaryBounce else 0f)
                            scaleX = s; scaleY = s
                            val lift = ReviewGestureMath.cueProgress(state.dy, t)
                            if (lift > 0f) {
                                shape = RoundedCornerShape(corner * lift)
                                clip = true
                            }
                        }
                    }
                    PageRole.Next -> translationX = state.dx + state.width
                    PageRole.Previous -> translationX = state.dx - state.width
                    PageRole.Beneath -> {
                        // 用户决定：拖动时下面不露出下一张，只有暗房底色 + 琥珀显影光 / 中性暗角。
                        // 仍然组合并解码（alpha = 0），松手时下一张直接命中已解码的图，从暗处升起。
                        alpha = 0f
                        scaleX = ExitPath.RECEDE_SCALE; scaleY = ExitPath.RECEDE_SCALE
                    }
                    PageRole.Recede -> {
                        val r = ExitPath.recede(state.undoFlight?.progress?.value ?: 1f)
                        val s = 1f + (ExitPath.RECEDE_SCALE - 1f) * r
                        scaleX = s; scaleY = s
                    }
                    PageRole.Exit -> {
                        val f = entry.flight!!
                        val pose = ExitPath.pose(f, f.progress.value)
                        translationX = pose.x
                        translationY = pose.y
                        scaleX = pose.scale; scaleY = pose.scale
                        alpha = pose.alpha
                        shape = RoundedCornerShape(corner)
                        clip = true
                    }
                }
            }
            // 描边在色调层外面画：它每帧随 dy 变化，画在离屏层里会让整张照片每帧重画（亮度单独乘上，观感不变）
            .then(if (role == PageRole.Current) Modifier.liftRim(state, (page as? ReviewPage.Photo)?.photo, brightness) else Modifier)
            .tone(saturation, brightness),
    ) {
        when (page) {
            is ReviewPage.Photo -> ReviewPhotoImage(page.photo, role, state, imageSize)
            is ReviewPage.Summary -> summaries[page.dayKey]?.let { summaryContent(it, role == PageRole.Current) }
                ?: Box(Modifier.fillMaxSize().background(DarkroomColors.Room))
        }
    }
}

/**
 * 照片本体。固定请求尺寸 + 固定内存缓存键：同一张图在「下一张 / 当前 / 离场」之间切换时同步命中内存缓存，
 * 不走 SubcomposeAsyncImage、不 crossfade（lessons 2026-05-09）。
 * 决定 7：只有第一次从磁盘解码、且这时它就是当前页时才显影（≤ 280ms、起始亮度 ≥ 0.6），其余一律直接显示。
 */
@Composable
private fun ReviewPhotoImage(photo: PhotoItem, role: PageRole, state: ReviewStageState, imageSize: IntSize) {
    val context = LocalContext.current
    val request = remember(photo.id, photo.uri, imageSize) { reviewImageRequest(context, photo, imageSize) }
    val scope = rememberCoroutineScope()
    val roleNow = androidx.compose.runtime.rememberUpdatedState(role)
    // 照片保持原比例：按宽高比 Fit 居中；宽高未知时铺满容器、内容按 Fit 画
    val fit = remember(photo.width, photo.height, imageSize) {
        ReviewGestureMath.fitSize(photo.width, photo.height, imageSize.width.toFloat(), imageSize.height.toFloat())
    }
    val knownRatio = photo.width != null && photo.height != null
    val letterboxed = !knownRatio || fit.width < imageSize.width - 1f || fit.height < imageSize.height - 1f
    // 决定 7：只在「第一次从磁盘解码」时显影。组合时就能判断：大图不在内存缓存 → 这次一定走磁盘。
    // 若此刻它就是当前页，立即开始显影（同一帧压暗），解码期间显示网格缩略图占位，避免空帧，也不会「亮一帧再变暗」。
    val memoryCache = remember { coil.Coil.imageLoader(context).memoryCache }
    val diskDecode = remember(photo.id, imageSize) { memoryCache?.get(request.memoryCacheKey!!) == null }
    val hadThumb = remember(photo.id) { memoryCache?.get(coil.memory.MemoryCache.Key(com.winters.cleanphotos.core.design.photoThumbKey(photo.id))) != null }
    // 被共享元素从网格带进来时不显影——缩略图长成大图的过程中亮度不能变
    val navEntering = com.winters.cleanphotos.core.navigation.isNavEntering()
    val skipDevelop = remember(photo.id) { navEntering }
    val developAtCompose = remember(photo.id) { diskDecode && role == PageRole.Current && state.axis != DragAxis.Horizontal && !skipDevelop }
    // 需要显影时第一帧就是起始态（progress = 0），不会先亮一帧
    val developState = rememberDevelopState(DevelopProfiles.ReviewNext, developed = !developAtCompose)
    var developStarted by remember(photo.id) { androidx.compose.runtime.mutableStateOf(developAtCompose) }
    androidx.compose.runtime.LaunchedEffect(photo.id) {
        if (developAtCompose) {
            com.winters.cleanphotos.core.util.ReviewTrace.log("developStart", "photo" to photo.id, "at" to "compose", "thumb" to hadThumb)
            developState.develop()
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (letterboxed && ApiTier.Current.hasBlur) {
            // 没铺满的地方用同一张照片放大 1.2、模糊 36dp、亮度 40% 做环境光；31 以下按分级表不画，露出暗房底色
            val ambient = remember(photo.id, photo.uri) {
                ImageRequest.Builder(context).data(photo.uri).size(AMBIENT_SIZE).memoryCacheKey("review-amb-${photo.id}").crossfade(false).build()
            }
            AsyncImage(
                model = ambient,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.colorMatrix(ColorMatrix(DevelopMatrix.compose(AMBIENT_BRIGHTNESS, 1f, AMBIENT_SATURATION))),
                modifier = Modifier
                    .fillMaxSize()
                    // 放大 1.2 后会超出本页：不裁的话左右翻页时会盖到相邻页边缘，成一条灰边
                    .clipToBounds()
                    .graphicsLayer { scaleX = AMBIENT_SCALE; scaleY = AMBIENT_SCALE }
                    .blur(AMBIENT_BLUR),
            )
        }
        AsyncImage(
            model = request,
            contentDescription = null,
            // 宽高已知时容器就是照片比例，用 Crop 让共享元素从方形缩略图长成照片时内容一直撑满、不跳
            contentScale = if (knownRatio) ContentScale.Crop else ContentScale.Fit,
            modifier = Modifier
                .then(if (knownRatio) Modifier.aspectRatio(fit.width / fit.height) else Modifier.fillMaxSize())
                .sharedPhoto(photo.id, expandsHere = true, enabled = role == PageRole.Current)
                .develop(developState),
            onSuccess = { success ->
                val firstDiskDecode = success.result.dataSource.requiresDevelop()
                // 组合时已开始显影的不再重复；组合时还不是当前页、后来才在当前页完成磁盘解码的，在这里补显影
                val develop = firstDiskDecode && !developStarted && !skipDevelop && roleNow.value == PageRole.Current && state.axis != DragAxis.Horizontal
                com.winters.cleanphotos.core.util.ReviewTrace.log(
                    "imageLoaded", "photo" to photo.id, "source" to success.result.dataSource, "role" to roleNow.value,
                    "axis" to state.axis, "thumb" to hadThumb, "developedAtCompose" to developStarted, "develop" to develop,
                )
                if (develop) {
                    developStarted = true
                    scope.launch { developState.develop() }
                }
            },
        )
    }
}

internal fun reviewImageRequest(context: android.content.Context, photo: PhotoItem, size: IntSize): ImageRequest =
    ImageRequest.Builder(context)
        .data(photo.uri)
        .size(size.width.coerceAtLeast(1), size.height.coerceAtLeast(1))
        .precision(Precision.INEXACT)
        // 照片按原比例 Fit 显示，解码不必大过屏幕
        .scale(Scale.FIT)
        .memoryCacheKey("review-${photo.id}-${size.width}x${size.height}")
        .diskCacheKey(photo.uri.toString())
        .placeholderMemoryCacheKey(com.winters.cleanphotos.core.design.photoThumbKey(photo.id))
        .crossfade(false)
        .build()

/** 上滑底部琥珀显影光（未过阈值约 38%），下滑四周极淡中性暗角。 */
@Composable
private fun SwipeGlow(state: ReviewStageState, modifier: Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .drawWithCache {
                val up = Brush.radialGradient(
                    0f to DarkroomColors.Amber.copy(alpha = 0.55f),
                    0.55f to DarkroomColors.Amber.copy(alpha = 0.12f),
                    0.75f to Color.Transparent,
                    center = Offset(size.width / 2f, size.height),
                    radius = size.width * 1.4f,
                )
                val down = Brush.radialGradient(
                    0.4f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.55f),
                    center = Offset(size.width / 2f, size.height * 0.62f),
                    radius = maxOf(size.width * 1.2f, size.height * 0.7f),
                )
                onDrawBehind {
                    val dy = state.dy
                    val after = state.releaseCue.value
                    if (dy == 0f && after > 0f) {
                        // 松手余光：满强度起淡出
                        drawRect(if (state.releaseCueUp) up else down, alpha = after)
                        return@onDrawBehind
                    }
                    if (dy == 0f) return@onDrawBehind
                    val pre = ReviewGestureMath.cueProgress(dy, state.thresholds)
                    val past = state.pastAnim.value
                    if (dy < 0f) {
                        val a = (pre * UP_GLOW_PRE + (1f - UP_GLOW_PRE) * past).coerceIn(0f, 1f)
                        drawRect(up, alpha = a)
                    } else {
                        val a = (pre * DOWN_VIGNETTE_PRE + (1f - DOWN_VIGNETTE_PRE) * past).coerceIn(0f, 1f)
                        drawRect(down, alpha = a)
                    }
                }
            },
    )
}

/** 两层琥珀余影，延迟 50 / 100ms，透明度 0.4 / 0.18（读同一离场路径的延迟样本）。 */
@Composable
private fun AmberAfterimages(f: ExitFlight) {
    val corner = with(androidx.compose.ui.platform.LocalDensity.current) { LIFT_CORNER.toPx() }
    Box(
        Modifier
            .zIndex(3.5f)
            .fillMaxSize()
            .drawWithCache {
                // 余影跟照片本身一样大（不含上下环境光）
                val photo = (f.page as? ReviewPage.Photo)?.photo
                val fit = ReviewGestureMath.fitSize(photo?.width, photo?.height, size.width, size.height)
                onDrawBehind {
                    val t = f.progress.value
                    AFTERIMAGES.forEach { (delayFraction, alpha) ->
                        val pt = t - delayFraction
                        if (pt <= 0f) return@forEach
                        val pose = ExitPath.pose(f, pt)
                        val w = fit.width * pose.scale
                        val h = fit.height * pose.scale
                        val cx = size.width / 2f + pose.x
                        val cy = size.height / 2f + pose.y
                        drawRoundRect(
                            color = DarkroomColors.Amber,
                            topLeft = Offset(cx - w / 2f, cy - h / 2f),
                            size = Size(w, h),
                            cornerRadius = CornerRadius(corner * pose.scale * 4f),
                            alpha = alpha * pose.alpha,
                        )
                    }
                }
            },
    )
}

/**
 * 过阈值后照片描边：上滑琥珀 55%（未过 20%）、下滑相纸白 8%（未过 6%）。
 * 画在色调层外，透明度乘上照片当前亮度（等同于描边跟着照片一起压暗）。
 * 描边贴着照片本身：没铺满时只框照片、不框环境光，照片是直角所以描边也是直角。
 */
private fun Modifier.liftRim(state: ReviewStageState, photo: PhotoItem?, brightness: () -> Float): Modifier = drawWithCache {
    val stroke = Stroke(width = 1.dp.toPx())
    val fit = ReviewGestureMath.fitSize(photo?.width, photo?.height, size.width, size.height)
    val fullBleed = fit.width >= size.width - 1f && fit.height >= size.height - 1f
    val corner = if (fullBleed) LIFT_CORNER.toPx() else 0f
    val topLeft = Offset((size.width - fit.width) / 2f, (size.height - fit.height) / 2f)
    onDrawWithContent {
        drawContent()
        val dy = state.dy
        if (dy == 0f || state.axis != DragAxis.Vertical) return@onDrawWithContent
        val lift = ReviewGestureMath.cueProgress(dy, state.thresholds)
        val past = state.pastAnim.value
        val color = if (dy < 0f) DarkroomColors.Amber.copy(alpha = 0.2f + 0.35f * past)
        else DarkroomColors.Paper.copy(alpha = 0.06f + 0.02f * past)
        drawRoundRect(color = color, topLeft = topLeft, size = fit, cornerRadius = CornerRadius(corner * lift), style = stroke, alpha = lift * brightness().coerceIn(0f, 1f))
    }
}

/**
 * 连续压暗 / 去色：graphicsLayer 的 colorFilter，只在 layer 属性阶段读值（拖动不触发重组、不重录内容）。
 * 照片在离屏层里只画一次，之后每帧只按新色调合成；恒等时去掉 colorFilter，不占离屏层。
 * （原先每帧全屏 saveLayer 重画整张大图，1440p 下拖动 / 离场 / 翻页时同时两三层，是 GPU 的主要开销。）
 */
internal fun Modifier.tone(saturation: () -> Float, brightness: () -> Float): Modifier = graphicsLayer {
    val s = saturation()
    val b = brightness()
    // 每帧新矩阵：Compose 的 ColorFilter 按矩阵内容判等，复用同一个数组会让 layer 认为「没变」而不更新
    colorFilter = if (s >= 0.999f && b >= 0.999f) null else ColorFilter.colorMatrix(ColorMatrix(DevelopMatrix.compose(b, 1f, s)))
}

/** .lift 圆角 32dp（随拖动距离出现）。 */
internal val LIFT_CORNER = 32.dp
private const val UP_GLOW_PRE = 0.38f
private const val DOWN_VIGNETTE_PRE = 0.5f
private val AFTERIMAGES = listOf(50f / 250f to 0.4f, 100f / 250f to 0.18f)
/** 教学期间照片压暗到 32%、饱和 0.6。 */
private const val TUTORIAL_BRIGHTNESS = 0.32f
private const val TUTORIAL_SATURATION = 0.6f
private const val AMBIENT_SCALE = 1.2f
private const val AMBIENT_BRIGHTNESS = 0.4f
private const val AMBIENT_SATURATION = 1.3f
private const val AMBIENT_SIZE = 96
private val AMBIENT_BLUR = 36.dp
