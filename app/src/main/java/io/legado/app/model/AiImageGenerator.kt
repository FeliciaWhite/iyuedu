package io.legado.app.model

import com.google.gson.JsonParser
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiImageTemplate
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.tts.TtsEngineActivator
import io.legado.app.utils.GSON
import io.legado.app.utils.getPrefString
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import splitties.init.appCtx
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * AI 图片生成器
 * 根据朗读场景（背景音乐切换）生成 AI 图片，与 BGM 同步切换，使用 BGM 收集的文本。
 *
 * 配置以「模板」为单位存储在数据库（AiImageTemplate）。
 * 每本书可绑定一个模板；未显式绑定时使用默认模板。
 * 生图失败时用原提示词重试一次，再失败则放弃本次生图，等待下次触发。
 */
object AiImageGenerator {

    const val CACHE_DIR_NAME = "ai_images"
    const val DEFAULT_STYLE_SUFFIX = "，超高清8K，有故事感，国风3DCG，皮肤质感真实细腻；柔和柔光打光，电影级细腻光影、皮肤次表面散射，细腻材质质感，氛围感清冷温柔，仙侠唯美，写实渲染，PBR材质，全局光照，极致画质，景深特写，高级冷调色调"
    const val DEFAULT_PROMPT_TEMPLATE = "请根据以下小说片段，从中选取一个最有画面感、最精彩动人的场景，生成一张完整的场景插图：{mood}{text}{book}要求：1.必须是一个完整的场景画面（包含环境背景、空间氛围、人物位置关系、互动动作），不要只画人物特写或正面肖像；2.选取主角参与度最高、互动最丰富的瞬间；3.如果片段中有女性角色，优先选取主角与美女角色互动的场景，女性角色娇媚动人、美丽迷人；4.男性角色英姿飒爽、气宇轩昂。{style}"
    const val DEFAULT_NEGATIVE_PROMPT = "凝重的眼神，愁眉苦脸，丑陋，畸形，低质量"
    const val DEFAULT_MODEL_URL = "https://api.siliconflow.cn/v1"
    const val DEFAULT_MODEL_NAME = "Kwai-Kolors/Kolors"

    /** 魔搭 Qwen-Image 2.0 Pro Gradio 兜底接口（无需密钥），当模板三字段全空时使用 */
    const val GRADIO_API_BASE = "https://qwen-qwen-image-2-0-pro.ms.show"
    const val GRADIO_API_SUBMIT = "https://qwen-qwen-image-2-0-pro.ms.show/gradio_api/call/generate_image"
    /** Gradio 兜底默认竖版尺寸（宽×高） */
    const val GRADIO_DEFAULT_WIDTH = 1024
    const val GRADIO_DEFAULT_HEIGHT = 1536

    /** 默认模板的固定 id，首次初始化时使用 */
    const val DEFAULT_TEMPLATE_ID = 1L

    /** 每本书最多绑定的模板数量 */
    const val MAX_BOUND_TEMPLATES = 20

    /** 模板总数上限（新建模板时检查） */
    const val MAX_TEMPLATES = 20

    /** 密钥分隔符，多个密钥用 @@ 分割实现轮换 */
    const val KEY_SEPARATOR = "@@"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 场景缓存：key = bookUrl_chapterIndex_sceneIndex，避免重复生成 */
    private val sceneCache = ConcurrentHashMap<String, File>()

    /**
     * 持久缓存上下文：携带生图时的段落范围与章节信息，用于持久缓存命中查询与落盘。
     * - startPara/endPara: 本次生图覆盖的段落区间
     * - charCount: 当前设定的字数阈值（用于落盘记录，供后续命中推断旧图粗细）
     */
    data class PersistentCacheContext(
        val bookUrl: String,
        val bookName: String,
        val chapterIndex: Int,
        val chapterName: String,
        val startPara: Int,
        val endPara: Int,
        val charCount: Int,
    )

    /** 每个 cacheKey 正在生成的 Job，替代单一 generatingKey，避免并发生成任务互相覆盖导致等待逻辑失效 */
    private val generatingJobs = ConcurrentHashMap<String, Job>()

    private var lastGeneratedImagePath: String? = null

    /**
     * 显示顺序记录（含命中复用的旧图），最新在前，用于左右切换图库。
     * 与 getHistoryImages()（仅新生成图）不同，这里还包含持久缓存命中复用的旧图，
     * 使命中图也能进入左右切换的图库。
     */
    private val displaySequence = mutableListOf<File>()

    var enabled: Boolean
        get() = AppConfig.readAloudAiImage
        set(value) {
            AppConfig.readAloudAiImage = value
            if (!value) {
                generatingJobs.values.forEach { it.cancel() }
                generatingJobs.clear()
                postEvent(EventBus.AI_IMAGE_CHANGED, "")
            }
        }

    // ========== 模板与书籍绑定（支持多模板轮换） ==========

    /**
     * 获取书籍当前生效的模板列表。
     * 若该书籍绑定了多个模板则返回这些模板；未绑定时返回默认模板。
     * 生图时按场景索引轮换使用列表中的模板。
     */
    fun getActiveTemplates(bookUrl: String): List<AiImageTemplate> {
        val boundIds = getBoundTemplateIds(bookUrl)
        if (boundIds.isNotEmpty()) {
            val templates = boundIds.mapNotNull { appDb.aiImageTemplateDao.getById(it) }
                .filter { t ->
                    val allBlank = t.modelUrl.isBlank() && t.modelName.isBlank() && t.modelKey.isBlank()
                    val hasUrlAndName = t.modelUrl.isNotBlank() && t.modelName.isNotBlank()
                    hasUrlAndName || allBlank
                }
            if (templates.isNotEmpty()) return templates
        }
        val default = appDb.aiImageTemplateDao.getDefault() ?: builtinDefaultTemplate()
        return listOf(default)
    }

    /** 获取默认模板 id（数据库中 isDefault=1 的模板），不存在时返回内置默认 id */
    fun getDefaultTemplateId(): Long {
        return appDb.aiImageTemplateDao.getDefault()?.id ?: DEFAULT_TEMPLATE_ID
    }

    /** 读取书籍绑定的模板 id 列表，未绑定返回空列表（表示使用默认模板） */
    fun getBoundTemplateIds(bookUrl: String): List<Long> {
        if (bookUrl.isBlank()) return emptyList()
        return appCtx.getPrefString(PreferKey.aiImageTemplateBindPrefix + bookUrl, null)
            ?.split(",")
            ?.mapNotNull { it.trim().toLongOrNull() }
            ?.filter { it > 0 }
            ?: emptyList()
    }

