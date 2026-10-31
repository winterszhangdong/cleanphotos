package com.winters.cleanphotos.core.coroutines

import android.os.SystemClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** 进程级作用域。清理页已经接纳的写入挂在这里，页面 pop 不能把它取消。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/** 单调时钟。撤回截止用它，不用会随系统动画缩放变成 0 的动画时钟。 */
fun interface UptimeClock {
    fun uptimeMillis(): Long
}

@Module
@InstallIn(SingletonComponent::class)
object AppRuntimeModule {
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Provides
    fun provideUptimeClock(): UptimeClock = UptimeClock { SystemClock.uptimeMillis() }
}
