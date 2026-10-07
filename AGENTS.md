# Project Guidelines

## Git

- Atomic conventional commits

## Docs

- `README.md`: user-only (install+use); internals → `docs/` (`DEVELOPMENT`, `ARCHITECTURE`, `SCRAPING`, `RELEASING`+updater contract, `CONTRIBUTING`).
- Same-commit doc updates on behaviour change; comments why-only, link docs, no history.

## Gradle

- Every command gets an explicit short timeout (15–75s; never 600s). Long builds run in background (`nohup … > log &`) and are polled with short commands.
- A task stuck with ~idle CPU and zero file writes (e.g. `kspDebugKotlin`) is wedged, not slow: kill it, `./gradlew --stop`, retry fresh — longer timeouts won't help.
- Verify before push: `./gradlew testDebugUnitTest` green plus zero `failures`/`errors` in `app/build/test-results/testDebugUnitTest/*.xml.
