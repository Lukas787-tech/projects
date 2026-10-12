//! Local models through Ollama's native API (`/api/chat`, `/api/tags`).
//! No key, no limits; structured output via the `format` field.

use std::time::Duration;

use async_trait::async_trait;
use futures::StreamExt;
use kickoff_core::llm::{ChatDelta, ChatRequest, ChatStream, LlmError, LlmProvider, ModelInfo, OutputFormat, Usage};
use serde::Serialize;
use serde_json::{Value, json};

use crate::http;

pub const DEFAULT_URL: &str = "http://localhost:11434";

pub struct Ollama {
    id: String,
    base_url: String,
    client: reqwest::Client,
}

/// What [`Ollama::detect`] found.
#[derive(Debug, Clone, Serialize)]
pub struct OllamaStatus {
    pub version: String,
    pub models: Vec<ModelInfo>,
}

impl Ollama {
    pub fn new(id: &str, base_url: &str) -> Self {
        Self {
            id: id.to_owned(),
            base_url: base_url.trim_end_matches('/').to_owned(),
            // Local models can take a while to load before the first token.
            client: http::client(Duration::from_secs(300)),
        }
    }

    /// Check whether Ollama is running and list its models. `Unavailable`
    /// means it isn't installed or isn't running.
    pub async fn detect(&self) -> Result<OllamaStatus, LlmError> {
        let response = self
            .client
            .get(format!("{}/api/version", self.base_url))
            .timeout(Duration::from_secs(3))
            .send()
            .await
            .map_err(http::transport_error)?;
        if !response.status().is_success() {
            return Err(http::error_from_response(response).await);
        }
        let body: Value = response.json().await.map_err(|e| LlmError::InvalidResponse(e.to_string()))?;
        let version = body.get("version").and_then(Value::as_str).unwrap_or("unknown").to_owned();
        Ok(OllamaStatus { version, models: self.list_models().await? })
    }
}

/// Parse sizes like `"7.6B"` or `"270M"` into billions of parameters.
pub fn parameter_billions(size: &str) -> Option<f64> {
    let size = size.trim();
    let (number, unit) = size.split_at(size.find(|c: char| c.is_ascii_alphabetic())?);
    let value: f64 = number.parse().ok()?;
    match unit.to_ascii_uppercase().as_str() {
        "B" => Some(value),
        "M" => Some(value / 1000.0),
        "T" => Some(value * 1000.0),
        _ => None,
    }
}

#[async_trait]
impl LlmProvider for Ollama {
    fn id(&self) -> &str {
        &self.id
    }

    async fn list_models(&self) -> Result<Vec<ModelInfo>, LlmError> {
        let response =
            self.client.get(format!("{}/api/tags", self.base_url)).send().await.map_err(http::transport_error)?;
        if !response.status().is_success() {
            return Err(http::error_from_response(response).await);
        }
        let body: Value = response.json().await.map_err(|e| LlmError::InvalidResponse(e.to_string()))?;
        let models = body.get("models").and_then(Value::as_array).cloned().unwrap_or_default();
        Ok(models
            .iter()
            .filter_map(|m| {
                let id = m.get("name").or_else(|| m.get("model")).and_then(Value::as_str)?;
                let size = m.pointer("/details/parameter_size").and_then(Value::as_str).map(str::to_owned);
                Some(ModelInfo { id: id.to_owned(), parameter_size: size })
            })
            .collect())
    }

    async fn chat_stream(&self, request: &ChatRequest) -> Result<ChatStream, LlmError> {
        let messages: Vec<Value> =
            request.messages.iter().map(|m| json!({ "role": m.role, "content": m.content })).collect();
        let mut body = json!({ "model": request.model, "messages": messages, "stream": true });
        let mut options = serde_json::Map::new();
        if let Some(t) = request.temperature {
            options.insert("temperature".into(), json!(http::json_temperature(t)));
        }
        if let Some(n) = request.max_tokens {
            options.insert("num_predict".into(), json!(n));
        }
        if !options.is_empty() {
            body["options"] = Value::Object(options);
        }
        match &request.format {
            OutputFormat::Text => {}
            OutputFormat::Json => body["format"] = json!("json"),
            OutputFormat::JsonSchema { schema, .. } => body["format"] = schema.clone(),
        }

        let response = self
            .client
            .post(format!("{}/api/chat", self.base_url))
            .json(&body)
            .send()
            .await
            .map_err(http::transport_error)?;
        if !response.status().is_success() {
            return Err(http::error_from_response(response).await);
        }

        // NDJSON: one object per line; the last has `done: true` and counts.
        let stream = http::lines(response.bytes_stream()).filter_map(|line| async move {
            let line = match line {
                Ok(l) if l.trim().is_empty() => return None,
                Ok(l) => l,
                Err(e) => return Some(Err(e)),
            };
            let chunk: Value = match serde_json::from_str(&line) {
                Ok(c) => c,
                Err(e) => return Some(Err(LlmError::InvalidResponse(format!("bad stream line: {e}")))),
            };
            if let Some(error) = chunk.get("error").and_then(Value::as_str) {
                return Some(Err(LlmError::Server { status: 200, message: error.to_owned() }));
            }
            if chunk.get("done").and_then(Value::as_bool) == Some(true) {
                let count = |k: &str| chunk.get(k).and_then(Value::as_u64).map(|v| v as u32);
                let usage = count("prompt_eval_count").map(|p| Usage {
                    prompt_tokens: p,
                    completion_tokens: count("eval_count").unwrap_or(0),
                    estimated: false,
                });
                let truncated = chunk.get("done_reason").and_then(Value::as_str) == Some("length");
                let model = chunk.get("model").and_then(Value::as_str).map(str::to_owned);
                return Some(Ok(ChatDelta::Done { usage, truncated, model }));
            }
            // `message.thinking` (reasoning models) is deliberately ignored.
            chunk
                .pointer("/message/content")
                .and_then(Value::as_str)
                .filter(|t| !t.is_empty())
                .map(|t| Ok(ChatDelta::Text(t.to_owned())))
        });
        Ok(Box::pin(stream))
    }
}

