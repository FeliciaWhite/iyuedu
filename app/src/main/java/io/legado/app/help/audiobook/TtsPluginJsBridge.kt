package io.legado.app.help.audiobook

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import io.legado.app.constant.AppLog
import org.json.JSONObject
import splitties.init.appCtx
import java.util.UUID

/**
 * JS 桥接对象，注入到网络 TTS 的 JS 执行环境中。
 *
 * 在网络 TTS 的 JS 代码中可通过 `tts.synthesize(tag, text)` 调用 JReadVoiceEngine
 * 按标签查找配置并合成音频，返回 ByteArray。
 *
 * 示例 JS 用法：
 * ```js
 * // 用 "旁白" 标签对应的配置合成音频
 * var audio = tts.synthesize("旁白", speakText);
 * // 用 "男性青年/通用01" 标签合成
 * var audio2 = tts.synthesize("男性青年/通用01", speakText);
 * // 列出所有可用标签
 * var tags = tts.listTags();
 * ```
 */
@Keep
class TtsPluginJsBridge {

    companion object {
        private const val TAG = "TtsPluginJsBridge"
    }

    private val context: Context = appCtx

    /**
     * 按标签合成音频。
     *
     * @param voiceTag 语音标签，如 "旁白"、"括号1"、"男性青年/通用01"
     * @param text 要合成的文本
     * @return 音频字节数组 (PCM 24000Hz)，失败返回 null
     */
    fun synthesize(voiceTag: String, text: String): ByteArray? {
        if (voiceTag.isBlank() || text.isBlank()) {
            AppLog.putDebug("[TtsPluginJsBridge] synthesize 参数为空: tag=$voiceTag textLen=${text.length}")
            return null
        }
        val requestId = UUID.randomUUID().toString()
        val pointerJson = JSONObject().put("voiceTag", voiceTag).toString()
        return try {
            JReadVoiceEngine.synthesizeLineAudio(context, text, pointerJson, requestId)
        } catch (e: Exception) {
            AppLog.put("[TtsPluginJsBridge] synthesize 失败: tag=$voiceTag error=${e.message}", e)
            Log.e(TAG, "synthesize failed: tag=$voiceTag", e)
            null
        }
    }

    /**
     * 使用指定配置 ID 合成音频。
     *
     * @param configId 配置 ID
     * @param text 要合成的文本
     * @return 音频字节数组，失败返回 null
     */
    fun synthesizeByConfigId(configId: String, text: String): ByteArray? {
        if (configId.isBlank() || text.isBlank()) return null
        val config = JReadVoiceEngine.listConfigs(context).firstOrNull { it.id == configId }
            ?: run {
                AppLog.putDebug("[TtsPluginJsBridge] 未找到配置: configId=$configId")
                return null
            }
        val requestId = UUID.randomUUID().toString()
        val pointerJson = JSONObject().put("voiceTag", config.voiceTag).toString()
        return try {
            JReadVoiceEngine.synthesizeConfigLineAudio(context, config, text, pointerJson, requestId)
        } catch (e: Exception) {
            AppLog.put("[TtsPluginJsBridge] synthesizeByConfigId 失败: configId=$configId error=${e.message}", e)
            Log.e(TAG, "synthesizeByConfigId failed: configId=$configId", e)
            null
        }
    }

    /**
     * 列出所有可用的语音标签。
     *
     * @return 标签字符串数组
     */
    fun listTags(): Array<String> {
        val configs = JReadVoiceEngine.listConfigs(context)
        val tags = configs.filter { it.enabled }.map { it.voiceTag }.distinct()
        AppLog.putDebug("[TtsPluginJsBridge] listTags: count=${tags.size}")
        return tags.toTypedArray()
    }

    /**
     * 列出所有配置信息（标签 + 显示名 + 插件名）。
     *
     * @return JSON 字符串数组，每项包含 voiceTag, displayName, pluginName, voice
     */
    fun listConfigs(): String {
        val configs = JReadVoiceEngine.listConfigs(context).filter { it.enabled }
        val plugins = JReadVoiceEngine.listPlugins(context)
        val array = org.json.JSONArray()
        configs.forEach { config ->
            val plugin = plugins.firstOrNull {
                it.enabled && (it.id == config.pluginId || it.pluginId == config.pluginId)
            }
            array.put(JSONObject()
                .put("id", config.id)
                .put("voiceTag", config.voiceTag)
                .put("displayName", config.displayName)
                .put("pluginName", plugin?.name.orEmpty())
                .put("voice", config.voice)
                .put("groupName", config.groupName)
                .put("subGroupName", config.subGroupName)
            )
        }
        return array.toString()
    }

    /**
     * 获取音频采样率（固定 24000Hz）。
     */
    fun getSampleRate(): Int = 24_000
}
