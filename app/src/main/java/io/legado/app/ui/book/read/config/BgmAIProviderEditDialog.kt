package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.BgmAIProvider
import io.legado.app.databinding.DialogBgmAiProviderEditBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.service.BgmAIService
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BgmAIProviderEditDialog : BaseDialogFragment(R.layout.dialog_bgm_ai_provider_edit) {

    private val binding by viewBinding(DialogBgmAiProviderEditBinding::bind)
    private var provider: BgmAIProvider? = null
    private var onSavedCallback: (() -> Unit)? = null

    companion object {
        private const val ARG_PROVIDER_ID = "provider_id"

        fun newInstance(provider: BgmAIProvider?, onSaved: () -> Unit): BgmAIProviderEditDialog {
            return BgmAIProviderEditDialog().apply {
                arguments = Bundle().apply {
                    provider?.id?.let { putLong(ARG_PROVIDER_ID, it) }
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
        val providerId = arguments?.getLong(ARG_PROVIDER_ID, 0) ?: 0
        if (providerId > 0) {
            execute {
                appDb.bgmAIProviderDao.getById(providerId)
            }.onSuccess(Dispatchers.Main) {
                it?.let { loadedProvider ->
                    provider = loadedProvider
                    binding.etName.setText(loadedProvider.name)
                    binding.etConfig.setText(
                        "${loadedProvider.url}@@${loadedProvider.modelId}@@${loadedProvider.apiKey}"
                    )
                }
            }
        }

        binding.btnCancel.setOnClickListener {
            dismiss()
        }

        binding.btnSave.setOnClickListener {
            saveProvider()
        }

        binding.btnTest.setOnClickListener {
            testProvider()
        }
    }

    private fun parseConfig(): Triple<String, String, String>? {
        val config = binding.etConfig.text?.toString()?.trim() ?: ""
        val parts = config.split("@@")
        if (parts.size < 2) {
            binding.etConfig.error = "格式错误，应为：接口地址@@模型名字@@密钥"
            return null
        }
        val url = parseBaseUrl(parts[0].trim())
        val modelId = parts[1].trim()
        val apiKey = if (parts.size >= 3) parts[2].trim() else ""
        return Triple(url, modelId, apiKey)
    }

    private fun parseBaseUrl(url: String): String {
        return when {
            url.endsWith("/chat/completions") -> url.removeSuffix("/chat/completions").removeSuffix("/")
            url.endsWith("/") -> url.removeSuffix("/")
            else -> url
        }
    }

    private fun testProvider() {
        val name = binding.etName.text?.toString()?.trim() ?: ""
        val (url, modelId, apiKey) = parseConfig() ?: return

        if (url.isEmpty()) {
            binding.etConfig.error = "接口地址不能为空"
            return
        }

        if (apiKey.isEmpty()) {
            binding.etConfig.error = "API密钥不能为空"
            return
        }

        val testProvider = BgmAIProvider(
            id = System.currentTimeMillis(),
            name = name.ifEmpty { "测试" },
            url = url,
            apiKey = apiKey,
            modelId = modelId,
            enabled = false
        )

        binding.btnTest.isEnabled = false
        binding.btnTest.text = "测试中..."

        execute {
            BgmAIService.testProvider(testProvider)
        }.onSuccess(Dispatchers.Main) { success ->
            binding.btnTest.isEnabled = true
            binding.btnTest.text = "测试连接"

            if (success) {
                alert("测试成功", "AI 提供商配置正确，可以正常使用") {
                    okButton()
                }
            } else {
                alert("测试失败", "无法连接到 AI 提供商，请检查配置") {
                    okButton()
                }
            }
        }.onError(Dispatchers.Main) { e ->
            binding.btnTest.isEnabled = true
            binding.btnTest.text = "测试连接"
            alert("测试失败", "连接错误：${e.localizedMessage}") {
                okButton()
            }
        }
    }

    private fun saveProvider() {
        val name = binding.etName.text?.toString()?.trim() ?: ""
        val (url, modelId, apiKey) = parseConfig() ?: return

        if (name.isEmpty()) {
            binding.etName.error = "标题不能为空"
            return
        }

        if (url.isEmpty()) {
            binding.etConfig.error = "接口地址不能为空"
            return
        }

        val newProvider = BgmAIProvider(
            id = provider?.id ?: System.currentTimeMillis(),
            name = name,
            url = url,
            apiKey = apiKey,
            modelId = modelId,
            enabled = provider?.enabled ?: false
        )

        execute {
            appDb.bgmAIProviderDao.insert(newProvider)
        }.onSuccess(Dispatchers.Main) {
            onSavedCallback?.invoke()
            dismiss()
        }
    }
}
