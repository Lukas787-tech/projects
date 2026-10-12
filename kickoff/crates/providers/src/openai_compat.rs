//! One client for every OpenAI-compatible chat API (Gemini's compatibility
//! endpoint, Groq, OpenRouter, Mistral, Cerebras, LM Studio …).

use std::collections::BTreeMap;
use std::time::Duration;

use async_trait::async_trait;
use futures::StreamExt;
use kickoff_core::Secret;
use kickoff_core::llm::{
    ChatDelta, ChatRequest, ChatResponse, ChatStream, LlmError, LlmProvider, ModelInfo, OutputFormat, Usage,
    prompt_chars,
};
use serde_json::{Value, json};

use crate::presets::JsonMode;
use crate::{http, schema};

pub struct OpenAiCompat {
    id: String,
    base_url: String,
    key: Option<Secret>,
    json_mode: JsonMode,
    extra_headers: BTreeMap<String, String>,
    client: reqwest::Client,
}

impl OpenAiCompat {
    pub fn new(
        id: &str,
        base_url: &str,
        key: Option<Secret>,
        json_mode: JsonMode,
        extra_headers: BTreeMap<String, String>,
    ) -> Self {
        Self {
            id: id.to_owned(),
            base_url: base_url.trim_end_matches('/').to_owned(),
            key,
            json_mode,
            extra_headers,
            client: http::client(Duration::from_secs(90)),
        }
    }

    fn request(&self, method: reqwest::Method, path: &str) -> reqwest::RequestBuilder {
        let mut builder = self.client.request(method, format!("{}{path}", self.base_url));
        if let Some(key) = &self.key {
            builder = builder.bearer_auth(key.expose());
        }
        for (name, value) in &self.extra_headers {
            builder = builder.header(name, value);
        }
        builder
    }

    fn body(&self, request: &ChatRequest, stream: bool) -> Value {
        let messages: Vec<Value> =
            request.messages.iter().map(|m| json!({ "role": m.role, "content": m.content })).collect();
        let mut body = json!({ "model": request.model, "messages": messages, "stream": stream });
        if let Some(t) = request.temperature {
            body["temperature"] = json!(http::json_temperature(t));
        }
        if let Some(n) = request.max_tokens {
            body["max_tokens"] = json!(n);
        }
        if let Some(format) = self.response_format(&request.format) {
            body["response_format"] = format;
        }
        body
    }

    /// Map the requested format onto what this provider supports.
    fn response_format(&self, format: &OutputFormat) -> Option<Value> {
        match (format, self.json_mode) {
            (OutputFormat::Text, _) | (_, JsonMode::None) => None,
            (OutputFormat::JsonSchema { name, schema }, JsonMode::JsonSchema) => {
                let schema = schema::for_native_mode(schema);
                Some(json!({ "type": "json_schema", "json_schema": { "name": name, "schema": schema } }))
            }
            _ => Some(json!({ "type": "json_object" })),
        }
    }

    async fn send(&self, request: &ChatRequest, stream: bool) -> Result<reqwest::Response, LlmError> {
        let response = self
            .request(reqwest::Method::POST, "/chat/completions")
            .json(&self.body(request, stream))
            .send()
            .await
            .map_err(http::transport_error)?;
        if response.status().is_success() { Ok(response) } else { Err(http::error_from_response(response).await) }
    }
}

/// Token usage from a response or final stream chunk (`usage`, or Groq's `x_groq.usage`).
fn parse_usage(value: &Value) -> Option<Usage> {
    let usage = value.get("usage").filter(|u| !u.is_null()).or_else(|| value.pointer("/x_groq/usage"))?;
    let n = |k: &str| usage.get(k).and_then(Value::as_u64).map(|v| u32::try_from(v).unwrap_or(u32::MAX));
    Some(Usage {
        prompt_tokens: n("prompt_tokens")?,
        completion_tokens: n("completion_tokens").unwrap_or(0),
        estimated: false,
    })
}

#[async_trait]
impl LlmProvider for OpenAiCompat {
    fn id(&self) -> &str {
        &self.id
    }

    async fn list_models(&self) -> Result<Vec<ModelInfo>, LlmError> {
        let response = self.request(reqwest::Method::GET, "/models").send().await.map_err(http::transport_error)?;
        if !response.status().is_success() {
            return Err(http::error_from_response(response).await);
        }
        let body: Value = response.json().await.map_err(|e| LlmError::InvalidResponse(e.to_string()))?;
        let items = body
            .get("data")
            .or_else(|| body.get("models"))
            .and_then(Value::as_array)
            .ok_or_else(|| LlmError::InvalidResponse("model list has no `data` array".into()))?;
        Ok(items
            .iter()
            .filter_map(|m| m.get("id").or_else(|| m.get("name")).and_then(Value::as_str))
            // Gemini lists models as `models/<id>` but accepts the bare id.
            .map(|id| ModelInfo { id: id.strip_prefix("models/").unwrap_or(id).to_owned(), parameter_size: None })
            .collect())
    }

