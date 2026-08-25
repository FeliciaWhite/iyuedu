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
    // 编辑/新增配置保存后回传，父页面据此再保存一次 fayinren.json
    private val configEditorLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) scheduleExportEnabledTags()
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        // 长按拖拽排序配置项
        adapter.onConfigMoved = { orderedIds ->
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { JReadVoiceEngine.saveConfigsSortOrder(this@TtsPluginActivity, orderedIds) }
                scheduleExportEnabledTags()
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
                scheduleExportEnabledTags()
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
                scheduleExportEnabledTags()
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
            onConfigCopy = { config -> copyConfig(config) },
            onConfigExport = { config -> exportConfig(config) },
            onConfigToggle = { config, enabled -> toggleConfig(config, enabled) },
            onGroupToggle = { groupName -> toggleGroup(groupName) },
            onGroupToggleEnabled = { groupName, enabled -> setGroupEnabled(groupName, null, enabled) },
            onSubGroupToggle = { groupName, subGroupName -> toggleSubGroup(groupName, subGroupName) },
            onSubGroupToggleEnabled = { groupName, subGroupName, enabled -> setGroupEnabled(groupName, subGroupName, enabled) },
            onGroupRename = { groupName, subGroupName -> showRenameDialog(groupName, subGroupName) },
            onGroupDelete = { groupName, subGroupName -> showDeleteGroupDialog(groupName, subGroupName) },
            onGroupCopy = { groupName, subGroupName -> copyGroup(groupName, subGroupName) },
            onGroupDeleteEnabled = { groupName, subGroupName -> deleteGroupEnabledDisabled(groupName, subGroupName, enabled = true) },
            onGroupDeleteDisabled = { groupName, subGroupName -> deleteGroupEnabledDisabled(groupName, subGroupName, enabled = false) },
            onGroupReplacePlugin = { groupName, subGroupName -> showReplacePluginDialog(groupName, subGroupName) },
            onGroupOrganizeTags = { groupName, subGroupName -> showOrganizeTagsDialog(groupName, subGroupName) },
            onGroupAudioParams = { groupName, subGroupName -> showGroupAudioParamsDialog(groupName, subGroupName) },
            onGroupConvertToSub = { groupName -> convertGroupToSub(groupName) },
            onGroupConvertToGroup = { groupName, subGroupName -> convertSubToGroup(groupName, subGroupName) },
            onPluginClick = { plugin -> showPluginOptions(plugin) },
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
            // 注意：不再在 loadData 同步路径写 fayinren.json（避免每次进入/刷新都写文件造成卡顿）
            // 写文件改为在启用/禁用/导入等操作后由 refreshListLight() 异步触发
        }
    }

    private fun exportEnabledTagsNow() {
        try {
            // 直接用内存中的最新配置，不再读盘全量解析，文件很小直接覆盖写
            val tags = allConfigs.filter { it.enabled }
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
        configEditorLauncher.launch(Intent(this, TtsConfigEditorActivity::class.java).apply {
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
            // 仅重读配置列表并轻量刷新，不 loadData() 全量重载
            allConfigs = JReadVoiceEngine.listConfigs(this@TtsPluginActivity)
            toastOnUi("已删除: ${config.voiceTag}")
            refreshListLight()
        }
    }

    private fun toggleConfig(config: JReadVoiceEngine.VoiceConfig, enabled: Boolean) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { JReadVoiceEngine.saveConfig(this@TtsPluginActivity, config.copy(enabled = enabled)) }
            // 仅内存更新 + 局部重排，避免 loadData() 全量读盘/解析/写文件造成的卡顿
            allConfigs = allConfigs.map { if (it.id == config.id) it.copy(enabled = enabled) else it }
            refreshListLight()
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
                // 同步内存，避免 loadData() 全量重载
                val idSet = ids.toSet()
                allConfigs = allConfigs.map { if (idSet.contains(it.id)) it.copy(enabled = enabled) else it }
            }
            refreshListLight()
        }
    }

    /**
     * 轻量刷新：内存已是最新，只重排可见列表并异步写 fayinren.json，
     * 不再走 loadData()（不读盘、不解析整表、不做内置预设/迁移）。
     */
    private fun refreshListLight() {
        if (currentTab == TAB_PLUGINS) rebuildPluginRows() else rebuildRows()
        scheduleExportEnabledTags()
    }

    /**
     * 仅重读列表数据（groups/configs/plugins 各读盘一次），不执行 ensureBuiltIn 也不写 fayinren.json。
     * 用于仅改动分组/插件元信息、configs 内容不变的轻量场景。
     */
    private fun reloadListsLight() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                allConfigs = JReadVoiceEngine.listConfigs(this@TtsPluginActivity)
                allGroups = JReadVoiceEngine.listGroups(this@TtsPluginActivity)
                allPlugins = JReadVoiceEngine.listPlugins(this@TtsPluginActivity)
                pluginsMap = allPlugins.associateBy { it.id }
            }
            adapter.setPluginsMap(pluginsMap)
            if (currentTab == TAB_PLUGINS) rebuildPluginRows() else rebuildRows()
        }
    }

    // 后台写 fayinren.json 的串行标记：多次调用只保证"最终基于最新内存写一次"，避免快速连点堆积 I/O
    @Volatile
    private var exportTagsPending = false

    private fun scheduleExportEnabledTags() {
        if (exportTagsPending) return
        exportTagsPending = true
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    do {
                        exportTagsPending = false
                        exportEnabledTagsNow()
                        // 若期间又来了新请求，再写一次（基于最新内存），保证最终一致
                    } while (exportTagsPending)
                } catch (e: Exception) {
                    exportTagsPending = false
                }
            }
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
                        // 同步内存，避免 loadData() 全量重载
                        val updateIds = toUpdate.map { it.id }.toSet()
                        allConfigs = configs.map { if (updateIds.contains(it.id)) it.copy(groupName = if (subGroupName == null) newName else it.groupName, subGroupName = if (subGroupName == null) it.subGroupName else newName) else it }
                        // 删除原分组对应的 VoiceGroup 记录，避免残留空分组（新名字会在 upsertGroup 时自动生成记录）
                        // 注意：row 传入的 groupName/subGroupName 是显示值（"默认分组"/"默认"），
                        // 而存储里真实值是空串，因此用真实存储值精确匹配，避免 ifBlank 比较遗漏。
                        val realGroupKey = if (groupName == "默认分组") "" else groupName
                        val realSubKey = subGroupName?.let { if (it == "默认") "" else it }
                        val groups = JReadVoiceEngine.listGroups(this@TtsPluginActivity).toMutableList()
                        if (subGroupName == null) {
                            groups.removeAll {
                                it.groupName == realGroupKey &&
                                it.subGroupName.isBlank() && it.thirdGroupName.isBlank()
                            }
                        } else {
                            groups.removeAll {
                                it.groupName == realGroupKey &&
                                it.subGroupName == realSubKey
                            }
                        }
                        JReadVoiceEngine.saveGroups(this@TtsPluginActivity, groups)
                        // 同步内存中的分组记录，否则界面会残留旧名的空分组（要切走再回来才消失）
                        allGroups = groups
                    }
                    toastOnUi("已重命名")
                    refreshListLight()
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
                        // 一次性读盘，算出该分组下所有配置 id，仅全量写盘一次（避免逐条 deleteConfig 的 N 次全量读写）
                        val configs = JReadVoiceEngine.listConfigs(this@TtsPluginActivity)
                        val deleteIds = configs.filter { c ->
                            c.groupName.ifBlank { "默认分组" } == groupName &&
                            (subGroupName == null || c.subGroupName.ifBlank { "默认" } == subGroupName)
                        }.map { it.id }.toSet()
                        allConfigs = JReadVoiceEngine.deleteConfigs(this@TtsPluginActivity, deleteIds)
                        // 删除分组记录本身
                        JReadVoiceEngine.deleteGroup(this@TtsPluginActivity, groupName, subGroupName)
                        // 同步内存中的分组列表，避免界面残留空分组头（不重新 loadData）
                        allGroups = JReadVoiceEngine.listGroups(this@TtsPluginActivity)
                    }
                    toastOnUi("已删除分组")
                    refreshListLight()
                }
            }
            noButton()
        }
    }

    /**
     * 复制单个配置：生成新 id（避免与源配置冲突），其余内容完全照搬，
     * 先写入存储，再以「编辑」方式打开（编辑页按 id 加载并保留 id 保存），
     * 用户可在编辑页确认/微调。逻辑对齐 TTS Server 的 onCopy（打开副本编辑页）。
     */
    private fun copyConfig(config: JReadVoiceEngine.VoiceConfig) {
        val copy = config.copy(id = java.util.UUID.randomUUID().toString())
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                JReadVoiceEngine.saveConfigsBatch(this@TtsPluginActivity, listOf(copy))
                allConfigs = JReadVoiceEngine.listConfigs(this@TtsPluginActivity)
            }
            toastOnUi("已复制，请在编辑页确认保存")
            refreshListLight()
            openConfigEditor(copy.id, isNew = false)
        }
    }

    /**
     * 导出单个配置为 JSON 文件，并通过系统分享/打开。对齐 TTS Server 的 onExport。
     */
    private fun exportConfig(config: JReadVoiceEngine.VoiceConfig) {
        val json = org.json.JSONObject().apply {
            put("data", org.json.JSONObject().apply {
                // 把字段平铺到 data 内，与导入格式保持一致（TTS Server 导出即为单个配置对象）
                put("id", config.id); put("voiceTag", config.voiceTag)
                put("groupName", config.groupName); put("subGroupName", config.subGroupName)
                put("thirdGroupName", config.thirdGroupName); put("displayName", config.displayName)
                put("pluginId", config.pluginId); put("locale", config.locale)
                put("voice", config.voice); put("previewText", config.previewText)
                put("data", org.json.JSONObject(config.dataJson))
                put("speed", config.speed); put("volume", config.volume); put("pitch", config.pitch)
                put("method", config.method); put("urlTemplate", config.urlTemplate)
                put("headersText", config.headersText); put("bodyTemplate", config.bodyTemplate)
                put("responseAudioPath", config.responseAudioPath); put("enabled", config.enabled)
                put("postSpeed", config.postSpeed); put("postVolume", config.postVolume); put("postPitch", config.postPitch)
            })
        }.toString(2)
        val dir = java.io.File("/storage/emulated/0/Download/chajian/mingwuyan")
        try {
            if (!dir.exists()) dir.mkdirs()
            val safeName = config.displayName.ifBlank { config.voice }.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(40)
            val file = java.io.File(dir, "tts_config_${safeName}_${config.id.takeLast(6)}.json")
            file.writeText(json)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileProvider", file
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(intent, "导出配置"))
        } catch (e: Exception) {
            toastOnUi("导出失败：${e.message}")
        }
    }

    /**
     * 复制分组（含子分组）：源分组下的每个配置都生成新 id 并归入新分组名，
     * 新分组名追加「副本」后缀。对齐 TTS Server 的 onCopy（分组）。
     */
    private fun copyGroup(groupName: String, subGroupName: String?) {
        val srcGroup = groupName.ifBlank { "默认分组" }
        val srcSub = subGroupName
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val configs = JReadVoiceEngine.listConfigs(this@TtsPluginActivity)
                val matched = configs.filter { c ->
                    c.groupName.ifBlank { "默认分组" } == srcGroup &&
                    (srcSub == null || c.subGroupName.ifBlank { "默认" } == srcSub)
                }
                if (matched.isEmpty()) return@withContext
                val newGroupName = if (srcSub == null) "$srcGroup 副本" else srcGroup
                val newSubName = if (srcSub == null) null else "${srcSub} 副本"
                val copies = matched.map { c ->
                    c.copy(
                        id = java.util.UUID.randomUUID().toString(),
                        groupName = newGroupName,
                        subGroupName = newSubName ?: c.subGroupName
                    )
                }
                // 确保新分组记录存在（便于空分组也能显示）
                if (srcSub == null) {
                    JReadVoiceEngine.saveGroup(this@TtsPluginActivity, JReadVoiceEngine.VoiceGroup(groupName = newGroupName))
                } else {
                    JReadVoiceEngine.saveGroup(this@TtsPluginActivity, JReadVoiceEngine.VoiceGroup(groupName = newGroupName, subGroupName = newSubName ?: ""))
                }
                JReadVoiceEngine.saveConfigsBatch(this@TtsPluginActivity, copies)
                allConfigs = JReadVoiceEngine.listConfigs(this@TtsPluginActivity)
                allGroups = JReadVoiceEngine.listGroups(this@TtsPluginActivity)
            }
            toastOnUi("已复制分组")
            refreshListLight()
        }
    }

    /**
     * 删除某分组（或子分组）下全部「启用」或「停用」的配置。对齐 TTS Server 的
     * onDeleteEnabled / onDeleteDisabled。
     */
    private fun deleteGroupEnabledDisabled(groupName: String, subGroupName: String?, enabled: Boolean) {
        val srcGroup = groupName.ifBlank { "默认分组" }
        val srcSub = subGroupName
        val label = if (enabled) "启用项" else "停用项"
        alert("删除确认") {
            setMessage("确定删除分组「${srcSub ?: srcGroup}」下的全部${label}吗？")
            yesButton {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val configs = JReadVoiceEngine.listConfigs(this@TtsPluginActivity)
                        val deleteIds = configs.filter { c ->
                            c.groupName.ifBlank { "默认分组" } == srcGroup &&
                            (srcSub == null || c.subGroupName.ifBlank { "默认" } == srcSub) &&
                            c.enabled == enabled
                        }.map { it.id }.toSet()
                        if (deleteIds.isEmpty()) return@withContext
                        allConfigs = JReadVoiceEngine.deleteConfigs(this@TtsPluginActivity, deleteIds)
                    }
                    toastOnUi("已删除${label}")
                    refreshListLight()
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
                            val updateIds = toUpdate.map { it.id }.toSet()
                            allConfigs = configs.map { if (updateIds.contains(it.id)) it.copy(pluginId = newPlugin.id) else it }
                        }
                        toastOnUi("已更换插件为 ${newPlugin.name}")
                        refreshListLight()
                    }
                }.show()
        }
    }

    /**
     * 一键整理标签：对标 TTS Server 的 reassignTagsWithPrefix。
     * 让用户输入一个前缀，把目标分组（大分组含其下所有项；子分组仅该子分组）下
     * 的【启用】配置项按列表当前顺序从 01 开始连续编号，voiceTag = 前缀 + 两位序号。
     */
    private fun showOrganizeTagsDialog(groupName: String, subGroupName: String?) {
        val srcGroup = groupName.ifBlank { "默认分组" }
        val srcSub = subGroupName
        val input = android.widget.EditText(this).apply {
            hint = "如：女中 / 男青 / 旁白"
            setText("")
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("重新分配标签（前缀）")
            .setMessage("将「${srcSub ?: srcGroup}」下启用项按当前顺序编号")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val prefix = input.text.toString().trim()
                if (prefix.isEmpty()) { toastOnUi("前缀不能为空"); return@setPositiveButton }
                reassignTagsWithPrefix(srcGroup, srcSub, prefix)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 按当前列表顺序（sortOrder）对启用项重新连续编号，逻辑对齐 TTS Server reassignTagsWithPrefix。
     */
    private fun reassignTagsWithPrefix(groupName: String, subGroupName: String?, prefix: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val configs = JReadVoiceEngine.listConfigs(this@TtsPluginActivity, ensureBuiltIns = false)
                val matched = configs.filter { c ->
                    c.groupName.ifBlank { "默认分组" } == groupName &&
                    (subGroupName == null || c.subGroupName.ifBlank { "默认" } == subGroupName)
                }.sortedWith(compareBy({ it.sortOrder }, { it.voiceTag }, { it.displayName }))
                val enabled = matched.filter { it.enabled }
                if (enabled.isEmpty()) return@withContext
                val updated = configs.map { c ->
                    val idx = enabled.indexOfFirst { it.id == c.id }
                    if (idx >= 0) c.copy(voiceTag = prefix + String.format("%02d", idx + 1))
                    else c
                }
                JReadVoiceEngine.saveConfigsBatch(this@TtsPluginActivity, updated)
                allConfigs = JReadVoiceEngine.listConfigs(this@TtsPluginActivity, ensureBuiltIns = false)
            }
            toastOnUi("已重新分配标签")
            refreshListLight()
        }
    }

    /**
     * 大分组 → 子分组：对齐 TTS Server 的 convertToSubGroup。
     * 选中一个大分组 srcGroup，选一个目标大分组 dstGroup（从所有配置的大分组名派生，排除自己）。
     * 把 srcGroup 下【所有配置项】（含其下子分组项）整体迁入 dstGroup，
     * 子分组名变更为 "srcGroup/sub原分组名"（原无子分组的直接为 srcGroup）。
     */
    private fun convertGroupToSub(groupName: String) {
        val srcGroup = groupName.ifBlank { "默认分组" }
        lifecycleScope.launch {
            val allConfigsNow = withContext(Dispatchers.IO) {
                JReadVoiceEngine.listConfigs(this@TtsPluginActivity, ensureBuiltIns = false)
            }
            // 目标大分组：配置中出现过的大分组名 ∪ 分组记录中的大分组（含空分组），排除自身
            // 否则空大分组（只有 VoiceGroup 记录、无配置项）无法作为目标，造成死循环。
            val configGroups = allConfigsNow
                .map { it.groupName.ifBlank { "默认分组" } }
                .toSet()
            val recordGroups = allGroups
                .filter { it.subGroupName.isBlank() && it.thirdGroupName.isBlank() }
                .map { it.groupName.ifBlank { "默认分组" } }
                .toSet()
            val targetGroups = (configGroups + recordGroups)
                .filter { it != srcGroup }
                .distinct()
            if (targetGroups.isEmpty()) {
                toastOnUi("没有其他大分组可作为目标")
                return@launch
            }
            withContext(Dispatchers.Main) {
                androidx.appcompat.app.AlertDialog.Builder(this@TtsPluginActivity)
                    .setTitle("转为子分组：选择目标大分组")
                    .setItems(targetGroups.toTypedArray()) { _, which ->
                        val target = targetGroups[which]
                        doConvertGroupToSub(srcGroup, target, allConfigsNow)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    private fun doConvertGroupToSub(srcGroup: String, targetGroup: String, allConfigsNow: List<JReadVoiceEngine.VoiceConfig>) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                // 把显示值还原为真实存储值（"默认分组" → ""），避免把字面量写入 groupName
                val realTargetGroup = if (targetGroup == "默认分组") "" else targetGroup
                // srcGroup 下所有配置项整体迁入 targetGroup，子分组名前插 srcGroup
                val updated = allConfigsNow.map { c ->
                    if (c.groupName.ifBlank { "默认分组" } == srcGroup) {
                        val newSub = buildString {
                            append(srcGroup)
                            if (c.subGroupName.isNotBlank()) append("/").append(c.subGroupName)
                        }
                        c.copy(groupName = realTargetGroup, subGroupName = newSub)
                    } else c
                }
                JReadVoiceEngine.saveConfigsBatch(this@TtsPluginActivity, updated)
                // 源大分组已迁空，删除其分组记录，避免残留空分组
                val groups = JReadVoiceEngine.listGroups(this@TtsPluginActivity).toMutableList()
                groups.removeAll {
                    it.groupName.ifBlank { "默认分组" } == srcGroup &&
                    it.subGroupName.isBlank() && it.thirdGroupName.isBlank()
                }
                JReadVoiceEngine.saveGroups(this@TtsPluginActivity, groups)
                allConfigs = JReadVoiceEngine.listConfigs(this@TtsPluginActivity, ensureBuiltIns = false)
                allGroups = JReadVoiceEngine.listGroups(this@TtsPluginActivity)
            }
            toastOnUi("已转为「$targetGroup」的子分组")
            refreshListLight()
        }
    }

    /**
     * 子分组 → 大分组（B）：把该子分组整体升级为一个【独立】的新大分组。
     * 这些配置项的 groupName 改为子分组自己的名字（作为新大分组名），
     * subGroupName 清空，从而脱离原大分组、成为独立大分组。
     */
    private fun convertSubToGroup(groupName: String, subGroupName: String) {
        val srcGroup = groupName.ifBlank { "默认分组" }
        val srcSub = subGroupName // 原始子分组名（空串表示"默认"子分组）
        alert("转为大分组") {
            setMessage("将子分组「$srcSub」从「$srcGroup」中独立出来，成为新的大分组？")
            yesButton {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val configs = JReadVoiceEngine.listConfigs(this@TtsPluginActivity, ensureBuiltIns = false)
                        val newGroupName = if (srcSub.isBlank()) "${srcGroup}（独立）" else srcSub
                        val updated = configs.map { c ->
                            if (c.groupName.ifBlank { "默认分组" } == srcGroup &&
                                c.subGroupName == srcSub) {
                                // 整组脱离原大分组，以子分组名作为新大分组名
                                c.copy(groupName = newGroupName, subGroupName = "")
                            } else c
                        }
                        JReadVoiceEngine.saveConfigsBatch(this@TtsPluginActivity, updated)
                        // 删除原子分组的 VoiceGroup 记录，避免残留空子分组（新大分组名会在 upsertGroup 时自动生成记录）
                        val groups = JReadVoiceEngine.listGroups(this@TtsPluginActivity).toMutableList()
                        groups.removeAll {
                            it.groupName.ifBlank { "默认分组" } == srcGroup &&
                            it.subGroupName == srcSub
                        }
                        JReadVoiceEngine.saveGroups(this@TtsPluginActivity, groups)
                        allConfigs = JReadVoiceEngine.listConfigs(this@TtsPluginActivity, ensureBuiltIns = false)
                        allGroups = JReadVoiceEngine.listGroups(this@TtsPluginActivity)
                    }
                    toastOnUi("已转为独立大分组「${if (srcSub.isBlank()) "${srcGroup}（独立）" else srcSub}」")
                    refreshListLight()
                }
            }
            noButton()
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
                        toastOnUi("已更新音频调节"); reloadListsLight()
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
                    toastOnUi("已新增分组: $groupName"); reloadListsLight()
                }
            }
            cancelButton()
        }
    }

    private fun showPluginOptions(plugin: JReadVoiceEngine.VoicePlugin) {
        val options = arrayOf("编辑插件", "音频参数", "清空数据", "删除插件")
        android.app.AlertDialog.Builder(this)
            .setTitle(plugin.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showPluginEditor(plugin)
                    1 -> showPluginAudioParams(plugin)
                    2 -> clearPluginData(plugin)
                    3 -> deletePlugin(plugin)
                }
            }.show()
    }

    private fun clearPluginData(plugin: JReadVoiceEngine.VoicePlugin) {
        android.app.AlertDialog.Builder(this)
            .setTitle("清空数据")
            .setMessage("确定清空 ${plugin.name} 的所有缓存数据？")
            .setPositiveButton("清空") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val storageId = plugin.pluginId.ifBlank { plugin.id }
                        val dir = java.io.File(this@TtsPluginActivity.filesDir, "jread_voice_engine/plugin_files/$storageId")
                        if (dir.exists()) {
                            dir.listFiles()?.forEach { it.delete() }
                            dir.delete()
                        }
                    }
                    toastOnUi("已清空 ${plugin.name} 的数据")
                }
            }
            .setNegativeButton("取消", null)
            .show()
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
                        toastOnUi("已新增插件: $name"); reloadListsLight()
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
                    toastOnUi("已保存插件: ${plugin.name}"); reloadListsLight()
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
            toastOnUi("已删除插件: ${plugin.name}"); reloadListsLight()
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
                    toastOnUi("已更新音频参数"); reloadListsLight()
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
                            // 使用稳定 id + 一次性写盘（避免逐条 saveConfig 的 N 次全量写盘导致导入卡顿）
                            JReadVoiceEngine.importConfigsFromJson(this@TtsPluginActivity, text, useStableId = true)
                        }
                        TAB_PLUGINS -> JReadVoiceEngine.importPluginsFromPackageBytes(this@TtsPluginActivity, bytes)
                        else -> 0
                    }
                }
                if (count > 0) {
                    // 仅重读配置/插件列表（不全量 loadData），再局部刷新，避免卡顿
                    allConfigs = JReadVoiceEngine.listConfigs(this@TtsPluginActivity)
                    allPlugins = JReadVoiceEngine.listPlugins(this@TtsPluginActivity)
                    pluginsMap = allPlugins.associateBy { it.id }
                    adapter.setPluginsMap(pluginsMap)
                }
                toastOnUi("导入了 $count 条")
                refreshListLight()
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
