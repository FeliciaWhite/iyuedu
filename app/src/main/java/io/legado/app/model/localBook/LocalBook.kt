package io.legado.app.model.localBook

import android.net.Uri
import android.util.Base64
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.exception.EmptyFileException
import io.legado.app.exception.NoBooksDirException
import io.legado.app.exception.NoStackTraceException
import io.legado.app.exception.TocEmptyException
import io.legado.app.help.AppWebDav
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.addType
import io.legado.app.help.book.archiveName
import io.legado.app.help.book.getArchiveUri
import io.legado.app.help.book.getLocalUri
import io.legado.app.help.book.getRemoteUrl
import io.legado.app.help.book.isArchive
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isMobi
import io.legado.app.help.book.isPdf
import io.legado.app.help.book.isUmd
import io.legado.app.help.book.removeLocalUriCache
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.webdav.WebDav
import io.legado.app.ui.book.toc.AiCacheFileUtil
import org.json.JSONObject
import io.legado.app.lib.webdav.WebDavException
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.find
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getFile
import io.legado.app.utils.inputStream
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isDataUrl
import io.legado.app.utils.isUri
import io.legado.app.utils.printOnDebug
import kotlinx.coroutines.runBlocking
import org.apache.commons.text.StringEscapeUtils
import splitties.init.appCtx
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream
import java.util.regex.Pattern
import kotlin.coroutines.coroutineContext

/**
 * 书籍文件导入 目录正文解析
 * 支持在线文件(txt epub umd 压缩文件 本地文件
 */
object LocalBook {

    private val nameAuthorPatterns = arrayOf(
        Pattern.compile("(.*?)《([^《》]+)》.*?作者：(.*)"),
        Pattern.compile("(.*?)《([^《》]+)》(.*)"),
        Pattern.compile("(^)(.+) 作者：(.+)$"),
        Pattern.compile("(^)(.+) by (.+)$")
    )

    @Throws(FileNotFoundException::class, SecurityException::class)
    fun getBookInputStream(book: Book): InputStream {
        val uri = book.getLocalUri()
        val inputStream = uri.inputStream(appCtx).getOrNull()
            ?: let {
                book.removeLocalUriCache()
                val localArchiveUri = book.getArchiveUri()
                val webDavUrl = book.getRemoteUrl()
                if (localArchiveUri != null) {
                    // 重新导入对应的压缩包
                    importArchiveFile(localArchiveUri, book.originName) {
                        it.contains(book.originName)
                    }.firstOrNull()?.let {
                        getBookInputStream(it)
                    }
                } else if (webDavUrl != null && downloadRemoteBook(book)) {
                    // 下载远程链接
                    getBookInputStream(book)
                } else {
                    null
                }
            }
        if (inputStream != null) return inputStream
        book.removeLocalUriCache()
        throw FileNotFoundException("${uri.path} 文件不存在")
    }

    fun getLastModified(book: Book): Result<Long> {
        return kotlin.runCatching {
            val uri = Uri.parse(book.bookUrl)
            if (uri.isContentScheme()) {
                return@runCatching DocumentFile.fromSingleUri(appCtx, uri)!!.lastModified()
            }
            val file = File(uri.path!!)
            if (file.exists()) {
                return@runCatching file.lastModified()
            }
            throw FileNotFoundException("${uri.path} 文件不存在")
        }
    }

    @Throws(TocEmptyException::class)
    fun getChapterList(book: Book): ArrayList<BookChapter> {
        val chapters = when {
            book.isEpub -> {
                EpubFile.getChapterList(book)
            }

            book.isUmd -> {
                UmdFile.getChapterList(book)
            }

            book.isPdf -> {
                PdfFile.getChapterList(book)
            }

            book.isMobi -> {
                MobiFile.getChapterList(book)
            }

            else -> {
                TextFile.getChapterList(book)
            }
        }
        if (chapters.isEmpty()) {
            throw TocEmptyException(appCtx.getString(R.string.chapter_list_empty))
        }
        val list = ArrayList(LinkedHashSet(chapters))
        list.forEachIndexed { index, bookChapter ->
            bookChapter.index = index
            if (bookChapter.title.isEmpty()) {
                bookChapter.title = "无标题章节"
            }
        }
        //重复标题加序号
        BookHelp.makeTitlesUnique(list)
        val replaceRules = ContentProcessor.get(book).getTitleReplaceRules()
        book.durChapterTitle = list.getOrElse(book.durChapterIndex) { list.last() }
            .getDisplayTitle(replaceRules, book.getUseReplaceRule())
        book.latestChapterTitle =
            list.getOrElse(book.simulatedTotalChapterNum() - 1) { list.last() }
                .getDisplayTitle(replaceRules, book.getUseReplaceRule())
        book.totalChapterNum = list.size
        book.latestChapterTime = System.currentTimeMillis()
        return list
    }

