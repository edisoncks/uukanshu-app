# Project Guidelines

## Git

- Atomic conventional commits

## Docs

- `README.md`: user-only (install+use); internals → `docs/` (`DEVELOPMENT`, `ARCHITECTURE`, `SCRAPING`, `RELEASING`+updater contract, `CONTRIBUTING`).
- Same-commit doc updates on behaviour change; comments why-only, link docs, no history.

## Gradle

- Every command gets an explicit short timeout (15–75s; never 600s). Long builds run in background (`nohup … > log &`) and are polled with short commands.
- A task stuck with ~idle CPU and zero file writes (e.g. `kspDebugKotlin`) is wedged, not slow: confirm with `jstack` plus `/proc/<pid>/io` `write_bytes` unchanged ~10s apart — longer timeouts won't help. Waiting in `connectAndLease`/`Socket.connect` means a stale Kotlin-daemon registry entry probed over loopback that never refuses on this WSL box: kill it, `./gradlew --stop`, delete stale `~/.local/share/kotlin/daemon/*.run`, retry fresh.
- Fallback if the daemon still won't start: `-Pkotlin.compiler.execution.strategy=in-process` (Gradle property, not `-D`), e.g. `./gradlew testDebugUnitTest --no-daemon -Pkotlin.compiler.execution.strategy=in-process`. Keep the flag local — never commit it to `gradle.properties`.
- Verify before push: `./gradlew testDebugUnitTest` green plus zero `failures`/`errors` in `app/build/test-results/testDebugUnitTest/*.xml.
