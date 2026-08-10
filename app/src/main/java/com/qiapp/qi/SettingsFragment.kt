package com.qiapp.qi

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.qiapp.qi.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private var _b: FragmentSettingsBinding? = null
    private val b get() = _b!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _b = FragmentSettingsBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        b.rowModel.setOnClickListener { go(ModelConfigActivity::class.java) }
        b.rowVoice.setOnClickListener { go(VoiceServiceActivity::class.java) }
        b.rowVoiceChat.setOnClickListener { (requireActivity() as MainActivity).openChatVoiceChat() }
        b.rowPerm.setOnClickListener { go(PermissionsActivity::class.java) }
        b.rowBrowser.setOnClickListener { BrowserBridge.open(requireContext(), "https://www.bing.com") }
        b.rowStorage.setOnClickListener { go(StorageActivity::class.java) }
        b.rowAbout.setOnClickListener { go(AboutActivity::class.java) }
    }

    private fun go(cls: Class<*>) {
        startActivity(Intent(requireContext(), cls))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
