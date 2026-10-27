package com.winters.cleanphotos.data.di

import com.winters.cleanphotos.data.repository.MotionPhotoRepositoryImpl
import com.winters.cleanphotos.data.repository.PhotoRepositoryImpl
import com.winters.cleanphotos.data.repository.ReviewRepositoryImpl
import com.winters.cleanphotos.data.repository.SimilarPhotoRepositoryImpl
import com.winters.cleanphotos.data.repository.SimilarResolvedRepositoryImpl
import com.winters.cleanphotos.data.repository.UserPreferencesRepositoryImpl
import com.winters.cleanphotos.domain.repository.MotionPhotoRepository
import com.winters.cleanphotos.domain.repository.PhotoRepository
import com.winters.cleanphotos.domain.repository.ReviewRepository
import com.winters.cleanphotos.domain.repository.SimilarPhotoRepository
import com.winters.cleanphotos.domain.repository.SimilarResolvedRepository
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

    @Binds
    @Singleton
    abstract fun bindLanguageRepository(
        impl: com.winters.cleanphotos.data.repository.LanguageRepositoryImpl
    ): com.winters.cleanphotos.domain.repository.LanguageRepository

    @Binds
    @Singleton
    abstract fun bindSimilarPhotoRepository(
        impl: SimilarPhotoRepositoryImpl
    ): SimilarPhotoRepository

    @Binds
    @Singleton
    abstract fun bindSimilarResolvedRepository(
        impl: SimilarResolvedRepositoryImpl
    ): SimilarResolvedRepository

    @Binds
    @Singleton
    abstract fun bindMotionPhotoRepository(
        impl: MotionPhotoRepositoryImpl
    ): MotionPhotoRepository

    @Binds
    @Singleton
    abstract fun bindAlbumRepository(
        impl: com.winters.cleanphotos.data.repository.AlbumRepositoryImpl
    ): com.winters.cleanphotos.domain.repository.AlbumRepository
}
