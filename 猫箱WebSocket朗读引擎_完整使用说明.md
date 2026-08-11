# 猫箱 WebSocket 朗读引擎 — 完整使用说明

## 一、基本原理

本方案通过在阅读 APP 的 **HTTP 朗读引擎**的 `url` 字段填写 `@js:` 前缀的 JavaScript 代码，完全接管 TTS 合成流程：

1. JS 代码负责连接猫箱 WebSocket API
2. 支持按规则拆分文本（如引号内外用不同发音人）
3. 多段音频在 JS 里合并成一个 `byte[]` 返回
4. APP 播放这个合并后的音频，对外就是一个普通文件

---

## 二、JS 环境内置对象

在 `@js:` 脚本中，以下对象/变量可以直接使用：

| 名称 | 类型 | 说明 |
|---|---|---|
| `ws` | `TtsWebSocketHelper` | WebSocket 合成核心对象 |
| `speakText` | `String` | APP 传入的当前段落文字 |
| `speechRate` | `Int` | APP 朗读设置里的语速值（0~100） |

---

## 三、`ws.maoxiang()` 方法详解

### 完整参数列表

```javascript
ws.maoxiang(
    wsUrl,              // String  : WebSocket 连接地址（已含 voice/format 等 query 参数）
    speakText,          // String  : 要合成的文本
    voice,              // String  : 发音人 ID，如 "zh_female_wenroutaozi_uranus_bigtts"
    format,             // String  : 音频格式，如 "mp3"
    sampleRate,         // Number  : 采样率，如 24000
    speechRateFactor,   // Number  : 语速因子，如 1.15（范围约 0.5~2.0）
    pitchValue,         // Number  : 音调值，如 0
    appkey,             // String  : appkey，如 "WQuVLKMGRo"
    timeoutMs,          // Number  : 超时毫秒，如 30000
    extraPayload        // String? : 额外字段（JSON字符串），可选
);
```

**返回值**：`byte[]`（音频二进制数据），失败会抛异常，上层自动重试。

---

## 四、API 请求结构 & 内置字段

`ws.maoxiang()` 发给猫箱服务器的 `StartTask` 消息结构如下：

```json
{
  "appkey": "WQuVLKMGRo",
  "event": "StartTask",
  "namespace": "BidirectionalTTS",
  "payload": "{...}"
}
```

其中 `payload` 是一个 JSON 字符串，**内置字段**由 `maoxiang` 的前 9 个参数自动生成：

```json
{
  "audio_config": {
    "format": "mp3",
    "sample_rate": 24000
  },
  "extra": {
    "post_process": {
      "pitch": 0,
      "speech_rate": 1.15
    }
  },
  "speaker": "zh_female_wenroutaozi_uranus_bigtts"
}
```

### 内置字段对照表

| 参数名 | 对应 payload 字段 | 说明 |
|---|---|---|
| `format` | `audio_config.format` | 音频编码格式 |
| `sampleRate` | `audio_config.sample_rate` | 音频采样率 |
| `pitchValue` | `extra.post_process.pitch` | 音调调整 |
| `speechRateFactor` | `extra.post_process.speech_rate` | 语速倍率 |
| `voice` | `speaker` | 发音人 ID |

**注意**：`appkey`、`event`、`namespace` 不在 payload 里，它们在消息外层。

---

## 五、`extraPayload` — 添加任意自定义字段

### 设计目的

猫箱 API 以后可能会增加新字段（如情感、风格、控制标签等）。如果每次加字段都要改 APP 源码、重新编译 APK，非常麻烦。

`extraPayload` 就是解决这个问题的：**你直接在 JS 里写 JSON 字符串，源码自动把它合并到 `payload` 根对象，不需要再改 Kotlin 代码。**

### 使用方法

`extraPayload` 是一个 **JSON 字符串**，放在 `maoxiang` 的第 10 个参数位置。

#### 示例 1：添加提示词 `context_texts`

```javascript
var extra = '{"context_texts":["男性旁白朗读"]}';
var audio = ws.maoxiang(wsUrl, text, voice, format, sampleRate, rate, pitch, appkey, timeout, extra);
```

最终 payload 会变成：

```json
{
  "audio_config": { "format": "mp3", "sample_rate": 24000 },
  "extra": { "post_process": { "pitch": 0, "speech_rate": 1.15 } },
  "speaker": "zh_female_wenroutaozi_uranus_bigtts",
  "context_texts": ["男性旁白朗读"]
}
```

#### 示例 2：同时添加多个字段

