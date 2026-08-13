package com.qiapp.qi

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * 全局配置持久化层。所有设置都落在 SharedPreferences("qi_cfg")，
 * 应用启动后由 MainActivity 调用 [init] 初始化，之后任意组件直接读写即可。
 */
/**
 * TTS 可选服务商。id 对应 [Config.ttsProvider]；source 标注「内置 / 云端」。
 * 云端项复用「模型配置」的共享端点与 API Key（与聊天同一套，对齐 ZorvAI / QuroAI 的 QuroModelConfig），
 * 仅追加 OpenAI 兼容的 /v1/audio/speech 专属参数（model / voice），不维护独立 endpoint / key。
 */
data class TtsPreset(
    val id: Int,
    val label: String,
    val provider: String,
    val source: String,
    val model: String,
    val voice: String,
    val voices: List<String> = emptyList()
)

val TTS_PRESETS = listOf(
    TtsPreset(0, "系统语音（设备内置）", "system", "内置", "gpt-4o-mini-tts", "alloy"),
    TtsPreset(1, "云端 TTS（OpenAI 兼容）", "cloud", "云端", "gpt-4o-mini-tts", "alloy",
        listOf("alloy", "echo", "fable", "onyx", "nova", "shimmer"))
)

/**
 * STT 可选服务商。id 对应 [Config.sttProvider]；云端项复用「模型配置」共享端点 + API Key，
 * 仅追加 OpenAI 兼容的 /v1/audio/transcriptions（Whisper）模型名。
 */
data class SttPreset(
    val id: Int,
    val label: String,
    val provider: String,
    val source: String,
    val model: String
)

val STT_PRESETS = listOf(
    SttPreset(0, "系统识别（设备内置）", "system", "内置", "whisper-1"),
    SttPreset(1, "云端 STT（OpenAI 兼容 Whisper）", "cloud", "云端", "whisper-1")
)

/**
 * 预设服务商数据类（模型配置页 Spinner 与聊天页 provider 标签共用）。
 * idx 对应 [Config.provider]；endpoint/model 为「一键填充」预设值（自定义项为空白）。
 * 具体列表与标签见 [Config.PROVIDERS] / [Config.providerLabel]。
 */
data class ProviderPreset(
    val idx: Int,
    val label: String,
    val endpoint: String,
    val model: String
)

object Config {

    private lateinit var prefs: SharedPreferences
    private var appCtx: Context? = null

    fun init(ctx: Context) {
        if (!::prefs.isInitialized) {
            appCtx = ctx.applicationContext
            prefs = ctx.applicationContext.getSharedPreferences("qi_cfg", Context.MODE_PRIVATE)
            migrateProfiles()
            migrateProviderIndex()
        }
    }

    /**
     * 旧版「写死 3 个模型槽 + 选槽」数据 → 单一激活配置 + 动态已保存模板，保证老配置不丢。
     * 激活配置取原 currentModel 槽；其余已配置槽转化为「已保存模板」动态列表。
     */
    private fun migrateProfiles() {
        if ("migrated_v2".getB(false)) return
        val oldActive = "currentModel".getI(0).coerceIn(0, 2)
        val aEp = "endpoint_$oldActive".get(""); val aKey = "apiKey_$oldActive".get("")
        val aPv = "provider_$oldActive".getI(0); val aModel = "model_$oldActive".get("")
        if (aEp.isNotBlank() || aModel.isNotBlank()) {
            "active_base_url".put(aEp); "active_api_key".put(aKey)
            "active_provider".putI(aPv); "active_model".put(aModel)
        } else {
            for (i in 0..2) {
                val ep = "endpoint_$i".get(""); val model = "model_$i".get("")
                if (ep.isNotBlank() || model.isNotBlank()) {
                    "active_base_url".put(ep); "active_api_key".put("apiKey_$i".get(""))
                    "active_provider".putI("provider_$i".getI(0)); "active_model".put(model)
                    break
                }
            }
        }
        val repo = SavedProfileRepository(appCtx!!)
        for (i in 0..2) {
            if (i == oldActive) continue
            val ep = "endpoint_$i".get(""); val model = "model_$i".get("")
            if (ep.isNotBlank() || model.isNotBlank()) {
                repo.save(SavedProfile(
                    id = UUID.randomUUID().toString(),
                    name = "模型 ${i + 1}",
                    providerIdx = "provider_$i".getI(0),
                    baseUrl = ep, apiKey = "apiKey_$i".get(""), model = model,
                    temperature = 0.7f, createdAt = System.currentTimeMillis(),
                ))
            }
        }
        "migrated_v2".putB(true)
    }

    /**
     * 端点归一化：仅做无害清洗（去首尾空白、去尾部斜杠），**不做任何域名改写**。
     * 用户手填或订阅套餐提供的端点（如小米订阅网关 token-plan-cn.xiaomimimo.com/v1）一律原样保留——
     * 订阅网关与标准 API 端点（api.xiaomimimo.com）是两套鉴权体系，把订阅 Key 偷偷改发到标准端点必然 401。
     * 在端点读取（[resolveEndpoint]）、写入（[endpoint] setter）与客户端拼 URL（[LlmClient.completeEndpoint]）处统一生效。
     */
    fun normalizeEndpoint(raw: String): String {
        return raw.trim().trimEnd('/')
    }

    fun isInit() = ::prefs.isInitialized

