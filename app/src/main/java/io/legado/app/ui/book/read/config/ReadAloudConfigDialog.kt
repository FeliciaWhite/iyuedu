package io.legado.app.ui.book.read.config

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.base.BasePrefDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.help.IntentHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.prefs.SwitchPreference
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.ReadAloud
import io.legado.app.service.BaseReadAloudService
import io.legado.app.service.ReadAloudFloatService
import io.legado.app.utils.GSON
import io.legado.app.utils.StringUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.postEvent
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment

class ReadAloudConfigDialog : BasePrefDialogFragment() {
    private val readAloudPreferTag = "readAloudPreferTag"

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            setBackgroundDrawableResource(R.color.transparent)
            setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = LinearLayout(requireContext())
        view.setBackgroundColor(requireContext().backgroundColor)
        view.id = R.id.tag1
        container?.addView(view)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        var preferenceFragment = childFragmentManager.findFragmentByTag(readAloudPreferTag)
        if (preferenceFragment == null) preferenceFragment = ReadAloudPreferenceFragment()
        childFragmentManager.beginTransaction()
            .replace(view.id, preferenceFragment, readAloudPreferTag)
            .commit()
    }

    class ReadAloudPreferenceFragment : PreferenceFragment(),
        SpeakEngineDialog.CallBack,
        SharedPreferences.OnSharedPreferenceChangeListener {

        private val speakEngineSummary: String
            get() {
                val ttsEngine = ReadAloud.ttsEngine
                    ?: return getString(R.string.system_tts)
                if (StringUtils.isNumeric(ttsEngine)) {
                    return appDb.httpTTSDao.getName(ttsEngine.toLong())
                        ?: getString(R.string.system_tts)
                }
                return GSON.fromJsonObject<SelectItem<String>>(ttsEngine).getOrNull()?.title
                    ?: getString(R.string.system_tts)
            }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            addPreferencesFromResource(R.xml.pref_config_aloud)
            upSpeakEngineSummary()
            findPreference<SwitchPreference>(PreferKey.pauseReadAloudWhilePhoneCalls)?.let {
                it.isEnabled = AppConfig.ignoreAudioFocus
            }

            bindSummaryToValue("audioPreDownloadNum", "1")
            bindSummaryToValue("audioCacheCleanTime", "10")
            bindSummaryToValue(PreferKey.sysTtsSynthesizeTimeout, "120")
            bindSummaryToValue(PreferKey.ttsRetryCount, "3")

            // "视频字幕样式" - 汇总设置（字号/字色/描边粗细/描边色），点击弹自定义对话框
            upSubtitleStyleSummary()

            // "字幕每行最大字数" - 使用默认对话框
            findPreference<EditTextPreference>(PreferKey.srtSubtitleMaxChars)?.let { pref ->
                pref.setOnPreferenceChangeListener { _, newValue ->
                    AppConfig.srtSubtitleMaxChars = (newValue as String).toIntOrNull() ?: 15
                    true
                }
            }

            // "字幕时间偏移" - 使用默认对话框
            findPreference<EditTextPreference>(PreferKey.srtSubtitleTimeOffset)?.let { pref ->
                pref.setOnPreferenceChangeListener { _, newValue ->
                    AppConfig.srtSubtitleTimeOffset = (newValue as String).toFloatOrNull() ?: 0f
                    true
                }
            }

            // "静音匹配范围" - 使用默认对话框
            findPreference<EditTextPreference>(PreferKey.srtSilenceMatchRange)?.let { pref ->
                pref.setOnPreferenceChangeListener { _, newValue ->
                    AppConfig.srtSilenceMatchRange = (newValue as String).toFloatOrNull() ?: 0.5f
                    true
                }
            }

            // "显示封面字幕和进度条" - SwitchPreference，无需额外绑定

            // "手动指定引擎包名"
            findPreference<EditTextPreference>(PreferKey.sysTtsPackageName)?.let { pref ->
                val pkg = AppConfig.sysTtsPackageName
                pref.summary = if (pkg.isNullOrBlank()) "当系统无法枚举到TTS引擎时，手动输入包名" else pkg
                pref.setOnPreferenceChangeListener { _, newValue ->
                    val newPkg = (newValue as String).trim()
                    pref.summary = if (newPkg.isBlank()) "当系统无法枚举到TTS引擎时，手动输入包名" else newPkg
                    true
                }
            }

            // "封面宽度" - 使用默认对话框
            findPreference<EditTextPreference>(PreferKey.readAloudCoverWidth)?.let { pref ->
                pref.summary = "当前宽度: ${AppConfig.readAloudCoverWidth}dp"
                pref.setOnPreferenceChangeListener { _, newValue ->
                    val width = (newValue as String).toIntOrNull()?.coerceIn(80, 600) ?: 240
                    AppConfig.readAloudCoverWidth = width
                    pref.summary = "当前宽度: ${width}dp"
                    true
                }
            }

            // "跑马灯速度"
            findPreference<EditTextPreference>(PreferKey.readAloudCoverMarqueeSpeed)?.let { pref ->
                pref.summary = "当前速度: ${AppConfig.readAloudCoverMarqueeSpeed}ms"
                pref.setOnPreferenceChangeListener { _, newValue ->
                    val speed = (newValue as String).toIntOrNull()?.coerceIn(500, 10000) ?: 3000
                    AppConfig.readAloudCoverMarqueeSpeed = speed
                    pref.summary = "当前速度: ${speed}ms"
                    true
                }
            }

            // "悬浮窗贴边距离偏移"
            findPreference<EditTextPreference>(PreferKey.readAloudFloatEdgeOffset)?.let { pref ->
                val current = AppConfig.readAloudFloatEdgeOffset
                pref.summary = "当前偏移: ${current}dp，正值往内缩，负数超出屏幕"
                pref.setOnPreferenceChangeListener { _, newValue ->
                    val value = (newValue as String).toIntOrNull() ?: 0
                    AppConfig.readAloudFloatEdgeOffset = value
                    pref.summary = "当前偏移: ${value}dp，正值往内缩，负数超出屏幕"
                    true
                }
            }

            // "超短音频判定阈值"
            findPreference<EditTextPreference>(PreferKey.shortAudioMinDuration)?.let { pref ->
                val current = AppConfig.shortAudioMinDuration
                pref.summary = "当前阈值: ${current}秒，0表示不限制"
                pref.setOnPreferenceChangeListener { _, newValue ->
                    val value = (newValue as String).toFloatOrNull() ?: 0.3f
                    AppConfig.shortAudioMinDuration = value
                    pref.summary = "当前阈值: ${value}秒，0表示不限制"
                    true
                }
            }

            // "显示对话角色标注" - SwitchPreference，无需额外绑定

            // "角色标注上下偏移"
            findPreference<EditTextPreference>(PreferKey.roleAnnotationOffset)?.let { pref ->
                val current = AppConfig.roleAnnotationOffset
                pref.summary = "当前偏移: ${current}dp，正数向上，负数向下"
                pref.setOnPreferenceChangeListener { _, newValue ->
                    val value = (newValue as String).toIntOrNull() ?: 0
                    AppConfig.roleAnnotationOffset = value
                    pref.summary = "当前偏移: ${value}dp，正数向上，负数向下"
                    true
                }
            }

            // "播放音量增益" - 修改后实时生效（通知正在朗读的服务调整增益）
            findPreference<EditTextPreference>(PreferKey.readAloudVolumeGain)?.let { pref ->
                val current = AppConfig.readAloudVolumeGain
                pref.summary = "当前增益: ${current}x"
                pref.setOnPreferenceChangeListener { _, newValue ->
                    val gain = (newValue as String).toFloatOrNull()?.coerceIn(0.5f, 5.0f) ?: 1.0f
                    AppConfig.readAloudVolumeGain = gain
                    pref.summary = "当前增益: ${gain}x"
                    postEvent(EventBus.READ_ALOUD_VOLUME_GAIN, gain)
                    true
                }
            }

        }

        private fun bindSummaryToValue(key: String, defaultVal: String) {
            val pref = findPreference<EditTextPreference>(key)
            pref?.let {
                val currentVal = preferenceManager.sharedPreferences?.getString(key, defaultVal)
                it.summary = currentVal
                it.setOnPreferenceChangeListener { _, newValue ->
                    it.summary = newValue.toString()
                    true
                }
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            listView.setEdgeEffectColor(primaryColor)
        }

        override fun onResume() {
            super.onResume()
            preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onPause() {
            preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
            super.onPause()
        }

        override fun onPreferenceTreeClick(preference: Preference): Boolean {
            when (preference.key) {
                PreferKey.ttsEngine -> {
                    showDialogFragment(SpeakEngineDialog())
                    return true
                }
                PreferKey.videoSubtitleStyle -> {
                    showDialogFragment(VideoSubtitleStyleDialog())
                    return true
                }
                "sysTtsConfig" -> {
                    IntentHelp.openTTSSetting()
                    return true
                }
            }
            return super.onPreferenceTreeClick(preference)
        }

        override fun onSharedPreferenceChanged(
            sharedPreferences: SharedPreferences?,
            key: String?
        ) {
            when (key) {
                PreferKey.readAloudByPage, PreferKey.streamReadAloudAudio -> {
                    if (BaseReadAloudService.isRun) {
                        postEvent(EventBus.MEDIA_BUTTON, false)
                    }
                }

                PreferKey.ignoreAudioFocus -> {
                    findPreference<SwitchPreference>(PreferKey.pauseReadAloudWhilePhoneCalls)?.let {
                        it.isEnabled = AppConfig.ignoreAudioFocus
                    }
                }

                PreferKey.showReadAloudCoverSubtitle, PreferKey.readAloudCoverWidth,
                PreferKey.readAloudCoverMarqueeEnabled, PreferKey.readAloudCoverMarqueeSpeed,
                PreferKey.marqueeBgmLinkEnabled -> {
                    postEvent(EventBus.READ_ALOUD_CONFIG_CHANGED, true)
                }

                PreferKey.sysTtsPackageName -> {
                    findPreference<EditTextPreference>(PreferKey.sysTtsPackageName)?.let { pref ->
                        val pkg = AppConfig.sysTtsPackageName
                        pref.summary = if (pkg.isNullOrBlank()) "当系统无法枚举到TTS引擎时，手动输入包名" else pkg
                    }
                }

                PreferKey.readAloudFloatWindow -> {
                    if (BaseReadAloudService.isRun) {
                        if (sharedPreferences?.getBoolean(PreferKey.readAloudFloatWindow, false) == true) {
                            ReadAloudFloatService.updateVisibility(requireContext())
                        } else {
                            ReadAloudFloatService.stop(requireContext())
                        }
                    }
                }

                PreferKey.videoSubtitleFontSizeScale,
                PreferKey.videoSubtitleFontColor,
                PreferKey.videoSubtitleStrokeWidth,
                PreferKey.videoSubtitleStrokeColor -> {
                    upSubtitleStyleSummary()
                }
            }
        }

        private fun upSubtitleStyleSummary() {
            findPreference<Preference>(PreferKey.videoSubtitleStyle)?.let { pref ->
                pref.summary = "字号 ${AppConfig.videoSubtitleFontSizeScale} · 颜色 ${AppConfig.videoSubtitleFontColor} · " +
                        "描边 ${AppConfig.videoSubtitleStrokeWidth} · 描边色 ${AppConfig.videoSubtitleStrokeColor}"
            }
        }

        private fun upPreferenceSummary(preference: Preference?, value: String) {
            when (preference) {
                is ListPreference -> {
                    val index = preference.findIndexOfValue(value)
                    preference.summary = if (index >= 0) preference.entries[index] else null
                }
                else -> {
                    preference?.summary = value
                }
            }
        }

        override fun upSpeakEngineSummary() {
            upPreferenceSummary(
                findPreference(PreferKey.ttsEngine),
                speakEngineSummary
            )
        }
    }
}
