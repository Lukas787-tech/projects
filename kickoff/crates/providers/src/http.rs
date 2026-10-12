//! Shared HTTP plumbing: client setup, error classification, SSE decoding.

use std::time::{Duration, SystemTime, UNIX_EPOCH};

use futures::{Stream, StreamExt};
use kickoff_core::llm::{LimitScope, LlmError};
use reqwest::header::HeaderMap;
use serde_json::Value;

/// Longest error body text kept in an error message.
const MAX_ERROR_CHARS: usize = 400;

/// HTTP client used by every provider. Streams can run long, so there is no
/// overall timeout, only connect and per-read timeouts.
pub fn client(read_timeout: Duration) -> reqwest::Client {
    reqwest::Client::builder()
        .user_agent(kickoff_core::branding::user_agent())
        .connect_timeout(Duration::from_secs(10))
        .read_timeout(read_timeout)
        .build()
        .expect("HTTP client configuration is valid")
}

/// `f32` → JSON without float noise (0.2, not 0.20000000298023224).
pub fn json_temperature(t: f32) -> f64 {
    (f64::from(t) * 1000.0).round() / 1000.0
}

pub fn transport_error(e: reqwest::Error) -> LlmError {
    if e.is_timeout() {
        LlmError::Timeout
    } else if e.is_connect() {
        LlmError::Unavailable(format!("could not connect: {e}"))
    } else {
        LlmError::Network(e.to_string())
    }
}

/// Turn a non-success response into an [`LlmError`].
pub async fn error_from_response(response: reqwest::Response) -> LlmError {
    let status = response.status().as_u16();
    let headers = response.headers().clone();
    let body = response.text().await.unwrap_or_default();
    classify(status, &headers, &body)
}

pub fn classify(status: u16, headers: &HeaderMap, body: &str) -> LlmError {
    let message = error_message(body);
    let lower = body.to_lowercase();
    match status {
        401 | 403 => LlmError::Auth(message),
        // Gemini reports a bad key as 400 INVALID_ARGUMENT.
        400 if lower.contains("api key not valid") || lower.contains("invalid api key") => LlmError::Auth(message),
        404 => LlmError::ModelNotFound(message),
        // Out of credits (OpenRouter): waiting won't help today.
        402 => LlmError::RateLimited { retry_after: None, scope: LimitScope::Day, message },
        429 => LlmError::RateLimited { retry_after: retry_after(headers, body), scope: limit_scope(&lower), message },
        400 | 409 | 413 | 422 => LlmError::BadRequest(message),
        408 => LlmError::Timeout,
        _ => LlmError::Server { status, message },
    }
}

/// Daily quotas need a different provider, per-minute ones only a short wait.
fn limit_scope(lower_body: &str) -> LimitScope {
    const DAY: [&str; 7] = ["per day", "per-day", "perday", "daily", "(rpd)", "(tpd)", "requests per day"];
    const MINUTE: [&str; 5] = ["per minute", "per-minute", "perminute", "(rpm)", "(tpm)"];
    if DAY.iter().any(|m| lower_body.contains(m)) {
        LimitScope::Day
    } else if MINUTE.iter().any(|m| lower_body.contains(m)) {
        LimitScope::Minute
    } else {
        LimitScope::Unknown
    }
}

/// How long the provider asked us to wait, from (in order) `Retry-After`,
/// Groq's `x-ratelimit-reset-*`, Gemini's `RetryInfo.retryDelay`, or
/// OpenRouter's `X-RateLimit-Reset` (epoch ms) in the error metadata.
fn retry_after(headers: &HeaderMap, body: &str) -> Option<Duration> {
    let header = |name: &str| headers.get(name).and_then(|v| v.to_str().ok());
    if let Some(secs) = header("retry-after").and_then(|v| v.trim().parse::<f64>().ok()) {
        return duration_secs(secs);
    }
    let resets: Vec<Duration> = ["x-ratelimit-reset-requests", "x-ratelimit-reset-tokens"]
        .iter()
        .filter_map(|h| header(h).and_then(parse_go_duration))
        .collect();
    if let Some(max) = resets.into_iter().max() {
        return Some(max);
    }
    let json: Value = serde_json::from_str(body).ok()?;
    if let Some(delay) = find_string(&json, "retryDelay").and_then(parse_go_duration) {
        return Some(delay);
    }
    let reset_ms = find_string(&json, "X-RateLimit-Reset").and_then(|s| s.parse::<u64>().ok())?;
    let now_ms = SystemTime::now().duration_since(UNIX_EPOCH).ok()?.as_millis() as u64;
    Some(Duration::from_millis(reset_ms.saturating_sub(now_ms)))
}

fn duration_secs(secs: f64) -> Option<Duration> {
    (secs.is_finite() && secs >= 0.0).then(|| Duration::from_secs_f64(secs))
}