    private fun String.get(def: String) = if (isInit()) (prefs.getString(this, def) ?: def) else def
    private fun String.put(v: String) = prefs.edit().putString(this, v).apply()
    private fun String.getF(def: Float) = if (isInit()) prefs.getFloat(this, def) else def
    private fun String.putF(v: Float) = prefs.edit().putFloat(this, v).apply()
    private fun String.getI(def: Int) = if (isInit()) prefs.getInt(this, def) else def
    private fun String.putI(v: Int) = prefs.edit().putInt(this, v).apply()
    private fun String.getB(def: Boolean) = if (isInit()) prefs.getBoolean(this, def) else def
    private fun String.putB(v: Boolean) = prefs.edit().putBoolean(this, v).apply()

    // ---- 单一当前激活模型配置（与聊天 / TTS / STT 共用，对齐 ZorvAI / QuroAI 的 QuroModelConfig）----
    // 栖早期为「写死 3 个槽 + 选槽」模型，导致「已保存模板」死限 3 个且选槽/回退逻辑乱（401 根因之一）。
    // 现统一为「单个激活配置 + 动态命名已保存模板列表」，与 ZorvAI / QuroAI 完全一致。
    /**
     * 模型配置页 Spinner 展示的服务商顺序（单一数据源来自 [ApiProviderConfigs] 注册表）。
     * 仅收录本工程 [LlmClient]（OpenAI 兼容 /chat/completions + Bearer）能直接调用的服务商；
     * 非 OpenAI 兼容（ANTHROPIC / BAIDU / OPENAI_RESPONSES / 本地引擎 MNN·LLAMA_CPP 等）不进 Spinner，
     * 但完整保留在 [ApiProviderConfigs] 注册表供参考。顺序固定以保证已持久化的 provider 整型不漂移。
     */
    private val SPINNER_PROVIDER_TYPES: List<ApiProviderType> = listOf(
        ApiProviderType.OTHER,
        ApiProviderType.OPENAI,
        ApiProviderType.DEEPSEEK,
        ApiProviderType.ALIYUN,
        ApiProviderType.XUNFEI,
        ApiProviderType.ZHIPU,
        ApiProviderType.BAICHUAN,
        ApiProviderType.MOONSHOT,
        ApiProviderType.MIMO,
        ApiProviderType.MISTRAL,
        ApiProviderType.SILICONFLOW,
        ApiProviderType.IFLOW,
        ApiProviderType.OPENROUTER,
        ApiProviderType.FOUR_ROUTER,
        ApiProviderType.NOUS_PORTAL,
        ApiProviderType.INFINIAI,
        ApiProviderType.ALIPAY_BAILING,
        ApiProviderType.DOUBAO,
        ApiProviderType.NVIDIA,
        ApiProviderType.GOOGLE,
        ApiProviderType.LMSTUDIO,
        ApiProviderType.OLLAMA,
        ApiProviderType.OPENAI_LOCAL,
        ApiProviderType.PPINFRA,
        ApiProviderType.NOVITA,
    )

    /** 服务商中文展示名（标签集中维护；[ApiProviderConfigs] 仅存枚举与端点）。 */
    private val PROVIDER_LABELS: Map<ApiProviderType, String> = mapOf(
        ApiProviderType.OTHER to "自定义",
        ApiProviderType.OPENAI to "OpenAI",
        ApiProviderType.DEEPSEEK to "DeepSeek",
        ApiProviderType.ALIYUN to "通义千问 (Qwen)",
        ApiProviderType.XUNFEI to "讯飞星火",
        ApiProviderType.ZHIPU to "智谱 GLM",
        ApiProviderType.BAICHUAN to "百川",
        ApiProviderType.MOONSHOT to "Kimi (Moonshot)",
        ApiProviderType.MIMO to "小米 MiMo",
        ApiProviderType.MISTRAL to "Mistral",
        ApiProviderType.SILICONFLOW to "硅基流动",
        ApiProviderType.IFLOW to "iFlow",
        ApiProviderType.OPENROUTER to "OpenRouter",
        ApiProviderType.FOUR_ROUTER to "4Router",
        ApiProviderType.NOUS_PORTAL to "Nous Portal",
        ApiProviderType.INFINIAI to "无问芯穹 (InfiniAI)",
        ApiProviderType.ALIPAY_BAILING to "支付宝百灵",
        ApiProviderType.DOUBAO to "火山豆包",
        ApiProviderType.NVIDIA to "NVIDIA (NIM)",
        ApiProviderType.GOOGLE to "Gemini (OpenAI 兼容)",
        ApiProviderType.LMSTUDIO to "LM Studio (本地)",
        ApiProviderType.OLLAMA to "Ollama (本地)",
        ApiProviderType.OPENAI_LOCAL to "OpenAI 兼容本地",
        ApiProviderType.PPINFRA to "派欧云 (PPInfra)",
        ApiProviderType.NOVITA to "Novita AI",
    )

    /**
     * 预设服务商列表（模型配置页 Spinner 与聊天页 provider 标签的唯一数据源）。
     * **完全派生自权威注册表 [ApiProviderConfigs]**：endpoint / model 默认值直接取注册表，
     * 不再手抄任何端点字符串（历史手抄正是 MiMo 域名混淆与索引漂移的根因）。
     * idx 为 Spinner 位置，对应 [provider]；endpoint/model 为「一键填充」预设值（自定义项空白）。
     */
    val PROVIDERS: List<ProviderPreset> = SPINNER_PROVIDER_TYPES.mapIndexed { idx, t ->
        val cfg = ApiProviderConfigs.get(t)
        ProviderPreset(idx, PROVIDER_LABELS[t] ?: t.name, cfg.defaultApiEndpoint, cfg.defaultModelName)
    }

