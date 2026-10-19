# 上下分屏 viewport 下移一行高 — 方案报告

> 需求（m00421 原话摘要）：将上下模式的 scrollview 做一次 y 轴整体下移，偏移量 = 一个行高。
> 偏移后正文区域内只显示两条分割线：上分割线在"正文顶部 + 偏移量"，中分割线在"中间 + 偏移量"，底分割线被推出屏幕外。
> 滚动依旧按一节（upDownSplitY）固定步长。等价于只把整个 scrollview 的 viewport 下移一行高，其余内容不变。

---

## 1. 当前坐标模型（代码核实）

渲染（`ContentTextView.drawPage` 的 upDown 分支，`app/src/main/java/io/legado/app/ui/book/read/page/ContentTextView.kt`）：

- 基准 `rel = relativeOffset(0) = pageOffset`，四节画在 `rel − gap`、`rel`、`rel + gap`、`rel + 2·gap`（`gap = ChapterProvider.upDownSplitY = visibleHeight/2`）。
- 每节经 `drawUpDownSegment(canvas, page, off)` 绘制：裁剪盒 `[paddingTop + off, paddingTop + off + upDownSplitY − upDownMargin]`、内容 blit（`page.draw(view, canvas, off)`）、溢出渐隐（`top + upDownSplitY` 向上 24dp）、分割线（`top + upDownSplitY`）——**全部派生自同一个 off**。
- 因此三条可见分割线位于 `paddingTop + rel`、`paddingTop + rel + gap`、`paddingTop + rel + 2·gap`。`scroll()` 的修正逻辑保证稳态 `pageOffset ∈ {0, −gap}`，所以三条线恰好压在正文区的顶/中/底——即当前现象。

触摸（同一文件）：

- `touchOffset(relativePos) = pageOffset + relativePos · upDownSplitY`（上下模式），`touch()`/`touchRough()`/`getCurVisiblePage()`/`getReadAloudPos()` 四处共用。
- 渲染与触摸同坐标系（上一次触摸修复的结论），本方案延续该不变量。

缓存（`TextPage.draw`，`entities/TextPage.kt`）：

- 优化渲染下页面内容是页内相对坐标录制的画布，blit 时 `canvas.withTranslation(0f, relativeOffset)` —— **blit 的 Y 就是 off**。改 off 不触碰录制内容，缓存无需失效。

## 2. 方案：基准 Y 加一个常量偏移 L

只改两处（均在 `ContentTextView.kt`）：

1. **新增属性**（渲染与触摸共用同一来源，保证逐帧逐像素一致）：

```kotlin
/**
 * 上下分屏 viewport 下移量 = 当前页第一行文本的行高 + 2 倍分割线内边距(upDownMargin)。
 */
private val upDownShiftY: Float
    get() = if (ChapterProvider.upDownPage) {
        (textPage.lines.firstOrNull { !it.isImage }?.height
            ?: textPage.lines.firstOrNull()?.height
            ?: 0f) + 2f * ChapterProvider.upDownMargin
    } else 0f
```

2. **渲染基准下移一行**（`drawPage` upDown 分支，一行改动）：
   `val rel = relativeOffset(0)` → `val rel = relativeOffset(0) + upDownShiftY`
   四节内容、裁剪盒、渐隐、三条分割线全部自动随之下移 L。

3. **触摸同量下移**（`touchOffset`，一行改动）：
   `pageOffset + relativePos * ChapterProvider.upDownSplitY`
   → `pageOffset + upDownShiftY + relativePos * ChapterProvider.upDownSplitY`

L 的取值：当前页第一行**非图片**文本行的 `height`（`TextLine.height = lineBottom − lineTop`）+ **2 倍 `upDownMargin`（10dp）**。理由：行高由字号与行距决定，同一章内所有页一致，故 L 对同帧画出的四节是同一个常量；字号调整会触发重排并替换 textPage，L 随之更新，渲染与触摸读同一属性、无跳变。加 padding 的原因：节内内容底边距分割线有 10dp 内边距，只偏移一行高时顶部条带被该空隙挤掉一截、只剩半行文字；加 1 倍 padding 露出完整一行 + 空隙，再加 1 倍（最终取值）使尾行上方多出一段边距，不与正文区上边缘相贴。无文本行的极端页回退首行高度，再无则 0f + 2×padding。