/// Parse durations like `37s`, `7.66s`, `2m59.56s`, `1h2m`, `250ms`.
pub fn parse_go_duration(text: &str) -> Option<Duration> {
    let text = text.trim();
    if text.is_empty() {
        return None;
    }
    if let Ok(secs) = text.parse::<f64>() {
        return duration_secs(secs);
    }
    let mut total = 0.0;
    let mut number = String::new();
    let mut chars = text.chars().peekable();
    while let Some(c) = chars.next() {
        if c.is_ascii_digit() || c == '.' {
            number.push(c);
            continue;
        }
        let value: f64 = number.parse().ok()?;
        number.clear();
        let unit = match c {
            'h' => 3600.0,
            'm' if chars.peek() == Some(&'s') => {
                chars.next();
                0.001
            }
            'm' => 60.0,
            's' => 1.0,
            _ => return None,
        };
        total += value * unit;
    }
    if !number.is_empty() {
        return None;
    }
    duration_secs(total)
}

/// Depth-first search for a string field anywhere in a JSON value.
fn find_string<'a>(value: &'a Value, key: &str) -> Option<&'a str> {
    match value {
        Value::Object(map) => {
            map.get(key).and_then(Value::as_str).or_else(|| map.values().find_map(|v| find_string(v, key)))
        }
        Value::Array(items) => items.iter().find_map(|v| find_string(v, key)),
        _ => None,
    }
}

/// The human-readable part of an error body (`error.message`, `message`, or
/// the raw text), shortened.
pub fn error_message(body: &str) -> String {
    let from_json = serde_json::from_str::<Value>(body).ok().and_then(|json| {
        let root = if let Value::Array(items) = &json { items.first().cloned()? } else { json };
        root.pointer("/error/message")
            .or_else(|| root.get("message"))
            .or_else(|| root.get("error"))
            .and_then(Value::as_str)
            .map(str::to_owned)
    });
    let text = from_json.unwrap_or_else(|| body.trim().to_owned());
    let text = if text.is_empty() { "no details".to_owned() } else { text };
    match text.char_indices().nth(MAX_ERROR_CHARS) {
        Some((i, _)) => format!("{}…", &text[..i]),
        None => text,
    }
}

/// Split a byte stream into lines (`\n`, with a trailing `\r` removed).
pub fn lines(
    bytes: impl Stream<Item = Result<impl AsRef<[u8]>, reqwest::Error>> + Send + 'static,
) -> impl Stream<Item = Result<String, LlmError>> + Send + 'static {
    let mut buffer: Vec<u8> = Vec::new();
    let mut finished = false;
    let mut source = Box::pin(bytes);
    futures::stream::poll_fn(move |cx| {
        loop {
            if let Some(pos) = buffer.iter().position(|b| *b == b'\n') {
                let mut line: Vec<u8> = buffer.drain(..=pos).collect();
                line.pop();
                if line.last() == Some(&b'\r') {
                    line.pop();
                }
                return std::task::Poll::Ready(Some(Ok(String::from_utf8_lossy(&line).into_owned())));
            }
            if finished {
                if buffer.is_empty() {
                    return std::task::Poll::Ready(None);
                }
                let rest = std::mem::take(&mut buffer);
                return std::task::Poll::Ready(Some(Ok(String::from_utf8_lossy(&rest).into_owned())));
            }
            match source.poll_next_unpin(cx) {
                std::task::Poll::Ready(Some(Ok(chunk))) => buffer.extend_from_slice(chunk.as_ref()),
                std::task::Poll::Ready(Some(Err(e))) => return std::task::Poll::Ready(Some(Err(transport_error(e)))),
                std::task::Poll::Ready(None) => finished = true,
                std::task::Poll::Pending => return std::task::Poll::Pending,
            }
        }
    })
}

/// Server-sent event payloads: the `data:` field of each event (multi-line
/// data joined with `\n`), plus the `event:` name if any.
pub fn sse_events(
    lines: impl Stream<Item = Result<String, LlmError>> + Send + 'static,
) -> impl Stream<Item = Result<(Option<String>, String), LlmError>> + Send + 'static {
    let mut lines = Box::pin(lines);
    let mut event: Option<String> = None;
    let mut data: Vec<String> = Vec::new();
    futures::stream::poll_fn(move |cx| {
        loop {
            match lines.poll_next_unpin(cx) {
                std::task::Poll::Ready(Some(Ok(line))) => {
                    if line.is_empty() {
                        if !data.is_empty() {
                            let payload = data.join("\n");
                            data.clear();
                            return std::task::Poll::Ready(Some(Ok((event.take(), payload))));
                        }
                        event = None;
                    } else if let Some(v) = line.strip_prefix("data:") {
                        data.push(v.strip_prefix(' ').unwrap_or(v).to_owned());
                    } else if let Some(v) = line.strip_prefix("event:") {
                        event = Some(v.trim().to_owned());
                    }
                    // `id:`, `retry:` and `:` comments are ignored.
                }
                std::task::Poll::Ready(Some(Err(e))) => return std::task::Poll::Ready(Some(Err(e))),
                std::task::Poll::Ready(None) => {
                    if data.is_empty() {
                        return std::task::Poll::Ready(None);
                    }
                    let payload = data.join("\n");
                    data.clear();
                    return std::task::Poll::Ready(Some(Ok((event.take(), payload))));
                }
                std::task::Poll::Pending => return std::task::Poll::Pending,
            }
        }
    })
}

