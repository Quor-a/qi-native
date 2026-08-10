package com.qiapp.qi

import android.content.Context
import android.content.Intent

/**
 * 浏览器桥：把「打开某个网址」的请求转交给应用内置浏览器（[WebActivity] 的浏览器模式）。
 *
 * 上游 ZorvAI 用 Channel 把 URL 丢给 Compose 的 ChatScreen 再由它渲染 WebView；
 * 本 fork 是 View/XML 体系，没有常驻收集 Channel 的 Composable，
 * 因此直接以 NEW_TASK 启动 [WebActivity]，语义等价且不依赖任何前台组件存活。
 *
 * 注意：AI 工具链默认走「无界面」路径（search/read/automate/download），
 * 只有 action="open" 才会经由这里拉起可见界面。
 */
object BrowserBridge {

    /** 在应用内置浏览器打开 URL；成功返回 true。 */
    fun open(ctx: Context?, url: String): Boolean {
        if (ctx == null) return false
        val target = normalizeUrl(url) ?: return false
        return try {
            ctx.startActivity(
                Intent(ctx, WebActivity::class.java)
                    .putExtra("url", target)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 归一化用户/模型给出的地址：
     *  - 已带 scheme 的原样返回；
     *  - 形如 "example.com/x" 的补 https://；
     *  - 不像网址的（含空格、无点）返回 null，交由调用方当搜索词处理。
     */
    fun normalizeUrl(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if (s.startsWith("http://", true) || s.startsWith("https://", true)) return s
        if (s.startsWith("file://", true) || s.startsWith("content://", true)) return s
        if (s.contains(' ')) return null
        val host = s.substringBefore('/')
        if (!host.contains('.') || host.startsWith('.') || host.endsWith('.')) return null
        return "https://$s"
    }

    /** 把一段输入变成可加载地址：像网址就直连，否则走搜索。 */
    fun urlOrSearch(input: String): String {
        normalizeUrl(input)?.let { return it }
        val q = java.net.URLEncoder.encode(input.trim(), "UTF-8")
        return "https://www.baidu.com/s?wd=$q"
    }
}
