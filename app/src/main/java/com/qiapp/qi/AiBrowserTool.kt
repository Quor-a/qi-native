package com.qiapp.qi

import android.content.Context
import android.os.Environment
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * AI 自动化浏览器（**无界面**，纯后台，作为工具环的一环被模型直接调用）。
 *
 * 设计原则：模型调用它时**不弹出任何 Activity**，全部在 `qi-llm` 后台线程里跑完，
 * 把结果以文本回灌给模型继续推理。只有显式 action="open" 才会拉起可见的内置浏览器。
 *
 * 相对上游 ZorvAI 版的升级（本 fork 新增）：
 *  1. **字符集嗅探**：按 Content-Type / <meta charset> 解码，修掉 GBK 中文站点乱码
 *     （上游一律按 UTF-8 读，搜狗/百度/老站点正文全是「锟斤拷」）。
 *  2. **并行抓取**：automate 从「顺序抓 N 页」改为线程池并行，同样 20s 预算内
 *     能抓到的资料数量成倍提升。
 *  3. **跳板页跟随**：百度/搜狗结果是 link?url= 重定向，会解析 meta refresh 与
 *     location.replace 拿到真实地址（上游抓到的是一张空跳板页）。
 *  4. **响应体封顶**：peekBody(1.5MB)，避免抓到大文件把 App 撑爆。
 *  5. **新增 action**：links（抽取页面链接，支持多跳研究）、fetch（直接取 JSON/纯文本 API）。
 *  6. **引擎顺序面向国内网络**：Bing → 百度 → 搜狗 → 360 → DuckDuckGo，并统一 h3 锚点解析器
 *     + 通用兜底，任一引擎可用即返回。
 *  7. **同域去重**：automate 优先抓不同站点，避免 4 条来源全是同一个站。
 *  8. **JS 渲染兜底**（最大缺口补齐）：上游只有 OkHttp 直连，遇到 SPA / 前端渲染站点
 *     抓回来是空壳，正文全丢。这里接入 [HeadlessRenderer]（离屏 WebView，不挂界面），
 *     read 检测到空壳自动降级渲染，也可用 action="render" 强制渲染。
 */
object AiBrowserTool {

    const val NAME = "ai_browser"

    private val UA = DownloadUtil.DEFAULT_UA

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** 单页抓取上限，防止把大文件读进内存。 */
    private const val MAX_BODY = 1_500_000L

    /** automate 总耗时预算（毫秒）。到点即停，已抓到的照常合并返回。 */
    private const val RESEARCH_BUDGET_MS = 20_000L

    /** 正文短于这个长度就认定是「JS 空壳页」，转交离屏渲染。 */
    private const val JS_SHELL_THRESHOLD = 240

    // ---------------- 工具声明（OpenAI function calling） ----------------

    fun spec(): JSONObject {
        val props = JSONObject()
            .put("action", JSONObject().put("type", "string").put(
                "description",
                "研究/查资料一律用 automate（一次调用完成搜索+抓取+合并，不要拆成 search 再 read）；" +
                    "search=只要标题+链接；read=抓单页正文（遇到 JS 空壳页会自动降级渲染）；" +
                    "render=强制用离屏 WebView 跑完 JS 再取正文（仍然无界面，适合 SPA/前端渲染站点）；" +
                    "links=抽取页面里的链接；fetch=直接取 JSON/纯文本接口；" +
                    "download=下载文件到 Download/Qi；open=打开可见的内置浏览器"
            ))
            .put("query", JSONObject().put("type", "string").put("description", "search / automate 的搜索词或研究主题"))
            .put("url", JSONObject().put("type", "string").put("description", "read / links / fetch / download / open 的目标网址"))
            .put("limit", JSONObject().put("type", "integer").put("description", "search / links 返回条数，默认 5"))
            .put("depth", JSONObject().put("type", "integer").put("description", "automate 抓取前 N 个结果的正文，默认 4，上限 8"))
            .put("contentDisposition", JSONObject().put("type", "string").put("description", "download 可选：Content-Disposition 头，用于推断文件名"))
            .put("mime", JSONObject().put("type", "string").put("description", "download 可选：MIME 类型"))

        val params = JSONObject()
            .put("type", "object")
            .put("properties", props)
            .put("required", JSONArray().put("action"))

        val desc = "AI 自动化浏览器：后台联网搜索 / 抓取网页正文 / JS 渲染 / 抽取链接 / 调用 JSON 接口 / 下载文件，全程无界面。" +
            "【关键用法】任何「查一下」「搜一下」「帮我研究」类需求，只调用一次 action=\"automate\" 即可，" +
            "它会在单次调用内完成「搜索 → 并行抓取前 depth 个结果正文 → 合并成带出处的简报」；" +
            "严禁拆成先 search 再逐条 read，那样会产生大量重复调用并拖慢对话。" +
            "需要看到页面本身时才用 open（会打开界面）。"

        return JSONObject().put("type", "function").put(
            "function",
            JSONObject().put("name", NAME).put("description", desc).put("parameters", params)
        )
    }