    async fn chat(&self, request: &ChatRequest) -> Result<ChatResponse, LlmError> {
        let response = self.send(request, false).await?;
        let body: Value = response.json().await.map_err(|e| LlmError::InvalidResponse(e.to_string()))?;
        let choice = body.pointer("/choices/0").ok_or_else(|| {
            LlmError::InvalidResponse(format!("no choices in response: {}", http::error_message(&body.to_string())))
        })?;
        let content = choice.pointer("/message/content").and_then(Value::as_str).unwrap_or_default().to_owned();
        let truncated = choice.get("finish_reason").and_then(Value::as_str) == Some("length");
        let usage = parse_usage(&body).unwrap_or_else(|| Usage::estimate(prompt_chars(request), content.len()));
        let model = body.get("model").and_then(Value::as_str).unwrap_or(&request.model).to_owned();
        Ok(ChatResponse { content, usage, model, truncated })
    }

    async fn chat_stream(&self, request: &ChatRequest) -> Result<ChatStream, LlmError> {
        let response = self.send(request, true).await?;
        let events = Box::pin(http::sse_events(http::lines(response.bytes_stream())));
        let state = StreamState { events, usage: None, truncated: false, model: None, finished: false };
        Ok(Box::pin(futures::stream::unfold(state, next_delta)))
    }
}

type SseStream = std::pin::Pin<Box<dyn futures::Stream<Item = Result<(Option<String>, String), LlmError>> + Send>>;

struct StreamState {
    events: SseStream,
    usage: Option<Usage>,
    truncated: bool,
    model: Option<String>,
    finished: bool,
}

impl StreamState {
    fn done(&mut self) -> ChatDelta {
        self.finished = true;
        ChatDelta::Done { usage: self.usage, truncated: self.truncated, model: self.model.clone() }
    }
}

/// Next text delta from the SSE stream. Always ends with one `Done`, even if
/// the provider closes the stream without `[DONE]`.
async fn next_delta(mut st: StreamState) -> Option<(Result<ChatDelta, LlmError>, StreamState)> {
    if st.finished {
        return None;
    }
    loop {
        let data = match st.events.next().await {
            None => return Some((Ok(st.done()), st)),
            Some(Err(e)) => {
                st.finished = true;
                return Some((Err(e), st));
            }
            Some(Ok((_, data))) => data,
        };
        if data.trim() == "[DONE]" {
            return Some((Ok(st.done()), st));
        }
        let chunk: Value = match serde_json::from_str(&data) {
            Ok(c) => c,
            Err(e) => {
                st.finished = true;
                return Some((Err(LlmError::InvalidResponse(format!("bad stream chunk: {e}"))), st));
            }
        };
        if chunk.get("error").is_some() {
            st.finished = true;
            return Some((Err(LlmError::Server { status: 200, message: http::error_message(&data) }), st));
        }
        if let Some(u) = parse_usage(&chunk) {
            st.usage = Some(u);
        }
        if let Some(m) = chunk.get("model").and_then(Value::as_str) {
            st.model = Some(m.to_owned());
        }
        if chunk.pointer("/choices/0/finish_reason").and_then(Value::as_str) == Some("length") {
            st.truncated = true;
        }
        if let Some(text) = chunk.pointer("/choices/0/delta/content").and_then(Value::as_str).filter(|t| !t.is_empty())
        {
            return Some((Ok(ChatDelta::Text(text.to_owned())), st));
        }
    }
}

