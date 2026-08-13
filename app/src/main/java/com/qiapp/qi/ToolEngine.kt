package com.qiapp.qi

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.util.Base64
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 本地工具引擎（function calling）。
 *
 * 真实消费 [QuroModelConfig] 里被「点亮却从未接线」的工具开关（enableTools / useFullTools /
 * skillToolsEnabled）——此前 LlmClient.chat() 只发 model/messages，从不带 tools，也不解析
 * tool_calls，等于一段死代码。这里把它接成真正的「模型请求工具 → 本地执行 → 结果回灌」闭环。
 *
 * 工具集聚焦「无需外部密钥、纯本地即可生效」的能力，并对接用户指出的「缺少手机权限」：
 *  - 时间 / 计算 / 本地提醒：零权限，永远可用；
 *  - 打电话 / 发短信 / 查联系人 / 读日历：受危险权限保护，未授权时如实告知，授权后真实执行。
 */
data class ToolCall(val id: String, val name: String, val args: String)
data class ToolResult(val id: String, val content: String)

object ToolEngine {

    // ---------- 下发给模型的工具声明（OpenAI function calling 格式） ----------

    fun spec(): JSONArray {
        val a = JSONArray()
        a.put(fn("get_current_time", "获取当前日期与时间（含星期）。用于回答「现在几点」「今天几号」「星期几」等问题。", emptyList(), emptyList()))
        a.put(fn("calculate", "计算数学表达式，支持 + - * / % ^ 与括号。用于回答「多少乘多少」「算一下」等问题。",
            listOf("expression" to "要计算的表达式，例如 \"12*8+3\" 或 \"(100-20)/4\""), listOf("expression")))
        a.put(fn("set_reminder", "设置一个本地提醒，到时间后会在通知栏提醒。",
            listOf("text" to "提醒内容，例如 \"该喝水了\"", "minutes" to "多少分钟后提醒（数字，例如 10）"),
            listOf("text", "minutes")))
        a.put(fn("make_call", "拨打电话（需电话权限；未授权时仅打开拨号盘）。",
            listOf("number" to "要拨打的电话号码，或通讯录里的人名"), listOf("number")))
        a.put(fn("send_sms", "发送短信（需短信权限）。",
            listOf("number" to "接收方电话号码", "text" to "短信内容"), listOf("number", "text")))
        a.put(fn("lookup_contact", "在通讯录里按姓名查找联系人电话（需联系人权限）。",
            listOf("name" to "要查找的姓名关键词"), listOf("name")))
        a.put(fn("get_calendar", "读取未来若干天的日历日程（需日历权限）。",
            listOf("days" to "向后查看多少天（数字，默认 3）"), emptyList()))
        // AI 主动向对话发送文件 / 图片 / 文档：把生成的文本、代码、SVG 或 base64 图片保存为真实文件并推入聊天气泡
        a.put(fn("send_file",
            "把生成的内容（文本 / 代码 / 文档 / SVG 矢量图 / base64 图片）保存成真实文件并发送到当前对话，" +
            "用户可在聊天里点击直接打开。用于用户要求「存成文件 / 导出 / 发给我一份文档 / 画一张图」等场景。" +
            "图片请用 encoding=\"base64\" 传 PNG/JPG，或传 SVG 文本（mime=image/svg+xml）。",
            listOf(
                "filename" to "文件名，需带扩展名，例如 \"会议纪要.md\"、\"draw.py\"、\"pic.png\"、\"art.svg\"",
                "content" to "文件内容：文本/代码直接传字符串；图片传 base64 字符串（配合 encoding=base64）",
                "mime" to "可选，MIME 类型，例如 text/markdown、text/plain、image/png、image/svg+xml；缺省按扩展名推断",
                "encoding" to "可选，内容编码：\"base64\" 表示 content 是 base64（用于图片），省略则为纯文本"
            ),
            listOf("filename", "content")))
        // AI 主动记录长期记忆：把重要的人/事/约定写进记忆库，跨会话不忘。
        a.put(fn("save_memory",
            "把值得长期记住的事写进你的记忆库（跨会话持久化）：用户讲过的喜好、重要约定、共同经历、刚聊到的关键细节等。" +
            "用于你判断「这件事以后还会用上，该记住」的时刻。weight 1-3 表示重要程度（3 最重要）。",
            listOf(
                "text" to "要记住的内容，简洁一句，例如「用户讨厌被叫全名」「我们约好周五去看展」",
                "weight" to "可选，重要程度 1-3，默认 2（用户明确说「记住这个」或涉及约定/偏好时用 3）"
            ),
            listOf("text")))
        // AI 自动化浏览器：联网搜索 / 抓正文 / 抽链接 / 调接口 / 下载，全部后台无界面
        a.put(AiBrowserTool.spec())
        // 权限型工具（定位 / 短信读取 / 通话记录 / 媒体库 / 手电筒 / 蓝牙 / 日历写入 /
        // 电话信息 / 权限自检）——补齐「权限页已授权但模型没有对应工具可调」的缺口。
        ToolsPerm.specs().forEach { a.put(it) }
        return a
    }

