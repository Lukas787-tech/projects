# Kickoff: notes for Claude Code

Tauri 2 desktop app (Rust workspace + React/TS frontend). Windows 11 is the
target, but everything except `crates/platform` must build and test on Linux.
Read `docs/ARCHITECTURE.md` before changing structure.

## Commands (run from `kickoff/`)

- `cargo test --workspace`: Rust tests. This also regenerates `src/bindings/*.ts`; commit the result.
- `cargo clippy --workspace --all-targets -- -D warnings` and `cargo fmt --all`
- `npm test`, `npm run typecheck`, `npm run build`
- `npx tauri build --debug --no-bundle`: full app build

## Rules

- **Secrets:** never put a raw secret in SQLite, logs, IPC payloads, generated files or test fixtures that get committed. Use `kickoff_core::Secret`; only `crates/platform` touches the credential store.
- **OS code:** `cfg(windows)` and other OS-specific code belongs only in `crates/platform`.
- **Commands:** new commands are `async`, run blocking work in `spawn_blocking`, return `CmdResult<T>`, and use `ts_rs` types.
- **Provider facts** (endpoints, limits, model names) go in `data/providers.toml` and `docs/PROVIDERS.md` with a confidence level, never hard-coded.
- **App name:** use `APP_NAME` / `APP_ID` from `kickoff_core::branding`; never write "Kickoff" in code.
- **Migrations** are append-only: add `crates/store/src/migrations/NNN_*.sql`; never edit a shipped one.
- **Packs:** project-type specifics (brief fields, interview prompts) live in `packs/<id>/` as data. Keep game-specific logic out of `crates/core`; decisions like brief completeness stay in code, not prompts.
