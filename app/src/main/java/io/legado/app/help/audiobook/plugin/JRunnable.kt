package io.legado.app.help.audiobook.plugin

import android.util.Log
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

/**
 * 授权版 Runnable 包装。
 *
 * 部分 J.TTS 插件会用 `view.post(new java.lang.Runnable({ run: function() {...} }))`
 * 把 JS 函数交给 Android 主线程执行。原生 Runnable 通过 Rhino InterfaceAdapter
 * 调起 JS 时，RhinoContext.allowScriptRun 为 false，会抛
 * "Not allow run script in unauthorized way"。
 *
 * 本类在 run() 内手动 Context.enter() 并允许脚本运行，再调用 JS 回调，
 * 与 JSwitch/JSpinner 的授权方式一致。
 */
class JRunnable(
    private val jsObj: Scriptable,
) : Runnable {

    override fun run() {
        val cx = Context.enter() as? com.script.rhino.RhinoContext
        try {
            cx?.allowScriptRun = true
            val scope = ScriptableObject.getTopLevelScope(jsObj)
            val runFn = ScriptableObject.getProperty(jsObj, "run")
            if (runFn is Function) {
                runFn.call(cx, scope, jsObj, emptyArray())
            }
        } catch (e: Exception) {
            Log.e("JRunnable", "run callback error: ${e.message}", e)
        } finally {
            cx?.allowScriptRun = false
            Context.exit()
        }
    }
}
