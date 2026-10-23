package com.winters.cleanphotos.domain.repository

import kotlinx.coroutines.flow.Flow

/** 已经处理过、不再拿出来比的相似组。每条是一组照片 id。 */
interface SimilarResolvedRepository {
    fun observeResolved(): Flow<List<Set<Long>>>
    suspend fun addResolved(ids: Set<Long>)
    suspend fun removeResolved(ids: Set<Long>)
}
