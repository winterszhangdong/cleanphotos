package com.winters.cleanphotos.data.datasource

/**
 * 失效清理能不能把「这次没查到的 id」当成已经删除。
 *
 * [ids] 只在 [authoritative] 为 true 时使用。false 表示这次证据不够（查询失败、
 * 还有卸载卷但查不到它们的 id、或系统版本给不出全量证明），必须整次跳过清理。
 */
internal data class MediaPresence(
    val ids: Set<Long>,
    val authoritative: Boolean,
)

internal object MediaPresenceRules {
    /**
     * @param onlineIds 默认查询看到的 id，不含卸载卷
     * @param trashedIds 系统垃圾桶；查询失败是 null
     * @param unmountedInclusiveIds Android 12+ 带「包含最近卸载卷」的存在性查询；失败或不适用是 null
     * @param recentVolumes Android 11+ 最近出现过的卷；失败是 null
     * @param mountedVolumes 当前挂着的卷；失败是 null
     */
    fun decide(
        sdkInt: Int,
        onlineIds: Set<Long>,
        trashedIds: Set<Long>?,
        unmountedInclusiveIds: Set<Long>?,
        recentVolumes: Set<String>?,
        mountedVolumes: Set<String>?,
    ): MediaPresence {
        if (sdkInt >= 31) {
            val ids = unmountedInclusiveIds ?: return MediaPresence(emptySet(), authoritative = false)
            return MediaPresence(ids, authoritative = true)
        }
        if (sdkInt >= 30) {
            if (trashedIds == null || recentVolumes == null || mountedVolumes == null) {
                return MediaPresence(emptySet(), authoritative = false)
            }
            // 最近出现过、现在没挂上的卷，默认查询里没有，也不能按 id 对上本地记录。
            if (!mountedVolumes.containsAll(recentVolumes)) {
                return MediaPresence(emptySet(), authoritative = false)
            }
            return MediaPresence(onlineIds + trashedIds, authoritative = true)
        }
        // Android 10 及以下没有「最近卸载卷」查询，看不见的 id 可能只是卡被拔了。
        return MediaPresence(emptySet(), authoritative = false)
    }
}
