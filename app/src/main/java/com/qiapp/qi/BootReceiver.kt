package com.qiapp.qi

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** 开机后重新排程尚未触发的本地提醒（配合 set_reminder 工具）。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = ctx.getSharedPreferences("qi_reminders", Context.MODE_PRIVATE)
        val set = prefs.getStringSet("list", emptySet()) ?: return
        if (set.isEmpty()) return
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = System.currentTimeMillis()
        val remaining = set.toMutableSet()
        set.forEach { entry ->
            val parts = entry.split("|", limit = 2)
            val fire = parts[0].toLongOrNull() ?: return@forEach
            if (fire <= now) { remaining.remove(entry); return@forEach }
            val text = parts.getOrElse(1) { "" }
            val i = Intent(ctx, ReminderReceiver::class.java)
                .putExtra("text", text).putExtra("fire", fire)
            val pi = android.app.PendingIntent.getBroadcast(
                ctx, fire.toInt(), i,
                if (Build.VERSION.SDK_INT >= 23) android.app.PendingIntent.FLAG_IMMUTABLE else 0
            )
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fire, pi)
        }
        prefs.edit().putStringSet("list", remaining).apply()
    }
}
