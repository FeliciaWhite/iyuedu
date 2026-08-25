package io.legado.app.help.audiobook

import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.widget.LinearLayout
import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.help.audiobook.JReadVoiceEngine.VoicePlugin
import io.legado.app.help.audiobook.JReadVoicePluginRuntime.WebsocketFactory
import io.legado.app.help.audiobook.JReadVoicePluginRuntime.VoiceOption
import io.legado.app.help.audiobook.JReadVoicePluginRuntime.LocaleOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

/**
 * 持久化的插件编辑器会话。
 *
 * 与 TTS Server 的 PluginTtsViewModel 对应：保持同一个 JS scope，
 * 使 onLoadUI 创建的 View 和 onVoiceChanged 操作的 View 是同一组对象。
 *
 * 生命周期：
 * 1. [init] — 创建 scope，eval 插件代码，调用 onLoadData + onLoadUI，返回容器
 * 2. [onVoiceChanged] — 在同一 scope 中调用 onVoiceChanged，插件闭包直接操作已渲染的 View
 * 3. [listVoices] / [listLocales] — 在同一 scope 中查询
 * 4. [close] — 释放资源
 */
class PluginEditorSession(
    private val context: Context,
    private val plugin: VoicePlugin,
    private val dataMap: MutableMap<String, String>,
) {
    companion object {
        private const val TAG = "PluginEditorSession"
    }

    private var scope: Scriptable? = null
    private var runtime: JReadVoicePluginRuntime.RuntimeBridge? = null
    private var uiObjectName: String = ""
    private var container: LinearLayout? = null

    val isInitialized: Boolean get() = scope != null && container != null

    /**
     * 初始化会话：创建 scope，eval 插件代码，调用 onLoadData + onLoadUI。
     * 必须在 IO 线程调用（onLoadData 可能含网络请求），onLoadUI 内部会切到主线程。
     * 返回容器 LinearLayout，null 表示初始化失败。
     */
    suspend fun init(): LinearLayout? {
        if (plugin.code.isBlank()) return null
        return runCatching {
            val dataJson = JSONObject(dataMap as Map<*, *>).toString()
            val rt = JReadVoicePluginRuntime.RuntimeBridge(
                context = context,
                plugin = plugin,
                config = JReadVoiceEngine.VoiceConfig(
                    voiceTag = "",
                    dataJson = dataJson,
                ),
                pointer = JSONObject(),
                externalDataMap = dataMap,
            )
            val bindings = ScriptBindings().apply {
                put("ttsrv", rt)
                put("fs", rt.fs)
                put("http", rt.http)
            }
            val sc = RhinoScriptEngine.getRuntimeScope(bindings)
            JReadVoicePluginRuntime.installPluginGlobals(sc, WebsocketFactory(context))
            JReadVoicePluginRuntime.installPluginCompatShims(sc, context)
            RhinoScriptEngine.eval(JReadVoicePluginRuntime.preparePluginCode(plugin.code), sc)
            JReadVoicePluginRuntime.installPluginCompatShims(sc, context)

            val uiName = when {
                ScriptableObject.getProperty(sc, "EditorJS") is ScriptableObject -> "EditorJS"
                ScriptableObject.getProperty(sc, "PluginJS") is ScriptableObject -> "PluginJS"
                else -> ""
            }
            if (uiName.isBlank()) {
                // 插件既没有 EditorJS 也没有 PluginJS：明确抛错，让上层弹窗报错，
                // 而不是静默返回 null（否则既不加载出来也不报错）。
                throw IllegalStateException("插件 ${plugin.name} 缺少 EditorJS/PluginJS 对象，无法初始化编辑器")
            }

            // onLoadData
            val onLoadDataResult = runCatching {
                RhinoScriptEngine.eval(
                    "if (typeof $uiName.onLoadData === 'function') { $uiName.onLoadData(); }",
                    sc
                )
            }
            onLoadDataResult.onFailure {
                Log.w(TAG, "init onLoadData failed: plugin=${plugin.name}", it)
            }

            // onLoadUI — 主线程
            val cont = JReadVoicePluginRuntime.onLoadUI(sc, uiName, context)
            if (cont == null) {
                // onLoadUI 渲染失败：抛出错误让上层弹窗，而不是静默返回 null
                throw IllegalStateException("插件 ${plugin.name} 渲染 UI(onLoadUI)失败，请检查插件代码")
            }

            scope = sc
            runtime = rt
            uiObjectName = uiName
            container = cont
            Log.d(TAG, "init ok: plugin=${plugin.name}, childCount=${cont.childCount}")
            cont
        }.getOrElse {
            Log.e(TAG, "init failed: plugin=${plugin.name}", it)
            // 把真正的初始化异常抛给上层，触发弹窗报错
            throw it
        }
    }

    /**
     * 在同一 scope 中调用 onVoiceChanged。
     * 必须在主线程执行，因为插件 JS 代码会操作 View 的 visibility 等属性。
     */
    suspend fun onVoiceChanged(locale: String, voice: String) {
        val sc = scope ?: return
        val uiName = uiObjectName
        withContext(Dispatchers.Main) {
            runCatching {
                ScriptableObject.putProperty(sc, "__jreadVoiceLocale", locale.ifBlank { "zh-CN" })
                ScriptableObject.putProperty(sc, "__jreadVoiceId", voice)
                RhinoScriptEngine.eval(
                    """
                    if (typeof $uiName !== 'undefined') {
                        if (typeof $uiName.getVoices === 'function') {
                            $uiName.getVoices(String(__jreadVoiceLocale));
                        }
                        if (typeof $uiName.onVoiceChanged === 'function') {
                            $uiName.onVoiceChanged(String(__jreadVoiceLocale), String(__jreadVoiceId));
                        }
                    }
                    """.trimIndent(),
                    sc
                )
            }.onFailure {
                Log.w(TAG, "onVoiceChanged failed: plugin=${plugin.name}", it)
            }
        }
    }

    /**
     * 在同一 scope 中调用 onListVoices。
     */
    fun listVoices(locale: String): List<VoiceOption> {
        val sc = scope ?: return emptyList()
        val uiName = uiObjectName
        val result = runCatching {
            JReadVoicePluginRuntime.invokeListVoices(sc, uiName, locale)
        }.getOrElse {
            Log.w(TAG, "listVoices failed: plugin=${plugin.name}", it)
            emptyList()
        }
        Log.d(TAG, "listVoices: plugin=${plugin.name}, locale=$locale, count=${result.size}")
        return result
    }

    /**
     * 在同一 scope 中调用 onListLocales。
     */
    fun listLocales(): List<LocaleOption> {
        val sc = scope ?: return emptyList()
        val uiName = uiObjectName
        val result = runCatching {
            JReadVoicePluginRuntime.invokeListLocales(sc, uiName)
        }.getOrElse {
            Log.w(TAG, "listLocales failed: plugin=${plugin.name}", it)
            emptyList()
        }
        Log.d(TAG, "listLocales: plugin=${plugin.name}, count=${result.size}")
        return result
    }

    fun close() {
        scope = null
        runtime = null
        container = null
    }
}
