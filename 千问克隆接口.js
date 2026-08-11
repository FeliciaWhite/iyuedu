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

// ==================== 【最终版】远程获取 nonce/timestamp/sign ====================
const AUTH_REMOTE_URL = "https://cnb.cool/mingwuyan/yinpin/-/git/raw/main/qianwen2.json?download=true";
const LOCAL_AUTH_FILE = "auth_qianwen.json"; // 固定一个文件，覆盖保存

// 获取今天日期：yyyyMMdd
function getTodayDateStr() {
    let date = new Date();
    let y = date.getFullYear();
    let m = (date.getMonth() + 1).toString().padStart(2, '0');
    let d = date.getDate().toString().padStart(2, '0');
    return `${y}${m}${d}`;
}

// 下载并覆盖保存
function downloadAuthAndSave() {
    try {
        let headers = {};
        let jsonStr = ttsrv.httpGetString(AUTH_REMOTE_URL, headers);
        let remoteAuth = JSON.parse(jsonStr);
        
        // 必须包含这三个字段才有效
        if (!remoteAuth.nonce || !remoteAuth.timestamp || !remoteAuth.sign) {
            throw new Error("远程JSON缺少必要参数");
        }

        // 加入内部时间戳
        let localAuth = {
            nonce: remoteAuth.nonce,
            timestamp: remoteAuth.timestamp,
            sign: remoteAuth.sign,
            updateDate: getTodayDateStr() // 把日期写在JSON里
        };

        // 覆盖写入本地唯一文件
        let saveStr = JSON.stringify(localAuth, null, 2);
        ttsrv.writeTxtFile(LOCAL_AUTH_FILE, saveStr);
        return localAuth;
    } catch (e) {
        console.error("下载认证失败：", e);
        return null;
    }
}

// 读取本地，任何异常都重新下载
function loadAuth() {
    try {
        // 1. 文件不存在 → 重下
        if (!ttsrv.fileExist(LOCAL_AUTH_FILE)) {
            return downloadAuthAndSave();
        }

        // 2. 读取失败 → 重下
        let jsonStr = ttsrv.readTxtFile(LOCAL_AUTH_FILE);
        if (!jsonStr || jsonStr.trim() === "") {
            return downloadAuthAndSave();
        }

        // 3. 解析失败 → 重下
        let auth = JSON.parse(jsonStr);

        // 4. 参数缺失 → 重下
        if (!auth.nonce || !auth.timestamp || !auth.sign || !auth.updateDate) {
            return downloadAuthAndSave();
        }

        // 5. 不是今天 → 重下
        if (auth.updateDate !== getTodayDateStr()) {
            return downloadAuthAndSave();
        }

        // 全部正常，返回本地
        return auth;
    } catch (e) {
        // 任何读取/解析错误 → 重新获取
        return downloadAuthAndSave();
    }
}

// 最终获取认证（保证一定有效）
function getValidAuth() {
    let auth = loadAuth();
    // 极端情况：下载也失败，返回空对象避免崩溃
    if (!auth) {
        return { nonce: "", timestamp: "", sign: "" };
    }
    return auth;
}

// --- 重写：无Cookie，直接使用远程认证 ---
function getWsUrl() {
    let auth = getValidAuth();

    // 固定参数顺序，已移除所有Cookie
    let params = [
        "nt=5",
        "nw=wifi",
        "ve=6.1.5.2782",
        "pf=3300",
        "fr=android",
        "bi=37260",
        "pr=qwen",
        "sv=release",
        "ch=tongyi%40store_free_vivo",
        "os=15",
        `nonce=${auth.nonce}`,
        `timestamp=${auth.timestamp}`,
        `sign=${auth.sign}`,
        "bizid=qwen-chat"
    ];

    let allParams = params.join('&');
    return `wss://speech-tts.qianwen.com/api/v2/tts?${allParams}`;
}

function check() {
    getWsUrl();
}

function generateId() {
    const chars = 'abcdef0123456789'
    let result = ''
    for (let i = 0; i < 32; i++) {
        result += chars.charAt(Math.floor(Math.random() * chars.length))
    }
    return result
}

function generateUUID() {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
        var r = Math.random() * 16 | 0, v = c == 'x' ? r : (r & 0x3 | 0x8);
        return v.toString(16);
    });
}

// 判断是否是克隆音色 (忽略大小写)
function isCloneVoice(voiceId) {
    return voiceId && voiceId.toLowerCase().startsWith('create_voice_');
}

