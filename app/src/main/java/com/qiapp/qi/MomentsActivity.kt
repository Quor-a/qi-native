package com.qiapp.qi

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import android.content.res.ColorStateList
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 朋友圈页：AI 与用户共同的社交时间流。
 * - 合并流：两个灵魂的 AI 动态 + 用户自己的动态，按时间倒序展示（模仿聊天软件朋友圈）。
 * - 右下悬浮按钮：弹窗选择「我发一条」（用户发帖）或「让她发一条」（AI 按心情自动发）。
 * - 右上「添加贴纸」：从相册选图加入表情包，之后 AI 发动态时能用上。
 * - 每条动态支持「点赞」（toggle）与「评论」；在 AI 的动态下评论后，AI 会以第一人称简短回复这条评论。
 */
class MomentsActivity : AppCompatActivity() {

    private lateinit var adapter: MomentsAdapter
    private val dateFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    private val pickSticker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        val path = copySticker(uri)
        if (path != null) {
            Config.addUserSticker(path)
            toast("已加入表情包，她之后能用啦")
        } else {
            toast("这张图加不进去，换个试试？")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_moments)

        findViewById<ImageButton>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<TextView>(R.id.addStickerBtn).setOnClickListener { pickSticker.launch("image/*") }

        val list = findViewById<RecyclerView>(R.id.momentsList)
        list.layoutManager = LinearLayoutManager(this)
        adapter = MomentsAdapter()
        list.adapter = adapter

        findViewById<FloatingActionButton>(R.id.postFab).setOnClickListener {
            openPostChooser()
        }

        refresh()
        // 首次进入且当前灵魂的 AI 动态为空：静默填充一条，保证朋友圈不空白（AI 自己的内容，不读用户本地）
        val idx = AppState.currentSoul
        if (Config.moments(idx).isEmpty() && !Config.momentsSeeded(idx)) {
            Config.setMomentsSeeded(idx, true)
            MomentPublisher.publish(this, idx) { refresh() }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        adapter.submit(Config.feedMoments())
    }

