package io.legado.app.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志收集器 - 用于收集音频合并过程中的日志并保存到文件
 */
object AudioLogCollector {

    private val logBuffer = StringBuilder()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    /**
     * 记录一条日志
     */
    fun log(message: String) {
        val timestamp = dateFormat.format(Date())
        val logLine = "[$timestamp] $message"
        logBuffer.appendLine(logLine)
    }

    /**
     * 获取所有收集的日志
     */
    fun getLogs(): String {
        return logBuffer.toString()
    }

    /**
     * 清空日志缓冲区
     */
    fun clear() {
        logBuffer.clear()
    }

    /**
     * 添加分隔线
     */
    fun addSeparator(title: String = "") {
        if (title.isNotEmpty()) {
            logBuffer.appendLine("========================================")
            logBuffer.appendLine("  $title")
            logBuffer.appendLine("========================================")
        } else {
            logBuffer.appendLine("----------------------------------------")
        }
    }
}
