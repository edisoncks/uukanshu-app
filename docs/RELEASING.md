# Releasing

How to cut a `uukanshu` release. Official releases are built and
published by GitHub CI only (`.github/workflows/release.yml`, production
environment) — never from a laptop, so a dev-signed local build can never
slip into a release. Local `mise run build` APKs are dev-only test builds.

End users never need this — they install and update from the
[README](../README.md). This page is for maintainers.

## Prerequisites

- Push access to `main`.
- The `production` environment on GitHub holds the official signing key
  (`UUKANSHU_KEYSTORE_BASE64`) plus `UUKANSHU_KEYSTORE_PASSWORD` /
  `UUKANSHU_KEY_PASSWORD` secrets, with required reviewers so every
  release needs a human tap. Only maintainers who can approve that
  environment can ship. Secrets are entered in the GitHub UI (or via
  `gh secret set --env production` with piped stdin, never pasted into
  chat, docs, or shell history).
- No local keystore is needed for official releases. A repo-root
  `release.keystore` (from `mise run setup-signing`) is dev-only.

## Release steps (official, via CI)

### 1. Bump the version

The version lives in one place: `versionName` in `app/build.gradle.kts`.
Keep the tag in sync:

```sh
# edit versionName in app/build.gradle.kts, then:
git add app/build.gradle.kts
git commit -m "chore: bump version to X.Y.Z"
git push origin main
```

### 2. Push an annotated tag with the changelog

The tag message becomes the release notes verbatim (shown in the in-app
update dialog, which scrolls at ~220dp — keep it short, Traditional
Chinese, no jargon):

```sh
git tag -a vX.Y.Z -m "閱讀排版大升級

- 字體可選黑體 / 宋體
- 行距、段距隨手調"
git push origin vX.Y.Z
```

Use an annotated tag (`-a`): a lightweight tag has no message and CI
refuses it. Prerelease tags (`vX.Y.Z-beta`) are ignored by CI — betas
ship by hand (see below).

### 3. Approve + watch CI

The `release` workflow (production environment) tests, builds, verifies
(versionName == tag, exact `uukanshu-X.Y.Z.apk` asset name, `apksigner`
check, official-key fingerprint match), then publishes the GitHub release.
Approve the environment run when prompted and watch it go green. If it
fails, fix forward with a new tag — published assets are never
overwritten.

### 4. Smoke-test

1. Open the **Releases** page and confirm exactly one APK is attached, and
   that the API exposes its digest (the in-app updater verifies it):

   ```sh
   curl -s https://api.github.com/repos/edisoncks/uukanshu-app/releases/latest \
     | jq -r '.assets[0].digest'
   ```
2. On a device (Android 12+), download the APK from the release and install
   it once (clean-install path).
3. Smoke-test the in-app path: install the *previous* release, trigger the
   update check from **設定 → 更新 → 檢查更新**, confirm the prompt →
   download progress → installer handoff preserves data.
4. For the first beta→final pair (see below), sideload the beta and then
   update it to the final in-app: both carry the same `versionCode`, which
   the platform accepts (it refuses only a lower one). Every later beta
   relies on that, so confirm it once.

## Publishing a prerelease (beta)

Betas ship by hand from a trusted machine (CI ignores suffix tags).
Build with the production key via env vars (alias is `uukanshu`, same as CI):

```sh
export UUKANSHU_KEYSTORE_FILE=/path/to/official.keystore
export UUKANSHU_KEYSTORE_PASSWORD=…
export UUKANSHU_KEY_ALIAS=uukanshu
export UUKANSHU_KEY_PASSWORD=…
mise run test && mise run build
# → app/build/outputs/apk/release/uukanshu-X.Y.Z-beta.apk
```

Three differences from the
CI flow:

1. **The version and tag carry a suffix**: `versionName = "1.2.19-beta"`, tag
   `v1.2.19-beta`, asset `uukanshu-1.2.19-beta.apk`. The derived `versionCode`
   is the core version's (1002019), because the platform refuses only a
   *lower* code (see [DEVELOPMENT.md](DEVELOPMENT.md#signing)) — that is what
   lets a beta install over any earlier release and the final install over
   its own betas.
2. **Create it as a GitHub prerelease:**
   ```sh
   gh release create v1.2.19-beta \
     app/build/outputs/apk/release/uukanshu-1.2.19-beta.apk \
     --prerelease --title "v1.2.19-beta" --notes "…concise changelog…"
   ```
   `releases/latest` skips prereleases, so stable users keep seeing the last
   final release. The in-app updater additionally refuses any payload whose
   tag carries a suffix (or whose API `prerelease` flag is set), so a beta
   published *without* this flag still cannot be installed by the app — the
   check reports an error until the flag is added.
3. **Never publish a prerelease for a version whose final is already out.**
   Successive betas of the same `X.Y.Z` are fine (they share one
   `versionCode`; a later beta simply replaces the earlier one).

Testers install the APK by hand from the release page: the app has one
stable channel, so a beta build's own **檢查更新** keeps answering
"up to date" until the final ships, and it will then offer the final.

## Updater contract (do not break)

The in-app updater (`data/update/`, `ui/update/`, see
[ARCHITECTURE.md](ARCHITECTURE.md#in-app-update)) depends on this exact
shape — keep it stable or the auto-update flow silently stops finding
releases:

- Tag is `vX.Y.Z` and **must equal** `versionName X.Y.Z` (numeric
  dot-separated compare; leading `v` stripped). Prerelease tags
  (`vX.Y.Z-suffix`) are outside this contract: the app refuses them, see
  [Publishing a prerelease](#publishing-a-prerelease-beta). Build metadata
  (`vX.Y.Z+build`) is outside it too — the updater accepts exactly three
  numeric parts.
- Exactly one asset named `uukanshu-X.Y.Z.apk`. The updater counts every
  `.apk` on the release and requires the single one to be named
  `uukanshu-{tag-version}.apk`: a version-mismatched `uukanshu-*.apk`, a
  renamed asset or any second `.apk` fails the release closed (no update is
  offered at all) rather than the updater picking one of two binaries. Never
  rename it and never attach a second APK.
- The APK is integrity-checked in-app via the asset's server-side sha256
  (`digest` field, computed by GitHub at upload): the downloaded file must
  hash to it before it counts as ready or installable; a mismatch deletes
  the file and asks for a re-download. Nothing to compute by hand — `gh
  release create` uploads are digested automatically. **Never re-upload or
  replace the asset after publishing**: clients that already fetched the
  payload hold the old digest and will refuse the new bytes (by design — a
  post-publish swap is a publish mistake, not a refresh). Recovery is bump
  to a new `vX.Y.Z`, never overwrite. Threat model:
  catches corruption / wrong-stale content; it is NOT an anti-tamper system
  against a malicious GitHub or a compromised release key (the APK update
  signature check stays the anti-tamper anchor; out-of-band release signing
  would be the escalation).
- Release body is shown verbatim as the update changelog (Traditional Chinese for non-technical users; keep it concise,
  plain Markdown, no huge dumps, no jargon — the dialog scrolls at ~220dp).
- Non-matching APK assets fail closed (no update offered, never a partial
  install) — see `UpdateViewModel` / `UpdateDownloader.apkState` /
  `apkStateIO` (byte-exact when size known; sha256-verified when the
  release ships a digest; unknown size installs only with a fresh
  DownloadManager Success receipt for that download — a stale file or a bare
  user tap never qualifies).
