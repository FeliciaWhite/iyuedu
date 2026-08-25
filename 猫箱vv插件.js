var vvRequest = null;
var vvCallback = null;
var vvSocket = null;
var vvChunks = [];
var vvAudioLength = 0;
var vvFinished = false;
var vvWarmupBridgeStarted = false;
var vvWarmupBridgeUnavailable = false;

var VV_DEFAULT_WS_URL = "wss://audio5-normal-hl.myparallelstory.com/internal/api/v1/ws";
var VV_DEFAULT_APP_KEY = "WQuVLKMGRo";
var VV_APP_AID = "515927";
var VV_APP_NAME = "saina";
var VV_APP_VERSION_CODE = "1260050";
var VV_APP_VERSION_NAME = "1.26.0";
var VV_SILENCE_MS = 100;
var VV_MAP = [{"id":"zh_female_vv_uranus_bigtts","voiceTag":"官方音色/通用场景01","groupName":"官方音色","subGroupName":"通用场景","thirdGroupName":"通用场景","displayName":"Vivi 2.0","locale":"通用场景","voice":"zh_female_vv_uranus_bigtts","speed":1,"volume":1.5,"pitch":1,"data":{"contextTexts":"","emotion":"","emotionScale":"4","audioFormat":"mp3","sampleRate":"24000","officialScene":"通用场景","officialLanguage":"语种：中文、日文、印尼、墨西哥西班牙语 方言：四川、陕西、东北","officialAbility":"指令遵循","officialLabel":"","gender":"女性"}},{"id":"zh_male_lengkugege_emo_v2_mars_bigtts","voiceTag":"官方多情绪/01","groupName":"官方多情绪","subGroupName":"官方多情绪","thirdGroupName":"官方多情绪","displayName":"冷酷哥哥（多情感）","locale":"官方多情绪","voice":"zh_male_lengkugege_emo_v2_mars_bigtts","speed":1,"volume":1.5,"pitch":1,"data":{"contextTexts":"","emotion":"","emotionScale":"4","audioFormat":"mp3","sampleRate":"24000","officialScene":"多情感","officialLanguage":"中文","officialAbility":"官方 emotion 参数","officialLabel":"","officialV2Name":"","supportMix":"否","gender":"男性","emotionStyles":[{"id":"angry","name":"生气","emotion":"angry"},{"id":"coldness","name":"冷漠","emotion":"coldness"},{"id":"fear","name":"恐惧","emotion":"fear"},{"id":"happy","name":"开心","emotion":"happy"},{"id":"hate","name":"厌恶","emotion":"hate"},{"id":"neutral","name":"中性","emotion":"neutral"},{"id":"sad","name":"悲伤","emotion":"sad"},{"id":"depressed","name":"沮丧","emotion":"depressed"}]}},{"id":"zh_female_yingyujiaoyu_mars_bigtts","voiceTag":"官方多情绪/02","groupName":"官方多情绪","subGroupName":"官方多情绪","thirdGroupName":"官方多情绪","displayName":"Tina老师","locale":"官方多情绪","voice":"zh_female_yingyujiaoyu_mars_bigtts","speed":1,"volume":1.5,"pitch":1,"data":{"contextTexts":"","emotion":"","emotionScale":"4","audioFormat":"mp3","sampleRate":"24000","officialScene":"教育场景","officialLanguage":"中文,英式英语","officialAbility":"官方 emotion 参数","officialLabel":"","officialV2Name":"Tina老师 2.0","supportMix":"是","gender":"女性","emotionStyles":[]}},{"id":"zh_female_vv_mars_bigtts","voiceTag":"官方多情绪/03","groupName":"官方多情绪","subGroupName":"官方多情绪","thirdGroupName":"官方多情绪","displayName":"Vivi","locale":"官方多情绪","voice":"zh_female_vv_mars_bigtts","speed":1,"volume":1.5,"pitch":1,"data":{"contextTexts":"","emotion":"","emotionScale":"4","audioFormat":"mp3","sampleRate":"24000","officialScene":"通用场景","officialLanguage":"中文","officialAbility":"官方 emotion 参数","officialLabel":"","officialV2Name":"Vivi 2.0","supportMix":"是","gender":"女性","emotionStyles":[]}},{"id":"zh_male_hupunan_mars_bigtts","voiceTag":"官方多情绪/04","groupName":"官方多情绪","subGroupName":"官方多情绪","thirdGroupName":"官方多情绪","displayName":"沪普男","locale":"官方多情绪","voice":"zh_male_hupunan_mars_bigtts","speed":1,"volume":1.5,"pitch":1,"data":{"contextTexts":"","emotion":"","emotionScale":"4","audioFormat":"mp3","sampleRate":"24000","officialScene":"IP仿音","officialLanguage":"仅中文","officialAbility":"官方 emotion 参数","officialLabel":"豆包同款","officialV2Name":"","supportMix":"是","gender":"男性","emotionStyles":[]}},{"id":"zh_male_naiqimengwa_mars_bigtts","voiceTag":"官方多情绪/06","groupName":"官方多情绪","subGroupName":"官方多情绪","thirdGroupName":"官方多情绪","displayName":"奶气萌娃","locale":"官方多情绪","voice":"zh_male_naiqimengwa_mars_bigtts","speed":1,"volume":1.5,"pitch":1,"data":{"contextTexts":"","emotion":"","emotionScale":"4","audioFormat":"mp3","sampleRate":"24000","officialScene":"角色扮演","officialLanguage":"中文","officialAbility":"官方 emotion 参数","officialLabel":"剪映同款、豆包同款","officialV2Name":"奶气萌娃 2.0","supportMix":"是","gender":"男性","emotionStyles":[]}},{"id":"en_female_lauren_moon_bigtts","voiceTag":"官方多情绪/07","groupName":"官方多情绪","subGroupName":"官方多情绪","thirdGroupName":"官方多情绪","displayName":"Lauren","locale":"官方多情绪","voice":"en_female_lauren_moon_bigtts","speed":1,"volume":1.5,"pitch":1,"data":{"contextTexts":"","emotion":"","emotionScale":"4","audioFormat":"mp3","sampleRate":"24000","officialScene":"多语种","officialLanguage":"美式英语","officialAbility":"官方 emotion 参数","officialLabel":"","officialV2Name":"","supportMix":"是","gender":"女性","emotionStyles":[]}},{"id":"zh_female_kefunvsheng_mars_bigtts","voiceTag":"官方多情绪/08","groupName":"官方多情绪","subGroupName":"官方多情绪","thirdGroupName":"官方多情绪","displayName":"暖阳女声","locale":"官方多情绪","voice":"zh_female_kefunvsheng_mars_bigtts","speed":1,"volume":1.5,"pitch":1,"data":{"contextTexts":"","emotion":"","emotionScale":"4","audioFormat":"mp3","sampleRate":"24000","officialScene":"客服场景","officialLanguage":"仅中文","officialAbility":"官方 emotion 参数","officialLabel":"","officialV2Name":"暖阳女声 2.0","supportMix":"是","gender":"女性","emotionStyles":[]}},{"id":"zh_male_M100_conversation_wvae_bigtts","voiceTag":"官方多情绪/09","groupName":"官方多情绪","subGroupName":"官方多情绪","thirdGroupName":"官方多情绪","displayName":"悠悠君子","locale":"官方多情绪","voice":"zh_male_M100_conversation_wvae_bigtts","speed":1,"volume":1.5,"pitch":1,"data":{"contextTexts":"","emotion":"","emotionScale":"4","audioFormat":"mp3","sampleRate":"24000","officialScene":"视频配音","officialLanguage":"中文","officialAbility":"官方 emotion 参数","officialLabel":"豆包同款","officialV2Name":"悠悠君子 2.0","supportMix":"是","gender":"男性","emotionStyles":[]}},{"id":"zh_male_changtianyi_mars_bigtts","voiceTag":"官方多情绪/10","groupName":"官方多情绪","subGroupName":"官方多情绪","thirdGroupName":"官方多情绪","displayName":"悬疑解说","locale":"官方多情绪","voice":"zh_male_changtianyi_mars_bigtts","speed":1,"volume":1.5,"pitch":1,"data":{"contextTexts":"","emotion":"","emotionScale":"4","audioFormat":"mp3","sampleRate":"24000","officialScene":"有声阅读","officialLanguage":"中文","officialAbility":"官方 emotion 参数","officialLabel":"剪映同款、抖音同款、豆包同款","officialV2Name":"悬疑解说 2.0","supportMix":"是","gender":"男性","emotionStyles":[]}}];

