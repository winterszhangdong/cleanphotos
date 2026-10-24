package com.winters.cleanphotos.core.media

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextStyle
import com.winters.cleanphotos.R
import com.winters.cleanphotos.core.design.DarkroomColors
import com.winters.cleanphotos.core.design.DarkroomIcons
import com.winters.cleanphotos.core.design.DarkroomText
import com.winters.cleanphotos.core.design.Dimens
import com.winters.cleanphotos.core.design.GlassIconButton
import com.winters.cleanphotos.core.design.GlassSpecs
import com.winters.cleanphotos.core.design.LocalAnimationsEnabled
import com.winters.cleanphotos.core.design.MotionDurations
import com.winters.cleanphotos.core.design.MotionEasings
import com.winters.cleanphotos.core.design.MotionScales
import com.winters.cleanphotos.core.design.glassBlur
import com.winters.cleanphotos.core.util.DurationFormatter
import com.winters.cleanphotos.domain.model.PlaybackKind
import com.winters.cleanphotos.domain.model.PlaybackTarget
import com.winters.cleanphotos.domain.model.playbackPolicy
import kotlinx.coroutines.delay

/**
 * 落定且没有离场占着播放器时，等 [MotionDurations.PLAYBACK_START_DELAY] 再播。
 * 视频和动态照片都不循环。系统关闭动画则不自动播。
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
 * 64dp 玻璃中心播放键。只是视觉，没有点击：照片区域的单击才切换播放。
 * 底是实色 [DarkroomColors.Glass] 加 [DarkroomColors.GlassRim]，不走模糊：
 * 视频画在 TextureView 上，模糊取样不到画面。
 * 出现 / 消失用 [MotionDurations.SHORT] 淡入淡出 + [MotionScales.REVEAL] 缩放。系统关闭动画时直接显隐。
 */
@Composable
fun PlaybackCenterButton(visible: Boolean, modifier: Modifier = Modifier) {
    val animations = LocalAnimationsEnabled.current
    val fade = tween<Float>(MotionDurations.SHORT, easing = MotionEasings.Standard)
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (animations) {
            fadeIn(fade) + scaleIn(fade, initialScale = MotionScales.REVEAL)
        } else {
            EnterTransition.None
        },
        exit = if (animations) {
            fadeOut(fade) + scaleOut(fade, targetScale = MotionScales.REVEAL)
        } else {
            ExitTransition.None
        },
    ) {
        Box(
            Modifier
                .size(Dimens.PlaybackCenter)
                .clip(CircleShape)
                .background(DarkroomColors.Glass)
                .border(Dimens.Hairline, DarkroomColors.GlassRim, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = DarkroomIcons.Play,
                contentDescription = stringResource(R.string.cd_playback_play),
                tint = DarkroomColors.Paper,
                modifier = Modifier.size(Dimens.PlaybackCenterIcon),
            )
        }
    }
}

/**
 * 进度行：两端时间、3dp 轨道、40dp 玻璃声音钮。整行热区 [Dimens.PlaybackRowHeight]。
 * 拖动和点按都在这一层的 pointerInput 里，不进照片手势。
 */
@Composable
fun PlaybackProgressRow(
    engine: PlaybackEngine,
    muted: Boolean,
    onMutedChange: (Boolean) -> Unit,
    timeStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
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
    val progressLabel = stringResource(R.string.cd_playback_progress)
    Row(
        modifier.fillMaxWidth().height(Dimens.PlaybackRowHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(DurationFormatter.format(position.coerceAtLeast(0L)), style = timeStyle, maxLines = 1)
        var width by remember { mutableFloatStateOf(1f) }
        Box(
            Modifier
                .padding(horizontal = Dimens.GhostGap)
                .weight(1f)
                .fillMaxHeight()
                .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
                .semantics {
                    contentDescription = progressLabel
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f)
                    setProgress { value ->
                        engine.seekFraction(value)
                        true
                    }
                }
                .pointerInput(engine) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        fun at(x: Float) = (x / width).coerceIn(0f, 1f)
                        try {
                            scrub = at(down.position.x)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                change.consume()
                                scrub = at(change.position.x)
                            }
                        } finally {
                            if (!scrub.isNaN()) {
                                engine.seekFraction(scrub)
                                scrub = Float.NaN
                            }
                        }
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val y = size.height / 2f
                val stroke = Dimens.PlaybackTrackHeight.toPx()
                val thumb = (if (scrubbing) Dimens.PlaybackThumbDrag else Dimens.PlaybackThumb).toPx() / 2f
                val x = size.width * fraction.coerceIn(0f, 1f)
                drawLine(
                    DarkroomColors.PlaybackTrack,
                    Offset(0f, y),
                    Offset(size.width, y),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    DarkroomColors.Paper,
                    Offset(0f, y),
                    Offset(x, y),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawCircle(DarkroomColors.Paper, radius = thumb, center = Offset(x, y))
            }
        }
        Text(DurationFormatter.format(duration.coerceAtLeast(0L)), style = timeStyle, maxLines = 1)
        GlassIconButton(
            onClick = { onMutedChange(!muted) },
            modifier = Modifier.padding(start = Dimens.GhostGap),
        ) {
            Icon(
                imageVector = if (muted) DarkroomIcons.SoundOff else DarkroomIcons.SoundOn,
                contentDescription = stringResource(if (muted) R.string.cd_playback_sound_on else R.string.cd_playback_sound_off),
                tint = DarkroomColors.Paper,
                modifier = Modifier.size(Dimens.PlaybackSoundIcon),
            )
        }
    }
}

/** 40dp 玻璃胶囊「◉ 动态」。点它从头重播。 */
@Composable
fun MotionReplayPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(50)
    val label = stringResource(R.string.cd_playback_motion)
    Row(
        modifier
            .height(Dimens.GlassButton)
            .clip(shape)
            .glassBlur(GlassSpecs.Button)
            .then(if (pressed) Modifier.background(DarkroomColors.Tray2) else Modifier)
            .border(Dimens.Hairline, DarkroomColors.GlassRim, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = Dimens.PlaybackPillPadH)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.PlaybackPillGap),
    ) {
        Icon(
            imageVector = DarkroomIcons.Motion,
            contentDescription = null,
            tint = DarkroomColors.Paper,
            modifier = Modifier.size(Dimens.PlaybackMotionIcon),
        )
        Text(
            text = stringResource(R.string.playback_motion),
            style = DarkroomText.Button13.copy(color = DarkroomColors.Paper, fontSize = Dimens.PlaybackPillText),
            maxLines = 1,
        )
    }
}
