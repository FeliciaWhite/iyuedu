package io.legado.app.help.config

import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit
import io.legado.app.BuildConfig
import io.legado.app.constant.AppConst
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.utils.GSON
import io.legado.app.utils.canvasrecorder.CanvasRecorderFactory
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefFloat
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefLong
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isNightMode
import io.legado.app.utils.parseIpsFromString
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefFloat
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefLong
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import io.legado.app.utils.sysConfiguration
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx
import java.io.File
import io.legado.app.utils.FileUtils
import java.net.InetAddress

@Suppress("MemberVisibilityCanBePrivate", "ConstPropertyName")
object AppConfig : SharedPreferences.OnSharedPreferenceChangeListener {

    // 迁移：部分 key 在旧版本中以 Int 形式存储，但对应 Preference（EditTextPreference）
    // 初始化时调用 getString() 会触发 ClassCastException 导致打开设置界面崩溃。
    // 在对象初始化时将这些残留 Int 值转为 String，确保后续读取安全。
    init {
        val sp = appCtx.defaultSharedPreferences
        val keysToMigrate = listOf(
            PreferKey.readAloudImageInterval,
            PreferKey.readAloudCoverWidth,
            PreferKey.readAloudCoverTopMargin
        )
        for (key in keysToMigrate) {
            val v = sp.all[key]
            if (v is Int) {
                sp.edit().remove(key).apply()
                sp.edit().putString(key, v.toString()).apply()
            }
        }
    }

    val isCronet = appCtx.getPrefBoolean(PreferKey.cronet)
    var useAntiAlias = appCtx.getPrefBoolean(PreferKey.antiAlias)
    var userAgent: String = getPrefUserAgent()
    var customHosts = appCtx.getPrefString(PreferKey.customHosts)
    var editTheme = appCtx.getPrefInt(PreferKey.editTheme, 0)
    var editThemeDark = appCtx.getPrefInt(PreferKey.editThemeDark, 0)
    var editTemeAuto = appCtx.getPrefBoolean(PreferKey.editTemeAuto)
    var isEInkMode = appCtx.getPrefString(PreferKey.themeMode) == "3"
    var clickActionTL = appCtx.getPrefInt(PreferKey.clickActionTL, 2)
    var clickActionTC = appCtx.getPrefInt(PreferKey.clickActionTC, 2)
    var clickActionTR = appCtx.getPrefInt(PreferKey.clickActionTR, 1)
    var clickActionML = appCtx.getPrefInt(PreferKey.clickActionML, 2)
    var clickActionMC = appCtx.getPrefInt(PreferKey.clickActionMC, 0)
    var clickActionMR = appCtx.getPrefInt(PreferKey.clickActionMR, 1)
    var clickActionBL = appCtx.getPrefInt(PreferKey.clickActionBL, 2)
    var clickActionBC = appCtx.getPrefInt(PreferKey.clickActionBC, 1)
    var clickActionBR = appCtx.getPrefInt(PreferKey.clickActionBR, 1)
    var themeMode = appCtx.getPrefString(PreferKey.themeMode, "0")
    var useDefaultCover = appCtx.getPrefBoolean(PreferKey.useDefaultCover, false)
    var optimizeRender = CanvasRecorderFactory.isSupport
            && appCtx.getPrefBoolean(PreferKey.optimizeRender, false)
    var recordLog = appCtx.getPrefBoolean(PreferKey.recordLog)
    var editFontScale = appCtx.getPrefInt(PreferKey.editFontScale, 16)
    var editNonPrintable = appCtx.getPrefInt(PreferKey.editNonPrintable, 0)
    var editAutoWrap = appCtx.getPrefBoolean(PreferKey.editAutoWrap, true)
    var editAutoComplete = appCtx.getPrefBoolean(PreferKey.editAutoComplete, true)
    var showBoardLine = appCtx.getPrefInt(PreferKey.showBoardLine, 1)
    var adaptSpecialStyle = appCtx.getPrefBoolean(PreferKey.adaptSpecialStyle, true)

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            PreferKey.editFontScale -> editFontScale = appCtx.getPrefInt(PreferKey.editFontScale, 16)
            PreferKey.editNonPrintable -> editNonPrintable = appCtx.getPrefInt(PreferKey.editNonPrintable, 0)
            PreferKey.editAutoWrap -> editAutoWrap = appCtx.getPrefBoolean(PreferKey.editAutoWrap, true)
            PreferKey.editAutoComplete -> editAutoComplete = appCtx.getPrefBoolean(PreferKey.editAutoComplete, true)
            PreferKey.showBoardLine -> showBoardLine = appCtx.getPrefInt(PreferKey.showBoardLine, 1)
            PreferKey.adaptSpecialStyle -> adaptSpecialStyle = appCtx.getPrefBoolean(PreferKey.adaptSpecialStyle, true)

            PreferKey.themeMode -> {
                themeMode = appCtx.getPrefString(PreferKey.themeMode, "0")
                isEInkMode = themeMode == "3"
            }

            PreferKey.clickActionTL -> clickActionTL =
                appCtx.getPrefInt(PreferKey.clickActionTL, 2)

            PreferKey.clickActionTC -> clickActionTC =
                appCtx.getPrefInt(PreferKey.clickActionTC, 2)

            PreferKey.clickActionTR -> clickActionTR =
                appCtx.getPrefInt(PreferKey.clickActionTR, 1)

            PreferKey.clickActionML -> clickActionML =
                appCtx.getPrefInt(PreferKey.clickActionML, 2)

            PreferKey.clickActionMC -> clickActionMC =
                appCtx.getPrefInt(PreferKey.clickActionMC, 0)

            PreferKey.clickActionMR -> clickActionMR =
                appCtx.getPrefInt(PreferKey.clickActionMR, 1)

            PreferKey.clickActionBL -> clickActionBL =
                appCtx.getPrefInt(PreferKey.clickActionBL, 2)

            PreferKey.clickActionBC -> clickActionBC =
                appCtx.getPrefInt(PreferKey.clickActionBC, 1)

            PreferKey.clickActionBR -> clickActionBR =
                appCtx.getPrefInt(PreferKey.clickActionBR, 1)

            PreferKey.readBodyToLh -> ReadBookConfig.readBodyToLh =
                appCtx.getPrefBoolean(PreferKey.readBodyToLh, true)

            PreferKey.useZhLayout -> ReadBookConfig.useZhLayout =
                appCtx.getPrefBoolean(PreferKey.useZhLayout)

