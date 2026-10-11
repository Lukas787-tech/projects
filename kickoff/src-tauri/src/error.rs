//! The error type every command returns to the frontend.

use kickoff_core::redact;
use serde::Serialize;
use ts_rs::TS;

#[derive(Debug, Clone, Copy, Serialize, TS)]
#[serde(rename_all = "snake_case")]
#[ts(export)]
pub enum ErrorCode {
    Database,
    SecretStore,
    Internal,
}

/// A command failure. The message is redacted before it leaves Rust.
#[derive(Debug, Clone, Serialize, TS)]
#[ts(export)]
pub struct CommandError {
    pub code: ErrorCode,
    pub message: String,
}

impl CommandError {
    pub fn new(code: ErrorCode, message: impl std::fmt::Display) -> Self {
        let message = redact::global().redact(&message.to_string()).into_owned();
        tracing::warn!(?code, %message, "command failed");
        Self { code, message }
    }
}

impl From<kickoff_store::StoreError> for CommandError {
    fn from(e: kickoff_store::StoreError) -> Self {
        Self::new(ErrorCode::Database, e)
    }
}

impl From<kickoff_platform::SecretStoreError> for CommandError {
    fn from(e: kickoff_platform::SecretStoreError) -> Self {
        Self::new(ErrorCode::SecretStore, e)
    }
}

pub type CmdResult<T> = Result<T, CommandError>;
