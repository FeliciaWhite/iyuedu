package io.legado.app.help.audiobook.plugin

import android.annotation.SuppressLint
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 插件 UI 文本输入控件。
 * 继承 FrameLayout，内部包含一个标签 TextView 和一个 EditText。
 */
@Suppress("unused", "MemberVisibilityCanBePrivate")
@SuppressLint("ViewConstructor")
class JTextInput @JvmOverloads constructor(
    context: Any,
    val hint: String? = null
) : FrameLayout(context as Context) {

    private val ctx = context as Context

    interface OnTextChangedListener {
        fun onChanged(text: CharSequence)
    }

    private val mLabelView: TextView
    private val mEditText: EditText

    private val listeners = mutableSetOf<OnTextChangedListener>()

    fun addTextChangedListener(listener: OnTextChangedListener) {
        listeners.add(listener)
    }

    fun removeTextChangedListener(listener: OnTextChangedListener) {
        listeners.remove(listener)
    }

    fun setOnTextChangedListener(listener: OnTextChangedListener) {
        listeners.add(listener)
    }

    var text: CharSequence
        get() = mEditText.text?.toString() ?: ""
        set(value) {
            mEditText.setText(value)
        }

    var maxLines: Int
        get() = mEditText.maxLines
        set(value) {
            mEditText.maxLines = value
        }

    init {
        val padding = (8 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        mLabelView = TextView(ctx).apply {
            text = hint ?: ""
            textSize = 13f
            setPadding(0, 0, 0, (4 * resources.displayMetrics.density).toInt())
        }
        mEditText = EditText(ctx).apply {
            hint = this@JTextInput.hint
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        container.addView(mLabelView)
        container.addView(mEditText)
        addView(container)

        mEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString() ?: ""
                if (listeners.isEmpty()) return
                val cx = org.mozilla.javascript.Context.enter() as? com.script.rhino.RhinoContext
                try {
                    cx?.allowScriptRun = true
                    listeners.forEach { listener ->
                        runCatching { listener.onChanged(text) }
                    }
                } finally {
                    cx?.allowScriptRun = false
                    org.mozilla.javascript.Context.exit()
                }
            }
        })
    }
}
