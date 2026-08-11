package io.legado.app.help.tts

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.LogUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import splitties.init.appCtx

/**
 * TTS 引擎激活管理器
 * 用于在转发器朗读时，激活对应的系统 TTS 引擎
 * 
 * 注意：这里的"激活"是指让系统TTS接口识别并准备好该引擎，
 * 而不是打开TTS应用的前台服务
 */
object TtsEngineActivator {

    private const val TAG = "TtsEngineActivator"

    /**
     * TTS 引擎信息，包含包名和显示名称
     */
    data class TtsEngineInfo(
        val packageName: String,
        val label: String,
        val isDefault: Boolean = false
    )

    /**
     * 获取系统所有已安装的 TTS 引擎列表
     * 通过 Android 系统 Intent 查找所有实现 TTS 服务的应用
     */
    fun getInstalledTtsEngines(context: Context): List<TtsEngineInfo> {
        val engines = mutableListOf<TtsEngineInfo>()
        val pm = context.packageManager
        
        // 获取系统默认引擎
        val defaultEngine = try {
            val tts = TextToSpeech(context, null)
            val default = tts.defaultEngine
            tts.shutdown()
            default
        } catch (e: Exception) {
            null
        }
        
        // 通过 TTS 服务 Intent 查找
        val ttsIntents = listOf(
            Intent("android.intent.action.TTS_SERVICE"),
            Intent("android.speech.tts.TTS_SERVICE")
        )
        
        for (intent in ttsIntents) {
            try {
                val resolveInfos = pm.queryIntentServices(intent, PackageManager.GET_RESOLVED_FILTER)
                for (info in resolveInfos) {
                    val packageName = info.serviceInfo.packageName
                    // 跳过系统默认 TTS 服务（com.google.android.tts）
                    if (packageName == "com.google.android.tts") continue
                    
                    val label = try {
                        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
                    } catch (e: Exception) {
                        packageName
                    }
                    
                    val engineInfo = TtsEngineInfo(
                        packageName = packageName,
                        label = label,
                        isDefault = packageName == defaultEngine
                    )
                    
                    if (engines.none { it.packageName == packageName }) {
                        engines.add(engineInfo)
                    }
                }
            } catch (e: Exception) {
                // 忽略
            }
        }
        
        // 尝试查找特定已知 TTS 应用的包名
        val knownTtsPackages = listOf(
            "com.github.jing332.tts_server_android",
            "com.github.jing332.tts_server_android.dev",
            "com.github.jing332.tts_server",
            "com.wobble.speechengine",
            "com.ideabus.testtts"
        )
        
        for (pkgName in knownTtsPackages) {
            try {
                val appInfo = pm.getApplicationInfo(pkgName, 0)
                val label = pm.getApplicationLabel(appInfo).toString()
                
                val engineInfo = TtsEngineInfo(
                    packageName = pkgName,
                    label = label,
                    isDefault = pkgName == defaultEngine
                )
                
                if (engines.none { it.packageName == pkgName }) {
                    engines.add(engineInfo)
                }
            } catch (e: Exception) {
                // 包不存在，跳过
            }
        }
        
        // 按是否为默认引擎排序，默认引擎放在最前面
        return engines.sortedByDescending { it.isDefault }
    }

    /**
     * 从转发器 URL 中提取 TTS 包名
     * 例如: http://localhost:1221/api/tts?engine=com.github.jing332.tts_server_android.dev&text=...
     * 返回: com.github.jing332.tts_server_android.dev
     */
    fun extractPackageNameFromUrl(url: String): String? {
        if (url.isBlank()) return null
        
        // 尝试从 URL 中提取 engine 参数
        val enginePatterns = listOf(
            Regex("""[?&]engine=([^&\s]+)"""),
            Regex("""engine=([a-zA-Z0-9._]+)""")
        )
        
        for (pattern in enginePatterns) {
            val match = pattern.find(url)
            if (match != null) {
                val packageName = match.groupValues[1]
                if (isValidPackageName(packageName)) {
                    return packageName
                }
            }
        }
        
        return null
    }

