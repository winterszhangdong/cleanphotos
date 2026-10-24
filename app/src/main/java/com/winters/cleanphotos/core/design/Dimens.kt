package com.winters.cleanphotos.core.design

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 尺寸、间距、圆角，数值来自 docs/ui-design.md。 */
object Dimens {
    // 底部导航（.nav / .ni / .badge）
    val NavHeight = 44.dp
    val NavPadding = 4.dp
    val NavCorner = 22.dp
    val NavBottom = 24.dp
    val NavItemWidth = 72.dp
    val NavItemHeight = 36.dp
    val NavIndicatorWidth = 16.dp
    val NavIndicatorHeight = 2.dp
    val NavIndicatorBottom = 3.dp
    val NavItemGap = 4.dp
    val NavShadowY = 12.dp
    val NavShadowBlur = 32.dp
    val NavBlur = 20.dp
    val NavBadgeHeight = 16.dp
    val NavBadgeMinWidth = 16.dp
    val NavBadgePaddingH = 4.dp
    val NavLabelSize = 13.sp
    val NavBadgeTextSize = 10.sp

    // 圆形按钮
    val GlassButton = 40.dp
    val GlassBlur = 16.dp
    val ToolButton = 36.dp
    val ToolIcon = 18.dp
    val ToolPip = 6.dp
    val ToolPipInset = 7.dp
    val Hairline = 1.dp

    // 进度条
    val SegHeight = 2.dp
    /** 中心播放键直径。实色玻璃，不模糊：视频是 TextureView，采不到背景。 */
    val PlaybackCenter = 64.dp
    /**
     * 中心播放键里的三角布局框。
     * 路径在 24 视口里宽 16、高 19.2，36dp 时视觉宽约 24dp、高约 29dp。
     */
    val PlaybackCenterIcon = 36.dp
    /** 进度行热区高度。轨道线宽是 [PlaybackTrackHeight]，不复用 [SegHeight]。 */
    val PlaybackRowHeight = 48.dp
    val PlaybackTrackHeight = 3.dp
    /** 进度拇指直径。拖动时用 [PlaybackThumbDrag]。 */
    val PlaybackThumb = 12.dp
    val PlaybackThumbDrag = 16.dp
    /** 「动态」胶囊左右内边距。图标和文字间距是 [PlaybackPillGap]。 */
    val PlaybackPillPadH = 14.dp
    val PlaybackPillGap = 6.dp
    /** 胶囊里的动态图标。 */
    val PlaybackMotionIcon = 18.dp
    /** 胶囊文字。 */
    val PlaybackPillText = 13.sp
    /** 声音钮图标，同返回钮。 */
    val PlaybackSoundIcon = 20.dp
    val SegGap = 3.dp
    val SegSideInset = 16.dp
    val SegDash = 3.dp
    val SegDashGap = 2.dp
    val SegGlow = 6.dp

    // 菜单 / 对话框 / 按钮
    val MenuWidth = 192.dp
    val MenuCorner = 16.dp
    val MenuPadding = 4.dp
    val MenuItemHeight = 44.dp
    val MenuItemCorner = 12.dp
    /** ⋯ 菜单里的开关：轨道 36×20，圆钮 16，内边 2（task032 G6）。 */
    val MenuSwitchTrackWidth = 36.dp
    val MenuSwitchTrackHeight = 20.dp
    val MenuSwitchThumb = 16.dp
    val MenuSwitchInset = 2.dp
    val DialogCorner = 24.dp
    val DialogSide = 24.dp
    val PrimaryButtonHeight = 52.dp
    val SmallButtonHeight = 36.dp
    val DeleteButtonHeight = 40.dp

    // 顶栏
    val TopCountSize = 30.sp
    val TopCountLine = 32.sp
    val ScreenSide = 16.dp

