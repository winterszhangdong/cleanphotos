package com.winters.cleanphotos.core.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.dp

/**
 * 全局 Motion token，数值与 docs/ui-design.md 2.4 一致，
 * 并叠加 docs/ui-design.md 第 1 节用户最终决定。页面里禁止手写动画数字。
 */
object MotionDurations {
    /** 按压、控件淡出。 */
    const val MICRO = 100
    /** 图标切换、文案切换；跨天进度条交叉淡入（决定 1）。 */
    const val SHORT = 150
    /** 进度格、撤回条、导航文字色。 */
    const val MEDIUM = 280
    /** 共享元素、飞入导航。 */
    const val LONG = 450
    /** 松手后提示圆钮与琥珀光淡出（3800ms × 7%）。 */
    const val RELEASE_CUE_FADE = 266
    /** 收回：大图缩回格子 400ms；返回时控件先 MICRO 淡出。 */
    const val PHOTO_COLLAPSE = 400
    /** 显影。 */
    const val DEVELOP = 600
    /** 分批出现的步长。 */
    const val STAGGER = 40
    /** 分批最多步数。 */
    const val STAGGER_MAX_STEPS = 12

    // —— 具名时长（不在五档内，按原值集中定义） ——
    /** 菜单缩放 0.92→1 + 淡入。 */
    const val MENU = 180
    /** 拖动开始后返回 / 日期 / 进度条淡出窗口。 */
    const val CHROME_FADE_ON_DRAG = 80
    /** 多选选中格弹性缩放（决定 8）。 */
    const val SELECT_POP = 160
    /** 撤回条收起。 */
    const val UNDO_BAR_EXIT = 200
    /** 撤回倒计时。 */
    const val UNDO_COUNTDOWN = 4000
    /** 进度当前格呼吸周期。 */
    const val PROGRESS_BREATH = 1600
    /** 骨架呼吸周期、相邻错开。 */
    const val SKELETON_BREATH = 2400
    const val SKELETON_STAGGER = 200
    /** 首次教学每段。 */
    const val TUTORIAL_SEGMENT = 2400
    /** 空状态周期 / 上推 / 停留 / 回落。 */
    const val EMPTY_CYCLE = 3600
    const val EMPTY_PUSH = 440
    const val EMPTY_HOLD = 900
    const val EMPTY_DROP = 680
    /** 晾片摆动周期。 */
    const val CLOTHESLINE_SWING = 4000
    /** 清空溶解每格 / 步长 / 数字归零。 */
    const val DISSOLVE_ITEM = 380
    const val DISSOLVE_STAGGER = 60
    const val DISSOLVE_COUNT_TO_ZERO = 900
    /** 最后一格溶解完 160ms 后空状态升起（T = 6800：最后一格 3676 + 380 → 空状态 62% = 4216）。 */
    const val DISSOLVE_EMPTY_DELAY = 160
    /** interaction.md：收藏 / 移走离场主体位移上限。 */
    const val EXIT_MAIN_MAX = 250
    /** 决定 9：共享元素 0–80 按下，80–400 放大，400–520 控件淡入。 */
    const val SHARED_PRESS = 80
    const val SHARED_EXPAND_END = 400
    const val SHARED_CHROME_END = 520
    /** 80–400ms 放大段（settle 弹簧在这段内走完并收住）。 */
    const val SHARED_EXPAND = SHARED_EXPAND_END - SHARED_PRESS
    /** 400–520ms 控件淡入。 */
    const val SHARED_CHROME_FADE = SHARED_CHROME_END - SHARED_EXPAND_END

    /** 排序：点击后 50ms 图标开始转 180°（落定弹簧），重排每格步长 14ms（落定弹簧）。 */
    const val SORT_ICON_DELAY = 50
    const val REORDER_STAGGER = 14
    /** 排序提示：点击后 8ms 出现，224ms 减速淡入上移 6dp，停留到 1296ms，224ms 淡出（T = 5600 的 43% → 47% → 66% → 70%）。 */
    const val SORT_TOAST_IN = 224
    const val SORT_TOAST_HOLD = 1064
    const val SORT_TOAST_OUT = 224
}

object MotionEasings {
    /** 标准 0.2, 0, 0, 1。 */
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    /** 减速进入 0.05, 0.7, 0.1, 1。 */
    val Decelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    /** 加速离场 0.3, 0, 0.8, 0.15。 */
    val Accelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    /** 显影 0.45, 0, 0.2, 1。 */
    val Develop: Easing = CubicBezierEasing(0.45f, 0f, 0.2f, 1f)
    /** --ease-out 0.2, 0.8, 0.2, 1（用于教学光点）。 */
    val DraftEaseOut: Easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
    /** 数字滚动：1 − (1 − t)³（三次减速），用于清空时「可释放」归零、小结数字。 */
    val CountDecelerate: Easing = Easing { t -> 1f - (1f - t) * (1f - t) * (1f - t) }
}

