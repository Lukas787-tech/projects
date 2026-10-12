//! The LLM provider abstraction.
//!
//! Concrete clients (OpenAI-compatible, Ollama, Anthropic) live in
//! `kickoff-providers`; everything here is provider-agnostic so the interview,
//! research and kit logic can be tested with fakes.

mod json_repair;
pub mod router;
pub mod structured;

use std::pin::Pin;
use std::time::Duration;

use async_trait::async_trait;
use futures::Stream;
use serde::{Deserialize, Serialize};

pub use json_repair::extract_json;
pub use router::{Answer, RetryPolicy, Route, Router, RouterError, RouterEvent, RouterObserver, WaitReason};
pub use structured::{Structured, StructuredError, StructuredOptions, structured_call};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Role {
    System,
    User,
    Assistant,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ChatMessage {
    pub role: Role,
    pub content: String,
}

impl ChatMessage {
    pub fn system(content: impl Into<String>) -> Self {
        Self { role: Role::System, content: content.into() }
    }
    pub fn user(content: impl Into<String>) -> Self {
        Self { role: Role::User, content: content.into() }
    }
    pub fn assistant(content: impl Into<String>) -> Self {
        Self { role: Role::Assistant, content: content.into() }
    }
}

/// What shape the reply should have. Providers map this to their native JSON
/// mode where they have one; the schema is also always described in the prompt
/// because small models ignore native modes surprisingly often.
#[derive(Debug, Clone, Default, PartialEq)]
pub enum OutputFormat {
    #[default]
    Text,
    /// Any JSON object.
    Json,
    /// JSON matching a schema.
    JsonSchema { name: String, schema: serde_json::Value },
}

#[derive(Debug, Clone, Default)]
pub struct ChatRequest {
    /// Set by the router for each route; providers use it as-is.
    pub model: String,
    pub messages: Vec<ChatMessage>,
    pub temperature: Option<f32>,
    pub max_tokens: Option<u32>,
    pub format: OutputFormat,
}

impl ChatRequest {
    pub fn new(messages: Vec<ChatMessage>) -> Self {
        Self { messages, ..Self::default() }
    }
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Usage {
    pub prompt_tokens: u32,
    pub completion_tokens: u32,
    /// True when the provider didn't report usage and it was estimated.
    pub estimated: bool,
}

impl Usage {
    /// Rough token estimate (~4 characters per token) for providers that don't report usage.
    pub fn estimate(prompt_chars: usize, completion_chars: usize) -> Self {
        let tokens = |chars: usize| u32::try_from(chars.div_ceil(4)).unwrap_or(u32::MAX);
        Self { prompt_tokens: tokens(prompt_chars), completion_tokens: tokens(completion_chars), estimated: true }
    }

    pub fn total(&self) -> u32 {
        self.prompt_tokens.saturating_add(self.completion_tokens)
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct ChatResponse {
    pub content: String,
    pub usage: Usage,
    /// Model that actually answered (may differ from the requested alias).
    pub model: String,
    /// The reply hit the token limit.
    pub truncated: bool,
}

#[derive(Debug, Clone, PartialEq)]
pub enum ChatDelta {
    Text(String),
    Done { usage: Option<Usage>, truncated: bool, model: Option<String> },
}

pub type ChatStream = Pin<Box<dyn Stream<Item = Result<ChatDelta, LlmError>> + Send>>;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, ts_rs::TS)]
#[ts(export)]
pub struct ModelInfo {
    pub id: String,
    /// e.g. "8B" for local models, when known.
    pub parameter_size: Option<String>,
}

/// Which quota a rate limit refers to. Daily quotas won't clear by waiting a
/// few seconds, so the router moves to the next provider straight away.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LimitScope {
    Minute,
    Day,
    Unknown,
}

#[derive(Debug, Clone, thiserror::Error)]
pub enum LlmError {
    #[error("rate limited: {message}")]
    RateLimited { retry_after: Option<Duration>, scope: LimitScope, message: String },
    #[error("the API key was rejected: {0}")]
    Auth(String),
    #[error("model not available: {0}")]
    ModelNotFound(String),
    #[error("request rejected: {0}")]
    BadRequest(String),
    #[error("provider error ({status}): {message}")]
    Server { status: u16, message: String },
    #[error("network error: {0}")]
    Network(String),
    #[error("the provider took too long to respond")]
    Timeout,
    #[error("unexpected response: {0}")]
    InvalidResponse(String),
    #[error("provider not reachable: {0}")]
    Unavailable(String),
    #[error("cancelled")]
    Cancelled,
}

impl LlmError {
    /// Short machine-readable category, used in events and the UI.
    pub fn kind(&self) -> &'static str {
        match self {
            LlmError::RateLimited { scope: LimitScope::Day, .. } => "quota_exhausted",
            LlmError::RateLimited { .. } => "rate_limited",
            LlmError::Auth(_) => "auth",
            LlmError::ModelNotFound(_) => "model_not_found",
            LlmError::BadRequest(_) => "bad_request",
            LlmError::Server { .. } => "server",
            LlmError::Network(_) => "network",
            LlmError::Timeout => "timeout",
            LlmError::InvalidResponse(_) => "invalid_response",
            LlmError::Unavailable(_) => "unavailable",
            LlmError::Cancelled => "cancelled",
        }
    }
}

#[async_trait]
pub trait LlmProvider: Send + Sync {
    /// Preset id (`gemini`, `ollama`, …), used for throttling and usage tracking.
    fn id(&self) -> &str;

    async fn list_models(&self) -> Result<Vec<ModelInfo>, LlmError>;

    async fn chat_stream(&self, request: &ChatRequest) -> Result<ChatStream, LlmError>;

    /// Non-streaming chat. The default collects [`LlmProvider::chat_stream`].
    async fn chat(&self, request: &ChatRequest) -> Result<ChatResponse, LlmError> {
        use futures::StreamExt;

        let mut stream = self.chat_stream(request).await?;
        let mut content = String::new();
        let mut usage = None;
        let mut truncated = false;
        let mut model = None;
        while let Some(delta) = stream.next().await {
            match delta? {
                ChatDelta::Text(t) => content.push_str(&t),
                ChatDelta::Done { usage: u, truncated: t, model: m } => {
                    usage = u;
                    truncated = t;
                    model = m;
                }
            }
        }
        let usage = usage.unwrap_or_else(|| Usage::estimate(prompt_chars(request), content.len()));
        Ok(ChatResponse { content, usage, model: model.unwrap_or_else(|| request.model.clone()), truncated })
    }
}

/// Total characters in a request's messages (for usage estimates).
pub fn prompt_chars(request: &ChatRequest) -> usize {
    request.messages.iter().map(|m| m.content.len()).sum()
}
