# 📅 唤醒文档 - Legado 阅读APP legado_Bgm分支

**最后更新时间**: 2026-01-24

---

## 🎯 项目概述

**Legado 阅读APP legado_Bgm分支** - 在开源阅读APP Legado基础上新增AI智能背景音乐切换功能

**核心功能**：
- AI智能背景音乐切换：根据小说内容自动推荐并切换背景音乐
- 在线朗读服务：基于HTTP TTS的听书功能
- 预缓存机制：提前下载后续章节的TTS音频
- 文本一致性保证：确保预缓存和阅读界面的文本完全一致

---

## 🌍 当前环境信息

### 操作系统
- **OS**: Linux
- **Shell**: zsh
- **Workspace**: `/workspace`

### 项目配置
```kotlin
// app/build.gradle.kt
compileSdk = 36
targetSdk = 36
minSdk = 21

dependencies {
    core = "androidx.core:core-ktx:1.17.0"
    activity = "androidx.activity:activity-ktx:1.11.0"
    media3 = "androidx.media3:media3-exoplayer:1.8.0"
}
```

### Git 分支
- **当前分支**: `legado_Bgm`
- **远程仓库**: `origin/legado_Bgm`
- **基于**: 原始Legado项目 + Bgm功能重构
- **最新提交**: 查看最新构建版本号

---

## 📦 构建安装包

### 快速构建步骤

```bash
# 1. 进入项目目录
cd /workspace

# 2. 获取当前commit数（用于版本号）
git rev-list --count HEAD

# 3. 清理旧的构建（可选，推荐）
./gradlew clean

# 4. 构建Release版本APK
./gradlew assembleAppRelease

# 5. 查找生成的APK
find app/build/outputs/apk -name "*.apk" -type f

# 6. 复制到newapk目录并重命名
cp app/build/outputs/apk/app/release/legado_app_3.26.XXXXXXX.apk \
   newapk/legado_app_3.26.6-beta.{commit数}.apk
```

### APK输出路径
```
原始路径: /workspace/app/build/outputs/apk/app/release/legado_app_3.26.XXXXXXX.apk
目标路径: /workspace/newapk/legado_app_3.26.6-beta.{commit数}.apk
```

### 版本命名规则
- 格式：`legado_app_3.26.6-beta.{commit数}`
- 示例：`legado_app_3.26.6-beta.7257.apk`
- APK大小：约25MB

### 提交APK到远程仓库
```bash
# 删除旧版本
rm newapk/legado_app_3.26.6-beta.{旧版本}.apk

# 添加新版本（强制添加，因为.gitignore可能忽略.apk）
git add -f newapk/legado_app_3.26.6-beta.{新版本}.apk

# 提交
git commit -m "更新beta版APK: legado_app_3.26.6-beta.{commit数}"

# 推送
git push origin legado_Bgm
```

---

## 🚀 AI智能背景音乐功能详解

### 功能架构

```
┌─────────────────────────────────────────────────────────────┐
│                    听书流程                                │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  TTS音频播放完一个段落 → 段落计数器+1                       │
│  - TTSReadAloudService: onDone() 回调时调用                 │
│  - HttpReadAloudService: updateNextPos() 时调用               │
│                                                             │
│  当计数器 >= 间隔（默认3） → 触发AI分析                   │
│                                                             │
│  收集文本（书名+作者+分类+当前及后续段落） → AI分析         │
│                                                             │
│  AI返回推荐文件名 → 精确/模糊匹配 → 切换背景音乐          │
│  - 输出日志：AI返回的文件名和匹配结果                       │
│                                                             │
│  听书控制与背景音乐同步                                      │
│  - 播放听书：背景音乐自动播放                              │
│  - 暂停听书：背景音乐自动暂停                              │
│  - 恢复听书：背景音乐自动恢复                              │
│                                                             │
│  背景音乐控制                                              │
│  - 手动上一首/下一首：触发AI再次识别                        │
│  - 上段/下段按钮：只控制TTS段落，不影响背景音乐              │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 核心配置项

**AppConfig.bgmAIEnabled**
- 类型：Boolean
- 说明：是否启用AI智能切歌功能
- 默认值：false（需用户在听书设置中手动开启）

**AppConfig.bgmAIParagraphInterval**
- 类型：Int
- 说明：每朗读多少段触发一次AI分析
- 默认值：3
- 范围：1-10

**AppConfig.bgmPath**
- 类型：String（URI）
- 说明：背景音乐文件夹路径
- 格式：支持SAF（content://）和普通文件路径

### 关键代码位置

**BaseReadAloudService.kt**
```kotlin
// 段落计数器
protected var bgmParagraphCounter = 0

