package com.winters.cleanphotos.core.media

import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import com.winters.cleanphotos.R
import com.winters.cleanphotos.core.design.DarkroomColors
import com.winters.cleanphotos.core.design.DarkroomIcons
import com.winters.cleanphotos.core.design.Dimens
import com.winters.cleanphotos.core.design.LocalAnimationsEnabled
import com.winters.cleanphotos.core.design.MotionDurations
import com.winters.cleanphotos.core.design.MotionEasings
import com.winters.cleanphotos.core.util.DurationFormatter
import com.winters.cleanphotos.domain.model.PlaybackKind
import com.winters.cleanphotos.domain.model.PlaybackTarget
import com.winters.cleanphotos.domain.model.playbackPolicy
import kotlinx.coroutines.delay

/**
 * 落定且没有离场占着播放器时，等 [MotionDurations.PLAYBACK_START_DELAY] 再播。
 * 系统关闭动画则不自动播；循环与动画开关无关。
 */
@Composable
fun PlaybackAutoplay(engine: PlaybackEngine, settled: Boolean) {
    val animations = LocalAnimationsEnabled.current
    val media = engine.media
    LaunchedEffect(settled) {
        if (!settled) engine.pauseForGesture()
    }
    LaunchedEffect(media, settled, animations, engine.resumed, engine.userPaused, engine.ended, engine.generation) {
        if (!settled || !engine.resumed || media == null) return@LaunchedEffect
        val kind = when (media) {
            is PlaybackTarget.Video -> PlaybackKind.Video
            is PlaybackTarget.MotionPhoto -> PlaybackKind.MotionPhoto
        }
        val policy = playbackPolicy(kind, animations)
        if (!policy.autoplay || engine.userPaused || engine.ended) return@LaunchedEffect
        delay(MotionDurations.PLAYBACK_START_DELAY.toLong())
        engine.playFromAutoplay()
    }
    LaunchedEffect(engine.generation, engine.ended, media) {
        if (media is PlaybackTarget.MotionPhoto && engine.ended) {
            val start = engine.fadeNow
            val anim = androidx.compose.animation.core.Animatable(start)
            anim.animateTo(0f, tween(MotionDurations.SHORT, easing = MotionEasings.Standard)) {
                engine.setFade(value)
            }
        } else {
            engine.setFade(1f)
        }
    }
}

/**
 * 详情 / 预览元信息上的控件。进度条在这里消费拖动，不进照片的 pointerInput。
 * 颜色只用相纸白。
 */
@Composable
fun PlaybackControls(
    engine: PlaybackEngine,
    target: PlaybackTarget?,
    muted: Boolean,
    onMutedChange: (Boolean) -> Unit,
    timeStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    if (target == null) return
    val playing = engine.playing
    var scrub by remember { mutableFloatStateOf(Float.NaN) }
    val scrubbing = !scrub.isNaN()
    LaunchedEffect(playing, scrubbing, engine.generation) {
        engine.refreshPosition(allowPosition = !scrubbing)
        while (playing && !scrubbing) {
            delay(PLAYBACK_POSITION_POLL_MS)
            engine.refreshPosition(allowPosition = true)
        }
    }
    val duration = engine.durationMs
    val position = if (scrubbing) (duration * scrub).toLong() else engine.positionMs
    val fraction = when {
        scrubbing -> scrub
        duration > 0L -> (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        else -> 0f
    }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            when (target) {
                is PlaybackTarget.Video -> {
                    IconButton(onClick = engine::togglePlay, modifier = Modifier.size(Dimens.ToolButton)) {
                        Icon(
                            imageVector = if (playing) DarkroomIcons.Pause else DarkroomIcons.Play,
                            contentDescription = stringResource(if (playing) R.string.cd_playback_pause else R.string.cd_playback_play),
                            tint = DarkroomColors.Paper,
                            modifier = Modifier.size(Dimens.ToolIcon),
                        )
                    }
                    SoundButton(muted, onMutedChange)
                    Column(Modifier.weight(1f).padding(start = Dimens.NavItemGap)) {
                        ProgressTrack(fraction) { next ->
                            if (next.isNaN()) {
                                if (!scrub.isNaN()) engine.seekFraction(scrub)
                                scrub = Float.NaN
                            } else {
                                scrub = next
                            }
                        }
                        androidx.compose.material3.Text(
                            text = DurationFormatter.format(position.coerceAtLeast(0L)) + " / " + DurationFormatter.format(duration.coerceAtLeast(0L)),
                            style = timeStyle,
                            maxLines = 1,
                        )
                    }
                }
                is PlaybackTarget.MotionPhoto -> {
                    MotionTag(engine)
                    SoundButton(muted, onMutedChange)
                }
            }
        }
    }
}

@Composable
private fun SoundButton(muted: Boolean, onMutedChange: (Boolean) -> Unit) {
    IconButton(onClick = { onMutedChange(!muted) }, modifier = Modifier.size(Dimens.ToolButton)) {
        Icon(
            imageVector = if (muted) DarkroomIcons.SoundOff else DarkroomIcons.SoundOn,
            contentDescription = stringResource(if (muted) R.string.cd_playback_sound_on else R.string.cd_playback_sound_off),
            tint = DarkroomColors.Paper,
            modifier = Modifier.size(Dimens.ToolIcon),
        )
    }
}

@Composable
private fun MotionTag(engine: PlaybackEngine) {
    val source = remember { MutableInteractionSource() }
    androidx.compose.material3.Text(
        text = stringResource(R.string.playback_motion),
        style = com.winters.cleanphotos.core.design.DarkroomText.RvFile.copy(color = DarkroomColors.Paper),
        modifier = Modifier
            .clickable(interactionSource = source, indication = null, onClick = engine::replayMotion)
            .padding(horizontal = Dimens.GhostGap, vertical = Dimens.PlaybackTagPadV),
    )
}

@Composable
private fun ProgressTrack(fraction: Float, onScrub: (Float) -> Unit) {
    var width by remember { mutableFloatStateOf(1f) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(Dimens.PlaybackScrubHeight)
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset -> onScrub((offset.x / width).coerceIn(0f, 1f)) },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        onScrub((change.position.x / width).coerceIn(0f, 1f))
                    },
                    onDragEnd = { onScrub(Float.NaN) },
                    onDragCancel = { onScrub(Float.NaN) },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val y = size.height / 2f
            val stroke = Dimens.SegHeight.toPx()
            val thumb = Dimens.NavItemGap.toPx()
            drawLine(DarkroomColors.PlaybackTrack, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
            val x = size.width * fraction.coerceIn(0f, 1f)
            drawLine(DarkroomColors.Paper, Offset(0f, y), Offset(x, y), strokeWidth = stroke)
            drawCircle(DarkroomColors.Paper, radius = thumb, center = Offset(x, y))
        }
    }
}
