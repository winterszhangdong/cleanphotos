package com.winters.cleanphotos.core.design

import android.os.SystemClock
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive

/** 甩动结束后仍屏蔽点按的时长：吸收惯性抬手，避免误开清理页。 */
const val SCROLL_SETTLE_TAP_GUARD_MS = 300L

/**
 * 最近一次网格滚动（含惯性）的 uptime。
 * 嵌套滚动回调 + 滚动进行中的心跳共同刷新，停稳后再等 [SCROLL_SETTLE_TAP_GUARD_MS]。
 */
internal object GridScrollClock {
    val lastScrollUptimeMs = AtomicLong(0L)

    fun mark() {
        lastScrollUptimeMs.set(SystemClock.uptimeMillis())
    }

    fun settled(settleMs: Long = SCROLL_SETTLE_TAP_GUARD_MS): Boolean =
        SystemClock.uptimeMillis() - lastScrollUptimeMs.get() >= settleMs
}

/** 挂在 LazyVerticalGrid 上：滚动量 / fling 同步刷新时钟。 */
@Composable
fun Modifier.gridScrollTapGuard(): Modifier {
    val connection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y != 0f) GridScrollClock.mark()
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (consumed.y != 0f || available.y != 0f) GridScrollClock.mark()
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (available.y != 0f) GridScrollClock.mark()
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (consumed.y != 0f || available.y != 0f) GridScrollClock.mark()
                return Velocity.Zero
            }
        }
    }
    return nestedScroll(connection)
}

/**
 * 网格是否已「停稳可点」。
 * 滚动进行中每 ~32ms 刷新时钟；刚停住时再 mark 一次，之后满 [settleMs] 才允许点按。
 */
@Composable
fun rememberScrollSettled(
    state: LazyGridState,
    settleMs: Long = SCROLL_SETTLE_TAP_GUARD_MS,
): () -> Boolean {
    LaunchedEffect(state, settleMs) {
        snapshotFlow { state.isScrollInProgress }.collectLatest { scrolling ->
            if (scrolling) {
                while (isActive && state.isScrollInProgress) {
                    GridScrollClock.mark()
                    delay(32)
                }
            }
            // 停住（或本轮结束）：从这一刻起算 settle
            GridScrollClock.mark()
        }
    }
    return remember(state, settleMs) {
        {
            !state.isScrollInProgress && GridScrollClock.settled(settleMs)
        }
    }
}

/** 仅在 [settled] 为 true 时把参数传给 [action]。 */
inline fun <T> (() -> Boolean).whenSettled(crossinline action: (T) -> Unit): (T) -> Unit =
    { value -> if (this()) action(value) }