var VV_BY_ID = {};
var VV_BY_TAG = {};
var emotionSpinner = null;
for (var vvIndex = 0; vvIndex < VV_MAP.length; vvIndex++) {
    var vvItem = VV_MAP[vvIndex];
    VV_BY_ID[vvItem.id] = vvItem;
    if (vvItem.voiceTag) VV_BY_TAG[vvItem.voiceTag] = vvItem;
    if (vvItem.displayName) VV_BY_TAG[vvItem.displayName] = vvItem;
}

function vvString(value) {
    return value === null || typeof value === "undefined" ? "" : String(value);
}

function vvNumber(value, fallback) {
    var number = Number(value);
    return isFinite(number) ? number : fallback;
}

function vvStyleValue(request, data) {
    data = data || {};
    var value = vvString(data.officialEmotionStyle || data.voiceStyle || data.styleId || data.style || request.voiceStyle || request.styleId).trim();
    if (!value || value === "auto" || value === "自动" || value === "default") return "";
    return value;
}

function vvEmotionAlias(value) {
    value = vvString(value).trim();
    if (!value) return "";
    var lower = value.toLowerCase();
    var map = {
        "自然": "neutral", "中性": "neutral", "通用": "neutral", "neutral": "neutral",
        "开心": "happy", "高兴": "happy", "愉悦": "happy", "快乐": "happy", "happy": "happy",
        "悲伤": "sad", "伤心": "sad", "难过": "sad", "sad": "sad",
        "生气": "angry", "愤怒": "angry", "怒": "angry", "angry": "angry",
        "惊讶": "surprised", "惊奇": "surprised", "surprised": "surprised", "surprise": "surprised",
        "恐惧": "fear", "害怕": "fear", "fear": "fear",
        "厌恶": "hate", "hate": "hate",
        "激动": "excited", "兴奋": "excited", "excited": "excited",
        "冷漠": "coldness", "冷酷": "coldness", "coldness": "coldness",
        "沮丧": "depressed", "depressed": "depressed",
        "撒娇": "lovey-dovey", "lovey-dovey": "lovey-dovey",
        "害羞": "shy", "shy": "shy",
        "安慰鼓励": "comfort", "安慰": "comfort", "鼓励": "comfort", "comfort": "comfort",
        "咆哮": "tension", "焦急": "tension", "紧张": "tension", "tension": "tension",
        "温柔": "tender", "tender": "tender",
        "讲故事": "storytelling", "自然讲述": "storytelling", "storytelling": "storytelling",
        "情感电台": "radio", "电台": "radio", "radio": "radio",
        "磁性": "magnetic", "magnetic": "magnetic",
        "广告营销": "advertising", "advertising": "advertising",
        "气泡音": "vocal-fry", "vocal-fry": "vocal-fry",
        "低语": "ASMR", "asmr": "ASMR", "asMR": "ASMR",
        "新闻播报": "news", "news": "news",
        "娱乐八卦": "entertainment", "entertainment": "entertainment"
    };
    return map[value] || map[lower] || value;
}

