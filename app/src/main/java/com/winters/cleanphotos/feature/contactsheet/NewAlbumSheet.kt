package com.winters.cleanphotos.feature.contactsheet

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import com.winters.cleanphotos.R
import com.winters.cleanphotos.core.design.DarkroomColors
import com.winters.cleanphotos.core.design.DarkroomText
import com.winters.cleanphotos.core.design.Dimens
import com.winters.cleanphotos.core.design.FilingMotion
import com.winters.cleanphotos.core.design.MotionSprings
import com.winters.cleanphotos.core.design.PrimaryButton
import com.winters.cleanphotos.core.design.blockTouchesBelow
import com.winters.cleanphotos.core.design.plainClickable
import com.winters.cleanphotos.domain.model.AlbumRules
import com.winters.cleanphotos.domain.model.PhotoAlbum
import com.winters.cleanphotos.domain.model.PhotoItem

/** 新袋子 220×280，正面 224 高，指口 22，标签 84 高。 */
private object BigEnvelope {
    val Width = 220.dp
    val Height = 280.dp
    val BackTop = 40.dp
    val FrontHeight = 224.dp
    val Notch = 22.dp
    val LabelSide = 20.dp
    val LabelBottom = 28.dp
    val LabelHeight = 96.dp
    const val TILT = -2f
    /** 从托盘那么小长到中间这么大。 */
    const val FROM_SCALE = 0.35f
}

/**
 * 新袋子：空袋子从托盘里被抽出、放大到屏幕中间，圈中的照片斜插在袋口，直接在标签上写名字。
 * 同名（忽略大小写）的 Pictures/ 文件夹已存在时写「已有 · 合并进去」，不新建。
 */
@Composable
internal fun NewAlbumSheet(
    photos: List<PhotoItem>,
    albums: List<PhotoAlbum>,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit,
    /** 从相册面板「+ 新建「xxx」」进来时填好的名字。 */
    initialName: String = "",
) {
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        val start = AlbumRules.sanitizeInput(initialName)
        mutableStateOf(TextFieldValue(start, TextRange(start.length)))
    }
    val name = field.text
    val valid = AlbumRules.isValidName(name)
    val existing = if (valid) AlbumRules.existingFor(name, albums) else null
    val focus = remember { FocusRequester() }
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        enter.animateTo(1f, MotionSprings.Settle.spec())
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = enter.value.coerceIn(0f, 1f) }
            .background(DarkroomColors.Room.copy(alpha = 0.96f))
            .blockTouchesBelow()
            .statusBarsPadding()
            .imePadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().height(Dimens.HeaderHeight).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.new_album_cancel),
                style = DarkroomText.TextButton,
                modifier = Modifier.height(Dimens.MinTouch).plainClickable(onClick = onCancel).padding(horizontal = 12.dp, vertical = 14.dp),
            )
            Text(
                pluralStringResource(R.plurals.new_album_title, photos.size, photos.size),
                style = DarkroomText.GhostMono,
                modifier = Modifier.weight(1f).padding(end = 64.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        Column(
            Modifier.fillMaxSize().padding(top = Dimens.HeaderHeight + 24.dp, start = 24.dp, end = 24.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                Modifier
                    .size(BigEnvelope.Width, BigEnvelope.Height)
                    .graphicsLayer {
                        val e = enter.value
                        val s = BigEnvelope.FROM_SCALE + (1f - BigEnvelope.FROM_SCALE) * e
                        scaleX = s
                        scaleY = s
                        translationY = (1f - e) * 320.dp.toPx()
                        rotationZ = BigEnvelope.TILT * e
                    },
            ) {
                Box(
                    Modifier
                        .offset(y = BigEnvelope.BackTop)
                        .size(BigEnvelope.Width, BigEnvelope.Height - BigEnvelope.BackTop)
                        .clip(RoundedCornerShape(8.dp))
                        .background(DarkroomColors.KraftInside),
                )
                // 圈中的照片斜插在袋口
                photos.take(3).forEachIndexed { i, photo ->
                    val rot = FilingMotion.STACK_ROTATIONS[i]
                    Print(
                        photo,
                        size = 64.dp,
                        modifier = Modifier
                            .offset(x = BigEnvelope.Width / 2 - 32.dp + (i - 1) * 22.dp, y = (-6).dp + (i % 2) * 8.dp)
                            .graphicsLayer { rotationZ = rot * 1.2f },
                    )
                }
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .size(BigEnvelope.Width, BigEnvelope.FrontHeight)
                        .drawBehind {
                            val r = BigEnvelope.Notch.toPx()
                            val cx = size.width / 2f
                            val path = Path().apply {
                                moveTo(0f, 0f)
                                lineTo(cx - r, 0f)
                                arcTo(Rect(cx - r, -r, cx + r, r), 180f, -180f, false)
                                lineTo(size.width, 0f)
                                lineTo(size.width, size.height)
                                lineTo(0f, size.height)
                                close()
                            }
                            drawPath(path, Brush.verticalGradient(listOf(DarkroomColors.KraftHigh, DarkroomColors.Kraft)))
                            drawLine(DarkroomColors.Paper.copy(alpha = 0.16f), Offset(0f, 0.5f), Offset(cx - r, 0.5f), 1.dp.toPx())
                            drawLine(DarkroomColors.Paper.copy(alpha = 0.16f), Offset(cx + r, 0.5f), Offset(size.width, 0.5f), 1.dp.toPx())
                        },
                )
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = BigEnvelope.LabelSide, end = BigEnvelope.LabelSide, bottom = BigEnvelope.LabelBottom)
                        .fillMaxWidth()
                        .height(BigEnvelope.LabelHeight)
                        .clip(RoundedCornerShape(3.dp))
                        .background(DarkroomColors.Label)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Box {
                        if (name.isEmpty()) {
                            Text(stringResource(R.string.new_album_placeholder), style = DarkroomText.NewAlbumInput.copy(color = DarkroomColors.LabelInk.copy(alpha = 0.3f)))
                        }
                        BasicTextField(
                            value = field,
                            onValueChange = { v ->
                                val clean = AlbumRules.sanitizeInput(v.text)
                                field = if (clean == v.text) v else TextFieldValue(clean, TextRange(clean.length))
                            },
                            singleLine = true,
                            textStyle = DarkroomText.NewAlbumInput,
                            cursorBrush = SolidColor(DarkroomColors.LabelInk),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { if (valid) onConfirm(name.trim()) }),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    }
                    Box(Modifier.fillMaxWidth().height(1.5.dp).background(DarkroomColors.LabelInk.copy(alpha = 0.3f)))
                    Text(
                        when {
                            !valid -> "${AlbumRules.NEW_ALBUM_ROOT}…"
                            existing != null -> stringResource(R.string.new_album_path_merge, existing.relativePath.trimEnd('/'))
                            else -> stringResource(R.string.new_album_path_new, AlbumRules.pathFor(name).trimEnd('/'))
                        },
                        style = DarkroomText.NewAlbumPath,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            PrimaryButton(
                text = stringResource(R.string.new_album_go),
                onClick = { if (valid) onConfirm(name.trim()) },
                enabled = valid,
            )
        }
    }
}
