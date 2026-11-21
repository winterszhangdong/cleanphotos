package com.winters.cleanphotos.feature.compress

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.winters.cleanphotos.core.coroutines.ApplicationScope
import com.winters.cleanphotos.domain.model.CompressFailure
import com.winters.cleanphotos.domain.model.CompressResult
import com.winters.cleanphotos.domain.model.MediaType
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.usecase.CompressVideoUseCase
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VideoCompressState(
    val sourceId: Long? = null,
    val running: Boolean = false,
    val progressPercent: Int = 0,
    val failure: CompressFailure? = null,
    val created: PhotoItem? = null,
    /** 一次结束加一。清理页只认领没见过的序号。 */
    val generation: Long = 0,
)

interface VideoCompressController {
    val state: StateFlow<VideoCompressState>
    fun start(photo: PhotoItem)
    fun cancel()
}

/**
 * 压缩挂在进程级作用域上，离开清理页不取消。前台服务保住进程。
 * 一次只压一条。
 */
@Singleton
class ForegroundVideoCompressController @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val appScope: CoroutineScope,
    private val compressVideo: CompressVideoUseCase,
) : VideoCompressController {
    private val _state = MutableStateFlow(VideoCompressState())
    override val state: StateFlow<VideoCompressState> = _state

    private var job: Job? = null
    private var armed: PhotoItem? = null
    private var service: VideoCompressService? = null
    /** 服务还没 attach 时用户已经取消。attach 看到这个标记就停，不再开压。 */
    private var cancelledBeforeStart = false

    override fun start(photo: PhotoItem) {
        if (job?.isActive == true) return
        if (photo.mediaType != MediaType.Video || photo.isTrashed) return
        cancelledBeforeStart = false
        armed = photo
        _state.value = _state.value.copy(
            sourceId = photo.id,
            running = true,
            progressPercent = 0,
            failure = null,
            created = null,
        )
        ContextCompat.startForegroundService(context, Intent(context, VideoCompressService::class.java))
    }

    override fun cancel() {
        val running = job
        if (running == null) {
            cancelledBeforeStart = true
            armed = null
            val generation = _state.value.generation + 1
            _state.value = VideoCompressState(
                failure = CompressFailure.Cancelled,
                generation = generation,
            )
            service?.shutdown()
            return
        }
        running.cancel()
    }

    fun attach(host: VideoCompressService) {
        service = host
        val photo = armed
        if (photo == null || cancelledBeforeStart) {
            cancelledBeforeStart = false
            host.shutdown()
            return
        }
        if (job?.isActive == true) return
        job = appScope.launch {
            try {
                _state.update { it.copy(sourceId = photo.id, running = true, progressPercent = 0, failure = null, created = null) }
                val result = compressVideo(photo) { percent ->
                    _state.update { current ->
                        if (current.running && current.sourceId == photo.id) current.copy(progressPercent = percent) else current
                    }
                    host.showProgress(percent)
                }
                finish(photo.id, result)
            } catch (e: CancellationException) {
                finish(photo.id, CompressResult.Failure(CompressFailure.Cancelled))
            } catch (e: Exception) {
                Log.e(TAG, "压缩失败", e)
                finish(photo.id, CompressResult.Failure(CompressFailure.Failed))
            } finally {
                armed = null
                host.shutdown()
                if (service === host) service = null
            }
        }
    }

    private fun finish(sourceId: Long, result: CompressResult) {
        val generation = _state.value.generation + 1
        _state.value = when (result) {
            is CompressResult.Success -> VideoCompressState(
                sourceId = sourceId,
                progressPercent = 100,
                created = result.created,
                generation = generation,
            )
            is CompressResult.Failure -> VideoCompressState(
                sourceId = sourceId,
                failure = result.reason,
                generation = generation,
            )
        }
    }

    private companion object {
        const val TAG = "VideoCompress"
    }
}
