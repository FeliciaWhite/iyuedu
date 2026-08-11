package io.legado.app.help.audio

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.tanh

/**
 * 朗读音量增益处理器。
 *
 * 作用在 ExoPlayer 解码后的 PCM 数据上（与源音频格式 mp3/wav/opus 无关），
 * 对每个采样做乘性增益，并使用 tanh 软限幅（soft clip）代替硬限幅，
 * 避免高增益时产生严重的谐波失真和爆音。
 *
 * 增益值可在运行期间通过 [setGain] 动态调整，下一次 [queueInput] 即生效。
 */
class GainAudioProcessor(
    initialGain: Float = 1.0f
) : AudioProcessor {

    @Volatile
    private var gain: Float = initialGain

    private var inputAudioFormat: AudioProcessor.AudioFormat = AudioProcessor.AudioFormat.NOT_SET
    private var outputAudioFormat: AudioProcessor.AudioFormat = AudioProcessor.AudioFormat.NOT_SET

    private var buffer: ByteBuffer = EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = EMPTY_BUFFER
    private var inputEnded = false

    init {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
    }

    /** 设置增益倍率。1.0 表示不处理，>1 放大，<1 衰减。 */
    fun setGain(value: Float) {
        gain = value
    }

    /** 当前增益倍率。 */
    fun getGain(): Float = gain

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != androidx.media3.common.C.ENCODING_PCM_16BIT
            && inputAudioFormat.encoding != androidx.media3.common.C.ENCODING_PCM_FLOAT
        ) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        this.inputAudioFormat = inputAudioFormat
        this.outputAudioFormat = inputAudioFormat
        return outputAudioFormat
    }

    override fun isActive(): Boolean {
        // 始终处于激活状态，增益为 1.0 时按原样输出（乘法等价）。
        // 这样可避免运行时切换增益时 ExoPlayer 重建音频链路造成卡顿。
        return inputAudioFormat != AudioProcessor.AudioFormat.NOT_SET
    }

    /**
     * 软限幅函数：将任意浮点值平滑压缩到 [-1, 1] 区间。
     * 使用 tanh 曲线，模拟模拟电路的饱和特性，过渡自然无爆音。
     *
     * @param x 归一化后的采样值（增益已应用）
     * @return 软限幅后的采样值，范围 (-1, 1)
     */
    private fun softClip(x: Float): Float {
        // tanh 的导数在 x≈0 处约等于 1，保证小信号线性；大信号逐渐饱和
        return tanh(x)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val position = inputBuffer.position()
        val limit = inputBuffer.limit()
        val remaining = limit - position
        if (remaining == 0) return

        // 申请足够大的输出缓冲
        val outCapacity = remaining
        if (outCapacity > outputBuffer.capacity()) {
            outputBuffer = ByteBuffer.allocateDirect(outCapacity).apply {
                order(ByteOrder.LITTLE_ENDIAN)
            }
        }
        outputBuffer.clear()

        val currentGain = gain
        when (inputAudioFormat.encoding) {
            androidx.media3.common.C.ENCODING_PCM_16BIT -> {
                // 16-bit PCM：先归一化到 [-1, 1]，乘增益后经 tanh 软限幅，再缩放回 16-bit
                val invMax = 1.0f / 32768.0f
                val samples = ShortArray(remaining / 2)
                var i = position
                var s = 0
                while (i + 1 < limit) {
                    val sample = inputBuffer.getShort(i).toInt()
                    val normalized = sample * invMax * currentGain
                    val softClipped = softClip(normalized)
                    val result = (softClipped * 32767.0f).toInt()
                    samples[s++] = result.toShort()
                    i += 2
                }
                for (v in samples) {
                    outputBuffer.putShort(v)
                }
            }
            androidx.media3.common.C.ENCODING_PCM_FLOAT -> {
                // Float PCM：直接乘增益后经 tanh 软限幅
                val samples = FloatArray(remaining / 4)
                var i = position
                var s = 0
                while (i + 3 < limit) {
                    val sample = inputBuffer.getFloat(i)
                    val amplified = sample * currentGain
                    samples[s++] = softClip(amplified)
                    i += 4
                }
                for (v in samples) {
                    outputBuffer.putFloat(v)
                }
            }
            else -> {
                // 不应到达，configure 已限制
                outputBuffer.put(inputBuffer)
            }
        }

        inputBuffer.position(limit)
        outputBuffer.flip()
    }

    override fun getOutput(): ByteBuffer {
        val out = outputBuffer
        outputBuffer = EMPTY_BUFFER
        return out
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun isEnded(): Boolean {
        return inputEnded && outputBuffer === EMPTY_BUFFER
    }

    override fun flush() {
        outputBuffer = EMPTY_BUFFER
        inputEnded = false
    }

    override fun reset() {
        flush()
        inputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
        outputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
        buffer = EMPTY_BUFFER
    }

    companion object {
        private val EMPTY_BUFFER = ByteBuffer.allocateDirect(0).apply {
            order(ByteOrder.LITTLE_ENDIAN)
        }
    }
}
