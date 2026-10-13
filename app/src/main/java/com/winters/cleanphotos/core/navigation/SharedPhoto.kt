package com.winters.cleanphotos.core.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import android.os.SystemClock
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Animatable
import kotlinx.coroutines.delay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.winters.cleanphotos.core.design.DelayedSpec
import com.winters.cleanphotos.core.design.LocalAnimationsEnabled
import com.winters.cleanphotos.core.design.MotionSprings
import com.winters.cleanphotos.core.design.MotionDurations
import com.winters.cleanphotos.core.design.MotionEasings

/**
 * 共享元素：照片页 / 收藏页的缩略图与清理页大图用同一个 key（照片 id）配对，
 * Navigation Compose 的 AnimatedContentScope 驱动；大图请求的 placeholderMemoryCacheKey 就是缩略图的缓存键，
 * 过程中没有空白帧也不换图。动画关闭时两个 local 都不生效，直接切页。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** 当前目的地第一次组合的时刻（uptimeMillis），控件淡入按它计时。 */
val LocalNavEntryStart = staticCompositionLocalOf { 0L }

/** 当前目的地的 AnimatedContentScope（NavHost composable 的接收者）。 */
val LocalNavAnimatedScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

private val SharedSettleEasing = MotionSprings.Settle.asEasing()

fun sharedPhotoKey(photoId: Long): String = "photo-$photoId"

/**
 * 给照片挂上共享元素。[expandsHere] = 这一侧是「大图」（清理页）：进入（80ms 后 settle 弹簧，400ms 内收住），离开（收回）用 400ms；
 * 网格一侧相反：自己重新出现时就是收回（400ms）。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedPhoto(photoId: Long, expandsHere: Boolean, enabled: Boolean = true): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val anim = LocalNavAnimatedScope.current ?: return this
    if (!enabled || !LocalAnimationsEnabled.current) return this
    return with(shared) {
        this@sharedPhoto.sharedElement(
            sharedContentState = rememberSharedContentState(sharedPhotoKey(photoId)),
            animatedVisibilityScope = anim,
            boundsTransform = { _, _ ->
                val entering = anim.transition.targetState == EnterExitState.Visible
                if (entering == expandsHere) {
                    // 决定 9：0–80ms 停在缩略图，80–400ms 按 settle 弹簧曲线放大并在 400ms 准时收住
                    // （真弹簧收到 0.5px 要 ~600ms，期间共享元素一直盖在控件上面，控件会在 600ms 硬切出现）
                    tween(MotionDurations.SHARED_EXPAND, MotionDurations.SHARED_PRESS, SharedSettleEasing)
                } else {
                    // 收回前停一个按压档（80ms）：返回瞬间要重新组合网格页，首帧很长，停住段吸收掉，几何从起点连续开始
                    DelayedSpec(MotionDurations.SHARED_PRESS, tween(MotionDurations.PHOTO_COLLAPSE - MotionDurations.SHARED_PRESS, easing = MotionEasings.Standard))
                }
            },
        )
    }
}

/** 正在被导航动画带进来（还没到位）——清理页借此跳过首帧显影，避免共享元素途中换亮度。 */
@Composable
fun isNavEntering(): Boolean {
    val t = LocalNavAnimatedScope.current?.transition ?: return false
    return t.currentState != EnterExitState.Visible && t.targetState == EnterExitState.Visible
}

/** 大图上的返回、日期、进度条：400–520ms 淡入；离开时先 100ms 淡出。 */
@Composable
fun Modifier.navChrome(): Modifier {
    val anim = LocalNavAnimatedScope.current ?: return this
    if (!LocalAnimationsEnabled.current) return this
    // 从进入这一页起 400–520ms 淡入。数据晚到（控件晚于 520ms 才组合进来）时从出现那一刻补同样 120ms 淡入，不硬切。
    val entryStart = LocalNavEntryStart.current
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        val wait = entryStart + MotionDurations.SHARED_EXPAND_END - SystemClock.uptimeMillis()
        if (wait > 0) delay(wait)
        alpha.animateTo(1f, tween(MotionDurations.SHARED_CHROME_FADE, easing = MotionEasings.Standard))
    }
    return with(anim) {
        this@navChrome.graphicsLayer { this.alpha = alpha.value }.animateEnterExit(
            enter = EnterTransition.None,
            exit = fadeOut(tween(MotionDurations.MICRO, easing = MotionEasings.Standard)),
        )
    }
}
