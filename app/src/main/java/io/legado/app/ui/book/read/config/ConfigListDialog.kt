package io.legado.app.ui.book.read.config

import android.app.Dialog
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.constant.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections

class ConfigListDialog : DialogFragment() {

    private lateinit var rootView: View
    private lateinit var rvConfigList: RecyclerView
    private lateinit var btnAdd: Button
    private lateinit var btnToggleList: Button

    // 工作文件固定为 jiaoseliebiao-list.json
    private val WORK_FILE = "jiaoseliebiao-list.json"
    // 记录当前使用的列表编号
    private val CURRENT_FILE = "jiaoseliebiao-current.txt"

    // 数据
    private var configData = mutableListOf<ConfigGroup>()
    private var voiceData = listOf<VoiceInfo>()
    private var presetData = listOf<PresetItem>()

    // 状态
    private var expandedGroupIndex = -1
    private var currentEditGroupIndex = -1
    private var currentEditChildIndex = -1

    // Adapter & TouchHelper
    private lateinit var adapter: ConfigAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper

    // 颜色
    private var textPrimary = Color.parseColor("#333333")
    private var textSecondary = Color.parseColor("#666666")
    private val colorEnabled = Color.parseColor("#2E7D32")
    private val colorEnabledBg = Color.parseColor("#C8E6C9")
    private val colorDisabled = Color.parseColor("#C62828")
    private val colorDisabledBg = Color.parseColor("#FFCDD2")
    private val checkBoxTint: android.content.res.ColorStateList by lazy {
        android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(colorEnabled, Color.parseColor("#757575"))
        )
    }

    // 标签常量
    private val mainRoles = listOf(
        Triple("主角 男主", "男主", 10),
        Triple("主角 女主", "女主", 10)
    )
    private val batchRoles = listOf(
        Triple("女/少女", "少女", 100), Triple("男/少年", "少年", 100),
        Triple("女/女青年", "女青年", 100), Triple("男/男青年", "男青年", 100),
        Triple("女/女中年", "女中年", 100), Triple("男/男中年", "男中年", 100),
        Triple("女/女老年", "女老年", 100), Triple("男/男老年", "男老年", 100),
        Triple("女/女童", "女童", 100), Triple("男/男童", "男童", 100),
        Triple("男/特殊", "特殊男", 20), Triple("女/特殊", "特殊女", 20)
    )
    private val specialRoles = listOf(
        Pair("【】括号发音人", "括号1"),
        Pair("在线音效", "括号2"),
        Pair("「」括号发音人", "括号3"),
        Pair("『对话旁白』", "括号4")
    )

    private val emotionMap = mapOf(
        "advertising" to "广告", "angry" to "生气", "coldness" to "冷漠",
        "comfort" to "安慰", "depressed" to "沮丧", "entertainment" to "娱乐",
        "excited" to "兴奋", "fear" to "恐惧", "happy" to "开心",
        "hate" to "厌恶", "lovey-dovey" to "撒娇", "neutral" to "中性",
        "news" to "新闻", "sad" to "悲伤", "shy" to "害羞",
        "surprised" to "惊讶", "tender" to "温柔", "tension" to "紧张"
    )

    // ===== 数据类 =====
    data class VoiceInfo(
        val voice_id: String,
        val name: String,
        val gender: String,
        val is_pro: Boolean = false,
        val is_emotion: Boolean = false,
        val is_singing: Boolean = false,
        val emotions: List<String>? = null
    )

    data class PresetItem(val name: String, val rules: List<String>? = null)

    data class ConfigGroup(
        val group: GroupInfo,
        val list: MutableList<ConfigItem>,
        var originalJson: JSONObject? = null
    )

    data class GroupInfo(var id: Long, var name: String)

    data class ConfigItem(
        var id: Long,
        var displayName: String,
        var groupId: Long,
        var enabled: Boolean = true,
        var config: ConfigDetail,
        var originalJson: JSONObject? = null
    )

    data class ConfigDetail(
        var speechRule: SpeechRule? = null,
        var audioParams: AudioParams? = null,
        var audioFormat: AudioFormat? = null,
        var source: Source? = null
    )

    data class SpeechRule(var tag: String = "", var tagName: String = "")
    data class AudioParams(var speed: Double = 1.0, var volume: Double = 1.0)
    data class AudioFormat(var sampleRate: String = "")
    data class Source(
        var speed: Double = 1.0,
        var volume: Double = 1.0,
        var voice: String = "",
        var data: SourceData? = null
    )

    data class SourceData(var contextTexts: String = "", var emotion: String = "")

    sealed class FlatItem {
        data class GroupHeader(val groupIndex: Int, val group: ConfigGroup, val isExpanded: Boolean) : FlatItem()
        data class ChildItem(val groupIndex: Int, val childIndex: Int, val item: ConfigItem) : FlatItem()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = Dialog(requireActivity(), R.style.dialog_style_material)
        rootView = LayoutInflater.from(dialog.context).inflate(R.layout.dialog_config_list, null)
        initViews()
        initColors()
        loadAllData()
        setupRecyclerView()
        setupListeners()

        dialog.setContentView(rootView)
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
        dialog.window?.setGravity(Gravity.CENTER)
        return dialog
    }

    private fun initViews() {
        rvConfigList = rootView.findViewById(R.id.rv_config_list)
        btnAdd = rootView.findViewById(R.id.btn_add)
        btnToggleList = rootView.findViewById(R.id.btn_toggle_list)
        btnToggleList.text = "列表" + getCurrentListNumber()
    }

    private fun initColors() {
        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        textPrimary = if (isDark) Color.WHITE else Color.parseColor("#333333")
        textSecondary = if (isDark) Color.parseColor("#AAAAAA") else Color.parseColor("#666666")
    }

    private fun setupListeners() {
        btnAdd.setOnClickListener {
            showAddMenu()
        }
        btnToggleList.setOnClickListener {
            toggleConfigList()
        }
    }

    /**
     * 点击列表切换按钮：扫描目录下所有 jiaoseliebiao-<数字>.json，弹出选择对话框
     */
    private fun toggleConfigList() {
        val listFiles = scanListFiles()
        if (listFiles.isEmpty()) {
            toast("未找到任何列表文件")
            return
        }
        val currentNum = getCurrentListNumber()
        val items = listFiles.map { "列表$it" }.toTypedArray()
        var selectedIndex = listFiles.indexOf(currentNum).coerceAtLeast(0)

        AlertDialog.Builder(requireContext())
            .setTitle("选择配置列表")
            .setSingleChoiceItems(items, selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton("切换") { _, _ ->
                val targetNum = listFiles[selectedIndex]
                if (targetNum == currentNum) return@setPositiveButton
                switchToList(targetNum)
            }
            .setNegativeButton("取消", null)
            .create()
            .apply { window?.setWindowAnimations(R.style.DialogAnimation) }
            .show()
    }

    /**
     * 切换到指定编号的列表
     * 1. 备份当前 jiaoseliebiao-list.json 到当前编号文件
     * 2. 把目标编号文件复制到 jiaoseliebiao-list.json
     * 3. 更新记录文件
     */
    private fun switchToList(targetNum: String) {
        val currentNum = getCurrentListNumber()
        val dir = File(baseDir)
        val workFile = File(dir, WORK_FILE)
        val currentBackup = File(dir, "jiaoseliebiao-$currentNum.json")
        val targetFile = File(dir, "jiaoseliebiao-$targetNum.json")

        // 1. 备份当前工作文件到原编号文件
        if (workFile.exists()) {
            try {
                workFile.copyTo(currentBackup, overwrite = true)
                AppLog.putDebug("已备份当前列表到 jiaoseliebiao-$currentNum.json")
            } catch (e: Exception) {
                AppLog.put("备份列表失败: ${e.localizedMessage}")
                toast("备份失败")
                return
            }
        }

        // 2. 目标文件存在则覆盖工作文件，不存在则创建一个空的
        if (targetFile.exists()) {
            try {
                targetFile.copyTo(workFile, overwrite = true)
                AppLog.putDebug("已从 jiaoseliebiao-$targetNum.json 加载列表")
            } catch (e: Exception) {
                AppLog.put("加载列表失败: ${e.localizedMessage}")
                toast("加载失败")
                return
            }
        } else {
            // 目标文件不存在，清空工作文件（创建空JSON数组）
            try {
                workFile.writeText("[]")
                AppLog.putDebug("jiaoseliebiao-$targetNum.json 不存在，已创建空列表")
            } catch (e: Exception) {
                AppLog.put("创建空列表失败: ${e.localizedMessage}")
                return
            }
        }

        // 3. 更新记录文件
        setCurrentListNumber(targetNum)
        btnToggleList.text = "列表$targetNum"

        // 4. 刷新UI
        loadAllData()
        expandedGroupIndex = -1
        adapter.updateData(buildFlatList())
        toast("已切换到 列表$targetNum")
    }

    /**
     * 扫描 mingwuyan 目录下所有 jiaoseliebiao-<数字>.json 文件，返回数字列表
     */
    private fun scanListFiles(): List<String> {
        val dir = File(baseDir)
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        val pattern = Regex("^jiaoseliebiao-(\\d+)\\.json\$")
        return dir.listFiles()?.mapNotNull { file ->
            pattern.find(file.name)?.groupValues?.get(1)
        }?.sortedBy { it.toIntOrNull() ?: 0 } ?: emptyList()
    }

    /**
     * 从记录文件读取当前列表编号，默认返回 "1"
     */
    private fun getCurrentListNumber(): String {
        return try {
            val file = File(baseDir, CURRENT_FILE)
            if (file.exists()) file.readText().trim() else "1"
        } catch (e: Exception) { "1" }
    }

    /**
     * 写入记录文件
     */
    private fun setCurrentListNumber(number: String) {
        try {
            val file = File(baseDir, CURRENT_FILE)
            file.parentFile?.mkdirs()
            file.writeText(number)
        } catch (e: Exception) {
            AppLog.put("写入列表记录失败: ${e.localizedMessage}")
        }
    }

    private fun showAddMenu() {
        val options = arrayOf("新增配置", "新增分组")
        AlertDialog.Builder(requireContext())
            .setTitle("新增")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> addNewConfig()
                    1 -> showAddGroupDialog()
                }
            }
            .create()
            .apply { window?.setWindowAnimations(R.style.DialogAnimation) }
            .show()
    }

    // ===== 文件操作 =====
    private val baseDir: String get() = "/storage/emulated/0/Download/chajian/mingwuyan/"

    private fun readFile(fileName: String): String {
        return try {
            val file = File(baseDir, fileName)
            if (file.exists()) file.readText() else ""
        } catch (e: Exception) { "" }
    }

    private fun writeFile(fileName: String, content: String) {
        try {
            val file = File(baseDir, fileName)
            file.parentFile?.mkdirs()
            file.writeText(content)
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun saveConfigData() {
        val arr = JSONArray()
        configData.forEachIndexed { groupIndex, g ->
            val groupObj = g.originalJson ?: JSONObject().apply {
                put("group", JSONObject())
                put("list", JSONArray())
            }
            val groupInfo = groupObj.optJSONObject("group") ?: JSONObject()
            groupInfo.put("id", g.group.id)
            groupInfo.put("name", g.group.name)
            groupInfo.put("order", groupIndex + 1)
            groupObj.put("group", groupInfo)

            val listArr = JSONArray()
            g.list.forEachIndexed { itemIndex, item ->
                val itemObj = item.originalJson ?: JSONObject()
                itemObj.put("id", item.id)
                itemObj.put("displayName", item.displayName)
                itemObj.put("groupId", item.groupId)
                itemObj.put("isEnabled", item.enabled)
                itemObj.put("order", itemIndex + 1)

                val cfgObj = itemObj.optJSONObject("config") ?: JSONObject()
                if (!cfgObj.has("#type")) cfgObj.put("#type", "tts")

                item.config.speechRule?.let { sr ->
                    val srObj = cfgObj.optJSONObject("speechRule") ?: JSONObject()
                    srObj.put("tag", sr.tag)
                    srObj.put("tagName", sr.tagName)
                    cfgObj.put("speechRule", srObj)
                }
                item.config.audioParams?.let { ap ->
                    val apObj = cfgObj.optJSONObject("audioParams") ?: JSONObject()
                    // speed：原配置有，或不是默认值 1.0 时才写入
                    if (apObj.has("speed") || ap.speed != 1.0) {
                        apObj.put("speed", ap.speed)
                    }
                    // volume：原配置有，或不是默认值 1.0 时才写入
                    if (apObj.has("volume") || ap.volume != 1.0) {
                        apObj.put("volume", ap.volume)
                    }
                    cfgObj.put("audioParams", apObj)
                }
                item.config.audioFormat?.let { af ->
                    val afObj = cfgObj.optJSONObject("audioFormat") ?: JSONObject()
                    // 保持和原始配置一致的类型：能转整数就存整数，否则存字符串
                    val sr = af.sampleRate
                    afObj.put("sampleRate", sr.toIntOrNull() ?: sr)
                    cfgObj.put("audioFormat", afObj)
                }
                item.config.source?.let { src ->
                    val srcObj = cfgObj.optJSONObject("source") ?: JSONObject()
                    if (!srcObj.has("#type")) srcObj.put("#type", "plugin")
                    if (!srcObj.has("locale")) srcObj.put("locale", "all")
                    if (!srcObj.has("pluginId")) srcObj.put("pluginId", "maoxiang.tts.gj")
                    srcObj.put("voice", src.voice)
                    // speed：原配置有，或不是默认值 1.0 时才写入
                    if (srcObj.has("speed") || src.speed != 1.0) {
                        srcObj.put("speed", src.speed)
                    }
                    srcObj.put("volume", src.volume)
                    src.data?.let { d ->
                        val dObj = srcObj.optJSONObject("data") ?: JSONObject()
                        dObj.put("contextTexts", d.contextTexts)
                        dObj.put("emotion", d.emotion)
                        srcObj.put("data", dObj)
                    }
                    cfgObj.put("source", srcObj)
                }
                itemObj.put("config", cfgObj)
                listArr.put(itemObj)

                // 回写 originalJson，供后续编辑保留完整字段
                item.originalJson = itemObj
            }
            groupObj.put("list", listArr)
            arr.put(groupObj)

            // 回写 group originalJson
            g.originalJson = groupObj
        }
        writeFile(WORK_FILE, arr.toString(4))
    }

    // ===== 数据加载 =====
    private fun loadAllData() {
        // 1. 配置
        val configContent = readFile(WORK_FILE)
        configData = parseConfigData(configContent)

        // 2. 音色
        val voiceContent = readFile("voice_list.json")
        voiceData = parseVoiceData(voiceContent)

        // 3. 预设
        val presetContent = readFile("rule_presets.json")
        presetData = parsePresetData(presetContent)
    }

    private fun parseConfigData(content: String): MutableList<ConfigGroup> {
        val result = mutableListOf<ConfigGroup>()
        if (content.isBlank()) return result
        try {
            val arr = JSONArray(content)
            for (i in 0 until arr.length()) {
                val gObj = arr.getJSONObject(i)
                val group = gObj.getJSONObject("group")
                val gInfo = GroupInfo(group.getLong("id"), group.getString("name"))
                val list = mutableListOf<ConfigItem>()
                val listArr = gObj.getJSONArray("list")
                for (j in 0 until listArr.length()) {
                    val itObj = listArr.getJSONObject(j)
                    val cfgObj = itObj.optJSONObject("config") ?: JSONObject()
                    val sourceObj = cfgObj.optJSONObject("source")
                    val dataObj = sourceObj?.optJSONObject("data")
                    val item = ConfigItem(
                        id = itObj.optLong("id", System.currentTimeMillis() + j),
                        displayName = itObj.optString("displayName", ""),
                        groupId = itObj.optLong("groupId", gInfo.id),
                        enabled = itObj.optBoolean("isEnabled", true),
                        config = ConfigDetail(
                            speechRule = cfgObj.optJSONObject("speechRule")?.let {
                                SpeechRule(it.optString("tag", ""), it.optString("tagName", ""))
                            },
                            audioParams = cfgObj.optJSONObject("audioParams")?.let {
                                AudioParams(it.optDouble("speed", 1.0), it.optDouble("volume", 1.0))
                            },
                            audioFormat = cfgObj.optJSONObject("audioFormat")?.let {
                                AudioFormat(it.optString("sampleRate", ""))
                            },
                            source = sourceObj?.let { src ->
                                Source(
                                    speed = src.optDouble("speed", 1.0),
                                    volume = src.optDouble("volume", 1.0),
                                    voice = src.optString("voice", ""),
                                    data = dataObj?.let { d ->
                                        SourceData(
                                            d.optString("contextTexts", ""),
                                            d.optString("emotion", "")
                                        )
                                    }
                                )
                            }
                        ),
                        originalJson = JSONObject(itObj.toString())
                    )
                    list.add(item)
                }
                result.add(ConfigGroup(gInfo, list, JSONObject(gObj.toString())))
            }
        } catch (e: Exception) { e.printStackTrace() }
        return result
    }

    private fun parseVoiceData(content: String): List<VoiceInfo> {
        if (content.isBlank()) return emptyList()
        return try {
            val arr = when {
                content.trim().startsWith("[") -> JSONArray(content)
                else -> JSONObject(content).optJSONArray("data") ?: return emptyList()
            }
            val list = mutableListOf<VoiceInfo>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val emoArr = obj.optJSONArray("emotions")
                val emotions = mutableListOf<String>()
                emoArr?.let {
                    for (j in 0 until it.length()) emotions.add(it.getString(j))
                }
                list.add(VoiceInfo(
                    voice_id = obj.optString("voice_id", ""),
                    name = obj.optString("name", ""),
                    gender = obj.optString("gender", ""),
                    is_pro = obj.optBoolean("is_pro", false),
                    is_emotion = obj.optBoolean("is_emotion", false),
                    is_singing = obj.optBoolean("is_singing", false),
                    emotions = emotions
                ))
            }
            list
        } catch (e: Exception) { emptyList() }
    }

    private fun parsePresetData(content: String): List<PresetItem> {
        if (content.isBlank()) return emptyList()
        return try {
            val arr = when {
                content.trim().startsWith("[") -> JSONArray(content)
                else -> JSONObject(content).optJSONArray("data") ?: return emptyList()
            }
            val list = mutableListOf<PresetItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val rulesArr = obj.optJSONArray("rules")
                val rules = mutableListOf<String>()
                rulesArr?.let {
                    for (j in 0 until it.length()) rules.add(it.getString(j))
                }
                list.add(PresetItem(obj.optString("name", ""), rules))
            }
            list
        } catch (e: Exception) { emptyList() }
    }

    // ===== RecyclerView =====
    private fun buildFlatList(): List<FlatItem> {
        val flat = mutableListOf<FlatItem>()
        configData.forEachIndexed { index, group ->
            flat.add(FlatItem.GroupHeader(index, group, index == expandedGroupIndex))
            if (index == expandedGroupIndex) {
                group.list.forEachIndexed { cIndex, item ->
                    flat.add(FlatItem.ChildItem(index, cIndex, item))
                }
            }
        }
        return flat
    }

    private fun setupRecyclerView() {
        adapter = ConfigAdapter()
        rvConfigList.layoutManager = LinearLayoutManager(requireContext())
        rvConfigList.adapter = adapter

        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.bindingAdapterPosition
                val toPos = target.bindingAdapterPosition
                if (fromPos == RecyclerView.NO_POSITION || toPos == RecyclerView.NO_POSITION) return false

                val fromItem = adapter.getItem(fromPos)
                val toItem = adapter.getItem(toPos)

                when {
                    // 分组拖拽：都是GroupHeader
                    fromItem is FlatItem.GroupHeader && toItem is FlatItem.GroupHeader -> {
                        if (fromPos == toPos) return false
                        Collections.swap(configData, fromItem.groupIndex, toItem.groupIndex)
                        expandedGroupIndex = toItem.groupIndex
                        adapter.updateData(buildFlatList())
                        saveConfigData()
                        return true
                    }
                    // 子项拖拽：都是ChildItem且同一组
                    fromItem is FlatItem.ChildItem && toItem is FlatItem.ChildItem -> {
                        if (fromItem.groupIndex != toItem.groupIndex) return false
                        val group = configData[fromItem.groupIndex]
                        Collections.swap(group.list, fromItem.childIndex, toItem.childIndex)
                        adapter.updateData(buildFlatList())
                        saveConfigData()
                        return true
                    }
                }
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                val position = viewHolder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return makeMovementFlags(0, 0)
                val item = adapter.getItem(position)
                return when (item) {
                    is FlatItem.GroupHeader -> makeMovementFlags(
                        ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
                    )
                    is FlatItem.ChildItem -> makeMovementFlags(
                        ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
                    )
                }
            }

            override fun isLongPressDragEnabled(): Boolean = true
        }
        itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper.attachToRecyclerView(rvConfigList)
    }

    // ===== Adapter =====
    inner class ConfigAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var flatList = listOf<FlatItem>()

        init { updateData(buildFlatList()) }

        fun updateData(list: List<FlatItem>) {
            if (rvConfigList.isComputingLayout) {
                rvConfigList.post { updateData(list) }
                return
            }
            flatList = list
            notifyDataSetChanged()
        }

        fun getItem(position: Int): FlatItem = flatList[position]

        override fun getItemViewType(position: Int): Int = when (flatList[position]) {
            is FlatItem.GroupHeader -> 0
            is FlatItem.ChildItem -> 1
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == 0) {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_config_group, parent, false)
                GroupHolder(v)
            } else {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_config_entry, parent, false)
                ChildHolder(v)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = flatList[position]) {
                is FlatItem.GroupHeader -> (holder as GroupHolder).bind(item)
                is FlatItem.ChildItem -> (holder as ChildHolder).bind(item)
            }
        }

        override fun getItemCount(): Int = flatList.size

        inner class GroupHolder(v: View) : RecyclerView.ViewHolder(v) {
            private val llRoot: LinearLayout = v.findViewById(R.id.ll_group_root)
            private val ivExpand: ImageView = v.findViewById(R.id.iv_expand)
            private val tvName: TextView = v.findViewById(R.id.tv_group_name)
            private val cbStatus: com.google.android.material.checkbox.MaterialCheckBox = v.findViewById(R.id.cb_group_status)
            private val ivMore: ImageView = v.findViewById(R.id.iv_group_more)

            fun bind(item: FlatItem.GroupHeader) {
                tvName.text = "${item.group.group.name}（${item.group.list.size}个）"

                // 展开箭头旋转动画
                ivExpand.animate().rotation(if (item.isExpanded) 0f else -90f).setDuration(200).start()

                // 分组三态：先清 listener 再设状态，避免复用触发旧回调
                cbStatus.setOnCheckedChangeListener(null)
                cbStatus.buttonTintList = checkBoxTint
                val total = item.group.list.size
                val enabledCount = item.group.list.count { it.enabled }
                when {
                    total == 0 || enabledCount == 0 -> {
                        cbStatus.checkedState = com.google.android.material.checkbox.MaterialCheckBox.STATE_UNCHECKED
                    }
                    enabledCount == total -> {
                        cbStatus.checkedState = com.google.android.material.checkbox.MaterialCheckBox.STATE_CHECKED
                    }
                    else -> {
                        cbStatus.checkedState = com.google.android.material.checkbox.MaterialCheckBox.STATE_INDETERMINATE
                    }
                }

                llRoot.setOnClickListener {
                    expandedGroupIndex = if (item.isExpanded) -1 else item.groupIndex
                    // 延迟刷新，避免在 RecyclerView 布局计算期间调用 notify
                    llRoot.post {
                        updateData(buildFlatList())
                    }
                }

                cbStatus.setOnClickListener {
                    val group = configData[item.groupIndex]
                    val allEnabled = group.list.all { it.enabled }
                    group.list.forEach { it.enabled = !allEnabled }
                    // 延迟刷新，避免在 RecyclerView 布局计算期间调用 notify
                    cbStatus.post {
                        updateData(buildFlatList())
                    }
                    saveConfigData()
                }

                ivMore.setOnClickListener { view ->
                    showGroupPopup(view, item.groupIndex)
                }
            }
        }

        inner class ChildHolder(v: View) : RecyclerView.ViewHolder(v) {
            private val cbEnable: CheckBox = v.findViewById(R.id.cb_enable)
            private val tvName: TextView = v.findViewById(R.id.tv_name)
            private val tvTag: TextView = v.findViewById(R.id.tv_tag)
            private val tvDesc: TextView = v.findViewById(R.id.tv_desc)
            private val tvParams: TextView = v.findViewById(R.id.tv_params)
            private val ivEdit: ImageView = v.findViewById(R.id.iv_edit)
            private val ivMore: ImageView = v.findViewById(R.id.iv_more)

            fun bind(item: FlatItem.ChildItem) {
                val cfg = item.item.config
                val src = cfg.source

                tvName.text = item.item.displayName.ifBlank { "未命名" }

                // 启用/禁用复选框：先清 listener 再设状态，避免复用触发旧回调
                cbEnable.setOnCheckedChangeListener(null)
                cbEnable.buttonTintList = checkBoxTint
                cbEnable.isChecked = item.item.enabled
                cbEnable.setOnCheckedChangeListener { _, isChecked ->
                    item.item.enabled = isChecked
                    // 延迟刷新，避免在 RecyclerView 布局计算期间调用 notify
                    cbEnable.post {
                        updateData(buildFlatList())
                    }
                    saveConfigData()
                }

                // 右上角标签
                val tagText = cfg.speechRule?.tagName ?: cfg.speechRule?.tag ?: ""
                if (tagText.isNotBlank()) {
                    tvTag.visibility = View.VISIBLE
                    tvTag.text = tagText
                } else {
                    tvTag.visibility = View.GONE
                }

                // 描述行：音色 + 情绪
                val voiceName = voiceData.find { it.voice_id == src?.voice }?.name ?: src?.voice ?: "未设置"
                val emoText = src?.data?.emotion?.split(",")?.filter { it.isNotBlank() }
                    ?.joinToString("、") { emotionMap[it] ?: it }
                val descBuilder = StringBuilder()
                descBuilder.append("音色：$voiceName")
                if (!emoText.isNullOrBlank()) {
                    descBuilder.append("  |  情绪：$emoText")
                }
                tvDesc.text = descBuilder.toString()

                // 参数行
                val speed = cfg.audioParams?.speed ?: src?.speed ?: 1.0
                val volume = cfg.audioParams?.volume ?: src?.volume ?: 1.0
                val sampleRate = cfg.audioFormat?.sampleRate ?: "-"
                tvParams.text = "$sampleRate  |  语速：$speed  |  音量：$volume"

                ivEdit.setOnClickListener {
                    openEditDialog(item.groupIndex, item.childIndex)
                }

                ivMore.setOnClickListener { view ->
                    showChildPopup(view, item.groupIndex, item.childIndex)
                }
            }
        }
    }

    // ===== 弹窗菜单 =====
    private fun showGroupPopup(anchor: View, groupIndex: Int) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add("重命名分组")
        popup.menu.add("按标签排序")
        popup.menu.add("删除分组")
        popup.setOnMenuItemClickListener {
            when (it.title) {
                "重命名分组" -> showRenameGroupDialog(groupIndex)
                "按标签排序" -> sortGroupByTag(groupIndex)
                "删除分组" -> deleteGroup(groupIndex)
            }
            true
        }
        popup.show()
    }

    private fun showChildPopup(anchor: View, groupIndex: Int, childIndex: Int) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add("复制配置")
        popup.menu.add("删除配置")
        popup.setOnMenuItemClickListener {
            when (it.title) {
                "复制配置" -> copyConfig(groupIndex, childIndex)
                "删除配置" -> deleteConfig(groupIndex, childIndex)
            }
            true
        }
        popup.show()
    }

    // ===== 操作 =====
    private fun addNewConfig() {
        if (configData.isEmpty()) {
            toast("请先创建分组")
            return
        }
        val blank = ConfigItem(
            id = System.currentTimeMillis(),
            displayName = "新配置",
            groupId = configData[0].group.id,
            enabled = true,
            config = ConfigDetail(
                speechRule = SpeechRule("", ""),
                audioParams = AudioParams(1.0, 1.0),
                audioFormat = AudioFormat("24000"),
                source = Source(1.0, 1.0, "", SourceData("", ""))
            )
        )
        configData[0].list.add(blank)
        expandedGroupIndex = 0
        adapter.updateData(buildFlatList())
        saveConfigData()
        openEditDialog(0, configData[0].list.size - 1)
    }

    private fun showAddGroupDialog() {
        val input = EditText(requireContext()).apply {
            hint = "请输入分组名称"
            setTextColor(textPrimary)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("新增分组")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    configData.add(ConfigGroup(
                        GroupInfo(System.currentTimeMillis(), name),
                        mutableListOf()
                    ))
                    adapter.updateData(buildFlatList())
                    saveConfigData()
                }
            }
            .setNegativeButton("取消", null)
            .create()
            .apply { window?.setWindowAnimations(R.style.DialogAnimation) }
            .show()
    }

    private fun showRenameGroupDialog(groupIndex: Int) {
        val input = EditText(requireContext()).apply {
            setText(configData[groupIndex].group.name)
            setTextColor(textPrimary)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("重命名分组")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    configData[groupIndex].group.name = name
                    adapter.updateData(buildFlatList())
                    saveConfigData()
                }
            }
            .setNegativeButton("取消", null)
            .create()
            .apply { window?.setWindowAnimations(R.style.DialogAnimation) }
            .show()
    }

    private fun sortGroupByTag(groupIndex: Int) {
        val tagOrder = buildTagItems().mapIndexed { index, pair -> pair.first to index }.toMap()
        configData[groupIndex].list.sortBy {
            tagOrder[it.config.speechRule?.tag ?: ""] ?: Int.MAX_VALUE
        }
        adapter.updateData(buildFlatList())
        saveConfigData()
    }

    private fun deleteGroup(groupIndex: Int) {
        AlertDialog.Builder(requireContext())
            .setTitle("确认删除")
            .setMessage("确定要删除该分组及所有配置吗？")
            .setPositiveButton("删除") { _, _ ->
                configData.removeAt(groupIndex)
                if (expandedGroupIndex == groupIndex) expandedGroupIndex = -1

                adapter.updateData(buildFlatList())
                saveConfigData()
            }
            .setNegativeButton("取消", null)
            .create()
            .apply { window?.setWindowAnimations(R.style.DialogAnimation) }
            .show()
    }

    private fun copyConfig(groupIndex: Int, childIndex: Int) {
        val source = configData[groupIndex].list[childIndex]
        val newId = System.currentTimeMillis()
        val copy = source.copy(
            id = newId,
            displayName = source.displayName + " - 副本",
            config = source.config.copy()
        )
        // deep copy config contents
        copy.config.source = source.config.source?.copy()
        copy.config.source?.data = source.config.source?.data?.copy()
        // deep copy originalJson so that edits to the copy do not affect the source
        copy.originalJson = source.originalJson?.let {
            JSONObject(it.toString()).apply {
                put("id", newId)
                put("displayName", copy.displayName)
            }
        }
        configData[groupIndex].list.add(childIndex + 1, copy)
        adapter.updateData(buildFlatList())
        saveConfigData()
    }

    private fun deleteConfig(groupIndex: Int, childIndex: Int) {
        AlertDialog.Builder(requireContext())
            .setTitle("确认删除")
            .setMessage("确定删除该配置？此操作不可恢复！")
            .setPositiveButton("删除") { _, _ ->
                configData[groupIndex].list.removeAt(childIndex)

                adapter.updateData(buildFlatList())
                saveConfigData()
            }
            .setNegativeButton("取消", null)
            .create()
            .apply { window?.setWindowAnimations(R.style.DialogAnimation) }
            .show()
    }

    // ===== 带搜索的选择器 =====
    private fun showSearchablePicker(title: String, items: List<String>, selectedPos: Int, onSelect: (Int) -> Unit) {
        val contentView = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 16)
        }
        val etSearch = EditText(requireContext()).apply {
            hint = "输入关键字搜索"
            setTextColor(textPrimary)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val listView = ListView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 800)
        }
        contentView.addView(etSearch)
        contentView.addView(listView)

        val displayItems = items.toMutableList()
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, displayItems)
        listView.adapter = adapter

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(contentView)
            .setNegativeButton("取消", null)
            .create()
            .apply { window?.setWindowAnimations(R.style.DialogAnimation) }

        listView.setOnItemClickListener { _, _, position, _ ->
            dialog.dismiss()
            val text = adapter.getItem(position) ?: return@setOnItemClickListener
            val realPos = items.indexOf(text)
            if (realPos >= 0) {
                onSelect(realPos)
            }
        }

        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val filter = s.toString()
                adapter.clear()
                val filtered = if (filter.isBlank()) items else items.filter { it.contains(filter) }
                adapter.addAll(filtered)
            }
        })

        dialog.show()
        listView.post {
            if (selectedPos in items.indices) {
                listView.setSelection(selectedPos)
            }
        }
    }

    // ===== 编辑弹窗 =====
    private fun openEditDialog(groupIndex: Int, childIndex: Int) {
        currentEditGroupIndex = groupIndex
        currentEditChildIndex = childIndex
        val item = configData[groupIndex].list[childIndex]
        val cfg = item.config
        val src = cfg.source ?: Source()
        val data = src.data ?: SourceData()

        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_config_edit, null)

        val etName = dialogView.findViewById<EditText>(R.id.et_display_name)
        val spinnerGroup = dialogView.findViewById<Spinner>(R.id.spinner_group)
        val spinnerCategory = dialogView.findViewById<Spinner>(R.id.spinner_category)
        val spinnerVoice = dialogView.findViewById<Spinner>(R.id.spinner_voice)
        val spinnerTag = dialogView.findViewById<Spinner>(R.id.spinner_tag)
        val spinnerPreset = dialogView.findViewById<Spinner>(R.id.spinner_preset)
        val llPreset = dialogView.findViewById<LinearLayout>(R.id.ll_preset)
        val etPrompt = dialogView.findViewById<EditText>(R.id.et_prompt)
        val llEmotion = dialogView.findViewById<LinearLayout>(R.id.ll_emotion)
        val llEmotionCheck = dialogView.findViewById<LinearLayout>(R.id.ll_emotion_check)
        val etSampleRate = dialogView.findViewById<EditText>(R.id.et_sample_rate)
        val etSpeed = dialogView.findViewById<EditText>(R.id.et_speed)
        val etVolume = dialogView.findViewById<EditText>(R.id.et_volume)

        etName.setText(item.displayName)
        etPrompt.setText(data.contextTexts)
        etSampleRate.setText(cfg.audioFormat?.sampleRate ?: "")
        val originalAudioParams = item.originalJson?.optJSONObject("config")?.optJSONObject("audioParams")
        val displaySpeed = if (originalAudioParams?.has("speed") == true) cfg.audioParams?.speed ?: src.speed else src.speed
        val displayVolume = if (originalAudioParams?.has("volume") == true) cfg.audioParams?.volume ?: src.volume else src.volume
        etSpeed.setText(displaySpeed.toString())
        etVolume.setText(displayVolume.toString())

        // 分组Spinner
        val groupNames = configData.map { it.group.name }
        spinnerGroup.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, groupNames).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerGroup.setSelection(groupIndex)

        // 音色分类Spinner
        val categories = listOf("全部可用音色", "高级音色", "情感音色", "精品女声", "精品男声", "声音成曲")
        val catKeys = listOf("all", "pro", "emotion", "female", "male", "sing")
        spinnerCategory.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, categories).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        // 标签搜索选择
        val tagItems = buildTagItems()
        var selectedTagValue = cfg.speechRule?.tag ?: ""
        val tagDisplayList = tagItems.map { it.second }
        spinnerTag.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, tagDisplayList).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerTag.setSelection(getTagIndex(tagItems, selectedTagValue))
        spinnerTag.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                showSearchablePicker("选择标签", tagDisplayList, spinnerTag.selectedItemPosition) { pos ->
                    spinnerTag.setSelection(pos)
                    selectedTagValue = tagItems.getOrNull(pos)?.first ?: ""
                }
            }
            true
        }

        // 预设搜索选择
        val presetNames = mutableListOf("-- 自定义提示词 --")
        presetNames.addAll(presetData.map { it.name })
        spinnerPreset.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, presetNames).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        var selectedPresetIndex = 0
        if (data.contextTexts.isNotBlank() && presetData.isNotEmpty()) {
            val match = presetData.indexOfFirst { it.rules?.firstOrNull() == data.contextTexts }
            if (match >= 0) {
                selectedPresetIndex = match + 1
                spinnerPreset.setSelection(selectedPresetIndex)
            }
        }
        spinnerPreset.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                showSearchablePicker("选择预设", presetNames, spinnerPreset.selectedItemPosition) { pos ->
                    spinnerPreset.setSelection(pos)
                    selectedPresetIndex = pos
                    if (pos > 0) {
                        val rule = presetData.getOrNull(pos - 1)?.rules?.firstOrNull() ?: ""
                        etPrompt.setText(rule)
                    } else {
                        etPrompt.setText("")
                    }
                }
            }
            true
        }

        // 根据当前voice设置分类和voice spinner
        val currentVoice = voiceData.find { it.voice_id == src.voice }
        var currentCat = "all"
        if (currentVoice != null) {
            currentCat = when {
                currentVoice.is_pro -> "pro"
                currentVoice.is_emotion -> "emotion"
                currentVoice.is_singing -> "sing"
                currentVoice.gender == "女性" -> "female"
                currentVoice.gender == "男性" -> "male"
                else -> "all"
            }
        }
        // 显隐控制
        fun updateVisibility() {
            val selVoice = getSelectedVoice(spinnerVoice)
            llPreset.visibility = if (selVoice?.is_pro == true) View.VISIBLE else View.GONE
            if (selVoice?.is_emotion == true && selVoice.emotions != null) {
                llEmotion.visibility = View.VISIBLE
                // 如果情绪列表变化则重建
                if (llEmotionCheck.childCount != selVoice.emotions.size) {
                    llEmotionCheck.removeAllViews()
                    selVoice.emotions.forEachIndexed { index, emo ->
                        val cb = CheckBox(requireContext()).apply {
                            text = emotionMap[emo] ?: emo
                            tag = emo
                            setTextColor(textPrimary)
                        }
                        cb.setOnCheckedChangeListener { _, isChecked ->
                            if (isChecked) {
                                for (i in 0 until llEmotionCheck.childCount) {
                                    if (i != index) {
                                        (llEmotionCheck.getChildAt(i) as? CheckBox)?.isChecked = false
                                    }
                                }
                            }
                        }
                        llEmotionCheck.addView(cb)
                    }
                }
            } else {
                llEmotion.visibility = View.GONE
            }
        }

        // 情绪CheckBox
        llEmotionCheck.removeAllViews()
        currentVoice?.emotions?.forEachIndexed { index, emo ->
            val cb = CheckBox(requireContext()).apply {
                text = emotionMap[emo] ?: emo
                tag = emo
                setTextColor(textPrimary)
                isChecked = data.emotion.split(",").filter { it.isNotBlank() }.contains(emo)
            }
            cb.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    for (i in 0 until llEmotionCheck.childCount) {
                        if (i != index) {
                            (llEmotionCheck.getChildAt(i) as? CheckBox)?.isChecked = false
                        }
                    }
                }
            }
            llEmotionCheck.addView(cb)
        }

        // 发音人搜索选择
        spinnerVoice.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                val adapter = spinnerVoice.adapter as? ArrayAdapter<String>
                val items = adapter?.let { (0 until it.count).map { i -> it.getItem(i) ?: "" } } ?: emptyList()
                showSearchablePicker("选择发音人", items, spinnerVoice.selectedItemPosition) { pos ->
                    spinnerVoice.setSelection(pos)
                    updateVisibility()
                }
            }
            true
        }

        // 先初始化分类和发音人（此时还没有设置监听器，不会触发回调）
        spinnerCategory.setSelection(catKeys.indexOf(currentCat))
        updateVoiceSpinner(spinnerVoice, currentCat, src.voice)
        updateVisibility()

        // 第一次加载后延迟重新确认发音人选中项，确保 view 已 layout 完成
        spinnerVoice.post {
            val savedVoiceId = src.voice
            val voiceList = spinnerVoice.tag as? List<VoiceInfo> ?: return@post
            val idx = voiceList.indexOfFirst { it.voice_id == savedVoiceId }
            if (idx >= 0 && spinnerVoice.selectedItemPosition != idx) {
                spinnerVoice.setSelection(idx)
            }
        }

        spinnerVoice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateVisibility()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerCategory.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val currentVoiceId = getSelectedVoiceId(spinnerVoice)
                updateVoiceSpinner(spinnerVoice, catKeys[position], currentVoiceId)
                updateVisibility()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 试听按钮
        val etPreviewText = dialogView.findViewById<EditText>(R.id.et_preview_text)
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        etPreviewText.setText(prefs.getString("tts_preview_text", "你好，这是一段试听语音"))
        val btnPreview = dialogView.findViewById<Button>(R.id.btn_preview)
        btnPreview.setOnClickListener {
            val voiceId = getSelectedVoiceId(spinnerVoice)
            val speed = etSpeed.text.toString().toDoubleOrNull() ?: 1.0
            val volume = etVolume.text.toString().toDoubleOrNull() ?: 1.0
            val sampleRate = etSampleRate.text.toString()
            val emotion = (0 until llEmotionCheck.childCount)
                .mapNotNull { llEmotionCheck.getChildAt(it) as? CheckBox }
                .filter { it.isChecked }
                .joinToString(",") { it.tag as String }
            val contextTexts = etPrompt.text.toString()

            // 1. 把当前参数保存到 tag="试听" 的配置中，供 @js: 引擎读取
            ensurePreviewConfig(voiceId, speed, volume, sampleRate, emotion, contextTexts)

            // 2. 试听文本包成对话形式并打上 <<试听>> 标签，让脚本按角色配置合成
            val rawText = etPreviewText.text.toString()
            val previewText = "\u201C <<试听>>$rawText\u201D"

            TtsPreviewHelper.previewVoice(
                fragment = this@ConfigListDialog,
                previewText = previewText,
                voiceId = voiceId,
                speed = speed,
                volume = volume,
                sampleRate = sampleRate,
                emotion = emotion,
                contextTexts = contextTexts,
                onToast = { msg -> toast(msg) }
            )
        }

        AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                val newGroupIndex = spinnerGroup.selectedItemPosition

                item.displayName = etName.text.toString()
                item.groupId = configData[newGroupIndex].group.id

                val tagInfo = tagItems.find { it.first == selectedTagValue } ?: ("" to "")
                cfg.speechRule = SpeechRule(tagInfo.first, tagInfo.second)

                val voiceId = getSelectedVoiceId(spinnerVoice)
                src.voice = voiceId
                data.contextTexts = etPrompt.text.toString()
                cfg.audioFormat = AudioFormat(etSampleRate.text.toString())

                val speed = etSpeed.text.toString().toDoubleOrNull() ?: 1.0
                val volume = etVolume.text.toString().toDoubleOrNull() ?: 1.0
                cfg.audioParams = AudioParams(speed, volume)
                src.speed = speed
                src.volume = volume

                data.emotion = (0 until llEmotionCheck.childCount)
                    .mapNotNull { llEmotionCheck.getChildAt(it) as? CheckBox }
                    .filter { it.isChecked }
                    .joinToString(",") { it.tag as String }

                src.data = data
                cfg.source = src

                // 切换分组
                if (newGroupIndex != groupIndex) {
                    configData[groupIndex].list.removeAt(childIndex)
                    configData[newGroupIndex].list.add(item)
                    expandedGroupIndex = newGroupIndex
                }

                // 保存试听文本到 SharedPreferences
                prefs.edit().putString("tts_preview_text", etPreviewText.text.toString()).apply()

                adapter.updateData(buildFlatList())
                saveConfigData()
            }
            .setNegativeButton("取消", null)
            .create()
            .apply { window?.setWindowAnimations(R.style.DialogAnimation) }
            .show()
    }

    // ===== 辅助 =====
    private fun buildTagItems(): List<Pair<String, String>> {
        val items = mutableListOf<Pair<String, String>>()
        items.add("" to "—— 请选择标签 ——")
        mainRoles.forEach { (show, tag, num) ->
            for (i in 1..num) {
                val n = i.toString().padStart(2, '0')
                items.add("$tag$n" to "$show$n")
            }
        }
        batchRoles.forEach { (show, tag, num) ->
            for (i in 1..num) {
                val n = i.toString().padStart(2, '0')
                items.add("$tag$n" to "$show$n")
            }
        }
        specialRoles.forEach { (show, tag) ->
            items.add(tag to show)
        }
        // 旁白
        items.add("narration" to "旁白")
        return items
    }

    private fun setupTagSpinner(spinner: Spinner, items: List<Pair<String, String>>) {
        val adapter = object : ArrayAdapter<Pair<String, String>>(
            requireContext(), android.R.layout.simple_spinner_item, items
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                view.text = getItem(position)?.second ?: ""
                view.setTextColor(textPrimary)
                return view
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getDropDownView(position, convertView, parent) as TextView
                view.text = getItem(position)?.second ?: ""
                view.setTextColor(textPrimary)
                return view
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
    }

    private fun getTagIndex(items: List<Pair<String, String>>, tagValue: String): Int {
        return items.indexOfFirst { it.first == tagValue }.coerceAtLeast(0)
    }

    private fun getTagInfoByIndex(items: List<Pair<String, String>>, index: Int): Pair<String, String> {
        return items.getOrNull(index) ?: ("" to "")
    }

    private fun updateVoiceSpinner(spinner: Spinner, category: String, selectedVoiceId: String) {
        val filtered = when (category) {
            "pro" -> voiceData.filter { it.is_pro }
            "emotion" -> voiceData.filter { it.is_emotion }
            "female" -> voiceData.filter { it.gender == "女性" && !it.is_pro && !it.is_emotion && !it.is_singing }
            "male" -> voiceData.filter { it.gender == "男性" && !it.is_pro && !it.is_emotion && !it.is_singing }
            "sing" -> voiceData.filter { it.is_singing }
            else -> voiceData
        }
        val displayList = filtered.map { "${it.name}(${it.gender})" }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, displayList).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinner.adapter = adapter
        spinner.tag = filtered // 保存voice列表
        val selIndex = filtered.indexOfFirst { it.voice_id == selectedVoiceId }
        if (selIndex >= 0) spinner.setSelection(selIndex)
    }

    private fun getSelectedVoice(spinner: Spinner): VoiceInfo? {
        val list = spinner.tag as? List<VoiceInfo> ?: return null
        val pos = spinner.selectedItemPosition
        return list.getOrNull(pos)
    }

    private fun getSelectedVoiceId(spinner: Spinner): String {
        return getSelectedVoice(spinner)?.voice_id ?: ""
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    /**
     * 确保 jiaoseliebiao-list.json 中存在 tag="试听" 的配置项，
     * 并用当前弹窗中的参数更新它，以便 @js: 引擎在试听时读取。
     */
    private fun ensurePreviewConfig(
        voiceId: String,
        speed: Double,
        volume: Double,
        sampleRate: String,
        emotion: String,
        contextTexts: String
    ) {
        var found = false
        configData.forEach { group ->
            group.list.forEach { item ->
                if (item.config.speechRule?.tag == "试听") {
                    found = true
                    item.displayName = "试听"
                    item.config.source?.let { src ->
                        src.voice = voiceId
                        src.speed = speed
                        src.volume = volume
                        src.data = src.data?.copy(
                            contextTexts = contextTexts,
                            emotion = emotion
                        ) ?: SourceData(contextTexts, emotion)
                    }
                    item.config.audioParams = AudioParams(speed, volume)
                    item.config.audioFormat = AudioFormat(sampleRate)
                }
            }
        }

        if (!found) {
            val groupId = System.currentTimeMillis()
            val itemId = groupId + 1
            configData.add(
                ConfigGroup(
                    group = GroupInfo(groupId, "试听分组"),
                    list = mutableListOf(
                        ConfigItem(
                            id = itemId,
                            displayName = "试听",
                            groupId = groupId,
                            enabled = true,
                            config = ConfigDetail(
                                speechRule = SpeechRule("试听", "试听"),
                                audioParams = AudioParams(speed, volume),
                                audioFormat = AudioFormat(sampleRate),
                                source = Source(
                                    speed = speed,
                                    volume = volume,
                                    voice = voiceId,
                                    data = SourceData(contextTexts, emotion)
                                )
                            )
                        )
                    )
                )
            )
        }

        saveConfigData()
    }
}
