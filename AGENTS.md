# Project Guidelines

## Git

- Atomic commits
- Conventional commits specifications

## Docs

- `README.md` is the user entrypoint (install + use, non-technical). No build,
  signing, scraping, or architecture internals there — link to `docs/` instead.
- Technical details live in `docs/`:
  `DEVELOPMENT.md` (build/test/signing), `ARCHITECTURE.md`, `SCRAPING.md`,
  `RELEASING.md` (incl. updater contract), `CONTRIBUTING.md`.
- Update the matching doc(s) in the same commit when behaviour changes.
