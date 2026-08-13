package com.qiapp.qi

import android.os.Bundle
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment

/**
 * 「灵魂注入」承载页：从「设置」进入，内部复用 SoulFragment 的全部逻辑
 * （灵魂卡、角色设定、标签、AI 孵化），不改动 SoulFragment 本身。
 * 原底部导航 nav_soul 标签页已移除，灵魂注入改为「设置」内的入口。
 */
class SoulActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_soul)

        findViewById<ImageButton>(R.id.soulBack).setOnClickListener { finish() }

        if (savedInstanceState == null) {
            val frag: Fragment = SoulFragment()
            supportFragmentManager.beginTransaction()
                .replace(R.id.soulHost, frag, "soul")
                .commitNow()
        }
    }
}
