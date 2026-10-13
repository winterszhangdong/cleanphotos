package com.winters.cleanphotos.domain.model

/**
 * 清理会话来源枚举。
 *
 * 区分清理页的入口来源，决定候选队列和手势语义：
 * - AllPhotos：从全部照片页进入，上滑 = 收藏，下滑 = 移入垃圾桶
 * - Favorites：从收藏页进入，上滑 = 取消收藏，下滑 = 移入垃圾桶
 */
enum class ReviewScope {
    AllPhotos,
    Favorites
}