    private fun fn(name: String, desc: String, fields: List<Pair<String, String>>, required: List<String>): JSONObject {
        val props = JSONObject()
        fields.forEach { (n, d) -> props.put(n, JSONObject().put("type", "string").put("description", d)) }
        val params = JSONObject()
            .put("type", "object")
            .put("properties", props)
            .put("required", JSONArray(required))
        return JSONObject().put("type", "function").put("function",
            JSONObject().put("name", name).put("description", desc).put("parameters", params))
    }

    // ---------- 执行 ----------

    fun run(ctx: Context?, calls: List<ToolCall>): List<ToolResult> =
        calls.map { call ->
            val res = try {
                dispatch(ctx, call.name, call.args)
            } catch (e: Exception) {
                "工具执行出错：${e.message}"
            }
            ToolResult(call.id, res)
        }

    private fun dispatch(ctx: Context?, name: String, rawArgs: String): String {
        // 权限网关（对齐上游 ZorvAI）：危险工具先确保权限到位再执行。
        // 已授权直接放行；未授权则经 Activity 注入的 requester 弹系统框请求（后台线程阻塞等待）。
        //
        // 语义为「任一满足」而非「全部满足」：同一工具声明的多个权限彼此等价
        // （定位 FINE/COARSE、媒体 IMAGES/VIDEO/AUDIO），且跨版本权限在当前系统上
        // 恒为 DENIED，用「全部满足」会把已授权的用户也一并拒掉。详见 PermissionGate.anyGranted。
        if (ctx != null) {
            val needs = permsFor(name)
            if (needs.isNotEmpty() && !PermissionGate.ensureAnyGranted(ctx, needs)) {
                return "需要权限：${needs.joinToString()}，请允许弹出的授权请求，或在「设置→权限」中授予后重试。"
            }
        }
        val args = try { JSONObject(rawArgs.ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
        return when (name) {
            "get_current_time" -> currentTime()
            "calculate" -> calc(args.optString("expression", ""))
            "set_reminder" -> if (ctx != null) setReminder(ctx, args.optString("text", ""), args.optString("minutes", "")) else "无应用上下文，无法设置提醒"
            "make_call" -> if (ctx != null) makeCall(ctx, args.optString("number", "")) else "无应用上下文"
            "send_sms" -> if (ctx != null) sendSms(ctx, args.optString("number", ""), args.optString("text", "")) else "无应用上下文"
            "lookup_contact" -> if (ctx != null) lookupContact(ctx, args.optString("name", "")) else "无应用上下文"
            "get_calendar" -> if (ctx != null) getCalendar(ctx, args.optString("days", "")) else "无应用上下文"
            "send_file" -> if (ctx != null) sendFile(ctx, args) else "无应用上下文"
            "save_memory" -> saveMemory(args)
            // 无界面 AI 浏览器：运行在 qi-llm 后台线程，直接把网页/搜索结果回灌给模型
            AiBrowserTool.NAME -> AiBrowserTool.run(ctx, args)
            // 权限型工具集（定位/短信/通话记录/媒体/手电筒/蓝牙/日历写入/电话/权限自检）
            else -> if (ToolsPerm.handles(name)) {
                if (ctx != null) ToolsPerm.run(ctx, name, args) else "无应用上下文"
            } else {
                "未知工具：$name"
            }
        }
    }

    /**
     * 工具名 → 所需危险权限（仅运行时权限需要经网关请求）。
     * 语义为「任一满足即放行」，见 [PermissionGate.ensureAnyGranted]。
     */
    private fun permsFor(name: String): List<String> = when (name) {
        "make_call" -> listOf(Manifest.permission.CALL_PHONE)
        "send_sms" -> listOf(Manifest.permission.SEND_SMS)
        "lookup_contact" -> listOf(Manifest.permission.READ_CONTACTS)
        "get_calendar" -> listOf(Manifest.permission.READ_CALENDAR)
        else -> ToolsPerm.permsFor(name)
    }

    // ---------- 各工具实现 ----------

    private fun currentTime(): String {
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss EEEE", Locale.CHINA)
        return "当前时间：${f.format(Date())}"
    }

    private fun calc(expr: String): String {
        if (expr.isBlank()) return "缺少表达式"
        return try {
            val v = ExprParser(expr).parse()
            if (!v.isFinite()) return "计算结果不是有限数"
            val s = if (v == v.toLong().toDouble()) v.toLong().toString()
            else String.format(Locale.US, "%.6f", v).trimEnd('0').trimEnd('.')
            "计算结果：$expr = $s"
        } catch (e: Exception) {
            "无法计算：${e.message}"
        }
    }

    private fun setReminder(ctx: Context, text: String, minutesRaw: String): String {
        if (text.isBlank()) return "缺少提醒内容"
        val mins = minutesRaw.toIntOrNull()?.coerceIn(1, 10080) ?: 10
        val fire = System.currentTimeMillis() + mins * 60_000L
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(ctx, ReminderReceiver::class.java)
            .putExtra("text", text).putExtra("fire", fire)
        val pi = PendingIntent.getBroadcast(ctx, fire.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fire, pi)
        // 持久化，供开机后 BootReceiver 重新排程
        val prefs = ctx.getSharedPreferences("qi_reminders", Context.MODE_PRIVATE)
        val set = prefs.getStringSet("list", emptySet())?.toMutableSet() ?: mutableSetOf()
        set.add("$fire|$text")
        prefs.edit().putStringSet("list", set).apply()
        val f = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(fire))
        return "已设置提醒：「$text」，将在约 $mins 分钟后（$f）提醒你。"
    }

    private fun makeCall(ctx: Context, number: String): String {
        if (number.isBlank()) return "缺少号码"
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val intent = Intent(if (granted) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:$number"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            if (granted) "已为你拨打电话：$number" else "已打开拨号盘：$number（如需自动拨打，请在「权限」页授予电话权限）"
        } catch (e: Exception) {
            "无法拨号：${e.message}"
        }
    }

    private fun sendSms(ctx: Context, number: String, text: String): String {
        if (number.isBlank() || text.isBlank()) return "缺少号码或内容"
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        if (!granted) return "未授予短信权限，无法发送。请在「权限」页授予短信权限后重试。"
        return try {
            SmsManager.getDefault().sendTextMessage(number, null, text, null, null)
            "已发送短信给 $number：$text"
        } catch (e: Exception) {
            "发送失败：${e.message}"
        }
    }

    private fun lookupContact(ctx: Context, name: String): String {
        if (name.isBlank()) return "缺少姓名"
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        if (!granted) return "未授予联系人权限，无法查询。请在「权限」页授予联系人权限后重试。"
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val cur = ctx.contentResolver.query(
            uri,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$name%"), null
        ) ?: return "查无此人"
        val sb = StringBuilder()
        var n = 0
        while (cur.moveToNext() && n < 5) {
            sb.append("- ${cur.getString(0)}：${cur.getString(1)}\n")
            n++
        }
        cur.close()
        return if (sb.isEmpty()) "通讯录中未找到含「$name」的联系人" else "找到联系人：\n$sb"
    }

    private fun getCalendar(ctx: Context, daysRaw: String): String {
        val days = daysRaw.toIntOrNull()?.coerceIn(1, 60) ?: 3
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        if (!granted) return "未授予日历权限，无法读取。请在「权限」页授予日历权限后重试。"
        val now = System.currentTimeMillis()
        val end = now + days * 24L * 3600_000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(now.toString()).appendPath(end.toString()).build()
        val cur = ctx.contentResolver.query(
            uri,
            arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.DTSTART, CalendarContract.Instances.EVENT_LOCATION),
            null, null, "start ASC"
        ) ?: return "无日程"
        val sb = StringBuilder()
        var n = 0
        val f = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        while (cur.moveToNext() && n < 10) {
            val t = cur.getLong(1)
            val loc = if (!cur.isNull(2)) " @${cur.getString(2)}" else ""
            sb.append("- ${f.format(Date(t))} ${cur.getString(0) ?: ""}$loc\n")
            n++
        }
        cur.close()
        return if (sb.isEmpty()) "未来 $days 天没有日程" else "未来 $days 天日程：\n$sb"
    }

