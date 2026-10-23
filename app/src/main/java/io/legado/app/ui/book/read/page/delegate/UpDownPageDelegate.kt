package io.legado.app.ui.book.read.page.delegate

import android.os.Handler
import android.os.Looper
import android.graphics.Canvas
import android.view.MotionEvent
import io.legado.app.R
import io.legado.app.utils.LogUtils
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.book.read.page.entities.PageDirection
import io.legado.app.ui.book.read.page.provider.ChapterProvider

/**
 * 上下分屏动画(上下翻页)，"卫生纸/卷轴"模型：
 * 整条文字卷轴按半个正文区高度切段、段间画分割线(压痕)。
 * 只在九宫格上下按钮点击时触发"整条卷轴平移一截"的离散动画(不是一屏、无自由拖拽/跟手)。
 * 渲染由 ContentTextView 的连续卷轴 drawPage 负责(随 pageOffset 一起滚动，段间带分割线)，
 * 本委托只驱动 pageOffset 平移一截，动画结束后触发翻页。
 */
class UpDownPageDelegate(readView: ReadView) : PageDelegate(readView) {

    /** 一截的高度 = 上半节高度(严格半屏；16dp 是分割线视觉间距，不占滚动步长) */
    private val scrollDist get() = ChapterProvider.upDownSplitY
    /** 上一帧 scroller.currY，用于算每帧增量(不让 pageFactory 修正 offset 影响步长) */
    private var lastCurrY = 0
    /** 补帧驱动：draw 调度丢帧时每 16ms 直接 invalidate 一帧 */
    private val driveHandler = Handler(Looper.getMainLooper())
    private val driveTick = object : Runnable {
        override fun run() {
            if (isStarted && !scroller.isFinished) {
                readView.invalidate()
                driveHandler.postDelayed(this, 16)
            }
        }
    }

    override fun onTouch(event: MotionEvent) {
        // 不做自由滚动/跟手；按下也不取消进行中的滚动——
        // 否则按下的瞬间内容会被强制弹回原位(表现为"按一下就变"的无动画跳变)。
        // 滚动统一由抬手(九宫格点击)触发，按下对滚动无任何影响。
    }

    override fun onAnimStart(animationSpeed: Int) {
        if (mDirection == PageDirection.NONE) return
        lastCurrY = 0
        val dy = if (mDirection == PageDirection.NEXT) -scrollDist.toInt() else scrollDist.toInt()
        scroller.startScroll(0, 0, 0, dy, animationSpeed)
        isRunning = true
        isStarted = true
        readView.invalidate()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            // 连续 scrollview：每帧只按 scroller 增量走(scroll(delta)，越过整页边界时按需推进 pageFactory)，
            // 不用绝对目标值——否则 pageFactory 修正 offset 会把下一帧增量撑大，越滚越多。
            val delta = scroller.currY - lastCurrY
            lastCurrY = scroller.currY
            if (delta != 0) {
                curPage.scroll(delta.toInt())
            }
            // 无条件续帧：首帧与 startScroll 同毫秒时 currY=0、delta=0，
            // 若依赖 scroll() 里的 postInvalidate 续帧，动画会在这帧之后静默死亡
            curPage.postInvalidate()
            // 兜底驱动：postInvalidateOnAnimation 的 ANIMATION 回调帧实测会整段丢失
            // (死帧后 1 秒无任何绘制)，改用 Handler 每 16ms 直接 invalidate 补帧直到动画结束
            driveHandler.postDelayed(driveTick, 16)
        } else if (isStarted) {
            driveHandler.removeCallbacks(driveTick)
            onAnimStop()
            stopScroll()
        }
    }

    override fun onDraw(canvas: Canvas) {
        // 内容(连续卷轴+分割线)由 ContentTextView 依 pageOffset 自行绘制，委托不画位图
    }

    override fun onAnimStop() {
        // 连续 scrollview：offset 已平移一截，pageFactory 在 scroll() 里按需推进。
        // 不 fillPage 换页、不归零 offset，避免和连续渲染的坐标系打架。
    }

    override fun abortAnim() {
driveHandler.removeCallbacks(driveTick)
        isStarted = false
        isMoved = false
        isRunning = false
        if (!scroller.isFinished) {
            scroller.abortAnimation()
        }
        // scrollview 模型不弹回原位：中止动画只是停止平移，内容停在当前 offset
        setDirection(PageDirection.NONE)
    }

    /** 九宫格"下一截"按钮：向上滚一截(内容上移，露出下方内容) */
    override fun nextPageByAnim(animationSpeed: Int) {
        abortAnim()
driveHandler.removeCallbacks(driveTick)
        if (!hasNext()) return
        //占位节在视野内:锁定向下滚动,只能向上翻
        if (readView.isUpDownPlaceholderVisible()) {
            //占位节可见期间锁定向下:点按不静默,给出加载反馈,数据到位后自动切换
            showSnackBar(R.string.next_chapter_loading)
            return
        }
        setDirection(PageDirection.NEXT)
        readView.setStartPoint(viewWidth * 0.5f, viewHeight * 0.5f, false)
        onAnimStart(animationSpeed)
    }

    /** 九宫格"上一截"按钮：向下滚一截(内容下移，露出上方内容) */
    override fun prevPageByAnim(animationSpeed: Int) {
        abortAnim()
        if (!hasPrev()) return
        setDirection(PageDirection.PREV)
        readView.setStartPoint(viewWidth * 0.5f, viewHeight * 0.5f, false)
        onAnimStart(animationSpeed)
    }
}
