package io.legado.app.ui.tts.plugin

import io.legado.app.help.audiobook.JReadVoiceEngine

sealed class TtsPluginItem {
    data class ConfigItem(val config: JReadVoiceEngine.VoiceConfig) : TtsPluginItem()
    data class PluginItem(val plugin: JReadVoiceEngine.VoicePlugin) : TtsPluginItem()
}
