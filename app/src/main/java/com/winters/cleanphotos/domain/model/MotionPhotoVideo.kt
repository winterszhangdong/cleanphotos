package com.winters.cleanphotos.domain.model

/**
 * 动态照片文件里内嵌 MP4 的字节范围。
 * [offset] 起 4 字节之后必须是 `ftyp`，[length] 是校验后最后一个合法 box 的结尾，不含厂商尾标。
 */
data class MotionPhotoVideo(
    val offset: Long,
    val length: Long,
)
