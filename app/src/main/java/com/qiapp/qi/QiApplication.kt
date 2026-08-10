package com.qiapp.qi

import android.app.Application
import android.os.Build
import android.os.Environment
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局应用入口。
 *
 * 唯一职责（v1）：注册进程级未捕获异常处理器，把崩溃栈写到
 *   <外部私有 Download>/栖_logs/crash_<时间戳>.txt
 * 这样用户无需 adb / logcat，用手机文件管理器即可取到真栈，便于精准定位。
 * 处理完仍委托给系统默认 handler，保证「应用已停止」弹窗照常出现。
 *
 * 注册逻辑全部包在 try/catch 里，绝不影响正常启动。
 */
class QiApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
    }

    private fun installCrashLogger() {
        try {
            val default = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    writeCrash(thread, throwable)
                } catch (_: Exception) {
                    // 写日志失败不影响崩溃上报链路
                }
                default?.uncaughtException(thread, throwable)
            }
        } catch (_: Exception) {
            // 注册失败也无所谓，退回系统默认行为
        }
    }

    private fun writeCrash(thread: Thread, throwable: Throwable) {
        val base = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
        val dir = File(base, "栖_logs")
        if (!dir.exists()) dir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "crash_$ts.txt")

        val sb = StringBuilder()
        sb.append("时间: ").append(Date()).append("\n")
        sb.append("线程: ").append(thread.name).append("\n")
        sb.append("应用: 栖 (com.qiapp.qi)\n")
        sb.append("Android: ").append(Build.VERSION.RELEASE)
            .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("设备: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n")
        sb.append("===== 崩溃栈 =====\n")
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        sb.append(sw.toString())

        try {
            file.writeText(sb.toString())
        } catch (_: Exception) {
            // 写入失败忽略
        }
    }
}
