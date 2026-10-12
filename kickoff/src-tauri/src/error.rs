//! The error type every command returns to the frontend.

use kickoff_core::redact;
use serde::Serialize;
use ts_rs::TS;

use crate::ai::AiError;
use crate::interview::InterviewServiceError;
use crate::keys::KeyError;

#[derive(Debug, Clone, Copy, Serialize, TS)]
#[serde(rename_all = "snake_case")]
#[ts(export)]
pub enum ErrorCode {
    Database,
    SecretStore,
    Invalid,
    NotFound,
    Provider,
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

impl From<KeyError> for CommandError {
    fn from(e: KeyError) -> Self {
        match e {
            KeyError::Invalid(m) => Self::new(ErrorCode::Invalid, m),
            KeyError::NotFound => Self::new(ErrorCode::NotFound, e),
            KeyError::Store(e) => e.into(),
            KeyError::SecretStore(e) => e.into(),
        }
    }
}

impl From<AiError> for CommandError {
    fn from(e: AiError) -> Self {
        match e {
            AiError::Keys(k) => k.into(),
            AiError::Store(s) => s.into(),
            AiError::UnknownProvider(_) => Self::new(ErrorCode::NotFound, e),
            AiError::MissingKey(_) | AiError::Llm(_) => Self::new(ErrorCode::Provider, e),
        }
    }
}

impl From<InterviewServiceError> for CommandError {
    fn from(e: InterviewServiceError) -> Self {
        match e {
            InterviewServiceError::Store(s) => s.into(),
            InterviewServiceError::Ai(a) => a.into(),
            InterviewServiceError::NotFound => Self::new(ErrorCode::NotFound, e),
            InterviewServiceError::Busy | InterviewServiceError::Brief(_) | InterviewServiceError::Interview(_) => {
                Self::new(ErrorCode::Invalid, e)
            }
            InterviewServiceError::NoAi => Self::new(ErrorCode::Provider, e),
            InterviewServiceError::UnknownPack(_) | InterviewServiceError::Corrupt(_) => {
                Self::new(ErrorCode::Internal, e)
            }
        }
    }
}

pub type CmdResult<T> = Result<T, CommandError>;
