# uukanshu

[繁體中文](README.md) | English

A clean, minimalist Android reading app for novels from [uukanshu.cc](https://uukanshu.cc) — just the story, no ads, no images.

> The app itself is in Traditional Chinese. This English guide mirrors the Traditional Chinese [README](README.md) (which may be newer).

---

## Install

1. On your phone, open the [**Releases page**](https://github.com/edisoncks/uukanshu-app/releases/latest).
2. Download `uukanshu-{version}.apk` (e.g. `uukanshu-1.2.8.apk`).
3. Open the file to install.
   - Requires **Android 12 or newer**.
   - If asked, allow your browser to “Install unknown apps” once.

Open **uukanshu** and start reading.

## Update

- New versions prompt automatically (at most once a day).
- Or check manually: **設定 → 更新 → 檢查更新**.
- Tap **立即更新**, wait, then **立即安裝**. Your shelf, downloads and settings are kept.
- The first in-app update asks you to allow **uukanshu** to “Install unknown apps” once.

## How to use

Wireframes below use placeholder text (`示例` = example, not real novel content):

### Book detail

```
┌─────────────────────────┐
│ ←  Book Title Example   │  ← Top bar: back + title
├─────────────────────────┤
│ Author: Example         │
│ [   Continue: Ch.12   ] │
│ [ Download whole book ] │
│ [   Share book URL    ] │
│                         │
│ 872 chapters · 3 saved  │
│ 1. Ch.1 Example  ✓      │
│ 2. Ch.2 Example  ✓      │
│ 2. Ch.3 Example  ✓      │
│ 2. Ch.4 Example         │
└─────────────────────────┘
```

- The top bar keeps the book title visible while scrolling; back is top-left.
- **Continue reading** jumps back in; **Download whole book** saves it offline.
- Saved chapters show **✓**; **Share link** sends the book URL to a friend.

### Reader

```
┌─────────────────────────┐
│ ← Book Title Example    │
│   307 / 872 Ch.8        │
├─────────────────────────┤
│  Body text…             │
│  (selectable)           │
│                         │
│                         │
│                         │
│                         │
│                         │
├─────────────────────────┤
│ [⋯] [Prev] [Next]       │
└─────────────────────────┘
```

- Scroll to read, switch chapters at the bottom. The **⋯** sheet holds language (繁體/簡體), font size (remembered), and theme (auto/light/dark).

### Four tabs

- **首頁 Home**: recent updates + categories.
- **搜索 Search**: search by title.
- **書架 Library**: saved books. New chapters show a badge; **Check for updates** refreshes all (or wait for the daily check). Opening the detail clears it.
- **設定 Settings**: appearance, language, follow-updates, update (current version lives here).

## Offline

- Opened/downloaded chapters work without internet.
- **離線模式 · 緩存版本** on the detail page means you are reading the saved copy.
- Out of space? Delete finished books from the library.

## Troubleshooting

| Problem                    | What to do                                              |
| -------------------------- | ------------------------------------------------------- |
| Can't install              | Android 12+, allow “Install unknown apps”, retry.       |
| Downloaded file won't open | Incomplete download — delete and re-download.           |
| Update/book fails to load  | Check connection, tap Retry. Saved chapters still work. |
| Wrong script               | Switch in Settings → Language or the reader **⋯** menu. |
| Text size                  | Reader **⋯** → A- / A+ (remembered).                    |

## Privacy

- No account, no ads. Progress, downloads and settings stay on your phone.
- Network is only used for novel text (uukanshu.cc) and update checks (GitHub Releases).

## Need help?

- [Open an issue](https://github.com/edisoncks/uukanshu-app/issues) with your app version (Settings → Update → current version), phone model, and what you tapped.
- Older versions: [all releases](https://github.com/edisoncks/uukanshu-app/releases).

## For developers

Internals live in [`docs/`](docs/README.md).