    /** 设置书籍绑定的模板 id 列表 */
    fun setBoundTemplateIds(bookUrl: String, templateIds: List<Long>) {
        if (bookUrl.isBlank()) return
        if (templateIds.isEmpty()) {
            appCtx.removePref(PreferKey.aiImageTemplateBindPrefix + bookUrl)
        } else {
            appCtx.putPrefString(PreferKey.aiImageTemplateBindPrefix + bookUrl, templateIds.joinToString(","))
        }
    }

    /**
     * 添加单个模板绑定（支持多模板轮换）。
     * @return true 绑定成功；false 已达上限 [MAX_BOUND_TEMPLATES]。
     */
    fun addBoundTemplateId(bookUrl: String, templateId: Long): Boolean {
        val ids = getBoundTemplateIds(bookUrl).toMutableList()
        if (templateId in ids) return true
        if (ids.size >= MAX_BOUND_TEMPLATES) return false
        ids.add(templateId)
        setBoundTemplateIds(bookUrl, ids)
        return true
    }

    /** 移除单个模板绑定 */
    fun removeBoundTemplateId(bookUrl: String, templateId: Long) {
        val ids = getBoundTemplateIds(bookUrl).toMutableList()
        if (ids.remove(templateId)) {
            setBoundTemplateIds(bookUrl, ids)
        }
    }

    /** 解除书籍所有绑定（回到默认模板） */
    fun unbindBook(bookUrl: String) {
        if (bookUrl.isBlank()) return
        appCtx.removePref(PreferKey.aiImageTemplateBindPrefix + bookUrl)
    }

    /**
     * 判断书籍是否绑定了指定模板。
     * 未显式绑定时视为绑定到默认模板。
     */
    fun isBookBoundToTemplate(bookUrl: String, templateId: Long, isDefault: Boolean): Boolean {
        val ids = getBoundTemplateIds(bookUrl)
        return if (ids.isEmpty()) {
            isDefault
        } else {
            templateId in ids
        }
    }

    private fun builtinDefaultTemplate(): AiImageTemplate {
        return AiImageTemplate(
            id = DEFAULT_TEMPLATE_ID,
            name = "默认模板",
            modelUrl = DEFAULT_MODEL_URL,
            modelName = DEFAULT_MODEL_NAME,
            imageSize = "784x1168",
            imageStyle = DEFAULT_STYLE_SUFFIX,
            promptTemplate = DEFAULT_PROMPT_TEMPLATE,
            negativePrompt = DEFAULT_NEGATIVE_PROMPT,
            isDefault = true
        )
    }

    // ========== 图片生成 ==========