// 检查是否触发AI切换
protected fun checkBgmSwitch() {
    val isEnabled = AppConfig.bgmAIEnabled
    val interval = AppConfig.bgmAIParagraphInterval

    if (!isEnabled) return

    bgmParagraphCounter++
    AppLog.putDebug("AI背景音乐: 段落计数器=$bgmParagraphCounter, 设置间隔=$interval")

    if (bgmParagraphCounter >= interval) {
        bgmParagraphCounter = 0
        AppLog.putDebug("AI背景音乐: 触发切换分析")
        // 触发AI分析
        lifecycleScope.launch {
            val contentToAnalyze = withContext(IO) {
                collectContentForAI()
            }
            if (contentToAnalyze != null) {
                AppLog.putDebug("AI背景音乐: 收集到的文本长度=${contentToAnalyze.length}")
                BgmManager.switchBgmByContent(contentToAnalyze)
            }
        }
    }
}

// 收集AI分析文本
protected fun collectContentForAI(): String? {
    val maxCharCount = 5000
    val maxParagraphs = AppConfig.bgmAIParagraphInterval

    // 添加书籍信息
    val book = ReadBook.book
    if (book != null) {
        sb.append("【书籍信息】\n")
        sb.append("书名：${book.name}\n")
        if (!book.author.isNullOrEmpty()) {
            sb.append("作者：${book.author}\n")
        }
        if (!book.kind.isNullOrEmpty()) {
            sb.append("分类：${book.kind}\n")
        }
        sb.append("正文内容：\n\n")
    }

    // 收集段落
    for (i in nowSpeak until contentList.size) {
        if (collectedParagraphs >= maxParagraphs) break
        val paragraph = contentList[i]
        if (paragraph.isNullOrBlank() || paragraph.matches(AppPattern.notReadAloudRegex)) {
            continue
        }
        sb.append(paragraph).append("\n")
        collectedParagraphs++
    }

    return sb.toString().trim().takeIf { it.isNotEmpty() }
}

// 播放时同步背景音乐
override fun play() {
    super.play()
    BgmManager.play()  // 背景音乐自动播放
}

// 暂停时同步背景音乐
override fun pauseReadAloud() {
    super.pauseReadAloud()
    BgmManager.pause()  // 背景音乐自动暂停
}

// 恢复时同步背景音乐
override fun resumeReadAloud() {
    super.resumeReadAloud()
    BgmManager.play()  // 背景音乐自动恢复
}
```

**TTSReadAloudService.kt**
```kotlin
// onDone回调：TTS播放完一个段落后触发
override fun onDone() {
    super.onDone()
    // 段落计数器增加，触发AI切换检查
    checkBgmSwitch()
}
```

**HttpReadAloudService.kt**
```kotlin
// 更新播放位置并触发AI切歌
private fun updateNextPos() {
    readAloudNumber += contentList[nowSpeak].length + 1 - paragraphStartPos
    paragraphStartPos = 0

    // AI背景音乐段落计数和切换
    checkBgmSwitch()

    if (nowSpeak < contentList.lastIndex) {
        nowSpeak++
    } else {
        nextChapter()
    }
}