    /**
     * 旧版 fork 手写 PROVIDERS（0-32，含 Groq/Together/Cohere/零一万物/MiniMax/StepFun/腾讯混元 等非 ZorvAI 服务商）
     * 与新版派生自 [ApiProviderConfigs] 的 [SPINNER_PROVIDER_TYPES] 顺序不同（移除非 ZorvAI 服务商、火山豆包/阿里等位置变化）。
     * 把持久化的旧整型 active_provider 映射到新顺序，避免选错服务商。一次性执行。
     */
    private val LEGACY_PROVIDER_INDEX_TO_NEW = intArrayOf(
        0, 1, 2, 7, 3, 5, 4, 6, 17, 8, 9, 10, 11, 12,
        0, 0, 0, 0, 0, 0, 0,
        3, 19, 21, 20, 13, 14, 15, 16, 18, 22, 23, 24
    )

    private fun migrateProviderIndex() {
        if ("migrated_provider_idx".getB(false)) return
        val old = "active_provider".getI(-1)
        if (old in LEGACY_PROVIDER_INDEX_TO_NEW.indices) {
            "active_provider".putI(LEGACY_PROVIDER_INDEX_TO_NEW[old])
        }
        "migrated_provider_idx".putB(true)
    }

    /** 预设服务商标签（渲染副标题用）；越界/未知返回「自定义」。 */
    fun providerLabel(idx: Int): String = PROVIDERS.getOrNull(idx)?.label ?: "自定义"

    /** 当前激活配置：预设服务商序号（对应 [PROVIDERS]）。 */
    var provider: Int
        get() = "active_provider".getI(0).coerceIn(0, PROVIDERS.lastIndex)
        set(v) = "active_provider".putI(v.coerceIn(0, PROVIDERS.lastIndex))
    /** 当前激活配置：端点（写入即经 [normalizeEndpoint] 仅做无害清洗：去尾部斜杠，不改写域名）。 */
    var endpoint: String
        get() = "active_base_url".get("").trim().trimEnd('/')
        set(v) = "active_base_url".put(normalizeEndpoint(v))
    /** 当前激活配置：API Key。写入时经 [cleanKey] 清洗（去掉不可见字符 / Bearer 前缀 / 首尾空白），
     *  落库即干净，聊天与测试连接读到的一律是清洗后的值，杜绝「复制粘贴带零宽空格/BOM → 服务端 401」。 */
    var apiKey: String
        get() = "active_api_key".get("")
        set(v) = "active_api_key".put(cleanKey(v))
    /** 当前激活配置：模型名（空白即未配置，绝不回退默认）。 */
    val model: String
        get() = "active_model".get("")

    fun setModelName(v: String) = "active_model".put(v.trim())

    /** 激活配置的真实存储值（空白即空白，不回退），仅用于 UI 预填与「未配置」判定。 */
    fun rawEndpoint(): String = "active_base_url".get("")
    fun rawApiKey(): String = "active_api_key".get("")
    fun rawModel(): String = "active_model".get("")

    /** 当前激活模型名（无参，等价于 [model]）。 */
    fun modelName(): String = model

    /** 当前激活服务商标签（无参）。 */
    fun providerLabel(): String = PROVIDERS.getOrNull(provider)?.label ?: "自定义"

    /**
     * 解析「实际生效端点」：优先激活配置端点 → 再选中服务商预设端点 → 空（未配置）。
     * **绝不**回退到 api.openai.com：把非 OpenAI 的 Key 偷偷发到 OpenAI 正是 401 的根因。
     * 端点经 [normalizeEndpoint] 仅做无害清洗（去尾部斜杠），不改写域名，订阅网关等用户自定义端点原样保留。
     * 聊天 / 测试连接 / 拉模型列表一律用本方法；端点解析为空时由调用方明确报错。
     */
    fun resolveEndpoint(): String {
        rawEndpoint().takeIf { it.isNotBlank() }?.let { return normalizeEndpoint(it) }
        PROVIDERS.getOrNull(provider)?.endpoint?.takeIf { it.isNotBlank() }?.let {
            return normalizeEndpoint(it)
        }
        return ""
    }

    /** Key 脱敏（仅首尾，绝不记完整 Key）：用于设备内诊断日志。 */
    fun maskKey(k: String): String {
        if (k.isBlank()) return "[空]"
        return if (k.length <= 8) k.take(2) + "***" else k.take(6) + "…" + k.takeLast(2)
    }

    /**
     * 清洗 API Key：去掉首尾空白、误粘的「Bearer 」前缀，以及复制粘贴常带入的不可见字符
     * （零宽空格 \u200B、BOM \uFEFF、不间断空格 \u00A0、各类零宽控制符、C0/C1 控制字符等）。
     * 普通的 [String.trim] 只去 \n\r\t 等常规空白，清不掉这些字符——
     * 这正是「Key 看起来一模一样、服务端却报 Invalid API Key（401）」的最常见根因：
     * 你从网页/密码管理器/备忘录复制 Key 时，末尾或首尾常夹带一个零宽空格或换行，
     * 服务端按字节校验，多一个不可见字符就判定 Key 无效。
     * 清洗在「写入即落库」与「发起请求」两处都生效，保证发出去的一定是最干净的字节。
     */
    fun cleanKey(raw: String): String {
        return raw
            .replace(Regex("(?i)^Bearer\\s+"), "") // 去掉误粘的 Bearer 前缀
            .replace(Regex("[\\u0000-\\u001F\\u007F\\u0080-\\u009F\\u00A0\\u2000-\\u200F\\u2028-\\u202F\\u205F\\u2060\\u3000\\uFEFF\\u200B\\u200C\\u200D\\u2061-\\u2064]"), "") // 去控制/零宽/特殊空格
            .trim()
    }

    /** 把 Key 转成 hex 字符码，用于设备内诊断暴露「不可见字符」（不泄露明文 Key 本身）。 */
    fun keyHex(k: String): String = k.toCharArray().joinToString("") { "%02X".format(it.code) }

