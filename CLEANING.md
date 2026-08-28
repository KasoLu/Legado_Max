# 设置页精简 & 配置项迁移 — 工作跟踪

> 本文件是本次任务的**单一事实来源**（single source of truth）。
> 上下文窗口只负责执行；目标、决策、迁移映射、进度全以本文件为准。
> 每完成一步，更新「实施清单」的勾选与「进度日志」。

## 0. 目标（verbatim）

> "帮我梳理下，整个app现在提供的具体功能。我看设置页面里内容繁多，感觉很多东西应该都用不上，可以精简掉，提高稳定性和性能，优化使用体验。还有些配置项放置的地点也不合理，精简过后可以整体做一次排查迁移。"

交付：(1) 功能全景梳理 ✅(已完成，见会话) ；(2) 精简拥挤的"其它设置"页；(3) 迁移错置配置项到合理位置；(4) 性能/稳定性优化（启动监控开关）。

## 1. 已确认决策（用户拍板，2025 会话内）

| 决策点 | 选择 |
|---|---|
| A桶(调试/诊断项) | **移除代码**（但见 §2 的精化：2 项真删、3 项迁入高级页） |
| Web 服务设置 | **新建独立"Web 远程控制"设置页** |
| 性能监控(AppFreezeMonitor/DispatchersMonitor) | **加开关，默认关**（改为仅 `debugMode` 开启时初始化） |
| 执行方式 | **分批实施，每批 `assembleAppMaxDebug` 验证** |

## 2. 配置项处置映射（source → destination）

### 迁到 新「Web 远程控制」页 (pref_config_web.xml / WebServiceConfigFragment)
- `webPort` · `webServiceToken` · `webServiceAuthEnabled` · `webServiceWakeLock`
  - 主页 My 页保留 `webService` 主开关（快捷开/关），新增"Web 服务设置"入口指向新页。

### 迁到 新「高级设置」页 (pref_config_advanced.xml / AdvancedConfigFragment)
- 类别「性能与网络 advanced_network」:
  `customHosts` · `sourceEditMaxLine` · `Cronet` · `unsafeSsl` · `antiAlias` · `bitmapCacheSize` · `imageRetainNum` · `threadCount` · `preDownloadNum` · `backwardPreDownloadNum` · `checkSource` · `uploadRule`
- 类别「日志与诊断 log_and_diagnostic」:
  `recordLog` · `copyCrashLog` · `debugLogFloatingBall`

### 迁到 朗读设置 (pref_config_aloud.xml / ReadAloudConfigDialog)
- `mediaButtonOnExit` · `readAloudByMediaButton`
- `ignoreAudioFocus`：aloud 里已有定义 → **只删 other 里的重复项**（去重，保留 aloud 那份）

### 迁到 关于页 (about.xml / AboutFragment)
- `updateToVariant` · `autoUpdateVariant`（放到 check_update 附近）

### 迁到 精准管理页 (pref_precise_manage.xml / PreciseManageFragment)
- `cleanCache` · `clearWebViewData` · `shrinkDatabase`（维护操作，走 ConfigViewModel）

### 保留在「其它设置」主页 (pref_config_other.xml)
- `language`(顶) · 主活动组: `auto_refresh` `onlyUpdateRead` `defaultToRead` `showDiscovery` `showRss` `defaultHomePage`
- 增强功能组(残留): `tocPartialLoad` `tocPartialLoadInterval` `debugMode` `mdLinkInnerBrowser`
- 其它设置组(残留): `localPassword` `userAgent` `defaultBookTreeUri` `replaceEnableDefault` `autoClearExpired` `showAddToShelfAlert` `showMangaUi` `videoSetting`
- advancedConfig：批次5 起上提为『我的』页顶层入口（已不在本页）
- **其它设置页由 ~40 项 → ~18 项 + 1 入口**

## 3. A桶：删除 vs 迁移（精化决策，已发现依赖复杂度）

用户选"移除代码"，但排查发现 5 项里 3 项深度耦合，硬删风险大。最终：
- **真删代码**（耦合少，干净）:
  - `recordHeapDump`：删 PreferKey/AppConfig getter/PreferKey、`CrashHandler.kt` 的 OOM 自动堆转储分支(L86)、`AboutFragment.createHeapDump()` 的 `if(!recordHeapDump)` 提示。保留 about 页 `createHeapDump` 手动按钮（主动触发，非被动开销）。
  - `verboseUpdateLog`：删 PreferKey/AppConfig、`MainViewModel.kt:76` 的 `|| AppConfig.verboseUpdateLog`。
