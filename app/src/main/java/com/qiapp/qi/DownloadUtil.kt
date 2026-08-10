package com.qiapp.qi

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 统一文件落盘工具。
 *
 * 与上游 QuroDownloadUtil 的差异（本 fork 的升级点）：
 *  1. **流式落盘**：上游 `readBytes()` 把整个响应读进内存，几百 MB 的文件必 OOM；
 *     这里边读边写，内存占用恒定 8KB。
 *  2. **去品牌化**：公共目录从 Download/Quro 改为 Download/Qi。
 *  3. **重名不覆盖**：同名文件自动追加 (1)(2) 后缀。
 *
 * 返回约定：成功 "OK:<名字>" 或 "FALLBACK:<绝对路径>"；失败返回中文错误信息。
 */
object DownloadUtil {

    private const val PUBLIC_SUBDIR = "Qi"

    /** 从 URL 或 Content-Disposition 推断文件名；无法推断返回 null。 */
    fun deriveFileName(url: String, contentDisposition: String?): String? {
        if (!contentDisposition.isNullOrBlank()) {
            val m = Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
                .find(contentDisposition)?.groupValues?.getOrNull(1)?.trim()
            if (!m.isNullOrBlank()) return sanitize(m)
        }
        val path = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        return if (path.isNotBlank() && path.contains('.')) sanitize(path) else null
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120).ifBlank { "download" }

    /**
     * 下载并落盘（**阻塞**，务必在后台线程调用）。
     */
    fun download(
        ctx: Context,
        dlUrl: String,
        userAgent: String? = null,
        contentDisposition: String? = null,
        mimeType: String? = null,
    ): String {
        val target = BrowserBridge.normalizeUrl(dlUrl) ?: return "下载失败：地址不合法"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(target).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent ?: DEFAULT_UA)
                connectTimeout = 20000
                readTimeout = 30000
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            if (code !in 200..299) return "下载失败：HTTP $code"

            // 服务端返回的头优先于调用方猜测
            val cd = conn.getHeaderField("Content-Disposition") ?: contentDisposition
            val name = deriveFileName(target, cd) ?: "qi_${System.currentTimeMillis()}"
            val mime = mimeType?.takeIf { it.isNotBlank() }
                ?: conn.contentType?.substringBefore(';')?.trim()?.takeIf { it.isNotBlank() }
                ?: "application/octet-stream"

            conn.inputStream.use { input ->
                // 先拿到公共目录写入句柄；拿不到才回退私有目录，
                // 避免「公共写到一半失败再回退」造成半截文件。
                val uri = createPublicUri(ctx, name, mime)
                if (uri == null) {
                    "FALLBACK:${saveFallback(ctx, input, name)}"
                } else {
                    val ok = runCatching {
                        ctx.contentResolver.openOutputStream(uri)?.use { out -> pump(input, out) } != null
                    }.getOrDefault(false)
                    if (ok) "OK:$name" else "下载失败：写入 Download/$PUBLIC_SUBDIR 失败"
                }
            }
        } catch (e: Exception) {
            "下载失败：${e.message ?: e.javaClass.simpleName}"
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** 申请公共 Download/Qi 目录的写入句柄；拿不到返回 null 让调用方回退私有目录。 */
    private fun createPublicUri(ctx: Context, name: String, mime: String): Uri? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + PUBLIC_SUBDIR)
            }
            ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), PUBLIC_SUBDIR)
            if (!dir.exists()) dir.mkdirs()
            Uri.fromFile(uniqueFile(dir, name))
        }
    } catch (e: Exception) {
        null
    }

    /** 回退到应用私有 Download 目录（永远有写权限）。 */
    private fun saveFallback(ctx: Context, input: InputStream, name: String): String {
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
        if (!dir.exists()) dir.mkdirs()
        val out = uniqueFile(dir, name)
        out.outputStream().use { os -> pump(input, os) }
        return out.absolutePath
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (f.exists() && i < 999) {
            f = File(dir, if (ext.isEmpty()) "$base($i)" else "$base($i).$ext")
            i++
        }
        return f
    }

    private fun pump(input: InputStream, out: OutputStream) {
        val buf = ByteArray(8 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        out.flush()
    }

    /** 把一段文本写入公共 Download/Qi 目录。成功返回 "OK:<名字>"。 */
    fun saveTextToDownloads(ctx: Context, fileName: String, mime: String, text: String): String = try {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + PUBLIC_SUBDIR)
            }
            ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), PUBLIC_SUBDIR)
            if (!dir.exists()) dir.mkdirs()
            Uri.fromFile(uniqueFile(dir, fileName))
        }
        if (uri == null) "保存失败：无法获取写入 URI"
        else {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            "OK:$fileName"
        }
    } catch (e: Exception) {
        "保存失败：${e.message}"
    }

    const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
}
