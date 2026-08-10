package com.qiapp.qi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.math.hypot

/**
 * 悬浮语音球服务（参考 Zorv AI 的 QuroVoiceBallService 思路）：
 * 在任意界面挂一个可点击 / 可拖拽的球，点按（或长按，见「唤醒方式」）= 启停连续语音对话
 * （聆听 → LLM → 朗读 → 再聆听），完全脱离对话框、跨应用可用。
 * 复用本应用既有的 SttHelper / LlmClient / TtsEngine，零新增依赖。
 *
 * 「语音服务」页可配置的真实项：尺寸(ballSize)、配色(ballColor)、
 * 唤醒方式(ballWake)、记住位置(ballRemember)、断句静音(silenceMs，见 SttHelper)。
 */
class VoiceBallService : Service() {

    private lateinit var wm: WindowManager
    private var ballRoot: View? = null
    private var statusText: TextView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var conversationActive = false
    private var listening = false
    private var speaking = false
    private var stt: SttHelper? = null
    private lateinit var tts: TtsEngine

    // 连续空结果/出错计数（超过上限自动暂停，避免错误时无限续听刷屏/耗电）
    private var emptyCount = 0
    /** TTS 播报重入守卫：正在播报时忽略新的 speak 请求，避免重复/并发播报。 */
    @Volatile private var ttsBusy = false