// 第290-330行：预缓存逻辑（确保与阅读界面一致）
private fun downloadAndPlayAudios() {
    for (i in 1..limit) {
        val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, targetIndex) ?: break

        // 使用与阅读界面完全一致的内容获取方式
        val contentProcessor = ContentProcessor.get(book.name, book.origin)
        val rawContent = BookHelp.getContent(book, chapter) ?: break

        val bookContent = contentProcessor.getContent(
            book,
            chapter,
            rawContent,
            includeTitle = false  // 与阅读界面一致
        )

        // 处理段落
        val segments = mutableListOf<String>()
        bookContent.textList.forEach { content ->
            segments.add(content.trim())
        }

        // 为每个段落后生成TTS音频
        segments.forEach { segmentText ->
            val fileName = getFileNameHelper(chapter.title, segmentText)
            val speakText = segmentText.replace(AppPattern.notReadAloudRegex, "")

            if (speakText.isEmpty()) {
                createSilentSound(fileName)
            } else if (!hasSpeakFile(fileName)) {
                val inputStream = getSpeakStream(httpTts, speakText)
                if (inputStream != null) {
                    createSpeakFile(fileName, inputStream)
                } else {
                    createSilentSound(fileName)
                }
            }
        }
    }
}
```

### 文本一致性保证

**三处文本来源必须完全一致**：

1. **阅读界面显示**：`TextChapter.getNeedReadAloud()` → `ContentProcessor.getContent()`
2. **朗读列表**：`contentList` 来自 `TextChapter.getNeedReadAloud()`
3. **预缓存**：`ContentProcessor.getContent(book, chapter, rawContent, includeTitle = false)`

**ContentProcessor处理流程**：
```kotlin
fun getContent(
    book: Book,
    chapter: BookChapter,
    content: String,
    includeTitle: Boolean = false,
    useReplace: Boolean = true,
    chineseConvert: Boolean = true,
    reSegment: Boolean = true
): BookContent {
    var mContent = content

    // 1. 去除重复标题
    val fileName = chapter.getFileName("nr")
    if (!removeSameTitleCache.contains(fileName)) {
        val title = chapter.title.escapeRegex().replace(spaceRegex, "\\s*")
        val matcher = Pattern.compile("^(\\s|\\p{P}|${name})*${title}(\\s)*")
            .matcher(mContent)
        if (matcher.find()) {
            mContent = mContent.substring(matcher.end())
        }
    }

    // 2. 重新分段（如果启用）
    if (reSegment && book.getReSegment()) {
        mContent = ContentHelp.reSegment(mContent, chapter.title)
    }

    // 3. 简繁转换
    if (chineseConvert) {
        when (AppConfig.chineseConverterType) {
            1 -> mContent = ChineseUtils.t2s(mContent)
            2 -> mContent = ChineseUtils.s2t(mContent)
        }
    }

    // 4. 应用替换规则
    if (useReplace && book.getUseReplaceRule()) {
        getContentReplaceRules().forEach { rule ->
            mContent = mContent.replace(rule.regex, rule.replacement)
        }
    }

    // 5. 添加标题（如果启用）
    if (includeTitle) {
        mContent = chapter.getDisplayTitle(
            getTitleReplaceRules(),
            useReplace = useReplace && book.getUseReplaceRule()
        ) + "\n" + mContent
    }

    // 6. 段落分割和缩进
    val contents = arrayListOf<String>()
    mContent.split("\n").forEach { str ->
        val paragraph = str.trim()
        if (paragraph.isNotEmpty()) {
            if (contents.isEmpty() && includeTitle) {
                contents.add(paragraph)
            } else {
                contents.add("${ReadBookConfig.paragraphIndent}$paragraph")
            }
        }
    }

    return BookContent(sameTitleRemoved, contents, effectiveReplaceRules)
}
```

---

## 🔧 配置说明

### AI提供商配置

**用户需在听书设置 → AI提供商中配置**：

1. **提供商名称**：自定义名称（如"OpenAI"、"DeepSeek"等）
2. **API URL**：OpenAI兼容格式的API地址
   - 示例：`https://api.openai.com/v1`
   - 示例：`https://api.deepseek.com`
