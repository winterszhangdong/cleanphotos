package com.winters.cleanphotos.core.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

private val PhotoGridPrefetchAhead = 640.dp
private val PhotoGridPrefetchBehind = 320.dp

/**
 * 照片网格的预取窗口：视口外再组合约一屏半，快滑时缩略图请求已经发出。
 * 默认窗口只有几百像素，快速甩动会在新行上看到灰格。
 *
 * [pausePrefetch] 为 true 时窗口收成 0（日期滑块按住的那一阵）。
 * 只影响这一次记住的网格，普通甩动仍用 640 / 320。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberPhotoGridState(pausePrefetch: (() -> Boolean)? = null): LazyGridState {
    val cacheWindow = remember(pausePrefetch) {
        if (pausePrefetch == null) {
            LazyLayoutCacheWindow(ahead = PhotoGridPrefetchAhead, behind = PhotoGridPrefetchBehind)
        } else {
            pausingPhotoGridCacheWindow(pausePrefetch)
        }
    }
    return rememberLazyGridState(cacheWindow = cacheWindow)
}

@OptIn(ExperimentalFoundationApi::class)
private fun pausingPhotoGridCacheWindow(pausePrefetch: () -> Boolean): LazyLayoutCacheWindow =
    object : LazyLayoutCacheWindow {
        override fun Density.calculateAheadWindow(viewport: Int): Int =
            if (pausePrefetch()) 0 else PhotoGridPrefetchAhead.roundToPx()

        override fun Density.calculateBehindWindow(viewport: Int): Int =
            if (pausePrefetch()) 0 else PhotoGridPrefetchBehind.roundToPx()
    }
