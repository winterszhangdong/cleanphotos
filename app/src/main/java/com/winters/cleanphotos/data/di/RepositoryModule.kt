package com.winters.cleanphotos.data.di

import com.winters.cleanphotos.data.repository.PhotoRepositoryImpl
import com.winters.cleanphotos.data.repository.ReviewRepositoryImpl
import com.winters.cleanphotos.data.repository.UserPreferencesRepositoryImpl
import com.winters.cleanphotos.domain.repository.PhotoRepository
import com.winters.cleanphotos.domain.repository.ReviewRepository
import com.winters.cleanphotos.domain.repository.UserPreferencesRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindPhotoRepository(
        impl: PhotoRepositoryImpl
    ): PhotoRepository

    @Binds
    @Singleton
    abstract fun bindReviewRepository(
        impl: ReviewRepositoryImpl
    ): ReviewRepository

    @Binds
    @Singleton
    abstract fun bindUserPreferencesRepository(
        impl: UserPreferencesRepositoryImpl
    ): UserPreferencesRepository

    @Binds
    @Singleton
    abstract fun bindSeenStateRepository(
        impl: com.winters.cleanphotos.data.repository.SeenStateRepositoryImpl
    ): com.winters.cleanphotos.domain.repository.SeenStateRepository
}
