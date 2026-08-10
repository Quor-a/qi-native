package com.qiapp.qi

/**
 * 轻量离线情感分析：基于中文情感词典 + 关键词命中，输出 6 类情绪之一。
 * 用于驱动「动态形象」的表情与背景，无需联网、无第三方依赖。
 *
 * 情绪枚举同时携带 key（持久化/总线传递用）与中文 label（UI 展示用）。
 */
object EmotionAnalyzer {

    enum class Emotion(val key: String, val label: String) {
        NEUTRAL("neutral", "平静"),
        HAPPY("happy", "开心"),
        SAD("sad", "难过"),
        ANGRY("angry", "生气"),
        SURPRISED("surprised", "惊讶"),
        CALM("calm", "温柔");

        companion object {
            fun fromKey(k: String?): Emotion = values().firstOrNull { it.key == k } ?: NEUTRAL
        }
    }

    // 正向词：命中 +2
    private val POS = setOf(
        "开心", "快乐", "高兴", "喜欢", "爱", "哈哈", "嘻嘻", "好呀", "棒", "赞", "谢谢",
        "满意", "幸福", "期待", "可爱", "温柔", "拥抱", "加油", "好呢", "耶", "美", "好哦",
        "❤", "♥", "😊", "😄", "😍", "🌟", "✨"
    )
    // 负向词：命中 -2
    private val NEG = setOf(
        "难过", "伤心", "哭", "讨厌", "烦", "生气", "气死", "痛", "累", "怕", "担心",
        "焦虑", "无聊", "孤单", "寂寞", "委屈", "失望", "崩溃", "呜呜", "烦人", "可恶",
        "😢", "😭", "💔"
    )
    // 惊讶词：直接判惊喜
    private val SURP = setOf(
        "哇", "天哪", "天啊", "居然", "竟然", "没想到", "什么", "惊", "不料", "不会吧",
        "？", "?", "！", "!", "😲", "😮"
    )
    // 温柔/安抚词：直接判温柔
    private val CALM = setOf(
        "慢慢", "安静", "陪", "发呆", "静静", "听", "懂你", "抱", "别怕", "我在", "放心", "睡"
    )
    // 生气词：直接判生气
    private val ANGRY = setOf("气死", "可恶", "讨厌你", "烦人", "滚", "混蛋", "岂有此理")

    /**
     * 分析文本情绪。规则优先级：生气 > 惊讶 > 温柔 > 正负分。
     * 返回 [Emotion]，调用方据此驱动表情与背景。
     */
    fun analyze(text: String): Emotion {
        if (text.isBlank()) return Emotion.NEUTRAL
        val t = text.lowercase()
        for (w in ANGRY) if (t.contains(w)) return Emotion.ANGRY
        for (w in SURP) if (t.contains(w)) return Emotion.SURPRISED
        for (w in CALM) if (t.contains(w)) return Emotion.CALM
        var score = 0
        for (w in POS) if (t.contains(w)) score += 2
        for (w in NEG) if (t.contains(w)) score -= 2
        return when {
            score > 0 -> Emotion.HAPPY
            score < 0 -> Emotion.SAD
            else -> Emotion.NEUTRAL
        }
    }
}
