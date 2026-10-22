package com.winters.cleanphotos.core.image

import android.content.Context
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import kotlinx.coroutines.Dispatchers
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Coil ImageLoader 的 Hilt 模块。
 *
 * 内存缓存 30%、磁盘缓存 2%。解码限在 2 个线程：大图解码是 CPU 活，
 * 和默认的线程池抢在一起会让翻页那几帧掉帧。网格缩略图用 [GridThumbFetcher]（自己关掉 Crossfade）。
 * [VideoFrameDecoder] 让清理页、小结页、垃圾桶预览里直接用 photo.uri 的 AsyncImage 能画出视频首帧。
 */
@Module
@InstallIn(SingletonComponent::class)
object ImageLoaderModule {

    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context
    ): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(VideoFrameDecoder.Factory())
                add(GridThumbFetcher.Factory())
            }
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(0.30)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.02)
                    .build()
            }
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .crossfade(false)
            .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
            .allowHardware(true)
            .fetcherDispatcher(Dispatchers.IO.limitedParallelism(2))
            .decoderDispatcher(Dispatchers.IO.limitedParallelism(2))
            .build()
    }
}
