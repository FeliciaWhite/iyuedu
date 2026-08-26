package io.legado.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.wav.WavExtractor
import com.script.ScriptException
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.EventBus
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import kotlinx.coroutines.isActive
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.HttpTTS
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.BookHelp
import io.legado.app.help.audio.GainAudioProcessor
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.exoplayer.InputStreamDataSource
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.tts.TtsEngineActivator
import io.legado.app.help.tts.TtsWebSocketHelper
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.utils.AudioConcatUtil
import io.legado.app.utils.AudioConvertUtil
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.SubTitleUtils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.servicePendingIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import org.mozilla.javascript.WrappedException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import splitties.init.appCtx
import android.media.MediaMetadataRetriever
import java.io.File
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/**
 * 在线朗读服务 (最终修复版)
 * 1. [修复] 退出听书键失效 (onDestroy 同步调用)
 * 2. [修复] 全符号/空文本段落卡死 (生成静音文件)
 * 3. [修复] 章节名重复下载 (trim)
 * 4. [优化] BGM 不乱切歌
 */
@SuppressLint("UnsafeOptInUsageError")
class HttpReadAloudService : BaseReadAloudService(), Player.Listener {

    companion object {
        private var instance: HttpReadAloudService? = null

        /** 为 JS 朗读引擎注入通用的 Websocket 构造函数（兼容 Rhino） */
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
                        // 修复 Race Condition：如果事件在注册前已触发，立即补偿回调
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
        """.trimIndent() + "\n"

        /** 对话框打开时调用，请求发送当前字幕（解决暂停/初始进入时字幕空白问题） */
        fun requestCurrentSubtitle() {
            instance?.let { svc ->
                if (svc.currentSubtitles.isNotEmpty() && svc.exoPlayer.duration > 0) {
                    svc.updateSubtitle()
                }
            }
        }
    }

    // 朗读播放音量增益处理器：注入 ExoPlayer 音频管线，解码后 PCM 上做乘性增益 + tanh 软限幅
    private val gainAudioProcessor: GainAudioProcessor by lazy {
        GainAudioProcessor(AppConfig.readAloudVolumeGain)
    }

    private val exoPlayer: ExoPlayer by lazy {
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): androidx.media3.exoplayer.audio.AudioSink? {
                return DefaultAudioSink.Builder(this@HttpReadAloudService)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(gainAudioProcessor))
                    .build()
            }
        }
        ExoPlayer.Builder(this, renderersFactory).build()
    }

    private val ttsFolderPath: String by lazy {
        val baseDir = externalFiles
        baseDir.absolutePath + File.separator + "httpTTS" + File.separator
    }

    private val cache by lazy {
        val baseDir = externalFiles
        SimpleCache(
            File(baseDir, "httpTTS_cache"),
            LeastRecentlyUsedCacheEvictor(128 * 1024 * 1024),
            StandaloneDatabaseProvider(appCtx)
        )
    }

    private val cacheDataSinkFactory by lazy {
        CacheDataSink.Factory().setCache(cache)
    }

    private val loadErrorHandlingPolicy by lazy { CustomLoadErrorHandlingPolicy() }

    private var speechRate: Int = AppConfig.speechRatePlay + 5
    private var downloadTask: Coroutine<*>? = null
    private var preDownloadJob: Job? = null
    private var playIndexJob: Job? = null
    private var playErrorNo = 0
    // 当前段出错是否已重试过，保证同一段只重试一次，避免死循环
    private var itemRetryPending = false
    private var downloadErrorNo: Int = 0
    private val downloadTaskActiveLock = Mutex()
    private var silentPlayCheckJob: Job? = null
    private var lastActivateTime: Long = 0L

    private var subtitleSyncJob: Job? = null
    private var currentSubtitles: List<String> = emptyList()
    private var currentSubtitleCharRanges: List<Pair<Int, Int>> = emptyList()
    private var lastNowSpeak: Int = -1

    /**
     * TTS 音频获取结果
     */
    private sealed class TtsSpeakResult {
        data class Single(
            val stream: InputStream,
            val forceConvertToWav: Boolean = false,
            val postAudioParams: io.legado.app.help.audiobook.PostAudioParams? = null
        ) : TtsSpeakResult()
        data class MultiSegment(
            val segments: List<ByteArray>,
            val ranges: List<Pair<Int, Int>> = emptyList(),
            val forceConvertToWav: Boolean = false,
            val postAudioParams: io.legado.app.help.audiobook.PostAudioParams? = null
        ) : TtsSpeakResult()
        data class Url(val url: String) : TtsSpeakResult()
    }

    // ========== 分段音频信息文件 (.seginfo) ==========

    /**
     * 回退：按原始方式保存 MultiSegment（拼接为一个完整文件，整文件播放）
     * 不再写 .seginfo 分段信息：其记录的是网络分片边界而非真实音频段边界，
     * 按其切段播放会在分片边界处解析失败报错
     */
    private suspend fun fallbackSaveMultiSegment(fileName: String, speakResult: TtsSpeakResult.MultiSegment, index: Int) {
        val out = java.io.ByteArrayOutputStream()
        speakResult.segments.forEach { out.write(it) }
        createSpeakFile(fileName, ByteArrayInputStream(out.toByteArray()))
        val file = getSpeakFileAsMd5(fileName)
        if (file.exists()) {
            val mediaItem = MediaItem.Builder()
                .setMediaId("$index")
                .setUri(Uri.fromFile(file))
                .build()
            withContext(Dispatchers.Main) {
                exoPlayer.addMediaSource(createLocalMediaSource(mediaItem))
            }
        }
    }

    private fun getSegInfoFile(fileName: String): File {
        return File("${ttsFolderPath}$fileName.mp3.seginfo")
    }

    /** 删除历史遗留的 .seginfo 伴随文件（分段播放机制已废弃） */
    private fun removeLegacySegInfo(fileName: String) {
        getSegInfoFile(fileName).delete()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 监听器注册保持在主线程较为安全，但 onCreate 本身就在主线程，直接调用也可。
        // 为了稳妥，这里直接调用，避免协程延迟导致初始化未完成。
        exoPlayer.addListener(this)
        BgmManager.init(this)
        // loadBgmFiles 移到 play() / playByIndex() 的协程中后台执行，
        // 避免 onCreate 主线程同步遍历文件阻塞朗读启动
        observeEvent<Int>(EventBus.READ_ALOUD_SEEK_PARAGRAPH) { progress ->
            seekToParagraphByProgress(progress)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        downloadTask?.cancel()
        playIndexJob?.cancel()
        preDownloadJob?.cancel()
        silentPlayCheckJob?.cancel()
        stopSubtitleSync()
        
        // 【关键修复】这里必须同步调用 release()
        // 之前使用了 lifecycleScope.launch，导致 Service 销毁时协程被取消，release 未执行，从而导致退出失效
        runCatching {
            exoPlayer.release()
        }
        
        cache.release()
        BgmManager.release()
        // 取消自动清理：停止朗读时不应删除已缓存的音频文件
        // Coroutine.async { removeCacheFile() }
    }

    override fun play(affectBgm: Boolean) {
        pageChanged = false
        // play 操作在 UI 线程触发，直接 stop 即可，无需协程包裹
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        
        if (!requestFocus()) return
        if (contentList.isEmpty()) {
            AppLog.putDebug("朗读列表为空")
            ReadBook.readAloud()
        } else {
            resetSoundEffects()
            lastEffectPlayTime = 0L
            super.play(affectBgm)
            // 朗读开始前，先激活转发器对应的 TTS 引擎
            activateHttpTtsEngine()
            // BGM移到onPlaybackStateChanged(STATE_READY)中触发，避免和朗读启动争抢资源
            if (AppConfig.streamReadAloudAudio) {
                downloadAndPlayAudiosStream()
            } else {
                downloadAndPlayAudios()
            }
        }
    }

    /**
     * 激活当前转发器对应的 TTS 引擎
     * 增加冷却期，避免短时间内重复初始化干扰转发器连接
     * 
     * 【修复】改为后台线程异步执行，避免 TextToSpeech 创建/销毁阻塞主线程，
     * 导致切换章节时标题朗读延迟几秒。
     */
    private fun activateHttpTtsEngine(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastActivateTime < 1000) return
        lastActivateTime = now
        val httpTts = ReadAloud.httpTTS
        if (httpTts != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                TtsEngineActivator.activateFromHttpTts(httpTts)
            }
        }
    }

    // 朗读播放音量增益实时生效：由 BaseReadAloudService 事件监听触发
    override fun updateVolumeGain(gain: Float) {
        gainAudioProcessor.setGain(gain)
    }

    override fun playStop(affectBgm: Boolean) {
        // 【关键修复】playStop 必须立即生效，改回直接调用
        exoPlayer.stop()
        playIndexJob?.cancel()
        preDownloadJob?.cancel()
        stopSubtitleSync()
        pauseSoundEffects()
        if (affectBgm) {
            BgmManager.pause()
        }
    }

    private fun updateNextPos() {
        val advancedLen = contentList[nowSpeak].length + 1 - paragraphStartPos
        readAloudNumber += advancedLen
        paragraphStartPos = 0
        if (nowSpeak < contentList.lastIndex) {
            nowSpeak++
            // 段落推进后检查是否触发AI生图切换
            checkAiImageTrigger(advancedLen)
        } else {
            nextChapter()
            return
        }
    }

    /**
     * 根据进度条千分比跳转到对应段落开头，整段播放
     */
    private fun seekToParagraphByProgress(progress: Int) {
        val tc = textChapter ?: return
        if (contentList.isEmpty()) return
        val totalChars = tc.getContent().length.coerceAtLeast(1)
        val targetPos = (totalChars * progress / 1000f).toInt()

        // 在 contentList 中找到目标段落索引
        var acc = 0
        var targetParagraph = 0
        for (i in contentList.indices) {
            val len = contentList[i].length + 1
            if (acc + len > targetPos) {
                targetParagraph = i
                break
            }
            acc += len
        }

        // 取消当前进度跟踪，并重置错误计数
        playIndexJob?.cancel()
        stopSubtitleSync()
        playErrorNo = 0

        // 重置到目标段落开头（整段播放）
        nowSpeak = targetParagraph
        paragraphStartPos = 0

        // 重新计算已读字符累计数
        readAloudNumber = 0
        for (i in 0 until targetParagraph) {
            readAloudNumber += contentList[i].length + 1
        }

        // 同步阅读界面页码
        pageIndex = tc.getPageIndexByCharIndex(readAloudNumber)

        // 立即停止当前音频并重新从目标段落整段播放
        exoPlayer.stop()
        play()
    }

    /**
     * 音频请求失败重试耗尽后，静默重置错误计数器，不中断朗读。
     * 如果播放器有缓存音频则继续播放；如果没有缓存则稍后重试获取。
     */
    private fun restartTtsService() {
        AppLog.put("请求音频连续失败，静默重置错误计数器，不中断朗读")
        downloadErrorNo = 0
        playErrorNo = 0
        itemRetryPending = false
        // 连续失败兜底：重新激活转发器 TTS 引擎，避免引擎被系统回收后一直失败
        activateHttpTtsEngine(force = true)
    }

    private fun downloadAndPlayAudios() {
        exoPlayer.clearMediaItems()
        downloadTask?.cancel()
        downloadTask = execute {
            ensureActive()
            val httpTts = ReadAloud.httpTTS ?: throw NoStackTraceException("tts is null")
            downloadTaskActiveLock.withLock {
                ensureActive()
                val endIndex = if (singleParagraphMode) (nowSpeak + 1).coerceAtMost(contentList.size) else contentList.size
                contentList.forEachIndexed { index, contentText ->
                    ensureActive()
                    if (index < nowSpeak) return@forEachIndexed
                    if (singleParagraphMode && index >= endIndex) return@forEachIndexed
                    var text = contentText
                    if (paragraphStartPos > 0 && paragraphStartPos < text.length && index == nowSpeak) {
                        text = text.substring(paragraphStartPos)
                    }
                    val currentTitle = textChapter?.chapter?.title ?: ""
                    val fileName = getFileNameHelper(currentTitle, text, index)

                    // 检查单文件缓存（统一整文件播放。
                    // 废弃按 .seginfo 切段播放：转发器输出本是完整音频，
                    // seginfo 记录的是 WebSocket 网络分片边界而非真实音频段边界，
                    // 按其切段会在段边界处因子段无 RIFF 头导致 WavExtractor 解析失败报错）
                    if (hasSpeakFile(fileName)) {
                        AppLog.put("HttpTTS缓存命中: $fileName")
                        // 自动清理历史遗留的 .seginfo（旧版分段机制产物，会导致切段播放报错）
                        removeLegacySegInfo(fileName)
                        val file = getSpeakFileAsMd5(fileName)
                        if (file.exists()) {
                            val mediaItem = MediaItem.Builder()
                                .setMediaId("$index")
                                .setUri(Uri.fromFile(file))
                                .build()
                            launch(Dispatchers.Main) {
                                exoPlayer.addMediaSource(createLocalMediaSource(mediaItem))
                            }
                        }
                        return@forEachIndexed
                    }

                    text = applyTtsScripts(text)
                    val speakText = purifySpeakText(text)
                        .replace(AppPattern.notReadAloudRegex, "")
                    
                    val isEndMarker = index == contentList.lastIndex

                    if (speakText.isEmpty() || isEndMarker) {
                        if (isEndMarker) {
                            AppLog.putDebug("章节末尾静音占位符，生成静音文件: $fileName")
                        } else {
                            AppLog.putDebug("全符号/空文本，生成静音文件: $fileName")
                        }
                        createSilentSound(fileName)
                        val file = getSpeakFileAsMd5(fileName)
                        if (file.exists()) {
                            val mediaItem = MediaItem.Builder()
                                .setMediaId("$index")
                                .setUri(Uri.fromFile(file))
                                .build()
                            launch(Dispatchers.Main) {
                                exoPlayer.addMediaSource(createLocalMediaSource(mediaItem))
                            }
                        }
                    } else {
                        AppLog.putDebug("TTS下载音频: $fileName")
                        runCatching {
                            when (val speakResult = getSpeakStreamResult(httpTts, speakText)) {
                                is TtsSpeakResult.MultiSegment -> {
                                    val needSilenceSkip = io.legado.app.help.config.AppConfig.ttsSilenceSkipEnabled
                                    if (AppConfig.convertCacheToWav || speakResult.forceConvertToWav || needSilenceSkip) {
                                        // 统一解码、重采样为 24000Hz WAV，每段合成后立即去除空音频（快节奏）
                                        val wavBytes = io.legado.app.utils.AudioDecodeUtil.mergeSegmentsToWav(
                                            speakResult.segments,
                                            postParams = speakResult.postAudioParams,
                                            silenceSkip = io.legado.app.utils.AudioDecodeUtil.SilenceSkipConfig(
                                                enabled = needSilenceSkip,
                                                minDurationMs = io.legado.app.help.config.AppConfig.ttsSilenceSkipMinMs,
                                            )
                                        )
                                        if (wavBytes != null) {
                                            createSpeakFile(fileName, ByteArrayInputStream(wavBytes))
                                            val file = getSpeakFileAsMd5(fileName)
                                            if (file.exists()) {
                                                val mediaItem = MediaItem.Builder()
                                                    .setMediaId("$index")
                                                    .setUri(Uri.fromFile(file))
                                                    .build()
                                                launch(Dispatchers.Main) {
                                                    exoPlayer.addMediaSource(createLocalMediaSource(mediaItem))
                                                }
                                            }
                                        } else {
                                            fallbackSaveMultiSegment(fileName, speakResult, index)
                                        }
                                    } else {
                                        fallbackSaveMultiSegment(fileName, speakResult, index)
                                    }
                                }
                                is TtsSpeakResult.Single -> {
                                    // 快节奏播放开启时，也需走解码裁剪流程（即使未开转 WAV 缓存）
                                    val needSilenceSkip = io.legado.app.help.config.AppConfig.ttsSilenceSkipEnabled
                                    if (AppConfig.convertCacheToWav || speakResult.forceConvertToWav || needSilenceSkip) {
                                        val bytes = speakResult.stream.readBytes()
                                        val wavBytes = io.legado.app.utils.AudioDecodeUtil.decodeToStandardWav(
                                            bytes,
                                            postParams = speakResult.postAudioParams,
                                            silenceSkip = io.legado.app.utils.AudioDecodeUtil.SilenceSkipConfig(
                                                enabled = needSilenceSkip,
                                                minDurationMs = io.legado.app.help.config.AppConfig.ttsSilenceSkipMinMs,
                                            )
                                        )
                                        if (wavBytes != null) {
                                            createSpeakFile(fileName, ByteArrayInputStream(wavBytes))
                                        } else {
                                            createSpeakFile(fileName, ByteArrayInputStream(bytes))
                                        }
                                    } else {
                                        createSpeakFile(fileName, speakResult.stream)
                                    }
                                    val file = getSpeakFileAsMd5(fileName)
                                    if (file.exists()) {
                                        val mediaItem = MediaItem.Builder()
                                            .setMediaId("$index")
                                            .setUri(Uri.fromFile(file))
                                            .build()
                                        launch(Dispatchers.Main) {
                                            exoPlayer.addMediaSource(createLocalMediaSource(mediaItem))
                                        }
                                    }
                                }
                                is TtsSpeakResult.Url -> {
                        createSilentSound(fileName)
                        val file = getSpeakFileAsMd5(fileName)
                        if (file.exists()) {
                            val mediaItem = MediaItem.Builder()
                                .setMediaId("$index")
                                .setUri(Uri.fromFile(file))
                                .build()
                            launch(Dispatchers.Main) {
                                exoPlayer.addMediaSource(createLocalMediaSource(mediaItem))
                            }
                        }
                                }
                                null -> {
                        createSilentSound(fileName)
                        val file = getSpeakFileAsMd5(fileName)
                        if (file.exists()) {
                            val mediaItem = MediaItem.Builder()
                                .setMediaId("$index")
                                .setUri(Uri.fromFile(file))
                                .build()
                            launch(Dispatchers.Main) {
                                exoPlayer.addMediaSource(createLocalMediaSource(mediaItem))
                            }
                        }
                                }
                            }
                        }.onFailure { e ->
                            when (e) {
                                is CancellationException -> Unit
                                else -> {
                                    if (AppConfig.ttsRetrySkipOnFail) {
                                        createSilentSound(fileName)
                                        val file = getSpeakFileAsMd5(fileName)
                                        if (file.exists()) {
                                            val mediaItem = MediaItem.Builder()
                                                .setMediaId("$index")
                                                .setUri(Uri.fromFile(file))
                                                .build()
                                            launch(Dispatchers.Main) {
                                                exoPlayer.addMediaSource(createLocalMediaSource(mediaItem))
                                            }
                                        }
                                    } else {
                                        lifecycleScope.launch(Dispatchers.Main) {
                                            pauseReadAloud()
                                        }
                                    }
                                }
                            }
                            return@execute
                        }
                    }
                }
            }
            // 预下载放到独立子协程，与主播放协程解耦：
            // 翻页/切章时 downloadTask?.cancel() 只取消主协程，不会卡在 preDownloadAudios 的 evalJS 上。
            // 旧 preDownloadJob 先取消再启动新的，避免多个预下载任务并发争抢 TTS 连接。
            preDownloadJob?.cancel()
            preDownloadJob = lifecycleScope.launch(Dispatchers.IO) {
                try {
                    preDownloadAudios(httpTts)
                } catch (e: CancellationException) {
                    // 正常取消，忽略
                } catch (e: Exception) {
                    AppLog.put("预下载出错\n${e.localizedMessage}", e)
                }
            }
        }.onError { e ->
            AppLog.put("朗读下载出错\n${e.localizedMessage}", e, true)
        }
    }

    private suspend fun preDownloadAudios(httpTts: HttpTTS) {
        val book = ReadBook.book ?: return
        val currentIdx = ReadBook.durChapterIndex
        val limit = AppConfig.audioPreDownloadNum
        for (i in 1..limit) {
            try {
                currentCoroutineContext().ensureActive()
                val targetIndex = currentIdx + i
                val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, targetIndex) ?: break
                val contentString = getPurifiedChapterContent(book, chapter)
                val segments = mutableListOf<String>()
                if (!contentString.isNullOrEmpty()) {
                    segments.addAll(contentString.split("\n").filter { it.isNotEmpty() })
                }
                segments.forEachIndexed { segIdx, rawText ->
                    currentCoroutineContext().ensureActive()
                    val fileName = getFileNameHelper(chapter.title, rawText, segIdx)
                    val segmentText = applyTtsScripts(rawText)
                    val speakText = purifySpeakText(segmentText)
                        .replace(AppPattern.notReadAloudRegex, "")

                    if (speakText.isEmpty()) {
                        if (!hasSpeakFile(fileName)) createSilentSound(fileName)
                    } else if (!hasSpeakFile(fileName)) {
                        AppLog.putDebug("TTS预下载音频: $fileName")
                        runCatching {
                            when (val speakResult = getSpeakStreamResult(httpTts, speakText)) {
                                is TtsSpeakResult.MultiSegment -> {
                                    val needSilenceSkip = io.legado.app.help.config.AppConfig.ttsSilenceSkipEnabled
                                    if (AppConfig.convertCacheToWav || speakResult.forceConvertToWav || needSilenceSkip) {
                                        val wavBytes = io.legado.app.utils.AudioDecodeUtil.mergeSegmentsToWav(
                                            speakResult.segments,
                                            postParams = speakResult.postAudioParams,
                                            silenceSkip = io.legado.app.utils.AudioDecodeUtil.SilenceSkipConfig(
                                                enabled = needSilenceSkip,
                                                minDurationMs = io.legado.app.help.config.AppConfig.ttsSilenceSkipMinMs,
                                            )
                                        )
                                        if (wavBytes != null) {
                                            createSpeakFile(fileName, ByteArrayInputStream(wavBytes))
                                        } else {
                                            val out = java.io.ByteArrayOutputStream()
                                            speakResult.segments.forEach { out.write(it) }
                                            createSpeakFile(fileName, ByteArrayInputStream(out.toByteArray()))
                                        }
                                    } else {
                                        val out = java.io.ByteArrayOutputStream()
                                        speakResult.segments.forEach { out.write(it) }
                                        createSpeakFile(fileName, ByteArrayInputStream(out.toByteArray()))
                                    }
                                }
                                is TtsSpeakResult.Single -> {
                                    // 快节奏播放开启时，也需走解码裁剪流程（即使未开转 WAV 缓存）
                                    val needSilenceSkip = io.legado.app.help.config.AppConfig.ttsSilenceSkipEnabled
                                    if (AppConfig.convertCacheToWav || speakResult.forceConvertToWav || needSilenceSkip) {
                                        val bytes = speakResult.stream.readBytes()
                                        val wavBytes = io.legado.app.utils.AudioDecodeUtil.decodeToStandardWav(
                                            bytes,
                                            postParams = speakResult.postAudioParams,
                                            silenceSkip = io.legado.app.utils.AudioDecodeUtil.SilenceSkipConfig(
                                                enabled = needSilenceSkip,
                                                minDurationMs = io.legado.app.help.config.AppConfig.ttsSilenceSkipMinMs,
                                            )
                                        )
                                        if (wavBytes != null) {
                                            createSpeakFile(fileName, ByteArrayInputStream(wavBytes))
                                        } else {
                                            createSpeakFile(fileName, ByteArrayInputStream(bytes))
                                        }
                                    } else {
                                        createSpeakFile(fileName, speakResult.stream)
                                    }
                                }
                                else -> { }
                            }
                        }.onFailure { e ->
                            when (e) {
                                is CancellationException -> Unit
                                else -> AppLog.put("音频预载失败: ${e.localizedMessage}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                AppLog.put("音频预载异常(第${i}章): ${e.localizedMessage}")
            }
        }
    }

    private fun downloadAndPlayAudiosStream() {
        exoPlayer.clearMediaItems()
        downloadTask?.cancel()
        downloadTask = execute {
            ensureActive()
            val httpTts = ReadAloud.httpTTS ?: throw NoStackTraceException("tts is null")
            val downloaderChannel = Channel<Downloader>(Channel.UNLIMITED)
            launch {
                for (downloader in downloaderChannel) {
                    kotlin.runCatching { downloader.download(null) }
                }
            }
            downloadTaskActiveLock.withLock {
                ensureActive()
                val endIndex = if (singleParagraphMode) (nowSpeak + 1).coerceAtMost(contentList.size) else contentList.size
                contentList.forEachIndexed { index, contentText ->
                    ensureActive()
                    if (index < nowSpeak) return@forEachIndexed
                    if (singleParagraphMode && index >= endIndex) return@forEachIndexed
                    var text = contentText
                    if (paragraphStartPos > 0 && paragraphStartPos < text.length && index == nowSpeak) {
                        text = text.substring(paragraphStartPos)
                    }
                    val currentTitle = textChapter?.chapter?.title ?: ""
                    val fileName = getFileNameHelper(currentTitle, text, index)
                    text = applyTtsScripts(text)
                    val speakText = purifySpeakText(text)
                        .replace(AppPattern.notReadAloudRegex, "")
                    
                    val isEndMarker = index == contentList.lastIndex
                    if (speakText.isEmpty() || isEndMarker) {
                        if (isEndMarker) {
                            AppLog.putDebug("章节末尾静音占位符(streaming)，生成静音文件: $fileName")
                        } else {
                            AppLog.putDebug("全符号/空文本(streaming)，生成静音文件: $fileName")
                        }
                        createSilentSound(fileName)
                        val file = getSpeakFileAsMd5(fileName)
                        if (file.exists()) {
                            val mediaItem = MediaItem.Builder()
                                .setMediaId("$index")
                                .setUri(Uri.fromFile(file))
                                .build()
                            launch(Dispatchers.Main) {
                                exoPlayer.addMediaSource(createLocalMediaSource(mediaItem))
                            }
                        }
                    } else {
                        runCatching {
                            when (val speakResult = getSpeakStreamResult(httpTts, speakText)) {
                                is TtsSpeakResult.MultiSegment -> {
                                    val needSilenceSkip = io.legado.app.help.config.AppConfig.ttsSilenceSkipEnabled
                                    if (AppConfig.convertCacheToWav || speakResult.forceConvertToWav || needSilenceSkip) {
                                        val wavBytes = io.legado.app.utils.AudioDecodeUtil.mergeSegmentsToWav(
                                            speakResult.segments,
                                            postParams = speakResult.postAudioParams,
                                            silenceSkip = io.legado.app.utils.AudioDecodeUtil.SilenceSkipConfig(
                                                enabled = needSilenceSkip,
                                                minDurationMs = io.legado.app.help.config.AppConfig.ttsSilenceSkipMinMs,
                                            )
                                        )
                                        if (wavBytes != null) {
                                            createSpeakFile(fileName, ByteArrayInputStream(wavBytes))
                                            val file = getSpeakFileAsMd5(fileName)
                                            if (file.exists()) {
                                                val mediaItem = MediaItem.Builder()
                                                    .setMediaId("$index")
                                                    .setUri(Uri.fromFile(file))
                                                    .build()
                                                launch(Dispatchers.Main) {
                                                    exoPlayer.addMediaSource(createLocalMediaSource(mediaItem))
                                                }
                                            }
                                        } else {
                                            fallbackSaveMultiSegment(fileName, speakResult, index)
                                        }
                                    } else {
                                        fallbackSaveMultiSegment(fileName, speakResult, index)
                                    }
                                }
                                is TtsSpeakResult.Single -> {
                                    // 快节奏播放开启时，也需走解码裁剪流程（即使未开转 WAV 缓存）
                                    val needSilenceSkip = io.legado.app.help.config.AppConfig.ttsSilenceSkipEnabled
                                    if (speakResult.forceConvertToWav || needSilenceSkip) {
                                        val bytes = speakResult.stream.readBytes()
                                        val wavBytes = io.legado.app.utils.AudioDecodeUtil.decodeToStandardWav(
                                            bytes,
                                            postParams = speakResult.postAudioParams,
                                            silenceSkip = io.legado.app.utils.AudioDecodeUtil.SilenceSkipConfig(
                                                enabled = needSilenceSkip,
                                                minDurationMs = io.legado.app.help.config.AppConfig.ttsSilenceSkipMinMs,
                                            )
                                        )
                                        if (wavBytes != null) {
                                            createSpeakFile(fileName, ByteArrayInputStream(wavBytes))
                                        } else {
                                            createSpeakFile(fileName, ByteArrayInputStream(bytes))
                                        }
                                        val file = getSpeakFileAsMd5(fileName)
                                        if (file.exists()) {
                                            val mediaItem = MediaItem.Builder()
                                                .setMediaId("$index")
                                                .setUri(Uri.fromFile(file))
                                                .build()
                                            launch(Dispatchers.Main) {
                                                exoPlayer.addMediaSource(createLocalMediaSource(mediaItem))
                                            }
                                        }
                                    } else {
                                        val dataSourceFactory = createDataSourceFactory(httpTts, speakText)
                                        val downloader = createDownloader(dataSourceFactory, fileName)
                                        downloaderChannel.send(downloader)
                                        val mediaSource = createMediaSource(dataSourceFactory, fileName)
                                        launch(Dispatchers.Main) {
                                            exoPlayer.addMediaSource(mediaSource)
                                        }
                                    }
                                }
                                is TtsSpeakResult.Url -> {
                                    val dataSourceFactory = createDataSourceFactory(httpTts, speakText)
                                    val downloader = createDownloader(dataSourceFactory, fileName)
                                    downloaderChannel.send(downloader)
                                    val mediaSource = createMediaSource(dataSourceFactory, fileName)
                                    launch(Dispatchers.Main) {
                                        exoPlayer.addMediaSource(mediaSource)
                                    }
                                }
                                null -> {
                                    createSilentSound(fileName)
                                    val file = getSpeakFileAsMd5(fileName)
                                    if (file.exists()) {
                                        val mediaItem = MediaItem.Builder()
                                            .setMediaId("$index")
                                            .setUri(Uri.fromFile(file))
                                            .build()
                                        launch(Dispatchers.Main) {
                                            exoPlayer.addMediaSource(createLocalMediaSource(mediaItem))
                                        }
                                    }
                                }
                            }
                        }.onFailure { e ->
                            when (e) {
                                is CancellationException -> throw e
                                else -> {
                                    if (AppConfig.ttsRetrySkipOnFail) {
                                        createSilentSound(fileName)
                                        val file = getSpeakFileAsMd5(fileName)
                                        if (file.exists()) {
                                            val mediaItem = MediaItem.Builder()
                                                .setMediaId("$index")
                                                .setUri(Uri.fromFile(file))
                                                .build()
                                            launch(Dispatchers.Main) {
                                                exoPlayer.addMediaSource(createLocalMediaSource(mediaItem))
                                            }
                                        }
                                    } else {
                                        lifecycleScope.launch(Dispatchers.Main) {
                                            pauseReadAloud()
                                        }
                                        return@execute
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // 预下载放到独立子协程，与主播放协程解耦（同 downloadAndPlayAudios）
            preDownloadJob?.cancel()
            preDownloadJob = lifecycleScope.launch(Dispatchers.IO) {
                try {
                    preDownloadAudiosStream(httpTts, downloaderChannel)
                } catch (e: CancellationException) {
                    // 正常取消，忽略
                } catch (e: Exception) {
                    AppLog.put("预下载出错\n${e.localizedMessage}", e)
                }
            }
            downloaderChannel.close()
        }.onError { e ->
            AppLog.put("朗读下载出错\n${e.localizedMessage}", e, true)
        }
    }

    private suspend fun preDownloadAudiosStream(
        httpTts: HttpTTS,
        downloaderChannel: Channel<Downloader>
    ) {
        val book = ReadBook.book ?: return
        val currentIdx = ReadBook.durChapterIndex
        val limit = AppConfig.audioPreDownloadNum
        for (i in 1..limit) {
            try {
                currentCoroutineContext().ensureActive()
                val targetIndex = currentIdx + i
                val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, targetIndex) ?: break
                val contentString = getPurifiedChapterContent(book, chapter)
                val segments = mutableListOf<String>()
                // getPurifiedChapterContent 内部已根据 readAloudTitle 处理标题，不再手动添加
                if (!contentString.isNullOrEmpty()) {
                    segments.addAll(contentString.split("\n").filter { it.isNotEmpty() })
                }
                segments.forEachIndexed { segIdx, segmentText ->
                    currentCoroutineContext().ensureActive()
                    val fileName = getFileNameHelper(chapter.title, segmentText, segIdx)
                    val speakText = purifySpeakText(segmentText)
                        .replace(AppPattern.notReadAloudRegex, "")
                    if (hasSpeakFile(fileName)) return@forEachIndexed
                    when (val speakResult = getSpeakStreamResult(httpTts, speakText)) {
                        is TtsSpeakResult.MultiSegment -> {
                            val needSilenceSkip = io.legado.app.help.config.AppConfig.ttsSilenceSkipEnabled
                            if (AppConfig.convertCacheToWav || speakResult.forceConvertToWav || needSilenceSkip) {
                                val wavBytes = io.legado.app.utils.AudioDecodeUtil.mergeSegmentsToWav(
                                    speakResult.segments,
                                    postParams = speakResult.postAudioParams,
                                    silenceSkip = io.legado.app.utils.AudioDecodeUtil.SilenceSkipConfig(
                                        enabled = needSilenceSkip,
                                        minDurationMs = io.legado.app.help.config.AppConfig.ttsSilenceSkipMinMs,
                                    )
                                )
                                if (wavBytes != null) {
                                    createSpeakFile(fileName, ByteArrayInputStream(wavBytes))
                                } else {
                                    val out = java.io.ByteArrayOutputStream()
                                    speakResult.segments.forEach { out.write(it) }
                                    createSpeakFile(fileName, ByteArrayInputStream(out.toByteArray()))
                                }
                            } else {
                                val out = java.io.ByteArrayOutputStream()
                                speakResult.segments.forEach { out.write(it) }
                                createSpeakFile(fileName, ByteArrayInputStream(out.toByteArray()))
                            }
                        }
                        is TtsSpeakResult.Single, is TtsSpeakResult.Url -> {
                            val dataSourceFactory = createDataSourceFactory(httpTts, speakText)
                            val downloader = createDownloader(dataSourceFactory, fileName)
                            downloaderChannel.send(downloader)
                        }
                        null -> { }
                    }
                }
            } catch (e: Exception) {
                AppLog.put("流式预载异常(第${i}章): ${e.localizedMessage}")
            }
        }
    }

    /**
     * 净化朗读文本：删除所有和段评、网页图片相关的标记，只保留纯正文内容。
     *   - <img> 标签 → 直接删除，不保留任何字符
     *   - 段评占位符 ꧁、图片占位符 袮 → 直接删除
     *   - 其他 HTML 标签、HTML 实体 → 一并清理
     * 注意：若书源抓取时已将段评纯文本混入正文（无标签/占位符标识），
     *       代码层面无法自动区分，需靠书源替换规则清理。
     */
    private fun purifySpeakText(text: String): String {
        return text
            // <img> 标签 → 彻底删除，不留空格
            .replace(Regex("<img[^>]*>"), "")
            // 其他 HTML 标签（以字母开头），避免误伤数学/比较符号
            .replace(Regex("<[a-zA-Z][^>]*>|</[a-zA-Z][^>]*>"), "")
            // HTML 实体（如 &nbsp;）
            .replace(Regex("&[a-zA-Z#0-9]+;"), "")
            // 段评/图片排版占位符 → 彻底删除
            .replace(Regex("[袮꧁]"), "")
    }

    private fun getPurifiedChapterContent(book: Book, chapter: BookChapter): String? {
        val rawContent = BookHelp.getContent(book, chapter) ?: return null
        val contentProcessor = io.legado.app.help.book.ContentProcessor.get(book)
        val bookContent = contentProcessor.getContent(
            book = book,
            chapter = chapter,
            content = rawContent,
            includeTitle = AppConfig.readAloudTitle,
            useReplace = AppConfig.replaceEnableDefault && book.getUseReplaceRule(),
            chineseConvert = AppConfig.chineseConverterType != 0,
            reSegment = book.getReSegment()
        )
        return purifySpeakText(bookContent.toString())
    }

    private fun createDataSourceFactory(
        httpTts: HttpTTS,
        speakText: String
    ): CacheDataSource.Factory {
        val upstreamFactory = DataSource.Factory {
            InputStreamDataSource {
                if (speakText.isEmpty()) {
                    null
                } else {
                    kotlin.runCatching {
                        runBlocking(lifecycleScope.coroutineContext[Job]!!) {
                            getSpeakStream(httpTts, speakText)
                        }
                    }.getOrNull()
                } ?: resources.openRawResource(R.raw.silent_sound)
            }
        }
        val factory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheWriteDataSinkFactory(cacheDataSinkFactory)
        return factory
    }

    private fun createDownloader(factory: CacheDataSource.Factory, fileName: String): Downloader {
        val uri = fileName.toUri()
        val request = DownloadRequest.Builder(fileName, uri).build()
        return DefaultDownloaderFactory(factory, okHttpClient.dispatcher.executorService)
            .createDownloader(request)
    }

    /**
     * 探测本地文件头部是否为 WAV（RIFF/WAVE）。
     * 缓存文件扩展名统一为 .mp3，但内容可能是 WAV，需按真实头部识别。
     */
    private fun isWavFile(path: String?): Boolean {
        if (path.isNullOrEmpty()) return false
        return try {
            val header = ByteArray(12)
            val read = java.io.File(path).inputStream().use { it.read(header, 0, 12) }
            read >= 12 &&
                    String(header.copyOfRange(0, 4)) == "RIFF" &&
                    String(header.copyOfRange(8, 12)) == "WAVE"
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 用本地文件构建 MediaSource：按文件真实头部选择解码器，而非依赖扩展名。
     * - WAV 内容（即使扩展名为 .mp3）强制使用 WavExtractor。
     * - 其余走 ExoPlayer 默认探测，按内容识别真实格式。
     */
    private fun createLocalMediaSource(mediaItem: MediaItem): MediaSource {
        val dataSourceFactory = DefaultDataSource.Factory(this)
        val extractorsFactory = if (isWavFile(mediaItem.localConfiguration?.uri?.path)) {
            ExtractorsFactory { arrayOf(WavExtractor()) }
        } else {
            androidx.media3.extractor.DefaultExtractorsFactory()
        }
        return ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
            .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            .createMediaSource(mediaItem)
    }

    private fun createMediaSource(factory: DataSource.Factory, fileName: String): MediaSource {
        val mediaItem = MediaItem.Builder()
            .setUri(fileName)
            .setMediaId(fileName)
            .build()
        return DefaultMediaSourceFactory(this)
            .setDataSourceFactory(factory)
            .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            .createMediaSource(mediaItem)
    }

    /**
     * 新版 TTS 音频获取，支持识别多段音频结果。
     */
    private suspend fun getSpeakStreamResult(
        httpTts: HttpTTS,
        speakText: String
    ): TtsSpeakResult? {
        fun isAudioHeader(data: ByteArray, offset: Int): Boolean {
            if (offset + 12 > data.size) return false
            // WAV: RIFF....WAVE
            if (data[offset] == 'R'.code.toByte() && data[offset + 1] == 'I'.code.toByte() &&
                data[offset + 2] == 'F'.code.toByte() && data[offset + 3] == 'F'.code.toByte() &&
                data[offset + 8] == 'W'.code.toByte() && data[offset + 9] == 'A'.code.toByte() &&
                data[offset + 10] == 'V'.code.toByte() && data[offset + 11] == 'E'.code.toByte()
            ) return true
            // MP3 with ID3
            if (data[offset] == 'I'.code.toByte() && data[offset + 1] == 'D'.code.toByte() &&
                data[offset + 2] == '3'.code.toByte()
            ) return true
            // OGG
            if (data[offset] == 'O'.code.toByte() && data[offset + 1] == 'g'.code.toByte() &&
                data[offset + 2] == 'g'.code.toByte() && data[offset + 3] == 'S'.code.toByte()
            ) return true
            // FLAC
            if (data[offset] == 'f'.code.toByte() && data[offset + 1] == 'L'.code.toByte() &&
                data[offset + 2] == 'a'.code.toByte() && data[offset + 3] == 'C'.code.toByte()
            ) return true
            // M4A: ftyp at offset 4
            if (offset + 8 <= data.size &&
                data[offset + 4] == 'f'.code.toByte() && data[offset + 5] == 't'.code.toByte() &&
                data[offset + 6] == 'y'.code.toByte() && data[offset + 7] == 'p'.code.toByte()
            ) return true
            return false
        }

        fun splitMixedAudioBytes(data: ByteArray): List<ByteArray> {
            if (data.isEmpty()) return emptyList()
            val headers = mutableListOf<Int>()
            var i = 0
            while (i <= data.size - 12) {
                if (isAudioHeader(data, i)) {
                    headers.add(i)
                }
                i++
            }
            if (headers.isEmpty()) return listOf(data)
            // 【严格校验】PCM 语音数据中可能巧合出现假音频头字节模式（如静音段重复字节），
            // 导致单个完整音频被误判为多段、被错误切段播放（在假边界处解析失败报错）。
            // 真正的多段拼接中，每个子音频自带头部且其声明的长度必须与下一头位置/文件尾吻合；
            // 假头几乎不可能满足该约束，据此过滤。
            val verified = headers.filterIndexed { idx, pos ->
                val nextHead = headers.getOrNull(idx + 1) ?: data.size
                when {
                    // WAV: RIFF size 字段声明 8+size 为整段长度
                    data[pos] == 'R'.code.toByte() && pos + 8 <= data.size -> {
                        val riffSize = ((data[pos + 4].toInt() and 0xFF) or
                                ((data[pos + 5].toInt() and 0xFF) shl 8) or
                                ((data[pos + 6].toInt() and 0xFF) shl 16) or
                                ((data[pos + 7].toInt() and 0xFF) shl 24)).toLong() and 0xFFFFFFFFL
                        val declaredEnd = pos + 8 + riffSize
                        // 声明长度需与下一头/文件尾吻合（WAV 允许 1 字节奇数填充）
                        declaredEnd == nextHead.toLong() || declaredEnd == data.size.toLong() ||
                                (declaredEnd + 1 == nextHead.toLong()) || (declaredEnd + 1 == data.size.toLong())
                    }
                    // ID3: syncsafe size 字段声明 10+size 为标签长度，
                    // 其后才是音频帧数据，边界必然落在下一头/文件尾之前，此处不精确校验，
                    // 但要求下一个头不能紧贴在 ID3 头部内（>pos+10）
                    data[pos] == 'I'.code.toByte() && data[pos + 1] == 'D'.code.toByte() -> nextHead > pos + 10
                    // OGG/FLAC 等流格式无法用长度校验：要求下一头位置不能太近（真音频段远大于几十字节）
                    else -> nextHead - pos > 64
                }
            }
            // 校验后只剩 0 或 1 个可信头：不拆分，按单个完整音频处理
            if (verified.size <= 1) return listOf(data)
            val result = mutableListOf<ByteArray>()
            for (idx in verified.indices) {
                val start = verified[idx]
                val end = if (idx + 1 < verified.size) verified[idx + 1] else data.size
                if (end > start) {
                    result.add(data.copyOfRange(start, end))
                }
            }
            return result
        }

        var ttsUrl = httpTts.url
        val wsHelper = TtsWebSocketHelper()

        // 【新增】支持 @js: 前缀：先执行 JS 获取实际 URL 或音频流
        if (ttsUrl.startsWith("@js:")) {
            val jsCode = ttsUrl.substring(4)
            var retryCount = 0
            while (true) {
                try {
                    io.legado.app.help.audiobook.TtsPluginJsBridge.resetSynthesizeFlag()
                    val jsResult = httpTts.evalJS(
                        WEBSOCKET_JS_BRIDGE + jsCode,
                        currentCoroutineContext()
                    ) {
                        put("speakText", speakText)
                        put("speechRate", speechRate)
                        put("ws", wsHelper)
                    }
                    val ttsUsed = io.legado.app.help.audiobook.TtsPluginJsBridge.synthesizeCalled
                    val postParams = if (ttsUsed) {
                        val tag = io.legado.app.help.audiobook.TtsPluginJsBridge.lastVoiceTag
                        if (tag.isNotBlank()) {
                            io.legado.app.help.audiobook.JReadVoiceEngine.resolvePostAudioParams(appCtx, tag)
                        } else null
                    } else null
                    when (jsResult) {
                        is InputStream -> return TtsSpeakResult.Single(jsResult, forceConvertToWav = ttsUsed, postAudioParams = postParams)
                        is ByteArray -> {
                            val segments = wsHelper.lastSegmentedBuffer?.getSegments()
                            if (segments != null && segments.size > 1) {
                                val ranges = wsHelper.lastSegmentedBuffer?.getSegmentRanges() ?: emptyList()
                                return TtsSpeakResult.MultiSegment(segments, ranges, forceConvertToWav = ttsUsed, postAudioParams = postParams)
                            }
                            // 兜底：脚本直接拼接的 ByteArray 也做自动拆分
                            val splitSegments = splitMixedAudioBytes(jsResult)
                            if (splitSegments.size > 1) {
                                AppLog.putDebug("@js: ByteArray 检测到${splitSegments.size}段混合格式音频")
                                val ranges = splitSegments.mapIndexed { idx, seg ->
                                    val offset = splitSegments.take(idx).sumOf { it.size }
                                    offset to seg.size
                                }
                                return TtsSpeakResult.MultiSegment(splitSegments, ranges, forceConvertToWav = ttsUsed, postAudioParams = postParams)
                            }
                            return TtsSpeakResult.Single(ByteArrayInputStream(jsResult), forceConvertToWav = ttsUsed, postAudioParams = postParams)
                        }
                        is String -> {
                            ttsUrl = jsResult
                            break
                        }
                    }
                } catch (e: Exception) {
                    retryCount++
                    if (retryCount > AppConfig.ttsRetryCount) {
                        AppLog.put("TTS URL(@js:) JS错误，已重试${AppConfig.ttsRetryCount}次: ${e.localizedMessage}", e)
                        restartTtsService()
                        if (AppConfig.ttsRetrySkipOnFail) {
                            return TtsSpeakResult.Single(
                                ByteArrayInputStream(BaseReadAloudService.generateSilentWavBytes(50))
                            )
                        } else {
                            throw NoStackTraceException("TTS合成失败: ${e.localizedMessage}")
                        }
                    }
                    delay(500)
                }
            }
        }

        if (ttsUrl.startsWith("ws://") || ttsUrl.startsWith("wss://")) {
            val jsStr = httpTts.loginCheckJs
            if (!jsStr.isNullOrBlank()) {
                try {
                    io.legado.app.help.audiobook.TtsPluginJsBridge.resetSynthesizeFlag()
                    val jsResult = httpTts.evalJS(
                        WEBSOCKET_JS_BRIDGE + jsStr,
                        currentCoroutineContext()
                    ) {
                        put("speakText", speakText)
                        put("speechRate", speechRate)
                        put("ws", wsHelper)
                    }
                    val ttsUsed = io.legado.app.help.audiobook.TtsPluginJsBridge.synthesizeCalled
                    val postParams = if (ttsUsed) {
                        val tag = io.legado.app.help.audiobook.TtsPluginJsBridge.lastVoiceTag
                        if (tag.isNotBlank()) {
                            io.legado.app.help.audiobook.JReadVoiceEngine.resolvePostAudioParams(appCtx, tag)
                        } else null
                    } else null
                    when (jsResult) {
                        is InputStream -> return TtsSpeakResult.Single(jsResult, forceConvertToWav = ttsUsed, postAudioParams = postParams)
                        is ByteArray -> {
                            val segments = wsHelper.lastSegmentedBuffer?.getSegments()
                            if (segments != null && segments.size > 1) {
                                val ranges = wsHelper.lastSegmentedBuffer?.getSegmentRanges() ?: emptyList()
                                return TtsSpeakResult.MultiSegment(segments, ranges, forceConvertToWav = ttsUsed, postAudioParams = postParams)
                            }
                            val splitSegments = splitMixedAudioBytes(jsResult)
                            if (splitSegments.size > 1) {
                                AppLog.putDebug("WS-JS ByteArray 检测到${splitSegments.size}段混合格式音频")
                                val ranges = splitSegments.mapIndexed { idx, seg ->
                                    val offset = splitSegments.take(idx).sumOf { it.size }
                                    offset to seg.size
                                }
                                return TtsSpeakResult.MultiSegment(splitSegments, ranges, forceConvertToWav = ttsUsed, postAudioParams = postParams)
                            }
                            return TtsSpeakResult.Single(ByteArrayInputStream(jsResult), forceConvertToWav = ttsUsed, postAudioParams = postParams)
                        }
                    }
                } catch (e: Exception) {
                    AppLog.put("TTS WebSocket JS错误: ${e.localizedMessage}", e)
                }
            }
            var wsRetryCount = 0
            while (true) {
                try {
                    val stream = getSpeakStreamViaWebSocket(httpTts, speakText, ttsUrl)
                    if (stream != null) {
                        val bytes = stream.readBytes()
                        val splitSegments = splitMixedAudioBytes(bytes)
                        if (splitSegments.size > 1) {
                            AppLog.putDebug("WebSocket流检测到${splitSegments.size}段混合格式音频")
                            val ranges = splitSegments.mapIndexed { idx, seg ->
                                val offset = splitSegments.take(idx).sumOf { it.size }
                                offset to seg.size
                            }
                            return TtsSpeakResult.MultiSegment(splitSegments, ranges)
                        }
                        return TtsSpeakResult.Single(ByteArrayInputStream(bytes))
                    }
                    return null
                } catch (e: Exception) {
                    wsRetryCount++
                    if (wsRetryCount > AppConfig.ttsRetryCount) {
                        AppLog.put("TTS WebSocket 合成失败，已重试${AppConfig.ttsRetryCount}次: ${e.localizedMessage}", e)
                        throw e
                    }
                    // 【修复】重试前等待 1 秒，避免瞬间疯狂重试
                    delay(500)
                }
            }
        }
        downloadErrorNo = 0
        while (true) {
            try {
                val analyzeUrl = AnalyzeUrl(
                    httpTts.url,
                    speakText = speakText,
                    speakSpeed = speechRate,
                    source = httpTts,
                    readTimeout = AppConfig.sysTtsSynthesizeTimeout * 1000L,
                    coroutineContext = currentCoroutineContext()
                )
                var response = analyzeUrl.getResponseAwait()
                currentCoroutineContext().ensureActive()

                // 自动从最终请求URL中提取包名并更新数据库
                autoUpdateTtsPackageNameFromUrl(httpTts, analyzeUrl.url)

                val checkJs = httpTts.loginCheckJs
                if (checkJs?.isNotBlank() == true) {
                    response = analyzeUrl.evalJS(checkJs, response) as Response
                }
                response.headers["Content-Type"]?.let { contentTypeHeader ->
                    val contentType = contentTypeHeader.substringBefore(";")
                    val ct = httpTts.contentType
                    if (contentType == "application/json" || contentType.startsWith("text/")) {
                        throw NoStackTraceException(response.body.string())
                    } else if (ct?.isNotBlank() == true) {
                        if (!contentType.matches(ct.toRegex())) {
                            throw NoStackTraceException(
                                "TTS服务器返回错误：" + response.body.string()
                            )
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                val bytes = response.body.bytes()
                downloadErrorNo = 0
                val segments = splitMixedAudioBytes(bytes)
                if (segments.size > 1) {
                    AppLog.putDebug("HTTP响应检测到${segments.size}段混合格式音频，拆分为MultiSegment处理")
                    val ranges = segments.mapIndexed { idx, seg ->
                        val offset = segments.take(idx).sumOf { it.size }
                        offset to seg.size
                    }
                    return TtsSpeakResult.MultiSegment(segments, ranges)
                }
                return TtsSpeakResult.Single(ByteArrayInputStream(bytes))
            } catch (e: Exception) {
                when (e) {
                    is CancellationException -> throw e
                    is ScriptException, is WrappedException -> {
                        AppLog.put("js错误\n${e.localizedMessage}", e, true)
                        e.printOnDebug()
                        throw e
                    }
                    else -> {
                        // 任何合成失败都尝试重新激活转发器对应的 TTS 引擎
                        activateHttpTtsEngine(force = true)
                        downloadErrorNo++
                        if (downloadErrorNo > AppConfig.ttsRetryCount) {
                            val msg = "TTS合成失败超过${AppConfig.ttsRetryCount}次\n${e.localizedMessage}"
                            AppLog.put(msg, e, true)
                            restartTtsService()
                            if (AppConfig.ttsRetrySkipOnFail) {
                                break
                            } else {
                                throw e
                            }
                        }
                        // 重试前等待 0.5 秒，避免瞬间疯狂重试
                        delay(500)
                    }
                }
            }
        }
        return null
    }

    /**
     * 兼容旧接口：stream 模式下的 DataSource 仍需要 InputStream。
     * 若检测到多段音频，则回退为拼接后的单一流（stream 模式下的折中方案）。
     */
    private suspend fun getSpeakStream(
        httpTts: HttpTTS,
        speakText: String
    ): InputStream? {
        return when (val result = getSpeakStreamResult(httpTts, speakText)) {
            is TtsSpeakResult.Single -> result.stream
            is TtsSpeakResult.MultiSegment -> {
                val out = java.io.ByteArrayOutputStream()
                result.segments.forEach { out.write(it) }
                java.io.ByteArrayInputStream(out.toByteArray())
            }
            is TtsSpeakResult.Url -> null
            null -> null
        }
    }
    
    /**
     * 从最终请求的URL中自动提取TTS包名并更新数据库
     * 识别最后合成音频发送的网址，只要网址里面有包名参数就更新默认包名
     */
    private suspend fun autoUpdateTtsPackageNameFromUrl(httpTts: HttpTTS, finalUrl: String) {
        val extractedPackage = TtsEngineActivator.extractPackageNameFromUrl(finalUrl)
        if (extractedPackage != null && extractedPackage != httpTts.ttsPackageName) {
            // URL中包含包名，且与当前储存的不一致，自动更新
            AppLog.putDebug("从URL自动提取TTS包名: $extractedPackage (原: ${httpTts.ttsPackageName})")
            
            // 更新内存中的对象
            httpTts.ttsPackageName = extractedPackage
            
            // 异步更新数据库
            Coroutine.async {
                appDb.httpTTSDao.get(httpTts.id)?.let { dbTts ->
                    if (dbTts.ttsPackageName != extractedPackage) {
                        dbTts.ttsPackageName = extractedPackage
                        appDb.httpTTSDao.update(dbTts)
                        AppLog.putDebug("已更新数据库中的TTS包名: $extractedPackage")
                    }
                }
            }
        }
    }

    private suspend fun getSpeakStreamViaWebSocket(
        httpTts: HttpTTS,
        speakText: String,
        url: String = httpTts.url
    ): InputStream? = withContext(Dispatchers.IO) {
        // url 已由 getSpeakStream 预处理（包括 @js:），直接使用
        val ttsUrl = url

        val protocol = when {
            httpTts.contentType == "websocket/maoxiang" -> "maoxiang"
            ttsUrl.contains("myparallelstory.com") -> "maoxiang"
            else -> "simple"
        }

        val audioData = ByteArrayOutputStream()
        val latch = CountDownLatch(1)
        var wsError: Throwable? = null

        fun generateId(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(16)

        val uri = Uri.parse(ttsUrl)
        val appkey = uri.getQueryParameter("appkey") ?: "WQuVLKMGRo"
        val voice = uri.getQueryParameter("voice") ?: "ICL_5561786db01b"
        val format = uri.getQueryParameter("format") ?: "mp3"
        val sampleRate = uri.getQueryParameter("sampleRate")?.toIntOrNull() ?: 24000
        val speechRateFactor = (speechRate / 50.0).coerceIn(0.5, 1.5)
        val pitchValue = 0

        val deviceId = generateId()
        val aid = generateId()
        // 保留 HttpTTS URL 中的所有 query 参数（voice/format/sampleRate/appkey 等），
        // 同时覆盖 ssmix/aid/device_id，确保认证信息完整传递到 wsUrl
        val wsUrlBuilder = StringBuilder()
        wsUrlBuilder.append(ttsUrl.substringBefore("?"))
        wsUrlBuilder.append("?")
        val queryNames = uri.queryParameterNames
        var first = true
        for (name in queryNames) {
            if (name == "ssmix" || name == "aid" || name == "device_id") continue
            if (!first) wsUrlBuilder.append("&")
            first = false
            wsUrlBuilder.append(name).append("=").append(uri.getQueryParameter(name))
        }
        if (!first) wsUrlBuilder.append("&")
        wsUrlBuilder.append("ssmix=").append("&aid=").append(aid).append("&device_id=").append(deviceId)
        val wsUrl = wsUrlBuilder.toString()
        AppLog.putDebug("猫箱WebSocket连接: $wsUrl")

        val request = Request.Builder()
            .url(wsUrl)
            .apply {
                httpTts.getHeaderMap(true).forEach { (k, v) -> header(k, v) }
            }
            .build()

        val timeout = AppConfig.sysTtsSynthesizeTimeout * 1000L

        // 【修复】使用完全独立的 OkHttpClient，不共享全局 dispatcher，
        // 确保重试时旧连接与新连接彻底隔离，避免线程池污染。
        val wsClient = OkHttpClient.Builder()
            .connectTimeout(timeout, TimeUnit.MILLISECONDS)
            .readTimeout(timeout, TimeUnit.MILLISECONDS)
            .writeTimeout(timeout, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()

        val webSocket = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                AppLog.putDebug("猫箱WebSocket已连接")
                if (protocol == "maoxiang") {
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
                    }
                    val msg = JSONObject().apply {
                        put("appkey", appkey)
                        put("event", "StartTask")
                        put("namespace", "BidirectionalTTS")
                        put("payload", payload.toString())
                    }
                    AppLog.putDebug("猫箱发送StartTask: $msg")
                    webSocket.send(msg.toString())
                } else {
                    webSocket.send(speakText)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                AppLog.putDebug("猫箱收到text: $text")
                if (protocol == "maoxiang") {
                    try {
                        val data = JSONObject(text)
                        val event = data.optString("event", "")
                        if (event == "TaskStarted") {
                            AppLog.putDebug("猫箱TaskStarted，发送文本和FinishTask")
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
                            AppLog.putDebug("猫箱TaskFinished，音频长度=${audioData.size()}")
                            latch.countDown()
                        } else if (data.has("status_code")) {
                            // 【修复】增加服务端错误码处理（与原插件保持一致）
                            val statusCode = data.optInt("status_code", 20000000)
                            if (statusCode != 20000000) {
                                val errMsg = "猫箱API错误: status_code=$statusCode, " + (data.optString("status_text", "") ?: text)
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
                        AppLog.putDebug("猫箱解析text消息异常: ${e.message}")
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                AppLog.putDebug("猫箱收到binary: ${bytes.size} bytes")
                audioData.write(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                AppLog.putDebug("猫箱WebSocket closing: $code, $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                AppLog.putDebug("猫箱WebSocket closed: $code, $reason")
                latch.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                AppLog.put("猫箱WebSocket错误: ${t.localizedMessage}", t)
                wsError = t
                latch.countDown()
            }
        })

        latch.await(timeout, TimeUnit.MILLISECONDS)

        // 【修复】确保无论成功或失败，都彻底关闭 WebSocket 并销毁独立 Client 的线程池
        webSocket.close(1000, "TTS synthesize end")
        wsClient.dispatcher.cancelAll()
        wsClient.dispatcher.executorService.shutdown()

        if (wsError != null) throw wsError!!

        AppLog.putDebug("猫箱合成结束，音频总长度=${audioData.size()}")
        return@withContext if (audioData.size() > 0) {
            ByteArrayInputStream(audioData.toByteArray())
        } else null
    }

    private fun getFileNameHelper(title: String, content: String, index: Int = -1): String {
        val t = title.trim()
        val c = content.trim()
        val indexPart = if (index >= 0) "|$index" else ""
        return MD5Utils.md5Encode16(t) + "_" + MD5Utils.md5Encode16("$speechRate-$indexPart-|$c")
    }

    /**
     * gengxin 触发处理：删除所有 HttpTTS 缓存（.mp3 和 .seginfo）。
     */
    /**
     * 删除指定段之后的 HttpTTS 缓存（.mp3 和 .seginfo）。
     * @param fromIndex 从此 index 之后（含 fromIndex+1）开始删除；-1 删除全部。
     */
    override fun deleteGengxinCachesAfter(fromIndex: Int) {
        if (fromIndex < 0) {
            // 未朗读：删除全部
            var deleted = 0
            FileUtils.listDirsAndFiles(ttsFolderPath)?.forEach { fileItem ->
                if (fileItem.isFile) {
                    FileUtils.delete(fileItem.absolutePath)
                    deleted++
                }
            }
            AppLog.put("gengxin: 已删除所有 HttpTTS 缓存，共 $deleted 个文件")
            return
        }
        // 正在朗读：以当前朗读音频的生成时间为基准，删除所有生成时间晚于它的缓存（跨章节）
        val title = textChapter?.chapter?.title ?: return
        if (fromIndex > contentList.lastIndex) {
            AppLog.put("gengxin: fromIndex 越界 (fromIndex=$fromIndex)")
            return
        }
        val currentFn = getFileNameHelper(title, contentList[fromIndex], fromIndex)
        val currentMp3 = File("${ttsFolderPath}$currentFn.mp3")
        val baseTime = if (currentMp3.exists()) currentMp3.lastModified() else 0L
        if (baseTime == 0L) {
            // 兜底：取不到基准时间时，退回按 index 删本章后续
            val deleteFrom = fromIndex + 1
            if (deleteFrom > contentList.lastIndex) {
                AppLog.put("gengxin: 无后续段落需删除 (fromIndex=$fromIndex)")
                return
            }
            var deleted = 0
            for (i in deleteFrom..contentList.lastIndex) {
                val fn = getFileNameHelper(title, contentList[i], i)
                val mp3 = File("${ttsFolderPath}$fn.mp3")
                val seg = File("${ttsFolderPath}$fn.mp3.seginfo")
                if (mp3.exists()) { mp3.delete(); deleted++ }
                if (seg.exists()) { seg.delete(); deleted++ }
            }
            AppLog.put("gengxin: 基准时间缺失，已删除本章段落 $deleteFrom..${contentList.lastIndex} 的 HttpTTS 缓存，共 $deleted 个文件")
            return
        }
        // 按生成时间删除：遍历整个缓存目录，删除所有 lastModified() 晚于基准时间的文件
        // 当前正在朗读的音频及其 seginfo 通过文件名排除，确保不被误删
        var deleted = 0
        FileUtils.listDirsAndFiles(ttsFolderPath)?.forEach { fileItem ->
            if (!fileItem.isFile) return@forEach
            val name = fileItem.name
            if (name == "$currentFn.mp3" || name == "$currentFn.mp3.seginfo") return@forEach
            if (fileItem.lastModified() > baseTime) {
                FileUtils.delete(fileItem.absolutePath)
                deleted++
            }
        }
        AppLog.put("gengxin: 已按时间删除 baseTime=$baseTime 之后的 HttpTTS 缓存，共 $deleted 个文件")
    }

    private fun md5SpeakFileName(content: String, textChapter: TextChapter? = this.textChapter, index: Int = -1): String {
        return getFileNameHelper(textChapter?.chapter?.title ?: "", content, index)
    }

    private fun createSilentSound(fileName: String) {
        val file = createSpeakFile(fileName)
        file.writeBytes(BaseReadAloudService.generateSilentWavBytes(50))
    }

    private fun hasSpeakFile(name: String): Boolean {
        return File("${ttsFolderPath}$name.mp3").exists()
    }

    private fun isSilentFile(fileName: String): Boolean {
        val file = File("${ttsFolderPath}$fileName.mp3")
        if (!file.exists()) return false
        return file.length() == BaseReadAloudService.SILENT_SOUND_SIZE
    }

    private fun getSpeakFileAsMd5(name: String): File {
        return File("${ttsFolderPath}$name.mp3")
    }

    private fun createSpeakFile(name: String): File {
        return FileUtils.createFileIfNotExist("${ttsFolderPath}$name.mp3")
    }

    private fun createSpeakFile(name: String, inputStream: InputStream) {
        FileUtils.createFileIfNotExist("${ttsFolderPath}$name.mp3").outputStream().use { out ->
            inputStream.use { input ->
                input.copyTo(out)
            }
        }
    }

    private fun removeCacheFile() {
        val keepTime = AppConfig.audioCacheCleanTime
        if (keepTime == 0L) {
            FileUtils.listDirsAndFiles(ttsFolderPath)?.forEach { fileItem ->
                FileUtils.delete(fileItem.absolutePath)
            }
            return
        }
        val book = ReadBook.book ?: return
        val currentIdx = ReadBook.durChapterIndex
        val limit = AppConfig.audioPreDownloadNum
        val protectedPrefixes = mutableSetOf<String>()
        val currentTitle = this.textChapter?.chapter?.title ?: ""
        if (currentTitle.isNotEmpty()) {
            protectedPrefixes.add(MD5Utils.md5Encode16(currentTitle.trim()))
        }
        runBlocking {
            // 保护上一章，避免回退时被清理
            val prevChapter = appDb.bookChapterDao.getChapter(book.bookUrl, currentIdx - 1)
            if (prevChapter != null) {
                protectedPrefixes.add(MD5Utils.md5Encode16(prevChapter.title.trim()))
            }
            for (i in 1..limit) {
                val nextChapter = appDb.bookChapterDao.getChapter(book.bookUrl, currentIdx + i)
                if (nextChapter != null) {
                    protectedPrefixes.add(MD5Utils.md5Encode16(nextChapter.title.trim()))
                }
            }
        }
        FileUtils.listDirsAndFiles(ttsFolderPath)?.forEach { fileItem ->
            val fName = fileItem.name
            val isProtected = protectedPrefixes.any { fName.startsWith(it) }
            val shouldDelete = if (isProtected) {
                false
            } else {
                (System.currentTimeMillis() - fileItem.lastModified() > keepTime)
            }
            if (shouldDelete) {
                FileUtils.delete(fileItem.absolutePath)
                if (fileItem.name.endsWith(".mp3")) {
                    FileUtils.delete(fileItem.absolutePath + ".seginfo")
                }
            }
        }
    }

    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        kotlin.runCatching {
            playIndexJob?.cancel()
            stopSubtitleSync()
            // 恢复同步调用，确保状态切换及时。若有线程问题，Legado BaseService 通常在主线程调用。
            exoPlayer.pause()
            BgmManager.pause()
        }
    }

    override fun resumeReadAloud() {
        super.resumeReadAloud()
        kotlin.runCatching {
            if (pageChanged) {
                play()
            } else {
                // 恢复同步调用
                exoPlayer.play()
                if (AppConfig.isBgmEnabled && !BgmManager.isPlaying()) {
                    startBgm()
                }
                upPlayPos()
                startSubtitleSync()
            }
        }
    }

    private fun upPlayPos() {
        playIndexJob?.cancel()
        val textChapter = textChapter ?: return
        playIndexJob = lifecycleScope.launch(Dispatchers.Main) {
            upTtsProgress(readAloudNumber + 1)
            if (exoPlayer.duration <= 0) {
                return@launch
            }
            val speakTextLength = contentList[nowSpeak].length
            if (speakTextLength <= 0) {
                return@launch
            }
            val sleep = exoPlayer.duration / speakTextLength
            val start = (speakTextLength * exoPlayer.currentPosition / exoPlayer.duration).toInt()
            for (i in start..contentList[nowSpeak].length) {
                if (pageIndex + 1 < textChapter.pageSize && readAloudNumber + i > textChapter.getReadLength(pageIndex + 1) ) {
                    pageIndex++
                    ReadBook.moveToNextPage()
                    upTtsProgress(readAloudNumber + i)
                }
                delay(sleep)
            }
        }
    }

    override fun upSpeechRate(reset: Boolean) {
        downloadTask?.cancel()
        exoPlayer.stop()
        speechRate = AppConfig.speechRatePlay + 5
        if (AppConfig.streamReadAloudAudio) {
            downloadAndPlayAudiosStream()
        } else {
            downloadAndPlayAudios()
        }
    }

    private fun startSubtitleSync() {
        if (subtitleSyncJob?.isActive == true) return
        subtitleSyncJob = lifecycleScope.launch {
            while (isActive) {
                if (!pause && exoPlayer.isPlaying) {
                    if (nowSpeak != lastNowSpeak) {
                        prepareSubtitles()
                        lastNowSpeak = nowSpeak
                    }
                    updateSubtitle()
                    // 检查并触发音效
                    checkAndPlayEffects(exoPlayer.duration, exoPlayer.currentPosition)
                }
                delay(200)
            }
        }
    }

    private fun stopSubtitleSync() {
        subtitleSyncJob?.cancel()
        subtitleSyncJob = null
        lastNowSpeak = -1
        currentSubtitles = emptyList()
        currentSubtitleCharRanges = emptyList()
        currentParagraphTotalChars = 0
    }

    private fun prepareSubtitles() {
        if (nowSpeak >= contentList.size) return
        var text = contentList[nowSpeak]
        if (paragraphStartPos > 0 && paragraphStartPos < text.length) {
            text = text.substring(paragraphStartPos)
        }
        val effectiveMode = io.legado.app.model.ReadBook.book?.bookUrl?.let {
            AppConfig.getEffectiveSoundEffectMode(it)
        } ?: AppConfig.soundEffectMode
        if (effectiveMode != "off") {
            // 朗读时：对当前朗读文本执行替换规则，提取音效标记
            // 替换结果只用于 MediaPlayer 音效播放，不影响合成文本
            text = applySoundEffectRules(text)
            text = extractSoundEffects(text, nowSpeak)
        }
        // 字幕显示用净化后的文本（去掉 HTML 标签）
        text = purifySpeakText(text)
        
        currentSubtitles = SubTitleUtils.splitToSubtitles(text, 15)
        var acc = 0
        currentSubtitleCharRanges = currentSubtitles.map { subtitle ->
            val chars = SubTitleUtils.countChinese(subtitle)
            val range = acc to (acc + chars)
            acc += chars
            range
        }
        currentParagraphTotalChars = acc
    }

    private fun updateSubtitle() {
        if (currentSubtitles.isEmpty()) return
        // 兜底：若 ExoPlayer 读不出时长，按字数估算总时长，避免字幕完全不更新
        val totalDuration = if (exoPlayer.duration > 0) {
            exoPlayer.duration
        } else {
            val rate = speechRate.coerceAtLeast(1)
            val msPerChar = (1250f / rate).toLong()
            (currentParagraphTotalChars * msPerChar).coerceAtLeast(1)
        }
        if (totalDuration <= 0) return
        val ratio = exoPlayer.currentPosition.toFloat() / totalDuration
        val currentCharCount = (ratio * currentParagraphTotalChars).toInt()

        var currentSubtitle = currentSubtitles.lastOrNull() ?: ""
        for (i in currentSubtitleCharRanges.indices) {
            val (startChars, endChars) = currentSubtitleCharRanges[i]
            if (currentCharCount in startChars until endChars) {
                currentSubtitle = currentSubtitles[i]
                break
            }
        }
        postEvent(EventBus.READ_ALOUD_SUBTITLE, currentSubtitle)

        // 章节整体进度（千分比 0~1000）
        val textChapter = textChapter ?: return
        val totalChapterChars = textChapter.getContent().length.coerceAtLeast(1)
        val paragraphReadChars = (ratio * currentParagraphTotalChars).toInt()
        val currentTotalRead = readAloudNumber + paragraphReadChars
        val progress = (currentTotalRead.toFloat() / totalChapterChars * 1000)
            .toInt()
            .coerceIn(0, 1000)
        postEvent(EventBus.READ_ALOUD_CHAPTER_PROGRESS, progress)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        super.onPlaybackStateChanged(playbackState)
        when (playbackState) {
            Player.STATE_READY -> {
                if (pause) return
                // 同步调用 play
                exoPlayer.play()
                // 朗读开始播放后再启动BGM匹配，避免和朗读启动争抢资源
                if (AppConfig.isBgmEnabled && !BgmManager.isPlaying()) {
                    startBgm()
                }
                upPlayPos()
                startSubtitleSync()
                postEvent(EventBus.READ_ALOUD_AUDIO_CACHE_REFRESH, true)
            }
            Player.STATE_ENDED -> {
                playErrorNo = 0
                if (singleParagraphMode) {
                    singleParagraphMode = false
                    pauseReadAloud()
                    return
                }
                // 重置当前段落音效
                paragraphEffects[nowSpeak]?.forEach { it.triggered = false }
                updateNextPos()
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
            }
            else -> {}
        }
    }

    /**
     * 静音超时检测：防止播放器卡在静音文件
     */
    private fun checkSilentPlay() {
        silentPlayCheckJob?.cancel()
        val currentFileName = exoPlayer.currentMediaItem?.mediaId
        if (currentFileName != null && isSilentFile(currentFileName)) {
            silentPlayCheckJob = lifecycleScope.launch {
                delay(1000L)
                try {
                    if (exoPlayer.playbackState == Player.STATE_READY && exoPlayer.playWhenReady &&
                        exoPlayer.currentMediaItem?.mediaId == currentFileName && isSilentFile(currentFileName)
                    ) {
                        AppLog.putDebug("静音播放超时，强制跳过: $currentFileName")
                        playErrorNo = 0
                        if (exoPlayer.hasNextMediaItem()) {
                            exoPlayer.seekToNextMediaItem()
                        } else {
                            updateNextPos()
                            exoPlayer.stop()
                            exoPlayer.clearMediaItems()
                        }
                    }
                } catch (e: Exception) {
                    // 忽略异常
                }
            }
        } else {
            silentPlayCheckJob?.cancel()
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
            if (!timeline.isEmpty && exoPlayer.playbackState == Player.STATE_IDLE) {
                exoPlayer.prepare()
            }
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            playErrorNo = 0
            // 正常切到下一段，重置当前段重试标志，允许下一段各自重试一次
            itemRetryPending = false
        }
        if (mediaItem != null) {
            val mediaIndex = mediaItem.mediaId.toIntOrNull() ?: -1
            if (mediaIndex >= 0 && mediaIndex < contentList.size) {
                // 记录旧段落索引，用于计算跳过的字数触发 AI 生图切换
                val oldIndex = nowSpeak
                syncToParagraph(mediaIndex)
                // syncToParagraph 路径也需要触发 AI 生图检查（正常播放走此路径）
                if (mediaIndex > oldIndex) {
                    var advancedLen = 0
                    for (i in oldIndex until mediaIndex) {
                        if (i < contentList.size) advancedLen += contentList[i].length + 1
                    }
                    checkAiImageTrigger(advancedLen)
                }
            } else {
                updateNextPos()
            }
            checkSilentPlay()
            // 检测到静音文件（章节末尾50ms占位）时触发淡出过渡
            val currentFileName = mediaItem.mediaId
            if (currentFileName != null && isSilentFile(currentFileName)) {
                postEvent(EventBus.READ_ALOUD_FADE_OUT, true)
            }
        }
        upPlayPos()
    }

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)
        AppLog.put("朗读错误\n${contentList[nowSpeak]}", error)
        playErrorNo++
        if (playErrorNo >= 5) {
            AppLog.put("朗读连续5次错误，静默重置错误计数器(${error.localizedMessage})", error)
            restartTtsService()
        }
        // 同一段出错只重试一次：重建当前段 MediaSource（新解码器实例）并从实际报错位置续播
        // 注意：渲染层致命错误后解码器已失效，必须提供新的 MediaSource 才能继续，否则 play() 会卡死
        val currentItem = exoPlayer.currentMediaItem
        if (!itemRetryPending && currentItem != null) {
            val errorPosition = exoPlayer.currentPosition
            val index = exoPlayer.currentMediaItemIndex
            AppLog.putDebug("朗读出错，从实际报错位置(${errorPosition}ms)重建续播当前段")
            val freshSource = createLocalMediaSource(currentItem)
            exoPlayer.removeMediaItem(index)
            exoPlayer.addMediaSource(index, freshSource)
            exoPlayer.seekTo(index, errorPosition)
            exoPlayer.play()
            itemRetryPending = true
            return
        }
        // 已重试过或无可重试项，跳到下一段
        if (exoPlayer.hasNextMediaItem()) {
            exoPlayer.seekToNextMediaItem()
            exoPlayer.prepare()
        } else {
            exoPlayer.clearMediaItems()
            updateNextPos()
        }
    }

    private fun deleteCurrentSpeakFile() {
        if (AppConfig.streamReadAloudAudio) return
        val mediaItem = exoPlayer.currentMediaItem ?: return
        val filePath = mediaItem.localConfiguration?.uri?.path ?: return
        File(filePath).delete()
        File("$filePath.seginfo").delete()
    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<HttpReadAloudService>(actionStr)
    }

    /**
     * 重写上一章逻辑，发送章节变化事件
     */
    override fun prevChapter() {
        super.prevChapter()
        postEvent(EventBus.READ_ALOUD_CHAPTER_CHANGED, ReadBook.curTextChapter?.title ?: "")
    }

    /**
     * 重写章节切换逻辑，添加自动合并音频功能
     * 当朗读完当前章节时，如果开启了自动合并开关，则自动将当前章节的缓存音频合并为WAV文件
     */
    override fun nextChapter() {
        // 保存当前章节信息（切换前）
        val currentBook = ReadBook.book
        val currentChapter = textChapter?.chapter
        val currentChapterIndex = ReadBook.durChapterIndex

        // 切换章节前重置AI生图状态，新章节 sceneIndex 从0开始，避免跨章 sceneIndex 错位导致缓存/保存错乱
        resetAiImageState()

        // 先切换章节，再触发朗读，避免 resumeReadAloud 时 contentList 还是旧章节内容
        ReadBook.upReadTime()
        AppLog.putDebug("${currentChapter?.title} 朗读结束跳转下一章并朗读")
        isAutoSwitchingChapter = true
        val hasNext = ReadBook.moveToNextChapter(true)

        // 发送章节变化事件
        postEvent(EventBus.READ_ALOUD_CHAPTER_CHANGED, ReadBook.curTextChapter?.title ?: "")

        // 如果开启了自动合并开关且当前章节有效，则异步合并音频
        if (AppConfig.autoMergeAudioOnChapterEnd && currentBook != null && currentChapter != null) {
            Coroutine.async {
                io.legado.app.service.HttpTtsAudioCache.mergeChapterAudioAuto(currentBook, currentChapter, currentChapterIndex, this@HttpReadAloudService)
            }
        }

        if (hasNext) {
            resumeReadAloud()
        } else {
            stopSelf()
        }
    }

    /**
     * 估算段落朗读时长（毫秒）
     * 优先读取缓存音频的准确时长，无缓存时按字数估算
     * 正确处理 paragraphStartPos：当前段落可能从中间位置开始朗读
     */
    override fun estimateParagraphDuration(index: Int): Long {
        if (index < 0 || index >= contentList.size) return 0L
        var text = contentList[index]
        if (text.isEmpty()) return 0L

        // 处理 paragraphStartPos：当前段落可能从中间位置开始朗读
                    if (paragraphStartPos > 0 && paragraphStartPos < text.length && index == nowSpeak) {
                        text = text.substring(paragraphStartPos)
                    }
        if (text.isEmpty()) return 0L

        // 尝试读取缓存音频时长（用处理后的text，与朗读时保持一致）
        val fileName = getFileNameHelper(textChapter?.chapter?.title ?: "", text, -1)
        val file = getSpeakFileAsMd5(fileName)
        if (file.exists() && file.length() > 0) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val duration = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull()
                if (duration != null && duration > 0) return duration
            } catch (_: Exception) {
            } finally {
                retriever.release()
            }
        }

        // 无缓存则按字数估算：语速5约250ms/字，语速15约83ms/字
        val rate = speechRate.coerceAtLeast(1)
        val msPerChar = (1250f / rate).toLong()
        return text.length * msPerChar
    }

    class CustomLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy(0) {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            return C.TIME_UNSET
        }
    }
}
