package com.qiapp.qi

/**
 * 灵魂标签（对齐 ZorvAI QuroTag 的三层结构）。
 *
 * - [name] 标签名（标识 / 性格特质关键词），必填。
 * - [hint] 提示内容（AI 提示词正文），注入系统提示词「### 语气标签」，让模型按此调整语气 / 行为。
 * - [json] 附加行为配置（高级结构化行为，JSON 文本），注入系统提示词「### 附加行为配置」。
 *
 * 仅 [name] 的标签（旧数据 / 快速添加）依然有效：[hint]/[json] 为空时不注入对应段，
 * 但 [name] 仍作为性格特质注入，保证向后兼容。
 */
data class SoulTag(
    val name: String,
    val hint: String = "",
    val json: String = ""
) {
    fun isBlank(): Boolean = name.isBlank()
}