    /** 检测原始 Key 是否含不可见字符（用于诊断提示「已自动清洗」）。 */
    fun keyHasInvisible(k: String): Boolean = k.any { c ->
        c.code < 0x20 || c.code == 0x7F || c.code in 0x80..0x9F ||
            c.code == 0xA0 || c.code in 0x2000..0x200F || c.code in 0x2028..0x202F ||
            c.code == 0x205F || c.code == 0x2060 || c.code == 0x3000 || c.code == 0xFEFF ||
            c.code == 0x200B || c.code == 0x200C || c.code == 0x200D
    }

    /**
     * 设备内 LLM 诊断：把「实际打到哪个端点 / Key 是否空 / 模型 / 返回码」写入
     * <外部私有 Download>/栖_logs/llm_last.txt（覆盖写最近一次），401 等错误额外写 llm_401_<ts>.txt。
     * 复用 QiApplication 的栖_logs 目录，用户无需 adb，用手机文件管理器即可查看。
     * 仅在 appCtx 可用时写入，失败静默忽略，绝不阻塞聊天。
     */
    fun writeLlmDiag(text: String, isError: Boolean = false) {
        try {
            val ctx = appCtx ?: return
            val base = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
            val dir = File(base, "栖_logs")
            if (!dir.exists()) dir.mkdirs()
            File(dir, "llm_last.txt").writeText(text)
            if (isError) {
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                File(dir, "llm_401_$ts.txt").writeText(text)
            }
        } catch (_: Exception) { /* 忽略 */ }
    }

    /**
     * 已保存模板仓库（延迟创建，需 Context，[init] 之后 appCtx 可用）。
     * 对齐 ZorvAI / QuroAI 的 QuroSavedProfileRepository，去品牌化。
     */
    fun savedProfiles(): SavedProfileRepository = SavedProfileRepository(appCtx!!)

    /**
     * 将当前激活配置转为可保存的预设（动态模板）。
     * 对齐 ZorvAI / QuroAI 的 [QuroModelConfig.toProfile]。
     */
    fun toProfile(name: String): SavedProfile {
        return SavedProfile(
            id = UUID.randomUUID().toString(),
            name = name,
            providerIdx = provider,
            baseUrl = endpoint,
            apiKey = apiKey,
            model = model,
            temperature = temperature,
            createdAt = System.currentTimeMillis(),
        )
    }

    // ---- 生成参数 ----
    var temperature: Float
        get() = "temperature".getF(0.7f)
        set(v) = "temperature".putF(v.coerceIn(0f, 2f))

    var memoryRounds: Int
        get() = "memoryRounds".getI(10)
        set(v) = "memoryRounds".putI(v.coerceIn(0, 50))

    /** 0=短 1=中 2=长 */
    var lengthMode: Int
        get() = "lengthMode".getI(1)
        set(v) = "lengthMode".putI(v.coerceIn(0, 2))

    var stream: Boolean
        get() = "stream".getB(true)
        set(v) = "stream".putB(v)

    var think: Boolean
        get() = "think".getB(false)
        set(v) = "think".putB(v)

    /** 工具调用（function calling）总开关：点亮后 LlmClient 会真正下发 tools 并执行本地工具 */
    var enableTools: Boolean
        get() = "enableTools".getB(true)
        set(v) = "enableTools".putB(v)

    /** 工具调用轮次上限：0=不限制（内置 200 轮安全天花板） */
    var maxToolRounds: Int
        get() = "maxToolRounds".getI(0)
        set(v) = "maxToolRounds".putI(v.coerceIn(0, 200))

    // ---- TTS 语音合成 ----
    /** 语速 0.5~1.5 */
    var ttsSpeed: Float
        get() = "ttsSpeed".getF(1.0f)
        set(v) = "ttsSpeed".putF(v.coerceIn(0.5f, 1.5f))

    /** 音调偏移 -1.0~+1.0（1.0 为中性） */
    var ttsPitch: Float
        get() = "ttsPitch".getF(1.0f)
        set(v) = "ttsPitch".putF(v.coerceIn(0f, 2f))

    var autoplay: Boolean
        get() = "autoplay".getB(true)
        set(v) = "autoplay".putB(v)

    /** 对话框内「语音回复」总开关：开启后 AI 回复以语音气泡呈现并自动朗读；关闭后仍是文本气泡。默认开。 */
    var voiceReply: Boolean
        get() = "voice_reply".getB(true)
        set(v) = "voice_reply".putB(v)

    // ---- STT 语音识别 ----
    var silenceMs: Int
        get() = "silenceMs".getI(800)
        set(v) = "silenceMs".putI(v)

    var autopunc: Boolean
        get() = "autopunc".getB(true)
        set(v) = "autopunc".putB(v)

    // ---- 语音「模型」配置（TTS / STT 可切换服务商，对齐 ZorvAI / QuroAI）----
    // 云端 TTS / STT 复用「模型配置」的共享端点与 API Key（与聊天同一套），不维护独立 endpoint / key。
    /** TTS 服务商序号；0=系统内置（本地 TextToSpeech），非 0=云端（具体服务商见 [ttsProviderId]） */
    var ttsProvider: Int
        get() = "ttsProvider".getI(0).coerceIn(0, 1)
        set(v) = "ttsProvider".putI(v.coerceIn(0, 1))

    /** 云端 TTS 选中的具体服务商 id（对应 [QuroTtsProviders]，如 "openai" / "edge" / "siliconflow"），默认 openai。 */
    var ttsProviderId: String
        get() = "ttsProviderId".get("openai")
        set(v) = "ttsProviderId".put(v.trim())

    var ttsModel: String
        get() = "ttsModel".get("")
        set(v) = "ttsModel".put(v.trim())
    var ttsVoice: String
        get() = "ttsVoice".get("")
        set(v) = "ttsVoice".put(v.trim())

