package com.winters.cleanphotos.verify

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.winters.cleanphotos.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P4 验证用的手势驱动（不是断言型测试）：adb shell input 不支持多指，这里用 UiAutomation 注入
 * 双指缩放 / 双击 / 双击后立即拖动，供外部 screenrecord + ffmpeg 抽帧核对。
 * 每次注入前都确认当前 RESUMED 的 Activity 是 CleanPhotos，避免点到别的应用。
 * 运行：./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=…#方法名
 */
@RunWith(AndroidJUnit4::class)
class ReviewGestureDriver {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.uiAutomation

    private fun assertForeground() {
        var ok = false
        instrumentation.runOnMainSync {
            ok = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .any { it.packageName == "com.winters.cleanphotos" }
        }
        assertTrue("CleanPhotos 不在前台，停止注入", ok)
    }

    private fun event(downTime: Long, action: Int, pts: List<Pair<Float, Float>>): MotionEvent {
        val props = Array(pts.size) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(pts.size) { i -> MotionEvent.PointerCoords().apply { x = pts[i].first; y = pts[i].second; pressure = 1f; size = 1f } }
        return MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, pts.size, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }

    private fun send(e: MotionEvent) {
        automation.injectInputEvent(e, true)
        e.recycle()
    }

    private fun tap(x: Float, y: Float) {
        assertForeground()
        val t = SystemClock.uptimeMillis()
        send(event(t, MotionEvent.ACTION_DOWN, listOf(x to y)))
        SystemClock.sleep(40)
        send(event(t, MotionEvent.ACTION_UP, listOf(x to y)))
    }

    /** 冷启动 → 点 9 月 21 日第一张进入清理页。 */
    private fun openReview() {
        ActivityScenario.launch(MainActivity::class.java)
        SystemClock.sleep(3000)
        tap(134f, 1300f)
        SystemClock.sleep(2500)
    }

    /** 双指：第一根手指先按住不动 [firstFingerMove] px，再落第二根，沿竖直方向张开 / 合拢。 */
    private fun pinch(from: Float, to: Float, firstFingerMove: Float = 0f) {
        assertForeground()
        val cx = 540f; val cy = 1200f
        val t = SystemClock.uptimeMillis()
        var a = cy - from; var b = cy + from
        send(event(t, MotionEvent.ACTION_DOWN, listOf(cx to a)))
        SystemClock.sleep(30)
        if (firstFingerMove != 0f) {
            for (i in 1..4) { send(event(t, MotionEvent.ACTION_MOVE, listOf(cx + firstFingerMove * i / 4 to a))); SystemClock.sleep(16) }
        }
        val x0 = cx + firstFingerMove
        send(event(t, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(x0 to a, cx to b)))
        val steps = 18
        for (i in 1..steps) {
            val r = from + (to - from) * i / steps
            a = cy - r; b = cy + r
            send(event(t, MotionEvent.ACTION_MOVE, listOf(x0 to a, cx to b)))
            SystemClock.sleep(16)
        }
        SystemClock.sleep(200)
        send(event(t, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(x0 to a, cx to b)))
        send(event(t, MotionEvent.ACTION_UP, listOf(x0 to a)))
    }

    private fun drag(x0: Float, y0: Float, x1: Float, y1: Float, ms: Long) {
        assertForeground()
        val t = SystemClock.uptimeMillis()
        send(event(t, MotionEvent.ACTION_DOWN, listOf(x0 to y0)))
        val steps = (ms / 16).toInt().coerceAtLeast(2)
        for (i in 1..steps) {
            send(event(t, MotionEvent.ACTION_MOVE, listOf(x0 + (x1 - x0) * i / steps to y0 + (y1 - y0) * i / steps)))
            SystemClock.sleep(16)
        }
        send(event(t, MotionEvent.ACTION_UP, listOf(x1 to y1)))
    }

    @Test fun pinchZoom() {
        openReview()
        pinch(150f, 500f)                          // 张开放大
        SystemClock.sleep(1200)
        drag(700f, 1200f, 400f, 1000f, 250)        // 放大时单指 = 平移，不翻页
        SystemClock.sleep(1000)
        pinch(500f, 150f)                          // 合拢回 1×
        SystemClock.sleep(1200)
        pinch(150f, 450f, firstFingerMove = 40f)   // 第一根手指先移动过 slop 再落第二根（lessons 2026-05-09）
        SystemClock.sleep(1200)
        pinch(450f, 120f)                          // 缩到 < 1 松手回弹
        SystemClock.sleep(1500)
    }

    @Test fun doubleTap() {
        openReview()
        tap(540f, 1200f); SystemClock.sleep(90); tap(540f, 1200f)   // 双击放大
        SystemClock.sleep(1200)
        tap(540f, 1200f); SystemClock.sleep(90); tap(540f, 1200f)   // 双击还原
        SystemClock.sleep(1200)
        tap(540f, 1200f); SystemClock.sleep(60)                      // 单击后立即横滑：拖动不应等双击超时
        drag(900f, 1200f, 250f, 1200f, 160)
        SystemClock.sleep(1500)
    }
}
