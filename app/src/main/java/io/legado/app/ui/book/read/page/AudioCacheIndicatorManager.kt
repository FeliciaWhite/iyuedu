package io.legado.app.ui.book.read.page

import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadAloud
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.StringUtils
import splitties.init.appCtx
import java.io.File

/**
 * 音频缓存标记管理器
 * 负责扫描当前章节各段落的音频缓存状态，并提供单段删除功能。
 * 文件名计算严格复用 HttpReadAloudService / TTSReadAloudService 的算法。
 * 同时支持 HttpTTS 和系统 TTS 缓存的检测、播放与删除。
 * 系统 TTS 与 HttpTTS 均使用朗读脚本前的原始文本计算 MD5，仅文件夹不同。
 */
object AudioCacheIndicatorManager {

    // paragraphNum -> 缓存文件
    private val cachedParagraphMap = mutableMapOf<Int, File>()

    private val httpTtsFolderPath: String by lazy {
        val baseDir = appCtx.getExternalFilesDir(null) ?: appCtx.filesDir
        baseDir.absolutePath + File.separator + "httpTTS" + File.separator
    }

    private val sysTtsFolderPath: String by lazy {
        val baseDir = appCtx.externalCacheDir ?: appCtx.cacheDir
        baseDir.absolutePath + File.separator + "systemTTS" + File.separator
    }

    /** 判断当前是否使用 HttpTTS */
    private fun isHttpTts(): Boolean {
        val ttsEngine = ReadAloud.ttsEngine
        return !ttsEngine.isNullOrBlank() && StringUtils.isNumeric(ttsEngine)
    }

    /**
     * 对段落文本做和朗读服务 [TextChapter.getNeedReadAloud] 相同的预处理，
     * 保证后续过滤、MD5 计算与实际缓存文件名一致。
     */
    private fun prepareParagraphText(raw: String): String {
        return raw.replace(Regex("[袮꧁]"), " ")
    }

    /** 扫描当前章节各段落的缓存状态 */
    fun scanChapter(textChapter: TextChapter) {
        cachedParagraphMap.clear()
        if (textChapter.pages.isEmpty()) return

        val paragraphs = textChapter.getParagraphs(false)
        if (paragraphs.isEmpty()) return

        // 预扫描两个缓存文件夹，建立文件名 -> 文件的映射
        val httpCacheFiles = mutableSetOf<String>()
        File(httpTtsFolderPath).listFiles()?.filter { it.isFile && it.name.endsWith(".mp3") }
            ?.forEach { httpCacheFiles.add(it.name.removeSuffix(".mp3")) }
        val sysCacheFiles = mutableSetOf<String>()
        File(sysTtsFolderPath).listFiles()?.filter { it.isFile && it.name.endsWith(".wav") }
            ?.forEach { sysCacheFiles.add(it.name.removeSuffix(".wav")) }

        val title = textChapter.title
        val speechRateHttp = AppConfig.speechRatePlay + 5
        val engine = ReadAloud.ttsEngine ?: "default"
        val voice = AppConfig.sysTtsVoiceName ?: "default"
        val rateSys = (AppConfig.ttsSpeechRate + 5) / 10f

        // 朗读服务 contentList 会过滤空段落，索引只计非空段落，
        // 因此这里也过滤后按顺序匹配，避免空行导致后续索引偏移。
        val nonEmptyParagraphs = paragraphs.filter {
            prepareParagraphText(it.text).trim().isNotEmpty()
        }

        nonEmptyParagraphs.forEachIndexed { index, paragraph ->
            val rawText = prepareParagraphText(paragraph.text)
            val indexPart = "|$index"

            // 1) 尝试 HttpTTS 缓存（使用 trim 后的文本，与 HttpReadAloudService.getFileNameHelper 保持一致）
            val httpFileName = MD5Utils.md5Encode16(title.trim()) + "_" +
                    MD5Utils.md5Encode16("$speechRateHttp-$indexPart-|${rawText.trim()}")
            if (httpCacheFiles.contains(httpFileName)) {
                cachedParagraphMap[paragraph.num] = File("${httpTtsFolderPath}$httpFileName.mp3")
                return@forEachIndexed
            }

            // 2) 尝试系统 TTS 缓存（使用原始文本计算，与 TTSReadAloudService 一致）
            val titlePart = if (title.isNotEmpty()) {
                MD5Utils.md5Encode16(title.trim()) + "_"
            } else ""

            val key = MD5Utils.md5Encode16("$engine|$voice|$rateSys$indexPart|$rawText")
            val sysFileName = "$titlePart$key"
            if (sysCacheFiles.contains(sysFileName)) {
                cachedParagraphMap[paragraph.num] = File("${sysTtsFolderPath}$sysFileName.wav")
            }
        }
    }

    fun hasCache(paragraphNum: Int): Boolean = cachedParagraphMap.containsKey(paragraphNum)

    /**
     * 删除指定段落的音频缓存，不用确认框，直接删除。
     * @return 是否成功删除
     */
    fun deleteCache(textChapter: TextChapter, paragraphNum: Int): Boolean {
        val file = cachedParagraphMap[paragraphNum] ?: getCacheFile(textChapter, paragraphNum)
        ?: return false
        val deleted = file.delete()
        // HttpTTS 可能还有对应的 .seginfo
        if (deleted && file.name.endsWith(".mp3")) {
            File("${file.absolutePath}.seginfo").delete()
        }
        if (deleted) {
            cachedParagraphMap.remove(paragraphNum)
        }
        return deleted
    }

    /**
     * 获取指定段落的缓存文件（如果存在）。
     * @return 缓存文件，不存在则返回 null
     */
    fun getCacheFile(textChapter: TextChapter, paragraphNum: Int): File? {
        // 优先返回扫描时记录的文件
        cachedParagraphMap[paragraphNum]?.let { return it }

        val paragraphs = textChapter.getParagraphs(false)
        val paragraph = paragraphs.find { it.num == paragraphNum } ?: return null
        val rawText = prepareParagraphText(paragraph.text)
        if (rawText.trim().isEmpty()) return null

        val nonEmptyParagraphs = paragraphs.filter { prepareParagraphText(it.text).trim().isNotEmpty() }
        val index = nonEmptyParagraphs.indexOfFirst { it.num == paragraphNum }
        if (index < 0) return null
        val indexPart = "|$index"
        val title = textChapter.title

        // 1) 尝试 HttpTTS（使用 trim 后的文本，与 HttpReadAloudService.getFileNameHelper 保持一致）
        val speechRateHttp = AppConfig.speechRatePlay + 5
        val httpFileName = MD5Utils.md5Encode16(title.trim()) + "_" +
                MD5Utils.md5Encode16("$speechRateHttp-$indexPart-|${rawText.trim()}")
        File("${httpTtsFolderPath}$httpFileName.mp3").takeIf { it.exists() }?.let { return it }

        // 2) 尝试系统 TTS
        val engine = ReadAloud.ttsEngine ?: "default"
        val voice = AppConfig.sysTtsVoiceName ?: "default"
        val rateSys = (AppConfig.ttsSpeechRate + 5) / 10f

        val titlePart = if (title.isNotEmpty()) MD5Utils.md5Encode16(title.trim()) + "_" else ""
        val key = MD5Utils.md5Encode16("$engine|$voice|$rateSys$indexPart|$rawText")
        val file = File("${sysTtsFolderPath}$titlePart$key.wav")
        if (file.exists()) return file
        return null
    }

    fun clearChapterState() {
        cachedParagraphMap.clear()
    }
}
