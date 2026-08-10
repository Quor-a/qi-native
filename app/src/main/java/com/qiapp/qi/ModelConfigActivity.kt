package com.qiapp.qi

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.text.Editable
import android.text.TextWatcher
import java.util.UUID
import android.widget.AdapterView
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import com.qiapp.qi.databinding.ActivityModelConfigBinding

class ModelConfigActivity : AppCompatActivity() {

    private lateinit var b: ActivityModelConfigBinding

    // 预设服务商统一取自 Config.PROVIDERS（单一数据源，聊天页 provider 标签共用）。
    private val providers = Config.PROVIDERS

    private var loadingSlot = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityModelConfigBinding.inflate(layoutInflater)
        setContentView(b.root)

        findViewById<TextView>(R.id.titleText).text = "模型配置 · v${BuildConfig.VERSION_NAME}"
        findViewById<ImageView>(R.id.backBtn).setOnClickListener { finish() }

        val labels = providers.map { it.label }
        b.providerSpin.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        b.providerSpin.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (loadingSlot) return
                val p = providers.getOrNull(position) ?: return
                if (p.endpoint.isNotBlank()) b.apiEndpointEdit.setText(p.endpoint)
                if (p.model.isNotBlank()) b.apiModelEdit.setText(p.model)
                persistActive()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        b.tempSeek.progress = (Config.temperature * 100).toInt().coerceIn(0, 100)
        b.tempVal.text = "%.2f".format(Config.temperature)
        b.memSeek.progress = Config.memoryRounds
        b.memVal.text = "${Config.memoryRounds} 轮"
        b.lenSeek.progress = Config.lengthMode
        b.lenVal.text = lenLabel(Config.lengthMode)
        b.streamSwitch.isChecked = Config.stream
        b.thinkSwitch.isChecked = Config.think

        b.tempSeek.setOnSeekBarChangeListener(seek { v -> b.tempVal.text = "%.2f".format(v / 100f); persistActive() })
        b.memSeek.setOnSeekBarChangeListener(seek { v -> b.memVal.text = "$v 轮"; persistActive() })
        b.lenSeek.setOnSeekBarChangeListener(seek { v -> b.lenVal.text = lenLabel(v); persistActive() })
        b.streamSwitch.setOnCheckedChangeListener { _, _ -> persistActive() }
        b.thinkSwitch.setOnCheckedChangeListener { _, _ -> persistActive() }

