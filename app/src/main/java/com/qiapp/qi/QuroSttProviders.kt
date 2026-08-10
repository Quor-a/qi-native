package com.qiapp.qi

/**
 * 云端 STT（语音识别）服务商目录。
 *
 * 栖的云端 STT 统一走 OpenAI 兼容的 /audio/transcriptions（Whisper），
 * 与 ZorvAI 的 STT 服务商目录对齐：每个服务商定义默认端点、必填字段、默认模型。
 * 用户选定具体服务商后，端点 / Key / 模型落到 [QuroSttProviderPrefs] 按服务商独立保存，
 * 引擎 [QuroSttRecorder] 在云端分支读取对应配置发起转写。
 *
 * 原生（设备 SpeechRecognizer）识别不在此目录，由「系统原生识别」选项单独处理。
 */
data class QuroSttProviderDef(
    val id: String,
    val name: String,
    val desc: String,
    val defaultBaseUrl: String,
    val fields: List<QuroTtsField>,   // 复用 TTS 的字段定义（api_key / base_url / model 等）
    val defaultModel: String,
    val requiredFields: List<String> = emptyList(),
)

object QuroSttProviders {

    // ─────────────────────────── OpenAI Whisper ───────────────────────────
    private val OPENAI: QuroSttProviderDef = QuroSttProviderDef(
        id = "openai", name = "OpenAI Whisper",
        desc = "OpenAI 官方 /audio/transcriptions。可复用「模型配置」共享端点 + API Key（与聊天同一套）。",
        defaultBaseUrl = "https://api.openai.com/v1",
        fields = listOf(
            QuroTtsField("api_key", "API Key", "sk-...", secret = true),
            QuroTtsField("base_url", "Base URL", "https://api.openai.com/v1"),
            QuroTtsField("model", "模型", "whisper-1"),
        ),
        defaultModel = "whisper-1", requiredFields = listOf("api_key"),
    )

    // ─────────────────────────── 硅基流动 ───────────────────────────
    private val SILICONFLOW: QuroSttProviderDef = QuroSttProviderDef(
        id = "siliconflow", name = "硅基流动 Whisper",
        desc = "SiliconFlow 兼容 OpenAI /audio/transcriptions，内置 SenseVoice / Paraformer 等。",
        defaultBaseUrl = "https://api.siliconflow.cn/v1",
        fields = listOf(
            QuroTtsField("api_key", "API Key", "", secret = true),
            QuroTtsField("base_url", "Base URL", "https://api.siliconflow.cn/v1"),
            QuroTtsField("model", "模型", "whisper-1"),
        ),
        defaultModel = "whisper-1", requiredFields = listOf("api_key"),
    )

    // ─────────────────────────── 阿里百炼 DashScope ───────────────────────────
    private val ALIYUN: QuroSttProviderDef = QuroSttProviderDef(
        id = "aliyun", name = "阿里百炼 DashScope",
        desc = "DashScope 兼容 OpenAI /audio/transcriptions（whisper / paraformer 系列）。",
        defaultBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        fields = listOf(
            QuroTtsField("api_key", "DashScope API Key", "", secret = true),
            QuroTtsField("base_url", "Base URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
            QuroTtsField("model", "模型", "whisper-1"),
        ),
        defaultModel = "whisper-1", requiredFields = listOf("api_key"),
    )

    // ─────────────────────────── TTS302AI ───────────────────────────
    private val TTS302: QuroSttProviderDef = QuroSttProviderDef(
        id = "tts302", name = "TTS302AI Whisper",
        desc = "TTS302AI 兼容 OpenAI /audio/transcriptions。",
        defaultBaseUrl = "https://api.tts302.ai/v1",
        fields = listOf(
            QuroTtsField("api_key", "API Key", "", secret = true),
            QuroTtsField("base_url", "Base URL", "https://api.tts302.ai/v1"),
            QuroTtsField("model", "模型", "whisper-1"),
        ),
        defaultModel = "whisper-1", requiredFields = listOf("api_key"),
    )

    // ─────────────────────────── CozeCn ───────────────────────────
    private val COZECN: QuroSttProviderDef = QuroSttProviderDef(
        id = "cozecn", name = "CozeCn Whisper",
        desc = "Coze 国内版兼容 OpenAI /audio/transcriptions。",
        defaultBaseUrl = "https://api.coze.cn/v1",
        fields = listOf(
            QuroTtsField("api_key", "API Key / PAT", "", secret = true),
            QuroTtsField("base_url", "Base URL", "https://api.coze.cn/v1"),
            QuroTtsField("model", "模型", "whisper-1"),
        ),
        defaultModel = "whisper-1", requiredFields = listOf("api_key"),
    )

    // ─────────────────────────── 自托管网关（Gizwits / ACGN 形态） ───────────────────────────
    private val GIZWITS: QuroSttProviderDef = QuroSttProviderDef(
        id = "gizwits", name = "自托管网关 A",
        desc = "兼容 OpenAI /audio/transcriptions 的自托管 / 第三方网关，需手动填写端点。",
        defaultBaseUrl = "",
        fields = listOf(
            QuroTtsField("api_key", "API Key", "", secret = true),
            QuroTtsField("base_url", "Base URL", "https://your-gateway/v1"),
            QuroTtsField("model", "模型", "whisper-1"),
        ),
        defaultModel = "whisper-1", requiredFields = listOf("api_key", "base_url"),
    )

    private val ACGN: QuroSttProviderDef = QuroSttProviderDef(
        id = "acgn", name = "自托管网关 B",
        desc = "兼容 OpenAI /audio/transcriptions 的自托管 / 第三方网关，需手动填写端点。",
        defaultBaseUrl = "",
        fields = listOf(
            QuroTtsField("api_key", "API Key", "", secret = true),
            QuroTtsField("base_url", "Base URL", "https://your-gateway/v1"),
            QuroTtsField("model", "模型", "whisper-1"),
        ),
        defaultModel = "whisper-1", requiredFields = listOf("api_key", "base_url"),
    )

    val ALL: List<QuroSttProviderDef> = listOf(
        OPENAI, SILICONFLOW, ALIYUN, TTS302, COZECN, GIZWITS, ACGN,
    )

    fun byId(id: String) = ALL.firstOrNull { it.id == id }
}
