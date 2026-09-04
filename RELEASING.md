# Releasing

How to cut a `uukanshu` release. The APK is built locally (or on any machine
with `mise`), signed, and attached to a GitHub Release.

## Prerequisites

- Push access to `main`
- The official release keystore (see README Signing): export
  `UUKANSHU_KEYSTORE_FILE`, `UUKANSHU_KEYSTORE_PASSWORD`,
  `UUKANSHU_KEY_ALIAS`, `UUKANSHU_KEY_PASSWORD` so the APK signature matches
  previous releases — otherwise users must uninstall before updating.

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
.android-sdk/build-tools/34.0.0/apksigner verify \
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
  --notes "See README Features for what is included."
```

1. Open the **Releases** page and confirm the APK is attached.
2. On a device (Android 12+), download the APK from the release and
   install it once as a smoke test.
