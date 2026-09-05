# Architecture

High-level map of the app for contributors. UI text is Traditional Chinese by
default with a global Simplified toggle (see below).

## App shell

- `MainActivity.kt` (`UukanshuApp` composable):
  - Owns the global `MaterialTheme` (dynamic color on `minSdk 31`, plain
    schemes as fallback) driven by `Prefs.theme` (`system` / `light` / `dark`).
  - Bottom nav with 4 tabs: `home` (首頁), `search` (搜索), `library` (書架),
    `settings` (設定). Detail/Reader are full-screen destinations that hide
    the bottom bar.
  - Hosts one shared `UpdateViewModel` (auto-check once per day) and overlays
    `UpdateDialog` on any tab.
  - Navigation uses `ui/Nav.kt` (`Routes` + `navigateToTab` /
    `navigateToBook` / `navigateToChapter`): route strings are constants
    so typos fail at compile time, not runtime. Tabs are single-top +
    state restore; books are single-top; chapters enforce one reader per
    book (`popUpTo(detail)`) and skip identical double-taps.
- `App.kt`: `Application` subclass holding the shared singletons:
  `UukanshuGate` + `BookRepo` + app-scoped `BookDownloadManager` +
  `Prefs` + `T2S` (accessed via `ctx.app()`, e.g. `app.repo`).
  Screens must use these singletons — never `Prefs(app)` / `T2S(app)`
  per-composition — and must construct ViewModels via the single
  `ui/vmFactory { ... }` helper (`ui/VmFactory.kt`), so wiring stays
  uniform and greppable instead of six hand-written factories.
- `Site.kt`: `BASE_URL = https://uukanshu.cc` + fixed `CATEGORIES` list
  (ids 1–10, `/class_{id}_{page}.html`).

## Screen-by-screen (MVVM)

Each screen has a `*Screen.kt` composable + `*ViewModel` exposing a `Ui`
`StateFlow`. Rapid taps are guarded synchronously on the Main thread
(set flags before `launch{}`); stale async results are dropped when
tab/category/position changed mid-fetch.

