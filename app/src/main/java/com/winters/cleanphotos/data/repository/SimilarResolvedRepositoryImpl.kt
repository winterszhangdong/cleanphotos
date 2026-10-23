package com.winters.cleanphotos.data.repository

import com.winters.cleanphotos.data.datasource.AppSimilarResolvedDataSource
import com.winters.cleanphotos.domain.repository.SimilarResolvedRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class SimilarResolvedRepositoryImpl @Inject constructor(
    private val source: AppSimilarResolvedDataSource,
) : SimilarResolvedRepository {
    override fun observeResolved(): Flow<List<Set<Long>>> = source.observeResolved()

    override suspend fun addResolved(ids: Set<Long>) = source.addResolved(ids)

    override suspend fun removeResolved(ids: Set<Long>) = source.removeResolved(ids)
}