function vvSupportedEmotion(item, emotion) {
    if (!item || !item.data || !emotion) return "";
    var styles = item.data.emotionStyles || [];
    for (var i = 0; i < styles.length; i++) {
        var s = styles[i] || {};
        if (vvString(s.id) === emotion || vvString(s.emotion) === emotion || vvString(s.name) === emotion) return vvString(s.emotion || s.id || emotion);
        if (vvEmotionAlias(s.name) === emotion) return vvString(s.emotion || s.id || emotion);
    }
    return "";
}

function vvResolveOfficialEmotion(item, request, data) {
    var selected = vvEmotionAlias(vvStyleValue(request || {}, data || {}));
    if (!selected) selected = vvEmotionAlias((data || {}).emotion || (request || {}).emotion || (data || {}).emotionName || (request || {}).emotionName);
    return vvSupportedEmotion(item, selected);
}

function vvClamp(value, min, max) {
    return Math.max(min, Math.min(max, value));
}

function vvRandomId() {
    return String(Math.floor(1e12 + 9e12 * Math.random()));
}

function vvUuid() {
    return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, function(character) {
        var random = Math.random() * 16 | 0;
        var value = character === "x" ? random : (random & 3 | 8);
        return value.toString(16);
    });
}

function vvBuildWsUrl() {
    var separator = vvRequest.wsUrl.indexOf("?") >= 0 ? "&" : "?";
    return vvRequest.wsUrl + separator +
        "voice=" + encodeURIComponent(vvRequest.voice) +
        "&format=" + encodeURIComponent(vvRequest.format) +
        "&sampleRate=" + encodeURIComponent(vvRequest.sampleRate) +
        "&appkey=" + encodeURIComponent(vvRequest.appKey) +
        "&device_platform=android" +
        "&os=android" +
        "&ssmix=a" +
        "&_rticket=" + String(Math.floor(Date.now() / 1000)) +
        "&cdid=" + vvUuid() +
        "&channel=vivo_515927" +
        "&aid=" + VV_APP_AID +
        "&app_name=" + VV_APP_NAME +
        "&version_code=" + VV_APP_VERSION_CODE +
        "&version_name=" + VV_APP_VERSION_NAME +
        "&manifest_version_code=" + VV_APP_VERSION_CODE +
        "&update_version_code=" + VV_APP_VERSION_CODE +
        "&resolution=1080*2310" +
        "&dpi=420" +
        "&device_type=23013RK75C" +
        "&device_brand=Redmi" +
        "&language=zh" +
        "&os_api=35" +
        "&os_version=15" +
        "&ac=wifi" +
        "&iid=" + vvRandomId() +
        "&device_id=" + vvRandomId() +
        "&qos_level=2" +
        "&qos_sdk_version=2";
}

