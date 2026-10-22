package com.winters.cleanphotos.core.design

import androidx.compose.ui.graphics.Color

/**
 * 暗房色板，数值与 docs/ui-design.md 2.1 完全一致。
 *
 * 语义硬规则：琥珀只表示收藏；退影灰只表示移到垃圾桶；删除红只用于「清空」入口和永久删除确认。
 */
object DarkroomColors {
    /** --room 暗室底。 */
    val Room = Color(0xFF0F0E0D)
    /** --tray 抬起的面：按钮、卡片、对话框。 */
    val Tray = Color(0xFF1A1816)
    /** --tray-2 再抬一层：按下态。 */
    val Tray2 = Color(0xFF24211E)
    /** --rim rgba(255, 240, 225, 0.09) 细线。 */
    val Rim = Color(0xFFFFF0E1).copy(alpha = 0.09f)
    /** --rim-2 rgba(255, 240, 225, 0.16)。 */
    val Rim2 = Color(0xFFFFF0E1).copy(alpha = 0.16f)
    /** --paper 相纸白：主文字、主按钮。 */
    val Paper = Color(0xFFEEE9E2)
    /** --dim 次文字。 */
    val Dim = Color(0xFF8C857D)
    /** --faint 三级，只用于非关键信息。 */
    val Faint = Color(0xFF5B554F)
    /** --fade 退影灰 = 移到垃圾桶。 */
    val Fade = Color(0xFF6E6862)
    /** --fade-deep 进度条里已移走的格子。 */
    val FadeDeep = Color(0xFF4A4541)
    /** --fade-text 退影灰文字。 */
    val FadeText = Color(0xFFA39C94)
    /** --delete 删除红，只用于「清空」和永久删除确认。 */
    val Delete = Color(0xFFC4553F)
    /** 删除按钮白字 #FFF4EF（组件细节·永久删除确认）。 */
    val OnDelete = Color(0xFFFFF4EF)
    /** --amber 显影琥珀 = 收藏。 */
    val Amber = Color(0xFFF2A93B)
    /** --glass rgba(20, 18, 16, 0.56) 玻璃圆钮底。 */
    val Glass = Color(0xFF141210).copy(alpha = 0.56f)
    /** 玻璃圆钮内描边 rgba(255, 240, 225, 0.14)。 */
    val GlassRim = Color(0xFFFFF0E1).copy(alpha = 0.14f)
    /** 底部导航胶囊底 rgba(30, 28, 25, 0.72)。 */
    val NavGlass = Color(0xFF1E1C19).copy(alpha = 0.72f)
    /** 底部导航内描边 rgba(255, 240, 225, 0.1)。 */
    val NavRim = Color(0xFFFFF0E1).copy(alpha = 0.10f)
    /** 底部导航投影 rgba(0, 0, 0, 0.5)，0 12px 32px。 */
    val NavShadow = Color.Black.copy(alpha = 0.5f)
    /** 导航计数徽标底 rgba(238, 233, 226, 0.1)。 */
    val BadgeFill = Paper.copy(alpha = 0.10f)
    /** 加载骨架呼吸两端 #171513 ↔ #211E1B。 */
    val SkeletonLow = Color(0xFF171513)
    val SkeletonHigh = Color(0xFF211E1B)
    /** 清理页顶部渐变起点 rgba(10, 9, 8, 0.62)。 */
    val ScrimTop = Color(0xFF0A0908).copy(alpha = 0.62f)
    /** 进度条：未处理虚线 rgba(238,233,226,0.34)、看过保留 0.78、当前发光 rgba(255,246,232,0.8)。 */
    val SegPending = Paper.copy(alpha = 0.34f)
    val SegKept = Paper.copy(alpha = 0.78f)
    val SegCurrentGlow = Color(0xFFFFF6E8).copy(alpha = 0.8f)

    // —— P3 ——
    /** .tile 占位底 #1c1a18。 */
    val TileFill = Color(0xFF1C1A18)
    /** .list-fade：0 → rgba(15,14,13,0.92) @70% → room。 */
    val ListFadeMid = Room.copy(alpha = 0.92f)
    /** .btn-clear 描边 rgba(196,85,63,0.55)。 */
    val DeleteRim = Delete.copy(alpha = 0.55f)
    /** .menu 玻璃底 rgba(32,29,26,0.88)、投影 rgba(0,0,0,0.6)。 */
    val MenuGlass = Color(0xFF201D1A).copy(alpha = 0.88f)
    val MenuShadow = Color.Black.copy(alpha = 0.6f)
    /** .menu-rim rgba(255,240,225,0.12)。 */
    val MenuRim = Color(0xFFFFF0E1).copy(alpha = 0.12f)
    /** .menu .mi.on 底 rgba(238,233,226,0.1)。 */
    val MenuItemOn = Paper.copy(alpha = 0.10f)
    /** .menu-scrim rgba(10,9,8,0.48)。 */
    val MenuScrim = Color(0xFF0A0908).copy(alpha = 0.48f)
    /** 永久删除确认背后压暗 .confirm-dim rgba(10,9,8,0.64)。 */
    val DialogScrim = Color(0xFF0A0908).copy(alpha = 0.64f)
    /** 对话框投影 rgba(0,0,0,0.5) 0 24 48。 */
    val DialogShadow = Color.Black.copy(alpha = 0.5f)
    /** .pile .c3 #1D1A16，琥珀描边 0.35，琥珀光晕 0.12；星标阴影 0.6。 */
    val PileCard = Color(0xFF1D1A16)
    val PileRim = Amber.copy(alpha = 0.35f)
    val PileGlow = Amber.copy(alpha = 0.12f)
    val PileStarGlow = Amber.copy(alpha = 0.6f)
    /** .print / .hang 相纸 #DCD6CD、夹子 #3A3632、晾绳 rgba(238,233,226,0.24)。 */
    val PrintPaper = Color(0xFFDCD6CD)
    val PrintShadow = Color.Black.copy(alpha = 0.5f)
    val HangClip = Color(0xFF3A3632)
    val HangLine = Paper.copy(alpha = 0.24f)
    /** .eyebrow .fav-pip 琥珀光 0.6。 */
    val AmberGlow = Amber.copy(alpha = 0.6f)
    /** .tile .ck 投影 rgba(0,0,0,0.5)。 */
    val CheckShadow = Color.Black.copy(alpha = 0.5f)
    /** .undo 撤回胶囊 rgba(26,24,22,0.78)。 */
    val UndoGlass = Color(0xFF1A1816).copy(alpha = 0.78f)
    /** 排序提示 .c12-toast：rgba(30,28,25,0.86) + 内描边 rgba(255,240,225,0.12)。 */
    val ToastFill = Color(0xFF1E1C19).copy(alpha = 0.86f)
    val ToastRim = Color(0xFFFFF0E1).copy(alpha = 0.12f)
    /** 清理页底部渐变起点 rgba(10,9,8,0.7)。 */
    val ScrimBottom = Color(0xFF0A0908).copy(alpha = 0.70f)
}
