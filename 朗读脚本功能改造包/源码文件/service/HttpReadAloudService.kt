package io.legado.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
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
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
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
import java.net.ConnectException
import java.net.SocketTimeoutException
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

        /** 对话框打开时调用，请求发送当前字幕（解决暂停/初始进入时字幕空白问题） */
        fun requestCurrentSubtitle() {
            instance?.let { svc ->
                if (svc.currentSubtitles.isNotEmpty() && svc.exoPlayer.duration > 0) {
                    svc.updateSubtitle()
                }
            }
        }
    }

    private val exoPlayer: ExoPlayer by lazy { ExoPlayer.Builder(this).build() }

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
    private var playIndexJob: Job? = null
    private var downloadErrorNo: Int = 0
    private var playErrorNo = 0
    private val downloadTaskActiveLock = Mutex()
    private var silentPlayCheckJob: Job? = null
    private var lastActivateTime: Long = 0L

    // ========== 朗读字幕同步 ==========
    private var subtitleSyncJob: Job? = null
    private var currentSubtitles: List<String> = emptyList()
    private var currentSubtitleCharRanges: List<Pair<Int, Int>> = emptyList()
    private var currentParagraphTotalChars: Int = 0
    private var lastNowSpeak: Int = -1

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 监听器注册保持在主线程较为安全，但 onCreate 本身就在主线程，直接调用也可。
        // 为了稳妥，这里直接调用，避免协程延迟导致初始化未完成。
        exoPlayer.addListener(this)
        BgmManager.init(this)
        if (AppConfig.isBgmEnabled) {
            BgmManager.loadBgmFiles()
        }
        observeEvent<Int>(EventBus.READ_ALOUD_SEEK_PARAGRAPH) { progress ->
            seekToParagraphByProgress(progress)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        downloadTask?.cancel()
        playIndexJob?.cancel()
        silentPlayCheckJob?.cancel()
        stopSubtitleSync()
        
        // 【关键修复】这里必须同步调用 release()
        // 之前使用了 lifecycleScope.launch，导致 Service 销毁时协程被取消，release 未执行，从而导致退出失效
        runCatching {
            exoPlayer.release()
        }
        
        cache.release()
        BgmManager.release()
        Coroutine.async { removeCacheFile() }
    }

    override fun play(affectBgm: Boolean) {
        pageChanged = false
        // play 操作在 UI 线程触发，直接 stop 即可，无需协程包裹
        exoPlayer.stop()
        
        if (!requestFocus()) return
        if (contentList.isEmpty()) {
            AppLog.putDebug("朗读列表为空")
            ReadBook.readAloud()
        } else {
            super.play(affectBgm)
            // 朗读开始前，先激活转发器对应的 TTS 引擎
            activateHttpTtsEngine()
            if (affectBgm && AppConfig.isBgmEnabled && !BgmManager.isPlaying()) {
                startBgm()
            }
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
    private fun activateHttpTtsEngine() {
        val now = System.currentTimeMillis()
        if (now - lastActivateTime < 3000) return
        lastActivateTime = now
        val httpTts = ReadAloud.httpTTS
        if (httpTts != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                TtsEngineActivator.activateFromHttpTts(httpTts)
            }
        }
    }

    override fun playStop(affectBgm: Boolean) {
        // 【关键修复】playStop 必须立即生效，改回直接调用
        exoPlayer.stop()
        playIndexJob?.cancel()
        stopSubtitleSync()
        if (affectBgm) {
            BgmManager.pause()
        }
    }

    private fun updateNextPos() {
        readAloudNumber += contentList[nowSpeak].length + 1 - paragraphStartPos
        paragraphStartPos = 0
        if (nowSpeak < contentList.lastIndex) {
            nowSpeak++
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
        downloadErrorNo = 0
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

    private fun downloadAndPlayAudios() {
        exoPlayer.clearMediaItems()
        downloadTask?.cancel()
        downloadErrorNo = 0
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                ensureActive()
                val httpTts = ReadAloud.httpTTS ?: throw NoStackTraceException("tts is null")
                contentList.forEachIndexed { index, contentText ->
                    ensureActive()
                    if (index < nowSpeak) return@forEachIndexed
                    var text = contentText
                    if (paragraphStartPos > 0 && index == nowSpeak) {
                        text = text.substring(paragraphStartPos)
                    }
                    val currentTitle = textChapter?.chapter?.title ?: ""
                    // 快速路径：后续段落的缓存已准备好时，跳过脚本执行，避免阻塞下载流程
                    if (index > nowSpeak) {
                        val rawFileName = getFileNameHelper(currentTitle, text, index)
                        if (hasSpeakFile(rawFileName)) {
                            AppLog.putDebug("HttpTTS缓存命中(快速路径): $rawFileName")
                            val file = getSpeakFileAsMd5(rawFileName)
                            if (file.exists()) {
                                val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
                                launch(Dispatchers.Main) {
                                    exoPlayer.addMediaItem(mediaItem)
                                }
                            }
                            return@forEachIndexed
                        }
                    }
                    text = applyTtsScripts(text)
                    val fileName = getFileNameHelper(currentTitle, text, index)
                    val speakText = purifySpeakText(text)
                        .replace(AppPattern.notReadAloudRegex.toString(), "")

                    // ==========================================
                    // 核心修复：处理空文本/全符号/章节末尾占位符，防止卡死
                    // ==========================================
                    val isEndMarker = index == contentList.lastIndex
                    if (speakText.isEmpty() || isEndMarker) {
                        if (isEndMarker) {
                            AppLog.putDebug("章节末尾静音占位符，生成静音文件: $fileName")
                        } else {
                            AppLog.putDebug("全符号/空文本，生成静音文件: $fileName")
                        }
                        createSilentSound(fileName)
                    } else if (hasSpeakFile(fileName)) {
                        AppLog.putDebug("TTS缓存命中: $fileName")
                    } else {
                        AppLog.putDebug("TTS下载音频: $fileName")
                        runCatching {
                            val inputStream = getSpeakStream(httpTts, speakText)
                            if (inputStream != null) {
                                createSpeakFile(fileName, inputStream)
                            } else {
                                // 下载失败用静音代替，防止卡死
                                createSilentSound(fileName)
                            }
                        }.onFailure { e ->
                            when (e) {
                                is CancellationException -> Unit
                                else -> {
                                    // 注意：这里是在后台线程，若 pauseReadAloud 内部操作 UI 需小心。
                                    // 但为了解决退出键失效问题，我们优先保证 onDestroy 的同步性。
                                    // 这里使用 runCatching 忽略可能的线程错误，或者 Legado 框架能处理。
                                    lifecycleScope.launch(Dispatchers.Main) { 
                                        pauseReadAloud()
                                    }
                                }
                            }
                            return@execute
                        }
                    }

                    // 即使是静音文件，也必须加入播放列表，保证 index 对齐
                    val file = getSpeakFileAsMd5(fileName)
                    if (file.exists()) {
                        val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
                        // 添加 MediaItem 必须在主线程
                        launch(Dispatchers.Main) {
                            exoPlayer.addMediaItem(mediaItem)
                        }
                    }
                }
                preDownloadAudios(httpTts)
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
                // 注意：getPurifiedChapterContent 内部已根据 AppConfig.readAloudTitle
                // 调用 ContentProcessor.getContent(includeTitle = readAloudTitle)，
                // 标题已在 contentString 中，不要再手动 add。
                if (!contentString.isNullOrEmpty()) {
                    segments.addAll(contentString.split("\n").filter { it.isNotEmpty() })
                }
                segments.forEachIndexed { segIdx, rawText ->
                    currentCoroutineContext().ensureActive()
                    val segmentText = applyTtsScripts(rawText)
                    val fileName = getFileNameHelper(chapter.title, segmentText, segIdx)
                    val speakText = purifySpeakText(segmentText)
                        .replace(AppPattern.notReadAloudRegex.toString(), "")

                    // 修复：预加载同样处理空文本
                    if (speakText.isEmpty()) {
                        if (!hasSpeakFile(fileName)) createSilentSound(fileName)
                    } else if (!hasSpeakFile(fileName)) {
                        AppLog.putDebug("TTS预下载音频: $fileName")
                        runCatching {
                            val inputStream = getSpeakStream(httpTts, speakText)
                            if (inputStream != null) {
                                createSpeakFile(fileName, inputStream)
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
        downloadErrorNo = 0
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                ensureActive()
                val httpTts = ReadAloud.httpTTS ?: throw NoStackTraceException("tts is null")
                val downloaderChannel = Channel<Downloader>(Channel.UNLIMITED)
                launch {
                    for (downloader in downloaderChannel) {
                        kotlin.runCatching { downloader.download(null) }
                    }
                }
                contentList.forEachIndexed { index, contentText ->
                    ensureActive()
                    if (index < nowSpeak) return@forEachIndexed
                    var text = contentText
                    if (paragraphStartPos > 0 && index == nowSpeak) {
                        text = text.substring(paragraphStartPos)
                    }
                    val speakText = purifySpeakText(text)
                        .replace(AppPattern.notReadAloudRegex.toString(), "")
                    val currentTitle = textChapter?.chapter?.title ?: ""
                    val fileName = getFileNameHelper(currentTitle, text, index)

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
                            val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
                            launch(Dispatchers.Main) {
                                exoPlayer.addMediaItem(mediaItem)
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
                preDownloadAudiosStream(httpTts, downloaderChannel)
            }
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
                    val dataSourceFactory = createDataSourceFactory(httpTts, speakText)
                    val downloader = createDownloader(dataSourceFactory, fileName)
                    downloaderChannel.send(downloader)
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

    private suspend fun getSpeakStream(
        httpTts: HttpTTS,
        speakText: String
    ): InputStream? {
        var ttsUrl = httpTts.url

        // 【新增】支持 @js: 前缀：先执行 JS 获取实际 URL 或音频流
        // 这样可以把所有代码放在 url 字段里，包括调用 ws.maoxiang() 完全接管
        if (ttsUrl.startsWith("@js:")) {
            val jsCode = ttsUrl.substring(4)
            var retryCount = 0
            while (true) {
                try {
                    val jsResult = httpTts.evalJS(jsCode) {
                        put("speakText", speakText)
                        put("speechRate", speechRate)
                        put("ws", TtsWebSocketHelper())
                    }
                    when (jsResult) {
                        is InputStream -> return jsResult
                        is ByteArray -> return ByteArrayInputStream(jsResult)
                        is String -> {
                            ttsUrl = jsResult
                            break
                        }
                    }
                } catch (e: Exception) {
                    retryCount++
                    if (retryCount > AppConfig.ttsRetryCount) {
                        AppLog.put("TTS URL(@js:) JS错误，已重试${AppConfig.ttsRetryCount}次: ${e.localizedMessage}", e)
                        if (AppConfig.ttsRetrySkipOnFail) {
                            return ByteArrayInputStream(BaseReadAloudService.generateSilentWavBytes(50))
                        } else {
                            throw NoStackTraceException("TTS合成失败: ${e.localizedMessage}")
                        }
                    }
                    delay(1000)
                }
            }
        }

        if (ttsUrl.startsWith("ws://") || ttsUrl.startsWith("wss://")) {
            // JS 钩子：如果配置了 loginCheckJs，先执行 JS
            // JS 可以返回 InputStream 或 ByteArray 来完全接管 WebSocket 请求和解析
            val jsStr = httpTts.loginCheckJs
            if (!jsStr.isNullOrBlank()) {
                try {
                    val jsResult = httpTts.evalJS(jsStr) {
                        put("speakText", speakText)
                        put("speechRate", speechRate)
                        put("ws", TtsWebSocketHelper())
                    }
                    when (jsResult) {
                        is InputStream -> return jsResult
                        is ByteArray -> return ByteArrayInputStream(jsResult)
                    }
                } catch (e: Exception) {
                    AppLog.put("TTS WebSocket JS错误: ${e.localizedMessage}", e)
                }
            }
            return getSpeakStreamViaWebSocket(httpTts, speakText, ttsUrl)
        }
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
                response.body.byteStream().let { stream ->
                    downloadErrorNo = 0
                    return stream
                }
            } catch (e: Exception) {
                when (e) {
                    is CancellationException -> throw e
                    is ScriptException, is WrappedException -> {
                        AppLog.put("js错误\n${e.localizedMessage}", e, true)
                        e.printOnDebug()
                        throw e
                    }
                    is SocketTimeoutException, is ConnectException -> {
                        // 下载失败，先激活对应的 TTS 引擎再重试
                        activateHttpTtsEngine()
                        downloadErrorNo++
                        if (downloadErrorNo > AppConfig.ttsRetryCount) {
                            val msg = "tts超时或连接错误超过${AppConfig.ttsRetryCount}次\n${e.localizedMessage}"
                            AppLog.put(msg, e, true)
                            if (AppConfig.ttsRetrySkipOnFail) {
                                break
                            } else {
                                throw e
                            }
                        }
                    }
                    else -> {
                        // 下载失败，先激活对应的 TTS 引擎再重试
                        activateHttpTtsEngine()
                        downloadErrorNo++
                        if (downloadErrorNo > 5) {
                            val msg1 = "TTS服务器连续5次错误，已暂停阅读。"
                            AppLog.put(msg1, e, true)
                            throw e
                        } else {
                            AppLog.put("TTS下载音频出错，使用无声音频代替。\n朗读文本：$speakText")
                            break
                        }
                    }
                }
            }
        }
        return null
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

        fun generateId(): String = (1_000_000_000_000L + (Math.random() * 9_000_000_000_000L).toLong()).toString()

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

        val wsClient = okHttpClient.newBuilder()
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

        val timeout = AppConfig.sysTtsSynthesizeTimeout * 1000L
        latch.await(timeout, TimeUnit.MILLISECONDS)
        webSocket.cancel()
        wsClient.dispatcher.cancelAll()

        if (wsError != null) throw wsError!!

        AppLog.putDebug("猫箱合成结束，音频总长度=${audioData.size()}")
        return@withContext if (audioData.size() > 0) {
            ByteArrayInputStream(audioData.toByteArray())
        } else null
    }

    private fun getFileNameHelper(title: String, content: String, index: Int = -1): String {
        val t = title.trim()
        val c = content.trim()
        val ttsUrl = ReadAloud.httpTTS?.url ?: ""
        val indexPart = if (index >= 0) "|$index" else ""
        return MD5Utils.md5Encode16(t) + "_" + MD5Utils.md5Encode16("$ttsUrl-|$speechRate-$indexPart-|$c")
    }

    private fun md5SpeakFileName(content: String, textChapter: TextChapter? = this.textChapter, index: Int = -1): String {
        return getFileNameHelper(textChapter?.chapter?.title ?: "", content, index)
    }

    private fun createSilentSound(fileName: String) {
        val file = createSpeakFile(fileName)
        file.writeBytes(BaseReadAloudService.generateSilentWavBytes(50))
    }

    private fun hasSpeakFile(name: String): Boolean {
        return FileUtils.exist("${ttsFolderPath}$name.mp3")
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

    // ========== 朗读字幕同步 ==========

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
        if (paragraphStartPos > 0) {
            text = text.substring(paragraphStartPos)
        }
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
                // 优化：移除 BgmManager.play()，防止朗读切段时 BGM 顿挫或重置
                upPlayPos()
                startSubtitleSync()
            }
            Player.STATE_ENDED -> {
                playErrorNo = 0
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
        }
        if (mediaItem != null) {
            updateNextPos()
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
        deleteCurrentSpeakFile()
        playErrorNo++
        if (playErrorNo >= 5) {
            pauseReadAloud()
        } else {
            if (exoPlayer.hasNextMediaItem()) {
                exoPlayer.seekToNextMediaItem()
                exoPlayer.prepare()
            } else {
                exoPlayer.clearMediaItems()
                updateNextPos()
            }
        }
    }

    private fun deleteCurrentSpeakFile() {
        if (AppConfig.streamReadAloudAudio) return
        val mediaItem = exoPlayer.currentMediaItem ?: return
        val filePath = mediaItem.localConfiguration?.uri?.path ?: return
        File(filePath).delete()
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

        // 调用父类的章节切换逻辑
        ReadBook.upReadTime()
        AppLog.putDebug("${currentChapter?.title} 朗读结束跳转下一章并朗读")
        resumeReadAloud()
        isAutoSwitchingChapter = true
        val hasNext = ReadBook.moveToNextChapter(true)

        // 发送章节变化事件
        postEvent(EventBus.READ_ALOUD_CHAPTER_CHANGED, ReadBook.curTextChapter?.title ?: "")

        // 如果开启了自动合并开关且当前章节有效，则异步合并音频
        if (AppConfig.autoMergeAudioOnChapterEnd && currentBook != null && currentChapter != null) {
            Coroutine.async {
                io.legado.app.service.HttpTtsAudioCache.mergeChapterAudioAuto(currentBook, currentChapter, currentChapterIndex)
            }
        }

        if (!hasNext) {
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
        if (paragraphStartPos > 0 && index == nowSpeak) {
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
