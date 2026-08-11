// ===================== 朗读脚本：AI对话角色分析（提取自朗读规则2.js）=====================
// 核心逻辑完全提取自 /workspace/朗读规则2.js，去掉角色管理/发音人/TTS合成，
// 只保留：获取密钥→提取对话→下文→加序号→缓存匹配→AI分析→保存缓存
//
// 运行环境：Rhino JS (ES5.1)，朗读脚本上下文
// 文件路径：/storage/emulated/0/Download/chajian/mingwuyan/
//
// 前置文件：
//   miyue.txt  — 密钥（格式：地址@@模型@@密钥 或纯密钥字符串）
//   data.json  — 章节内容（含 texts 字段为完整章节文本）
// 生成文件：
//   dialog_cache.json — 对话分析结果缓存
// ==================================================================================


// ===================== 配置区 =====================
var EXT_DIR = "/storage/emulated/0/Download/chajian/mingwuyan/";
var CACHE_FILE = EXT_DIR + "dialog_cache.json";
var KEY_FILE = EXT_DIR + "miyue.txt";
var DATA_FILE = EXT_DIR + "data.json";

var ANALYZE_TIMEOUT = 25000;  // 请求体里的timeout（当前ajaxRaceDelayed已忽略，保留兼容）
var RACE_DELAY = 8000;         // 阶梯间隔：8秒后启动备用请求
var MAX_RETRY = 3;
var BELOW_EXTRACT_LENGTH = 800;    // 下文提取字数（每次运行都是全新上下文，无需区分首次）
var MAX_HISTORY = 20;               // 上文历史段落数
var HISTORY_FILE = EXT_DIR + "paragraphHistory.json";

var DEFAULT_API_CONFIG = {
    endpoint: "https://open.bigmodel.cn/api/paas/v4/chat/completions",
    model: "glm-4-flash",
    key: "b26b869ffd7e4a1dac61666db27de213.ayAJYkmqeA1w3OL"
};


// ===================== 工具函数 =====================
function padZero(num, length) {
    num = num.toString();
    while (num.length < length) num = "0" + num;
    return num;
}
function log(msg) {
    try { java.log(msg); } catch (e) {}
}


