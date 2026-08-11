package io.legado.app.ui.book.read.config

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object CharacterVoiceHelper {

    private val baseDir: String
        get() = "/storage/emulated/0/Download/chajian/mingwuyan/"

    private fun readTxtFile(fileName: String): String {
        return try {
            val file = File(baseDir, fileName)
            if (file.exists()) file.readText() else ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun writeTxtFile(fileName: String, content: String) {
        try {
            val file = File(baseDir, fileName)
            file.parentFile?.mkdirs()
            file.writeText(content)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getCharacterRecords(bookName: String): JSONArray {
        val charRecordsContent = readTxtFile("characterRecords.json")
        val cunfangContent = readTxtFile("cunfang.txt").ifEmpty { "默认" }.trim()
        return if (bookName == cunfangContent && charRecordsContent.isNotEmpty()) {
            try {
                JSONArray(charRecordsContent)
            } catch (e: Exception) {
                JSONArray()
            }
        } else {
            val fileName = "shuming.$bookName.json"
            val content = readTxtFile(fileName)
            try {
                if (content.isNotEmpty()) JSONArray(content) else JSONArray()
            } catch (e: Exception) {
                JSONArray()
            }
        }
    }

    fun findCharacterVoice(bookName: String, name: String): String? {
        val records = getCharacterRecords(bookName)
        val normalizedName = normalizeString(name)
        for (i in 0 until records.length()) {
            val char = records.optJSONObject(i) ?: continue
            val charName = normalizeString(char.optString("name", ""))
            if (charName == normalizedName) {
                return char.optString("voice", "").takeIf { it.isNotBlank() }
            }
            val aliasesStr = char.optString("aliases", "")
            val aliases = aliasesStr.split("|").map { normalizeString(it) }
            if (aliases.contains(normalizedName)) {
                return char.optString("voice", "").takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    fun saveCharacterVoice(bookName: String, name: String, voice: String) {
        val records = getCharacterRecords(bookName)
        val normalizedName = normalizeString(name)
        var found = false
        for (i in 0 until records.length()) {
            val char = records.optJSONObject(i) ?: continue
            val charName = normalizeString(char.optString("name", ""))
            val aliasesStr = char.optString("aliases", "")
            val aliases = aliasesStr.split("|").map { normalizeString(it) }
            if (charName == normalizedName || aliases.contains(normalizedName)) {
                char.put("voice", voice)
                found = true
                break
            }
        }
        if (!found) {
            val newChar = JSONObject().apply {
                put("name", name)
                put("aliases", name)
                put("gender", "")
                put("age", "")
                put("voice", voice)
                put("usageCount", 0)
            }
            records.put(newChar)
        }
        val jsonData = records.toString()
        writeTxtFile("characterRecords.json", jsonData)
        writeTxtFile("shuming.$bookName.json", jsonData)
        createGengxinFile(records)
    }

    private fun createGengxinFile(records: JSONArray) {
        try {
            val saveRecords = JSONArray()
            for (i in 0 until records.length()) {
                val char = records.optJSONObject(i) ?: continue
                val record = JSONObject().apply {
                    put("name", char.optString("name", ""))
                    put("aliases", char.optString("aliases", ""))
                    put("voice", char.optString("voice", ""))
                    put("gender", char.optString("gender", ""))
                    put("age", char.optString("age", ""))
                    put("usageCount", char.optInt("usageCount", 0))
                    if (char.has("fixedVoice")) put("fixedVoice", char.optBoolean("fixedVoice", false))
                    if (char.has("fixedGenderAge")) put("fixedGenderAge", char.optBoolean("fixedGenderAge", false))
                    if (char.has("genderAgeHistory")) put("genderAgeHistory", char.opt("genderAgeHistory"))
                }
                saveRecords.put(record)
            }
            writeTxtFile("gengxin.json", saveRecords.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun normalizeString(str: String): String {
        return str.replace(Regex("[\u200B-\u200D\uFEFF]"), "").trim()
    }

    fun showVoiceCategoryDialog(context: Context, onCategorySelected: (String) -> Unit) {
        val categories = arrayOf(
            "女童", "少女", "女青年", "女中年", "女老年",
            "男童", "少年", "男青年", "男中年", "男老年",
            "男主", "女主", "特殊"
        )
        AlertDialog.Builder(context)
            .setTitle("选择发音人分类")
            .setItems(categories) { _, which ->
                val category = categories[which]
                onCategorySelected(category)
            }
            .setNegativeButton("全部") { _, _ ->
                showVoiceListDialog(context, "全部", onCategorySelected)
            }
            .setNeutralButton("搜索") { _, _ ->
                showVoiceSearchDialog(context, onCategorySelected)
            }
            .setPositiveButton("取消", null)
            .show()
    }

    fun showVoiceSearchDialog(context: Context, onVoiceSelected: (String) -> Unit) {
        val editText = androidx.appcompat.widget.AppCompatEditText(context).apply {
            hint = "输入关键词搜索发音人"
        }
        AlertDialog.Builder(context)
            .setTitle("搜索发音人")
            .setView(editText)
            .setPositiveButton("搜索") { _, _ ->
                val keyword = editText.text?.toString()?.trim() ?: ""
                if (keyword.isNotEmpty()) {
                    showVoiceListDialog(context, keyword, onVoiceSelected)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun showVoiceListDialog(context: Context, category: String, onVoiceSelected: (String) -> Unit) {
        val allVoices = getVoiceList()
        val filteredVoices = when {
            category == "全部" -> allVoices
            category.isNotEmpty() -> allVoices.filter { v -> v.contains(category) }
            else -> allVoices
        }
        val displayList = if (filteredVoices.isEmpty()) allVoices else filteredVoices
        if (displayList.isEmpty()) {
            Toast.makeText(context, "发音人列表为空", Toast.LENGTH_SHORT).show()
            return
        }
        val title = if (category == "全部") "选择发音人 (全部)" else "选择发音人 ($category)"
        AlertDialog.Builder(context)
            .setTitle(title)
            .setItems(displayList.toTypedArray()) { _, which ->
                val voice = displayList[which]
                onVoiceSelected(voice)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun getVoiceList(): List<String> {
        return try {
            val content = readTxtFile("fayinren.json")
            if (content.isNotEmpty()) {
                val arr = JSONArray(content)
                (0 until arr.length()).map { arr.getString(it) }
            } else {
                listOf("auto")
            }
        } catch (e: Exception) {
            listOf("auto")
        }
    }

    fun previewCharacterVoice(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        bookName: String,
        name: String,
        onToast: (String) -> Unit
    ) {
        val voice = findCharacterVoice(bookName, name)
        if (voice.isNullOrBlank()) {
            onToast("未找到该角色的发音人")
            return
        }
        val prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val customText = prefs.getString("tts_preview_text", "你好，这是一段试听语音") ?: "你好，这是一段试听语音"
        val previewText = "\u201C <<$voice>>$customText\u201D"
        TtsPreviewHelper.previewVoice(
            context = context,
            lifecycleOwner = lifecycleOwner,
            previewText = previewText,
            voiceId = voice,
            onToast = onToast
        )
    }
}
