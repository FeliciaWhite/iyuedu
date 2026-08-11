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
import io.legado.app.utils.StringUtils
import io.legado.app.help.MediaHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.http.CookieStore
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.model.AiImageGenerator
import io.legado.app.model.AiImagePersistentCache
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

        /**
         * gengxin 触发重启后要恢复到的段落索引。
         * 服务重启后 newReadAloud 会重置 nowSpeak，在 play() 前用此值覆盖。
         * -1 表示无待恢复。
         */
        @JvmStatic
        var pendingGengxinResumeIndex: Int = -1

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

        /**
         * 音效标记正则：匹配 (打雷音效) 等形式
         */
        private val soundEffectPattern = Regex("\\(([\\u4e00-\\u9fa5]*音效)\\)")

        /**
         * 应用内置音效替换规则，在文本中插入 (音效) 标记。
         * 若文本中已存在音效标记，则跳过，避免重复。
         * 根据当前书籍生效的音效模式选择不同的规则文件。
         */
        @JvmStatic
        protected fun applySoundEffectRules(text: String): String {
            if (text.isEmpty()) return text
            val mode = io.legado.app.model.ReadBook.book?.bookUrl?.let {
                AppConfig.getEffectiveSoundEffectMode(it)
            } ?: AppConfig.soundEffectMode
            if (mode == "off") return text

            val jsonFileName = if (mode == "normal") "ttsrv-replaces5.json" else "ttsrv-replaces3.json"
            val hasEffects = soundEffectPattern.containsMatchIn(text)
            val file = java.io.File("/storage/emulated/0/Download/chajian/mingwuyan/$jsonFileName")
            val downloadUrl = "https://cnb.cool/mingwuyan/yinpin/-/git/raw/main/$jsonFileName?download=true"
            var raw: String? = null
            // 1. 优先读取本地 JSON
            if (file.exists()) {
                try {
                    raw = file.readText()
                    if (raw.isNullOrEmpty() || raw == "null" || raw == "undefined"
                        || !raw.trimStart().startsWith("[")
                    ) {
                        raw = null
                        file.delete()
                        AppLog.put("[ttsrv] 本地文件内容无效，已删除，将重新下载")
                    }
                } catch (e: Exception) {
                    AppLog.put("[ttsrv] 本地文件读取失败: ${e.message}")
                    raw = null
                }
            }
            // 2. 本地没有或无效，下载
            if (raw == null) {
                try {
                    val response = io.legado.app.help.http.okHttpClient.newCall(
                        okhttp3.Request.Builder().url(downloadUrl).build()
                    ).execute()
                    raw = response.body?.string()
                    if (!raw.isNullOrEmpty() && raw != "null" && raw != "undefined"
                        && raw.trimStart().startsWith("[")
                    ) {
                        file.parentFile?.mkdirs()
                        file.writeText(raw)
                        AppLog.put("[ttsrv] 下载成功并保存")
                    } else {
                        AppLog.put("[ttsrv] 下载返回无效内容: ${raw?.take(100)}")
                        return text
                    }
                } catch (e: Exception) {
                    AppLog.put("[ttsrv] 下载失败: ${e.message}")
                    return text
                }
            }
            return try {
                val data = org.json.JSONArray(raw)
                var result = text
                var totalRules = 0
                var successRules = 0
                var failRules = 0
                for (i in 0 until data.length()) {
                    val groupObj = data.getJSONObject(i)
                    val list = groupObj.optJSONArray("list") ?: continue
                    val rules = mutableListOf<RuleItem>()
                    for (j in 0 until list.length()) {
                        val item = list.getJSONObject(j)
                        if (item.optBoolean("isEnabled", true).not()) continue
                        val pattern = item.optString("pattern", "") ?: continue
                        if (pattern.isEmpty()) continue
                        val order = item.optInt("order", 0)
                        rules.add(RuleItem(
                            pattern = pattern,
                            replacement = item.optString("replacement", ""),
                            isRegex = item.optBoolean("isRegex", false),
                            order = order
                        ))
                    }
                    rules.sortBy { it.order }
                    for (rule in rules) {
                        totalRules++
                        try {
                            val (cleanPattern, flags) = parsePatternAndFlags(rule.pattern)
                            val effectivePattern = convertEs6RegexToJava(cleanPattern)
                            result = if (rule.isRegex) {
                                val regex = if (flags.isNotEmpty()) {
                                    val flagSet = mutableSetOf<RegexOption>()
                                    if (flags.contains('i')) flagSet.add(RegexOption.IGNORE_CASE)
                                    if (flags.contains('m')) flagSet.add(RegexOption.MULTILINE)
                                    if (flags.contains('s')) flagSet.add(RegexOption.DOT_MATCHES_ALL)
                                    Regex(effectivePattern, flagSet)
                                } else {
                                    Regex(effectivePattern)
                                }
                                regex.replace(result, rule.replacement)
                            } else {
                                result.replace(rule.pattern, rule.replacement)
                            }
                            successRules++
                        } catch (e: Exception) {
                            failRules++
                            AppLog.put("[ttsrv] 规则失败 [order=${rule.order}]: ${e.message} pattern=${rule.pattern}")
                        }
                    }
                }
                AppLog.put("[ttsrv] 规则执行: ${totalRules}条, 成功${successRules}条, 失败${failRules}条, 原文本${text.length}字, 结果${result.length}字")
                val nowHasEffects = soundEffectPattern.containsMatchIn(result)
                if (!hasEffects && nowHasEffects) {
                    AppLog.put("[ttsrv] 替换后生成了音效标记")
                }
                result
            } catch (e: Exception) {
                AppLog.put("[ttsrv] 整体异常: ${e.message}")
                text
            }
        }

        private data class RuleItem(
            val pattern: String,
            val replacement: String,
            val isRegex: Boolean,
            val order: Int
        )

        private fun parsePatternAndFlags(pattern: String): Pair<String, String> {
            if (pattern.length >= 2 && pattern.startsWith("/") && !pattern.substring(1).contains("/")) {
                return pattern to ""
            }
            if (pattern.length >= 2 && pattern.startsWith("/")) {
                val lastSlash = pattern.lastIndexOf("/")
                if (lastSlash > 0) {
                    val regex = pattern.substring(1, lastSlash)
                    val flags = pattern.substring(lastSlash + 1)
                    return regex to flags
                }
            }
            return pattern to ""
        }

        private fun convertEs6RegexToJava(pattern: String): String {
            return pattern.replace(Regex("""\\u\{([0-9a-fA-F]+)\}""")) { match ->
                "\\x{${match.groupValues[1]}}"
            }
        }

        /**
         * 供合并流程使用：对文本应用音效替换规则并提取音效标记。
         * 返回 (去掉标记后的净文本, 音效列表)，不依赖播放时的 paragraphEffects 状态。
         * 可在无 service 实例时静态调用（如目录界面手动保存）。
         */
        fun extractSoundEffectsForMerge(text: String): Pair<String, List<SoundEffect>> {
            val processed = applySoundEffectRules(text)
            val effects = mutableListOf<SoundEffect>()
            val sb = StringBuilder()
            var lastEnd = 0
            soundEffectPattern.findAll(processed).forEach { match ->
                sb.append(processed.substring(lastEnd, match.range.first))
                val offset = sb.length
                val name = match.groupValues[1]
                effects.add(SoundEffect(offset, name, "$name.json"))
                lastEnd = match.range.last + 1
            }
            sb.append(processed.substring(lastEnd))
            return sb.toString() to effects
        }

        /**
         * 供合并流程使用：按音效 JSON 文件名获取解码后的音效音频文件（复用播放流程的下载/解码逻辑）。
         * 可在无 service 实例时静态调用。
         */
        fun getSoundEffectAudioFile(fileName: String): java.io.File? {
            return downloadAndDecodeEffect(fileName, true)?.first
        }

        /**
         * 下载音效 JSON 并解码为音频文件。
         * 支持轮换：从 JSON 的 audios 数组中按 currentIndex 取出 base64，
         * 解码后保存为索引文件。若 consumeIndex 为 true，则更新 currentIndex 并写回 JSON。
         * 返回音频文件和当前索引。
         */
        private fun downloadAndDecodeEffect(fileName: String, consumeIndex: Boolean = false): Pair<java.io.File, Int>? {
            val sfxDir = java.io.File("/storage/emulated/0/Download/chajian/bendiyinxiao2")
            if (!sfxDir.exists()) sfxDir.mkdirs()
            val jsonFile = java.io.File(sfxDir, fileName)

            var jsonStr: String? = null
            // 1. 优先读取本地 JSON
            if (jsonFile.exists()) {
                try {
                    jsonStr = jsonFile.readText()
                    if (jsonStr.isNullOrEmpty() || !jsonStr.trimStart().startsWith("{")) jsonStr = null
                    else AppLog.putDebug("[音效] 读取本地 JSON: $fileName")
                } catch (e: Exception) {
                    AppLog.put("[音效] 本地 JSON 读取失败: $fileName, ${e.message}")
                    jsonStr = null
                }
            } else {
                AppLog.putDebug("[音效] 本地 JSON 不存在: $fileName")
            }

            // 2. 本地没有或无效，下载
            if (jsonStr == null) {
                val url = "https://cnb.cool/mingwuyan/yinpin/-/git/raw/main/bdyinxiao3/$fileName"
                AppLog.put("[音效] 开始下载: $url")
                try {
                    val response = io.legado.app.help.http.okHttpClient.newCall(
                        okhttp3.Request.Builder().url(url).build()
                    ).execute()
                    val body = response.body?.string()
                    if (!body.isNullOrEmpty() && body.trimStart().startsWith("{")) {
                        jsonStr = body
                        jsonFile.writeText(jsonStr)
                        AppLog.put("[音效] 下载成功并保存: $fileName")
                    } else {
                        AppLog.put("[音效] 下载返回无效内容: $fileName")
                    }
                } catch (e: Exception) {
                    AppLog.put("[音效] 下载失败: $fileName, ${e.message}")
                }
            }

            if (jsonStr == null) {
                AppLog.put("[音效] 无法获取 JSON: $fileName")
                return null
            }

            // 3. 解析
            val json = try { org.json.JSONObject(jsonStr) } catch (e: Exception) {
                AppLog.put("[音效] JSON 解析失败: $fileName, ${e.message}")
                return null
            }
            val audios = json.optJSONArray("audios") ?: run {
                AppLog.put("[音效] 缺少 audios 数组: $fileName")
                return null
            }
            if (audios.length() == 0) {
                AppLog.put("[音效] audios 为空: $fileName")
                return null
            }

            // 4. 获取当前索引
            val savedIndex = json.optInt("currentIndex", 0)
            val index = if (savedIndex >= audios.length()) 0 else savedIndex
            val b64 = audios.optString(index) ?: run {
                AppLog.put("[音效] 索引 $index 无 base64: $fileName")
                return null
            }
            if (b64.length < 100) {
                AppLog.put("[音效] base64 太短(${b64.length}): $fileName idx=$index")
                return null
            }

            // 5. 若 consumeIndex 为 true，更新索引并写回 JSON
            if (consumeIndex && audios.length() > 1) {
                val nextIndex = (index + 1) % audios.length()
                try {
                    json.put("currentIndex", nextIndex)
                    jsonFile.writeText(json.toString())
                    AppLog.putDebug("[音效] 轮换索引: $fileName $index -> $nextIndex")
                } catch (e: Exception) {
                    AppLog.put("[音效] 索引更新失败: $fileName, ${e.message}")
                }
            }

            // 6. 解码并保存为索引文件
            val bytes = try {
                android.util.Base64.decode(b64.trim(), android.util.Base64.DEFAULT)
            } catch (e: Exception) {
                AppLog.put("[音效] base64 解码失败: $fileName idx=$index, ${e.message}")
                return null
            }

            val audioFile = java.io.File(sfxDir, fileName.replace(".json", "_$index.mp3"))
            audioFile.writeBytes(bytes)
            AppLog.putDebug("[音效] 解码成功: $fileName idx=$index size=${bytes.size}")
            return audioFile to index
        }
    }

    private val useWakeLock = appCtx.getPrefBoolean(PreferKey.readAloudWakeLock, false)
    private val mainHandler by lazy { io.legado.app.utils.buildMainHandler() }
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
    /** 单段播放模式：只播放当前段落，播放完后自动暂停 */
    var singleParagraphMode = false
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

    // AI 生图：字数驱动 + 预生成
    /** 当前 AI 图片场景序号（0,1,2...），解绑BGM后独立递增 */
    private var aiImageSceneIndex: Int = 0
    /** 自上次触发以来累计的已读字数 */
    private var aiImageAccumulatedChars: Int = 0
    /** 是否已执行过首次生成（避免重复触发） */
    private var aiImageFirstGenerated: Boolean = false
    /** 实时生成防重入标志：displayCurrentAiImage 启动生成后置 true，完成后置 false，防止重复调用 */
    private var aiImageRealtimeGenerating: Boolean = false

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

    /** gengxin 信号文件所在目录 */
    private val gengxinDir = "/storage/emulated/0/Download/chajian/mingwuyan/"
    /** cunfang.txt 存放书名，shuming.{书名}.json 是要检测修改时间的信号文件 */
    private val gengxinCunfangPath get() = gengxinDir + "cunfang.txt"
    private fun gengxinShumingPath(bookName: String) = gengxinDir + "shuming.$bookName.json"

    /** 后台轮询协程 */
    private var gengxinPollJob: Job? = null
    /** 上次检测到的信号文件修改时间，0 表示尚未记录 */
    private var gengxinLastMtime: Long = 0L
    /** 上次检测到的书名（cunfang.txt 内容），用于判断是否切换书籍 */
    private var gengxinLastBookName: String? = null
    /** 标记是否正在执行触发处理（防止重启过程中重复触发） */
    private var gengxinHandling: Boolean = false
    /** 标记本次朗读是 gengxin 恢复，跳过 newReadAloud 里的"跳过当前段"逻辑 */
    private var gengxinResuming: Boolean = false

    /**
     * 启动后台定时轮询：每秒检测 cunfang.txt → 提取书名 → 查 shuming.{书名}.json 的修改时间。
     * 时间变化则停止朗读、删除所有缓存、重新开始朗读。
     * 仅当开关开启时生效。
     */
    private fun startGengxinPolling() {
        gengxinPollJob?.cancel()
        gengxinPollJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    if (AppConfig.gengxinRealtimeUpdate && !gengxinHandling) {
                        checkGengxinSignalFile()
                    }
                } catch (e: Exception) {
                    AppLog.put("gengxin 轮询异常: ${e.localizedMessage}", e)
                }
                delay(1000)
            }
        }
    }

    /** 停止后台轮询 */
    private fun stopGengxinPolling() {
        gengxinPollJob?.cancel()
        gengxinPollJob = null
    }

    /**
     * 检测信号文件：读 cunfang.txt 内容(书名) → 查 shuming.{书名}.json 的 lastModified。
     * 若与上次记录不同则触发处理。
     */
    private fun checkGengxinSignalFile() {
        val cunfang = java.io.File(gengxinCunfangPath)
        if (!cunfang.exists()) return
        val bookName = cunfang.readText().trim()
        if (bookName.isEmpty()) return
        val shumingFile = java.io.File(gengxinShumingPath(bookName))
        if (!shumingFile.exists()) return
        val mtime = shumingFile.lastModified()
        // 首次记录 或 切换书籍（书名不同）：只更新记录，不触发处理
        if (gengxinLastMtime == 0L || bookName != gengxinLastBookName) {
            gengxinLastMtime = mtime
            gengxinLastBookName = bookName
            AppLog.put("gengxin 轮询: 记录 $bookName mtime=$mtime (首次或切换书籍，不触发)")
            return
        }
        if (mtime == gengxinLastMtime) return   // 同一本书时间未变，不触发
        // 同一本书且修改时间变化，触发处理
        AppLog.put("gengxin 轮询: 检测到 $bookName 修改时间变化 $gengxinLastMtime -> $mtime")
        gengxinLastMtime = mtime
        handleGengxinTrigger()
    }

    /**
     * gengxin 触发处理：
     * - 正在朗读：删除当前段之后的缓存 → 停止朗读 → 从当前段重新开始朗读
     * - 未朗读：删除所有缓存
     * 由子类实现 deleteGengxinCachesAfter() 删除指定段之后的缓存。
     */
    private fun handleGengxinTrigger() {
        gengxinHandling = true
        val speaking = isPlay()
        val curIndex = nowSpeak
        AppLog.put("gengxin 触发处理: speaking=$speaking, curIndex=$curIndex")
        if (speaking) {
            // 正在朗读：只删除当前段之后的缓存，当前段及之前保留
            deleteGengxinCachesAfter(curIndex)
            // 记录恢复段索引，重启后从此段继续
            pendingGengxinResumeIndex = curIndex
            // 停止当前朗读
            stopSelf()
            // 延迟让服务完全停止后重新开始朗读
            // 直接调 ReadAloud.play 而非 ReadBook.readAloud，避免循环重启
            mainHandler.postDelayed({
                gengxinHandling = false
                ReadAloud.play(appCtx, true, startPos = 0)
            }, 800)
        } else {
            // 未朗读：删除所有缓存
            deleteGengxinCachesAfter(-1)
            gengxinHandling = false
        }
    }

    /**
     * 删除指定段之后的缓存（不含当前段）。
     * @param fromIndex 从此 index 之后（含 fromIndex+1）开始删除；
     *                  传 -1 表示删除全部（未朗读场景）。
     * 子类按各自缓存后缀实现。
     */
    protected abstract fun deleteGengxinCachesAfter(fromIndex: Int)

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
        val currentEngineName = getCurrentTtsEngineName()
        var result = text
        scripts.forEach { script ->
            if (script.code.isBlank()) return@forEach
            // 如果绑定了朗读引擎，检查当前引擎是否在绑定列表中
            if (script.bindTtsEngines.isNotBlank()) {
                val boundEngines = script.bindTtsEngines.split(",").map { it.trim() }
                if (currentEngineName != null && !boundEngines.contains(currentEngineName)) {
                    return@forEach
                }
            }
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

    // ========== 音效处理 ==========

    /**
     * 段落音效映射：段落索引 -> 该段落的音效列表
     */
    val paragraphEffects = mutableMapOf<Int, MutableList<SoundEffect>>()

    /**
     * 音效信息
     * @param charOffset 在净化后文本中的字符偏移位置
     * @param name 音效名称，如 "打雷"
     * @param fileName 对应的 JSON 文件名，如 "打雷音效.json"
     * @param triggered 是否已触发
     */
    data class SoundEffect(
        val charOffset: Int,
        val name: String,
        val fileName: String,
        var triggered: Boolean = false
    )

    /**
     * 提取文本中的 (音效) 标记，记录其在净化后文本中的字符偏移位置。
     * 返回已移除标记的纯文本。
     */
    fun extractSoundEffects(text: String, index: Int): String {
        val effects = mutableListOf<SoundEffect>()
        val sb = StringBuilder()
        var lastEnd = 0
        soundEffectPattern.findAll(text).forEach { match ->
            sb.append(text.substring(lastEnd, match.range.first))
            val offset = sb.length
            val name = match.groupValues[1]
            effects.add(SoundEffect(offset, name, "$name.json"))
            lastEnd = match.range.last + 1
        }
        sb.append(text.substring(lastEnd))
        paragraphEffects[index] = effects
        return sb.toString()
    }

    /**
     * 重置所有音效触发状态，并清空播放队列。
     */
    fun resetSoundEffects() {
        paragraphEffects.values.forEach { list ->
            list.forEach { it.triggered = false }
        }
        effectPlayQueue.clear()
        currentMediaPlayer?.release()
        currentMediaPlayer = null
    }

    protected var currentParagraphTotalChars: Int = 0

    // ========== 音效播放（MediaPlayer 顺序队列） ==========
    private var currentMediaPlayer: android.media.MediaPlayer? = null
    private val effectPlayQueue = java.util.LinkedList<Pair<java.io.File, String>>()
    protected var lastEffectPlayTime = 0L

    /**
     * 触发时下载/解码音效，然后加入播放队列。
     * 当前无音效播放时立即播放，否则排队等待。
     */
    protected fun loadAndPlayEffect(effect: SoundEffect) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = downloadAndDecodeEffect(effect.fileName, true)
            if (result == null) {
                AppLog.put("[音效] 加载失败，跳过: ${effect.name}")
                return@launch
            }
            val (audioFile, _) = result
            withContext(Dispatchers.Main) {
                enqueueOrPlayEffect(audioFile, effect.name)
            }
        }
    }

    /**
     * 将音效加入队列，或立即播放。
     */
    private fun enqueueOrPlayEffect(audioFile: java.io.File, effectName: String) {
        if (currentMediaPlayer?.isPlaying == true) {
            effectPlayQueue.add(audioFile to effectName)
            AppLog.put("[音效] 加入队列等待: $effectName")
        } else {
            doPlayEffect(audioFile, effectName)
        }
    }

    /**
     * 使用 MediaPlayer 播放音效，并在播放完成后自动播放下一个。
     */
    private fun doPlayEffect(audioFile: java.io.File, effectName: String) {
        try {
            val volume = AppConfig.soundEffectVolume / 100f
            val mp = android.media.MediaPlayer().apply {
                setDataSource(audioFile.absolutePath)
                setVolume(volume, volume)
                prepare()
                setOnCompletionListener {
                    it.release()
                    lastEffectPlayTime = System.currentTimeMillis()
                    currentMediaPlayer = null
                    playNextEffect()
                }
                setOnErrorListener { mp, what, extra ->
                    AppLog.put("[音效] 播放错误: $effectName what=$what extra=$extra")
                    mp.release()
                    currentMediaPlayer = null
                    playNextEffect()
                    true
                }
                start()
            }
            currentMediaPlayer = mp
            lastEffectPlayTime = System.currentTimeMillis()
            AppLog.put("[音效] 播放: $effectName")
        } catch (e: Exception) {
            AppLog.put("[音效] 播放失败: $effectName, ${e.message}")
            currentMediaPlayer = null
            playNextEffect()
        }
    }

    /**
     * 从队列中取出下一个音效播放。
     */
    private fun playNextEffect() {
        if (effectPlayQueue.isNotEmpty()) {
            val (file, name) = effectPlayQueue.poll()
            doPlayEffect(file, name)
        }
    }

    /**
     * 暂停当前音效播放（用于朗读暂停时）。
     */
    protected fun pauseSoundEffects() {
        currentMediaPlayer?.pause()
    }

    /**
     * 根据当前播放进度检查并触发音效
     */
    protected fun checkAndPlayEffects(totalDuration: Long, currentPosition: Long) {
        val effectiveMode = io.legado.app.model.ReadBook.book?.bookUrl?.let {
            AppConfig.getEffectiveSoundEffectMode(it)
        } ?: AppConfig.soundEffectMode
        if (effectiveMode == "off") return
        val effects = paragraphEffects[nowSpeak] ?: return
        if (effects.isEmpty()) return

        val duration = totalDuration.coerceAtLeast(1)
        val totalChars = currentParagraphTotalChars.coerceAtLeast(1)
        val ratio = currentPosition.toFloat() / duration
        val charPos = (ratio * totalChars).toInt()

        effects.forEach { effect ->
            if (!effect.triggered && charPos >= (effect.charOffset - AppConfig.soundEffectOffsetChars)) {
                effect.triggered = true
                loadAndPlayEffect(effect)
            }
        }
    }

    /**
     * 获取当前使用的朗读引擎标识，用于脚本绑定匹配。
     * HttpTTS 返回 http:ID，系统 TTS 返回 sys:包名。
     */
    private fun getCurrentTtsEngineName(): String? {
        val ttsEngine = ReadAloud.ttsEngine
        return if (!ttsEngine.isNullOrBlank() && StringUtils.isNumeric(ttsEngine)) {
            "http:$ttsEngine"
        } else {
            val pkg = AppConfig.sysTtsPackageName
            if (!pkg.isNullOrBlank()) "sys:$pkg" else null
        }
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
        // 启动 gengxin 信号后台轮询（每秒检测一次）
        startGengxinPolling()
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
            // BGM预分析（仅推荐下一首BGM，AI生图已改为字数独立驱动）
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
        observeEvent<String>(EventBus.AI_IMAGE_REQUEST) { _ ->
            // 朗读对话框请求显示当前 AI 图片
            // 若首次生成已触发，只需通知UI显示已有图片，不重复启动生成
            if (aiImageFirstGenerated) {
                val book = ReadBook.book
                if (book != null) {
                    // 仅当当前场景已有图片时才通知 UI。
                    // 生图失败或尚未就绪时保持当前显示，不再回退到"最近生成的图片"，
                    // 避免失败场景被其它旧图覆盖（即"失败保持状态"）。
                    AiImageGenerator.notifyImageIfReady(book.bookUrl, ReadBook.durChapterIndex, aiImageSceneIndex)
                }
            } else {
                displayCurrentAiImage()
            }
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
        // 停止 gengxin 信号后台轮询
        stopGengxinPolling()
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
        AiImageGenerator.clearCache()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.play -> {
                singleParagraphMode = intent.getBooleanExtra("singleParagraph", false)
                newReadAloud(
                    intent.getBooleanExtra("play", true),
                    intent.getIntExtra("pageIndex", ReadBook.durPageIndex),
                    intent.getIntExtra("startPos", 0)
                )
            }

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
                if (textChapter.pages.isEmpty()) {
                    AppLog.putDebug("newReadAloud: TextChapter 排版未完成且 pages 为空，等待排版完成")
                    return@execute
                }
                AppLog.putDebug("newReadAloud: TextChapter 排版未完成，使用已排版部分继续朗读")
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
            if (singleParagraphMode) {
                paragraphStartPos = 0
            } else {
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
            }
            // gengxin 重启恢复：若有待恢复的段落索引，覆盖 nowSpeak 从该段继续
            if (pendingGengxinResumeIndex >= 0 && pendingGengxinResumeIndex < contentList.size) {
                AppLog.put("gengxin 恢复: nowSpeak $nowSpeak -> ${pendingGengxinResumeIndex}")
                nowSpeak = pendingGengxinResumeIndex
                paragraphStartPos = 0
                pendingGengxinResumeIndex = -1
                gengxinResuming = true   // 标记恢复，避免下方跳过当前段
            }
            launch(Dispatchers.Main) {
                if (play) {
                    LogUtils.d(TAG, "nowSpeak=$nowSpeak")
                    // 简化逻辑：nowSpeak==0直接朗读，nowSpeak!=0跳过当前段
                    // gengxin 恢复时不跳过（要从恢复段重新朗读）
                    if (!gengxinResuming && !singleParagraphMode && !AppConfig.readAloudStartFromFirst && !toLast && nowSpeak < contentList.size) {
                        if (nowSpeak != 0) {
                            LogUtils.d(TAG, "nowSpeak!=0，跳过当前段")
                            moveToNextParagraph()
                        } else {
                            LogUtils.d(TAG, "nowSpeak==0，直接朗读")
                        }
                    }
                    gengxinResuming = false   // 重置标志
                    // 开启状态：直接朗读，不做位置调整
                    isAutoSwitchingChapter = false
                    upTtsProgress(readAloudNumber + 1)
                    play()
                    // 首次开始朗读时立即生成第一张AI图片，并预生成下一张
                    triggerFirstAiImageIfNeeded()
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

    /**
     * 同步朗读位置到指定段落索引
     * 根据 contentList 重新计算 readAloudNumber，避免 updateNextPos 累积误差
     * 用于音频播放切换时直接定位，解决队列跳过段落导致的高亮不同步
     */
    protected fun syncToParagraph(targetIndex: Int) {
        if (targetIndex < 0 || targetIndex >= contentList.size) return
        nowSpeak = targetIndex
        paragraphStartPos = 0
        readAloudNumber = 0
        for (i in 0 until targetIndex) {
            readAloudNumber += contentList[i].length + 1
        }
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
        if (AppConfig.bgmAIEnabled) {
            if (BgmManager.hasPlayedOnce) {
                BgmManager.play()
                return
            }
            lifecycleScope.launch {
                try {
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
                BgmManager.play()
            }
            return
        }
        // AI智能切歌关闭：后台匹配BGM，匹配成功后再播放，不阻塞朗读流程
        lifecycleScope.launch {
            try {
                // 先加载BGM文件列表和关键词配置（确保 allAudioFiles、playlist 和 categoryKeywords 可用）
                BgmManager.loadBgmFiles()

                // 如果已经播放过，直接继续播放当前音乐，不再重新匹配
                if (BgmManager.hasPlayedOnce) {
                    BgmManager.play()
                    return@launch
                }

                val currentBgm = BgmManager.getCurrentBgmName()
                val contentToAnalyze = withContext(Dispatchers.IO) {
                    collectContentForAI()
                }
                if (!contentToAnalyze.isNullOrBlank()) {
                    val recommendedFileName = withContext(Dispatchers.IO) {
                        BgmKeywordMatcher.analyze(contentToAnalyze, currentBgm)
                    }
                    if (!recommendedFileName.isNullOrEmpty()) {
                        val index = BgmManager.findMediaItemIndexByName(recommendedFileName)
                        val targetIndex = if (index >= 0) index else BgmManager.findMediaItemIndexFuzzy(recommendedFileName)
                        if (targetIndex >= 0) {
                            AppLog.put("关键词BGM: 匹配成功=$recommendedFileName, index=$targetIndex")
                            BgmManager.playByIndex(targetIndex)
                        }
                    }
                }
            } catch (e: Exception) {
                AppLog.put("关键词BGM匹配失败: ${e.localizedMessage}")
            }
        }
    }

    /**
     * BGM播放时预分析下一首：根据BGM剩余时长定位朗读结束段落，
     * 从那收集文本让AI分析，实现BGM切换与朗读场景对齐
     */
    fun triggerBgmAnalysis() {
        if (AppConfig.bgmAIEnabled) {
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
                        BgmManager.pendingNextBgmIndex = pendingNextBgmIndex
                        AppLog.put("AI背景音乐: 预分析下一首=$recommendedFileName, index=$pendingNextBgmIndex")
                        if (pendingNextBgmIndex >= 0 && !BgmManager.isPlaying()) {
                            AppLog.put("AI背景音乐: 分析完成，BGM暂停中，主动播放 index=$pendingNextBgmIndex")
                            BgmManager.playByIndex(pendingNextBgmIndex)
                        }
                        // AI生图已改为字数独立驱动，不再由BGM预分析触发
                    } else {
                        AppLog.put("AI背景音乐: AI未返回推荐文件名 (result=$recommendedFileName)")
                    }
                } catch (e: Exception) {
                    LogUtils.e(TAG, "AI背景音乐预分析失败：${e.message}")
                    AppLog.put("AI背景音乐预分析失败：${e.localizedMessage}")
                }
            }
            return
        }
        // AI智能切歌关闭：使用关键词匹配进行预分析
        val now = System.currentTimeMillis()
        if (now - lastBgmAnalysisTime < 5000L) {
            AppLog.put("关键词BGM: 预分析被5秒防抖跳过")
            return
        }
        lastBgmAnalysisTime = now
        bgmAnalysisJob?.cancel()
        AppLog.put("关键词BGM: ==== 触发预分析 ====")
        bgmAnalysisJob = lifecycleScope.launch {
            try {
                val remainingMs = BgmManager.getRemainingDuration()
                val currentBgm = BgmManager.getCurrentBgmName()
                AppLog.put("关键词BGM: 当前BGM=$currentBgm, 剩余时长=${remainingMs}ms")
                val contentToAnalyze = if (remainingMs > 0) {
                    val endParagraph = calculateEndParagraph(remainingMs)
                    AppLog.put("关键词BGM: BGM剩余${remainingMs}ms, 预计结束在段落$endParagraph")
                    withContext(Dispatchers.IO) {
                        collectContentFromParagraph(endParagraph)
                    }
                } else {
                    AppLog.put("关键词BGM: remainingMs<=0，从当前段落收集")
                    withContext(Dispatchers.IO) {
                        collectContentForAI()
                    }
                }
                if (contentToAnalyze.isNullOrBlank()) {
                    AppLog.put("关键词BGM: 未收集到有效文本")
                    return@launch
                }
                AppLog.put("关键词BGM: 预分析文本长度=${contentToAnalyze.length}")
                val recommendedFileName = withContext(Dispatchers.IO) {
                    BgmKeywordMatcher.analyze(contentToAnalyze, currentBgm)
                }
                if (!recommendedFileName.isNullOrEmpty()) {
                    val index = BgmManager.findMediaItemIndexByName(recommendedFileName)
                    pendingNextBgmIndex = if (index >= 0) {
                        index
                    } else {
                        BgmManager.findMediaItemIndexFuzzy(recommendedFileName)
                    }
                    BgmManager.pendingNextBgmIndex = pendingNextBgmIndex
                    AppLog.put("关键词BGM: 预分析下一首=$recommendedFileName, index=$pendingNextBgmIndex")
                    if (pendingNextBgmIndex >= 0 && !BgmManager.isPlaying()) {
                        AppLog.put("关键词BGM: 分析完成，BGM暂停中，主动播放 index=$pendingNextBgmIndex")
                        BgmManager.playByIndex(pendingNextBgmIndex)
                    }
                } else {
                    AppLog.put("关键词BGM: 未匹配到推荐文件名")
                }
            } catch (e: Exception) {
                AppLog.put("关键词BGM预分析失败：${e.localizedMessage}")
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
     * 收集总字数由 bgmAICharInterval 决定（默认350字）
     */
    internal fun collectContentForAI(): String? {
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

    /**
     * 显示当前场景对应的AI图片（字数驱动，不再依赖BGM索引）
     * 优先显示预生成的图片，若不存在则实时生成
     * 含防重入保护：避免 triggerFirst 和 AI_IMAGE_REQUEST 同时触发导致重复生成
     */
    fun displayCurrentAiImage() {
        if (!AppConfig.readAloudAiImage) {
            AppLog.put("AI生图[DEBUG] displayCurrentAiImage: readAloudAiImage=false, 跳过")
            return
        }
        val book = ReadBook.book ?: run {
            AppLog.put("AI生图[DEBUG] displayCurrentAiImage: ReadBook.book=null, 跳过")
            return
        }
        val chapterIndex = ReadBook.durChapterIndex
        AppLog.put("AI生图[DEBUG] displayCurrentAiImage: sceneIndex=$aiImageSceneIndex, chapterIndex=$chapterIndex")
        // 1. 先尝试显示预生成的图片
        AiImageGenerator.notifyImageIfReady(book.bookUrl, chapterIndex, aiImageSceneIndex)
        // 2. 如果预生成不存在，实时生成（首次或预生成失败时）
        if (AiImageGenerator.getCachedImage(book.bookUrl, chapterIndex, aiImageSceneIndex) == null) {
            // 防重入：如果已有生成任务在跑或本方法已启动过实时生成，跳过重复启动
            if (aiImageRealtimeGenerating || AiImageGenerator.isGenerating(book.bookUrl, chapterIndex, aiImageSceneIndex)) {
                AppLog.put("AI生图[DEBUG] displayCurrentAiImage: 已有生成任务在跑, 跳过重复启动 sceneIndex=$aiImageSceneIndex")
            } else {
                AppLog.put("AI生图[DEBUG] displayCurrentAiImage: 缓存为空, 启动实时生成 sceneIndex=$aiImageSceneIndex")
                // 同步设置标志（在 launch 之前），确保第二个调用能检测到
                aiImageRealtimeGenerating = true
                lifecycleScope.launch {
                    try {
                        val content = withContext(Dispatchers.IO) { collectContentForAiImage() }
                        if (!content.isNullOrBlank()) {
                            AiImageGenerator.generateForScene(
                                bookUrl = book.bookUrl,
                                chapterIndex = chapterIndex,
                                sceneIndex = aiImageSceneIndex,
                                mood = "",
                                sourceText = content,
                                bookName = book.name,
                                retryCount = AppConfig.aiImageRetryCount.coerceAtLeast(0),
                                contentProvider = { collectContentForAiImage() },
                                cacheCtx = buildPersistentCacheContext(),
                                // 重试时按当前朗读位置重新算段落范围并复查缓存，段落递增后可命中旧图
                                cacheCtxProvider = { buildPersistentCacheContext() }
                            )
                        }
                    } catch (e: Exception) {
                        AppLog.put("AI 生图实时生成失败: ${e.localizedMessage}")
                    } finally {
                        aiImageRealtimeGenerating = false
                    }
                }
            }
        }
        // 3. 预生成下一张（即使跳过实时生成也执行，保证下一张提前准备）
        preGenerateNextAiImage()
    }

    /**
     * 首次开始朗读时触发：立即生成第一张AI图片并预生成下一张
     * 仅在首次调用时执行，后续不再重复
     */
    fun triggerFirstAiImageIfNeeded() {
        if (!AppConfig.readAloudAiImage) {
            AppLog.put("AI生图[DEBUG] triggerFirst: readAloudAiImage=false, 跳过")
            return
        }
        if (aiImageFirstGenerated) {
            AppLog.put("AI生图[DEBUG] triggerFirst: 已首次生成过, 跳过 (sceneIndex=$aiImageSceneIndex, accumulated=$aiImageAccumulatedChars)")
            return
        }
        aiImageFirstGenerated = true
        aiImageSceneIndex = 0
        aiImageAccumulatedChars = 0
        AppLog.put("AI生图: 首次触发，开始生成第一张图片(sceneIndex=0)")
        displayCurrentAiImage()
    }

    /**
     * 段落推进后检查是否达到字数阈值，触发AI图片切换
     * 在子类 updateNextPos() 的 nowSpeak++ 之后调用
     * @param advancedParagraphLength 刚朗读完的段落字数（用于累计）
     */
    protected fun checkAiImageTrigger(advancedParagraphLength: Int) {
        if (!AppConfig.readAloudAiImage) return
        if (!aiImageFirstGenerated) {
            AppLog.put("AI生图[DEBUG] checkAiImageTrigger: aiImageFirstGenerated=false, 跳过(段落推进+${advancedParagraphLength}字)")
            return
        }
        val charCount = AppConfig.aiImageCharCount.coerceIn(50, 5000)
        aiImageAccumulatedChars += advancedParagraphLength
        if (aiImageAccumulatedChars >= charCount) {
            AppLog.put("AI生图: 累计字数${aiImageAccumulatedChars}≥${charCount}，触发切换")
            aiImageAccumulatedChars = 0
            switchToNextAiImage()
        } else {
            AppLog.put("AI生图[DEBUG] checkAiImageTrigger: 累计${aiImageAccumulatedChars}/${charCount}字 (本次+${advancedParagraphLength})")
        }
    }

    /**
     * 切换到下一张AI图片：显示已预生成的图片 + 预生成再下一张
     */
    private fun switchToNextAiImage() {
        aiImageSceneIndex++
        val book = ReadBook.book ?: run {
            AppLog.put("AI生图[DEBUG] switchToNextAiImage: ReadBook.book=null, 无法切换")
            return
        }
        val chapterIndex = ReadBook.durChapterIndex
        AppLog.put("AI生图: 切换到 sceneIndex=$aiImageSceneIndex, chapterIndex=$chapterIndex")
        // 1. 立即显示已预生成的图片（秒切，无等待）
        AiImageGenerator.notifyImageIfReady(book.bookUrl, chapterIndex, aiImageSceneIndex)
        // 若预生成不存在（上次预生成失败），则实时生成当前场景
        if (AiImageGenerator.getCachedImage(book.bookUrl, chapterIndex, aiImageSceneIndex) == null) {
            AppLog.put("AI生图[DEBUG] switchToNextAiImage: sceneIndex=$aiImageSceneIndex 缓存为空, 实时生成")
            realtimeGenerateForCurrentScene(book, chapterIndex, aiImageSceneIndex)
        } else {
            AppLog.put("AI生图[DEBUG] switchToNextAiImage: sceneIndex=$aiImageSceneIndex 预生成缓存命中, 秒切")
        }
        // 2. 预生成下一张图片
        preGenerateNextAiImage()
    }

    /**
     * 实时生成当前场景图片（预生成缓存未命中时的兜底）。
     * 当检测到当前段落接近章末且字数不足时，进行跨章节处理（生成本章末尾图 + 下章开头图）。
     */
    private fun realtimeGenerateForCurrentScene(
        book: io.legado.app.data.entities.Book,
        chapterIndex: Int,
        sceneIndex: Int,
    ) {
        lifecycleScope.launch {
            try {
                val charCount = AppConfig.aiImageCharCount.coerceIn(50, 5000)
                val endParagraph = calculateEndParagraphByCharCount(charCount)
                val realLastIndex = (contentList.lastIndex - 1).coerceAtLeast(0)
                // 跨章节判定：结束段落被钳制到本章最后段，且当前段落不在最后（仍有本章末尾内容可生成）
                if (endParagraph >= realLastIndex && nowSpeak < realLastIndex) {
                    AppLog.put("AI生图: 实时生成检测到跨章节（章末字数不足），分两章生成")
                    preGenerateCrossChapter(book, chapterIndex, sceneIndex, charCount, realLastIndex)
                    return@launch
                }
                val content = withContext(Dispatchers.IO) { collectContentForAiImage() }
                if (!content.isNullOrBlank()) {
                    AiImageGenerator.generateForScene(
                        bookUrl = book.bookUrl,
                        chapterIndex = chapterIndex,
                        sceneIndex = sceneIndex,
                        mood = "",
                        sourceText = content,
                        bookName = book.name,
                        retryCount = AppConfig.aiImageRetryCount.coerceAtLeast(0),
                        contentProvider = { collectContentForAiImage() },
                        cacheCtx = buildPersistentCacheContext(),
                        // 重试时按当前朗读位置重新算段落范围并复查缓存，段落递增后可命中旧图
                        cacheCtxProvider = { buildPersistentCacheContext() }
                    )
                }
            } catch (e: Exception) {
                AppLog.put("AI 生图切换时实时生成失败: ${e.localizedMessage}")
            }
        }
    }

    /**
     * 预生成下一张AI图片
     * 从当前朗读位置往后推算「分析字数」对应的结束段落，从该段落提取正文预生成
     * 当推算到达本章末尾（字数不足）时，进行跨章节处理：
     *   - 第一张：本章末尾剩余段落（序号沿用本章，保存到本章目录）
     *   - 第二张：下一章开头段落补足剩余字数（序号从下一章0开始，保存到下一章目录）
     */
    private fun preGenerateNextAiImage() {
        if (!AppConfig.readAloudAiImage) return
        val book = ReadBook.book ?: return
        val chapterIndex = ReadBook.durChapterIndex
        val nextSceneIndex = aiImageSceneIndex + 1
        // 已缓存或正在生成则跳过
        if (AiImageGenerator.getCachedImage(book.bookUrl, chapterIndex, nextSceneIndex) != null) return
        lifecycleScope.launch {
            try {
                val charCount = AppConfig.aiImageCharCount.coerceIn(50, 5000)
                val endParagraph = calculateEndParagraphByCharCount(charCount)
                val realLastIndex = (contentList.lastIndex - 1).coerceAtLeast(0)
                // 跨章节判定：结束段落被钳制到本章最后段，说明字数不足到达章末
                if (endParagraph >= realLastIndex && nowSpeak < realLastIndex) {
                    AppLog.put("AI生图: 预生成检测到跨章节（章末字数不足），分两章生成")
                    preGenerateCrossChapter(book, chapterIndex, nextSceneIndex, charCount, realLastIndex)
                    return@launch
                }
                AppLog.put("AI生图: 预生成 sceneIndex=$nextSceneIndex, 从段落${endParagraph}提取${charCount}字")
                val content = withContext(Dispatchers.IO) {
                    collectContentFromParagraphWithCharCount(endParagraph, charCount)
                }
                if (!content.isNullOrBlank()) {
                    AiImageGenerator.preGenerateForScene(
                        bookUrl = book.bookUrl,
                        chapterIndex = chapterIndex,
                        sceneIndex = nextSceneIndex,
                        mood = "",
                        sourceText = content,
                        bookName = book.name,
                        retryCount = AppConfig.aiImageRetryCount.coerceAtLeast(0),
                        contentProvider = { collectContentForAiImage() },
                        cacheCtx = buildPersistentCacheContext(startPara = endParagraph),
                        // 预生成重试：按「当前图片段落的下一个段」重算范围并复查缓存，段落递增可命中旧图
                        cacheCtxProvider = { buildPersistentCacheContext(startPara = calculateEndParagraphByCharCount(charCount)) }
                    )
                }
            } catch (e: Exception) {
                AppLog.put("AI生图预生成失败: ${e.localizedMessage}")
            }
        }
    }

    /**
     * 跨章节预生成两张图片：
     * - 第一张：本章末尾剩余段落（startPara=当前段落, endPara=本章最后段），保存到本章目录
     * - 第二张：下一章开头段落补足剩余字数（startPara=0, endPara=补足位置），保存到下一章目录，序号从0开始
     */
    private suspend fun preGenerateCrossChapter(
        book: io.legado.app.data.entities.Book,
        chapterIndex: Int,
        sceneIndex: Int,
        charCount: Int,
        realLastIndex: Int,
    ) {
        // 第一张：本章末尾剩余段落
        val curEndPara = realLastIndex
        val curContent = withContext(Dispatchers.IO) {
            collectContentFromParagraphWithCharCount(nowSpeak, charCount)
        }
        if (!curContent.isNullOrBlank()) {
            AppLog.put("AI生图: 跨章节-本章末尾图 sceneIndex=$sceneIndex, 段落[$nowSpeak,$curEndPara]")
            AiImageGenerator.preGenerateForScene(
                bookUrl = book.bookUrl,
                chapterIndex = chapterIndex,
                sceneIndex = sceneIndex,
                mood = "",
                sourceText = curContent,
                bookName = book.name,
                retryCount = AppConfig.aiImageRetryCount.coerceAtLeast(0),
                contentProvider = { collectContentForAiImage() },
                cacheCtx = buildPersistentCacheContext(startPara = nowSpeak),
                // 跨章节-本章末尾图：重试时按当前 nowSpeak 重算范围并复查缓存
                cacheCtxProvider = { buildPersistentCacheContext(startPara = nowSpeak) }
            )
        }
        // 第二张：下一章开头段落补足剩余字数
        val nextChapter = ReadBook.textChapter(1)
        if (nextChapter != null) {
            val nextChapterIndex = chapterIndex + 1
            val nextParaList = nextChapter.getNeedReadAloud(0, readAloudByPage, 0)
                .split("\n").filter { it.isNotEmpty() }.toMutableList()
            if (nextParaList.isNotEmpty()) {
                val nextCharCount = AppConfig.aiImageCharCount.coerceIn(50, 5000)
                val nextEndPara = calculateEndParagraphInList(nextParaList, nextCharCount, 0)
                val nextContent = withContext(Dispatchers.IO) {
                    collectContentFromList(nextParaList, 0, nextCharCount)
                }
                if (!nextContent.isNullOrBlank()) {
                    AppLog.put("AI生图: 跨章节-下章开头图 sceneIndex=0(下章), 段落[0,$nextEndPara]")
                    val nextChapterName = nextChapter.title
                    AiImageGenerator.preGenerateForScene(
                        bookUrl = book.bookUrl,
                        chapterIndex = nextChapterIndex,
                        sceneIndex = 0,
                        mood = "",
                        sourceText = nextContent,
                        bookName = book.name,
                        retryCount = AppConfig.aiImageRetryCount.coerceAtLeast(0),
                        contentProvider = { collectContentForAiImage() },
                        cacheCtx = AiImageGenerator.PersistentCacheContext(
                            bookUrl = book.bookUrl,
                            bookName = book.name,
                            chapterIndex = nextChapterIndex,
                            chapterName = nextChapterName,
                            startPara = 0,
                            endPara = nextEndPara,
                            charCount = nextCharCount,
                        ),
                        // 跨章节-下章开头图：重试时复查下一章开头区间（startPara=0 固定，按当前章状态重算）
                        cacheCtxProvider = {
                            AiImageGenerator.PersistentCacheContext(
                                bookUrl = book.bookUrl,
                                bookName = book.name,
                                chapterIndex = nextChapterIndex,
                                chapterName = nextChapterName,
                                startPara = 0,
                                endPara = calculateEndParagraphInList(nextParaList, nextCharCount, 0),
                                charCount = nextCharCount,
                            )
                        }
                    )
                }
            }
        }
    }

    /**
     * 根据字数计算从指定段落起，累计charCount字后会到达的结束段落
     * @param fromIndex 起始段落，默认为当前朗读位置 nowSpeak
     */
    private fun calculateEndParagraphByCharCount(charCount: Int, fromIndex: Int = nowSpeak): Int {
        var accumulated = 0
        val realLastIndex = (contentList.lastIndex - 1).coerceAtLeast(0)
        for (i in fromIndex until contentList.size) {
            val paragraph = contentList[i]
            if (paragraph.isNullOrBlank() || paragraph.matches(AppPattern.notReadAloudRegex)) continue
            accumulated += paragraph.length + 1
            if (accumulated >= charCount) {
                return i.coerceAtMost(realLastIndex)
            }
        }
        return realLastIndex
    }

    /**
     * 从当前段落开始收集AI生图用的正文内容
     * 提取字数由 aiImageCharCount 决定（默认200字）
     */
    internal fun collectContentForAiImage(): String? {
        val charCount = AppConfig.aiImageCharCount.coerceIn(50, 5000)
        return collectContentFromParagraphWithCharCount(nowSpeak, charCount)
    }

    /**
     * 从指定段落开始收集正文，提取指定字数
     */
    private fun collectContentFromParagraphWithCharCount(startIndex: Int, targetChars: Int): String? {
        val maxCharCount = 5000
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
            if (paragraph.isNullOrBlank() || paragraph.matches(AppPattern.notReadAloudRegex)) continue
            if (currentCharCount + paragraph.length > maxCharCount) {
                val remainingChars = maxCharCount - currentCharCount
                if (remainingChars > 0) sb.append(paragraph.substring(0, remainingChars))
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
     * 在指定段落列表上，从 fromIndex 起累计 charCount 字后到达的结束段落索引。
     * 用于跨章节时在下一章的段列表上推算结束段落。
     */
    private fun calculateEndParagraphInList(
        paraList: List<String>,
        charCount: Int,
        fromIndex: Int = 0,
    ): Int {
        var accumulated = 0
        val realLastIndex = (paraList.lastIndex).coerceAtLeast(0)
        for (i in fromIndex until paraList.size) {
            val paragraph = paraList[i]
            if (paragraph.isNullOrBlank() || paragraph.matches(AppPattern.notReadAloudRegex)) continue
            accumulated += paragraph.length + 1
            if (accumulated >= charCount) {
                return i.coerceAtMost(realLastIndex)
            }
        }
        return realLastIndex
    }

    /**
     * 在指定段落列表上收集正文，从 startIndex 起提取 targetChars 字。
     * 用于跨章节时从下一章段列表收集开头正文。
     */
    private fun collectContentFromList(
        paraList: List<String>,
        startIndex: Int,
        targetChars: Int,
    ): String? {
        val maxCharCount = 5000
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
        for (i in startIndex until paraList.size) {
            val paragraph = paraList[i]
            if (paragraph.isNullOrBlank() || paragraph.matches(AppPattern.notReadAloudRegex)) continue
            if (currentCharCount + paragraph.length > maxCharCount) {
                val remainingChars = maxCharCount - currentCharCount
                if (remainingChars > 0) sb.append(paragraph.substring(0, remainingChars))
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
     * 构建持久缓存上下文：携带当前生图覆盖的段落区间与章节信息，供持久缓存命中查询与落盘。
     * @param startPara 本次生图起始段落，默认为当前朗读位置 nowSpeak
     */
    private fun buildPersistentCacheContext(startPara: Int = nowSpeak): AiImageGenerator.PersistentCacheContext? {
        val book = ReadBook.book ?: return null
        val charCount = AppConfig.aiImageCharCount.coerceIn(50, 5000)
        val chapterIndex = ReadBook.durChapterIndex
        val chapterName = textChapter?.title ?: textChapter?.chapter?.title ?: ""
        // 始终以传入的 startPara（当前朗读位置 / 下一段目标段）计算区间，
        // 每次生图前都先按当前段落查命中，命中复用旧图，未命中才调 API。
        val start = startPara
        val endPara = calculateEndParagraphByCharCount(charCount, fromIndex = start)
        return AiImageGenerator.PersistentCacheContext(
            bookUrl = book.bookUrl,
            bookName = book.name,
            chapterIndex = chapterIndex,
            chapterName = chapterName,
            startPara = start,
            endPara = endPara,
            charCount = charCount,
        )
    }

    /**
     * 切换章节时重置AI生图状态
     */
    fun resetAiImageState() {
        aiImageSceneIndex = 0
        aiImageAccumulatedChars = 0
        aiImageFirstGenerated = false
        aiImageRealtimeGenerating = false
        // 清理持久缓存游标，使新章节从头按序轮换复用旧图
        ReadBook.book?.let { book ->
            AiImagePersistentCache.resetChapterCursor(book.bookUrl, ReadBook.durChapterIndex)
        }
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
        // 切换章节时重置AI生图状态
        resetAiImageState()
        resumeReadAloudInternal()
        ReadBook.moveToPrevChapter(true, toLast = false)
    }

    open fun nextChapter() {
        ReadBook.upReadTime()
        AppLog.putDebug("${ReadBook.curTextChapter?.chapter?.title} 朗读结束跳转下一章并朗读")
        isAutoSwitchingChapter = true
        // 切换章节时重置AI生图状态，新章节从头开始计数
        resetAiImageState()
        if (!ReadBook.moveToNextChapter(true)) {
            stopSelf()
            return
        }
        resumeReadAloudInternal()
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
