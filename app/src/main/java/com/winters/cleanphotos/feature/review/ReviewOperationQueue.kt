package com.winters.cleanphotos.feature.review

import android.util.Log
import com.winters.cleanphotos.core.coroutines.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * 已经接纳的清理写入。消费者挂在进程作用域上：清理页 pop、ViewModel 清除后，
 * 排在后面的滑动仍按入队顺序落盘。系统授权在页面离开时由 ViewModel 结束等待，避免这条队列永远停住。
 */
@Singleton
class ReviewOperationQueue @Inject constructor(
    @ApplicationScope scope: CoroutineScope,
) {
    private val operations = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (op in operations) {
                try {
                    op()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "清理操作执行失败", e)
                }
            }
        }
    }

    fun enqueue(op: suspend () -> Unit) {
        val result = operations.trySend(op)
        check(result.isSuccess) { "清理写入队列已关闭: $result" }
    }

    private companion object {
        const val TAG = "ReviewOperationQueue"
    }
}
