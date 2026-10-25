package com.winters.cleanphotos.core.design

import com.winters.cleanphotos.core.locale.currentLocale
import com.winters.cleanphotos.R
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.State
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.winters.cleanphotos.core.util.NumberFormatter
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints

/** 透明点击（暗房里按压反馈由各组件自己画，不用涟漪）。 */
@Composable
fun Modifier.plainClickable(enabled: Boolean = true, role: Role = Role.Button, onClick: () -> Unit): Modifier =
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        enabled = enabled,
        role = role,
        onClick = onClick,
    )

/**
 * 叠在列表上的实色层（顶栏、导航底带）：Compose 不会因为有底色就拦触摸，空白处的点按会落到底下被盖住的格子上。
 * 挂一个空的 pointerInput 让命中停在这一层。
 */
fun Modifier.blockTouchesBelow(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope { while (true) awaitPointerEvent() }
}

/**
 * .bar：状态栏下 56dp，内边距 0 16 8，底对齐。左边计数行，右边按钮组（间距 8）。
 * [leftAlpha] 用于菜单打开时左侧变 50%。
 */
@Composable
fun ScreenHeader(
    count: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    leftAlpha: Float = 1f,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(Dimens.HeaderHeight)
            .padding(start = Dimens.ScreenSide, end = Dimens.ScreenSide, bottom = Dimens.HeaderBottomPadding),
        verticalAlignment = Alignment.Bottom,
    ) {
        // 固定高度（与 ScreenHeaderSpacer 对齐）：大字号时缩放封顶，计数行不被顶出状态栏（1.0 时不变）
        CappedFontScale(FontScaleCaps.HEADER) {
            Row(
                Modifier.weight(1f).alpha(leftAlpha).heightIn(min = Dimens.CountHeight),
                horizontalArrangement = Arrangement.spacedBy(Dimens.CountGap),
                verticalAlignment = Alignment.Bottom,
                content = count,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.HeaderActionGap), content = actions)
        }
    }
}

/** 页头占位：与 [ScreenHeader] 同高（状态栏 + 56dp），用于把头部画在遮罩之上。 */
@Composable
fun ScreenHeaderSpacer() {
    Spacer(Modifier.fillMaxWidth().statusBarsPadding().height(Dimens.HeaderHeight))
}

/** .eyebrow 文案。 */
@Composable
fun Eyebrow(text: String) {
    Text(text, style = DarkroomText.Eyebrow)
}

/**
 * 单位后面的收藏标记：5dp 琥珀点，左距 6dp，琥珀光 6dp。
 * 外盒与单位字号同高并底对齐计数行，点在这行里垂直居中。
 */
@Composable
fun FavPip() {
    val unitLine = with(LocalDensity.current) { DarkroomText.CountUnit.fontSize.toDp() }
    Box(
        Modifier.height(unitLine).padding(start = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(5.dp)
                .dropShadow(CircleShape, Shadow(radius = 6.dp, color = DarkroomColors.AmberGlow))
                .clip(CircleShape)
                .background(DarkroomColors.Amber),
        )
    }
}

/**
 * .count：大数字（里程表）+ 单位（[unit]，默认 header_count_unit）+ 可选 .sub（左距 8 = gap 4 + 4）。
 * 放得下时三者同一行按基线对齐；宽度不够（大数字、英文、大字号）时 .sub 换到数字下方单独一行，
 * 仍放不下才省略号截断。占满标题区剩余宽度，不会挤到右侧按钮。
 */
