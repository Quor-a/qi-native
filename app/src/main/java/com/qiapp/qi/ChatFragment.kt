package com.qiapp.qi

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import java.io.File
import java.util.Locale
import com.qiapp.qi.databinding.FragmentChatBinding
import com.qiapp.qi.databinding.ItemMsgFileBinding
import com.qiapp.qi.databinding.ItemMsgTextBinding
import com.qiapp.qi.databinding.ItemMsgTypingBinding
import com.qiapp.qi.databinding.ItemMsgVoiceBinding
import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import kotlin.math.abs
import kotlin.math.roundToInt

class ChatFragment : Fragment(R.layout.fragment_chat) {

    private var _b: FragmentChatBinding? = null
    private val b get() = _b!!
    private lateinit var adapter: ChatAdapter
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var tts: TtsEngine
    private var generating = false
    private var replyPos = -1
    private var replyIsVoice = false
    private val replyBuf = StringBuilder()
    /** 当前正在播放语音的语音气泡位置（-1 表示无）；驱动播放图标与波形动画 */
    private var playingPos = -1

    // 对话框内「语音聊天」(STT→LLM→TTS 连续对话) 状态
    private var vcActive = false
    private var vcListening = false
    private var vcStt: SttHelper? = null
    private var vcEmptyCount = 0
    private var pendingVoiceChat = false

