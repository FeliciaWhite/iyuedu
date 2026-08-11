# 根据转发器激活系统TTS引擎

## 功能说明

当使用HTTP转发器进行TTS朗读时，自动激活对应包名的系统TTS引擎，让转发器能正常工作。

**核心原理**：通过 `TextToSpeech(context, listener, packageName)` 初始化指定引擎，让Android系统识别这个TTS引擎正在被使用，而不需要打开前台Activity或手动在系统设置中选择引擎。

---

## 完整代码

### TtsEngineActivator.kt

```kotlin
package io.legado.app.help.tts

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener

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
    fun activateTtsEngine(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        
        // 检查是否已安装
        if (!isTtsEngineInstalled(context, packageName)) {
            return false
        }
        
        // 检查是否已经是默认引擎
        val currentDefault = getDefaultTtsEnginePackageName(context)
        if (currentDefault == packageName) {
            return true
        }
        
        // 通过初始化TextToSpeech来"激活"引擎
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
                    success = true
                }
            }
        }
        
        return try {
            // 尝试使用指定引擎初始化TTS
            // 第三个参数是包名，这会让系统识别这个引擎
            val tts = TextToSpeech(context, initListener, packageName)
            // 等待初始化完成
            Thread.sleep(500)
            tts.shutdown()
            success
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 从转发器URL自动激活TTS引擎
     */
    fun activateFromHttpTtsUrl(context: Context, httpTtsUrl: String?): Boolean {
        if (httpTtsUrl.isNullOrBlank()) {
            return false
        }
        
        val packageName = extractPackageNameFromUrl(httpTtsUrl)
        if (packageName == null) {
            return false
        }
        
        return activateTtsEngine(context, packageName)
    }
}
```

---

## 调用流程图

```
┌─────────────────────────────────────────────────────────────┐
│                    朗读开始                                 │
└─────────────────────┬─────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────────────┐
│ activateFromHttpTtsUrl(context, httpTtsUrl)                │
└─────────────────────┬─────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────────────┐
│ extractPackageNameFromUrl(url)                              │
│                                                             │
│ 输入: http://localhost:1221/api/tts?engine=com.xxx&text=.. │
│ 输出: com.xxx                                               │
└─────────────────────┬─────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────────────┐
│ activateTtsEngine(context, packageName)                     │
│                                                             │
│ 1. 检查是否已安装                                          │
│ 2. 检查是否已是默认引擎                                     │
│ 3. 调用 tryInitializeTtsEngine()                            │
└─────────────────────┬─────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────────────┐
│ tryInitializeTtsEngine(context, packageName)                │
│                                                             │
│ TextToSpeech(context, listener, packageName)  ← 关键代码    │
│                                                             │
│ 这会让系统识别这个TTS引擎被使用                             │
└─────────────────────────────────────────────────────────────┘
```

---

## 给其他APP添加此功能

### 1. 添加权限 (AndroidManifest.xml)

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

### 2. 复制激活器类

直接复制上文的 `TtsEngineActivator.kt` 到你的项目中，修改包名即可。

### 3. 在朗读开始时调用

```kotlin
// 假设你有一个转发器URL
val httpTtsUrl = "http://localhost:1221/api/tts?engine=com.example.tts&text=..."

// 提取包名并激活
val packageName = TtsEngineActivator.extractPackageNameFromUrl(httpTtsUrl)
if (packageName != null) {
    TtsEngineActivator.activateTtsEngine(context, packageName)
}
```

### 4. 关键点总结

| 要点 | 说明 |
|------|------|
| 使用 `TextToSpeech` | `TextToSpeech(context, listener, "目标包名")` |
| 第三参数是包名 | 不是引擎名，是Android应用的包名 |
| 不需要root权限 | 纯系统API调用 |
| 不会弹出UI | 后台静默激活 |
| URL格式 | 支持 `?engine=包名` 或 `&engine=包名` |

### 5. 支持的URL格式

```
http://localhost:1221/api/tts?engine=com.github.jing332.tts_server_android.dev&text=...
http://localhost:1221/api/tts?text=...&engine=com.wobble.speechengine
```

只要URL中包含 `engine=包名` 参数即可自动提取。

### 6. 已知支持的TTS应用包名

```kotlin
val knownTtsPackages = listOf(
    "com.github.jing332.tts_server_android",      // TTS Server (Play商店版)
    "com.github.jing332.tts_server_android.dev",  // TTS Server (测试版)
    "com.github.jing332.tts_server",               // TTS Server
    "com.wobble.speechengine",                     // Speech Engine
    "com.ideabus.testtts"                          // 测试用TTS
)
```

---

## 注意事项

1. **不是替换默认引擎**：此方法只是让系统"知道"这个TTS引擎在被使用，不会改变系统默认TTS设置
2. **需要APP保持运行**：TTS引擎激活后，APP需要保持后台运行，否则可能被系统回收
3. **转发器需要支持**：目标TTS转发器APP需要监听TTS请求并合成音频
4. **包名格式验证**：会自动验证提取的包名是否符合Android包名规范