function vvWebsocketHeaders() {
    return {
        "Origin": "wss://audio.myparallelstory.com",
        "User-Agent": "com.parallel.odyssey/1260050 (Linux; U; Android 15; zh_CN; 23013RK75C; Build/AQ3A.240912.001; Cronet/TTNetVersion:8c3b9c07 2024-07-30 QuicVersion:8915c07c 2024-04-19)",
        "X-SS-DP": VV_APP_AID
    };
}

function vvSetting(name, fallback) {
    var value = "";
    try { value = ttsrv.userVars[name]; } catch (e1) {}
    if (vvString(value).trim()) return vvString(value).trim();
    try { value = ttsrv.defVars[name]; } catch (e2) {}
    if (vvString(value).trim() && vvString(value).charAt(0) !== "{") return vvString(value).trim();
    return fallback;
}

function vvAppKey() {
    return vvSetting("apiKey", "") || vvSetting("appKey", VV_DEFAULT_APP_KEY);
}

function vvMappedItem(request) {
    request = request || {};
    var data = request.data || {};
    var voice = vvString(request.voice).trim();
    var tag = vvString(data.voiceTag || request.voiceTag || data.tag || request.tag).trim();
    return VV_BY_ID[voice] || VV_BY_TAG[tag] || VV_BY_TAG[voice] || null;
}

