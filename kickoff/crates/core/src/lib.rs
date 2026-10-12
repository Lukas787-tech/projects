//! Kickoff domain logic.
//!
//! This crate holds no OS-specific code and no Tauri code, so everything in it
//! can be unit-tested on any platform with fake providers.

pub mod branding;
pub mod keys;
pub mod llm;
pub mod redact;
pub mod secret;
pub mod settings;
#[cfg(any(test, feature = "testing"))]
pub mod testing;

pub use branding::{APP_ID, APP_NAME, APP_VERSION, AppInfo};
pub use secret::Secret;
pub use settings::{AiSettings, AppSettings, RouteSpec, Task, Theme};
