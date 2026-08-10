package com.qiapp.qi

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/** 本地提醒闹钟触发：弹通知，并从持久化列表里移除该条。 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: "提醒时间到"
        val fire = intent.getLongExtra("fire", 0L)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = "qi_reminder"
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(ch, "提醒", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val n = NotificationCompat.Builder(ctx, ch)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("⏰ 栖 · 提醒")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify((if (fire != 0L) fire else System.currentTimeMillis()).toInt(), n)

        // 从待提醒列表移除
        val prefs = ctx.getSharedPreferences("qi_reminders", Context.MODE_PRIVATE)
        val set = prefs.getStringSet("list", emptySet())?.toMutableSet() ?: mutableSetOf()
        set.remove("$fire|$text")
        prefs.edit().putStringSet("list", set).apply()
    }
}