    /**
     * 解析模板密钥字段，多个密钥用 [KEY_SEPARATOR]（@@）分隔。
     * @return 密钥列表（已去空、去空串），无密钥返回空列表
     */
    fun parseKeys(modelKey: String): List<String> {
        if (modelKey.isBlank()) return emptyList()
        return modelKey.split(KEY_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * 判断模板是否为魔搭 Gradio 兜底模板（接口地址、模型名、密钥三字段全空）。
     * 此类模板无需密钥，走内置免费魔搭接口，不参与密钥轮换。
     * 统一用此方法判断，避免多处判断不一致。
     */
    fun isGradioFallback(template: AiImageTemplate): Boolean {
        return template.modelUrl.isBlank() && template.modelName.isBlank() && template.modelKey.isBlank()
    }

    /** 密钥槽位：模板 + 具体密钥 + 该密钥在模板中的序号（从0开始） */
    private data class KeySlot(
        val template: AiImageTemplate,
        val key: String,
        val keyIndex: Int,
    )

    /**
     * 构建密钥槽位列表。
     * - 魔搭兜底模板（三字段全空，无需密钥）：占一个空密钥槽位，直接走魔搭接口
     * - 普通模板：每个密钥各占一个槽位，生图时按槽位顺序轮换
     * 生图时按槽位顺序轮换，实现「同一模板多密钥依次用完，再切换到下一个模板」。
     */
    private fun buildKeySlots(templates: List<AiImageTemplate>): List<KeySlot> {
        val slots = mutableListOf<KeySlot>()
        for (template in templates) {
            if (isGradioFallback(template)) {
                // 魔搭兜底模板：无需密钥，占一个空槽位，调用时走 callGradioApi
                slots.add(KeySlot(template, "", 0))
                continue
            }
            val keys = parseKeys(template.modelKey)
            if (keys.isEmpty()) {
                // 有接口地址/模型名但无密钥的模板（部分接口无需鉴权），占一个空槽位
                slots.add(KeySlot(template, "", 0))
            } else {
                keys.forEachIndexed { idx, key ->
                    slots.add(KeySlot(template, key, idx))
                }
            }
        }
        return slots
    }

    /**
     * 获取并递增全局密钥槽位索引（跨场景、跨重试持续轮换）。
     * 每次调用返回当前索引并将索引 +1（对槽位总数取模），保证每次生图请求使用下一个密钥。
     */
    @Synchronized
    private fun nextSlotIndex(totalSlots: Int): Int {
        if (totalSlots <= 0) return 0
        val prefs = appCtx.getSharedPreferences("aiImageKeyRotation", 0)
        val current = prefs.getInt("slotIndex", 0) % totalSlots
        prefs.edit().putInt("slotIndex", (current + 1) % totalSlots).apply()
        return current
    }

    /**
     * 根据场景信息生成 AI 图片
     * @param bookUrl 书籍唯一标识
     * @param chapterIndex 章节索引
     * @param sceneIndex 场景索引（字数驱动的触发序号）
     * @param mood 场景氛围（可空）
     * @param sourceText 场景文本片段
     * @param bookName 书名
     * @param retryCount 重试次数（默认1），每次重试通过 contentProvider 重新收集正文
     * @param contentProvider 重试时重新收集正文的回调（传 null 则用原文本重试）
     */
    fun generateForScene(
        bookUrl: String,
        chapterIndex: Int,
        sceneIndex: Int,
        mood: String,
        sourceText: String,
        bookName: String,
        retryCount: Int = 1,
        contentProvider: (suspend () -> String?)? = null,
        cacheCtx: PersistentCacheContext? = null,
        // 重试时重新计算段落范围并复查持久缓存的回调（基于当前朗读位置），
        // 用于生图失败后段落递增时复用之前的旧图。传 null 则不复查（预生成场景）。
        cacheCtxProvider: (() -> PersistentCacheContext?)? = null,
    ) {
        AppLog.put("AI生图[DEBUG] generateForScene入口: sceneIndex=$sceneIndex, enabled=$enabled, sourceTextLen=${sourceText.length}")
        if (!enabled) {
            AppLog.put("AI生图[DEBUG] generateForScene: enabled=false, 直接返回")
            return
        }
        val templates = getActiveTemplates(bookUrl)
        AppLog.put("AI生图[DEBUG] generateForScene: templates数量=${templates.size}")
        if (templates.isEmpty()) {
            AppLog.put("AI生图[DEBUG] generateForScene: templates为空, 直接返回")
            return
        }
        // 按 sceneIndex 轮换选择模板，实现多模板依次轮换生图
        val template = templates[sceneIndex % templates.size]
        val useGradioFallback = template.modelUrl.isBlank() && template.modelName.isBlank() && template.modelKey.isBlank()
        if (!useGradioFallback && (template.modelUrl.isBlank() || template.modelName.isBlank())) {
            AppLog.put("AI生图[DEBUG] generateForScene: 模板url或name为空, 直接返回")
            return
        }

        val cacheKey = "${bookUrl}_${chapterIndex}_${sceneIndex}"
        // 已缓存则直接通知
        if (sceneCache.containsKey(cacheKey)) {
            AppLog.put("AI生图[DEBUG] generateForScene: 命中内存缓存, 直接通知")
            val cachedPath = sceneCache[cacheKey]?.absolutePath ?: ""
            // 同步更新 lastGeneratedImagePath，确保退出重进朗读界面时 getLastGeneratedImage 能取到最新图片
            if (cachedPath.isNotBlank()) {
                lastGeneratedImagePath = cachedPath
            }
            recordDisplay(cachedPath)
            postEvent(EventBus.AI_IMAGE_CHANGED, cachedPath)
            return
        }

        // 持久缓存命中查询：二次朗读同一处时直接复用已保存图片，不再调 API
        if (cacheCtx != null) {
            val hit = AiImagePersistentCache.findHit(
                bookUrl = cacheCtx.bookUrl,
                bookName = cacheCtx.bookName,
                chapterIndex = chapterIndex,
                chapterName = cacheCtx.chapterName,
                startPara = cacheCtx.startPara,
                endPara = cacheCtx.endPara,
                charCount = cacheCtx.charCount,
            )
            if (hit != null) {
                AppLog.put("AI生图[DEBUG] generateForScene: 命中持久缓存, 回填并通知")
                sceneCache[cacheKey] = hit
                lastGeneratedImagePath = hit.absolutePath
                recordDisplay(hit.absolutePath)
                postEvent(EventBus.AI_IMAGE_CHANGED, hit.absolutePath)
                return
            }
        }

        // 已有同名任务在生成（可能是预生成），可靠地 join 等待其完成。
        // 若等待结束后缓存仍为空（预生成失败），由本调用兜底重新生成，避免该场景永久卡死、后续切换全部失效。
        val existing = generatingJobs[cacheKey]
        if (existing != null && existing.isActive) {
            AppLog.put("AI生图[DEBUG] generateForScene: sceneIndex=$sceneIndex 已有任务在跑, join等待")
            scope.launch {
                try {
                    existing.join()
                } catch (_: Exception) {}
                val cached = sceneCache[cacheKey]
                if (cached != null) {
                    // 避免重复通知：若 startSceneGenerate(notifyUi=true) 已通知过同一路径，跳过
                    // 这样当 displayCurrentAiImage 被调用两次时，第二次 join 完成不会重复推送图片
                    if (lastGeneratedImagePath != cached.absolutePath) {
                        AppLog.put("AI生图[DEBUG] generateForScene: join完成, 缓存命中, 通知UI")
                        lastGeneratedImagePath = cached.absolutePath
                        recordDisplay(cached.absolutePath)
                        postEvent(EventBus.AI_IMAGE_CHANGED, cached.absolutePath)
                    } else {
                        AppLog.put("AI生图[DEBUG] generateForScene: join完成, 缓存命中, 但已通知过, 跳过")
                    }
                } else {
                    // 预生成失败或并发请求时旧 job 未生成成功，兜底重新生成当前场景并通知 UI
                    AppLog.put("AI生图[DEBUG] generateForScene: join完成但缓存为空, 兜底重新生成 sceneIndex=$sceneIndex")
                    startSceneGenerate(
                        templates, sceneIndex, mood, sourceText, bookName,
                        cacheKey, retryCount, contentProvider, notifyUi = true, cacheCtx = cacheCtx,
                        cacheCtxProvider = cacheCtxProvider
                    )
                }
            }
            return
        }

        AppLog.put("AI生图[DEBUG] generateForScene: sceneIndex=$sceneIndex 无同名任务, 启动新生成")
        startSceneGenerate(
            templates, sceneIndex, mood, sourceText, bookName,
            cacheKey, retryCount, contentProvider, notifyUi = true, cacheCtx = cacheCtx,
            cacheCtxProvider = cacheCtxProvider
        )
    }

    /**
     * 启动一个场景的生成任务（实时生成与预生成共用）。
     * 用 generatingJobs[cacheKey] 跟踪每个场景的生成 Job，不同场景间互不取消。
     * 注意：本方法不做判重，由调用方保证不重复启动同一场景。
     * @param notifyUi true=生成成功后通知 UI 显示；false=预生成静默不通知
     */
    private fun startSceneGenerate(
        templates: List<AiImageTemplate>,
        sceneIndex: Int,
        mood: String,
        sourceText: String,
        bookName: String,
        cacheKey: String,
        retryCount: Int,
        contentProvider: (suspend () -> String?)?,
        notifyUi: Boolean,
        cacheCtx: PersistentCacheContext? = null,
        cacheCtxProvider: (() -> PersistentCacheContext?)? = null,
    ) {
        val job = scope.launch {
            try {
                val genResult = generateWithRetry(
                    templates, sceneIndex, mood, sourceText, bookName, cacheKey, retryCount, contentProvider,
                    cacheCtxProvider = cacheCtxProvider
                )
                if (genResult != null) {
                    val imageFile = genResult.file
                    sceneCache[cacheKey] = imageFile
                    // 持久化到 AI生图/{书名}/{章节}/ 目录，供二次朗读命中复用
                    // 仅当"本次是真正新生成的图"且当前书开启"保存AI图片"开关时才落盘。
                    // 命中已有持久缓存时（genResult.isHit=true），imageFile 就是章节文件夹里
                    // 已落盘的旧图，若再 saveImage 会按当前偏移区间把同一张图存成新文件名，
                    // 造成章节文件夹里出现两张一模一样的图——这是之前重复的根因。
                    if (!genResult.isHit && cacheCtx != null && AppConfig.isAiImageSaveEnabled(cacheCtx.bookUrl)) {
                        AiImagePersistentCache.saveImage(
                            bookName = cacheCtx.bookName,
                            chapterIndex = cacheCtx.chapterIndex,
                            chapterName = cacheCtx.chapterName,
                            startPara = cacheCtx.startPara,
                            endPara = cacheCtx.endPara,
                            charCount = cacheCtx.charCount,
                            sourceText = sourceText,
                            imageFile = imageFile,
                        )
                    }
                    if (notifyUi) {
                        lastGeneratedImagePath = imageFile.absolutePath
                        recordDisplay(imageFile.absolutePath)
                        postEvent(EventBus.AI_IMAGE_CHANGED, imageFile.absolutePath)
                    } else {
                        AppLog.put("AI生图: 预生成完成 sceneIndex=$sceneIndex")
                    }
                    trimHistoryIfNeeded()
                } else {
                    AppLog.put(
                        "AI生图${if (notifyUi) "" else "预生成"}重试${retryCount}次仍失败，放弃本次生图，等待下次触发"
                    )
                }
            } catch (e: Exception) {
                AppLog.put("AI生图${if (notifyUi) "" else "预生成"}失败: ${e.message}", e)
            } finally {
                generatingJobs.remove(cacheKey)
            }
        }
        generatingJobs[cacheKey] = job
    }

    /**
     * 生图结果包装：file 为最终图片文件；isHit 表示是否命中已有持久缓存。
     * 命中时 file 就是章节文件夹里已落盘的旧图，调用方【禁止】再调用 saveImage，
     * 否则会按当前偏移区间把同一张图又存成新文件名，造成章节文件夹里出现两张。
     */
    private data class GenerateResult(val file: File, val isHit: Boolean)

    /**
     * 支持自定义重试次数的生图核心。
     * 每次重试通过 contentProvider 重新收集正文内容并重建提示词，同时轮换模板。
     * @param templates 书籍绑定的模板列表（多模板时重试轮换使用）
     * @param sceneIndex 场景序号，用于模板轮换基准
     * @param retryCount 重试次数（不含首次），默认1
     * @param contentProvider 重试时重新收集正文的回调，返回null或空则沿用上次文本
     */
    private suspend fun generateWithRetry(
        templates: List<AiImageTemplate>,
        sceneIndex: Int,
        mood: String,
        sourceText: String,
        bookName: String,
        cacheKey: String,
        retryCount: Int = 1,
        contentProvider: (suspend () -> String?)? = null,
        cacheCtxProvider: (() -> PersistentCacheContext?)? = null,
    ): GenerateResult? {
        // 生图发送请求前，先激活当前 TTS 引擎
        // 后台 TTS 常被系统回收/关闭，提前激活以保证朗读在生图期间不中断
        // 激活逻辑与朗读开始时完全一致（转发器 TTS 或系统 TTS 按配置选择）
        TtsEngineActivator.activateCurrentTtsEngine()

        var currentText = sourceText
        val maxAttempts = 1 + retryCount.coerceAtLeast(0)

        // 构建密钥槽位列表：同一模板的多个密钥依次排列，用完后切换到下一个模板。
        // 每次生图请求（含重试）从全局轮换索引取下一个槽位，实现多密钥轮换。
        val slots = buildKeySlots(templates)
        if (slots.isEmpty()) return null

        for (attempt in 1..maxAttempts) {
            // 每次重试前，根据当前朗读位置重新计算段落范围并复查持久缓存：
            // 朗读在生图期间持续前移，段落递增后可能正好对齐到之前的旧图，
            // 此时直接复用旧图而非再调 API（正文也通过 contentProvider 紧跟剧情）。
            if (cacheCtxProvider != null) {
                val freshCtx = cacheCtxProvider()
                if (freshCtx != null) {
                    val retryHit = AiImagePersistentCache.findHit(
                        bookUrl = freshCtx.bookUrl,
                        bookName = freshCtx.bookName,
                        chapterIndex = freshCtx.chapterIndex,
                        chapterName = freshCtx.chapterName,
                        startPara = freshCtx.startPara,
                        endPara = freshCtx.endPara,
                        charCount = freshCtx.charCount,
                    )
                    if (retryHit != null) {
                        AppLog.put("AI生图第${attempt}次尝试前复查命中持久缓存, 直接复用 区间[${freshCtx.startPara},${freshCtx.endPara}]")
                        return GenerateResult(retryHit, isHit = true)
                    }
                }
            }
            // 请求间隔：每次请求（含首次与重试）前等待设定的间隔时间，避免触发 API 频率限制
            val intervalMs = (AppConfig.aiImageRequestInterval * 1000).toLong().coerceAtLeast(0)
            if (intervalMs > 0) {
                AppLog.put("AI生图第${attempt}次请求前等待 ${intervalMs}ms 间隔")
                delay(intervalMs)
            }
            // 每次尝试（含重试）轮换密钥槽位：同一模板多密钥依次用完，再切换下一个模板
            val slotIndex = nextSlotIndex(slots.size)
            val slot = slots[slotIndex]
            val template = slot.template
            val prompt = buildPrompt(template, mood, currentText, bookName)
            // 魔搭兜底模板无需密钥，单独显示日志避免误导
            if (isGradioFallback(template)) {
                AppLog.put("AI生图第${attempt}次尝试，模板=${template.name}，魔搭兜底(无需密钥)")
            } else {
                val keyCount = parseKeys(template.modelKey).size.coerceAtLeast(1)
                AppLog.put("AI生图第${attempt}次尝试，模板=${template.name}，密钥${slot.keyIndex + 1}/$keyCount")
            }
            val result = try {
                callImageApi(template, slot.key, prompt, cacheKey)
            } catch (e: Exception) {
                AppLog.put("AI生图第${attempt}次请求异常: ${e.message}")
                null
            }
            if (result != null) {
                AppLog.put("AI生图第${attempt}次成功")
                return GenerateResult(result, isHit = false)
            }
            if (attempt < maxAttempts) {
                // 重试前重新收集正文（朗读在继续，段落位置已变化）
                if (contentProvider != null) {
                    val newText = withContext(Dispatchers.IO) { contentProvider() }
                    if (!newText.isNullOrBlank()) {
                        currentText = newText
                        AppLog.put("AI生图第${attempt}次失败，重试已重新收集文本(${currentText.length}字)")
                    } else {
                        AppLog.put("AI生图第${attempt}次失败，重新收集文本为空，沿用上次文本重试")
                    }
                } else {
                    AppLog.put("AI生图第${attempt}次失败，使用原文本重试")
                }
            }
        }
        return null
    }

    /**
     * 获取已缓存的场景图片（内存+文件系统）
     */
    fun getCachedImage(bookUrl: String, chapterIndex: Int, sceneIndex: Int): File? {
        val cacheKey = "${bookUrl}_${chapterIndex}_${sceneIndex}"
        sceneCache[cacheKey]?.let { return it }
        // 内存缺失时从文件系统恢复
        val file = getCacheFile(cacheKey)
        if (file.exists()) {
            sceneCache[cacheKey] = file
            return file
        }
        return null
    }

    /**
     * 检查指定场景是否已有生成任务在跑
     */
    fun isGenerating(bookUrl: String, chapterIndex: Int, sceneIndex: Int): Boolean {
        val cacheKey = "${bookUrl}_${chapterIndex}_${sceneIndex}"
        val job = generatingJobs[cacheKey]
        return job != null && job.isActive
    }

    fun getLastGeneratedImage(): File? {
        lastGeneratedImagePath?.let { path ->
            val f = File(path)
            if (f.exists()) return f
        }
        // 内存变量失效时，从文件系统取最新一张（按修改时间倒序第一张）
        return getHistoryImages().firstOrNull()
    }

    private fun getCacheFile(cacheKey: String): File {
        val cacheDir = File(appCtx.cacheDir, CACHE_DIR_NAME)
        if (!cacheDir.exists()) cacheDir.mkdirs()
        return File(cacheDir, "${cacheKey.hashCode()}.png")
    }

    /** 历史图片保留数量上限（可在设置中调整，默认 50） */
    private val maxHistoryImages: Int
        get() = AppConfig.aiImageCacheCount

    /**
     * 获取按最后修改时间倒序排列的历史图片列表（最新在前）。
     * 仅统计生图缓存文件（排除测试图片 test_image_*）。
     * 同时清理超过 [maxHistoryImages] 张的旧图片。
     */
    fun getHistoryImages(): List<File> {
        val cacheDir = File(appCtx.cacheDir, CACHE_DIR_NAME)
        if (!cacheDir.exists()) return emptyList()
        val files = cacheDir.listFiles { f ->
            f.isFile && f.name.endsWith(".png") && !f.name.startsWith("test_image_")
        }?.sortedByDescending { it.lastModified() }
            ?: return emptyList()
        // 清理超量旧图片
        if (files.size > maxHistoryImages) {
            files.drop(maxHistoryImages).forEach { it.delete() }
            return files.take(maxHistoryImages)
        }
        return files
    }

    /**
     * 记录一次"真正显示"的图片（含命中复用的旧图），供左右切换图库使用。
     * 去重、置顶、限长，保证显示过的命中旧图也出现在历史切换列表里。
     */
    private fun recordDisplay(path: String) {
        if (path.isBlank()) return
        val f = File(path)
        if (!f.exists()) return
        displaySequence.removeAll { it.absolutePath == f.absolutePath }
        displaySequence.add(0, f)
        while (displaySequence.size > maxHistoryImages) {
            displaySequence.removeAt(displaySequence.lastIndex)
        }
    }

    /**
     * 左右切换用的图库：显示过的命中旧图（displaySequence，最新显示在前）+
     * 新生成图（getHistoryImages），合并去重后限长。
     *
     * 注意：命中旧图位于持久缓存目录（/Download/AI生图/...），修改时间很老，
     * 若按 lastModified 倒序再 take(50)，会被大量新生成图挤掉、永远进不了切换列表。
     * 因此这里以"显示顺序优先"排列，保证命中图一定保留在列表内、且排在最前（与当前显示一致）。
     */
    fun getDisplayGallery(): List<File> {
        val result = LinkedHashSet<File>()
        // 显示顺序优先（最新显示在前），命中旧图一定保留，不会被时间排序丢弃
        displaySequence.forEach { result.add(it) }
        // 再补充新生成图（getHistoryImages 已自行限长并清理超量旧图）
        getHistoryImages().forEach { result.add(it) }
        // 整体限长，但命中图因排在前面而优先保留
        return result.take(maxHistoryImages)
    }


    /**
     * 清理超量历史图片，保证磁盘缓存不超过 [maxHistoryImages] 张。
     * 在每张新图生成后调用，避免图片无限增长。
     */
    private fun trimHistoryIfNeeded() {
        try {
            val cacheDir = File(appCtx.cacheDir, CACHE_DIR_NAME)
            if (!cacheDir.exists()) return
            val files = cacheDir.listFiles { f ->
                f.isFile && f.name.endsWith(".png") && !f.name.startsWith("test_image_")
            }?.sortedByDescending { it.lastModified() } ?: return
            if (files.size > maxHistoryImages) {
                files.drop(maxHistoryImages).forEach { it.delete() }
            }
            // 顺便清理超过1小时的测试图片
            val now = System.currentTimeMillis()
            cacheDir.listFiles { f -> f.isFile && f.name.startsWith("test_image_") }
                ?.forEach { if (now - it.lastModified() > 3600000L) it.delete() }
        } catch (_: Exception) {}
    }

    /**
     * 清空 AI 生图临时缓存：删除缓存目录下的所有生图文件，
     * 并清除内存中的相关引用（场景缓存、显示序列、最近图片路径）。
     * 仅删除临时缓存（cacheDir/CACHE_DIR_NAME），不会删除
     * 「保存AI图片」落盘的 /Download/AI生图/ 永久图片。
     * @return 删除的文件数量
     */
    fun clearTempCache(): Int {
        var deleted = 0
        try {
            val cacheDir = File(appCtx.cacheDir, CACHE_DIR_NAME)
            if (cacheDir.exists()) {
                cacheDir.listFiles { f ->
                    f.isFile && f.name.endsWith(".png") && !f.name.startsWith("test_image_")
                }?.forEach {
                    if (it.delete()) deleted++
                }
            }
        } catch (_: Exception) {}
        // 同步清理内存引用，避免画廊继续引用已删除的文件
        sceneCache.clear()
        displaySequence.clear()
        lastGeneratedImagePath = null
        return deleted
    }

    /**
     * 根据当前缓存数量上限，立即清理超量的旧图（用户在设置中调小上限时调用）。
     */
    fun applyCacheLimit() {
        trimHistoryIfNeeded()
    }

    /**
     * 获取当前正在显示的图片文件（优先 lastGeneratedImagePath，其次历史第一张）
     */
    fun getCurrentDisplayImage(): File? {
        lastGeneratedImagePath?.let { path ->
            val f = File(path)
            if (f.exists()) return f
        }
        return getHistoryImages().firstOrNull()
    }

    /**
     * 清除内存缓存（切换书籍时调用）
     */
    fun clearCache() {
        sceneCache.clear()
        generatingJobs.values.forEach { it.cancel() }
        generatingJobs.clear()
        lastGeneratedImagePath = null
        displaySequence.clear()
        AiImagePersistentCache.clearAllCursors()
    }

    // ========== 预生成机制 ==========

    /** 预生成图片：静默生成，不通知UI，用于提前准备下一张图片 */
    fun preGenerateForScene(
        bookUrl: String,
        chapterIndex: Int,
        sceneIndex: Int,
        mood: String,
        sourceText: String,
        bookName: String,
        retryCount: Int = 1,
        contentProvider: (suspend () -> String?)? = null,
        cacheCtx: PersistentCacheContext? = null,
        // 重试时重新计算段落范围并复查持久缓存的回调（基于当前朗读位置），
        // 用于生图失败后段落递增时复用之前的旧图。传 null 则不复查（预生成场景）。
        cacheCtxProvider: (() -> PersistentCacheContext?)? = null,
    ) {
        if (!enabled) return
        val templates = getActiveTemplates(bookUrl)
        if (templates.isEmpty()) return
        // 按 sceneIndex 轮换选择模板
        val template = templates[sceneIndex % templates.size]
        val useGradioFallback = template.modelUrl.isBlank() && template.modelName.isBlank() && template.modelKey.isBlank()
        if (!useGradioFallback && (template.modelUrl.isBlank() || template.modelName.isBlank())) return

        val cacheKey = "${bookUrl}_${chapterIndex}_${sceneIndex}"
        // 已缓存则跳过（顺便把文件系统缓存恢复到内存）
        if (sceneCache.containsKey(cacheKey)) return
        if (getCacheFile(cacheKey).exists()) {
            sceneCache[cacheKey] = getCacheFile(cacheKey)
            return
        }
        // 持久缓存命中：静默回填到内存，不再调 API
        if (cacheCtx != null) {
            val hit = AiImagePersistentCache.findHit(
                bookUrl = cacheCtx.bookUrl,
                bookName = cacheCtx.bookName,
                chapterIndex = chapterIndex,
                chapterName = cacheCtx.chapterName,
                startPara = cacheCtx.startPara,
                endPara = cacheCtx.endPara,
                charCount = cacheCtx.charCount,
            )
            if (hit != null) {
                AppLog.put("AI生图[DEBUG] preGenerateForScene: 命中持久缓存, 静默回填 sceneIndex=$sceneIndex")
                sceneCache[cacheKey] = hit
                return
            }
        }
        // 已有同名任务在生成（实时生成或预生成），直接跳过避免重复
        val existing = generatingJobs[cacheKey]
        if (existing != null && existing.isActive) return

        startSceneGenerate(
            templates, sceneIndex, mood, sourceText, bookName,
            cacheKey, retryCount, contentProvider, notifyUi = false, cacheCtx = cacheCtx,
            cacheCtxProvider = cacheCtxProvider
        )
    }

    /** 通知UI显示已预生成的图片（支持文件系统恢复）。返回 true 表示找到并通知了图片 */
    fun notifyImageIfReady(bookUrl: String, chapterIndex: Int, sceneIndex: Int): Boolean {
        val cacheKey = "${bookUrl}_${chapterIndex}_${sceneIndex}"
        var imageFile = sceneCache[cacheKey]
        if (imageFile == null) {
            val file = getCacheFile(cacheKey)
            if (file.exists()) {
                sceneCache[cacheKey] = file
                imageFile = file
            }
        }
        if (imageFile != null) {
            // 同步更新 lastGeneratedImagePath，确保退出重进朗读界面时能取到最新图片
            lastGeneratedImagePath = imageFile.absolutePath
            recordDisplay(imageFile.absolutePath)
            postEvent(EventBus.AI_IMAGE_CHANGED, imageFile.absolutePath)
            return true
        }
        return false
    }

    private fun buildPrompt(
        template: AiImageTemplate,
        mood: String,
        sourceText: String,
        bookName: String,
    ): String {
        val moodDesc = if (mood.isNotBlank()) "场景氛围：$mood。" else ""
        // 正文过滤：删除正文中包含的过滤词语（多个用竖线分隔）
        val filteredText = filterSourceText(sourceText, template.filterWords)
        val textDesc = if (filteredText.isNotBlank()) {
            val truncated = if (filteredText.length > 350) filteredText.take(350) + "..." else filteredText
            "内容片段：$truncated。"
        } else ""
        val bookDesc = if (bookName.isNotBlank()) "出自小说《$bookName》。" else ""

        return template.promptTemplate
            .replace("{mood}", moodDesc)
            .replace("{text}", textDesc)
            .replace("{book}", bookDesc)
            .replace("{style}", template.imageStyle)
    }

    /**
     * 过滤正文内容：将 [sourceText] 中包含的过滤词语删除（替换为空字符串）。
     * 过滤词语多个用竖线 | 分隔，例如：词语1|词语2|词语3
     * 空过滤词会被跳过，避免误删。
     */
    private fun filterSourceText(sourceText: String, filterWords: String): String {
        if (filterWords.isBlank() || sourceText.isBlank()) return sourceText
        var result = sourceText
        filterWords.split("|").forEach { word ->
            val trimmed = word.trim()
            if (trimmed.isNotEmpty()) {
                result = result.replace(trimmed, "")
            }
        }
        return result
    }

    private suspend fun callImageApi(
        template: AiImageTemplate,
        modelKey: String,
        prompt: String,
        cacheKey: String,
    ): File? {
        // 模板三字段全空 → 走魔搭 Gradio 兜底（免费、无需密钥）
        // 注意：此判断基于模板原始字段，不受多密钥轮换传入的 modelKey 影响，魔搭兜底不会被误判
        if (isGradioFallback(template)) {
            AppLog.put("AI生图: 模板「${template.name}」三字段全空，走魔搭Gradio兜底（无需密钥）")
            return callGradioApi(prompt, template.imageSize, cacheKey)
        }
        val url = normalizeImageApiUrl(template.modelUrl.trim())
        val authHeader = if (modelKey.isNotBlank()) {
            "Bearer ${modelKey.trim()}"
        } else null

        // 构造请求体：同时包含各主流格式的字段，兼容所有生图服务
        // 硅基流动用 image_size，OpenAI DALL-E 用 size，各服务各取所需，忽略不认识的字段
        val body = mutableMapOf<String, Any>(
            "model" to template.modelName.trim(),
            "prompt" to prompt,
            "image_size" to template.imageSize,
            "size" to template.imageSize,
            "n" to 1,
        )
        if (template.negativePrompt.isNotBlank()) {
            body["negative_prompt"] = template.negativePrompt
        }
        // 开启 base64 模式：要求 API 直接返回 base64 图片数据，避免临时 URL 秒级过期无法下载
        if (template.useBase64Response) {
            body["response_format"] = "b64_json"
        }
        val bodyJson = GSON.toJson(body)
        val requestBody = bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType())

        val requestBuilder = Request.Builder()
            .url(url)
            .post(requestBody)
            .header("Content-Type", "application/json")

        if (authHeader != null) {
            requestBuilder.header("Authorization", authHeader)
        }

        val response = okHttpClient.newCall(requestBuilder.build()).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string()?.take(500) ?: ""
            AppLog.put("AI生图 API 请求失败 HTTP ${response.code}: $errorBody")
            return null
        }

        val responseBody = response.body?.string() ?: return null
        val json = JsonParser.parseString(responseBody).asJsonObject

        // 尝试解析图片数据（支持 base64 / URL 多种格式）
        val imageBytes = extractImageBytes(json) ?: return null

        val imageFile = getCacheFile(cacheKey)
        imageFile.writeBytes(imageBytes)
        return imageFile
    }

