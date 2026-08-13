package com.qiapp.qi

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.content.res.ColorStateList
import java.io.File

/**
 * 用户自己的资料页（编辑 + 卡片合一）：
 * - 头像（点按从相册选图 → 交互式圆形裁剪，复用 AvatarCropActivity，idx=99 落到 avatar_99.png）；
 * - 名称（显示在自己发的消息、自己发的朋友圈，并注入 AI 系统提示词）；
 * - 「我的朋友圈」入口（进入合并的朋友圈流，可看到自己发的动态）。
 */
class UserProfileActivity : AppCompatActivity() {

    /** 用户头像裁剪哨兵 idx：落到 filesDir/avatar_99.png，与灵魂头像(0/1)区分。 */
    private val USER_AVA_IDX = 99

    /** 选图 → 复制到私有缓存 → 进交互式裁剪页 */
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        val tmp = copyUriToCache(uri)
        if (tmp == null) { toast("无法读取图片"); return@registerForActivityResult }
        val intent = Intent(this, AvatarCropActivity::class.java).apply {
            putExtra("src", tmp.absolutePath)
            putExtra("idx", USER_AVA_IDX)
        }
        cropLauncher.launch(intent)
    }

    /** 接收裁剪结果：裁剪页已写出 avatar_99.png，这里落盘并刷新 */
    private val cropLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val path = res.data?.getStringExtra("path")
            if (path != null) {
                Config.setUserAvatar(path)
                showAvatar()
                toast("头像已更新")
            } else {
                toast("裁剪未返回图片")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_profile)

        findViewById<ImageButton>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<ImageView>(R.id.uAva).setOnClickListener { pickImage.launch("image/*") }
        findViewById<LinearLayout>(R.id.momentsRow).setOnClickListener {
            startActivity(Intent(this, MomentsActivity::class.java))
        }
        findViewById<Button>(R.id.saveBtn).setOnClickListener { save() }

        bind()
    }

    override fun onResume() {
        super.onResume()
        bind()
    }

    private fun bind() {
        showAvatar()
        val nameEt = findViewById<EditText>(R.id.uName)
        if (nameEt.text.toString() != Config.userName()) nameEt.setText(Config.userName())
        val count = Config.userMoments().size
        findViewById<TextView>(R.id.uMomentCount).text =
            if (count > 0) "共 $count 条动态" else "还没有动态，去朋友圈发一条吧"
    }

    private fun showAvatar() {
        val iv = findViewById<ImageView>(R.id.uAva)
        val p = Config.userAvatar()
        if (p.isNotBlank() && File(p).exists()) {
            iv.background = null
            iv.imageTintList = null
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setImageURI(Uri.fromFile(File(p)))
        } else {
            iv.background = ContextCompat.getDrawable(this, R.color.ink_faint)
            iv.setImageResource(R.drawable.ic_user)
            iv.imageTintList = ColorStateList.valueOf(Color.WHITE)
            iv.scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        iv.toCircle()
    }

    private fun save() {
        val name = findViewById<EditText>(R.id.uName).text.toString().trim()
        Config.setUserName(name)
        toast("已保存")
        finish()
    }

    /** 把 content uri 复制到 app 私有缓存，供裁剪页无权限顾虑地读取 */
    private fun copyUriToCache(uri: Uri): File? {
        return try {
            val ext = if (uri.toString().contains("png", ignoreCase = true)) "png" else "jpg"
            val f = File(cacheDir, "crop_user.$ext")
            contentResolver.openInputStream(uri)?.use { input ->
                f.outputStream().use { out -> input.copyTo(out) }
            }
            if (f.length() > 0) f else null
        } catch (e: Exception) { null }
    }

    private fun toast(s: String) =
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()
}
