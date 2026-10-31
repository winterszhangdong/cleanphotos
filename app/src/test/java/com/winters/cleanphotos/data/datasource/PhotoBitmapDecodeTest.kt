package com.winters.cleanphotos.data.datasource

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream

/** inJustDecodeBounds 的 decodeStream 按契约返回 null，这个 null 不能当成解码失败。 */
class PhotoBitmapDecodeTest {
    @Before
    fun setUp() {
        mockkStatic(BitmapFactory::class)
    }

    @After
    fun tearDown() {
        unmockkStatic(BitmapFactory::class)
    }

    @Test
    fun `Android 8 和 9 的 bounds 返回 null 仍解码出图`() {
        val resolver = mockk<ContentResolver>()
        val context = mockk<Context>()
        val uri = mockk<Uri>()
        val bitmap = mockk<Bitmap>()
        every { context.contentResolver } returns resolver
        every { bitmap.config } returns Bitmap.Config.RGB_565
        every { resolver.openInputStream(uri) } answers { ByteArrayInputStream(byteArrayOf(1)) }
        every { BitmapFactory.decodeStream(any(), isNull(), any()) } answers {
            val opts = thirdArg<BitmapFactory.Options>()
            if (opts.inJustDecodeBounds) {
                opts.outWidth = 100
                opts.outHeight = 80
                null
            } else {
                bitmap
            }
        }
        for (sdk in listOf(26, 28)) {
            assertSame(bitmap, loadPhotoBitmap(context, uri, 64, sdkInt = sdk))
        }
        verify(exactly = 0) { resolver.loadThumbnail(any(), any(), any()) }
    }

    @Test
    fun `打不开流时不调用解码`() {
        val resolver = mockk<ContentResolver>()
        val context = mockk<Context>()
        val uri = mockk<Uri>()
        every { context.contentResolver } returns resolver
        every { resolver.openInputStream(uri) } returns null
        assertNull(loadPhotoBitmap(context, uri, 64, sdkInt = 26))
        verify(exactly = 0) { BitmapFactory.decodeStream(any(), isNull(), any()) }
    }

    @Test
    fun `尺寸无效时不再读像素`() {
        val resolver = mockk<ContentResolver>()
        val context = mockk<Context>()
        val uri = mockk<Uri>()
        var opens = 0
        every { context.contentResolver } returns resolver
        every { resolver.openInputStream(uri) } answers {
            opens++
            ByteArrayInputStream(byteArrayOf(1))
        }
        every { BitmapFactory.decodeStream(any(), isNull(), any()) } answers {
            val opts = thirdArg<BitmapFactory.Options>()
            opts.outWidth = 0
            opts.outHeight = 0
            null
        }
        assertNull(loadPhotoBitmap(context, uri, 64, sdkInt = 28))
        assertEquals(1, opens)
    }
}
