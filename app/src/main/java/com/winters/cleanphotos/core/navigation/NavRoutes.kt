package com.winters.cleanphotos.core.navigation

/**
 * 应用导航路由定义。
 *
 * REVIEW 路由包含四个参数：photoId、sortOrder、filterMode、scope，
 * 确保清理页的切换顺序与入口页一致，且手势语义由 scope 决定。
 */
object NavRoutes {
    const val ALL_PHOTOS = "all_photos"
    const val SIMILAR = "similar"
    const val FAVORITES = "favorites"
    const val REVIEW = "review/{photoId}/{sortOrder}/{filterMode}/{scope}"
    const val TRASH = "trash"

    /**
     * 构造清理页路由，携带排序、过滤和 scope 上下文。
     */
    fun reviewRoute(photoId: Long, sortOrder: String, filterMode: String, scope: String): String {
        return "review/$photoId/$sortOrder/$filterMode/$scope"
    }
}
