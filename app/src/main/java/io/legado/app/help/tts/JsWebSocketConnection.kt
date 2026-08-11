package io.legado.app.help.tts

import androidx.annotation.Keep
import io.legado.app.constant.AppLog
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import java.util.concurrent.TimeUnit

/**
 * 供 JS 朗读引擎使用的通用 WebSocket 连接。
 * 通过 ws._connectNative(url, headers) 在 Rhino JS 环境中创建。
 */
@Keep
class JsWebSocketConnection(
    url: String,
    headers: Map<String, String>?
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val callbacks = mutableMapOf<String, Any?>()

    @Volatile
    var readyState: Int = 0 // 0=CONNECTING, 1=OPEN, 2=CLOSING, 3=CLOSED

    init {
        val request = Request.Builder().url(url).apply {
            headers?.forEach { (k, v) -> header(k, v) }
        }.build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                readyState = 1
                emit("open")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                emit("text", text)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                emit("message", bytes.toByteArray())
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                readyState = 2
                emit("close", code, reason)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                readyState = 3
                emit("close", code, reason)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                readyState = 3
                emit("error", t.localizedMessage ?: "WebSocket error")
            }
        })
    }

    fun on(event: String, callback: Any?) {
        callbacks[event] = callback
    }

    fun send(data: String) {
        webSocket?.send(data)
    }

    fun close(code: Int, reason: String) {
        webSocket?.close(code, reason)
        client.dispatcher.cancelAll()
    }

    private fun emit(event: String, vararg args: Any?) {
        val cb = callbacks[event] ?: return
        val argArray = args.toList().toTypedArray()
        when (cb) {
            is Function -> {
                val cx = Context.enter()
                try {
                    if (cx is com.script.rhino.RhinoContext) {
                        cx.allowScriptRun = true
                    }
                    val scope = cb.parentScope
                    cb.call(cx, scope, scope, argArray)
                } catch (e: Exception) {
                    AppLog.put("JsWebSocket 回调异常[$event]: ${e.localizedMessage}", e)
                } finally {
                    Context.exit()
                }
            }
            else -> {
                kotlin.runCatching {
                    val callMethod = cb.javaClass.getMethod(
                        "call",
                        Context::class.java,
                        org.mozilla.javascript.Scriptable::class.java,
                        org.mozilla.javascript.Scriptable::class.java,
                        Array<Any>::class.java
                    )
                    val cx = Context.enter()
                    try {
                        if (cx is com.script.rhino.RhinoContext) {
                            cx.allowScriptRun = true
                        }
                        callMethod.invoke(cb, cx, null, null, argArray)
                    } finally {
                        Context.exit()
                    }
                }
            }
        }
    }
}