    fun getContent(book: Book, chapter: BookChapter): String? {
        var content = try {
            when {
                book.isEpub -> {
                    EpubFile.getContent(book, chapter)
                }

                book.isUmd -> {
                    UmdFile.getContent(book, chapter)
                }

                book.isPdf -> {
                    PdfFile.getContent(book, chapter)
                }

                book.isMobi -> {
                    MobiFile.getContent(book, chapter)
                }

                else -> {
                    var txtContent = TextFile.getContent(book, chapter)
                    if (!chapter.isVolume) {
                        txtContent = extractAndSaveAnnotations(txtContent, book, chapter)
                    }
                    txtContent
                }
            }
        } catch (e: Exception) {
            e.printOnDebug()
            AppLog.put("获取本地书籍内容失败\n${e.localizedMessage}", e)
            "获取本地书籍内容失败\n${e.localizedMessage}"
        }
        if (book.isEpub) {
            content ?: return null
            if (content.indexOf('&') > -1) {
                content = content.replace("&lt;img", "&lt; img", true)
                return StringEscapeUtils.unescapeHtml4(content)
            }
        }

        if (content.isNullOrEmpty() && !chapter.isVolume) {
            return null
        }

        return content
    }

    private val annotationRegex = "<<([^>]+)>>".toRegex()
    private val annotationDetailRegex = "(.+?)（(.+?)(?:/(.+?))?）".toRegex()

    /**
     * 从TXT正文提取角色标注，保存为AI缓存json，并返回去掉标注后的正文
     * 以去掉标注后的正文为基准：内容变了就重新提取覆盖，没变就保持原样
     */
    private fun extractAndSaveAnnotations(content: String, book: Book, chapter: BookChapter): String {
        val cacheFile = AiCacheFileUtil.getChapterCacheFile(book, chapter)
        val cleanContent = content.replace(annotationRegex, "")
        val contentHash = MD5Utils.md5Encode(cleanContent)

        // 已有缓存且正文未变，直接返回
        if (cacheFile.exists()) {
            try {
                val oldJson = JSONObject(cacheFile.readText())
                if (oldJson.optString("contentHash") == contentHash) {
                    return cleanContent
                }
            } catch (_: Exception) {}
        }

        val results = JSONObject()
        var seq = 0
        var i = 0

        while (i < content.length) {
            if (content[i] == '“') {
                seq++
                val afterQuote = content.substring(i + 1)
                val obj = JSONObject()
                var dialogText = ""

                // 检查 " 后面是否紧跟 <<...>> 标注
                val annotationMatch = annotationRegex.find(afterQuote)
                if (annotationMatch != null && annotationMatch.range.first == 0) {
                    val inner = annotationMatch.groupValues[1].trim()
                    val detailMatch = annotationDetailRegex.matchEntire(inner)
                    if (detailMatch != null) {
                        obj.put("name", detailMatch.groupValues[1].trim())
                        obj.put("gender", detailMatch.groupValues[2].trim())
                        val age = detailMatch.groupValues[3].trim()
                        if (age.isNotEmpty()) obj.put("age", age)
                    } else {
                        obj.put("name", inner)
                    }

                    // 标注后的文本到下一个 " 之间是对话文本
                    val afterAnnotation = afterQuote.substring(annotationMatch.value.length)
                    val endIdx = afterAnnotation.indexOf('”')
                    dialogText = if (endIdx >= 0) afterAnnotation.substring(0, endIdx).trim() else afterAnnotation.trim()
                    i += annotationMatch.value.length
                } else {
                    // 没有标注，提取 " 到 " 之间的文本
                    val endIdx = afterQuote.indexOf('”')
                    dialogText = if (endIdx >= 0) afterQuote.substring(0, endIdx).trim() else afterQuote.trim()
                }

                obj.put("dialogText", dialogText)
                results.put(seq.toString(), obj)
            }
            i++
        }

        if (results.length() == 0) {
            if (cacheFile.exists()) cacheFile.delete()
            return cleanContent
        }

        val json = JSONObject()
        json.put("title", chapter.title)
        json.put("results", results)
        json.put("contentHash", contentHash)

        kotlin.runCatching {
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(json.toString(2))
        }

        return cleanContent
    }

