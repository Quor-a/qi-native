package com.qiapp.qi

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.qiapp.qi.databinding.ActivityFilePreviewBinding
import java.io.File

/**
 * 自写「文件 / 图片 / 文档」预览架构：对话框里点开 AI 发送的文件气泡后，
 * 在本应用内直接预览，而不是甩给外部查看器（FileProvider 仅作为「用其他应用打开」兜底）。
 *
 * 预览策略（按类型分流，四选一）：
 *  - 位图（png/jpg/webp/gif）→ ImageView 直接显示；
 *  - SVG / HTML → WebView 渲染（矢量图与网页原生可显示）；
 *  - 文本 / 代码 / Markdown / JSON / CSV 等 → 等宽 TextView 滚动展示；
 *  - PDF → 本应用无内置 PDF 渲染（不引第三方库），提示并用其他应用打开。
 */
class FilePreviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_MIME = "mime"
        const val EXTRA_NAME = "name"
        fun createIntent(ctx: android.content.Context, path: String, mime: String, name: String): Intent =
            Intent(ctx, FilePreviewActivity::class.java)
                .putExtra(EXTRA_PATH, path)
                .putExtra(EXTRA_MIME, mime)
                .putExtra(EXTRA_NAME, name)
    }

    private lateinit var b: ActivityFilePreviewBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityFilePreviewBinding.inflate(layoutInflater)
        setContentView(b.root)

        val path = intent.getStringExtra(EXTRA_PATH) ?: ""
        val mime = intent.getStringExtra(EXTRA_MIME) ?: "*/*"
        val name = intent.getStringExtra(EXTRA_NAME) ?: "文件"
        val file = File(path)

        b.titleText.text = name
        b.backBtn.setOnClickListener { finish() }
        b.openBtn.setOnClickListener { openExternal(file, mime, name) }

        if (!file.exists()) {
            showNote("文件已不存在：$name")
            return
        }

        when {
            isRasterImage(mime, name) -> showImage(file)
            mime == "application/pdf" || name.endsWith(".pdf", true) ->
                showNote("本应用暂不支持直接预览 PDF，请点右上「其他应用」打开。")
            mime.startsWith("image/svg+xml") || mime.startsWith("text/html") ||
                name.endsWith(".svg", true) || name.endsWith(".html", true) || name.endsWith(".htm", true) ->
                showWeb(file)
            else -> showText(file)
        }
    }

    private fun isRasterImage(mime: String, name: String): Boolean =
        (mime.startsWith("image/") && !mime.endsWith("svg+xml")) ||
            name.endsWith(".png", true) || name.endsWith(".jpg", true) ||
            name.endsWith(".jpeg", true) || name.endsWith(".webp", true) || name.endsWith(".gif", true)

    private fun showImage(file: File) {
        val bmp = try { BitmapFactory.decodeFile(file.absolutePath) } catch (_: Exception) { null }
        if (bmp == null) { showNote("图片解码失败：${file.name}"); return }
        b.previewImage.setImageBitmap(bmp)
        showOnly(b.previewImage)
    }

    private fun showWeb(file: File) {
        b.previewWeb.settings.apply {
            allowFileAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
        }
        b.previewWeb.loadUrl("file://${file.absolutePath}")
        showOnly(b.previewWeb)
    }

    private fun showText(file: File) {
        val text = try { file.readText() } catch (e: Exception) { "无法读取文件：${e.message}" }
        b.previewText.text = text
        b.previewText.movementMethod = ScrollingMovementMethod()
        showOnly(b.previewText)
    }

    private fun showNote(msg: String) {
        b.previewNote.text = msg
        showOnly(b.previewNote)
    }

    /** 仅显示 keep，其余预览视图全部隐藏（避免重叠）。 */
    private fun showOnly(keep: View) {
        listOf(b.previewImage, b.previewWeb, b.previewText, b.previewNote).forEach {
            it.visibility = if (it === keep) View.VISIBLE else View.GONE
        }
    }

    /** 「用其他应用打开」兜底：经 FileProvider 授予临时读权限后调起系统查看器。 */
    private fun openExternal(file: File, mime: String, name: String) {
        val uri = try {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } catch (e: Exception) {
            toast("无法打开：${e.message}"); return
        }
        val type = mime.takeIf { it.isNotBlank() && it != "*/*" } ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, type)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            toast("没有可打开该文件的应用：$name")
        }
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }
}
