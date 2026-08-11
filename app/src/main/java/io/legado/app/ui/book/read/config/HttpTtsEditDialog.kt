package io.legado.app.ui.book.read.config

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.entities.HttpTTS
import io.legado.app.databinding.DialogHttpTtsEditBinding
import io.legado.app.help.tts.TtsEngineActivator
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.widget.code.addJsPattern
import io.legado.app.ui.widget.code.addJsonPattern
import io.legado.app.ui.widget.code.addLegadoPattern
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.applyTint
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

private const val TAG = "HttpTtsEditDialog"

class HttpTtsEditDialog() : BaseDialogFragment(R.layout.dialog_http_tts_edit, true),
    Toolbar.OnMenuItemClickListener {

    constructor(id: Long) : this() {
        arguments = Bundle().apply {
            putLong("id", id)
        }
    }

    private val binding by viewBinding(DialogHttpTtsEditBinding::bind)
    private val viewModel by viewModels<HttpTtsEditViewModel>()
    private var focusedEditText: EditText? = null
    
    // TTS 引擎列表
    private var ttsEngineList: List<TtsEngineActivator.TtsEngineInfo> = emptyList()
    // 当前选中的引擎索引
    private var selectedEngineIndex: Int = -1
    // 是否正在从 URL 自动提取包名（避免循环更新）
    private var isAutoExtracting = false

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.tvUrl.run {
            addLegadoPattern()
            addJsonPattern()
            addJsPattern()
        }
        binding.tvLoginUrl.run {
            addLegadoPattern()
            addJsonPattern()
            addJsPattern()
        }
        binding.tvLoginUi.addJsonPattern()
        binding.tvLoginCheckJs.addJsPattern()
        binding.tvHeaders.run {
            addLegadoPattern()
            addJsonPattern()
            addJsPattern()
        }
        binding.tvJsLib.run {
            addLegadoPattern()
            addJsonPattern()
            addJsPattern()
        }
        
        // 加载 TTS 引擎列表
        loadTtsEngines()
        
        // 设置 URL 变化监听，自动提取 TTS 包名
        setupUrlWatcher()
        
        viewModel.initData(arguments) {
            initView(httpTTS = it)
        }
        initMenu()
    }

    /**
     * 加载系统 TTS 引擎列表
     */
    private fun loadTtsEngines() {
        ttsEngineList = TtsEngineActivator.getInstalledTtsEngines(requireContext())
        LogUtils.d(TAG, "加载到 ${ttsEngineList.size} 个 TTS 引擎")
        
        // 构建下拉列表
        val engineLabels = mutableListOf("不指定（自动从URL提取）")
        engineLabels.addAll(ttsEngineList.map { 
            val defaultMark = if (it.isDefault) " [默认]" else ""
            "${it.label}$defaultMark"
        })
        
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            engineLabels
        )
        binding.spTtsEngine.setAdapter(adapter)
        
        // 设置默认选中项
        binding.spTtsEngine.setText(engineLabels.firstOrNull() ?: "", false)
        LogUtils.d(TAG, "默认选中: ${engineLabels.firstOrNull()}")
        
        // 下拉选择监听
        binding.spTtsEngine.setOnItemClickListener { _, _, position, _ ->
            selectedEngineIndex = position - 1 // -1 因为第一个是"不指定"
            if (position == 0) {
                // 用户选择"不指定"，清空包名
                binding.tvTtsPackageName.setText("")
                LogUtils.d(TAG, "用户选择不指定，清空包名")
            } else {
                // 使用选中的引擎包名
                val engine = ttsEngineList.getOrNull(selectedEngineIndex)
                engine?.let {
                    binding.tvTtsPackageName.setText(it.packageName)
                    LogUtils.d(TAG, "用户选择引擎: ${it.label} -> ${it.packageName}")
                }
            }
        }
    }

    /**
     * 设置 URL 变化监听，自动从 URL 提取 TTS 包名
     */
    private fun setupUrlWatcher() {
        binding.tvUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            
            override fun afterTextChanged(s: Editable?) {
                if (isAutoExtracting) return
                
                val url = s?.toString() ?: return
                LogUtils.d(TAG, "URL 变化，检测包名: $url")
                
                val extractedPackage = TtsEngineActivator.extractPackageNameFromUrl(url)
                
                if (extractedPackage != null) {
                    isAutoExtracting = true
                    try {
                        // 自动更新包名
                        binding.tvTtsPackageName.setText(extractedPackage)
                        LogUtils.d(TAG, "从 URL 自动提取 TTS 包名: $extractedPackage")
                        
                        // 自动选中对应的引擎（如果能找到）
                        val engineIndex = ttsEngineList.indexOfFirst { it.packageName == extractedPackage }
                        if (engineIndex >= 0) {
                            selectedEngineIndex = engineIndex
                            val engineLabels = mutableListOf("不指定（自动从URL提取）")
                            engineLabels.addAll(ttsEngineList.map { 
                                val defaultMark = if (it.isDefault) " [默认]" else ""
                                "${it.label}$defaultMark"
                            })
                            binding.spTtsEngine.setText(engineLabels.getOrNull(engineIndex + 1) ?: "", false)
                            LogUtils.d(TAG, "自动选中引擎: ${engineLabels.getOrNull(engineIndex + 1)}")
                        }
                    } finally {
                        isAutoExtracting = false
                    }
                }
            }
        })
    }

    fun initMenu() {
        binding.toolBar.inflateMenu(R.menu.speak_engine_edit)
        binding.toolBar.menu.applyTint(requireContext())
        binding.toolBar.setOnMenuItemClickListener(this)
    }

    fun initView(httpTTS: HttpTTS) {
        binding.tvName.setText(httpTTS.name)
        binding.tvUrl.setText(httpTTS.url)
        binding.tvContentType.setText(httpTTS.contentType)
        binding.tvConcurrentRate.setText(httpTTS.concurrentRate)
        binding.tvLoginUrl.setText(httpTTS.loginUrl)
        binding.tvLoginUi.setText(httpTTS.loginUi)
        binding.tvLoginCheckJs.setText(httpTTS.loginCheckJs)
        binding.tvHeaders.setText(httpTTS.header)
        binding.tvJsLib.setText(httpTTS.jsLib)
        
        // 设置 TTS 包名
        binding.tvTtsPackageName.setText(httpTTS.ttsPackageName)
        LogUtils.d(TAG, "初始化视图，已保存的 TTS 包名: ${httpTTS.ttsPackageName}")
        
        // 如果有包名，自动选中对应的引擎
        val savedPackageName = httpTTS.ttsPackageName
        if (!savedPackageName.isNullOrBlank()) {
            val engineIndex = ttsEngineList.indexOfFirst { it.packageName == savedPackageName }
            if (engineIndex >= 0) {
                selectedEngineIndex = engineIndex
                val engineLabels = mutableListOf("不指定（自动从URL提取）")
                engineLabels.addAll(ttsEngineList.map { 
                    val defaultMark = if (it.isDefault) " [默认]" else ""
                    "${it.label}$defaultMark"
                })
                binding.spTtsEngine.setText(engineLabels.getOrNull(engineIndex + 1) ?: "", false)
                LogUtils.d(TAG, "自动选中已保存的引擎: ${engineLabels.getOrNull(engineIndex + 1)}")
            }
        }
    }


    private val textEditLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.getStringExtra("text")?.let { editedText ->
                focusedEditText?.let { editText ->
                    editText.setText(editedText)
                    editText.setSelection(result.data!!.getIntExtra("cursorPosition", 0))
                    editText.requestFocus()
                } ?: run {
                    toastOnUi(R.string.focus_lost_on_textbox)
                }
            }
        }
    }
    private fun onFullEditClicked() {
        val view = dialog?.window?.decorView?.findFocus()
        if (view is EditText) {
            val hint = findParentTextInputLayout(view)?.hint?.toString()
            focusedEditText = view
            val currentText = view.text.toString()
            val intent = Intent(requireActivity(), CodeEditActivity::class.java).apply {
                putExtra("text", currentText)
                putExtra("title", hint)
                putExtra("cursorPosition", view.selectionStart)
            }
            textEditLauncher.launch(intent)
        }
        else {
            toastOnUi(R.string.please_focus_cursor_on_textbox)
        }
    }
    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_fullscreen_edit -> onFullEditClicked()
            R.id.menu_save -> viewModel.save(dataFromView()) {
                dismissAllowingStateLoss()
                toastOnUi("保存成功")
            }
            R.id.menu_login -> dataFromView().let { httpTts ->
                if (httpTts.loginUrl.isNullOrBlank()) {
                    toastOnUi("登录url不能为空")
                } else {
                    viewModel.save(httpTts) {
                        startActivity<SourceLoginActivity> {
                            putExtra("type", "httpTts")
                            putExtra("key", httpTts.id.toString())
                        }
                    }
                }
            }
            R.id.menu_show_login_header -> alert {
                setTitle(R.string.login_header)
                dataFromView().getLoginHeader()?.let { loginHeader ->
                    setMessage(loginHeader)
                }
            }
            R.id.menu_del_login_header -> dataFromView().removeLoginHeader()
            R.id.menu_copy_source -> dataFromView().let {
                context?.sendToClip(GSON.toJson(it))
            }
            R.id.menu_paste_source -> viewModel.importFromClip {
                initView(it)
            }
            R.id.menu_log -> showDialogFragment<AppLogDialog>()
            R.id.menu_help -> showHelp("httpTTSHelp")
        }
        return true
    }

    private fun dataFromView(): HttpTTS {
        val httpTTS = HttpTTS(
            id = viewModel.id ?: System.currentTimeMillis(),
            name = binding.tvName.text.toString(),
            url = binding.tvUrl.text.toString(),
            contentType = binding.tvContentType.text?.toString(),
            concurrentRate = binding.tvConcurrentRate.text?.toString(),
            loginUrl = binding.tvLoginUrl.text?.toString(),
            loginUi = binding.tvLoginUi.text?.toString(),
            loginCheckJs = binding.tvLoginCheckJs.text?.toString(),
            header = binding.tvHeaders.text?.toString(),
            jsLib = binding.tvJsLib.text?.toString(),
            ttsPackageName = binding.tvTtsPackageName.text?.toString()
        )
        LogUtils.d(TAG, "从视图生成 HttpTTS: ${httpTTS.name}, TTS包名: ${httpTTS.ttsPackageName}")
        return httpTTS
    }

    private fun isSame(): Boolean{
        val httpTTS = viewModel.httpTTS ?: return binding.tvName.text.toString().isEmpty()
        return dataFromView().equal(httpTTS)
    }

    override fun dismiss() {
        if (!isSame()) {
            alert(R.string.exit) {
                setMessage(R.string.exit_no_save)
                positiveButton(R.string.yes)
                negativeButton(R.string.no) {
                    super.dismiss()
                }
            }
        } else {
            super.dismiss()
        }
    }

}