- **迁入高级页（保留代码，仅移出主页）**（深度耦合，删了会改变全局行为）:
  - `recordLog`：被 App.kt(LiveEventBus)/LogUtils/UrlUtil/ToastUtils/Glide/WebView/AppLog/两监控 引用 10+ 处 → 迁到高级页「日志与诊断」。
  - `copyCrashLog`：`CrashHandler.kt:83` 控制崩溃自动复制剪贴板 → 迁到高级页。
  - `debugLogFloatingBall`：`DebugFloatingBallManager` + 3 个 debug recorder 的 isEnabled → 迁到高级页。

## 4. 性能/稳定性：监控开关（批次4）

- 现状：`App.kt` 无条件调用 `AppFreezeMonitor.init(ctx)` + `DispatchersMonitor.init()`，二者内部 `if(!recordLog) return`。
  - `AppFreezeMonitor`：handler 是 lazy → 关闭时**零开销**（无需改，仅把条件扩到 `debugMode||recordLog`）。
  - `DispatchersMonitor`：`scope` 是**非 lazy**，`init()` 先跑 `scope.coroutineContext.cancelChildren()` → 触发 `Executors.newSingleThreadExecutor` 建线程，**即使关闭也常驻 1 个后台线程**。这是真实性能点。
- 方案：两者改为 `val on = debugMode || recordLog`；
  - `DispatchersMonitor`：`scope`/`dispatcher` 改 lazy + `started` 标志，off 时完全不建线程，on→off 时 `cancelChildren`。
  - `AppFreezeMonitor`：把两处 `recordLog` 判断改为 `debugMode || recordLog`（early-return + 内部 postDelayed 门控）。

## 5. 新增字符串（已加到 values/ 与 values-zh/）
`web_service_setting` · `advanced_setting` · `advanced_network` · `log_and_diagnostic`

## 6. 实施清单（分批 + 每批验证）

### 批次1：新建 2 个页面 + 迁移（低风险，纯新增/搬移）
- [x] values/strings.xml + values-zh/strings.xml 加 4 个字符串
- [x] ConfigTag.kt 增 `WEB_SERVICE_CONFIG` / `ADVANCED_CONFIG`
- [x] 新建 res/xml/pref_config_web.xml
- [x] 新建 res/xml/pref_config_advanced.xml
- [x] 新建 WebServiceConfigFragment.kt（port: webPort 数字框 / webServiceToken 对话框 / auth·wakeLock 开关切服务）
- [x] 新建 AdvancedConfigFragment.kt（port: 数字框 / customHosts 对话框 / checkSource / uploadRule / recordLog / debugLogFloatingBall / unsafeSsl 重启提示）
- [x] ConfigActivity.kt 接线 2 个新 tag
- [x] pref_main.xml 加 `web_service_setting` 入口 + MyFragment 处理点击
- [x] pref_config_other.xml 加 `advancedConfig` 入口 + OtherConfigFragment 处理点击
- [x] pref_config_other.xml **删掉所有迁出项**；OtherConfigFragment 删对应 handler/summary
- [x] pref_config_aloud.xml 加 `mediaButtonOnExit`/`readAloudByMediaButton`（ignoreAudioFocus 已存在）
- [x] about.xml 加 `updateToVariant`/`autoUpdateVariant`（自存储项，AboutFragment 无需 handler）
- [x] pref_precise_manage.xml 加 `cleanCache`/`clearWebViewData`/`shrinkDatabase` + PreciseManageFragment 处理（接 ConfigViewModel）
- [x] `./gradlew assembleAppMaxDebug` 验证 ✅(批次1)

### 批次2：A桶删/迁 代码
- [x] 删 `recordHeapDump`（PreferKey/AppConfig/CrashHandler OOM分支/AboutFragment 提示）
- [x] 删 `verboseUpdateLog`（PreferKey/AppConfig/MainViewModel.kt:76）
- [x] 确认 recordLog/copyCrashLog/debugLogFloatingBall 已从 other 移除、handler 已在 AdvancedConfigFragment
- [x] `./gradlew assembleAppMaxDebug` 验证 ✅(批次2)

### 批次3：监控开关
- [x] DispatchersMonitor：scope 改 lazy + started 标志，off 时零线程
- [x] AppFreezeMonitor：条件改 `debugMode || recordLog`
- [x] OtherConfigFragment：debugMode 变更亦触发 AppFreeze/Dispatchers Monitor 初始化
- [x] `./gradlew assembleAppMaxDebug` 验证 ✅(批次3)

### 收尾
- [x] 全量 `./gradlew assembleAppMaxDebug` ✅(批次3 构建即全量, exit 0)
- [ ] 复查各新页在真机/模拟器可正常进入、开关生效（需设备；代码级接线已核对）
- [ ] 更新 updateLog.md?（触发 CI 官方发布，待用户确认）

