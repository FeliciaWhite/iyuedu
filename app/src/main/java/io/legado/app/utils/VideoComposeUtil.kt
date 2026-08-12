package io.legado.app.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.media.MediaCodec
import android.os.Build
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.min

/**
 * 视频合成工具：将已合并的章节音频与「已保存的 AI 静态图片」合成为 MP4。
 *
 * 设计要点：
 * - 画面全部来自 [getChapterImageTracks] 返回的已保存图片，绝不重新生图。
 * - 帧率 10fps：按固定间隔喂帧保证字幕切换流畅；画面不变时复用缓存的 YUV，渲染开销仍是"按段"粒度。
 * - 视频尺寸取第一张图片的尺寸（超过上限等比缩小并取偶），竖版图片输出竖版视频。
 * - 字幕开关开启时才把字幕画进画面底部（黑字白描边），不写独立 .srt 侧车文件。
 * - 音频轨：解码每段缓存音频 -> 统一格式 PCM -> AAC 编码 -> 与视频轨一起 mux 进 MP4。
 * - 完成后调用方负责删除独立的 WAV/M4A 音频文件，只保留 MP4。
 *
 * 依赖：复用 [AudioConcatUtil.decodeToPcm] / [AudioConcatUtil.resamplePcm] 解码音频。
 */
object VideoComposeUtil {

    private const val TAG = "VideoComposeUtil"

    /** 单张图片轨道（段落区间 + 文件） */
    data class ImageTrack(
        val file: File,
        val startPara: Int,
        val endPara: Int
    )

    /** 一段画面（图片 + 字幕 + 时间区间，单位微秒） */
    private data class FrameItem(
        val imageFile: File,
        val subtitle: String,
        val startUs: Long,
        val endUs: Long
    )

    /**
     * 合成幻灯片式视频。
     *
     * @param audioFiles 按朗读顺序的章节缓存音频（与 segmentTexts 一一对应）
     * @param imageTracks 本章已保存图片轨道（段落区间对应 segment 索引）
     * @param segmentTexts 每段朗读文本，用于烧入字幕
     * @param outputFile 输出 MP4 文件
     * @param frameRate 视频帧率（默认 10fps，画面不变时复用缓存帧）
     * @param burnSubtitle 是否把字幕烧入画面（跟随"合并时保存字幕"开关）
     * @return true 表示成功写出 MP4
     */
    fun composeSlideshowVideo(
        audioFiles: List<File>,
        imageTracks: List<ImageTrack>,
        segmentTexts: List<String>,
        outputFile: File,
        frameRate: Int = 10,
        burnSubtitle: Boolean = true,
        subtitleFontSizeScale: Float = 1f,
        subtitleVOffset: Float = 0f,
        splitSubtitle: Boolean = false,
        subtitleMaxChars: Int = 15
    ): Boolean {
        if (audioFiles.isEmpty()) {
            AppLog.put("$TAG: 无音频文件，无法合成视频")
            return false
        }
        if (imageTracks.isEmpty()) {
            AppLog.put("$TAG: 无图片轨道，无法合成视频")
            return false
        }
        // 防御校验：音频段与字幕段必须等长，否则按音频段数截断字幕，避免错位/越界
        if (segmentTexts.size != audioFiles.size) {
            AppLog.put(
                "$TAG: 音频与字幕段数不一致(音频=${audioFiles.size}, 字幕=${segmentTexts.size})，" +
                    "已按音频段对齐字幕"
            )
        }

        // 0. 视频尺寸取第一张图片的尺寸（过大时等比缩小，宽高取偶），竖图输出竖版视频
        val (width, height) = resolveVideoSize(imageTracks.first().file)

        // 1. 解码每段音频并统一到第一段格式，拼接为完整 PCM，同时算每段时长
        val pcms = mutableListOf<AudioConcatUtil.PcmData>()
        for (f in audioFiles) {
            val p = AudioConcatUtil.decodeToPcm(f)
            if (p == null) {
                AppLog.put("$TAG: 音频解码失败 ${f.name}")
                return false
            }
            pcms.add(p)
        }
        val sampleRate = pcms.first().sampleRate
        val channels = pcms.first().channels
        val resampled = pcms.map { AudioConcatUtil.resamplePcm(it, sampleRate, channels) }
        val bytesPerFrame = channels * 2

        val segDurationsUs = resampled.map { seg ->
            val frameCount = seg.bytes.size / bytesPerFrame
            if (sampleRate > 0) frameCount * 1_000_000L / sampleRate else 0L
        }

        val pcmOut = java.io.ByteArrayOutputStream()
        resampled.forEach { pcmOut.write(it.bytes) }
        val pcmBytes = pcmOut.toByteArray()
        if (pcmBytes.isEmpty()) {
            AppLog.put("$TAG: 拼接后 PCM 为空")
            return false
        }
        val totalAudioUs = (pcmBytes.size / bytesPerFrame) * 1_000_000L / sampleRate

        // 2. 构建每段的画面帧（图片 + 字幕 + 时间）
        val frames = buildFrames(imageTracks, segmentTexts, segDurationsUs, totalAudioUs, burnSubtitle, splitSubtitle, subtitleMaxChars)
        if (frames.isEmpty()) {
            AppLog.put("$TAG: 未生成任何画面帧")
            return false
        }

        // 3. 编码并 mux
        return encodeMp4(
            outputFile = outputFile,
            frames = frames,
            pcmBytes = pcmBytes,
            sampleRate = sampleRate,
            channels = channels,
            width = width,
            height = height,
            frameRate = frameRate,
            totalAudioUs = totalAudioUs,
            subtitleFontSizeScale = subtitleFontSizeScale,
            subtitleVOffset = subtitleVOffset
        )
    }

