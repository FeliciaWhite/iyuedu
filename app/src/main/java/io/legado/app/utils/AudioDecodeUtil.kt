package io.legado.app.utils

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import io.legado.app.constant.AppLog
import io.legado.app.help.audio.Sonic
import io.legado.app.help.audiobook.PostAudioParams
import io.legado.app.help.config.AppConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

/**
 * 音频解码与重采样工具。
 * 将任意格式音频（MP3/AAC/WAV/OGG/FLAC 等）解码为 PCM，
 * 重采样为统一参数（24000Hz, 16bit, 单声道），输出标准 WAV 文件。
 */
object AudioDecodeUtil {

    private const val TAG = "AudioDecodeUtil"
    private const val TARGET_SAMPLE_RATE = 24000
    private const val TARGET_CHANNELS = 1
    private const val TARGET_BITS = 16

    /**
     * 将任意格式的音频 ByteArray 解码并重采样为标准 WAV PCM。
     *
     * @param audioData 原始音频字节数据
     * @return 标准 WAV 文件的 ByteArray，失败返回 null
     */
    fun decodeToStandardWav(audioData: ByteArray, gain: Float = 1.0f, postParams: PostAudioParams? = null): ByteArray? {
        if (audioData.isEmpty()) {
            AppLog.put("$TAG 输入音频数据为空")
            Log.d(TAG, "输入音频数据为空")
            return null
        }

        // 先检测是否为 WAV 格式
        val head = audioData.copyOfRange(0, audioData.size.coerceAtMost(16))
        val isWav = audioData.size >= 12 &&
                String(audioData.copyOfRange(0, 4)) == "RIFF" &&
                String(audioData.copyOfRange(8, 12)) == "WAVE"
        Log.d(TAG, "decodeToStandardWav 输入大小=${audioData.size}, 是WAV=$isWav, 头16字节=${head.joinToString(" ") { "%02X".format(it) }}")

        return try {
            var pcmResult: Triple<ByteArray, Int, Int>? = null
            if (isWav) {
                pcmResult = extractWavPcm(audioData)
                if (pcmResult == null) {
                    AppLog.put("$TAG WAV 解析失败，尝试 MediaCodec 解码")
                    Log.d(TAG, "WAV 解析失败，尝试 MediaCodec 解码")
                    pcmResult = decodeViaMediaCodec(audioData)
                }
            } else {
                pcmResult = decodeViaMediaCodec(audioData)
            }

            if (pcmResult == null) {
                AppLog.put("$TAG 解码失败，无法获取 PCM 数据")
                Log.e(TAG, "解码失败，无法获取 PCM 数据")
                return null
            }

            val (pcm, srcRate, srcChannels) = pcmResult
            AppLog.put("$TAG 解码成功: 采样率=${srcRate}Hz, 声道数=$srcChannels, PCM大小=${pcm.size}")
            Log.d(TAG, "解码成功: 采样率=${srcRate}Hz, 声道数=$srcChannels, PCM大小=${pcm.size}")

            // 重采样到目标参数
            val resampled = if (srcRate == TARGET_SAMPLE_RATE && srcChannels == TARGET_CHANNELS) {
                pcm
            } else {
                AppLog.put("$TAG 需要重采样: $srcRate/$srcChannels -> $TARGET_SAMPLE_RATE/$TARGET_CHANNELS")
                Log.d(TAG, "需要重采样: $srcRate/$srcChannels -> $TARGET_SAMPLE_RATE/$TARGET_CHANNELS")
                resamplePcm(pcm, srcRate, TARGET_SAMPLE_RATE, srcChannels, TARGET_CHANNELS)
            }

            // Sonic 后处理（语速/音量/音高，三者独立）
            val processed = if (postParams != null && !postParams.isDefault()) {
                applySonicProcessing(resampled, TARGET_SAMPLE_RATE, postParams)
            } else {
                resampled
            }

            val gain = AppConfig.convertCacheToWavGain
            val amplified = if (gain != 1.0f) applyVolumeGain(processed, gain) else processed
            writeWavHeaderAndData(amplified, TARGET_SAMPLE_RATE, TARGET_CHANNELS, TARGET_BITS)
        } catch (e: Exception) {
            AppLog.put("$TAG 解码并重采样失败: ${e.message}", e)
            Log.e(TAG, "解码并重采样失败", e)
            null
        }
    }

