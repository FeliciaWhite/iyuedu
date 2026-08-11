package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.BgmAIPrompt
import io.legado.app.databinding.DialogBgmAiPromptBinding
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BgmAIPromptDialog : BaseDialogFragment(R.layout.dialog_bgm_ai_prompt) {

    private val binding by viewBinding(DialogBgmAiPromptBinding::bind)
    private val adapter = BgmAIPromptAdapter { prompt ->
        editPrompt(prompt)
    }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.btnAdd.setOnClickListener {
            editPrompt(null)
        }

        loadPrompts()
    }

    private fun loadPrompts() {
        execute {
            appDb.bgmAIPromptDao.getAllList()
        }.onSuccess(Dispatchers.Main) {
            adapter.setData(it)
        }
    }

    private fun editPrompt(prompt: BgmAIPrompt?) {
        BgmAIPromptEditDialog.newInstance(prompt) {
            loadPrompts()
        }.show(parentFragmentManager, "editPrompt")
    }
}