    /** 云端 TTS 地址：复用当前模型配置 base（与聊天同一套），追加 OpenAI 音频接口路径。 */
    fun ttsCloudUrl(): String {
        val base = endpoint.trim().trimEnd('/')
        return if (base.endsWith("/audio/speech")) base else "$base/audio/speech"
    }

    /** STT 服务商序号；0=系统原生识别（SpeechRecognizer），非 0=云端 Whisper（具体服务商见 [sttProviderId]） */
    var sttProvider: Int
        get() = "sttProvider".getI(0).coerceIn(0, 1)
        set(v) = "sttProvider".putI(v.coerceIn(0, 1))

    /** 云端 STT 选中的具体服务商 id（对应 [QuroSttProviders]，如 "openai" / "siliconflow"），默认 openai。 */
    var sttProviderId: String
        get() = "sttProviderId".get("openai")
        set(v) = "sttProviderId".put(v.trim())

    var sttModel: String
        get() = "sttModel".get("")
        set(v) = "sttModel".put(v.trim())

    /** 云端 STT 地址：复用当前模型配置 base，追加 OpenAI Whisper 音频接口路径。 */
    fun sttCloudUrl(): String {
        val base = endpoint.trim().trimEnd('/')
        return if (base.endsWith("/audio/transcriptions")) base else "$base/audio/transcriptions"
    }

    // ---- 语音球 ----
    var ballEnabled: Boolean
        get() = "ballEnabled".getB(true)
        set(v) = "ballEnabled".putB(v)

    var ballWake: Int     // 0 长按 1 点按
        get() = "ballWake".getI(0)
        set(v) = "ballWake".putI(v)

    /** 语音球尺寸 0 小 1 中 2 大 */
    var ballSize: Int
        get() = "ballSize".getI(1)
        set(v) = "ballSize".putI(v.coerceIn(0, 2))

    /** 语音球配色 0 桃粉 1 天蓝 2 藕荷 */
    var ballColor: Int
        get() = "ballColor".getI(0)
        set(v) = "ballColor".putI(v.coerceIn(0, 2))

    var ballRemember: Boolean
        get() = "ballRemember".getB(true)
        set(v) = "ballRemember".putB(v)

    /** 记住的浮窗位置（px，屏幕坐标），-1 表示未记录 */
    var ballPosX: Int
        get() = "ballPosX".getI(-1)
        set(v) = "ballPosX".putI(v)
    var ballPosY: Int
        get() = "ballPosY".getI(-1)
        set(v) = "ballPosY".putI(v)

    // ---- 选择态 ----
    var currentSoul: Int
        get() = "currentSoul".getI(0)
        set(v) = "currentSoul".putI(v.coerceIn(0, 1))

    // ---- 用户资料（自己）：名称 + 头像，对应聊天里自己发出的消息显示的名字与头像 ----
    /** 当前用户名称（默认「我」）；用于对话框自己消息、朋友圈、以及注入 AI 系统提示词。 */
    fun userName(): String = "userName".get("我")
    fun setUserName(name: String) = "userName".put(name.trim().takeIf { it.isNotBlank() } ?: "我")

    /** 用户头像本地路径（空表示未设置，用默认图标）。 */
    fun userAvatar(): String = "userAvatar".get("")
    fun setUserAvatar(path: String) = "userAvatar".put(path)

    // ---- 灵魂卡（可被用户编辑覆盖）----
    fun soulName(idx: Int): String {
        val def = if (idx in AppState.baseSouls.indices) AppState.baseSouls[idx].name else "栖"
        return "soulName_$idx".get(def)
    }
    fun soulDesc(idx: Int): String {
        val def = if (idx in AppState.baseSouls.indices) AppState.baseSouls[idx].desc else ""
        return "soulDesc_$idx".get(def)
    }
    fun setSoul(idx: Int, name: String, desc: String) {
        prefs.edit().putString("soulName_$idx", name.trim()).putString("soulDesc_$idx", desc.trim()).apply()
    }

    // 角色设定（系统提示词核心 / 聊天设定 / 语音设定），被 LlmClient.buildSystemPrompt 真实消费
    fun soulSystem(idx: Int): String = "soulSystem_$idx".get("")
    fun soulChat(idx: Int): String = "soulChat_$idx".get("")
    fun soulVoice(idx: Int): String = "soulVoice_$idx".get("")
    fun setSoulProfile(idx: Int, system: String, chat: String, voice: String) {
        "soulSystem_$idx".put(system.trim())
        "soulChat_$idx".put(chat.trim())
        "soulVoice_$idx".put(voice.trim())
    }

