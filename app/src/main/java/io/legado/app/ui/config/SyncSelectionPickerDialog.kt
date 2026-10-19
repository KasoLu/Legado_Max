package io.legado.app.ui.config

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import androidx.appcompat.app.AppCompatDialog
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.help.storage.SyncSelection
import io.legado.app.help.storage.ValidationResult
import io.legado.app.help.storage.ValidationState
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.isDarkTheme
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.utils.dpToPx
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

/**
 * 备份/恢复内容树选择入口模式。
 *
 * SETTINGS：设置页入口（点「确定」保存）；
 * ACTION：备份/恢复前置面板（确认后保存并继续执行）
 */
enum class SyncSelectionDialogMode { SETTINGS, ACTION }

/**
 * 仅在垂直位移超过阈值后才接管滚动手势的 ScrollView（第十轮输入加固，保留）。
 *
 * 标准 ScrollView 在低端设备（用户实机 BF61，Android 10 + 联发科，触摸面板抖动、
 * 输入事件偶发丢失）上，按下后出现微小位移即经 onInterceptTouchEvent 接管并取消
 * 子视图（复选框/行）的按压手势，导致其点击永不触发。
 * 此处仅当垂直位移超过阈值才接管滚动：抖动/小幅位移的点击可正常完成，真实滑动仍可滚动。
 */
private class JitterSafeScrollView(context: Context) : ScrollView(context) {
    private val interceptThreshold = 40f * context.resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            // DOWN/UP/CANCEL 必须原样转发给父类：ScrollView 在 ACTION_DOWN 时初始化内部滚动
            // 状态（按下坐标/子视图基准），不转发则从子视图开始的手势永远无法被接管成滚动
            // （091221 版漏转发 DOWN，导致面板完全无法滑动，091222 修复）。
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; super.onInterceptTouchEvent(ev) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { super.onInterceptTouchEvent(ev) }
            MotionEvent.ACTION_MOVE -> {
                val dx = Math.abs(ev.x - downX)
                val dy = Math.abs(ev.y - downY)
                if (dy > interceptThreshold && dy > dx) return super.onInterceptTouchEvent(ev)
                // 小幅度抖动：不让标准 touchSlop 逻辑提前接管而取消子视图点击
            }
        }
        return false
    }
}

/**
 * 备份/恢复内容树选择对话框（第十六轮全新重写，纯 View 实现）。
 *
 * 交互层保留前几轮在实机上验证可用的机制（BF61/联发科实机日志定案，该设备触摸抖动、
 * 输入事件丢失/重放）：
 * - 复选框「按下（ACTION_DOWN）即触发」并接管整个手势——即使后续 MOVE/UP 被截断或丢失，
 *   切换也已落地；
 * - [JitterSafeScrollView] 仅在垂直位移超过阈值时才接管滚动；
 * - 同一项 500ms 内的再次切换视为重复触摸事件，忽略并记录日志；
 * - 保存窗口（[saving]）内吞掉一切状态变更，防止触摸重放改写写入值；
 * - 全链路日志（本实例 [opTag] + 单调递增 [seq]）：每次 UI 操作与保存动作均带前后值，
 *   与 SyncSelection 侧的写入日志首尾相接，可完整重建时间线。
 *
 * 数据层（第十六轮全新）：面板私有快照 leafState/advState 打开时取自 [SyncSelection.snapshot]
 * （现读现校验，任何存疑的文件按纯默认）；点「确定」时把私有快照传给
 * [SyncSelection.confirm]（唯一写盘路径，内容只由快照序列化，不读任何共享态）。
 * 「阅读样式」条目已整体移除——阅读样式永不参与多机同步。
 *
 * @param mode 见 [SyncSelectionDialogMode]
 * @param zipFileNames 待恢复备份内的文件名集合（备份场景传 null），用于标记「备份中不存在」
 * @param onValidate 触发格式检测（对勾选叶子 ∩ 备份内文件的列表执行校验，宿主侧挂协程）
 * @param onActionConfirmed ACTION 模式确认回调（状态回写完成后触发，宿主执行备份/恢复）
 * @param onDismissed 对话框关闭后回调（宿主刷新摘要等）
 */
