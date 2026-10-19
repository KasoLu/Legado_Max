package io.legado.app.help.storage

import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.repository.CoverGalleryRepository
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.config.ThemeConfig
import io.legado.app.model.BookCover
import io.legado.app.ui.book.read.config.HighlightRuleStore
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import splitties.init.appCtx
import java.io.File
import java.security.MessageDigest

/**
 * 多机备份/恢复内容选择状态 —— 第十六轮全新实现（与 v1~v7 旧实现零耦合，整体重写）。
 *
 * 旧实现（v1~v7）经十六轮排查（BF61/联发科实机日志定案）确认的教训：
 * - 设备运行时不可信：僵尸进程长期存活并覆盖文件、写盘后回读不一致、
 *   共享内存态在 4ms 内被翻转、文件 delete 静默失败、触摸事件重放；
 * - 「记住一个复选框的勾选状态」在该设备上不可靠；常驻共享态 + 版本闸门
 *   会把运行时异常放大成永久自锁（本构建写出的污染文件被版本闸门判为可信）。
 *
 * 本实现的设计原则：
 * 1. 默认值纯代码计算，永不从磁盘读（新默认 = 20 项全选；「阅读样式」条目已整体移除，
 *    阅读样式/阅读界面配置永不参与多机同步）；
 * 2. 无常驻共享内存态：每次使用现读文件 + 现校验（[snapshot]）；
 * 3. 唯一写盘路径 = 用户点「确定」时调用 [confirm]（内容只由传入快照序列化，不读任何共享态）；
 * 4. 文件自描述 + 三重信任校验（builtWith = 本构建版本码 / writerPid 进程存活检查 /
 *    SHA-256 内容校验和）：文件缺失、旧格式、旧构建所写、校验和不符、写入者进程仍存活
 *    （僵尸并存）→ 一律按纯默认处理，绝不采纳可疑文件；
 * 5. 写入 = 原子写（.tmp + rename）→ 回读 → 校验和核对，不符最多强制重写 3 次；
 * 6. 旧版本文件（v1~v7 的 syncSelection.json、backupSelector.json、restoreIgnore.json）
 *    不再读取、不再迁移：尽力删除后从纯默认开始。
 */
object SyncSelection {

    // ==================== 文件格式（schema 1，全新定义） ====================

    const val SCHEMA = 1

    private const val FILE_NAME = "syncSelection.json"

    /** 持久化记录。全部字段可空 + 带默认值：Gson 对旧格式文件的解析结果安全无副作用
     *  （旧文件解析出的字段为 null → 直接判为旧格式 → 纯默认，不会 NPE/异常）。 */
    data class Persisted(
        val schema: Int? = null,
        /** 写入时本应用的 versionCode（识别「其他构建写出的文件」——多设备/多版本并存时不可信） */
        val builtWith: Long? = null,
        /** 写入进程 pid（若该进程仍存活 → 僵尸实例并存，文件随时会被其覆盖，不可信） */
        val writerPid: Int? = null,
        val writtenAt: Long? = null,
        /** 叶子 key -> 是否参与备份/恢复 */
        val items: Map<String, Boolean>? = null,
        /** 进阶 key -> 是否恢复时忽略（反向语义） */
        val advanced: Map<String, Boolean>? = null,
        /** 内容校验和（SHA-256 十六进制；对 [canonical] 规范化串计算，与序列化格式无关） */
        val checksum: String? = null,
    )

    // ==================== 分支 / 分组 key ====================

    const val BRANCH_BOOK = "book"
    const val BRANCH_CONFIG = "config"

    const val GROUP_SOURCE = "source"
    const val GROUP_RULE = "rule"

    // ==================== 叶子 key（第十六轮：「阅读样式」已移除，共 20 项） ====================

