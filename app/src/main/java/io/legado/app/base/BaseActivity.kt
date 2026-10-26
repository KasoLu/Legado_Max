package io.legado.app.base

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.util.AttributeSet
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ViewOutlineProvider
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.FrameLayout
import androidx.activity.addCallback
import androidx.core.view.ViewCompat
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.EventBus
import io.legado.app.constant.AppLog
import io.legado.app.constant.Theme
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.config.ReadAloudActivity
import io.legado.app.ui.widget.ReadAloudMiniBarController
import io.legado.app.ui.widget.ReadAloudMiniBarHost
import io.legado.app.ui.widget.TitleBar
import io.legado.app.ui.debuglog.DebugFloatingBallManager
import io.legado.app.ui.debuglog.DebugLogPanelDialog
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.applyBackgroundTint
import io.legado.app.utils.applyOpenTint
import io.legado.app.utils.applyTint
import io.legado.app.utils.disableAutoFill
import io.legado.app.utils.fullScreen
import io.legado.app.utils.dpToPx
import io.legado.app.utils.hideSoftInput
import io.legado.app.utils.observeEvent
import io.legado.app.utils.oneHandDensityScale
import io.legado.app.utils.setLightStatusBar
import io.legado.app.utils.setNavigationBarColorAuto
import io.legado.app.utils.setStatusBarColorAuto
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.toggleSystemBar
import io.legado.app.utils.windowSize

