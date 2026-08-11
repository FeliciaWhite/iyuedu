package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogBgmAiSettingsBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener

class BgmAISettingsDialog : BaseDialogFragment(R.layout.dialog_bgm_ai_settings) {

    private val binding by viewBinding(DialogBgmAiSettingsBinding::bind)

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initView()
        initData()
    }

    private fun initData() = binding.run {
        switchAI.isChecked = AppConfig.bgmAIEnabled
        seekAIInterval.progress = charToProgress(AppConfig.bgmAICharInterval)
        tvAIIntervalValue.text = "${AppConfig.bgmAICharInterval}字"
    }

    private fun initView() = binding.run {
        switchAI.setOnCheckedChangeListener { _, isChecked ->
            AppConfig.bgmAIEnabled = isChecked
        }

        btnAIProvider.setOnClickListener {
            BgmAIProviderDialog().show(parentFragmentManager, "bgmAIProvider")
        }

        btnAIPrompt.setOnClickListener {
            BgmAIPromptDialog().show(parentFragmentManager, "bgmAIPrompt")
        }

        seekAIInterval.setOnSeekBarChangeListener(object : SeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val chars = progressToChar(progress)
                    AppConfig.bgmAICharInterval = chars
                    tvAIIntervalValue.text = "${chars}字"
                }
            }
        })
    }

    private fun charToProgress(chars: Int): Int {
        return ((chars - 100) / 10).coerceIn(0, 90)
    }

    private fun progressToChar(progress: Int): Int {
        return (progress * 10 + 100).coerceIn(100, 1000)
    }
}
