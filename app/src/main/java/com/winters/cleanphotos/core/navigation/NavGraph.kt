package com.winters.cleanphotos.core.navigation

import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.winters.cleanphotos.core.design.BottomTab
import com.winters.cleanphotos.core.design.DarkroomColors
import com.winters.cleanphotos.core.design.GlassBackdropHost
import com.winters.cleanphotos.core.design.MotionDurations
import com.winters.cleanphotos.core.design.MotionEasings
import com.winters.cleanphotos.feature.allphotos.AllPhotosRoute
import com.winters.cleanphotos.feature.favorites.FavoritesRoute
import com.winters.cleanphotos.feature.review.ReviewRoute
import com.winters.cleanphotos.feature.similar.SimilarCompareRoute
import com.winters.cleanphotos.feature.trash.TrashRoute

/**
 * 导航图 + 公共层。底部导航只有一份（[AppDockBar]），画在 NavHost 之上；
 * 玻璃模糊取样当前页面自己的 GlassBackdropHost（见 [DockConfig.hazeState]）。各页面用 [AppDockEffect] 声明导航形态。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun NavGraph(
    navController: NavHostController = rememberNavController(),
    shellViewModel: AppShellViewModel = hiltViewModel(),
) {
    val dock = remember { AppDockState() }
    val entry by navController.currentBackStackEntryAsState()
    val trashCount by shellViewModel.trashCount.collectAsStateWithLifecycle()
    // 回到前台重新读图库（外部增删照片后不用重开 App）
    LifecycleResumeEffect(shellViewModel) {
        shellViewModel.onAppResumed()
        onPauseOrDispose { }
    }

    // 只有当前页发起的跳转 / 返回才执行：离场动画期间页面仍可点，连点会重复 push 或连退两层
    fun NavBackStackEntry.ifCurrent(action: () -> Unit) {
        if (navController.currentBackStackEntry?.id == id) action()
    }

    fun selectTab(tab: BottomTab) {
        val route = navController.currentDestination?.route
        when (tab) {
            BottomTab.AllPhotos -> if (route != NavRoutes.ALL_PHOTOS) navController.popBackStack(NavRoutes.ALL_PHOTOS, inclusive = false)
            BottomTab.Favorites -> when (route) {
                NavRoutes.FAVORITES -> Unit
                // 垃圾桶上切到收藏：避免在 Trash 之上无限堆 Favorites
                NavRoutes.TRASH -> navController.navigate(NavRoutes.FAVORITES) {
                    popUpTo(NavRoutes.ALL_PHOTOS) { inclusive = false }
                    launchSingleTop = true
                }
                else -> navController.navigate(NavRoutes.FAVORITES)
            }
            BottomTab.Trash -> if (route != NavRoutes.TRASH) navController.navigate(NavRoutes.TRASH)
        }
    }

    CompositionLocalProvider(LocalAppDock provides dock) {
        GlassBackdropHost {
            SharedTransitionLayout(Modifier.fillMaxSize().background(DarkroomColors.Room)) {
              CompositionLocalProvider(LocalSharedTransitionScope provides this) {
              // 横屏时系统导航栏 / 挖孔在左右两侧：页面内容整体让开（竖屏左右安全区为 0，排版不变）
              Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
                NavHost(
                    navController = navController,
                    startDestination = NavRoutes.ALL_PHOTOS,
                    modifier = Modifier.fillMaxSize(),
                    enterTransition = {
                        if (gridToReview()) {
                            BackdropSnapshot.returningFromGesture = false
                            photoEnter()
                        } else EnterTransition.None
                    },
                    exitTransition = { if (gridToReview()) gridRecede() else ExitTransition.None },
                    popEnterTransition = { if (reviewToGrid()) gridReturn() else EnterTransition.None },
                    popExitTransition = { if (reviewToGrid()) photoExit() else ExitTransition.None },
                ) {
                    composable(NavRoutes.ALL_PHOTOS) { e ->
                        DockEntry(e, this) { capture ->
                            AllPhotosRoute(
                                onNavigateToReview = { photoId, sortOrder, filterMode, unseenOnly ->
                                    e.ifCurrent {
                                        capture()
                                        navController.navigate(
                                            NavRoutes.reviewRoute(photoId, sortOrder, filterMode, "AllPhotos", unseenOnly),
                                        )
                                    }
                                },
                                onNavigateToTrash = { selectTab(BottomTab.Trash) },
                                onNavigateToSimilar = { groupKey, photoId ->
                                    e.ifCurrent {
                                        navController.navigate(NavRoutes.similarCompareRoute(groupKey, photoId))
                                    }
                                },
                            )
                        }
                    }

                    composable(NavRoutes.FAVORITES) { e ->
                        DockEntry(e, this) { capture ->
                            FavoritesRoute(
                                onNavigateToReview = { photoId, sortOrder ->
                                    e.ifCurrent {
                                        capture()
                                        navController.navigate(NavRoutes.reviewRoute(photoId, sortOrder, "AllPhotos", "Favorites"))
                                    }
                                },
                                onNavigateBack = { e.ifCurrent { navController.popBackStack() } },
                            )
                        }
                    }

                    composable(
                        route = NavRoutes.REVIEW,
                        arguments = listOf(
                            navArgument("photoId") { type = NavType.LongType },
                            navArgument("sortOrder") { type = NavType.StringType },
                            navArgument("filterMode") { type = NavType.StringType },
                            navArgument("scope") { type = NavType.StringType },
                            navArgument("unseenOnly") {
                                type = NavType.BoolType
                                defaultValue = false
                            },
                        ),
                    ) { e ->
                        val args = requireNotNull(e.arguments)
                        DockEntry(e, this) { _ ->
                            ReviewRoute(
                                photoId = args.getLong("photoId"),
                                sortOrder = requireNotNull(args.getString("sortOrder")),
                                filterMode = requireNotNull(args.getString("filterMode")),
                                scope = requireNotNull(args.getString("scope")),
                                unseenOnly = args.getBoolean("unseenOnly"),
                                onNavigateBack = { e.ifCurrent { navController.popBackStack() } },
                            )
                        }
                    }

                    composable(
                        route = NavRoutes.SIMILAR_COMPARE,
                        arguments = listOf(
                            navArgument("groupKey") { type = NavType.StringType },
                            navArgument("photoId") { type = NavType.LongType },
                        ),
                    ) { e ->
                        val args = requireNotNull(e.arguments)
                        val parent = remember(e) { navController.getBackStackEntry(NavRoutes.ALL_PHOTOS) }
                        DockEntry(e, this) { _ ->
                            SimilarCompareRoute(
                                groupKey = requireNotNull(args.getString("groupKey")),
                                photoId = args.getLong("photoId"),
                                onBack = { e.ifCurrent { navController.popBackStack() } },
                                parentEntry = parent,
                            )
                        }
                    }

                    composable(NavRoutes.TRASH) { e ->
                        DockEntry(e, this) { _ ->
                            TrashRoute(
                                onNavigateBack = { e.ifCurrent { navController.popBackStack() } },
                            )
                        }
                    }
                }

                val current = entry
                AppDockBar(
                    dock = dock,
                    config = current?.let { dock.configs[it.id] },
                    selected = current?.let(::tabFor) ?: BottomTab.AllPhotos,
                    trashCount = trashCount,
                    onSelect = ::selectTab,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
              }
              }
            }
        }
    }
}

@Composable
private fun DockEntry(entry: NavBackStackEntry, scope: AnimatedContentScope, content: @Composable (capture: () -> Unit) -> Unit) {
    val recorder = rememberBackdropRecorder()
    val entryStart = remember { android.os.SystemClock.uptimeMillis() }
    CompositionLocalProvider(LocalDockEntryId provides entry.id, LocalNavAnimatedScope provides scope, LocalNavEntryStart provides entryStart) {
        // 只在离开前那一帧录进 GraphicsLayer。每帧录整页会让滚动把所有可见格子重画进全屏缓冲。
        var capture by remember { mutableStateOf(false) }
        Box(
            Modifier
                .fillMaxSize()
                .captureBackdropOnce(recorder, capture) { capture = false },
        ) {
            content { capture = true }
        }
    }
}

private val GRID_ROUTES = setOf(NavRoutes.ALL_PHOTOS, NavRoutes.FAVORITES)

private fun AnimatedContentTransitionScope<NavBackStackEntry>.gridToReview() =
    initialState.destination.route in GRID_ROUTES && targetState.destination.route == NavRoutes.REVIEW

private fun AnimatedContentTransitionScope<NavBackStackEntry>.reviewToGrid() =
    initialState.destination.route == NavRoutes.REVIEW && targetState.destination.route in GRID_ROUTES

/* 大图层（暗房底）随缩略图长大淡入；照片页退后到 0.96、亮度 0.25（在暗房底上用透明度表达）。收回反过来。 */
private fun photoEnter() = fadeIn(tween(MotionDurations.SHARED_EXPAND, MotionDurations.SHARED_PRESS, MotionEasings.Standard))
private fun photoExit() = fadeOut(tween(MotionDurations.PHOTO_COLLAPSE, easing = MotionEasings.Standard))
private fun gridRecede() =
    fadeOut(tween(MotionDurations.SHARED_EXPAND, MotionDurations.SHARED_PRESS, MotionEasings.Standard), targetAlpha = GRID_RECEDE_BRIGHTNESS) +
        scaleOut(tween(MotionDurations.SHARED_EXPAND, MotionDurations.SHARED_PRESS, MotionEasings.Standard), targetScale = GRID_RECEDE_SCALE)
