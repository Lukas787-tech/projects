//! Anthropic Messages API (optional paid provider), over raw HTTP since there
//! is no official Rust SDK.
//!
//! Current models (Opus 5.5, Sonnet 5.5, Haiku 5.5) reject `temperature` and
//! explicit thinking settings, so neither is sent; thinking stays on its
//! adaptive default. On models that support it, server-side refusal fallback
//! (`fallbacks: "default"`) is requested so a safety decline is retried on the
//! model Anthropic recommends instead of simply stopping.

use std::time::Duration;

use async_trait::async_trait;
use futures::StreamExt;
use kickoff_core::Secret;
use kickoff_core::llm::{
    ChatDelta, ChatRequest, ChatStream, LlmError, LlmProvider, ModelInfo, OutputFormat, Role, Usage,
};
use serde_json::{Value, json};

use crate::{http, schema};

pub const DEFAULT_URL: &str = "https://api.anthropic.com";
const API_VERSION: &str = "2023-06-01";
const FALLBACK_BETA: &str = "server-side-fallback-2026-07-01";
/// Default output ceiling for streamed replies; thinking tokens count toward it.
const DEFAULT_MAX_TOKENS: u32 = 64_000;

pub struct Anthropic {
    id: String,
    base_url: String,
    key: Secret,
    client: reqwest::Client,
}

impl Anthropic {
    pub fn new(id: &str, base_url: &str, key: Secret) -> Self {
        Self {
            id: id.to_owned(),
            base_url: base_url.trim_end_matches('/').to_owned(),
            key,
            client: http::client(Duration::from_secs(300)),
        }
    }

    fn request(&self, method: reqwest::Method, path: &str) -> reqwest::RequestBuilder {
        self.client
            .request(method, format!("{}{path}", self.base_url))
            .header("x-api-key", self.key.expose())
            .header("anthropic-version", API_VERSION)
    }
}

/// Models that accept `fallbacks: "default"` on the Claude API. Claude Haiku
/// 5.5 has no server-side fallback.
fn supports_default_fallback(model: &str) -> bool {
    model.starts_with("claude-opus-5") || model.starts_with("claude-fable-5") || model == "claude-sonnet-5-5"
}

fn body(request: &ChatRequest) -> Value {
    let system: Vec<&str> =
        request.messages.iter().filter(|m| m.role == Role::System).map(|m| m.content.as_str()).collect();
    let messages: Vec<Value> = request
        .messages
        .iter()
        .filter(|m| m.role != Role::System)
        .map(|m| json!({ "role": m.role, "content": m.content }))
        .collect();
    let mut body = json!({
        "model": request.model,
        "max_tokens": request.max_tokens.unwrap_or(DEFAULT_MAX_TOKENS),
        "messages": messages,
        "stream": true,
    });
    if !system.is_empty() {
        body["system"] = json!(system.join("\n\n"));
    }
    if let OutputFormat::JsonSchema { schema, .. } = &request.format {
        body["output_config"] =
            json!({ "format": { "type": "json_schema", "schema": schema::for_native_mode(schema) } });
    }
    if supports_default_fallback(&request.model) {
        body["fallbacks"] = json!("default");
    }
    body
}

#[async_trait]
impl LlmProvider for Anthropic {
    fn id(&self) -> &str {
        &self.id
    }

    async fn list_models(&self) -> Result<Vec<ModelInfo>, LlmError> {
        let response =
            self.request(reqwest::Method::GET, "/v1/models?limit=100").send().await.map_err(http::transport_error)?;
        if !response.status().is_success() {
            return Err(http::error_from_response(response).await);
        }
        let body: Value = response.json().await.map_err(|e| LlmError::InvalidResponse(e.to_string()))?;
        Ok(body
            .get("data")
            .and_then(Value::as_array)
            .map(|items| {
                items
                    .iter()
                    .filter_map(|m| m.get("id").and_then(Value::as_str))
                    .map(|id| ModelInfo { id: id.to_owned(), parameter_size: None })
                    .collect()
            })
            .unwrap_or_default())
    }

    async fn chat_stream(&self, request: &ChatRequest) -> Result<ChatStream, LlmError> {
        let mut builder = self.request(reqwest::Method::POST, "/v1/messages").json(&body(request));
        if supports_default_fallback(&request.model) {
            builder = builder.header("anthropic-beta", FALLBACK_BETA);
        }
        let response = builder.send().await.map_err(http::transport_error)?;
        if !response.status().is_success() {
            return Err(http::error_from_response(response).await);
        }
        let events = Box::pin(http::sse_events(http::lines(response.bytes_stream())));
        let state = State {
            events,
            input_tokens: None,
            output_tokens: None,
            model: None,
            stop_reason: None,
            emitted: false,
            finished: false,
        };
        Ok(Box::pin(futures::stream::unfold(state, next_delta)))
    }
}

