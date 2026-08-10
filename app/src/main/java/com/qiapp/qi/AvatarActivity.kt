package com.qiapp.qi

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Environment
import android.os.Looper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.json.JSONObject

/**
 * 沉浸式「形象空间」——3D 版（v9）。
 *
 * 用户要求：「背景应该是一个场景，认真设计 3D 的，人物丰富，LLM 驱动身体」。
 * 于是整页主视觉换成 **自包含 WebGL 实时 3D**（assets/avatar3d/，完全离线、不联网）：
 *  - 场景：带落地窗 / 体积光 / 家具 / 植物 / 悬浮尘埃的 3D 房间；
 *  - 角色：真骨架 3D 人体（头/发/眼/眉/会开合的嘴/四肢/服装，三种风格）；
 *  - 动画：分层系统（呼吸 → 待机小动作 → 情绪 → 说话点头打拍子 → 手势）；
 *  - 驱动：[AvatarBus] 的口形 / 情绪 / **说话文本** / 手势 四条通道经 JS 桥推给 3D 端，
 *          其中「说话文本」由 3D 端做语义分析派生身体动作 —— 即 LLM 驱动身体。
 *
 * 交互：单指转视角、双指缩放、轻点角色触发互动手势、双击复位、点名字换镜头。
 */
class AvatarActivity : AppCompatActivity() {

    private lateinit var stage: WebView
    private lateinit var sceneBg: View
    private lateinit var emoLabel: TextView
    private lateinit var tvName: TextView
    private lateinit var tvHint: TextView
    private lateinit var ivGear: View
    private lateinit var ivBack: View

    private lateinit var tts: TtsEngine
    private val idx get() = AppState.currentSoul

    private val ui = Handler(Looper.getMainLooper())

    /** 3D 端是否已 boot 完成（JS API 可用）。 */
    @Volatile private var glReady = false
    /** ready 之前累积的指令，ready 后一次性回放。 */
    private val pending = ArrayList<String>(8)

    /** 实时联动总开关（聊天时是否随 AI 语音/情绪变化）。 */
    private var liveOn = true

    /** 镜头：0 全身 / 1 半身 / 2 特写。 */
    private var shot = 1

    // ── 口形节流：ValueAnimator 是屏幕刷新率级别（90/120Hz），
    //    直接每帧 evaluateJavascript 会有明显的 JNI + JS 解析开销。
    //    这里限制到 ~40fps，且幅度变化过小就跳过。
    private var lastAmpMs = 0L
    private var lastAmp = -1f

