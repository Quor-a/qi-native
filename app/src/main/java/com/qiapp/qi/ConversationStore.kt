package com.qiapp.qi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 多会话历史对话持久化：所有会话存于应用私有目录 conversations.json。
 * 兼容旧版单文件 chat.json（首次启动自动迁移为首个会话，迁移后删除旧文件）。
 * 单条消息的序列化复用 ChatStore（TextMsg / VoiceMsg 同一套格式）。
 *
 * 数据结构（conversations.json）：
 *   [ { "id": "...", "title": "...", "updatedAt": 169..., "messages": "[...ChatStore 序列化...]" } ]
 */
object ConversationStore {

    private const val FILE = "conversations.json"
    private const val LEGACY = "chat.json"

    /** 全部会话（按更新时间倒序）。 */
    fun list(ctx: Context): List<Conversation> = loadAll(ctx)

    fun get(ctx: Context, id: String): Conversation? = loadAll(ctx).firstOrNull { it.id == id }

    /** 新建空白会话（默认标题「新对话」），写入并返回。 */
    fun create(ctx: Context, title: String = "新对话"): Conversation {
        val all = loadAll(ctx).toMutableList()
        val conv = Conversation(newId(), title, System.currentTimeMillis(), mutableListOf())
        all.add(conv)
        saveAll(ctx, all)
        return conv
    }

    /** 写入 / 更新一个会话（按 id 存在则替换），并刷新 updatedAt。 */
    fun upsert(ctx: Context, conv: Conversation) {
        val all = loadAll(ctx).toMutableList()
        val idx = all.indexOfFirst { it.id == conv.id }
        conv.updatedAt = System.currentTimeMillis()
        if (idx >= 0) all[idx] = conv else all.add(conv)
        saveAll(ctx, all)
    }

    fun delete(ctx: Context, id: String) {
        val all = loadAll(ctx).toMutableList()
        all.removeAll { it.id == id }
        saveAll(ctx, all)
    }

    fun clearAll(ctx: Context) {
        File(ctx.filesDir, FILE).delete()
    }

    // ---------------- 内部实现 ----------------

    private fun newId(): String =
        "c_${System.currentTimeMillis()}_${(Math.random() * 1000).toInt()}"

    private fun loadAll(ctx: Context): List<Conversation> {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) {
            migrateLegacy(ctx)?.let { migrated ->
                val all = mutableListOf(migrated)
                saveAll(ctx, all)
                return all
            }
            return emptyList()
        }
        return try {
            val arr = JSONArray(f.readText())
            val out = mutableListOf<Conversation>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("id", newId())
                val title = o.optString("title", "新对话")
                val updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
                val msgsJson = o.optString("messages", "[]")
                val msgs = ChatStore.deserialize(msgsJson)?.toMutableList() ?: mutableListOf()
                out.add(Conversation(id, title, updatedAt, msgs))
            }
            out.sortedByDescending { it.updatedAt }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveAll(ctx: Context, list: List<Conversation>) {
        try {
            val arr = JSONArray()
            list.forEach { c ->
                arr.put(
                    JSONObject()
                        .put("id", c.id)
                        .put("title", c.title)
                        .put("updatedAt", c.updatedAt)
                        .put("messages", ChatStore.serialize(c.messages))
                )
            }
            File(ctx.filesDir, FILE).writeText(arr.toString())
        } catch (_: Exception) { /* 忽略写入失败 */ }
    }

    /**
     * 旧版 chat.json → 首个会话；返回迁移后的会话。
     * 迁移成功后删除旧文件，避免下次又迁移出重复会话。
     */
    private fun migrateLegacy(ctx: Context): Conversation? {
        val legacy = File(ctx.filesDir, LEGACY)
        if (!legacy.exists()) return null
        val msgs = ChatStore.deserialize(legacy.readText())?.toMutableList() ?: return null
        val title = deriveTitle(msgs)
        legacy.delete()
        return Conversation(newId(), title, System.currentTimeMillis(), msgs)
    }

    /** 从首条用户消息派生标题；无则回退「新对话」。 */
    private fun deriveTitle(msgs: List<Any>): String {
        val firstUser = msgs.firstOrNull { it is TextMsg && it.me } as? TextMsg
        val raw = firstUser?.text?.trim().orEmpty()
        return if (raw.isBlank()) "新对话" else raw.take(20)
    }
}

/** 单个会话：id 稳定唯一，messages 为可变的消息列表（TextMsg / VoiceMsg / TypingMsg）。 */
data class Conversation(
    val id: String,
    var title: String,
    var updatedAt: Long,
    val messages: MutableList<Any>
)
