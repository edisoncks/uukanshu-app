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

## Theme

Four modes (`Prefs.theme`): `system`, `light`, `paper`, `dark`. `system` follows the device;
everything but `paper` resolves to the platform's dynamic color scheme, with a plain-scheme fallback.
A mode is picked with one row, `ThemeOptionsCard` over `THEME_OPTIONS`, in the settings screen and in
the reader settings sheet alike — one composable, so the two cannot drift. The row spends one line
on four segments, so a label stays two glyphs: `system` is shown as `自動` and nothing in the picker
says what it follows — the meaning lives here, not in the UI. There is no in-reader toggle; the
sheet is the way a theme is changed while reading.
`AppTheme.isDark()` is the pure, JVM-testable half of that decision, and it also drives system-bar
icon appearance (the window theme is static light, so the bars need the in-app answer).

`paper` is the exception in every sense:

- **Fixed.** `AppTheme.paperScheme()` builds it from `PaperPalette` and takes no `Context`, so dynamic
  color is structurally unreachable. It is the only theme that ignores Material You and the only one
  that looks the same on every device. All 36 roles are set explicitly: a role left unset would fall
  back to the baseline scheme (lavender) inside sepia chrome.
- **Semantic roles stay semantic.** `error` keeps the Material baseline red; a failure must not read
  as decoration. `surfaceTint` is warm, so elevation shading cannot drag a sepia surface toward a
  hue the rest of the scheme does not use.
- **Validated, not eyeballed.** `AppThemeTest` pins every role to the palette, pins the warm/light
  properties, and enforces a 4.5 contrast floor on the foreground/container pairs the app renders.
  Editing a hex here means passing that test.
- **Global, never reader-local.** No screen styles paper itself; the reader had three hardcoded
  colors and `if (isPaper)` branches until paper became a scheme, and now reads
  `surface`/`onSurface`/`onBackground` like any other screen.

## Permissions

[`AndroidManifest.xml`](../app/src/main/AndroidManifest.xml) is the source of
truth for what the app declares itself — every entry there carries its own
why-comment. This is the inventory of what the shipped APK requests and which
behaviour owns it: every permission must appear here, and nothing may be
declared without an owner (the app is text-only; there is no analytics/ad SDK
surface).

