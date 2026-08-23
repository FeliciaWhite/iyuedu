package io.legado.app.help.audiobook.plugin

import android.annotation.SuppressLint
import android.content.Context
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/**
 * 插件 UI 开关控件。
 * 继承 FrameLayout，内部包含一个 Switch。
 * 包装 android.widget.Switch，提供简化的 setOnCheckedChangeListener 接口，
 * 避免 Rhino 无法将 JS 箭头函数转换为 CompoundButton.OnCheckedChangeListener 的问题。
 */
@Suppress("unused", "MemberVisibilityCanBePrivate")
@SuppressLint("UseSwitchCompatOrMaterialCode", "ViewConstructor")
class JSwitch @JvmOverloads constructor(
    context: Any,
    val hint: String = ""
) : FrameLayout(context as Context) {

    private val ctx = context as Context

    interface OnCheckedChangeListener {
        fun onCheckedChanged(buttonView: JSwitch, isChecked: Boolean)
    }

    private val mLabelView: TextView
    private val mSwitch: Switch

    var isChecked: Boolean
        get() = mSwitch.isChecked
        set(value) { mSwitch.isChecked = value }

    /**
     * 接受任意类型的监听器。
     * 插件代码可能用箭头函数 (button, checked) => {...} 或
     * new CompoundButton.OnCheckedChangeListener({...}) 创建监听器。
     * Rhino 的 InterfaceAdapter 会创建代理对象，这里统一处理。
     */
    fun setOnCheckedChangeListener(listener: Any) {
        mSwitch.setOnCheckedChangeListener { buttonView, isChecked ->
            val cx = org.mozilla.javascript.Context.enter() as? com.script.rhino.RhinoContext
            try {
                cx?.allowScriptRun = true
                when (listener) {
                    is OnCheckedChangeListener -> listener.onCheckedChanged(this@JSwitch, isChecked)
                    is CompoundButton.OnCheckedChangeListener -> listener.onCheckedChanged(mSwitch, isChecked)
                    else -> {
                        // Rhino InterfaceAdapter 代理对象：通过反射调用 onCheckedChanged
                        try {
                            val method = listener.javaClass.getMethod("onCheckedChanged", CompoundButton::class.java, Boolean::class.javaPrimitiveType)
                            method.invoke(listener, mSwitch, isChecked)
                        } catch (e: Exception) {
                            android.util.Log.w("JSwitch", "setOnCheckedChangeListener: unknown listener type ${listener.javaClass.name}", e)
                        }
                    }
                }
            } finally {
                cx?.allowScriptRun = false
                org.mozilla.javascript.Context.exit()
            }
        }
    }

    fun setText(text: String) {
        mLabelView.text = text
    }

    init {
        val padding = (8 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(padding, padding, padding, padding)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        mLabelView = TextView(ctx).apply {
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        mSwitch = Switch(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        container.addView(mLabelView)
        container.addView(mSwitch)
        addView(container)
    }
}