## 7. 关键实现注意事项
- Fragment 生命周期：新 Fragment 用 `onCreate` 注册 `OnSharedPreferenceChangeListener` + `onDestroy` 注销（对齐 Theme/Cover/Welcome）。
- `startActivity(Intent(requireContext(), DirectLinkUploadActivity::class.java))`（uploadRule 入口）。
- `checkSource` 用 `showDialogFragment<CheckSourceConfig>()`；summary 用 `CheckSource.summary`。
- `bitmapCacheSize` 变更需 `ImageProvider.bitmapLruCache.resize(ImageProvider.cacheSize)`。
- `threadCount` 变更需 `postEvent(PreferKey.threadCount, "")`。
- web 相关开关变更若 `WebService.isRun` 则 stop+start 重启服务。
- `recordLog` 变更 handler 顺序：`AppConfig.recordLog=` → `LogUtils.upLevel()` → `logDeviceInfo()` → `LiveEventBus.config().enableLogger(...)` → `AppFreezeMonitor.init(appCtx)` → `DispatchersMonitor.init()`。
- ⚠️ `mediaButtonOnExit` 不在 PreferKey 里，用字符串 key 读取。
- 编译用 `gradlew.bat`（Windows），JDK17，flavor=appMax。

## 8. 进度日志
- [x] 完成功能全景 + 设置页审计（会话内已汇报）
- [x] 用户确认 4 项决策
- [x] 摸清全部引用点（blast radius）、迁移目标页结构（about.xml/My页/精准管理页）
- [x] 建 CLEANING.md
- [x] 加 4 个字符串（中英）
- [x] ConfigTag + 2 个新 XML
- [x] 批次1：2 新 Fragment + 接线 + 迁移，assembleAppMaxDebug ✅
- [x] 批次2：删 recordHeapDump/verboseUpdateLog，assembleAppMaxDebug ✅
- [x] 批次3：DispatchersMonitor lazy+started / AppFreezeMonitor 条件 / debugMode 触发监控，assembleAppMaxDebug ✅
- [ ] 收尾：待真机复查 + updateLog.md（需用户确认）
- [x] 批次5：『我的』页入口重排为 4 分组 + 高级设置上提顶层，assembleAppMaxDebug ✅

## 10. 『我的』页全量设置分级清单（阶段1：我整理 → 待用户筛选）
> 新任务：把「我的」页下所有层级设置按功能重分组 + 按与阅读核心功能相关度排序（常用置顶、冷门收底）。
> 分层：T1 核心常用（每日用）｜T2 常用/情境｜T3 冷门/高级｜T4 基本不用（可移除候选）。
> 用法：每项前 `[x]`=我会用(保留)；留空 `[ ]`=用不上(下轮先屏蔽并记入待删)。页内已按相关度重排。
> 批次6（本轮）：pref_main.xml 重排为 5 层（见 10.1）+ 新增 3 个分组标题字符串，assembleAppMaxDebug 验证。

### 10.1 『我的』页入口重排（pref_main.xml，4组→5层，常用置顶/冷门收底）
| 层 | 分组 | 入口项（顺序=相关度） |
|---|---|---|
| L1 | 阅读偏好 | 主题模式 · 主题设置 · 其它设置 |
| L2 | 书源与规则 | 书源管理 · 替换净化 · TXT目录规则 · 字典规则 · 调试工具 |
| L3 | 数据与记录 | 书签 · 阅读记录 · 备份与同步 · 精准管理 |
| L4 | 高级与扩展 | 高级设置 · Web远程控制[开关] · Web服务设置 |
| L5 | 关于 | 关于 · 退出 |

（新增字符串：pref_reading=阅读偏好 / pref_data=数据与记录 / pref_advanced=高级与扩展；source_rule 复用）

### 10.2 其它设置（setting → OTHER_CONFIG，pref_config_other.xml；页内已重排，常用在上）
- [ ] T1 auto_refresh 自动刷新
- [ ] T2 language 语言
- [ ] T2 defaultHomePage 默认主页
- [ ] T2 showDiscovery 显示发现页
- [ ] T2 autoClearExpired 自动清理过期数据
- [ ] T2 showAddToShelfAlert 加入书架提示
- [ ] T3 onlyUpdateRead 仅更新在读（依赖自动刷新）
- [ ] T3 defaultToRead 默认进入阅读
- [ ] T3 showRss 显示 RSS 页
- [ ] T3 tocPartialLoad 目录不完全加载
- [ ] T3 debugMode 调试模式
- [ ] T3 mdLinkInnerBrowser MD链接用内置浏览器
- [ ] T3 localPassword 本地密码
- [ ] T3 userAgent 用户代理
- [ ] T3 defaultBookTreeUri 默认书库目录
- [ ] T3 replaceEnableDefault 替换规则默认启用
- [ ] T3 showMangaUi 漫画 UI
- [ ] T3 videoSetting 视频设置
- [ ] T3 process_text 文本菜单处理
- [ ] T4 tocPartialLoadInterval 目录加载间隔

