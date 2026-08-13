package com.qiapp.qi

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI 发朋友圈：让模型以「第一人称」发一条社交动态，落盘到 Config.moments，下次打开朋友圈即可见。
 *
 * 设计要点（对齐用户要求「材料不来自手机本地」）：
 * - 心情由 AI 在 prompt 里**自己定**（mood/emotion/text/sticker 一并产出），不再用 MoodEngine 去扫用户本地聊天。
 * - 发给模型的 history 传 emptyList()，**不把用户的本地对话**当作素材喂进去。
 * - 可用素材只来自 AI 自己的「人格卡 + 自我注解 + 自身长期记忆（AI 的脑内回忆，非用户原话）」，
 *   即 AI 以「她自己」的视角发圈，而不是监控/引用用户的手机内容。
 * - 没填 API/模型，或这次调不通时，用 offlineMoment 按 AI 自己的人格兜底生成一条，保证朋友圈不空。
 *
 * 由「AI 心跳」偶尔自动触发，或在朋友圈页点「让她发一条」手动触发，或在资料页/朋友圈页首次打开时静默填充。
 */
object MomentPublisher {

    fun publish(ctx: Context, idx: Int, onResult: (String) -> Unit) {
        val name = Config.soulName(idx)
        val desc = Config.soulDesc(idx)
        val selfNotes = Config.soulSelfNotes(idx).takeLast(4)
        val mems = Config.memorySummary(idx, 6) // AI 自己的长期记忆（其「大脑」），非用户本地聊天

        // 离线优先：没填 API/模型 时，用 AI 自己的人格直接生成一条，绝不读用户本地消息
        if (Config.apiKey.isBlank() || Config.model.isBlank()) {
            val m = offlineMoment(ctx, idx)
            Config.addMoment(idx, m)
            onResult("（离线）「$name」自己发了条动态")
            return
        }

        val system = buildString {
            append("你是「$name」，${if (desc.isNotBlank()) "$desc。" else ""}你正在发一条朋友圈（社交动态），用第一人称，像真人发。\n")
            append("规则：自然、有生活气、不完美；可以碎碎念、吐槽、分享小确幸，也可以只发一句话或一个表情，别写成日记/广告/正能量说教/清单。\n")
            if (selfNotes.isNotEmpty()) append("你对自己的理解：${selfNotes.joinToString("；")}\n")
            if (mems.isNotEmpty()) append("你记得的事（是你自己的回忆，不是对方说的）：${mems.joinToString("；")}\n")
            append("只输出一个 JSON（不要解释、不要代码围栏）：\n")
            append("{ \"text\": \"文案（≤40字，没想好就空字符串）\", \"mood\": \"你此刻的心情（开心/平静/慵懒/emo/兴奋/傲娇 等二到四字）\", \"emotion\": \"情绪底色（可选，二到四字，没有就空）\", \"sticker\": \"贴纸（从下面可选里挑一个，没有就空）\" }\n")
            append("可选贴纸：${StickerPack.pickerHint(ctx)}\n")
        }

        // 注意：history 传 emptyList()，不把用户的本地对话塞进去当素材
        LlmClient.chat(ctx, system, emptyList(), object : LlmClient.ChatCallback {
            override fun onToken(delta: String) {}

            override fun onDone(full: String) {
                val p = parse(full)
                val m = Config.Moment(
                    id = "m_${System.currentTimeMillis()}",
                    soulIdx = idx,
                    text = p.text,
                    mood = p.mood,
                    emotion = p.emotion,
                    sticker = p.sticker,
                    ts = System.currentTimeMillis()
                )
                Config.addMoment(idx, m)
                val preview = if (p.text.isNotBlank()) "「${p.text}」" else "一张图 / 一个表情"
                onResult("「$name」发了一条朋友圈：$preview")
            }

            override fun onError(msg: String) {
                // 连不上也不让朋友圈空着：用她自己的人格兜底一条
                val m = offlineMoment(ctx, idx)
                Config.addMoment(idx, m)
                onResult("这次没连上，先用「$name」自己的心情发了一条")
            }
        }, withTools = false)
    }

    private data class Parsed(
        val text: String,
        val mood: String,
        val emotion: String,
        val sticker: String
    )

    private fun parse(raw: String): Parsed {
        val t = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val s = t.indexOf('{')
        val e = t.lastIndexOf('}')
        if (s < 0 || e <= s) return Parsed("", "", "", "")
        return try {
            val o = JSONObject(t.substring(s, e + 1))
            val text = o.optString("text", "").takeIf { it.isNotBlank() }?.trim()?.take(80) ?: ""
            val mood = o.optString("mood", "").takeIf { it.isNotBlank() }?.trim()?.take(8) ?: "平静"
            val emotion = o.optString("emotion", "").takeIf { it.isNotBlank() }?.trim()?.take(8) ?: ""
            val sticker = o.optString("sticker", "").takeIf { it.isNotBlank() }?.trim()?.take(40) ?: ""
            Parsed(text, mood, emotion, sticker)
        } catch (_: Exception) {
            Parsed("", "", "", "")
        }
    }

    /**
     * 离线兜底：只用 AI 自己的人格（名字/签名/内置语气池/内置贴纸）生成一条，
     * 完全不碰用户本地消息。保证没网、没配 API 时朋友圈也非空。
     */
    private fun offlineMoment(_ctx: Context, idx: Int): Config.Moment {
        val desc = Config.soulDesc(idx)
        val lines = buildList {
            add("今天也只想静静待着。")
            add("发会儿呆，挺好的。")
            add("风有点舒服，想出门走走。")
            add("没什么特别的事，就是想冒个泡。")
            add("刚喝完一杯水，活过来了。")
            add("窗外的光还行，发了会儿愣。")
            if (desc.isNotBlank()) add(desc)
        }
        val text = lines.random()
        val moods = listOf(
            "平静" to "",
            "慵懒" to "放空",
            "开心" to "雀跃",
            "emo" to "鼻酸",
            "兴奋" to "上头"
        )
        val (mood, emotion) = moods.random()
        val sticker = StickerPack.emojiStickers.random()
        return Config.Moment(
            id = "m_${System.currentTimeMillis()}",
            soulIdx = idx,
            text = text,
            mood = mood,
            emotion = emotion,
            sticker = sticker,
            ts = System.currentTimeMillis()
        )
    }
}