3. **API Key**：访问API的密钥
4. **模型ID**：要使用的模型名称
   - 示例：`gpt-3.5-turbo`
   - 示例：`deepseek-chat`
5. **启用状态**：开关该提供商

### AI提示词配置

**默认提示词**（在App.kt中初始化）：

```
你是一个专业的背景音乐推荐助手。请根据提供的小说信息和正文片段，分析其场景类型和情感氛围，并推荐一个合适的背景音乐文件名。

一、书籍分类参考：
- 仙侠玄幻：修练场景、突破境界、炼丹炼器、宗门争斗、秘境探险
- 武侠江湖：江湖恩怨、武学修炼、门派斗争、恩怨情仇、行走江湖
- 都市现代：职场生活、都市恋情、商业竞争、家庭日常、现代科技
- 古代言情：宫廷权谋、深宫后宫、家族恩怨、儿女情长、朝堂风云
- 悬疑推理：案件侦破、线索追踪、真相揭秘、紧张对峙、推理过程
- 战争军事：战场厮杀、军营生活、战略部署、攻城略地、军旅生涯
- 历史传奇：历史事件、名人传记、朝代更替、历史转折、家国情怀
- 科幻未来：星际旅行、未来科技、外星文明、时空穿越、机器人世界
- 轻松日常：校园生活、朋友聚会、家庭温馨、休闲时光、轻松幽默
- 恐怖惊悚：诡异事件、恐怖氛围、悬疑惊悚、心理恐惧、灵异现象

二、情感氛围类型：
- 悲伤哀婉：离别、死亡、牺牲、追忆、遗憾
- 欢快轻松：庆祝、成功、相聚、幽默、开心
- 紧张刺激：战斗、逃跑、追击、危机、紧急
- 平静舒缓：修炼、思考、休息、欣赏、宁静
- 浪漫温馨：表白、约会、拥抱、深情、甜蜜
- 愤怒激昂：复仇、呐喊、爆发、热血、抗争
- 神秘诡谲：未知、阴谋、谜团、诡异、悬疑
- 壮阔豪迈：宏大场面、英雄事迹、史诗感、震撼、气势
- 忧郁惆怅：思念、孤独、迷茫、感伤、失落
- 温暖治愈：关怀、陪伴、安慰、感动、希望

三、推荐背景音乐文件名规范：
1. 使用中文拼音或英文单词，简洁明了
2. 文件名应体现场景类型或情感氛围
3. 示例：
   - 打斗场景：战斗(douzhan)、激战(jizhan)、交锋、battle、fight
   - 修练场景：修炼、突破、悟道、cultivation、meditation
   - 感情场景：浪漫、深情、恋爱、romantic、love
   - 悬疑场景：悬疑、神秘、紧张、mystery、suspense
   - 轻松日常：轻松、日常、愉快、relax、casual
   - 悲伤场景：悲伤、哀伤、忧郁、sad、melancholy
   - 宏大场景：史诗、壮阔、震撼、epic、grand
   - 温馨场景：温馨、温暖、治愈、warm、healing

四、分析要求：
1. 优先考虑正文片段的场景类型和情感氛围
2. 结合书籍分类进行综合判断
3. 只返回推荐文件名，不要包含任何解释
4. 文件名不含路径或扩展名

请直接返回推荐的文件名。
```

### 数据库配置

```kotlin
// AppDatabase.kt
@Database(
    entities = [
        Book::class,
        BookChapter::class,
        BookSource::class,
        SearchBook::class,
        ReplaceRule::class,
        BookGroup::class,
        RssSource::class,
        RssStar::class,
        RssRead::class,
        HttpTTS::class,
        BookProgress::class,
        KeyboardShortcutConfig::class,
        ReadBookConfig::class,
        BookshelfConfig::class,
        BgmAIProvider::class,  // 新增：AI提供商
        BgmAIPrompt::class     // 新增：AI提示词
    ],
    version = 89,
    exportSchema = false
)
```

---

## 🐛 常见问题与解决方案

### 最近修复记录（2026-01-24）