    private val ampListener: (Float) -> Unit = { v ->
        if (Config.soulAvatarOn(idx) && liveOn && Config.soulLipsync(idx)) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastAmpMs >= 25L && kotlin.math.abs(v - lastAmp) > 0.012f) {
                lastAmpMs = now
                lastAmp = v
                // 必须锁 Locale.US：德/法等区域的 %f 会输出「0,500」，注入 JS 直接语法错误
                js("__avatar.setMouth(${String.format(java.util.Locale.US, "%.3f", v)})")
            }
        }
    }
    private val emoListener: (String) -> Unit = { key ->
        if (Config.soulAvatarOn(idx) && liveOn) applyEmotion(key)
    }
    /** LLM 驱动身体：整段回复文本 + 预计时长 → 3D 端解析语义排布手势。 */
    private val sayListener: (String, Long) -> Unit = { text, dur ->
        if (Config.soulAvatarOn(idx) && liveOn) {
            js("__avatar.say(${JSONObject.quote(clipForJs(text))}, $dur)")
        }
    }
    private val gestureListener: (String) -> Unit = { name ->
        if (Config.soulAvatarOn(idx) && liveOn) js("__avatar.gesture(${JSONObject.quote(name)})")
    }
    private val endListener: () -> Unit = {
        lastAmp = -1f
        js("__avatar.endSpeech()")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_avatar)
        tts = TtsEngine(this)

        stage = findViewById(R.id.glStage)
        sceneBg = findViewById(R.id.sceneBg)
        emoLabel = findViewById(R.id.emoLabel)
        tvName = findViewById(R.id.tvName)
        tvHint = findViewById(R.id.tvHint)
        ivGear = findViewById(R.id.ivGear)
        ivBack = findViewById(R.id.ivBack)

        tvName.text = AppState.soulDisplayName()

        // 一次性迁移：旧默认「用头像做脸」会让形象空间显示成静态/不完整
        if (!Config.avatarPhotoMigrated()) {
            for (i in 0 until AppState.baseSouls.size) Config.setSoulAvatarPhoto(i, false)
            Config.setAvatarPhotoMigrated(true)
        }

        liveOn = Config.soulAvatarLive(idx)
        setupStage()

        ivBack.setOnClickListener { finish() }
        ivGear.setOnClickListener { openDesigner() }
        tvName.setOnClickListener {
            shot = (shot + 1) % 3
            js("__avatar.setShot($shot)")
            toast(when (shot) { 0 -> "全身"; 1 -> "半身"; else -> "特写" })
        }
    }

    // ────────────────────────── WebGL 舞台 ──────────────────────────

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun setupStage() {
        stage.setBackgroundColor(0xFF101018.toInt())
        stage.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = false
            // 3D 场景自己管理视口，禁用 WebView 的缩放/自适应干扰
            useWideViewPort = false
            loadWithOverviewMode = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            mediaPlaybackRequiresUserGesture = true
        }
        stage.isVerticalScrollBarEnabled = false
        stage.isHorizontalScrollBarEnabled = false
        stage.overScrollMode = View.OVER_SCROLL_NEVER

        stage.addJavascriptInterface(Bridge(), "QiBridge")
        stage.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                // 页面 DOM 就绪，但 WebGL boot 还没跑完，等 Bridge.onReady
            }
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                val msg = "[pageError] code=$errorCode desc=$description url=$failingUrl"
                android.util.Log.e("Avatar3D", msg)
                writeAvatarLog(msg)
            }
        }
        stage.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(cm: ConsoleMessage): Boolean {
                val line = "[${cm.messageLevel()}] ${cm.message()} @${cm.lineNumber()}"
                android.util.Log.d("Avatar3D", line)
                writeAvatarLog(line)
                return true
            }
        }
        stage.loadUrl("file:///android_asset/avatar3d/index.html")
    }

    /** 把 3D 端诊断信息双写到手机存储，用户无需 adb 即可取（文件管理器 → Download/qi_logs/glb_render.log）。 */
    private fun writeAvatarLog(line: String) {
        runCatching {
            val ts = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date())
            val content = "[$ts] $line\n"
            val targets = ArrayList<File>()
            // 优先公共 Download（用户用文件管理器易取）
            targets.add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "qi_logs"))
            // 回退：app 私有外部目录（不需要额外权限）
            getExternalFilesDir(null)?.let { targets.add(File(it, "qi_logs")) }
            for (d in targets) {
                runCatching {
                    if (!d.exists()) d.mkdirs()
                    val f = File(d, "glb_render.log")
                    f.appendText(content)
                    if (f.length() > 200_000) {
                        val keep = f.readLines().takeLast(800)
                        f.writeText(keep.joinToString("\n") + "\n")
                    }
                }
            }
        }
    }

    /** Kotlin → JS。ready 前先入队，ready 后按序回放。 */
    private fun js(expr: String) {
        val stmt = "try{$expr}catch(e){}"
        ui.post {
            if (!glReady) {
                if (pending.size < 32) pending.add(stmt)
                return@post
            }
            runCatching { stage.evaluateJavascript(stmt, null) }
        }
    }

    /** 说话文本过长时截断（语义手势只看开头，避免一次塞几千字进 JS）。 */
    private fun clipForJs(s: String): String =
        if (s.length <= 400) s else s.substring(0, 400)

    /** 3D 端 boot 成功后推送初始状态。 */
    private fun pushInitialState() {
        val style = Config.soulAvatarStyle(idx).coerceIn(0, 2)
        val scene = Config.soulAvatarBg(idx).coerceIn(0, 3)
        glReady = true
        runCatching {
            stage.evaluateJavascript(
                "try{__avatar.setStyle($style);" +
                    "__avatar.setAccent('${String.format(java.util.Locale.US, "#%06X", 0xFFFFFF and soulAccent(idx))}');" +
                    "__avatar.setScene($scene);" +
                    "__avatar.setShot($shot);" +
                    "__avatar.setEmotion(${JSONObject.quote(currentEmotionKey)});}catch(e){}",
                null
            )
        }
        if (pending.isNotEmpty()) {
            val batch = pending.joinToString(";")
            pending.clear()
            runCatching { stage.evaluateJavascript(batch, null) }
        }
        sceneBg.visibility = View.GONE
        if (Config.soulAvatarOn(idx) && liveOn) subscribe()
    }

    /** JS → Kotlin 桥。 */
    inner class Bridge {
        @JavascriptInterface
        fun onReady() { ui.post { pushInitialState() } }

        @JavascriptInterface
        fun onError(msg: String) {
            ui.post {
                sceneBg.visibility = View.VISIBLE
                sceneBg.setBackgroundColor(0xFF1A1A24.toInt())
                tvHint.text = "此设备不支持 WebGL，3D 形象已降级"
                android.util.Log.w("Avatar3D", "boot failed: $msg")
            }
        }

        /** 用户轻点角色，3D 端回调（可用来触发一句招呼）。 */
        @JavascriptInterface
        fun onTap() { /* 预留：后续可接「点一下就打招呼」 */ }
    }

    // ────────────────────────── 设计师弹层 ──────────────────────────

    private fun openDesigner() {
        val sheet = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.dialog_avatar_design, null)
        sheet.setContentView(v)

        val switchOn = v.findViewById<SwitchCompat>(R.id.switchOn)
        val switchLipsync = v.findViewById<SwitchCompat>(R.id.switchLipsync)
        val switchLive = v.findViewById<SwitchCompat>(R.id.switchLive)
        val spinnerStyle = v.findViewById<Spinner>(R.id.spinnerStyle)
        val spinnerBg = v.findViewById<Spinner>(R.id.spinnerBg)

        switchOn.isChecked = Config.soulAvatarOn(idx)
        switchLipsync.isChecked = Config.soulLipsync(idx)
        switchLive.isChecked = liveOn

        val styleAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            listOf("温柔（长直发）", "活泼（双马尾）", "高冷（短发）")).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        // 3D 场景不再用位图背景，第 4 项换成「夜晚」光照
        val bgAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            listOf("跟随情绪", "晨曦", "暮色", "夜晚")).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerStyle.adapter = styleAdapter
        spinnerBg.adapter = bgAdapter
        spinnerStyle.setSelection(Config.soulAvatarStyle(idx).coerceIn(0, 2))
        spinnerBg.setSelection(Config.soulAvatarBg(idx).coerceIn(0, 3))

        spinnerStyle.setOnItemSelectedListener(object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, w: View?, pos: Int, id: Long) {
                Config.setSoulAvatarStyle(idx, pos)
                js("__avatar.setStyle($pos)")
            }
            override fun onNothingSelected(p: AdapterView<*>) {}
        })
        spinnerBg.setOnItemSelectedListener(object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, w: View?, pos: Int, id: Long) {
                Config.setSoulAvatarBg(idx, pos)
                js("__avatar.setScene($pos)")
            }
            override fun onNothingSelected(p: AdapterView<*>) {}
        })

        switchOn.setOnCheckedChangeListener { _, on ->
            Config.setSoulAvatarOn(idx, on)
            if (on) { if (liveOn) subscribe() }
            else { unsubscribe(); js("__avatar.setMouth(0);__avatar.setEmotion('neutral')") }
        }
        switchLipsync.setOnCheckedChangeListener { _, on ->
            Config.setSoulLipsync(idx, on)
            if (!on) js("__avatar.setMouth(0)")
        }
        switchLive.setOnCheckedChangeListener { _, on ->
            liveOn = on
            Config.setSoulAvatarLive(idx, on)
            if (on) subscribe() else unsubscribe()
        }

        v.findViewById<Button>(R.id.btnEmoCalm).setOnClickListener { previewEmotion("calm") }
        v.findViewById<Button>(R.id.btnEmoHappy).setOnClickListener { previewEmotion("happy") }
        v.findViewById<Button>(R.id.btnEmoSad).setOnClickListener { previewEmotion("sad") }
        v.findViewById<Button>(R.id.btnEmoSurprised).setOnClickListener { previewEmotion("surprised") }
        v.findViewById<Button>(R.id.btnTry).setOnClickListener { trySpeak() }

        sheet.show()
    }

    // ────────────────────────── 总线订阅 ──────────────────────────

    private var subscribed = false

    private fun subscribe() {
        if (subscribed) return
        subscribed = true
        AvatarBus.addAmp(ampListener)
        AvatarBus.addEmo(emoListener)
        AvatarBus.addSay(sayListener)
        AvatarBus.addGesture(gestureListener)
        AvatarBus.addSpeechEnd(endListener)
    }

    private fun unsubscribe() {
        subscribed = false
        AvatarBus.removeAmp(ampListener)
        AvatarBus.removeEmo(emoListener)
        AvatarBus.removeSay(sayListener)
        AvatarBus.removeGesture(gestureListener)
        AvatarBus.removeSpeechEnd(endListener)
    }

    /** 情绪预览：仅本页可见，不写全局。 */
    private fun previewEmotion(key: String) = applyEmotion(key)

    private var currentEmotionKey = "neutral"

    /** 应用情绪到 3D 端 + 顶部标签（「跟随情绪」模式下光照也会随之偏移）。 */
    private fun applyEmotion(key: String) {
        currentEmotionKey = key
        val e = EmotionAnalyzer.Emotion.fromKey(key)
        emoLabel.text = "情绪：${e.label}"
        js("__avatar.setEmotion(${JSONObject.quote(key)})")
    }

    /** 试听：一句示例文案，先定情绪，再朗读（TTS 经 AvatarBus 驱动口形 + 身体）。 */
    private fun trySpeak() {
        val line = "你好呀，今天也谢谢你一直陪着我，能和你聊天我真的好开心！"
        val e = EmotionAnalyzer.analyze(line)
        applyEmotion(e.key)
        if (!Config.soulAvatarOn(idx) || !liveOn) {
            // 形象/联动被关掉时，至少让身体动起来，方便用户看效果
            js("__avatar.say(${JSONObject.quote(line)}, ${AvatarBus.estimateDurMs(line)})")
        }
        if (Config.apiKey.isBlank()) {
            toast("未配置 TTS 也能看口形与动作")
            AvatarBus.beginSpeech(line)
            ui.postDelayed({ AvatarBus.endSpeech() }, AvatarBus.estimateDurMs(line))
            return
        }
        // 必须带回调：TtsEngine 只有在 onDone 非空时才会在播完后调 endSpeech 收口
        tts.speak(line, true) { }
    }

    /** 灵魂主题色：小栖=桃粉，阿粲=藕荷，其余默认桃粉。 */
    private fun soulAccent(i: Int): Int = when (i) {
        1 -> 0xFFA877C8.toInt()
        else -> 0xFFE86A8C.toInt()
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        stage.onResume()
        runCatching { stage.resumeTimers() }
        if (glReady && Config.soulAvatarOn(idx) && liveOn) subscribe()
    }

    override fun onPause() {
        super.onPause()
        unsubscribe()
        stage.onPause()
        runCatching { stage.pauseTimers() }
    }

    override fun onDestroy() {
        unsubscribe()
        runCatching {
            stage.loadUrl("about:blank")
            (stage.parent as? ViewGroup)?.removeView(stage)
            stage.destroy()
        }
        tts.shutdown()
        super.onDestroy()
    }
}
