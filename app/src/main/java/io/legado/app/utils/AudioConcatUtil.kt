package io.legado.app.utils

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import io.legado.app.constant.AppLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 音频文件合并工具类
 * 用于将多个音频片段合并成一个文件
 */
object AudioConcatUtil {

    private const val TAG = "AudioConcatUtil"

    /**
     * 合并多个音频文件到目标文件
     * 支持直接拼接二进制数据（适合同格式的音频文件）
     *
     * @param inputFiles 输入的音频文件列表（按顺序）
     * @param outputFile 输出文件
     * @return true 表示成功，false 表示失败
     */
    fun concatAudioFiles(inputFiles: List<File>, outputFile: File): Boolean {
        if (inputFiles.isEmpty()) {
            return false
        }

        // 检查输入文件是否存在
        val validFiles = inputFiles.filter { it.exists() && it.length() > 0 }
        if (validFiles.isEmpty()) {
            return false
        }

        return try {
            outputFile.parentFile?.mkdirs()

            // 检查文件是否是WAV格式
            val firstFile = validFiles.first()
            val isWav = isWavFile(firstFile)
            
            val result = if (isWav) {
                concatWavFiles(validFiles, outputFile)
            } else {
                concatRawFiles(validFiles, outputFile)
            }
            
            if (result) {
                AppLog.put("$TAG 合并完成: ${outputFile.name} (${outputFile.length() / 1024}KB)")
            }
            result
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 通过文件头判断是否是WAV文件
     */
    private fun isWavFile(file: File): Boolean {
        return try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(12)
                if (fis.read(header) != 12) return false
                val riff = String(header, 0, 4)
                val wave = String(header, 8, 4)
                riff == "RIFF" && wave == "WAVE"
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 合并 WAV 文件
     * WAV 文件需要特殊处理文件头
     */
    private fun concatWavFiles(inputFiles: List<File>, outputFile: File): Boolean {
        return try {
            // 读取第一个文件的 WAV 头信息
            val firstFile = inputFiles.first()
            val wavHeader = readWavHeader(firstFile)
            if (wavHeader == null) {
                return concatRawFiles(inputFiles, outputFile)
            }

            // 计算总音频数据长度
            var totalAudioLength = 0L
            inputFiles.forEach { file ->
                if (file.exists() && file.length() > 44) {
                    totalAudioLength += file.length() - 44
                }
            }

            // 创建输出文件并写入
            FileOutputStream(outputFile).use { fos ->
                writeWavHeader(fos, wavHeader, totalAudioLength)
                inputFiles.forEach { file ->
                    if (file.exists() && file.length() > 44) {
                        FileInputStream(file).use { fis ->
                            fis.skip(44)
                            fis.copyTo(fos)
                        }
                    }
                }
            }

            true
        } catch (_: Exception) {
            concatRawFiles(inputFiles, outputFile)
        }
    }

    /**
     * 读取 WAV 文件头信息
     * WAV头部结构:
     * 0-3: "RIFF"
     * 4-7: 文件大小 - 8
     * 8-11: "WAVE"
     * 12-15: "fmt "
     * 16-19: fmt chunk大小 (通常是16)
     * 20-21: 音频格式 (audioFormat) - 1=PCM
     * 22-23: 声道数 (numChannels)
     * 24-27: 采样率 (sampleRate)
     * 28-31: 字节率 (byteRate)
     * 32-33: 块对齐 (blockAlign)
     * 34-35: 位深度 (bitsPerSample)
     * 36-39: "data"
     * 40-43: data chunk大小
     */
    private fun readWavHeader(file: File): WavHeader? {
        return try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(44)
                if (fis.read(header) != 44) return null

                // 验证 RIFF 标识
                val riff = String(header, 0, 4)
                if (riff != "RIFF") return null

                val wave = String(header, 8, 4)
                if (wave != "WAVE") return null

                // 直接使用字节数组读取，不需要ByteBuffer
                // 20-21: audioFormat (2字节)
                val audioFormat = (header[20].toInt() and 0xFF) or ((header[21].toInt() and 0xFF) shl 8)
                // 22-23: numChannels (2字节)
                val numChannels = (header[22].toInt() and 0xFF) or ((header[23].toInt() and 0xFF) shl 8)
                // 24-27: sampleRate (4字节)
                val sampleRate = (header[24].toInt() and 0xFF) or 
                                ((header[25].toInt() and 0xFF) shl 8) or 
                                ((header[26].toInt() and 0xFF) shl 16) or 
                                ((header[27].toInt() and 0xFF) shl 24)
                // 28-31: byteRate (4字节)
                val byteRate = (header[28].toInt() and 0xFF) or 
                              ((header[29].toInt() and 0xFF) shl 8) or 
                              ((header[30].toInt() and 0xFF) shl 16) or 
                              ((header[31].toInt() and 0xFF) shl 24)
                // 32-33: blockAlign (2字节)
                val blockAlign = (header[32].toInt() and 0xFF) or ((header[33].toInt() and 0xFF) shl 8)
                // 34-35: bitsPerSample (2字节)
                val bitsPerSample = (header[34].toInt() and 0xFF) or ((header[35].toInt() and 0xFF) shl 8)

                WavHeader(
                    audioFormat = audioFormat,
                    numChannels = numChannels,
                    sampleRate = sampleRate,
                    bitsPerSample = bitsPerSample,
                    byteRate = byteRate,
                    blockAlign = blockAlign
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 写入 WAV 文件头
     */
    private fun writeWavHeader(
        fos: FileOutputStream,
        header: WavHeader,
        totalAudioLength: Long
    ) {
        val byteRate = header.sampleRate * header.numChannels * header.bitsPerSample / 8
        val blockAlign = header.numChannels * header.bitsPerSample / 8

        val buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF 头
        buffer.put("RIFF".toByteArray())
        buffer.putInt((36 + totalAudioLength).toInt()) // 文件大小 - 8
        buffer.put("WAVE".toByteArray())

        // fmt 子块
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16) // fmt 子块大小
        buffer.putShort(header.audioFormat.toShort())
        buffer.putShort(header.numChannels.toShort())
        buffer.putInt(header.sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(header.bitsPerSample.toShort())

        // data 子块
        buffer.put("data".toByteArray())
        buffer.putInt(totalAudioLength.toInt())

        fos.write(buffer.array())
        fos.flush()
    }

    /**
     * 原始二进制拼接（适用于MP3和其他格式，或WAV直接拼接作为备选）
     */
    private fun concatRawFiles(inputFiles: List<File>, outputFile: File): Boolean {
        return try {
            FileOutputStream(outputFile).use { fos ->
                inputFiles.forEach { file ->
                    if (file.exists()) {
                        FileInputStream(file).use { fis ->
                            fis.copyTo(fos)
                        }
                    }
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 获取下载文件夹路径
     * 优先使用系统 Download 文件夹
     */
    fun getDownloadFolder(bookName: String): File {
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val bookFolder = File(downloadDir, sanitizeFileName(bookName))
        if (!bookFolder.exists()) {
            bookFolder.mkdirs()
        }
        return bookFolder
    }

    /**
     * 清理文件名中的非法字符
     */
    fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
    }

    /**
     * 清理章节标题中的非法字符
     */
    fun sanitizeChapterTitle(title: String): String {
        return title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace("\n", " ")
            .trim()
    }

    /**
     * 数据类：WAV 文件头信息
     */
    private data class WavHeader(
        val audioFormat: Int,
        val numChannels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
        val byteRate: Int,
        val blockAlign: Int
    )

    /**
     * 合并多个 M4A (AAC) 文件为一个文件
     * 使用 MediaExtractor 读取每个文件，MediaMuxer 合并输出
     * @param inputFiles 输入的 M4A 文件列表（按顺序）
     * @param outputFile 输出文件
     * @param progressCallback 进度回调 (已完成数量, 总数)
     * @return true 表示成功，false 表示失败
     */
    fun concatM4aFiles(
        inputFiles: List<File>,
        outputFile: File,
        progressCallback: ((Int, Int) -> Unit)? = null
    ): Boolean {
        // 过滤有效文件
        val validFiles = inputFiles.filter { it.exists() && it.length() > 0 }
        if (validFiles.isEmpty()) {
            return false
        }
        
        // 确保输出目录存在
        outputFile.parentFile?.mkdirs()
        
        var muxer: MediaMuxer? = null
        var muxerTrackIndex = -1
        var muxerStarted = false
        var overallPresentationTimeUs = 0L
        var processedCount = 0
        
        try {
            // 创建输出文件的 Muxer
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            
            // 按顺序处理每个输入文件
            validFiles.forEachIndexed { index, inputFile ->
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(inputFile.absolutePath)
                    
                    // 查找音频轨道
                    var audioTrackIndex = -1
                    var audioFormat: MediaFormat? = null
                    for (i in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(i)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                        if (mime.startsWith("audio/")) {
                            audioTrackIndex = i
                            audioFormat = format
                            break
                        }
                    }
                    
                    if (audioTrackIndex == -1 || audioFormat == null) {
                        extractor.release()
                        return@forEachIndexed
                    }
                    
                    extractor.selectTrack(audioTrackIndex)
                    
                    // 获取文件时长（用于正确累加时间戳）
                    val fileDurationUs = audioFormat.getLong(MediaFormat.KEY_DURATION)
                    
                    // 如果是第一个文件，获取格式并添加轨道
                    if (index == 0) {
                        muxerTrackIndex = muxer.addTrack(audioFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    
                    // 读取并写入样本数据
                    val buffer = ByteBuffer.allocate(1024 * 1024)
                    val bufferInfo = MediaCodec.BufferInfo()
                    
                    while (true) {
                        buffer.clear()
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) break
                        
                        val sampleTime = extractor.sampleTime
                        val sampleFlags = extractor.sampleFlags
                        val relativeTimeUs = overallPresentationTimeUs + sampleTime
                        buffer.flip()
                        
                        bufferInfo.offset = 0
                        bufferInfo.size = sampleSize
                        bufferInfo.presentationTimeUs = relativeTimeUs
                        bufferInfo.flags = sampleFlags
                        
                        try {
                            muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                        } catch (_: Exception) {
                            extractor.advance()
                            continue
                        }
                        extractor.advance()
                    }
                    
                    overallPresentationTimeUs += fileDurationUs
                    processedCount++
                    progressCallback?.invoke(processedCount, validFiles.size)
                    
                } catch (_: Exception) {
                    // 静默跳过单文件失败
                } finally {
                    extractor.release()
                }
            }
            
            // 检查是否有成功处理的文件
            if (processedCount == 0) {
                outputFile.delete()
                return false
            }
            
            // 验证输出文件
            if (!outputFile.exists() || outputFile.length() < 1000) {
                return false
            }
            
            AppLog.put("$TAG 合并完成: ${outputFile.name} (${outputFile.length() / 1024}KB)")
            return true
            
        } catch (_: Exception) {
            if (outputFile.exists()) outputFile.delete()
            return false
        } finally {
            try {
                if (muxerStarted) muxer?.stop()
                muxer?.release()
            } catch (_: Exception) { /* 静默 */ }
        }
    }

    private const val MIX_TIMEOUT_US = 10000L

    /**
     * 裸 PCM 数据（16-bit little-endian）
     */
    data class PcmData(
        val sampleRate: Int,
        val channels: Int,
        val bytes: ByteArray
    )

    /**
     * 某段文本中需要混音的音效：音效 JSON 文件名 + 段内净字符偏移
     */
    data class SegmentEffect(
        val fileName: String,
        val charOffsetInSegment: Int
    )

    /**
     * 一段文本的信息：净字符数（用于按比例定位音效时间点）+ 该段音效列表
     */
    data class SegmentInfo(
        val textLength: Int,
        val effects: List<SegmentEffect>
    )

    /**
     * 将音频文件解码为 16-bit PCM 裸数据。
     * WAV 直接读取 data 段；MP3/AAC 等用 MediaExtractor+MediaCodec 解码。
     */
    fun decodeToPcm(file: File): PcmData? {
        if (!file.exists() || file.length() == 0L) return null
        return try {
            if (isWavFile(file)) {
                val header = readWavHeader(file) ?: return null
                val dataLen = (file.length() - 44).toInt()
                if (dataLen <= 0) return null
                val data = ByteArray(dataLen)
                FileInputStream(file).use { fis ->
                    fis.skip(44)
                    var read = 0
                    while (read < data.size) {
                        val r = fis.read(data, read, data.size - read)
                        if (r < 0) break
                        read += r
                    }
                }
                PcmData(header.sampleRate, header.numChannels, data)
            } else {
                decodeCompressedToPcm(file)
            }
        } catch (e: Exception) {
            AppLog.put("$TAG 解码失败: ${file.name}, ${e.localizedMessage}")
            null
        }
    }

    private fun decodeCompressedToPcm(file: File): PcmData? {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                trackIndex = i
                format = f
                break
            }
        }
        if (trackIndex < 0 || format == null) {
            extractor.release()
            return null
        }
        extractor.selectTrack(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)
            ?: run { extractor.release(); return null }
        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(format, null, null, 0)
        decoder.start()
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val out = ByteArrayOutputStream()
        val bufferInfo = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false
        try {
            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inId = decoder.dequeueInputBuffer(MIX_TIMEOUT_US)
                    if (inId >= 0) {
                        val inBuf = decoder.getInputBuffer(inId)
                        if (inBuf != null) {
                            val size = extractor.readSampleData(inBuf, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(
                                    inId, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                sawInputEos = true
                            } else {
                                decoder.queueInputBuffer(
                                    inId, 0, size, extractor.sampleTime, 0
                                )
                                extractor.advance()
                            }
                        }
                    }
                }
                val outId = decoder.dequeueOutputBuffer(bufferInfo, MIX_TIMEOUT_US)
                if (outId >= 0) {
                    val outBuf = decoder.getOutputBuffer(outId)
                    if (outBuf != null && bufferInfo.size > 0) {
                        val chunk = ByteArray(bufferInfo.size)
                        outBuf.get(chunk)
                        out.write(chunk)
                    }
                    decoder.releaseOutputBuffer(outId, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEos = true
                    }
                } else if (outId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // 音频解码输出为 PCM，无需处理
                }
            }
        } finally {
            decoder.stop()
            decoder.release()
            extractor.release()
        }
        if (out.size() == 0) return null
        return PcmData(sampleRate, channels, out.toByteArray())
    }

    /**
     * 将 PCM 重采样到目标采样率/声道数（线性插值 + 声道映射）。
     */
    fun resamplePcm(src: PcmData, dstRate: Int, dstChannels: Int): PcmData {
        if (src.sampleRate == dstRate && src.channels == dstChannels) return src
        val srcFrameCount = src.bytes.size / (src.channels * 2)
        if (srcFrameCount <= 0) return PcmData(dstRate, dstChannels, ByteArray(0))
        val ratio = src.sampleRate.toDouble() / dstRate
        val dstFrameCount = ((srcFrameCount / ratio).toInt()).coerceAtLeast(1)
        val dst = ByteArray(dstFrameCount * dstChannels * 2)
        val srcBuf = ByteBuffer.wrap(src.bytes).order(ByteOrder.LITTLE_ENDIAN)
        val dstBuf = ByteBuffer.wrap(dst).order(ByteOrder.LITTLE_ENDIAN)
        for (f in 0 until dstFrameCount) {
            val srcPosF = f * ratio
            val i0 = srcPosF.toInt().coerceIn(0, srcFrameCount - 1)
            val i1 = (i0 + 1).coerceIn(0, srcFrameCount - 1)
            val frac = (srcPosF - i0).toFloat()
            for (c in 0 until dstChannels) {
                val srcC = if (c < src.channels) c else src.channels - 1
                val v0 = srcBuf.getShort((i0 * src.channels + srcC) * 2).toInt()
                val v1 = srcBuf.getShort((i1 * src.channels + srcC) * 2).toInt()
                val v = (v0 + (v1 - v0) * frac).toInt().coerceIn(-32768, 32767)
                dstBuf.putShort((f * dstChannels + c) * 2, v.toShort())
            }
        }
        return PcmData(dstRate, dstChannels, dst)
    }

    /**
     * 将音效 PCM 叠加到已落盘 WAV 正文的指定帧偏移处（加法 + 限幅）。
     * 只读取/写回受音效影响的局部区域（音效通常只有几秒，几百 KB 级），
     * 避免把整章 PCM 全量加载进内存，长章节（上万字）混音不会 OOM。
     * @param raf 已打开"rw"模式的 WAV 文件，正文从偏移 44 字节开始
     * @param baseFrames 正文总帧数
     */
    private fun mixInto(
        raf: RandomAccessFile,
        baseFrames: Int,
        baseChannels: Int,
        effect: ByteArray,
        effectChannels: Int,
        offsetFrames: Int,
        volume: Float
    ) {
        val effFrames = effect.size / (effectChannels * 2)
        val startFrame = offsetFrames.coerceAtLeast(0)
        val count = if (effFrames < baseFrames - startFrame) effFrames else baseFrames - startFrame
        if (count <= 0) return
        val frameBytes = baseChannels * 2
        val regionLen = count.toLong() * frameBytes
        if (regionLen > Int.MAX_VALUE) return
        val baseOffset = 44L + startFrame.toLong() * frameBytes
        raf.seek(baseOffset)
        val region = ByteArray(regionLen.toInt())
        raf.readFully(region)
        val baseBuf = ByteBuffer.wrap(region).order(ByteOrder.LITTLE_ENDIAN)
        val effBuf = ByteBuffer.wrap(effect).order(ByteOrder.LITTLE_ENDIAN)
        for (f in 0 until count) {
            for (c in 0 until baseChannels) {
                val effC = if (c < effectChannels) c else effectChannels - 1
                val bIdx = f * baseChannels + c
                val eIdx = f * effectChannels + effC
                val bv = baseBuf.getShort(bIdx * 2).toInt()
                val ev = (effBuf.getShort(eIdx * 2).toInt() * volume).toInt()
                val mixed = (bv + ev).coerceIn(-32768, 32767)
                baseBuf.putShort(bIdx * 2, mixed.toShort())
            }
        }
        raf.seek(baseOffset)
        raf.write(region)
    }

    /**
     * 将裸 PCM 写成 WAV 文件。
     */
    fun writePcmToWav(pcm: PcmData, outputFile: File): Boolean {
        return try {
            outputFile.parentFile?.mkdirs()
            FileOutputStream(outputFile).use { fos ->
                val sampleRate = pcm.sampleRate
                val channels = pcm.channels
                val bitsPerSample = 16
                val byteRate = sampleRate * channels * bitsPerSample / 8
                val blockAlign = channels * bitsPerSample / 8
                val totalAudioLength = pcm.bytes.size.toLong()
                val buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                buffer.put("RIFF".toByteArray())
                buffer.putInt((36 + totalAudioLength).toInt())
                buffer.put("WAVE".toByteArray())
                buffer.put("fmt ".toByteArray())
                buffer.putInt(16)
                buffer.putShort(1) // PCM
                buffer.putShort(channels.toShort())
                buffer.putInt(sampleRate)
                buffer.putInt(byteRate)
                buffer.putShort(blockAlign.toShort())
                buffer.putShort(bitsPerSample.toShort())
                buffer.put("data".toByteArray())
                buffer.putInt(totalAudioLength.toInt())
                fos.write(buffer.array())
                fos.write(pcm.bytes)
            }
            true
        } catch (e: Exception) {
            AppLog.put("$TAG 写WAV失败: ${e.localizedMessage}")
            false
        }
    }

    /**
     * 将章节缓存音频解码拼接为完整 PCM，并按文本正则提取的音效标记，
     * 将音效混音到对应时间段，最后写成完整 WAV。
     *
     * @param audioFiles 按朗读顺序的缓存音频文件（与 segments 一一对应）
     * @param segments 每段文本信息（净字符数 + 段内音效标记）
     * @param volume 音效音量 0..1
     * @param outputFile 输出 WAV 文件
     * @param effectFileProvider 根据音效文件名取解码后的音频文件
     */
    fun mixChapterWithEffects(
        audioFiles: List<File>,
        segments: List<SegmentInfo>,
        volume: Float,
        outputFile: File,
        effectFileProvider: (String) -> File?
    ): Boolean {
        if (audioFiles.isEmpty()) return false
        return try {
            outputFile.parentFile?.mkdirs()
            RandomAccessFile(outputFile, "rw").use { raf ->
                // 1. 先占位 44 字节 WAV 头，正文从偏移 44 开始流式写入，
                //    解码一段写一段，避免整章 PCM 全量驻留内存（长章节不再 OOM）
                raf.setLength(0)
                raf.write(ByteArray(44))
                val first = decodeToPcm(audioFiles.first())
                    ?: throw IllegalStateException("音频解码失败 ${audioFiles.first().name}")
                val baseRate = first.sampleRate
                val baseChannels = first.channels
                val segmentStartFrame = IntArray(audioFiles.size)
                val segmentFrameCount = IntArray(audioFiles.size)
                var curFrame = 0
                audioFiles.forEachIndexed { i, f ->
                    val pcm = if (i == 0) first else
                        decodeToPcm(f) ?: throw IllegalStateException("音频解码失败 ${f.name}")
                    val res = resamplePcm(pcm, baseRate, baseChannels)
                    segmentStartFrame[i] = curFrame
                    val frames = res.bytes.size / (baseChannels * 2)
                    segmentFrameCount[i] = frames
                    curFrame += frames
                    raf.write(res.bytes)
                }
                val baseFrames = curFrame
                // 2. 计算每个音效的绝对帧偏移并混音（只读写音效影响的局部区域）
                // 顺序叠加：若本音效与上一个已放置的音效在时间上重叠，则延后到上一个播放完毕之后，
                // 与播放流程「队列顺序播放」行为保持一致，避免重叠时两个音效同时出声。
                var lastEffectEndFrame = 0
                segments.forEachIndexed { i, seg ->
                    if (i >= audioFiles.size) return@forEachIndexed
                    val segFrameCount = segmentFrameCount[i]
                    seg.effects.forEach { eff ->
                        val innerRatio = if (seg.textLength > 0)
                            eff.charOffsetInSegment.toFloat() / seg.textLength else 0f
                        val innerFrame = (innerRatio * segFrameCount).toInt()
                        var absFrame = segmentStartFrame[i] + innerFrame
                        val effFile = effectFileProvider(eff.fileName) ?: return@forEach
                        val effPcm = decodeToPcm(effFile) ?: return@forEach
                        val effResampled = resamplePcm(effPcm, baseRate, baseChannels)
                        val effFrames = effResampled.bytes.size / (baseChannels * 2)
                        // 与上一个音效重叠则延后到其结束之后
                        if (absFrame < lastEffectEndFrame) {
                            absFrame = lastEffectEndFrame
                        }
                        mixInto(raf, baseFrames, baseChannels, effResampled.bytes, baseChannels, absFrame, volume)
                        val endFrame = absFrame + effFrames
                        if (endFrame > lastEffectEndFrame) lastEffectEndFrame = endFrame
                    }
                }
                // 3. 回填 WAV 头
                val totalAudioLength = baseFrames.toLong() * baseChannels * 2
                raf.seek(0)
                writeWavHeaderTo(raf, baseRate, baseChannels, totalAudioLength)
            }
            true
        } catch (e: Exception) {
            AppLog.put("$TAG 混音失败: ${e.localizedMessage}")
            try { outputFile.delete() } catch (_: Exception) {}
            false
        }
    }

    /**
     * 向已打开的 WAV 文件（RandomAccessFile）写入 44 字节标准头。
     */
    private fun writeWavHeaderTo(raf: RandomAccessFile, sampleRate: Int, channels: Int, totalAudioLength: Long) {
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())
        buffer.putInt((36 + totalAudioLength).toInt())
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1) // PCM
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(bitsPerSample.toShort())
        buffer.put("data".toByteArray())
        buffer.putInt(totalAudioLength.toInt())
        raf.seek(0)
        raf.write(buffer.array())
    }
}
