package com.qiapp.qi

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.chip.Chip
import com.qiapp.qi.databinding.ActivityVoiceServiceBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VoiceServiceActivity : AppCompatActivity() {

    private lateinit var b: ActivityVoiceServiceBinding
    private lateinit var tts: TtsEngine
    private val mainHandler = Handler(Looper.getMainLooper())
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var onDeviceStatusTv: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityVoiceServiceBinding.inflate(layoutInflater)
        setContentView(b.root)

        findViewById<TextView>(R.id.titleText).text = "语音服务"
        findViewById<ImageButton>(R.id.backBtn).setOnClickListener { finish() }

        tts = TtsEngine(this)

        // ---- 载入已保存配置 ----
        b.speedSeek.progress = ((Config.ttsSpeed - 0.5f) * 100).toInt().coerceIn(0, 100)
        b.pitchSeek.progress = (((Config.ttsPitch - 1f) * 6) + 6).toInt().coerceIn(0, 12)
        b.silenceSeek.progress = Config.silenceMs
        b.autoplaySwitch.isChecked = Config.autoplay
        b.autopuncSwitch.isChecked = Config.autopunc
        b.emotionSwitch.isChecked = QuroVoiceFeaturePrefs.getEmotionTagsEnabled(this)
        b.voiceColorSwitch.isChecked = QuroVoiceFeaturePrefs.getVoiceColorRoutingEnabled(this)
        b.sttChatCompletionsSwitch.isChecked = QuroSttPrefs.getUseChatCompletions(this)
        b.ballEnableSwitch.isChecked = Config.ballEnabled
        (b.ballWakeChips.getChildAt(Config.ballWake) as? Chip)?.isChecked = true
        (b.ballSizeChips.getChildAt(Config.ballSize) as? Chip)?.isChecked = true
        (b.ballColorChips.getChildAt(Config.ballColor) as? Chip)?.isChecked = true
        b.ballRememberSwitch.isChecked = Config.ballRemember

        // ---- TTS ----
        b.speedSeek.setOnSeekBarChangeListener(sb { p ->
            b.speedVal.text = "%.1f×".format(0.5 + p / 100f)
            Config.ttsSpeed = 0.5f + p / 100f
        })
        b.pitchSeek.setOnSeekBarChangeListener(sb { p ->
            val v = p - 6
            b.pitchVal.text = if (v == 0) "0" else if (v > 0) "+$v" else "$v"
            Config.ttsPitch = 1f + v / 6f
        })
        b.autoplaySwitch.setOnCheckedChangeListener { _, c -> Config.autoplay = c }

        // ---- STT ----
        b.silenceSeek.setOnSeekBarChangeListener(sb { p ->
            b.silenceVal.text = "${p}ms"
            Config.silenceMs = p
            applyBallNow() // 断句静音实时影响进行中的识别会话
        })
        b.autopuncSwitch.setOnCheckedChangeListener { _, c -> Config.autopunc = c }
        b.emotionSwitch.setOnCheckedChangeListener { _, c -> QuroVoiceFeaturePrefs.setEmotionTagsEnabled(this, c) }
        b.voiceColorSwitch.setOnCheckedChangeListener { _, c -> QuroVoiceFeaturePrefs.setVoiceColorRoutingEnabled(this, c) }
        b.sttChatCompletionsSwitch.setOnCheckedChangeListener { _, c ->
            QuroSttPrefs.setUseChatCompletions(this, c)
            if (c) {
                android.widget.Toast.makeText(
                    this,
                    "已开启：云端转写将复用聊天模型端点，以多模态音频消息发送",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }

        // ---- TTS 服务商（完整目录：系统内置 + 13 云端）----
        val ttsItems = listOf("系统内置 TTS") + QuroTtsProviders.ALL.map { it.name }
        val ttsAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, ttsItems)
        ttsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        b.ttsProviderSpin.adapter = ttsAdapter
        val ttsPos = if (Config.ttsProvider == 0) 0 else {
            val i = QuroTtsProviders.ALL.indexOfFirst { it.id == Config.ttsProviderId }
            if (i < 0) 1 else i + 1
        }
        b.ttsProviderSpin.setSelection(ttsPos)
        b.ttsProviderSpin.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos == 0) {
                    Config.ttsProvider = 0
                    renderTtsConfig(null)
                } else {
                    Config.ttsProvider = 1
                    val def = QuroTtsProviders.ALL[pos - 1]
                    Config.ttsProviderId = def.id
                    QuroTtsProviderPrefs.setProvider(this@VoiceServiceActivity, def.id)
                    renderTtsConfig(def)
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        renderTtsConfig(if (ttsPos == 0) null else QuroTtsProviders.ALL[ttsPos - 1])

        // ---- STT 服务商（完整目录：系统原生 + 云端 Whisper）----
        val sttItems = listOf("系统原生识别") + QuroSttProviders.ALL.map { it.name }
        val sttAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, sttItems)
        sttAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        b.sttProviderSpin.adapter = sttAdapter
        val sttPos = if (Config.sttProvider == 0) 0 else {
            val i = QuroSttProviders.ALL.indexOfFirst { it.id == Config.sttProviderId }
            if (i < 0) 1 else i + 1
        }
        b.sttProviderSpin.setSelection(sttPos)
        b.sttProviderSpin.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos == 0) {
                    Config.sttProvider = 0
                    renderSttConfig(null)
                } else {
                    Config.sttProvider = 1
                    val def = QuroSttProviders.ALL[pos - 1]
                    Config.sttProviderId = def.id
                    QuroSttProviderPrefs.setProvider(this@VoiceServiceActivity, def.id)
                    renderSttConfig(def)
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        renderSttConfig(if (sttPos == 0) null else QuroSttProviders.ALL[sttPos - 1])

        // ---- 端侧离线 STT（本机模型，不联网） ----
        val onDevice = QuroSttPrefs.getSource(this) == QuroSttPrefs.SOURCE_ONDEVICE
        b.sttOnDeviceSwitch.isChecked = onDevice
        b.sttProviderSpin.isEnabled = !onDevice
        b.sttOnDeviceBox.visibility = if (onDevice) View.VISIBLE else View.GONE
        if (onDevice) renderOnDeviceConfig()
        b.sttOnDeviceSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                QuroSttPrefs.setSource(this, QuroSttPrefs.SOURCE_ONDEVICE)
                b.sttProviderSpin.isEnabled = false
                b.sttOnDeviceBox.visibility = View.VISIBLE
                renderOnDeviceConfig()
            } else {
                b.sttProviderSpin.isEnabled = true
                b.sttOnDeviceBox.visibility = View.GONE
                // 恢复到当前服务商选择（本地识别 / 云端模型）
                QuroSttPrefs.setSource(
                    this,
                    if (Config.sttProvider == 0) QuroSttPrefs.SOURCE_LOCAL else QuroSttPrefs.SOURCE_MODEL
                )
            }
        }

        // ---- 语音球 ----
        b.ballEnableSwitch.setOnCheckedChangeListener { _, c ->
            if (c && !hasMic()) {
                // 未授予麦克风：microphone 前台服务会 SecurityException 崩溃，先回退开关并引导授权
                b.ballEnableSwitch.isChecked = false
                Config.ballEnabled = false
                android.widget.Toast.makeText(this, "开启语音球需要先授予麦克风权限", android.widget.Toast.LENGTH_LONG).show()
                startActivity(Intent(this, PermissionsActivity::class.java))
                return@setOnCheckedChangeListener
            }
            Config.ballEnabled = c
            val svc = Intent(this@VoiceServiceActivity, VoiceBallService::class.java)
            if (c) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)
            } else {
                stopService(svc)
            }
        }
        b.ballSizeChips.setOnCheckedChangeListener { _, checkedId ->
            Config.ballSize = when (checkedId) { R.id.size2 -> 2; R.id.size0 -> 0; else -> 1 }
            applyBallNow()
        }
        b.ballColorChips.setOnCheckedChangeListener { _, checkedId ->
            Config.ballColor = when (checkedId) { R.id.col2 -> 2; R.id.col1 -> 1; else -> 0 }
            applyBallNow()
        }
        b.ballWakeChips.setOnCheckedChangeListener { _, checkedId ->
            Config.ballWake = when (checkedId) { R.id.wake1 -> 1; else -> 0 }
            applyBallNow()
        }
        b.ballRememberSwitch.setOnCheckedChangeListener { _, c ->
            Config.ballRemember = c
            applyBallNow()
        }

        b.previewBtn.setOnClickListener {
            android.widget.Toast.makeText(this, "正在试听「今天也辛苦啦」…", android.widget.Toast.LENGTH_SHORT).show()
            tts.speak("今天也辛苦啦")
        }
    }

    /** 根据选中的 TTS 服务商动态渲染配置项（描述 + 字段 + 音色 + 格式），并即时持久化到 [QuroTtsProviderPrefs]。 */
    private fun renderTtsConfig(def: QuroTtsProviderDef?) {
        val container = b.ttsProviderConfig
        container.removeAllViews()
        if (def == null) { b.ttsCloudBox.visibility = View.GONE; return }
        b.ttsCloudBox.visibility = View.VISIBLE
        b.ttsDescText.text = def.desc

        val cfg = QuroTtsProviderPrefs.getConfig(this, def.id)
        var voiceValue = cfg.voice
        var formatValue = cfg.format.ifBlank { def.defaultFormat }

        val fieldEdits = mutableMapOf<String, EditText>()
        for (f in def.fields) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, 0)
            }
            row.addView(TextView(this).apply { text = f.label; textSize = 13f })
            val edit = EditText(this).apply {
                setText(cfg.fields[f.key] ?: "")
                hint = f.placeholder
                textSize = 13f
                if (f.secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            fieldEdits[f.key] = edit
            row.addView(edit)
            container.addView(row)
        }

        // 音色：预置列表 → 下拉；自由输入 → 编辑框；否则不显示
        if (def.voices.isNotEmpty()) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0) }
            row.addView(TextView(this).apply { text = "音色 Voice"; textSize = 13f })
            val spinner = Spinner(this)
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, def.voices.map { it.name })
            val idx = def.voices.indexOfFirst { it.id == cfg.voice }.let { if (it < 0) 0 else it }
            spinner.setSelection(idx)
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    voiceValue = def.voices[pos].id
                    saveTtsConfig(def, fieldEdits, voiceValue, formatValue)
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            row.addView(spinner)
            container.addView(row)
        } else if (def.voiceFreeText) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0) }
            row.addView(TextView(this).apply { text = "音色 Voice"; textSize = 13f })
            val edit = EditText(this).apply { setText(cfg.voice); hint = "自定义音色名 / id"; textSize = 13f }
            edit.addTextChangedListener(simpleWatcher { voiceValue = it; saveTtsConfig(def, fieldEdits, voiceValue, formatValue) })
            row.addView(edit)
            container.addView(row)
        }

        // 格式（多于 1 个选项才显示下拉）
        if (def.formatOptions.size > 1) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0) }
            row.addView(TextView(this).apply { text = "输出格式"; textSize = 13f })
            val spinner = Spinner(this)
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, def.formatOptions)
            val fidx = def.formatOptions.indexOf(cfg.format).let { if (it < 0) def.formatOptions.indexOf(def.defaultFormat).let { j -> if (j < 0) 0 else j } else it }
            spinner.setSelection(fidx)
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    formatValue = def.formatOptions[pos]
                    saveTtsConfig(def, fieldEdits, voiceValue, formatValue)
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            row.addView(spinner)
            container.addView(row)
        }

        for ((k, edit) in fieldEdits) {
            edit.addTextChangedListener(simpleWatcher {
                saveTtsConfig(def, fieldEdits, voiceValue, formatValue)
            })
        }
    }

    private fun saveTtsConfig(def: QuroTtsProviderDef, fieldEdits: Map<String, EditText>, voice: String, format: String) {
        val fields = mutableMapOf<String, String>()
        for (f in def.fields) fields[f.key] = fieldEdits[f.key]?.text?.toString()?.trim() ?: ""
        val model = (fields["model"] ?: def.defaultModel).ifBlank { def.defaultModel }
        QuroTtsProviderPrefs.setProvider(this, def.id)
        QuroTtsProviderPrefs.saveConfig(
            this, def.id,
            QuroTtsProviderConfig(
                fields = fields,
                voice = voice,
                format = format.ifBlank { def.defaultFormat },
                model = model,
            ),
        )
    }

    /** 根据选中的 STT 服务商动态渲染配置项（描述 + 字段），即时持久化到 [QuroSttProviderPrefs]。 */
    private fun renderSttConfig(def: QuroSttProviderDef?) {
        val container = b.sttProviderConfig
        container.removeAllViews()
        if (def == null) { b.sttCloudBox.visibility = View.GONE; return }
        b.sttCloudBox.visibility = View.VISIBLE
        b.sttDescText.text = def.desc

        val cfg = QuroSttProviderPrefs.getConfig(this, def.id)
        val fieldEdits = mutableMapOf<String, EditText>()
        for (f in def.fields) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0) }
            row.addView(TextView(this).apply { text = f.label; textSize = 13f })
            val edit = EditText(this).apply {
                setText(cfg.fields[f.key] ?: "")
                hint = f.placeholder
                textSize = 13f
                if (f.secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            fieldEdits[f.key] = edit
            edit.addTextChangedListener(simpleWatcher { saveSttConfig(def, fieldEdits) })
            row.addView(edit)
            container.addView(row)
        }
    }

    private fun saveSttConfig(def: QuroSttProviderDef, fieldEdits: Map<String, EditText>) {
        val fields = mutableMapOf<String, String>()
        for (f in def.fields) fields[f.key] = fieldEdits[f.key]?.text?.toString()?.trim() ?: ""
        val model = (fields["model"] ?: def.defaultModel).ifBlank { def.defaultModel }
        QuroSttProviderPrefs.setProvider(this, def.id)
        QuroSttProviderPrefs.saveConfig(this, def.id, QuroSttProviderConfig(fields = fields, model = model))
    }

    // ── 端侧离线识别：模型下载 / 部署 / 删除 UI ──
    private fun renderOnDeviceConfig() {
        val box = b.sttOnDeviceBox
        box.removeAllViews()

        val status = TextView(this).apply {
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@VoiceServiceActivity, R.color.ink_soft))
            setPadding(0, 0, 0, dp(8))
        }
        onDeviceStatusTv = status
        box.addView(status)

        val activeKey = QuroOnDeviceModelPrefs.getActiveKey(this)
        val statusCode = QuroOnDeviceModelPrefs.getStatus(this)
        val deployedName = QuroOnDeviceModelPrefs.getDeployedName(this)
        status.text = when {
            activeKey != null && statusCode == QuroOnDeviceModelPrefs.STATUS_DEPLOYED ->
                "已部署：$deployedName（占用 ${formatBytes(QuroOnDeviceModelManager.deployedSizeBytes(this))}）"
            statusCode == QuroOnDeviceModelPrefs.STATUS_DOWNLOADING -> "正在下载模型…"
            statusCode == QuroOnDeviceModelPrefs.STATUS_ERROR -> "上次部署失败，可重新选择模型下载。"
            else -> "尚未下载模型。选择下方模型开始下载（约 22MB 起，建议 WiFi）。"
        }

        if (!QuroOnDeviceAsr.isNativeLoaded()) {
            status.text = "端侧识别引擎原生库未加载（本机可能非 arm64-v8a）。${status.text}"
        }

        for (spec in AsrModelCatalog.BUILTIN) {
            val isDeployed = activeKey == spec.id && statusCode == QuroOnDeviceModelPrefs.STATUS_DEPLOYED
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, 0)
            }
            row.addView(TextView(this).apply { text = spec.displayName; textSize = 13f })
            row.addView(TextView(this).apply {
                text = spec.note
                textSize = 11.5f
                setTextColor(ContextCompat.getColor(this@VoiceServiceActivity, R.color.ink_soft))
            })
            val btn = Button(this).apply {
                text = if (isDeployed) "删除模型" else "下载部署（约 ${spec.downloadBytes / (1024 * 1024)} MB）"
                textSize = 13f
                setOnClickListener { if (isDeployed) deleteModel() else downloadModel(spec) }
            }
            row.addView(btn)
            box.addView(row)
        }
    }

    private fun downloadModel(spec: AsrModelSpec) {
        uiScope.launch(Dispatchers.IO) {
            val ok = QuroOnDeviceModelManager.downloadAndDeploy(
                this@VoiceServiceActivity, spec,
                onState = { s -> mainHandler.post { onDeviceStatusTv?.text = s } },
            )
            mainHandler.post {
                android.widget.Toast.makeText(
                    this@VoiceServiceActivity,
                    if (ok) "模型部署完成" else "部署失败：${QuroOnDeviceAsr.lastError.ifEmpty { "未知错误" }}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                renderOnDeviceConfig()
            }
        }
    }

    private fun deleteModel() {
        uiScope.launch(Dispatchers.IO) {
            QuroOnDeviceModelManager.deleteDeployed(this@VoiceServiceActivity)
            mainHandler.post { renderOnDeviceConfig() }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun simpleWatcher(after: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { after(s?.toString()?.trim() ?: "") }
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** 若语音球正在运行，重启它以应用最新的尺寸 / 配色 / 唤醒方式 / 位置记忆等配置 */
    private fun applyBallNow() {
        if (!Config.ballEnabled) return
        if (!hasMic()) return // 无麦克风权限不重启 microphone 前台服务，避免 SecurityException 崩溃
        val svc = Intent(this, VoiceBallService::class.java)
        stopService(svc)
        mainHandler.postDelayed({
            if (Config.ballEnabled) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)
            }
        }, 300)
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
        tts.shutdown()
    }

    private fun sb(onChange: (Int) -> Unit) = object : android.widget.SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, f: Boolean) = onChange(p)
        override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
        override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
    }
}
