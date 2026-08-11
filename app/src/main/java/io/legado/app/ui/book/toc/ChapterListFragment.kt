package io.legado.app.ui.book.toc

import android.annotation.SuppressLint
import android.app.Activity.RESULT_OK
import android.media.MediaMetadataRetriever
import android.content.Intent
import android.graphics.PorterDuff
import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.databinding.FragmentChapterListBinding
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isVideo
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.ui.widget.recycler.UpLinearLayoutManager
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.service.HttpTtsAudioCache
import io.legado.app.utils.AudioConcatUtil
import io.legado.app.utils.AudioConvertUtil
import io.legado.app.utils.AudioLogCollector
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.SubTitleUtils
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.longToastOnUi
import io.legado.app.utils.observeEvent
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ChapterListFragment : VMBaseFragment<TocViewModel>(R.layout.fragment_chapter_list),
    ChapterListAdapter.Callback,
    TocViewModel.ChapterListCallBack {
    override val viewModel by activityViewModels<TocViewModel>()
    private val binding by viewBinding(FragmentChapterListBinding::bind)
    private val mLayoutManager by lazy { UpLinearLayoutManager(requireContext()) }
    private val adapter by lazy { ChapterListAdapter(requireContext(), this) }
    private var durChapterIndex = 0

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) = binding.run {
        viewModel.chapterListCallBack = this@ChapterListFragment
        val bbg = bottomBackground
        val btc = requireContext().getPrimaryTextColor(ColorUtils.isColorLight(bbg))
        llChapterBaseInfo.setBackgroundColor(bbg)
        tvCurrentChapterInfo.setTextColor(btc)
        ivChapterTop.setColorFilter(btc, PorterDuff.Mode.SRC_IN)
        ivChapterBottom.setColorFilter(btc, PorterDuff.Mode.SRC_IN)
        initRecyclerView()
        initView()
        viewModel.bookData.observe(this@ChapterListFragment) {
            initBook(it)
        }
    }

    private fun initRecyclerView() {
        binding.recyclerView.layoutManager = mLayoutManager
        binding.recyclerView.addItemDecoration(VerticalDivider(requireContext()))
        binding.recyclerView.adapter = adapter
    }

    private fun initView() = binding.run {
        ivChapterTop.setOnClickListener {
            mLayoutManager.scrollToPositionWithOffset(0, 0)
        }
        ivChapterBottom.setOnClickListener {
            if (adapter.itemCount > 0) {
                mLayoutManager.scrollToPositionWithOffset(adapter.itemCount - 1, 0)
            }
        }
        tvCurrentChapterInfo.setOnClickListener {
            mLayoutManager.scrollToPositionWithOffset(durChapterIndex, 0)
        }
        binding.llChapterBaseInfo.applyNavigationBarPadding()
    }

    @SuppressLint("SetTextI18n")
    private fun initBook(book: Book) {
        lifecycleScope.launch {
            upChapterList(null)
            durChapterIndex = book.durChapterIndex
            binding.tvCurrentChapterInfo.text =
                "${book.durChapterTitle}(${book.durChapterIndex + 1}/${book.simulatedTotalChapterNum()})"
            initCacheFileNames(book)
            checkAiCacheForItems()
        }
    }

    private fun checkAiCacheForItems() {
        val book = book ?: return
        lifecycleScope.launch(IO) {
            val items = adapter.getItems()
            val cachedTitles = hashSetOf<String>()
            items.forEach { chapter ->
                if (AiCacheFileUtil.hasChapterCache(book, chapter)) {
                    cachedTitles.add(chapter.title)
                }
            }
            withContext(Main) {
                adapter.aiCacheTitles.clear()
                adapter.aiCacheTitles.addAll(cachedTitles)
                adapter.notifyItemRangeChanged(0, adapter.itemCount, true)
            }
        }
    }

    private fun initCacheFileNames(book: Book) {
        lifecycleScope.launch(IO) {
            adapter.cacheFileNames.addAll(BookHelp.getChapterFiles(book))
            withContext(Main) {
                adapter.notifyItemRangeChanged(0, adapter.itemCount, true)
            }
        }
    }

    override fun observeLiveBus() {
        observeEvent<Pair<Book, BookChapter>>(EventBus.SAVE_CONTENT) { (book, chapter) ->
            viewModel.bookData.value?.bookUrl?.let { bookUrl ->
                if (book.bookUrl == bookUrl) {
                    adapter.cacheFileNames.add(chapter.getFileName())
                    if (viewModel.searchKey.isNullOrEmpty()) {
                        adapter.notifyItemChanged(chapter.index, true)
                    } else {
                        adapter.getItems().forEachIndexed { index, bookChapter ->
                            if (bookChapter.index == chapter.index) {
                                adapter.notifyItemChanged(index, true)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun upChapterList(searchKey: String?) {
        lifecycleScope.launch {
            withContext(IO) {
                val end = (book?.simulatedTotalChapterNum() ?: Int.MAX_VALUE) - 1
                when {
                    searchKey.isNullOrBlank() ->
                        appDb.bookChapterDao.getChapterList(viewModel.bookUrl, 0, end).also {
                            chapterList = it
                        }

                    else -> appDb.bookChapterDao.search(viewModel.bookUrl, searchKey, 0, end)
                }
            }.let {
                adapter.setItems(it)
                checkAiCacheForItems()
            }
        }
    }

    override fun onListChanged() {
        lifecycleScope.launch {
            var scrollPos = 0
            withContext(Default) {
                adapter.getItems().forEachIndexed { index, bookChapter ->
                    if (bookChapter.index >= durChapterIndex) {
                        return@withContext
                    }
                    scrollPos = index
                }
            }
            mLayoutManager.scrollToPositionWithOffset(scrollPos, 0)
            adapter.upDisplayTitles(scrollPos)
        }
    }

    override fun clearDisplayTitle() {
        adapter.clearDisplayTitle()
        adapter.upDisplayTitles(mLayoutManager.findFirstVisibleItemPosition())
    }

    override fun upAdapter() {
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
    }

    override val scope: CoroutineScope
        get() = lifecycleScope

    override val book: Book?
        get() = viewModel.bookData.value

    override val isLocalBook: Boolean
        get() = viewModel.bookData.value?.isLocal == true

    override fun durChapterIndex(): Int {
        return durChapterIndex
    }
    private var chapterList: List<BookChapter>? = null

    override fun deleteAiCache(bookChapter: BookChapter) {
        val book = book ?: return
        lifecycleScope.launch(IO) {
            val deleted = AiCacheFileUtil.deleteChapterCache(book, bookChapter)
            withContext(Main) {
                if (deleted) {
                    adapter.aiCacheTitles.remove(bookChapter.title)
                    adapter.getItems().forEachIndexed { index, ch ->
                        if (ch.title == bookChapter.title) {
                            adapter.notifyItemChanged(index, true)
                        }
                    }
                    context?.longToastOnUi("已删除 [${bookChapter.title}] 的AI缓存")
                }
            }
        }
    }

    fun deleteAllAiCache() {
        val book = book ?: return
        lifecycleScope.launch(IO) {
            val deletedCount = AiCacheFileUtil.deleteAllChapterCaches(book)
            withContext(Main) {
                adapter.aiCacheTitles.clear()
                adapter.notifyItemRangeChanged(0, adapter.itemCount, true)
                context?.longToastOnUi("已删除本书 $deletedCount 个章节的AI缓存")
            }
        }
    }

    override fun openChapter(bookChapter: BookChapter) {
        activity?.run {
            if (book?.isVideo == true) {
                val volumes = arrayListOf<BookChapter>()
                chapterList?.forEach { chapter ->
                    if (chapter.isVolume) {
                        volumes.add(chapter)
                    }
                }
                var chapterInVolumeIndex = 0
                var durVolumeIndex = 0
                if (volumes.isNotEmpty()) {
                    for ((index, volume) in volumes.reversed().withIndex()) {
                        val first = bookChapter.index
                        if (volume.index < first) {
                            chapterInVolumeIndex = first - volume.index - 1
                            durVolumeIndex = volumes.size - index - 1
                            break
                        } else if (volume.index == first) {
                            chapterInVolumeIndex = 0
                            durVolumeIndex = volumes.size - index - 1
                            break
                        }
                    }
                } else {
                    chapterInVolumeIndex = bookChapter.index
                }
                setResult(
                    RESULT_OK, Intent()
                        .putExtra("index", bookChapter.index)
                        .putExtra("chapterChanged", bookChapter.index != durChapterIndex)
                        .putExtra("durVolumeIndex", durVolumeIndex)
                        .putExtra("chapterInVolumeIndex", chapterInVolumeIndex)
                )
                finish()
                return@run
            }
            setResult(
                RESULT_OK, Intent()
                    .putExtra("index", bookChapter.index)
                    .putExtra("chapterChanged", bookChapter.index != durChapterIndex)
            )
            finish()
        }
    }

    override fun downloadChapterAudio(bookChapter: BookChapter) {
        val book = book ?: return

        lifecycleScope.launch {
            // 开始前清空日志收集器
            AudioLogCollector.clear()
            AudioLogCollector.addSeparator("下载任务开始")
            AudioLogCollector.log("书籍: ${book.name}")
            AudioLogCollector.log("章节: ${bookChapter.title}")
            AudioLogCollector.log("时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
            AudioLogCollector.addSeparator("")
            
            withContext(IO) {
                // 步骤1: 获取原始内容并统计字符数
                AudioLogCollector.log("步骤1: 开始获取章节内容")
                val rawContent = io.legado.app.help.book.BookHelp.getContent(book, bookChapter) ?: run {
                    withContext<Unit>(Main) {
                        context?.longToastOnUi("无法获取章节内容")
                    }
                    return@withContext
                }
                val charCount = rawContent.length
                AudioLogCollector.log("步骤1: 已获取内容，共 $charCount 个字符")
                
                withContext<Unit>(Main) {
                    context?.longToastOnUi("步骤1: 已获取内容，共 $charCount 个字符")
                }
                
                // 等待用户确认（2秒延迟，方便看清每步提示）
                kotlinx.coroutines.delay(1000)
                
                // 步骤2: 获取段落列表并统计段数
                AudioLogCollector.log("步骤2: 开始解析段落")
                // 预先获取下载文件夹（提前定义，避免后续作用域问题）
                val downloadFolder = AudioConcatUtil.getDownloadFolder(book.name)
                val contentProcessor = io.legado.app.help.book.ContentProcessor.get(book)
                val bookContent = contentProcessor.getContent(
                    book = book,
                    chapter = bookChapter,
                    content = rawContent,
                    includeTitle = false,
                    useReplace = io.legado.app.help.config.AppConfig.replaceEnableDefault && book.getUseReplaceRule(),
                    chineseConvert = io.legado.app.help.config.AppConfig.chineseConverterType != 0,
                    reSegment = book.getReSegment()
                )
                val paragraphCount = bookContent.textList.size
                AudioLogCollector.log("步骤2: 共解析出 $paragraphCount 段内容")
                
                withContext<Unit>(Main) {
                    context?.longToastOnUi("步骤2: 共解析出 $paragraphCount 段内容")
                }
                
                // 等待用户确认（2秒延迟，方便看清每步提示）
                kotlinx.coroutines.delay(1000)
                
                // 步骤3: 获取缓存的音频文件
                AudioLogCollector.log("步骤3: 开始获取缓存音频文件")
                io.legado.app.constant.AppLog.put("下载: 步骤3 - 开始获取缓存音频文件")
                val audioFiles = HttpTtsAudioCache.getChapterCachedAudioFiles(book, bookChapter)
                
                io.legado.app.constant.AppLog.put("下载: 步骤3 - 共找到 ${audioFiles.size} 个缓存音频文件")
                AudioLogCollector.log("步骤3: 根据 $paragraphCount 段内容，共找到 ${audioFiles.size} 个缓存音频文件")
                withContext<Unit>(Main) {
                    context?.longToastOnUi("步骤3: 根据 $paragraphCount 段内容，共找到 ${audioFiles.size} 个缓存音频文件")
                }
                
                // 等待用户确认（2秒延迟，方便看清每步提示）
                kotlinx.coroutines.delay(1000)
                
                if (audioFiles.isEmpty()) {
                    AudioLogCollector.log("错误: 该章节没有缓存的音频文件")
                    AudioLogCollector.addSeparator("下载任务结束（失败）")
                    withContext<Unit>(Main) {
                        context?.longToastOnUi("该章节没有缓存的音频文件\n请先使用朗读功能播放该章节")
                    }
                    return@withContext
                }

                // 根据设置决定输出文件格式
                val convertToM4a = AppConfig.convertToMp3AfterMerge
                val chapterTitle = AudioConcatUtil.sanitizeChapterTitle(bookChapter.title)
                val filePrefix = "${String.format("%04d", bookChapter.index + 1)}_$chapterTitle"
                val wavFile = File(downloadFolder, "${filePrefix}.wav")
                val m4aFile = File(downloadFolder, "${filePrefix}.m4a")
                val tempDir = File(downloadFolder, "temp_m4a_${System.currentTimeMillis()}")

                var finalOutputFile: File? = null
                var success = false

                // 混音分支：开启「合并时混音效」开关且音效模式非关闭时，尝试带音效合并
                // （与自动合并一致，重叠音效按播放队列行为顺序延后，输出 WAV）
                if (AppConfig.mixSoundEffectOnMerge) {
                    val mode = if (book.bookUrl.isNotBlank())
                        AppConfig.getEffectiveSoundEffectMode(book.bookUrl) else AppConfig.soundEffectMode
                    if (mode != "off") {
                        AudioLogCollector.log("步骤3.5: 尝试带音效合并")
                        val mixed = HttpTtsAudioCache.mergeChapterAudioWithEffects(
                            book = book,
                            chapter = bookChapter,
                            audioFiles = audioFiles,
                            outputFolder = downloadFolder,
                            filePrefix = filePrefix
                        )
                        if (mixed) {
                            success = true
                            if (convertToM4a) {
                                // 开启「保存为 M4A」：把混音完成的 WAV 转成 M4A
                                AudioLogCollector.log("带音效合并完成，开始转换为 M4A")
                                val converted = AudioConvertUtil.wavToAac(wavFile, m4aFile, 64000)
                                if (converted && m4aFile.exists() && m4aFile.length() > 0) {
                                    wavFile.delete()
                                    finalOutputFile = m4aFile
                                    AudioLogCollector.log("带音效合并(M4A)完成: ${m4aFile.absolutePath}")
                                } else {
                                    AudioLogCollector.log("转换为 M4A 失败，保留 WAV")
                                    finalOutputFile = wavFile
                                }
                            } else {
                                finalOutputFile = wavFile
                                AudioLogCollector.log("带音效合并完成: ${wavFile.absolutePath}")
                            }
                        } else {
                            AudioLogCollector.log("带音效合并失败，回退普通合并")
                        }
                    }
                }

                if (finalOutputFile == null) {
                    if (convertToM4a) {
                        // 转换为 M4A：先并行转换所有 WAV -> M4A，再合并
                        AudioLogCollector.log("步骤4: 开始并行转换 WAV -> M4A")
                        withContext<Unit>(Main) {
                            context?.longToastOnUi("步骤4: 开始并行转换 ${audioFiles.size} 个音频文件...")
                        }

                        // 创建临时目录
                        tempDir.mkdirs()

                        // 并行转换所有 WAV 文件为 M4A（最多30线程）
                        val m4aFiles = AudioConvertUtil.convertWavFilesToM4aParallel(
                            inputFiles = audioFiles,
                            outputDir = tempDir,
                            bitRate = 64000,
                            maxThreads = 30
                        )

                        // 过滤转换成功的文件
                        val validM4aFiles = m4aFiles.filterNotNull()

                        if (validM4aFiles.size != audioFiles.size) {
                            AudioLogCollector.log("警告: 只有 ${validM4aFiles.size}/${audioFiles.size} 个文件转换成功")
                        }

                        if (validM4aFiles.isNotEmpty()) {
                            // 按文件名排序以保持原始顺序
                            val sortedM4aFiles = validM4aFiles.sortedBy { it.name }

                            AudioLogCollector.log("步骤5: 开始合并 ${sortedM4aFiles.size} 个 M4A 文件")
                            withContext<Unit>(Main) {
                                context?.longToastOnUi("步骤5: 开始合并 ${sortedM4aFiles.size} 个 M4A 文件...")
                            }

                            // 合并所有 M4A 文件
                            success = AudioConcatUtil.concatM4aFiles(
                                inputFiles = sortedM4aFiles,
                                outputFile = m4aFile
                            ) { completed, total ->
                                AudioLogCollector.log("M4A 合并进度: $completed/$total")
                            }

                            if (success) {
                                finalOutputFile = m4aFile
                                AudioLogCollector.log("M4A 合并成功: ${m4aFile.absolutePath}")
                            }

                            // 清理临时目录
                            sortedM4aFiles.forEach { it.delete() }
                            tempDir.delete()
                        } else {
                            AudioLogCollector.log("没有成功转换的文件，回退到 WAV 格式")
                        }
                    }

                    // 如果没有转换为 M4A 或转换失败，使用 WAV 格式
                    if (finalOutputFile == null) {
                        AudioLogCollector.log("步骤4: 开始合并音频为 WAV 格式")
                        withContext<Unit>(Main) {
                            context?.longToastOnUi("步骤4: 开始合并 ${audioFiles.size} 个音频文件...")
                        }
                        success = AudioConcatUtil.concatAudioFiles(audioFiles, wavFile)
                        if (success) {
                            finalOutputFile = wavFile
                        }
                    }
                }

                // 保存日志文件
                AudioLogCollector.addSeparator("下载任务结束")
                AudioLogCollector.log("合并${if (success) "成功" else "失败"}")
                AudioLogCollector.log("输出文件: ${finalOutputFile?.absolutePath}")

                // 如果开启了保存文本/视频开关，则生成 SRT 字幕或视频
                if (AppConfig.saveVideoWithMerge && success) {
                    // 保存视频：复用已保存的AI图片，将静态画面(字幕烧入)与音频合成为 MP4
                    val videoOk = HttpTtsAudioCache.mergeChapterVideo(
                        book = book,
                        chapter = bookChapter,
                        chapterIndex = bookChapter.index,
                        audioFiles = audioFiles,
                        outputFolder = downloadFolder,
                        filePrefix = filePrefix
                    )
                    if (videoOk) {
                        AudioLogCollector.log("视频生成完成")
                        finalOutputFile = File(downloadFolder, "${filePrefix}.mp4")
                    } else {
                        AudioLogCollector.log("视频生成失败：本章没有已保存的AI图片或合成出错")
                        withContext<Unit>(Main) {
                            context?.longToastOnUi("保存视频失败：本章没有已保存的AI图片")
                        }
                    }
                } else if (AppConfig.saveTextWithMerge && success) {
                    // 生成SRT字幕
                    saveChapterSrt(book, bookChapter, downloadFolder, filePrefix)
                    AudioLogCollector.log("字幕生成完成")
                }

                /* TODO: 如果需要保存 TXT 文件，取消注释以下代码
                if (AppConfig.saveTextWithMerge && success) {
                    val txtFile = File(downloadFolder, "${filePrefix}.txt")
                    val textContent = buildString {
                        appendLine(bookChapter.title)
                        appendLine()
                        append(rawContent)
                    }
                    txtFile.writeText(textContent)
                    AudioLogCollector.log("文本保存: ${txtFile.absolutePath}")
                } */

                withContext<Unit>(Main) {
                    if (success && finalOutputFile != null) {
                        context?.longToastOnUi("完成! 已保存到: ${finalOutputFile.absolutePath}")
                    } else {
                        context?.longToastOnUi("保存失败")
                    }
                }
            }
        }
    }

    /**
     * 保存章节字幕为 SRT 文件
     * 遍历文本段落 → 查找缓存音频（支持 HttpTTS 和系统TTS） → 读取时长 → 生成字幕
     * 没找到缓存音频的段落直接舍弃
     */
    private suspend fun saveChapterSrt(book: Book, chapter: BookChapter, outputFolder: File, filePrefix: String) {
        try {
            // 获取章节的文本片段列表（按朗读顺序）
            val chapterSegments = getChapterSegments(book, chapter)

            if (chapterSegments.isEmpty()) {
                AudioLogCollector.log("手动合并: 未获取到章节文本")
                return
            }

            // 用于读取音频时长
            val retriever = MediaMetadataRetriever()

            // 遍历文本段落，查找缓存音频
            val segments = mutableListOf<SubTitleUtils.AudioSegment>()

            try {
                chapterSegments.forEachIndexed { index, segmentText ->
                    // 同时支持 HttpTTS 和系统TTS 缓存查找
                    val cacheFile = HttpTtsAudioCache.findCacheFileForSegment(
                        chapter.title,
                        segmentText,
                        index
                    )

                    // 没找到缓存音频就舍弃
                    if (cacheFile == null) {
                        AudioLogCollector.log("字幕生成: 段落无缓存，舍弃: ${segmentText.take(20)}...")
                        return@forEachIndexed
                    }

                    // 读取音频时长
                    val duration = try {
                        retriever.setDataSource(cacheFile.absolutePath)
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    } catch (e: Exception) {
                        AudioLogCollector.log("字幕生成: 读取时长失败: ${e.message}")
                        0L
                    }

                    segments.add(SubTitleUtils.AudioSegment(segmentText, duration))
                }
            } finally {
                retriever.release()
            }

            if (segments.isEmpty()) {
                AudioLogCollector.log("手动合并: 没有有效的音频片段生成字幕")
                return
            }

            // 生成SRT字幕
            val srtContent = SubTitleUtils.generateSrtFromAudioSegments(
                segments,
                AppConfig.srtSubtitleMaxChars,
                AppConfig.srtSubtitleTimeOffset
            )

            val srtFile = File(outputFolder, "${filePrefix}.srt")
            srtFile.writeText(srtContent)
            AudioLogCollector.log("SRT字幕保存成功: ${srtFile.absolutePath}")
        } catch (e: Exception) {
            AudioLogCollector.log("手动合并: 生成SRT字幕失败: ${e.localizedMessage}")
        }
    }

    /**
     * 获取章节的朗读片段列表
     */
    private fun getChapterSegments(book: Book, chapter: BookChapter): List<String> {
        val segments = mutableListOf<String>()

        // 获取原始内容
        val rawContent = BookHelp.getContent(book, chapter) ?: return emptyList()

        // 内容处理（includeTitle=false，避免重复添加标题）
        val contentProcessor = io.legado.app.help.book.ContentProcessor.get(book)
        val bookContent = contentProcessor.getContent(
            book = book,
            chapter = chapter,
            content = rawContent,
            includeTitle = false,
            useReplace = AppConfig.replaceEnableDefault && book.getUseReplaceRule(),
            chineseConvert = AppConfig.chineseConverterType != 0,
            reSegment = book.getReSegment()
        ).toString()

        // 如果需要朗读标题，添加标题
        if (AppConfig.readAloudTitle) {
            segments.add(chapter.title)
        }
        if (bookContent.isNotEmpty()) {
            segments.addAll(bookContent.split("\n").filter { it.isNotEmpty() })
        }

        return segments
    }

}