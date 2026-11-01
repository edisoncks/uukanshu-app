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

Prereleases (`X.Y.Z-rc.1`, `X.Y.Z-beta`) go through the same CI pipeline as
finals — same production key, same updater-contract and signature checks — but
they are started by hand, because a suffix tag pushed to the repo is skipped by
the workflow's job guard.

1. **Bump the version *and* write the changelog.** `versionName` carries the
   suffix, and the user-facing notes go in `docs/release-notes/v<version>.md`
   (Traditional Chinese, plain Markdown, concise). Commit both:

   ```sh
   # app/build.gradle.kts: versionName = "1.2.28-rc.1"
   # docs/release-notes/v1.2.28-rc.1.md
   ```

   The derived `versionCode` is the core version's (1002028), because the
   platform refuses only a *lower* code (see
   [DEVELOPMENT.md](DEVELOPMENT.md#signing)) — that is what lets the candidate
   install over any earlier release and the final install over it later.
2. **Dispatch the workflow from the default branch:**

   ```sh
   gh workflow run release.yml --ref main -f version=1.2.28-rc.1
   ```

   It refuses an input without a suffix, a non-default branch, an existing tag
   or a missing notes file. It creates the annotated tag from the notes file
   (the tag message is what `gh release create` publishes), then tests, builds
   with the production key, verifies the contract + signer fingerprint and
   publishes with `--prerelease --latest=false`.
3. **Approve the `production` environment** when prompted, then confirm the
   release: exactly one asset `uukanshu-X.Y.Z-rc.1.apk`, an exposed digest, and
   `latest=false`.

Notes that hold for every prerelease:

- `releases/latest` skips prereleases, so stable users keep seeing the last
  final release. The in-app updater refuses any payload whose tag carries a
  suffix *and* any payload flagged prerelease, so a candidate cannot reach a
  stable user even if the flag were forgotten.
- **Never publish a prerelease for a version whose final is already out.**
  Successive candidates of the same `X.Y.Z` are fine (they share one
  `versionCode`; a later one simply replaces the earlier one) — but publish
  each as either `-rc.N` or `-beta`, not both, so testers know what to expect.

Testers install the APK by hand from the release page: the app has one stable
channel, so a candidate's own **檢查更新** keeps answering "up to date" until
the final ships, and it will then offer the final.

Signing by hand (the pre-CI path) is an emergency exit only: it needs the
production keystore and the same three properties above, and CI must be down to
justify it.

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
- Release body is shown in full as the update changelog (Traditional Chinese for non-technical users; keep it concise,
  plain Markdown, no huge dumps, no jargon — the dialog scrolls at ~220dp). The
  app trims surrounding whitespace and converts Traditional → Simplified when
  that display mode is on; it truncates and restructures nothing, so what you
  write is what the user reads.
- Non-matching APK assets fail closed (no update offered, never a partial
  install) — see `UpdateViewModel` / `UpdateDownloader.apkState` /
  `apkStateIO` (byte-exact when size known; sha256-verified when the
  release ships a digest; unknown size installs only with a fresh
  DownloadManager Success receipt for that download — a stale file or a bare
  user tap never qualifies).
