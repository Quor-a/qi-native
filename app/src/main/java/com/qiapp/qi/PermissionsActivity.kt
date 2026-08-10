package com.qiapp.qi

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.ImageButton
import android.widget.TextView
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.qiapp.qi.databinding.ActivityPermissionsBinding
import com.qiapp.qi.databinding.RowPermBinding

class PermissionsActivity : AppCompatActivity() {

    private lateinit var b: ActivityPermissionsBinding
    private lateinit var adapter: PermAdapter

    // 系统弹窗（运行时权限）结果返回后，重新评估所有权限的真实状态
    private val reqPerm = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        adapter.notifyStateChanged()
    }

    data class Perm(
        val title: String,
        val sub: String,
        val icon: Int,
        val granted: () -> Boolean,
        val act: () -> Unit
    )

    private val list = mutableListOf<Perm>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPermissionsBinding.inflate(layoutInflater)
        setContentView(b.root)

        findViewById<TextView>(R.id.titleText).text = "权限"
        findViewById<ImageButton>(R.id.backBtn).setOnClickListener { finish() }

        list.add(Perm("麦克风", "语音输入需要，用于语音球说话", R.drawable.ic_mic,
            { ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
            { reqMic() }))
        list.add(Perm("悬浮窗权限", "让语音球常驻桌面", R.drawable.ic_ball,
            { Settings.canDrawOverlays(this) },
            { reqOverlay() }))
        list.add(Perm("通知权限", "语音球常驻通知需要（Android 13+）", R.drawable.ic_settings,
            { notifyGranted() },
            { reqNotify() }))
        list.add(Perm("存储权限", "读取 / 导出聊天与灵魂卡", R.drawable.ic_upload,
            { hasStorage() },
            { reqStorage() }))
        list.add(Perm("电池优化豁免", "避免后台被清理，语音球更稳定", R.drawable.ic_lock,
            { isIgnoringBattery() },
            { reqBattery() }))
        // 工具调用所需的手机权限（危险权限，运行时授予）
        list.add(Perm("电话", "工具调用可拨打电话", R.drawable.ic_call,
            { ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED },
            { reqCall() }))
        list.add(Perm("短信（收发）", "工具调用可收发短信 / 彩信", R.drawable.ic_sms,
            { ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED },
            { reqSms() }))
        list.add(Perm("联系人", "工具调用可读写通讯录", R.drawable.ic_contacts,
            { ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED },
            { reqContacts() }))
        list.add(Perm("日历", "工具调用可读写日程", R.drawable.ic_calendar,
            { ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED },
            { reqCalendar() }))
        // 位置权限（可选，用于场景化提醒）——对齐上游 ZorvAI 的权限项
        list.add(Perm("位置权限", "可选 · 用于场景化提醒", R.drawable.ic_location,
            { ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED },
            { reqLocation() }))
        // 以下为对齐上游 ZorvAI 补齐的手机能力权限
        list.add(Perm("相机", "头像拍摄 / 扫码等", R.drawable.ic_camera,
            { ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED },
            { reqCamera() }))
        list.add(Perm("蓝牙", "连接蓝牙耳机等音频设备", R.drawable.ic_bluetooth,
            { ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED },
            { reqBluetooth() }))
        list.add(Perm("电话与通话记录", "读取本机号 / 通话记录（工具调用）", R.drawable.ic_call,
            { ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED },
            { reqPhone() }))

        b.permList.layoutManager = LinearLayoutManager(this)
        adapter = PermAdapter()
        b.permList.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置页（悬浮窗/电池/通知）返回后刷新真实状态
        adapter.notifyStateChanged()
    }

    private fun notifyGranted(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun hasStorage(): Boolean = if (Build.VERSION.SDK_INT >= 33) {
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun isIgnoringBattery(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun reqMic() = reqPerm.launch(arrayOf(Manifest.permission.RECORD_AUDIO))

    private fun reqNotify() = reqPerm.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))

    private fun reqStorage() = reqPerm.launch(arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_EXTERNAL_STORAGE
    ))

    private fun reqOverlay() =
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))

    private fun reqBattery() =
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Throwable) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

    private fun reqCall() = reqPerm.launch(arrayOf(Manifest.permission.CALL_PHONE))
    private fun reqSms() = reqPerm.launch(arrayOf(
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_SMS,
        Manifest.permission.RECEIVE_SMS,
        "android.permission.RECEIVE_MMS",
        "android.permission.SEND_MMS"
    ))
    private fun reqContacts() = reqPerm.launch(arrayOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS,
        Manifest.permission.GET_ACCOUNTS
    ))
    private fun reqCalendar() = reqPerm.launch(arrayOf(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR
    ))
    private fun reqLocation() = reqPerm.launch(arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION
    ))
    private fun reqCamera() = reqPerm.launch(arrayOf(Manifest.permission.CAMERA))
    private fun reqBluetooth() = reqPerm.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
    private fun reqPhone() = reqPerm.launch(arrayOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_PHONE_NUMBERS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.WRITE_CALL_LOG
    ))

    inner class PermAdapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = list.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int): Holder =
            Holder(RowPermBinding.inflate(LayoutInflater.from(p.context), p, false))
        override fun onBindViewHolder(h: Holder, i: Int) {
            val item = list[i]
            h.x.permIcon.setImageResource(item.icon)
            h.x.permTitle.text = item.title
            h.x.permSub.text = item.sub
            val ok = item.granted()
            h.x.permStatus.text = if (ok) "已授权 ✓" else "未授权"
            h.x.permStatus.setTextColor(
                ContextCompat.getColor(this@PermissionsActivity, if (ok) R.color.ok else R.color.rose)
            )
            h.x.permRow.setOnClickListener {
                item.act()
                // 运行时权限在 reqPerm 回调刷新；设置页类权限在 onResume 刷新
            }
        }
        fun notifyStateChanged() = notifyDataSetChanged()
    }

    class Holder(val x: RowPermBinding) : RecyclerView.ViewHolder(x.root)
}