    /**
     * 验证包名格式是否有效
     */
    private fun isValidPackageName(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        // Android 包名格式: 以字母开头，只能包含字母、数字、点、下划线
        return Regex("""^[a-zA-Z][a-zA-Z0-9._]*$""").matches(packageName)
    }

    /**
     * 检查系统是否安装了指定的 TTS 引擎
     */
    fun isTtsEngineInstalled(context: Context, packageName: String): Boolean {
        try {
            // 检查包是否存在
            context.packageManager.getPackageInfo(packageName, 0)
            return true
        } catch (e: PackageManager.NameNotFoundException) {
            return false
        }
    }

    /**
     * 获取当前系统默认 TTS 引擎的包名
     */
    fun getDefaultTtsEnginePackageName(context: Context): String? {
        return try {
            val tts = TextToSpeech(context, null)
            val engine = tts.defaultEngine
            tts.shutdown()
            engine
        } catch (e: Exception) {
            LogUtils.d(TAG, "获取默认 TTS 引擎失败: ${e.message}")
            null
        }
    }

    /**
     * 激活指定的 TTS 引擎
     * 
     * 通过初始化 TextToSpeech 并使用指定的引擎来"激活"它，
     * 让系统的TTS接口识别这个引擎，而不是打开前台Activity
     * 
     * @param context 上下文
     * @param packageName 要激活的 TTS 引擎包名
     * @return 是否激活成功
     */
    @Suppress("unused")
    fun activateTtsEngine(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        
        LogUtils.d(TAG, "尝试激活 TTS 引擎: $packageName")
        
        // 检查是否已安装
        if (!isTtsEngineInstalled(context, packageName)) {
            LogUtils.d(TAG, "TTS 引擎未安装: $packageName")
            return false
        }
        
        // 检查是否已经是默认引擎
        val currentDefault = getDefaultTtsEnginePackageName(context)
        if (currentDefault == packageName) {
            LogUtils.d(TAG, "TTS 引擎已是默认: $packageName")
            return true
        }
        
        // 通过初始化TextToSpeech来"激活"引擎
        // 这会让系统知道这个TTS引擎正在被使用
        return tryInitializeTtsEngine(context, packageName)
    }

    /**
     * 通过初始化TextToSpeech来激活TTS引擎
     * 这是非侵入式的激活方式，不会打开前台Activity
     */
    private fun tryInitializeTtsEngine(context: Context, packageName: String): Boolean {
        var success = false
        val initListener = object : TextToSpeech.OnInitListener {
            override fun onInit(status: Int) {
                if (status == TextToSpeech.SUCCESS) {
                    LogUtils.d(TAG, "TTS 引擎初始化成功: $packageName")
                    success = true
                } else {
                    LogUtils.d(TAG, "TTS 引擎初始化失败: $packageName, status=$status")
                }
            }
        }
        
        return try {
            // 尝试使用指定引擎初始化TTS
            // 注意：这里只是让系统识别这个引擎，不会打开UI
            val tts = TextToSpeech(context, initListener, packageName)
            // 等待初始化完成
            // 注：原500ms阻塞主线程，已改为0ms以优化启动速度。
            // 若后续发现某些引擎因初始化不及时导致异常，可恢复为500ms。
            Thread.sleep(0)
            tts.shutdown()
            success
        } catch (e: Exception) {
            LogUtils.d(TAG, "TTS 引擎初始化异常: ${e.message}")
            false
        }
    }

