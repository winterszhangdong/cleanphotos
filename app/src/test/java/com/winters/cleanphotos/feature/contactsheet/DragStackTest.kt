package com.winters.cleanphotos.feature.contactsheet

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.winters.cleanphotos.TestPhotoData.createTestPhoto
import com.winters.cleanphotos.core.design.FilingMotion
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DragStackTest {

    @Test
    fun `每张的拿起进度两头都收在0和1_后面的晚一点起飞`() {
        for (i in 0..8) {
            assertEquals(0f, liftProgress(0f, i, FilingMotion.PICK_UP), 0f)
            assertEquals(1f, liftProgress(1f, i, FilingMotion.PICK_UP), 0f)
        }
        val mid = (0..4).map { liftProgress(0.5f, it, FilingMotion.PICK_UP) }
        assertTrue(mid.zipWithNext().all { (a, b) -> a > b })
        // 第 5 张起和第 5 张同时走，不再往后错
        assertEquals(liftProgress(0.5f, 4, FilingMotion.PICK_UP), liftProgress(0.5f, 20, FilingMotion.PICK_UP), 0f)
    }

    @Test
    fun `拿起后格子空着_落回到最后一段原图才淡回来`() {
        assertEquals(0f, slotAlphaFor(returning = false, t = 0f), 0f)
        assertEquals(0f, slotAlphaFor(returning = false, t = 1f), 0f)
        assertEquals(0f, slotAlphaFor(returning = true, t = 1f), 0f)
        assertEquals(0f, slotAlphaFor(returning = true, t = FilingMotion.LAND_HANDOFF), 0f)
        assertEquals(0.5f, slotAlphaFor(returning = true, t = FilingMotion.LAND_HANDOFF / 2f), 1e-5f)
        assertEquals(1f, slotAlphaFor(returning = true, t = 0f), 0f)
    }

    @Test
    fun `拿起的照片格子空着_没拿起的照常显示_落回落定后原图回来`() = runTest(FrameClock()) {
        val a = createTestPhoto(id = 1)
        val b = createTestPhoto(id = 2)
        val drag = DragStack(listOf(a, b), Offset.Zero, mapOf(1L to Rect(0f, 0f, 10f, 10f)))
        val visuals = FilingVisuals().apply { this.drag = drag }
        assertEquals(0f, visuals.slotAlpha(1), 0f)
        assertEquals(0f, visuals.slotAlpha(2), 0f)
        assertEquals(1f, visuals.slotAlpha(3), 0f)
        drag.lift.snapTo(1f)
        drag.putBack(emptyMap())
        assertTrue(drag.returning)
        assertEquals(1f, visuals.slotAlpha(1), 0f)
        assertEquals(1f, visuals.slotAlpha(2), 0f)
    }

    @Test
    fun `从手指下的叠入袋时格子一直空着_点袋子起飞的不管`() {
        val a = createTestPhoto(id = 1)
        val visuals = FilingVisuals()
        visuals.flight = FlightRun(1, listOf(a), 1, "Pictures/猫/", listOf(null), Offset(5f, 5f), 0, null)
        assertEquals(0f, visuals.slotAlpha(1), 0f)
        visuals.flight = FlightRun(2, listOf(a), 1, "Pictures/猫/", listOf(null), null, 0, null)
        assertEquals(1f, visuals.slotAlpha(1), 0f)
    }

    /** 每次一帧 16ms，让 Animatable 在 JVM 上跑完。 */
    private class FrameClock : MonotonicFrameClock {
        private var now = 0L
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            now += 16_000_000L
            return onFrame(now)
        }
    }
}
