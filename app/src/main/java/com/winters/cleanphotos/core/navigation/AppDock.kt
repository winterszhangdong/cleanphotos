package com.winters.cleanphotos.core.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.winters.cleanphotos.core.design.BottomTab
import com.winters.cleanphotos.core.design.LocalGlassHazeState
import dev.chrisbanes.haze.HazeState
import com.winters.cleanphotos.core.design.MotionDurations
import com.winters.cleanphotos.core.design.MotionEasings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 共享底部导航的显示方式。导航胶囊只有一份，放在 NavHost 之上的公共层，
 * 所以切页时下划线真的滑过去、垃圾桶计数跨页滚动。
 */
enum class DockMode {
    /** 照片 / 收藏 / 垃圾桶网格、多选：常驻、可点。 */
    Visible,
    /** 授权页、垃圾桶预览等：不显示。 */
    Hidden,
}

/** 页面盖在导航上的遮罩（菜单压暗 48%、对话框压暗 64%）：导航在公共层，要由导航自己画同色遮罩并把点击交给页面关闭。 */
data class DockScrim(val color: Color, val enterMs: Int, val exitMs: Int, val onDismiss: () -> Unit)

/**
 * @param hazeState 导航玻璃模糊的来源：当前页面自己的 [GlassBackdropHost] 状态（页面内容是 hazeSource）。
 * 导航在公共层、和页面是兄弟节点，按 Haze 的兄弟模式取样。不在 NavHost 外层再包一层 hazeSource——
 * 嵌套 hazeSource 在清理页会把下半屏画成白色（P5 实测）。
 */
data class DockConfig(val mode: DockMode, val scrim: DockScrim? = null, val hazeState: HazeState? = null)

@Stable
class AppDockState {
    /** 每个目的地（backStackEntry id）各自登记的配置；导航只看当前目的地那一份，切页时两页同时在组合里也不会互相覆盖。 */
    internal val configs = mutableStateMapOf<String, DockConfig>()
}

val LocalAppDock = staticCompositionLocalOf<AppDockState> { error("AppDock 只能在 NavGraph 内使用") }

/** 当前目的地的 backStackEntry id，由 NavGraph 为每个目的地提供。 */
val LocalDockEntryId = staticCompositionLocalOf<String> { error("AppDock 只能在 NavGraph 的目的地里使用") }

/** 页面声明自己需要的导航形态。 */
@Composable
fun AppDockEffect(mode: DockMode, scrim: DockScrim? = null) {
    val config = DockConfig(mode, scrim, LocalGlassHazeState.current)
    val dock = LocalAppDock.current
    val id = LocalDockEntryId.current
    // 每次组合都写入最新配置（遮罩回调可能变化）；离开组合时移除
    val latestDismiss = rememberUpdatedState(config.scrim?.onDismiss)
    val stableDismiss = remember { { latestDismiss.value?.invoke(); Unit } }
    val stable = config.copy(scrim = config.scrim?.copy(onDismiss = stableDismiss))
    SideEffect { if (dock.configs[id] != stable) dock.configs[id] = stable }
    DisposableEffect(id) { onDispose { dock.configs.remove(id) } }
}
