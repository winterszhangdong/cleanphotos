package com.winters.cleanphotos.domain.model

/** 压缩没做成的原因。界面只把这些换成短句，不展示异常堆栈。 */
enum class CompressFailure {
    Undecodable,
    HdrOnOldAndroid,
    NotEnoughSpace,
    DurationMismatch,
    AlreadySmall,
    Cancelled,
    Failed,
}

sealed interface CompressResult {
    data class Success(val created: PhotoItem) : CompressResult
    data class Failure(val reason: CompressFailure) : CompressResult
}