        // 三个编辑框实时落盘（对齐 ZorvAI / QuroAI 的「每次编辑即存」），
        // 避免「填了 Key 没点保存、直接回聊天 → 激活配置仍是空 → 401」这类问题。
        val tw = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) { persistActive() }
            override fun afterTextChanged(s: Editable?) {}
        }
        b.apiEndpointEdit.addTextChangedListener(tw)
        b.apiKeyEdit.addTextChangedListener(tw)
        b.apiModelEdit.addTextChangedListener(tw)

        b.testConnBtn.setOnClickListener { runTestConn() }
        b.fetchModelsBtn.setOnClickListener { loadModelList() }
        // 「保存为预设」：把当前激活配置存为一条命名模板，加入动态「已保存模板」列表。
        b.addModelBtn.setOnClickListener { saveAsPreset() }
        b.saveModelBtn.setOnClickListener { saveActive() }

        loadActive()
        renderProfiles()
    }

    private fun seek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { onChange(p) }
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }

    private fun lenLabel(v: Int) = when (v) { 0 -> "短"; 1 -> "中"; 2 -> "长"; else -> "中" }

    /**
     * 把编辑框里的实时值写入「当前激活配置」并落盘。
     * 对齐 ZorvAI / QuroAI 的「每次编辑即存」：聊天、TTS、STT 都直接读这份激活配置，
     * 因此编辑框里填什么，聊天就用什么，无需额外点保存按钮也可生效。
     * 空配置（端点与模型名都空白）不落库，避免把激活配置清成空导致 401。
     */
    private fun persistActive() {
        val ep = b.apiEndpointEdit.text.toString().trim()
        val key = b.apiKeyEdit.text.toString().trim()
        val model = b.apiModelEdit.text.toString().trim()
        // 仅当三者全空才跳过落库，避免把激活配置清成空；
        // 用户「只填了 Key」这类部分填写也要照常持久化，否则仍会 401。
        if (ep.isBlank() && model.isBlank() && key.isBlank()) return
        Config.provider = b.providerSpin.selectedItemPosition
        Config.endpoint = ep
        Config.apiKey = key
        Config.setModelName(model)
        Config.temperature = (b.tempSeek.progress / 100f).coerceIn(0f, 2f)
        Config.memoryRounds = b.memSeek.progress
        Config.lengthMode = b.lenSeek.progress
        Config.stream = b.streamSwitch.isChecked
        Config.think = b.thinkSwitch.isChecked
    }

    /** 把当前激活配置载入编辑区（单一激活配置，无槽概念）。 */
    private fun loadActive() {
        loadingSlot = true
        b.providerSpin.setSelection(Config.provider.coerceIn(0, providers.lastIndex))
        // 用 rawXxx 预填编辑框：空白即空白，不回退旧全局端点 / 默认模型名，避免幽灵默认值。
        b.apiEndpointEdit.setText(Config.rawEndpoint())
        b.apiKeyEdit.setText(Config.rawApiKey())
        b.apiModelEdit.setText(Config.rawModel())
        // Spinner 的 setSelection 触发的 onItemSelected 是异步投递到主线程队列的，
        // 若此处同步把 loadingSlot 置回 false，等回调真正执行时锁已失效，
        // 会用预设端点覆盖用户自定义端点。改为 post 到队尾，等回调回放完再解锁。
        b.providerSpin.post { loadingSlot = false }
    }

    /** 渲染「已保存模板」动态列表：每条可一键「加载」为当前激活配置，或「删除」。 */
    private fun renderProfiles() {
        b.modelList.removeAllViews()
        val profiles = Config.savedProfiles().loadAll()
        if (profiles.isEmpty()) {
            val empty = layoutInflater.inflate(R.layout.saved_profile_row, b.modelList, false)
            empty.findViewById<TextView>(R.id.rowTitle).text = "暂无已保存模板"
            empty.findViewById<TextView>(R.id.rowSub).text = "填好上方配置后点「保存为预设」即可收藏"
            empty.findViewById<Button>(R.id.rowLoad).visibility = View.GONE
            empty.findViewById<Button>(R.id.rowDelete).visibility = View.GONE
            b.modelList.addView(empty)
            return
        }
        profiles.forEach { p ->
            val row = layoutInflater.inflate(R.layout.saved_profile_row, b.modelList, false)
            row.findViewById<TextView>(R.id.rowTitle).text = p.name.ifBlank { "未命名模板" }
            row.findViewById<TextView>(R.id.rowSub).text =
                "${Config.providerLabel(p.providerIdx)} · ${p.model.ifBlank { "?" }} · ${hostOf(p.baseUrl)}"
            row.findViewById<Button>(R.id.rowLoad).setOnClickListener {
                Config.savedProfiles().applyToConfig(p)
                loadActive()
                Toast.makeText(this, "已加载模板「${p.name}」", Toast.LENGTH_SHORT).show()
                renderProfiles()
            }
            row.findViewById<Button>(R.id.rowDelete).setOnClickListener {
                Config.savedProfiles().delete(p.id)
                renderProfiles()
                Toast.makeText(this, "已删除模板「${p.name}」", Toast.LENGTH_SHORT).show()
            }
            b.modelList.addView(row)
        }
    }

    /** 把当前激活配置存为一条命名模板。 */
    /**
     * 把当前编辑框里实时填的值存成一条命名模板。
     * 关键修复：必须读编辑框里的实时值，而不是 [Config.toProfile]（它读的是「已持久化」的激活配置）。
     * 否则用户「填了 Key → 直接点保存为预设」时，模板会存进旧的/空的 Key，
     * 之后「加载模板」把空 Key 套进激活配置，聊天发出去就被网关拒成 401——这正是「已保存模板一直有问题」的根因。
     */
    private fun saveAsPreset() {
        val ep = b.apiEndpointEdit.text.toString().trim()
        val key = b.apiKeyEdit.text.toString().trim()
        val model = b.apiModelEdit.text.toString().trim()
        if (ep.isBlank() && model.isBlank()) {
            Toast.makeText(this, "请先填写端点或模型名", Toast.LENGTH_SHORT).show()
            return
        }
        // 先把编辑框的值落为当前激活配置（顺带保证激活配置也是最新的），再据此生成模板。
        persistActive()
        val count = Config.savedProfiles().loadAll().size
        val edit = EditText(this).apply {
            hint = "例如：我的 DeepSeek"
            setText("预设 ${count + 1}")
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("保存为预设")
            .setView(edit)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val name = edit.text.toString().trim().ifBlank { "预设 ${count + 1}" }
                val p = SavedProfile(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    providerIdx = b.providerSpin.selectedItemPosition,
                    baseUrl = ep,
                    apiKey = key,
                    model = model,
                    temperature = (b.tempSeek.progress / 100f).coerceIn(0f, 2f),
                    createdAt = System.currentTimeMillis(),
                )
                Config.savedProfiles().save(p)
                renderProfiles()
                Toast.makeText(this, "已保存模板「$name」", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun saveActive() {
        val ep = b.apiEndpointEdit.text.toString().trim()
        val model = b.apiModelEdit.text.toString().trim()
        if (ep.isBlank() && model.isBlank()) {
            Toast.makeText(this, "请至少填写端点或模型名", Toast.LENGTH_SHORT).show()
            return
        }
        persistActive()
        Toast.makeText(this, "已保存当前模型配置", Toast.LENGTH_SHORT).show()
        renderProfiles()
    }

    private fun hostOf(url: String): String {
        if (url.isBlank()) return "未配置"
        return try {
            val h = java.net.URL(url).host
            if (h.isBlank()) url else h
        } catch (_: Exception) { url }
    }

    private fun setConnButtonsEnabled(enabled: Boolean) {
        b.testConnBtn.isEnabled = enabled
        b.fetchModelsBtn.isEnabled = enabled
    }

    private fun runTestConn() {
        val ep = b.apiEndpointEdit.text.toString().trim()
        val key = b.apiKeyEdit.text.toString().trim()
        val model = b.apiModelEdit.text.toString().trim()
        if (ep.isBlank()) { b.connStatus.text = "请先填写端点地址"; return }
        setConnButtonsEnabled(false)
        b.connStatus.text = "连接测试中…"
        val url = LlmClient.diagUrl(ep)
        LlmClient.testConnection(ep, key, model) { ok, msg ->
            runOnUiThread {
                val full = if (ok) msg else buildString {
                    val cleanK = Config.cleanKey(key)
                    append("测试失败：$msg\n")
                    append("↳ 实际请求 URL：$url\n")
                    append("↳ 使用 Key：${Config.maskKey(cleanK)}（脱敏，长度 ${cleanK.length}）\n")
                    append("↳ Key 是否为空：${if (cleanK.isBlank()) "是（空→必然 401）" else "否（已填）"}\n")
                    append("↳ Key 含不可见字符：${if (Config.keyHasInvisible(key)) "是（已自动清洗：多为复制粘贴带入的零宽空格/BOM/换行）" else "否"}\n")
                    append("↳ Key 原始字节(hex)：${Config.keyHex(cleanK)}")
                }
                b.connStatus.text = full
                if (!ok) {
                    // 失败时把诊断复制到剪贴板，用户可直接粘贴发给开发者。
                    try {
                        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("栖诊断", full))
                        Toast.makeText(this, "诊断已复制，可直接粘贴发送", Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) { /* 忽略 */ }
                }
                setConnButtonsEnabled(true)
            }
        }
    }

    private fun loadModelList() {
        val ep = b.apiEndpointEdit.text.toString().trim()
        val key = b.apiKeyEdit.text.toString().trim()
        if (ep.isBlank()) { b.connStatus.text = "请先填写端点地址"; return }
        setConnButtonsEnabled(false)
        b.connStatus.text = "获取模型列表中…"
        LlmClient.fetchModels(ep, key) { ok, models, msg ->
            runOnUiThread {
                setConnButtonsEnabled(true)
                if (!ok) { b.connStatus.text = msg; return@runOnUiThread }
                if (models.isEmpty()) { b.connStatus.text = "未返回模型，请手动填写"; return@runOnUiThread }
                val items = models.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("选择模型")
                    .setItems(items) { _, which ->
                        b.apiModelEdit.setText(items[which])
                        b.connStatus.text = "已选择：${items[which]}"
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }
}
