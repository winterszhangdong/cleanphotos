package com.winters.cleanphotos.data.repository

import com.winters.cleanphotos.data.datasource.MotionPhotoDataSource
import com.winters.cleanphotos.domain.model.MediaType
import com.winters.cleanphotos.domain.model.MotionPhotoVideo
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.repository.MotionPhotoRepository
import java.util.LinkedHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MotionPhotoRepositoryImpl @Inject constructor(
    private val dataSource: MotionPhotoDataSource,
) : MotionPhotoRepository {

    /** 访问序 LRU。null 也缓存，静态图不反复读文件。读失败不写入。 */
    private val cache = LinkedHashMap<String, MotionPhotoVideo?>(CACHE_CAP, 0.75f, true)

    override suspend fun locate(photo: PhotoItem): MotionPhotoVideo? {
        if (photo.mediaType != MediaType.Image) return null
        val key = "${photo.id}:${photo.size ?: -1L}"
        synchronized(cache) {
            if (cache.containsKey(key)) return cache[key]
        }
        val found = dataSource.locate(photo.uri)
        synchronized(cache) {
            cache[key] = found
            while (cache.size > CACHE_CAP) {
                val eldest = cache.entries.iterator()
                eldest.next()
                eldest.remove()
            }
        }
        return found
    }

    private companion object {
        const val CACHE_CAP = 256
    }
}
