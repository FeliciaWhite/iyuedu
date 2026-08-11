package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiImageTemplate
import io.legado.app.databinding.DialogAiImageTemplateListBinding
import io.legado.app.model.AiImageGenerator
import io.legado.app.model.ReadBook
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * AI 生图模板列表对话框。
 * 单选某个模板用于在设置界面编辑；左下角新建模板；每项右侧可删除（默认模板除外）。
 * 每项右侧带「绑定」开关，可统一管理当前书籍绑定的多个模板。
 */
class AiImageTemplateListDialog : BaseDialogFragment(R.layout.dialog_ai_image_template_list) {

    private val binding by viewBinding(DialogAiImageTemplateListBinding::bind)

    /** 当前正在编辑的模板 id */
    private var currentEditId: Long = 0

    /** 选择模板后的回调 */
    private var onSelectCallback: ((AiImageTemplate) -> Unit)? = null

    /** 绑定状态变化后的回调（用于通知外部刷新） */
    private var onBindChangedCallback: (() -> Unit)? = null

    companion object {
        private const val ARG_CURRENT_ID = "current_id"

        fun newInstance(
            currentEditId: Long,
            onSelect: (AiImageTemplate) -> Unit,
            onBindChanged: () -> Unit = {}
        ): AiImageTemplateListDialog {
            return AiImageTemplateListDialog().apply {
                arguments = Bundle().apply { putLong(ARG_CURRENT_ID, currentEditId) }
                onSelectCallback = onSelect
                onBindChangedCallback = onBindChanged
            }
        }
    }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        currentEditId = arguments?.getLong(ARG_CURRENT_ID, 0) ?: 0

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())

        binding.btnAdd.setOnClickListener {
            showNewTemplateDialog()
        }

        loadTemplates()
    }

    private fun loadTemplates() {
        val bookUrl = ReadBook.book?.bookUrl.orEmpty()
        execute {
            appDb.aiImageTemplateDao.getAllList()
        }.onSuccess(Dispatchers.Main) { list ->
            val adapter = AiImageTemplateListAdapter(
                selectedId = currentEditId,
                bookUrl = bookUrl,
                onSelect = { template ->
                    onSelectCallback?.invoke(template)
                    dismiss()
                },
                onDelete = { template ->
                    confirmDelete(template)
                },
                onBindToggle = { template, isChecked ->
                    if (bookUrl.isBlank()) {
                        toastOnUi("未打开书籍，无法绑定")
                        return@AiImageTemplateListAdapter
                    }
                    if (isChecked) {
                        val ok = AiImageGenerator.addBoundTemplateId(bookUrl, template.id)
                        if (ok) {
                            toastOnUi("已将「${template.name}」加入当前书籍")
                        } else {
                            toastOnUi("每本书最多绑定 ${AiImageGenerator.MAX_BOUND_TEMPLATES} 个模板")
                            loadTemplates()
                            onBindChangedCallback?.invoke()
                            return@AiImageTemplateListAdapter
                        }
                    } else {
                        AiImageGenerator.removeBoundTemplateId(bookUrl, template.id)
                        toastOnUi("已将「${template.name}」从当前书籍移除")
                    }
                    onBindChangedCallback?.invoke()
                }
            )
            binding.recyclerView.adapter = adapter
            adapter.setData(list)
        }
    }

    private fun showNewTemplateDialog() {
        val input = EditText(requireContext()).apply { hint = "模板名称" }
        AlertDialog.Builder(requireContext())
            .setTitle("新建模板")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    toastOnUi("模板名称不能为空")
                    return@setPositiveButton
                }
                execute {
                    val template = AiImageTemplate(
                        name = name,
                        modelUrl = AiImageGenerator.DEFAULT_MODEL_URL,
                        modelName = AiImageGenerator.DEFAULT_MODEL_NAME,
                        imageSize = "784x1168",
                        imageStyle = AiImageGenerator.DEFAULT_STYLE_SUFFIX,
                        promptTemplate = AiImageGenerator.DEFAULT_PROMPT_TEMPLATE,
                        negativePrompt = AiImageGenerator.DEFAULT_NEGATIVE_PROMPT,
                        isDefault = false
                    )
                    appDb.aiImageTemplateDao.insert(template)
                    template
                }.onSuccess(Dispatchers.Main) { newTemplate ->
                    onSelectCallback?.invoke(newTemplate)
                    dismiss()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDelete(template: AiImageTemplate) {
        AlertDialog.Builder(requireContext())
            .setTitle("删除模板")
            .setMessage("确认删除模板「${template.name}」？删除后不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                execute {
                    appDb.aiImageTemplateDao.deleteById(template.id)
                }.onSuccess(Dispatchers.Main) {
                    toastOnUi("已删除模板「${template.name}」")
                    loadTemplates()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