private fun gridReturn() = if (BackdropSnapshot.returningFromGesture) {
    // 拖动时后面已经是 0.6 亮度的原尺寸网格快照，接着从这里亮起来
    fadeIn(tween(MotionDurations.PHOTO_COLLAPSE, easing = MotionEasings.Standard), initialAlpha = GESTURE_RETURN_BRIGHTNESS)
} else {
    fadeIn(tween(MotionDurations.PHOTO_COLLAPSE, easing = MotionEasings.Standard), initialAlpha = GRID_RECEDE_BRIGHTNESS) +
        scaleIn(tween(MotionDurations.PHOTO_COLLAPSE, easing = MotionEasings.Standard), initialScale = GRID_RECEDE_SCALE)
}

private const val GRID_RECEDE_SCALE = 0.96f
private const val GRID_RECEDE_BRIGHTNESS = 0.25f
private const val GESTURE_RETURN_BRIGHTNESS = 0.6f

/** 当前目的地对应的选中项；清理页按入口 scope（从收藏进入时是「收藏」）。 */
private fun tabFor(entry: NavBackStackEntry): BottomTab = when (entry.destination.route) {
    NavRoutes.FAVORITES -> BottomTab.Favorites
    NavRoutes.TRASH -> BottomTab.Trash
    NavRoutes.REVIEW -> if (entry.arguments?.getString("scope") == "Favorites") BottomTab.Favorites else BottomTab.AllPhotos
    else -> BottomTab.AllPhotos
}
