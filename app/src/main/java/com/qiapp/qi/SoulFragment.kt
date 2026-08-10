package com.qiapp.qi

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.chip.Chip
import com.qiapp.qi.databinding.FragmentSoulBinding
import java.io.File

class SoulFragment : Fragment(R.layout.fragment_soul) {

    private var _b: FragmentSoulBinding? = null
    private val b get() = _b!!

    private val idx get() = AppState.currentSoul

    /** 选图 → 复制到私有缓存（避免跨 Activity 的 uri 权限失效）→ 进交互式裁剪页 */
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        val tmp = copyUriToCache(uri)
        if (tmp == null) { toast("无法读取图片"); return@registerForActivityResult }
        val intent = Intent(requireContext(), AvatarCropActivity::class.java).apply {
            putExtra("src", tmp.absolutePath)
            putExtra("idx", idx)
        }
        cropLauncher.launch(intent)
    }

    /** 接收裁剪结果：裁剪页已写出 avatar_$idx.png，这里落盘并刷新 */
    private val cropLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val path = res.data?.getStringExtra("path")
            if (path != null) {
                Config.setSoulAvatar(idx, path)
                showAvatar()
                toast("头像已更新 ❤")
            } else {
                toast("裁剪未返回图片")
            }
        }
    }

    /** 把 content uri 复制到 app 私有缓存，供裁剪页无权限顾虑地读取 */
    private fun copyUriToCache(uri: Uri): File? {
        return try {
            val ext = if (uri.toString().contains("png", ignoreCase = true)) "png" else "jpg"
            val f = File(requireContext().cacheDir, "crop_src.$ext")
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                f.outputStream().use { out -> input.copyTo(out) }
            }
            if (f.length() > 0) f else null
        } catch (e: Exception) { null }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _b = FragmentSoulBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)
        setupListeners()
        bindSoul()
    }

    /** 切了灵魂再进本页（或返回本页）时重新绑定当前灵魂，避免编辑页停留旧灵魂数据。 */
    override fun onResume() {
        super.onResume()
        if (_b != null) bindSoul()
    }

    override fun onPause() {
        super.onPause()
        if (_b != null) saveAll()
    }

    /** 把当前灵魂(idx)的全部设定回填界面；可重复调用（切灵魂 / 返回页面时刷新）。 */
    private fun bindSoul() {
        val i = idx
        val s = AppState.soul()
        showAvatar(s)
        b.soulName.setText(AppState.soulDisplayName())
        b.soulDesc.setText(AppState.soulDisplayDesc())

        // 首次把布局默认设定落盘，确保真的注入 system prompt
        if (Config.soulSystem(i).isBlank()) {
            Config.setSoulProfile(i, b.editSystem.text.toString(), b.editChat.text.toString(), b.editVoice.text.toString())
        }
        b.editSystem.setText(Config.soulSystem(i).ifBlank { b.editSystem.text.toString() })
        b.editChat.setText(Config.soulChat(i))
        b.editVoice.setText(Config.soulVoice(i))

        // 标签：先清旧的再按当前灵魂重渲（避免 onResume 重复累加）
        b.tagGroup.removeAllViews()
        Config.soulTags(i).forEach { addTag(it) }

        refreshHatchSub()
    }

    /** 统一头像显示：有上传图则不染色、居中裁切铺满；无图则显示对应渐变 + 白色默认图标。统一圆形（对齐 ZorvAI） */
    private fun showAvatar(s: Soul = AppState.soul()) {
        val av = AppState.soulAvatarFile()
        if (av != null) {
            b.soulAva.background = null
            b.soulAva.imageTintList = null
            b.soulAva.scaleType = ImageView.ScaleType.CENTER_CROP
            b.soulAva.setImageBitmap(BitmapFactory.decodeFile(av.absolutePath))
        } else {
            b.soulAva.background = ContextCompat.getDrawable(requireContext(), s.gradRes)
            b.soulAva.setImageResource(R.drawable.ic_soul)
            b.soulAva.imageTintList = ColorStateList.valueOf(Color.WHITE)
            b.soulAva.scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        b.soulAva.toCircle()
    }

    private fun setupListeners() {
        b.cameraBtn.setOnClickListener { pickImage.launch("image/*") }
        b.avaFrame.setOnClickListener { pickImage.launch("image/*") }

        accordion(b.hdrSystem, b.contentSystem, b.chevSystem)
        accordion(b.hdrChat, b.contentChat, b.chevChat)
        accordion(b.hdrVoice, b.contentVoice, b.chevVoice)

        // 灵魂卡编辑持久化
        b.soulName.setOnFocusChangeListener { _, has -> if (!has) saveSoulNameDesc() }
        b.soulDesc.setOnFocusChangeListener { _, has -> if (!has) saveSoulNameDesc() }

        // 角色设定三框：失焦即持久化，真实进入 LlmClient.buildSystemPrompt
        b.editSystem.setOnFocusChangeListener { _, has -> if (!has) saveProfile() }
        b.editChat.setOnFocusChangeListener { _, has -> if (!has) saveProfile() }
        b.editVoice.setOnFocusChangeListener { _, has -> if (!has) saveProfile() }

        b.hatchBtn.setOnClickListener { saveAll(); hatch() }

        b.tagInput.setOnEditorActionListener { _, _, _ ->
            val t = b.tagInput.text.toString().trim()
            if (t.isNotEmpty()) addTag(t)
            b.tagInput.text?.clear()
            true
        }
    }

    private fun saveSoulNameDesc() {
        Config.setSoul(idx, b.soulName.text.toString(), b.soulDesc.text.toString())
    }
    private fun saveProfile() {
        Config.setSoulProfile(idx, b.editSystem.text.toString(), b.editChat.text.toString(), b.editVoice.text.toString())
    }
    private fun saveAll() {
        saveSoulNameDesc()
        saveProfile()
        persistTags()
    }

    private fun refreshHatchSub() {
        val h = Config.soulHatch(idx)
        b.hatchSub.text = if (h > 0) "已陪你成长 $h 次 · 让她在聊天中慢慢长出自己的脾气"
        else "让小栖在聊天中慢慢长出自己的脾气"
    }

    private fun accordion(header: View, content: View, chev: ImageView) {
        header.setOnClickListener {
            val open = content.visibility == View.GONE
            content.visibility = if (open) View.VISIBLE else View.GONE
            chev.rotation = if (open) 90f else 0f
        }
    }

    /**
     * 真实 AI 孵化：把最近的聊天 + 当前人格设定交给 LLM，让它生成一句「刚刚长出的脾气」，
     * 持久化为成长印记并注入 system prompt（LlmClient.buildSystemPrompt 会读取）。
     * 不再是用假进度条 + 计数器自增糊弄。
     */
    private fun hatch() {
        if (Config.apiKey.isBlank()) {
            toast("请先在「设置→模型配置」填好 API Key，她才能思考着长大")
            return
        }
        val i = idx
        b.hatchBtn.isEnabled = false
        b.hatchProgress.visibility = View.VISIBLE
        b.hatchProgress.isIndeterminate = true

        val name = Config.soulName(i)
        val hatchSystem = buildString {
            append("你是「$name」的「人格孵化引擎」。请基于我们最近的聊天，")
            append("提炼出她在与我相处中刚刚「长出」的一句新脾气或新认知（一个具体的性格侧面）。\n")
            append("只输出一句中文（不超过 22 字），不要解释、不要引号、不要标点包裹。")
            append("示例：「更敢在我面前撒娇了」「学会在我低落时安静陪着」。")
        }
        LlmClient.chat(requireContext(), hatchSystem, LlmClient.buildHistory(), object : LlmClient.ChatCallback {
            override fun onToken(delta: String) { /* 孵化过程不流式呈现，完成时整体展示 */ }
            override fun onDone(full: String) {
                if (_b == null || !isAdded) return
                val mark = full.trim().replace("\n", " ").takeIf { it.isNotBlank() } ?: "又悄悄懂事了一点"
                Config.addSoulHatchMark(i, mark)
                Config.bumpSoulHatch(i)
                b.hatchProgress.isIndeterminate = false
                b.hatchProgress.visibility = View.GONE
                b.hatchBtn.isEnabled = true
                b.hatchSub.text = "刚长出的脾气：$mark"
                toast("小栖又长大了 ❤ 已陪你成长 ${Config.soulHatch(i)} 次")
            }
            override fun onError(msg: String) {
                if (_b == null || !isAdded) return
                b.hatchProgress.isIndeterminate = false
                b.hatchProgress.visibility = View.GONE
                b.hatchBtn.isEnabled = true
                toast("这次孵化没能连上：$msg")
            }
        })
    }

    /** 标签 chip：对齐 ZorvAI 的玫瑰色描边风格（圆角 + 玫瑰描边 + 浅玫瑰底 + 可移除 ×） */
    private fun addTag(tag: SoulTag) {
        if (tag.isBlank()) return
        val rose = ContextCompat.getColor(requireContext(), R.color.rose)
        val chip = Chip(requireContext()).apply {
            this.text = tag.name
            this.tag = tag
            isCloseIconVisible = true
            chipStrokeColor = ColorStateList.valueOf(rose)
            chipStrokeWidth = 1.5f
            chipBackgroundColor = ColorStateList.valueOf(0x1AE86A8C.toInt()) // 玫瑰 @ ~10% 透明
            setTextColor(rose)
            closeIconTint = ColorStateList.valueOf(rose)
            setOnCloseIconClickListener {
                b.tagGroup.removeView(this)
                persistTags()
            }
            // 点击标签（非关闭图标）编辑 hint / json（完整标签，对齐 ZorvAI）
            setOnClickListener { editTag(this) }
        }
        b.tagGroup.addView(chip)
        persistTags()
    }
    private fun addTag(text: String) = addTag(SoulTag(text))

    /** 编辑标签：名称 / 提示内容(hint) / 附加行为配置(json)，对齐 ZorvAI QuroTag 三层 */
    private fun editTag(chip: Chip) {
        val tag = (chip.tag as? SoulTag) ?: return
        val ctx = requireContext()
        val nameEt = EditText(ctx).apply { setText(tag.name); hint = "标签名" }
        val hintEt = EditText(ctx).apply { setText(tag.hint); hint = "提示内容（注入「语气标签」）" }
        val jsonEt = EditText(ctx).apply { setText(tag.json); hint = "附加行为配置（JSON，可选）" }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(48, 24, 48, 24) }
            nameEt.layoutParams = p; hintEt.layoutParams = p; jsonEt.layoutParams = p
            addView(nameEt); addView(hintEt); addView(jsonEt)
        }
        AlertDialog.Builder(ctx)
            .setTitle("编辑标签")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val nt = SoulTag(
                    nameEt.text.toString().trim(),
                    hintEt.text.toString().trim(),
                    jsonEt.text.toString().trim()
                )
                if (nt.isBlank()) { b.tagGroup.removeView(chip); persistTags(); return@setPositiveButton }
                chip.tag = nt
                chip.text = nt.name
                persistTags()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun persistTags() {
        val list = mutableListOf<SoulTag>()
        for (i in 0 until b.tagGroup.childCount) {
            (b.tagGroup.getChildAt(i) as? Chip)?.let { (it.tag as? SoulTag)?.let { list.add(it) } }
        }
        Config.setSoulTags(idx, list)
    }

    private fun toast(s: String) {
        android.widget.Toast.makeText(requireContext(), s, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