function vvEmotionPrompt(request, data) {
    var prompt = vvString(data.emotionPrompt || request.emotionPrompt).trim();
    if (prompt) return prompt;
    var emotion = vvString(data.emotion || request.emotion).trim();
    if (!emotion || emotion === "neutral" || emotion === "中性" || emotion === "自然") return "";
    var scale = vvNumber(data.emotionScale || data.vScale || request.emotionScale, 4);
    return "请用【" + emotion + "】情绪朗读当前文本，情绪强度约为 " + scale + "/5；保持自然，不要读出本提示。";
}

function vvWriteString(stream, value) {
    value = String(value);
    for (var i = 0; i < value.length; i++) stream.write(value.charCodeAt(i));
}

function vvWriteInt(stream, value) {
    stream.write(value & 255);
    stream.write((value >> 8) & 255);
    stream.write((value >> 16) & 255);
    stream.write((value >> 24) & 255);
}

function vvWriteShort(stream, value) {
    stream.write(value & 255);
    stream.write((value >> 8) & 255);
}

function vvWriteWavHeader(stream, dataLength, sampleRate) {
    var channels = 1;
    var bitsPerSample = 16;
    var byteRate = sampleRate * channels * bitsPerSample / 8;
    var blockAlign = channels * bitsPerSample / 8;
    vvWriteString(stream, "RIFF");
    vvWriteInt(stream, 36 + dataLength);
    vvWriteString(stream, "WAVE");
    vvWriteString(stream, "fmt ");
    vvWriteInt(stream, 16);
    vvWriteShort(stream, 1);
    vvWriteShort(stream, channels);
    vvWriteInt(stream, sampleRate);
    vvWriteInt(stream, byteRate);
    vvWriteShort(stream, blockAlign);
    vvWriteShort(stream, bitsPerSample);
    vvWriteString(stream, "data");
    vvWriteInt(stream, dataLength);
}

function vvToJavaBytes(buffer) {
    if (!buffer) return null;
    try {
        if (typeof buffer.getClass === "function" && buffer.getClass().isArray()) return buffer;
    } catch (e1) {}
    try {
        if (typeof buffer === "string") return Packages.android.util.Base64.decode(buffer, 0);
    } catch (e2) {}
    try {
        if (typeof buffer.length === "number" && buffer.length > 0) {
            var stream = new java.io.ByteArrayOutputStream(buffer.length);
            for (var i = 0; i < buffer.length; i++) stream.write(buffer[i] & 255);
            var bytes = stream.toByteArray();
            stream.close();
            return bytes;
        }
    } catch (e3) {}
    return null;
}

function vvAddChunk(bytes) {
    if (!bytes || bytes.length <= 0 || vvFinished) return;
    vvChunks.push(bytes);
    vvAudioLength += bytes.length;
    if (vvRequest && vvRequest.format === "pcm" && !vvWarmupBridgeUnavailable) {
        try {
            if (!vvWarmupBridgeStarted) {
                __jreadPcmStreamBridge.start("pcm_s16le", vvRequest.sampleRate, 1);
                vvWarmupBridgeStarted = true;
            }
            __jreadPcmStreamBridge.write(bytes);
        } catch (bridgeError) {
            vvWarmupBridgeUnavailable = true;
        }
    }
}

function vvFail(message) {
    if (vvFinished) return;
    vvFinished = true;
    var savedCallback = vvCallback;
    if (vvSocket !== null) {
        try { vvSocket.cancel(); } catch (e1) {}
        vvSocket = null;
    }
    try { if (vvWarmupBridgeStarted) __jreadPcmStreamBridge.error(message); } catch (bridgeError) {}
    try { savedCallback.error(message); } catch (e2) {}
}