    fun getCoverPath(book: Book): String {
        return getCoverPath(book.bookUrl)
    }

    private fun getCoverPath(bookUrl: String): String {
        return FileUtils.getPath(
            appCtx.externalFiles,
            "covers",
            "${MD5Utils.md5Encode16(bookUrl)}.jpg"
        )
    }

    /**
     * 获取本地书籍同名的封面图片路径
     * 支持常见的图片格式：jpg, jpeg, png, webp, gif, bmp
     * 如果找到本地封面，返回本地封面路径；否则返回null
     * 
     * 兼容处理：
     * 1. 文件不存在时返回null
     * 2. 权限不足时返回null
     * 3. 不支持的文件类型时返回null
     */
    fun getLocalCoverPath(book: Book): String? {
        return kotlin.runCatching {
            val coverExtensions = listOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
            
            // 只处理文件URI，Content URI暂时不支持封面检测
            if (book.bookUrl.startsWith("content://")) {
                return null
            }
            
            val bookUri = if (book.bookUrl.isUri()) {
                book.bookUrl.toUri()
            } else {
                android.net.Uri.fromFile(java.io.File(book.bookUrl))
            }
            
            val bookNameWithoutExt = book.originName.substringBeforeLast(".")
            
            // 获取父目录
            val parentFile = java.io.File(bookUri.path ?: return null).parentFile ?: return null
            val parentDir = FileDoc.fromFile(parentFile)

            // 查找同名图片
            for (ext in coverExtensions) {
                val coverName = "$bookNameWithoutExt.$ext"
                val coverFile = java.io.File(parentFile, coverName)
                if (coverFile.exists() && coverFile.isFile) {
                    val mimeType = FileUtils.getMimeType(coverName)
                    if (mimeType?.startsWith("image/") == true) {
                        return coverFile.absolutePath
                    }
                }
            }
            null
        }.getOrNull()
    }

    /**
     * 获取书籍封面路径，优先使用本地同名图片
     */
    fun getBookCoverPath(book: Book): String {
        // 优先检查本地同名图片
        val localCover = getLocalCoverPath(book)
        if (localCover != null) {
            return localCover
        }
        // 使用默认封面路径
        return getCoverPath(book)
    }

    /**
     * 下载在线的文件并自动导入到阅读（txt umd epub)
     */
    suspend fun importFileOnLine(
        str: String,
        fileName: String,
        source: BaseSource? = null,
    ): Book {
        return importFile(saveBookFile(str, fileName, source))
    }

    /**
     * 导入本地文件
     */
    fun importFile(uri: Uri): Book {
        val bookUrl: String
        //updateTime变量不要修改,否则会导致读取不到缓存
        val (fileName, _, _, updateTime, _) = FileDoc.fromUri(uri, false).apply {
            if (size == 0L) throw EmptyFileException("Unexpected empty File")

            bookUrl = toString()
        }
        var book = appDb.bookDao.getBook(bookUrl)
        if (book == null) {
            val nameAuthor = analyzeNameAuthor(fileName)
            book = Book(
                type = BookType.text or BookType.local,
                bookUrl = bookUrl,
                name = nameAuthor.first,
                author = nameAuthor.second,
                originName = fileName,
                latestChapterTime = updateTime,
                order = appDb.bookDao.minOrder - 1
            )
            upBookInfo(book)
            appDb.bookDao.insert(book)
        } else {
            deleteBook(book, false)
            upBookInfo(book)
            // 触发 isLocalModified
            book.latestChapterTime = 0
            //已有书籍说明是更新,删除原有目录
            appDb.bookChapterDao.delByBook(bookUrl)
        }
        return book
    }

    fun upBookInfo(book: Book) {
        when {
            book.isEpub -> EpubFile.upBookInfo(book)
            book.isUmd -> UmdFile.upBookInfo(book)
            book.isPdf -> PdfFile.upBookInfo(book)
            book.isMobi -> MobiFile.upBookInfo(book)
            else -> {
                // TXT书籍：检查本地同名图片作为封面
                if (book.coverUrl.isNullOrEmpty()) {
                    val localCover = getLocalCoverPath(book)
                    if (localCover != null) {
                        book.coverUrl = localCover
                    }
                }
            }
        }
    }