    // ---------------- 执行入口 ----------------

    /** 由 [ToolEngine.dispatch] 调用；**阻塞**，运行在 qi-llm 后台线程。 */
    fun run(ctx: Context?, args: JSONObject): String {
        val action = args.optString("action", "").trim().lowercase()
        return when (action) {
            "search" -> {
                val q = args.optString("query", "").trim()
                if (q.isEmpty()) "search 缺少 query 参数"
                else webSearch(ctx, q, intArg(args, "limit", 5, 1, 20))
            }
            "read" -> {
                val url = args.optString("url", "").trim()
                if (url.isEmpty()) "read 缺少 url 参数" else readPage(ctx, url)
            }
            "render" -> {
                val url = args.optString("url", "").trim()
                when {
                    url.isEmpty() -> "render 缺少 url 参数"
                    ctx == null -> "无应用上下文，无法渲染"
                    else -> renderPage(ctx, url)
                }
            }
            "links" -> {
                val url = args.optString("url", "").trim()
                if (url.isEmpty()) "links 缺少 url 参数"
                else extractLinks(url, intArg(args, "limit", 20, 1, 60))
            }
            "fetch" -> {
                val url = args.optString("url", "").trim()
                if (url.isEmpty()) "fetch 缺少 url 参数" else fetchRaw(url)
            }
            "automate" -> {
                val q = args.optString("query", "").trim()
                if (q.isEmpty()) "automate 缺少 query 参数"
                else automateResearch(ctx, q, intArg(args, "depth", 4, 1, 8))
            }
            "download" -> {
                val url = args.optString("url", "").trim()
                when {
                    url.isEmpty() -> "download 缺少 url 参数"
                    ctx == null -> "无应用上下文，无法下载"
                    else -> {
                        val cd = args.optString("contentDisposition", "").takeIf { it.isNotBlank() }
                        val mime = args.optString("mime", "").takeIf { it.isNotBlank() }
                        val res = DownloadUtil.download(ctx, url, UA, cd, mime)
                        when {
                            res.startsWith("OK:") -> "已下载并保存到 Download/Qi：${res.substring(3)}"
                            res.startsWith("FALLBACK:") -> "已保存到应用目录：${res.substring(9)}"
                            else -> res
                        }
                    }
                }
            }
            "open" -> {
                val url = args.optString("url", "").trim()
                when {
                    url.isEmpty() -> "open 缺少 url 参数"
                    ctx == null -> "无应用上下文，无法打开浏览器"
                    BrowserBridge.open(ctx, BrowserBridge.urlOrSearch(url)) -> "已在应用内置浏览器打开：$url"
                    else -> "打开内置浏览器失败：$url"
                }
            }
            else -> "未知 action：$action（支持 automate / search / read / render / links / fetch / download / open）"
        }
    }

    /** 模型有时把数字当字符串传，这里两种都吃。 */
    private fun intArg(o: JSONObject, key: String, def: Int, min: Int, max: Int): Int {
        val v = when {
            o.has(key) && o.opt(key) is Number -> o.optInt(key, def)
            else -> o.optString(key, "").trim().toIntOrNull() ?: def
        }
        return v.coerceIn(min, max)
    }

