package com.theveloper.pixelplay.data.media

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.sqrt

@UnstableApi
class AudioRmsSink(
    private val onRmsChanged: (Float) -> Unit
) : TeeAudioProcessor.AudioBufferSink {

    // 给一个合理的初始底噪下限，防止刚开始静音时被无限放大
    private var maxRms = 1000f
    private var currentEncoding = C.ENCODING_PCM_16BIT

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        currentEncoding = encoding
        maxRms = 1000f
        onRmsChanged(0f)
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!buffer.hasRemaining()) return

        var sumSquares = 0.0
        var sampleCount = 0

        when (currentEncoding) {
            C.ENCODING_PCM_16BIT -> {
                val shortBuffer = buffer.asShortBuffer()
                sampleCount = shortBuffer.remaining()
                if (sampleCount == 0) return
                while (shortBuffer.hasRemaining()) {
                    val sample = shortBuffer.get().toDouble()
                    sumSquares += sample * sample
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                val floatBuffer = buffer.asFloatBuffer()
                sampleCount = floatBuffer.remaining()
                if (sampleCount == 0) return
                while (floatBuffer.hasRemaining()) {
                    val sample = floatBuffer.get().toDouble()
                    // Float 范围是 -1.0 到 1.0，乘以 32768 对齐到 16-bit 级别，保证计算口径统一
                    val scaled = sample * 32768.0
                    sumSquares += scaled * scaled
                }
            }
            else -> return // 其他非常规编码直接忽略
        }

        if (sampleCount == 0) return

        val rms = sqrt(sumSquares / sampleCount).toFloat()

        // 【核心修复】不仅要记录最大值，还要让它缓慢衰减
        if (rms > maxRms) {
            maxRms = rms
        } else {
            // 每次缓冲平滑衰减，使其能适应接下来的低潮片段
            maxRms *= 0.995f
        }

        // 钳制最低基准，防止将纯静音里的微弱底噪放大成强烈的跳动
        maxRms = maxRms.coerceAtLeast(1000f)

        // 归一化，并加入一个极小的死区（低于 5% 视作静音停止跳动）
        var normalizedRms = (rms / maxRms).coerceIn(0f, 1f)
        if (normalizedRms < 0.05f) normalizedRms = 0f

        onRmsChanged(normalizedRms)
    }
}