package io.legado.app.service

import io.legado.app.data.appDb
import io.legado.app.data.dao.BgmAIProviderDao
import io.legado.app.data.dao.BgmAIPromptDao
import io.legado.app.data.entities.BgmAIProvider
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx
import java.util.concurrent.TimeUnit

/**
 * BGM AI服务
 */
object BgmAIService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /**
     * 根据正文内容AI识别推荐的背景音乐文件名
     * @param content 正文内容
     * @return 推荐的背景音乐文件名，如果没有则返回null
     */
    suspend fun analyzeContent(content: String, currentBgm: String? = null): String? = withContext(Dispatchers.IO) {
        try {
            io.legado.app.utils.LogUtils.d("BgmAIService", "开始分析内容，内容长度=${content.length}")
            io.legado.app.constant.AppLog.putDebug("AI背景音乐: 开始分析内容，长度=${content.length}")
            
            val provider = appDb.bgmAIProviderDao.getEnabled()
            if (provider == null) {
                io.legado.app.utils.LogUtils.d("BgmAIService", "未启用AI提供商")
                io.legado.app.constant.AppLog.put("AI背景音乐: 未启用AI提供商")
                appCtx.toastOnUi("未启用AI提供商")
                return@withContext null
            }
            
            io.legado.app.utils.LogUtils.d("BgmAIService", "AI提供商: ${provider.name}, 模型: ${provider.modelId}")
            io.legado.app.constant.AppLog.putDebug("AI背景音乐: AI提供商=${provider.name}, 模型=${provider.modelId}")

            val prompt = getPrompt(content, currentBgm)
            io.legado.app.utils.LogUtils.d("BgmAIService", "提示词长度=${prompt.length}")
            io.legado.app.constant.AppLog.putDebug("AI背景音乐: 提示词长度=${prompt.length}")
            
            val result = callOpenAIApi(provider, prompt)
            io.legado.app.utils.LogUtils.d("BgmAIService", "API返回结果: $result")
            io.legado.app.constant.AppLog.putDebug("AI背景音乐: API返回=$result")
            
            // 解析AI返回的结果，提取文件名
            val fileName = extractFileName(result)
            io.legado.app.utils.LogUtils.d("BgmAIService", "提取的文件名: $fileName")
            io.legado.app.constant.AppLog.putDebug("AI背景音乐: 提取文件名=$fileName")
            fileName
        } catch (e: Exception) {
            io.legado.app.utils.LogUtils.d("BgmAIService", "AI分析失败: ${e.message}")
            io.legado.app.constant.AppLog.put("AI背景音乐: 分析异常: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * 获取完整的提示词
     * @param currentBgm 当前正在播放的BGM文件名（主线程传入，避免在IO线程访问ExoPlayer）
     */
    private fun getPrompt(content: String, currentBgm: String? = null): String {
        val customPrompt = appDb.bgmAIPromptDao.getDefault()
        val basePrompt = customPrompt?.prompt ?: getDefaultPrompt()

        // 获取实际可用的音频文件列表，排除当前正在播放的
        val audioFileList = BgmManager.getAudioFileList().filter { 
            !it.equals(currentBgm, ignoreCase = true)
        }

        // 将音频文件列表添加到提示词中
        val audioFilesSection = if (audioFileList.isNotEmpty()) {
            "\n\n可用文件列表（已排除当前播放）：\n${audioFileList.joinToString("、")}"
        } else {
            ""
        }

        return "$basePrompt$audioFilesSection\n\n【小说正文】\n$content"
    }

    /**
     * 获取默认提示词
     */
    private fun getDefaultPrompt(): String {
        return """你是小说朗读背景音乐选择器。
你的任务：根据【小说正文】,从【音乐库】中选出1个最合适的文件名。

## 选择逻辑（优先级从高到低）
1. 题材/时代 → 判断是古风、科幻、现代、民国等
2. 场景类型 → 如打斗、日常、转场、独白、探案等
3. 情绪氛围 → 紧张、悲情、温馨、热血、诡异等
4. 用途 → 过场/转场、叙事/回忆、战斗/对峙等
5. 配器/音色 → 仅在前4项相近时用于区分

## 场景-情绪-音乐快速映射
- 过场/场景切换 → 含“过场/转场”的音乐
- 大段旁白/独白/回忆 → 含“叙事/静谧/抒情/回忆”的音乐
- 探案/悬疑/刑侦/反转 → 含“悬疑/案情/诡异/紧张/压迫感”的音乐
- 打斗/对峙/战斗/爆发 → 含“热血/战歌/鼓点/史诗/震撼/压迫感”的音乐
- 离别/伤感/孤独/夜晚/回忆 → 含“悲情/凄凉/静谧/空旷/孤寂/惆怅”的音乐
- 日常/轻松/温馨/治愈 → 含“轻快/清新/文雅/轻缓/温柔”的音乐
- 信息不足时 → 选择最中性、最不冲突的“叙事/静谧/过场”类音乐

## 输出规则

- 只返回一个文件名，不要解释，不要标点，不要额外文字。
请直接返回推荐的文件名。

---

当前可用的背景音乐文件列表如下请根据正文内容直接返回以下文件名其中一个："""
    }

    /**
     * 调用OpenAI格式的API
     */
    private suspend fun callOpenAIApi(provider: BgmAIProvider, prompt: String): String = withContext(Dispatchers.IO) {
        val url = if (provider.url.endsWith("/")) {
            "${provider.url}chat/completions"
        } else {
            "${provider.url}/chat/completions"
        }

        val requestBody = JSONObject().apply {
            put("model", provider.modelId.ifEmpty { "gpt-3.5-turbo" })
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
            put("temperature", 0.7)
            put("max_tokens", 100)
        }.toString()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${provider.apiKey}")
            .header("Content-Type", "application/json")
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response: Response = client.newCall(request).execute()
        
        if (!response.isSuccessful) {
            throw Exception("API调用失败: ${response.code}")
        }

        val responseBody = response.body?.string() ?: throw Exception("响应为空")
        
        // 解析OpenAI格式响应
        val jsonResponse = JSONObject(responseBody)
        val choices = jsonResponse.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            val firstChoice = choices.getJSONObject(0)
            val message = firstChoice.optJSONObject("message")
            message?.optString("content")?.trim() ?: ""
        } else {
            throw Exception("无法解析API响应")
        }
    }

    /**
     * 从AI返回的结果中提取文件名
     */
    private fun extractFileName(result: String): String? {
        // 清理结果，去除引号、多余空格等
        var fileName = result.trim()
        
        // 移除可能的引号
        fileName = fileName.removeSurrounding("\"").removeSurrounding("'").trim()
        
        // 移除文件扩展名（如果有）
        fileName = fileName.substringBeforeLast(".").trim()
        
        // 如果结果为空或太短，返回null
        if (fileName.length < 2) {
            return null
        }
        
        return fileName
    }

    /**
     * 测试AI提供商配置是否有效
     */
    suspend fun testProvider(provider: BgmAIProvider): Boolean = withContext(Dispatchers.IO) {
        try {
            val testPrompt = "请返回文件名：test"
            val result = callOpenAIApi(provider, testPrompt)
            result.isNotEmpty()
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
