package io.legado.app.ui.tts.plugin

import io.legado.app.help.audiobook.JReadVoiceEngine

sealed class ConfigListRow {
    data class GroupHeader(val groupName: String, val expanded: Boolean, val configCount: Int) : ConfigListRow()
    data class SubGroupHeader(val groupName: String, val subGroupName: String, val expanded: Boolean, val configCount: Int) : ConfigListRow()
    data class ConfigRow(val config: JReadVoiceEngine.VoiceConfig) : ConfigListRow()
    data class PluginRow(val plugin: JReadVoiceEngine.VoicePlugin) : ConfigListRow()
}