    /**
     * 工具 send_file：把 AI 生成的内容落盘为真实文件，并作为气泡注入对话（用户可点击打开）。
     * 支持：纯文本 / 代码 / 文档 / SVG（矢量图）；图片用 encoding=base64 传 PNG/JPG 亦可直接解码。
     */
    private fun sendFile(ctx: Context, args: JSONObject): String {
        val filename = args.optString("filename", "").ifBlank { return "缺少文件名（filename）" }
        val content = args.optString("content", "")
        if (content.isBlank()) return "缺少文件内容（content）"
        val mime = args.optString("mime", "").ifBlank { guessMime(filename) }
        val encoding = args.optString("encoding", "").lowercase()

        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val dir = File(base, "sent")
        if (!dir.exists() && !dir.mkdirs()) return "无法创建文件目录"

        val safeName = filename.replace(Regex("""[\\/]"""), "_").take(120)
        val file = File(dir, safeName)
        return try {
            if (encoding == "base64") {
                val bytes = Base64.decode(content, Base64.DEFAULT)
                file.writeBytes(bytes)
            } else {
                file.writeText(content)
            }
            val isImage = mime.startsWith("image/") || safeName.endsWith(".svg", true)
            val fm = FileMsg(
                name = safeName,
                path = file.absolutePath,
                mime = mime,
                size = file.length(),
                me = false,
                isImage = isImage
            )
            // 注入聊天流（主线程追加 + 刷新），让文件气泡即时出现
            ChatInjection.inject(fm)
            "已把文件「$safeName」(${file.length()} 字节) 发送到对话，用户可在聊天里点击打开查看。"
        } catch (e: Exception) {
            "发送文件失败：${e.message}"
        }
    }