@Composable
fun RowScope.HeaderCount(count: Int, unit: String = stringResource(R.string.header_count_unit), sub: String? = null) {
    val locale = currentLocale()
    val gapPx = with(LocalDensity.current) { Dimens.CountGap.roundToPx() }
    val subGapPx = with(LocalDensity.current) { Dimens.CountSubGap.roundToPx() }
    Layout(
        modifier = Modifier.weight(1f, fill = false),
        content = {
            OdometerText(
                value = count.toLong(),
                style = DarkroomText.CountBig,
                format = { NumberFormatter.formatCount(it.toInt(), locale) },
            )
            // 空单位量出 0 宽，不留间距
            Text(
                unit, style = DarkroomText.CountUnit,
                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
            )
            if (sub != null) {
                Text(sub, style = DarkroomText.CountSub, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
        },
    ) { measurables, constraints ->
        val maxW = constraints.maxWidth
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val num = measurables[0].measure(loose)
        val unitM = measurables[1].measure(loose.copy(maxWidth = (maxW - num.width - gapPx).coerceAtLeast(0)))
        val unitGap = if (unit.isEmpty()) 0 else gapPx
        val subM = measurables.getOrNull(2)
        fun Placeable.base(): Int = this[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: height
        val lineW = num.width + unitGap + unitM.width
        // 先用固有宽度判断一行能否放下（每个子项只能 measure 一次）
        val oneLine = subM == null || lineW + subGapPx + subM.maxIntrinsicWidth(Constraints.Infinity) <= maxW
        val subP = subM?.measure(loose.copy(maxWidth = if (oneLine) (maxW - lineW - subGapPx).coerceAtLeast(0) else maxW))
        val firstRow = if (oneLine) listOfNotNull(num, unitM, subP) else listOf(num, unitM)
        val baseline = firstRow.maxOf { it.base() }
        val rowH = firstRow.maxOf { baseline - it.base() + it.height }
        val width = if (oneLine) lineW + (subP?.let { subGapPx + it.width } ?: 0) else maxOf(lineW, subP!!.width)
        val height = if (oneLine) rowH else rowH + subP!!.height
        layout(width.coerceAtMost(maxW), height) {
            num.placeRelative(0, baseline - num.base())
            unitM.placeRelative(num.width + unitGap, baseline - unitM.base())
            if (subP != null) {
                if (oneLine) subP.placeRelative(lineW + subGapPx, baseline - subP.base())
                else subP.placeRelative(0, rowH)
            }
        }
    }
}

/** .year：年份 + 1dp 细线。 */
@Composable
fun YearDivider(year: Int, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(Dimens.YearHeight).padding(horizontal = Dimens.ScreenSide),
        horizontalArrangement = Arrangement.spacedBy(Dimens.YearGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(year.toString(), style = DarkroomText.Year)
        Box(Modifier.weight(1f).height(Dimens.Hairline).background(DarkroomColors.Rim))
    }
}

/**
 * .day：高 50，padding 16 16 10，大号两位日期 + 「9月 · 周一」基线对齐；右侧等宽小字 + 16dp 箭头。
 * 只有右侧（[onEnter]）可点，进入这一天（interaction.md）。
 */
@Composable
fun DayHeaderRow(
    numeral: String,
    meta: String,
    right: @Composable () -> Unit,
    onEnter: (() -> Unit)?,
    modifier: Modifier = Modifier,
    showChevron: Boolean = true,
) {
    Row(modifier.fillMaxWidth().height(Dimens.DayHeight)) {
      // 50dp 固定高度：大字号时缩放封顶，天数字不被裁（1.0 时不变）
      CappedFontScale(FontScaleCaps.DAY_HEADER) {
        Row(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .weight(1f)
                    .padding(start = Dimens.ScreenSide, top = Dimens.DayTop, bottom = Dimens.DayBottom),
                horizontalArrangement = Arrangement.spacedBy(Dimens.DayGap),
            ) {
                Text(numeral, style = DarkroomText.DayNumeral, modifier = Modifier.alignByBaseline())
                Text(meta, style = DarkroomText.DayMeta, modifier = Modifier.alignByBaseline())
            }
            // 右侧热区撑满行高（50）且至少 48dp 宽。只有这里进入这一天 / 全选。
            Row(
                Modifier
                    .fillMaxHeight()
                    .defaultMinSize(minWidth = Dimens.MinTouch)
                    .then(if (onEnter != null) Modifier.plainClickable(onClick = onEnter) else Modifier)
                    .padding(end = Dimens.ScreenSide),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                right()
                if (showChevron) Icon(DarkroomIcons.Chevron, contentDescription = stringResource(R.string.cd_enter_day), tint = DarkroomColors.Dim, modifier = Modifier.size(Dimens.DayChevron))
            }
        }
      }
    }
}

/** 「<b>9</b> / 14 未看」：粗体部分相纸白 500。 */
@Composable
fun MonoWithBold(bold: String?, rest: String) {
    Text(
        buildAnnotatedString {
            if (bold != null) {
                withStyle(SpanStyle(color = DarkroomColors.Paper, fontWeight = FontWeight.Medium)) { append(bold) }
            }
            append(rest)
        },
        style = DarkroomText.DayRight,
    )
}

/** .list-fade：底部 128dp 渐暗（透明 → 92% @70% → 暗室底）。 */
@Composable
fun BoxScope.ListFade(modifier: Modifier = Modifier) {
    Box(
        modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(Dimens.ListFade)
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.7f to DarkroomColors.ListFadeMid,
                    1f to DarkroomColors.Room,
                ),
            ),
    )
}

