package io.legado.app.service

import android.media.MediaMetadataRetriever
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.config.AppConfig
import io.legado.app.model.AiImagePersistentCache
import io.legado.app.model.ReadAloud
import io.legado.app.utils.AudioConcatUtil
import io.legado.app.utils.AudioConvertUtil
import io.legado.app.utils.AudioLogCollector
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.SubTitleUtils
import io.legado.app.utils.VideoComposeUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File

/**
 * HTTP TTS 音频缓存工具类
 * 用于读取和合并已缓存的音频文件
 * 复用 HttpReadAloudService 中的缓存逻辑，确保文件名生成规则一致
 *
 * 已扩展：同时支持系统TTS缓存（TTSReadAloudService）
 */
object HttpTtsAudioCache {

    private val ttsFolderPath: String by lazy {
        val baseDir = appCtx.getExternalFilesDir(null) ?: appCtx.filesDir
        baseDir.absolutePath + File.separator + "httpTTS" + File.separator
    }

    /** 系统TTS缓存目录（与 TTSReadAloudService.ttsFolderPath 保持一致） */
    private val sysTtsCacheDir: File by lazy {
        File(appCtx.externalCacheDir, "systemTTS").apply { if (!exists()) mkdirs() }
    }

    private val speechRate: Int by lazy {
        AppConfig.speechRatePlay + 5
    }

    /**
     * 获取 TTS 缓存文件夹路径（HttpTTS）
     */
    fun getCacheFolderPath(): String = ttsFolderPath

    /**
     * 生成文件名（与 HttpReadAloudService.getFileNameHelper 保持一致）
     */
    fun getFileName(title: String, content: String, index: Int = -1): String {
        val t = title.trim()
        val c = content.trim()
        val indexPart = if (index >= 0) "|$index" else ""
        // 必须与 HttpReadAloudService.getFileNameHelper 保持完全一致
        return MD5Utils.md5Encode16(t) + "_" + MD5Utils.md5Encode16("$speechRate-$indexPart-|$c")
    }

    /**
     * 生成系统TTS缓存文件名（与 TTSReadAloudService.getCacheFileForText 保持一致）
     */
    private fun getSysTtsFileName(
        chapterTitle: String,
        text: String,
        index: Int = -1
    ): String {
        val engine = ReadAloud.ttsEngine ?: "default"
        val rate = (AppConfig.ttsSpeechRate + 5) / 10f
        val voice = AppConfig.sysTtsVoiceName ?: "default"
        val indexPart = if (index >= 0) "|$index" else ""
        val key = MD5Utils.md5Encode16("$engine|$voice|$rate$indexPart|$text")
        val titlePart = if (chapterTitle.isNotEmpty()) {
            MD5Utils.md5Encode16(chapterTitle.trim()) + "_"
        } else ""
        return "$titlePart$key.wav"
    }

    /**
     * 查找系统TTS缓存文件。
     * 尝试 index=segmentIndex，若找不到则尝试不带 index 的版本。
     */
    private fun findSysTtsCacheFile(
        chapterTitle: String,
        text: String,
        segmentIndex: Int
    ): File? {
        // 1. 尝试带 index 的版本
        val nameWithIndex = getSysTtsFileName(chapterTitle, text, segmentIndex)
        val fileWithIndex = File(sysTtsCacheDir, nameWithIndex)
        if (fileWithIndex.exists() && fileWithIndex.length() > 0) {
            return fileWithIndex
        }
        // 2. 尝试不带 index 的版本（fallback）
        val nameWithoutIndex = getSysTtsFileName(chapterTitle, text, -1)
        val fileWithoutIndex = File(sysTtsCacheDir, nameWithoutIndex)
        if (fileWithoutIndex.exists() && fileWithoutIndex.length() > 0) {
            return fileWithoutIndex
        }
        return null
    }

    /**
     * 检查缓存文件是否存在
     */
    fun hasCacheFile(fileName: String): Boolean {
        return FileUtils.exist("${ttsFolderPath}$fileName.mp3")
    }

    /**
     * 获取缓存文件
     */
    fun getCacheFile(fileName: String): File? {
        val file = File("${ttsFolderPath}$fileName.mp3")
        return if (file.exists()) file else null
    }

    /**
     * 检查是否是静音文件（2160字节）
     */
    fun isSilentFile(file: File): Boolean {
        return file.exists() && file.length() == 2160L
    }