/** 弹簧参数，mass = 1。 */
/** 弹簧「收住」的判定：剩余幅度 0.1%。 */
private const val SETTLE_EPSILON = 0.001f

data class SpringToken(val dampingRatio: Float, val stiffness: Float) {
    fun <T> spec(visibilityThreshold: T? = null): SpringSpec<T> =
        spring(dampingRatio = dampingRatio, stiffness = stiffness, visibilityThreshold = visibilityThreshold)

    /**
     * 从 0 到 1、初速 0 的弹簧在 ms 时刻的值（mass = 1，解析解）。用于一条时间轴派生多段弹簧，
     * 与 spec() 跑出来的曲线一致，不必为每段各开一个 Animatable。
     */
    /**
     * 把这条弹簧从起步到收住（包络衰减到 [SETTLE_EPSILON]）的整段曲线压进一个定长时间窗，当 Easing 用：
     * 形状（含轻微过冲）与弹簧一致，但保证在给定时长内准确结束。用于必须在固定窗口里收住的弹簧（决定 9：80–400ms）。
     */
    fun asEasing(): Easing {
        val span = settleMs()
        val end = valueAt(span)
        return Easing { f -> if (f >= 1f) 1f else valueAt(f * span) / end }
    }

    /** 包络 e^(-ζωt) 衰减到 [SETTLE_EPSILON] 的时刻（ms）。 */
    fun settleMs(): Float {
        val decay = minOf(dampingRatio, 1f) * kotlin.math.sqrt(stiffness)
        return (kotlin.math.ln(1f / SETTLE_EPSILON) / decay) * 1000f
    }

    fun valueAt(ms: Float): Float {
        if (ms <= 0f) return 0f
        val t = ms / 1000f
        val w = kotlin.math.sqrt(stiffness)
        val z = dampingRatio
        return if (z < 1f) {
            val wd = w * kotlin.math.sqrt(1f - z * z)
            1f - kotlin.math.exp(-z * w * t) * (kotlin.math.cos(wd * t) + z * w / wd * kotlin.math.sin(wd * t))
        } else {
            1f - kotlin.math.exp(-w * t) * (1f + w * t)
        }
    }
}

object MotionSprings {
    /** 利落 1.0 / 1500：左右切换落位、按下回弹。 */
    val Snappy = SpringToken(1.0f, 1500f)
    /** 落定 0.75 / 400：拖动回弹、下一张、导航条、重排、共享元素收尾。 */
    val Settle = SpringToken(0.75f, 400f)
    /** 弹跳 0.5 / 400：勾选 pop、缩放越界回弹。 */
    val Bouncy = SpringToken(0.5f, 400f)
    /** 舒缓 0.85 / 200：小结、对话框。 */
    val Gentle = SpringToken(0.85f, 200f)
}

/** 显影通道参数：从 start 到 1，在 [startMs, endMs] 窗口内按显影曲线变化。 */
data class DevelopChannel(val start: Float, val startMs: Int, val endMs: Int)

data class DevelopProfile(
    val durationMs: Int,
    val brightness: DevelopChannel,
    val contrast: DevelopChannel,
    val saturation: DevelopChannel,
    val scaleFrom: Float,
    val easing: Easing,
    /** API 33+ 颗粒起始透明度；0 表示该档不加颗粒。 */
    val grainFrom: Float,
)

object DevelopProfiles {
    /** 单张显影：亮度 0.06（0–500）、反差 0.55（80–600）、饱和 0（200–600），scale 1.035，颗粒 0.55。 */
    val Single = DevelopProfile(
        durationMs = MotionDurations.DEVELOP,
        brightness = DevelopChannel(0.06f, 0, 500),
        contrast = DevelopChannel(0.55f, 80, 600),
        saturation = DevelopChannel(0f, 200, 600),
        scaleFrom = 1.035f,
        easing = MotionEasings.Develop,
        grainFrom = 0.55f,
    )

