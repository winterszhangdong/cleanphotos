package com.winters.cleanphotos.domain.model

/** 清理业务规则常量（用户最终决定，见 docs/ui-design.md 第 1 节）。 */
object ReviewRules {
    /** 决定 2：小结插页要求这一天会话内至少 6 张。 */
    const val DAY_SUMMARY_MIN_PHOTOS = 6
    /** 进度条超过 40 张改为连续比例条。 */
    const val PROGRESS_CONTINUOUS_THRESHOLD = 40
    /** 决定 10：累计 20 次成功上下滑后隐藏方向提示小点。 */
    const val DIRECTION_HINT_HIDE_AFTER = 20
}