### 10.3 高级设置（advancedConfig → ADVANCED_CONFIG，pref_config_advanced.xml）
**性能与网络**
- [ ] T2 checkSource 书源检查
- [ ] T3 Cronet 使用 Cronet 网络引擎
- [ ] T3 unsafeSsl 忽略 SSL 证书错误
- [ ] T3 antiAlias 字体抗锯齿
- [ ] T3 bitmapCacheSize 图片缓存大小
- [ ] T3 imageRetainNum 图片保留数量
- [ ] T3 threadCount 并发线程数
- [ ] T3 preDownloadNum 预下载章节数
**日志与诊断**（深度耦合，仅迁来、暂不删码）
- [ ] T4 customHosts 自定义 Hosts
- [ ] T4 sourceEditMaxLine 书源编辑最大行数
- [ ] T4 backwardPreDownloadNum 向后预下载
- [ ] T4 uploadRule 直链上传规则
- [ ] T4 recordLog 记录日志
- [ ] T4 copyCrashLog 复制崩溃日志
- [ ] T4 debugLogFloatingBall 日志悬浮球

### 10.4 主题设置（theme_setting → THEME_CONFIG，pref_config_theme.xml）
- [ ] T1 themeList 主题列表
- [ ] T2 fontScale 字号
- [ ] T2 coverConfig 封面设置（→ 封面页，见 10.4b）
- [ ] T3 launcherIcon 应用图标
- [ ] T3 welcomeStyle 欢迎页设置（→ 欢迎页，见 10.4c）
- [ ] T3 transparentStatusBar 状态栏沉浸
- [ ] T3 immNavigationBar 导航栏沉浸
- [ ] T4 barElevation 栏高度
**白天/夜间配色（仅自定义主题时用到）**
- [ ] T3 白天：colorPrimary / colorAccent / colorBackground / colorBottomBackground / backgroundImage / transparentNavBar / saveDayTheme
- [ ] T3 夜间：colorPrimaryNight / colorAccentNight / colorBackgroundNight / colorBottomBackgroundNight / backgroundImageNight / transparentNavBarNight / saveNightTheme

### 10.4b 封面设置（coverConfig → COVER_CONFIG，pref_config_cover.xml）
- [ ] T2 白天：defaultCover / coverShowName / coverShowAuthor
- [ ] T2 夜间：defaultCoverDark / coverShowNameN / coverShowAuthorN
- [ ] T3 loadCoverOnlyWifi 仅 WiFi 加载封面
- [ ] T3 coverRule 封面规则
- [ ] T3 useDefaultCover 使用默认封面
- [ ] T4 coverHtmlEnable 封面 HTML 解析
- [ ] T4 coverHtmlCode 封面 HTML 代码（隐藏项，仅 enable 时显示）
- [ ] T4 coverGallery 封面画廊

### 10.4c 欢迎页（welcomeStyle → WELCOME_CONFIG，pref_config_welcome.xml）
- [ ] T4 welcomeShowTime 启动页显示时长
- [ ] T3 customWelcome 自定义启动页
- [ ] T4 白天：welcomeImagePath / welcomeShowText / welcomeShowIcon
- [ ] T4 夜间：welcomeImagePathDark / welcomeShowTextDark / welcomeShowIconDark

### 10.5 Web 远程控制（web_service_setting → WEB_SERVICE_CONFIG，pref_config_web.xml；首页另有 webService 总开关）
- [ ] T3 webPort 服务端口
- [ ] T4 webServiceAuthEnabled 启用访问鉴权
- [ ] T4 webServiceToken 访问令牌
- [ ] T4 webServiceWakeLock 保持唤醒

