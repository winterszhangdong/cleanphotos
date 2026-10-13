package com.winters.cleanphotos.data.repository

import com.winters.cleanphotos.data.datasource.AppSeenStateDataSource
import com.winters.cleanphotos.domain.repository.SeenStateRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SeenStateRepositoryImpl @Inject constructor(
    private val seenStateDataSource: AppSeenStateDataSource,
) : SeenStateRepository {
    override fun observeSeenIds(): Flow<Set<Long>> = seenStateDataSource.observeSeenIds()
    override suspend fun markSeen(photoId: Long) = seenStateDataSource.markSeen(photoId)
}
