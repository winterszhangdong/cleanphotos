package com.winters.cleanphotos.core.navigation

import android.net.Uri

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
    /** groupKey 含逗号，导航前要编码。 */
    const val SIMILAR_COMPARE = "similar/{groupKey}/{photoId}"
    /** 印样表：收藏里某一天。dayKey 含空格和逗号，导航前要编码。 */
    const val CONTACT_SHEET = "contact_sheet/{dayKey}"

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

    fun similarCompareRoute(groupKey: String, photoId: Long): String =
        "similar/${Uri.encode(groupKey)}/$photoId"

    fun contactSheetRoute(dayKey: String): String = "contact_sheet/${Uri.encode(dayKey)}"
}
