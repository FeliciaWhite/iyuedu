package io.legado.app.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import io.legado.app.ui.widget.image.CoverImageView

class MarqueeCoverImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : CoverImageView(context, attrs) {

    private var isMarqueeEnabled = false
    private var marqueeSpeedMs: Long = 3000L
    private var isAnimating = false
    private var offset = 0f
    private var animator: ValueAnimator? = null

    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val borderWidthPx = context.resources.displayMetrics.density * 6f

    fun setMarqueeEnabled(enabled: Boolean) {
        isMarqueeEnabled = enabled
        if (enabled) {
            startMarquee()
        } else {
            stopMarquee()
        }
    }

    fun setMarqueeSpeed(speedMs: Long) {
        marqueeSpeedMs = speedMs
        if (isAnimating) {
            startMarqueeAnimation()
        }
    }

    fun startMarquee() {
        if (isAnimating || !isMarqueeEnabled) return
        isAnimating = true
        startMarqueeAnimation()
    }

    fun stopMarquee() {
        isAnimating = false
        offset = 0f
        animator?.cancel()
        invalidate()
    }

    fun pauseMarquee() {
        // 不修改 isAnimating，只暂停 animator，让 onDraw 继续把当前帧画出来
        animator?.cancel()
    }

    fun resumeMarquee() {
        if (!isAnimating) return
        startMarqueeAnimation()
    }

    private var marqueeStartTime = 0L

    private fun startMarqueeAnimation() {
        animator?.cancel()
        // 根据当前 offset 反推出对应的开始时间，保证恢复后从原位置无缝继续
        marqueeStartTime = SystemClock.uptimeMillis() - (offset * marqueeSpeedMs).toLong()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Long.MAX_VALUE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val elapsed = SystemClock.uptimeMillis() - marqueeStartTime
                offset = ((elapsed % marqueeSpeedMs) / marqueeSpeedMs.toFloat()).coerceIn(0f, 1f)
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isAnimating) return

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 边框靠近边缘，中心线贴边
        val halfStroke = borderWidthPx / 2
        val rect = RectF(halfStroke, halfStroke, w - halfStroke, h - halfStroke)
        val cornerRadius = context.resources.displayMetrics.density * 8f

        // 创建顺时针圆角矩形路径
        val path = Path()
        path.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
        val pathMeasure = PathMeasure(path, false)
        val pathLength = pathMeasure.length
        if (pathLength <= 0) return

        // 1. 绘制底色边框（暗色半透明）
        borderPaint.shader = null
        borderPaint.color = Color.parseColor("#30FFFFFF")
        borderPaint.strokeWidth = borderWidthPx * 0.4f
        borderPaint.pathEffect = null
        borderPaint.clearShadowLayer()
        canvas.drawPath(path, borderPaint)

        // 2. 绘制梭形流光：中间粗两头细，尾巴拖在运动方向后方
        val streamerLength = pathLength * 0.4f
        val segments = 120
        val pos = FloatArray(2)
        val tan = FloatArray(2)
        val headDist = offset * pathLength

        // 前缘（运动方向前方）占 15%，主尾巴（后方）占 85%
        val frontLen = streamerLength * 0.15f
        val backLen = streamerLength * 0.85f

        for (i in 0 until segments) {
            val t0 = i.toFloat() / segments
            val t1 = (i + 1f) / segments

            // 映射到路径距离：从 headDist - backLen 到 headDist + frontLen
            val dist0 = (headDist - backLen + t0 * (backLen + frontLen)) % pathLength
            val dist1 = (headDist - backLen + t1 * (backLen + frontLen)) % pathLength

            if (!pathMeasure.getPosTan(if (dist0 < 0) dist0 + pathLength else dist0, pos, tan)) continue
            val x0 = pos[0]
            val y0 = pos[1]

            if (!pathMeasure.getPosTan(if (dist1 < 0) dist1 + pathLength else dist1, pos, tan)) continue
            val x1 = pos[0]
            val y1 = pos[1]

            // 计算每个小段的“中心位置”离头部（headDist）的距离比例
            // 头部在 backLen / (backLen + frontLen) 这个位置
            val segCenter = (t0 + t1) / 2f
            val headRatio = backLen / (backLen + frontLen)
            val distFromHead = kotlin.math.abs(segCenter - headRatio) / kotlin.math.max(headRatio, 1f - headRatio)
            val intensity = 1f - distFromHead.coerceIn(0f, 1f)

            // 流光溢彩：越靠近头部越亮，颜色随时间流动
            val alpha = (intensity * 240).toInt()
            val hue = (offset * 360f + intensity * 120f) % 360f
            val color = Color.HSVToColor(alpha, floatArrayOf(hue, 0.85f, 0.9f + intensity * 0.1f))

            borderPaint.color = color
            // 中间最粗，两头最细
            borderPaint.strokeWidth = borderWidthPx * (0.3f + intensity * 1.4f)
            borderPaint.clearShadowLayer()
            canvas.drawLine(x0, y0, x1, y1, borderPaint)
        }

    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}
