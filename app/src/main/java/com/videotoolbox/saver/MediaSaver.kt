package com.videotoolbox.saver

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import okhttp3.Request
import java.io.File

/** 下载并保存到系统媒体库（API 29+ 无需存储权限） */
object MediaSaver {

    private val client = Http.client

    /** 下载到应用缓存目录，onProgress 回调 0-100 */
    fun downloadToCache(cacheDir: File, url: String, fileName: String, onProgress: (Int) -> Unit): File {
        val req = Request.Builder().url(url)
            .header("User-Agent", Http.MOBILE_UA)
            .header("Referer", "https://www.douyin.com/")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("下载失败 HTTP ${resp.code}")
            val body = resp.body ?: throw Exception("下载内容为空")
            val total = body.contentLength()
            val dir = File(cacheDir, "download").apply { mkdirs() }
            val out = File(dir, fileName)
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var done = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        done += read
                        if (total > 0) onProgress((done * 100 / total).toInt())
                    }
                }
            }
            return out
        }
    }

    /** 保存视频到 相册/Movies/VideoSaver，返回显示名 */
    fun saveVideo(context: Context, file: File, displayName: String): String {
        val name = if (displayName.endsWith(".mp4")) displayName else "$displayName.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VideoSaver")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri: Uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw Exception("创建相册条目失败")
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return name
    }

    /** 保存图集图片到 相册/Pictures/VideoSaver，返回保存数量 */
    fun saveImages(context: Context, files: List<File>): Int {
        val resolver = context.contentResolver
        var count = 0
        files.forEachIndexed { index, f ->
            val name = "gallery_${System.currentTimeMillis()}_$index.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/VideoSaver")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@forEachIndexed
            resolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            count++
        }
        return count
    }
}
