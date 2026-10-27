package com.winters.cleanphotos.data.repository

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.winters.cleanphotos.core.permission.PermissionChecker
import com.winters.cleanphotos.data.datasource.AppFavoriteStateDataSource
import com.winters.cleanphotos.data.datasource.AppFilingLogDataSource
import com.winters.cleanphotos.data.datasource.MediaStoreAlbumDataSource
import com.winters.cleanphotos.domain.model.FilingOutcome
import com.winters.cleanphotos.domain.model.FilingRecord
import com.winters.cleanphotos.domain.model.MovedPhoto
import com.winters.cleanphotos.domain.model.PhotoItem
import com.winters.cleanphotos.domain.model.PhotoOperationFailureReason
import com.winters.cleanphotos.domain.model.PhotoOperationResult
import com.winters.cleanphotos.domain.model.SystemMediaActionRequest
import com.winters.cleanphotos.domain.model.SystemMediaActionType
import com.winters.cleanphotos.domain.repository.AlbumRepository
import com.winters.cleanphotos.domain.repository.PhotoRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlbumRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val albumDataSource: MediaStoreAlbumDataSource,
    private val favoriteDataSource: AppFavoriteStateDataSource,
    private val filingLogDataSource: AppFilingLogDataSource,
    private val photoRepository: PhotoRepository,
    private val dataStore: DataStore<Preferences>,
) : AlbumRepository {

    override fun observeFilingLog(): Flow<List<FilingRecord>> = filingLogDataSource.observeEntries()

    override suspend fun prepareMove(photos: List<PhotoItem>): PhotoOperationResult {
        if (!PermissionChecker.hasLegacyWritePermission(context)) {
            return PhotoOperationResult.Failure(PhotoOperationFailureReason.WritePermissionRequired)
        }
        // checkUriPermission / createWriteRequest 都是 binder 调用
        val sender = withContext(Dispatchers.IO) { albumDataSource.writeRequest(photos) }
            ?: return PhotoOperationResult.Success
        return PhotoOperationResult.NeedSystemApproval(
            SystemMediaActionRequest(photos, SystemMediaActionType.MoveToAlbum, UUID.randomUUID().toString(), sender),
        )
    }

    override suspend fun fileToAlbum(photos: List<PhotoItem>, relativePath: String): FilingOutcome {
        val moved = mutableListOf<MovedPhoto>()
        val failed = mutableListOf<PhotoItem>()
        withContext(Dispatchers.IO) {
            for (photo in photos) {
                // 放在存储根目录、没有文件夹的照片撤回时回不去，不动它
                val from = photo.relativePath
                if (from != null && albumDataSource.move(photo, relativePath)) moved += MovedPhoto(photo, from) else failed += photo
            }
        }
        var favoritedAt = emptyMap<Long, Long>()
        if (moved.isNotEmpty()) {
            val ids = moved.mapTo(HashSet()) { it.photo.id }
            val now = System.currentTimeMillis()
            dataStore.edit { p ->
                favoritedAt = favoriteDataSource.entriesIn(p).filter { it.photoId in ids }.associate { it.photoId to it.favoritedAt }
                favoriteDataSource.removeIn(p, ids)
                filingLogDataSource.upsertIn(p, moved.map { FilingRecord(it.photo.id, relativePath, it.photo.dateGroup, now) })
            }
            // 文件夹变了：重读一次，袋子张数、露角和照片的 relativePath 跟上
            photoRepository.refreshPhotos()
        }
        return FilingOutcome(relativePath, moved, failed, favoritedAt)
    }

    override suspend fun undoFiling(outcome: FilingOutcome): List<PhotoItem> {
        val failed = withContext(Dispatchers.IO) {
            outcome.moved
                .filterNot { albumDataSource.move(it.photo.copy(relativePath = outcome.relativePath), it.fromPath) }
                .map { it.photo }
        }
        val failedIds = failed.mapTo(HashSet()) { it.id }
        val back = outcome.moved.map { it.photo.id }.filterNot { it in failedIds }.toSet()
        if (back.isNotEmpty()) {
            dataStore.edit { p ->
                favoriteDataSource.upsertIn(p, outcome.favoritedAt.filterKeys { it in back })
                filingLogDataSource.removeIn(p, back)
            }
            photoRepository.refreshPhotos()
        }
        return failed
    }

    override suspend fun systemFavoriteRequest(photos: List<PhotoItem>): PhotoOperationResult? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || photos.isEmpty()) return null
        val sender = withContext(Dispatchers.IO) { albumDataSource.favoriteRequest(photos) }
        return PhotoOperationResult.NeedSystemApproval(
            SystemMediaActionRequest(photos, SystemMediaActionType.SystemFavorite, UUID.randomUUID().toString(), sender),
        )
    }
}
