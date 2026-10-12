//! Structured output that survives small models.
//!
//! 1. Ask for JSON (native JSON mode where the provider has one, plus the
//!    schema in the prompt).
//! 2. Extract leniently (code fences, `<think>` blocks, trailing commas).
//! 3. Validate against the JSON Schema.
//! 4. On failure, send the validation errors back to the same model and ask
//!    for a corrected reply (up to `max_repairs` times).
//! 5. Still invalid: move to the next route in the chain.

use serde::de::DeserializeOwned;
use serde_json::Value;

use super::extract_json;
use super::router::{Route, Router, RouterError, RouterEvent};
use super::{ChatMessage, ChatRequest, OutputFormat, Role, Usage};

/// Longest previous reply echoed back in a repair prompt.
const MAX_ECHO_CHARS: usize = 6000;
/// Validation problems listed in a repair prompt.
const MAX_PROBLEMS: usize = 6;

#[derive(Debug, Clone)]
pub struct StructuredOptions {
    /// Short name for the output, e.g. `brief_update`.
    pub name: String,
    pub max_repairs: u32,
    pub temperature: Option<f32>,
    pub max_tokens: Option<u32>,
}

impl StructuredOptions {
    pub fn named(name: &str) -> Self {
        Self { name: name.to_owned(), max_repairs: 2, temperature: Some(0.2), max_tokens: None }
    }
}

#[derive(Debug, Clone)]
pub struct Structured<T> {
    pub value: T,
    pub provider_id: String,
    pub model: String,
    /// Usage summed over the first call and any repair calls.
    pub usage: Usage,
    pub repairs: u32,
}

#[derive(Debug, Clone, thiserror::Error)]
pub enum StructuredError {
    #[error("internal schema error: {0}")]
    BadSchema(String),
    #[error(transparent)]
    Router(RouterError),
    #[error("no AI provider produced a valid {name}: {}", reasons.join("; "))]
    Exhausted { name: String, reasons: Vec<String> },
}

/// Run a structured call and deserialize the validated value into `T`.
pub async fn structured_call<T: DeserializeOwned>(
    router: &Router,
    routes: &[Route],
    messages: Vec<ChatMessage>,
    schema: &Value,
    options: &StructuredOptions,
) -> Result<Structured<T>, StructuredError> {
    let validator = jsonschema::validator_for(schema).map_err(|e| StructuredError::BadSchema(e.to_string()))?;
    let check = |reply: &str| -> Result<T, Vec<String>> {
        let value = extract_json(reply).map_err(|e| vec![format!("the reply is not valid JSON ({e})")])?;
        let problems: Vec<String> = validator
            .iter_errors(&value)
            .take(MAX_PROBLEMS)
            .map(|e| {
                let path = e.instance_path().to_string();
                format!("{}: {e}", if path.is_empty() { "(root)" } else { path.as_str() })
            })
            .collect();
        if !problems.is_empty() {
            return Err(problems);
        }
        serde_json::from_value(value).map_err(|e| vec![e.to_string()])
    };

    let request = ChatRequest {
        model: String::new(),
        messages: with_schema_instructions(messages, schema),
        temperature: options.temperature,
        max_tokens: options.max_tokens,
        format: OutputFormat::JsonSchema { name: options.name.clone(), schema: schema.clone() },
    };

    let mut reasons = Vec::new();
    let mut start = 0;
    while start < routes.len() {
        let answer = match router.chat(&routes[start..], &request).await {
            Ok(a) => a,
            Err(RouterError::AllFailed(failures)) => {
                reasons.extend(failures.iter().map(|f| format!("{} ({}): {}", f.provider_id, f.model, f.error)));
                break;
            }
            Err(e) => return Err(StructuredError::Router(e)),
        };
        let index = start + answer.route_index;
        let route = &routes[index];
        let mut usage = answer.response.usage;
        let mut reply = answer.response.content;
        let mut conversation = request.messages.clone();

        for repair in 0..=options.max_repairs {
            let problems = match check(&reply) {
                Ok(value) => {
                    return Ok(Structured {
                        value,
                        provider_id: route.provider_id().to_owned(),
                        model: route.model.clone(),
                        usage,
                        repairs: repair,
                    });
                }
                Err(p) => p,
            };
            tracing::debug!(provider = route.provider_id(), ?problems, "invalid structured output");
            if repair == options.max_repairs {
                reasons.push(format!(
                    "{} ({}): invalid output: {}",
                    route.provider_id(),
                    route.model,
                    problems.join(", ")
                ));
                break;
            }
            conversation.push(ChatMessage::assistant(truncate(&reply, MAX_ECHO_CHARS)));
            conversation.push(ChatMessage::user(repair_prompt(&problems)));
            let retry = ChatRequest { messages: conversation.clone(), ..request.clone() };
            match router.chat(std::slice::from_ref(route), &retry).await {
                Ok(a) => {
                    usage = add_usage(usage, a.response.usage);
                    reply = a.response.content;
                }
                Err(RouterError::Cancelled) => return Err(StructuredError::Router(RouterError::Cancelled)),
                Err(e) => {
                    reasons.push(format!("{} ({}): {e}", route.provider_id(), route.model));
                    break;
                }
            }
        }

        if let Some(next) = routes.get(index + 1) {
            router.notify(RouterEvent::FellBack {
                from_provider: route.provider_id().to_owned(),
                from_model: route.model.clone(),
                to_provider: next.provider_id().to_owned(),
                to_model: next.model.clone(),
                reason: "could not produce valid structured output".into(),
            });
        }
        start = index + 1;
    }
    if routes.is_empty() {
        return Err(StructuredError::Router(RouterError::NoRoutes));
    }
    Err(StructuredError::Exhausted { name: options.name.clone(), reasons })
}

