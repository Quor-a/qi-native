package com.qiapp.qi

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 历史对话页：列出全部会话（按更新时间倒序），支持
 * - 点击某条 → 切换到该会话并返回聊天
 * - 每条右侧删除按钮 → 删除单条会话
 * - 右上「清理全部」→ 删除所有会话
 * - 右下悬浮按钮 → 新建会话
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var adapter: HistoryAdapter
    private val dateFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        findViewById<ImageButton>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<TextView>(R.id.clearAllBtn).setOnClickListener { confirmClearAll() }

        val list = findViewById<RecyclerView>(R.id.historyList)
        list.layoutManager = LinearLayoutManager(this)
        adapter = HistoryAdapter { switchTo(it) }
        list.adapter = adapter

        findViewById<FloatingActionButton>(R.id.newConvFab).setOnClickListener {
            AppState.newConversation(this)
            toast("已开始新对话")
            finish()
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        adapter.submit(ConversationStore.list(this))
    }

    private fun switchTo(conv: Conversation) {
        AppState.currentConvId = conv.id
        toast("已切换到：${conv.title.ifBlank { "新对话" }}")
        finish()
    }

    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setTitle("清理全部对话")
            .setMessage("将删除所有历史对话，且无法恢复。确定继续？")
            .setPositiveButton("清理全部") { _, _ ->
                AppState.clearAllConversations(this)
                toast("已清理全部对话")
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(s: String) =
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

    inner class HistoryAdapter(
        private val onItem: (Conversation) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.VH>() {

        private val data = mutableListOf<Conversation>()

        fun submit(list: List<Conversation>) {
            data.clear()
            data.addAll(list)
            notifyDataSetChanged()
        }

        override fun getItemCount() = data.size

        override fun onCreateViewHolder(parent: ViewGroup, type: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_conversation, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, pos: Int) {
            val c = data[pos]
            h.title.text = c.title.ifBlank { "新对话" }
            h.preview.text = lastSnippet(c.messages)
            h.meta.text = "${c.messages.size} 条 · ${dateFmt.format(Date(c.updatedAt))}"
            h.root.setOnClickListener { onItem(c) }
            h.delete.setOnClickListener {
                val name = c.title.ifBlank { "新对话" }
                AlertDialog.Builder(h.root.context)
                    .setTitle("删除对话")
                    .setMessage("确定删除「$name」？此操作不可恢复。")
                    .setPositiveButton("删除") { _, _ ->
                        ConversationStore.delete(this@HistoryActivity, c.id)
                        if (AppState.currentConvId == c.id) {
                            AppState.deleteCurrent(this@HistoryActivity)
                        }
                        refresh()
                        toast("已删除对话")
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }

        private fun lastSnippet(msgs: List<Any>): String {
            val last = msgs.lastOrNull() ?: return "（空对话）"
            val text = when (last) {
                is TextMsg -> last.text
                is VoiceMsg -> last.text
                else -> ""
            }
            return if (text.isBlank()) "（空对话）" else text.take(40)
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val root: View = v
            val title: TextView = v.findViewById(R.id.convTitle)
            val preview: TextView = v.findViewById(R.id.convPreview)
            val meta: TextView = v.findViewById(R.id.convMeta)
            val delete: ImageButton = v.findViewById(R.id.convDelete)
        }
    }
}
