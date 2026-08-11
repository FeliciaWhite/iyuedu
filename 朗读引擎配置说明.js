/* ============================================================
 * 猫箱朗读引擎 — 配置说明与参数调节文档
 * ============================================================
 * 这个文件不是给 APP 直接读取的，是专门给你看、给你改的说明文档。
 * 看懂之后，把对应数值改到 "猫箱_温柔桃子_朗读引擎.json" 里即可。
 * ============================================================ */


/* -------------------- 一、基础信息 -------------------- */
const ENGINE_ID        = 1748582400001;                       // 引擎唯一ID，不要和其他引擎重复
const ENGINE_NAME      = "猫箱-温柔桃子(高级)";                // 在阅读APP里显示的名字
const CONTENT_TYPE     = "websocket/maoxiang";                // 标识这是猫箱WebSocket引擎
const CONCURRENT_RATE  = "0";                                 // 并发限制，0=不限制


/* -------------------- 二、服务器连接信息 -------------------- */
const WS_HOST      = "wss://audio5-normal-hl.myparallelstory.com";
const VOICE_ID     = "zh_female_wenroutaozi_uranus_bigtts";   // 【音色ID】想换声音就改这里
const AUDIO_FORMAT = "mp3";                                   // 【音频格式】mp3 或 pcm
const SAMPLE_RATE  = 24000;                                   // 【采样率】24000/32000/44100/48000
const APP_KEY      = "WQuVLKMGRo";                            // 【AppKey】身份验证用
const TIMEOUT_MS   = 30000;                                   // 【超时】30秒没响应就断开


/* -------------------- 三、语速调节（重点！） -------------------- */
// 阅读APP传过来的原始语速值叫 speechRate，默认一般是 50
// 把它转换成"倍率"再额外加速，就是最终传给服务器的语速

const SPEED_BASE   = 50.0;     // 阅读APP的默认语速基准值
const SPEED_BOOST  = 1.15;     // 【全局提速系数】★ 改这个来整体调节快慢 ★
                               // 1.0 = 不快不慢（APP设多少就是多少）
                               // 1.15 = 比APP设置快15%（当前默认值）
                               // 1.30 = 比APP设置快30%
                               // 0.90 = 比APP设置慢10%

const SPEED_MIN    = 0.5;      // 【最慢限制】防止语速太慢导致异常
const SPEED_MAX    = 2.0;      // 【最快限制】防止语速太快导致服务器报错

// 计算公式（不用改，理解就行）：
//   最终语速 = (speechRate / SPEED_BASE) * SPEED_BOOST
//   然后再用 SPEED_MIN / SPEED_MAX 掐头去尾


/* -------------------- 四、音高调节 -------------------- */
const PITCH_VALUE  = 0;        // 【音高】0=不变，正数变尖，负数变沉
                               // 一般保持 0 就行，猫箱音色本身已经调好了


/* -------------------- 五、完整调用示例 -------------------- */
// 下面这段就是最终塞进 JSON 里 "loginCheckJs" 字段的代码：

function buildLoginCheckJs(speechRate, speakText) {
    var deviceId = String(Math.floor(1e12 + 9e12 * Math.random()));
    var aid      = String(Math.floor(1e12 + 9e12 * Math.random()));
    var wsUrl    = WS_HOST + '/internal/api/v1/ws?ssmix=&aid=' + aid + '&device_id=' + deviceId;

    // ===== 语速计算 =====
    var baseRate   = speechRate / SPEED_BASE;
    var rate       = baseRate * SPEED_BOOST;

    // 限速保护
    if (rate < SPEED_MIN) rate = SPEED_MIN;
    if (rate > SPEED_MAX) rate = SPEED_MAX;

    // ===== 执行朗读 =====
    ws.maoxiang(
        wsUrl,
        speakText,
        VOICE_ID,      // 音色
        AUDIO_FORMAT,  // 格式
        SAMPLE_RATE,   // 采样率
        rate,          // 最终语速
        PITCH_VALUE,   // 音高
        APP_KEY,       // AppKey
        TIMEOUT_MS     // 超时
    );
}


/* -------------------- 六、网络下载音频文件（新增） -------------------- */
// 如果想先下载一个现成的音频文件，再对音频进行修改后交给 APP 播放，
// 可以使用 java.downloadBytes() 直接下载二进制音频数据。
//
// 适用场景：
//   - 有现成的 MP3/WAV 音频网址，想下载后裁剪、拼接、加音效再播放
//   - 不想通过 WebSocket 合成，而是直接下载在线音频
//   - 下载后再用 JS 对音频字节数组做任意处理

// 用法示例：
var audioBytes = java.downloadBytes("https://xxx.com/audio.mp3", 30000);
if (audioBytes && audioBytes.length > 0) {
    // 这里可以对 audioBytes 做任何修改（裁剪、拼接、混音等）
    // 最终返回字节数组，APP 会直接播放，不需要再走网络请求
    audioBytes;
} else {
    java.log("音频下载失败");
    // 返回空字节数组（APP 会处理为静音）
    new java.io.ByteArrayOutputStream().toByteArray();
}

// 相关 API：
//   java.downloadBytes(url, timeout) → ByteArray | null
//   java.downloadFile(url)           → 下载到 APP cache 目录，返回相对路径
//   java.ajax(url, timeout)          → 返回文本（不适合下载二进制）
//   java.writeExternalFile(path, text) → 写入文本到 /storage/emulated/0/Download/ 下
//   java.readExternalFile(path)        → 从 /storage/emulated/0/Download/ 下读取文本


/* ============================================================
 * 快速调节指南：
 * -----------------------------------------------------------
 * 觉得语速慢？  →  把 SPEED_BOOST 改大（如 1.2、1.3）
 * 觉得语速快？  →  把 SPEED_BOOST 改小（如 1.0、0.9）
 * 想换音色？    →  改 VOICE_ID（需要知道猫箱的其他音色ID）
 * 换音频格式？  →  改 AUDIO_FORMAT 为 "pcm"（同时改采样率）
 * ============================================================ */