### 10.6 备份与同步（web_dav_setting → BACKUP_CONFIG，pref_config_backup.xml；页内已按相关度重排）
**WebDAV**
- [ ] T2 web_dav_url / web_dav_account / web_dav_password 地址·账号·密码
- [ ] T2 syncBookProgress 同步阅读进度
- [ ] T3 webDavDeviceName 设备名
- [ ] T3 web_dav_backup 备份到 WebDAV
- [ ] T3 web_dav_restore 从 WebDAV 恢复
- [ ] T4 webDavDir 子目录
- [ ] T4 syncBookProgressPlus 同步进度(含章节)
**备份/恢复**
- [ ] T2 backupUri 本地备份路径
- [ ] T3 backup_selector 备份选择器
- [ ] T3 book_cache_selector 书籍缓存选择
- [ ] T3 restoreShowSelector 恢复显示选择器
- [ ] T3 onlyLatestBackup 仅最新备份
- [ ] T3 autoCheckNewBackup 自动检查新备份
- [ ] T4 restoreIgnore 恢复时忽略
- [ ] T4 import_old 导入旧版数据
- [ ] T4 viewBackupInfo 查看备份信息
- [ ] T4 viewRestoreInfo 查看恢复信息

### 10.7 精准管理（preciseManage → PRECISE_MANAGE，pref_precise_manage.xml）
- [ ] T2 cleanCache 清理缓存
- [ ] T3 sourceRecycleBin 书源回收站
- [ ] T3 downloadManage 下载管理
- [ ] T3 storageManage 存储管理
- [ ] T3 clearWebViewData 清理 WebView 数据
- [ ] T4 urlRecord URL 记录
- [ ] T4 moduleStatus 模块状态
- [ ] T4 fileManage 文件管理
- [ ] T4 shrinkDatabase 收缩数据库

### 10.8 关于（about → about.xml；页内已按相关度重排）
- [ ] T2 check_update 检查更新
- [ ] T3 update_log 更新日志
- [ ] T3 privacyPolicy 隐私政策
- [ ] T3 license 开源许可
- [ ] T3 disclaimer 免责声明
- [ ] T4 contributors 贡献者
- [ ] T4 updateToVariant 更新到指定渠道
- [ ] T4 autoUpdateVariant 自动更新渠道
- [ ] T4 crashLog 崩溃日志
- [ ] T4 saveLog 保存日志
- [ ] T4 createHeapDump 创建堆转储（手动按钮，代码保留）

### 10.9 朗读设置（阅读 UI 内 ReadAloudConfigDialog 加载，非「我的」树；pref_config_aloud.xml）
- [ ] T2 appTtsEngine TTS 引擎
- [ ] T3 pauseReadAloudWhilePhoneCalls 通话时暂停
- [ ] T3 readAloudWakeLock 朗读保持唤醒
- [ ] T3 mediaButtonPerNext 媒体键切换
- [ ] T3 readAloudByPage 按页朗读
- [ ] T3 streamReadAloudAudio 流式朗读
- [ ] T3 readAloudFloatingUi 悬浮窗
- [ ] T3 sysTtsConfig 系统 TTS 设置
- [ ] T4 ignoreAudioFocus 忽略音频焦点
- [ ] T4 mediaButtonOnExit 媒体键（退出）
- [ ] T4 readAloudByMediaButton 媒体键（朗读）

### 10.10 阅读 / 漫画设置（阅读 UI 内，非「我的」树，本轮不纳入，另行处理）
- pref_config_read.xml：约 40 项（screenOrientation / keep_light / hideStatusBar / hideNavigationBar / readBodyToLh / … / textMenuConfig / showBrightnessView / clickImgWay / optimizeRender / readBarStyleFollowPage 等）
- pref_manga_quick_setting.xml：约 7 项（mangaReadMode / mangaPreDownloadNum / disableMangaPageAnim / disableMangaScale / disableClickScroll / hideMangaTitle / mangaHideFooterInfo）

### 10.11 筛选后动作（阶段2，待用户勾选后执行）
1. 用户在 §10.2–10.10 勾选「会用」的项；留空项 = 用不上。
2. 对留空项：先从对应 pref_*.xml 移除 <Preference>（下线/隐藏），保留 PreferKey 常量与读取代码，避免破坏逻辑。
3. 汇总「可下线项」清单 → 后续批次再评估是否连代码一起删（深度耦合项谨慎）。
4. 每改动一批跑 assembleAppMaxDebug 验证。

> **批次6 完成（本轮）**：pref_main.xml 重排 4组→5层（阅读偏好/书源与规则/数据与记录/高级与扩展/关于）+ 新增 3 字符串（pref_reading/pref_data/pref_advanced）+ 本 §10 全量分级清单（§10.1–10.11）。assembleAppMaxDebug ✅（APK 55.8MB，2026-08-28 19:50:50）。
>
> **批次6b（本轮·goal round）**：子页内按相关度重排（常用置顶、冷门收底）——pref_config_advanced.xml（checkSource 置顶、T4 收底）/ pref_config_other.xml（主活动/增强/其它三组按 T 级重排）/ pref_precise_manage.xml（cleanCache 置顶、T4 管理入口收底）。纯重排，保留全部属性/常量/代码，assembleAppMaxDebug 验证。

