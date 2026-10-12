//! Rate-limit resilience: throttling, retries with backoff, and fallback
//! across an ordered chain of (provider, model) routes.
//!
//! Policy per error:
//! - per-minute rate limit: wait `Retry-After` (or back off) and retry the same
//!   route, unless the wait is longer than `max_wait`;
//! - daily quota, bad key, missing model, provider down: move to the next route;
//! - network errors / 5xx: back off and retry, then move on;
//! - once text has been streamed to the caller, errors are not retried (the
//!   caller already showed partial output).

use std::collections::{HashMap, VecDeque};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use futures::StreamExt;
use serde::Serialize;
use tokio::time::Instant;

use super::{ChatDelta, ChatRequest, ChatResponse, LimitScope, LlmError, LlmProvider, Usage, prompt_chars};

/// One entry in a fallback chain.
#[derive(Clone)]
pub struct Route {
    pub provider: Arc<dyn LlmProvider>,
    pub model: String,
    /// Requests per minute to stay under (from the provider preset), if known.
    pub rpm: Option<u32>,
}

impl Route {
    pub fn provider_id(&self) -> &str {
        self.provider.id()
    }
}

impl std::fmt::Debug for Route {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}:{}", self.provider.id(), self.model)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum WaitReason {
    /// The provider answered 429.
    RateLimited,
    /// Holding back to stay under the provider's known per-minute limit.
    Throttled,
    /// Retrying after a network or server error.
    Retrying,
}

#[derive(Debug, Clone, PartialEq)]
pub enum RouterEvent {
    Waiting { provider_id: String, model: String, seconds: u64, reason: WaitReason },
    FellBack { from_provider: String, from_model: String, to_provider: String, to_model: String, reason: String },
    Completed { provider_id: String, model: String, usage: Usage },
    Failed { provider_id: String, model: String, error_kind: &'static str, message: String },
}

pub trait RouterObserver: Send + Sync {
    fn on_event(&self, event: &RouterEvent);
}

/// Observer that ignores everything.
pub struct NoopObserver;

impl RouterObserver for NoopObserver {
    fn on_event(&self, _: &RouterEvent) {}
}

#[derive(Debug, Clone)]
pub struct RetryPolicy {
    /// Attempts per route before moving on.
    pub max_attempts: u32,
    pub base_backoff: Duration,
    /// Longer waits than this skip to the next route instead.
    pub max_wait: Duration,
}

impl Default for RetryPolicy {
    fn default() -> Self {
        Self { max_attempts: 3, base_backoff: Duration::from_secs(2), max_wait: Duration::from_secs(60) }
    }
}

#[derive(Debug, Clone)]
pub struct RouteFailure {
    pub provider_id: String,
    pub model: String,
    pub error: LlmError,
}

#[derive(Debug, Clone, thiserror::Error)]
pub enum RouterError {
    #[error("no AI provider is configured for this task")]
    NoRoutes,
    #[error("{}", describe_failures(.0))]
    AllFailed(Vec<RouteFailure>),
    #[error("the reply from {provider_id} was interrupted: {error}")]
    Interrupted { provider_id: String, error: LlmError },
    #[error("cancelled")]
    Cancelled,
}

fn describe_failures(failures: &[RouteFailure]) -> String {
    let parts: Vec<String> = failures.iter().map(|f| format!("{} ({}): {}", f.provider_id, f.model, f.error)).collect();
    format!("every AI provider failed: {}", parts.join("; "))
}

/// The reply plus which route produced it.
#[derive(Debug, Clone)]
pub struct Answer {
    pub response: ChatResponse,
    pub route_index: usize,
    pub provider_id: String,
}

pub struct Router {
    policy: RetryPolicy,
    observer: Arc<dyn RouterObserver>,
    /// Request start times in the last minute, per provider.
    windows: Mutex<HashMap<String, VecDeque<Instant>>>,
}

enum Decision {
    Retry(Duration, WaitReason),
    FallBack,
    Abort,
}

enum Outcome {
    Done(ChatResponse),
    Failed(LlmError, bool /* text already streamed */),
}

impl Router {
    pub fn new(policy: RetryPolicy, observer: Arc<dyn RouterObserver>) -> Self {
        Self { policy, observer, windows: Mutex::new(HashMap::new()) }
    }

