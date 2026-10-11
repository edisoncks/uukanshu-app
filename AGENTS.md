# Project Guidelines

## Git

- Atomic conventional commits

## Docs

- `README.md`: user-only (install+use); internals → `docs/` (`DEVELOPMENT`, `ARCHITECTURE`, `SCRAPING`, `RELEASING`+updater contract, `CONTRIBUTING`).
- Same-commit doc updates on behaviour change; comments why-only, link docs, no history.

## Gradle

- Every command gets an explicit short timeout (15–75s; never 600s). Long builds run in background (`nohup … > log &`) and are polled with short commands.
- Before every Gradle compile/test run, clear stale `~/.local/share/kotlin/daemon/*.run`: on WSL mirrored mode loopback never refuses, so one dead entry wedges `kspDebugKotlin` forever — preventing is cheaper than detecting after minutes stuck. If still wedged (~idle CPU, `jstack` in `connectAndLease`): kill it, `./gradlew --stop`, retry fresh. Last resort, local only: `-Pkotlin.compiler.execution.strategy=in-process`.
- Verify before push: `./gradlew testDebugUnitTest` green plus zero `failures`/`errors` in `app/build/test-results/testDebugUnitTest/*.xml.