    /**
     * 获取章节所有缓存音频文件（按朗读顺序）
     * 支持 HttpTTS 和系统TTS 两种缓存。
     * 对每个段落：优先查找 HttpTTS 缓存，找不到再找系统TTS 缓存。
     *
     * @param chapter 章节对象
     * @param book 书籍对象
     * @return 缓存文件列表（按顺序排列）
     */
    fun getChapterCachedAudioFiles(
        book: Book,
        chapter: BookChapter
    ): List<File> {
        val audioFiles = mutableListOf<File>()

        // 获取内容列表（与 HttpReadAloudService.preDownloadAudios 完全一致）
        val segments = getChapterSegments(book, chapter)

        segments.forEachIndexed { index, text ->
            val fileName = getFileName(chapter.title, text, index)
            val speakText = text.replace(AppPattern.notReadAloudRegex, "")

            if (speakText.isEmpty()) {
                // 全符号/空文本，先查 HttpTTS 静音缓存
                val httpFile = File("${ttsFolderPath}$fileName.mp3")
                if (httpFile.exists() && httpFile.length() > 0) {
                    audioFiles.add(httpFile)
                    return@forEachIndexed
                }
                // 再查系统TTS静音缓存（静默文件）
                val sysFile = findSysTtsCacheFile(chapter.title, text, index)
                if (sysFile != null) {
                    audioFiles.add(sysFile)
                }
            } else {
                // 正常文本，先查 HttpTTS 缓存
                val httpFile = File("${ttsFolderPath}$fileName.mp3")
                if (httpFile.exists() && httpFile.length() > 0) {
                    audioFiles.add(httpFile)
                    return@forEachIndexed
                }
                // 再查系统TTS 缓存
                val sysFile = findSysTtsCacheFile(chapter.title, text, index)
                if (sysFile != null) {
                    audioFiles.add(sysFile)
                }
            }
        }

        return audioFiles
    }

    /**
     * 获取章节的朗读片段列表（与 HttpReadAloudService.preDownloadAudios 逻辑一致）
     */
    private fun getChapterSegments(book: Book, chapter: BookChapter): List<String> {
        val segments = mutableListOf<String>()

        // 获取原始内容
        val rawContent = BookHelp.getContent(book, chapter) ?: return emptyList()

        // 内容处理（与 getPurifiedChapterContent 逻辑一致）
        val contentProcessor = ContentProcessor.get(book)
        val bookContent = contentProcessor.getContent(
            book = book,
            chapter = chapter,
            content = rawContent,
            includeTitle = false, // 不在这里包含标题，后面单独添加
            useReplace = AppConfig.replaceEnableDefault && book.getUseReplaceRule(),
            chineseConvert = AppConfig.chineseConverterType != 0,
            reSegment = book.getReSegment()
        )

        // 如果需要朗读标题，添加标题（与预下载逻辑一致）
        if (AppConfig.readAloudTitle) {
            segments.add(chapter.title)
        }

        // 添加正文内容（按换行分割，与预下载逻辑一致）
        segments.addAll(bookContent.textList)

        return segments
    }

    /**
     * 获取章节的朗读片段列表（直接传入内容列表，用于兼容性）
     */
    fun getChapterCachedAudioFilesByContent(
        chapterTitle: String,
        contentList: List<String>
    ): List<File> {
        val audioFiles = mutableListOf<File>()

        contentList.forEachIndexed { index, text ->
            val fileName = getFileName(chapterTitle, text, index)
            val speakText = text.replace(AppPattern.notReadAloudRegex, "")

            if (speakText.isEmpty()) {
                // 全符号/空文本，先查 HttpTTS 静音缓存
                val httpFile = File("${ttsFolderPath}$fileName.mp3")
                if (httpFile.exists() && httpFile.length() > 0) {
                    audioFiles.add(httpFile)
                    return@forEachIndexed
                }
                val sysFile = findSysTtsCacheFile(chapterTitle, text, index)
                if (sysFile != null) {
                    audioFiles.add(sysFile)
                }
            } else {
                // 正常文本，先查 HttpTTS 缓存
                val httpFile = File("${ttsFolderPath}$fileName.mp3")
                if (httpFile.exists() && httpFile.length() > 0) {
                    audioFiles.add(httpFile)
                    return@forEachIndexed
                }
                // 再查系统TTS 缓存
                val sysFile = findSysTtsCacheFile(chapterTitle, text, index)
                if (sysFile != null) {
                    audioFiles.add(sysFile)
                }
            }
        }

        return audioFiles
    }

