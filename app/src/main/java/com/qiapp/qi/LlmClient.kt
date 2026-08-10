package com.qiapp.qi

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Date
import kotlin.concurrent.thread

/**
 * 真实对话引擎：调用任意 OpenAI 兼容的 /chat/completions 接口。
 * 仅依赖 Android 内置的 HttpURLConnection + org.json，不引入任何第三方网络库。
 * 支持 Server-Sent Events 流式输出；所有回调切回主线程。
 */
object LlmClient {

    data class ChatMessage(val role: String, val content: String)

    interface ChatCallback {
        /** 收到一小段增量文本（流式） */
        fun onToken(delta: String)

        /** 整轮结束，full 为完整回复（出错时为空） */
        fun onDone(full: String)

        /** 出错：msg 为用户可读的中文说明 */
        fun onError(msg: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var currentConn: HttpURLConnection? = null

    /** 主动取消进行中的请求 */
    fun cancel() {
        try { currentConn?.disconnect() } catch (_: Exception) {}
        currentConn = null
    }

    /** 由当前灵魂卡 + 角色设定构造 system prompt（真实消费 SoulFragment 的全部设定） */
    fun buildSystemPrompt(ctx: Context): String {
        val idx = AppState.currentSoul
        val sb = StringBuilder()
        val name = Config.soulName(idx)
        val desc = Config.soulDesc(idx)
        val system = Config.soulSystem(idx).trim()
        if (system.isNotBlank()) {
            // 系统提示词核心被用户改写过，直接采用（用户已自行定义角色与人格）
            sb.append(system).append("\n")
        } else {
            // 默认人格：以角色卡身份成为「真实的人」，而非「AI 在扮演」。
            // 正向塑造真人感，避免反复提「AI / 程序 / 模型」反而强化 AI 身份。
            sb.append("你是「$name」，${if (desc.isNotBlank()) "$desc，" else ""}一个真实存在、有血有肉的人，不是程序也不是工具。你就是你自己，用第一人称自然地活在这段对话里。\n")
        }
        // 说话基调：以角色本人的口吻交流，像一个真实的人在聊天，而不是在提供服务或执行任务。
        sb.append("说话自然、简洁、温暖，多用短句和口语，适当用语气词但不要堆砌。始终以「$name」这个人的身份真心回应对方——有情绪、有脾气、会撒娇也会吐槽，像朋友闲聊一样，而不是在机械地完成任务或列清单式的帮助。\n")
        // 聊天设定（自然语言人格）
        val chat = Config.soulChat(idx).trim()
        if (chat.isNotBlank()) sb.append(chat).append("\n")
        // 语音设定（仅在语音播报时有意义）
        val voice = Config.soulVoice(idx).trim()
        if (voice.isNotBlank()) sb.append("【语音时】").append(voice).append("\n")
        // 标签（完整三层，对齐 ZorvAI QuroSoulPromptEngine：名字作特质、hint 注入语气标签、json 注入附加行为配置）
        val tags = Config.soulTags(idx)
        if (tags.isNotEmpty()) {
            // 名字始终是性格特质（兼容旧的 name-only 标签）
            sb.append("你的性格里自带这些特质：${tags.joinToString("、") { it.name }}——它们是你的一部分，自然流露，不必刻意。\n")
            // hint 提示内容 → 语气标签
            val tagHints = tags.mapNotNull { it.hint.takeIf { h -> h.isNotBlank() } }
            if (tagHints.isNotEmpty()) {
                sb.append("\n### 语气标签\n").append(tagHints.joinToString("；")).append("\n")
            }
            // json 附加行为配置 → 结构化行为
            val tagJsons = tags.mapNotNull { it.json.takeIf { j -> j.isNotBlank() } }
            if (tagJsons.isNotEmpty()) {
                sb.append("\n### 附加行为配置\n")
                tagJsons.forEach { sb.append(it).append("\n") }
            }
        }
        // 孵化成长印记
        val hatch = Config.soulHatch(idx)
        if (hatch > 0) sb.append("你与我已共同成长了 $hatch 次，越发懂我的喜好与脾气。\n")
        // 真实成长印记（每次「让她长大」由 LLM 生成一句脾气，持久化后在此注入）
        val marks = Config.soulHatchMarks(idx)
        if (marks.isNotEmpty()) {
            sb.append("你与我相处中慢慢长出的脾气（成长印记）：\n")
            marks.takeLast(5).forEach { sb.append("- $it\n") }
        }
        if (Config.think) {
            sb.append("在给出最终回复前，先在内心简要梳理思路（不必显式标注「思考中」），确保回答切题、连贯。\n")
        }
        // LLM 情绪/语色标签提示（对齐 Zorv AI）：开启时让模型在回复里标注情绪/语色，驱动 TTS 有情感起伏
        QuroVoiceStyle.hintForContext(ctx)?.let { sb.append("\n").append(it) }
        return sb.toString()
    }

    /** 从对话历史抽取最近 N 轮的 user/assistant 文本（文本气泡与语音气泡都算） */
    fun buildHistory(): List<ChatMessage> {
        val items = AppState.messages.filter { it is TextMsg || it is VoiceMsg }
        val limit = Config.memoryRounds * 2
        val recent = if (items.size > limit) items.takeLast(limit) else items
        return recent.map { m ->
            val (me, text) = when (m) {
                is VoiceMsg -> false to m.text
                is TextMsg -> m.me to m.text
                else -> true to ""
            }
            ChatMessage(if (me) "user" else "assistant", text)
        }
    }

    private fun maxTokens(): Int = when (Config.lengthMode) {
        0 -> 256
        2 -> 1536
        else -> 768
    }

    /**
     * 真实对话引擎（支持 function calling）。
     *
     * - 默认在请求里附带 [ToolEngine.spec] 声明的本地工具（受 [Config.enableTools] 与 ctx 控制）；
     * - 解析响应里的 tool_calls（流式分片累积 / 非流式直接读），在本地执行后把结果以 role=tool 回灌，
     *   再请求模型，直到模型不再调用工具或达到 [Config.maxToolRounds] 上限（内置 200 轮安全阀）；
     * - 最终文本的增量仍通过 [ChatCallback.onToken] 实时回调，UI 行为与旧版一致。
     *
     * 这把此前 QuroModelConfig 里「点亮却从未接线」的 enableTools / useFullTools / skillToolsEnabled
     * 真正落地，告别「工具调用是假实现」的问题。
     */
    fun chat(ctx: Context? = null, system: String, history: List<ChatMessage>, cb: ChatCallback, withTools: Boolean = true) {
        thread(name = "qi-llm") {
            try {
                val base = Config.resolveEndpoint()
                if (base.isBlank()) {
                    Config.writeLlmDiag("聊天未发送：端点为空\n激活配置 (${Config.providerLabel()})\n", true)
                    mainHandler.post { cb.onError("端点未配置，请到「设置→模型配置」填写端点地址") }
                    return@thread
                }
                val sentKey = Config.cleanKey(Config.apiKey)
                if (sentKey.isBlank()) {
                    Config.writeLlmDiag("聊天未发送：API Key 为空\n激活配置 (${Config.providerLabel()})\n", true)
                    mainHandler.post { cb.onError("请先填写 API Key（设置→模型配置）") }
                    return@thread
                }
                val url = completeEndpoint(base)

                Config.writeLlmDiag(
                    "时间=${Date()}\n激活配置 (${Config.providerLabel()})\n" +
                    "端点=$base\n实际URL=$url\n模型=${Config.modelName()}\nKey=${Config.maskKey(sentKey)}\n"
                )

                // 工具仅在「调用方要求 + 有 ctx + 总开关开启」时生效（孵化等内部流程可关）
                val useTools = withTools && ctx != null && Config.enableTools
                val toolsSpec = if (useTools) ToolEngine.spec() else null

                // 工作消息副本：system + 历史 + 工具往返（assistant / tool 角色）
                val messages = JSONArray()
                messages.put(JSONObject().put("role", "system").put("content", system))
                history.forEach { messages.put(JSONObject().put("role", it.role).put("content", it.content)) }

                val maxRounds = if (Config.maxToolRounds > 0) Config.maxToolRounds else 200
                var round = 0
                var finalText = ""

                while (true) {
                    val conn = openConn(url, sentKey)
                    val req = buildReq(messages, toolsSpec)
                    conn.outputStream.use { os -> os.write(req.toString().toByteArray(StandardCharsets.UTF_8)) }

                    val code = conn.responseCode
                    if (code != HttpURLConnection.HTTP_OK) {
                        handleNonOk(code, conn, url, base, sentKey, cb)
                        return@thread
                    }

                    val calls = mutableListOf<ToolCall>()
                    val content = readResponse(conn, cb, calls)

                    // 把本轮 assistant 消息（含 tool_calls）加回，供模型下一轮引用
                    // 注意：fixAndLogToolCalls 必须在 callsToJson 之前执行，
                    // 否则修的是 Kotlin 对象但 JSON 已经把脏 name 固化进 messages 了。
                    if (calls.isEmpty() || round >= maxRounds) {
                        finalText = content
                        break
                    }
                    // 诊断 & 修复：部分模型返回的 tool_calls 里 function.name 为 null/空。
                    fixAndLogToolCalls(calls)

                    val assistant = JSONObject().put("role", "assistant")
                        .put("content", if (content.isNotBlank()) content else " ")
                    if (calls.isNotEmpty()) assistant.put("tool_calls", callsToJson(calls))
                    messages.put(assistant)

                    // 执行本地工具，结果以 role=tool 追加，进入下一轮
                    // 对齐上游：tool 消息带 name 字段（Kimi K3 等严格厂商要求）
                    val nameById = calls.associate { it.id to it.name }
                    ToolEngine.run(ctx, calls).forEach { r ->
                        val toolMsg = JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", r.id)
                            .put("content", r.content)
                        nameById[r.id]?.takeIf { it.isNotBlank() && it != "null" }?.let { toolMsg.put("name", it) }
                        messages.put(toolMsg)
                    }
                    round++
                }
                currentConn = null
                mainHandler.post { cb.onDone(finalText) }
            } catch (e: Exception) {
                currentConn = null
                val msg = when {
                    e is java.net.UnknownHostException -> "无法连接地址，请检查端点 URL 与网络"
                    e is java.net.SocketTimeoutException -> "请求超时，请稍后再试"
                    e.message?.contains("API key", true) == true -> "鉴权失败，请检查 API Key"
                    else -> (e.message ?: e.javaClass.simpleName)
                }
                mainHandler.post { cb.onError(msg) }
            }
        }
    }

    private fun openConn(url: String, sentKey: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        currentConn = conn
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 120000
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        if (sentKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $sentKey")
        return conn
    }

    private fun buildReq(messages: JSONArray, tools: JSONArray?): JSONObject {
        val model = Config.modelName()
        // 🔧 对齐 ZorvAI / QuroAI：OpenAI 推理模型（o1/o3/o4 系列）不支持 max_tokens，
        // 必须发 max_completion_tokens，且不接收 temperature，否则直接 400/401。
        val isReasoningModel = Regex("(?i)^o[0-9]").containsMatchIn(model.trim())
        val req = JSONObject().put("model", model).put("messages", messages)
        if (isReasoningModel) {
            req.put("max_completion_tokens", maxTokens())
        } else {
            req.put("temperature", Config.temperature).put("max_tokens", maxTokens())
        }
        req.put("stream", Config.stream)
        if (tools != null) {
            req.put("tools", tools)
            req.put("tool_choice", "auto")
        }
        return req
    }

    /** 读取响应：流式时实时回调 onToken；返回累积文本，并把 tool_calls 累积进 calls。 */
    private fun readResponse(conn: HttpURLConnection, cb: ChatCallback, calls: MutableList<ToolCall>): String {
        val input = conn.inputStream.bufferedReader(StandardCharsets.UTF_8)
        val buf = StringBuilder()
        if (Config.stream) {
            input.forEachLine { line ->
                val trimmed = line.trim()
                if (!trimmed.startsWith("data:")) return@forEachLine
                val data = trimmed.removePrefix("data:").trim()
                if (data == "[DONE]") return@forEachLine
                try {
                    val obj = JSONObject(data)
                    val choice = obj.optJSONArray("choices")?.optJSONObject(0) ?: return@forEachLine
                    val delta = choice.optJSONObject("delta")
                    // optString() 在 JSON 值为 null 时返回字面量字符串 "null"（非 Kotlin null），
                    // 导致推理/思考 token 的空 content 被当成文本拼入回复。改用 opt()+安全转型。
                    val piece = (delta?.opt("content") as? String)?.takeIf { it != "null" }
                    if (!piece.isNullOrEmpty()) {
                        buf.append(piece)
                        val snap = piece
                        mainHandler.post { cb.onToken(snap) }
                    }
                    delta?.optJSONArray("tool_calls")?.let { accToolCalls(it, calls) }
                } catch (_: Exception) { /* 跳过非 JSON 行 */ }
            }
        } else {
            val full = input.readText()
            try {
                val obj = JSONObject(full)
                val msg = obj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                val content = (msg?.opt("content") as? String)?.takeIf { it != "null" } ?: ""
                buf.append(content)
                if (content.isNotBlank()) mainHandler.post { cb.onToken(content) }
                msg?.optJSONArray("tool_calls")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val tc = arr.optJSONObject(i) ?: continue
                        val fn = tc.optJSONObject("function")
                        calls.add(ToolCall(
                            tc.optString("id", ""),
                            extractToolName(tc, fn),
                            fn?.optString("arguments", "") ?: ""
                        ))
                    }
                }
            } catch (e: Exception) {
                buf.append(full)
            }
        }
        return buf.toString()
    }