**1. 修复朗读停止键失效问题**
- 问题：点击停止键后TTS音频仍在播放，重新播放时出现多个音频重叠
- 解决：
  - TTSReadAloudService.kt：在play()方法添加 `if (!isRun) return` 检查
  - 增强onDestroy()清理逻辑：取消协程、停止播放、清理TTS队列
  - HttpReadAloudService.kt：同样添加isRun检查和完善的onDestroy()清理

**2. 修复线程安全问题（CalledFromWrongThreadException）**
- 问题：切换AI提示词时崩溃，提示UI操作在非主线程
- 解决：
  - BgmAIPromptAdapter.kt：使用 `withContext(Dispatchers.Main)` 包装UI更新
  - 确保notifyDataSetChanged()、submitList()等在主线程执行

**3. 统一UI样式**
- 问题：编辑提示词界面标题样式与其他对话框不一致
- 解决：
  - dialog_bgm_ai_prompt_edit.xml：替换Toolbar为TextView
  - 设置标题为16sp，使用@color/primaryText统一字体颜色

**4. 优化BGM AI提示词内容**
- 修改：提示词文本从"五、当前可用的背景音乐文件列表："改为"五、当前可用的背景音乐文件列表如下请根据正文内容直接返回以下文件名其中一个："
- 目的：提高AI返回文件名的准确率

### 编译错误

**问题1：Unresolved reference 'withContext'**
```kotlin
// 解决方案：添加导入
import kotlinx.coroutines.withContext
```

**问题2：Content is not allowed in prolog（XML解析错误）**
```
错误：dialog_bgm_ai_provider_edit.xml:1:1: Content is not allowed in prolog.
原因：XML文件开头有非法字符（如中文"构建"）
解决：删除XML文件开头所有非<?xml>的内容
```

**问题3：Kotlin编译错误**
```bash
# 解决方案：清理构建缓存
./gradlew clean
rm -rf .gradle/
rm -rf app/build/
```

### 运行时错误

**问题1：AI切换不生效**
- 检查1：`AppConfig.bgmAIEnabled` 是否为 true
- 检查2：`AppConfig.bgmAIParagraphInterval` 是否正确（默认3）
- 检查3：AI提供商是否已配置并启用
- 检查4：背景音乐文件夹是否已加载（BgmManager.loadBgmFiles()）
- 检查5：日志中是否有 "AI背景音乐: 触发切换分析"

**问题2：预缓存文本与阅读不一致**
- 检查：是否使用 `ContentProcessor.get(book.name, book.origin)`
- 检查：是否调用 `contentProcessor.getContent(book, chapter, rawContent, includeTitle = false)`
- 检查：是否使用 `bookContent.textList` 而非简单的 split("\n")

**问题3：AI返回的文件名找不到**
- 可能原因：文件名不匹配（空格、大小写等）
- 解决方案：模糊匹配 `BgmManager.findMediaItemIndexFuzzy()`
- 日志查看：搜索 "精确匹配失败，尝试模糊匹配"

---

## 🔍 调试方法

### 查看日志

**方法1：通过AppLog**
```kotlin
// 添加调试日志
AppLog.putDebug("AI背景音乐: 段落计数器=$bgmParagraphCounter")
AppLog.put("AI背景音乐: 切换失败\n${e.localizedMessage}", e)
```

**方法2：通过LogUtils**
```kotlin
// 添加详细日志
LogUtils.d("HttpReadAloud", "AI背景音乐: 触发切换分析")
```

**方法3：Android Studio Logcat**
```
过滤：BaseReadAloudService
过滤：BgmManager
过滤：BgmAIService
```

### 关键日志点

