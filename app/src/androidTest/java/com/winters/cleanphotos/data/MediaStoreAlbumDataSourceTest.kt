package com.winters.cleanphotos.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.winters.cleanphotos.data.datasource.MediaStoreAlbumDataSource
import com.winters.cleanphotos.domain.model.PhotoItem
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 装袋移动文件（真机 / 模拟器上跑，按系统版本走三条路）：_ID 不变、文件真的换了文件夹、
 * 目标里有同名文件时两份都在、撤回能移回原文件夹。
 *
 * Android 8–10 用 java.io 写文件再让媒体扫描入库（相当于别的 App 拍的照片）；11+ 由本 App 插入
 * （自己的文件不需要 createWriteRequest，别人照片的授权弹窗在 UI 验证里覆盖）。
 */
@RunWith(AndroidJUnit4::class)
class MediaStoreAlbumDataSourceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver
    private val dataSource = MediaStoreAlbumDataSource(context)
    private val created = mutableListOf<Uri>()
    private val stamp = System.currentTimeMillis()
    private val src = "Pictures/CsTestSrc$stamp/"
    private val dst = "Pictures/CsTestDst$stamp/"

    @Before
    fun grantLegacyWrite() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
            for (p in listOf("android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE")) {
                ui.executeShellCommand("pm grant ${context.packageName} $p").close()
            }
            Thread.sleep(500)
        }
    }

    @After
    fun cleanUp() {
        for (uri in created) {
            runCatching { pathOf(uri)?.let { File(it).delete() } }
            runCatching { resolver.delete(uri, null, null) }
        }
        val root = Environment.getExternalStorageDirectory()
        File(root, src).deleteRecursively()
        File(root, dst).deleteRecursively()
    }

    @Test
    fun move_keepsIdAndMovesFile_thenUndoMovesBack() {
        val bytes = jpeg(1)
        val photo = insert(src, "cs_a.jpg", bytes)

        assertTrue(dataSource.move(photo, dst))
        assertEquals(dst, folderOf(photo.uri))
        val moved = pathOf(photo.uri)!!
        assertTrue("新位置有文件：$moved", File(moved).exists())
        assertFalse(File(Environment.getExternalStorageDirectory(), src + "cs_a.jpg").exists())
        assertArrayEquals(bytes, read(photo.uri))

        // 撤回：同一行（同一个 _ID）移回原文件夹
        assertTrue(dataSource.move(photo.copy(relativePath = dst), src))
        assertEquals(src, folderOf(photo.uri))
        assertArrayEquals(bytes, read(photo.uri))
    }

    @Test
    fun move_sameNameInTarget_keepsBothFiles() {
        val mine = jpeg(2)
        val theirs = jpeg(3)
        val existing = insert(dst, "cs_dup.jpg", theirs)
        val photo = insert(src, "cs_dup.jpg", mine)

        val ok = dataSource.move(photo, dst)
        Log.i(TAG, "API ${Build.VERSION.SDK_INT} 同名移动 ok=$ok → ${pathOf(photo.uri)}；原有 ${pathOf(existing.uri)}")

        // 不管系统是改名放进去还是拒绝，两张照片的内容都必须还在、各是各的
        assertArrayEquals("目标里原来那张没被覆盖", theirs, read(existing.uri))
        assertArrayEquals("移动的这张内容不变", mine, read(photo.uri))
        assertNotEquals(pathOf(existing.uri), pathOf(photo.uri))
        if (ok) {
            assertEquals(dst, folderOf(photo.uri))
            // 撤回：改过名的那张也能回去
            assertTrue(dataSource.move(photo.copy(relativePath = dst), src))
            assertEquals(src, folderOf(photo.uri))
            assertArrayEquals(mine, read(photo.uri))
        } else {
            assertEquals(src, folderOf(photo.uri))
        }
        assertArrayEquals(theirs, read(existing.uri))
    }

    @Test
    fun move_alreadyInTarget_isNoOp() {
        val photo = insert(dst, "cs_here.jpg", jpeg(4))
        assertTrue(dataSource.move(photo, dst))
        assertEquals(dst, folderOf(photo.uri))
    }

    private companion object {
        const val TAG = "AlbumMoveTest"
    }

    // —— 工具 ——

    private fun insert(folder: String, name: String, bytes: ByteArray): PhotoItem {
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            }
            val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
            uri
        } else {
            val dir = File(Environment.getExternalStorageDirectory(), folder).apply { mkdirs() }
            val file = File(dir, name)
            file.writeBytes(bytes)
            scan(file)
        }
        created += uri
        // 插入时同名会被系统改名：以实际入库的名字为准
        assertEquals(folder, folderOf(uri))
        return PhotoItem(
            id = ContentUris.parseId(uri),
            uri = uri,
            displayName = name,
            dateTaken = null,
            dateGroup = "",
            size = bytes.size.toLong(),
            width = null,
            height = null,
            relativePath = folder,
        )
    }

    private fun scan(file: File): Uri {
        val latch = CountDownLatch(1)
        var result: Uri? = null
        MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf("image/jpeg")) { _, uri ->
            result = uri
            latch.countDown()
        }
        assertTrue("媒体扫描超时", latch.await(15, TimeUnit.SECONDS))
        val uri = requireNotNull(result) { "媒体扫描没有入库：${file.path}" }
        // 扫描返回的是 files 表的 uri，换成 images 表
        return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentUris.parseId(uri))
    }

    @Suppress("DEPRECATION")
    private fun pathOf(uri: Uri): String? =
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    private fun folderOf(uri: Uri): String? {
        val path = pathOf(uri) ?: return null
        val root = Environment.getExternalStorageDirectory().path.trimEnd('/') + "/"
        assertTrue("$path 不在外部存储根下", path.startsWith(root))
        return path.removePrefix(root).substringBeforeLast('/') + "/"
    }

    private fun read(uri: Uri): ByteArray = resolver.openInputStream(uri)!!.use { it.readBytes() }

    /** 内容各不相同的最小 JPEG（SOI + 注释段 + EOI），够媒体库认作图片。 */
    private fun jpeg(seed: Int): ByteArray {
        val comment = "cleanphotos-test-$seed-$stamp".toByteArray()
        val len = comment.size + 2
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xFE.toByte(), (len shr 8).toByte(), len.toByte()) +
            comment + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    }
}