    private var params: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        tts = TtsEngine(applicationContext)
        startForegroundSafely()
    }

    /**
     * 前台化挂通知。manifest 声明了 foregroundServiceType="microphone"，
     * Android 14+（targetSdk 34）要求调用 startForeground 时已授予 RECORD_AUDIO，
     * 否则抛 SecurityException 崩溃（一打开 App 就崩的元凶）。
     * 未授权麦克风时不以麦克风类型前台运行，直接停止服务（语音球本就依赖麦克风，
     * 未授权没有意义），并兜底任何前台化异常，绝不让服务崩溃。
     */
    private fun startForegroundSafely() {
        val hasMic = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= 34 && !hasMic) {
            stopSelf()
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, buildNotification())
            }
        } catch (t: Throwable) {
            try { stopSelf() } catch (_: Throwable) {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_BALL -> {
                if (ballRoot != null) removeBall() else ensureBall()
            }
            ACTION_OPEN_CHAT -> {
                try {
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Throwable) {}
            }
            else -> {
                val show = intent?.getBooleanExtra(EXTRA_SHOW, true) ?: true
                if (show) ensureBall() else removeBall()
            }
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val chan = NotificationChannel(CHANNEL_ID, "栖 语音球", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(chan)
        val open = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE)
        // 通知栏快捷按钮：对齐 Zorv AI —— 「语音球」切换悬浮球显隐，「聊天框」打开主对话
        val toggleIntent = Intent(this, VoiceBallService::class.java).apply { action = ACTION_TOGGLE_BALL }
        val togglePi = PendingIntent.getService(
            this, 1, toggleIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val chatIntent = Intent(this, MainActivity::class.java).apply { action = ACTION_OPEN_CHAT }
        val chatPi = PendingIntent.getActivity(
            this, 2, chatIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("栖 · 语音球")
            .setContentText("点按悬浮球开始语音对话")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_btn_speak_now, "语音球", togglePi)
            .addAction(android.R.drawable.ic_dialog_email, "聊天框", chatPi)
            .build()
    }

    private fun ensureBall() {
        if (ballRoot != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            updateStatus("开启语音球需授予「悬浮窗」权限")
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Throwable) {}
            return
        }
        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val root = inflater.inflate(R.layout.voice_ball, null)
        ballRoot = root
        statusText = root.findViewById(R.id.status)
        val ball = root.findViewById<FrameLayout>(R.id.ball)
        val avatar = root.findViewById<ImageView>(R.id.avatar)
        applyBallStyle(ball)
        // 虚拟形象合体到语音球：显示与聊天头部同一张静态头像图（非 3D、非动态卡通）
        applyAvatarToBall(avatar)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = 0
            y = 0
        }
        wm.addView(root, params)

        // 落点：优先恢复上次记忆位置，否则右下角
        root.post {
            val dm = resources.displayMetrics
            if (Config.ballRemember && Config.ballPosX >= 0 && Config.ballPosY >= 0) {
                params?.x = Config.ballPosX.coerceIn(0, (dm.widthPixels - root.width).coerceAtLeast(0))
                params?.y = Config.ballPosY.coerceIn(0, (dm.heightPixels - root.height).coerceAtLeast(0))
            } else {
                params?.x = (dm.widthPixels - root.width - (24 * dm.density).toInt()).coerceAtLeast(0)
                params?.y = (dm.heightPixels - root.height - (140 * dm.density).toInt()).coerceAtLeast(0)
            }
            wm.updateViewLayout(root, params)
        }

        var downX = 0f; var downY = 0f; var offX = 0f; var offY = 0f
        var moved = false; var downTime = 0L
        val threshold = (8 * resources.displayMetrics.density).toInt()
        ball.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    offX = ev.rawX - (params?.x ?: 0); offY = ev.rawY - (params?.y ?: 0)
                    moved = false; downTime = System.currentTimeMillis(); true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX; val dy = ev.rawY - downY
                    if (!moved && hypot(dx, dy) > threshold) moved = true
                    if (moved) {
                        val dm = resources.displayMetrics
                        params?.x = (ev.rawX - offX).toInt()
                            .coerceIn(0, (dm.widthPixels - root.width).coerceAtLeast(0))
                        params?.y = (ev.rawY - offY).toInt()
                            .coerceIn(0, (dm.heightPixels - root.height).coerceAtLeast(0))
                        wm.updateViewLayout(root, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        // 未拖动：按「唤醒方式」决定触发手势
                        // ballWake==1 点按说话；ballWake==0 长按说话（按住>500ms 视为长按）
                        val dt = System.currentTimeMillis() - downTime
                        if (Config.ballWake == 1 || dt >= 500) toggleConversation()
                    } else {
                        // 拖拽结束：记忆位置
                        if (Config.ballRemember) {
                            Config.ballPosX = params?.x ?: -1
                            Config.ballPosY = params?.y ?: -1
                        }
                    }
                    true
                }
                else -> false
            }
        }
        updateStatus(if (Config.ballWake == 1) "点我说话" else "长按说话")
    }

    /** 应用「语音服务」页配置的尺寸与配色到球体 */
    private fun applyBallStyle(ball: View) {
        val dm = resources.displayMetrics
        val sizeDp = when (Config.ballSize) { 0 -> 48; 2 -> 72; else -> 56 }
        val sizePx = (sizeDp * dm.density).toInt()
        ball.layoutParams?.let { lp ->
            lp.width = sizePx
            lp.height = sizePx
            ball.layoutParams = lp
        }
        val colors = when (Config.ballColor) {
            1 -> intArrayOf(0xFF7FB2FF.toInt(), 0xFF4A90E2.toInt())   // 天蓝
            2 -> intArrayOf(0xFFC9A6E0.toInt(), 0xFFA877C8.toInt())   // 藕荷
            else -> intArrayOf(0xFFFF8FA8.toInt(), 0xFFE86A8C.toInt()) // 桃粉
        }
        val gd = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors)
        gd.shape = GradientDrawable.OVAL
        ball.background = gd
    }

    private fun removeBall() {
        try { ballRoot?.let { wm.removeView(it) } } catch (_: Throwable) {}
        ballRoot = null
        statusText = null
    }

    /**
     * 把虚拟形象的**静态头像图**显示到语音球（与聊天头部 applyAvatar 同一张）：
     * 用户上传的头像文件优先，否则回退到灵魂默认渐变 + ic_soul。
     * 注意：是静态图片，不是 3D 模型、也不是动态卡通。
     */
    private fun applyAvatarToBall(iv: ImageView) {
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

    private fun toggleConversation() {
        try {
            if (conversationActive) stopConversation() else startConversation()
        } catch (e: Throwable) {
            updateStatus("操作失败：${e.message}")
        }
    }

    private fun startConversation() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            updateStatus("缺少麦克风权限，请在「设置→权限」开启")
            Toast.makeText(applicationContext, "请在设置→权限中开启麦克风", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(this, PermissionsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Throwable) {}
            return
        }
        if (!SttHelper(this, object : SttHelper.Cb {}).isAvailable()) {
            updateStatus("本机未提供语音识别")
            return
        }
        conversationActive = true
        emptyCount = 0
        updateStatus("聆听中…")
        startListening()
    }

    private fun stopConversation(reason: String? = null) {
        conversationActive = false
        listening = false
        speaking = false
        emptyCount = 0
        ttsBusy = false
        stt?.stop()
        stt?.destroy()
        stt = null
        tts.stop()
        updateStatus(reason ?: "已暂停 · 点我继续")
    }

    private fun startListening() {
        listening = true
        updateStatus("聆听中…")
        stt = SttHelper(this, object : SttHelper.Cb {
            override fun onPartial(text: String) {
                if (text.isNotBlank()) updateStatus("聆听中：$text")
            }
            override fun onFinal(text: String) {
                if (!conversationActive) return
                listening = false
                if (text.isNotBlank()) {
                    emptyCount = 0
                    updateStatus("你说：$text")
                    appendUser(text)
                    process(text)
                } else {
                    onEmptyOrError("没听清")
                }
            }
            override fun onError(msg: String) {
                if (!conversationActive) return
                listening = false
                onEmptyOrError(msg)
            }
        })
        stt?.start()
    }

    /** 空结果/出错：累计计数，超过上限自动暂停；否则退避一小段再续听（与 Zorv AI 语音球一致）。 */
    private fun onEmptyOrError(reason: String) {
        if (!conversationActive) return
        emptyCount++
        if (emptyCount > MAX_CONSECUTIVE_EMPTY) {
            stopConversation("连续无语音，已自动暂停")
            return
        }
        updateStatus("$reason，稍后重试")
        mainHandler.postDelayed({ if (conversationActive) startListening() }, BACKOFF_MS)
    }

    private fun process(text: String) {
        if (Config.apiKey.isBlank()) {
            updateStatus("未配置 API Key")
            speak("请先在模型配置页填写 API Key") { if (conversationActive) startListening() }
            return
        }
        updateStatus("思考中…")
        LlmClient.chat(this, LlmClient.buildSystemPrompt(this), LlmClient.buildHistory(), object : LlmClient.ChatCallback {
            override fun onToken(delta: String) {}
            override fun onDone(full: String) {
                if (!conversationActive) return
                val rawReply = full.ifBlank { "(对方没有回应)" }
                appendAssistant(QuroVoiceStyle.strip(rawReply))
                // 把回复情绪广播给动态形象
                AvatarBus.emitEmotion(EmotionAnalyzer.analyze(rawReply).key)
                updateStatus("回复中…")
                speaking = true
                speak(rawReply) {
                    speaking = false
                    if (conversationActive) { updateStatus("聆听中…"); startListening() }
                }
            }
            override fun onError(msg: String) {
                if (!conversationActive) return
                appendAssistant("⚠️ $msg")
                updateStatus("⚠️ $msg")
                if (conversationActive) startListening()
            }
        })
    }

    private fun speak(text: String, onDone: () -> Unit) {
        if (ttsBusy) { updateStatus("播报中，稍候"); return }
        ttsBusy = true
        tts.speak(text, true) {
            ttsBusy = false
            mainHandler.post(onDone)
        }
    }

    private fun appendUser(text: String) {
        AppState.messages.add(TextMsg(text, true))
        AppState.persistCurrent(applicationContext)
    }

    private fun appendAssistant(text: String) {
        AppState.messages.add(TextMsg(text, false))
        AppState.persistCurrent(applicationContext)
    }

    private fun updateStatus(s: String) {
        mainHandler.post { statusText?.text = s }
    }

    override fun onDestroy() {
        try {
            if (Config.ballRemember) {
                Config.ballPosX = params?.x ?: -1
                Config.ballPosY = params?.y ?: -1
            }
            conversationActive = false
            stt?.destroy()
            stt = null
            tts.shutdown()
            removeBall()
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {}
        super.onDestroy()
    }

    companion object {
        const val NOTIF_ID = 8801
        const val CHANNEL_ID = "qi_voice_ball"
        const val EXTRA_SHOW = "extra_show"
        /** 通知栏「语音球」按钮：切换悬浮球显隐。 */
        const val ACTION_TOGGLE_BALL = "com.qiapp.qi.action.TOGGLE_BALL"
        /** 通知栏「聊天框」按钮：打开主对话界面。 */
        const val ACTION_OPEN_CHAT = "com.qiapp.qi.action.OPEN_CHAT"
        private const val BACKOFF_MS = 600L
        private const val MAX_CONSECUTIVE_EMPTY = 3
    }
}