function vvFinish(reason) {
    if (vvFinished) return;
    if (vvChunks.length === 0) {
        vvFail("猫箱未返回音频数据 (" + reason + ")");
        return;
    }
    vvFinished = true;
    var stream = null;
    var savedCallback = vvCallback;
    var savedRequest = vvRequest;
    try {
        if (savedRequest.format === "mp3") {
            stream = new java.io.ByteArrayOutputStream(vvAudioLength);
            for (var i = 0; i < vvChunks.length; i++) stream.write(vvChunks[i]);
        } else {
            var silenceLength = Math.floor(savedRequest.sampleRate * 2 * VV_SILENCE_MS / 1000);
            var dataLength = silenceLength + vvAudioLength + silenceLength;
            stream = new java.io.ByteArrayOutputStream(44 + dataLength);
            vvWriteWavHeader(stream, dataLength, savedRequest.sampleRate);
            for (var before = 0; before < silenceLength; before++) stream.write(0);
            for (var chunkIndex = 0; chunkIndex < vvChunks.length; chunkIndex++) stream.write(vvChunks[chunkIndex]);
            for (var after = 0; after < silenceLength; after++) stream.write(0);
        }
        try { if (vvWarmupBridgeStarted) __jreadPcmStreamBridge.complete(); } catch (bridgeError) {}
        savedCallback.write(stream.toByteArray());
        savedCallback.close();
    } catch (error) {
        try { savedCallback.error("猫箱音频处理失败: " + error.message); } catch (e1) {}
    } finally {
        if (stream !== null) try { stream.close(); } catch (e2) {}
        if (vvSocket !== null) try { vvSocket.cancel(); } catch (e3) {}
        vvSocket = null;
    }
}

function vvSendStartTask() {
    var payload = {
        audio_config: {
            format: vvRequest.format,
            sample_rate: vvRequest.sampleRate,
            loudness_rate: vvRequest.loudnessRate
        },
        extra: {
            post_process: {
                pitch: vvRequest.pitch,
                speech_rate: vvRequest.speed
            },
            max_length_to_filter_parenthesis: 0
        },
        speaker: vvRequest.voice
    };
    if (vvRequest.contextTexts.length > 0) payload.context_texts = vvRequest.contextTexts;
    if (vvRequest.officialEmotion) {
        payload.emotion = vvRequest.officialEmotion;
        payload.enable_emotion = true;
        payload.emotion_scale = vvRequest.emotionScale;
    }
    vvSocket.send(JSON.stringify({
        appkey: vvRequest.appKey,
        event: "StartTask",
        namespace: "BidirectionalTTS",
        payload: JSON.stringify(payload)
    }));
}

function vvSendTextAndFinish() {
    vvSocket.send(JSON.stringify({ payload: JSON.stringify({ text: vvRequest.text }) }));
    vvSocket.send(JSON.stringify({
        appkey: vvRequest.appKey,
        event: "FinishTask",
        namespace: "BidirectionalTTS"
    }));
}

function vvHandleText(message) {
    try {
        var data = JSON.parse(message);
        if (data.type === 3 && data.buffer) {
            vvAddChunk(Packages.android.util.Base64.decode(data.buffer, 0));
            return;
        }
        var event = vvString(data.event);
        if (event === "TaskStarted") {
            vvSendTextAndFinish();
        } else if (event === "TaskFinished") {
            vvFinish("Done");
        } else if (data.status_code && Number(data.status_code) !== 20000000) {
            vvFail("猫箱接口错误: " + vvString(data.status_text || message));
        }
    } catch (error) {
        try { console.warn("[猫箱VV合成核心] 无法解析文本帧: " + error.message); } catch (ignored) {}
    }
}

function vvConnect() {
    try {
        vvSocket = new Websocket(vvBuildWsUrl(), vvWebsocketHeaders());
        vvSocket.on("open", function() { vvSendStartTask(); });
        vvSocket.on("text", function(message) { vvHandleText(message); });
        vvSocket.on("binary", function(buffer) { vvAddChunk(vvToJavaBytes(buffer)); });
        vvSocket.on("close", function(code) {
            vvSocket = null;
            if (!vvFinished) vvFinish("Close:" + code);
        });
        vvSocket.on("error", function(error) { vvFail("猫箱 WebSocket 错误: " + error); });
    } catch (error) {
        vvFail("猫箱 WebSocket 连接失败: " + error.message);
    }
}

