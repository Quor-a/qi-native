package com.qiapp.qi

import android.content.Context
import java.io.File

data class Soul(
    val name: String,
    val desc: String,
    val gradRes: Int
)

data class TextMsg(val text: String, val me: Boolean, val imagePath: String? = null)

/** AI 语音回复气泡：text 为朗读内容，durSec 为估算秒数，点击重听 */
data class VoiceMsg(val text: String, val durSec: Int)

/** AI 正在输入时的占位气泡（三跳点动画），回复首 token 到达即被替换为真实消息 */
object TypingMsg

/**
 * AI（或用户）发送的文件 / 图片 / 文档气泡。
 * path 为设备上的真实文件路径（写入应用私有 sent 目录，可被 FileProvider 打开）。
 * isImage=true 时在气泡内渲染缩略图，否则显示文件卡片（图标 + 文件名 + 大小）。
 */
data class FileMsg(
    val name: String,
    val path: String,
    val mime: String,
    val size: Long,
    val me: Boolean,
    val isImage: Boolean
)

object AppState {
    // 基础灵魂卡（可被用户在「灵魂注入」页编辑覆盖，覆盖值存于 Config）
    val baseSouls = listOf(
        Soul("小栖", "温柔的邻家女孩，喜欢陪你发呆", R.drawable.grad_xiaoqi),
        Soul("阿粲", "清冷的才女，爱聊文学与星空", R.drawable.grad_acan)
    )

    var currentSoul = 0
    var autoPlayed = false

    // 对话消息（仅文本气泡）；首启为空，无写死示例、无假数据。
    val messages = mutableListOf<Any>()

    /** 当前会话 id（多会话历史）；空串表示尚未绑定会话，loadCurrent 会自动创建 / 绑定。 */
    var currentConvId: String = ""

    /** 启动时从 Config 恢复灵魂覆盖（模型配置已是单一激活态，无需恢复槽位） */
    fun applyConfig() {
        if (!Config.isInit()) return
        currentSoul = Config.currentSoul
    }

    fun soul() = baseSouls[currentSoul]
    /** 当前激活模型名（单一激活配置）；未配置显示「未配置模型」便于 UI 提示。 */
    fun modelName() = Config.model.ifBlank { "未配置模型" }

    /** 当前灵魂显示名（含用户编辑覆盖） */
    fun soulDisplayName() = Config.soulName(currentSoul)
    fun soulDisplayDesc() = Config.soulDesc(currentSoul)

    /** 当前灵魂用户上传头像的本地文件；未设置或损坏则返回 null */
    fun soulAvatarFile(): File? {
        val p = Config.soulAvatar(currentSoul)
        if (p.isBlank()) return null
        val f = File(p)
        return if (f.exists()) f else null
    }

    // ---------------- 多会话历史 ----------------

    /** 载入当前会话：按 currentConvId；为空或找不到则取最近会话；列表空则新建空白会话。 */
    fun loadCurrent(ctx: Context) {
        val all = ConversationStore.list(ctx)
        val target = if (currentConvId.isNotBlank()) all.firstOrNull { it.id == currentConvId }
                    else all.firstOrNull()
        if (target != null) {
            currentConvId = target.id
            messages.clear()
            messages.addAll(target.messages)
        } else {
            currentConvId = ""
            messages.clear()
            val fresh = ConversationStore.create(ctx, "新对话")
            currentConvId = fresh.id
        }
    }

    /** 把内存中的 messages 持久化进当前会话（标题按首条用户消息自动派生）。 */
    fun persistCurrent(ctx: Context) {
        if (currentConvId.isBlank()) {
            val fresh = ConversationStore.create(ctx, "新对话")
            currentConvId = fresh.id
        }
        val conv = ConversationStore.get(ctx, currentConvId)
            ?: Conversation(currentConvId, "新对话", System.currentTimeMillis(), mutableListOf())
                .also { ConversationStore.upsert(ctx, it) }
        conv.messages.clear()
        conv.messages.addAll(messages)
        if (conv.title.isBlank() || conv.title == "新对话") {
            conv.title = deriveTitle(messages)
        }
        ConversationStore.upsert(ctx, conv)
    }

    /** 开新对话：清空内存并新建空白会话，绑定为当前。 */
    fun newConversation(ctx: Context) {
        val fresh = ConversationStore.create(ctx, "新对话")
        currentConvId = fresh.id
        messages.clear()
    }

    /** 删除当前会话并切到最近的另一会话（无则新建空白）。 */
    fun deleteCurrent(ctx: Context) {
        if (currentConvId.isNotBlank()) ConversationStore.delete(ctx, currentConvId)
        currentConvId = ""
        loadCurrent(ctx)
    }

    /** 清理全部会话并新建空白会话。 */
    fun clearAllConversations(ctx: Context) {
        ConversationStore.clearAll(ctx)
        currentConvId = ""
        loadCurrent(ctx)
    }

    /** 导入消息到当前会话并持久化。 */
    fun importIntoCurrent(ctx: Context, msgs: List<Any>) {
        messages.clear()
        messages.addAll(msgs)
        persistCurrent(ctx)
    }

    private fun deriveTitle(msgs: List<Any>): String {
        val firstUser = msgs.firstOrNull { it is TextMsg && it.me } as? TextMsg
        val raw = firstUser?.text?.trim().orEmpty()
        return if (raw.isBlank()) "新对话" else raw.take(20)
    }
}
