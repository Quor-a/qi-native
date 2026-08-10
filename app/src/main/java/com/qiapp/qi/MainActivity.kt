package com.qiapp.qi

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.qiapp.qi.databinding.ActivityMainBinding
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity(), PermissionRequester {

    lateinit var binding: ActivityMainBinding
    private var chatFrag: ChatFragment? = null
    private var soulFrag: SoulFragment? = null
    private var settingsFrag: SettingsFragment? = null

    // 权限网关：工具在后台线程请求危险权限时，经此 launcher 在 UI 线程弹框，
    // 后台线程用 CountDownLatch 阻塞等待用户授权结果（对齐上游 QuroPermissionGate）。
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { map ->
        permResult.set(map.values.all { it })
        permLatch?.countDown()
    }
    private var permLatch: CountDownLatch? = null
    private val permResult = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Config.init(applicationContext)
        AppState.applyConfig()
        // Android 14+（targetSdk 34）：microphone 类型的前台服务在 startForeground 时必须已授予
        // RECORD_AUDIO，否则抛 SecurityException 直接崩溃（一打开 App 就崩）。
        // 语音球本就依赖麦克风，故仅在已授权麦克风时才自启；未授权则由「权限」页引导后再开。
        if (Config.ballEnabled && micGranted()) startVoiceBall()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            chatFrag = ChatFragment()
            supportFragmentManager.beginTransaction()
                .replace(R.id.nav_host, chatFrag!!, "chat")
                .commit()
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_chat -> showFragment("chat") { chatFrag ?: ChatFragment().also { chatFrag = it } }
                R.id.nav_soul -> showFragment("soul") { soulFrag ?: SoulFragment().also { soulFrag = it } }
                // 形象已合体到语音球（悬浮窗常驻），独立「虚拟形象界面」入口移除
                R.id.nav_settings -> showFragment("settings") { settingsFrag ?: SettingsFragment().also { settingsFrag = it } }
                else -> false
            }
        }
    }

    private fun showFragment(tag: String, make: () -> androidx.fragment.app.Fragment): Boolean {
        val fm = supportFragmentManager
        val existing = fm.findFragmentByTag(tag)
        if (existing != null && existing.isVisible) return true
        val tx = fm.beginTransaction()
        fm.fragments.forEach { if (it.isVisible) tx.hide(it) }
        if (existing != null) {
            tx.show(existing)
        } else {
            tx.add(R.id.nav_host, make(), tag)
        }
        tx.commit()
        return true
    }

    private fun micGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun startVoiceBall() {
        val svc = Intent(this, VoiceBallService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)
    }

    /** 从设置页「语音聊天」入口进入对话框并直接发起语音对话。 */
    fun openChatVoiceChat() {
        binding.bottomNav.selectedItemId = R.id.nav_chat
        chatFrag?.launchVoiceChat()
    }

    // ---- 权限网关：供后台线程的工具请求危险权限（对齐上游 QuroPermissionGate）----

    override fun ensure(permissions: List<String>): Boolean {
        if (isFinishing || isDestroyed) return false
        permLatch = CountDownLatch(1)
        permResult.set(false)
        val launch = { permLauncher.launch(permissions.toTypedArray()) }
        // 权限弹框必须在主线程发起；从后台线程（qi-llm）调用时切回 UI 线程。
        if (Looper.myLooper() == Looper.getMainLooper()) launch() else runOnUiThread(launch)
        // 后台线程阻塞等待授权结果；超时（30s）兜底，避免极端情况下 chat 线程被永久卡死。
        runCatching { permLatch?.await(30, TimeUnit.SECONDS) }
        return permResult.get()
    }

    override fun onStart() {
        super.onStart()
        // 承载对话的 Activity 在前台时，注入权限请求器，使工具能弹系统授权框。
        PermissionGate.requester = this
    }

    override fun onStop() {
        // Activity 离开前台即清空，避免后台/销毁场景下 requester 指向失效 Activity。
        PermissionGate.requester = null
        super.onStop()
    }
}