    /**
     * 工具 save_memory：把一条长期记忆写入当前灵魂的记忆库（跨会话持久化），
     * 与「人类资料库提示词 / 人格卡」一起构成 AI 自我演化的三要素之一。
     */
    private fun saveMemory(args: JSONObject): String {
        val text = args.optString("text", "").trim()
        if (text.isBlank()) return "缺少要记住的内容（text）"
        val w = args.optString("weight", "2").toIntOrNull()?.coerceIn(1, 3) ?: 2
        Config.addMemory(AppState.currentSoul, text, w)
        return "已记住：$text（重要度 $w）。以后我会一直记得。"
    }

    /** 按扩展名推断 MIME（覆盖常见文档 / 代码 / 图片类型，缺省 application/octet-stream）。 */
    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "txt" -> "text/plain"
            "md", "markdown" -> "text/markdown"
            "json" -> "application/json"
            "csv" -> "text/csv"
            "xml", "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js" -> "application/javascript"
            "py" -> "text/x-python"
            "kt" -> "text/x-kotlin"
            "java" -> "text/x-java-source"
            "c", "cpp", "h", "hpp" -> "text/x-c"
            "sh" -> "application/x-sh"
            "yaml", "yml" -> "application/x-yaml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }
    }

    /**
     * 极简安全算术求值器（递归下降）：仅支持 + - * / % ^、括号、小数与一元负号。
     * 拒绝任何其它字符，绝不执行任意代码，避免 eval 类注入风险。
     */
    private class ExprParser(s: String) {
        private val t = s.replace(" ", "")
        private var i = 0
        fun parse(): Double {
            val v = expr()
            if (i < t.length) throw IllegalArgumentException("无法识别的字符：${t[i]}")
            return v
        }
        private fun peek(): Char? = if (i < t.length) t[i] else null
        private fun expr(): Double {
            var v = term()
            while (peek() == '+' || peek() == '-') {
                val op = t[i++]
                v = if (op == '+') v + term() else v - term()
            }
            return v
        }
        private fun term(): Double {
            var v = factor()
            while (peek() == '*' || peek() == '/' || peek() == '%') {
                val op = t[i++]
                v = when (op) {
                    '*' -> v * factor()
                    '/' -> v / factor()
                    else -> v % factor()
                }
            }
            return v
        }
        private fun factor(): Double {
            if (peek() == '-') { i++; return -factor() }
            if (peek() == '+') { i++; return factor() }
            return power()
        }
        private fun power(): Double {
            val base = primary()
            if (peek() == '^') { i++; return Math.pow(base, factor()) }
            return base
        }
        private fun primary(): Double {
            if (peek() == '(') {
                i++
                val v = expr()
                if (peek() != ')') throw IllegalArgumentException("缺少右括号")
                i++
                return v
            }
            val start = i
            if (peek()?.isDigit() == true || peek() == '.') {
                while (i < t.length && (t[i].isDigit() || t[i] == '.')) i++
                return t.substring(start, i).toDouble()
            }
            throw IllegalArgumentException("非法字符：${peek()}")
        }
    }
}
