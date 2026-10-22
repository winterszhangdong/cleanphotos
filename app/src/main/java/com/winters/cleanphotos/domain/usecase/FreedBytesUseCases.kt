package com.winters.cleanphotos.domain.usecase

import com.winters.cleanphotos.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** 垃圾桶空状态读「累计已释放」。 */
class ObserveTotalFreedBytesUseCase @Inject constructor(
    private val userPreferencesRepository: UserPreferencesRepository,
) {
    operator fun invoke(): Flow<Long> = userPreferencesRepository.observeTotalFreedBytes()
}

/** 把确认已从图库消失的字节累加进 total_freed_bytes。 */
class AddFreedBytesUseCase @Inject constructor(
    private val userPreferencesRepository: UserPreferencesRepository,
) {
    suspend operator fun invoke(bytes: Long) = userPreferencesRepository.addFreedBytes(bytes)
}