```javascript
var extra = JSON.stringify({
    "context_texts": ["用夹子音朗读"],
    "some_future_field": "value",
    "emotion": "happy"
});
var audio = ws.maoxiang(wsUrl, text, voice, format, sampleRate, rate, pitch, appkey, timeout, extra);
```

#### 示例 3：动态拼接（不需要 JSON.stringify 时）

```javascript
var prompt = '男性旁白朗读';
var extra = prompt ? '{"context_texts":["' + prompt + '"]}' : null;
var audio = ws.maoxiang(wsUrl, text, voice, format, sampleRate, rate, pitch, appkey, timeout, extra);
```

**不需要传额外字段时，最后一个参数可以写 `null` 或空字符串 `""`，也可以只传 9 个参数。**

---

## 六、音频拼接辅助方法

### `ws.newBuffer()` — 创建缓存

```javascript
var out = ws.newBuffer();   // 相当于 Java 的 new ByteArrayOutputStream()
out.write(audio1);
out.write(audio2);
return out.toByteArray();   // 合并后的完整音频
```

### `ws.mergeAudio(audio1, audio2, ...)` — 直接合并多个 byte[]

```javascript
var audio1 = ws.maoxiang(...);
var audio2 = ws.maoxiang(...);
var merged = ws.mergeAudio(audio1, audio2);
return merged;
```

**注意**：猫箱返回的是流式裸 MP3 帧（无 ID3 头尾），直接二进制拼接是安全的，播放器会自动衔接。

---

## 七、完整配置示例（单发音人 + 提示词）

最简单的用法：所有文字都用同一个发音人，并加上提示词。

```json
[
  {
    "id": 1,
    "name": "猫箱-温柔桃子单发音人",
    "url": "@js:\nvar BASE_URL = 'wss://audio5-normal-hl.myparallelstory.com/internal/api/v1/ws';\nvar format = 'mp3';\nvar sampleRate = 24000;\nvar appkey = 'WQuVLKMGRo';\nvar timeout = 30000;\nvar pitch = 0;\nvar rate = 1.15;\nvar voice = 'zh_female_wenroutaozi_uranus_bigtts';\nvar deviceId = String(Math.floor(1e12 + 9e12 * Math.random()));\nvar aid = String(Math.floor(1e12 + 9e12 * Math.random()));\nvar query = 'voice=' + voice + '&format=' + format + '&sampleRate=' + sampleRate + '&appkey=' + appkey;\nvar wsUrl = BASE_URL + '?' + query + '&ssmix=&aid=' + aid + '&device_id=' + deviceId;\nvar extra = '{\"context_texts\":[\"温柔地朗读\"]}';\nws.maoxiang(wsUrl, speakText, voice, format, sampleRate, rate, pitch, appkey, timeout, extra);",
    "contentType": "websocket/maoxiang",
    "concurrentRate": "0",
    "lastUpdateTime": 1748582400001,
    "loginCheckJs": ""
  }
]
```

---

## 八、完整配置示例（双发音人 + 引号切换 + 提示词）

这是当前在用的完整版：按中文双引号拆分，引号外用桃子（男性旁白），引号内用 VV（夹子音），最后合并。

