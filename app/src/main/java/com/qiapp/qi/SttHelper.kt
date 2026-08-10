package com.qiapp.qi

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer

/**
 * 语音识别封装（栖 —— 完整移植 ZorvAI 真实引擎）。
 *
 * 保留旧版对外 API（Cb / start / stop / destroy / isAvailable），
 * 内部委托给 [QuroSttRecorder]（VAD 静音断句 + 云端 Whisper 转写 / 原生 SpeechRecognizer）。
 *
 * 路由：
 *  - Config.sttProvider == 0 → 原生 SpeechRecognizer（QuroSttPrefs.SOURCE_LOCAL）
 *  - Config.sttProvider != 0 → 云端 Whisper（QuroSttPrefs.SOURCE_MODEL，
 *    模型/端点/Key 由 QuroModelConfigRepository 桥接到 Config 共享配置）
 */
class SttHelper(context: Context, private val cb: Cb) {

    interface Cb {
        fun onPartial(text: String) {}
        fun onFinal(text: String) {}
        fun onError(msg: String) {}
        fun onReady() {}
    }

    private val appCtx = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    init { syncSource() }

    private fun syncSource() {
        // 端侧离线模式由「语音服务」开关直接控制（SOURCE_ONDEVICE），不被此处按 Config.sttProvider 的覆盖逻辑改写
        if (QuroSttPrefs.getSource(appCtx) == QuroSttPrefs.SOURCE_ONDEVICE) return
        if (Config.sttProvider == 0) {
            QuroSttPrefs.setSource(appCtx, QuroSttPrefs.SOURCE_LOCAL)
        } else {
            QuroSttPrefs.setSource(appCtx, QuroSttPrefs.SOURCE_MODEL)
        }
        // 云端转写：选中「具体服务商」并同步其模型名（provider 带 openai/whisper 关键字 -> providerSupportsAudio 判真）
        val id = Config.sttProviderId.ifBlank { "openai" }
        QuroSttPrefs.setModelProvider(appCtx, id)
        val cfg = QuroSttProviderPrefs.getConfig(appCtx, id)
        QuroSttPrefs.setModelName(appCtx, cfg.model.ifBlank { "whisper-1" })
    }

    fun isAvailable(): Boolean {
        return if (Config.sttProvider == 0) {
            SpeechRecognizer.isRecognitionAvailable(appCtx)
        } else {
            QuroSttProviderPrefs.isConfigured(appCtx)
        }
    }

    fun start() {
        syncSource()
        QuroSttRecorder.recognize(
            context = appCtx,
            onStatus = { mainHandler.post { cb.onReady() } },
            onFinal = { text -> mainHandler.post { cb.onFinal(text) } },
            onError = { _, msg -> mainHandler.post { cb.onError(msg) } },
        )
    }

    fun stop() {
        QuroSttRecorder.stop()
    }

    fun destroy() {
        QuroSttRecorder.stop()
    }
}
