package com.winters.cleanphotos.data.repository

import android.util.Log
import com.winters.cleanphotos.data.datasource.PhotoHashDataSource
import com.winters.cleanphotos.data.datasource.PhotoSharpnessDataSource
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.SimilarHashProgress
import com.winters.cleanphotos.domain.model.SimilarRules
import com.winters.cleanphotos.domain.model.SimilarSharpnessProgress
import com.winters.cleanphotos.domain.repository.SimilarPhotoRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

@Singleton
class SimilarPhotoRepositoryImpl @Inject constructor(
    private val photoHashDataSource: PhotoHashDataSource,
    private val photoSharpnessDataSource: PhotoSharpnessDataSource,
) : SimilarPhotoRepository {

    private val hashDispatcher = Dispatchers.IO.limitedParallelism(SimilarRules.HASH_PARALLELISM)

    override fun hashPhotos(photos: List<PhotoItem>): Flow<SimilarHashProgress> = channelFlow {
        if (photos.isEmpty()) {
            send(SimilarHashProgress(0, 0, emptyMap(), finished = true))
            return@channelFlow
        }
        val total = photos.size
        val acc = ConcurrentHashMap<Long, Long>()
        val done = AtomicInteger(0)
        send(SimilarHashProgress(0, total, emptyMap(), finished = false))
        coroutineScope {
            photos.forEach { photo ->
                launch {
                    // 单张失败只跳过：不让这一张的异常取消整次比对，进度也一定能走到头
                    val hash = try {
                        photoHashDataSource.dHash(photo)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "dHash 失败 id=${photo.id}", e)
                        null
                    }
                    if (hash != null) acc[photo.id] = hash
                    val n = done.incrementAndGet()
                    if (n == total) {
                        send(SimilarHashProgress(n, total, HashMap(acc), finished = true))
                    } else {
                        send(SimilarHashProgress(n, total, emptyMap(), finished = false))
                    }
                }
            }
        }
    }.flowOn(hashDispatcher)

    override fun measureSharpness(photos: List<PhotoItem>): Flow<SimilarSharpnessProgress> = channelFlow {
        if (photos.isEmpty()) {
            send(SimilarSharpnessProgress(0, 0, emptyMap(), finished = true))
            return@channelFlow
        }
        val total = photos.size
        val acc = ConcurrentHashMap<Long, Double>()
        val done = AtomicInteger(0)
        send(SimilarSharpnessProgress(0, total, emptyMap(), finished = false))
        coroutineScope {
            photos.forEach { photo ->
                launch {
                    val score = try {
                        photoSharpnessDataSource.sharpness(photo)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "清晰度失败 id=${photo.id}", e)
                        null
                    }
                    if (score != null) acc[photo.id] = score
                    val n = done.incrementAndGet()
                    if (n == total) {
                        send(SimilarSharpnessProgress(n, total, HashMap(acc), finished = true))
                    } else {
                        send(SimilarSharpnessProgress(n, total, emptyMap(), finished = false))
                    }
                }
            }
        }
    }.flowOn(hashDispatcher)

    private companion object {
        const val TAG = "SimilarPhotoRepository"
    }
}