    /** 发帖选择：我发一条 / 让她发一条 */
    private fun openPostChooser() {
        AlertDialog.Builder(this)
            .setTitle("发一条朋友圈")
            .setItems(arrayOf("我发一条", "让她发一条")) { _, which ->
                if (which == 0) composeUserPost() else publishAiPost()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 用户手动发一条：弹输入法填文案，落盘到「用户动态」。 */
    private fun composeUserPost() {
        val et = EditText(this).apply {
            hint = "说点什么…"
            setPadding(48, 36, 48, 36)
        }
        AlertDialog.Builder(this)
            .setTitle("我发一条")
            .setView(et)
            .setPositiveButton("发布") { _, _ ->
                val text = et.text.toString().trim()
                if (text.isBlank()) { toast("写点内容吧~"); return@setPositiveButton }
                Config.addUserMoment(
                    Config.Moment(
                        id = "u_${System.currentTimeMillis()}",
                        soulIdx = AppState.currentSoul,
                        text = text.take(200),
                        mood = "", emotion = "", sticker = "",
                        ts = System.currentTimeMillis()
                    )
                )
                refresh()
                toast("已发布到你的朋友圈")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 让 AI 按当下心情 + 记忆自动发一条。 */
    private fun publishAiPost() {
        val idx = AppState.currentSoul
        toast("正在让她发一条…")
        MomentPublisher.publish(this, idx) { msg ->
            toast(msg)
            refresh()
        }
    }

    /** 在 AI 的动态下评论后，让该灵魂以第一人称简短回复这条评论。 */
    private fun aiReplyComment(m: Config.Moment, userComment: String) {
        val soulIdx = m.soulIdx
        val name = Config.soulName(soulIdx)
        val userName = Config.userName()
        val system = buildString {
            append("你是「$name」，${if (Config.soulDesc(soulIdx).isNotBlank()) "${Config.soulDesc(soulIdx)}。" else ""}你正在回复一条朋友圈评论，用第一人称，像真人朋友互动。\n")
            append("你发的那条朋友圈是：「${m.text}」\n")
            append("用户「$userName」评论说：「$userComment」\n")
            append("请用第一人称，简短、自然、口语化地回复这条评论（≤30字，可带一个 emoji，不要解释、不要引号包裹、不要分点）。")
        }
        LlmClient.chat(this, system, emptyList(), object : LlmClient.ChatCallback {
            override fun onToken(delta: String) {}
            override fun onDone(full: String) {
                val reply = full.trim().replace("\n", " ").takeIf { it.isNotBlank() }?.take(80)
                if (reply != null) {
                    Config.updateMoment(m.id) { it.copy(comments = it.comments + Config.Comment("soul:$soulIdx", reply, System.currentTimeMillis())) }
                    runOnUiThread { refresh() }
                }
            }
            override fun onError(msg: String) {
                runOnUiThread { toast("她这会儿没接上，评论已留下啦") }
            }
        }, withTools = false)
    }

    private fun commentAuthorName(c: Config.Comment): String {
        return if (c.author == "me") Config.userName()
        else {
            val si = c.author.removePrefix("soul:").toIntOrNull() ?: 0
            Config.soulName(si)
        }
    }

    private fun bindAvatar(av: ImageView, m: Config.Moment) {
        val userFile = Config.userAvatar().takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() }
        val soulFile = if (Config.isUserMoment(m)) null else Config.soulAvatar(m.soulIdx).takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() }
        when {
            Config.isUserMoment(m) && userFile != null -> {
                av.background = null; av.imageTintList = null; av.scaleType = ImageView.ScaleType.CENTER_CROP
                av.setImageURI(Uri.fromFile(userFile))
            }
            Config.isUserMoment(m) -> {
                av.background = ContextCompat.getDrawable(this, R.color.ink_faint)
                av.setImageResource(R.drawable.ic_user); av.imageTintList = ColorStateList.valueOf(Color.WHITE)
                av.scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
            soulFile != null -> {
                av.background = null; av.imageTintList = null; av.scaleType = ImageView.ScaleType.CENTER_CROP
                av.setImageURI(Uri.fromFile(soulFile))
            }
            else -> {
                val s = AppState.baseSouls.getOrElse(m.soulIdx) { AppState.baseSouls[0] }
                av.background = ContextCompat.getDrawable(this, s.gradRes)
                av.setImageResource(R.drawable.ic_soul); av.imageTintList = ColorStateList.valueOf(Color.WHITE)
                av.scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
        }
        av.toCircle()
    }

    private fun toast(s: String) =
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

    inner class MomentsAdapter : RecyclerView.Adapter<MomentsAdapter.VH>() {

        private val data = mutableListOf<Config.Moment>()

        fun submit(list: List<Config.Moment>) {
            data.clear()
            data.addAll(list)
            notifyDataSetChanged()
        }

        override fun getItemCount() = data.size

        override fun onCreateViewHolder(parent: android.view.ViewGroup, type: Int): VH {
            val v = layoutInflater.inflate(R.layout.item_moment, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, pos: Int) {
            val m = data[pos]
            h.name.text = Config.momentAuthorName(m)
            val meta = buildString {
                append(dateFmt.format(Date(m.ts)))
                if (m.mood.isNotBlank()) append(" · 心情 ${m.mood}")
            }
            h.meta.text = meta
            bindAvatar(h.ava, m)
            // 文案
            if (m.text.isNotBlank()) {
                h.text.text = m.text
                h.text.visibility = View.VISIBLE
            } else {
                h.text.visibility = View.GONE
            }
            // 贴纸
            StickerPack.apply(m.sticker, h.stickerImg, h.stickerEmoji)

            // 点赞
            val liked = m.likedByMe
            h.like.setColorFilter(ContextCompat.getColor(h.itemView.context, if (liked) R.color.rose else R.color.ink_soft))
            h.likeCount.text = if (m.likes > 0) m.likes.toString() else ""
            h.like.setOnClickListener {
                Config.updateMoment(m.id) {
                    it.copy(likedByMe = !it.likedByMe, likes = it.likes + if (!it.likedByMe) 1 else -1)
                }
                refresh()
            }

            // 评论数 + 点击评论
            h.commentCount.text = if (m.comments.isNotEmpty()) m.comments.size.toString() else ""
            h.comment.setOnClickListener { openCommentInput(m) }

            // 渲染评论列表
            h.comments.removeAllViews()
            m.comments.forEach { c ->
                val row = TextView(h.itemView.context).apply {
                    text = "${commentAuthorName(c)}：${c.text}"
                    textSize = 13f
                    setTextColor(ContextCompat.getColor(context, R.color.ink_soft))
                    val pad = (4 * resources.displayMetrics.density).toInt()
                    setPadding(0, pad, 0, pad)
                }
                h.comments.addView(row)
            }
        }

        private fun openCommentInput(m: Config.Moment) {
            val et = EditText(this@MomentsActivity).apply {
                hint = "评论一下…"
                setPadding(48, 36, 48, 36)
            }
            AlertDialog.Builder(this@MomentsActivity)
                .setTitle("评论")
                .setView(et)
                .setPositiveButton("发送") { _, _ ->
                    val text = et.text.toString().trim()
                    if (text.isBlank()) return@setPositiveButton
                    Config.updateMoment(m.id) {
                        it.copy(comments = it.comments + Config.Comment("me", text.take(200), System.currentTimeMillis()))
                    }
                    refresh()
                    // AI 的动态下评论：让该灵魂回复一句
                    if (!Config.isUserMoment(m)) {
                        toast("已评论，她可能会回一句~")
                        aiReplyComment(m, text)
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        inner class VH(v: android.view.View) : RecyclerView.ViewHolder(v) {
            val ava: ImageView = v.findViewById(R.id.mAva)
            val name: TextView = v.findViewById(R.id.mName)
            val meta: TextView = v.findViewById(R.id.mMeta)
            val text: TextView = v.findViewById(R.id.mText)
            val stickerImg: ImageView = v.findViewById(R.id.mStickerImg)
            val stickerEmoji: TextView = v.findViewById(R.id.mStickerEmoji)
            val like: ImageView = v.findViewById(R.id.mLike)
            val likeCount: TextView = v.findViewById(R.id.mLikeCount)
            val comment: ImageView = v.findViewById(R.id.mComment)
            val commentCount: TextView = v.findViewById(R.id.mCommentCount)
            val comments: LinearLayout = v.findViewById(R.id.mComments)
        }
    }

    /** 把相册选中的图复制到应用私有 stickers 目录，返回绝对路径。 */
    private fun copySticker(uri: Uri): String? = try {
        val dir = File(getExternalFilesDir(null), "stickers")
        if (!dir.exists()) dir.mkdirs()
        val ext = contentResolver.getType(uri)?.let { if (it.contains("png")) "png" else "jpg" } ?: "png"
        val file = File(dir, "user_${System.currentTimeMillis()}.$ext")
        contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { out -> input.copyTo(out) }
        }
        file.absolutePath
    } catch (_: Exception) { null }
}
