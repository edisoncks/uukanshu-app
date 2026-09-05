# Scraping notes

How the app reads uukanshu.cc. Ported from `uukanshu-cli`; the CLI remains
the reference implementation for fetch/parse semantics.

## Base rules

- Base URL: `https://uukanshu.cc` (`Site.kt` / `Parser.BASE`). Canonical book
  URL form: `https://uukanshu.cc/book/{id}/`. Non-book URLs are rejected
  (`bookUrlOrNull` returns null); oversized numeric ids that overflow `Int`
  are treated as invalid, never thrown.
- **Text only.** `<img>` / iframes / scripts are never fetched or rendered.
  `SiteApi` only requests HTML pages; `Parser` never surfaces image nodes.
- App sends `POST /search` for search (fields `searchkey`, `searchtype=all`).
- Category catalogue is fixed (ids 1–10): 玄幻奇幻, 武俠仙俠, 現代都市,
  歷史軍事, 科幻小說, 遊戲競技, 恐怖靈異, 言情小說, 動漫同人, 其他類型 —
  endpoint `/class_{id}_{page}.html`. Recently-updated list is the Home
  default tab.

## Fetch (`data/net/SiteApi.kt`)

Mirrors CLI `fetch()`:

- Browser-like headers: Android Chrome mobile `User-Agent`,
  `Accept: text/html,…`, `Accept-Language: zh-TW,zh;q=0.9,en;q=0.8`,
  `Upgrade-Insecure-Requests: 1`. Gzip handled transparently by OkHttp.
- Timeouts: connect 30s, read 30s.
- Retry: **3 attempts** with cancellable backoff (`1500ms × attempt`, `delay` outside
  the single-flight gate) on transport errors and HTTP `408 / 429 / 5xx`. Deterministic client errors (e.g. 404)
  fail fast with no retry.
- Cloudflare interstitial sniff: response bodies whose `<title>` indicates a
  challenge/block are treated as failures (surfaced, retried per above).

## Parse (`data/parse/Parser.kt`)

Pure functions over HTML (Jsoup), covered by `ParserTest` fixtures. The
rules below look odd — they encode real site quirks. **Do not "simplify".**

- **TOC dedup (LAST wins):** the chapter-list page leads with a "latest
  updates" duplicate block, so the parser keeps the **LAST** occurrence of
  each (book, chapter) pair. Links pointing at *other* books (recommendation
  blocks) are dropped via a numeric bookId filter (ids compare numerically,
  so `/book/2/…` never matches `/book/20/…`).
- **Chapter body cut:** cut at `<div class="mulu-box"` (extra classes tolerated) first, then at the
  **LAST** standalone nav row (`上一章 … 下一章`), so in-body mentions of
  "上一章/下一章" don't truncate text. Body accepts both `readcotent` (live
  misspelling) and `readcontent` spellings so a typo fix doesn't zero chapters.
- **Prev/next resolution:** hrefs resolve with urljoin semantics **before**
  shape validation, with `?query`/`#fragment` stripped to canonical chapter URLs
  (tracking params never read as end-of-book, never fork cache keys). Non-chapter
  hrefs (TOC index, `lastchapter.php`, etc.) mean end-of-book → `null`. TOC links
  likewise tolerate query/fragment, storing canonical `/book/{id}/{page}.html`. Bare `position` is 1-based into the TOC;
  `pageId` (`/book/{id}/{page}.html`) is the stable identity used for the
  TOC-shift save guard.
- **Search/category cards:** strip `<span class="hot">` highlight markup via
  `.text()` so titles/authors come out clean. Word count anchors on the
  字數 label (not a bare 字) so an author name containing 字 can't hijack
  the field. Search also handles the
  exact-match case (keyword yields a book page, not a result list) and tagged
  result counts.
- **Reader title:** book name comes from TOC meta (`ReaderTitle.resolve`),
  never from the chapter title or stale UI state; chapter pages backfill it
  only when TOC meta is empty (offline edge).

## Politeness / rate limiting

- Bulk chapter fetching (full-novel download, next-5 prefetch) pauses
  **`crawlDelay()` random 1–3s** between requests to avoid rate
  limiting. Single chapter opens and TOC/search fetches have no delay.
- Prefetch is sequential (1-at-a-time), silent-fail: a failed chapter neither
  breaks reading nor forces a delay on the next one.
- Full-book downloads run one at a time (a second tapped book queues), so
  request pacing is always the single-book 1–3s profile.

## Platform constraints

- `minSdk 31` (Android 12+), `targetSdk 34`, `compileSdk 34`.
- Permissions: `INTERNET` + `ACCESS_NETWORK_STATE` (reading/fetching),
  `REQUEST_INSTALL_PACKAGES` (in-app update installer handoff only).
