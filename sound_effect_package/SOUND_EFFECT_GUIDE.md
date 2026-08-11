# 音效功能实现指南

## 1. 概述

本文档介绍阅读器朗读服务中音效功能的完整实现流程。涵盖从获取当前文本、替换音效标记、下载音效文件，到在 TTS 朗读进度中触发播放的完整链路。

---

## 2. 核心概念

### 2.1 音效标记格式

音效通过特殊的方括号语法嵌入在文本中。例如：

```
[ding] -- 这是一个音效标记
```

这些标记**对 TTS 不可见**（在合成前会被移除），但会被**记录位置**。当朗读读到该位置时，就会触发对应的音效文件。

### 2.2 音效文件

音效以 JSON 文件形式存放在本地目录（`/storage/emulated/0/Download/chajian/bendiyinxiao2/`）。每个 JSON 文件包含一个 `currentIndex` 和一个 `audios` 数组，数组里存放的是 base64 编码的 MP3 数据。格式如下：

```json
{
  "currentIndex": 0,
  "audios": [
    "base64编码的MP3数据",
    "base64编码的MP3数据"
  ]
}
```

`currentIndex` 用于**轮换播放**：当同一个音效被多次触发时，会从数组中取出不同的音频变体，避免反复播放同一个声音。

---

## 3. 架构

该功能涉及多个组件：

| 组件 | 文件 | 职责 |
|------|------|------|
| 朗读服务 | `BaseReadAloudService.kt` | 核心逻辑：标记提取、进度跟踪、触发判断、下载、播放 |
| TTS 服务 | `TTSReadAloudService.kt` | 集成 Android TTS，向 TTS 传文本，上报进度 |
| 在线 TTS 服务 | `HttpReadAloudService.kt` | 支持在线 TTS 引擎，同样上报进度 |
| 配置对话框 | `BgmConfigDialog.kt` | 音效设置界面（模式、偏移、音量） |
| 布局 | `dialog_bgm_config.xml` | 对话框布局 XML |
| 偏好配置 | `PreferKey.kt` + `AppConfig.kt` | 偏好键和类型化访问器 |
| 工具类 | `SubTitleUtils.kt` | 中文字符计数工具 |

---

## 4. 完整流程

### 第一步：获取当前文本（在 TTSReadAloudService.kt 中）

当开始朗读新段落时，从内容列表中获取文本：

```kotlin
val text = contentList[nowSpeak]
// text 包含带有标记的原文，例如："[ding] 咚咚咚！"
```

### 第二步：提取音效标记（在 BaseReadAloudService.kt 中）

在将文本发给 TTS 之前，先通过 `extractSoundEffects()` 提取标记：

```kotlin
data class SoundEffectMarker(
    val name: String,       // 例如 "ding"
    val charOffset: Int,    // 在文本中的字符位置
    var triggered: Boolean = false
)

/**
 * 从文本中提取 [音效标记]，返回：
 * 1. 去掉标记后的干净文本
 * 2. SoundEffectMarker 列表
 */
fun extractSoundEffects(text: String): Pair<String, List<SoundEffectMarker>> {
    val regex = Regex("\\[([^\\[\\]]+)\\]")
    val markers = mutableListOf<SoundEffectMarker>()
    var cleanText = ""
    val matches = regex.findAll(text)
    var lastIndex = 0
    for (match in matches) {
        val start = match.range.first
        val end = match.range.last + 1
        // 把标记之前的文本追加到干净文本
        cleanText += text.substring(lastIndex, start)
        // 记录标记位置
        val effectName = match.groupValues[1]
        val offset = cleanText.length
        markers.add(SoundEffectMarker(effectName, offset))
        lastIndex = end
    }
    // 追加剩余文本
    cleanText += text.substring(lastIndex)
    return cleanText to markers
}
```

**关键点**：`charOffset` 是**去掉标记后的干净文本**中的位置。这个位置就是 TTS 引擎实际"看到"的位置。

### 第三步：应用音效规则并替换标记（在 BaseReadAloudService.kt 中）

根据用户选择的模式（`off` 关闭、`normal` 普通、`all` 全部），标记要么被完全移除，要么替换成短停顿标记（`[p500]`），要么保留用于后续触发：