            PreferKey.userAgent -> userAgent = getPrefUserAgent()

            PreferKey.customHosts -> {
                customHosts = appCtx.getPrefString(PreferKey.customHosts)
                _hostMap = null
                _addressCache = null
            }

            PreferKey.editTheme -> editTheme = appCtx.getPrefInt(PreferKey.editTheme, 0)

            PreferKey.editThemeDark -> editThemeDark = appCtx.getPrefInt(PreferKey.editThemeDark, 0)

            PreferKey.editTemeAuto -> editTemeAuto = appCtx.getPrefBoolean(PreferKey.editTemeAuto)

            PreferKey.antiAlias -> useAntiAlias = appCtx.getPrefBoolean(PreferKey.antiAlias)

            PreferKey.useDefaultCover -> useDefaultCover =
                appCtx.getPrefBoolean(PreferKey.useDefaultCover, false)

            PreferKey.optimizeRender -> optimizeRender = CanvasRecorderFactory.isSupport
                    && appCtx.getPrefBoolean(PreferKey.optimizeRender, false)

            PreferKey.recordLog -> recordLog = appCtx.getPrefBoolean(PreferKey.recordLog)

        }
    }

    //dns配置
    private var _hostMap: Map<String, Any?>? = null
    val hostMap: Map<String, Any?>
        get() = _hostMap ?: run {
            val cache = GSON.fromJsonObject<Map<String, Any?>>(customHosts).getOrNull() ?: emptyMap()
            _hostMap = cache
            cache
        }
    private var _addressCache: Map<String, List<InetAddress>>? = null
    val addressCache: Map<String, List<InetAddress>>
        get() = _addressCache ?: run {
            val cache = hostMap.mapNotNull { (host, ipValue) ->
                val addresses = when (ipValue) {
                    is String -> ipValue.parseIpsFromString()
                    is List<*> -> ipValue.parseIpsFromList()
                    else -> null
                }
                addresses?.let { host to it }
            }.toMap()
            _addressCache = cache
            cache
        }
    private fun List<*>.parseIpsFromList(): List<InetAddress> =
        mapNotNull { element ->
            (element as? String)?.trim()?.takeIf { it.isNotEmpty() }
                ?.runCatching { InetAddress.getByName(this) }
                ?.getOrNull()
        }

    var isNightTheme: Boolean
        get() = when (themeMode) {
            "1" -> false
            "2" -> true
            "3" -> false
            else -> sysConfiguration.isNightMode
        }
        set(value) {
            if (isNightTheme != value) {
                if (value) {
                    appCtx.putPrefString(PreferKey.themeMode, "2")
                } else {
                    appCtx.putPrefString(PreferKey.themeMode, "1")
                }
            }
        }
    var showBookname: Int
        get() = appCtx.getPrefInt(PreferKey.showBooknameLayout, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.showBooknameLayout, value)
        }
    var bookshelfMargin: Int
        get() = appCtx.getPrefInt(PreferKey.bookshelfMargin, 12)
        set(value) {
            appCtx.putPrefInt(PreferKey.bookshelfMargin, value)
        }

    var showUnread: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showUnread, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.showUnread, value)
        }

    var showLastUpdateTime: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showLastUpdateTime, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.showLastUpdateTime, value)
        }

    var showWaitUpCount: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showWaitUpCount, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.showWaitUpCount, value)
        }

    var readBrightness: Int
        get() = if (isNightTheme) {
            appCtx.getPrefInt(PreferKey.nightBrightness, 100)
        } else {
            appCtx.getPrefInt(PreferKey.brightness, 100)
        }
        set(value) {
            if (isNightTheme) {
                appCtx.putPrefInt(PreferKey.nightBrightness, value)
            } else {
                appCtx.putPrefInt(PreferKey.brightness, value)
            }
        }

    val textSelectAble: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.textSelectAble, true)

    val isTransparentStatusBar: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.transparentStatusBar, true)

    val immNavigationBar: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.immNavigationBar, true)

    val screenOrientation: String?
        get() = appCtx.getPrefString(PreferKey.screenOrientation)

    var bookGroupStyle: Int
        get() = appCtx.getPrefInt(PreferKey.bookGroupStyle, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.bookGroupStyle, value)
        }

    var bookshelfLayout: Int
        get() = appCtx.getPrefInt(PreferKey.bookshelfLayout, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.bookshelfLayout, value)
        }

    var saveTabPosition: Int
        get() = appCtx.getPrefInt(PreferKey.saveTabPosition, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.saveTabPosition, value)
        }

    var bookExportFileName: String?
        get() = appCtx.getPrefString(PreferKey.bookExportFileName)
        set(value) {
            appCtx.putPrefString(PreferKey.bookExportFileName, value)
        }

    var episodeExportFileName: String?
        get() = appCtx.getPrefString(PreferKey.episodeExportFileName, "")
        set(value) {
            appCtx.putPrefString(PreferKey.episodeExportFileName, value)
        }

    var bookImportFileName: String?
        get() = appCtx.getPrefString(PreferKey.bookImportFileName)
        set(value) {
            appCtx.putPrefString(PreferKey.bookImportFileName, value)
        }

    var backupPath: String?
        get() = appCtx.getPrefString(PreferKey.backupPath)
        set(value) {
            if (value.isNullOrEmpty()) {
                appCtx.removePref(PreferKey.backupPath)
            } else {
                appCtx.putPrefString(PreferKey.backupPath, value)
            }
        }

    var defaultBookTreeUri: String?
        get() = appCtx.getPrefString(PreferKey.defaultBookTreeUri)
        set(value) {
            if (value.isNullOrEmpty()) {
                appCtx.removePref(PreferKey.defaultBookTreeUri)
            } else {
                appCtx.putPrefString(PreferKey.defaultBookTreeUri, value)
            }
        }

    val showDiscovery: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showDiscovery, true)

    val showRSS: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showRss, true)

    val autoRefreshBook: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.autoRefresh)

    val onlyUpdateRead: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.onlyUpdateRead)

    var enableReview: Boolean
        get() = BuildConfig.DEBUG && appCtx.getPrefBoolean(PreferKey.enableReview, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.enableReview, value)
        }

    var threadCount: Int
        get() = appCtx.getPrefInt(PreferKey.threadCount, 16)
        set(value) {
            appCtx.putPrefInt(PreferKey.threadCount, value)
        }

    var remoteServerId: Long
        get() = appCtx.getPrefLong(PreferKey.remoteServerId)
        set(value) {
            appCtx.putPrefLong(PreferKey.remoteServerId, value)
        }

    var importBookPath: String?
        get() = appCtx.getPrefString("importBookPath")
        set(value) {
            if (value == null) {
                appCtx.removePref("importBookPath")
            } else {
                appCtx.putPrefString("importBookPath", value)
            }
        }

    var ttsFlowSys: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.ttsFollowSys, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.ttsFollowSys, value)
        }

    /**
     * TTS 音频缓存开关
     * 启用后会在朗读时保存音频文件到缓存目录
     */
    var ttsCacheEnabled: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.ttsCacheEnabled, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.ttsCacheEnabled, value)
        }

    val noAnimScrollPage: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.noAnimScrollPage, false)

    const val defaultSpeechRate = 5

    var ttsSpeechRate: Int
        get() = appCtx.getPrefInt(PreferKey.ttsSpeechRate, defaultSpeechRate)
        set(value) {
            appCtx.putPrefInt(PreferKey.ttsSpeechRate, value)
        }

    var ttsTimer: Int
        get() = appCtx.getPrefInt(PreferKey.ttsTimer, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.ttsTimer, value)
        }

    val speechRatePlay: Int get() = if (ttsFlowSys) defaultSpeechRate else ttsSpeechRate

    var chineseConverterType: Int
        get() = appCtx.getPrefInt(PreferKey.chineseConverterType)
        set(value) {
            appCtx.putPrefInt(PreferKey.chineseConverterType, value)
        }

    var systemTypefaces: Int
        get() = appCtx.getPrefInt(PreferKey.systemTypefaces)
        set(value) {
            appCtx.putPrefInt(PreferKey.systemTypefaces, value)
        }

    var elevation: Int
        get() = if (isEInkMode) 0 else appCtx.getPrefInt(
            PreferKey.barElevation,
            AppConst.sysElevation
        )
        set(value) {
            appCtx.putPrefInt(PreferKey.barElevation, value)
        }

    var readUrlInBrowser: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.readUrlOpenInBrowser)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.readUrlOpenInBrowser, value)
        }

    var exportCharset: String
        get() {
            val c = appCtx.getPrefString(PreferKey.exportCharset)
            if (c.isNullOrBlank()) {
                return "UTF-8"
            }
            return c
        }
        set(value) {
            appCtx.putPrefString(PreferKey.exportCharset, value)
        }

    var exportUseReplace: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.exportUseReplace, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.exportUseReplace, value)
        }

    var exportToWebDav: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.exportToWebDav)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.exportToWebDav, value)
        }
    var exportNoChapterName: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.exportNoChapterName)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.exportNoChapterName, value)
        }

    var enableCustomExport: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.enableCustomExport, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.enableCustomExport, value)
        }

    var exportType: Int
        get() = appCtx.getPrefInt(PreferKey.exportType)
        set(value) {
            appCtx.putPrefInt(PreferKey.exportType, value)
        }
    var exportPictureFile: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.exportPictureFile, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.exportPictureFile, value)
        }

    var parallelExportBook: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.parallelExportBook, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.parallelExportBook, value)
        }

    var exportAddAnnotation: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.exportAddAnnotation, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.exportAddAnnotation, value)
        }

    var changeSourceCheckAuthor: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.changeSourceCheckAuthor)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.changeSourceCheckAuthor, value)
        }

    var ttsEngine: String?
        get() = appCtx.getPrefString(PreferKey.ttsEngine)
        set(value) {
            appCtx.putPrefString(PreferKey.ttsEngine, value)
        }

    /**
     * 系统TTS引擎包名
     * 用于系统TTS模式下的定时激活和前台保活
     */
    var sysTtsPackageName: String?
        get() = appCtx.getPrefString(PreferKey.sysTtsPackageName)
        set(value) {
            appCtx.putPrefString(PreferKey.sysTtsPackageName, value)
        }

    var webPort: Int
        get() = appCtx.getPrefInt(PreferKey.webPort, 1122)
        set(value) {
            appCtx.putPrefInt(PreferKey.webPort, value)
        }

    var tocUiUseReplace: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.tocUiUseReplace)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.tocUiUseReplace, value)
        }

    var tocCountWords: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.tocCountWords, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.tocCountWords, value)
        }

    var enableReadRecord: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.enableReadRecord, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.enableReadRecord, value)
        }

    val autoChangeSource: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.autoChangeSource, true)

    var changeSourceLoadInfo: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.changeSourceLoadInfo)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.changeSourceLoadInfo, value)
        }

    var changeSourceLoadToc: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.changeSourceLoadToc)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.changeSourceLoadToc, value)
        }

    var changeSourceLoadWordCount: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.changeSourceLoadWordCount)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.changeSourceLoadWordCount, value)
        }

    var openBookInfoByClickTitle: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.openBookInfoByClickTitle, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.openBookInfoByClickTitle, value)
        }

    var showBookshelfFastScroller: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showBookshelfFastScroller, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.showBookshelfFastScroller, value)
        }

    var contentSelectSpeakMod: Int
        get() = appCtx.getPrefInt(PreferKey.contentSelectSpeakMod)
        set(value) {
            appCtx.putPrefInt(PreferKey.contentSelectSpeakMod, value)
        }

    var batchChangeSourceDelay: Int
        get() = appCtx.getPrefInt(PreferKey.batchChangeSourceDelay)
        set(value) {
            appCtx.putPrefInt(PreferKey.batchChangeSourceDelay, value)
        }

    val importKeepName get() = appCtx.getPrefBoolean(PreferKey.importKeepName)
    val importKeepGroup get() = appCtx.getPrefBoolean(PreferKey.importKeepGroup)
    var importKeepEnable: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.importKeepEnable, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.importKeepEnable, value)
        }
    var importShowComment: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.importShowComment, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.importShowComment, value)
        }

    var previewImageByClick: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.previewImageByClick, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.previewImageByClick, value)
        }

    val clickImgWay: String?
        get() = appCtx.getPrefString(PreferKey.clickImgWay)

    var preDownloadNum
        get() = appCtx.getPrefInt(PreferKey.preDownloadNum, 10)
        set(value) {
            appCtx.putPrefInt(PreferKey.preDownloadNum, value)
        }

    val syncBookProgress get() = appCtx.getPrefBoolean(PreferKey.syncBookProgress, true)

    val syncBookProgressPlus get() = appCtx.getPrefBoolean(PreferKey.syncBookProgressPlus, false)

    val mediaButtonOnExit get() = appCtx.getPrefBoolean("mediaButtonOnExit", true)

    val readAloudByMediaButton
        get() = appCtx.getPrefBoolean(PreferKey.readAloudByMediaButton, false)

    val replaceEnableDefault get() = appCtx.getPrefBoolean(PreferKey.replaceEnableDefault, true)

    val webDavDir get() = appCtx.getPrefString(PreferKey.webDavDir, "legado")

    val webDavDeviceName get() = appCtx.getPrefString(PreferKey.webDavDeviceName, Build.MODEL)

    val recordHeapDump get() = appCtx.getPrefBoolean(PreferKey.recordHeapDump, false)

    val loadCoverOnlyWifi get() = appCtx.getPrefBoolean(PreferKey.loadCoverOnlyWifi, false)

    val showAddToShelfAlert get() = appCtx.getPrefBoolean(PreferKey.showAddToShelfAlert, true)

    val ignoreAudioFocus get() = appCtx.getPrefBoolean(PreferKey.ignoreAudioFocus, false)

    var pauseReadAloudWhilePhoneCalls
        get() = appCtx.getPrefBoolean(PreferKey.pauseReadAloudWhilePhoneCalls, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.pauseReadAloudWhilePhoneCalls, value)

    val onlyLatestBackup get() = appCtx.getPrefBoolean(PreferKey.onlyLatestBackup, true)

    val autoCheckNewBackup get() = appCtx.getPrefBoolean(PreferKey.autoCheckNewBackup, true)

    val defaultHomePage get() = appCtx.getPrefString(PreferKey.defaultHomePage, "bookshelf")

    val updateToVariant get() = appCtx.getPrefString(PreferKey.updateToVariant, "default_version")

    val streamReadAloudAudio get() = appCtx.getPrefBoolean(PreferKey.streamReadAloudAudio, false)

    val doublePageHorizontal: String?
        get() = appCtx.getPrefString(PreferKey.doublePageHorizontal)

    val progressBarBehavior: String?
        get() = appCtx.getPrefString(PreferKey.progressBarBehavior, "page")

    val keyPageOnLongPress
        get() = appCtx.getPrefBoolean(PreferKey.keyPageOnLongPress, false)

    val volumeKeyPage
        get() = appCtx.getPrefBoolean(PreferKey.volumeKeyPage, true)

    val volumeKeyPageOnPlay
        get() = appCtx.getPrefBoolean(PreferKey.volumeKeyPageOnPlay, true)

    val mouseWheelPage
        get() = appCtx.getPrefBoolean(PreferKey.mouseWheelPage, true)

    val paddingDisplayCutouts
        get() = appCtx.getPrefBoolean(PreferKey.paddingDisplayCutouts, false)

    var searchScope: String
        get() = appCtx.getPrefString("searchScope") ?: ""
        set(value) {
            appCtx.putPrefString("searchScope", value)
        }

    var searchGroup: String
        get() = appCtx.getPrefString("searchGroup") ?: ""
        set(value) {
            appCtx.putPrefString("searchGroup", value)
        }

    var pageTouchSlop: Int
        get() = appCtx.getPrefInt(PreferKey.pageTouchSlop, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.pageTouchSlop, value)
        }

    var pageTouchClick: Int
        get() = appCtx.getPrefInt(PreferKey.pageTouchClick, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.pageTouchClick, value)
        }

    var bookshelfSort: Int
        get() = appCtx.getPrefInt(PreferKey.bookshelfSort, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.bookshelfSort, value)
        }

    fun getBookSortByGroupId(groupId: Long): Int {
        return appDb.bookGroupDao.getByID(groupId)?.getRealBookSort()
            ?: bookshelfSort
    }

    private fun getPrefUserAgent(): String {
        val ua = appCtx.getPrefString(PreferKey.userAgent)
        if (ua.isNullOrBlank()) {
            return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + BuildConfig.Cronet_Main_Version + " Safari/537.36"
        }
        return ua
    }

    var bitmapCacheSize: Int
        get() = appCtx.getPrefInt(PreferKey.bitmapCacheSize, 50)
        set(value) {
            appCtx.putPrefInt(PreferKey.bitmapCacheSize, value)
        }

    var imageRetainNum: Int
        get() = appCtx.getPrefInt(PreferKey.imageRetainNum, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.imageRetainNum, value)
        }

    var showReadTitleBarAddition: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showReadTitleAddition, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.showReadTitleAddition, value)
        }
    var readBarStyleFollowPage: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.readBarStyleFollowPage, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.readBarStyleFollowPage, value)
        }

    var sourceEditMaxLine: Int
        get() {
            val maxLine = appCtx.getPrefInt(PreferKey.sourceEditMaxLine, Int.MAX_VALUE)
            if (maxLine < 10) {
                return Int.MAX_VALUE
            }
            return maxLine
        }
        set(value) {
            appCtx.putPrefInt(PreferKey.sourceEditMaxLine, value)
        }

    var audioPlayUseWakeLock: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.audioPlayWakeLock)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.audioPlayWakeLock, value)
        }

    var brightnessVwPos: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.brightnessVwPos)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.brightnessVwPos, value)
        }

    fun detectClickArea() {
        if (clickActionTL * clickActionTC * clickActionTR
            * clickActionML * clickActionMC * clickActionMR
            * clickActionBL * clickActionBC * clickActionBR != 0
        ) {
            appCtx.putPrefInt(PreferKey.clickActionMC, 0)
            appCtx.toastOnUi("当前没有配置菜单区域,自动恢复中间区域为菜单.")
        }
    }

    val showMangaUi: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showMangaUi, true)

    var disableMangaScale: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.disableMangaScale, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.disableMangaScale, value)
        }

    var disableMangaPageAnim: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.disableMangaPageAnim, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.disableMangaPageAnim, value)
        }

    var mangaPreDownloadNum
        get() = appCtx.getPrefInt(PreferKey.mangaPreDownloadNum, 10)
        set(value) {
            appCtx.putPrefInt(PreferKey.mangaPreDownloadNum, value)
        }

    var disableClickScroll
        get() = appCtx.getPrefBoolean(PreferKey.disableClickScroll, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.disableClickScroll, value)
        }

    var mangaAutoPageSpeed
        get() = appCtx.getPrefInt(PreferKey.mangaAutoPageSpeed, 3)
        set(value) {
            appCtx.putPrefInt(PreferKey.mangaAutoPageSpeed, value)
        }

    var mangaFooterConfig
        get() = appCtx.getPrefString(PreferKey.mangaFooterConfig, "")
        set(value) {
            appCtx.putPrefString(PreferKey.mangaFooterConfig, value)
        }

    var enableMangaHorizontalScroll
        get() = appCtx.getPrefBoolean(PreferKey.enableMangaHorizontalScroll, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.enableMangaHorizontalScroll, value)
        }

    var mangaColorFilter
        get() = appCtx.getPrefString(PreferKey.mangaColorFilter, "")
        set(value) {
            appCtx.putPrefString(PreferKey.mangaColorFilter, value)
        }

    var hideMangaTitle
        get() = appCtx.getPrefBoolean(PreferKey.hideMangaTitle, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.hideMangaTitle, value)
        }

    var enableMangaEInk
        get() = appCtx.getPrefBoolean(PreferKey.enableMangaEInk, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.enableMangaEInk, value)
        }

    var mangaEInkThreshold
        get() = appCtx.getPrefInt(PreferKey.mangaEInkThreshold, 150)
        set(value) {
            appCtx.putPrefInt(PreferKey.mangaEInkThreshold, value)
        }

    var disableHorizontalPageSnap
        get() = appCtx.getPrefBoolean(PreferKey.disableHorizontalPageSnap, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.disableHorizontalPageSnap, value)
        }

    var enableMangaGray
        get() = appCtx.getPrefBoolean(PreferKey.enableMangaGray, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.enableMangaGray, value)
        }

    var welcomeImage
        get() = appCtx.getPrefString(PreferKey.welcomeImage)
        set(value) {
            appCtx.putPrefString(PreferKey.welcomeImage, value)
        }

    var welcomeShowText
        get() = appCtx.getPrefBoolean(PreferKey.welcomeShowText, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.welcomeShowText, value)
        }

    var welcomeShowIcon
        get() = appCtx.getPrefBoolean(PreferKey.welcomeShowIcon, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.welcomeShowIcon, value)
        }

    var welcomeImageDark
        get() = appCtx.getPrefString(PreferKey.welcomeImageDark)
        set(value) {
            appCtx.putPrefString(PreferKey.welcomeImageDark, value)
        }

    var welcomeShowTextDark
        get() = appCtx.getPrefBoolean(PreferKey.welcomeShowTextDark, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.welcomeShowTextDark, value)
        }

    var welcomeShowIconDark
        get() = appCtx.getPrefBoolean(PreferKey.welcomeShowIconDark, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.welcomeShowIconDark, value)
        }

    // ================= 自定义功能区域 Start =================

    // 1. 听书预加载数量
    val audioPreDownloadNum: Int
        get() {
            val str = appCtx.getPrefString("audioPreDownloadNum")
            return str?.toIntOrNull() ?: 1
        }

    // 2. 音频缓存保留时间 (返回毫秒)
    val audioCacheCleanTime: Long
        get() {
            val str = appCtx.getPrefString("audioCacheCleanTime")
            val minutes = str?.toLongOrNull() ?: 10L
            return minutes * 60 * 1000L
        }

    // 3. 朗读标题开关 (修复预缓存 bug 用)
    var readAloudTitle: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.readAloudTitle, true)
        set(value) = appCtx.putPrefBoolean(PreferKey.readAloudTitle, value)

    // 4. 朗读完章节自动合并音频
    var autoMergeAudioOnChapterEnd: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.autoMergeAudioOnChapterEnd, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.autoMergeAudioOnChapterEnd, value)

    // 5. 合并后转换为MP3 (64kbps)
    var convertToMp3AfterMerge: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.convertToMp3AfterMerge, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.convertToMp3AfterMerge, value)

    // 6. 合并时保存文本
    var saveTextWithMerge: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.saveTextWithMerge, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.saveTextWithMerge, value)

    // 6.1 合并时保存为视频（复用已保存的AI图片，静态帧+烧入字幕，只保留 MP4）
    var saveVideoWithMerge: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.saveVideoWithMerge, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.saveVideoWithMerge, value)

    // 7. 朗读缓存音频统一转换为 WAV (24000Hz, 16bit, 单声道)
    var convertCacheToWav: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.convertCacheToWav, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.convertCacheToWav, value)

    // 7.1 转换为 WAV 时的音量增益倍数（默认 1.0，最大 3.0，超过 3.0 自动截断为 3.0）
    // 注意：EditTextPreference 存储的是 String，不能用 getFloat/putFloat
    var convertCacheToWavGain: Float
        get() = appCtx.getPrefString(PreferKey.convertCacheToWavGain, "1.0")
            ?.toFloatOrNull()?.coerceIn(0f, 3f) ?: 1.0f
        set(value) = appCtx.putPrefString(PreferKey.convertCacheToWavGain, value.toString())

    // 朗读播放时的音量增益倍数（默认 1.0，范围 0.5~5.0，超过自动截断为 5.0）
    var readAloudVolumeGain: Float
        get() = appCtx.getPrefString(PreferKey.readAloudVolumeGain, "1.0")
            ?.toFloatOrNull()?.coerceIn(0.5f, 5.0f) ?: 1.0f
        set(value) = appCtx.putPrefString(
            PreferKey.readAloudVolumeGain,
            value.coerceIn(0.5f, 5.0f).toString()
        )

    // 6.1 超短音频判定阈值（秒），设为0表示不限制
    // 注意：EditTextPreference 存储的是 String，不能用 getFloat/putFloat
    var shortAudioMinDuration: Float
        get() = appCtx.getPrefString(PreferKey.shortAudioMinDuration, "0.3")?.toFloatOrNull() ?: 0.3f
        set(value) = appCtx.putPrefString(PreferKey.shortAudioMinDuration, value.toString())

    // 7. 字幕每行最大中文字符数
    var srtSubtitleMaxChars: Int
        get() = appCtx.defaultSharedPreferences.getString(PreferKey.srtSubtitleMaxChars, "15")?.toIntOrNull() ?: 15
        set(value) = appCtx.defaultSharedPreferences.edit().putString(PreferKey.srtSubtitleMaxChars, value.toString()).apply()

    // 8. 字幕时间偏移量（秒，支持负数）
    var srtSubtitleTimeOffset: Float
        get() = appCtx.defaultSharedPreferences.getString(PreferKey.srtSubtitleTimeOffset, "0")?.toFloatOrNull() ?: 0f
        set(value) = appCtx.defaultSharedPreferences.edit().putString(PreferKey.srtSubtitleTimeOffset, value.toString()).apply()

    // 9. 静音匹配范围（秒）- 字幕时间对齐到静音位置的范围
    var srtSilenceMatchRange: Float
        get() = appCtx.defaultSharedPreferences.getString(PreferKey.srtSilenceMatchRange, "0.5")?.toFloatOrNull() ?: 0.5f
        set(value) = appCtx.defaultSharedPreferences.edit().putString(PreferKey.srtSilenceMatchRange, value.toString()).apply()

    // 9.1 保存为视频时的字幕样式
    // 字幕字号相对默认大小的比例，1.3 = 当前默认大小
    var videoSubtitleFontSizeScale: Float
        get() = appCtx.defaultSharedPreferences.getString(PreferKey.videoSubtitleFontSizeScale, "1.3")?.toFloatOrNull() ?: 1.3f
        set(value) = appCtx.defaultSharedPreferences.edit().putString(PreferKey.videoSubtitleFontSizeScale, value.toString()).apply()

    // 字幕垂直偏移（画面高度比例），正数上移，负数下移，默认 0
    var videoSubtitleVOffset: Float
        get() = appCtx.defaultSharedPreferences.getString(PreferKey.videoSubtitleVOffset, "0")?.toFloatOrNull() ?: 0f
        set(value) = appCtx.defaultSharedPreferences.edit().putString(PreferKey.videoSubtitleVOffset, value.toString()).apply()

    // 字幕是否按句（标点）切割，默认 false（整段作为一条字幕）
    var videoSubtitleSplit: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.videoSubtitleSplit, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.videoSubtitleSplit, value)

    // 字幕填充色（十六进制，如 #FF000000），默认金黄 #FFFFDD00
    var videoSubtitleFontColor: String
        get() = appCtx.defaultSharedPreferences.getString(PreferKey.videoSubtitleFontColor, "#FFFFDD00") ?: "#FFFFDD00"
        set(value) = appCtx.defaultSharedPreferences.edit().putString(PreferKey.videoSubtitleFontColor, value).apply()

    // 描边粗细（相对字号的比例），默认 0.12（描边宽 = 字号 × 0.12）
    var videoSubtitleStrokeWidth: Float
        get() = appCtx.defaultSharedPreferences.getString(PreferKey.videoSubtitleStrokeWidth, "0.12")?.toFloatOrNull() ?: 0.12f
        set(value) = appCtx.defaultSharedPreferences.edit().putString(PreferKey.videoSubtitleStrokeWidth, value.toString()).apply()

    // 描边颜色（十六进制，如 #FFFFFFFF），默认黑色 #FF000000
    var videoSubtitleStrokeColor: String
        get() = appCtx.defaultSharedPreferences.getString(PreferKey.videoSubtitleStrokeColor, "#FF000000") ?: "#FF000000"
        set(value) = appCtx.defaultSharedPreferences.edit().putString(PreferKey.videoSubtitleStrokeColor, value).apply()

    // 10. 朗读对话框显示封面/字幕/进度条
    var showReadAloudCoverSubtitle: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showReadAloudCoverSubtitle, true)
        set(value) = appCtx.putPrefBoolean(PreferKey.showReadAloudCoverSubtitle, value)

    // 朗读界面是否隐藏当前章节进度条，默认 true 隐藏（开启即去掉进度条）
    var readAloudHideChapterProgress: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.readAloudHideChapterProgress, true)
        set(value) = appCtx.putPrefBoolean(PreferKey.readAloudHideChapterProgress, value)

    // 朗读界面小图（封面/本地图）与顶部的距离，单位 dp，默认 16
    // 注意：必须用 String 存储，因为 EditTextPreference 底层调用 getString()
    var readAloudCoverTopMargin: Int
        get() = try {
            appCtx.defaultSharedPreferences.getString(PreferKey.readAloudCoverTopMargin, "16")
                ?.toIntOrNull() ?: 16
        } catch (e: ClassCastException) {
            // 兼容旧版本误存为 Int 的情况
            appCtx.getPrefInt(PreferKey.readAloudCoverTopMargin, 16)
        }
        set(value) = appCtx.defaultSharedPreferences.edit()
            .putString(PreferKey.readAloudCoverTopMargin, value.toString()).apply()

    // 11. 朗读对话框封面宽度（dp），默认240，高度按340/240比例自动匹配
    // 注意：必须用 String 存储，因为 EditTextPreference 底层调用 getString()
    var readAloudCoverWidth: Int
        get() = try {
            appCtx.defaultSharedPreferences.getString(PreferKey.readAloudCoverWidth, "240")
                ?.toIntOrNull() ?: 240
        } catch (e: ClassCastException) {
            // 兼容旧版存的是 int 的情况
            val old = appCtx.defaultSharedPreferences.getInt(PreferKey.readAloudCoverWidth, 240)
            appCtx.defaultSharedPreferences.edit()
                .putString(PreferKey.readAloudCoverWidth, old.toString()).apply()
            old
        }
        set(value) = appCtx.defaultSharedPreferences.edit()
            .putString(PreferKey.readAloudCoverWidth, value.toString()).apply()

    /**
     * 手动清理 TTS 缓存
     * 同时清理转发器缓存和系统TTS缓存
     */
    fun clearTtsCache() {
        val baseDir = appCtx.getExternalFilesDir(null) ?: appCtx.filesDir
        FileUtils.delete(baseDir.absolutePath + File.separator + "httpTTS")
        FileUtils.delete(baseDir.absolutePath + File.separator + "httpTTS_cache")
        // 清理系统TTS缓存（与 TTSReadAloudService 的 ttsFolderPath 保持一致）
        val sysTtsCacheDir = File(appCtx.externalCacheDir, "systemTTS")
        if (sysTtsCacheDir.exists()) {
            FileUtils.listDirsAndFiles(sysTtsCacheDir.absolutePath)?.forEach {
                FileUtils.delete(it.absolutePath)
            }
        }
    }

    /**
     * 删除当前正在阅读/朗读章节的缓存音频（HttpTTS + 系统TTS）。
     * @return 删除的文件数量；若无法定位当前章节则返回 -1
     */
    fun deleteCurrentChapterCache(): Int {
        val book = io.legado.app.model.ReadBook.book ?: return -1
        val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, io.legado.app.model.ReadBook.durChapterIndex)
            ?: return -1
        return io.legado.app.service.HttpTtsAudioCache.deleteBookChapterCache(book, chapter)
    }

    // 系统TTS单句合成超时（秒），默认120秒
    var sysTtsSynthesizeTimeout: Int
        get() = try {
            appCtx.defaultSharedPreferences.getString(PreferKey.sysTtsSynthesizeTimeout, "120")
                ?.toIntOrNull()?.coerceAtLeast(5)?.coerceAtMost(300) ?: 120
        } catch (e: ClassCastException) {
            val old = appCtx.defaultSharedPreferences.getInt(PreferKey.sysTtsSynthesizeTimeout, 120)
            appCtx.defaultSharedPreferences.edit()
                .putString(PreferKey.sysTtsSynthesizeTimeout, old.toString()).apply()
            old
        }
        set(value) = appCtx.defaultSharedPreferences.edit()
            .putString(PreferKey.sysTtsSynthesizeTimeout, value.toString()).apply()

    // TTS合成失败重试次数，默认3次
    var ttsRetryCount: Int
        get() = try {
            appCtx.defaultSharedPreferences.getString(PreferKey.ttsRetryCount, "3")
                ?.toIntOrNull()?.coerceAtLeast(1)?.coerceAtMost(10) ?: 3
        } catch (e: ClassCastException) {
            val old = appCtx.defaultSharedPreferences.getInt(PreferKey.ttsRetryCount, 3)
            appCtx.defaultSharedPreferences.edit()
                .putString(PreferKey.ttsRetryCount, old.toString()).apply()
            old
        }
        set(value) = appCtx.defaultSharedPreferences.edit()
            .putString(PreferKey.ttsRetryCount, value.toString()).apply()

    // TTS合成失败超过最大次数后是否跳过（静音代替），默认开启
    var ttsRetrySkipOnFail: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.ttsRetrySkipOnFail, true)
        set(value) = appCtx.putPrefBoolean(PreferKey.ttsRetrySkipOnFail, value)

    /**
     * 实时更新音频开关。
     * 开启后：朗读中检测到 gengxin.json 信号时，停止朗读 → 删除所有缓存 → 重新开始朗读。
     * 默认关闭。
     */
    var gengxinRealtimeUpdate: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.gengxinRealtimeUpdate, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.gengxinRealtimeUpdate, value)

    // --- 背景音乐 (BGM) 配置 ---
    
    var isBgmEnabled: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.bgmEnabled, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.bgmEnabled, value)

    var soundEffectMode: String
        get() = appCtx.getPrefString(PreferKey.soundEffectMode, "off") ?: "off"
        set(value) = appCtx.putPrefString(PreferKey.soundEffectMode, value)

    /**
     * 当前书籍音效模式。值为 follow / off / normal / all。
     * - follow：跟随全局 [soundEffectMode]
     * - 其它：仅对当前书生效，覆盖全局设置
     */
    fun getBookSoundEffectMode(bookUrl: String): String {
        if (bookUrl.isBlank()) return "follow"
        return appCtx.getPrefString(PreferKey.bookSoundEffectModePrefix + bookUrl, "follow") ?: "follow"
    }

    fun setBookSoundEffectMode(bookUrl: String, mode: String) {
        if (bookUrl.isBlank()) return
        appCtx.putPrefString(PreferKey.bookSoundEffectModePrefix + bookUrl, mode)
    }

    /**
     * 获取当前书籍实际生效的音效模式：
     * 若书籍配置为 follow 则用全局 [soundEffectMode]，否则用书籍专属配置。
     */
    fun getEffectiveSoundEffectMode(bookUrl: String): String {
        val bookMode = getBookSoundEffectMode(bookUrl)
        return if (bookMode == "follow") soundEffectMode else bookMode
    }

    var soundEffectOffsetChars: Int
        get() = appCtx.getPrefInt(PreferKey.soundEffectOffsetChars, 5)
        set(value) = appCtx.putPrefInt(PreferKey.soundEffectOffsetChars, value)

    var soundEffectVolume: Int
        get() = appCtx.getPrefInt(PreferKey.soundEffectVolume, 80)
        set(value) = appCtx.putPrefInt(PreferKey.soundEffectVolume, value)

    /** 合并章节音频时是否将音效混音进完整 WAV */
    var mixSoundEffectOnMerge: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.mixSoundEffectOnMerge, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.mixSoundEffectOnMerge, value)

    var bgmPath: String?
        get() = appCtx.getPrefString(PreferKey.bgmPath)
        set(value) = appCtx.putPrefString(PreferKey.bgmPath, value)

    var bgmVolume: Int
        get() = appCtx.getPrefInt(PreferKey.bgmVolume, 30)
        set(value) = appCtx.putPrefInt(PreferKey.bgmVolume, value)

    var bgmAIEnabled: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.bgmAIEnabled, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.bgmAIEnabled, value)

    var bgmAICharInterval: Int
        get() = appCtx.getPrefInt(PreferKey.bgmAICharInterval, 350)
        set(value) = appCtx.putPrefInt(PreferKey.bgmAICharInterval, value)

    /**
     * 下一段上一段朗读：开启后开始朗读时从第一段开始
     */
    var readAloudStartFromFirst: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.readAloudStartFromFirst, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.readAloudStartFromFirst, value)

    /**
     * 系统TTS当前使用的voice名称
     * 用于生成/匹配系统TTS缓存文件名
     */
    var sysTtsVoiceName: String
        get() = appCtx.getPrefString(PreferKey.sysTtsVoiceName, "default") ?: "default"
        set(value) = appCtx.putPrefString(PreferKey.sysTtsVoiceName, value)

    // 12. 朗读封面跑马灯边框开关
    var readAloudCoverMarqueeEnabled: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.readAloudCoverMarqueeEnabled, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.readAloudCoverMarqueeEnabled, value)

    // 13. 朗读封面跑马灯边框速度（毫秒），默认3000，值越小越快
    // 注意：必须用 String 存储，因为 EditTextPreference 底层调用 getString()
    var readAloudCoverMarqueeSpeed: Int
        get() = try {
            appCtx.defaultSharedPreferences.getString(PreferKey.readAloudCoverMarqueeSpeed, "3000")
                ?.toIntOrNull() ?: 3000
        } catch (e: ClassCastException) {
            // 兼容旧版存的是 int 的情况
            val old = appCtx.defaultSharedPreferences.getInt(PreferKey.readAloudCoverMarqueeSpeed, 3000)
            appCtx.defaultSharedPreferences.edit()
                .putString(PreferKey.readAloudCoverMarqueeSpeed, old.toString()).apply()
            old
        }
        set(value) = appCtx.defaultSharedPreferences.edit()
            .putString(PreferKey.readAloudCoverMarqueeSpeed, value.toString()).apply()

    // 14. 跑马灯与BGM联动开关
    var marqueeBgmLinkEnabled: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.marqueeBgmLinkEnabled, true)
        set(value) = appCtx.putPrefBoolean(PreferKey.marqueeBgmLinkEnabled, value)

    // 15. 悬浮窗贴边距离偏移（dp），默认0贴边，正值往内缩，负数超出屏幕
    var readAloudFloatEdgeOffset: Int
        get() = try {
            appCtx.defaultSharedPreferences.getString(PreferKey.readAloudFloatEdgeOffset, "0")
                ?.toIntOrNull() ?: 0
        } catch (e: ClassCastException) {
            val old = appCtx.defaultSharedPreferences.getInt(PreferKey.readAloudFloatEdgeOffset, 0)
            appCtx.defaultSharedPreferences.edit()
                .putString(PreferKey.readAloudFloatEdgeOffset, old.toString()).apply()
            old
        }
        set(value) = appCtx.defaultSharedPreferences.edit()
            .putString(PreferKey.readAloudFloatEdgeOffset, value.toString()).apply()

    // 16. 角色标注显示开关
    var showRoleAnnotation: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showRoleAnnotation, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.showRoleAnnotation, value)

    // 17. 角色标注上下偏移量（dp），默认0。正数向上偏移，负数向下偏移
    // 注意：必须用 String 存储，因为 EditTextPreference 底层调用 getString()
    var roleAnnotationOffset: Int
        get() = try {
            appCtx.defaultSharedPreferences.getString(PreferKey.roleAnnotationOffset, "0")
                ?.toIntOrNull() ?: 0
        } catch (e: ClassCastException) {
            val old = appCtx.defaultSharedPreferences.getInt(PreferKey.roleAnnotationOffset, 0)
            appCtx.defaultSharedPreferences.edit()
                .putString(PreferKey.roleAnnotationOffset, old.toString()).apply()
            old
        }
        set(value) = appCtx.defaultSharedPreferences.edit()
            .putString(PreferKey.roleAnnotationOffset, value.toString()).apply()

    // 18. 音频缓存标记显示开关
    var showAudioCacheIndicator: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showAudioCacheIndicator, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.showAudioCacheIndicator, value)

    // 19. AI 生图开关（每本书单独设置，默认关闭；无打开书籍时读为 false、写入无效）
    // 存储 key 为 aiImageEnablePrefix + bookUrl，使该开关仅对当前小说生效。
    var readAloudAiImage: Boolean
        get() {
            val bookUrl = io.legado.app.model.ReadBook.book?.bookUrl
            if (bookUrl.isNullOrBlank()) return false
            return appCtx.getPrefBoolean(PreferKey.aiImageEnablePrefix + bookUrl, false)
        }
        set(value) {
            val bookUrl = io.legado.app.model.ReadBook.book?.bookUrl ?: return
            if (bookUrl.isBlank()) return
            appCtx.putPrefBoolean(PreferKey.aiImageEnablePrefix + bookUrl, value)
        }
    // AI 生图临时缓存保留数量上限（默认 50，至少为 1）
    var aiImageCacheCount: Int
        get() = appCtx.getPrefInt(PreferKey.aiImageCacheCount, 50)
        set(value) = appCtx.putPrefInt(PreferKey.aiImageCacheCount, value.coerceAtLeast(1))

    // AI 生图分析字数（每读多少字触发一次生图，默认200）
    var aiImageCharCount: Int
        get() = appCtx.getPrefInt(PreferKey.aiImageCharCount, 200)
        set(value) = appCtx.putPrefInt(PreferKey.aiImageCharCount, value)

    // AI 生图上下文字数（正文前/后的上文与后续字数，默认50，范围0~2000）
    var aiImageContextCharCount: Int
        get() = appCtx.getPrefInt(PreferKey.aiImageContextCharCount, 50).coerceIn(0, 2000)
        set(value) = appCtx.putPrefInt(PreferKey.aiImageContextCharCount, value.coerceIn(0, 2000))

    // AI 生图重试次数（生图失败后重试次数，每次重试重新收集正文，默认1）
    var aiImageRetryCount: Int
        get() = appCtx.getPrefInt(PreferKey.aiImageRetryCount, 1)
        set(value) = appCtx.putPrefInt(PreferKey.aiImageRetryCount, value)

    // AI 生图请求间隔时间（秒，每次请求与重试前等待，避免触发 API 频率限制，默认1.0）
    var aiImageRequestInterval: Float
        get() = appCtx.getPrefFloat(PreferKey.aiImageRequestInterval, 1.0f)
        set(value) = appCtx.putPrefFloat(PreferKey.aiImageRequestInterval, value)

    /**
     * 是否保存AI生图到持久目录（每本书单独设置，默认关闭）。
     * 仅当开关开启时，生图成功后才会落盘到 AI生图/{书名}/{章节}/。
     */
    fun isAiImageSaveEnabled(bookUrl: String): Boolean {
        if (bookUrl.isBlank()) return false
        return appCtx.getPrefBoolean(PreferKey.aiImageSavePrefix + bookUrl, false)
    }

    fun setAiImageSaveEnabled(bookUrl: String, enabled: Boolean) {
        if (bookUrl.isBlank()) return
        appCtx.putPrefBoolean(PreferKey.aiImageSavePrefix + bookUrl, enabled)
    }

    // 20. 朗读/大图图片轮播间隔（秒，默认5，0=不自动轮播，仅手动切换）
    // 注意：必须用 String 存储，因为 pref_config_aloud.xml 中是 EditTextPreference，
    // 其初始化会调用 SharedPreferences.getString()，若旧版本以 Int 存储则会崩溃。
    var readAloudImageInterval: Int
        get() = try {
            appCtx.defaultSharedPreferences.getString(PreferKey.readAloudImageInterval, "5")
                ?.toIntOrNull() ?: 5
        } catch (e: ClassCastException) {
            appCtx.getPrefInt(PreferKey.readAloudImageInterval, 5)
        }
        set(value) = appCtx.defaultSharedPreferences.edit()
            .putString(PreferKey.readAloudImageInterval, value.toString()).apply()

    // ================= 自定义功能区域 End =================

}
