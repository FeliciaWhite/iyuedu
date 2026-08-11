package io.legado.app

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.os.Build
import com.github.liuyueyi.quick.transfer.constants.TransType
import com.jeremyliao.liveeventbus.LiveEventBus
import com.jeremyliao.liveeventbus.logger.DefaultLogger
import com.script.rhino.ReadOnlyJavaObject
import com.script.rhino.RhinoScriptEngine
import com.script.rhino.RhinoWrapFactory
import io.legado.app.base.AppContextWrapper
import io.legado.app.constant.AppConst.channelIdDownload
import io.legado.app.constant.AppConst.channelIdReadAloud
import io.legado.app.constant.AppConst.channelIdWeb
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.entities.rule.ExploreRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.help.AppFreezeMonitor
import io.legado.app.help.AppWebDav
import io.legado.app.help.CrashHandler
import io.legado.app.help.DefaultData
import io.legado.app.help.DispatchersMonitor
import io.legado.app.help.LifecycleHelp
import io.legado.app.help.RuleBigDataHelp
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig.applyDayNight
import io.legado.app.help.config.ThemeConfig.applyDayNightInit
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.Cronet
import io.legado.app.help.http.ObsoleteUrlFactory
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.rhino.NativeBaseSource
import io.legado.app.help.source.SourceHelp
import io.legado.app.help.storage.Backup
import io.legado.app.help.tts.TtsEngineActivator
import io.legado.app.help.tts.TtsWebSocketHelper
import io.legado.app.model.BookCover
import io.legado.app.utils.ChineseUtils
import io.legado.app.utils.LogUtils
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.isDebuggable
import kotlinx.coroutines.launch
import org.chromium.base.ThreadUtils
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.logging.Level

class App : Application() {

    private lateinit var oldConfig: Configuration

    override fun onCreate() {
        super.onCreate()
        CrashHandler(this)
        if (isDebuggable) {
            ThreadUtils.setThreadAssertsDisabledForTesting(true)
        }
        oldConfig = Configuration(resources.configuration)
        applyDayNightInit(this)
        registerActivityLifecycleCallbacks(LifecycleHelp)
        defaultSharedPreferences.registerOnSharedPreferenceChangeListener(AppConfig)
        Coroutine.async {
            LogUtils.init(this@App)
            LogUtils.d("App", "onCreate")
            LogUtils.logDeviceInfo()
            //预下载Cronet so
            Cronet.preDownload()
            createNotificationChannels()
            LiveEventBus.config()
                .lifecycleObserverAlwaysActive(true)
                .autoClear(false)
                .enableLogger(BuildConfig.DEBUG || AppConfig.recordLog)
                .setLogger(EventLogger())
            DefaultData.upVersion()
            AppFreezeMonitor.init(this@App)
            DispatchersMonitor.init()
            URL.setURLStreamHandlerFactory(ObsoleteUrlFactory(okHttpClient))
            launch { installGmsTlsProvider(appCtx) }
            initRhino()
            //初始化封面
            BookCover.toString()
            //清除过期数据
            appDb.cacheDao.clearDeadline(System.currentTimeMillis())
            if (getPrefBoolean(PreferKey.autoClearExpired, true)) {
                val clearTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
                appDb.searchBookDao.clearExpired(clearTime)
            }
            RuleBigDataHelp.clearInvalid()
            BookHelp.clearInvalidCache()
            Backup.clearCache()
            ReadBookConfig.clearBgAndCache()
//            ThemeConfig.clearBg() //每次手动切换主题时清理多余图片
            //初始化简繁转换引擎
            when (AppConfig.chineseConverterType) {
                1 -> {
                    ChineseUtils.fixT2sDict()
                    ChineseUtils.preLoad(true, TransType.TRADITIONAL_TO_SIMPLE)
                }

                2 -> ChineseUtils.preLoad(true, TransType.SIMPLE_TO_TRADITIONAL)
            }
            //调整排序序号
            SourceHelp.adjustSortNumber()
            //同步阅读记录
            if (AppConfig.syncBookProgress) {
                AppWebDav.downloadAllBookProgress()
            }
            //初始化默认AI提示词
            initDefaultBgmAIPrompts()
            //初始化默认AI生图模板
            initDefaultAiImageTemplate()
            //激活转发器对应的TTS引擎
            TtsEngineActivator.activateOnAppInit()
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppContextWrapper.wrap(base))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val diff = newConfig.diff(oldConfig)
        if ((diff and ActivityInfo.CONFIG_UI_MODE) != 0) {
            applyDayNight(this)
        }
        oldConfig = Configuration(newConfig)
    }