    /* 导入压缩包内的书籍 */
    fun importArchiveFile(
        archiveFileUri: Uri,
        saveFileName: String? = null,
        filter: ((String) -> Boolean)? = null
    ): List<Book> {
        val archiveFileDoc = FileDoc.fromUri(archiveFileUri, false)
        val files = ArchiveUtils.deCompress(archiveFileDoc, filter = filter)
        if (files.isEmpty()) {
            throw NoStackTraceException(appCtx.getString(R.string.unsupport_archivefile_entry))
        }
        return files.map {
            saveBookFile(FileInputStream(it), saveFileName ?: it.name).let { uri ->
                importFile(uri).apply {
                    //附加压缩包名称 以便解压文件被删后再解压
                    origin = "${BookType.localTag}::${archiveFileDoc.name}"
                    addType(BookType.archive)
                    save()
                }
            }
        }
    }

    /* 批量导入 支持自动导入压缩包的支持书籍 */
    fun importFiles(uri: Uri): List<Book> {
        val books = mutableListOf<Book>()
        val fileDoc = FileDoc.fromUri(uri, false)
        if (ArchiveUtils.isArchive(fileDoc.name)) {
            books.addAll(
                importArchiveFile(uri) {
                    it.matches(AppPattern.bookFileRegex)
                }
            )
        } else {
            books.add(importFile(uri))
        }
        return books
    }

    fun importFiles(uris: List<Uri>) {
        var errorCount = 0
        uris.forEach { uri ->
            val fileDoc = FileDoc.fromUri(uri, false)
            kotlin.runCatching {
                if (ArchiveUtils.isArchive(fileDoc.name)) {
                    importArchiveFile(uri) {
                        it.matches(AppPattern.bookFileRegex)
                    }
                } else {
                    importFile(uri)
                }
            }.onFailure {
                AppLog.put("ImportFile Error:\nFile $fileDoc\n${it.localizedMessage}", it)
                errorCount += 1
            }
        }
        if (errorCount == uris.size) {
            throw NoStackTraceException("ImportFiles Error:\nAll input files occur error")
        }
    }

    /**
     * 从文件分析书籍必要信息（书名 作者等）
     */
    private fun analyzeNameAuthor(fileName: String): Pair<String, String> {
        val tempFileName = fileName.substringBeforeLast(".")
        var name = ""
        var author = ""
        if (!AppConfig.bookImportFileName.isNullOrBlank()) {
            try {
                //在用户脚本后添加捕获author、name的代码，只要脚本中author、name有值就会被捕获
                val js =
                    AppConfig.bookImportFileName + "\nJSON.stringify({author:author,name:name})"
                //在脚本中定义如何分解文件名成书名、作者名
                val jsonStr = RhinoScriptEngine.run {
                    val bindings = ScriptBindings()
                    bindings["src"] = tempFileName
                    eval(js, bindings)
                }.toString()
                val bookMess = GSON.fromJsonObject<HashMap<String, String>>(jsonStr)
                    .getOrThrow()
                name = bookMess["name"] ?: ""
                author = bookMess["author"]?.takeIf { it.length != tempFileName.length } ?: ""
            } catch (e: Exception) {
                AppLog.put("执行导入文件名规则出错\n${e.localizedMessage}", e)
            }
        }
        if (name.isBlank()) {
            for (pattern in nameAuthorPatterns) {
                pattern.matcher(tempFileName).takeIf { it.find() }?.run {
                    name = group(2)!!
                    val group1 = group(1) ?: ""
                    val group3 = group(3) ?: ""
                    author = BookHelp.formatBookAuthor(group1 + group3)
                    return Pair(name, author)
                }
            }
            name = BookHelp.formatBookName(tempFileName)
            author = BookHelp.formatBookAuthor(tempFileName.replace(name, ""))
                .takeIf { it.length != tempFileName.length } ?: ""
        }
        return Pair(name, author)
    }

    fun deleteBook(book: Book, deleteOriginal: Boolean) {
        kotlin.runCatching {
            BookHelp.clearCache(book)
            if (!book.coverUrl.isNullOrEmpty()) {
                FileUtils.delete(book.coverUrl!!)
            }
            if (deleteOriginal) {
                if (book.bookUrl.isContentScheme()) {
                    val uri = Uri.parse(book.bookUrl)
                    DocumentFile.fromSingleUri(appCtx, uri)?.delete()
                } else {
                    FileUtils.delete(book.bookUrl)
                }
            }
        }
    }