    const val LEAF_BOOKSHELF = "bookshelf"
    const val LEAF_BOOKMARK = "bookmark"
    const val LEAF_BOOK_GROUP = "bookGroup"
    const val LEAF_READ_RECORD = "readRecord"
    const val LEAF_SEARCH_HISTORY = "searchHistory"
    const val LEAF_BOOK_CACHE = "bookCache"

    const val LEAF_BOOK_SOURCE = "bookSource"
    const val LEAF_RSS_SOURCE = "rssSource"
    const val LEAF_SOURCE_CACHE = "sourceCache"

    const val LEAF_REPLACE_RULE = "replaceRule"
    const val LEAF_TXT_TOC_RULE = "txtTocRule"
    const val LEAF_DICT_RULE = "dictRule"
    const val LEAF_KEYBOARD_ASSIST = "keyboardAssist"
    const val LEAF_HIGHLIGHT_RULE = "highlightRule"

    const val LEAF_HTTP_TTS = "httpTTS"
    const val LEAF_SERVERS = "servers"
    const val LEAF_DIRECT_LINK = "directLink"
    const val LEAF_COVER = "cover"
    const val LEAF_THEME = "theme"
    const val LEAF_APP_CONFIG = "appConfig"

    // ==================== 进阶项 key（反向语义：勾选 = 恢复时忽略该配置） ====================

    const val ADV_LOCAL_BOOK = "localBook"
    const val ADV_BOOKSHELF_LAYOUT = "bookshelfLayout"
    const val ADV_SHOW_RSS = "showRss"
    const val ADV_THREAD_COUNT = "threadCount"

    // ==================== 树节点（与选择面板 UI 共用） ====================

    enum class Type { BRANCH, GROUP, LEAF, ADVANCED }

    data class SyncNode(
        val key: String,
        val parentKey: String,
        val type: Type,
        val title: String,
        val icon: String = "",
        val subtitle: String = "",
        val stateKey: String? = null,
        val advanced: Boolean = false,
        val files: List<String> = emptyList()
    )