```kotlin
// BaseReadAloudService.kt
"AI背景音乐: 段落计数器=$bgmParagraphCounter, 设置间隔=$interval"
"AI背景音乐: 触发切换分析"
"AI背景音乐: 收集到的文本长度=${contentToAnalyze.length}"

// BgmManager.kt
"AI背景音乐: 开始分析文本，长度=${content.length}"
"AI背景音乐: AI返回推荐文件名=$recommendedFileName"
"AI背景音乐: ✅ 精确匹配成功，切换到: $recommendedFileName"
"AI背景音乐: 精确匹配失败，尝试模糊匹配，推荐文件名=$recommendedFileName"
"AI背景音乐: ✅ 模糊匹配成功，推荐文件名=$recommendedFileName，实际切换到: $fileName"
"AI背景音乐: ❌ 未找到匹配的音频文件，推荐文件名=$recommendedFileName，可用文件: ${allAudioFiles.map { it.first }}"
"AI背景音乐: ⚠️ AI未返回推荐文件名"
"AI背景音乐: ❌ 切换失败\n${e.localizedMessage}"
"AI背景音乐: 分析结束"

// HttpReadAloudService.kt
"TTS预下载音频: $fileName"
"TTS缓存命中: $fileName"
```

---

## 📁 项目结构（关键文件）

```
/workspace/
├── app/
│   ├── src/main/java/io/legado/app/
│   │   ├── service/
│   │   │   ├── BaseReadAloudService.kt      # 朗读服务基类（包含AI切歌逻辑）
│   │   │   ├── HttpReadAloudService.kt     # HTTP TTS朗读服务
│   │   │   ├── BgmManager.kt              # 背景音乐管理器
│   │   │   ├── BgmAIService.kt            # AI分析服务
│   │   │   └── TTSReadAloudService.kt     # 本地TTS朗读服务
│   │   ├── data/
│   │   │   ├── entities/
│   │   │   │   ├── BgmAIProvider.kt       # AI提供商实体
│   │   │   │   └── BgmAIPrompt.kt          # AI提示词实体
│   │   │   └── appDb/
│   │   │       ├── AppDatabase.kt          # 数据库配置
│   │   │       ├── BgmAIProviderDao.kt    # AI提供商DAO
│   │   │       └── BgmAIPromptDao.kt       # AI提示词DAO
│   │   ├── help/book/
│   │   │   └── ContentProcessor.kt        # 内容处理器（文本一致性核心）
│   │   └── ui/book/read/config/
│   │       ├── BgmConfigDialog.kt          # BGM设置对话框
│   │       ├── BgmAIPromptEditDialog.kt   # AI提示词编辑对话框
│   │       └── BgmAIProviderEditDialog.kt # AI提供商编辑对话框
│   ├── src/main/res/
│   │   ├── xml/
│   │   │   └── pref_config_aloud.xml     # 朗读设置配置
│   │   └── layout/
│   │       ├── dialog_read_aloud.xml       # 朗读对话框（包含BGM按钮）
│   │       └── dialog_bgm_ai_*.xml       # AI相关对话框布局
│   └── build.gradle.kt                    # Gradle构建配置
├── newapk/
│   └── legado_app_3.26.6-beta.{commit数}.apk  # 最新构建版本
├── Bgm_Reference/                         # BGM分支参考代码
└── WAKEUP.md                             # 本文档
```

---

## 🔧 环境验证与故障排除

### ✅ 环境验证步骤
当云构建服务重新启动时，请按以下顺序验证环境配置：

```bash
# 1. 检查Java环境
echo "JAVA_HOME: $JAVA_HOME"
java -version

# 2. 检查Android SDK组件
/opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --list_installed | grep -E "(platforms|build-tools)"

# 3. 检查Gradle环境
./gradlew --version

# 4. 检查必要目录权限
ls -la /opt/android-sdk/build-tools/35.0.0/
ls -la /opt/android-sdk/platforms/android-35/
```

### 🚨 常见问题及解决方案

#### 问题1：`Dispatchers` 未解析
**症状**：编译错误 `Unresolved reference: Dispatchers`
**原因**：协程库依赖不完整
**解决**：在 `app/build.gradle` 中添加：
```gradle
implementation "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2"
```

#### 问题2：NDK版本不匹配
**症状**：构建失败，提示NDK相关问题
**解决**：在 `app/build.gradle` 的 `android` 块中添加：
```gradle
ndkVersion "26.1.10909125"
```