    private val reqMicVc = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startVoiceChat() else toast("需要麦克风权限才能语音聊天")
    }

    /** 输入框上传：选图后复制到私有目录，作为待发送附件（真实上传，不再假实现） */
    private var pendingImage: String? = null
    private val pickAttach = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        val path = copyAttachToFiles(uri)
        if (path == null) { toast("无法读取图片"); return@registerForActivityResult }
        pendingImage = path
        showAttachPreview(path)
    }
    private fun copyAttachToFiles(uri: Uri): String? = try {
        val ext = if (uri.toString().contains("png", ignoreCase = true)) "png" else "jpg"
        val f = File(requireContext().filesDir, "attach_${System.currentTimeMillis()}.$ext")
        requireContext().contentResolver.openInputStream(uri)?.use { input ->
            f.outputStream().use { out -> input.copyTo(out) }
        }
        if (f.length() > 0) f.absolutePath else null
    } catch (_: Exception) { null }
    private fun showAttachPreview(path: String) {
        b.attachPreview.visibility = View.VISIBLE
        b.attachThumb.setImageBitmap(BitmapFactory.decodeFile(path))
    }
    private fun clearAttachPreview() {
        pendingImage = null
        b.attachPreview.visibility = View.GONE
        b.attachThumb.setImageBitmap(null)
    }
    private val pickBg = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        try {
            val out = File(requireContext().filesDir, "chatbg.png")
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
            Config.chatBg = out.absolutePath
            applyChatBg()
        } catch (e: Exception) {
            toast("背景设置失败：${e.message}")
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _b = FragmentChatBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        // 载入当前会话（多会话历史）；首次启动自动创建空白会话并落盘
        AppState.loadCurrent(requireContext())

        b.chatRecycler.layoutManager = LinearLayoutManager(requireContext())
        adapter = ChatAdapter()
        b.chatRecycler.adapter = adapter

        refreshHeader()
        b.chatHeader.setOnClickListener { openSoulSheet() }
        // 头像不再跳转独立形象界面（形象已合体到语音球，悬浮窗常驻）
        b.chatMenu.setOnClickListener { openMenu(it) }

        b.modelPill.setOnClickListener { openModelSheet() }

        b.btnAttach.setOnClickListener { pickAttach.launch("image/*") }
        b.attachRemove.setOnClickListener { clearAttachPreview() }
        b.btnSend.setOnClickListener { send() }
        b.inputBox.setOnEditorActionListener { _, _, _ -> send(); true }
        b.btnVoiceChat.setOnClickListener { toggleVoiceChat() }

        b.autoplaySwitch.isChecked = Config.voiceReply
        b.autoplaySwitch.setOnCheckedChangeListener { _, c -> Config.voiceReply = c }

        tts = TtsEngine(requireContext())
        applyChatBg()
        // 注册文件注入监听：AI 经 send_file 工具推送的文件气泡会即时刷新到本适配器
        ChatInjection.setListener { pos ->
            if (!isAdded) return@setListener
            adapter.notifyItemInserted(pos)
            b.chatRecycler.scrollToPosition(pos)
        }
        if (pendingVoiceChat) { pendingVoiceChat = false; startVoiceChat() }
    }

    private fun refreshHeader() {
        val s = AppState.soul()
        b.chatName.text = AppState.soulDisplayName()
        applyAvatar(b.chatAva)
        b.modelPillName.text = AppState.modelName()
        setPresence(false)
    }

    /** 像正常聊天软件那样：对方正在回复时显示「正在输入…」，否则显示「在线」+ 模型。 */
    private fun setPresence(typing: Boolean) {
        if (!isAdded) return
        if (typing) {
            b.chatStatus.text = "正在输入…"
            b.modelPillName.visibility = View.GONE
        } else {
            b.chatStatus.setText(R.string.online)
            b.modelPillName.visibility = View.VISIBLE
        }
    }

    /** 统一头像显示：有上传图则不染色、居中裁切铺满；无图则显示对应渐变 + 白色默认图标。统一圆形（对齐 ZorvAI） */
    private fun applyAvatar(iv: ImageView) {
        val av = AppState.soulAvatarFile()
        if (av != null) {
            iv.background = null
            iv.imageTintList = null
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setImageURI(Uri.fromFile(av))
        } else {
            val s = AppState.soul()
            iv.background = ContextCompat.getDrawable(iv.context, s.gradRes)
            iv.setImageResource(R.drawable.ic_soul)
            iv.imageTintList = ColorStateList.valueOf(Color.WHITE)
            iv.scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        iv.toCircle()
    }

    private fun send() {
        if (generating) return
        val t = b.inputBox.text.toString().trim()
        if (t.isEmpty() && pendingImage == null) return
        val img = pendingImage
        AppState.messages.add(TextMsg(t, true, img))
        b.inputBox.text.clear()
        clearAttachPreview()
        adapter.notifyItemInserted(AppState.messages.size - 1)
        b.chatRecycler.scrollToPosition(AppState.messages.size - 1)

        val history = LlmClient.buildHistory()
        replyIsVoice = Config.voiceReply
        AppState.messages.add(TypingMsg)
        replyPos = AppState.messages.size - 1
        replyBuf.clear()
        adapter.notifyItemInserted(replyPos)
        b.chatRecycler.scrollToPosition(replyPos)

        generating = true
        b.btnSend.isEnabled = false
        setPresence(true)
        LlmClient.chat(requireContext(), LlmClient.buildSystemPrompt(requireContext()), history, object : LlmClient.ChatCallback {
            override fun onToken(delta: String) {
                if (!isAdded) return
                // 首 token 到达：把「正在输入」占位换成真实类型气泡，开始流式
                if (replyPos in AppState.messages.indices && AppState.messages[replyPos] is TypingMsg) {
                    AppState.messages[replyPos] = replyPlaceholder("")
                    adapter.notifyItemChanged(replyPos)
                }
                replyBuf.append(delta)
                val shown = QuroVoiceStyle.strip(replyBuf.toString())
                if (replyPos in AppState.messages.indices && AppState.messages[replyPos] !is TypingMsg) {
                    AppState.messages[replyPos] = replyPlaceholder(shown)
                    adapter.notifyItemChanged(replyPos)
                }
                b.chatRecycler.scrollToPosition(replyPos)
            }
            override fun onDone(full: String) {
                if (!isAdded) return
                finishTurn(full)
            }
            override fun onError(msg: String) {
                if (!isAdded) return
                // 把完整诊断（实际 URL + 脱敏 Key + 模型 + Key 是否为空）自动复制到剪贴板，
                // 用户发消息出现 401 后，直接把剪贴板内容粘贴给开发者即可，免去取日志/翻文件管理器。
                try {
                    val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("栖诊断", msg))
                    toast("诊断已复制到剪贴板，可直接粘贴发送")
                } catch (_: Exception) { /* 忽略 */ }
                finishTurn("⚠️ $msg\n\n请到「设置 → 模型配置」填写正确的端点与 API Key。")
            }
        })
    }

    private fun finishTurn(raw: String) {
        val finalRaw = raw.ifBlank { "(对方没有回应)" }
        val shown = QuroVoiceStyle.strip(finalRaw)
        if (replyPos in AppState.messages.indices) {
            AppState.messages[replyPos] = if (replyIsVoice) VoiceMsg(shown, durOf(shown)) else TextMsg(shown, false)
            adapter.notifyItemChanged(replyPos)
        }
        AppState.persistCurrent(requireContext())
        generating = false
        b.btnSend.isEnabled = true
        setPresence(false)
        // 把 AI 回复情绪广播给动态形象（开启实时联动时，形象会跟着变表情/光照）
        AvatarBus.emitEmotion(EmotionAnalyzer.analyze(finalRaw).key)
        // LLM 驱动身体：非语音回复时也把整段文本交给 3D 形象做语义手势
        // （语音回复会由 TtsEngine → AvatarBus.beginSpeech 统一驱动口形 + 身体，此处不重复）
        if (!replyIsVoice && finalRaw.isNotBlank() && !finalRaw.startsWith("⚠️")) {
            AvatarBus.driveBodyOnly(shown)
        }
        if (replyIsVoice && finalRaw.isNotBlank() && !finalRaw.startsWith("⚠️")) {
            if (replyPos in AppState.messages.indices) {
                playingPos = replyPos
                adapter.notifyItemChanged(replyPos)
            }
            tts.speak(finalRaw, true) {
                if (isAdded) {
                    playingPos = -1
                    if (replyPos in AppState.messages.indices) adapter.notifyItemChanged(replyPos)
                }
            }
        }
    }

    /** 根据当前「语音回复」开关，返回流式占位消息（类型在整段对话中恒定，避免 RecyclerView 类型切换崩溃）。 */
    private fun replyPlaceholder(text: String): Any =
        if (replyIsVoice) VoiceMsg(text, 0) else TextMsg(text, false)

    /** 估算语音时长（秒）：中文约 4 字/秒，至少 1 秒。 */
    private fun durOf(text: String): Int = ((text.length / 4.0).roundToInt()).coerceAtLeast(1)

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).roundToInt()

    /** 在语音气泡里绘制一条条竖线波形（按文本 hash 生成稳定随机形状）。 */
    private fun buildWave(container: LinearLayout, text: String) {
        container.removeAllViews()
        val n = 22
        val rnd = java.util.Random((text.hashCode().toLong() and 0x7fffffffL).coerceAtLeast(1))
        val baseH = 18.dp
        for (i in 0 until n) {
            val f = 0.35f + rnd.nextFloat() * 0.65f
            val h = (baseH * f).toInt().coerceAtLeast(4.dp)
            val bar = View(container.context)
            bar.layoutParams = LinearLayout.LayoutParams(3.dp, h)
            bar.setBackgroundColor(ContextCompat.getColor(container.context, R.color.rose))
            container.addView(bar)
        }
    }

    private fun openMenu(anchor: View) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add("历史对话")
            menu.add("新对话")
            menu.add("清空对话")
            menu.add(if (Config.chatBg.isBlank()) "聊天背景" else "更换聊天背景")
            menu.add("恢复默认背景")
            setOnMenuItemClickListener {
                when (it.title) {
                    "历史对话" -> {
                        startActivity(android.content.Intent(requireContext(), HistoryActivity::class.java))
                    }
                    "新对话" -> {
                        AppState.newConversation(requireContext())
                        adapter.notifyDataSetChanged()
                        toast("已开始新对话")
                    }
                    "清空对话" -> {
                        AppState.messages.clear()
                        AppState.autoPlayed = false
                        AppState.persistCurrent(requireContext())
                        adapter.notifyDataSetChanged()
                        toast("已清空当前对话")
                    }
                    "恢复默认背景" -> {
                        Config.chatBg = ""
                        applyChatBg()
                        toast("已恢复默认背景")
                    }
                    else -> pickBg.launch("image/*")
                }
                true
            }
        }.show()
    }

    private fun applyChatBg() {
        val path = Config.chatBg
        if (path.isBlank()) {
            b.chatRecycler.background = null
            return
        }
        val f = File(path)
        if (!f.exists()) { b.chatRecycler.background = null; return }
        try {
            val bmp = BitmapFactory.decodeFile(path)
            if (bmp != null) {
                b.chatRecycler.background = BitmapDrawable(resources, bmp)
                return
            }
        } catch (_: Exception) { /* 解码失败则清空 */ }
        b.chatRecycler.background = null
    }

    private fun openSoulSheet() {
        val d = BottomSheetDialog(requireContext())
        d.setContentView(R.layout.dialog_soul_switch)
        val c0 = d.findViewById<View>(R.id.soulRow0)!!
        val c1 = d.findViewById<View>(R.id.soulRow1)!!
        val k0 = d.findViewById<ImageView>(R.id.soulCheck0)!!
        val k1 = d.findViewById<ImageView>(R.id.soulCheck1)!!
        fun mark() {
            k0.visibility = if (AppState.currentSoul == 0) View.VISIBLE else View.GONE
            k1.visibility = if (AppState.currentSoul == 1) View.VISIBLE else View.GONE
        }
        mark()
        c0.setOnClickListener {
            AppState.currentSoul = 0; Config.currentSoul = 0; refreshHeader(); adapter.notifyDataSetChanged(); mark(); d.dismiss()
        }
        c1.setOnClickListener {
            AppState.currentSoul = 1; Config.currentSoul = 1; refreshHeader(); adapter.notifyDataSetChanged(); mark(); d.dismiss()
        }
        d.findViewById<View>(R.id.editSoulBtn)!!.setOnClickListener {
            d.dismiss()
            (requireActivity() as MainActivity).binding.bottomNav.selectedItemId = R.id.nav_soul
        }
        d.show()
    }

    private fun openModelSheet() {
        val d = BottomSheetDialog(requireContext())
        d.setContentView(R.layout.dialog_model_switch)
        val list = d.findViewById<android.widget.LinearLayout>(R.id.modelList)!!
        d.findViewById<android.widget.TextView>(R.id.currentModelHint)?.text = "当前：${AppState.modelName()}"

        fun hostOf(url: String): String {
            val u = url.trim()
            if (u.isBlank()) return "未配置"
            val host = u.replaceFirst(Regex("^https?://"), "").substringBefore('/').substringBefore('?')
            return if (host.isBlank()) "未配置" else host
        }

        // 模型切换 = 从「已保存模板」动态列表里加载一条为当前激活配置（对齐 ZorvAI / QuroAI）。
        val profiles = Config.savedProfiles().loadAll()
        if (profiles.isEmpty()) {
            val empty = layoutInflater.inflate(R.layout.saved_profile_row, list, false)
            empty.findViewById<android.widget.TextView>(R.id.rowTitle).text = "暂无已保存模板"
            empty.findViewById<android.widget.TextView>(R.id.rowSub).text = "去「模型配置」填好并点「保存为预设」"
            empty.findViewById<android.widget.Button>(R.id.rowLoad).visibility = View.GONE
            empty.findViewById<android.widget.Button>(R.id.rowDelete).visibility = View.GONE
            list.addView(empty)
        } else {
            profiles.forEach { p ->
                val row = layoutInflater.inflate(R.layout.saved_profile_row, list, false)
                row.findViewById<android.widget.TextView>(R.id.rowTitle).text = p.name.ifBlank { "未命名模板" }
                row.findViewById<android.widget.TextView>(R.id.rowSub).text =
                    "${Config.providerLabel(p.providerIdx)} · ${p.model.ifBlank { "?" }} · ${hostOf(p.baseUrl)}"
                row.findViewById<android.widget.Button>(R.id.rowLoad).setOnClickListener {
                    Config.savedProfiles().applyToConfig(p)
                    refreshHeader()
                    toast("已切换为「${p.name}」")
                    d.dismiss()
                }
                row.findViewById<android.widget.Button>(R.id.rowDelete).setOnClickListener {
                    Config.savedProfiles().delete(p.id)
                    list.removeView(row)
                    toast("已删除模板「${p.name}」")
                }
                list.addView(row)
            }
        }

        d.findViewById<View>(R.id.detailCfgBtn)!!.setOnClickListener {
            d.dismiss()
            startActivity(android.content.Intent(requireContext(), ModelConfigActivity::class.java))
        }
        d.show()
    }

    // ---------------- 对话框内语音聊天 (STT→LLM→TTS) ----------------
    // 与悬浮语音球同一套连续对话逻辑，但绑定本对话框的适配器，消息直接写入聊天流。
    private companion object {
        const val VC_BACKOFF_MS = 600L
        const val VC_MAX_EMPTY = 3
        const val TYPE_TEXT = 0
        const val TYPE_VOICE = 1
        const val TYPE_TYPING = 2
        const val TYPE_FILE = 3
    }

    private fun toggleVoiceChat() {
        if (vcActive) stopVoiceChat() else startVoiceChat()
    }

    /** 供设置页「语音聊天」入口调用：视图已就绪则立即开始，否则延迟到 onViewCreated。 */
    fun launchVoiceChat() {
        if (_b != null) startVoiceChat() else pendingVoiceChat = true
    }

    private fun startVoiceChat() {
        if (!SttHelper(requireContext(), object : SttHelper.Cb {}).isAvailable()) {
            toast("本机未提供语音识别引擎"); return
        }
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            reqMicVc.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        vcActive = true
        vcEmptyCount = 0
        b.btnVoiceChat.setImageResource(R.drawable.ic_close)
        b.btnVoiceChat.contentDescription = "结束语音聊天"
        b.vcStatus.visibility = View.VISIBLE
        vcStatus("聆听中…")
        vcListen()
    }

    private fun stopVoiceChat() {
        vcActive = false
        vcListening = false
        vcEmptyCount = 0
        vcStt?.stop()
        vcStt?.destroy()
        vcStt = null
        tts.stop()
        b.btnVoiceChat.setImageResource(R.drawable.ic_mic)
        b.btnVoiceChat.contentDescription = "语音聊天"
        b.vcStatus.visibility = View.GONE
    }

    private fun vcListen() {
        if (!vcActive || !isAdded) return
        vcListening = true
        vcStatus("聆听中…")
        vcStt = SttHelper(requireContext(), object : SttHelper.Cb {
            override fun onPartial(text: String) {
                if (text.isNotBlank()) vcStatus("聆听中：$text")
            }
            override fun onFinal(text: String) {
                if (!vcActive || !isAdded) return
                vcListening = false
                if (text.isNotBlank()) {
                    vcEmptyCount = 0
                    vcStatus("你说：$text")
                    vcProcess(text)
                } else {
                    vcOnEmptyOrError("没听清")
                }
            }
            override fun onError(msg: String) {
                if (!vcActive || !isAdded) return
                vcListening = false
                vcOnEmptyOrError(msg)
            }
        })
        vcStt?.start()
    }

    /** 空结果/出错：累计计数，超过上限自动结束；否则退避一小段再续听。 */
    private fun vcOnEmptyOrError(reason: String) {
        if (!vcActive || !isAdded) return
        vcEmptyCount++
        if (vcEmptyCount > VC_MAX_EMPTY) {
            vcStatus("连续无语音，已结束")
            stopVoiceChat()
            return
        }
        vcStatus("$reason，稍后重试")
        handler.postDelayed({ if (vcActive) vcListen() }, VC_BACKOFF_MS)
    }

    private fun vcProcess(text: String) {
        if (Config.apiKey.isBlank()) {
            vcStatus("未配置 API Key")
            vcSpeak("请先在模型配置页填写 API Key") { if (vcActive) vcListen() }
            return
        }
        // 写入用户消息
        AppState.messages.add(TextMsg(text, true))
        adapter.notifyItemInserted(AppState.messages.size - 1)
        b.chatRecycler.scrollToPosition(AppState.messages.size - 1)
        // 预留助手消息占位并流式填充
        val history = LlmClient.buildHistory()
        replyIsVoice = Config.voiceReply
        AppState.messages.add(TypingMsg)
        replyPos = AppState.messages.size - 1
        replyBuf.clear()
        adapter.notifyItemInserted(replyPos)
        b.chatRecycler.scrollToPosition(replyPos)

        generating = true
        b.btnSend.isEnabled = false
        setPresence(true)
        vcStatus("思考中…")
        LlmClient.chat(requireContext(), LlmClient.buildSystemPrompt(requireContext()), history, object : LlmClient.ChatCallback {
            override fun onToken(delta: String) {
                if (!isAdded) return
                if (replyPos in AppState.messages.indices && AppState.messages[replyPos] is TypingMsg) {
                    AppState.messages[replyPos] = replyPlaceholder("")
                    adapter.notifyItemChanged(replyPos)
                }
                replyBuf.append(delta)
                val shown = QuroVoiceStyle.strip(replyBuf.toString())
                if (replyPos in AppState.messages.indices && AppState.messages[replyPos] !is TypingMsg) {
                    AppState.messages[replyPos] = replyPlaceholder(shown)
                    adapter.notifyItemChanged(replyPos)
                }
                b.chatRecycler.scrollToPosition(replyPos)
            }
            override fun onDone(full: String) {
                if (!isAdded) return
                val finalRaw = full.ifBlank { "(对方没有回应)" }
                val shown = QuroVoiceStyle.strip(finalRaw)
                if (replyPos in AppState.messages.indices) {
                    AppState.messages[replyPos] = if (replyIsVoice) VoiceMsg(shown, durOf(shown)) else TextMsg(shown, false)
                    adapter.notifyItemChanged(replyPos)
                }
                AppState.persistCurrent(requireContext())
                generating = false
                b.btnSend.isEnabled = true
                if (vcActive) {
                    vcStatus("回复中…")
                    if (replyIsVoice && replyPos in AppState.messages.indices) {
                        playingPos = replyPos
                        adapter.notifyItemChanged(replyPos)
                    }
                    vcSpeak(finalRaw) {
                        if (replyIsVoice) {
                            playingPos = -1
                            if (replyPos in AppState.messages.indices) adapter.notifyItemChanged(replyPos)
                        }
                        if (vcActive) vcListen()
                    }
                }
            }
            override fun onError(msg: String) {
                if (!isAdded) return
                val err = "⚠️ $msg\n\n请到「设置 → 模型配置」填写正确的端点与 API Key。"
                if (replyPos in AppState.messages.indices) {
                    AppState.messages[replyPos] = if (replyIsVoice) VoiceMsg(err, durOf(err)) else TextMsg(err, false)
                    adapter.notifyItemChanged(replyPos)
                }
                AppState.persistCurrent(requireContext())
                generating = false
                b.btnSend.isEnabled = true
                setPresence(false)
                if (vcActive) vcListen()
            }
        })
    }

    private fun vcSpeak(text: String, onDone: () -> Unit) {
        tts.speak(text) { if (isAdded) onDone() }
    }

    private fun vcStatus(s: String) {
        if (isAdded) b.vcStatus.text = s
    }

    private fun toast(s: String) {
        android.widget.Toast.makeText(requireContext(), s, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** 在应用内直接预览 AI 发送的文件 / 图片 / 文档（自写预览架构，不再甩给外部查看器）。 */
    private fun previewFile(file: File, mime: String, name: String) {
        if (!file.exists()) { toast("文件已不存在：$name"); return }
        startActivity(FilePreviewActivity.createIntent(requireContext(), file.absolutePath, mime, name))
    }

    /** 字节数 → 人类可读大小。 */
    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(Locale.US, kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(Locale.US, mb)
        return "%.1f GB".format(Locale.US, mb / 1024.0)
    }

    /** 文件类型的中文标签（用于气泡副标题）。 */
    private fun typeLabel(mime: String, name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            mime.startsWith("image/") -> "图片"
            mime.startsWith("text/markdown") || ext == "md" -> "Markdown 文档"
            mime.startsWith("text/html") || ext == "html" || ext == "htm" -> "网页"
            mime.startsWith("application/json") || ext == "json" -> "JSON"
            mime.startsWith("text/csv") || ext == "csv" -> "CSV 表格"
            mime == "application/pdf" || ext == "pdf" -> "PDF 文档"
            mime.startsWith("text/") || ext in listOf("txt", "md", "xml", "css", "yaml", "yml", "log") -> "文本文档"
            ext in listOf("py", "js", "kt", "java", "c", "cpp", "h", "hpp", "sh", "go", "rs") -> "代码文件"
            else -> "文件"
        }
    }

    override fun onResume() {
        super.onResume()
        if (_b != null) {
            // 从历史对话页返回后，按 currentConvId 重新载入对应会话
            AppState.loadCurrent(requireContext())
            refreshHeader()
            adapter.notifyDataSetChanged()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (vcActive) stopVoiceChat()
        ChatInjection.setListener(null)
        LlmClient.cancel()
        tts.shutdown()
        _b = null
    }

    // ---------------- adapter ----------------
    inner class ChatAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(p: Int): Int =
            when (AppState.messages[p]) {
                is VoiceMsg -> TYPE_VOICE
                is TypingMsg -> TYPE_TYPING
                is FileMsg -> TYPE_FILE
                else -> TYPE_TEXT
            }

        override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return when (type) {
                TYPE_VOICE -> VViewHolder(ItemMsgVoiceBinding.inflate(inf, parent, false))
                TYPE_TYPING -> TypingViewHolder(ItemMsgTypingBinding.inflate(inf, parent, false))
                TYPE_FILE -> FViewHolder(ItemMsgFileBinding.inflate(inf, parent, false))
                else -> TViewHolder(ItemMsgTextBinding.inflate(inf, parent, false))
            }
        }

        override fun getItemCount() = AppState.messages.size

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            val m = AppState.messages[pos]
            when {
                h is VViewHolder && m is VoiceMsg -> h.bind(m)
                h is TypingViewHolder -> h.bind()
                h is FViewHolder && m is FileMsg -> h.bind(m)
                h is TViewHolder && m is TextMsg -> h.bind(m)
            }
        }

        override fun onViewRecycled(h: RecyclerView.ViewHolder) {
            if (h is TypingViewHolder) h.stopTyping()
            if (h is VViewHolder) h.stopWaveAnim()
            super.onViewRecycled(h)
        }

        inner class TViewHolder(val x: ItemMsgTextBinding) : RecyclerView.ViewHolder(x.root) {
            fun bind(m: TextMsg) {
                x.bubble.text = m.text
                if (m.imagePath != null) {
                    x.imgAttach.visibility = View.VISIBLE
                    x.imgAttach.setImageBitmap(BitmapFactory.decodeFile(m.imagePath))
                } else {
                    x.imgAttach.visibility = View.GONE
                    x.imgAttach.setImageBitmap(null)
                }
                if (m.me) {
                    x.root.gravity = Gravity.END
                    x.mini.visibility = View.GONE
                    x.bubble.setBackgroundResource(R.drawable.bg_bubble_me)
                } else {
                    x.root.gravity = Gravity.START
                    x.mini.visibility = View.VISIBLE
                    applyAvatar(x.mini)
                    x.bubble.setBackgroundResource(R.drawable.bg_bubble_them)
                }
            }
        }

        inner class FViewHolder(val x: ItemMsgFileBinding) : RecyclerView.ViewHolder(x.root) {
            fun bind(m: FileMsg) {
                // 头像与对齐（AI 发送→左侧带头像；用户发送→右侧无头像）
                if (m.me) {
                    x.root.gravity = Gravity.END
                    x.mini.visibility = View.GONE
                } else {
                    x.root.gravity = Gravity.START
                    x.mini.visibility = View.VISIBLE
                    applyAvatar(x.mini)
                }

                x.name.text = m.name
                x.meta.text = "${formatSize(m.size)} · ${typeLabel(m.mime, m.name)}"

                // 图片类：能解码则显示缩略图；否则用图标占位
                if (m.isImage) {
                    val bmp = try { BitmapFactory.decodeFile(m.path) } catch (_: Exception) { null }
                    if (bmp != null) {
                        x.thumb.visibility = View.VISIBLE
                        x.thumb.setImageBitmap(bmp)
                        x.icon.setImageResource(R.drawable.ic_qi_img)
                    } else {
                        x.thumb.visibility = View.GONE
                        x.icon.setImageResource(R.drawable.ic_qi_img)
                    }
                } else {
                    x.thumb.visibility = View.GONE
                    x.icon.setImageResource(R.drawable.ic_qi_doc)
                }

                val file = File(m.path)
                x.card.setOnClickListener {
                    if (!file.exists()) { toast("文件已不存在：${m.name}"); return@setOnClickListener }
                    previewFile(file, m.mime, m.name)
                }
                x.thumb.setOnClickListener {
                    if (file.exists()) previewFile(file, m.mime, m.name)
                }
            }
        }

        inner class TypingViewHolder(val x: ItemMsgTypingBinding) : RecyclerView.ViewHolder(x.root) {
            private var anims: List<Animator>? = null
            fun bind() {
                applyAvatar(x.mini)
                startTyping()
            }
            private fun startTyping() {
                stopTyping()
                anims = listOf(x.dot1, x.dot2, x.dot3).mapIndexed { i, dot ->
                    ObjectAnimator.ofPropertyValuesHolder(
                        dot,
                        android.animation.PropertyValuesHolder.ofFloat("scaleX", 0.35f, 1f),
                        android.animation.PropertyValuesHolder.ofFloat("scaleY", 0.35f, 1f),
                        android.animation.PropertyValuesHolder.ofFloat("alpha", 0.3f, 1f)
                    ).apply {
                        duration = 500
                        repeatCount = ValueAnimator.INFINITE
                        repeatMode = ValueAnimator.REVERSE
                        startDelay = (i * 160).toLong()
                        start()
                    }
                }
            }
            fun stopTyping() {
                anims?.forEach { it.cancel() }
                anims = null
                listOf(x.dot1, x.dot2, x.dot3).forEach { it.alpha = 1f; it.scaleX = 1f; it.scaleY = 1f }
            }
        }

        inner class VViewHolder(val x: ItemMsgVoiceBinding) : RecyclerView.ViewHolder(x.root) {
            private var waveAnim: Animator? = null
            fun bind(m: VoiceMsg) {
                x.dur.text = "${m.durSec}″"
                x.mini.visibility = View.VISIBLE
                applyAvatar(x.mini)
                buildWave(x.wave, m.text)
                refreshPlayState()
                x.playBtn.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) togglePlay(pos, m)
                }
            }
            /** 根据当前是否正在播放，切换播放/暂停图标并启停波形动画 */
            fun refreshPlayState() {
                val playing = bindingAdapterPosition == playingPos
                x.playBtn.setImageResource(if (playing) R.drawable.ic_pause_voice else R.drawable.ic_play_voice)
                if (playing) startWaveAnim() else stopWaveAnim()
            }
            private fun startWaveAnim() {
                stopWaveAnim()
                val anims = (0 until x.wave.childCount).map { x.wave.getChildAt(it) }.mapIndexed { i, bar ->
                    ObjectAnimator.ofFloat(bar, "scaleY", 0.3f, 1f).apply {
                        duration = 420
                        repeatCount = ValueAnimator.INFINITE
                        repeatMode = ValueAnimator.REVERSE
                        startDelay = (i * 28).toLong()
                        start()
                    }
                }
                waveAnim = AnimatorSet().apply { playTogether(anims); start() }
            }
            fun stopWaveAnim() {
                waveAnim?.cancel()
                waveAnim = null
                for (i in 0 until x.wave.childCount) x.wave.getChildAt(i).scaleY = 1f
            }
        }
    }

    /** 语音气泡点击：正在播放则停止，否则停止其他并播放本条；停止/播放结束都会刷新该行 UI */
    private fun togglePlay(pos: Int, m: VoiceMsg) {
        if (playingPos == pos) {
            tts.stop()
            playingPos = -1
            adapter.notifyItemChanged(pos)
            return
        }
        val old = playingPos
        playingPos = -1
        if (old != RecyclerView.NO_POSITION && old != pos) adapter.notifyItemChanged(old)
        playingPos = pos
        adapter.notifyItemChanged(pos)
        tts.speak(m.text, true) {
            if (isAdded) {
                playingPos = -1
                if (pos in AppState.messages.indices) adapter.notifyItemChanged(pos)
            }
        }
    }
}
