package com.winters.cleanphotos.core.design

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.winters.cleanphotos.domain.model.photoMarkLine

/**
 * 大图左下角的 RAW / 动态。没有衬底。
 * RAW 和间隔用等宽；「动态」是汉字，走 Noto，否则 Plex Mono 里没有这两个字。
 */
@Composable
fun PhotoMarkText(
    rawLabel: String?,
    motionLabel: String?,
    modifier: Modifier = Modifier,
    sizeLabel: String? = null,
) {
    val line = photoMarkLine(rawLabel, motionLabel, sizeLabel) ?: return
    val style = DarkroomText.ReviewMark
    if (motionLabel.isNullOrEmpty()) {
        Text(line, style = style, modifier = modifier)
        return
    }
    val motionStart = line.indexOf(motionLabel)
    Text(
        text = buildAnnotatedString {
            if (motionStart < 0) {
                append(line)
            } else {
                append(line.substring(0, motionStart))
                withStyle(SpanStyle(fontFamily = DarkroomFonts.NotoSansSc)) {
                    append(motionLabel)
                }
                append(line.substring(motionStart + motionLabel.length))
            }
        },
        style = style,
        modifier = modifier,
    )
}