#### 问题3：Android SDK组件缺失
**症状**：提示缺少 `build-tools;35.0.0` 或 `platforms;android-35`
**解决**：运行环境初始化脚本：
```bash
chmod +x setup-android-sdk.sh
./setup-android-sdk.sh
```

或手动安装：
```bash
# 接受许可
yes | sdkmanager --licenses
# 安装必需组件
sdkmanager "build-tools;35.0.0" "platforms;android-35" "platforms;android-36"
```

#### 问题4：构建卡住或超时
**症状**：Gradle构建进程长时间无响应
**解决**：
1. 清理Gradle缓存：`./gradlew clean`
2. 停止Gradle守护进程：`./gradlew --stop`
3. 重新构建：`./gradlew assembleAppRelease --stacktrace`

#### 问题5：APK复制失败
**症状**：构建成功但APK未复制到 `newapk/` 目录
**解决**：手动复制：
```bash
SRC_APK=$(find app/build/outputs/apk/app/release -name "*.apk" | head -1)
COMMIT_COUNT=$(git rev-list --count HEAD)
cp "$SRC_APK" "newapk/legado_app_3.26.6-beta.${COMMIT_COUNT}.apk"
```

### 📋 环境健康检查脚本
创建 `check-environment.sh` 文件进行一键检查：
```bash
#!/bin/bash
echo "=== Legado构建环境健康检查 ==="
echo "1. Java环境..."
java -version 2>&1 | grep -E "(version|Java)"

echo "2. Android SDK..."
ls -d /opt/android-sdk/build-tools/35.0.0 2>/dev/null && echo "✅ build-tools 35.0.0 存在"
ls -d /opt/android-sdk/platforms/android-35 2>/dev/null && echo "✅ platforms;android-35 存在"

echo "3. Gradle版本..."
./gradlew --version 2>&1 | grep "Gradle"

echo "4. 项目结构..."
[ -f "app/build.gradle" ] && echo "✅ app/build.gradle 存在"
[ -f "gradle.properties" ] && echo "✅ gradle.properties 存在"

echo "=== 检查完成 ==="
```

### ⚡ 快速恢复流程
如果环境异常，按以下顺序恢复：
1. **执行健康检查**：`bash check-environment.sh`
2. **安装缺失组件**：根据检查结果运行对应安装命令
3. **清理重建**：`./gradlew clean && ./gradlew assembleAppRelease`
4. **验证输出**：确认APK已生成并复制到 `newapk/` 目录

---

## 🎯 快速唤醒命令

```bash
# 查看唤醒文档
cat /workspace/WAKEUP.md

# 查看最新提交
cd /workspace && git log --oneline -5

# 查看当前分支状态
cd /workspace && git status

# 快速构建APK
cd /workspace && ./gradlew assembleAppRelease

# 构建Bgm分支APK
cd /workspace/Bgm_Reference && ./gradlew assembleappRelease

# 复制APK到newapk目录
cp /workspace/Bgm_Reference/app/build/outputs/apk/app/release/*.apk /workspace/newapk/
```

---

## 📌 重要提醒

1. **文本一致性优先**：任何修改预缓存或朗读逻辑时，必须确保使用`ContentProcessor`
2. **日志完整性**：关键操作必须添加日志（LogUtils和AppLog）
3. **配置检查**：AI切歌功能依赖多个配置项，确保都已正确配置
4. **异常处理**：AI分析、BGM切换等网络操作必须有完善的异常处理
5. **版本管理**：每次构建后更新commit数和版本号
6. **远程同步**：重要修改后及时推送到`origin/legado_Bgm`
7. **计数器逻辑**：段落计数器在TTS播放完一个段落后才增加
8. **日志可见性**：计数器等调试信息使用AppLog.putDebug()输出
9. **控制分离**：上段/下段按钮只控制TTS段落，背景音乐单独控制
10. **同步控制**：听书播放/暂停/恢复自动同步背景音乐

---

**分支**: legado_Bgm
**远程仓库**: origin/legado_Bgm
**最后更新**: 2026-01-23
