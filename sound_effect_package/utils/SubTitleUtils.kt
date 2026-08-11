package io.legado.app.utils

import java.util.regex.Pattern

/**
 * 字幕分割工具
 * 按标点分割文本，相邻片段拼接（不超过15字）
 * 根据总时长和字数分配时间
 */
object SubTitleUtils {

    // 标点符号正则
    private val punctRegex = Pattern.compile("[，。！？；：、]")

    // 所有标点符号（用于清理）
    private val allPunctRegex = Pattern.compile("[，。！？；：、\"\"''「」（）【】《》【】…—·\\u2018\\u2019\\u201c\\u201d\\u0022\\u0027\\s+]")

    /**
     * 判断是否是中文字符
     */
    private fun isChinese(c: Char): Boolean {
        return c.code in 0x4E00..0x9FFF
    }

    /**
     * 统计中文字符数
     */
    fun countChinese(text: String): Int {
        return text.count { isChinese(it) }
    }

    /**
     * 清理文本中的所有标点符号
     */
    fun cleanPunct(text: String): String {
        return allPunctRegex.matcher(text).replaceAll("")
    }

    /**
     * 分割文本为字幕片段
     * 按标点分割，相邻片段拼接，不超过15字
     * @param text 原始文本
     * @param maxChars 每行最大中文字符数，默认15
     * @return 字幕片段列表，每个元素是字幕文本
     */
    fun splitToSubtitles(text: String, maxChars: Int = 15): List<String> {
        if (text.isBlank()) return emptyList()

        // 按标点分割
        val parts = punctRegex.split(text)

        val result = mutableListOf<String>()
        val current = StringBuilder()
        var currentCount = 0

        for (part in parts) {
            // 清理标点
            val seg = cleanPunct(part)
            if (seg.isEmpty()) continue

            val segCount = countChinese(seg)

            if (current.isEmpty()) {
                // 当前为空，直接添加
                current.append(seg)
                currentCount = segCount
            } else {
                // 检查拼接后是否超过maxChars
                val newCount = currentCount + 1 + segCount // +1 是空格
                if (newCount <= maxChars) {
                    current.append(" ")
                    current.append(seg)
                    currentCount = newCount
                } else {
                    // 超过15字，保存当前并开始新片段
                    result.add(current.toString())
                    current.clear()
                    current.append(seg)
                    currentCount = segCount
                }
            }
        }

        // 保存最后一段
        if (current.isNotEmpty()) {
            result.add(current.toString())
        }

        return result
    }

    /**
     * 分割多段落文本
     * @param text 多段落文本，用换行分隔
     * @param maxChars 每行最大中文字符数
     * @return 所有字幕片段列表
     */
    fun splitParagraphs(text: String, maxChars: Int = 15): List<String> {
        if (text.isBlank()) return emptyList()

        val result = mutableListOf<String>()
        val lines = text.trim().split("\n")

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            val parts = splitToSubtitles(trimmed, maxChars)
            result.addAll(parts)
        }

