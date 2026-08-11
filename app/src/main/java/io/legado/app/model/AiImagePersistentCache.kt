package io.legado.app.model

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.legado.app.constant.AppLog
import java.io.File
import java.security.MessageDigest

/**
 * manifest 单条记录（顶层类，确保 Gson 能正确反序列化泛型列表元素）
 */
data class AiImageCacheEntry(
    var seq: Int = 0,
    var startPara: Int = 0,
    var endPara: Int = 0,
    var charCount: Int = 0,
    var textHash: String = "",
    var file: String = ""
)

/** 章节清单 */
data class AiImageCacheManifest(
    var bookName: String = "",
    var chapterIndex: Int = 0,
    var chapterName: String = "",
    var images: MutableList<AiImageCacheEntry> = mutableListOf()
)

/**
 * AI 生图持久化缓存
 *
 * 将生图结果持久保存到外部存储，按 书名/章节 组织，
 * 支持二次朗读时按段落索引模糊命中（70% 容差）复用，避免重复生成。
 *
 * 目录结构:
 * {ROOT}/AI生图/{bookName}/{chapterIndex}_{chapterName}/
 *   ├─ 001_p012_p018_c200.png   (序号_起始段_结束段_当时charCount)
 *   └─ manifest.json
 *
 * 命中策略（段落区间重叠 >= 70% min(旧,新)）:
 * - 旧图细(旧字数小): 多张细旧图按序轮换，每张都能用上
 * - 旧图粗(旧字数大): 一张大旧图被相邻新场景复用
 */
object AiImagePersistentCache {

    private const val ROOT_DIR = "/storage/emulated/0/Download/AI生图"
    private const val MANIFEST_NAME = "manifest.json"
    private const val HIT_RATIO = 0.5
    /** 文件名格式: 001_p041_p043_c150.png */
    private val FILE_NAME_RE =
        Regex("(\\d+)_p(\\d+)_p(\\d+)_c(\\d+)\\.png", RegexOption.IGNORE_CASE)

    /** 使用独立 Gson 实例，避免项目 GSON 的自定义 TypeAdapter 干扰简单数据类序列化 */
    private val gson: Gson by lazy { Gson() }

    /**
     * 每个章节已用过的 seq 集合。
     * key = bookUrl_chapterIndex，保证按序轮换不跳过细旧图。
     * 切章节/重新朗读时清理。
     */
    private val usedSeqsByKey = HashMap<String, MutableSet<Int>>()

    /**
     * 从文件名解析出的图片信息（以目录内实际 PNG 文件为准）。
     */
    private data class ParsedImage(
        val seq: Int,
        val startPara: Int,
        val endPara: Int,
        val charCount: Int,
        val file: File
    )

    /**
     * 每个章节已扫描解析的图片列表（内存缓存，切章节/重新朗读时清理）。
     * 以目录内实际 PNG 文件为准，不再依赖 manifest.json。
     * 首次访问某章节时扫描目录一次；saveImage 写入新图时同步追加，
     * 因此无需每次生图前都重新扫描目录。
     * key = 章节目录的绝对路径。
     */
    private val chapterImagesCache = HashMap<String, MutableList<ParsedImage>>()