#[cfg(test)]
mod tests {
    use reqwest::header::{HeaderName, HeaderValue};

    use super::*;

    fn headers(pairs: &[(&str, &str)]) -> HeaderMap {
        let mut h = HeaderMap::new();
        for (k, v) in pairs {
            h.insert(HeaderName::from_bytes(k.as_bytes()).unwrap(), HeaderValue::from_str(v).unwrap());
        }
        h
    }

    #[test]
    fn go_durations() {
        assert_eq!(parse_go_duration("37s"), Some(Duration::from_secs(37)));
        assert_eq!(parse_go_duration("2m59.5s"), Some(Duration::from_secs_f64(179.5)));
        assert_eq!(parse_go_duration("250ms"), Some(Duration::from_millis(250)));
        assert_eq!(parse_go_duration("1h"), Some(Duration::from_secs(3600)));
        assert_eq!(parse_go_duration("12"), Some(Duration::from_secs(12)));
        assert_eq!(parse_go_duration("soon"), None);
    }

    #[test]
    fn classifies_retry_after_header() {
        let e = classify(
            429,
            &headers(&[("retry-after", "12")]),
            r#"{"error":{"message":"Rate limit reached for requests per minute (RPM)"}}"#,
        );
        assert!(
            matches!(e, LlmError::RateLimited { retry_after: Some(d), scope: LimitScope::Minute, .. } if d == Duration::from_secs(12))
        );
    }

    #[test]
    fn classifies_groq_daily_token_limit() {
        let body = r#"{"error":{"message":"Rate limit reached for model `openai/gpt-oss-120b` on tokens per day (TPD): Limit 200000, Used 199990","type":"tokens","code":"rate_limit_exceeded"}}"#;
        let e = classify(429, &headers(&[("retry-after", "3600")]), body);
        assert!(matches!(e, LlmError::RateLimited { scope: LimitScope::Day, .. }));
    }

    #[test]
    fn classifies_gemini_retry_info() {
        let body = r#"[{"error":{"code":429,"message":"You exceeded your current quota.","status":"RESOURCE_EXHAUSTED","details":[{"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaId":"GenerateRequestsPerMinutePerProjectPerModel-FreeTier"}]},{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"37s"}]}}]"#;
        let e = classify(429, &HeaderMap::new(), body);
        assert!(matches!(e, LlmError::RateLimited { retry_after: Some(d), ref message, .. }
            if d == Duration::from_secs(37) && message == "You exceeded your current quota."));
    }

    #[test]
    fn classifies_gemini_daily_quota() {
        let body = r#"{"error":{"code":429,"message":"Quota exceeded","details":[{"violations":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]}]}}"#;
        assert!(matches!(classify(429, &HeaderMap::new(), body), LlmError::RateLimited { scope: LimitScope::Day, .. }));
    }

    #[test]
    fn classifies_openrouter_free_daily_limit() {
        let body = r#"{"error":{"message":"Rate limit exceeded: free-models-per-day. Add 10 credits to unlock 1000 free model requests per day","code":429}}"#;
        assert!(matches!(classify(429, &HeaderMap::new(), body), LlmError::RateLimited { scope: LimitScope::Day, .. }));
    }

    #[test]
    fn classifies_auth_and_bad_keys() {
        assert!(matches!(classify(401, &HeaderMap::new(), "{}"), LlmError::Auth(_)));
        let gemini = r#"[{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}]"#;
        assert!(matches!(classify(400, &HeaderMap::new(), gemini), LlmError::Auth(_)));
        assert!(matches!(classify(400, &HeaderMap::new(), "bad field"), LlmError::BadRequest(_)));
        assert!(matches!(
            classify(402, &HeaderMap::new(), "no credits"),
            LlmError::RateLimited { scope: LimitScope::Day, .. }
        ));
        assert!(matches!(classify(503, &HeaderMap::new(), ""), LlmError::Server { status: 503, .. }));
    }

    #[test]
    fn error_message_is_shortened() {
        let long = "x".repeat(1000);
        assert_eq!(error_message(&long).chars().count(), MAX_ERROR_CHARS + 1);
        assert_eq!(error_message(r#"{"error":"model not found"}"#), "model not found");
    }

    #[tokio::test]
    async fn sse_parsing_handles_split_chunks() {
        let chunks: Vec<Result<Vec<u8>, reqwest::Error>> = vec![
            Ok(b"event: message_start\ndata: {\"a\"".to_vec()),
            Ok(b":1}\n\n: keep-alive\n\ndata: [DONE]\r\n\r\n".to_vec()),
        ];
        let events: Vec<_> = sse_events(lines(futures::stream::iter(chunks))).collect().await;
        let events: Vec<_> = events.into_iter().map(Result::unwrap).collect();
        assert_eq!(events, vec![(Some("message_start".into()), "{\"a\":1}".into()), (None, "[DONE]".into())]);
    }
}