// --- 核心工具：生成WAV ---
function writeString(stream, str) {
    for (let i = 0; i < str.length; i++) {
        stream.write(str.charCodeAt(i));
    }
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

function writeWavHeaderToStream(stream, dataLength) {
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

// ==================== 内置克隆音色生成函数 ====================
function getBuiltinCloneVoices() {
    // 基础URL（可根据需要修改）
    const baseUrl = "https://cnb.cool/mingwuyan/yinpin/-/git/raw/main/yuanshen/";
    
    // 在这里添加克隆音色：只需提供名字和文本内容
    const voices = [
        {name: "三月七", text: "他们这些大反派往往都有比金钱更加重要的目的，所以赔钱做生意也是非常合理、非常符合逻辑的。"},
        
        
        
        {name: "七七", text: "这一切都是因为你在，谢谢你…可以答应我一个愿望吗？今后…就让我来保护你吧，好不好？"},
        
        {name: "鹿野院平藏", text: "说起来，宫司大人的八重堂也会出版推理小说吧？什么时候能请你当面引荐一番？在这个时代，要成为名侦探，实力以外，宣传也不能落下。哎呀呀，我哪有别的心思啦。"}
        
        // 后续添加新音色直接在这里加，格式：
        // { name: "角色名", text: "参考文本" }
    ];
    
    return voices.map(v => ({
        voice_id: `create_voice_${v.name}`,
        voice_name: v.name,
        gender: "female",
        is_cloned: true,
        audio_text: v.text,
        audio_url: baseUrl + v.name + ".mp3"
    }));
}

// --- 预设音色 ---
const PRESET_VOICES = [

];

function getAllVoices() {
    // 合并预设音色和动态生成的内置克隆音色
    return PRESET_VOICES.concat(getBuiltinCloneVoices());
}

// --- 插件主对象 ---
let PluginJS = {
    "name": "千问-(原神崩铁)",
    "id": "qianwen.tts.yb",
    "author": "TTS Server",
    "version": 1,
    'iconUrl': 'https://img.alicdn.com/imgextra/i1/O1CN01L9yG8u1oO6iA7Jz9e_!!6000000005214-55-tps-83-82.svg',
    // 已移除所有用户输入变量

    "isNeedDecode": function(locale, voice) {
        return true; 
    },

    "onStop": function () {
        if (ws != null) {
            try { ws.close(1000, "stop"); } catch(e) {}
            ws = null
        }
    },

    "getAudioV2": function (request, callback2) {
        check()

        let speed = 0.5 + (request.rate / 100) * 1.5
        let volume = request.volume / 50
        callback = callback2
        
        audioState = {
            chunks: [],
            totalLength: 0,
            isFinished: false
        }
        
        let rawId = String(request.voice || "").trim()
        let finalId = rawId;
        let voiceInfo = null;
        
        // 如果是默认值，映射到具体音色（兼容旧配置）
        if (rawId === "" || rawId.indexOf("unknown1") > -1 || rawId === "longqiang") {
            finalId = "longqiang"
        } else if (rawId.indexOf("unknown2") > -1 || rawId === "longyan") {
            finalId = "longyan"
        } else if (rawId.indexOf("unknown_muyang") > -1) {
            finalId = "zh_female_quarkF531S0_ptts"
        }
        
        // 从所有音色中查找（包括内置克隆音色）
        let allVoices = getAllVoices();
        for (let v of allVoices) {
            if (v.voice_id === finalId) {
                voiceInfo = v;
                break;
            }
        }

        req = {
            text: request.text,
            voice: finalId,
            voiceInfo: voiceInfo,
            speed: speed,
            volume: volume,
            reqid: generateId()
        }
        
        if (ws != null) {
            try { ws.close(1000, "restart"); } catch(e) {}
            ws = null
        }

        getAudio()
    }
}

// --- WebSocket 处理 ---
function getAudio() {
    if (ws == null) {
        let wsUrl = getWsUrl()
        if (!wsUrl.startsWith("wss://")) {
            callback.error("URL 格式错误")
            return
        }
        
        let headers = {
            "Origin": "https://tongyi.aliyun.com",
            "User-Agent": "Mozilla/5.0 (Linux; Android 16; PJX110 Build/UKQ1.231108.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/123.0.6312.80 Mobile Safari/537.36 AliApp(tongyi/6.1.5.2780) TTID/36335934394984@TongYi_Android_6.1.5.2780"
        }
        
        try {
            ws = new Websocket(wsUrl, headers)
        } catch (e) {
            callback.error("WebSocket 创建失败: " + e.message)
            return
        }

        const flushAllAudio = function(reason) {
            if (audioState.isFinished) return;
            audioState.isFinished = true;

            try {
                if (audioState.chunks.length > 0) {
                    let silenceDurationMs = 200;
                    let silenceBytes = 24000 * 2 * (silenceDurationMs / 1000);
                    let finalTotalLength = silenceBytes + audioState.totalLength + silenceBytes;

                    let stream = new java.io.ByteArrayOutputStream();
                    writeWavHeaderToStream(stream, finalTotalLength);
                    for (let i = 0; i < silenceBytes; i++) stream.write(0);
                    for (let chunk of audioState.chunks) stream.write(chunk);
                    for (let i = 0; i < silenceBytes; i++) stream.write(0);
                    
                    let finalBytes = stream.toByteArray();
                    callback.write(finalBytes);
                    stream.close();
                    
                    try { java.lang.Thread.sleep(200); } catch(e) {}
                    
                } else {
                    logger.w("无音频数据生成 (" + reason + ")");
                }
            } catch (e) {
                logger.e("写入异常: " + e);
            } finally {
                callback.close();
                ws = null;
            }
        }

        ws.on('close', function (code, reason) {
            flushAllAudio("Close:" + code);
        })

        ws.on('error', function (err, resp) {
            flushAllAudio("Error");
        })

        ws.on('text', function (msg) {
            try {
                let data = JSON.parse(msg)
                
                if (data.code == 2000000 && data.data) {
                    if (ws && ws._lastDataTimeRef) ws._lastDataTimeRef.value = Date.now()
                    
                    if (data.data.audio) {
                        let audioBytes = ttsrv.base64DecodeToBytes(data.data.audio)
                        if (audioBytes) {
                            audioState.chunks.push(audioBytes);
                            audioState.totalLength += audioBytes.length;
                        }
                    }
                    
                    if (data.data.status == 2 || data.data.status == "2") {
                        ws.close(1000, "done");
                        flushAllAudio("Done");
                    }
                } else if (data.code && data.code != 2000000) {
                    logger.e("API错误: " + data.message);
                    flushAllAudio("API Error");
                }
            } catch (e) {
                logger.e("解析异常: " + e);
            }
        })

        ws.on('open', function () {
            sendMessage()
            
            let lastDataTime = { value: Date.now() }
            let stopCheck = { value: false }
            let checkRunnable = new java.lang.Runnable({
                run: function() {
                    try {
                        java.lang.Thread.sleep(1500)
                        while (!stopCheck.value && ws != null) {
                            java.lang.Thread.sleep(500)
                            if (ws == null || stopCheck.value) break
                            let elapsed = Date.now() - lastDataTime.value
                            if (elapsed > 30000) {
                                stopCheck.value = true;
                                if (ws != null) try { ws.close(1000, "timeout"); } catch(e) {}
                                flushAllAudio("Timeout");
                                break
                            }
                        }
                    } catch (e) {}
                }
            })
            let checkThread = new java.lang.Thread(checkRunnable)
            checkThread.start()
            ws._lastDataTimeRef = lastDataTime
            ws._stopCheck = stopCheck
        })
        return
    }
    if (ws.readyState == Websocket.OPEN) {
        sendMessage()
    } else {
        ws = null
        return getAudio()
    }
}

function sendMessage() {
    let chatReqId = ""
    try {
        let wsUrl = getWsUrl()
        let match = wsUrl.match(/[?&]hid=([^&]+)/)
        if (match) chatReqId = match[1]
        else chatReqId = generateUUID()
    } catch(e) {}

    let isClone = isCloneVoice(req.voice);
    
    let targetModel = "QUARK_VOICE";
    if (req.voice === "longqiang" || req.voice === "longyan") {
        targetModel = "QWEN_NLS";
    }

    let msg1 = {
        reqid: req.reqid,
        text: req.text,
        model: targetModel,
        vcn: req.voice,
        type: "stream",
        speed: parseFloat(req.speed),
        volume: parseFloat(req.volume),
        format: "pcm",
        status: 1,
        sample_rate: 24000,
        extra_params: {chat_req_id: chatReqId}
    };
    
    if (isClone && req.voiceInfo) {
        msg1.audio_text = req.voiceInfo.audio_text || "";
        msg1.audio_url = req.voiceInfo.audio_url || "";
    }

    let msg2 = {
        reqid: req.reqid,
        text: "",
        model: "QUARK_VOICE",
        vcn: isClone ? "zh_female_quarkF531S0_ptts" : req.voice,
        type: "stream",
        speed: parseFloat(req.speed),
        volume: parseFloat(req.volume),
        format: "pcm",
        status: 2,
        sample_rate: 24000,
        extra_params: {}
    }
    
    try {
        ws.send(JSON.stringify(msg1))
        
        let t = new java.lang.Thread(new java.lang.Runnable({
            run: function() {
                try {
                     java.lang.Thread.sleep(50) 
                     if (ws) ws.send(JSON.stringify(msg2))
                } catch(e) {}
            }
        }))
        t.start()
    } catch(e) {
        callback.error("发送异常: " + e)
    }
}

// ==================== EditorJS ====================
let EditorJS = {
    "getAudioSampleRate": function (locale, voice) { return 24000 },
    "getAudioFormat": function (locale, voice) { return "wav" },
    "getLocales": function () { return localesCache },
    "getVoices": function (locale) {
        let mm = {}
        let allVoices = getAllVoices();
        
        allVoices.forEach(v => { 
            let prefix = v.is_cloned ? "" : "";
            mm[v.voice_id] = { 
                name: prefix + v.voice_name, 
                gender: v.gender 
            } 
        })
        return mm
    },

    "onLoadData": function () {
        logger.i("[onLoadData] 刷新数据...");
    },

    "onLoadUI": function (ctx, linerLayout) {
        // 已移除所有克隆输入界面，无需任何动态输入
    },

    "onVoiceChanged": function (locale, voiceCode) {
        if (ws != null) { try { ws.close(1000, "change"); } catch(e) {} ws = null }
        // 无需处理UI显示隐藏
    }
}