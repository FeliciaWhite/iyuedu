package io.legado.app.ui.tts.plugin

import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityTtsPluginBinding
import io.legado.app.help.audiobook.JReadVoiceEngine
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showHelp
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

class TtsPluginActivity : BaseActivity<ActivityTtsPluginBinding>() {

    override val binding by viewBinding(ActivityTtsPluginBinding::inflate)
    private val adapter by lazy { TtsPluginAdapter() }
    private var currentTab = TAB_CONFIGS
    private var allConfigs: List<JReadVoiceEngine.VoiceConfig> = emptyList()
    private var allPlugins: List<JReadVoiceEngine.VoicePlugin> = emptyList()
    private var expandedGroups = mutableSetOf<String>()
    private var expandedSubGroups = mutableSetOf<Pair<String, String>>()
    private var searchQuery = ""
    private var mediaPlayer: MediaPlayer? = null

    private val importLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { doImport(it) } }
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { doExport(it) } }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))

        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("配置列表"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("插件管理"))
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { currentTab = tab.position; loadData() }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s.toString().trim()
                binding.ivClear.visibility = if (searchQuery.isNotEmpty()) View.VISIBLE else View.GONE
                rebuildRows()
            }
        })
        binding.ivClear.setOnClickListener { binding.etSearch.setText(""); searchQuery = ""; rebuildRows() }

        adapter.setCallbacks(
            onConfigClick = { config -> openConfigEditor(config.id, false) },
            onConfigPreview = { config -> previewConfig(config) },
            onConfigDelete = { config -> deleteConfig(config) },
            onConfigToggle = { config, enabled -> toggleConfig(config, enabled) },
            onGroupToggle = { groupName -> toggleGroup(groupName) },
            onSubGroupToggle = { groupName, subGroupName -> toggleSubGroup(groupName, subGroupName) },
            onGroupMore = { groupName, subGroupName -> showGroupManageDialog(groupName, subGroupName) },
            onPluginClick = { plugin -> showPluginEditor(plugin) },
            onPluginToggle = { plugin, enabled -> togglePlugin(plugin, enabled) },
            onPluginDelete = { plugin -> deletePlugin(plugin) },
            onPluginAudioParams = { plugin -> showPluginAudioParams(plugin) },
        )
        loadData()
    }

    private fun toggleGroup(groupName: String) {
        if (expandedGroups.contains(groupName)) expandedGroups.remove(groupName) else expandedGroups.add(groupName)
        rebuildRows()
    }

    private fun toggleSubGroup(groupName: String, subGroupName: String) {
        val key = Pair(groupName, subGroupName)
        if (expandedSubGroups.contains(key)) expandedSubGroups.remove(key) else expandedSubGroups.add(key)
        rebuildRows()
    }

    private fun loadData() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { JReadVoiceEngine.ensureBuiltInVoicePresets(this@TtsPluginActivity) }
            when (currentTab) {
                TAB_CONFIGS -> {
                    allConfigs = withContext(Dispatchers.IO) { JReadVoiceEngine.listConfigs(this@TtsPluginActivity) }
                    binding.searchBar.visibility = View.VISIBLE
                    rebuildRows()
                }
                TAB_PLUGINS -> {
                    allPlugins = withContext(Dispatchers.IO) { JReadVoiceEngine.listPlugins(this@TtsPluginActivity) }
                    binding.searchBar.visibility = View.GONE
                    rebuildPluginRows()
                }
            }
        }
    }

    private fun rebuildRows() {
        val filtered = if (searchQuery.isEmpty()) allConfigs
        else allConfigs.filter {
            it.voiceTag.contains(searchQuery, true) || it.displayName.contains(searchQuery, true) ||
            it.groupName.contains(searchQuery, true) || it.subGroupName.contains(searchQuery, true) ||
            it.voice.contains(searchQuery, true) || it.locale.contains(searchQuery, true)
        }
        val rows = mutableListOf<ConfigListRow>()
        val groups = filtered.groupBy { it.groupName.ifBlank { "默认分组" } }
        for ((groupName, groupConfigs) in groups) {
            val groupExpanded = expandedGroups.contains(groupName) || searchQuery.isNotEmpty()
            rows.add(ConfigListRow.GroupHeader(groupName, groupExpanded, groupConfigs.size))
            if (groupExpanded) {
                val subGroups = groupConfigs.groupBy { it.subGroupName.ifBlank { "默认" } }
                for ((subGroupName, subConfigs) in subGroups) {
                    val subExpanded = expandedSubGroups.contains(Pair(groupName, subGroupName)) || searchQuery.isNotEmpty()
                    if (subGroups.size > 1) {
                        rows.add(ConfigListRow.SubGroupHeader(groupName, subGroupName, subExpanded, subConfigs.size))
                    }
                    if (subExpanded || subGroups.size <= 1) {
                        subConfigs.sortedWith(compareBy({ it.voiceTag }, { it.displayName })).forEach {
                            rows.add(ConfigListRow.ConfigRow(it))
                        }
                    }
                }
            }
        }
        updateEmpty(rows.isEmpty())
        adapter.setRows(rows)
    }

    private fun rebuildPluginRows() {
        val rows = allPlugins.map { ConfigListRow.PluginRow(it) }
        updateEmpty(rows.isEmpty())
        adapter.setRows(rows)
    }

    private fun updateEmpty(empty: Boolean) {
        binding.tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (empty) View.GONE else View.VISIBLE
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.tts_plugin, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_add -> when (currentTab) {
                TAB_CONFIGS -> openConfigEditor(null, true)
                TAB_PLUGINS -> showPluginEditor(null)
            }
            R.id.menu_init_builtins -> lifecycleScope.launch {
                withContext(Dispatchers.IO) { JReadVoiceEngine.ensureBuiltInVoicePresets(this@TtsPluginActivity) }
                toastOnUi("内置配置已初始化"); loadData()
            }
            R.id.menu_import -> importLauncher.launch("*/*")
            R.id.menu_export -> {
                val suffix = if (currentTab == TAB_CONFIGS) "configs" else "plugins"
                exportLauncher.launch("tts_${suffix}_${System.currentTimeMillis()}.json")
            }
            R.id.menu_list_tags -> lifecycleScope.launch {
                val tags = withContext(Dispatchers.IO) {
                    JReadVoiceEngine.listConfigs(this@TtsPluginActivity).filter { it.enabled }.map { it.voiceTag }.distinct()
                }
                if (tags.isEmpty()) toastOnUi("暂无可用标签")
                else alert("可用标签 (${tags.size})") { setMessage(tags.joinToString("\n")); okButton() }
            }
            R.id.menu_help -> showHelp("ttsPluginHelp")
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun openConfigEditor(configId: String?, isNew: Boolean) {
        startActivity(Intent(this, TtsConfigEditorActivity::class.java).apply {
            putExtra(TtsConfigEditorActivity.EXTRA_CONFIG_ID, configId)
            putExtra(TtsConfigEditorActivity.EXTRA_IS_NEW, isNew)
        })
    }

    private fun previewConfig(config: JReadVoiceEngine.VoiceConfig) {
        toastOnUi("正在试听: ${config.voiceTag}")
        lifecycleScope.launch {
            val requestId = UUID.randomUUID().toString()
            val pointerJson = JSONObject().put("voiceTag", config.voiceTag).toString()
            val audioBytes = withContext(Dispatchers.IO) {
                runCatching { JReadVoiceEngine.synthesizeConfigLineAudio(this@TtsPluginActivity, config, "试听文本。", pointerJson, requestId) }.getOrNull()
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

    private fun deleteConfig(config: JReadVoiceEngine.VoiceConfig) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { JReadVoiceEngine.deleteConfig(this@TtsPluginActivity, config.id) }
            toastOnUi("已删除: ${config.voiceTag}"); loadData()
        }
    }

    private fun toggleConfig(config: JReadVoiceEngine.VoiceConfig, enabled: Boolean) {
        lifecycleScope.launch { withContext(Dispatchers.IO) { JReadVoiceEngine.saveConfig(this@TtsPluginActivity, config.copy(enabled = enabled)) } }
    }

    private fun showGroupManageDialog(groupName: String, subGroupName: String?) {
        alert("分组管理") {
            setMessage("分组: $groupName${subGroupName?.let { " / $it" }.orEmpty()}\n\n功能开发中")
            okButton()
        }
    }

    private fun showPluginEditor(plugin: JReadVoiceEngine.VoicePlugin?) {
        if (plugin == null) {
            // 新增插件：提供完整的代码输入框
            val etName = EditText(this).apply { hint = "插件名称"; setPadding(4, 8, 4, 8) }
            val etPluginId = EditText(this).apply { hint = "插件 ID (可选，留空自动生成)"; setPadding(4, 8, 4, 8) }
            val tvCodeLabel = TextView(this).apply { text = "插件 JS 代码"; textSize = 12f; setPadding(4, 12, 4, 4) }
            val etCode = EditText(this).apply {
                hint = "在此粘贴完整的插件 JS 代码..."
                setPadding(8, 8, 8, 8)
                minLines = 15
                setHorizontallyScrolling(true)
                setOnTouchListener { v, event ->
                    if (event.action == android.view.MotionEvent.ACTION_UP) {
                        v.parent.requestDisallowInterceptTouchEvent(true)
                    }
                    false
                }
            }
            val tvHint = TextView(this).apply {
                text = "也可从右上角菜单「导入」J.TTS 插件 JSON 文件。"
                textSize = 11f; setPadding(4, 8, 4, 4)
            }
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0)
                addView(etName); addView(etPluginId); addView(tvCodeLabel); addView(etCode); addView(tvHint)
            }
            val scrollView = android.widget.ScrollView(this).apply { addView(container) }
            alert("新增声音插件") {
                customView { scrollView }
                okButton {
                    val name = etName.text.toString().trim()
                    val code = etCode.text.toString().trim()
                    if (name.isBlank()) { toastOnUi("插件名称不能为空"); return@okButton }
                    if (code.isBlank()) { toastOnUi("插件代码不能为空"); return@okButton }
                    val newPlugin = JReadVoiceEngine.VoicePlugin(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        pluginId = etPluginId.text.toString().trim().ifBlank { name },
                        code = code,
                        enabled = true,
                    )
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { JReadVoiceEngine.savePlugin(this@TtsPluginActivity, newPlugin) }
                        toastOnUi("已新增插件: $name"); loadData()
                    }
                }
                cancelButton()
            }
            return
        }
        // 编辑插件：只填写 API 密钥
        val userVars = runCatching { JSONObject(plugin.userVarsJson.ifBlank { "{}" }) }.getOrNull() ?: JSONObject()
        val apiValue = listOf("api", "apiKey", "api_key", "key", "token", "accessToken", "secret")
            .firstNotNullOfOrNull { key -> userVars.optString(key).takeIf { it.isNotBlank() } }.orEmpty()
        val etApi = EditText(this).apply { setText(apiValue); textSize = 13f }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0)
            addView(TextView(this@TtsPluginActivity).apply { text = plugin.name; textSize = 18f; setPadding(4, 6, 4, 2) })
            addView(TextView(this@TtsPluginActivity).apply { text = "只需要填写 API。保存后会自动写入 api/apiKey/key/token 等兼容字段。"; textSize = 12f; setPadding(4, 0, 4, 8) })
            addView(TextView(this@TtsPluginActivity).apply { text = "API"; textSize = 12f; setPadding(4, 8, 4, 2) })
            addView(etApi)
        }
        alert("插件 API") {
            customView { container }
            okButton {
                val api = etApi.text.toString().trim()
                val merged = JSONObject(plugin.userVarsJson.ifBlank { "{}" })
                listOf("api", "apiKey", "api_key", "key", "token", "accessToken", "secret").forEach { merged.put(it, api) }
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { JReadVoiceEngine.savePlugin(this@TtsPluginActivity, plugin.copy(userVarsJson = merged.toString())) }
                    toastOnUi("已保存插件 API: ${plugin.name}"); loadData()
                }
            }
            cancelButton()
        }
    }

    private fun togglePlugin(plugin: JReadVoiceEngine.VoicePlugin, enabled: Boolean) {
        lifecycleScope.launch { withContext(Dispatchers.IO) { JReadVoiceEngine.savePlugin(this@TtsPluginActivity, plugin.copy(enabled = enabled)) } }
    }

    private fun deletePlugin(plugin: JReadVoiceEngine.VoicePlugin) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { JReadVoiceEngine.deletePlugin(this@TtsPluginActivity, plugin.id) }
            toastOnUi("已删除插件: ${plugin.name}"); loadData()
        }
    }

    private fun showPluginAudioParams(plugin: JReadVoiceEngine.VoicePlugin) {
        val configs = allConfigs.filter { it.pluginId == plugin.id || it.pluginId == plugin.pluginId }
        val firstConfig = configs.firstOrNull()
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        container.addView(TextView(this).apply { text = "${plugin.name} (${configs.size}个音色项)"; textSize = 14f; setPadding(4, 4, 4, 12) })
        val sbSpeed = SeekBar(this).apply { max = 300; progress = ((firstConfig?.speed ?: 1f) * 100).toInt() }
        val sbVol = SeekBar(this).apply { max = 300; progress = ((firstConfig?.volume ?: 1f) * 100).toInt() }
        val sbPitch = SeekBar(this).apply { max = 300; progress = ((firstConfig?.pitch ?: 1f) * 100).toInt() }
        val tvSpeedVal = TextView(this).apply { text = String.format("%.2f", sbSpeed.progress / 100f); textSize = 11f }
        val tvVolVal = TextView(this).apply { text = String.format("%.2f", sbVol.progress / 100f); textSize = 11f }
        val tvPitchVal = TextView(this).apply { text = String.format("%.2f", sbPitch.progress / 100f); textSize = 11f }
        listOf(Triple("语速", sbSpeed, tvSpeedVal), Triple("音量", sbVol, tvVolVal), Triple("音调", sbPitch, tvPitchVal)).forEach { (label, sb, tv) ->
            container.addView(TextView(this).apply { text = label; textSize = 12f })
            container.addView(sb); container.addView(tv)
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { tv.text = String.format("%.2f", p / 100f) }
                override fun onStartTrackingTouch(s: SeekBar?) = Unit
                override fun onStopTrackingTouch(s: SeekBar?) = Unit
            })
        }
        alert("音频参数") {
            customView { container }
            okButton {
                val speed = sbSpeed.progress / 100f; val volume = sbVol.progress / 100f; val pitch = sbPitch.progress / 100f
                lifecycleScope.launch {
                    var count = 0
                    withContext(Dispatchers.IO) {
                        configs.forEach { c -> JReadVoiceEngine.saveConfig(this@TtsPluginActivity, c.copy(speed = speed, volume = volume, pitch = pitch)); count++ }
                    }
                    toastOnUi("已更新 $count 个音色项"); loadData()
                }
            }
            cancelButton()
        }
    }

    private fun doImport(uri: android.net.Uri) {
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                val raw = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return@withContext 0
                when (currentTab) {
                    TAB_CONFIGS -> JReadVoiceEngine.importConfigsFromJson(this@TtsPluginActivity, raw)
                    TAB_PLUGINS -> JReadVoiceEngine.importPluginsFromJson(this@TtsPluginActivity, raw)
                    else -> 0
                }
            }
            toastOnUi("导入了 $count 条"); loadData()
        }
    }

    private fun doExport(uri: android.net.Uri) {
        lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) {
                when (currentTab) {
                    TAB_CONFIGS -> JReadVoiceEngine.exportConfigsJson(this@TtsPluginActivity)
                    TAB_PLUGINS -> JReadVoiceEngine.exportPluginsJson(this@TtsPluginActivity)
                    else -> "[]"
                }
            }
            contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
            toastOnUi("导出完成")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release(); mediaPlayer = null
    }

    companion object {
        private const val TAB_CONFIGS = 0
        private const val TAB_PLUGINS = 1
    }
}