    /**
     * 把图片轨道映射到每段音频时间轴上，生成画面帧列表。
     * - 图片选择：优先选「包含当前段」的图片；否则选起始段<=当前段中起始最大的（实现画面停留）；
     * - 若之前都没有图片，则用第一张图兜底。
     * - 字幕：去掉音效标记后的朗读文本。
     */
    private fun buildFrames(
        imageTracks: List<ImageTrack>,
        segmentTexts: List<String>,
        segDurationsUs: List<Long>,
        totalAudioUs: Long,
        burnSubtitle: Boolean,
        splitSubtitle: Boolean,
        subtitleMaxChars: Int
    ): List<FrameItem> {
        val segCount = segDurationsUs.size
        val out = mutableListOf<FrameItem>()
        var acc = 0L
        var currentImage: File? = null
        for (i in 0 until segCount) {
            val containing = imageTracks.firstOrNull { it.startPara <= i && it.endPara >= i }
            val chosen = containing
                ?: imageTracks.filter { it.startPara <= i }.maxByOrNull { it.startPara }
            currentImage = chosen?.file ?: currentImage ?: imageTracks.first().file
            val startUs = acc
            acc += segDurationsUs[i]
            val endUs = if (i == segCount - 1) totalAudioUs else acc
            if (!burnSubtitle) {
                out.add(FrameItem(currentImage!!, "", startUs, endUs))
                continue
            }
            val text = stripEffectMarkers(segmentTexts.getOrNull(i) ?: "")
            if (text.isBlank()) {
                out.add(FrameItem(currentImage!!, "", startUs, endUs))
                continue
            }
            if (splitSubtitle) {
                // 按句切割：复用 SubTitleUtils 的切分规则（标点切分、相邻拼接到 maxChars 字），
                // 并按各片段字数比例把该段时长切分，片段依次出现。
                val frags = SubTitleUtils.splitToSubtitles(text, subtitleMaxChars)
                val fragList = if (frags.isEmpty()) listOf(text) else frags
                val totalChars = fragList.sumOf { SubTitleUtils.countChinese(it) }
                if (totalChars == 0) {
                    out.add(FrameItem(currentImage!!, text, startUs, endUs))
                } else {
                    var cursor = startUs
                    fragList.forEachIndexed { idx, frag ->
                        val dur = SubTitleUtils.countChinese(frag).toLong() * (endUs - startUs) / totalChars
                        val fragEnd = if (idx == fragList.lastIndex) endUs else cursor + dur
                        out.add(FrameItem(currentImage!!, frag, cursor, fragEnd))
                        cursor = fragEnd
                    }
                }
            } else {
                out.add(FrameItem(currentImage!!, text, startUs, endUs))
            }
        }
        return out
    }

