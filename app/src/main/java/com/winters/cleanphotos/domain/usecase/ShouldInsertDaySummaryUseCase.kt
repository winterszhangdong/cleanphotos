package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.domain.model.DayKey
import com.winters.cleanphotos.domain.model.PhotoSortOrder
import com.winters.cleanphotos.domain.model.ReviewRules
import com.winters.cleanphotos.domain.model.ReviewLedger
import javax.inject.Inject

/**
 * 决定 2：是否在两天之间插入小结插页。四个条件同时满足才插入：
 * 1. 从 fromPhoto 前进到 toPhoto（会话顺序向后），且跨了天；往回滑不触发；
 *    toPhotoId 为 null 表示会话已走到末尾（最后一天处理完），也算「离开」；
 * 2. 离开的那一天在本次会话里全部看过或处理过（跳过的算保留）；
 * 3. 离开的那一天会话内至少 6 张；
 * 4. 同一天在一个会话里最多出现一次小结；
 * 5. 没有日期（DayKey.UNKNOWN）的照片不生成小结（data-model.md）。
 *
 * 调用方必须先把 fromPhoto 的看过 / 处理结果写进 ledger 再调用。
 */
class ShouldInsertDaySummaryUseCase @Inject constructor() {
    operator fun invoke(ledger: ReviewLedger, fromPhotoId: Long, toPhotoId: Long?): Boolean {
        if (ledger.sortOrder == PhotoSortOrder.LargestFirst) return false
        val from = ledger.photo(fromPhotoId)
        if (toPhotoId != null) {
            val to = ledger.photo(toPhotoId)
            if (ledger.indexOf(toPhotoId) <= ledger.indexOf(fromPhotoId)) return false
            if (to.dateGroup == from.dateGroup) return false
        }
        val day = from.dateGroup
        if (day == DayKey.UNKNOWN) return false
        if (day in ledger.summarizedDays) return false
        val photos = ledger.photosOfDay(day)
        if (photos.size < ReviewRules.DAY_SUMMARY_MIN_PHOTOS) return false
        return photos.all { ledger.isTouched(it.id) }
    }
}