    /**
     * 从 API 响应中提取图片字节数据
     */
    private fun extractImageBytes(json: com.google.gson.JsonObject): ByteArray? {
        // 1. OpenAI DALL-E b64_json 格式: data[0].b64_json
        try {
            val dataArray = json.getAsJsonArray("data")
            if (dataArray != null && dataArray.size() > 0) {
                val first = dataArray[0].asJsonObject
                val b64 = first.get("b64_json")?.asString
                if (!b64.isNullOrBlank()) {
                    return android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                }
            }
        } catch (_: Exception) {}

        // 2. 通用格式: images[0] 直接是 base64 字符串
        try {
            val images = json.getAsJsonArray("images")
            if (images != null && images.size() > 0) {
                val first = images[0]
                if (first.isJsonPrimitive) {
                    return android.util.Base64.decode(first.asString, android.util.Base64.DEFAULT)
                }
            }
        } catch (_: Exception) {}

        // 3. 直接 image 字段
        try {
            val image = json.get("image")?.asString
            if (!image.isNullOrBlank()) {
                return android.util.Base64.decode(image, android.util.Base64.DEFAULT)
            }
        } catch (_: Exception) {}

        // 4. 硅基流动格式: images[0].url（下载 URL 图片）
        try {
            val images = json.getAsJsonArray("images")
            if (images != null && images.size() > 0) {
                val first = images[0].asJsonObject
                val imageUrl = first.get("url")?.asString
                if (!imageUrl.isNullOrBlank()) {
                    return downloadImageBytes(imageUrl)
                }
            }
        } catch (_: Exception) {}

        // 5. OpenAI URL 格式: data[0].url
        try {
            val dataArray = json.getAsJsonArray("data")
            if (dataArray != null && dataArray.size() > 0) {
                val first = dataArray[0].asJsonObject
                val imageUrl = first.get("url")?.asString
                if (!imageUrl.isNullOrBlank()) {
                    return downloadImageBytes(imageUrl)
                }
            }
        } catch (_: Exception) {}

        return null
    }

