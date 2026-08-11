package io.legado.app.ui.book.read.config

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.data.entities.AiImageTemplate
import io.legado.app.databinding.ItemAiImageTemplateBinding
import io.legado.app.model.AiImageGenerator

class AiImageTemplateListAdapter(
    private val selectedId: Long,
    private val bookUrl: String,
    private val onSelect: (AiImageTemplate) -> Unit,
    private val onDelete: (AiImageTemplate) -> Unit,
    private val onBindToggle: (AiImageTemplate, Boolean) -> Unit,
) : ListAdapter<AiImageTemplate, AiImageTemplateListAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAiImageTemplateBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    fun setData(newData: List<AiImageTemplate>) {
        submitList(newData)
    }

    inner class ViewHolder(private val binding: ItemAiImageTemplateBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(template: AiImageTemplate) {
            binding.tvName.text = if (template.isDefault) "${template.name}（默认）" else template.name
            binding.radioButton.isChecked = template.id == selectedId
            // 默认模板不可删除
            binding.btnDelete.visibility = if (template.isDefault) View.GONE else View.VISIBLE

            // 绑定开关：未打开书籍时隐藏
            val bindVisible = bookUrl.isNotBlank()
            binding.tvBindLabel.visibility = if (bindVisible) View.VISIBLE else View.GONE
            binding.switchBind.visibility = if (bindVisible) View.VISIBLE else View.GONE
            // 绑定开关：根据当前书籍的绑定列表设置状态
            val bound = bindVisible &&
                AiImageGenerator.isBookBoundToTemplate(bookUrl, template.id, template.isDefault)
            // 避免复用触发回调
            binding.switchBind.setOnCheckedChangeListener(null)
            binding.switchBind.isChecked = bound
            binding.switchBind.setOnCheckedChangeListener { _, isChecked ->
                onBindToggle(template, isChecked)
            }

            val selectListener = View.OnClickListener { onSelect(template) }
            binding.root.setOnClickListener(selectListener)
            binding.radioButton.setOnClickListener(selectListener)

            binding.btnDelete.setOnClickListener { onDelete(template) }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<AiImageTemplate>() {
        override fun areItemsTheSame(oldItem: AiImageTemplate, newItem: AiImageTemplate): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: AiImageTemplate, newItem: AiImageTemplate): Boolean {
            return oldItem == newItem
        }
    }
}
