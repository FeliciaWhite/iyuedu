package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.data.appDb
import io.legado.app.data.entities.BgmAIProvider
import io.legado.app.databinding.ItemBgmAiProviderBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BgmAIProviderAdapter(
    private val onEditClick: (BgmAIProvider) -> Unit
) : ListAdapter<BgmAIProvider, BgmAIProviderAdapter.ViewHolder>(DiffCallback()) {

    private var selectedId: Long? = null

    init {
        CoroutineScope(Dispatchers.IO).launch {
            val enabled = appDb.bgmAIProviderDao.getEnabled()
            selectedId = enabled?.id
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemBgmAiProviderBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    fun setData(newData: List<BgmAIProvider>) {
        submitList(newData)
    }

    inner class ViewHolder(private val binding: ItemBgmAiProviderBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(provider: BgmAIProvider) {
            binding.tvName.text = provider.name
            binding.tvUrl.text = provider.url
            binding.radioButton.isChecked = provider.id == selectedId
            binding.radioButton.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    selectedId = provider.id
                    CoroutineScope(Dispatchers.IO).launch {
                        // 取消其他提供商的启用状态
                        val all = appDb.bgmAIProviderDao.getAllList()
                        all.forEach {
                            if (it.id != provider.id) {
                                it.enabled = false
                            } else {
                                it.enabled = true
                            }
                        }
                        appDb.bgmAIProviderDao.update(*all.toTypedArray())
                        // 在主线程更新 UI
                        CoroutineScope(Dispatchers.Main).launch {
                            notifyDataSetChanged()
                        }
                    }
                }
            }
            binding.btnEdit.setOnClickListener { onEditClick(provider) }
            binding.btnDelete.setOnClickListener {
                CoroutineScope(Dispatchers.IO).launch {
                    if (provider.id == selectedId) {
                        selectedId = null
                    }
                    appDb.bgmAIProviderDao.deleteById(provider.id)
                    val newList = currentList.toMutableList().apply { remove(provider) }
                    submitList(newList)
                }
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<BgmAIProvider>() {
        override fun areItemsTheSame(oldItem: BgmAIProvider, newItem: BgmAIProvider): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: BgmAIProvider, newItem: BgmAIProvider): Boolean {
            return oldItem == newItem
        }
    }
}