        return result
    }

    /**
     * 生成SRT字幕内容
     * 根据总时长和总字数平均分配每个字的时间
     *
     * @param subtitles 字幕片段列表
     * @param totalDurationMs 总音频时长（毫秒）
     * @param charPerSecond 每秒播放的中文字符数，默认5
     * @return SRT格式的字符串
     */
    fun generateSrtContent(
        subtitles: List<String>,
        totalDurationMs: Long,
        charPerSecond: Float = 5f
    ): String {
        if (subtitles.isEmpty()) return ""

        // 计算总字数
        val totalChars = subtitles.sumOf { countChinese(it) }

        // 计算每个字的时间（毫秒）
        val msPerChar = if (totalChars > 0) {
            totalDurationMs.toFloat() / totalChars
        } else {
            1000f / charPerSecond
        }

        val sb = StringBuilder()
        var currentTimeMs = 0L
        var index = 1

        for (subtitle in subtitles) {
            val charCount = countChinese(subtitle)
            val durationMs = (charCount * msPerChar).toLong()

            val startMs = currentTimeMs
            val endMs = currentTimeMs + durationMs

            sb.append(index).append("\n")
            sb.append(formatSrtTime(startMs)).append(" --> ").append(formatSrtTime(endMs)).append("\n")
            sb.append(subtitle).append("\n\n")

            index++
            currentTimeMs = endMs
        }

        return sb.toString()
    }

    /**
     * 生成SRT字幕内容（简化版，使用固定时间）
     *
     * @param subtitles 字幕片段列表
     * @param durationMs 每个字幕片段的持续时间（毫秒）
     * @return SRT格式的字符串
     */
    fun generateSrtContent(subtitles: List<String>, durationMs: Long = 3500): String {
        if (subtitles.isEmpty()) return ""

        val sb = StringBuilder()
        var currentTimeMs = 0L
        var index = 1

        for (subtitle in subtitles) {
            val startMs = currentTimeMs
            val endMs = currentTimeMs + durationMs

            sb.append(index).append("\n")
            sb.append(formatSrtTime(startMs)).append(" --> ").append(formatSrtTime(endMs)).append("\n")
            sb.append(subtitle).append("\n\n")

            index++
            currentTimeMs = endMs
        }

        return sb.toString()
    }

    /**
     * 格式化SRT时间 (HH:MM:SS,mmm)
     */
    private fun formatSrtTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val millis = ms % 1000
        return String.format("%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }

    /**
     * 字幕片段数据类，包含文本和显示时间
     */
    data class SubtitleItem(
        val text: String,
        val startMs: Long,
        val endMs: Long,
        val charCount: Int = countChinese(text)
    )

    /**
     * 分割文本并生成带时间的字幕列表
     *
     * @param text 原始文本
     * @param totalDurationMs 总音频时长（毫秒）
     * @param maxChars 每行最大中文字符数
     * @return 字幕片段列表，包含时间和文本
     */
    fun splitAndTime(
        text: String,
        totalDurationMs: Long,
        maxChars: Int = 15
    ): List<SubtitleItem> {
        val subtitles = splitParagraphs(text, maxChars)
        if (subtitles.isEmpty()) return emptyList()

        val totalChars = subtitles.sumOf { countChinese(it) }
        val msPerChar = if (totalChars > 0) {
            totalDurationMs.toFloat() / totalChars
        } else {
            200f
        }

        val result = mutableListOf<SubtitleItem>()
        var currentTimeMs = 0L

        for (subtitle in subtitles) {
            val charCount = countChinese(subtitle)
            val durationMs = (charCount * msPerChar).toLong()
            val endMs = currentTimeMs + durationMs

            result.add(SubtitleItem(subtitle, currentTimeMs, endMs, charCount))
            currentTimeMs = endMs
        }

        return result
    }

    /**
     * 音频片段信息（用于生成字幕）
     */
    data class AudioSegment(
        val text: String,        // 原始文本
        val durationMs: Long     // 音频时长（毫秒）
    )

    /**
     * 根据音频片段生成SRT字幕
     * 流程：
     * 1. 对每个音频片段，按标点分割文本并拼接（不超过maxChars字）
     * 2. 根据该片段的音频时长，按字数比例分配给内部字幕
     * 3. 累积计算最终的时间位置
     * 4. 应用时间偏移（timeOffsetSeconds）
     *
     * @param segments 音频片段列表，每个片段包含文本和时长
     * @param maxChars 每行字幕最大中文字符数，默认15
     * @param timeOffsetSeconds 时间偏移量（秒），负数提前，正数延后，默认0
     * @return SRT格式的字符串
     */
    fun generateSrtFromAudioSegments(
        segments: List<AudioSegment>,
        maxChars: Int = 15,
        timeOffsetSeconds: Float = 0f
    ): String {
        if (segments.isEmpty()) return ""

        val timeOffsetMs = (timeOffsetSeconds * 1000).toLong()

        val sb = StringBuilder()
        var index = 1
        var currentTimeMs = 0L

        for (segment in segments) {
            if (segment.text.isBlank() || segment.durationMs <= 0) {
                // 空白文本或无效时长，直接累加时间
                currentTimeMs += segment.durationMs
                continue
            }

            // 使用splitToSubtitles分割文本（按标点分割，相邻拼接不超过maxChars字）
            val parts = splitToSubtitles(segment.text, maxChars)

            if (parts.isEmpty()) {
                currentTimeMs += segment.durationMs
                continue
            }

            // 计算该片段内每个字幕的时长
            // 字幕时长 = 该字幕字数 / 片段总字数 * 片段音频时长
            val totalChars = parts.sumOf { countChinese(it) }
            if (totalChars == 0) {
                currentTimeMs += segment.durationMs
                continue
            }

            for (part in parts) {
                val charCount = countChinese(part)
                if (charCount == 0) continue

                // 按字数比例分配时长
                val ratio = charCount.toFloat() / totalChars
                val allocatedDuration = (segment.durationMs * ratio).toLong()
                    .coerceAtLeast(500L) // 最少显示0.5秒

                val startMs = currentTimeMs + timeOffsetMs
                val endMs = currentTimeMs + allocatedDuration + timeOffsetMs

                sb.append(index).append("\n")
                sb.append(formatSrtTime(startMs.coerceAtLeast(0))).append(" --> ").append(formatSrtTime(endMs.coerceAtLeast(0))).append("\n")
                sb.append(part).append("\n\n")

                index++
                currentTimeMs = endMs - timeOffsetMs
            }
        }

        return sb.toString()
    }
}
