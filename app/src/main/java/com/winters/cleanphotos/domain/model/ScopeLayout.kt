package com.winters.cleanphotos.domain.model

/**
 * 照片页一个范围的分组方式。相似列表不画这个网格。
 * 属性挂在 [com.winters.cleanphotos.feature.allphotos.LibraryScope] 上。
 */
enum class ScopeLayout {
    /** 按天。每天最多露出 8 张。 */
    Day,

    /** 按月。不截断。 */
    Month,

    /** 不分组，平铺。不截断，不画年份和日期行。 */
    Flat,
}
