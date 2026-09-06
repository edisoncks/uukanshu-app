# Contributing

## Git conventions

Per [`AGENTS.md`](../AGENTS.md):

- **Atomic commits** — one logical change per commit (code + its tests/docs
  together; unrelated changes apart).
- **Conventional Commits** — `type(scope): subject`, e.g.:
  - `feat(reader): prefetch next 5 chapters`
  - `fix(search): handle exact-match book page`
  - `chore: bump version to 1.0.19`
  - `docs: split user README from technical docs`
  - `test(parser): add TOC dedup fixture`
  - `refactor(repo): …`, `perf(home): …`

Common scopes in this repo: `home`, `search`, `detail`, `reader`, `library`,
`settings`, `update`, `repo`, `parser`, `prefs`, `build`.

## Workflow

1. Make the change (keep the [updater contract](RELEASING.md#updater-contract-do-not-break)
   in mind — tag/asset/release-body shape is load-bearing).
2. Run the unit tests:
   ```sh
   mise run test   # ./gradlew testDebugUnitTest
   ```
3. Build the release APK as a smoke check when touching build/signing/UI:
   ```sh
   mise run build  # → app/build/outputs/apk/release/uukanshu-{version}.apk
   ```
4. Update docs in the same commit when behaviour changes:
   - User-visible behaviour → [`README.md`](../README.md)
   - Build/test/signing/layout → [`DEVELOPMENT.md`](DEVELOPMENT.md)
   - Screens/data/cache/update → [`ARCHITECTURE.md`](ARCHITECTURE.md)
   - Fetch/parse/rate-limit → [`SCRAPING.md`](SCRAPING.md)
5. Commit atomically with a Conventional Commit message, push, open a PR.

## Comments (why-only)

- Code comments explain *why* (site quirk, race guard, deadlock warning),
  never *how* already covered in `ARCHITECTURE.md` / `SCRAPING.md` — link
  the doc section instead of duplicating it.
- Keep comments short; delete history lessons ("previously X did Y").
- `docs/` is canonical; code points to docs, not the reverse.

## Tests

- Location: `app/src/test/java/cc/uukanshu/`.
- Pure JVM tests (JUnit + fixtures) — no device or emulator needed.
- What's covered: `Parser` (home/category/search/detail/chapter fixtures),
  `T2S` conversion, Home merge / Search dedup by stable id, reader title +
  TOC-shift save guard, repo behaviour, updater version-compare / APK
  completeness, `SiteApi` retry.
- Add or extend a fixture/test with any parser, merge, or updater change —
  the HTML quirks in [SCRAPING.md](SCRAPING.md) exist because the site
  really serves them; lock them in with tests.

## Releases

Cutting a release is a maintainer task — see [RELEASING.md](RELEASING.md).
In short: bump `versionName`, `mise run test && mise run build`, verify the
signature, push tag `vX.Y.Z`, publish via `gh release create` with exactly
one `uukanshu-X.Y.Z.apk` asset, then smoke-test install + in-app update.
