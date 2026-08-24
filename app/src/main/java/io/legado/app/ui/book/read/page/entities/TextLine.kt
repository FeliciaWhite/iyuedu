package io.legado.app.ui.book.read.page.entities

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Paint.FontMetrics
import android.os.Build
import android.text.TextPaint
import androidx.annotation.Keep
import io.legado.app.help.PaintPool
import io.legado.app.help.book.isImage
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.DialogRoleManager
import io.legado.app.ui.book.read.page.entities.TextPage.Companion.emptyTextPage
import io.legado.app.ui.book.read.page.entities.column.BaseColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.utils.canvasrecorder.CanvasRecorderFactory
import io.legado.app.utils.canvasrecorder.recordIfNeededThenDraw
import io.legado.app.utils.dpToPx

/**
 * 行信息
 */
@Keep
@Suppress("unused", "MemberVisibilityCanBePrivate")
data class TextLine(
    var text: String = "",
    private val textColumns: ArrayList<BaseColumn> = arrayListOf(),
    var lineTop: Float = 0f,
    var lineBase: Float = 0f,
    var lineBottom: Float = 0f,
    var indentWidth: Float = 0f,
    var paragraphNum: Int = 0,
    var chapterPosition: Int = 0,
    var pagePosition: Int = 0,
    val isTitle: Boolean = false,
    var isParagraphEnd: Boolean = false,
    var isImage: Boolean = false,
    var isHtml: Boolean = false,
    var startX: Float = 0f,
    var indentSize: Int = 0,
    var extraLetterSpacing: Float = 0f,
    var extraLetterSpacingOffsetX: Float = 0f,
    var wordSpacing: Float = 0f,
    var exceed: Boolean = false,
    var onlyTextColumn: Boolean = true,
) {

    val columns: List<BaseColumn> get() = textColumns
    val charSize: Int get() = text.length
    val lineStart: Float get() = textColumns.firstOrNull()?.start ?: 0f
    val lineEnd: Float get() = textColumns.lastOrNull()?.end ?: 0f
    val chapterIndices: IntRange get() = chapterPosition..chapterPosition + charSize
    val height: Float inline get() = lineBottom - lineTop
    val canvasRecorder = CanvasRecorderFactory.create()
    var searchResultColumnCount = 0
    var isReadAloud: Boolean = false
        set(value) {
            if (field != value) {
                invalidate()
            }
            if (value) {
                textPage.hasReadAloudSpan = true
            }
            field = value
        }
    var textPage: TextPage = emptyTextPage
    var isLeftLine = true
    val roleAnnotations = arrayListOf<DialogRoleManager.RoleAnnotation>()
    var audioParagraphIndex: Int = -1  // 段落首行绑定的音频段落索引
    var hasAudioCache: Boolean = false // 该段落是否有音频缓存

    fun addColumn(column: BaseColumn) {
        if (column !is TextColumn) {
            onlyTextColumn = false
        }
        column.textLine = this
        textColumns.add(column)
    }

    fun setRoleAnnotations(annotations: List<DialogRoleManager.RoleAnnotation>) {
        synchronized(roleAnnotations) {
            roleAnnotations.clear()
            roleAnnotations.addAll(annotations)
        }
    }

    fun addColumns(columns: Collection<BaseColumn>) {
        onlyTextColumn = false
        columns.forEach { column ->
            column.textLine = this
        }
        textColumns.addAll(columns)
    }

    fun getColumn(index: Int): BaseColumn {
        return textColumns.getOrElse(index) {
            textColumns.last()
        }
    }

    fun getColumnReverseAt(index: Int, offset: Int = 0): BaseColumn {
        return textColumns[textColumns.lastIndex - offset - index]
    }

    fun getColumnsCount(): Int {
        return textColumns.size
    }

    fun upTopBottom(durY: Float, textHeight: Float, fontMetrics: FontMetrics) {
        lineTop = ChapterProvider.paddingTop + durY
        lineBottom = lineTop + textHeight
        lineBase = lineBottom - fontMetrics.descent
    }

    fun isTouch(x: Float, y: Float, relativeOffset: Float): Boolean {
        return y > lineTop + relativeOffset
                && y < lineBottom + relativeOffset
                && x >= lineStart
                && x <= lineEnd + 20.dpToPx()
    }

    fun isTouchY(y: Float, relativeOffset: Float): Boolean {
        return y > lineTop + relativeOffset
                && y < lineBottom + relativeOffset
    }

    fun isVisible(relativeOffset: Float): Boolean {
        val top = lineTop + relativeOffset
        val bottom = lineBottom + relativeOffset
        val width = bottom - top
        val visibleTop = ChapterProvider.paddingTop
        val visibleBottom = ChapterProvider.visibleBottom
        val visible = when {
            // 完全可视
            top >= visibleTop && bottom <= visibleBottom -> true
            top <= visibleTop && bottom >= visibleBottom -> true
            // 上方第一行部分可视
            top < visibleTop && bottom > visibleTop && bottom < visibleBottom -> {
                if (isImage) {
                    true
                } else {
                    val visibleRate = (bottom - visibleTop) / width
                    visibleRate > 0.6
                }
            }
            // 下方第一行部分可视
            top > visibleTop && top < visibleBottom && bottom > visibleBottom -> {
                if (isImage) {
                    true
                } else {
                    val visibleRate = (visibleBottom - top) / width
                    visibleRate > 0.6
                }
            }
            // 不可视
            else -> false
        }
        return visible
    }

    fun draw(view: ContentTextView, canvas: Canvas) {
        if (AppConfig.optimizeRender) {
            canvasRecorder.recordIfNeededThenDraw(canvas, view.width, height.toInt()) {
                drawTextLine(view, this)
            }
        } else {
            drawTextLine(view, canvas)
        }
    }

    private fun drawTextLine(view: ContentTextView, canvas: Canvas) {
        if (checkFastDraw()) {
            fastDrawTextLine(view, canvas)
        } else {
            for (i in columns.indices) {
                columns[i].draw(view, canvas)
            }
        }

        // 绘制角色标注（在左双引号正上方行间距处）
        if (AppConfig.showRoleAnnotation && !isTitle && !isImage && !isHtml) {
            val snapshot = synchronized(roleAnnotations) { ArrayList(roleAnnotations) }
            if (snapshot.isNotEmpty()) {
                val paint = DialogRoleManager.annotationPaint
                paint.textSize = ChapterProvider.contentPaint.textSize * 0.55f
                paint.color = ReadBookConfig.textAccentColor
                val fm = paint.fontMetrics
                val offsetPx = AppConfig.roleAnnotationOffset.dpToPx()
                val textY = -fm.descent - offsetPx
                for (anno in snapshot) {
                    canvas.drawText(
                        anno.name,
                        anno.labelStart,
                        textY,
                        paint
                    )
                }
            }
        }

        // 绘制音频缓存标记（在行首缩进上方）
        // 检测缩进字符（全角空格），在缩进区域上方绘制删除图标。
        if (AppConfig.showAudioCacheIndicator
            && !isTitle && !isImage && !isHtml
        ) {
            val indentColumns = columns.filter {
                it is TextColumn && it.charData == ChapterProvider.indentChar
            }
            if (indentColumns.isNotEmpty()) {
                val firstIndent = indentColumns.first()
                val lastIndent = indentColumns.last()
                val paint = PaintPool.obtain()
                paint.color = ReadBookConfig.textColor
                paint.alpha = if (hasAudioCache) 200 else 40
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.dpToPx().toFloat()
                paint.isAntiAlias = true
                val radius = 13.dpToPx().toFloat()
                val cx = (firstIndent.start + lastIndent.end) / 2
                val cy = (lineTop + lineBottom) / 2 - lineTop
                drawDeleteIcon(canvas, cx, cy, radius, paint)
                PaintPool.recycle(paint)
            }
        }

        // 段落末尾绘制小喇叭（播放按钮）
        if (AppConfig.showAudioCacheIndicator
            && isParagraphEnd
            && !isTitle && !isImage && !isHtml
        ) {
            val paint = PaintPool.obtain()
            paint.color = ReadBookConfig.textColor
            paint.alpha = if (hasAudioCache) 180 else 100
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.dpToPx().toFloat()
            paint.isAntiAlias = true
            val iconX = lineEnd + 6.dpToPx()
            val iconY = (lineTop + lineBottom) / 2 - lineTop
            drawSpeakerIcon(canvas, iconX, iconY, paint, hasAudioCache)
            PaintPool.recycle(paint)
        }

        // 墨水屏模式下的朗读和搜索下划线
        if (AppConfig.isEInkMode && (isReadAloud || searchResultColumnCount > 0)) {
            val underlinePaint = PaintPool.obtain()
            underlinePaint.set(ChapterProvider.contentPaint)
            underlinePaint.strokeWidth = 1.dpToPx().toFloat()
            val lineY = height - 1.dpToPx()
            canvas.drawLine(lineStart + indentWidth, lineY, lineEnd, lineY, underlinePaint)
            PaintPool.recycle(underlinePaint)
        }

        val underlineMode = ReadBookConfig.underlineMode
        if (underlineMode == 0) return
        if (!isImage && !isHtml && ReadBook.book?.isImage != true) {
            drawUnderline(canvas, underlineMode)
        }
    }

    @SuppressLint("NewApi")
    private fun fastDrawTextLine(view: ContentTextView, canvas: Canvas) {
        val textPaint = if (isTitle) {
            ChapterProvider.titlePaint
        } else {
            ChapterProvider.contentPaint
        }
        val textColor = if (isReadAloud) {
            ReadBookConfig.textAccentColor
        } else {
            ReadBookConfig.textColor
        }
        if (textPaint.color != textColor) {
            textPaint.color = textColor
        }
        val paint = PaintPool.obtain()
        paint.set(textPaint)
        val letterSpacing = paint.letterSpacing * paint.textSize
        val letterSpacingHalf = letterSpacing * 0.5f
        if (extraLetterSpacing != 0f) {
            paint.letterSpacing += extraLetterSpacing
        }
        if (wordSpacing != 0f) {
            paint.wordSpacing = wordSpacing
        }
        val offsetX = if (atLeastApi35) letterSpacingHalf else extraLetterSpacingOffsetX
        // 安卓 16 (API 36) 的 BaseCanvas.drawText 对越界的 start/end 会抛 IndexOutOfBoundsException，
        // 这里将绘制区间钳制到合法范围，避免 indentSize 异常时崩溃（如重排后 text 被截断）。
        val drawStart = indentSize.coerceAtLeast(0).coerceAtMost(text.length)
        if (text.length > drawStart) {
            canvas.drawText(text, drawStart, text.length, startX + offsetX, lineBase - lineTop, paint)
        }
        PaintPool.recycle(paint)
        for (i in columns.indices) {
            val column = columns[i] as TextColumn
            if (column.selected) {
                canvas.drawRect(column.start, 0f, column.end, height, view.selectedPaint)
            }
        }
        // 绘制角色标注（在左双引号正上方行间距处）
        if (AppConfig.showRoleAnnotation && !isTitle && !isImage && !isHtml) {
            val snapshot = synchronized(roleAnnotations) { ArrayList(roleAnnotations) }
            if (snapshot.isNotEmpty()) {
                val paint = DialogRoleManager.annotationPaint
                paint.textSize = ChapterProvider.contentPaint.textSize * 0.55f
                paint.color = ReadBookConfig.textAccentColor
                val fm = paint.fontMetrics
                val offsetPx = AppConfig.roleAnnotationOffset.dpToPx()
                val textY = -fm.descent - offsetPx
                for (anno in snapshot) {
                    canvas.drawText(anno.name, anno.labelStart, textY, paint)
                }
            }
        }

        // 绘制音频缓存标记（在行首缩进上方）
        if (AppConfig.showAudioCacheIndicator
            && !isTitle && !isImage && !isHtml
        ) {
            val indentColumns = columns.filter {
                it is TextColumn && it.charData == ChapterProvider.indentChar
            }
            if (indentColumns.isNotEmpty()) {
                val firstIndent = indentColumns.first()
                val lastIndent = indentColumns.last()
                val paint = PaintPool.obtain()
                paint.color = ReadBookConfig.textColor
                paint.alpha = if (hasAudioCache) 200 else 40
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.dpToPx().toFloat()
                paint.isAntiAlias = true
                val radius = 13.dpToPx().toFloat()
                val cx = (firstIndent.start + lastIndent.end) / 2
                val cy = (lineTop + lineBottom) / 2 - lineTop
                drawDeleteIcon(canvas, cx, cy, radius, paint)
                PaintPool.recycle(paint)
            }
        }

        // 段落末尾绘制小喇叭（播放按钮）
        if (AppConfig.showAudioCacheIndicator
            && isParagraphEnd
            && !isTitle && !isImage && !isHtml
        ) {
            val paint = PaintPool.obtain()
            paint.color = ReadBookConfig.textColor
            paint.alpha = if (hasAudioCache) 180 else 100
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.dpToPx().toFloat()
            paint.isAntiAlias = true
            val iconX = lineEnd + 6.dpToPx()
            val iconY = (lineTop + lineBottom) / 2 - lineTop
            drawSpeakerIcon(canvas, iconX, iconY, paint, hasAudioCache)
            PaintPool.recycle(paint)
        }
    }

    /**
     * 绘制下划线
     */
    private fun drawUnderline(canvas: Canvas, underlineMode: Int) {
        val paint = ChapterProvider.contentPaint
        val distance = (ChapterProvider.lineSpacingExtra * 10 - 11).coerceIn(-1f, 10f)
        val lineY = height + distance.dpToPx()
        if (underlineMode == 1) {
            canvas.drawLine(
                lineStart + indentWidth,
                lineY,
                lineEnd,
                lineY,
                paint
            )
        } else if (underlineMode == 2) { // 虚线
            val dashPathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
            val dashPath = TextPaint(paint)
            dashPath.pathEffect = dashPathEffect
            canvas.drawLine(
                lineStart + indentWidth,
                lineY,
                lineEnd,
                lineY,
                dashPath
            )
        }
    }

    /**
     * 在指定位置绘制空心喇叭图标（艺术曲线风格）
     */
    private fun drawSpeakerIcon(canvas: Canvas, startX: Float, centerY: Float, paint: Paint, hasAudioCache: Boolean) {
        val bx = startX
        val by = centerY
        val backW = 4.5f.dpToPx()   // 后部宽度
        val backH = 5.5f.dpToPx()   // 后部半高
        val bodyW = 7.5f.dpToPx()   // 主体展开长度
        val mouthH = 8.5f.dpToPx()  // 喇叭口半高
        val r = 1.dpToPx().toFloat() // 圆角半径

        // 喇叭后部（圆角矩形轮廓）
        val backPath = Path()
        backPath.moveTo(bx + r, by - backH)
        backPath.lineTo(bx + backW - r, by - backH)
        backPath.quadTo(bx + backW, by - backH, bx + backW, by - backH + r)
        backPath.lineTo(bx + backW, by + backH - r)
        backPath.quadTo(bx + backW, by + backH, bx + backW - r, by + backH)
        backPath.lineTo(bx + r, by + backH)
        backPath.quadTo(bx, by + backH, bx, by + backH - r)
        backPath.lineTo(bx, by - backH + r)
        backPath.quadTo(bx, by - backH, bx + r, by - backH)
        backPath.close()
        canvas.drawPath(backPath, paint)

        // 喇叭主体（向右展开的梯形，弧线过渡）
        val mouthX = bx + backW + bodyW
        val bodyPath = Path()
        bodyPath.moveTo(bx + backW, by - backH + r)
        bodyPath.quadTo(bx + backW + bodyW * 0.4f, by - backH - 1.dpToPx(), mouthX - r, by - mouthH)
        bodyPath.quadTo(mouthX, by - mouthH, mouthX, by - mouthH + r)
        bodyPath.lineTo(mouthX, by + mouthH - r)
        bodyPath.quadTo(mouthX, by + mouthH, mouthX - r, by + mouthH)
        bodyPath.quadTo(bx + backW + bodyW * 0.4f, by + backH + 1.dpToPx(), bx + backW, by + backH - r)
        canvas.drawPath(bodyPath, paint)

        // 双层声波弧线
        if (hasAudioCache) {
            val wavePaint = PaintPool.obtain()
            wavePaint.set(paint)
            wavePaint.style = Paint.Style.STROKE
            wavePaint.strokeWidth = 1.dpToPx().toFloat()

            val wavePath1 = Path()
            val wx1 = mouthX + 1.5f.dpToPx()
            wavePath1.moveTo(wx1, by - mouthH * 0.45f)
            wavePath1.quadTo(wx1 + 3.5f.dpToPx(), by, wx1, by + mouthH * 0.45f)
            canvas.drawPath(wavePath1, wavePaint)

            val wavePath2 = Path()
            val wx2 = mouthX + 4.5f.dpToPx()
            wavePath2.moveTo(wx2, by - mouthH * 0.65f)
            wavePath2.quadTo(wx2 + 5.5f.dpToPx(), by, wx2, by + mouthH * 0.65f)
            canvas.drawPath(wavePath2, wavePaint)

            PaintPool.recycle(wavePaint)
        }
    }

    /**
     * 在指定位置绘制删除图标（与目录界面的 ic_outline_delete.xml 一模一样）
     */
    private fun drawDeleteIcon(canvas: Canvas, cx: Float, cy: Float, radius: Float, paint: Paint) {
        val scale = radius / 12f
        val path = Path()

        // === 基于 ic_outline_delete.xml 的 pathData，将壁厚从 2 缩减到 1 ===
        // 第一段：X 叉叉
        path.moveTo(14.12f, 10.47f)
        path.lineTo(12f, 12.59f)
        path.rLineTo(-2.13f, -2.12f)
        path.rLineTo(-1.41f, 1.41f)
        path.lineTo(10.59f, 14f)
        path.rLineTo(-2.12f, 2.12f)
        path.rLineTo(1.41f, 1.41f)
        path.lineTo(12f, 15.41f)
        path.rLineTo(2.12f, 2.12f)
        path.rLineTo(1.41f, -1.41f)
        path.lineTo(13.41f, 14f)
        path.rLineTo(2.12f, -2.12f)
        path.close()

        // 第二段：盖子和提手
        path.moveTo(15.5f, 4f)
        path.rLineTo(-1f, -1f)
        path.rLineTo(-5f, 0f)
        path.rLineTo(-1f, 1f)
        path.lineTo(5f, 4f)
        path.rLineTo(0f, 2f)
        path.rLineTo(14f, 0f)
        path.lineTo(19f, 4f)
        path.close()

        // 第三段：桶身外轮廓
        path.moveTo(6f, 19f)
        path.cubicTo(6f, 20.1f, 6.9f, 21f, 8f, 21f)
        path.rLineTo(8f, 0f)
        path.cubicTo(17.1f, 21f, 18f, 20.1f, 18f, 19f)
        path.lineTo(18f, 7f)
        path.lineTo(6f, 7f)
        path.rLineTo(0f, 12f)
        path.close()

        // 第四段：桶内挖空（坐标内移，壁厚减半至 1，使线条更细）
        path.moveTo(7f, 8f)
        path.rLineTo(10f, 0f)
        path.rLineTo(0f, 11f)
        path.lineTo(7f, 19f)
        path.lineTo(7f, 8f)
        path.close()

        val oldStyle = paint.style
        paint.style = Paint.Style.FILL

        canvas.save()
        canvas.translate(cx - 12f * scale, cy - 12f * scale)
        canvas.scale(scale, scale)
        canvas.drawPath(path, paint)
        canvas.restore()

        paint.style = oldStyle
    }

    fun checkFastDraw(): Boolean {
        if (!AppConfig.optimizeRender || exceed || !onlyTextColumn || textPage.isMsgPage) {
            return false
        }
        if (wordSpacing != 0f && (!atLeastApi26 || !wordSpacingWorking)) {
            return false
        }
        return searchResultColumnCount == 0
    }

    fun invalidate() {
        invalidateSelf()
        textPage.invalidate()
    }

    fun invalidateSelf() {
        canvasRecorder.invalidate()
    }

    fun recycleRecorder() {
        canvasRecorder.recycle()
    }

    @SuppressLint("NewApi")
    companion object {
        val emptyTextLine = TextLine()
        private val atLeastApi26 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        val atLeastApi28 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        private val atLeastApi35 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
        private val wordSpacingWorking by lazy {
            // issue 3785 3846
            val paint = PaintPool.obtain()
            val text = "一二 三"
            val width1 = paint.measureText(text)
            try {
                paint.wordSpacing = 10f
                val width2 = paint.measureText(text)
                width2 - width1 == 10f
            } catch (e: NoSuchMethodError) {
                false
            } finally {
                PaintPool.recycle(paint)
            }
        }
    }

}