```json
[
  {
    "id": 1748582400001,
    "name": "猫箱-温柔桃子/VV交替",
    "url": "@js:\n/* ========================================================== */\n/*  发音人分配规则：                                            */\n/*    双引号以内的文字  →  用 VV 朗读                           */\n/*    双引号以外的文字  →  用温柔桃子朗读                       */\n/* ========================================================== */\n\nvar BASE_URL = 'wss://audio5-normal-hl.myparallelstory.com/internal/api/v1/ws';\nvar AUDIO_FORMAT = 'mp3';\nvar SAMPLE_RATE = 24000;\nvar APP_KEY = 'WQuVLKMGRo';\nvar TIMEOUT_MS = 30000;\nvar PITCH_VALUE = 0;\nvar SPEED_BOOST = 1.15;\nvar rate = SPEED_BOOST;\n\nvar VOICE_NARRATOR = 'zh_female_wenroutaozi_uranus_bigtts';\nvar VOICE_DIALOG   = 'zh_female_vv_uranus_bigtts';\nvar PROMPT_NARRATOR = '男性旁白朗读';\nvar PROMPT_DIALOG   = '用夹子音朗读';\n\n/* ---------- 按中文双引号拆分 ---------- */\nvar segments = [];\nvar text = speakText;\nvar idx = 0;\nwhile (idx < text.length) {\n    var qStart = text.indexOf('\\u201C', idx);\n    if (qStart === -1) {\n        if (idx < text.length) {\n            segments.push({txt: text.substring(idx), voice: VOICE_NARRATOR});\n        }\n        break;\n    }\n    if (qStart > idx) {\n        segments.push({txt: text.substring(idx, qStart), voice: VOICE_NARRATOR});\n    }\n    var qEnd = text.indexOf('\\u201D', qStart + 1);\n    if (qEnd === -1) qEnd = text.length - 1;\n    segments.push({txt: text.substring(qStart, qEnd + 1), voice: VOICE_DIALOG});\n    idx = qEnd + 1;\n}\nif (segments.length === 0) {\n    segments.push({txt: text, voice: VOICE_NARRATOR});\n}\n\n/* ---------- 分别合成并合并 ---------- */\nvar deviceId = String(Math.floor(1e12 + 9e12 * Math.random()));\nvar aid = String(Math.floor(1e12 + 9e12 * Math.random()));\nvar out = ws.newBuffer();\n\nfor (var i = 0; i < segments.length; i++) {\n    var seg = segments[i];\n    if (!seg.txt || seg.txt.length === 0) continue;\n    var query = 'voice=' + seg.voice + '&format=' + AUDIO_FORMAT + '&sampleRate=' + SAMPLE_RATE + '&appkey=' + APP_KEY;\n    var wsUrl = BASE_URL + '?' + query + '&ssmix=&aid=' + aid + '&device_id=' + deviceId;\n    var prompt = (seg.voice === VOICE_NARRATOR) ? PROMPT_NARRATOR : PROMPT_DIALOG;\n    var extra = prompt ? '{\"context_texts\":[\"' + prompt + '\"]}' : null;\n    var audio = ws.maoxiang(wsUrl, seg.txt, seg.voice, AUDIO_FORMAT, SAMPLE_RATE, rate, PITCH_VALUE, APP_KEY, TIMEOUT_MS, extra);\n    if (audio && audio.length > 0) {\n        out.write(audio);\n    }\n}\nout.toByteArray();",
    "contentType": "websocket/maoxiang",
    "concurrentRate": "0",
    "lastUpdateTime": 1748582400001,
    "loginCheckJs": ""
  }
]
```

---

## 九、添加新字段速查表（以后不需要改源码）

| 你想加的字段 | extraPayload 写法 |
|---|---|
| 提示词 | `'{"context_texts":["用悲伤语气朗读"]}'` |
| 假设以后有情感参数 | `'{"emotion":"sad"}'` |
| 假设以后有风格参数 | `'{"style":"narration","context_texts":["旁白"]}'` |
| 多个字段一起加 | `'{"context_texts":["旁白"],"emotion":"calm","speed":"slow"}'` |

**核心原则**：把你想加的字段写成 JSON 字符串，传给 `maoxiang` 第 10 个参数，源码会自动合并到 API payload 里。

---

## 十、故障排查

| 现象 | 原因 | 解决 |
|---|---|---|
| `ReferenceError: "ws"未定义` | 旧版 APP 没有注入 `ws` 对象 | 安装最新编译的 APK |
| `TypeError: 无法读取 ByteArrayOutputStream` | JS 沙箱无法访问 `java.io` | 用 `ws.newBuffer()` 代替 |
| 弹窗报错后停止朗读 | 旧版 `@js:` 异常后继续走 HTTP 分支 | 安装最新 APK，已修复 |
| 网络错误不自动重试 | APP 设置里重试次数为 0 | 去 APP 设置 → 朗读 → 调高 TTS 重试次数 |
| 音频拼接后播放异常 | 极少见，可能是非流式 MP3 | 确认 API 返回的是流式裸帧 |

---

## 十一、重要注意事项

1. **不要自己 `new java.io.ByteArrayOutputStream()`**，Rhino JS 沙箱里没有 `java.io` 包，必须用 `ws.newBuffer()`。
2. **双引号拆分时用的是中文双引号** `“”`（Unicode `\u201C` / `\u201D`），不是英文双引号 `""`。
3. **每多一段就多一次 WebSocket 请求**，如果一段话里引号很多，合成会变慢。正常情况下 2~3 段几乎无感知。
4. **缓存按整段文字算 hash**：不管你内部拆成几段，只要 `speakText` 没变，下次播放就直接命中缓存。
5. **提示词效果取决于猫箱 API 的支持程度**，如果某些词无效，可以换其他描述试试。