// ===================== 文本清理（和朗读规则2.js cleanDialogText 完全一致）=====================
function cleanDialogText(text) {
    // 直接使用真正的中文引号与标点符号，不再用变量
    var quoteReg = /[“”‘’"']/g;
    var keepReg = /[^一-龥。？！，、；：“”‘’（）【】《》…—·a-zA-Z0-9]/g;
    // 不可见空白符保留\u转义
    var wsReg = /[\s　\u2000-\u200F\u2028-\u202F\uFEFF]/g;
    return text
        .replace(wsReg, "")
        .replace(/【\d+】/g, "")
        .replace(quoteReg, "")
        .replace(keepReg, "")
        .trim();
}


// ===================== APIKey管理（提取自朗读规则2.js DualKeyManager，简化为单场景）=====================
function readApiConfigs() {
    try {
        var content = java.readExternalFile(KEY_FILE);
        // java.readExternalFile 返回 Java String，必须显式转为 JS String 后再用 JS 方法
        content = String(content);
        if (!content || content.trim() === "") return [];
        var contentTrim = content.trim();
        // 有##分隔：只取姓名分析部分
        if (contentTrim.indexOf("##") !== -1) {
            contentTrim = contentTrim.split("##")[0].trim();
        }
        if (contentTrim === "") return [];
        // 有@@：按 地址@@模型@@密钥 分组
        if (contentTrim.indexOf("@@") !== -1) {
            var splitArr = contentTrim.split("@@");
            var configs = [];
            for (var i = 0; i + 2 < splitArr.length; i += 3) {
                var endpoint = String(splitArr[i] || "").trim();
                var model = String(splitArr[i + 1] || "").trim();
                var key = String(splitArr[i + 2] || "").trim();
                if (key) {
                    if (endpoint) {
                        // String.prototype.endsWith 是 ES6 方法，Rhino 1.8.1 不支持，改用 indexOf + length
                        if (endpoint.lastIndexOf("/") === endpoint.length - 1) endpoint = endpoint.slice(0, -1);
                        if (endpoint.indexOf("/chat/completions") === endpoint.length - 17) endpoint = endpoint.slice(0, -17);
                        endpoint += "/chat/completions";
                    }
                    configs.push({
                        endpoint: endpoint || DEFAULT_API_CONFIG.endpoint,
                        model: model || DEFAULT_API_CONFIG.model,
                        key: key
                    });
                }
            }
            return configs;
        } else {
            // 纯密钥
            return [{ endpoint: DEFAULT_API_CONFIG.endpoint, model: DEFAULT_API_CONFIG.model, key: contentTrim }];
        }
    } catch (e) {
        log("【密钥】读取失败：" + e.message);
        return [];
    }
}

var _apiIndex = 0;   // 轮转索引
function getNextApiConfig() {
    var configs = readApiConfigs();
    if (configs.length === 0) return DEFAULT_API_CONFIG;
    if (_apiIndex >= configs.length) _apiIndex = 0;
    var cfg = configs[_apiIndex];
    _apiIndex = (_apiIndex + 1) % configs.length;
    return cfg;
}


// ===================== 缓存读写（提取自朗读规则2.js readDialogCache/writeDialogCache）=====================
function readDialogCache() {
    try {
        var content = java.readExternalFile(CACHE_FILE);
        content = String(content);
        if (!content || content.trim() === "") return { currentIndex: 1, dialogList: [] };
        var raw = JSON.parse(content);
        if (!raw || typeof raw !== "object") return { currentIndex: 1, dialogList: [] };
        var safeList = Array.isArray(raw.dialogList)
            ? raw.dialogList.filter(function(item) {
                return item && typeof item === "object" && typeof item.dialogContent === "string";
            })
            : [];
        var safeIdx = typeof raw.currentIndex === "number" && raw.currentIndex >= 1 ? raw.currentIndex : 1;
        if (safeIdx > safeList.length + 1) safeIdx = Math.max(1, safeList.length);
        return { currentIndex: safeIdx, dialogList: safeList };
    } catch (e) {
        log("【缓存】读取失败：" + e.message);
        return { currentIndex: 1, dialogList: [] };
    }
}
function writeDialogCache(cacheData) {
    try { java.writeExternalFile(CACHE_FILE, JSON.stringify(cacheData, null, 2)); return true; }
    catch (e) { log("【缓存】写入失败：" + e.message); return false; }
}


// ===================== 缓存匹配（提取自朗读规则2.js matchDialogFromCache，逐行匹配，偏移±2，匹配后 index+1）=====================
function matchDialogFromCache(currentDialogText) {
    var cache = readDialogCache();
    var dialogList = cache.dialogList;
    var currentIndex = cache.currentIndex;
    var MAX_FORWARD_OFFSET = 2;
    var MAX_BACKWARD_OFFSET = 2;

    if (!dialogList || dialogList.length === 0 || currentIndex < 1 || currentIndex > dialogList.length + 1) {
        return null;
    }

    var cleanCurrent = cleanDialogText(currentDialogText);
    if (cleanCurrent === "") return null;

    var matchedResult = null;
    var finalMatchedIndex = -1;

    // 优先级1：当前目标位置
    var ci = Math.min(currentIndex - 1, dialogList.length - 1);
    if (ci >= 0 && ci < dialogList.length) {
        var item = dialogList[ci];
        var lines = item.dialogContent.split("\n").filter(function(l) { return l.trim() !== ""; });
        for (var i = 0; i < lines.length; i++) {
            if (cleanDialogText(lines[i]) === cleanCurrent) { matchedResult = { name: item.name, gender: item.gender, age: item.age }; finalMatchedIndex = ci + 1; break; }
        }
    }
    // 优先级2：向前偏移
    if (!matchedResult) {
        for (var offset = 1; offset <= MAX_FORWARD_OFFSET; offset++) {
            var ti = currentIndex - 1 - offset;
            if (ti < 0) break;
            var item = dialogList[ti];
            var lines = item.dialogContent.split("\n").filter(function(l) { return l.trim() !== ""; });
            for (var i = 0; i < lines.length; i++) {
                if (cleanDialogText(lines[i]) === cleanCurrent) { matchedResult = { name: item.name, gender: item.gender, age: item.age }; finalMatchedIndex = ti + 1; break; }
            }
            if (matchedResult) break;
        }
    }
    // 优先级3：向后偏移
    if (!matchedResult) {
        for (var offset = 1; offset <= MAX_BACKWARD_OFFSET; offset++) {
            var ti = currentIndex - 1 + offset;
            if (ti >= dialogList.length) break;
            var item = dialogList[ti];
            var lines = item.dialogContent.split("\n").filter(function(l) { return l.trim() !== ""; });
            for (var i = 0; i < lines.length; i++) {
                if (cleanDialogText(lines[i]) === cleanCurrent) { matchedResult = { name: item.name, gender: item.gender, age: item.age }; finalMatchedIndex = ti + 1; break; }
            }
            if (matchedResult) break;
        }
    }

    // 匹配成功：更新序号（直接写入匹配到的位置，不加一）
    if (matchedResult && finalMatchedIndex > 0) {
        cache.currentIndex = finalMatchedIndex;
        writeDialogCache(cache);
        return matchedResult;
    }
    return null;
}


// ===================== 提取段落中的中文全角双引号对话 =====================
function extractDialogs(paragraph) {
    var dialogs = [];
    // 直接使用中文双引号字符
    var regex = /“([^”]{2,1000})”/g;
    var match;
    while ((match = regex.exec(paragraph)) !== null) {
        var content = match[1];
        if (content && content.length >= 1) {
            dialogs.push({
                content: content,
                fullMatch: match[0],
                index: match.index,
                length: match[0].length
            });
        }
    }
    return dialogs;
}


// ===================== 下文内容提取（提取自朗读规则2.js next100Chars 获取逻辑）=====================
function getBelowContent(currentParagraph) {
    try {
        var dataStr = java.readExternalFile(DATA_FILE);
        dataStr = String(dataStr);
        if (!dataStr || dataStr.trim() === "") return "";
        var data = JSON.parse(dataStr);
        if (!data || !data.texts) return "";
        var fullChapter = String(data.texts);
        var targetPos = fullChapter.indexOf(currentParagraph);
        if (targetPos === -1) {
            var shortPara = currentParagraph.substring(0, Math.min(currentParagraph.length, 50));
            targetPos = fullChapter.indexOf(shortPara);
            if (targetPos === -1) return "";
        }
        var startExtractPos = targetPos + currentParagraph.length;
        var remainLen = fullChapter.length - startExtractPos;
        if (remainLen <= 0) return "";
        return fullChapter.substring(startExtractPos, startExtractPos + Math.min(BELOW_EXTRACT_LENGTH, remainLen));
    } catch (e) { log("【下文】提取失败：" + e.message); return ""; }
}


// ===================== 上文历史管理 =====================
function readParagraphHistory() {
    try {
        var content = java.readExternalFile(HISTORY_FILE);
        content = String(content);
        if (!content || content.trim() === "") return [];
        var arr = JSON.parse(content);
        return Array.isArray(arr) ? arr : [];
    } catch (e) { return []; }
}
function saveParagraphHistory(para) {
    try {
        if (!para || para.trim() === "") return;
        var arr = readParagraphHistory();
        arr.push(para);
        if (arr.length > MAX_HISTORY) arr.shift();
        java.writeExternalFile(HISTORY_FILE, JSON.stringify(arr));
    } catch (e) {}
}


// ===================== 给当前段落+下文统一加序号（提取自朗读规则2.js generateBatchSeqContent 核心逻辑）=====================
function generateBatchSeqContent(currentParagraph, dialogs, belowContent) {
    // 第一步：清理当前段落的每段对话（去掉旧序号），用引号包起来后拼接
    var cleanedDialogues = "";
    for (var i = 0; i < dialogs.length; i++) {
        var dialogText = dialogs[i].content || "";
        // 清理可能已有的旧序号
        dialogText = dialogText.replace(/^【\d+】/, "");
        // 直接使用中文双引号字符
        cleanedDialogues += "“" + dialogText + "”\n";
    }

    // 第二步：清理下文（去掉旧序号）
    var cleanedBelow = (belowContent || "").replace(/【\d+】/g, "");

    // 第三步：拼接得到完整原始文本（对话 + 下文）
    var fullRawText = cleanedDialogues + cleanedBelow;

    // 第四步：清理特殊字符
    fullRawText = fullRawText.replace(/【\d\d?】/g, "");
    fullRawText = fullRawText.replace(/[『「【〈〉〔'']/g, "");

    // 第五步：计算要加序号的引号数量（≤5全加，>5按比例）
    var lqReg = /“/g;
    var allLeftQuotes = fullRawText.match(lqReg);
    var totalQuoteCount = allLeftQuotes ? allLeftQuotes.length : 0;
    var stopAddIndex;
    if (totalQuoteCount <= 5) {
        stopAddIndex = totalQuoteCount;
    } else {
        stopAddIndex = Math.max(Math.floor(totalQuoteCount * 0.9), 1);
    }

    // 第六步：替换左引号，按规则加序号
    var seqCounter = 0;
    var lqRegG = /“/g;
    var finalContentWithSeq = fullRawText.replace(lqRegG, function(match) {
        seqCounter++;
        if (seqCounter <= stopAddIndex) {
            return "【" + padZero(seqCounter, 2) + "】" + match;
        }
        return match;
    });

    return finalContentWithSeq;
}


// ===================== AI分析Prompt（提取自朗读规则2.js 姓名分析prompt）=====================
function buildAnalyzePrompt() {
    return "你是喜马拉雅听书软件中智能朗读功能的人声分配AI，任务是精准判断小说手稿中所有带【01】【02】序号标记的对话的说话人，每个序号对应一段对话。\n\n" +
        "你要具备下面的能力，中文小说说话人识别（专业名称为「对话归因/说话人归属识别」），核心是将小说中的对话精准匹配到对应人物：\n" +
        "1. 指代消解能力：人称代词（他/她）、身份代称（门主/师兄）、昵称与本名的精准对应，是该任务的核心难点，直接决定复杂场景的准确率；\n" +
        "2. 隐式对话识别能力：无“XX说/道”等明确提示词的连续对话，能否通过上下文语境、人物交替逻辑正确归因；\n" +
        "3. 中文小说语料适配度：对网文叙事习惯、对话格式、神态动作绑定话术的熟悉程度，避免把旁白和对话混淆、动作发出者与说话人错位；\n" +
        "4. 多人对话追踪能力：3人以上交叉对话的逻辑链维护，避免连续对话中出现说话人错位。\n" +
        "**【核心原则 - 最高优先级】**\n" +
        "1. 严禁将对话双引号“”**内部**提及的人名当作说话人，双引号内名字是「说话者谈论的其他人」，除非是本人自我介绍；\n" +
        "2. 示例：`张伟说：“别提了，都是为了王明那个项目。”` 中，说话人是**张伟**，绝非王明。\n" +
        "3. 连续对话中，说话人通常交替出现，若某角色连续多句对话，需检查是否有明确提示词（如“他接着说”）或上下文支持，避免错归为同一人。\n" +
        "**【输出要求】**\n" +
        "1. 分析文本中所有带【01】【02】【03】...序号标记的对话，每个序号对应一个结果，序号和对话一一对应，不能错位；\n" +
        "2. 返回严格的JSON格式，key为对话的序号（如'01'、'02'，必须和文本里的序号完全一致），value为对应角色信息；\n" +
        "3. 如果无法确定说话人姓名，就用前后对这个人的描述作为名字，如果连描述也没有，就根据性别年龄填写“群众男青年”“群众男中年”“群众男老年”“群众男童”“群众少女”“群众女青年”“群众女中年”“群众女老年”“群众女童”“系统”其中的一个；\n" +
        "4. 必须包含文本中所有序号的对话结果，不能遗漏、不能多返回、不能少返回。\n" +
        "5. 输出前，请仔细核对每个序号对应的对话内容与上下文，确保说话人归属无误；如遇歧义，优先选择上下文中最合理的角色，并避免因序号相邻而误判。\n" +
        "输出格式示例：\n" +
        "{\n" +
        "  \"01\": {\n" +
        "    \"name\": \"分析出的说话人姓名\",\n" +
        "    \"gender\": \"性别（男/女/特殊）\",\n" +
        "    \"age\": \"年龄分类（女性：女童/少女/女青年/女中年/女老年）；（男性：男童/少年/男青年/男中年/男老年）；（特殊：系统/旁白）\"\n" +
        "  },\n" +
        "  \"02\": {\n" +
        "    \"name\": \"分析出的说话人姓名\",\n" +
        "    \"gender\": \"性别（男/女/特殊）\",\n" +
        "    \"age\": \"年龄分类（女性：女童/少女/女青年/女中年/女老年）；（男性：男童/少年/男青年/男中年/男老年）；（系统：系统/旁白）\"\n" +
        "  }\n" +
        "}\n";
}


// ===================== 主逻辑（并发重试版）=====================
(function() {
    var originalText = text;

    // 1. 提取当前段落中的对话
    var dialogs = extractDialogs(originalText);
    if (dialogs.length === 0) {
        saveParagraphHistory(originalText);
        return text;
    }

    log("【脚本】当前段落共 " + dialogs.length + " 段对话");

    // 2. 逐个对话尝试缓存匹配
    var allMatched = true;
    var matchedMap = {};
    for (var i = 0; i < dialogs.length; i++) {
        var match = matchDialogFromCache(dialogs[i].content);
        if (match) {
            matchedMap[padZero(i + 1, 2)] = match;
        } else {
            allMatched = false;
            break;
        }
    }

    // 3. 全部命中缓存 → 跳过AI
    if (allMatched) {
        log("【缓存】全部命中，跳过AI分析");
        for (var i = 0; i < dialogs.length; i++) {
            var seq = padZero(i + 1, 2);
            var mr = matchedMap[seq];
            log("  [" + seq + "] " + mr.name + " (" + mr.gender + "/" + mr.age + ")");
        }
        saveParagraphHistory(originalText);
        return text;
    }

    // 4. 有未匹配的 → 获取下文 + 上文 → 构建带序号的完整文本 → 并发调AI
    var belowContent = getBelowContent(originalText);
    log("【下文】提取 " + (belowContent ? belowContent.length : 0) + " 字");

    var aboveContext = readParagraphHistory().join("\n");
    log("【上文】历史 " + readParagraphHistory().length + " 段");

    var seqContent = generateBatchSeqContent(originalText, dialogs, belowContent);
    log("【序号】生成带序号的完整文本，长度 " + seqContent.length + " 字");

    // ---------- 并发竞速请求（使用 java.ajaxRace，Kotlin 层并发，绕过 Rhino Thread 限制）----------
    var analyzeResult = null;

    var userContent = "";
    if (aboveContext && aboveContext.trim() !== "") {
        userContent += "【上文历史内容】\n" + aboveContext + "\n";
    }
    userContent += "【当前待分析对话内容】\n" + seqContent;

    // 构建多个 API 配置的请求数组（每个配置用各自的 model）
    var requestList = [];
    var configs = readApiConfigs();
    if (configs.length === 0) configs = [DEFAULT_API_CONFIG];

    // 按 MAX_RETRY 次数构建请求列表，配置不足时轮询复用。
    // 即使只配了1个API，也会重复发MAX_RETRY次，实现超时后继续发送的效果。
    for (var i = 0; i < MAX_RETRY; i++) {
        var cfg = configs[i % configs.length];
        var requestBody = JSON.stringify({
            model: cfg.model,
            messages: [
                { role: "system", content: buildAnalyzePrompt() },
                { role: "user", content: userContent }
            ],
            temperature: 0.1
        });
        requestList.push(JSON.stringify({
            url: cfg.endpoint,
            method: "POST",
            body: requestBody,
            headers: {
                "Content-Type": "application/json",
                "Authorization": "Bearer " + cfg.key
            }
            // timeout: 已弃用；ajaxRaceDelayed 内部忽略底层超时，请求会一直活着直到外层 cancel
        }));
    }

    log("【AI】阶梯并发 " + requestList.length + " 个请求，间隔 " + RACE_DELAY + "ms...");

    // 调用 Kotlin 层的 ajaxRaceDelayed：逐个延迟启动，保留已启动的请求继续运行
    // 第1个请求立即启动，8秒后启动第2个，16秒后启动第3个...
    // 谁先成功返回谁的结果，其余自动取消
    var raceBody = java.ajaxRaceDelayed(requestList, RACE_DELAY);

    if (raceBody) {
        try {
            var resJson = JSON.parse(raceBody);
            if (resJson.choices && resJson.choices[0] && resJson.choices[0].message) {
                var content = resJson.choices[0].message.content.trim();
                var jsonStart = content.indexOf("{");
                var jsonEnd = content.lastIndexOf("}");
                if (jsonStart !== -1 && jsonEnd !== -1) {
                    var result = JSON.parse(content.substring(jsonStart, jsonEnd + 1));
                    // 校验
                    var valid = true;
                    for (var key in result) {
                        if (result.hasOwnProperty(key)) {
                            var item = result[key];
                            if (!item || !item.name || !item.gender || !item.age) {
                                valid = false;
                                break;
                            }
                        }
                    }
                    if (valid) {
                        analyzeResult = result;
                        log("【AI】阶梯并发成功，" + Object.keys(result).length + " 个结果");
                    }
                }
            }
        } catch (e) {
            log("【AI】解析阶梯并发结果失败：" + e.message);
        }
    }

    if (!analyzeResult) {
        log("【失败】所有阶梯并发请求均失败");
    }
    // ---------- 阶梯并发请求结束 ----------

    // 5. 保存分析结果到对话缓存
    if (analyzeResult) {
        var dialogList = [];
        var seqReg = /【(\d{2})】[\s\S]*?(“)([\s\S]*?)(”)/g;
        var seqMatch;
        while ((seqMatch = seqReg.exec(seqContent)) !== null) {
            var seqN = seqMatch[1];
            var rawDialog = seqMatch[3];
            var itemResult = analyzeResult[seqN] || { name: "未知", gender: "男", age: "青年" };
            dialogList.push({
                seq: seqN,
                dialogContent: rawDialog,
                name: itemResult.name,
                gender: itemResult.gender,
                age: itemResult.age
            });
        }

        var newCache = {
            currentIndex: 1,
            dialogList: dialogList
        };
        writeDialogCache(newCache);

        log("【保存】" + dialogList.length + " 条对话结果写入 dialog_cache.json");
        for (var i = 0; i < dialogList.length; i++) {
            var d = dialogList[i];
            log("  [" + d.seq + "] " + d.name + " (" + d.gender + "/" + d.age + ")");
        }
    } else {
        log("【失败】所有并发请求均失败");
    }

    // 6. 保存段落到上文历史
    saveParagraphHistory(originalText);

    // 7. 返回原文
    return text;
})()