    // 标签（人格补充）：完整三层结构（name+hint+json），对齐 ZorvAI QuroTag。
    // 持久化为 JSON 对象数组；兼容旧版的纯字符串数组（仅 name）。
    fun soulTags(idx: Int): List<SoulTag> {
        val raw = "soulTags_$idx".get("")
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val e = arr.get(i)
                when (e) {
                    is String -> SoulTag(e) // 旧格式：纯名字
                    is JSONObject -> SoulTag(
                        name = e.optString("name", ""),
                        hint = e.optString("hint", ""),
                        json = e.optString("json", "")
                    ).takeIf { !it.isBlank() }
                    else -> null
                }
            }
        } catch (_: Exception) { emptyList() }
    }
    fun setSoulTags(idx: Int, tags: List<SoulTag>) {
        val arr = JSONArray()
        tags.forEach { t ->
            val o = JSONObject()
            o.put("name", t.name)
            o.put("hint", t.hint)
            o.put("json", t.json)
            arr.put(o)
        }
        "soulTags_$idx".put(arr.toString())
    }

    // 头像本地文件路径（用户上传后复制到 filesDir）
    fun soulAvatar(idx: Int): String = "soulAvatar_$idx".get("")
    fun setSoulAvatar(idx: Int, path: String) = "soulAvatar_$idx".put(path)

    // 人格孵化次数（真实累计，注入 system prompt 的成长印记）
    fun soulHatch(idx: Int): Int = "soulHatch_$idx".getI(0)
    fun bumpSoulHatch(idx: Int) = "soulHatch_$idx".putI(soulHatch(idx) + 1)

    // 人格孵化「成长印记」：每次「让她长大」由 LLM 生成一句真实脾气，持久化并注入 system prompt
    fun soulHatchMarks(idx: Int): List<String> {
        val raw = "soulHatchMarks_$idx".get("")
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) { emptyList() }
    }
    fun addSoulHatchMark(idx: Int, mark: String) {
        val list = soulHatchMarks(idx).toMutableList()
        list.add(mark)
        val trimmed = if (list.size > 20) list.takeLast(20) else list
        val arr = JSONArray()
        trimmed.forEach { arr.put(it) }
        "soulHatchMarks_$idx".put(arr.toString())
    }

    // ---- 资料页：模仿聊天软件的「栖号」ID（稳定，生成一次后持久化）----
    fun soulQiId(idx: Int): String {
        val existing = "soulQiId_$idx".get("")
        if (existing.isNotBlank()) return existing
        val id = genQiId(idx)
        "soulQiId_$idx".put(id)
        return id
    }
    private fun genQiId(idx: Int): String {
        val name = soulName(idx)
        val base = name.lowercase().replace(Regex("[^a-z0-9]"), "").takeIf { it.isNotBlank() } ?: "qi"
        val t = (System.currentTimeMillis() and 0x7fffffffL).toInt() % 9000
        val rnd = 1000 + (idx * 7919 + t) % 9000
        return "qi_${base}_$rnd"
    }

    // 朋友圈首次自动填充标记（避免每次打开空白页都触发联网 / 重复生成）
    fun momentsSeeded(idx: Int): Boolean = "momentsSeeded_$idx".getB(false)
    fun setMomentsSeeded(idx: Int, v: Boolean) = "momentsSeeded_$idx".putB(v)

    // 自我沉淀的性格注解（由「AI 心跳」研究升级时自动追加，区别于用户手写的角色设定）
    fun soulSelfNotes(idx: Int): List<String> {
        val raw = "soulSelfNotes_$idx".get("")
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        } catch (_: Exception) { emptyList() }
    }
    fun addSoulSelfNote(idx: Int, note: String) {
        val t = note.trim()
        if (t.isBlank()) return
        val list = soulSelfNotes(idx).toMutableList()
        list.add(t)
        val kept = if (list.size > 8) list.takeLast(8) else list
        val arr = JSONArray()
        kept.forEach { arr.put(it) }
        "soulSelfNotes_$idx".put(arr.toString())
    }

    // 记忆库（长期记忆）：跨会话持久化，与资料库提示词、人格卡互相关联。
    // 由助手在聊天中经 save_memory 工具写入，或由「AI 心跳」孵化时从对话蒸馏写入。
    data class MemoryEntry(val text: String, val ts: Long, val weight: Int)

    fun memoryEntries(idx: Int): List<MemoryEntry> {
        val raw = "memory_$idx".get("")
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val t = o.optString("t", "")
                if (t.isBlank()) null else MemoryEntry(t, o.optLong("ts", 0L), o.optInt("w", 1))
            }
        } catch (_: Exception) { emptyList() }
    }

    /** 写入一条长期记忆；weight 1-3 表示重要性，越高越优先被回忆。 */
    fun addMemory(idx: Int, text: String, weight: Int = 1) {
        val t = text.trim()
        if (t.isBlank()) return
        val list = memoryEntries(idx).toMutableList()
        list.add(MemoryEntry(t, System.currentTimeMillis(), weight.coerceIn(1, 3)))
        val kept = if (list.size > 120) list.takeLast(120) else list
        val arr = JSONArray()
        kept.forEach { arr.put(JSONObject().put("t", it.text).put("ts", it.ts).put("w", it.weight)) }
        "memory_$idx".put(arr.toString())
    }

    /** 取最近/最重要的 N 条记忆摘要（按权重降序），供 system prompt 注入。 */
    fun memorySummary(idx: Int, n: Int = 10): List<String> {
        val list = memoryEntries(idx)
        if (list.isEmpty()) return emptyList()
        return list.sortedWith(compareByDescending<MemoryEntry> { it.weight }.thenByDescending { it.ts })
            .take(n).map { it.text }
    }

    fun clearMemory(idx: Int) = "memory_$idx".put("[]")

    // ---- AI 心跳（后台自动孵化）配置 ----
    var aiHeartbeatOn: Boolean
        get() = "aiHeartbeatOn".getB(true)
        set(v) = "aiHeartbeatOn".putB(v)
    fun aiHeartbeatMinutes(): Int = "aiHeartbeatMin".getI(30).coerceIn(5, 1440)
    fun setAiHeartbeatMinutes(m: Int) = "aiHeartbeatMin".putI(m.coerceIn(5, 1440))
    fun aiHeartbeatLast(): Long = "aiHeartbeatLast".get("0").toLongOrNull() ?: 0L
    fun setAiHeartbeatLast(t: Long) = "aiHeartbeatLast".put(t.toString())

    // ---- 情绪构架（AI 的心情与情绪底色，驱动说话语气与朋友圈）----
    fun soulMood(idx: Int): String = "soulMood_$idx".get("")
    fun setSoulMood(idx: Int, m: String) = "soulMood_$idx".put(m)
    fun soulEmotion(idx: Int): String = "soulEmotion_$idx".get("")
    fun setSoulEmotion(idx: Int, e: String) = "soulEmotion_$idx".put(e)

    // ---- 朋友圈动态（AI 与用户共同的社交时间流，支持点赞 + 评论 + AI 回复评论）----
    /** 单条评论：author 为 "me"（用户）或 "soul:0"/"soul:1"（AI 灵魂回复）。 */
    data class Comment(
        val author: String,
        val text: String,
        val ts: Long
    )

    /**
     * 一条朋友圈动态。
     * - author：""/"soul:0"/"soul:1" 表示 AI（旧数据 author 为空时按 soulIdx 判定为 AI）；"me" 表示用户自己。
     * - likes：点赞数；likedByMe：当前用户是否已点赞。
     * - comments：评论列表（含用户评论与 AI 对评论的回复）。
     */
    data class Moment(
        val id: String,
        val soulIdx: Int,
        val text: String,
        val mood: String,
        val emotion: String,
        val sticker: String,   // emoji 字符串，或 "asset:xxx.png"，或 "file:/绝对路径"
        val ts: Long,
        val author: String = "",
        val likes: Int = 0,
        val likedByMe: Boolean = false,
        val comments: List<Comment> = emptyList()
    )

    /** 是否为用户自己发的动态。 */
    fun isUserMoment(m: Moment): Boolean = m.author == "me"

    /** 读「某灵魂的 AI 动态」列表（author 为空或 "soul:idx"）。 */
    fun moments(idx: Int): List<Moment> = parseMoments("moments_$idx", idx)

    /** 用户的动态列表（author == "me"）。 */
    fun userMoments(): List<Moment> = parseMoments("user_moments", 0)

    /** 朋友圈合并流：两个灵魂的 AI 动态 + 用户自己的动态，按时间倒序（最新在前）。 */
    fun feedMoments(): List<Moment> =
        (moments(0) + moments(1) + userMoments()).sortedByDescending { it.ts }

    /** 动态作者的展示名：用户 → userName；AI → 灵魂名。 */
    fun momentAuthorName(m: Moment): String =
        if (isUserMoment(m)) userName() else soulName(m.soulIdx)

    private fun parseMoments(key: String, idxFallback: Int): List<Moment> {
        val raw = key.get("")
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val t = o.optString("t", "")
                val soulIdx = o.optInt("si", idxFallback)
                val authorRaw = o.optString("au", "")
                val author = authorRaw.ifBlank { "soul:$soulIdx" }
                val comments = try {
                    val ca = o.optJSONArray("cm") ?: JSONArray()
                    (0 until ca.length()).mapNotNull { j ->
                        val co = ca.optJSONObject(j) ?: return@mapNotNull null
                        val ct = co.optString("x", "")
                        if (ct.isBlank()) null else Comment(co.optString("a", "me"), ct, co.optLong("ts", 0L))
                    }
                } catch (_: Exception) { emptyList() }
                Moment(
                    o.optString("id", ""),
                    soulIdx, t,
                    o.optString("mood", ""),
                    o.optString("emo", ""),
                    o.optString("st", ""),
                    o.optLong("ts", 0L),
                    author,
                    o.optInt("lk", 0),
                    o.optBoolean("lm", false),
                    comments
                ).takeIf { t.isNotBlank() || o.optString("st", "").isNotBlank() }
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun saveMoments(key: String, list: List<Moment>) {
        val arr = JSONArray()
        list.forEach { mm ->
            arr.put(JSONObject().apply {
                put("id", mm.id); put("si", mm.soulIdx); put("t", mm.text)
                put("mood", mm.mood); put("emo", mm.emotion); put("st", mm.sticker)
                put("ts", mm.ts); put("au", mm.author); put("lk", mm.likes)
                put("lm", mm.likedByMe)
                val ca = JSONArray()
                mm.comments.forEach { c ->
                    ca.put(JSONObject().put("a", c.author).put("x", c.text).put("ts", c.ts))
                }
                put("cm", ca)
            })
        }
        key.put(arr.toString())
    }

    /** 追加一条 AI 动态（author 固定为 "soul:idx"）。 */
    fun addMoment(idx: Int, m: Moment) {
        val list = moments(idx).toMutableList()
        list.add(0, m.copy(author = "soul:$idx", soulIdx = idx))
        val kept = if (list.size > 200) list.take(200) else list
        saveMoments("moments_$idx", kept)
    }

    /** 追加一条用户动态（author 固定为 "me"）。 */
    fun addUserMoment(m: Moment) {
        val list = userMoments().toMutableList()
        list.add(0, m.copy(author = "me"))
        val kept = if (list.size > 200) list.take(200) else list
        saveMoments("user_moments", kept)
    }

    /**
     * 按 id 找到动态并就地更新（点赞 / 评论 / AI 回复评论等）。
     * 自动定位它所在列表（moments_0 / moments_1 / user_moments）并回写。
     */
    fun updateMoment(id: String, block: (Moment) -> Moment) {
        for (key in listOf("moments_0", "moments_1", "user_moments")) {
            val list = parseMoments(key, 0).toMutableList()
            val pos = list.indexOfFirst { it.id == id }
            if (pos >= 0) {
                list[pos] = block(list[pos])
                saveMoments(key, list)
                return
            }
        }
    }

    fun clearMoments(idx: Int) = saveMoments("moments_$idx", emptyList())

    // 用户/AI 添加的图片贴纸（绝对路径列表，存于应用私有目录）
    fun userStickers(): List<String> {
        val raw = "userStickers".get("")
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        } catch (_: Exception) { emptyList() }
    }
    fun addUserSticker(path: String) {
        val p = path.trim()
        if (p.isBlank()) return
        val list = userStickers().toMutableList()
        if (list.contains(p)) return
        list.add(p)
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        "userStickers".put(arr.toString())
    }

    // 聊天背景（用户选择的图片本地路径，空表示默认）
    var chatBg: String
        get() = "chatBg".get("")
        set(v) = "chatBg".put(v)

    // ---- 动态形象（灵魂数字人，按灵魂持久化）----
    /** 是否启用动态形象。 */
    fun soulAvatarOn(idx: Int): Boolean = "soulAvatarOn_$idx".getB(true)
    fun setSoulAvatarOn(idx: Int, v: Boolean) = "soulAvatarOn_$idx".putB(v)
    /** 是否用上传的灵魂头像做脸（默认关：形象空间优先显示完整的插画角色，可在齿轮里开启）。 */
    fun soulAvatarPhoto(idx: Int): Boolean = "soulAvatarPhoto_$idx".getB(false)
    fun setSoulAvatarPhoto(idx: Int, v: Boolean) = "soulAvatarPhoto_$idx".putB(v)
    /** 表情风格 0 温柔 / 1 活泼 / 2 高冷。 */
    fun soulAvatarStyle(idx: Int): Int = "soulAvatarStyle_$idx".getI(0).coerceIn(0, 2)
    fun setSoulAvatarStyle(idx: Int, v: Int) = "soulAvatarStyle_$idx".putI(v.coerceIn(0, 2))
    /** 形象背景 0 跟随情绪 / 1 晨曦 / 2 暮色 / 3 自定义图。 */
    fun soulAvatarBg(idx: Int): Int = "soulAvatarBg_$idx".getI(0).coerceIn(0, 3)
    fun setSoulAvatarBg(idx: Int, v: Int) = "soulAvatarBg_$idx".putI(v.coerceIn(0, 3))
    /** 自定义形象背景图片本地路径（bg 模式为 3 时生效）。 */
    fun soulAvatarCustomBg(idx: Int): String = "soulAvatarCustomBg_$idx".get("")
    fun setSoulAvatarCustomBg(idx: Int, p: String) = "soulAvatarCustomBg_$idx".put(p)
    /** 语音对口形开关。 */
    fun soulLipsync(idx: Int): Boolean = "soulLipsync_$idx".getB(true)
    fun setSoulLipsync(idx: Int, v: Boolean) = "soulLipsync_$idx".putB(v)
    /** 实时联动（聊天时随 AI 语音/情绪变化）。 */
    fun soulAvatarLive(idx: Int): Boolean = "soulAvatarLive_$idx".getB(true)
    fun setSoulAvatarLive(idx: Int, v: Boolean) = "soulAvatarLive_$idx".putB(v)
    /** 一次性迁移标记：把旧默认「用头像做脸=true」翻成 false，让形象空间默认显示完整插画角色。 */
    fun avatarPhotoMigrated(): Boolean = "avatarPhotoMigrated".getB(false)
    fun setAvatarPhotoMigrated(v: Boolean) = "avatarPhotoMigrated".putB(v)
}

