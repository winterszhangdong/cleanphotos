package com.winters.cleanphotos.domain.usecase

import app.cash.turbine.test
import com.winters.cleanphotos.fake.FakeUserPreferencesRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ObserveTutorialStateUseCaseTest {
    @Test
    fun `默认未看过教学`() = runTest {
        val useCase = ObserveTutorialStateUseCase(FakeUserPreferencesRepository(false))
        useCase().test {
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `已看过教学返回 true`() = runTest {
        val useCase = ObserveTutorialStateUseCase(FakeUserPreferencesRepository(true))
        useCase().test {
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
