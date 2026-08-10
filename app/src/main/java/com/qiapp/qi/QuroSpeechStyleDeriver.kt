package com.qiapp.qi

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 语音风格自动推导器（栖移植版）。
 *
 * 原 ZorvAI 实现含「LLM 轻量推断」分支（依赖聊天模型做情绪/语速/语气推导）。
 * 栖当前未接入该 LLM 风格栈，故仅保留：
 *   1) 人格卡 voiceSetting 基调（栖无人格卡 -> 恒为空串）；
 *   2) 关键词 + 标点启发式兜底（离线可用，永不因网络变哑）。
 * 完整标记（如 (开心)）由 [QuroVoiceStyle] 在下游按服务商处理。
 */
object QuroSpeechStyleDeriver {
    private const val TAG = "QuroSpeechStyle"

    suspend fun deriveStyle(ctx: Context, text: String): String = withContext(Dispatchers.IO) {
        val base = runCatching { QuroPersonaRepository(ctx).getActive().voiceSetting }.getOrNull().orEmpty().trim()
        val clean = QuroVoiceStyle.strip(text).trim()
        if (clean.isEmpty()) return@withContext base
        val h = heuristic(clean)
        return@withContext if (h.isBlank()) base else (if (base.isBlank()) h else "$base，$h")
    }

    private fun combine(base: String, style: String): String {
        val s = style.trim()
        if (s.isEmpty()) return base
        return if (base.isBlank()) s else "$base，$s"
    }

    private fun heuristic(text: String): String {
        val rules = listOf(
            Regex("哈哈|嘻嘻|嘿嘿|耶|太棒了|好开心|太喜欢|开心|高兴|喜欢|可爱|赞|棒") to "开心活泼，语速稍快，带笑意",
            Regex("呜呜|呜咽|抽泣|伤心|难过|遗憾|可惜|失落|想哭|心碎") to "悲伤温柔，语速偏慢，带鼻音",
            Regex("气死|可恶|混蛋|愤怒|讨厌|烦死|岂有此理|受不了|滚") to "愤怒急切，语速快，声音略带颤抖",
            Regex("害怕|恐惧|吓人|恐怖|担心|紧张|不安|发抖") to "紧张不安，语速偏快，声音微微发颤",
            Regex("恭喜|庆祝|好消息|太好了|胜利|成功|毕业|中奖") to "兴奋喜悦，语速稍快，充满活力",
            Regex("抱歉|对不起|愧疚|忘了|失误|不好意思|遗憾地") to "愧疚温和，语速偏慢，语气放软",
            Regex("警告|注意|危险|小心|务必|必须|记住|严禁") to "严肃郑重，语速沉稳，语气坚定",
            Regex("累|疲惫|困|想睡|休息|安静|沉默|……|缓缓") to "慵懒平静，语速放慢，气息轻柔",
            Regex("好奇|为什么|怎么|吗？|如何|什么情况|咋") to "好奇疑惑，语速中等，尾音上扬",
            Regex("爱你|喜欢你|想你|抱抱|亲亲|亲爱") to "温柔甜蜜，语速稍慢，带笑意",
            Regex("急|快一点|赶紧|马上|立刻|来不及") to "急切紧迫，语速快，语气紧凑",
            Regex("慢慢|不急|从容|淡定|冷静") to "从容舒缓，语速放慢，语气平和",
        )
        for ((re, hint) in rules) {
            if (re.containsMatchIn(text)) return hint
        }
        val excl = text.count { it == '！' || it == '!' }
        val q = text.count { it == '？' || it == '?' }
        return when {
            excl >= 2 -> "情绪激动，语速稍快"
            q >= 2 -> "好奇疑惑，语速中等，尾音上扬"
            text.length > 80 -> "平缓叙述，语速中等，自然流畅"
            else -> ""
        }
    }
}