type SseStream = std::pin::Pin<Box<dyn futures::Stream<Item = Result<(Option<String>, String), LlmError>> + Send>>;

struct State {
    events: SseStream,
    input_tokens: Option<u32>,
    output_tokens: Option<u32>,
    model: Option<String>,
    stop_reason: Option<String>,
    emitted: bool,
    finished: bool,
}

impl State {
    fn finish(&mut self) -> Result<ChatDelta, LlmError> {
        self.finished = true;
        if self.stop_reason.as_deref() == Some("refusal") {
            // A decline: let the router try the next provider.
            return Err(LlmError::BadRequest("Claude declined this request (refusal)".into()));
        }
        let usage = self.input_tokens.map(|input| Usage {
            prompt_tokens: input,
            completion_tokens: self.output_tokens.unwrap_or(0),
            estimated: false,
        });
        Ok(ChatDelta::Done {
            usage,
            truncated: self.stop_reason.as_deref() == Some("max_tokens"),
            model: self.model.clone(),
        })
    }
}

fn tokens(value: Option<&Value>) -> Option<u32> {
    value.and_then(Value::as_u64).map(|v| u32::try_from(v).unwrap_or(u32::MAX))
}

async fn next_delta(mut st: State) -> Option<(Result<ChatDelta, LlmError>, State)> {
    if st.finished {
        return None;
    }
    loop {
        let data = match st.events.next().await {
            None => {
                let done = st.finish();
                return Some((done, st));
            }
            Some(Err(e)) => {
                st.finished = true;
                return Some((Err(e), st));
            }
            Some(Ok((_, data))) => data,
        };
        let event: Value = match serde_json::from_str(&data) {
            Ok(v) => v,
            Err(e) => {
                st.finished = true;
                return Some((Err(LlmError::InvalidResponse(format!("bad stream event: {e}"))), st));
            }
        };
        match event.get("type").and_then(Value::as_str).unwrap_or_default() {
            "message_start" => {
                st.input_tokens = tokens(event.pointer("/message/usage/input_tokens"));
                st.model = event.pointer("/message/model").and_then(Value::as_str).map(str::to_owned);
            }
            "content_block_delta" => {
                // Only visible text; thinking and other block types are skipped.
                if event.pointer("/delta/type").and_then(Value::as_str) == Some("text_delta")
                    && let Some(text) = event.pointer("/delta/text").and_then(Value::as_str).filter(|t| !t.is_empty())
                {
                    st.emitted = true;
                    return Some((Ok(ChatDelta::Text(text.to_owned())), st));
                }
            }
            "message_delta" => {
                if let Some(reason) = event.pointer("/delta/stop_reason").and_then(Value::as_str) {
                    st.stop_reason = Some(reason.to_owned());
                }
                if let Some(out) = tokens(event.pointer("/usage/output_tokens")) {
                    st.output_tokens = Some(out);
                }
                if let Some(input) = tokens(event.pointer("/usage/input_tokens")) {
                    st.input_tokens = Some(input);
                }
            }
            "message_stop" => {
                let done = st.finish();
                return Some((done, st));
            }
            "error" => {
                st.finished = true;
                let kind = event.pointer("/error/type").and_then(Value::as_str).unwrap_or("error");
                let message = event.pointer("/error/message").and_then(Value::as_str).unwrap_or(kind).to_owned();
                let error = match kind {
                    "overloaded_error" => LlmError::Server { status: 529, message },
                    "rate_limit_error" => LlmError::RateLimited {
                        retry_after: None,
                        scope: kickoff_core::llm::LimitScope::Unknown,
                        message,
                    },
                    _ => LlmError::Server { status: 500, message },
                };
                return Some((Err(error), st));
            }
            _ => {} // ping, content_block_start/stop, fallback blocks
        }
    }
}

