package io.legado.app.ui.book.read.page

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import io.legado.app.R
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.book.isOnLineTxt
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.ui.association.OpenUrlConfirmActivity
import io.legado.app.ui.book.read.page.delegate.PageDelegate
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.TextPos
import io.legado.app.ui.book.read.page.entities.column.BaseColumn
import io.legado.app.ui.book.read.page.entities.column.ButtonColumn
import io.legado.app.ui.book.read.page.entities.column.TextHtmlColumn
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.ReviewColumn
import io.legado.app.ui.book.read.page.entities.column.TextBaseColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.book.read.page.provider.TextPageFactory
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.utils.activity
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.longToastOnUi
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

/**
 * 阅读内容视图
 */
class ContentTextView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    var selectAble = AppConfig.textSelectAble
    val selectedPaint by lazy {
        Paint().apply {
            color = context.getCompatColor(R.color.btn_bg_press_2)
            style = Paint.Style.FILL
        }
    }
    private var callBack: CallBack
    private val visibleRect = ChapterProvider.visibleRect
    val selectStart = TextPos(0, -1, -1)
    private val selectEnd = TextPos(0, -1, -1)
    var textPage: TextPage = TextPage()
        private set
    var isMainView = false
    var longScreenshot = false
    var reverseStartCursor = false
    var reverseEndCursor = false
    private var lastClickTime = 0L
    private var doubleClick = false

    //滚动参数
    private val pageFactory get() = callBack.pageFactory
    private val pageDelegate get() = callBack.pageDelegate
    private var pageOffset = 0
    private var autoPager: AutoPager? = null
    private var isScroll = false
    private val renderRunnable by lazy { Runnable { preRenderPage() } }

    //绘制图片的paint
    val imagePaint by lazy {
        Paint().apply {
            isAntiAlias = AppConfig.useAntiAlias
        }
    }

    init {
        callBack = activity as CallBack
    }

    /**
     * 设置内容
     */
    fun setContent(textPage: TextPage) {
        this.textPage = textPage
        // 非滑动翻页动画需要同步重绘，不然翻页可能会出现闪烁
        if (isScroll) {
            postInvalidate()
        } else {
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!isMainView) return
        ChapterProvider.upViewSize(w, h)
        textPage.format()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        autoPager?.onDraw(canvas)
        if (longScreenshot) {
            canvas.translate(0f, scrollY.toFloat())
        }
        check(!visibleRect.isEmpty) { "visibleRect 为空" }
        canvas.clipRect(visibleRect)
        drawPage(canvas)
    }

    /**
     * 绘制页面
     */
    private fun drawPage(canvas: Canvas) {
        var relativeOffset = relativeOffset(0)
        textPage.draw(this, canvas, relativeOffset)
        if (!callBack.isScroll) return
        //滚动翻页
        if (!pageFactory.hasNext()) return
        val textPage1 = relativePage(1)
        relativeOffset += textPage.height
        textPage1.draw(this, canvas, relativeOffset)
        if (!pageFactory.hasNextPlus()) return
        relativeOffset += textPage1.height
        if (relativeOffset < ChapterProvider.visibleHeight) {
            val textPage2 = relativePage(2)
            textPage2.draw(this, canvas, relativeOffset)
        }
    }

    override fun computeScroll() {
        pageDelegate?.computeScroll()
        autoPager?.computeOffset()
    }

    /**
     * 滚动事件
     * pageOffset 向上滚动 减小 向下滚动 增大
     * pageOffset 范围 0 ~ -textPage.height 大于0为上一页，小于-textPage.height为下一页
     * 以内容显示区域顶端为界，pageOffset的绝对值为textPage上方的高度
     * pageOffset + textPage.height 为 textPage 下方的高度
     */
    fun scroll(mOffset: Int) {
        pageOffset += mOffset
        if (longScreenshot) {
            scrollY += -mOffset
        }
        if (!pageFactory.hasPrev() && pageOffset > 0) {
            pageOffset = 0
            pageDelegate?.abortAnim()
        } else if (!pageFactory.hasNext()
            && pageOffset < 0
            && pageOffset + textPage.height < ChapterProvider.visibleHeight
        ) {
            val offset = (ChapterProvider.visibleHeight - textPage.height).toInt()
            pageOffset = min(0, offset)
            pageDelegate?.abortAnim()
        } else if (pageOffset > 0) {
            if (pageFactory.moveToPrev(true)) {
                pageOffset -= textPage.height.toInt()
            } else {
                pageOffset = 0
                pageDelegate?.abortAnim()
            }
        } else if (pageOffset < -textPage.height) {
            val height = textPage.height
            if (pageFactory.moveToNext(upContent = true)) {
                pageOffset += height.toInt()
            } else {
                pageOffset = -height.toInt()
                pageDelegate?.abortAnim()
            }
        }
        postInvalidate()
    }

    fun submitRenderTask() {
        renderThread.submit(renderRunnable)
    }

    private fun preRenderPage() {
        val view = this
        var invalidate = false
        pageFactory.run {
            if (hasPrev() && prevPage.render(view)) {
                invalidate = true
            }
            if (curPage.render(view)) {
                invalidate = true
            }
            if (hasNext() && nextPage.render(view) && callBack.isScroll) {
                invalidate = true
            }
            if (hasNextPlus() && nextPlusPage.render(view) && callBack.isScroll
                && relativeOffset(2) < ChapterProvider.visibleHeight
            ) {
                invalidate = true
            }
            if (invalidate) {
                postInvalidate()
                pageDelegate?.postInvalidate()
            }
        }
    }

    /**
     * 重置滚动位置
     */
    fun resetPageOffset() {
        pageOffset = 0
    }

    /**
     * 长按
     */
    fun longPress(
        x: Float,
        y: Float,
        select: (textPos: TextPos) -> Unit,
    ) {
        touch(x, y) { _, textPos, _, _, column ->
            when (column) {
                is ImageColumn -> callBack.onImageLongPress(x, y, column.src)
                is TextColumn -> {
                    if (!selectAble) return@touch
                    column.selected = true
                    select(textPos)
                }
                is TextHtmlColumn -> {
                    if (!selectAble) return@touch
                    column.selected = true
                    select(textPos)
                }
            }
        }
    }

    /**
     * 单击
     * @return true:已处理, false:未处理
     */
    @Suppress("UNUSED_ANONYMOUS_PARAMETER")
    fun click(x: Float, y: Float): Boolean {
        val currentTime = System.currentTimeMillis()
        val debounceClick = currentTime - lastClickTime < 300L //300毫秒防抖和双击
        lastClickTime = currentTime
        doubleClick = if (debounceClick) {
            !doubleClick
        } else {
            false
        }
        var handled = false

        // 先检测角色标注点击
        if (!debounceClick && checkRoleAnnotationClick(x, y)) {
            return true
        }

        // 检测音频缓存标记点击（直接删除，不用确认框）
        if (!debounceClick && checkAudioCacheIndicatorClick(x, y)) {
            return true
        }

        // 检测段落末尾小喇叭点击（播放该段落音频）
        if (!debounceClick && checkParagraphSpeakerClick(x, y)) {
            return true
        }

        touch(x, y) { _, textPos, textPage, textLine, column ->
            when (column) {
                is ButtonColumn -> {
                    context.toastOnUi("Button Pressed!")
                    handled = true
                }

                is ReviewColumn -> {
                    context.toastOnUi("Button Pressed!")
                    handled = true
                }

                is ImageColumn -> when (AppConfig.clickImgWay) {
                    "1" -> { //预览图片
                        activity?.showDialogFragment(PhotoDialog(column.src))
                        handled = true
                    }
                    "2" -> { //兼容处理
                        if (!debounceClick) {
                            if (ReadBook.book?.isOnLineTxt == true) {
                                val click = column.click
                                val src = column.src
                                if (!click.isNullOrBlank()) {
                                    callBack.clickImg(click, src)
                                    handled = true
                                } else {
                                    handled = callBack.oldClickImg(src)
                                }
                            }
                        }
                    }
                    "3" -> { //关闭
                        handled = false
                    }
                    "4" -> { //双击
                        if (doubleClick) {
                            val click = column.click
                            if (!click.isNullOrBlank()) {
                                callBack.clickImg(click, column.src)
                                handled = true
                            }
                        } else {
                            handled = true
                        }
                    }
                    else -> { //默认点击
                        if (!debounceClick) {
                            val click = column.click
                            if (!click.isNullOrBlank()) {
                                callBack.clickImg(click, column.src)
                                handled = true
                            }
                        }
                    }
                }
                is TextHtmlColumn -> {
                    column.linkUrl?.let {
                        activity?.startActivity<OpenUrlConfirmActivity> {
                            putExtra("uri", it)
                        }
                        handled = true
                    }

                }
            }
        }
        return handled
    }

    /**
     * 检测是否点击了角色标注区域
     */
    private fun checkRoleAnnotationClick(x: Float, y: Float): Boolean {
        if (!AppConfig.showRoleAnnotation) return false
        if (!visibleRect.contains(x, y)) return false
        var relativeOffset: Float
        for (relativePos in 0..2) {
            relativeOffset = relativeOffset(relativePos)
            if (relativePos > 0) {
                if (!callBack.isScroll) return false
                if (relativeOffset >= ChapterProvider.visibleHeight) return false
            }
            val textPage = relativePage(relativePos)
            for (textLine in textPage.lines) {
                val paint = DialogRoleManager.annotationPaint
                paint.textSize = ChapterProvider.contentPaint.textSize * 0.55f
                val fm = paint.fontMetrics
                val textHeight = fm.descent - fm.ascent
                // 行的y范围扩大到包含上方名字区域
                val lineTouchTop = textLine.lineTop + relativeOffset - textHeight - 12.dpToPx()
                val lineTouchBottom = textLine.lineBottom + relativeOffset
                if (y > lineTouchTop && y < lineTouchBottom) {
                    val snapshot = synchronized(textLine.roleAnnotations) { ArrayList(textLine.roleAnnotations) }
                    for (anno in snapshot) {
                        val targetColumn = textLine.columns[anno.columnIndex]
                        // 扩大点击区域：x 覆盖名字和双引号本身 ±12dp
                        val touchStart = min(anno.labelStart, targetColumn.start) - 12.dpToPx()
                        val touchEnd = max(anno.labelEnd, targetColumn.end) + 12.dpToPx()
                        // y 覆盖名字区域 + 当前行顶部以下 12dp
                        val absTop = textLine.lineTop + relativeOffset - textHeight - 12.dpToPx()
                        val absBottom = textLine.lineTop + relativeOffset + 12.dpToPx()
                        if (x >= touchStart && x <= touchEnd
                            && y >= absTop && y <= absBottom
                        ) {
                            val book = ReadBook.book ?: return false
                            val chapterTitle = textLine.textPage.textChapter.title
                            callBack.showRoleEditDialog(
                                book.name,
                                chapterTitle,
                                anno.seq,
                                anno.fullName,
                                anno.gender,
                                anno.age
                            ) { newName, newGender, newAge ->
                                DialogRoleManager.saveRole(
                                    book.name,
                                    chapterTitle,
                                    anno.seq,
                                    newName,
                                    newGender,
                                    newAge
                                )
                                // 即时更新当前 TextLine 的标注，使重绘立即生效
                                synchronized(textLine.roleAnnotations) {
                                    val idx = textLine.roleAnnotations.indexOf(anno)
                                    if (idx >= 0) {
                                        val label = newName.take(5)
                                        val paint = DialogRoleManager.annotationPaint
                                        paint.textSize = ChapterProvider.contentPaint.textSize * 0.55f
                                        val labelWidth = paint.measureText(label)
                                        val targetColumn = textLine.columns[anno.columnIndex]
                                        val x = targetColumn.start
                                        textLine.roleAnnotations[idx] = anno.copy(
                                            name = label,
                                            fullName = newName,
                                            gender = newGender,
                                            age = newAge,
                                            labelStart = x,
                                            labelEnd = x + labelWidth
                                        )
                                    }
                                }
                                invalidate()
                            }
                            return true
                        }
                    }
                }
            }
        }
        return false
    }

    /**
     * 检测是否点击了音频缓存标记区域（直接删除，不用确认框）
     */
    private fun checkAudioCacheIndicatorClick(x: Float, y: Float): Boolean {
        if (!AppConfig.showAudioCacheIndicator) return false
        if (!visibleRect.contains(x, y)) return false

        var relativeOffset: Float
        for (relativePos in 0..2) {
            relativeOffset = relativeOffset(relativePos)
            if (relativePos > 0) {
                if (!callBack.isScroll) return false
                if (relativeOffset >= ChapterProvider.visibleHeight) return false
            }
            val textPage = relativePage(relativePos)
            for (textLine in textPage.lines) {
                if (!textLine.isTouch(x, y, relativeOffset)) continue
                // 检测缩进字符，在缩进上方区域响应点击（区域扩大）
                val indentColumns = textLine.columns.filter {
                    it is TextColumn && it.charData == ChapterProvider.indentChar
                }
                if (indentColumns.isEmpty()) continue
                val firstIndent = indentColumns.first()
                val lastIndent = indentColumns.last()
                val radius = 4.dpToPx() + 16.dpToPx() // 扩大点击区域
                val cx = (firstIndent.start + lastIndent.end) / 2
                val cy = textLine.lineTop + relativeOffset - 4.dpToPx() - 2.dpToPx()
                if (x < cx - radius || x > cx + radius
                    || y < cy - radius || y > cy + radius
                ) {
                    continue
                }
                if (!textLine.hasAudioCache) {
                    context.toastOnUi("该段无音频缓存")
                    return true
                }
                val deleted = AudioCacheIndicatorManager.deleteCache(
                    textLine.textPage.textChapter, textLine.audioParagraphIndex
                )
                if (deleted) {
                    textLine.hasAudioCache = false
                    invalidate()
                    context.toastOnUi("已删除该段音频缓存")
                }
                return true
            }
        }
        return false
    }

    /**
     * 检测是否点击了段落末尾小喇叭（播放该段落音频）
     */
    private fun checkParagraphSpeakerClick(x: Float, y: Float): Boolean {
        if (!AppConfig.showAudioCacheIndicator) return false
        if (!visibleRect.contains(x, y)) return false

        var relativeOffset: Float
        for (relativePos in 0..2) {
            relativeOffset = relativeOffset(relativePos)
            if (relativePos > 0) {
                if (!callBack.isScroll) return false
                if (relativeOffset >= ChapterProvider.visibleHeight) return false
            }
            val textPage = relativePage(relativePos)
            for (textLine in textPage.lines) {
                if (!textLine.isTouchY(y, relativeOffset)) continue
                if (!textLine.isParagraphEnd || textLine.isTitle || textLine.isImage || textLine.isHtml) continue

                // 喇叭图标区域（行末尾右侧）
                val radius = 5.dpToPx() + 16.dpToPx()
                val cx = textLine.lineEnd + 5.dpToPx() + 6.dpToPx()
                val cy = textLine.lineTop + relativeOffset + (textLine.lineBottom - textLine.lineTop) / 2
                val hRadius = radius * 1.4f // 水平方向扩展 40%
                if (x < cx - hRadius || x > cx + hRadius * 1.4f
                    || y < cy - radius || y > cy + radius
                ) {
                    continue
                }

                val textChapter = textLine.textPage.textChapter
                val paragraphNum = textLine.paragraphNum
                if (paragraphNum <= 0) continue

                // 有缓存则直接播放缓存文件
                val cacheFile = AudioCacheIndicatorManager.getCacheFile(textChapter, paragraphNum)
                if (cacheFile != null) {
                    playCacheFile(cacheFile)
                    context.toastOnUi("播放该段音频")
                } else {
                    // 无缓存：启动单段朗读模式，只合成并播放当前段落
                    val pageIndex = textLine.textPage.index
                    val pageStart = textChapter.getReadLength(pageIndex)
                    // 使用段落第一行的 chapterPosition 计算 startPos，确保从段落开头朗读
                    val paragraphFirstLine = textChapter.getParagraphs(false)
                        .find { it.num == paragraphNum }?.firstLine
                    val paragraphStart = paragraphFirstLine?.chapterPosition ?: textLine.chapterPosition
                    val startPos = paragraphStart - pageStart
                    ReadAloud.play(context, true, pageIndex, startPos.coerceAtLeast(0), singleParagraph = true)
                    context.toastOnUi("生成并播放该段音频")
                }
                return true
            }
        }
        return false
    }

    /**
     * 使用 ExoPlayer 播放单个缓存音频文件
     */
    private fun playCacheFile(file: File) {
        try {
            paragraphPlayer?.release()
            paragraphPlayer = ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
                prepare()
                play()
            }
        } catch (e: Exception) {
            context.longToastOnUi("播放出错：${e.localizedMessage}")
        }
    }

    /**
     * 选择文字
     */
    fun selectText(
        x: Float,
        y: Float,
        select: (textPos: TextPos) -> Unit,
    ) {
        touchRough(x, y) { _, textPos, _, _, column ->
            if (column is TextBaseColumn) {
                column.selected = true
                select(textPos)
            }
        }
    }

    /**
     * 开始选择符移动
     */
    fun selectStartMove(x: Float, y: Float) {
        touchRough(x, y) { _, textPos, _, _, _ ->
            if (selectStart.compare(textPos) == 0) {
                return@touchRough
            }
            if (textPos.compare(selectEnd) <= 0) {
                selectStartMoveIndex(textPos)
            } else {
                touchRough(x - 2 * cursorWidth, y) { _, textPos, _, _, _ ->
                    if (textPos.compare(selectEnd) > 0) {
                        reverseStartCursor = true
                        reverseEndCursor = false
                        selectEnd.columnIndex++
                        selectStartMoveIndex(selectEnd)
                        selectEndMoveIndex(textPos)
                    }
                }
            }
        }
    }

    /**
     * 结束选择符移动
     */
    fun selectEndMove(x: Float, y: Float) {
        touchRough(x, y) { _, textPos, _, _, _ ->
            if (textPos.compare(selectEnd) == 0) {
                return@touchRough
            }
            if (textPos.compare(selectStart) >= 0) {
                selectEndMoveIndex(textPos)
            } else {
                touchRough(x + 2 * cursorWidth, y) { _, textPos, _, _, _ ->
                    if (textPos.compare(selectStart) < 0) {
                        reverseEndCursor = true
                        reverseStartCursor = false
                        selectStart.columnIndex--
                        selectEndMoveIndex(selectStart)
                        selectStartMoveIndex(textPos)
                    }
                }
            }
        }
    }

    /**
     * 触碰位置信息
     * @param touched 回调
     */
    private fun touch(
        x: Float,
        y: Float,
        touched: (
            relativeOffset: Float,
            textPos: TextPos,
            textPage: TextPage,
            textLine: TextLine,
            column: BaseColumn
        ) -> Unit
    ) {
        if (!visibleRect.contains(x, y)) return
        var relativeOffset: Float
        for (relativePos in 0..2) {
            relativeOffset = relativeOffset(relativePos)
            if (relativePos > 0) {
                //滚动翻页
                if (!callBack.isScroll) return
                if (relativeOffset >= ChapterProvider.visibleHeight) return
            }
            val textPage = relativePage(relativePos)
            for ((lineIndex, textLine) in textPage.lines.withIndex()) {
                if (textLine.isTouch(x, y, relativeOffset)) {
                    for ((charIndex, textColumn) in textLine.columns.withIndex()) {
                        if (textColumn.isTouch(x)) {
                            touched.invoke(
                                relativeOffset,
                                TextPos(relativePos, lineIndex, charIndex),
                                textPage, textLine, textColumn
                            )
                            return
                        }
                    }
                    return
                }
            }
        }
    }

    /**
     * 触碰位置信息
     * 文本选择专用
     * @param touched 回调
     */
    private fun touchRough(
        x: Float,
        y: Float,
        touched: (
            relativeOffset: Float,
            textPos: TextPos,
            textPage: TextPage,
            textLine: TextLine,
            column: BaseColumn
        ) -> Unit
    ) {
        var relativeOffset: Float
        for (relativePos in 0..2) {
            relativeOffset = relativeOffset(relativePos)
            if (relativePos > 0) {
                //滚动翻页
                if (!callBack.isScroll) return
                if (relativeOffset >= ChapterProvider.visibleHeight) return
            }
            val textPage = relativePage(relativePos)
            for (lineIndex in textPage.lines.indices) {
                val textLine = textPage.getLine(lineIndex)
                if (textLine.isTouchY(y, relativeOffset)) {
                    if (textPage.doublePage) {
                        val halfWidth = width / 2
                        if (textLine.isLeftLine && x > halfWidth) {
                            continue
                        }
                        if (!textLine.isLeftLine && x < halfWidth) {
                            continue
                        }
                    }
                    val columns = textLine.columns
                    for (charIndex in columns.indices) {
                        val textColumn = columns[charIndex]
                        if (textColumn.isTouch(x)) {
                            touched.invoke(
                                relativeOffset,
                                TextPos(relativePos, lineIndex, charIndex),
                                textPage, textLine, textColumn
                            )
                            return
                        }
                    }
                    val isLast = columns.first().start < x
                    val charIndex = if (isLast) columns.lastIndex + 1 else -1
                    val textColumn = if (isLast) columns.last() else columns.first()
                    touched.invoke(
                        relativeOffset,
                        TextPos(relativePos, lineIndex, charIndex),
                        textPage, textLine, textColumn
                    )
                    return
                }
            }
        }
    }

    fun getCurVisiblePage(): TextPage {
        val visiblePage = TextPage()
        var relativeOffset: Float
        for (relativePos in 0..2) {
            relativeOffset = relativeOffset(relativePos)
            if (relativePos > 0) {
                //滚动翻页
                if (!callBack.isScroll) break
                if (relativeOffset >= ChapterProvider.visibleHeight) break
            }
            val textPage = relativePage(relativePos)
            val lines = textPage.lines
            for (i in lines.indices) {
                val textLine = lines[i]
                if (textLine.isVisible(relativeOffset)) {
                    val visibleLine = textLine.copy().apply {
                        lineTop += relativeOffset
                        lineBottom += relativeOffset
                    }
                    visiblePage.addLine(visibleLine)
                }
            }
        }
        return visiblePage
    }

    fun getReadAloudPos(): Pair<Int, TextLine>? {
        var relativeOffset: Float
        for (relativePos in 0..2) {
            relativeOffset = relativeOffset(relativePos)
            if (relativePos > 0) {
                //滚动翻页
                if (!callBack.isScroll) break
                if (relativeOffset >= ChapterProvider.visibleHeight) break
            }
            val textPage = relativePage(relativePos)
            val lines = textPage.lines
            for (i in lines.indices) {
                val textLine = lines[i]
                if (textLine.isVisible(relativeOffset)) {
                    val visibleLine = textLine.copy().apply {
                        lineTop += relativeOffset
                        lineBottom += relativeOffset
                    }
                    return textPage.chapterIndex to visibleLine
                }
            }
        }
        return null
    }

    /**
     * 选择开始文字
     */
    fun selectStartMoveIndex(
        relativePagePos: Int,
        lineIndex: Int,
        charIndex: Int,
    ) {
        selectStart.relativePagePos = relativePagePos
        selectStart.lineIndex = lineIndex
        selectStart.columnIndex = max(0, charIndex)
        val textLine = relativePage(relativePagePos).getLine(lineIndex)
        val textColumn = textLine.getColumn(charIndex)
        upSelectedStart(
            if (charIndex < textLine.columns.size) textColumn.start else textColumn.end,
            textLine.lineBottom + relativeOffset(relativePagePos),
            textLine.lineTop + relativeOffset(relativePagePos)
        )
        upSelectChars()
    }

    fun selectStartMoveIndex(textPos: TextPos) = textPos.run {
        selectStartMoveIndex(relativePagePos, lineIndex, columnIndex)
    }

    /**
     * 选择结束文字
     */
    fun selectEndMoveIndex(
        relativePage: Int,
        lineIndex: Int,
        charIndex: Int,
    ) {
        selectEnd.relativePagePos = relativePage
        selectEnd.lineIndex = lineIndex
        val textLine = relativePage(relativePage).getLine(lineIndex)
        selectEnd.columnIndex = min(charIndex, textLine.columns.lastIndex)
        val textColumn = textLine.getColumn(charIndex)
        upSelectedEnd(
            if (charIndex > -1) textColumn.end else textColumn.start,
            textLine.lineBottom + relativeOffset(relativePage)
        )
        upSelectChars()
    }

    fun selectEndMoveIndex(textPos: TextPos) = textPos.run {
        selectEndMoveIndex(relativePagePos, lineIndex, columnIndex)
    }

    private fun upSelectChars() {
        if (!selectStart.isSelected() && !selectEnd.isSelected()) {
            return
        }
        val last = if (callBack.isScroll) 2 else 0
        val textPos = TextPos(0, 0, 0)
        for (relativePos in 0..last) {
            textPos.relativePagePos = relativePos
            val textPage = relativePage(relativePos)
            for ((lineIndex, textLine) in textPage.lines.withIndex()) {
                textPos.lineIndex = lineIndex
                for ((charIndex, column) in textLine.columns.withIndex()) {
                    textPos.columnIndex = charIndex
                    if (column is TextBaseColumn) {
                        val compareStart = textPos.compare(selectStart)
                        val compareEnd = textPos.compare(selectEnd)
                        column.selected = compareStart >= 0 && compareEnd <= 0
                        column.isSearchResult =
                            column.selected && callBack.isSelectingSearchResult
                        if (column.isSearchResult) {
                            textPage.searchResult.add(column)
                        }
                    }
                }
            }
        }
        postInvalidate()
    }

    private fun upSelectedStart(x: Float, y: Float, top: Float) {
        callBack.run {
            upSelectedStart(x + imgBgPaddingStart, y + headerHeight, top + headerHeight)
        }
    }

    private fun upSelectedEnd(x: Float, y: Float) {
        callBack.run {
            upSelectedEnd(x + imgBgPaddingStart, y + headerHeight)
        }
    }

    fun resetReverseCursor() {
        reverseStartCursor = false
        reverseEndCursor = false
    }

    fun cancelSelect(clearSearchResult: Boolean = false) {
        val last = if (callBack.isScroll) 2 else 0
        for (relativePos in 0..last) {
            val textPage = relativePage(relativePos)
            textPage.lines.forEach { textLine ->
                textLine.columns.forEach {
                    if (it is TextBaseColumn) {
                        it.selected = false
                        if (clearSearchResult) {
                            it.isSearchResult = false
                            textPage.searchResult.remove(it)
                        }
                    }
                }
            }
        }
        selectStart.reset()
        selectEnd.reset()
        postInvalidate()
        callBack.onCancelSelect()
    }

    fun getSelectedText(): String {
        val textPos = TextPos(0, 0, 0)
        val builder = StringBuilder()
        for (relativePos in selectStart.relativePagePos..selectEnd.relativePagePos) {
            val textPage = relativePage(relativePos)
            textPos.relativePagePos = relativePos
            textPage.lines.forEachIndexed { lineIndex, textLine ->
                textPos.lineIndex = lineIndex
                textLine.columns.forEachIndexed { charIndex, column ->
                    textPos.columnIndex = charIndex
                    val compareStart = textPos.compare(selectStart)
                    val compareEnd = textPos.compare(selectEnd)
                    if (column is TextBaseColumn) {
                        when {
                            compareStart == -1 -> if (
                                selectStart.columnIndex == textLine.columns.size
                                && charIndex == textLine.columns.lastIndex
                            ) {
                                builder.append("\n")
                            }

                            compareEnd == 1 -> if (selectEnd.columnIndex == -1 && charIndex == 0) {
                                builder.append("\n")
                            }

                            compareStart >= 0 && compareEnd <= 0 -> {
                                builder.append(column.charData)
                                if (
                                    textLine.isParagraphEnd
                                    && charIndex == textLine.columns.lastIndex
                                    && compareEnd != 0
                                ) {
                                    builder.append("\n")
                                }
                            }
                        }
                    }
                }
            }
        }
        return builder.toString()
    }

    fun createBookmark(): Bookmark? {
        val page = relativePage(selectStart.relativePagePos)
        page.getTextChapter().let { chapter ->
            ReadBook.book?.let { book ->
                return book.createBookMark().apply {
                    chapterIndex = page.chapterIndex
                    chapterPos = chapter.getReadLength(page.index) +
                            page.getPosByLineColumn(selectStart.lineIndex, selectStart.columnIndex)
                    chapterName = chapter.title
                    bookText = getSelectedText()
                }
            }
        }
        return null
    }

    private fun relativeOffset(relativePos: Int): Float {
        return when (relativePos) {
            0 -> pageOffset.toFloat()
            1 -> pageOffset + textPage.height
            else -> pageOffset + textPage.height + pageFactory.nextPage.height
        }
    }

    fun relativePage(relativePos: Int): TextPage {
        return when (relativePos) {
            0 -> textPage
            1 -> pageFactory.nextPage
            else -> pageFactory.nextPlusPage
        }
    }

    fun setAutoPager(autoPager: AutoPager?) {
        this.autoPager = autoPager
    }

    fun setIsScroll(value: Boolean) {
        isScroll = value
    }

    override fun canScrollVertically(direction: Int): Boolean {
        return callBack.isScroll && pageFactory.hasNext()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                longScreenshot = true
                scrollY = 0
            }

            MotionEvent.ACTION_UP -> {
                longScreenshot = false
                scrollY = 0
            }
        }
        return callBack.onLongScreenshotTouchEvent(event)
    }

    companion object {
        private val renderThread by lazy {
            Executors.newSingleThreadExecutor {
                Thread(it, "TextPageRender")
            }
        }
        private val cursorWidth = 24.dpToPx()
        private var paragraphPlayer: ExoPlayer? = null
    }

    interface CallBack {
        val headerHeight: Int
        val imgBgPaddingStart: Int
        val pageFactory: TextPageFactory
        val pageDelegate: PageDelegate?
        val isScroll: Boolean
        var isSelectingSearchResult: Boolean
        fun upSelectedStart(x: Float, y: Float, top: Float)
        fun upSelectedEnd(x: Float, y: Float)
        fun onImageLongPress(x: Float, y: Float, src: String)
        fun onCancelSelect()
        fun onLongScreenshotTouchEvent(event: MotionEvent): Boolean
        fun oldClickImg(src: String): Boolean
        fun clickImg(click: String, src: String)
        fun showRoleEditDialog(
            bookName: String,
            chapterTitle: String,
            seq: Int,
            name: String,
            gender: String,
            age: String,
            onSave: (String, String, String) -> Unit
        )
    }
}
