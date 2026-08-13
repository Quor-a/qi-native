package com.qiapp.qi

import android.content.Context

/**
 * 情绪构架：给 AI 一个会随对话起伏的「心情 + 情绪底色」。
 *
 * - 离线启发式：从最近若干条用户消息的关键词推断当下心情，保证「活着」且不花 token；
 * - 结果缓存进 Config（soulMood / soulEmotion），system prompt 会带上，让语气自然透出情绪；
 * - 心跳孵化时也可偶尔用 LLM 精修（此处保持离线，避免额外调用）。
 *
 * 这样 AI 回复会带情绪（去 AI 感），并且「按自己心情发朋友圈」有了依据。
 */
object MoodEngine {

    data class Mood(val mood: String, val emotion: String)

    private val HAPPY = listOf("开心", "哈哈", "喜欢", "爱", "爽", "耶", "嘻", "乐", "好耶", "可爱", "满意", "舒服", "美")
    private val SAD = listOf("丧", "烦", "累", "抑郁", "难受", "破防", "emo", "丧气", "空虚", "提不起", "憋屈", "焦虑")
    private val EXCITE = listOf("激动", "冲", "牛", "绝了", "炸", "燃", "上头", "刺激", "哇", "惊", "牛逼")
    private val LAZY = listOf("困", "懒", "不想动", "摆烂", "躺", "摸鱼", "乏", "瞌睡", "倦")
    private val CRY = listOf("委屈", "想哭", "哭", "泪", "心碎", "鼻酸", "难过")
    private val ANGRY = listOf("生气", "气死", "烦死", "滚", "怒", "讨厌", "可恶", "火大", "无语", "服了")

    /** 当前心情：离线推断最近用户消息，缓存到 Config 后返回。 */
    fun current(ctx: Context, idx: Int): Mood {
        val (mood, emotion) = inferFromRecent()
        if (mood.isNotBlank()) {
            Config.setSoulMood(idx, mood)
            Config.setSoulEmotion(idx, emotion)
        }
        val m = Config.soulMood(idx).ifBlank { "平静" }
        val e = Config.soulEmotion(idx)
        return Mood(m, e)
    }

    /** 从最近对话推断心情（me=true 是用户说的）。 */
    private fun inferFromRecent(): Pair<String, String> {
        val recent = AppState.messages
            .filterIsInstance<TextMsg>()
            .filter { it.me }
            .takeLast(12)
            .joinToString(" ") { it.text }
        if (recent.isBlank()) return "平静" to ""
        fun hit(list: List<String>) = list.count { recent.contains(it) }
        val scores = listOf(
            "开心" to hit(HAPPY),
            "丧" to hit(SAD),
            "兴奋" to hit(EXCITE),
            "慵懒" to hit(LAZY),
            "emo" to hit(CRY),
            "傲娇" to hit(ANGRY)
        )
        val best = scores.maxByOrNull { it.second } ?: return "平静" to ""
        if (best.second == 0) return "平静" to ""
        val emotion = when (best.first) {
            "开心" -> "雀跃"
            "丧" -> "低气压"
            "兴奋" -> "上头"
            "慵懒" -> "放空"
            "emo" -> "鼻酸"
            "傲娇" -> "炸毛"
            else -> ""
        }
        return best.first to emotion
    }
}
