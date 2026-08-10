package com.qiapp.qi

import android.content.Context

/**
 * 栖（qi）本地适配的「当前激活模型配置」。
 *
 * 完整服务商注册表见同包 [ApiProviderConfigs]（逐字移植自 ZorvAI / QuroAI 的 core.model.ApiProviderConfigs，去品牌化）。
 *
 * 本数据类字段对齐 ZorvAI / QuroAI 的 core.model.QuroModelConfig（去品牌化）：provider / baseUrl / apiKey /
 * model / temperature / maxTokens / enableTools / maxToolRounds / contextWindow / customProviderName /
 * localModelPath / useFullTools / skillToolsEnabled / maxSkillTools / 本地离线独立字段等。
 *
 * 本工程（栖）的「单一数据源」是 [Config]（SharedPreferences "qi_cfg"，与聊天 / TTS / STT 共用）；
 * 因此本数据类是 [Config] 的**视图/桥接**：[QuroModelConfigRepository.load] 从 [Config] 读取当前激活配置，
 * 不另起存储，避免双份配置漂移。STT 云端转写直接复用该配置，无需独立维护。
 * 本地离线相关字段（localTemperature / localMaxTokens / localEnableTools）沿用 ZorvAI / QuroAI 默认值，
 * 与云端隔离，互不影响（改离线不会把云端带歪，与上游一致）。
 */
data class QuroModelConfig(
    val provider: String = "OPENAI",          // 服务商标识（对应 [ApiProviderType] 枚举名或 [Config.providerLabel]）
    val baseUrl: String = "",                 // API 基址（不含 /chat/completions）
    val apiKey: String = "",
    val model: String = "",
    val temperature: Float = 0.7f,
    val maxTokens: Int = 4096,
    val enableTools: Boolean = true,
    val maxToolRounds: Int = 0,               // 工具调用轮次上限：0=不限制（内置 200 轮安全天花板）
    val contextWindow: Int = 16000,           // 上下文窗口（输入 token 预算）：0=不限制
    val customProviderName: String = "",      // 自定义厂商展示名（provider=="OTHER" 时有效）
    val localModelPath: String = "",          // 本地离线模型路径（provider 为 MNN/LLAMA_CPP 时有效）
    val useFullTools: Boolean = true,         // 完整工具集开关：默认开启
    val skillToolsEnabled: Boolean = true,    // 技能可调用（function calling）总开关
    val maxSkillTools: Int = 16,              // 最多下发的技能工具数量
    // ---- 本地离线模型独立设置（与云端隔离）----
    val localTemperature: Float = 0.7f,
    val localMaxTokens: Int = 2048,
    val localEnableTools: Boolean = true,
)

/**
 * 激活模型配置仓库（桥接到 [Config] 单一数据源）。
 * STT 云端转写直接复用该配置，无需独立维护。
 */
object QuroModelConfigRepository {
    /**
     * 从 [Config] 当前激活配置构建 [QuroModelConfig]。
     * model 优先取聊天模型名，回退到 STT 模型名（与上游 QuroModelConfig 的「模型配置即共享端点 + Key」一致）。
     */
    fun load(ctx: Context): QuroModelConfig {
        val ep = Config.endpoint.trim().trimEnd('/')
        return QuroModelConfig(
            provider = Config.providerLabel(),
            baseUrl = ep,
            apiKey = Config.apiKey,
            model = Config.model.ifBlank { Config.sttModel.ifBlank { "whisper-1" } },
            temperature = Config.temperature,
            // 其余高级字段（maxTokens / enableTools / contextWindow / 本地离线等）沿用默认值：
            // 栖当前未在 Config 中持久化这些项，保持与 ZorvAI / QuroAI 默认一致即可。
        )
    }

    /** 把当前激活配置转成可保存模板（动态模板），对齐 [Config.toProfile]。 */
    fun toProfile(name: String): SavedProfile = Config.toProfile(name)
}