## 3. 偏移后的行为核对（对照需求逐条）

| 需求 | 结果 |
|---|---|
| 上分割线 = 正文顶部 + L | 顶线 = `paddingTop + rel + L`，稳态 rel∈{0,−gap} → 正好是顶部 + L ✓ |
| 中分割线 = 中间 + L | `paddingTop + rel + gap + L` = 中间 + L ✓ |
| 底分割线出屏 | `paddingTop + rel + 2·gap + L` = 底部 + L，被 `onDraw` 的 `clipRect(visibleRect)` 裁掉 ✓ |
| 滚动仍按一节 | `scroll()`/`UpDownPageDelegate.scrollDist = upDownSplitY` 不依赖绘制偏移，零改动 ✓ |
| 其余内容不变 | 排版（TextChapterLayout 行填充/断页/+5 间距）、isTopLine、自动翻页、九宫格、懒加载整屏、整页图——全部零改动 ✓ |

额外表现（需求的自然推论，非 bug）：正文区顶部 L 像素内是"一段边距 + 上一节完整尾行 + 一段空隙"（尾行不贴上边缘）；书首第一页（pageOffset 钳制为 0）顶部是 L 高的空白，第一分割线在顶部 + L ——与"顶部分割线位于正文顶部 + 偏移量"的规格一致。

## 4. 明确不改的部分

- `TextChapterLayout.kt`：排版与断页逻辑不变（isTopLine、节内按行填充、upDownSplitY − upDownMargin 切分点）。
- `TextLayoutCache.kt`：**不升 VERSION**——录制画布是页内相对坐标，blit 偏移由 off 决定，缓存内容零改动。
- `scroll()` / `scrollHeight` / `UpDownPageDelegate`：分页推进、跨页修正、书首书尾钳制、一节步长全部不变。
- `ReadView.kt` 九宫格点击流、`AutoPager`、非 upDown 模式（`ChapterProvider.upDownPage` 门槛隔离）。
- 懒加载占位页（整屏展示、早退，不经过分节渲染）与整页图（isFullPageText 早退）。

## 5. 风险与评估

| 项 | 评估 |
|---|---|
| 渲染/触摸坐标系失配 | 无——两处引用同一 `upDownShiftY` 属性，同帧同值 |
| 章节边界字号不同 | L 跟随当前页；重排替换 textPage 后下一帧起用新值，渲染触摸同步，无中间态 |
| 性能 | 每帧多一次 `lines.firstOrNull { !it.isImage }` 扫描（一页至多二三十行，可忽略） |
| 长截图 | upDown 分支基准本就不含 scrollY（既有行为），L 与其正交，行为不变 |
| 回归面 | 改动仅 upDownPage 门槛内的两行 + 一个新属性；其他模式零改动 |

## 6. 验证清单（真机回归）

1. 打开上下模式：正文区内恰有两条分割线，上分割线距正文顶部一行高、中分割线在中间 + 一行高；底部无分割线。
2. 连续向下滚动 5 次以上：每次移一节（半屏），两条线位置恒定（顶 + L / 中 + L），无跳变。
3. 正文区顶部一行高内可见上一节内容尾部（文字/图片），不出现半行裁切。
4. 书首翻到第一页：顶部一行高空白，顶分割线在顶部 + L。
5. 滚到懒加载页：仍整屏居中"数据加载中"，不受 L 影响；加载完成后恢复两条线布局。
6. 长按选中文本、点击神评/本章说入口、图片链接：全部正常命中（无错位一行）。
7. 截长图、朗读定位在上下模式下正常。
8. 左右翻页/滚动/封面/双页模式无任何变化。

**改动量预估**：`ContentTextView.kt` 新增一个属性（~8 行含注释）+ 两行偏移改动，无其他文件。