    /** 完整树（扁平存储，按 parentKey 关联；顶层 = 分支） */
    val nodes: List<SyncNode> by lazy {
        listOf(
            SyncNode(BRANCH_BOOK, "", Type.BRANCH, "书籍相关", icon = "📚"),
            SyncNode(LEAF_BOOKSHELF, BRANCH_BOOK, Type.LEAF, "书架", files = listOf("bookshelf.json")),
            SyncNode(ADV_LOCAL_BOOK, LEAF_BOOKSHELF, Type.ADVANCED, "忽略本地书籍", stateKey = ADV_LOCAL_BOOK, advanced = true),
            SyncNode(LEAF_BOOKMARK, BRANCH_BOOK, Type.LEAF, "书签", files = listOf("bookmark.json")),
            SyncNode(LEAF_BOOK_GROUP, BRANCH_BOOK, Type.LEAF, "书籍分组", files = listOf("bookGroup.json")),
            SyncNode(LEAF_READ_RECORD, BRANCH_BOOK, Type.LEAF, "阅读记录", files = listOf("readRecord.json", "readRecordDetail.json")),
            SyncNode(LEAF_SEARCH_HISTORY, BRANCH_BOOK, Type.LEAF, "搜索历史", files = listOf("searchHistory.json")),
            SyncNode(LEAF_BOOK_CACHE, BRANCH_BOOK, Type.LEAF, "书籍缓存", subtitle = "含章节目录/缓存索引", files = listOf("book_cache", "bookChapterCache.json", "bookCacheIndex.json", "bookCacheBooks.json")),

            SyncNode(BRANCH_CONFIG, "", Type.BRANCH, "具体配置", icon = "⚙️"),
            SyncNode(GROUP_SOURCE, BRANCH_CONFIG, Type.GROUP, "书源相关", icon = "📗"),
            SyncNode(LEAF_BOOK_SOURCE, GROUP_SOURCE, Type.LEAF, "书源", files = listOf("bookSource.json")),
            SyncNode(LEAF_RSS_SOURCE, GROUP_SOURCE, Type.LEAF, "订阅源", files = listOf("rssSources.json", "rssStar.json")),
            SyncNode(LEAF_SOURCE_CACHE, GROUP_SOURCE, Type.LEAF, "书源运行数据", subtitle = "登录信息/运行变量", files = listOf("runtimeSourceCache.json")),
            SyncNode(GROUP_RULE, BRANCH_CONFIG, Type.GROUP, "规则", icon = "🔧"),
            SyncNode(LEAF_REPLACE_RULE, GROUP_RULE, Type.LEAF, "替换规则", files = listOf("replaceRule.json")),
            SyncNode(LEAF_TXT_TOC_RULE, GROUP_RULE, Type.LEAF, "TXT目录规则", files = listOf("txtTocRule.json")),
            SyncNode(LEAF_DICT_RULE, GROUP_RULE, Type.LEAF, "词典规则", files = listOf("dictRule.json")),
            SyncNode(LEAF_KEYBOARD_ASSIST, GROUP_RULE, Type.LEAF, "键盘辅助", files = listOf("keyboardAssists.json")),
            SyncNode(LEAF_HIGHLIGHT_RULE, GROUP_RULE, Type.LEAF, "高亮规则", files = listOf(HighlightRuleStore.backupFileName)),
            SyncNode(LEAF_HTTP_TTS, BRANCH_CONFIG, Type.LEAF, "TTS配置", files = listOf("httpTTS.json")),
            SyncNode(LEAF_SERVERS, BRANCH_CONFIG, Type.LEAF, "服务器配置", files = listOf("servers.json")),
            SyncNode(LEAF_DIRECT_LINK, BRANCH_CONFIG, Type.LEAF, "直链上传", files = listOf(DirectLinkUpload.ruleFileName)),
            SyncNode(LEAF_COVER, BRANCH_CONFIG, Type.LEAF, "封面规则与图集", files = listOf(BookCover.configFileName, CoverGalleryRepository.backupDirName)),
            SyncNode(LEAF_THEME, BRANCH_CONFIG, Type.LEAF, "主题配置", files = listOf(ThemeConfig.configFileName)),
            SyncNode(LEAF_APP_CONFIG, BRANCH_CONFIG, Type.LEAF, "应用配置", files = listOf("config.xml")),
            SyncNode(ADV_BOOKSHELF_LAYOUT, LEAF_APP_CONFIG, Type.ADVANCED, "忽略书架布局", stateKey = ADV_BOOKSHELF_LAYOUT, advanced = true),
            SyncNode(ADV_SHOW_RSS, LEAF_APP_CONFIG, Type.ADVANCED, "忽略RSS显示", stateKey = ADV_SHOW_RSS, advanced = true),
            SyncNode(ADV_THREAD_COUNT, LEAF_APP_CONFIG, Type.ADVANCED, "忽略线程数", stateKey = ADV_THREAD_COUNT, advanced = true),
        )
    }

    private val leafNodes: List<SyncNode> by lazy { nodes.filter { it.type == Type.LEAF } }
    private val leafKeys: List<String> by lazy { leafNodes.map { it.key } }
    private val advancedKeys: List<String> by lazy { nodes.filter { it.type == Type.ADVANCED }.map { it.key } }

    /** 直接子节点（按树定义顺序） */
    fun children(key: String): List<SyncNode> = nodes.filter { it.parentKey == key }

    /** 所有后代节点（递归） */
    fun descendants(key: String): List<SyncNode> {
        val result = mutableListOf<SyncNode>()
        fun walk(k: String) {
            nodes.filter { it.parentKey == k }.forEach { node ->
                result.add(node)
                walk(node.key)
            }
        }
        walk(key)
        return result
    }

