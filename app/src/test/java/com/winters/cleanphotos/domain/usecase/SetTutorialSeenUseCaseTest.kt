package com.winters.cleanphotos.domain.usecase

import app.cash.turbine.test
import com.winters.cleanphotos.fake.FakeUserPreferencesRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SetTutorialSeenUseCaseTest {
    @Test
    fun `调用后教学状态变为 true`() = runTest {
        val repo = FakeUserPreferencesRepository(false)
        val useCase = SetTutorialSeenUseCase(repo)
        useCase()
        repo.observeHasSeenReviewTutorial().test {
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
