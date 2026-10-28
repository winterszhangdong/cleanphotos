package com.winters.cleanphotos.feature.similar

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import com.winters.cleanphotos.core.design.MotionSprings

/** 当前缩略图中心相对视口中心的位移。正值要把条往前滚。 */
internal fun filmstripScrollDelta(
    itemOffset: Int,
    itemSize: Int,
    viewportStart: Int,
    viewportEnd: Int,
): Int {
    val viewportCenter = (viewportStart + viewportEnd) / 2
    val itemCenter = itemOffset + itemSize / 2
    return itemCenter - viewportCenter
}

/**
 * 让 [LazyListState.scrollToItem] 把这一格的起点放在视口中间。
 * 负值表示相对贴左再往回让。列表比视口短，或已经到头，滚动会被夹住。
 */
internal fun filmstripCenterOffset(viewportPx: Int, itemPx: Int): Int {
    if (viewportPx <= 0 || itemPx <= 0 || itemPx >= viewportPx) return 0
    return -((viewportPx - itemPx) / 2)
}

/**
 * 下标变了才调用。当前张还在条上就用翻页同一条 snappy 滚到中间；
 * 已经滑出可视范围就立刻对准，连着滑时弹簧不会越甩越远。
 * 开着系统「移除动画」时 [animate] 为 false。
 */
internal suspend fun LazyListState.followFilmstrip(index: Int, itemPx: Int, animate: Boolean) {
    if (index < 0) return
    val info = layoutInfo
    val viewport = info.viewportEndOffset - info.viewportStartOffset
    if (viewport <= 0) return
    val visible = info.visibleItemsInfo.firstOrNull { it.index == index }
    if (visible == null) {
        scrollToItem(index, filmstripCenterOffset(viewport, itemPx))
        return
    }
    val delta = filmstripScrollDelta(
        visible.offset,
        visible.size,
        info.viewportStartOffset,
        info.viewportEndOffset,
    )
    if (delta == 0) return
    if (delta < 0 && !canScrollBackward) return
    if (delta > 0 && !canScrollForward) return
    if (animate) {
        animateScrollBy(delta.toFloat(), MotionSprings.Snappy.spec())
    } else {
        scroll { scrollBy(delta.toFloat()) }
    }
}
