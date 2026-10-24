package com.winters.cleanphotos.feature.similar

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import com.winters.cleanphotos.core.design.GestureTokens
import com.winters.cleanphotos.feature.review.DragAxis
import com.winters.cleanphotos.feature.review.ReviewThresholds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimilarCompareMotionTest {
    private val thresholds = ReviewThresholds(
        vertical = 120f,
        verticalFlingVelocity = 3000f,
        minFlingDistance = 24f,
        horizontal = 80f,
        horizontalFlingVelocity = 2000f,
        effectCap = 170f,
        edgeLength = 100f,
    )

    @Test
    fun releaseFreezesDirectionThenAppliesTheSwipe() {
        val scope = scope()
        try {
            val motion = motion(scope)
            var up: Boolean? = null
            motion.onVertical = { up = it }
            motion.onDragStart(DragAxis.Vertical)
            motion.onDrag(DragAxis.Vertical, Offset(0f, 200f))
            motion.onDragEnd(DragAxis.Vertical, Velocity.Zero)
            assertFalse(motion.fingerVertical)
            assertTrue(motion.holding)
            assertEquals(1f, motion.cueAlpha, 0f)
            assertEquals(false, up)
            assertFalse(motion.heldUp)
            assertTrue(motion.heldPast)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun belowThresholdStillFreezesTheCue() {
        val scope = scope()
        try {
            val motion = motion(scope)
            var called = false
            motion.onVertical = { called = true }
            motion.onDragStart(DragAxis.Vertical)
            motion.onDrag(DragAxis.Vertical, Offset(0f, -30f))
            motion.onDragEnd(DragAxis.Vertical, Velocity.Zero)
            assertFalse(called)
            assertTrue(motion.heldUp)
            assertFalse(motion.heldPast)
            assertEquals(30f / 120f, motion.heldProgress, 0.001f)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun removedAnimationsHideTheCueImmediately() {
        val scope = scope()
        try {
            val motion = motion(scope)
            motion.animationsEnabled = false
            motion.onDragStart(DragAxis.Vertical)
            motion.onDrag(DragAxis.Vertical, Offset(0f, 200f))
            motion.onDragEnd(DragAxis.Vertical, Velocity.Zero)
            assertFalse(motion.holding)
            assertEquals(0f, motion.cueAlpha, 0f)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun verticalSwipeDuringPageSlideStaysOnTheNewPage() {
        val scope = scope()
        try {
            val motion = motion(scope)
            motion.pageCount = 3
            motion.pageIndex = 1
            motion.width = 400f
            var committed = 0
            motion.onCommitPage = { committed = it }
            var up: Boolean? = null
            motion.onVertical = { up = it }
            motion.onDragStart(DragAxis.Horizontal)
            motion.onDrag(DragAxis.Horizontal, Offset(-100f, 0f))
            motion.onDragEnd(DragAxis.Horizontal, Velocity.Zero)
            assertEquals(-1, committed)
            assertEquals(2, motion.pageIndex)
            assertEquals(300f, motion.dx, 0.01f)
            motion.onDown()
            motion.onDragStart(DragAxis.Vertical)
            motion.onDrag(DragAxis.Vertical, Offset(0f, 200f))
            motion.onDragEnd(DragAxis.Vertical, Velocity.Zero)
            assertEquals(2, motion.pageIndex)
            assertEquals(false, up)
            assertTrue(motion.holding)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun shortHorizontalSwipeDoesNotChangePage() {
        val scope = scope()
        try {
            val motion = motion(scope)
            motion.pageCount = 3
            motion.pageIndex = 1
            motion.width = 400f
            var committed = false
            motion.onCommitPage = { committed = true }
            motion.onDragStart(DragAxis.Horizontal)
            motion.onDrag(DragAxis.Horizontal, Offset(-30f, 0f))
            motion.onDragEnd(DragAxis.Horizontal, Velocity.Zero)
            assertFalse(committed)
            assertEquals(1, motion.pageIndex)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun horizontalDragDoesNotHoldACue() {
        val scope = scope()
        try {
            val motion = motion(scope)
            motion.pageCount = 3
            motion.width = 400f
            motion.onDragStart(DragAxis.Horizontal)
            motion.onDrag(DragAxis.Horizontal, Offset(-100f, 0f))
            motion.onDragEnd(DragAxis.Horizontal, Velocity.Zero)
            assertFalse(motion.fingerVertical)
            assertFalse(motion.holding)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun heroBrightnessOnlyDipsWhileTheFingerDragsDown() {
        val down = similarHeroBrightness(0, fingerVertical = true, dy = 170f, dx = 0f, width = 1000f, thresholds)
        val half = similarHeroBrightness(0, fingerVertical = true, dy = 85f, dx = 0f, width = 1000f, thresholds)
        assertEquals(GestureTokens.SIMILAR_DRAG_MIN_BRIGHTNESS, down, 0.001f)
        assertEquals(1f - (1f - GestureTokens.SIMILAR_DRAG_MIN_BRIGHTNESS) * 0.5f, half, 0.001f)
        assertEquals(1f, similarHeroBrightness(0, fingerVertical = true, dy = -170f, dx = 0f, width = 1000f, thresholds), 0f)
        assertEquals(1f, similarHeroBrightness(0, fingerVertical = false, dy = 170f, dx = 0f, width = 1000f, thresholds), 0f)
        assertEquals(0.7f, similarHeroBrightness(1, fingerVertical = true, dy = 170f, dx = 0f, width = 1000f, thresholds), 0.001f)
    }

    private fun scope() = CoroutineScope(SupervisorJob() + StandardTestDispatcher())

    private fun motion(scope: CoroutineScope) = SimilarCompareMotion(scope).also {
        it.thresholds = thresholds
        it.animationsEnabled = true
    }
}