    /**
     * 检查TTS引擎数据是否可用，必要时请求安装
     * @param context 上下文
     * @param packageName TTS引擎包名
     * @return 是否成功
     */
    @Suppress("unused")
    fun checkTtsData(context: Context, packageName: String): Boolean {
        try {
            // 发送检查TTS数据的Intent
            val checkIntent = Intent(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA)
            checkIntent.setPackage(packageName)
            context.startActivity(checkIntent)
            LogUtils.d(TAG, "已发送TTS数据检查请求: $packageName")
            return true
        } catch (e: Exception) {
            LogUtils.d(TAG, "检查TTS数据失败: ${e.message}")
            return false
        }
    }

    /**
     * 激活当前转发器对应的 TTS 引擎
     * 
     * @param httpTtsUrl 转发器 URL
     * @return 是否激活成功，如果 URL 为空或无法提取包名则返回 false
     */
    fun activateFromHttpTtsUrl(httpTtsUrl: String?): Boolean {
        if (httpTtsUrl.isNullOrBlank()) {
            return false
        }
        
        val packageName = extractPackageNameFromUrl(httpTtsUrl)
        if (packageName == null) {
            LogUtils.d(TAG, "无法从 URL 中提取 TTS 包名: $httpTtsUrl")
            return false
        }
        
        return activateTtsEngine(appCtx, packageName)
    }

    /**
     * 激活转发器对应的 TTS 引擎
     * 优先使用指定的 ttsPackageName，其次从 URL 中提取
     * 
     * @param httpTts 转发器对象
     * @return 是否激活成功
     */
    fun activateFromHttpTts(httpTts: io.legado.app.data.entities.HttpTTS?): Boolean {
        if (httpTts == null) {
            return false
        }
        
        // 优先使用指定的 ttsPackageName
        val packageName = httpTts.ttsPackageName
        if (!packageName.isNullOrBlank()) {
            LogUtils.d(TAG, "使用配置的 TTS 包名激活: $packageName")
            return activateTtsEngine(appCtx, packageName)
        }
        
        // 其次从 URL 中提取
        return activateFromHttpTtsUrl(httpTts.url)
    }

    /**
     * 激活当前配置的 TTS 引擎（转发器 TTS 或系统 TTS 引擎）
     * 根据配置自动选择，与 App 启动时逻辑一致，可复用在任何需要提前激活的场景
     * （如朗读开始、生图请求发送前等）
     */
    fun activateCurrentTtsEngine() {
        try {
            val ttsEngine = AppConfig.ttsEngine
            if (ttsEngine.isNullOrBlank()) {
                // 系统TTS默认模式，尝试激活已保存的系统TTS引擎包名
                val sysTtsPkg = AppConfig.sysTtsPackageName
                if (!sysTtsPkg.isNullOrBlank()) {
                    LogUtils.d(TAG, "App 初始化，激活系统TTS引擎: $sysTtsPkg")
                    activateTtsEngine(appCtx, sysTtsPkg)
                }
                return
            }
            
            // 检查是否是转发器（数字 ID 格式）
            if (!io.legado.app.utils.StringUtils.isNumeric(ttsEngine)) {
                // 非数字格式可能是系统TTS引擎JSON配置，尝试激活已保存的包名
                val sysTtsPkg = AppConfig.sysTtsPackageName
                if (!sysTtsPkg.isNullOrBlank()) {
                    LogUtils.d(TAG, "App 初始化，激活系统TTS引擎: $sysTtsPkg")
                    activateTtsEngine(appCtx, sysTtsPkg)
                }
                return
            }
            
            val httpTtsId = ttsEngine.toLongOrNull() ?: return
            val httpTts = io.legado.app.data.appDb.httpTTSDao.get(httpTtsId)
            
            httpTts?.let {
                LogUtils.d(TAG, "App 初始化，激活转发器 TTS: ${it.name}")
                activateFromHttpTts(it)
            }
        } catch (e: Exception) {
            LogUtils.d(TAG, "App 初始化激活 TTS 失败: ${e.message}")
        }
    }

    /**
     * App 初始化时激活对应的 TTS 引擎（兼容旧调用点）
     */
    fun activateOnAppInit() {
        activateCurrentTtsEngine()
    }
}
