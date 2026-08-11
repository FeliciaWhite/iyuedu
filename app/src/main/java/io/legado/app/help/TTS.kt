package io.legado.app.help

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.utils.buildMainHandler
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.runBlocking
import splitties.init.appCtx
import java.io.File
import java.util.Locale
import java.util.UUID

class TTS {

    private val handler by lazy { buildMainHandler() }

    private val tag = "legado_tts"

    private val clearTtsRunnable = Runnable { clearTts() }

    private var speakStateListener: SpeakStateListener? = null

    private var textToSpeech: TextToSpeech? = null

    private var text: String? = null

    private var onInit = false

    private val initListener by lazy {
        InitListener()
    }

    private val utteranceListener by lazy {
        TTSUtteranceListener()
    }

    /**
     * TTS 缓存目录
     */
    private val cacheDir: File by lazy {
        File(appCtx.cacheDir, "tts_cache").apply {
            if (!exists()) mkdirs()
        }
    }

    val isSpeaking: Boolean
        get() {
            return textToSpeech?.isSpeaking ?: false
        }

    /**
     * 保存音频文件到缓存
     * @param text 要合成的文本
     * @param engineName TTS 引擎名称，为空则使用默认引擎
     * @return 保存的音频文件，如果失败则返回 null
     */
    @Suppress("unused")
    fun saveAudioToCache(text: String, engineName: String? = null): File? {
        val tts = textToSpeech ?: return null
        if (!onInit) return null

        val hash = text.hashCode().toString()
        val fileName = "${hash}_${System.currentTimeMillis()}.wav"
        val audioFile = File(cacheDir, fileName)

        return try {
            // 使用 synthesizeToFile 方法保存音频
            val params = android.os.Bundle()
            if (!engineName.isNullOrEmpty()) {
                params.putString("engine", engineName)
            }
            params.putInt("stream", android.media.AudioManager.STREAM_MUSIC)

            val utteranceId = UUID.randomUUID().toString()
            val result = tts.synthesizeToFile(text, params, audioFile, utteranceId)

            if (result == TextToSpeech.SUCCESS && audioFile.exists() && audioFile.length() > 0) {
                audioFile
            } else {
                AppLog.put("TTS 保存音频失败: result=$result, fileExists=${audioFile.exists()}")
                null
            }
        } catch (e: Exception) {
            AppLog.put("TTS 保存音频异常", e)
            null
        }
    }

    /**
     * 使用指定的 TTS 引擎合成音频并保存
     * @param text 要合成的文本
     * @param enginePackageName TTS 引擎包名（如 "com.microsoft.tts"）
     * @return 保存的音频文件，如果失败则返回 null
     */
    @Suppress("unused")
    fun synthesizeToFile(text: String, enginePackageName: String?): File? {
        val tts = textToSpeech ?: return null

        val hash = text.hashCode().toString()
        val fileName = "${hash}_${System.currentTimeMillis()}.wav"
        val audioFile = File(cacheDir, fileName)

        return try {
            val params = android.os.Bundle()
            if (!enginePackageName.isNullOrEmpty()) {
                params.putString("engine", enginePackageName)
            }
            params.putInt("stream", android.media.AudioManager.STREAM_MUSIC)
            params.putFloat("volume", 1.0f)
            params.putFloat("pan", 0.0f)

            val utteranceId = UUID.randomUUID().toString()
            val result = tts.synthesizeToFile(text, params, audioFile, utteranceId)

            if (result == TextToSpeech.SUCCESS && audioFile.exists() && audioFile.length() > 0) {
                AppLog.put("TTS 音频已保存: ${audioFile.absolutePath}, 大小: ${audioFile.length()}")
                audioFile
            } else {
                AppLog.put("TTS 保存音频失败: result=$result, fileExists=${audioFile.exists()}, size=${audioFile.length()}")
                null
            }
        } catch (e: Exception) {
            AppLog.put("TTS 保存音频异常", e)
            e.printStackTrace()
            null
        }
    }

    /**
     * 批量保存文本列表的音频
     * @param texts 文本列表
     * @param enginePackageName TTS 引擎包名
     * @return 成功保存的文件列表
     */
    @Suppress("unused")
    fun batchSaveAudio(texts: List<String>, enginePackageName: String?): List<File> {
        val results = mutableListOf<File>()
        for (text in texts) {
            synthesizeToFile(text, enginePackageName)?.let {
                results.add(it)
            }
        }
        return results
    }

    /**
     * 清除 TTS 缓存目录
     */
    @Suppress("unused")
    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    /**
     * 获取缓存目录大小
     */
    @Suppress("unused")
    fun getCacheSize(): Long {
        return cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    @Suppress("unused")
    fun setSpeakStateListener(speakStateListener: SpeakStateListener) {
        this.speakStateListener = speakStateListener
    }

    @Suppress("unused")
    fun removeSpeakStateListener() {
        speakStateListener = null
    }

    @Synchronized
    fun speak(text: String) {
        handler.removeCallbacks(clearTtsRunnable)
        this.text = text
        if (onInit) {
            return
        }
        if (textToSpeech == null) {
            onInit = true
            textToSpeech = TextToSpeech(appCtx, initListener)
        } else {
            addTextToSpeakList()
        }
    }

    fun stop() {
        textToSpeech?.stop()
    }

    @Synchronized
    fun clearTts() {
        textToSpeech?.let { tts ->
            tts.stop()
            tts.shutdown()
        }
        textToSpeech = null
    }

    private fun addTextToSpeakList() {
        val tts = textToSpeech ?: return
        kotlin.runCatching {
            var result = tts.speak("", TextToSpeech.QUEUE_FLUSH, null, null)
            if (result == TextToSpeech.ERROR) {
                clearTts()
                textToSpeech = TextToSpeech(appCtx, initListener)
                return
            }
            text?.splitNotBlank("\n")?.forEachIndexed { i, s ->
                result = tts.speak(s, TextToSpeech.QUEUE_ADD, null, tag + i)
                if (result == TextToSpeech.ERROR) {
                    AppLog.put("tts朗读出错:$text")
                }
            }
        }.onFailure {
            AppLog.put("tts朗读出错", it)
            appCtx.toastOnUi(it.localizedMessage)
        }
    }

    /**
     * 初始化监听
     */
    private inner class InitListener : TextToSpeech.OnInitListener {

        override fun onInit(status: Int) {
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.setOnUtteranceProgressListener(utteranceListener)
                addTextToSpeakList()
            } else {
                appCtx.toastOnUi(R.string.tts_init_failed)
            }
            onInit = false
        }

    }

    /**
     * 朗读监听
     */
    private inner class TTSUtteranceListener : UtteranceProgressListener() {

        override fun onStart(utteranceId: String?) {
            //开始朗读取消释放资源任务
            handler.removeCallbacks(clearTtsRunnable)
            speakStateListener?.onStart()
        }

        override fun onDone(utteranceId: String?) {
            //一分钟没有朗读释放资源
            handler.postDelayed(clearTtsRunnable, 60000L)
            speakStateListener?.onDone()
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            //Deprecated
        }

    }

    interface SpeakStateListener {
        fun onStart()
        fun onDone()
    }
}