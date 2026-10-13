package com.winters.cleanphotos.ui.photopager

import androidx.compose.ui.unit.IntSize
import com.winters.cleanphotos.domain.model.PhotoItem
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pager 页面角色。
 *
 * Previous：当前照片的上一张，渲染在屏幕左侧。
 * Current：当前正在查看的照片，渲染在屏幕中间。
 * Next：当前照片的下一张，渲染在屏幕右侧。
 */
enum class PhotoPagerRole {
    Previous, Current, Next, Background
}

/**
 * 单个 Pager 页面的布局描述。
 *
 * 把"上一张/当前张/下一张"的渲染参数从 Composable 条件分支中抽离出来，
 * 形成纯 Kotlin 数据模型。渲染时用 key(photo.id) 保证页身份稳定，
 * 避免照片从侧页切到中间页时被 Compose 当成新节点重新加载。
 *
 * @param photo 该页对应的照片
 * @param role 页面角色（Previous / Current / Next）
 * @param horizontalOffsetPx 页面容器的主水平偏移量（像素）
 * @param verticalOffsetPx 垂直偏移量（像素），只有 Current 页会携带垂直手势偏移
 * @param scale 缩放比例，只有 Current 页会携带缩放状态（垂直上下滑/zoom 用）
 * @param zoomOffsetX zoom 平移 X，只有 Current 页使用
 * @param zoomOffsetY zoom 平移 Y，只有 Current 页使用
 * @param reportLoadFailure 是否上报加载失败。只有 Current 页为 true
 * @param zIndex 渲染层级，用于控制横滑中页面叠放顺序
 * @param pageScale 页面容器缩放，用于横滑轮播过渡，静止态必须回到 1.0f
 * @param pageAlpha 页面容器透明度，用于横滑轮播过渡，静止态必须回到 1.0f
 * @param pageParallaxOffsetPx 进入页视差偏移，用于横滑轮播过渡，静止态必须为 0
 */
data class PhotoPagerPage(
    val photo: PhotoItem,
    val role: PhotoPagerRole,
    val horizontalOffsetPx: Int,
    val verticalOffsetPx: Int,
    val scale: Float,
    val zoomOffsetX: Float,
    val zoomOffsetY: Float,
    val reportLoadFailure: Boolean,
    val zIndex: Float,
    val pageScale: Float = 1f,
    val pageAlpha: Float = 1f,
    val pageParallaxOffsetPx: Int = 0
)

/**
 * 计算横滑轮播过渡进度。
 *
 * progress 表示"页面切换完成度"：
 * - 静止态 progress = 0
 * - 拖到一半 progress ≈ 0.5
 * - 切页完成 progress = 1
 *
 * @param offsetX 当前水平滑动偏移量（像素）
 * @param screenWidthPx 屏幕宽度（像素）
 * @return 0f..1f 的过渡进度
 */
internal fun calculatePhotoPagerTransitionProgress(
    offsetX: Int,
    screenWidthPx: Int
): Float {
    if (screenWidthPx <= 0) return 0f
    return (abs(offsetX).toFloat() / screenWidthPx.toFloat()).coerceIn(0f, 1f)
}

/**
 * 根据当前照片列表和滑动状态，构建最多三页的布局描述。
 *
 * 平面轮播风格：
 * - 当前页跟随手指向左/右滑出，目标页从相反方向同步滑入
 * - 拖动中两张照片同时可见
 * - pageScale/pageAlpha 随进度轻微变化，静止态恢复 1.0f
 * - 进入页 zIndex 高于当前页，确保不被遮挡
 *
 * @param photos 完整照片列表
 * @param currentIndex 当前显示的照片索引
 * @param offsetX 当前水平滑动偏移量（像素）
 * @param offsetY 当前垂直滑动偏移量（像素）
 * @param scale 当前缩放比例
 * @param screenSizePx 视口尺寸（像素）
 * @return 最多三个 PhotoPagerPage 实例
 */