    /** 节点自身（若是叶子）及后代中所有叶子的 key */
    fun leafStateKeys(nodeKey: String): List<String> {
        val node = nodes.firstOrNull { it.key == nodeKey } ?: return emptyList()
        return buildList {
            if (node.type == Type.LEAF) add(node.key)
            addAll(descendants(nodeKey).filter { it.type == Type.LEAF }.map { it.key })
        }
    }

    /** 节点后代中所有进阶项的 key */
    fun advancedStateKeys(nodeKey: String): List<String> =
        descendants(nodeKey).filter { it.type == Type.ADVANCED }.map { it.key }

    /** 备份文件 -> 所属叶子节点 */
    fun leafByFile(fileName: String): SyncNode? = leafNodes.firstOrNull { fileName in it.files }

    /** 已勾选叶子对应的备份文件/目录名（用于格式检测等） */
    fun selectedFileNames(): List<String> = leafNodes.filter { isSelected(it.key) }.flatMap { it.files }

    // ==================== 配置键组（config.xml 导出/恢复过滤用） ====================

    /** 阅读样式/阅读界面相关配置键：永不参与多机同步（第十六轮：「阅读样式」条目整体移除，
     *  多设备间阅读样式永远不互相覆盖，各设备保留自己的阅读设置） */
    val readPrefKeys = arrayOf(
        PreferKey.readStyleSelect,
        PreferKey.comicStyleSelect,
        PreferKey.shareLayout,
        PreferKey.hideStatusBar,
        PreferKey.hideNavigationBar,
        PreferKey.autoReadSpeed,
        PreferKey.clickActionTL,
        PreferKey.clickActionTC,
        PreferKey.clickActionTR,
        PreferKey.clickActionML,
        PreferKey.clickActionMC,
        PreferKey.clickActionMR,
        PreferKey.clickActionBL,
        PreferKey.clickActionBC,
        PreferKey.clickActionBR
    )

    /** 主题相关配置键（按「主题配置」叶子过滤） */
    val themePrefKeys = arrayOf(
        PreferKey.cPrimary,
        PreferKey.cAccent,
        PreferKey.cBackground,
        PreferKey.cBBackground,
        PreferKey.bgImage,
        PreferKey.bgImageBlurring,
        PreferKey.tNavBar,
        PreferKey.cNPrimary,
        PreferKey.cNAccent,
        PreferKey.cNBackground,
        PreferKey.cNBBackground,
        PreferKey.bgImageN,
        PreferKey.bgImageNBlurring,
        PreferKey.tNavBarN
    )

    /** 封面相关配置键（按「封面规则与图集」叶子过滤） */
    val coverPrefKeys = arrayOf(
        PreferKey.useDefaultCover,
        PreferKey.loadCoverOnlyWifi,
        PreferKey.coverShowName,
        PreferKey.coverShowAuthor,
        PreferKey.coverShowNameN,
        PreferKey.coverShowAuthorN
    )

    /** 恒定自动忽略的配置键（与设备/安装相关，永不备份恢复） */
    val autoIgnorePrefKeys = arrayOf(
        PreferKey.defaultCover,
        PreferKey.defaultCoverDark,
        PreferKey.backupPath,
        PreferKey.defaultBookTreeUri,
        PreferKey.webDavDeviceName,
        PreferKey.launcherIcon,
        PreferKey.bitmapCacheSize,
        PreferKey.readAloudWakeLock,
        PreferKey.audioPlayWakeLock
    )

    /** 恢复主题配置后需要在新设备重设的主题键 */
    val themeRestorePrefKeys = arrayOf(PreferKey.dThemeName, PreferKey.dNThemeName) + themePrefKeys