    /**
     * 查找章节中某段文本对应的缓存音频文件（用于字幕生成）
     * 支持 HttpTTS 和系统TTS。
     *
     * @return 找到的缓存文件，或 null
     */
    fun findCacheFileForSegment(
        chapterTitle: String,
        segmentText: String,
        segmentIndex: Int = -1
    ): File? {
        // 1. 先查找 HttpTTS 缓存
        val fileName = getFileName(chapterTitle, segmentText, segmentIndex)
        val httpFile = File("${ttsFolderPath}$fileName.mp3")
        if (httpFile.exists() && httpFile.length() > 0) {
            return httpFile
        }
        // 2. 再查找系统TTS 缓存
        return findSysTtsCacheFile(chapterTitle, segmentText, segmentIndex)
    }

    /**
     * 检查章节是否有缓存音频
     */
    fun hasChapterCache(book: Book, chapter: BookChapter): Boolean {
        return getChapterCachedAudioFiles(book, chapter).isNotEmpty()
    }

    /**
     * 获取章节缓存文件的数量
     */
    fun getChapterCacheCount(book: Book, chapter: BookChapter): Int {
        return getChapterCachedAudioFiles(book, chapter).size
    }

    /**
     * 自动合并章节缓存音频并保存。
     * 支持 HttpTTS 和系统TTS 双模式缓存查找。
     * 根据设置决定输出格式：WAV 或 M4A。
     */
    suspend fun mergeChapterAudioAuto(
        book: Book,
        chapter: BookChapter,
        chapterIndex: Int,
        service: BaseReadAloudService? = null
    ) {
        try {
            val audioFiles = getChapterCachedAudioFiles(book, chapter)
            if (audioFiles.isEmpty()) {
                AppLog.put("自动合并: 无缓存音频, chapter=${chapter.title}")
                return
            }

            val outputFolder = AudioConcatUtil.getDownloadFolder(book.name)
            val chapterTitle = AudioConcatUtil.sanitizeChapterTitle(chapter.title)
            val filePrefix = "${String.format("%04d", chapterIndex + 1)}_$chapterTitle"
            val wavFile = File(outputFolder, "${filePrefix}.wav")

            // 混音分支：开启混音开关、已传入 service、且音效模式非关闭时，尝试带音效合并
            if (AppConfig.mixSoundEffectOnMerge && service != null) {
                val mode = if (book.bookUrl.isNotBlank())
                    AppConfig.getEffectiveSoundEffectMode(book.bookUrl) else AppConfig.soundEffectMode
                if (mode != "off" && tryMixWithEffects(book, chapter, chapterIndex, audioFiles)) {
                    AppLog.put("带音效合并完成: ${wavFile.name}")
                    // 开启「保存为 M4A」：把混音完成的 WAV 转成 M4A，否则保留 WAV
                    if (AppConfig.convertToMp3AfterMerge) {
                        val m4aFile = File(outputFolder, "${filePrefix}.m4a")
                        if (AudioConvertUtil.wavToAac(wavFile, m4aFile, 64000) && m4aFile.exists() && m4aFile.length() > 0) {
                            wavFile.delete()
                            AppLog.put("带音效合并(M4A)完成: ${m4aFile.name}")
                        } else {
                            AppLog.put("带音效合并转 M4A 失败，保留 WAV: ${wavFile.name}")
                        }
                    }
                    if (AppConfig.saveVideoWithMerge) {
                        mergeChapterVideo(book, chapter, chapterIndex, audioFiles, outputFolder, filePrefix)
                    } else if (AppConfig.saveTextWithMerge) {
                        saveChapterSrtAuto(book, chapter, outputFolder, filePrefix)
                    }
                    return
                }
            }

            // 原合并逻辑（不含音效）
            val convertToM4a = AppConfig.convertToMp3AfterMerge
            val m4aFile = File(outputFolder, "${filePrefix}.m4a")
            val tempDir = File(outputFolder, "temp_m4a_auto_${System.currentTimeMillis()}")

            if (convertToM4a) {
                tempDir.mkdirs()
                val m4aFiles = AudioConvertUtil.convertWavFilesToM4aParallel(
                    inputFiles = audioFiles,
                    outputDir = tempDir,
                    bitRate = 64000,
                    maxThreads = 30
                )
                val validM4aFiles = m4aFiles.filterNotNull()
                if (validM4aFiles.isNotEmpty()) {
                    val sortedM4aFiles = validM4aFiles.sortedBy { it.name }
                    val success = AudioConcatUtil.concatM4aFiles(
                        inputFiles = sortedM4aFiles,
                        outputFile = m4aFile
                    )
                    if (success) {
                        AppLog.put("合并完成: ${m4aFile.name}")
                    }
                    sortedM4aFiles.forEach { it.delete() }
                    tempDir.delete()
                } else {
                    val success = AudioConcatUtil.concatAudioFiles(audioFiles, wavFile)
                    if (success) {
                        AppLog.put("合并完成: ${wavFile.name}")
                    }
                }
            } else {
                val success = AudioConcatUtil.concatAudioFiles(audioFiles, wavFile)
                if (success) {
                    AppLog.put("合并完成: ${wavFile.name}")
                }
            }

            if (AppConfig.saveVideoWithMerge) {
                mergeChapterVideo(book, chapter, chapterIndex, audioFiles, outputFolder, filePrefix)
            } else if (AppConfig.saveTextWithMerge) {
                saveChapterSrtAuto(book, chapter, outputFolder, filePrefix)
            }
        } catch (e: Exception) {
            AppLog.put("自动合并异常: ${e.localizedMessage}", e)
        }
    }