#[cfg(test)]
mod tests {
    use kickoff_core::llm::ChatMessage;
    use wiremock::matchers::{body_partial_json, method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    use super::*;

    #[tokio::test]
    async fn detects_and_lists_models() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/api/version"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({"version": "0.12.3"})))
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/api/tags"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({"models": [
                {"name": "qwen3:8b", "model": "qwen3:8b", "details": {"parameter_size": "8.2B"}},
                {"name": "llama3.2:latest", "details": {"parameter_size": "3.2B"}}
            ]})))
            .mount(&server)
            .await;
        let status = Ollama::new("ollama", &server.uri()).detect().await.unwrap();
        assert_eq!(status.version, "0.12.3");
        assert_eq!(status.models[0].id, "qwen3:8b");
        assert_eq!(status.models[1].parameter_size.as_deref(), Some("3.2B"));
    }

    #[tokio::test]
    async fn not_running_is_unavailable() {
        let err = Ollama::new("ollama", "http://127.0.0.1:9").detect().await.unwrap_err();
        assert!(matches!(err, LlmError::Unavailable(_)));
    }

    #[tokio::test]
    async fn streams_ndjson_and_sends_schema_format() {
        let server = MockServer::start().await;
        let ndjson = concat!(
            "{\"model\":\"qwen3:8b\",\"message\":{\"role\":\"assistant\",\"content\":\"\",\"thinking\":\"hmm\"},\"done\":false}\n",
            "{\"model\":\"qwen3:8b\",\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"a\\\"\"},\"done\":false}\n",
            "{\"model\":\"qwen3:8b\",\"message\":{\"role\":\"assistant\",\"content\":\":1}\"},\"done\":false}\n",
            "{\"model\":\"qwen3:8b\",\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":20,\"eval_count\":6}\n",
        );
        Mock::given(method("POST"))
            .and(path("/api/chat"))
            .and(body_partial_json(json!({"format": {"type": "object"}, "options": {"temperature": 0.2}})))
            .respond_with(ResponseTemplate::new(200).set_body_string(ndjson))
            .mount(&server)
            .await;
        let req = ChatRequest {
            model: "qwen3:8b".into(),
            temperature: Some(0.2),
            format: OutputFormat::JsonSchema { name: "x".into(), schema: json!({"type": "object"}) },
            ..ChatRequest::new(vec![ChatMessage::user("json please")])
        };
        let r = Ollama::new("ollama", &server.uri()).chat(&req).await.unwrap();
        assert_eq!(r.content, "{\"a\":1}");
        assert_eq!(r.usage, Usage { prompt_tokens: 20, completion_tokens: 6, estimated: false });
    }

    #[tokio::test]
    async fn missing_model_is_model_not_found() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .respond_with(
                ResponseTemplate::new(404)
                    .set_body_json(json!({"error": "model \"nope\" not found, try pulling it first"})),
            )
            .mount(&server)
            .await;
        let req = ChatRequest { model: "nope".into(), ..ChatRequest::new(vec![ChatMessage::user("hi")]) };
        let err = Ollama::new("ollama", &server.uri()).chat(&req).await.unwrap_err();
        assert!(matches!(err, LlmError::ModelNotFound(ref m) if m.contains("not found")));
    }

    #[test]
    fn parses_parameter_sizes() {
        assert_eq!(parameter_billions("7.6B"), Some(7.6));
        assert_eq!(parameter_billions("270M"), Some(0.27));
        assert_eq!(parameter_billions("big"), None);
    }
}
