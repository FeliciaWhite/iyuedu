package io.legado.app.ui.replace

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.core.os.bundleOf
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.databinding.ItemReplaceGroupBinding
import io.legado.app.databinding.ItemReplaceRuleBinding
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.widget.recycler.DragSelectTouchHelper
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.utils.ColorUtils
import splitties.init.appCtx
import java.util.Collections

sealed class ReplaceFlatItem {
    abstract val id: Long

    data class GroupHeader(
        val groupName: String,
        val ruleCount: Int,
        val enabledCount: Int,
        val isExpanded: Boolean
    ) : ReplaceFlatItem() {
        override val id: Long get() = groupName.hashCode().toLong()
    }

    data class RuleItem(val rule: ReplaceRule) : ReplaceFlatItem() {
        override val id: Long get() = rule.id
    }
}

class ReplaceRuleAdapter(private val context: Context, var callBack: CallBack) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>(),
    ItemTouchCallback.Callback {

    companion object {
        private const val TYPE_GROUP = 0
        private const val TYPE_RULE = 1
    }

    private val inflater = android.view.LayoutInflater.from(context)
    private var currentRules = listOf<ReplaceRule>()
    private var flatItems = listOf<ReplaceFlatItem>()
    var expandedGroups = setOf<String>()
        private set
    private val selected = linkedSetOf<ReplaceRule>()
    private val defaultGroupName by lazy { appCtx.getString(R.string.no_group) }

    val selection: List<ReplaceRule>
        get() = selected.toList()

    val ruleItemCount: Int
        get() = currentRules.size

    fun setGroupedData(rules: List<ReplaceRule>, expanded: Set<String>, reorder: Boolean = false) {
        if (reorder && rules.isNotEmpty()) {
            val grouped = rules.groupBy {
                it.group?.takeIf { g -> g.isNotBlank() } ?: defaultGroupName
            }
            val seenGroups = linkedSetOf<String>()
            rules.forEach {
                val g = it.group?.takeIf { g -> g.isNotBlank() } ?: defaultGroupName
                seenGroups.add(g)
            }
            var expectedOrder = 1
            val updatedRules = mutableListOf<ReplaceRule>()
            seenGroups.forEach { groupName ->
                val groupRules = grouped[groupName] ?: return@forEach
                groupRules.forEach { rule ->
                    if (rule.order != expectedOrder) {
                        rule.order = expectedOrder
                        updatedRules.add(rule)
                    }
                    expectedOrder++
                }
            }
            if (updatedRules.isNotEmpty()) {
                callBack.update(*updatedRules.toTypedArray())
            }
        }
        currentRules = rules
        expandedGroups = expanded
        val newFlatItems = buildFlatItems()
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = flatItems.size
            override fun getNewListSize() = newFlatItems.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                flatItems[oldPos].id == newFlatItems[newPos].id

            override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                when {
                    flatItems[oldPos] is ReplaceFlatItem.RuleItem
                            && newFlatItems[newPos] is ReplaceFlatItem.RuleItem -> {
                        val oldRule = (flatItems[oldPos] as ReplaceFlatItem.RuleItem).rule
                        val newRule = (newFlatItems[newPos] as ReplaceFlatItem.RuleItem).rule
                        oldRule.id == newRule.id &&
                                oldRule.isEnabled == newRule.isEnabled &&
                                oldRule.name == newRule.name &&
                                oldRule.group == newRule.group &&
                                oldRule.order == newRule.order
                    }
                    else -> flatItems[oldPos] == newFlatItems[newPos]
                }
        })
        flatItems = newFlatItems
        diffResult.dispatchUpdatesTo(this)
        callBack.upCountView()
    }

    fun toggleGroup(groupName: String) {
        expandedGroups = if (expandedGroups.contains(groupName)) {
            expandedGroups - groupName
        } else {
            expandedGroups + groupName
        }
        setGroupedData(currentRules, expandedGroups)
    }

    private fun buildFlatItems(): List<ReplaceFlatItem> {
        val result = mutableListOf<ReplaceFlatItem>()
        val grouped = currentRules.groupBy {
            it.group?.takeIf { g -> g.isNotBlank() } ?: defaultGroupName
        }
        val seenGroups = linkedSetOf<String>()
        currentRules.forEach {
            val g = it.group?.takeIf { g -> g.isNotBlank() } ?: defaultGroupName
            seenGroups.add(g)
        }
        seenGroups.forEach { groupName ->
            val groupRules = grouped[groupName] ?: return@forEach
            val enabledCount = groupRules.count { it.isEnabled }
            val isExpanded = expandedGroups.contains(groupName)
            result.add(
                ReplaceFlatItem.GroupHeader(
                    groupName,
                    groupRules.size,
                    enabledCount,
                    isExpanded
                )
            )
            if (isExpanded) {
                groupRules.forEach { rule ->
                    result.add(ReplaceFlatItem.RuleItem(rule))
                }
            }
        }
        return result
    }

    fun selectAll() {
        currentRules.forEach { selected.add(it) }
        notifyItemRangeChanged(0, itemCount, bundleOf(Pair("selected", null)))
        callBack.upCountView()
    }

    fun revertSelection() {
        currentRules.forEach {
            if (selected.contains(it)) selected.remove(it) else selected.add(it)
        }
        notifyItemRangeChanged(0, itemCount, bundleOf(Pair("selected", null)))
        callBack.upCountView()
    }

    fun isGroupHeader(position: Int): Boolean {
        return flatItems.getOrNull(position) is ReplaceFlatItem.GroupHeader
    }

    override fun getItemCount() = flatItems.size

    override fun getItemViewType(position: Int): Int {
        return when (flatItems[position]) {
            is ReplaceFlatItem.GroupHeader -> TYPE_GROUP
            is ReplaceFlatItem.RuleItem -> TYPE_RULE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_GROUP -> GroupViewHolder(
                ItemReplaceGroupBinding.inflate(inflater, parent, false)
            )
            else -> RuleViewHolder(
                ItemReplaceRuleBinding.inflate(inflater, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        onBindViewHolder(holder, position, mutableListOf())
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        when (val item = flatItems[position]) {
            is ReplaceFlatItem.GroupHeader -> (holder as GroupViewHolder).bind(item, payloads)
            is ReplaceFlatItem.RuleItem -> (holder as RuleViewHolder).bind(item, payloads)
        }
    }

    inner class GroupViewHolder(private val binding: ItemReplaceGroupBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                val item = flatItems.getOrNull(pos) as? ReplaceFlatItem.GroupHeader
                    ?: return@setOnClickListener
                callBack.onGroupToggle(item.groupName)
            }
            binding.ivGroupStatus.setOnClickListener {
                val pos = bindingAdapterPosition
                val item = flatItems.getOrNull(pos) as? ReplaceFlatItem.GroupHeader
                    ?: return@setOnClickListener
                val newChecked = when {
                    item.enabledCount == 0 -> true
                    item.enabledCount == item.ruleCount -> false
                    else -> true
                }
                callBack.onGroupEnableToggle(item.groupName, newChecked)
            }
            binding.ivGroupMore.setOnClickListener {
                val pos = bindingAdapterPosition
                val item = flatItems.getOrNull(pos) as? ReplaceFlatItem.GroupHeader
                    ?: return@setOnClickListener
                showGroupMenu(it, item.groupName)
            }
        }

        fun bind(item: ReplaceFlatItem.GroupHeader, payloads: MutableList<Any>) {
            binding.run {
                if (payloads.isEmpty() || payloads.any { it == null }) {
                    tvGroupName.text = "${item.groupName}(${item.ruleCount})"
                    ivExpand.rotation = if (item.isExpanded) 180f else 0f
                }
                updateCheckBoxState(item)
            }
        }

        private fun updateCheckBoxState(item: ReplaceFlatItem.GroupHeader) {
            binding.ivGroupStatus.setImageResource(
                when {
                    item.ruleCount == 0 || item.enabledCount == 0 -> R.drawable.ic_checkbox_empty
                    item.enabledCount == item.ruleCount -> R.drawable.ic_checkbox_checked
                    else -> R.drawable.ic_checkbox_indeterminate
                }
            )
        }
    }

    inner class RuleViewHolder(private val binding: ItemReplaceRuleBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.swtEnabled.setOnCheckedChangeListener { buttonView, isChecked ->
                if (buttonView.isPressed) {
                    val pos = bindingAdapterPosition
                    val item = flatItems.getOrNull(pos) as? ReplaceFlatItem.RuleItem
                        ?: return@setOnCheckedChangeListener
                    callBack.update(item.rule.copy(isEnabled = isChecked))
                }
            }
            binding.ivEdit.setOnClickListener {
                val pos = bindingAdapterPosition
                val item = flatItems.getOrNull(pos) as? ReplaceFlatItem.RuleItem
                    ?: return@setOnClickListener
                callBack.edit(item.rule)
            }
            binding.cbName.setOnClickListener {
                val pos = bindingAdapterPosition
                val item = flatItems.getOrNull(pos) as? ReplaceFlatItem.RuleItem
                    ?: return@setOnClickListener
                if (binding.cbName.isChecked) {
                    selected.add(item.rule)
                } else {
                    selected.remove(item.rule)
                }
                callBack.upCountView()
            }
            binding.ivMenuMore.setOnClickListener {
                val pos = bindingAdapterPosition
                val item = flatItems.getOrNull(pos) as? ReplaceFlatItem.RuleItem
                    ?: return@setOnClickListener
                showRuleMenu(it, item.rule)
            }
        }

        fun bind(item: ReplaceFlatItem.RuleItem, payloads: MutableList<Any>) {
            binding.run {
                if (payloads.isEmpty() || payloads.any { it == null }) {
                    root.setBackgroundColor(ColorUtils.withAlpha(context.backgroundColor, 0.5f))
                    cbName.text = item.rule.name
                    swtEnabled.isChecked = item.rule.isEnabled
                    cbName.isChecked = selected.contains(item.rule)
                } else {
                    payloads.forEach { payload ->
                        val bundle = payload as? Bundle ?: return@forEach
                        bundle.keySet().forEach { key ->
                            when (key) {
                                "selected" -> cbName.isChecked = selected.contains(item.rule)
                                "upName" -> cbName.text = item.rule.name
                                "enabled" -> swtEnabled.isChecked = item.rule.isEnabled
                            }
                        }
                    }
                }
            }
        }
    }

    private fun showGroupMenu(view: View, groupName: String) {
        val popupMenu = PopupMenu(context, view)
        popupMenu.inflate(R.menu.replace_rule_group)
        popupMenu.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_group_top -> callBack.onGroupToTop(groupName)
                R.id.menu_group_up -> callBack.onGroupUp(groupName)
                R.id.menu_group_down -> callBack.onGroupDown(groupName)
                R.id.menu_group_bottom -> callBack.onGroupToBottom(groupName)
                R.id.menu_group_export -> callBack.onExportGroup(groupName)
                R.id.menu_group_del -> callBack.onDeleteGroup(groupName)
            }
            true
        }
        popupMenu.show()
    }

    private fun showRuleMenu(view: View, rule: ReplaceRule) {
        val popupMenu = PopupMenu(context, view)
        popupMenu.inflate(R.menu.replace_rule_item)
        popupMenu.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_top -> callBack.toTop(rule)
                R.id.menu_bottom -> callBack.toBottom(rule)
                R.id.menu_del -> {
                    callBack.delete(rule)
                    selected.remove(rule)
                }
            }
            true
        }
        popupMenu.show()
    }

    override fun swap(srcPosition: Int, targetPosition: Int): Boolean {
        val srcItem = flatItems.getOrNull(srcPosition)
        val targetItem = flatItems.getOrNull(targetPosition)

        if (srcItem is ReplaceFlatItem.RuleItem && targetItem is ReplaceFlatItem.RuleItem) {
            val srcGroup = getGroupName(srcItem.rule)
            val targetGroup = getGroupName(targetItem.rule)
            if (srcGroup != targetGroup) {
                return false
            }
            val srcOrder = srcItem.rule.order
            srcItem.rule.order = targetItem.rule.order
            targetItem.rule.order = srcOrder
            movedRules.add(srcItem.rule)
            movedRules.add(targetItem.rule)
            val mutableList = flatItems.toMutableList()
            mutableList[srcPosition] = targetItem
            mutableList[targetPosition] = srcItem
            flatItems = mutableList
            notifyItemMoved(srcPosition, targetPosition)
            return true
        }

        if (srcItem is ReplaceFlatItem.GroupHeader && targetItem is ReplaceFlatItem.GroupHeader) {
            val minPos = minOf(srcPosition, targetPosition)
            val maxPos = maxOf(srcPosition, targetPosition)
            for (i in minPos + 1 until maxPos) {
                if (flatItems.getOrNull(i) !is ReplaceFlatItem.GroupHeader) {
                    return false
                }
            }
            val mutableList = flatItems.toMutableList()
            mutableList[srcPosition] = targetItem
            mutableList[targetPosition] = srcItem
            flatItems = mutableList
            notifyItemMoved(srcPosition, targetPosition)
            movedGroups.add(srcItem.groupName)
            movedGroups.add(targetItem.groupName)
            return true
        }

        return false
    }

    private val movedRules = linkedSetOf<ReplaceRule>()
    private val movedGroups = linkedSetOf<String>()

    override fun onClearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        if (movedRules.isNotEmpty()) {
            callBack.update(*movedRules.toTypedArray())
            movedRules.clear()
        }
        if (movedGroups.isNotEmpty()) {
            val newGroupOrder = flatItems.filterIsInstance<ReplaceFlatItem.GroupHeader>().map { it.groupName }
            val grouped = currentRules.groupBy { getGroupName(it) }
            var order = 1
            val updatedRules = mutableListOf<ReplaceRule>()
            newGroupOrder.forEach { groupName ->
                val rules = grouped[groupName] ?: return@forEach
                rules.forEach { rule ->
                    if (rule.order != order) {
                        rule.order = order
                        updatedRules.add(rule)
                    }
                    order++
                }
            }
            if (updatedRules.isNotEmpty()) {
                callBack.update(*updatedRules.toTypedArray())
            }
            movedGroups.clear()
        }
    }

    val dragSelectCallback: DragSelectTouchHelper.Callback =
        object : DragSelectTouchHelper.AdvanceCallback<ReplaceRule>(Mode.ToggleAndReverse) {
            private val dummyRule by lazy { ReplaceRule(id = -1) }

            override fun currentSelectedId(): MutableSet<ReplaceRule> {
                return selected
            }

            override fun getItemId(position: Int): ReplaceRule {
                return when (val item = flatItems.getOrNull(position)) {
                    is ReplaceFlatItem.RuleItem -> item.rule
                    else -> dummyRule
                }
            }

            override fun updateSelectState(position: Int, isSelected: Boolean): Boolean {
                val item = flatItems.getOrNull(position) as? ReplaceFlatItem.RuleItem
                    ?: return false
                if (isSelected) {
                    selected.add(item.rule)
                } else {
                    selected.remove(item.rule)
                }
                notifyItemChanged(position, bundleOf(Pair("selected", null)))
                callBack.upCountView()
                return true
            }
        }

    private fun getGroupName(rule: ReplaceRule): String {
        return rule.group?.takeIf { it.isNotBlank() } ?: defaultGroupName
    }

    interface CallBack {
        fun update(vararg rule: ReplaceRule)
        fun delete(rule: ReplaceRule)
        fun edit(rule: ReplaceRule)
        fun toTop(rule: ReplaceRule)
        fun toBottom(rule: ReplaceRule)
        fun upOrder()
        fun upCountView()
        fun onGroupToggle(groupName: String)
        fun onGroupEnableToggle(groupName: String, enable: Boolean)
        fun onGroupToTop(groupName: String)
        fun onGroupToBottom(groupName: String)
        fun onGroupUp(groupName: String)
        fun onGroupDown(groupName: String)
        fun onExportGroup(groupName: String)
        fun onDeleteGroup(groupName: String)
    }
}
