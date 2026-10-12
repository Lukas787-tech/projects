# Kickoff

An AI pre-production studio for Claude Code projects (Windows 11). Describe a
game idea in one sentence. Kickoff interviews you, researches references and
licensed assets, and generates a starter kit (`CLAUDE.md`, design docs,
milestones, `.claude/` skills, `.mcp.json`, a first prompt) ready to open in
Claude Code. The whole core flow works with free AI services. Paid providers
are optional.

> "Kickoff" is a working name. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#renaming-the-app) to rename it.

## Status

| Milestone | Scope | State |
|---|---|---|
| M0 | Provider verification, workspace, SQLite, logging with redaction, CI | ✅ |
| M1 | Key library, LLM layer (free providers, fallback, usage meter), setup wizard | ✅ |
| M2 | Producer interview (text) with live brief | ✅ |
| M3 | Voice: local Whisper, Fish Audio / Piper / Windows voices | Windows CI pending |
| M4 | Research and reference triage with license tracking | Windows CI pending |
| M5 | Starter kit generator and key launcher | |
| M6 | Hardening and installer | |

## Develop

Prerequisites (Windows 11):

- [Rust](https://rustup.rs) (stable, 1.90+) with the MSVC toolchain
- [Node.js](https://nodejs.org) 22+
- WebView2 (already included in Windows 11)
- For local Whisper (on by default): LLVM/Clang and CMake. Build with
  `--no-default-features` to leave it out (Groq Whisper still works).

```powershell
cd kickoff
npm install
npm run tauri dev       # run the app with hot reload
cargo test --workspace  # Rust tests (also refreshes src/bindings)
npm test                # frontend tests
npm run tauri build     # release installer (NSIS) in target/release/bundle
```

`npm run dev` alone serves the UI in a browser with mocked commands. This is useful for UI work without the Rust side.

Logs are written to `%APPDATA%\dev.kickoff.studio\logs`. Set `KICKOFF_LOG=debug` for more detail.

## Docs

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): crates, security model, data and IPC
- [docs/PROVIDERS.md](docs/PROVIDERS.md): which AI, voice, search and asset services are free and how each was verified
- [docs/SMOKE_TESTS.md](docs/SMOKE_TESTS.md): manual Windows checks per milestone
- [data/providers.toml](data/providers.toml): editable provider presets