    /**
     * 将多段音频统一解码、重采样后合并为一个 WAV 文件。
     */
    fun mergeSegmentsToWav(segments: List<ByteArray>, gain: Float = 1.0f, postParams: PostAudioParams? = null): ByteArray? {
        if (segments.isEmpty()) {
            AppLog.put("$TAG mergeSegmentsToWav 输入为空")
            Log.d(TAG, "mergeSegmentsToWav 输入为空")
            return null
        }

        AppLog.put("$TAG 开始合并 ${segments.size} 段音频")
        Log.d(TAG, "开始合并 ${segments.size} 段音频")
        val allPcm = mutableListOf<ByteArray>()
        try {
            segments.forEachIndexed { index, segment ->
                AppLog.put("$TAG 处理第 $index 段, 大小=${segment.size}")
                Log.d(TAG, "处理第 $index 段, 大小=${segment.size}")
                val wavBytes = decodeToStandardWav(segment)
                if (wavBytes == null) {
                    AppLog.put("$TAG 第 $index 段解码失败，跳过")
                    Log.e(TAG, "第 $index 段解码失败，跳过")
                    return@forEachIndexed
                }
                AppLog.put("$TAG 第 $index 段解码成功, WAV大小=${wavBytes.size}")
                Log.d(TAG, "第 $index 段解码成功, WAV大小=${wavBytes.size}")
                // 去掉 WAV 头，取 PCM 数据
                val pcm = extractPcmFromWav(wavBytes)
                if (pcm != null) {
                    AppLog.put("$TAG 第 $index 段提取PCM成功, PCM大小=${pcm.size}")
                    Log.d(TAG, "第 $index 段提取PCM成功, PCM大小=${pcm.size}")
                    allPcm.add(pcm)
                } else {
                    AppLog.put("$TAG 第 $index 段提取PCM失败")
                    Log.e(TAG, "第 $index 段提取PCM失败")
                }
            }

            if (allPcm.isEmpty()) {
                AppLog.put("$TAG 所有段解码/提取均失败")
                Log.e(TAG, "所有段解码/提取均失败")
                return null
            }

            val totalSize = allPcm.sumOf { it.size }
            AppLog.put("$TAG 合并PCM: 共 ${allPcm.size} 段, 总大小=$totalSize")
            Log.d(TAG, "合并PCM: 共 ${allPcm.size} 段, 总大小=$totalSize")
            val mergedPcm = ByteArray(totalSize)
            var offset = 0
            allPcm.forEach {
                System.arraycopy(it, 0, mergedPcm, offset, it.size)
                offset += it.size
            }

            // Sonic 后处理（语速/音量/音高，三者独立）
            val processed = if (postParams != null && !postParams.isDefault()) {
                applySonicProcessing(mergedPcm, TARGET_SAMPLE_RATE, postParams)
            } else {
                mergedPcm
            }

            val gain = AppConfig.convertCacheToWavGain
            val amplified = if (gain != 1.0f) applyVolumeGain(processed, gain) else processed
            return writeWavHeaderAndData(amplified, TARGET_SAMPLE_RATE, TARGET_CHANNELS, TARGET_BITS)
        } catch (e: Exception) {
            AppLog.put("$TAG 合并段失败: ${e.message}", e)
            Log.e(TAG, "合并段失败", e)
            return null
        }
    }

    // ========== 内部方法 ==========

    /**
     * 对 16bit 小端 PCM 数据应用音量增益。
     * 每个采样点乘以 gain，并裁剪到 [-32768, 32767] 防止爆音。
     * gain 为 NaN 或 1.0 时不做处理直接返回原数据。
     */
    private fun applyVolumeGain(pcm: ByteArray, gain: Float): ByteArray {
        if (gain.isNaN() || gain == 1.0f) return pcm
        val effectiveGain = gain.coerceAtLeast(0.0f)
        if (effectiveGain == 0.0f) {
            // 静音
            return ByteArray(pcm.size)
        }
        val out = ByteArray(pcm.size)
        var i = 0
        while (i + 1 < pcm.size) {
            val lo = pcm[i].toInt() and 0xFF
            val hi = pcm[i + 1].toInt()
            var sample = lo or (hi shl 8)
            if (sample > 32767) sample -= 65536
            val amplified = (sample * effectiveGain).toInt().coerceIn(-32768, 32767)
            out[i] = (amplified and 0xFF).toByte()
            out[i + 1] = ((amplified shr 8) and 0xFF).toByte()
            i += 2
        }
        return out
    }

