@js:
try {

// ===== 0. 文本预处理 =====
var text = speakText;

//java.log(text);
text = text.replace(/[【「『]([\u4E00-\u9Fa5]+)[】』」]/g, "$1");
      
text = text.replace(/(“[^“”\n]*)[【「『』」】]([^“”\n]*”)/g, "$1$2");
text = text.replace(/(“[^“”\n]*)[【「『』」】]([^“”\n]*”)/g, "$1$2");
text = text.replace(/(“[^“”\n]*)[【「『』」】]([^“”\n]*”)/g, "$1$2");
text = text.replace(/(“[^“”\n]*)[【「『』」】]([^“”\n]*”)/g, "$1$2");



text = text.replace(/【([^【】\n]+)】/g, '“<<括号4>>$1”');
text = text.replace(/『([^『』\n]+)』/g, '“<<括号3>>$1”');

text = text.replace(/“(<<[^<>]+>>)?([\u4E00-\u9FFF]{1,15})”/g, "$2");


var sfxDir = '/storage/emulated/0/Download/chajian/bendiyinxiao2/';
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

// ===== 1. 读取/下载角色映射表 =====
var MAP_FILE = '/storage/emulated/0/Download/chajian/mingwuyan/jiaoseliebiao-list.json';
var REMOTE_URL = 'https://cnb.cool/mingwuyan/yinpin/-/git/raw/main/jiaoseliebiao-list.json?download=true';
var DATA_DIR = '/storage/emulated/0/Download/chajian/mingwuyan/';
var RULE_PRESETS_URL = "https://cnb.cool/xiatian.ktn/tts/-/git/raw/main/rule_presets.json";
var RULE_PRESETS_CACHE_FILE = DATA_DIR + "rule_presets.json";
var VOICE_LIST_URL = "https://cnb.cool/xiatian.ktn/tts/-/git/raw/main/maojiandouwentts.json";
var VOICE_LIST_CACHE_FILE = DATA_DIR + "voice_list.json";

// 新增：jiaoseliebiao-2.json 的本地路径与远程地址
var JIAOSELIEBIAO2_FILE = '/storage/emulated/0/Download/chajian/mingwuyan/jiaoseliebiao-2.json';
var JIAOSELIEBIAO2_URL = 'https://cnb.cool/mingwuyan/yinpin/-/git/raw/main/jiaoseliebiao-2.json?download=true';

var tagConfig = {};

function isJsonLike(str) {
    return str && (str.charAt(0) === '[' || str.charAt(0) === '{');
}

function ensureJsonFile(localPath, remoteUrl) {
    try {
        var content = String(java.readExternalFile(localPath));
        if (isJsonLike(content)) return content;
    } catch(e) {}
    java.log('下载文件: ' + remoteUrl);
    try {
        var downloaded = String(java.ajax(remoteUrl, 60000));
        if (downloaded && isJsonLike(downloaded)) {
            java.writeExternalFile(localPath, downloaded);
            return downloaded;
        }
    } catch(e) {
        java.log('下载失败: ' + e);
    }
    return null;
}

try { var d = new java.io.File(DATA_DIR); if(!d.exists()) d.mkdirs(); } catch(e) {}

var raw = ensureJsonFile(MAP_FILE, REMOTE_URL);
ensureJsonFile(RULE_PRESETS_CACHE_FILE, RULE_PRESETS_URL);
ensureJsonFile(VOICE_LIST_CACHE_FILE, VOICE_LIST_URL);
// 新增：检测并下载 jiaoseliebiao-2.json
ensureJsonFile(JIAOSELIEBIAO2_FILE, JIAOSELIEBIAO2_URL);

var DEFAULT_VOICE = 'zh_female_vv_uranus_bigtts';
var DEFAULT_PROMPT = null;

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
                        if (!tag) continue;
                        var source = item.config.source;
                        var voice = (source && source.voice) || DEFAULT_VOICE;
                        var prompt = (source && source.data && source.data.contextTexts) || null;
                        var emotion = (source && source.data && source.data.emotion) || null;
                        var ap = item.config.audioParams || {};
                        var speed = ap.speed || (source && source.speed) || 1.0;
                        var volume = (ap.volume != null) ? ap.volume : ((source && source.volume != null) ? source.volume : 1);
                        tagConfig[tag] = {
                            voice: voice,
                            prompt: prompt,
                            emotion: emotion,
                            speed: speed,
                            volume: volume,
                            source: source
                        };
                    }
                }
            }
        }
    }
} catch (e) {
    java.log('JSON解析失败: ' + e);
}