    // ---------------- 搜索 ----------------

    private fun webSearch(ctx: Context?, query: String, limit: Int): String =
        when (val out = parseResults(query)) {
            is SearchOutcome.Results -> {
                val results = out.list
                if (results.isEmpty()) "未从搜索引擎解析到结果（可能触发人机验证，请换个关键词稍后再试）。"
                else buildString {
                    append("联网搜索「$query」命中 ${results.size} 条（展示前 ${minOf(limit, results.size)} 条）：\n")
                    results.take(limit).forEachIndexed { i, (t, u) -> append("${i + 1}. $t\n   $u\n") }
                }
            }
            is SearchOutcome.Failed -> {
                writeBrowserDiag(ctx, query, out.engines)
                val reason = if (out.anyConnected)
                    "搜索引擎有响应但解析不到结果（极可能被反爬拦截/页面改版/需 JS 渲染）"
                else
                    "设备无法直连任何搜索引擎——很可能本机没有外网（例如聊天用的是本机/局域网模型，但浏览器要去连外网却被拦截或没网关）"
                val hint = if (out.anyConnected)
                    "可改用 action=\"open\" 让 AI 直接在内置浏览器里打开搜索页查看。"
                else
                    "请确认这台设备能直接访问外网（浏览器工具只能走设备自身的网络，无法借聊天端点）。"
                "联网搜索失败：$reason。$hint\n（各引擎诊断已写入 Download/栖_logs/browser_*.txt，可用手机文件管理器查看，无需 adb）"
            }
        }

    private sealed class SearchOutcome {
        data class Results(val list: List<Pair<String, String>>) : SearchOutcome()
        /** 全部失败：engines 为各引擎诊断（连通/反爬/例外），anyConnected 区分「没外网」与「被反爬」。 */
        data class Failed(val engines: List<String>, val anyConnected: Boolean) : SearchOutcome()
    }

    /**
     * 多引擎回退搜索。面向国内网络重排了顺序，任一引擎出结果即返回。
     * 专属解析为空时用通用链接抽取兜底，抗页面改版。
     * 每个引擎的成败都会记录进 [SearchOutcome.Failed.engines]，供自诊断落盘。
     */
    private fun parseResults(query: String): SearchOutcome {
        val enc = URLEncoder.encode(query, "UTF-8")
        val engines = listOf<Pair<String, (String) -> List<Pair<String, String>>>>(
            "https://www.bing.com/search?q=$enc" to ::parseBing,
            "https://www.baidu.com/s?wd=$enc" to ::parseH3Anchor,
            "https://www.sogou.com/web?query=$enc" to ::parseH3Anchor,
            "https://www.so.com/s?q=$enc" to ::parseH3Anchor,
            "https://lite.duckduckgo.com/lite/?q=$enc" to ::parseDdgLite,
        )
        val log = mutableListOf<String>()
        var anyConnected = false
        val start = System.currentTimeMillis()
        for ((url, parser) in engines) {
            val name = engineName(url)
            if (System.currentTimeMillis() - start > 18_000) { log.add("$name ✗ 超时跳过"); continue }
            val page = try { fetch(url) } catch (e: Exception) {
                log.add("$name ✗ 例外：${e.message}"); null
            }
            if (page == null) { log.add("$name ✗ 无响应(连接被拒/超时/DNS 失败)"); continue }
            anyConnected = true
            val specific = runCatching { parser(page.body) }.getOrNull().orEmpty()
            if (specific.isNotEmpty()) return SearchOutcome.Results(specific)
            val generic = runCatching { parseGeneric(page.body) }.getOrNull().orEmpty()
            if (generic.isNotEmpty()) return SearchOutcome.Results(generic)
            log.add("$name ✓ 连通但解析不到结果（可能反爬/页面改版/需 JS）")
        }
        return SearchOutcome.Failed(log, anyConnected)
    }

    private fun engineName(url: String): String = when {
        url.contains("bing.com") -> "Bing"
        url.contains("baidu.com") -> "百度"
        url.contains("sogou.com") -> "搜狗"
        url.contains("so.com") -> "360"
        url.contains("duckduckgo.com") -> "DuckDuckGo"
        else -> url
    }

