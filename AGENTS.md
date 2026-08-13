# Legado_Max — AGENTS.md

## Identity

阅读Max — An Android e-book reader forked from Legado. Supports custom book sources (Jsoup selectors + Rhino JS rules), RSS, local TXT/EPUB, and an embedded HTTP/WS server for remote control.

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
├── web/                # NanoHTTPD + WebSocket server (HttpServer.kt, WebSocketServer.kt)
├── api/                # REST controllers (used by the web frontend)
├── service/            # Background services (AudioPlay, WebService, Download, TTS)
├── ui/                 # Activities & Fragments grouped by feature
└── utils/              # ~100+ Kotlin extensions
```

3 library modules under `modules/`:
- `:modules:rhino` — Mozilla Rhino JS engine fork (package `com.script`). Evaluates user-defined book source rules.
- `:modules:book` — EPUB/UMD/MOBI parsing fork (package `me.ag2s.epublib`).
- `:modules:web` — Vue 3 frontend for the embedded HTTP server (see below).

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

## Web frontend (modules/web)

- Vue 3 + Vite + TypeScript + Element Plus + Pinia.
- Node >= 20, pnpm >= 9 required.
- Commands (run from `modules/web/`):
  - `pnpm install` — install deps
  - `pnpm dev` — dev server on port 8080 (requires app backend running)
  - `pnpm build` — type-check → build → run `scripts/sync.js`
  - `pnpm lint:fix` — eslint
  - `pnpm format` — prettier
- **Build artifact**: `pnpm build` outputs to `modules/web/dist/`, then `sync.js` copies to `app/src/main/assets/web/vue/`.
  - **Local builds**: `sync.js` exits early if `GITHUB_ENV` is not set — you must copy `dist/` manually, or set `GITHUB_ENV`.
  - **CI**: `web.yml` handles the copy and auto-commits with message `Bump web v...`.
- Vite config: base path is `./` (relative) for production, `/` for dev.

## CI/CD (GitHub Actions)

| Workflow | Trigger | What it does |
|---|---|---|
| `test.yml` | Push to main (any path except web) | Builds **all 3 release flavors**. If `updateLog.md` changed → official GitHub/Gitee release; else → beta pre-release. |
| `web.yml` | Changes in `modules/web/` | Builds Vue frontend, auto-commits to `app/src/main/assets/web/vue/`. Only pushes on upstream repo. |
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

## Gotchas

- **jsoup version is pinned** — bumping breaks JsoupXpath and AnalyzeByJSoup. Verify JsoupXpath compatibility first.
- **18PlusList.txt** is emptied during CI — do not rely on it persisting.
- **updateLog.md** drives the release system. If you modify it, the next CI run will create an official release (not a pre-release).
- **sync.js for web builds** only works in CI — set `GITHUB_ENV` or manually copy `dist/` when building locally.
- **Cronet native libs** are architecture-specific and large (tens of MB). `downloadCronet` fetches them from GCS.
- **Release APK signing** uses a hardcoded debug key in CI — fine for test builds, not for Play Store.
- The `modules/rhino` and `modules/book` are **forks** — sync with upstream carefully.
- Two icon variants exist: `Launcher1` and `Launcher2` activities (`enabled=false` by default) — they share the same MAIN/LAUNCHER intent-filter as alternate icons.
