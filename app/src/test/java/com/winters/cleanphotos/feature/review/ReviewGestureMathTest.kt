package com.winters.cleanphotos.feature.review

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 清理页手势纯计算（interaction.md「滑动手感硬性要求」，决定 3 / 4）。px 以 density = 1 计。 */
class ReviewGestureMathTest {
    private val t = ReviewThresholds(
        vertical = 120f, verticalFlingVelocity = 800f, minFlingDistance = 24f,
        horizontal = 60f, horizontalFlingVelocity = 600f, effectCap = 170f, edgeLength = 120f,
    )

    @Test fun `水平阈值_屏宽 12 百分比限制在 48 到 96`() {
        assertEquals(48f, ReviewGestureMath.horizontalThreshold(300f, 48f, 96f), 0.01f)
        assertEquals(60f, ReviewGestureMath.horizontalThreshold(500f, 48f, 96f), 0.01f)
        assertEquals(96f, ReviewGestureMath.horizontalThreshold(1200f, 48f, 96f), 0.01f)
    }

    @Test fun `方向锁定_主轴需 1_2 倍`() {
        assertEquals(DragAxis.Horizontal, ReviewGestureMath.axisOf(12f, 10f))
        assertEquals(DragAxis.Vertical, ReviewGestureMath.axisOf(-3f, -10f))
        assertEquals(DragAxis.None, ReviewGestureMath.axisOf(10f, 10f))
    }

    @Test fun `上下松手_距离过阈值或甩动过速度且至少 24`() {
        assertEquals(VerticalRelease.Up, ReviewGestureMath.verticalRelease(-130f, 0f, t))
        assertEquals(VerticalRelease.Down, ReviewGestureMath.verticalRelease(30f, 1200f, t))
        assertEquals(VerticalRelease.None, ReviewGestureMath.verticalRelease(-20f, -2000f, t))
        assertEquals(VerticalRelease.None, ReviewGestureMath.verticalRelease(-100f, 0f, t))
    }

    @Test fun `上下松手_过了阈值但用力往回甩不触发`() {
        assertEquals(VerticalRelease.None, ReviewGestureMath.verticalRelease(-150f, 1500f, t))
    }

    @Test fun `左右松手_距离或速度_反向甩回不翻`() {
        assertEquals(-1, ReviewGestureMath.horizontalRelease(-70f, 0f, t))
        assertEquals(1, ReviewGestureMath.horizontalRelease(20f, 900f, t))
        assertEquals(0, ReviewGestureMath.horizontalRelease(-70f, 900f, t))
        assertEquals(0, ReviewGestureMath.horizontalRelease(-30f, -100f, t))
        assertEquals(0, ReviewGestureMath.horizontalRelease(0f, 0f, t))
    }

    @Test fun `决定3_上下都随距离缩到 0_9`() {
        assertEquals(1f, ReviewGestureMath.dragScale(0f, t), 0.001f)
        assertEquals(0.9f, ReviewGestureMath.dragScale(-170f, t), 0.001f)
        assertEquals(0.9f, ReviewGestureMath.dragScale(400f, t), 0.001f)
        assertEquals(0.95f, ReviewGestureMath.dragScale(85f, t), 0.001f)
    }

    @Test fun `决定4_下滑饱和亮度连续变化_过阈值不跳档_上滑不变`() {
        var prevS = 1f; var prevB = 1f
        var y = 0f
        while (y <= 200f) {
            val s = ReviewGestureMath.dragSaturation(y, t)
            val b = ReviewGestureMath.dragBrightness(y, t)
            assertTrue(s <= prevS && prevS - s < 0.02f)
            assertTrue(b <= prevB && prevB - b < 0.02f)
            prevS = s; prevB = b; y += 1f
        }
        assertEquals(0.35f, ReviewGestureMath.dragSaturation(170f, t), 0.001f)
        assertEquals(0.55f, ReviewGestureMath.dragBrightness(170f, t), 0.001f)
        assertEquals(1f, ReviewGestureMath.dragSaturation(-170f, t), 0.001f)
    }

