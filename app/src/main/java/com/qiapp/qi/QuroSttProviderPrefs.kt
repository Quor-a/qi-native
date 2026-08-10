package com.qiapp.qi

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * 云端 STT 服务商配置持久化（镜像 [QuroTtsProviderPrefs]）。
 *
 * 按服务商 id 独立保存「字段（api_key / base_url / model…）+ 模型名」。
 * 引擎在云端转写分支通过 [getConfig] 读取当前选中服务商的端点 / Key / 模型；
 * 对 "openai" 未单独配置时回落到「模型配置」共享端点 + API Key（与聊天同一套）。
 */
data class QuroSttProviderConfig(
    val fields: Map<String, String> = emptyMap(),
    val model: String = "",
)

object QuroSttProviderPrefs {
    private const val PREFS = "quro_stt_providers"
    private const val KEY_PROVIDER = "provider"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getProvider(ctx: Context): String = prefs(ctx).getString(KEY_PROVIDER, "openai") ?: "openai"
    fun setProvider(ctx: Context, id: String) = prefs(ctx).edit().putString(KEY_PROVIDER, id).apply()

    private fun cfgKey(id: String) = "cfg_$id"

    fun getConfig(ctx: Context, id: String): QuroSttProviderConfig {
        val def = QuroSttProviders.byId(id)
        val cfg = runCatching {
            val jo = JSONObject(prefs(ctx).getString(cfgKey(id), "{}") ?: "{}")
            val fields = mutableMapOf<String, String>()
            jo.optJSONObject("fields")?.let { o -> o.keys().forEach { fields[it] = o.optString(it, "") } }
            QuroSttProviderConfig(
                fields = fields,
                model = jo.optString("model", def?.defaultModel ?: "whisper-1").ifBlank { def?.defaultModel ?: "whisper-1" },
            )
        }.getOrDefault(QuroSttProviderConfig(model = def?.defaultModel ?: "whisper-1"))

        return cfg.let {
            if (id == "openai") {
                // 栖桥接：未单独配置 openai 服务商时，回落到「模型配置」共享端点 + API Key（与聊天同一套）
                val ak = (it.fields["api_key"] ?: "").ifBlank { Config.apiKey }
                val bu = (it.fields["base_url"] ?: "").ifBlank { Config.endpoint.trim().trimEnd('/') }
                val fields = it.fields.toMutableMap().apply {
                    put("api_key", ak)
                    put("base_url", bu)
                }
                it.copy(fields = fields)
            } else it
        }
    }

    fun saveConfig(ctx: Context, id: String, cfg: QuroSttProviderConfig) {
        val o = JSONObject().apply {
            put("fields", JSONObject().apply { cfg.fields.forEach { (k, v) -> put(k, v) } })
            put("model", cfg.model)
        }
        prefs(ctx).edit().putString(cfgKey(id), o.toString()).apply()
    }

    fun getActiveConfig(ctx: Context): QuroSttProviderConfig = getConfig(ctx, getProvider(ctx))

    fun isConfigured(ctx: Context): Boolean {
        val id = getProvider(ctx)
        val def = QuroSttProviders.byId(id) ?: return false
        if (def.requiredFields.isEmpty()) return true
        val cfg = getConfig(ctx, id)
        return def.requiredFields.all { (cfg.fields[it] ?: "").isNotBlank() }
    }

    fun isConfiguredFor(ctx: Context, id: String): Boolean {
        val def = QuroSttProviders.byId(id) ?: return false
        if (def.requiredFields.isEmpty()) return true
        val cfg = getConfig(ctx, id)
        return def.requiredFields.all { (cfg.fields[it] ?: "").isNotBlank() }
    }
}