fun buildPhotoPagerPages(
    photos: List<PhotoItem>,
    currentIndex: Int,
    offsetX: Int,
    offsetY: Int,
    scale: Float,
    zoomOffsetX: Float = 0f,
    zoomOffsetY: Float = 0f,
    screenSizePx: IntSize,
    backgroundPhotoOverride: PhotoItem? = null
): List<PhotoPagerPage> {
    if (photos.isEmpty() || currentIndex !in photos.indices) return emptyList()

    val pages = mutableListOf<PhotoPagerPage>()
    val screenWidthPx = screenSizePx.width
    val prevIndex = currentIndex - 1
    val nextIndex = currentIndex + 1

    // 计算横滑过渡进度和视觉参数
    val progress = calculatePhotoPagerTransitionProgress(offsetX, screenWidthPx)
    val currentPageScale = 1f - 0.04f * progress
    val enteringPageScale = 0.96f + 0.04f * progress
    val currentPageAlpha = 1f - 0.08f * progress
    val enteringPageAlpha = 0.92f + 0.08f * progress

    // 视差补偿：使用 4p(1-p) 曲线，在进度 0 和 1 处归零，保证切页位置连续
    // 中段最大偏移约为 screenWidth * 0.02，足够增强深度感但不破坏无缝衔接
    val parallaxCurve = 4f * progress * (1f - progress)
    val parallaxOffsetPx = (screenWidthPx * 0.08f * parallaxCurve).roundToInt()

    // 判断进入页方向
    val enteringIsNext = offsetX < 0 // 左滑时下一页是进入页
    val enteringIsPrevious = offsetX > 0 // 右滑时上一页是进入页

    // 上一张页：在当前页左侧
    if (prevIndex >= 0) {
        val isEntering = enteringIsPrevious
        pages.add(
            PhotoPagerPage(
                photo = photos[prevIndex],
                role = PhotoPagerRole.Previous,
                horizontalOffsetPx = -screenWidthPx + offsetX,
                verticalOffsetPx = 0,
                scale = 1f,
                zoomOffsetX = 0f,
                zoomOffsetY = 0f,
                reportLoadFailure = false,
                zIndex = if (isEntering) 3f else 1f,
                pageScale = if (isEntering) enteringPageScale else 1f,
                pageAlpha = if (isEntering) enteringPageAlpha else 1f,
                pageParallaxOffsetPx = if (isEntering) parallaxOffsetPx else 0
            )
        )
    }

    // 当前页始终存在
    pages.add(
        PhotoPagerPage(
            photo = photos[currentIndex],
            role = PhotoPagerRole.Current,
            horizontalOffsetPx = offsetX,
            verticalOffsetPx = offsetY,
            scale = scale,
            zoomOffsetX = zoomOffsetX,
            zoomOffsetY = zoomOffsetY,
            reportLoadFailure = true,
            zIndex = 2f,
            pageScale = if (offsetX != 0) currentPageScale else 1f,
            pageAlpha = if (offsetX != 0) currentPageAlpha else 1f,
            pageParallaxOffsetPx = 0
        )
    )

    // 下一张页：在当前页右侧
    if (nextIndex < photos.size) {
        val isEntering = enteringIsNext
        pages.add(
            PhotoPagerPage(
                photo = photos[nextIndex],
                role = PhotoPagerRole.Next,
                horizontalOffsetPx = screenWidthPx + offsetX,
                verticalOffsetPx = 0,
                scale = 1f,
                zoomOffsetX = 0f,
                zoomOffsetY = 0f,
                reportLoadFailure = false,
                zIndex = if (isEntering) 3f else 1f,
                pageScale = if (isEntering) enteringPageScale else 1f,
                pageAlpha = if (isEntering) enteringPageAlpha else 1f,
                pageParallaxOffsetPx = if (isEntering) -parallaxOffsetPx else 0
            )
        )
    }

    // 垂直滑动时在当前页背后预渲染替代照片，Current 页飞离后立即可见，避免黑屏
    // backgroundPhotoOverride 用于在 ViewModel 更新列表期间锁定正确的替代照片，防止闪烁
    if (offsetY != 0 && offsetX == 0) {
        val verticalProgress = (abs(offsetY).toFloat() / screenSizePx.height).coerceIn(0f, 1f)
        // 确定替代照片：正常取下一张（移除后 index 不变），最后一张取上一张
        val replacementIndex = when {
            nextIndex < photos.size -> nextIndex
            prevIndex >= 0 -> prevIndex
            else -> -1
        }
        // 优先使用覆盖照片，避免 ViewModel 更新列表后 Background 指向错误照片
        // 当 backgroundPhotoOverride 有值时跳过 index 检查（覆盖照片已锁定，不受列表变化影响）
        val bgPhoto = backgroundPhotoOverride ?: photos.getOrNull(replacementIndex)
        val shouldRenderBackground = bgPhoto != null &&
            (backgroundPhotoOverride != null || (replacementIndex >= 0 && replacementIndex != currentIndex))
        if (shouldRenderBackground) {
            pages.add(
                PhotoPagerPage(
                    photo = bgPhoto,
                    role = PhotoPagerRole.Background,
                    horizontalOffsetPx = 0,
                    verticalOffsetPx = 0,
                    scale = 1f,
                    zoomOffsetX = 0f,
                    zoomOffsetY = 0f,
                    reportLoadFailure = false,
                    zIndex = 0f,
                    pageScale = 1f,
                    pageAlpha = verticalProgress
                )
            )
        }
    }

    return pages
}

/**
 * 兼容旧测试与过渡调用方的重载。
 */
fun buildPhotoPagerPages(
    photos: List<PhotoItem>,
    currentIndex: Int,
    offsetX: Int,
    offsetY: Int,
    scale: Float,
    screenWidthPx: Int,
    screenHeightPx: Int = screenWidthPx
): List<PhotoPagerPage> = buildPhotoPagerPages(
    photos = photos,
    currentIndex = currentIndex,
    offsetX = offsetX,
    offsetY = offsetY,
    scale = scale,
    zoomOffsetX = 0f,
    zoomOffsetY = 0f,
    screenSizePx = IntSize(screenWidthPx, screenHeightPx),
    backgroundPhotoOverride = null
)
