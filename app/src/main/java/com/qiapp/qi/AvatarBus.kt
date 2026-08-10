package com.qiapp.qi

import android.os.Handler
import android.os.Looper
import android.animation.ValueAnimator
import kotlin.math.sin
import kotlin.math.PI
import kotlin.math.pow

/**
 * 动态形象事件总线（v9 — 3D / LLM 驱动身体版）。
 *
 * 四条通道：
 *  1. [addAmp]  口形幅度 0..1（TTS 播放期间由包络动画驱动）
 *  2. [addEmo]  情绪 key（neutral/calm/happy/sad/angry/surprised）
 *  3. [addSay]  「说话文本 + 预计时长」——交给 3D 端做 **LLM 语义驱动身体**：
 *               WebGL 侧 `window.__avatar.say(text, durMs)` 会从文本里解析语义，
 *               排布点头 / 挥手 / 比心 / 摊手 / 思考 等具名手势。
 *  4. [addGesture] 直接指定一个具名手势（外部主动触发，如点击互动）
 *
 * 口形包络模型（沿用 v8 真人语速）：
 *  - 中文 ~2.5 字 / 音节，每音节 ~230ms（约 4.3 音节/秒）；
 *  - 每音节一次 sin² 开→闭脉冲，音节间嘴完全闭合；
 *  - 整体首尾 sin 淡入淡出。
 */
object AvatarBus {

    private val ampListeners = mutableSetOf<(Float) -> Unit>()
    private val emoListeners = mutableSetOf<(String) -> Unit>()
    private val sayListeners = mutableSetOf<(String, Long) -> Unit>()
    private val gestureListeners = mutableSetOf<(String) -> Unit>()
    private val endListeners = mutableSetOf<() -> Unit>()

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var env: ValueAnimator? = null

    fun addAmp(l: (Float) -> Unit) { ampListeners.add(l) }
    fun removeAmp(l: (Float) -> Unit) { ampListeners.remove(l) }
    fun addEmo(l: (String) -> Unit) { emoListeners.add(l) }
    fun removeEmo(l: (String) -> Unit) { emoListeners.remove(l) }
    fun addSay(l: (String, Long) -> Unit) { sayListeners.add(l) }
    fun removeSay(l: (String, Long) -> Unit) { sayListeners.remove(l) }
    fun addGesture(l: (String) -> Unit) { gestureListeners.add(l) }
    fun removeGesture(l: (String) -> Unit) { gestureListeners.remove(l) }
    fun addSpeechEnd(l: () -> Unit) { endListeners.add(l) }
    fun removeSpeechEnd(l: () -> Unit) { endListeners.remove(l) }

    fun emitAmp(v: Float) {
        for (l in ampListeners.toList()) l(v)
    }

    fun emitEmotion(key: String) {
        for (l in emoListeners.toList()) l(key)
    }

    /** 广播一个具名手势（nod/shake/wave/think/explain/point/shrug/heart/cheer/bow/clap/tilt/shy/stretch/hair/glance/thumb）。 */
    fun emitGesture(name: String) {
        for (l in gestureListeners.toList()) l(name)
    }

    /**
     * 广播「这段文本要被说出来」——3D 端据此做语义驱动身体。
     * [durMs] <= 0 时由接收端按文本长度自估。
     */
    fun emitSay(text: String, durMs: Long = 0L) {
        if (text.isBlank()) return
        for (l in sayListeners.toList()) l(text, durMs)
    }

    /** 估算一段文本的口播时长（毫秒），与 [beginSpeech] 保持同一模型。 */
    fun estimateDurMs(text: String): Long {
        val sylCount = maxOf(1, (text.length / 2.5).toInt())
        return (sylCount * 230L).coerceIn(800, 15_000)
    }

    /**
     * 纯文本回复（未走 TTS）时，只驱动身体语言，不做口形。
     * 让形象在「读」这段话时也有点头 / 摊手 / 思考等自然动作。
     */
    fun driveBodyOnly(text: String) {
        emitSay(text, estimateDurMs(text))
    }

    /** 开始一段「说话」：驱动口形包络 + 广播文本给 3D 端做语义手势。 */
    fun beginSpeech(text: String) {
        endSpeech()

        val sylCount = maxOf(1, (text.length / 2.5).toInt())
        val perSylMs = 230L
        val dur = (sylCount * perSylMs).coerceIn(800, 15_000)

        // LLM 驱动身体：把整段文本连同时长交给 3D 端排布手势
        emitSay(text, dur)

        val a = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = dur
            addUpdateListener {
                val t = it.animatedValue as Float

                // 连续相位：t ∈ [0,1] 映射到 sylCount 个完整音节周期
                val phase = t * sylCount
                val sylFrac = phase % 1f  // 当前音节内的局部进度 0..1

                // 单次音节脉冲：sin² 包络（0→1→0，首尾圆滑无突变）
                val pulse = sin(sylFrac * PI.toFloat()).pow(2).toFloat()

                // 每个音节的微小幅度扰动（确定性伪随机，让每次不完全一样）
                val ampVar = 0.82f + 0.18f * (
                    sin(phase * 17.3f + 1.7f).toFloat() * 0.5f +
                    sin(phase * 41.7f + 3.3f).toFloat() * 0.5f
                ).coerceIn(0f, 1f)

                // 全局淡入淡出（首尾各 ~15% 渐变）
                val fade = sin(t * Math.PI).toFloat()

                // 最终幅度：基础微开(6%) + 脉冲 × 扰动 × 淡出
                emitAmp((0.06f + pulse * ampVar * 0.88f) * fade)
            }
        }
        env = a
        a.start()
    }

    /** 结束说话：取消包络、嘴复位闭合，并通知 3D 端收束说话层。 */
    fun endSpeech() {
        env?.cancel()
        env = null
        emitAmp(0f)
        for (l in endListeners.toList()) l()
    }
}