// 旁白硬编码兜底
var NARRATOR_DEFAULT = {
    voice: 'zh_female_vv_uranus_bigtts',
    prompt: '[#设定：男声，年龄40-55岁。强制物理级锁定醇厚沉稳中低音绝对频段，底层强制开启宽厚饱满胸腔共鸣与苍劲通透喉结发音，彻底屏蔽轻浮跳脱、尖锐刺耳与稚嫩单薄感，全程评书腔调。赋予声音在中低音区岁月基底上醇厚稳重、抑扬顿挫的独特质感，声线浑厚有力，带岁月磨砂颗粒感，中气十足老练通透，绝对禁止稚嫩、禁止沙哑、禁止单薄伪音。咬字顿挫分明，语速张弛有度，尾音利落收束，语气沉稳大气，能在绝对纯正中年音域内完成评书叙事、悬念铺垫、生动演绎、感慨点评，绝不稚嫩轻浮、绝不尖锐刺耳。评书先生感、岁月沉淀感、看似沉稳老练实则声情并茂的中年男声，说话带贴耳醇厚呼吸感，用焊死中低音的极致厚重感，打造传统评书故事感中年声线。]',
    speed: 1.0,
    volume: 1
};
var narratorCfg = tagConfig["narration"] || NARRATOR_DEFAULT;

// ===== 2. 基础参数 =====
var BASE_URL     = 'wss://audio5-normal-hl.myparallelstory.com/internal/api/v1/ws';
var AUDIO_FORMAT = 'mp3';
var SAMPLE_RATE  = 24000;
var APP_KEY      = 'WQuVLKMGRo';
var TIMEOUT_MS   = 30000;
var PITCH_VALUE  = 0;
var SPEED_BOOST = speechRate / 20 * 1;

var NEEDS_CONTEXT_TEXTS = {
    'zh_female_vv_uranus_bigtts': true,
    'zh_female_vv_mars_bigtts': true,
    'zh_female_wenroutaozi_uranus_bigtts': true
};

// ===== 3. 拆分段落 =====
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
        var qStart = segText.indexOf('“', idx);
        if (qStart === -1) {
            var rem = segText.substring(idx);
            if (rem.trim().length > 0)
                segments.push({txt: rem, config: narratorCfg});
            break;
        }
        if (qStart > idx) {
            var pre = segText.substring(idx, qStart);
            if (pre.trim().length > 0)
                segments.push({txt: pre, config: narratorCfg});
        }

        var qEnd = segText.indexOf('”', qStart + 1);
        if (qEnd === -1) qEnd = segText.length - 1;
        var dialogText = segText.substring(qStart, qEnd + 1);

        var roleCfg = null;
        var match = dialogText.match(/<<([^>]+)>>/);
        if (match) {
            var tag = match[1];
            roleCfg = tagConfig[tag];
            dialogText = dialogText.replace(/<<[^>]+>>/, '');
        }
        if (!roleCfg) {
            roleCfg = {
                voice: DEFAULT_VOICE,
                prompt: null,
                emotion: null,
                speed: 1.0,
                volume: 1
            };
        }

        var pureText = dialogText.replace(/[“”]/g, '').trim();
        if (pureText.length > 0) {
            segments.push({txt: dialogText, config: roleCfg});
        }
        idx = qEnd + 1;
    }
}