class SyncSelectionPickerDialog(
    private val activity: Activity,
    private val mode: SyncSelectionDialogMode,
    private val zipFileNames: Set<String>? = null,
    private val onValidate: (List<String>) -> Unit = {},
    private val onActionConfirmed: () -> Unit = {},
    private val onDismissed: () -> Unit = {}
) : AppCompatDialog(activity) {

    private val handler = Handler(Looper.getMainLooper())

    private val leafNodes = SyncSelection.nodes.filter { it.type == SyncSelection.Type.LEAF }
    private val advNodes = SyncSelection.nodes.filter { it.type == SyncSelection.Type.ADVANCED }
    /** 面板私有状态快照（打开时取自存储层；与存储层无共享引用，写入只经 confirm 快照） */
    private val leafState = HashMap<String, Boolean>()
    private val advState = HashMap<String, Boolean>()
    private val collapsed = HashSet<String>()
    private val validationResults = HashMap<String, ValidationResult>()
    /** 重复触摸防护——每项「最近一次切换时间」（见 markOrDetectDuplicateToggle） */
    private val lastToggleAt = HashMap<String, Long>()
    /** 「确定」按钮重复点击防护（本实机触摸事件会重复） */
    private var lastConfirmAt = 0L

    // 本对话框实例是否已持久化状态
    private var saved = false
    /** 保存锁——persist() 窗口内吞掉一切状态变更（触摸重放） */
    private var saving = false
    /** 事件序列号（本对话框实例内单调递增，与 SyncSelection 日志共同重建全链路时间线） */
    private var seq = 0
    private val opTag: String get() = "i${System.identityHashCode(this)}"

    private fun logOp(msg: String) {
        AppLog.putDebug("Picker[$opTag#${++seq}] $msg")
    }

    private var rowsContainer: LinearLayout? = null

    /**
     * 重复触摸防护：低端设备（用户实机 BF61，Android 10 + 联发科；logcat 有
     * "InputEventReceiver ... disposed" 告警与 724ms 主线程卡顿）上，一次物理触摸
     * 可能产生重复/错乱的点击事件。
     * 规则：同一项 500ms 内的再次切换视为重复事件，忽略并记录日志供事后甄别。
     */
    private fun markOrDetectDuplicateToggle(key: String): Boolean {
        val now = SystemClock.uptimeMillis()
        val last = lastToggleAt[key] ?: 0L
        lastToggleAt[key] = now
        return now - last < 500L
    }

    /** 保存窗口内禁止任何状态变更——本设备(BF61/联发科)输入栈存在事件重放/错序，
     *  可能在保存窗口内再次触发复选框、改写写入值。 */
    private fun ignoreIfSaving(key: String): Boolean {
        if (!saving) return false
        AppLog.putDebug("Picker[$opTag] 已忽略保存期间的状态变更: $key")
        return true
    }

    init {
        // 打开面板时取存储层状态快照（现读现校验；存疑文件按纯默认，默认 = 20 项全选）
        leafNodes.forEach { leafState[it.key] = SyncSelection.isSelected(it.key) }
        advNodes.forEach { advState[it.key] = SyncSelection.isAdvanced(it.key) }
        logOp(
            "面板初始化: mode=$mode 未勾选叶子=" +
                leafNodes.filter { leafState[it.key] != true }.map { it.title } +
                "（打开面板时刻的存储状态快照）"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logOp("面板打开: mode=$mode")
        setContentView(buildContentView())
        window?.let { window ->
            // 与旧版几何一致：宽 95% × 高 85% 居中，窗口外区域由 dim 形成遮罩，
            // 点击窗口外 = 标准 dialog 取消（可关闭），返回键同样可关闭
            window.setLayout(
                (activity.resources.displayMetrics.widthPixels * 0.95f).toInt(),
                (activity.resources.displayMetrics.heightPixels * 0.85f).toInt()
            )
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setDimAmount(0.9f)
        }
        setCanceledOnTouchOutside(true)
        setOnDismissListener {
            // 关闭 = 取消，不写盘。唯一保存路径是「确定」按钮（persist）——
            // 避免「未确认的对话框被系统/用户关闭」时把打开瞬间的旧状态原样写回磁盘。
            logOp("面板关闭: saved=$saved")
            onDismissed()
        }
    }

    // ==================== 布局 ====================

    private fun buildContentView(): View {
        val c = context
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 8.dpToPx().toFloat()
                setColor(c.backgroundColor)
            }
        }
        // 头部：标题 +（恢复场景）格式检测按钮
        val header = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dpToPx(), 16.dpToPx(), 16.dpToPx(), 12.dpToPx())
        }
        header.addView(
            TextView(c).apply {
                text = c.getString(R.string.fvd_title)
                textSize = 18f
                setTextColor(c.primaryTextColor)
                typeface = Typeface.DEFAULT_BOLD
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        if (mode == SyncSelectionDialogMode.ACTION && zipFileNames != null) {
            header.addView(borderlessButton(c.getString(R.string.fvd_detect_format)) { validateCheckedFiles() })
        }
        root.addView(header)
        if (zipFileNames != null) {
            root.addView(
                TextView(c).apply {
                    text = "备份中不存在的条目已标记，勾选后仅恢复备份内实际存在的内容"
                    textSize = 12f
                    setTextColor(c.secondaryTextColor)
                    setPadding(16.dpToPx(), 0, 16.dpToPx(), 12.dpToPx())
                }
            )
        }
        root.addView(divider())
        // 树行容器（可滚动）
        val scroll = JitterSafeScrollView(c)
        rowsContainer = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8.dpToPx(), 0, 8.dpToPx())
        }
        scroll.addView(
            rowsContainer,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(divider())
        // 底部操作条
        val bottom = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 12.dpToPx())
        }
        bottom.addView(borderlessButton(c.getString(R.string.select_all)) {
            if (ignoreIfSaving("<全选>")) return@borderlessButton
            logOp("按钮: 全选 -> 全部叶子=true")
            leafState.keys.forEach { leafState[it] = true }
            refresh()
        })
        bottom.addView(borderlessButton(c.getString(R.string.un_select_all)) {
            if (ignoreIfSaving("<全不选>")) return@borderlessButton
            logOp("按钮: 全不选 -> 全部叶子=false")
            leafState.keys.forEach { leafState[it] = false }
            refresh()
        })
        bottom.addView(borderlessButton("恢复默认") {
            if (ignoreIfSaving("<恢复默认>")) return@borderlessButton
            logOp("按钮: 恢复默认 -> 叶子=全选, 进阶=全关")
            leafState.keys.forEach { leafState[it] = true }
            advState.keys.forEach { advState[it] = false }
            refresh()
        })
        bottom.addView(Space(c), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(
            Button(c).apply {
                text = c.getString(R.string.ok)
                setOnClickListener {
                    if (mode == SyncSelectionDialogMode.ACTION) {
                        confirm()
                    } else {
                        val now = SystemClock.uptimeMillis()
                        val last = lastConfirmAt
                        lastConfirmAt = now
                        if (now - last < 500L) {
                            logOp("已忽略疑似重复的确定点击")
                            return@setOnClickListener
                        }
                        closeSettings()
                    }
                }
            }
        )
        root.addView(bottom)
        rebuildRows()
        return root
    }

    private fun divider(): View = View(context).apply {
        background = ColorDrawable((0x33 shl 24) or (context.secondaryTextColor and 0x00FFFFFF))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1.dpToPx())
    }

    private fun borderlessButton(text: String, onClick: () -> Unit): Button =
        Button(context, null, android.R.attr.buttonBarButtonStyle).apply {
            this.text = text
            setOnClickListener { onClick() }
        }

    private fun space(width: Int): Space = Space(context).apply {
        layoutParams = LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun errorColor(): Int =
        if (context.isDarkTheme) 0xFFFF8A80.toInt() else 0xFFD32F2F.toInt()

    // ==================== 树行构建 ====================

    private fun rebuildRows() {
        val container = rowsContainer ?: return
        container.removeAllViews()
        SyncSelection.nodes.forEach { node ->
            if (node.type != SyncSelection.Type.BRANCH) return@forEach
            addSectionRow(container, node, isGroup = false)
            if (collapsed.contains(node.key)) return@forEach
            SyncSelection.children(node.key).forEach { child ->
                when (child.type) {
                    SyncSelection.Type.GROUP -> {
                        addSectionRow(container, child, isGroup = true)
                        if (!collapsed.contains(child.key)) {
                            addLeafRows(container, SyncSelection.children(child.key))
                        }
                    }

                    else -> addLeafRows(container, listOf(child))
                }
            }
        }
    }

    private fun addLeafRows(container: LinearLayout, nodes: List<SyncSelection.SyncNode>) {
        nodes.forEach { node ->
            if (node.type != SyncSelection.Type.LEAF) return@forEach
            addLeafRow(container, node)
            SyncSelection.children(node.key).forEach { adv ->
                if (adv.type == SyncSelection.Type.ADVANCED) addAdvancedRow(container, adv)
            }
        }
    }

    /** 分支/分组行：点行=折叠展开，点右侧三态框=批量勾选/取消 */
    private fun addSectionRow(container: LinearLayout, node: SyncSelection.SyncNode, isGroup: Boolean) {
        val c = context
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                if (isGroup) 36.dpToPx() else 12.dpToPx(),
                if (isGroup) 8.dpToPx() else 12.dpToPx(),
                16.dpToPx(),
                if (isGroup) 8.dpToPx() else 12.dpToPx()
            )
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (collapsed.contains(node.key)) collapsed.remove(node.key) else collapsed.add(node.key)
                rebuildRows()
            }
        }
        if (node.icon.isNotBlank()) {
            row.addView(
                TextView(c).apply {
                    text = node.icon
                    textSize = if (isGroup) 15f else 20f
                    setTextColor(c.primaryTextColor)
                }
            )
            row.addView(space(8.dpToPx()))
        }
        row.addView(
            TextView(c).apply {
                text = node.title
                textSize = if (isGroup) 14f else 16f
                setTextColor(c.primaryTextColor)
                typeface = Typeface.DEFAULT_BOLD
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(
            TextView(c).apply {
                text = if (collapsed.contains(node.key)) "▸" else "▾"
                textSize = 16f
                setTextColor(c.secondaryTextColor)
            }
        )
        row.addView(space(8.dpToPx()))
        row.addView(triStateBox(node.key))
        container.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun triStateBox(nodeKey: String): TextView {
        val c = context
        val symbol = triSymbol(nodeKey)
        val active = symbol == "✓"
        return TextView(c).apply {
            text = symbol
            textSize = 14f
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (ignoreIfSaving(nodeKey)) return@setOnClickListener
                val keys = SyncSelection.leafStateKeys(nodeKey)
                val to = !keys.all { leafState[it] ?: false }
                logOp("三态框点击: $nodeKey -> 该组全部叶子=$to")
                toggleAll(nodeKey)
                refresh()
            }
            setTextColor(if (active) c.primaryColor else c.secondaryTextColor)
            background = GradientDrawable().apply {
                cornerRadius = 6.dpToPx().toFloat()
                setStroke(1.dpToPx(), if (active) c.primaryColor else c.secondaryTextColor)
                setColor(if (active) (0x14 shl 24) or (c.primaryColor and 0x00FFFFFF) else Color.TRANSPARENT)
            }
            layoutParams = LinearLayout.LayoutParams(24.dpToPx(), 24.dpToPx())
        }
    }

    /** 叶子行：Checkbox + 标题(+备份中不存在标记) + 副标题(文件列表) + 校验状态图标 */
    private fun addLeafRow(container: LinearLayout, node: SyncSelection.SyncNode) {
        val c = context
        val checked = leafState[node.key] ?: false
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(52.dpToPx(), 8.dpToPx(), 16.dpToPx(), 8.dpToPx())
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (ignoreIfSaving(node.key)) return@setOnClickListener
                if (markOrDetectDuplicateToggle(node.key)) {
                    logOp("已忽略疑似重复触摸(行点击): ${node.key}")
                    return@setOnClickListener
                }
                val before = leafState[node.key] ?: false
                leafState[node.key] = !before
                logOp("行点击: ${node.key} $before -> ${leafState[node.key]}")
                refresh()
            }
        }
        val cb = CheckBox(c).apply { this.isChecked = checked }
        // 输入加固：「按下（ACTION_DOWN）」即触发切换并接管整个手势。
        // 低端设备触摸抖动时，标准「抬起触发」会被滚动区拦截/事件丢失而永不生效；
        // 按下即触发后即使后续 MOVE/UP 被截断或丢失，切换也已落地。
        cb.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                if (ignoreIfSaving(node.key)) {
                    // 保存窗口内吞掉任何重放触摸
                } else if (markOrDetectDuplicateToggle(node.key)) {
                    logOp("已忽略疑似重复触摸: ${node.key}")
                } else {
                    val before = cb.isChecked
                    cb.performClick()
                    logOp("复选框按下: ${node.key} $before -> ${cb.isChecked} 来源=触摸")
                }
            }
            true
        }
        cb.setOnCheckedChangeListener { _, isChecked ->
            logOp("复选框状态变化: ${node.key} -> $isChecked")
            leafState[node.key] = isChecked
            refresh()
        }
        row.addView(cb)
        row.addView(space(6.dpToPx()))
        val column = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val titleRow = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(
            TextView(c).apply {
                text = node.title
                textSize = 14f
                setTextColor(c.primaryTextColor)
            }
        )
        if (zipFileNames != null && node.files.none { it in zipFileNames }) {
            titleRow.addView(space(6.dpToPx()))
            titleRow.addView(
                TextView(c).apply {
                    text = "备份中不存在"
                    textSize = 11f
                    setTextColor(errorColor())
                }
            )
        }
        column.addView(titleRow)
        if (node.subtitle.isNotBlank() || node.files.isNotEmpty()) {
            column.addView(
                TextView(c).apply {
                    text = buildString {
                        if (node.subtitle.isNotBlank()) append(node.subtitle)
                        if (node.subtitle.isNotBlank() && node.files.isNotEmpty()) append("，")
                        if (node.files.isNotEmpty()) append(node.files.joinToString("、"))
                    }
                    textSize = 11f
                    setTextColor(c.secondaryTextColor)
                }
            )
        }
        row.addView(column, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        when (leafWorstState(node)) {
            ValidationState.VALID -> row.addView(iconText("✓", c.primaryColor, null))
            ValidationState.WARNING, ValidationState.ERROR ->
                row.addView(iconText("⚠", errorColor()) { showValidationDetail(node) })
            ValidationState.VALIDATING -> row.addView(iconText("⏳", c.secondaryTextColor, null))
            else -> row.addView(space(24.dpToPx()))
        }
        container.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun iconText(symbol: String, color: Int, onClick: (() -> Unit)?): TextView =
        TextView(context).apply {
            text = symbol
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(color)
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
        }

    /** 进阶项行（反语义：勾选 = 恢复时忽略该配置） */
    private fun addAdvancedRow(container: LinearLayout, node: SyncSelection.SyncNode) {
        val c = context
        val checked = advState[node.key] ?: false
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(72.dpToPx(), 6.dpToPx(), 16.dpToPx(), 6.dpToPx())
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (ignoreIfSaving(node.key)) return@setOnClickListener
                if (markOrDetectDuplicateToggle(node.key)) {
                    logOp("已忽略疑似重复触摸(行点击): ${node.key}")
                    return@setOnClickListener
                }
                val before = advState[node.key] ?: false
                advState[node.key] = !before
                logOp("进阶行点击: ${node.key} $before -> ${advState[node.key]}")
                refresh()
            }
        }
        val cb = CheckBox(c).apply { this.isChecked = checked }
        // 输入加固：同叶子行，「按下即触发」并接管手势
        cb.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                if (ignoreIfSaving(node.key)) {
                    // 保存窗口内吞掉任何重放触摸
                } else if (markOrDetectDuplicateToggle(node.key)) {
                    logOp("已忽略疑似重复触摸(进阶): ${node.key}")
                } else {
                    val before = cb.isChecked
                    cb.performClick()
                    logOp("进阶项按下: ${node.key} $before -> ${cb.isChecked} 来源=触摸")
                }
            }
            true
        }
        cb.setOnCheckedChangeListener { _, isChecked ->
            logOp("进阶状态变化: ${node.key} -> $isChecked")
            advState[node.key] = isChecked
            refresh()
        }
        row.addView(cb)
        row.addView(space(6.dpToPx()))
        row.addView(
            TextView(c).apply {
                text = node.title
                textSize = 13f
                setTextColor(c.secondaryTextColor)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        container.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    // ==================== 状态 ====================

    private fun triSymbol(nodeKey: String): String {
        val vals = SyncSelection.leafStateKeys(nodeKey).map { leafState[it] ?: false }
        return when {
            vals.isEmpty() -> "☐"
            vals.all { it } -> "✓"
            vals.any { it } -> "◐"
            else -> "☐"
        }
    }

    private fun toggleAll(nodeKey: String) {
        val keys = SyncSelection.leafStateKeys(nodeKey)
        val allTrue = keys.all { leafState[it] ?: false }
        keys.forEach { leafState[it] = !allTrue }
    }

    /** 状态变化后统一在主线程重建行（刷新分支/分组行的三态符号） */
    private fun refresh() {
        handler.post { rebuildRows() }
    }

    // ==================== 校验 ====================

    /** 宿主侧回调：更新单文件格式检测结果 */
    fun updateValidationResult(fileName: String, result: ValidationResult) {
        validationResults[fileName] = result
        refresh()
    }

    /** 触发格式检测：对勾选叶子 ∩ 备份内文件的列表执行校验 */
    private fun validateCheckedFiles() {
        val files = leafNodes
            .filter { leafState[it.key] == true }
            .flatMap { it.files }
            .filter { zipFileNames?.contains(it) == true }
        if (files.isNotEmpty()) onValidate(files)
    }

    /** 叶子行校验状态的最严重级别 */
    private fun leafWorstState(node: SyncSelection.SyncNode): ValidationState? {
        val states = node.files.mapNotNull { validationResults[it] }.map { it.state }
        return when {
            states.contains(ValidationState.ERROR) -> ValidationState.ERROR
            states.contains(ValidationState.WARNING) -> ValidationState.WARNING
            states.contains(ValidationState.VALIDATING) -> ValidationState.VALIDATING
            states.contains(ValidationState.VALID) -> ValidationState.VALID
            else -> null
        }
    }

    private fun showValidationDetail(node: SyncSelection.SyncNode) {
        val result = node.files
            .mapNotNull { validationResults[it] }
            .firstOrNull { it.state == ValidationState.ERROR || it.state == ValidationState.WARNING }
            ?: return
        val c = context
        val msg = SpannableStringBuilder().apply {
            append(result.message)
            if (result.details.isNotBlank()) append("\n\n").append(result.details)
            if (result.missingFields.isNotEmpty()) {
                append("\n\n").append(c.getString(R.string.fvd_missing_fields, result.missingFields.joinToString(", ")))
            }
            append("\n\n")
            if (result.canRestore) {
                append("✅ ").append(c.getString(R.string.fvd_can_restore))
            } else {
                append("❌ ").append(c.getString(R.string.fvd_cannot_restore))
            }
        }.toString()
        c.alert(null as CharSequence?, null as CharSequence?) {
            setTitle(result.fileName)
            setMessage(msg)
            okButton()
        }
    }

    // ==================== 保存 / 关闭 ====================

    private fun persist() {
        if (saved) return
        saved = true
        // 先开保存锁——此后任何触摸重放都无法改变写入值
        saving = true
        try {
            // 快照：以下写入与核对全部以这份快照为准（私有 Map 的拷贝，
            // 写入内容只由此快照序列化，不再受面板 UI 事件影响）
            val items = leafState.toMap()
            val adv = advState.toMap()
            // 全链路日志：确认时刻的完整状态（逐项键值），与 SyncSelection 侧的写入日志首尾相接
            logOp(
                "确认保存: mode=$mode " +
                    "叶子态=" + leafNodes.joinToString(",") { "${it.key}=${items[it.key]}" } +
                    " 进阶态=" + advNodes.joinToString(",") { "${it.key}=${adv[it.key]}" }
            )
            val result = SyncSelection.confirm(items, adv)
            if (result.ok) {
                val unchecked = leafNodes
                    .filter { items[it.key] != true }
                    .map { it.title }
                appCtx.toastOnUi(
                    "已保存（回读校验通过）" +
                        (if (unchecked.isEmpty()) "" else "，未勾选：${unchecked.joinToString("、")}")
                )
            } else {
                appCtx.toastOnUi(
                    "保存后回读校验不一致（${result.detail}）。已写入调试日志，请反馈日志定位。"
                )
            }
            // 保存完成后：从存储层重新同步 UI 状态（现读现校验），消除任何 UI 与磁盘不一致
            leafNodes.forEach { leafState[it.key] = SyncSelection.isSelected(it.key) }
            advNodes.forEach { advState[it.key] = SyncSelection.isAdvanced(it.key) }
            refresh()
        } finally {
            saving = false
        }
    }

    private fun confirm() {
        if (mode == SyncSelectionDialogMode.ACTION && leafState.values.none { it }) {
            appCtx.toastOnUi(appCtx.getString(R.string.fvd_select_at_least_one))
            return
        }
        persist()
        onActionConfirmed()
        dismiss()
    }

    private fun closeSettings() {
        persist()
        dismiss()
    }
}
