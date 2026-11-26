package com.prts.client.call

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream

/**
 * 相册观察（observe.album）：只取「最近一张」，不列、不搜、不导出别人的相册。
 *
 * 两条边界：
 * 1. 读相册要 READ_MEDIA_IMAGES（Android 13+），没授权就返回 null，绝不弹窗骚扰；
 * 2. 只留缩略图（最长边 640、JPEG 质量 70，正常 30–90KB），原图不出手机。
 * 上传与否由调用方（通话页 AlbumWatcher）决定，服务端还要再过一次 `observe.album` 门。
 */
object AlbumFeed {
    private const val TAG = "PRTS-Album"
    private const val MAX_EDGE = 640
    private const val QUALITY = 70

    data class Latest(val id: Long, val at: Long, val name: String)

    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun hasAccess(ctx: Context): Boolean = permissions().any {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

    /** 最近一张照片的 id / 拍摄时间 / 文件名；没权限或空相册返回 null。 */
    fun latest(ctx: Context): Latest? {
        if (!hasAccess(ctx)) return null
        val columns = arrayOf(MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DISPLAY_NAME)
        return runCatching {
            ctx.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, columns,
                null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { cursor ->
                if (!cursor.moveToFirst()) null
                else Latest(id = cursor.getLong(0),
                    at = cursor.getLong(1) * 1000L,
                    name = (cursor.getString(2) ?: "").take(60))
            }
        }.onFailure { Log.i(TAG, "latest failed ${it.javaClass.simpleName}") }.getOrNull()
    }

    fun uriOf(id: Long): Uri =
        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

    /** 缩略 JPEG；失败返回 null（不抛）。 */
    fun thumbnail(ctx: Context, id: Long): ByteArray? {
        val uri = uriOf(id)
        val bitmap = runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                ctx.contentResolver.loadThumbnail(uri, Size(MAX_EDGE, MAX_EDGE), null)
            } else {
                ctx.contentResolver.openInputStream(uri)?.use { stream ->
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(stream, null, bounds)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE) sample *= 2
                    ctx.contentResolver.openInputStream(uri)?.use { again ->
                        BitmapFactory.decodeStream(again, null,
                            BitmapFactory.Options().apply { inSampleSize = sample })
                    }
                }
            }
        }.onFailure { Log.i(TAG, "thumbnail failed ${it.javaClass.simpleName}") }.getOrNull()
            ?: return null
        val scaled = scaleDown(bitmap)
        return try {
            ByteArrayOutputStream().use { out ->
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) null else out.toByteArray()
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
        }
    }

    private fun scaleDown(source: Bitmap): Bitmap {
        val edge = maxOf(source.width, source.height)
        if (edge <= MAX_EDGE) return source
        val ratio = MAX_EDGE.toFloat() / edge
        return Bitmap.createScaledBitmap(source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1), true)
    }
}
