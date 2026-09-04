# uukanshu

Clean, minimalist Android reader for novels from [uukanshu.cc](https://uukanshu.cc) — just the story: no images, no ads.

## Install

Grab `uukanshu-{version}.apk` from the
[Releases page](https://github.com/edisoncks/uukanshu-app/releases/latest),
then open it on your phone to install (Android 12+). If prompted, allow
"install unknown apps" for your browser/file manager — the APK is signed
but not Play-distributed.

To update, download the newest APK and install over the old one (same
signature, data and cache preserved).

## Features

- 📚 Browse by category (10 cats, paged) and recently updated
- 🔍 Search by title (`POST /search`, hot-span stripping)
- 📖 Book detail with full chapter list (reading order, cached badges)
- 📄 Chapter reader with prev/next, start/end snackbars
- 🀄 Global Traditional → Simplified toggle (Home top bar + reader; opencc4j at render time, raw cached)
- ⏬ Auto-cache next 5 chapters; manual full-novel download with progress + cancel
- 🗑️ Library with per-book size, delete per book / clear all; offline cached-first reading
- 🔤 Font-size +/-, persisted with DataStore; Material3 light/dark

## Build

Toolchain is pinned in `mise.toml` (java 17, gradle 8.10.2, kotlin 2.0.20) with the Android SDK project-local in `.android-sdk/`:

```sh
mise install          # runtimes (project-local, no global installs)
mise run setup-android  # platforms;android-34, build-tools;34.0.0 (first time)
mise run build        # ./gradlew assembleRelease
# → app/build/outputs/apk/release/uukanshu-1.0.0.apk
mise run test         # unit tests (Parser fixtures, T2S)
```

`versionName` in `app/build.gradle.kts` is the single source of truth; release APKs are renamed to `uukanshu-{version}.apk`.

## Signing

Release builds are signed (unsigned APKs are rejected at install time with
"package appears to be invalid"):

- Local dev key: `mise run setup-signing` generates a gitignored
  `release.keystore` (password/alias `uukanshu`), used automatically.
- Official releases: set `UUKANSHU_KEYSTORE_FILE`,
  `UUKANSHU_KEYSTORE_PASSWORD`, `UUKANSHU_KEY_ALIAS`, `UUKANSHU_KEY_PASSWORD`.
- Keep your keystore backed up: updates signed with a different key
  require uninstalling the app first.

## Scraping notes

Ported from [`uukanshu-cli`](../uukanshu-cli): browser UA, 3× retry, Cloudflare `<title>` sniff, TOC LAST-occurrence dedup + numeric bookId filter, `mulu-box` + LAST nav-row cut, urljoin-then-validate nav (non-chapter hrefs = end-of-book), canonical book URLs, `POST /search`. Text only — `<img>`/iframes/scripts never fetched or rendered. Bulk chapter fetching (full download, next-5 prefetch) pauses 3s + random 0–1s between requests to avoid rate limiting.

Requires `minSdk 31` (Android 12+), `targetSdk 34`.
