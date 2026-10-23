package io.legado.app.ui.book.read.page

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import io.legado.app.R
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.book.isOnLineTxt
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
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
import io.legado.app.utils.LogUtils
import io.legado.app.utils.LinkAnchor
import io.legado.app.utils.activity
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

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

    //滚动参数
    private val pageFactory get() = callBack.pageFactory
    private val pageDelegate get() = callBack.pageDelegate
    var pageOffset = 0
        private set
    private var autoPager: AutoPager? = null
    private var isScroll = false
    private val renderRunnable by lazy { Runnable { preRenderPage() } }
    private var lastClickTime = 0L
    private var doubleClick = false
    private val activeAnimatedColumns = Collections.newSetFromMap(IdentityHashMap<ImageColumn, Boolean>())
    private val drawingAnimatedColumns = Collections.newSetFromMap(IdentityHashMap<ImageColumn, Boolean>())

    //绘制图片的paint
    val imagePaint by lazy {
        Paint().apply {
            isAntiAlias = AppConfig.useAntiAlias
        }
    }

    /** 上下分屏的分割线(卫生纸压痕)，作为内容的一部分随内容一起滚动 */
    private val upDownDividerPaint = Paint().apply {
        strokeWidth = 1.dpToPx().toFloat()
        pathEffect = DashPathEffect(floatArrayOf(10f.dpToPx(), 8f.dpToPx()), 0f)
    }
    /** 上下分屏盒底"渐变到透明"过渡遮罩：覆盖溢出内容，提示下方还有内容 */
    private val upDownFadePaint = Paint()

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
        drawingAnimatedColumns.clear()
        autoPager?.onDraw(canvas)
        if (longScreenshot) {
            canvas.translate(0f, scrollY.toFloat())
        }
        check(!visibleRect.isEmpty) { "visibleRect 为空" }
        canvas.clipRect(visibleRect)
        drawPage(canvas)
        syncAnimatedColumns()
    }

    /**
     * 绘制页面
     */
    private fun drawPage(canvas: Canvas) {
        if (ChapterProvider.upDownPage && !isFullPageText(textPage)) {
            // 整屏占位页(旧机制,不变):首开/无缓存整体重排时当前章没有任何真实页,
            // 整条卷轴都是占位,保持原整屏居中"数据加载中";其余情况走下面的分节渲染
            val curChapter = ReadBook.curTextChapter
            if (textPage.isLazyLoading && (curChapter == null || curChapter.pageSize == 0)) {
                textPage.draw(this, canvas, 0f)
                return
            }
            // 连续文字卷轴：上截+当前截+下截+下下截，段间画分割线(压痕)，整条一起平移
            val rel = relativeOffset(0) + upDownShiftY
            // 节距 = 一节(严格半屏)；16dp 是节内"内容到分割线"的间距，不是节与节之间的间距
            val gap = ChapterProvider.upDownSplitY
            if (pageFactory.hasPrev()) {
                val prev = relativePage(-1)
                if (prev.isLazyLoading) drawUpDownPlaceholderSegment(canvas, rel - gap)
                else drawUpDownSegment(canvas, prev, rel - gap)
            }
            if (textPage.isLazyLoading) drawUpDownPlaceholderSegment(canvas, rel)
            else drawUpDownSegment(canvas, textPage, rel)
            if (pageFactory.hasNext()) {
                val next = relativePage(1)
                if (next.isLazyLoading) drawUpDownPlaceholderSegment(canvas, rel + gap)
                else drawUpDownSegment(canvas, next, rel + gap)
            }
            if (pageFactory.hasNextPlus()) {
                val nextPlus = relativePage(2)
                if (nextPlus.isLazyLoading) drawUpDownPlaceholderSegment(canvas, rel + 2f * gap)
                else drawUpDownSegment(canvas, nextPlus, rel + 2f * gap)
            }
            return
        }
        // 懒加载页固定从0画(整屏)，不受滚动偏移影响
        var relativeOffset = if (textPage.isLazyLoading) 0f else relativeOffset(0)
        textPage.draw(this, canvas, relativeOffset)
        // 懒加载页只画自己(整屏)，不画相邻节，避免被"分割"
        if (textPage.isLazyLoading) return
        if (!callBack.isScroll) return
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

    /**
     * 绘制上下分屏的分割线(卫生纸压痕)。画在内容坐标系 upDownSplitY 处，
     * 作为内容的一部分，翻页时随内容一起上移/下移。
     */
    /**
     * 绘制上下分屏的一个截：裁剪到该截盒子内，防止富文本块(作者说/评论等)溢出到相邻截；
     * 并在截底边画分割线(卫生纸压痕)。[off] 为该截内容顶相对 paddingTop 的偏移。
     */
    private fun drawUpDownSegment(canvas: Canvas, page: TextPage, off: Float) {
        val top = ChapterProvider.paddingTop + off
        val boxBottom = top + ChapterProvider.upDownSplitY - ChapterProvider.upDownMargin
        canvas.save()
        canvas.clipRect(0f, top, width.toFloat(), boxBottom)
        page.draw(this, canvas, off)
        canvas.restore()
        // 溢出渐隐：仅当内容真正越过节高(page.height > upDownSplitY，不含padding)时，从分割线向上渐变到透明；纯文本按行填满不越界→不显示
        if (page.height > ChapterProvider.upDownSplitY) {
            val fadeH = 24.dpToPx().toFloat()
            val dividerY = top + ChapterProvider.upDownSplitY
            upDownFadePaint.shader = LinearGradient(
                0f, dividerY - fadeH, 0f, dividerY,
                Color.TRANSPARENT, ReadBookConfig.bgMeanColor, Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, dividerY - fadeH, width.toFloat(), dividerY, upDownFadePaint)
        }
        drawUpDownDivider(canvas, top + ChapterProvider.upDownSplitY)
    }

    /**
     * 在内容坐标系的 [y] 处绘制上下分屏的分割线(卫生纸压痕)，随内容一起滚动。
     */
    private fun drawUpDownDivider(canvas: Canvas, y: Float) {
        val bgColor = ReadBookConfig.bgMeanColor
        val lum = 0.299f * Color.red(bgColor) + 0.587f * Color.green(bgColor) +
                0.114f * Color.blue(bgColor)
        upDownDividerPaint.color = if (lum > 128) 0x80000000.toInt() else 0x99FFFFFF.toInt()
        canvas.drawLine(0f, y, width.toFloat(), y, upDownDividerPaint)
    }
    /**
     * 占位节(上下分屏新增机制,区别于旧的 isLazyLoading 整屏占位页):
     * 卷轴末尾内容尚未加载时,该节按普通一节渲染——节顶"数据加载中"文案 + 节底分割线,
     * 文案用排版正文同款 contentPaint(字号/字体/颜色天然一致),随滚动一起流动;
     * 可见期间向下滚动被锁(见 ReadView.isUpDownPlaceholderVisible)。
     */
    private fun drawUpDownPlaceholderSegment(canvas: Canvas, off: Float) {
        val top = ChapterProvider.paddingTop + off
        val boxBottom = top + ChapterProvider.upDownSplitY - ChapterProvider.upDownMargin
        canvas.save()
        canvas.clipRect(0f, top, width.toFloat(), boxBottom)
        canvas.drawText(
            "加载中",
            ChapterProvider.paddingLeft.toFloat(),
            top + ChapterProvider.upDownMargin - ChapterProvider.contentPaintFontMetrics.ascent,
            ChapterProvider.contentPaint
        )
        canvas.restore()
        drawUpDownDivider(canvas, top + ChapterProvider.upDownSplitY)
    }

    /**
     * 判断页面是否为整页全屏图(封面页)，整页图不做上下分屏
     */
    private fun isFullPageText(textPage: TextPage): Boolean {
        if (textPage.lineSize != 1) return false
        val line = textPage.lines[0]
        return line.isImage && line.height >= ChapterProvider.visibleHeight
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
    /**
     * 翻页滚动步长/边界：一律用整页高。
     * scroll() 的翻页边界和 offset 修正都用它——offset 越过一整页才推进 pageFactory。
     * 上下分屏的"一截"位移步长由 UpDownPageDelegate.scrollDist 单独定义，不在此处。
     */
    private val scrollHeight: Float
        // 上下分屏：一节间距=严格半屏(upDownSplitY)，跨页修正按它，与渲染节距/滚动步长一致；
        // 其他模式仍用整页高
        get() = if (ChapterProvider.upDownPage) ChapterProvider.upDownSplitY else textPage.height
    fun scroll(mOffset: Int) {
        val oldOffset = pageOffset
        pageOffset += mOffset
        if (longScreenshot) {
            scrollY += -mOffset
        }
        if (!pageFactory.hasPrev() && pageOffset > 0) {
            pageOffset = 0
            pageDelegate?.abortAnim()
        } else if (!pageFactory.hasNext()
            && pageOffset < 0
            && pageOffset + scrollHeight < ChapterProvider.visibleHeight
        ) {
            val offset = (ChapterProvider.visibleHeight - scrollHeight).toInt()
            pageOffset = min(0, offset)
            pageDelegate?.abortAnim()
        } else if (pageOffset > 0) {
            if (pageFactory.moveToPrev(true)) {
                pageOffset -= scrollHeight.toInt()
            } else {
                pageOffset = 0
                pageDelegate?.abortAnim()
            }
        } else if (pageOffset < -scrollHeight) {
            val height = scrollHeight
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

    internal fun registerAnimatedColumn(column: ImageColumn) {
        drawingAnimatedColumns.add(column)
    }

    private fun syncAnimatedColumns() {
        val iterator = activeAnimatedColumns.iterator()
        while (iterator.hasNext()) {
            val column = iterator.next()
            if (!drawingAnimatedColumns.contains(column)) {
                column.detachAnimatedView()
                iterator.remove()
            }
        }
        drawingAnimatedColumns.forEach { column ->
            if (activeAnimatedColumns.add(column)) {
                column.attachAnimatedView(this)
            }
        }
    }

    override fun onDetachedFromWindow() {
        activeAnimatedColumns.forEach { it.detachAnimatedView() }
        activeAnimatedColumns.clear()
        drawingAnimatedColumns.clear()
        super.onDetachedFromWindow()
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
                        activity?.showDialogFragment(PhotoDialog(column.src, isBook = true))
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
                    column.linkUrl?.let { url ->
                        if (url.startsWith(LinkAnchor.IN_BOOK_SCHEME)) { //书内链接：跳转到书内目标位置
                            callBack.onInBookLink(url.removePrefix(LinkAnchor.IN_BOOK_SCHEME))
                        } else {
                            activity?.startActivity<OpenUrlConfirmActivity> {
                                putExtra("uri", url)
                            }
                        }
                        handled = true
                    }
                }
            }
        }
        return handled
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
            relativeOffset = touchOffset(relativePos)
            if (relativePos > 0) {
                //滚动翻页
                if (!callBack.isScroll && !ChapterProvider.upDownPage) return
                if (relativeOffset >= ChapterProvider.visibleHeight) return
            }
            //上下分屏:触点在本节盒子底部以下(内容超出节高的溢出区/节间缝隙),本节任何行都不应命中
            if (ChapterProvider.upDownPage &&
                y - relativeOffset > ChapterProvider.paddingTop + ChapterProvider.upDownSplitY - ChapterProvider.upDownMargin
            ) continue
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
            relativeOffset = touchOffset(relativePos)
            if (relativePos > 0) {
                //滚动翻页
                if (!callBack.isScroll && !ChapterProvider.upDownPage) return
                if (relativeOffset >= ChapterProvider.visibleHeight) return
            }
            //上下分屏:触点在本节盒子底部以下(溢出区/节间缝隙),本节任何行都不应命中(选择同源)
            if (ChapterProvider.upDownPage &&
                y - relativeOffset > ChapterProvider.paddingTop + ChapterProvider.upDownSplitY - ChapterProvider.upDownMargin
            ) continue
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

    /**
     * 上下分屏：判断页面是否为整页全屏图(封面页)
     */
    private fun isFullPage(textPage: TextPage): Boolean {
        if (textPage.lineSize != 1) return false
        val line = textPage.lines[0]
        return line.isImage && line.height >= ChapterProvider.visibleHeight
    }
    fun getCurVisiblePage(): TextPage {
        val visiblePage = TextPage()
        var relativeOffset: Float
        for (relativePos in 0..2) {
            relativeOffset = touchOffset(relativePos)
            if (relativePos > 0) {
                //滚动翻页
            if (!callBack.isScroll && !ChapterProvider.upDownPage) break
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
            relativeOffset = touchOffset(relativePos)
            if (relativePos > 0) {
                //滚动翻页
            if (!callBack.isScroll && !ChapterProvider.upDownPage) break
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
            textLine.lineBottom + touchOffset(relativePagePos),
            textLine.lineTop + touchOffset(relativePagePos)
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
            textLine.lineBottom + touchOffset(relativePage)
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
        val last = if (callBack.isScroll || ChapterProvider.upDownPage) 2 else 0
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
                        column.isCurrentSearchResult = column.isSearchResult
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
        val last = if (callBack.isScroll || ChapterProvider.upDownPage) 2 else 0
        for (relativePos in 0..last) {
            val textPage = relativePage(relativePos)
            textPage.lines.forEach { textLine ->
                textLine.columns.forEach {
                    if (it is TextBaseColumn) {
                        it.selected = false
                        if (clearSearchResult) {
                            it.isSearchResult = false
                            it.isCurrentSearchResult = false
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
    /**
     * 触摸命中测试的偏移：上下分屏渲染是固定节距(pageOffset + N * upDownSplitY)，
     * 与累计页高不同，触摸必须用同一坐标系才能命中；其他模式沿用累计页高。
     */
    private fun touchOffset(relativePos: Int): Float {
        return if (ChapterProvider.upDownPage) {
            pageOffset + upDownShiftY + relativePos * ChapterProvider.upDownSplitY
        } else {
            relativeOffset(relativePos)
        }
    }
    /**
     * 上下分屏:本节无文本行(纯图片/仅本章说入口等)时沿用的上一次行高;
     * 从未有过文本(如开书第一节即图片)保持0。
     */
    private var lastUpDownLineHeight: Float = 0f

    /**
     * 上下分屏 viewport 下移量 = 当前页第一行文本的行高 + 2 倍分割线内边距(upDownMargin)。
     * 加 1 倍 padding 后顶部是完整一行 + 空隙；再加 1 倍，尾行上方还多出一段边距，
     * 顶部分割线距正文顶部一行高 + 2*padding，观感上尾行不与屏幕边缘相贴。
     * 内容与分割线整体下移该值（渲染基准与触摸命中同量偏移），滚动步长与分页逻辑不变。
     * 兜底:本节无文本行时行高部分沿用 lastUpDownLineHeight，从未有过则取0，最终偏移仍含两倍 padding。
     */

    private val upDownShiftY: Float
        get() = if (ChapterProvider.upDownPage) {
            val lineHeight = textPage.lines
                .firstOrNull { it.columns.any { c -> c is TextColumn } }
                ?.height ?: lastUpDownLineHeight
            if (lineHeight > 0f) lastUpDownLineHeight = lineHeight
            lineHeight + 2f * ChapterProvider.upDownMargin
        } else 0f

    fun relativePage(relativePos: Int): TextPage {
        return when (relativePos) {
            -1 -> pageFactory.prevPage
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
        fun onInBookLink(ref: String)
    }
}
