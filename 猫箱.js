var req = {};
var callback = null;
var ws = null;
var audioChunks = [];
var audioLength = 0;
var isFinished = false;
var isRequesting = false;
var lastRequestTime = 0;
var currentRequestId = 0;
var isWsReady = false;
var wsConnectTime = 0;
var WS_IDLE_TIMEOUT = 60000;
var lastVoiceId = "";
var timeoutHandler = null;
var currentTaskId = "";
var SPECIAL_VOICES = {
    "zh_female_wenroutaozi_uranus_bigtts": true,
    "zh_female_vv_uranus_bigtts": true
};

var CONFIG = {
    silenceMs: 100,
    timeoutMs: 30000,
    sampleRate: 24000,
    appkey: "WQuVLKMGRo"
};
var cachedSilenceBytes = null;
var cachedSilenceLength = 0;

function getSilenceBytes(length) {
    try {
        if (cachedSilenceBytes === null || cachedSilenceLength !== length) {
            var baos = new java.io.ByteArrayOutputStream(length);
            try {
                for (var i = 0; i < length; i++) {
                    baos.write(0);
                }
                cachedSilenceBytes = baos.toByteArray();
                cachedSilenceLength = length;
            } finally {
                try { baos.close(); } catch(e) {}
            }
        }
        return cachedSilenceBytes;
    } catch(e) {
        return null;
    }
}

function generateId() {
    return String(Math.floor(1e12 + 9e12 * Math.random()));
}