if (segments.length === 0) {
    if (text.replace(/[“”]/g, '').trim().length > 0)
        segments.push({txt: text, config: narratorCfg});
}

// ===== 4. 合成音频 =====
var deviceId = String(Math.floor(1e12 + 9e12 * Math.random()));
var aid      = String(Math.floor(1e12 + 9e12 * Math.random()));
var out = ws.newBuffer();

function loadAndRotateSfx(fileName) {
    var filePath = sfxDir + fileName;
    try { var d = new java.io.File(sfxDir); if(!d.exists()) d.mkdirs(); } catch(e) {}

    var sfxJson = null;
    try {
        var raw = String(java.readExternalFile(filePath));
        if (raw && raw.charAt(0) === '{') sfxJson = JSON.parse(raw);
    } catch(e) {}

    if (!sfxJson) {
        var url = 'https://cnb.cool/mingwuyan/yinpin/-/git/raw/main/bdyinxiao3/' + fileName;
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

    if (sfxJson.audios.length > 1) {
        sfxJson.currentIndex = (index + 1) % sfxJson.audios.length;
        try { java.writeExternalFile(filePath, JSON.stringify(sfxJson)); } catch(e) {}
    }

    try {
        var bytes = android.util.Base64.decode(b64.trim(), android.util.Base64.DEFAULT);
        if (bytes && bytes.length > 0) {
            java.log('[音效] 解码成功，字节: ' + bytes.length + ' (' + fileName + ' idx=' + index + ')');
            return bytes;
        }
    } catch(e) { java.log('[音效] 解码异常: ' + e); }
    return null;
}

for (var i = 0; i < segments.length; i++) {
    var seg = segments[i];
    if (seg.type === 'sfx') {
        var sfxBytes = loadAndRotateSfx(seg.fileName);
        if (sfxBytes && sfxBytes.length > 0) out.write(sfxBytes);
        continue;
    }

    var pure = seg.txt ? seg.txt.replace(/[“”]/g, '').trim() : '';
    if (!seg.txt || pure.length === 0) continue;

    var cfg = seg.config;
    var query = 'voice=' + cfg.voice + '&format=' + AUDIO_FORMAT + '&sampleRate=' + SAMPLE_RATE + '&appkey=' + APP_KEY;
    var wsUrl = BASE_URL + '?' + query + '&ssmix=&aid=' + aid + '&device_id=' + deviceId;
    
    var segSpeed = cfg.speed || 1.0;
    var segVolume = cfg.volume != null ? cfg.volume : 1;
    var segRate = SPEED_BOOST * segSpeed;
    var loudness = Math.max(-48, (segVolume - 1) * 50);   // 最小限制 -48

    var extraObj = {};
    if (cfg.source && cfg.source.data) {
        extraObj = JSON.parse(JSON.stringify(cfg.source.data));
    }

    if (NEEDS_CONTEXT_TEXTS[cfg.voice]) {
        if (cfg.prompt) {
            extraObj.context_texts = [cfg.prompt];
        }
    } else {
        delete extraObj.context_texts;
    }

    extraObj.audio_config = {
        format: AUDIO_FORMAT,
        sample_rate: SAMPLE_RATE,
        loudness_rate: loudness
    };

    if (cfg.voice.indexOf('emo') !== -1 && cfg.emotion) {
        extraObj.audio_config.emotion = cfg.emotion;
        extraObj.audio_config.emotion_scale = 4;
    }

    var extra = JSON.stringify(extraObj);

    var audio = ws.maoxiang(wsUrl, seg.txt, cfg.voice, AUDIO_FORMAT, SAMPLE_RATE, segRate, PITCH_VALUE, APP_KEY, TIMEOUT_MS, extra);
    if (audio && audio.length > 0) {
        out.write(audio);
    } else {
        throw new Error('[合成] 返回空音频: ' + seg.txt.substring(0, 20));
    }
}

out.toByteArray();

} catch (e) {
    java.log("脚本异常: " + e);
    throw e;
}
