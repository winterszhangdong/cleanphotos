package com.winters.cleanphotos.feature.review

import android.net.Uri
import com.winters.cleanphotos.TestPhotoData
import com.winters.cleanphotos.domain.model.ReviewPage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import androidx.compose.ui.geometry.Offset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 自绘三页舞台的相邻页、跨天触感与离场起点。
 * 接替原 ReviewPagerLayoutTest（三页生成 / 首尾 / 越界）、SwitchReviewPhotoUseCaseTest（左右切换目标）
 * 与 ReviewBandOffsetTest（离场第一帧位置不跳）所覆盖的行为。
 */
class ReviewStageStateTest {
    private lateinit var scope: CoroutineScope

    @Before fun setUp() {
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers { mockk(relaxed = true) }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After fun tearDown() {
        scope.cancel()
        unmockkStatic(Uri::class)
    }

    private fun photo(id: Long, day: String = "2024-05-20") = ReviewPage.Photo(TestPhotoData.createTestPhoto(id = id, dateGroup = day))

    private fun stage(pages: List<ReviewPage>, current: String?) = ReviewStageState(scope).apply {
        this.pages = pages
        currentKey = current
    }

    private val three = listOf(photo(1), photo(2), photo(3))

    @Test fun `第一张_只有当前页和下一页`() {
        val s = stage(three, "p1")
        assertNull(s.previousPage)
        assertEquals("p1", s.currentPage?.key)
        assertEquals("p2", s.nextPage?.key)
    }

    @Test fun `最后一张_只有上一页和当前页`() {
        val s = stage(three, "p3")
        assertEquals("p2", s.previousPage?.key)
        assertNull(s.nextPage)
    }

    @Test fun `中间张_完整三页`() {
        val s = stage(three, "p2")
        assertEquals("p1", s.previousPage?.key)
        assertEquals("p3", s.nextPage?.key)
    }

    @Test fun `空序列_没有任何页`() {
        val s = stage(emptyList(), null)
        assertNull(s.currentPage); assertNull(s.previousPage); assertNull(s.nextPage)
    }

    @Test fun `key 不在序列里_没有相邻页`() {
        val s = stage(three, "p99")
        assertNull(s.currentPage); assertNull(s.previousPage); assertNull(s.nextPage)
    }

    @Test fun `上下拖动垫底页_有下一页用下一页_最后一张用上一页`() {
        assertEquals("p3", stage(three, "p2").beneathPage?.key)
        assertEquals("p2", stage(three, "p3").beneathPage?.key)
        assertNull(stage(listOf(photo(1)), "p1").beneathPage)
    }

    @Test fun `跨天 CLOCK_TICK 每次跨天恰好一次_同一天不触发_初始不触发`() {
        val pages = listOf(photo(1, "d1"), photo(2, "d1"), photo(3, "d2"), photo(4, "d3"))
        val s = stage(pages, "p1")
        var ticks = 0
        s.onDayChanged = { ticks++ }
        s.noteCurrentDay(tick = true) // 进入会话第一张
        assertEquals(0, ticks)
        s.currentKey = "p2"; s.noteCurrentDay(true)
        assertEquals(0, ticks)
        s.currentKey = "p3"; s.noteCurrentDay(true)
        assertEquals(1, ticks)
        s.noteCurrentDay(true) // 同一页重复通知
        assertEquals(1, ticks)
        s.currentKey = "p4"; s.noteCurrentDay(true)
        assertEquals(2, ticks)
        s.currentKey = "p3"; s.noteCurrentDay(true) // 往回跨天
        assertEquals(3, ticks)
    }

    @Test fun `经过小结插页跨天_落到下一天照片时触发一次`() {
        val pages = listOf(photo(1, "d1"), ReviewPage.Summary("d1"), photo(2, "d2"))
        val s = stage(pages, "p1")
        var ticks = 0
        s.onDayChanged = { ticks++ }
        s.noteCurrentDay(true)
        s.currentKey = "sd1"; s.noteCurrentDay(true)
        assertEquals("小结页本身不触发", 0, ticks)
        s.currentKey = "p2"; s.noteCurrentDay(true)
        assertEquals(1, ticks)
    }

    @Test fun `撤回同步日期但不触发`() {
        val pages = listOf(photo(1, "d1"), photo(2, "d2"))
        val s = stage(pages, "p2")
        var ticks = 0
        s.onDayChanged = { ticks++ }
        s.noteCurrentDay(true)
        s.currentKey = "p1"; s.noteCurrentDay(tick = false)
        s.currentKey = "p2"; s.noteCurrentDay(true)
        assertEquals("撤回后再翻回另一天仍算一次跨天", 1, ticks)
    }

    private fun flight(kind: ExitKind, y: Float, scale: Float) = ExitFlight(
        id = 1, page = photo(1), kind = kind, startY = y, startScale = scale, startSaturation = 0.5f,
        startBrightness = 0.7f, velocityY = 0f, target = Offset(100f, 900f), rise = 40f,
    )

    @Test fun `离场第一帧就在松手位置_不跳`() {
        for (kind in ExitKind.entries) {
            val f = flight(kind, if (kind == ExitKind.Up) -320f else 320f, 0.9f)
            val p = ExitPath.pose(f, 0f)
            assertEquals(f.startY, p.y, 0.01f)
            assertEquals(0f, p.x, 0.01f)
            assertEquals(0.9f, p.scale, 0.001f)
            assertEquals(0.7f, p.brightness, 0.001f)
            assertEquals(1f, p.alpha, 0.001f)
        }
    }

    @Test fun `离场终点落到导航目标并缩到 0_05`() {
        for (kind in ExitKind.entries) {
            val f = flight(kind, -200f, 0.9f)
            val p = ExitPath.pose(f, 1f)
            assertEquals(900f, p.y, 0.5f)
            assertEquals(100f, p.x, 0.5f)
            assertEquals(ExitPath.EXIT_END_SCALE, p.scale, 0.001f)
            assertEquals(0f, p.alpha, 0.001f)
        }
    }

    @Test fun `收藏离场先顺惯性上冲再下落`() {
        val f = flight(ExitKind.Up, -300f, 0.9f)
        val atRise = ExitPath.pose(f, ExitPath.RISE_FRACTION)
        assertEquals(-340f, atRise.y, 0.5f)
        assertTrue(ExitPath.pose(f, 0.1f).y < f.startY)
    }

    @Test fun `撤回飞回终点在中央原尺寸_后半段才显影`() {
        val u = UndoFlight("p1", ExitKind.Down, Offset(300f, 800f), null)
        val end = ExitPath.undoPose(u, 1f)
        assertEquals(0f, end.x, 0.01f); assertEquals(0f, end.y, 0.01f)
        assertEquals(1f, end.scale, 0.001f); assertEquals(1f, end.brightness, 0.001f)
        assertEquals(ExitPath.TRASH_END_BRIGHTNESS, ExitPath.undoPose(u, 0.5f).brightness, 0.001f)
    }

    private val testThresholds = ReviewThresholds(
        vertical = 300f, verticalFlingVelocity = 2000f, minFlingDistance = 60f,
        horizontal = 200f, horizontalFlingVelocity = 1500f, effectCap = 450f, edgeLength = 300f,
    )

    /** 拖动开始会启动控件淡出动画；给一个永不出帧的时钟，动画挂起到 tearDown 取消，不往外抛异常。 */
    private val stalledClock = object : androidx.compose.runtime.MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R = kotlinx.coroutines.awaitCancellation()
    }

