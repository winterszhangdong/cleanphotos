package com.winters.cleanphotos.core.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * 照片网格的预取窗口：视口外再组合约一屏半，快滑时缩略图请求已经发出。
 * 默认窗口只有几百像素，快速甩动会在新行上看到灰格。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberPhotoGridState(): LazyGridState =
    rememberLazyGridState(cacheWindow = LazyLayoutCacheWindow(ahead = 640.dp, behind = 320.dp))
