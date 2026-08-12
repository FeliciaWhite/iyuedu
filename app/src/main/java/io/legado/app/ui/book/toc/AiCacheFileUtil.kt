package io.legado.app.ui.book.toc

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import org.json.JSONObject
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

    /**
     * 为文本添加角色标注：在每个左双引号 “ 后面插入 <<姓名（性别/年龄）>>。
     * 角色信息来自 AI 章节缓存文件（results 字段，键为双引号序号，值为 name/gender/age）。
     * 无缓存文件或解析失败时返回原文本。
     * 导出小说与 AI 生图共用此逻辑。
     */
    fun annotateTextWithRoles(text: String, book: Book, chapter: BookChapter): String {
        val cacheFile = getChapterCacheFile(book, chapter)
        if (!cacheFile.exists()) return text

        return try {
            val json = JSONObject(cacheFile.readText())
            val results = json.optJSONObject("results") ?: return text
            val roleMap = mutableMapOf<Int, Triple<String, String, String>>()
            results.keys().forEach { key ->
                val seq = key.toIntOrNull() ?: return@forEach
                val obj = results.getJSONObject(key)
                val name = obj.optString("name", "")
                if (name.isNotBlank()) {
                    roleMap[seq] = Triple(
                        name,
                        obj.optString("gender", ""),
                        obj.optString("age", "")
                    )
                }
            }
            if (roleMap.isEmpty()) return text

            val sb = StringBuilder()
            var seq = 0
            var i = 0
            while (i < text.length) {
                val ch = text[i]
                if (ch == '“') {
                    seq++
                    sb.append(ch)
                    roleMap[seq]?.let { (name, gender, age) ->
                        val genderAge = buildString {
                            if (gender.isNotBlank()) append(gender)
                            if (gender.isNotBlank() && age.isNotBlank()) append("/")
                            if (age.isNotBlank()) append(age)
                        }
                        if (genderAge.isNotBlank()) {
                            sb.append("<<").append(name).append("（").append(genderAge).append("）>>")
                        } else {
                            sb.append("<<").append(name).append(">>")
                        }
                    }
                } else {
                    sb.append(ch)
                }
                i++
            }
            sb.toString()
        } catch (e: Exception) {
            text
        }
    }
}

