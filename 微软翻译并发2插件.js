// ====================== 【新增：全局统一超时控制】 ======================
const dengdai = 6; // 全局超时时间（单位：秒），修改这里即可全局生效
const TIMEOUT_MILLIS = dengdai * 1000; // 转换为毫秒，供底层调用

// ====================== 【修复：第三方服务器添加音量参数】 ======================
let formatOptions = [
	"amr-wb-16000hz",
	"audio-16khz-32kbitrate-mono-mp3",
	"audio-16khz-64kbitrate-mono-mp3",
	"audio-16khz-128kbitrate-mono-mp3",
	"audio-24khz-48kbitrate-mono-mp3", // 索引4
	"audio-24khz-96kbitrate-mono-mp3",
	"audio-24khz-160kbitrate-mono-mp3",
	"audio-48khz-96kbitrate-mono-mp3",
	"audio-48khz-192kbitrate-mono-opus",
	"ogg-16khz-16bit-mono-opus",
	"ogg-24khz-16bit-mono-opus", // 原默认格式，索引10
	"ogg-48khz-16bit-mono-opus",
	"webm-16khz-16bit-mono-opus",
	"webm-24khz-16bit-mono-opus",
	"webm-24khz-16bit-24kbps-mono-opus",
	"riff-8khz-8bit-mono-alaw",
	"riff-8khz-8bit-mono-mulaw",
	"riff-8khz-16bit-mono-pcm",
	"riff-16khz-16bit-mono-pcm",
	"riff-22050hz-16bit-mono-pcm",
	"riff-24khz-16bit-mono-pcm",
	"riff-44100hz-16bit-mono-pcm",
	"riff-48khz-16bit-mono-pcm"
]
function getSampleRateFromFormat(format) {
	if (format.includes('opus')) {
		return 24000
	}
	let match = format.match(/(\d+)khz|(\d+)hz/)
	return match[1] ? parseInt(match[1]) * 1000 : parseInt(match[2])
}
// 【新增：锁定配置文件路径】
const LOCK_CONFIG_FILE = "server_lock_config.json"
// 【新增：读取锁定配置函数】
function readLockConfig() {
	try {
		if (ttsrv.fileExist(LOCK_CONFIG_FILE)) {
			let configStr = ttsrv.readTxtFile(LOCK_CONFIG_FILE)
			return JSON.parse(configStr)
		}
	} catch (e) {
		// 文件读取失败，使用默认配置
	}
	
	// 默认配置
	return {
		lockServer: false,
		lockedServerName: "官方-东亚"
	}
}
// 【新增：保存锁定配置函数】
function saveLockConfig(config) {
	try {
		let configStr = JSON.stringify(config)
		ttsrv.writeTxtFile(LOCK_CONFIG_FILE, configStr)
		return true
	} catch (e) {
		return false
	}
}
// ====================== 【新增：服务器并发配置】 ======================
// 【新增：并发配置文件路径】
const ROTATION_CONFIG_FILE = "server_rotation_config.json"
// 【新增：读取并发配置函数】
function readRotationConfig() {
	try {
		if (ttsrv.fileExist(ROTATION_CONFIG_FILE)) {
			let configStr = ttsrv.readTxtFile(ROTATION_CONFIG_FILE)
			return JSON.parse(configStr)
		}
	} catch (e) {
		// 文件读取失败，使用默认配置
	}
	
	// 默认配置：不启用并发
	return {
		enableRotation: false,
		rotationServers: [],
		currentRotationIndex: 0
	}
}
// 【新增：保存并发配置函数】
function saveRotationConfig(config) {
	try {
		let configStr = JSON.stringify(config)
		ttsrv.writeTxtFile(ROTATION_CONFIG_FILE, configStr)
		return true
	} catch (e) {
		return false
	}
}
// 【新增：获取下一个服务器（兼容原配置）】
function getNextRotationServer(rotationConfig) {
	if (!rotationConfig.enableRotation || rotationConfig.rotationServers.length === 0) {
		return null
	}
	
	let index = rotationConfig.currentRotationIndex
	let serverName = rotationConfig.rotationServers[index]
	
	// 更新索引，为下一次使用准备
	rotationConfig.currentRotationIndex = (index + 1) % rotationConfig.rotationServers.length
	saveRotationConfig(rotationConfig)
	
	return getServerByName(serverName)
}
// 【新增：显示并发服务器选择对话框】
function showRotationServerDialog(ctx) {
	let rotationConfig = readRotationConfig()
	let serverNames = combinedServers.map(server => server.name)
	let checkedItems = new Array(serverNames.length).fill(false)
	
	// 设置已选中的服务器
	if (rotationConfig.rotationServers && rotationConfig.rotationServers.length > 0) {
		for (let i = 0; i < serverNames.length; i++) {
			if (rotationConfig.rotationServers.includes(serverNames[i])) {
				checkedItems[i] = true
			}
		}
	}
	
	// 创建多选对话框
	let builder = new android.app.AlertDialog.Builder(ctx)
	builder.setTitle("选择并发服务器")
	builder.setMultiChoiceItems(serverNames, checkedItems, new android.content.DialogInterface.OnMultiChoiceClickListener({
		onClick: function(dialog, which, isChecked) {
			checkedItems[which] = isChecked
		}
	}))
	
	builder.setPositiveButton("保存", new android.content.DialogInterface.OnClickListener({
		onClick: function(dialog, which) {
			let selectedServers = []
			for (let i = 0; i < checkedItems.length; i++) {
				if (checkedItems[i]) {
					selectedServers.push(serverNames[i])
				}
			}
			
			// 保存并发配置
			let newConfig = {
				enableRotation: selectedServers.length > 0,
				rotationServers: selectedServers,
				currentRotationIndex: 0
			}
			saveRotationConfig(newConfig)
		}
	}))
	
	builder.setNegativeButton("取消", null)
	builder.setNeutralButton("不并发", new android.content.DialogInterface.OnClickListener({
		onClick: function(dialog, which) {
			// 清空并发配置
			let newConfig = {
				enableRotation: false,
				rotationServers: [],
				currentRotationIndex: 0
			}
			saveRotationConfig(newConfig)
		}
	}))
	
	builder.show()
}
// ====================== 【修改：Edge TTS API 服务器处理函数 - 统一使用24000采样率】 ======================
// Edge TTS API 服务器处理函数
function getAudioInternalEdgeAPI(text, voice, rate, volume, pitch, serverConfig) {
    // 直接使用传入的voice参数，不进行映射
    let apiVoice = voice
    
    // 转换语速参数格式 (假设系统传入50-100，50为正常值)
    let rateParam = "+0%"
    if (rate !== 50) {
        // 将50-100的范围转换为百分比变化
        // 50为正常(0%)，100为最快(+100%)，0为最慢(-100%)
        let rateChange = Math.round((rate - 50) * 2) // 扩大变化范围
        rateParam = (rateChange >= 0 ? "+" : "") + rateChange + "%"
    }
    
    // 转换音量参数格式 (假设系统传入0-100)
    let volumeParam = "+50%"
    if (volume !== 100) {
        let volumeChange = Math.round(volume - 100) + 10
        volumeParam = (volumeChange >= 0 ? "+" : "") + volumeChange + "%"
    }
    
    try {
        // 构建请求URL
        let apiUrl = serverConfig.serverUrl + "/run/predict"
        
        // 构建请求数据 - 与Edge TTS API格式匹配
        let requestData = {
            "fn_index": 0,
            "data": [
                text,           // 要转换的文本
                apiVoice,       // 语音模型
                rateParam,      // 语速调整
                volumeParam     // 音量调整
            ]
        }
        
        // 设置请求头
        let headers = {
            "Content-Type": "application/json",
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        }
        
        // 发送POST请求 - 恢复原格式，删除超时参数
        let response = ttsrv.httpPost(apiUrl, JSON.stringify(requestData), headers)
        
        // 检查响应状态
        if (response.code() !== 200) {
            throw "Edge TTS API请求失败，状态码: " + response.code() + ", 响应: " + response.body().string()
        }
        
        // 解析响应
        let responseText = response.body().string()
        let responseData
        try {
            responseData = JSON.parse(responseText)
        } catch (e) {
            throw "Edge TTS API返回无效JSON: " + responseText.substring(0, 100) + "..."
        }
        
        // 检查响应结构
        if (!responseData.data || responseData.data.length === 0) {
            throw "Edge TTS API响应格式不正确: " + JSON.stringify(responseData)
        }
        
        // 获取音频信息
        let audioInfo = responseData.data[0]
        
        // 检查音频数据格式
        let audioUrl
        if (audioInfo && audioInfo.url) {
            audioUrl = audioInfo.url
        } else if (audioInfo && audioInfo.path) {
            audioUrl = serverConfig.serverUrl + "/file=" + audioInfo.path
        } else {
            throw "Edge TTS API响应中没有有效的音频数据: " + JSON.stringify(responseData)
        }
        
        // 下载音频文件 - 恢复原格式，删除超时参数
        let audioResponse = ttsrv.httpGet(audioUrl, headers)
        
        if (audioResponse.code() !== 200) {
            throw "无法从Edge TTS API获取音频文件: " + audioResponse.code()
        }
        
        // 返回字节流
        return audioResponse.body().byteStream()
        
    } catch (e) {
        throw "Edge TTS API请求失败: " + e.toString()
    }
}
// ====================== 【新增：旺旺TTS处理函数 - 添加风格支持】 ======================
function getAudioInternalWangwang(text, voice, rate, volume, pitch, serverConfig, style) {
    try {
        // 旺旺TTS接口参数格式适配 - 新增风格参数
        let requestBody = JSON.stringify({
            input: text,
            voice: voice,
            speed: (rate + 100)/100,  // 转换为旺旺要求的0.5-2.0速度范围
            pitch: "0",
            style: style === "default" ? "general" : style
        });
        
        // 设置请求头
        let headers = {
            "Content-Type": "application/json"
        };
        
        // 发送POST请求 - 恢复原格式，删除超时参数
        let response = ttsrv.httpPost(serverConfig.serverUrl, requestBody, headers);
        
        if (response.code() !== 200) {
            throw "旺旺TTS请求失败，状态码: " + response.code();
        }
        
        // 返回音频字节流
        return response.body().byteStream();
        
    } catch (e) {
        throw "旺旺TTS请求失败: " + e.toString();
    }
}
let PluginJS = {
	name: "微软翻译【并发2】",
	id: "com.microsoft.translator",
	author: "XY50",
	iconUrl: "http://www.microsoft.com/favicon.ico",
	version: 25, // 完全恢复原版本号
	getAudio: function(text, locale, voice, rate, volume, pitch) {
    
        text = text.replace(/[＜＞<>＆&]/g, "");
		rate = rate - 50
		pitch = pitch - 50
		volume = volume + 10
		
		// 读取配置
		let lockConfig = readLockConfig()
		let rotationConfig = readRotationConfig()
		let useLockedServer = lockConfig.lockServer
		let currentServerName
		let currentServer
		
		// 基础服务器选择逻辑（兼容原配置）
		if (rotationConfig.enableRotation && rotationConfig.rotationServers.length > 0) {
			currentServer = getNextRotationServer(rotationConfig)
			if (!currentServer) {
				throw "并发服务器配置错误"
			}
		} else if (useLockedServer) {
			// 使用锁定的服务器
			currentServerName = lockConfig.lockedServerName || "官方-东亚"
			currentServer = getServerByName(currentServerName)
		} else {
			// 使用系统提交的服务器
			currentServerName = String(ttsrv.tts.data['currentServerName'] || "官方-东亚")
			currentServer = getServerByName(currentServerName)
		}
		
		// 确保所有配置数据都是基本类型
		let styleDegree = parseFloat(ttsrv.tts.data['styleDegree'] || "1.0")
		let format = String(ttsrv.tts.data['format'] || formatOptions[4])
		let style = String(ttsrv.tts.data['style'] || "default")
		let role = String(ttsrv.tts.data['role'] || "default")
		let langSkill = String(ttsrv.tts.data['languageSkill'] || "")
		
		// ====================== 核心：并发竞态逻辑（全局统一超时+原子操作解决竞态） ======================
		if (rotationConfig.enableRotation && rotationConfig.rotationServers.length > 1) {
			let servers = rotationConfig.rotationServers
			let errors = []
			// 1. 原子操作变量：解决多线程竞态，保证只有一个服务器能成功（重命名避免作用域冲突）
			let rotationIsSuccess = new java.util.concurrent.atomic.AtomicBoolean(false);
			let rotationSuccessAudio = new java.util.concurrent.atomic.AtomicReference(null);
			// 初始化计数器，值为并发服务器数量（重命名避免作用域冲突）
			let rotationCountDownLatch = new java.util.concurrent.CountDownLatch(servers.length);
			// 封装：单服务器合成逻辑（复用原插件所有合成规则）
			const synthesizeServer = function(tryServer, serverName) {
				try {
					// 原子检测：已有成功服务器，直接终止当前线程
					if (rotationIsSuccess.get()) {
						rotationCountDownLatch.countDown();
						return;
					}
					let audioStream;
					// 按服务器类型执行合成逻辑
					if (tryServer.type === "proxy") {
						let tryStyle = style === "default" ? "general" : style
						let encodedText = encodeURIComponent(text)
						let proxyVolume = volume + 50
						let proxyUrl = `${tryServer.config.serverUrl}?t=${encodedText}&v=${voice}&r=${rate}&p=${pitch}&s=${tryStyle}&vol=${proxyVolume}`
						audioStream = getAudioInternalProxy(proxyUrl)
					} else if (tryServer.type === "official") {
						let textSsml = ''
						if (langSkill === "" || langSkill == null) {
							textSsml = specialCharReplace(text)
						} else {
							textSsml = `<lang xml:lang="${langSkill}">${specialCharReplace(text)}</lang>`
						}
						let ssml = `<speak xmlns="http://www.w3.org/2001/10/synthesis" xmlns:mstts="http://www.w3.org/2001/mstts" xmlns:emo="http://www.w3.org/2009/10/emotionml" version="1.0" xml:lang="zh-CN">
								<voice name="${voice}">
									<mstts:express-as style="${style}" styledegree="${styleDegree}" role="${role}">
										<prosody rate="${rate}%" pitch="${pitch}%" volume="${volume}">${textSsml}</prosody>
									</mstts:express-as>
								</voice>
							</speak>`
						let selectedRegion = tryServer.config.region || "eastasia"
						audioStream = getAudioInternal(ssml, format, selectedRegion)
					} else if (tryServer.type === "edge-api") {
						// Edge TTS API 原参数规则
						audioStream = getAudioInternalEdgeAPI(text, voice, rate + 50, volume + 0, pitch + 50, tryServer.config)
					} else if (tryServer.type === "wangwang") {
						// 旺旺TTS 风格转换规则
						let tryStyle = style === "default" ? "general" : style
						audioStream = getAudioInternalWangwang(text, voice, rate, volume, pitch, tryServer.config, tryStyle)
					} else {
						throw "未支持的服务器类型"
					}
					// 原子抢占：CAS操作，仅第一个线程能将状态置为true并存储音频
					if (rotationIsSuccess.compareAndSet(false, true)) {
						rotationSuccessAudio.set(audioStream);
						console.log(`服务器【${serverName}】合成成功，抢占返回音频`);
						// 成功后强制将计数器归0，主线程立即唤醒，不等待其他阻塞线程
						while(rotationCountDownLatch.getCount() > 0) rotationCountDownLatch.countDown();
					}
				} catch (e) {
					// 单个服务器失败，仅记录错误
					errors.push(`服务器 ${serverName} 失败: ${e}`);
				} finally {
					// 无论成功/失败，计数器-1，保证主线程不阻塞
					rotationCountDownLatch.countDown();
				}
			};
			// 为每个选中的服务器创建独立并发线程
			for (let i = 0; i < servers.length; i++) {
				// IIFE捕获索引和服务器名，解决JS循环闭包陷阱
				(function(index, srvName) {
					let tryServer = getServerByName(srvName);
					let thread = new java.lang.Thread(function() {
						synthesizeServer(tryServer, srvName);
					});
					thread.start();
				})(i, servers[i]);
			}
			// 主线程等待：全局统一超时控制（重命名避免重复声明）
			const waitRotationSuccess = rotationCountDownLatch.await(TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS);
			if (!waitRotationSuccess) {
				errors.push("所有服务器合成超时（" + dengdai + "秒）");
			}
			// 原子获取最终状态和音频，判断返回结果
			if (rotationIsSuccess.get() && rotationSuccessAudio.get() !== null) {
				return rotationSuccessAudio.get();
			} else {
				throw "所有并发服务器合成失败:\n" + errors.join("\n");
			}
		} else {
			// ====================== 单服务器/锁定服务器模式（新增全局超时控制） ======================
			// 原子操作变量：解决线程安全，存储合成结果和错误信息（重命名避免作用域冲突）
			let singleIsSuccess = new java.util.concurrent.atomic.AtomicBoolean(false);
			let singleSuccessAudio = new java.util.concurrent.atomic.AtomicReference(null);
			let singleErrorMsg = new java.util.concurrent.atomic.AtomicReference("未知错误");
			// 计数器：单线程合成，初始值1（重命名避免作用域冲突）
			let singleCountDownLatch = new java.util.concurrent.CountDownLatch(1);

			// 封装原有合成逻辑（完全保留原规则，无任何功能改动）
			const synthesizeSingleServer = function() {
				try {
					let audioStream;
					// 原服务器类型判断逻辑100%保留
					if (currentServer.type === "proxy") {
						let tryStyle = style === "default" ? "general" : style
						let encodedText = encodeURIComponent(text)
						let proxyVolume = volume + 50
						let proxyUrl = `${currentServer.config.serverUrl}?t=${encodedText}&v=${voice}&r=${rate}&p=${pitch}&s=${tryStyle}&vol=${proxyVolume}`
						audioStream = getAudioInternalProxy(proxyUrl)
					} else if (currentServer.type === "official") {
						let textSsml = ''
						if (langSkill === "" || langSkill == null) {
							textSsml = specialCharReplace(text)
						} else {
							textSsml = `<lang xml:lang="${langSkill}">${specialCharReplace(text)}</lang>`
						}
						let ssml = `<speak xmlns="http://www.w3.org/2001/10/synthesis" xmlns:mstts="http://www.w3.org/2001/mstts" xmlns:emo="http://www.w3.org/2009/10/emotionml" version="1.0" xml:lang="zh-CN">
								<voice name="${voice}">
									<mstts:express-as style="${style}" styledegree="${styleDegree}" role="${role}">
										<prosody rate="${rate}%" pitch="${pitch}%" volume="${volume}">${textSsml}</prosody>
									</mstts:express-as>
								</voice>
							</speak>`
						let selectedRegion = currentServer.config.region || "eastasia"
						ttsrv.tts.data['selectedRegion'] = selectedRegion
						audioStream = getAudioInternal(ssml, format, selectedRegion)
					} else if (currentServer.type === "edge-api") {
						audioStream = getAudioInternalEdgeAPI(text, voice, rate + 50, volume + 0, pitch + 50, currentServer.config)
					} else if (currentServer.type === "wangwang") {
						let tryStyle = style === "default" ? "general" : style
						audioStream = getAudioInternalWangwang(text, voice, rate, volume, pitch, currentServer.config, tryStyle)
					} else {
						throw "未支持的服务器类型"
					}
					// 合成成功，标记状态并存储音频流
					singleIsSuccess.set(true);
					singleSuccessAudio.set(audioStream);
				} catch (e) {
					// 合成失败，存储错误信息
					singleErrorMsg.set(e.toString());
				} finally {
					// 无论成功/失败，计数器减1，唤醒主线程
					singleCountDownLatch.countDown();
				}
			};

			// 启动合成子线程，不阻塞主线程
			let thread = new java.lang.Thread(function() {
				synthesizeSingleServer();
			});
			thread.start();

			// 主线程等待：全局统一超时控制（重命名避免重复声明）
			const waitSingleSuccess = singleCountDownLatch.await(TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS);
			if (!waitSingleSuccess) {
				throw "单服务器合成超时（" + dengdai + "秒）";
			}

			// 结果返回：成功返回音频流，失败抛出错误
			if (singleIsSuccess.get() && singleSuccessAudio.get() !== null) {
				return singleSuccessAudio.get();
			} else {
				throw "单服务器合成失败: " + singleErrorMsg.get();
			}
		}
	}
}
// 【修复：通过名称获取服务器配置】
function getServerByName(serverName) {
	for (let server of combinedServers) {
		if (server.name === serverName) {
			return server
		}
	}
	return combinedServers[0] // 默认返回东亚（第一个官方服务器）
}
function specialCharReplace(s) {
	return s.replace(/['"<>&]/g, ',')
}
let ep = ""
function getAudioInternal(ssml, format, selectedRegion) {
	ep ||= getEndpoint()
	let url = "https://" + selectedRegion + ".tts.speech.microsoft.com/cognitiveservices/v1"
	let headers = {
		Authorization: ep['t'],
		"Content-Type": "application/ssml+xml",
		"X-Microsoft-OutputFormat": format
	}
	// 恢复原格式，删除超时参数
	let resp = ttsrv.httpPost(url, ssml, headers)
	if (resp.code() !== 200) {
		ep = ''
		throw "官方音频获取失败" + resp.code()
	}
	return resp.body().byteStream()
}
function getAudioInternalProxy(url) {
	let headers = {
		"User-Agent": "TTS-Client/1.0",
		"Accept": "audio/*"
	}
	// 恢复原格式，删除超时参数
	let resp = ttsrv.httpGet(url, headers)
	if (resp.code() !== 200) {
		throw "第三方音频获取失败: HTTP-" + resp.code() + ", URL: " + url
	}
	let contentType = resp.header("Content-Type")
	if (contentType && contentType.includes("audio")) {
		return resp.body().byteStream()
	} else {
		let errorText = resp.body().string()
		throw "第三方服务器返回错误: " + errorText
	}
}
let endpointUrl = "https://dev.microsofttranslator.com/apps/endpoint?api-version=1.0"
function getEndpoint() {
	let sign = getSign()
	let headers = {
		"Accept-Language": "zh-Hans",
		"X-ClientVersion": "4.0.530a 5fe1dc6c",
		"X-UserId": "0f04d16a175c411e",
		"X-HomeGeographicRegion": "zh-Hans-CN",
		"X-ClientTraceId": "aab069b9-70a7-4844-a734-96cd78d94be9",
		"X-MT-Signature": sign,
		"User-Agent": "okhttp/4.5.0",
		"Content-Type": "application/json; charset=utf-8",
		"Content-Length": "0",
		"Accept-Encoding": "gzip"
	}
	// 恢复原格式，删除超时参数
	let resp = ttsrv.httpPost(endpointUrl, "", headers)
	if (resp.code() !== 200) {
		throw "官方终结点信息获取失败" + resp.code()
	}
	let str = resp.body().string()
	return JSON.parse(str)
}
function getSign() {
	let aly = new JavaImporter(
		javax.crypto.Mac,
		javax.crypto.spec.SecretKeySpec,
		java.net.URLEncoder,
		java.lang.String,
		java.text.SimpleDateFormat,
		java.util.Locale,
		java.util.TimeZone,
		android.util.Base64
	)
	with(aly) {
		function percentEncode(value) {
			return URLEncoder.encode(value, 'UTF-8')
		}
		function dateFormat() {
			let simpleDateFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss", Locale.US)
			simpleDateFormat.setTimeZone(TimeZone.getTimeZone("GMT"))
			return simpleDateFormat.format(new Date()).toLowerCase() + "GMT"
		}
		function sign(url) {
			url = url.split('://')[1]
			let encodeUrl = percentEncode(url)
			let uuid = ttsrv.randomUUID().replaceAll("-", "")
			let formattedDate = dateFormat()
			let bytes = String.format("%s%s%s%s", "MSTranslatorAndroidApp", encodeUrl, formattedDate, uuid).toLowerCase().getBytes('UTF-8')
			let secretKeySpec = new SecretKeySpec(Base64.decode("oik6PdDdMnOXemTbwvMn9de/h9lFnfBaCWbGMMZqqoSaQaqUOqjVGm5NqsmjcBI1x+sS9ugjB55HEJWRiFXYFw==", 2), "HmacSHA256")
			var mac = Mac.getInstance('HmacSHA256')
			mac.init(secretKeySpec)
			var signData = mac.doFinal(bytes)
			var signBase64 = Base64.encodeToString(signData, Base64.NO_WRAP)
			return String.format("%s::%s::%s::%s", "MSTranslatorAndroidApp", signBase64, formattedDate, uuid)
		}
	}
	return sign(endpointUrl)
}
let endpointRegions = [
    { name: "东亚", value: "eastasia" },
    { name: "东亚2", value: "eastasia" },
    { name: "东亚3", value: "eastasia" },
    { name: "东南亚", value: "southeastasia" },
    { name: "东南亚2", value: "southeastasia" },
    { name: "东南亚3", value: "southeastasia" },
    { name: "默认", value: "" },
    { name: "默认2", value: "" },
    { name: "默认3", value: "" }
]
let voices = {}
let currentVoices = new Map()
let skillSpinner
let styleSpinner
let roleSpinner
let seekStyle
let formatSpinner
let regionSpinner
let lockServerSwitch
// 【修改：组合服务器列表，包含所有类型的服务器】
const proxyServers = [
	{ name: "中国", serverUrl: "http://t.leftsite.cn/tts" },
	{ name: "中国杭州", serverUrl: "http://60.205.243.148:8080/tts" },
	{ name: "德国法兰克福", serverUrl: "http://5.45.99.149:8075/tts" },
	{ name: "韩国首尔", serverUrl: "http://193.122.107.44:9090/tts" },
	{ name: "美国德克萨斯州", serverUrl: "http://104.214.168.83:8080/tts" },
	{ name: "美国纽约", serverUrl: "http://74.48.40.244:8010/tts" },
	{ name: "美国加利福尼亚州", serverUrl: "http://47.79.92.215:18080/tts" },
	// 新增的GET接口
	{ name: "SkyBook", serverUrl: "https://skybook.qzz.io/tts" },
	{ name: "中国湖北", serverUrl: "http://171.113.113.119:8085/tts" },
	{ name: "中国江苏", serverUrl: "http://47.119.125.172:8080/tts" },
	{ name: "美国洛杉矶", serverUrl: "http://64.112.42.45:9080/tts" },
	{ name: "荷兰阿姆斯特丹", serverUrl: "http://146.56.188.115:8080/tts" },
	{ name: "中国广东", serverUrl: "http://36.248.181.23:22335/tts" },
	{ name: "日本东京", serverUrl: "http://180.114.35.250:1080/tts" },
	{ name: "中国上海", serverUrl: "http://124.71.164.73:8085/tts" },
	{ name: "巴西圣保罗", serverUrl: "http://190.92.218.92:8080/tts" }
];
// 【修改点1：Edge数组移除旺旺，仅保留Edge自身】
const edgeApiServers = [
	{ 
		name: "Edge TTS API", 
		serverUrl: "https://zhaoshao-edge-tts.ms.show"
	}
];
// 【修改点2：调整拼接顺序，官方→旺旺→第三方→Edge】
const combinedServers = endpointRegions.map(region => ({
	type: "official",
	name: `官方-${region.name}`,
	config: { region: region.value }
}))
// 插入旺旺TTS到官方后、第三方前
.concat([{
	type: "wangwang",
	name: "旺旺-旺旺TTS",
	config: { serverUrl: "https://tts.wangwangit.com/v1/audio/speech" }
}])
// 拼接第三方服务器
.concat(proxyServers.map(server => ({
	type: "proxy",
	name: `第三方-${server.name}`,
	config: { serverUrl: server.serverUrl }
})))
// 拼接Edge API服务器
.concat(edgeApiServers.map(server => ({
	type: server.name.includes("旺旺") ? "wangwang" : "edge-api", 
	name: server.name.includes("旺旺") ? `旺旺-${server.name}` : `Edge-${server.name}`,
	config: { serverUrl: server.serverUrl }
})))
// 【修复：移除顶层 const View/ViewGroup/Switch/LinearLayout 声明】
// 原因：legado 运行时已在宿主 installPluginCompatShims 中注入同名全局变量
// （View/ViewGroup/Switch/LinearLayout 指向 Packages.android.view.* 等），
// 插件在此用 const 重新声明同名变量会触发 Rhino redefineProperty 抛
// “变量 X 被重新声明”，导致分类/编辑器加载失败。
// 宿主已注入这些短名，插件可直接使用，无需重复声明。
let EditorJS = {
    getAudioSampleRate: function(locale, voice) {
        // 读取配置，获取当前使用的服务器
        let lockConfig = readLockConfig()
        let rotationConfig = readRotationConfig()
        let currentServer
        if (rotationConfig.enableRotation && rotationConfig.rotationServers.length > 0) {
            currentServer = getServerByName(rotationConfig.rotationServers[rotationConfig.currentRotationIndex] || combinedServers[0].name)
        } else if (lockConfig.lockServer) {
            currentServer = getServerByName(lockConfig.lockedServerName || "官方-东亚")
        } else {
            currentServer = getServerByName(ttsrv.tts.data['currentServerName'] || "官方-东亚")
        }
        // 第三方/EdgeAPI/旺旺TTS服务器固定返回24000，官方服务器按格式解析采样率
        if (currentServer.type === "proxy" || currentServer.type === "edge-api" || currentServer.type === "wangwang") {
            return 24000
        } else {
            let format = ttsrv.tts.data['format'] || formatOptions[4]
            return getSampleRateFromFormat(format)
        }
    },
	getLocales: function() {
		let locales = new Array()
		voices.forEach(function(v) {
			let loc = v["Locale"]
			if (!locales.includes(loc)) {
				locales.push(loc)
			}
		})
		return locales
	},
	getVoices: function(locale) {
		currentVoices.clear()
		voices.forEach(function(v) {
			if (v['Locale'] === locale) {
				currentVoices.set(v['ShortName'], v)
			}
		})
		let mm = {}
		for (let [key, value] of currentVoices.entries()) {
			mm[key] = {
				name: value['LocalName'] + ' (' + key + ')',
				icon: value['Gender'].toLowerCase()
			}
		}
		return mm
	},
	onLoadData: function() {
		let jsonStr = ''
		if (ttsrv.fileExist('voices.json')) {
			jsonStr = ttsrv.readTxtFile('voices.json')
		} else {
			// 从指定URL下载角色列表 - 恢复原格式，删除超时参数
			let url = 'https://cnb.cool/mingwuyan/yinpin/-/git/raw/main/voices.json'
			jsonStr = ttsrv.httpGetString(url, {})
			ttsrv.writeTxtFile('voices.json', jsonStr)
		}
		voices = JSON.parse(jsonStr)
	},
	onLoadUI: function(ctx, linerLayout) {
		linerLayout.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
		
		// 读取锁定配置
		let lockConfig = readLockConfig();
		
		// 【新增：并发服务器按钮布局】
		let rotationLayout = new LinearLayout(ctx)
		rotationLayout.orientation = LinearLayout.HORIZONTAL
		let rotationParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
		rotationLayout.setLayoutParams(rotationParams)
		
		let rotationButton = new android.widget.Button(ctx)
		rotationButton.setText("并发服务器设置")
		let buttonParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1)
		rotationButton.setLayoutParams(buttonParams)
		rotationLayout.addView(rotationButton)
		
		linerLayout.addView(rotationLayout)
		ttsrv.setMargins(rotationLayout, 0, 8, 0, 0)
		
		// 【新增：并发服务器按钮点击事件】
		rotationButton.setOnClickListener(new android.view.View.OnClickListener({
			onClick: function(v) {
				showRotationServerDialog(ctx)
			}
		}))
		
		// 【原有代码：服务器锁定开关布局】
		let lockLayout = new LinearLayout(ctx)
		lockLayout.orientation = LinearLayout.HORIZONTAL
		let lockParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
		lockLayout.setLayoutParams(lockParams)
		
		let serverSpinner = JSpinner(ctx, "服务器选择")
		let spinnerParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1)
		serverSpinner.setLayoutParams(spinnerParams)
		lockLayout.addView(serverSpinner)
		
		// 使用标准 Android Switch 组件
		lockServerSwitch = new Switch(ctx)
		lockServerSwitch.setText("锁定服务器")
		let switchParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
		lockServerSwitch.setLayoutParams(switchParams)
		ttsrv.setMargins(lockServerSwitch, 16, 0, 0, 0)
		lockLayout.addView(lockServerSwitch)
		
		linerLayout.addView(lockLayout)
		ttsrv.setMargins(lockLayout, 0, 4, 0, 0)
		
		serverSpinner.items = combinedServers.map(server => Item(server.name, server))
		
		// 从缓存文件读取锁定配置
		let useLockedServer = lockConfig.lockServer
		let currentServerName
		
		if (useLockedServer) {
			currentServerName = lockConfig.lockedServerName || "官方-东亚"
		} else {
			currentServerName = String(ttsrv.tts.data['currentServerName'] || "官方-东亚")
		}
		
		let serverIndex = combinedServers.findIndex(s => s.name === currentServerName)
		if (serverIndex === -1) serverIndex = 0
		serverSpinner.selectedPosition = serverIndex
		
		// 设置锁定开关状态
		lockServerSwitch.setChecked(lockConfig.lockServer)
		
		serverSpinner.setOnItemSelected(function(spinner, pos, item) {
			let serverName = String(item.value.name)
			ttsrv.tts.data['currentServerName'] = serverName
			
			// 修复：如果选择的是官方服务器，更新selectedRegion
			if (item.value.type === "official") {
				ttsrv.tts.data['selectedRegion'] = item.value.config.region
			}
			
			// 如果锁定开启，更新缓存文件中的锁定服务器
			if (lockServerSwitch.isChecked()) {
				let newConfig = readLockConfig()
				newConfig.lockedServerName = serverName
				saveLockConfig(newConfig)
			}
			
			let serverType = String(item.value.type)
			EditorJS.onLoadData()
			EditorJS.adjustUIControls(linerLayout, layout, serverType)
		})
		
		// 使用标准 Switch 监听器
		lockServerSwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener({
			onCheckedChanged: function(buttonView, isChecked) {
				// 保存锁定状态到缓存文件
				let newConfig = readLockConfig()
				newConfig.lockServer = isChecked
				
				// 如果锁定开启，同时保存当前服务器为锁定服务器
				if (isChecked) {
					let currentServerName = String(ttsrv.tts.data['currentServerName'] || "官方-东亚")
					newConfig.lockedServerName = currentServerName
				}
				
				saveLockConfig(newConfig)
			}
		}))
		let layout = new LinearLayout(ctx)
		layout.orientation = LinearLayout.HORIZONTAL
		let params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1)
		formatSpinner = JSpinner(ctx, "音频格式")
		linerLayout.addView(formatSpinner)
		ttsrv.setMargins(formatSpinner, 0, 4, 0, 0)
		formatSpinner.items = formatOptions.map(opt => Item(opt, opt))
		let currentFormat = String(ttsrv.tts.data['format'] || formatOptions[4])
		formatSpinner.selectedPosition = formatOptions.findIndex(f => f === currentFormat)
		if (formatSpinner.selectedPosition === -1) formatSpinner.selectedPosition = 4
		formatSpinner.setOnItemSelected(function(spinner, pos, item) {
			ttsrv.tts.data['format'] = String(item.value)
		})
		skillSpinner = JSpinner(ctx, "语言技能")
		linerLayout.addView(skillSpinner)
		ttsrv.setMargins(skillSpinner, 0, 4, 0, 0)
		let currentLangSkill = String(ttsrv.tts.data['languageSkill'] || "")
		skillSpinner.selectedPosition = 0
		skillSpinner.setOnItemSelected(function(spinner, pos, item) {
			ttsrv.tts.data['languageSkill'] = String(item.value)
		})
		styleSpinner = JSpinner(ctx, "风格")
		styleSpinner.layoutParams = params
		layout.addView(styleSpinner)
		ttsrv.setMargins(styleSpinner, 0, 4, 0, 0)
		let currentStyle = String(ttsrv.tts.data['style'] || "default")
		styleSpinner.selectedPosition = 0
		styleSpinner.setOnItemSelected(function(spinner, pos, item) {
			ttsrv.tts.data['style'] = String(item.value)
			
			let currentServerName
			if (lockConfig.lockServer) {
				currentServerName = lockConfig.lockedServerName || "官方-东亚"
			} else {
				currentServerName = String(ttsrv.tts.data['currentServerName'] || "官方-东亚")
			}
			
			let currentServer = getServerByName(currentServerName)
			let currentVoiceCode = String(ttsrv.tts.data['currentVoice'] || "")
			let currentVoice = currentVoices.get(currentVoiceCode)
			let styles = currentVoice ? currentVoice['StyleList'] || [] : []
			
			if (currentServer.type === "official" && styles.length > 0) {
				seekStyle.visibility = (pos !== 0 && item.value && item.value !== "default") ? View.VISIBLE : View.GONE
			} else {
				seekStyle.visibility = View.GONE
			}
		})
		let spaceView = new View(ctx)
		let spaceParams = new LinearLayout.LayoutParams(30, LinearLayout.LayoutParams.MATCH_PARENT)
		spaceView.setLayoutParams(spaceParams)
		layout.addView(spaceView)
		roleSpinner = JSpinner(ctx, "角色")
		roleSpinner.layoutParams = params
		layout.addView(roleSpinner)
		let currentRole = String(ttsrv.tts.data['role'] || "default")
		roleSpinner.selectedPosition = 0
		ttsrv.setMargins(roleSpinner, 0, 4, 0, 0)
		roleSpinner.setOnItemSelected(function(spinner, pos, item) {
			ttsrv.tts.data['role'] = String(item.value)
		})
		linerLayout.addView(layout)
		seekStyle = JSeekBar(ctx, "风格强度")
		linerLayout.addView(seekStyle)
		ttsrv.setMargins(seekStyle, 0, 4, 0, 0)
		seekStyle.setFloatType(1)
		seekStyle.max = 20
		let styleDegree = parseFloat(ttsrv.tts.data['styleDegree'] || "1.0")
		seekStyle.value = new java.lang.Float(styleDegree)
		seekStyle.setOnChangeListener({
			onStartTrackingTouch: function(seek) {},
			onProgressChanged: function(seek, progress, fromUser) {},
			onStopTrackingTouch: function(seek) {
				ttsrv.tts.data['styleDegree'] = String(Number(seek.value).toFixed(1))
			}
		})
		// 初始化当前服务器
		if (lockConfig.lockServer) {
			currentServerName = lockConfig.lockedServerName || "官方-东亚"
		} else {
			currentServerName = String(ttsrv.tts.data['currentServerName'] || "官方-东亚")
		}
		let currentServer = getServerByName(currentServerName)
		if (currentServer.type === "official") {
			ttsrv.tts.data['selectedRegion'] = currentServer.config.region
		}
		EditorJS.adjustUIControls(linerLayout, layout, currentServer.type)
	},
	adjustUIControls: function(linerLayout, layout, serverType) {
		serverType = String(serverType)
		if (serverType === "official") {
			formatSpinner.visibility = View.VISIBLE
			skillSpinner.visibility = View.VISIBLE
			layout.visibility = View.VISIBLE
			seekStyle.visibility = View.GONE
		} else if (serverType === "proxy" || serverType === "wangwang") {
			formatSpinner.visibility = View.GONE
			skillSpinner.visibility = View.GONE
			layout.visibility = View.VISIBLE
			roleSpinner.visibility = View.GONE
			seekStyle.visibility = View.GONE
		} else if (serverType === "edge-api") {
			formatSpinner.visibility = View.GONE
			skillSpinner.visibility = View.GONE
			layout.visibility = View.GONE
			seekStyle.visibility = View.GONE
		}
	},
	onVoiceChanged: function(locale, voiceCode) {
		// 保存当前语音代码
		ttsrv.tts.data['currentVoice'] = voiceCode
		
		java.lang.Thread.sleep(50)
		let vic = currentVoices.get(voiceCode)
		if (!vic) return
		// 读取配置
		let lockConfig = readLockConfig()
		let currentServerName
		if (lockConfig.lockServer) {
			currentServerName = lockConfig.lockedServerName || "官方-东亚"
		} else {
			currentServerName = String(ttsrv.tts.data['currentServerName'] || "官方-东亚")
		}
		let currentServer = getServerByName(currentServerName)
		
		// Edge服务器不处理风格/角色
		if (currentServer.type === "edge-api") {
			skillSpinner.visibility = View.GONE
			styleSpinner.visibility = View.GONE
			roleSpinner.visibility = View.GONE
			seekStyle.visibility = View.GONE
			return
		}
		// 旺旺服务器仅显示风格
		if (currentServer.type === "wangwang") {
			skillSpinner.visibility = View.GONE
			styleSpinner.visibility = View.VISIBLE
			roleSpinner.visibility = View.GONE
			seekStyle.visibility = View.GONE
			let styleItems = [Item("默认", "default")]
			styleSpinner.items = styleItems
			styleSpinner.selectedPosition = 0
			return
		}
		// 1. 语言技能
		let locale2List = vic['SecondaryLocaleList']
		let locale2Items = []
		let locale2Pos = 0
		if (locale2List) {
			locale2Items.push(Item("默认", ""))
			locale2List.map(function(v, i) {
				let loc = java.util.Locale.forLanguageTag(v)
				let name = loc.getDisplayName(loc)
				locale2Items.push(Item(name, v))
				if (v === String(ttsrv.tts.data['languageSkill'] || '')) {
					locale2Pos = i + 1
				}
			})
		}
		skillSpinner.items = locale2Items
		skillSpinner.selectedPosition = locale2Pos
		skillSpinner.visibility = (locale2Items.length > 0 && currentServer.type === "official") ? View.VISIBLE : View.GONE
		// 2. 风格
		let styles = vic['StyleList'] || []
		let styleItems = [Item("默认", "default")]
		let stylePos = 0
		let currentStyle = String(ttsrv.tts.data['style'] || "default")
		if (styles.length > 0) {
			styles.map(function(v, i) {
				let styleName = getString(v) || v
				styleItems.push(Item(styleName, v))
				if (v === currentStyle) {
					stylePos = i + 1
				}
			})
		} else {
			currentStyle = "default"
			ttsrv.tts.data['style'] = "default"
		}
		styleSpinner.items = styleItems
		styleSpinner.selectedPosition = stylePos
		if (currentServer.type === "official" && styles.length > 0 && currentStyle !== "default") {
			seekStyle.visibility = View.VISIBLE
		} else {
			seekStyle.visibility = View.GONE
		}
		// 3. 角色
		let roles = vic['RolePlayList'] || []
		let roleItems = [Item("默认", "default")]
		let rolePos = 0
		let currentRole = String(ttsrv.tts.data['role'] || "default")
		if (roles.length > 0) {
			roles.map(function(v, i) {
				let roleName = getString(v) || v
				roleItems.push(Item(roleName, v))
				if (v === currentRole) {
					rolePos = i + 1
				}
			})
		}
		roleSpinner.items = roleItems
		roleSpinner.selectedPosition = rolePos
		roleSpinner.visibility = (currentServer.type === "official" && roles.length > 0) ? View.VISIBLE : View.GONE
	}
}
let cnLocales = {
	"narrator": "旁白",
	"girl": "女孩", 
	"boy": "男孩",
	"youngadultfemale": "青年女",
	"youngadultmale": "青年男",
	"olderadultfemale": "中年女", 
	"olderadultmale": "中年男",
	"seniorfemale": "老年女",
	"seniormale": "老年男",
	"affectionate": "亲切",
	"angry": "生气",
	"assassin": "刺客",
	"assistant": "助理", 
	"captain": "船长",
	"calm": "平静",
	"cavalier": "骑士",
	"chat": "聊天",
	"chat-casual": "闲聊", 
	"cheerful": "愉快",
	"conversation": "对话", 
	"customerservice": "客户服务",
	"depressed": "沮丧",
	"drake": "人名",
	"disgruntled": "不满", 
	"documentary-narration": "纪录片旁白",
	"embarrassed": "尴尬",
	"empathetic": "共情", 
	"envious": "羡慕",
	"excited": "兴奋",
	"fearful": "害怕", 
	"friendly": "友好",
	"funny": "有趣", 
	"gentle": "温柔",
	"gamenarrator": "游戏旁白", 
	"geomancer": "风水师",
	"hopeful": "希望", 
	"livecommercial": "直播带货",
	"lyrical": "抒情", 
	"narration-professional": "专业旁白",
	"narration-relaxed": "轻松旁白", 
	"newscast": "新闻",
	"newscast-casual": "新闻-休闲", 
	"newscast-formal": "新闻-正式",
	"poet": "诗人", 
	"poetry-reading": "诗歌朗诵",
	"relieved": "释然", 
	"sad": "悲伤",
	"serious": "严肃", 
	"sentiment": "情感",
	"shouting": "喊叫", 
	"shy": "害羞",
	"sorry": "抱歉", 
	"sports-commentary": "体育解说",
	"sports-commentary-excited": "体育解说-兴奋", 
	"story": "故事",
	"terrified": "恐惧", 
	"unfriendly": "不友好",
	"advertisement-upbeat": "广告推销", 
	"voiceassistant": "语音助手",
	"whisper": "低语", 
	"whispering": "耳语"
}
let isZh = java.util.Locale.getDefault().getLanguage() == 'zh'
const getString = key => isZh ? (cnLocales[key.toLowerCase()] || key) : key