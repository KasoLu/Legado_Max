package io.legado.app.ui.book.read.page.provider

import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.getFolderNameNoCache
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.BaseColumn
import io.legado.app.ui.book.read.page.entities.column.ButtonColumn
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.LinkAnchorColumn
import io.legado.app.ui.book.read.page.entities.column.ReviewColumn
import io.legado.app.ui.book.read.page.entities.column.TextBaseColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.book.read.page.entities.column.TextHtmlColumn
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import splitties.init.appCtx
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/**
 * 章节排版缓存
 *
 * 排版（逐字测量+断行+高亮规则匹配）是全 App 最重的 CPU 任务。
 * 同一内容在同样的视图尺寸与字体/段落/排版设置下，排版结果是确定的，
 * 因此把排版产物（页/行/列几何数据）以紧凑二进制格式落盘，
 * 再次打开同一章节时直接反序列化，跳过整个排版过程。
 *
 * 缓存文件：externalFiles/layout_cache/<书名>/(章节索引).dat
 * 文件头带完整 key（内容哈希+全部影响排版的设置指纹），key 不匹配即视为未命中。
 * 任何解析异常都按未命中处理，回退到正常排版，绝不影响阅读。
 */
object TextLayoutCache {

    private const val VERSION = 4

    // 列类型标记
    private const val COL_TEXT = 0
    private const val COL_HTML = 1
    private const val COL_IMAGE = 2
    private const val COL_REVIEW = 3
    private const val COL_BUTTON = 4
    private const val COL_ANCHOR = 5

    /** 排版缓存文件（每本书每章节一个） */
    fun cacheFile(book: Book, chapterIndex: Int): File {
        return appCtx.externalFiles.getFile(
            BookHelp.layoutCacheFolderName,
            book.getFolderName(),
            "${chapterIndex}.dat"
        )
    }

    /** 删除单章缓存 */
    fun deleteCache(book: Book, chapterIndex: Int) {
        runCatching { cacheFile(book, chapterIndex).delete() }
    }

    /** 清空某本书的全部排版缓存 */
    fun deleteBookCache(book: Book) {
        runCatching {
            val dir = appCtx.externalFiles.getFile(
                BookHelp.layoutCacheFolderName,
                book.getFolderName()
            )
            if (dir.isDirectory) dir.deleteRecursively()
        }
    }

    /** 清空所有排版缓存 */
    fun clearAll() {
        runCatching {
            val dir = appCtx.externalFiles.getFile(BookHelp.layoutCacheFolderName)
            if (dir.isDirectory) dir.deleteRecursively()
        }
    }

    /** 书籍改名后迁移排版缓存目录 */
    fun moveBookCache(oldBook: Book, newBook: Book) {
        val oldName = oldBook.getFolderNameNoCache()
        val newName = newBook.getFolderNameNoCache()
        if (oldName == newName) return
        runCatching {
            val oldDir = appCtx.externalFiles.getFile(BookHelp.layoutCacheFolderName, oldName)
            val newDir = appCtx.externalFiles.getFile(BookHelp.layoutCacheFolderName, newName)
            if (oldDir.isDirectory) {
                oldDir.renameTo(newDir)
            }
        }
    }

    /**
     * 读取缓存。key 不匹配、文件损坏或格式不符时返回 null（按未命中处理）
     */
    fun load(key: String, file: File): List<TextPage>? {
        if (!file.isFile) return null
        return runCatching {
            BufferedInputStream(file.inputStream()).use { input ->
                DataInputStream(input).use { d ->
                    val version = d.readInt()
                    if (version != VERSION) return null
                    val fileKey = d.readUTF()
                    if (fileKey != key) return null
                    val count = d.readInt()
                    if (count <= 0) return null
                    val pages = ArrayList<TextPage>(count)
                    repeat(count) { pages.add(readPage(d)) }
                    pages
                }
            }
        }.getOrNull()
    }