function writeString(stream, str) {
    var s = String(str);
    for (var i = 0; i < s.length; i++) {
        stream.write(s.charCodeAt(i));
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

function writeWavHeader(stream, dataLength, sampleRate) {
    var sr = sampleRate || CONFIG.sampleRate;
    var channels = 1;
    var bitsPerSample = 16;
    var byteRate = sr * channels * bitsPerSample / 8;
    var blockAlign = channels * bitsPerSample / 8;
    writeString(stream, 'RIFF');
    writeInt(stream, 36 + dataLength);
    writeString(stream, 'WAVE');
    writeString(stream, 'fmt ');
    writeInt(stream, 16);
    writeShort(stream, 1);
    writeShort(stream, channels);
    writeInt(stream, sr);
    writeInt(stream, byteRate);
    writeShort(stream, blockAlign);
    writeShort(stream, bitsPerSample);
    writeString(stream, 'data');
    writeInt(stream, dataLength);
}

var VOICE_LIST = [
    {voice_id: "zh_female_wenroutaozi_uranus_bigtts", voice_name: "温柔桃子(高级)", gender: "female", special: true},
    {voice_id: "zh_female_vv_uranus_bigtts", voice_name: "VV(高级)", gender: "female", special: true},
    
    
    
    
    
];

var PluginJS = {
    "name": "猫箱-(多感情)",
    "id": "maoxiang.tts.dgq",
    "author": "TTS Server",
    "version": 2,
    "iconUrl": "https://lf-flow-web-cdn.myparallelstory.com/obj/parallel-bucket-cn/maoxiangv1-logo/box-logo.png",
    "onStop": function() {
        if (timeoutHandler !== null) {
            timeoutHandler.interrupt();
            timeoutHandler = null;
        }
        if (ws !== null) {
            try { ws.cancel(); } catch(e) {}
            ws = null;
        }
        isRequesting = false;
        isWsReady = false;
        lastVoiceId = "";
    },
    "getAudioV2": function(request, callback2) {
        try {
            if (isRequesting) {
                callback2.error("请等待当前请求完成");
                try { callback2.close(); } catch(e) {}
                return;
            }
            var now = Date.now();
            if (now - lastRequestTime < 200) {
                callback2.error("请求过快，请稍后再试");
                try { callback2.close(); } catch(e) {}
                return;
            }
            isRequesting = true;
            lastRequestTime = now;
            currentRequestId++;
            var speechRateFactor = 1.0;
            var pitchValue = 0;
            var rate = Number(request.rate) || 50;
            var pitch = Number(request.pitch) || 50;
            speechRateFactor = rate / 50 * 1.15;
            speechRateFactor = Math.max(0.5, Math.min(2.0, speechRateFactor));
            pitchValue = Math.round((pitch - 50) / 10);
            pitchValue = Math.max(-5, Math.min(5, pitchValue));
            callback = callback2;
            audioChunks = [];
            audioLength = 0;
            isFinished = false;
            var voiceId = String(request.voice || "").trim();
            if (!voiceId) {
                voiceId = "ICL_5561786db01b";
            }
            var audioFormat = String(ttsrv.tts.data["audioFormat"] || "mp3");
            var sampleRate = parseInt(ttsrv.tts.data["sampleRate"]) || CONFIG.sampleRate;
            var contextTexts = String(ttsrv.tts.data["contextTexts"] || "");
            req = {
                text: String(request.text || ""),
                voice: voiceId,
                speechRateFactor: speechRateFactor,
                pitchValue: pitchValue,
                format: audioFormat,
                sampleRate: sampleRate,
                contextTexts: contextTexts
            };
            var inputTaskId = String(request.task_id || "").trim();
            if (inputTaskId) {
                currentTaskId = inputTaskId;
            }
            var canReuseConnection = false;
            if (ws !== null && isWsReady) {
                var idleTime = now - wsConnectTime;
                if (idleTime < WS_IDLE_TIMEOUT && lastVoiceId === voiceId) {
                    canReuseConnection = true;
                } else {
                    try { ws.cancel(); } catch(e) {}
                    ws = null;
                    isWsReady = false;
                }
            }
            if (canReuseConnection) {
                sendStartTask();
                startTimeoutCheck();
            } else {
                connectWebSocket();
            }
            lastVoiceId = voiceId;
        } catch(e) {
            isRequesting = false;
            try { callback2.error("插件异常: " + e.message); } catch(e2) {}
            try { callback2.close(); } catch(e2) {}
        }
    }
};

// 解析contextTexts规则
function parseContextRules(contextTexts) {
    var rules = [];
    if (!contextTexts || !contextTexts.trim()) {
        return rules;
    }
    var lines = contextTexts.split('\n');
    
    // 检查是否只有一行且没有##，直接作为默认提示词
    if (lines.length === 1 && lines[0].indexOf('##') === -1) {
        var singleLine = lines[0].trim();
        if (singleLine) {
            return {
                type: 'direct',
                promptText: singleLine
            };
        }
        return null;
    }
    
    // 多行规则模式
    for (var i = 0; i < lines.length; i++) {
        var line = lines[i].trim();
        if (!line) continue;
        var separatorIndex = line.indexOf('##');
        if (separatorIndex === -1) continue;
        var regexStr = line.substring(0, separatorIndex).trim();
        var promptText = line.substring(separatorIndex + 2).trim();
        if (!regexStr || !promptText) continue;
        try {
            var regex = new RegExp(regexStr);
            rules.push({
                regex: regex,
                regexStr: regexStr,
                promptText: promptText
            });
        } catch(e) {
            // 正则表达式无效，跳过这条规则
        }
    }
    return {
        type: 'rules',
        rules: rules
    };
}

// 根据text获取提示词
function getContextPrompt(text, parseResult) {
    if (!parseResult) {
        return null;
    }
    
    // 直接模式：单行无分隔符
    if (parseResult.type === 'direct') {
        return parseResult.promptText;
    }
    
    // 规则匹配模式
    if (parseResult.type === 'rules' && parseResult.rules) {
        for (var i = 0; i < parseResult.rules.length; i++) {
            var rule = parseResult.rules[i];
            try {
                if (rule.regex.test(text)) {
                    return rule.promptText;
                }
            } catch(e) {
                // 匹配失败，继续下一条
            }
        }
    }
    
    return null;
}

function buildWsUrl() {
    var deviceId = generateId();
    var aid = generateId();
    return "wss://audio5-normal-hl.myparallelstory.com/internal/api/v1/ws?ssmix=&aid=" + aid + "&device_id=" + deviceId;
}

function connectWebSocket() {
    try {
        var wsUrl = buildWsUrl();
        ws = new Websocket(wsUrl, {});
    } catch(e) {
        isRequesting = false;
        try { callback.error("WebSocket连接失败: " + e.message); } catch(e2) {}
        try { callback.close(); } catch(e2) {}
        return;
    }
    ws.on('open', function() {
        isWsReady = true;
        wsConnectTime = Date.now();
        sendStartTask();
        startTimeoutCheck();
    });
    ws.on('close', function(code) {
        ws = null;
        isWsReady = false;
        isRequesting = false;
        flushAudio("Close:" + code);
    });
    ws.on('error', function(err) {
        ws = null;
        isWsReady = false;
        isRequesting = false;
        try { callback.error("WebSocket错误: " + err); } catch(e) {}
        try { callback.close(); } catch(e) {}
    });
    ws.on('text', function(msg) {
        try {
            var data = JSON.parse(msg);
            if (data.type === 3 && data.buffer) {
                var decoded = android.util.Base64.decode(data.buffer, 0);
                if (decoded && decoded.length > 0) {
                    audioChunks.push(decoded);
                    audioLength += decoded.length;
                }
            } else {
                handleTextMessage(msg);
            }
        } catch(e) {
            handleTextMessage(msg);
        }
    });
    ws.on('binary', function(buf) {
        handleBinaryData(buf);
    });
}

function sendStartTask() {
    try {
        var payloadObj = {
            audio_config: {
                format: req.format,
                sample_rate: req.sampleRate
            },
            extra: {
                post_process: {
                    pitch: req.pitchValue,
                    speech_rate: req.speechRateFactor
                }
            },
            speaker: req.voice
        };
        
        // 解析并获取提示词
        if (req.contextTexts && req.contextTexts.trim()) {
            var parseResult = parseContextRules(req.contextTexts);
            var prompt = getContextPrompt(req.text, parseResult);
            if (prompt) {
                payloadObj.context_texts = [prompt];
            }
        }
        
        var msg = {
            appkey: CONFIG.appkey,
            event: "StartTask",
            namespace: "BidirectionalTTS",
            payload: JSON.stringify(payloadObj)
        };
        if (currentTaskId) {
            msg.task_id = currentTaskId;
        }
        ws.send(JSON.stringify(msg));
    } catch(e) {
        var savedCallback = callback;
        try { savedCallback.error("发送StartTask失败: " + e.message); } catch(e2) {}
        try { savedCallback.close(); } catch(e2) {}
    }
}

function sendText(text) {
    try {
        var textStr = String(text);
        var msg = { payload: JSON.stringify({ text: textStr }) };
        ws.send(JSON.stringify(msg));
    } catch(e) {}
}

function sendFinishTask() {
    try {
        var msg = {
            appkey: CONFIG.appkey,
            event: "FinishTask",
            namespace: "BidirectionalTTS"
        };
        ws.send(JSON.stringify(msg));
    } catch(e) {}
}

function handleTextMessage(msg) {
    try {
        var data = JSON.parse(msg);
        var event = data.event || "";
        if (event === "TaskStarted") {
            if (data.task_id && data.task_id !== currentTaskId) {
                currentTaskId = data.task_id;
            }
            sendText(req.text);
            sendFinishTask();
        } else if (event === "TaskFinished") {
            flushAudio("Done");
        } else if (data.status_code && data.status_code !== 20000000) {
            var savedCallback = callback;
            try { savedCallback.error("API错误: " + (data.status_text || JSON.stringify(data))); } catch(e) {}
            try { savedCallback.close(); } catch(e) {}
        }
    } catch(e) {}
}

function handleBinaryData(buf) {
    try {
        if (!buf) return;
        var javaBytes = null;
        if (typeof buf.getClass === 'function' && buf.getClass().isArray()) {
            javaBytes = buf;
        } else if (typeof buf === 'string') {
            javaBytes = android.util.Base64.decode(buf, 0);
        } else if (typeof buf === 'object' && buf !== null) {
            if (typeof buf.length === 'number' && buf.length > 0) {
                var baos = new java.io.ByteArrayOutputStream(buf.length);
                try {
                    for (var i = 0; i < buf.length; i++) {
                        baos.write(buf[i] & 0xFF);
                    }
                    javaBytes = baos.toByteArray();
                } finally {
                    try { baos.close(); } catch(e) {}
                }
            } else if (typeof buf.toString === 'function') {
                try {
                    javaBytes = android.util.Base64.decode(buf.toString('base64'), 0);
                } catch(e) {}
            }
        }
        if (javaBytes && javaBytes.length > 0) {
            audioChunks.push(javaBytes);
            audioLength += javaBytes.length;
        }
    } catch(e) {}
}

function startTimeoutCheck() {
    if (timeoutHandler !== null) {
        timeoutHandler.interrupt();
        timeoutHandler = null;
    }
    var requestId = currentRequestId;
    var savedCallback = callback;
    var t = new java.lang.Thread(new java.lang.Runnable({
        run: function() {
            try {
                java.lang.Thread.sleep(CONFIG.timeoutMs);
                if (!isFinished && ws !== null && requestId === currentRequestId) {
                    try { ws.cancel(); } catch(e) {}
                    ws = null;
                    isWsReady = false;
                    isRequesting = false;
                    if (audioChunks.length > 0) {
                        flushAudio("Timeout");
                    } else {
                        try { savedCallback.error("请求超时，请检查网络或配置"); } catch(e) {}
                        try { savedCallback.close(); } catch(e) {}
                    }
                }
            } catch(e) {}
            timeoutHandler = null;
        }
    }));
    timeoutHandler = t;
    t.start();
}

function flushAudio(reason) {
    if (isFinished) return;
    isFinished = true;
    if (timeoutHandler !== null) {
        timeoutHandler.interrupt();
        timeoutHandler = null;
    }
    var stream = null;
    var savedCallback = callback;
    var savedReq = req;
    try {
        if (audioChunks.length === 0) {
            try { savedCallback.error("无音频数据 (" + reason + ")"); } catch(e) {}
            return;
        }
        if (savedReq.format === "mp3") {
            var totalLen = audioLength;
            stream = new java.io.ByteArrayOutputStream(totalLen);
            for (var j = 0; j < audioChunks.length; j++) {
                var chunk = audioChunks[j];
                if (chunk && chunk.length > 0) {
                    stream.write(chunk);
                }
            }
            var finalBytes = stream.toByteArray();
            savedCallback.write(finalBytes);
        } else {
            var silenceBytes = savedReq.sampleRate * 2 * CONFIG.silenceMs / 1000;
            var totalLen = silenceBytes + audioLength + silenceBytes;
            stream = new java.io.ByteArrayOutputStream();
            writeWavHeader(stream, totalLen, savedReq.sampleRate);
            var silenceArray = getSilenceBytes(silenceBytes);
            if (silenceArray) {
                stream.write(silenceArray);
            }
            for (var j = 0; j < audioChunks.length; j++) {
                var chunk = audioChunks[j];
                if (chunk && chunk.length > 0) {
                    stream.write(chunk);
                }
            }
            if (silenceArray) {
                stream.write(silenceArray);
            }
            var finalBytes = stream.toByteArray();
            savedCallback.write(finalBytes);
        }
    } catch(e) {
        try { savedCallback.error("音频处理错误: " + e.message); } catch(e2) {}
    } finally {
        if (stream !== null) {
            try { stream.close(); } catch(e) {}
        }
        try { savedCallback.close(); } catch(e) {}
        isRequesting = false;
    }
}

var EditorJS = {
    "getAudioSampleRate": function(locale, voice) {
        return parseInt(ttsrv.tts.data["sampleRate"]) || CONFIG.sampleRate;
    },
    "getAudioFormat": function(locale, voice) {
        var format = String(ttsrv.tts.data["audioFormat"] || "mp3");
        return format === "mp3" ? "mp3" : "wav";
    },
    "getLocales": function() {
        return {
            "all": "全部音色",
            "special": "特殊音色",
            "male": "男声",
            "female": "女声"
        };
    },
    "getVoices": function(gender) {
        var mm = {};
        for (var i = 0; i < VOICE_LIST.length; i++) {
            var v = VOICE_LIST[i];
            var isSpecialVoice = v.special ? true : false;
            var match = false;
            if (gender === "all") {
                match = true;
            } else if (v.gender === gender) {
                match = true;
            } else if (gender === "special" && isSpecialVoice) {
                match = true;
            }
            if (match) {
                mm[v.voice_id] = { name: v.voice_name, gender: v.gender };
            }
        }
        return mm;
    },
    "onLoadData": function() {
        // 初始化数据，确保字段存在
        if (typeof ttsrv.tts.data["contextTexts"] === "undefined") {
            ttsrv.tts.data["contextTexts"] = "";
        }
        if (typeof ttsrv.tts.data["audioFormat"] === "undefined") {
            ttsrv.tts.data["audioFormat"] = "mp3";
        }
        if (typeof ttsrv.tts.data["sampleRate"] === "undefined") {
            ttsrv.tts.data["sampleRate"] = "24000";
        }
    },
    "onLoadUI": function(ctx, linearLayout) {
        // 检测当前是否为深色模式
        var isDarkMode = false;
        try {
            var configuration = ctx.getResources().getConfiguration();
            var uiMode = configuration.uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            isDarkMode = (uiMode === android.content.res.Configuration.UI_MODE_NIGHT_YES);
        } catch (e) {
            console.error("检测深色模式失败: " + e.toString());
        }
        
        // 根据主题设置颜色
        var textColor = isDarkMode ? android.graphics.Color.WHITE : android.graphics.Color.BLACK;
        var hintColor = isDarkMode ? android.graphics.Color.parseColor("#AAAAAA") : android.graphics.Color.parseColor("#666666");
        var backgroundColor = isDarkMode ? android.graphics.Color.parseColor("#2C2C2C") : android.graphics.Color.WHITE;
        var borderColor = isDarkMode ? android.graphics.Color.parseColor("#555555") : android.graphics.Color.parseColor("#CCCCCC");

        // 音频格式选择
        var formatSpinner = JSpinner(ctx, "音频格式");
        linearLayout.addView(formatSpinner);
        ttsrv.setMargins(formatSpinner, 0, 8, 0, 0);

        var formatItems = [
            Item("MP3", "mp3"),
            Item("PCM (WAV)", "pcm")
        ];
        formatSpinner.items = formatItems;

        var currentFormat = ttsrv.tts.data["audioFormat"] || "mp3";
        for (var i = 0; i < formatItems.length; i++) {
            if (formatItems[i].value === currentFormat) {
                formatSpinner.selectedPosition = i;
                break;
            }
        }

        formatSpinner.setOnItemSelected(function(spinner, pos, item) {
            ttsrv.tts.data["audioFormat"] = String(item.value);
        });

        // 采样率选择
        var sampleRateSpinner = JSpinner(ctx, "采样率 (Hz)");
        linearLayout.addView(sampleRateSpinner);
        ttsrv.setMargins(sampleRateSpinner, 0, 8, 0, 0);

        var sampleRateItems = [
            Item("24000", 24000),
            Item("32000", 32000),
            Item("44100", 44100),
            Item("48000", 48000)
        ];
        sampleRateSpinner.items = sampleRateItems;

        var currentSampleRate = parseInt(ttsrv.tts.data["sampleRate"]) || 24000;
        for (var i = 0; i < sampleRateItems.length; i++) {
            if (sampleRateItems[i].value === currentSampleRate) {
                sampleRateSpinner.selectedPosition = i;
                break;
            }
        }

        sampleRateSpinner.setOnItemSelected(function(spinner, pos, item) {
            ttsrv.tts.data["sampleRate"] = String(Math.floor(Number(item.value)));
        });

        // 提示词标签 - 适配深色模式
        var contextLabel = new android.widget.TextView(ctx);
        contextLabel.setText("提示词规则（每行：正则##提示词，或直接输入提示词）");
        contextLabel.setTextSize(14);
        contextLabel.setTextColor(textColor);
        linearLayout.addView(contextLabel);
        ttsrv.setMargins(contextLabel, 0, 8, 0, 0);
        contextLabel.setVisibility(android.view.View.GONE);
        
        // 使用原生 Android EditText，适配深色模式
        var contextInput = new android.widget.EditText(ctx);
        contextInput.setHint("每行一个规则，格式：正则##提示词\n或直接输入提示词");
        contextInput.setSingleLine(false);
        contextInput.setMinLines(3);
        contextInput.setMaxLines(10);
        contextInput.setGravity(android.view.Gravity.TOP | android.view.Gravity.LEFT);
        
        // 设置文字颜色（适配深色模式）
        contextInput.setTextColor(textColor);
        contextInput.setHintTextColor(hintColor);
        
        // 设置背景样式 - 使用半透明的边框，适配深色/浅色模式
        var backgroundDrawable = new android.graphics.drawable.GradientDrawable();
        backgroundDrawable.setStroke(2, borderColor);
        backgroundDrawable.setColor(backgroundColor);
        backgroundDrawable.setCornerRadius(8);
        contextInput.setBackground(backgroundDrawable);
        
        var layoutParams = new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        );
        contextInput.setLayoutParams(layoutParams);
        contextInput.setPadding(16, 16, 16, 16);
        
        linearLayout.addView(contextInput);
        ttsrv.setMargins(contextInput, 0, 8, 0, 0);
        contextInput.setVisibility(android.view.View.GONE);

        // 加载已保存的值
        var savedText = String(ttsrv.tts.data["contextTexts"] || "");
        contextInput.setText(savedText);

        // 文本变化监听 - 实时保存到 data
        var textWatcher = new android.text.TextWatcher({
            beforeTextChanged: function(charSequence, start, count, after) {},
            onTextChanged: function(charSequence, start, before, count) {},
            afterTextChanged: function(editable) {
                var text = String(editable.toString());
                ttsrv.tts.data["contextTexts"] = text;
                android.util.Log.d("TTSPlugin", "Context text saved, length: " + text.length);
            }
        });
        contextInput.addTextChangedListener(textWatcher);

        // 调试按钮 - 适配深色模式
        var checkButton = new android.widget.Button(ctx);
        checkButton.setText("检查保存长度");
        checkButton.setVisibility(android.view.View.GONE);
        
        // 按钮颜色适配
        var buttonBgColor = isDarkMode ? android.graphics.Color.parseColor("#444444") : android.graphics.Color.parseColor("#E0E0E0");
        var buttonTextColor = isDarkMode ? android.graphics.Color.WHITE : android.graphics.Color.BLACK;
        checkButton.setBackgroundColor(buttonBgColor);
        checkButton.setTextColor(buttonTextColor);
        
        linearLayout.addView(checkButton);
        ttsrv.setMargins(checkButton, 0, 8, 0, 0);
        
        checkButton.setOnClickListener(new android.view.View.OnClickListener({
            onClick: function(v) {
                var currentText = String(ttsrv.tts.data["contextTexts"] || "");
                var inputText = String(contextInput.getText() ? contextInput.getText().toString() : "");
                android.widget.Toast.makeText(ctx, 
                    "Data长度: " + currentText.length + "\n输入框长度: " + inputText.length, 
                    android.widget.Toast.LENGTH_LONG).show();
            }
        }));

        this.formatSpinner = formatSpinner;
        this.sampleRateSpinner = sampleRateSpinner;
        this.contextInput = contextInput;
        this.contextLabel = contextLabel;
        this.checkButton = checkButton;
        this.isDarkMode = isDarkMode; // 保存主题状态供后续使用
    },
    "onVoiceChanged": function(locale, voiceCode) {
        if (ws !== null) {
            try { ws.cancel(); } catch(e) {}
            ws = null;
        }
        isWsReady = false;

        var isSpecial = SPECIAL_VOICES[voiceCode] ? true : false;
        if (isSpecial) {
            this.contextInput.setVisibility(android.view.View.VISIBLE);
            this.contextLabel.setVisibility(android.view.View.VISIBLE);
            this.checkButton.setVisibility(android.view.View.VISIBLE);
        } else {
            this.contextInput.setVisibility(android.view.View.GONE);
            this.contextLabel.setVisibility(android.view.View.GONE);
            this.checkButton.setVisibility(android.view.View.GONE);
        }
    }
};
