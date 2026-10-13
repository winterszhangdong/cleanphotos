package com.winters.cleanphotos.feature.trash

import com.winters.cleanphotos.domain.model.SystemMediaActionRequest

/**
 * 垃圾桶页一次性事件，通过 Channel 发送给 UI 层。
 */
sealed interface TrashEffect {
    /** 系统要求用户确认媒体修改授权 */
    data class LaunchSystemMediaAction(val request: SystemMediaActionRequest) : TrashEffect
    /** Android 8–10：清空垃圾桶前申请 WRITE_EXTERNAL_STORAGE，结果回给 [TrashViewModel.onWritePermissionResult] */
    data object RequestWritePermission : TrashEffect
}
