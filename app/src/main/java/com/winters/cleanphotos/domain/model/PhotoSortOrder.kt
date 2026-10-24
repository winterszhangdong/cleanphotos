package com.winters.cleanphotos.domain.model

/**
 * 首页照片排序方式。
 */
enum class PhotoSortOrder {
    /** 最新照片在前（默认）。菜单里可选。 */
    NewestFirst,
    /** 最旧照片在前。菜单里可选。 */
    OldestFirst,
    /**
     * 文件从大到小。不出现在菜单里，只在大文件范围和从那里进入的清理会话使用。
     * size 相同按 dateTaken 新到旧。size 为 null 直接出错（大文件范围不会放进这种照片）。
     */
    LargestFirst,
}
