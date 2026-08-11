package io.legado.app.ui.book.toc

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import java.io.File

/**
 * AI 章节缓存文件工具类
 * 与 朗读脚本缓存版.js 中的文件命名规则保持一致
 */
object AiCacheFileUtil {

    private const val CACHE_ROOT = "/storage/emulated/0/Download/chajian/xiaoshuo/"

    /**
     * 处理文件名中的非法字符（章节名规则）
     * JS: name.replace(/\?/g, "？").replace(/[\\/:*?"<>|]/g, "＿")
     */
    fun sanitizeChapterFileName(name: String): String {
        return name.replace("?", "？")
            .replace(Regex("[\\\\/:*?\"<>|]"), "＿")
    }

    /**
     * 处理书名中的非法字符（书名规则）
     * JS: bookName.replace(/[\\/:*?"<>|]/g, "_").substring(0, 50)
     */
    fun sanitizeBookName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .take(50)
    }

    /**
     * 获取书籍缓存目录
     */
    fun getBookDir(book: Book): File {
        return File(CACHE_ROOT + sanitizeBookName(book.name) + "/")
    }

    /**
     * 获取章节的 AI 缓存文件
     */
    fun getChapterCacheFile(book: Book, chapter: BookChapter): File {
        val bookDir = getBookDir(book)
        return File(bookDir, sanitizeChapterFileName(chapter.title) + ".json")
    }

    /**
     * 检查章节是否有 AI 缓存文件
     */
    fun hasChapterCache(book: Book, chapter: BookChapter): Boolean {
        return getChapterCacheFile(book, chapter).exists()
    }

    /**
     * 删除章节的 AI 缓存文件
     */
    fun deleteChapterCache(book: Book, chapter: BookChapter): Boolean {
        val file = getChapterCacheFile(book, chapter)
        return if (file.exists()) {
            file.delete()
        } else {
            false
        }
    }

    /**
     * 获取书籍下所有 AI 缓存文件
     */
    fun getAllChapterCacheFiles(book: Book): List<File> {
        val bookDir = getBookDir(book)
        if (!bookDir.exists() || !bookDir.isDirectory) return emptyList()
        return bookDir.listFiles { _, name -> name.endsWith(".json") }?.toList() ?: emptyList()
    }

    /**
     * 删除整本书的所有 AI 缓存文件
     * 如果目录为空，也删除目录本身
     */
    fun deleteAllChapterCaches(book: Book): Int {
        val bookDir = getBookDir(book)
        if (!bookDir.exists() || !bookDir.isDirectory) return 0
        val files = bookDir.listFiles { _, name -> name.endsWith(".json") } ?: return 0
        var deletedCount = 0
        files.forEach {
            if (it.delete()) deletedCount++
        }
        // 如果目录为空，删除目录本身
        if (bookDir.listFiles().isNullOrEmpty()) {
            bookDir.delete()
        }
        return deletedCount
    }
}