    /** Bing：<li class="b_algo"><h2><a href="URL">TITLE</a> */
    private fun parseBing(html: String): List<Pair<String, String>> {
        val re = """<li[^>]*class="b_algo"[^>]*>.*?<h2>\s*<a[^>]*href="([^"]+)"[^>]*>(.*?)</a>"""
            .toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        return re.findAll(html).mapNotNull { m ->
            val url = m.groupValues[1]
            val title = stripTags(m.groupValues[2]).take(120)
            if (url.startsWith("http") && title.isNotBlank()) title to url else null
        }.toList()
    }

    /** 百度 / 搜狗 / 360 / 移动百度：统一的 <h3 ...><a href="URL">TITLE</a> 结构。 */
    private fun parseH3Anchor(html: String): List<Pair<String, String>> {
        val re = """<h3[^>]*>\s*<a[^>]*href="([^"]+)"[^>]*>(.*?)</a>"""
            .toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val seen = mutableSetOf<String>()
        return re.findAll(html).mapNotNull { m ->
            val url = m.groupValues[1]
            val title = stripTags(m.groupValues[2]).take(120)
            if (url.startsWith("http") && title.isNotBlank() && seen.add(url)) title to url else null
        }.toList()
    }

    /** DuckDuckGo Lite：result-link / result__a，href 里是 uddg= 编码过的真实地址。 */
    private fun parseDdgLite(html: String): List<Pair<String, String>> {
        val re = """<a[^>]*class="(?:result-link|result__a)"[^>]*href="([^"]+)"[^>]*>(.*?)</a>"""
            .toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        return re.findAll(html).mapNotNull { m ->
            val real = resolveDdgUrl(m.groupValues[1])
            val title = stripTags(m.groupValues[2]).take(120)
            if (real != null && title.isNotBlank()) title to real else null
        }.toList()
    }

    /** 通用兜底：抽所有外链 + 合理长度标题，过滤导航/引擎自身链接。 */
    private fun parseGeneric(html: String): List<Pair<String, String>> {
        val re = """<a\s+[^>]*href="(https?://[^"]+)"[^>]*>(.*?)</a>"""
            .toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val seen = mutableSetOf<String>()
        val out = mutableListOf<Pair<String, String>>()
        re.findAll(html).forEach { m ->
            var url = m.groupValues[1]
            val title = stripTags(m.groupValues[2]).take(120)
            if (title.length < 12 || title.length > 110) return@forEach
            if (url.contains("/search?") || url.contains("/preferences") || url.contains("/account") ||
                url.contains("javascript:") || url.contains("mailto:")
            ) return@forEach
            if (url.contains("uddg=")) url = resolveDdgUrl(url) ?: return@forEach
            if (url.startsWith("http") && seen.add(url)) out.add(title to url)
        }
        return out.take(20)
    }

    private fun resolveDdgUrl(raw: String): String? {
        if (raw.startsWith("http")) return raw
        val uddg = """uddg=([^&]+)""".toRegex().find(raw)?.groupValues?.get(1)
        return if (uddg != null) runCatching { URLDecoder.decode(uddg, "UTF-8") }.getOrNull() else raw
    }

    // ---------------- 自动研究（无界面核心） ----------------