    /// Non-streaming chat across the chain.
    pub async fn chat(&self, routes: &[Route], request: &ChatRequest) -> Result<Answer, RouterError> {
        self.run(routes, request, None).await
    }

    /// Streaming chat: `on_text` receives text as it arrives. Retries and
    /// fallback only happen before the first piece of text.
    pub async fn chat_streaming(
        &self,
        routes: &[Route],
        request: &ChatRequest,
        on_text: &mut (dyn FnMut(&str) + Send),
    ) -> Result<Answer, RouterError> {
        self.run(routes, request, Some(on_text)).await
    }

    async fn run(
        &self,
        routes: &[Route],
        request: &ChatRequest,
        mut on_text: Option<&mut (dyn FnMut(&str) + Send)>,
    ) -> Result<Answer, RouterError> {
        if routes.is_empty() {
            return Err(RouterError::NoRoutes);
        }
        let mut failures = Vec::new();
        for (index, route) in routes.iter().enumerate() {
            let mut req = request.clone();
            req.model = route.model.clone();
            let mut attempt = 0;
            let last_error = loop {
                attempt += 1;
                self.throttle(route).await;
                let outcome = match on_text.as_deref_mut() {
                    Some(cb) => self.stream_once(route, &req, cb).await,
                    None => match route.provider.chat(&req).await {
                        Ok(r) => Outcome::Done(r),
                        Err(e) => Outcome::Failed(e, false),
                    },
                };
                match outcome {
                    Outcome::Done(response) => {
                        self.emit(RouterEvent::Completed {
                            provider_id: route.provider_id().to_owned(),
                            model: response.model.clone(),
                            usage: response.usage,
                        });
                        return Ok(Answer {
                            response,
                            route_index: index,
                            provider_id: route.provider_id().to_owned(),
                        });
                    }
                    Outcome::Failed(error, streamed) => {
                        self.emit(RouterEvent::Failed {
                            provider_id: route.provider_id().to_owned(),
                            model: route.model.clone(),
                            error_kind: error.kind(),
                            message: error.to_string(),
                        });
                        if streamed {
                            return Err(RouterError::Interrupted {
                                provider_id: route.provider_id().to_owned(),
                                error,
                            });
                        }
                        match self.decide(&error, attempt) {
                            Decision::Abort => return Err(RouterError::Cancelled),
                            Decision::FallBack => break error,
                            Decision::Retry(wait, reason) => {
                                self.emit(RouterEvent::Waiting {
                                    provider_id: route.provider_id().to_owned(),
                                    model: route.model.clone(),
                                    seconds: wait.as_secs_f64().ceil() as u64,
                                    reason,
                                });
                                tokio::time::sleep(wait).await;
                            }
                        }
                    }
                }
            };
            if let Some(next) = routes.get(index + 1) {
                self.emit(RouterEvent::FellBack {
                    from_provider: route.provider_id().to_owned(),
                    from_model: route.model.clone(),
                    to_provider: next.provider_id().to_owned(),
                    to_model: next.model.clone(),
                    reason: last_error.to_string(),
                });
            }
            failures.push(RouteFailure {
                provider_id: route.provider_id().to_owned(),
                model: route.model.clone(),
                error: last_error,
            });
        }
        Err(RouterError::AllFailed(failures))
    }

    async fn stream_once(&self, route: &Route, req: &ChatRequest, on_text: &mut (dyn FnMut(&str) + Send)) -> Outcome {
        let mut stream = match route.provider.chat_stream(req).await {
            Ok(s) => s,
            Err(e) => return Outcome::Failed(e, false),
        };
        let mut content = String::new();
        let (mut usage, mut truncated, mut model) = (None, false, None);
        while let Some(delta) = stream.next().await {
            match delta {
                Ok(ChatDelta::Text(t)) => {
                    if !t.is_empty() {
                        on_text(&t);
                        content.push_str(&t);
                    }
                }
                Ok(ChatDelta::Done { usage: u, truncated: t, model: m }) => {
                    usage = u;
                    truncated = t;
                    model = m;
                }
                Err(e) => return Outcome::Failed(e, !content.is_empty()),
            }
        }
        let usage = usage.unwrap_or_else(|| Usage::estimate(prompt_chars(req), content.len()));
        Outcome::Done(ChatResponse { content, usage, model: model.unwrap_or_else(|| req.model.clone()), truncated })
    }

