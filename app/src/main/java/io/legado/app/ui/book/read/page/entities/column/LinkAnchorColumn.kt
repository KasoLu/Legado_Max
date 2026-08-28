package io.legado.app.ui.book.read.page.entities.column

import android.graphics.Canvas
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextLine.Companion.emptyTextLine

/**
 * 书内锚点列
 *
 * 零宽、不绘制、不可点击。对应 EPUB 锚点目标位置的 PUA 令牌字符
 * （见 [io.legado.app.utils.LinkAnchor]），仅用于在排版后文本中定位目标位置。
 */
data class LinkAnchorColumn(
    override var start: Float,
    override var end: Float,
) : BaseColumn {

    override var textLine: TextLine = emptyTextLine

    override fun draw(view: ContentTextView, canvas: Canvas) {
        // 零宽锚点，不绘制任何内容
    }

    override fun isTouch(x: Float): Boolean = false
}