#[cfg(test)]
mod tests {
    use kickoff_core::llm::ChatMessage;
    use wiremock::matchers::{body_partial_json, header, method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    use super::*;

    fn sse(events: &[(&str, Value)]) -> String {
        events.iter().map(|(name, data)| format!("event: {name}\ndata: {data}\n\n")).collect()
    }

    fn request(model: &str) -> ChatRequest {
        ChatRequest {
            model: model.into(),
            temperature: Some(0.7),
            ..ChatRequest::new(vec![ChatMessage::system("Be brief."), ChatMessage::user("Hi")])
        }
    }

    #[tokio::test]
    async fn streams_text_and_usage_with_required_headers() {
        let server = MockServer::start().await;
        let stream = sse(&[
            (
                "message_start",
                json!({"type":"message_start","message":{"id":"msg_1","model":"claude-opus-5-5","usage":{"input_tokens":12,"output_tokens":1}}}),
            ),
            (
                "content_block_start",
                json!({"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}),
            ),
            (
                "content_block_delta",
                json!({"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":""}}),
            ),
            (
                "content_block_start",
                json!({"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}),
            ),
            ("ping", json!({"type":"ping"})),
            (
                "content_block_delta",
                json!({"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Hel"}}),
            ),
            (
                "content_block_delta",
                json!({"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"lo"}}),
            ),
            (
                "message_delta",
                json!({"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":7}}),
            ),
            ("message_stop", json!({"type":"message_stop"})),
        ]);
        Mock::given(method("POST"))
            .and(path("/v1/messages"))
            .and(header("x-api-key", "sk-ant-test-0000000000000000"))
            .and(header("anthropic-version", "2023-06-01"))
            .and(header("anthropic-beta", FALLBACK_BETA))
            .and(body_partial_json(json!({"system": "Be brief.", "stream": true, "fallbacks": "default",
                                          "messages": [{"role": "user", "content": "Hi"}]})))
            .respond_with(ResponseTemplate::new(200).set_body_string(stream))
            .mount(&server)
            .await;
        let p = Anthropic::new("anthropic", &server.uri(), Secret::new("sk-ant-test-0000000000000000"));
        let r = p.chat(&request("claude-opus-5-5")).await.unwrap();
        assert_eq!(r.content, "Hello");
        assert_eq!(r.usage, Usage { prompt_tokens: 12, completion_tokens: 7, estimated: false });
        assert_eq!(r.model, "claude-opus-5-5");
    }

    #[test]
    fn body_omits_sampling_and_maps_structured_output() {
        let mut req = request("claude-haiku-5-5");
        req.format = OutputFormat::JsonSchema {
            name: "x".into(),
            schema: json!({"type": "object", "properties": {"a": {"type": "string", "minLength": 2}}}),
        };
        let b = body(&req);
        assert!(b.get("temperature").is_none(), "current models reject temperature");
        assert!(b.get("thinking").is_none());
        assert!(b.get("fallbacks").is_none(), "Haiku 5.5 has no server-side fallback");
        assert_eq!(
            b["output_config"],
            json!({"format": {"type": "json_schema", "schema":
                {"type": "object", "properties": {"a": {"type": "string"}}, "additionalProperties": false}}})
        );
        assert_eq!(b["max_tokens"], DEFAULT_MAX_TOKENS);
    }

    #[tokio::test]
    async fn refusal_becomes_an_error_for_fallback() {
        let server = MockServer::start().await;
        let stream = sse(&[
            (
                "message_start",
                json!({"type":"message_start","message":{"model":"claude-opus-5-5","usage":{"input_tokens":5}}}),
            ),
            (
                "message_delta",
                json!({"type":"message_delta","delta":{"stop_reason":"refusal"},"usage":{"output_tokens":0}}),
            ),
            ("message_stop", json!({"type":"message_stop"})),
        ]);
        Mock::given(method("POST"))
            .respond_with(ResponseTemplate::new(200).set_body_string(stream))
            .mount(&server)
            .await;
        let p = Anthropic::new("anthropic", &server.uri(), Secret::new("k"));
        assert!(
            matches!(p.chat(&request("claude-opus-5-5")).await, Err(LlmError::BadRequest(m)) if m.contains("declined"))
        );
    }

    #[tokio::test]
    async fn overloaded_and_rate_limited_responses() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .respond_with(ResponseTemplate::new(429).insert_header("retry-after", "20").set_body_json(
                json!({"type":"error","error":{"type":"rate_limit_error","message":"Number of request tokens has exceeded your per-minute rate limit"}}),
            ))
            .mount(&server)
            .await;
        let p = Anthropic::new("anthropic", &server.uri(), Secret::new("k"));
        let err = p.chat(&request("claude-sonnet-5-5")).await.unwrap_err();
        assert!(matches!(err, LlmError::RateLimited { retry_after: Some(d), .. } if d == Duration::from_secs(20)));

        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .respond_with(
                ResponseTemplate::new(529)
                    .set_body_json(json!({"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}})),
            )
            .mount(&server)
            .await;
        let p = Anthropic::new("anthropic", &server.uri(), Secret::new("k"));
        assert!(
            matches!(p.chat(&request("claude-sonnet-5-5")).await, Err(LlmError::Server { status: 529, ref message }) if message == "Overloaded")
        );
    }

    #[tokio::test]
    async fn lists_models() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/v1/models"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({"data": [
                {"id": "claude-opus-5-5", "display_name": "Claude Opus 5.5"},
                {"id": "claude-haiku-5-5", "display_name": "Claude Haiku 5.5"}
            ], "has_more": false})))
            .mount(&server)
            .await;
        let p = Anthropic::new("anthropic", &server.uri(), Secret::new("k"));
        let ids: Vec<_> = p.list_models().await.unwrap().into_iter().map(|m| m.id).collect();
        assert_eq!(ids, ["claude-opus-5-5", "claude-haiku-5-5"]);
    }
}
