package com.winters.cleanphotos.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.winters.cleanphotos.core.datastore.DataStoreKeys
import com.winters.cleanphotos.core.permission.PermissionChecker
import com.winters.cleanphotos.data.datasource.AppFavoriteStateDataSource
import com.winters.cleanphotos.data.datasource.AppSeenStateDataSource
import com.winters.cleanphotos.data.datasource.AppTrashStateDataSource
import com.winters.cleanphotos.data.datasource.MediaStorePhotoDataSource
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.repository.PhotoRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 照片仓库实现。
 *
 * 核心语义：
 * - observeAllPhotos()：返回 MediaStore 照片中不在 App 垃圾桶且不在 App 收藏的子集
 * - observeFavoritedPhotos()：返回在 App 收藏中且不在 App 垃圾桶中的照片
 * - observeTrashedPhotos()：返回 App 内垃圾桶中的照片（按 movedToTrashAt 倒序）
 * - refreshPhotos()：刷新 MediaStore 后串行调用三项 removeMissingEntries + 一次性迁移
 * - refreshTrashedPhotos()：复用 refreshPhotos()，不再单独查询系统垃圾桶
 * - PhotoItem.isFavorite 来源改为 AppFavoriteStore
 */
@Singleton
class PhotoRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaStoreDataSource: MediaStorePhotoDataSource,
    private val appTrashStateDataSource: AppTrashStateDataSource,
    private val appFavoriteStateDataSource: AppFavoriteStateDataSource,
    private val appSeenStateDataSource: AppSeenStateDataSource,
    private val dataStore: DataStore<Preferences>
) : PhotoRepository {

    companion object {
        private const val TAG = "PhotoRepository"
        private val HAS_MIGRATED_KEY = booleanPreferencesKey(DataStoreKeys.HAS_MIGRATED_FAVORITES)
    }

    /** MediaStore 全部照片（未过滤） */
    private val allMediaStorePhotos = MutableStateFlow<List<PhotoItem>>(emptyList())

    private val refreshMutex = Mutex()

    /** 上次成功查询 MediaStore 的时刻（单调时钟 ms）；null = 本进程还没加载过。 */
    @Volatile
    private var lastRefreshAt: Long? = null

    /** 迁移是否已执行（内存缓存，避免每次 refresh 都读 DataStore） */
    private var migrationChecked = false

    /**
     * 观察全部照片（排除 App 内垃圾桶和 App 内收藏中的照片）。
     * 同时将 PhotoItem.isFavorite 设置为来自 AppFavoriteStore 的状态。
     */
    override fun observeAllPhotos(): Flow<List<PhotoItem>> {
        return combine(
            allMediaStorePhotos,
            appTrashStateDataSource.observeEntries(),
            appFavoriteStateDataSource.observeEntries()
        ) { photos, trashEntries, favoriteEntries ->
            val trashedIds = HashSet<Long>(trashEntries.size).apply { trashEntries.forEach { add(it.photoId) } }
            val favoriteIds = HashSet<Long>(favoriteEntries.size).apply { favoriteEntries.forEach { add(it.photoId) } }
            buildList {
                for (photo in photos) {
                    if (photo.id in trashedIds || photo.id in favoriteIds) continue
                    add(if (photo.isFavorite) photo.copy(isFavorite = false) else photo)
                }
            }
        }
    }

    /**
     * 观察已收藏照片（在 App 收藏中且不在垃圾桶中）。
     */
    override fun observeFavoritedPhotos(): Flow<List<PhotoItem>> {
        return combine(
            allMediaStorePhotos,
            appTrashStateDataSource.observeEntries(),
            appFavoriteStateDataSource.observeEntries()
        ) { photos, trashEntries, favoriteEntries ->
            val trashedIds = HashSet<Long>(trashEntries.size).apply { trashEntries.forEach { add(it.photoId) } }
            val favoriteIds = HashSet<Long>(favoriteEntries.size).apply { favoriteEntries.forEach { add(it.photoId) } }
            buildList {
                for (photo in photos) {
                    if (photo.id !in favoriteIds || photo.id in trashedIds) continue
                    add(if (photo.isFavorite) photo else photo.copy(isFavorite = true))
                }
            }
        }
    }

    /**
     * 刷新 MediaStore 照片，执行失效清理和一次性迁移。
     *
     * 流程：
     * 1. 查询 MediaStore
     * 2. 执行一次性迁移（首次启动时）
     * 3. 串行调用三项 removeMissingEntries（Favorite → Seen → Trash）
     * 4. MediaStore 查询异常时跳过全部清理
     * 5. 只有部分照片访问权时跳过迁移和清理：查询结果只是用户选中的那几张，
     *    拿它当整个图库会把其余照片的垃圾桶 / 收藏 / 看过状态全部删掉
     */
    override suspend fun refreshPhotos() {
        // 串行：照片页 / 收藏页 / 垃圾桶 / 回到前台可能同时刷新，并发时先发起但后返回的旧查询会覆盖新结果
        refreshMutex.withLock { refreshLocked() }
    }

    /** 回到前台：距上次刷新完成不足 [minIntervalMs] 就跳过（刚启动、刚从系统删除确认回来时已经刷过）。 */
    override suspend fun refreshPhotosIfStale(minIntervalMs: Long) {
        refreshMutex.withLock {
            val last = lastRefreshAt
            if (last != null && elapsedMillis() - last < minIntervalMs) return
            refreshLocked()
        }
    }

    /** 进程被回收后清理页先于照片页重建：内存照片表还没加载过就先查一次（等待中别人加载完了就不重复）。 */
    override suspend fun ensurePhotosLoaded() {
        if (lastRefreshAt != null) return
        refreshMutex.withLock {
            if (lastRefreshAt == null) refreshLocked()
        }
    }

    private suspend fun refreshLocked() {
        // MediaStore 主查询异常向上抛出，让 ViewModel 能展示错误状态
        val photos = withContext(Dispatchers.IO) {
            mediaStoreDataSource.queryAllPhotos()
        }
        allMediaStorePhotos.value = photos
        lastRefreshAt = elapsedMillis()
        if (!PermissionChecker.hasFullPhotoAccess(context)) return

        // 迁移和清理失败不影响主流程
        try {
            migrateFavoritesIfNeeded(photos)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "迁移收藏失败", e)
        }

        // 系统垃圾桶查询是 binder 调用，不能留在调用方的主线程（P8 实测曾卡主线程 11–17ms）
        val existingIds = withContext(Dispatchers.IO) { buildExistingPhotoIds(photos) }
        try {
            cleanupStores(existingIds)
        } catch (e: CancellationException) {
            throw e
        } catch (e: java.io.IOException) {
            // 存储满等写入失败：照片已经加载好了，失效条目下次刷新再清
            Log.w(TAG, "清理失效条目失败", e)
        }
    }

    private fun elapsedMillis(): Long = System.nanoTime() / 1_000_000L

    /**
     * 观察 App 内垃圾桶照片。
     * 从 MediaStore 照片中筛选在垃圾桶条目中的照片，按 movedToTrashAt 倒序排列。
     */
    override fun observeTrashEntries(): Flow<List<com.winters.cleanphotos.domain.model.AppTrashEntry>> =
        appTrashStateDataSource.observeEntries()

    override fun observeTrashedPhotos(): Flow<List<PhotoItem>> {
        return combine(
            allMediaStorePhotos,
            appTrashStateDataSource.observeEntries()
        ) { photos, trashEntries ->
            val photoMap = photos.associateBy { it.id }
            trashEntries.mapNotNull { entry -> photoMap[entry.photoId] }
        }
    }

    /**
     * 刷新垃圾桶列表。复用 refreshPhotos()。
     */
    override suspend fun refreshTrashedPhotos() {
        refreshPhotos()
    }

    /**
     * 构建当前存在的照片 ID 集合（未进系统垃圾桶 ∪ 已进系统垃圾桶）。
     * 返回 null 表示查询不完整（任一类查询失败），调用方应跳过清理。
     * 返回空集表示媒体库确实为空，调用方应正常执行清理以移除 stale 条目。
     */
    private fun buildExistingPhotoIds(normalPhotos: List<PhotoItem>): Set<Long>? {
        val trashedPhotos = try {
            mediaStoreDataSource.queryTrashedPhotos()
        } catch (e: Exception) {
            Log.w(TAG, "查询系统垃圾桶失败，跳过失效清理", e)
            return null
        }
        return (normalPhotos.map { it.id } + trashedPhotos.map { it.id }).toSet()
    }

    /**
     * 串行调用三项 removeMissingEntries：Favorite → Seen → Trash。
     * existingIds 为 null 时跳过（查询不完整，无法判断哪些条目已失效）。
     */
    private suspend fun cleanupStores(existingIds: Set<Long>?) {
        if (existingIds == null) return
        // 三项共用同一个 DataStore，合成一次写入；没有失效条目时 DataStore 内容不变，不会重写文件
        dataStore.edit { p ->
            appFavoriteStateDataSource.removeMissingIn(p, existingIds)
            appSeenStateDataSource.removeMissingIn(p, existingIds)
            appTrashStateDataSource.removeMissingIn(p, existingIds)
        }
    }

    /**
     * 一次性迁移：将 MediaStore 中 IS_FAVORITE == true 的照片写入 AppFavoriteStore。
     * 仅在首次启动时执行，后续启动不再重复。
     */
    private suspend fun migrateFavoritesIfNeeded(mediaStorePhotos: List<PhotoItem>) {
        if (migrationChecked) return

        val hasMigrated = dataStore.data.first()[HAS_MIGRATED_KEY] ?: false

        if (hasMigrated) {
            migrationChecked = true
            return
        }

        // 查询 MediaStore 中 IS_FAVORITE == true 的照片
        val systemFavorites = mediaStorePhotos.filter { it.isFavorite }
        val now = System.currentTimeMillis()
        // 一次写完；失败直接抛给 refreshPhotos，不标记完成，下次刷新重试
        appFavoriteStateDataSource.upsertEntries(systemFavorites.associate { it.id to now })

        // 标记迁移完成
        dataStore.edit { preferences ->
            preferences[HAS_MIGRATED_KEY] = true
        }
        migrationChecked = true
        Log.i(TAG, "系统收藏迁移完成，共迁移 ${systemFavorites.size} 条")
    }
}