/// Append the output contract to the system prompt (or add one).
fn with_schema_instructions(mut messages: Vec<ChatMessage>, schema: &Value) -> Vec<ChatMessage> {
    let instructions = format!(
        "Reply with only one JSON value that matches this JSON Schema. No prose, no code fences.\nSchema: {}",
        serde_json::to_string(schema).unwrap_or_default()
    );
    match messages.iter_mut().find(|m| m.role == Role::System) {
        Some(system) => {
            system.content.push_str("\n\n");
            system.content.push_str(&instructions);
        }
        None => messages.insert(0, ChatMessage::system(instructions)),
    }
    messages
}

fn repair_prompt(problems: &[String]) -> String {
    let list: Vec<String> = problems.iter().map(|p| format!("- {p}")).collect();
    format!(
        "Your reply did not match the required JSON Schema:\n{}\nReply again with only the corrected JSON.",
        list.join("\n")
    )
}

fn truncate(text: &str, max_chars: usize) -> String {
    match text.char_indices().nth(max_chars) {
        Some((i, _)) => format!("{}…", &text[..i]),
        None => text.to_owned(),
    }
}

fn add_usage(a: Usage, b: Usage) -> Usage {
    Usage {
        prompt_tokens: a.prompt_tokens.saturating_add(b.prompt_tokens),
        completion_tokens: a.completion_tokens.saturating_add(b.completion_tokens),
        estimated: a.estimated || b.estimated,
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;

    use serde::Deserialize;
    use serde_json::json;

    use super::*;
    use crate::llm::LlmProvider;
    use crate::llm::router::RetryPolicy;
    use crate::testing::{FakeProvider, RecordingObserver, Step};

    #[derive(Debug, Deserialize, PartialEq)]
    struct Pitch {
        title: String,
        pillars: Vec<String>,
    }

    fn schema() -> Value {
        json!({
            "type": "object",
            "required": ["title", "pillars"],
            "properties": {
                "title": {"type": "string", "minLength": 1},
                "pillars": {"type": "array", "items": {"type": "string"}, "minItems": 3, "maxItems": 5}
            },
            "additionalProperties": false
        })
    }

    fn route(p: &Arc<FakeProvider>) -> Route {
        Route { provider: p.clone(), model: format!("{}-model", p.id()), rpm: None }
    }

    fn router() -> Router {
        Router::new(RetryPolicy::default(), RecordingObserver::new())
    }

    const GOOD: &str = r#"{"title": "Hearth", "pillars": ["cozy", "craft", "seasons"]}"#;

    #[tokio::test]
    async fn valid_first_try_with_fences() {
        let p = FakeProvider::new("gemini", vec![Step::reply(&format!("```json\n{GOOD}\n```"))]);
        let out: Structured<Pitch> = structured_call(
            &router(),
            &[route(&p)],
            vec![ChatMessage::user("pitch?")],
            &schema(),
            &StructuredOptions::named("pitch"),
        )
        .await
        .unwrap();
        assert_eq!(out.value.title, "Hearth");
        assert_eq!(out.repairs, 0);
        // The schema went into the system prompt and the request asked for JSON.
        let req = &p.requests()[0];
        assert!(req.messages[0].content.contains("\"pillars\""));
        assert!(matches!(req.format, OutputFormat::JsonSchema { .. }));
    }

    #[tokio::test]
    async fn repairs_invalid_output_on_same_model() {
        let p = FakeProvider::new(
            "groq",
            vec![Step::reply(r#"{"title": "Hearth", "pillars": ["cozy"]}"#), Step::reply(GOOD)],
        );
        let out: Structured<Pitch> = structured_call(
            &router(),
            &[route(&p)],
            vec![ChatMessage::user("pitch?")],
            &schema(),
            &StructuredOptions::named("pitch"),
        )
        .await
        .unwrap();
        assert_eq!(out.repairs, 1);
        assert_eq!(out.usage.prompt_tokens, 20, "usage includes the repair call");
        let repair_req = &p.requests()[1];
        let last = repair_req.messages.last().unwrap();
        assert!(last.content.contains("/pillars"), "repair prompt names the failing field: {}", last.content);
    }

    #[tokio::test]
    async fn falls_back_after_repairs_run_out() {
        let bad =
            FakeProvider::new("ollama", vec![Step::reply("sure!"), Step::reply("{}"), Step::reply("{\"title\": 1}")]);
        let good = FakeProvider::new("gemini", vec![Step::reply(GOOD)]);
        let out: Structured<Pitch> = structured_call(
            &router(),
            &[route(&bad), route(&good)],
            vec![ChatMessage::user("pitch?")],
            &schema(),
            &StructuredOptions::named("pitch"),
        )
        .await
        .unwrap();
        assert_eq!(out.provider_id, "gemini");
        assert_eq!(bad.calls(), 3);
    }

    #[tokio::test]
    async fn exhausted_lists_reasons() {
        let bad = FakeProvider::new("ollama", vec![Step::reply("nope"), Step::reply("nope"), Step::reply("nope")]);
        let err = structured_call::<Pitch>(
            &router(),
            &[route(&bad)],
            vec![ChatMessage::user("pitch?")],
            &schema(),
            &StructuredOptions::named("pitch"),
        )
        .await
        .unwrap_err();
        assert!(matches!(err, StructuredError::Exhausted { ref reasons, .. } if reasons.len() == 1));
        assert!(err.to_string().contains("valid pitch"));
    }

    #[test]
    fn truncate_is_char_safe() {
        assert_eq!(truncate("héllo", 2), "hé…");
        assert_eq!(truncate("hi", 5), "hi");
    }
}
