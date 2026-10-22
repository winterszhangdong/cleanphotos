package com.winters.cleanphotos.domain.model

/**
 * 照片页范围。和「只看没看过」分开：未看是叠加开关，不是一种范围。
 */
enum class PhotoFilterMode {
    /** 全部照片（默认） */
    AllPhotos,
    /** 截图（路径或文件夹名命中截图关键字） */
    Screenshots,
    /** 大文件（体积达到 [SmartFilterRules.LARGE_FILE_MIN_BYTES]） */
    LargeFiles,
    /** 聊天软件存图（微信 / WhatsApp / Telegram / QQ 目录） */
    ChatApps,
    /** 那年今天：本地月日与今天相同，且年份更早 */
    OnThisDay,
}
