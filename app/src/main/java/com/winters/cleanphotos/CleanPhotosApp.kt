package com.winters.cleanphotos

import android.app.Application
import androidx.core.content.res.ResourcesCompat
import coil.ImageLoader
import coil.ImageLoaderFactory
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlin.concurrent.thread

@HiltAndroidApp
class CleanPhotosApp : Application(), ImageLoaderFactory {

    @Inject
    lateinit var imageLoader: ImageLoader

    override fun newImageLoader(): ImageLoader = imageLoader

    override fun onCreate() {
        super.onCreate()
        preloadFonts()
    }

    /**
     * 后台预载全部字重。Noto Sans SC 单个字重就有数 MB，第一次用到某个字重（例如第一次切到收藏页）时
     * 在主线程同步加载，perfetto 里看到一帧 71ms 卡在 TextStringSimpleNode::measure。
     * Compose 的资源字体走 ResourcesCompat.getFont，命中它的进程内缓存后就不再阻塞。
     */
    private fun preloadFonts() {
        thread(name = "font-preload", priority = Thread.MIN_PRIORITY) {
            FONTS.forEach { id -> runCatching { ResourcesCompat.getFont(this, id) } }
        }
    }

    private companion object {
        val FONTS = intArrayOf(
            R.font.noto_sans_sc_400, R.font.noto_sans_sc_500, R.font.noto_sans_sc_600, R.font.noto_sans_sc_700,
            R.font.schibsted_grotesk_500, R.font.schibsted_grotesk_600, R.font.schibsted_grotesk_700,
            R.font.ibm_plex_mono_400, R.font.ibm_plex_mono_500,
        )
    }
}
