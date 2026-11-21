package com.winters.cleanphotos.feature.compress

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CompressModule {
    @Binds
    @Singleton
    abstract fun bindVideoCompressController(impl: ForegroundVideoCompressController): VideoCompressController
}
