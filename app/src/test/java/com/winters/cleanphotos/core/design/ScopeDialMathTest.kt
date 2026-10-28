package com.winters.cleanphotos.core.design

import org.junit.Assert.assertEquals
import org.junit.Test

class ScopeDialMathTest {

    @Test
    fun alpha_falls_off_then_floors() {
        assertEquals(1f, ScopeDialMath.alpha(0f), 0.0001f)
        assertEquals(0.725f, ScopeDialMath.alpha(0.5f), 0.0001f)
        assertEquals(0.45f, ScopeDialMath.alpha(1f), 0.0001f)
        assertEquals(0.22f, ScopeDialMath.alpha(2f), 0.0001f)
        assertEquals(0.22f, ScopeDialMath.alpha(4f), 0.0001f)
    }

    @Test
    fun centers_interpolate_and_extrapolate() {
        val widths = floatArrayOf(40f, 60f, 40f)
        val gap = 22f
        val first = ScopeDialMath.center(0, widths, gap)
        val second = ScopeDialMath.center(1, widths, gap)
        assertEquals(20f, first, 0.001f)
        assertEquals(40f + gap + 30f, second, 0.001f)
        assertEquals((first + second) / 2f, ScopeDialMath.centerAt(0.5f, widths, gap), 0.001f)
        val step = second - first
        assertEquals(first - 0.25f * step, ScopeDialMath.centerAt(-0.25f, widths, gap), 0.001f)
        assertEquals(180f - first, ScopeDialMath.translationX(180f, 0f, widths, gap), 0.001f)
        assertEquals(180f - (first + second) / 2f, ScopeDialMath.translationX(180f, 0.5f, widths, gap), 0.001f)
    }

    @Test
    fun drag_pages_moves_toward_the_next_scope_when_the_finger_moves_left() {
        assertEquals(1f, ScopeDialMath.dragPages(dx = -40f, stride = 40f), 0.001f)
        assertEquals(-0.5f, ScopeDialMath.dragPages(dx = 20f, stride = 40f), 0.001f)
    }

    @Test
    fun hit_index_picks_the_label_under_the_content_x() {
        val widths = floatArrayOf(40f, 60f, 40f)
        val gap = 22f
        val minTouch = 48f
        assertEquals(0, ScopeDialMath.hitIndex(10f, widths, gap, minTouch))
        assertEquals(1, ScopeDialMath.hitIndex(ScopeDialMath.center(1, widths, gap), widths, gap, minTouch))
        assertEquals(-1, ScopeDialMath.hitIndex(-100f, widths, gap, minTouch))
    }

    @Test
    fun single_label_overscroll_uses_width_plus_gap() {
        val widths = floatArrayOf(40f)
        val gap = 22f
        assertEquals(20f, ScopeDialMath.centerAt(0f, widths, gap), 0.001f)
        assertEquals(20f + 0.5f * (40f + gap), ScopeDialMath.centerAt(0.5f, widths, gap), 0.001f)
    }

    @Test
    fun hit_width_extends_half_a_gap_each_side_and_floors_at_min_touch() {
        assertEquals(48f, ScopeDialMath.hitWidth(10f, 22f, 48f), 0.001f)
        assertEquals(80f, ScopeDialMath.hitWidth(58f, 22f, 48f), 0.001f)
    }
}