#[cfg(test)]
mod tests {
    use kickoff_core::llm::{ChatMessage, LimitScope};
    use wiremock::matchers::{body_partial_json, header, method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    use super::*;

    fn provider(server: &MockServer, json_mode: JsonMode) -> OpenAiCompat {
        OpenAiCompat::new(
            "groq",
            &server.uri(),
            Some(Secret::new("gsk_test_key_123456789012")),
            json_mode,
            BTreeMap::new(),
        )
    }

    fn request() -> ChatRequest {
        ChatRequest { model: "openai/gpt-oss-120b".into(), ..ChatRequest::new(vec![ChatMessage::user("Say OK")]) }
    }

    #[tokio::test]
    async fn non_streaming_chat() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .and(path("/chat/completions"))
            .and(header("authorization", "Bearer gsk_test_key_123456789012"))
            .and(body_partial_json(json!({"model": "openai/gpt-oss-120b", "stream": false})))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "id": "x", "model": "openai/gpt-oss-120b",
                "choices": [{"index": 0, "message": {"role": "assistant", "content": "OK"}, "finish_reason": "stop"}],
                "usage": {"prompt_tokens": 9, "completion_tokens": 1, "total_tokens": 10}
            })))
            .mount(&server)
            .await;
        let r = provider(&server, JsonMode::JsonObject).chat(&request()).await.unwrap();
        assert_eq!(r.content, "OK");
        assert_eq!(r.usage, Usage { prompt_tokens: 9, completion_tokens: 1, estimated: false });
        assert!(!r.truncated);
    }

    #[tokio::test]
    async fn streaming_chat_with_groq_usage() {
        let server = MockServer::start().await;
        let sse = concat!(
            "data: {\"model\":\"m\",\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}\n\n",
            "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n",
            "data: {\"choices\":[{\"delta\":{\"reasoning\":\"thinking...\"}}]}\n\n",
            "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"},\"finish_reason\":\"stop\"}],\"x_groq\":{\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2}}}\n\n",
            "data: [DONE]\n\n",
        );
        Mock::given(method("POST"))
            .and(path("/chat/completions"))
            .and(body_partial_json(json!({"stream": true})))
            .respond_with(
                ResponseTemplate::new(200).insert_header("content-type", "text/event-stream").set_body_string(sse),
            )
            .mount(&server)
            .await;
        let mut stream = provider(&server, JsonMode::JsonObject).chat_stream(&request()).await.unwrap();
        let mut deltas = Vec::new();
        while let Some(d) = stream.next().await {
            deltas.push(d.unwrap());
        }
        assert_eq!(
            deltas,
            vec![
                ChatDelta::Text("Hel".into()),
                ChatDelta::Text("lo".into()),
                ChatDelta::Done {
                    usage: Some(Usage { prompt_tokens: 5, completion_tokens: 2, estimated: false }),
                    truncated: false,
                    model: Some("m".into())
                },
            ]
        );
    }

    #[tokio::test]
    async fn json_schema_mode_is_sent_when_supported() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .and(body_partial_json(
                json!({"response_format": {"type": "json_schema", "json_schema": {"name": "pitch"}}}),
            ))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "choices": [{"message": {"content": "{}"}, "finish_reason": "stop"}]
            })))
            .mount(&server)
            .await;
        let mut req = request();
        req.format = OutputFormat::JsonSchema { name: "pitch".into(), schema: json!({"type": "object"}) };
        let r = provider(&server, JsonMode::JsonSchema).chat(&req).await.unwrap();
        assert_eq!(r.content, "{}");
        assert!(r.usage.estimated);
    }

    #[test]
    fn json_object_fallback_and_none() {
        let p = OpenAiCompat::new("x", "http://x", None, JsonMode::JsonObject, BTreeMap::new());
        let schema = OutputFormat::JsonSchema { name: "a".into(), schema: json!({}) };
        assert_eq!(p.response_format(&schema), Some(json!({"type": "json_object"})));
        assert_eq!(p.response_format(&OutputFormat::Text), None);
        let none = OpenAiCompat::new("x", "http://x", None, JsonMode::None, BTreeMap::new());
        assert_eq!(none.response_format(&schema), None);
    }

    #[tokio::test]
    async fn rate_limit_is_classified() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .respond_with(ResponseTemplate::new(429).insert_header("retry-after", "3").set_body_json(json!({
                "error": {"message": "Rate limit reached ... requests per minute (RPM)"}
            })))
            .mount(&server)
            .await;
        let err = provider(&server, JsonMode::JsonObject).chat_stream(&request()).await.err().unwrap();
        assert!(
            matches!(err, LlmError::RateLimited { retry_after: Some(d), scope: LimitScope::Minute, .. } if d == Duration::from_secs(3))
        );
    }

    #[tokio::test]
    async fn lists_models_and_strips_gemini_prefix() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/models"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "object": "list",
                "data": [{"id": "models/gemini-2.5-flash", "object": "model"}, {"id": "openai/gpt-oss-120b"}]
            })))
            .mount(&server)
            .await;
        let models = provider(&server, JsonMode::JsonSchema).list_models().await.unwrap();
        let ids: Vec<_> = models.iter().map(|m| m.id.as_str()).collect();
        assert_eq!(ids, ["gemini-2.5-flash", "openai/gpt-oss-120b"]);
    }

    #[tokio::test]
    async fn unreachable_server_is_unavailable() {
        let p = OpenAiCompat::new("x", "http://127.0.0.1:9", None, JsonMode::None, BTreeMap::new());
        assert!(matches!(p.list_models().await, Err(LlmError::Unavailable(_))));
    }
}