> **批次6c（本轮·goal round 2）**：补齐功能组织型子页重排 —— pref_config_backup.xml（WebDAV 组：syncBookProgress/地址/账号/密码 前置 → webDavDeviceName → webDavDir → syncBookProgressPlus；备份恢复组：backupUri 前置 → backup_selector/book_cache_selector → restoreShowSelector/onlyLatestBackup/autoCheckNewBackup → web_dav_backup/web_dav_restore → restoreIgnore/import_old → viewBackupInfo/viewRestoreInfo 收底）+ about.xml（check_update 置顶 → update_log → updateToVariant → autoUpdateVariant → contributors；其它组：privacyPolicy/license → crashLog/saveLog/createHeapDump → disclaimer 收底）。pref_config_web.xml 当前序已=目标序，未改；主题/封面/欢迎页按白天·夜间配色方案分组，保持原序。纯重排，assembleAppMaxDebug ✅（APK 55.77MB，2026-08-28 20:01:03）。

### 10.12 默认屏蔽建议（阶段3 预备，待用户确认即可执行）
> 若用户选「按 T4 默认屏蔽」，下批将从对应 pref_*.xml 删除下列 <Preference>（**保留 PreferKey 常量与读取代码**，仅下线 UI 项）：
- 其它设置：tocPartialLoadInterval
- 高级设置：customHosts、sourceEditMaxLine、backwardPreDownloadNum、uploadRule、recordLog、copyCrashLog、debugLogFloatingBall
- 主题设置：barElevation
- 封面设置：coverHtmlEnable、coverHtmlCode、coverGallery
- 欢迎页：welcomeShowTime 及自定义欢迎页 day/night 项
- Web 设置：webServiceAuthEnabled、webServiceToken、webServiceWakeLock（若整块不用 → 连同首页 webService 开关 + web 页一并下线）
- 备份：webDavDir、syncBookProgressPlus、restoreIgnore、import_old、viewBackupInfo、viewRestoreInfo
- 精准管理：urlRecord、moduleStatus、fileManage、shrinkDatabase
- 关于：contributors、updateToVariant、autoUpdateVariant、crashLog、saveLog
- 整页候选（若整块不用）：Web 远程控制 / 漫画(showMangaUi) / 字典(dictRuleManage) / TXT目录(txtTocRuleManage) / 视频(videoSetting)
> 其余（T1/T2/常用 T3）默认保留。用户确认或微调后 → 阶段3 执行并逐批 assembleAppMaxDebug。
> **批次6d（本轮·阶段3 执行）**：用户选 B（按页）——**Web远程控制 / 视频 / 漫画 / 欢迎页 / 朗读** 暂时用不上 → 已从「我的」页对应 `pref_*.xml` 移除入口（保留 PreferKey 常量与读取代码）：`pref_main.xml` 删 Web 总开关 `webService` + `web_service_setting` 入口（第四层现仅留「高级设置」）；`pref_config_other.xml` 删 `videoSetting`（视频）+ `showMangaUi`（漫画）；`pref_config_theme.xml` 删 `welcomeStyle`（欢迎页）。朗读的 `pref_config_aloud.xml` 在阅读界面（ReadAloudConfigDialog）、不在「我的」树，按范围原则本轮不动。各 Fragment 已核对：相关项均为 onPreferenceTreeClick 分支或 `?.` 空安全，移除 XML 项不会 NPE。

### 10.13 范围原则 + 阶段3 实际执行（用户已确认）
- **范围原则**（用户澄清）：
  1. 「我的」页 = **app 级**设置；「阅读界面」= **阅读核心**设置（排版等）。
  2. 阅读界面「更多设置」= 从阅读界面**可直达、与阅读直接关联**的项（排版、书评拉起的 webview 弹窗等）。
  → app 级归「我的」，阅读直达项归阅读 UI，互不越界；本轮仅整理「我的」页，阅读 UI 不动。
- **用户最终决定（选 B·按页）**：Web远程控制 / 视频 / 漫画 / 欢迎页 / 朗读 暂时屏蔽（详见上方批次6d）。
- **后续可删代码清单（保留至今，待评估）**：
  - Web远程控制（整块）：`pref_config_web.xml` + `WebServiceConfigFragment.kt` + `WebService.kt`/`WebTileService` + `api/controller/*` + `assets/web/`
  - 视频（整块）：`videoSetting` 对话框 + `ui/video/` + `VideoPlayService` + `help/gsyVideo/`
  - 漫画：`showMangaUi` 相关 + `ReadMangaActivity` + 漫画规则
  - 欢迎页：`pref_config_welcome.xml` + `WelcomeConfigFragment` + 启动页逻辑
  - 朗读（在阅读 UI，另议）：`pref_config_aloud.xml` + `TTSReadAloudService`/`HttpReadAloud`