var PluginJS = {
    name: "猫箱VV官方音色核心（可出声10音色）",
    id: "maoxiang.vv.synth.official.103.emotion.v2",
    author: "Codex / source 火山官方音色列表",
    version: "20260717.usable10.no-pcm",
    iconUrl: "https://www.helloimg.com/i/2026/03/26/69c47d2e087de.jpg",

    onStop: function() {
        vvFinished = true;
        if (vvSocket !== null) try { vvSocket.cancel(); } catch (e) {}
        vvSocket = null;
    },

    getAudioV2: function(request, callback) {
        try {
            var item = vvMappedItem(request);
            if (!item) throw new Error("找不到音色映射: " + vvString(request && request.voice));
            var sourceData = item.data || {};
            var runtimeData = (request && request.data) || {};
            var appKey = vvAppKey();
            if (!appKey) throw new Error("插件 ApiKey/appKey 未配置：请在插件设置里填写原猫箱/Vivi 的 appKey");
            var contextTexts = [];
            var rolePrompt = vvString(sourceData.contextTexts).trim();
            if (rolePrompt) contextTexts.push(rolePrompt);
            var customPrompt = vvString(runtimeData.contextTexts || runtimeData.contextText || runtimeData.rolePrompt || runtimeData.prompt || runtimeData.voicePrompt).trim();
            if (customPrompt) contextTexts.push(customPrompt);
            var emotionPrompt = vvEmotionPrompt(request || {}, runtimeData);
            if (emotionPrompt) contextTexts.push(emotionPrompt);

            vvCallback = callback;
            vvChunks = [];
            vvAudioLength = 0;
            vvFinished = false;
            vvWarmupBridgeStarted = false;
            vvWarmupBridgeUnavailable = false;
            vvWarmupBridgeUnavailable = true;
            vvRequest = {
                text: vvString(request && request.text).trim(),
                voice: vvString(item.voice).trim(),
                format: vvString(sourceData.audioFormat || "mp3").toLowerCase(),
                sampleRate: parseInt(sourceData.sampleRate, 10) || 24000,
                speed: vvClamp(vvNumber(request && request.rate, 50) / 50, 0.5, 2),
                pitch: vvClamp(Math.round((vvNumber(request && request.pitch, 50) - 50) / 10), -5, 5),
                loudnessRate: vvClamp(vvNumber(request && request.volume, 50) - 50, -50, 100),
                contextTexts: contextTexts,
                officialEmotion: vvResolveOfficialEmotion(item, request || {}, runtimeData),
                emotionScale: vvClamp(vvNumber(runtimeData.emotionScale || runtimeData.vScale || request.emotionScale, sourceData.emotionScale || 4), 1, 5),
                appKey: appKey,
                wsUrl: vvSetting("wsUrl", VV_DEFAULT_WS_URL)
            };
            if (!vvRequest.text) throw new Error("合成文本为空");
            if (!vvRequest.voice) throw new Error("音色映射缺少底层 voice");
            vvConnect();
        } catch (error) {
            vvCallback = callback;
            vvFinished = false;
            vvFail("猫箱合成失败: " + error.message);
        }
    }
};

