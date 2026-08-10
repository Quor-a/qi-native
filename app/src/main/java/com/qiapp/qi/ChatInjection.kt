package com.qiapp.qi

import android.os.Handler
import android.os.Looper

/**
 * 轻量「消息注入总线」：让后台线程（如 ToolEngine 在执行 send_file 工具时）能安全地把
 * 一条新消息（FileMsg 等）追加到聊天流并触发 UI 刷新。
 *
 * 设计要点（对齐 AvatarBus 的事件总线思路，但只服务「追加消息」这一件事）：
 *  - 追加动作统一 post 到主线程，避免与 ChatFragment 的 RecyclerView 适配器在多线程下竞态；
 *  - ChatFragment 在 onViewCreated 注册监听、onDestroyView 注销；工具执行时若 UI 已就绪则即时刷新，
 *    若未就绪（极少数情况）消息仍会落入 AppState.messages，下次 notifyDataSetChanged 自然显示。
 */
object ChatInjection {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var listener: ((Int) -> Unit)? = null

    /** ChatFragment 调用：把注入事件转发给适配器。回调参数为被插入消息在列表中的下标。 */
    fun setListener(l: ((Int) -> Unit)?) {
        listener = l
    }

    /** 追加一条消息并刷新。可在任意线程调用。 */
    fun inject(msg: Any) {
        mainHandler.post {
            AppState.messages.add(msg)
            val pos = AppState.messages.size - 1
            listener?.invoke(pos)
        }
    }
}
