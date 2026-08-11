package io.legado.app.service

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.legado.app.constant.AppLog
import org.json.JSONObject
import splitties.init.appCtx
import java.io.File

/**
 * 背景音乐关键词匹配器
 * 根据小说文本内容，通过关键词匹配算法选择最合适的背景音乐
 *
 * 匹配逻辑：
 * 1. 从BGM文件夹读取"背景音乐的关键词.json"（分类词 -> 关键词列表）
 * 2. 从BGM文件夹读取"背景音乐的名字.txt"（MP3文件名列表），若不存在则使用BgmManager扫描结果
 * 3. 对小说片段进行关键词匹配，统计每个分类词命中次数（得分）
 * 4. 用分类词去各MP3文件名标签中匹配，累加得分，取总分最高的音乐
 * 5. 同分则随机选择
 */
object BgmKeywordMatcher {

    private var categoryKeywords: Map<String, List<String>> = emptyMap()
    private var fileNames: List<String> = emptyList()
    private var configValid = false
    private var lastLoadedDir: String? = null

    private const val JSON_FILE_NAME = "背景音乐的关键词.json"
    private const val TXT_FILE_NAME = "背景音乐的名字.txt"
    private const val MAX_RECENT_BGMS = 3
    private val recentBgms = ArrayDeque<String>(MAX_RECENT_BGMS)

    // 保存上次多候选时未被选中的其他候选（无扩展名），用于匹配失败时兜底
    private val savedCandidates = mutableListOf<String>()

    /**
     * 配置是否有效
     */
    val isConfigValid: Boolean
        get() = configValid && categoryKeywords.isNotEmpty()

    /**
     * 文件名列表是否非空
     */
    fun hasFileNames(): Boolean = fileNames.isNotEmpty()

    /**
     * 从BGM文件夹加载配置文件
     * @param bgmDir BGM文件夹路径（普通路径或content:// URI）
     */
    fun loadConfig(bgmDir: String?) {
        configValid = false
        // 切换BGM目录时清空最近播放记录和保存的候选
        if (lastLoadedDir != bgmDir) {
            clearRecentBgms()
            savedCandidates.clear()
            lastLoadedDir = bgmDir
        }
        if (bgmDir.isNullOrBlank()) {
            AppLog.putDebug("关键词BGM: bgmDir为空，跳过加载")
            return
        }

        val jsonMap = mutableMapOf<String, List<String>>()
        var nameList = listOf<String>()

        try {
            if (bgmDir.startsWith("content://")) {
                val treeUri = Uri.parse(bgmDir)
                val treeDoc = DocumentFile.fromTreeUri(appCtx, treeUri) ?: return
                // 读取JSON
                val jsonDoc = treeDoc.findFile(JSON_FILE_NAME)
                if (jsonDoc != null && jsonDoc.isFile) {
                    appCtx.contentResolver.openInputStream(jsonDoc.uri)?.use { stream ->
                        val text = stream.bufferedReader().readText()
                        parseJson(text, jsonMap)
                    }
                } else {
                    AppLog.put("关键词BGM: 未找到配置文件 $JSON_FILE_NAME")
                    return
                }
                // 读取TXT
                val txtDoc = treeDoc.findFile(TXT_FILE_NAME)
                if (txtDoc != null && txtDoc.isFile) {
                    appCtx.contentResolver.openInputStream(txtDoc.uri)?.use { stream ->
                        nameList = stream.bufferedReader().readLines()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                    }
                }
            } else {
                val dir = File(bgmDir)
                if (!dir.exists() || !dir.isDirectory) {
                    AppLog.put("关键词BGM: 目录不存在或不是文件夹: $bgmDir")
                    return
                }
                val jsonFile = File(dir, JSON_FILE_NAME)
                if (jsonFile.exists()) {
                    parseJson(jsonFile.readText(), jsonMap)
                } else {
                    AppLog.put("关键词BGM: 未找到配置文件 $JSON_FILE_NAME")
                    return
                }
                val txtFile = File(dir, TXT_FILE_NAME)
                if (txtFile.exists()) {
                    nameList = txtFile.readLines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                }
            }
        } catch (e: Exception) {
            AppLog.put("关键词BGM: 加载配置文件失败: ${e.localizedMessage}")
            return
        }

        if (jsonMap.isEmpty()) {
            AppLog.put("关键词BGM: 关键词配置为空")
            return
        }

        categoryKeywords = jsonMap
        fileNames = nameList
        configValid = true
        AppLog.put("关键词BGM: 配置加载成功，共 ${jsonMap.size} 个分类词，${nameList.size} 个音乐文件")
    }

