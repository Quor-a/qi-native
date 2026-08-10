package com.qiapp.qi

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.qiapp.qi.databinding.ActivityWebBinding
import kotlin.concurrent.thread

/**
 * 双模页面：
 *
 *  1. **文档模式**（intent extra "asset"）：加载 assets/ 里的 HTML（用户协议 / 隐私政策 / 开源许可），
 *     JS 关闭、不联网，保持原有行为不变。
 *  2. **浏览器模式**（intent extra "url"）：真正可用的内置浏览器 —— 地址栏、前进后退、刷新/停止、
 *     加载进度、页面标题、Cookie、DOM Storage、下载落盘、网页表单选文件、外部 scheme 分流、
 *     以及「用系统浏览器打开 / 复制链接 / 分享 / 桌面版」菜单。
 *
 * AI 工具链默认走 [AiBrowserTool] 的无界面通道，只有 action="open" 才会经 [BrowserBridge] 拉起这里。
 */
class WebActivity : AppCompatActivity() {

    private lateinit var b: ActivityWebBinding

    /** 浏览器模式标记；false 时为本地文档阅读。 */
    private var browserMode = false
    private var desktopMode = false
    private var currentUrl = ""

    /** 网页 <input type="file"> 的回调载体。 */
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val cb = filePathCallback ?: return@registerForActivityResult
        filePathCallback = null
        val uris: Array<Uri>? = if (result.resultCode == RESULT_OK) {
            val data = result.data
            val clip = data?.clipData
            when {
                clip != null && clip.itemCount > 0 -> Array(clip.itemCount) { clip.getItemAt(it).uri }
                data?.data != null -> arrayOf(data.data!!)
                else -> null
            }
        } else null
        cb.onReceiveValue(uris)
    }

    private val mobileUa =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    private val desktopUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityWebBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.backBtn.setOnClickListener { finish() }

        val url = intent.getStringExtra("url")?.trim().orEmpty()
        browserMode = url.isNotEmpty()

        if (browserMode) setupBrowser(url) else setupDocument()
    }

    // ---------------- 文档模式（原有行为） ----------------

    private fun setupDocument() {
        val title = intent.getStringExtra("title") ?: "文档"
        val asset = intent.getStringExtra("asset") ?: "agreement.html"
        b.titleText.text = title
        b.webView.settings.apply {
            javaScriptEnabled = false
            defaultTextEncodingName = "utf-8"
            textZoom = 100
        }
        b.webView.loadUrl("file:///android_asset/$asset")
    }

    // ---------------- 浏览器模式 ----------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupBrowser(rawUrl: String) {
        b.browserBar.visibility = View.VISIBLE
        b.moreBtn.visibility = View.VISIBLE
        b.titleText.text = "加载中…"
        b.webView.setPadding(0, 0, 0, 0)

        b.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            defaultTextEncodingName = "utf-8"
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = mobileUa
            // 安全：不给网页读本地文件的能力
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(b.webView, true)

        b.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url ?: return false
                val scheme = u.scheme?.lowercase().orEmpty()
                if (scheme == "http" || scheme == "https") return false
                // tel: / mailto: / intent: / 各类 App 唤起 —— 交给系统，失败就静默忽略
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                } catch (e: Exception) {
                    true
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                currentUrl = url.orEmpty()
                if (!b.urlEdit.hasFocus()) b.urlEdit.setText(currentUrl)
                b.loadProgress.visibility = View.VISIBLE
                syncNavButtons()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                currentUrl = url.orEmpty()
                if (!b.urlEdit.hasFocus()) b.urlEdit.setText(currentUrl)
                b.loadProgress.visibility = View.GONE
                syncNavButtons()
            }
        }

        b.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                b.loadProgress.progress = newProgress
                b.loadProgress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank()) b.titleText.text = title
            }

            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?,
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                val pick = runCatching { params?.createIntent() }.getOrNull()
                if (pick == null) {
                    filePathCallback = null
                    callback?.onReceiveValue(null)
                    return false
                }
                return try {
                    fileChooser.launch(pick)
                    true
                } catch (e: Exception) {
                    filePathCallback = null
                    callback?.onReceiveValue(null)
                    false
                }
            }
        }

        // 下载：交给自研落盘工具，写 Download/Qi，避免「点了没反应」
        b.webView.setDownloadListener { dlUrl, userAgent, contentDisposition, mimetype, _ ->
            toast("开始下载…")
            thread(name = "qi-web-dl") {
                val res = DownloadUtil.download(applicationContext, dlUrl, userAgent, contentDisposition, mimetype)
                runOnUiThread {
                    toast(
                        when {
                            res.startsWith("OK:") -> "已保存到 Download/Qi：${res.substring(3)}"
                            res.startsWith("FALLBACK:") -> "已保存到应用目录"
                            else -> res
                        }
                    )
                }
            }
        }

        b.navBack.setOnClickListener { if (b.webView.canGoBack()) b.webView.goBack() }
        b.navForward.setOnClickListener { if (b.webView.canGoForward()) b.webView.goForward() }
        b.navReload.setOnClickListener {
            if (b.loadProgress.visibility == View.VISIBLE) b.webView.stopLoading() else b.webView.reload()
        }
        b.moreBtn.setOnClickListener { showMenu() }

        b.urlEdit.setOnEditorActionListener { _, actionId, event ->
            val go = actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (go) {
                val input = b.urlEdit.text?.toString().orEmpty()
                if (input.isNotBlank()) {
                    hideKeyboard()
                    b.urlEdit.clearFocus()
                    b.webView.loadUrl(BrowserBridge.urlOrSearch(input))
                }
                true
            } else false
        }

        syncNavButtons()
        val target = BrowserBridge.urlOrSearch(rawUrl)
        b.urlEdit.setText(target)
        b.webView.loadUrl(target)
    }

    private fun syncNavButtons() {
        b.navBack.isEnabled = b.webView.canGoBack()
        b.navForward.isEnabled = b.webView.canGoForward()
        b.navBack.alpha = if (b.navBack.isEnabled) 1f else 0.35f
        b.navForward.alpha = if (b.navForward.isEnabled) 1f else 0.35f
    }

    private fun showMenu() {
        val pm = PopupMenu(this, b.moreBtn)
        pm.menu.add(0, 1, 0, "用系统浏览器打开")
        pm.menu.add(0, 2, 1, "复制链接")
        pm.menu.add(0, 3, 2, "分享")
        pm.menu.add(0, 4, 3, if (desktopMode) "切回手机版" else "请求桌面版")
        pm.menu.add(0, 5, 4, "清除本站 Cookie")
        pm.setOnMenuItemClickListener { item ->
            val url = currentUrl.ifBlank { b.urlEdit.text?.toString().orEmpty() }
            when (item.itemId) {
                1 -> runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { toast("没有可用的系统浏览器") }
                2 -> {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("url", url))
                    toast("已复制链接")
                }
                3 -> runCatching {
                    startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url),
                            "分享链接"
                        )
                    )
                }.onFailure { toast("分享失败") }
                4 -> {
                    desktopMode = !desktopMode
                    b.webView.settings.userAgentString = if (desktopMode) desktopUa else mobileUa
                    b.webView.settings.useWideViewPort = true
                    b.webView.reload()
                    toast(if (desktopMode) "已切换到桌面版" else "已切回手机版")
                }
                5 -> {
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                    toast("已清除 Cookie")
                }
            }
            true
        }
        pm.show()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(b.urlEdit.windowToken, 0)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /** 浏览器模式下返回键优先在网页历史里回退。 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && browserMode && b.webView.canGoBack()) {
            b.webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        if (browserMode) b.webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (browserMode) b.webView.onResume()
    }

    override fun onDestroy() {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        runCatching {
            b.webView.stopLoading()
            (b.webView.parent as? android.view.ViewGroup)?.removeView(b.webView)
            b.webView.destroy()
        }
        super.onDestroy()
    }
}