    /** config.xml 导出/恢复时的配置键白名单：
     *  阅读样式相关键（readPrefKeys）永不同步；其余键按所属叶子勾选状态过滤。 */
    fun keyAllowed(key: String): Boolean = when (key) {
        in readPrefKeys -> false
        // 应用配置（LEAF_APP_CONFIG）整份 config.xml 已在 Backup/Restore 调用点由
        // isSelected(LEAF_APP_CONFIG) 门控，无需逐键处理
        in themePrefKeys -> isSelected(LEAF_THEME)
        in coverPrefKeys -> isSelected(LEAF_COVER)
        in autoIgnorePrefKeys -> false
        else -> true
    }

    // ==================== 状态读取（现读现校验，无常驻内存态） ====================

    data class State(
        val items: Map<String, Boolean>,
        val advanced: Map<String, Boolean>,
        val source: String,
    )

    /** 纯默认（纯代码计算）：20 个叶子全选，进阶项全关 */
    fun defaultState(): State = State(
        items = leafKeys.associateWith { true },
        advanced = advancedKeys.associateWith { false },
        source = "纯默认",
    )

    /** 当前生效状态：现读文件并做信任校验（本构建版本码 / 写入者进程存活 / SHA-256 校验和）。
     *  任何存疑（文件缺失、旧格式、旧构建所写、校验和不符、僵尸进程并存）→ 纯默认。 */
    fun snapshot(): State {
        val f = file()
        val raw = if (f.exists() && f.length() > 0) {
            runCatching { f.readText() }.getOrNull()
        } else {
            null
        }
        AppLog.putDebug(
            "SyncSelection 原始文件: len=${raw?.length ?: -1} mtime=${f.lastModified()} " +
                "head=${raw?.take(160) ?: "（无文件）"}"
        )
        val zombie = runCatching { detectOtherInstances() }.getOrNull()
        if (zombie != null) {
            AppLog.put(
                "SyncSelection ⚠️ 检测到本应用另一活进程(pid=$zombie, 本进程 pid=${android.os.Process.myPid()})，" +
                    "其内存旧状态可能覆盖本文件：不采纳磁盘文件，使用纯默认。" +
                    "请在系统设置→应用→强制停止本应用并重启设备。"
            )
            return defaultState()
        }
        val persisted = raw?.let {
            runCatching { GSON.fromJsonObject<Persisted>(it).getOrNull() }.getOrNull()
        }
        if (persisted == null || persisted.schema != SCHEMA ||
            persisted.builtWith == null || persisted.writerPid == null ||
            persisted.writtenAt == null || persisted.items == null ||
            persisted.checksum == null
        ) {
            AppLog.putDebug("SyncSelection 文件缺失或旧格式/解析失败 → 使用纯默认（旧文件已尽力删除） path=$f")
            deleteLegacy()
            return defaultState()
        }
        val myVc = versionCode()
        if (myVc < 0 || persisted.builtWith != myVc) {
            AppLog.putDebug(
                "SyncSelection 文件由其他构建(vc=${persisted.builtWith}, 当前vc=$myVc)所写 → 不采纳，使用纯默认 path=$f"
            )
            deleteLegacy()
            return defaultState()
        }
        if (persisted.writerPid != android.os.Process.myPid() && pidAlive(persisted.writerPid)) {
            AppLog.put(
                "SyncSelection ⚠️ 文件写入者进程(pid=${persisted.writerPid})仍存活" +
                    "（本进程 pid=${android.os.Process.myPid()}）→ 不采纳，使用纯默认"
            )
            return defaultState()
        }
        if (checksumOf(persisted) != persisted.checksum) {
            AppLog.put(
                "SyncSelection ⚠️ 文件校验和不符（内容被篡改/存储层异常）→ 不采纳，使用纯默认 path=$f"
            )
            deleteLegacy()
            return defaultState()
        }
        return State(
            items = leafKeys.associateWith { persisted.items[it] ?: true },
            advanced = advancedKeys.associateWith { persisted.advanced?.get(it) ?: false },
            source = "文件（校验通过，vc=${persisted.builtWith}，写入于 ${persisted.writtenAt}）",
        )
    }

