package io.legado.app.ui.tts.plugin

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.help.audiobook.JReadVoiceEngine

class TtsPluginAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val rows = mutableListOf<ConfigListRow>()
    private var pluginsMap: Map<String, JReadVoiceEngine.VoicePlugin> = emptyMap()

    private var onConfigClick: ((JReadVoiceEngine.VoiceConfig) -> Unit)? = null
    private var onConfigPreview: ((JReadVoiceEngine.VoiceConfig) -> Unit)? = null
    private var onConfigDelete: ((JReadVoiceEngine.VoiceConfig) -> Unit)? = null
    private var onConfigToggle: ((JReadVoiceEngine.VoiceConfig, Boolean) -> Unit)? = null
    private var onGroupToggle: ((String) -> Unit)? = null
    private var onGroupToggleEnabled: ((String, Boolean) -> Unit)? = null
    private var onSubGroupToggle: ((String, String) -> Unit)? = null
    private var onSubGroupToggleEnabled: ((String, String, Boolean) -> Unit)? = null
    private var onGroupRename: ((String, String?) -> Unit)? = null
    private var onGroupDelete: ((String, String?) -> Unit)? = null
    private var onGroupReplacePlugin: ((String, String?) -> Unit)? = null
    private var onGroupOrganizeTags: ((String, String?) -> Unit)? = null
    private var onGroupAudioParams: ((String, String?) -> Unit)? = null
    private var onGroupMoveUp: ((String, String?) -> Unit)? = null
    private var onGroupMoveDown: ((String, String?) -> Unit)? = null
    private var onPluginClick: ((JReadVoiceEngine.VoicePlugin) -> Unit)? = null
    private var onPluginEdit: ((JReadVoiceEngine.VoicePlugin) -> Unit)? = null
    private var onPluginToggle: ((JReadVoiceEngine.VoicePlugin, Boolean) -> Unit)? = null
    private var onPluginDelete: ((JReadVoiceEngine.VoicePlugin) -> Unit)? = null
    private var onPluginAudioParams: ((JReadVoiceEngine.VoicePlugin) -> Unit)? = null

    fun setRows(newRows: List<ConfigListRow>) { rows.clear(); rows.addAll(newRows); notifyDataSetChanged() }
    fun setPluginsMap(map: Map<String, JReadVoiceEngine.VoicePlugin>) { pluginsMap = map }

    var onConfigMoved: ((List<String>) -> Unit)? = null
    var onGroupMoved: ((List<String>) -> Unit)? = null  // group names order
    var onSubGroupMoved: ((String, List<String>) -> Unit)? = null  // parent groupName, subGroup names order

    /**
     * 交换两个相邻 ConfigRow 的位置（仅限同一子分组内）
     */
    fun moveConfig(fromPos: Int, toPos: Int): Boolean {
        if (fromPos !in rows.indices || toPos !in rows.indices) return false
        val fromRow = rows[fromPos]
        val toRow = rows[toPos]
        if (fromRow is ConfigListRow.ConfigRow && toRow is ConfigListRow.ConfigRow) {
            val fc = fromRow.config
            val tc = toRow.config
            // 仅允许同一分组+子分组内移动
            if (fc.groupName == tc.groupName && fc.subGroupName == tc.subGroupName) {
                java.util.Collections.swap(rows, fromPos, toPos)
                notifyItemMoved(fromPos, toPos)
                return true
            }
        }
        return false
    }

    /**
     * 移动整个分组块（GroupHeader + 所有子项）到目标 GroupHeader 位置
     */
    fun moveGroupBlock(fromPos: Int, toPos: Int): Boolean {
        if (fromPos !in rows.indices || toPos !in rows.indices) return false
        val fromRow = rows[fromPos]
        val toRow = rows[toPos]
        // 只允许 GroupHeader 拖到 GroupHeader
        if (fromRow !is ConfigListRow.GroupHeader || toRow !is ConfigListRow.GroupHeader) return false
        if (fromPos == toPos) return false

        // 找到 from 分组块的范围 [fromPos, fromEnd)
        var fromEnd = fromPos + 1
        while (fromEnd < rows.size && rows[fromEnd] !is ConfigListRow.GroupHeader) {
            fromEnd++
        }
        // 找到 to 分组块的范围 [toPos, toEnd)
        var toEnd = toPos + 1
        while (toEnd < rows.size && rows[toEnd] !is ConfigListRow.GroupHeader) {
            toEnd++
        }

        // 提取 from 块和 to 块
        val fromBlock = rows.subList(fromPos, fromEnd).toList()
        val toBlock = rows.subList(toPos, toEnd).toList()

        // 重建 rows
        val newRows = mutableListOf<ConfigListRow>()
        if (fromPos < toPos) {
            // 向下拖：from 块移到 to 块后面
            newRows.addAll(rows.subList(0, fromPos))
            newRows.addAll(rows.subList(fromEnd, toPos))
            newRows.addAll(toBlock)
            newRows.addAll(fromBlock)
            newRows.addAll(rows.subList(toEnd, rows.size))
        } else {
            // 向上拖：from 块移到 to 块前面
            newRows.addAll(rows.subList(0, toPos))
            newRows.addAll(fromBlock)
            newRows.addAll(toBlock)
            newRows.addAll(rows.subList(fromEnd, rows.size))
        }

        rows.clear()
        rows.addAll(newRows)
        notifyDataSetChanged()
        return true
    }

    /**
     * 移动整个子分组块（SubGroupHeader + 其下所有 ConfigRow）到目标 SubGroupHeader 位置
     */
    fun moveSubGroupBlock(fromPos: Int, toPos: Int): Boolean {
        if (fromPos !in rows.indices || toPos !in rows.indices) return false
        val fromRow = rows[fromPos]
        val toRow = rows[toPos]
        // 只允许 SubGroupHeader 拖到 SubGroupHeader，且必须在同一大分组内
        if (fromRow !is ConfigListRow.SubGroupHeader || toRow !is ConfigListRow.SubGroupHeader) return false
        if (fromRow.groupName != toRow.groupName) return false
        if (fromPos == toPos) return false

        // 找到 from 子分组块的范围 [fromPos, fromEnd)
        var fromEnd = fromPos + 1
        while (fromEnd < rows.size &&
            rows[fromEnd] !is ConfigListRow.GroupHeader &&
            rows[fromEnd] !is ConfigListRow.SubGroupHeader) {
            fromEnd++
        }
        // 找到 to 子分组块的范围 [toPos, toEnd)
        var toEnd = toPos + 1
        while (toEnd < rows.size &&
            rows[toEnd] !is ConfigListRow.GroupHeader &&
            rows[toEnd] !is ConfigListRow.SubGroupHeader) {
            toEnd++
        }

        val fromBlock = rows.subList(fromPos, fromEnd).toList()
        val toBlock = rows.subList(toPos, toEnd).toList()

        val newRows = mutableListOf<ConfigListRow>()
        if (fromPos < toPos) {
            newRows.addAll(rows.subList(0, fromPos))
            newRows.addAll(rows.subList(fromEnd, toPos))
            newRows.addAll(toBlock)
            newRows.addAll(fromBlock)
            newRows.addAll(rows.subList(toEnd, rows.size))
        } else {
            newRows.addAll(rows.subList(0, toPos))
            newRows.addAll(fromBlock)
            newRows.addAll(toBlock)
            newRows.addAll(rows.subList(fromEnd, rows.size))
        }

        rows.clear()
        rows.addAll(newRows)
        notifyDataSetChanged()
        return true
    }

    /**
     * 获取当前所有 ConfigRow 的 configId 顺序（用于持久化）
     */
    fun getConfigIdOrder(): List<String> {
        return rows.filterIsInstance<ConfigListRow.ConfigRow>().map { it.config.id }
    }

    /**
     * 获取当前所有大分组的名称顺序（用于持久化 sortOrder）
     */
    fun getGroupNameOrder(): List<String> {
        return rows.filterIsInstance<ConfigListRow.GroupHeader>().map { it.groupName }
    }

    /**
     * 获取每个大分组下子分组的名称顺序（用于持久化 sortOrder）
     * 返回 Map<groupName, List<subGroupName>>
     */
    fun getSubGroupNameOrders(): Map<String, List<String>> {
        val result = mutableMapOf<String, MutableList<String>>()
        var currentGroup = ""
        for (row in rows) {
            when (row) {
                is ConfigListRow.GroupHeader -> currentGroup = row.groupName
                is ConfigListRow.SubGroupHeader -> {
                    result.getOrPut(currentGroup) { mutableListOf() }.add(row.subGroupName)
                }
                else -> Unit
            }
        }
        return result
    }

    fun createItemTouchHelper(): ItemTouchHelper {
        val callback = object : ItemTouchHelper.Callback() {
            override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
                return when (rows.getOrNull(vh.bindingAdapterPosition)) {
                    is ConfigListRow.ConfigRow,
                    is ConfigListRow.GroupHeader,
                    is ConfigListRow.SubGroupHeader -> makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
                    else -> 0
                }
            }
            override fun isLongPressDragEnabled(): Boolean = true
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val fromPos = vh.bindingAdapterPosition
                val toPos = target.bindingAdapterPosition
                val fromRow = rows.getOrNull(fromPos)
                return when (fromRow) {
                    is ConfigListRow.GroupHeader -> moveGroupBlock(fromPos, toPos)
                    is ConfigListRow.SubGroupHeader -> moveSubGroupBlock(fromPos, toPos)
                    is ConfigListRow.ConfigRow -> moveConfig(fromPos, toPos)
                    else -> false
                }
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) = Unit
            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                onConfigMoved?.invoke(getConfigIdOrder())
                onGroupMoved?.invoke(getGroupNameOrder())
                getSubGroupNameOrders().forEach { (groupName, subNames) ->
                    onSubGroupMoved?.invoke(groupName, subNames)
                }
            }
        }
        return ItemTouchHelper(callback)
    }

    fun setCallbacks(
        onConfigClick: ((JReadVoiceEngine.VoiceConfig) -> Unit)? = null,
        onConfigPreview: ((JReadVoiceEngine.VoiceConfig) -> Unit)? = null,
        onConfigDelete: ((JReadVoiceEngine.VoiceConfig) -> Unit)? = null,
        onConfigToggle: ((JReadVoiceEngine.VoiceConfig, Boolean) -> Unit)? = null,
        onGroupToggle: ((String) -> Unit)? = null,
        onGroupToggleEnabled: ((String, Boolean) -> Unit)? = null,
        onSubGroupToggle: ((String, String) -> Unit)? = null,
        onSubGroupToggleEnabled: ((String, String, Boolean) -> Unit)? = null,
        onGroupRename: ((String, String?) -> Unit)? = null,
        onGroupDelete: ((String, String?) -> Unit)? = null,
        onGroupReplacePlugin: ((String, String?) -> Unit)? = null,
        onGroupOrganizeTags: ((String, String?) -> Unit)? = null,
        onGroupAudioParams: ((String, String?) -> Unit)? = null,
        onGroupMoveUp: ((String, String?) -> Unit)? = null,
        onGroupMoveDown: ((String, String?) -> Unit)? = null,
        onPluginClick: ((JReadVoiceEngine.VoicePlugin) -> Unit)? = null,
        onPluginEdit: ((JReadVoiceEngine.VoicePlugin) -> Unit)? = null,
        onPluginToggle: ((JReadVoiceEngine.VoicePlugin, Boolean) -> Unit)? = null,
        onPluginDelete: ((JReadVoiceEngine.VoicePlugin) -> Unit)? = null,
        onPluginAudioParams: ((JReadVoiceEngine.VoicePlugin) -> Unit)? = null,
    ) {
        this.onConfigClick = onConfigClick; this.onConfigPreview = onConfigPreview
        this.onConfigDelete = onConfigDelete; this.onConfigToggle = onConfigToggle
        this.onGroupToggle = onGroupToggle; this.onGroupToggleEnabled = onGroupToggleEnabled
        this.onSubGroupToggle = onSubGroupToggle; this.onSubGroupToggleEnabled = onSubGroupToggleEnabled
        this.onGroupRename = onGroupRename; this.onGroupDelete = onGroupDelete
        this.onGroupReplacePlugin = onGroupReplacePlugin; this.onGroupOrganizeTags = onGroupOrganizeTags
        this.onGroupAudioParams = onGroupAudioParams
        this.onGroupMoveUp = onGroupMoveUp; this.onGroupMoveDown = onGroupMoveDown
        this.onPluginClick = onPluginClick; this.onPluginEdit = onPluginEdit
        this.onPluginToggle = onPluginToggle; this.onPluginDelete = onPluginDelete
        this.onPluginAudioParams = onPluginAudioParams
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is ConfigListRow.GroupHeader -> TYPE_GROUP_HEADER
        is ConfigListRow.SubGroupHeader -> TYPE_SUB_GROUP_HEADER
        is ConfigListRow.ConfigRow -> TYPE_CONFIG
        is ConfigListRow.PluginRow -> TYPE_PLUGIN
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_GROUP_HEADER, TYPE_SUB_GROUP_HEADER -> GroupHeaderViewHolder(inflater.inflate(R.layout.item_config_group_header, parent, false))
            TYPE_CONFIG -> ConfigViewHolder(inflater.inflate(R.layout.item_tts_plugin_config, parent, false))
            TYPE_PLUGIN -> PluginViewHolder(inflater.inflate(R.layout.item_tts_plugin_config, parent, false))
            else -> throw IllegalArgumentException("Unknown viewType: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is ConfigListRow.GroupHeader -> (holder as GroupHeaderViewHolder).bind(row)
            is ConfigListRow.SubGroupHeader -> (holder as GroupHeaderViewHolder).bindSub(row)
            is ConfigListRow.ConfigRow -> (holder as ConfigViewHolder).bind(row.config, row.indentLevel)
            is ConfigListRow.PluginRow -> (holder as PluginViewHolder).bind(row.plugin)
        }
    }

    override fun getItemCount(): Int = rows.size

    inner class GroupHeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivExpand: ImageView = itemView.findViewById(R.id.iv_expand)
        private val cbEnabled: ImageView = itemView.findViewById(R.id.cb_group_enabled)
        private val tvGroupName: TextView = itemView.findViewById(R.id.tv_group_name)
        private val tvCount: TextView = itemView.findViewById(R.id.tv_count)
        private val ivMore: ImageView = itemView.findViewById(R.id.iv_more)

        fun bind(row: ConfigListRow.GroupHeader) {
            tvGroupName.text = row.groupName.ifBlank { "默认分组" }
            tvCount.text = "${row.configCount}项"
            ivExpand.setImageResource(if (row.expanded) R.drawable.ic_arrow_down else R.drawable.ic_arrow_right)
            itemView.setPadding(0, itemView.paddingTop, itemView.paddingRight, itemView.paddingBottom)
            updateTriState(cbEnabled, row.allEnabled, row.someEnabled)
            cbEnabled.setOnClickListener {
                val newState = !row.allEnabled
                onGroupToggleEnabled?.invoke(row.groupName, newState)
            }
            itemView.setOnClickListener { onGroupToggle?.invoke(row.groupName) }
            ivMore.setOnClickListener {
                val pm = PopupMenu(itemView.context, ivMore)
                pm.menu.add("一键整理标签"); pm.menu.add("更换插件")
                pm.menu.add("音频调节"); pm.menu.add("上移"); pm.menu.add("下移")
                pm.menu.add("重命名分组"); pm.menu.add("删除分组")
                pm.setOnMenuItemClickListener { item ->
                    when (item.title) {
                        "一键整理标签" -> onGroupOrganizeTags?.invoke(row.groupName, null)
                        "更换插件" -> onGroupReplacePlugin?.invoke(row.groupName, null)
                        "音频调节" -> onGroupAudioParams?.invoke(row.groupName, null)
                        "上移" -> onGroupMoveUp?.invoke(row.groupName, null)
                        "下移" -> onGroupMoveDown?.invoke(row.groupName, null)
                        "重命名分组" -> onGroupRename?.invoke(row.groupName, null)
                        "删除分组" -> onGroupDelete?.invoke(row.groupName, null)
                    }; true
                }
                pm.show()
            }
        }

        fun bindSub(row: ConfigListRow.SubGroupHeader) {
            tvGroupName.text = row.subGroupName.ifBlank { "默认" }
            tvCount.text = "${row.configCount}项"
            ivExpand.setImageResource(if (row.expanded) R.drawable.ic_arrow_down else R.drawable.ic_arrow_right)
            itemView.setPadding(dpToPx(itemView.context, 24), itemView.paddingTop, itemView.paddingRight, itemView.paddingBottom)
            updateTriState(cbEnabled, row.allEnabled, row.someEnabled)
            cbEnabled.setOnClickListener {
                val newState = !row.allEnabled
                onSubGroupToggleEnabled?.invoke(row.groupName, row.subGroupName, newState)
            }
            itemView.setOnClickListener { onSubGroupToggle?.invoke(row.groupName, row.subGroupName) }
            ivMore.setOnClickListener {
                val pm = PopupMenu(itemView.context, ivMore)
                pm.menu.add("一键整理标签"); pm.menu.add("更换插件")
                pm.menu.add("音频调节"); pm.menu.add("上移"); pm.menu.add("下移")
                pm.menu.add("重命名"); pm.menu.add("删除")
                pm.setOnMenuItemClickListener { item ->
                    when (item.title) {
                        "一键整理标签" -> onGroupOrganizeTags?.invoke(row.groupName, row.subGroupName)
                        "更换插件" -> onGroupReplacePlugin?.invoke(row.groupName, row.subGroupName)
                        "音频调节" -> onGroupAudioParams?.invoke(row.groupName, row.subGroupName)
                        "上移" -> onGroupMoveUp?.invoke(row.groupName, row.subGroupName)
                        "下移" -> onGroupMoveDown?.invoke(row.groupName, row.subGroupName)
                        "重命名" -> onGroupRename?.invoke(row.groupName, row.subGroupName)
                        "删除" -> onGroupDelete?.invoke(row.groupName, row.subGroupName)
                    }; true
                }
                pm.show()
            }
        }
    }

    inner class ConfigViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTag: TextView = itemView.findViewById(R.id.tv_tag)
        private val tvPluginName: TextView = itemView.findViewById(R.id.tv_plugin_name)
        private val tvName: TextView = itemView.findViewById(R.id.tv_name)
        private val tvParams: TextView = itemView.findViewById(R.id.tv_params)
        private val cbEnabled: CheckBox = itemView.findViewById(R.id.cb_enabled)
        private val ivPreview: ImageView = itemView.findViewById(R.id.iv_preview)
        private val ivMore: ImageView = itemView.findViewById(R.id.iv_more)

        fun bind(config: JReadVoiceEngine.VoiceConfig, indentLevel: Int = 0) {
            // 标签：只显示真正的标签字段 voiceTag；为空则隐藏标签行，不用其它字段冒充
            if (config.voiceTag.isBlank()) {
                tvTag.visibility = View.GONE
            } else {
                tvTag.visibility = View.VISIBLE
                tvTag.text = config.voiceTag
            }
            tvName.text = config.displayName.ifBlank { config.voice }
            val plugin = pluginsMap[config.pluginId]
            tvPluginName.text = if (plugin != null) displayNameForConfigCard(plugin) else ""
            // 采样率从 data.sampleRate 读取（导入配置多为字符串），缺省回退 24000
            val sampleRate = runCatching {
                val obj = org.json.JSONObject(config.dataJson)
                val raw = obj.optString("sampleRate").ifBlank { obj.optInt("sampleRate", 24000).toString() }
                raw.toIntOrNull() ?: 24000
            }.getOrDefault(24000)
            tvParams.text = "${config.voice} | ${sampleRate}hz | 音量:${"%.1f".format(config.volume)} | 语速:${"%.1f".format(config.speed)} | 音高:${"%.1f".format(config.pitch)}"
            itemView.setPadding(dpToPx(itemView.context, indentLevel * 24), itemView.paddingTop, itemView.paddingRight, itemView.paddingBottom)
            cbEnabled.setOnCheckedChangeListener(null)
            cbEnabled.isChecked = config.enabled
            cbEnabled.setOnCheckedChangeListener { _, checked -> onConfigToggle?.invoke(config, checked) }
            itemView.setOnClickListener { onConfigClick?.invoke(config) }
            ivPreview.setOnClickListener { onConfigPreview?.invoke(config) }
            ivMore.setOnClickListener {
                val pm = PopupMenu(itemView.context, ivMore)
                pm.menu.add("编辑"); pm.menu.add("试听"); pm.menu.add("删除")
                pm.setOnMenuItemClickListener { item ->
                    when (item.title) {
                        "编辑" -> onConfigClick?.invoke(config)
                        "试听" -> onConfigPreview?.invoke(config)
                        "删除" -> onConfigDelete?.invoke(config)
                    }; true
                }
                pm.show()
            }
        }
    }

    inner class PluginViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTag: TextView = itemView.findViewById(R.id.tv_tag)
        private val tvPluginName: TextView = itemView.findViewById(R.id.tv_plugin_name)
        private val tvName: TextView = itemView.findViewById(R.id.tv_name)
        private val tvParams: TextView = itemView.findViewById(R.id.tv_params)
        private val cbEnabled: CheckBox = itemView.findViewById(R.id.cb_enabled)
        private val ivPreview: ImageView = itemView.findViewById(R.id.iv_preview)
        private val ivMore: ImageView = itemView.findViewById(R.id.iv_more)

        fun bind(plugin: JReadVoiceEngine.VoicePlugin) {
            tvTag.text = plugin.name.ifBlank { "未命名插件" }
            tvPluginName.text = ""
            tvName.text = plugin.pluginId.ifBlank { plugin.id }.takeIf { !looksLikeOpaquePluginId(it) }.orEmpty()
            tvParams.text = listOf(plugin.author, plugin.version).filter { it.isNotBlank() }.joinToString(" | ")
            cbEnabled.setOnCheckedChangeListener(null)
            cbEnabled.isChecked = plugin.enabled
            cbEnabled.setOnCheckedChangeListener { _, checked -> onPluginToggle?.invoke(plugin, checked) }
            ivPreview.visibility = View.GONE
            itemView.setOnClickListener { onPluginClick?.invoke(plugin) }
            ivMore.setOnClickListener {
                val pm = PopupMenu(itemView.context, ivMore)
                pm.menu.add("编辑"); pm.menu.add("音频参数"); pm.menu.add("删除")
                pm.setOnMenuItemClickListener { item ->
                    when (item.title) {
                        "编辑" -> onPluginEdit?.invoke(plugin)
                        "音频参数" -> onPluginAudioParams?.invoke(plugin)
                        "删除" -> onPluginDelete?.invoke(plugin)
                    }; true
                }
                pm.show()
            }
        }
    }

    companion object {
        private const val TYPE_GROUP_HEADER = 0
        private const val TYPE_SUB_GROUP_HEADER = 1
        private const val TYPE_CONFIG = 2
        private const val TYPE_PLUGIN = 3

        fun looksLikeOpaquePluginId(id: String): Boolean {
            if (id.isBlank()) return true
            if (id.matches(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"))) return true
            if (id.matches(Regex("^\\d{6,}$"))) return true
            return false
        }

        fun dpToPx(context: android.content.Context, dp: Int): Int {
            return (dp * context.resources.displayMetrics.density).toInt()
        }

        fun updateTriState(iv: ImageView, allEnabled: Boolean, someEnabled: Boolean) {
            when {
                allEnabled -> iv.setImageResource(R.drawable.ic_checkbox_checked)
                someEnabled -> iv.setImageResource(R.drawable.ic_checkbox_indeterminate)
                else -> iv.setImageResource(R.drawable.ic_checkbox_empty)
            }
        }

        fun displayNameForConfigCard(plugin: JReadVoiceEngine.VoicePlugin): String {
            return plugin.name.trim()
                .ifBlank { plugin.pluginId.trim().takeIf { !looksLikeOpaquePluginId(it) }.orEmpty() }
                .ifBlank { "插件未命名" }
        }
    }
}
