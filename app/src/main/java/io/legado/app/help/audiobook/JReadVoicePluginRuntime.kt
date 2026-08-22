package io.legado.app.help.audiobook

import android.content.Context
import android.util.Log
import android.webkit.WebSettings
import androidx.annotation.Keep
import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.help.crypto.SymmetricCryptoAndroid
import io.legado.app.help.http.okHttpClient
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeJavaArray
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Wrapper
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.typedarrays.NativeArrayBuffer
import org.mozilla.javascript.typedarrays.NativeTypedArrayView
import org.mozilla.javascript.typedarrays.NativeUint8Array
import io.legado.app.utils.MD5Utils
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.URLConnection
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.util.zip.ZipInputStream

object JReadVoicePluginRuntime {
    private const val TAG = "JReadVoiceRuntime"
    private const val DEFAULT_TTS_HTTP_TIMEOUT_MS = 300_000L
    private const val BUILTIN_AUDIOS_EFFECT_MAP_ASSET = "defaultData/jreadVoice/audios_effect_map_1064.json"
    private const val BUILTIN_AUDIOS_EFFECT_ZIP_ASSET = "defaultData/jreadVoice/audios_effects_1064.zip"
    private val builtInAudiosEffectMapCache = ConcurrentHashMap<String, String>()
    private val websocketClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20_000L, TimeUnit.MILLISECONDS)
            .readTimeout(0L, TimeUnit.MILLISECONDS)
            .writeTimeout(20_000L, TimeUnit.MILLISECONDS)
            .pingInterval(20_000L, TimeUnit.MILLISECONDS)
            .build()
    }

    data class VoiceOption(
        val id: String,
        val name: String,
        val icon: String = "",
    )

    data class LocaleOption(
        val id: String,
        val name: String,
    )

    data class PluginPresetOption(
        val name: String,
        val value: String,
        val contextText: String = "",
    )

    fun synthesize(
        context: Context,
        plugin: JReadVoiceEngine.VoicePlugin,
        config: JReadVoiceEngine.VoiceConfig,
        text: String,
        voiceTag: String,
        pointer: JSONObject,
    ): ByteArray {
        Log.i(TAG, "Synthesize start: plugin=${plugin.name}, tag=$voiceTag, voice=${config.voice}, textLen=${text.length}")
        val runtime = RuntimeBridge(context, plugin, config, pointer)
        runtime.localSoundFastPath(text)?.let { bytes ->
            Log.i(TAG, "Synthesize done: bytes=${bytes.size}, plugin=${plugin.name}, tag=$voiceTag, fastPath=localSound")
            return bytes
        }
        val websocketFactory = WebsocketFactory(context)
        val bindings = ScriptBindings().apply {
            put("ttsrv", runtime)
            put("fs", runtime.fs)
            put("http", runtime.http)
        }
        val scope = RhinoScriptEngine.getRuntimeScope(bindings)
        installPluginGlobals(scope, websocketFactory)
        installPluginCompatShims(scope)
        RhinoScriptEngine.eval(preparePluginCode(plugin.code), scope)
        installPluginCompatShims(scope)
        if (ScriptableObject.getProperty(scope, "PluginJS") !is ScriptableObject) {
            error("J.TTS 插件缺少 PluginJS 对象")
        }

        runCatching {
            RhinoScriptEngine.eval(
                "if (typeof PluginJS.onLoad === 'function') PluginJS.onLoad();",
                scope
            )
        }
        ensurePresetContextTexts(scope, runtime)

        val locale = config.locale.ifBlank { "zh-CN" }
        val voice = config.voice.ifBlank { voiceTag }
        ScriptableObject.putProperty(scope, "__jreadText", text)
        ScriptableObject.putProperty(scope, "__jreadLocale", locale)
        ScriptableObject.putProperty(scope, "__jreadVoice", voice)
        ScriptableObject.putProperty(scope, "__jreadRate", (config.speed * 50f).toInt())
        ScriptableObject.putProperty(scope, "__jreadVolume", (config.volume * 50f).toInt())
        ScriptableObject.putProperty(scope, "__jreadPitch", (config.pitch * 50f).toInt())
        val result = runCatching {
            RhinoScriptEngine.eval(
                """
                if (typeof PluginJS.getAudio !== 'function') {
                    throw new Error('__JREAD_NO_GET_AUDIO__');
                }
                PluginJS.getAudio(
                    String(__jreadText),
                    String(__jreadLocale),
                    String(__jreadVoice),
                    __jreadRate,
                    __jreadVolume,
                    __jreadPitch
                );
                """.trimIndent(),
                scope
            )
        }.getOrElse { error ->
            if (error.message?.contains("__JREAD_NO_GET_AUDIO__") == true) {
                val callback = AudioCallback()
                val configData = runtime.tts.data.toMutableMap()
                val request = linkedMapOf<String, Any>(
                    "text" to text,
                    "locale" to locale,
                    "voice" to voice,
                    "rate" to (config.speed * 50f).toInt(),
                    "speed" to (config.speed * 50f).toInt(),
                    "volume" to (config.volume * 50f).toInt(),
                    "pitch" to (config.pitch * 50f).toInt(),
                    "data" to configData,
                )
                configData.forEach { (key, value) ->
                    if (!request.containsKey(key)) request[key] = value
                }
                Log.i(
                    TAG,
                    "Synthesize getAudioV2 data: plugin=${plugin.name}, tag=$voiceTag, " +
                        "contextLen=${configData["contextTexts"]?.length ?: 0}, " +
                        "preset=${configData["clonePresetIndex"].orEmpty()}"
                )
                ScriptableObject.putProperty(scope, "__jreadRequest", request)
                ScriptableObject.putProperty(scope, "__jreadCallback", callback)
                RhinoScriptEngine.eval(
                    """
                    if (typeof PluginJS.getAudioV2 !== 'function') {
                        throw new Error('J.TTS 插件缺少 getAudio/getAudioV2');
                    }
                    PluginJS.getAudioV2(__jreadRequest, __jreadCallback);
                    """.trimIndent(),
                    scope
                ) ?: waitForCallbackAudio(scope, websocketFactory, callback)
            } else {
                throw error
            }
        }

        return resultToBytes(result)
            ?.also { Log.i(TAG, "Synthesize done: bytes=${it.size}, plugin=${plugin.name}, tag=$voiceTag") }
            ?: error("J.TTS 插件没有返回音频")
    }

    private fun ensurePresetContextTexts(
        scope: Scriptable,
        runtime: RuntimeBridge,
    ) {
        val data = runtime.tts.data
        val presetIndex = data["clonePresetIndex"].orEmpty().trim().toIntOrNull() ?: 0
        if (presetIndex <= 0) {
            if (data["contextTexts"].isNullOrBlank()) {
                data["manualContextTexts"]?.takeIf { it.isNotBlank() }?.let { data["contextTexts"] = it }
            }
            return
        }
        if (!data["contextTexts"].isNullOrBlank()) return
        val options = readRulePresetOptionsFromScope(scope)
        Log.i(TAG, "Synthesize preset lookup: preset=$presetIndex options=${options.size}")
        val option = options
            .firstOrNull { it.value == presetIndex.toString() }
            ?: return
        if (option.contextText.isNotBlank()) {
            data["contextTexts"] = option.contextText
            data["clonePresetName"] = option.name
            Log.i(
                TAG,
                "Synthesize preset context restored: preset=${option.name}, contextLen=${option.contextText.length}"
            )
        }
    }

    fun listVoices(
        context: Context,
        plugin: JReadVoiceEngine.VoicePlugin,
        locale: String = "zh-CN",
    ): List<VoiceOption> {
        if (plugin.code.isBlank()) return emptyList()
        if (plugin.isBuiltInAudios1064Plugin()) {
            val options = builtInAudios1064VoiceOptions(context)
            Log.i(TAG, "listVoices built-in Audios 1064: count=${options.size}")
            return options
        }
        val runtime = RuntimeBridge(
            context = context,
            plugin = plugin,
            config = JReadVoiceEngine.VoiceConfig(voiceTag = "", locale = locale),
            pointer = JSONObject(),
        )
        val bindings = ScriptBindings().apply {
            put("ttsrv", runtime)
            put("fs", runtime.fs)
            put("http", runtime.http)
        }
        val scope = RhinoScriptEngine.getRuntimeScope(bindings)
        installPluginGlobals(scope, WebsocketFactory(context))
        installPluginCompatShims(scope)
        RhinoScriptEngine.eval(preparePluginCode(plugin.code), scope)
        installPluginCompatShims(scope)
        val uiObjectName = when {
            ScriptableObject.getProperty(scope, "EditorJS") is ScriptableObject -> "EditorJS"
            ScriptableObject.getProperty(scope, "PluginJS") is ScriptableObject -> "PluginJS"
            else -> error("J.TTS 插件缺少 EditorJS/PluginJS 对象")
        }
        runCatching {
            loadEditorRuntime(scope, uiObjectName)
        }.onFailure {
            Log.w(TAG, "listVoices editor bootstrap failed: plugin=${plugin.name}", it)
        }
        val candidateLocales = linkedSetOf(locale.ifBlank { "zh-CN" }, "zh-CN").apply {
            addAll(readPluginLocaleOptionsFromScope(scope, uiObjectName).map { it.id })
        }.filter { it.isMeaningfulLocaleId() }
        var firstError: Throwable? = null
        var lastResult: Any? = null
        for (candidateLocale in candidateLocales) {
            val result = runCatching {
                evalPluginVoices(scope, uiObjectName, candidateLocale)
            }.getOrElse {
                if (it.message?.contains("插件没有提供音色列表") == true) throw it
                if (firstError == null) firstError = it
                Log.w(TAG, "listVoices candidate failed: plugin=${plugin.name}, locale=$candidateLocale", it)
                null
            }
            lastResult = result
            val options = voicesResultToOptions(result)
            if (options.isNotEmpty()) {
                Log.i(TAG, "listVoices done: plugin=${plugin.name}, locale=$candidateLocale, count=${options.size}")
                return options
            }
        }
        val fallbackOptions = microsoftVoiceOptionsFromCache(runtime, locale)
        if (fallbackOptions.isNotEmpty()) {
            Log.i(TAG, "listVoices fallback done: plugin=${plugin.name}, locale=$locale, count=${fallbackOptions.size}")
            return fallbackOptions
        }
        firstError?.let { Log.w(TAG, "listVoices empty after candidate errors: plugin=${plugin.name}", it) }
        Log.w(TAG, "listVoices empty: plugin=${plugin.name}, locale=$locale, result=${describeJsResult(lastResult)}")
        return emptyList()
    }

    fun listLocales(
        context: Context,
        plugin: JReadVoiceEngine.VoicePlugin,
    ): List<LocaleOption> {
        if (plugin.code.isBlank()) return emptyList()
        if (plugin.isBuiltInAudios1064Plugin()) {
            return listOf(LocaleOption(id = "zh-CN", name = "内置音效"))
        }
        val runtime = RuntimeBridge(
            context = context,
            plugin = plugin,
            config = JReadVoiceEngine.VoiceConfig(voiceTag = ""),
            pointer = JSONObject(),
        )
        val bindings = ScriptBindings().apply {
            put("ttsrv", runtime)
            put("fs", runtime.fs)
            put("http", runtime.http)
        }
        val scope = RhinoScriptEngine.getRuntimeScope(bindings)
        installPluginGlobals(scope, WebsocketFactory(context))
        installPluginCompatShims(scope)
        RhinoScriptEngine.eval(preparePluginCode(plugin.code), scope)
        installPluginCompatShims(scope)
        val uiObjectName = when {
            ScriptableObject.getProperty(scope, "EditorJS") is ScriptableObject -> "EditorJS"
            ScriptableObject.getProperty(scope, "PluginJS") is ScriptableObject -> "PluginJS"
            else -> return emptyList()
        }
        runCatching {
            loadEditorRuntime(scope, uiObjectName)
        }.onFailure {
            Log.w(TAG, "listLocales editor bootstrap failed: plugin=${plugin.name}", it)
        }
        val result = runCatching {
            evalPluginLocales(scope, uiObjectName)
        }.getOrElse {
            Log.w(TAG, "listLocales getLocales failed: plugin=${plugin.name}", it)
            null
        }
        val options = localesResultToOptions(result)
        if (options.isNotEmpty()) {
            Log.i(TAG, "listLocales done: plugin=${plugin.name}, count=${options.size}")
            return options
        }
        val fallbackOptions = microsoftLocaleOptionsFromCache(runtime)
        if (fallbackOptions.isNotEmpty()) {
            Log.i(TAG, "listLocales fallback done: plugin=${plugin.name}, count=${fallbackOptions.size}")
            return fallbackOptions
        }
        Log.w(TAG, "listLocales empty: plugin=${plugin.name}, result=${describeJsResult(result)}")
        return emptyList()
    }

    private fun JReadVoiceEngine.VoicePlugin.isBuiltInAudios1064Plugin(): Boolean {
        return pluginId.equals("jread.audios.1064", ignoreCase = true) ||
                id.equals("jread.audios.1064", ignoreCase = true) ||
                id.equals("builtin-jread-audios-1064", ignoreCase = true)
    }

    private fun builtInAudios1064VoiceOptions(context: Context): List<VoiceOption> {
        return runCatching {
            context.assets.open(BUILTIN_AUDIOS_EFFECT_MAP_ASSET)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        }.mapCatching { raw ->
            val root = JSONObject(raw)
            val items = root.optJSONArray("items") ?: JSONArray()
            val seen = linkedSetOf<String>()
            val options = mutableListOf<VoiceOption>()
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val effect = item.optString("effect").trim()
                if (effect.isBlank() || !seen.add(effect)) continue
                val speakerName = item.optString("speakerName").trim()
                val roleName = item.optString("roleName").trim()
                val name = listOf(speakerName, roleName)
                    .firstOrNull { it.isNotBlank() }
                    ?.let { "$effect · $it" }
                    ?: effect
                options += VoiceOption(
                    id = effect,
                    name = name,
                )
            }
            options.sortedWith(compareBy<VoiceOption> { it.id.length }.thenBy { it.id })
        }.getOrElse {
            Log.w(TAG, "Load built-in Audios 1064 voices failed", it)
            emptyList()
        }
    }

    private fun evalPluginVoices(
        scope: Scriptable,
        uiObjectName: String,
        locale: String,
    ): Any? {
        ScriptableObject.putProperty(scope, "__jreadVoiceLocale", locale.ifBlank { "zh-CN" })
        return RhinoScriptEngine.eval(
            """
            if (typeof $uiObjectName.getVoices !== 'function') {
                throw new Error('插件没有提供音色列表 getVoices(locale)');
            }
            $uiObjectName.getVoices(String(__jreadVoiceLocale));
            """.trimIndent(),
            scope
        )
    }

    private fun evalPluginLocales(
        scope: Scriptable,
        uiObjectName: String,
    ): Any? {
        return RhinoScriptEngine.eval(
            """
            if (typeof $uiObjectName.getLocales !== 'function') {
                null;
            } else {
                $uiObjectName.getLocales();
            }
            """.trimIndent(),
            scope
        )
    }

    private fun readPluginLocaleOptionsFromScope(
        scope: Scriptable,
        uiObjectName: String,
    ): List<LocaleOption> {
        return runCatching {
            localesResultToOptions(evalPluginLocales(scope, uiObjectName))
        }.getOrElse {
            Log.w(TAG, "readPluginLocaleOptionsFromScope failed: ui=$uiObjectName", it)
            emptyList()
        }
    }

    fun notifyVoiceChanged(
        context: Context,
        plugin: JReadVoiceEngine.VoicePlugin,
        locale: String,
        voice: String,
        dataJson: String = "{}",
    ): Map<String, String> {
        if (plugin.code.isBlank()) return emptyMap()
        return runCatching {
            val runtime = RuntimeBridge(
                context = context,
                plugin = plugin,
                config = JReadVoiceEngine.VoiceConfig(
                    voiceTag = "",
                    locale = locale,
                    voice = voice,
                    dataJson = dataJson,
                ),
                pointer = JSONObject(),
            )
            val bindings = ScriptBindings().apply {
                put("ttsrv", runtime)
                put("fs", runtime.fs)
                put("http", runtime.http)
            }
            val scope = RhinoScriptEngine.getRuntimeScope(bindings)
            installPluginGlobals(scope, WebsocketFactory(context))
            installPluginCompatShims(scope)
            RhinoScriptEngine.eval(preparePluginCode(plugin.code), scope)
            installPluginCompatShims(scope)
            val uiObjectName = when {
                ScriptableObject.getProperty(scope, "EditorJS") is ScriptableObject -> "EditorJS"
                ScriptableObject.getProperty(scope, "PluginJS") is ScriptableObject -> "PluginJS"
                else -> ""
            }
            if (uiObjectName.isBlank()) return@runCatching runtime.tts.data.toMap()
            runCatching {
                loadEditorRuntime(scope, uiObjectName)
            }.onFailure {
                Log.w(TAG, "notifyVoiceChanged editor bootstrap failed: plugin=${plugin.name}", it)
            }
            ScriptableObject.putProperty(scope, "__jreadVoiceLocale", locale.ifBlank { "zh-CN" })
            ScriptableObject.putProperty(scope, "__jreadVoiceId", voice)
            RhinoScriptEngine.eval(
                """
                if (typeof $uiObjectName.onVoiceChanged === 'function') {
                    $uiObjectName.onVoiceChanged(String(__jreadVoiceLocale), String(__jreadVoiceId));
                }
                """.trimIndent(),
                scope
            )
            runtime.tts.data.toMap()
        }.getOrElse { emptyMap() }
    }

    fun listRulePresetOptions(
        context: Context,
        plugin: JReadVoiceEngine.VoicePlugin,
        dataJson: String = "{}",
    ): List<PluginPresetOption> {
        if (plugin.code.isBlank()) return emptyList()
        return runCatching {
            val runtime = RuntimeBridge(
                context = context,
                plugin = plugin,
                config = JReadVoiceEngine.VoiceConfig(
                    voiceTag = "",
                    dataJson = dataJson,
                ),
                pointer = JSONObject(),
            )
            val bindings = ScriptBindings().apply {
                put("ttsrv", runtime)
                put("fs", runtime.fs)
                put("http", runtime.http)
            }
            val scope = RhinoScriptEngine.getRuntimeScope(bindings)
            installPluginGlobals(scope, WebsocketFactory(context))
            installPluginCompatShims(scope)
            RhinoScriptEngine.eval(preparePluginCode(plugin.code), scope)
            installPluginCompatShims(scope)
            val uiObjectName = when {
                ScriptableObject.getProperty(scope, "EditorJS") is ScriptableObject -> "EditorJS"
                ScriptableObject.getProperty(scope, "PluginJS") is ScriptableObject -> "PluginJS"
                else -> ""
            }
            if (uiObjectName.isNotBlank()) {
                runCatching {
                    loadEditorRuntime(scope, uiObjectName)
                }.onFailure {
                    Log.w(TAG, "listRulePresetOptions editor bootstrap failed: plugin=${plugin.name}", it)
                }
            }
            listOf(PluginPresetOption("手写提示词", "0")) + readRulePresetOptionsFromScope(scope)
        }.getOrElse {
            Log.w(TAG, "listRulePresetOptions failed: plugin=${plugin.name}", it)
            emptyList()
        }
    }

    private fun readRulePresetOptionsFromScope(scope: Scriptable): List<PluginPresetOption> {
        val json = runCatching {
            RhinoScriptEngine.eval(
                """
                if (typeof getRulePresets === 'function') {
                    JSON.stringify(getRulePresets() || []);
                } else {
                    "[]";
                }
                """.trimIndent(),
                scope
            )?.toString().orEmpty()
        }.getOrDefault("")
        val fromJson = rulePresetsJsonToOptions(json)
        if (fromJson.isNotEmpty()) return fromJson
        val result = runCatching {
            RhinoScriptEngine.eval(
                """
                if (typeof getRulePresets === 'function') {
                    getRulePresets();
                } else {
                    [];
                }
                """.trimIndent(),
                scope
            )
        }.getOrNull()
        return rulePresetsResultToOptions(result)
    }

    private fun loadEditorRuntime(
        scope: Scriptable,
        uiObjectName: String,
    ) {
        RhinoScriptEngine.eval(
            """
            if (typeof $uiObjectName.onLoadData === 'function') {
                $uiObjectName.onLoadData();
            }
            if (typeof $uiObjectName.onLoadUI === 'function') {
                if (typeof __jreadUiContainer === 'undefined') {
                    var __jreadUiContainer = __jreadCreateLinearLayout(null);
                }
                if (typeof __jreadUiContext === 'undefined') {
                    var __jreadUiContext = __jreadCreateContext();
                }
                $uiObjectName.onLoadUI(__jreadUiContext, __jreadUiContainer);
            }
            """.trimIndent(),
            scope
        )
    }

    private fun installPluginGlobals(scope: Scriptable, websocketFactory: WebsocketFactory) {
        ScriptableObject.putProperty(scope, "__jreadWebsocketFactory", websocketFactory)
        ScriptableObject.putProperty(scope, "__jreadThreadScheduler", websocketFactory.threadScheduler)
        ScriptableObject.putProperty(scope, "__jreadBuffer", BufferFacade())
        ScriptableObject.putProperty(scope, "__jreadLogger", LoggerFacade())
        RhinoScriptEngine.eval(
            """
            if (typeof globalThis === 'undefined') {
                var globalThis = this;
            }
            if (typeof window === 'undefined') {
                var window = globalThis;
            }
            if (typeof self === 'undefined') {
                var self = globalThis;
            }
            if (typeof global === 'undefined') {
                var global = globalThis;
            }
            if (typeof console === 'undefined') {
                var __jreadLogMessage = function(args) {
                    var list = [];
                    for (var i = 0; i < args.length; i++) list.push(String(args[i]));
                    return list.join(' ');
                };
                var console = {
                    log: function() { __jreadLogger.d(__jreadLogMessage(arguments)); },
                    warn: function() { __jreadLogger.w(__jreadLogMessage(arguments)); },
                    error: function() { __jreadLogger.e(__jreadLogMessage(arguments)); },
                    info: function() { __jreadLogger.i(__jreadLogMessage(arguments)); }
                };
            } else if (typeof __jreadLogMessage === 'undefined') {
                var __jreadLogMessage = function(args) {
                    var list = [];
                    for (var i = 0; i < args.length; i++) list.push(String(args[i]));
                    return list.join(' ');
                };
            }
            if (typeof logger === 'undefined') {
                var logger = {
                    i: function() { __jreadLogger.i(__jreadLogMessage(arguments)); },
                    d: function() { __jreadLogger.d(__jreadLogMessage(arguments)); },
                    w: function() { __jreadLogger.w(__jreadLogMessage(arguments)); },
                    e: function() { __jreadLogger.e(__jreadLogMessage(arguments)); },
                    info: function() { __jreadLogger.i(__jreadLogMessage(arguments)); },
                    debug: function() { __jreadLogger.d(__jreadLogMessage(arguments)); },
                    warn: function() { __jreadLogger.w(__jreadLogMessage(arguments)); },
                    error: function() { __jreadLogger.e(__jreadLogMessage(arguments)); }
                };
            }
            if (typeof Buffer === 'undefined') {
                var Buffer = __jreadBuffer;
            }
            var __jreadThread = function(runnable) {
                return __jreadThreadScheduler.create(runnable);
            };
            var __jreadThreadSleep = function(ms) {
                return __jreadThreadScheduler.sleep(ms);
            };
            if (typeof Websocket === 'undefined') {
                var Websocket = function(url, headers) {
                    return __jreadWebsocketFactory.create(url, headers);
                };
            }
            if (typeof WebSocket === 'undefined') {
                var WebSocket = Websocket;
            }
            """.trimIndent(),
            scope
        )
    }

    private fun preparePluginCode(code: String): String {
        return code
            .replace("new java.lang.Thread", "new __jreadThread")
            .replace("java.lang.Thread.sleep", "__jreadThreadSleep")
    }

    private fun installPluginCompatShims(scope: Scriptable) {
        RhinoScriptEngine.eval(
            """
            // 部分 J.TTS 插件会用 java.lang.Thread 做内部超时检查。
            // J阅读这里由宿主 waitForCallbackAudio 统一超时，避免 JS 回调从任意 Java 线程进入 Rhino。
            if (typeof startMaoxiangTimeoutCheck === 'function') {
                startMaoxiangTimeoutCheck = function() {};
            }
            if (typeof atob === 'undefined') {
                var atob = function(value) {
                    return new java.lang.String(Packages.android.util.Base64.decode(String(value), Packages.android.util.Base64.DEFAULT), "UTF-8");
                };
            }
            if (typeof btoa === 'undefined') {
                var btoa = function(value) {
                    return Packages.android.util.Base64.encodeToString(new java.lang.String(String(value)).getBytes("UTF-8"), Packages.android.util.Base64.NO_WRAP);
                };
            }
            if (typeof Object.keys !== 'function') {
                Object.keys = function(obj) {
                    var out = [];
                    for (var key in obj) {
                        if (Object.prototype.hasOwnProperty.call(obj, key)) out.push(key);
                    }
                    return out;
                };
            }
            if (typeof Object.values !== 'function') {
                Object.values = function(obj) {
                    var keys = Object.keys(obj);
                    var out = [];
                    for (var i = 0; i < keys.length; i++) out.push(obj[keys[i]]);
                    return out;
                };
            }
            if (typeof Object.entries !== 'function') {
                Object.entries = function(obj) {
                    var keys = Object.keys(obj);
                    var out = [];
                    for (var i = 0; i < keys.length; i++) out.push([keys[i], obj[keys[i]]]);
                    return out;
                };
            }
            if (typeof __jreadMakeUiBase !== 'function') {
                var __jreadMakeUiBase = function() {
                    return {
                        children: [],
                        visibility: 0,
                        layoutParams: null,
                        backgroundColor: 0,
                        textColor: 0,
                        _clickListener: null,
                        addView: function(view) { this.children.push(view); return view; },
                        removeAllViews: function() { this.children = []; },
                        setOrientation: function(value) { this.orientation = value; },
                        setDescendantFocusability: function(value) { this.descendantFocusability = value; },
                        setPadding: function(left, top, right, bottom) {
                            this.padding = { left: left || 0, top: top || 0, right: right || 0, bottom: bottom || 0 };
                        },
                        setMargins: function(left, top, right, bottom) {
                            this.margins = { left: left || 0, top: top || 0, right: right || 0, bottom: bottom || 0 };
                        },
                        setVisibility: function(value) { this.visibility = value; },
                        getVisibility: function() { return this.visibility; },
                        setLayoutParams: function(value) { this.layoutParams = value; },
                        getLayoutParams: function() { return this.layoutParams; },
                        setBackgroundColor: function(value) { this.backgroundColor = value; },
                        setTextColor: function(value) { this.textColor = value; },
                        setText: function(value) { this.text = value == null ? "" : String(value); },
                        getText: function() { return this.text || ""; },
                        setHint: function(value) { this.hint = value == null ? "" : String(value); },
                        setEnabled: function(value) { this.enabled = !!value; },
                        setOnClickListener: function(listener) { this._clickListener = listener; },
                        performClick: function() {
                            if (!this._clickListener) return false;
                            if (typeof this._clickListener === 'function') this._clickListener(this);
                            else if (typeof this._clickListener.onClick === 'function') this._clickListener.onClick(this);
                            return true;
                        }
                    };
                };
                var __jreadCreateLinearLayout = function(ctx) {
                    var view = __jreadMakeUiBase();
                    view.orientation = 1;
                    return view;
                };
                var __jreadCreateContext = function() {
                    return {
                        getResources: function() {
                            return {
                                getDisplayMetrics: function() {
                                    return { density: 1, scaledDensity: 1, widthPixels: 1080, heightPixels: 1920 };
                                }
                            };
                        },
                        getPackageName: function() { return "io.legato.kazusa.jread.debug"; },
                        getApplicationContext: function() { return this; }
                    };
                };
                var Item = function(name, value) {
                    return { name: name, value: value };
                };
                var JSpinner = function(ctx, hint) {
                    var view = __jreadMakeUiBase();
                    view.hint = hint == null ? "" : String(hint);
                    view.items = [];
                    view.selectedPosition = 0;
                    view._itemListener = null;
                    Object.defineProperty(view, 'value', {
                        get: function() {
                            var item = this.items && this.items.length ? this.items[Math.max(0, this.selectedPosition || 0)] : null;
                            return item ? item.value : null;
                        },
                        set: function(value) {
                            if (!this.items) this.items = [];
                            for (var i = 0; i < this.items.length; i++) {
                                if (String(this.items[i].value) === String(value)) {
                                    this.selectedPosition = i;
                                    return;
                                }
                            }
                            this.selectedPosition = 0;
                        }
                    });
                    view.setOnItemSelected = function(listener) { this._itemListener = listener; };
                    view.select = function(position) {
                        this.selectedPosition = Math.max(0, position || 0);
                        var item = this.items && this.items.length ? this.items[this.selectedPosition] : null;
                        if (!this._itemListener || !item) return;
                        if (typeof this._itemListener === 'function') this._itemListener(this, this.selectedPosition, item);
                        else if (typeof this._itemListener.onItemSelected === 'function') this._itemListener.onItemSelected(this, this.selectedPosition, item);
                    };
                    return view;
                };
                var __jreadEditable = function(initial) {
                    return {
                        _value: initial == null ? "" : String(initial),
                        set: function(value) { this._value = value == null ? "" : String(value); },
                        append: function(value) { this._value += value == null ? "" : String(value); return this; },
                        clear: function() { this._value = ""; },
                        toString: function() { return this._value; },
                        length: function() { return this._value.length; }
                    };
                };
                var JTextInput = function(ctx, hint) {
                    var view = __jreadMakeUiBase();
                    view.hint = hint == null ? "" : String(hint);
                    view.text = __jreadEditable("");
                    view._textListener = null;
                    view.setText = function(value) {
                        this.text.set(value);
                        if (!this._textListener) return;
                        if (typeof this._textListener === 'function') this._textListener(this.text.toString());
                        else if (typeof this._textListener.onChanged === 'function') this._textListener.onChanged(this.text.toString());
                    };
                    view.getText = function() { return this.text; };
                    view.setOnTextChangedListener = function(listener) { this._textListener = listener; };
                    view.addTextChangedListener = view.setOnTextChangedListener;
                    return view;
                };
                var JSeekBar = function(ctx, hint) {
                    var view = __jreadMakeUiBase();
                    view.hint = hint == null ? "" : String(hint);
                    view.max = 0;
                    view.value = 0;
                    view._floatDigits = 0;
                    view._changeListener = null;
                    view.setFloatType = function(value) { this._floatDigits = value || 0; };
                    view.setOnChangeListener = function(listener) { this._changeListener = listener; };
                    return view;
                };
                var LinearLayout = function(ctx) { return __jreadCreateLinearLayout(ctx); };
                LinearLayout.HORIZONTAL = 0;
                LinearLayout.VERTICAL = 1;
                LinearLayout.LayoutParams = function(width, height, weight) {
                    return {
                        width: width,
                        height: height,
                        weight: weight || 0,
                        leftMargin: 0,
                        topMargin: 0,
                        rightMargin: 0,
                        bottomMargin: 0,
                        setMargins: function(left, top, right, bottom) {
                            this.leftMargin = left || 0;
                            this.topMargin = top || 0;
                            this.rightMargin = right || 0;
                            this.bottomMargin = bottom || 0;
                        }
                    };
                };
                LinearLayout.LayoutParams.MATCH_PARENT = -1;
                LinearLayout.LayoutParams.WRAP_CONTENT = -2;
                var TextView = function(ctx) { return __jreadMakeUiBase(); };
                var Button = function(ctx) { return __jreadMakeUiBase(); };
                var EditText = function(ctx) {
                    var view = JTextInput(ctx, "");
                    view.setSingleLine = function(value) { this.singleLine = !!value; };
                    view.setMaxLines = function(value) { this.maxLines = value || 0; };
                    view.setMinLines = function(value) { this.minLines = value || 0; };
                    view.setGravity = function(value) { this.gravity = value; };
                    view.setTextSize = function(value) { this.textSize = value; };
                    view.setHintTextColor = function(value) { this.hintTextColor = value; };
                    view.setBackgroundDrawable = function(value) { this.backgroundDrawable = value; };
                    view.setMinHeight = function(value) { this.minHeight = value; };
                    return view;
                };
                var Spinner = function(ctx) {
                    var view = JSpinner(ctx, "");
                    view.setAdapter = function(adapter) { this.adapter = adapter; this.items = adapter && adapter.items ? adapter.items : []; };
                    return view;
                };
                var ArrayAdapter = function(ctx, layout, items) {
                    return {
                        items: items || [],
                        setDropDownViewResource: function(value) { this.dropDownViewResource = value; }
                    };
                };
                var GradientDrawable = function() {
                    return {
                        setShape: function(value) { this.shape = value; },
                        setColor: function(value) { this.color = value; },
                        setStroke: function(width, color) { this.stroke = { width: width, color: color }; },
                        setCornerRadius: function(value) { this.cornerRadius = value; }
                    };
                };
                GradientDrawable.RECTANGLE = 0;
                var Toast = {
                    LENGTH_SHORT: 0,
                    LENGTH_LONG: 1,
                    makeText: function(ctx, text, length) {
                        return { show: function() { try { __jreadLogger.i(String(text)); } catch (ignore) {} } };
                    }
                };
                var __jreadColor = {
                    WHITE: -1,
                    TRANSPARENT: 0,
                    BLACK: -16777216,
                    parseColor: function(value) { return 0; }
                };
                var View = { VISIBLE: 0, INVISIBLE: 4, GONE: 8 };
                var ViewGroup = { FOCUS_BLOCK_DESCENDANTS: 0 };
                var Gravity = { TOP: 48, START: 8388611, CENTER: 17, CENTER_VERTICAL: 16 };
                var Typeface = { DEFAULT: {}, BOLD: 1, NORMAL: 0 };
                var Handler = function(looper) {
                    return {
                        post: function(runnable) {
                            if (runnable && typeof runnable.run === 'function') runnable.run();
                            else if (typeof runnable === 'function') runnable();
                            return true;
                        }
                    };
                };
                var Looper = { getMainLooper: function() { return {}; } };
                var Runnable = function(obj) { return obj || { run: function() {} }; };
                if (typeof android === 'undefined' || String(android).indexOf('[JavaPackage') >= 0) {
                    var android = {
                        R: { layout: { simple_spinner_item: 0, simple_spinner_dropdown_item: 1 } },
                        util: { Base64: Packages.android.util.Base64 },
                        os: { Handler: Handler, Looper: Looper },
                        view: { View: View, ViewGroup: ViewGroup, Gravity: Gravity },
                        widget: {
                            LinearLayout: LinearLayout,
                            TextView: TextView,
                            EditText: EditText,
                            Spinner: Spinner,
                            ArrayAdapter: ArrayAdapter,
                            Button: Button,
                            Toast: Toast
                        },
                        graphics: {
                            Color: __jreadColor,
                            Typeface: Typeface,
                            drawable: { GradientDrawable: GradientDrawable }
                        },
                        text: { TextUtils: { isEmpty: function(value) { return value == null || String(value).length === 0; } } }
                    };
                } else {
                    if (!android.R) android.R = { layout: { simple_spinner_item: 0, simple_spinner_dropdown_item: 1 } };
                    if (!android.view) android.view = {};
                    if (!android.view.View) android.view.View = View;
                    if (!android.view.ViewGroup) android.view.ViewGroup = ViewGroup;
                    if (!android.view.Gravity) android.view.Gravity = Gravity;
                    if (!android.os) android.os = {};
                    if (!android.os.Handler) android.os.Handler = Handler;
                    if (!android.os.Looper) android.os.Looper = Looper;
                    if (!android.widget) android.widget = {};
                    if (!android.widget.LinearLayout) android.widget.LinearLayout = LinearLayout;
                    if (!android.widget.TextView) android.widget.TextView = TextView;
                    if (!android.widget.EditText) android.widget.EditText = EditText;
                    if (!android.widget.Spinner) android.widget.Spinner = Spinner;
                    if (!android.widget.ArrayAdapter) android.widget.ArrayAdapter = ArrayAdapter;
                    if (!android.widget.Button) android.widget.Button = Button;
                    if (!android.widget.Toast) android.widget.Toast = Toast;
                    if (!android.graphics) android.graphics = {};
                    if (!android.graphics.Color) android.graphics.Color = __jreadColor;
                    if (!android.graphics.Typeface) android.graphics.Typeface = Typeface;
                    if (!android.graphics.drawable) android.graphics.drawable = {};
                    if (!android.graphics.drawable.GradientDrawable) android.graphics.drawable.GradientDrawable = GradientDrawable;
                    if (!android.text) android.text = {};
                    if (!android.text.TextUtils) android.text.TextUtils = { isEmpty: function(value) { return value == null || String(value).length === 0; } };
                }
            }
            """.trimIndent(),
            scope
        )
    }

    private fun waitForCallbackAudio(
        scope: Scriptable,
        websocketFactory: WebsocketFactory,
        callback: AudioCallback,
    ): ByteArray? {
        val deadline = System.currentTimeMillis() + 60_000L
        while (!callback.isClosed() && System.currentTimeMillis() < deadline) {
            drainWebsocketEvents(scope, websocketFactory)
            if (callback.isClosed()) break
            callback.awaitClose(80L)
        }
        drainWebsocketEvents(scope, websocketFactory)
        if (!callback.isClosed() && !callback.hasBytes()) {
            websocketFactory.cancelAll()
            throw IllegalStateException("等待插件音频超时，请检查网络或插件 WebSocket")
        }
        return callback.bytesOrNull()
    }

    private fun drainWebsocketEvents(scope: Scriptable, websocketFactory: WebsocketFactory) {
        ScriptableObject.putProperty(scope, "__jreadDrainWebsockets", websocketFactory)
        RhinoScriptEngine.eval("__jreadDrainWebsockets.drainAll();", scope)
    }

    private fun resultToBytes(result: Any?): ByteArray? {
        if (result == null) return null
        val value = (result as? Wrapper)?.unwrap() ?: result
        return when (value) {
            is ByteArray -> value
            is InputStream -> value.use { it.readBytes() }
            is NativeJavaArray -> nativeJavaArrayToBytes(value)
            is NativeArrayBuffer -> value.buffer
            is NativeTypedArrayView<*> -> value.buffer.buffer
            is SimpleResponse -> value.body().bytes()
            is AudioCallback -> value.bytesOrNull()
            is CharSequence -> {
                val url = value.toString()
                if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                    error(url)
                }
                okHttpClient.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        error("插件返回 URL 下载失败: HTTP ${response.code}")
                    }
                    response.body.bytes()
                }
            }
            else -> error("J.TTS 插件返回类型暂不支持: ${value.javaClass.name}")
        }
    }

    private fun voicesResultToOptions(result: Any?): List<VoiceOption> {
        val value = (result as? Wrapper)?.unwrap() ?: result ?: return emptyList()
        return when (value) {
            is Map<*, *> -> value.entries.flatMap { (key, rawValue) ->
                voiceEntryToOptions(key?.toString().orEmpty(), rawValue)
            }
            is Scriptable -> scriptableIds(value).flatMap { id ->
                voiceEntryToOptions(id, ScriptableObject.getProperty(value, id))
            }
            else -> emptyList()
        }.filter { it.id.isNotBlank() }.distinctBy { it.id }
    }

    private fun localesResultToOptions(result: Any?): List<LocaleOption> {
        val value = (result as? Wrapper)?.unwrap() ?: result ?: return emptyList()
        return when (value) {
            is Map<*, *> -> value.entries.mapNotNull { (key, rawValue) ->
                localeEntryToOption(key?.toString().orEmpty(), rawValue)
            }
            is Scriptable -> scriptableIds(value).mapNotNull { id ->
                localeEntryToOption(id, ScriptableObject.getProperty(value, id))
            }
            else -> emptyList()
        }.filter { it.id.isNotBlank() }.distinctBy { it.id }
    }

    private fun rulePresetsResultToOptions(result: Any?): List<PluginPresetOption> {
        val value = (result as? Wrapper)?.unwrap() ?: result ?: return emptyList()
        val rawItems = when (value) {
            is Map<*, *> -> value.values.toList()
            is Scriptable -> value.ids
                .map { key -> key.toString() }
                .filterNot { it == "length" }
                .sortedWith(compareBy<String> { it.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it })
                .map { id -> ScriptableObject.getProperty(value, id) }
            else -> emptyList()
        }
        return rawItems.mapIndexedNotNull { index, rawItem ->
            val item = (rawItem as? Wrapper)?.unwrap() ?: rawItem ?: return@mapIndexedNotNull null
            val name = readObjectString(item, "name")
                .ifBlank { readObjectString(item, "title") }
                .ifBlank { readObjectString(item, "label") }
                .ifBlank { "预设 ${index + 1}" }
            val rules = readObjectValue(item, "rules")
            val contextText = rulesToContextText(rules)
            PluginPresetOption(
                name = name,
                value = (index + 1).toString(),
                contextText = contextText,
            )
        }
    }

    private fun rulePresetsJsonToOptions(rawJson: String): List<PluginPresetOption> {
        val raw = rawJson.trim()
        if (!raw.startsWith("[")) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val name = item.optString("name")
                .ifBlank { item.optString("title") }
                .ifBlank { item.optString("label") }
                .ifBlank { "预设 ${index + 1}" }
            val contextText = rulesJsonToContextText(item.opt("rules"))
            PluginPresetOption(
                name = name,
                value = (index + 1).toString(),
                contextText = contextText,
            )
        }
    }

    private fun rulesJsonToContextText(rawRules: Any?): String {
        return when (rawRules) {
            null, JSONObject.NULL -> ""
            is JSONArray -> (0 until rawRules.length())
                .mapNotNull { rawRules.opt(it)?.toString()?.takeIf { text -> text.isNotBlank() } }
                .joinToString("\n")
            is JSONObject -> rawRules.keys().asSequence()
                .mapNotNull { key -> rawRules.opt(key)?.toString()?.takeIf { it.isNotBlank() } }
                .joinToString("\n")
            else -> rawRules.toString()
        }
    }

    private fun rulesToContextText(rawRules: Any?): String {
        val rules = (rawRules as? Wrapper)?.unwrap() ?: rawRules ?: return ""
        return when (rules) {
            is CharSequence -> rules.toString()
            is Map<*, *> -> rules.values.joinToString("\n") { it?.toString().orEmpty() }
            is Scriptable -> rules.ids
                .map { it.toString() }
                .filterNot { it == "length" }
                .sortedWith(compareBy<String> { it.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it })
                .joinToString("\n") { id ->
                    ScriptableObject.getProperty(rules, id)
                        ?.takeUnless { it == Scriptable.NOT_FOUND }
                        ?.toString()
                        .orEmpty()
                }
            else -> rules.toString()
        }
    }

    private fun microsoftLocaleOptionsFromCache(runtime: RuntimeBridge): List<LocaleOption> {
        val voices = microsoftVoicesJson(runtime) ?: return emptyList()
        val locales = linkedSetOf("zh-CN")
        for (index in 0 until voices.length()) {
            voices.optJSONObject(index)
                ?.optString("Locale")
                ?.takeIf { it.isNotBlank() }
                ?.let { locales += it }
        }
        return locales.map { LocaleOption(it, it) }
    }

    private fun microsoftVoiceOptionsFromCache(
        runtime: RuntimeBridge,
        locale: String,
    ): List<VoiceOption> {
        val voices = microsoftVoicesJson(runtime) ?: return emptyList()
        val labels = microsoftLocaleLabels(runtime)
        val cleanLocale = locale
            .takeUnless { it.isArrayIndexKey() }
            ?.ifBlank { "zh-CN" }
            ?: "zh-CN"
        return (0 until voices.length())
            .mapNotNull { voices.optJSONObject(it) }
            .filter { it.optString("Locale") == cleanLocale }
            .sortedWith(
                compareByDescending<JSONObject> { it.optString("Gender") }
                    .thenBy { it.optString("LocalName") }
            )
            .mapNotNull { item ->
                val id = item.optString("ShortName").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val localName = item.optString("LocalName").ifBlank { id }
                VoiceOption(
                    id = id,
                    name = labels[localName] ?: localName,
                    icon = item.optString("Gender").lowercase(),
                )
            }
            .distinctBy { it.id }
    }

    private fun microsoftVoicesJson(runtime: RuntimeBridge): JSONArray? {
        val cached = runtime.readTxtFile("voices2.json").trim()
        val raw = cached.ifBlank {
            runCatching {
                runtime.httpGetString("https://cnb.cool/XYZ50/0/-/git/raw/main/vs", emptyMap<String, String>())
                    .also { runtime.writeTxtFile("voices2.json", it) }
            }.getOrElse {
                Log.w(TAG, "Microsoft voices fallback download failed", it)
                ""
            }
        }.trim()
        if (!raw.startsWith("[")) return null
        return runCatching { JSONArray(raw) }.getOrElse {
            Log.w(TAG, "Microsoft voices fallback parse failed", it)
            null
        }
    }

    private fun microsoftLocaleLabels(runtime: RuntimeBridge): Map<String, String> {
        val cached = runtime.readTxtFile("Locales.json").trim()
        val raw = cached.ifBlank {
            runCatching {
                runtime.httpGetString("https://cnb.cool/XYZ50/0/-/git/raw/main/Locales.json", emptyMap<String, String>())
                    .also { runtime.writeTxtFile("Locales.json", it) }
            }.getOrDefault("")
        }.trim()
        if (!raw.startsWith("{")) return emptyMap()
        return runCatching { jsonObjectToMap(raw) }.getOrDefault(emptyMap())
    }

    private fun describeJsResult(result: Any?): String {
        val value = (result as? Wrapper)?.unwrap() ?: result ?: return "null"
        return when (value) {
            is Scriptable -> "Scriptable(ids=${value.ids.take(12).joinToString()})"
            is Map<*, *> -> "Map(size=${value.size}, keys=${value.keys.take(12).joinToString()})"
            else -> value.javaClass.name
        }
    }

    private fun readObjectValue(rawObject: Any?, key: String): Any? {
        val value = (rawObject as? Wrapper)?.unwrap() ?: rawObject ?: return null
        return when (value) {
            is Map<*, *> -> value[key]
            is Scriptable -> ScriptableObject.getProperty(value, key)
                ?.takeUnless { it == Scriptable.NOT_FOUND }
            else -> null
        }
    }

    private fun readObjectString(rawObject: Any?, key: String): String {
        return readObjectValue(rawObject, key)?.toString().orEmpty()
    }

    private fun localeEntryToOption(id: String, rawValue: Any?): LocaleOption? {
        if (id.isBlank()) return null
        val value = (rawValue as? Wrapper)?.unwrap() ?: rawValue ?: return LocaleOption(id, id)
        return when (value) {
            is CharSequence -> {
                val text = value.toString().trim()
                val optionId = if (id.isArrayIndexKey() && text.isNotBlank()) text else id
                LocaleOption(optionId, text.ifBlank { optionId })
            }
            is Scriptable -> {
                val optionId = listOf("id", "locale", "value", "code")
                    .firstNotNullOfOrNull { key ->
                        ScriptableObject.getProperty(value, key)
                            ?.takeUnless { it == Scriptable.NOT_FOUND }
                            ?.toString()
                            ?.takeIf { it.isNotBlank() }
                    }
                    .orEmpty()
                    .ifBlank { id }
                val name = listOf("name", "label", "title")
                    .firstNotNullOfOrNull { key ->
                        ScriptableObject.getProperty(value, key)
                            ?.takeUnless { it == Scriptable.NOT_FOUND }
                            ?.toString()
                    }
                    .orEmpty()
                LocaleOption(optionId, name.ifBlank { optionId })
            }
            is Map<*, *> -> {
                val optionId = listOf("id", "locale", "value", "code")
                    .firstNotNullOfOrNull { key -> value[key]?.toString()?.takeIf { it.isNotBlank() } }
                    .orEmpty()
                    .ifBlank { id }
                val name = (value["name"] ?: value["label"] ?: value["title"])?.toString().orEmpty()
                LocaleOption(optionId, name.ifBlank { optionId })
            }
            else -> {
                val text = value.toString().trim()
                val optionId = if (id.isArrayIndexKey() && text.isNotBlank()) text else id
                LocaleOption(optionId, text.ifBlank { optionId })
            }
        }
    }

    private fun voiceEntryToOption(id: String, rawValue: Any?): VoiceOption? {
        val value = (rawValue as? Wrapper)?.unwrap() ?: rawValue ?: return null
        return when (value) {
            is CharSequence -> {
                val text = value.toString().trim()
                val optionId = if (id.isArrayIndexKey() && text.isNotBlank()) text else id
                VoiceOption(id = optionId, name = text.ifBlank { optionId })
            }
            is Scriptable -> {
                val voiceId = listOf("id", "voice_id", "voiceId", "value", "code")
                    .firstNotNullOfOrNull { key ->
                        ScriptableObject.getProperty(value, key)
                            ?.takeUnless { it == Scriptable.NOT_FOUND }
                            ?.toString()
                            ?.takeIf { it.isNotBlank() }
                    }
                    .orEmpty()
                val name = listOf("name", "label", "title")
                    .firstNotNullOfOrNull { key ->
                        ScriptableObject.getProperty(value, key)
                            ?.takeUnless { it == Scriptable.NOT_FOUND }
                            ?.toString()
                            ?.takeIf { it.isNotBlank() }
                    }
                    .orEmpty()
                val icon = listOf("iconUrl", "icon")
                    .firstNotNullOfOrNull { key ->
                        ScriptableObject.getProperty(value, key)
                            ?.takeUnless { it == Scriptable.NOT_FOUND }
                            ?.toString()
                    }
                    .orEmpty()
                val optionId = voiceId.ifBlank { id }
                VoiceOption(id = optionId, name = name.ifBlank { optionId }, icon = icon)
            }
            is Map<*, *> -> {
                val voiceId = listOf("id", "voice_id", "voiceId", "value", "code")
                    .firstNotNullOfOrNull { key -> value[key]?.toString()?.takeIf { it.isNotBlank() } }
                    .orEmpty()
                val name = listOf("name", "label", "title")
                    .firstNotNullOfOrNull { key -> value[key]?.toString()?.takeIf { it.isNotBlank() } }
                    .orEmpty()
                val icon = (value["iconUrl"] ?: value["icon"])?.toString().orEmpty()
                val optionId = voiceId.ifBlank { id }
                VoiceOption(id = optionId, name = name.ifBlank { optionId }, icon = icon)
            }
            else -> {
                val text = value.toString().trim()
                val optionId = if (id.isArrayIndexKey() && text.isNotBlank()) text else id
                VoiceOption(id = optionId, name = text.ifBlank { optionId })
            }
        }
    }

    private fun String.isArrayIndexKey(): Boolean {
        return toIntOrNull()?.let { it >= 0 } == true
    }

    private fun String.isMeaningfulLocaleId(): Boolean {
        val clean = trim()
        return clean.isNotBlank() && clean != "length" && !clean.isArrayIndexKey()
    }

    private fun scriptableIds(value: Scriptable): List<String> {
        return value.ids
            .map { it.toString() }
            .filter { it.isNotBlank() && it != "length" }
    }

    private fun voiceEntryToOptions(id: String, rawValue: Any?): List<VoiceOption> {
        val value = (rawValue as? Wrapper)?.unwrap() ?: rawValue ?: return emptyList()
        return when (value) {
            is Scriptable -> {
                if (scriptableLooksLikeVoice(value)) {
                    return listOfNotNull(voiceEntryToOption(id, value))
                }
                val nested = value.ids.flatMap { key ->
                    val nestedId = key.toString()
                    val nestedRaw = ScriptableObject.getProperty(value, nestedId)
                    voiceEntryToOptions(nestedId, nestedRaw).map { option ->
                        if (id.isBlank() || option.name.startsWith("$id / ")) {
                            option
                        } else {
                            option.copy(name = "$id / ${option.name}")
                        }
                    }
                }
                nested.takeIf { it.isNotEmpty() } ?: listOfNotNull(voiceEntryToOption(id, rawValue))
            }
            is Map<*, *> -> {
                if (mapLooksLikeVoice(value)) {
                    return listOfNotNull(voiceEntryToOption(id, value))
                }
                val nested = value.entries.flatMap { (key, nestedRaw) ->
                    val nestedId = key?.toString().orEmpty()
                    voiceEntryToOptions(nestedId, nestedRaw).map { option ->
                        if (id.isBlank() || option.name.startsWith("$id / ")) {
                            option
                        } else {
                            option.copy(name = "$id / ${option.name}")
                        }
                    }
                }
                nested.takeIf { it.isNotEmpty() } ?: listOfNotNull(voiceEntryToOption(id, rawValue))
            }
            else -> listOfNotNull(voiceEntryToOption(id, rawValue))
        }
    }

    private fun scriptableLooksLikeVoice(value: Scriptable): Boolean {
        return VOICE_OBJECT_KEYS.any { key ->
            ScriptableObject.getProperty(value, key).let { it != null && it != Scriptable.NOT_FOUND }
        }
    }

    private fun mapLooksLikeVoice(value: Map<*, *>): Boolean {
        return VOICE_OBJECT_KEYS.any { key -> value.containsKey(key) }
    }

    private val VOICE_OBJECT_KEYS = setOf(
        "id",
        "voice_id",
        "voiceId",
        "value",
        "code",
        "name",
        "label",
        "title",
        "icon",
        "iconUrl",
    )

    @Keep
    class RuntimeBridge(
        private val context: Context,
        private val plugin: JReadVoiceEngine.VoicePlugin,
        config: JReadVoiceEngine.VoiceConfig,
        pointer: JSONObject,
    ) {
        val defVars: MutableMap<String, String> = jsonObjectToMap(plugin.defVarsJson)
        val userVars: MutableMap<String, String> = normalizeUserVars(jsonObjectToMap(plugin.userVarsJson))
        val fs = FileFacade(this)
        val http = HttpFacade(this)
        val tts = TtsState(
            locale = config.locale,
            voice = config.voice,
            pluginId = plugin.pluginId.ifBlank { plugin.id },
            speed = config.speed,
            volume = config.volume,
            pitch = config.pitch,
            userVars = userVars,
            defVars = defVars,
            data = jsonObjectToMap(config.dataJson).apply {
                if (!containsKey("voiceTag")) put("voiceTag", config.voiceTag)
                if (!containsKey("roleName")) put("roleName", pointer.optString("roleName"))
                if (!containsKey("emotion")) put("emotion", pointer.optString("emotion"))
            },
        )

        @JvmOverloads
        fun httpGetString(url: Any?, headers: Any? = null): String {
            return httpGet(url, headers).body().string()
        }

        @JvmOverloads
        fun httpGetBytes(url: Any?, headers: Any? = null): ByteArray {
            return httpGet(url, headers).body().bytes()
        }

        @JvmOverloads
        fun httpGet(
            url: Any?,
            headers: Any? = null,
            timeoutMs: Long = DEFAULT_TTS_HTTP_TIMEOUT_MS,
        ): SimpleResponse {
            val request = Request.Builder().url(url.toString()).get()
            anyToMap(headers).forEach { (name, value) -> request.addHeader(name, value) }
            val call = okHttpClient.newBuilder()
                .readTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .callTimeout(timeoutMs + 10_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()
                .newCall(request.build())
            return call.execute().use { response ->
                SimpleResponse(response.code, response.message, response.headers.toMultimap(), response.body.bytes())
            }
        }

        @JvmOverloads
        fun httpPost(
            url: Any?,
            body: Any? = "",
            headers: Any? = null,
            timeoutMs: Long = DEFAULT_TTS_HTTP_TIMEOUT_MS,
        ): SimpleResponse {
            val headerMap = anyToMap(headers)
            val contentType = headerMap.entries
                .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                ?.value
                ?.toMediaTypeOrNull()
            val requestBody = anyToBytes(body).toRequestBody(contentType)
            val request = Request.Builder().url(url.toString()).post(requestBody)
            headerMap.forEach { (name, value) ->
                if (!name.equals("Timeout", true) && !name.equals("X-Timeout", true)) {
                    request.addHeader(name, value)
                }
            }
            val call = okHttpClient.newBuilder()
                .readTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .callTimeout(timeoutMs + 10_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()
                .newCall(request.build())
            return call.execute().use { response ->
                SimpleResponse(response.code, response.message, response.headers.toMultimap(), response.body.bytes())
            }
        }

        fun fileExist(name: Any?): Boolean {
            return localPluginFile(name).exists() || externalPluginFileForRead(name)?.exists() == true
        }

        fun exists(name: Any?): Boolean = fileExist(name)

        fun readTxtFile(name: Any?): String {
            return readablePluginFile(name)?.readText(Charsets.UTF_8).orEmpty()
        }

        fun readText(name: Any?): String = readTxtFile(name)

        fun writeTxtFile(name: Any?, text: Any?) {
            val file = localPluginFile(name)
            file.parentFile?.mkdirs()
            file.writeText(text?.toString().orEmpty(), Charsets.UTF_8)
        }

        fun writeFile(name: Any?, text: Any?) = writeTxtFile(name, text)

        fun deleteFile(name: Any?) {
            localPluginFile(name).takeIf { it.exists() }?.delete()
        }

        fun getAudioSampleRate(audio: Any?): Int = 24_000

        fun md5Encode(text: Any?): String = MD5Utils.md5Encode(text?.toString().orEmpty())

        fun md5Encode16(text: Any?): String = MD5Utils.md5Encode16(text?.toString().orEmpty())

        @JvmOverloads
        fun createSymmetricCrypto(
            transformation: String,
            key: Any? = null,
            iv: Any? = null,
        ): SymmetricCryptoFacade {
            val crypto = SymmetricCryptoAndroid(
                transformation,
                key?.let { anyToBytes(it) }?.takeIf { it.isNotEmpty() },
            )
            val ivBytes = iv?.let { anyToBytes(it) }?.takeIf { it.isNotEmpty() }
            if (ivBytes != null) {
                crypto.setIv(ivBytes)
            }
            return SymmetricCryptoFacade(crypto)
        }

        @JvmOverloads
        fun strToBytes(text: Any?, charset: String = "UTF-8"): ByteArray {
            return text?.toString().orEmpty().toByteArray(Charset.forName(charset))
        }

        @JvmOverloads
        fun bytesToStr(bytes: Any?, charset: String = "UTF-8"): String {
            return anyToBytes(bytes).toString(Charset.forName(charset))
        }

        fun randomUUID(): String = UUID.randomUUID().toString()

        @JvmOverloads
        fun base64Encode(value: Any?, flags: Int = android.util.Base64.NO_WRAP): String {
            return android.util.Base64.encodeToString(anyToBytes(value), flags)
        }

        @JvmOverloads
        fun base64DecodeToBytes(value: Any?, flags: Int = android.util.Base64.DEFAULT): ByteArray {
            val raw = (value as? Wrapper)?.unwrap() ?: value
            return when (raw) {
                is ByteArray -> android.util.Base64.decode(raw, flags)
                else -> android.util.Base64.decode(raw?.toString().orEmpty(), flags)
            }
        }

        @JvmOverloads
        fun base64Decode(value: Any?, flags: Int = android.util.Base64.DEFAULT): String {
            return base64DecodeToBytes(value, flags).toString(Charsets.UTF_8)
        }

        @JvmOverloads
        fun httpGetStream(url: Any?, headers: Any? = null): InputStream {
            return ByteArrayInputStream(httpGet(url, headers).body().bytes())
        }

        fun playAudio(value: Any?) {
            Log.i(TAG, "Plugin playAudio ignored in JRead preview runtime: bytes=${anyToBytes(value).size}")
        }

        fun playAudioChunk(value: Any?) {
            Log.i(TAG, "Plugin playAudioChunk ignored in JRead preview runtime: bytes=${anyToBytes(value).size}")
        }

        fun setMargins(vararg args: Any?) = Unit

        fun localSoundFastPath(text: String): ByteArray? {
            if (!isLocalSoundPlugin()) return null
            val voice = tts.voice
            if (voice != "all_voices" && voice != "custom_url_audio" && !voice.contains("音效")) return null
            val soundName = extractLocalSoundName(if (voice == "all_voices") text else voice) ?: return null
            val fileName = soundName.replace(Regex("[^A-Za-z0-9\\u4e00-\\u9fa5]"), "_") + ".json"
            val raw = readTxtFile(fileName).ifBlank {
                downloadLocalSoundCache(soundName, fileName)
            }
            if (raw.isBlank()) {
                Log.w(TAG, "Local sound file missing: name=$soundName file=$fileName")
                return null
            }
            return runCatching {
                val trimmed = raw.trim()
                val obj = if (trimmed.startsWith("[")) {
                    JSONObject().put("currentIndex", 0).put("audios", JSONArray(trimmed))
                } else {
                    JSONObject(trimmed)
                }
                val audios = obj.optJSONArray("audios") ?: JSONArray()
                if (audios.length() <= 0) {
                    Log.w(TAG, "Local sound audios empty: name=$soundName file=$fileName")
                    return null
                }
                val index = obj.optInt("currentIndex", 0).floorMod(audios.length())
                val encoded = audios.optString(index).trim()
                val bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
                if (bytes.isEmpty()) {
                    Log.w(TAG, "Local sound decoded empty: name=$soundName file=$fileName index=$index")
                    return null
                }
                obj.put("currentIndex", (index + 1).floorMod(audios.length()))
                writeTxtFile(fileName, obj.toString())
                Log.i(TAG, "Local sound fast path hit: name=$soundName file=$fileName index=$index bytes=${bytes.size}")
                bytes
            }.getOrElse {
                Log.w(TAG, "Local sound fast path failed: name=$soundName file=$fileName", it)
                null
            }
        }

        private fun downloadLocalSoundCache(soundName: String, fileName: String): String {
            val encodedAudios = mutableListOf<String>()
            val variants = buildList {
                add(soundName)
                for (index in 2..5) add("$soundName$index")
            }
            variants.forEach { variant ->
                val bytes = readBuiltInAudiosEffect(variant) ?: downloadLocalSoundVariant(variant)
                if (bytes != null && bytes.isNotEmpty()) {
                    encodedAudios += android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                }
            }
            if (encodedAudios.isEmpty()) return ""
            val obj = JSONObject()
                .put("currentIndex", 0)
                .put("audios", JSONArray(encodedAudios))
            writeTxtFile(fileName, obj.toString())
            Log.i(TAG, "Local sound downloaded: name=$soundName file=$fileName count=${encodedAudios.size}")
            return obj.toString()
        }

        private fun downloadLocalSoundVariant(variantName: String): ByteArray? {
            val encodedName = URLEncoder.encode(variantName, "UTF-8").replace("+", "%20")
            val urls = listOf(
                "https://cnb.cool/mingwuyan/yinpin/-/git/raw/main/bdyinxiao/$encodedName.mp3",
                "https://gitee.com/mingwuyan/yinpin/raw/master/bdyinxiao/$encodedName.mp3",
            )
            urls.forEach { url ->
                val bytes = runCatching {
                    val request = Request.Builder().url(url).get().build()
                    okHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use null
                        response.body.bytes().takeIf { it.isNotEmpty() }
                    }
                }.getOrElse {
                    Log.w(TAG, "Local sound download failed: variant=$variantName url=$url", it)
                    null
                }
                if (bytes != null) return bytes
            }
            return null
        }

        private fun readBuiltInAudiosEffect(soundName: String): ByteArray? {
            val assetPath = builtInAudiosEffectAssetPath(soundName) ?: return null
            return runCatching {
                context.assets.open(BUILTIN_AUDIOS_EFFECT_ZIP_ASSET).use { input ->
                    ZipInputStream(input).use { zip ->
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            if (!entry.isDirectory && entry.name == assetPath) {
                                return@use zip.readBytes()
                            }
                        }
                    }
                }
                null
            }.getOrElse {
                Log.w(TAG, "Read built-in Audios effect failed: name=$soundName path=$assetPath", it)
                null
            }
        }

        private fun builtInAudiosEffectAssetPath(soundName: String): String? {
            val cleanName = soundName.trim(' ', '(', ')', '（', '）')
            if (cleanName.isBlank()) return null
            ensureBuiltInAudiosEffectMapLoaded()
            return builtInAudiosEffectMapCache[cleanName]
                ?: builtInAudiosEffectMapCache[cleanName.removeSuffix("音效") + "音效"]
        }

        private fun ensureBuiltInAudiosEffectMapLoaded() {
            if (builtInAudiosEffectMapCache.isNotEmpty()) return
            synchronized(builtInAudiosEffectMapCache) {
                if (builtInAudiosEffectMapCache.isNotEmpty()) return
                runCatching {
                    context.assets.open(BUILTIN_AUDIOS_EFFECT_MAP_ASSET)
                        .bufferedReader(Charsets.UTF_8)
                        .use { it.readText() }
                }.mapCatching { raw ->
                    val root = JSONObject(raw)
                    val items = root.optJSONArray("items") ?: JSONArray()
                    for (index in 0 until items.length()) {
                        val item = items.optJSONObject(index) ?: continue
                        val effect = item.optString("effect").trim()
                        val path = item.optString("assetPath").trim()
                        if (effect.isNotBlank() && path.isNotBlank()) {
                            builtInAudiosEffectMapCache.putIfAbsent(effect, path)
                        }
                    }
                    Log.i(TAG, "Loaded built-in Audios effect map: ${builtInAudiosEffectMapCache.size}")
                }.onFailure {
                    Log.w(TAG, "Load built-in Audios effect map failed", it)
                }
            }
        }

        private fun readablePluginFile(name: Any?): File? {
            val local = localPluginFile(name)
            if (local.exists()) return local
            val external = externalPluginFileForRead(name) ?: return null
            return runCatching {
                local.parentFile?.mkdirs()
                external.copyTo(local, overwrite = false)
                Log.i(TAG, "Copied plugin data file from J.TTS: plugin=${pluginStorageId()} file=${local.name}")
                local
            }.getOrElse {
                Log.w(TAG, "Read external plugin data directly: plugin=${pluginStorageId()} file=${external.name}", it)
                external
            }
        }

        private fun localPluginFile(name: Any?): File {
            val safeName = name?.toString().orEmpty()
                .replace('\\', '/')
                .split('/')
                .filter { it.isNotBlank() && it != "." && it != ".." }
                .joinToString("_")
                .ifBlank { "data.txt" }
            val dir = File(context.filesDir, "jread_voice_engine/plugin_files/${pluginStorageId()}")
            return File(dir, safeName)
        }

        private fun externalPluginFileForRead(name: Any?): File? {
            val safeName = name?.toString().orEmpty()
                .replace('\\', '/')
                .split('/')
                .filter { it.isNotBlank() && it != "." && it != ".." }
                .joinToString("_")
                .ifBlank { return null }
            val storageId = pluginStorageId()
            val dirs = listOf(
                File("/storage/emulated/0/Android/data/com.github.jing332.tts_server_android/files/plugins/$storageId"),
                File("/sdcard/Android/data/com.github.jing332.tts_server_android/files/plugins/$storageId"),
            )
            return dirs.firstNotNullOfOrNull { dir ->
                File(dir, safeName).takeIf { it.exists() && it.isFile && it.canRead() }
            }
        }

        private fun pluginStorageId(): String = plugin.pluginId.ifBlank { plugin.id }

        private fun isLocalSoundPlugin(): Boolean {
            val storageId = pluginStorageId()
            return storageId.equals("bendiyinxiao", ignoreCase = true) ||
                    storageId.equals("jread.audios.1064", ignoreCase = true)
        }

        private fun extractLocalSoundName(raw: String): String? {
            val matches = Regex("[\\u4e00-\\u9fa5]+").findAll(raw).map { it.value }.joinToString("")
            return matches.takeIf { it.isNotBlank() }
        }

        private fun Int.floorMod(mod: Int): Int {
            if (mod <= 0) return 0
            val value = this % mod
            return if (value < 0) value + mod else value
        }
    }

    @Keep
    class FileFacade(private val runtime: RuntimeBridge) {
        fun exists(name: Any?): Boolean = runtime.fileExist(name)
        fun readText(name: Any?): String = runtime.readTxtFile(name)
        fun writeFile(name: Any?, text: Any?) = runtime.writeTxtFile(name, text)
    }

    @Keep
    class HttpFacade(private val runtime: RuntimeBridge) {
        @JvmOverloads
        fun get(
            url: Any?,
            headers: Any? = null,
            timeoutMs: Long = DEFAULT_TTS_HTTP_TIMEOUT_MS,
        ): SimpleResponse = runtime.httpGet(url, headers, timeoutMs)

        @JvmOverloads
        fun post(
            url: Any?,
            body: Any? = "",
            headers: Any? = null,
            timeoutMs: Long = DEFAULT_TTS_HTTP_TIMEOUT_MS,
        ): SimpleResponse = runtime.httpPost(url, body, headers, timeoutMs)
    }

    @Keep
    class SymmetricCryptoFacade(
        private val crypto: SymmetricCryptoAndroid,
    ) {
        fun encrypt(value: Any?): ByteArray {
            val raw = (value as? Wrapper)?.unwrap() ?: value
            return when (raw) {
                is ByteArray -> crypto.encrypt(raw)
                is InputStream -> crypto.encrypt(raw)
                else -> crypto.encrypt(raw?.toString().orEmpty())
            }
        }

        fun encryptBase64(value: Any?): String {
            val raw = (value as? Wrapper)?.unwrap() ?: value
            return when (raw) {
                is ByteArray -> crypto.encryptBase64(raw)
                is InputStream -> crypto.encryptBase64(raw)
                else -> crypto.encryptBase64(raw?.toString().orEmpty())
            }
        }

        fun decrypt(value: Any?): ByteArray {
            val raw = (value as? Wrapper)?.unwrap() ?: value
            return when (raw) {
                is ByteArray -> crypto.decrypt(raw)
                is InputStream -> crypto.decrypt(raw)
                else -> crypto.decrypt(raw?.toString().orEmpty())
            }
        }

        fun decryptStr(value: Any?): String {
            return decrypt(value).toString(Charsets.UTF_8)
        }
    }

    @Keep
    data class TtsState(
        val locale: String,
        val voice: String,
        val pluginId: String,
        val speed: Float,
        val volume: Float,
        val pitch: Float,
        val userVars: MutableMap<String, String>,
        val defVars: MutableMap<String, String>,
        val data: MutableMap<String, String>,
    )

    @Keep
    class SimpleResponse(
        private val code: Int,
        private val message: String,
        private val headers: Map<String, List<String>>,
        private val bytes: ByteArray,
    ) {
        fun code(): Int = code
        fun status(): Int = code
        fun message(): String = message
        fun isSuccessful(): Boolean = code in 200..299
        fun headers(): Map<String, List<String>> = headers
        fun header(name: String): String? = header(name, null)
        fun header(name: String, defaultValue: String?): String? {
            return headers.entries
                .firstOrNull { it.key.equals(name, ignoreCase = true) }
                ?.value
                ?.firstOrNull()
                ?: defaultValue
        }
        fun body(): SimpleBody = SimpleBody(bytes)
        fun bytes(): ByteArray = bytes
        fun text(): String = bytes.toString(Charsets.UTF_8)
        fun string(): String = text()
        fun json(): Any? = parseJsonValue(text())
        fun contentType(): String = URLConnection.guessContentTypeFromStream(ByteArrayInputStream(bytes)).orEmpty()
    }

    @Keep
    class BufferFacade {
        @JvmOverloads
        fun from(value: Any?, encoding: String = "utf-8"): ByteArray {
            val raw = (value as? Wrapper)?.unwrap() ?: value
            if (raw is ByteArray) return raw
            if (raw is InputStream) return raw.use { it.readBytes() }
            if (raw is NativeJavaArray) return nativeJavaArrayToBytes(raw)
            if (raw is NativeArrayBuffer) return raw.buffer
            if (raw is NativeTypedArrayView<*>) return raw.buffer.buffer
            val text = raw?.toString().orEmpty()
            return when (encoding.lowercase()) {
                "base64" -> android.util.Base64.decode(text, android.util.Base64.DEFAULT)
                "hex" -> text.chunked(2)
                    .filter { it.length == 2 }
                    .map { it.toInt(16).toByte() }
                    .toByteArray()
                "ascii" -> text.toByteArray(Charsets.US_ASCII)
                "utf8", "utf-8" -> text.toByteArray(Charsets.UTF_8)
                else -> text.toByteArray(Charset.forName(encoding))
            }
        }
    }

    @Keep
    class LoggerFacade {
        fun i(message: Any?) {
            Log.i(TAG, message?.toString().orEmpty())
        }

        fun d(message: Any?) {
            Log.d(TAG, message?.toString().orEmpty())
        }

        fun w(message: Any?) {
            Log.w(TAG, message?.toString().orEmpty())
        }

        fun e(message: Any?) {
            Log.e(TAG, message?.toString().orEmpty())
        }
    }

    @Keep
    class SimpleBody(private val bytes: ByteArray) {
        fun bytes(): ByteArray = bytes
        fun string(): String = bytes.toString(Charsets.UTF_8)
        fun byteStream(): InputStream = ByteArrayInputStream(bytes)
        fun contentLength(): Long = bytes.size.toLong()
        fun contentType(): String = URLConnection.guessContentTypeFromStream(ByteArrayInputStream(bytes)).orEmpty()
    }

    @Keep
    class AndroidFacade {
        val util = AndroidUtilFacade()
    }

    @Keep
    class AndroidUtilFacade {
        val Base64 = AndroidBase64Facade()
    }

    @Keep
    class AndroidBase64Facade {
        @JvmField
        val DEFAULT: Int = android.util.Base64.DEFAULT
        @JvmField
        val NO_WRAP: Int = android.util.Base64.NO_WRAP
        @JvmField
        val URL_SAFE: Int = android.util.Base64.URL_SAFE

        @JvmOverloads
        fun decode(value: Any?, flags: Int = android.util.Base64.DEFAULT): ByteArray {
            val raw = (value as? Wrapper)?.unwrap() ?: value
            return when (raw) {
                is ByteArray -> android.util.Base64.decode(raw, flags)
                else -> android.util.Base64.decode(raw?.toString().orEmpty(), flags)
            }
        }

        @JvmOverloads
        fun encodeToString(value: Any?, flags: Int = android.util.Base64.NO_WRAP): String {
            return android.util.Base64.encodeToString(anyToBytes(value), flags)
        }
    }

    @Keep
    class WebsocketFactory(context: Context) {
        private val sockets = CopyOnWriteArrayList<PluginWebsocket>()
        private val userAgent = runCatching { WebSettings.getDefaultUserAgent(context.applicationContext) }
            .getOrElse { DEFAULT_WEBVIEW_USER_AGENT }
        val threadScheduler = PluginThreadScheduler { drainSocketEvents() }

        @JvmOverloads
        fun create(url: Any?, headers: Any? = null): PluginWebsocket {
            return PluginWebsocket(url?.toString().orEmpty(), headers, userAgent).also { sockets += it }
        }

        fun drainAll() {
            drainSocketEvents()
            threadScheduler.drainAll()
        }

        fun cancelAll() {
            threadScheduler.cancelAll()
            sockets.forEach { it.cancel() }
        }

        private fun drainSocketEvents() {
            sockets.forEach { it.drainQueuedEvents() }
        }
    }

    @Keep
    class PluginThreadScheduler(private val pumpEvents: () -> Unit) {
        private val tasks = ConcurrentLinkedQueue<PluginThread>()

        fun create(runnable: Any?): PluginThread {
            return PluginThread(runnable).also { thread ->
                thread.onStart = { tasks.add(thread) }
            }
        }

        fun sleep(ms: Any?) {
            val delay = ms?.toString()?.toLongOrNull()?.coerceAtMost(2_000L) ?: return
            val deadline = System.currentTimeMillis() + delay
            while (System.currentTimeMillis() < deadline) {
                pumpEvents()
                Thread.sleep(40L.coerceAtMost((deadline - System.currentTimeMillis()).coerceAtLeast(1L)))
            }
            pumpEvents()
        }

        fun drainAll() {
            while (true) {
                val task = tasks.poll() ?: break
                task.runIfNeeded()
            }
        }

        fun cancelAll() {
            while (true) {
                val task = tasks.poll() ?: break
                task.interrupt()
            }
        }
    }

    @Keep
    class PluginThread(private val runnable: Any?) {
        internal var onStart: (() -> Unit)? = null

        @Volatile
        private var started = false

        @Volatile
        private var finished = false

        @Volatile
        private var interrupted = false

        fun start() {
            if (started) return
            started = true
            onStart?.invoke()
        }

        @JvmOverloads
        fun join(timeoutMs: Long = 0L) {
            runIfNeeded()
        }

        fun interrupt() {
            interrupted = true
        }

        fun isAlive(): Boolean = started && !finished && !interrupted

        fun runIfNeeded() {
            if (finished || interrupted) return
            finished = true
            val raw = (runnable as? Wrapper)?.unwrap() ?: runnable
            when (raw) {
                is Runnable -> raw.run()
                is Function -> {
                    val scope = raw.parentScope ?: return
                    val cx = RhinoContext.enter()
                    try {
                        raw.call(cx, scope, scope, emptyArray())
                    } finally {
                        RhinoContext.exit()
                    }
                }
            }
        }
    }

    @Keep
    class PluginWebsocket @JvmOverloads constructor(
        private val url: String,
        headers: Any? = null,
        private val userAgent: String = DEFAULT_WEBVIEW_USER_AGENT,
    ) : WebSocketListener() {
        companion object {
            const val CONNECTING = 0
            const val OPEN = 1
            const val CLOSING = 2
            const val CLOSED = 3
            const val FAILURE = 4
        }

        private val callbacks = ConcurrentHashMap<String, Any>()
        private val replayEvents = ConcurrentHashMap<String, Array<out Any?>>()
        private val eventQueue = ConcurrentLinkedQueue<WebsocketEvent>()
        private var socket: WebSocket? = null

        @Volatile
        var state: Int = CONNECTING
            private set

        init {
            val request = Request.Builder().url(url)
            defaultWebsocketHeaders(url, userAgent).forEach { (name, value) -> request.header(name, value) }
            anyToMap(headers).forEach { (name, value) -> request.addHeader(name, value) }
            socket = websocketClient.newWebSocket(request.build(), this)
        }

        fun on(name: Any?, callback: Any?): PluginWebsocket {
            val key = name?.toString().orEmpty().lowercase()
            val value = (callback as? Wrapper)?.unwrap() ?: callback
            if (value == null) {
                callbacks.remove(key)
            } else {
                callbacks[key] = value
                replayEvents[key]?.let { eventQueue.add(WebsocketEvent(key, it)) }
            }
            return this
        }

        fun send(value: Any?): Boolean {
            val raw = (value as? Wrapper)?.unwrap() ?: value
            return when (raw) {
                is ByteArray,
                is InputStream,
                is NativeArrayBuffer,
                is NativeTypedArrayView<*> -> socket?.send(ByteString.of(*anyToBytes(raw))) == true
                else -> socket?.send(raw?.toString().orEmpty()) == true
            }
        }

        @JvmOverloads
        fun close(code: Int = 1000, reason: String? = ""): Boolean {
            state = CLOSING
            return socket?.close(code, reason) == true
        }

        fun cancel() {
            socket?.cancel()
            state = CLOSED
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            state = OPEN
            Log.i(TAG, "WebSocket open: code=${response.code}, url=${webSocket.request().url.redact()}")
            rememberAndQueue("open", response)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.i(TAG, "WebSocket text: len=${text.length}, head=${text.take(220)}")
            queue("text", text)
            queue("message", text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val data = bytes.toByteArray()
            Log.i(TAG, "WebSocket binary: len=${data.size}")
            queue("binary", data)
            queue("bytes", data)
            queue("message", data)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            state = CLOSING
            Log.i(TAG, "WebSocket closing: code=$code, reason=$reason")
            queue("closing", code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            state = CLOSED
            Log.i(TAG, "WebSocket closed: code=$code, reason=$reason")
            rememberAndQueue("close", code, reason)
            rememberAndQueue("closed", code, reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            state = FAILURE
            val message = t.localizedMessage ?: t.javaClass.simpleName
            Log.w(TAG, "WebSocket failure: $message, responseCode=${response?.code}")
            rememberAndQueue("error", message)
            rememberAndQueue("failure", message)
        }

        fun drainQueuedEvents() {
            while (true) {
                val event = eventQueue.poll() ?: break
                dispatch(event.name, *event.args)
            }
        }

        private fun queue(name: String, vararg args: Any?) {
            eventQueue.add(WebsocketEvent(name, args))
        }

        private fun rememberAndQueue(name: String, vararg args: Any?) {
            replayEvents[name] = args
            queue(name, *args)
        }

        private fun dispatch(name: String, vararg args: Any?) {
            val callback = callbacks[name] ?: return
            val function = callback as? Function ?: return
            val scope = function.parentScope ?: return
            val cx = RhinoContext.enter()
            try {
                val jsArgs = args.map { arg ->
                    if (arg is ByteArray) {
                        byteArrayToUint8Array(arg, scope)
                    } else {
                        RhinoContext.javaToJS(arg, scope)
                    }
                }.toTypedArray()
                function.call(cx, scope, scope, jsArgs)
            } finally {
                RhinoContext.exit()
            }
        }

        private data class WebsocketEvent(
            val name: String,
            val args: Array<out Any?>,
        )
    }

    private fun byteArrayToUint8Array(bytes: ByteArray, scope: Scriptable): NativeUint8Array {
        val buffer = NativeArrayBuffer(bytes.size.toDouble())
        System.arraycopy(bytes, 0, buffer.buffer, 0, bytes.size)
        return NativeUint8Array(buffer, 0, bytes.size).apply {
            parentScope = scope
            prototype = ScriptableObject.getClassPrototype(scope, "Uint8Array")
        }
    }

    private const val DEFAULT_WEBVIEW_USER_AGENT =
        "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/123.0 Mobile Safari/537.36"

    private fun defaultWebsocketHeaders(url: String, userAgent: String): Map<String, String> {
        val host = runCatching { URI(url).host }.getOrNull().orEmpty()
        return buildMap {
            if (host.isNotBlank()) put("Host", host)
            put("Origin", "https://$host")
            put("User-Agent", userAgent)
            put("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7")
            put("Cache-Control", "no-cache")
            put("Pragma", "no-cache")
        }
    }

    @Keep
    class AudioCallback {
        private val chunks = mutableListOf<ByteArray>()
        private var error: String? = null
        private val closed = CountDownLatch(1)

        fun write(value: Any?) {
            val bytes = anyToBytes(value)
            synchronized(chunks) {
                chunks += bytes
            }
            Log.i(TAG, "AudioCallback.write: len=${bytes.size}, chunks=${synchronized(chunks) { chunks.size }}")
        }

        fun close() {
            Log.i(TAG, "AudioCallback.close: bytes=${synchronized(chunks) { chunks.sumOf { it.size } }}")
            closed.countDown()
        }

        fun error(value: Any?) {
            error = value?.toString()
            Log.w(TAG, "AudioCallback.error: $error")
            closed.countDown()
        }

        fun isClosed(): Boolean = closed.count == 0L

        fun hasBytes(): Boolean = synchronized(chunks) { chunks.isNotEmpty() }

        fun awaitClose(timeoutMs: Long): Boolean {
            return closed.await(timeoutMs, TimeUnit.MILLISECONDS)
        }

        @JvmOverloads
        fun bytesOrNull(wait: Boolean = false): ByteArray? {
            if (wait) {
                val completed = closed.await(60_000L, TimeUnit.MILLISECONDS)
                if (!completed && synchronized(chunks) { chunks.isEmpty() }) {
                    throw IllegalStateException("等待插件音频超时，请检查网络或插件 WebSocket")
                }
            }
            error?.let { throw IllegalStateException(it) }
            val snapshot = synchronized(chunks) { chunks.toList() }
            if (snapshot.isEmpty()) return null
            return snapshot.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
        }
    }

    private fun anyToBytes(value: Any?): ByteArray {
        val unwrapped = (value as? Wrapper)?.unwrap() ?: value
        return when (unwrapped) {
            null -> ByteArray(0)
            is ByteArray -> unwrapped
            is InputStream -> unwrapped.use { it.readBytes() }
            is NativeJavaArray -> nativeJavaArrayToBytes(unwrapped)
            is NativeArrayBuffer -> unwrapped.buffer
            is NativeTypedArrayView<*> -> unwrapped.buffer.buffer
            else -> unwrapped.toString().toByteArray(Charsets.UTF_8)
        }
    }

    private fun nativeJavaArrayToBytes(value: NativeJavaArray): ByteArray {
        val raw = value.unwrap()
        return when (raw) {
            is ByteArray -> raw
            is Array<*> -> raw.map { (it as? Number)?.toByte() ?: 0.toByte() }.toByteArray()
            else -> raw?.toString().orEmpty().toByteArray(Charsets.UTF_8)
        }
    }

    private fun anyToMap(value: Any?): Map<String, String> {
        val unwrapped = (value as? Wrapper)?.unwrap() ?: value ?: return emptyMap()
        return when (unwrapped) {
            is Map<*, *> -> unwrapped.entries.associate {
                it.key.toString() to (it.value?.toString().orEmpty())
            }
            is NativeObject -> unwrapped.ids.associate { key ->
                key.toString() to ScriptableObject.getProperty(unwrapped, key.toString()).toString()
            }
            is Scriptable -> unwrapped.ids.associate { key ->
                key.toString() to ScriptableObject.getProperty(unwrapped, key.toString()).toString()
            }
            is JSONObject -> jsonObjectToMap(unwrapped.toString())
            else -> emptyMap()
        }
    }

    private fun parseJsonValue(raw: String): Any? {
        val text = raw.trim()
        return when {
            text.startsWith("{") -> jsonObjectToJava(JSONObject(text))
            text.startsWith("[") -> jsonArrayToJava(JSONArray(text))
            else -> null
        }
    }

    private fun jsonObjectToJava(obj: JSONObject): MutableMap<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        obj.keys().forEach { key ->
            result[key] = jsonValueToJava(obj.opt(key))
        }
        return result
    }

    private fun jsonArrayToJava(array: JSONArray): MutableList<Any?> {
        return MutableList(array.length()) { index ->
            jsonValueToJava(array.opt(index))
        }
    }

    private fun jsonValueToJava(value: Any?): Any? {
        return when (value) {
            null, JSONObject.NULL -> null
            is JSONObject -> jsonObjectToJava(value)
            is JSONArray -> jsonArrayToJava(value)
            else -> value
        }
    }

    private fun jsonObjectToMap(raw: String): MutableMap<String, String> {
        val obj = runCatching { JSONObject(raw.ifBlank { "{}" }) }.getOrNull() ?: return mutableMapOf()
        return buildMap {
            obj.keys().forEach { key ->
                put(key, obj.optString(key))
            }
        }.toMutableMap()
    }

    private fun normalizeUserVars(vars: MutableMap<String, String>): MutableMap<String, String> {
        val apiAliases = listOf("api", "apiKey", "api_key", "key", "token", "accessToken", "secret")
        val apiValue = apiAliases
            .asSequence()
            .mapNotNull { key -> vars[key]?.trim()?.takeIf { it.isNotBlank() } }
            .firstOrNull()
        if (apiValue != null) {
            apiAliases.forEach { key ->
                if (vars[key].isNullOrBlank()) {
                    vars[key] = apiValue
                }
            }
        }
        return vars
    }
}
