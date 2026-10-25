package com.winters.cleanphotos.core.media

import android.content.Context
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.winters.cleanphotos.domain.model.PlaybackTarget
import com.winters.cleanphotos.domain.model.poseAfterUserSeek
import com.winters.cleanphotos.domain.model.videoEndedPose

/** 进度条轮询间隔。不是动效 token，不放进 Motion.kt。 */
internal const val PLAYBACK_POSITION_POLL_MS = 200L

/**
 * 清理页 / 垃圾桶预览各持有一个播放器。它是渲染器，跟 Coil 一样留在界面层，不进 ViewModel。
 * 必须是 TextureView：离场、圆角和色调都画在 graphicsLayer 上。
 */
@OptIn(UnstableApi::class)
class PlaybackEngine(context: Context) {
    private val appContext = context.applicationContext
    private val player: ExoPlayer = ExoPlayer.Builder(appContext).build().apply {
        videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        playWhenReady = false
    }
    private var released = false
    private var loadedKey: String? = UNSET
    private var appliedMute: Boolean? = null

    var media by mutableStateOf<PlaybackTarget?>(null)
        private set
    var firstFrame by mutableStateOf(false)
        private set
    var ended by mutableStateOf(false)
        private set
    var userPaused by mutableStateOf(false)
        private set
    /** 自动播放、用户点播放或动态照片重播之后为 true。手势暂停不清它。 */
    var started by mutableStateOf(false)
        private set
    var playing by mutableStateOf(false)
        private set
    var resumed by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    /** 动态照片播完后淡出视频层。只在 graphicsLayer 里读。 */
    var fadeNow by mutableFloatStateOf(1f)
        private set
    var generation by mutableIntStateOf(0)
        private set
    /**
     * 显示用宽高比（已算进像素比）。NaN 表示还没拿到尺寸。
     * 布局要用它，所以在组合里读；alpha 仍只在 graphicsLayer 里读。
     */
    var videoAspect by mutableFloatStateOf(Float.NaN)
        private set

