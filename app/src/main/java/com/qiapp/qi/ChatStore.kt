package com.qiapp.qi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 对话历史的本地持久化：保存文本消息（TextMsg）与语音回复气泡（VoiceMsg），
 * 写成 JSON 存于应用私有目录。每条记录带 type 字段区分类型，向后兼容旧文本记录。
 */
object ChatStore {

    private const val FILE = "chat.json"

    /** 把消息列表序列化为 JSON 字符串（含 type 区分）。 */
    fun serialize(msgs: List<Any>): String = try {
        val arr = JSONArray()
        msgs.forEach { m ->
            when (m) {
                is VoiceMsg -> arr.put(
                    JSONObject().put("type", "voice").put("text", m.text).put("dur", m.durSec)
                )
                is TextMsg -> {
                    val o = JSONObject().put("type", "text").put("text", m.text).put("me", m.me)
                    if (m.imagePath != null) o.put("img", m.imagePath)
                    arr.put(o)
                }
                is FileMsg -> arr.put(
                    JSONObject()
                        .put("type", "file")
                        .put("name", m.name)
                        .put("path", m.path)
                        .put("mime", m.mime)
                        .put("size", m.size)
                        .put("me", m.me)
                        .put("img", m.isImage)
                )
            }
        }
        arr.toString()
    } catch (_: Exception) { "[]" }

    /** 从 JSON 字符串反序列化；格式错误返回 null。 */
    fun deserialize(json: String): List<Any>? = try {
        val arr = JSONArray(json)
        val out = mutableListOf<Any>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            when (o.optString("type", "text")) {
                "voice" -> out.add(VoiceMsg(o.getString("text"), o.optInt("dur", 0)))
                "file" -> out.add(
                    FileMsg(
                        name = o.optString("name", "文件"),
                        path = o.optString("path", ""),
                        mime = o.optString("mime", "*/*"),
                        size = o.optLong("size", 0),
                        me = o.optBoolean("me", false),
                        isImage = o.optBoolean("img", false)
                    )
                )
                else -> out.add(
                    TextMsg(
                        o.getString("text"),
                        o.optBoolean("me", false),
                        o.optString("img", "").takeIf { it.isNotBlank() }
                    )
                )
            }
        }
        out
    } catch (_: Exception) { null }

    fun load(ctx: Context): List<Any>? {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return null
        return deserialize(f.readText())
    }

    fun save(ctx: Context, msgs: List<Any>) {
        try {
            File(ctx.filesDir, FILE).writeText(serialize(msgs))
        } catch (_: Exception) { /* 忽略写入失败 */ }
    }

    fun clear(ctx: Context) {
        File(ctx.filesDir, FILE).delete()
    }
}
