package com.qiapp.qi

import android.content.Context

/**
 * AI 心跳（资料库提示词 ↔ 记忆库 ↔ 人格卡 三件套的「自动孵化」侧）。
 *
 * 在应用进程存活期间，周期性地把三件套交给 [PersonaIncubator] 自动孵化，
 * 让「栖」在用户不操作的时候也慢慢长大、自我升级——不用手动点。
 *
 * 触发条件（同时满足才孵化，省 token）：
 *   1. 距上次成功孵化 ≥ 设定间隔（默认 30 分钟，[Config.aiHeartbeatMinutes]）；
 *   2. 这段时间里有新的对话消息（[AppState.messages] 增长了）。
 * 否则只打盹，不调用模型。
 *
 * 在 [QiApplication.onCreate] 启动；整段包在 try/catch 里，绝不拖慢正常启动。
 * 实现为进程内守护线程（非前台 Service），不引入新权限、不动 Manifest，低风险落地。
 */
object AiHeartbeat {
    @Volatile private var started = false
    @Volatile private var running = false
    private var snapshottedMsgCount = 0
    private var beatCount = 0

    @Synchronized
    fun start(ctx: Context) {
        if (started) return
        started = true
        running = true
        snapshottedMsgCount = AppState.messages.size
        Thread({ loop(ctx.applicationContext) }, "qi-heartbeat").start()
    }

    fun stop() {
        running = false
    }

    private fun loop(ctx: Context) {
        while (running) {
            try {
                Thread.sleep(60_000L) // 每分钟检查一次，不空转
            } catch (_: InterruptedException) {
                break
            }
            if (!running) break
            if (!Config.aiHeartbeatOn) continue
            if (Config.apiKey.isBlank()) continue

            val idx = AppState.currentSoul
            val now = System.currentTimeMillis()
            val elapsed = now - Config.aiHeartbeatLast()
            val interval = Config.aiHeartbeatMinutes() * 60_000L
            val grew = AppState.messages.size > snapshottedMsgCount

            if (elapsed >= interval && grew) {
                // 先落时间戳，避免孵化期间重复触发
                Config.setAiHeartbeatLast(now)
                snapshottedMsgCount = AppState.messages.size
                // 静默孵化：结果不弹窗，下次对话自然体现
                PersonaIncubator.incubate(ctx, idx) { /* no-op：心跳产物是「悄悄长大」 */ }
                // 每两次心跳，让她按当下心情发一条朋友圈（也是悄悄的）
                beatCount++
                if (beatCount % 2 == 0) {
                    MomentPublisher.publish(ctx, idx) { /* no-op：朋友圈产物在「朋友圈」页可见 */ }
                }
            }
        }
    }
}
