# Development

How to build, test, and sign the app locally.

## Requirements

- OS: Linux (these instructions), macOS/Windows should work with the same
  `mise` toolchain but paths differ.
- Toolchain is pinned in [`mise.toml`](../mise.toml):
  - Java 17, Gradle 8.10.2, Kotlin 2.0.20
  - Android SDK installed **project-local** in `.android-sdk/`
    (`ANDROID_SDK_ROOT` / `ANDROID_HOME` point there via `mise.toml [env]`).
- App targets: `minSdk 31` (Android 12+), `targetSdk 34`, `compileSdk 34`
  (see `app/build.gradle.kts`).

## First-time setup

```sh
mise install            # install pinned runtimes (project-local, no global installs)
mise run setup-android  # platforms;android-34, build-tools;34.0.0, platform-tools (first time only)
```

`setup-android` bootstraps `cmdline-tools` under `.android-sdk/` if missing,
accepts licenses, and installs the three packages above.

## Build

```sh
mise run build          # ./gradlew assembleRelease
# → app/build/outputs/apk/release/uukanshu-{version}.apk
```

Notes:

- The output APK is always renamed to `uukanshu-{version}.apk` from
  `versionName` (see `applicationVariants.all` in `app/build.gradle.kts`).
- Release builds set `isMinifyEnabled = true` + `isShrinkResources = true`
  with `proguard-rules.pro`. Debug builds skip minification.
- `versionName` in `app/build.gradle.kts` is the **single source of truth**
  for the version. The release tag (`vX.Y.Z`) and the APK asset name must
  match it — see [RELEASING.md](RELEASING.md).

## Test

```sh
mise run test           # ./gradlew testDebugUnitTest
```

Unit tests live in `app/src/test/java/cc/uukanshu/` and cover the pure-logic
layers (no device/emulator needed):

- `ParserTest` — HTML fixtures for home/category/search/detail/chapter parsing
- `T2STest` — Traditional → Simplified conversion
- `BookPagingSourceTest`, `SearchDedupTest` — paging dedup / list dedup by stable book id
- `ReaderTitleTest`, `ReaderSaveGuardTest`, `BookRepoTest` — reader title
  resolution, TOC-shift save guard, repo behaviour
- `UpdateCheckTest`, `ApkCompleteTest`, `SiteApiRetryTest` — updater version
  compare / APK completeness, network retry

See [CONTRIBUTING.md](CONTRIBUTING.md) for the expected workflow before
pushing.

## Signing

Release builds must be signed — Android rejects unsigned APKs at install
time ("package appears to be invalid").

Resolution order in `app/build.gradle.kts` (`signingConfigs.release`):

1. `UUKANSHU_KEYSTORE_FILE` (env var or Gradle property) + companion
   `UUKANSHU_KEYSTORE_PASSWORD` / `UUKANSHU_KEY_ALIAS` / `UUKANSHU_KEY_PASSWORD`.
   This is the path used for **official releases** so the signature matches
   previous releases.
2. Otherwise the local dev key `release.keystore` at the repo root
   (password/alias `uukanshu`).
3. If neither exists (fresh clone), the build falls back to the debug key so
   the APK is at least installable. **Never use this fallback for official
   releases.**

Generate the local dev key:

```sh
mise run setup-signing   # creates gitignored release.keystore (password/alias uukanshu)
```

Back up the official keystore: updates signed with a **different key**
require users to uninstall first (data loss). Verify before publishing:

```sh
export ANDROID_SDK_ROOT=$PWD/.android-sdk
.android-sdk/build-tools/34.0.0/apksigner verify \
  app/build/outputs/apk/release/uukanshu-X.Y.Z.apk
```

## Project layout

```text
app/src/main/java/cc/uukanshu/
  MainActivity.kt        # theme, bottom nav (home/search/library/settings), update dialog host
  App.kt                 # Application + shared BookRepo
  Site.kt                # BASE_URL + fixed category catalogue (ids 1..10)
  data/
    net/SiteApi.kt       # HTTP client (browser UA, retry, Cloudflare sniff)
    parse/Parser.kt      # pure HTML parsers (port of uukanshu-cli)
    repo/BookRepo.kt     # cache-first orchestration, prefetch, full download
    db/                  # Room: AppDb, Entities, BookDao, ChapterDao, ProgressDao
    prefs/Prefs.kt       # DataStore: theme, simplified, fontScale, update check state
    convert/T2S.kt       # Traditional → Simplified (opencc4j), raw cached
    update/              # UpdateApi, UpdateDownloader, VersionCompare, JsonMini
  ui/
    home/ detail/ search/ reader/ library/ settings/ update/
app/src/main/res/        # launcher icons, theme, FileProvider paths
app/schemas/             # Room schema exports
app/src/test/            # unit tests (see above)
```

Gradle config: `settings.gradle.kts` (repo root name `uukanshu`, `FAIL_ON_PROJECT_REPOS`
with google()/mavenCentral()), root `build.gradle.kts` (plugin aliases only),
`gradle.properties` (`android.useAndroidX`, Kotlin official code style).
