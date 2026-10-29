# Project Guidelines

## Git

- Atomic conventional commits
- A PR body stands on its own: a reviewer with no chat history and no linked issue can still tell what changed, what it deliberately does not do, and how it was verified (command plus result). No "as discussed", no relying on the conversation that produced it.

## Docs

- `README.md`: user-only (install+use); internals → `docs/` (`DEVELOPMENT`, `ARCHITECTURE`, `SCRAPING`, `RELEASING`+updater contract, `CONTRIBUTING`).
- Same-commit doc updates on behaviour change; comments why-only, link docs, no history.

## Gradle

- Every command gets an explicit short timeout (15–75s; never 600s). Long builds run in background (`nohup … > log &`) and are polled with short commands.
- Before every Gradle compile/test run, clear stale `~/.local/share/kotlin/daemon/*.run`: on WSL mirrored mode loopback never refuses, so one dead entry wedges `kspDebugKotlin` forever — preventing is cheaper than detecting after minutes stuck. If still wedged (~idle CPU, `jstack` in `connectAndLease`): kill it, `./gradlew --stop`, retry fresh. Last resort, local only: `-Pkotlin.compiler.execution.strategy=in-process` (Gradle property, not `-D`), e.g. `./gradlew testDebugUnitTest --no-daemon -Pkotlin.compiler.execution.strategy=in-process` — never commit it to `gradle.properties`.
- Verify before push: `./gradlew testDebugUnitTest` green plus zero `failures`/`errors` in `app/build/test-results/testDebugUnitTest/*.xml.