var EditorJS = {
    getAudioSampleRate: function(locale, voice) {
        var item = VV_BY_ID[vvString(voice)];
        return item && item.data ? (parseInt(item.data.sampleRate, 10) || 24000) : 24000;
    },
    getAudioFormat: function(locale, voice) {
        var item = VV_BY_ID[vvString(voice)];
        return item && item.data ? vvString(item.data.audioFormat || "pcm") : "pcm";
    },
    getLocales: function() {
        var out = {};
        var list = ["官方音色","官方多情绪","通用场景","角色扮演","视频配音","教育场景","客服场景","有声阅读","多语种"];
        for (var i = 0; i < list.length; i++) out[list[i]] = list[i];
        return out;
    },
    getVoices: function(locale) {
        var voices = {};
        var selectedLocale = vvString(locale).trim();
        for (var i = 0; i < VV_MAP.length; i++) {
            var item = VV_MAP[i];
            if (selectedLocale && selectedLocale !== "官方音色" && item.locale !== selectedLocale) continue;
            var suffix = item.data && item.data.emotionStyles && item.data.emotionStyles.length ? "（" + item.data.emotionStyles.length + "情绪）" : "";
            voices[item.id] = {
                id: item.id,
                name: (item.displayName || item.voiceTag || item.id) + suffix,
                gender: item.data && item.data.gender ? item.data.gender : (item.subGroupName || "官方音色")
            };
        }
        return voices;
    },
    onLoadUI: function(ctx, root) {
        // 宿主把真实 LinearLayout 容器传给 onLoadUI，自定义选择框必须在此用 JSpinner 创建并 addView。
        emotionSpinner = JSpinner(ctx, "情感");
        root.addView(emotionSpinner);
        try { ttsrv.setMargins(emotionSpinner, 0, 4, 0, 0); } catch (e1) {}
        emotionSpinner.setVisibility(View.GONE);
        emotionSpinner.setOnItemSelected(function(spinner, pos, item) {
            if (item && item.value) {
                storeEmotion(String(item.value), item.name ? String(item.name) : String(item.value));
            } else {
                storeEmotion("", "");
            }
        });
    },
    onVoiceChanged: function(locale, voiceCode) {
        // 刷新自建的情感 JSpinner（兼容不自动生成下拉的宿主）。
        // 全局 getVoiceStyles 一定存在，不会报未定义；无情感音色时自动隐藏。
        refreshEmotionSpinner(locale, voiceCode);
    }
};

// 全局函数：返回情感选项 map。
// - jing332 类宿主会直接调用本函数自动生成情感下拉框；
// - 本插件 onLoadUI/onVoiceChanged 也用它来填充自建的 JSpinner。
// 必须放在全局作用域（不能是 EditorJS 的方法），否则其它宿主调用时找不到。
function getVoiceStyles(locale, voice) {
    var item = VV_BY_ID[vvString(voice || locale)];
    var out = { "auto": "自动：跟随脚本情绪" };
    if (!item || !item.data || !item.data.emotionStyles || item.data.emotionStyles.length === 0) return out;
    for (var i = 0; i < item.data.emotionStyles.length; i++) {
        var style = item.data.emotionStyles[i] || {};
        var id = vvString(style.id || style.emotion || style.name).trim();
        if (id) out[id] = vvString(style.name || id);
    }
    return out;
}

function refreshEmotionSpinner(locale, voice) {
    if (!emotionSpinner) return;
    var map = getVoiceStyles(locale, voice);
    var keys = [];
    for (var k in map) { if (map.hasOwnProperty(k)) keys.push(k); }
    if (keys.length <= 1) { emotionSpinner.setVisibility(View.GONE); return; }
    var items = [];
    for (var i = 0; i < keys.length; i++) {
        items.push(Item(map[keys[i]], keys[i]));
    }
    emotionSpinner.items = items;
    var cur = "";
    try { cur = vvString(ttsrv.userVars["emotion"]); } catch (e2) {}
    var idx = 0;
    for (var j = 0; j < items.length; j++) {
        if (items[j] && items[j].value === cur) { idx = j; break; }
    }
    emotionSpinner.selectedPosition = idx;
    emotionSpinner.setVisibility(View.VISIBLE);
}

function storeEmotion(styleId, styleName) {
    try {
        ttsrv.userVars["emotion"] = vvString(styleId);
    } catch (e) {}
}