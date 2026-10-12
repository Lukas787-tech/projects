//! Test doubles for the LLM layer. Available to other crates with the
//! `testing` feature.

use std::collections::VecDeque;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use async_trait::async_trait;
use futures::stream;

use crate::llm::router::{RouterEvent, RouterObserver};
use crate::llm::{
    ChatDelta, ChatRequest, ChatResponse, ChatStream, LimitScope, LlmError, LlmProvider, ModelInfo, Usage, prompt_chars,
};

/// One scripted provider response.
#[derive(Debug, Clone)]
pub enum Step {
    Reply { text: String, usage: Option<Usage> },
    Stream(Vec<String>),
    StreamThenFail(Vec<String>, LlmError),
    Error(LlmError),
}

impl Step {
    pub fn reply(text: &str) -> Self {
        Step::Reply {
            text: text.to_owned(),
            usage: Some(Usage { prompt_tokens: 10, completion_tokens: 5, estimated: false }),
        }
    }
    pub fn stream(chunks: &[&str]) -> Self {
        Step::Stream(chunks.iter().map(|s| s.to_string()).collect())
    }
    pub fn stream_then_fail(chunks: &[&str], error: LlmError) -> Self {
        Step::StreamThenFail(chunks.iter().map(|s| s.to_string()).collect(), error)
    }
    pub fn error(error: LlmError) -> Self {
        Step::Error(error)
    }
    pub fn rate_limited(retry_after_secs: Option<u64>, scope: LimitScope) -> Self {
        Step::Error(LlmError::RateLimited {
            retry_after: retry_after_secs.map(Duration::from_secs),
            scope,
            message: "slow down".into(),
        })
    }
}

type Handler = Box<dyn Fn(&ChatRequest) -> Step + Send + Sync>;

/// A provider that replays scripted steps (or computes them from the request)
/// and records every request it receives.
pub struct FakeProvider {
    id: String,
    steps: Mutex<VecDeque<Step>>,
    handler: Option<Handler>,
    calls: AtomicUsize,
    requests: Mutex<Vec<ChatRequest>>,
    models: Vec<ModelInfo>,
}

impl FakeProvider {
    pub fn new(id: &str, steps: Vec<Step>) -> Arc<Self> {
        Arc::new(Self {
            id: id.to_owned(),
            steps: Mutex::new(steps.into()),
            handler: None,
            calls: AtomicUsize::new(0),
            requests: Mutex::new(Vec::new()),
            models: vec![ModelInfo { id: format!("{id}-model"), parameter_size: None }],
        })
    }

    /// A provider whose reply is computed from each request.
    pub fn with_handler(id: &str, handler: impl Fn(&ChatRequest) -> Step + Send + Sync + 'static) -> Arc<Self> {
        Arc::new(Self {
            id: id.to_owned(),
            steps: Mutex::new(VecDeque::new()),
            handler: Some(Box::new(handler)),
            calls: AtomicUsize::new(0),
            requests: Mutex::new(Vec::new()),
            models: vec![ModelInfo { id: format!("{id}-model"), parameter_size: None }],
        })
    }

    pub fn calls(&self) -> usize {
        self.calls.load(Ordering::SeqCst)
    }

    pub fn requests(&self) -> Vec<ChatRequest> {
        self.requests.lock().unwrap().clone()
    }

    fn next_step(&self, request: &ChatRequest) -> Step {
        self.calls.fetch_add(1, Ordering::SeqCst);
        self.requests.lock().unwrap().push(request.clone());
        if let Some(handler) = &self.handler {
            return handler(request);
        }
        self.steps
            .lock()
            .unwrap()
            .pop_front()
            .unwrap_or_else(|| Step::Error(LlmError::InvalidResponse("fake provider has no more steps".into())))
    }
}

#[async_trait]
impl LlmProvider for FakeProvider {
    fn id(&self) -> &str {
        &self.id
    }

    async fn list_models(&self) -> Result<Vec<ModelInfo>, LlmError> {
        Ok(self.models.clone())
    }

    async fn chat(&self, request: &ChatRequest) -> Result<ChatResponse, LlmError> {
        let (text, usage) = match self.next_step(request) {
            Step::Reply { text, usage } => (text, usage),
            Step::Stream(chunks) => (chunks.concat(), None),
            Step::StreamThenFail(_, e) | Step::Error(e) => return Err(e),
        };
        let usage = usage.unwrap_or_else(|| Usage::estimate(prompt_chars(request), text.len()));
        Ok(ChatResponse { content: text, usage, model: request.model.clone(), truncated: false })
    }

    async fn chat_stream(&self, request: &ChatRequest) -> Result<ChatStream, LlmError> {
        let model = Some(request.model.clone());
        let items: Vec<Result<ChatDelta, LlmError>> = match self.next_step(request) {
            Step::Reply { text, usage } => {
                vec![Ok(ChatDelta::Text(text)), Ok(ChatDelta::Done { usage, truncated: false, model })]
            }
            Step::Stream(chunks) => chunks
                .into_iter()
                .map(|c| Ok(ChatDelta::Text(c)))
                .chain([Ok(ChatDelta::Done { usage: None, truncated: false, model })])
                .collect(),
            Step::StreamThenFail(chunks, e) => {
                chunks.into_iter().map(|c| Ok(ChatDelta::Text(c))).chain([Err(e)]).collect()
            }
            Step::Error(e) => return Err(e),
        };
        Ok(Box::pin(stream::iter(items)))
    }
}

/// Collects router events for assertions.
#[derive(Default)]
pub struct RecordingObserver {
    events: Mutex<Vec<RouterEvent>>,
}

impl RecordingObserver {
    pub fn new() -> Arc<Self> {
        Arc::new(Self::default())
    }

    pub fn events(&self) -> Vec<RouterEvent> {
        self.events.lock().unwrap().clone()
    }
}

impl RouterObserver for RecordingObserver {
    fn on_event(&self, event: &RouterEvent) {
        self.events.lock().unwrap().push(event.clone());
    }
}
