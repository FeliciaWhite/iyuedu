package io.legado.app.ui.tts.plugin

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
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
    private var onPluginClick: ((JReadVoiceEngine.VoicePlugin) -> Unit)? = null
    private var onPluginEdit: ((JReadVoiceEngine.VoicePlugin) -> Unit)? = null
    private var onPluginToggle: ((JReadVoiceEngine.VoicePlugin, Boolean) -> Unit)? = null
    private var onPluginDelete: ((JReadVoiceEngine.VoicePlugin) -> Unit)? = null
    private var onPluginAudioParams: ((JReadVoiceEngine.VoicePlugin) -> Unit)? = null

    fun setRows(newRows: List<ConfigListRow>) { rows.clear(); rows.addAll(newRows); notifyDataSetChanged() }
    fun setPluginsMap(map: Map<String, JReadVoiceEngine.VoicePlugin>) { pluginsMap = map }

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
        private val cbEnabled: CheckBox = itemView.findViewById(R.id.cb_group_enabled)
        private val tvGroupName: TextView = itemView.findViewById(R.id.tv_group_name)
        private val tvCount: TextView = itemView.findViewById(R.id.tv_count)
        private val ivMore: ImageView = itemView.findViewById(R.id.iv_more)

        fun bind(row: ConfigListRow.GroupHeader) {
            tvGroupName.text = row.groupName.ifBlank { "默认分组" }
            tvCount.text = "${row.configCount}项"
            ivExpand.setImageResource(if (row.expanded) R.drawable.ic_arrow_down else R.drawable.ic_arrow_right)
            itemView.setPadding(0, itemView.paddingTop, itemView.paddingRight, itemView.paddingBottom)
            cbEnabled.setOnCheckedChangeListener(null)
            cbEnabled.isChecked = row.allEnabled
            cbEnabled.setOnCheckedChangeListener { _, checked -> onGroupToggleEnabled?.invoke(row.groupName, checked) }
            itemView.setOnClickListener { onGroupToggle?.invoke(row.groupName) }
            ivMore.setOnClickListener {
                val pm = PopupMenu(itemView.context, ivMore)
                pm.menu.add("一键整理标签"); pm.menu.add("更换插件")
                pm.menu.add("重命名分组"); pm.menu.add("删除分组")
                pm.setOnMenuItemClickListener { item ->
                    when (item.title) {
                        "一键整理标签" -> onGroupOrganizeTags?.invoke(row.groupName, null)
                        "更换插件" -> onGroupReplacePlugin?.invoke(row.groupName, null)
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
            cbEnabled.setOnCheckedChangeListener(null)
            cbEnabled.isChecked = row.allEnabled
            cbEnabled.setOnCheckedChangeListener { _, checked -> onSubGroupToggleEnabled?.invoke(row.groupName, row.subGroupName, checked) }
            itemView.setOnClickListener { onSubGroupToggle?.invoke(row.groupName, row.subGroupName) }
            ivMore.setOnClickListener {
                val pm = PopupMenu(itemView.context, ivMore)
                pm.menu.add("一键整理标签"); pm.menu.add("更换插件")
                pm.menu.add("重命名"); pm.menu.add("删除")
                pm.setOnMenuItemClickListener { item ->
                    when (item.title) {
                        "一键整理标签" -> onGroupOrganizeTags?.invoke(row.groupName, row.subGroupName)
                        "更换插件" -> onGroupReplacePlugin?.invoke(row.groupName, row.subGroupName)
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
            tvTag.text = config.voiceTag
            tvName.text = config.displayName.ifBlank { config.voice }
            val plugin = pluginsMap[config.pluginId]
            tvPluginName.text = if (plugin != null) displayNameForConfigCard(plugin) else ""
            tvParams.text = "采样率:24000hz | 音量:${"%.1f".format(config.volume)} | 语速:${"%.1f".format(config.speed)} | 音高:${"%.1f".format(config.pitch)}"
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

        fun displayNameForConfigCard(plugin: JReadVoiceEngine.VoicePlugin): String {
            return plugin.name.trim()
                .ifBlank { plugin.pluginId.trim().takeIf { !looksLikeOpaquePluginId(it) }.orEmpty() }
                .ifBlank { "插件未命名" }
        }
    }
}
