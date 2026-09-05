# uukanshu

A clean, minimalist Android reading app for novels from [uukanshu.cc](https://uukanshu.cc) — just the story, no ads, no images.

The app's buttons and menus are shown in Traditional Chinese. This guide uses English with the on-screen Chinese labels in brackets, so you can match them on your phone.

---

## Install

1. On your Android phone, open the [**Releases page**](https://github.com/edisoncks/uukanshu-app/releases/latest).
2. Download the file named `uukanshu-{version}.apk` (for example `uukanshu-1.0.19.apk`).
3. Open the downloaded file to install it.
   - Your phone needs **Android 12 or newer**.
   - If asked, allow **"Install unknown apps"** for your browser or file manager. This is needed because the app is installed directly instead of through the Play Store.

That's it — open the **uukanshu** app and start reading.

> For developers: building from source, signing, and project internals live in [`docs/`](docs/README.md).

## Update the app

You don't need to reinstall manually every time:

- The app checks for new versions automatically (at most once a day) and will pop up a message when one is available.
- You can also check anytime: go to the **Settings tab → 設定 → 更新 → 檢查更新** ("Check for updates").
- The Settings screen also shows your **currently installed version** (目前版本).
- When updating, tap **立即更新** ("Update now"), wait for the download, then tap **立即安裝** ("Install now"). Your bookshelf, downloads, and settings are kept.
- If you don't want a particular version, tap **跳過此版本** ("Skip this version").
- If the in-app download doesn't work, download the new APK from the Releases page (same steps as Install) and install it over the old one.

The first time you update inside the app, your phone may ask you to allow **"Install unknown apps" for uukanshu** — allow it once and you won't be asked again.

## How to use

The bottom of the screen has four tabs:

### 首頁 — Home

- **最近更新** ("Recently updated"): the latest updated novels.
- **分類** ("Categories"): browse by genre (10 genres, e.g. 玄幻奇幻, 武俠仙俠, 現代都市…). Tap a genre chip, scroll down for more pages.
- Tap any book cover/title card to open its detail page.

### 搜索 — Search

- Tap the Search tab, type a book title (書名搜索…), and results appear automatically.
- Tap a result to open its detail page.

### Book detail page

- Shows the title, author, status, category, and introduction.
- Shows how many chapters exist in total (共 … 章) and how many are already saved on your phone (已緩存 … 章).
- Chapters you have already saved show a **✓ 已緩存** ("cached") mark.
- Tap any chapter to start reading. If you were reading before, tap **繼續閱讀** ("Continue reading") to jump back to where you left off.
- To save the whole book for offline reading, tap **下載整本** ("Download whole book"). You can **取消** ("Cancel") anytime. Downloading again later with **重新下載整本** refreshes it.

### Reading page

- Swipe/scroll to read. Use **上一章** ("Previous chapter") and **下一章** ("Next chapter") at the bottom to move between chapters.
- Tap **⋯** (bottom-left) for reading options:
  - Switch between **繁體** (Traditional) and **簡體** (Simplified).
  - Make text bigger (**A+**) or smaller (**A-**).
  - Change theme (主題：自動 / 淺色 / 深色 — Auto / Light / Dark).
- The app automatically saves your place and pre-saves the next few chapters, so turning the page works even with a weak connection.

### 書架 — Library (your saved books)

- Shows every book saved on your phone, with how many chapters are saved (… 章) and how much space it uses.
- Books being downloaded show live progress (下載中 done/total) and can be cancelled (取消) here; tapping one opens its detail page.
- The top line shows the total (e.g. `3 本 · 12.5 MB`).
- Tap a book to open it. Tap **刪除緩存** ("Delete") to remove one book, or **清空全部** ("Clear all") to remove everything. Deleted books need to be downloaded again, so you'll be asked to confirm before clearing everything.

### 設定 — Settings

Three groups:

- **外觀** ("Appearance"): 自動 / 淺色 / 深色 (Auto / Light / Dark).
- **語言** ("Language"): switch between 繁體 (Traditional) and 簡體 (Simplified). Applies everywhere instantly.
- **更新** ("Update"): shows 目前版本 (current version), a 檢查更新 ("Check for updates") button, and the skip-version option.

## Reading offline

- Once a chapter is opened or downloaded, it stays on your phone and can be read without internet.
- On the detail page, **離線模式 · 緩存版本** ("Offline mode · cached version") means you're seeing the saved copy because the network is unavailable.
- To free space, delete books from the 書架 (Library) tab.

## Troubleshooting

| Problem | What to do |
|---|---|
| "Can't install" / "App not installed" | Make sure your phone runs **Android 12+** and that you allowed **Install unknown apps** for your browser (first install) or for **uukanshu** (in-app update). Then try opening the APK again. |
| "Package appears to be invalid" | The APK didn't download completely. Delete it and download again from the Releases page. |
| Update check fails | Check your internet connection and tap **重試** ("Retry"). If it keeps failing, download the APK from the Releases page manually. |
| Book or chapter won't load | Check your connection and tap **重試** ("Retry"). Saved chapters still work offline. |
| Text shows the "wrong" Chinese | Flip the language switch in **設定 → 語言** or in the reader's **⋯** menu. |
| Text too small / big | Use **A- / A+** in the reader's **⋯** menu. The size is remembered. |
| Running out of space | Go to **書架** (Library) to see per-book sizes and delete books you finished. |

## Privacy

- No account, no login, no ads.
- Your reading progress, downloads, and settings stay on your phone.
- The app connects to the internet only to fetch novel text from uukanshu.cc and to check GitHub Releases for app updates.

## Need help?

- To report a problem or suggest a feature, please [open an issue](https://github.com/edisoncks/uukanshu-app/issues) with your app version (see 設定 → 更新 → 目前版本), your phone model / Android version, and what you tapped before the problem happened.
- To install a specific older version, browse [all releases](https://github.com/edisoncks/uukanshu-app/releases).

## For developers

Technical documentation lives in [`docs/`](docs/README.md):

- [Development setup & building](docs/DEVELOPMENT.md) — toolchain, build, test, signing
- [Architecture](docs/ARCHITECTURE.md) — screens, data flow, storage, update flow
- [Scraping notes](docs/SCRAPING.md) — how the app reads uukanshu.cc
- [Releasing](docs/RELEASING.md) — how to cut a release (updater contract)
- [Contributing](docs/CONTRIBUTING.md) — commits, tests, workflow
