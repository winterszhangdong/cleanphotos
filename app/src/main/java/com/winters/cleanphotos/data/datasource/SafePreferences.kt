package com.winters.cleanphotos.data.datasource

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import java.io.IOException

/**
 * 读 DataStore 时的 IO 失败（存储满、文件被系统占用）按空配置继续，不让订阅它的页面整条数据链崩掉。
 * 文件损坏由 DataStoreModule 的 ReplaceFileCorruptionHandler 处理；其它异常照常抛出。
 */
internal fun DataStore<Preferences>.safeData(): Flow<Preferences> = data.catch { e ->
    if (e is IOException) {
        Log.e("PreferencesDataStore", "读取 DataStore 失败，按空配置继续", e)
        emit(emptyPreferences())
    } else {
        throw e
    }
}