## 11. 代码移除阶段（按 §10.13 清单删码，每块 assembleAppMaxDebug 验证）

### 11.0 执行顺序（据代码耦合面重排）
1. **视频**（✅ 完成，assembleAppMaxDebug exit 0）——GSY 独立，`BookType.video`(位值4) 深嵌书籍类型系统；`media3`/ExoPlayer 被**音频朗读共用**→只删 GSY+弹幕，保留 media3。已删类型/DAO/播放器/依赖/消费方/15 资源。
2. **漫画**（✅ 完成）——`BookType.image`(图片书类型) **保留**（主阅读器共用），只删漫画专属：删 `ReadManga.kt`/`MangaReadMode.kt`/`ui/book/manga/` 整包/`MangaMenu`/`LegadoDataUrlLoader`+9 资源；`okHttpClientManga`（**漫画专用客户端**，非全局）直接删（其 rateLimiter 只被漫画 `upWebBook` 设置，删后无需迁移，全局 `okHttpClient` 不受影响）；`OkHttpStreamFetcher` 去 manga 分支/`OkHttpModelLoader` 删 `mangaOption`/`BookCover` 去 2 处设置/`LegadoGlideModule` 去 `LegadoDataUrlLoader.Factory()` 注册；图片书重路由到 `ReadBookActivity`（`ContextExtensions`/`FragmentExtensions`/`BookInfoActivity`）+`SourceHelp`/`BookInfoViewModel` 去 `ReadManga` 分支；删 `AppConfig` ~13 属性 + `PreferKey` 13 键（**保留 `disableHorizontalPageSnap`** 翻页共用）+ `MangaReadMode` import + Manifest `ReadMangaActivity`。残留（可选）：`BookCover.loadManga`/`preloadManga` 死函数（未调用）。
3. **Web 远程控制**（✅ 完成，exit 0）——独立子系统（NanoHTTPD `web/` + `api/controller/*` + `WebService`/`WebTileService` + `assets/web/` 前端[保留 help/] + `pref_config_web`/`WebServiceConfigFragment`）+ nanohttpd 依赖 + 全部引用，已删。
4. **欢迎页**（✅ 完成，exit 0）——用户决策保留启动器图标、仅删闪屏：`WelcomeActivity` 保留作入口 + `Launcher1..8` 变体 + `LauncherIconHelp`；删 `WelcomeConfigFragment`/`pref_config_welcome`/欢迎键属性/`WELCOME_CONFIG` 路由。
- **朗读** 暂不删（在阅读 UI + 深度耦合书源 TTS 规则），另议。

### 11.1 视频块手术清单
**已完成（结构性，本批）：**
- [x] 删 `ui/video/`（VideoPlayerActivity/VideoPlayerViewModel/ChapterAdapter/config.SettingsDialog）
- [x] 删 `help/gsyVideo/`（VideoPlayer/FloatingPlayer/ExoVideoManager/ExoPlayerManager/Exo2MediaPlayer/Choice*/Switch*/Danmaku*/BiliDanmukuParser）
- [x] 删 `model/VideoPlay.kt`、`service/VideoPlayService.kt`
- [x] AndroidManifest：删 `VideoPlayerActivity`(activity) + `VideoPlayService`(service)
- [x] app/build.gradle：删 `libs.gsyVideoPlayer.java/.exo2` + `libs.danmakuFlameMaster`（保留 media3）
- [x] libs.versions.toml：删 `gsyvideoplayer="11.3.0"` + `gsyVideoPlayer-java/.exo2` + `danmakuFlameMaster`
**引用清理（已完成，待构建确认）：**
- [ ] BookType.kt：删 `video=0b100` 常量 + `@IntDef` 里的 video + `allBookType`/`allBookTypeLocal` 去 video
- [ ] BookSourceType.kt：删 `video=4` + `@IntDef`
- [ ] BookGroup.kt：删 `IdVideo` 常量 + 名称映射
- [ ] BookGroupDao.kt(36/42/51)、BookDao.kt(53/61)：SQL 去掉 `type & ${BookType.video}`
- [ ] BookSourceExtensions.kt(148)：删 `BookSourceType.video -> BookType.video` 分支
- [ ] BookSourceEditActivity.kt(511/646)、BookInfoEditActivity.kt(129/134)：类型映射去 video
- [ ] BookExtensions.kt(43)：删 `isVideo`
- [ ] SourceHelp.kt(19-21/50-51/204/212)、ContextExtensions.kt(49/71)、FragmentExtensions.kt(24/143)、BookInfoActivity.kt(93/1184)、ReadRss.kt(14/38/65)、ReadRssActivity.kt(104/1046)、RssJsExtensions.kt(18/265-267)、SourceLoginViewModel.kt(16/45-48)：删 import + 视频分支
- [ ] Backup.kt(54/113/160/590-592)、Restore.kt(49/490-492/847-848)、BackupController.kt(15/254/429)、BackupSelectorConfig.kt(47)、BackupInfoHelper.kt(67/110/214)：删 videoConfig 备份项 + `VIDEO_PREF_NAME` import
- [ ] PreferKey.kt(10)：删 `videoSetting`；NotificationId.kt(16)：删 `VideoPlayService`；OtherConfigFragment.kt(92)：删 videoSetting 分支 + `SettingsDialog` import
- [ ] toml(28)：删 `danmaku="0.9.25"` 残留版本
- [ ] （可选）删视频专属布局/菜单/字符串：activity_video_player、dialog_video_settings、video_layout_*、floating_video_player、switch_video_dialog_item、item_video_chapter*、menu/video_play、`@string/video` 等
- [x] **assembleAppMaxDebug 验证 exit 0 ✅**（首次因残留 video_layout_*×4 资源引用已删 GSY 的 drawable 链接失败；删全部 15 个视频资源后通过）

