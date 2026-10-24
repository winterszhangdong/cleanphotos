package com.winters.cleanphotos.data.datasource

import android.content.Context
import android.net.Uri
import com.winters.cleanphotos.core.coroutines.IoDispatcher
import com.winters.cleanphotos.domain.model.MotionPhotoVideo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 只读打开照片，按偏移取动态照片里的视频范围。
 * open / 读 / 关整段在 [IoDispatcher]，不把文件描述符带回主线程。
 */
@Singleton
class MotionPhotoDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun locate(uri: Uri): MotionPhotoVideo? = withContext(ioDispatcher) {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("openFileDescriptor 返回 null: $uri")
        descriptor.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { input ->
                val channel = input.channel
                val source = object : RandomBytes {
                    override val size: Long = channel.size()

                    override fun read(position: Long, length: Int): ByteArray {
                        if (position < 0 || length < 0 || position + length > size) {
                            throw IOException("越界读 position=$position length=$length size=$size")
                        }
                        val buf = ByteBuffer.allocate(length)
                        var got = 0
                        while (got < length) {
                            val n = channel.read(buf, position + got)
                            if (n < 0) throw IOException("短读 position=$position length=$length got=$got")
                            got += n
                        }
                        return buf.array()
                    }
                }
                MotionPhotoLocator.locate(source)
            }
        }
    }
}
