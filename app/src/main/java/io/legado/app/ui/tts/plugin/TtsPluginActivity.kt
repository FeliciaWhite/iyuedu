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
import io.legado.app.help.audiobook.PostAudioParams
import io.legado.app.help.config.AppConfig
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
        // 长按拖拽排序配置项
        adapter.onConfigMoved = { orderedIds ->
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { JReadVoiceEngine.saveConfigsSortOrder(this@TtsPluginActivity, orderedIds) }
            }
        }
        adapter.onGroupMoved = { orderedGroupNames ->
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    val groups = JReadVoiceEngine.listGroups(this@TtsPluginActivity)
                    val nameToOrder = orderedGroupNames.mapIndexed { index, name -> name to index }.toMap()
                    val updated = groups.map { g ->
                        if (g.subGroupName.isBlank() && g.thirdGroupName.isBlank()) {
                            g.copy(sortOrder = nameToOrder[g.groupName] ?: g.sortOrder)
                        } else g
                    }
                    JReadVoiceEngine.saveGroups(this@TtsPluginActivity, updated)
                }
            }
        }
        adapter.onSubGroupMoved = { parentGroupName, orderedSubNames ->
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    val groups = JReadVoiceEngine.listGroups(this@TtsPluginActivity)
                    val nameToOrder = orderedSubNames.mapIndexed { index, name -> name to index }.toMap()
                    val updated = groups.map { g ->
                        if (g.groupName == parentGroupName && g.subGroupName.isNotBlank() && g.thirdGroupName.isBlank()) {
                            g.copy(sortOrder = nameToOrder[g.subGroupName] ?: g.sortOrder)
                        } else g
                    }
                    JReadVoiceEngine.saveGroups(this@TtsPluginActivity, updated)
                }
            }
        }
        adapter.createItemTouchHelper().attachToRecyclerView(binding.recyclerView)

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
            onGroupAudioParams = { groupName, subGroupName -> showGroupAudioParamsDialog(groupName, subGroupName) },
            onGroupMoveUp = { groupName, subGroupName -> moveGroup(groupName, subGroupName, up = true) },
            onGroupMoveDown = { groupName, subGroupName -> moveGroup(groupName, subGroupName, up = false) },
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
        // 按 sortOrder 排序，sortOrder 相同则按名称
        val configGroupNames = filtered.map { it.groupName.ifBlank { "默认分组" } }.toMutableSet()
        val savedGroupNames = allGroups.map { it.groupName.ifBlank { "默认分组" } }.toSet()
        configGroupNames.addAll(savedGroupNames)
        val groupSortMap = allGroups
            .filter { it.subGroupName.isBlank() && it.thirdGroupName.isBlank() }
            .associate { it.groupName.ifBlank { "默认分组" } to it.sortOrder }
        val sortedGroupNames = configGroupNames.sortedWith(compareBy(
            { groupSortMap[it] ?: Int.MAX_VALUE },
            { it }
        ))
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
                val subSortMap = allGroups
                    .filter { it.groupName.ifBlank { "默认分组" } == groupName && it.subGroupName.isNotBlank() && it.thirdGroupName.isBlank() }
                    .associate { it.subGroupName.ifBlank { "默认" } to it.sortOrder }
                val sortedSubNames = configSubNames.sortedWith(compareBy(
                    { subSortMap[it] ?: Int.MAX_VALUE },
                    { it }
                ))
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
                        subConfigs.sortedWith(compareBy({ it.sortOrder }, { it.voiceTag }, { it.displayName })).forEach {
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
            R.id.menu_global_audio_params -> showGlobalAudioParamsDialog()
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

    private fun moveGroup(groupName: String, subGroupName: String?, up: Boolean) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val groups = JReadVoiceEngine.listGroups(this@TtsPluginActivity)
                if (subGroupName == null) {
                    // 移动大分组：筛选出大分组级别（subGroupName 为空）
                    val mainGroups = groups.filter { it.subGroupName.isBlank() && it.thirdGroupName.isBlank() }
                        .sortedBy { it.sortOrder }
                    val idx = mainGroups.indexOfFirst { it.groupName == groupName }
                    if (idx < 0) return@withContext
                    val swapIdx = if (up) idx - 1 else idx + 1
                    if (swapIdx < 0 || swapIdx >= mainGroups.size) return@withContext
                    val a = mainGroups[idx]; val b = mainGroups[swapIdx]
                    val updated = groups.map { g ->
                        when (g.id) {
                            a.id -> g.copy(sortOrder = b.sortOrder)
                            b.id -> g.copy(sortOrder = a.sortOrder)
                            else -> g
                        }
                    }
                    JReadVoiceEngine.saveGroups(this@TtsPluginActivity, updated)
                } else {
                    // 移动子分组：筛选出同一大分组下的子分组级别
                    val subGroups = groups
                        .filter { it.groupName == groupName && it.subGroupName.isNotBlank() && it.thirdGroupName.isBlank() }
                        .sortedBy { it.sortOrder }
                    val idx = subGroups.indexOfFirst { it.subGroupName == subGroupName }
                    if (idx < 0) return@withContext
                    val swapIdx = if (up) idx - 1 else idx + 1
                    if (swapIdx < 0 || swapIdx >= subGroups.size) return@withContext
                    val a = subGroups[idx]; val b = subGroups[swapIdx]
                    val updated = groups.map { g ->
                        when (g.id) {
                            a.id -> g.copy(sortOrder = b.sortOrder)
                            b.id -> g.copy(sortOrder = a.sortOrder)
                            else -> g
                        }
                    }
                    JReadVoiceEngine.saveGroups(this@TtsPluginActivity, updated)
                }
            }
            loadData()
        }
    }

    private fun showGroupAudioParamsDialog(groupName: String, subGroupName: String?) {
        lifecycleScope.launch {
            val groups = withContext(Dispatchers.IO) { JReadVoiceEngine.listGroups(this@TtsPluginActivity) }
            val group = groups.firstOrNull {
                it.groupName == groupName &&
                (subGroupName == null || it.subGroupName == subGroupName) &&
                it.thirdGroupName.isBlank()
            }
            val curSpeed = group?.postSpeed ?: PostAudioParams.FOLLOW
            val curVolume = group?.postVolume ?: PostAudioParams.FOLLOW
            val curPitch = group?.postPitch ?: PostAudioParams.FOLLOW

            val container = LinearLayout(this@TtsPluginActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0)
            }
            container.addView(TextView(this@TtsPluginActivity).apply {
                text = "音频后处理调节（合成后生效）\n0 = 跟随上级，1.0 = 不调整"
                textSize = 12f; setPadding(4, 4, 4, 12)
            })
            val (sbSpeed, _, _) = buildSliderRow("语速", if (curSpeed <= 0f) 0 else (curSpeed * 100).toInt(), true)
            val (sbVol, _, _) = buildSliderRow("音量", if (curVolume <= 0f) 0 else (curVolume * 100).toInt(), true)
            val (sbPitch, _, _) = buildSliderRow("音高", if (curPitch <= 0f) 0 else (curPitch * 100).toInt(), true)
            listOf(sbSpeed, sbVol, sbPitch).forEach { container.addView(it.tag as android.view.View) }
            alert("音频调节 - ${subGroupName ?: groupName}") {
                customView { container }
                okButton {
                    val speed = sbSpeed.progress / 100f
                    val volume = sbVol.progress / 100f
                    val pitch = sbPitch.progress / 100f
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            val existing = JReadVoiceEngine.listGroups(this@TtsPluginActivity)
                                .firstOrNull { it.groupName == groupName && (subGroupName == null || it.subGroupName == subGroupName) && it.thirdGroupName.isBlank() }
                            val updated = (existing ?: JReadVoiceEngine.VoiceGroup(groupName = groupName, subGroupName = subGroupName ?: "")).copy(
                                postSpeed = speed, postVolume = volume, postPitch = pitch
                            )
                            JReadVoiceEngine.saveGroup(this@TtsPluginActivity, updated)
                        }
                        toastOnUi("已更新音频调节"); loadData()
                    }
                }
                cancelButton()
            }.show()
        }
    }

    private fun showGlobalAudioParamsDialog() {
        val curSpeed = AppConfig.ttsPostSpeed
        val curVolume = AppConfig.ttsPostVolume
        val curPitch = AppConfig.ttsPostPitch

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0)
        }
        container.addView(TextView(this).apply {
            text = "全局音频后处理调节（合成后生效）\n1.0 = 不调整，范围 0.1~3.0"
            textSize = 12f; setPadding(4, 4, 4, 12)
        })
        val (sbSpeed, _, _) = buildSliderRow("语速", (curSpeed * 100).toInt().coerceIn(1, 300), false)
        val (sbVol, _, _) = buildSliderRow("音量", (curVolume * 100).toInt().coerceIn(1, 300), false)
        val (sbPitch, _, _) = buildSliderRow("音高", (curPitch * 100).toInt().coerceIn(1, 300), false)
        listOf(sbSpeed, sbVol, sbPitch).forEach { container.addView(it.tag as android.view.View) }
        alert("全局音频调节") {
            customView { container }
            okButton {
                AppConfig.ttsPostSpeed = sbSpeed.progress / 100f
                AppConfig.ttsPostVolume = sbVol.progress / 100f
                AppConfig.ttsPostPitch = sbPitch.progress / 100f
                toastOnUi("已更新全局音频调节")
            }
            cancelButton()
        }.show()
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

    /**
     * 构建一个滑块行：标签+数值居中在上方，减号+滑块+加号在下方
     * @param label 标签名（语速/音量/音高/音调）
     * @param initialProgress 初始进度 (0~300)
     * @param allowFollow 是否支持"跟随"（progress=0 时显示"跟随"）
     * @return Triple(SeekBar, TextView, 更新显示的函数)
     */
    private fun buildSliderRow(
        label: String, initialProgress: Int, allowFollow: Boolean
    ): Triple<SeekBar, TextView, () -> Unit> {
        val ctx = this
        val density = resources.displayMetrics.density
        val sb = SeekBar(ctx).apply { max = 300; progress = initialProgress }
        val tvVal = TextView(ctx).apply {
            textSize = 13f
            text = if (allowFollow && initialProgress == 0) "跟随" else String.format("%.2f", initialProgress / 100f)
        }
        val updateText: () -> Unit = {
            val p = sb.progress
            tvVal.text = if (allowFollow && p == 0) "跟随" else String.format("%.2f", p / 100f)
        }
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) = updateText()
            override fun onStartTrackingTouch(s: SeekBar?) = Unit
            override fun onStopTrackingTouch(s: SeekBar?) = Unit
        })
        // 标签+数值行（居中）
        val labelRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER
            addView(TextView(ctx).apply { text = label; textSize = 13f; setTextColor(android.graphics.Color.parseColor("#FF333333")) })
            addView(TextView(ctx).apply { text = "  "; textSize = 13f })
            addView(tvVal)
        }
        // 滑块行（减号 + 滑块 + 加号）
        val sliderRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            val btnMinus = android.widget.Button(ctx).apply {
                text = "−"; textSize = 18f; layoutParams = LinearLayout.LayoutParams((40 * density).toInt(), (36 * density).toInt())
                minWidth = 0; minHeight = 0; setPadding(0, 0, 0, 0)
                setOnClickListener { sb.progress = (sb.progress - 1).coerceAtLeast(0) }
            }
            val sbParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            val btnPlus = android.widget.Button(ctx).apply {
                text = "+"; textSize = 18f; layoutParams = LinearLayout.LayoutParams((40 * density).toInt(), (36 * density).toInt())
                minWidth = 0; minHeight = 0; setPadding(0, 0, 0, 0)
                setOnClickListener { sb.progress = (sb.progress + 1).coerceAtMost(300) }
            }
            addView(btnMinus); addView(sb, sbParams); addView(btnPlus)
        }
        // 外层垂直布局
        val outer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(labelRow); addView(sliderRow)
        }
        // 用 tag 存 outer，调用方 addView(outer) 即可
        sb.tag = outer
        return Triple(sb, tvVal, updateText)
    }

    private fun showPluginAudioParams(plugin: JReadVoiceEngine.VoicePlugin) {
        val configs = allConfigs.filter { it.pluginId == plugin.id || it.pluginId == plugin.pluginId }
        val firstConfig = configs.firstOrNull()
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        container.addView(TextView(this).apply { text = "${plugin.name} (${configs.size}个音色项)"; textSize = 14f; setPadding(4, 4, 4, 12) })
        val (sbSpeed, _, _) = buildSliderRow("语速", ((firstConfig?.speed ?: 1f) * 100).toInt(), false)
        val (sbVol, _, _) = buildSliderRow("音量", ((firstConfig?.volume ?: 1f) * 100).toInt(), false)
        val (sbPitch, _, _) = buildSliderRow("音调", ((firstConfig?.pitch ?: 1f) * 100).toInt(), false)
        listOf(sbSpeed, sbVol, sbPitch).forEach { container.addView(it.tag as android.view.View) }
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