    private val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            firstFrame = true
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            val width = videoSize.width
            val height = videoSize.height
            if (width <= 0 || height <= 0) {
                videoAspect = Float.NaN
                return
            }
            val pixelRatio = videoSize.pixelWidthHeightRatio.takeIf { it > 0f } ?: 1f
            // Media3 1.11 已在解码端转正，宽高就是显示方向
            videoAspect = width * pixelRatio / height
        }

        override fun onPlaybackStateChanged(state: Int) {
            val current = media
            if (state == Player.STATE_ENDED && current is PlaybackTarget.Video && !ended) {
                // 先暂停再 seek，避免 seek 回到 0 时 playWhenReady 仍为 true 被看成循环。
                // ended 保持到下一次真正开播，READY 不清它。
                val pose = videoEndedPose()
                ended = true
                playing = false
                player.pause()
                player.seekTo(pose.positionMs)
                positionMs = pose.positionMs
                val duration = player.duration
                if (duration > 0L) durationMs = duration
            } else if (state == Player.STATE_ENDED && current is PlaybackTarget.MotionPhoto) {
                ended = true
                playing = false
                refreshPosition(allowPosition = true)
            } else {
                refreshPosition(allowPosition = true)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playing = isPlaying
        }
    }

    init {
        player.addListener(listener)
    }

    /** 同一个目标重复调用不重新准备。组合阶段调用，避免交接那一帧还带着上一张的首帧标记。 */
    fun ensureLoaded(target: PlaybackTarget?) {
        if (released) return
        val key = targetKey(target)
        if (key == loadedKey) return
        loadedKey = key
        generation += 1
        firstFrame = false
        ended = false
        userPaused = false
        started = false
        playing = false
        fadeNow = 1f
        videoAspect = Float.NaN
        positionMs = 0L
        durationMs = 0L
        media = target
        player.stop()
        player.clearMediaItems()
        player.playWhenReady = false
        when (target) {
            null -> Unit
            is PlaybackTarget.Video -> {
                player.repeatMode = Player.REPEAT_MODE_OFF
                player.setMediaItem(MediaItem.fromUri(target.uri))
                player.prepare()
            }
            is PlaybackTarget.MotionPhoto -> {
                player.repeatMode = Player.REPEAT_MODE_OFF
                val factory = ResolvingDataSource.Factory(DefaultDataSource.Factory(appContext)) { spec ->
                    spec.subrange(target.video.offset, target.video.length)
                }
                player.setMediaSource(
                    ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(target.uri)),
                )
                player.prepare()
            }
        }
    }

    fun ensureMuted(muted: Boolean) {
        // 动态照片恒静音，但不改调用方记住的静音开关，切回视频时仍用用户的选择。
        val effective = muted || media is PlaybackTarget.MotionPhoto
        if (released || appliedMute == effective) return
        appliedMute = effective
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        if (effective) {
            player.volume = 0f
            player.setAudioAttributes(attrs, false)
        } else {
            player.setAudioAttributes(attrs, true)
            player.volume = 1f
        }
    }

    fun onHostStop() {
        resumed = false
        if (!released) player.pause()
    }

    fun onHostStart() {
        resumed = true
    }

    fun pauseForGesture() {
        if (!released) player.pause()
    }

    fun playFromAutoplay() {
        if (released || userPaused || ended) return
        started = true
        player.play()
    }

    fun togglePlay() {
        if (released) return
        if (player.isPlaying) {
            userPaused = true
            player.pause()
        } else {
            userPaused = false
            started = true
            if (ended) {
                ended = false
                fadeNow = 1f
                player.seekTo(0)
            }
            player.play()
        }
    }

    /** 动态照片胶囊：从头再播，视频层立刻回来。 */
    fun replayMotion() {
        if (released) return
        userPaused = false
        started = true
        ended = false
        fadeNow = 1f
        generation += 1
        player.seekTo(0)
        player.play()
    }

    fun seekFraction(fraction: Float) {
        if (released) return
        val duration = player.duration
        if (duration <= 0L) return
        // 先改成播放态再 seek。拖到结尾若同步进入 STATE_ENDED，监听器会暂停并回到第 0 帧，这时不再 play。
        val pose = poseAfterUserSeek()
        ended = pose.ended
        userPaused = pose.userPaused
        started = pose.started
        val at = (duration * fraction.coerceIn(0f, 1f)).toLong()
        player.seekTo(at)
        if (ended) return
        positionMs = at
        player.play()
    }

    fun refreshPosition(allowPosition: Boolean) {
        if (released) return
        if (allowPosition) positionMs = player.currentPosition.coerceAtLeast(0L)
        val duration = player.duration
        if (duration > 0L) durationMs = duration
    }

    fun setFade(value: Float) {
        fadeNow = value
    }

    fun attach(view: TextureView) {
        if (!released) player.setVideoTextureView(view)
    }

    fun detach(view: TextureView) {
        if (!released) player.clearVideoTextureView(view)
    }

    fun release() {
        if (released) return
        released = true
        player.removeListener(listener)
        player.release()
    }

    private fun targetKey(target: PlaybackTarget?): String? = when (target) {
        null -> null
        is PlaybackTarget.Video -> "v:${target.uri}"
        is PlaybackTarget.MotionPhoto -> "m:${target.uri}:${target.video.offset}:${target.video.length}"
    }

    private companion object {
        const val UNSET = "\u0000unset"
    }
}

@Composable
fun rememberPlaybackEngine(): PlaybackEngine {
    val context = LocalContext.current
    val engine = remember { PlaybackEngine(context.applicationContext) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, engine) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> engine.onHostStop()
                Lifecycle.Event.ON_START -> engine.onHostStart()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) engine.onHostStart()
        onDispose {
            lifecycle.removeObserver(observer)
            engine.release()
        }
    }
    return engine
}

/**
 * 在 [modifier] 给出的区域内按视频自己的比例居中。拿到尺寸之前 alpha = 0，避免拉伸和黑帧。
 * alpha 只在 graphicsLayer 里读。未应用的旋转画在 TextureView 的变换上，和 PlayerView 同一套。
 */
@Composable
fun PlaybackSurface(engine: PlaybackEngine, modifier: Modifier = Modifier) {
    val aspect = engine.videoAspect
    val sized = if (aspect.isFinite() && aspect > 0f) Modifier.aspectRatio(aspect) else Modifier.fillMaxSize()
    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    isOpaque = false
                    engine.attach(this)
                }
            },
            onRelease = { view -> engine.detach(view) },
            modifier = sized.graphicsLayer {
                val ready = engine.firstFrame && engine.videoAspect.let { it.isFinite() && it > 0f }
                alpha = if (ready) engine.fadeNow else 0f
            },
        )
    }
}