    private fun bounceStage(pages: List<ReviewPage>, current: String) =
        ReviewStageState(CoroutineScope(scope.coroutineContext + stalledClock)).apply {
            this.pages = pages
            currentKey = current
            thresholds = testThresholds
            width = 1080f; height = 2400f
        }

    @Test fun `小结页上下拖_只按阻尼位移_不改 dy_不触发操作`() {
        val pages = listOf(photo(1, "d1"), ReviewPage.Summary("d1"), photo(2, "d2"))
        val s = bounceStage(pages, ReviewPage.summaryKeyOf("d1"))
        var swiped = false
        s.onSwipe = { _, _ -> swiped = true; null }
        s.handler.onDown()
        s.handler.onDragStart(DragAxis.Vertical)
        repeat(10) { s.handler.onDrag(DragAxis.Vertical, Offset(0f, 60f)) }   // 手指下移 600px
        assertEquals(0f, s.dy)
        assertTrue("应有回弹位移", s.summaryBounce > 0f)
        assertTrue("阻尼后小于手指位移且不超过阻尼长度", s.summaryBounce < 300f)
        repeat(20) { s.handler.onDrag(DragAxis.Vertical, Offset(0f, -60f)) }  // 反向上拖同样有阻尼
        assertTrue(s.summaryBounce < 0f && s.summaryBounce > -300f)
        assertTrue(!swiped)
        assertEquals(ReviewPage.summaryKeyOf("d1"), s.currentKey)
    }

