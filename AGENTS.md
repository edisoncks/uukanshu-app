# Project Guidelines

## Git

- Atomic conventional commits

## Docs

- `README.md`: user-only (install+use); internals → `docs/` (`DEVELOPMENT`, `ARCHITECTURE`, `SCRAPING`, `RELEASING`+updater contract, `CONTRIBUTING`).
- Same-commit doc updates on behaviour change; comments why-only, link docs, no history.

## Gradle

- Every command gets an explicit short timeout (15–75s; never 600s). Long builds run in background (`nohup … > log &`) and are polled with short commands.
- On WSL mirrored networking mode, loopback connects never refuse, so a wedged `kspDebugKotlin` (~idle CPU, `jstack` in `connectAndLease`) is a stale Kotlin-daemon probe: kill it, `./gradlew --stop`, always delete stale `~/.local/share/kotlin/daemon/*.run`, retry fresh. Last resort, local only: `-Pkotlin.compiler.execution.strategy=in-process`.
- Verify before push: `./gradlew testDebugUnitTest` green plus zero `failures`/`errors` in `app/build/test-results/testDebugUnitTest/*.xml.
