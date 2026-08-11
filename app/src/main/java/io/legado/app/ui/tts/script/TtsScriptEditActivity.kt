package io.legado.app.ui.tts.script

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.data.entities.TtsScript
import io.legado.app.databinding.ActivityTtsScriptEditBinding
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.widget.keyboard.KeyboardToolPop
import io.legado.app.utils.GSON
import io.legado.app.utils.imeHeight
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.showHelp
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

class TtsScriptEditActivity :
    VMBaseActivity<ActivityTtsScriptEditBinding, TtsScriptEditViewModel>(),
    KeyboardToolPop.CallBack {

    companion object {
        fun startIntent(context: Context, id: Long = -1): Intent {
            return Intent(context, TtsScriptEditActivity::class.java).apply {
                putExtra("id", id)
            }
        }
    }

    override val binding by viewBinding(ActivityTtsScriptEditBinding::inflate)
    override val viewModel by viewModels<TtsScriptEditViewModel>()

    private val softKeyboardTool by lazy {
        KeyboardToolPop(this, lifecycleScope, binding.root, this)
    }

    private val textEditLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val editedText = result.data?.getStringExtra("text")
            editedText?.let {
                val view = window.decorView.findFocus()
                if (view is EditText) {
                    view.setText(it)
                    view.setSelection(result.data!!.getIntExtra("cursorPosition", 0))
                } else {
                    toastOnUi(R.string.focus_lost_on_textbox)
                }
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        softKeyboardTool.attachToWindow(window)
        initView()
        viewModel.initData(intent) {
            upScriptView(it)
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.tts_script_edit, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    private fun onFullEditClicked() {
        val view = window.decorView.findFocus()
        if (view is EditText) {
            val intent = Intent(this, CodeEditActivity::class.java).apply {
                putExtra("text", view.text.toString())
                putExtra("title", getString(R.string.tts_script_code))
                putExtra("cursorPosition", view.selectionStart)
            }
            textEditLauncher.launch(intent)
        } else {
            toastOnUi(R.string.please_focus_cursor_on_textbox)
        }
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_fullscreen_edit -> onFullEditClicked()
            R.id.menu_save -> viewModel.save(getTtsScript()) {
                setResult(RESULT_OK)
                finish()
            }
            R.id.menu_copy_rule -> sendToClip(GSON.toJson(getTtsScript()))
            R.id.menu_paste_rule -> viewModel.pasteScript {
                upScriptView(it)
            }
        }
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        softKeyboardTool.dismiss()
    }

    private fun initView() {
        binding.root.setOnApplyWindowInsetsListenerCompat { _, windowInsets ->
            softKeyboardTool.initialPadding = windowInsets.imeHeight
            windowInsets
        }
    }

    private fun upScriptView(script: TtsScript) = binding.run {
        etName.setText(script.name)
        etCode.setText(script.code)
    }

    private fun getTtsScript(): TtsScript = binding.run {
        val script: TtsScript = viewModel.ttsScript ?: TtsScript()
        script.name = etName.text.toString()
        script.code = etCode.text.toString()
        return script
    }

    override fun helpActions(): List<SelectItem<String>> {
        return arrayListOf(
            SelectItem("JS教程", "jsHelp")
        )
    }

    override fun onHelpActionSelect(action: String) {
        when (action) {
            "jsHelp" -> showHelp("jsHelp")
        }
    }

    override fun sendText(text: String) {
        if (text.isEmpty()) return
        val view = window?.decorView?.findFocus()
        if (view is EditText) {
            var start = view.selectionStart
            var end = view.selectionEnd
            if (start > end) {
                val temp = start
                start = end
                end = temp
            }
            val edit = view.editableText
            if (start < 0 || start >= edit.length) {
                edit.append(text)
            } else {
                edit.replace(start, end, text)
            }
        }
    }

    override fun onUndoClicked() {
        val editText = window.decorView.findFocus()
        if (editText is EditText) {
            editText.onTextContextMenuItem(android.R.id.undo)
        }
    }

    override fun onRedoClicked() {
        val editText = window.decorView.findFocus()
        if (editText is EditText) {
            editText.onTextContextMenuItem(android.R.id.redo)
        }
    }
}
