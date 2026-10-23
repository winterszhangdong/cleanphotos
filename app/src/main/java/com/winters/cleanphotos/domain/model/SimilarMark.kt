package com.winters.cleanphotos.domain.model

/**
 * 相似组里一张照片的标记，和清理页的三种结果对应。
 * [Favorite] 也算留下，但收藏后会离开「全部照片」。
 */
enum class SimilarMark { Keep, Remove, Favorite }

/**
 * 上滑在收藏和留下之间切换，下滑在移走和留下之间切换。
 * 当前是另一侧时，滑向这边直接改成这一侧。
 */
fun SimilarMark.cycled(up: Boolean): SimilarMark = if (up) {
    if (this == SimilarMark.Favorite) SimilarMark.Keep else SimilarMark.Favorite
} else {
    if (this == SimilarMark.Remove) SimilarMark.Keep else SimilarMark.Remove
}
