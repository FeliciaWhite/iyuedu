// ==================== 用户可调静音时长（毫秒） ====================
const SILENCE_PRE_MS = 150;   // 前置静音时长（毫秒）
const SILENCE_POST_MS = 150;  // 后置静音时长（毫秒）
// ==================== 音频末尾清零字节数 ====================
const TRAILING_BYTES_TO_ZERO = 5000;  // 音频末尾要清零的字节数量
// =============================================================

let req = {}
var callback = null
let ws = null

// --- 状态管理 ---
let audioState = {
    chunks: [],      // 存放所有音频片段
    totalLength: 0,  // 总字节数
    isFinished: false
}

// --- 缓存管理 ---
let localesCache = ['zh-CN']

// --- 认证状态管理 ---
let authRetryCount = 0;
const MAX_AUTH_RETRY = 1;

// ==================== 远程获取 nonce/timestamp/sign ====================
const AUTH_REMOTE_URL = "https://cnb.cool/mingwuyan/yinpin/-/git/raw/main/qianwen2.json?download=true";
const LOCAL_AUTH_FILE = "auth_qianwen.json";

function getTodayDateStr() {
    let date = new Date();
    let y = date.getFullYear();
    let m = (date.getMonth() + 1).toString().padStart(2, '0');
    let d = date.getDate().toString().padStart(2, '0');
    return `${y}${m}${d}`;
}

function downloadAuthAndSave() {
    try {
        let headers = {};
        let jsonStr = ttsrv.httpGetString(AUTH_REMOTE_URL, headers);
        let remoteAuth = JSON.parse(jsonStr);
        if (!remoteAuth.nonce || !remoteAuth.timestamp || !remoteAuth.sign) {
            throw new Error("远程JSON缺少必要参数");
        }
        let localAuth = {
            nonce: remoteAuth.nonce,
            timestamp: remoteAuth.timestamp,
            sign: remoteAuth.sign,
            updateDate: getTodayDateStr()
        };
        let saveStr = JSON.stringify(localAuth, null, 2);
        ttsrv.writeTxtFile(LOCAL_AUTH_FILE, saveStr);
        authRetryCount = 0;
        return localAuth;
    } catch (e) {
        console.error("下载认证失败：", e);
        return null;
    }
}

function loadAuthFromFile() {
    try {
        if (!ttsrv.fileExist(LOCAL_AUTH_FILE)) return null;
        let jsonStr = ttsrv.readTxtFile(LOCAL_AUTH_FILE);
        if (!jsonStr || jsonStr.trim() === "") return null;
        let auth = JSON.parse(jsonStr);
        if (!auth.nonce || !auth.timestamp || !auth.sign || !auth.updateDate) return null;
        return auth;
    } catch (e) {
        return null;
    }
}

function getValidAuth() {
    let auth = loadAuthFromFile();
    if (!auth) auth = downloadAuthAndSave();
    if (!auth) return { nonce: "", timestamp: "", sign: "", updateDate: "" };
    return auth;
}

function refreshAuthOnFailure() {
    if (authRetryCount < MAX_AUTH_RETRY) {
        authRetryCount++;
        logger.i("认证可能过期，重新下载... (第" + authRetryCount + "次)");
        return downloadAuthAndSave();
    }
    return null;
}

function getWsUrl() {
    let auth = getValidAuth();
    let params = [
        "nt=5", "nw=wifi", "ve=6.1.5.2782", "pf=3300", "fr=android",
        "bi=37260", "pr=qwen", "sv=release", "ch=tongyi%40store_free_vivo",
        "os=15", `nonce=${auth.nonce}`, `timestamp=${auth.timestamp}`,
        `sign=${auth.sign}`, "bizid=qwen-chat"
    ];
    return `wss://speech-tts.qianwen.com/api/v2/tts?${params.join('&')}`;
}

function check() { getWsUrl(); }

function generateId() {
    const chars = 'abcdef0123456789';
    let result = '';
    for (let i = 0; i < 32; i++) result += chars.charAt(Math.floor(Math.random() * chars.length));
    return result;
}

function generateUUID() {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
        var r = Math.random() * 16 | 0, v = c == 'x' ? r : (r & 0x3 | 0x8);
        return v.toString(16);
    });
}

