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

    private var onConfigClick: ((JReadVoiceEngine.VoiceConfig) -> Unit)? = null
    private var onConfigPreview: ((JReadVoiceEngine.VoiceConfig) -> Unit)? = null
    private var onConfigDelete: ((JReadVoiceEngine.VoiceConfig) -> Unit)? = null
    private var onConfigToggle: ((JReadVoiceEngine.VoiceConfig, Boolean) -> Unit)? = null
    private var onGroupToggle: ((String) -> Unit)? = null
    private var onSubGroupToggle: ((String, String) -> Unit)? = null
    private var onGroupMore: ((String, String?) -> Unit)? = null
    private var onPluginClick: ((JReadVoiceEngine.VoicePlugin) -> Unit)? = null
    private var onPluginToggle: ((JReadVoiceEngine.VoicePlugin, Boolean) -> Unit)? = null
    private var onPluginDelete: ((JReadVoiceEngine.VoicePlugin) -> Unit)? = null
    private var onPluginAudioParams: ((JReadVoiceEngine.VoicePlugin) -> Unit)? = null

    fun setRows(newRows: List<ConfigListRow>) {
        rows.clear(); rows.addAll(newRows); notifyDataSetChanged()
    }

    fun setCallbacks(
        onConfigClick: ((JReadVoiceEngine.VoiceConfig) -> Unit)? = null,
        onConfigPreview: ((JReadVoiceEngine.VoiceConfig) -> Unit)? = null,
        onConfigDelete: ((JReadVoiceEngine.VoiceConfig) -> Unit)? = null,
        onConfigToggle: ((JReadVoiceEngine.VoiceConfig, Boolean) -> Unit)? = null,
        onGroupToggle: ((String) -> Unit)? = null,
        onSubGroupToggle: ((String, String) -> Unit)? = null,
        onGroupMore: ((String, String?) -> Unit)? = null,
        onPluginClick: ((JReadVoiceEngine.VoicePlugin) -> Unit)? = null,
        onPluginToggle: ((JReadVoiceEngine.VoicePlugin, Boolean) -> Unit)? = null,
        onPluginDelete: ((JReadVoiceEngine.VoicePlugin) -> Unit)? = null,
        onPluginAudioParams: ((JReadVoiceEngine.VoicePlugin) -> Unit)? = null,
    ) {
        this.onConfigClick = onConfigClick; this.onConfigPreview = onConfigPreview
        this.onConfigDelete = onConfigDelete; this.onConfigToggle = onConfigToggle
        this.onGroupToggle = onGroupToggle; this.onSubGroupToggle = onSubGroupToggle
        this.onGroupMore = onGroupMore; this.onPluginClick = onPluginClick
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
            TYPE_GROUP_HEADER, TYPE_SUB_GROUP_HEADER ->
                GroupHeaderViewHolder(inflater.inflate(R.layout.item_config_group_header, parent, false))
            TYPE_CONFIG ->
                ConfigViewHolder(inflater.inflate(R.layout.item_tts_plugin_config, parent, false))
            TYPE_PLUGIN ->
                PluginViewHolder(inflater.inflate(R.layout.item_tts_plugin_config, parent, false))
            else -> throw IllegalArgumentException("Unknown viewType: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is ConfigListRow.GroupHeader -> (holder as GroupHeaderViewHolder).bind(row)
            is ConfigListRow.SubGroupHeader -> (holder as GroupHeaderViewHolder).bindSub(row)
            is ConfigListRow.ConfigRow -> (holder as ConfigViewHolder).bind(row.config)
            is ConfigListRow.PluginRow -> (holder as PluginViewHolder).bind(row.plugin)
        }
    }

    override fun getItemCount(): Int = rows.size

    inner class GroupHeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivExpand: ImageView = itemView.findViewById(R.id.iv_expand)
        private val tvGroupName: TextView = itemView.findViewById(R.id.tv_group_name)
        private val tvCount: TextView = itemView.findViewById(R.id.tv_count)
        private val ivMore: ImageView = itemView.findViewById(R.id.iv_more)

        fun bind(row: ConfigListRow.GroupHeader) {
            tvGroupName.text = row.groupName.ifBlank { "默认分组" }
            tvCount.text = "${row.configCount}项"
            ivExpand.setImageResource(if (row.expanded) R.drawable.ic_arrow_down else R.drawable.ic_arrow_right)
            itemView.setOnClickListener { onGroupToggle?.invoke(row.groupName) }
            ivMore.setOnClickListener {
                val pm = PopupMenu(itemView.context, ivMore)
                pm.menu.add("重命名分组"); pm.menu.add("删除分组")
                pm.menu.add("更换插件"); pm.menu.add("一键整理标签")
                pm.setOnMenuItemClickListener { onGroupMore?.invoke(row.groupName, null); true }
                pm.show()
            }
        }

        fun bindSub(row: ConfigListRow.SubGroupHeader) {
            tvGroupName.text = "  ${row.subGroupName.ifBlank { "默认" }}"
            tvCount.text = "${row.configCount}项"
            ivExpand.setImageResource(if (row.expanded) R.drawable.ic_arrow_down else R.drawable.ic_arrow_right)
            itemView.setOnClickListener { onSubGroupToggle?.invoke(row.groupName, row.subGroupName) }
            ivMore.setOnClickListener {
                val pm = PopupMenu(itemView.context, ivMore)
                pm.menu.add("重命名"); pm.menu.add("删除"); pm.menu.add("更换插件")
                pm.setOnMenuItemClickListener { onGroupMore?.invoke(row.groupName, row.subGroupName); true }
                pm.show()
            }
        }
    }

    inner class ConfigViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTag: TextView = itemView.findViewById(R.id.tv_tag)
        private val tvName: TextView = itemView.findViewById(R.id.tv_name)
        private val tvGroup: TextView = itemView.findViewById(R.id.tv_group)
        private val tvVoice: TextView = itemView.findViewById(R.id.tv_voice)
        private val cbEnabled: CheckBox = itemView.findViewById(R.id.cb_enabled)
        private val ivPreview: ImageView = itemView.findViewById(R.id.iv_preview)
        private val ivDelete: ImageView = itemView.findViewById(R.id.iv_delete)

        fun bind(config: JReadVoiceEngine.VoiceConfig) {
            tvTag.text = config.voiceTag
            tvName.text = config.displayName.ifBlank { config.voice }
            tvGroup.text = listOf(config.groupName, config.subGroupName).filter { it.isNotBlank() }.joinToString(" / ")
            tvVoice.text = config.voice
            cbEnabled.setOnCheckedChangeListener(null)
            cbEnabled.isChecked = config.enabled
            cbEnabled.setOnCheckedChangeListener { _, checked -> onConfigToggle?.invoke(config, checked) }
            itemView.setOnClickListener { onConfigClick?.invoke(config) }
            ivPreview.setOnClickListener { onConfigPreview?.invoke(config) }
            ivDelete.setOnClickListener { onConfigDelete?.invoke(config) }
        }
    }

    inner class PluginViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTag: TextView = itemView.findViewById(R.id.tv_tag)
        private val tvName: TextView = itemView.findViewById(R.id.tv_name)
        private val tvGroup: TextView = itemView.findViewById(R.id.tv_group)
        private val tvVoice: TextView = itemView.findViewById(R.id.tv_voice)
        private val cbEnabled: CheckBox = itemView.findViewById(R.id.cb_enabled)
        private val ivPreview: ImageView = itemView.findViewById(R.id.iv_preview)
        private val ivDelete: ImageView = itemView.findViewById(R.id.iv_delete)

        fun bind(plugin: JReadVoiceEngine.VoicePlugin) {
            tvTag.text = plugin.name
            tvName.text = "ID: ${plugin.pluginId.ifBlank { plugin.id }}"
            tvGroup.text = listOf(plugin.author, plugin.version).filter { it.isNotBlank() }.joinToString(" / ")
            tvVoice.text = plugin.urlTemplate.ifBlank { "JS 插件" }
            cbEnabled.setOnCheckedChangeListener(null)
            cbEnabled.isChecked = plugin.enabled
            cbEnabled.setOnCheckedChangeListener { _, checked -> onPluginToggle?.invoke(plugin, checked) }
            ivPreview.visibility = View.GONE
            itemView.setOnClickListener { onPluginClick?.invoke(plugin) }
            ivDelete.setOnClickListener {
                val pm = PopupMenu(itemView.context, ivDelete)
                pm.menu.add("音频参数"); pm.menu.add("删除")
                pm.setOnMenuItemClickListener { item ->
                    when (item.title) { "音频参数" -> onPluginAudioParams?.invoke(plugin); "删除" -> onPluginDelete?.invoke(plugin) }
                    true
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
    }
}
