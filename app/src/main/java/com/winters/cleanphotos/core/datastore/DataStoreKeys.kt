package com.winters.cleanphotos.core.datastore

object DataStoreKeys {
    const val PREFERENCES_NAME = "cleanphotos_preferences"
    const val HAS_SEEN_REVIEW_TUTORIAL = "has_seen_review_tutorial"
    const val HAS_MIGRATED_FAVORITES = "has_migrated_favorites"
    const val REVIEW_SWIPE_ACTION_COUNT = "review_swipe_action_count"
    /** 上次在照片页出现垃圾桶提醒的时间（epoch ms）。没有这条表示从没提醒过。 */
    const val TRASH_REMINDER_SHOWN_AT = "trash_reminder_shown_at"
}