```kotlin
fun applySoundEffectRules(text: String, markers: List<SoundEffectMarker>): String {
    val mode = AppConfig.soundEffectMode // "off"、"normal"、"all"
    if (mode == "off" || markers.isEmpty()) return text
    
    // normal 模式：把标记替换成 [p500] 停顿
    var result = text
    if (mode == "normal") {
        for (marker in markers.sortedByDescending { it.charOffset }) {
            result = result.replaceRange(
                marker.charOffset,
                marker.charOffset + "[${marker.name}]".length,
                "[p500]"
            )
        }
    }
    return result
}
```

`paragraphEffects` 列表保存当前段落提取出的所有标记，`currentParagraphTotalChars` 保存干净文本的总长度。

### 第四步：将文本发给 TTS 引擎（在 TTSReadAloudService.kt 中）

去掉标记后的干净文本被发送给 TTS 引擎朗读：

```kotlin
// 在 TTSReadAloudService 内部
val text = contentList[nowSpeak]
// 提取并保存当前段落的音效标记
val (cleanText, effects) = extractSoundEffects(text)
paragraphEffects.clear()
paragraphEffects.addAll(effects)
currentParagraphTotalChars = SubTitleUtils.countChinese(cleanText) // 或 cleanText.length

// 应用规则（替换或移除标记）
val finalText = applySoundEffectRules(cleanText, effects)

// 发送给 TTS
tts.speak(finalText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
```

### 第五步：跟踪朗读进度并触发音效（在 BaseReadAloudService.kt 中）

`TTSReadAloudService` 和 `HttpReadAloudService` 都会定期上报进度（约每 200ms 一次）。`onTtsProgress` 回调会收到当前音频位置和总时长。

核心逻辑在 `checkAndPlayEffects()` 中：

```kotlin
/**
 * 检查当前朗读位置是否到达任何音效标记。
 * 每次进度更新时调用（例如每 200ms）。
 */
private fun checkAndPlayEffects(currentPosition: Int, totalDuration: Int) {
    val effects = paragraphEffects
    if (effects.isEmpty()) return

    val duration = totalDuration.coerceAtLeast(1)
    val totalChars = currentParagraphTotalChars.coerceAtLeast(1)

    // 计算当前音频进度比例
    val ratio = currentPosition.toFloat() / duration
    // 将比例映射到干净文本中的字符位置
    val charPos = (ratio * totalChars).toInt()

    // 检查每个标记
    effects.forEach { effect ->
        if (!effect.triggered && charPos >= (effect.charOffset - AppConfig.soundEffectOffsetChars)) {
            effect.triggered = true
            loadAndPlayEffect(effect)
        }
    }
}
```

**计算原理**：
- `currentPosition` = 当前音频播放时间（毫秒）
- `totalDuration` = 当前这段音频的总时长（毫秒）
- `ratio` = 0.0 ~ 1.0，表示音频播放到哪里了
- `charPos` = 当前"读到了第几个字符"
- `charOffset` = 标记在干净文本中的位置
- `AppConfig.soundEffectOffsetChars` = **提前多少字符触发**（用户可设置，默认 5）

**注意**：判断条件是 `charPos >= (effect.charOffset - AppConfig.soundEffectOffsetChars)`，这意味着音效会比实际文本位置**提前触发**，让听感更自然。

### 第六步：下载音效（在 BaseReadAloudService.kt 中）

当标记被触发时，会调用 `loadAndPlayEffect()`。它先检查本地有没有缓存文件，没有就下载：