### 11.6 Web 前端源码 + CI 彻底清除（✅ 本次 exit 0）
- 删 `modules/web/`（Vue3 前端源码）+ `.github/workflows/web.yml`（`pnpm build`+自动提交回 `assets/web/vue/`；自动提交仅上游 GEd520/Legados 生效）。现状 `modules/`=book/rhino；workflows=test/release/cronet/stale。
- **WebView/书评/内置帮助 安全**（用户要求"webview 页面要能打开，例如书评"）：`showHelp`（ActivityExtensions.kt:251）= 原生 `TextDialog(Mode.MD)` 读 `assets/web/help/md/`（保留，**非 WebView**）；书评=`ReviewRule.reviewUrl`（段评URL）走网络 `WebViewActivity`；所有 WebView 入口（规则调试/打开URL/TTS/浏览器）走 `ui/browser/WebViewActivity` 网络 URL；`assets/web/help/`（index.html/js/css）保留 → 删 modules/web+web.yml 对 WebView 零影响。
- **Web 远程控制彻底清除**：后端 `web/`+`api/` + 前端产物 `assets/web/{vue,uploadBook,index.html,assets,favicon}` + 源码 `modules/web/` + CI `web.yml` 全删；仅保留 `assets/web/help/`（内置 Markdown 帮助）。上游 GEd520/Legados 仍带 web 功能，日后 merge 需留意。

### 11.7 朗读(TTS) 方案A：隐藏入口（代码保留）——`view_read_menu.xml` 的 `ll_read_aloud`→`visibility="gone"`；`rss_read.xml`/`content_select_action.xml` 的 `menu_aloud`→`visible="false"`；`AudioPlay`(真人演播) 保留，代码全保留未删。

### 11.8 用户澄清修正：恢复启动闪屏 + 删首次安装弹窗（✅ assembleAppMaxDebug exit 0）
> 用户：'我以为是首次安装的弹窗，不是启动时那个页面。将启动时的欢迎页回滚恢复，将首次安装之后的弹窗去掉。'
- **恢复启动闪屏**（之前误删）：`git checkout HEAD` 还原 `WelcomeActivity.kt`(闪屏 welcomeShowTime+postDelayed+upBackgroundImage，保留 Launcher1..8)/`WelcomeConfigFragment.kt`/`pref_config_welcome.xml`；外科补回 `ConfigTag.WELCOME_CONFIG`+`ConfigActivity`路由(同包免import)+`ThemeConfigFragment` welcomeStyle handler+`PreferKey` 8键(customWelcome+welcomeShowTime/Image[Dark]/ShowText[Dark]/ShowIcon[Dark])+`AppConfig` 6属性+`pref_config_theme.xml` welcomeStyle 入口(HEAD 提交时删的,需加回)。
- **删首次安装弹窗**：`MainActivity.upVersion()` 里 `if(LocalConfig.isFirstOpenApp)` 原弹出的 `appHelp.md` 帮助对话框→改 `block.resume(null)`(首启无弹窗)。`isFirstOpenApp` 本身保留(`isLastVersion` 新老用户判断仍复用)；release 升级仍显示 updateLog 更新日志不变。strings(welcome_style 等)本未删,全语言齐全。视频/漫画/Web 已删代码保持不动。
