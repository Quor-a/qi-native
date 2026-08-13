package com.qiapp.qi

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.qiapp.qi.databinding.FragmentSettingsBinding
import android.content.res.ColorStateList
import java.io.File

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private var _b: FragmentSettingsBinding? = null
    private val b get() = _b!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _b = FragmentSettingsBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        b.rowModel.setOnClickListener { go(ModelConfigActivity::class.java) }
        b.rowSoul.setOnClickListener { startActivity(Intent(requireContext(), SoulActivity::class.java)) }
        // 顶部「我的资料」资料头 → 用户资料编辑页
        b.profileHeader.setOnClickListener { startActivity(Intent(requireContext(), UserProfileActivity::class.java)) }
        b.rowVoice.setOnClickListener { go(VoiceServiceActivity::class.java) }
        b.rowVoiceChat.setOnClickListener { (requireActivity() as MainActivity).openChatVoiceChat() }
        b.rowPerm.setOnClickListener { go(PermissionsActivity::class.java) }
        b.rowBrowser.setOnClickListener { BrowserBridge.open(requireContext(), "https://www.bing.com") }
        b.rowStorage.setOnClickListener { go(StorageActivity::class.java) }
        b.rowAbout.setOnClickListener { go(AboutActivity::class.java) }

        bindProfileHeader()
    }

    override fun onResume() {
        super.onResume()
        if (_b != null) bindProfileHeader()
    }

    /** 把用户自己的头像 + 名字显示到设置页顶部资料头（从 用户资料 编辑页返回后刷新）。 */
    private fun bindProfileHeader() {
        val iv = b.profileAva
        val p = Config.userAvatar()
        if (p.isNotBlank() && File(p).exists()) {
            iv.background = null
            iv.imageTintList = null
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setImageURI(Uri.fromFile(File(p)))
        } else {
            iv.background = ContextCompat.getDrawable(iv.context, R.color.ink_faint)
            iv.setImageResource(R.drawable.ic_user)
            iv.imageTintList = ColorStateList.valueOf(Color.WHITE)
            iv.scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        iv.toCircle()
        b.profileName.text = Config.userName().ifBlank { "未设置名字" }
    }

    private fun go(cls: Class<*>) {
        startActivity(Intent(requireContext(), cls))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
