package com.winters.cleanphotos.core.design

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.winters.cleanphotos.R

/**
 * 暗房字体。全部是本地静态 ttf（非可变字体），按字重一一对应，不需要 FontVariation。
 * 数字与等宽小字一律开 tnum。
 */

/**
 * CSS 行高语义：每行盒子严格等于 lineHeight，半行距上下对称（可为负）。
 * Compose 1.10 默认 Mode.Fixed 会在首末行补齐字体自然高度（hhea），导致行高偏高
 * （如 Noto 12/16 实际 17.4dp）。行高小于字体自然高度时用 Tight + Trim.Both 收紧到 lineHeight；
 * 否则 Trim.None 保留上下半行距，与 CSS 一致。
 */
internal fun TextStyle.cssLines(): TextStyle {
    if (!lineHeight.isSp || !fontSize.isSp) return this
    val natural = when (fontFamily) {
        DarkroomFonts.NotoSansSc -> 1.448f // (1160 + 288) / 1000
        DarkroomFonts.PlexMono -> 1.3f // (1025 + 275) / 1000
        DarkroomFonts.Schibsted -> 1.2344f // (2000 + 528) / 2048
        else -> return this
    }
    val tight = lineHeight.value < fontSize.value * natural
    return copy(
        lineHeightStyle = if (tight) {
            LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both, LineHeightStyle.Mode.Tight)
        } else {
            LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
        },
    )
}
object DarkroomFonts {
    /** 四档都是 Noto Sans SC 子集，只留界面里出现过的字。新增汉字若显示成方框，按源码重切。 */
    val NotoSansSc: FontFamily = FontFamily(
        Font(R.font.noto_sans_sc_400, FontWeight.Normal),
        Font(R.font.noto_sans_sc_500, FontWeight.Medium),
        Font(R.font.noto_sans_sc_600, FontWeight.SemiBold),
        Font(R.font.noto_sans_sc_700, FontWeight.Bold),
    )
    val Schibsted: FontFamily = FontFamily(
        Font(R.font.schibsted_grotesk_500, FontWeight.Medium),
        Font(R.font.schibsted_grotesk_600, FontWeight.SemiBold),
        Font(R.font.schibsted_grotesk_700, FontWeight.Bold),
    )
    val PlexMono: FontFamily = FontFamily(
        Font(R.font.ibm_plex_mono_400, FontWeight.Normal),
        Font(R.font.ibm_plex_mono_500, FontWeight.Medium),
    )
    const val TABULAR = "tnum"
}

