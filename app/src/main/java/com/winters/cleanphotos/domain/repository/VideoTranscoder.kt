package com.winters.cleanphotos.domain.repository

import com.winters.cleanphotos.domain.model.CompressFailure
import com.winters.cleanphotos.domain.model.PhotoItem

/**
 * 读轨道、按计划转码、把成品写入相册。
 * 网格查询不加格式字段；轨道在开压时读。
 */
interface VideoTranscoder {
    suspend fun execute(photo: PhotoItem, onProgress: (Int) -> Unit): TranscodeProduct
    suspend fun publish(token: String): PhotoItem
    suspend fun discard(token: String)
    suspend fun removePublished(photo: PhotoItem)
}

sealed interface TranscodeProduct {
    data class Encoded(
        val token: String,
        val outputDurationMs: Long,
        val originalDurationMs: Long,
    ) : TranscodeProduct

    data class Rejected(val failure: CompressFailure) : TranscodeProduct
    data object Cancelled : TranscodeProduct
}
