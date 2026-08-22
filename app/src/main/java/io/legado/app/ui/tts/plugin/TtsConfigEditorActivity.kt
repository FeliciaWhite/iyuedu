package io.legado.app.ui.tts.plugin

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityTtsConfigEditorBinding
import io.legado.app.help.audiobook.JReadVoiceEngine
import io.legado.app.help.audiobook.JReadVoicePluginRuntime
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

class TtsConfigEditorActivity : BaseActivity<ActivityTtsConfigEditorBinding>() {

    override val binding by viewBinding(ActivityTtsConfigEditorBinding::inflate)
    private var config: JReadVoiceEngine.VoiceConfig = JReadVoiceEngine.VoiceConfig(voiceTag = "")
    private var plugins: List<JReadVoiceEngine.VoicePlugin> = emptyList()
    private var mediaPlayer: MediaPlayer? = null

    companion object {
        const val EXTRA_CONFIG_ID = "configId"
        const val EXTRA_IS_NEW = "isNew"
        private const val MENU_SAVE = 1
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        title = if (intent.getBooleanExtra(EXTRA_IS_NEW, false)) "新增 TTS 配置" else "编辑 TTS 配置"
        initViews()
        loadData()
    }

    private fun initViews() {
        val genderOptions = listOf("女性", "男性")
        val ageOptions = listOf("儿童", "少年", "青年", "中年", "老年")
        binding.spinnerGender.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, genderOptions)
        binding.spinnerAge.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ageOptions)

        binding.btnGenerateTag.setOnClickListener { generateTimbreTag() }
        binding.btnRecognizeTag.setOnClickListener { recognizeTimbreTag() }
        binding.ivTagSearch.setOnClickListener { showTagSearchDialog() }
        binding.ivVoiceSearch.setOnClickListener { loadAndShowVoices() }
        binding.btnLoadVoices.setOnClickListener { loadAndShowVoices() }
        binding.btnPreview.setOnClickListener { previewConfig() }

        setupSlider(binding.seekbarSpeed, binding.tvSpeedValue)
        setupSlider(binding.seekbarVolume, binding.tvVolumeValue)
        setupSlider(binding.seekbarPitch, binding.tvPitchValue)

        binding.spinnerMethod.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("GET", "POST"))
    }

    private fun setupSlider(seekbar: SeekBar, tvValue: TextView) {
        seekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvValue.text = String.format("%.2f", progress / 100f)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })
    }

    private fun loadData() {
        lifecycleScope.launch {
            val configId = intent.getStringExtra(EXTRA_CONFIG_ID)
            val isNew = intent.getBooleanExtra(EXTRA_IS_NEW, false)
            if (!isNew && configId != null) {
                val configs = withContext(Dispatchers.IO) {
                    JReadVoiceEngine.listConfigs(this@TtsConfigEditorActivity)
                }
                config = configs.firstOrNull { it.id == configId } ?: JReadVoiceEngine.VoiceConfig(voiceTag = "")
            }
            plugins = withContext(Dispatchers.IO) {
                JReadVoiceEngine.listPlugins(this@TtsConfigEditorActivity)
            }
            fillForm()
        }
    }

    private fun fillForm() {
        binding.swEnabled.isChecked = config.enabled
        binding.etDisplayName.setText(config.displayName)
        binding.etVoiceTag.setText(config.voiceTag)
        binding.etGroup.setText(config.groupName)
        binding.etSubGroup.setText(config.subGroupName)
        binding.etLocale.setText(config.locale)
        binding.etVoice.setText(config.voice)
        binding.etUrl.setText(config.urlTemplate)
        binding.etHeaders.setText(config.headersText)
        binding.etBody.setText(config.bodyTemplate)
        binding.etResponsePath.setText(config.responseAudioPath)

        binding.seekbarSpeed.progress = (config.speed * 100).toInt().coerceIn(0, 300)
        binding.seekbarVolume.progress = (config.volume * 100).toInt().coerceIn(0, 300)
        binding.seekbarPitch.progress = (config.pitch * 100).toInt().coerceIn(0, 300)
        binding.tvSpeedValue.text = String.format("%.2f", config.speed)
        binding.tvVolumeValue.text = String.format("%.2f", config.volume)
        binding.tvPitchValue.text = String.format("%.2f", config.pitch)

        val methods = listOf("GET", "POST")
        binding.spinnerMethod.setSelection(methods.indexOf(config.method.ifBlank { "GET" }).coerceAtLeast(0))

        val pluginNames = listOf("单项直连/不使用插件") + plugins.map { it.name.ifBlank { it.pluginId } }
        binding.spinnerPlugin.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, pluginNames)
        val pluginIdx = plugins.indexOfFirst { it.id == config.pluginId || it.pluginId == config.pluginId }
        if (pluginIdx >= 0) binding.spinnerPlugin.setSelection(pluginIdx + 1)

        recognizeTimbreTag()
    }

    private fun generateTimbreTag() {
        val gender = binding.spinnerGender.selectedItem?.toString() ?: "女性"
        val age = binding.spinnerAge.selectedItem?.toString() ?: "青年"
        val style = binding.etStyle.text.toString().trim().ifBlank { "通用" }
        val number = binding.etTagNumber.text.toString().trim().ifBlank { "01" }
        val tag = "${gender}${age}/${style}${number}"
        binding.etVoiceTag.setText(tag)
        if (binding.etGroup.text.isBlank()) binding.etGroup.setText("性格分组演员池")
        if (binding.etSubGroup.text.isBlank()) binding.etSubGroup.setText("${gender}${age}")
        toastOnUi("已生成标签: $tag")
    }

    private fun recognizeTimbreTag() {
        val tag = binding.etVoiceTag.text.toString().trim()
        val match = Regex("^(男性|女性)(儿童|少年|青年|中年|老年)/(.+?)(\\d{1,3})?$").matchEntire(tag)
        if (match != null) {
            val genderOptions = listOf("女性", "男性")
            val ageOptions = listOf("儿童", "少年", "青年", "中年", "老年")
            binding.spinnerGender.setSelection(genderOptions.indexOf(match.groupValues[1]).coerceAtLeast(0))
            binding.spinnerAge.setSelection(ageOptions.indexOf(match.groupValues[2]).coerceAtLeast(0))
            binding.etStyle.setText(match.groupValues[3].trim())
            binding.etTagNumber.setText(match.groupValues[4].ifBlank { "01" })
        }
    }

    private fun showTagSearchDialog() {
        lifecycleScope.launch {
            val tags = withContext(Dispatchers.IO) {
                JReadVoiceEngine.listConfigs(this@TtsConfigEditorActivity)
                    .map { it.voiceTag }.distinct().sorted()
            }
            if (tags.isEmpty()) { toastOnUi("暂无可用标签"); return@launch }
            android.app.AlertDialog.Builder(this@TtsConfigEditorActivity)
                .setTitle("选择标签 (${tags.size})")
                .setItems(tags.toTypedArray()) { _, which ->
                    binding.etVoiceTag.setText(tags[which])
                    recognizeTimbreTag()
                }
                .show()
        }
    }

    private fun loadAndShowVoices() {
        val pluginIdx = binding.spinnerPlugin.selectedItemPosition
        if (pluginIdx <= 0) { toastOnUi("请先选择插件"); return }
        val plugin = plugins.getOrNull(pluginIdx - 1) ?: return
        val locale = binding.etLocale.text.toString().trim().ifBlank { "zh-CN" }
        toastOnUi("正在读取插件音色列表...")
        lifecycleScope.launch {
            val voices = withContext(Dispatchers.IO) {
                runCatching { JReadVoicePluginRuntime.listVoices(this@TtsConfigEditorActivity, plugin, locale) }
                    .getOrDefault(emptyList())
            }
            if (voices.isEmpty()) { toastOnUi("该插件无可用音色"); return@launch }
            val items = voices.map { "${it.name.ifBlank { it.id }}  (${it.id})" }.toTypedArray()
            android.app.AlertDialog.Builder(this@TtsConfigEditorActivity)
                .setTitle("选择音色 (${voices.size})")
                .setItems(items) { _, which ->
                    val voice = voices[which]
                    binding.etVoice.setText(voice.id)
                    if (binding.etDisplayName.text.isBlank())
                        binding.etDisplayName.setText(voice.name.ifBlank { voice.id })
                }
                .show()
        }
    }

    private fun previewConfig() {
        val previewText = binding.etPreviewText.text.toString().trim().ifBlank {
            toastOnUi("请输入试听文本"); return
        }
        val tempConfig = buildConfig() ?: return
        toastOnUi("正在试听: ${tempConfig.voiceTag}")
        lifecycleScope.launch {
            val requestId = UUID.randomUUID().toString()
            val pointerJson = JSONObject().put("voiceTag", tempConfig.voiceTag).toString()
            val audioBytes = withContext(Dispatchers.IO) {
                runCatching {
                    JReadVoiceEngine.synthesizeConfigLineAudio(
                        this@TtsConfigEditorActivity, tempConfig, previewText, pointerJson, requestId
                    )
                }.getOrNull()
            }
            if (audioBytes == null || audioBytes.isEmpty()) {
                toastOnUi("试听失败，请检查配置"); return@launch
            }
            val tempFile = java.io.File(cacheDir, "preview_${requestId}.wav")
            tempFile.writeBytes(audioBytes)
            playAudio(tempFile.absolutePath)
        }
    }

    private fun playAudio(path: String) {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setDataSource(path)
            prepare()
            setOnCompletionListener { it.release(); mediaPlayer = null }
            start()
        }
    }

    private fun buildConfig(): JReadVoiceEngine.VoiceConfig? {
        val voiceTag = binding.etVoiceTag.text.toString().trim()
        if (voiceTag.isBlank()) { toastOnUi("voiceTag 不能为空"); return null }
        val pluginIdx = binding.spinnerPlugin.selectedItemPosition
        val selectedPlugin = if (pluginIdx > 0) plugins.getOrNull(pluginIdx - 1) else null
        val url = binding.etUrl.text.toString().trim()
        if (selectedPlugin == null && url.isBlank()) {
            toastOnUi("未选择插件时 URL 不能为空"); return null
        }
        val voice = binding.etVoice.text.toString().trim()
        if (selectedPlugin != null && selectedPlugin.code.isNotBlank() && voice.isBlank()) {
            toastOnUi("请先读取插件音色列表，并选择一个音色 / voice"); return null
        }
        return config.copy(
            id = config.id.ifBlank { UUID.randomUUID().toString() },
            enabled = binding.swEnabled.isChecked,
            voiceTag = voiceTag,
            displayName = binding.etDisplayName.text.toString().trim(),
            groupName = binding.etGroup.text.toString().trim(),
            subGroupName = binding.etSubGroup.text.toString().trim(),
            pluginId = selectedPlugin?.id.orEmpty(),
            voice = voice,
            locale = binding.etLocale.text.toString().trim(),
            speed = binding.seekbarSpeed.progress / 100f,
            volume = binding.seekbarVolume.progress / 100f,
            pitch = binding.seekbarPitch.progress / 100f,
            method = binding.spinnerMethod.selectedItem?.toString() ?: "GET",
            urlTemplate = url,
            headersText = binding.etHeaders.text.toString().trim(),
            bodyTemplate = binding.etBody.text.toString().trim(),
            responseAudioPath = binding.etResponsePath.text.toString().trim(),
        )
    }

    override fun onCompatCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add(0, MENU_SAVE, 0, "保存").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: android.view.MenuItem): Boolean {
        when (item.itemId) {
            MENU_SAVE -> {
                val saved = buildConfig()
                if (saved != null) {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            JReadVoiceEngine.saveConfig(this@TtsConfigEditorActivity, saved)
                        }
                        toastOnUi("已保存: ${saved.voiceTag}")
                        finish()
                    }
                }
            }
            android.R.id.home -> finish()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