    fn decide(&self, error: &LlmError, attempt: u32) -> Decision {
        let can_retry = attempt < self.policy.max_attempts;
        match error {
            LlmError::Cancelled => Decision::Abort,
            LlmError::RateLimited { scope: LimitScope::Day, .. } => Decision::FallBack,
            LlmError::RateLimited { retry_after, .. } => {
                let wait = retry_after.unwrap_or_else(|| self.backoff(attempt));
                if can_retry && wait <= self.policy.max_wait {
                    Decision::Retry(wait, WaitReason::RateLimited)
                } else {
                    Decision::FallBack
                }
            }
            LlmError::Network(_) | LlmError::Timeout | LlmError::Server { .. } if can_retry => {
                Decision::Retry(self.backoff(attempt), WaitReason::Retrying)
            }
            _ => Decision::FallBack,
        }
    }

    /// Exponential backoff with up to 50% jitter.
    fn backoff(&self, attempt: u32) -> Duration {
        let base = self.policy.base_backoff.saturating_mul(1 << attempt.saturating_sub(1).min(6));
        let jitter = base.mul_f64(fastrand::f64() * 0.5);
        (base + jitter).min(self.policy.max_wait)
    }

    /// Wait until another request fits in the provider's per-minute window.
    async fn throttle(&self, route: &Route) {
        let Some(rpm) = route.rpm.filter(|r| *r > 0) else { return };
        const WINDOW: Duration = Duration::from_secs(60);
        loop {
            let wait = {
                let mut windows = self.windows.lock().unwrap_or_else(|e| e.into_inner());
                let window = windows.entry(route.provider_id().to_owned()).or_default();
                let now = Instant::now();
                while window.front().is_some_and(|t| now.duration_since(*t) >= WINDOW) {
                    window.pop_front();
                }
                if window.len() < rpm as usize {
                    window.push_back(now);
                    return;
                }
                WINDOW - now.duration_since(*window.front().expect("window is full"))
            };
            if wait >= Duration::from_secs(1) {
                self.emit(RouterEvent::Waiting {
                    provider_id: route.provider_id().to_owned(),
                    model: route.model.clone(),
                    seconds: wait.as_secs_f64().ceil() as u64,
                    reason: WaitReason::Throttled,
                });
            }
            tokio::time::sleep(wait).await;
        }
    }

    /// Report an event that happened outside the router (e.g. a structured
    /// call moving on after invalid output).
    pub fn notify(&self, event: RouterEvent) {
        self.emit(event);
    }

