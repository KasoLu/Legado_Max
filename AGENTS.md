# Legado_Max — AGENTS.md

## Identity

阅读Max — An Android e-book reader forked from Legado. Supports custom book sources (Jsoup selectors + Rhino JS rules), RSS, and local TXT/EPUB.

## Build system

- **JDK 17 required** — all build commands use the Gradle wrapper (`gradlew.bat` on Windows).
- Three product flavors in dimension `app`:
  - `appMax` (`io.legado.app.yuedu`) — primary target, coexistence package
  - `appLegacy` (`io.legado.app`) — same as original Legado, can overwrite it
  - `appS` (`io.legado.app.yuedu.a`) — another coexistence package
- Debug builds: `./gradlew assembleAppMaxDebug`. No minification.
- Release builds: minify + ProGuard (`app/proguard-rules.pro` + `cronet-proguard-rules.pro`).
- **First build** or after Cronet version bump: `./gradlew app:downloadCronet` (downloads native .so from GCS).
- All dependency versions in `gradle/libs.versions.toml`. Referenced as `libs.xxx`.
- Annotation processing: **KSP**, never kapt.
- AGP 9.3.1, Kotlin 2.3.10.

### Version-pinned dependencies (DO NOT bump casually)

These are pinned with rationale in `libs.versions.toml`:

| Library | Version | Why pinned |
|---|---|---|
| jsoup | 1.16.2 | #3811 — 1.17+ breaks JsoupXpath and `AnalyzeByJSoup.kt` |
| commons-text | 1.13.1 | Newer releases use `Arrays.setAll` (missing pre-Android 6) |
| rhino (Mozilla) | 1.8.1 | Newer releases use `VarHandle.compareAndExchange` (missing pre-Android 8) |
| hutool-crypto | 5.8.22 | Do not update (commented in TOML) |

## Architecture overview

```
app/src/main/java/io/legado/app/
├── App.kt              # Application — init order: CrashHandler → theme → LiveEventBus → DB → Rhino → cleanup → WebDAV sync
├── base/               # BaseActivity<VB>, VMBaseActivity<VB,VM>, BaseViewModel
├── data/               # Room DB (AppDatabase v96), DAOs, entities, repositories
├── model/              # Domain logic: ReadBook, WebBook, analyzeRule (Jsoup/XPath/JSON/Regex), AudioPlay
├── help/               # Config, HTTP (OkHttp + Cronet), coroutine utilities, Rhino bridge
├── service/            # Background services (AudioPlay, Download, CacheBook, TTS)
├── ui/                 # Activities & Fragments grouped by feature
└── utils/              # ~100+ Kotlin extensions
```

3 library modules under `modules/`:
- `:modules:rhino` — Mozilla Rhino JS engine fork (package `com.script`). Evaluates user-defined book source rules.
- `:modules:book` — EPUB/UMD/MOBI parsing fork (package `me.ag2s.epublib`).

## Key patterns

### ViewModel + coroutines
- Extend `BaseViewModel`, use `execute { }` for async work — wraps `Coroutine.async()` on `Dispatchers.IO`, callbacks on `Dispatchers.Main`.
- Chain: `.onSuccess { } .onError { } .onFinally { }`.
- **Prefer `execute` over raw `viewModelScope.launch`**.

