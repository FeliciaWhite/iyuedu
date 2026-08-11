package io.legado.app.ui.book.read.config

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import io.legado.app.ui.book.read.page.provider.ChapterProvider

/**
 * 字幕样式实时预览：与 [io.legado.app.utils.VideoComposeUtil.drawSubtitle] 一致的
 * 双遍绘制（黑底、先描边后填充），并复用阅读器字体，使预览与成片效果一致。
 */
class SubtitlePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var fontSizeScale = 1f
    private var fontColor = Color.BLACK
    private var strokeWidthRatio = 0.12f
    private var strokeColor = Color.WHITE
    private var bgColor = Color.BLACK
    private val typeface: Typeface? = ChapterProvider.typeface

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        textAlign = Paint.Align.CENTER
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val sample = "字体效果展示"

    init {
        // 点击预览区在黑/白背景间切换，方便查看深色描边效果
        isClickable = true
        setOnClickListener {
            bgColor = if (bgColor == Color.BLACK) Color.WHITE else Color.BLACK
            invalidate()
        }
    }

    fun update(
        fontSizeScale: Float,
        fontColor: Int,
        strokeWidthRatio: Float,
        strokeColor: Int
    ) {
        this.fontSizeScale = fontSizeScale
        this.fontColor = fontColor
        this.strokeWidthRatio = strokeWidthRatio
        this.strokeColor = strokeColor
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 预览背景（点击可切换黑/白），与视频默认黑底不同，仅用于实时预览观察
        canvas.drawColor(bgColor)
        val textSize = height * 0.18f * fontSizeScale.coerceAtLeast(0.1f)
        strokePaint.textSize = textSize
        strokePaint.strokeWidth = textSize * strokeWidthRatio.coerceAtLeast(0f)
        strokePaint.color = strokeColor
        strokePaint.typeface = typeface
        fillPaint.textSize = textSize
        fillPaint.color = fontColor
        fillPaint.typeface = typeface
        val fm = strokePaint.fontMetrics
        val textHeight = fm.descent - fm.ascent
        val y = (height + textHeight) / 2f - fm.descent
        canvas.drawText(sample, width / 2f, y, strokePaint)
        canvas.drawText(sample, width / 2f, y, fillPaint)
    }
}