    @Test fun `小结页上下拖之后_左右翻页照常可用`() {
        val pages = listOf(photo(1, "d1"), ReviewPage.Summary("d1"), photo(2, "d2"))
        val s = bounceStage(pages, ReviewPage.summaryKeyOf("d1"))
        s.handler.onDown()
        s.handler.onDragStart(DragAxis.Vertical)
        s.handler.onDrag(DragAxis.Vertical, Offset(0f, 120f))
        // 不松手结算（回弹动画需要帧时钟），直接开始下一次横向手势：位移不受上一次回弹影响
        s.handler.onDown()
        s.handler.onDragStart(DragAxis.Horizontal)
        s.handler.onDrag(DragAxis.Horizontal, Offset(-150f, 0f))
        assertEquals(-150f, s.dx)
        assertEquals("p2", s.nextPage?.key)
    }

    @Test fun `小结页点下一天_走翻页去下一页`() {
        val pages = listOf(photo(1, "d1"), ReviewPage.Summary("d1"), photo(2, "d2"))
        val s = bounceStage(pages, ReviewPage.summaryKeyOf("d1"))
        s.goNext()
        s.handler.onDown()   // 翻页动画没帧时钟，按下直接落定到目标页
        assertEquals("p2", s.currentKey)
    }

    @Test fun `横向翻页弹簧未落定时钉住视频宿主_提前切页后钉还在`() {
        val s = bounceStage(three, "p2")
        s.activeVideoHostKey = "p2"
        s.goNext()
        assertEquals("p2", s.playbackPinnedKey)
        assertEquals("p2", s.currentKey)
        s.handler.onDown()
        assertEquals("p3", s.currentKey)
        assertEquals("p2", s.playbackPinnedKey)
    }

    @Test fun `不是视频宿主的横向翻页不钉播放器`() {
        val s = bounceStage(three, "p2")
        s.goNext()
        assertNull(s.playbackPinnedKey)
    }

    @Test fun `上下拖过阈值时落下第二根手指_放弃这次拖动_不触发操作`() {
        val s = bounceStage(three, "p2")
        var swiped = false
        s.onSwipe = { _, _ -> swiped = true; "p3" }
        s.handler.onDown()
        s.handler.onDragStart(DragAxis.Vertical)
        s.handler.onDrag(DragAxis.Vertical, Offset(0f, -400f))   // 超过 300 的阈值
        s.handler.onDragCancel()
        assertTrue(!swiped)
        assertEquals("p2", s.currentKey)
    }

    @Test fun `左右或上下拖动开始就收回详情_平移不收`() {
        val s = bounceStage(three, "p2")
        var n = 0
        s.onDetailsDismiss = { n++ }
        s.handler.onDragStart(DragAxis.Horizontal)
        s.handler.onDragStart(DragAxis.Vertical)
        s.handler.onDragStart(DragAxis.Pan)
        assertEquals(2, n)
    }

    @Test fun `翻页和读屏上下滑也收回详情`() {
        val s = bounceStage(three, "p2")
        var n = 0
        s.onDetailsDismiss = { n++ }
        s.goPrevious()
        assertEquals(1, n)
        s.handler.onDown()
        s.onSwipe = { _, _ -> "p3" }
        assertTrue(s.accessibilitySwipe(up = true))
        assertEquals(2, n)
    }

    @Test fun `放大后拖动_横图上下不能拖出照片_左右拖到照片边缘为止`() {
        val wide = ReviewPage.Photo(TestPhotoData.createTestPhoto(id = 1).copy(width = 4000, height = 3000))
        val s = bounceStage(listOf(wide), "p1")
        s.handler.onPinch(Offset(540f, 1200f), 2f, Offset.Zero)
        val z = s.zoom
        assertTrue("放大后仍应小于 2400 / 810", z > 1f && 810f * z < 2400f)
        s.handler.onDrag(DragAxis.Pan, Offset(5000f, 5000f))
        // 显示 1080×810：放大后高没超出 2400，上下不能拖；宽超出 1080，拖到照片边缘为止
        assertEquals(1080f * (z - 1f) / 2f, s.zoomOffset.x, 0.01f)
        assertEquals(0f, s.zoomOffset.y, 0.01f)
    }
}