```kotlin
/**
 * 下载并解码音效 JSON 文件。
 * 返回解码后的音频文件和当前使用的索引。
 */
private fun downloadAndDecodeEffect(fileName: String, consumeIndex: Boolean = false): Pair<File, Int>? {
    val sfxDir = File("/storage/emulated/0/Download/chajian/bendiyinxiao2")
    if (!sfxDir.exists()) sfxDir.mkdirs()
    val jsonFile = File(sfxDir, fileName)

    var jsonStr: String? = null
    // 1. 优先读取本地 JSON
    if (jsonFile.exists()) {
        try {
            jsonStr = jsonFile.readText()
            if (jsonStr.isNullOrEmpty() || !jsonStr.trimStart().startsWith("{")) jsonStr = null
        } catch (e: Exception) { jsonStr = null }
    }

    // 2. 本地没有就下载
    if (jsonStr == null) {
        val downloadUrl = "https://example.com/sfx/$fileName" // 你的下载地址
        try {
            val conn = URL(downloadUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("Accept", "application/json")
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            jsonStr = response
            // 保存到本地缓存
            jsonFile.writeText(response)
        } catch (e: Exception) { return null }
    }

    // 3. 解析 JSON 并解码 base64
    try {
        val json = JSONObject(jsonStr)
        val currentIndex = json.optInt("currentIndex", 0)
        val audiosArray = json.getJSONArray("audios")
        if (audiosArray.length() == 0) return null

        val index = currentIndex % audiosArray.length()
        val base64Str = audiosArray.getString(index)

        // 将 base64 解码成 MP3 文件
        val audioBytes = Base64.decode(base64Str, Base64.DEFAULT)
        val audioFile = File(sfxDir, fileName.replace(".json", "_$index.mp3"))
        audioFile.writeBytes(audioBytes)

        // 4. 如果 consumeIndex 为 true，消耗索引并写回 JSON
        if (consumeIndex) {
            json.put("currentIndex", (currentIndex + 1) % audiosArray.length())
            jsonFile.writeText(json.toString())
        }

        return audioFile to index
    } catch (e: Exception) { return null }
}
```

**关键点**：
- 首次下载后会在本地缓存。
- `consumeIndex = true` 会轮换到下一个音频变体，避免反复播放同一个声音。
- 解码后的 `.mp3` 文件和 `.json` 文件放在同一个目录。

### 第七步：播放音效并调节音量（在 BaseReadAloudService.kt 中）

音频文件准备好后，使用 `MediaPlayer` 播放，并支持音量调节：

```kotlin
private fun doPlayEffect(audioFile: File, effectName: String) {
    try {
        val volume = AppConfig.soundEffectVolume / 100f  // 0.0 ~ 1.0
        val mp = MediaPlayer().apply {
            setDataSource(audioFile.absolutePath)
            setVolume(volume, volume)  // 设置左右声道音量
            prepare()
            setOnCompletionListener {
                it.release()
                lastEffectPlayTime = System.currentTimeMillis()
                currentMediaPlayer = null
                playNextEffect()  // 播放队列中的下一个音效
            }
            setOnErrorListener { mp, what, extra ->
                mp.release()
                currentMediaPlayer = null
                playNextEffect()
                true
            }
            start()
        }
        currentMediaPlayer = mp
        lastEffectPlayTime = System.currentTimeMillis()
    } catch (e: Exception) {
        currentMediaPlayer = null
        playNextEffect()
    }
}
```

**音量控制**：`AppConfig.soundEffectVolume` 是一个 0~100 的整数（默认 80）。除以 100f 后映射到 `MediaPlayer.setVolume()` 所需的 0.0~1.0 范围。

---

## 5. 配置界面（BgmConfigDialog.kt + dialog_bgm_config.xml）

对话框中有三个音效相关的控件：

### 5.1 音效替换（下拉选择）
- **关闭**：不播放音效，标记从 TTS 文本中移除。
- **普通音效**：标记被替换成 `[p500]`（500ms 停顿），TTS 不会读出标记名，但也不会播放音效文件。
- **全部音效**：完整音效功能——标记用于位置跟踪，音效文件会被下载并播放。

### 5.2 音效偏移字数（数字输入框）
- 默认值：**5** 个字符。
- 这个值会从 `charOffset` 中减去，也就是提前触发。
- 数值越大，音效越早播放。

### 5.3 音效音量（滑块，0-100）
- 默认值：**80**。
- 控制音效播放时的音量大小。
- 与背景音乐音量和 TTS 音量相互独立。

### 偏好键（在 PreferKey.kt 中）

```kotlin
const val soundEffectMode = "soundEffectMode"           // "off" | "normal" | "all"
const val soundEffectOffsetChars = "soundEffectOffsetChars"  // Int，默认 5
const val soundEffectVolume = "soundEffectVolume"       // Int，默认 80
```

