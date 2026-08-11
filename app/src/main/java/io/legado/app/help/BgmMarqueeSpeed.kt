package io.legado.app.help

import io.legado.app.help.config.AppConfig

/**
 * BGM 背景音乐情绪与跑马灯速度联动计算
 */
object BgmMarqueeSpeed {

    /**
     * 根据BGM文件名计算速度倍率
     * 值越大 = 跑马灯移动越快（周期越短）
     * 值越小 = 跑马灯移动越慢（周期越长）
     */
    fun getSpeedMultiplier(bgmName: String?): Float {
        if (bgmName.isNullOrBlank()) return 1.0f

        val lower = bgmName.lowercase()

        // 1. 热血/战斗（最快）
        if (containsAny(lower, "热血", "战歌", "震撼", "史诗", "打斗", "鼓点",
                "武侠", "机甲", "战场", "战斗")) {
            return 1.8f
        }

        // 2. 紧张/对峙（较快）
        if (containsAny(lower, "紧张", "对峙", "末日", "压迫感")) {
            return 1.5f
        }

        // 3. 悬疑/恐怖（略快）
        if (containsAny(lower, "恐怖", "悬疑", "诡异", "深邃", "毛骨",
                "刑侦", "推理")) {
            return 1.3f
        }

        // 4. 轻松/搞笑（基础速度）
        if (containsAny(lower, "搞笑", "喜剧", "诙谐", "轻松", "日常",
                "有趣", "鸟鸣")) {
            return 1.0f
        }

        // 5. 温柔/浪漫（略慢）
        if (containsAny(lower, "温柔", "表白", "婚礼", "浪漫", "抒情",
                "文雅", "清新")) {
            return 0.85f
        }

        // 6. 悲伤/孤寂（较慢）
        if (containsAny(lower, "凄凉", "伤感", "悲情", "离别", "虐恋",
                "惆怅", "孤寂")) {
            return 0.75f
        }

        // 7. 极舒缓/安静（最慢）
        if (containsAny(lower, "舒缓", "静谧", "安静", "轻缓", "山水",
                "冥想", "月夜")) {
            return 0.6f
        }

        return 1.0f
    }

    /**
     * 计算最终周期（毫秒）
     * 周期 = 基础周期 / 速度倍率
     * 倍率越大 -> 周期越短 -> 跑马灯越快
     */
    fun calculateSpeed(baseSpeed: Int, bgmName: String?): Long {
        if (!AppConfig.marqueeBgmLinkEnabled) return baseSpeed.toLong()
        val multiplier = getSpeedMultiplier(bgmName)
        return (baseSpeed / multiplier).toLong()
    }

    private fun containsAny(text: String, vararg keywords: String): Boolean {
        return keywords.any { text.contains(it) }
    }
}