/** .tb：36dp 托盘胶囊文字按钮。 */
@Composable
fun TrayTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val shape = RoundedCornerShape(Dimens.SmallButtonHeight / 2)
    Box(
        modifier
            .height(Dimens.SmallButtonHeight)
            .clip(shape)
            .background(DarkroomColors.Tray)
            .border(Dimens.Hairline, DarkroomColors.Rim, shape)
            .plainClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Dimens.TextButtonPaddingH)
            .alpha(if (enabled) 1f else 0.3f),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = DarkroomText.Button13) }
}

/** .btn-paper：36dp 相纸白实心；0 项时 30% 不可点（interaction.md）。 */
@Composable
fun PaperButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.3f)
            .height(Dimens.SmallButtonHeight)
            .clip(RoundedCornerShape(Dimens.SmallButtonHeight / 2))
            .background(DarkroomColors.Paper)
            .plainClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Dimens.TextButtonPaddingH),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = DarkroomText.ButtonPaper) }
}

/** .ghost：40dp 托盘中性按钮 + 可选等宽小字。 */
@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, mono: String? = null) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier
            .height(40.dp)
            .clip(shape)
            .background(DarkroomColors.Tray)
            .border(Dimens.Hairline, DarkroomColors.Rim2, shape)
            .plainClickable(onClick = onClick)
            .padding(horizontal = Dimens.GhostPaddingH),
        horizontalArrangement = Arrangement.spacedBy(Dimens.GhostGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = DarkroomText.Button13)
        if (mono != null) Text(mono, style = DarkroomText.GhostMono)
    }
}

/** .btn-clear：40dp 透明 + 删除红 55% 描边，「清空」入口专用。 */
@Composable
fun DeleteOutlineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.3f)
            .height(Dimens.DeleteButtonHeight)
            .clip(shape)
            .border(Dimens.Hairline, DarkroomColors.DeleteRim, shape)
            .plainClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Dimens.GhostPaddingH),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = DarkroomText.Button14.copy(color = DarkroomColors.Delete)) }
}

/** .perm-go / .pv-act：52dp 相纸白主按钮，可带 20dp 图标（间距 8）。 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .alpha(if (enabled) 1f else 0.6f)
            .fillMaxWidth()
            .height(Dimens.PrimaryButtonHeight)
            .clip(RoundedCornerShape(Dimens.PrimaryButtonHeight / 2))
            .background(DarkroomColors.Paper)
            .plainClickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = DarkroomColors.Room, modifier = Modifier.size(20.dp))
        Text(text, style = DarkroomText.ButtonPrimary)
    }
}

/**
 * 骨架呼吸：#171513 ↔ #211E1B，2.4s ease-in-out，[delayMs] 错开。
 * 返回 State，只在绘制阶段读（[skeletonFill]），呼吸不触发重组。
 */
@Composable
fun skeletonColor(delayMs: Int): State<Color> {
    val t = rememberInfiniteTransition(label = "skeleton")
    return t.animateColor(
        initialValue = DarkroomColors.SkeletonLow,
        targetValue = DarkroomColors.SkeletonHigh,
        animationSpec = infiniteRepeatable(
            animation = tween(MotionDurations.SKELETON_BREATH / 2, easing = androidx.compose.animation.core.EaseInOut),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(delayMs),
        ),
        label = "skeleton-color",
    )
}

fun Modifier.skeletonFill(color: State<Color>): Modifier = drawBehind { drawRect(color.value) }

/** .sk 骨架块（圆角 4）。 */
@Composable
fun SkeletonBlock(width: Dp, height: Dp, delayMs: Int = 0, modifier: Modifier = Modifier) {
    val color = skeletonColor(delayMs)
    Box(modifier.width(width).height(height).clip(RoundedCornerShape(4.dp)).skeletonFill(color))
}
