package com.qiapp.qi

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.qiapp.qi.databinding.ActivityAboutBinding

class AboutActivity : AppCompatActivity() {

    private lateinit var b: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(b.root)

        findViewById<TextView>(R.id.titleText).text = "关于栖"
        findViewById<ImageButton>(R.id.backBtn).setOnClickListener { finish() }

        b.aboutAgreement.setOnClickListener { openDoc("agreement.html", "用户协议") }
        b.aboutPrivacy.setOnClickListener { openDoc("privacy.html", "隐私政策") }
        b.aboutPermission.setOnClickListener { openDoc("permissions.html", "权限使用声明") }
        b.aboutOss.setOnClickListener { openDoc("oss.html", "开源许可") }
        b.rateBtn.setOnClickListener { rate() }
    }

    private fun openDoc(asset: String, title: String) {
        startActivity(Intent(this, WebActivity::class.java)
            .putExtra("asset", asset)
            .putExtra("title", title))
    }

    private fun rate() {
        val pkg = packageName
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$pkg")))
            } catch (e: Exception) {
                toast("无法打开应用商店")
            }
        }
    }

    private fun toast(s: String) =
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()
}
