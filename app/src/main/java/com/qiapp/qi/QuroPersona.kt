package com.qiapp.qi

import android.content.Context

/**
 * 栖本地适配：ZorvAI 的 QuroPersona / QuroVoiceProfile / QuroPersonaRepository。
 *
 * 栖当前无「人格卡」概念，故 [QuroPersonaRepository.getActive] 恒返回空人格
 * （voiceSetting=""、voiceProfile=null）。TTS 引擎据此回落到全局配置，不会因空指针崩溃。
 * 仅保留 TTS 引擎实际访问的字段，避免搬运整个 QuroPersona 体系。
 */
data class QuroVoiceProfile(
    val providerId: String = "",
    val voiceId: String = "",
    val emotionEnabled: Boolean = false,
    val emotionTags: List<String> = emptyList(),
    val speed: Float = 1.0f,
)

data class QuroPersona(
    val voiceSetting: String = "",
    val voiceProfile: QuroVoiceProfile? = null,
)

class QuroPersonaRepository(val context: Context) {
    fun getActive(): QuroPersona = QuroPersona()
}
