package io.legado.app.utils

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import io.legado.app.constant.AppLog
import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.min

/**
 * 音频格式转换工具类
 * 支持 WAV 转 AAC (M4A) 格式
 */
object AudioConvertUtil {

    private const val TAG = "AudioConvertUtil"

    /**
     * WAV 转 AAC (M4A) 格式
     */
    fun wavToAac(
        inputWav: File,
        outputAac: File,
        bitRate: Int = 128000
    ): Boolean {
        AppLog.put("================== AudioConvertUtil 开始 ==================")
        AppLog.put("$TAG wavToAac 被调用，输入文件: ${inputWav.absolutePath}")
        AppLog.put("$TAG 输出文件: ${outputAac.absolutePath}")

        if (!inputWav.exists()) {
            AppLog.put("$TAG 输入文件不存在: ${inputWav.absolutePath}")
            return false
        }

        return try {
            // 读取 WAV 头信息
            val wavInfo = parseWavFile(inputWav) ?: run {
                AppLog.put("$TAG 无法解析 WAV 文件")
                return false
            }

            AppLog.put("$TAG WAV 参数: 采样率=${wavInfo.sampleRate}, 声道数=${wavInfo.numChannels}, 位深=${wavInfo.bitsPerSample}, 数据偏移=${wavInfo.dataOffset}, 数据大小=${wavInfo.dataSize}")

            // 创建输出目录
            outputAac.parentFile?.mkdirs()

            // 直接读取 WAV 音频数据，不进行声道转换
            // AAC-LC 编码器支持单声道，直接使用原始声道数即可
            val audioData = readWavAudioData(inputWav, wavInfo)
            if (audioData == null || audioData.isEmpty()) {
                AppLog.put("$TAG 无法读取 WAV 音频数据")
                return false
            }

            AppLog.put("$TAG 声道数: ${wavInfo.numChannels}, 音频数据: ${audioData.size} 字节")

            // 使用 MediaCodec 编码为 AAC，保持原始声道数
            encodeWavToAac(inputWav, outputAac, wavInfo, wavInfo.numChannels, bitRate, audioData)
        } catch (e: Exception) {
            AppLog.put("$TAG WAV 转 AAC 失败: ${e.message}", e)
            false
        }
    }

    /**
     * WAV 转 MP3 格式（使用 AAC 编码）
     */
    fun wavToMp3(
        inputWav: File,
        outputMp3: File,
        bitRate: Int = 64000
    ): Boolean {
        AppLog.put("$TAG wavToMp3 被调用")
        AppLog.put("$TAG 注意：Android 原生不支持 MP3 编码，将使用 AAC 编码替代")
        AppLog.put("$TAG 目标比特率: ${bitRate / 1000}kbps")

        val tempAac = File(outputMp3.parentFile, "${outputMp3.nameWithoutExtension}.m4a")
        val result = wavToAac(inputWav, tempAac, bitRate)

        if (result && tempAac.exists() && tempAac.length() > 0) {
            if (outputMp3.exists()) {
                outputMp3.delete()
            }
            val renamed = tempAac.renameTo(outputMp3)
            AppLog.put("$TAG 重命名结果: $renamed, 文件大小: ${outputMp3.length() / 1024} KB")
            return renamed
        } else {
            AppLog.put("$TAG 转换失败或输出文件为空")
            return false
        }
    }

    /**
     * WAV 文件信息
     */
    private data class WavInfo(
        val sampleRate: Int,
        val numChannels: Int,
        val bitsPerSample: Int,
        val dataOffset: Long,
        val dataSize: Long
    )

    /**
     * 正确解析 WAV 文件头部
     */
    private fun parseWavFile(file: File): WavInfo? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                // 读取 RIFF header
                val riff = ByteArray(4)
                raf.read(riff)
                if (String(riff) != "RIFF") {
                    AppLog.put("$TAG 不是有效的 RIFF 文件")
                    return null
                }

                // 跳过文件大小 (4 bytes)
                raf.skipBytes(4)

