package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.setFragmentResultListener
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.BgmAIPrompt
import io.legado.app.databinding.DialogBgmAiPromptEditBinding
import io.legado.app.service.BgmManager
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BgmAIPromptEditDialog : BaseDialogFragment(R.layout.dialog_bgm_ai_prompt_edit) {

    private val binding by viewBinding(DialogBgmAiPromptEditBinding::bind)
    private var prompt: BgmAIPrompt? = null
    private var onSavedCallback: (() -> Unit)? = null

    companion object {
        private const val ARG_PROMPT_ID = "prompt_id"

        fun newInstance(prompt: BgmAIPrompt?, onSaved: () -> Unit): BgmAIPromptEditDialog {
            return BgmAIPromptEditDialog().apply {
                arguments = Bundle().apply {
                    prompt?.id?.let { putLong(ARG_PROMPT_ID, it) }
                }
                onSavedCallback = onSaved
            }
        }
    }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val promptId = arguments?.getLong(ARG_PROMPT_ID, 0) ?: 0
        if (promptId > 0) {
            execute {
                appDb.bgmAIPromptDao.getById(promptId)
            }.onSuccess(Dispatchers.Main) {
                it?.let { loadedPrompt ->
                    prompt = loadedPrompt
                    binding.etName.setText(loadedPrompt.name)
                    // 显示提示词时，添加音频文件列表预览（仅用于显示，保存时不会保存）
                    val audioFileList = BgmManager.getAudioFileList()
                    val displayPrompt = if (audioFileList.isNotEmpty()) {
                        val listSection = "\n\n可用文件列表（已排除当前播放）：\n${audioFileList.joinToString("、")}"
                        if (!loadedPrompt.prompt.contains("可用文件列表")) {
                            loadedPrompt.prompt + listSection
                        } else {
                            loadedPrompt.prompt
                        }
                    } else {
                        loadedPrompt.prompt
                    }
                    binding.etPrompt.setText(displayPrompt)
                }
            }
        }

        binding.btnCancel.setOnClickListener {
            dismiss()
        }

        binding.btnSave.setOnClickListener {
            savePrompt()
        }
    }

    private fun savePrompt() {
        val name = binding.etName.text?.toString()?.trim() ?: ""
        var promptText = binding.etPrompt.text?.toString()?.trim() ?: ""

        if (name.isEmpty()) {
            binding.etName.error = "标题不能为空"
            return
        }

        if (promptText.isEmpty()) {
            binding.etPrompt.error = "提示词不能为空"
            return
        }

        // 去掉动态添加的音频文件列表部分，只保存提示词主体
        val audioFileListSectionStart = "\n\n可用文件列表（已排除当前播放）：\n"
        val audioFileListSectionIndex = promptText.indexOf(audioFileListSectionStart)
        if (audioFileListSectionIndex >= 0) {
            promptText = promptText.substring(0, audioFileListSectionIndex).trim()
        }

        val newPrompt = BgmAIPrompt(
            id = prompt?.id ?: System.currentTimeMillis(),
            name = name,
            prompt = promptText,
            isDefault = prompt?.isDefault ?: false
        )

        execute {
            // 如果设置为默认，取消其他提示词的默认状态
            if (newPrompt.isDefault) {
                val all = appDb.bgmAIPromptDao.getAllList()
                all.forEach { it.isDefault = false }
                appDb.bgmAIPromptDao.update(*all.toTypedArray())
            }

            appDb.bgmAIPromptDao.insert(newPrompt)
        }.onSuccess(Dispatchers.Main) {
            onSavedCallback?.invoke()
            dismiss()
        }
    }
}
