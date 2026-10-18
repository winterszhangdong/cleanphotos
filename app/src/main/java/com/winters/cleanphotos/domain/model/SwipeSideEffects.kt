package com.winters.cleanphotos.domain.model

/**
 * 清理页一次上下滑附带的写入：下一张记为看过、滑动计数（方向提示用）。
 * 和操作本身合进同一次 DataStore edit，一次滑动只重写一次文件、只触发一轮全库重算。
 */
data class SwipeSideEffects(
    val markSeenId: Long? = null,
    val countSwipe: Boolean = false,
) {
    companion object {
        val None = SwipeSideEffects()
    }
}
