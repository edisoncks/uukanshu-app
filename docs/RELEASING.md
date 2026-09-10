# Releasing

How to cut a `uukanshu` release. The APK is built locally (or on any machine
with `mise`), signed, and attached to a GitHub Release.

End users never need this — they install and update from the
[README](../README.md#install). This page is for maintainers.

## Prerequisites

- Push access to `main`.
- The official release keystore (see [DEVELOPMENT.md](DEVELOPMENT.md#signing)).
  Export the four env vars so the APK signature matches previous releases —
  otherwise users must uninstall before updating (data loss):
  ```sh
  export UUKANSHU_KEYSTORE_FILE=/path/to/official.keystore
  export UUKANSHU_KEYSTORE_PASSWORD=…
  export UUKANSHU_KEY_ALIAS=…
  export UUKANSHU_KEY_PASSWORD=…
  ```
- `mise` toolchain + Android SDK ready (`mise install`, `mise run setup-android` —
  see [DEVELOPMENT.md](DEVELOPMENT.md#first-time-setup)).

## Release steps

### 1. Bump the version

The version lives in one place: `versionName` in `app/build.gradle.kts`
(the APK is renamed to `uukanshu-{version}.apk` from it). Keep the tag in
sync:

```sh
# edit versionName in app/build.gradle.kts, then:
git add app/build.gradle.kts
git commit -m "chore: bump version to X.Y.Z"
```

### 2. Build and verify

```sh
mise run test
mise run build
# → app/build/outputs/apk/release/uukanshu-X.Y.Z.apk
```

Verify the signature before publishing:

```sh
export ANDROID_SDK_ROOT=$PWD/.android-sdk
.android-sdk/build-tools/35.0.0/apksigner verify \
  app/build/outputs/apk/release/uukanshu-X.Y.Z.apk
```

### 3. Push the tag

```sh
git tag vX.Y.Z
git push origin main vX.Y.Z
```

### 4. Publish the release

```sh
gh release create vX.Y.Z \
  app/build/outputs/apk/release/uukanshu-X.Y.Z.apk \
  --title "vX.Y.Z" \
  --notes "…concise changelog, plain Markdown (shown verbatim in the in-app update dialog)…"
```

Write the release notes for end users (what changed, in a few lines) —
they are shown verbatim as the update changelog (dialog scrolls at ~220dp,
so keep it short, no huge dumps).

### 5. Smoke-test

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

## Updater contract (do not break)

The in-app updater (`data/update/`, `ui/update/`, see
[ARCHITECTURE.md](ARCHITECTURE.md#in-app-update)) depends on this exact
shape — keep it stable or the auto-update flow silently stops finding
releases:

- Tag is `vX.Y.Z` and **must equal** `versionName X.Y.Z` (numeric
  dot-separated compare; leading `v` stripped).
- Exactly one asset named `uukanshu-X.Y.Z.apk` (the updater enforces an exact
  `uukanshu-{tag-version}.apk` match; any other `.apk`, including a
  version-mismatched `uukanshu-*.apk`, is ignored and yields no update).
  Never rename it and never attach a second APK.
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
- Release body is shown verbatim as the update changelog (keep it concise,
  plain Markdown, no huge dumps — the dialog scrolls at ~220dp).
- Non-matching APK assets fail closed (no update offered, never a partial
  install) — see `UpdateViewModel` / `UpdateDownloader.apkState` /
  `apkStateIO` (byte-exact when size known; sha256-verified when the
  release ships a digest; unknown size installs only with a fresh
  DownloadManager Success receipt for that download — a stale file or a bare
  user tap never qualifies).