    /**
     * 保存排版结果。先写临时文件再原子替换，避免写一半损坏缓存
     */
    fun save(key: String, file: File, pages: List<TextPage>) {
        if (pages.isEmpty()) return
        runCatching {
            file.parentFile?.mkdirs()
            // 随机后缀避免并发排版（快速跳章）写同一临时文件
            val tmp = File(file.parentFile, file.name + "-" + java.util.UUID.randomUUID().toString().take(8) + ".tmp")
            BufferedOutputStream(tmp.outputStream()).use { out ->
                DataOutputStream(out).use { d ->
                    d.writeInt(VERSION)
                    d.writeUTF(key)
                    d.writeInt(pages.size)
                    pages.forEach { writePage(d, it) }
                    d.flush()
                }
            }
            file.delete()
            if (!tmp.renameTo(file)) {
                // 跨设备或极端情况下 rename 失败，退化为复制
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    // ---------- 序列化 ----------

    private fun writePage(d: DataOutputStream, page: TextPage) {
        d.writeInt(page.index)
        d.writeFloat(page.height)
        d.writeInt(page.renderHeight)
        d.writeInt(page.paddingTop)
        d.writeInt(page.leftLineSize)
        d.writeInt(page.chapterSize)
        d.writeInt(page.chapterIndex)
        d.writeBoolean(page.doublePage)
        d.writeBoolean(page.upDownPage)
        d.writeUTF(page.title)
        d.writeUTF(page.text)
        val lines = page.lines
        d.writeInt(lines.size)
        lines.forEach { writeLine(d, it) }
    }

    private fun writeLine(d: DataOutputStream, line: TextLine) {
        d.writeUTF(line.text)
        d.writeFloat(line.lineTop)
        d.writeFloat(line.lineBase)
        d.writeFloat(line.lineBottom)
        d.writeFloat(line.indentWidth)
        d.writeInt(line.paragraphNum)
        d.writeInt(line.chapterPosition)
        d.writeInt(line.pagePosition)
        d.writeBoolean(line.isTitle)
        d.writeBoolean(line.isParagraphEnd)
        d.writeBoolean(line.isImage)
        d.writeBoolean(line.isHtml)
        d.writeFloat(line.startX)
        d.writeInt(line.indentSize)
        d.writeFloat(line.extraLetterSpacing)
        d.writeFloat(line.extraLetterSpacingOffsetX)
        d.writeFloat(line.wordSpacing)
        d.writeBoolean(line.exceed)
        d.writeBoolean(line.onlyTextColumn)
        d.writeBoolean(line.isLeftLine)
        d.writeBoolean(line.isTopLine)
        d.writeBoolean(line.hasFadeBottom)
        val columns = line.columns
        d.writeInt(columns.size)
        columns.forEach { writeColumn(d, it) }
    }

    private fun writeColumn(d: DataOutputStream, column: BaseColumn) {
        when (column) {
            is TextColumn -> {
                d.writeByte(COL_TEXT)
                writeTextColumn(d, column)
            }
            is TextHtmlColumn -> {
                d.writeByte(COL_HTML)
                writeTextBaseGeometry(d, column)
                d.writeUTF(column.charData)
                d.writeFloat(column.mTextSize)
                d.writeBoolean(column.mTextColor != null)
                column.mTextColor?.let { d.writeInt(it) }
                d.writeBoolean(column.linkUrl != null)
                column.linkUrl?.let { d.writeUTF(it) }
                writeUnderlineBg(d, column)
            }
            is ImageColumn -> {
                d.writeByte(COL_IMAGE)
                d.writeFloat(column.start)
                d.writeFloat(column.end)
                d.writeUTF(column.src)
                d.writeBoolean(column.click != null)
                column.click?.let { d.writeUTF(it) }
                d.writeBoolean(column.isAnimated)
            }
            is ReviewColumn -> {
                d.writeByte(COL_REVIEW)
                d.writeFloat(column.start)
                d.writeFloat(column.end)
                d.writeInt(column.count)
            }
            is ButtonColumn -> {
                d.writeByte(COL_BUTTON)
                d.writeFloat(column.start)
                d.writeFloat(column.end)
            }
            is LinkAnchorColumn -> {
                d.writeByte(COL_ANCHOR)
                d.writeFloat(column.start)
                d.writeFloat(column.end)
            }
            else -> throw IOException("未知列类型: ${column::class}")
        }
    }

    private fun writeTextColumn(d: DataOutputStream, column: TextColumn) {
        writeTextBaseGeometry(d, column)
        d.writeUTF(column.charData)
        d.writeBoolean(column.textColor != null)
        column.textColor?.let { d.writeInt(it) }
        writeUnderlineBg(d, column)
    }

    private fun writeTextBaseGeometry(d: DataOutputStream, column: TextBaseColumn) {
        d.writeFloat(column.start)
        d.writeFloat(column.end)
    }

    private fun writeUnderlineBg(d: DataOutputStream, column: TextBaseColumn) {
        d.writeInt(column.underlineMode)
        d.writeBoolean(column.underlineColor != null)
        column.underlineColor?.let { d.writeInt(it) }
        d.writeFloat(column.underlineWidth)
        d.writeFloat(column.underlineOffset)
        d.writeUTF(column.underlineSvgPath)
        d.writeUTF(column.bgImage)
        d.writeInt(column.bgImageFit)
        d.writeFloat(column.bgImageScale)
    }

    // ---------- 反序列化 ----------

    private fun readPage(d: DataInputStream): TextPage {
        val page = TextPage()
        page.index = d.readInt()
        page.height = d.readFloat()
        page.renderHeight = d.readInt()
        page.paddingTop = d.readInt()
        page.leftLineSize = d.readInt()
        page.chapterSize = d.readInt()
        page.chapterIndex = d.readInt()
        page.doublePage = d.readBoolean()
        page.upDownPage = d.readBoolean()
        page.title = d.readUTF()
        page.text = d.readUTF()
        page.isCompleted = true
        val count = d.readInt()
        repeat(count) { page.addLine(readLine(d, page)) }
        page.hasAnimatedContent = page.lines.any { it.hasAnimatedColumn }
        return page
    }

    private fun readLine(d: DataInputStream, page: TextPage): TextLine {
        val line = TextLine(
            d.readUTF(),
            arrayListOf(),
            d.readFloat(),
            d.readFloat(),
            d.readFloat(),
            d.readFloat(),
            d.readInt(),
            d.readInt(),
            d.readInt(),
            d.readBoolean(),
            d.readBoolean(),
            d.readBoolean(),
            d.readBoolean(),
            d.readFloat(),
            d.readInt(),
            d.readFloat(),
            d.readFloat(),
            d.readFloat(),
            d.readBoolean(),
            d.readBoolean()
        )
        line.isLeftLine = d.readBoolean()
        line.isTopLine = d.readBoolean()
        line.hasFadeBottom = d.readBoolean()
        line.textPage = page
        val count = d.readInt()
        repeat(count) { line.addColumn(readColumn(d)) }
        return line
    }

    private fun readColumn(d: DataInputStream): BaseColumn {
        return when (d.readByte().toInt()) {
            COL_TEXT -> {
                val bg = readUnderlineBg(d)
                TextColumn(
                    d.readFloat(),
                    d.readFloat(),
                    d.readUTF(),
                    if (d.readBoolean()) d.readInt() else null,
                    bg.underlineMode,
                    bg.underlineColor,
                    bg.underlineWidth,
                    bg.underlineOffset,
                    bg.underlineSvgPath,
                    bg.bgImage,
                    bg.bgImageFit,
                    bg.bgImageScale
                )
            }
            COL_HTML -> {
                val bg = readUnderlineBg(d)
                TextHtmlColumn(
                    d.readFloat(),
                    d.readFloat(),
                    d.readUTF(),
                    d.readFloat(),
                    if (d.readBoolean()) d.readInt() else null,
                    if (d.readBoolean()) d.readUTF() else null,
                    bg.underlineMode,
                    bg.underlineColor,
                    bg.underlineWidth,
                    bg.underlineOffset,
                    bg.underlineSvgPath,
                    bg.bgImage,
                    bg.bgImageFit,
                    bg.bgImageScale
                )
            }
            COL_IMAGE -> ImageColumn(
                d.readFloat(),
                d.readFloat(),
                d.readUTF(),
                if (d.readBoolean()) d.readUTF() else null,
                d.readBoolean()
            )
            COL_REVIEW -> ReviewColumn(
                d.readFloat(),
                d.readFloat(),
                d.readInt()
            )
            COL_BUTTON -> ButtonColumn(
                d.readFloat(),
                d.readFloat()
            )
            COL_ANCHOR -> LinkAnchorColumn(
                d.readFloat(),
                d.readFloat()
            )
            else -> throw IOException("未知列类型标记")
        }
    }

    private fun readUnderlineBg(d: DataInputStream): TextUnderlineBg {
        val underlineMode = d.readInt()
        val underlineColor = if (d.readBoolean()) d.readInt() else null
        val underlineWidth = d.readFloat()
        val underlineOffset = d.readFloat()
        val underlineSvgPath = d.readUTF()
        val bgImage = d.readUTF()
        val bgImageFit = d.readInt()
        val bgImageScale = d.readFloat()
        return TextUnderlineBg(
            underlineMode, underlineColor, underlineWidth,
            underlineOffset, underlineSvgPath, bgImage, bgImageFit, bgImageScale
        )
    }

    private data class TextUnderlineBg(
        val underlineMode: Int,
        val underlineColor: Int?,
        val underlineWidth: Float,
        val underlineOffset: Float,
        val underlineSvgPath: String,
        val bgImage: String,
        val bgImageFit: Int,
        val bgImageScale: Float
    )
}