    /**
     * 尝试在安装了GMS的设备上(GMS或者MicroG)使用GMS内置的Conscrypt
     * 作为首选JCE提供程序，而使Okhttp在低版本Android上
     * 能够启用TLSv1.3
     * https://f-droid.org/zh_Hans/2020/05/29/android-updates-and-tls-connections.html
     * https://developer.android.google.cn/reference/javax/net/ssl/SSLSocket
     *
     * @param context
     * @return
     */
    private fun installGmsTlsProvider(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return
        }
        try {
            val gmsPackageName = "com.google.android.gms"
            val appInfo = packageManager.getApplicationInfo(gmsPackageName, 0)
            if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0) {
                return
            }
            val gms = context.createPackageContext(
                gmsPackageName,
                CONTEXT_INCLUDE_CODE or CONTEXT_IGNORE_SECURITY
            )
            gms.classLoader
                .loadClass("com.google.android.gms.common.security.ProviderInstallerImpl")
                .getMethod("insertProvider", Context::class.java)
                .invoke(null, gms)
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 创建通知ID
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val downloadChannel = NotificationChannel(
            channelIdDownload,
            getString(R.string.action_download),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val readAloudChannel = NotificationChannel(
            channelIdReadAloud,
            getString(R.string.read_aloud),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val webChannel = NotificationChannel(
            channelIdWeb,
            getString(R.string.web_service),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        //向notification manager 提交channel
        notificationManager.createNotificationChannels(
            listOf(
                downloadChannel,
                readAloudChannel,
                webChannel
            )
        )
    }

    private fun initRhino() {
        RhinoScriptEngine
        RhinoWrapFactory.register(BookSource::class.java, NativeBaseSource.factory)
        RhinoWrapFactory.register(RssSource::class.java, NativeBaseSource.factory)
        RhinoWrapFactory.register(HttpTTS::class.java, NativeBaseSource.factory)
        RhinoWrapFactory.register(TtsWebSocketHelper::class.java, NativeBaseSource.factory)
        RhinoWrapFactory.register(ExploreRule::class.java, ReadOnlyJavaObject.factory)
        RhinoWrapFactory.register(SearchRule::class.java, ReadOnlyJavaObject.factory)
        RhinoWrapFactory.register(BookInfoRule::class.java, ReadOnlyJavaObject.factory)
        RhinoWrapFactory.register(ContentRule::class.java, ReadOnlyJavaObject.factory)
        RhinoWrapFactory.register(BookChapter::class.java, ReadOnlyJavaObject.factory)
        RhinoWrapFactory.register(Book.ReadConfig::class.java, ReadOnlyJavaObject.factory)
    }

    class EventLogger : DefaultLogger() {

        override fun log(level: Level, msg: String) {
            super.log(level, msg)
            LogUtils.d(TAG, msg)
        }

        override fun log(level: Level, msg: String, th: Throwable?) {
            super.log(level, msg, th)
            LogUtils.d(TAG, "$msg\n${th?.stackTraceToString()}")
        }

        companion object {
            private const val TAG = "[LiveEventBus]"
        }
    }

    companion object {
        init {
            if (BuildConfig.DEBUG) {
                System.setProperty("kotlinx.coroutines.debug", "on")
            }
        }
    }

    /**
     * 初始化默认AI提示词
     */
    private fun initDefaultBgmAIPrompts() {
        Coroutine.async {
            // 检查是否已有提示词
            val existingPrompts = appDb.bgmAIPromptDao.getAllList()
            if (existingPrompts.isEmpty()) {
                // 添加默认提示词
                val defaultPrompt = """你是小说朗读背景音乐选择器。
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

                val defaultPromptEntity = io.legado.app.data.entities.BgmAIPrompt(
                    name = "默认提示词",
                    prompt = defaultPrompt,
                    isDefault = true
                )
                appDb.bgmAIPromptDao.insert(defaultPromptEntity)
                LogUtils.d("App", "已初始化默认AI提示词")
            }
        }
    }

    /**
     * 初始化默认AI生图模板
     * 首次使用时创建默认模板；若旧版 SharedPreferences 中已有配置则迁移过来。
     */
    private fun initDefaultAiImageTemplate() {
        Coroutine.async {
            val existing = appDb.aiImageTemplateDao.getDefault()
            if (existing == null) {
                // 读取旧版 SharedPreferences 配置（若有则迁移，否则用内置默认值）
                val prefs = appCtx.defaultSharedPreferences
                val oldUrl = prefs.getString("aiImageModelUrl", null)
                val oldName = prefs.getString("aiImageModelName", null)
                val oldKey = prefs.getString("aiImageModelKey", null)
                val oldSize = prefs.getString("aiImageSize", null)
                val oldStyle = prefs.getString("aiImageStyle", null)
                val oldPrompt = prefs.getString("aiImagePromptTemplate", null)
                val oldNegative = prefs.getString("aiImageNegativePrompt", null)

                val defaultTemplate = io.legado.app.data.entities.AiImageTemplate(
                    id = io.legado.app.model.AiImageGenerator.DEFAULT_TEMPLATE_ID,
                    name = "默认模板",
                    modelUrl = oldUrl ?: io.legado.app.model.AiImageGenerator.DEFAULT_MODEL_URL,
                    modelName = oldName ?: io.legado.app.model.AiImageGenerator.DEFAULT_MODEL_NAME,
                    modelKey = oldKey ?: "",
                    imageSize = oldSize ?: "1024x1024",
                    imageStyle = oldStyle ?: io.legado.app.model.AiImageGenerator.DEFAULT_STYLE_SUFFIX,
                    promptTemplate = oldPrompt ?: io.legado.app.model.AiImageGenerator.DEFAULT_PROMPT_TEMPLATE,
                    negativePrompt = oldNegative ?: io.legado.app.model.AiImageGenerator.DEFAULT_NEGATIVE_PROMPT,
                    isDefault = true
                )
                appDb.aiImageTemplateDao.insert(defaultTemplate)
                LogUtils.d("App", "已初始化默认AI生图模板")
            }
        }
    }

}