### Event bus
- `LiveEventBus` (not Android's LiveData) for cross-component events.
- Subscribe in `observeLiveBus()` (auto-cleaned on destroy).
- Config in `App.kt`: lifecycleObserverAlwaysActive(true), autoClear(false).

### Database
- Room `AppDatabase` (current version 96), singleton at `appDb`.
- KSP-generated DAOs, entities in `data/entities/`.
- Schema exported to `$projectDir/schemas` for migration verification.
- Migration test assets from schemas dir (`androidTest.assets.srcDirs`).

### Book source rule engine
- Users define rules in JSON (Jsoup selectors / XPath / JSONPath / Regex / JavaScript).
- `model/analyzeRule/` parses and executes rules via Rhino JS engine.
- Rhino gets registered wrappers in `App.initRhino()` — `BookSource`, `RssSource`, `HttpTTS` get `NativeBaseSource` factory; rule config types get `ReadOnlyJavaObject` factory.
- JS extension functions in `help/JsExtensions.kt` and `help/RegexJsExtensions.kt`.

### UI
- **ViewBinding + XML layouts** for most screens (traditional View system).
- **Jetpack Compose (Material3, BOM 2025.04.01)** for newer UI surfaces (e.g., debug log panel).
- Both coexist — ComposeViews can overlay View-based Activities.
- `NonTransitiveRClass` enabled — reference only directly used resources.

## Web 远程控制与 Web 前端（已删除）

内置 HTTP/WS 远程控制与 Vue 3 前端已彻底移除（后端 `web/`+`api/` 包、前端源码 `modules/web/`、前端产物 `assets/web/{vue,uploadBook,index.html,assets,favicon}`、CI `web.yml`）。仅保留 `assets/web/help/` —— 内置 Markdown 帮助，用原生 `TextDialog(Mode.MD)` 打开，非 WebView；书评（段评）走 `ReviewRule.reviewUrl` 网络 URL。上游 GEd520/Legados 仍带 web 功能，merge 时需留意冲突。

## CI/CD (GitHub Actions)

| Workflow | Trigger | What it does |
|---|---|---|
| `test.yml` | Push to main (any path except web) | Builds **all 3 release flavors**. If `updateLog.md` changed → official GitHub/Gitee release; else → beta pre-release. |
| `cronet.yml` | Weekly schedule or manual | Checks for Cronet updates, downloads if newer, creates PR. |
| `release.yml` | Manual (`workflow_dispatch`) | Builds all 3 flavors, creates release. |

### Release workflow quirks
- Version = `3.yy.MMddHH` (CST), computed in CI.
- Release notes: extracted from `app/src/main/assets/updateLog.md` — the latest `**20...` section.
- `18PlusList.txt` is **cleared** before every CI build.
- CI uses a **bundled debug keystore** (`.github/workflows/legado.jks`) for release signing. Password: `gedoor_legado`. Not for production distribution.
- Cronet version defined in `gradle.properties`: `CronetVersion=128.0.6613.40`, `cronet.json` tracks installed native libs.

## Tooling quirks

- **LSP**: Kotlin LSP is available if configured. No `.opencode/lsp.json` exists — configure manually if needed.
- **No opencode.json** — the project relies on `CLAUDE.md` for agent instructions. The superpowers-zh framework is embedded in `CLAUDE.md` (20 skills under `<!-- superpowers-zh:begin -->`).
- **No pre-commit hooks** — no husky/commitlint config found.
- Room schema: `ksp { arg("room.schemaLocation", ...) }` exports to `app/schemas/`.
- Core library desugaring enabled (`coreLibraryDesugaring`).

## 上下分屏阅读模式（upDownPageAnim）— 设计与已修复 bug

本 fork 新增翻页模式 `PageAnim.upDownPageAnim = 5`（`app/src/main/java/io/legado/app/constant/PageAnim.kt:18`）。核心文件：`ui/book/read/page/provider/ChapterProvider.kt`（upDownSplitY/upDownMargin）、`ui/book/read/page/ContentTextView.kt`（drawPage 分节渲染 + 触摸）、`ui/book/read/page/PageView.kt`、`ui/book/read/page/delegate/UpDownPageDelegate.kt`（动画）。`ReadView.kt` 无需改（九宫格点击自然触发 nextPageByAnim/prevPageByAnim）。

**最终模型（v3 定稿，无任何覆盖层）**：
- 无遮罩、无按钮、无额外视图：阅读区 = 连续文字卷轴 + 画在内容里的分割线（随内容滚动）。全屏触摸透传内容：命中文本/图片 → 点击/选择；空白处 → 九宫格翻页（按节滚动）。
- `upDownSplitY = visibleHeight/2`（一节 = 半屏，像素级严格对半，保持不变）；`upDownMargin = 10dp`（每节内部排版 padding，仅用于排版、不占滚动，排版可用高度 = upDownSplitY − upDownMargin）。
- 滚动步长 = 渲染节距 = 跨页修正量 = upDownSplitY（三者必须一致，否则分割线漂移/跳变）。
- 任意时刻恰好露出两整节，分割线永远在顶/中/底；节内文本按行填充（一节以整行结尾，放不下整体挪下一节，底部留白）；仅当内容（图片/不可断行块）真正超出一节高度时，节底部画渐隐遮罩，纯文本按行填满 → 不显示；整页图（封面）走 isFullPageText 全屏不分屏。
- 滚动直接复用 `ContentTextView.scroll(delta)`：pageOffset 连续累积，跨整页高（visibleHeight = 两节）才推进 pageFactory 且按整页高修正（连续不跳），书首书尾钳制 + abortAnim。无自由拖拽：按下不跟手，抬手（ACTION_UP）经九宫格触发。`UpDownPageDelegate.computeScroll` 每帧调 `curPage.scroll(delta.toInt())`，scroller 从 0 走到 ±一节，onAnimStop 为空，abortAnim 不弹回。

**已修复 bug 1：懒加载页（"数据加载中"）被分节裁切（2026-10-18）**：根因 = 工厂占位页缺 isLazyLoading 标志，被当普通内容进分节排版、裁成一条。修复：占位页整屏居中显示"数据加载中"，不画分割线；数据加载完成后自动恢复分节排版。

**已修复 bug 2：上下模式触摸失效（2026-10-19）**：三个根因 — A. `isScroll` 短路（upDownPageAnim ≠ scrollPageAnim → isScroll=false，`touch()`/`touchRough()` 在 relativePos>0 直接 return，第二屏节永远不命中；"滚一截"后稳态 pageOffset=−upDownSplitY，curPage 滑出屏幕，整屏触摸失效）；B. 触摸偏移用累计页高 `relativeOffset()`（pageOffset + textPage.height + nextPage.height），与渲染固定节距（pageOffset + N×upDownSplitY）坐标系不一致，偏差 = N×(upDownSplitY−page.height)；C. `touchRough()` 的 isTopLine/midY 过滤（排版时刻标志）与滑动窗口模型矛盾，下半屏行被无条件 continue。修复（全在 `ContentTextView.kt`）：新增 `touchOffset(relativePos)`（upDownPage 时返回 pageOffset + relativePos×upDownSplitY，否则原 relativeOffset），`touch()`/`touchRough()`/`getCurVisiblePage()`/`getReadAloudPos()` 四处改用 touchOffset，短路条件改为 `!callBack.isScroll && !ChapterProvider.upDownPage`，删除 isTopLine/midY 过滤。不变量：空白 → 九宫格按节滚动（行为不变）；命中行/列 → 内容点击/选择（本次修复）；分割线绘制、UpDownPageDelegate、自动翻页、左右/滚动模式零改动。

**已修复 bug 3：上下模式点按偶发"吞点"，动画第一帧后静默死亡（2026-10-23）**：根因 = 翻页动画的首个 `computeScroll` 与 `startScroll` 落在同一毫秒时 `currY=0`、`delta=0`，唯一的续帧来源是 `scroll()` 里的 `postInvalidate()`，delta=0 时不执行 → 不再有任何视图失效，动画整体停摆（实测死帧后 1 秒无任何绘制）；深层原因是 `postInvalidateOnAnimation` 的 ANIMATION 回调帧调度会整段丢失，普通 `invalidate()` 调度则可靠。修复（`UpDownPageDelegate.kt`）：动画帧无条件 `curPage.postInvalidate()` 之外，另加 Handler 兜底 `driveTick`——动画进行中每 16ms `readView.invalidate()` 补帧直到结束，`abortAnim`/`nextPageByAnim`/`prevPageByAnim` 处 `removeCallbacks` 清理。
**回归清单（待真机验证）**：① 上下分屏滚到"数据加载中"页 → 整屏居中显示、不裁成一条，加载完成后自动恢复分节排版；② 上下模式滚一截后：长按文本 → 出现选择手柄、拖选可跨节；点下半区神评/本章说入口图 → 弹出对应页面、不翻页；点空白 → 仍按节滚动/菜单；③ 截图（longScreenshot）与朗读在上下模式下取到的可见页/行位置正确；④ 左右翻页/滚动/封面/双页等其他模式点击与选择无回归；⑤ 上下模式任意位置（含章末占位节出现后、非最后页）反复点按空白区 → 每次都能滚一截，不再出现"点了没反应"。

**版本号格式（app/build.gradle）**：versionName = `0.1.yyMMdd.HHmmss`（GMT+8，如 `0.1.261019.115547`）。**坑**：SimpleDateFormat 小写 `mm` = 分钟、大写 `MM` = 月（曾误写成 yymmdd 导致 `0.1.263318...`）。versionCode 逻辑（`releaseCode()`，yyDDDHHmm）未动。

---

## 已完成改造备忘（过程文档已删除，以 git 历史与代码为准）

- **备份/恢复"树状选择"改造**（2026-09，九轮实机回归通过，**改动尚未 commit**）：27 项扁平 `BackupSelectorConfig` + 9 项恢复忽略列表（`BackupConfig`）合并为统一树状模型 `help/storage/SyncSelection.kt`（VERSION 3，v2 污染态一次性修复：保留其余勾选、强制 readStyle=false、回写 v3）。树面板 = 纯 View `ui/config/SyncSelectionPickerDialog.kt`（第八轮从 Compose 版 SyncSelectionDialog 改为纯 View；`FileValidationDialog` 并入 picker 格式检测，`ValidationResult/ValidationState` 移入 `help/storage/BackupFileValidator.kt`）。备份侧与恢复侧完全对齐（结构 + 显示名称一致）；手动备份/恢复必弹树面板（确认即持久化），WebDAV 自动备份/自动恢复不弹面板、直接跟随上次选择；默认（从未选过）= 除阅读排版（阅读样式）外全选。连带修复：`LogUtils` 文件 handler 懒创建（recordLog 关闭不建文件）+ Formatter 用记录自身时间戳。删除 `BackupSelectorConfig.kt`/`BackupConfig.kt` + `PreferKey.restoreIgnore`。帮助文档 `app/src/main/assets/web/help/md/缓存与导出备份机制.md` 已同步更新。
- **设置页精简与功能裁剪**（已 commit 7e5c1408/518f8cee）：删视频（BookType.video/BookSourceType.video/GSY 15 个视频资源）、漫画、**Web 远程控制**（见上节"Web 远程控制与 Web 前端（已删除）"）；朗读入口隐藏、代码保留（`view_read_menu.xml` ll_read_aloud→gone 等，AudioPlay 真人演播保留）；启动闪屏恢复（WelcomeActivity），首次安装弹窗删除（`MainActivity.upVersion` isFirstOpenApp→不弹，release 升级仍显示 updateLog）；"我的"页设置项重排。

---

## Gotchas

- **jsoup version is pinned** — bumping breaks JsoupXpath and AnalyzeByJSoup. Verify JsoupXpath compatibility first.
- **18PlusList.txt** is emptied during CI — do not rely on it persisting.
- **updateLog.md** drives the release system. If you modify it, the next CI run will create an official release (not a pre-release).
- **sync.js for web builds** only works in CI — set `GITHUB_ENV` or manually copy `dist/` when building locally.
- **Cronet native libs** are architecture-specific and large (tens of MB). `downloadCronet` fetches them from GCS.
- **Release APK signing** uses a hardcoded debug key in CI — fine for test builds, not for Play Store.
- The `modules/rhino` and `modules/book` are **forks** — sync with upstream carefully.
- Two icon variants exist: `Launcher1` and `Launcher2` activities (`enabled=false` by default) — they share the same MAIN/LAUNCHER intent-filter as alternate icons.
