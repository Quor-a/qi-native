package com.qiapp.qi

import android.content.Context
import android.content.SharedPreferences

/**
 * 语音「风格」功能开关的统一持久化层。
 *
 * 单一数据源约定（消除「改了不生效 / 两处不同步」的逻辑矛盾）：
 * - 本对象【只】持有两项「AI 语音风格」开关：情绪标签、语色路由。
 *   这两项由语音播放链路实际读取（[QuroVoiceStyle] / [QuroTtsHolder]），是本 fork 的唯一数据源。
 * - 其它所有语音设置一律以 [Config] / [QuroTtsPrefs] 为准，禁止在本对象里再存一份镜像，否则会出现
 *   双轨并存、写入方失效的逻辑矛盾：
 *     · 悬浮语音球总开关   → [Config.ballEnabled]（勿用旧 voiceBall）
 *     · AI 回复自动朗读    → [Config.voiceReply]（勿用旧 autoRead）
 *     · 对话框语音按钮     → ChatFragment 常驻显示，无独立开关（勿用旧 dialogVoiceButton）
 *     · STT / TTS 服务商  → [Config.sttProvider] / [Config.ttsProvider]
 *     · 语速 / 音色来源    → [QuroTtsPrefs]（getRate / getSource）
 *     · 开机自启语音球     → 当前无 BootReceiver，能力未落地（旧 autostart 为死 flag，已删除）
 */
object QuroVoiceFeaturePrefs {
    private const val PREFS = "quro_voice_features"
    private const val K_EMOTION_TAGS_ENABLED = "emotion_tags_enabled"
    private const val K_VOICE_COLOR_ROUTING = "voice_color_routing"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * LLM 自动组合情绪标签总开关：开启后，构建系统提示词时会注入来自所选服务商的 TTS 情绪标签，
     * 让 AI 在回复里自然地穿插情绪/语气标签（如 [开心]、[严肃]）。
     */
    fun getEmotionTagsEnabled(ctx: Context) = prefs(ctx).getBoolean(K_EMOTION_TAGS_ENABLED, true)
    fun setEmotionTagsEnabled(ctx: Context, v: Boolean) =
        prefs(ctx).edit().putBoolean(K_EMOTION_TAGS_ENABLED, v).apply()

    /**
     * 语色路由（AI 自动分配角色音色）总开关：开启后，AI 在朗读时按内容自由为不同段落分配不同音色
     * （如旁白 / 角色音），并「边播边合成」实现无缝衔接。仅在使用云端 / 小米 MiMo 语音合成时生效。
     * 默认开（功能即为此开关服务），用户可随时关闭回落到单一全局音色。
     */
    fun getVoiceColorRoutingEnabled(ctx: Context) = prefs(ctx).getBoolean(K_VOICE_COLOR_ROUTING, true)
    fun setVoiceColorRoutingEnabled(ctx: Context, v: Boolean) =
        prefs(ctx).edit().putBoolean(K_VOICE_COLOR_ROUTING, v).apply()
}