    /** 加载后显影：亮度 0、反差 0.6、饱和 0，320ms 减速曲线，同一行 40ms 错开。 */
    val Loading = DevelopProfile(
        durationMs = 320,
        brightness = DevelopChannel(0f, 0, 320),
        contrast = DevelopChannel(0.6f, 0, 320),
        saturation = DevelopChannel(0f, 0, 320),
        scaleFrom = 1f,
        easing = MotionEasings.Decelerate,
        grainFrom = 0f,
    )
    const val LOADING_ROW_STAGGER = MotionDurations.STAGGER

    /** 网格首屏：单格 520ms，步长 55ms（行 + 列），最多 12 步，显影曲线。起点沿用原规格的 0.08 / 0.6 / 0。 */
    val GridFirstScreen = DevelopProfile(
        durationMs = 520,
        brightness = DevelopChannel(0.08f, 0, 520),
        contrast = DevelopChannel(0.6f, 0, 520),
        saturation = DevelopChannel(0f, 0, 520),
        scaleFrom = 1f,
        easing = MotionEasings.Develop,
        grainFrom = 0f,
    )
    const val GRID_STAGGER = 55
    const val GRID_MAX_STEPS = MotionDurations.STAGGER_MAX_STEPS

    /** 决定 7：清理页操作后下一张出场，起始亮度 ≥ 0.6、时长 ≤ 280ms，配合 0.94→1 的落定弹簧。 */
    val ReviewNext = DevelopProfile(
        durationMs = MotionDurations.MEDIUM,
        brightness = DevelopChannel(0.6f, 0, MotionDurations.MEDIUM),
        contrast = DevelopChannel(0.85f, 0, MotionDurations.MEDIUM),
        saturation = DevelopChannel(0.6f, 0, MotionDurations.MEDIUM),
        scaleFrom = 1f,
        easing = MotionEasings.Develop,
        grainFrom = 0f,
    )
    const val REVIEW_NEXT_SCALE_FROM = 0.94f
}

/** 清理页手势阈值（interaction.md「滑动手感硬性要求」）。 */
object GestureTokens {
    /** 垂直触发阈值 120dp；横屏按短边算。 */
    val VerticalThreshold = 120.dp
    /** 缩放 / 压暗 / 去色的封顶距离。 */
    val EffectCapDistance = 170.dp
    /** 甩动触发：垂直速度阈值与最小位移（用户要求快速一甩也触发；数值为本次定义，见报告）。 */
    const val VERTICAL_FLING_VELOCITY_DP = 1000f
    val MinFlingDistance = 24.dp
    /** 水平翻页：屏宽 12%，48–96dp；甩动 600dp/s。 */
    const val HORIZONTAL_THRESHOLD_FRACTION = 0.12f
    val HorizontalThresholdMin = 48.dp
    val HorizontalThresholdMax = 96.dp
    const val HORIZONTAL_FLING_VELOCITY_DP = 600f
    /** 方向判定主轴比例。 */
    const val DIRECTION_RATIO = 1.2f
    /** 会话首尾阻尼：L = 屏宽 × 0.3，系数 0.55。 */
    const val EDGE_RESISTANCE_LENGTH_FRACTION = 0.3f
    const val EDGE_RESISTANCE_FACTOR = 0.55f
    /** 决定 3：拖动缩放到 0.9，下滑饱和到 0.35、亮度到 0.55。 */
    const val DRAG_MIN_SCALE = 0.9f
    const val DRAG_MIN_SATURATION = 0.35f
    const val DRAG_MIN_BRIGHTNESS = 0.55f
    /** 左右翻页进入页亮度起点。 */
    const val PAGE_ENTER_BRIGHTNESS = 0.7f
    /** 缩放范围。 */
    const val ZOOM_MIN = 0.8f
    const val ZOOM_REST_MIN = 1f
    const val ZOOM_MAX = 3f
    const val DOUBLE_TAP_SCALE = 2f

    /** 缩小到 1× 以下继续给阻尼，渐近到 ZOOM_MIN（0.8）；1× 处斜率为 1，不跳。 */
    fun pinchResistance(raw: Float): Float {
        if (raw >= ZOOM_REST_MIN) return raw
        val room = ZOOM_REST_MIN - ZOOM_MIN
        val x = (ZOOM_REST_MIN - raw) / room
        return ZOOM_REST_MIN - room * (1f - 1f / (x + 1f))
    }

    /** 阻尼：offset = L·(1 − 1/(d·0.55/L + 1))。 */
    fun edgeResistance(distance: Float, length: Float): Float {
        require(length > 0f)
        val sign = if (distance < 0f) -1f else 1f
        val d = kotlin.math.abs(distance)
        return sign * length * (1f - 1f / (d * EDGE_RESISTANCE_FACTOR / length + 1f))
    }
}
