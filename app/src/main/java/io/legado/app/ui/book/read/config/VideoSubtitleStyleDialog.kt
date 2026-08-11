package io.legado.app.ui.book.read.config

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogVideoSubtitleStyleBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 视频字幕样式设置：字体大小 / 字体颜色 / 描边粗细 / 描边颜色，
 * 顶部提供与成片一致的实时预览。
 */
class VideoSubtitleStyleDialog : BaseDialogFragment(R.layout.dialog_video_subtitle_style) {

    private val binding by viewBinding(DialogVideoSubtitleStyleBinding::bind)

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        // 用当前已保存值初始化输入框
        binding.etFontSize.setText(AppConfig.videoSubtitleFontSizeScale.toString())
        binding.etFontColor.setText(AppConfig.videoSubtitleFontColor)
        binding.etStrokeWidth.setText(AppConfig.videoSubtitleStrokeWidth.toString())
        binding.etStrokeColor.setText(AppConfig.videoSubtitleStrokeColor)

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refreshPreview()
        }
        binding.etFontSize.addTextChangedListener(watcher)
        binding.etFontColor.addTextChangedListener(watcher)
        binding.etStrokeWidth.addTextChangedListener(watcher)
        binding.etStrokeColor.addTextChangedListener(watcher)

        refreshPreview()

        binding.btnCancel.setOnClickListener { dismiss() }
        binding.btnSave.setOnClickListener {
            AppConfig.videoSubtitleFontSizeScale =
                binding.etFontSize.text.toString().toFloatOrNull() ?: 1.0f
            AppConfig.videoSubtitleFontColor =
                binding.etFontColor.text.toString().ifBlank { "#FFFFDD00" }
            AppConfig.videoSubtitleStrokeWidth =
                binding.etStrokeWidth.text.toString().toFloatOrNull() ?: 0.12f
            AppConfig.videoSubtitleStrokeColor =
                binding.etStrokeColor.text.toString().ifBlank { "#FF000000" }
            dismiss()
        }
    }

    private fun refreshPreview() {
        val fontSizeScale = binding.etFontSize.text.toString().toFloatOrNull() ?: 1.0f
        val fontColor = parseColor(binding.etFontColor.text.toString(), Color.BLACK)
        val strokeWidthRatio = binding.etStrokeWidth.text.toString().toFloatOrNull() ?: 0.12f
        val strokeColor = parseColor(binding.etStrokeColor.text.toString(), Color.WHITE)
        binding.previewView.update(fontSizeScale, fontColor, strokeWidthRatio, strokeColor)
    }

    private fun parseColor(s: String, fallback: Int): Int {
        return runCatching { Color.parseColor(s) }.getOrDefault(fallback)
    }
}
