package com.qiapp.qi

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONTokener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * **离屏**（headless）网页渲染器。
 *
 * 存在的理由：[AiBrowserTool] 的 OkHttp 抓取拿到的是**服务端原始 HTML**，
 * 现在大量站点（SPA / 前端渲染 / 懒加载正文）的 HTML 里根本没有正文，
 * 抓回来只有一个空壳 <div id="root">。此时用真实 WebView 跑完 JS 再取 innerText，
 * 是唯一能拿到内容的办法。
 *
 * 关键点：WebView **从不 addView 到任何界面**，只在主线程创建、跑完即 destroy，
 * 用户全程看不到任何东西 —— 符合「AI 工具环的一环，不需要界面」的要求。
 *
 * 调用方必须在**后台线程**调用（会阻塞等待）；在主线程调用会直接返回 null 以防死锁。
 */
object HeadlessRenderer {

    /** 页面 onPageFinished 之后再等一会儿，给前端框架渲染/接口回填留时间。 */
    private const val SETTLE_MS = 1500L

    /** 单页渲染总超时。 */
    private const val TIMEOUT_MS = 16_000L

    /**
     * 在离屏 WebView 中加载 [url]、执行 JS、返回正文纯文本。
     * @return 正文文本；失败或超时返回 null。
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun renderText(ctx: Context, url: String): String? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val target = BrowserBridge.normalizeUrl(url) ?: return null

        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        val done = AtomicBoolean(false)
        val result = AtomicReference<String?>(null)
        val holder = AtomicReference<WebView?>(null)

        main.post {
            val wv = try {
                WebView(app)
            } catch (e: Throwable) {
                latch.countDown(); return@post
            }
            holder.set(wv)
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = DownloadUtil.DEFAULT_UA
                // 只要文本，不要图片 —— 省流量也快得多
                loadsImagesAutomatically = false
                blockNetworkImage = true
                mediaPlaybackRequiresUserGesture = true
                cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            }

            fun harvest() {
                if (done.get()) return
                wv.evaluateJavascript(JS_EXTRACT) { raw ->
                    if (done.compareAndSet(false, true)) {
                        result.set(unquote(raw))
                        latch.countDown()
                    }
                }
            }

            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, u: String?) {
                    main.postDelayed({ harvest() }, SETTLE_MS)
                }

                override fun onReceivedError(
                    view: WebView?, request: WebResourceRequest?, error: WebResourceError?
                ) {
                    // 主文档失败才算失败；子资源（图片/统计脚本）失败忽略
                    if (request?.isForMainFrame == true && done.compareAndSet(false, true)) {
                        latch.countDown()
                    }
                }
            }
            wv.loadUrl(target)
        }

        val ok = try {
            latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); false
        }

        // 超时兜底：抢在销毁前再取一次当前已渲染的内容
        if (!ok && done.compareAndSet(false, true)) {
            val late = CountDownLatch(1)
            main.post {
                val wv = holder.get()
                if (wv == null) { late.countDown(); return@post }
                wv.evaluateJavascript(JS_EXTRACT) { raw ->
                    result.set(unquote(raw)); late.countDown()
                }
            }
            runCatching { late.await(2500, TimeUnit.MILLISECONDS) }
        }

        main.post {
            holder.getAndSet(null)?.let { wv ->
                runCatching {
                    wv.stopLoading()
                    wv.loadUrl("about:blank")
                    wv.webViewClient = WebViewClient()
                    (wv.parent as? android.view.ViewGroup)?.removeView(wv)
                    wv.destroy()
                }
            }
        }

        val text = result.get()?.trim().orEmpty()
        return text.ifBlank { null }
    }

    /**
     * 取正文：优先 article/main，其次去掉 nav/footer/script 后的 body.innerText。
     * innerText 本身已经是「渲染后可见文本」，比手撸标签剥离干净得多。
     */
    private val JS_EXTRACT = """
        (function(){
          try{
            var pick=document.querySelector('article')||document.querySelector('main')||document.body;
            if(!pick) return '';
            var clone=pick.cloneNode(true);
            var junk=clone.querySelectorAll('script,style,noscript,nav,footer,header,aside,iframe');
            for(var i=0;i<junk.length;i++){ junk[i].parentNode && junk[i].parentNode.removeChild(junk[i]); }
            var t=(clone.innerText||clone.textContent||'');
            return (document.title? document.title+'\n\n':'')+t;
          }catch(e){ return ''; }
        })();
    """.trimIndent()

    /** evaluateJavascript 回来的是 JSON 字符串字面量，需要反引号解码。 */
    private fun unquote(raw: String?): String? {
        if (raw == null || raw == "null") return null
        return runCatching {
            val v = JSONTokener(raw).nextValue()
            (v as? String) ?: raw
        }.getOrElse { raw }
            ?.replace(Regex("[ \\t]+"), " ")
            ?.replace(Regex(" *\\n *"), "\n")
            ?.replace(Regex("\\n{3,}"), "\n\n")
            ?.trim()
    }
}