    fn emit(&self, event: RouterEvent) {
        self.observer.on_event(&event);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::llm::{ChatMessage, LlmProvider};
    use crate::testing::{FakeProvider, RecordingObserver, Step};

    fn route(p: &Arc<FakeProvider>, rpm: Option<u32>) -> Route {
        Route { provider: p.clone(), model: format!("{}-model", p.id()), rpm }
    }

    fn request() -> ChatRequest {
        ChatRequest::new(vec![ChatMessage::user("hi")])
    }

    fn router(observer: &Arc<RecordingObserver>) -> Router {
        Router::new(RetryPolicy::default(), observer.clone())
    }

    #[tokio::test(start_paused = true)]
    async fn retries_after_retry_after_then_succeeds() {
        let p = FakeProvider::new("gemini", vec![Step::rate_limited(Some(7), LimitScope::Minute), Step::reply("ok")]);
        let obs = RecordingObserver::new();
        let start = Instant::now();
        let answer = router(&obs).chat(&[route(&p, None)], &request()).await.unwrap();
        assert_eq!(answer.response.content, "ok");
        assert_eq!(start.elapsed(), Duration::from_secs(7));
        assert!(
            obs.events()
                .iter()
                .any(|e| matches!(e, RouterEvent::Waiting { seconds: 7, reason: WaitReason::RateLimited, .. }))
        );
    }

    #[tokio::test(start_paused = true)]
    async fn daily_quota_falls_back_immediately() {
        let a = FakeProvider::new("gemini", vec![Step::rate_limited(None, LimitScope::Day)]);
        let b = FakeProvider::new("groq", vec![Step::reply("from groq")]);
        let obs = RecordingObserver::new();
        let start = Instant::now();
        let answer = router(&obs).chat(&[route(&a, None), route(&b, None)], &request()).await.unwrap();
        assert_eq!(answer.provider_id, "groq");
        assert_eq!(answer.route_index, 1);
        assert_eq!(start.elapsed(), Duration::ZERO);
        assert!(
            obs.events()
                .iter()
                .any(|e| matches!(e, RouterEvent::FellBack { to_provider, .. } if to_provider == "groq"))
        );
    }

    #[tokio::test(start_paused = true)]
    async fn long_retry_after_skips_to_next_route() {
        let a = FakeProvider::new("gemini", vec![Step::rate_limited(Some(600), LimitScope::Minute)]);
        let b = FakeProvider::new("ollama", vec![Step::reply("local")]);
        let obs = RecordingObserver::new();
        let answer = router(&obs).chat(&[route(&a, None), route(&b, None)], &request()).await.unwrap();
        assert_eq!(answer.provider_id, "ollama");
    }

    #[tokio::test(start_paused = true)]
    async fn auth_error_is_not_retried() {
        let a = FakeProvider::new("groq", vec![Step::error(LlmError::Auth("bad key".into())), Step::reply("never")]);
        let obs = RecordingObserver::new();
        let err = router(&obs).chat(&[route(&a, None)], &request()).await.unwrap_err();
        assert!(matches!(err, RouterError::AllFailed(ref f) if f.len() == 1));
        assert_eq!(a.calls(), 1);
        assert!(err.to_string().contains("bad key"));
    }

    #[tokio::test(start_paused = true)]
    async fn network_errors_back_off_then_give_up() {
        let a = FakeProvider::new(
            "groq",
            vec![
                Step::error(LlmError::Network("reset".into())),
                Step::error(LlmError::Network("reset".into())),
                Step::error(LlmError::Network("reset".into())),
            ],
        );
        let obs = RecordingObserver::new();
        let err = router(&obs).chat(&[route(&a, None)], &request()).await.unwrap_err();
        assert!(matches!(err, RouterError::AllFailed(_)));
        assert_eq!(a.calls(), 3);
    }

    #[tokio::test(start_paused = true)]
    async fn throttle_spaces_requests_over_the_rpm_limit() {
        let p = FakeProvider::new("gemini", (0..3).map(|i| Step::reply(&i.to_string())).collect());
        let obs = RecordingObserver::new();
        let r = router(&obs);
        let routes = [route(&p, Some(2))];
        let start = Instant::now();
        for _ in 0..3 {
            r.chat(&routes, &request()).await.unwrap();
        }
        // Third request has to wait for the first to leave the 60 s window.
        assert_eq!(start.elapsed(), Duration::from_secs(60));
        assert!(obs.events().iter().any(|e| matches!(e, RouterEvent::Waiting { reason: WaitReason::Throttled, .. })));
    }

    #[tokio::test(start_paused = true)]
    async fn streaming_delivers_text_and_falls_back_before_first_token() {
        let a = FakeProvider::new("gemini", vec![Step::rate_limited(None, LimitScope::Day)]);
        let b = FakeProvider::new("groq", vec![Step::stream(&["Hel", "lo"])]);
        let obs = RecordingObserver::new();
        let mut seen = String::new();
        let answer = router(&obs)
            .chat_streaming(&[route(&a, None), route(&b, None)], &request(), &mut |t| seen.push_str(t))
            .await
            .unwrap();
        assert_eq!(seen, "Hello");
        assert_eq!(answer.response.content, "Hello");
        assert!(answer.response.usage.estimated);
    }

    #[tokio::test(start_paused = true)]
    async fn mid_stream_failure_is_reported_not_retried() {
        let a = FakeProvider::new("groq", vec![Step::stream_then_fail(&["Hel"], LlmError::Network("eof".into()))]);
        let b = FakeProvider::new("ollama", vec![Step::reply("unused")]);
        let obs = RecordingObserver::new();
        let err = router(&obs)
            .chat_streaming(&[route(&a, None), route(&b, None)], &request(), &mut |_| {})
            .await
            .unwrap_err();
        assert!(matches!(err, RouterError::Interrupted { .. }));
        assert_eq!(b.calls(), 0);
    }

    #[tokio::test]
    async fn empty_chain_is_an_error() {
        let obs = RecordingObserver::new();
        assert!(matches!(router(&obs).chat(&[], &request()).await, Err(RouterError::NoRoutes)));
    }
}