    /** 叶子/进阶项是否勾选（现读现校验；未知 key 按默认：叶子=勾选，进阶=不勾选） */
    fun isSelected(key: String): Boolean = snapshot().items[key] ?: true

    /** 进阶项是否勾选（现读现校验） */
    fun isAdvanced(key: String): Boolean = snapshot().advanced[key] ?: false

    // ==================== 唯一写盘路径 ====================

    data class SaveResult(val ok: Boolean, val detail: String)

    /** 用户确认后的唯一写盘路径：内容只由传入快照序列化（不读任何共享态，免疫运行时翻转），
     *  原子写（.tmp+rename）→ 回读 → 校验和核对，不符最多强制重写 3 次。 */
    fun confirm(items: Map<String, Boolean>, advanced: Map<String, Boolean>): SaveResult {
        val itemsC = leafKeys.associateWith { items[it] ?: true }
        val advC = advancedKeys.associateWith { advanced[it] ?: false }
        AppLog.putDebug(
            "SyncSelection 确认写入: 未勾选叶子=${itemsC.filter { !it.value }.keys} " +
                "进阶勾选=${advC.filter { it.value }.keys}"
        )
        val base = Persisted(
            schema = SCHEMA,
            builtWith = versionCode().coerceAtLeast(0),
            writerPid = android.os.Process.myPid(),
            writtenAt = System.currentTimeMillis(),
            items = itemsC,
            advanced = advC,
            checksum = ""
        )
        val persisted = base.copy(checksum = checksumOf(base))
        val f = file()
        var rewrite = 0
        while (true) {
            writeAtomic(f, GSON.toJson(persisted))
            val reread = runCatching { readPersisted(f) }.getOrNull()
            if (reread != null &&
                reread.checksum == persisted.checksum &&
                reread.items == persisted.items &&
                reread.advanced == persisted.advanced
            ) {
                AppLog.putDebug("SyncSelection 保存成功（原子写+回读+校验和通过） path=$f")
                return SaveResult(true, "回读校验通过")
            }
            if (rewrite >= 3) {
                AppLog.put(
                    "SyncSelection ⚠️ 三次强制重写后回读校验仍不符（存储层可能持续异常）: " +
                        "期望checksum=${persisted.checksum?.take(16)} 实际=${reread?.checksum?.take(16) ?: "无"}"
                )
                return SaveResult(false, "回读校验失败（详见调试日志）")
            }
            rewrite++
            AppLog.put("SyncSelection 回读校验不符，强制重写 #$rewrite path=$f")
        }
    }

    // ==================== 内部工具 ====================

    private fun file(): File = File(FileUtils.getPath(appCtx.filesDir, FILE_NAME))

    private fun versionCode(): Long = try {
        appCtx.packageManager.getPackageInfo(appCtx.packageName, 0).longVersionCode
    } catch (e: Exception) {
        -1L
    }

    private fun pidAlive(pid: Int): Boolean =
        pid > 0 && File("/proc/$pid").exists()

    /** 检测本包名是否存在另一个活进程（僵尸实例）。
     *  本 ROM（Android 10/联发科）上冻结进程的 cmdline 可能为空，需回退读 status 的 Name 字段。 */
    private fun detectOtherInstances(): Int? {
        val me = android.os.Process.myPid()
        val proc = File("/proc")
        val dirs = try {
            proc.listFiles { f -> f.name.isNotEmpty() && f.name.all { it.isDigit() } }
        } catch (e: Exception) {
            AppLog.putDebug("SyncSelection 僵尸检测: 无法枚举 /proc: ${e.message}")
            return null
        } ?: run {
            AppLog.putDebug("SyncSelection 僵尸检测: /proc 枚举结果为空")
            return null
        }
        for (d in dirs) {
            val pid = d.name.toIntOrNull() ?: continue
            if (pid == me) continue
            try {
                val cmdFile = File(d, "cmdline")
                val cmd = if (cmdFile.exists()) runCatching { cmdFile.readText() }.getOrNull() else null
                if (!cmd.isNullOrBlank() && cmd.contains(appCtx.packageName)) return pid
                // cmdline 为空（本 ROM 冻结进程特性）→ 回退读 status 的 Name 字段
                val status = runCatching { File(d, "status").readText() }.getOrNull()
                    ?.lineSequence()?.firstOrNull { it.startsWith("Name:") }
                    ?.removePrefix("Name:")?.trim()
                if (status != null && appCtx.packageName in status) return pid
            } catch (_: Exception) {
            }
        }
        AppLog.putDebug("SyncSelection 僵尸检测: 扫描 ${dirs.size} 个进程，未发现其他本应用活实例（本进程 pid=$me）")
        return null
    }