                // 读取 WAVE
                val wave = ByteArray(4)
                raf.read(wave)
                if (String(wave) != "WAVE") {
                    AppLog.put("$TAG 不是有效的 WAVE 文件")
                    return null
                }

                var sampleRate = 0
                var numChannels = 0
                var bitsPerSample = 0
                var dataOffset = 0L
                var dataSize = 0L

                // 搜索 fmt 和 data chunk
                while (raf.filePointer < raf.length() - 8) {
                    val chunkId = ByteArray(4)
                    raf.read(chunkId)
                    val chunkSize = readUint32(raf)

                    when (String(chunkId)) {
                        "fmt " -> {
                            // 读取 fmt 数据
                            val fmtData = ByteArray(chunkSize.coerceAtMost(16))
                            raf.read(fmtData)
                            if (fmtData.size >= 14) {
                                val audioFormat = (fmtData[0].toInt() and 0xFF) or ((fmtData[1].toInt() and 0xFF) shl 8)
                                numChannels = (fmtData[2].toInt() and 0xFF) or ((fmtData[3].toInt() and 0xFF) shl 8)
                                sampleRate = (fmtData[4].toInt() and 0xFF) or
                                        ((fmtData[5].toInt() and 0xFF) shl 8) or
                                        ((fmtData[6].toInt() and 0xFF) shl 16) or
                                        ((fmtData[7].toInt() and 0xFF) shl 24)
                                bitsPerSample = (fmtData[14].toInt() and 0xFF) or ((fmtData[15].toInt() and 0xFF) shl 8)
                                AppLog.put("$TAG 音频格式: $audioFormat, 声道数: $numChannels, 采样率: $sampleRate, 位深: $bitsPerSample")
                            }
                            // 如果 fmt chunk 大于 16 字节，需要跳过多余的字节
                            if (chunkSize > 16) {
                                raf.skipBytes((chunkSize - 16).toInt())
                            }
                        }
                        "data" -> {
                            dataOffset = raf.filePointer
                            dataSize = chunkSize.toLong()
                            AppLog.put("$TAG 找到 data chunk, 偏移: $dataOffset, 大小: $dataSize")
                            break
                        }
                        else -> {
                            // 跳过其他 chunk
                            raf.skipBytes(chunkSize.toInt())
                        }
                    }
                }

                if (sampleRate == 0 || dataSize == 0L) {
                    AppLog.put("$TAG 解析 WAV 头信息不完整")
                    return null
                }

