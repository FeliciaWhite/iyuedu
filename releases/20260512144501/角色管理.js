let PluginJS = {
    'name': '角色管理  2.665',
    'id': 'mingwuyan', 
    'author': '命無言',
    'iconUrl': 'https://cnb.cool/mingwuyan/yinpin/-/git/raw/main/doubao/mingwuyan.png',
    'version': 37,
  
    // 【核心修改：新增http开头文本的直接下载逻辑】
    'getAudio': function (text, locale, voice, speed, volume, pitch) {
        const targetText = text.trim();
        
        // 第一步：判断是否为http/https网址，是则直接下载
        if (targetText.startsWith("http://") || targetText.startsWith("https://")) {
            var audioResponse = ttsrv.httpGet(targetText);
            if (audioResponse.code() !== 200) {
                throw "网址下载失败，网址：" + targetText + "，状态码：" + audioResponse.code();
            }
            return audioResponse.body().byteStream(); // 直接返回音频流
        }
        // 第二步：非网址文本，走原有音效配置匹配逻辑（不变）
        var yinxiaoPath = "yinxiao.json";
        var yinxiaoJson = ttsrv.readTxtFile(yinxiaoPath);
        
        if (!yinxiaoJson || yinxiaoJson.trim() === "") {
            throw "音效配置文件[" + yinxiaoPath + "]不存在或为空";
        }
        
        var yinxiaoList = null;
        try {
            yinxiaoList = JSON.parse(yinxiaoJson);
        } catch (e) {
            throw "音效配置文件格式错误：" + e.toString();
        }
        
        if (!Array.isArray(yinxiaoList)) {
            throw "音效配置文件应为数组格式";
        }
        
        var targetSound = null;
        const specialPrefixes = ["@", "#", ">", "<", "＞", "＜"];
        for (var i = 0; i < yinxiaoList.length; i++) {
            var soundItem = yinxiaoList[i];
            if (soundItem && soundItem.name) {
                const isSpecial = specialPrefixes.some(prefix => soundItem.name.startsWith(prefix));
                if (isSpecial) {
                    if (soundItem.name === targetText) {
                        targetSound = soundItem;
                        break;
                    }
                } else {
                    var soundNames = soundItem.name.split('|').map(name => name.trim());
                    if (soundNames.includes(targetText)) {
                        targetSound = soundItem;
                        break;
                    }
                }
            }
        }
        
        if (!targetSound || !targetSound.url) {
            throw "未找到文字[" + text + "]对应的音效配置";
        }
        
        var audioResponse = ttsrv.httpGet(targetSound.url);
        if (audioResponse.code() !== 200) {
            throw "音效下载失败，网址：" + targetSound.url + "，状态码：" + audioResponse.code();
        }
        
        return audioResponse.body().byteStream();
    }
  }
  
  // 以下其余代码（EditorJS、角色管理、音效辅助函数等）均保持不变，此处省略重复内容
  
  
  
  const voices = {
    "tts.default.placeholder": { name: "默认发音人", locale: "zh-CN", gender: "female" }
  }
  
  // 定义4个变量，分别对应4排关键词（可按需调整每排内容）
  const PRESET_KEYWORDS_ROW1 = ["女童", "少女", "女青年", "女中年", "女老年"]; // 第一排
  const PRESET_KEYWORDS_ROW2 = ["男童", "少年", "男青年", "男中年", "男老年"]; // 第二排
  const PRESET_KEYWORDS_ROW3 = ["男主", "女主", "知性", "温柔", "磁性"]; // 第三排（新增2个常用词，可删改）
  const PRESET_KEYWORDS_ROW4 = ["微软", "讯飞", "火山", "呱呱"]; // 第四排（新增，可自定义）
  
  var GENSHIN_CHARACTERS = {
    '女 主角': { gender: '女', age: '主角', voice: '默认发音人' },
    '女 女童': { gender: '女', age: '女童', voice: '默认发音人' },
    '女 少女': { gender: '女', age: '少女', voice: '默认发音人' },
    '女 女青年': { gender: '女', age: '女青年', voice: '默认发音人' },
    '女 女中年': { gender: '女', age: '女中年', voice: '默认发音人' },
    '女 女老年': { gender: '女', age: '女老年', voice: '默认发音人' },
    '男 主角': { gender: '男', age: '主角', voice: '默认发音人' },
    '男 男童': { gender: '男', age: '男童', voice: '默认发音人' },
    '男 少年': { gender: '男', age: '少年', voice: '默认发音人' },
    '男 男青年': { gender: '男', age: '男青年', voice: '默认发音人' },
    '男 男中年': { gender: '男', age: '男中年', voice: '默认发音人' },
    '男 男老年': { gender: '男', age: '男老年', voice: '默认发音人' }
  };
  // 正向映射：存储值→显示值（从本地JSON读取映射表，全字符精确匹配）
  function replaceFayinrenName(name) {
    var originalName = name || '';
    var mapData = [];
    
    // 读取本地映射表文件
    try {
      var jsonContent = ttsrv.readTxtFile("fayinren_personality_summary.json");
      if (jsonContent && jsonContent.trim() !== "") {
        mapData = JSON.parse(jsonContent);
        // 校验是否为二维数组（符合用户提供的格式）
        if (!Array.isArray(mapData) || (mapData.length > 0 && !Array.isArray(mapData[0]))) {
          console.error("fayinren_personality_summary.json格式错误：需为二维数组");
          mapData = [];
        }
      } else {
        console.warn("fayinren_personality_summary.json文件为空");
      }
    } catch (e) {
      console.error("读取fayinren_personality_summary.json失败：" + e.toString());
    }
    
    // 全字符精确匹配存储值→显示值
    for (var i = 0; i < mapData.length; i++) {
      var storeVal = mapData[i][0] || '';
      var displayVal = mapData[i][1] || '';
      if (storeVal === originalName) {
        return displayVal;
      }
    }
    return originalName; // 无匹配则返回原字符串
  }
  
  // 反向映射：显示值→存储值（从本地JSON读取映射表，全字符精确匹配）
  function reverseReplaceFayinrenName(displayName) {
    var originalName = displayName || '';
    var mapData = [];
    
    // 读取本地映射表文件
    try {
      var jsonContent = ttsrv.readTxtFile("fayinren_personality_summary.json");
      if (jsonContent && jsonContent.trim() !== "") {
        mapData = JSON.parse(jsonContent);
        // 校验是否为二维数组（符合用户提供的格式）
        if (!Array.isArray(mapData) || (mapData.length > 0 && !Array.isArray(mapData[0]))) {
          console.error("fayinren_personality_summary.json格式错误：需为二维数组");
          mapData = [];
        }
      } else {
        console.warn("fayinren_personality_summary.json文件为空");
      }
    } catch (e) {
      console.error("读取fayinren_personality_summary.json失败：" + e.toString());
    }
    
    // 全字符精确匹配显示值→存储值
    for (var i = 0; i < mapData.length; i++) {
      var displayVal = mapData[i][1] || '';
      var storeVal = mapData[i][0] || '';
      if (displayVal === originalName) {
        return storeVal;
      }
    }
    return originalName; // 无匹配则返回原字符串
  }
  
  
  
  
  function CharacterManager() {
    this.characterRecords = [];
    this.contextHistory = "";
    this.activeRecordLimit = 100;
  }
  
  CharacterManager.prototype.saveRecords = function() {
    console.log("保存角色记录");
  };
  
  CharacterManager.prototype.loadRecords = function() {
    console.log("加载角色记录");
  };
  
  var characterManager = new CharacterManager();
  characterManager.loadRecords();
  
  let EditorJS = {
    'getAudioSampleRate': function (locale, voice) {
        return 44100;
    },
  
    "isNeedDecode": function (locale, voice) {
        return true;
    },
  
    'onLoadData': function () {
    },
  
    'getLocales': function () {
        return Object.values(voices).map(function(item) { return item.locale; });
    },
  
    'getVoices': function (locale) {
        return Object.keys(voices).reduce(function(acc, key) {
            if (voices[key].locale === locale) {
                acc[key] = {
                    name: voices[key].name,
                    icon: voices[key].gender
                };
            }
            return acc;
        }, {});
    },
  
    'onLoadUI': function (ctx, linearLayout) {
        initializeFileSystem();
        
        
        
            // ↓ 修正后：同时检测音效文件（yinxiao.json）和列表文件（liebiao.json）的初始化代码 ↓
        // 保留原函数名 initYinxiaoFile，处理 yinxiao.json（逻辑不变）
        function initYinxiaoFile() {
            const yinxiaoPath = "yinxiao.json";
            const emptyArray = "[]"; // 只声明1次，全函数复用（解决重复声明错误）
            try {
                var yinxiaoJson = ttsrv.readTxtFile(yinxiaoPath);
                if (!yinxiaoJson || yinxiaoJson.trim() === "") {
                    ttsrv.writeTxtFile(yinxiaoPath, emptyArray);
                    console.log("yinxiao.json 为空，已重置为空数组");
                    Toast.makeText(ctx, "音效配置文件为空，已初始化", Toast.LENGTH_SHORT).show();
                    return;
                }
                var yinxiaoList = JSON.parse(yinxiaoJson);
                if (!Array.isArray(yinxiaoList)) {
                    ttsrv.writeTxtFile(yinxiaoPath, emptyArray);
                    console.log("yinxiao.json 格式错，已重置为空数组");
                    Toast.makeText(ctx, "音效配置格式错误，已初始化", Toast.LENGTH_SHORT).show();
                } else {
                    console.log("yinxiao.json 正常，共" + yinxiaoList.length + "个音效");
                }
            } catch (e) {
                ttsrv.writeTxtFile(yinxiaoPath, emptyArray);
                console.error("yinxiao.json 不存在，已创建空文件：" + e.toString());
                Toast.makeText(ctx, "音效配置文件不存在，已初始化", Toast.LENGTH_SHORT).show();
            }
        }
  
        // 处理 liebiao.json 的函数：不存在/异常时初始化存入 ["默认"]
        function initLiebiaoFile() {
            const liebiaoPath = "liebiao.json";
            const defaultLiebiao = '["默认"]'; // 列表文件默认初始化内容，而非空数组
            try {
                var liebiaoJson = ttsrv.readTxtFile(liebiaoPath);
                if (!liebiaoJson || liebiaoJson.trim() === "") {
                    ttsrv.writeTxtFile(liebiaoPath, defaultLiebiao); // 写入默认内容
                    console.log("liebiao.json 为空，已初始化为 [\"默认\"]");
                    Toast.makeText(ctx, "列表配置文件为空，已初始化", Toast.LENGTH_SHORT).show();
                    return;
                }
                var liebiaoList = JSON.parse(liebiaoJson);
                if (!Array.isArray(liebiaoList)) {
                    ttsrv.writeTxtFile(liebiaoPath, defaultLiebiao); // 格式错误时写入默认内容
                    console.log("liebiao.json 格式错，已初始化为 [\"默认\"]");
                    Toast.makeText(ctx, "列表配置格式错误，已初始化", Toast.LENGTH_SHORT).show();
                } else {
                    console.log("liebiao.json 正常，共" + liebiaoList.length + "条列表数据");
                }
            } catch (e) {
                ttsrv.writeTxtFile(liebiaoPath, defaultLiebiao); // 文件不存在时写入默认内容
                console.error("liebiao.json 不存在，已创建文件并写入 [\"默认\"]：" + e.toString());
                Toast.makeText(ctx, "列表配置文件不存在，已初始化", Toast.LENGTH_SHORT).show();
            }
        }
  
        // 分别调用两个初始化函数
        initYinxiaoFile(); 
        initLiebiaoFile();
        
        // 修复：仅初始化 ttsrv.tts.data（不触碰框架的 ttsrv 本身！）
        // 框架已预定义 ttsrv，直接使用即可，禁止赋值 ttsrv = {}
        if (typeof ttsrv.tts === "undefined") ttsrv.tts = {};
        if (typeof ttsrv.tts.data !== "object") ttsrv.tts.data = {};
        
        // 读取自动备份状态（字符清洗+严谨判断）
        var autoBackupState = "0"; // 默认关闭
        try {
            // 处理原始值：转为字符串+去空格，避免异常格式
            var rawState = String(ttsrv.tts.data.autoBackupEnable || "").trim();
            console.log("自动备份原始状态：值='" + rawState + "', 类型=" + typeof rawState);
            
            // 仅当清洗后是"1"才视为开启
            autoBackupState = (rawState === "1") ? "1" : "0";
        } catch (e) {
            console.error("读取自动备份状态异常：" + e.toString());
            autoBackupState = "0";
        }
        
        // 状态为"1"时执行备份
        if (autoBackupState === "1") {
            backupInAddMode();
            console.log("自动备份（增加模式）已执行");
        } else {
            console.log("自动备份（增加模式）关闭，不执行");
        }
  
        
        // ↑ 修正结束 ↑
  
        
        
        
        
        var fayinrenList = [];
        try {
            var fayinrenJson = ttsrv.readTxtFile("fayinren.json");
            if (fayinrenJson) {
                fayinrenList = JSON.parse(fayinrenJson);
                 // ↓ 新增：fayinren列表执行正向映射（仅1行）
                for (var i = 0; i < fayinrenList.length; i++) {
                  fayinrenList[i] = replaceFayinrenName(fayinrenList[i]);
                }
        // ↑ 新增结束
                console.log("从fayinren.json读取到发音人列表: " + fayinrenList.length);
            } else {
                console.log("fayinren.json文件内容为空");
            }
        } catch (e) {
            console.error("读取fayinren.json失败: " + e.toString());
        }
        
        var keyEditor = JTextInput(ctx, "(输入密钥) 或 (网址@@模型名@@密钥)");
        keyEditor.setVisibility(View.VISIBLE);
        linearLayout.addView(keyEditor);
        ttsrv.setMargins(keyEditor, 0, 8, 0, 0);
        
        keyEditor.setOnTextChangedListener(function (text) {
            ttsrv.tts.data['key'] = text;
        });
  
  
        // --------------- 2. 新建按钮容器（横向排列两个按钮，不影响原布局结构）---------------
        var keyBtnLayout = new android.widget.LinearLayout(ctx);
        keyBtnLayout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        linearLayout.addView(keyBtnLayout);
        ttsrv.setMargins(keyBtnLayout, 0, 8, 0, 0);
  
        // --------------- 3. 「保存密钥」按钮（仅调用原保存逻辑，无任何新增修改）---------------
        var saveKeyButton = new android.widget.Button(ctx);
        saveKeyButton.setText("保存密钥");
        saveKeyButton.setBackgroundColor(android.graphics.Color.parseColor("#4CAF50")); // 原颜色保留
        saveKeyButton.setTextColor(android.graphics.Color.WHITE);
        // 原布局参数逻辑（权重1，与右侧按钮间距5dp）
        var saveKeyParams = new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        saveKeyParams.setMargins(0, 0, 5, 0);
        saveKeyButton.setLayoutParams(saveKeyParams);
        keyBtnLayout.addView(saveKeyButton);
  
        // 「保存密钥」点击事件：弹出输入框让用户输入密钥，再弹出命名框保存
        saveKeyButton.setOnClickListener(new android.view.View.OnClickListener({
            onClick: function(view) {
                // 弹出输入框让用户输入密钥
                var builder = new android.app.AlertDialog.Builder(ctx);
                builder.setTitle("输入密钥");
                
                var input = new android.widget.EditText(ctx);
                input.setHint("输入密钥 或 网址@@模型名@@密钥");
                builder.setView(input);
                
                builder.setPositiveButton("下一步", new android.content.DialogInterface.OnClickListener({
                    onClick: function(dialog, which) {
                        var dataKey = input.getText().toString().trim();
                        if (android.text.TextUtils.isEmpty(dataKey)) {
                            Toast.makeText(ctx, "密钥不能为空", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        
                        // 弹出命名输入框
                        showCustomNameInputDialogForNew(dataKey);
                        dialog.dismiss();
                    }
                }));
                builder.setNegativeButton("取消", function(dialog) {
                    dialog.cancel();
                });
                builder.create().show();
            }
        }));
  
        // --------------- 4. 「恢复密钥」按钮（仅调用原恢复逻辑，不校验输入框）---------------
        var restoreKeyButton = new android.widget.Button(ctx);
        restoreKeyButton.setText("恢复密钥");
        restoreKeyButton.setBackgroundColor(android.graphics.Color.parseColor("#FF9800")); // 新颜色区分，不影响逻辑
        restoreKeyButton.setTextColor(android.graphics.Color.WHITE);
        // 原布局参数逻辑（权重1，与左侧按钮间距5dp）
        var restoreKeyParams = new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        restoreKeyParams.setMargins(5, 0, 0, 0);
        restoreKeyButton.setLayoutParams(restoreKeyParams);
        keyBtnLayout.addView(restoreKeyButton);
  
        // 「恢复密钥」点击事件（完全复用原代码的“恢复分支”逻辑，仅移除输入框空值判断）
        restoreKeyButton.setOnClickListener(new android.view.View.OnClickListener({
            onClick: function(view) {
                // 原逻辑1：初始化密钥列表（调用原辅助函数，无任何修改）
                var keyMap = getKeyMapFromData();
                var customNameList = [];
                for (var prop in keyMap) {
                    if (keyMap.hasOwnProperty(prop)) customNameList.push(prop);
                }
  
                // 原逻辑2：无保存密钥提示（完全保留）
                if (customNameList.length === 0) {
                    Toast.makeText(ctx, "输入框为空，且无已保存密钥", Toast.LENGTH_SHORT).show();
                    return;
                }
  
                // 原逻辑3：调用原恢复弹窗（参数、流程完全不变）
                showKeyRestoreDialog(customNameList, keyMap);
            }
        }));
  
        // --------------- 5. 原密钥相关辅助函数（完全保留，不做任何修改）---------------
        // （以下函数无需改动，确保保存/恢复逻辑与原代码一致）
        // function getKeyMapFromData() { /* 原代码 */ }
        // function saveKeyMapToData(keyMap) { /* 原代码 */ }
        // function getNextKeyCode(keyMap) { /* 原代码 */ }
        // function saveKeyToLocal(key) { /* 原代码 */ }
        // function saveKeyWithMultipleMethods(key) { /* 原代码 */ }
        // function showKeySaveDialog(dataKey, customNameList, keyMap) { /* 原代码 */ }
        // function showKeyRestoreDialog(customNameList, keyMap) { /* 原代码 */ }
        // function showCustomNameInputDialog(defaultName, dataKey, isEdit, keyMap) { /* 原代码 */ }
        // function showDeleteKeyDialog(customNameList, keyMap) { /* 原代码 */ }
        // ============== 核心修复1：先定义所有音效相关辅助函数 ==============
        // 辅助函数1：保存音效（新增/修改通用）
        function saveSound(name, alias, soundUrl) {
            if (!soundUrl || soundUrl.trim() === "") {
                Toast.makeText(ctx, "网址不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            var url = soundUrl.trim();
            try {
                var yinxiaoJson = ttsrv.readTxtFile("yinxiao.json");
                var yinxiaoList = yinxiaoJson ? JSON.parse(yinxiaoJson) : [];
                if (!Array.isArray(yinxiaoList)) yinxiaoList = [];
  
                var isOverwrite = false;
                for (var i = 0; i < yinxiaoList.length; i++) {
                    if (yinxiaoList[i].name === name) {
                        yinxiaoList[i].alias = alias;
                        yinxiaoList[i].url = url;
                        isOverwrite = true;
                        break;
                    }
                }
                if (!isOverwrite) {
                    yinxiaoList.push({ "name": name, "alias": alias, "url": url });
                }
  
                ttsrv.writeTxtFile("yinxiao.json", JSON.stringify(yinxiaoList, null, 2));
                Toast.makeText(ctx, isOverwrite ? "音效已覆盖" : "音效已新增", Toast.LENGTH_SHORT).show();
            } catch (e) {
                console.error("保存音效失败: " + e.toString());
                Toast.makeText(ctx, "失败: " + e.toString(), Toast.LENGTH_SHORT).show();
            }
        }
  
        // 辅助函数2：带默认值的输入框（修改时回显用）
        function showInputDialog(title, callback, defaultValue = "") {
            var builder = new android.app.AlertDialog.Builder(ctx);
            builder.setTitle(title);
            var input = new android.widget.EditText(ctx);
            input.setText(defaultValue);
            builder.setView(input);
            builder.setPositiveButton("确定", function(dialog, which) {
                callback(input.getText().toString());
            });
            builder.setNegativeButton("取消", function(dialog) {
                dialog.cancel();
            });
            builder.show();
        }
  
        // 辅助函数3：多行输入框（备份恢复用）
        function showMultiLineInputDialog(title, callback) {
            try {
                var builder = new android.app.AlertDialog.Builder(ctx);
                builder.setTitle(title);
                
                var input = new android.widget.EditText(ctx);
                input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
                input.setSingleLine(false);
                input.setLines(8);
                input.setMaxLines(15);
                
                builder.setView(input);
                builder.setPositiveButton("确定", function(dialog, which) {
                    callback(input.getText().toString());
                });
                builder.setNegativeButton("取消", function(dialog) {
                    dialog.cancel();
                });
                builder.create().show();
            } catch (e) {
                console.error("显示多行输入框失败: " + e.toString());
                showInputDialog(title + " (单行模式)", callback);
            }
        }
  
        // 辅助函数4：音效备份到剪贴板
        function backupSoundToClipboard() {
            try {
                var soundData = ttsrv.readTxtFile("yinxiao.json");
                if (!soundData || soundData.trim() === "") {
                    Toast.makeText(ctx, "音效数据为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                var clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                var clip = android.content.ClipData.newPlainText("音效配置备份", soundData);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(ctx, "音效配置已复制到剪贴板", Toast.LENGTH_SHORT).show();
            } catch (e) {
                console.error("音效备份失败: " + e.toString());
                Toast.makeText(ctx, "备份失败: " + e.toString(), Toast.LENGTH_SHORT).show();
            }
        }
  
        // 辅助函数5：从文本恢复音效【修改2：改为添加模式，重名覆盖】
        function restoreSoundFromText() {
            showMultiLineInputDialog("请输入音效配置JSON", function(inputText) {
                if (!inputText || inputText.trim() === "") {
                    Toast.makeText(ctx, "输入内容不能为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                try {
                    // 1. 解析新输入的音效配置
                    var newSoundList = JSON.parse(inputText);
                    if (!Array.isArray(newSoundList)) {
                        Toast.makeText(ctx, "音效配置必须是数组格式", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    
                    // 2. 读取原有音效配置（不存在则为空数组）
                    var oldYinxiaoJson = ttsrv.readTxtFile("yinxiao.json");
                    var oldSoundList = oldYinxiaoJson ? JSON.parse(oldYinxiaoJson) : [];
                    if (!Array.isArray(oldSoundList)) oldSoundList = [];
                    
                    // 3. 合并逻辑：新音效添加，重名覆盖（按name判断）
                    newSoundList.forEach(newSound => {
                        if (!newSound.name || !newSound.url) return; // 跳过无效配置
                        const oldIndex = oldSoundList.findIndex(s => s.name === newSound.name);
                        if (oldIndex > -1) {
                            oldSoundList[oldIndex] = newSound; // 重名覆盖
                        } else {
                            oldSoundList.push(newSound); // 不重名添加
                        }
                    });
                    
                    // 4. 写入合并后的配置
                    ttsrv.writeTxtFile("yinxiao.json", JSON.stringify(oldSoundList, null, 2));
                    Toast.makeText(ctx, "音效配置恢复成功（新增" + (newSoundList.length - newSoundList.filter(n => oldSoundList.some(o => o.name === n.name)).length) + "个，覆盖" + newSoundList.filter(n => oldSoundList.some(o => o.name === n.name)).length + "个）", Toast.LENGTH_SHORT).show();
                } catch (e) {
                    console.error("解析JSON失败: " + e.toString());
                    Toast.makeText(ctx, "数据格式错误: " + e.toString(), Toast.LENGTH_SHORT).show();
                }
            });
        }
  
        // ============== 核心调整：音效按钮顺序（新增→备份恢复→删除→修改） ==============
        var soundBtnLayout = new android.widget.LinearLayout(ctx);
        soundBtnLayout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        linearLayout.addView(soundBtnLayout);
        ttsrv.setMargins(soundBtnLayout, 0, 8, 0, 0);
  
        // 1. 新增音效（位置1）
        var addSoundBtn = new android.widget.Button(ctx);
  
        // 2. 音效备份恢复（位置2）
        var soundBackupBtn = new android.widget.Button(ctx);
  
        // 3. 删除音效（位置3）
        var deleteSoundBtn = new android.widget.Button(ctx);
        // 4. 修改音效（位置4）
        var editSoundBtn = new android.widget.Button(ctx);
  
        // ============== 各按钮点击逻辑 ==============
        // 1. 新增音效点击逻辑【修改3：特殊符号（>、<、＞、＜）需输入别名】
        addSoundBtn.setOnClickListener(new android.view.View.OnClickListener({
            onClick: function(view) {
                // 提示文本增加特殊符号说明
                showInputDialog("音效名称(“@”  “>”  “＜”)\n#为正则去双引号", function(soundName) {
                    if (!soundName || soundName.trim() === "") {
                        Toast.makeText(ctx, "名称不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    var name = soundName.trim();
                    var alias = "";
                    // 特殊符号开头需输入别名
                    const specialPrefixes = ["@", "#", ">", "<", "＞", "＜"];
                    const isSpecial = specialPrefixes.some(prefix => name.startsWith(prefix));
                    
                    if (isSpecial) {
                        showInputDialog("请输入音效别名（用于显示）", function(inputAlias) {
                            alias = inputAlias ? inputAlias.trim() : "";
                            showInputDialog("请输入音效网址", function(soundUrl) {
                                saveSound(name, alias, soundUrl);
                            });
                        });
                    } else {
                        showInputDialog("请输入音效网址", function(soundUrl) {
                            saveSound(name, alias, soundUrl);
                        });
                    }
                });
            }
        }));
  
        // 2. 音效备份恢复点击逻辑（无修改）
        soundBackupBtn.setOnClickListener(new android.view.View.OnClickListener({
            onClick: function(view) {
                var builder = new android.app.AlertDialog.Builder(ctx);
                builder.setTitle("音效备份");
                var options = ["备份到剪贴板", "从文本恢复"];
                
                builder.setItems(options, function(dialog, which) {
                    switch (which) {
                        case 0:
                            backupSoundToClipboard();
                            break;
                        case 1:
                            restoreSoundFromText();
                            break;
                    }
                });
                builder.show();
            }
        }));
  
        // 3. 删除音效点击逻辑（无修改）
        deleteSoundBtn.setOnClickListener(new android.view.View.OnClickListener({
            onClick: function(view) {
                try {
                    var yinxiaoJson = ttsrv.readTxtFile("yinxiao.json");
                    if (!yinxiaoJson || yinxiaoJson.trim() === "") {
                        Toast.makeText(ctx, "音效列表为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    var yinxiaoList = JSON.parse(yinxiaoJson);
                    if (!Array.isArray(yinxiaoList) || yinxiaoList.length === 0) {
                        Toast.makeText(ctx, "音效列表为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
  
                    var displayNames = [];
                    var nameMap = {};
                    for (var i = 0; i < yinxiaoList.length; i++) {
                        var item = yinxiaoList[i];
                        var showName = item.name.startsWith("@") && item.alias ? item.alias : item.name;
                        displayNames.push(showName);
                        nameMap[showName] = item.name;
                    }
  
                    var checkedItems = new Array(displayNames.length).fill(false);
                    new android.app.AlertDialog.Builder(ctx)
                        .setTitle("选择要删除的音效（可多选）")
                        .setMultiChoiceItems(displayNames, checkedItems, function(dialog, which, isChecked) {
                            checkedItems[which] = isChecked;
                        })
                        .setPositiveButton("删除选中", function(dialog, which) {
                            var toDelete = [];
                            for (var i = 0; i < checkedItems.length; i++) {
                                if (checkedItems[i]) {
                                    toDelete.push(nameMap[displayNames[i]]);
                                }
                            }
                            if (toDelete.length === 0) {
                                Toast.makeText(ctx, "未选择音效", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            var newSoundList = yinxiaoList.filter(item => !toDelete.includes(item.name));
                            ttsrv.writeTxtFile("yinxiao.json", JSON.stringify(newSoundList, null, 2));
                            Toast.makeText(ctx, "已删除" + toDelete.length + "个音效", Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("取消", function(dialog) {
                            dialog.cancel();
                        })
                        .show();
                } catch (e) {
                    console.error("删除音效失败: " + e.toString());
                    Toast.makeText(ctx, "失败: " + e.toString(), Toast.LENGTH_SHORT).show();
                }
            }
        }));
  
        // 4. 修改音效点击逻辑【修改4：特殊符号（>、<、＞、＜）需维护别名】
        editSoundBtn.setOnClickListener(new android.view.View.OnClickListener({
            onClick: function(view) {
                try {
                    var yinxiaoJson = ttsrv.readTxtFile("yinxiao.json");
                    if (!yinxiaoJson || yinxiaoJson.trim() === "") {
                        Toast.makeText(ctx, "音效列表为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    var yinxiaoList = JSON.parse(yinxiaoJson);
                    if (!Array.isArray(yinxiaoList) || yinxiaoList.length === 0) {
                        Toast.makeText(ctx, "音效列表为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
  
                    var displayNames = [];
                    var soundMap = {};
                    for (var i = 0; i < yinxiaoList.length; i++) {
                        var item = yinxiaoList[i];
                        var showName = item.name.startsWith("@") && item.alias ? item.alias : item.name;
                        displayNames.push(showName);
                        soundMap[showName] = item;
                    }
  
                    new android.app.AlertDialog.Builder(ctx)
                        .setTitle("选择要修改的音效")
                        .setItems(displayNames, function(dialog, which) {
                            var selectedShowName = displayNames[which];
                            var targetSound = soundMap[selectedShowName];
                            if (!targetSound) return;
  
                            // 1. 修改名称
                            showInputDialog("修改音效名称", function(newName) {
                                if (!newName || newName.trim() === "") {
                                    Toast.makeText(ctx, "名称不能为空", Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                newName = newName.trim();
                                var newAlias = targetSound.alias || "";
  
                                // 2. 若为特殊符号开头，修改别名
                                var aliasCallback = function() {
                                    // 3. 修改网址
                                    showInputDialog("修改音效网址", function(newUrl) {
                                        if (!newUrl || newUrl.trim() === "") {
                                            Toast.makeText(ctx, "网址不能为空", Toast.LENGTH_SHORT).show();
                                            return;
                                        }
                                        newUrl = newUrl.trim();
  
                                        // 保存修改
                                        for (var i = 0; i < yinxiaoList.length; i++) {
                                            if (yinxiaoList[i].name === targetSound.name) {
                                                yinxiaoList[i].name = newName;
                                                yinxiaoList[i].alias = newAlias;
                                                yinxiaoList[i].url = newUrl;
                                                break;
                                            }
                                        }
                                        ttsrv.writeTxtFile("yinxiao.json", JSON.stringify(yinxiaoList, null, 2));
                                        Toast.makeText(ctx, "音效修改成功", Toast.LENGTH_SHORT).show();
                                    }, targetSound.url);
                                };
  
                                // 【修改点】判断特殊符号：@、>、<、＞、＜
                                const specialPrefixes = ["@", "#", ">", "<", "＞", "＜"];
                                if (specialPrefixes.some(prefix => newName.startsWith(prefix))) {
                                    showInputDialog("修改音效别名", function(inputAlias) {
                                        newAlias = inputAlias ? inputAlias.trim() : "";
                                        aliasCallback();
                                    }, targetSound.alias);
                                } else {
                                    aliasCallback();
                                }
                            }, targetSound.name);
                        })
                        .show();
                } catch (e) {
                    console.error("修改音效失败: " + e.toString());
                    Toast.makeText(ctx, "失败: " + e.toString(), Toast.LENGTH_SHORT).show();
                }
            }
        }));
  
  
  
  
  
  
  
  
  
  
        // ============== 1. 密钥输入框实时同步（核心：将输入框内容同步到data['key']，仅此一处从输入框获取） ==============
        keyEditor.setOnTextChangedListener(function (text) {
            ttsrv.tts.data['key'] = text; // 输入变化时，唯一一次从输入框取文本并同步到data
            console.log("输入框同步到data['key']：" + (text ? text : "空"));
        });
  
  
  
  // ====================== 3. 恢复模式弹窗（不变，正常工作） ======================
  // 替换原 showKeyRestoreDialog 函数（ES5语法，无语法错误）
        function showKeyRestoreDialog(customNameList, keyMap) {
            // 修复：用ES5 concat方法拼接数组（替代ES6扩展运算符）
            var dialogOptions = customNameList.concat(["查看当前密钥"]);
            
            var builder = new android.app.AlertDialog.Builder(ctx);
            builder.setTitle("选择密钥恢复到本地");
            builder.setItems(dialogOptions, new android.content.DialogInterface.OnClickListener({
                onClick: function(dialog, which) {
                    // 原有逻辑：点击已保存的密钥 → 恢复到本地
                    if (which < customNameList.length) {
                        var selectedName = customNameList[which];
                        var restoredKey = keyMap[selectedName] && keyMap[selectedName].value 
                            ? keyMap[selectedName].value.toString().trim() 
                            : "";
  
                        if (android.text.TextUtils.isEmpty(restoredKey)) {
                            Toast.makeText(ctx, "【" + selectedName + "】无有效密钥", Toast.LENGTH_SHORT).show();
                            dialog.dismiss();
                            return;
                        }
  
                        saveKeyToLocal(restoredKey);
                        Toast.makeText(ctx, "已恢复【" + selectedName + "】到本地txt", Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                    }
                    // 新增：点击"查看当前密钥" → 弹出新弹窗显示完整密钥+复制（全ES5语法）
                    else if (which === customNameList.length) {
                        dialog.dismiss(); // 关闭原弹窗
                        try {
                            // 读取miyue.txt完整密钥
                            var currentKey = ttsrv.readTxtFile("miyue.txt");
                            if (!currentKey || currentKey.trim() === "") {
                                Toast.makeText(ctx, "miyue.txt中无有效密钥", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            currentKey = currentKey.trim();
  
                            // 弹出新选择框：显示完整密钥，点击复制
                            var viewBuilder = new android.app.AlertDialog.Builder(ctx);
                            viewBuilder.setTitle("当前密钥（点击复制完整内容）");
                            var keyOptions = [currentKey]; // 完整显示，不截断
                            
                            viewBuilder.setItems(keyOptions, new android.content.DialogInterface.OnClickListener({
                                onClick: function(viewDialog, viewWhich) {
                                    // 复制到剪贴板（ES5兼容写法）
                                    var clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                                    var clip = android.content.ClipData.newPlainText("当前密钥", currentKey);
                                    clipboard.setPrimaryClip(clip);
                                    
                                    Toast.makeText(ctx, "密钥已复制到剪贴板", Toast.LENGTH_SHORT).show();
                                    viewDialog.dismiss();
                                }
                            }));
                            
                            viewBuilder.setNegativeButton("取消", new android.content.DialogInterface.OnClickListener({
                                onClick: function(viewDialog) {
                                    viewDialog.dismiss();
                                }
                            }));
                            viewBuilder.create().show();
  
                        } catch (e) {
                            console.error("查看当前密钥失败: " + e.toString());
                            Toast.makeText(ctx, "读取密钥失败: " + e.toString(), Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            }));
            
            // 原有：左下角"修改"按钮（完全不变，保持ES5语法）
            builder.setNeutralButton("修改", new android.content.DialogInterface.OnClickListener({
                onClick: function(dialog, which) {
                    dialog.dismiss();
                    // 弹出密钥选择框（复用现有列表）
                    var modifyBuilder = new android.app.AlertDialog.Builder(ctx);
                    modifyBuilder.setTitle("选择要修改的密钥");
                    modifyBuilder.setItems(customNameList, new android.content.DialogInterface.OnClickListener({
                        onClick: function(modifyDialog, modifyWhich) {
                            var selectedName = customNameList[modifyWhich];
                            var selectedItem = keyMap[selectedName];
                            var originalKey = selectedItem && selectedItem.value ? selectedItem.value.toString().trim() : "";
                            
                            if (android.text.TextUtils.isEmpty(originalKey)) {
                                Toast.makeText(ctx, "【" + selectedName + "】无有效密钥，无法修改", Toast.LENGTH_SHORT).show();
                                modifyDialog.dismiss();
                                return;
                            }
                            
                            // 第一步：弹出名称输入框（预填原名称）
                            showInputDialog("修改密钥名称", function(newName) {
                                newName = newName ? newName.trim() : "";
                                if (android.text.TextUtils.isEmpty(newName)) {
                                    Toast.makeText(ctx, "密钥名称不能为空", Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                
                                // 第二步：弹出密钥输入框（预填原密钥）
                                showInputDialog("修改密钥内容", function(newKey) {
                                    newKey = newKey ? newKey.trim() : "";
                                    if (android.text.TextUtils.isEmpty(newKey)) {
                                        Toast.makeText(ctx, "密钥内容不能为空", Toast.LENGTH_SHORT).show();
                                        return;
                                    }
                                    
                                    // 仅保存到data，不保存到本地txt
                                    delete keyMap[selectedName]; // 删除原条目
                                    keyMap[newName] = { keyCode: selectedItem.keyCode, value: newKey }; // 添加新条目
                                    saveKeyMapToData(keyMap); // 仅更新data
                                    
                                    Toast.makeText(ctx, "已修改密钥【" + newName + "】（仅保存到data）", Toast.LENGTH_SHORT).show();
                                }, originalKey); // 预填原密钥
                            }, selectedName); // 预填原名称
                            
                            modifyDialog.dismiss();
                        }
                    }));
                    modifyBuilder.setNegativeButton("取消", new android.content.DialogInterface.OnClickListener({
                        onClick: function(modifyDialog) {
                            modifyDialog.dismiss();
                        }
                    }));
                    modifyBuilder.create().show();
                }
            }));
            
            // 原有：取消按钮（完全不变）
            builder.setNegativeButton("取消", new android.content.DialogInterface.OnClickListener({
                onClick: function(dialog) {
                    dialog.cancel();
                }
            }));
            builder.create().show();
        }
  
  
  
  
        function showKeySaveDialog(dataKey, customNameList, keyMap) {
            var options = [];
            for (var i = 0; i < customNameList.length; i++) {
                options.push(customNameList[i]);
            }
            options.push("点击此处保存新密钥");
        
            var builder = new android.app.AlertDialog.Builder(ctx);
            builder.setTitle("保存密钥（来自data['key']）");
            builder.setItems(options, new android.content.DialogInterface.OnClickListener({
                onClick: function(dialog, which) {
                    var selectedOption = options[which];
                    if (android.text.TextUtils.isEmpty(selectedOption)) {
                        dialog.cancel();
                        return;
                    }
        
                    if (customNameList.indexOf(selectedOption) !== -1) {
                        showCustomNameInputDialog(selectedOption, dataKey, true, keyMap);
                    } else if (selectedOption === "点击此处保存新密钥") {
                        var newKeyCode = getNextKeyCode(keyMap);
                        showCustomNameInputDialog(newKeyCode, dataKey, false, keyMap);
                    }
                    dialog.dismiss();
                }
            }));
        
            // 原有代码完全保留：不修改任何现有按钮
            builder.setPositiveButton("删除", function(dialog) {
                dialog.dismiss();
                showDeleteKeyDialog(customNameList, keyMap);
            });
            builder.setNegativeButton("取消", function(dialog) {
                dialog.cancel();
            });
        
            // 仅新增：左下角「保存新密钥」按钮（NeutralButton位置，默认在取消按钮右侧、删除按钮左侧）
            builder.setNeutralButton("保存新密钥", function(dialog) {
                // 与「新的key」功能完全一致
                var newKeyCode = getNextKeyCode(keyMap);
                showCustomNameInputDialog(newKeyCode, dataKey, false, keyMap);
                dialog.dismiss();
            });
        
            builder.create().show();
        }
        
        
        // ====================== 5. 自定义名称输入框（用的是data传来的密钥，无输入框依赖） ======================
        function showCustomNameInputDialog(defaultName, dataKey, isEdit, keyMap) {
            var builder = new android.app.AlertDialog.Builder(ctx);
            builder.setTitle(isEdit ? "修改密钥名称" : "输入新密钥名称");
            
            var nameInput = new android.widget.EditText(ctx);
            nameInput.setText(defaultName);
            builder.setView(nameInput);
  
            builder.setPositiveButton("确定", new android.content.DialogInterface.OnClickListener({
                onClick: function(dialog) {
                    var customName = nameInput.getText() ? nameInput.getText().toString().trim() : "";
                    if (android.text.TextUtils.isEmpty(customName)) {
                        Toast.makeText(ctx, "密钥名称不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
  
                    // 保存的是来自data['key']的密钥
                    if (isEdit) {
                        var oldItem = keyMap[defaultName];
                        delete keyMap[defaultName];
                        keyMap[customName] = { keyCode: oldItem.keyCode, value: dataKey };
                    } else {
                        keyMap[customName] = { keyCode: defaultName, value: dataKey };
                    }
  
                    saveKeyMapToData(keyMap);
                    saveKeyToLocal(dataKey); // 本地保存也用data中的密钥
                    Toast.makeText(ctx, 
                        isEdit ? "【" + customName + "】已更新（密钥来自data）" : "【" + customName + "】已新增（密钥来自data）", 
                        Toast.LENGTH_SHORT
                    ).show();
                    dialog.dismiss();
                }
            }));
            builder.setNegativeButton("取消", function(dialog) {
                dialog.cancel();
            });
            builder.create().show();
        }
  
        // ====================== 5.1 新增：保存密钥时的命名弹窗（仅用于新密钥保存） ======================
        function showCustomNameInputDialogForNew(dataKey) {
            var keyMap = getKeyMapFromData();
            var newKeyCode = getNextKeyCode(keyMap);
            
            var builder = new android.app.AlertDialog.Builder(ctx);
            builder.setTitle("输入密钥名称");
            
            var nameInput = new android.widget.EditText(ctx);
            nameInput.setHint("输入密钥名称，如：我的密钥1");
            builder.setView(nameInput);
            
            builder.setPositiveButton("保存", new android.content.DialogInterface.OnClickListener({
                onClick: function(dialog) {
                    var customName = nameInput.getText().toString().trim();
                    if (android.text.TextUtils.isEmpty(customName)) {
                        Toast.makeText(ctx, "密钥名称不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    
                    // 保存密钥
                    keyMap[customName] = { keyCode: newKeyCode, value: dataKey };
                    saveKeyMapToData(keyMap);
                    saveKeyToLocal(dataKey);
                    Toast.makeText(ctx, "【" + customName + "】已保存", Toast.LENGTH_SHORT).show();
                    
                    // 更新输入框显示
                    ttsrv.tts.data['key'] = dataKey;
                    keyEditor.setText(dataKey);
                    
                    dialog.dismiss();
                }
            }));
            builder.setNegativeButton("取消", function(dialog) {
                dialog.cancel();
            });
            builder.create().show();
        }
  
        // ====================== 6. 删除弹窗（不变） ======================
        function showDeleteKeyDialog(customNameList, keyMap) {
            if (customNameList.length === 0) {
                Toast.makeText(ctx, "无已保存密钥可删除", Toast.LENGTH_SHORT).show();
                return;
            }
  
            var checkedItems = [];
            for (var i = 0; i < customNameList.length; i++) {
                checkedItems.push(false);
            }
  
            var builder = new android.app.AlertDialog.Builder(ctx);
            builder.setTitle("选择要删除的密钥项（可多选）");
            builder.setMultiChoiceItems(customNameList, checkedItems, function(dialog, which, isChecked) {
                checkedItems[which] = isChecked;
            });
            builder.setPositiveButton("确认删除", function(dialog) {
                var deletedCount = 0;
                for (var i = customNameList.length - 1; i >= 0; i--) {
                    if (checkedItems[i]) {
                        delete keyMap[customNameList[i]];
                        deletedCount++;
                    }
                }
                saveKeyMapToData(keyMap);
                Toast.makeText(ctx, "已删除" + deletedCount + "个密钥项", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            });
            builder.setNegativeButton("取消", function(dialog) {
                dialog.cancel();
            });
            builder.create().show();
        }
  
        // ====================== 7. 辅助函数（均不变，确保data操作正常） ======================
        function getKeyMapFromData() {
            if (!ttsrv) ttsrv = {};
            if (!ttsrv.tts) ttsrv.tts = {};
            if (typeof ttsrv.tts.data !== "object") ttsrv.tts.data = {};
            
            var keyListJson = ttsrv.tts.data.keyListJson || "{}";
            try {
                return JSON.parse(keyListJson);
            } catch (e) {
                console.error("解析密钥列表失败：" + e.toString());
                return {};
            }
        }
  
        function saveKeyMapToData(keyMap) {
            try {
                var keyListJson = JSON.stringify(keyMap);
                ttsrv.tts.data.keyListJson = keyListJson;
                console.log("Data已保存密钥列表（长度：" + keyListJson.length + "）");
            } catch (e) {
                console.error("序列化失败：" + e.toString());
                Toast.makeText(ctx, "Data存储失败", Toast.LENGTH_SHORT).show();
            }
        }
  
        function getNextKeyCode(keyMap) {
            var keyCodes = [];
            for (var name in keyMap) {
                if (keyMap[name].keyCode) keyCodes.push(keyMap[name].keyCode);
            }
            if (keyCodes.length === 0) return "key01";
            
            keyCodes.sort(function(a, b) {
                var numA = parseInt(a.replace("key", "")) || 0;
                var numB = parseInt(b.replace("key", "")) || 0;
                return numA - numB;
            });
  
            var lastNum = parseInt(keyCodes[keyCodes.length - 1].replace("key", "")) || 0;
            return "key" + (lastNum + 1 < 10 ? "0" + (lastNum + 1) : lastNum + 1);
        }
  
        function saveKeyToLocal(key) {
            try {
                ttsrv.writeTxtFile("miyue.txt", key);
                ttsrv.writeTxtFile("gengxin.txt", key);
                saveKeyWithMultipleMethods(key);
                console.log("本地txt已更新（密钥来自data，长度：" + key.length + "）");
            } catch (e) {
                console.error("本地保存失败：" + e.toString());
                Toast.makeText(ctx, "本地txt保存失败", Toast.LENGTH_SHORT).show();
            }
        }
  
        function saveKeyWithMultipleMethods(key) {
            try {
                ttsrv.writeTxtFile("miyue_backup.txt", key);
                console.log("密钥备份成功（来自data）");
            } catch (e1) {
                console.error("ttsrv备份失败：" + e1.toString());
                try {
                    if (typeof fs !== "undefined") fs.writeFile("miyue_backup.txt", key);
                } catch (e2) {
                    console.error("fs备份失败：" + e2.toString());
                }
            }
        }
  
  
  
  
  
  
  
        
        var divider = new android.view.View(ctx);
        divider.setBackgroundColor(android.graphics.Color.parseColor("#888888"));
        var layoutParams = new android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 2);
        layoutParams.setMargins(0, 20, 0, 20);
        divider.setLayoutParams(layoutParams);
        linearLayout.addView(divider);
        
        var displayMetrics = new android.util.DisplayMetrics();
        var windowManager = ctx.getSystemService(android.content.Context.WINDOW_SERVICE);
        windowManager.getDefaultDisplay().getMetrics(displayMetrics);
        var density = displayMetrics.density;
        
        var characterRecords = characterManager.characterRecords; // 使用实例的全局数据
        var characterFilePath = 'characterRecords.json';
        var backupFilePath = 'characterRecords_backup.json';
        
        try {
            console.log("尝试读取角色数据文件: " + characterFilePath);
            
            try {
                var data = ttsrv.readTxtFile(characterFilePath);
                console.log("使用ttsrv.readTxtFile读取文件成功");
                characterRecords = JSON.parse(data) || [];
  
  
  
          // ↓ 纯ES5写法：替换箭头函数为forEach普通函数
                characterRecords.forEach(function(char) {
                  char.voice = replaceFayinrenName(char.voice || "");
                });
        // ↑ 新增结束
  
  
  
  
  
            } catch (e) {
                console.log("读取角色数据失败: " + e.toString());
                
                if (!characterRecords || characterRecords.length === 0) {
                    console.log("使用默认角色数据");
                    characterRecords = [
                        {"name":"命無言","aliases":"命無言","gender":"男","age":"中年","voice":"默认发音人","usageCount":20},
                        {"name":"大帅哥","gender":"男","age":"中年","voice":"默认发音人","usageCount":20,"aliases":"大帅哥"},
                        {"name":"求三连","gender":"男","age":"青年","voice":"默认发音人","usageCount":18,"aliases":"求三连"}
                    ];
                }
            }
            
            console.log("成功解析角色数据，记录数: " + characterRecords.length);
            characterManager.characterRecords = characterRecords;
            
            var allNames = [];
            for (var i = 0; i < characterRecords.length; i++) {
                allNames.push(characterRecords[i].name);
            }
            console.log("所有角色名称: " + allNames.join(", "));
        } catch (e) {
            console.error("读取角色数据失败: " + e.toString());
        }
        
        function safeGetName(character) {
            if (character && character.name) {
                return character.name;
            }
            console.warn("角色名称缺失，使用默认值");
            return "未知角色";
        }
        
        
        function generateDisplayText(character) {
            if (!character) {
                console.warn("generateDisplayText: 角色对象为空");
                return "无效角色";
            }
            
            var name = safeGetName(character);
            var displayText = name;
            
            // 核心修改：主角皇冠放在主名字右边
            if (character.age === "主角") {
                displayText += "👑"; // 主名字后直接加皇冠
            }
            
            if (character.usageCount === 50) {
                displayText = "【" + displayText + "】";
            } else {
                displayText = displayText;
            }
            
            // 别名部分保持原有格式，不再包含皇冠
            if (character.aliases && character.aliases !== name) {
                displayText += " (" + character.aliases + ")"; // 仅显示别名括号
            }
            
            if (character.voice) {
                var voiceText = character.voice;
                if (character.usageCount === 100) {
                    voiceText = "【" + character.voice + "】";
                }
                displayText += "  [" + voiceText + "]";
                displayText = displayText.replace(/\[(【.+】)\]/g, "$1");
            }
            
            return displayText;
        }
  
        
        function initializeFileSystem() {
            // 书籍切换逻辑：检测到书名后，调用 useBook() 切换到当前书籍
            try {
                console.log("开始初始化文件系统");
                
                // 1. 读取 cunfang.txt 获取当前书籍名
                var cunfangBookName = "默认";
                try {
                    var cunfangContent = ttsrv.readTxtFile("cunfang.txt");
                    if (cunfangContent && cunfangContent.trim() !== "") {
                        cunfangBookName = cunfangContent.trim();
                    }
                } catch (e) {
                    // 文件不存在，默认使用"默认"
                }
                console.log("当前书籍: [" + cunfangBookName + "]");
                
                // 2. 直接调用 useBook() 切换到当前书籍（模拟点击切换书籍下拉框）
                useBook(cunfangBookName);
                
                console.log("文件系统初始化完成");
                
            } catch (e) {
                console.error("文件系统初始化失败: " + e.toString());
            }
        }
        
        function saveCharacterData() {
            try {
                var currentBookName = getCurrentBookName();
                
                backupOriginalData();
                
  
          // ↓ 纯ES5写法：替换箭头函数+对象解构（手动复制对象属性）
                var saveRecords = [];
                for (var i = 0; i < characterRecords.length; i++) {
                    var char = characterRecords[i];
              // 手动复制所有属性，修改voice字段（ES5无解构，避免影响原对象）
                    saveRecords.push({
                      name: char.name,
                      aliases: char.aliases,
                      voice: reverseReplaceFayinrenName(char.voice || ""),
                      gender: char.gender,
                      age: char.age,
                      usageCount: char.usageCount,
                      genderAgeHistory: char.genderAgeHistory // 保留可能存在的其他字段
                    });
                }
          // ↑ 新增结束
  
  
  
  
  
  // ↓ 修复：序列化 saveRecords 而不是 characterRecords
                var jsonData = JSON.stringify(saveRecords, null, 2);
  // ↑ 修复结束
  
                console.log("准备写入JSON数据，长度: " + jsonData.length);
                
                ttsrv.writeTxtFile("characterRecords.json", jsonData);
                console.log("角色记录已保存到characterRecords.json");
                
                var shumingFileName = "shuming." + currentBookName + ".json";
                ttsrv.writeTxtFile(shumingFileName, jsonData);
                console.log("角色记录已保存到" + shumingFileName);
                
                ttsrv.writeTxtFile("gengxin.json", jsonData);
                console.log("角色记录已保存到gengxin.json");
                
            } catch (e) {
                console.error("写入文件失败: " + e.toString());
                Toast.makeText(ctx, "保存失败: " + e.toString(), Toast.LENGTH_SHORT).show();
            }
        }
        
          function createGengxinFile() {
            try {
              // 与 saveCharacterData 保持一致：执行反向映射（显示名 → 存储名）
              var saveRecords = [];
              for (var i = 0; i < characterRecords.length; i++) {
                var char = characterRecords[i];
                // 手动复制属性 + 反向映射 voice 字段
                saveRecords.push({
                  name: char.name,
                  aliases: char.aliases,
                  voice: reverseReplaceFayinrenName(char.voice || ""), // 关键：添加反向映射
                  gender: char.gender,
                  age: char.age,
                  usageCount: char.usageCount,
                  genderAgeHistory: char.genderAgeHistory // 保留其他字段
                });
              }
              var jsonData = JSON.stringify(saveRecords, null, 2);
              ttsrv.writeTxtFile("gengxin.json", jsonData);
              console.log("已创建/更新gengxin.json文件（已执行反向映射）");
            } catch (e) {
              console.error("创建gengxin.json文件失败: " + e.toString());
            }
          }
          
        
        function backupOriginalData() {
            try {
                console.log("开始备份原始数据");
                
                var currentData = "";
                try {
                    currentData = ttsrv.readTxtFile("characterRecords.json");
                    console.log("成功读取当前文件内容");
                } catch (e) {
                    console.log("无法读取当前文件，使用内存数据备份: " + e.toString());
                    currentData = JSON.stringify(characterRecords, null, 2);
                }
                
                ttsrv.writeTxtFile("characterRecords_backup.json", currentData);
                console.log("原始数据备份完成");
                
            } catch (e) {
                console.error("备份原始数据失败: " + e.toString());
            }
        }
        
        function restoreBackup() {
            try {
                console.log("尝试恢复备份文件: " + backupFilePath);
                
                var data = ttsrv.readTxtFile(backupFilePath);
                characterRecords = JSON.parse(data) || [];
                console.log("从备份恢复数据成功");
                
                saveCharacterData();
                
                createGengxinFile();
                
                return true;
            } catch (e) {
                console.error("恢复备份失败: " + e.toString());
            }
            return false;
        }
        
        function cleanupGenderAgeTags(character) {
            if (!character) return;
            
            delete character.gender;
            delete character.age;
            
            if (character.genderAgeHistory && Array.isArray(character.genderAgeHistory)) {
                for (var i = 0; i < character.genderAgeHistory.length; i++) {
                    var historyItem = character.genderAgeHistory[i];
                    if (historyItem) {
                        delete historyItem.gender;
                        delete historyItem.age;
                    }
                }
                
                var hasValidData = false;
                for (var i = 0; i < character.genderAgeHistory.length; i++) {
                    var historyItem = character.genderAgeHistory[i];
                    if (historyItem && Object.keys(historyItem).length > 0) {
                        hasValidData = true;
                        break;
                    }
                }
                if (!hasValidData) {
                    delete character.genderAgeHistory;
                }
            }
        }
        
        var topButtonsLayout = new android.widget.LinearLayout(ctx);
        topButtonsLayout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        linearLayout.addView(topButtonsLayout);
        ttsrv.setMargins(topButtonsLayout, 0, 10, 0, 0);
  
  
  
        // ---------------------- 新增：在“管理书籍”按钮行下方添加 执行合并/固定发音人/释放角色/删除角色 按钮行 ----------------------
        var newFunctionButtonRow = new android.widget.LinearLayout(ctx);
        newFunctionButtonRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        linearLayout.addView(newFunctionButtonRow);
        ttsrv.setMargins(newFunctionButtonRow, 0, 10, 0, 0); // 与上方按钮行间距10dp
        
        // 1. 执行合并按钮
        var mergeBtnNew = new android.widget.Button(ctx);
        mergeBtnNew.setText("执行合并");
        mergeBtnNew.setBackgroundColor(android.graphics.Color.parseColor("#4CAF50"));
        mergeBtnNew.setTextColor(android.graphics.Color.WHITE);
        var mergeBtnNewParams = new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        mergeBtnNewParams.setMargins(0, 0, 5, 0);
        mergeBtnNew.setLayoutParams(mergeBtnNewParams);
        newFunctionButtonRow.addView(mergeBtnNew);
        mergeBtnNew.setOnClickListener(new android.view.View.OnClickListener({
            onClick: function(view) {
                doMergeOperation("follow_target");
            }
        }));
        
        // 2. 更换发音人按钮
        var fixVoiceBtnNew = new android.widget.Button(ctx);
        fixVoiceBtnNew.setText("更换发音人");
        fixVoiceBtnNew.setBackgroundColor(android.graphics.Color.parseColor("#9C27B0"));
        fixVoiceBtnNew.setTextColor(android.graphics.Color.WHITE);
        var fixVoiceBtnNewParams = new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        fixVoiceBtnNewParams.setMargins(5, 0, 5, 0);
        fixVoiceBtnNew.setLayoutParams(fixVoiceBtnNewParams);
        newFunctionButtonRow.addView(fixVoiceBtnNew);
        fixVoiceBtnNew.setOnClickListener(new android.view.View.OnClickListener({
            onClick: function(view) {
                if (selectedIndex >= 0 && markedIndices.indexOf(selectedIndex) !== -1) {
                    longPressedIndex = selectedIndex;
                    showVoiceSelectionDialogForFix();
                } else {
                    Toast.makeText(ctx, "请标记并选中一个角色", Toast.LENGTH_SHORT).show();
                }
            }
        }));
        
        // 3. 释放角色按钮
        var releaseBtnNew = new android.widget.Button(ctx);
        releaseBtnNew.setText("释放角色");
        releaseBtnNew.setBackgroundColor(android.graphics.Color.parseColor("#2196F3"));
        releaseBtnNew.setTextColor(android.graphics.Color.WHITE);
        var releaseBtnNewParams = new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        releaseBtnNewParams.setMargins(5, 0, 5, 0);
        releaseBtnNew.setLayoutParams(releaseBtnNewParams);
        newFunctionButtonRow.addView(releaseBtnNew);
        releaseBtnNew.setOnClickListener(new android.view.View.OnClickListener({
            onClick: function(view) {
                doReleaseOperation();
            }
        }));
        
        // 4. 删除角色按钮
        var deleteCharBtnNew = new android.widget.Button(ctx);
        deleteCharBtnNew.setText("删除角色");
        deleteCharBtnNew.setBackgroundColor(android.graphics.Color.parseColor("#F44336"));
        deleteCharBtnNew.setTextColor(android.graphics.Color.WHITE);
        var deleteCharBtnNewParams = new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        deleteCharBtnNewParams.setMargins(5, 0, 0, 0);
        deleteCharBtnNew.setLayoutParams(deleteCharBtnNewParams);
        newFunctionButtonRow.addView(deleteCharBtnNew);
        deleteCharBtnNew.setOnClickListener(new android.view.View.OnClickListener({
            onClick: function(view) {
                doDeleteCharacterOperation();
            }
        }));
        // ---------------------- 新增按钮行代码结束 ----------------------
        
  
        
        // 修改后“新增角色”按钮创建（替换原“快速备份”按钮）
        var addCharacterButton = new android.widget.Button(ctx);
        addCharacterButton.setText("新增角色"); // 按钮文本改为“新增角色”
        var addCharacterParams = new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        addCharacterParams.setMargins(0, 0, 5, 0);
        addCharacterButton.setLayoutParams(addCharacterParams);
        addCharacterButton.setBackgroundColor(android.graphics.Color.parseColor("#4CAF50")); // 保留原绿色，保持UI一致性
        addCharacterButton.setTextColor(android.graphics.Color.WHITE);
        topButtonsLayout.addView(addCharacterButton);
        
        var addEmptyBookButton = new android.widget.Button(ctx);
        addEmptyBookButton.setText("创建新书");
        var addEmptyBookParams = new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        addEmptyBookParams.setMargins(5, 0, 5, 0);
        addEmptyBookButton.setLayoutParams(addEmptyBookParams);
        addEmptyBookButton.setBackgroundColor(android.graphics.Color.parseColor("#2196F3"));
        addEmptyBookButton.setTextColor(android.graphics.Color.WHITE);
        topButtonsLayout.addView(addEmptyBookButton);
        
        var backupRestoreButton = new android.widget.Button(ctx);
        backupRestoreButton.setText("备份恢复");
        var backupRestoreParams = new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        backupRestoreParams.setMargins(5, 0, 5, 0);
        backupRestoreButton.setLayoutParams(backupRestoreParams);
        backupRestoreButton.setBackgroundColor(android.graphics.Color.parseColor("#9C27B0"));
        backupRestoreButton.setTextColor(android.graphics.Color.WHITE);
        topButtonsLayout.addView(backupRestoreButton);
        
        var manageBooksButton = new android.widget.Button(ctx);
        manageBooksButton.setText("管理书籍");
        var manageBooksParams = new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        manageBooksParams.setMargins(5, 0, 0, 0);
        manageBooksButton.setLayoutParams(manageBooksParams);
        manageBooksButton.setBackgroundColor(android.graphics.Color.parseColor("#FF9800"));
        manageBooksButton.setTextColor(android.graphics.Color.WHITE);
        topButtonsLayout.addView(manageBooksButton);
        
        var secondRowLayout = new android.widget.LinearLayout(ctx);
        secondRowLayout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        linearLayout.addView(secondRowLayout);
        ttsrv.setMargins(secondRowLayout, 0, 10, 0, 0);
        
        var bookSpinner = new android.widget.Spinner(ctx);
        
        var borderDrawable = new android.graphics.drawable.GradientDrawable();
        borderDrawable.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        borderDrawable.setStroke(3, android.graphics.Color.BLACK);
        borderDrawable.setColor(android.graphics.Color.TRANSPARENT);
        
        var arrowDrawable = ctx.getResources().getDrawable(android.R.drawable.arrow_down_float);
        arrowDrawable.setColorFilter(android.graphics.Color.BLACK, android.graphics.PorterDuff.Mode.SRC_IN);
        
        var layers = [borderDrawable, arrowDrawable];
        var layeredDrawable = new android.graphics.drawable.LayerDrawable(layers);
        
        layeredDrawable.setLayerGravity(1, android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL);
        layeredDrawable.setLayerInsetRight(1, dipToPx(10));
        
        bookSpinner.setBackground(layeredDrawable);
        
        var bookSpinnerParams = new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 2);
        bookSpinner.setLayoutParams(bookSpinnerParams);
        
        function dipToPx(dip) {
            return Math.round(dip * ctx.getResources().getDisplayMetrics().density);
        }
        
        bookSpinnerParams.setMargins(0, 0, 5, 0);
        bookSpinner.setLayoutParams(bookSpinnerParams);
        secondRowLayout.addView(bookSpinner);
        
        var refreshButton = new android.widget.Button(ctx);
        refreshButton.setText("刷新");
        var refreshButtonParams = new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        refreshButtonParams.setMargins(5, 0, 0, 0);
        refreshButton.setLayoutParams(refreshButtonParams);
        refreshButton.setBackgroundColor(android.graphics.Color.parseColor("#607D8B"));
        refreshButton.setTextColor(android.graphics.Color.WHITE);
        secondRowLayout.addView(refreshButton);
                // ---------------------- 先定义字符串标准化函数（供后续复用） ----------------------
        function normalizeString(str) {
            if (typeof str !== 'string') str = String(str);
            try {
                return str.replace(/[\u200B-\u200D\uFEFF]/g, '').trim().toLowerCase();
            } catch (e) {
                return str.trim().toLowerCase();
            }
        }
  
        // ---------------------- 搜索功能代码（提前到此处，确保布局初始化完成） ----------------------
        // 6. 创建搜索按钮（新增：在刷新按钮右侧）
        var searchButton = new android.widget.Button(ctx);
        searchButton.setText("搜索");
        var searchButtonParams = new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        searchButtonParams.setMargins(5, 0, 0, 0); // 与刷新按钮间距5dp
        searchButton.setLayoutParams(searchButtonParams);
        searchButton.setBackgroundColor(android.graphics.Color.parseColor("#2196F3")); // 蓝色系，与创建新书按钮配色统一
        searchButton.setTextColor(android.graphics.Color.WHITE);
        secondRowLayout.addView(searchButton);
  
        // 7. 搜索按钮点击事件（核心：跟踪选中顺序+去掉年龄显示）
        searchButton.setOnClickListener(new android.view.View.OnClickListener({
            onClick: function(view) {
                showInputDialog("请输入搜索关键词（多关键词用@分隔）", function(inputText) {
                    var rawKeywords = inputText ? inputText.trim() : "";
                    if (rawKeywords === "") {
                        Toast.makeText(ctx, "搜索关键词不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
  
                    var keywords = rawKeywords.split("@")
                        .map(k => k.trim())
                        .filter(k => k !== "");
                    var characterData = "";
                    var targetCharacters = [];
                    try {
                        characterData = ttsrv.readTxtFile("characterRecords.json");
                        var allCharacters = JSON.parse(characterData);
                        if (!Array.isArray(allCharacters)) {
                            Toast.makeText(ctx, "角色数据格式错误", Toast.LENGTH_SHORT).show();
                            return;
                        }
  
                        // 包含匹配逻辑（不变）
                        targetCharacters = allCharacters.filter(char => {
                            var charName = (char.name || "").toLowerCase();
                            var charAlias = (char.aliases || "").toLowerCase();
                            return keywords.some(keyword => {
                                var lowerKey = keyword.toLowerCase();
                                return charName.includes(lowerKey) || charAlias.includes(lowerKey);
                            });
                        });
  
                        if (targetCharacters.length === 0) {
                            Toast.makeText(ctx, "未找到匹配角色", Toast.LENGTH_SHORT).show();
                            return;
                        }
  
                        // ---------------------- 核心修改：去掉年龄阶段，仅保留原有列表格式 ----------------------
                        var displayTexts = [];
                        var charMap = {};
                        for (var i = 0; i < targetCharacters.length; i++) {
                            var char = targetCharacters[i];
  
                            char.voice = replaceFayinrenName(char.voice || "");
      // ↑ 新增结束
                            // 仅用generateDisplayText生成显示文本（与原有角色列表完全一致，无年龄）
                            var fullText = generateDisplayText(char);
                            displayTexts.push(fullText);
                            charMap[fullText] = char;
                        }
  
                        // 跟踪选中顺序（不变）
                        var selectedOrder = [];
                        new android.app.AlertDialog.Builder(ctx)
                            .setTitle("选择操作角色（共" + targetCharacters.length + "个匹配项）")
                            .setMultiChoiceItems(displayTexts, new Array(displayTexts.length).fill(false), function(dialog, which, isChecked) {
                                if (isChecked) {
                                    if (selectedOrder.indexOf(which) === -1) {
                                        selectedOrder.push(which);
                                    }
                                } else {
                                    selectedOrder = selectedOrder.filter(idx => idx !== which);
                                }
                            })
                            .setPositiveButton("合并角色（置顶）", function(dialog) {
                                handleMergeAndTop(selectedOrder, displayTexts, charMap);
                                dialog.dismiss();
                            })
                            .setNegativeButton("批量置顶", function(dialog) {
                                handleBatchTop(selectedOrder, displayTexts, charMap);
                                dialog.dismiss();
                            })
                            .show();
  
                    } catch (e) {
                        console.error("搜索角色失败: " + e.toString());
                        Toast.makeText(ctx, "搜索失败: " + e.toString(), Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }));
        // 修复后：通过“标准化角色名”匹配，确保待合并角色被删除
        function handleMergeAndTop(selectedOrder, displayTexts, charMap) {
            // 1. 根据选中顺序，获取所有选中角色（顺序与选择顺序一致）
            var selectedChars = selectedOrder.map(idx => charMap[displayTexts[idx]]).filter(char => char);
            if (selectedChars.length < 2) {
                Toast.makeText(ctx, "合并需选择至少2个角色", Toast.LENGTH_SHORT).show();
                return;
            }
  
            // 2. 取最后一个选中的角色作为目标角色
            var targetChar = selectedChars[selectedChars.length - 1];
            // 待合并角色：除目标外的其他选中角色（关键：提取它们的“标准化名称”用于匹配）
            var mergeCharNames = selectedChars.slice(0, selectedChars.length - 1)
                .map(char => normalizeString(char.name)) // 存储待合并角色的标准化名称
                .filter(name => name !== ""); // 过滤空名称
  
            backupOriginalData();
            try {
                // 3. 合并别名（不变）
                var allAliases = {};
                // 收集目标角色别名
                if (targetChar.aliases) {
                    targetChar.aliases.split('|').forEach(alias => {
                        allAliases[normalizeString(alias)] = true;
                    });
                } else {
                    allAliases[normalizeString(targetChar.name)] = true;
                }
                // 收集待合并角色别名
                selectedChars.slice(0, selectedChars.length - 1).forEach(char => {
                    if (char.aliases) {
                        char.aliases.split('|').forEach(alias => {
                            allAliases[normalizeString(alias)] = true;
                        });
                    } else {
                        allAliases[normalizeString(char.name)] = true;
                    }
                });
  
                // 4. 合并后角色信息（不变）
                targetChar.name = targetChar.name;
                targetChar.aliases = Object.keys(allAliases).join('|');
                targetChar.usageCount = 100;
  
                // ---------------------- 核心修复：通过“标准化名称”过滤待合并角色 ----------------------
                // 原问题：用对象引用匹配（搜索结果对象≠原始列表对象），导致无法删除
                // 修复：用角色名标准化后的值匹配，确保能找到待合并角色
                var remainingChars = characterRecords.filter(rawChar => {
                    var rawCharName = normalizeString(rawChar.name);
                    // 保留：原始角色的标准化名称不在待合并列表中，且不是目标角色
                    return !mergeCharNames.includes(rawCharName) 
                        && normalizeString(rawChar.name) !== normalizeString(targetChar.name);
                });
  
                // 5. 目标角色置顶（不变）
                characterRecords = [targetChar].concat(remainingChars);
  
                // 6. 保存刷新（不变）
                saveCharacterData();
                createGengxinFile();
                refreshCharacterList();
                Toast.makeText(ctx, "合并成功！合并后角色名为：" + targetChar.name + "（已置顶，删除" + mergeCharNames.length + "个角色）", Toast.LENGTH_SHORT).show();
  
            } catch (e) {
                console.error("合并角色失败: " + e.toString());
                Toast.makeText(ctx, "合并失败: " + e.toString(), Toast.LENGTH_SHORT).show();
            }
        }
  
        // 修复后：批量置顶无重复（通过名称匹配删除原列表中的选中角色）
        function handleBatchTop(selectedOrder, displayTexts, charMap) {
            // 1. 根据选中顺序获取选中角色，并提取它们的“标准化名称”（用于后续匹配删除）
            var selectedChars = selectedOrder.map(idx => charMap[displayTexts[idx]]).filter(char => char);
            if (selectedChars.length === 0) {
                Toast.makeText(ctx, "请选择至少1个角色", Toast.LENGTH_SHORT).show();
                return;
            }
            // 关键：提取选中角色的标准化名称（避免对象引用问题）
            var selectedCharNames = selectedChars.map(char => normalizeString(char.name)).filter(name => name !== "");
  
            backupOriginalData();
            try {
                // ---------------------- 核心修复：通过名称匹配过滤原列表中的选中角色 ----------------------
                // 原问题：用对象引用过滤（selectedChars是新对象，和原始列表对象不同），导致原角色没被删除
                // 修复：用标准化名称过滤，确保原列表中的选中角色被移除，避免重复
                var remainingChars = characterRecords.filter(rawChar => {
                    var rawCharName = normalizeString(rawChar.name);
                    // 保留：原始角色的名称不在选中列表中
                    return !selectedCharNames.includes(rawCharName);
                });
  
                // 2. 拼接“选中角色（置顶）+ 剩余角色”（无重复）
                characterRecords = selectedChars.concat(remainingChars);
  
                // 3. 保存刷新
                saveCharacterData();
                createGengxinFile();
                refreshCharacterList();
                Toast.makeText(ctx, "批量置顶成功！共置顶" + selectedChars.length + "个角色（无重复）", Toast.LENGTH_SHORT).show();
  
            } catch (e) {
                console.error("批量置顶失败: " + e.toString());
                Toast.makeText(ctx, "置顶失败: " + e.toString(), Toast.LENGTH_SHORT).show();
            }
        }
  
        // ---------------------- 原有updateListViewAppearance函数（现在能正常获取系统颜色） ----------------------
        function updateListViewAppearance() {
            // -------------- 第一步：获取书籍选择框（Spinner）的系统颜色（用于标题、未标记未选中角色） --------------
            var systemTextColor = android.graphics.Color.parseColor("#333333"); // 降级默认色
            try {
                // 遍历父布局找“书籍选择框+刷新按钮”的横向布局
                var secondRowLayout = null;
                for (var layoutIdx = 0; layoutIdx < linearLayout.getChildCount(); layoutIdx++) {
                    var childLayout = linearLayout.getChildAt(layoutIdx);
                    if (childLayout instanceof android.widget.LinearLayout 
                        && childLayout.getOrientation() === android.widget.LinearLayout.HORIZONTAL 
                        && childLayout.getChildCount() === 2) {
                        var firstChild = childLayout.getChildAt(0);
                        var secondChild = childLayout.getChildAt(1);
                        if (firstChild instanceof android.widget.Spinner 
                            && secondChild instanceof android.widget.Button 
                            && secondChild.getText().toString().includes("刷新")) {
                            secondRowLayout = childLayout;
                            break;
                        }
                    }
                }
  
                // 从Spinner获取系统颜色（用于标题、未标记未选中角色）
                if (secondRowLayout) {
                    var bookSpinner = secondRowLayout.getChildAt(0);
                    if (bookSpinner instanceof android.widget.Spinner) {
                        var spinnerAdapter = bookSpinner.getAdapter();
                        if (spinnerAdapter) {
                            var spinnerItemView = spinnerAdapter.getView(0, null, bookSpinner);
                            if (spinnerItemView instanceof android.widget.TextView) {
                                systemTextColor = spinnerItemView.getCurrentTextColor();
                            }
                        }
                    }
                }
            } catch (e) {
                console.error("获取系统颜色失败：" + e.toString());
            }
  
            // -------------- 第二步：标题“角色列表 （已标记X):”跟随系统颜色（不变） --------------
            if (mergeLabel && mergeLabel instanceof android.widget.TextView) {
                mergeLabel.setTextColor(systemTextColor);
            }
  
            // -------------- 第三步：角色列表项颜色逻辑（核心修改：已标记未选中→固定深灰色） --------------
            for (var i = 0; i < mergeListView.getChildCount(); i++) {
                var child = mergeListView.getChildAt(i);
                if (child instanceof android.widget.CheckedTextView) {
                    // 重置默认样式（未标记未选中：背景透明+跟随系统颜色）
                    child.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                    child.setTextColor(systemTextColor);
  
                    // 1. 已标记 + 未选中：背景浅黄色，文字固定深灰色（#333333）——核心修改
                    if (markedIndices.indexOf(i) !== -1 && i !== selectedIndex) {
                        child.setBackgroundColor(android.graphics.Color.parseColor("#FFF9C4"));
                        child.setTextColor(android.graphics.Color.parseColor("#333333")); // 固定深灰色
                    }
                    // 2. 已标记 + 已选中：背景浅黄色，文字橙色（保留高亮，不变）
                    else if (markedIndices.indexOf(i) !== -1 && i === selectedIndex) {
                        child.setBackgroundColor(android.graphics.Color.parseColor("#FFF9C4"));
                        child.setTextColor(android.graphics.Color.parseColor("#FF5722"));
                    }
                    // 3. 未标记 + 已选中：背景浅蓝色，文字深蓝色（保留高亮，不变）
                    else if (i === selectedIndex) {
                        child.setBackgroundColor(android.graphics.Color.parseColor("#E3F2FD"));
                        child.setTextColor(android.graphics.Color.parseColor("#1976D2"));
                    }
                }
            }
        }
  
        function refreshCharacterData() {
            try {
                console.log("开始刷新角色列表数据");
                
                var characterData = ttsrv.readTxtFile("characterRecords.json");
                if (characterData && characterData.trim() !== "") {
                    var parsedData = JSON.parse(characterData);
                    characterRecords = parsedData || [];
  
  
  
                                // ↓ 纯ES5写法：替换箭头函数为forEach普通函数
                    characterRecords.forEach(function(char) {
                        char.voice = replaceFayinrenName(char.voice || "");
                    });
            // ↑ 新增结束
  
  
  
  
  
  
                    console.log("重新读取角色数据成功，记录数: " + characterRecords.length);
                    
                    refreshCharacterList();
                    
                    Toast.makeText(ctx, "角色列表已刷新", Toast.LENGTH_SHORT).show();
                } else {
                    console.log("characterRecords.json文件为空");
                    characterRecords = [];
                    refreshCharacterList();
                    Toast.makeText(ctx, "角色数据为空", Toast.LENGTH_SHORT).show();
                }
            } catch (e) {
                console.error("刷新角色数据失败: " + e.toString());
                Toast.makeText(ctx, "刷新失败: " + e.toString(), Toast.LENGTH_SHORT).show();
            }
        }
        
        refreshButton.setOnClickListener(new android.view.View.OnClickListener({
            onClick: function(view) {
                console.log("刷新按钮被点击，开始执行完整刷新流程");
                
                refreshCharacterData();
                
                try {
                    var currentBookName = getCurrentBookName();
                    console.log("从cunfang.txt读取的当前书籍: [" + currentBookName + "]");
                    
                    initBookSpinner();
                    
                    Toast.makeText(ctx, "刷新完成：角色列表和书籍选择框已更新", Toast.LENGTH_SHORT).show();
                    
                } catch (e) {
                    console.error("刷新书籍选择框失败: " + e.toString());
                    Toast.makeText(ctx, "刷新角色列表成功，但更新书籍选择框失败", Toast.LENGTH_SHORT).show();
                }
            }
        }));
        
        function initBookSpinner() {
            try {
                var bookList = getBookList();
                var currentBook = getCurrentBookName();
                
                console.log("初始化书籍下拉框:");
                console.log("当前书名: [" + currentBook + "]");
                console.log("书籍列表: " + JSON.stringify(bookList));
                
                var adapter = new android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_item, bookList);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                bookSpinner.setAdapter(adapter);
                
                var heightDp = 50;
                var heightPx = Math.round(heightDp * ctx.getResources().getDisplayMetrics().density);
                bookSpinner.getLayoutParams().height = heightPx;
                
                var selectedIndex = 0;
                
                for (var i = 0; i < bookList.length; i++) {
                    var bookName = String(bookList[i]).trim();
                    var currentBookTrimmed = String(currentBook).trim();
                    console.log("匹配比较: [" + bookName + "] vs [" + currentBookTrimmed + "]");
                    if (bookName === currentBookTrimmed) {
                        selectedIndex = i;
                        console.log("精确匹配成功，位置: " + i);
                        break;
                    }
                }
                
                bookSpinner.setSelection(selectedIndex);
                console.log("最终选择的书籍索引: " + selectedIndex + ", 书名: " + bookList[selectedIndex]);
  
                // ============== 新增：书籍选择监听器（正确切换核心） ==============
                bookSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener({
                    onItemSelected: function(parent, view, position, id) {
                        var selectedBook = bookList[position];
                        var currentBookName = getCurrentBookName();
                        if (selectedBook !== currentBookName) {
                            console.log("切换书籍：从[" + currentBookName + "]到[" + selectedBook + "]");
                            saveCurrentBookBeforeSwitch(selectedBook);
                        } else {
                            console.log("选中当前书籍，无需切换");
                        }
                    },
                    onNothingSelected: function(parent) {
                        console.log("未选择任何书籍");
                    }
                }));
                // ============== 新增结束 ==============
            } catch (e) {
                console.error("初始化书籍下拉框失败: " + e.toString());
            }
        }
        // ============== 书籍切换逻辑结束 ==============
        
        addCharacterButton.setOnClickListener(new android.view.View.OnClickListener({
            onClick: function(view) {
                // 第一步：弹窗输入角色名（非空校验，避免无效角色）
                showInputDialog("请输入角色名", function(characterName) {
                    var name = characterName ? characterName.trim() : "";
                    if (name === "") {
                        Toast.makeText(ctx, "角色名不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
  
                    // 第二步：弹窗输入别名（为空时自动用角色名填充）
                    showInputDialog("请输入角色别名（为空则使用角色名）", function(aliasText) {
                        var alias = aliasText ? aliasText.trim() : "";
                        var finalAlias = alias || name; // 别名空值处理：用角色名替代
  
                        // 【新增步骤：第三步 选择年龄性别】
                        var genderAgeOptions = Object.keys(GENSHIN_CHARACTERS);
                        new android.app.AlertDialog.Builder(ctx)
                            .setTitle("选择角色年龄性别")
                            .setItems(genderAgeOptions, function(dialog, which) {
                                var selectedGenderAge = genderAgeOptions[which];
                                var genderAgeData = GENSHIN_CHARACTERS[selectedGenderAge];
                                var selectedGender = genderAgeData.gender;
                                var selectedAge = genderAgeData.age;
  
                                // 第四步：选择发音人（原第三步，逻辑不变）
                                var voiceOptions = fayinrenList.length > 0 ? fayinrenList : ["默认发音人"];
                                new android.app.AlertDialog.Builder(ctx)
                                    .setTitle("选择角色固定发音人")
                                    .setItems(voiceOptions, function(dialog2, which2) {
                                        var selectedVoice = voiceOptions[which2];
  
                                        // 组装新增角色数据（添加 gender 和 age 字段）
                                        var newCharacter = {
                                            "name": name,                  // 角色名（用户输入，非空）
                                            "aliases": finalAlias,          // 别名（空则用角色名）
                                            "voice": selectedVoice,         // 选择的固定发音人
                                            "usageCount": 100,              // 关键：100 = 发音人固定状态
                                            "gender": selectedGender,       // 新增：选择的性别
                                            "age": selectedAge              // 新增：选择的年龄
                                        };
  
                                        // 添加到内存角色列表（置顶）
                                        characterRecords.unshift(newCharacter);
  
                                        // 同步更新JSON文件
                                        saveCharacterData();
                                        createGengxinFile();
                                        refreshCharacterList();
  
                                        // 成功提示（包含年龄性别信息）
                                        Toast.makeText(ctx, 
                                            "新增角色成功：" + name + "（" + selectedGender + " " + selectedAge + "，发音人：" + selectedVoice + "）", 
                                            Toast.LENGTH_SHORT
                                        ).show();
                                    })
                                    .setNegativeButton("取消", function(dialog2) {
                                        dialog2.cancel();
                                    })
                                    .show();
                            })
                            .setNegativeButton("取消", function(dialog) {
                                dialog.cancel();
                            })
                            .show();
                    });
                });
            }
        }));
  
        // 注：以下依赖函数无需修改（原代码已存在，仅需确保调用正常）
        // 1. saveCharacterData()：原角色数据保存函数，负责写入 characterRecords.json 和书籍文件
        // 2. createGengxinFile()：原函数，负责更新 gengxin.json
        // 3. refreshCharacterList()：原函数，负责刷新角色列表UI
        // 4. showInputDialog()：原函数，负责显示单行输入弹窗
        
        
        
                
        addEmptyBookButton.setOnClickListener(new android.view.View.OnClickListener({
            onClick: function(view) {
                showInputDialog("请输入新书名", function(inputText) {
                    if (!inputText || inputText.trim() === "") {
                        Toast.makeText(ctx, "书名不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    
                    var newBookName = inputText.trim();
                    
                    try {
                        ttsrv.writeTxtFile("cunfang.txt", newBookName);
                        
                        var emptyData = "[]";
                        ttsrv.writeTxtFile("characterRecords.json", emptyData);
                        
                        var shumingFileName = "shuming." + newBookName + ".json";
                        ttsrv.writeTxtFile(shumingFileName, emptyData);
                        
                        updateBookList(newBookName);
                        
                        characterRecords = [];
                        
                        createGengxinFile();
                        
                        initBookSpinner();
                        
                        refreshCharacterList();
                        
                        Toast.makeText(ctx, "创建新书成功: " + newBookName, Toast.LENGTH_SHORT).show();
                        
                    } catch (e) {
                        console.error("创建新书失败: " + e.toString());
                        Toast.makeText(ctx, "操作失败: " + e.toString(), Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }));
        
        backupRestoreButton.setOnClickListener(new android.view.View.OnClickListener({
            onClick: function(view) {
                showBackupRestoreDialog();
            }
        }));
        
        manageBooksButton.setOnClickListener(new android.view.View.OnClickListener({
            onClick: function(view) {
                showMultiSelectBookDialog();
            }
        }));
        
        function showInputDialog(title, callback) {
            var builder = new android.app.AlertDialog.Builder(ctx);
            builder.setTitle(title);
            
            var input = new android.widget.EditText(ctx);
            builder.setView(input);
            
            builder.setPositiveButton("确定", new android.content.DialogInterface.OnClickListener({
                onClick: function(dialog, which) {
                    var inputText = input.getText().toString();
                    if (callback) {
                        callback(inputText);
                    }
                }
            }));
            
            builder.setNegativeButton("取消", new android.content.DialogInterface.OnClickListener({
                onClick: function(dialog, which) {
                    dialog.cancel();
                }
            }));
            
            builder.show();
        }
        
        function showMultiLineInputDialog(title, callback) {
            try {
                var builder = new android.app.AlertDialog.Builder(ctx);
                builder.setTitle(title);
                
                var input = new android.widget.EditText(ctx);
                input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
                input.setSingleLine(false);
                input.setLines(8);
                input.setMaxLines(15);
                
                builder.setView(input);
                
                builder.setPositiveButton("确定", new android.content.DialogInterface.OnClickListener({
                    onClick: function(dialog, which) {
                        var inputText = input.getText().toString();
                        if (callback) {
                            callback(inputText);
                        }
                    }
                }));
                
                builder.setNegativeButton("取消", new android.content.DialogInterface.OnClickListener({
                    onClick: function(dialog, which) {
                        dialog.cancel();
                    }
                }));
                
                var dialog = builder.create();
                dialog.show();
                
            } catch (e) {
                console.error("显示多行输入对话框失败: " + e.toString());
                showInputDialog(title + " (使用单行输入)", callback);
            }
        }
        
        function deleteMultipleBooks(booksToDelete) {
            try {
                var bookList = getBookList();
                var newBookList = [];
                
                for (var i = 0; i < bookList.length; i++) {
                    if (booksToDelete.indexOf(bookList[i]) === -1) {
                        newBookList.push(bookList[i]);
                    }
                }
                
                newBookList = removeDuplicateBooks(newBookList);
                
                var currentBook = getCurrentBookName();
                console.log("当前书籍: [" + currentBook + "]");
                console.log("要删除的书籍: " + JSON.stringify(booksToDelete));
                
                var isCurrentBookDeleted = booksToDelete.indexOf(currentBook) !== -1;
                
                console.log("当前书籍是否被删除: " + isCurrentBookDeleted);
                
                if (isCurrentBookDeleted) {
                    console.log("当前书籍被删除，开始切换到默认书籍");
                    
                    ttsrv.writeTxtFile("cunfang.txt", "默认");
                    console.log("已立即更新cunfang.txt为默认");
                    
                    currentBook = getCurrentBookName();
                    console.log("更新后当前书籍: [" + currentBook + "]");
                    
                    try {
                        var defaultData = ttsrv.readTxtFile("shuming.默认.json");
                        if (defaultData && defaultData.trim() !== "") {
                            ttsrv.writeTxtFile("characterRecords.json", defaultData);
                            console.log("已更新characterRecords.json为默认数据");
                            
                            ttsrv.writeTxtFile("gengxin.json", defaultData);
                            console.log("已更新gengxin.json为默认数据");
                            
                            characterRecords = JSON.parse(defaultData) || [];
                            console.log("内存数据已更新为默认数据，角色数量: " + characterRecords.length);
                        } else {
                            var emptyData = "[]";
                            ttsrv.writeTxtFile("characterRecords.json", emptyData);
                            ttsrv.writeTxtFile("gengxin.json", emptyData);
                            characterRecords = [];
                            console.log("默认文件为空，已清空数据");
                        }
                    } catch (e) {
                        console.log("读取默认书籍失败，创建空数据: " + e.toString());
                        var emptyData = "[]";
                        ttsrv.writeTxtFile("characterRecords.json", emptyData);
                        ttsrv.writeTxtFile("gengxin.json", emptyData);
                        characterRecords = [];
                    }
                }
                
                ttsrv.writeTxtFile("liebiao.json", JSON.stringify(newBookList, null, 2));
                console.log("已更新liebiao.json，新列表: " + JSON.stringify(newBookList));
                
                for (var i = 0; i < booksToDelete.length; i++) {
                    var bookName = booksToDelete[i];
                    var shumingFileName = "shuming." + bookName + ".json";
                    try {
                        var deleteResult = ttsrv.deleteFile(shumingFileName);
                        if (deleteResult) {
                            console.log("已删除文件: " + shumingFileName);
                        } else {
                            console.log("文件不存在或删除失败: " + shumingFileName);
                        }
                    } catch (e) {
                        console.log("删除文件失败: " + e.toString());
                    }
                }
                
                if (isCurrentBookDeleted) {
                    refreshCharacterList();
                    
                    Toast.makeText(ctx, "已删除" + booksToDelete.length + "个书籍，已切换到默认书籍", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(ctx, "已删除 " + booksToDelete.length + " 个书籍", Toast.LENGTH_SHORT).show();
                }
                
                initBookSpinner();
                
            } catch (e) {
                console.error("删除多个书籍失败: " + e.toString());
                Toast.makeText(ctx, "删除失败: " + e.toString(), Toast.LENGTH_SHORT).show();
            }
        }
        
        function showMultiSelectBookDialog() {
            try {
                var bookList = getBookList();
                if (bookList.length === 0) {
                    Toast.makeText(ctx, "书籍列表为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                var bookArray = bookList;
                var checkedItems = new Array(bookArray.length);
                for (var i = 0; i < checkedItems.length; i++) {
                    checkedItems[i] = false;
                }
                
                var builder = new android.app.AlertDialog.Builder(ctx);
                builder.setTitle("选择要删除的书籍（可多选）");
                
                builder.setMultiChoiceItems(bookArray, checkedItems, new android.content.DialogInterface.OnMultiChoiceClickListener({
                    onClick: function(dialog, which, isChecked) {
                        checkedItems[which] = isChecked;
                    }
                }));
                
                builder.setPositiveButton("删除选中", new android.content.DialogInterface.OnClickListener({
                    onClick: function(dialog, which) {
                        var booksToDelete = [];
                        for (var i = 0; i < checkedItems.length; i++) {
                            if (checkedItems[i]) {
                                booksToDelete.push(bookArray[i]);
                            }
                        }
                        
                        if (booksToDelete.length > 0) {
                            deleteMultipleBooks(booksToDelete);
                        } else {
                            Toast.makeText(ctx, "未选择任何书籍", Toast.LENGTH_SHORT).show();
                        }
                    }
                }));
                
                builder.setNegativeButton("取消", new android.content.DialogInterface.OnClickListener({
                    onClick: function(dialog, which) {
                        dialog.cancel();
                    }
                }));
                
                builder.show();
                
            } catch (e) {
                console.error("显示多选书籍对话框失败: " + e.toString());
                Toast.makeText(ctx, "显示书籍列表失败: " + e.toString(), Toast.LENGTH_SHORT).show();
            }
        }
        
        
        
        
        
        
                // 修改后：含“设置自动备份”选项的备份恢复对话框函数
        function showBackupRestoreDialog() {
            var builder = new android.app.AlertDialog.Builder(ctx);
            builder.setTitle("备份恢复操作");
            // 原有选项不变，末尾新增“设置自动备份”
            var options = [
                "备份到剪贴板", 
                "从文本恢复", 
                "备份到data（存入角色）", 
                "从data恢复（完整还原）",
                "备份数据(增加模式)",
                "设置自动备份" // 新增：自动备份开关入口
            ];
            builder.setItems(options, function(dialog, which) {
                switch (which) {
                    case 0: backupToClipboard(); break;
                    case 1: restoreFromText(); break;
                    case 2: backupAllFilesToData(); break;
                    case 3: restoreAllFilesFromData(); break;
                    case 4: backupInAddMode(); break;
                    case 5: showAutoBackupSettingDialog(); // 触发自动备份设置弹窗
                }
            });
            builder.show();
        }
        // 修复：确保状态读取准确（处理异常格式+安全初始化层级），解决显示未开启问题
        function showAutoBackupSettingDialog() {
            // 1. 安全初始化层级（避免ttsrv.tts.data未定义导致读取失败）
            if (typeof ttsrv.tts === "undefined") ttsrv.tts = {};
            if (typeof ttsrv.tts.data !== "object") ttsrv.tts.data = {};
            
            // 2. 读取并清洗状态（处理空格、null、undefined等异常，确保"1"能被正确识别）
            var rawState = ttsrv.tts.data.autoBackupEnable;
            var cleanedState = String(rawState || "").trim(); // 强制转字符串+去空格
            var currentState = cleanedState === "1" ? "1" : "0"; // 仅基于清洗后的值判断
            
            var dialogTitle = currentState === "1" 
                ? "当前自动备份：开启（选择操作）" 
                : "当前自动备份：关闭（选择操作）";
            
            var builder = new android.app.AlertDialog.Builder(ctx);
            builder.setTitle(dialogTitle);
            var settingOptions = ["开启自动备份", "关闭自动备份"];
            
            builder.setItems(settingOptions, function(dialog, which) {
                // 明确存储为纯净String类型（无空格，避免后续读取异常）
                var newState = which === 0 ? "1" : "0";
                ttsrv.tts.data.autoBackupEnable = newState;
                
                // 提示结果
                Toast.makeText(ctx, 
                    newState === "1" 
                        ? "自动备份已开启（下次启动初始化时自动执行）" 
                        : "自动备份已关闭（下次启动初始化时不执行）", 
                    Toast.LENGTH_SHORT
                ).show();
                console.log("自动备份状态更新：" + (newState === "1" ? "开启" : "关闭") + "（存储类型：String，值：" + newState + "）");
                dialog.dismiss();
            });
            builder.show();
        }
  
  
        function backupInAddMode() {
            try {
                if (typeof ttsrv.tts.data !== "object") ttsrv.tts.data = {};
                
                // 【1. 读取data中原有备份】
                var dataFiles = {};
                if (ttsrv.tts.data.backupTest) {
                    try {
                        dataFiles = JSON.parse(ttsrv.tts.data.backupTest);
                    } catch (e) {
                        dataFiles = {};
                    }
                }
  
                // 【2. 读取本地数据（先获取cunfang.txt的当前书名，用于后续特殊处理）】
                var localFileInfo = {
                    "characterRecords.json": { content: "", isReadSuccess: false, isContentValid: false },
                    "liebiao.json": { content: "", isReadSuccess: false, isContentValid: false },
                    "yinxiao.json": { content: "", isReadSuccess: false, isContentValid: false },
                    "miyue.txt": { content: "", isReadSuccess: false, isContentValid: false },
                    "gengxin.json": { content: "", isReadSuccess: false, isContentValid: false },
                    "cunfang.txt": { content: "", isReadSuccess: false, isContentValid: false } // 先读cunfang.txt
                };
  
                // 2.1 读取核心文件（先处理cunfang.txt和characterRecords.json，为书籍读取做准备）
                for (var fileName in localFileInfo) {
                    var info = localFileInfo[fileName];
                    try {
                        var fileContent = ttsrv.readTxtFile(fileName);
                        info.isReadSuccess = true;
                        info.content = fileContent || "";
                        var trimmedContent = info.content.trim();
  
                        // 有效性判断（沿用之前规则）
                        if (fileName === "miyue.txt") {
                            info.isContentValid = (trimmedContent !== "") && 
                                                (trimmedContent.toLowerCase() !== "0b") && 
                                                (trimmedContent.length >= 10);
                        } else if (fileName.endsWith(".txt")) {
                            info.isContentValid = (trimmedContent !== "") && (trimmedContent.toLowerCase() !== "0b");
                        } else if (fileName.endsWith(".json")) {
                            if (trimmedContent !== "") {
                                JSON.parse(trimmedContent);
                                info.isContentValid = true;
                            }
                        }
  
                        // characterRecords.json 过滤genderAgeHistory（提前处理，供特殊书籍使用）
                        if (fileName === "characterRecords.json" && info.isContentValid) {
                            var characterList = JSON.parse(info.content);
                            var filteredList = [];
                            for (var k = 0; k < characterList.length; k++) {
                                var character = characterList[k];
                                if (character && character.hasOwnProperty("genderAgeHistory")) {
                                    delete character.genderAgeHistory;
                                }
                                filteredList.push(character);
                            }
                            info.content = JSON.stringify(filteredList);
                            console.log("characterRecords.json过滤完成，角色数：" + filteredList.length);
                        }
                    } catch (e) {
                        info.isReadSuccess = false;
                        info.isContentValid = false;
                        info.content = fileName.endsWith(".json") ? "[]" : "";
                        console.error("读取" + fileName + "失败：" + e);
                    }
                }
  
                // 【2.2 读取书籍文件（核心调整：cunfang.txt对应书籍从characterRecords.json获取）】
                try {
                    // 步骤1：获取cunfang.txt中的当前书名（目标书籍）
                    var cunfangInfo = localFileInfo["cunfang.txt"];
                    var targetBookName = "";
                    if (cunfangInfo.isContentValid) {
                        targetBookName = cunfangInfo.content.trim(); // 目标书籍名（如“三国演义”）
                        console.log("cunfang.txt当前书名：" + targetBookName);
                    } else {
                        console.log("cunfang.txt无效，所有书籍按原方式读取");
                    }
  
                    // 步骤2：遍历liebiao.json中的书籍列表
                    var liebiaoInfo = localFileInfo["liebiao.json"];
                    var liebiaoContent = liebiaoInfo.isContentValid ? liebiaoInfo.content : "[]";
                    var bookList = JSON.parse(liebiaoContent);
                    if (Object.prototype.toString.call(bookList) === "[object Array]") {
                        for (var i = 0; i < bookList.length; i++) {
                            var bookName = (bookList[i] || "").trim();
                            if (bookName === "") continue;
                            var bookFileName = "shuming." + bookName + ".json"; // 书籍对应的键名（不变）
  
                            // 步骤3：判断是否为cunfang.txt对应的目标书籍
                            if (bookName === targetBookName && targetBookName !== "") {
                                // 特殊处理：从characterRecords.json获取数据
                                var charRecordsInfo = localFileInfo["characterRecords.json"];
                                if (charRecordsInfo.isContentValid) {
                                    // 直接复用characterRecords.json的内容（已过滤genderAgeHistory）
                                    localFileInfo[bookFileName] = {
                                        content: charRecordsInfo.content,
                                        isReadSuccess: true,
                                        isContentValid: true
                                    };
                                    console.log("目标书籍" + bookName + "：从characterRecords.json获取数据");
                                } else {
                                    // characterRecords.json无效时，设为空数组
                                    localFileInfo[bookFileName] = {
                                        content: "[]",
                                        isReadSuccess: false,
                                        isContentValid: false
                                    };
                                    console.log("目标书籍" + bookName + "：characterRecords.json无效，设为空数组");
                                }
                            } else {
                                // 其他书籍：按原方式读取shuming.xxx.json
                                localFileInfo[bookFileName] = { content: "[]", isReadSuccess: false, isContentValid: false };
                                var bookInfo = localFileInfo[bookFileName];
                                try {
                                    var bookContent = ttsrv.readTxtFile(bookFileName);
                                    bookInfo.isReadSuccess = true;
                                    bookInfo.content = bookContent || "[]";
                                    var trimmedBookContent = bookInfo.content.trim();
                                    
                                    // 原有效性判断+过滤逻辑
                                    if (trimmedBookContent !== "") {
                                        JSON.parse(trimmedBookContent);
                                        bookInfo.isContentValid = true;
                                        
                                        var bookCharacters = JSON.parse(bookInfo.content);
                                        var filteredBookChars = [];
                                        for (var m = 0; m < bookCharacters.length; m++) {
                                            var char = bookCharacters[m];
                                            if (char && char.hasOwnProperty("genderAgeHistory")) {
                                                delete char.genderAgeHistory;
                                            }
                                            filteredBookChars.push(char);
                                        }
                                        bookInfo.content = JSON.stringify(filteredBookChars);
                                    }
                                    console.log("普通书籍" + bookName + "：从" + bookFileName + "获取数据");
                                } catch (e) {
                                    bookInfo.isReadSuccess = false;
                                    bookInfo.isContentValid = false;
                                    bookInfo.content = "[]";
                                    console.error("读取普通书籍" + bookName + "失败：" + e);
                                }
                            }
                        }
                    }
                } catch (e) {
                    console.error("读取书籍列表失败：" + e);
                }
  
                // 【3. 合并逻辑（无修改，沿用之前的有效性判断）】
                var mergedFiles = {};
                function mergeJsonArray(dataContent, localContent, uniqueKey) {
                    try {
                        var dataArr = JSON.parse(dataContent || "[]");
                        var localArr = JSON.parse(localContent || "[]");
                        if (!Array.isArray(dataArr)) dataArr = [];
                        if (!Array.isArray(localArr)) localArr = [];
  
                        var map = {};
                        for (var p = 0; p < dataArr.length; p++) {
                            var item = dataArr[p];
                            var key = (uniqueKey === "self") ? (typeof item === "string" ? item : "") : (item[uniqueKey] || "");
                            if (key) map[key] = item;
                        }
                        for (var q = 0; q < localArr.length; q++) {
                            var item = localArr[q];
                            var key = (uniqueKey === "self") ? (typeof item === "string" ? item : "") : (item[uniqueKey] || "");
                            if (key) map[key] = item;
                        }
  
                        var resultArr = [];
                        for (var key in map) {
                            if (map.hasOwnProperty(key)) {
                                resultArr.push(map[key]);
                            }
                        }
                        return JSON.stringify(resultArr);
                    } catch (e) {
                        return localContent || "[]";
                    }
                }
  
                // 收集去重文件名
                var allFileNames = [];
                for (var dFile in dataFiles) {
                    if (dataFiles.hasOwnProperty(dFile)) {
                        allFileNames.push(dFile);
                    }
                }
                for (var lFile in localFileInfo) {
                    if (localFileInfo.hasOwnProperty(lFile)) {
                        allFileNames.push(lFile);
                    }
                }
                var uniqueFileNames = [];
                var nameMap = {};
                for (var r = 0; r < allFileNames.length; r++) {
                    var fname = allFileNames[r];
                    if (!nameMap[fname]) {
                        nameMap[fname] = true;
                        uniqueFileNames.push(fname);
                    }
                }
  
                // 合并处理
                for (var s = 0; s < uniqueFileNames.length; s++) {
                    var fileName = uniqueFileNames[s];
                    var dataContent = dataFiles[fileName] || "";
                    var fileInfo = localFileInfo[fileName] || { content: "", isReadSuccess: false, isContentValid: false };
                    var localContent = fileInfo.content;
                    var isLocalValid = fileInfo.isContentValid;
  
                    if (fileName === "characterRecords.json") {
                        mergedFiles[fileName] = isLocalValid ? mergeJsonArray(dataContent, localContent, "name") : dataContent;
                    } else if (fileName === "liebiao.json") {
                        mergedFiles[fileName] = isLocalValid ? mergeJsonArray(dataContent, localContent, "self") : dataContent;
                    } else if (fileName === "yinxiao.json") {
                        mergedFiles[fileName] = isLocalValid ? mergeJsonArray(dataContent, localContent, "name") : dataContent;
                    } else if (fileName.indexOf("shuming.") === 0) {
                        mergedFiles[fileName] = isLocalValid ? mergeJsonArray(dataContent, localContent, "name") : dataContent;
                    } else if (fileName === "miyue.txt") {
                        mergedFiles[fileName] = isLocalValid ? localContent : dataContent;
                    } else if (fileName.endsWith(".txt") || fileName === "gengxin.json") {
                        mergedFiles[fileName] = isLocalValid ? localContent : dataContent;
                    } else {
                        mergedFiles[fileName] = isLocalValid ? localContent : dataContent;
                    }
                }
  
                // 【4. 存入data】
                ttsrv.tts.data.backupTest = JSON.stringify(mergedFiles);
                Toast.makeText(ctx, "增加模式备份完成（合并后共" + Object.keys(mergedFiles).length + "个文件）", Toast.LENGTH_SHORT).show();
            } catch (e) {
                Toast.makeText(ctx, "增加模式备份失败：" + e, Toast.LENGTH_SHORT).show();
                console.error("增加模式备份异常：" + e);
            }
        }
  
  
  
  
  
        
        // 【第五步：辅助函数mergeJsonByAddMode（全ES5）】
        function mergeJsonByAddMode(fileName, existingJson, localJson) {
            // 验证数组类型（ES5方式）
            var isExistingArray = Object.prototype.toString.call(existingJson) === "[object Array]";
            var isLocalArray = Object.prototype.toString.call(localJson) === "[object Array]";
            if (!isExistingArray || !isLocalArray) {
                return localJson;
            }
        
            var uniqueKey = "name";
            if (fileName === "liebiao.json") {
                uniqueKey = "self";
            }
        
            // 构建原有数据映射表
            var existingMap = {};
            for (var i = 0; i < existingJson.length; i++) {
                var item = existingJson[i];
                var key = "";
                if (uniqueKey === "self") {
                    key = item;
                } else {
                    key = item[uniqueKey] || "";
                }
                if (key) {
                    existingMap[key] = item;
                }
            }
        
            // 融合本地数据
            for (var j = 0; j < localJson.length; j++) {
                var item = localJson[j];
                var key = "";
                if (uniqueKey === "self") {
                    key = item;
                } else {
                    key = item[uniqueKey] || "";
                }
                if (key) {
                    existingMap[key] = item;
                }
            }
        
            // 转换为数组返回（ES5方式）
            var result = [];
            for (var key in existingMap) {
                if (existingMap.hasOwnProperty(key)) {
                    result.push(existingMap[key]);
                }
            }
            return result;
        }
        
        // 【第六步：辅助函数mergeBookCharacters（全ES5，处理别名覆盖）】
        function mergeBookCharacters(existingCharacters, localCharacters) {
            // 1. 构建「主name+别名」映射表
            var allCharMap = {};
            // 处理原有角色（for循环替代forEach）
            var isExistingArr = Object.prototype.toString.call(existingCharacters) === "[object Array]";
            if (isExistingArr) {
                for (var i = 0; i < existingCharacters.length; i++) {
                    var charItem = existingCharacters[i];
                    // 映射主name
                    if (charItem.name) {
                        var mainName = charItem.name.trim();
                        allCharMap[mainName] = charItem;
                    }
                    // 映射别名（拆分|）
                    if (charItem.aliases) {
                        var aliasesArr = charItem.aliases.split('|');
                        for (var k = 0; k < aliasesArr.length; k++) {
                            var alias = aliasesArr[k].trim();
                            if (alias) {
                                allCharMap[alias] = charItem;
                            }
                        }
                    }
                }
            }
        
            // 2. 处理本地合并角色
            var isLocalArr = Object.prototype.toString.call(localCharacters) === "[object Array]";
            if (isLocalArr) {
                for (var j = 0; j < localCharacters.length; j++) {
                    var localChar = localCharacters[j];
                    var localName = localChar.name ? localChar.name.trim() : "";
                    if (!localName) {
                        continue;
                    }
        
                    // 收集需删除的名称（主name+别名）
                    var namesToRemove = [localName];
                    if (localChar.aliases) {
                        var localAliases = localChar.aliases.split('|');
                        for (var m = 0; m < localAliases.length; m++) {
                            var aliasTrim = localAliases[m].trim();
                            if (aliasTrim) {
                                namesToRemove.push(aliasTrim);
                            }
                        }
                    }
        
                    // 删除原有映射中的未合并角色
                    for (var n = 0; n < namesToRemove.length; n++) {
                        delete allCharMap[namesToRemove[n]];
                    }
        
                    // 添加本地合并角色
                    allCharMap[localName] = localChar;
                    if (localChar.aliases) {
                        var newAliases = localChar.aliases.split('|');
                        for (var p = 0; p < newAliases.length; p++) {
                            var newAliasTrim = newAliases[p].trim();
                            if (newAliasTrim) {
                                allCharMap[newAliasTrim] = localChar;
                            }
                        }
                    }
                }
            }
        
            // 3. 去重（保留主name唯一）
            var uniqueCharMap = {};
            for (var key in allCharMap) {
                if (allCharMap.hasOwnProperty(key)) {
                    var char = allCharMap[key];
                    if (char.name) {
                        var charMainName = char.name.trim();
                        uniqueCharMap[charMainName] = char;
                    }
                }
            }
        
            // 转换为数组返回
            var result = [];
            for (var charKey in uniqueCharMap) {
                if (uniqueCharMap.hasOwnProperty(charKey)) {
                    result.push(uniqueCharMap[charKey]);
                }
            }
            return result;
        }
        
        
        
        
        
        
        
        
        function backupAllFilesToData() {
                try {
                        if (typeof ttsrv.tts.data !== "object") ttsrv.tts.data = {};
                        // 1. 初始化核心文件集合
                        var allFilesData = {
                                "characterRecords.json": "", "liebiao.json": "", "yinxiao.json": "",
                                "miyue.txt": "", "gengxin.json": "", "cunfang.txt": ""
                        };
  
                        // 2. 处理核心文件（含 characterRecords.json 过滤）
                        for (var fileName in allFilesData) {
                                try {
                                        var fileContent = ttsrv.readTxtFile(fileName) || "";
                                        // 角色数据文件：过滤 genderAgeHistory + 紧凑JSON
                                        if (fileName === "characterRecords.json") {
                                                if (fileContent.trim() !== "") {
                                                        var characterList = JSON.parse(fileContent);
                                                        var filteredList = characterList.map(character => {
                                                                if (character && character.hasOwnProperty("genderAgeHistory")) {
                                                                        delete character.genderAgeHistory;
                                                                }
                                                                return character;
                                                        });
                                                        allFilesData[fileName] = JSON.stringify(filteredList);
                                                        continue;
                                                }
                                        }
                                        // 非角色核心文件直接保留原内容
                                        allFilesData[fileName] = fileContent;
                                } catch (e) {
                                        allFilesData[fileName] = "";
                                        console.error("处理核心文件" + fileName + "失败：" + e);
                                }
                        }
  
                        // 3. 关键修复：读取书籍列表，添加书籍文件到备份集合
                        try {
                                // 先读取 liebiao.json 获取所有书籍名
                                var liebiaoContent = allFilesData["liebiao.json"] || ttsrv.readTxtFile("liebiao.json") || "[]";
                                var bookList = JSON.parse(liebiaoContent);
                                // 仅当书籍列表是数组时才遍历
                                if (Object.prototype.toString.call(bookList) === "[object Array]") {
                                        for (var i = 0; i < bookList.length; i++) {
                                                var bookName = bookList[i] || "";
                                                if (bookName.trim() === "") continue; // 跳过空书名
                                                var bookFileName = "shuming." + bookName.trim() + ".json";
                                                // 读取书籍文件内容
                                                try {
                                                        var bookContent = ttsrv.readTxtFile(bookFileName) || "[]";
                                                        // 过滤书籍文件中的 genderAgeHistory + 紧凑JSON
                                                        if (bookContent.trim() !== "") {
                                                                var bookCharacters = JSON.parse(bookContent);
                                                                var filteredBookChars = bookCharacters.map(char => {
                                                                        if (char && char.hasOwnProperty("genderAgeHistory")) {
                                                                                delete char.genderAgeHistory;
                                                                        }
                                                                        return char;
                                                                });
                                                                bookContent = JSON.stringify(filteredBookChars);
                                                        }
                                                        // 【核心】将书籍文件加入备份集合
                                                        allFilesData[bookFileName] = bookContent;
                                                        console.log("已添加书籍文件到备份：" + bookFileName);
                                                } catch (e) {
                                                        allFilesData[bookFileName] = "[]"; // 空文件用空数组占位
                                                        console.error("处理书籍" + bookFileName + "失败：" + e);
                                                }
                                        }
                                }
                        } catch (e) {
                                console.error("读取书籍列表失败：" + e);
                        }
  
                        // 4. 紧凑格式保存所有备份数据
                        ttsrv.tts.data.backupTest = JSON.stringify(allFilesData);
                        Toast.makeText(ctx, "覆盖模式备份完成（共" + Object.keys(allFilesData).length + "个文件）", Toast.LENGTH_SHORT).show();
                } catch (e) {
                        Toast.makeText(ctx, "覆盖模式备份失败：" + e, Toast.LENGTH_SHORT).show();
                }
        }
  
        
        function restoreAllFilesFromData() {
                try {
                        if (!ttsrv.tts.data || !ttsrv.tts.data.backupTest) throw "无备份数据";
                        var allFilesData = JSON.parse(ttsrv.tts.data.backupTest);
                        var restoredCount = 0;
                        for (var fileName in allFilesData) {
                                try {
                                        // 正常恢复当前文件
                                        ttsrv.writeTxtFile(fileName, allFilesData[fileName]);
                                        restoredCount++;
  
                                        // 核心修改：恢复characterRecords.json时，同步保存到gengxin.json
                                        if (fileName === "characterRecords.json") {
                                                ttsrv.writeTxtFile("gengxin.json", allFilesData[fileName]);
                                                // ↓ 修复：恢复后执行正向映射（替换原错误代码）
                                                characterRecords = JSON.parse(allFilesData[fileName]) || [];
                                                characterRecords.forEach(function(char) {
                                                    char.voice = replaceFayinrenName(char.voice || "");
                                                });
                      // ↑ 修复结束
                      
                  // ↓ 纯ES5写法：替换箭头函数为forEach普通函数
                                                if (fileName === "characterRecords.json") {
                                                  characterRecords.forEach(function(char) {
                                                    char.voice = replaceFayinrenName(char.voice || "");
                                                    });
                                                }
                // ↑ 新增结束
  
  
                                                console.log("已同步保存characterRecords.json内容到gengxin.json");
                                                restoredCount++; // 计数+1（同步文件也算恢复成功1个）
                                        }
                                } catch (e) {
                                        // 单独捕获同步gengxin.json的错误，不影响主文件恢复
                                        if (fileName === "characterRecords.json") {
                                                console.error("恢复characterRecords.json成功，但同步gengxin.json失败：" + e.toString());
                                        } else {
                                                console.error("恢复" + fileName + "失败：" + e.toString());
                                        }
                                }
                        }
                        refreshCharacterData();
                        initBookSpinner(); 
                        Toast.makeText(ctx, "恢复成功" + restoredCount + "个文件（含同步的gengxin.json）", Toast.LENGTH_SHORT).show();
                } catch (e) {
                        console.error("恢复失败：" + (e.message || e.toString()));
                        Toast.makeText(ctx, "恢复失败：" + (e.message || e.toString()), Toast.LENGTH_SHORT).show();
                }
        }
  
        // 【第四步：修复刷新函数（仅刷新角色，不碰选择框）】
        function refreshCharacterData() {
            try {
                var characterData = ttsrv.readTxtFile("characterRecords.json") || "[]";
                characterRecords = JSON.parse(characterData);
  
  
  
                                // ↓ 纯ES5写法：替换箭头函数为forEach普通函数
                characterRecords.forEach(function(char) {
                    char.voice = replaceFayinrenName(char.voice || "");
                });
                      // ↑ 新增结束
            
                
  
  
  
                if (!Array.isArray(characterRecords)) characterRecords = [];
        
                refreshCharacterList();
              //  Toast.makeText(ctx, "角色数据已刷新", Toast.LENGTH_SHORT).show();
            } catch (e) {
                console.error("刷新角色数据失败：" + e);
                Toast.makeText(ctx, "刷新失败：" + (e.message || e.toString()), Toast.LENGTH_SHORT).show();
            }
        }
        
        // 【第五步：在EditorJS.onLoadUI中严格按顺序执行（关键！）】
        // （将以下代码插入到EditorJS.onLoadUI函数内，替换原书籍选择框相关代码）
        EditorJS = {
            // ...（保留其他原有函数）
            'onLoadUI': function (ctx, linearLayout) {
                // 1. 先初始化文件系统（确保书籍文件可访问）
                initializeFileSystem();
        
                // 2. 先创建第二行布局（secondRowLayout）
                var secondRowLayout = new android.widget.LinearLayout(ctx);
                secondRowLayout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                linearLayout.addView(secondRowLayout);
                ttsrv.setMargins(secondRowLayout, 0, 10, 0, 0);
        
                // 3. 创建书籍选择框（关键：先实例化，再赋值给全局变量）
                bookSpinner = new android.widget.Spinner(ctx); // 全局变量赋值
                // 设置选择框样式（原代码保留）
                var borderDrawable = new android.graphics.drawable.GradientDrawable();
                borderDrawable.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                borderDrawable.setStroke(3, android.graphics.Color.BLACK);
                borderDrawable.setColor(android.graphics.Color.TRANSPARENT);
                var arrowDrawable = ctx.getResources().getDrawable(android.R.drawable.arrow_down_float);
                arrowDrawable.setColorFilter(android.graphics.Color.BLACK, android.graphics.PorterDuff.Mode.SRC_IN);
                var layers = [borderDrawable, arrowDrawable];
                var layeredDrawable = new android.graphics.drawable.LayerDrawable(layers);
                layeredDrawable.setLayerGravity(1, android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL);
                layeredDrawable.setLayerInsetRight(1, dipToPx(10));
                bookSpinner.setBackground(layeredDrawable);
        
                // 4. 将选择框添加到布局（必须添加后再初始化）
                secondRowLayout.addView(bookSpinner);
                console.log("书籍选择框已创建并添加到布局");
        
                // 5. 创建刷新按钮
                var refreshButton = new android.widget.Button(ctx);
                refreshButton.setText("刷新");
                var refreshButtonParams = new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1);
                refreshButtonParams.setMargins(5, 0, 0, 0);
                refreshButton.setLayoutParams(refreshButtonParams);
                refreshButton.setBackgroundColor(android.graphics.Color.parseColor("#607D8B"));
                refreshButton.setTextColor(android.graphics.Color.WHITE);
                secondRowLayout.addView(refreshButton);
        
                // 6. 初始化书籍选择框（关键：在创建+添加到布局后执行）
                initBookSpinner();
        
                // 7. 绑定刷新按钮点击事件
                refreshButton.setOnClickListener(new android.view.View.OnClickListener({
                    onClick: function(view) {
                        refreshCharacterData(); // 仅刷新角色，不碰选择框
                    }
                }));
        
                // ...（保留其他原有UI创建代码）
            }
            // ...（保留其他原有函数）
        };
        
        // 【辅助函数：dip转px（确保选择框高度正常）】
        function dipToPx(dip) {
            return Math.round(dip * ctx.getResources().getDisplayMetrics().density);
        }
        
        // 【辅助函数：初始化文件系统（确保书籍相关文件存在）】
        function initializeFileSystem() {
            try {
                // 确保cunfang.txt存在（当前书籍标记）
                var cunfangContent = ttsrv.readTxtFile("cunfang.txt") || "默认";
                ttsrv.writeTxtFile("cunfang.txt", cunfangContent.trim());
        
                // 确保liebiao.json存在（书籍列表）
                var liebiaoContent = ttsrv.readTxtFile("liebiao.json") || "[]";
                var bookList = JSON.parse(liebiaoContent);
                if (!Array.isArray(bookList)) bookList = ["默认"];
                ttsrv.writeTxtFile("liebiao.json", JSON.stringify(bookList, null, 2));
        
                console.log("文件系统初始化完成");
            } catch (e) {
                console.error("文件系统初始化失败：" + e);
                Toast.makeText(ctx, "文件系统初始化出错：" + e, Toast.LENGTH_SHORT).show();
            }
        }
        
        // 1. 书籍备份到剪贴板（新增书名字段，备份JSON格式：{bookName: "书名", characterData: [...]}）
        function backupToClipboard() {
            try {
                // 获取当前书名和角色数据
                var currentBookName = getCurrentBookName();
                var characterData = ttsrv.readTxtFile("characterRecords.json");
                
                if (!currentBookName || currentBookName.trim() === "") {
                    Toast.makeText(ctx, "当前书籍名异常，无法备份", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!characterData || characterData.trim() === "") {
                    Toast.makeText(ctx, "当前书籍角色数据为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                // 封装「书名+角色数据」为JSON对象
                var backupData = {
                    bookName: currentBookName.trim(),
                    characterData: JSON.parse(characterData) // 提前解析为数组，确保恢复时格式正确
                };
                var backupJson = JSON.stringify(backupData, null, 2);
                
                // 复制到剪贴板
                var clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                var clip = android.content.ClipData.newPlainText("书籍备份（含书名）", backupJson);
                clipboard.setPrimaryClip(clip);
                
                Toast.makeText(ctx, "已备份书籍：" + currentBookName + "（含角色数据）", Toast.LENGTH_SHORT).show();
            } catch (e) {
                console.error("书籍备份到剪贴板失败: " + e.toString());
                Toast.makeText(ctx, "备份失败: " + e.toString(), Toast.LENGTH_SHORT).show();
            }
        }
        
        // 2. 从文本恢复书籍（自动解析书名，无需手动输入）
        function restoreFromText() {
            showMultiLineInputDialog("请输入书籍备份JSON（含书名）", function(inputText) {
                if (!inputText || inputText.trim() === "") {
                    Toast.makeText(ctx, "输入内容不能为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                try {
                    // 解析备份JSON（必须包含bookName和characterData字段）
                    var backupData = JSON.parse(inputText);
                    if (!backupData.bookName || !Array.isArray(backupData.characterData)) {
                        throw new Error("备份格式错误，需包含「bookName（书名）」和「characterData（角色数组）」");
                    }
                    
                    // 提取书名和角色数据
                    var restoredBookName = backupData.bookName.trim();
                    var restoredCharacterData = JSON.stringify(backupData.characterData, null, 2);
                    
                    if (restoredBookName === "") {
                        throw new Error("书籍名为空，无法恢复");
                    }
                    
                    // 执行恢复逻辑：更新书籍列表→保存角色数据→切换到恢复的书籍
                    updateBookList(restoredBookName); // 将书名加入书籍列表（去重）
                    ttsrv.writeTxtFile("cunfang.txt", restoredBookName); // 更新当前书名
                    ttsrv.writeTxtFile("characterRecords.json", restoredCharacterData); // 保存角色数据
                    ttsrv.writeTxtFile("shuming." + restoredBookName + ".json", restoredCharacterData); // 保存书籍专属文件
                    createGengxinFile(); // 更新gengxin.json
                    
                    // 加载恢复的数据并刷新UI
                    characterRecords = backupData.characterData;
                    initBookSpinner(); // 刷新书籍选择框
                    refreshCharacterList(); // 刷新角色列表
                    useBook(restoredBookName); // 切换到恢复的书籍
                    
                    Toast.makeText(ctx, "成功恢复书籍：" + restoredBookName + "（共" + backupData.characterData.length + "个角色）", Toast.LENGTH_SHORT).show();
                } catch (e) {
                    console.error("书籍恢复失败: " + e.toString());
                    Toast.makeText(ctx, "恢复失败: " + e.toString(), Toast.LENGTH_SHORT).show();
                }
            });
        }
        
        
        function updateBookList(newBookName) {
            try {
                var bookList = getBookList();
                
                var exists = false;
                for (var i = 0; i < bookList.length; i++) {
                    if (bookList[i] === newBookName) {
                        exists = true;
                        break;
                    }
                }
                
                if (!exists) {
                    bookList.push(newBookName);
                    bookList = removeDuplicateBooks(bookList);
                    ttsrv.writeTxtFile("liebiao.json", JSON.stringify(bookList, null, 2));
                    console.log("已添加新书名到liebiao.json: " + newBookName);
                }
                
            } catch (e) {
                console.error("更新书籍列表失败: " + e.toString());
            }
        }
        
        function removeDuplicateBooks(bookList) {
            console.log("开始检测重复书籍...");
            console.log("原始书籍列表: " + JSON.stringify(bookList));
            
            var uniqueBooks = [];
            var seenBooks = {};
            var removedCount = 0;
            
            for (var i = 0; i < bookList.length; i++) {
                var bookName = String(bookList[i]).trim();
                
                if (!seenBooks[bookName]) {
                    seenBooks[bookName] = true;
                    uniqueBooks.push(bookList[i]);
                    console.log("保留书籍: [" + bookName + "]");
                } else {
                    removedCount++;
                    console.log("移除重复书籍: [" + bookName + "]");
                }
            }
            
            if (removedCount > 0) {
                console.log("共移除 " + removedCount + " 个重复书籍");
                console.log("清理后书籍列表: " + JSON.stringify(uniqueBooks));
            } else {
                console.log("未发现重复书籍");
            }
            
            return uniqueBooks;
        }
        
        var mergeLabel = new android.widget.TextView(ctx);
        mergeLabel.setText("角色列表 (单击标记/取消标记并选中/取消选中):");
        mergeLabel.setTextSize(16);
        mergeLabel.setTextColor(android.graphics.Color.parseColor("#333333"));
        linearLayout.addView(mergeLabel);
        ttsrv.setMargins(mergeLabel, 0, 15, 0, 5);
        
        var mergeListView = new android.widget.ListView(ctx);
        linearLayout.addView(mergeListView);
        ttsrv.setMargins(mergeListView, 0, 5, 0, 0);
        
        var adapter = new android.widget.ArrayAdapter(ctx, android.R.layout.simple_list_item_single_choice);
        mergeListView.setAdapter(adapter);
        
        mergeListView.setChoiceMode(android.widget.AbsListView.CHOICE_MODE_SINGLE);
        
        console.log("填充列表，角色记录数量: " + characterRecords.length);
        for (var i = 0; i < characterRecords.length; i++) {
            var displayText = generateDisplayText(characterRecords[i]);
            console.log("添加角色选项: " + displayText);
            adapter.add(displayText);
        }
        
        var selectedIndex = -1;
        
        var markedIndices = [];
        
        mergeListView.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener({
            onItemClick: function(parent, view, position, id) {
                if (markedIndices.indexOf(position) !== -1) {
                    var newMarkedIndices = [];
                    for (var idx = 0; idx < markedIndices.length; idx++) {
                        if (markedIndices[idx] !== position) {
                            newMarkedIndices.push(markedIndices[idx]);
                        }
                    }
                    markedIndices = newMarkedIndices;
                    console.log("取消标记角色索引: " + position);
                                                
                            
                            
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
                    if (selectedIndex === position) {
                        selectedIndex = -1;
                        mergeListView.setItemChecked(position, false);
                    } else {
                        mergeListView.setItemChecked(position, false);
                    }
                } else {
                    markedIndices.push(position);
                    console.log("标记角色索引: " + position);
                    
                    selectedIndex = position;
                    mergeListView.setItemChecked(position, true);
                }
                
                updateListViewAppearance();
                
                mergeLabel.setText("角色列表 (已标记 " + markedIndices.length + "):");
            }
        }));
        
        var longPressedIndex = -1;
        var firstDialog = null;
        
        function showFirstDialog(position) {
            longPressedIndex = position;
            
            var builder = new android.app.AlertDialog.Builder(ctx);
            builder.setTitle("选择操作");
            
            var options = [
                "合并+跟随角色",
                "合并+选择发音人",
                "释放角色",
                "固定发音人",
                "固定当前发音人",
                "固定性别年龄",
                "删除角色",
                "取消所有标记",
                "修改角色",
                "设为主角"
  
            ];
            
            builder.setItems(options, new android.content.DialogInterface.OnClickListener({
                onClick: function(dialog, which) {
                    switch (which) {
                        case 0:
                            doMergeOperation("follow_target");
                            break;
                        case 1:
                            showVoiceSelectionDialogForMerge();
                            break;
                        case 2:
                            doReleaseOperation();
                            break;
                        case 3:
                            showVoiceSelectionDialogForFix();
                            break;
                        case 4:
                            doFixCurrentVoiceOperation();
                            break;
                        case 5:
                            showGenderAgeSelectionDialogForFix();
                            break;
                        case 6:
                            doDeleteCharacterOperation();
                            break;
                        case 7:
                            clearAllMarks();
                            break;
                        case 8:
                    
                            doEditCharacterOperation(position); // 新增“修改角色”处理
                            break;
                        case 9:
                    
                            setAsMainCharacter();// 新增选项对应的处理函数
                            break;
  
                    }
                }
            }));
            
            firstDialog = builder.create();
            firstDialog.show();
        }
        
  
  
  
  
  
  
  
  
  
  
        // 修复后：固定每个角色自己当前的发音人（仅改usageCount=100标记固定状态）
        function doFixCurrentVoiceOperation() {
            // 1. 校验前提：必须标记至少一个角色
            if (markedIndices.length === 0) {
                Toast.makeText(ctx, "请标记至少一个角色", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // 2. 数据备份（保留原安全机制）
            backupOriginalData();
            
            try {
                console.log("开始批量固定角色自身发音人，共" + markedIndices.length + "个角色");
                
                // 3. 核心逻辑：仅将每个标记角色的usageCount设为100（标识固定状态），不修改原有发音人
                for (var i = 0; i < markedIndices.length; i++) {
                    var charIndex = markedIndices[i];
                    var character = characterRecords[charIndex];
                    if (character) {
                        character.usageCount = 100; // 仅更新固定状态标记
                        console.log("已固定角色[" + safeGetName(character) + "]的当前发音人");
                    }
                }
                
                // 4. 保存数据并刷新列表
                saveCharacterData();
                createGengxinFile();
                
                Toast.makeText(ctx, "已为" + markedIndices.length + "个角色固定自身当前发音人", Toast.LENGTH_SHORT).show();
                refreshCharacterList();
                
            } catch (e) {
                console.error("批量固定自身发音人失败: " + e.toString());
                Toast.makeText(ctx, "操作失败: " + e.toString(), Toast.LENGTH_SHORT).show();
            }
        }
        
        function clearAllMarks() {
            markedIndices = [];
            selectedIndex = -1;
            mergeListView.clearChoices();
            updateListViewAppearance();
            mergeLabel.setText("角色列表 (单击标记/取消标记并选中/取消选中):");
        }
        
        // 角色列表长按事件（触发操作菜单）
        mergeListView.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener({
            onItemLongClick: function(parent, view, position, id) {
                if (markedIndices.indexOf(position) === -1) {
                    markedIndices.push(position);
                    console.log("长按自动标记角色索引: " + position);
                }
                
                selectedIndex = position;
                mergeListView.setItemChecked(position, true);
                
                updateListViewAppearance();
                
                mergeLabel.setText("角色列表 (已标记 " + markedIndices.length + "):");
                
                showFirstDialog(position);
                return true;
            }
        }));
        
        // 长按操作菜单（角色核心功能入口）
        function showFirstDialog(position) {
            longPressedIndex = position;
            
            var builder = new android.app.AlertDialog.Builder(ctx);
            builder.setTitle("选择操作");
            
            var options = [
                "合并+跟随角色",
                "合并+选择发音人",
                "释放角色",
                "固定发音人",
                "固定当前发音人",
                "固定性别年龄",
                "删除角色",
                "取消所有标记",
                "修改角色",
                "设为主角"
            ];
            
            builder.setItems(options, new android.content.DialogInterface.OnClickListener({
                onClick: function(dialog, which) {
                    switch (which) {
                        case 0:
                            doMergeOperation("follow_target");
                            break;
                        case 1:
                            showVoiceSelectionDialogForMerge();
                            break;
                        case 2:
                            doReleaseOperation();
                            break;
                        case 3:
                            showVoiceSelectionDialogForFix();
                            break;
                        case 4:
                            doFixCurrentVoiceOperation(); // 调用修复后的函数
                            break;
                        case 5:
                            showGenderAgeSelectionDialogForFix();
                            break;
                        case 6:
                            doDeleteCharacterOperation();
                            break;
                        case 7:
                            clearAllMarks();
                            break;
                        case 8:
                    
                            doEditCharacterOperation(position); // 新增“修改角色”处理
                            break;
                        case 9:
                    
                            setAsMainCharacter();// 新增选项对应的处理函数
                            break;
                    }
                }
            }));
            
            firstDialog = builder.create();
            firstDialog.show();
        }
        
        
        
        
        // 通用发音人筛选弹窗（核心修改：新增左下角「搜索」按钮+传递回调）
        function showFilteredVoiceDialog(voiceList, callback) {
            var builder = new android.app.AlertDialog.Builder(ctx);
            builder.setTitle("选择新发音人");
        
            var voiceOptions = [new Item("跟随目标角色", "follow_target")];
            for (var i = 0; i < voiceList.length; i++) {
                voiceOptions.push(new Item(voiceList[i], voiceList[i]));
            }
            var voiceNames = voiceOptions.map(item => item.name);
        
            // 选择发音人后的业务回调
            builder.setItems(voiceNames, new android.content.DialogInterface.OnClickListener({
                onClick: function(dialog, which) {
                    var selectedVoice = voiceOptions[which].value;
                    var selectedName = voiceOptions[which].name;
                    console.log("新发音人已更改为: " + selectedName);
                    callback(selectedVoice); // 执行合并/固定的核心回调
                    Toast.makeText(ctx, "新发音人已设置为: " + selectedName, Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                }
            }));
        
            // 新增：左下角「搜索」按钮（NeutralButton），点击后重新打开搜索弹窗
            builder.setNeutralButton("搜索", new android.content.DialogInterface.OnClickListener({
                onClick: function(dialog, which) {
                    dialog.dismiss(); // 关闭当前发音人列表
                    showVoiceSearchDialog(callback); // 重新弹出搜索弹窗，传递原回调（保证业务逻辑连续）
                }
            }));
        
            // 取消按钮
            builder.setNegativeButton("取消", new android.content.DialogInterface.OnClickListener({
                onClick: function(dialog) {
                    dialog.dismiss();
                }
            }));
        
            builder.create().show();
        }
        
        // 通用发音人搜索弹窗（无核心修改，保留原有逻辑）
        function showVoiceSearchDialog(callback) {
            var builder = new android.app.AlertDialog.Builder(ctx);
            builder.setTitle("搜索发音人");
        
            // 1. 搜索输入框
            var searchInput = new android.widget.EditText(ctx);
            searchInput.setHint("输入关键词搜索（如：少女）");
            searchInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
            searchInput.setMinHeight(dipToPx(40));
        
            // 2. 关键词容器（4排布局）
            var presetContainer = new android.widget.LinearLayout(ctx);
            presetContainer.setOrientation(android.widget.LinearLayout.VERTICAL);
            presetContainer.setGravity(android.view.Gravity.CENTER);
            presetContainer.setPadding(0, 10, 0, 10);
        
            // 按钮样式参数
            var buttonMargin = dipToPx(4);
            var btnTextSize = 13;
            var btnBgColor = android.graphics.Color.parseColor("#E3F2FD");
            var btnTextColor = android.graphics.Color.parseColor("#1976D2");
        
            // 3. 声明搜索弹窗实例
            var searchDialog;
        
            // 4. 创建关键词行（接收弹窗实例，直接关闭）
            function createKeywordRow(rowKeywords, dialogInstance) {
                var rowLayout = new android.widget.LinearLayout(ctx);
                rowLayout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                rowLayout.setGravity(android.view.Gravity.CENTER);
        
                for (var i = 0; i < rowKeywords.length; i++) {
                    var keyword = rowKeywords[i];
                    var presetBtn = new android.widget.Button(ctx);
                    presetBtn.setText(keyword);
                    presetBtn.setBackgroundColor(btnBgColor);
                    presetBtn.setTextColor(btnTextColor);
                    presetBtn.setTextSize(btnTextSize);
        
                    var btnParams = new android.widget.LinearLayout.LayoutParams(
                        0,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        1
                    );
                    btnParams.setMargins(buttonMargin, 15, buttonMargin, 15);
                    presetBtn.setLayoutParams(btnParams);
        
                    // 关键词点击：关闭搜索弹窗+筛选
                    presetBtn.setOnClickListener(new android.view.View.OnClickListener({
                        onClick: function(view) {
                            var keyword = view.getText().toString();
                            if (dialogInstance) {
                                try {
                                    dialogInstance.dismiss();
                                } catch (e) {}
                            }
                            filterAndShowVoiceList(keyword, callback);
                        }
                    }));
        
                    rowLayout.addView(presetBtn);
                }
                return rowLayout;
            }
        
            // 5. 组合布局（输入框+关键词）
            var rootLayout = new android.widget.LinearLayout(ctx);
            rootLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
            rootLayout.setPadding(dipToPx(15), dipToPx(10), dipToPx(15), dipToPx(10));
            rootLayout.addView(searchInput);
        
            // 6. 实例化弹窗+添加关键词行
            builder.setView(rootLayout);
            searchDialog = builder.create();
            presetContainer.addView(createKeywordRow(PRESET_KEYWORDS_ROW1, searchDialog));
            presetContainer.addView(createKeywordRow(PRESET_KEYWORDS_ROW2, searchDialog));
            presetContainer.addView(createKeywordRow(PRESET_KEYWORDS_ROW3, searchDialog));
            presetContainer.addView(createKeywordRow(PRESET_KEYWORDS_ROW4, searchDialog));
            rootLayout.addView(presetContainer);
            searchDialog.setContentView(rootLayout);
        
            // 7. 左下角「全部列表」按钮
            searchDialog.setButton(android.app.AlertDialog.BUTTON_NEUTRAL, "全部列表", new android.content.DialogInterface.OnClickListener({
                onClick: function(dialog, which) {
                    dialog.dismiss(); // 关闭搜索弹窗
                    // 显示全部发音人列表
                    var fullVoiceList = fayinrenList.length > 0 ? fayinrenList : ["默认发音人"];
                    showFilteredVoiceDialog(fullVoiceList, callback);
                }
            }));
        
            // 8. 确定按钮：搜索筛选
            searchDialog.setButton(android.app.AlertDialog.BUTTON_POSITIVE, "确定", new android.content.DialogInterface.OnClickListener({
                onClick: function(dialog, which) {
                    var keyword = searchInput.getText().toString().trim();
                    dialog.dismiss();
                    filterAndShowVoiceList(keyword, callback);
                }
            }));
        
            // 9. 取消按钮：关闭弹窗
            searchDialog.setButton(android.app.AlertDialog.BUTTON_NEGATIVE, "取消", new android.content.DialogInterface.OnClickListener({
                onClick: function(dialog) {
                    dialog.dismiss();
                }
            }));
        
            // 10. 显示搜索弹窗
            searchDialog.show();
        }
        
        // 通用筛选逻辑（无修改）
        function filterAndShowVoiceList(keyword, callback) {
            var fullVoiceList = fayinrenList.length > 0 ? fayinrenList : ["默认发音人"];
            var filteredList = fullVoiceList;
        
            if (keyword !== "") {
                var lowerKeyword = keyword.toLowerCase();
                filteredList = fullVoiceList.filter(voice => 
                    voice.toLowerCase().includes(lowerKeyword)
                );
            }
        
            if (filteredList.length === 0) {
                Toast.makeText(ctx, "未找到包含「" + keyword + "」的发音人", Toast.LENGTH_SHORT).show();
                showFilteredVoiceDialog(fullVoiceList, callback);
            } else {
                showFilteredVoiceDialog(filteredList, callback);
            }
        }
        
        // 保留原有辅助函数
        function dipToPx(dip) {
            return Math.round(dip * ctx.getResources().getDisplayMetrics().density);
        }
        
        // 合并时直接弹出搜索弹窗
        function showVoiceSelectionDialogForMerge() {
            showVoiceSearchDialog(function(selectedVoice) {
                doMergeOperation(selectedVoice);
            });
        }
        
        // 固定发音人时直接弹出搜索弹窗
        function showVoiceSelectionDialogForFix() {
            showVoiceSearchDialog(function(selectedVoice) {
                doFixVoiceOperation(selectedVoice);
            });
        }
        
        
        
        // 固定性别年龄选择对话框
        function showGenderAgeSelectionDialogForFix() {
            var builder = new android.app.AlertDialog.Builder(ctx);
            builder.setTitle("选择性别年龄");
            
            var genderAgeOptions = [];
            for (var charName in GENSHIN_CHARACTERS) {
                genderAgeOptions.push(new Item(charName, charName));
            }
            
            var genderAgeNames = [];
            for (var i = 0; i < genderAgeOptions.length; i++) {
                genderAgeNames.push(genderAgeOptions[i].name);
            }
            
            builder.setItems(genderAgeNames, new android.content.DialogInterface.OnClickListener({
                onClick: function(dialog, which) {
                    var selectedGenderAge = genderAgeOptions[which].value;
                    console.log("性别年龄已更改为: " + genderAgeOptions[which].name);
                    
                    doFixGenderAgeOperation(selectedGenderAge);
                    
                    Toast.makeText(ctx, "性别年龄已设置为: " + genderAgeOptions[which].name, Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                }
            }));
            builder.create().show();
        }
        
        
       
               // 新增：设为主角（设置age为'主角'，usageCount为100）
        function setAsMainCharacter() {
            if (longPressedIndex === -1) {
                Toast.makeText(ctx, "请长按一个角色", Toast.LENGTH_SHORT).show();
                return;
            }
            
            backupOriginalData(); // 保留原有备份机制
            
            try {
                var character = characterRecords[longPressedIndex];
                if (character) {
                    character.age = '主角'; // 设置年龄为“主角”
                    character.usageCount = 100; // 设置固定状态标记
                    
                    // 保存数据并刷新列表（复用原有函数，确保一致性）
                    saveCharacterData();
                    createGengxinFile();
                    refreshCharacterList();
                    
                    Toast.makeText(ctx, "已将角色「" + safeGetName(character) + "」设为主角", Toast.LENGTH_SHORT).show();
                }
            } catch (e) {
                console.error("设为主角失败: " + e.toString());
                Toast.makeText(ctx, "操作失败: " + e.toString(), Toast.LENGTH_SHORT).show();
            }
        }
         
        
        
        
        // 执行合并操作（支持多角色合并到目标角色）
        function doMergeOperation(newVoiceName) {
            var targetRecord = null;
            var targetIndex = -1;
            if (selectedIndex >= 0 && markedIndices.indexOf(selectedIndex) !== -1) {
                targetRecord = characterRecords[selectedIndex];
                targetIndex = selectedIndex;
            }
            
            // 单角色场景：仅更新发音人
            if (markedIndices.length === 1) {
                if (!targetRecord) {
                    Toast.makeText(ctx, "请标记并选中一个角色", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                try {
                    if (newVoiceName !== "follow_target") {
                        targetRecord.voice = newVoiceName; // 仅更新当前角色发音人
                    }
                    
                    saveCharacterData();
                    createGengxinFile();
                    
                    Toast.makeText(ctx, "角色发音人更新成功", Toast.LENGTH_SHORT).show();
                    
                    selectedIndex = -1;
                    markedIndices = [];
                    mergeListView.clearChoices();
                    refreshCharacterList();
                    
                } catch (e) {
                    console.error("更新发音人失败: " + e.toString());
                    Toast.makeText(ctx, "更新失败: " + e.toString(), Toast.LENGTH_SHORT).show();
                }
                return;
            }
            
            // 多角色合并场景：校验目标角色
            if (!targetRecord) {
                Toast.makeText(ctx, "请标记并选中一个角色作为目标角色", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // 收集待合并角色（排除目标角色）
            var mergeRecords = [];
            for (var i = 0; i < markedIndices.length; i++) {
                var index = markedIndices[i];
                if (index !== targetIndex) {
                    mergeRecords.push(characterRecords[index]);
                }
            }
            
            if (mergeRecords.length === 0) {
                Toast.makeText(ctx, "请标记至少一个要合并的角色", Toast.LENGTH_SHORT).show();
                return;
            }
            
            try {
                // 字符串标准化（去特殊字符、trim）
                function normalizeString(str) {
                    if (typeof str !== 'string') {
                        str = String(str);
                    }
                    try {
                        return str.replace(/[\u200B-\u200D\uFEFF]/g, '').trim();
                    } catch (e) {
                        return str.trim();
                    }
                }
                
                var normalizedTargetName = normalizeString(safeGetName(targetRecord));
                var allAliases = {};
                
                // 收集目标角色的别名
                if (targetRecord.aliases) {
                    var targetAliases = targetRecord.aliases.split('|');
                    for (var i = 0; i < targetAliases.length; i++) {
                        allAliases[targetAliases[i].trim()] = true;
                    }
                } else {
                    allAliases[targetRecord.name] = true;
                }
                
                // 收集待合并角色的别名
                for (var i = 0; i < mergeRecords.length; i++) {
                    var mergeRecord = mergeRecords[i];
                    if (mergeRecord.aliases) {
                        var mergeAliases = mergeRecord.aliases.split('|');
                        for (var j = 0; j < mergeAliases.length; j++) {
                            allAliases[mergeAliases[j].trim()] = true;
                        }
                    } else {
                        allAliases[mergeRecord.name] = true;
                    }
                }
                
                // 合并别名（去重）
                var mergedAliases = [];
                for (var alias in allAliases) {
                    mergedAliases.push(alias);
                }
                targetRecord.aliases = mergedAliases.join('|');
                
                // 若指定新发音人，更新目标角色发音人
                if (newVoiceName && newVoiceName !== "follow_target") {
                    targetRecord.voice = newVoiceName;
                }
                
                // 移除待合并角色（保留目标角色）
                var newCharacterRecords = [];
                for (var i = 0; i < characterRecords.length; i++) {
                    var skip = false;
                    for (var j = 0; j < mergeRecords.length; j++) {
                        if (characterRecords[i] === mergeRecords[j]) {
                            skip = true;
                            break;
                        }
                    }
                    if (!skip) {
                        newCharacterRecords.push(characterRecords[i]);
                    }
                }
                characterRecords = newCharacterRecords;
                
                // 保存并刷新
                saveCharacterData();
                createGengxinFile();
                
                Toast.makeText(ctx, "角色合并成功", Toast.LENGTH_SHORT).show();
                
                selectedIndex = -1;
                markedIndices = [];
                mergeListView.clearChoices();
                refreshCharacterList();
                
            } catch (e) {
                console.error("合并操作失败: " + e.toString());
                Toast.makeText(ctx, "合并失败: " + e.toString(), Toast.LENGTH_SHORT).show();
            }
        } 
  
  
  // 执行释放角色操作（从别名拆分新角色）
        function doReleaseOperation() {
            backupOriginalData();
            
            if (markedIndices.length === 0) {
                Toast.makeText(ctx, "请标记至少一个角色", Toast.LENGTH_SHORT).show();
                return;
            }
            
            try {
                function normalizeString(str) {
                    if (typeof str !== 'string') {
                        str = String(str);
                    }
                    try {
                        return str.replace(/[\u200B-\u200D\uFEFF]/g, '').trim();
                    } catch (e) {
                        return str.trim();
                    }
                }
                
                // 倒序处理（避免索引混乱）
                for (var k = markedIndices.length - 1; k >= 0; k--) {
                    var index = markedIndices[k];
                    var character = characterRecords[index];
                    
                    if (!character.aliases || character.aliases.trim() === "") {
                        continue; // 无别名则跳过
                    }
                    
                    var aliases = character.aliases.split('|');
                    var normalizedName = normalizeString(safeGetName(character));
                    var newRecordsForThis = [];
                    
                    // 遍历别名，拆分出新角色（排除与角色名重复的别名）
                    for (var i = 0; i < aliases.length; i++) {
                        var alias = aliases[i].trim();
                        var normalizedAlias = normalizeString(alias);
                        
                        if (normalizedAlias === normalizedName) {
                            continue; // 跳过与角色名一致的别名
                        }
                    
                        // 检查新角色是否已存在
                        var exists = false;
                        for (var j = 0; j < characterRecords.length; j++) {
                            var recordName = normalizeString(safeGetName(characterRecords[j]));
                            if (recordName === normalizedAlias) {
                                exists = true;
                                break;
                            }
                        }
                        
                        // 不存在则创建新角色（按要求设置固定值，不继承主角色属性）
                        if (!exists) {
                            var newRecord = {
                                name: alias,
                                aliases: alias,
                                voice: "",       // 不跟随主角色，设为空
                                gender: "",      // 不跟随主角色，设为空
                                age: "",         // 不跟随主角色，设为空
                                usageCount: 0    // 不跟随主角色，设为0
                            };
                            
                            newRecordsForThis.push(newRecord);
                        }
                    }
                    
                    // 插入新角色到列表（原角色后）
                    if (newRecordsForThis.length > 0) {
                        var args = [index + 1, 0].concat(newRecordsForThis);
                        Array.prototype.splice.apply(characterRecords, args);
                    }
                    
                    // 原角色仅保留自身名称作为别名
                    character.aliases = character.name;
                }
                
                // 保存并刷新
                saveCharacterData();
                createGengxinFile();
                
                Toast.makeText(ctx, "角色释放成功", Toast.LENGTH_SHORT).show();
                
                selectedIndex = -1;
                markedIndices = [];
                mergeListView.clearChoices();
                refreshCharacterList();
                
            } catch (e) {
                console.error("释放角色失败: " + e.toString());
                Toast.makeText(ctx, "释放失败: " + e.toString(), Toast.LENGTH_SHORT).show();
            }
        }
        
  
  
        // 执行恢复备份操作（从backup文件恢复角色数据）
        function doRestoreBackup() {
            if (restoreBackup()) {
                Toast.makeText(ctx, "恢复备份成功", Toast.LENGTH_SHORT).show();
                selectedIndex = -1;
                markedIndices = [];
                mergeListView.clearChoices();
                refreshCharacterList();
            } else {
                Toast.makeText(ctx, "恢复备份失败", Toast.LENGTH_SHORT).show();
            }
        }
        
        // 执行固定发音人操作（单个角色指定新发音人）
        function doFixVoiceOperation(selectedVoice) {
            if (longPressedIndex === -1) {
                Toast.makeText(ctx, "请长按一个角色", Toast.LENGTH_SHORT).show();
                return;
            }
            
            backupOriginalData();
            
            try {
                var character = characterRecords[longPressedIndex];
                if (character) {
                    character.voice = selectedVoice; // 设置新发音人
                    character.usageCount = 100; // 标记固定状态
                    
                    saveCharacterData();
                    createGengxinFile();
                    
                    Toast.makeText(ctx, "已为角色固定发音人", Toast.LENGTH_SHORT).show();
                    refreshCharacterList();
                }
            
            } catch (e) {
                console.error("固定发音人失败: " + e.toString());
                Toast.makeText(ctx, "操作失败: " + e.toString(), Toast.LENGTH_SHORT).show();
            }
        }
                // 执行固定性别年龄操作（单个角色指定性别年龄）
        function doFixGenderAgeOperation(selectedGenderAge) {
            if (longPressedIndex === -1) {
                Toast.makeText(ctx, "请长按一个角色", Toast.LENGTH_SHORT).show();
                return;
            }
            
            var parts = selectedGenderAge.split(' ');
            if (parts.length !== 2) {
                Toast.makeText(ctx, "格式错误，应为'性别 年龄'", Toast.LENGTH_SHORT).show();
                return;
            }
            var gender = parts[0];
            var age = parts[1];
            
            backupOriginalData();
            
            try {
                var character = characterRecords[longPressedIndex];
                if (character) {
                    cleanupGenderAgeTags(character); // 清理旧标签
                    character.gender = gender;
                    character.age = age;
                    // 核心修改：age为"主角"时usageCount设为100，否则保持50
                    character.usageCount = age === '主角' ? 100 : 50;
                    
                    saveCharacterData();
                    createGengxinFile();
                    
                    Toast.makeText(ctx, "已为角色固定性别年龄", Toast.LENGTH_SHORT).show();
                    refreshCharacterList();
                }
            
            } catch (e) {
                console.error("固定性别年龄失败: " + e.toString());
                Toast.makeText(ctx, "操作失败: " + e.toString(), Toast.LENGTH_SHORT).show();
            }
        }
  
        
        // 执行删除角色操作（删除所有标记角色）
        function doDeleteCharacterOperation() {
            if (markedIndices.length === 0) {
                Toast.makeText(ctx, "请标记至少一个角色", Toast.LENGTH_SHORT).show();
                return;
            }
            
            backupOriginalData();
            
            try {
                // 筛选保留未标记角色
                var newCharacterRecords = [];
                for (var i = 0; i < characterRecords.length; i++) {
                    if (markedIndices.indexOf(i) === -1) {
                        newCharacterRecords.push(characterRecords[i]);
                    }
                }
                
                characterRecords = newCharacterRecords;
                
                // 保存并刷新
                saveCharacterData();
                createGengxinFile();
                
                Toast.makeText(ctx, "已删除" + markedIndices.length + "个角色", Toast.LENGTH_SHORT).show();
                refreshCharacterList();
                
            } catch (e) {
                console.error("删除角色失败: " + e.toString());
                Toast.makeText(ctx, "操作失败: " + e.toString(), Toast.LENGTH_SHORT).show();
            }
        }
        
        // 刷新角色列表（更新UI显示）
        function refreshCharacterList() {
            adapter.clear();
            for (var i = 0; i < characterRecords.length; i++) {
                var displayText = generateDisplayText(characterRecords[i]);
                adapter.add(displayText);
            }
            markedIndices = [];
            selectedIndex = -1;
            mergeListView.clearChoices();
            updateListViewAppearance();
            mergeLabel.setText("角色列表 (已标记 " + markedIndices.length + "):");
            updateListViewHeight();
        }
        
        // 更新列表外观（标记/选中状态可视化）
        
      // 【完全兼容原逻辑】行高自适应+列表样式渲染函数
      function updateListViewAppearance() {
        // -------------- 原代码：获取系统默认文字颜色，完全不变 --------------
        var systemTextColor = android.graphics.Color.parseColor("#333333");
        try {
            if (secondRowLayout && secondRowLayout instanceof android.widget.LinearLayout) {
                var bookSpinner = secondRowLayout.getChildAt(0);
                if (bookSpinner && bookSpinner instanceof android.widget.Spinner) {
                    var spinnerAdapter = bookSpinner.getAdapter();
                    if (spinnerAdapter) {
                        var spinnerItemView = spinnerAdapter.getView(0, null, bookSpinner);
                        if (spinnerItemView && spinnerItemView instanceof android.widget.TextView) {
                            systemTextColor = spinnerItemView.getCurrentTextColor();
                        }
                    }
                }
            }
        } catch (e) {
            console.error("获取系统颜色失败：" + e.toString());
        }
    
        // 原代码：标题颜色设置，完全不变
        if (mergeLabel && mergeLabel instanceof android.widget.TextView) {
            mergeLabel.setTextColor(systemTextColor);
        }
    
        // -------------- 核心：你原有的自适应行高设置，完全保留 --------------
        for (var i = 0; i < mergeListView.getChildCount(); i++) {
            var child = mergeListView.getChildAt(i);
            if (child instanceof android.widget.CheckedTextView) {
                // 1. 高度自动包裹文本内容，和你之前的设置完全一致
                child.setLayoutParams(new android.widget.AbsListView.LayoutParams(
                    android.widget.AbsListView.LayoutParams.MATCH_PARENT,
                    android.widget.AbsListView.LayoutParams.WRAP_CONTENT
                ));
                // 2. 关闭单行限制，允许文本自动换行
                child.setSingleLine(false);
                child.setMaxLines(java.lang.Integer.MAX_VALUE);
                child.setEllipsize(null);
                // 3. 固定内边距，和高度计算里的内边距完全一致，确保尺寸匹配
                child.setPadding(dipToPx(15), dipToPx(12), dipToPx(15), dipToPx(12));
    
                // -------------- 原代码：标记/选中样式逻辑，完全不变 --------------
                child.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                child.setTextColor(systemTextColor);
    
                if (markedIndices.indexOf(i) !== -1 && i !== selectedIndex) {
                    child.setBackgroundColor(android.graphics.Color.parseColor("#FFF9C4"));
                    child.setTextColor(android.graphics.Color.parseColor("#333333"));
                } else if (markedIndices.indexOf(i) !== -1 && i === selectedIndex) {
                    child.setBackgroundColor(android.graphics.Color.parseColor("#FFF9C4"));
                    child.setTextColor(android.graphics.Color.parseColor("#FF5722"));
                } else if (i === selectedIndex) {
                    child.setBackgroundColor(android.graphics.Color.parseColor("#E3F2FD"));
                    child.setTextColor(android.graphics.Color.parseColor("#1976D2"));
                }
            }
        }
    
        // -------------- 核心：行样式渲染完成后，同步更新列表总高度 --------------
        updateListViewHeight();
    }
    
  
        
      // 【完全匹配原代码规律】适配自适应行高的列表总高度计算函数
      function updateListViewHeight() {
        // --------------- 完全沿用你原代码的参数规范 ---------------
        var itemTotalCount = characterRecords.length; // 原代码：用角色总数计算，和原逻辑完全一致
        var maxHeight = Math.floor(300000 * density + 0.5); // 原代码：极大值不限制高度，避免截断，你要改上限可以改这里的300000
        var totalHeight = 0;
        var listAdapter = mergeListView.getAdapter();
    
        // 无角色数据时，高度设为0，和原代码逻辑一致
        if (itemTotalCount === 0 || !listAdapter) {
            var layoutParams = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                0
            );
            mergeListView.setLayoutParams(layoutParams);
            mergeListView.requestLayout();
            return;
        }
    
        // --------------- 修复核心：正确测量每一行的实际自适应高度 ---------------
        // 修复：用屏幕宽度测量，避免ListView未布局时getWidth()=0导致的测量错误（显示不全的核心原因）
        var screenWidth = ctx.getResources().getDisplayMetrics().widthPixels;
        var listPaddingHorizontal = mergeListView.getPaddingLeft() + mergeListView.getPaddingRight();
        var availableWidth = screenWidth - listPaddingHorizontal; // 列表可用宽度，测量100%准确
    
        // 遍历所有角色行，累加实际高度
        for (var i = 0; i < itemTotalCount; i++) {
            var listItem = listAdapter.getView(i, null, mergeListView);
            // 强制设置和你UI里一致的内边距，确保测量高度和实际显示高度完全一致
            listItem.setPadding(dipToPx(15), dipToPx(12), dipToPx(15), dipToPx(12));
            // 精确测量：宽度用列表实际可用宽度，高度完全按文本内容自适应
            listItem.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(availableWidth, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
            );
            // 累加每一行的实际测量高度
            totalHeight += listItem.getMeasuredHeight();
        }
    
        // --------------- 完全沿用你原代码的补充计算逻辑 ---------------
        // 累加列表上下内边距
        totalHeight += mergeListView.getPaddingTop() + mergeListView.getPaddingBottom();
        // 累加行之间的分割线总高度
        var dividerHeight = mergeListView.getDividerHeight();
        totalHeight += dividerHeight * (itemTotalCount - 1);
    
        // --------------- 完全沿用你原代码的最终高度取值规则 ---------------
        var finalHeight = Math.min(totalHeight, maxHeight);
    
        // --------------- 完全沿用你原代码的布局参数设置方式 ---------------
        // 强制使用LinearLayout.LayoutParams，和你原代码完全一致，避免布局类型不兼容
        var layoutParams = new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            finalHeight
        );
        mergeListView.setLayoutParams(layoutParams);
        // 强制刷新布局，确保修改立即生效
        mergeListView.requestLayout();
        mergeListView.invalidate();
      }
    
        
        // 初始设置列表高度
        updateListViewHeight();
  
    // 创建第一行功能按钮容器（合并、释放、回退）
    var buttonContainer = new android.widget.LinearLayout(ctx);
    buttonContainer.setOrientation(android.widget.LinearLayout.HORIZONTAL);
    linearLayout.addView(buttonContainer);
    
    // 1. 执行合并按钮
    var mergeButton = new android.widget.Button(ctx);
    mergeButton.setText("执行合并");
    mergeButton.setBackgroundColor(android.graphics.Color.parseColor("#4CAF50"));
    mergeButton.setTextColor(android.graphics.Color.WHITE);
    
    var mergeButtonParams = new android.widget.LinearLayout.LayoutParams(
        0,
        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
        1
    );
    mergeButtonParams.setMargins(0, 20, 5, 0);
    mergeButton.setLayoutParams(mergeButtonParams);
    buttonContainer.addView(mergeButton);
    
    // 2. 释放角色按钮
    var releaseButton = new android.widget.Button(ctx);
    releaseButton.setText("释放角色");
    releaseButton.setBackgroundColor(android.graphics.Color.parseColor("#2196F3"));
    releaseButton.setTextColor(android.graphics.Color.WHITE);
    
    var releaseButtonParams = new android.widget.LinearLayout.LayoutParams(
        0,
        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
        1
    );
    releaseButtonParams.setMargins(5, 20, 5, 0);
    releaseButton.setLayoutParams(releaseButtonParams);
    buttonContainer.addView(releaseButton);
    
    // 3. 回退一步（恢复备份）按钮
    var restoreButton = new android.widget.Button(ctx);
    restoreButton.setText("回退一步");
    restoreButton.setBackgroundColor(android.graphics.Color.parseColor("#FF9800"));
    restoreButton.setTextColor(android.graphics.Color.WHITE);
    
    var restoreButtonParams = new android.widget.LinearLayout.LayoutParams(
        0,
        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
        1
    );
    restoreButtonParams.setMargins(5, 20, 0, 0);
    restoreButton.setLayoutParams(restoreButtonParams);
    buttonContainer.addView(restoreButton);
    
    // 创建第二行功能按钮容器（固定发音人、固定当前发音人、固定性别年龄、删除）
    var newButtonContainer = new android.widget.LinearLayout(ctx);
    newButtonContainer.setOrientation(android.widget.LinearLayout.HORIZONTAL);
    linearLayout.addView(newButtonContainer);
    
    // 1. 固定发音人按钮
    var fixVoiceButton = new android.widget.Button(ctx);
    fixVoiceButton.setText("固定发音人");
    fixVoiceButton.setBackgroundColor(android.graphics.Color.parseColor("#9C27B0"));
    fixVoiceButton.setTextColor(android.graphics.Color.WHITE);
    
    var fixVoiceButtonParams = new android.widget.LinearLayout.LayoutParams(
        0,
        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
        1
    );
    fixVoiceButtonParams.setMargins(0, 20, 5, 0);
    fixVoiceButton.setLayoutParams(fixVoiceButtonParams);
    newButtonContainer.addView(fixVoiceButton);
    
    // 2. 固定当前发音人按钮（调用修复后的函数）
    var fixCurrentVoiceButton = new android.widget.Button(ctx);
    fixCurrentVoiceButton.setText("固定当前发音人");
    fixCurrentVoiceButton.setBackgroundColor(android.graphics.Color.parseColor("#7B1FA2")); // 深紫色区分
    fixCurrentVoiceButton.setTextColor(android.graphics.Color.WHITE);
    
    var fixCurrentVoiceParams = new android.widget.LinearLayout.LayoutParams(
        0,
        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
        1
    );
    fixCurrentVoiceParams.setMargins(5, 20, 5, 0);
    fixCurrentVoiceButton.setLayoutParams(fixCurrentVoiceParams);
    newButtonContainer.addView(fixCurrentVoiceButton);
    
    // 3. 固定性别年龄按钮
    var fixGenderAgeButton = new android.widget.Button(ctx);
    fixGenderAgeButton.setText("固定性别年龄");
    fixGenderAgeButton.setBackgroundColor(android.graphics.Color.parseColor("#673AB7"));
    fixGenderAgeButton.setTextColor(android.graphics.Color.WHITE);
    
    var fixGenderAgeButtonParams = new android.widget.LinearLayout.LayoutParams(
        0,
        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
        1
    );
    fixGenderAgeButtonParams.setMargins(5, 20, 5, 0);
    fixGenderAgeButton.setLayoutParams(fixGenderAgeButtonParams);
    newButtonContainer.addView(fixGenderAgeButton);
    
    // 4. 删除角色按钮
    var deleteCharacterButton = new android.widget.Button(ctx);
    deleteCharacterButton.setText("删除角色");
    deleteCharacterButton.setBackgroundColor(android.graphics.Color.parseColor("#F44336"));
    deleteCharacterButton.setTextColor(android.graphics.Color.WHITE);
    
    var deleteCharacterButtonParams = new android.widget.LinearLayout.LayoutParams(
        0,
        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
        1
    );
    deleteCharacterButtonParams.setMargins(5, 20, 0, 0);
    deleteCharacterButton.setLayoutParams(deleteCharacterButtonParams);
    newButtonContainer.addView(deleteCharacterButton);
    
    // ---------------------- 按钮点击事件绑定 ----------------------
    // 合并按钮点击事件
    mergeButton.setOnClickListener(new android.view.View.OnClickListener({
        onClick: function(view) {
            doMergeOperation("follow_target");
        }
    }));
    
    // 释放按钮点击事件
    releaseButton.setOnClickListener(new android.view.View.OnClickListener({
        onClick: function(view) {
            doReleaseOperation();
        }
    }));
    
    // 回退一步（恢复备份）按钮点击事件
    restoreButton.setOnClickListener(new android.view.View.OnClickListener({
        onClick: function(view) {
            doRestoreBackup();
        }
    }));
    
    // 固定发音人按钮点击事件
    fixVoiceButton.setOnClickListener(new android.view.View.OnClickListener({
        onClick: function(view) {
            if (selectedIndex >= 0 && markedIndices.indexOf(selectedIndex) !== -1) {
                longPressedIndex = selectedIndex;
                showVoiceSelectionDialogForFix();
            } else {
                Toast.makeText(ctx, "请标记并选中一个角色", Toast.LENGTH_SHORT).show();
            }
        }
    }));
    
    // 固定当前发音人按钮点击事件（调用修复后的函数）
    fixCurrentVoiceButton.setOnClickListener(new android.view.View.OnClickListener({
        onClick: function(view) {
            doFixCurrentVoiceOperation();
        }
    }));
    
    // 固定性别年龄按钮点击事件
    fixGenderAgeButton.setOnClickListener(new android.view.View.OnClickListener({
        onClick: function(view) {
            if (selectedIndex >= 0 && markedIndices.indexOf(selectedIndex) !== -1) {
                longPressedIndex = selectedIndex;
                showGenderAgeSelectionDialogForFix();
            } else {
                Toast.makeText(ctx, "请标记并选中一个角色", Toast.LENGTH_SHORT).show();
            }
        }
    }));
    
    // 删除角色按钮点击事件
    deleteCharacterButton.setOnClickListener(new android.view.View.OnClickListener({
        onClick: function(view) {
            doDeleteCharacterOperation();
        }
    }));
  
    // ---------------------- 音效模块核心代码（完整） ----------------------
    // 音效保存辅助函数（新增/修改通用）
    function saveSound(name, alias, soundUrl) {
        if (!soundUrl || soundUrl.trim() === "") {
            Toast.makeText(ctx, "网址不能为空", Toast.LENGTH_SHORT).show();
            return;
        }
        var url = soundUrl.trim();
        try {
            var yinxiaoJson = ttsrv.readTxtFile("yinxiao.json");
            var yinxiaoList = yinxiaoJson ? JSON.parse(yinxiaoJson) : [];
            if (!Array.isArray(yinxiaoList)) yinxiaoList = [];
  
            var isOverwrite = false;
            // 查找同名音效，存在则覆盖
            for (var i = 0; i < yinxiaoList.length; i++) {
                if (yinxiaoList[i].name === name) {
                    yinxiaoList[i].alias = alias;
                    yinxiaoList[i].url = url;
                    isOverwrite = true;
                    break;
                }
            }
            // 不存在则新增
            if (!isOverwrite) {
                yinxiaoList.push({ "name": name, "alias": alias, "url": url });
            }
  
            // 写入文件（保留格式化）
            ttsrv.writeTxtFile("yinxiao.json", JSON.stringify(yinxiaoList, null, 2));
            Toast.makeText(ctx, isOverwrite ? "音效已覆盖" : "音效已新增", Toast.LENGTH_SHORT).show();
        } catch (e) {
            console.error("保存音效失败: " + e.toString());
            Toast.makeText(ctx, "失败: " + e.toString(), Toast.LENGTH_SHORT).show();
        }
    }
  
    // 带默认值的输入对话框（修改音效时回显用）
    function showInputDialog(title, callback, defaultValue = "") {
        var builder = new android.app.AlertDialog.Builder(ctx);
        builder.setTitle(title);
        var input = new android.widget.EditText(ctx);
        input.setText(defaultValue); // 回显默认值
        builder.setView(input);
        builder.setPositiveButton("确定", function(dialog, which) {
            callback(input.getText().toString());
        });
        builder.setNegativeButton("取消", function(dialog) {
            dialog.cancel();
        });
        builder.show();
    }
  
    // 新增音效点击逻辑（支持@、>、<、＞、＜特殊符号）
    addSoundBtn.setOnClickListener(new android.view.View.OnClickListener({
        onClick: function(view) {
            showInputDialog("音效名称(“@”  “>”  “＜”)\n#为正则去双引号", function(soundName) {
                if (!soundName || soundName.trim() === "") {
                    Toast.makeText(ctx, "名称不能为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                var name = soundName.trim();
                var alias = "";
                // 特殊符号判断：@、>、<、＞、＜开头需输入别名
                var specialPrefixes = ["@", "#", ">", "<", "＞", "＜"];
                const isSpecialSound = specialPrefixes.some(prefix => name.startsWith(prefix));
                
                if (isSpecialSound) {
                    // 特殊音效必须设置别名（用于列表显示）
                    showInputDialog("请输入音效别名（用于列表显示）", function(inputAlias) {
                        alias = inputAlias ? inputAlias.trim() : "";
                        if (!alias) {
                            Toast.makeText(ctx, "特殊音效必须设置别名", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        // 输入音效URL
                        showInputDialog("请输入音效网址", function(soundUrl) {
                            saveSound(name, alias, soundUrl);
                        });
                    });
                } else {
                    // 普通音效无需别名，直接输入URL
                    showInputDialog("请输入音效网址", function(soundUrl) {
                        saveSound(name, alias, soundUrl);
                    });
                }
            });
        }
    }));
  
    // 音效备份/恢复点击逻辑（备份到剪贴板、从文本恢复）
    soundBackupBtn.setOnClickListener(new android.view.View.OnClickListener({
        onClick: function(view) {
            var builder = new android.app.AlertDialog.Builder(ctx);
            builder.setTitle("音效备份");
            var options = ["备份到剪贴板", "从文本恢复"];
            
            builder.setItems(options, new android.content.DialogInterface.OnClickListener({
                onClick: function(dialog, which) {
                    switch (which) {
                        case 0:
                            backupSoundToClipboard(); // 备份到剪贴板
                            break;
                        case 1:
                            restoreSoundFromText(); // 从文本恢复（添加+覆盖模式）
                            break;
                    }
                }
            }));
            builder.show();
        }
    }));
  
    // 删除音效点击逻辑（支持多选删除）
    deleteSoundBtn.setOnClickListener(new android.view.View.OnClickListener({
        onClick: function(view) {
            try {
                var yinxiaoJson = ttsrv.readTxtFile("yinxiao.json");
                if (!yinxiaoJson || yinxiaoJson.trim() === "") {
                    Toast.makeText(ctx, "音效列表为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                var yinxiaoList = JSON.parse(yinxiaoJson);
                if (!Array.isArray(yinxiaoList) || yinxiaoList.length === 0) {
                    Toast.makeText(ctx, "音效列表为空", Toast.LENGTH_SHORT).show();
                    return;
                }
  
                // 整理显示名称（特殊音效显示别名，普通音效显示名称）
                var displayNames = [];
                var nameMap = {};
                for (var i = 0; i < yinxiaoList.length; i++) {
                    var item = yinxiaoList[i];
                    var showName = (item.name.startsWith("@") || item.name.startsWith("#") || item.name.startsWith(">") || item.name.startsWith("<") || item.name.startsWith("＞") || item.name.startsWith("＜")) 
                        ? (item.alias || item.name) 
                        : item.name;
                    displayNames.push(showName);
                    nameMap[showName] = item.name; // 映射显示名到真实名
                }
  
                // 多选对话框
                var checkedItems = new Array(displayNames.length).fill(false);
                new android.app.AlertDialog.Builder(ctx)
                    .setTitle("选择要删除的音效（可多选）")
                    .setMultiChoiceItems(displayNames, checkedItems, function(dialog, which, isChecked) {
                        checkedItems[which] = isChecked;
                    })
                    .setPositiveButton("删除选中", function(dialog, which) {
                        var toDelete = [];
                        // 收集要删除的音效真实名
                        for (var i = 0; i < checkedItems.length; i++) {
                            if (checkedItems[i]) {
                                toDelete.push(nameMap[displayNames[i]]);
                            }
                        }
                        if (toDelete.length === 0) {
                            Toast.makeText(ctx, "未选择音效", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        // 筛选保留未删除的音效
                        var newSoundList = yinxiaoList.filter(item => !toDelete.includes(item.name));
                        ttsrv.writeTxtFile("yinxiao.json", JSON.stringify(newSoundList, null, 2));
                        Toast.makeText(ctx, "已删除" + toDelete.length + "个音效", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("取消", function(dialog) {
                        dialog.cancel();
                    })
                    .show();
            } catch (e) {
                console.error("删除音效失败: " + e.toString());
                Toast.makeText(ctx, "失败: " + e.toString(), Toast.LENGTH_SHORT).show();
            }
        }
    }));
  
    // 修改音效点击逻辑（支持特殊符号音效）
    editSoundBtn.setOnClickListener(new android.view.View.OnClickListener({
        onClick: function(view) {
            try {
                var yinxiaoJson = ttsrv.readTxtFile("yinxiao.json");
                if (!yinxiaoJson || yinxiaoJson.trim() === "") {
                    Toast.makeText(ctx, "音效列表为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                var yinxiaoList = JSON.parse(yinxiaoJson);
                if (!Array.isArray(yinxiaoList) || yinxiaoList.length === 0) {
                    Toast.makeText(ctx, "音效列表为空", Toast.LENGTH_SHORT).show();
                    return;
                }
  
                // 整理显示名称
                var displayNames = [];
                var soundMap = {};
                for (var i = 0; i < yinxiaoList.length; i++) {
                    var item = yinxiaoList[i];
                    var showName = (item.name.startsWith("@") || item.name.startsWith("#") || item.name.startsWith(">") || item.name.startsWith("<") || item.name.startsWith("＞") || item.name.startsWith("＜")) 
                        ? (item.alias || item.name) 
                        : item.name;
                    displayNames.push(showName);
                    soundMap[showName] = item; // 映射显示名到音效对象
                }
  
                // 选择要修改的音效
                new android.app.AlertDialog.Builder(ctx)
                    .setTitle("选择要修改的音效")
                    .setItems(displayNames, new android.content.DialogInterface.OnClickListener({
                        onClick: function(dialog, which) {
                            var selectedShowName = displayNames[which];
                            var targetSound = soundMap[selectedShowName];
                            if (!targetSound) return;
  
                            // 第一步：修改音效名称
                            showInputDialog("修改音效名称", function(newName) {
                                if (!newName || newName.trim() === "") {
                                    Toast.makeText(ctx, "名称不能为空", Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                newName = newName.trim();
                                var newAlias = targetSound.alias || "";
  
                                // 别名处理回调（统一后续逻辑）
                                var aliasCallback = function() {
                                    // 第二步：修改音效URL（回显原URL）
                                    showInputDialog("修改音效网址", function(newUrl) {
                                        if (!newUrl || newUrl.trim() === "") {
                                            Toast.makeText(ctx, "网址不能为空", Toast.LENGTH_SHORT).show();
                                            return;
                                        }
                                        newUrl = newUrl.trim();
  
                                        // 更新音效列表并保存
                                        for (var i = 0; i < yinxiaoList.length; i++) {
                                            if (yinxiaoList[i].name === targetSound.name) {
                                                yinxiaoList[i].name = newName;
                                                yinxiaoList[i].alias = newAlias;
                                                yinxiaoList[i].url = newUrl;
                                                break;
                                            }
                                        }
                                        ttsrv.writeTxtFile("yinxiao.json", JSON.stringify(yinxiaoList, null, 2));
                                        Toast.makeText(ctx, "音效修改成功", Toast.LENGTH_SHORT).show();
                                    }, targetSound.url); // 回显原URL
                                };
  
                                // 新名称若为特殊符号，强制输入别名
                                if (newName.startsWith("@") || item.name.startsWith("#") || newName.startsWith(">") || newName.startsWith("<") || newName.startsWith("＞") || newName.startsWith("＜")) {
                                    showInputDialog("修改音效别名", function(inputAlias) {
                                        newAlias = inputAlias ? inputAlias.trim() : "";
                                        if (!newAlias) {
                                            Toast.makeText(ctx, "特殊音效必须设置别名", Toast.LENGTH_SHORT).show();
                                            return;
                                        }
                                        aliasCallback();
                                    }, targetSound.alias); // 回显原别名
                                } else {
                                    aliasCallback(); // 普通音效无需别名
                                }
                            }, targetSound.name); // 回显原名称
                        }
                    }))
                    .show();
            } catch (e) {
                console.error("修改音效失败: " + e.toString());
                Toast.makeText(ctx, "失败: " + e.toString(), Toast.LENGTH_SHORT).show();
            }
        }
    }));
  
    // 音效备份到剪贴板
    function backupSoundToClipboard() {
        try {
            var soundData = ttsrv.readTxtFile("yinxiao.json");
            if (!soundData || soundData.trim() === "") {
                Toast.makeText(ctx, "音效数据为空", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // 获取系统剪贴板服务
            var clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            var clip = android.content.ClipData.newPlainText("音效配置备份", soundData);
            clipboard.setPrimaryClip(clip);
            
            Toast.makeText(ctx, "音效配置已复制到剪贴板", Toast.LENGTH_SHORT).show();
        } catch (e) {
            console.error("音效备份失败: " + e.toString());
            Toast.makeText(ctx, "备份失败: " + e.toString(), Toast.LENGTH_SHORT).show();
        }
    }
  
    // 从文本恢复音效（添加+重名覆盖模式）
    function restoreSoundFromText() {
        showMultiLineInputDialog("请输入音效配置JSON", function(inputText) {
            if (!inputText || inputText.trim() === "") {
                Toast.makeText(ctx, "输入内容不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            
            try {
                // 1. 解析新输入的音效配置
                var newSoundList = JSON.parse(inputText);
                if (!Array.isArray(newSoundList)) {
                    Toast.makeText(ctx, "音效配置必须是数组格式", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                // 2. 读取原有音效配置（不存在则为空数组）
                var oldYinxiaoJson = ttsrv.readTxtFile("yinxiao.json");
                var oldSoundList = oldYinxiaoJson ? JSON.parse(oldYinxiaoJson) : [];
                if (!Array.isArray(oldSoundList)) {
                    oldSoundList = []; // 旧配置格式错误时重置
                }
                
                // 3. 合并逻辑：新音效添加，重名覆盖（按name判断）
                newSoundList.forEach(newSound => {
                    if (!newSound.name) {
                        Toast.makeText(ctx, "跳过无效音效（缺少name或url）", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // 查找旧列表中是否有同名音效
                    const oldIndex = oldSoundList.findIndex(oldSound => oldSound.name === newSound.name);
                    if (oldIndex > -1) {
                        // 重名：覆盖旧音效
                        oldSoundList[oldIndex] = newSound;
                        console.log("覆盖音效：" + newSound.name);
                    } else {
                        // 不重名：新增到旧列表
                        oldSoundList.push(newSound);
                        console.log("新增音效：" + newSound.name);
                    }
                });
                
                // 4. 写入合并后的配置（保留格式化）
                ttsrv.writeTxtFile("yinxiao.json", JSON.stringify(oldSoundList, null, 2));
                // 计算新增/覆盖数量
                const overwriteCount = newSoundList.filter(n => oldSoundList.some(o => o.name === n.name)).length;
                const addCount = newSoundList.length - overwriteCount;
                Toast.makeText(ctx, `音效配置恢复成功（新增${addCount}个，覆盖${overwriteCount}个）`, Toast.LENGTH_SHORT).show();
            } catch (e) {
                console.error("解析音效JSON失败: " + e.toString());
                Toast.makeText(ctx, "数据格式错误: " + e.toString(), Toast.LENGTH_SHORT).show();
            }
        });
    }
  
    // 多行输入对话框（用于音效恢复的JSON输入）
    function showMultiLineInputDialog(title, callback) {
        try {
            var builder = new android.app.AlertDialog.Builder(ctx);
            builder.setTitle(title);
            
            var input = new android.widget.EditText(ctx);
            // 设置多行输入
            input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            input.setSingleLine(false);
            input.setLines(8); // 默认8行
            input.setMaxLines(15); // 最大15行
            
            builder.setView(input);
            
            builder.setPositiveButton("确定", new android.content.DialogInterface.OnClickListener({
                onClick: function(dialog, which) {
                    var inputText = input.getText().toString();
                    if (callback) {
                        callback(inputText);
                    }
                }
            }));
            
            builder.setNegativeButton("取消", new android.content.DialogInterface.OnClickListener({
                onClick: function(dialog, which) {
                    dialog.cancel();
                }
            }));
            
            var dialog = builder.create();
            dialog.show();
            
        } catch (e) {
            console.error("显示多行输入对话框失败: " + e.toString());
            // 降级为单行输入
            showInputDialog(title + " (使用单行输入)", callback);
        }
    }
  
    // 新增：修改角色函数（预填原有数据，流程：改角色名→改别名→改发音人）
    function doEditCharacterOperation(position) {
        // 1. 获取被长按角色的原有数据（确保索引有效）
        if (position < 0 || position >= characterRecords.length) {
            Toast.makeText(ctx, "角色索引无效", Toast.LENGTH_SHORT).show();
            return;
        }
        var targetChar = characterRecords[position];
        var originalName = targetChar.name || "";       // 原有角色名
        var originalAlias = targetChar.aliases || "";   // 原有别名
        var originalVoice = targetChar.voice || "默认发音人"; // 原有发音人
    
        // 封装角色保存逻辑（选择发音人/沿用原发音人复用）
        function saveEditedCharacter(finalName, finalAlias, finalVoice) {
            // 更新角色数据（仅修改变化的字段，保留其他原有字段如gender/age）
            targetChar.name = finalName;
            targetChar.aliases = finalAlias;
            targetChar.voice = finalVoice;
            targetChar.usageCount = 100; // 保持发音人固定状态（与新增逻辑一致）
    
            // 保存数据+刷新列表（完全复用原代码函数，无新增逻辑）
            saveCharacterData();
            createGengxinFile();
            refreshCharacterList();
    
            Toast.makeText(ctx, "角色修改成功：" + finalName, Toast.LENGTH_SHORT).show();
        }
    
        // 2. 第一步：修改角色名（预填原有角色名）
        showInputDialog("修改角色名（原：" + originalName + "）", function(newName) {
            var finalName = newName ? newName.trim() : "";
            if (finalName === "") {
                Toast.makeText(ctx, "角色名不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
    
            // 3. 第二步：修改别名（预填原有别名，空则用原角色名）
            var aliasDefault = originalAlias || originalName;
            showInputDialog("修改角色别名（原：" + aliasDefault + "）", function(newAlias) {
                var finalAlias = newAlias ? newAlias.trim() : "";
                finalAlias = finalAlias || finalName;
    
                // 4. 第三步：修改发音人弹窗（核心修改：调整按钮布局+新增沿用选项）
                var voiceOptions = fayinrenList.length > 0 ? fayinrenList : ["默认发音人"];
                var dialogBuilder = new android.app.AlertDialog.Builder(ctx)
                    .setTitle("修改角色固定发音人（原：" + originalVoice + "）")
                    // 发音人列表选择逻辑不变
                    .setItems(voiceOptions, function(dialog, which) {
                        var selectedVoice = voiceOptions[which];
                        saveEditedCharacter(finalName, finalAlias, selectedVoice);
                        dialog.dismiss();
                    })
                    // 左侧：取消按钮
                    .setNegativeButton("取消", function(dialog) {
                        dialog.cancel();
                    })
                    // 右侧：新增沿用之前发音人按钮
                    .setPositiveButton("沿用之前发音人", function(dialog) {
                        saveEditedCharacter(finalName, finalAlias, originalVoice);
                        dialog.dismiss();
                    });
                
                dialogBuilder.create().show();
            }, aliasDefault);
        }, originalName);
    }
    
  
  
  
  
    // ---------------------- 【核心补充：正确的书籍切换逻辑】 ----------------------
    // 1. 在initBookSpinner函数末尾补充选择监听器（若原代码无此监听器）
    // （注：若原代码已有initBookSpinner函数，需将此监听器插入到函数最后）
    if (typeof initBookSpinner === 'function' && !initBookSpinner.__hasSwitchListener) {
        var originInitBookSpinner = initBookSpinner;
        initBookSpinner = function() {
            var result = originInitBookSpinner.apply(this, arguments);
            var bookList = getBookList();
            var bookSpinner = bookSpinner || (function() {
                // 查找原代码中的bookSpinner变量（适配原代码变量声明方式）
                var views = linearLayout.getChildAt(linearLayout.getChildCount() - 5); // 适配第二行布局位置
                return views ? views.getChildAt(0) : null;
            })();
            
            if (bookSpinner && bookList && bookList.length > 0) {
                bookSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener({
                    onItemSelected: function(parent, view, position, id) {
                        var selectedBook = bookList[position];
                        var currentBookName = getCurrentBookName();
                        if (selectedBook !== currentBookName) {
                            saveCurrentBookBeforeSwitch(selectedBook);
                        }
                    },
                    onNothingSelected: function(parent) {}
                }));
            }
            initBookSpinner.__hasSwitchListener = true;
            return result;
        };
    }
  
    // 2. 切换书籍前保存当前书籍数据
    function saveCurrentBookBeforeSwitch(newBookName) {
        try {
            var characterData = ttsrv.readTxtFile("characterRecords.json") || JSON.stringify(characterRecords, null, 2);
            var currentBookName = getCurrentBookName();
            if (currentBookName && currentBookName.trim() !== "") {
                var shumingFileName = "shuming." + currentBookName + ".json";
                ttsrv.writeTxtFile(shumingFileName, characterData);
                createGengxinFile();
            }
            useBook(newBookName);
        } catch (e) {
            console.error("保存当前书籍失败: " + e.toString());
            useBook(newBookName);
        }
    }
  
    // 3. 加载目标书籍数据
    function useBook(bookName) {
        try {
            ttsrv.writeTxtFile("cunfang.txt", bookName);
            var shumingFileName = "shuming." + bookName + ".json";
            
            // 尝试读取目标书籍文件
            var bookData = null;
            try {
                bookData = ttsrv.readTxtFile(shumingFileName);
            } catch (readError) {
                console.log("书籍文件不存在: " + shumingFileName);
            }
            
            // 只有当文件存在且有数据时才更新 characterManager.characterRecords
            if (bookData && bookData.trim() !== "") {
                var parsedData = JSON.parse(bookData);
                var loadedRecords = Array.isArray(parsedData) ? parsedData : [];
                
                // 正向映射 voice 字段
                loadedRecords.forEach(function(char) {
                    char.voice = replaceFayinrenName(char.voice || "");
                });
                
                // 更新 characterManager.characterRecords
                characterManager.characterRecords = loadedRecords;
                
                // 只有在数据有效时才写入 characterRecords.json
                ttsrv.writeTxtFile("characterRecords.json", bookData);
            } else {
                // 【修复】：书籍文件不存在时，保持原有数据不变！
                // 不要写入空数据覆盖原有角色记录
                console.log("目标书籍文件不存在，保持原有角色数据不变");
            }
            
            createGengxinFile();
            refreshCharacterList();
            Toast.makeText(ctx, "已切换到书籍: " + bookName, Toast.LENGTH_SHORT).show();
        } catch (e) {
            console.error("加载书籍失败: " + e.toString());
            // 【修复】：加载失败时不覆盖原有数据
            Toast.makeText(ctx, "切换失败，角色数据保持不变", Toast.LENGTH_SHORT).show();
        }
    }
    // ---------------------- 书籍切换逻辑补充结束 ----------------------
    },
  
    // 发音人切换回调（预留，当前无实际逻辑）
    'onVoiceChanged': function (locale, voice) {
        // 发音人已为摆设，无需处理
    }
  }
  
  // 重写PluginJS.getAudio：修复特殊符号音效匹配逻辑（精确匹配@、>、<、＞、＜）
  // 重写PluginJS.getAudio：保留特殊符号匹配修复 + 新增http网址直接下载
  PluginJS.getAudio = function (text, locale, voice, speed, volume, pitch) {
  
    
    text = "https://cnb.cool/mingwuyan/yinpin/-/git/raw/main/wenxintishi.mp3?download=true";
    const targetText = text.trim();
    
    // 【新增：http/https开头直接下载，优先级最高】
    if (targetText.startsWith("http://") || targetText.startsWith("https://")) {
        var audioResponse = ttsrv.httpGet(targetText);
        if (audioResponse.code() !== 200) {
            throw "网址下载失败，网址：" + targetText + "，状态码：" + audioResponse.code();
        }
        return audioResponse.body().byteStream();
    }
  
    // 以下是原重写逻辑（特殊符号精确匹配），完全保留不变
    var yinxiaoPath = "yinxiao.json";
    var yinxiaoJson = ttsrv.readTxtFile(yinxiaoPath);
    
    // 校验配置文件存在性
    if (!yinxiaoJson || yinxiaoJson.trim() === "") {
        throw "音效配置文件[" + yinxiaoPath + "]不存在或为空";
    }
    
    // 解析配置文件
    var yinxiaoList = null;
    try {
        yinxiaoList = JSON.parse(yinxiaoJson);
    } catch (e) {
        throw "音效配置文件格式错误：" + e.toString();
    }
    
    // 校验配置格式为数组
    if (!Array.isArray(yinxiaoList)) {
        throw "音效配置文件应为数组格式";
    }
    
    // 特殊符号列表（需精确匹配）
    var specialPrefixes = ["@", "#", ">", "<", "＞", "＜"]; 
    var targetSound = null;
    
    // 遍历音效列表匹配
    for (var i = 0; i < yinxiaoList.length; i++) {
        var soundItem = yinxiaoList[i];
        if (soundItem && soundItem.name) {
            // 判断是否为特殊符号音效
            const isSpecialSound = specialPrefixes.some(prefix => soundItem.name.startsWith(prefix));
            if (isSpecialSound) {
                // 特殊音效：必须完全精确匹配（如“>”只能匹配“>”）
                if (soundItem.name === targetText) {
                    targetSound = soundItem;
                    break;
                }
            } else {
            
            // 普通音效：新增完整关键词匹配 + 保留原拆分匹配（ES5语法）
                var fullKeyword = soundItem.name.trim();
                // 1. 先匹配完整关键词（支持输入“轰隆|轰隆隆”）
                if (targetText === fullKeyword) {
                  targetSound = soundItem;
                  break;
                }
                // 普通音效：按“|”分割多名称，任一匹配即可
                var soundNames = soundItem.name.split('|').map(name => name.trim());
                if (soundNames.includes(targetText)) {
                    targetSound = soundItem;
                    break;
                }
            }
        }
    }
    
    // 校验匹配结果
    if (!targetSound || !targetSound.url) {
        throw "未找到文字[" + text + "]对应的音效配置";
    }
    
    // 下载音效并返回字节流
    var audioResponse = ttsrv.httpGet(targetSound.url);
    if (audioResponse.code() !== 200) {
        throw "音效下载失败，网址：" + targetSound.url + "，状态码：" + audioResponse.code();
    }
    
    return audioResponse.body().byteStream();
  }
  
  // 多重备份方式保存密钥（兼容不同存储接口）
  function saveKeyWithMultipleMethods(key) {
    try {
        try {
            // 方式1：使用ttsrv接口
            ttsrv.writeTxtFile("miyue_backup.txt", key);
            console.log("使用ttsrv.writeTxtFile备份密钥成功");
        } catch (e1) {
            console.error("ttsrv.writeTxtFile失败: " + e1.toString());
            try {
                // 方式2：使用fs接口（若存在）
                fs.writeFile("miyue_backup.txt", key);
                console.log("使用fs.writeFile备份密钥成功");
            } catch (e2) {
                console.error("fs.writeFile失败: " + e2.toString());
                try {
                    // 方式3：使用Java文件流（底层兼容）
                    var file = new java.io.File("miyue_backup.txt");
                    var fos = new java.io.FileOutputStream(file);
                    fos.write(new java.lang.String(key).getBytes("UTF-8"));
                    fos.close();
                    console.log("使用文件流写入备份密钥成功");
                } catch (e3) {
                    console.error("文件流写入失败: " + e3.toString());
                }
            }
        }
    } catch (e) {
        console.error("备份密钥失败: " + e.toString());
    }
  }
  
  // 全局长按菜单函数（与列表长按事件复用）
  function showFirstDialog(position) {
    longPressedIndex = position;
    
    var builder = new android.app.AlertDialog.Builder(ctx);
    builder.setTitle("选择操作");
    
    var options = [
        "合并+跟随角色",
        "合并+选择发音人",
        "释放角色",
        "固定发音人",
        "固定当前发音人",
        "固定性别年龄",
        "删除角色",
        "取消所有标记",
        "修改角色",
        "设为主角"
  
    ];
    
    builder.setItems(options, new android.content.DialogInterface.OnClickListener({
        onClick: function(dialog, which) {
            switch (which) {
                case 0:
                    doMergeOperation("follow_target");
                    break;
                case 1:
                    showVoiceSelectionDialogForMerge();
                    break;
                case 2:
                    doReleaseOperation();
                    break;
                case 3:
                    showVoiceSelectionDialogForFix();
                    break;
                case 4:
                    doFixCurrentVoiceOperation(); // 调用修复后的函数
                    break;
                case 5:
                    showGenderAgeSelectionDialogForFix();
                    break;
                case 6:
                    doDeleteCharacterOperation();
                    break;
                case 7:
                    clearAllMarks();
                    break;
                case 8:
                    
                    doEditCharacterOperation(position); // 新增“修改角色”处理
                    break;
                case 9:
                    
                    setAsMainCharacter();// 新增选项对应的处理函数
                    break;
  
            }
        }
    }));
    
    firstDialog = builder.create();
    firstDialog.show();
  }
  
  // 全局密钥输入框（预留引用）
  let keyEditor = null;
  
  // Item类：用于发音人/性别年龄选择的“名称-值”映射
  function Item(name, value) {
    this.name = name;
    this.value = value;
  }