package com.winters.cleanphotos.fake

import com.winters.cleanphotos.domain.repository.SimilarResolvedRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeSimilarResolvedRepository : SimilarResolvedRepository {
    private val records = MutableStateFlow<List<Set<Long>>>(emptyList())
    val added = mutableListOf<Set<Long>>()
    val removed = mutableListOf<Set<Long>>()

    override fun observeResolved(): Flow<List<Set<Long>>> = records

    override suspend fun addResolved(ids: Set<Long>) {
        added += ids
        if (records.value.none { it == ids }) records.value = records.value + listOf(ids)
    }

    override suspend fun removeResolved(ids: Set<Long>) {
        removed += ids
        records.value = records.value.filterNot { it == ids }
    }
}