### AppConfig 访问器（在 AppConfig.kt 中）

```kotlin
var soundEffectMode: String
    get() = appCtx.getPrefString(PreferKey.soundEffectMode, "off") ?: "off"
    set(value) = appCtx.putPrefString(PreferKey.soundEffectMode, value)

var soundEffectOffsetChars: Int
    get() = appCtx.getPrefInt(PreferKey.soundEffectOffsetChars, 5)
    set(value) = appCtx.putPrefInt(PreferKey.soundEffectOffsetChars, value)

var soundEffectVolume: Int
    get() = appCtx.getPrefInt(PreferKey.soundEffectVolume, 80)
    set(value) = appCtx.putPrefInt(PreferKey.soundEffectVolume, value)
```

---

## 6. 如何移植到其他阅读器

如果你想把这套音效功能加到另一个阅读器里，按以下步骤操作：

### 6.1 添加偏好键和配置
把三个 `const val` 键复制到你应用的偏好键类里，并在 `AppConfig` 中添加对应的属性。

### 6.2 实现提取逻辑
在你的 TTS 服务（或准备发送给 TTS 前的位置）添加 `extractSoundEffects()` 和 `applySoundEffectRules()`。在调用 `tts.speak()` 之前，先调用这两个方法去掉标记并记录位置。

### 6.3 跟踪段落级状态
在朗读服务中维护两个字段：

```kotlin
var paragraphEffects: MutableList<SoundEffectMarker> = mutableListOf()
var currentParagraphTotalChars: Int = 0
```

每次开始新段落时填充它们。

### 6.4 接收进度回调
如果 TTS 引擎支持 `UtteranceProgressListener`（Android 自带 TTS），实现 `onRangeStart()` 或 `onAudioAvailable()` 获取进度。如果是在线 TTS，可能需要根据音频播放位置估算进度。

每次进度更新时调用 `checkAndPlayEffects(currentPosition, totalDuration)`。

### 6.5 实现下载和播放
在你的服务中添加 `downloadAndDecodeEffect()` 和 `doPlayEffect()`。确保你有：
- 一个本地目录存放 JSON 和 MP3 文件。
- 一个基础 URL 用于下载 JSON 文件。
- Base64 解码能力。

### 6.6 添加界面控件
在朗读设置对话框中加一个下拉框（或分段控件）选择模式、一个数字输入框设置偏移、一个滑块设置音量。通过监听器绑定到 `AppConfig`。

### 6.7 处理暂停/继续/停止
- **暂停**：调用 `pauseSoundEffects()` 暂停 `currentMediaPlayer`。
- **继续/下一段**：清空 `paragraphEffects`，重置 `triggered` 标志。
- **停止**：释放 `currentMediaPlayer`，清空队列。

---

## 7. 压缩包内的文件列表

```
sound_effect_package/
├── SOUND_EFFECT_GUIDE.md              # 本文档
├── service/
│   ├── BaseReadAloudService.kt        # 核心音效逻辑（提取、触发、下载、播放）
│   ├── TTSReadAloudService.kt         # Android TTS 集成、进度上报
│   └── HttpReadAloudService.kt        # 在线 TTS 集成、进度上报
├── ui/
│   └── BgmConfigDialog.kt             # 设置对话框（模式、偏移、音量）
├── res/layout/
│   └── dialog_bgm_config.xml          # 对话框布局 XML
├── constant/
│   └── PreferKey.kt                   # 偏好键
├── config/
│   └── AppConfig.kt                   # 类型化偏好访问器
└── utils/
    └── SubTitleUtils.kt               # 中文字符计数工具
```

---

## 8. 注意事项

- 当前实现使用了固定的本地路径（`/storage/emulated/0/Download/chajian/bendiyinxiao2/`），请根据你的应用需求调整。
- 示例中的下载 URL 是占位符，请替换为你实际的 CDN 或服务器地址。
- 音效 JSON 文件需要预先准备好。每个文件对应一个标记名（例如 `ding.json` 对应 `[ding]`）。
- 如果使用的自定义 TTS 引擎不提供逐字进度，你可能需要通过音频时长除以文本长度来估算进度。
