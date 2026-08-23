package io.legado.app.help.audiobook.plugin

import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

/**
 * 授权版 TextWatcher 包装。
 *
 * 部分 J.TTS 插件会用 `editText.addTextChangedListener(new android.text.TextWatcher({
 *   beforeTextChanged: ..., onTextChanged: ..., afterTextChanged: ...
 * }))` 把 JS 对象注册为文本监听器。
 *
 * 原生 EditText 在输入法 commitText / replaceText 时会经 Android 主线程回调
 * beforeTextChanged / onTextChanged / afterTextChanged，Rhino 通过 InterfaceAdapter
 * 调起 JS 时 RhinoContext.allowScriptRun 为 false，会抛
 * "Not allow run script in unauthorized way"（崩溃栈正指向 beforeTextChanged）。
 *
 * 本类在三个回调内手动 Context.enter() 并允许脚本运行，再调用 JS 对应函数，
 * 与 JRunnable / JSwitch 的授权方式一致。
 */
class JTextWatcher(
    private val jsObj: Scriptable,
) : TextWatcher {

    private fun invoke(name: String, vararg args: Any?) {
        val cx = Context.enter() as? com.script.rhino.RhinoContext
        try {
            cx?.allowScriptRun = true
            val scope = ScriptableObject.getTopLevelScope(jsObj)
            val fn = ScriptableObject.getProperty(jsObj, name)
            if (fn is Function) {
                fn.call(cx, scope, jsObj, args)
            }
        } catch (e: Exception) {
            Log.e("JTextWatcher", "$name callback error: ${e.message}", e)
        } finally {
            cx?.allowScriptRun = false
            Context.exit()
        }
    }

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
        invoke("beforeTextChanged", s, start, count, after)
    }

    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
        invoke("onTextChanged", s, start, before, count)
    }

    override fun afterTextChanged(s: Editable?) {
        invoke("afterTextChanged", s)
    }
}