    /**
     * 根据第一张图片确定视频尺寸：
     * - 直接使用图片原始宽高（竖图即竖版视频）；
     * - 长边超过 1920 时等比缩小；
     * - 宽高各自向下取偶（YUV420 要求）。
     */
    private fun resolveVideoSize(firstImage: File): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(firstImage.absolutePath, opts)
        var w = opts.outWidth
        var h = opts.outHeight
        if (w <= 0 || h <= 0) {
            w = 1280
            h = 720
        }
        val maxSide = 1920
        val longSide = maxOf(w, h)
        if (longSide > maxSide) {
            val scale = maxSide.toFloat() / longSide
            w = (w * scale).toInt()
            h = (h * scale).toInt()
        }
        w = (w and 1.inv()).coerceAtLeast(2)
        h = (h and 1.inv()).coerceAtLeast(2)
        return w to h
    }

    /** 去掉文本中的音效标记，如 (打雷音效) / （打雷音效） */
    private fun stripEffectMarkers(text: String): String {
        return text
            .replace(Regex("\\([\\u4e00-\\u9fa5]*音效\\)"), "")
            .replace(Regex("（[\\u4e00-\\u9fa5]*音效）"), "")
            .trim()
    }

    /**
     * H264(视频) + AAC(音频) 编码并 mux 为 MP4。
     * 视频用 ByteBuffer 输入（COLOR_FormatYUV420Flexible），逐帧喂 NV12；
     * 音频直接喂 16-bit PCM。两路编码器交错喂数据并实时 drain 到 MediaMuxer。
     */
    private fun encodeMp4(
        outputFile: File,
        frames: List<FrameItem>,
        pcmBytes: ByteArray,
        sampleRate: Int,
        channels: Int,
        width: Int,
        height: Int,
        frameRate: Int,
        totalAudioUs: Long,
        subtitleFontSizeScale: Float,
        subtitleVOffset: Float
    ): Boolean {
        outputFile.parentFile?.mkdirs()
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var videoTrackIdx = -1
        var audioTrackIdx = -1

        // 视频编码器
        val vFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        vFormat.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        )
        // 码率随分辨率自适应（静态画面为主，无需太高）
        vFormat.setInteger(MediaFormat.KEY_BIT_RATE, (width * height * 2).coerceIn(1_000_000, 8_000_000))
        vFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate.coerceAtLeast(1))
        vFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        val vEnc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        vEnc.configure(vFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        vEnc.start()

        // 音频编码器
        val aFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels)
        aFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        aFormat.setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
        aFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        val aEnc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        aEnc.configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        aEnc.start()

        val bytesPerFrame = channels * 2
        val ySize = width * height
        val uvSize = width * height / 4
        val yuv = ByteArray(ySize + uvSize * 2)
        val timeoutUs = 10_000L
        val vInfo = MediaCodec.BufferInfo()
        val aInfo = MediaCodec.BufferInfo()

        // 固定帧间隔（10fps 即 100ms），按时间轴逐帧喂；画面段不变时直接复用缓存 yuv
        val frameIntervalUs = 1_000_000L / frameRate.coerceAtLeast(1)
        val totalVideoFrames = ((totalAudioUs + frameIntervalUs - 1) / frameIntervalUs).toInt().coerceAtLeast(1)
        var segCursor = 0
        var lastRenderedSeg = -1

        var pcmOffset = 0
        var frameIdx = 0
        var videoQueuedEos = false
        var audioQueuedEos = false
        var videoDone = false
        var audioDone = false

        fun maybeStartMuxer() {
            if (!muxerStarted && videoTrackIdx >= 0 && audioTrackIdx >= 0) {
                muxer.start()
                muxerStarted = true
            }
        }

        try {
            while (!(videoDone && audioDone)) {
                // ---- 喂视频帧（固定 10fps 间隔，画面段不变时复用缓存 yuv）----
                if (!videoQueuedEos && frameIdx < totalVideoFrames) {
                    val inIdx = vEnc.dequeueInputBuffer(timeoutUs)
                    if (inIdx >= 0) {
                        val ptsUs = frameIdx * frameIntervalUs
                        // 定位当前时间所属的画面段（时间单调递增，游标只前进）
                        while (segCursor < frames.size - 1 && ptsUs >= frames[segCursor].endUs) {
                            segCursor++
                        }
                        val buf = vEnc.getInputBuffer(inIdx)
                        if (buf != null) {
                            buf.clear()
                            val need = yuv.size
                            if (buf.remaining() >= need) {
                                if (segCursor != lastRenderedSeg) {
                                    renderFrameToNv12(frames[segCursor], width, height, yuv, subtitleFontSizeScale, subtitleVOffset)
                                    lastRenderedSeg = segCursor
                                }
                                buf.put(yuv, 0, need)
                                vEnc.queueInputBuffer(inIdx, 0, need, ptsUs, 0)
                            } else {
                                vEnc.queueInputBuffer(inIdx, 0, 0, ptsUs, 0)
                            }
                        } else {
                            vEnc.queueInputBuffer(inIdx, 0, 0, ptsUs, 0)
                        }
                        frameIdx++
                    }
                } else if (!videoQueuedEos) {
                    val inIdx = vEnc.dequeueInputBuffer(timeoutUs)
                    if (inIdx >= 0) {
                        vEnc.queueInputBuffer(inIdx, 0, 0, totalAudioUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        videoQueuedEos = true
                    }
                }

                // ---- 喂音频 PCM ----
                if (!audioQueuedEos) {
                    val inIdx = aEnc.dequeueInputBuffer(timeoutUs)
                    if (inIdx >= 0) {
                        val buf = aEnc.getInputBuffer(inIdx)
                        if (buf != null) {
                            buf.clear()
                            val remaining = pcmBytes.size - pcmOffset
                            if (remaining > 0) {
                                val toCopy = minOf(buf.remaining(), remaining)
                                buf.put(pcmBytes, pcmOffset, toCopy)
                                val ptsUs = (pcmOffset / bytesPerFrame) * 1_000_000L / sampleRate
                                aEnc.queueInputBuffer(inIdx, 0, toCopy, ptsUs, 0)
                                pcmOffset += toCopy
                            } else {
                                aEnc.queueInputBuffer(
                                    inIdx, 0, 0,
                                    (pcmBytes.size / bytesPerFrame) * 1_000_000L / sampleRate,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                audioQueuedEos = true
                            }
                        } else {
                            aEnc.queueInputBuffer(
                                inIdx, 0, 0,
                                (pcmBytes.size / bytesPerFrame) * 1_000_000L / sampleRate,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            audioQueuedEos = true
                        }
                    }
                }

                // ---- drain 视频输出 ----
                var vOut = vEnc.dequeueOutputBuffer(vInfo, timeoutUs)
                when {
                    vOut == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoTrackIdx = muxer.addTrack(vEnc.outputFormat)
                        maybeStartMuxer()
                    }
                    vOut == MediaCodec.INFO_TRY_AGAIN_LATER -> { /* 下一轮 */ }
                    vOut >= 0 -> {
                        val buf = vEnc.getOutputBuffer(vOut)
                        if (buf != null && vInfo.size > 0 &&
                            (vInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            if (muxerStarted) {
                                buf.position(vInfo.offset)
                                buf.limit(vInfo.offset + vInfo.size)
                                muxer.writeSampleData(videoTrackIdx, buf, vInfo)
                            }
                        }
                        vEnc.releaseOutputBuffer(vOut, false)
                        if ((vInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) videoDone = true
                    }
                }

                // ---- drain 音频输出 ----
                var aOut = aEnc.dequeueOutputBuffer(aInfo, timeoutUs)
                when {
                    aOut == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        audioTrackIdx = muxer.addTrack(aEnc.outputFormat)
                        maybeStartMuxer()
                    }
                    aOut == MediaCodec.INFO_TRY_AGAIN_LATER -> { /* 下一轮 */ }
                    aOut >= 0 -> {
                        val buf = aEnc.getOutputBuffer(aOut)
                        if (buf != null && aInfo.size > 0 &&
                            (aInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            if (muxerStarted) {
                                buf.position(aInfo.offset)
                                buf.limit(aInfo.offset + aInfo.size)
                                muxer.writeSampleData(audioTrackIdx, buf, aInfo)
                            }
                        }
                        aEnc.releaseOutputBuffer(aOut, false)
                        if ((aInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) audioDone = true
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.put("$TAG: 编码异常 ${e.localizedMessage}", e)
            return false
        } finally {
            try { vEnc.stop(); vEnc.release() } catch (_: Exception) {}
            try { aEnc.stop(); aEnc.release() } catch (_: Exception) {}
            try {
                if (muxerStarted) muxer.stop()
                muxer.release()
            } catch (_: Exception) {}
        }

        val ok = muxerStarted && outputFile.exists() && outputFile.length() > 0
        AppLog.put(if (ok) "$TAG: 视频合成完成 ${outputFile.name}" else "$TAG: 视频合成失败")
        return ok
    }

    /** 把一帧画面（图片 + 字幕）渲染为 NV12(YUV420) 写入 out */
    private fun renderFrameToNv12(
        frame: FrameItem,
        w: Int,
        h: Int,
        out: ByteArray,
        fontSizeScale: Float,
        vOffset: Float
    ) {
        // BitmapFactory 按文件内容(魔数)嗅探格式，与后缀无关；这里强制 ARGB_8888，
        // 让带广色域/ICC 配置的图片在绘制到 sRGB 画布时正确转换为 sRGB，避免播放时偏色。
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK)

        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val src = try { BitmapFactory.decodeFile(frame.imageFile.absolutePath, opts) } catch (_: Exception) { null }
        if (src != null) {
            val scale = minOf(w.toFloat() / src.width, h.toFloat() / src.height)
            val dw = (src.width * scale).toInt().coerceAtLeast(1)
            val dh = (src.height * scale).toInt().coerceAtLeast(1)
            val dx = (w - dw) / 2
            val dy = (h - dh) / 2
            canvas.drawBitmap(src, null, Rect(dx, dy, dx + dw, dy + dh), null)
            src.recycle()
        }

        if (frame.subtitle.isNotBlank()) {
            drawSubtitle(canvas, frame.subtitle, w, h, fontSizeScale, vOffset)
        }

        argbToNv12(bmp, out)
        bmp.recycle()
    }

    /** 画面底部绘制居中字幕：黑色文字 + 白色描边（自动换行，无底栏） */
    private fun drawSubtitle(
        canvas: Canvas,
        text: String,
        w: Int,
        h: Int,
        fontSizeScale: Float = 1f,
        vOffset: Float = 0f
    ) {
        // 字号 = 默认基准(画面高度 3.3%) × 用户比例；竖版视频居多，默认值已较原 0.045 调小
        val textSize = h * 0.033f * fontSizeScale.coerceAtLeast(0.1f)
        // 复用阅读器当前字体（ReadBookConfig.textFont，无则回退系统默认），保持字幕与阅读字体一致
        // 并跟随阅读界面的加粗/细体设置（ReadBookConfig.textBold），使字幕粗细与正文一致
        val baseTypeface = ChapterProvider.typeface
        val typeface = when (ReadBookConfig.textBold) {
            1 -> Typeface.create(baseTypeface, Typeface.BOLD)
            2 -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                Typeface.create(baseTypeface, 300, false) else baseTypeface
            else -> baseTypeface
        }
        // 字幕颜色 / 描边样式跟随设置（解析失败时回退默认：黑字 / 白描边）
        val fontColor = runCatching { Color.parseColor(AppConfig.videoSubtitleFontColor) }.getOrDefault(Color.BLACK)
        val strokeColor = runCatching { Color.parseColor(AppConfig.videoSubtitleStrokeColor) }.getOrDefault(Color.WHITE)
        val strokeWidthRatio = AppConfig.videoSubtitleStrokeWidth
        // 描边画笔（先画）
        val strokePaint = Paint().apply {
            isAntiAlias = true
            color = strokeColor
            this.textSize = textSize
            this.typeface = typeface
            textAlign = Paint.Align.CENTER
            style = Paint.Style.STROKE
            strokeWidth = textSize * strokeWidthRatio.coerceAtLeast(0f)
            strokeJoin = Paint.Join.ROUND
        }
        // 填充画笔（后画）
        val fillPaint = Paint().apply {
            isAntiAlias = true
            color = fontColor
            this.textSize = textSize
            this.typeface = typeface
            textAlign = Paint.Align.CENTER
        }
        val maxWidth = w * 0.92f
        val lines = wrapText(text, fillPaint, maxWidth)
        if (lines.isEmpty()) return
        val lineH = textSize * 1.25f
        val totalH = lineH * lines.size
        // 默认底部留白 12% 高度；vOffset 为正上移、为负下移（单位：画面高度比例），clamp 防出屏
        val pad = ((0.12f + vOffset).coerceIn(-0.12f, 0.6f)) * h
        var y = h - totalH - pad + lineH * 0.85f
        for (line in lines) {
            canvas.drawText(line, w / 2f, y, strokePaint)
            canvas.drawText(line, w / 2f, y, fillPaint)
            y += lineH
        }
    }

    /** 按宽度简单逐字符换行 */
    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val lines = mutableListOf<String>()
        var line = StringBuilder()
        for (ch in text) {
            val test = line.toString() + ch
            if (paint.measureText(test) > maxWidth && line.isNotEmpty()) {
                lines.add(line.toString())
                line = StringBuilder(ch.toString())
            } else {
                line.append(ch)
            }
        }
        if (line.isNotEmpty()) lines.add(line.toString())
        return lines
    }

    /** ARGB8888 -> NV12(YUV420 半平面) */
    private fun argbToNv12(bmp: Bitmap, out: ByteArray) {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        var yIdx = 0
        var uvIdx = w * h
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = pixels[y * w + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val yVal = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                out[yIdx++] = yVal.coerceIn(0, 255).toByte()
            }
        }
        for (y in 0 until h step 2) {
            for (x in 0 until w step 2) {
                val p = pixels[y * w + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val uVal = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val vVal = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                // NV12: 半平面按 U、V 顺序交错（U 在前）。写反成 V、U 会变成 NV21，
                // 导致色度平面错位、画面整体发蓝发灰。
                out[uvIdx++] = uVal.coerceIn(0, 255).toByte()
                out[uvIdx++] = vVal.coerceIn(0, 255).toByte()
            }
        }
    }
}