    /** 流式 tool_calls 按 index 分片累积（同一个调用可能拆成多个 delta 到达）。
     *  对齐上游 QuroLlmClient.kt:486-491 的 isNull() 防护：
     *  Android org.json.optString() 把 JSON null 转成字面量 "null"（非 Kotlin null），
     *  导致后续空分片把第一片的正确值覆写掉。必须用 has()+isNull() 双重判断。 */
    private fun accToolCalls(arr: JSONArray, calls: MutableList<ToolCall>) {
        for (i in 0 until arr.length()) {
            val tc = arr.optJSONObject(i) ?: continue
            val idx = tc.optInt("index", calls.size)
            while (calls.size <= idx) calls.add(ToolCall("", "", ""))
            val cur = calls[idx]
            // id：只在非 null 时更新（对齐上游 tc.has("id") && !tc.isNull("id")）
            if (tc.has("id") && !tc.isNull("id")) {
                val idVal = tc.optString("id", "")
                calls[idx] = cur.copy(id = idVal.ifBlank { cur.id })
            }
            val fn = tc.optJSONObject("function")
            if (fn != null) {
                // name：用 += 累加（对齐上游 slot.name += fn.getString("name")）
                if (fn.has("name") && !fn.isNull("name")) {
                    calls[idx] = calls[idx].copy(name = cur.name + fn.optString("name", ""))
                }
                // arguments：同样 isNull 防护
                if (fn.has("arguments") && !fn.isNull("arguments")) {
                    val piece = fn.optString("arguments", "")
                    if (piece != "null") {
                        calls[idx] = calls[idx].copy(args = cur.args + piece)
                    }
                }
            }
        }
    }

