@file:Suppress("DEPRECATION")

package io.legado.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.PowerManager
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.annotation.CallSuper
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import io.legado.app.R
import io.legado.app.base.BaseService
import com.script.ScriptBindings
import com.script.buildScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.constant.PreferKey
import io.legado.app.constant.Status
import io.legado.app.help.CacheManager
import io.legado.app.help.JsExtensions
import io.legado.app.help.MediaHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.http.CookieStore
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.receiver.MediaButtonReceiver
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.data.appDb
import io.legado.app.utils.LogUtils
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.observeEvent
import io.legado.app.utils.observeSharedPreferences
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import splitties.systemservices.audioManager
import splitties.systemservices.notificationManager
import splitties.systemservices.powerManager
import splitties.systemservices.telephonyManager
import splitties.systemservices.wifiManager

/**
 * 朗读服务
 */
abstract class BaseReadAloudService : BaseService(),
    AudioManager.OnAudioFocusChangeListener {

    companion object {
        @JvmStatic
        var isRun = false
            private set

        @JvmStatic
        var pause = true
            private set

        @JvmStatic
        var timeMinute: Int = 0
            private set

        fun isPlay(): Boolean {
            return isRun && !pause
        }

        private const val TAG = "BaseReadAloudService"

        /** 0.05 秒静音 WAV 文件大小（8000Hz 单声道 16bit） */
        const val SILENT_SOUND_SIZE = 844L

        /**
         * 生成指定时长的静音 WAV 文件字节数组
         * 默认 50ms，8000Hz 单声道 16bit PCM
         */
        fun generateSilentWavBytes(durationMs: Int = 50): ByteArray {
            val sampleRate = 8000
            val numChannels = 1
            val bitsPerSample = 16
            val byteRate = sampleRate * numChannels * bitsPerSample / 8
            val blockAlign = numChannels * bitsPerSample / 8
            val numSamples = sampleRate * durationMs / 1000
            val dataSize = numSamples * blockAlign
            val totalSize = 36 + dataSize

            val buffer = java.nio.ByteBuffer.allocate(44 + dataSize)
            buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)

            // RIFF chunk descriptor
            buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
            buffer.putInt(totalSize)
            buffer.put("WAVE".toByteArray(Charsets.US_ASCII))

            // fmt sub-chunk
            buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
            buffer.putInt(16)           // Subchunk1Size
            buffer.putShort(1)          // AudioFormat = PCM
            buffer.putShort(numChannels.toShort())
            buffer.putInt(sampleRate)
            buffer.putInt(byteRate)
            buffer.putShort(blockAlign.toShort())
            buffer.putShort(bitsPerSample.toShort())

            // data sub-chunk
            buffer.put("data".toByteArray(Charsets.US_ASCII))
            buffer.putInt(dataSize)

            // PCM silence data (all zeros)
            repeat(dataSize) { buffer.put(0) }

            return buffer.array()
        }

    }

    private val useWakeLock = appCtx.getPrefBoolean(PreferKey.readAloudWakeLock, false)
    private val wakeLock by lazy {
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "legado:ReadAloudService")
            .apply {
                this.setReferenceCounted(false)
            }
    }
    private val wifiLock by lazy {
        @Suppress("DEPRECATION")
        wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "legado:AudioPlayService")
            ?.apply {
                setReferenceCounted(false)
            }
    }
    private val mFocusRequest: AudioFocusRequestCompat by lazy {
        MediaHelp.buildAudioFocusRequestCompat(this)
    }
    private val mediaSessionCompat: MediaSessionCompat by lazy {
        MediaSessionCompat(this, "readAloud")
    }
    private val phoneStateListener by lazy {
        ReadAloudPhoneStateListener()
    }
    internal var contentList = emptyList<String>()
    internal var nowSpeak: Int = 0
    internal var readAloudNumber: Int = 0
    internal var textChapter: TextChapter? = null
    internal var pageIndex = 0
    private var needResumeOnAudioFocusGain = false
    private var needResumeOnCallStateIdle = false
    private var registeredPhoneStateListener = false
    private var dsJob: Job? = null
    private var upNotificationJob: Coroutine<*>? = null
    private var cover: Bitmap =
        BitmapFactory.decodeResource(appCtx.resources, R.drawable.icon_read_book)
    var pageChanged = false
    private var toLast = false
    var paragraphStartPos = 0
    var readAloudByPage = false
        private set
    protected var isAutoSwitchingChapter = false

    // AI预分析下一首BGM
    internal var pendingNextBgmIndex: Int = -1
    private var lastBgmAnalysisTime: Long = 0L
    private var bgmAnalysisJob: Job? = null

    /**
     * 估算某段朗读的音频时长（毫秒）
     * 子类根据实际朗读方式实现（缓存音频时长或字数估算）
     */
    abstract fun estimateParagraphDuration(index: Int): Long

    private val ttsJsExtensions by lazy {
        object : JsExtensions {
            override fun getSource() = ReadAloud.httpTTS
            override fun getTag() = "TTS"
        }
    }

    /**
     * 应用朗读脚本，按顺序执行启用的JS脚本，修改待朗读文本。
     * 脚本通过变量 text 获取当前文本，通过 java 调用 ajax、readFile、writeTxtFile 等扩展方法，
     * 返回值为修改后的文本。
     */
    protected fun applyTtsScripts(text: String): String {
        if (text.isEmpty() || text == " ") return text
        val scripts = try {
            appDb.ttsScriptDao.all.filter { it.isEnabled }.sortedBy { it.order }
        } catch (e: Exception) {
            return text
        }
        if (scripts.isEmpty()) return text
        var result = text
        scripts.forEach { script ->
            if (script.code.isBlank()) return@forEach
            try {
                val jsResult = RhinoScriptEngine.run {
                    val bindings = buildScriptBindings { bindings ->
                        bindings["java"] = ttsJsExtensions
                        bindings["cache"] = CacheManager
                        bindings["cookie"] = CookieStore
                        bindings["text"] = result
                        bindings["source"] = ReadAloud.httpTTS
                        bindings["speakText"] = text
                    }
                    eval(script.code, bindings)
                }
                if (jsResult != null) {
                    result = jsResult.toString()
                }
            } catch (e: Exception) {
                AppLog.put("朗读脚本[${script.name}]执行错误", e)
            }
        }
        return result
    }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY == intent.action) {
                pauseReadAloud()
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        isRun = true
        pause = false
        observeLiveBus()
        initMediaSession()
        initBroadcastReceiver()
        initPhoneStateListener()
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PLAYING)
        setTimer(AppConfig.ttsTimer)
        if (AppConfig.ttsTimer > 0) {
            toastOnUi("朗读定时 ${AppConfig.ttsTimer} 分钟")
        }
        execute {
            ImageLoader
                .loadBitmap(this@BaseReadAloudService, ReadBook.book?.getDisplayCover())
                .submit()
                .get()
        }.onSuccess {
            if (it.width > 16 && it.height > 16) {
                cover = it
                upReadAloudNotification()
            }
        }
        // 注册BGM回调：提供下一首索引，BGM播放/切换后触发预分析
        BgmManager.getNextBgmIndex = { pendingNextBgmIndex }
        BgmManager.onBgmStarted = { 
            // 更新BgmManager中的预存索引
            BgmManager.pendingNextBgmIndex = pendingNextBgmIndex
            triggerBgmAnalysis() 
        }
    }

    fun observeLiveBus() {
        observeEvent<Bundle>(EventBus.READ_ALOUD_PLAY) {
            val play = it.getBoolean("play")
            val pageIndex = it.getInt("pageIndex")
            val startPos = it.getInt("startPos")
            newReadAloud(play, pageIndex, startPos)
        }
        observeSharedPreferences { _, key ->
            when (key) {
                PreferKey.ignoreAudioFocus,
                PreferKey.pauseReadAloudWhilePhoneCalls -> {
                    initPhoneStateListener()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (useWakeLock) {
            wakeLock.release()
            wifiLock?.release()
        }
        isRun = false
        pause = true
        abandonFocus()
        unregisterReceiver(broadcastReceiver)
        postEvent(EventBus.ALOUD_STATE, Status.STOP)
        notificationManager.cancel(NotificationId.ReadAloudService)
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_STOPPED)
        mediaSessionCompat.release()
        ReadBook.uploadProgress()
        unregisterPhoneStateListener(phoneStateListener)
        upNotificationJob?.invokeOnCompletion {
            notificationManager.cancel(NotificationId.ReadAloudService)
        }
        ReadAloudFloatService.stop(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.play -> newReadAloud(
                intent.getBooleanExtra("play", true),
                intent.getIntExtra("pageIndex", ReadBook.durPageIndex),
                intent.getIntExtra("startPos", 0)
            )

            IntentAction.pause -> pauseReadAloud()
            IntentAction.resume -> resumeReadAloud()
            IntentAction.upTtsSpeechRate -> upSpeechRate(true)
            IntentAction.prevParagraph -> prevP()
            IntentAction.nextParagraph -> nextP()
            IntentAction.prev -> prevChapter()
            IntentAction.next -> nextChapter()
            IntentAction.addTimer -> addTimer()
            IntentAction.setTimer -> setTimer(intent.getIntExtra("minute", 0))
            IntentAction.stop -> stopSelf()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun newReadAloud(play: Boolean, pageIndex: Int, startPos: Int) {
        execute(executeContext = Dispatchers.IO) {
            val textChapter = ReadBook.curTextChapter ?: return@execute
            if (!textChapter.isCompleted) {
                AppLog.putDebug("newReadAloud: TextChapter 排版未完成，等待排版完成后再更新状态")
                return@execute
            }
            this@BaseReadAloudService.pageIndex = pageIndex
            this@BaseReadAloudService.textChapter = textChapter
            readAloudNumber = textChapter.getReadLength(pageIndex) + startPos
            ReadBook.saveTtsTextJson()
            readAloudByPage = getPrefBoolean(PreferKey.readAloudByPage)
            contentList = textChapter.getNeedReadAloud(0, readAloudByPage, 0)
                .split("\n")
                .filter { it.isNotEmpty() }
                .toMutableList()
                .apply { add(" ") }
            var pos = startPos
            val page = textChapter.getPage(pageIndex)!!
            if (pos > 0) {
                for (paragraph in page.paragraphs) {
                    val tmp = pos - paragraph.length - 1
                    if (tmp < 0) break
                    pos = tmp
                }
            }
            nowSpeak = textChapter.getParagraphNum(readAloudNumber + 1, readAloudByPage) - 1
            if (!readAloudByPage && startPos == 0 && !toLast) {
                pos = page.chapterPosition -
                        textChapter.paragraphs[nowSpeak].chapterPosition
            }
            if (toLast) {
                toLast = false
                readAloudNumber = textChapter.getLastParagraphPosition()
                nowSpeak = (contentList.lastIndex - 1).coerceAtLeast(0)
                if (page.paragraphs.size == 1) {
                    pos = page.chapterPosition -
                            textChapter.paragraphs[nowSpeak].chapterPosition
                }
            }
            paragraphStartPos = pos
            launch(Dispatchers.Main) {
                if (play) {
                    LogUtils.d(TAG, "nowSpeak=$nowSpeak")
                    // 简化逻辑：nowSpeak==0直接朗读，nowSpeak!=0跳过当前段
                    if (!AppConfig.readAloudStartFromFirst && !toLast && nowSpeak < contentList.size) {
                        if (nowSpeak != 0) {
                            LogUtils.d(TAG, "nowSpeak!=0，跳过当前段")
                            moveToNextParagraph()
                        } else {
                            LogUtils.d(TAG, "nowSpeak==0，直接朗读")
                        }
                    }
                    // 开启状态：下一段→上一段→朗读（自动切换章节时跳过）
                    val skipAdjust = isAutoSwitchingChapter
                    isAutoSwitchingChapter = false
                    if (AppConfig.readAloudStartFromFirst && !toLast && !skipAdjust) {
                        moveToNextParagraph()
                        moveToPrevParagraph()
                    }
                    upTtsProgress(readAloudNumber + 1)
                    play()
                } else {
                    pageChanged = true
                }
            }
        }.onError {
            AppLog.put("启动朗读出错\n${it.localizedMessage}", it, true)
        }
    }

    @SuppressLint("WakelockTimeout")
    open fun play(affectBgm: Boolean = true) {
        if (useWakeLock) {
            wakeLock.acquire()
            wifiLock?.acquire()
        }
        isRun = true
        pause = false
        needResumeOnAudioFocusGain = false
        needResumeOnCallStateIdle = false
        upReadAloudNotification()
        postEvent(EventBus.ALOUD_STATE, Status.PLAY)
        upFloatWindow()
    }

    abstract fun playStop(affectBgm: Boolean = true)

    @CallSuper
    open fun pauseReadAloud(abandonFocus: Boolean = true) {
        if (useWakeLock) {
            wakeLock.release()
            wifiLock?.release()
        }
        pause = true
        if (abandonFocus) {
            abandonFocus()
        }
        upReadAloudNotification()
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PAUSED)
        postEvent(EventBus.ALOUD_STATE, Status.PAUSE)
        ReadBook.uploadProgress()
        doDs()
    }

    @SuppressLint("WakelockTimeout")
    @CallSuper
    open fun resumeReadAloud() {
        resumeReadAloudInternal()
    }

    private fun resumeReadAloudInternal() {
        pause = false
        needResumeOnAudioFocusGain = false
        needResumeOnCallStateIdle = false
        upReadAloudNotification()
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PLAYING)
        postEvent(EventBus.ALOUD_STATE, Status.PLAY)
    }

    abstract fun upSpeechRate(reset: Boolean = false)

    fun upTtsProgress(progress: Int) {
        postEvent(EventBus.TTS_PROGRESS, progress)
    }

    private fun prevP() {
        if (nowSpeak > 0) {
            // 手动切换上一段时，不影响背景音乐
            stopReadAloudInternal(false)
            do {
                nowSpeak--
                readAloudNumber -= contentList[nowSpeak].length + 1 + paragraphStartPos
                paragraphStartPos = 0
            } while (contentList[nowSpeak].matches(AppPattern.notReadAloudRegex))
            textChapter?.let {
                if (readAloudByPage) {
                    val paragraphs = it.getParagraphs(true)
                    if (!paragraphs[nowSpeak].isParagraphEnd) readAloudNumber++
                }
                if (readAloudNumber < it.getReadLength(pageIndex)) {
                    pageIndex--
                    ReadBook.moveToPrevPage()
                }
            }
            upTtsProgress(readAloudNumber + 1)
            playReadAloudInternal(false)
        } else {
            toLast = true
            ReadBook.moveToPrevChapter(true)
        }
    }

    private fun nextP() {
        if (nowSpeak < contentList.size - 1) {
            // 手动切换下一段时，不影响背景音乐
            stopReadAloudInternal(false)
            readAloudNumber += contentList[nowSpeak].length.plus(1) - paragraphStartPos
            paragraphStartPos = 0
            nowSpeak++
            textChapter?.let {
                if (readAloudByPage && nowSpeak < it.getParagraphs(true).size) {
                    val paragraphs = it.getParagraphs(true)
                    if (!paragraphs[nowSpeak].isParagraphEnd) readAloudNumber--
                }
                if (pageIndex + 1 < it.pageSize
                    && readAloudNumber >= it.getReadLength(pageIndex + 1)
                ) {
                    pageIndex++
                    ReadBook.moveToNextPage()
                }
            }
            upTtsProgress(readAloudNumber + 1)
            playReadAloudInternal(false)
        } else {
            nextChapter()
        }
    }

    /**
     * 下一段（不播放，用于朗读开始前的位置调整）
     */
    private fun moveToNextParagraph() {
        if (nowSpeak < contentList.size - 1) {
            readAloudNumber += contentList[nowSpeak].length + 1 - paragraphStartPos
            paragraphStartPos = 0
            nowSpeak++
            textChapter?.let {
                if (readAloudByPage && nowSpeak < it.getParagraphs(true).size) {
                    val paragraphs = it.getParagraphs(true)
                    if (!paragraphs[nowSpeak].isParagraphEnd) readAloudNumber--
                }
                if (pageIndex + 1 < it.pageSize
                    && readAloudNumber >= it.getReadLength(pageIndex + 1)
                ) {
                    pageIndex++
                }
            }
        }
    }

    /**
     * 上一段（不播放，用于朗读开始前的位置调整）
     */
    private fun moveToPrevParagraph() {
        if (nowSpeak > 0) {
            do {
                nowSpeak--
                readAloudNumber -= contentList[nowSpeak].length + 1 + paragraphStartPos
                paragraphStartPos = 0
            } while (contentList[nowSpeak].matches(AppPattern.notReadAloudRegex))
            textChapter?.let {
                if (readAloudByPage) {
                    val paragraphs = it.getParagraphs(true)
                    if (!paragraphs[nowSpeak].isParagraphEnd) readAloudNumber++
                }
                if (readAloudNumber < it.getReadLength(pageIndex)) {
                    pageIndex--
                }
            }
        }
    }

    /**
     * 内部停止朗读（不受背景音乐影响）
     * @param affectBgm 是否影响背景音乐播放状态
     */
    protected fun stopReadAloudInternal(affectBgm: Boolean = true) {
        playStop(affectBgm)
    }

    /**
     * 内部播放朗读（不受背景音乐影响）
     * @param affectBgm 是否影响背景音乐播放状态
     */
    protected fun playReadAloudInternal(affectBgm: Boolean = true) {
        play(affectBgm)
    }

    /**
     * 启动BGM：首次播放时先AI分析当前文本再播放，后续直接继续播放
     */
    protected fun startBgm() {
        if (!AppConfig.isBgmEnabled) return
        if (!AppConfig.bgmAIEnabled || BgmManager.hasPlayedOnce) {
            BgmManager.play()
            return
        }
        // 首次播放且AI启用：先分析当前文本，拿到推荐索引后再播放
        lifecycleScope.launch {
            try {
                // 在主线程获取当前BGM（避免IO线程访问ExoPlayer）
                val currentBgm = BgmManager.getCurrentBgmName()
                val contentToAnalyze = withContext(Dispatchers.IO) {
                    collectContentForAI()
                }
                if (!contentToAnalyze.isNullOrBlank()) {
                    val recommendedFileName = withContext(Dispatchers.IO) {
                        BgmAIService.analyzeContent(contentToAnalyze, currentBgm)
                    }
                    if (!recommendedFileName.isNullOrEmpty()) {
                        val index = BgmManager.findMediaItemIndexByName(recommendedFileName)
                        val targetIndex = if (index >= 0) index else BgmManager.findMediaItemIndexFuzzy(recommendedFileName)
                        if (targetIndex >= 0) {
                            AppLog.put("AI背景音乐: 第一首分析结果=$recommendedFileName, index=$targetIndex")
                            BgmManager.playByIndex(targetIndex)
                            return@launch
                        }
                    }
                }
            } catch (e: Exception) {
                AppLog.put("AI背景音乐首首分析失败: ${e.localizedMessage}")
            }
            // 分析失败或结果无效，回退到默认播放
            BgmManager.play()
        }
    }

    /**
     * BGM播放时预分析下一首：根据BGM剩余时长定位朗读结束段落，
     * 从那收集文本让AI分析，实现BGM切换与朗读场景对齐
     */
    fun triggerBgmAnalysis() {
        if (!AppConfig.bgmAIEnabled) {
            AppLog.put("AI背景音乐: AI开关未开启，跳过预分析")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastBgmAnalysisTime < 5000L) {
            AppLog.put("AI背景音乐: 预分析被5秒防抖跳过")
            return
        }
        lastBgmAnalysisTime = now
        bgmAnalysisJob?.cancel()
        AppLog.put("AI背景音乐: ==== 触发预分析 ====")
        bgmAnalysisJob = lifecycleScope.launch {
            try {
                // 在主线程获取BGM信息（避免IO线程访问ExoPlayer）
                val remainingMs = BgmManager.getRemainingDuration()
                val currentBgm = BgmManager.getCurrentBgmName()
                AppLog.put("AI背景音乐: 当前BGM=$currentBgm, 剩余时长=${remainingMs}ms")
                val contentToAnalyze = if (remainingMs > 0) {
                    val endParagraph = calculateEndParagraph(remainingMs)
                    AppLog.put("AI背景音乐: BGM剩余${remainingMs}ms, 预计结束在段落$endParagraph")
                    withContext(Dispatchers.IO) {
                        collectContentFromParagraph(endParagraph)
                    }
                } else {
                    AppLog.put("AI背景音乐: remainingMs<=0，从当前段落收集")
                    withContext(Dispatchers.IO) {
                        collectContentForAI()
                    }
                }
                if (contentToAnalyze.isNullOrBlank()) {
                    LogUtils.d(TAG, "AI背景音乐: 未收集到有效文本")
                    AppLog.put("AI背景音乐: 未收集到有效文本")
                    return@launch
                }
                LogUtils.d(TAG, "AI背景音乐: 预分析文本长度=${contentToAnalyze.length}")
                AppLog.put("AI背景音乐: 预分析文本长度=${contentToAnalyze.length}")
                val recommendedFileName = withContext(Dispatchers.IO) {
                    BgmAIService.analyzeContent(contentToAnalyze, currentBgm)
                }
                if (!recommendedFileName.isNullOrEmpty()) {
                    val index = BgmManager.findMediaItemIndexByName(recommendedFileName)
                    pendingNextBgmIndex = if (index >= 0) {
                        index
                    } else {
                        BgmManager.findMediaItemIndexFuzzy(recommendedFileName)
                    }
                    // 同时更新BgmManager中的预存索引
                    BgmManager.pendingNextBgmIndex = pendingNextBgmIndex
                    AppLog.put("AI背景音乐: 预分析下一首=$recommendedFileName, index=$pendingNextBgmIndex")
                    // 关键修复：如果BGM当前处于暂停等待状态（例如onMediaItemTransition(AUTO)已暂停等AI结果），主动播放推荐曲目
                    if (pendingNextBgmIndex >= 0 && !BgmManager.isPlaying()) {
                        AppLog.put("AI背景音乐: 分析完成，BGM暂停中，主动播放 index=$pendingNextBgmIndex")
                        BgmManager.playByIndex(pendingNextBgmIndex)
                    }
                } else {
                    AppLog.put("AI背景音乐: AI未返回推荐文件名 (result=$recommendedFileName)")
                }
            } catch (e: Exception) {
                LogUtils.e(TAG, "AI背景音乐预分析失败：${e.message}")
                AppLog.put("AI背景音乐预分析失败：${e.localizedMessage}")
            }
        }
    }

    /**
     * 计算BGM剩余时长内朗读会走到哪个段落
     * 从当前段落(nowSpeak)开始累加各段时长，累加>=remainingMs时停止
     */
    private fun calculateEndParagraph(remainingMs: Long): Int {
        var accumulated = 0L
        val realLastIndex = (contentList.lastIndex - 1).coerceAtLeast(0)
        for (i in nowSpeak until contentList.size) {
            val duration = estimateParagraphDuration(i)
            accumulated += duration
            if (accumulated >= remainingMs) {
                return i.coerceAtMost(realLastIndex)
            }
        }
        return realLastIndex
    }

    /**
     * 从指定段落开始收集AI分析文本
     * 用于BGM切换前的预分析，收集BGM结束时朗读所在场景
     */
    private fun collectContentFromParagraph(startIndex: Int): String? {
        val maxCharCount = 5000
        val targetChars = AppConfig.bgmAICharInterval.coerceIn(100, 1000)
        val sb = StringBuilder()
        var currentCharCount = 0

        val book = ReadBook.book
        if (book != null) {
            sb.append("【书籍信息】\n")
            sb.append("书名：${book.name}\n")
            if (book.author.isNotEmpty()) sb.append("作者：${book.author}\n")
            if (!book.kind.isNullOrEmpty()) sb.append("分类：${book.kind}\n")
            if (!book.customTag.isNullOrEmpty()) sb.append("自定义分类：${book.customTag}\n")
            sb.append("【正文内容】\n\n")
            currentCharCount = sb.length
        }

        for (i in startIndex until contentList.size) {
            val paragraph = contentList[i]
            if (paragraph.isNullOrBlank() || paragraph.matches(AppPattern.notReadAloudRegex)) {
                continue
            }

            if (currentCharCount + paragraph.length > maxCharCount) {
                val remainingChars = maxCharCount - currentCharCount
                if (remainingChars > 0) {
                    sb.append(paragraph.substring(0, remainingChars))
                }
                break
            }

            sb.append(paragraph)
            sb.append("\n")
            currentCharCount += paragraph.length + 1

            if (currentCharCount >= targetChars) break
        }

        return sb.toString().trim().takeIf { it.isNotEmpty() }
    }

    /**
     * 段落计数切换已废弃，由BGM播放状态驱动切换
     */
    protected fun checkBgmSwitch() {
        // 不再使用段落计数触发BGM切换
    }

    /**
     * 收集用于AI分析的文本内容
     * 基于段落位置，收集当前段落及后续段落的文本
     * 包含书籍信息（书名、作者、分类）
     * 收集总字数由 bgmAICharInterval 决定（默认500字）
     */
    private fun collectContentForAI(): String? {
        val maxCharCount = 5000 // 8000 tokens约等于5000字符（考虑中英混合）
        val targetChars = AppConfig.bgmAICharInterval.coerceIn(100, 1000)
        val sb = StringBuilder()
        var currentCharCount = 0

        // 添加书籍信息
        val book = ReadBook.book
        if (book != null) {
            sb.append("【书籍信息】\n")
            sb.append("书名：${book.name}\n")
            if (book.author.isNotEmpty()) {
                sb.append("作者：${book.author}\n")
            }
            if (!book.kind.isNullOrEmpty()) {
                sb.append("分类：${book.kind}\n")
            }
            if (!book.customTag.isNullOrEmpty()) {
                sb.append("自定义分类：${book.customTag}\n")
            }
            sb.append("【正文内容】\n\n")
            currentCharCount = sb.length
        }

        // 从当前段落开始，收集文本直到达到targetChars字数
        for (i in nowSpeak until contentList.size) {
            val paragraph = contentList[i]
            if (paragraph.isNullOrBlank() || paragraph.matches(io.legado.app.constant.AppPattern.notReadAloudRegex)) {
                continue
            }

            // 如果添加这个段落后会超过字符限制，则截断
            if (currentCharCount + paragraph.length > maxCharCount) {
                val remainingChars = maxCharCount - currentCharCount
                if (remainingChars > 0) {
                    sb.append(paragraph.substring(0, remainingChars))
                }
                break
            }

            sb.append(paragraph)
            sb.append("\n")
            currentCharCount += paragraph.length + 1

            // 正文部分达到目标字数就停止
            if (currentCharCount >= targetChars) {
                break
            }
        }

        return sb.toString().trim().takeIf { it.isNotEmpty() }
    }

    private fun setTimer(minute: Int) {
        timeMinute = minute
        doDs()
    }

    private fun addTimer() {
        if (timeMinute == 180) {
            timeMinute = 0
        } else {
            timeMinute += 10
            if (timeMinute > 180) timeMinute = 180
        }
        doDs()
    }

    /**
     * 定时
     */
    @Synchronized
    private fun doDs() {
        postEvent(EventBus.READ_ALOUD_DS, timeMinute)
        upReadAloudNotification()
        dsJob?.cancel()
        dsJob = lifecycleScope.launch {
            while (isActive) {
                delay(60000)
                if (!pause) {
                    if (timeMinute >= 0) {
                        timeMinute--
                    }
                    if (timeMinute == 0) {
                        ReadAloud.stop(this@BaseReadAloudService)
                        postEvent(EventBus.READ_ALOUD_DS, timeMinute)
                        break
                    }
                }
                postEvent(EventBus.READ_ALOUD_DS, timeMinute)
                upReadAloudNotification()
            }
        }
    }

    /**
     * 请求音频焦点
     * @return 音频焦点
     */
    fun requestFocus(): Boolean {
        if (AppConfig.ignoreAudioFocus) {
            return true
        }
        val requestFocus = MediaHelp.requestFocus(mFocusRequest)
        if (!requestFocus) {
            pauseReadAloud(false)
            toastOnUi("未获取到音频焦点")
        }
        return requestFocus
    }

    /**
     * 放弃音频焦点
     */
    private fun abandonFocus() {
        AudioManagerCompat.abandonAudioFocusRequest(audioManager, mFocusRequest)
    }

    /**
     * 更新媒体状态
     */
    private fun upMediaSessionPlaybackState(state: Int) {
        mediaSessionCompat.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(MediaHelp.MEDIA_SESSION_ACTIONS)
                .setState(state, nowSpeak.toLong(), 1f)
                // 为系统媒体控件添加定时按钮
//                .addCustomAction(
//                    PlaybackStateCompat.CustomAction.Builder(
//                        "ACTION_ADD_TIMER",
//                        getString(R.string.set_timer),
//                        R.drawable.ic_time_add_24dp
//                    ).build()
//                )
                .build()
        )
    }

    /**
     * 初始化MediaSession, 注册多媒体按钮
     */
    /**
     * 初始化MediaSession, 注册多媒体按钮
     */
    @SuppressLint("UnspecifiedImmutableFlag")
    private fun initMediaSession() {
        if (getPrefBoolean("systemMediaControlCompatibilityChange")) {
            mediaSessionCompat.setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    resumeReadAloud()
                }

                override fun onPause() {
                    pauseReadAloud()
                }

                override fun onSkipToNext() {
                    if (getPrefBoolean("mediaButtonPerNext", false)) {
                        nextChapter()
                    } else {
                        nextP()
                    }
                }

                override fun onSkipToPrevious() {
                    if (getPrefBoolean("mediaButtonPerNext", false)) {
                        prevChapter()
                    } else {
                        prevP()
                    }
                }

                override fun onStop() {
                    stopSelf()
                }

                override fun onCustomAction(action: String, extras: Bundle?) {
                    if (action == "ACTION_ADD_TIMER") addTimer()
                }

                override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                    return MediaButtonReceiver.handleIntent(
                        this@BaseReadAloudService, mediaButtonEvent
                    )
                }
            })
        } else {
            mediaSessionCompat.setCallback(object : MediaSessionCompat.Callback() {
                override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                    return MediaButtonReceiver.handleIntent(
                        this@BaseReadAloudService, mediaButtonEvent
                    )
                }
            })
        }
    }

    /**
     * 注册多媒体按钮监听
     */
    private fun initBroadcastReceiver() {
        val intentFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registerReceiver(broadcastReceiver, intentFilter)
    }

    /**
     * 音频焦点变化
     */
    override fun onAudioFocusChange(focusChange: Int) {
        if (AppConfig.ignoreAudioFocus) {
            AppLog.put("忽略音频焦点处理(TTS)")
            return
        }
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (needResumeOnAudioFocusGain) {
                    AppLog.put("音频焦点获得,继续朗读")
                    resumeReadAloud()
                } else {
                    AppLog.put("音频焦点获得")
                }
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                AppLog.put("音频焦点丢失,暂停朗读")
                pauseReadAloud()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                AppLog.put("音频焦点暂时丢失并会很快再次获得,暂停朗读")
                if (!pause) {
                    needResumeOnAudioFocusGain = true
                    pauseReadAloud(false)
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // 短暂丢失焦点，这种情况是被其他应用申请了短暂的焦点希望其他声音能压低音量（或者关闭声音）凸显这个声音（比如短信提示音），
                AppLog.put("音频焦点短暂丢失,不做处理")
            }
        }
    }

    private fun upReadAloudNotification() {
        upNotificationJob = execute {
            try {
                val notification = createNotification()
                notificationManager.notify(NotificationId.ReadAloudService, notification.build())
            } catch (e: Exception) {
                AppLog.put("创建朗读通知出错,${e.localizedMessage}", e, true)
            }
        }
    }

    private fun upFloatWindow() {
        if (getPrefBoolean(PreferKey.readAloudFloatWindow) && isPlay()) {
            ReadAloudFloatService.updateVisibility(this)
        }
    }

    private fun choiceMediaStyle(): androidx.media.app.NotificationCompat.MediaStyle {
        val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle()
            .setShowActionsInCompactView(1, 2, 4)
        if (getPrefBoolean("systemMediaControlCompatibilityChange")) {
            //fix #4090 android 14 can not show play control in lock screen
            mediaStyle.setMediaSession(mediaSessionCompat.sessionToken)
        }
        return mediaStyle
    }

    private fun createNotification(): NotificationCompat.Builder {
        var nTitle: String = when {
            pause -> getString(R.string.read_aloud_pause)
            timeMinute > 0 -> getString(
                R.string.read_aloud_timer,
                timeMinute
            )

            else -> getString(R.string.read_aloud_t)
        }
        nTitle += ": ${ReadBook.book?.name}"
        var nSubtitle = ReadBook.curTextChapter?.title
        if (nSubtitle.isNullOrBlank())
            nSubtitle = getString(R.string.read_aloud_s)
        val builder = NotificationCompat
            .Builder(this, AppConst.channelIdReadAloud)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setSmallIcon(R.drawable.ic_volume_up)
            .setSubText(getString(R.string.read_aloud))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(nTitle)
            .setContentText(nSubtitle)
            .setContentIntent(
                activityPendingIntent<ReadBookActivity>("activity")
            )
            .setVibrate(null)
            .setSound(null)
            .setLights(0, 0, 0)
        builder.setLargeIcon(cover)
        // 按钮定义：上一章、播放、停止、下一章、定时
        builder.addAction(
            R.drawable.ic_skip_previous,
            getString(R.string.previous_chapter),
            aloudServicePendingIntent(IntentAction.prev)
        )
        if (pause) {
            builder.addAction(
                R.drawable.ic_play_24dp,
                getString(R.string.resume),
                aloudServicePendingIntent(IntentAction.resume)
            )
        } else {
            builder.addAction(
                R.drawable.ic_pause_24dp,
                getString(R.string.pause),
                aloudServicePendingIntent(IntentAction.pause)
            )
        }
        builder.addAction(
            R.drawable.ic_stop_black_24dp,
            getString(R.string.stop),
            aloudServicePendingIntent(IntentAction.stop)
        )
        builder.addAction(
            R.drawable.ic_skip_next,
            getString(R.string.next_chapter),
            aloudServicePendingIntent(IntentAction.next)
        )
        builder.addAction(
            R.drawable.ic_time_add_24dp,
            getString(R.string.set_timer),
            aloudServicePendingIntent(IntentAction.addTimer)
        )
        builder.setStyle(choiceMediaStyle())
        return builder
    }

    /**
     * 更新通知
     */
    override fun startForegroundNotification() {
        execute {
            try {
                val notification = createNotification()
                startForeground(NotificationId.ReadAloudService, notification.build())
            } catch (e: Exception) {
                AppLog.put("创建朗读通知出错,${e.localizedMessage}", e, true)
                //创建通知出错不结束服务就会崩溃,服务必须绑定通知
                stopSelf()
            }
        }
    }

    abstract fun aloudServicePendingIntent(actionStr: String): PendingIntent?

    open fun prevChapter() {
        toLast = false
        resumeReadAloudInternal()
        ReadBook.moveToPrevChapter(true, toLast = false)
    }

    open fun nextChapter() {
        ReadBook.upReadTime()
        AppLog.putDebug("${ReadBook.curTextChapter?.chapter?.title} 朗读结束跳转下一章并朗读")
        resumeReadAloudInternal()
        isAutoSwitchingChapter = true
        if (!ReadBook.moveToNextChapter(true)) {
            stopSelf()
        }
    }

    private fun initPhoneStateListener() {
        val needRegister = AppConfig.ignoreAudioFocus && AppConfig.pauseReadAloudWhilePhoneCalls
        if (needRegister && registeredPhoneStateListener) {
            return
        }
        if (needRegister) {
            registerPhoneStateListener(phoneStateListener)
        } else {
            unregisterPhoneStateListener(phoneStateListener)
        }
    }

    private fun unregisterPhoneStateListener(l: PhoneStateListener) {
        if (registeredPhoneStateListener) {
            withReadPhoneStatePermission {
                telephonyManager.listen(l, PhoneStateListener.LISTEN_NONE)
                registeredPhoneStateListener = false
            }
        }
    }

    private fun registerPhoneStateListener(l: PhoneStateListener) {
        withReadPhoneStatePermission {
            telephonyManager.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
            registeredPhoneStateListener = true
        }
    }

    private fun withReadPhoneStatePermission(block: () -> Unit) {
        try {
            block.invoke()
        } catch (_: SecurityException) {
            PermissionsCompat.Builder()
                .addPermissions(Permissions.READ_PHONE_STATE)
                .rationale(R.string.read_aloud_read_phone_state_permission_rationale)
                .onGranted {
                    try {
                        block.invoke()
                    } catch (_: SecurityException) {
                        LogUtils.d(TAG, "Grant read phone state permission fail.")
                    }
                }
                .request()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    inner class ReadAloudPhoneStateListener : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            super.onCallStateChanged(state, phoneNumber)
            when (state) {
                TelephonyManager.CALL_STATE_IDLE -> {
                    if (needResumeOnCallStateIdle) {
                        AppLog.put("来电结束,继续朗读")
                        resumeReadAloud()
                    } else {
                        AppLog.put("来电结束")
                    }
                }

                TelephonyManager.CALL_STATE_RINGING -> {
                    if (!pause) {
                        AppLog.put("来电响铃,暂停朗读")
                        needResumeOnCallStateIdle = true
                        pauseReadAloud()
                    } else {
                        AppLog.put("来电响铃")
                    }
                }

                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    AppLog.put("来电接听,不做处理")
                }
            }
        }
    }

}