    /**
     * 使用 Sonic 算法对 PCM 进行后处理（语速/音量/音高三者独立调节）。
     * 输入和输出都是 16bit 小端 PCM，单声道。
     */
    private fun applySonicProcessing(pcm: ByteArray, sampleRate: Int, params: PostAudioParams): ByteArray {
        val resolved = params.resolved()
        if (resolved.isDefault()) return pcm

        val numSamples = pcm.size / 2
        val sonic = Sonic(sampleRate, TARGET_CHANNELS)
        sonic.setSpeed(resolved.speed)
        sonic.setVolume(resolved.volume)
        sonic.setPitch(resolved.pitch)
        sonic.setRate(1.0f)

        // 输入 short 数组
        val inputSamples = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            var s = lo or (hi shl 8)
            if (s > 32767) s -= 65536
            inputSamples[i] = s.toShort()
        }

        sonic.writeShortToStream(inputSamples, numSamples)
        sonic.flushStream()

        // 输出
        val maxOutSamples = (numSamples / resolved.speed.coerceAtLeast(0.01f)).toInt() + 1024
        val outputSamples = ShortArray(maxOutSamples)
        val outLen = sonic.readShortFromStream(outputSamples, maxOutSamples)

        val result = ByteArray(outLen * 2)
        for (i in 0 until outLen) {
            val s = outputSamples[i].toInt()
            result[i * 2] = (s and 0xFF).toByte()
            result[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return result
    }

    /** 从 WAV ByteArray 中提取 PCM 数据 */
    private fun extractWavPcm(data: ByteArray): Triple<ByteArray, Int, Int>? {
        return try {
            val riff = String(data.copyOfRange(0, 4))
            val wave = String(data.copyOfRange(8, 12))
            if (riff != "RIFF" || wave != "WAVE") return null

            var pos = 12
            var sampleRate = 0
            var numChannels = 0
            var bitsPerSample = 0
            var dataOffset = 0
            var dataSize = 0
            var audioFormat = 1
            var fmtChunkSize = 0

            while (pos < data.size - 8) {
                val chunkId = String(data.copyOfRange(pos, pos + 4))
                val chunkSize = readUint32Le(data, pos + 4)
                pos += 8

                when (chunkId) {
                    "fmt " -> {
                        fmtChunkSize = chunkSize
                        if (pos + 16 <= data.size) {
                            audioFormat = readUint16Le(data, pos)
                            numChannels = readUint16Le(data, pos + 2)
                            sampleRate = readUint32Le(data, pos + 4)
                            bitsPerSample = readUint16Le(data, pos + 14)
                        }
                    }
                    "data" -> {
                        dataOffset = pos
                        dataSize = chunkSize
                        break
                    }
                }
                // WAV chunk 数据长度必须是偶数，奇数时补一个 pad 字节
                pos += chunkSize + (chunkSize and 1)
            }

            if (sampleRate == 0 || dataSize == 0 || dataOffset == 0) {
                AppLog.put("$TAG WAV 解析失败: fmt/data chunk 缺失")
                Log.d(TAG, "WAV 解析失败: fmt/data chunk 缺失")
                return null
            }

            // dataSize 可能大于实际数据（某些 WAV 生成工具声明值偏大），取实际可用大小
            val actualSize = dataSize.coerceAtMost(data.size - dataOffset)
            if (actualSize <= 0) {
                AppLog.put("$TAG WAV 解析失败: data chunk 无实际数据")
                Log.d(TAG, "WAV 解析失败: data chunk 无实际数据")
                return null
            }

            // 【关键修复】支持 WAVEFORMATEXTENSIBLE (格式码 0xFFFE)
            // 很多现代 TTS 引擎返回的是 EXTENSIBLE 格式
            val isFloat: Boolean
            val effectiveFormat: Int
            if (audioFormat == 0xFFFE && fmtChunkSize >= 40 && pos >= 40 + 12) {
                // 读取 SubFormat GUID 的前 4 字节（小端）
                val guidLo = readUint32Le(data, 12 + 8 + 24) // fmt chunk 内偏移 24
                effectiveFormat = guidLo
                isFloat = (guidLo == 3)
                Log.d(TAG, "检测到 WAVEFORMATEXTENSIBLE, SubFormat GUID 低4字节=$guidLo, 按 ${if (isFloat) "float" else "PCM"} 处理")
            } else {
                effectiveFormat = audioFormat
                isFloat = audioFormat == 3
            }

            // 支持 PCM (1)、float (3)、EXTENSIBLE(底层为PCM/float)；其他回退到 MediaCodec
            if (effectiveFormat != 1 && effectiveFormat != 3) {
                AppLog.put("$TAG WAV 格式码不支持: effective=$effectiveFormat (original=$audioFormat)，回退到 MediaCodec")
                Log.d(TAG, "WAV 格式码不支持: effective=$effectiveFormat (original=$audioFormat)")
                return null
            }

            val pcmData = when {
                bitsPerSample == 8 -> {
                    val pcm8 = data.copyOfRange(dataOffset, dataOffset + actualSize)
                    val pcm16 = ByteArray(pcm8.size * 2)
                    for (i in pcm8.indices) {
                        val sample = ((pcm8[i].toInt() and 0xFF) - 128) shl 8
                        pcm16[i * 2] = (sample and 0xFF).toByte()
                        pcm16[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
                    }
                    pcm16
                }
                bitsPerSample == 16 && !isFloat -> {
                    data.copyOfRange(dataOffset, dataOffset + actualSize)
                }
                bitsPerSample == 24 -> {
                    val pcm24 = data.copyOfRange(dataOffset, dataOffset + actualSize)
                    val samples = pcm24.size / 3
                    val pcm16 = ByteArray(samples * 2)
                    for (i in 0 until samples) {
                        val b0 = pcm24[i * 3].toInt() and 0xFF
                        val b1 = pcm24[i * 3 + 1].toInt() and 0xFF
                        val b2 = pcm24[i * 3 + 2].toInt() and 0xFF
                        val value = b0 or (b1 shl 8) or (b2 shl 16)
                        val signed = if (value > 0x7FFFFF) value - 0x1000000 else value
                        val scaled = (signed shr 8).coerceIn(-32768, 32767)
                        pcm16[i * 2] = (scaled and 0xFF).toByte()
                        pcm16[i * 2 + 1] = ((scaled shr 8) and 0xFF).toByte()
                    }
                    pcm16
                }
                bitsPerSample == 32 -> {
                    val pcm32 = data.copyOfRange(dataOffset, dataOffset + actualSize)
                    val samples = pcm32.size / 4
                    val pcm16 = ByteArray(samples * 2)
                    val bb = java.nio.ByteBuffer.wrap(pcm32).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until samples) {
                        val intBits = bb.getInt(i * 4)
                        val intVal = if (isFloat) {
                            (java.lang.Float.intBitsToFloat(intBits) * 32767f).toInt()
                        } else {
                            (intBits shr 16).coerceIn(-32768, 32767)
                        }
                        val scaled = intVal.coerceIn(-32768, 32767)
                        pcm16[i * 2] = (scaled and 0xFF).toByte()
                        pcm16[i * 2 + 1] = ((scaled shr 8) and 0xFF).toByte()
                    }
                    pcm16
                }
                else -> {
                    AppLog.put("$TAG 不支持的 WAV 位深: $bitsPerSample, 回退到 MediaCodec")
                    Log.d(TAG, "不支持的 WAV 位深: $bitsPerSample")
                    return null
                }
            }

            AppLog.put("$TAG WAV 解析成功: ${sampleRate}Hz, $numChannels ch, $bitsPerSample bit, PCM=${pcmData.size}")
            Log.d(TAG, "WAV 解析成功: ${sampleRate}Hz, $numChannels ch, $bitsPerSample bit, PCM=${pcmData.size}")
            Triple(pcmData, sampleRate, numChannels)
        } catch (e: Exception) {
            AppLog.put("$TAG extractWavPcm 异常: ${e.message}", e)
            Log.e(TAG, "extractWavPcm 异常", e)
            null
        }
    }

    /** 使用 MediaExtractor + MediaCodec 解码任意格式音频 */
    private fun decodeViaMediaCodec(audioData: ByteArray): Triple<ByteArray, Int, Int>? {
        val ext = detectAudioExtension(audioData)
        val tempFile = File.createTempFile("audio_decode", ext)
        tempFile.writeBytes(audioData)
        try {
            return decodeFileViaMediaCodec(tempFile)
        } finally {
            tempFile.delete()
        }
    }

    /**
     * 根据音频数据前几个字节检测格式，返回正确的扩展名，
     * 帮助 MediaExtractor 正确识别格式。
     */
    private fun detectAudioExtension(audioData: ByteArray): String {
        if (audioData.size < 4) return ".tmp"
        val b0 = audioData[0].toInt() and 0xFF
        val b1 = audioData[1].toInt() and 0xFF
        val b2 = audioData[2].toInt() and 0xFF
        val b3 = audioData[3].toInt() and 0xFF
        return when {
            // WAV
            String(audioData.copyOfRange(0, 4)) == "RIFF" -> ".wav"
            // FLAC
            String(audioData.copyOfRange(0, 4)) == "fLaC" -> ".flac"
            // OGG
            String(audioData.copyOfRange(0, 4)) == "OggS" -> ".ogg"
            // MP3 (ID3 tag)
            b0 == 0x49 && b1 == 0x44 && b2 == 0x33 -> ".mp3"
            // MP3 ( MPEG sync word 0xFFE/0xFFF )
            b0 == 0xFF && (b1 and 0xE0) == 0xE0 -> ".mp3"
            // M4A/AAC (ftyp at offset 4)
            audioData.size >= 8 && String(audioData.copyOfRange(4, 8)) == "ftyp" -> ".m4a"
            else -> ".tmp"
        }
    }

    private fun decodeFileViaMediaCodec(file: File): Triple<ByteArray, Int, Int>? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue

                extractor.selectTrack(i)
                val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

                // 【关键修复】PCM / WAV (audio/raw) 不需要 MediaCodec 解码，直接读取
                if (mime.startsWith("audio/raw") || mime.contains("wav")) {
                    AppLog.put("$TAG 检测到 RAW PCM，直接提取: $mime, ${sampleRate}Hz, $channels ch")
                    Log.d(TAG, "MediaExtractor MIME=$mime, 直接提取 PCM")
                    val pcmChunks = mutableListOf<ByteArray>()
                    val buf = ByteArray(1024 * 1024)
                    val bb = ByteBuffer.wrap(buf)
                    while (true) {
                        val sampleSize = extractor.readSampleData(bb, 0)
                        if (sampleSize < 0) break
                        pcmChunks.add(buf.copyOfRange(0, sampleSize))
                        bb.clear()
                        extractor.advance()
                    }
                    val totalSize = pcmChunks.sumOf { it.size }
                    val pcm = ByteArray(totalSize)
                    var offset = 0
                    pcmChunks.forEach {
                        System.arraycopy(it, 0, pcm, offset, it.size)
                        offset += it.size
                    }
                    // MediaExtractor 对 PCM 可能报告位深为 0，默认按 16bit 处理
                    return Triple(pcm, sampleRate, channels)
                }

                val decoder = MediaCodec.createDecoderByType(mime)
                decoder.configure(format, null, null, 0)
                decoder.start()

                val pcmChunks = mutableListOf<ByteArray>()
                val bufferInfo = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false

                while (!outputDone) {
                    if (!inputDone) {
                        val inputIdx = decoder.dequeueInputBuffer(10000)
                        if (inputIdx >= 0) {
                            val buffer = decoder.getInputBuffer(inputIdx)!!
                            val sampleSize = extractor.readSampleData(buffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inputIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inputIdx, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outputIdx = decoder.dequeueOutputBuffer(bufferInfo, 10000)
                    when {
                        outputIdx >= 0 -> {
                            val buffer = decoder.getOutputBuffer(outputIdx)!!
                            val chunk = ByteArray(bufferInfo.size)
                            buffer.get(chunk)
                            pcmChunks.add(chunk)
                            decoder.releaseOutputBuffer(outputIdx, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                            }
                        }
                        outputIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            // 获取输出格式，通常与输入一致
                        }
                        outputIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (inputDone) {
                                Thread.sleep(5)
                            }
                        }
                    }
                }

                decoder.stop()
                decoder.release()

                val totalSize = pcmChunks.sumOf { it.size }
                val pcm = ByteArray(totalSize)
                var offset = 0
                pcmChunks.forEach {
                    System.arraycopy(it, 0, pcm, offset, it.size)
                    offset += it.size
                }

                // MediaCodec 解码出来的 PCM 是 16bit 的
                return Triple(pcm, sampleRate, channels)
            }
        } catch (e: Exception) {
            AppLog.put("$TAG MediaCodec 解码异常: ${e.message}", e)
        } finally {
            extractor.release()
        }
        return null
    }

    /** 重采样：线性插值，16bit PCM */
    private fun resamplePcm(
        pcm: ByteArray,
        srcRate: Int, dstRate: Int,
        srcChannels: Int, dstChannels: Int
    ): ByteArray {
        val srcSamples = pcm.size / 2 / srcChannels
        val ratio = srcRate.toDouble() / dstRate.toDouble()
        val dstSamples = (srcSamples / ratio).toInt()

        val result = ByteArray(dstSamples * 2 * dstChannels)
        var dstIdx = 0

        for (i in 0 until dstSamples) {
            val srcPos = i * ratio
            val srcIdx = srcPos.toInt()
            val frac = srcPos - srcIdx
            val srcIdxNext = (srcIdx + 1).coerceAtMost(srcSamples - 1)

            // 取所有声道的平均值（混音为单声道）
            val sample0 = getSampleMono(pcm, srcIdx, srcChannels)
            val sample1 = getSampleMono(pcm, srcIdxNext, srcChannels)
            val interpolated = (sample0 * (1 - frac) + sample1 * frac).toInt().coerceIn(-32768, 32767)

            // 写入 16bit 小端
            result[dstIdx++] = (interpolated and 0xFF).toByte()
            result[dstIdx++] = ((interpolated shr 8) and 0xFF).toByte()

            // 如果目标是双声道，重复写入
            if (dstChannels == 2) {
                result[dstIdx++] = (interpolated and 0xFF).toByte()
                result[dstIdx++] = ((interpolated shr 8) and 0xFF).toByte()
            }
        }

        return result
    }

    /** 从多声道 PCM 中取一个采样点（混音为单声道） */
    private fun getSampleMono(pcm: ByteArray, sampleIndex: Int, channels: Int): Double {
        var sum = 0.0
        for (ch in 0 until channels) {
            val idx = sampleIndex * channels * 2 + ch * 2
            if (idx + 1 >= pcm.size) return 0.0
            val lo = pcm[idx].toInt() and 0xFF
            val hi = pcm[idx + 1].toInt()
            val value = lo or (hi shl 8)
            sum += if (value > 32767) (value - 65536).toDouble() else value.toDouble()
        }
        return sum / channels
    }

    /** 从 WAV 字节数组中提取 PCM 数据 */
    private fun extractPcmFromWav(wavData: ByteArray): ByteArray? {
        if (wavData.size < 44) return null
        var pos = 12
        while (pos < wavData.size - 8) {
            val chunkId = String(wavData.copyOfRange(pos, pos + 4))
            val chunkSize = readUint32Le(wavData, pos + 4)
            pos += 8
            if (chunkId == "data") {
                val end = (pos + chunkSize).coerceAtMost(wavData.size)
                return wavData.copyOfRange(pos, end)
            }
            // WAV chunk 数据长度必须是偶数，奇数时补一个 pad 字节
            pos += chunkSize + (chunkSize and 1)
        }
        return null
    }

    /** 写入标准 WAV 头 + PCM 数据 */
    private fun writeWavHeaderAndData(
        pcm: ByteArray, sampleRate: Int, channels: Int, bits: Int
    ): ByteArray {
        val byteRate = sampleRate * channels * bits / 8
        val blockAlign = channels * bits / 8
        val totalLen = 36 + pcm.size

        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray())
        out.write(intToLe(totalLen))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.write(intToLe(16))
        out.write(shortToLe(1)) // PCM = 1
        out.write(shortToLe(channels.toShort()))
        out.write(intToLe(sampleRate))
        out.write(intToLe(byteRate))
        out.write(shortToLe(blockAlign.toShort()))
        out.write(shortToLe(bits.toShort()))
        out.write("data".toByteArray())
        out.write(intToLe(pcm.size))
        out.write(pcm)
        return out.toByteArray()
    }

    private fun readUint16Le(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readUint32Le(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or
                ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun intToLe(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte()
    )

    private fun shortToLe(v: Short): ByteArray = byteArrayOf(
        (v.toInt() and 0xFF).toByte(),
        ((v.toInt() shr 8) and 0xFF).toByte()
    )
}