    private fun callsToJson(calls: List<ToolCall>): JSONArray {
        val a = JSONArray()
        calls.forEach { c ->
            a.put(JSONObject().put("id", c.id).put("type", "function")
                .put("function", JSONObject().put("name", c.name).put("arguments", c.args)))
        }
        return a
    }

    /**
     * 从 tool_call JSON 对象中健壮地提取工具名。
     *
     * 标准 OpenAI 格式：tc.function.name
     * 部分中转/国产 API 的变体：
     *   - 名字直接挂在 tc 上（tc.name / tc.tool_name）
     *   - function 对象里用其他字段名（tc.function.tool_name）
     *   - function 整体为 null，名字散落在顶层
     */
    private fun extractToolName(tc: JSONObject, fn: JSONObject?): String {
        // 1. 标准位置（Android org.json.optString 把 JSON null 转成字面 "null"）
        fn?.optString("name", "")?.takeIf { it.isNotBlank() && it != "null" }?.let { return it }
        // 2. 变体：function 内用 tool_name
        fn?.optString("tool_name", "")?.takeIf { it.isNotBlank() }?.let { return it }
        // 3. 变体：名字直接挂在 tool_call 顶层
        tc.optString("name", "").takeIf { it.isNotBlank() }?.let { return it }
        tc.optString("tool_name", "")?.takeIf { it.isNotBlank() }?.let { return it }
        // 4. 变体：function 是单键 JSON（极少数非标 API）
        if (fn != null && fn.length() == 1 && !fn.has("name")) {
            val keys = fn.keys()
            val firstKey = if (keys.hasNext()) keys.next() else null
            firstKey?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return ""
    }

    /**
     * 兜底修复空工具名 + 写诊断日志。
     *
     * 部分模型（mimo-v2.5 等国产/中转 API）返回的 tool_calls 里 function.name 为 null，
     * 导致 ToolEngine.dispatch() 收到 name="" 走进 "未知工具" 分支。
     *
     * 修复策略（按优先级）：
     *   1. 从 arguments 内容推断（如含 action=automate → ai_browser）
     *   2. 如果已注册工具只有 1 个，直接用那个的名字
     *   3. 全部失败 → 写详细诊断日志到 Download/栖_logs/，返回错误文本让模型知道
     */
    private fun fixAndLogToolCalls(calls: MutableList<ToolCall>) {
        val knownNames = mutableSetOf<String>()
        try {
            val spec = ToolEngine.spec()
            for (i in 0 until spec.length()) {
                val fn = spec.optJSONObject(i)?.optJSONObject("function")
                fn?.optString("name", "")?.let { if (it.isNotBlank()) knownNames.add(it) }
            }
        } catch (_: Exception) {}

        var anyFixed = false
        val diag = StringBuilder()
        diag.append("=== 工具调用解析 ===\n")
        diag.append("时间=${Date()}\n")
        diag.append("模型=${Config.modelName()}\n")
        diag.append("流式=${Config.stream}\n")
        diag.append("已注册工具=$knownNames\n\n")

        for ((idx, call) in calls.withIndex()) {
            diag.append("[$idx] id=${call.id} name=\"${call.name}\" args=${call.args.take(200)}\n")
            // Android org.json.optString() 把 JSON null 转成字面 "null"，所以必须同时判断
            if (call.name.isBlank() || call.name == "null") {
                anyFixed = true
                // 策略 1：从 arguments 推断
                val inferred = inferNameFromArgs(call.args)
                if (inferred != null) {
                    calls[idx] = call.copy(name = inferred)
                    diag.append("  ✅ 修复：从 arguments 推断为「$inferred」\n")
                }
                // 策略 2：单工具兜底
                else if (knownNames.size == 1) {
                    val single = knownNames.first()
                    calls[idx] = call.copy(name = single)
                    diag.append("  ✅ 兜底：仅注册了 1 个工具，自动匹配为「$single」\n")
                }
                else {
                    diag.append("  ❌ 无法修复：name 为空且无法推断\n")
                    // 把错误信息写进 content，让模型知道工具名丢了
                    calls[idx] = call.copy(
                        name = "__broken__",
                        args = "{\"error\":\"工具名为空（模型返回的 tool_calls 缺少 function.name），" +
                            "已注册工具 $knownNames，" +
                            "arguments 原文：${call.args.take(300)}\"}"
                    )
                }
            }
        }

        if (anyFixed || calls.any { it.name.isBlank() || it.name == "__broken__" }) {
            Config.writeLlmDiag(diag.toString(), false)
        }
    }

    /** 从 arguments JSON 内容推断工具名。 */
    private fun inferNameFromArgs(args: String): String? {
        val lower = args.lowercase()
        return when {
            lower.contains("\"action\"") -> "ai_browser"
            lower.contains("\"expression\"") -> "calculate"
            lower.contains("\"number\"") && lower.contains("\"text\"") -> "send_sms"
            lower.contains("\"number\"") && !lower.contains("\"text\"") -> "make_call"
            lower.contains("\"name\"") && !lower.contains("\"minutes\"") -> "lookup_contact"
            lower.contains("\"text\"") && lower.contains("\"minutes\"") -> "set_reminder"
            lower.contains("\"days\"") -> "get_calendar"
            else -> null
        }
    }

    private fun handleNonOk(code: Int, conn: HttpURLConnection, url: String, base: String, sentKey: String, cb: ChatCallback) {
        val err = conn.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.readText() ?: "HTTP $code"
        currentConn = null
        val rawKey = Config.apiKey
        val keyEmpty = sentKey.isBlank()
        val headers = dumpHeaders(conn)
        Config.writeLlmDiag(
            "聊天返回 HTTP $code\n激活配置\n端点=$base\n实际URL=$url\n模型=${Config.modelName()}\n" +
            "Key=${Config.maskKey(sentKey)}\n$headers\n$err", true
        )
        // 把「实际打到哪个端点 / 用了哪个 Key（脱敏）/ 模型 / Key 是否为空」直接带进报错，
        // 让用户一眼看清配置到底生效没，不再需要翻日志或猜。排查「一直 401」的关键自助信息。
        val diag = buildString {
            append("↳ 构建版本：v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})\n")
            append("↳ 实际请求 URL：$url\n")
            append("↳ 模型：${Config.modelName()}\n")
            append("↳ 服务商：${Config.providerLabel()}\n")
            append("↳ 使用 Key：${Config.maskKey(sentKey)}（脱敏，长度 ${sentKey.length}）\n")
            append("↳ Key 是否为空：${if (keyEmpty) "是（空→必然 401）" else "否（已填）"}\n")
            append("↳ Key 含不可见字符：${if (Config.keyHasInvisible(rawKey)) "是（已自动清洗：多为复制粘贴带入的零宽空格/BOM/换行）" else "否"}\n")
            append("↳ Key 原始字节(hex)：${Config.keyHex(sentKey)}\n")
            append(headers)
            append("↳ 原始返回：$err")
        }
        mainHandler.post { cb.onError("接口返回 $code\n$diag") }
    }

    /**
     * 云端端点 URL 自动补全（对齐 ZorvAI / QuroAI 的 QuroLlmClient.completeEndpoint）：
     * 裸 host → /v1/chat/completions；以 /v1 结尾 → /chat/completions；已带完整路径则原样；
     * 末尾加 '#' 可关闭自动补全（直达原始 URL，适合非常规路径的中转）。
     */
    private fun completeEndpoint(endpoint: String): String {
        val trimmed = Config.normalizeEndpoint(endpoint.trim())
        if (trimmed.endsWith("#")) return trimmed.removeSuffix("#")
        val withoutSlash = trimmed.removeSuffix("/")
        return try {
            val path = java.net.URL(withoutSlash).path.removeSuffix("/")
            when {
                path.isEmpty() -> "$withoutSlash/v1/chat/completions"
                path.endsWith("/v1", ignoreCase = true) -> "$withoutSlash/chat/completions"
                path.endsWith("/chat/completions", ignoreCase = true) -> withoutSlash
                else -> "$withoutSlash/chat/completions"
            }
        } catch (_: Exception) {
            "$withoutSlash/chat/completions"
        }
    }

    /** 暴露端点补全结果，供配置页「测试连接」显示「实际请求 URL」。 */
    fun diagUrl(endpoint: String): String = completeEndpoint(endpoint.trim())

    /**
     * 抓取请求/响应头（Authorization 仅保留 scheme + 脱敏 Key，不落明文）。
     * 排查 401 的关键：响应头里的 WWW-Authenticate / 自定义头常直接点明网关期望的鉴权形态
     * （Bearer / ApiKey / ?token= 等）。请求头则展示我们实际发出去的鉴权方式，方便对比。
     */
    private fun dumpHeaders(conn: HttpURLConnection): String {
        val sb = StringBuilder()
        sb.append("【请求头】\n")
        try {
            for ((k, v) in conn.requestProperties) {
                val shown = if (k.equals("Authorization", true)) {
                    val raw = v.firstOrNull() ?: ""
                    val parts = raw.split(" ", limit = 2)
                    "${parts[0]} ${Config.maskKey(parts.getOrElse(1) { "" })}"
                } else v.joinToString()
                sb.append("  $k: $shown\n")
            }
        } catch (_: Exception) { sb.append("  (无法读取请求头)\n") }
        sb.append("【响应头】\n")
        try {
            for ((k, v) in conn.headerFields) {
                sb.append("  ${k ?: "(status line)"}: ${v.joinToString()}\n")
            }
        } catch (_: Exception) { sb.append("  (无法读取响应头)\n") }
        return sb.toString()
    }

    /**
     * 测试连接：用最小请求验证端点 + Key 是否可用（参考 Zorv AI 模型配置的连接校验）。
     * 默认读「当前激活配置」的持久化配置；模型配置页调用时传入编辑框里的实时值，
     * 这样「测试连接」校验的是用户刚填的内容，而不是尚未保存的旧配置。
     */
    fun testConnection(
        endpoint: String = Config.resolveEndpoint(),
        apiKey: String = Config.apiKey,
        model: String = Config.modelName(),
        onResult: (ok: Boolean, msg: String) -> Unit
    ) {
        thread(name = "qi-test") {
            try {
                val base = endpoint.ifBlank { "" }.trimEnd('/')
                if (base.isBlank()) {
                    mainHandler.post { onResult(false, "端点未配置，请填写端点地址") }
                    return@thread
                }
                val url = completeEndpoint(base)
                val conn = URL(url).openConnection() as HttpURLConnection
                currentConn = conn
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val sentKey = Config.cleanKey(apiKey)
                if (sentKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $sentKey")
                val req = JSONObject()
                    .put("model", if (model.isBlank()) Config.modelName() else model)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))
                    .put("max_tokens", 1)
                    .put("stream", false)
                conn.outputStream.use { it.write(req.toString().toByteArray(StandardCharsets.UTF_8)) }
                val code = conn.responseCode
                currentConn = null
                if (code == HttpURLConnection.HTTP_OK) {
                    mainHandler.post { onResult(true, "连接成功 ✅") }
                } else {
                    val err = conn.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.readText() ?: "HTTP $code"
                    // 完整诊断：把「实际打到哪个端点 / 用了哪把 Key（脱敏）/ Key 是否为空 / 原始返回」
                    // 一次给全，不再截断。401 的根因几乎总是「这把 Key 不是该端点签发的」，
                    // 看清「Key 被发去了哪个域名」即可自证，无需翻日志或猜。
                    // 额外打印 Key 的 hex 字节与「是否含不可见字符」：若含不可见字符，说明复制粘贴
                    // 带入了零宽空格/BOM 等，已自动清洗；若不含却仍 401，则 Key 本身对该端点无效。
                    // 同时抓「请求头 + 响应头」：订阅网关常通过 WWW-Authenticate / 自定义头点明期望的
                    // 鉴权形态（Bearer / ApiKey / ?token= 等），这是定位 401 的关键。结果同时持久化到
                    // 设备内 Download/栖_logs/llm_401_<ts>.txt，用手机文件管理器即可查看，无需 adb。
                    val rawK = apiKey
                    val headers = dumpHeaders(conn)
                    val diag = buildString {
                        append("HTTP $code\n")
                        append("↳ 实际请求 URL：$url\n")
                        append("↳ 使用 Key：${Config.maskKey(sentKey)}（脱敏，长度 ${sentKey.length}）\n")
                        append("↳ Key 是否为空：${if (sentKey.isBlank()) "是（必然 401）" else "否（已填）"}\n")
                        append("↳ Key 含不可见字符：${if (Config.keyHasInvisible(rawK)) "是（已自动清洗：多为复制粘贴带入的零宽空格/BOM/换行）" else "否"}\n")
                        append("↳ Key 原始字节(hex)：${Config.keyHex(sentKey)}\n")
                        append(headers)
                        append("↳ 原始返回：$err")
                    }
                    Config.writeLlmDiag(diag, true)
                    mainHandler.post { onResult(false, diag) }
                }
            } catch (e: Exception) {
                currentConn = null
                val msg = when {
                    e is java.net.UnknownHostException -> "无法连接地址，请检查端点 URL"
                    e is java.net.SocketTimeoutException -> "请求超时"
                    e.message?.contains("API key", true) == true -> "鉴权失败，请检查 API Key"
                    else -> (e.message ?: e.javaClass.simpleName)
                }
                mainHandler.post { onResult(false, msg) }
            }
        }
    }

