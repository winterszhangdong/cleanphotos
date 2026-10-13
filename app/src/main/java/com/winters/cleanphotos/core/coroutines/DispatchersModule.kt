package com.winters.cleanphotos.core.coroutines

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier

/**
 * 页面状态的计算线程（排序、分组、会话序列、小结）。
 * 数据流的 combine / map 默认跑在收集方的 viewModelScope（主线程）上，一次滑动会连锁触发多轮全库计算，
 * 正好压在离场动画里；ViewModel 用 flowOn 把这些挪到这里。测试里换成 TestDispatcher。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {
    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default
}