    private fun automateResearch(ctx: Context?, query: String, depth: Int): String {
        val out = parseResults(query)
        if (out !is SearchOutcome.Results) {
            if (out is SearchOutcome.Failed) writeBrowserDiag(ctx, query, out.engines)
            val why = if (out is SearchOutcome.Failed && out.anyConnected)
                "搜索引擎风控/页面改版，解析不到结果"
            else "设备无法直连搜索引擎（很可能本机没有外网）"
            return "自动化研究失败：$why，请稍后重试。（各引擎诊断已写入 Download/栖_logs/browser_*.txt）"
        }
        val results = out.list
        if (results.isEmpty()) return "自动化研究失败：未解析到搜索结果。"

        val top = preferDistinctHosts(results, depth)
        val start = System.currentTimeMillis()
        val pool = Executors.newFixedThreadPool(minOf(4, top.size))
        val futures: List<Future<String>> = top.mapIndexed { i, pair ->
            pool.submit(Callable {
                val (title, url) = pair
                val page = fetch(url)
                val body = if (page == null) "（该页面未能抓取，可能是反爬或超时；需要时可对该链接单独调用 action=\"render\"）"
                else {
                    val t = htmlToText(page.body)
                    if (t.length < JS_SHELL_THRESHOLD)
                        "（该页面正文由 JS 前端渲染，原始 HTML 是空壳；需要它的内容请对该链接单独调用 action=\"render\"）"
                    else t.take(2200)
                }
                val shown = page?.finalUrl ?: url
                "【来源 ${i + 1}】$title\n$shown\n\n$body"
            })
        }
        pool.shutdown()

        val sections = futures.mapIndexed { i, f ->
            val left = RESEARCH_BUDGET_MS - (System.currentTimeMillis() - start)
            if (left <= 0) {
                f.cancel(true)
                "【来源 ${i + 1}】${top[i].first}\n${top[i].second}\n\n（总耗时预算已到，未完成抓取）"
            } else {
                runCatching { f.get(left, TimeUnit.MILLISECONDS) }.getOrElse {
                    f.cancel(true)
                    "【来源 ${i + 1}】${top[i].first}\n${top[i].second}\n\n（抓取超时或失败）"
                }
            }
        }
        pool.shutdownNow()

        return buildString {
            append("自动化研究简报：「$query」\n")
            append("已检索 ${results.size} 条结果，并行抓取其中 ${top.size} 条正文，合并如下：\n\n")
            sections.forEach { append(it); append("\n\n---\n\n") }
            append("（以上由 AI 自动化浏览器后台抓取合并，全程未打开界面；深入单页用 read，" +
                "遇到 JS 空壳页用 render，需要顺链继续用 links。）")
        }
    }

    /** 同域去重优先：先每站取一条凑满 n，不够再用剩余的补齐。 */
    private fun preferDistinctHosts(list: List<Pair<String, String>>, n: Int): List<Pair<String, String>> {
        val picked = mutableListOf<Pair<String, String>>()
        val hosts = mutableSetOf<String>()
        val rest = mutableListOf<Pair<String, String>>()
        list.forEach { p ->
            val h = hostOf(p.second)
            if (picked.size < n && hosts.add(h)) picked.add(p) else rest.add(p)
        }
        var i = 0
        while (picked.size < n && i < rest.size) picked.add(rest[i++])
        return picked
    }

    private fun hostOf(url: String): String =
        runCatching { URI(url).host ?: url }.getOrElse { url }

    // ---------------- 单页能力 ----------------

    private fun readPage(ctx: Context?, url: String): String {
        val page = fetch(url)
        if (page == null) {
            // 直连拿不到（反爬/需要 JS 才吐内容），直接上离屏渲染
            return if (ctx != null) renderPage(ctx, url, "直连抓取失败") else "抓取失败：无法获取网页 $url"
        }
        val ct = page.contentType.lowercase()
        if (ct.contains("json") || ct.contains("text/plain") || ct.contains("xml")) {
            return "内容（${page.finalUrl}）：\n" + page.body.take(8000)
        }
        val title = titleOf(page.body)
        val text = htmlToText(page.body)

        // 空壳页（SPA / 前端渲染 / 懒加载）自动降级到离屏 WebView 跑 JS
        if (text.length < JS_SHELL_THRESHOLD && ctx != null) {
            val rendered = HeadlessRenderer.renderText(ctx, page.finalUrl)
            if (rendered != null && rendered.length > text.length) {
                return buildString {
                    if (title.isNotBlank()) append("标题：$title\n")
                    append("地址：${page.finalUrl}\n")
                    append("（原始 HTML 无正文，已用离屏 WebView 执行 JS 后取渲染结果，全程无界面）\n\n")
                    append(rendered.take(8000))
                }
            }
        }

        if (text.isBlank()) return "网页未解析到正文：${page.finalUrl}（正文可能由 JS 渲染且渲染也失败）"
        return buildString {
            if (title.isNotBlank()) append("标题：$title\n")
            append("地址：${page.finalUrl}\n\n")
            append(text.take(8000))
        }
    }