    private fun parseJson(text: String, map: MutableMap<String, List<String>>) {
        val json = JSONObject(text)
        val keys = json.keys()
        while (keys.hasNext()) {
            val category = keys.next()
            val obj = json.getJSONObject(category)
            val subKeys = obj.keys()
            while (subKeys.hasNext()) {
                val subKey = subKeys.next()
                val arr = obj.getJSONArray(subKey)
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    list.add(arr.getString(i))
                }
                // 去重
                map[subKey] = list.distinct()
            }
        }
    }

    /**
     * 设置文件名列表（当txt文件不存在时，由BgmManager同步扫描结果）
     */
    fun setFileNames(names: List<String>) {
        if (configValid) {
            fileNames = names
        }
    }

    /**
     * 记录已播放的BGM
     * 只要一首BGM开始播放，就将其加入最近播放列表，最多保留3首
     */
    fun recordPlayedBgm(name: String?) {
        if (name.isNullOrBlank()) return
        val normalized = name.substringBeforeLast(".").trim()
        if (normalized.isBlank()) return
        // 如果已在列表中，先移除再移到最前面，保持最新顺序
        recentBgms.remove(normalized)
        recentBgms.addFirst(normalized)
        while (recentBgms.size > MAX_RECENT_BGMS) {
            recentBgms.removeLast()
        }
        AppLog.putDebug("关键词BGM: 记录已播放 $normalized, 最近列表=$recentBgms")
    }

    /**
     * 清空最近播放记录
     */
    fun clearRecentBgms() {
        recentBgms.clear()
    }

    /**
     * 分析文本内容，返回推荐的音乐文件名（不含扩展名）
     * @param content 小说文本内容
     * @param currentBgm 当前正在播放的BGM文件名（用于排除当前音乐）
     * @return 推荐文件名，无匹配或配置无效时返回null
     */
    fun analyze(content: String, currentBgm: String? = null): String? {
        if (!configValid || fileNames.isEmpty()) return null
        if (content.isBlank()) return null

        val cleanContent = content

        // 1. 统计分类词得分，并记录匹配到的关键词
        val scores = mutableMapOf<String, Int>()
        val matchedKeywords = mutableMapOf<String, MutableList<String>>()
        categoryKeywords.forEach { (category, keywords) ->
            var count = 0
            val matched = mutableListOf<String>()
            keywords.forEach { keyword ->
                if (keyword.isEmpty()) return@forEach
                // 统计出现次数，允许重叠匹配
                var start = 0
                while (true) {
                    val idx = cleanContent.indexOf(keyword, start)
                    if (idx < 0) break
                    count++
                    matched.add(keyword)
                    start = idx + keyword.length
                }
            }
            if (count > 0) {
                scores[category] = count
                matchedKeywords[category] = matched
                AppLog.putDebug("关键词BGM: 分类词 [$category] 匹配到 ${matched.take(20)}${if (matched.size > 20) "..." else ""} (共${count}次)")
            }
        }

        if (scores.isEmpty()) {
            AppLog.put("关键词BGM: 未匹配到任何分类词")
            return null
        }

        AppLog.putDebug(
            "关键词BGM: 分类词得分=" +
                    scores.toList().sortedByDescending { it.second }.take(10)
        )

        // 2. 匹配文件名：将每个文件名按 _ 分割为标签，检查标签是否包含分类词（子串匹配）
        // 同时排除当前正在播放的BGM，避免连续重复播放同一首
        var maxScore = -1
        val bestFiles = mutableListOf<String>()

        fileNames.forEach { fileName ->
            // 移除 .mp3 等扩展名
            val nameWithoutExt = if (fileName.contains(".")) {
                fileName.substringBeforeLast(".")
            } else fileName
            // 跳过当前正在播放的BGM
            if (currentBgm != null) {
                val currentBase = if (currentBgm.contains(".")) currentBgm.substringBeforeLast(".") else currentBgm
                if (nameWithoutExt.equals(currentBase, ignoreCase = true)) return@forEach
            }
            // 跳过最近播放过的BGM（最多3首），避免短时间内重复
            if (recentBgms.any { nameWithoutExt.equals(it, ignoreCase = true) }) return@forEach
            // 移除末尾序号 _2 _3 _4 等，用于标签匹配
            val cleanName = nameWithoutExt.replace(Regex("_\\d+$"), "")
            // 按下划线分割标签
            val tags = cleanName.split("_")

            var score = 0
            val hitTags = mutableListOf<String>()
            scores.forEach { (category, categoryScore) ->
                tags.forEach { tag ->
                    if (tag.contains(category)) {
                        score += categoryScore
                        hitTags.add("$tag→$category($categoryScore)")
                    }
                }
            }

            if (score > 0) {
                AppLog.putDebug("关键词BGM: 文件 [$fileName] 得分=$score, 匹配标签=${hitTags.take(10)}${if (hitTags.size > 10) "..." else ""}")
            }

            if (score > maxScore) {
                maxScore = score
                bestFiles.clear()
                bestFiles.add(fileName)
            } else if (score == maxScore && score > 0) {
                bestFiles.add(fileName)
            }
        }

        if (bestFiles.isEmpty() || maxScore <= 0) {
            AppLog.put("关键词BGM: 未匹配到任何音乐文件（当前BGM已排除）")

            // 所有匹配失败时，从历史保存的候选中随机选取
            if (savedCandidates.isNotEmpty()) {
                val chosen = savedCandidates.random()
                savedCandidates.remove(chosen)
                AppLog.put("关键词BGM: 所有匹配失败，从历史候选中选取=$chosen (剩余候选=${savedCandidates.size}个)")
                return chosen
            }

            return null
        }

        // 3. 同分随机选择，并保存其他候选
        val chosen = bestFiles.random()
        val result = chosen.substringBeforeLast(".")

        // 保存其他候选（无扩展名），用于下次全部匹配失败时兜底
        val otherCandidates = bestFiles.filter { it != chosen }.map { it.substringBeforeLast(".") }
        if (otherCandidates.isNotEmpty()) {
            savedCandidates.clear()
            savedCandidates.addAll(otherCandidates)
            AppLog.put("关键词BGM: 保存其他候选=${otherCandidates} (共${otherCandidates.size}个)")
        }

        AppLog.put("关键词BGM: 最佳匹配=$result (score=$maxScore, 候选=${bestFiles.size}个, 已排除当前+最近${recentBgms.size}首)")
        return result
    }
}