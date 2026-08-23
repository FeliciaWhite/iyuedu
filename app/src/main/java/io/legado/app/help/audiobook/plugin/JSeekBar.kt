package io.legado.app.help.audiobook.plugin

import android.annotation.SuppressLint
import android.content.Context
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 插件 UI 滑块控件。
 * 继承 FrameLayout，内部包含一个标签 TextView 和一个 SeekBar。
 * 支持 setFloatType 实现小数精度。
 */
@Suppress("unused", "MemberVisibilityCanBePrivate")
@SuppressLint("ViewConstructor")
class JSeekBar @JvmOverloads constructor(
    context: Any,
    val hint: String = ""
) : FrameLayout(context as Context) {

    private val ctx = context as Context

    interface OnSeekBarChangeListener {
        fun onStartTrackingTouch(seekBar: JSeekBar)
        fun onProgressChanged(seekBar: JSeekBar, progress: Int, fromUser: Boolean)
        fun onStopTrackingTouch(seekBar: JSeekBar)
    }

    private var mListener: OnSeekBarChangeListener? = null

    fun setOnChangeListener(listener: OnSeekBarChangeListener?) {
        mListener = listener
    }

    private val mLabelView: TextView
    private val mSeekBar: SeekBar

    var max = 0
        set(value) {
            field = value
            mSeekBar.max = value
        }

    private var mFloatDigits = 0
    private var mScale = 1f

    fun setFloatType(n: Int) {
        mFloatDigits = n
        mScale = 1f
        repeat(n) { mScale *= 10f }
    }

    var value: Float
        get() = mSeekBar.progress / mScale
        set(value) {
            mSeekBar.progress = (value * mScale).roundToInt()
        }

    init {
        val padding = (8 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        mLabelView = TextView(ctx).apply {
            text = hint
            textSize = 13f
            setPadding(0, 0, 0, (4 * resources.displayMetrics.density).toInt())
        }
        mSeekBar = SeekBar(ctx).apply {
            max = 0
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        container.addView(mLabelView)
        container.addView(mSeekBar)
        addView(container)

        mSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val listener = mListener ?: return
                val cx = org.mozilla.javascript.Context.enter() as? com.script.rhino.RhinoContext
                try {
                    cx?.allowScriptRun = true
                    listener.onProgressChanged(this@JSeekBar, progress, fromUser)
                } finally {
                    cx?.allowScriptRun = false
                    org.mozilla.javascript.Context.exit()
                }
                updateLabel()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                val listener = mListener ?: return
                val cx = org.mozilla.javascript.Context.enter() as? com.script.rhino.RhinoContext
                try {
                    cx?.allowScriptRun = true
                    listener.onStartTrackingTouch(this@JSeekBar)
                } finally {
                    cx?.allowScriptRun = false
                    org.mozilla.javascript.Context.exit()
                }
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val listener = mListener ?: return
                val cx = org.mozilla.javascript.Context.enter() as? com.script.rhino.RhinoContext
                try {
                    cx?.allowScriptRun = true
                    listener.onStopTrackingTouch(this@JSeekBar)
                } finally {
                    cx?.allowScriptRun = false
                    org.mozilla.javascript.Context.exit()
                }
            }
        })
    }

    private fun updateLabel() {
        val displayValue = if (mFloatDigits > 0) {
            val factor = 10f.pow(mFloatDigits)
            (mSeekBar.progress / factor).toString()
        } else {
            mSeekBar.progress.toString()
        }
        mLabelView.text = "$hint: $displayValue"
    }
}