// ==================== WAV 头写入工具 ====================
function writeString(stream, str) {
    for (let i = 0; i < str.length; i++) stream.write(str.charCodeAt(i));
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
function writeWavHeader(stream, dataLength) {
    let sampleRate = 24000;
    let channels = 1;
    let bitsPerSample = 16;
    let byteRate = sampleRate * channels * bitsPerSample / 8;
    let blockAlign = channels * bitsPerSample / 8;
    writeString(stream, 'RIFF');
    writeInt(stream, 36 + dataLength);
    writeString(stream, 'WAVE');
    writeString(stream, 'fmt ');
    writeInt(stream, 16);
    writeShort(stream, 1);
    writeShort(stream, channels);
    writeInt(stream, sampleRate);
    writeInt(stream, byteRate);
    writeShort(stream, blockAlign);
    writeShort(stream, bitsPerSample);
    writeString(stream, 'data');
    writeInt(stream, dataLength);
}

// --- 音色列表 ---
const PRESET_VOICES = [
    {voice_id: "zh_female_quarkF531S0_ptts", voice_name: "沐阳", gender: "female"},
    {voice_id: "zh_female_quark_lulu", voice_name: "若初", gender: "female"},
    {voice_id: "zh_female_quark_ajiao", voice_name: "苏荷姐姐", gender: "female"},
    {voice_id: "zh_female_quark_luoying", voice_name: "元气草莓", gender: "female"},
    {voice_id: "zh_female_quark_jiabei", voice_name: "活力嘉蓓", gender: "female"},
    {voice_id: "zh_female_quark_xinshen", voice_name: "起司妹妹", gender: "female"},
    {voice_id: "zh_female_quark_xiaoning", voice_name: "电台华姐", gender: "female"},
    {voice_id: "zh_female_quark_f29", voice_name: "彩虹甜豆", gender: "female"},
    {voice_id: "zh_female_quark_xiaoxiao", voice_name: "念念", gender: "female"},
    {voice_id: "zh_female_quark_zheque", voice_name: "方晴师姐", gender: "female"},
    {voice_id: "longqiang", voice_name: "浅吻雾梨", gender: "female"},
    {voice_id: "longyan", voice_name: "午夜甜茶", gender: "female"},
    {voice_id: "zh_male_quark_bb01", voice_name: "皓东", gender: "male"},
    {voice_id: "zh_male_quark_m24", voice_name: "温屿哥哥", gender: "male"},
    {voice_id: "zh_male_chengfeng_ICL", voice_name: "阿辉", gender: "male"}
];

function getAllVoices() { return PRESET_VOICES; }

// --- 插件主对象 ---
let PluginJS = {
    "name": "通义千问-精简版",
    "id": "qianwen.tts.clone",
    "author": "TTS Server",
    "version": 1,
    'iconUrl': 'https://img.alicdn.com/imgextra/i1/O1CN01L9yG8u1oO6iA7Jz9e_!!6000000005214-55-tps-83-82.svg',
    'vars': {},

    "isNeedDecode": function(locale, voice) {
        return true;   // 输出 WAV 格式，需要框架解码
    },

    "onStop": function () {
        if (ws != null) {
            try { ws.close(1000, "stop"); } catch(e) {}
            ws = null;
        }
    },

    "getAudioV2": function (request, callback2) {
        check();
        let speed = 0.5 + (request.rate / 100) * 1.5;
        let volume = request.volume / 50;
        callback = callback2;
        audioState = { chunks: [], totalLength: 0, isFinished: false };
        
        // 对文本进行正则替换：句号 -> 逗号（使用 split+join 避免 Java 方法重载歧义）
        let processedText = String(request.text).split("。").join("，");
        
        let rawId = String(request.voice || "").trim();
        let finalId = rawId;
        if (rawId === "" || rawId.indexOf("unknown1") > -1 || rawId === "longqiang") finalId = "longqiang";
        else if (rawId.indexOf("unknown2") > -1 || rawId === "longyan") finalId = "longyan";
        else if (rawId.indexOf("unknown_muyang") > -1) finalId = "zh_female_quarkF531S0_ptts";

        req = {
            text: processedText,   // 使用替换后的文本
            voice: finalId,
            speed: speed,
            volume: volume,
            reqid: generateId()
        };
        if (ws != null) { try { ws.close(1000, "restart"); } catch(e) {} ws = null; }
        authRetryCount = 0;
        getAudio();
    }
};

// --- WebSocket 处理 ---
function getAudio() {
    if (ws == null) {
        let wsUrl = getWsUrl();
        if (!wsUrl.startsWith("wss://")) { callback.error("URL 格式错误"); return; }
        
        let headers = {
            "Origin": "https://tongyi.aliyun.com",
            "User-Agent": "Mozilla/5.0 (Linux; Android 16; PJX110 Build/UKQ1.231108.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/123.0.6312.80 Mobile Safari/537.36 AliApp(tongyi/6.1.5.2780) TTID/36335934394984@TongYi_Android_6.1.5.2780"
        };
        
        try { ws = new Websocket(wsUrl, headers); } catch(e) { callback.error("WebSocket 创建失败: " + e.message); return; }

        const flushAllAudio = function(reason, shouldRetryAuth) {
            if (audioState.isFinished) return;
            audioState.isFinished = true;
            try {
                if (audioState.chunks.length > 0) {
                    let sampleRate = 24000;
                    let bytesPerMs = (sampleRate * 2) / 1000; // 48字节/毫秒
                    let preSilenceBytes = Math.floor(bytesPerMs * SILENCE_PRE_MS);
                    let postSilenceBytes = Math.floor(bytesPerMs * SILENCE_POST_MS);
                    
                    // 合并所有音频片段
                    let pcmStream = new java.io.ByteArrayOutputStream();
                    for (let chunk of audioState.chunks) pcmStream.write(chunk);
                    let pcmData = pcmStream.toByteArray();
                    pcmStream.close();
                    
                    // 将音频末尾的指定字节数清零
                    if (TRAILING_BYTES_TO_ZERO > 0 && pcmData.length > 0) {
                        let bytesToZero = Math.min(TRAILING_BYTES_TO_ZERO, pcmData.length);
                        for (let i = pcmData.length - bytesToZero; i < pcmData.length; i++) {
                            pcmData[i] = 0;
                        }
                    }
                    
                    // 重新构建带静音的音频流
                    let finalPcmStream = new java.io.ByteArrayOutputStream();
                    // 前置静音
                    for (let i = 0; i < preSilenceBytes; i++) finalPcmStream.write(0);
                    // 处理后的音频数据
                    finalPcmStream.write(pcmData);
                    // 后置静音
                    for (let i = 0; i < postSilenceBytes; i++) finalPcmStream.write(0);
                    let finalPcmData = finalPcmStream.toByteArray();
                    finalPcmStream.close();
                    
                    // 封装 WAV 头
                    let wavStream = new java.io.ByteArrayOutputStream();
                    writeWavHeader(wavStream, finalPcmData.length);
                    wavStream.write(finalPcmData);
                    let finalWav = wavStream.toByteArray();
                    wavStream.close();
                    
                    callback.write(finalWav);
                    try { java.lang.Thread.sleep(200); } catch(e) {}
                } else {
                    logger.w("无音频数据生成 (" + reason + ")");
                    if (shouldRetryAuth && authRetryCount < MAX_AUTH_RETRY) {
                        let newAuth = refreshAuthOnFailure();
                        if (newAuth) {
                            logger.i("使用新认证重新合成...");
                            audioState.isFinished = false;
                            ws = null;
                            let retryThread = new java.lang.Thread(new java.lang.Runnable({
                                run: function() {
                                    try { java.lang.Thread.sleep(100); getAudio(); } catch(e) {}
                                }
                            }));
                            retryThread.start();
                            return;
                        }
                    }
                }
            } catch (e) { logger.e("写入异常: " + e); }
            finally { callback.close(); ws = null; }
        };

        ws.on('close', function (code, reason) {
            let shouldRetry = (code !== 1000 && code !== 1005);
            flushAllAudio("Close:" + code, shouldRetry);
        });
        ws.on('error', function (err, resp) { flushAllAudio("Error", true); });
        ws.on('text', function (msg) {
            try {
                let data = JSON.parse(msg);
                if (data.code == 2000000 && data.data) {
                    if (ws && ws._lastDataTimeRef) ws._lastDataTimeRef.value = Date.now();
                    if (data.data.audio) {
                        let audioBytes = ttsrv.base64DecodeToBytes(data.data.audio);
                        if (audioBytes) {
                            audioState.chunks.push(audioBytes);
                            audioState.totalLength += audioBytes.length;
                        }
                    }
                    if (data.data.status == 2 || data.data.status == "2") {
                        ws.close(1000, "done");
                        flushAllAudio("Done", false);
                    }
                } else if (data.code && data.code != 2000000) {
                    logger.e("API错误: " + data.message + " (code:" + data.code + ")");
                    if (authRetryCount < MAX_AUTH_RETRY) ws.close(1000, "auth_retry");
                    else flushAllAudio("API Error", false);
                }
            } catch (e) { logger.e("解析异常: " + e); }
        });
        ws.on('open', function () {
            sendMessage();
            let lastDataTime = { value: Date.now() };
            let stopCheck = { value: false };
            let checkRunnable = new java.lang.Runnable({
                run: function() {
                    try {
                        java.lang.Thread.sleep(1500);
                        while (!stopCheck.value && ws != null) {
                            java.lang.Thread.sleep(500);
                            if (ws == null || stopCheck.value) break;
                            let elapsed = Date.now() - lastDataTime.value;
                            if (elapsed > 30000) {
                                stopCheck.value = true;
                                if (ws != null) try { ws.close(1000, "timeout"); } catch(e) {}
                                flushAllAudio("Timeout", true);
                                break;
                            }
                        }
                    } catch(e) {}
                }
            });
            let checkThread = new java.lang.Thread(checkRunnable);
            checkThread.start();
            ws._lastDataTimeRef = lastDataTime;
            ws._stopCheck = stopCheck;
        });
        return;
    }
    if (ws.readyState == Websocket.OPEN) sendMessage();
    else { ws = null; return getAudio(); }
}

function sendMessage() {
    let chatReqId = "";
    try {
        let wsUrl = getWsUrl();
        let match = wsUrl.match(/[?&]hid=([^&]+)/);
        if (match) chatReqId = match[1];
        else chatReqId = generateUUID();
    } catch(e) {}
    let targetModel = "QUARK_VOICE";
    if (req.voice === "longqiang" || req.voice === "longyan") targetModel = "QWEN_NLS";
    let msg1 = {
        reqid: req.reqid, text: req.text, model: targetModel, vcn: req.voice,
        type: "stream", speed: parseFloat(req.speed), volume: parseFloat(req.volume),
        format: "pcm", status: 1, sample_rate: 24000,
        extra_params: {chat_req_id: chatReqId}
    };
    let msg2 = {
        reqid: req.reqid, text: "", model: "QUARK_VOICE", vcn: req.voice,
        type: "stream", speed: parseFloat(req.speed), volume: parseFloat(req.volume),
        format: "pcm", status: 2, sample_rate: 24000, extra_params: {}
    };
    try {
        ws.send(JSON.stringify(msg1));
        let t = new java.lang.Thread(new java.lang.Runnable({
            run: function() { try { java.lang.Thread.sleep(50); if (ws) ws.send(JSON.stringify(msg2)); } catch(e) {} }
        }));
        t.start();
    } catch(e) { callback.error("发送异常: " + e); }
}

// ==================== EditorJS ====================
let EditorJS = {
    "getAudioSampleRate": function (locale, voice) { return 24000; },
    "getAudioFormat": function (locale, voice) { return "wav"; },
    "getLocales": function () { return localesCache; },
    "getVoices": function (locale) {
        let mm = {};
        getAllVoices().forEach(v => { mm[v.voice_id] = { name: v.voice_name, gender: v.gender }; });
        return mm;
    },
    "onLoadData": function () { logger.i("[onLoadData] 刷新数据..."); },
    "onLoadUI": function (ctx, linerLayout) {},
    "onVoiceChanged": function (locale, voiceCode) {
        if (ws != null) { try { ws.close(1000, "change"); } catch(e) {} ws = null; }
    }
};