    @Test fun `左右翻页进入页亮度 0_7 到 1`() {
        assertEquals(0.7f, ReviewGestureMath.enteringBrightness(0f, 1000f), 0.001f)
        assertEquals(1f, ReviewGestureMath.enteringBrightness(-1000f, 1000f), 0.001f)
    }

    @Test fun `越界阻尼只在会话首尾`() {
        assertEquals(50f, ReviewGestureMath.horizontalOffset(50f, hasPrevious = true, hasNext = true, t = t), 0.001f)
        assertTrue(ReviewGestureMath.horizontalOffset(50f, hasPrevious = false, hasNext = true, t = t) < 50f)
        assertTrue(ReviewGestureMath.horizontalOffset(-50f, hasPrevious = true, hasNext = false, t = t) > -50f)
    }

    // —— 抬起事件自带位移（P5 连滑丢操作）——

    @Test fun `抬起位移_已在拖动时整段计入`() {
        val up = ReviewGestureMath.upEventDrag(DragAxis.Vertical, Offset(0f, 700f), Offset(0f, 511f), slop = 8f, zoomed = false)!!
        assertEquals(DragAxis.Vertical, up.axis)
        assertFalse(up.startAxis)
        assertEquals(511f, up.delta.y, 0.001f)
    }

    @Test fun `抬起位移_尚未定方向时补开始拖动并扣 slop`() {
        // 整段 700px 只出现在抬起事件里：以前会被当成点按
        val up = ReviewGestureMath.upEventDrag(DragAxis.None, Offset(0f, -700f), Offset(0f, -700f), slop = 8f, zoomed = false)!!
        assertEquals(DragAxis.Vertical, up.axis)
        assertTrue(up.startAxis)
        assertEquals(-692f, up.delta.y, 0.001f)
        assertEquals(0f, up.delta.x, 0.001f)
    }

    @Test fun `抬起位移_为零或不足 slop 或放大中或方向不明时不处理`() {
        assertNull(ReviewGestureMath.upEventDrag(DragAxis.Vertical, Offset(0f, 300f), Offset.Zero, 8f, false))
        assertNull(ReviewGestureMath.upEventDrag(DragAxis.None, Offset(0f, 5f), Offset(0f, 5f), 8f, false))
        assertNull(ReviewGestureMath.upEventDrag(DragAxis.None, Offset(0f, 300f), Offset(0f, 300f), 8f, zoomed = true))
        assertNull(ReviewGestureMath.upEventDrag(DragAxis.None, Offset(100f, 100f), Offset(100f, 100f), 8f, false))
    }

    @Test fun `抬起位移_横向同样计入`() {
        val up = ReviewGestureMath.upEventDrag(DragAxis.None, Offset(-400f, 10f), Offset(-400f, 10f), slop = 8f, zoomed = false)!!
        assertEquals(DragAxis.Horizontal, up.axis)
        assertEquals(-392f, up.delta.x, 0.001f)
        assertEquals(0f, up.delta.y, 0.001f)
    }

    @Test fun `照片 Fit 尺寸_横图按宽撑满_竖图按高撑满`() {
        val wide = ReviewGestureMath.fitSize(4000, 3000, 1080f, 2400f)
        assertEquals(1080f, wide.width, 0.01f)
        assertEquals(810f, wide.height, 0.01f)
        val tall = ReviewGestureMath.fitSize(1000, 4000, 1080f, 2400f)
        assertEquals(600f, tall.width, 0.01f)
        assertEquals(2400f, tall.height, 0.01f)
    }

    @Test fun `照片 Fit 尺寸_宽高未知时等于容器`() {
        val unknown = ReviewGestureMath.fitSize(null, 3000, 1080f, 2400f)
        assertEquals(1080f, unknown.width, 0.01f)
        assertEquals(2400f, unknown.height, 0.01f)
    }
}
