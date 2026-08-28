package io.legado.app.utils

import java.net.URI

/**
 * EPUB 书内锚点支持（脚注/参考文献等 `<a href>` 双向跳转）
 *
 * 约定：
 * - 书内链接统一规范化为 [IN_BOOK_SCHEME] + 规范资源路径#锚点（RFC3986 百分号编码形式）
 * - 锚点目标位置以 PUA 私用区字符（U+E000..U+F7FF）标记，由 EpubFile 注入内容；
 *   排版引擎将其视为零宽不绘制的 [io.legado.app.ui.book.read.page.entities.column.LinkAnchorColumn]，
 *   字符保留在页面文本中，定位时通过 indexOf 令牌查找
 */
object LinkAnchor {

    /** 书内链接前缀 */
    const val IN_BOOK_SCHEME = "epubref:"

    /** PUA 令牌字符范围（含边界，共 6400 个） */
    const val TOKEN_MIN = 0xE000
    const val TOKEN_MAX = 0xF7FF

    private val EXTERNAL_SCHEMES = setOf("http", "https", "mailto", "ftp", "file")

    fun isToken(char: Char): Boolean = char.code in TOKEN_MIN..TOKEN_MAX

    /**
     * 令牌字符：由 key 哈希稳定映射到 PUA 区间。
     * key 全局唯一即可（资源路径#锚点），无需反向解析——链接本身携带完整 key。
     */
    fun tokenChar(key: String): Char =
        (TOKEN_MIN + (key.hashCode() and 0x3FFFFFFF) % (TOKEN_MAX - TOKEN_MIN + 1)).toChar()

    /**
     * 规范化的书内引用：资源路径#锚点（百分号编码，dot 段已折叠）。
     * @param baseHref 资源路径（原始形式，如 "OEBPS/ch01.xhtml"）
     * @param fragment 锚点 id
     */
    fun canonicalRef(baseHref: String, fragment: String): String? {
        return try {
            URI(escapeForUri(baseHref))
                .resolve("#" + escapeForUri(fragment))
                .normalize()
                .toString()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 解析 a 标签 href：
     * @param baseHref 所在资源路径
     * @param rawHref 原始 href
     * @return null：空 href 或未知 scheme（不做适配，按纯文本处理）；
     *         以 [IN_BOOK_SCHEME] 开头：书内引用，后续内容为规范化资源路径#锚点；
     *         其它：外部链接（原样 URL，走既有 OpenUrlConfirm 流程）
     */
    fun resolveHref(baseHref: String, rawHref: String): String? {
        val raw = rawHref.trim()
        if (raw.isEmpty()) return null
        // 外部链接直接判定，避免 encode 破坏 scheme
        val colon = raw.indexOf(':')
        if (colon in 1..32 && raw.substring(0, colon).all { it.isLetterOrDigit() || it in "+.-" }) {
            return if (raw.substring(0, colon).lowercase() in EXTERNAL_SCHEMES) raw else null
        }
        val hash = raw.indexOf('#')
        val pathPart = if (hash >= 0) raw.substring(0, hash) else raw
        val fragPart = if (hash >= 0) raw.substring(hash + 1) else null
        return try {
            val enc = escapeForUri(pathPart) + if (hash >= 0) "#" + escapeForUri(fragPart.orEmpty()) else ""
            val resolved = URI(escapeForUri(baseHref)).resolve(enc)
            if (resolved.scheme == null) IN_BOOK_SCHEME + resolved.normalize().toString()
            else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * RFC3986 百分号编码：保留 pchar 及 '/'，其余 ASCII 按 %XX、非 ASCII 按 UTF-8 字节编码。
     * 两侧（EpubFile 注入 / 章节匹配）必须使用同一编码保证 key 一致。
     */
    fun escapeForUri(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when {
                c.isLetterOrDigit() || c in "-._~!$&'()*+,;=:@/" -> sb.append(c)
                c.code < 0x80 -> sb.append('%')
                    .append(c.code.toString(16).uppercase().padStart(2, '0'))
                else -> c.toString().toByteArray(Charsets.UTF_8).forEach { b ->
                    sb.append('%').append((b.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0'))
                }
            }
        }
        return sb.toString()
    }
}