/**
 * 单条已保存的模型配置模板（对齐 ZorvAI / QuroAI 的 QuroSavedProfile，去品牌化）。
 * 用户可在「模型配置」页将当前激活配置保存为一个命名模板，之后一键「加载」复用，无需每次手填。
 */
data class SavedProfile(
    val id: String = "",
    val name: String = "",
    val providerIdx: Int = 0,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val temperature: Float = 0.7f,
    val createdAt: Long = 0L,
)

/**
 * 已保存模板仓库（对齐 ZorvAI / QuroAI 的 QuroSavedProfileRepository，去品牌化）：
 * 用 SharedPreferences 存储多条 JSON 序列化的模板。存储键 `qi_saved_profiles` → JSONArray 字符串。
 */
class SavedProfileRepository(context: Context) {
    private val prefs = context.getSharedPreferences("qi_saved_profiles", Context.MODE_PRIVATE)
    private val KEY = "profiles_json"

    fun loadAll(): List<SavedProfile> {
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { jsonToProfile(arr.getJSONObject(it)) }
        } catch (_: Exception) { emptyList() }
    }

    fun save(p: SavedProfile) {
        val list = loadAll().toMutableList()
        // 若已存在同 id，替换；否则追加
        val idx = list.indexOfFirst { it.id == p.id }
        if (idx >= 0) list[idx] = p else list.add(p)
        persist(list)
    }

    fun delete(id: String) {
        persist(loadAll().filter { it.id != id })
    }

    /** 用一条模板覆写当前激活配置。 */
    fun applyToConfig(p: SavedProfile) {
        Config.provider = p.providerIdx
        Config.endpoint = p.baseUrl
        Config.apiKey = p.apiKey
        Config.setModelName(p.model)
        Config.temperature = p.temperature
    }

    private fun persist(list: List<SavedProfile>) {
        val arr = JSONArray()
        list.forEach { arr.put(profileToJson(it)) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private fun profileToJson(p: SavedProfile) = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("providerIdx", p.providerIdx)
        put("baseUrl", p.baseUrl)
        put("apiKey", p.apiKey)
        put("model", p.model)
        put("temperature", p.temperature.toDouble())
        put("createdAt", p.createdAt)
    }

    private fun jsonToProfile(o: JSONObject) = SavedProfile(
        id = o.optString("id", ""),
        name = o.optString("name", ""),
        providerIdx = o.optInt("providerIdx", 0),
        baseUrl = o.optString("baseUrl", ""),
        apiKey = o.optString("apiKey", ""),
        model = o.optString("model", ""),
        temperature = o.optDouble("temperature", 0.7).toFloat(),
        createdAt = o.optLong("createdAt", 0L),
    )
}
