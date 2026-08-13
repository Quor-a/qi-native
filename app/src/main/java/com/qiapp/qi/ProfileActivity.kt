package com.qiapp.qi

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * 资料页（点头像进入）：模仿聊天软件的个人资料卡。
 * - 大头像、名字、个性签名；
 * - 「栖号」：模仿聊天软件的 ID（如微信号），点一下复制到剪贴板；
 * - 「朋友圈」：进入该灵魂的朋友圈时间流；
 * - 「切换角色」：页内底部抽屉切换灵魂（复用 dialog_soul_switch）。
 *
 * 与「朋友圈」解耦：资料页是入口，朋友圈页只管展示动态。
 */
class ProfileActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        findViewById<ImageButton>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<LinearLayout>(R.id.qiIdRow).setOnClickListener { copyQiId() }
        findViewById<LinearLayout>(R.id.momentsRow).setOnClickListener {
            startActivity(Intent(this, MomentsActivity::class.java))
        }
        findViewById<Button>(R.id.switchBtn).setOnClickListener { openSoulSheet() }

        bindSoul()
    }

    private fun bindSoul() {
        val idx = AppState.currentSoul
        val av = findViewById<ImageView>(R.id.pAva)
        val file = AppState.soulAvatarFile()
        if (file != null) {
            av.background = null
            av.imageTintList = null
            av.scaleType = ImageView.ScaleType.CENTER_CROP
            av.setImageURI(Uri.fromFile(file))
        } else {
            val s = AppState.soul()
            av.background = ContextCompat.getDrawable(this, s.gradRes)
            av.setImageResource(R.drawable.ic_soul)
            av.imageTintList = ContextCompat.getColorStateList(this, android.R.color.white)
            av.scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        av.toCircle()

        findViewById<TextView>(R.id.pName).text = AppState.soulDisplayName()
        val desc = AppState.soulDisplayDesc()
        findViewById<TextView>(R.id.pDesc).text = desc.ifBlank { "" }
        findViewById<TextView>(R.id.pDesc).visibility =
            if (desc.isBlank()) View.GONE else View.VISIBLE

        findViewById<TextView>(R.id.pQiId).text = Config.soulQiId(idx)

        val sign = Config.soulDesc(idx).ifBlank { "这个人很神秘，还没写签名~" }
        findViewById<TextView>(R.id.pSign).text = sign

        val count = Config.moments(idx).size
        findViewById<TextView>(R.id.pMomentCount).text = if (count > 0) "共 $count 条动态" else "还没有动态，去发一条吧"
    }

    private fun copyQiId() {
        val id = Config.soulQiId(AppState.currentSoul)
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("栖号", id))
            toast("已复制栖号：$id")
        } catch (_: Exception) {
            toast("栖号：$id")
        }
    }

    /** 页内底部抽屉切换灵魂（复用 dialog_soul_switch，保持与聊天页一致的两张卡）。 */
    private fun openSoulSheet() {
        val d = BottomSheetDialog(this)
        d.setContentView(R.layout.dialog_soul_switch)
        val c0 = d.findViewById<View>(R.id.soulRow0)!!
        val c1 = d.findViewById<View>(R.id.soulRow1)!!
        val k0 = d.findViewById<ImageView>(R.id.soulCheck0)!!
        val k1 = d.findViewById<ImageView>(R.id.soulCheck1)!!
        fun mark() {
            k0.visibility = if (AppState.currentSoul == 0) View.VISIBLE else View.GONE
            k1.visibility = if (AppState.currentSoul == 1) View.VISIBLE else View.GONE
        }
        mark()
        c0.setOnClickListener { switchSoul(0); d.dismiss() }
        c1.setOnClickListener { switchSoul(1); d.dismiss() }
        // 资料页内不跨 Activity 跳编辑页，隐藏编辑按钮，避免导航歧义
        d.findViewById<View>(R.id.editSoulBtn)?.visibility = android.view.View.GONE
        d.show()
    }

    private fun switchSoul(x: Int) {
        AppState.currentSoul = x
        Config.currentSoul = x
        bindSoul()
    }

    private fun toast(s: String) =
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()
}