    private fun downloadImageBytes(url: String): ByteArray? {
        return try {
            val request = Request.Builder().url(url).build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.bytes()
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 魔搭 Qwen-Image 2.0 Pro Gradio 兜底生图。
     * 严格参照 qwen_image_generator.html 的两步流程：
     *  1) POST 提交任务，拿到 event_id
     *  2) 每 3 秒轮询 …/generate_image/{event_id}，解析 SSE，拿到图片 URL 后下载
     * @param prompt 已构造好的提示词
     * @param imageSize 模板的图片尺寸（格式 "宽x高"），无法识别时用默认竖版 1024×1536
     */
    private suspend fun callGradioApi(
        prompt: String,
        imageSize: String,
        cacheKey: String,
    ): File? {
        val (width, height) = parseImageSizeForGradio(imageSize)
        // 注意：魔搭 Gradio API 的 data 数组中，第7个位置实际是"高度"、第8个位置实际是"宽度"
        // （与 qwen_image_generator.html 中 label 标注相反，HTML 误标，但行为是竖版）。
        // 为复刻 HTML 的竖版效果，这里把真实 height 放第7位、真实 width 放第8位。
        // 步骤1：提交任务
        val submitBodyJson = GSON.toJson(
            mapOf(
                "data" to listOf(
                    emptyList<Any>(),   // [] 参考图（空）
                    prompt,             // 提示词
                    false,              // 提示词扩展（关闭）
                    false,
                    0,                  // seed
                    true,               // randomize（seed===0）
                    height,             // 第7位：魔搭实际当作高度
                    width,              // 第8位：魔搭实际当作宽度
                    " "
                )
            )
        )
        val submitRequest = Request.Builder()
            .url(GRADIO_API_SUBMIT)
            .post(submitBodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Content-Type", "application/json")
            .build()

        val submitResp = try {
            okHttpClient.newCall(submitRequest).execute()
        } catch (e: Exception) {
            AppLog.put("AI生图(魔搭) 提交异常: ${e.message}")
            return null
        }
        if (!submitResp.isSuccessful) {
            AppLog.put("AI生图(魔搭) 提交失败 HTTP ${submitResp.code}")
            return null
        }
        val submitBodyStr = submitResp.body?.string() ?: run {
            AppLog.put("AI生图(魔搭) 提交响应为空")
            return null
        }
        val eventId = try {
            JsonParser.parseString(submitBodyStr).asJsonObject.get("event_id")?.asString
        } catch (_: Exception) {
            null
        }
        if (eventId.isNullOrBlank()) {
            AppLog.put("AI生图(魔搭) 未获取到 event_id: ${submitBodyStr.take(300)}")
            return null
        }

        // 步骤2：轮询（最多 20 次，每次间隔 3 秒）
        val maxAttempts = 20
        var lastImageUrl: String? = null
        var foundComplete = false
        for (attempt in 1..maxAttempts) {
            delay(3000)
            val pollRequest = Request.Builder()
                .url("$GRADIO_API_SUBMIT/$eventId")
                .build()
            val text = try {
                okHttpClient.newCall(pollRequest).execute().body?.string()
            } catch (_: Exception) {
                null
            } ?: continue

            // 解析 SSE
            val lines = text.split("\n")
            for (line in lines) {
                if (line.startsWith("event: complete")) {
                    foundComplete = true
                }
                if (line.startsWith("data: ")) {
                    val dataStr = line.substring(6)
                    if (dataStr == "[, null, null]") continue
                    try {
                        val data = JsonParser.parseString(dataStr)
                        if (data.isJsonArray && data.asJsonArray.size() > 0) {
                            val first = data.asJsonArray[0]
                            if (first.isJsonObject) {
                                val url = first.asJsonObject.get("url")?.asString
                                if (!url.isNullOrBlank()) {
                                    lastImageUrl = url
                                }
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
            if (foundComplete) break
        }

        if (lastImageUrl.isNullOrBlank()) {
            AppLog.put("AI生图(魔搭) 轮询结束未获取到图片URL")
            return null
        }
        val bytes = downloadImageBytes(lastImageUrl) ?: run {
            AppLog.put("AI生图(魔搭) 下载图片失败: $lastImageUrl")
            return null
        }
        val imageFile = getCacheFile(cacheKey)
        imageFile.writeBytes(bytes)
        return imageFile
    }

    /**
     * 解析图片尺寸为魔搭 Gradio 支持的宽高。
     * 支持 "宽x高" / "宽×高" / "宽X高"。
     * 魔搭 width∈{768,1024,1344,1536,2688}，height∈{768,1024,1344,1536,2688}。
     * 无法识别或不支持时返回默认竖版 1024×1536。
     */
    private fun parseImageSizeForGradio(imageSize: String): Pair<Int, Int> {
        val supportedW = setOf(768, 1024, 1344, 1536, 2688)
        val supportedH = setOf(768, 1024, 1344, 1536, 2688)
        val parts = imageSize.trim().split("x", "×", "X")
        if (parts.size == 2) {
            val w = parts[0].trim().toIntOrNull()
            val h = parts[1].trim().toIntOrNull()
            if (w != null && h != null && w in supportedW && h in supportedH) {
                return Pair(w, h)
            }
        }
        return Pair(GRADIO_DEFAULT_WIDTH, GRADIO_DEFAULT_HEIGHT)
    }

    /**
     * 测试模型是否可用（使用当前编辑中的参数，不依赖已保存模板）
     */
    suspend fun testModel(
        modelUrl: String,
        modelName: String,
        modelKey: String,
        imageSize: String,
        imageStyle: String,
        promptTemplate: String,
        negativePrompt: String,
        useBase64Response: Boolean = false,
    ): Result<File> {
        // 测试连接发送请求前，先激活当前 TTS 引擎
        // 后台 TTS 常被系统回收/关闭，提前激活以保证朗读在测试期间不中断
        // 激活逻辑与朗读开始、正常生图完全一致（转发器 TTS 或系统 TTS 按配置选择）
        TtsEngineActivator.activateCurrentTtsEngine()

        // 三字段全空 → 测试魔搭 Gradio 兜底
        if (modelUrl.isBlank() && modelName.isBlank() && modelKey.isBlank()) {            return try {
                val testPrompt = promptTemplate
                    .replace("{mood}", "")
                    .replace("{text}", "A beautiful landscape painting")
                    .replace("{book}", "")
                    .replace("{style}", imageStyle)
                val file = callGradioApi(testPrompt, imageSize, "test_${System.currentTimeMillis()}")
                if (file != null) Result.success(file)
                else Result.failure(Exception("魔搭生图失败，请稍后重试"))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
        return try {
            val testPrompt = promptTemplate
                .replace("{mood}", "")
                .replace("{text}", "A beautiful landscape painting")
                .replace("{book}", "")
                .replace("{style}", imageStyle)
            val url = normalizeImageApiUrl(modelUrl.trim())
            // 多密钥场景：测试连接时使用第一个密钥
            val testKey = parseKeys(modelKey).firstOrNull() ?: ""
            val authHeader = if (testKey.isNotBlank()) "Bearer ${testKey.trim()}" else null

            val body = mutableMapOf<String, Any>(
                "model" to modelName.trim(),
                "prompt" to testPrompt,
                "image_size" to imageSize,
                "size" to imageSize,
                "n" to 1,
            )
            if (negativePrompt.isNotBlank()) {
                body["negative_prompt"] = negativePrompt
            }
            // 开启 base64 模式：要求 API 直接返回 base64 图片数据
            if (useBase64Response) {
                body["response_format"] = "b64_json"
            }
            val bodyJson = GSON.toJson(body)
            val requestBody = bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType())

            val requestBuilder = Request.Builder()
                .url(url)
                .post(requestBody)
                .header("Content-Type", "application/json")

            if (authHeader != null) {
                requestBuilder.header("Authorization", authHeader)
            }

            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful) {
                val errorBody = response.body?.string()?.take(500) ?: "未知错误"
                return Result.failure(Exception("HTTP ${response.code}: $errorBody"))
            }

            val responseBody = response.body?.string() ?: return Result.failure(Exception("响应体为空"))
            val json = JsonParser.parseString(responseBody).asJsonObject
            val imageBytes = extractImageBytes(json)
                ?: return Result.failure(Exception("无法解析图片数据，请确认 API 和模型名称正确"))

            val cacheDir = File(appCtx.cacheDir, CACHE_DIR_NAME)
            if (!cacheDir.exists()) cacheDir.mkdirs()

            val imageFile = File(cacheDir, "test_image_${System.currentTimeMillis()}.png")
            imageFile.writeBytes(imageBytes)
            Result.success(imageFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun normalizeImageApiUrl(url: String): String {
        val trimmed = url.trimEnd('/')
        // 已是完整生图接口，直接用
        if (trimmed.endsWith("/images/generations")) return trimmed
        // 误填了聊天接口，替换为生图接口
        if (trimmed.endsWith("/chat/completions")) {
            return trimmed.removeSuffix("/chat/completions") + "/images/generations"
        }
        // 其他情况最多只补 /images/generations，不强制补 /v1，避免非标准路径出错
        return "$trimmed/images/generations"
    }
}
