package io.legado.app.ui.book.read.config

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.legado.app.help.config.AppConfig
import io.legado.app.help.tts.TtsWebSocketHelper
import io.legado.app.model.ReadAloud
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.StringUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object TtsPreviewHelper {

    private var previewMediaPlayer: MediaPlayer? = null

    private val WEBSOCKET_JS_BRIDGE = """
        if (typeof Websocket === 'undefined' && typeof ws !== 'undefined' && ws._connectNative) {
            function Websocket(url, headers) {
                var nativeConn = ws._connectNative(url, headers || {});
                this.readyState = nativeConn.readyState;
                var callbacks = {};
                var self = this;
                this.on = function(event, fn) {
                    callbacks[event] = fn;
                    nativeConn.on(event, function() {
                        self.readyState = nativeConn.readyState;
                        var cb = callbacks[event];
                        if (cb) {
                            var args = Array.prototype.slice.call(arguments);
                            cb.apply(self, args);
                        }
                    });
                    if (event === 'open' && nativeConn.readyState === 1) {
                        self.readyState = 1;
                        fn.call(self);
                    }
                    if (event === 'error' && nativeConn.readyState === 3) {
                        self.readyState = 3;
                        fn.call(self, "WebSocket error");
                    }
                    if (event === 'close' && nativeConn.readyState === 3) {
                        self.readyState = 3;
                        fn.call(self, 1000, "");
                    }
                };
                this.send = function(data) { nativeConn.send(data); };
                this.close = function(code, reason) { nativeConn.close(code || 1000, reason || ""); };
            }
            Websocket.CONNECTING = 0;
            Websocket.OPEN = 1;
            Websocket.CLOSING = 2;
            Websocket.CLOSED = 3;
        }
    """.trimIndent()

    fun previewVoice(
        fragment: DialogFragment,
        previewText: String,
        voiceId: String = "",
        speed: Double = 1.0,
        volume: Double = 1.0,
        sampleRate: String = "24000",
        emotion: String = "",
        contextTexts: String = "",
        onToast: (String) -> Unit
    ) {
        previewVoice(
            context = fragment.requireContext(),
            lifecycleOwner = fragment,
            previewText = previewText,
            voiceId = voiceId,
            speed = speed,
            volume = volume,
            sampleRate = sampleRate,
            emotion = emotion,
            contextTexts = contextTexts,
            onToast = onToast
        )
    }

    fun previewVoice(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewText: String,
        voiceId: String = "",
        speed: Double = 1.0,
        volume: Double = 1.0,
        sampleRate: String = "24000",
        emotion: String = "",
        contextTexts: String = "",
        onToast: (String) -> Unit
    ) {
        if (previewText.isBlank()) {
            onToast("请输入试听文本")
            return
        }

        val ttsEngine = ReadAloud.ttsEngine
        if (!ttsEngine.isNullOrBlank() && StringUtils.isNumeric(ttsEngine)) {
            // 用户设置了网络TTS引擎（ID是纯数字）
            previewWithHttpTts(context, lifecycleOwner, previewText, voiceId, speed, volume, sampleRate, emotion, contextTexts, onToast)
        } else {
            // 用户设置了系统TTS引擎（ttsEngine为空或不是数字）
            previewWithSystemTts(context, lifecycleOwner, previewText, onToast)
        }
    }

    private fun previewWithHttpTts(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewText: String,
        voiceId: String,
        speed: Double,
        volume: Double,
        sampleRate: String,
        emotion: String,
        contextTexts: String,
        onToast: (String) -> Unit
    ) {
        val httpTts = ReadAloud.httpTTS ?: return

        onToast("正在合成试听音频...")

        lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val tempFile = File(context.cacheDir, "tts_preview_${System.currentTimeMillis()}.mp3")
                var ttsUrl = httpTts.url

                // 支持 @js: 前缀：先执行 JS 获取实际 URL（包含 token 等认证信息）
                if (ttsUrl.startsWith("@js:")) {
                    val jsCode = ttsUrl.substring(4)
                    val wsHelper = TtsWebSocketHelper()
                    val speechRate = AppConfig.speechRatePlay + 5
                    val jsResult = httpTts.evalJS(WEBSOCKET_JS_BRIDGE + jsCode) {
                        put("speakText", previewText)
                        put("speechRate", speechRate)
                        put("ws", wsHelper)
                    }
                    when (jsResult) {
                        is ByteArray -> {
                            tempFile.writeBytes(jsResult)
                            withContext(Dispatchers.Main) { playPreviewAudio(context, tempFile) }
                            return@launch
                        }
                        is java.io.InputStream -> {
                            jsResult.use { input ->
                                tempFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            withContext(Dispatchers.Main) { playPreviewAudio(context, tempFile) }
                            return@launch
                        }
                        is String -> {
                            ttsUrl = jsResult
                        }
                        else -> {
                            throw Exception("JS 脚本返回类型不支持: ${jsResult?.javaClass?.name}")
                        }
                    }
                }

                // 如果是猫箱类型引擎且传入了当前页面参数，
                // 从 @js: 返回的 URL 中提取 token，再用当前参数合成试听音频
                val isMaoxiangEngine = ttsUrl.contains("myparallelstory")
                if (isMaoxiangEngine && voiceId.isNotBlank()) {
                    val uri = Uri.parse(ttsUrl)
                    val token = uri.getQueryParameter("token") ?: ""
                    if (token.isBlank()) {
                        withContext(Dispatchers.Main) {
                            onToast("无法获取猫箱引擎的认证 token，请检查网络连接")
                        }
                        return@launch
                    }
                    val appkey = uri.getQueryParameter("appkey") ?: "WQuVLKMGRo"
                    val sr = sampleRate.toIntOrNull() ?: 24000
                    val host = ttsUrl.substringBefore("?")

                    val extraPayloadObj = JSONObject()
                    val audioConfig = JSONObject().apply {
                        put("format", "mp3")
                        put("sample_rate", sr)
                        put("loudness_rate", maxOf(-48.0, (volume - 1) * 50))
                    }
                    if (voiceId.contains("emo") && emotion.isNotBlank()) {
                        audioConfig.put("emotion", emotion)
                        audioConfig.put("emotion_scale", 4)
                    }
                    extraPayloadObj.put("audio_config", audioConfig)
                    if (contextTexts.isNotBlank()) {
                        extraPayloadObj.put(
                            "context_texts",
                            JSONArray().apply { put(contextTexts) }
                        )
                    }

                    val extraPayload = extraPayloadObj.toString()

                    var retryCount = 0
                    while (true) {
                        // 每次重试重新生成随机设备信息，避免服务器因同一 device 拒绝连接
                        val deviceId = UUID.randomUUID().toString().replace("-", "").take(16)
                        val aid = UUID.randomUUID().toString().replace("-", "").take(16)
                        val wsUrl = "$host?" +
                            "voice=$voiceId&format=mp3&sampleRate=$sr&appkey=$appkey" +
                            "&token=$token&ssmix=&aid=$aid&device_id=$deviceId"

                        try {
                            val wsHelper = TtsWebSocketHelper()
                            val audioBytes = wsHelper.maoxiang(
                                wsUrl = wsUrl,
                                speakText = previewText,
                                voice = voiceId,
                                format = "mp3",
                                sampleRate = sr,
                                speechRateFactor = speed,
                                pitchValue = 0,
                                appkey = appkey,
                                timeoutMs = AppConfig.sysTtsSynthesizeTimeout * 1000L,
                                extraPayload = extraPayload
                            )
                            if (audioBytes != null && audioBytes.isNotEmpty()) {
                                tempFile.writeBytes(audioBytes)
                                withContext(Dispatchers.Main) { playPreviewAudio(context, tempFile) }
                            } else {
                                withContext(Dispatchers.Main) { onToast("试听合成返回空数据") }
                            }
                            return@launch
                        } catch (e: Exception) {
                            retryCount++
                            if (retryCount > AppConfig.ttsRetryCount) {
                                withContext(Dispatchers.Main) {
                                    onToast("试听失败: ${e.localizedMessage}")
                                }
                                return@launch
                            }
                            delay(500)
                        }
                    }
                }

                if (ttsUrl.startsWith("ws://") || ttsUrl.startsWith("wss://")) {
                    val uri = Uri.parse(ttsUrl)
                    val appkey = uri.getQueryParameter("appkey") ?: "WQuVLKMGRo"
                    val voice = voiceId.ifBlank { uri.getQueryParameter("voice") ?: "ICL_5561786db01b" }
                    val format = uri.getQueryParameter("format") ?: "mp3"
                    val sr = uri.getQueryParameter("sampleRate")?.toIntOrNull() ?: 24000
                    val speechRateFactor = ((AppConfig.speechRatePlay + 5) / 50.0).coerceIn(0.5, 1.5)

                    var retryCount = 0
                    while (true) {
                        try {
                            val wsHelper = TtsWebSocketHelper()
                            val audioBytes = wsHelper.maoxiang(
                                wsUrl = ttsUrl,
                                speakText = previewText,
                                voice = voice,
                                format = format,
                                sampleRate = sr,
                                speechRateFactor = speechRateFactor,
                                pitchValue = 0,
                                appkey = appkey,
                                timeoutMs = AppConfig.sysTtsSynthesizeTimeout * 1000L
                            )
                            if (audioBytes != null) {
                                tempFile.writeBytes(audioBytes)
                            } else {
                                throw Exception("WebSocket合成返回空数据")
                            }
                            break
                        } catch (e: Exception) {
                            retryCount++
                            if (retryCount > AppConfig.ttsRetryCount) {
                                throw e
                            }
                            delay(500)
                        }
                    }
                } else {
                    val analyzeUrl = AnalyzeUrl(
                        ttsUrl,
                        speakText = previewText,
                        speakSpeed = AppConfig.speechRatePlay + 5,
                        source = httpTts,
                        readTimeout = AppConfig.sysTtsSynthesizeTimeout * 1000L,
                        coroutineContext = Dispatchers.IO
                    )
                    val response = analyzeUrl.getResponseAwait()

                    response.headers["Content-Type"]?.let { contentTypeHeader ->
                        val contentType = contentTypeHeader.substringBefore(";")
                        if (contentType == "application/json" || contentType.startsWith("text/")) {
                            throw Exception("TTS返回错误: ${response.body?.string() ?: ""}")
                        }
                    }

                    response.body?.byteStream()?.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    } ?: throw Exception("响应体为空")
                }

                withContext(Dispatchers.Main) {
                    playPreviewAudio(context, tempFile)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onToast("试听失败: ${e.localizedMessage}")
                }
            }
        }
    }

    private fun previewWithSystemTts(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewText: String,
        onToast: (String) -> Unit
    ) {
        onToast("正在使用系统TTS合成试听音频...")

        lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val tempFile = File(context.cacheDir, "tts_sys_preview_${System.currentTimeMillis()}.wav")

                val ttsEngine = withContext(Dispatchers.Main) {
                    initSystemTts(context, previewText, tempFile, onToast)
                }

                if (ttsEngine == null) {
                    withContext(Dispatchers.Main) {
                        onToast("系统TTS初始化失败")
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    playPreviewAudio(context, tempFile)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onToast("系统TTS试听失败: ${e.localizedMessage}")
                }
            }
        }
    }

    private suspend fun initSystemTts(
        context: Context,
        text: String,
        outputFile: File,
        onToast: (String) -> Unit
    ): File? {
        val initCompleter = CompletableDeferred<Boolean>()
        val synthCompleter = CompletableDeferred<File?>()
        val utteranceId = "preview_${System.currentTimeMillis()}"
        val sysTtsPkg = AppConfig.sysTtsPackageName

        val tts = if (!sysTtsPkg.isNullOrBlank()) {
            TextToSpeech(context, { status ->
                initCompleter.complete(status == TextToSpeech.SUCCESS)
            }, sysTtsPkg)
        } else {
            TextToSpeech(context) { status ->
                initCompleter.complete(status == TextToSpeech.SUCCESS)
            }
        }

        return try {
            val initSuccess = withTimeout(AppConfig.sysTtsSynthesizeTimeout * 1000L) {
                initCompleter.await()
            }
            if (!initSuccess) {
                return null
            }

            // 设置语速（和App配置保持一致）
            val speechRate = AppConfig.speechRatePlay / 50f
            tts.setSpeechRate(speechRate.coerceIn(0.5f, 2.0f))

            val params = Bundle().apply {
                putInt("stream", android.media.AudioManager.STREAM_MUSIC)
            }

            val result = tts.synthesizeToFile(text, params, outputFile, utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                return null
            }

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (outputFile.exists() && outputFile.length() > 0) {
                        synthCompleter.complete(outputFile)
                    } else {
                        synthCompleter.complete(null)
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    synthCompleter.complete(null)
                }
            })

            withTimeout(AppConfig.sysTtsSynthesizeTimeout * 1000L) {
                synthCompleter.await()
            }
        } catch (_: Exception) {
            null
        } finally {
            tts.shutdown()
        }
    }

    fun playPreviewAudio(context: Context, file: File) {
        previewMediaPlayer?.runCatching {
            stop()
            release()
        }
        previewMediaPlayer = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            prepare()
            start()
            setOnCompletionListener {
                release()
                previewMediaPlayer = null
                kotlin.runCatching { file.delete() }
            }
            setOnErrorListener { _, _, _ ->
                release()
                previewMediaPlayer = null
                kotlin.runCatching { file.delete() }
                true
            }
        }
    }
}