    /** 回读持久化文件（写入校验用） */
    private fun readPersisted(f: File): Persisted? {
        if (!f.exists() || f.length() == 0L) return null
        val raw = runCatching { f.readText() }.getOrNull() ?: return null
        AppLog.putDebug("SyncSelection 回读文件: len=${raw.length} head=${raw.take(160)}")
        return runCatching { GSON.fromJsonObject<Persisted>(raw).getOrNull() }.getOrNull()
    }

    /** 规范化串（与序列化格式无关，仅取业务字段） */
    private fun canonical(p: Persisted): String = buildString {
        append("schema=").append(p.schema)
        append(";builtWith=").append(p.builtWith)
        append(";writerPid=").append(p.writerPid)
        append(";writtenAt=").append(p.writtenAt)
        append(";items=")
        (p.items ?: emptyMap()).toSortedMap().forEach { (k, v) ->
            append(k).append('=').append(v).append(',')
        }
        append(";advanced=")
        (p.advanced ?: emptyMap()).toSortedMap().forEach { (k, v) ->
            append(k).append('=').append(v).append(',')
        }
    }

    private fun checksumOf(p: Persisted): String =
        MessageDigest.getInstance("SHA-256")
            .digest(canonical(p).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** 原子写：先写 .tmp 再 rename（失败则 copy 覆盖），避免半截文件 */
    private fun writeAtomic(target: File, json: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            tmp.writeText(json)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
            }
        } finally {
            runCatching { tmp.delete() }
        }
    }

    /** 旧版本遗留文件（v1~v7 的 syncSelection.json 与更早的两套文件）：不再读取/迁移，仅尽力删除 */
    private fun deleteLegacy() {
        listOf(FILE_NAME, "backupSelector.json", "restoreIgnore.json").forEach { name ->
            runCatching { File(FileUtils.getPath(appCtx.filesDir, name)).delete() }
        }
    }

    // ==================== 调试信息（设置页摘要展示） ====================

    fun fileDebugInfo(): String {
        val f = file()
        if (!f.exists() || f.length() == 0L) return "文件不存在（当前按纯默认）"
        val raw = runCatching { f.readText() }.getOrNull()
            ?: return "文件无法读取（当前按纯默认）"
        val p = runCatching { GSON.fromJsonObject<Persisted>(raw).getOrNull() }.getOrNull()
            ?: return "文件无法解析（当前按纯默认）"
        if (p.schema != SCHEMA) return "旧格式文件（当前按纯默认）"
        val sb = StringBuilder()
        sb.append("schema=").append(p.schema)
        sb.append(" 构建vc=").append(p.builtWith)
        sb.append(" 写入进程pid=").append(p.writerPid)
        val zombie = runCatching { detectOtherInstances() }.getOrNull()
        if (zombie != null) {
            sb.append(" ⚠️另一活进程pid=").append(zombie)
        }
        sb.append(" 校验和=").append(if (p.checksum != null && p.checksum == checksumOf(p)) "通过" else "不符")
        sb.append(" 当前采用=").append(snapshot().source)
        return sb.toString()
    }
}
