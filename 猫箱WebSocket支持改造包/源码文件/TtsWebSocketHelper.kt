package io.legado.app.help.tts

import androidx.annotation.Keep
import io.legado.app.constant.AppLog
import io.legado.app.exception.NoStackTraceException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * TTS WebSocket 辅助工具，供 JS 脚本调用。
 * 完全独立的 OkHttpClient，不受 Legado 协程生命周期影响。
 */
@Keep
class TtsWebSocketHelper {

    /**
     * 猫箱 WebSocket TTS 合成（同步阻塞）。
     *
     * 以后增加任何新字段都不需要改源码！把字段写成 JSON 字符串传给 extraPayload 即可。
     * 例：ws.maoxiang(..., '{"context_texts":["男性旁白朗读"]}')
     *
     * @param wsUrl        WebSocket 连接地址（已含 aid/device_id 等参数）
     * @param speakText    要合成的文本
     * @param voice        发音人，如 "zh_female_wenroutaozi_uranus_bigtts"
     * @param format       音频格式，如 "mp3"
     * @param sampleRate   采样率，如 24000
     * @param speechRateFactor 语速因子，如 0.5~1.5
     * @param pitchValue   音调值，如 0
     * @param appkey       appkey，如 "WQuVLKMGRo"
     * @param timeoutMs    超时毫秒
     * @param extraPayload 额外字段（JSON字符串），自动合并到 payload 根对象，以后加字段直接写这里
     * @return 音频 ByteArray，失败返回 null
     */
    fun maoxiang(
        wsUrl: String,
        speakText: String,
        voice: String,
        format: String,
        sampleRate: Int,
        speechRateFactor: Double,
        pitchValue: Int,
        appkey: String,
        timeoutMs: Long,
        extraPayload: String? = null
    ): ByteArray? {
        AppLog.putDebug("TtsWebSocketHelper 开始合成: wsUrl=$wsUrl, voice=$voice, text=${speakText.take(20)}...")
        val audioData = ByteArrayOutputStream()
        val latch = CountDownLatch(1)
        var wsError: Throwable? = null

        // 独立的 OkHttpClient，不受外部协程/下载任务影响
        val client = OkHttpClient.Builder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder().url(wsUrl).build()

        val webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                AppLog.putDebug("TtsWebSocketHelper WebSocket 已连接")
                val payload = JSONObject().apply {
                    put("audio_config", JSONObject().apply {
                        put("format", format)
                        put("sample_rate", sampleRate)
                    })
                    put("extra", JSONObject().apply {
                        put("post_process", JSONObject().apply {
                            put("pitch", pitchValue)
                            put("speech_rate", speechRateFactor)
                        })
                    })
                    put("speaker", voice)
                    // 【通用扩展】解析 extraPayload JSON，把字段合并到 payload 根对象
                    if (!extraPayload.isNullOrBlank()) {
                        try {
                            val extra = JSONObject(extraPayload)
                            extra.keys().forEach { key ->
                                put(key, extra.get(key))
                            }
                            AppLog.putDebug("TtsWebSocketHelper extraPayload 已合并: $extraPayload")
                        } catch (e: Exception) {
                            AppLog.put("TtsWebSocketHelper extraPayload 解析失败: ${e.localizedMessage}")
                        }
                    }
                }
                val msg = JSONObject().apply {
                    put("appkey", appkey)
                    put("event", "StartTask")
                    put("namespace", "BidirectionalTTS")
                    put("payload", payload.toString())
                }
                AppLog.putDebug("TtsWebSocketHelper 发送 StartTask")
                webSocket.send(msg.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                AppLog.putDebug("TtsWebSocketHelper 收到 text: ${text.take(200)}")
                try {
                    val data = JSONObject(text)
                    val event = data.optString("event", "")
                    if (event == "TaskStarted") {
                        val textPayload = JSONObject().apply {
                            put("payload", JSONObject().apply {
                                put("text", speakText)
                            }.toString())
                        }
                        webSocket.send(textPayload.toString())
                        val finishMsg = JSONObject().apply {
                            put("appkey", appkey)
                            put("event", "FinishTask")
                            put("namespace", "BidirectionalTTS")
                        }
                        webSocket.send(finishMsg.toString())
                    } else if (event == "TaskFinished") {
                        AppLog.putDebug("TtsWebSocketHelper TaskFinished，音频长度=${audioData.size()}")
                        latch.countDown()
                    } else if (data.has("status_code")) {
                        val statusCode = data.optInt("status_code", 20000000)
                        if (statusCode != 20000000) {
                            val errMsg = "猫箱API错误: status_code=$statusCode, " + data.optString("status_text", "")
                            AppLog.put(errMsg)
                            wsError = NoStackTraceException(errMsg)
                            latch.countDown()
                        }
                    } else if (data.optInt("type", -1) == 3 && data.has("buffer")) {
                        val buffer = data.getString("buffer")
                        val decoded = android.util.Base64.decode(buffer, android.util.Base64.DEFAULT)
                        if (decoded != null && decoded.isNotEmpty()) {
                            audioData.write(decoded)
                        }
                    } else if (data.has("buffer")) {
                        val buffer = data.getString("buffer")
                        val decoded = android.util.Base64.decode(buffer, android.util.Base64.DEFAULT)
                        if (decoded != null && decoded.isNotEmpty()) {
                            audioData.write(decoded)
                        }
                    }
                } catch (e: Exception) {
                    AppLog.putDebug("TtsWebSocketHelper 解析异常: ${e.message}")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                AppLog.putDebug("TtsWebSocketHelper 收到 binary: ${bytes.size} bytes")
                audioData.write(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                AppLog.putDebug("TtsWebSocketHelper WebSocket closed: $code")
                latch.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                AppLog.put("TtsWebSocketHelper WebSocket 错误: ${t.localizedMessage}", t)
                wsError = t
                latch.countDown()
            }
        })

        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        webSocket.cancel()
        client.dispatcher.executorService.shutdown()

        if (wsError != null) throw wsError!!

        AppLog.putDebug("TtsWebSocketHelper 合成结束，音频总长度=${audioData.size()}")
        return if (audioData.size() > 0) audioData.toByteArray() else null
    }

    /**
     * 供 JS 脚本创建 ByteArrayOutputStream，用于拼接多段音频
     */
    fun newBuffer(): java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()

    /**
     * 供 JS 脚本合并多个音频 byte[]
     */
    fun mergeAudio(vararg audios: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        audios.forEach { out.write(it) }
        return out.toByteArray()
    }
}
