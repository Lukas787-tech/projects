//! Everything that differs between operating systems lives in this crate, and
//! only here: other crates must not use `cfg(windows)` or hard-code OS paths.
//!
//! Kickoff targets Windows 11. Other platforms compile (so CI and tests run on
//! Linux) but some features report themselves as unavailable.

pub mod paths;
pub mod secrets;

pub use paths::AppPaths;
pub use secrets::{MemorySecretStore, OsSecretStore, SecretStore, SecretStoreError};

/// Short name of the current OS, for diagnostics.
pub fn os_name() -> &'static str {
    std::env::consts::OS
}