                WavInfo(sampleRate, numChannels, bitsPerSample, dataOffset, dataSize)
            }
        } catch (e: Exception) {
            AppLog.put("$TAG 解析 WAV 文件异常: ${e.message}", e)
            null
        }
    }

    private fun readUint32(raf: RandomAccessFile): Int {
        val b0 = raf.readByte().toInt() and 0xFF
        val b1 = raf.readByte().toInt() and 0xFF
        val b2 = raf.readByte().toInt() and 0xFF
        val b3 = raf.readByte().toInt() and 0xFF
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    /**
     * 使用 MediaCodec 将 WAV 编码为 AAC
     * @param inputWav 原始WAV文件
     * @param outputAac 输出AAC文件
     * @param wavInfo WAV文件信息
     * @param channels 实际传给编码器的声道数（与wavInfo.numChannels一致）
     * @param bitRate 比特率
     * @param audioData 音频数据（与原始WAV声道数一致）
     */
    private fun encodeWavToAac(
        inputWav: File,
        outputAac: File,
        wavInfo: WavInfo,
        channels: Int,
        bitRate: Int,
        audioData: ByteArray
    ): Boolean {
        // 计算每帧的字节数 = 采样率 * 声道数 * 位深/8
        val bytesPerFrame = wavInfo.sampleRate * channels * (wavInfo.bitsPerSample / 8)
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var trackIndex = -1
        var muxerStarted = false

        try {
            // 创建 AAC 编码器
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, wavInfo.sampleRate, channels)
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, audioData.size.coerceAtMost(65536))

            AppLog.put("$TAG 创建编码器, format: $format")

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            muxer = MediaMuxer(outputAac.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            AppLog.put("$TAG 音频数据: ${audioData.size} 字节, 采样率: ${wavInfo.sampleRate}, 声道: $channels")

            // 编码处理
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var offset = 0
            val bufferSize = 4096

            AppLog.put("$TAG 开始编码循环...")

            while (!outputDone) {
                // 填充输入缓冲区
                if (!inputDone) {
                    val inputBufferIndex = encoder.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = encoder.getInputBuffer(inputBufferIndex)
                        if (inputBuffer != null) {
                            inputBuffer.clear()

                            val remaining = audioData.size - offset
                            if (remaining > 0) {
                            val presentationTimeUs = (offset.toLong() * 1000000) / bytesPerFrame
                            val toCopy = min(bufferSize, remaining)
                            inputBuffer.put(audioData, offset, toCopy)
                            offset += toCopy
                            encoder.queueInputBuffer(inputBufferIndex, 0, toCopy, presentationTimeUs, 0)
                            } else {
                                encoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                                AppLog.put("$TAG 输入完成, 已处理 $offset 字节")
                            }
                        }
                    }
                }

                // 获取输出缓冲区
                val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                when {
                    outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = encoder.outputFormat
                        AppLog.put("$TAG 输出格式改变: $newFormat")
                        trackIndex = muxer.addTrack(newFormat)
                        muxer.start()
                        muxerStarted = true
                        AppLog.put("$TAG Muxer 已启动")
                    }
                    outputBufferIndex >= 0 -> {
                        val processed = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (bufferInfo.size > 0 && muxerStarted && !processed) {
                            val outputBuffer = encoder.getOutputBuffer(outputBufferIndex)
                            if (outputBuffer != null) {
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                            }
                        }
                        encoder.releaseOutputBuffer(outputBufferIndex, false)

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                            AppLog.put("$TAG 输出完成")
                        }
                    }
                    outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // 继续等待
                    }
                }
            }

            // 确保所有输出都被处理
            Thread.sleep(100)

            AppLog.put("$TAG AAC 编码完成: ${outputAac.absolutePath}, 大小: ${outputAac.length() / 1024} KB")
            return outputAac.exists() && outputAac.length() > 0
        } catch (e: Exception) {
            AppLog.put("$TAG AAC 编码异常: ${e.message}", e)
            e.printStackTrace()
            return false
        } finally {
            try {
                encoder?.stop()
                encoder?.release()
            } catch (e: Exception) {
                AppLog.put("$TAG 编码器释放异常: ${e.message}")
            }
            try {
                if (muxerStarted) {
                    muxer?.stop()
                }
                muxer?.release()
            } catch (e: Exception) {
                AppLog.put("$TAG Muxer 释放异常: ${e.message}")
            }
        }
    }

    /**
     * 读取 WAV 文件的音频数据
     */
    private fun readWavAudioData(file: File, wavInfo: WavInfo): ByteArray? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(wavInfo.dataOffset)
                val data = ByteArray(wavInfo.dataSize.toInt())
                val read = raf.read(data)
                AppLog.put("$TAG 读取音频数据: 请求 ${wavInfo.dataSize}, 实际读取 $read")
                if (read > 0) {
                    data.copyOf(read)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            AppLog.put("$TAG 读取音频数据失败: ${e.message}", e)
            null
        }
    }

    /**
     * 读取 WAV 文件的音频数据并转换为双声道
     * 将每个采样点重复一次（左声道=右声道）
     */
    private fun readWavAudioDataAsStereo(file: File, wavInfo: WavInfo): ByteArray? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(wavInfo.dataOffset)
                val monoData = ByteArray(wavInfo.dataSize.toInt())
                val read = raf.read(monoData)
                if (read <= 0) return null

                // 将单声道数据转换为双声道（每个16bit采样重复一次）
                val stereoData = ByteArray(read * 2)
                var stereoIdx = 0
                for (i in 0 until read step 2) {
                    // 原始16bit采样: sampleL
                    // 转换后: sampleL, sampleL (左声道=右声道)
                    stereoData[stereoIdx++] = monoData[i]
                    stereoData[stereoIdx++] = monoData[i + 1]
                    stereoData[stereoIdx++] = monoData[i]
                    stereoData[stereoIdx++] = monoData[i + 1]
                }
                AppLog.put("$TAG 单声道转双声道: ${read} -> ${stereoData.size} 字节")
                stereoData
            }
        } catch (e: Exception) {
            AppLog.put("$TAG 读取/转换音频数据失败: ${e.message}", e)
            null
        }
    }

    /**
     * 计算转换后的大约文件大小
     */
    fun estimateOutputSize(
        wavFileSize: Long,
        bitRate: Int,
        wavSampleRate: Int = 48000,
        wavBitsPerSample: Int = 16
    ): Long {
        val wavBitRate = wavSampleRate * 2 * wavBitsPerSample
        val ratio = bitRate.toFloat() / wavBitRate.toFloat()
        return (wavFileSize * ratio * 0.9).toLong()
    }

    /**
     * 并行转换多个 WAV 文件到 M4A 格式
     * @param inputFiles 输入的 WAV 文件列表（按顺序）
     * @param outputDir 输出目录
     * @param bitRate 比特率，默认 64kbps
     * @param maxThreads 最大并发线程数，默认 30
     * @param progressCallback 进度回调 (已完成数量, 总数)
     * @return 转换成功的 M4A 文件列表（按输入顺序），失败的文件会返回 null 占位
     */
    suspend fun convertWavFilesToM4aParallel(
        inputFiles: List<File>,
        outputDir: File,
        bitRate: Int = 64000,
        maxThreads: Int = 30,
        progressCallback: ((Int, Int) -> Unit)? = null
    ): List<File?> = withContext(Dispatchers.IO) {
        AppLog.put("================== 开始并行转换 WAV -> M4A ==================")
        AppLog.put("$TAG 并行转换: ${inputFiles.size} 个文件, 最大线程数: $maxThreads")
        
        // 确保输出目录存在
        outputDir.mkdirs()
        
        // 简单并发控制：使用线程安全的计数器
        val activeCount = java.util.concurrent.atomic.AtomicInteger(0)
        val completedCount = java.util.concurrent.atomic.AtomicInteger(0)
        
        // 转换任务
        suspend fun convertOne(index: Int, inputFile: File): File? {
            // 等待有可用槽位
            while (activeCount.get() >= maxThreads) {
                delay(50)
            }
            
            activeCount.incrementAndGet()
            val result: File? = try {
                if (!inputFile.exists()) {
                    AppLog.put("$TAG [${index + 1}/${inputFiles.size}] 文件不存在: ${inputFile.name}")
                    null
                } else {
                    AppLog.put("$TAG [${index + 1}/${inputFiles.size}] 开始转换: ${inputFile.name}, 当前活跃线程: ${activeCount.get()}")
                    val outputFile = File(outputDir, "temp_${String.format("%06d", index)}_${inputFile.nameWithoutExtension}.m4a")
                    val success = wavToAac(inputFile, outputFile, bitRate)
                    
                    completedCount.incrementAndGet()
                    progressCallback?.invoke(completedCount.get(), inputFiles.size)
                    
                    if (success && outputFile.exists()) {
                        AppLog.put("$TAG [${index + 1}/${inputFiles.size}] 转换成功: ${outputFile.name}, 大小: ${outputFile.length() / 1024} KB")
                        outputFile
                    } else {
                        AppLog.put("$TAG [${index + 1}/${inputFiles.size}] 转换失败: ${inputFile.name}")
                        null
                    }
                }
            } catch (e: Exception) {
                AppLog.put("$TAG [${index + 1}/${inputFiles.size}] 转换异常: ${e.message}")
                null
            }
            activeCount.decrementAndGet()
            return result
        }
        
        // 并行执行所有转换
        val deferreds = inputFiles.mapIndexed { index, file ->
            async(Dispatchers.IO) { convertOne(index, file) }
        }
        
        // 等待所有任务完成并收集结果
        val outputFiles = deferreds.map { it.await() }
        
        val successCount = outputFiles.count { it != null }
        AppLog.put("$TAG 并行转换完成: $successCount/${inputFiles.size} 成功")
        
        outputFiles
    }
}