| Permission | Why | Owner |
|---|---|---|
| `INTERNET` | Fetch uukanshu.cc HTML and check GitHub Releases. | [Data layers](#data-layers), [Offline cache model](#offline-cache-model) |
| `POST_NOTIFICATIONS` | One summary notification per 追更 run (channel `book_updates`). Denied permission = silent badges, never a crash. | [追更](#追更-library-update-check) |
| `REQUEST_INSTALL_PACKAGES` | Hand the downloaded APK to the system package installer. The grant itself is a system Settings toggle ("install unknown apps"), not a runtime dialog. | [In-app update](#in-app-update) |

WorkManager merges four more into the shipped manifest from its own —
`WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED` and
`FOREGROUND_SERVICE`. They belong to [追更](#追更-library-update-check), not to
any app code: the periodic 24h job has to wake the device, check its
`NetworkType.CONNECTED` constraint when the job starts, and survive a reboot (the
OS re-schedules it after `BOOT_COMPLETED`). `FOREGROUND_SERVICE` arrives with
WorkManager 2.x for expedited and foreground workers; no worker here runs as
either, so it stays granted and unused. No app code reads connectivity — that
is why offline stays a failing fetch plus a cache fallback even though the APK
carries `ACCESS_NETWORK_STATE`. A new dependency that merges a permission puts it
on this inventory too.

The packaged manifest also carries `cc.uukanshu.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`,
generated by AndroidX for `registerReceiver(..., RECEIVER_NOT_EXPORTED)`. No
user grants it and no app code names it; it is listed here only so the promise
above stays true for the artifact people install.

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
- Failure wording is owned, not invented per call site: transport/IO text comes
  from `core/Errors.kt`, the reader's deleted and out-of-range cases from
  `ui/reader/ReaderErrors.kt` and its bounds check, and every screen renders
  that text through `core/Display.kt` — so nothing leaks a URL or mixes
  scripts.
- **Rapid-tap rule:** taps are guarded synchronously on the Main thread, and an
  async result that belongs to an earlier target (another tab, category or
  chapter) is dropped rather than painted.

| Screen | Design |
|---|---|
| Home | Two feeds (recent / category) over Paging 3, one HTML page per load, no hand-rolled prefetcher. Ids, scroll and cached pagers are per list and bounded: lists never leak items or positions into each other, coming back from a book replays already-loaded pages instead of refetching page 1, an explicit tab/category switch resets that list to the top, and a long session cannot accumulate pagers. |
| Search | One cancellable query pipeline per keystroke (a superseded search cancels structurally, and re-submitting the same text refires instead of being conflated away), deduped by book id, following the language toggle live. While a new query loads, the previous results stay visible under a progress bar. |
| Detail | The chapter list is stale-while-revalidate: the cached list paints immediately and one refresh either confirms it or leaves it visibly stale with the reason; offline and refreshing are derived from that phase rather than tracked, so they cannot disagree with it. Download progress, cached-chapter badges and the bookmark are separate live overlays, so they can never invalidate the loaded book. Shrink handling and its escape hatch are invariants — see below. |
| Reader | Settings sheet: the appearance section is `ThemeOptionsCard`, the same composable the Settings tab renders (see § Theme), so a theme reads the same in both places. One chapter load owns one TOC generation and gets at most one bounded restart (two fetch attempts in total, never a loop), so a TOC shift mid-load cannot open the wrong chapter, and a failure cannot retry forever. Text is cache-first, network second, always keyed by the stable chapter id. Prev/next are last-tap-wins. A chapter the site deleted is reported as deleted instead of being retried forever. |
| Library | The shelf is a single reactive read (never a one-shot query racing its own flow), stale-while-revalidate, with per-book download rows taken from the app-scoped manager. Deleting a book also drops the manager's retained state for that book. |
| Settings | Four cards — appearance, language, 追更, update — all writing `Prefs`, so every screen follows live. The appearance card shows the four themes as one segmented row (`ThemeOptionsCard` over `THEME_OPTIONS`): a theme is one tap out of four, not a list of rows to scroll. The card, its row and its padding are one composable, not a copy — the reader sheet calls it too (see the Reader row) — so the two screens cannot drift apart. The label is a plain `Text` on purpose: capping its width needs the segment's real width, and the natural way to ask (`BoxWithConstraints`) is a `SubcomposeLayout`, which throws inside `SegmentedButton`'s intrinsic measurement — that crash shipped in 1.2.26, and `ThemeOptionsCardTest` now composes the card so it cannot ship again, and bounds-locks each label inside its segment at 320/360/393dp and font scale 1.0/1.3. Measured budget from material3 1.3.0's own defaults: a 360dp phone gives each of the four segments 74.5dp; `SegmentedButtonDefaults.ContentPadding` fixes 12dp per side and that version exposes no `contentPadding` parameter to tune it; the label is measured against the inner width before the check icon's box is added (`IconSize` 18dp, `IconSpacing` 8dp), so overrun eats the padding first. The only case the bounds test excludes is a 2.0x font scale at 360dp, where the selected label is shaved by about 1dp and the labels ellipsize — material's own behaviour, not something this row adds. The 追更 switch names the bookshelf, so it cannot be read as the app-update check in the card below it, and its state shares a line with the last-check stamp instead of stacking. |
| Update | A dialog over the updater state machine; the decisions behind it are pure functions (see [In-app update](#in-app-update)). |

## Invariants (do not break)

None of these is obvious from the code, and each is cheap to violate and
expensive to notice.

| Invariant | Why | Where |
|---|---|---|
| Content and progress are keyed by the stable chapter id, never by position. | The site inserts chapters, which shifts positions; keying by position misfiles cached text and continue-reading. | `chapters`/`progress` schema, `resolveBookmark`, `resolveEffectivePosition` |
| An empty **or shrunken** fresh chapter list is a failed refresh, not an empty book. | A block page or a truncated parse must never delete downloaded chapters. | `TocRevalidator.shouldAcceptFresh`, `TocShrunkException`, `BookRepo.detail` |
| The shrink guard cannot clear itself; the only way out is user-confirmed. | Retrying cannot heal a genuine site-side deletion, so Detail offers 重新同步章節列表 — which still refuses an empty list and prunes through the normal diff. That offer must also survive a confirmed run that failed, or the only way out disappears with it. | `TocState.StaleReason.Shrunk`, `Load.Ready.canResync`, `detailAcceptingShrink` |
| TOC replacement and single-row content writes serialize on one writer, and the shrink-guard read sits inside the same lock as the write it authorizes. | A refresh running against a download must not lose a committed chapter or resurrect pruned rows; two concurrent same-book refreshes must not regress the TOC. | repo `dbWrite` mutex + `AppDb.replaceToc`, `BookRepoGuardRaceTest` |
| A shipped migration is never edited, and the schema exports stay committed. | An upgrade path cannot be fixed by a release note, and a missing export only surfaces at review time for the next migration. | `app/schemas/`, `DbSchemaTest` (data-level run: `MigrationTest`) |
| DB writes go through `AppDb`'s transactional methods, with explicit transactions. | Room 2.6.1 generated no override for a non-abstract `@Transaction` method of a `@Database` class: the annotation alone was a silent no-op, and a cancel mid-body could commit half a merge. | `AppDb.replaceToc/deleteBookFull/clearAllFull`, `DbTransactionTest` |
| The single-flight gate is held per HTTP attempt, never across backoff or crawl delay. | Otherwise one dead bulk fetch head-of-line blocks a user tap for minutes. | `SiteApi` + `UukanshuGate` |
| Batch fetches differ from interactive ones only by timeout profile, and must opt in. | A stuck background fetch has to fail in seconds; the default has to stay safe for taps. | `BulkFetch` marker + the two profiles |
| One download job per book, owned by the app rather than by a screen. | Downloads must survive leaving Detail, and a second tap must queue rather than race. | `BookDownloadManager` ownership boundary |
| Deleting a book evicts its retained download state, and a stale job can never publish over its replacement, after cancellation, or after a delete. | A re-opened detail must not replay progress for a book that is gone, and a cancel must not lose to an in-flight callback. | manager `forget`/`forgetAll`, Library delete |
| A book refresh never cancels a running download. | Losing a 500-chapter run to a stale refresh is unrecoverable work for the user. | Detail refresh path |
| Text is stored raw (Traditional); conversion happens at render time only. | Cached content must not depend on the current display preference. | `T2S`, `Display.text` |
| Offline is detected by a failing fetch plus a cache fallback — never by a connectivity observer. | App code never observes connectivity (the packaged `ACCESS_NETWORK_STATE` belongs to WorkManager) and must behave the same on a flaky network. | fetch/read paths |
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
  shelf deliberately does not follow a user to a new device. Settings (the main
  DataStore) are *not* excluded — they follow the platform defaults, which is why
  the user-facing privacy wording separates progress/downloads from settings.
- A full-book download is an app-scoped coroutine job, not persistent work:
  process death stops it and nothing auto-resumes. Tapping 下載整本 again is safe
  and cheap — it skips every chapter id already cached. This is deliberately
  different from the updater's `DownloadManager` request, which *is* durable and
  reattached after process death (see [In-app update](#in-app-update)).

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
- Download runs through the system `DownloadManager`, observed as a Flow
  that polls it until a terminal status (never unbounded). A durable record
  pins the enqueued release, so a process
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
  **visible** failed check — the manual one; an automatic failure stays silent by
  design — offers the browser download as a fallback, including when no release
  could be parsed at all; the release body is shown in full as the changelog —
  the dialog scrolls, nothing is truncated.
- **Release-shape dependency:** tag `vX.Y.Z` == `versionName X.Y.Z`, exactly one
  asset named `uukanshu-X.Y.Z.apk`, whose server-side sha256 `digest` is
  verified in-app before install. Full contract in
  [RELEASING.md](RELEASING.md#updater-contract-do-not-break).