    /** 显式离屏渲染：WebView 跑完 JS 再取正文，**不显示任何界面**。 */
    private fun renderPage(ctx: Context, url: String, reason: String? = null): String {
        val rendered = HeadlessRenderer.renderText(ctx, url)
            ?: return "离屏渲染失败：$url（页面超时、需要登录或被反爬拦截）"
        return buildString {
            append("地址：$url\n")
            append("（离屏 WebView 渲染结果，已执行页面 JS，全程无界面")
            if (reason != null) append("；触发原因：$reason")
            append("）\n\n")
            append(rendered.take(8000))
        }
    }

    /** 抽取页面内链接，供模型顺链多跳研究。 */
    private fun extractLinks(url: String, limit: Int): String {
        val page = fetch(url) ?: return "抓取失败：无法获取网页 $url"
        val re = """<a\s+[^>]*href="([^"]+)"[^>]*>(.*?)</a>"""
            .toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val seen = mutableSetOf<String>()
        val items = mutableListOf<Pair<String, String>>()
        re.findAll(page.body).forEach { m ->
            val href = m.groupValues[1].trim()
            if (href.isBlank() || href.startsWith("#") || href.startsWith("javascript:") || href.startsWith("mailto:")) return@forEach
            val abs = absolutize(page.finalUrl, href)
            if (!abs.startsWith("http")) return@forEach
            val title = stripTags(m.groupValues[2]).take(100)
            if (title.isBlank()) return@forEach
            if (seen.add(abs)) items.add(title to abs)
        }
        if (items.isEmpty()) return "该页面未解析到可用链接：${page.finalUrl}"
        return buildString {
            append("页面「${titleOf(page.body).ifBlank { page.finalUrl }}」内的链接（共 ${items.size} 条，展示前 ${minOf(limit, items.size)} 条）：\n")
            items.take(limit).forEachIndexed { i, (t, u) -> append("${i + 1}. $t\n   $u\n") }
        }
    }

    /** 直接取接口原文（JSON / 纯文本），不做正文抽取。 */
    private fun fetchRaw(url: String): String {
        val page = fetch(url) ?: return "请求失败：无法访问 $url"
        return "响应（${page.finalUrl}，${page.contentType.ifBlank { "未知类型" }}）：\n" + page.body.take(8000)
    }

    // ---------------- 抓取底座 ----------------

    private data class Page(val finalUrl: String, val body: String, val contentType: String)

