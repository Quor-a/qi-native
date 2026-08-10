package com.qiapp.qi

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 语音合成封装（栖 —— 完整移植 ZorvAI 真实引擎）。
 *
 * 保留旧版对外 API（构造 / speak / stop / isReady / shutdown / applyParams），
 * 内部全部委托给 [QuroTtsHolder]（串行播报队列 + 30s 看门狗 + 本地/云端自动回退）
 * 与 [QuroCloudTts]（流式播放 + 音频焦点 + PCM 采样率重建 + 多服务商调度），
 * 不再自己用 MediaPlayer 手工拼播放。
 *
 * 服务商路由：
 *  - Config.ttsProvider == 0 → 系统内置 TextToSpeech（QuroTtsPrefs.SOURCE_LOCAL）
 *  - Config.ttsProvider != 0 → 云端（统一走 OpenAI 兼容，QuroTtsPrefs.SOURCE_CLOUD，
 *    配置由 QuroTtsProviderPrefs 桥接到 Config 共享端点/Key）
 */
class TtsEngine(context: Context) {

    private val appCtx = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        QuroTtsHolder.prepare(appCtx)
        syncSource()
    }

    /** 把 栖 的 TTS 选择同步到 ZorvAI 引擎的 prefs（source + 具体服务商）。 */
    private fun syncSource() {
        if (Config.ttsProvider == 0) {
            QuroTtsPrefs.setSource(appCtx, QuroTtsPrefs.SOURCE_LOCAL)
        } else {
            QuroTtsPrefs.setSource(appCtx, QuroTtsPrefs.SOURCE_CLOUD)
            QuroTtsProviderPrefs.setProvider(appCtx, Config.ttsProviderId.ifBlank { "openai" })
        }
    }

    /** 把 栖 的语速/音调映射进 QuroTtsPrefs（同时驱动本地 TTS 与云端 speed 参数）。 */
    fun applyParams() {
        QuroTtsPrefs.setRate(appCtx, Config.ttsSpeed.coerceIn(0.5f, 2.0f))
        QuroTtsPrefs.setPitch(appCtx, Config.ttsPitch.coerceIn(0.1f, 2.0f))
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        speak(text, false, onDone)
    }

    /**
     * 朗读文本。[avatarSync] 为真时，在播放起止期间通过 [AvatarBus] 广播「说话幅度」，
     * 驱动任意可见 [AvatarView] 与语音同步对口形（对任意 TTS 后端都生效，无需解析波形）。
     */
    fun speak(text: String, avatarSync: Boolean, onDone: (() -> Unit)? = null) {
        if (text.isBlank()) { onDone?.let { mainHandler.post(it) }; return }
        if (avatarSync) AvatarBus.beginSpeech(text)
        syncSource()
        applyParams()
        val cb = onDone?.let { r -> { AvatarBus.endSpeech(); mainHandler.post(r); Unit } }
        scope.launch {
            val rc = QuroTtsHolder.speak(text, cb)
            if (rc != 0) { AvatarBus.endSpeech(); cb?.invoke() }
        }
    }

    fun isReady(): Boolean {
        return if (Config.ttsProvider == 0) {
            true // 系统 TTS 在首次 speak 时懒初始化，乐观返回
        } else {
            QuroTtsProviderPrefs.isConfigured(appCtx)
        }
    }

    fun stop() {
        QuroTtsHolder.stop()
    }

    fun shutdown() {
        QuroTtsHolder.stop()
        QuroTtsHolder.reset()
    }
}
