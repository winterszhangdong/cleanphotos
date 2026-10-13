package com.winters.cleanphotos.core.design

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * 系统「动画时长缩放」。Compose 的有限动画已经通过 MotionDurationScale 乘上这个值（=0 时直接跳终态）；
 * 这里额外暴露给无限循环（呼吸、空状态、晾片）和「去向提示」降级使用。
 */
object AnimationScale {
    fun isEnabled(scale: Float): Boolean = scale > 0f
}

/** true 表示系统没有关闭动画。 */
val LocalAnimationsEnabled = staticCompositionLocalOf<Boolean> {
    error("LocalAnimationsEnabled 未提供，请在 CleanphotosTheme 内使用")
}

@Composable
fun ProvideAnimationScale(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val resolver = context.contentResolver
    var scale by remember {
        mutableFloatStateOf(Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f))
    }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                scale = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    CompositionLocalProvider(LocalAnimationsEnabled provides AnimationScale.isEnabled(scale), content = content)
}