    /**
     * 单次抓取（不重试，连不上就是连不上，避免把对话卡住）。
     * 带字符集嗅探 + 跳板页跟随 + 响应体封顶。
     */
    private fun fetch(url: String, hop: Int = 0): Page? {
        if (hop > 2) return null
        val target = BrowserBridge.normalizeUrl(url) ?: return null
        return try {
            val req = Request.Builder().url(target)
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val ct = resp.header("Content-Type").orEmpty()
                val bytes = resp.peekBody(MAX_BODY).bytes()
                if (bytes.isEmpty()) return null
                val text = decode(bytes, ct)
                val finalUrl = resp.request.url.toString()
                val jump = redirectOf(text)
                if (jump != null) return fetch(absolutize(finalUrl, jump), hop + 1)
                Page(finalUrl, text, ct)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 按 Content-Type / <meta charset> 解码，修 GBK 中文站乱码。 */
    private fun decode(bytes: ByteArray, contentType: String): String {
        val fromHeader = Regex("charset\\s*=\\s*\"?([\\w\\-]+)", RegexOption.IGNORE_CASE)
            .find(contentType)?.groupValues?.getOrNull(1)
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        val fromMeta = Regex("<meta[^>]+charset\\s*=\\s*[\"']?\\s*([\\w\\-]+)", RegexOption.IGNORE_CASE)
            .find(head)?.groupValues?.getOrNull(1)
        val name = (fromHeader ?: fromMeta ?: "UTF-8").trim()
        val cs = runCatching { Charset.forName(name) }.getOrElse { Charsets.UTF_8 }
        return String(bytes, cs)
    }

    /** 识别跳板页（百度/搜狗 link?url= 常见）：meta refresh 或 location.replace。 */
    private fun redirectOf(html: String): String? {
        if (html.length > 4000) return null
        Regex("""(?i)<meta[^>]+http-equiv=["']?refresh["']?[^>]*content=["'][^"']*url=([^"';]+)""")
            .find(html)?.groupValues?.getOrNull(1)?.trim()?.trim('\'', '"')
            ?.let { if (it.isNotBlank()) return it }
        Regex("""(?i)(?:location\.replace\s*\(|location\.href\s*=)\s*["']([^"']+)["']""")
            .find(html)?.groupValues?.getOrNull(1)?.trim()
            ?.let { if (it.isNotBlank()) return it }
        return null
    }

    private fun absolutize(base: String, rel: String): String =
        runCatching { URI(base).resolve(rel).toString() }.getOrElse { rel }

    private fun titleOf(html: String): String =
        Regex("(?i)<title[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.getOrNull(1)?.let { stripTags(it) }.orEmpty().take(120)

    private fun stripTags(s: String): String =
        s.replace(Regex("(?i)<[^>]+>"), "").replace(Regex("&[a-zA-Z]+;"), " ")
            .replace(Regex("\\s+"), " ").trim()

    private fun htmlToText(html: String): String {
        val main = Regex("(?i)<(article|main)[^>]*>.*?</\\1>", setOf(RegexOption.DOT_MATCHES_ALL)).find(html)?.value
        var s = main ?: html
        s = s.replace(Regex("(?i)<script[^>]*>.*?</script>", setOf(RegexOption.DOT_MATCHES_ALL)), " ")
        s = s.replace(Regex("(?i)<style[^>]*>.*?</style>", setOf(RegexOption.DOT_MATCHES_ALL)), " ")
        s = s.replace(Regex("(?i)<head[^>]*>.*?</head>", setOf(RegexOption.DOT_MATCHES_ALL)), " ")
        s = s.replace(Regex("(?i)<nav[^>]*>.*?</nav>", setOf(RegexOption.DOT_MATCHES_ALL)), " ")
        s = s.replace(Regex("(?i)<footer[^>]*>.*?</footer>", setOf(RegexOption.DOT_MATCHES_ALL)), " ")
        s = s.replace(Regex("(?i)<noscript[^>]*>.*?</noscript>", setOf(RegexOption.DOT_MATCHES_ALL)), " ")
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)</(p|div|li|h[1-6]|tr)>"), "\n")
        s = s.replace(Regex("(?i)<[^>]+>"), " ")
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        return s.replace(Regex("[ \\t]+"), " ").replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    /**
     * 自诊断：把浏览器工具本次失败的真实原因（每个搜索引擎的连通情况）写入
     * [Environment.DIRECTORY_DOWNLOADS]/栖_logs/，用户无需 adb，用手机文件管理器即可查看。
     * 对齐 LlmClient 的聊天 401 诊断落盘机制。
     */
    private fun writeBrowserDiag(ctx: Context?, query: String, engines: List<String>) {
        if (ctx == null) return
        try {
            val base = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
            val dir = File(base, "栖_logs")
            if (!dir.exists()) dir.mkdirs()
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
            val body = buildString {
                append("时间=${Date()}\n")
                append("动作=浏览器搜索/研究\n")
                append("查询词=$query\n\n")
                append("各引擎诊断：\n")
                engines.forEach { append("  - $it\n") }
                append("\n排查建议：\n")
                append("  · 全部「无响应/例外」→ 本机没有外网。浏览器工具只能走设备自身网络，\n")
                append("    无法借用聊天端点（本机/局域网模型）上网。请确认设备能直接访问外网。\n")
                append("  · 有「连通但解析不到」→ 搜索引擎反爬。可让 AI 用 action=open 在内置浏览器打开，或换关键词。\n")
            }
            File(dir, "browser_$ts.txt").writeText(body)
            File(dir, "browser_last.txt").writeText(body)
        } catch (_: Exception) { /* 诊断写失败不影响主流程 */ }
    }
}
