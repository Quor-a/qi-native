package com.qiapp.qi

import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.qiapp.qi.databinding.ActivityStorageBinding
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class StorageActivity : AppCompatActivity() {

    private lateinit var b: ActivityStorageBinding

    private val importLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { r ->
                val msgs = ChatStore.deserialize(r.readText())
                if (msgs == null) {
                    toast("导入失败：文件格式不正确")
                    return@use
                }
                AppState.importIntoCurrent(this, msgs)
                toast("已导入 ${msgs.size} 条消息到当前对话")
            }
        } catch (e: Exception) {
            toast("导入失败：${e.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityStorageBinding.inflate(layoutInflater)
        setContentView(b.root)

        findViewById<TextView>(R.id.titleText).text = "存储与备份"
        findViewById<ImageButton>(R.id.backBtn).setOnClickListener { finish() }

        refreshCache()

        b.exportChatBtn.setOnClickListener {
            val dir = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "栖")
            dir.mkdirs()
            val f = File(dir, "chat.json")
            try {
                f.writeText(ChatStore.serialize(AppState.messages))
                toast("聊天记录已导出到：\n${f.absolutePath}")
            } catch (e: Exception) {
                toast("导出失败：${e.message}")
            }
        }
        b.exportSoulBtn.setOnClickListener {
            val dir = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "栖")
            dir.mkdirs()
            val f = File(dir, "soul.json")
            try {
                val arr = JSONArray()
                AppState.baseSouls.forEachIndexed { i, _ ->
                    arr.put(JSONObject().put("name", Config.soulName(i)).put("desc", Config.soulDesc(i)))
                }
                f.writeText(arr.toString())
                toast("灵魂卡已导出到：\n${f.absolutePath}")
            } catch (e: Exception) {
                toast("导出失败：${e.message}")
            }
        }
        b.importBtn.setOnClickListener { importLauncher.launch("application/json") }
        b.clearCacheBtn.setOnClickListener {
            val dir = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "栖")
            dir.listFiles()?.forEach { it.delete() }
            refreshCache()
            toast("缓存已清理")
        }
    }

    private fun refreshCache() {
        val dir = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "栖")
        val bytes = dir.listFiles()?.sumOf { it.length() } ?: 0
        val mb = if (bytes == 0L) "0" else String.format("%.1f", bytes / 1048576.0)
        b.cacheSize.text = "当前缓存 $mb MB"
    }

    private fun toast(s: String) =
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show()
}
