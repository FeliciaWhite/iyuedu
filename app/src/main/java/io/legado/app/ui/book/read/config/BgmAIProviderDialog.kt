package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.BgmAIProvider
import io.legado.app.databinding.DialogBgmAiProviderBinding
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BgmAIProviderDialog : BaseDialogFragment(R.layout.dialog_bgm_ai_provider) {

    private val binding by viewBinding(DialogBgmAiProviderBinding::bind)
    private val adapter = BgmAIProviderAdapter { provider ->
        editProvider(provider)
    }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.btnAdd.setOnClickListener {
            editProvider(null)
        }

        loadProviders()
    }

    private fun loadProviders() {
        execute {
            appDb.bgmAIProviderDao.getAllList()
        }.onSuccess(Dispatchers.Main) {
            adapter.setData(it)
        }
    }

    private fun editProvider(provider: BgmAIProvider?) {
        BgmAIProviderEditDialog.newInstance(provider) {
            loadProviders()
        }.show(parentFragmentManager, "editProvider")
    }
}
