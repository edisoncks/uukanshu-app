# Architecture

Design of the app: what owns what, and which rules a change must not break.
Deliberately not a second copy of the code — behaviour you can read off a file
stays in that file. Site quirks live in [SCRAPING.md](SCRAPING.md), the release
shape in [RELEASING.md](RELEASING.md), build/test/signing in
[DEVELOPMENT.md](DEVELOPMENT.md).

UI text is Traditional Chinese by default, with a global Simplified toggle.

## App shell

- One Gradle module, **manual DI** (no Hilt/Koin). `App` owns the app-scoped
  singletons; `di/AppContainer` exposes the interfaces a screen is allowed to
  see; `UukanshuApp` (`ui/AppNavHost.kt`) provides the real container through
  `LocalContainer`; ViewModels are built only by `ui/vmFactory { … }`.
  `MainActivity` does nothing but `setContent` the shell.
- The seam earns its keep: the faked interfaces (`RepoApi`, `PrefsApi`,
  `ReleaseFetcher`, `ApkDownloader`) keep ViewModel tests on plain JUnit, while
  the pure-Kotlin collaborators (`T2S`, `BookDownloadManager`) are used for
  real. Don't re-abstract them, and never construct a screen-level singleton per
  composition (`Prefs(app)`, `T2S(app)`, `UpdateApi()`).
- Navigation (`ui/Nav.kt`): routes are constants, so a typo fails to compile.
  Tabs are single-top with state restore; a book opens single-top; chapters
  enforce one reader per book, and a repeated tap on the same chapter is a
  no-op. Detail and Reader are full-screen and hide the bottom bar.
- The shell owns the shared `UpdateViewModel` and overlays the update dialog on
  any tab; the library tab shows a dot while any book has unseen chapters.
- `Site.kt`: base URL plus the fixed category catalogue (ids 1–10).
- Theme is one pure decision (`isDark`) driven by `Prefs.theme`, wrapping
  dynamic color where the platform offers it with a plain-scheme fallback.

## Permissions

[`AndroidManifest.xml`](../app/src/main/AndroidManifest.xml) is the source of
truth — every entry carries its own why-comment there. This is the inventory of
what is declared and which behaviour owns it: every permission must appear here,
and nothing may be declared without an owner (the app is text-only; there is no
analytics/ad SDK surface).

