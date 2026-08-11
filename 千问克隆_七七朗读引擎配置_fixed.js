@js:

try {

// ===== 0. 文本预处理 =====
var text = speakText;
java.log("[七七引擎] 开始处理文本，长度：" + text.length);
text = text.replace(/[【「『]([\u4E00-\u9Fa5]+)[】』」]/g, "$1");

// 提取音效标记
var sfxDir = '/storage/emulated/0/Download/chajian/bendiyinxiao/';
var parts = text.split(/(\([\u4e00-\u9fa5]*音效\))/);
var mixedSegments = [];
for (var p = 0; p < parts.length; p++) {
    var part = parts[p];
    if (!part) continue;
    if (/^\([\u4e00-\u9fa5]*音效\)$/.test(part)) {
        var nameMatch = part.match(/\(([\u4e00-\u9fa5]*音效)\)/);
        if (nameMatch && nameMatch[1]) {
            mixedSegments.push({type: 'sfx', fileName: nameMatch[1] + '.json'});
            java.log('[音效] 提取: ' + nameMatch[1]);
        }
    } else {
        mixedSegments.push({type: 'text', content: part});
    }
}

// ===== 1. 角色映射表（统一用千问第一个发音人） =====
var MAP_FILE = '/storage/emulated/0/Download/chajian/mingwuyan/jiaoseliebiao-list.json';
var REMOTE_URL = 'https://cnb.cool/mingwuyan/yinpin/-/git/raw/main/jiaoseliebiao-list.json?download=true';
var tagToVoice = {};
var tagToPrompt = {};

function isJsonLike(str) {
    return str && (str.charAt(0) === '[' || str.charAt(0) === '{');
}

var raw = '';
try {
    raw = String(java.readExternalFile(MAP_FILE));
} catch (e) {}

if (!isJsonLike(raw)) {
    try {
        var downloaded = String(java.ajax(REMOTE_URL, 60000));
        if (downloaded && isJsonLike(downloaded)) {
            java.writeExternalFile(MAP_FILE, downloaded);
            raw = downloaded;
        }
    } catch (e) {}
}

try {
    if (isJsonLike(raw)) {
        var groups = JSON.parse(raw);
        for (var g = 0; g < groups.length; g++) {
            var group = groups[g];
            if (group && group.list) {
                for (var l = 0; l < group.list.length; l++) {
                    var item = group.list[l];
                    if (item && item.config) {
                        var tag = item.config.speechRule && item.config.speechRule.tag;
                        var prompt = item.config.source && item.config.source.data && item.config.source.data.contextTexts;
                        if (tag) {
                            tagToVoice[tag] = "zh_female_quarkF531S0_ptts"; // 强制千问第一个发音人
                            if (prompt) tagToPrompt[tag] = prompt;
                        }
                    }
                }
            }
        }
    }
} catch (e) {}

// ===== 2. 千问全局参数 =====
var QIANWEN_VOICE = "create_voice_七七";   // 七七克隆音色
var SAMPLE_RATE   = 24000;
var SILENCE_PRE   = 150;   // 前置静音 ms
var SILENCE_POST  = 150;   // 后置静音 ms
var ZERO_TAIL     = 5000;  // 尾部清零字节数

var CLONE_AUDIO_TEXT = "这一切都是因为你在，谢谢你…可以答应我一个愿望吗？今后…就让我来保护你吧，好不好？";
var CLONE_AUDIO_URL  = "https://cnb.cool/mingwuyan/yinpin/-/git/raw/main/yuanshen/%E4%B8%83%E4%B8%83.mp3";

// 语速转换公式（与千问插件一致）
var rate = 0.5 + (speechRate / 100) * 1.5;
if (rate < 0.5) rate = 0.5;
if (rate > 2.0) rate = 2.0;
java.log("[七七引擎] 当前语速 rate=" + rate);

// 所有角色都使用七七克隆音色
var VOICE_NARRATOR = QIANWEN_VOICE;
var VOICE_DEFAULT  = QIANWEN_VOICE;

// ===== 3. 拆分段落（保留原逻辑） =====
var segments = [];
for (var s = 0; s < mixedSegments.length; s++) {
    var mix = mixedSegments[s];
    if (mix.type === 'sfx') {
        segments.push({type: 'sfx', fileName: mix.fileName});
        continue;
    }
    var segText = mix.content;
    var idx = 0;
    while (idx < segText.length) {
        var qStart = segText.indexOf('"', idx);
        if (qStart === -1) {
            if (idx < segText.length)
                segments.push({txt: segText.substring(idx), voice: VOICE_NARRATOR});
            break;
        }
        if (qStart > idx)
            segments.push({txt: segText.substring(idx, qStart), voice: VOICE_NARRATOR});
        var qEnd = segText.indexOf('"', qStart + 1);
        if (qEnd === -1) qEnd = segText.length - 1;
        var dialogText = segText.substring(qStart, qEnd + 1);
        var voice = null;
        var match = dialogText.match(/<<([^>]+)>>/);
        if (match) {
            var tag = match[1];
            voice = tagToVoice[tag] || null;
            dialogText = dialogText.replace(/<<[^>]+>>/, '');
        }
        if (!voice) voice = VOICE_DEFAULT;
        segments.push({txt: dialogText, voice: voice});
        idx = qEnd + 1;
    }
}
if (segments.length === 0)
    segments.push({txt: text, voice: VOICE_NARRATOR});

// ===== 4. 音效加载函数（原样保留） =====
function loadAndRotateSfx(fileName) {
    var filePath = sfxDir + fileName;
    var sfxJson = null;
    try {
        var raw = String(java.readExternalFile(filePath));
        if (raw && raw.charAt(0) === '{') sfxJson = JSON.parse(raw);
    } catch(e) {}
    if (!sfxJson) {
        var url = 'https://cnb.cool/mingwuyan/yinpin/-/git/raw/main/bdyinxiao2/' + fileName;
        java.log('[音效] 下载: ' + url);
        try {
            var dl = String(java.ajax(url, 30000));
            if (dl && dl.charAt(0) === '{') {
                sfxJson = JSON.parse(dl);
                try { java.writeExternalFile(filePath, dl); } catch(e) {}
            }
        } catch(e) { java.log('[音效] 下载失败: ' + e); return null; }
    }
    if (!sfxJson || !Array.isArray(sfxJson.audios) || sfxJson.audios.length === 0) {
        java.log('[音效] audios无效');
        return null;
    }
    var index = sfxJson.currentIndex || 0;
    if (index >= sfxJson.audios.length) index = 0;
    var b64 = sfxJson.audios[index];
    if (!b64 || typeof b64 !== 'string' || b64.length < 100) {
        java.log('[音效] Base64无效');
        return null;
    }
    sfxJson.currentIndex = (index + 1) % sfxJson.audios.length;
    try { java.writeExternalFile(filePath, JSON.stringify(sfxJson)); } catch(e) {}
    try {
        var bytes = java.base64DecodeToByteArray(b64.trim());
        if (bytes && bytes.length > 0) {
            java.log('[音效] 解码成功: ' + bytes.length + ' 字节 (' + fileName + ' idx=' + index + ')');
            return bytes;
        }
    } catch(e) { java.log('[音效] 解码异常: ' + e); }
    return null;
}

// ===== 5. 千问 WebSocket 合成（同步阻塞版，带详细日志） =====
function qianwenTTS(synthesisText, voiceId, speed) {
    java.log("========================================");
    java.log("[七七TTS] 开始合成，文本长度=" + synthesisText.length + "，音色=" + voiceId);

    // ---------- 下载认证文件 ----------
    var AUTH_URL = "https://cnb.cool/mingwuyan/yinpin/-/git/raw/main/qianwen2.json?download=true";
    java.log("[七七TTS] 正在下载认证文件: " + AUTH_URL);
    var authStr = null;
    try {
        authStr = String(java.ajax(AUTH_URL, 10000));
        java.log("[七七TTS] 下载成功，长度=" + (authStr ? authStr.length : 0));
        java.log("[七七TTS] 认证原始内容(前200字符): " + authStr.substring(0, Math.min(200, authStr.length)));
    } catch (e) {
        java.log("[七七TTS] 认证下载失败: " + e);
        return Packages.java.lang.reflect.Array.newInstance(Packages.java.lang.Byte.TYPE, 0);
    }
    var authObj;
    try {
        authObj = JSON.parse(authStr);
        java.log("[七七TTS] JSON解析成功");
    } catch (e) {
        java.log("[七七TTS] JSON解析失败: " + e);
        return Packages.java.lang.reflect.Array.newInstance(Packages.java.lang.Byte.TYPE, 0);
    }
    if (!authObj.nonce || !authObj.timestamp || !authObj.sign) {
        java.log("[七七TTS] 认证参数缺失: nonce=" + authObj.nonce + " timestamp=" + authObj.timestamp + " sign=" + authObj.sign);
        return Packages.java.lang.reflect.Array.newInstance(Packages.java.lang.Byte.TYPE, 0);
    }
    java.log("[七七TTS] 认证参数 -> nonce=" + authObj.nonce + " timestamp=" + authObj.timestamp + " sign=" + authObj.sign);

    // ---------- 拼接 WebSocket URL ----------
    var params = [
        "nt=5", "nw=wifi", "ve=6.1.5.2782", "pf=3300", "fr=android",
        "bi=37260", "pr=qwen", "sv=release", "ch=tongyi%40store_free_vivo",
        "os=15", "nonce=" + authObj.nonce, "timestamp=" + authObj.timestamp,
        "sign=" + authObj.sign, "bizid=qwen-chat"
    ];
    var wsUrl = "wss://speech-tts.qianwen.com/api/v2/tts?" + params.join("&");
    java.log("[七七TTS] WebSocket URL: " + wsUrl);

    // ---------- 创建 WebSocket ----------
    var headers = {
        "Origin": "https://tongyi.aliyun.com",
        "User-Agent": "Mozilla/5.0 (Linux; Android 16; PJX110 Build/UKQ1.231108.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/123.0.6312.80 Mobile Safari/537.36 AliApp(tongyi/6.1.5.2780) TTID/36335934394984@TongYi_Android_6.1.5.2780"
    };

    var conn;
    try {
        conn = new Websocket(wsUrl, headers);
        java.log("[七七TTS] WebSocket 创建成功 (readyState=" + conn.readyState + ")");
    } catch (e) {
        java.log("[七七TTS] WebSocket 创建失败: " + e);
        return Packages.java.lang.reflect.Array.newInstance(Packages.java.lang.Byte.TYPE, 0);
    }

    var chunks = [];
    var finished = false;
    var errorFlag = false;
    var wsOpened = false;

    // 接收回调
    conn.on('open', function() {
        java.log("[七七TTS] WebSocket onOpen 回调触发");
        wsOpened = true;
    });

    conn.on('text', function(msg) {
        java.log("[七七TTS] 收到消息(前200字符): " + msg.substring(0, Math.min(200, msg.length)));
        try {
            var data = JSON.parse(msg);
            if (data.code == 2000000 && data.data) {
                if (data.data.audio) {
                    var audioBytes = java.base64DecodeToByteArray(data.data.audio);
                    if (audioBytes && audioBytes.length > 0) {
                        chunks.push(audioBytes);
                        java.log("[七七TTS] 收到音频片段: " + audioBytes.length + " 字节");
                    }
                }
                if (data.data.status == 2 || data.data.status == "2") {
                    java.log("[七七TTS] 收到结束标记 status=2，准备关闭");
                    conn.close(1000, "done");
                    finished = true;
                }
            } else if (data.code && data.code != 2000000) {
                java.log("[七七TTS] API错误: code=" + data.code + " message=" + (data.message || ""));
                errorFlag = true;
                conn.close();
            }
        } catch (e) {
            java.log("[七七TTS] 解析消息异常: " + e);
        }
    });

    conn.on('error', function(err) {
        java.log("[七七TTS] 连接错误: " + err);
        errorFlag = true;
    });

    conn.on('close', function(code, reason) {
        java.log("[七七TTS] 连接关闭: code=" + code + " reason=" + reason);
        if (!finished && !errorFlag) finished = true; // 意外关闭也结束等待
    });

    // 等待连接打开（最多10秒）
    var openWait = 0;
    while (!wsOpened && conn.readyState !== 1 && openWait < 10000) {
        Packages.java.lang.Thread.sleep(50);
        openWait += 50;
    }
    if (conn.readyState !== 1 && !wsOpened) {
        java.log("[七七TTS] 连接未能在10秒内打开，readyState=" + conn.readyState);
        return Packages.java.lang.reflect.Array.newInstance(Packages.java.lang.Byte.TYPE, 0);
    }
    java.log("[七七TTS] WebSocket 已打开，准备发送消息");

    // ---------- 构造并发送两条消息 ----------
    var reqId = '';
    var chars = 'abcdef0123456789';
    for (var i = 0; i < 32; i++) reqId += chars.charAt(Math.floor(Math.random() * chars.length));

    var msg1 = JSON.stringify({
        reqid: reqId, text: synthesisText, model: "QUARK_VOICE", vcn: voiceId,
        type: "stream", speed: parseFloat(speed), volume: 1.0,
        format: "pcm", status: 1, sample_rate: 24000,
        extra_params: {chat_req_id: reqId},
        audio_text: CLONE_AUDIO_TEXT,
        audio_url: CLONE_AUDIO_URL
    });
    var msg2 = JSON.stringify({
        reqid: reqId, text: "", model: "QUARK_VOICE", vcn: "zh_female_quarkF531S0_ptts",
        type: "stream", speed: parseFloat(speed), volume: 1.0,
        format: "pcm", status: 2, sample_rate: 24000, extra_params: {}
    });

    java.log("[七七TTS] 发送消息1: " + msg1);
    try {
        conn.send(msg1);
        Packages.java.lang.Thread.sleep(50);
        conn.send(msg2);
        java.log("[七七TTS] 两条消息已发送");
    } catch (e) {
        java.log("[七七TTS] 发送消息失败: " + e);
        return Packages.java.lang.reflect.Array.newInstance(Packages.java.lang.Byte.TYPE, 0);
    }

    // 等待合成结束（最长30秒）
    var waited = 0;
    while (!finished && !errorFlag && waited < 30000) {
        Packages.java.lang.Thread.sleep(100);
        waited += 100;
    }

    if ((errorFlag && chunks.length === 0) || (!finished && waited >= 30000)) {
        java.log("[七七TTS] 合成超时或出错 errorFlag=" + errorFlag + " finished=" + finished + " chunks=" + chunks.length);
        try { conn.close(); } catch(e) {}
        return Packages.java.lang.reflect.Array.newInstance(Packages.java.lang.Byte.TYPE, 0);
    }

    java.log("[七七TTS] 合成完成，共收到 " + chunks.length + " 个音频片段");

    // ---------- 拼接 PCM 并加静音/清零 ----------
    var bos = ws.newBuffer();
    for (var i = 0; i < chunks.length; i++) {
        bos.write(chunks[i]);
    }
    var pcmData = bos.toByteArray();
    bos.close();
    java.log("[七七TTS] 合并后 PCM 长度: " + pcmData.length + " 字节");

    if (pcmData.length === 0) return Packages.java.lang.reflect.Array.newInstance(Packages.java.lang.Byte.TYPE, 0);

    // 尾部清零
    if (ZERO_TAIL > 0) {
        var zeroLen = Math.min(ZERO_TAIL, pcmData.length);
        for (var i = pcmData.length - zeroLen; i < pcmData.length; i++) pcmData[i] = 0;
    }

    // 前后静音
    var bytesPerMs = (SAMPLE_RATE * 2) / 1000; // 48
    var preSilenceBytes = Math.floor(SILENCE_PRE * bytesPerMs);
    var postSilenceBytes = Math.floor(SILENCE_POST * bytesPerMs);

    var finalBos = ws.newBuffer();
    for (var i = 0; i < preSilenceBytes; i++) finalBos.write(0);
    finalBos.write(pcmData);
    for (var i = 0; i < postSilenceBytes; i++) finalBos.write(0);
    var finalPcm = finalBos.toByteArray();
    finalBos.close();

    // 写 WAV 头（纯 JS 实现，避免 java.nio / java.lang.String）
    function writeString(stream, str) {
        for (var i = 0; i < str.length; i++) stream.write(str.charCodeAt(i));
    }
    function writeInt(stream, val) {
        stream.write(val & 0xFF);
        stream.write((val >> 8) & 0xFF);
        stream.write((val >> 16) & 0xFF);
        stream.write((val >> 24) & 0xFF);
    }
    function writeShort(stream, val) {
        stream.write(val & 0xFF);
        stream.write((val >> 8) & 0xFF);
    }

    var wavBos = ws.newBuffer();
    writeString(wavBos, 'RIFF');
    writeInt(wavBos, 36 + finalPcm.length);
    writeString(wavBos, 'WAVE');
    writeString(wavBos, 'fmt ');
    writeInt(wavBos, 16);
    writeShort(wavBos, 1); // PCM
    writeShort(wavBos, 1); // mono
    writeInt(wavBos, SAMPLE_RATE);
    writeInt(wavBos, SAMPLE_RATE * 2); // byte rate
    writeShort(wavBos, 2); // block align
    writeShort(wavBos, 16); // bits per sample
    writeString(wavBos, 'data');
    writeInt(wavBos, finalPcm.length);
    wavBos.write(finalPcm);
    var wavBytes = wavBos.toByteArray();
    wavBos.close();
    java.log("[七七TTS] 最终WAV大小: " + wavBytes.length + " 字节");
    return wavBytes;
}

// ===== 6. 按段落合成并拼接 =====
var out = ws.newBuffer();
for (var i = 0; i < segments.length; i++) {
    var seg = segments[i];
    if (seg.type === 'sfx') {
        var sfxBytes = loadAndRotateSfx(seg.fileName);
        if (sfxBytes && sfxBytes.length > 0) out.write(sfxBytes);
        continue;
    }
    if (!seg.txt) continue;
    java.log("[七七主流程] 合成段落 " + (i+1) + "/" + segments.length + " 文本: " + seg.txt.substring(0, 30) + "...");
    var audio = qianwenTTS(seg.txt, seg.voice, rate);
    if (audio && audio.length > 0) {
        out.write(audio);
    } else {
        java.log("[七七主流程] 段落 " + (i+1) + " 合成失败，跳过");
    }
}

java.log("[七七引擎] 所有段落处理完毕，输出总字节数: " + out.size());
out.toByteArray();

} catch (e) {
    java.log("脚本异常: " + e);
    throw e;
}
