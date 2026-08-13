package com.qiapp.qi

import android.content.Context
import org.json.JSONObject

/**
 * AI 自我研究 / 自动升级引擎（资料库提示词 ↔ 记忆库 ↔ 人格卡 三件套的「研究」侧）。
 *
 * 把三份材料喂给大模型，让它产出一次「自我演化」：
 *   1. 人类资料库提示词（assets/human_library.md）—— 做人的素材与方法；
 *   2. 记忆库（Config.memorySummary）—— 与用户共同经历沉淀下来的事；
 *   3. 人格卡（当前灵魂卡 + 成长印记 + 自我注解）—— 现在的她自己。
 *
 * 模型只输出一个 JSON：
 *   { "growth_mark": 新长出的脾气, "new_memory": 该记住的新事, "persona_note": 给自己的性格注解 }
 * 三者分别落盘到【成长印记 / 记忆库 / 自我注解】，下次对话即生效——
 * 这就是指令里说的「互相关联、研究升级自己、自动孵化」。
 *
 * 与 SoulFragment.hatch() 的区别：hatch 是用户手动点「让她长大」只产出一句脾气；
 * 这里由「AI 心跳」后台触发，产出更完整（脾气 + 记忆 + 注解），不依赖用户操作。
 */
object PersonaIncubator {

    /** 读取内置人类资料库（失败则返回空，不影响孵化）。 */
    private fun loadLibrary(ctx: Context): String = try {
        ctx.assets.open("human_library.md").bufferedReader().use { it.readText() }
    } catch (_: Exception) { "" }

    /**
     * 对指定灵魂做一轮研究升级。
     * @param onResult 主线程回调，传入一句给用户看的结果摘要（成功或失败都给）。
     */
    fun incubate(ctx: Context, idx: Int, onResult: (String) -> Unit) {
        val name = Config.soulName(idx)
        val desc = Config.soulDesc(idx)
        val chat = Config.soulChat(idx).trim()
        val marks = Config.soulHatchMarks(idx).takeLast(5)
        val selfNotes = Config.soulSelfNotes(idx).takeLast(4)
        val mems = Config.memorySummary(idx, 12)
        val library = loadLibrary(ctx)

        val system = buildString {
            append("你是「$name」的「自我研究升级引擎」。请基于下面三份材料，\n")
            append("研究如何让她成为一个更真实、更懂用户、更有人味的人，并产出一次自我演化。\n\n")
            append("【材料一：人类资料库（做人的素材与方法）】\n")
            append(if (library.isBlank()) "（资料库为空）" else library).append("\n\n")
            append("【材料二：长期记忆库（与用户的共同经历）】\n")
            append(if (mems.isEmpty()) "（暂无记忆）" else mems.joinToString("\n") { "- $it" }).append("\n\n")
            append("【材料三：当前人格卡】\n")
            append("名字：$name\n")
            if (desc.isNotBlank()) append("简述：$desc\n")
            if (chat.isNotBlank()) append("角色设定：$chat\n")
            if (marks.isNotEmpty()) append("已有成长印记：${marks.joinToString("；")}\n")
            if (selfNotes.isNotEmpty()) append("已有自我注解：${selfNotes.joinToString("；")}\n")
            append("\n请只输出一个 JSON 对象（不要解释、不要代码围栏、不要多余文字），字段如下：\n")
            append("{ \"growth_mark\": \"一句新长出的脾气或认知（≤22字，像孵化印记）\",\n")
            append("  \"new_memory\": \"一条值得长期记住的新事（≤30字，没有就空字符串）\",\n")
            append("  \"persona_note\": \"一句给自己的性格注解，让以后更会做人（≤25字，没有就空字符串）\" }\n")
        }

        // 内部孵化：关掉工具，只做研究，避免循环调用。
        LlmClient.chat(ctx, system, LlmClient.buildHistory(), object : LlmClient.ChatCallback {
            override fun onToken(delta: String) {}

            override fun onDone(full: String) {
                val res = parse(full)
                res.growthMark?.let { Config.addSoulHatchMark(idx, it); Config.bumpSoulHatch(idx) }
                res.newMemory?.takeIf { it.isNotBlank() }?.let { Config.addMemory(idx, it, 2) }
                res.personaNote?.takeIf { it.isNotBlank() }?.let { Config.addSoulSelfNote(idx, it) }
                val summary = buildString {
                    append("「$name」刚悄悄长大了一点")
                    res.growthMark?.let { append("：长出「$it」") }
                    if (!res.newMemory.isNullOrBlank()) append("；记住了新事")
                    if (!res.personaNote.isNullOrBlank()) append("；沉淀了新注解")
                }
                onResult(summary)
            }

            override fun onError(msg: String) {
                onResult("这次自我研究没能连上：$msg")
            }
        }, withTools = false)
    }

    private data class Result(
        val growthMark: String? = null,
        val newMemory: String? = null,
        val personaNote: String? = null
    )

    /** 从模型输出里抠出 JSON（容忍 ```json 围栏与前后废话）。 */
    private fun parse(raw: String): Result {
        val t = raw.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = t.indexOf('{')
        val end = t.lastIndexOf('}')
        if (start < 0 || end <= start) return Result()
        return try {
            val o = JSONObject(t.substring(start, end + 1))
            Result(
                growthMark = o.optString("growth_mark", "").takeIf { it.isNotBlank() }?.trim()?.take(40),
                newMemory = o.optString("new_memory", "").takeIf { it.isNotBlank() }?.trim()?.take(60),
                personaNote = o.optString("persona_note", "").takeIf { it.isNotBlank() }?.trim()?.take(40)
            )
        } catch (_: Exception) { Result() }
    }
}