    /**
     * 下载在线的文件
     */
    suspend fun saveBookFile(
        str: String,
        fileName: String,
        source: BaseSource? = null,
    ): Uri {
        AppConfig.defaultBookTreeUri
            ?: throw NoBooksDirException()
        val inputStream = when {
            str.isAbsUrl() -> AnalyzeUrl(
                str, source = source, callTimeout = 0,
                coroutineContext = coroutineContext
            ).getInputStreamAwait()

            str.isDataUrl() -> ByteArrayInputStream(
                Base64.decode(
                    str.substringAfter("base64,"),
                    Base64.DEFAULT
                )
            )

            else -> throw NoStackTraceException("在线导入书籍支持http/https/DataURL")
        }
        return saveBookFile(inputStream, fileName)
    }

    @Throws(SecurityException::class)
    fun saveBookFile(
        inputStream: InputStream,
        fileName: String
    ): Uri {
        inputStream.use {
            val defaultBookTreeUri = AppConfig.defaultBookTreeUri
            if (defaultBookTreeUri.isNullOrBlank()) throw NoBooksDirException()
            val treeUri = Uri.parse(defaultBookTreeUri)
            return if (treeUri.isContentScheme()) {
                val treeDoc = DocumentFile.fromTreeUri(appCtx, treeUri)
                var doc = treeDoc!!.findFile(fileName)
                if (doc == null) {
                    doc = treeDoc.createFile(FileUtils.getMimeType(fileName), fileName)
                        ?: throw SecurityException("请重新设置书籍保存位置\nPermission Denial")
                }
                appCtx.contentResolver.openOutputStream(doc.uri)!!.use { oStream ->
                    it.copyTo(oStream)
                }
                doc.uri
            } else {
                try {
                    val treeFile = File(treeUri.path!!)
                    val file = treeFile.getFile(fileName)
                    FileOutputStream(file).use { oStream ->
                        it.copyTo(oStream)
                    }
                    Uri.fromFile(file)
                } catch (e: FileNotFoundException) {
                    throw SecurityException("请重新设置书籍保存位置\nPermission Denial\n$e").apply {
                        addSuppressed(e)
                    }
                }
            }
        }
    }

    fun isOnBookShelf(
        fileName: String
    ): Boolean {
        return appDb.bookDao.hasFile(fileName) == true
    }

    //文件类书源 合并在线书籍信息 在线 > 本地
    fun mergeBook(localBook: Book, onLineBook: Book?): Book {
        onLineBook ?: return localBook
        localBook.name = onLineBook.name.ifBlank { localBook.name }
        localBook.author = onLineBook.author.ifBlank { localBook.author }
        localBook.coverUrl = onLineBook.coverUrl
        localBook.intro =
            if (onLineBook.intro.isNullOrBlank()) localBook.intro else onLineBook.intro
        localBook.save()
        return localBook
    }

    //下载book对应的远程文件 并更新Book
    private fun downloadRemoteBook(localBook: Book): Boolean {
        val webDavUrl = localBook.getRemoteUrl()
        if (webDavUrl.isNullOrBlank()) throw NoStackTraceException("Book file is not webDav File")
        try {
            AppConfig.defaultBookTreeUri
                ?: throw NoBooksDirException()
            // 兼容旧版链接
            val webdav: WebDav = kotlin.runCatching {
                WebDav.fromPath(webDavUrl)
            }.getOrElse {
                AppWebDav.authorization?.let { WebDav(webDavUrl, it) }
                    ?: throw WebDavException("Unexpected defaultBookWebDav")
            }
            val inputStream = runBlocking {
                webdav.downloadInputStream()
            }
            inputStream.use {
                if (localBook.isArchive) {
                    // 压缩包
                    val archiveUri = saveBookFile(it, localBook.archiveName)
                    val newBook = importArchiveFile(archiveUri, localBook.originName) { name ->
                        name.contains(localBook.originName)
                    }.first()
                    localBook.origin = newBook.origin
                    localBook.bookUrl = newBook.bookUrl
                } else {
                    // txt epub pdf umd
                    val fileUri = saveBookFile(it, localBook.originName)
                    localBook.bookUrl = FileDoc.fromUri(fileUri, false).toString()
                    localBook.save()
                }
            }
            return true
        } catch (e: Exception) {
            e.printOnDebug()
            AppLog.put("自动下载webDav书籍失败", e)
            return false
        }
    }

}