    /** 计算正文指纹（取前 200 字 md5），用于记录内容快照（命中不强制校验） */
    private fun textHash(text: String): String {
        val sample = if (text.length > 200) text.take(200) else text
        val bytes = MessageDigest.getInstance("MD5").digest(sample.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** 文件名/目录名非法字符清理 */
    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "unknown" }

    /** 章节目录: {ROOT}/{bookName}/{chapterIndex}_{chapterName}/ */
    private fun chapterDir(bookName: String, chapterIndex: Int, chapterName: String): File {
        val bookDir = File(ROOT_DIR, sanitize(bookName))
        val chDir = File(bookDir, "${chapterIndex}_${sanitize(chapterName)}")
        if (!chDir.exists()) chDir.mkdirs()
        return chDir
    }

    private fun manifestFile(dir: File): File = File(dir, MANIFEST_NAME)

    /** 解析文件名 001_p041_p043_c150.png -> ParsedImage，格式不符返回 null */
    private fun parseFileName(file: File): ParsedImage? {
        val m = FILE_NAME_RE.find(file.name) ?: return null
        return try {
            ParsedImage(
                seq = m.groupValues[1].toInt(),
                startPara = m.groupValues[2].toInt(),
                endPara = m.groupValues[3].toInt(),
                charCount = m.groupValues[4].toInt(),
                file = file
            )
        } catch (e: Exception) {
            null
        }
    }

    /** 扫描章节目录，解析所有 PNG 文件名（以目录为准，不依赖 manifest） */
    private fun scanChapterImages(dir: File): List<ParsedImage> {
        val files = dir.listFiles { f -> f.isFile && f.extension.equals("png", true) } ?: return emptyList()
        return files.mapNotNull { parseFileName(it) }
    }

    /** 获取某章节的图片列表（内存缓存，首次访问时扫描目录） */
    private fun getChapterImages(key: String, dir: File): MutableList<ParsedImage> {
        return chapterImagesCache.getOrPut(key) { scanChapterImages(dir).toMutableList() }
    }

    private fun loadManifest(dir: File): AiImageCacheManifest? {
        val f = manifestFile(dir)
        if (!f.exists()) return null
        return try {
            parseManifestJson(f.readText())
        } catch (e: Exception) {
            AppLog.put("AI生图持久缓存: manifest解析失败 ${e.message}")
            null
        }
    }

    /**
     * 手动解析 manifest JSON，避免 Gson 反序列化泛型列表时退回 LinkedTreeMap 导致 ClassCastException。
     * 兼容旧版本（嵌套类）写出的 manifest 文件。
     */
    private fun parseManifestJson(json: String): AiImageCacheManifest? {
        val root = JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val manifest = AiImageCacheManifest()
        manifest.bookName = root.get("bookName")?.asString ?: ""
        manifest.chapterIndex = root.get("chapterIndex")?.asInt ?: 0
        manifest.chapterName = root.get("chapterName")?.asString ?: ""
        val imagesArr = root.getAsJsonArray("images") ?: return manifest
        for (elem in imagesArr) {
            if (!elem.isJsonObject) continue
            val obj = elem.asJsonObject
            manifest.images.add(
                AiImageCacheEntry(
                    seq = obj.get("seq")?.asInt ?: 0,
                    startPara = obj.get("startPara")?.asInt ?: 0,
                    endPara = obj.get("endPara")?.asInt ?: 0,
                    charCount = obj.get("charCount")?.asInt ?: 0,
                    textHash = obj.get("textHash")?.asString ?: "",
                    file = obj.get("file")?.asString ?: ""
                )
            )
        }
        return manifest
    }

    private fun saveManifest(dir: File, m: AiImageCacheManifest) {
        try {
            manifestFile(dir).writeText(gson.toJson(m))
        } catch (e: Exception) {
            AppLog.put("AI生图持久缓存: manifest写入失败 ${e.message}")
        }
    }

    private fun cursorKey(bookUrl: String, chapterIndex: Int) = "${bookUrl}_$chapterIndex"

    /**
     * 查询持久缓存命中
     * @param startPara 当前场景起始段落
     * @param endPara 当前场景结束段落
     * @param charCount 当前设定的字数阈值
     * @return 命中的图片文件，未命中返回 null
     */
    @Synchronized
    fun findHit(
        bookUrl: String,
        bookName: String,
        chapterIndex: Int,
        chapterName: String,
        startPara: Int,
        endPara: Int,
        charCount: Int,
    ): File? {
        return try {
            findHitInternal(bookUrl, bookName, chapterIndex, chapterName, startPara, endPara, charCount)
        } catch (e: Exception) {
            AppLog.put("AI生图持久缓存: findHit异常 ${e.message}")
            null
        }
    }

    private fun findHitInternal(
        bookUrl: String,
        bookName: String,
        chapterIndex: Int,
        chapterName: String,
        startPara: Int,
        endPara: Int,
        @Suppress("UNUSED_PARAMETER") charCount: Int,
    ): File? {
        val dir = chapterDir(bookName, chapterIndex, chapterName)
        if (!dir.exists()) return null
        // 直接读取目录内所有 PNG 文件名，解析出段落区间进行匹配
        // （不再依赖 manifest.json，避免 manifest 缺失/损坏导致文件夹里的图全被忽略）
        val images = getChapterImages(dir.absolutePath, dir)
        if (images.isEmpty()) return null

        val sorted = images.sortedBy { it.startPara }
        val used = usedSeqsByKey.getOrPut(cursorKey(bookUrl, chapterIndex)) { mutableSetOf() }

        // 第1步: 找"未用过 且 命中"的，按序轮换（旧图细时逐张用上）
        for (e in sorted) {
            if (e.seq in used) continue
            if (!rangeOverlapHit(e.startPara, e.endPara, startPara, endPara)) continue
            if (e.file.exists()) {
                used.add(e.seq)
                AppLog.put("AI生图持久缓存命中(轮换): seq=${e.seq} [${e.startPara},${e.endPara}] -> [${startPara},${endPara}]")
                return e.file
            }
        }

        // 第2步: 粗图复用 - 找"完全包含当前区间"的旧图（旧图粗时被相邻新场景复用）
        for (e in sorted) {
            if (e.startPara <= startPara && e.endPara >= endPara) {
            if (e.file.exists()) {
                AppLog.put("AI生图持久缓存命中(复用粗图): seq=${e.seq} [${e.startPara},${e.endPara}] 包含 [${startPara},${endPara}]")
                return e.file
            }
            }
        }
        return null
    }

    /** 段落区间重叠比例判定（详见 rangeOverlapHit） */
    private fun rangeOverlapHit(s1: Int, e1: Int, s2: Int, e2: Int): Boolean {
        val overlapStart = maxOf(s1, s2)
        val overlapEnd = minOf(e1, e2)
        if (overlapEnd < overlapStart) return false
        val overlap = overlapEnd - overlapStart + 1
        val oldCnt = e1 - s1 + 1
        val newCnt = e2 - s2 + 1
        val minCnt = minOf(oldCnt, newCnt)
        if (minCnt <= 0) return false
        return overlap.toDouble() / minCnt >= HIT_RATIO
    }

    /**
     * 保存图片到持久缓存并更新 manifest
     * @return 保存后的目标文件，失败返回 null
     */
    @Synchronized
    fun saveImage(
        bookName: String,
        chapterIndex: Int,
        chapterName: String,
        startPara: Int,
        endPara: Int,
        charCount: Int,
        sourceText: String,
        imageFile: File,
    ): File? {
        return try {
            val dir = chapterDir(bookName, chapterIndex, chapterName)
            val key = dir.absolutePath
            // seq 基于目录内已有图片的最大序号，避免依赖 manifest 导致序号重复/覆盖
            val images = getChapterImages(key, dir)
            val seq = (images.maxOfOrNull { it.seq } ?: 0) + 1
            val fileName = "%03d_p%03d_p%03d_c%d.png".format(seq, startPara, endPara, charCount)
            val target = File(dir, fileName)
            imageFile.copyTo(target, overwrite = true)

            // 同步更新内存缓存，保证后续 findHit 立即可见刚写入的图
            images.add(ParsedImage(seq, startPara, endPara, charCount, target))

            // 仍写一份 manifest 作为可读备份（命中不再依赖它）
            val manifest = loadManifest(dir) ?: AiImageCacheManifest(
                bookName = bookName,
                chapterIndex = chapterIndex,
                chapterName = chapterName
            )
            manifest.images.add(
                AiImageCacheEntry(
                    seq = seq,
                    startPara = startPara,
                    endPara = endPara,
                    charCount = charCount,
                    textHash = textHash(sourceText),
                    file = fileName
                )
            )
            saveManifest(dir, manifest)
            AppLog.put("AI生图持久缓存保存: seq=$seq [${startPara},${endPara}] c=$charCount -> ${target.absolutePath}")
            target
        } catch (e: Exception) {
            AppLog.put("AI生图持久缓存保存失败: ${e.message}")
            null
        }
    }

    /**
     * 本章已保存图片的只读查询（供生成视频复用，不触发任何生图/落盘）。
     * 以目录内实际 PNG 文件为准解析段落区间，按起始段落排序返回。
     * @return 图片轨道列表（含段落区间与文件），无图返回空。
     */
    fun getChapterImageTracks(bookName: String, chapterIndex: Int, chapterName: String): List<SavedImageTrack> {
        val dir = File(File(ROOT_DIR, sanitize(bookName)), "${chapterIndex}_${sanitize(chapterName)}")
        if (!dir.exists()) return emptyList()
        return getChapterImages(dir.absolutePath, dir)
            .map { SavedImageTrack(it.startPara, it.endPara, it.file) }
            .sortedBy { it.startPara }
    }

    /** 已保存图片轨道（只读查询用） */
    data class SavedImageTrack(
        val startPara: Int,
        val endPara: Int,
        val file: File
    )

    /** 切换章节时清理对应章节游标与图片缓存 */
    fun resetChapterCursor(bookUrl: String, chapterIndex: Int) {
        usedSeqsByKey.remove(cursorKey(bookUrl, chapterIndex))
        chapterImagesCache.clear()
    }

    /** 清理所有游标与图片缓存（重新朗读时） */
    fun clearAllCursors() {
        usedSeqsByKey.clear()
        chapterImagesCache.clear()
    }
}