    /**
     * 尝试将章节缓存音频与音效混音合并为完整 WAV。
     * 复用播放流程的 applySoundEffectRules / 音效下载解码逻辑。
     * @return true 表示混音成功并写出文件
     */
    private suspend fun tryMixWithEffects(
        book: Book,
        chapter: BookChapter,
        chapterIndex: Int,
        audioFiles: List<File>
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val segments = getChapterSegments(book, chapter)
                if (segments.size != audioFiles.size) {
                    AppLog.put("混音: 段落数(${segments.size})与音频数(${audioFiles.size})不一致，跳过混音")
                    return@withContext false
                }
                val segInfos = mutableListOf<AudioConcatUtil.SegmentInfo>()
                segments.forEach { text ->
                    val (clean, effects) = BaseReadAloudService.extractSoundEffectsForMerge(text)
                    segInfos.add(
                        AudioConcatUtil.SegmentInfo(
                            textLength = clean.length,
                            effects = effects.map {
                                AudioConcatUtil.SegmentEffect(it.fileName, it.charOffset)
                            }
                        )
                    )
                }
                val volume = AppConfig.soundEffectVolume / 100f
                val outputFolder = AudioConcatUtil.getDownloadFolder(book.name)
                val chapterTitle = AudioConcatUtil.sanitizeChapterTitle(chapter.title)
                val filePrefix = "${String.format("%04d", chapterIndex + 1)}_$chapterTitle"
                val wavFile = File(outputFolder, "${filePrefix}.wav")
                AudioConcatUtil.mixChapterWithEffects(
                    audioFiles = audioFiles,
                    segments = segInfos,
                    volume = volume,
                    outputFile = wavFile,
                    effectFileProvider = { fileName -> BaseReadAloudService.getSoundEffectAudioFile(fileName) }
                )
            } catch (e: Exception) {
                AppLog.put("混音异常: ${e.localizedMessage}", e)
                false
            }
        }
    }

    /**
     * 将章节缓存音频与音效混音合并为完整 WAV（无需 service 实例，供目录界面手动保存复用）。
     * 复用 BaseReadAloudService 的静态音效解析/下载解码逻辑。
     * @return true 表示混音成功并写出文件
     */
    suspend fun mergeChapterAudioWithEffects(
        book: Book,
        chapter: BookChapter,
        audioFiles: List<File>,
        outputFolder: File,
        filePrefix: String
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val segments = getChapterSegments(book, chapter)
                if (segments.size != audioFiles.size) {
                    AppLog.put("混音: 段落数(${segments.size})与音频数(${audioFiles.size})不一致，跳过混音")
                    return@withContext false
                }
                val segInfos = mutableListOf<AudioConcatUtil.SegmentInfo>()
                segments.forEach { text ->
                    val (clean, effects) = BaseReadAloudService.extractSoundEffectsForMerge(text)
                    segInfos.add(
                        AudioConcatUtil.SegmentInfo(
                            textLength = clean.length,
                            effects = effects.map {
                                AudioConcatUtil.SegmentEffect(it.fileName, it.charOffset)
                            }
                        )
                    )
                }
                val volume = AppConfig.soundEffectVolume / 100f
                val wavFile = File(outputFolder, "${filePrefix}.wav")
                AudioConcatUtil.mixChapterWithEffects(
                    audioFiles = audioFiles,
                    segments = segInfos,
                    volume = volume,
                    outputFile = wavFile,
                    effectFileProvider = { fileName -> BaseReadAloudService.getSoundEffectAudioFile(fileName) }
                )
            } catch (e: Exception) {
                AppLog.put("混音异常: ${e.localizedMessage}", e)
                false
            }
        }
    }

    /**
     * 将章节缓存音频与已保存的 AI 图片合成为 MP4 视频（字幕烧入画面）。
     * 复用 [AiImagePersistentCache.getChapterImageTracks] 查询已保存图片，绝不重新生图。
     * 某章没有任何已保存图片时返回 false（调用方据此报错/提示）。
     * 成功后将删除独立的 WAV/M4A 音频文件，只保留 MP4。
     */
    suspend fun mergeChapterVideo(
        book: Book,
        chapter: BookChapter,
        chapterIndex: Int,
        audioFiles: List<File>,
        outputFolder: File,
        filePrefix: String
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val imageTracks = AiImagePersistentCache.getChapterImageTracks(book.name, chapterIndex, chapter.title)
                if (imageTracks.isEmpty()) {
                    AppLog.put("保存视频: 本章没有已保存的AI图片，无法生成视频: ${chapter.title}")
                    return@withContext false
                }
                val segmentTexts = getChapterSegments(book, chapter)
                val mp4File = File(outputFolder, "${filePrefix}.mp4")
                val ok = VideoComposeUtil.composeSlideshowVideo(
                    audioFiles = audioFiles,
                    imageTracks = imageTracks.map {
                        VideoComposeUtil.ImageTrack(it.file, it.startPara, it.endPara)
                    },
                    segmentTexts = segmentTexts,
                    outputFile = mp4File,
                    frameRate = 10,
                    burnSubtitle = AppConfig.saveVideoWithMerge,
                    subtitleFontSizeScale = AppConfig.videoSubtitleFontSizeScale,
                    subtitleVOffset = AppConfig.videoSubtitleVOffset,
                    splitSubtitle = AppConfig.videoSubtitleSplit,
                    subtitleMaxChars = AppConfig.srtSubtitleMaxChars
                )
                if (ok && mp4File.exists() && mp4File.length() > 0) {
                    // 只保留视频，删除独立的音频文件
                    File(outputFolder, "${filePrefix}.wav").delete()
                    File(outputFolder, "${filePrefix}.m4a").delete()
                    true
                } else {
                    AppLog.put("保存视频失败: ${chapter.title}")
                    false
                }
            } catch (e: Exception) {
                AppLog.put("保存视频异常: ${e.localizedMessage}", e)
                false
            }
        }
    }

    /**
     * 保存章节字幕为 SRT 文件（双模式缓存查找）
     */
    private suspend fun saveChapterSrtAuto(book: Book, chapter: BookChapter, outputFolder: File, filePrefix: String) {
        try {
            val chapterSegments = getChapterSegments(book, chapter)
            if (chapterSegments.isEmpty()) {
                AppLog.put("自动合并: 未获取到章节文本")
                return
            }

            val retriever = MediaMetadataRetriever()
            val segments = mutableListOf<SubTitleUtils.AudioSegment>()

            try {
                for ((index, segmentText) in chapterSegments.withIndex()) {
                    val cacheFile = findCacheFileForSegment(chapter.title, segmentText, index)
                    if (cacheFile == null) {
                        AppLog.putDebug("字幕生成: 段落无缓存，舍弃: ${segmentText.take(20)}...")
                        continue
                    }
                    val duration = try {
                        retriever.setDataSource(cacheFile.absolutePath)
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    } catch (e: Exception) {
                        AppLog.putDebug("字幕生成: 读取时长失败: ${e.message}")
                        0L
                    }
                    segments.add(SubTitleUtils.AudioSegment(segmentText, duration))
                }
            } finally {
                retriever.release()
            }

            if (segments.isEmpty()) {
                AppLog.put("自动合并: 没有有效的音频片段生成字幕")
                return
            }

            val srtContent = SubTitleUtils.generateSrtFromAudioSegments(
                segments,
                AppConfig.srtSubtitleMaxChars,
                AppConfig.srtSubtitleTimeOffset
            )
            val srtFile = File(outputFolder, "${filePrefix}.srt")
            withContext(Dispatchers.IO) {
                srtFile.writeText(srtContent, Charsets.UTF_8)
            }
            AppLog.put("自动合并: SRT字幕保存成功: ${srtFile.absolutePath}")
        } catch (e: Exception) {
            AppLog.put("自动合并: 生成SRT字幕失败: ${e.localizedMessage}", e)
        }
    }
}