| Screen | Key behaviour |
|---|---|
| Home (`ui/home`) | Tabs 最近更新 (recent) / 分類 (category). Category chips (`AssistChip`), Paging 3 `LazyColumn` (one HTML page per load, prefetch automatic). `BookPagingSource` filters already-seen ids per list because the live recent feed overlaps pages; a new Pager per tab/category restarts the set. Refresh/append load-states drive the spinner, error + retry and empty states — the old hand-rolled `page`/`loadingMore`/`endOfList`/stale-drop/`mergeBooks` machinery is gone. |
| Search (`ui/search`) | `queries` flow with 400ms `debounce` + `flatMapLatest` into `repo.search()` (superseded searches cancel structurally — no manual job/activeQuery guard), dedup by book id, follows `Prefs.simplified` live. Sealed `Ui` (Idle/Loading/Success/Error): impossible states are unrepresentable and the `when` is exhaustive. Shows result count (共 … 條結果), loading / error + retry / 沒有結果 (no results) states. |
| Detail (`ui/detail`) | Stale-while-revalidate: paint cached TOC instantly, then refresh silently (`refreshing` shows a thin progress bar). Offline with cache keeps stale content + 離線模式 flag; offline without cache shows error + retry. An empty fresh TOC is treated as refresh failure (never wipes painted cache). Live flows for cached-chapter badges (`cachedPositionsFlow`) and continue-reading bookmark (`progressFlow`). Full-novel download is app-scoped (`BookDownloadManager` survives leaving detail, re-attaches on re-open; one book at a time — a second tapped book queues — sequential chapters, cancelable, progress `done/total`); refresh never cancels a running download. |
| Reader (`ui/reader`) | Loads by 1-based `position` into TOC. Sealed `Ui` (Loading/Content/Error with position/total/prefs on the interface for the sticky bottom bar) — content+spinner combos are unrepresentable. Cache-first chapter text (read/written by stable `pageId`, so shifts can't misfile), silent auto-bookmark (`saveProgress`, failures ignored), silent prefetch of next 5 (sequential with crawl delay, failures ignored). TOC itself is stale-while-revalidate; empty revalidations are ignored so `total` never zeroes mid-read. Prev/next serialize via `loadJob` (last-tapped wins). Bottom bar: `⋯` menu (language, font A±, theme cycle) + 上一章 / 下一章. Out-of-range and load errors show retry. |
| Library (`ui/library`) | Lists `repo.library()` (`CachedBook`: id/title/author/cached/total/bytes), total `N 本 · size`, per-book 刪除緩存 (delete) + 清空全部 (clear all, behind a confirm dialog). Deletes also evict retained `BookDownloadManager` state so re-opened details can't replay stale progress. Failed downloads offer 重試 on the shelf row itself. Progress publishes are dropped once the job is gone, so cancel can't lose to an in-flight callback. Live `BookDownloadManager` progress per book (`下載中 done/total` + 取消, incl. fresh downloads not yet qualified for the shelf). While downloading, the shelf hides the stale `cached/total` count and shows only the live `done/total` line so progress never appears twice. Empty state hints at downloading from the detail page. |
| Settings (`ui/settings`) | Three cards: 外觀 (appearance radio: 自動/淺色/深色), 語言 (language switch 繁體/簡體), 更新 (current version 目前版本, 檢查更新 button, checking spinner, up-to-date note, reopen-dismissed-update button, 跳過此版本 skip). Writes go to `Prefs` so all screens follow live. |
| Update (`ui/update`) | `UpdateDialog` renders every updater state (checking / up-to-date / error / new-version / downloading / file-ready / needs-unknown-sources) from `UpdateViewModel.Ui`. `startDownload` checks the on-disk APK first, so a complete file skips the unknown-sources gate straight to install. See "In-app update" below. |

## Data layers

```text
UI (ViewModels)
 └─ BookRepo            # orchestration: cache-first, prefetch, downloadAll, progress
     ├─ SiteApi         # raw HTTP (GET pages, POST /search) behind UukanshuGate
     ├─ UukanshuGate    # single-flight Mutex: at most 1 uukanshu.cc request
     ├─ BookDownloadManager # app-scoped full-book jobs, one at a time (survive detail)
     ├─ Parser          # pure HTML → data classes (BookItem, BookMeta, ChapterRef, ChapterContent)
     ├─ Room (AppDb)    # cached TOC/chapters/progress; schemas in app/schemas/
     └─ Prefs           # DataStore (see below)
```

- `data/net/SiteApi.kt`: browser-like headers (Android Chrome UA,
  `Accept-Language: zh-TW…`), gzip via OkHttp, 3× retry with backoff on
  408/429/5xx + transport errors (4xx like 404 fail fast), Cloudflare
  interstitial sniff on `<title>`. Only HTML is fetched — images/iframes/
  scripts are never requested (see [SCRAPING.md](SCRAPING.md)).
  Single-flight: every `uukanshu.cc` call goes through `UukanshuGate`
  (one HTTP request at a time; GitHub update traffic stays ungated).
  Gate scope is per request — bulk loops release it during `crawlDelay()`
  so interactive taps interleave.
- `data/parse/Parser.kt`: pure, unit-tested parsers. Non-obvious rules
  (LAST-occurrence TOC dedup, `mulu-box` + LAST nav-row cut,
  urljoin-then-validate nav, canonical book URLs, `<span class=hot>` strip)
  are documented in [SCRAPING.md](SCRAPING.md) — do not "simplify" them.
- `data/repo/BookRepo.kt`: cache-first reads (`cachedDetail`,
  `cachedChapterContent`), network fetch + raw save, `crawlDelay()` between
  bulk requests, progress save, library stats, delete/clear. Background
  TOC revalidation merges without wiping downloads (by `pageId`); the
  wholesale TOC replace and single-row content writes serialize on one
  Mutex so concurrent download/refresh never loses a committed chapter.
  Clear-all is one transaction wiping all three tables (bulk deletes),
  so cancellation can't strand a half-cleared library and orphan progress
  rows can't survive it.
  Network fetches (`recent`/`category`/`search`/`detail`/`chapter`) are
  serialized through `UukanshuGate`; only the HTTP fetch holds the permit,
  parse + Room merge run outside.
- `data/db/`: Room entities + DAOs (`BookDao`, `ChapterDao`, `ProgressDao`).
  `room.schemaDirectory("$projectDir/schemas")` exports schemas; keep them
  in version control.
- `core/Errors.kt`: single error-formatting policy (`Errors.message`).
  ViewModels/managers must not inline `"${e.javaClass...}"`; cancellation
  always propagates via `messageOrThrow` (unit-tested in `ErrorsTest`).
- `data/prefs/Prefs.kt` (DataStore `uukanshu`): `simplified: Boolean`
  (default false), `fontScale: Float` (default 1.0, clamped 0.8–1.6),
  `theme: String` (`system`/`light`/`dark`), `lastUpdateCheck: Long`,
  `skippedVersion: String`. All UI prefs are `Flow`s; screens collect them
  so a change in Settings re-renders everywhere live.
- `data/convert/T2S.kt` (opencc4j): Traditional → Simplified is applied
  **at render time only**; caches and DB always store raw Traditional.
  Reader re-renders `currentRaw` on toggle with no network or spinner.

## Offline cache model

- Chapters are cached raw (Traditional) keyed by stable `(bookId, pageId)`
  (DB v3; `position` is display order only, indexed). The old
  `(bookId, position)` key misfiled text after TOC shifts.
- Detail badges (✓ 已緩存) and counts (已緩存 N 章) derive from cached
  positions; Library shows per-book `cached/total` + bytes.
- Reader and Detail prefer cache, then network, then save raw — all content
  reads/writes by `pageId`, so a background TOC revalidate can never
  misfile text (no caller-side shift guard needed). Prefetch (next 5) and
  full download are sequential with `crawlDelay()` and never break reading
  on failure.

## In-app update

Files: `data/update/` (`UpdateApi`, `UpdateDownloader`, `VersionCompare`,
`JsonMini`) + `ui/update/` (`UpdateViewModel`, `UpdateDialog`).

- Source: GitHub Releases API. Auto-check throttled to once per 24h
  (`AUTO_CHECK_INTERVAL_MS`, persisted `lastUpdateCheck`); manual check from
  Settings always hits the network.
- Version compare is numeric dot-separated on `versionName` vs tag (leading
  `v` stripped). Non-matching APK assets fail closed (no update offered).
- Download via system `DownloadManager` with progress (0..1, indeterminate
  fallback), byte-exact size verification (`isComplete`), FileProvider +
  installer intent handoff. Same-version file already on disk skips straight
  to install. `REQUEST_INSTALL_PACKAGES` permission + system "unknown sources"
  grant required (first in-app update prompts once).
- Skipped versions (`skippedVersion`) suppress auto-prompts but stay
  re-openable from Settings; browser-download fallback always offered on
  error; release body is shown verbatim as the changelog (kept concise).
- **Release-shape dependency:** tag `vX.Y.Z` == `versionName X.Y.Z`, exactly
  one asset named `uukanshu-X.Y.Z.apk`. Full contract in
  [RELEASING.md](RELEASING.md#updater-contract-do-not-break).