abstract class BaseActivity<VB : ViewBinding>(
    val fullScreen: Boolean = true,
    private val theme: Theme = Theme.Auto,
    private val toolBarTheme: Theme = Theme.Auto,
    private val transparent: Boolean = false,
    private val imageBg: Boolean = true,
    private val showOpenMenuIcon: Boolean = true
) : AppCompatActivity(), ReadAloudMiniBarHost {

    protected abstract val binding: VB
    private var readAloudMiniBarController: ReadAloudMiniBarController? = null

    val isInMultiWindow: Boolean
        @SuppressLint("ObsoleteSdkInt")
        get() {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                isInMultiWindowMode
            } else {
                false
            }
        }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppContextWrapper.wrap(newBase))
    }

    override fun onCreateView(
        parent: View?,
        name: String,
        context: Context,
        attrs: AttributeSet
    ): View? {
        if (AppConst.menuViewNames.contains(name) && parent?.parent is FrameLayout) {
            (parent.parent as View).setBackgroundColor(backgroundColor)
        }
        return super.onCreateView(parent, name, context, attrs)
    }

    @SuppressLint("ObsoleteSdkInt")
    override fun onCreate(savedInstanceState: Bundle?) {
        window.decorView.disableAutoFill()
        initTheme()
        super.onCreate(savedInstanceState)
        setupSystemBar()
        // 预置（inflate 之前）：先确定密度与窗口框最终态，保证首次 inflate/首帧即最终态，杜绝密度混排
        oneHandModeAtLaunch = AppConfig.oneHandMode
        if (AppConfig.oneHandMode) {
            oneHandDensityScale = AppConfig.ONE_HAND_DENSITY_SCALE
            applyOneHandDensityBoost()
            // 单手模式窗口框统一不透明纯黑：不透明合成让旧页面留屏直到新页面首帧完成，消除切页黑闪
            setOneHandFrameBlack(true)
        } else {
            oneHandDensityScale = 1f
            // 防御：新实例的 displayMetrics 可能与进程级共享引用，继承到之前单手实例施加过的密度
            // → inflate 前还原到系统基准（已一致则 no-op），保证普通实例首帧 inflate 即 1.0
            restoreOneHandDensity()
        }
        Log.d(
            "OneHand",
            "pre: mode=${AppConfig.oneHandMode} dm=${resources.displayMetrics.density} " +
                    "sys=${Resources.getSystem().displayMetrics.density}"
        )
        setContentView(binding.root)
        findViewById<ViewGroup>(android.R.id.content)?.let {
            readAloudMiniBarController = ReadAloudMiniBarController(this, this, it)
        }
        wrapRootInHost()
        if (!AppConfig.oneHandMode) upBackgroundImage()
        applyOneHandMode()
        // 关闭本窗口进入（push）时的系统过渡动画：主题 @null 在部分 ROM 上不被遵守，
        // 客户端指令可强制 WMS 不播放动画
        overridePendingTransition(0, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            findViewById<TitleBar>(R.id.title_bar)
                ?.onMultiWindowModeChanged(isInMultiWindowMode, fullScreen)
        }
        onBackPressedDispatcher.addCallback(this) {
            finish()
        }
        observeLiveBus()    // 模板方法：子类覆写 observeLiveBus() 注册事件订阅，自动在 onCreate 中调用
        observeEvent<Int>(EventBus.ALOUD_STATE) {
            refreshReadAloudMiniBar()
        }
        observeEvent<Unit>(EventBus.ONE_HAND_MODE_CHANGED) {
            if (AppConfig.oneHandMode != oneHandModeAtLaunch) {
                // 模式已变化 → 重建（按新密度/窗口框重新 inflate）；就地切换无法改写已 inflate 视图
                recreate()
            } else {
                applyOneHandMode()
            }
        }
        onActivityCreated(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        DebugFloatingBallManager.onActivityResumed(this)
        refreshReadAloudMiniBar()
        // 模式在后台/下层期间已变化且事件未触达 → 重建，按新模式重新 inflate
        if (AppConfig.oneHandMode != oneHandModeAtLaunch) {
            recreate()
            return
        }
        // 已创建的活动切回前台时事件可能未触达（后台不派发），这里无条件重套（幂等）
        applyOneHandMode()
    }

    override fun onPause() {
        super.onPause()
        readAloudMiniBarController?.onPause()
        DebugFloatingBallManager.onActivityPaused(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        DebugFloatingBallManager.onActivityDestroyed(this)
        DebugLogPanelDialog.onActivityDestroyed(this)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        findViewById<TitleBar>(R.id.title_bar)
            ?.onMultiWindowModeChanged(isInMultiWindowMode, fullScreen)
        setupSystemBar()
        applyOneHandMode()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        findViewById<TitleBar>(R.id.title_bar)
            ?.onMultiWindowModeChanged(isInMultiWindow, fullScreen)
        setupSystemBar()
        applyOneHandMode()
    }

    abstract fun onActivityCreated(savedInstanceState: Bundle?)

    final override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val bool = onCompatCreateOptionsMenu(menu)
        menu.applyTint(this, toolBarTheme)
        return bool
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.applyOpenTint(this, showOpenMenuIcon)
        return super.onMenuOpened(featureId, menu)
    }

    open fun onCompatCreateOptionsMenu(menu: Menu) = super.onCreateOptionsMenu(menu)

    final override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            supportFinishAfterTransition()
            return true
        }
        return onCompatOptionsItemSelected(item)
    }

    open fun onCompatOptionsItemSelected(item: MenuItem) = super.onOptionsItemSelected(item)

    open fun initTheme() {
        when (theme) {
            Theme.Transparent -> setTheme(R.style.AppTheme_Transparent)
            Theme.Dark -> {
                setTheme(R.style.AppTheme_Dark)
               if (!oneHandModeActive) window.decorView.applyBackgroundTint(backgroundColor)
            }

            Theme.Light -> {
                setTheme(R.style.AppTheme_Light)
               if (!oneHandModeActive) window.decorView.applyBackgroundTint(backgroundColor)
            }

            else -> {
                if (ColorUtils.isColorLight(primaryColor)) {
                    setTheme(R.style.AppTheme_Light)
                } else {
                    setTheme(R.style.AppTheme_Dark)
                }
               if (!oneHandModeActive) window.decorView.applyBackgroundTint(backgroundColor)
            }
        }
    }

    open fun upBackgroundImage() {
        if (imageBg) {
            try {
                ThemeConfig.getBgImage(this, windowManager.windowSize)?.let { drawable ->
                   window.decorView.background = drawable
                }
            } catch (_: OutOfMemoryError) {
                toastOnUi("背景图片太大,内存溢出")
            } catch (e: Exception) {
                AppLog.put("加载背景出错\n${e.localizedMessage}", e)
            }
        }
    }

    // ==================== 单手模式 ====================

    /** 内容窗口高度 = 0.6 × 全屏高（固定，便于维护） */
    private val ONE_HAND_BOX_H_RATIO = 0.6f
    /** 内容窗口宽高 = 3:5（高:宽 = 5:3，取自示意图比例取整）；宽度 = 高度 × 3/5，超出屏宽时宽度顶满、高度不变 */
    private val ONE_HAND_BOX_WH_RATIO = 3f / 5f
    /** 内容盒底边距占屏高比例（示意图实测 270/3216 ≈ 0.083955） */
    private val ONE_HAND_BOTTOM_MARGIN_RATIO = 270f / 3216f

    private var oneHandModeActive = false
    /** 本实例创建（inflate）时的单手模式状态；与当前设置不一致时须整体重建（就地切换无法改写已 inflate 视图的 px 缓存） */
    private var oneHandModeAtLaunch = false
    /** 单手模式窗口容器（常驻：onCreate 时创建，普通模式保持屏幕等大，单手模式缩小为窗口尺寸，根视图不再搬移） */
    private var oneHandHost: FrameLayout? = null
    /** 当前窗口矩形（屏幕坐标），用于屏蔽窗外触摸 */
    private var oneHandBoxRect: RectF? = null


    /** 用常驻容器包一层活动根视图：单手模式容器缩小为窗口尺寸，普通模式保持屏幕大小，之后根视图永不搬移 */
    private fun wrapRootInHost() {
        val content = findViewById<ViewGroup>(android.R.id.content) ?: return
        val root = content.getChildAt(0) ?: return
        content.removeView(root)
        val host = FrameLayout(this).apply {
            addView(root, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 16f.dpToPx())
                }
            }
        }
        oneHandHost = host
        content.addView(host, 0, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        if (AppConfig.oneHandMode) {
            // 首帧即小窗：用屏幕尺寸同步套窗口几何/圆角（不等 post 变换），
            // 消除"先全屏再缩小"的中间帧（黑闪）；post 变换仅做幂等校验与精确校正
            val dm = Resources.getSystem().displayMetrics
            val sh = dm.heightPixels.toFloat()
            val boxH = (sh * ONE_HAND_BOX_H_RATIO).toInt()
            var boxW = (sh * ONE_HAND_BOX_H_RATIO * ONE_HAND_BOX_WH_RATIO).toInt()
            if (boxW > dm.widthPixels) boxW = dm.widthPixels
            host.layoutParams = FrameLayout.LayoutParams(boxW, boxH).apply {
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                bottomMargin = (sh * ONE_HAND_BOTTOM_MARGIN_RATIO).toInt()
            }
            // 容器背景 = 主题背景色（16dp 圆角与裁剪一致）：页面根视图背景透明的区域透出主题色，
            // 与普通模式一致，亮色主题下窗口不发黑
            val hostBgColor = backgroundColor
            host.background = GradientDrawable().apply {
                setColor(hostBgColor)
                cornerRadius = 16f.dpToPx()
            }
            host.clipToOutline = true
        }
    }

    /**
     * 应用单手模式：内容窗口为高 0.6×屏高、宽:高 = 3:5 的容器，四周 16dp 圆角，
     * 固定于屏幕底部（下边距 8.4% 屏高）；常驻容器缩小为窗口尺寸，活动根视图以窗口尺寸重新布局
     *（系统浮动小窗语义，无位图缩放）；窗口顶部无模拟状态栏（页面内容直接顶到窗口顶部），
     * 容器背景=主题背景色（页面根视图透明处透出主题色，亮色主题下窗口不发黑）；
     * 窗口内密度缩放（AppConfig.ONE_HAND_DENSITY_SCALE，当前值见常量），状态栏与导航栏均隐藏
     *（不用瞬态栏，避免滚动手势让栏重现造成闪烁）；窗外恒为纯黑（内容框背景=黑 + decorView 黑双保险）；
     * 模式切换触发活动整体重建（按新密度/窗口框重新 inflate），此处的就地套设仅作幂等兜底。
     * 关闭时容器恢复屏幕大小。
     *
     * 容器只做平移定位（无缩放矩阵）：框架标准坐标平移完成命中判定，九宫格翻页 / 文本选择 /
     * 图片点击全部自然正确，无需改阅读屏代码。窗口外触摸在 dispatchTouchEvent 拦截（黑区为死区）。
     */
    fun applyOneHandMode() {
        if (!AppConfig.oneHandMode) {
            if (oneHandModeActive) removeOneHandMode()
            return
        }
        oneHandModeActive = true
        // 先装 inset 吞掉监听，再取消内容框随 inset 重排：
        // 保证 inset 变更时内部视图永远收到 0，杜绝其自加状态栏 padding（治模式切换后状态栏空白累加）
        installHostOneHandInsetsListener()
        // 内容框不再随系统栏 inset 重排：手势唤出瞬态栏时只覆盖不改变内容尺寸，避免屏幕抖动
        window.setDecorFitsSystemWindows(false)
        hideSystemBarsNoTransient()
        // 设置窗口内密度缩放（AppConfig.ONE_HAND_DENSITY_SCALE，当前值见常量）：全局 dp/sp 换算系数 + 本活动 Resources，退出时还原
        oneHandDensityScale = AppConfig.ONE_HAND_DENSITY_SCALE
        applyOneHandDensityBoost()
        applyOneHandTransform()
        // 隐藏栏后的 inset 重排是异步的，布局完成后再套一次拿到精确屏尺寸
        window.decorView.post { applyOneHandTransform() }
    }

    /** 隐藏状态栏 + 导航栏，且不启用滑动瞬态栏（瞬态栏会被滑动手势重新唤出导致闪烁） */
    private fun hideSystemBarsNoTransient() {
        WindowCompat.getInsetsController(window, window.decorView).run {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        }
    }

    /** 为单手模式容器安装 inset 吞掉监听：内部视图收不到系统栏 inset；并同步窗口矩形为容器实际窗口内坐标 */
    private fun installHostOneHandInsetsListener() {
        val host = oneHandHost ?: return
        ViewCompat.setOnApplyWindowInsetsListener(host) { v, _ ->
            if (v.width > 0) {
                val p = IntArray(2)
                v.getLocationInWindow(p)
                oneHandBoxRect = RectF(
                    p[0].toFloat(), p[1].toFloat(),
                    (p[0] + v.width).toFloat(), (p[1] + v.height).toFloat()
                )
            }
            WindowInsetsCompat.CONSUMED
        }
    }


    /** 单手模式窗口外围设纯黑（窗口背景 + 内容框 + decorView）；单手模式下恒为不透明纯黑（不透明合成，切页无黑闪） */
    private fun setOneHandFrameBlack(black: Boolean) {
        val c = if (black) Color.BLACK else Color.TRANSPARENT
        window.setBackgroundDrawable(ColorDrawable(c))
        window.decorView.setBackgroundColor(c)
        findViewById<ViewGroup>(android.R.id.content)?.setBackgroundColor(c)
    }

    private fun applyOneHandTransform() {
        val content = findViewById<ViewGroup>(android.R.id.content) ?: return
        val w = content.width
        val h = content.height
        if (w <= 0 || h <= 0) return
        val boxH = h * ONE_HAND_BOX_H_RATIO        // 窗口高 = 0.6 × 全屏高
        var boxW = boxH * ONE_HAND_BOX_WH_RATIO    // 窗口宽 = 高 × 3/5（高宽比 5:3）
        if (boxW > w) {
            // 宽超出屏宽 → 宽取上限，高不变
            boxW = w.toFloat()
        }
        val boxLeft = (w - boxW) / 2f
        val boxTop = h - boxH - h * ONE_HAND_BOTTOM_MARGIN_RATIO
        oneHandBoxRect = RectF(boxLeft, boxTop, boxLeft + boxW, boxTop + boxH)
        // 窗口外围统一不透明纯黑（不透明合成：旧页面留屏直到新页面首帧完成），
        // 每次重排重设保持最终态；窗外恒为纯黑视觉
        setOneHandFrameBlack(true)
        // 窗口容器：16dp 圆角裁剪（屏幕上唯一的非黑区域）+ 主题色背景（页面根视图透明处透出主题色，亮色不发黑）
        val host = oneHandHost ?: return
        host.clipToOutline = true
        host.invalidateOutline()
        val hostBgColor = backgroundColor
        host.background = GradientDrawable().apply {
            setColor(hostBgColor)
            cornerRadius = 16f.dpToPx()
        }
        val params = FrameLayout.LayoutParams(boxW.toInt(), boxH.toInt()).apply {
            // 锚定屏幕底边（内容框底边=屏幕底边，不受状态栏显隐影响）→ 窗口位置恒定
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            bottomMargin = (h * ONE_HAND_BOTTOM_MARGIN_RATIO).toInt()
        }
        // 容器层吞掉全部 inset：全面屏手势瞬态唤出状态栏时内部视图无感知，窗口位置与内容不动
        installHostOneHandInsetsListener()
        host.alpha = 1f
        // 强制重派一轮 inset：子视图收到 0 inset，保持窗口内容位置不变
        ViewCompat.requestApplyInsets(host)
        Log.d("OneHand", "transform: host=${host.left},${host.top} ${host.width}x${host.height} frame=black")
        // 几何未变（onResume 幂等重套）时跳过重排，避免 pop 后下层窗口重现瞬间再排抖动
        val lp = host.layoutParams
        val unchanged = lp is FrameLayout.LayoutParams
            && lp.width == params.width && lp.height == params.height
            && lp.gravity == params.gravity && lp.bottomMargin == params.bottomMargin
        if (!unchanged) {
            host.layoutParams = params
            // 根视图按窗口尺寸重新布局（系统浮窗语义）
            host.requestLayout()
        }
    }


    /**
     * 设置窗口内密度缩放（AppConfig.ONE_HAND_DENSITY_SCALE，当前值见常量）：
     * 基准永远取系统级 displayMetrics（本 app 永不修改），每次进入（含主题切换重建后）都从系统基准直接施加，
     * 天然幂等、不随活动重建累加；代码计算的 dp/sp 换算由 oneHandDensityScale（ConvertExtensions）统一承担
     */
    private fun applyOneHandDensityBoost() {
        val sys = Resources.getSystem().displayMetrics
        val dm = resources.displayMetrics
        val scale = AppConfig.ONE_HAND_DENSITY_SCALE
        if (dm.density != sys.density * scale) {
            dm.density = sys.density * scale
            dm.scaledDensity = sys.scaledDensity * scale
            val config = Configuration(resources.configuration)
            config.densityDpi = (sys.densityDpi * scale).toInt()
            resources.updateConfiguration(config, dm)
            window.decorView.requestLayout()
            Log.d("OneHand", "densityBoost: density=${dm.density} scaled=${dm.scaledDensity} dpi=${config.densityDpi}")
        }
    }

    /** 退出单手模式时把密度还原到系统基准 */
    private fun restoreOneHandDensity() {
        val sys = Resources.getSystem().displayMetrics
        val dm = resources.displayMetrics
        if (dm.density == sys.density && dm.scaledDensity == sys.scaledDensity
            && resources.configuration.densityDpi == sys.densityDpi
        ) {
            return
        }
        dm.density = sys.density
        dm.scaledDensity = sys.scaledDensity
        val config = Configuration(resources.configuration)
        config.densityDpi = sys.densityDpi
        resources.updateConfiguration(config, dm)
        window.decorView.requestLayout()
        Log.d("OneHand", "density restored: ${dm.density} dpi=${config.densityDpi}")
    }
    private fun removeOneHandMode() {
        oneHandModeActive = false
        restoreOneHandDensity()
        oneHandDensityScale = 1f
        window.setDecorFitsSystemWindows(true)
        val content = findViewById<ViewGroup>(android.R.id.content) ?: return
        val host = oneHandHost
        if (host != null) {
            // 容器恢复普通模式：屏幕等大、无圆角裁剪、无背景
            host.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            host.clipToOutline = false
            host.background = null
            ViewCompat.setOnApplyWindowInsetsListener(host, null)
            // 重派一轮真实 inset，让子视图清掉自加的状态栏 padding，恢复正常布局
            ViewCompat.requestApplyInsets(host)
            host.alpha = 1f
        }
        oneHandBoxRect = null
        content.background = null
        window.decorView.background = null
        window.setBackgroundDrawable(null)
        initTheme()
        upBackgroundImage()
        toggleSystemBar(true)
    }

    open fun setupSystemBar() {
        if (fullScreen && !isInMultiWindow) {
            fullScreen()
        }
        val isTransparentStatusBar = AppConfig.isTransparentStatusBar
        val statusBarColor = ThemeStore.statusBarColor(this, isTransparentStatusBar)
        setStatusBarColorAuto(statusBarColor, isTransparentStatusBar, fullScreen)
        if (toolBarTheme == Theme.Dark) {
            setLightStatusBar(false)
        } else if (toolBarTheme == Theme.Light) {
            setLightStatusBar(true)
        }
        upNavigationBarColor()
    }

    open fun upNavigationBarColor() {
        if (AppConfig.immNavigationBar) {
            setNavigationBarColorAuto(ThemeStore.navigationBarColor(this))
        } else {
            val nbColor = ColorUtils.darkenColor(ThemeStore.navigationBarColor(this))
            setNavigationBarColorAuto(nbColor)
        }
    }

    /**
     * 事件订阅入口（模板方法）
     *
     * 子类覆写此方法，调用 observeEvent() / observeEventSticky() 注册感兴趣的事件。
     * 由 BaseActivity.onCreate() 自动调用，无需手动触发。
     * 观察者与 Activity 生命周期绑定，销毁时自动移除，无需手动注销。
     *
     * 示例：
     *   override fun observeLiveBus() {
     *       observeEvent<String>(EventBus.BOOKSHELF_REFRESH) { refreshBookshelf() }
     *   }
     */
    open fun observeLiveBus() {
    }

    protected fun refreshReadAloudMiniBar() {
        readAloudMiniBarController?.refresh()
    }

    protected fun hideReadAloudMiniBar() {
        readAloudMiniBarController?.hide()
    }

    open override fun showReadAloudMiniBar(): Boolean = AppConfig.readAloudFloatingUi

    open override fun lockReadAloudMiniBarPosition(): Boolean = false

    open override fun readAloudMiniBarBottomMarginDp(): Int = 76

    open override fun defaultReadAloudMiniBarColor(): Int = 0xFF665185.toInt()

    open override fun onReadAloudMiniBarClick() {
        BaseReadAloudService.activeBookUrl?.let { bookUrl ->
            startActivity<ReadBookActivity> {
                putExtra("bookUrl", bookUrl)
            }
        } ?: ReadBook.book?.let { book ->
            startActivity<ReadBookActivity> {
                putExtra("bookUrl", book.bookUrl)
            }
        } ?: startActivity<ReadAloudActivity>()
    }

    open override fun onReadAloudMiniBarLongClick(): Boolean = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // 单手模式下窗口外的触摸直接消费（黑区为死区），避免误触发窗口外的内容
        if (oneHandModeActive) {
            val rect = oneHandBoxRect
            if (rect != null && !rect.contains(ev.x, ev.y)) return true
        }
        return try {
            super.dispatchTouchEvent(ev)
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
            false
        }
    }

    override fun finish() {
        currentFocus?.hideSoftInput()
        super.finish()
        // 关闭本窗口退出（pop）时的系统过渡动画
        overridePendingTransition(0, 0)
    }
}
