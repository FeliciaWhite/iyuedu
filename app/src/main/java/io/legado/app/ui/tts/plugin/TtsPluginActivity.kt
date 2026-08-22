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
    private var allGroups: List<JReadVoiceEngine.VoiceGroup> = emptyList()
    private var pluginsMap: Map<String, JReadVoiceEngine.VoicePlugin> = emptyMap()
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

        binding.fabAdd.setOnClickListener {
            when (currentTab) {
                TAB_CONFIGS -> {
                    val options = arrayOf("新增配置", "新增分组")
                    android.app.AlertDialog.Builder(this)
                        .setTitle("请选择")
                        .setItems(options) { _, which ->
                            when (which) {
                                0 -> openConfigEditor(null, true)
                                1 -> showAddGroupDialog()
                            }
                        }.show()
                }
                TAB_PLUGINS -> showPluginEditor(null)
            }
        }

        adapter.setCallbacks(
            onConfigClick = { config -> openConfigEditor(config.id, false) },
            onConfigPreview = { config -> previewConfig(config) },
            onConfigDelete = { config -> deleteConfig(config) },
            onConfigToggle = { config, enabled -> toggleConfig(config, enabled) },
            onGroupToggle = { groupName -> toggleGroup(groupName) },
            onGroupToggleEnabled = { groupName, enabled -> setGroupEnabled(groupName, null, enabled) },
            onSubGroupToggle = { groupName, subGroupName -> toggleSubGroup(groupName, subGroupName) },
            onSubGroupToggleEnabled = { groupName, subGroupName, enabled -> setGroupEnabled(groupName, subGroupName, enabled) },
            onGroupRename = { groupName, subGroupName -> showRenameDialog(groupName, subGroupName) },
            onGroupDelete = { groupName, subGroupName -> showDeleteGroupDialog(groupName, subGroupName) },
            onGroupReplacePlugin = { groupName, subGroupName -> showReplacePluginDialog(groupName, subGroupName) },
            onGroupOrganizeTags = { _, _ -> showOrganizeTagsDialog() },
            onPluginClick = { plugin -> showPluginEditor(plugin) },
            onPluginEdit = { plugin -> showPluginEditor(plugin) },
            onPluginToggle = { plugin, enabled -> togglePlugin(plugin, enabled) },
            onPluginDelete = { plugin -> deletePlugin(plugin) },
            onPluginAudioParams = { plugin -> showPluginAudioParams(plugin) },
        )
        loadData()
    }

    override fun onResume() {
        super.onResume()
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
            allPlugins = withContext(Dispatchers.IO) { JReadVoiceEngine.listPlugins(this@TtsPluginActivity) }
            pluginsMap = allPlugins.associateBy { it.id }
            adapter.setPluginsMap(pluginsMap)
            allGroups = withContext(Dispatchers.IO) { JReadVoiceEngine.listGroups(this@TtsPluginActivity) }
            when (currentTab) {
                TAB_CONFIGS -> {
                    allConfigs = withContext(Dispatchers.IO) { JReadVoiceEngine.listConfigs(this@TtsPluginActivity) }
                    android.util.Log.d("TtsPluginActivity", "loadData: configs=${allConfigs.size} groups=${allGroups.size} plugins=${allPlugins.size}")
                    binding.searchBar.visibility = View.VISIBLE
                    rebuildRows()
                }
                TAB_PLUGINS -> {
                    binding.searchBar.visibility = View.GONE
                    rebuildPluginRows()
                }
            }
            // 自动导出启用的标签到 fayinren.json
            exportEnabledTags()
        }
    }

    private fun exportEnabledTags() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val configs = JReadVoiceEngine.listConfigs(this@TtsPluginActivity)
                    val tags = configs.filter { it.enabled }
                        .map { it.voiceTag }
                        .filter { it.isNotBlank() }
                        .distinct()
                        .sorted()
                    val json = org.json.JSONArray(tags).toString(2)
                    val dir = java.io.File("/storage/emulated/0/Download/chajian/mingwuyan")
                    if (!dir.exists()) dir.mkdirs()
                    val file = java.io.File(dir, "fayinren.json")
                    file.writeText(json)
                } catch (e: Exception) {
                    // 忽略写入错误（可能没有存储权限）
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
        // 合并：从配置项中提取的分组 + 从 listGroups() 读取的分组（含空分组）
        val configGroupNames = filtered.map { it.groupName.ifBlank { "默认分组" } }.toMutableSet()
        val savedGroupNames = allGroups.map { it.groupName.ifBlank { "默认分组" } }.toSet()
        configGroupNames.addAll(savedGroupNames)
        val sortedGroupNames = configGroupNames.sorted()
        val rows = mutableListOf<ConfigListRow>()
        for (groupName in sortedGroupNames) {
            val groupConfigs = filtered.filter { it.groupName.ifBlank { "默认分组" } == groupName }
            // 默认展开所有分组（用户可手动折叠）
            val groupExpanded = expandedGroups.contains(groupName) || searchQuery.isNotEmpty()
            val allOn = groupConfigs.isNotEmpty() && groupConfigs.all { it.enabled }
            val someOn = groupConfigs.any { it.enabled }
            rows.add(ConfigListRow.GroupHeader(groupName, groupExpanded, groupConfigs.size, allOn, someOn))
            if (groupExpanded) {
                // 合并子分组：配置项中的 + listGroups 中该分组下的
                val configSubNames = groupConfigs.map { it.subGroupName.ifBlank { "默认" } }.toMutableSet()
                val savedSubNames = allGroups.filter { it.groupName.ifBlank { "默认分组" } == groupName }
                    .map { it.subGroupName.ifBlank { "默认" } }.toSet()
                configSubNames.addAll(savedSubNames)
                val sortedSubNames = configSubNames.sorted()
                for (subGroupName in sortedSubNames) {
                    val subConfigs = groupConfigs.filter { it.subGroupName.ifBlank { "默认" } == subGroupName }
                    val subExpanded = expandedSubGroups.contains(Pair(groupName, subGroupName)) || searchQuery.isNotEmpty()
                    val hasMultipleSubs = sortedSubNames.size > 1
                    if (hasMultipleSubs) {
                        val sAllOn = subConfigs.isNotEmpty() && subConfigs.all { it.enabled }
                        val sSomeOn = subConfigs.any { it.enabled }
                        rows.add(ConfigListRow.SubGroupHeader(groupName, subGroupName, subExpanded, subConfigs.size, sAllOn, sSomeOn))
                    }
                    if (subExpanded || !hasMultipleSubs) {
                        subConfigs.sortedWith(compareBy({ it.voiceTag }, { it.displayName })).forEach {
                            // 有子分组头时缩进2级，没有时缩进1级
                            rows.add(ConfigListRow.ConfigRow(it, if (hasMultipleSubs) 2 else 1))
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
        binding.layoutEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (empty) View.GONE else View.VISIBLE
        binding.tvEmptyHint.text = when (currentTab) {
            TAB_CONFIGS -> "点击右下角按钮新增配置"
            TAB_PLUGINS -> "点击右下角按钮或右上角导入插件"
            else -> ""
        }
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
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { JReadVoiceEngine.saveConfig(this@TtsPluginActivity, config.copy(enabled = enabled)) }
            loadData()
        }
    }

    private fun setGroupEnabled(groupName: String, subGroupName: String?, enabled: Boolean) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val configs = JReadVoiceEngine.listConfigs(this@TtsPluginActivity)
                val ids = configs.filter { c ->
                    c.groupName.ifBlank { "默认分组" } == groupName &&
                    (subGroupName == null || c.subGroupName.ifBlank { "默认" } == subGroupName)
                }.map { it.id }
                JReadVoiceEngine.setConfigsEnabled(this@TtsPluginActivity, ids, enabled)
            }
            loadData()
        }
    }

    private fun showRenameDialog(groupName: String, subGroupName: String?) {
        val etNewName = EditText(this).apply { setText(subGroupName ?: groupName); setPadding(48, 24, 48, 24) }
        alert("重命名") {
            customView { etNewName }
            okButton {
                val newName = etNewName.text.toString().trim()
                if (newName.isBlank()) { toastOnUi("名称不能为空"); return@okButton }
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val configs = JReadVoiceEngine.listConfigs(this@TtsPluginActivity)
                        val toUpdate = configs.filter { c ->
                            c.groupName.ifBlank { "默认分组" } == groupName &&
                            (subGroupName == null || c.subGroupName.ifBlank { "默认" } == subGroupName)
                        }
                        val updated = if (subGroupName == null) toUpdate.map { it.copy(groupName = newName) }
                        else toUpdate.map { it.copy(subGroupName = newName) }
                        JReadVoiceEngine.saveConfigsBatch(this@TtsPluginActivity, updated)
                    }
                    toastOnUi("已重命名"); loadData()
                }
            }
            cancelButton()
        }
    }

    private fun showDeleteGroupDialog(groupName: String, subGroupName: String?) {
        alert("删除确认") {
            setMessage("确定要删除分组「${subGroupName ?: groupName}」及其下所有配置吗？")
            yesButton {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        // 删除该分组下所有配置
                        val configs = JReadVoiceEngine.listConfigs(this@TtsPluginActivity)
                        configs.filter { c ->
                            c.groupName.ifBlank { "默认分组" } == groupName &&
                            (subGroupName == null || c.subGroupName.ifBlank { "默认" } == subGroupName)
                        }.forEach { JReadVoiceEngine.deleteConfig(this@TtsPluginActivity, it.id) }
                        // 删除分组记录本身
                        JReadVoiceEngine.deleteGroup(this@TtsPluginActivity, groupName, subGroupName)
                    }
                    toastOnUi("已删除分组"); loadData()
                }
            }
            noButton()
        }
    }

    private fun showReplacePluginDialog(groupName: String, subGroupName: String?) {
        lifecycleScope.launch {
            val plugins = allPlugins
            if (plugins.isEmpty()) { toastOnUi("暂无可用插件"); return@launch }
            val names = plugins.map { TtsPluginAdapter.displayNameForConfigCard(it) }
            android.app.AlertDialog.Builder(this@TtsPluginActivity)
                .setTitle("选择新插件").setItems(names.toTypedArray()) { _, which ->
                    val newPlugin = plugins[which]
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            val configs = JReadVoiceEngine.listConfigs(this@TtsPluginActivity)
                            val toUpdate = configs.filter { c ->
                                c.groupName.ifBlank { "默认分组" } == groupName &&
                                (subGroupName == null || c.subGroupName.ifBlank { "默认" } == subGroupName)
                            }.map { it.copy(pluginId = newPlugin.id) }
                            JReadVoiceEngine.saveConfigsBatch(this@TtsPluginActivity, toUpdate)
                        }
                        toastOnUi("已更换插件为 ${newPlugin.name}"); loadData()
                    }
                }.show()
        }
    }

    private fun showOrganizeTagsDialog() {
        val modes = arrayOf("仅编号", "仅风格", "编号+风格", "完整重整理")
        android.app.AlertDialog.Builder(this)
            .setTitle("一键整理标签").setItems(modes) { _, which -> toastOnUi("整理模式: ${modes[which]}（功能开发中）") }.show()
    }

    private fun showAddGroupDialog() {
        val etGroup = EditText(this).apply { hint = "一级分组名称"; setPadding(48, 24, 48, 24) }
        val etSubGroup = EditText(this).apply { hint = "二级分组名称 (可选)"; setPadding(48, 24, 48, 24) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, 0)
            addView(etGroup); addView(etSubGroup)
        }
        alert("新增分组") {
            customView { container }
            okButton {
                val groupName = etGroup.text.toString().trim()
                if (groupName.isBlank()) { toastOnUi("分组名称不能为空"); return@okButton }
                val subGroupName = etSubGroup.text.toString().trim()
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        JReadVoiceEngine.saveGroup(this@TtsPluginActivity, JReadVoiceEngine.VoiceGroup(groupName = groupName, subGroupName = subGroupName))
                    }
                    toastOnUi("已新增分组: $groupName"); loadData()
                }
            }
            cancelButton()
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
        // 编辑插件：API 密钥 + JS 代码编辑
        val userVars = runCatching { JSONObject(plugin.userVarsJson.ifBlank { "{}" }) }.getOrNull() ?: JSONObject()
        val apiValue = listOf("api", "apiKey", "api_key", "key", "token", "accessToken", "secret")
            .firstNotNullOfOrNull { key -> userVars.optString(key).takeIf { it.isNotBlank() } }.orEmpty()
        val etApi = EditText(this).apply { setText(apiValue); textSize = 13f; hint = "API 密钥" }
        val tvCodeLabel = TextView(this).apply { text = "插件 JS 代码"; textSize = 12f; setPadding(4, 12, 4, 4) }
        val etCode = EditText(this).apply {
            setText(plugin.code); textSize = 11f; setPadding(8, 8, 8, 8)
            minLines = 10; setHorizontallyScrolling(true)
            setOnTouchListener { v, event ->
                if (event.action == android.view.MotionEvent.ACTION_UP) v.parent.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0)
            addView(TextView(this@TtsPluginActivity).apply { text = plugin.name; textSize = 18f; setPadding(4, 6, 4, 2) })
            addView(TextView(this@TtsPluginActivity).apply { text = "API 密钥"; textSize = 12f; setPadding(4, 8, 4, 2) })
            addView(etApi)
            addView(tvCodeLabel)
            addView(etCode)
        }
        val scrollView = android.widget.ScrollView(this).apply { addView(container) }
        alert("编辑插件") {
            customView { scrollView }
            okButton {
                val api = etApi.text.toString().trim()
                val code = etCode.text.toString().trim()
                val merged = JSONObject(plugin.userVarsJson.ifBlank { "{}" })
                listOf("api", "apiKey", "api_key", "key", "token", "accessToken", "secret").forEach { merged.put(it, api) }
                val updated = plugin.copy(userVarsJson = merged.toString(), code = code)
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { JReadVoiceEngine.savePlugin(this@TtsPluginActivity, updated) }
                    toastOnUi("已保存插件: ${plugin.name}"); loadData()
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
                    withContext(Dispatchers.IO) { JReadVoiceEngine.updatePluginAudioParams(this@TtsPluginActivity, plugin, speed, volume, pitch) }
                    toastOnUi("已更新音频参数"); loadData()
                }
            }
            cancelButton()
        }
    }

    private fun doImport(uri: android.net.Uri) {
        lifecycleScope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: run { toastOnUi("无法读取文件"); return@withContext 0 }
                    when (currentTab) {
                        TAB_CONFIGS -> {
                            val text = bytes.toString(Charsets.UTF_8)
                            JReadVoiceEngine.importConfigsFromJson(this@TtsPluginActivity, text)
                        }
                        TAB_PLUGINS -> JReadVoiceEngine.importPluginsFromPackageBytes(this@TtsPluginActivity, bytes)
                        else -> 0
                    }
                }
                toastOnUi("导入了 $count 条"); loadData()
            } catch (e: Exception) {
                toastOnUi("导入失败: ${e.message}")
            }
        }
    }

    private fun doExport(uri: android.net.Uri) {
        lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) {
                when (currentTab) {
                    TAB_CONFIGS -> JReadVoiceEngine.exportConfigsJson(this@TtsPluginActivity)
                    TAB_PLUGINS -> JReadVoiceEngine.exportPluginsJson(this@TtsPluginActivity, includeUserVars = false)
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