| Permission | Why | Owner |
|---|---|---|
| `INTERNET` | Fetch uukanshu.cc HTML and check GitHub Releases. Android 12+ has no separate network-state permission. | [Data layers](#data-layers), [Offline cache model](#offline-cache-model) |
| `POST_NOTIFICATIONS` | One summary notification per 追更 run (channel `book_updates`). Denied permission = silent badges, never a crash. | [追更](#追更-library-update-check) |
| `REQUEST_INSTALL_PACKAGES` | Hand the downloaded APK to the system package installer. The grant itself is a system Settings toggle ("install unknown apps"), not a runtime dialog. | [In-app update](#in-app-update) |

Build targets (`minSdk`/`targetSdk`/`compileSdk`) live in
[DEVELOPMENT.md](DEVELOPMENT.md#requirements). Fetch/parse consequences of the
text-only, no-connectivity-observer design are in [SCRAPING.md](SCRAPING.md).

## Screens

Each screen is a `*Screen.kt` composable plus a `*ViewModel` exposing a `Ui`
`StateFlow` — two deliberate exceptions: Settings is a plain composable writing
`Prefs` directly (its update card drives the shared updater VM; a dedicated
ViewModel would have nothing else to own), and the updater is a dialog rather
than a screen.

Rules every screen follows:

- `Ui` state is a sealed interface whose alternatives are real states, so
  impossible combinations (content *and* spinner, loaded *and* error) cannot be
  represented and every `when` is exhaustive.
- Every user-visible failure is rendered through `core/Errors.kt` and
  `core/Display.kt`, so no screen invents its own wording, leaks a URL, or
  mixes scripts.
- **Rapid-tap rule:** taps are guarded synchronously on the Main thread, and an
  async result that belongs to an earlier target (another tab, category or
  chapter) is dropped rather than painted.

| Screen | Design |
|---|---|
| Home | Two feeds (recent / category) over Paging 3, one HTML page per load, no hand-rolled prefetcher. Ids, scroll and cached pagers are per list and bounded: lists never leak items or positions into each other, coming back from a book replays already-loaded pages instead of refetching page 1, an explicit tab/category switch resets that list to the top, and a long session cannot accumulate pagers. |
| Search | One cancellable query pipeline per keystroke (a superseded search cancels structurally, and re-submitting the same text refires instead of being conflated away), deduped by book id, following the language toggle live. While a new query loads, the previous results stay visible under a progress bar. |
| Detail | The chapter list is stale-while-revalidate: the cached list paints immediately and one refresh either confirms it or leaves it visibly stale with the reason; offline and refreshing are derived from that phase rather than tracked, so they cannot disagree with it. Download progress, cached-chapter badges and the bookmark are separate live overlays, so they can never invalidate the loaded book. Shrink handling and its escape hatch are invariants — see below. |
| Reader | One chapter load owns one TOC generation and may restart at most twice, so a TOC shift mid-load cannot open the wrong chapter, and a failure cannot loop. Text is cache-first, network second, always keyed by the stable chapter id. Prev/next are last-tap-wins. A chapter the site deleted is reported as deleted instead of being retried forever. |
| Library | The shelf is a single reactive read (never a one-shot query racing its own flow), stale-while-revalidate, with per-book download rows taken from the app-scoped manager. Deleting a book also drops the manager's retained state for that book. |
| Settings | Four cards — appearance, language, 追更, update — all writing `Prefs`, so every screen follows live. |
| Update | A dialog over the updater state machine; the decisions behind it are pure functions (see [In-app update](#in-app-update)). |

## Invariants (do not break)

None of these is obvious from the code, and each is cheap to violate and
expensive to notice.

| Invariant | Why | Where |
|---|---|---|
| Content and progress are keyed by the stable chapter id, never by position. | The site inserts chapters, which shifts positions; keying by position misfiles cached text and continue-reading. | `chapters`/`progress` schema, `resolveBookmark`, `resolveEffectivePosition` |
| An empty **or shrunken** fresh chapter list is a failed refresh, not an empty book. | A block page or a truncated parse must never delete downloaded chapters. | `TocRevalidator.shouldAcceptFresh`, `TocShrunkException`, `BookRepo.detail` |
| The shrink guard cannot clear itself; the only way out is user-confirmed. | Retrying cannot heal a genuine site-side deletion, so Detail offers 重新同步章節列表 — which still refuses an empty list and prunes through the normal diff. That offer must also survive a confirmed run that failed, or the only way out disappears with it. | `TocState.StaleReason.Shrunk`, `Load.Ready.canResync`, `detailAcceptingShrink` |
| TOC replacement and single-row content writes serialize on one writer. | A refresh running against a download must not lose a committed chapter or resurrect pruned rows. | repo `dbWrite` mutex + `AppDb.replaceToc` |
| DB writes go through `AppDb`'s transactional methods, with explicit transactions. | Room 2.6.1 generated no override for a non-abstract `@Transaction` method of a `@Database` class: the annotation alone was a silent no-op, and a cancel mid-body could commit half a merge. | `AppDb.replaceToc/deleteBookFull/clearAllFull`, `DbTransactionTest` |
| The single-flight gate is held per HTTP attempt, never across backoff or crawl delay. | Otherwise one dead bulk fetch head-of-line blocks a user tap for minutes. | `SiteApi` + `UukanshuGate` |
| Batch fetches differ from interactive ones only by timeout profile, and must opt in. | A stuck background fetch has to fail in seconds; the default has to stay safe for taps. | `BulkFetch` marker + the two profiles |
| One download job per book, owned by the app rather than by a screen. | Downloads must survive leaving Detail, and a second tap must queue rather than race. | `BookDownloadManager` ownership boundary |
| Deleting a book evicts its retained download state, and a stale job can never publish over its replacement, after cancellation, or after a delete. | A re-opened detail must not replay progress for a book that is gone, and a cancel must not lose to an in-flight callback. | manager `forget`/`forgetAll`, Library delete |
| A book refresh never cancels a running download. | Losing a 500-chapter run to a stale refresh is unrecoverable work for the user. | Detail refresh path |
| Text is stored raw (Traditional); conversion happens at render time only. | Cached content must not depend on the current display preference. | `T2S`, `Display.text` |
| Offline is detected by a failing fetch plus a cache fallback — never by a connectivity observer. | The app declares no network-state permission and must behave the same on a flaky network. | fetch/read paths |
| Only one writer owns a piece of state. | Two writers (a one-shot query racing its own flow, two pagers for one list) produce stale-wins bugs that are hard to reproduce. | see [Data layers](#data-layers) |
| The updater is stable-only, and the release shape is fixed. | A beta must never reach a stable user, and the exact asset-name + digest match is what makes an install safe. | `UpdateApi.parse`, [RELEASING.md](RELEASING.md#updater-contract-do-not-break) |
| The database domain is excluded from backup. | The chapter cache is re-downloadable content that would blow the ~25 MB quota; the shelf deliberately does not restore onto a new device. | `data_extraction_rules.xml` |

## 追更 (library update check)

- Badge = `newCount`, baseline = `seenTotal`, stamp = `lastCheckedAt`. A first
  check seeds the baseline so a freshly added book shows no false badge; a
  successful check advances the baseline and clears the badge; a
  stale, offline or failed refresh preserves both. Every TOC merge carries all
  three columns, so an ordinary refresh can never wipe a badge or reorder the
  shelf.
- A run is bounded and oldest-check-first, so a large shelf converges over
  several runs instead of overrunning a Worker's budget. A per-book failure is
  a skip, not a failed run. An empty or shrunken refresh still stamps the
  check time, so such books sort last instead of wedging the front of that
  queue forever.
- One switch gates every *automatic* path — the daily Worker and the
  library-open fallback — and is read fail-closed: a read error must not put
  the app on the network against the user's choice. Manual 檢查更新 always
  runs, and the schedule itself stays registered, with the switch enforced when
  a run happens.
- Notifications are one summary per run, never one per book, and a denied
  permission degrades to silent badges instead of failing the run.
- A finished download clears the badge only when it covered the live chapter
  list: the run re-reads the list at the end and fetches whatever grew, so a
  success can never clear badges for rows that were never downloaded.

## Data layers

```text
UI (ViewModels)
 └─ BookRepo            # orchestration: cache-first, TOC merge, downloadAll, progress
     ├─ SiteApi         # raw HTTP (GET pages, POST /search), owns UukanshuGate per attempt
     ├─ UukanshuGate    # plain single-flight Mutex (bulk vs interactive differ only in timeouts)
     ├─ BookDownloadManager # app-scoped full-book jobs, one at a time (survive detail)
     ├─ Parser          # pure HTML → data classes (BookItem, BookMeta, ChapterRef, ChapterContent)
     ├─ Room (AppDb)    # cached TOC/chapters/progress; schemas in app/schemas/
     └─ Prefs           # DataStore (see below)
```

- **HTTP** (`data/net/`): browser-like headers, gzip, HTML only — images,
  iframes and scripts are never requested. Retries are bounded with a
  cancellable backoff on transport errors and 408/429/5xx; other 4xx and a
  Cloudflare interstitial fail fast, because neither heals inside the backoff
  window. Timeouts are profiled per lane (see [SCRAPING.md](SCRAPING.md)) and a
  deadline surfaces as an ordinary IO failure so a caller shows Error/retry
  instead of a spinner that never ends. The single-flight gate covers
  uukanshu.cc traffic only: update checks never wait behind a novel fetch.
- **Parsing** (`data/parse/`): pure functions over HTML behind one facade, so
  they are testable without a network. The site quirks they encode are
  contractual — documented in [SCRAPING.md](SCRAPING.md), do not "simplify"
  them.
- **Repo** (`data/repo/`): the orchestration layer and the only writer of
  cached content — cache-first reads, network fetch with raw save, TOC merge,
  progress, library stats, delete/clear. The pure rules (TOC diff, shelf order,
  bookmark resolution, what is missing) live in collaborators so they can be
  tested without network or DB, and the UI is handed domain types rather than
  Room entities. Bulk work is sequential with a politeness delay, treats an
  empty or shrunken chapter list as failure, and tolerates the book being
  deleted mid-run. Network I/O goes through `SiteApi`; parsing and the DB merge
  happen outside the single-flight gate.
- **DB** (`data/db/`): Room, with schema exports committed under
  `app/schemas/`. Migrations are additive and rekey `chapters` onto the stable
  chapter id; `progress` keeps the display position only as a pre-migration
  fallback. Metadata-only queries exist so badge/count paths never load
  chapter bodies. All merging and wiping goes through the transactional write
  paths named in the invariants above.
- **Prefs** (`data/prefs/`): one DataStore for user preferences and check
  stamps, plus a separate no-backup store for the updater's download record,
  because a DownloadManager id is device-local and must not be restored onto
  another device. Everything UI-visible is a `Flow`, so a change in Settings
  re-renders every screen live. A failed read or write is logged and must never
  escape a check or leave a tap guard stuck.
- **Text transform** (`data/convert/`, `core/Display.kt`): one conversion rule,
  applied at render time by every screen, error text included, so no screen can
  mix scripts while the rest follow the toggle.
- **Errors** (`core/Errors.kt`): one formatting policy for every
  dialog/snackbar/error state — typed mapping to Traditional Chinese messages,
  URLs stripped, class name only as a last resort. Cancellation is never
  swallowed by a formatting helper; callers rethrow it before formatting.

## Offline cache model

- Chapter text is cached raw (Traditional) under the stable chapter id, so a
  TOC shift can never misfile content or a continue-reading target.
- Cached-chapter badges and counts derive from the set of cached chapter ids,
  never from positions, for the same reason.
- Bookmarks are resolved by the stable id, falling back to the stored position
  only for rows written before that id existed. A vanished non-zero id means a
  deleted chapter: no target, and never the neighbour that now sits at the old
  position.
- Reader and Detail read cache first, then network, saving raw — all keyed by
  the stable id, so a background TOC revalidate needs no caller-side guard.
  Progress saving, prefetch and full download all fail silently rather than
  interrupt reading, and the bulk paths are sequential with a politeness delay.
- The database domain is excluded from cloud backup and device transfer: it is
  re-downloadable site content that would dominate the backup quota, and the
  shelf deliberately does not follow a user to a new device.

## In-app update

Files: `data/update/` (`UpdateApi` behind `ReleaseFetcher`, `UpdateDownloader`
behind `ApkDownloader`, `VersionCompare`, `JsonMini`) and `ui/update/`
(`UpdateViewModel`, `UpdateDecisions`, `UpdateDialog`).

- The gateways exist so the ViewModel is fakeable and every non-trivial
  decision is a pure function: the 24h auto-check throttle and offer/skip
  policy, the check/install test-and-sets behind the Main-thread tap guard, and
  the single terminal verdict that turns a finished download into a verified
  file (or a failure). A real-IO review path is exercised too, so the fakes
  cannot hide an IO bug.
- Source is the GitHub Releases API. The channel is **stable-only**: a payload
  whose tag carries a prerelease suffix (or whose `prerelease` flag is set) is
  refused, so a beta cannot be handed to the installer even if it was published
  without GitHub's prerelease flag — see
  [RELEASING.md](RELEASING.md#publishing-a-prerelease-beta). Auto-checks are
  throttled to once a day and persisted; a manual check always hits the
  network.
- Versions compare numerically on `versionName` versus the tag, with prerelease
  suffixes ordered numerically. Assets fail closed: only exactly
  `uukanshu-{version}.apk` for that tag is ever offered, so a stale or second
  APK yields no update rather than the wrong binary.
- Download runs through the system `DownloadManager` and is observed, not
  polled by hand. A durable record pins the enqueued release, so a process
  death between enqueue and completion reattaches to the same request instead
  of starting a second one; matching in-flight files are never deleted or
  re-enqueued. The record survives a verified success so the DownloadManager
  receipt can be restored, and is cleared by cancel, terminal failure, or
  recovery deciding the release is no longer relevant. Recovery restores state
  without forcing a dismissed prompt back on screen.
- Integrity is a single small state table over one file: missing, partial or
  ready, computed by a pure function with one IO wrapper, so every caller —
  enqueue, already-have, install gate — agrees. When the release publishes a
  server-side digest, the file must hash to it before it counts as ready or
  installable, and a mismatch deletes it; a release without a digest keeps a
  size-only path, where a sizeless build installs only with a fresh
  DownloadManager success receipt. Verification and its receipt are pinned to
  the enqueued release, not to whatever the dialog currently shows, so a
  mid-flight re-check or a skip can never mint a receipt for another payload.
- Install re-checks the file, then hands it to the system installer through a
  FileProvider and an injectable launcher, so a missing handler or a blocked
  install surfaces as a dialog error instead of a crash. A complete file
  already on disk skips straight to install. The system "install unknown apps"
  grant is required once and is prompted for.
- A skipped version stops auto-prompts but stays re-openable from Settings; a
  failed check always offers the browser download as a fallback; the release
  body is shown verbatim as the changelog.
- **Release-shape dependency:** tag `vX.Y.Z` == `versionName X.Y.Z`, exactly one
  asset named `uukanshu-X.Y.Z.apk`, whose server-side sha256 `digest` is
  verified in-app before install. Full contract in
  [RELEASING.md](RELEASING.md#updater-contract-do-not-break).
