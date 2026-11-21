package com.winters.cleanphotos.data.repository

import com.winters.cleanphotos.core.design.ApiTier
import com.winters.cleanphotos.core.media.CompressEncodeResult
import com.winters.cleanphotos.core.media.Media3VideoCompressor
import com.winters.cleanphotos.data.datasource.VideoCompressStore
import com.winters.cleanphotos.data.datasource.VideoProbe
import com.winters.cleanphotos.data.datasource.toRequest
import com.winters.cleanphotos.domain.model.CompressFailure
import com.winters.cleanphotos.domain.model.CompressVideoPlan
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.repository.TranscodeProduct
import com.winters.cleanphotos.domain.repository.VideoTranscoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class VideoTranscoderImpl @Inject constructor(
    private val store: VideoCompressStore,
    private val compressor: Media3VideoCompressor,
) : VideoTranscoder {

    override suspend fun execute(photo: PhotoItem, onProgress: (Int) -> Unit): TranscodeProduct {
        val probe = withContext(Dispatchers.IO) { store.probe(photo) }
        if (probe !is VideoProbe.Ok) return TranscodeProduct.Rejected(CompressFailure.Undecodable)
        when (val decision = CompressVideoPlan.decide(probe.toRequest(ApiTier.Current.sdkInt))) {
            CompressVideoPlan.Decision.AlreadySmall ->
                return TranscodeProduct.Rejected(CompressFailure.AlreadySmall)
            CompressVideoPlan.Decision.HdrOnOldAndroid ->
                return TranscodeProduct.Rejected(CompressFailure.HdrOnOldAndroid)
            is CompressVideoPlan.Decision.Proceed -> {
                val need = CompressVideoPlan.requiredFreeBytes(decision.plan.videoBitrate, probe.durationMs)
                val free = withContext(Dispatchers.IO) { store.availableBytes() }
                if (free < need) return TranscodeProduct.Rejected(CompressFailure.NotEnoughSpace)
                return when (val encoded = compressor.compress(
                    uri = photo.uri,
                    plan = decision.plan,
                    samsungSefSlowMotion = probe.samsungSef,
                    sdkInt = ApiTier.Current.sdkInt,
                    onProgress = onProgress,
                )) {
                    is CompressEncodeResult.Stopped -> TranscodeProduct.Rejected(encoded.failure)
                    is CompressEncodeResult.Ready -> {
                        store.hold(encoded.file, photo, decision.plan, encoded.durationMs)
                        TranscodeProduct.Encoded(
                            token = encoded.file.absolutePath,
                            outputDurationMs = encoded.durationMs,
                            originalDurationMs = probe.durationMs,
                        )
                    }
                }
            }
        }
    }

    override suspend fun publish(token: String): PhotoItem = withContext(Dispatchers.IO) { store.publish(token) }

    override suspend fun discard(token: String) {
        withContext(Dispatchers.IO) { store.discard(token) }
    }

    override suspend fun removePublished(photo: PhotoItem) {
        withContext(Dispatchers.IO) { store.removePublished(photo) }
    }
}
