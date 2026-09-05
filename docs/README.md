# Technical documentation

Start here if you build, modify, test, or release the app. End users should
start at the [README](../README.md) instead — this folder is for developers.

| Doc | What it covers |
|---|---|
| [DEVELOPMENT.md](DEVELOPMENT.md) | Toolchain (mise, Java 17, Gradle, Kotlin), Android SDK setup, build, test, signing, versioning, project layout |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Screens, navigation, data layers (SiteApi, Parser, BookRepo, Room, DataStore), Traditional/Simplified rendering, offline cache, update flow |
| [SCRAPING.md](SCRAPING.md) | How the app fetches and parses uukanshu.cc (endpoints, retry, Cloudflare handling, TOC/chapter rules, rate limiting) |
| [RELEASING.md](RELEASING.md) | How to cut a release, including the **updater contract** that must not be broken |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Git conventions, workflow, tests |

Source of truth reminders:

- `versionName` in `app/build.gradle.kts` is the single source of truth for
  the version and the release APK name (`uukanshu-{version}.apk`).
- The in-app updater depends on the exact release shape described in
  [RELEASING.md](RELEASING.md#updater-contract-do-not-break) — read it before
  changing anything about tags, asset names, or release notes.