/** 文字样式（.num / .mono / .count / .nav 等）。 */
object DarkroomText {
    /** .count .big：30/32，-0.02em，Schibsted 600 tnum。 */
    val CountBig = TextStyle(
        fontFamily = DarkroomFonts.Schibsted, fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp, lineHeight = 32.sp, letterSpacing = (-0.02).em,
        fontFeatureSettings = DarkroomFonts.TABULAR, color = DarkroomColors.Paper,
    ).cssLines()
    /** .count .unit：13sp dim。 */
    val CountUnit = TextStyle(
        fontFamily = DarkroomFonts.NotoSansSc, fontSize = 13.sp, color = DarkroomColors.Dim).cssLines()
    /** .count .sub：11sp mono dim。 */
    val CountSub = TextStyle(
        fontFamily = DarkroomFonts.PlexMono, fontSize = 11.sp,
        fontFeatureSettings = DarkroomFonts.TABULAR, color = DarkroomColors.Dim,
    ).cssLines()
    /** .eyebrow：12/16，字距 0.12em，dim。 */
    val Eyebrow = TextStyle(
        fontFamily = DarkroomFonts.NotoSansSc, fontSize = 12.sp, lineHeight = 16.sp,
        letterSpacing = 0.12.em, color = DarkroomColors.Dim,
    ).cssLines()
    /** 正文 14。 */
    val Body = TextStyle(
        fontFamily = DarkroomFonts.NotoSansSc, fontSize = 14.sp, lineHeight = 20.sp, color = DarkroomColors.Paper).cssLines()
    /** 空状态说明 13/20。 */
    val Caption = TextStyle(
        fontFamily = DarkroomFonts.NotoSansSc, fontSize = 13.sp, lineHeight = 20.sp, color = DarkroomColors.Dim).cssLines()
    /** 空状态标题 18。 */
    val EmptyTitle = TextStyle(
        fontFamily = DarkroomFonts.NotoSansSc, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = DarkroomColors.Paper).cssLines()
    /** 对话框标题 .dg-title 17/24 600。 */
    val DialogTitle = TextStyle(
        fontFamily = DarkroomFonts.NotoSansSc, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp, color = DarkroomColors.Paper).cssLines()
    /** 底部导航 13。 */
    val NavLabel = TextStyle(
        fontFamily = DarkroomFonts.NotoSansSc, fontSize = 13.sp, color = DarkroomColors.Dim).cssLines()
    /** 导航计数徽标 .badge.mono 10/16。 */
    val NavBadge = TextStyle(
        fontFamily = DarkroomFonts.PlexMono, fontSize = 10.sp, lineHeight = 16.sp,
        fontFeatureSettings = DarkroomFonts.TABULAR, color = DarkroomColors.Dim,
    ).cssLines()
    /** EXIF / 文件信息类小字 .mono 11。 */
    val Mono = TextStyle(
        fontFamily = DarkroomFonts.PlexMono, fontSize = 11.sp,
        fontFeatureSettings = DarkroomFonts.TABULAR, color = DarkroomColors.Dim,
    ).cssLines()
    /** 数字 .num（Schibsted 600 tnum），字号由使用处 copy。 */
    val Num = TextStyle(
        fontFamily = DarkroomFonts.Schibsted, fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = DarkroomFonts.TABULAR, color = DarkroomColors.Paper,
    ).cssLines()

    // —— P3 列表 / 空态 / 授权 / 预览（注释里的 .xxx 是样式名）——

    private val Sans = TextStyle(
        fontFamily = DarkroomFonts.NotoSansSc, color = DarkroomColors.Paper).cssLines()
    private val MonoBase = TextStyle(
        fontFamily = DarkroomFonts.PlexMono, fontFeatureSettings = DarkroomFonts.TABULAR, color = DarkroomColors.Dim,
    ).cssLines()

