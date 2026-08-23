package io.legado.app.ui.tts.plugin

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityTtsConfigEditorBinding
import io.legado.app.help.audiobook.JReadVoiceEngine
import io.legado.app.help.audiobook.JReadVoicePluginRuntime
import io.legado.app.help.audiobook.PluginEditorSession
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
    private var localeOptions: List<JReadVoicePluginRuntime.LocaleOption> = emptyList()
    private var voiceOptions: List<JReadVoicePluginRuntime.VoiceOption> = emptyList()
    private var pluginDataFields = mutableMapOf<String, String>()
    private var editorSession: PluginEditorSession? = null

    companion object {
        const val EXTRA_CONFIG_ID = "configId"
        const val EXTRA_IS_NEW = "isNew"
        private const val MENU_SAVE = 1
        private const val KEY_PREVIEW_TEXT = "tts_preview_text"
        private const val DEFAULT_PREVIEW_TEXT = "你好呀，你吃饭了吗？"
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        title = if (intent.getBooleanExtra(EXTRA_IS_NEW, false)) "新增 TTS 配置" else "编辑 TTS 配置"
        initViews()
        loadData()
    }

    private fun initViews() {
        binding.spinnerGender.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("男", "女"))
        binding.spinnerAge.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("童", "少年", "青年", "中年", "老年"))
        binding.btnGenerateTag.setOnClickListener { generateTimbreTag() }
        binding.ivTagSearch.setOnClickListener { showTagSearchDialog() }
        binding.btnPreview.setOnClickListener { previewConfig() }
        // 试听文本持久化：打开时读取已保存内容，无则使用默认值
        binding.etPreviewText.setText(
            getPreferences(Context.MODE_PRIVATE)
                .getString(KEY_PREVIEW_TEXT, DEFAULT_PREVIEW_TEXT)
                .orEmpty()
        )
        binding.etPreviewText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                getPreferences(Context.MODE_PRIVATE).edit()
                    .putString(KEY_PREVIEW_TEXT, s?.toString().orEmpty())
                    .apply()
            }
        })
        binding.ivGroupPicker.setOnClickListener { showGroupPicker() }
        binding.ivSubGroupPicker.setOnClickListener { showSubGroupPicker() }
        // 旧格式不需要风格，隐藏
        binding.etStyle.visibility = View.GONE
        setupSlider(binding.seekbarSpeed, binding.tvSpeedValue, binding.btnSpeedMinus, binding.btnSpeedPlus)
        setupSlider(binding.seekbarVolume, binding.tvVolumeValue, binding.btnVolumeMinus, binding.btnVolumePlus)
        setupSlider(binding.seekbarPitch, binding.tvPitchValue, binding.btnPitchMinus, binding.btnPitchPlus)
        setupPostSlider(binding.seekbarPostSpeed, binding.tvPostSpeedValue, binding.btnPostSpeedMinus, binding.btnPostSpeedPlus)
        setupPostSlider(binding.seekbarPostVolume, binding.tvPostVolumeValue, binding.btnPostVolumeMinus, binding.btnPostVolumePlus)
        setupPostSlider(binding.seekbarPostPitch, binding.tvPostPitchValue, binding.btnPostPitchMinus, binding.btnPostPitchPlus)
        binding.spinnerPlugin.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long) { onPluginSelected(position) }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        binding.spinnerLocale.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long) { onLocaleSelected(position) }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        binding.spinnerVoice.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long) { onVoiceSelected(position) }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
    }

    private fun setupSlider(seekbar: SeekBar, tvValue: TextView, btnMinus: Button, btnPlus: Button) {
        seekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) { tvValue.text = String.format("%.2f", progress / 100f) }
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })
        btnMinus.setOnClickListener { seekbar.progress = (seekbar.progress - 1).coerceAtLeast(0) }
        btnPlus.setOnClickListener { seekbar.progress = (seekbar.progress + 1).coerceAtMost(300) }
    }

    private fun setupPostSlider(seekbar: SeekBar, tvValue: TextView, btnMinus: Button, btnPlus: Button) {
        seekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvValue.text = if (progress == 0) "跟随" else String.format("%.2f", progress / 100f)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })
        btnMinus.setOnClickListener { seekbar.progress = (seekbar.progress - 1).coerceAtLeast(0) }
        btnPlus.setOnClickListener { seekbar.progress = (seekbar.progress + 1).coerceAtMost(300) }
    }

    private fun loadData() {
        lifecycleScope.launch {
            val configId = intent.getStringExtra(EXTRA_CONFIG_ID)
            val isNew = intent.getBooleanExtra(EXTRA_IS_NEW, false)
            if (!isNew && configId != null) {
                val configs = withContext(Dispatchers.IO) { JReadVoiceEngine.listConfigs(this@TtsConfigEditorActivity) }
                config = configs.firstOrNull { it.id == configId } ?: JReadVoiceEngine.VoiceConfig(voiceTag = "")
            }
            plugins = withContext(Dispatchers.IO) { JReadVoiceEngine.listPlugins(this@TtsConfigEditorActivity) }
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
        binding.seekbarSpeed.progress = (config.speed * 100).toInt().coerceIn(0, 300)
        binding.seekbarVolume.progress = (config.volume * 100).toInt().coerceIn(0, 300)
        binding.seekbarPitch.progress = (config.pitch * 100).toInt().coerceIn(0, 300)
        binding.tvSpeedValue.text = String.format("%.2f", config.speed)
        binding.tvVolumeValue.text = String.format("%.2f", config.volume)
        binding.tvPitchValue.text = String.format("%.2f", config.pitch)
        // 后处理参数
        binding.seekbarPostSpeed.progress = if (config.postSpeed <= 0f) 0 else (config.postSpeed * 100).toInt().coerceIn(0, 300)
        binding.seekbarPostVolume.progress = if (config.postVolume <= 0f) 0 else (config.postVolume * 100).toInt().coerceIn(0, 300)
        binding.seekbarPostPitch.progress = if (config.postPitch <= 0f) 0 else (config.postPitch * 100).toInt().coerceIn(0, 300)
        binding.tvPostSpeedValue.text = if (config.postSpeed <= 0f) "跟随" else String.format("%.2f", config.postSpeed)
        binding.tvPostVolumeValue.text = if (config.postVolume <= 0f) "跟随" else String.format("%.2f", config.postVolume)
        binding.tvPostPitchValue.text = if (config.postPitch <= 0f) "跟随" else String.format("%.2f", config.postPitch)
        val pluginNames = listOf("单项直连/不使用插件") + plugins.map { TtsPluginAdapter.displayNameForConfigCard(it) }
        binding.spinnerPlugin.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, pluginNames)
        val pluginIdx = plugins.indexOfFirst { it.id == config.pluginId || it.pluginId == config.pluginId }
        pluginDataFields = runCatching {
            val obj = JSONObject(config.dataJson.ifBlank { "{}" })
            mutableMapOf<String, String>().apply { obj.keys().forEach { put(it, obj.optString(it)) } }
        }.getOrDefault(mutableMapOf())
        if (pluginIdx >= 0) {
            binding.spinnerPlugin.setSelection(pluginIdx + 1)
            // onPluginSelected 会被 setSelection 异步触发，不需要再手动调用 renderPluginUi
        }
    }

    private fun onPluginSelected(position: Int) {
        val plugin = if (position > 0) plugins.getOrNull(position - 1) else null
        if (plugin == null || plugin.code.isBlank()) {
            editorSession?.close()
            editorSession = null
            binding.layoutPluginUi.removeAllViews()
            binding.layoutPluginUi.visibility = View.GONE
            binding.spinnerLocale.visibility = View.GONE
            binding.etLocale.visibility = View.VISIBLE
            localeOptions = emptyList(); voiceOptions = emptyList()
            return
        }
        binding.spinnerLocale.visibility = View.VISIBLE
        binding.etLocale.visibility = View.GONE
        lifecycleScope.launch {
            // 先渲染插件 UI（创建 session），等 session 初始化完成后再查询 locales
            renderPluginUi(plugin)
            val session = editorSession
            localeOptions = if (session != null && session.isInitialized) {
                withContext(Dispatchers.IO) { runCatching { session.listLocales() }.getOrDefault(emptyList()) }
            } else {
                withContext(Dispatchers.IO) { runCatching { JReadVoicePluginRuntime.listLocales(this@TtsConfigEditorActivity, plugin) }.getOrDefault(emptyList()) }
            }
            if (localeOptions.isEmpty()) {
                binding.spinnerLocale.visibility = View.GONE
                binding.etLocale.visibility = View.VISIBLE
            } else {
                val localeNames = localeOptions.map { it.name.ifBlank { it.id } }
                binding.spinnerLocale.adapter = ArrayAdapter(this@TtsConfigEditorActivity, android.R.layout.simple_spinner_dropdown_item, localeNames)
                val currentLocale = config.locale.ifBlank { "zh-CN" }
                val localeIdx = localeOptions.indexOfFirst { it.id == currentLocale || it.name == currentLocale }
                if (localeIdx >= 0) binding.spinnerLocale.setSelection(localeIdx)
            }
        }
    }

    private suspend fun renderPluginUi(plugin: JReadVoiceEngine.VoicePlugin) {
        // 关闭旧 session
        editorSession?.close()
        editorSession = null
        binding.layoutPluginUi.removeAllViews()
        val session = PluginEditorSession(this@TtsConfigEditorActivity, plugin, pluginDataFields)
        val result = runCatching { withContext(Dispatchers.IO) { session.init() } }
        result.onFailure {
            android.util.Log.e("TtsConfigEditor", "renderPluginUi init failed: ${plugin.name}", it)
            toastOnUi("插件初始化失败: ${it.message}")
        }
        val container = result.getOrNull()
        if (container != null) {
            editorSession = session
            binding.layoutPluginUi.addView(container)
            binding.layoutPluginUi.visibility = View.VISIBLE
            android.util.Log.d("TtsConfigEditor", "renderPluginUi ok: ${plugin.name}, childCount=${container.childCount}")
            // 初始加载后触发 onVoiceChanged，让插件根据当前音色更新 UI
            val currentVoice = config.voice
            if (currentVoice.isNotBlank()) {
                val locale = if (localeOptions.isNotEmpty() && binding.spinnerLocale.selectedItemPosition < localeOptions.size) {
                    localeOptions[binding.spinnerLocale.selectedItemPosition].id
                } else config.locale.ifBlank { "zh-CN" }
                session.onVoiceChanged(locale, currentVoice)
            }
        } else {
            binding.layoutPluginUi.visibility = View.GONE
        }
    }

    private fun onLocaleSelected(position: Int) {
        if (localeOptions.isEmpty() || position >= localeOptions.size) return
        val locale = localeOptions[position].id
        val session = editorSession
        voiceOptions = emptyList()
        binding.spinnerVoice.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("加载中..."))
        lifecycleScope.launch {
            voiceOptions = withContext(Dispatchers.IO) {
                if (session != null && session.isInitialized) {
                    runCatching { session.listVoices(locale) }.getOrDefault(emptyList())
                } else {
                    val pluginIdx = binding.spinnerPlugin.selectedItemPosition
                    if (pluginIdx <= 0) emptyList()
                    else {
                        val plugin = plugins.getOrNull(pluginIdx - 1) ?: return@withContext emptyList()
                        runCatching { JReadVoicePluginRuntime.listVoices(this@TtsConfigEditorActivity, plugin, locale) }.getOrDefault(emptyList())
                    }
                }
            }
            if (voiceOptions.isEmpty()) {
                binding.spinnerVoice.adapter = ArrayAdapter(this@TtsConfigEditorActivity, android.R.layout.simple_spinner_dropdown_item, listOf("无可用音色"))
                toastOnUi("该分类无可用音色")
            } else {
                val voiceNames = voiceOptions.map { "${it.name.ifBlank { it.id }} (${it.id})" }
                binding.spinnerVoice.adapter = ArrayAdapter(this@TtsConfigEditorActivity, android.R.layout.simple_spinner_dropdown_item, voiceNames)
                // 恢复当前选中的音色
                val currentVoice = config.voice
                val voiceIdx = voiceOptions.indexOfFirst { it.id == currentVoice }
                if (voiceIdx >= 0) binding.spinnerVoice.setSelection(voiceIdx)
            }
        }
    }

    private fun onVoiceSelected(position: Int) {
        if (voiceOptions.isEmpty() || position >= voiceOptions.size) return
        val voice = voiceOptions[position]
        if (binding.etDisplayName.text.isBlank()) {
            binding.etDisplayName.setText(voice.name.ifBlank { voice.id })
        }
        notifyVoiceChanged(voice.id)
    }

    private fun notifyVoiceChanged(voiceId: String) {
        val session = editorSession ?: return
        val locale = if (localeOptions.isNotEmpty() && binding.spinnerLocale.selectedItemPosition < localeOptions.size) {
            localeOptions[binding.spinnerLocale.selectedItemPosition].id
        } else { binding.etLocale.text.toString().trim().ifBlank { "zh-CN" } }
        lifecycleScope.launch {
            session.onVoiceChanged(locale, voiceId)
        }
    }

    private fun showGroupPicker() {
        lifecycleScope.launch {
            val groups = withContext(Dispatchers.IO) {
                val voiceGroups = JReadVoiceEngine.listGroups(this@TtsConfigEditorActivity)
                val groupNames = voiceGroups.map { it.groupName }.filter { it.isNotBlank() }.distinct().sorted()
                if (groupNames.isEmpty()) {
                    JReadVoiceEngine.listConfigs(this@TtsConfigEditorActivity)
                        .map { it.groupName.ifBlank { "默认分组" } }.distinct().sorted()
                } else groupNames
            }
            if (groups.isEmpty()) { toastOnUi("暂无分组"); return@launch }
            android.app.AlertDialog.Builder(this@TtsConfigEditorActivity)
                .setTitle("选择一级分组 (${groups.size})")
                .setItems(groups.toTypedArray()) { _, which -> binding.etGroup.setText(groups[which]) }
                .show()
        }
    }

    private fun showSubGroupPicker() {
        val currentGroup = binding.etGroup.text.toString().trim()
        lifecycleScope.launch {
            val subGroups = withContext(Dispatchers.IO) {
                val voiceGroups = JReadVoiceEngine.listGroups(this@TtsConfigEditorActivity)
                val subNames = voiceGroups
                    .filter { it.groupName == currentGroup && it.subGroupName.isNotBlank() }
                    .map { it.subGroupName }.distinct().sorted()
                if (subNames.isEmpty()) {
                    JReadVoiceEngine.listConfigs(this@TtsConfigEditorActivity)
                        .filter { it.groupName.ifBlank { "默认分组" } == currentGroup.ifBlank { "默认分组" } }
                        .map { it.subGroupName.ifBlank { "默认" } }.distinct().sorted()
                } else subNames
            }
            if (subGroups.isEmpty()) { toastOnUi("该分组下暂无子分组"); return@launch }
            android.app.AlertDialog.Builder(this@TtsConfigEditorActivity)
                .setTitle("选择二级分组 (${subGroups.size})")
                .setItems(subGroups.toTypedArray()) { _, which -> binding.etSubGroup.setText(subGroups[which]) }
                .show()
        }
    }

    private fun generateTimbreTag() {
        val gender = binding.spinnerGender.selectedItem?.toString() ?: "男"
        val age = binding.spinnerAge.selectedItem?.toString() ?: "青年"
        val number = binding.etTagNumber.text.toString().trim().ifBlank { "01" }.padStart(2, '0')
        // 旧格式: 男青年01、少女01、男童01 等
        // 少年=男性，少女=女性，不需要性别前缀
        val tag = when (age) {
            "少年" -> if (gender == "女") "少女$number" else "少年$number"
            else -> "$gender$age$number"
        }
        binding.etVoiceTag.setText(tag)
        toastOnUi("已生成标签: $tag")
    }

    private fun showTagSearchDialog() {
        lifecycleScope.launch {
            val allTags = withContext(Dispatchers.IO) { JReadVoiceEngine.listConfigs(this@TtsConfigEditorActivity).map { it.voiceTag }.distinct().sorted() }
            if (allTags.isEmpty()) { toastOnUi("暂无可用标签"); return@launch }
            val context = this@TtsConfigEditorActivity
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 24, 32, 8)
            }
            val searchInput = EditText(context).apply {
                hint = "搜索标签"
                inputType = android.text.InputType.TYPE_CLASS_TEXT
                setSingleLine(true)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            }
            val listView = ListView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    resources.displayMetrics.heightPixels / 2,
                )
            }
            container.addView(searchInput)
            container.addView(listView)

            val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, allTags)
            listView.adapter = adapter

            val dialog = android.app.AlertDialog.Builder(context)
                .setTitle("选择标签 (${allTags.size})")
                .setView(container)
                .setNegativeButton("取消", null)
                .show()

            listView.setOnItemClickListener { _, _, position, _ ->
                val tag = adapter.getItem(position) ?: return@setOnItemClickListener
                binding.etVoiceTag.setText(tag)
                dialog.dismiss()
            }

            searchInput.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    adapter.filter.filter(s)
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
    }

    private fun previewConfig() {
        val previewText = binding.etPreviewText.text.toString().trim().ifBlank { toastOnUi("请输入试听文本"); return }
        val tempConfig = buildConfig() ?: return
        toastOnUi("正在试听: ${tempConfig.voiceTag}")
        lifecycleScope.launch {
            val requestId = UUID.randomUUID().toString()
            val pointerJson = JSONObject().put("voiceTag", tempConfig.voiceTag).toString()
            val audioBytes = withContext(Dispatchers.IO) {
                runCatching { JReadVoiceEngine.synthesizeConfigLineAudio(this@TtsConfigEditorActivity, tempConfig, previewText, pointerJson, requestId) }.getOrNull()
            }
            if (audioBytes == null || audioBytes.isEmpty()) { toastOnUi("试听失败"); return@launch }
            val tempFile = java.io.File(cacheDir, "preview_${requestId}.wav")
            tempFile.writeBytes(audioBytes)
            playAudio(tempFile.absolutePath)
        }
    }

    private fun playAudio(path: String) {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            setDataSource(path); prepare()
            setOnCompletionListener { it.release(); mediaPlayer = null }
            start()
        }
    }

    private fun buildDataJson(): String {
        val obj = JSONObject()
        pluginDataFields.forEach { (k, v) -> obj.put(k, v) }
        return obj.toString()
    }

    private fun buildConfig(): JReadVoiceEngine.VoiceConfig? {
        val voiceTag = binding.etVoiceTag.text.toString().trim()
        if (voiceTag.isBlank()) { toastOnUi("voiceTag 不能为空"); return null }
        val pluginIdx = binding.spinnerPlugin.selectedItemPosition
        val selectedPlugin = if (pluginIdx > 0) plugins.getOrNull(pluginIdx - 1) else null
        if (selectedPlugin == null) { toastOnUi("请选择一个插件"); return null }
        if (selectedPlugin.code.isNotBlank() && voiceOptions.isNotEmpty() && binding.spinnerVoice.selectedItemPosition < 0) { toastOnUi("请先选择一个音色"); return null }
        val voice = if (voiceOptions.isNotEmpty() && binding.spinnerVoice.selectedItemPosition in voiceOptions.indices) {
            voiceOptions[binding.spinnerVoice.selectedItemPosition].id
        } else { config.voice }
        val locale = if (localeOptions.isNotEmpty() && binding.spinnerLocale.selectedItemPosition < localeOptions.size && binding.spinnerLocale.visibility == View.VISIBLE) {
            localeOptions[binding.spinnerLocale.selectedItemPosition].id
        } else { binding.etLocale.text.toString().trim() }
        return config.copy(
            id = config.id.ifBlank { UUID.randomUUID().toString() },
            enabled = binding.swEnabled.isChecked,
            voiceTag = voiceTag,
            displayName = binding.etDisplayName.text.toString().trim(),
            groupName = binding.etGroup.text.toString().trim(),
            subGroupName = binding.etSubGroup.text.toString().trim(),
            pluginId = selectedPlugin.id,
            voice = voice,
            locale = locale,
            speed = binding.seekbarSpeed.progress / 100f,
            volume = binding.seekbarVolume.progress / 100f,
            pitch = binding.seekbarPitch.progress / 100f,
            postSpeed = binding.seekbarPostSpeed.progress / 100f,
            postVolume = binding.seekbarPostVolume.progress / 100f,
            postPitch = binding.seekbarPostPitch.progress / 100f,
            dataJson = buildDataJson(),
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
                        withContext(Dispatchers.IO) { JReadVoiceEngine.saveConfig(this@TtsConfigEditorActivity, saved) }
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
        mediaPlayer?.release(); mediaPlayer = null
    }
}
