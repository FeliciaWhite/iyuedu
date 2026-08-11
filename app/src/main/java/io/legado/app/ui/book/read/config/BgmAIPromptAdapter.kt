package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.data.appDb
import io.legado.app.data.entities.BgmAIPrompt
import io.legado.app.databinding.ItemBgmAiPromptBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BgmAIPromptAdapter(
    private val onEditClick: (BgmAIPrompt) -> Unit
) : ListAdapter<BgmAIPrompt, BgmAIPromptAdapter.ViewHolder>(DiffCallback()) {

    @Volatile
    private var selectedId: Long? = null

    init {
        CoroutineScope(Dispatchers.IO).launch {
            val default = appDb.bgmAIPromptDao.getDefault()
            selectedId = default?.id
            withContext(Dispatchers.Main) {
                notifyDataSetChanged()
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemBgmAiPromptBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    fun setData(newData: List<BgmAIPrompt>) {
        submitList(newData)
    }

    inner class ViewHolder(private val binding: ItemBgmAiPromptBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(prompt: BgmAIPrompt) {
            binding.tvName.text = prompt.name
            binding.tvPrompt.text = prompt.prompt
            binding.radioButton.isChecked = prompt.id == selectedId
            binding.radioButton.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    selectedId = prompt.id
                    CoroutineScope(Dispatchers.IO).launch {
                        // 取消其他提示词的默认状态
                        val all = appDb.bgmAIPromptDao.getAllList()
                        all.forEach {
                            if (it.id != prompt.id) {
                                it.isDefault = false
                            } else {
                                it.isDefault = true
                            }
                        }
                        appDb.bgmAIPromptDao.update(*all.toTypedArray())
                        withContext(Dispatchers.Main) {
                            notifyDataSetChanged()
                            // 重新绑定当前item以更新radioButton状态
                            notifyItemChanged(currentList.indexOf(prompt))
                        }
                    }
                }
            }
            binding.btnEdit.setOnClickListener { onEditClick(prompt) }
            binding.btnDelete.setOnClickListener {
                val wasSelected = prompt.id == selectedId
                CoroutineScope(Dispatchers.IO).launch {
                    if (wasSelected) {
                        selectedId = null
                    }
                    appDb.bgmAIPromptDao.deleteById(prompt.id)
                    val newList = currentList.toMutableList().apply { remove(prompt) }
                    withContext(Dispatchers.Main) {
                        submitList(newList)
                    }
                }
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<BgmAIPrompt>() {
        override fun areItemsTheSame(oldItem: BgmAIPrompt, newItem: BgmAIPrompt): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: BgmAIPrompt, newItem: BgmAIPrompt): Boolean {
            return oldItem == newItem
        }
    }
}