    /** .year.mono：11sp，字距 0.08em，dim。 */
    val Year = MonoBase.copy(fontSize = 11.sp, letterSpacing = 0.08.em).cssLines()
    /** .day .d：.num 24/24，-0.01em。 */
    val DayNumeral = Num.copy(fontSize = 24.sp, lineHeight = 24.sp, letterSpacing = (-0.01).em).cssLines()
    /** .day .md：12sp dim。 */
    val DayMeta = Sans.copy(fontSize = 12.sp, color = DarkroomColors.Dim).cssLines()
    /** .day .r.mono：11sp dim；其中 b 为相纸白 500。 */
    val DayRight = MonoBase.copy(fontSize = 11.sp).cssLines()
    /** .grid3 .size.mono：10sp，rgba(238,233,226,0.88)，阴影 0 1 4 rgba(0,0,0,0.8)。 */
    val TileSize = MonoBase.copy(
        fontSize = 10.sp, color = DarkroomColors.Paper.copy(alpha = 0.88f),
        shadow = androidx.compose.ui.graphics.Shadow(
            color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.8f),
            offset = androidx.compose.ui.geometry.Offset(0f, 1f), blurRadius = 4f,
        ),
    ).cssLines()
    /** .tb / .ghost：13sp 500 相纸白。 */
    val Button13 = Sans.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium).cssLines()
    /** .btn-paper：13sp 600 暗室色。 */
    val ButtonPaper = Sans.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DarkroomColors.Room).cssLines()
    /** .btn-clear / .dg-actions：14sp 600。 */
    val Button14 = Sans.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold).cssLines()
    /** .perm-go / .pv-act：15sp 600 暗室色。 */
    val ButtonPrimary = Sans.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DarkroomColors.Room).cssLines()
    /** .free .lab：12/16 dim。 */
    val Label12 = Sans.copy(fontSize = 12.sp, lineHeight = 16.sp, color = DarkroomColors.Dim).cssLines()
    /** .free .v .num：32/36，-0.02em。 */
    val FreeNum = Num.copy(fontSize = 32.sp, lineHeight = 36.sp, letterSpacing = (-0.02).em).cssLines()
    /** .free .v .u：14sp 500 dim。 */
    val FreeUnit = Sans.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = DarkroomColors.Dim).cssLines()
    /** .free .sub：11/16 faint。 */
    val FreeSub = Sans.copy(fontSize = 11.sp, lineHeight = 16.sp, color = DarkroomColors.Faint).cssLines()
    /** .sel-bar .t：15sp 500；数字 .num 17sp。 */
    val SelTitle = Sans.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium).cssLines()
    val SelNum = Num.copy(fontSize = 17.sp).cssLines()
    /** .sel-sum：12sp dim；mono 部分 b 为相纸白 500。 */
    val SelSum = Sans.copy(fontSize = 12.sp, color = DarkroomColors.Dim).cssLines()
    val SelSumMono = MonoBase.copy(fontSize = 12.sp).cssLines()
    /** .empty h4：18/24 600。 */
    val EmptyH4 = Sans.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold).cssLines()
    /** .empty p：13/20 dim。 */
    val EmptyP = Sans.copy(fontSize = 13.sp, lineHeight = 20.sp, color = DarkroomColors.Dim).cssLines()
    /** .ghost .mono：11sp dim。 */
    val GhostMono = MonoBase.copy(fontSize = 11.sp).cssLines()
    /** .done-stats .num：20/24；span 11sp dim。 */
    val DoneNum = Num.copy(fontSize = 20.sp, lineHeight = 24.sp).cssLines()
    val DoneLabel = Sans.copy(fontSize = 11.sp, color = DarkroomColors.Dim).cssLines()
    /** .text-btn / .perm-alt / .later：13sp dim。 */
    val TextButton = Sans.copy(fontSize = 13.sp, color = DarkroomColors.Dim).cssLines()
    /** .menu .mh：11/16，0.12em，dim。 */
    val MenuHeader = Sans.copy(fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.12.em, color = DarkroomColors.Dim).cssLines()
    /** .menu .mi：14sp dim；on 相纸白 500。 */
    val MenuItem = Sans.copy(fontSize = 14.sp, color = DarkroomColors.Dim).cssLines()
    /** .menu .mi .r.mono：11sp faint；on 时 dim。 */
    val MenuItemRight = MonoBase.copy(fontSize = 11.sp, color = DarkroomColors.Faint).cssLines()
    /** .pv-top b：15/20 500；span.mono 10.5/16 dim。 */
    val PvTitle = Sans.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium).cssLines()
    val PvSub = MonoBase.copy(fontSize = 10.5.sp, lineHeight = 16.sp).cssLines()
    /** .pv-meta.mono：10.5/16 dim 居中。 */
    val PvMeta = MonoBase.copy(fontSize = 10.5.sp, lineHeight = 16.sp).cssLines()
    /** .pv-hint：11sp faint。 */
    val PvHint = Sans.copy(fontSize = 11.sp, color = DarkroomColors.Faint).cssLines()
    /** .perm h4：24/32 600。 */
    val PermH4 = Sans.copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold).cssLines()
    /** .perm-list b：14/20 500；span 12/18 dim；.n.mono 11 dim。 */
    val PermItemTitle = Sans.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium).cssLines()
    val PermItemBody = Sans.copy(fontSize = 12.sp, lineHeight = 18.sp, color = DarkroomColors.Dim).cssLines()
    val PermItemNo = MonoBase.copy(fontSize = 11.sp).cssLines()
    /** .dg-body：13/20 dim。 */
    val DialogBody = Sans.copy(fontSize = 13.sp, lineHeight = 20.sp, color = DarkroomColors.Dim).cssLines()

    // —— P4 清理页——

    /** .rv-date b：15/20 500。 */
    val RvDate = Sans.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium).cssLines()
    /** .rv-date span.mono：10.5/16，字距 0.04em，相纸白 66%。 */
    val RvDateMeta = MonoBase.copy(fontSize = 10.5.sp, lineHeight = 16.sp, letterSpacing = 0.04.em, color = DarkroomColors.Paper.copy(alpha = 0.66f)).cssLines()
    /** .rv-meta.mono：10.5，字距 0.02em，相纸白 62%。 */
    val RvFile = MonoBase.copy(fontSize = 10.5.sp, lineHeight = 15.225.sp, letterSpacing = 0.02.em, color = DarkroomColors.Paper.copy(alpha = 0.62f)).cssLines()
    /** .cue b（过阈值）：15/20 600，字距 0.04em。 */
    val CueTitle = Sans.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.04.em).cssLines()
    /** .pre .cue b：14/20 500，字距 0.04em。 */
    val CuePre = Sans.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.04.em).cssLines()
    /** .cue small.mono：11/16。 */
    val CueSmall = MonoBase.copy(fontSize = 11.sp, lineHeight = 16.sp).cssLines()
    /** .undo .txt：14 500（行高 1.45）。 */
    val UndoTitle = Sans.copy(fontSize = 14.sp, lineHeight = 20.3.sp, fontWeight = FontWeight.Medium).cssLines()
    /** .undo .txt small：11 dim（行高 1.45）。 */
    val UndoSub = Sans.copy(fontSize = 11.sp, lineHeight = 15.95.sp, color = DarkroomColors.Dim).cssLines()
    /** .undo .act：13 600 暗室色。 */
    val UndoAct = Sans.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DarkroomColors.Room).cssLines()
    /** .sum .eyebrow.mono：12/16，字距 0.12em。 */
    val SumEyebrow = MonoBase.copy(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.12.em).cssLines()
    /** .sum h4：28/36 600，-0.01em。 */
    val SumTitle = Sans.copy(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em).cssLines()
    /** .stats .v.num：36/40，-0.02em。 */
    val SumStat = Num.copy(fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = (-0.02).em).cssLines()
    /** .stats .l：12/16 dim。 */
    val SumLabel = Sans.copy(fontSize = 12.sp, lineHeight = 16.sp, color = DarkroomColors.Dim).cssLines()
    /** .stats .x.mono：10.5 dim（父行 16）。 */
    val SumExtra = MonoBase.copy(fontSize = 10.5.sp, lineHeight = 16.sp).cssLines()
    /** .month .hd：12/16 dim；b 相纸白 500。 */
    val MonthHead = Sans.copy(fontSize = 12.sp, lineHeight = 16.sp, color = DarkroomColors.Dim).cssLines()
    val MonthHeadMono = MonoBase.copy(fontSize = 12.sp, lineHeight = 16.sp).cssLines()
    /** .next b：15/20 600 暗室色。 */
    val NextTitle = Sans.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = DarkroomColors.Room).cssLines()
    /** .next small：12/16 #6E675F。 */
    val NextSub = Sans.copy(fontSize = 12.sp, lineHeight = 16.sp, color = DarkroomColors.Fade).cssLines()
    /** .later：13 dim。 */
    val Later = Sans.copy(fontSize = 13.sp, color = DarkroomColors.Dim).cssLines()
    /** .tut-row b：15 500；span 13 dim。 */
    val TutRowTitle = Sans.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium).cssLines()
    val TutRowRight = Sans.copy(fontSize = 13.sp, color = DarkroomColors.Dim).cssLines()
    /** .tut-ok：14 600 暗室色。 */
    val TutOk = Sans.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DarkroomColors.Room).cssLines()
    /** 会话首尾提示 / 出错提示：12 相纸白 70%。 */
    /** 排序提示：9px × 1.579 ≈ 14sp，行高 = 胶囊高。 */
    val Toast = Sans.copy(fontSize = 14.sp, lineHeight = 38.sp, color = DarkroomColors.Paper).cssLines()
    val EdgeHint = Sans.copy(fontSize = 12.sp, lineHeight = 16.sp, color = DarkroomColors.Paper.copy(alpha = 0.7f)).cssLines()
}
