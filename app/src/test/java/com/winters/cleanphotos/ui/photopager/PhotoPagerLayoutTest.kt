package com.winters.cleanphotos.ui.photopager

import android.net.Uri
import androidx.compose.ui.unit.IntSize
import com.winters.cleanphotos.domain.model.PhotoItem
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

/**
 * 共享横向预览布局的单元测试。
 *
 * 验证 buildPhotoPagerPages 的平面轮播布局规则：
 * - 页数量随索引位置变化
 * - 当前页和相邻页的参数分配
 * - 横滑轮播过渡参数（pageScale、pageAlpha、zIndex）
 * - 切页位置连续性
 * - 任意图片比例不影响轮播参数
 */
class PhotoPagerLayoutTest {

    private val screenWidthPx = 1080
    private val screenHeightPx = 1920

    private lateinit var testPhotos: List<PhotoItem>

    @Before
    fun setUp() {
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } returns mockk<Uri>(relaxed = true)

        testPhotos = (1L..5L).map { id ->
            PhotoItem(
                id = id,
                uri = Uri.parse("content://media/external/photo/$id"),
                displayName = "photo_$id",
                dateTaken = id * 1000L,
                dateGroup = "2024-01-01",
                size = 1024L,
                width = 1920,
                height = 1080
            )
        }
    }

    @After
    fun tearDown() {
        unmockkStatic(Uri::class)
    }

    private fun createPhoto(
        id: Long,
        width: Int?,
        height: Int?
    ): PhotoItem {
        return PhotoItem(
            id = id,
            uri = Uri.parse("content://media/external/photo/$id"),
            displayName = "photo_$id",
            dateTaken = id * 1000L,
            dateGroup = "2024-01-01",
            size = 1024L,
            width = width,
            height = height
        )
    }

    // ===== 边界测试 =====

    @Test
    fun `第一张右滑回弹 - 不生成 Previous 页`() {
        val pages = buildPhotoPagerPages(
            photos = testPhotos,
            currentIndex = 0,
            offsetX = 100,
            offsetY = 0,
            scale = 1f,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx
        )

        assertEquals("第一张应生成 2 页", 2, pages.size)
        assertFalse("不应有 Previous 页", pages.any { it.role == PhotoPagerRole.Previous })
        assertTrue("应有 Next 页", pages.any { it.role == PhotoPagerRole.Next })
    }

    @Test
    fun `最后一张左滑回弹 - 不生成 Next 页`() {
        val pages = buildPhotoPagerPages(
            photos = testPhotos,
            currentIndex = testPhotos.lastIndex,
            offsetX = -100,
            offsetY = 0,
            scale = 1f,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx
        )

        assertEquals("最后一张应生成 2 页", 2, pages.size)
        assertTrue("应有 Previous 页", pages.any { it.role == PhotoPagerRole.Previous })
        assertFalse("不应有 Next 页", pages.any { it.role == PhotoPagerRole.Next })
    }

    @Test
    fun `空列表返回空页`() {
        val pages = buildPhotoPagerPages(
            photos = emptyList(),
            currentIndex = 0,
            offsetX = 0,
            offsetY = 0,
            scale = 1f,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx
        )

        assertTrue("空列表应返回空页", pages.isEmpty())
    }

    @Test
    fun `越界索引返回空页`() {
        val pages = buildPhotoPagerPages(
            photos = testPhotos,
            currentIndex = 10,
            offsetX = 0,
            offsetY = 0,
            scale = 1f,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx
        )

        assertTrue("越界索引应返回空页", pages.isEmpty())
    }

    // ===== 基础偏移测试 =====

    @Test
    fun `中间照片左滑到下一张 - 三页完整且偏移正确`() {
        val offsetX = -300
        val pages = buildPhotoPagerPages(
            photos = testPhotos,
            currentIndex = 2,
            offsetX = offsetX,
            offsetY = 0,
            scale = 1f,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx
        )

        assertEquals("中间张应生成 3 页", 3, pages.size)

        val currentPage = pages.first { it.role == PhotoPagerRole.Current }
        val previousPage = pages.first { it.role == PhotoPagerRole.Previous }
        val nextPage = pages.first { it.role == PhotoPagerRole.Next }

        assertEquals(offsetX, currentPage.horizontalOffsetPx)
        assertEquals(-screenWidthPx + offsetX, previousPage.horizontalOffsetPx)
        assertEquals(screenWidthPx + offsetX, nextPage.horizontalOffsetPx)
    }

    @Test
    fun `中间照片右滑回上一张 - 偏移方向正确`() {
        val offsetX = 200
        val pages = buildPhotoPagerPages(
            photos = testPhotos,
            currentIndex = 2,
            offsetX = offsetX,
            offsetY = 0,
            scale = 1f,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx
        )

        val currentPage = pages.first { it.role == PhotoPagerRole.Current }
        val previousPage = pages.first { it.role == PhotoPagerRole.Previous }

        assertEquals(offsetX, currentPage.horizontalOffsetPx)
        assertEquals(-screenWidthPx + offsetX, previousPage.horizontalOffsetPx)
    }

    // ===== 页身份与基础属性 =====

    @Test
    fun `页身份按 photo id 稳定 - 索引变化时同一张照片保持同一对象`() {
        val pages1 = buildPhotoPagerPages(
            photos = testPhotos,
            currentIndex = 1,
            offsetX = 0,
            offsetY = 0,
            scale = 1f,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx
        )
        val currentAt1 = pages1.first { it.role == PhotoPagerRole.Current }
        assertEquals(2L, currentAt1.photo.id)

        val pages2 = buildPhotoPagerPages(
            photos = testPhotos,
            currentIndex = 2,
            offsetX = 0,
            offsetY = 0,
            scale = 1f,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx
        )
        val previousAt2 = pages2.first { it.role == PhotoPagerRole.Previous }
        assertEquals(2L, previousAt2.photo.id)

        assertEquals(currentAt1.photo, previousAt2.photo)
    }

    @Test
    fun `当前页始终 reportLoadFailure 为 true`() {
        for (index in testPhotos.indices) {
            val pages = buildPhotoPagerPages(
                photos = testPhotos,
                currentIndex = index,
                offsetX = 0,
                offsetY = 0,
                scale = 1f,
                screenWidthPx = screenWidthPx,
                screenHeightPx = screenHeightPx
            )
            val currentPage = pages.first { it.role == PhotoPagerRole.Current }
            assertTrue(
                "索引 $index 的当前页 reportLoadFailure 应为 true",
                currentPage.reportLoadFailure
            )
        }
    }

    @Test
    fun `相邻页始终 reportLoadFailure 为 false`() {
        val pages = buildPhotoPagerPages(
            photos = testPhotos,
            currentIndex = 2,
            offsetX = 0,
            offsetY = 0,
            scale = 1f,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx
        )

        val adjacentPages = pages.filter { it.role != PhotoPagerRole.Current }
        assertTrue("相邻页应存在", adjacentPages.isNotEmpty())
        adjacentPages.forEach { page ->
            assertFalse(
                "角色 ${page.role} 的 reportLoadFailure 应为 false",
                page.reportLoadFailure
            )
        }
    }

    // ===== 轮播过渡参数测试 =====

    @Test
    fun `左滑中段生成 Current 和 Next 的轮播参数`() {
        val offsetX = -screenWidthPx / 2
        val pages = buildPhotoPagerPages(
            photos = testPhotos,
            currentIndex = 2,
            offsetX = offsetX,
            offsetY = 0,
            scale = 1f,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx
        )

        val currentPage = pages.first { it.role == PhotoPagerRole.Current }
        val previousPage = pages.first { it.role == PhotoPagerRole.Previous }
        val nextPage = pages.first { it.role == PhotoPagerRole.Next }

        // 当前页向左移
        assertTrue("当前页应向左偏移", currentPage.horizontalOffsetPx < 0)
        // 下一页从右侧进入
        assertTrue("下一页应在屏幕右半侧", nextPage.horizontalOffsetPx > 0)

        // 当前页 scale 轻微缩小
        assertTrue("当前页 scale 应小于 1", currentPage.pageScale < 1f)
        assertEquals(0.98f, currentPage.pageScale, 0.001f)

        // 进入页（Next）scale 从 0.96 开始恢复
        assertTrue("进入页 scale 应大于 0.96", nextPage.pageScale > 0.96f)
        assertEquals(0.98f, nextPage.pageScale, 0.001f)

        // 当前页 alpha 轻微降低
        assertTrue("当前页 alpha 应小于 1", currentPage.pageAlpha < 1f)
        assertEquals(0.96f, currentPage.pageAlpha, 0.001f)

        // 进入页 alpha 从 0.92 开始恢复
        assertTrue("进入页 alpha 应大于 0.92", nextPage.pageAlpha > 0.92f)
        assertEquals(0.96f, nextPage.pageAlpha, 0.001f)

        // 上一页（非进入页）不参与过渡
        assertEquals("非进入页 scale 应为 1", 1f, previousPage.pageScale, 0.001f)
        assertEquals("非进入页 alpha 应为 1", 1f, previousPage.pageAlpha, 0.001f)

        // 进入页 zIndex 最高
        assertTrue("进入页 zIndex 应高于当前页", nextPage.zIndex > currentPage.zIndex)
        assertEquals(3f, nextPage.zIndex, 0.001f)
        assertEquals(2f, currentPage.zIndex, 0.001f)
    }

    @Test
    fun `右滑中段生成 Current 和 Previous 的轮播参数`() {
        val offsetX = screenWidthPx / 2
        val pages = buildPhotoPagerPages(
            photos = testPhotos,
            currentIndex = 2,
            offsetX = offsetX,
            offsetY = 0,
            scale = 1f,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx
        )

        val currentPage = pages.first { it.role == PhotoPagerRole.Current }
        val previousPage = pages.first { it.role == PhotoPagerRole.Previous }
        val nextPage = pages.first { it.role == PhotoPagerRole.Next }

        // 当前页向右移
        assertTrue("当前页应向右偏移", currentPage.horizontalOffsetPx > 0)
        // 上一页从左侧进入
        assertTrue("上一页应在屏幕左半侧", previousPage.horizontalOffsetPx < 0)

        // 参数与左滑对称
        assertEquals(0.98f, currentPage.pageScale, 0.001f)
        assertEquals(0.98f, previousPage.pageScale, 0.001f)
        assertEquals(0.96f, currentPage.pageAlpha, 0.001f)
        assertEquals(0.96f, previousPage.pageAlpha, 0.001f)

        // 下一页（非进入页）不参与过渡
        assertEquals(1f, nextPage.pageScale, 0.001f)
        assertEquals(1f, nextPage.pageAlpha, 0.001f)

        // 进入页 zIndex 最高
        assertTrue("进入页 zIndex 应高于当前页", previousPage.zIndex > currentPage.zIndex)
    }

    @Test
    fun `静止态没有轮播变换`() {
        val pages = buildPhotoPagerPages(
            photos = testPhotos,
            currentIndex = 2,
            offsetX = 0,
            offsetY = 0,
            scale = 1f,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx
        )

        pages.forEach { page ->
            assertEquals("静止态 pageScale 应为 1", 1f, page.pageScale, 0.001f)
            assertEquals("静止态 pageAlpha 应为 1", 1f, page.pageAlpha, 0.001f)
            assertEquals("静止态 pageParallaxOffsetPx 应为 0", 0, page.pageParallaxOffsetPx)
        }
    }

    @Test
    fun `切页完成态目标页位置连续 - 左滑`() {
        val beforeSwitch = buildPhotoPagerPages(
            photos = testPhotos,
            currentIndex = 1,
            offsetX = -screenWidthPx,
            offsetY = 0,
            scale = 1f,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx
        )
        val afterSwitch = buildPhotoPagerPages(
            photos = testPhotos,
            currentIndex = 2,
            offsetX = 0,
            offsetY = 0,
            scale = 1f,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx
        )

        val targetPhotoBefore = beforeSwitch.first { it.role == PhotoPagerRole.Next }
        val currentPhotoAfter = afterSwitch.first { it.role == PhotoPagerRole.Current }

        assertEquals("切页前后应是同一张目标照片", targetPhotoBefore.photo.id, currentPhotoAfter.photo.id)
        // parallax 在 progress=1 时使用 4*1*(1-1)=0 的曲线，确保位置连续
        assertEquals(
            "切页完成瞬间目标照片应保持在同一屏幕位置",
            targetPhotoBefore.horizontalOffsetPx,
            currentPhotoAfter.horizontalOffsetPx
        )
    }

    @Test
    fun `切页完成态目标页位置连续 - 右滑`() {
        val beforeSwitch = buildPhotoPagerPages(
            photos = testPhotos,
            currentIndex = 2,
            offsetX = screenWidthPx,
            offsetY = 0,
            scale = 1f,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx
        )
        val afterSwitch = buildPhotoPagerPages(
            photos = testPhotos,
            currentIndex = 1,
            offsetX = 0,
            offsetY = 0,
            scale = 1f,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx
        )

        val targetPhotoBefore = beforeSwitch.first { it.role == PhotoPagerRole.Previous }
        val currentPhotoAfter = afterSwitch.first { it.role == PhotoPagerRole.Current }

        assertEquals("切页前后应是同一张目标照片", targetPhotoBefore.photo.id, currentPhotoAfter.photo.id)
        assertEquals(
            "切页完成瞬间目标照片应保持在同一屏幕位置",
            targetPhotoBefore.horizontalOffsetPx,
            currentPhotoAfter.horizontalOffsetPx
        )
    }

    @Test
    fun `任意图片比例不影响轮播参数`() {
        // 横图
        val landscapePhotos = listOf(
            createPhoto(id = 1L, width = 2500, height = 1000),
            createPhoto(id = 2L, width = 3000, height = 1000),
            createPhoto(id = 3L, width = 2000, height = 800)
        )
        // 竖图
        val portraitPhotos = listOf(
            createPhoto(id = 1L, width = 1000, height = 2500),
            createPhoto(id = 2L, width = 800, height = 2000),
            createPhoto(id = 3L, width = 600, height = 1800)
        )
        // 方图
        val squarePhotos = listOf(
            createPhoto(id = 1L, width = 1000, height = 1000),
            createPhoto(id = 2L, width = 2000, height = 2000),
            createPhoto(id = 3L, width = 800, height = 800)
        )
        // null 尺寸
        val nullSizePhotos = listOf(
            createPhoto(id = 1L, width = null, height = null),
            createPhoto(id = 2L, width = null, height = null),
            createPhoto(id = 3L, width = null, height = null)
        )

        val allPhotoSets = listOf(landscapePhotos, portraitPhotos, squarePhotos, nullSizePhotos)
        val offsetX = -screenWidthPx / 2

        for (photos in allPhotoSets) {
            val pages = buildPhotoPagerPages(
                photos = photos,
                currentIndex = 1,
                offsetX = offsetX,
                offsetY = 0,
                scale = 1f,
                screenWidthPx = screenWidthPx,
                screenHeightPx = screenHeightPx
            )

            val currentPage = pages.first { it.role == PhotoPagerRole.Current }
            val nextPage = pages.first { it.role == PhotoPagerRole.Next }

            // 轮播参数完全由 offset 决定，不依赖图片宽高比
            assertEquals(offsetX, currentPage.horizontalOffsetPx)
            assertEquals(screenWidthPx + offsetX, nextPage.horizontalOffsetPx)
            assertEquals(0.98f, currentPage.pageScale, 0.001f)
            assertEquals(0.98f, nextPage.pageScale, 0.001f)
        }
    }

    // ===== 连续帧和过渡计算 =====

    @Test
    fun `相邻连续帧中同一张当前照片的位置差异不超过1像素`() {
        val firstFrame = buildPhotoPagerPages(
            photos = testPhotos,
            currentIndex = 2,
            offsetX = -320,
            offsetY = 0,
            scale = 1f,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx
        )
        val secondFrame = buildPhotoPagerPages(
            photos = testPhotos,
            currentIndex = 2,
            offsetX = -321,
            offsetY = 0,
            scale = 1f,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx
        )

        val currentAtFirstFrame = firstFrame.first { it.role == PhotoPagerRole.Current }
        val currentAtSecondFrame = secondFrame.first { it.role == PhotoPagerRole.Current }

        assertEquals(
            "连续帧中当前照片的像素位置差异应不超过 1",
            1,
            abs(currentAtFirstFrame.horizontalOffsetPx - currentAtSecondFrame.horizontalOffsetPx)
        )
    }

    @Test
    fun `过渡进度计算正确`() {
        assertEquals("静止态进度为 0", 0f, calculatePhotoPagerTransitionProgress(0, screenWidthPx), 0.0001f)
        assertEquals("半屏进度为 0.5", 0.5f, calculatePhotoPagerTransitionProgress(-screenWidthPx / 2, screenWidthPx), 0.0001f)
        assertEquals("全屏进度为 1", 1f, calculatePhotoPagerTransitionProgress(-screenWidthPx, screenWidthPx), 0.0001f)
        assertEquals("屏幕宽度为 0 时进度为 0", 0f, calculatePhotoPagerTransitionProgress(-100, 0), 0.0001f)
        assertEquals("进度不超 1", 1f, calculatePhotoPagerTransitionProgress(-screenWidthPx * 2, screenWidthPx), 0.0001f)
    }

    // ===== 图片请求键稳定性 =====

    @Test
    fun `同一照片会生成稳定的图片请求键`() {
        val photo = createPhoto(id = 41L, width = 1000, height = 2500)
        val samePhotoCopy = photo.copy()
        val anotherPhoto = createPhoto(id = 42L, width = 1000, height = 2500)

        assertEquals(
            "同一照片即使经过 copy 也应生成同一请求键",
            buildPhotoPagerImageRequestKey(photo),
            buildPhotoPagerImageRequestKey(samePhotoCopy)
        )
        assertTrue(
            "不同照片应生成不同请求键",
            buildPhotoPagerImageRequestKey(photo) != buildPhotoPagerImageRequestKey(anotherPhoto)
        )
    }

    // ===== 垂直滑动背景页测试 =====

    @Test
    fun `垂直滑动中段生成 Background 页在中心位置`() {
        val offsetY = -300
        val pages = buildPhotoPagerPages(
            photos = testPhotos,
            currentIndex = 2,
            offsetX = 0,
            offsetY = offsetY,
            scale = 0.98f,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx
        )

        val backgroundPage = pages.firstOrNull { it.role == PhotoPagerRole.Background }
        assertNotNull("垂直滑动时应生成 Background 页", backgroundPage)
        assertEquals("Background 页应在水平中心", 0, backgroundPage!!.horizontalOffsetPx)
        assertEquals("Background 页垂直偏移应为 0", 0, backgroundPage.verticalOffsetPx)
        assertEquals("Background 页 zIndex 应为 0", 0f, backgroundPage.zIndex, 0.001f)
        assertTrue("Background 页 alpha 应大于 0", backgroundPage.pageAlpha > 0f)
        assertTrue("Background 页 alpha 应小于 1", backgroundPage.pageAlpha < 1f)
        assertFalse("Background 页 reportLoadFailure 应为 false", backgroundPage.reportLoadFailure)
    }

    @Test
    fun `垂直滑动 Background 页显示下一张照片`() {
        val offsetY = -300
        val pages = buildPhotoPagerPages(
            photos = testPhotos,
            currentIndex = 2,
            offsetX = 0,
            offsetY = offsetY,
            scale = 0.98f,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx
        )

        val backgroundPage = pages.first { it.role == PhotoPagerRole.Background }
        // nextIndex = 3，替代照片为 photos[3]
        assertEquals(testPhotos[3].id, backgroundPage.photo.id)
    }

    @Test
    fun `垂直滑动到最后一张时 Background 显示上一张`() {
        val offsetY = 300
        val pages = buildPhotoPagerPages(
            photos = testPhotos,
            currentIndex = testPhotos.lastIndex,
            offsetX = 0,
            offsetY = offsetY,
            scale = 0.98f,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx
        )

        val backgroundPage = pages.first { it.role == PhotoPagerRole.Background }
        // 最后一张无 next，替代为 previous（index - 1）
        assertEquals(testPhotos[testPhotos.lastIndex - 1].id, backgroundPage.photo.id)
    }

    @Test
    fun `只有一张照片时垂直滑动不生成 Background 页`() {
        val singlePhoto = listOf(testPhotos.first())
        val offsetY = -300
        val pages = buildPhotoPagerPages(
            photos = singlePhoto,
            currentIndex = 0,
            offsetX = 0,
            offsetY = offsetY,
            scale = 0.98f,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx
        )

        assertFalse("只有一张时不应有 Background 页", pages.any { it.role == PhotoPagerRole.Background })
    }

    @Test
    fun `水平滑动时不生成 Background 页`() {
        val offsetX = -300
        val pages = buildPhotoPagerPages(
            photos = testPhotos,
            currentIndex = 2,
            offsetX = offsetX,
            offsetY = 0,
            scale = 1f,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx
        )

        assertFalse("水平滑动时不应有 Background 页", pages.any { it.role == PhotoPagerRole.Background })
    }

    @Test
    fun `静止态不生成 Background 页`() {
        val pages = buildPhotoPagerPages(
            photos = testPhotos,
            currentIndex = 2,
            offsetX = 0,
            offsetY = 0,
            scale = 1f,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx
        )

        assertFalse("静止态不应有 Background 页", pages.any { it.role == PhotoPagerRole.Background })
    }

    @Test
    fun `Background 页 alpha 随垂直进度线性增长`() {
        val halfScreenOffset = -screenHeightPx / 2
        val pages = buildPhotoPagerPages(
            photos = testPhotos,
            currentIndex = 2,
            offsetX = 0,
            offsetY = halfScreenOffset,
            scale = 0.95f,
            screenWidthPx = screenWidthPx,
            screenHeightPx = screenHeightPx
        )

        val backgroundPage = pages.first { it.role == PhotoPagerRole.Background }
        assertEquals(0.5f, backgroundPage.pageAlpha, 0.01f)
    }
}