    // —— P3 列表 / 页面骨架 ——
    /** .bar：高 56，状态栏下方，内边距 0 16 8（没有 eyebrow，task032 G1）。 */
    val HeaderHeight = 56.dp
    val HeaderBottomPadding = 8.dp
    val EyebrowGap = 4.dp
    val CountHeight = 32.dp
    val CountGap = 4.dp
    val CountSubGap = 8.dp
    val HeaderActionGap = 8.dp
    /** 照片页范围按钮（task032 G3）：胶囊高 32、左右 14、间距 8；整行高 40（含下边 8）。 */
    val ScopeChipHeight = 32.dp
    val ScopeChipPadH = 14.dp
    val ScopeChipGap = 8.dp
    val ScopeRowHeight = 40.dp
    /** .year：高 32，左右 16，间距 12。 */
    val YearHeight = 32.dp
    val YearGap = 12.dp
    /** .day：高 50，padding 16 16 10，间距 8。 */
    val DayHeight = 50.dp
    val DayTop = 16.dp
    val DayBottom = 10.dp
    val DayGap = 8.dp
    val DayChevron = 16.dp
    /** 日期行 / 月份行右侧进入或全选的热区至少这个宽，行高 50 已经够高。 */
    val MinTouch = 48.dp
    /** .grid4 / .grid3 间隔 2。 */
    val GridGap = 2.dp
    /** .list-fade 高 128。 */
    val ListFade = 128.dp
    /** .tb / .ghost / .btn-clear / .btn-paper。 */
    val TextButtonPaddingH = 16.dp
    val GhostPaddingH = 20.dp
    val GhostGap = 8.dp
    /** .free 卡片：外边距 4 16 16，内边距 16，圆角 20，间距 16。 */
    val FreeCorner = 20.dp
    val FreePadding = 16.dp
    val FreeTop = 4.dp
    /** .grid3 .size 距右下 6。 */
    val TileLabelInset = 6.dp
    /**
     * 格子角标底部渐变的高度。盖住 6dp 内缩和 10sp 那一行，上沿淡出。
     * 四列格子大约 90dp，这条只占底部一截。
     */
    val TileBadgeScrimHeight = 28.dp
    /** .sel-sum 高 40。 */
    val SelSumHeight = 40.dp
    /** .tile .ck：22，距右上 8，图标 14，暗室色外环 2。 */
    val CheckBadge = 22.dp
    val CheckInset = 8.dp
    val CheckIcon = 14.dp
    val CheckRing = 2.dp
    /** 多选：选中缩到 0.86、圆角 6（决定 8）。 */
    val SelectedCorner = 6.dp
    /** 照片页拖选：手指进入列表上下边缘 64dp 内开始自动滚动，贴边时最快 14dp / 帧。 */
    val DragSelectEdge = 64.dp
    val DragSelectMaxStep = 14.dp
    /** 照片页多选底栏：两项各 112×36dp（与导航同一枚 44dp 玻璃胶囊）。 */
    val SelectActionWidth = 112.dp
    /** 照片页撤回胶囊底边距导航胶囊顶 16dp。 */
    val UndoAboveNav = 16.dp
    /** .menu：距顶（状态栏下）80，右 16。 */
    val MenuTopBelowStatus = 80.dp
    val MenuHeaderPadding = 12.dp
    /** .empty：上 116 / 下 92 之间居中。 */
    val EmptyTop = 116.dp
    val EmptyBottom = 92.dp
    val EmptyTextWidth = 232.dp
    /** .pile 160×176，卡 100×128 圆角 8。 */
    val PileWidth = 160.dp
    val PileHeight = 176.dp
    val PileCardWidth = 100.dp
    val PileCardHeight = 128.dp
    val PileCardCorner = 8.dp
    /** .done-art 248×152。 */
    val DoneArtWidth = 248.dp
    val DoneArtHeight = 152.dp
    /** .perm 侧边 24，主按钮距底 72，次按钮距底 36。 */
    val PermSide = 24.dp
    val PermGoBottom = 72.dp
    val PermAltBottom = 36.dp
    /** .pv-act 距底 40、左右 24；.pv-hint 距底 100。meta 左右同 24，控件和文件信息间距 8 复用 [GhostGap]。 */
    val PreviewSide = 24.dp
    val PreviewActionBottom = 40.dp
    val PreviewHintBottom = 100.dp
    /** 排序提示（mini 228px ↔ 360dp，× 1.579）：高 24→38、圆角 12→19、左右 12→19；顶边在屏高 342/494 处。 */
    val ToastHeight = 38.dp
    val ToastPaddingH = 19.dp
    const val TOAST_TOP_FRACTION = 342f / 494f
    /** 进场上移 6px → 9.5dp。 */
    val ToastRise = 9.5.dp
    /** 空状态升起 8px → 12.6dp。 */
    val EmptyRise = 12.6.dp
    /** 溶解模糊 5dp（31+）。 */
    val DissolveBlur = 5.dp
    /** 相似照片组里一行缩略图的边长。对比页胶片条同大。 */
    val SimilarThumb = 72.dp
    /** 对比页胶片条里，当前那张的相纸白描边。 */
    val SimilarFilmStroke = 2.dp
    /** 标成移走的缩略图底部退影灰细线。照片本身保持原色。 */
    val SimilarRemoveBar = 2.dp
    /** 方向提示圆：未过阈值空心，过阈值实心。对比页和清理页同一套尺寸。 */
    val CueRing = 32.dp
    val CueSolid = 56.dp
    val CueIcon = 16.dp
    val CueIconSolid = 24.dp

    /** 撤回胶囊 / 垃圾桶提醒胶囊：高 56、内边距 8、间距 12、玻璃模糊 24、投影 0 16 40。 */
    val CapsuleHeight = 56.dp
    val CapsuleInset = 8.dp
    val CapsuleGap = 12.dp
    val CapsuleBlur = 24.dp
    val CapsuleShadowBlur = 40.dp
    val CapsuleShadowY = 16.dp
    /** 提醒胶囊右侧 ✕ 的点击区域。 */
    val ReminderDismiss = 32.dp
}
