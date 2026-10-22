package com.winters.cleanphotos.core.navigation

/**
 * 应用导航路由定义。
 *
 * REVIEW 路由包含 photoId、sortOrder、filterMode、scope，以及可选的 unseenOnly。
 * 确保清理页的切换顺序与入口页一致，且手势语义由 scope 决定。
 * unseenOnly 默认 false，收藏页不传时不叠加「只看没看过」。
 */
object NavRoutes {
    const val ALL_PHOTOS = "all_photos"
    const val FAVORITES = "favorites"
    const val REVIEW = "review/{photoId}/{sortOrder}/{filterMode}/{scope}?unseenOnly={unseenOnly}"
    const val TRASH = "trash"

    /**
     * 构造清理页路由，携带排序、过滤、scope，以及「只看没看过」。
     */
    fun reviewRoute(
        photoId: Long,
        sortOrder: String,
        filterMode: String,
        scope: String,
        unseenOnly: Boolean = false,
    ): String {
        return "review/$photoId/$sortOrder/$filterMode/$scope?unseenOnly=$unseenOnly"
    }
}
