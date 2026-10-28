package com.winters.cleanphotos.core.design

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignTokensTest {

    private fun hex(c: Color): String = "%08X".format(
        ((c.alpha * 255 + 0.5f).toInt() shl 24) or ((c.red * 255 + 0.5f).toInt() shl 16) or
            ((c.green * 255 + 0.5f).toInt() shl 8) or (c.blue * 255 + 0.5f).toInt()
    )

    @Test fun colors_match_html_root() {
        assertEquals("FF0F0E0D", hex(DarkroomColors.Room))
        assertEquals("FF1A1816", hex(DarkroomColors.Tray))
        assertEquals("FF24211E", hex(DarkroomColors.Tray2))
        assertEquals("FFEEE9E2", hex(DarkroomColors.Paper))
        assertEquals("FF8C857D", hex(DarkroomColors.Dim))
        assertEquals("FF5B554F", hex(DarkroomColors.Faint))
        assertEquals("FF6E6862", hex(DarkroomColors.Fade))
        assertEquals("FF4A4541", hex(DarkroomColors.FadeDeep))
        assertEquals("FFA39C94", hex(DarkroomColors.FadeText))
        assertEquals("FFC4553F", hex(DarkroomColors.Delete))
        assertEquals("FFF2A93B", hex(DarkroomColors.Amber))
        assertEquals(0.09f, DarkroomColors.Rim.alpha, 0.005f)
        assertEquals(0.16f, DarkroomColors.Rim2.alpha, 0.005f)
        assertEquals(0.56f, DarkroomColors.Glass.alpha, 0.005f)
    }

    @Test fun durations_match_motion_tokens() {
        assertEquals(listOf(100, 150, 280, 450, 600, 40), with(MotionDurations) { listOf(MICRO, SHORT, MEDIUM, LONG, DEVELOP, STAGGER) })
        assertEquals(12, MotionDurations.STAGGER_MAX_STEPS)
    }

    @Test fun easings_match_cubic_bezier() {
        assertEquals(CubicBezierEasing(0.2f, 0f, 0f, 1f), MotionEasings.Standard)
        assertEquals(CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f), MotionEasings.Decelerate)
        assertEquals(CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f), MotionEasings.Accelerate)
        assertEquals(CubicBezierEasing(0.45f, 0f, 0.2f, 1f), MotionEasings.Develop)
    }

    @Test fun springs_match() {
        assertEquals(SpringToken(1.0f, 1500f), MotionSprings.Snappy)
        assertEquals(SpringToken(0.75f, 400f), MotionSprings.Settle)
        assertEquals(SpringToken(0.5f, 400f), MotionSprings.Bouncy)
        assertEquals(SpringToken(0.85f, 200f), MotionSprings.Gentle)
    }

    @Test fun tile_badge_scrim_is_a_short_room_fade() {
        assertEquals(28f, Dimens.TileBadgeScrimHeight.value)
        assertEquals(0.62f, DarkroomColors.TileBadgeScrim.alpha, 0.001f)
        assertEquals(DarkroomColors.Room.red, DarkroomColors.TileBadgeScrim.red, 0.001f)
        assertEquals(DarkroomColors.Room.green, DarkroomColors.TileBadgeScrim.green, 0.001f)
        assertEquals(DarkroomColors.Room.blue, DarkroomColors.TileBadgeScrim.blue, 0.001f)
    }

    @Test fun nav_indicator_is_16dp_by_decision_6() {
        assertEquals(16f, Dimens.NavIndicatorWidth.value)
        assertEquals(2f, Dimens.NavIndicatorHeight.value)
    }

    @Test fun scope_dial_matches_the_row_spec() {
        assertEquals(40f, Dimens.ScopeRowHeight.value)
        assertEquals(8f, Dimens.ScopeRowBottom.value)
        assertEquals(22f, Dimens.ScopeDialGap.value)
        assertEquals(40f, Dimens.ScopeDialEdgeFade.value)
        assertEquals(6f, Dimens.ScopeDialTextTop.value)
        assertEquals(32f, Dimens.ScopeDialIndicatorTop.value)
        assertEquals(14f, DarkroomText.ScopeDial.fontSize.value)
        assertEquals(20f, DarkroomText.ScopeDial.lineHeight.value)
    }

    @Test fun develop_single_channel_windows() {
        val p = DevelopProfiles.Single
        val v0 = DevelopMatrix.values(p, 0f)
        assertEquals(0.06f, v0.brightness, 1e-4f); assertEquals(0.55f, v0.contrast, 1e-4f); assertEquals(0f, v0.saturation, 1e-4f)
        assertEquals(1.035f, v0.scale, 1e-4f); assertEquals(0.55f, v0.grain, 1e-4f)
        // 80ms 时反差还没开始、200ms 时饱和还没开始
        assertEquals(0.55f, DevelopMatrix.values(p, 80f / 600f).contrast, 1e-4f)
        assertEquals(0f, DevelopMatrix.values(p, 200f / 600f).saturation, 1e-4f)
        // 500ms 亮度到位
        assertEquals(1f, DevelopMatrix.values(p, 500f / 600f).brightness, 1e-4f)
        val v1 = DevelopMatrix.values(p, 1f)
        assertEquals(1f, v1.brightness, 1e-4f); assertEquals(1f, v1.contrast, 1e-4f); assertEquals(1f, v1.saturation, 1e-4f)
        assertEquals(1f, v1.scale, 1e-4f); assertEquals(0f, v1.grain, 1e-4f)
    }

    @Test fun review_next_respects_decision_7() {
        val p = DevelopProfiles.ReviewNext
        assertTrue(p.durationMs <= 280)
        assertTrue(p.brightness.start >= 0.6f)
        assertEquals(0.94f, DevelopProfiles.REVIEW_NEXT_SCALE_FROM)
    }

    @Test fun matrix_identity_at_end_and_css_semantics() {
        val id = DevelopMatrix.compose(1f, 1f, 1f)
        assertArrayEquals(floatArrayOf(1f,0f,0f,0f,0f, 0f,1f,0f,0f,0f, 0f,0f,1f,0f,0f, 0f,0f,0f,1f,0f), id, 1e-4f)
        // 白像素经过 brightness 0.5, contrast 1, saturate 1 → 0.5
        val m = DevelopMatrix.compose(0.5f, 1f, 1f)
        assertEquals(127.5f, (m[0] + m[1] + m[2]) * 255f + m[4], 0.01f)
        // contrast 0 → 全部变 50% 灰
        val c0 = DevelopMatrix.compose(1f, 0f, 1f)
        assertEquals(127.5f, c0[4], 0.01f); assertEquals(0f, c0[0], 1e-4f)
    }

    @Test fun api_tier_table() {
        val t26 = ApiTier(26); val t30 = ApiTier(30); val t31 = ApiTier(31); val t33 = ApiTier(33); val t34 = ApiTier(34)
        assertFalse(t26.hasBlur); assertFalse(t30.hasBlur); assertTrue(t31.hasBlur)
        assertFalse(t31.hasDevelopGrain); assertTrue(t33.hasDevelopGrain)
        assertFalse(t33.hasPredictiveBack); assertTrue(t34.hasPredictiveBack)
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, t26.thresholdActivateHaptic)
        assertEquals(HapticFeedbackConstants.CONFIRM, t30.thresholdActivateHaptic)
        assertEquals(HapticFeedbackConstants.REJECT, t33.thresholdDeactivateHaptic)
        assertEquals(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE, t34.thresholdActivateHaptic)
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, t26.dayChangeHaptic)
    }

    @Test(expected = IllegalArgumentException::class) fun api_tier_below_min_fails() { ApiTier(25) }

    @Test fun edge_resistance_card02() {
        // 手指 150dp、L = 360·0.3 = 108 → 约 47dp（按「屏宽 × 0.3」，360dp 屏为 47、412dp 屏约 50）
        val out = GestureTokens.edgeResistance(150f, 108f)
        assertEquals(108f * (1f - 1f / (150f * 0.55f / 108f + 1f)), out, 1e-3f)
        assertEquals(-out, GestureTokens.edgeResistance(-150f, 108f), 1e-3f)
    }

    @Test fun spring_value_at_matches_shape() {
        assertEquals(0f, MotionSprings.Gentle.valueAt(0f), 1e-6f)
        // 舒缓 0.85/200：600ms 基本落定，略有过冲但 < 1%
        assertEquals(1f, MotionSprings.Gentle.valueAt(600f), 0.01f)
        // 弹跳 0.5/400 有明显过冲
        val peak = (1..800).maxOf { MotionSprings.Bouncy.valueAt(it.toFloat()) }
        assertTrue(peak > 1.1f)
    }

    @Test fun pinch_resistance_card15() {
        assertEquals(1.5f, GestureTokens.pinchResistance(1.5f), 1e-6f)
        assertEquals(1f, GestureTokens.pinchResistance(1f), 1e-6f)
        // 1× 附近斜率 ≈ 1，越往下越硬，渐近 0.8 不越过
        assertEquals(0.99f, GestureTokens.pinchResistance(0.99f), 1e-3f)
        assertTrue(GestureTokens.pinchResistance(0.5f) > 0.8f)
        assertTrue(GestureTokens.pinchResistance(0.1f) > 0.8f)
        assertTrue(GestureTokens.pinchResistance(0.5f) < GestureTokens.pinchResistance(0.7f))
    }

    @Test fun odometer_direction_and_positions() {
        assertEquals(Odometer.Direction.Up, Odometer.direction(22, 23))
        assertEquals(Odometer.Direction.Down, Odometer.direction(1284, 1283))
        assertEquals(listOf(false, false, false, false, true), Odometer.changedPositions("1,284", "1,283"))
        assertEquals(listOf(true, true, true), Odometer.changedPositions("99", "100"))
    }

    @Test fun animation_scale_zero_disables() {
        assertFalse(AnimationScale.isEnabled(0f)); assertTrue(AnimationScale.isEnabled(0.5f))
    }

    @Test fun `决定 9_settle 弹簧压进定长窗口_两端准确且保留弹簧形状`() {
        val e = MotionSprings.Settle.asEasing()
        assertEquals(0f, e.transform(0f), 1e-4f)
        assertEquals(1f, e.transform(1f), 1e-4f)
        // 0.75 阻尼有轻微过冲，中段超过 1 后回落
        val peak = (1..99).maxOf { e.transform(it / 100f) }
        assertTrue(peak > 1f && peak < 1.05f)
        // 前段快速上升（弹簧起步快）
        assertTrue(e.transform(0.25f) > 0.6f)
        // 窗口长度：衰减到 0.1% 的时刻
        assertTrue(MotionDurations.SHARED_EXPAND == 320 && MotionDurations.SHARED_CHROME_FADE == 120)
    }
}