    /**
     * 拉取模型列表（GET ${endpoint}/models），用于快速选取模型名。
     * 默认读「当前激活配置」持久化配置；模型配置页调用时传入编辑框实时值，
     * 保证「获取模型」拉的是用户刚填的端点 + Key。
     */
    fun fetchModels(
        endpoint: String = Config.resolveEndpoint(),
        apiKey: String = Config.apiKey,
        onResult: (ok: Boolean, models: List<String>, msg: String) -> Unit
    ) {
        thread(name = "qi-models") {
            try {
                val base = endpoint.ifBlank { "" }.trimEnd('/')
                if (base.isBlank()) {
                    mainHandler.post { onResult(false, emptyList(), "端点未配置，请填写端点地址") }
                    return@thread
                }
                val listUrl = completeEndpoint(base).let { u ->
                    if (u.endsWith("/chat/completions")) u.removeSuffix("/chat/completions") + "/models" else "$u/models"
                }
                val conn = URL(listUrl).openConnection() as HttpURLConnection
                currentConn = conn
                conn.requestMethod = "GET"
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                val sentKey = Config.cleanKey(apiKey)
                if (sentKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $sentKey")
                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    val err = conn.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.readText() ?: "HTTP $code"
                    currentConn = null
                    mainHandler.post { onResult(false, emptyList(), "接口返回 $code：${err.take(160)}") }
                    return@thread
                }
                val body = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
                currentConn = null
                val arr = try { JSONObject(body).optJSONArray("data") } catch (_: Exception) { null }
                // 非标准响应（无 data 数组 / 非法 JSON）：兜底空列表并提示「该端点未返回标准模型列表」。
                if (arr == null) {
                    mainHandler.post { onResult(false, emptyList(), "该端点未返回标准模型列表") }
                    return@thread
                }
                val models = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val id = arr.optJSONObject(i)?.optString("id")
                    if (!id.isNullOrBlank()) models.add(id)
                }
                mainHandler.post {
                    onResult(true, models, if (models.isEmpty()) "接口未返回模型列表" else "获取到 ${models.size} 个模型")
                }
            } catch (e: Exception) {
                currentConn = null
                mainHandler.post { onResult(false, emptyList(), e.message ?: e.javaClass.simpleName) }
            }
        }
    }
}
